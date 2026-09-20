package io.astrolabe.os

/**
 * A git object name. Forty hex characters for SHA-1 repositories, sixty-four for SHA-256 ones
 * (D-04); git emits lowercase, and the canonical form here is lowercase so that ids compare by
 * value.
 */
public data class ObjectId(public val hex: String) {
    init {
        require(hex.length == 40 || hex.length == 64) {
            "object id must be 40 or 64 hex characters, got ${hex.length}: '$hex'"
        }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "object id must be lowercase hex: '$hex'"
        }
    }

    /** The all-zero name, which git uses to mean "no such object" in ref updates. */
    public val isZero: Boolean get() = hex.all { it == '0' }

    override fun toString(): String = hex

    public companion object {
        /** Trims and lowercases [text], returning `null` when it is not a git object name. */
        public fun parseOrNull(text: String): ObjectId? {
            val trimmed = text.trim().lowercase()
            if (trimmed.length != 40 && trimmed.length != 64) return null
            if (!trimmed.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return ObjectId(trimmed)
        }

        /** Trims and lowercases [text]; throws [IllegalArgumentException] when it is not an id. */
        public fun parse(text: String): ObjectId =
            parseOrNull(text) ?: throw IllegalArgumentException("not a git object id: '${text.trim()}'")

        /** The all-zero name with the same width as [like], for `git update-ref` sentinels. */
        public fun zeroLike(like: ObjectId): ObjectId = ObjectId("0".repeat(like.hex.length))
    }
}

/**
 * The git version triple. `version()` reports it and `requireMinimum` tests it; D-04 keeps 2.20 a
 * *tested* minimum rather than a substitute for feature tests.
 */
public data class GitVersion(
    public val major: Int,
    public val minor: Int,
    public val patch: Int,
    public val raw: String,
) : Comparable<GitVersion> {
    public fun atLeast(major: Int, minor: Int): Boolean =
        this.major > major || (this.major == major && this.minor >= minor)

    override fun compareTo(other: GitVersion): Int {
        val byMajor = major.compareTo(other.major)
        if (byMajor != 0) return byMajor
        val byMinor = minor.compareTo(other.minor)
        if (byMinor != 0) return byMinor
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    public companion object {
        private val TRIPLE = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

        /**
         * Parses the single line `git version` prints, for example `git version 2.45.1.windows.1`
         * or `git version 2.39.3 (Apple Git-146)`. Vendor suffixes past the triple are kept in
         * [raw] and otherwise ignored.
         */
        public fun parse(raw: String): GitVersion {
            val line = raw.trim().lineSequence().firstOrNull()?.trim().orEmpty()
            val match = TRIPLE.find(line.removePrefix("git version").trim())
                ?: throw IllegalArgumentException("cannot parse git version from: '$line'")
            return GitVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].ifEmpty { "0" }.toInt(),
                raw = line,
            )
        }
    }
}

/** The closed set of modes git stores in trees and in the index. */
public enum class FileMode(public val octal: String) {
    /** `000000` — no entry on this side of a comparison. */
    ABSENT("000000"),
    TREE("040000"),
    REGULAR("100644"),
    EXECUTABLE("100755"),
    SYMLINK("120000"),

    /** `160000` — a submodule reference; D-53 refuses these rather than indexing them. */
    GITLINK("160000"),
    ;

    public companion object {
        public fun parseOrNull(octal: String): FileMode? = entries.firstOrNull { it.octal == octal }

        public fun parse(octal: String): FileMode =
            parseOrNull(octal) ?: throw IllegalArgumentException("unknown git file mode: '$octal'")
    }
}

/** The per-side change codes of a porcelain v2 `XY` field. */
public enum class StatusCode(public val code: Char) {
    UNMODIFIED('.'),
    MODIFIED('M'),
    FILE_TYPE_CHANGED('T'),
    ADDED('A'),
    DELETED('D'),
    RENAMED('R'),
    COPIED('C'),
    UNMERGED('U'),
    ;

    public companion object {
        public fun parse(code: Char): StatusCode =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("unknown git status code: '$code'")
    }
}

