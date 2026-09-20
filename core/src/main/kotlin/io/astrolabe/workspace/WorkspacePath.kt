package io.astrolabe.workspace

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** Why a built-in filesystem operation wants a path; mutation is held to the stricter rules (D-47). */
public enum class Intent {
    Read,
    Mutate,
}

/**
 * What the last segment of a resolved path actually is, looked up without following the final link.
 *
 * [Junction] is a Windows junction / mount point — a reparse point the JDK does not report as a
 * POSIX symlink. [Special] is a POSIX entry that is neither file, directory nor symlink (device,
 * fifo, socket): like a link it is not something a built-in operation may write through.
 */
public enum class PathKind {
    Regular,
    Directory,
    Symlink,
    Junction,
    Special,
    Missing,
    ;

    /** True for the kinds a mutation must not pass through until an explicit operation supports them (§9.5). */
    public val isLink: Boolean get() = this == Symlink || this == Junction
}

/** Named refusals; a rejected path never falls back to a lexical check (I-09). */
public enum class RejectionReason {
    /** Empty, `.`, or nothing but separators. */
    Empty,

    /** Rooted, drive-qualified or UNC — an absolute escape. */
    Absolute,

    /** A `..` segment. */
    Traversal,

    /** NUL or another byte a path may not carry. */
    IllegalCharacter,

    /** The real path lands outside the workspace root. */
    OutsideRoot,

    /** The real path matches a protected prefix or name for this [Intent]. */
    Protected,

    /** An ancestor is a symlink, junction or reparse point and the intent is [Intent.Mutate]. */
    LinkAncestor,

    /** The target itself is a link or a special file and the intent is [Intent.Mutate]. */
    LinkTarget,

    /** The target is a directory where a file operation was asked for. */
    NotAFile,

    /** The path could not be canonicalised at all (an I/O failure, not a policy refusal). */
    Unresolvable,

    /** [WorkspacePath.revalidate]: the real path this resolution named is no longer the same object. */
    Moved,
}

/** One ancestor of a resolved path that is a link; reads report them rather than refusing (D-47). */
public data class LinkAncestor(val relative: String, val kind: PathKind)

/** The outcome of [WorkspacePath.resolve]; there is no third answer and no unchecked path. */
public sealed interface PathResolution {

    /**
     * An accepted path. [relative] is workspace-relative with forward slashes in the on-disk case
     * for the part that exists, which is the identity every registry, stamp and closure keys on.
     * [real] is the canonical path the operation must use — never the caller's spelling.
     */
    public data class Resolved(
        val relative: String,
        val real: Path,
        val kind: PathKind,
        val intent: Intent,
        /** Link ancestors between the root and the target; always empty for [Intent.Mutate]. */
        val linkAncestors: List<LinkAncestor> = emptyList(),
    ) : PathResolution {
        init {
            require(relative.isNotEmpty()) { "a resolved path is never empty" }
            require(intent == Intent.Read || linkAncestors.isEmpty()) {
                "a mutation is never resolved through a link ancestor: $linkAncestors"
            }
        }
    }

    /** A refused path, with the reason and a detail naming what was refused. */
    public data class Rejected(val reason: RejectionReason, val detail: String) : PathResolution
}

/**
 * Paths the workspace refuses to hand out, matched on the **real** relative path so that a junction
 * or a case alias pointing at one cannot launder it (I-09).
 *
 * Two tiers: [readDeniedPrefixes] are directories no built-in operation may even read — the Git
 * common metadata of §4.6 — and the write tier adds the default protected write scope of D-31 (CI
 * configuration, lock files, migration directories). Reading CI configuration or a lock file is
 * legitimate; writing one is authorized work that belongs in the contract, not in a path check.
 */
