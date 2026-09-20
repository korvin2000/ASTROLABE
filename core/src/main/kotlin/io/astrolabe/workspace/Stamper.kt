package io.astrolabe.workspace

import io.astrolabe.id.CanonicalEncoding
import io.astrolabe.id.CapturedStamp
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.Stamp
import io.astrolabe.os.ChangeOrigin
import io.astrolabe.os.FileMode
import io.astrolabe.os.GitStatus
import io.astrolabe.os.StatusEntry
import io.astrolabe.os.UntrackedFiles
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Clock
import java.time.Instant

/** What a stamped path is on disk; a closed vocabulary so the encoding stays injective. */
public enum class EntryType {
    File,
    Symlink,
    Directory,

    /** Tracked at the base commit, absent from the working tree. */
    Deleted,
}

/**
 * One member of the candidate encoding: path, type, mode and content digest (I-05).
 *
 * [digest] is the SHA-256 of the **raw** bytes — the link target for a [EntryType.Symlink], nothing
 * for [EntryType.Deleted]. [sizeBytes] is metadata: it is recorded but does not enter the encoding,
 * because the digest already determines it.
 */
public data class StampEntry(
    val path: String,
    val type: EntryType,
    val mode: FileMode,
    val digest: Digest?,
    val sizeBytes: Long,
) {
    init {
        require(path.isNotEmpty()) { "stamp entry needs a path" }
        require((type == EntryType.Deleted) == (digest == null)) {
            "a deleted entry has no digest and every other entry has one: $path/$type"
        }
    }
}

/**
 * The stamp plus everything that went into it, so a caller can say *why* two candidates differ
 * without recomputing (§8.4).
 *
 * [ignoredCount] records the exclusion that D-44 and §8.4 require to be explicit rather than silent:
 * ignored files are not part of candidate identity, and how many there were is stated. `null` means
 * the count was not taken.
 */
public data class StampReport(
    val stamp: Stamp,
    val baseCommit: String,
    /** Sorted by path: every tracked path that differs from the base commit, staged or not. */
    val trackedDelta: List<StampEntry>,
    /** Sorted by path: every untracked, non-ignored file. */
    val untracked: List<StampEntry>,
    val env: EnvFingerprint,
    val ignoredCount: Int?,
    /** Paths git listed that could not be read when the stamp was taken; recorded, not hashed. */
    val unreadable: List<String> = emptyList(),
) {
    /** Every stamped member by path, tracked delta first. */
    public val members: Map<String, StampEntry> =
        LinkedHashMap<String, StampEntry>().apply {
            trackedDelta.forEach { put(it.path, it) }
            untracked.forEach { put(it.path, it) }
        }

    public val candidateId: io.astrolabe.id.CandidateId get() = stamp.id
}

/**
 * Computes candidate identity (§8.4): base commit + tracked delta hash + untracked manifest hash +
 * environment id. A commit hash alone is insufficient in a dirty tree, and a capture time is never
 * part of the identity (I-05), so equal trees stamped at different moments stamp equal.
 *
 * ## Canonical encodings
 * Both manifests use [CanonicalEncoding] over a **sorted membership** with explicit path, type, mode
 * and content fields, and both are versioned:
 *
 * ```text
 * astrolabe/tracked-delta/v1        astrolabe/untracked-manifest/v1
 * count=<n>                         count=<n>
 * path=<repository-relative path>   … the same four lines per entry …
 * type=file|symlink|directory|deleted
 * mode=100644|100755|120000|040000|000000
 * digest=<64 hex>|deleted
 * ```
 *
 * Entries are sorted by the unsigned UTF-8 bytes of their path, so the order does not depend on a
 * locale, a platform or a JDK collation. Four fixed lines per entry behind a leading count keep the
 * encoding injective even for paths containing `=` or `|`.
 *
 * ## What is excluded
 * Ignored files are excluded and counted ([StampReport.ignoredCount]). Harness state lives outside
 * the source tree by construction (D-44), so there is nothing of the harness's own to exclude; an
 * `.astrolabe/` directory *inside* the tree is repository content and is stamped like any other
 * file. Capture times and observational counters are excluded (I-05).
 */
