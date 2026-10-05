package io.astrolabe.workspace

import io.astrolabe.id.CandidateId
import io.astrolabe.id.CanonicalEncoding
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.InstantSerializer
import io.astrolabe.os.ChangeOrigin
import io.astrolabe.os.FileMode
import io.astrolabe.os.GitStatus
import io.astrolabe.os.LsFilesEntry
import io.astrolabe.os.StatusCode
import io.astrolabe.os.StatusEntry
import io.astrolabe.os.UnsupportedRepositoryForm
import io.astrolabe.os.UntrackedFiles
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Clock
import java.time.Instant

/** What a manifest entry is; a snapshot records files, symlinks and deletions and nothing else. */
@Serializable
public enum class SnapshotEntryKind {
    File,
    Symlink,

    /** Tracked at the base commit and absent from the working tree at capture time. */
    Deleted,
}

/**
 * One member of a [Snapshot] manifest: raw bytes, type and mode (D-53, I-12).
 *
 * [digest] addresses the **exact recovery blob** in `blobs/recovery/`, written from the bytes read
 * off the working tree. No attribute filter and no end-of-line conversion took part, so restoring
 * from it reproduces the bytes that were read and tested, not the bytes a `git add` would have
 * produced.
 */
@Serializable
public data class SnapshotEntry(
    val path: String,
    val kind: SnapshotEntryKind,
    val mode: FileMode,
    val digest: Digest? = null,
    val sizeBytes: Long = 0,
) {
    init {
        require(path.isNotEmpty()) { "snapshot entry needs a path" }
        require((kind == SnapshotEntryKind.Deleted) == (digest == null)) {
            "only a deleted entry has no recovery blob: $path/$kind"
        }
    }

    public val present: Boolean get() = kind != SnapshotEntryKind.Deleted
}

/**
 * One staged blob of the **user's** index, captured by reading it (`ls-files` + `cat-file`) and
 * never by touching it (D-53). [stage] is 0 for an ordinary staged path and 1/2/3 for the three
 * sides of an unmerged one.
 */
@Serializable
public data class StagedEntry(
    val path: String,
    val mode: FileMode,
    val stage: Int,
    /** The git object name the user's index holds, kept as text so the record stays portable. */
    val indexBlobId: String,
    /** Recovery blob of the exact bytes that object contains. */
    val digest: Digest,
    val sizeBytes: Long,
) {
    init {
        require(stage in 0..3) { "index stage is 0..3, got $stage for $path" }
    }
}

/**
 * The authority on what a candidate's bytes are (D-53): a raw-byte/type/mode manifest plus exact
 * recovery blobs. A Git tree only *indexes* this; it never defines it.
 *
 * [manifestDigest] hashes the canonical encoding `astrolabe/snapshot/v1` — sorted membership with
 * explicit path/kind/mode/digest fields, the staged entries in their own sorted group, and the base
 * commit. [capturedAt] and [turn] are metadata and stay out of it (I-05), so the same dirty tree
 * captured twice yields the same manifest digest.
 */
@Serializable
public data class Snapshot(
    val turn: Int,
    val entries: List<SnapshotEntry>,
    val staged: List<StagedEntry> = emptyList(),
    val baseCommit: String,
    val stampId: CandidateId,
    /** Ignored files present at capture time; excluded from the manifest, counted here (I-12). */
    val ignoredCount: Int = 0,
    /** Paths git listed that could not be read; recorded rather than silently dropped. */
    val unreadable: List<String> = emptyList(),
    @Serializable(with = InstantSerializer::class) val capturedAt: Instant,
) {
    init {
        require(turn >= 0) { "turn must be ≥ 0, got $turn" }
    }

    /** Identity of the captured bytes; excludes [turn] and [capturedAt] (I-05). */
    public val manifestDigest: Digest get() = Digest.ofUtf8(encode())

    public fun entry(path: String): SnapshotEntry? = entries.firstOrNull { it.path == path }

    /** Paths this manifest holds bytes for. */
    public val presentPaths: Set<String>
        get() = entries.filter { it.present }.mapTo(LinkedHashSet()) { it.path }

    /** Every path the manifest speaks about, present or deleted. */
    public val paths: Set<String> get() = entries.mapTo(LinkedHashSet()) { it.path }

    private fun encode(): String {
        val fields = ArrayList<Pair<String, String>>(entries.size * 4 + staged.size * 4 + 3)
        fields.add("base" to baseCommit)
        fields.add("entries" to entries.size.toString())
        for (entry in entries.sortedWith(compareBy(Stamper.PATH_ORDER) { it.path })) {
            fields.add("path" to entry.path)
            fields.add("kind" to entry.kind.name.lowercase())
            fields.add("mode" to entry.mode.octal)
            fields.add("digest" to (entry.digest?.hex ?: "deleted"))
        }
        fields.add("staged" to staged.size.toString())
        for (entry in staged.sortedWith(compareBy(Stamper.PATH_ORDER) { it.path + "" + it.stage })) {
            fields.add("staged.path" to entry.path)
            fields.add("staged.stage" to entry.stage.toString())
            fields.add("staged.mode" to entry.mode.octal)
            fields.add("staged.digest" to entry.digest.hex)
        }
        return CanonicalEncoding.encode("snapshot", ENCODING_VERSION, fields)
    }

    public companion object {
        public const val ENCODING_VERSION: Int = 1

        private val JSON = Json { prettyPrint = false; encodeDefaults = true }

        /** The manifest as the bytes stored in a blob and read back by [decode]. */
        @JvmStatic
        public fun encodeToBytes(snapshot: Snapshot): ByteArray =
            JSON.encodeToString(serializer(), snapshot).toByteArray(StandardCharsets.UTF_8)

        @JvmStatic
        public fun decode(bytes: ByteArray): Snapshot =
            JSON.decodeFromString(serializer(), String(bytes, StandardCharsets.UTF_8))
    }
}

