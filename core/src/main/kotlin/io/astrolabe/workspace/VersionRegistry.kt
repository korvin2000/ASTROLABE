package io.astrolabe.workspace

import io.astrolabe.evidence.RedactionMask
import io.astrolabe.id.ContextId
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Generation
import io.astrolabe.id.WorkspaceId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bytes read at a consequential boundary, together with the version they actually hash to.
 *
 * [raced] records that the file's metadata moved while it was being read, so the acquisition was
 * retried: the bytes and the version agree with each other, but another writer was active and the
 * caller should treat any decision built on them as provisional (I-05).
 *
 * `equals`/`hashCode`/`toString` are written out because [bytes] is an array.
 */
public class FileContent(
    public val version: FileVersion,
    public val bytes: ByteArray,
    public val raced: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is FileContent &&
                    version == other.version &&
                    raced == other.raced &&
                    bytes.contentEquals(other.bytes)
                )

    override fun hashCode(): Int = 31 * (31 * version.hashCode() + bytes.contentHashCode()) + raced.hashCode()

    override fun toString(): String = "FileContent(${version.hash8}, ${bytes.size}B, raced=$raced)"
}

/** The answer to "is this file still the version the model was shown?" (§9.1 `expect`). */
public sealed interface CasCheck {
    /** The file still hashes to the expected version; an edit may proceed against it. */
    public data class Current(val version: FileVersion, val raced: Boolean) : CasCheck

    /** The bytes moved. [actual] is `null` when the file is gone. */
    public data class Stale(val actual: FileVersion?) : CasCheck

    /** The path contract refused the path; no comparison was made (D-47). */
    public data class Refused(val rejection: PathResolution.Rejected) : CasCheck
}

/**
 * One `path: from → to` transition as the registry announced it (§4.4 `on change(path, v → v')`).
 *
 * [from] is `null` when the file did not exist before *or* when the mutator could not establish the
 * previous version (a run that touched a file nobody had read). The horizons do not need the
 * distinction: everything anchored at [path] with a version other than [to] is stale either way.
 * [to] is `null` for a deletion. [cause] names the mutation for the announcement the model sees
 * (`stale @v (edited by transform #40)`, §5.3).
 */
public data class VersionChange(
    val path: String,
    val from: FileVersion?,
    val to: FileVersion?,
    val cause: String,
) {
    init {
        require(path.isNotBlank()) { "a version change needs a path" }
        require(from != to) { "$path: $from → $to is not a transition" }
        require(cause.isNotBlank()) { "a version change names its cause" }
    }

    val deleted: Boolean get() = to == null

    /** True when [version] is what an item anchored at [path] must carry to stay current. */
    public fun current(version: FileVersion): Boolean = version == to
}

/**
 * Notified once per version transition. [io.astrolabe.evidence.Coherence] is the registry's one
 * subscriber and fans each change out to the horizons (Workset, register facts, checks, …), which
 * implement this same interface.
 */
public fun interface ChangeListener {
    public fun onChange(change: VersionChange)
}

/**
 * The single component behind coherence (§3.3, §4.4): `version(path)`, `displayed(path, v)` and the
 * change notifications that make a stale read impossible to serve as current.
 *
 * **Content, not metadata (I-05).** [version] and [read] always hash the raw bytes. The private
 * `(size, mtime) → digest` cache is reachable only through [versionHint], which exists for
 * non-consequential lookups (a listing, a progress line) and is documented as a guess: an external
 * rewrite of equal length with a restored timestamp makes the hint wrong, which is exactly why every
 * read, edit compare-and-swap, check, reuse and publication goes through [version]/[read] instead.
 *
 * **Acquisition races.** A read stats the file, reads it, and stats it again; a changed size or
 * timestamp means another writer was active, so the read is retried up to
 * [MAX_ACQUISITION_ATTEMPTS] times and the result is flagged [FileContent.raced]. Timestamp
 * granularity can still hide a same-millisecond rewrite — that limit is the reason the digest, not
 * the metadata, is the identity.
 *
 * **Namespaces (F2).** Coverage is keyed by all five components of
 * `(context, generation, workspace, path, version)`. One registry can therefore hold the coverage of
 * several workspaces, and the same path at the same content hash in two workspaces has two separate
 * coverage records. Version lookups always concern this registry's own [workspace].
 *
 * Thread-safe: the caches are concurrent maps and listeners are held in a copy-on-write list, so a
 * slow listener never blocks a read.
 */
