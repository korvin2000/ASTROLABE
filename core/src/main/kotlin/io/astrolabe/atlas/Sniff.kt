package io.astrolabe.atlas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path

/** A manifest file whose contents declare how a package is tested, built, linted and checked. */
@Serializable
public enum class Manifest(public val fileName: String) {
    Makefile("Makefile"),
    PyProject("pyproject.toml"),
    PackageJson("package.json"),
    CargoToml("Cargo.toml"),
    GoMod("go.mod"),
    GradleKts("build.gradle.kts"),
    GradleGroovy("build.gradle"),
    PomXml("pom.xml"),
    ;

    public companion object {
        private val BY_NAME: Map<String, Manifest> = Manifest.entries.associateBy { it.fileName }

        /** The manifest a file name denotes, or `null`. Names are matched exactly, as on disk. */
        @JvmStatic
        public fun of(fileName: String): Manifest? = BY_NAME[fileName]
    }
}

/**
 * The commands one package declares. Each is an argv list, never a shell string, so nothing is ever
 * re-parsed by a shell; a `null` field means **the manifest does not declare one** and is rendered
 * `none`. Nothing here is guessed: an absent declaration stays absent (§7.7).
 *
 * The runner resolves the executable: `gradlew` means the wrapper script for the host
 * (`./gradlew` on POSIX, `gradlew.bat` on Windows), and `python` means the interpreter the runner
 * selected.
 */
@Serializable
public data class PackageCommands(
    val dir: String,
    val manifest: Manifest,
    val test: List<String>? = null,
    val build: List<String>? = null,
    val lint: List<String>? = null,
    val typecheck: List<String>? = null,
) {
    init {
        require(dir.isNotEmpty()) { "a package directory is '.' at the repository root, never empty" }
        require('\\' !in dir) { "package directories use forward slashes; got '$dir'" }
    }

    /** True when the manifest declared nothing at all. */
    public val isEmpty: Boolean get() = test == null && build == null && lint == null && typecheck == null

    /** The manifest's repository-relative path. */
    public val manifestPath: String
        get() = if (dir == ROOT) manifest.fileName else "$dir/${manifest.fileName}"

    public companion object {
        public const val ROOT: String = "."
    }
}

/** Every package the repository declares, ordered by directory then manifest (§7.7). */
@Serializable
public data class Sniffed(val packages: List<PackageCommands>) {
    public val isEmpty: Boolean get() = packages.isEmpty()

    public companion object {
        public val NONE: Sniffed = Sniffed(emptyList())
    }
}

/**
 * Reads the repository's manifests and reports the commands they declare (§7.7, D-09).
 *
 * The rule throughout is *declared, not inferred*: `pyproject.toml` yields pytest only when it
 * configures pytest, `package.json` yields what its `scripts` contain, and a repository with no
 * manifest yields [Sniffed.NONE]. In particular a `node --check` script is never reported as a
 * type check, because `node --check` does not validate TypeScript (D-09).
 */
public object Sniff {

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Every package of [atlas] with the commands its manifest declares. */
    @JvmStatic
    public fun commands(atlas: Atlas): Sniffed = commands(atlas.root, atlas.rows.map { it.path }.toSet())

    /** The same over an explicit path set, for callers that have not built an atlas. */
    @JvmStatic
    public fun commands(root: Path, paths: Set<String>): Sniffed {
        val found = ArrayList<PackageCommands>()
        for (path in paths.sorted()) {
            val manifest = Manifest.of(path.substringAfterLast('/')) ?: continue
            val dir = path.substringBeforeLast('/', "").ifEmpty { PackageCommands.ROOT }
            val text = readRelative(root, path)?.toString(Charsets.UTF_8) ?: continue
            found += when (manifest) {
                Manifest.Makefile -> makefile(dir, text)
                Manifest.PyProject -> pyproject(dir, text, paths)
                Manifest.PackageJson -> packageJson(dir, text, paths)
                Manifest.CargoToml -> cargo(dir)
                Manifest.GoMod -> go(dir)
                Manifest.GradleKts, Manifest.GradleGroovy -> gradle(dir, manifest, paths)
                Manifest.PomXml -> maven(dir)
            }
        }
        return Sniffed(found.sortedWith(compareBy({ it.dir }, { it.manifest.name })))
    }

    // --------------------------------------------------------------- Python

    private fun pyproject(dir: String, text: String, paths: Set<String>): PackageCommands {
        val sections = tomlSections(text)
        val test = when {
            "tool.pytest.ini_options" in sections -> listOf("python", "-m", "pytest", "-q")
            hasDirectory(dir, "tests", paths) -> listOf("python", "-m", "unittest", "discover", "-s", "tests")
            else -> null
        }
        val lint = when {
            "tool.ruff" in sections || sections.any { it.startsWith("tool.ruff.") } -> ruff()
            hasSibling(dir, "ruff.toml", paths) || hasSibling(dir, ".ruff.toml", paths) -> ruff()
            else -> null
        }
        val typecheck = when {
            "tool.mypy" in sections || hasSibling(dir, "mypy.ini", paths) -> listOf("python", "-m", "mypy", ".")
            "tool.pyright" in sections -> listOf("pyright")
            else -> null
        }
        return PackageCommands(dir, Manifest.PyProject, test = test, lint = lint, typecheck = typecheck)
    }

