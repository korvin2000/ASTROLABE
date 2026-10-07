package io.astrolabe.evallive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
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

/**
 * A message the user sends while the agent works (WP-B7): after [afterResponses] model responses the runner delivers
 * [text] to the running campaign, as the Studio does for a message sent to a live task (`StudioHost.amend`).
 */
@Serializable
internal data class MessageSpec(val afterResponses: Int, val text: String) {
    init {
        require(afterResponses >= 1) { "a message comes after at least one response" }
        require(text.isNotBlank()) { "a message needs the user's text" }
    }
}

/**
 * A task in two sessions (WP-B7): after [afterResponses] model responses the runner closes the session — the run job
 * is cancelled as when the Studio backend stops, the project and its store are closed — then opens the same work in
 * the same state root again and runs it on, as the Studio's resume does.
 */
@Serializable
internal data class ReopenSpec(val afterResponses: Int) {
    init {
        require(afterResponses >= 1) { "a session is closed after at least one response" }
    }
}

/**
 * A dirty working tree (WP-W0, plan §7.2): after the base is committed the runner adds an untracked [dir] of [files]
 * small files, one of them [bigMegabytes] MB, never committed and never ignored — the `devtools/` of the 5 October runs.
 * The base itself carries no `.gitignore`, so what its checks write stays in the tree.
 *
 * T-14: [dir] is a plain relative name (under the workspace) or an absolute path — `C:\...`, `\\server\share\...` or
 * `/...`. The tree is then written there, outside the repository, where git never sees it: it is not dirt of the tree,
 * [write] says so in its [DirtResult], and the runner removes what it wrote when the run ends. Dirt that cannot be
 * written there (an unreachable share, a path of the other system, a directory that already exists) never stops a run.
 */
@Serializable
internal data class DirtSpec(val dir: String = "devtools", val files: Int = 1500, val bigMegabytes: Int = 20) {
    init {
        require(dir.isNotBlank() && !dir.contains("..")) { "a dirt directory is a relative name or an absolute path, without '..'" }
        if (isAbsoluteName) require(!dir.startsWith("\\\\") || DIRT_UNC.matches(dir)) { "a UNC dirt path names a server and a share" }
        else require(!dir.startsWith(".git")) { "a dirt directory is not the repository's own" }
        require(files >= 1) { "a dirt directory holds at least one file" }
        require(bigMegabytes >= 0) { "the big file's size is not negative" }
    }

    /** Whether [dir] is written as an absolute path (drive, UNC or rooted), whatever system this is. */
    private val isAbsoluteName: Boolean get() = DIRT_ABSOLUTE.containsMatchIn(dir)

    /**
     * Writes the untracked files: `files - 1` small ones in sub-directories of 100, then `bundle.bin` — under [root], or
     * at the absolute [dir]. The placement is closed when the run ends.
     */
    fun write(root: Path): DirtPlacement {
        if (!isAbsoluteName) {
            writeTree(root.resolve(dir))
            return DirtPlacement(DirtResult(dir, inTree = true, written = true), null)
        }
        val target = runCatching { Path.of(dir) }.getOrNull()?.takeIf { it.isAbsolute }?.normalize()
            ?: return DirtPlacement(DirtResult(dir, inTree = false, written = false, reason = "not an absolute path on this system"), null)
        val tree = root.toAbsolutePath().normalize()
        if (target.startsWith(tree) && target != tree) {
            writeTree(target)
            return DirtPlacement(DirtResult(dir, inTree = true, written = true), null)
        }
        if (Files.exists(target)) return DirtPlacement(DirtResult(dir, inTree = false, written = false, reason = "outside the tree and already there; left as it is"), null)
        return try {
            writeTree(target)
            DirtPlacement(DirtResult(dir, inTree = false, written = true, reason = "outside the tree: not untracked files of the repository"), target)
        } catch (e: IOException) {
            runCatching { Trees.delete(target) }
            DirtPlacement(DirtResult(dir, inTree = false, written = false, reason = "outside the tree and not writable: ${e::class.java.simpleName}: ${e.message}"), null)
        }
    }