/** Whether a porcelain v2 `2` entry reports a rename or a copy. */
public enum class ChangeOrigin {
    RENAME,
    COPY,
    ;

    public companion object {
        public fun parse(code: Char): ChangeOrigin = when (code) {
            'R' -> RENAME
            'C' -> COPY
            else -> throw IllegalArgumentException("unknown rename/copy origin: '$code'")
        }
    }
}

/** The porcelain v2 `<sub>` field: `N...` for an ordinary path, `S<c><m><u>` for a submodule. */
public data class SubmoduleState(
    public val isSubmodule: Boolean,
    public val commitChanged: Boolean,
    public val hasTrackedChanges: Boolean,
    public val hasUntrackedChanges: Boolean,
) {
    public companion object {
        public val NOT_A_SUBMODULE: SubmoduleState = SubmoduleState(false, false, false, false)

        public fun parse(field: String): SubmoduleState {
            require(field.length == 4) { "malformed porcelain v2 submodule field: '$field'" }
            return when (field[0]) {
                'N' -> NOT_A_SUBMODULE
                'S' -> SubmoduleState(
                    isSubmodule = true,
                    commitChanged = field[1] == 'C',
                    hasTrackedChanges = field[2] == 'M',
                    hasUntrackedChanges = field[3] == 'U',
                )

                else -> throw IllegalArgumentException("malformed porcelain v2 submodule field: '$field'")
            }
        }
    }
}

/**
 * One line of `git status --porcelain=v2`. Paths are repository-relative and always use forward
 * slashes, on every platform; `-z` mode means they are never quoted or escaped.
 */
public sealed interface StatusEntry {
    public val path: String

    /** `1` — a tracked path changed on one or both sides. */
    public data class Ordinary(
        public val index: StatusCode,
        public val worktree: StatusCode,
        public val submodule: SubmoduleState,
        public val headMode: FileMode,
        public val indexMode: FileMode,
        public val worktreeMode: FileMode,
        public val headId: ObjectId,
        public val indexId: ObjectId,
        override val path: String,
    ) : StatusEntry

    /** `2` — a staged rename or copy; [origPath] is the name the content had before. */
    public data class Renamed(
        public val index: StatusCode,
        public val worktree: StatusCode,
        public val submodule: SubmoduleState,
        public val headMode: FileMode,
        public val indexMode: FileMode,
        public val worktreeMode: FileMode,
        public val headId: ObjectId,
        public val indexId: ObjectId,
        public val origin: ChangeOrigin,
        public val similarityPercent: Int,
        override val path: String,
        public val origPath: String,
    ) : StatusEntry

    /** `u` — an unmerged path, carrying all three index stages. */
    public data class Unmerged(
        public val index: StatusCode,
        public val worktree: StatusCode,
        public val submodule: SubmoduleState,
        public val stage1Mode: FileMode,
        public val stage2Mode: FileMode,
        public val stage3Mode: FileMode,
        public val worktreeMode: FileMode,
        public val stage1Id: ObjectId,
        public val stage2Id: ObjectId,
        public val stage3Id: ObjectId,
        override val path: String,
    ) : StatusEntry

    /** `?` — an untracked path. */
    public data class Untracked(override val path: String) : StatusEntry

    /** `!` — an ignored path, reported only when status was asked for ignored files. */
    public data class Ignored(override val path: String) : StatusEntry
}

/** The `# branch.*` headers of `git status --porcelain=v2 --branch`. */
public data class BranchInfo(
    /** `null` on an unborn branch, where git prints `(initial)`. */
    public val oid: ObjectId?,
    /** `null` on a detached HEAD, where git prints `(detached)`. */
    public val head: String?,
    public val upstream: String?,
    public val ahead: Int?,
    public val behind: Int?,
)

/** A parsed `git status --porcelain=v2` result. */
public data class GitStatus(
    public val branch: BranchInfo?,
    public val entries: List<StatusEntry>,
) {
    /** No tracked change, no untracked file — ignored entries do not count as dirt. */
    public val isClean: Boolean
        get() = entries.none { it !is StatusEntry.Ignored }

    public val untracked: List<String>
        get() = entries.filterIsInstance<StatusEntry.Untracked>().map { it.path }
}

