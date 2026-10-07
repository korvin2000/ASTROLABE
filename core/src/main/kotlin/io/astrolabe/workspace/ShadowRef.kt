package io.astrolabe.workspace

import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.InstantSerializer
import io.astrolabe.id.WorkId
import io.astrolabe.os.FileMode
import io.astrolabe.os.Git
import io.astrolabe.os.Identity
import io.astrolabe.os.IndexEntry
import io.astrolabe.os.ObjectId
import io.astrolabe.os.Os
import io.astrolabe.os.RefUpdateRejected
import io.astrolabe.os.TreeEntryKind
import io.astrolabe.store.BlobKind
import io.astrolabe.store.MissingBlob
import io.astrolabe.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Instant

/** One recorded shadow snapshot; a persisted turn to commit row, so selection needs no history walk. */
@Serializable
public data class SnapshotRecord(
    val turn: Int,
    /** The shadow commit that indexes this snapshot. */
    val commit: String,
    /** Digest of the manifest blob; the manifest, not the commit, is the authority (D-53). */
    val manifestBlob: Digest,
    val manifestDigest: Digest,
    val stampId: CandidateId,
    @Serializable(with = InstantSerializer::class) val capturedAt: Instant,
)

/** The persisted `turn → commit` index; the file the store keeps beside its blobs. */
@Serializable
internal data class ShadowIndex(val ref: String, val records: List<SnapshotRecord> = emptyList())

/** Restoring a turn either happens, refuses because the human moved on, or refuses by name. */
public sealed interface RestoreResult {
    public data class Restored(
        val written: List<String>,
        val deleted: List<String>,
        val limitations: List<String> = emptyList(),
    ) : RestoreResult

    /** FX-05: current bytes differ from the last snapshot's postimage, so the inverse is refused. */
    public data class Divergent(val paths: List<String>) : RestoreResult

    public data class Refused(
        val reason: String,
        val rejections: List<PathResolution.Rejected> = emptyList(),
    ) : RestoreResult
}

/** The result of exporting a candidate and checking it against its manifest (D-53). */
public data class MaterializeResult(
    val root: Path,
    val files: List<String>,
    /** Manifest entries whose exported bytes hashed to the recorded digest. */
    val verified: Int,
    /** Tree or manifest paths whose bytes, type or mode did not match; non-empty means do not check it. */
    val mismatches: List<String>,
    val limitations: List<String> = emptyList(),
) {
    public val ok: Boolean get() = mismatches.isEmpty()
}

/** A raw blob git stored under an id other than the one its bytes hash to — never papered over. */
public class SnapshotIntegrityError(message: String) : IllegalStateException(message)

/**
 * `refs/astrolabe/<work>/<attempt>/<workspace>/head` — a snapshot after every mutating turn, with
 * no `reset`, no `clean`, no `stash` and no user ref, index or branch touched (§4.6, §9.3).
 *
 * Selecting a turn is a direct lookup in a persisted `turn → commit` index beside the store, never a
 * walk of the commit chain: [record] costs one small read whatever the turn number is.
 *
 * ## What a snapshot is
 * A [Snapshot] manifest of raw bytes, types and modes with exact recovery blobs (D-53). The Git side
 * only indexes it: every manifest byte sequence is written with `git hash-object --no-filters -w`
 * and the returned id is checked against the id those bytes must have, a temporary index is
 * initialised deliberately — the unchanged tracked files from `git ls-files -s`, the changed ones
 * from the manifest — and `write-tree` / `commit-tree` / `update-ref` with the expected old id turn
 * it into a commit. No `.gitattributes` filter runs at any point, and none can change a snapshot
 * byte (I-12).
 *
 * ## Snapshot 0
 * Turn 0 is the captured dirty state: `revert:turn:N` therefore never crosses the initial
 * dirty-state record, because there is nothing before it to cross into.
 *
 * ## Restoring
 * [restore] writes bytes back per file and is guarded per file against divergent current content
 * (FX-05): if the human changed a file since the last snapshot, the restore refuses and names the
 * files instead of overwriting them. The `git` side is never asked to check anything out.
 */
