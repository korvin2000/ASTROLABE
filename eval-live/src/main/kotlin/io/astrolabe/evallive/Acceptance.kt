package io.astrolabe.evallive

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText

/** What the hidden acceptance of one run said; [outputTail] is the end of its output, the whole output is in `acceptance.log`. */
@Serializable
internal data class AcceptanceResult(
    val passed: Boolean,
    val exitCode: Int?,
    val timedOut: Boolean,
    val durationMillis: Long,
    val outputTail: String,
)

/**
 * The hidden acceptance (plan §9.2): run in a fresh copy of the finished workspace under [temp], with the task's
 * `acceptance/` (as read when the task was loaded) laid in as [HIDDEN] only for that run. The agent's workspace never holds these files, so the agent can
 * neither read nor change them; the copy is removed afterwards.
 */
internal class Acceptance(private val interpreters: Interpreters, private val temp: Path) {
    fun run(task: BenchTask, workspace: Path, log: Path? = null): AcceptanceResult {
        val argv = task.acceptance.argv.map { interpreters.expand(it) }
        val root = Files.createTempDirectory(temp, "accept-")
        try {
            // A `_acceptance` the agent may have created is replaced, never merged.
            Trees.copy(workspace, root, skipTop = setOf(".git", HIDDEN))
            task.hidden.write(root.resolve(HIDDEN))
            val result = Proc.run(argv, root, Duration.ofSeconds(task.acceptance.timeoutSeconds), ENV)
            log?.writeText(result.output)
            return AcceptanceResult(
                passed = result.exitCode == 0 && !result.timedOut,
                exitCode = result.exitCode,
                timedOut = result.timedOut,
                durationMillis = result.durationMillis,
                outputTail = result.output.takeLast(TAIL_CHARS),
            )
        } finally {
            Trees.delete(root)
        }
    }

    /**
     * The task's acceptance is sound when it fails on the unchanged base and passes on the base with `reference/` laid
     * over it (plan §9.2); also returns whether the reference keeps the visible tests green when [visibleTests] is given.
     */
    fun validate(task: BenchTask, visibleTests: List<String>?): TaskValidity {
        val base = Files.createTempDirectory(temp, "base-")
        val reference = Files.createTempDirectory(temp, "reference-")
        try {
            Trees.copy(task.base, base)
            Trees.copy(task.base, reference)
            Trees.copy(task.reference, reference)
            val onBase = run(task, base)
            val onReference = run(task, reference)
            val visible = visibleTests?.let { argv ->
                Proc.run(argv.map { interpreters.expand(it) }, reference, Duration.ofSeconds(task.acceptance.timeoutSeconds), ENV)
            }
            return TaskValidity(task.id, onBase, onReference, visible?.let { it.exitCode == 0 })
        } finally {
            Trees.delete(base)
            Trees.delete(reference)
        }
    }

    companion object {
        const val HIDDEN: String = "_acceptance"
        const val TAIL_CHARS: Int = 4_000

        /** The visible test command the core sniffs for these Python tasks (`Sniff.pyproject`: `tests/` without pytest). */
        val VISIBLE_TESTS: List<String> = listOf("{python}", "-m", "unittest", "discover", "-s", "tests")

        private val ENV = mapOf("PYTHONDONTWRITEBYTECODE" to "1", "PYTHONIOENCODING" to "utf-8")
    }
}

internal data class TaskValidity(val task: String, val onBase: AcceptanceResult, val onReference: AcceptanceResult, val visibleGreenOnReference: Boolean?) {
    val sound: Boolean get() = !onBase.passed && onReference.passed && visibleGreenOnReference != false
}

/** Interpreters a task command names by placeholder; resolved once per process from the PATH unless given. */
internal class Interpreters(private val python: String?) {
    fun expand(arg: String): String = if (arg == PYTHON) python ?: throw IllegalStateException("no Python 3 interpreter found on the PATH; pass --python <program>") else arg

    companion object {
        const val PYTHON: String = "{python}"

        /** The first of the usual names that runs as Python 3 here, or [override] as given. */
        fun detect(override: String? = null): Interpreters {
            if (override != null) return Interpreters(override)
            val windows = System.getProperty("os.name").startsWith("Windows")
            val candidates = if (windows) listOf("python", "python3", "py") else listOf("python3", "python")
            val found = candidates.firstOrNull { name ->
                runCatching {
                    val r = Proc.run(listOf(name, "-c", "import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)"), Path.of("."), Duration.ofSeconds(20))
                    r.exitCode == 0
                }.getOrDefault(false)
            }
            return Interpreters(found)
        }
    }
}
