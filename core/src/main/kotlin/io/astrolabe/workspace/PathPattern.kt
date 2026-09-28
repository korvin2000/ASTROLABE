package io.astrolabe.workspace

import java.util.Locale

/**
 * The one path-pattern convention of contract scopes and increment write scopes (D-31, §8.6), applied to
 * workspace-relative paths with forward slashes:
 *
 * - `dir/` — a directory prefix (`src/` matches `src/a.py`, not `srcs/a.py`);
 * - `name` with no slash and no wildcard — that file name anywhere in the tree (lock files are not rooted);
 * - `a/b` with a slash and no wildcard — that path, or anything below it;
 * - a glob — a double star spans directories (a leading double star plus slash also matches the root), a
 *   single star and `?` stay inside one segment;
 * - `**` alone — the whole repository.
 *
 * Matching is exact-case by default: the path contract already unified case aliases of existing entries on
 * case-insensitive filesystems (D-47). A not-yet-existing path keeps the caller's spelling, so a protection
 * check on such a filesystem passes [ignoreCase] (D-302).
 */
public object PathPattern {
    @JvmStatic
    @JvmOverloads
    public fun matches(pattern: String, relative: String, ignoreCase: Boolean = false): Boolean {
        val path = relative.replace('\\', '/').removePrefix("./").trimEnd('/').let { if (ignoreCase) it.lowercase(Locale.ROOT) else it }
        val raw = pattern.replace('\\', '/').removePrefix("./").let { if (ignoreCase) it.lowercase(Locale.ROOT) else it }
        if (raw.isEmpty() || raw == "/" || path.isEmpty()) return false
        if (raw == "**") return true
        val glob = raw.any { it == '*' || it == '?' }
        return when {
            glob -> regexOf(raw.trimEnd('/')).matches(path)
            raw.endsWith("/") -> raw.trimEnd('/').let { path == it || path.startsWith("$it/") }
            '/' !in raw -> path == raw || path.endsWith("/$raw")
            else -> path == raw || path.startsWith("$raw/")
        }
    }

    private fun regexOf(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> {
                    sb.append("(.*/)?")
                    i += 3
                    continue
                }
                glob.startsWith("**", i) -> {
                    sb.append(".*")
                    i += 2
                    continue
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c in ".()+|^$[]{}\\" -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        return Regex(sb.append('$').toString())
    }
}
