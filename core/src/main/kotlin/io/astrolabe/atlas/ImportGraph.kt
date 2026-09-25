package io.astrolabe.atlas

import io.astrolabe.id.WorkspaceId
import java.util.Locale

/**
 * The runtime import graph over an [Atlas] (§7.3), produced into the P3.2.7 kernel projections:
 * [graph] is the [ImpactGraph] that [Impact.analyze] consumes; there is no parallel graph type.
 *
 * Extraction is the tier-0 regex outline plus the atlas resolver, so [ImpactGraph.tier] is
 * [IndexTier.Lexical] and the kernel never claims a complete blast over it (§7.3 asks for tier 1+),
 * unless an [OutlineSource] supplies tier-1 outlines.
 * Per-file completeness is the extraction verdict itself: a dynamic import, reflection, a
 * generated file or an import that is neither resolved nor clearly external ⇒ [isComplete] `false`
 * with the reason in [ImpactGraph.unresolved] (§7.3: reflection, plugins, generated clients).
 *
 * Package identity (D-87) is the nearest build manifest directory (`pyproject.toml`, `setup.py`,
 * `package.json`, `build.gradle(.kts)`, `settings.gradle(.kts)`, `pom.xml`): nested packages are
 * named by that directory's workspace-relative path, the root package by the root directory's name
 * (`.` when that name is unavailable or collides), and a file under no manifest has an unknown
 * package. Every file imports its own package's manifests, Gradle `project(":x")` and Maven
 * sibling `<dependency>` edges join manifests, and `settings.gradle` / aggregator `pom.xml` import
 * the modules they include, so a build-script edit blasts through the module it configures.
 */
