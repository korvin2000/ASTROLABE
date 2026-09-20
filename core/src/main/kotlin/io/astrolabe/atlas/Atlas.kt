package io.astrolabe.atlas

import io.astrolabe.id.Digest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * One file of the repository, without its body (§7.1).
 *
 * [path] is workspace-relative with forward slashes on every platform. [hash8] is the first eight
 * hex characters of the SHA-256 of the raw bytes — a display and change-detection form, never an
 * identity: the authority for a file version is [io.astrolabe.id.FileVersion] (I-05).
 *
 * [imports] and [testsFor] hold workspace-relative paths that tier 0 could resolve; an import it
 * could not resolve (a package from the registry, a dynamic import) is simply absent, which is why
 * every derived answer carries `complete = false`.
 */
@Serializable
public data class AtlasRow(
    val path: String,
    val bytes: Long,
    val lang: Language,
    val hash8: String,
    val exports: List<String>,
    val imports: List<String>,
    val testsFor: List<String>,
) {
    init {
        require(path.isNotBlank()) { "an atlas path may not be blank" }
        require('\\' !in path) { "atlas paths use forward slashes; got '$path'" }
        require(bytes >= 0) { "a file size may not be negative; got $bytes" }
        require(hash8.length == HASH8_LENGTH) { "hash8 is eight hex characters; got '$hash8'" }
    }

    /** True when this file looks like a test by directory or by name convention (§7.3). */
    public val isTest: Boolean get() = isTestPath(path)

    private companion object {
        const val HASH8_LENGTH = 8
    }
}

/** Why a path is listed but not parsed (§7.1: vendor/build/generated collapsed but listed). */
@Serializable
public enum class CollapseReason {
    /** Third-party code checked in or installed: `node_modules/`, `vendor/`, `venv/`, `.venv/`. */
    Vendored,

    /** Build output: `build/`, `dist/`, `target/`, `.gradle/`. */
    Build,

    /** Generated: `__pycache__/`. */
    Generated,

    /** A minified bundle: `*.min.js`. */
    Minified,

    /** A dependency lock file: `*.lock`. */
    Lockfile,

    /** Larger than [Atlas.MAX_PARSED_BYTES]. */
    TooLarge,
}

/**
 * A path the atlas lists but does not parse. For a collapsed directory [path] is the directory and
 * [files]/[bytes] are its totals; for a collapsed file [files] is 1.
 */
@Serializable
public data class Collapsed(
    val path: String,
    val files: Int,
    val bytes: Long,
    val reason: CollapseReason,
) {
    init {
        require(path.isNotBlank()) { "a collapsed path may not be blank" }
        require(files >= 1) { "a collapsed entry covers at least one file; got $files" }
        require(bytes >= 0) { "a collapsed size may not be negative; got $bytes" }
    }
}

/**
 * The harness-built map of the repository: what exists, how big it is, what it declares and what it
 * imports — never a body (§7.1). **Models invent files; the atlas never lies about existence.**
 *
 * Built lazily on first boot ([build]), cached under the project store's disposable `indexes/`
 * directory ([save], [load]) and refreshed in O(touched) after an edit ([refresh]). Equality covers
 * [root], [rows] and [collapsed], so a rebuild from cache can be compared with a fresh build.
 *
 * Every answer derived from it (`imports`, `importers`, `refs`) is tier 0 and carries
 * `complete = false`: the atlas states what it found, never what does not exist.
 */