public class VersionRegistry(public val workspace: Workspace) {

    private val hints = ConcurrentHashMap<String, Hint>()
    private val coverage = ConcurrentHashMap<CoverageKey, Ranges>()

    /** `path → FileVersion` or [DELETED]; the transition already announced to the listeners. */
    private val current = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArrayList<ChangeListener>()

    // ------------------------------------------------------------- versions

    /**
     * The version of [path]: the SHA-256 of its raw bytes, hashed now. `null` when the file is
     * absent or the path contract refuses it.
     */
    public fun version(path: String): FileVersion? = read(path)?.version

    /** [version], but reporting why a path was refused instead of collapsing it to `null`. */
    public fun versionOf(path: String): CasCheck {
        val resolved = workspace.resolve(path, Intent.Read)
        if (resolved is PathResolution.Rejected) return CasCheck.Refused(resolved)
        val content = readResolved(resolved as PathResolution.Resolved)
            ?: return CasCheck.Stale(null)
        return CasCheck.Current(content.version, content.raced)
    }

    /**
     * Reads [path] and hashes what was actually read. `null` when the file is absent or refused.
     * This is the consequential read: it never consults the metadata cache.
     */
    public fun read(path: String): FileContent? {
        val resolved = workspace.resolve(path, Intent.Read)
        if (resolved !is PathResolution.Resolved) return null
        return readResolved(resolved)
    }

    /**
     * A cached guess at the version of [path], valid only while size and modification time are
     * unchanged — **never** an identity (I-05). Use [version] anywhere the answer matters.
     */
    public fun versionHint(path: String): FileVersion? {
        val resolved = workspace.resolve(path, Intent.Read)
        if (resolved !is PathResolution.Resolved) return null
        val stat = statOf(resolved) ?: return null
        hints[resolved.relative]?.let { if (it.matches(stat)) return it.version }
        return readResolved(resolved)?.version
    }

    /**
     * §9.1: the compare-and-swap guard. Re-hashes [path] now and compares it with [expected]; a
     * same-size external rewrite with a restored timestamp is therefore caught, because no cached
     * metadata takes part in the comparison.
     */
    public fun checkExpected(path: String, expected: FileVersion): CasCheck {
        val resolved = workspace.resolve(path, Intent.Read)
        if (resolved is PathResolution.Rejected) return CasCheck.Refused(resolved)
        val content = readResolved(resolved as PathResolution.Resolved) ?: return CasCheck.Stale(null)
        return if (content.version == expected) {
            CasCheck.Current(content.version, content.raced)
        } else {
            CasCheck.Stale(content.version)
        }
    }

    // ------------------------------------------------------------- coverage

    /** `displayed(context, generation, workspace, path, v)` — the union of line ranges shown (§3.3). */
    public fun displayed(
        context: ContextId,
        generation: Generation,
        workspaceId: WorkspaceId,
        path: String,
        version: FileVersion,
    ): Ranges = coverage[CoverageKey(context, generation, workspaceId, path, version)] ?: Ranges.EMPTY

    /**
     * Registers [range] as shown for exactly that `(context, generation, workspace, path, version)`
     * and returns the coverage this key now holds.
     *
     * Redacted lines grant no coverage (D-49): [redaction]'s hidden lines are subtracted before the
     * range is merged in, so an anchored edit can never claim authority over bytes the model was
     * shown a placeholder for.
     */
    public fun show(
        context: ContextId,
        generation: Generation,
        workspaceId: WorkspaceId,
        path: String,
        version: FileVersion,
        range: Ranges,
        redaction: RedactionMask = RedactionMask.NONE,
    ): Ranges {
        val granted = range - redaction.hiddenLines
        val key = CoverageKey(context, generation, workspaceId, path, version)
        if (granted.isEmpty) return coverage[key] ?: Ranges.EMPTY
        return coverage.compute(key) { _, existing -> (existing ?: Ranges.EMPTY) + granted }!!
    }