public class Stamper @JvmOverloads public constructor(
    private val workspace: Workspace,
    private val env: EnvFingerprint,
    /** Counting ignored files costs one `--ignored=matching` status pass over the tree. */
    private val countIgnored: Boolean = true,
) {

    /** The candidate identity of the working tree right now. */
    public fun stamp(): Stamp = report().stamp

    /** [stamp] with the membership and the exclusions that produced it. */
    public fun report(): StampReport {
        val status = workspace.git.status(UntrackedFiles.ALL, includeIgnored = countIgnored)
        val unreadable = ArrayList<String>()
        val tracked = trackedDelta(status, unreadable)
        val untracked = untracked(status, unreadable)
        val baseCommit = baseCommit()
        val stamp = Stamp(
            baseCommit = baseCommit,
            trackedDeltaHash = Digest.ofUtf8(encode("tracked-delta", tracked)),
            untrackedManifestHash = Digest.ofUtf8(encode("untracked-manifest", untracked)),
            envId = env.envId,
        )
        return StampReport(
            stamp = stamp,
            baseCommit = baseCommit,
            trackedDelta = tracked,
            untracked = untracked,
            env = env,
            ignoredCount = if (countIgnored) status.entries.count { it is StatusEntry.Ignored } else null,
            unreadable = unreadable,
        )
    }

    /** [report] with the moment of capture attached as metadata (I-05). */
    public fun capture(clock: Clock): CapturedStampReport = CapturedStampReport(report(), clock.instant())

    public companion object {
        public const val TRACKED_ENCODING_VERSION: Int = 1

        public const val UNTRACKED_ENCODING_VERSION: Int = 1

        private val JSON = Json { prettyPrint = false; encodeDefaults = true }

        /** Paths whose stamped member differs between [a] and [b], sorted. */
        @JvmStatic
        public fun diff(a: StampReport, b: StampReport): Set<String> {
            val changed = LinkedHashSet<String>()
            val names = (a.members.keys + b.members.keys).sortedWith(PATH_ORDER)
            for (path in names) {
                if (a.members[path] != b.members[path]) changed.add(path)
            }
            return changed
        }

        /**
         * Persists [captured] in the `stamps` table. `at` is a metadata column, never part of the
         * key: the same candidate captured twice keeps one row (I-05).
         */
        @JvmStatic
        public fun record(store: Store, ids: Identities, captured: CapturedStamp) {
            val stamp = captured.stamp
            store.db.tx { tx ->
                tx.execute(
                    "INSERT INTO stamps (stamp_id, work_id, attempt_id, candidate_id, context_id, " +
                        "base_commit, tracked_delta_hash, untracked_manifest_hash, env_id, at, " +
                        "schema_version, created_at, body) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (stamp_id) DO NOTHING",
                    stamp.id.digest.hex,
                    ids.work.value,
                    ids.attempt.value,
                    (ids.candidate ?: stamp.id).digest.hex,
                    ids.context?.value,
                    stamp.baseCommit,
                    stamp.trackedDeltaHash.hex,
                    stamp.untrackedManifestHash.hex,
                    stamp.envId.hex,
                    captured.at.toString(),
                    Migrations.SCHEMA_VERSION,
                    captured.at.toString(),
                    body(captured),
                )
            }
        }

        private fun body(captured: CapturedStamp): JsonElement =
            JSON.encodeToJsonElement(CapturedStamp.serializer(), captured)

        /** Unsigned UTF-8 byte order: locale-, platform- and JDK-independent. */
        @JvmField
        public val PATH_ORDER: Comparator<String> = Comparator { left, right ->
            val a = left.toByteArray(StandardCharsets.UTF_8)
            val b = right.toByteArray(StandardCharsets.UTF_8)
            var i = 0
            while (i < a.size && i < b.size) {
                val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (diff != 0) return@Comparator diff
                i++
            }
            a.size - b.size
        }
    }

    // ------------------------------------------------------------ internals

    private fun baseCommit(): String =
        runCatching { workspace.git.revParse("HEAD").hex }.getOrDefault(Stamp.NO_COMMIT)

    private fun trackedDelta(status: GitStatus, unreadable: MutableList<String>): List<StampEntry> {
        val entries = LinkedHashMap<String, StampEntry>()
        for (entry in status.entries) {
            when (entry) {
                is StatusEntry.Ordinary -> entries[entry.path] = stampEntry(entry.path, entry.worktreeMode, unreadable)
                is StatusEntry.Unmerged -> entries[entry.path] = stampEntry(entry.path, entry.worktreeMode, unreadable)
                is StatusEntry.Renamed -> {
                    entries[entry.path] = stampEntry(entry.path, entry.worktreeMode, unreadable)
                    // A rename removes its origin from the candidate; a copy leaves it in place.
                    if (entry.origin == ChangeOrigin.RENAME &&
                        !Files.exists(workspace.root.resolve(entry.origPath))
                    ) {
                        entries[entry.origPath] = deleted(entry.origPath)
                    }
                }

                is StatusEntry.Untracked, is StatusEntry.Ignored -> Unit
            }
        }
        return entries.values.sortedWith(compareBy(PATH_ORDER) { it.path })
    }

    private fun untracked(status: GitStatus, unreadable: MutableList<String>): List<StampEntry> =
        status.entries.filterIsInstance<StatusEntry.Untracked>()
            .map { stampEntry(it.path, FileMode.ABSENT, unreadable) }
            .filter { it.type != EntryType.Deleted }
            .sortedWith(compareBy(PATH_ORDER) { it.path })

    /**
     * Hashes the raw bytes of [path]. [reportedMode] is what git says the working-tree mode is,
     * which is the only mode that is meaningful on a platform without a POSIX executable bit;
     * [FileMode.ABSENT] means "git did not say", and the mode is then derived from the file itself.
     */
    private fun stampEntry(path: String, reportedMode: FileMode, unreadable: MutableList<String>): StampEntry {
        val resolved = workspace.resolve(path, Intent.Read)
        if (resolved !is PathResolution.Resolved) {
            unreadable.add(path)
            return deleted(path)
        }
        return when (WorkspacePath.kindOf(resolved.real)) {
            PathKind.Missing -> deleted(path)
            PathKind.Symlink -> {
                val target = runCatching { Files.readSymbolicLink(resolved.real).toString() }.getOrNull()
                if (target == null) {
                    unreadable.add(path)
                    deleted(path)
                } else {
                    val bytes = target.replace('\\', '/').toByteArray(StandardCharsets.UTF_8)
                    StampEntry(path, EntryType.Symlink, FileMode.SYMLINK, Digest.of(bytes), bytes.size.toLong())
                }
            }

            PathKind.Directory -> StampEntry(
                path = path,
                type = EntryType.Directory,
                mode = FileMode.TREE,
                digest = Digest.ofUtf8(""),
                sizeBytes = 0,
            )

            else -> {
                val bytes = workspace.bytes(resolved)
                if (bytes == null) {
                    unreadable.add(path)
                    deleted(path)
                } else {
                    StampEntry(
                        path = path,
                        type = EntryType.File,
                        mode = fileMode(resolved, reportedMode),
                        digest = Digest.of(bytes),
                        sizeBytes = bytes.size.toLong(),
                    )
                }
            }
        }
    }

    /**
     * The mode git reports is authoritative where it has one: on Windows there is no executable bit
     * to read, so deriving it from the filesystem would make the same tree stamp differently on the
     * two supported platforms.
     */
    private fun fileMode(resolved: PathResolution.Resolved, reportedMode: FileMode): FileMode = when {
        reportedMode == FileMode.EXECUTABLE || reportedMode == FileMode.REGULAR -> reportedMode
        POSIX && Files.isExecutable(resolved.real) -> FileMode.EXECUTABLE
        else -> FileMode.REGULAR
    }

    /** True where the filesystem carries a POSIX executable bit at all; Windows does not. */
    private val POSIX: Boolean =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun deleted(path: String): StampEntry =
        StampEntry(path, EntryType.Deleted, FileMode.ABSENT, null, 0)

    private fun encode(kind: String, entries: List<StampEntry>): String {
        val fields = ArrayList<Pair<String, String>>(entries.size * 4 + 1)
        fields.add("count" to entries.size.toString())
        for (entry in entries) {
            fields.add("path" to entry.path)
            fields.add("type" to entry.type.name.lowercase())
            fields.add("mode" to entry.mode.octal)
            fields.add("digest" to (entry.digest?.hex ?: "deleted"))
        }
        val version = if (kind == "tracked-delta") TRACKED_ENCODING_VERSION else UNTRACKED_ENCODING_VERSION
        return CanonicalEncoding.encode(kind, version, fields)
    }
}