public data class Atlas(
    val root: Path,
    val rows: List<AtlasRow>,
    val collapsed: List<Collapsed>,
) {
    private val byPath: Map<String, AtlasRow> = rows.associateBy { it.path }
    private val outlines = ConcurrentHashMap<String, Outline>()

    /**
     * The repository key: the digest of the sorted `path hash8` lines. Identical content under the
     * same paths produces an identical key, which is what makes a cache hit decidable.
     */
    public val repoKey: Digest by lazy {
        Digest.ofUtf8(rows.sortedBy { it.path }.joinToString("\n") { "${it.path} ${it.hash8}" })
    }

    /** Total bytes of the parsed rows; collapsed entries are counted by [collapsedBytes]. */
    public val bytes: Long get() = rows.sumOf { it.bytes }

    public val collapsedBytes: Long get() = collapsed.sumOf { it.bytes }

    /** File counts by language, highest count first, then by [Language.id]. */
    public val languages: List<Pair<Language, Int>>
        get() = rows.groupingBy { it.lang }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Language, Int>> { it.value }.thenBy { it.key.id })
            .map { it.key to it.value }

    public fun row(path: String): AtlasRow? = byPath[path]

    /** Every row directly under the directory [dir] (`""` for the repository root), sorted. */
    public fun children(dir: String): List<AtlasRow> {
        val prefix = if (dir.isEmpty()) "" else "${dir.trimEnd('/')}/"
        return rows.filter { it.path.startsWith(prefix) && '/' !in it.path.substring(prefix.length) }
    }

    /** Immediate subdirectory names of [dir] (`""` for the repository root), sorted. */
    public fun subdirectories(dir: String): List<String> {
        val prefix = if (dir.isEmpty()) "" else "${dir.trimEnd('/')}/"
        return rows.asSequence()
            .filter { it.path.startsWith(prefix) }
            .map { it.path.substring(prefix.length) }
            .filter { '/' in it }
            .map { it.substringBefore('/') }
            .distinct()
            .sorted()
            .toList()
    }

    /** Total size of everything under the directory [dir], parsed rows only. */
    public fun bytesUnder(dir: String): Long {
        val prefix = if (dir.isEmpty()) "" else "${dir.trimEnd('/')}/"
        return rows.filter { it.path.startsWith(prefix) }.sumOf { it.bytes }
    }

    /** Number of parsed rows under the directory [dir]. */
    public fun filesUnder(dir: String): Int {
        val prefix = if (dir.isEmpty()) "" else "${dir.trimEnd('/')}/"
        return rows.count { it.path.startsWith(prefix) }
    }

    /**
     * The tier-0 outline of [path], parsed on demand and memoized for this instance. An unknown or
     * unreadable path yields an empty outline rather than an error.
     */
    public fun outline(path: String): Outline = outlines.getOrPut(path) {
        val row = byPath[path] ?: return@getOrPut Outline.empty(path)
        val bytes = readRelative(root, path) ?: return@getOrPut Outline.empty(path, row.lang)
        Outline.of(path, bytes)
    }

    /** Hands this instance the outlines the build already parsed, so nothing is parsed twice. */
    internal fun seedOutlines(seed: Map<String, Outline>): Atlas {
        for ((path, outline) in seed) if (path in byPath) outlines.putIfAbsent(path, outline)
        return this
    }

    /** Paths whose resolved imports name [path], sorted. Tier 0, never complete. */
    public fun importers(path: String): List<String> =
        rows.filter { path in it.imports }.map { it.path }

    /** Inbound resolved-import counts, highest first then by path; paths with none are absent. */
    public fun hubs(limit: Int): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        for (row in rows) for (target in row.imports) counts[target] = (counts[target] ?: 0) + 1
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value }
    }

    /**
     * Re-reads exactly the named [touched] paths and returns a new atlas; every untouched row is
     * carried over by identity, so a caller can assert that nothing else moved. A path that has
     * disappeared is dropped, a path that is new is added, and a path that a collapse rule now
     * covers moves to [collapsed].
     *
     * What this deliberately does *not* do: re-resolve the imports of rows that were not touched.
     * Adding a file therefore does not retroactively resolve another file's dangling import until
     * the next [build] — an incompleteness of tier 0, consistent with `complete = false`. When a
     * touched file is Kotlin or Java the outlines of the repository's *other* JVM files are also
     * consulted (memoized on this instance), because a JVM import names a package, not a path.
     */
    public fun refresh(touched: Collection<String>): Atlas {
        if (touched.isEmpty()) return this
        val wanted = touched.map(::normalizeRelative).filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return this
        val kept = rows.filterNot { it.path in wanted }
        val keptCollapsed = collapsed.filterNot { it.path in wanted }
        val added = ArrayList<AtlasRow>()
        val addedCollapsed = ArrayList<Collapsed>()
        val known = HashSet(kept.map { it.path })
        var fresh: Map<String, Outline> = emptyMap()

        val scanned = ArrayList<ScannedFile>()
        for (path in wanted.sorted()) {
            if (collapsedAncestor(path) != null) continue
            val attributes = try {
                Files.readAttributes(resolveRelative(root, path), BasicFileAttributes::class.java)
            } catch (_: IOException) {
                null
            } ?: continue
            if (!attributes.isRegularFile) continue
            val file = ScannedFile(path, attributes.size(), attributes.lastModifiedTime().toMillis())
            val reason = collapseReasonFor(path, file.size)
            if (reason != null) {
                addedCollapsed += Collapsed(path, 1, file.size, reason)
            } else {
                scanned += file
                known += path
            }
        }
        if (scanned.isNotEmpty()) {
            val parsed = parseAll(root, scanned)
            val jvmTouched = scanned.any { Language.of(it.path) in JVM_LANGUAGES }
            val context = if (jvmTouched) {
                kept.filter { it.lang in JVM_LANGUAGES }.associate { it.path to outline(it.path) }
            } else {
                emptyMap()
            }
            val resolver = ImportResolver(known, context + parsed.outlines)
            for (file in scanned) buildRow(file, parsed, resolver, known)?.let { added += it }
            fresh = parsed.outlines
        }
        return Atlas(
            root = root,
            rows = (kept + added).sortedBy { it.path },
            collapsed = (keptCollapsed + addedCollapsed).sortedBy { it.path },
        ).seedOutlines(outlines.filterKeys { it !in wanted } + fresh)
    }

    /**
     * Writes this atlas into [indexesDir] ([io.astrolabe.store.Layout.indexes], which is disposable)
     * and returns the file it wrote. The name is derived from [root], because a cache keyed by
     * content could not be found before the content is known; [repoKey] is stored inside and is
     * what [load] checks.
     */
    public fun save(indexesDir: Path): Path {
        Files.createDirectories(indexesDir)
        val stamps = rows.associate { row -> row.path to mtimeOf(resolveRelative(root, row.path)) }
        val cache = AtlasCache(
            schema = CACHE_SCHEMA,
            repoKey = repoKey.hex,
            rows = rows.map { CachedRow(it, stamps[it.path] ?: 0L) },
            collapsed = collapsed,
        )
        val target = cacheFile(indexesDir, root)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        Files.write(temporary, JSON.encodeToString(AtlasCache.serializer(), cache).toByteArray(Charsets.UTF_8))
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    public companion object {
        /** Files larger than this are listed as [CollapseReason.TooLarge] and never parsed. */
        public const val MAX_PARSED_BYTES: Long = 1L shl 20

        internal const val CACHE_SCHEMA: Int = 1

        private val JSON = Json { encodeDefaults = true }

        /**
         * Scans [root] and builds the atlas. Every regular file that no collapse rule covers is
         * read once: hashed, outlined, and its imports resolved against the other paths found.
         */
        @JvmStatic
        public fun build(root: Path): Atlas {
            val canonical = canonicalRoot(root)
            val scan = scanRepository(canonical)
            val known = scan.files.mapTo(HashSet()) { it.path }
            val parsed = parseAll(canonical, scan.files)
            val resolver = ImportResolver(known, parsed.outlines)
            val rows = scan.files.mapNotNull { buildRow(it, parsed, resolver, known) }.sortedBy { it.path }
            return Atlas(canonical, rows, scan.collapsed.sortedBy { it.path }).seedOutlines(parsed.outlines)
        }

        /**
         * The cached atlas for [root] when it is still current, else `null`.
         *
         * The tree is rescanned for `(path, size, mtime)` and a row whose stamp is unchanged keeps
         * its cached `hash8`; only changed rows are re-read. The resulting [repoKey] must equal the
         * cached one, so a stale metadata stamp can cost a needless rebuild but can never produce a
         * cache hit on content that differs. Metadata is a lookup cache here and nothing more
         * (I-05): the atlas is an orientation index, never the authority on a file version.
         */
        @JvmStatic
        public fun load(indexesDir: Path, root: Path): Atlas? {
            val canonical = canonicalRoot(root)
            val cache = readCache(cacheFile(indexesDir, canonical)) ?: return null
            if (cache.schema != CACHE_SCHEMA) return null
            val scan = scanRepository(canonical)
            if (scan.collapsed.sortedBy { it.path } != cache.collapsed.sortedBy { it.path }) return null
            val cachedByPath = cache.rows.associateBy { it.row.path }
            if (cachedByPath.size != scan.files.size) return null
            val lines = ArrayList<String>(scan.files.size)
            for (file in scan.files) {
                val cached = cachedByPath[file.path] ?: return null
                val hash8 = if (cached.row.bytes == file.size && cached.mtime == file.mtime) {
                    cached.row.hash8
                } else {
                    val bytes = readRelative(canonical, file.path) ?: return null
                    Digest.of(bytes).hash8
                }
                lines += "${file.path} $hash8"
            }
            lines.sort()
            if (Digest.ofUtf8(lines.joinToString("\n")).hex != cache.repoKey) return null
            return Atlas(canonical, cache.rows.map { it.row }.sortedBy { it.path }, cache.collapsed.sortedBy { it.path })
        }

        /** [load] when the cache is current, otherwise a fresh [build] that is then [save]d. */
        @JvmStatic
        public fun open(indexesDir: Path, root: Path): Atlas =
            load(indexesDir, root) ?: build(root).also { it.save(indexesDir) }

        /** The cache file this [root] uses inside [indexesDir]. */
        @JvmStatic
        public fun cacheFile(indexesDir: Path, root: Path): Path =
            indexesDir.resolve("atlas-${Digest.ofUtf8(canonicalRoot(root).toString()).hash8}.json")

        private fun readCache(file: Path): AtlasCache? = try {
            JSON.decodeFromString(AtlasCache.serializer(), Files.readString(file, Charsets.UTF_8))
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }

        private fun canonicalRoot(root: Path): Path = root.toAbsolutePath().normalize()
    }
}

