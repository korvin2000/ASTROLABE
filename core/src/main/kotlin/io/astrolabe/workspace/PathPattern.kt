package io.astrolabe.workspace

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
 * Matching is exact-case: the path contract already unified case aliases on case-insensitive filesystems
 * (D-47), so what arrives here is the on-disk spelling.
 */
public object PathPattern {
    @JvmStatic
    public fun matches(pattern: String, relative: String): Boolean {
        val path = relative.replace('\\', '/').removePrefix("./").trimEnd('/')
        val raw = pattern.replace('\\', '/').removePrefix("./")
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