/** A [StampReport] with the moment it was taken; the time is metadata outside identity (I-05). */
public data class CapturedStampReport(val report: StampReport, val at: Instant) {
    public val captured: CapturedStamp get() = CapturedStamp(report.stamp, at)
}

/** What the environment fingerprint was computed over (D-13); the caller resolves the identities. */
public data class EnvInputs(
    val osName: String = System.getProperty("os.name").orEmpty(),
    val osArch: String = System.getProperty("os.arch").orEmpty(),
    /** Resolved tool, checker and parser identities: `python` → `3.12.4`, `git` → `2.45.1`. */
    val toolVersions: Map<String, String> = emptyMap(),
    /**
     * Effective values of the environment variables that matter. Names alone are insufficient
     * (D-13) and the values are secrets, so only their digests are kept — never the value.
     */
    val environmentValues: Map<String, String> = emptyMap(),
    /** Lock and dependency state: path → digest of the file's bytes. */
    val lockDigests: Map<String, Digest> = emptyMap(),
    val buildFlags: List<String> = emptyList(),
    val runnerPolicyId: String = "",
    val externalFixtureIds: List<String> = emptyList(),
    /**
     * Relevant inputs that could not be resolved. A non-empty list makes `envKnown` false, which
     * blocks cross-candidate reuse (D-13) — it does not block the run.
     */
    val unknownInputs: List<String> = emptyList(),
)

