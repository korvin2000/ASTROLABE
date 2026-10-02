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

@Serializable
internal data class TaskFile(
    val id: String,
    @SerialName("class") val kind: String,
    val title: String,
    val acceptance: AcceptanceSpec,
)

/**
 * A bench task (plan §9.2): `base/` is the repository the agent gets, `prompt.md` its request, `acceptance/` the hidden
 * check that never enters the workspace, `reference/` the files of a known-good solution laid over the base.
 */
internal class BenchTask(val id: String, val kind: String, val title: String, val prompt: String, val dir: Path, val acceptance: AcceptanceSpec) {
    val base: Path get() = dir.resolve("base")
    val reference: Path get() = dir.resolve("reference")

    /** The hidden acceptance as read at load: later changes on disk (by anyone, the agent included) never reach a run. */
    val hidden: HiddenFiles = HiddenFiles.read(dir.resolve("acceptance"))

    companion object {
        private val json = Json { ignoreUnknownKeys = false }
        private val ID = Regex("[a-z0-9][a-z0-9-]{0,63}")

        fun load(dir: Path): BenchTask {
            val file = json.decodeFromString(TaskFile.serializer(), dir.resolve("task.json").readText())
            require(ID.matches(file.id)) { "task id '${file.id}' must match ${ID.pattern}" }
            require(file.id == dir.name) { "task ${file.id} lives in a directory of another name: $dir" }
            val prompt = dir.resolve("prompt.md").readText().trim()
            require(prompt.isNotEmpty()) { "task ${file.id} has an empty prompt.md" }
            require(dir.resolve("base").isDirectory() && dir.resolve("acceptance").isDirectory()) { "task ${file.id} needs base/ and acceptance/ directories" }
            return BenchTask(file.id, file.kind, file.title, prompt, dir, file.acceptance)
        }

        /** Every task under [root] (one directory with a `task.json` each), by id. */
        fun all(root: Path): List<BenchTask> {
            require(root.isDirectory()) { "no tasks directory at $root" }
            return Files.list(root).use { dirs -> dirs.filter { it.resolve("task.json").let(Files::isRegularFile) }.toList() }
                .map(::load).sortedBy { it.id }
        }

        /** The tasks named in [ids] (`all` for every one), in the order named. */
        fun select(root: Path, ids: List<String>): List<BenchTask> {
            val known = all(root).associateBy { it.id }
            if (ids == listOf("all")) return known.values.toList()
            return ids.map { id -> known[id] ?: throw IllegalArgumentException("unknown task '$id'; known: ${known.keys.joinToString(", ")}") }
        }
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

    fun write(to: Path) {
        for ((path, bytes) in files) {
            val target = to.resolve(path)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
    }

    companion object {
        fun read(root: Path): HiddenFiles {
            require(root.isDirectory()) { "no directory $root" }
            val files = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }
                .associate { root.relativize(it).joinToString("/") to Files.readAllBytes(it) }
            return HiddenFiles(files.toSortedMap())
        }
    }
}