// ------------------------------------------------------------------- caching

@Serializable
internal data class AtlasCache(
    val schema: Int,
    val repoKey: String,
    val rows: List<CachedRow>,
    val collapsed: List<Collapsed>,
)

/** A row plus the metadata stamp that lets [Atlas.load] skip re-hashing it. */
@Serializable
internal data class CachedRow(val row: AtlasRow, val mtime: Long)

// ------------------------------------------------------------------ scanning

internal data class ScannedFile(val path: String, val size: Long, val mtime: Long)

internal data class RepositoryScan(val files: List<ScannedFile>, val collapsed: List<Collapsed>)

private val COLLAPSED_DIRECTORIES: Map<String, CollapseReason> = mapOf(
    "node_modules" to CollapseReason.Vendored,
    "vendor" to CollapseReason.Vendored,
    "venv" to CollapseReason.Vendored,
    ".venv" to CollapseReason.Vendored,
    "build" to CollapseReason.Build,
    "dist" to CollapseReason.Build,
    "target" to CollapseReason.Build,
    ".gradle" to CollapseReason.Build,
    "__pycache__" to CollapseReason.Generated,
)

/** The collapse reason for a *file* path, or null when the file is parsed normally. */
internal fun collapseReasonFor(path: String, size: Long): CollapseReason? {
    val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
    if (name.endsWith(".min.js")) return CollapseReason.Minified
    if (name.endsWith(".lock")) return CollapseReason.Lockfile
    if (size > Atlas.MAX_PARSED_BYTES) return CollapseReason.TooLarge
    return null
}

