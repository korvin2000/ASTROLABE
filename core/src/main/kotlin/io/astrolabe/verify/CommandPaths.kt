package io.astrolabe.verify

/** Workspace paths stay in evidence; only command arguments are relative to the command directory. */
internal fun commandPath(path: String, cwd: String?, insideOnly: Boolean): String? {
    val file = java.nio.file.Path.of(path.replace('\\', '/')).normalize()
    val base = java.nio.file.Path.of((cwd ?: "").replace('\\', '/')).normalize()
    if (file.isAbsolute || base.isAbsolute || file.startsWith("..") || base.startsWith("..")) return null
    if (insideOnly && base.toString().isNotEmpty() && !file.startsWith(base)) return null
    val relative = base.relativize(file).toString().replace('\\', '/')
    return if (relative.startsWith('-')) "./$relative" else relative
}