/** Where a change in the final tree came from, for the finish receipt (§4.6). */
public enum class ChangeSource {
    Agent,
    ByRun,
    PreExistingUserChanges,

    /** Changed, but by neither the agent nor a run, and not dirty at campaign open. */
    Unattributed,
}

/**
 * The finish-receipt separation (§4.6): the user's pre-existing modifications are reported apart
 * from what the campaign did, and a change nobody claims is named rather than folded into either.
 */
public data class Separated(
    val agent: Set<String>,
    val byRun: Set<String>,
    val preExistingUserChanges: Set<String>,
    val unattributed: Set<String>,
) {
    public fun sourceOf(path: String): ChangeSource? = when (path) {
        in agent -> ChangeSource.Agent
        in byRun -> ChangeSource.ByRun
        in preExistingUserChanges -> ChangeSource.PreExistingUserChanges
        in unattributed -> ChangeSource.Unattributed
        else -> null
    }
}

private const val CAPTURE_ATTEMPTS = 3
private const val SHA1_HEX = 40
private const val SHA256_HEX = 64

/**
 * The initial dirty-state record (§4.6, D-53, FX-06).
 *
 * At campaign open the working tree is rarely `HEAD`: the user has modified files, staged some of
 * them and left others untracked. Candidates are created from *that* state, `revert` never crosses
 * it, and the final report separates it from the campaign's own work. This class captures it as a
 * [Snapshot] — a raw-byte/type/mode manifest with exact recovery blobs — plus the `s0` stamp.
 *
 * **Byte fidelity (I-12).** Working-tree bytes are read through the path contract with no
 * conversion; staged content is read with `git cat-file`, which applies no smudge filter. A
 * `.gitattributes` `text=auto eol=lf` rule and a configured clean filter therefore change nothing
 * about what is captured, and a restore reproduces CRLF exactly as it was on disk.
 *
 * **Unsupported forms** (submodules, sparse checkout) are refused by name through
 * [UnsupportedRepositoryForm], never partially captured.
 */