/** The shortest ancestor directory of [path] a collapse rule covers, or null. */
internal fun collapsedAncestor(path: String): Pair<String, CollapseReason>? {
    val segments = path.split('/')
    for (index in 0 until segments.size - 1) {
        val reason = COLLAPSED_DIRECTORIES[segments[index].lowercase(Locale.ROOT)] ?: continue
        return segments.subList(0, index + 1).joinToString("/") to reason
    }
    return null
}

/**
 * A forward-slashed workspace-relative path, or `""` when the input is not one.
 *
 * A `..` segment makes the whole path empty rather than resolving: the atlas never looks outside
 * its own root. The full path contract — real-path resolution, symlink ancestors, case aliases —
 * belongs to `WorkspacePath` (D-47, P1.2.6); this is the atlas's own floor under it.
 */
internal fun normalizeRelative(path: String): String {
    val segments = path.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) return ""
    return segments.joinToString("/")
}

/**
 * The repository's files, classified into parsed rows and collapsed entries.
 *
 * The file list comes from `git ls-files --cached --others --exclude-standard` where [root] is a
 * git repository, so the ignore rules are exactly the ones git resolves — the same source
 * [io.astrolabe.os.search.Search] uses, which keeps the atlas and a search over it consistent.
 * Outside a repository it is a tree walk that skips only `.git`. Dotfiles are *not* skipped: the
 * D-32 rules candidate `.astrolabe/rules.md` is one, and §7.1 forbids the atlas denying existence.
 */
