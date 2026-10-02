package io.astrolabe.evallive

import io.astrolabe.Astrolabe
import io.astrolabe.event.Events
import io.astrolabe.id.IdGen
import io.astrolabe.provider.Effort
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.function.LongSupplier
import kotlin.io.path.writeText
import kotlin.random.Random

/** One run of the bench: [task] on [model], repetition [repeat], at [order] in the seeded sequence. */
internal data class PlannedRun(val order: Int, val task: BenchTask, val model: String, val repeat: Int) {
    fun dir(out: Path): Path = out.resolve("runs").resolve(task.id).resolve(slug(model)).resolve("r$repeat")

    companion object {
        fun slug(model: String): String = model.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}

internal data class BenchPlan(
    val tasks: List<BenchTask>,
    val models: List<String>,
    val provider: String,
    val repeats: Int,
    val seed: Long,
    val out: Path,
    val temp: Path,
    val effort: Effort = Effort.Medium,
    val maxCells: Int = StudioPolicy.MAX_CELLS,
    val deadline: Duration = Duration.ofMinutes(60),
    val keepWorkspaces: Boolean = false,
) {
    init {
        require(tasks.isNotEmpty() && models.isNotEmpty()) { "a bench needs at least one task and one model" }
        require(repeats >= 1 && maxCells >= 1) { "repeats and max cells must be at least 1" }
    }

    /** Every task × model × repetition, shuffled by [seed] so order effects do not line up with tasks (plan §9.3). */
    fun runs(): List<PlannedRun> {
        val all = tasks.flatMap { task -> models.flatMap { model -> (1..repeats).map { PlannedRun(0, task, model, it) } } }
        return all.shuffled(Random(seed)).mapIndexed { i, run -> run.copy(order = i + 1) }
    }
}

/**
 * Runs a [BenchPlan] one run after another. Each run gets a fresh temporary directory: the task's base copied in and
 * committed (the core works on a git repository), the state root beside it, outside the workspace. After the attempt
 * the hidden acceptance runs on a copy of the result. A run whose `result.json` exists is kept, so a stopped bench
 * resumes where it stopped.
 */
internal class Bench(
    private val plan: BenchPlan,
    private val models: ModelSource,
    private val interpreters: Interpreters,
    private val clock: Clock,
    private val idGen: IdGen,
    private val log: (String) -> Unit = {},
    private val nanos: LongSupplier = LongSupplier(System::nanoTime),
    private val osName: String = System.getProperty("os.name"),
) {
    fun run(): List<RunResult> {
        Files.createDirectories(plan.out)
        Files.createDirectories(plan.temp)
        val results = ArrayList<RunResult>()
        val runs = plan.runs()
        for (run in runs) {
            val dir = run.dir(plan.out)
            val existing = dir.resolve("result.json")
            if (Files.isRegularFile(existing)) {
                log("[${run.order}/${runs.size}] ${run.task.id} / ${run.model} / r${run.repeat}: kept the earlier result")
                results += Summary.read(existing)
                continue
            }
            log("[${run.order}/${runs.size}] ${run.task.id} / ${run.model} / r${run.repeat}: started")
            val result = runOne(run, dir)
            results += result
            Summary.write(plan.out, results)
            log("[${run.order}/${runs.size}] ${run.task.id} / ${run.model} / r${run.repeat}: ${result.outcome ?: "no outcome"}, " +
                "acceptance ${if (result.acceptance?.passed == true) "passed" else "failed"}" + (result.failure?.let { " ($it)" } ?: ""))
        }
        Summary.write(plan.out, results)
        return results
    }

    private fun runOne(run: PlannedRun, dir: Path): RunResult {
        Files.createDirectories(dir)
        val started = clock.instant()
        val temp = Files.createTempDirectory(plan.temp, "run-")
        val workspace = temp.resolve("workspace")
        try {
            Trees.copy(run.task.base, workspace)
            val base = GitRepo.initWithBase(workspace)
            var attempt: AttemptOutcome? = null
            var failure: String? = null
            var totals: Totals? = null
            var dropped: Long? = null
            var wall: Long? = null
            var binding: ModelBinding? = null
            try {
                val bound = models.bind(run.model)
                binding = bound
                val events = Events(clock)
                try {
                    Recorder(events, dir.resolve("events.jsonl")).use { recorder ->
                        val start = nanos.asLong
                        try {
                            attempt = runBlocking {
                                StudioAttempt(clock, idGen, osName).run(
                                    workspace, temp.resolve("state"), run.task.prompt, bound, events, plan.effort, plan.maxCells, plan.deadline,
                                )
                            }
                        } finally {
                            wall = (nanos.asLong - start) / 1_000_000
                            recorder.drain()
                            totals = Totals.of(recorder.events(), bound.profile.priceTable)
                            dropped = recorder.dropped
                        }
                    }
                } finally {
                    events.close()
                }
            } catch (e: Exception) {
                failure = describe(e)
            } finally {
                runCatching { binding?.close() }
            }
            val diff = GitRepo.diff(workspace, base)
            dir.resolve("workspace.diff").writeText(diff)
            val acceptance = runCatching { Acceptance(interpreters, plan.temp).run(run.task, workspace, dir.resolve("acceptance.log")) }
                .onFailure { failure = failure ?: "acceptance: ${describe(it)}" }.getOrNull()
            val result = RunResult(
                task = run.task.id,
                taskClass = run.task.kind,
                provider = plan.provider,
                model = run.model,
                repeat = run.repeat,
                order = run.order,
                seed = plan.seed,
                harnessVersion = Astrolabe.VERSION,
                effort = plan.effort.name,
                maxCells = plan.maxCells,
                profileId = binding?.profile?.id,
                contextLimitTokens = binding?.profile?.capabilities?.contextLimitTokens,
                outputHeadroomTokens = binding?.profile?.let(StudioPolicy::outputHeadroom),
                workId = attempt?.workId,
                attemptFingerprint = attempt?.fingerprint,
                shape = attempt?.shape,
                verification = attempt?.verification,
                outcome = attempt?.outcome,
                stopCode = attempt?.stopCode,
                reason = attempt?.reason,
                failure = failure ?: attempt?.failure,
                cells = attempt?.cells,
                policyDecisions = attempt?.decisions ?: emptyList(),
                acceptance = acceptance,
                acceptanceDigest = run.task.hidden.digest,
                startedAt = started.toString(),
                endedAt = clock.instant().toString(),
                attemptWallMillis = wall,
                totals = totals,
                eventsDropped = dropped,
                changedFiles = DIFF_FILE.findAll(diff).count(),
            )
            dir.resolve("result.json").writeText(Summary.encode(result))
            return result
        } finally {
            if (plan.keepWorkspaces) log("kept the run directory $temp") else runCatching { Trees.delete(temp) }.onFailure { log("could not remove $temp: ${it.message}") }
        }
    }

    private fun describe(e: Throwable): String = "${e::class.java.simpleName}: ${e.message}"

    companion object {
        private val DIFF_FILE = Regex("(?m)^diff --git ")
    }
}