    private fun ruff(): List<String> = listOf("python", "-m", "ruff", "check", ".")

    // ------------------------------------------------------------- JS and TS

    private fun packageJson(dir: String, text: String, paths: Set<String>): PackageCommands {
        val root = parseJsonObject(text)
        val scripts = (root?.get("scripts") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
            ?.toMap()
            .orEmpty()
        val devDependencies = (root?.get("devDependencies") as? JsonObject)?.keys.orEmpty() +
            (root?.get("dependencies") as? JsonObject)?.keys.orEmpty()

        val test = scripts["test"]?.let { script ->
            // Prefer the direct runner when the script is a bare runner: one less process, and the
            // argv is what a runner can actually own.
            if (script.trim() == "node --test") listOf("node", "--test") else listOf("npm", "test")
        }
        val build = scripts["build"]?.let { listOf("npm", "run", "build") }
        val lint = scripts["lint"]?.let { listOf("npm", "run", "lint") }
        val typecheckScript = listOf("typecheck", "type-check", "check")
            .firstOrNull { name -> scripts[name]?.let { !isNodeCheck(it) } == true }
        val typecheck = when {
            typecheckScript != null -> listOf("npm", "run", typecheckScript)
            hasSibling(dir, "tsconfig.json", paths) && "typescript" in devDependencies ->
                listOf("npx", "tsc", "--noEmit")

            else -> null
        }
        return PackageCommands(dir, Manifest.PackageJson, test, build, lint, typecheck)
    }

    /** D-09: `node --check` parses JavaScript; it never validates TypeScript, so it is not a type check. */
    private fun isNodeCheck(script: String): Boolean = Regex("""(^|[|&;]\s*)node\s+--check\b""").containsMatchIn(script)

    // ----------------------------------------------------------- other tools

    private fun cargo(dir: String) = PackageCommands(
        dir = dir,
        manifest = Manifest.CargoToml,
        test = listOf("cargo", "test"),
        build = listOf("cargo", "build"),
        lint = listOf("cargo", "clippy"),
    )

    private fun go(dir: String) = PackageCommands(
        dir = dir,
        manifest = Manifest.GoMod,
        test = listOf("go", "test", "./..."),
        build = listOf("go", "build", "./..."),
        lint = listOf("go", "vet", "./..."),
    )

    private fun gradle(dir: String, manifest: Manifest, paths: Set<String>): PackageCommands {
        val launcher = if (hasWrapper(dir, paths)) "gradlew" else "gradle"
        return PackageCommands(
            dir = dir,
            manifest = manifest,
            test = listOf(launcher, "test"),
            build = listOf(launcher, "build"),
        )
    }

    private fun maven(dir: String) = PackageCommands(
        dir = dir,
        manifest = Manifest.PomXml,
        test = listOf("mvn", "-q", "test"),
        build = listOf("mvn", "-q", "-DskipTests", "package"),
    )

    private val MAKE_TARGET = Regex("""^([A-Za-z0-9_][A-Za-z0-9_.\-]*)\s*:(?!=)""")

    private fun makefile(dir: String, text: String): PackageCommands {
        val targets = splitLines(text)
            .mapNotNull { MAKE_TARGET.find(it)?.groupValues?.get(1) }
            .toSet()
        fun target(vararg names: String): List<String>? =
            names.firstOrNull { it in targets }?.let { listOf("make", it) }
        return PackageCommands(
            dir = dir,
            manifest = Manifest.Makefile,
            test = target("test"),
            build = target("build"),
            lint = target("lint"),
            typecheck = target("typecheck", "type-check"),
        )
    }

    // ---------------------------------------------------------------- helpers

    /** Section headers of a TOML document, `[tool.ruff.lint]` → `tool.ruff.lint`. Not a TOML parser. */
    private fun tomlSections(text: String): Set<String> =
        splitLines(text)
            .map { it.trim() }
            .filter { it.startsWith("[") && it.endsWith("]") }
            .map { it.trim('[', ']').trim() }
            .toSet()

    private fun parseJsonObject(text: String): JsonObject? = try {
        JSON.parseToJsonElement(text).jsonObject
    } catch (_: RuntimeException) {
        null
    }

    private fun sibling(dir: String, name: String): String =
        if (dir == PackageCommands.ROOT) name else "$dir/$name"

    private fun hasSibling(dir: String, name: String, paths: Set<String>): Boolean = sibling(dir, name) in paths

    private fun hasDirectory(dir: String, name: String, paths: Set<String>): Boolean {
        val prefix = sibling(dir, name) + "/"
        return paths.any { it.startsWith(prefix) }
    }

    /**
     * A Gradle wrapper usable from [dir]: in the package itself or in any ancestor up to the
     * repository root, which is where a monorepo keeps the single wrapper its packages share.
     */
    private fun hasWrapper(dir: String, paths: Set<String>): Boolean {
        var current = if (dir == PackageCommands.ROOT) "" else dir
        while (true) {
            val candidate = if (current.isEmpty()) "gradlew" else "$current/gradlew"
            if (candidate in paths) return true
            if (current.isEmpty()) return false
            current = current.substringBeforeLast('/', "")
        }
    }
}