internal fun scanRepository(root: Path): RepositoryScan {
    val relative = gitListFiles(root) ?: walkFiles(root)
    val files = ArrayList<ScannedFile>()
    val collapsedFiles = ArrayList<Collapsed>()
    val collapsedDirectories = LinkedHashMap<String, Triple<Int, Long, CollapseReason>>()
    for (path in relative.distinct().sorted()) {
        val attributes = try {
            Files.readAttributes(resolveRelative(root, path), BasicFileAttributes::class.java)
        } catch (_: IOException) {
            continue
        }
        if (!attributes.isRegularFile) continue
        val size = attributes.size()
        val ancestor = collapsedAncestor(path)
        if (ancestor != null) {
            val (directory, reason) = ancestor
            val current = collapsedDirectories[directory]
            collapsedDirectories[directory] = if (current == null) {
                Triple(1, size, reason)
            } else {
                Triple(current.first + 1, current.second + size, reason)
            }
            continue
        }
        val reason = collapseReasonFor(path, size)
        if (reason != null) {
            collapsedFiles += Collapsed(path, 1, size, reason)
            continue
        }
        files += ScannedFile(path, size, attributes.lastModifiedTime().toMillis())
    }
    val collapsed = collapsedFiles + collapsedDirectories.map { (path, totals) ->
        Collapsed(path, totals.first, totals.second, totals.third)
    }
    return RepositoryScan(files, collapsed.sortedBy { it.path })
}

/** `git ls-files -z` separates entries with a NUL byte. */
private const val NUL: Char = '\u0000'

private fun gitListFiles(root: Path): List<String>? {
    val output = try {
        val process = ProcessBuilder("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard")
            .directory(root.toFile())
            .start()
        val stderr = Thread { process.errorStream.use { it.readBytes() } }.apply {
            isDaemon = true
            start()
        }
        val bytes = process.inputStream.use { it.readBytes() }
        val exit = process.waitFor()
        stderr.join()
        if (exit != 0) return null
        String(bytes, Charsets.UTF_8)
    } catch (_: IOException) {
        return null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return null
    }
    return output.split(NUL).filter { it.isNotEmpty() }
}

private fun walkFiles(root: Path): List<String> {
    val files = ArrayList<String>()
    try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != root && dir.fileName?.toString() == ".git") {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        files += root.relativize(file).toString().replace('\\', '/')
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )
    } catch (_: IOException) {
        return files
    }
    return files
}

/** Resolves a forward-slashed workspace-relative path against [root] on every platform. */
internal fun resolveRelative(root: Path, relative: String): Path =
    relative.split('/').filter { it.isNotEmpty() }.fold(root) { path, segment ->
        require(segment != "..") { "an atlas path may not escape the repository root; got '$relative'" }
        path.resolve(segment)
    }

internal fun readRelative(root: Path, relative: String): ByteArray? = readBytes(resolveRelative(root, relative))