public data class ProtectedPaths(
    val readDeniedPrefixes: Set<String> = DEFAULT_READ_DENIED,
    val writeDeniedPrefixes: Set<String> = DEFAULT_WRITE_DENIED_PREFIXES,
    /** Matched against the file name anywhere in the tree, because lock files are not rooted. */
    val writeDeniedNames: Set<String> = DEFAULT_WRITE_DENIED_NAMES,
) {
    public companion object {
        /** Git common metadata: never read and never written through this contract (§4.6). */
        @JvmField
        public val DEFAULT_READ_DENIED: Set<String> = setOf(".git")

        @JvmField
        public val DEFAULT_WRITE_DENIED_PREFIXES: Set<String> =
            setOf(".git", ".github", ".gitlab", "migrations")

        @JvmField
        public val DEFAULT_WRITE_DENIED_NAMES: Set<String> = setOf(
            ".gitlab-ci.yml",
            "package-lock.json",
            "npm-shrinkwrap.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "poetry.lock",
            "uv.lock",
            "Pipfile.lock",
            "Cargo.lock",
            "gradle.lockfile",
            "Gemfile.lock",
            "composer.lock",
            "go.sum",
        )
    }
}

/**
 * What publication through this contract does and does not guarantee (D-47, I-09).
 *
 * Reported rather than claimed: [atomicRename] is what `Os.replaceFileAtomically` provides — readers
 * see the old or the new file, never a partial one — and [compareAndReplace] is what it does **not**
 * provide. A concurrent external writer's version is overwritten by a rename; a content hash is not
 * a lock (§9.1). Where that stronger guarantee is required, publication has to be coordinated by the
 * host (an exported candidate, an enforced no-concurrent-writer boundary), not by this contract.
 */
public data class PublicationLimits(
    val atomicRename: Boolean,
    val compareAndReplace: Boolean,
    val revalidationIsBestEffort: Boolean,
    val detail: String,
)

/**
 * The one workspace-path contract (D-47, I-09, §14.1). Every built-in filesystem operation — look,
 * edit, the version registry, stamps, closures, ownership — resolves through it, so filesystem-root
 * enforcement happens once, on canonical real paths, instead of once per caller on strings.
 *
 * Rules, in order: a path must be non-empty, relative, free of `..` and of NUL; its real path (the
 * canonical path of the deepest existing ancestor plus the segments that do not exist yet) must lie
 * inside the root; it must not match a protected prefix or name for the [Intent]; and for
 * [Intent.Mutate] neither the target nor any ancestor may be a symlink, junction or other reparse
 * point. Reads are allowed through links and report their kind instead.
 *
 * `..` is refused outright rather than normalised away. Inside the harness a `..` segment is never
 * needed, and refusing it keeps the real path the only authority on where a path lands.
 *
 * Case identity is unified by construction: the accepted [PathResolution.Resolved.relative] is
 * derived from `toRealPath`, which reports the on-disk spelling, so `SRC/A.py` and `src/a.py` resolve
 * to one identity on a case-insensitive filesystem. [caseInsensitive] is probed once, read-only, by
 * asking the filesystem whether an existing entry of the root answers to the other case.
 */