    private fun writeTree(base: Path) {
        for (n in 0 until files - 1) {
            val file = base.resolve("pkg-${n / 100}").resolve("file-$n.txt")
            Files.createDirectories(file.parent)
            Files.write(file, "tool file $n\n".toByteArray(StandardCharsets.UTF_8))
        }
        Files.createDirectories(base)
        val bytes = bigMegabytes * 1024 * 1024
        Files.write(base.resolve("bundle.bin"), ByteArray(bytes) { (it * 31 + (it ushr 11)).toByte() })
    }
}

private val DIRT_ABSOLUTE = Regex("""^([A-Za-z]:[\\/]|\\\\|/)""")
private val DIRT_UNC = Regex("""^\\\\[^\\/]+[\\/][^\\/]+.*""")

/** What became of a task's dirt in one run (T-14), kept in `result.json`: [inTree] says whether it is untracked files of the repository. */
@Serializable
internal data class DirtResult(val dir: String, val inTree: Boolean, val written: Boolean, val reason: String? = null)

/** The dirt written for a run; closing it removes what lies outside the tree ([outside] is the directory the runner created there). */
internal class DirtPlacement(val result: DirtResult, private val outside: Path?) : AutoCloseable {
    override fun close() {
        outside?.let { Trees.delete(it) }
    }
}

@Serializable
internal data class TaskFile(
    val id: String,
    @SerialName("class") val kind: String,
    val title: String,
    val acceptance: AcceptanceSpec,
    val interrupt: InterruptSpec? = null,
    /** False: the workspace is a git repository without a commit, the base content left untracked (WP-B7). */
    val baseCommit: Boolean = true,
    val message: MessageSpec? = null,
    val reopen: ReopenSpec? = null,
    val dirt: DirtSpec? = null,
    /** The id of the task this one is the second of (plan §9.2, pairs): see [BenchTask.first]. */
    val after: String? = null,
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
    val baseCommit: Boolean = true,
    val message: MessageSpec? = null,
    val reopen: ReopenSpec? = null,
    /** The untracked tool tree laid into the workspace after the base commit (WP-W0); needs a base commit. */
    val dirt: DirtSpec? = null,
    /** The id of the first task of the pair this task is the second of, as `task.json` names it (`after`). */
    val after: String? = null,
    /**
     * Plan §9.2 (pairs, "second task in the same project"): the task [after] names, resolved by [all]. The runner
     * runs it first on its own base, commits what it left and then runs this task in the same working directory and
     * the same state root, measuring this one; this task's own `base/` (the first's base with the first's reference
     * laid over it) only serves the validity check.
     */
    val first: BenchTask? = null,
) {
    init {
        require(interrupt == null || reopen == null) { "task $id: an interruption and a second session do not combine" }
        require(dirt == null || baseCommit) { "task $id: a dirty tree is laid over a committed base" }
        require(first == null || first.id == after) { "task $id: its first task is ${first?.id}, not $after" }
        if (after != null) {
            require(after != id) { "task $id: a task is not the second of itself" }
            require(interrupt == null && reopen == null && message == null && dirt == null) { "task $id: the second task of a pair has no interrupt, reopen, message or dirt" }
        }
        if (first != null) {
            require(first.after == null) { "task $id: its first task $after is itself the second of a pair" }
            require(first.baseCommit && first.interrupt == null && first.reopen == null && first.message == null && first.dirt == null) {
                "task $id: the first task of a pair runs plain on a committed base (no interrupt, reopen, message or dirt)"
            }
        }
    }

    /** This task as the second of a pair whose first task is [first]. */
    fun pairedWith(first: BenchTask): BenchTask = BenchTask(
        id, kind, title, prompt, dir, acceptance, hidden, reference, wrong, interrupt, baseCommit, message, reopen, dirt, after, first,
    )

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
                file.baseCommit, file.message, file.reopen, file.dirt, file.after,
            )
        }

        /** Every task under [root] (one directory with a `task.json` each), by id; the second task of a pair gets its [first]. */
        fun all(root: Path, bundle: HiddenBundle = HiddenBundle.classpath): List<BenchTask> {
            require(root.isDirectory()) { "no tasks directory at $root" }
            val loaded = Files.list(root).use { dirs -> dirs.filter { it.resolve("task.json").let(Files::isRegularFile) }.toList() }
                .map { load(it, bundle) }.associateBy { it.id }
            return loaded.values.map { task ->
                val after = task.after ?: return@map task
                task.pairedWith(loaded[after] ?: throw IllegalArgumentException("task ${task.id} comes after an unknown task '$after'"))
            }.sortedBy { it.id }
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