public class DirtyState(
    private val workspace: Workspace,
    private val blobs: BlobStore,
    private val stamper: Stamper,
    private val ids: Identities,
    private val clock: Clock,
) {

    /**
     * Captures the working tree as snapshot [turn] — tracked delta, the user's staged content and
     * relevant untracked files — writing every byte to `blobs/recovery/` first (D-14). With [fresh]
     * every file is read from disk instead of trusting the content cache ([Stamper.report]).
     */
    @JvmOverloads
    public fun capture(turn: Int = 0, fresh: Boolean = false): Snapshot {
        workspace.git.unsupportedForms().firstOrNull()?.let {
            throw UnsupportedRepositoryForm(it, "dirty-state capture")
        }
        // D-274: a concurrent writer (dev server, IDE autosave) gets a bounded, immediate retry; the last failure stands.
        // WD-02: the attempts share one capture's reads, so a retry reads again only what moved.
        val reads = ContentCache.Reads()
        var attempt = 1
        while (true) {
            try {
                return captureOnce(turn, attempt, fresh, reads)
            } catch (changed: SnapshotIntegrityError) {
                if (attempt >= CAPTURE_ATTEMPTS) throw changed
                attempt++
            }
        }
    }

    /** Test seam: runs after an attempt's reads and before its integrity recheck, with the attempt number. */
    internal var beforeRecheck: (attempt: Int) -> Unit = {}

    /** Git object ids of captured content by digest, computed from the capture's own read (WD-01); [ShadowRef] indexes with them. */
    private val objectIds = java.util.concurrent.ConcurrentHashMap<Digest, String>()

    /** The git object id of captured content [digest], when a capture of this tree computed it from the bytes it read. */
    internal fun objectId(digest: Digest): String? = objectIds[digest]

    private fun captureOnce(turn: Int, attempt: Int, fresh: Boolean, reads: ContentCache.Reads): Snapshot {
        val status = workspace.git.status(UntrackedFiles.ALL, includeIgnored = true)
        val index = workspace.git.lsFiles()
        val entries = LinkedHashMap<String, SnapshotEntry>()
        val algorithm = when ((status.branch?.oid ?: index.firstOrNull()?.id)?.hex?.length) {
            SHA1_HEX -> "SHA-1"
            SHA256_HEX -> "SHA-256"
            else -> workspace.objectAlgorithm
        }
        val read = { path: String, mode: FileMode -> worktreeEntry(path, mode, fresh, reads, algorithm) }

        for (entry in status.entries) {
            when (entry) {
                is StatusEntry.Ordinary -> read(entry.path, entry.worktreeMode)?.let { entries[entry.path] = it }
                is StatusEntry.Unmerged -> read(entry.path, entry.worktreeMode)?.let { entries[entry.path] = it }
                is StatusEntry.Untracked -> read(entry.path, FileMode.ABSENT)?.let { entries[entry.path] = it }
                is StatusEntry.Renamed -> {
                    read(entry.path, entry.worktreeMode)?.let { entries[entry.path] = it }
                    if (entry.origin == ChangeOrigin.RENAME) {
                        val origin = read(entry.origPath, FileMode.ABSENT)
                        if (origin?.kind == SnapshotEntryKind.Deleted) entries[entry.origPath] = origin
                    }
                }

                is StatusEntry.Ignored -> Unit
            }
        }

        val staged = stagedEntries(status, index)
        // WD-02: the stamp hashes the bytes this capture read and stored, not a second read of them.
        val report = stamper.report(fresh, reads)
        // Include raw differences hidden by Git's clean filters and checkout conversions.
        for ((path, entry) in report.members) {
            if (path !in entries) read(path, entry.mode)?.let { entries[path] = it }
        }
        val captured = entries.mapValues { (_, entry) ->
            val type = when (entry.kind) {
                SnapshotEntryKind.File -> EntryType.File
                SnapshotEntryKind.Symlink -> EntryType.Symlink
                SnapshotEntryKind.Deleted -> EntryType.Deleted
            }
            StampEntry(entry.path, type, entry.mode, entry.digest, entry.sizeBytes)
        }
        val base = status.branch?.let { it.oid?.hex ?: io.astrolabe.id.Stamp.NO_COMMIT }
        // A directory member (nested repository, submodule) carries no bytes to recover.
        val recoverable = report.members.filterValues { it.type != EntryType.Directory }
        beforeRecheck(attempt)
        // D-274/D-374: the recheck reads a file again unless its look proves it unwritten since this capture read it (a
        // change time no tool restores, outside the racy window); a fresh capture rereads from disk, never the shared cache.
        reads.rechecking = true
        val recheck = try {
            stamper.report(fresh, reads).stamp.id
        } finally {
            reads.rechecking = false
        }
        if (captured != recoverable || base != report.baseCommit || recheck != report.candidateId ||
            workspace.git.lsFiles() != index ||
            workspace.git.status(UntrackedFiles.ALL, includeIgnored = true) != status
        ) {
            throw SnapshotIntegrityError("workspace or index changed during dirty-state capture; retry acquisition")
        }
        return Snapshot(
            turn = turn,
            entries = entries.values.sortedWith(compareBy(Stamper.PATH_ORDER) { it.path }),
            staged = staged,
            baseCommit = report.baseCommit,
            stampId = report.stamp.id,
            ignoredCount = status.entries.count { it is StatusEntry.Ignored },
            capturedAt = clock.instant(),
        )
    }

    /** The bytes of [entry], read back from its recovery blob. */
    public fun bytesOf(entry: SnapshotEntry): ByteArray? = entry.digest?.let { blobs.get(it).also { bytes -> workspace.blobRead(bytes.size) } }

    // ------------------------------------------------------------ internals

    /**
     * The user's staged content, for every path whose index side differs from `HEAD`, read with
     * `ls-files` and `cat-file`. Neither command writes the index (D-53): the user's staged state
     * is captured beside the working tree, not merged into it and not disturbed.
     */
    private fun stagedEntries(status: GitStatus, index: List<LsFilesEntry>): List<StagedEntry> {
        val wanted = LinkedHashSet<String>()
        for (entry in status.entries) {
            when (entry) {
                is StatusEntry.Ordinary -> if (entry.index != StatusCode.UNMODIFIED) wanted.add(entry.path)
                is StatusEntry.Renamed -> wanted.add(entry.path)
                is StatusEntry.Unmerged -> wanted.add(entry.path)
                else -> Unit
            }
        }
        if (wanted.isEmpty()) return emptyList()
        val staged = ArrayList<StagedEntry>()
        for (row in index.filter { it.path in wanted }) {
            val bytes = workspace.git.catFile(row.id)
            staged.add(
                StagedEntry(
                    path = row.path,
                    mode = row.mode,
                    stage = row.stage,
                    indexBlobId = row.id.hex,
                    digest = blobs.put(bytes, BlobKind.PREIMAGE, ids, recovery = true),
                    sizeBytes = bytes.size.toLong(),
                ),
            )
        }
        return staged.sortedWith(compareBy(Stamper.PATH_ORDER) { it.path + "" + it.stage })
    }

    private fun worktreeEntry(path: String, reportedMode: FileMode, fresh: Boolean, reads: ContentCache.Reads, algorithm: String): SnapshotEntry? {
        val resolved = workspace.paths.resolveCapture(path)
        if (resolved !is PathResolution.Resolved) {
            throw SnapshotIntegrityError("cannot capture '$path': $resolved")
        }
        return when (resolved.kind) {
            PathKind.Missing -> deleted(path)
            PathKind.Symlink -> {
                val bytes = linkTarget(resolved.real).toByteArray(StandardCharsets.UTF_8)
                val digest = Digest.of(bytes)
                objectIds[digest] = ContentCache.objectId(algorithm, bytes)
                SnapshotEntry(path, SnapshotEntryKind.Symlink, FileMode.SYMLINK,
                    blobs.put(bytes, digest, BlobKind.PREIMAGE, ids, recovery = true), bytes.size.toLong())
            }
            PathKind.Regular -> {
                // One mode rule with the stamp report the recheck compares against (D-293).
                val mode = stamper.fileMode(resolved, reportedMode)
                // D-364: unchanged content already in the recovery store is not read or stored again.
                val known = if (fresh) null else workspace.contents.cached(resolved.real)
                if (known != null && blobs.holds(known.digest, recovery = true)) {
                    known.objectIds[algorithm]?.let { objectIds[known.digest] = it }
                    return SnapshotEntry(path, SnapshotEntryKind.File, mode, known.digest, known.sizeBytes)
                }
                // WD-02: one read per capture — the bytes go to the store under the digest that read produced, once.
                var bytes: ByteArray? = null
                val load = { workspace.bytes(resolved).also { bytes = it } }
                var content = workspace.contents.within(reads, resolved.real, algorithm, fresh = true, load)
                if (content != null && bytes == null && !blobs.holds(content.digest, recovery = true)) {
                    // This capture's stamp read the file first (a raw difference git status hid): its bytes were never stored.
                    content = workspace.contents.load(resolved.real, algorithm, load)
                }
                val got = content ?: throw SnapshotIntegrityError("'$path' disappeared during capture")
                got.objectIds[algorithm]?.let { objectIds[got.digest] = it }
                bytes?.let { blobs.put(it, got.digest, BlobKind.PREIMAGE, ids, recovery = true) }
                SnapshotEntry(path, SnapshotEntryKind.File, mode, got.digest, got.sizeBytes)
            }
            PathKind.Directory -> null
            else -> throw SnapshotIntegrityError("unsupported capture kind ${resolved.kind}: $path")
        }
    }

    private fun deleted(path: String): SnapshotEntry =
        SnapshotEntry(path, SnapshotEntryKind.Deleted, FileMode.ABSENT)

    public companion object {
        /**
         * Splits the final tree into the three buckets the finish receipt needs (§4.6), plus the
         * changes nobody claims.
         *
         * A path the campaign never touched but that was already dirty when [initial] was captured
         * is a **pre-existing user change** and belongs to the user. A path that is dirty at the end
         * and was touched by neither the agent nor a run and was not dirty at open is reported as
         * [ChangeSource.Unattributed] rather than attributed to the user, because attributing it
         * would claim knowledge the harness does not have.
         */
        @JvmStatic
        public fun separate(
            initial: Snapshot,
            final: StampReport,
            agentEdits: Set<String>,
            runTouched: Set<String>,
        ): Separated {
            val agent = agentEdits.toSortedSet(Stamper.PATH_ORDER)
            val byRun = runTouched.filterNot { it in agent }.toSortedSet(Stamper.PATH_ORDER)
            val preExisting = initial.paths
                .filterNot { it in agent || it in byRun }
                .toSortedSet(Stamper.PATH_ORDER)
            val unattributed = final.members.keys
                .filterNot { it in agent || it in byRun || it in preExisting }
                .toSortedSet(Stamper.PATH_ORDER)
            return Separated(agent, byRun, preExisting, unattributed)
        }
    }
}