public class ImportGraph private constructor(
    public val atlas: Atlas,
    public val workspace: WorkspaceId,
    public val graph: ImpactGraph,
    private val files: Map<String, ImpactFile>,
    private val reasons: Map<String, List<String>>,
    private val reverse: Map<String, List<ImpactFile>>,
    private val packages: Map<String, String>,
    /** Atlas rows the kernel cannot identify (a `:` in the path); they make the graph incomplete. */
    public val omitted: Set<String>,
) {
    /** The package id of the file or directory at [path] (D-87), or null when no manifest covers it. */
    public fun packageOf(path: String): String? {
        var directory = normalizeRelative(path)
        while (true) {
            packages[directory]?.let { return it }
            if (directory.isEmpty()) return null
            directory = directory.substringBeforeLast('/', "")
        }
    }

    /** The package scope of the file at [path]; an unknown package is the workspace scope. */
    public fun scopeOf(path: String): ImpactScope =
        ImpactScope(workspace, packageOf(normalizeRelative(path).substringBeforeLast('/', "")))

    /** The suite scope of the directory [dir]. */
    public fun scopeOfDirectory(dir: String): ImpactScope = ImpactScope(workspace, packageOf(dir))

    /** The kernel identity of [path]; a path outside the atlas is still identified, just not in [graph]. */
    public fun file(path: String): ImpactFile {
        val normalized = normalizeRelative(path)
        return files[normalized] ?: ImpactFile(scopeOf(normalized), normalized)
    }

    public operator fun contains(path: String): Boolean = normalizeRelative(path) in files

    /** False when the extraction found something it could not follow; the reasons are [unresolved]. */
    public fun isComplete(path: String): Boolean = reasons[normalizeRelative(path)].isNullOrEmpty()

    public fun unresolved(path: String): List<String> = reasons[normalizeRelative(path)].orEmpty()

    /** Direct importers of [path], sorted; the transitive closure is the kernel's reverse BFS. */
    public fun importers(path: String): Set<ImpactFile> =
        impactSet(reverse[normalizeRelative(path)].orEmpty().sortedWith(impactFileOrder))

    /** Graph files under the directory [dir] (`""` for the whole workspace). */
    public fun filesUnder(dir: String): Set<ImpactFile> {
        val normalized = normalizeRelative(dir)
        val prefix = if (normalized.isEmpty()) "" else "$normalized/"
        return impactSet(files.values.filter { it.path.startsWith(prefix) }.sortedWith(impactFileOrder))
    }

    /**
     * `tests_for(paths)` (§7.3): test rows whose atlas `tests_for` edges name one of [paths] plus test
     * files whose name convention points at one of their base names. Over-selection by a shared base
     * name is accepted; a missed test is what the package widening covers.
     */
    public fun testsFor(paths: Collection<String>): Set<ImpactFile> {
        val wanted = paths.map(::normalizeRelative).filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return emptySet()
        val names = wanted.mapTo(HashSet()) { it.substringAfterLast('/') }
        val found = ArrayList<ImpactFile>()
        for (row in atlas.rows) {
            if (!row.isTest) continue
            val file = files[row.path] ?: continue
            val byRow = row.testsFor.any { it in wanted }
            val byName = conventionTarget(row.path)?.let { it in names } ?: false
            if (byRow || byName) found += file
        }
        return impactSet(found.sortedWith(impactFileOrder))
    }

    public companion object {
        private const val VERSION_PREFIX = "atlas-"
        private const val PROVENANCE = "atlas tier 0 outline + resolver"
        private const val SYNTAX_PROVENANCE = "tier 1 syntax outline + resolver"
        private val UNEXTRACTED = setOf(Language.Go, Language.Rust, Language.Shell)
        private const val DESCRIPTION_LIMIT = 120
        private const val GENERATED_PROBE_LINES = 8

        internal val MANIFESTS = setOf(
            "pyproject.toml", "setup.py", "package.json",
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "pom.xml",
        )
        private val GRADLE_BUILD = listOf("build.gradle.kts", "build.gradle")
        private val GENERATED_MARKERS = listOf("do not edit", "@generated", "code generated", "generated by")

        private val PY_DYNAMIC = Regex("""\b(?:importlib\.import_module|__import__|import_module)\s*\(""")
        private val JS_DYNAMIC_IMPORT = Regex("""(?<![\w.$])import\s*\(""")
        private val JS_DYNAMIC_REQUIRE = Regex("""\brequire\s*\(\s*(?!['"`])[^)\s]""")
        private val JVM_REFLECTION = Regex("""\b(?:Class\.forName|ServiceLoader\.load|ServiceLoader\.loadInstalled)\s*[(<]""")
        private val GRADLE_INCLUDE = Regex("""\binclude\s*\(?\s*((?:["'][^"']+["']\s*,?\s*)+)\)?""")
        private val GRADLE_PROJECT = Regex("""\bproject\s*\(\s*["']:?([^"']+)["']\s*\)""")
        private val QUOTED = Regex("""["']([^"']+)["']""")
        private val POM_MODULE = Regex("""<module>\s*([^<\s]+)\s*</module>""")
        private val POM_PARENT = Regex("""<parent>[\s\S]*?</parent>""")
        private val POM_ARTIFACT = Regex("""<artifactId>\s*([^<\s]+)\s*</artifactId>""")
        private val POM_DEPENDENCY = Regex("""<dependency>([\s\S]*?)</dependency>""")
        private val PACKAGE_NAME = Regex(""""name"\s*:\s*"([^"]+)"""")
        private val PACKAGE_MAIN = Regex(""""(?:main|module|types)"\s*:\s*"([^"]+)"""")
        private val SCRIPT_INDEX = listOf(
            "src/index.ts", "src/index.tsx", "src/index.js", "src/index.mjs", "index.ts", "index.js", "index.mjs",
        )

        /** Builds the graph over every parsed row of [atlas], reading each D-09 file once for the dynamic scan. */
        @JvmStatic
        public fun of(atlas: Atlas, workspace: WorkspaceId): ImportGraph = of(atlas, workspace, OutlineSource(atlas::outline))

        /**
         * Builds the graph from [source]'s outlines. The graph is [IndexTier.Syntax] only when every
         * D-09 file's outline is tier 1 or better; then a D-09 file whose parse had errors and a Go,
         * Rust or shell file (no import extractor) are unresolved, so a narrow blast is never claimed
         * over imports nobody extracted (D-213).
         */
        @JvmStatic
        public fun of(atlas: Atlas, workspace: WorkspaceId, source: OutlineSource): ImportGraph {
            val known = atlas.rows.mapTo(HashSet()) { it.path }
            val packages = packageIds(atlas)
            fun packageOf(path: String): String? {
                var directory = path
                while (true) {
                    packages[directory]?.let { return it }
                    if (directory.isEmpty()) return null
                    directory = directory.substringBeforeLast('/', "")
                }
            }
            fun scopeOf(path: String) = ImpactScope(workspace, packageOf(path.substringBeforeLast('/', "")))

            val files = LinkedHashMap<String, ImpactFile>()
            val omitted = LinkedHashSet<String>()
            for (row in atlas.rows) {
                if (':' in row.path || row.path != normalizeRelative(row.path)) {
                    omitted += row.path
                } else {
                    files[row.path] = ImpactFile(scopeOf(row.path), row.path)
                }
            }

            val outlines = LinkedHashMap<String, Outline>()
            for (path in files.keys) outlines[path] = source.outline(path)
            val parsed = outlines.values.filter { it.language.hasOutlineParser }
            val tier = if (parsed.isNotEmpty() && parsed.all { it.tier.level >= IndexTier.Syntax.level }) IndexTier.Syntax else IndexTier.Lexical
            val resolver = ImportResolver(known, outlines)
            val namespaces = HashMap<String, MutableList<String>>()
            for ((path, outline) in outlines) {
                if (outline.language in JVM_LANGUAGES) outline.namespace?.let { namespaces.getOrPut(it, ::ArrayList) += path }
            }
            val pythonRoots = pythonRoots(files.keys, packages)
            val scriptPackages = scriptPackages(atlas, files.keys)
            val manifestsByDirectory = files.keys.filter { it.substringAfterLast('/') in MANIFESTS }
                .groupBy { it.substringBeforeLast('/', "") }

            val edges = LinkedHashSet<ImpactImport>()
            val reasons = LinkedHashMap<String, MutableList<String>>()
            fun edge(from: String, to: String) {
                if (from != to && to in files) edges += ImpactImport(files.getValue(from), files.getValue(to))
            }
            fun reason(path: String, description: String) {
                val cleaned = description.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(DESCRIPTION_LIMIT).trim()
                if (cleaned.isNotEmpty()) reasons.getOrPut(path, ::ArrayList).apply { if (cleaned !in this) add(cleaned) }
            }

            for (path in files.keys) {
                val outline = outlines.getValue(path)
                val language = outline.language
                // Every file depends on the manifests of its own package (a dependency bump touches all of it).
                var directory = path.substringBeforeLast('/', "")
                while (true) {
                    val manifests = manifestsByDirectory[directory]
                    if (manifests != null) {
                        for (manifest in manifests) edge(path, manifest)
                        break
                    }
                    if (directory.isEmpty()) break
                    directory = directory.substringBeforeLast('/', "")
                }
                for (target in outline.importTargets) {
                    val cleaned = target.trim().trimEnd('.')
                    if (cleaned.isEmpty()) continue
                    val resolved = resolver.resolve(path, cleaned)
                    if (resolved != null) {
                        edge(path, resolved)
                        continue
                    }
                    when (language) {
                        Language.Python -> {
                            val local = resolvePythonInPackage(path, cleaned, known, ::packageOf, packages)
                            when {
                                local != null -> edge(path, local)
                                cleaned.startsWith(".") || cleaned.substringBefore('.') in pythonRoots ->
                                    reason(path, "unresolved import $cleaned")
                            }
                        }
                        Language.JavaScript, Language.TypeScript -> {
                            if (cleaned.startsWith(".") || cleaned.startsWith("/")) {
                                reason(path, "unresolved import $cleaned")
                            } else {
                                resolveScriptPackage(cleaned, scriptPackages, known)?.let { edge(path, it) }
                            }
                        }
                        Language.Kotlin, Language.Java -> {
                            val members = namespaces[cleaned]
                            when {
                                members != null -> for (member in members) edge(path, member)
                                namespaces.keys.any { it.startsWith("$cleaned.") || cleaned.startsWith("$it.") } ->
                                    reason(path, "unresolved import $cleaned")
                            }
                        }
                        else -> Unit
                    }
                }
                if (tier != IndexTier.Lexical) {
                    if (language.hasOutlineParser && !outline.complete) reason(path, "syntax errors: imports may be missing")
                    if (language in UNEXTRACTED) reason(path, "no import extraction for ${language.id}")
                }
                if (language.hasOutlineParser) {
                    val text = readRelative(atlas.root, path)?.toString(Charsets.UTF_8) ?: continue
                    scanDynamic(path, language, text, ::reason)
                }
            }
            buildEdges(atlas, files.keys, known, ::edge)

            val reverse = HashMap<String, MutableList<ImpactFile>>()
            for (edge in edges) reverse.getOrPut(edge.dependency.path, ::ArrayList) += edge.importer
            val unresolved = reasons.flatMap { (path, list) -> list.map { ImpactDependency(files.getValue(path), it) } }
            val graph = ImpactGraph(
                version = VERSION_PREFIX + atlas.repoKey.hash8,
                provenance = if (tier == IndexTier.Lexical) PROVENANCE else SYNTAX_PROVENANCE,
                files = files.values.toSet(),
                imports = edges,
                tier = tier,
                complete = reasons.isEmpty() && omitted.isEmpty(),
                coverage = files.values.mapNotNullTo(LinkedHashSet()) { it.scope.takeIf { scope -> scope.packageId != null } },
                unresolved = unresolved.toSet(),
            )
            return ImportGraph(atlas, workspace, graph, files, reasons, reverse, packages, omitted)
        }

        /** Manifest directory → package id (D-87). */
        private fun packageIds(atlas: Atlas): Map<String, String> {
            val directories = atlas.rows.asSequence()
                .filter { it.path.substringAfterLast('/') in MANIFESTS }
                .map { it.path.substringBeforeLast('/', "") }
                .toSortedSet()
            val ids = LinkedHashMap<String, String>()
            for (directory in directories) if (directory.isNotEmpty()) ids[directory] = directory
            if ("" in directories) {
                val name = atlas.root.fileName?.toString()?.trim()?.takeIf { it.isNotEmpty() && it !in ids.values }
                ids[""] = name ?: "."
            }
            return ids
        }

        /** First segments that name in-repository Python modules, seen from the root, each package and its `src`. */
        private fun pythonRoots(paths: Set<String>, packages: Map<String, String>): Set<String> {
            val roots = HashSet<String>()
            val bases = HashSet<String>().apply { add(""); addAll(packages.keys); for (dir in packages.keys) add(if (dir.isEmpty()) "src" else "$dir/src") }
            for (path in paths) {
                if (Language.of(path) != Language.Python) continue
                for (base in bases) {
                    val prefix = if (base.isEmpty()) "" else "$base/"
                    if (!path.startsWith(prefix)) continue
                    val first = path.substring(prefix.length).substringBefore('/')
                    roots += if ('/' in path.substring(prefix.length)) first else first.substringBeforeLast('.')
                }
            }
            return roots
        }

        /** A monorepo's absolute Python import resolved from the importing file's own package directory. */
        private fun resolvePythonInPackage(
            from: String,
            target: String,
            known: Set<String>,
            packageOf: (String) -> String?,
            packages: Map<String, String>,
        ): String? {
            if (target.startsWith(".")) return null
            val id = packageOf(from.substringBeforeLast('/', "")) ?: return null
            val directory = packages.entries.firstOrNull { it.value == id }?.key ?: return null
            if (directory.isEmpty()) return null
            val relative = target.replace('.', '/')
            for (base in listOf(directory, "$directory/src")) {
                for (candidate in listOf("$base/$relative.py", "$base/$relative/__init__.py", "$base/$relative.pyi")) {
                    if (candidate in known) return candidate
                }
                val parent = relative.substringBeforeLast('/', "")
                if (parent.isNotEmpty()) {
                    for (candidate in listOf("$base/$parent.py", "$base/$parent/__init__.py")) if (candidate in known) return candidate
                }
            }
            return null
        }

        /** `package.json` name → the entry file it exposes (its `main`, an index, else the manifest itself). */
        private fun scriptPackages(atlas: Atlas, paths: Set<String>): Map<String, String> {
            val entries = HashMap<String, String>()
            for (path in paths) {
                if (path.substringAfterLast('/') != "package.json") continue
                val text = readRelative(atlas.root, path)?.toString(Charsets.UTF_8) ?: continue
                val name = PACKAGE_NAME.find(text)?.groupValues?.get(1) ?: continue
                val directory = path.substringBeforeLast('/', "")
                val prefix = if (directory.isEmpty()) "" else "$directory/"
                val main = PACKAGE_MAIN.find(text)?.groupValues?.get(1)?.let { normalizeRelative(prefix + it) }
                val entry = main?.takeIf { it in paths }
                    ?: SCRIPT_INDEX.map { prefix + it }.firstOrNull { it in paths }
                    ?: path
                entries.putIfAbsent(name, entry)
            }
            return entries
        }

        private fun resolveScriptPackage(specifier: String, packages: Map<String, String>, known: Set<String>): String? {
            packages[specifier]?.let { return it }
            val match = packages.keys.filter { specifier.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
            val entry = packages.getValue(match)
            val directory = entry.substringBeforeLast('/', "").let {
                when {
                    it == "src" -> ""
                    it.endsWith("/src") -> it.removeSuffix("/src")
                    else -> it
                }
            }
            val subpath = normalizeRelative((if (directory.isEmpty()) "" else "$directory/") + specifier.removePrefix("$match/"))
            for (candidate in listOf(subpath, "$subpath.ts", "$subpath.tsx", "$subpath.js", "$subpath.mjs", "$subpath/index.ts", "$subpath/index.js")) {
                if (candidate in known) return candidate
            }
            return entry
        }

        private fun scanDynamic(path: String, language: Language, text: String, reason: (String, String) -> Unit) {
            val lines = text.lineSequence().toList()
            for (line in lines.take(GENERATED_PROBE_LINES)) {
                val lower = line.lowercase(Locale.ROOT)
                if (GENERATED_MARKERS.any { it in lower }) {
                    reason(path, "generated file: ${line.trim()}")
                    break
                }
            }
            val patterns = when (language) {
                Language.Python -> listOf(PY_DYNAMIC)
                Language.JavaScript, Language.TypeScript -> listOf(JS_DYNAMIC_IMPORT, JS_DYNAMIC_REQUIRE)
                Language.Kotlin, Language.Java -> listOf(JVM_REFLECTION)
                else -> return
            }
            val comment = if (language == Language.Python) "#" else "//"
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith(comment) || trimmed.startsWith("*")) continue
                for (pattern in patterns) {
                    val match = pattern.find(line) ?: continue
                    val kind = if (pattern === JVM_REFLECTION) "reflection" else "dynamic import"
                    reason(path, "$kind at line ${index + 1}: ${match.value.takeWhile { it != '(' && it != '<' }.trim()}")
                }
            }
        }

        /** Gradle `include`/`project(":x")` and Maven `<module>`/sibling `<dependency>` edges between manifests. */
        private fun buildEdges(atlas: Atlas, paths: Set<String>, known: Set<String>, edge: (String, String) -> Unit) {
            fun gradleBuildOf(directory: String): String? =
                GRADLE_BUILD.map { if (directory.isEmpty()) it else "$directory/$it" }.firstOrNull { it in known }
            val artifacts = HashMap<String, String>()
            for (path in paths) {
                if (path.substringAfterLast('/') != "pom.xml") continue
                val text = readRelative(atlas.root, path)?.toString(Charsets.UTF_8) ?: continue
                val own = POM_ARTIFACT.find(POM_PARENT.replace(text, "").substringBefore("<dependencies>"))?.groupValues?.get(1)
                if (own != null) artifacts.putIfAbsent(own, path)
            }
            for (path in paths) {
                val name = path.substringAfterLast('/')
                val directory = path.substringBeforeLast('/', "")
                val prefix = if (directory.isEmpty()) "" else "$directory/"
                when (name) {
                    "settings.gradle", "settings.gradle.kts" -> {
                        val text = readRelative(atlas.root, path)?.toString(Charsets.UTF_8) ?: continue
                        for (include in GRADLE_INCLUDE.findAll(text)) {
                            for (quoted in QUOTED.findAll(include.groupValues[1])) {
                                val module = quoted.groupValues[1].trim(':').replace(':', '/')
                                gradleBuildOf(normalizeRelative(prefix + module))?.let { edge(path, it) }
                            }
                        }
                    }
                    "build.gradle", "build.gradle.kts" -> {
                        val text = readRelative(atlas.root, path)?.toString(Charsets.UTF_8) ?: continue
                        val settings = generateSequence(directory) { it.substringBeforeLast('/', "").takeIf { _ -> it.isNotEmpty() } }
                            .map { dir -> if (dir.isEmpty()) "" else "$dir/" }
                            .firstOrNull { "${it}settings.gradle.kts" in known || "${it}settings.gradle" in known } ?: ""
                        for (project in GRADLE_PROJECT.findAll(text)) {
                            val module = project.groupValues[1].trim(':').replace(':', '/')
                            gradleBuildOf(normalizeRelative(settings + module))?.let { edge(path, it) }
                        }
                    }
                    "pom.xml" -> {
                        val text = readRelative(atlas.root, path)?.toString(Charsets.UTF_8) ?: continue
                        for (module in POM_MODULE.findAll(text)) {
                            val pom = normalizeRelative(prefix + module.groupValues[1] + "/pom.xml")
                            if (pom in known) edge(path, pom)
                        }
                        for (dependency in POM_DEPENDENCY.findAll(text)) {
                            val artifact = POM_ARTIFACT.find(dependency.groupValues[1])?.groupValues?.get(1) ?: continue
                            artifacts[artifact]?.let { edge(path, it) }
                        }
                    }
                }
            }
        }
    }
}