    /** [show] for a single closed range. */
    public fun show(
        context: ContextId,
        generation: Generation,
        workspaceId: WorkspaceId,
        path: String,
        version: FileVersion,
        range: LineRange,
        redaction: RedactionMask = RedactionMask.NONE,
    ): Ranges = show(context, generation, workspaceId, path, version, Ranges.of(range), redaction)

    // -------------------------------------------------------------- changes

    /** Subscribes [listener]; the returned handle removes it again. */
    public fun addListener(listener: ChangeListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    /**
     * Records the transition `path: from → to` caused by [cause] and notifies the listeners **once**.
     * A repeated call for a transition already recorded, and a call where `from == to`, notify
     * nobody: §4.4 marks evidence stale on a version change, and doing that twice for one change
     * would invalidate evidence that was rebuilt against the new version in between.
     *
     * Listeners run on the calling thread, in subscription order; a mutator therefore announces a
     * change only after the bytes are on disk, and workspace mutation stays serialized (D-26).
     */
    public fun change(path: String, from: FileVersion?, to: FileVersion?, cause: String) {
        if (from == to) return
        val next: Any = to ?: DELETED
        synchronized(current) {
            if (current[path] == next) return
            current[path] = next
        }
        val change = VersionChange(path, from, to, cause)
        for (listener in listeners) listener.onChange(change)
    }

    /** The last version this registry recorded for [path] through [change]; not a filesystem read. */
    public fun recorded(path: String): FileVersion? = current[path] as? FileVersion

    /** Forgets every metadata hint; a caller that suspects the cache never has to trust it. */
    public fun clearHints() {
        hints.clear()
    }

    // ------------------------------------------------------------ internals

    private fun readResolved(resolved: PathResolution.Resolved): FileContent? {
        var attempt = 0
        var raced = false
        while (attempt < MAX_ACQUISITION_ATTEMPTS) {
            attempt++
            val before = statOf(resolved) ?: return null
            val bytes = try {
                Files.readAllBytes(resolved.real)
            } catch (missing: IOException) {
                return null
            }
            val after = statOf(resolved) ?: return null
            if (before == after) {
                val version = FileVersion.of(bytes)
                hints[resolved.relative] = Hint(before, version)
                return FileContent(version, bytes, raced)
            }
            raced = true
        }
        // The file is being rewritten continuously; report the last read honestly rather than spin.
        val bytes = try {
            Files.readAllBytes(resolved.real)
        } catch (missing: IOException) {
            return null
        }
        return FileContent(FileVersion.of(bytes), bytes, raced = true)
    }

    private fun statOf(resolved: PathResolution.Resolved): Stat? = try {
        val attributes = Files.readAttributes(resolved.real, BasicFileAttributes::class.java)
        if (attributes.isDirectory) null else Stat(attributes.size(), attributes.lastModifiedTime().toMillis())
    } catch (missing: IOException) {
        null
    }

    private data class Stat(val sizeBytes: Long, val modifiedEpochMillis: Long)

    private class Hint(val stat: Stat, val version: FileVersion) {
        fun matches(other: Stat): Boolean = stat == other
    }

    private data class CoverageKey(
        val context: ContextId,
        val generation: Generation,
        val workspace: WorkspaceId,
        val path: String,
        val version: FileVersion,
    )

    public companion object {
        /** How often a read retries a metadata-detected race before reporting it (I-05). */
        public const val MAX_ACQUISITION_ATTEMPTS: Int = 3

        /** Marker for "this path has no version": a deletion is a transition, not an absent record. */
        private val DELETED = Any()
    }
}