internal fun readBytes(path: Path): ByteArray? = try {
    Files.readAllBytes(path)
} catch (_: NoSuchFileException) {
    null
} catch (_: IOException) {
    null
}

private fun mtimeOf(path: Path): Long = try {
    Files.getLastModifiedTime(path).toMillis()
} catch (_: IOException) {
    0L
}

internal val JVM_LANGUAGES: Set<Language> = setOf(Language.Kotlin, Language.Java)

// ------------------------------------------------------------------- parsing

internal class ParsedFiles(
    val outlines: Map<String, Outline>,
    val hashes: Map<String, String>,
)

internal fun parseAll(root: Path, files: List<ScannedFile>): ParsedFiles {
    val outlines = LinkedHashMap<String, Outline>(files.size)
    val hashes = HashMap<String, String>(files.size)
    for (file in files) {
        val bytes = readRelative(root, file.path) ?: continue
        hashes[file.path] = Digest.of(bytes).hash8
        outlines[file.path] = Outline.of(file.path, bytes)
    }
    return ParsedFiles(outlines, hashes)
}

/**
 * The row for [file], or `null` when its bytes could not be read after the scan saw it: a file that
 * vanished mid-build is left out rather than listed with an invented hash (§7.1).
 */
internal fun buildRow(
    file: ScannedFile,
    parsed: ParsedFiles,
    resolver: ImportResolver,
    known: Set<String>,
): AtlasRow? {
    val hash8 = parsed.hashes[file.path] ?: return null
    val outline = parsed.outlines[file.path] ?: Outline.empty(file.path)
    val imports = outline.importTargets
        .mapNotNull { resolver.resolve(file.path, it) }
        .filter { it != file.path }
        .distinct()
        .sorted()
    val testsFor = if (isTestPath(file.path)) {
        val byImport = imports.filterNot { isTestPath(it) }
        val byName = conventionTarget(file.path)
            ?.let { name -> known.filter { !isTestPath(it) && it.substringAfterLast('/') == name } }
            ?.singleOrNull()
        (byImport + listOfNotNull(byName)).distinct().sorted()
    } else {
        emptyList()
    }
    return AtlasRow(
        path = file.path,
        bytes = file.size,
        lang = outline.language,
        hash8 = hash8,
        exports = outline.exports,
        imports = imports,
        testsFor = testsFor,
    )
}

// ---------------------------------------------------------- test conventions

private val TEST_DIRECTORIES = setOf("test", "tests", "__tests__", "spec", "specs")

/** True when [path] is a test by directory placement or by file-name convention (§7.3). */
internal fun isTestPath(path: String): Boolean {
    val segments = path.split('/')
    if (segments.dropLast(1).any { it.lowercase(Locale.ROOT) in TEST_DIRECTORIES }) return true
    val name = segments.last()
    val base = if (name.lastIndexOf('.') > 0) name.substring(0, name.lastIndexOf('.')) else name
    return base.startsWith("test_") ||
        base.endsWith("_test") ||
        base.endsWith(".test") ||
        base.endsWith(".spec") ||
        base.endsWith("Test") ||
        base.endsWith("Tests") ||
        base.endsWith("Spec")
}

/** The source file name a test file's own name points at, or null when it follows no convention. */
internal fun conventionTarget(testPath: String): String? {
    val name = testPath.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return null
    val extension = name.substring(dot)
    val base = name.substring(0, dot)
    val stripped = when {
        base.startsWith("test_") -> base.removePrefix("test_")
        base.endsWith("_test") -> base.removeSuffix("_test")
        base.endsWith(".test") -> base.removeSuffix(".test")
        base.endsWith(".spec") -> base.removeSuffix(".spec")
        base.endsWith("Tests") -> base.removeSuffix("Tests")
        base.endsWith("Test") -> base.removeSuffix("Test")
        base.endsWith("Spec") -> base.removeSuffix("Spec")
        else -> return null
    }
    return if (stripped.isEmpty()) null else stripped + extension
}

// ------------------------------------------------------------ import resolver

private val SCRIPT_EXTENSIONS = listOf(".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs", ".d.ts")

