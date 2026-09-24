package io.astrolabe.tool.run

import io.astrolabe.Config
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.ContentClass
import io.astrolabe.auth.EffectPolicy
import io.astrolabe.auth.ExecutionDecision
import io.astrolabe.auth.Executors
import io.astrolabe.auth.InstructionShape
import io.astrolabe.auth.Redaction
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.event.Authority
import io.astrolabe.event.DClassRequest
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.Consequential
import io.astrolabe.evidence.ActionOutcome
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.Intent
import io.astrolabe.evidence.IntentJournal
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.evidence.Observation
import io.astrolabe.evidence.Observations
import io.astrolabe.evidence.Outcome
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.os.Command
import io.astrolabe.os.EnvPolicy
import io.astrolabe.os.Os
import io.astrolabe.os.Proc
import io.astrolabe.os.ProcStatus
import io.astrolabe.os.SpawnSpec
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.ToolMask
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import io.astrolabe.tool.Args
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.Effects
import io.astrolabe.tool.EnvelopeHeader
import io.astrolabe.tool.RunArgs
import io.astrolabe.tool.RuntimeFields
import io.astrolabe.tool.ToolCall
import io.astrolabe.tool.ToolExecutor
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOps
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.workspace.Intent as PathIntent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.StampReport
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/** The typed result of one `run` call (§5.4). Status is runner-assigned from exit code **and** parser, never model-authored. */
public data class RunResult(
    val alias: String,
    val actionId: String,
    val exit: Int?,
    val status: Outcome,
    val view: String,
    val truncated: Boolean,
    val log: Digest?,
    val effectClass: EffectClass,
    val stampBefore: CandidateId,
    val stampAfter: CandidateId?,
    /** `stamp_after == stamp_now` at the time of the result: necessary for currency, never sufficient (§8.4, D-45). */
    val current: Boolean,
    val changedPaths: List<String>,
    val handle: String?,
    val parsed: Counts?,
    val shaped: Shaped?,
    val limits: List<String> = emptyList(),
    val intentId: String? = null,
)

/**
 * The `run` family (§5.4, §4.6, §9.4, §13.1, TODO P1.6.5): `run(argv|cmd)`, `run(op=poll)`, `run(op=cancel)`.
 *
 * Before dispatch: the effect policy classifies the command, the contract's capability ceiling and the
 * configured execution mode decide (a host that requires confinement is refused, D-11), and a D-class command
 * needs a stated intent and the authority's approval unless the contract allowlists it. Dispatch follows the
 * consequential-action order — reserve → intent → dispatch → observe → persist → commit — so a crash or a lost
 * observation leaves an open intent and the result `unknown_outcome`, never a duplicate launch (FX-24).
 * After the process ends the stamp is diffed: every moved path is announced to the version registry
 * (coherence drops reads, facts and receipts, FX-07), an `R` label becomes `W` only for observed in-scope
 * writes, and the `touched (by run …)` line names them. Output is captured to a store-owned log, redacted,
 * shaped by the parsers of P1.6.6, and kept as a blob the model can recall. `bg=true` returns a persisted
 * [Handle]; `poll` reattaches to the same process after a restart and an observation timeout leaves it
 * running (FX-22); `cancel` is a request and a status, never proof that every effect stopped.
 */
