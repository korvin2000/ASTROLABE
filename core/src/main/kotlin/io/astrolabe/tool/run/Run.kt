package io.astrolabe.tool.run

import io.astrolabe.Config
import io.astrolabe.auth.Capability
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.Classification
import io.astrolabe.auth.ContentClass
import io.astrolabe.auth.ContainmentProbe
import io.astrolabe.auth.EffectPolicy
import io.astrolabe.auth.EffectPolicyConfig
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
import io.astrolabe.tool.Catalog
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.Effects
import io.astrolabe.tool.EnvelopeHeader
import io.astrolabe.tool.GeneratedTools
import io.astrolabe.tool.RunArgs
import io.astrolabe.tool.RuntimeFields
import io.astrolabe.tool.ToolCall
import io.astrolabe.tool.ToolExecutor
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOps
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.ToolSet
import io.astrolabe.tool.TurnContext
import io.astrolabe.workspace.Intent as PathIntent
import io.astrolabe.workspace.PathKind
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.StampReport
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import io.astrolabe.workspace.WorkspacePath
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** `cwd` blank, `.` or `./` (`.\` on Windows) names the workspace root, which a path resolution refuses (D-352). */
internal fun namesWorkspaceRoot(dir: String): Boolean = dir.isBlank() || dir.trim() in setOf(".", "./", ".\\")

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
 * The `run` family (§5.4, §4.6, §9.4, §13.1, TODO P1.6.5): `run(argv|cmd)`, `run(op=poll)`, `run(op=wait)`, `run(op=cancel)`.
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
 * running (FX-22); `cancel` is a request and a status, never proof that every effect stopped. `wait` observes a
 * handle in one call until the process ends, or until a readiness condition holds (`until_line`, `until_port`), or
 * its deadline on the injected clock passes; an end before readiness stops the wait with the terminal diagnostics,
 * and a cancelled wait leaves the process running. `until_*` on a launch starts it in the background and waits.
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
    /** The session's frozen mounts (§15.3); `run(["mcp:<server>/<tool>", "{…}"])` resolves only against it. */
    private val catalog: Catalog = Catalog.EMPTY,
    /** The transport to mounted servers; `null` until P7 wires one (the invocation is then `unavailable`). */
    private val mcp: McpClient? = null,
    /** The generated tools active at this attempt's boundary (§12.2); `run(["tool:<name>", …])` resolves only against it. */
    private val tools: ToolSet = ToolSet.EMPTY,
    /** The role this executor serves when that role runs R-class commands only (§3.4 probe, plan); `null` for the writing roles. */
    private val readOnlyRole: String? = null,
) : ToolExecutor {
    internal var beforeDispatch: () -> Unit = {}
    init {
        require(ids.context != null) { "run runs inside a cell: ids.context is its lineage" }
        require(pollSliceSeconds > 0) { "pollSliceSeconds must be positive" }
    }

    private val RunArgs.budgetTokens: Int get() = budget ?: config.defaults.runBudgetTokens

    /** An explicit timeout is clamped, never refused; the configured default may exceed the clamp. */
    private val RunArgs.timeoutSeconds: Int
        get() = timeout?.coerceAtMost(maxOf(MAX_TIMEOUT_SECONDS, config.defaults.runTimeoutSeconds)) ?: config.defaults.runTimeoutSeconds

    /** How long a poll waits for new output: a quiet server must not hold the turn for the whole run timeout. */
    private val RunArgs.pollWaitSeconds: Long
        get() = (timeout?.coerceAtMost(maxOf(MAX_TIMEOUT_SECONDS, config.defaults.runTimeoutSeconds)) ?: pollSliceSeconds.toInt()).toLong()

    override suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome {
        require(call.family == ToolFamily.Run) { "not a run call: ${call.name}" }
        // D-352: a cwd naming the root is no cwd, so intents, handles and the unknown-outcome guard see one command.
        val args = (call.args as Args.Run).args.let { if (it.cwd != null && namesWorkspaceRoot(it.cwd)) it.copy(cwd = null) else it }
        // A wait observes a handle exactly as a poll does, so a role allowed to poll may wait.
        if (!mask.allows(call.name) && !(args.op == "wait" && mask.allows(ToolOps.name(ToolFamily.Run, "poll")))) {
            return refused(args, Outcome.Denied, "${call.name} is masked in this role")
        }
        return when (args.op) {
            "run" -> run(if (!args.bg && !args.until().none) args.copy(bg = true) else args, context)
            // D-365 tolerance: a poll that names a readiness condition is a wait.
            "poll" -> if (args.until().none) poll(args, context) else wait(args)
            "wait" -> wait(args)
            "cancel" -> cancel(args)
            else -> refused(args, Outcome.Denied, "unknown run op '${args.op}'")
        }
    }

    // ------------------------------------------------------------------- run

    private sealed interface Launch {
        data class Unavailable(val reason: String) : Launch
        data class Background(val proc: Proc, val firstOutput: ByteArray, val cursor: Long) : Launch
        data class Finished(val proc: Proc, val output: ByteArray, val captureComplete: Boolean) : Launch
    }

    private suspend fun run(args: RunArgs, context: TurnContext): ToolOutcome {
        val contract = contracts.current(ids.work) ?: return refused(args, Outcome.Denied, "no committed contract for ${ids.work}")
        val requested = args.argv ?: listOf(args.cmd!!)
        val shell = args.argv == null
        val program = requested.first().trim()
        if (program.startsWith("mcp:")) return mcp(args, requested, shell, contract)
        val generated = if (program.startsWith("tool:")) {
            if (shell) return refused(args, Outcome.Denied, "generated tools take argv form: run([\"$program\", …]); nothing was dispatched")
            tools.resolve(program) ?: return refused(args, Outcome.Denied, "'$program' is not active at this attempt's boundary; see look(catalog); nothing was dispatched")
        } else {
            null
        }
        val argv = generated?.let { it.script + requested.drop(1) } ?: requested
        val cwd = args.cwd?.let { dir ->
            when (val resolved = workspace.resolve(dir, PathIntent.Read)) {
                is PathResolution.Resolved -> resolved.real
                is PathResolution.Rejected -> return refused(args, Outcome.Denied, "cwd '$dir' refused: ${resolved.detail}")
            }
        } ?: workspace.root

        // Policy label (§4.6), capability ceiling (§14.2) and execution mode (D-11) — all before any effect.
        val containment = DiskContainment(workspace.paths, { contract.scope.protects(it, ignoreCase = true) || workspace.paths.isProtected(it, PathIntent.Mutate) })
        val classified = EffectPolicy.classify(if (generated == null) args else args.copy(argv = argv), workspace.root.toString(), contract.scope.protectedPaths, EffectPolicyConfig(), containment)
        // §12.2: a generated wrapper's declarations only add to its script's classification; the caller's ceiling decides.
        val classification = generated?.let { GeneratedTools.inherit(classified, it) } ?: classified
        authorize(args, argv, contract, classification)?.let { return it }
        val replaySafe = classification.effectClass == EffectClass.R && !classification.effectsUnknown

        val actionId = idGen.next("act")
        val alias = aliases.allocate(ids.work, actionId, "result", ids.context, workspace.id)
        val before = stamper.report()
        // D-321: a background process may outlive a crash, so only a foreground run's effects are confined to one stamp.
        val confined = !args.bg && classification.effectClass != EffectClass.D && !classification.effectsUnknown &&
            classification.requiredCapabilities.all { it in setOf(Capability.WorkspaceRead, Capability.WorkspaceWrite, Capability.RunLocal) }
        val intent = Intent(idGen.next("intent"), ids, actionId, argv, args.cwd, classification.toString(), at = clock.instant(), replaySafe = replaySafe, workspaceConfined = confined)
        val spec = SpawnSpec(
            command = if (shell) Command.Shell(args.cmd!!) else Command.Argv(argv),
            workingDirectory = cwd,
            logPath = logPath(actionId),
            environment = EnvPolicy(inheritedNames = config.redaction.envAllowlist, extra = mapOf("CI" to "1", "NO_COLOR" to "1")),
            deadlineSeconds = args.timeoutSeconds.toLong(),
        )
        var logBlob: Digest? = null
        var rendered: ToolOutcome? = null
        var started: String? = null
        // The fence throws before any intent is recorded, so a lapsed lease never leaves an open intent (§13.1).
        beforeDispatch()
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
                rendered = when (launch) {
                    is Launch.Unavailable -> {
                        val result = RunResult(alias.text, actionId, null, Outcome.Unavailable, "cannot start: ${launch.reason}", false, logBlob, classification.effectClass, before.candidateId, before.candidateId, true, emptyList(), null, null, null, listOf(launch.reason), intent.intentId)
                        render(args, result, argv, shell, before, before, classification.effectsUnknown)
                    }
                    is Launch.Background -> {
                        val handle = Handle(idGen.next("handle"), ids, actionId, alias.text, argv, shell, args.cwd, launch.proc, wire(launch.proc.status), launch.cursor, before.candidateId.digest.hex,
                            classification.effectClass, classification.effectsUnknown, before.members, before.baseCommit)
                        handles.save(handle)
                        started = handle.handleId
                        val safe = redaction.applyBytes(launch.firstOutput, ContentClass.ModelFacing)
                        val slice = safe.text
                        val view = "background run ${alias.text} handle ${handle.handleId} · ${wire(launch.proc.status)} · wait with run(op=wait, handle=\"${handle.handleId}\")" + (if (slice.isBlank()) "" else "\n$slice")
                        val result = RunResult(alias.text, actionId, null, Outcome.NotRun, view, false, logBlob, classification.effectClass, before.candidateId, null, false, emptyList(), handle.handleId, null, null, emptyList(), intent.intentId)
                        render(args, result, argv, shell, before, null, classification.effectsUnknown, statusWire = wire(launch.proc.status), captureMask = safe.mask)
                    }
                    is Launch.Finished -> finish(args, alias.text, actionId, argv, shell, before, launch.proc, launch.output, classification.effectClass, classification.effectsUnknown, logBlob, intent.intentId, launch.captureComplete)
                }
            },
        )
        return when (outcome) {
            // The launch is committed and its handle persisted before the wait, so a cancelled wait leaves no open intent.
            is ActionOutcome.Completed -> started?.takeUnless { args.until().none }?.let { wait(args.copy(op = "wait", handle = it, since = 0)) } ?: checkNotNull(rendered)
            is ActionOutcome.Unknown -> unknown(args, alias.text, actionId, before, intent.intentId, outcome.cause)
            is ActionOutcome.NotDispatched -> refused(args, Outcome.Denied, outcome.reason)
        }
    }

    /**
     * Policy label (§4.6), capability ceiling (§14.2), execution mode (D-11), D-class authority and the
     * unknown-outcome guard (§13.1) — all before any effect, for commands and mounted tools alike (§15.3, FX-39).
     */
    private suspend fun authorize(args: RunArgs, argv: List<String>, contract: Contract, classification: Classification): ToolOutcome? {
        val ceiling = try {
            Ceiling.of(contract.authorization, config.executionMode, hostSets)
        } catch (misconfigured: IllegalArgumentException) {
            return refused(args, Outcome.Denied, "denied: ${misconfigured.message}")
        }
        ceiling.allows(classification)?.let { return refused(args, Outcome.Denied, "denied by the capability ceiling: ${it.detail}") }
        if (readOnlyRole != null && classification.effectClass != EffectClass.R) {
            return refused(args, Outcome.Denied, "denied: the $readOnlyRole role runs R-class commands only; this command is ${classification.effectClass}-class " +
                "(${classification.reasons.joinToString("; ")}); nothing was dispatched")
        }
        val decision = Executors.require(config.executionMode)
        if (decision is ExecutionDecision.Refused) return refused(args, Outcome.Denied, "denied: ${decision.refusal.detail} (D-11)")
        if (classification.effectClass == EffectClass.D) {
            val reason = args.intent ?: return refused(args, Outcome.Denied, "D-class effect (${classification.reasons.joinToString("; ")}) needs an explicit intent; nothing was dispatched")
            val allowlisted = contract.authorization.dClassAllowlist.any { classification.command == it || classification.command.startsWith("$it ") }
            val request = DClassRequest(idGen.next("dreq"), contract.version, ids, classification.command, argv, args.cwd, classification.reasons.joinToString("; "), reason, allowlisted)
            val approval = authority.approve(request)
            currentCoroutineContext().ensureActive()
            if (approval.requestId != request.id || approval.contractRevision != request.contractRevision ||
                contracts.current(ids.work)?.version != request.contractRevision
            ) return refused(args, Outcome.Denied, "D-class approval does not match the pending request and current contract; nothing was dispatched")
            if (!approval.approved) return refused(args, Outcome.Denied, "D-class effect denied: ${approval.reason ?: "no approval"} (${classification.reasons.joinToString("; ")})")
        }

        val replaySafe = classification.effectClass == EffectClass.R && !classification.effectsUnknown
        if (!replaySafe) intents.open().firstOrNull { it.ids.work == ids.work && !it.replaySafe && it.argv == argv && it.cwd == args.cwd }?.let {
            return refused(args, Outcome.UnknownOutcome, "unknown_outcome: intent ${it.intentId} ran this command and its effect is unreconciled; reconcile before any retry (§13.1), never relaunch")
        }
        return null
    }

    // ------------------------------------------------------------------- mcp

    /**
     * A mounted tool (§15.3, D-21): resolved in the frozen catalog, its arguments validated against the frozen
     * schema, its class decided locally, then the same ceiling, D-class authority, intent order, store, shaping
     * and envelope as any command. The server's reply is data; a thrown transport error is `unknown_outcome`.
     */
    private suspend fun mcp(args: RunArgs, argv: List<String>, shell: Boolean, contract: Contract): ToolOutcome {
        val program = argv.first().trim()
        if (shell) return refused(args, Outcome.Denied, "mounted tools take argv form: run([\"$program\", \"{json arguments}\"]); nothing was dispatched")
        if (args.bg) return refused(args, Outcome.Denied, "mounted tools do not run in the background; nothing was dispatched")
        if (args.cwd != null) return refused(args, Outcome.Denied, "mounted tools take no cwd; nothing was dispatched")
        val entry = catalog.resolve(program) ?: return refused(args, Outcome.Denied, "'$program' is not mounted in this session; see look(catalog); nothing was dispatched")
        if (argv.size > 2) return refused(args, Outcome.Denied, "'$program' takes one JSON object of arguments (D-146); nothing was dispatched")
        val arguments = argv.getOrNull(1) ?: "{}"
        entry.validate(arguments)?.let { return refused(args, Outcome.Denied, "'$program' arguments rejected: $it; nothing was dispatched") }
        val reasons = when {
            entry.tool.name in entry.mount.effectClassOverride -> listOf("mount ${entry.mount.server}: configured class ${entry.effectClass}")
            entry.effectClass == EffectClass.R -> listOf("mount ${entry.mount.server}: locally approved read-only")
            entry.tool.name in entry.mount.localApproval -> listOf("mount ${entry.mount.server}: the server's own hints claim effects, read-only approval withheld (D-145)")
            else -> listOf("mount ${entry.mount.server}: not locally approved or configured (annotations are hints)")
        }
        val classification = Classification(entry.effectClass, reasons, entry.mount.capabilities, program, effectsUnknown = entry.effectClass != EffectClass.R)
        authorize(args, argv, contract, classification)?.let { return it }
        val client = mcp ?: return refused(args, Outcome.Unavailable, "no MCP transport is configured (P7); '$program' was not dispatched")

        val actionId = idGen.next("act")
        val alias = aliases.allocate(ids.work, actionId, "result", ids.context, workspace.id)
        val before = stamper.report()
        val intent = Intent(idGen.next("intent"), ids, actionId, argv, null, classification.toString(), at = clock.instant(), replaySafe = entry.effectClass == EffectClass.R)
        var logBlob: Digest? = null
        var rendered: ToolOutcome? = null
        // The fence throws before any intent is recorded, so a lapsed lease never leaves an open intent (§13.1).
        beforeDispatch()
        val outcome = Consequential.run(
            journal = intents,
            intent = intent,
            reserve = { true },
            dispatch = { client.call(entry.mount.server, entry.tool.name, arguments) },
            persist = { reply ->
                logBlob = blobs.put(redaction.applyBytes(reply.content.toByteArray(Charsets.UTF_8), ContentClass.ReusableEvidence).text.toByteArray(Charsets.UTF_8), BlobKind.LOG, ids)
                val after = stamper.report()
                val changed = announce(before, after, "run ${alias.text}")
                val effectClass = observedClass(entry.effectClass, changed)
                val capture = RunCapture(actionId = actionId, argv = argv, exitCode = if (reply.isError) 1 else 0, output = reply.content.toByteArray(Charsets.UTF_8))
                val shaped = Shapers.shape(capture, ShapeBudget(args.budgetTokens, estimator, alias.text))
                val touched = if (changed.isEmpty()) "" else "\ntouched (by run ${alias.text} $program: ${changed.size} path${if (changed.size == 1) "" else "s"}) " + changed.take(10).joinToString(", ")
                val result = RunResult(
                    alias.text, actionId, capture.exitCode, shaped.status, shaped.view + touched, shaped.viewTruncated, logBlob, effectClass, before.candidateId, after.candidateId,
                    current = true, changedPaths = changed, handle = null, parsed = shaped.counts, shaped = shaped, limits = shaped.limitations, intentId = intent.intentId,
                )
                rendered = render(args, result, argv, false, before, after, classification.effectsUnknown)
            },
        )
        return when (outcome) {
            is ActionOutcome.Completed -> checkNotNull(rendered)
            is ActionOutcome.Unknown -> unknown(args, alias.text, actionId, before, intent.intentId, outcome.cause)
            is ActionOutcome.NotDispatched -> refused(args, Outcome.Denied, outcome.reason)
        }
    }

    /** Spawns and, unless backgrounded, observes to the terminal state; the deadline kills the tree, nothing replays. */
    private suspend fun launch(spec: SpawnSpec, args: RunArgs): Launch {
        currentCoroutineContext().ensureActive()
        val proc = try {
            runner.start(spec)
        } catch (failure: IOException) {
            return Launch.Unavailable(failure.message ?: failure::class.simpleName.orEmpty())
        }
        if (args.bg) {
            val first = os.poll(proc, 0, 0)
            return Launch.Background(proc.copy(status = first.status), first.newBytes, first.nextCursorBytes)
        }
        val observed = Executions.observeCancellable(os, proc, pollSliceSeconds, args.timeoutSeconds.toLong() + 5)
        if (observed.lost) throw IOException("process observation lost; reconcile before retry")
        return Launch.Finished(observed.proc, observed.output, captureComplete = !observed.truncated)
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
        captureComplete: Boolean,
    ): ToolOutcome {
        val after = stamper.report()
        val changed = announce(before, after, "run $alias")
        val effectClass = observedClass(label, changed)
        val status = proc.status
        val capture = RunCapture(
            actionId = actionId, argv = argv, shell = shell, cwd = args.cwd,
            exitCode = (status as? ProcStatus.Exited)?.exitCode, timedOut = status == ProcStatus.DeadlineExceeded,
            output = output, captureComplete = captureComplete && status !is ProcStatus.Lost,
        )
        val shaped = Shapers.shape(capture, ShapeBudget(args.budgetTokens, estimator, alias))
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
        return render(args, result, argv, shell, before, after, effectsUnknown || status is ProcStatus.Lost,
            captureMask = redaction.applyBytes(output, ContentClass.ReusableEvidence).mask, completedPlainly = completedPlainly(capture, outcome, shaped))
    }

    private fun completedPlainly(capture: RunCapture, outcome: Outcome, shaped: Shaped): Boolean =
        shaped.shaper == Shapers.generic.id && GenericShaper.completedPlainly(capture, outcome, shaped.counts, shaped.wrapper)

    /** §4.6 post hoc: a write under a protected path is D whatever the launch label; any other change lifts R to W. */
    private fun observedClass(label: EffectClass, changed: List<String>): EffectClass = when {
        changed.isEmpty() -> label
        changed.any { workspace.paths.isProtected(it, PathIntent.Mutate) } -> EffectClass.D
        else -> maxOf(label, EffectClass.W)
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

    private suspend fun poll(args: RunArgs, context: TurnContext): ToolOutcome {
        val handle = ownedHandle(args.handle!!) ?: return refused(args, Outcome.Denied, "no handle '${args.handle}' in this campaign workspace")
        val proc = os.reattach(handle.proc)
        val since = args.since ?: handle.cursor
        val poll = try {
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { os.poll(proc, since, args.pollWaitSeconds) }
        } catch (failure: IOException) {
            handles.save(handle.copy(status = wire(ProcStatus.Lost)))
            return refused(args, Outcome.UnknownOutcome, "handle ${handle.handleId}: the log cannot be read (${failure.message}); the process state is unknown — reconcile, never relaunch")
        }
        val updated = handle.copy(proc = proc.copy(status = poll.status), status = wire(poll.status), cursor = poll.nextCursorBytes)
        handles.save(updated)
        val safeSlice = redaction.applyBytes(poll.newBytes, ContentClass.ModelFacing)
        val slice = safeSlice.text
        return when (val status = poll.status) {
            ProcStatus.Running -> {
                val view = "handle ${handle.handleId} running · cursor ${poll.nextCursorBytes}" + (if (poll.timedOut) " · observation timed out after ${args.pollWaitSeconds}s, the process keeps running (no relaunch)" else "") + (if (slice.isBlank()) "" else "\n$slice")
                val result = RunResult(handle.alias, handle.actionId, null, Outcome.NotRun, view, false, null, handle.effectClass, CandidateId(Digest(handle.stampBefore)), null, false, emptyList(), handle.handleId, null, null, emptyList())
                render(args, result, handle.argv, handle.shell, null, null, effectsUnknown = handle.effectsUnknown, statusWire = "running", captureMask = safeSlice.mask)
            }
            else -> ended(args, handle, proc, status, poll.newBytes, note = null)
        }
    }

    /** A background process reached its terminal [status]: the whole log is stored and shaped, the interval diffed. */
    private fun ended(args: RunArgs, handle: Handle, proc: Proc, status: ProcStatus, fallback: ByteArray, note: String?): ToolOutcome {
        val after = stamper.report()
        var complete = true
        val log = try {
            Files.newInputStream(proc.log).use {
                val bytes = it.readNBytes(Executions.MAX_CAPTURE_BYTES)
                complete = it.read() == -1
                bytes
            }
        } catch (missing: IOException) {
            complete = false
            fallback
        }
        val safeLog = redaction.applyBytes(log, ContentClass.ReusableEvidence)
        val logBlob = blobs.put(safeLog.text.toByteArray(Charsets.UTF_8), BlobKind.LOG, ids)
        val stampBefore = CandidateId(Digest(handle.stampBefore))
        val changed = announceBackground(handle, after)
        val capture = RunCapture(handle.actionId, handle.argv, handle.shell, handle.cwd, (status as? ProcStatus.Exited)?.exitCode, status == ProcStatus.DeadlineExceeded, log, complete && status !is ProcStatus.Lost)
        val shaped = Shapers.shape(capture, ShapeBudget(args.budgetTokens, estimator, handle.alias))
        val outcome = if (status is ProcStatus.Lost || status is ProcStatus.Cancelled) Outcome.UnknownOutcome else shaped.status
        val view = "handle ${handle.handleId} ${wire(status)}\n" + (note?.let { "$it\n" } ?: "") + shaped.view + "\nBackground effects cannot be attributed exclusively to this process." + (if (changed.isEmpty()) "" else "\nchanged during background run (${changed.size} paths): " + changed.take(10).joinToString(", "))
        val effectClass = observedClass(handle.effectClass, changed)
        val result = RunResult(handle.alias, handle.actionId, capture.exitCode, outcome, view, shaped.viewTruncated, logBlob, effectClass, stampBefore, after.candidateId, true, changed, handle.handleId, shaped.counts, shaped, shaped.limitations)
        return render(args, result, handle.argv, handle.shell, null, after, effectsUnknown = true, captureMask = safeLog.mask, completedPlainly = completedPlainly(capture, outcome, shaped))
    }

    // ------------------------------------------------------------------ wait

    /** What a wait stops on before the process ends; with neither condition it waits for the end itself. */
    private class Until(val pattern: String?, val line: Regex?, val port: Int?) {
        val none: Boolean get() = line == null && port == null

        override fun toString(): String =
            listOfNotNull(pattern?.let { "a line matching /$it/" }, port?.let { "port $it accepting connections" }).joinToString(" or ").ifEmpty { "the process to end" }
    }

    private fun RunArgs.until(): Until {
        val pattern = untilLine?.takeIf { it.isNotBlank() }
        // D-365 tolerance: a pattern that does not compile is matched literally.
        val regex = pattern?.let { runCatching { Regex(it) }.getOrElse { _ -> Regex(Regex.escape(pattern)) } }
        return Until(pattern, regex, untilPort)
    }

    private sealed interface Waited {
        val cursor: Long

        /** [matched] is the readiness line that arrived with the end, if one did. */
        class Ended(val status: ProcStatus, override val cursor: Long, val tail: ByteArray, val matched: String?) : Waited
        class Ready(val reason: String, override val cursor: Long, val tail: ByteArray, val dropped: Long) : Waited
        class Expired(override val cursor: Long, val tail: ByteArray, val dropped: Long) : Waited
    }

    /**
     * `run(op=wait)`: one call observes the handle until its process ends, a readiness condition holds, or the wait
     * deadline passes (`timeout`, clamped like a run's, measured on the injected clock). The deadline ends only the
     * observation, never the process (§13.1); the process has its own deadline from its launch, which a launch with
     * `until_*` sets to the same `timeout`. A cancelled wait leaves the handle as it was.
     */
    private suspend fun wait(args: RunArgs): ToolOutcome {
        val handle = ownedHandle(args.handle!!) ?: return refused(args, Outcome.Denied, "no handle '${args.handle}' in this campaign workspace")
        val until = args.until()
        val proc = os.reattach(handle.proc)
        val limitSeconds = args.timeoutSeconds.toLong()
        val waitDeadline = clock.instant().plusSeconds(limitSeconds)
        val processSeconds = proc.deadlineSeconds
        // The supervisor stops the process at its own deadline: a wait that is not shorter never outlives it.
        val stoppedFirst = processSeconds != null && !waitDeadline.isBefore(java.time.Instant.ofEpochMilli(proc.startedAtEpochMillis).plusSeconds(processSeconds))
        val waited = try {
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { await(proc, args.since ?: handle.cursor, until, waitDeadline) }
        } catch (failure: IOException) {
            handles.save(handle.copy(status = wire(ProcStatus.Lost)))
            return refused(args, Outcome.UnknownOutcome, "handle ${handle.handleId}: the log cannot be read (${failure.message}); the process state is unknown — reconcile, never relaunch")
        }
        val (line, tail, dropped) = when (waited) {
            is Waited.Ended -> {
                handles.save(handle.copy(proc = proc.copy(status = waited.status), status = wire(waited.status), cursor = waited.cursor))
                // §14 risk: an end before readiness interrupts the wait, and the terminal diagnostics are its result.
                val note = when {
                    until.none -> null
                    waited.matched != null -> "wait ended: the process ended; readiness line matched: ${waited.matched}"
                    else -> "wait ended: the process ended before $until"
                }
                return ended(args, handle, proc, waited.status, waited.tail, note)
            }
            is Waited.Ready -> Triple("ready: ${waited.reason}", waited.tail, waited.dropped)
            is Waited.Expired -> Triple(
                if (stoppedFirst) "wait timed out after ${limitSeconds}s before $until; the process deadline (${processSeconds}s from its start) is reached and the process is being stopped (no relaunch), poll the handle for its final status"
                else "wait timed out after ${limitSeconds}s before $until, the process keeps running (no relaunch)",
                waited.tail, waited.dropped,
            )
        }
        handles.save(handle.copy(proc = proc.copy(status = ProcStatus.Running), status = wire(ProcStatus.Running), cursor = waited.cursor))
        val safe = redaction.applyBytes(tail, ContentClass.ModelFacing)
        val shown = tailWithin(safe.text, args.budgetTokens)
        val elided = dropped > 0 || shown.length < safe.text.length
        val deadlineView = if (processSeconds == null || (waited is Waited.Expired && stoppedFirst)) "" else " · process deadline ${processSeconds}s from its start"
        val view = "handle ${handle.handleId} running · $line · cursor ${waited.cursor}$deadlineView" +
            (if (elided) "\n… earlier output elided; the log holds it" else "") + (if (shown.isBlank()) "" else "\n$shown")
        val result = RunResult(handle.alias, handle.actionId, null, Outcome.NotRun, view, elided, null, handle.effectClass, CandidateId(Digest(handle.stampBefore)), null, false, emptyList(), handle.handleId, null, null, emptyList())
        return render(args, result, handle.argv, handle.shell, null, null, effectsUnknown = handle.effectsUnknown, statusWire = "running", captureMask = safe.mask)
    }

    /** The blocking wait loop; an interrupted (cancelled) caller propagates and leaves the process alone. */
    private fun await(start: Proc, since: Long, until: Until, deadline: java.time.Instant): Waited {
        var proc = start
        var cursor = since
        val tail = TailBuffer(WAIT_TAIL_BYTES)
        var partial = ByteArray(0)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("wait interrupted")
            val remainingMillis = java.time.Duration.between(clock.instant(), deadline).toMillis()
            if (remainingMillis <= 0) return Waited.Expired(cursor, tail.bytes(), tail.dropped)
            // A port opens silently, so a port wait looks again every second; otherwise output or the end wakes the poll.
            val slice = if (until.port != null) 1L else minOf(pollSliceSeconds, (remainingMillis + 999) / 1_000)
            val poll = os.poll(proc, cursor, slice)
            if (poll.newBytes.isNotEmpty() && poll.nextCursorBytes <= cursor) throw IOException("the log cursor did not advance")
            tail.add(poll.newBytes)
            cursor = poll.nextCursorBytes
            proc = proc.copy(status = poll.status)
            val terminal = proc.status.isTerminal
            var matched: String? = null
            // The last bytes arrive together with the terminal status, so the line is matched before the end is reported.
            if (until.line != null) {
                val pending = partial + poll.newBytes
                val end = pending.lastIndexOf('\n'.code.toByte())
                // Nothing completes a line once the process has ended, so the unfinished one counts as it stands.
                val cut = if (terminal) pending.size else if (end >= 0) end + 1 else if (pending.size > WAIT_LINE_BYTES) pending.size else 0
                partial = pending.copyOfRange(cut, pending.size)
                // Matched on the redacted line: a pattern is no oracle for a secret the model is never shown.
                matched = pending.copyOfRange(0, cut).toString(Charsets.UTF_8).lineSequence()
                    .map { redaction.apply(it.trimEnd('\r')).text }.firstOrNull { until.line.containsMatchIn(it) }?.take(200)
            }
            if (terminal) return Waited.Ended(proc.status, cursor, tail.bytes(), matched)
            if (matched != null) return Waited.Ready("line matched: $matched", cursor, tail.bytes(), tail.dropped)
            if (until.port != null && os.listening(until.port)) return Waited.Ready("port ${until.port} accepts connections", cursor, tail.bytes(), tail.dropped)
        }
    }

    /** The last whole lines of [text] that fit [budgetTokens]. */
    private fun tailWithin(text: String, budgetTokens: Int): String {
        if (estimator.estimate(text).tokens <= budgetTokens) return text
        val lines = text.lines()
        var used = 0L
        var first = lines.size
        while (first > 0) {
            val cost = estimator.estimate(lines[first - 1]).tokens + 1
            if (used + cost > budgetTokens) break
            used += cost
            first--
        }
        return lines.subList(first, lines.size).joinToString("\n")
    }

    /** The interval diff invalidates evidence but cannot attribute concurrent edits to a background process. */
    private fun announceBackground(handle: Handle, now: StampReport): List<String> {
        val before = handle.membersBefore
        val baseChanges = if (handle.baseCommitBefore != null && handle.baseCommitBefore != now.baseCommit) {
            fun tree(commit: String) = if (commit == io.astrolabe.id.Stamp.NO_COMMIT) emptyMap() else
                workspace.git.lsTree(io.astrolabe.os.ObjectId(commit), recursive = true).associateBy { it.path }
            val oldTree = tree(handle.baseCommitBefore)
            val newTree = tree(now.baseCommit)
            (oldTree.keys + newTree.keys).filter { oldTree[it] != newTree[it] }.toSet()
        } else emptySet()
        val changed = ((before?.keys ?: emptySet()) + now.members.keys + baseChanges).sorted().filter {
            before == null || handle.baseCommitBefore != now.baseCommit || before[it] != now.members[it]
        }
        for (path in changed) {
            val from = before?.get(path)?.digest?.let(::FileVersion) ?: registry.recorded(path)
            val to = now.members[path]?.digest?.let(::FileVersion) ?: registry.version(path)
            if (from != to) registry.change(path, from, to, "background interval ${handle.alias}")
        }
        return changed
    }

    private fun cancel(args: RunArgs): ToolOutcome {
        val handle = ownedHandle(args.handle!!) ?: return refused(args, Outcome.Denied, "no handle '${args.handle}' in this campaign workspace")
        val proc = try {
            os.terminate(os.reattach(handle.proc))
        } catch (failure: IOException) {
            return refused(args, Outcome.UnknownOutcome, "handle ${handle.handleId}: cancellation could not be delivered (${failure.message}); status unknown")
        }
        handles.save(handle.copy(proc = proc, status = wire(proc.status)))
        val view = "cancel requested for handle ${handle.handleId} · status ${wire(proc.status)} · a cancellation is a request and a status, not proof that every effect stopped"
        val result = RunResult(handle.alias, handle.actionId, (proc.status as? ProcStatus.Exited)?.exitCode, Outcome.UnknownOutcome, view, false, null, handle.effectClass, CandidateId(Digest(handle.stampBefore)), null, false, emptyList(), handle.handleId, null, null, emptyList())
        return render(args, result, handle.argv, handle.shell, null, null, effectsUnknown = true, statusWire = wire(proc.status))
    }

    // ---------------------------------------------------------------- render

    /** Attempts may resume their campaign's handles; another work or workspace has no authority over them. */
    private fun ownedHandle(id: String): Handle? = handles.get(id)?.takeIf {
        it.ids.work == ids.work && aliases.byCanonical(ids.work, it.actionId)?.workspace == workspace.id
    }

    private fun refused(args: RunArgs, status: Outcome, detail: String): ToolOutcome {
        val actionId = idGen.next("act")
        val safe = redaction.apply(detail)
        val header = EnvelopeHeader(
            resultAlias = "#-", tool = "run", effectClass = null, versions = emptyMap(), stamp = null, truncated = safe.limitations.isNotEmpty(), effects = Effects.None,
            runtime = RuntimeFields(actionId, wire(status), null, null, (args.argv?.joinToString(" ") ?: args.cmd ?: args.handle)?.let { redaction.apply(it).text }, if (safe.limitations.isEmpty()) "complete" else "truncated", redactionApplied = safe.applied, effectsUnknown = status == Outcome.UnknownOutcome),
        )
        return ToolOutcome(safe.text, header, tokens = estimator.estimate(safe.text).tokens)
    }

    private fun render(args: RunArgs, result: RunResult, argv: List<String>, shell: Boolean, before: StampReport?, after: StampReport?, effectsUnknown: Boolean, statusWire: String = wire(result.status), captureMask: io.astrolabe.evidence.RedactionMask = io.astrolabe.evidence.RedactionMask.NONE, completedPlainly: Boolean = false): ToolOutcome {
        // D-351/D-353: presentation only — the outcome and `green` stay what the shaper derived (D-50); a plain exit 0
        // reads `completed` in the header too, never green.
        val shown = if (completedPlainly) GenericShaper.COMPLETED else statusWire
        val exit = if (completedPlainly) "" else result.exit?.let { "exit $it · " } ?: ""
        val head = "run ${result.alias} $shown · class ${result.effectClass}" + (if (shell) " · shell wrapper" else "") + " · $exit${redaction.apply(argv.joinToString(" ")).text.take(80)}"
        val safe = redaction.apply(head + "\n" + result.view, ContentClass.ReusableEvidence)
        val body = safe.text
        val truncated = result.truncated || safe.limitations.isNotEmpty()
        val captureComplete = result.status != Outcome.UnknownOutcome && result.shaped?.captureTruncated != true && captureMask.limitations.isEmpty()
        observations.record(
            Observation(
                id = idGen.next("obs"), ids = ids, actionId = result.actionId, candidate = after?.candidateId, contentRef = result.log ?: blobs.put(body.toByteArray(Charsets.UTF_8), BlobKind.OUTPUT, ids),
                paths = emptyList(), ranges = emptyMap(), complete = !truncated && captureComplete, sourceVersions = emptyMap(), captureComplete = captureComplete,
                truncated = truncated, redaction = if (result.log != null) captureMask else safe.mask,
            ),
        )
        val header = EnvelopeHeader(
            resultAlias = result.alias, tool = "run", effectClass = result.effectClass, versions = emptyMap(), stamp = after?.candidateId, truncated = truncated,
            effects = if (result.changedPaths.isNotEmpty()) Effects.Observed else if (effectsUnknown) Effects.Unknown else Effects.None,
            flags = InstructionShape.detect(body).flags,
            runtime = RuntimeFields(
                actionId = result.actionId, status = if (completedPlainly) GenericShaper.COMPLETED_STATUS else statusWire, candidateBefore = result.stampBefore, candidateAfter = after?.candidateId,
                scope = redaction.apply(argv.joinToString(" ")).text.take(80), completeness = if (truncated || !captureComplete) "truncated" else "complete",
                artifactRefs = listOfNotNull(result.log?.hex), captureComplete = captureComplete, displayTruncated = truncated,
                redactionApplied = safe.applied || captureMask.applied, effectsObserved = result.changedPaths.take(20).map { redaction.apply(it).text }, effectsUnknown = effectsUnknown,
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

/** The longest `run` a model may ask for in one call (D-370); a larger value is clamped to it. */
private const val MAX_TIMEOUT_SECONDS: Int = 3_600

/** How much of a wait's output stays in memory for its view; the log holds all of it. */
private const val WAIT_TAIL_BYTES: Int = 256 * 1024

/** A line longer than this without a newline is matched as it stands. */
private const val WAIT_LINE_BYTES: Int = 64 * 1024

/** The last [capacity] bytes added, and how many earlier ones were [dropped]. */
private class TailBuffer(private val capacity: Int) {
    private var held = ByteArray(0)
    var dropped: Long = 0
        private set

    fun add(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val joined = held + bytes
        val excess = joined.size - capacity
        if (excess > 0) dropped += excess
        held = if (excess > 0) joined.copyOfRange(excess, joined.size) else joined
    }

    fun bytes(): ByteArray = held.copyOf()
}

/**
 * D-375: [ContainmentProbe] over the real workspace. Every existing segment of the path is a plain directory (the last
 * may be a file), never a link, junction or special entry; its real path lies strictly below the root; and neither it nor
 * any of at most [limit] entries below it is protected ([protects]) or a link. Anything unreadable answers false.
 */
internal class DiskContainment(
    private val paths: WorkspacePath,
    private val protects: (relative: String) -> Boolean,
    private val limit: Int = 20_000,
) : ContainmentProbe {
    override fun contained(relative: String): Boolean = try {
        inspect(relative)
    } catch (e: IOException) {
        false
    } catch (e: InvalidPathException) {
        false
    } catch (e: DirectoryIteratorException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun inspect(relative: String): Boolean {
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        var path = paths.root
        for ((index, segment) in segments.withIndex()) {
            path = path.resolve(segment)
            when (WorkspacePath.kindOf(path)) {
                PathKind.Missing -> return !protects(relative)
                PathKind.Directory -> Unit
                PathKind.Regular -> if (index < segments.lastIndex) return false
                else -> return false
            }
        }
        val real = path.toRealPath()
        if (real == paths.root || !real.startsWith(paths.root) || protects(relativeOf(real))) return false
        if (WorkspacePath.kindOf(real) != PathKind.Directory) return true
        val pending = ArrayDeque(listOf(real))
        var seen = 0
        while (pending.isNotEmpty()) {
            Files.newDirectoryStream(pending.removeLast()).use { entries ->
                for (entry in entries) {
                    if (++seen > limit) return false
                    when (WorkspacePath.kindOf(entry)) {
                        PathKind.Directory -> pending.addLast(entry)
                        PathKind.Regular -> Unit
                        else -> return false
                    }
                    if (protects(relativeOf(entry))) return false
                }
            }
        }
        return true
    }

    private fun relativeOf(real: Path): String = paths.root.relativize(real).joinToString("/") { it.toString() }
}