/** One row of `git ls-files -s`: a staged path at an index [stage] (0 unless unmerged). */
public data class LsFilesEntry(
    public val mode: FileMode,
    public val id: ObjectId,
    public val stage: Int,
    public val path: String,
)

/** One row of `git ls-tree`. */
public data class TreeEntry(
    public val mode: FileMode,
    public val kind: TreeEntryKind,
    public val id: ObjectId,
    public val path: String,
)

/** The object kinds a tree can point at. */
public enum class TreeEntryKind {
    BLOB,
    TREE,
    COMMIT,
    TAG,
    ;

    public companion object {
        public fun parse(text: String): TreeEntryKind =
            entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown git object kind: '$text'")
    }
}

/**
 * One record for `git update-index --index-info`, written as `<mode> SP <id> TAB <path>`.
 *
 * D-53: a snapshot indexes raw blobs, so only file modes are accepted here — a `GITLINK` entry
 * would name a submodule, which this wrapper refuses by name instead of indexing.
 */
public data class IndexEntry(
    public val mode: FileMode,
    public val id: ObjectId,
    public val path: String,
) {
    init {
        require(mode == FileMode.REGULAR || mode == FileMode.EXECUTABLE || mode == FileMode.SYMLINK) {
            "index entries carry file modes only, not ${mode.name} (${mode.octal}) — see D-53"
        }
        require(path.isNotEmpty()) { "index entry path must not be empty" }
        require(!path.contains(' ')) { "index entry path must not contain NUL: '$path'" }
        require(!path.startsWith('/')) { "index entry path must be repository-relative: '$path'" }
    }
}

/** An author/committer identity for `git commit-tree`. */
public data class Identity(public val name: String, public val email: String) {
    init {
        require(name.isNotBlank()) { "identity name must not be blank" }
        require(email.isNotBlank()) { "identity email must not be blank" }
    }
}

/** How much untracked detail `git status` should report. */
public enum class UntrackedFiles(public val flag: String) {
    NO("no"),
    NORMAL("normal"),
    ALL("all"),
}

/** Repository shapes this wrapper refuses by name rather than mis-indexing (D-53). */
public enum class RepositoryForm {
    SUBMODULES,
    SPARSE_CHECKOUT,
}

/** A git command exited non-zero. [argv] includes the executable; [stderr] is decoded UTF-8. */
public open class GitError(
    public val argv: List<String>,
    public val exitCode: Int,
    public val stderr: String,
) : RuntimeException(gitErrorMessage(argv, exitCode, stderr))

/**
 * `git update-ref <ref> <new> <old>` refused the update because the ref did not hold the expected
 * old id: either another writer moved it, or it exists where [expectedOld] was `null` (D-04
 * compare-and-swap). The ref is unchanged.
 */
public class RefUpdateRejected(
    public val ref: String,
    public val expectedOld: ObjectId?,
    argv: List<String>,
    exitCode: Int,
    stderr: String,
) : GitError(argv, exitCode, stderr)

/**
 * The repository is in a form this wrapper does not support for [operation]. D-53 requires these
 * to be rejected by name instead of producing a snapshot that silently omits or mangles content.
 */
public class UnsupportedRepositoryForm(
    public val form: RepositoryForm,
    public val operation: String,
) : RuntimeException(
    "git operation '$operation' is not supported in a repository with " +
        when (form) {
            RepositoryForm.SUBMODULES -> "submodules (.gitmodules present)"
            RepositoryForm.SPARSE_CHECKOUT -> "sparse checkout (core.sparseCheckout=true)"
        } + " — see D-53",
)

internal fun gitErrorMessage(argv: List<String>, exitCode: Int, stderr: String): String {
    val detail = stderr.trim().lineSequence().filter { it.isNotBlank() }.take(4).joinToString("; ")
    val rendered = argv.joinToString(" ")
    return if (detail.isEmpty()) {
        "git exited $exitCode: $rendered"
    } else {
        "git exited $exitCode: $rendered -- $detail"
    }
}
