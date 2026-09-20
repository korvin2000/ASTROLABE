package io.astrolabe.store

import io.astrolabe.id.CanonicalEncoding
import io.astrolabe.id.Digest
import io.astrolabe.os.Git
import io.astrolabe.os.ObjectId

/**
 * Canonical identity of a repository (D-44, D-15): the key of its external project state root.
 *
 * The identity is a digest of the repository's root commits and its git *common* directory. Both
 * inputs are deliberate:
 *  - root commits make two unrelated repositories that happen to sit at the same path (a clone
 *    replaced in place) different projects;
 *  - the common directory makes every linked worktree of one repository the *same* project, because
 *    `git rev-parse --git-common-dir` resolves to the main `.git` from any worktree. Worktrees then
 *    share the store and keep distinct `workspace_id`s in the rows.
 *
 * An unborn repository (no commits yet) has no roots and is keyed by its common directory alone; it
 * gains a different identity once the first commit exists, which is correct — there was no project
 * history to inherit.
 */
public data class RepoIdentity(
    val digest: Digest,
    val roots: List<ObjectId>,
    val commonDir: String,
) {
    init {
        require(commonDir.isNotBlank()) { "commonDir must name the git common directory" }
    }

    /** Directory name under `projects/`; the full digest, never the short form. */
    val directoryName: String get() = digest.hex

    override fun toString(): String = digest.hex

    public companion object {
        /** Bumping this re-keys every project root, so it is a deliberate version boundary. */
        public const val ENCODING_VERSION: Int = 1

        /** Root-commit field value of a repository without commits. */
        public const val NO_ROOTS: String = "none"

        /**
         * Reads the identity of [git]'s repository. Two git reads: root commits and the common
         * directory; both are pure reads that never touch the user's index or refs.
         */
        @JvmStatic
        public fun of(git: Git): RepoIdentity {
            val roots = git.rootCommits()
            return of(roots, git.commonDir().toString())
        }

        /** The pure part, so the encoding can be tested without a repository. */
        @JvmStatic
        public fun of(roots: List<ObjectId>, commonDir: String): RepoIdentity {
            val sorted = roots.sortedBy { it.hex }
            val canonicalDir = canonicalizeDirectory(commonDir)
            val encoded = CanonicalEncoding.encode(
                kind = "repo-identity",
                version = ENCODING_VERSION,
                fields = listOf(
                    "roots" to (sorted.joinToString(",") { it.hex }.ifEmpty { NO_ROOTS }),
                    "commonDir" to canonicalDir,
                ),
            )
            return RepoIdentity(Digest.ofUtf8(encoded), sorted, canonicalDir)
        }

        /**
         * Forward slashes everywhere and a lowercase drive letter, so `C:\r` and `c:/r` — which name
         * the same directory on Windows — never key two stores. Case elsewhere is left alone: the
         * caller passes a real path, and on a case-sensitive filesystem case is meaningful.
         */
        private fun canonicalizeDirectory(raw: String): String {
            val slashed = raw.replace('\\', '/').trimEnd('/')
            val normalized = slashed.ifEmpty { "/" }
            return if (normalized.length >= 2 && normalized[1] == ':' && normalized[0].isLetter()) {
                normalized[0].lowercaseChar() + normalized.substring(1)
            } else {
                normalized
            }
        }
    }
}