public class ShadowRef @JvmOverloads public constructor(
    public val work: WorkId,
    public val attempt: AttemptId,
    private val workspace: Workspace,
    private val store: Store,
    private val dirtyState: DirtyState,
    private val os: Os,
    private val clock: Clock,
    private val identity: Identity = HARNESS_IDENTITY,
) {

    private val ids = Identities(work = work, attempt = attempt)

    /** The full ref name this instance owns; nothing else ever writes it. */
    public val ref: String = "refs/astrolabe/${work.value}/${attempt.value}/${workspace.id.value}/head"

    private val stateFile: Path = store.layout.candidates
        .resolve("shadow")
        .resolve(work.value)
        .resolve(attempt.value)
        .resolve("${workspace.id.value}.json")

    private val tempIndex: Path = store.layout.candidates
        .resolve("shadow")
        .resolve(work.value)
        .resolve(attempt.value)
        .resolve("${workspace.id.value}.index")

    private val pendingFile: Path = stateFile.resolveSibling("${stateFile.fileName}.pending")

    init {
        for (component in listOf(work.value, attempt.value, workspace.id.value)) {
            require(!component.startsWith(".") && !component.endsWith(".lock") && !component.contains("..")) {
                "'$component' cannot be a git ref component in $ref"
            }
        }
    }

    // --------------------------------------------------------------- reading

    /** Every recorded snapshot, oldest first. */
    public fun records(): List<SnapshotRecord> = readIndex().records

    /** The record for [turn], selected directly from the index rather than by walking history. */
    public fun record(turn: Int): SnapshotRecord? = readIndex().records.firstOrNull { it.turn == turn }

    /** The manifest of [turn], read back from its blob. */
    public fun manifest(turn: Int): Snapshot? =
        record(turn)?.let { Snapshot.decode(blob(it.manifestBlob)) }

    /** The commit the ref points at right now, or `null` when no snapshot has been taken. */
    public fun head(): ObjectId? = workspace.git.readRef(ref)

    // -------------------------------------------------------------- writing

    /**
     * Records [initial] — the captured dirty state — as snapshot 0. Refuses to run twice: turn 0 is
     * the floor `revert` may never cross, so silently replacing it would move that floor.
     */
    public fun open(initial: Snapshot): SnapshotRecord {
        check(record(0) == null) { "snapshot 0 already exists for $ref" }
        require(initial.turn == 0) { "snapshot 0 must carry turn 0, got ${initial.turn}" }
        return commit(initial)
    }

    /** Captures the working tree and records it as snapshot [turn]. */
    public fun snapshot(turn: Int): SnapshotRecord {
        require(turn > 0) { "turn 0 is the captured dirty state; use open()" }
        check(record(turn) == null) { "snapshot $turn already exists for $ref" }
        return commit(dirtyState.capture(turn))
    }

    /** Records an already-built [manifest] as its own turn, for callers that captured it earlier. */
    public fun snapshot(manifest: Snapshot): SnapshotRecord {
        check(record(manifest.turn) == null) { "snapshot ${manifest.turn} already exists for $ref" }
        return commit(manifest)
    }

    // ------------------------------------------------------------ restoring

    /** The exact write/delete set used by restore, including clean files absent from the dirty manifest. */
    internal fun restorePaths(turn: Int): List<String> {
        val target = record(turn) ?: return emptyList()
        val latest = records().lastOrNull() ?: return emptyList()
        return changedPaths(treeOf(workspace.git, ObjectId.parse(target.commit)), treeOf(workspace.git, ObjectId.parse(latest.commit)))
    }

    /**
     * Restores the working tree to snapshot [turn], writing only the files that differ from the
     * last snapshot and refusing the whole operation when any of them diverged (FX-05).
     *
     * Never runs `checkout`, `reset`, `clean` or `stash`; the user's refs, index and stash are not
     * read for this and not written.
     */
    public fun restore(turn: Int): RestoreResult {
        val target = record(turn) ?: return RestoreResult.Refused("no snapshot for turn $turn on $ref")
        val latest = records().lastOrNull() ?: return RestoreResult.Refused("no snapshot recorded on $ref")
        val git = workspace.git
        val targetTree = treeOf(git, ObjectId.parse(target.commit))
        val latestTree = treeOf(git, ObjectId.parse(latest.commit))
        val targetManifest = manifest(turn)

        val touched = changedPaths(targetTree, latestTree)

        // FX-05 guard. The guarded set is wider than the set about to be written: it also covers
        // every path the two manifests name, so restoring the newest turn after a human edit
        // refuses instead of quietly finding nothing to do. Paths outside both manifests that are
        // identical in both trees are unrelated user work and are deliberately not examined (§9.3).
        val guarded = LinkedHashSet<String>(touched)
        guarded += targetManifest?.paths.orEmpty()
        guarded += manifest(latest.turn)?.paths.orEmpty()
        val divergent = guarded.sortedWith(Stamper.PATH_ORDER).filter { path ->
            val expected = latestTree[path]
            currentDigest(path) != expected?.let { digestOfBlob(git, it.id) } ||
                (expected != null && !matchesMode(workspace.paths, path, expected.mode, workspace.fileModeTrusted))
        }
        if (divergent.isNotEmpty()) return RestoreResult.Divergent(divergent)
        if (touched.isEmpty()) return RestoreResult.Restored(emptyList(), emptyList())

        // §9.1: every path is preflighted before the first write, so a refusal never leaves the
        // tree half restored.
        val resolutions = LinkedHashMap<String, PathResolution.Resolved>()
        val rejections = ArrayList<PathResolution.Rejected>()
        for (path in touched) {
            when (val resolved = workspace.resolve(path, Intent.Mutate)) {
                is PathResolution.Resolved -> resolutions[path] = resolved
                is PathResolution.Rejected -> rejections.add(resolved)
            }
        }
        if (rejections.isNotEmpty()) {
            return RestoreResult.Refused("the path contract refused ${rejections.size} path(s)", rejections)
        }

        val written = ArrayList<String>()
        val deleted = ArrayList<String>()
        val limitations = ArrayList<String>()
        for (path in touched) {
            val resolved = resolutions.getValue(path)
            val wanted = targetTree[path]
            if (wanted == null) {
                Files.deleteIfExists(resolved.real)
                deleted.add(path)
                continue
            }
            val fromManifest = targetManifest?.entry(path)?.takeIf { it.present }?.digest
            val bytes = if (fromManifest != null) {
                blob(fromManifest)
            } else {
                limitations.add(
                    "'$path' was clean at turn $turn, so its bytes come from the repository object " +
                        "store; a configured smudge filter would make those differ from the checkout (I-12)",
                )
                git.catFile(wanted.id)
            }
            if (wanted.mode == FileMode.SYMLINK) {
                if (!writeSymlink(resolved.real, String(bytes, StandardCharsets.UTF_8))) {
                    throw java.io.IOException("symlink restore failed for '$path'; publication effects unknown")
                }
            } else {
                os.replaceFileAtomically(resolved.real, bytes)
                applyMode(resolved.real, wanted.mode)
            }
            written.add(path)
        }
        return RestoreResult.Restored(written, deleted, limitations)
    }

    /**
     * Exports the candidate of [turn] into [dir] and verifies every manifest entry against the bytes
     * that landed there (D-53: a materialized candidate is checked against its manifest *before*
     * checks run on it).
     *
     * Files outside the manifest come from the repository object store; that limit is reported, not
     * hidden, because a configured smudge filter would make those bytes differ from a checkout.
     */
    public fun materialize(turn: Int, dir: Path): MaterializeResult {
        val target = record(turn) ?: throw IllegalArgumentException("no snapshot for turn $turn on $ref")
        val manifest = manifest(turn) ?: throw IllegalStateException("snapshot $turn has no manifest blob")
        val git = workspace.git
        val tree = treeOf(git, ObjectId.parse(target.commit))
        Files.createDirectories(dir)
        val exported = WorkspacePath.of(dir, ProtectedPaths(emptySet(), emptySet(), emptySet()))
        val files = ArrayList<String>()
        val limitations = ArrayList<String>()
        val mismatches = linkedSetOf<String>()
        var fromObjectStore = 0

        for ((path, entry) in tree.entries.sortedWith(compareBy(Stamper.PATH_ORDER) { it.key })) {
            val resolved = exported.resolve(path, Intent.Mutate)
            if (resolved !is PathResolution.Resolved) {
                limitations.add("'$path' refused by the export path contract: $resolved")
                mismatches.add(path)
                continue
            }
            val manifestDigest = manifest.entry(path)?.takeIf { it.present }?.digest
            val bytes = if (manifestDigest != null) blob(manifestDigest) else {
                fromObjectStore++
                git.catFile(entry.id)
            }
            Files.createDirectories(resolved.real.parent)
            if (entry.mode == FileMode.SYMLINK) {
                if (!writeSymlink(resolved.real, String(bytes, StandardCharsets.UTF_8))) {
                    limitations.add("'$path' exported as a regular file; this host refuses symlinks (§9.5)")
                    Files.write(resolved.real, bytes)
                }
            } else {
                Files.write(resolved.real, bytes)
                applyMode(resolved.real, entry.mode)
            }
            files.add(path)
            val digest = runCatching {
                if (entry.mode == FileMode.SYMLINK) Digest.of(linkTarget(resolved.real).toByteArray(StandardCharsets.UTF_8))
                else digestOfFile(resolved.real)
            }.getOrNull()
            if (digest != Digest.of(bytes) || !matchesMode(exported, path, entry.mode)) mismatches.add(path)
        }
        if (fromObjectStore > 0) {
            limitations.add(
                "$fromObjectStore file(s) outside the manifest were exported from the repository " +
                    "object store; the manifest is the authority only for the paths it names (I-12)",
            )
        }

        var verified = 0
        for (entry in manifest.entries) {
            val file = dir.resolve(entry.path)
            if (!entry.present) {
                if (Files.exists(file, NOFOLLOW_LINKS)) mismatches.add(entry.path)
                continue
            }
            val actual = runCatching {
                val resolved = exported.resolveCapture(entry.path)
                check(resolved is PathResolution.Resolved)
                when (entry.kind) {
                    SnapshotEntryKind.Symlink -> {
                        check(resolved.kind == PathKind.Symlink)
                        Digest.of(linkTarget(resolved.real).toByteArray(StandardCharsets.UTF_8))
                    }
                    SnapshotEntryKind.File -> {
                        check(resolved.kind == PathKind.Regular)
                        digestOfFile(resolved.real)
                    }
                    SnapshotEntryKind.Deleted -> null
                }
            }.getOrNull()
            if (actual == entry.digest && matchesMode(exported, entry.path, entry.mode)) verified++ else mismatches.add(entry.path)
        }
        return MaterializeResult(dir, files, verified, mismatches.toList(), limitations)
    }

    // ------------------------------------------------------------ internals

    private fun commit(manifest: Snapshot): SnapshotRecord {
        val git = workspace.git
        val index = readIndex()
        val last = index.records.lastOrNull()
        val previous = last?.let { ObjectId.parse(it.commit) }

        val entries = LinkedHashMap<String, IndexEntry>()
        for (row in git.lsFiles()) {
            if (row.stage != 0) continue
            if (row.mode == FileMode.GITLINK) {
                throw SnapshotIntegrityError("'${row.path}' is a submodule reference; D-53 refuses to index one")
            }
            entries[row.path] = IndexEntry(row.mode, row.id, row.path)
        }
        val objects = objectIds(manifest, last)
        for (entry in manifest.entries) {
            if (!entry.present) {
                entries.remove(entry.path)
                continue
            }
            entries[entry.path] = IndexEntry(entry.mode, objects.getValue(entry.digest!!), entry.path)
        }

        Files.createDirectories(tempIndex.parent)
        git.updateIndex(tempIndex, entries.values.sortedWith(compareBy(Stamper.PATH_ORDER) { it.path }))
        val tree = git.writeTree(tempIndex)
        val message = "astrolabe snapshot turn ${manifest.turn}\n\n" +
            "manifest ${manifest.manifestDigest.hex}\nstamp ${manifest.stampId.digest.hex}\n"
        val commit = git.commitTree(tree, listOfNotNull(previous), message, identity)
        val manifestBlob = store.blobs.put(
            Snapshot.encodeToBytes(manifest),
            BlobKind.PACKET,
            ids,
            recovery = true,
        )
        val record = SnapshotRecord(
            turn = manifest.turn,
            commit = commit.hex,
            manifestBlob = manifestBlob,
            manifestDigest = manifest.manifestDigest,
            stampId = manifest.stampId,
            capturedAt = manifest.capturedAt,
        )
        val next = ShadowIndex(ref, index.records + record)
        // Recovery material and the intended index must be durable before moving the ref.
        writeIndex(next, pendingFile)
        try {
            git.updateRef(ref, commit, previous)
        } catch (conflict: RefUpdateRejected) {
            Files.deleteIfExists(pendingFile)
            throw conflict
        }
        writeIndex(next)
        Files.deleteIfExists(pendingFile)
        Files.deleteIfExists(tempIndex)
        indexed = Indexed(commit.hex, objects)
        return record
    }

    /** The objects of the commit this instance last wrote, by content digest; the next snapshot starts from them. */
    private class Indexed(val commit: String, val objects: Map<Digest, ObjectId>)

    @Volatile
    private var indexed: Indexed? = null

    /**
     * WD-01: the git object of every present entry of [manifest], by content digest. Content the snapshot [last] already
     * indexed keeps its object — its manifest names the digest and its tree the object, so nothing is hashed again. The
     * rest is looked up in the object database with one `cat-file --batch-check`, and only what git lacks is written,
     * with one `hash-object -w --stdin-paths` over the recovery blobs. Each such id is the one the raw bytes must have,
     * computed from the capture's own read (§4.3, I-12).
     */
    private fun objectIds(manifest: Snapshot, last: SnapshotRecord?): Map<Digest, ObjectId> {
        val wanted = manifest.entries.mapNotNullTo(LinkedHashSet()) { it.digest }
        val objects = HashMap<Digest, ObjectId>(wanted.size * 2)
        if (last != null) {
            val known = indexed?.takeIf { it.commit == last.commit }?.objects ?: run {
                val before = Snapshot.decode(blob(last.manifestBlob))
                val tree = treeOf(workspace.git, ObjectId.parse(last.commit))
                before.entries.mapNotNull { e -> e.digest?.let { d -> tree[e.path]?.let { d to it.id } } }.toMap()
            }
            for (digest in wanted) known[digest]?.let { objects[digest] = it }
        }
        val rest = wanted.filter { it !in objects }
        if (rest.isEmpty()) return objects
        val expected = rest.associateWith { expectedId(it) }
        val present = workspace.git.presentObjects(expected.values)
        val missing = rest.filter { expected.getValue(it) !in present }
        val files = missing.map { store.blobs.file(it) ?: throw MissingBlob(it, "recovery blob ${it.hex} is not on disk") }
        val stored = workspace.git.hashObjects(files)
        // T-22 (§7.2): git read each recovery blob it hashed; those reads are the snapshot's, counted as blob reads.
        files.forEach { workspace.blobRead(Files.size(it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        missing.forEachIndexed { i, digest ->
            val want = expected.getValue(digest)
            val got = stored[i]
            if (got != want) {
                throw SnapshotIntegrityError(
                    "git stored the bytes of ${digest.hex} as $got but they hash to $want — a content filter ran during snapshotting (D-53, I-12)",
                )
            }
        }
        for (digest in rest) objects[digest] = expected.getValue(digest)
        return objects
    }

    /**
     * The object id the bytes of [digest] must have: the capture's, else hashed from the recovery blob (counted) with the
     * repository's object hash. §4.3: a stored id that differs means a filter ran despite `--no-filters` (I-12).
     */
    private fun expectedId(digest: Digest): ObjectId =
        ObjectId.parse(dirtyState.objectId(digest) ?: ContentCache.objectId(workspace.objectAlgorithm, blob(digest)))

    private fun changedPaths(target: Map<String, TreeBlob>, latest: Map<String, TreeBlob>): List<String> =
        (target.keys + latest.keys).filter { target[it] != latest[it] }.sortedWith(Stamper.PATH_ORDER)

    private fun treeOf(git: Git, commit: ObjectId): Map<String, TreeBlob> =
        git.lsTree(commit, recursive = true)
            .filter { it.kind == TreeEntryKind.BLOB }
            .associate { it.path to TreeBlob(it.mode, it.id) }

    private data class TreeBlob(val mode: FileMode, val id: ObjectId)

    private fun digestOfBlob(git: Git, id: ObjectId): Digest = Digest.of(git.catFile(id))

    /** A store blob read for this tree, counted (§7.2). */
    private fun blob(digest: Digest): ByteArray = store.blobs.get(digest).also { workspace.blobRead(it.size) }

    private fun digestOfFile(file: Path): Digest = Digest.of(Files.readAllBytes(file))

    private fun applyMode(path: Path, mode: FileMode) {
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) return
        val permissions = Files.getPosixFilePermissions(path)
        val execute = setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE)
        Files.setPosixFilePermissions(path, if (mode == FileMode.EXECUTABLE) permissions + execute else permissions - execute)
    }

    // D-293: a workspace with core.fileMode=false passes trustExecutable=false; an export directory keeps the strict check.
    private fun matchesMode(paths: WorkspacePath, path: String, mode: FileMode, trustExecutable: Boolean = true): Boolean {
        val resolved = paths.resolveCapture(path) as? PathResolution.Resolved ?: return false
        if (mode == FileMode.SYMLINK) return resolved.kind == PathKind.Symlink
        if (resolved.kind != PathKind.Regular || mode !in setOf(FileMode.REGULAR, FileMode.EXECUTABLE)) return false
        return !trustExecutable || !Files.getFileStore(resolved.real).supportsFileAttributeView("posix") ||
            Files.isExecutable(resolved.real) == (mode == FileMode.EXECUTABLE)
    }

    /** The digest of the working-tree path now, following the same rule the manifest used. */
    private fun currentDigest(path: String): Digest? {
        val resolved = workspace.paths.resolveCapture(path)
        if (resolved !is PathResolution.Resolved) return null
        return when (resolved.kind) {
            PathKind.Missing, PathKind.Directory -> null
            PathKind.Symlink -> runCatching {
                Digest.of(
                    linkTarget(resolved.real)
                        .toByteArray(StandardCharsets.UTF_8),
                )
            }.getOrNull()

            else -> workspace.bytes(resolved)?.let(Digest::of)
        }
    }

    private fun writeSymlink(target: Path, linkTarget: String): Boolean = try {
        Files.deleteIfExists(target)
        Files.createDirectories(target.parent)
        Files.createSymbolicLink(target, Path.of(linkTarget))
        true
    } catch (unsupported: IOException) {
        false
    } catch (unsupported: UnsupportedOperationException) {
        false
    }

    private fun readIndex(): ShadowIndex {
        val index = if (Files.exists(stateFile)) decodeIndex(stateFile) else ShadowIndex(ref)
        check(index.ref == ref) { "shadow index belongs to another ref" }
        if (!Files.exists(pendingFile)) return index
        val pending = decodeIndex(pendingFile)
        check(pending.ref == ref && pending.records.isNotEmpty()) { "invalid pending shadow index" }
        if (pending == index) {
            Files.deleteIfExists(pendingFile)
            return index
        }
        check(pending.records.dropLast(1) == index.records) { "pending shadow index does not extend the current index" }
        val record = pending.records.last()
        val head = workspace.git.readRef(ref)?.hex
        when (head) {
            record.commit -> {
                val manifest = Snapshot.decode(blob(record.manifestBlob))
                check(manifest.turn == record.turn && manifest.manifestDigest == record.manifestDigest &&
                    manifest.stampId == record.stampId) { "pending snapshot manifest is inconsistent" }
                writeIndex(pending)
                Files.deleteIfExists(pendingFile)
                return pending
            }
            index.records.lastOrNull()?.commit -> Files.deleteIfExists(pendingFile)
            else -> throw SnapshotIntegrityError("shadow ref $ref moved outside its pending publication; recovery refused")
        }
        return index
    }

    private fun decodeIndex(path: Path): ShadowIndex =
        JSON.decodeFromString(ShadowIndex.serializer(), Files.readString(path, StandardCharsets.UTF_8))

    private fun writeIndex(index: ShadowIndex, target: Path = stateFile) {
        Files.createDirectories(stateFile.parent)
        os.replaceFileAtomically(
            target,
            JSON.encodeToString(ShadowIndex.serializer(), index).toByteArray(StandardCharsets.UTF_8),
        )
    }

    public companion object {
        /** Author and committer of every shadow commit; never the user's identity. */
        @JvmField
        public val HARNESS_IDENTITY: Identity = Identity("ASTROLABE", "astrolabe@astrolabe.invalid")

        private val JSON = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
