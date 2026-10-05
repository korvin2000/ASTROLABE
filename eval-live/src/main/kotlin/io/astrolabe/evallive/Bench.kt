package io.astrolabe.evallive

import io.astrolabe.Astrolabe
import io.astrolabe.RunSpec
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.event.Events
import io.astrolabe.id.IdGen
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Profile
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.function.LongSupplier
import kotlin.io.path.writeText
import kotlin.random.Random

/** One run of the bench: [task] on [model] by [arm], repetition [repeat], at [order] in the seeded sequence. */
internal data class PlannedRun(val order: Int, val task: BenchTask, val model: String, val repeat: Int, val arm: String = Arms.DEFAULT.name) {
    fun dir(out: Path): Path = out.resolve("runs").resolve(arm).resolve(task.id).resolve(slug(model)).resolve("r$repeat")

    companion object {
        fun slug(model: String): String = model.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}

/**
 * The Studio task mode the host plays (WP-WG): `auto` accepts unverified work on the policy's word; `ask` leaves every
 * acceptance request to the [ScriptedUser], whose answer reaches the campaign when the run reopens the same work.
 */
internal enum class HostMode(val wire: String) {
    Auto("auto"),
    Ask("ask"),
    ;

    companion object {
        fun named(name: String): HostMode = entries.firstOrNull { it.wire == name } ?: throw UsageError("unknown mode '$name' (known: auto, ask)")
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
    val effort: Effort = RunSpec.EFFORT,
    val maxCells: Int = RunSpec.MAX_CELLS,
    val deadline: Duration = Duration.ofMinutes(60),
    val keepWorkspaces: Boolean = false,
    /** The bench named [effort] itself, as a user who picks one in the Studio's composer: the approach never steps it. */
    val effortExplicit: Boolean = false,
    /** The arm every run of this bench is made by (B5). */
    val arm: Arm = Arms.DEFAULT,
    /** The Studio task mode the host plays (WP-WG). */
    val mode: HostMode = HostMode.Auto,
) {
    init {
        require(tasks.isNotEmpty() && models.isNotEmpty()) { "a bench needs at least one task and one model" }
        require(repeats >= 1 && maxCells >= 1) { "repeats and max cells must be at least 1" }
        require(arm.unsupported().isEmpty()) { "arm ${arm.name} cannot run: ${arm.unsupported().joinToString("; ")}" }
        require(mode == HostMode.Auto || arm.runner == ArmRunner.Core) { "mode ${mode.wire} needs an arm of the core runner, not ${arm.name}" }
    }

    /** The default arm: the core's default launch — the Studio's — on [profile], with this bench's cell cap and effort. */
    fun spec(profile: Profile, stateRoot: Path): RunSpec =
        RunSpec.defaults(profile, stateRoot.toString()).copy(maxCells = maxCells, effort = effort, effortExplicit = effortExplicit)

    /** Every task × model × repetition, shuffled by [seed] so order effects do not line up with tasks (plan §9.3). */
    fun runs(): List<PlannedRun> {
        val all = tasks.flatMap { task -> models.flatMap { model -> (1..repeats).map { PlannedRun(0, task, model, it, arm.name) } } }
        return all.shuffled(Random(seed)).mapIndexed { i, run -> run.copy(order = i + 1) }
    }
}

/**
 * Runs a [BenchPlan] one run after another. Each run gets a fresh temporary directory: the task's base copied in and
 * committed (the core works on a git repository; a task with `baseCommit: false` gets a repository without a commit),
 * the state root beside it, outside the workspace. After the attempt
 * the hidden acceptance runs on a copy of the result. A run whose `result.json` exists with the same [ResultKey] is
 * kept, so a stopped bench resumes where it stopped; one of another key — arm, configuration, [code] or task — is set
 * aside beside it (`r<n>.stale-<k>`) and the run made again.
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
    /** The fingerprint of the code that makes the runs ([Fingerprints.code]). */
    private val code: String = Fingerprints.code,
) {
    fun run(): List<RunResult> {
        Files.createDirectories(plan.out)
        Files.createDirectories(plan.temp)
        val results = ArrayList<RunResult>()
        val runs = plan.runs()
        val taskKeys = HashMap<String, String>()
        for (run in runs) {
            val dir = run.dir(plan.out)
            val existing = dir.resolve("result.json")
            val key = ResultKey(plan.arm.name, Fingerprints.config(plan, run.model), code, taskKeys.getOrPut(run.task.id) { Fingerprints.task(run.task) })
            val label = "[${run.order}/${runs.size}] ${run.task.id} / ${run.model} / ${plan.arm.name} / r${run.repeat}"
            if (Files.isRegularFile(existing)) {
                val earlier = runCatching { Summary.read(existing) }.getOrNull()
                val differences = key.differences(earlier?.key).ifEmpty { if (earlier?.arm == plan.arm.name) emptyList() else listOf("arm") }
                if (earlier != null && differences.isEmpty()) {
                    log("$label: kept the earlier result")
                    results += earlier
                    continue
                }
                val aside = setAside(dir)
                log("$label: the earlier result does not match (${if (earlier == null) "unreadable" else differences.joinToString(", ")}); moved to ${aside.fileName}")
            }
            log("$label: started")
            val result = runOne(run, dir, key)
            results += result
            Summary.write(plan.out, results)
            log("$label: ${result.outcome ?: "no outcome"}, " +
                "acceptance ${if (result.acceptance?.passed == true) "passed" else "failed"}" +
                    (result.interrupt?.let { ", interrupt ${it.mode?.let(Summary::wire) ?: "not continued"}" } ?: "") +
                    (result.ask?.let { ", ask ${it.outcome ?: "no outcome"} after ${it.answers} answer(s)" } ?: "") + (result.failure?.let { " ($it)" } ?: ""))
        }
        Summary.write(plan.out, results)
        return results
    }

    /** Moves a run directory whose result does not stand to the first free `<name>.stale-<k>` beside it. */
    private fun setAside(dir: Path): Path {
        var k = 1
        while (Files.exists(dir.resolveSibling("${dir.fileName}.stale-$k"))) k++
        return Files.move(dir, dir.resolveSibling("${dir.fileName}.stale-$k"))
    }

    private fun runOne(run: PlannedRun, dir: Path, key: ResultKey): RunResult {
        Files.createDirectories(dir)
        val started = clock.instant()
        val temp = Files.createTempDirectory(plan.temp, "run-")
        val workspace = temp.resolve("workspace")
        try {
            Trees.copy(run.task.base, workspace)
            val base = if (run.task.baseCommit) GitRepo.initWithBase(workspace) else GitRepo.initWithoutCommit(workspace, temp.resolve("base.index"))
            run.task.dirt?.write(workspace)
            val interrupt = run.task.interrupt
            val reopen = run.task.reopen
            val segments = ArrayList<Segment>()
            var failure: String? = null
            var totals: Totals? = null
            var dropped: Long? = null
            var binding: ModelBinding? = null
            var phases: PhaseSummary? = null
            val user = if (plan.mode == HostMode.Ask) ScriptedUser() else null
            var askFrom = 0
            var exhausted = false
            try {
                val bound = models.bind(run.model)
                binding = bound
                val spec = plan.arm.spec(plan.spec(bound.profile, temp.resolve("state")))
                // B5: each call is priced by the profile it named; the run's currency is its main profile's.
                val tables = (spec.config.profiles.values + bound.profile).associate { it.id to it.priceTable }
                fun totalsOf(events: List<io.astrolabe.event.AgentEvent>) = Totals.of(events, tables, bound.profile.priceTable.currency)
                val events = Events(clock)
                try {
                    Recorder(events, dir.resolve("events.jsonl")).use { recorder ->
                        try {
                            val script = SessionScript(interrupt?.afterResponses, reopen?.afterResponses, run.task.message)
                            // The loop's limits are per work: a second session of the same work continues its spend; a follow-up is a new work.
                            val budget = LoopBudget()
                            val first = segment(run.task.prompt, bound, events, temp, spec, script, null, budget, user)
                            segments += first
                            // WP-B2: the user's constraint arrives as the Studio delivers a message after the run stopped or ended.
                            if (interrupt != null && first.attempt != null && first.attempt.failure == null) {
                                val request = StudioPolicy.recap(run.task.prompt, first.attempt, GitRepo.changedFiles(workspace, base)) + interrupt.constraint
                                segments += segment(request, bound, events, temp, spec, SessionScript(), null, LoopBudget())
                            }
                            // WP-B7: the second session opens the same work in the same state root, as the Studio's resume does.
                            if (reopen != null && first.attempt?.closedAt != null) {
                                segments += segment(run.task.prompt, bound, events, temp, spec, SessionScript(), WorkId(first.attempt.workId), budget, user)
                            }
                            // WP-WG: the user answers the open request and the run resumes the same work, as the Studio's card does.
                            if (user != null) {
                                askFrom = segments.size - 1
                                while (true) {
                                    val last = segments.last().attempt
                                    if (last == null || last.failure != null || last.outcome != WAITING || user.open == null) break
                                    if (user.answers >= ScriptedUser.MAX_ANSWERS) {
                                        exhausted = true
                                        break
                                    }
                                    user.accept()
                                    segments += segment(run.task.prompt, bound, events, temp, spec, SessionScript(), WorkId(last.workId), budget, user)
                                }
                            }
                        } finally {
                            recorder.drain()
                            totals = totalsOf(recorder.events())
                            phases = PhaseSummary.of(recorder.events())
                            dropped = recorder.dropped
                            segments.replaceAll { it.copy(totals = totalsOf(recorder.events(it.fromSeq, it.toSeq))) }
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
            failure = failure ?: segments.firstNotNullOfOrNull { it.failure }
            val attempt = segments.lastOrNull()?.attempt
            val attempts = segments.map { it.attempt }
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
                outputHeadroomTokens = binding?.profile?.let { plan.spec(it, temp.resolve("state")).outputHeadroom(it) },
                workId = attempt?.workId,
                attemptFingerprint = attempt?.fingerprint,
                shape = attempt?.shape,
                verification = attempt?.verification,
                outcome = attempt?.outcome,
                stopCode = attempt?.stopCode,
                reason = attempt?.reason,
                failure = failure ?: attempts.firstNotNullOfOrNull { it?.failure },
                cells = if (attempts.isEmpty() || attempts.any { it?.cells == null }) null else attempts.sumOf { it!!.cells!! },
                policyDecisions = attempts.flatMap { it?.decisions ?: emptyList() },
                acceptance = acceptance,
                acceptanceDigest = run.task.hidden.digest,
                startedAt = started.toString(),
                endedAt = clock.instant().toString(),
                attemptWallMillis = if (segments.isEmpty()) null else segments.sumOf { it.wallMillis },
                totals = totals,
                eventsDropped = dropped,
                changedFiles = DIFF_FILE.findAll(diff).count(),
                interrupt = interrupt?.let { spec ->
                    val first = segments.firstOrNull()?.attempt
                    InterruptResult(
                        afterResponses = spec.afterResponses,
                        constraint = spec.constraint,
                        mode = if (segments.size < 2) null else if (first?.interruptedAt != null) InterruptMode.CancelResume else InterruptMode.FollowUp,
                        atResponse = first?.interruptedAt,
                        segments = segments.map(::segmentResult),
                    )
                },
                baseCommit = run.task.baseCommit,
                message = run.task.message?.let { m ->
                    val first = segments.firstOrNull()?.attempt
                    MessageResult(m.afterResponses, m.text, first?.messageAt, first?.messageContractVersion)
                },
                reopen = reopen?.let { spec -> ReopenResult(spec.afterResponses, segments.firstOrNull()?.attempt?.closedAt, segments.map(::segmentResult)) },
                arm = plan.arm.name,
                key = key,
                mode = plan.mode.wire,
                ask = user?.let { u ->
                    val asked = segments.drop(askFrom)
                    AskResult(u.answers, if (exhausted) ScriptedUser.EXHAUSTED else asked.lastOrNull()?.attempt?.outcome, asked.map(::segmentResult))
                },
                phases = phases,
            )
            dir.resolve("result.json").writeText(Summary.encode(result))
            return result
        } finally {
            if (plan.keepWorkspaces) log("kept the run directory $temp") else runCatching { Trees.delete(temp) }.onFailure { log("could not remove $temp: ${it.message}") }
        }
    }

    /** One attempt of a run: events with a sequence number in ([fromSeq], [toSeq]] are its own. */
    private data class Segment(val attempt: AttemptOutcome?, val failure: String?, val wallMillis: Long, val fromSeq: Long, val toSeq: Long, val totals: Totals? = null)

    private fun segmentResult(s: Segment): SegmentResult = SegmentResult(
        s.attempt?.workId, s.attempt?.outcome, s.attempt?.stopCode, s.attempt?.reason, s.failure ?: s.attempt?.failure,
        s.attempt?.cells, s.wallMillis, s.totals, s.attempt?.openedContractVersion,
    )

    private fun segment(prompt: String, bound: ModelBinding, events: Events, temp: Path, spec: RunSpec, script: SessionScript, work: WorkId?, budget: LoopBudget, user: ScriptedUser? = null): Segment {
        val from = events.lastSeq
        val start = nanos.asLong
        var attempt: AttemptOutcome? = null
        var failure: String? = null
        try {
            attempt = runBlocking {
                val workspace = temp.resolve("workspace")
                when (plan.arm.runner) {
                    ArmRunner.Core -> {
                        val attempts = StudioAttempt(clock, idGen, osName)
                        if (work == null) {
                            attempts.run(workspace, prompt, bound, events, spec, plan.deadline, script, user = user)
                        } else {
                            attempts.run(workspace, prompt, bound, events, spec, plan.deadline, script, work, user)
                        }
                    }
                    ArmRunner.Loop -> LoopAttempt(clock, idGen, osName).run(workspace, prompt, bound, events, spec, plan.deadline, script, work ?: WorkId(idGen.next("W")), budget)
                }
            }
        } catch (e: Exception) {
            failure = describe(e)
        }
        return Segment(attempt, failure, (nanos.asLong - start) / 1_000_000, from, events.lastSeq)
    }

    private fun describe(e: Throwable): String = "${e::class.java.simpleName}: ${e.message}"

    companion object {
        private val DIFF_FILE = Regex("(?m)^diff --git ")
        private val WAITING = CampaignOutcome.WaitingForInput.wire
    }
}
