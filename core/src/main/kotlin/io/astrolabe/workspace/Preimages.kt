package io.astrolabe.workspace

import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.InstantSerializer
import io.astrolabe.os.Os
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * One saved preimage: the bytes a path held before an edit, addressed in `blobs/recovery/`.
 *
 * [versionAfter] is filled in by the edit tool once the write landed. Until it is, `revert:#id` has
 * nothing to compare current bytes against and refuses rather than guessing (§9.3).
 */
@Serializable
public data class Preimage(
    val editId: String,
    val path: String,
    val versionBefore: FileVersion,
    val preimageDigest: Digest,
    val versionAfter: FileVersion? = null,
    @Serializable(with = InstantSerializer::class) val savedAt: Instant,
) {
    init {
        require(editId.isNotBlank()) { "a preimage needs an edit id" }
        require(path.isNotEmpty()) { "a preimage needs a path" }
    }
}

/**
 * The bounded diff receipt a revert produces (§9.2, §9.3). [addedLines] and [removedLines] count the
 * lines of the changed region after the common prefix and suffix are trimmed — a deterministic,
 * linear measure of the region that moved, not a minimal edit script.
 */
public data class DiffReceipt(
    val path: String,
    val versionBefore: FileVersion,
    val versionAfter: FileVersion,
    val addedLines: Int,
    val removedLines: Int,
)

/** What `revert:#id` did, or why it refused (§9.3). */
public sealed interface RevertResult {
    public data class Reverted(val receipt: DiffReceipt) : RevertResult

    /** FX-05: current bytes are not the postimage this edit produced, so the inverse is refused. */
    public data class Diverged(val expected: FileVersion?, val actual: FileVersion?) : RevertResult

    public data class Refused(val reason: String, val rejection: PathResolution.Rejected? = null) : RevertResult
}

/** The write an edit performs, so [Preimages.saveThenWrite] can make the ordering structural. */
public fun interface WriteStep {
    public fun write()
}

/**
 * Exact preimages in protected recovery storage (D-14) and the version-checked inverse `revert:#id`
 * of §9.3.
 *
 * **Ordering.** A preimage is recorded *before* mutation, never after discovering the diff (§9.2).
 * [saveThenWrite] makes that structural: the blob is published and the record exists before the
 * write runs, so a crash between the two leaves recoverable bytes rather than an unrecoverable file.
 *
 * **Guarded inverse.** [revert] refuses when the current bytes are not the postimage this edit
 * produced: another edit, a formatter or the user has moved the file since, and restoring the
 * preimage would silently discard that work. Reversibility is what lets the model experiment (F6),
 * and it stops at the point where it would overwrite someone else.
 *
 * The records are held in memory keyed by `(editId, path)`; the *bytes* are durable in
 * `blobs/recovery/` from the moment [save] returns. Journaling the records belongs to the evidence
 * store (P1.4).
 */
public class Preimages(
    private val workspace: Workspace,
    private val blobs: BlobStore,
    private val ids: Identities,
    private val clock: Clock,
) {

    private val saved = ConcurrentHashMap<Key, Preimage>()

    /**
     * Publishes [bytes] as the preimage of [path] at [version] and records it. The blob is
     * unredacted and lands in the restricted recovery area (D-14).
     */
    public fun save(editId: String, path: String, version: FileVersion, bytes: ByteArray): Preimage {
        val digest = blobs.put(bytes, BlobKind.PREIMAGE, ids, recovery = true)
        val preimage = Preimage(
            editId = editId,
            path = path,
            versionBefore = version,
            preimageDigest = digest,
            savedAt = clock.instant(),
        )
        saved[Key(editId, path)] = preimage
        return preimage
    }

    /**
     * [save] and only then [write]. The preimage exists before any byte of the file moves; if
     * [write] throws, the preimage stays and the edit is recoverable.
     */
    public fun saveThenWrite(
        editId: String,
        path: String,
        version: FileVersion,
        bytes: ByteArray,
        write: WriteStep,
    ): Preimage {
        val preimage = save(editId, path, version, bytes)
        write.write()
        return preimage
    }

    /** Records the version the edit produced; `revert:#id` compares current bytes against it. */
    public fun recordPostimage(editId: String, path: String, versionAfter: FileVersion): Preimage {
        val key = Key(editId, path)
        val existing = saved[key] ?: throw IllegalStateException("no preimage saved for edit '$editId' on '$path'")
        val updated = existing.copy(versionAfter = versionAfter)
        saved[key] = updated
        return updated
    }

    /** The preimage recorded for this edit and path, if any. */
    public fun of(editId: String, path: String): Preimage? = saved[Key(editId, path)]

    /** Every preimage recorded for [editId], in the order the paths sort. */
    public fun of(editId: String): List<Preimage> =
        saved.values.filter { it.editId == editId }.sortedWith(compareBy(Stamper.PATH_ORDER) { it.path })

    /** The exact bytes saved for [preimage]. */
    public fun bytesOf(preimage: Preimage): ByteArray = blobs.get(preimage.preimageDigest)

    /**
     * The version-checked inverse of one edit (§9.3): writes the preimage back when the file still
     * holds this edit's postimage, and refuses otherwise. Produces a [DiffReceipt]; the inline
     * syntax check that accompanies it belongs to the edit tool (D-10, P1.6.4).
     */
    public fun revert(editId: String, path: String, os: Os): RevertResult {
        val preimage = of(editId, path)
            ?: return RevertResult.Refused("no preimage recorded for edit '$editId' on '$path'")
        val expected = preimage.versionAfter
            ?: return RevertResult.Refused("edit '$editId' on '$path' recorded no postimage; nothing to check against")

        val resolved = workspace.resolve(path, Intent.Mutate)
        if (resolved is PathResolution.Rejected) {
            return RevertResult.Refused("the path contract refused '$path': ${resolved.reason}", resolved)
        }
        val current = workspace.bytes(resolved as PathResolution.Resolved)
            ?: return RevertResult.Diverged(expected, null)
        val actual = FileVersion.of(current)
        if (actual != expected) return RevertResult.Diverged(expected, actual)

        val bytes = bytesOf(preimage)
        os.replaceFileAtomically(resolved.real, bytes)
        // D-47: revalidation confirms the path still names the same object; it is not a
        // compare-and-replace against an external writer, and never claims to be.
        val after = workspace.paths.revalidate(resolved)
        if (after is PathResolution.Rejected) {
            return RevertResult.Refused("'$path' changed identity during publication: ${after.detail}", after)
        }
        val counts = changedRegion(current, bytes)
        return RevertResult.Reverted(
            DiffReceipt(
                path = path,
                versionBefore = expected,
                versionAfter = preimage.versionBefore,
                addedLines = counts.first,
                removedLines = counts.second,
            ),
        )
    }

    private data class Key(val editId: String, val path: String)

    public companion object {
        /**
         * `(added, removed)` for the region that differs after trimming the common prefix and
         * suffix of the two line sequences. Deterministic and linear; it does not claim to be the
         * minimal edit script.
         */
        @JvmStatic
        public fun changedRegion(before: ByteArray, after: ByteArray): Pair<Int, Int> {
            val old = String(before, StandardCharsets.UTF_8).split("\n")
            val new = String(after, StandardCharsets.UTF_8).split("\n")
            var head = 0
            while (head < old.size && head < new.size && old[head] == new[head]) head++
            var tail = 0
            while (tail < old.size - head && tail < new.size - head &&
                old[old.size - 1 - tail] == new[new.size - 1 - tail]
            ) {
                tail++
            }
            return (new.size - head - tail) to (old.size - head - tail)
        }
    }
}