public class Run(
    private val workspace: Workspace,
    private val registry: VersionRegistry,
    private val stamper: Stamper,
    private val runner: Runner,
    private val os: Os,
    private val intents: IntentJournal,
    private val handles: Handles,
    private val observations: Observations,
    private val aliases: Aliases,
    private val blobs: BlobStore,
    private val redaction: Redaction,
    private val estimator: TokenEstimator,
    private val idGen: IdGen,
    private val ids: Identities,
    private val contracts: Contracts,
    private val authority: Authority,
    private val config: Config,
    private val clock: Clock,
    private val logsDir: Path,
    private val mask: ToolMask = ToolOps.implementingS0,
    private val hostSets: Map<String, CapabilitySet> = emptyMap(),
    private val pollSliceSeconds: Long = 30,
) : ToolExecutor {
    init {
        require(ids.context != null) { "run runs inside a cell: ids.context is its lineage" }
        require(pollSliceSeconds > 0) { "pollSliceSeconds must be positive" }
    }

    override suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome {
        require(call.family == ToolFamily.Run) { "not a run call: ${call.name}" }
        val args = (call.args as Args.Run).args
        if (!mask.allows(call.name)) return refused(args, Outcome.Denied, "${call.name} is masked in this role")
        return when (args.op) {
            "run" -> run(args, context)
            "poll" -> poll(args, context)
            "cancel" -> cancel(args)
            else -> refused(args, Outcome.Denied, "unknown run op '${args.op}'")
        }
    }

    // ------------------------------------------------------------------- run

    private sealed interface Launch {
        data class Unavailable(val reason: String) : Launch
        data class Background(val proc: Proc, val firstOutput: ByteArray, val cursor: Long) : Launch
        data class Finished(val proc: Proc, val output: ByteArray) : Launch
    }

    private suspend fun run(args: RunArgs, context: TurnContext): ToolOutcome {
        val contract = contracts.current(ids.work) ?: return refused(args, Outcome.Denied, "no committed contract for ${ids.work}")
        val argv = args.argv ?: listOf(args.cmd!!)
        val shell = args.argv == null
        val program = argv.first().trim()
        if (program.startsWith("mcp:")) return refused(args, Outcome.Denied, "MCP invocations ('$program') arrive in P4.7; nothing was dispatched")
        val cwd = args.cwd?.let { dir ->
            when (val resolved = workspace.resolve(dir, PathIntent.Read)) {
                is PathResolution.Resolved -> resolved.real
                is PathResolution.Rejected -> return refused(args, Outcome.Denied, "cwd '$dir' refused: ${resolved.detail}")
            }
        } ?: workspace.root

        // Policy label (§4.6), capability ceiling (§14.2) and execution mode (D-11) — all before any effect.
        val classification = EffectPolicy.classify(args, workspace.root.toString(), contract.scope.protectedPaths)
        val ceiling = try {
            Ceiling.of(contract.authorization, config.executionMode, hostSets)
        } catch (misconfigured: IllegalArgumentException) {
            return refused(args, Outcome.Denied, "denied: ${misconfigured.message}")
        }
        ceiling.allows(classification)?.let { return refused(args, Outcome.Denied, "denied by the capability ceiling: ${it.detail}") }
        val decision = Executors.require(config.executionMode)
        if (decision is ExecutionDecision.Refused) return refused(args, Outcome.Denied, "denied: ${decision.refusal.detail} (D-11)")
        if (classification.effectClass == EffectClass.D) {
            val reason = args.intent ?: return refused(args, Outcome.Denied, "D-class effect (${classification.reasons.joinToString("; ")}) needs an explicit intent; nothing was dispatched")
            val allowlisted = contract.authorization.dClassAllowlist.any { classification.command == it || classification.command.startsWith("$it ") }
            val request = DClassRequest(idGen.next("dreq"), contract.version, ids, classification.command, argv, args.cwd, classification.reasons.joinToString("; "), reason, allowlisted)
            val approval = authority.approve(request)
            if (!approval.approved) return refused(args, Outcome.Denied, "D-class effect denied: ${approval.reason ?: "no approval"} (${classification.reasons.joinToString("; ")})")
        }

        val replaySafe = classification.effectClass == EffectClass.R && !classification.effectsUnknown
        if (!replaySafe) intents.open().firstOrNull { it.ids.work == ids.work && it.status == IntentStatus.Unknown && !it.replaySafe && it.argv == argv && it.cwd == args.cwd }?.let {
            return refused(args, Outcome.UnknownOutcome, "unknown_outcome: intent ${it.intentId} ran this command and its effect is unreconciled; reconcile before any retry (§13.1), never relaunch")
        }

        val actionId = idGen.next("act")
        val alias = aliases.allocate(ids.work, actionId, "result", ids.context, workspace.id)
        val before = stamper.report()
        val intent = Intent(idGen.next("intent"), ids, actionId, argv, args.cwd, classification.toString(), at = clock.instant(), replaySafe = replaySafe)
        val spec = SpawnSpec(
            command = if (shell) Command.Shell(args.cmd!!) else Command.Argv(argv),
            workingDirectory = cwd,
            logPath = logPath(actionId),
            environment = EnvPolicy(inheritedNames = config.redaction.envAllowlist, extra = mapOf("CI" to "1", "NO_COLOR" to "1")),
            deadlineSeconds = args.timeout.toLong(),
        )
        var logBlob: Digest? = null
        val outcome = Consequential.run(
            journal = intents,
            intent = intent,
            reserve = { true }, // §8.1 reserve enforcement arrives in P1.7.6; the turn's budgets are the dispatcher's.
            dispatch = { launch(spec, args) },
            persist = { launch ->
                val bytes = when (launch) {
                    is Launch.Finished -> launch.output
                    is Launch.Background -> launch.firstOutput
                    is Launch.Unavailable -> ByteArray(0)
                }
                logBlob = blobs.put(redaction.applyBytes(bytes, ContentClass.ReusableEvidence).text.toByteArray(Charsets.UTF_8), BlobKind.LOG, ids)
            },
        )
        val launch = when (outcome) {
            is ActionOutcome.Completed -> outcome.value
            is ActionOutcome.Unknown -> return unknown(args, alias.text, actionId, before, intent.intentId, outcome.cause)
            is ActionOutcome.NotDispatched -> return refused(args, Outcome.Denied, outcome.reason)
        }
        return when (launch) {
            is Launch.Unavailable -> {
                val result = RunResult(alias.text, actionId, null, Outcome.Unavailable, "cannot start: ${launch.reason}", false, logBlob, classification.effectClass, before.candidateId, before.candidateId, true, emptyList(), null, null, null, listOf(launch.reason), intent.intentId)
                render(args, result, argv, shell, before, before, classification.effectsUnknown)
            }
            is Launch.Background -> {
                val handle = Handle(idGen.next("handle"), ids, actionId, alias.text, argv, shell, args.cwd, launch.proc, wire(launch.proc.status), launch.cursor, before.candidateId.digest.hex)
                handles.save(handle)
                val slice = redaction.applyBytes(launch.firstOutput, ContentClass.ModelFacing).text
                val view = "background run ${alias.text} handle ${handle.handleId} · ${wire(launch.proc.status)} · poll with run(op=poll, handle=\"${handle.handleId}\")" + (if (slice.isBlank()) "" else "\n$slice")
                val result = RunResult(alias.text, actionId, null, Outcome.NotRun, view, false, logBlob, classification.effectClass, before.candidateId, null, false, emptyList(), handle.handleId, null, null, emptyList(), intent.intentId)
                render(args, result, argv, shell, before, null, classification.effectsUnknown, statusWire = wire(launch.proc.status))
            }
            is Launch.Finished -> finish(args, alias.text, actionId, argv, shell, before, launch.proc, launch.output, classification.effectClass, classification.effectsUnknown, logBlob, intent.intentId)
        }
    }

    /** Spawns and, unless backgrounded, observes to the terminal state; the deadline kills the tree, nothing replays. */
    private fun launch(spec: SpawnSpec, args: RunArgs): Launch {
        val proc = try {
            runner.start(spec)
        } catch (failure: IOException) {
            return Launch.Unavailable(failure.message ?: failure::class.simpleName.orEmpty())
        }
        if (args.bg) {
            val first = os.poll(proc, 0, 0)
            return Launch.Background(proc.copy(status = first.status), first.newBytes, first.nextCursorBytes)
        }
        var current = proc
        var cursor = 0L
        val output = java.io.ByteArrayOutputStream()
        while (!current.status.isTerminal) {
            val poll = os.poll(current, cursor, minOf(pollSliceSeconds, args.timeout.toLong() + 5))
            output.write(poll.newBytes)
            cursor = poll.nextCursorBytes
            current = current.copy(status = poll.status)
        }
        val tail = os.poll(current, cursor, 0)
        output.write(tail.newBytes)
        return Launch.Finished(current, output.toByteArray())
    }

    /** Stamp diff, coherence announcements, reclassification and shaping once a process is terminal (§9.4). */
    private fun finish(
        args: RunArgs,
        alias: String,
        actionId: String,
        argv: List<String>,
        shell: Boolean,
        before: StampReport,
        proc: Proc,
        output: ByteArray,
        label: EffectClass,
        effectsUnknown: Boolean,
        logBlob: Digest?,
        intentId: String?,
    ): ToolOutcome {
        val after = stamper.report()
        val changed = announce(before, after, "run $alias")
        val effectClass = when {
            label == EffectClass.R && changed.isNotEmpty() -> if (changed.any { workspace.paths.isProtected(it, PathIntent.Mutate) }) EffectClass.D else EffectClass.W
            else -> label
        }
        val status = proc.status
        val capture = RunCapture(
            actionId = actionId, argv = argv, shell = shell, cwd = args.cwd,
            exitCode = (status as? ProcStatus.Exited)?.exitCode, timedOut = status == ProcStatus.DeadlineExceeded,
            output = output, captureComplete = status !is ProcStatus.Lost,
        )
        val shaped = Shapers.shape(capture, ShapeBudget(args.budget, estimator, alias))
        val outcome = when (status) {
            is ProcStatus.Lost -> Outcome.UnknownOutcome
            is ProcStatus.Cancelled -> Outcome.UnknownOutcome
            else -> shaped.status
        }
        val touched = if (changed.isEmpty()) null else "touched (by run $alias ${argv.joinToString(" ").take(60)}: ${changed.size} path${if (changed.size == 1) "" else "s"}) " + changed.take(10).joinToString(", ") + (if (changed.size > 10) " …" else "")
        val view = shaped.view + (touched?.let { "\n$it" } ?: "")
        val result = RunResult(
            alias, actionId, capture.exitCode, outcome, view, shaped.viewTruncated, logBlob, effectClass, before.candidateId, after.candidateId,
            current = true, changedPaths = changed, handle = null, parsed = shaped.counts, shaped = shaped, limits = shaped.limitations, intentId = intentId,
        )
        return render(args, result, argv, shell, before, after, effectsUnknown || status is ProcStatus.Lost)
    }

    /** Announces every stamped member that moved; a path nobody read before has `from = null` (conservative marking). */
    private fun announce(before: StampReport, after: StampReport, cause: String): List<String> = announceMoved(registry, before, after, cause)

    private fun unknown(args: RunArgs, alias: String, actionId: String, before: StampReport, intentId: String, cause: Throwable?): ToolOutcome {
        val after = runCatching { stamper.report() }.getOrNull()
        after?.let { announce(before, it, "run $alias") }
        val view = "unknown_outcome: the observation was lost (${cause?.message ?: "no cause"}); intent $intentId stays open — reconcile before any retry (§13.1), never relaunch"
        val result = RunResult(alias, actionId, null, Outcome.UnknownOutcome, view, false, null, EffectClass.D, before.candidateId, after?.candidateId, false, emptyList(), null, null, null, emptyList(), intentId)
        return render(args, result, args.argv ?: listOf(args.cmd!!), args.argv == null, before, after, effectsUnknown = true)
    }

    // ------------------------------------------------------------ poll · cancel

    private fun poll(args: RunArgs, context: TurnContext): ToolOutcome {
        val handle = handles.get(args.handle!!) ?: return refused(args, Outcome.Denied, "no handle '${args.handle}' in this campaign")
        val proc = os.reattach(handle.proc)
        val since = args.since ?: handle.cursor
        val poll = try {
            os.poll(proc, since, args.timeout.toLong())
        } catch (failure: IOException) {
            handles.save(handle.copy(status = wire(ProcStatus.Lost)))
            return refused(args, Outcome.UnknownOutcome, "handle ${handle.handleId}: the log cannot be read (${failure.message}); the process state is unknown — reconcile, never relaunch")
        }
        val updated = handle.copy(proc = proc.copy(status = poll.status), status = wire(poll.status), cursor = poll.nextCursorBytes)
        handles.save(updated)
        val slice = redaction.applyBytes(poll.newBytes, ContentClass.ModelFacing).text
        return when (val status = poll.status) {
            ProcStatus.Running -> {
                val view = "handle ${handle.handleId} running · cursor ${poll.nextCursorBytes}" + (if (poll.timedOut) " · observation timed out after ${args.timeout}s, the process keeps running (no relaunch)" else "") + (if (slice.isBlank()) "" else "\n$slice")
                val result = RunResult(handle.alias, handle.actionId, null, Outcome.NotRun, view, false, null, EffectClass.R, CandidateId(Digest(handle.stampBefore)), null, false, emptyList(), handle.handleId, null, null, emptyList())
                render(args, result, handle.argv, handle.shell, null, null, effectsUnknown = false, statusWire = "running")
            }
            else -> {
                val before = stamper.report().let { now -> now } // the diff is taken against the tree now; the pre-dispatch stamp is in the handle
                val log = try {
                    Files.readAllBytes(proc.log)
                } catch (missing: IOException) {
                    poll.newBytes
                }
                val logBlob = blobs.put(redaction.applyBytes(log, ContentClass.ReusableEvidence).text.toByteArray(Charsets.UTF_8), BlobKind.LOG, ids)
                val stampBefore = CandidateId(Digest(handle.stampBefore))
                val changed = if (before.candidateId == stampBefore) emptyList() else announceFromNow(before, "run ${handle.alias}")
                val capture = RunCapture(handle.actionId, handle.argv, handle.shell, handle.cwd, (status as? ProcStatus.Exited)?.exitCode, status == ProcStatus.DeadlineExceeded, log, status !is ProcStatus.Lost)
                val shaped = Shapers.shape(capture, ShapeBudget(args.budget, estimator, handle.alias))
                val outcome = if (status is ProcStatus.Lost || status is ProcStatus.Cancelled) Outcome.UnknownOutcome else shaped.status
                val view = "handle ${handle.handleId} ${wire(status)}\n" + shaped.view + (if (changed.isEmpty()) "" else "\ntouched (by run ${handle.alias}: ${changed.size} paths) " + changed.take(10).joinToString(", "))
                val result = RunResult(handle.alias, handle.actionId, capture.exitCode, outcome, view, shaped.viewTruncated, logBlob, if (changed.isEmpty()) EffectClass.R else EffectClass.W, stampBefore, before.candidateId, true, changed, handle.handleId, shaped.counts, shaped, shaped.limitations)
                render(args, result, handle.argv, handle.shell, null, before, effectsUnknown = status is ProcStatus.Lost)
            }
        }
    }

    /** A background process's writes are only known by their result: every stamped member that differs from the pre-dispatch tree moved. */
    private fun announceFromNow(now: StampReport, cause: String): List<String> {
        val changed = ArrayList<String>()
        for ((path, entry) in now.members) {
            val to = entry.digest?.let(::FileVersion)
            val from = registry.recorded(path)
            if (from != to) {
                registry.change(path, from, to, cause)
                changed += path
            }
        }
        return changed
    }

    private fun cancel(args: RunArgs): ToolOutcome {
        val handle = handles.get(args.handle!!) ?: return refused(args, Outcome.Denied, "no handle '${args.handle}' in this campaign")
        val proc = try {
            os.terminate(os.reattach(handle.proc))
        } catch (failure: IOException) {
            return refused(args, Outcome.UnknownOutcome, "handle ${handle.handleId}: cancellation could not be delivered (${failure.message}); status unknown")
        }
        handles.save(handle.copy(proc = proc, status = wire(proc.status)))
        val view = "cancel requested for handle ${handle.handleId} · status ${wire(proc.status)} · a cancellation is a request and a status, not proof that every effect stopped"
        val result = RunResult(handle.alias, handle.actionId, (proc.status as? ProcStatus.Exited)?.exitCode, Outcome.UnknownOutcome, view, false, null, EffectClass.R, CandidateId(Digest(handle.stampBefore)), null, false, emptyList(), handle.handleId, null, null, emptyList())
        return render(args, result, handle.argv, handle.shell, null, null, effectsUnknown = true, statusWire = wire(proc.status))
    }

    // ---------------------------------------------------------------- render

    private fun refused(args: RunArgs, status: Outcome, detail: String): ToolOutcome {
        val actionId = idGen.next("act")
        val header = EnvelopeHeader(
            resultAlias = "#-", tool = "run", effectClass = null, versions = emptyMap(), stamp = null, truncated = false, effects = Effects.None,
            runtime = RuntimeFields(actionId, wire(status), null, null, args.argv?.joinToString(" ") ?: args.cmd ?: args.handle, "complete", effectsUnknown = status == Outcome.UnknownOutcome),
        )
        return ToolOutcome(detail, header, tokens = estimator.estimate(detail).tokens)
    }

    private fun render(args: RunArgs, result: RunResult, argv: List<String>, shell: Boolean, before: StampReport?, after: StampReport?, effectsUnknown: Boolean, statusWire: String = wire(result.status)): ToolOutcome {
        val exit = result.exit?.let { "exit $it · " } ?: ""
        val head = "run ${result.alias} $statusWire · class ${result.effectClass}" + (if (shell) " · shell wrapper" else "") + " · $exit${argv.joinToString(" ").take(80)}"
        val body = head + "\n" + result.view
        observations.record(
            Observation(
                id = idGen.next("obs"), ids = ids, actionId = result.actionId, candidate = after?.candidateId, contentRef = result.log ?: blobs.put(body.toByteArray(Charsets.UTF_8), BlobKind.OUTPUT, ids),
                paths = emptyList(), ranges = emptyMap(), complete = !result.truncated, sourceVersions = emptyMap(), captureComplete = result.status != Outcome.UnknownOutcome,
                truncated = result.truncated,
            ),
        )
        val header = EnvelopeHeader(
            resultAlias = result.alias, tool = "run", effectClass = result.effectClass, versions = emptyMap(), stamp = after?.candidateId, truncated = result.truncated,
            effects = if (result.changedPaths.isNotEmpty()) Effects.Observed else if (effectsUnknown) Effects.Unknown else Effects.None,
            flags = InstructionShape.detect(body).flags,
            runtime = RuntimeFields(
                actionId = result.actionId, status = statusWire, candidateBefore = before?.candidateId, candidateAfter = after?.candidateId,
                scope = argv.joinToString(" ").take(80), completeness = if (result.truncated) "truncated" else "complete",
                artifactRefs = listOfNotNull(result.log?.hex), captureComplete = result.shaped?.captureTruncated?.not() ?: true, displayTruncated = result.truncated,
                redactionApplied = false, effectsObserved = result.changedPaths.take(20), effectsUnknown = effectsUnknown,
            ),
        )
        return ToolOutcome(body, header, green = result.status == Outcome.Passed, tokens = estimator.estimate(body).tokens)
    }

    private fun logPath(actionId: String): Path {
        Files.createDirectories(logsDir)
        return logsDir.resolve("$actionId.log")
    }

    private fun wire(status: ProcStatus): String = when (status) {
        ProcStatus.Running -> "running"
        is ProcStatus.Exited -> "exited"
        ProcStatus.DeadlineExceeded -> "deadline_exceeded"
        ProcStatus.Cancelled -> "cancelled"
        ProcStatus.Lost -> "lost"
    }

    private fun wire(outcome: Outcome): String = outcome.name.replace(Regex("(?<=[a-z])([A-Z])")) { "_" + it.value }.lowercase()
}
