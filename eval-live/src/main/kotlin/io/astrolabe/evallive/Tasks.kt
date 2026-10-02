package io.astrolabe.evallive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/** The hidden acceptance of a task: [argv] runs from the root of the acceptance copy; `{python}` names the interpreter. */
@Serializable
internal data class AcceptanceSpec(val argv: List<String>, val timeoutSeconds: Long = 120) {
    init {
        require(argv.isNotEmpty()) { "an acceptance needs a command" }
        require(timeoutSeconds > 0) { "an acceptance needs a positive timeout" }
    }
}

/**
 * A scripted user interruption (WP-B2): after [afterResponses] model responses the runner stops the attempt, and the
 * user's [constraint] reaches the agent the way the Studio delivers a message after a stop.
 */
@Serializable
internal data class InterruptSpec(val afterResponses: Int, val constraint: String) {
    init {
        require(afterResponses >= 1) { "an interruption comes after at least one response" }
        require(constraint.isNotBlank()) { "an interruption needs the user's text" }
    }
}

@Serializable
internal data class TaskFile(
    val id: String,
    @SerialName("class") val kind: String,
    val title: String,
    val acceptance: AcceptanceSpec,
    val interrupt: InterruptSpec? = null,
)

/**
 * A bench task (plan §9.2): `base/` is the repository the agent gets and `prompt.md` its request. The hidden parts never
 * enter the workspace: [hidden] is the acceptance, [reference] the files of a known-good solution and [wrong] those of a
 * plausible wrong one, each laid over the base. They are read once at load — from `acceptance/`, `reference/` and
 * `wrong/` beside the base in the source tree, or from the [HiddenBundle] an installed distribution carries instead —
 * so later changes on disk (by anyone, the agent included) never reach a run.
 */
internal class BenchTask(
    val id: String,
    val kind: String,
    val title: String,
    val prompt: String,
    val dir: Path,
    val acceptance: AcceptanceSpec,
    val hidden: HiddenFiles,
    val reference: HiddenFiles,
    val wrong: HiddenFiles,
    val interrupt: InterruptSpec? = null,
) {
    val base: Path get() = dir.resolve("base")

    companion object {
        private val json = Json { ignoreUnknownKeys = false }
        private val ID = Regex("[a-z0-9][a-z0-9-]{0,63}")

        /** The hidden parts of a task, by directory name. */
        val HIDDEN_PARTS: List<String> = listOf("acceptance", "reference", "wrong")

        fun load(dir: Path, bundle: HiddenBundle = HiddenBundle.classpath): BenchTask {
            val file = json.decodeFromString(TaskFile.serializer(), dir.resolve("task.json").readText())
            require(ID.matches(file.id)) { "task id '${file.id}' must match ${ID.pattern}" }
            require(file.id == dir.name) { "task ${file.id} lives in a directory of another name: $dir" }
            val prompt = dir.resolve("prompt.md").readText().trim()
            require(prompt.isNotEmpty()) { "task ${file.id} has an empty prompt.md" }
            require(dir.resolve("base").isDirectory()) { "task ${file.id} needs a base/ directory" }
            // All parts from one place: a source tree never mixes with a packaged bundle of another version.
            val open = HIDDEN_PARTS.filter { dir.resolve(it).isDirectory() }
            val parts = if (open.isNotEmpty()) {
                HIDDEN_PARTS.associateWith { part ->
                    require(dir.resolve(part).isDirectory()) { "task ${file.id} needs a $part/ directory beside ${open.joinToString(", ") { "$it/" }}" }
                    HiddenFiles.read(dir.resolve(part))
                }
            } else {
                HIDDEN_PARTS.associateWith { part ->
                    bundle.part(file.id, part) ?: throw IllegalArgumentException("task ${file.id} has no $part/: neither a directory nor a packaged resource")
                }
            }
            for ((part, files) in parts) require(files.files.isNotEmpty()) { "task ${file.id} has an empty $part/" }
            return BenchTask(
                file.id, file.kind, file.title, prompt, dir, file.acceptance,
                parts.getValue("acceptance"), parts.getValue("reference"), parts.getValue("wrong"), file.interrupt,
            )
        }

        /** Every task under [root] (one directory with a `task.json` each), by id. */
        fun all(root: Path, bundle: HiddenBundle = HiddenBundle.classpath): List<BenchTask> {
            require(root.isDirectory()) { "no tasks directory at $root" }
            return Files.list(root).use { dirs -> dirs.filter { it.resolve("task.json").let(Files::isRegularFile) }.toList() }
                .map { load(it, bundle) }.sortedBy { it.id }
        }

        /** The tasks named in [ids] (`all` for every one), in the order named. */
        fun select(root: Path, ids: List<String>, bundle: HiddenBundle = HiddenBundle.classpath): List<BenchTask> {
            val known = all(root, bundle).associateBy { it.id }
            if (ids == listOf("all")) return known.values.toList()
            return ids.map { id -> known[id] ?: throw IllegalArgumentException("unknown task '$id'; known: ${known.keys.joinToString(", ")}") }
        }
    }
}

/**
 * The hidden parts of the task set packaged as resources (the build's `hiddenJar`, a jar in the distribution's `lib/`):
 * [INDEX] lists every file as `<task>/<part>/<path>`, stored under [ROOT] by that name. An installed bench holds no
 * open `acceptance/`, `reference/` or `wrong/` an agent could come across.
 */
internal class HiddenBundle(private val loader: ClassLoader) {
    private val index: Map<String, List<String>> by lazy {
        val text = loader.getResourceAsStream(INDEX)?.use { String(it.readAllBytes(), StandardCharsets.UTF_8) } ?: return@lazy emptyMap()
        text.lineSequence().filter { it.isNotBlank() }.groupBy { it.split('/', limit = 3).take(2).joinToString("/") }
    }

    /** The files of [part] of task [id], or null when the bundle has none. */
    fun part(id: String, part: String): HiddenFiles? {
        val names = index["$id/$part"] ?: return null
        val prefix = "$id/$part/"
        return HiddenFiles.of(names.associate { name ->
            val bytes = loader.getResourceAsStream("$ROOT/$name")?.use { it.readAllBytes() }
                ?: throw IllegalStateException("the hidden bundle lists $name but has no such resource")
            name.removePrefix(prefix) to bytes
        })
    }

    companion object {
        const val ROOT: String = "evallive/hidden"
        const val INDEX: String = "$ROOT/index"

        val classpath: HiddenBundle by lazy { HiddenBundle(HiddenBundle::class.java.classLoader) }
    }
}

/** A file tree held in memory by relative path ('/'-separated), with the SHA-256 of its paths and bytes. */
internal class HiddenFiles private constructor(val files: Map<String, ByteArray>) {
    val digest: String = MessageDigest.getInstance("SHA-256").run {
        for ((path, bytes) in files) {
            update(path.toByteArray(StandardCharsets.UTF_8))
            update(0.toByte())
            update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            update(0.toByte())
            update(bytes)
        }
        HexFormat.of().formatHex(digest())
    }

    /** Lays the files into [to], replacing files of the same path. */
    fun write(to: Path) {
        for ((path, bytes) in files) {
            val target = to.resolve(path)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
    }

    companion object {
        fun of(files: Map<String, ByteArray>): HiddenFiles = HiddenFiles(files.toSortedMap())

        fun read(root: Path): HiddenFiles {
            require(root.isDirectory()) { "no directory $root" }
            val files = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }
                .associate { root.relativize(it).joinToString("/") to Files.readAllBytes(it) }
            return HiddenFiles(files.toSortedMap())
        }
    }
}