/**
 * The `env_id` component of a stamp (D-13, §8.4).
 *
 * The identity covers OS and architecture, resolved tool/checker/parser versions, **digests** of the
 * relevant effective environment values, lock and dependency state, build flags, the runner policy
 * id and external fixture identities. Unknown relevant inputs leave [envKnown] false: the
 * fingerprint is still computed and still separates candidates, but a receipt taken under it may not
 * be reused for a different candidate.
 *
 * Canonical encoding `astrolabe/env/v1`: a leading count per repeated group, maps sorted by name,
 * environment values present only as digests.
 */
public data class EnvFingerprint(
    val envId: Digest,
    val envKnown: Boolean,
    val osName: String,
    val osArch: String,
    val toolVersions: Map<String, String>,
    val environmentValueDigests: Map<String, Digest>,
    val lockDigests: Map<String, Digest>,
    val buildFlags: List<String>,
    val runnerPolicyId: String,
    val externalFixtureIds: List<String>,
    val unknownInputs: List<String>,
) {
    public companion object {
        public const val ENCODING_VERSION: Int = 1

        /** Lock and dependency files D-13 counts, looked for at the workspace root. */
        @JvmField
        public val LOCK_FILE_NAMES: List<String> = listOf(
            "Cargo.lock",
            "Gemfile.lock",
            "Pipfile.lock",
            "composer.lock",
            "go.sum",
            "gradle.lockfile",
            "package-lock.json",
            "pnpm-lock.yaml",
            "poetry.lock",
            "requirements.txt",
            "uv.lock",
            "yarn.lock",
        )

        @JvmStatic
        public fun compute(inputs: EnvInputs): EnvFingerprint {
            val envDigests = inputs.environmentValues.entries
                .sortedBy { it.key }
                .associate { (name, value) -> name to Digest.ofUtf8(value) }
            val fields = ArrayList<Pair<String, String>>()
            fields.add("os" to inputs.osName)
            fields.add("arch" to inputs.osArch)
            appendPairs(fields, "tool", inputs.toolVersions)
            appendPairs(fields, "env", envDigests.mapValues { it.value.hex })
            appendPairs(fields, "lock", inputs.lockDigests.toSortedMap().mapValues { it.value.hex })
            appendList(fields, "flag", inputs.buildFlags)
            fields.add("runner_policy" to inputs.runnerPolicyId)
            appendList(fields, "fixture", inputs.externalFixtureIds)
            appendList(fields, "unknown", inputs.unknownInputs.sorted())
            fields.add("known" to (inputs.unknownInputs.isEmpty()).toString())
            return EnvFingerprint(
                envId = Digest.ofUtf8(CanonicalEncoding.encode("env", ENCODING_VERSION, fields)),
                envKnown = inputs.unknownInputs.isEmpty(),
                osName = inputs.osName,
                osArch = inputs.osArch,
                toolVersions = inputs.toolVersions.toSortedMap(),
                environmentValueDigests = envDigests,
                lockDigests = inputs.lockDigests.toSortedMap(),
                buildFlags = inputs.buildFlags.toList(),
                runnerPolicyId = inputs.runnerPolicyId,
                externalFixtureIds = inputs.externalFixtureIds.toList(),
                unknownInputs = inputs.unknownInputs.toList(),
            )
        }

        /**
         * Digests of the [LOCK_FILE_NAMES] present at the workspace root, plus `requirements*.txt`.
         * The files are read through the path contract, raw, with no conversion.
         */
        @JvmStatic
        public fun lockDigests(workspace: Workspace): Map<String, Digest> {
            val found = sortedMapOf<String, Digest>()
            val names = LinkedHashSet(LOCK_FILE_NAMES)
            runCatching {
                Files.newDirectoryStream(workspace.root, "requirements*.txt").use { stream ->
                    stream.forEach { names.add(it.fileName.toString()) }
                }
            }
            for (name in names) {
                val resolved = workspace.resolve(name, Intent.Read)
                if (resolved !is PathResolution.Resolved) continue
                if (!Files.isRegularFile(resolved.real, LinkOption.NOFOLLOW_LINKS)) continue
                workspace.bytes(resolved)?.let { found[resolved.relative] = Digest.of(it) }
            }
            return found
        }

        private fun appendPairs(fields: MutableList<Pair<String, String>>, group: String, values: Map<String, String>) {
            val sorted = values.toSortedMap()
            fields.add("${group}s" to sorted.size.toString())
            for ((name, value) in sorted) {
                fields.add("$group.name" to name)
                fields.add("$group.value" to value)
            }
        }

        private fun appendList(fields: MutableList<Pair<String, String>>, group: String, values: List<String>) {
            fields.add("${group}s" to values.size.toString())
            for (value in values) fields.add(group to value)
        }
    }
}