/**
 * Turns the raw import targets tier 0 read out of a file into workspace-relative paths.
 *
 * Python resolves by module path (including relative `from .user import x`), JS/TS by relative
 * specifier with the usual extension and `index` candidates, Kotlin and Java by declared package
 * plus declaration name. A bare specifier that names an installed package resolves to nothing,
 * which is the honest answer at tier 0.
 */
internal class ImportResolver(
    private val known: Set<String>,
    outlines: Map<String, Outline?>,
) {
    /** `pay.Request` → the file declaring it, for the JVM languages. */
    private val byQualifiedName: Map<String, String> = buildMap {
        for ((path, outline) in outlines) {
            val namespace = outline?.namespace ?: continue
            if (outline.language != Language.Kotlin && outline.language != Language.Java) continue
            val simple = path.substringAfterLast('/').substringBeforeLast('.')
            putIfAbsent(qualify(namespace, simple), path)
            for (entry in outline.entries) {
                if (entry.kind == DeclarationKind.Class ||
                    entry.kind == DeclarationKind.Function ||
                    entry.kind == DeclarationKind.Const
                ) {
                    putIfAbsent(qualify(namespace, entry.name), path)
                }
            }
        }
    }

    fun resolve(from: String, target: String): String? {
        val cleaned = target.trim().trimEnd('.')
        if (cleaned.isEmpty()) return null
        return when (Language.of(from)) {
            Language.Python -> resolvePython(from, cleaned)
            Language.JavaScript, Language.TypeScript -> resolveScript(from, cleaned)
            Language.Kotlin, Language.Java -> byQualifiedName[cleaned] ?: resolveByPathGuess(cleaned)
            else -> null
        }
    }

    private fun resolvePython(from: String, target: String): String? {
        val dots = target.takeWhile { it == '.' }.length
        val base = if (dots == 0) {
            target.replace('.', '/')
        } else {
            var directory = from.substringBeforeLast('/', "")
            repeat(dots - 1) { directory = directory.substringBeforeLast('/', "") }
            val rest = target.drop(dots).replace('.', '/')
            listOf(directory, rest).filter { it.isNotEmpty() }.joinToString("/")
        }
        if (base.isEmpty()) return null
        for (candidate in listOf("$base.py", "$base/__init__.py", "$base.pyi")) {
            if (candidate in known) return candidate
        }
        // `from pay.router import Router` with no `pay/router.py`: the last segment may be a symbol.
        val parent = base.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            for (candidate in listOf("$parent.py", "$parent/__init__.py")) {
                if (candidate in known) return candidate
            }
        }
        return null
    }

    private fun resolveScript(from: String, target: String): String? {
        if (!target.startsWith(".")) return null
        val directory = from.substringBeforeLast('/', "")
        val joined = if (directory.isEmpty()) target else "$directory/$target"
        val base = normalizeDots(joined) ?: return null
        if (base in known) return base
        for (extension in SCRIPT_EXTENSIONS) {
            if ("$base$extension" in known) return "$base$extension"
            if ("$base/index$extension" in known) return "$base/index$extension"
        }
        // NodeNext TypeScript sources import a `.js` specifier that resolves to the `.ts` file.
        val withoutJs = base.removeSuffix(".js")
        if (withoutJs != base) {
            for (extension in listOf(".ts", ".tsx")) {
                if ("$withoutJs$extension" in known) return "$withoutJs$extension"
            }
        }
        return null
    }

    private fun resolveByPathGuess(target: String): String? {
        val suffix = target.replace('.', '/')
        for (extension in listOf(".kt", ".java", ".kts")) {
            val candidate = "$suffix$extension"
            if (candidate in known) return candidate
            val matches = known.filter { it.endsWith("/$candidate") }
            if (matches.size == 1) return matches.single()
        }
        return null
    }

    private fun qualify(namespace: String, name: String): String =
        if (namespace.isEmpty()) name else "$namespace.$name"

    private fun normalizeDots(path: String): String? {
        val out = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
                else -> out += segment
            }
        }
        return out.takeIf { it.isNotEmpty() }?.joinToString("/")
    }
}