public class WorkspacePath private constructor(
    public val root: Path,
    public val protectedPaths: ProtectedPaths,
    public val caseInsensitive: Boolean,
) {

    /** Resolves [userPath] under this root for [intent]; see the class documentation for the rules. */
    public fun resolve(userPath: String, intent: Intent): PathResolution {
        val lexical = lexicalSegments(userPath)
        lexical.rejected?.let { return it }
        val segments = lexical.segments

        var candidate = root
        for (segment in segments) candidate = candidate.resolve(segment)

        val real = try {
            canonicalise(candidate, segments.size)
        } catch (failure: IOException) {
            return PathResolution.Rejected(
                RejectionReason.Unresolvable,
                "cannot canonicalise '$userPath': ${failure.message}",
            )
        }
        if (real == root) {
            return PathResolution.Rejected(RejectionReason.Traversal, "'$userPath' resolves to the workspace root")
        }
        if (!real.startsWith(root)) {
            return PathResolution.Rejected(RejectionReason.OutsideRoot, "'$userPath' resolves to $real, outside $root")
        }
        val relative = root.relativize(real).joinToString("/") { it.toString() }
        protectedRefusal(relative, intent)?.let { return it }

        val ancestors = linkAncestors(segments)
        val kind = kindOf(candidate)
        if (intent == Intent.Mutate) {
            ancestors.firstOrNull()?.let {
                return PathResolution.Rejected(
                    RejectionReason.LinkAncestor,
                    "'$userPath' passes through ${it.kind.name.lowercase(Locale.ROOT)} ancestor '${it.relative}'",
                )
            }
            if (kind.isLink || kind == PathKind.Special) {
                return PathResolution.Rejected(
                    RejectionReason.LinkTarget,
                    "'$relative' is a ${kind.name.lowercase(Locale.ROOT)}; mutating one needs an explicit operation (§9.5)",
                )
            }
            if (kind == PathKind.Directory) {
                return PathResolution.Rejected(RejectionReason.NotAFile, "'$relative' is a directory")
            }
        }
        return PathResolution.Resolved(
            relative = relative,
            real = real,
            kind = kind,
            intent = intent,
            linkAncestors = if (intent == Intent.Mutate) emptyList() else ancestors,
        )
    }

    /**
     * Re-resolves [resolved] at publication and confirms it still names the same object (D-47).
     *
     * The kind may legitimately change from [PathKind.Missing] to [PathKind.Regular] — that is the
     * write that just happened — but a path that has become a link, moved to a different real path,
     * or gained a link ancestor is refused. This is **best effort**: see [publicationLimits].
     */
    public fun revalidate(resolved: PathResolution.Resolved): PathResolution {
        val again = resolve(resolved.relative, resolved.intent)
        if (again !is PathResolution.Resolved) return again
        if (again.real != resolved.real) {
            return PathResolution.Rejected(
                RejectionReason.Moved,
                "'${resolved.relative}' resolved to ${resolved.real} and now resolves to ${again.real}",
            )
        }
        if ((again.kind.isLink || again.kind == PathKind.Special) && !resolved.kind.isLink) {
            return PathResolution.Rejected(
                RejectionReason.LinkTarget,
                "'${resolved.relative}' became a ${again.kind.name.lowercase(Locale.ROOT)} after resolution",
            )
        }
        return again
    }

    /** True when [relative] (already canonical) is protected for [intent]. */
    public fun isProtected(relative: String, intent: Intent): Boolean =
        protectedRefusal(relative, intent) != null

    // ------------------------------------------------------------- internals

    /** Either a refusal or the segments; a private carrier so the lexical pass stays one pass. */
    private class Lexical(val rejected: PathResolution.Rejected?, val segments: List<String>)

    private fun lexicalSegments(userPath: String): Lexical {
        fun refuse(reason: RejectionReason, detail: String) =
            Lexical(PathResolution.Rejected(reason, detail), emptyList())

        if (userPath.isEmpty() || userPath.isBlank()) return refuse(RejectionReason.Empty, "empty path")
        if (userPath.indexOf(' ') >= 0) {
            return refuse(RejectionReason.IllegalCharacter, "path contains NUL")
        }
        if (userPath.startsWith("/") || userPath.startsWith("\\") || DRIVE.containsMatchIn(userPath)) {
            return refuse(RejectionReason.Absolute, "'$userPath' is absolute")
        }
        if (runCatching { Path.of(userPath).isAbsolute }.getOrDefault(false)) {
            return refuse(RejectionReason.Absolute, "'$userPath' is absolute")
        }
        val segments = userPath.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) {
            return refuse(RejectionReason.Traversal, "'$userPath' contains a '..' segment")
        }
        if (segments.isEmpty()) return refuse(RejectionReason.Empty, "'$userPath' names no path segment")
        return Lexical(null, segments)
    }

    /**
     * The canonical path of [candidate]: `toRealPath` of the deepest existing ancestor plus the
     * segments that do not exist yet. An existing ancestor that is a link is therefore already
     * replaced by its target here, which is what lets the protected and containment checks below
     * run on the real bytes rather than on the caller's spelling.
     */
    private fun canonicalise(candidate: Path, depth: Int): Path {
        var existing = candidate
        val missing = ArrayList<String>()
        var remaining = depth
        while (remaining > 0 && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(existing.fileName.toString())
            existing = existing.parent ?: break
            remaining--
        }
        // A dangling link still exists under NOFOLLOW, and following it then fails; canonicalising
        // its ancestors keeps the containment check honest and lets the read report the link kind.
        var real = try {
            existing.toRealPath()
        } catch (dangling: IOException) {
            existing.toRealPath(LinkOption.NOFOLLOW_LINKS)
        }
        for (segment in missing.asReversed()) real = real.resolve(segment)
        return real
    }

    private fun protectedRefusal(relative: String, intent: Intent): PathResolution.Rejected? {
        val prefixes = when (intent) {
            Intent.Read -> protectedPaths.readDeniedPrefixes
            Intent.Mutate -> protectedPaths.readDeniedPrefixes + protectedPaths.writeDeniedPrefixes
        }
        val comparable = fold(relative)
        for (prefix in prefixes) {
            val folded = fold(prefix.trimEnd('/'))
            if (comparable == folded || comparable.startsWith("$folded/")) {
                return PathResolution.Rejected(RejectionReason.Protected, "'$relative' is under protected '$prefix'")
            }
        }
        if (intent == Intent.Mutate) {
            val name = fold(relative.substringAfterLast('/'))
            for (denied in protectedPaths.writeDeniedNames) {
                if (name == fold(denied)) {
                    return PathResolution.Rejected(RejectionReason.Protected, "'$relative' is a protected '$denied'")
                }
            }
        }
        return null
    }

    /** Link ancestors strictly between the root and the target, named as the caller spelled them. */
    private fun linkAncestors(segments: List<String>): List<LinkAncestor> {
        if (segments.size < 2) return emptyList()
        val found = ArrayList<LinkAncestor>()
        var walked = root
        val names = ArrayList<String>()
        for (segment in segments.dropLast(1)) {
            walked = walked.resolve(segment)
            names.add(segment)
            val kind = kindOf(walked)
            if (kind.isLink || kind == PathKind.Special) {
                found.add(LinkAncestor(names.joinToString("/"), kind))
            }
        }
        return found
    }

    private fun fold(value: String): String = if (caseInsensitive) value.lowercase(Locale.ROOT) else value

    public companion object {
        private val DRIVE = Regex("""^[A-Za-z]:""")

        /**
         * Binds the contract to [root], which must exist; the real path of the root is the one every
         * containment check uses.
         */
        @JvmStatic
        @JvmOverloads
        public fun of(root: Path, protectedPaths: ProtectedPaths = ProtectedPaths()): WorkspacePath {
            val real = root.toRealPath()
            require(Files.isDirectory(real)) { "workspace root $real is not a directory" }
            return WorkspacePath(real, protectedPaths, probeCaseInsensitive(real))
        }

        /**
         * What this contract guarantees at publication, stated so callers never claim more (I-09).
         */
        @JvmStatic
        public fun publicationLimits(): PublicationLimits = PublicationLimits(
            atomicRename = true,
            compareAndReplace = false,
            revalidationIsBestEffort = true,
            detail = "an atomic rename is not a compare-and-replace against external writers; " +
                "revalidate() re-resolves and compares the real path but cannot exclude a " +
                "replacement between the check and the rename. Stronger guarantees need " +
                "host-coordinated publication (D-47, §9.1).",
        )

        /** The kind of [path], looked up without following a final link. */
        @JvmStatic
        public fun kindOf(path: Path): PathKind = try {
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            when {
                attributes.isSymbolicLink -> PathKind.Symlink
                // A Windows junction / mount point is a reparse point the JDK does not call a
                // symbolic link; NOFOLLOW attributes report it as "other".
                attributes.isOther -> if (isWindows()) PathKind.Junction else PathKind.Special
                attributes.isDirectory -> PathKind.Directory
                else -> PathKind.Regular
            }
        } catch (missing: IOException) {
            PathKind.Missing
        }

        private fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        /**
         * Asks the filesystem, without writing anything, whether an existing entry of [root] also
         * answers to the opposite case. Falls back to the platform default when the root holds no
         * entry with a letter in its name.
         */
        private fun probeCaseInsensitive(root: Path): Boolean {
            val names = runCatching {
                Files.newDirectoryStream(root).use { stream -> stream.take(64).map { it.fileName.toString() } }
            }.getOrDefault(emptyList())
            for (name in names) {
                val flipped = flipCase(name) ?: continue
                return Files.exists(root.resolve(flipped), LinkOption.NOFOLLOW_LINKS)
            }
            return isWindows()
        }

        private fun flipCase(name: String): String? {
            val upper = name.uppercase(Locale.ROOT)
            if (upper != name) return upper
            val lower = name.lowercase(Locale.ROOT)
            return if (lower != name) lower else null
        }
    }
}
