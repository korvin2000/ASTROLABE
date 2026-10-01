package io.astrolabe.cell

import io.astrolabe.telemetry.Accounting

import io.astrolabe.Defaults
import io.astrolabe.atlas.DefinitionChanges
import io.astrolabe.atlas.SymbolIndex
import io.astrolabe.auth.Boundary
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.ExecutionDecision
import io.astrolabe.auth.Executors
import io.astrolabe.budget.Admission
import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.Spend
import io.astrolabe.budget.Tokens
import io.astrolabe.context.AdmissionDecision
import io.astrolabe.context.CapacityCondition
import io.astrolabe.context.CarryForward
import io.astrolabe.context.ContextAdmission
import io.astrolabe.context.ContractSlice
import io.astrolabe.context.Rebuild
import io.astrolabe.context.RebuildReason
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Ledger
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.Outcome
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Generation
import io.astrolabe.id.IdGen
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.ProblemKind
import io.astrolabe.provider.InvocationProgress
import io.astrolabe.provider.ObservableAdapter
import io.astrolabe.provider.ProviderError
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Request
import io.astrolabe.provider.Response
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.ToolMask
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.UsageItem
import io.astrolabe.provider.Validation
import io.astrolabe.provider.estimate
import io.astrolabe.register.Condition
import io.astrolabe.register.ContractDigest
import io.astrolabe.register.DigestCapacity
import io.astrolabe.register.Mark
import io.astrolabe.register.ObligationStatus
import io.astrolabe.register.Register
import io.astrolabe.register.RegisterRender
import io.astrolabe.register.Step
import io.astrolabe.register.ValidationContext
import io.astrolabe.tool.Args
import io.astrolabe.tool.Disposition
import io.astrolabe.tool.Dispatcher
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.Partition
import io.astrolabe.tool.SchemaSelection
import io.astrolabe.tool.ToolCall
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolExecutor
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.ToolSchemas
import io.astrolabe.tool.TurnResult
import io.astrolabe.tool.run.announceMoved
import io.astrolabe.tool.kb.KbTool
import io.astrolabe.tool.state.BlockedRequest
import io.astrolabe.verify.Applicability
import io.astrolabe.verify.Blast
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.CheckLine
import io.astrolabe.verify.CheckState
import io.astrolabe.verify.ChecksRender
import io.astrolabe.verify.Currency
import io.astrolabe.verify.Layer
import io.astrolabe.verify.Layers
import io.astrolabe.verify.ObligationResult
import io.astrolabe.verify.RefactorMode
import io.astrolabe.verify.Resolver
import io.astrolabe.verify.Selector
import io.astrolabe.verify.TestIntegrity
import io.astrolabe.verify.TestIntegrityFlag
import io.astrolabe.workset.StaleDrop
import io.astrolabe.workspace.ChangeListener
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.PathPattern
import io.astrolabe.workspace.Preimage
import io.astrolabe.workspace.Ranges
import io.astrolabe.workspace.StampReport
import io.astrolabe.workspace.Stamper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import io.astrolabe.provider.ToolCall as NativeCall

/**
 * The cell turn loop (§3.7 `cell()` with the F03 corrections; §5.5 transactional turns). It wires the
 * completed components — `Layout`, `Anchor`, `Gauges`, `Gates`, `Residency`, `Partition`, `Dispatcher`,
 * the check registry and checker, the coherence horizons, the budget — into one run and adds no policy of
 * its own beyond the order the specification fixes:
 *
 * 1. dispatch authority and the turn/token reservation, before anything is spent;
 * 2. `[A]` rebuilt, `[S][R][K][T]` rendered, `validate` and admission, then `complete`;
 * 3. the native output journaled **before** any result exists; native items appended with the assistant's
 *    calls preceding their results (FX-21);
 * 4. calls validated as a whole — a schema error, a forward dependency, a missing required `state` op or an
 *    edit on a reserve turn refuses the **whole** turn, never a subset (fail closed);
 * 5. partition and dispatch; every result carries the gauge;
 * 6. the workspace reconciled (stamp diff announced, checks refreshed, the scheduled checker drained, the
 *    atlas refreshed) and a checkpoint persisted — on every path out of a turn, including the failing ones;
 * 7. gates on records, terminal requests honoured, eviction on the `k` cadence, pressure ⇒ rebuild, then `partial`.
 *
 * A cell never summarises: the first pressure rebuilds its projection (§5.8), a second one and turn exhaustion end it `partial` with a hint
 * for the controller. A no-call turn is a completion proposal assessed by the role's [RoleCompletion]
 * (resolved by [RoleCompletion.forRole] unless one is given); refused proposals are recorded as gaps. Every
 * exit carries the [ResultPacket] the loop collected from records (§5.9), never from the model's text.
 */
public class Cell @JvmOverloads constructor(
    private val clock: Clock,
    private val idGen: IdGen,
    private val defaults: Defaults = Defaults(),
    private val gates: Gates = Gates.s0(),
    private val events: Events? = null,
    /** `null`: [RoleCompletion.forRole] for the context's role. */
    private val completion: RoleCompletion? = null,
    private val authority: DispatchAuthority = DispatchAuthority.NONE,
) {
    /** Runs one cell over [increment] under [budget] until an exit; the exit's checkpoint is persisted before it returns. */
    public suspend fun run(ctx: CellContext, increment: Increment, budget: CellBudget): CellExit = Loop(ctx, increment, budget).run()

    // ------------------------------------------------------------------ loop

    /** One decided exit: the status to persist and the exit record built over the final checkpoint and packet. */
    private class Exit(
        val status: CellStatus,
        val reason: String?,
        val blocked: BlockedRequest? = null,
        val evidenceRefs: List<String> = emptyList(),
        val make: (Int, Register, CellCheckpoint, ResultPacket) -> CellExit,
    )

    /**
     * The turn after validation (D-372): [calls] dispatch under their op ids, the position in the model's output; every
     * other call of the turn has its result text in [notExecuted], by index. [refused] feeds the refusal loop (D-358).
     */
    private class Validated(val calls: List<ToolCall>, val notExecuted: Map<Int, String>, val refused: List<Refusal>)

    /** A refused [call]: [reason] as the model reads it, [key] the same without per-turn call ids. */
    private class Refusal(val call: NativeCall, val reason: String, val key: String)

    private inner class Loop(private val ctx: CellContext, private val increment: Increment, private val budget: CellBudget) {
        private val ids = ctx.ids
        private val cell = ids.context!!
        private val tools = ctx.tools
        private val ws = ctx.workspace
        private val ev = ctx.evidence
        private val estimator = ctx.model.estimator
        private val contextAdmission = ctx.admission ?: ContextAdmission()
        private val capabilities = ctx.model.adapter.capabilities(ctx.model.profile)
        private val residency = Residency.of(defaults, estimator)
        private val record = TurnRecord()
        private val dispatcher = Dispatcher(executors(), ws.workset, ids, events, ctx.turnCheckpoint)
        private val subscriptions = ArrayList<AutoCloseable>()
        private val completion = this@Cell.completion ?: RoleCompletion.forRole(ctx.role)

        private var turn = 0
        private var residents: List<Resident> = emptyList()
        private var sections = ctx.sections
        private var rebuildGap: String? = null
        private var fired: Set<GateKey> = emptySet()
        private val impact = ImpactNudges()
        private val signatures = ArrayList<CallSignature>()
        /** F1a: calls refused since a call last executed, and each signature's first turn and reason. */
        private val refused = ArrayList<RefusalSignature>()
        private val refusedFirst = HashMap<RefusalSignature, Pair<Int, String>>()
        /** The contract version the refusal history was collected under: an amendment may change masks and ceilings (D-358). */
        private var refusedUnder = -1
        private var lastProgressTurn = 0
        /** D-366: signatures of every finished `run`/`verify` of the cell; a repeat with the same result is not new information. */
        private val seenResults = HashSet<CallSignature>()
        private var requiredOp: String? = null
        private var refusals = 0
        private val touched = LinkedHashSet<String>()
        /** D-366: paths named by edits whose dispatch failed (effects unknown); ownership needs the ledger to show them moved. */
        private val failedEditPaths = LinkedHashSet<String>()
        private val touchedLedger = ArrayList<Touched>()
        private val flags = LinkedHashMap<String, TestIntegrityFlag>()
        private var drops: List<StaleDrop> = emptyList()
        private var nudges: List<String> = emptyList()
        private var lastReport: StampReport? = null
        private var reconciledTurn = 0
        private var occupancy: Occupancy? = null
        private var atlas = ws.atlas
        /** D-366: the files the atlas listed at cell start; with the base stamp's members, what existed before the cell. */
        private val baseFiles: Set<String> = ws.atlas.rows.mapTo(HashSet()) { it.path }
        private var rebuilds = 0
        private val rebuildNotes = ArrayList<String>()

        /** Reviewer rejections pinned verbatim for the rest of the cell (D-341). */
        private val reviewNotes = LinkedHashSet<String>()
        /** The previous turn's edits: what the §6.3 focus notes anchor on beside the register's focus. */
        private var editedThisTurn: Set<String> = emptySet()

        // The packet's runtime-owned fields, collected as the cell runs (§5.9).
        private var base: StampReport? = null
        private var contractVersion = 0

        /** §8.9 `red_ok_until: increment_end`: while true, a red check is not required in `Open` before `[>]` advances. */
        private var redOkUntilIncrementEnd = false
        private val shownAliases = LinkedHashSet<String>()
        private val readVersions = LinkedHashMap<String, FileVersion>()
        private val displayed = LinkedHashMap<Pair<String, FileVersion>, Ranges>()
        private val origins = LinkedHashMap<String, ChangeOrigin>()
        private val gaps = ArrayList<String>()

        /** Red check signatures of the previous turn, and how many repairs (turns with edits) each has survived (§5.6). */
        private var redSeen: Set<String> = emptySet()
        private val repairs = HashMap<String, Int>()

        /** `not_tested` (§8.2): what a layer could not test, and the required checks without a certifying receipt. */
        private val notTested = LinkedHashSet<String>()
        private var uncertified: List<String> = emptyList()
        private var cost = PacketCost()

        private val register: Register get() = tools.state.register

        suspend fun run(): CellExit {
            tools.edit?.beforeDispatch = ::enforceAuthority
            (tools.run as? io.astrolabe.tool.run.Run)?.beforeDispatch = ::enforceAuthority
            tools.verify?.beforeDispatch = ::enforceAuthority
            tools.task?.beforeDispatch = ::enforceAuthority
            ws.checker?.beforeDispatch = ::enforceAuthority
            events?.emit(AgentEvent.Cell.Started(ids, increment.id, ctx.role.name))
            // §4.4: the horizons hear every transition in this order — turn, cell, verification — then the Touched ledger.
            subscriptions += ws.coherence.register(ws.workset)
            subscriptions += ws.coherence.register(ChangeListener { tools.state.markStale(it) })
            subscriptions += ws.coherence.register(ws.checks)
            ctx.noteHorizon?.let { subscriptions += ws.coherence.register(it) }
            subscriptions += ws.coherence.register(ChangeListener { touchedLedger += Touched.of(it) })
            tools.edit?.increment = increment
            tools.verify?.inputs = atlas.rows.map { it.path }
            tools.verify?.atlas = atlas
            try {
                lastReport = ws.stamper.report()
                base = lastReport
                observeWorkset()
                while (true) {
                    val exit = turn() ?: continue
                    return finish(exit)
                }
            } catch (cancelled: CancellationException) {
                // Invariant 11: the interruption is recorded as its own outcome before the cancellation propagates.
                withContext(NonCancellable) { settle(CellStatus.Cancelled, "cancelled: ${cancelled.message ?: "coroutine cancelled"}") }
                throw cancelled
            } catch (failure: Exception) {
                val error = "${failure::class.simpleName}: ${failure.message}${site(failure)}"
                val checkpoint = settle(CellStatus.Failed, error)
                return CellExit.Failed(budget.turnsTaken, register, checkpoint, persistPacket(packet(PacketStatus.Failed, error)), error)
            } finally {
                tools.edit?.beforeDispatch = {}
                (tools.run as? io.astrolabe.tool.run.Run)?.beforeDispatch = {}
                tools.verify?.beforeDispatch = {}
                tools.task?.beforeDispatch = {}
                ws.checker?.beforeDispatch = {}
                subscriptions.forEach { it.close() }
            }
        }

        /** One turn; `null` means the loop continues. Every early return persists its checkpoint in [finish]. */
        private suspend fun turn(): Exit? {
            turn += 1
            events?.emit(AgentEvent.Cell.TurnStarted(ids, turn, budget.turns))

            // §3.7 enforce_dispatch_authority_and_budget: nothing below runs without the authority and a turn.
            authority.check(turn)?.let { return if (it.cancelled) cancelled(it.reason) else failed(it.reason) }
            (Executors.require(ctx.config.executionMode) as? ExecutionDecision.Refused)?.let { return failed("dispatch refused: ${it.refusal}") }
            val reserveTurn = when (val generation = budget.startTurn(Spend.Generation)) {
                is Admission.Admitted -> false
                is Admission.Refused -> {
                    if (budget.turnsLeft <= 0) return partial(PartialReason.TurnBudget, generation.reason)
                    // §5.9 reserve gate: the turns held back are for verifying and reporting, never for new edits.
                    when (val verify = budget.startTurn(Spend.Check)) {
                        is Admission.Admitted -> true
                        is Admission.Refused -> return partial(PartialReason.Reserve, verify.reason)
                    }
                }
            }

            rebuildGap?.let { return partial(PartialReason.Pressure, it) }
            // Render: [A] first (rebuilt every turn), then the cached regions, then admission.
            val contract = contract()
            if (contract.version != refusedUnder) { refused.clear(); refusedFirst.clear(); refusedUnder = contract.version }
            ws.checks.synchronizeAcceptance(contract)
            contractVersion = contract.version
            val refactor = RefactorMode.detect(contract)
            if (refactor.active && !redOkUntilIncrementEnd) {
                // §8.9 item 2: inline syntax, the checker and the step-boundary layer still run; only the red-not-recorded
                // gate waits for the increment end, where the exit gate refuses a red completion as ever.
                ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Boundary, text = "refactor mode (${refactor.reasons.first()}): red_ok_until ${RefactorMode.RED_OK_UNTIL}", at = clock.instant()))
            }
            redOkUntilIncrementEnd = refactor.active
            // D-366: a reserve reached by the turn count, with working tokens left, still lets the cell repair what it changed.
            val repairable = if (reserveTurn && budget.working.available.value > 0) ownPaths() else emptySet()
            val mask = maskFor(contract, reserveTurn, repairable.isNotEmpty())
            val schemas = when (val selection = ToolSchemas.forLineage(ctx.model.adapter, ctx.model.profile, mask)) {
                is SchemaSelection.Supported -> selection.set
                is SchemaSelection.Unsupported -> return failed("tool schemas unsupported for ${selection.profileId}: ${selection.reason}")
            }
            val anchor = try {
                renderAnchor(contract)
            } catch (capacity: DigestCapacity) {
                return partial(PartialReason.Pressure, "replan: ${capacity.message}")
            }
            val layout = Layout.render(ctx.role, mask, ctx.config.executionMode, ctx.prime, CompiledK(ContractSlice.forIncrement(contract, increment), ctx.preexisting, sections), transcript(contract), capabilities.caching.breakpoints)
            val request = Request(layout + anchor.segment(), schemas.schemas, ctx.model.profile, ctx.model.effort, ctx.model.maxOutputTokens, mask)
            val estimate = estimator.estimate(request)
            when (val validation = ctx.model.adapter.validate(request, estimate)) {
                Validation.Ok -> Unit
                is Validation.Rejected -> {
                    val problems = validation.problems.joinToString("; ") { "${it.kind}: ${it.detail}" }
                    if (validation.problems.any { it.kind == ProblemKind.ContextOverflow }) contextAdmission.rejected(estimate)
                    // next_request_exceeds_usable_context: a P1 cell checkpoints and stops rather than rebuilds.
                    if (validation.problems.any { it.kind == ProblemKind.ContextOverflow }) {
                        if (rebuilds >= 1) return partial(PartialReason.Pressure, "replan: the next request does not fit the window after a rebuild ($problems) — split the increment")
                        rebuild("the next request does not fit the window ($problems)")
                        return null
                    }
                    return failed("request refused by ${ctx.model.adapter.id}: $problems")
                }
            }
            // §6.1: the hard admission check — never an oversize request, never a fit claimed on unknown history.
            when (val decision = contextAdmission.check(request, estimate)) {
                is AdmissionDecision.Admitted -> Unit
                is AdmissionDecision.Capacity -> {
                    if (decision.condition != CapacityCondition.OverWindow || rebuilds >= 1) {
                        return partial(PartialReason.Pressure, "replan: capacity ${decision.condition.name.lowercase()} — ${decision.detail}")
                    }
                    rebuild("capacity: ${decision.detail}")
                    return null
                }
            }
            val spend = if (reserveTurn) Spend.Check else Spend.Generation
            val admission = when (val admitted = budget.admit(spend, Tokens(estimate.upperBoundTokens + ctx.model.maxOutputTokens))) {
                is Admission.Admitted -> admitted
                is Admission.Refused -> return partial(if (reserveTurn) PartialReason.Reserve else PartialReason.TokenBudget, admitted.reason)
            }

            // Complete.
            val invocationId = InvocationId(idGen.next("inv"))
            events?.emit(AgentEvent.Cell.ModelRequested(ids, invocationId.value, estimate.tokens, ctx.model.profile.id, anchorTokens = anchor.tokens))
            val accounting = ctx.accounting
            if (accounting != null && !accounting.reserve(ids, invocationId.value, ctx.model.profile, admission.estimate.value,
                    Accounting.estimateCost(ctx.model.profile, estimate.upperBoundTokens, ctx.model.maxOutputTokens.toLong()),
                    if (spend == Spend.Generation) (contract.budget.tokens.value * (1.0 - contract.budget.reserves.verification - contract.budget.reserves.recoveryAndPersist)).toLong() else contract.budget.tokens.value,
                    contract.budget.cost)) {
                admission.release()
                return partial(PartialReason.TokenBudget, "campaign funding exhausted before provider dispatch")
            }
            var invocation: io.astrolabe.provider.Invocation? = null
            var received: Response? = null
            var failure: Throwable? = null
            var terminal: io.astrolabe.provider.Terminal? = null
            var progress: AutoCloseable? = null
            try {
                progress = progressRelay(invocationId)
                invocation = ctx.model.adapter.start(request, invocationId)
                received = invocation.await()
            } catch (error: Throwable) {
                failure = error
                invocation?.cancel()
            } finally {
                var terminalTimedOut = false
                terminal = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    // D-314: a provider that never settles must not hang the cell; unknown usage keeps conservative funding.
                    try {
                        invocation?.let { inv ->
                            kotlinx.coroutines.withTimeoutOrNull(defaults.providerTerminalWaitSeconds * 1_000L) { inv.terminal() }
                                ?: null.also { terminalTimedOut = true; inv.cancel() }
                        }
                    } catch (_: Exception) { null }
                }
                if (terminalTimedOut) {
                    ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Call,
                        text = "provider terminal not reconciled within ${defaults.providerTerminalWaitSeconds}s; usage unknown, conservative funding retained",
                        at = clock.instant()))
                }
                runCatching { progress?.close() }
                val settled = terminal
                val usage = if (terminalTimedOut) null else settled?.usage ?: received?.usage
                val knownInput = usage?.quantities?.filterKeys { it.isInput }?.values?.fold(0L, Accounting::add) ?: 0L
                val inputKnown = usage != null && usage.quantities.keys.any { it.isInput } && usage.unknown.none { it.isInput }
                val inputCharge = if (inputKnown) knownInput else maxOf(knownInput, estimate.upperBoundTokens)
                val outputCharge = usage?.quantities?.get(BillingDimension.OUTPUT) ?: ctx.model.maxOutputTokens.toLong()
                val charge = Accounting.add(inputCharge, outputCharge)
                val complete = usage?.isComplete == true && inputKnown && BillingDimension.OUTPUT in usage.quantities
                val funded = if (complete) charge else maxOf(charge, admission.estimate.value)
                admission.reconcile(Tokens(funded))
                cost += usage
                accounting?.record(ids, invocationId.value, ctx.model.profile, request, usage, funded)
                if (settled != null && (settled.lateItems.isNotEmpty() || failure != null)) {
                    val late = settled.lateItems + if (failure != null) settled.response?.items.orEmpty() else emptyList()
                    ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Call,
                        text = "late terminal evidence archived without dispatch: " + late.joinToString { it.toString() },
                        payload = JSON.encodeToJsonElement(ITEMS, late), at = clock.instant()))
                }
            }
            failure?.let { error ->
                when (error) {
                    // I-17, D-331: the provider's own count refused the request; an estimation miss rebuilds like a validation overflow.
                    is ProviderError.ContextOverflow -> {
                        contextAdmission.rejected(estimate)
                        if (rebuilds >= 1) return partial(PartialReason.Pressure, "replan: the provider refused the request for size after a rebuild (${error.message}) — split the increment")
                        rebuild("the provider refused the request for size (${error.message})")
                        return null
                    }
                    // D-331: credentials are the host's to fix; retrying or failing the increment would not help.
                    is ProviderError.Authentication -> return blocked(BlockedRequest("provider authentication failed for profile ${ctx.model.profile.id}: ${error.message}",
                        listOf("invocation ${invocationId.value}"), null, turn))
                    is ProviderError -> return failed("provider ${error::class.simpleName}: ${error.message}")
                    else -> throw error
                }
            }
            val response = checkNotNull(received)
            val usage = terminal?.usage ?: response.usage
            if (terminal?.cancelled == true) return cancelled("provider terminal reconciliation confirmed cancellation")
            contextAdmission.observed(estimate, usage?.takeIf { it.isComplete }?.totalInput)
            events?.emit(AgentEvent.Cell.ModelResponded(ids, invocationId.value, response.stop, usage))
            if (response.toolCalls.isNotEmpty()) authority.check(turn)?.let { return if (it.cancelled) cancelled(it.reason) else failed(it.reason) }

            // §3.7: the native output is durable before any result exists, then appended (calls before results).
            journalOutput(response)
            appendNative(response)
            when (response.stop) {
                StopReason.Cancelled -> return cancelled("the provider cancelled the invocation")
                StopReason.Refusal -> return failed("the model refused: ${response.text.lineSequence().firstOrNull().orEmpty()}")
                else -> Unit
            }
            val before = lastReport ?: ws.stamper.report()
            val registerBefore = register
            val certifiedBefore = certified(currencies(before.candidateId))
            val native = response.toolCalls
            val validated = if (native.isEmpty()) null else validateCalls(native, reserveTurn, mask, contract, repairable)
            val calls = validated?.calls.orEmpty()

            // Partition and dispatch what validation left — nothing when the whole turn is refused.
            record.reset()
            val dispatchedAt = clock.millis()
            val result = if (calls.isNotEmpty()) dispatcher.dispatch(turn, calls, Tokens(defaults.rMaxTokens.toLong())) else null
            cost = cost.plusToolSeconds((clock.millis() - dispatchedAt) / MILLIS_PER_SECOND)
            val after = reconcile("turn $turn")
            val gauge = gauge(currencies(after.candidateId))
            var liveRunOutput = false
            val worked = ArrayList<Pair<ToolCall, ToolOutcome>>()
            val editedPaths = LinkedHashSet<String>()
            // §7.4 impact nudge: each edited file's bytes before this turn's batch, and the refs looks emitted after its last edit.
            val batchBefore = LinkedHashMap<String, ByteArray>()
            val inspected = ArrayList<String>()
            fun notExecuted(call: NativeCall, reason: String) {
                appendResult(call.id, "${Boundary.RESULT_OPEN}not executed: $reason${Boundary.RESULT_CLOSE}\n${gauge.line()}", isError = true, label = call.name, resultClass = ResultClass.Verdict, alias = null)
                journalResult(call.id, reason, emptyList())
            }
            fun executed(call: ToolCall) {
                val disposition = result!!.of(call.opId)
                val outcome = (disposition as? Disposition.Executed)?.outcome
                val text = when (disposition) {
                    is Disposition.Executed -> Gauges.result(
                        if (call.notes.isEmpty()) disposition.outcome else disposition.outcome.copy(body = call.notes.joinToString("") { "note: $it\n" } + disposition.outcome.body),
                        gauge,
                    )
                    is Disposition.NotExecuted -> "${Boundary.RESULT_OPEN}not executed: ${disposition.reason}${Boundary.RESULT_CLOSE}\n${gauge.line()}"
                    is Disposition.Failed -> "${Boundary.RESULT_OPEN}failed: ${disposition.error} — effects unknown; reconciled at the turn boundary${Boundary.RESULT_CLOSE}\n${gauge.line()}"
                }
                if (disposition is Disposition.Failed && call.family == ToolFamily.Edit) failedEditPaths += editPaths(call).orEmpty()
                val alias = outcome?.resultAlias?.takeIf { it != NO_ALIAS }
                val refs = listOfNotNull(alias) + outcome?.header?.runtime?.artifactRefs.orEmpty()
                appendResult(call.providerCallId, text, isError = outcome == null, label = label(call, outcome), resultClass = ResultClass.of(call.name), alias = alias)
                journalResult(call.providerCallId, outcome?.header?.line() ?: text.lineSequence().first(), refs)
                if (outcome == null) return
                worked += call to outcome
                // D-358: a `run` the executor denied by policy (read-only role, ceiling, execution mode, D-class without intent)
                // is a refusal the same call cannot get past: it joins the refusal loop, not the ordinary loop, so the third
                // identical denial ends the cell blocked instead of nudging twice per turn until the budget is spent.
                if (call.family == ToolFamily.Run && outcome.header?.runtime?.status == "denied") {
                    val reason = outcome.body.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith(Boundary.RESULT_OPEN) } ?: outcome.body.lineSequence().first()
                    val signature = RefusalSignature.of(call.name, call.raw.toString(), reason)
                    refused += signature
                    refusedFirst.putIfAbsent(signature, turn to reason)
                } else signatures += CallSignature.of(call, outcome)
                if (call.family == ToolFamily.Run && outcome.header?.runtime?.status == "running") liveRunOutput = true
                val mutated = mutatedPaths(call, outcome)
                if (mutated.isNotEmpty()) {
                    editedPaths += mutated
                    val origin = if (call.family == ToolFamily.Edit) ChangeOrigin.Edit else ChangeOrigin.Run
                    mutated.forEach { path -> origins.merge(path, origin) { old, new -> if (old == ChangeOrigin.External) new else old } }
                    // §8.6 run-side collection: every mutation, by edit or by run, is checked against the acceptance surface.
                    // An edit's own classified flags (P3.4.2) carry its `why`; a later pure addition never clears an earlier weakening.
                    val classified = if (call.family == ToolFamily.Edit && alias != null) tools.edit?.flagsOf(alias).orEmpty() else emptyList()
                    classified.forEach { flag -> flags.merge(flag.path, flag) { old, new -> if (old.blocksCompletion && !new.blocksCompletion) old else new } }
                    TestIntegrity.baseline(mutated - classified.map { it.path }.toSet(), "${call.family.wire} ${alias ?: "op ${call.opId}"}", contract, ws.checks).forEach { flags.putIfAbsent(it.path, it) }
                }
                if (call.family == ToolFamily.Edit && alias != null) {
                    journalPreimages(alias)
                    inspected.clear()
                    preimagesOf(alias).forEach { p -> batchBefore.getOrPut(p.path) { ev.preimages!!.bytesOf(p) } }
                }
                // D-92: a refs view clears obligations only when every reference found was displayed unredacted.
                if (call.family == ToolFamily.Look && outcome.header?.runtime?.status == "ok" &&
                    outcome.header.runtime.captureComplete && !outcome.header.truncated &&
                    !outcome.header.runtime.displayTruncated && !outcome.header.runtime.redactionApplied) {
                    (call.args as? Args.Look)?.args?.takeIf { it.what == "refs" }?.target?.let { impact.inspected(it); inspected += it }
                }
            }
            if (validated != null) {
                val dispatched = calls.associateBy { it.opId - 1 }
                native.forEachIndexed { index, call -> dispatched[index]?.let(::executed) ?: notExecuted(call, validated.notExecuted.getValue(index)) }
                // D-358: a refusal joins the cell's refusal history, kept until the contract version changes (masks and ceilings may move).
                for (refusal in validated.refused) {
                    val signature = RefusalSignature.of((parseOne(refusal.call, 1) as? ParsedCalls.Valid)?.calls?.single()?.name ?: refusal.call.name, refusal.call.argsJson, refusal.key)
                    refused += signature
                    refusedFirst.putIfAbsent(signature, turn to refusal.reason)
                }
            }
            editedPaths.addAll(movedThisTurn(before, after, contract))
            editedThisTurn = editedPaths.toSet()
            tools.state.fireTrips(editedPaths)
            ctx.knowledge?.cited(register)
            dropUnseenCoverage(calls, result)
            observeWorkset()

            // End-of-turn checker on the paths the horizons scheduled; the atlas follows the same set.
            val proposal = native.isEmpty() && (response.stop == StopReason.EndTurn || response.stop == StopReason.ToolUse)
            val dispatchRefusal = authority.check(turn)
            if (dispatchRefusal != null && !proposal) return if (dispatchRefusal.cancelled) cancelled(dispatchRefusal.reason) else failed(dispatchRefusal.reason)
            // Late completion may be archived from existing evidence; revoked authority never launches another check.
            val turnEnd = (if (dispatchRefusal == null) drainScheduled(contract) else null) ?: after
            if (batchBefore.isNotEmpty()) {
                val index = SymbolIndex(atlas)
                val changes = batchBefore.flatMap { (path, bytes) -> DefinitionChanges.of(path, bytes, ws.registry.read(path)?.bytes) }
                // D-366: a file absent at the cell's base is the cell's own; it is exempt only until a file the cell
                // did not create imports it (an extracted module with a real caller is impact like any other).
                val own = { path: String -> path !in baseFiles && base?.members?.containsKey(path) != true }
                val created = batchBefore.keys.filter { path -> own(path) && atlas.importers(path).all(own) }.toSet()
                // Fan-in outside the edited file: tier-0 lexical refs, which is also the no-index literal fallback (§7.4).
                impact.changed(turn, changes, created) { c -> index.refs(c.symbol).references.count { it.path != c.path } }
            }
            inspected.forEach(impact::inspected)
            impact.rescoped(registerBefore, register)
            // §6.6: the controller may pre-build the next [K] while verify-on-stop runs, if only slow checks remain.
            if (proposal && dispatchRefusal == null) ctx.precompile?.completionProposed(turnEnd.candidateId, remainingAcceptance(turnEnd.candidateId))
            // §8.1 layer table: a `[>]` move runs blast ∪ the left step's accept:, a completion proposal verify-on-stop;
            // each runs only missing or stale checks, reused receipts stand.
            val stepRun = if (dispatchRefusal == null) stepLeft(registerBefore, register)?.let { step -> tools.verify?.runLayer(Layer.BlastAndStepAccept, listOfNotNull(step.accept)) } else null
            // §7.4: an edit batch whose impact risk exceeds θ runs the blast layer now (P4.5.2, D-152).
            val riskRun = if (dispatchRefusal == null && stepRun == null && batchBefore.isNotEmpty()) tools.verify?.riskAboveTheta(EditHunks.of(batchBefore) { ws.registry.read(it)?.bytes }) else null
            val implementingCompletion = ctx.role.packetKind == PacketKind.Result
            val layerRuns = listOfNotNull(stepRun, riskRun, if (proposal && implementingCompletion && dispatchRefusal == null) tools.verify?.onStop(increment.accept) else null)
            for (layerRun in layerRuns) {
                notTested += layerRun.notTested
                val what = if (layerRun.layer == Layer.IncrementAcceptance) "verify-on-stop" else if (layerRun === riskRun) "risk > θ" else "step boundary"
                for (receipt in layerRun.receipts) {
                    ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Check, refs = listOf(receipt.receiptId), text = "$what ${receipt.checkId}: ${receipt.outcome.name.lowercase()}", at = clock.instant()))
                }
            }
            val stampNow = if (layerRuns.all { it.receipts.isEmpty() }) turnEnd else ws.stamper.report()
            lastReport = stampNow
            ws.scheduler.refresh(stampNow.candidateId, stampNow.env)
            worksetDrops()
            residency.due(residents, turn)?.let { trigger -> evict(trigger) }
            val current = residency.occupancy(prefix(layout), residents, turn, anchor.tokens, pinnedTokens(contract))
            occupancy = current

            // Gates on records. D-278: only a patch that materially changes the register acknowledges prior loop
            // signatures; a repeated identical `next` bumps only the version and must not reset the loop gate.
            if (calls.any { it.family == ToolFamily.State && it.op == "patch" &&
                    (result?.of(it.opId) as? Disposition.Executed)?.outcome?.applied == true
                } && register.copy(version = registerBefore.version) != registerBefore) signatures.clear()
            val currenciesNow = currencies(stampNow.candidateId)
            uncertified = outstanding(currenciesNow)
            val certifiedAfter = certified(currenciesNow)
            val work = Progress.work(turn, worked, seenResults)
            worked.filter { (call, outcome) -> (call.family == ToolFamily.Run || call.family == ToolFamily.Verify) && Progress.finished(outcome) }
                .forEach { (call, outcome) -> seenResults += CallSignature.of(call, outcome) }
            if (work.isNotEmpty() || Progress.events(registerBefore, register, turn, certifiedBefore, certifiedAfter, tools.state.unbackedTicks).isNotEmpty()) lastProgressTurn = turn
            val completionEvidence = if (proposal && implementingCompletion && dispatchRefusal == null)
                ctx.completionEvidence?.invoke(flags.values.toList()) else null
            completionEvidence?.flags?.forEach { flag -> flags[flag.path] = flag }
            val evidenceStamp = ws.stamper.report().candidateId
            val validEvidence = completionEvidence?.takeIf { evidenceStamp == stampNow.candidateId && contract().version == contract.version }
            if (validEvidence == null) flags.replaceAll { _, flag -> flag.copy(verdict = null) }
            val gateFlags = flags.values.filter { it.kind != TestIntegrity.ADDITIONS_ONLY }
            // D-337: the proposal resolved once, by the rule the controller's verifier applies to the same records.
            val acceptance = if (proposal && implementingCompletion) Resolver.increment(
                register, contract, increment, currenciesNow, validEvidence?.verdicts.orEmpty(), validEvidence?.unavailable.orEmpty(), gateFlags,
                impact.unresolvedPublic.map { it.missing }, stampNow.candidateId,
                validEvidence?.decision?.takeIf { it.appliesTo(stampNow.candidateId, contract.version) && it.incrementId == increment.id },
                reworkSpent = validEvidence?.reworkSpent == true,
            ) else null
            val repeated = repeatedFailures(currenciesNow, repaired = calls.any { it.family == ToolFamily.Edit })
            val editedByEdit = editedPaths.filter { origins[it] == ChangeOrigin.Edit }
            val state = GateState(
                turn = turn, register = register, contract = contract, increment = increment, calls = calls, signatures = signatures.toList(),
                patchRejection = if (calls.any { it.family == ToolFamily.State && it.op == "patch" }) tools.state.lastRejection else null,
                lastProgressTurn = lastProgressTurn, liveRunOutput = liveRunOutput,
                contextTokens = current.totalTokens, contextMaxTokens = capabilities.contextLimitTokens.toLong(), rebuilds = rebuilds,
                reserve = budget.verdict(outstanding(currenciesNow)), turnsMax = budget.turns, completionProposed = proposal,
                currencies = currenciesNow, verdicts = validEvidence?.verdicts.orEmpty(), unavailable = validEvidence?.unavailable.orEmpty(), flags = gateFlags, fired = fired, defaults = defaults,
                impactNudges = impact.unresolved, unresolvedImpactNudges = impact.unresolvedPublic.map { it.missing },
                impactOverflow = if (batchBefore.isNotEmpty()) impact.overflow else emptyList(),
                outsideIncrement = editedByEdit.filter { p -> contract.scope.covers(p) && increment.writeScope.none { PathPattern.matches(it, p) } },
                surfaceFlags = flags.values.filter { it.path in editedPaths }, editedPaths = editedPaths.toSet(),
                contractAnchors = (tools.kb as? KbTool)?.contractAnchors().orEmpty(), repeatedFailures = repeated, acceptance = acceptance,
                refusals = refused.toList(),
            )
            val report = gates.evaluate(state)
            fired = report.fired
            report.outcomes.forEach { events?.emit(AgentEvent.Cell.GateFired(ids, it.key.gate, it.line)) }
            report.rejections.firstOrNull { it.endsTurn }?.let { requiredOp = it.requiredOp }
            // §5.1: at most MAX_NUDGES lines, the hard gates' refusals first; D-372: the stall and budget lines before the
            // other nudges, the impact lines last, so a turn of impact nudges never hides how much of the cell is left.
            val (impactLines, softer) = report.nudges.partition { it.key.gate == Gates.IMPACT }
            val (budgetLines, otherLines) = softer.partition { it.key.gate in PRIORITY_NUDGES }
            nudges = (report.rejections.map { it.line + it.details.take(DETAILS_IN_LINE).joinToString("") { d -> " · $d" } } +
                (budgetLines + otherLines).map { it.line } + truncationLine(response) + impactLines.map { it.line }).take(MAX_NUDGES)

            // Checkpoint: the turn's boundary is durable before any exit is decided.
            persist(checkpoint(CellStatus.Running, stampNow.candidateId, null))

            // F1a: a refusal loop ends the cell with the refusal's own reason instead of spending the remaining turns.
            if (report.rejections.any { it.key.gate == Gates.REFUSAL_LOOP }) refusalLoop()?.let { return blocked(it) }
            // Terminal requests, the completion path, pressure.
            (tools.state.pendingBlock ?: tools.task?.pendingBlock)?.let { return blocked(it) }
            // D-344: an answer to a request that needed no change ends the cell before any acceptance is attempted.
            tools.task?.takeAnswer()?.let { return answered(it) }
            if (proposal) {
                // D-338: an unavailable runner is an unverified result like any other (FX-13 no longer blocks).
                val output = RoleOutput(turn, response.text, register, certifiedAfter.mapNotNull { currenciesNow.certifiedReceipt(it) }, refusals, packet(PacketStatus.Done, null), shownAliases.toSet(), acceptance)
                when (val decision = completion.assess(output, report)) {
                    is CompletionDecision.Accepted -> return completed(response.text, decision.evidenceRefs)
                    is CompletionDecision.Defer -> {
                        // D-339: the work is done; its acceptance is decided outside the cell, so no further turn is spent.
                        recordGaps(decision.gaps, "completion awaits ${decision.code.wire} at turn $turn")
                        return completed(response.text, acceptance?.evidenceRefs.orEmpty(), PendingAcceptance(decision.code, decision.gaps))
                    }
                    is CompletionDecision.CannotProgress -> {
                        recordGaps(decision.gaps)
                        return partial(PartialReason.CompletionStalled, "gaps this cell cannot close: ${decision.gaps.joinToString("; ")}")
                    }
                    is CompletionDecision.Continue -> {
                        recordGaps(decision.gaps)
                        // D-341: a reviewer's findings reach this cell whole, pinned with their author, never cut into a nudge line.
                        acceptance?.rejections?.forEach(::pinReview)
                        val gapLines = decision.gaps.take(MAX_NUDGES).map {
                            "packet validation: ${Boundary.escape(it.replace('\r', ' ').replace('\n', ' ')).take(240)}"
                        }
                        // §5.1 order: this turn's hard rejections stay first; the gaps precede the softer nudges.
                        val rejected = nudges.take(report.rejections.size)
                        nudges = (rejected + gapLines + nudges.drop(rejected.size)).take(MAX_NUDGES)
                        refusals += 1
                    }
                }
            }
            report.nudges.firstOrNull { it.key.gate == Gates.PRESSURE }?.let {
                // §5.8: the first pressure rebuilds the projection; a second means the increment was mis-sized.
                if (rebuilds >= 1) return partial(PartialReason.Pressure, "replan: ${it.line}; a second pressure in one cell — split the increment")
                rebuild(it.line)
            }
            return null
        }

        // ----------------------------------------------------------- render

        private fun renderAnchor(contract: Contract): AnchorRender {
            val stampNow = lastReport?.candidateId
            val currencies = currencies(stampNow)
            val digest = ContractDigest.render(contract, ctx.ledger ?: Ledger.initial(contract), obligations(contract, currencies), estimator, defaults.effectiveDigestCapTokens(contract.requirements.size))
            val worksetLine = ws.workset.render(estimator) + drops.joinToString("") { "; ${it.text}" }
            val focusNotes = ctx.knowledge?.focusNotes(register.focus, editedThisTurn)
            return Anchor.render(
                estimator, digest, RegisterRender.markdown(register), worksetLine, touchedLedger.toList(),
                ChecksRender.render(stampNow, checkLines(currencies)), null, focusNotes, gauge(currencies).line(), nudges,
                RegisterRender.firedTrips(register) + ctx.diagnoses?.lines().orEmpty(), defaults,
            )
        }

        private fun transcript(contract: Contract): Transcript = Transcript(pinned(contract), residency.items(residents))

        /** Pinned verbatim (invariant 1): the contract's requests, the caller's messages and every question this cell asked. */
        private fun pinned(contract: Contract): List<String> =
            contract.requests.map { it.text } + ctx.pinned + tools.task?.asked.orEmpty().map { asked ->
                "question ${asked.question.id}: ${asked.question.text}" + (asked.answer?.let { "\nanswer: ${it.text}" } ?: "\nanswer: none")
            } + reviewNotes + rebuildNotes

        private fun pinnedTokens(contract: Contract): Long = pinned(contract).sumOf { estimator.estimate(it).tokens }

        private fun prefix(layout: List<Segment>): PrefixTokens {
            fun tokens(kind: SegmentKind): Long = layout.firstOrNull { it.kind == kind }?.items?.sumOf { it.estimate(estimator).tokens } ?: 0L
            return PrefixTokens(tokens(SegmentKind.S), tokens(SegmentKind.R), tokens(SegmentKind.K))
        }

        private fun gauge(currencies: Map<String, Currency>) = Gauges.of(
            occupancy?.percentOf(capabilities.contextLimitTokens) ?: 0, budget.snapshot(), checksSummary(currencies), ws.workset, register, turn, budget.turns,
        )

        /**
         * Role mask ∩ shape ∩ ceiling; a reserve turn also masks the edit family (§5.9 "no new edits") — except, on a
         * [repair] turn (D-366), the path-addressed edit ops, which [validateCalls] holds to the cell's own paths.
         */
        private fun maskFor(contract: Contract, reserveTurn: Boolean, repair: Boolean = false): ToolMask {
            val effective = ctx.role.effectiveOps(contract.shape, Ceiling.of(contract.authorization, ctx.config.executionMode, ctx.hostSets))
            if (!reserveTurn) return effective
            val edit = ToolFamily.Edit.wire + "."
            return ToolMask(effective.allowed.filterNot { it.startsWith(edit) && (!repair || it.removePrefix(edit) in NOT_PATH_ADDRESSED) }.toSet())
        }

        /** D-366: the paths this cell changed itself — by edit or run, or by an edit that failed mid-batch and moved them. */
        private fun ownPaths(): Set<String> {
            val moved = touchedLedger.map { it.path }.toSet()
            return (origins.filterValues { it != ChangeOrigin.External }.keys + failedEditPaths.filter { it in moved }).toSortedSet()
        }

        /** The workspace-relative paths [call]'s edit ops name; `null` when an op is not addressed by path (transform, revert). */
        private fun editPaths(call: ToolCall): List<String>? {
            val ops = (call.args as? Args.Edit)?.args?.ops ?: return null
            if (ops.any { it.kind in NOT_PATH_ADDRESSED }) return null
            return ops.flatMap { listOfNotNull(it.path, it.create, it.delete, it.rename, it.to) }.map { spelled ->
                (ws.workspace.paths.resolve(spelled, Intent.Read) as? PathResolution.Resolved)?.relative ?: spelled.replace('\\', '/').removePrefix("./")
            }
        }

        private fun contract(): Contract = ctx.contracts.current(ids.work) ?: throw IllegalStateException("no committed contract for ${ids.work}")

        // --------------------------------------------------------- validate

        /**
         * §3.7 `validate_complete_calls_and_dependencies`, call by call (D-372): an unparseable call, a masked op or a
         * reserve-turn edit outside the cell's own paths (D-366) refuses that call alone; a call whose condition names a
         * refused op is not executed, and a refused edit holds back the turn's runs and verifies as an edit batch that
         * did not apply does. Only a dependency violation among the valid calls or a `state` op the loop gate requires
         * refuses the whole turn — and even then one valid terminal call runs alone (F2b).
         */
        private fun validateCalls(native: List<NativeCall>, reserveTurn: Boolean, mask: ToolMask, contract: Contract, repairable: Set<String> = emptySet()): Validated {
            val alone = LinkedHashMap<Int, Pair<String, String>>()
            val valid = ArrayList<ToolCall>()
            native.forEachIndexed { index, call ->
                when (val parsed = parseOne(call, index + 1)) {
                    is ParsedCalls.Invalid -> alone[index] = "schema error in call ${parsed.providerCallId}: ${parsed.error}" to "schema error: ${parsed.error}"
                    is ParsedCalls.Valid -> {
                        val one = parsed.calls.single()
                        refusalOf(one, reserveTurn, mask, contract, repairable)?.let { alone[index] = it to it } ?: valid.add(one)
                    }
                }
            }
            val refusedEdit = alone.keys.firstOrNull { ToolFamily.byWire(native[it].name) == ToolFamily.Edit }
            val dependents = LinkedHashMap<Int, String>()
            var remaining: List<ToolCall> = valid
            while (true) {
                val held = remaining.mapNotNull { call ->
                    val target = call.condition?.let(Condition::parse)?.opId
                    when {
                        target != null && target - 1 in alone -> "depends on op $target, which was refused"
                        target != null && target - 1 in dependents -> "depends on op $target, which was not executed"
                        refusedEdit != null && (call.family == ToolFamily.Run || call.family == ToolFamily.Verify) ->
                            "the edit batch did not apply fully: op ${refusedEdit + 1} was refused; runs execute only after a fully applied batch or none"
                        else -> null
                    }?.let { call to it }
                }
                if (held.isEmpty()) break
                held.forEach { (call, reason) -> dependents[call.opId - 1] = reason }
                remaining = remaining.filter { it.opId - 1 !in dependents }
            }
            (Partition.of(remaining) as? Partition.Rejected)?.let { rejected ->
                return wholeTurn(native, remaining, alone, rejected.reason, culprits = setOf(rejected.opId - 1))
            }
            requiredOp?.let { op ->
                if (remaining.none { it.family.wire == op }) return wholeTurn(native, remaining, alone, "the loop gate ended the last turn: a $op op is required before anything else runs")
                requiredOp = null
            }
            return validated(native, remaining, alone, dependents)
        }

        /** Why [call] is refused on its own, or `null`: a reserve-turn edit that is no repair (D-366), then a masked op (D-357). */
        private fun refusalOf(call: ToolCall, reserveTurn: Boolean, mask: ToolMask, contract: Contract, repairable: Set<String>): String? {
            // D-366: on a turn-count reserve an edit whose every op targets a path the cell already changed is a repair.
            if (reserveTurn && call.family == ToolFamily.Edit && (repairable.isEmpty() || editPaths(call)?.all { it in repairable } != true)) {
                return if (repairable.isEmpty()) CellBudget.GATE else "reserve reached: edits are limited to files this cell already changed (" +
                    repairable.take(REPAIR_PATHS_SHOWN).joinToString(", ") + (if (repairable.size > REPAIR_PATHS_SHOWN) ", … +${repairable.size - REPAIR_PATHS_SHOWN}" else "") + "); verify and report"
            }
            val op = call.operationNames.firstOrNull { !mask.allows(it) } ?: return null
            val ceiling = Ceiling.of(contract.authorization, ctx.config.executionMode, ctx.hostSets).allows(op, ctx.role.toolMask)?.detail
            return Refusals.masked(op, ctx.role, contract.shape, mask, ceiling)
        }

        /**
         * The whole turn is refused for [reason] ([culprits] by index caused it; every call not refused on its own when
         * none is to blame) — unless one of the [valid] calls is terminal (`task` ask or answer, `state` blocked): that
         * call runs alone (F2b). A call refused on its own keeps its own reason.
         */
        private fun wholeTurn(native: List<NativeCall>, valid: List<ToolCall>, alone: Map<Int, Pair<String, String>>, reason: String, culprits: Set<Int>? = null): Validated {
            val terminal = valid.firstOrNull { it.terminal }
            val text = if (terminal == null) reason + Refusals.WHOLE_TURN else "$reason — the terminal call ${terminal.family.wire}(${terminal.op}) ran alone"
            val others = native.indices.filter { it !in alone && it != terminal?.opId?.minus(1) }
            val blamed = (culprits ?: others.toSet()).filter { it !in alone }.map { Refusal(native[it], reason, reason) }
            val ran = validated(native, listOfNotNull(terminal), alone, others.associateWith { text })
            return Validated(ran.calls, ran.notExecuted, ran.refused + blamed)
        }

        /** [calls] dispatch; each refused call says whether the others ran; [held] are not executed for their own reason. */
        private fun validated(native: List<NativeCall>, calls: List<ToolCall>, alone: Map<Int, Pair<String, String>>, held: Map<Int, String>): Validated {
            val n = calls.size
            val trailer = if (n == 0) Refusals.WHOLE_TURN else "; this call was refused; the other $n call${if (n == 1) "" else "s"} of the turn ran"
            val notExecuted = HashMap(held)
            alone.forEach { (index, refusal) -> notExecuted[index] = refusal.first + trailer }
            return Validated(calls, notExecuted, alone.map { (index, refusal) -> Refusal(native[index], refusal.first, refusal.second) })
        }

        /** [call] parsed on its own as op [opId], or the schema error that refuses it. */
        private fun parseOne(call: NativeCall, opId: Int): ParsedCalls = try {
            when (val parsed = ToolCalls.parse(listOf(call))) {
                is ParsedCalls.Valid -> ParsedCalls.Valid(listOf(parsed.calls.single().copy(opId = opId)))
                is ParsedCalls.Invalid -> parsed
            }
        } catch (e: IllegalArgumentException) {
            ParsedCalls.Invalid(call.id, "${call.name}: ${e.message}")
        }

        private val ToolCall.terminal: Boolean
            get() = (family == ToolFamily.Task && (op == "ask" || op == "answer")) || (family == ToolFamily.State && op == "blocked")

        /** F1a: the signature the refusal loop counted past `loopIdentical`, as the request the cell ends blocked with. */
        private fun refusalLoop(): BlockedRequest? {
            val (signature, n) = refused.groupingBy { it }.eachCount().entries.firstOrNull { it.value > defaults.loopIdentical } ?: return null
            val (since, reason) = refusedFirst[signature] ?: return null
            return BlockedRequest("refusal loop: $reason", listOf("$n identical refused calls of ${signature.tool} since turn $since"), null, turn)
        }

        // ---------------------------------------------------------- results

        /** A-08: content-free progress of [id] as `ModelProgress` events, when the adapter reports any; closed after the terminal. */
        private fun progressRelay(id: InvocationId): AutoCloseable? {
            val bus = events ?: return null
            val observable = ctx.model.adapter as? ObservableAdapter ?: return null
            return observable.addListener { p ->
                if (p.id != id) return@addListener
                bus.emit(
                    when (p) {
                        is InvocationProgress.Started -> AgentEvent.Cell.ModelProgress(ids, id.value, "started")
                        is InvocationProgress.Output -> AgentEvent.Cell.ModelProgress(ids, id.value, "output", textChars = p.textChars, outputTokens = p.outputTokens)
                        is InvocationProgress.Retrying -> AgentEvent.Cell.ModelProgress(ids, id.value, "retrying", attempt = p.attempt)
                    },
                )
            }
        }

        private fun appendNative(response: Response) {
            for (item in response.items) {
                val resident = when (item) {
                    is Message -> Resident.message(item, turn, item.estimate(estimator).tokens)
                    is NativeCall -> Resident.call(item, turn, item.estimate(estimator).tokens)
                    is ReasoningRef -> Resident(item, turn, item.estimate(estimator).tokens)
                    else -> continue // usage and continuations are accounting items, never replayed as [T]
                }
                residents = residents + resident
            }
        }

        /** A result enters `[T]` with the pointer a stub will keep: the alias resolved to its observation, when it is one (§5.7). */
        private fun appendResult(callId: String, text: String, isError: Boolean, label: String, resultClass: ResultClass, alias: String?) {
            val pointer = alias?.let(Aliases::parse)?.let { ev.aliases.resolve(ids.work, it) }?.let { resolved ->
                ev.observations.get(resolved.canonicalId)?.let { RecallPointer(alias, it.id, it.contentRef) }
            }
            alias?.let(Aliases::parse)?.let { ev.aliases.resolve(ids.work, it) }?.let { shownAliases += alias }
            val item = ToolResult.text(callId, text, isError)
            residents = residents + Resident.result(item, turn, estimator.estimate(text).tokens, resultClass, pointer, label)
        }

        private fun label(call: ToolCall, outcome: ToolOutcome?): String {
            val header = outcome?.header ?: return call.name
            val versions = header.versions.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}@${it.value.hash8.take(4)}" }
            return listOf(call.name, header.runtime.scope.orEmpty(), versions).filter { it.isNotBlank() }.joinToString(" ")
        }

        /** Paths a call mutated, as the runtime observed them: the edit's applied ops (`kind path`), a run's stamp diff; a verify's checks are announced by the scheduler. */
        private fun mutatedPaths(call: ToolCall, outcome: ToolOutcome): List<String> {
            val observed = outcome.header?.runtime?.effectsObserved.orEmpty()
            return when (call.family) {
                ToolFamily.Edit -> observed.map { it.substringAfter(' ', it) }
                ToolFamily.Run -> observed
                else -> emptyList()
            }
        }

        private fun journalOutput(response: Response) {
            val items = response.items.filter { it !is UsageItem }
            val payload = JSON.encodeToJsonElement(ITEMS, items)
            val head = response.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            ev.journal.append(
                JournalEvent(
                    idGen.next("ev"), ids, turn, JournalKind.Call, argsDigest = Digest.ofUtf8(payload.toString()),
                    text = "turn $turn model output · stop ${response.stop.name.lowercase()} · ${response.toolCalls.size} calls" + (if (head.isEmpty()) "" else " · $head"),
                    payload = payload, at = clock.instant(),
                ),
            )
        }

        private fun journalResult(callId: String, line: String, refs: List<String>) {
            ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Result, refs = refs, text = "call $callId: $line", at = clock.instant()))
        }

        /** §9.2 preimage journaling: the preimages an edit saved before writing are indexed under the edit's alias. */
        private fun preimagesOf(alias: String): List<Preimage> {
            val preimages = ev.preimages ?: return emptyList()
            val editId = Aliases.parse(alias)?.let { ev.aliases.resolve(ids.work, it) }?.takeIf { it.kind == "edit" }?.canonicalId ?: return emptyList()
            return preimages.of(editId)
        }

        private fun journalPreimages(alias: String) {
            val saved = preimagesOf(alias)
            if (saved.isEmpty()) return
            ev.journal.append(
                JournalEvent(
                    idGen.next("ev"), ids, turn, JournalKind.EditOutcome, refs = listOf(alias) + saved.map { it.preimageDigest.hex },
                    text = "edit $alias preimages: " + saved.joinToString(", ") { "${it.path} @${it.versionBefore.hash8}→@${it.versionAfter?.hash8 ?: "none"}" },
                    payload = JSON.encodeToJsonElement(PREIMAGES, saved), at = clock.instant(),
                ),
            )
        }

        // -------------------------------------------------------- reconcile

        /** Stamps the tree and announces every member that moved without an announcement (an external edit, a crashed mutation). */
        private fun reconcile(cause: String): StampReport {
            val before = lastReport
            val after = ws.stamper.report()
            if (before != null) {
                // What the registry never heard of: a moved member whose recorded version is not the bytes now.
                val unannounced = Stamper.diff(before, after).filter { path -> ws.registry.recorded(path) != after.members[path]?.digest?.let(::FileVersion) }
                announceMoved(ws.registry, before, after, cause)
                if (unannounced.isNotEmpty()) {
                    unannounced.forEach { origins.putIfAbsent(it, ChangeOrigin.External) }
                    ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Reconcile, refs = unannounced, text = "$cause: ${unannounced.size} paths moved unannounced · reconciled @${after.candidateId.hash8}", at = clock.instant()))
                }
            }
            ctx.noteHorizon?.reconcile(ws.registry::version, mapOf("contract" to "v${ctx.contracts.current(ids.work)?.version}"))
            lastReport = after
            reconciledTurn = turn
            ws.scheduler.refresh(after.candidateId, after.env)
            return after
        }

        /**
         * L4: coverage the model never saw authorizes nothing. An executor that failed after registering a view
         * (an edit that crashed between its write and its result) leaves KNOWN ranges behind whose bytes never
         * reached `[T]`; they are dropped here, before the next turn can anchor an edit on them.
         */
        private fun dropUnseenCoverage(calls: List<ToolCall>, result: TurnResult?) {
            if (result == null) return
            val shown = calls.filter { result.of(it.opId) is Disposition.Executed }.mapNotNull { (result.of(it.opId) as Disposition.Executed).outcome.resultAlias }.toSet()
            ws.workset.entries.filter { it.turn == turn && it.resultId != null && it.resultId !in shown }.map { it.resultId!! }.distinct().forEach(ws.workset::stub)
        }

        /** Every stamped member that moved during the turn, whoever moved it; each joins the acceptance-surface check. */
        private fun movedThisTurn(before: StampReport, after: StampReport, contract: Contract): List<String> {
            val moved = Stamper.diff(before, after).toList()
            TestIntegrity.baseline(moved.filter { it !in flags }, "turn $turn", contract, ws.checks).forEach { flags.putIfAbsent(it.path, it) }
            return moved
        }

        /** Drains the scheduled paths into the end-of-turn checker and the atlas; returns the stamp after the checks, or `null` when nothing ran. */
        private suspend fun drainScheduled(contract: Contract): StampReport? {
            val scheduled = ws.coherence.takeScheduled()
            if (scheduled.isEmpty()) return null
            touched += scheduled
            atlas = atlas.refresh(scheduled)
            tools.look?.atlas = atlas
            tools.verify?.touched = touched.toList()
            tools.verify?.inputs = atlas.rows.map { it.path }
            tools.verify?.atlas = atlas
            val checker = ws.checker ?: return null
            val results = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { checker.run(scheduled, defaults.checkerTimeBoxSeconds.toLong(), defaults.checkerFallbackTimeBoxSeconds.toLong()) }
            if (results.isEmpty()) return null
            for (result in results) {
                val receipt = ws.scheduler.record(result, contract.version)
                ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Check, refs = listOf(receipt.receiptId), text = "end-of-turn ${result.checkId}: ${result.outcome.name.lowercase()} on ${result.touched.size} paths", at = clock.instant()))
            }
            return reconcile("end-of-turn checks of turn $turn")
        }

        /** Workset announcements of this turn: stale bodies lose their reuse value now, oversized ones are stubbed at once (§5.3). */
        private fun worksetDrops() {
            val announced = ws.workset.takeAnnouncements()
            drops = announced
            if (announced.isEmpty()) return
            val stale = announced.mapNotNull { it.recallId }.toSet()
            residents = residency.markStale(residents, stale)
            val now = announced.filter { it.stubNow }.mapNotNull { it.recallId }.toSet()
            if (now.isNotEmpty()) {
                val eviction = residency.stubNow(residents, now, turn)
                residents = eviction.residents
                eviction.stubbed.forEach { stub -> stub.alias?.let(ws.workset::stub) }
            }
            events?.emit(AgentEvent.Cell.WorksetChanged(ids, ws.workset.entries.map { it.path }.distinct().size, announced.map { it.path }))
        }

        private fun evict(trigger: EvictionTrigger) {
            val eviction = residency.batch(residents, turn, trigger)
            residents = eviction.residents
            eviction.stubbed.forEach { stub -> stub.alias?.let(ws.workset::stub) }
            if (eviction.rewritten || eviction.overBound) {
                ev.journal.append(
                    JournalEvent(
                        idGen.next("ev"), ids, turn, JournalKind.Boundary,
                        text = "eviction ${trigger.name.lowercase()} at turn $turn: ${eviction.stubbed.size} stubbed · ${eviction.trimmed} trimmed · ${eviction.losses.size} losses" +
                            (if (eviction.overBound) " · over R_max by pinned or unrefetchable results" else ""),
                        at = clock.instant(),
                    ),
                )
            }
        }

        // ------------------------------------------------------- checkpoint

        private fun checkpoint(status: CellStatus, stamp: CandidateId?, reason: String?): CellCheckpoint = CellCheckpoint(
            cell = cell, increment = increment.id, turn = turn, status = status, registerVersion = register.version, stamp = stamp,
            knownFiles = ws.workset.entries.map { it.path }.distinct().size, knownTokens = ws.workset.knownTokens,
            openIntents = ev.intents.open().map { it.intentId }, touched = touched.toList(),
            unresolvedFlags = TestIntegrity.unresolved(flags.values.toList()).map { it.line }, journalSeq = ev.journal.lastSeq(ids.work), reason = reason,
            rebuilds = rebuilds,
        )

        /**
         * `Rebuild(Pressure)` inside the cell (§5.8, P2.5.2): the whole projection is replaced — `[T]` keeps the last
         * m = 6 complete protocol turns, the Workset becomes the carried seeds (KNOWN = seeds only), STATE is validated
         * and a pinned `rebuilt:` note names the generation. Nothing is summarised by a model.
         */
        private fun rebuild(why: String) {
            val contract = contract()
            val carry = CarryForward.carry(
                register, ws.workset.export(), null, ws.registry::version,
                { id -> Aliases.parse(id)?.let { ev.aliases.resolve(ids.work, it) } != null }, emptyList(), pinned(contract),
            )
            val seeds = io.astrolabe.context.Seeds.render(carry.seeds, ws.registry::read)
            val carried = carry.copy(seeds = seeds.shown, notSeen = carry.notSeen + seeds.notSeen)
            val nextSections = sections.filterNot { it.id.startsWith("seed-") || it.id == "carry-forward" } +
                KSection("carry-forward", "Carry-forward", carried.render()) +
                seeds.blocks.mapIndexed { index, text -> KSection("seed-$index", "Seed", text) }
            val current = io.astrolabe.context.Projection(rebuilds, ctx.role, ctx.model.profile, ctx.prime,
                CompiledK(ContractSlice.forIncrement(contract, increment), ctx.preexisting, sections), transcript(contract), "")
            val (next, record) = io.astrolabe.context.Rebuild.run(
                RebuildReason.Pressure, current, carried, "", ctx.prime,
                { _, _ -> current.k.copy(sections = nextSections) },
                object : io.astrolabe.context.RebuildHooks {
                    override fun checkpoint(old: io.astrolabe.context.Projection, reason: RebuildReason) {
                        persist(checkpoint(CellStatus.Running, ws.stamper.report().candidateId, null))
                    }
                    override fun status(reason: RebuildReason) {}
                    override fun rehydrate(lost: List<String>) {
                        contract()
                        rebuildGap = "replan: pressure rebuild lost required evidence: ${lost.joinToString()}"
                    }
                },
                { projection -> io.astrolabe.context.Compiler(estimator, ctx.config).coverage(contract, increment,
                    Layout.compiled(projection.k), projection.repository, projection.transcript.pinned, io.astrolabe.context.CompileInputs()) },
            )
            if (record.lost.isNotEmpty()) return
            rebuilds = next.generation
            sections = next.k.sections
            residents = residency.tail(residents, RebuildReason.Pressure.tailTurns)
            ws.workset.rebuild(seeds.shown)
            tools.state.validated(carried.register)
            val note = "rebuilt: pressure (generation $rebuilds) - $why - ${carried.known}"
            rebuildNotes += note
            ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Boundary, text = note, at = clock.instant()))
            events?.emit(AgentEvent.Cell.Rebuilt(ids, why, Generation(rebuilds)))
        }

        private fun persist(checkpoint: CellCheckpoint) {
            val stamped = ids.withCandidate(checkpoint.stamp)
            ev.registerVersions.save(stamped, register)
            ev.checkpoints.save(stamped, checkpoint, ws.workset.export())
            ev.journal.append(
                JournalEvent(
                    idGen.next("ev"), ids, turn, JournalKind.Boundary, text = "turn $turn ${checkpoint.status.name.lowercase()} · stamp @${checkpoint.stamp?.hash8 ?: "none"} · STATE v${checkpoint.registerVersion} · open intents ${checkpoint.openIntents.size}",
                    payload = JSON.encodeToJsonElement(CellCheckpoint.serializer(), checkpoint), at = clock.instant(),
                ),
            )
        }

        /** Every exit path: the tree is reconciled if this turn has not done it, then the final checkpoint is persisted. */
        private fun settle(status: CellStatus, reason: String?): CellCheckpoint {
            if (reconciledTurn != turn) runCatching { reconcile("exit at turn $turn") }
            val checkpoint = checkpoint(status, lastReport?.candidateId, reason)
            persist(checkpoint)
            events?.emit(AgentEvent.Cell.Ended(ids, status.name.lowercase(), null, ctx.manifest))
            return checkpoint
        }

        /** The exit reports the turns the budget actually admitted; a turn refused before dispatch was never taken. */
        private fun finish(exit: Exit): CellExit {
            val checkpoint = settle(exit.status, exit.reason)
            val packet = persistPacket(packet(PacketStatus.of(exit.status), exit.reason, exit.blocked, exit.evidenceRefs))
            return exit.make(budget.turnsTaken, register, checkpoint, packet)
        }

        private fun failed(error: String) = Exit(CellStatus.Failed, error) { t, r, cp, p -> CellExit.Failed(t, r, cp, p, error) }
        private fun cancelled(reason: String) = Exit(CellStatus.Cancelled, reason) { t, r, cp, p -> CellExit.Cancelled(t, r, cp, p, reason) }
        private fun partial(reason: PartialReason, hint: String) = Exit(CellStatus.Partial, "${reason.name}: $hint") { t, r, cp, p -> CellExit.Partial(t, r, cp, p, reason, hint) }
        private fun blocked(request: BlockedRequest) = Exit(CellStatus.Blocked, request.reason, blocked = request) { t, r, cp, p -> CellExit.Blocked(t, r, cp, p, request) }
        private fun answered(text: String) = Exit(CellStatus.Completed, null) { t, r, cp, p -> CellExit.Completed(t, r, cp, p, text, emptyList(), answer = text) }

        // A pending acceptance keeps the `done` packet reason-free (§5.9): its gaps are the packet's recorded gaps.
        private fun completed(text: String, refs: List<String>, pending: PendingAcceptance? = null) =
            Exit(CellStatus.Completed, null, evidenceRefs = refs) { t, r, cp, p -> CellExit.Completed(t, r, cp, p, text, refs, pending) }

        // ----------------------------------------------------------- packet

        /**
         * §5.9 from records: the dispatch base, the Workset's displayed versions, the registry's transitions with
         * the runtime's attribution, the scheduler's receipts, the collected flags and usage. The status comes
         * from the exit; the model's text is not consulted.
         */
        private fun packet(status: PacketStatus, reason: String?, blocked: BlockedRequest? = null, evidenceRefs: List<String> = emptyList()): ResultPacket {
            val report = lastReport
            val changes = changes()
            val shown = displayed.keys.map { it.first }.toSet()
            val own = changes.filter { it.origin != ChangeOrigin.External }
            return ResultPacket(
                ids = ids.withCandidate(report?.candidateId), increment = increment.id, role = ctx.role.name,
                contractVersion = contractVersion, executionGeneration = ctx.generation,
                base = base?.let { PacketBase(it.candidateId, ws.workspace.id) }, readVersions = readVersions.toMap(),
                status = status, reason = reason, waiting = null, register = register, worksetExport = ws.workset.export(),
                changes = changes, transforms = tools.edit?.transforms.orEmpty().map(TransformRecord::of), receipts = ws.checks.all().mapNotNull { it.last?.receiptId },
                stamp = report?.candidateId, envId = report?.env?.envId,
                coverage = PacketCoverage(displayed.values.sumOf { it.ranges.size }, own.filter { it.path !in shown }.map { it.path }),
                flags = PacketFlags(own.filter { c -> increment.writeScope.none { PathPattern.matches(it, c.path) } }.map { it.path }, flags.values.toList()),
                claims = PacketClaims(notTested = (notTested + uncertified.map { "$it: no current certifying receipt" }).toList(), openQuestions = register.open.filter { !it.closed }.map { it.text } + tools.task?.asked.orEmpty().filter { it.answer == null }.map { it.question.text }),
                blocked = blocked, gaps = gaps.toList(), evidenceRefs = evidenceRefs, cost = cost,
            )
        }

        /** Net changes of the cell: each path's first and last registry transition, attributed to whoever the runtime saw move it. */
        private fun changes(): List<Change> {
            val first = LinkedHashMap<String, FileVersion?>()
            val last = HashMap<String, FileVersion?>()
            for (t in touchedLedger) {
                if (!first.containsKey(t.path)) first[t.path] = t.from
                last[t.path] = t.to
            }
            return first.mapNotNull { (path, before) ->
                val after = last[path]
                if (before == after) return@mapNotNull null
                val kind = when {
                    after == null -> TouchKind.Deleted
                    before == null -> TouchKind.Added
                    else -> TouchKind.Modified
                }
                Change(path, kind, before, after, origins[path] ?: ChangeOrigin.External)
            }
        }

        /** Coverage the model was shown; redacted lines grant none (D-49), so a fully redacted view is not a read. */
        private fun observeWorkset() {
            for (entry in ws.workset.entries) {
                if (entry.coverage.isEmpty) continue
                displayed.merge(entry.path to entry.version, entry.coverage) { a, b -> a + b }
                readVersions[entry.path] = entry.version
            }
        }

        /** §3.7 `record_completion_gaps`: journaled and carried into the packet; `[A]` shows them through the exit gate's line. */
        private fun recordGaps(found: List<String>, what: String = "completion refused at turn $turn") {
            gaps += found
            ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Nudge, text = "$what: ${found.joinToString("; ")}", at = clock.instant()))
        }

        /** One reviewer rejection as a pinned block (D-341): the author and every finding, in full. */
        private fun pinReview(rejection: ObligationResult) {
            reviewNotes += buildString {
                append("review of ").append(rejection.obligation).append(" by ").append(rejection.by ?: "the reviewer")
                append(" rejected the change; fix every finding below, then propose completion again:")
                rejection.findings.forEach { f ->
                    append("\n- ").append(f.severity.name.lowercase()).append(' ').append(f.location).append(": ").append(f.issue)
                    f.suggestedFix?.let { append(" (suggested: ").append(it).append(')') }
                }
            }
        }

        /** The packet is durable before the exit returns (§3.7 `persist_role_packet`): one boundary event with its references. */
        private fun persistPacket(packet: ResultPacket): ResultPacket {
            ev.journal.append(
                JournalEvent(
                    idGen.next("ev"), ids.withCandidate(packet.stamp), turn, JournalKind.Boundary, refs = packet.receipts + packet.evidenceRefs,
                    text = "packet ${packet.status.wire} · ${packet.changes.size} changes · ${packet.receipts.size} receipts · stamp @${packet.stamp?.hash8 ?: "none"}" +
                        (packet.reason?.let { " · $it" } ?: ""),
                    at = clock.instant(),
                ),
            )
            return packet
        }

        // ------------------------------------------------------------ checks

        private fun currencies(stampNow: CandidateId?): Map<String, Currency> =
            ws.checks.all().filter { it.last != null }.associate { it.id to ws.scheduler.currency(it, stampNow) }

        /** The acceptance checks verify-on-stop is about to run at [stampNow] — the same selection [Verify.onStop] makes. */
        private fun remainingAcceptance(stampNow: CandidateId): List<Check> =
            Layers.select(Layer.IncrementAcceptance, ws.checks, increment.accept) { check ->
                val currency = ws.scheduler.currency(check, stampNow)
                currency.receiptId == null || currency.applicability != Applicability.Current || !currency.eligible
            }.run

        /** Acceptance ids of the increment certified on the tree now. */
        private fun certified(currencies: Map<String, Currency>): Set<String> =
            increment.accept.filter { id -> ws.checks.forAcceptance(id).any { currencies[it.id]?.certifies == true } }.toSet()

        private fun Map<String, Currency>.certifiedReceipt(acceptanceId: String): String? =
            ws.checks.forAcceptance(acceptanceId).firstNotNullOfOrNull { check -> this[check.id]?.takeIf { it.certifies }?.receiptId }

        /**
         * §5.6 repeated failure signature: a red check's normalized first error line (digits folded, whitespace
         * collapsed) or its failure counts; a signature still red after two turns with edits is returned.
         */
        private fun repeatedFailures(currencies: Map<String, Currency>, repaired: Boolean): List<String> {
            val latest = ws.checker?.latest().orEmpty().associateBy { it.checkId }
            val red = currencies.filter { it.value.red }.map { (id, currency) ->
                val line = latest[id]?.errorLines?.firstOrNull()?.replace(DIGITS, "#")?.replace(SPACES, " ")?.trim()?.take(SIGNATURE_CHARS)
                val counts = currency.receiptId?.let { ev.receipts.get(it)?.parsed }?.let { "${it.failed} failed ${it.errors} errors" }
                "$id: ${line ?: counts ?: "red"}"
            }.toSet()
            if (repaired) red.filter { it in redSeen }.forEach { repairs.merge(it, 1, Int::plus) }
            repairs.keys.retainAll(red)
            redSeen = red
            return red.filter { (repairs[it] ?: 0) >= 2 }.sorted()
        }

        /** The `[>]` step this turn's patch left (ticked or moved past), when it moved. */
        private fun stepLeft(before: Register, after: Register): Step? =
            before.plan.firstOrNull { it.mark == Mark.Cursor }?.takeIf { after.step(it.n)?.mark != Mark.Cursor }

        /** Required checks without a certifying receipt: what a reserve exit names as unverified (FX-43). */
        private fun outstanding(currencies: Map<String, Currency>): List<String> =
            ws.checks.required().filter { currencies[it.id]?.certifies != true }.map { it.id }

        private fun obligations(contract: Contract, currencies: Map<String, Currency>): List<ObligationStatus> = increment.accept.map { id ->
            val text = when (contract.acceptance(id)) {
                is Acceptance.Run -> {
                    val check = ws.checks.forAcceptance(id).firstOrNull()
                    val currency = check?.let { currencies[it.id] }
                    when {
                        currency?.receiptId == null -> "needs run"
                        currency.certifies -> "green @${check.last?.stamp?.hash8?.take(4)}"
                        currency.green -> "green STALE (${currency.reasons.firstOrNull() ?: "not current"})"
                        else -> "red (${ws.scheduler.aliasOf(currency.receiptId) ?: currency.receiptId})"
                    }
                }
                is Acceptance.Check -> "needs assessment"
                is Acceptance.Review -> "needs review"
                null -> "not in contract v${contract.version}"
            }
            ObligationStatus(id, text)
        }

        private fun checkLines(currencies: Map<String, Currency>): List<CheckLine> = ws.checks.all().mapNotNull { check ->
            val last = check.last ?: return@mapNotNull null
            val currency = currencies[check.id]
            val state = when (last.outcome) {
                Outcome.Passed -> if (currency?.applicability == Applicability.Current || currency == null) CheckState.Green("✓") else CheckState.Stale(currency.reasons.firstOrNull() ?: "not current")
                Outcome.Failed -> CheckState.Red("", (last.counts?.failed ?: 0) + (last.counts?.errors ?: 0))
                Outcome.Timeout -> CheckState.Timeout("time box")
                Outcome.NotRun -> CheckState.NotRun
                Outcome.Unavailable -> CheckState.Unavailable(unavailableReason(last.receiptId))
                Outcome.UnknownOutcome -> CheckState.Unavailable("unknown outcome; reconcile before retry")
                else -> CheckState.Inconclusive(last.outcome.name.lowercase())
            }
            CheckLine(labelOf(check), Blast.scope(check), null, state, last.stamp.hash8, ws.scheduler.aliasOf(last.receiptId))
        }

        /** Why a check could not run, as its receipt records it (the launcher's reason, bounded), the way `verify` shows it. */
        private fun unavailableReason(receiptId: String): String {
            val limits = ev.receipts.get(receiptId)?.limits.orEmpty()
            val reason = (limits.firstOrNull { it.kind == "runner" || it.kind == "unavailable" } ?: limits.firstOrNull())?.detail
                ?.replace('\r', ' ')?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() } ?: return "runner missing"
            return if (reason.length <= UNAVAILABLE_REASON_CHARS) reason else reason.take(UNAVAILABLE_REASON_CHARS - 1) + "…"
        }

        private fun checksSummary(currencies: Map<String, Currency>): String = checkLines(currencies).joinToString(" · ") { line ->
            line.label + " " + when (val s = line.state) {
                is CheckState.Green -> "✓"
                is CheckState.Red -> "✗${s.count}"
                is CheckState.Stale -> "stale"
                CheckState.NotRun -> "not run"
                is CheckState.Timeout -> "timeout"
                is CheckState.Unavailable -> "unavailable"
                is CheckState.Inconclusive -> "?"
            }
        }

        private fun labelOf(check: Check): String = when {
            check.selector == Selector.Blast -> "tests"
            check.kind == CheckKind.Acceptance -> "accept ${check.acceptanceIds.joinToString("+")}"
            check.kind == CheckKind.Type -> "types"
            else -> check.kind.name.lowercase()
        }

        private fun truncationLine(response: Response): List<String> = when (response.stop) {
            StopReason.Truncated, StopReason.OutputLimit -> listOf("output: the response ended ${response.stop.name.lowercase()}; no call was exposed or executed — restate the call")
            else -> emptyList()
        }

        // --------------------------------------------------------- executors

        /** Every executor records its outcome so the `state` tool's validator sees this turn's green and applied ops (§5.5). */
        private fun executors(): Map<ToolFamily, ToolExecutor> = buildMap {
            put(ToolFamily.State, recording(tools.state))
            tools.look?.let { put(ToolFamily.Look, recording(it)) }
            tools.edit?.let { put(ToolFamily.Edit, recording(it)) }
            tools.run?.let { put(ToolFamily.Run, recording(it)) }
            tools.verify?.let { put(ToolFamily.Verify, recording(it)) }
            tools.task?.let { put(ToolFamily.Task, recording(it)) }
            tools.kb?.let { put(ToolFamily.Kb, recording(it)) }
        }

        private fun recording(executor: ToolExecutor): ToolExecutor = ToolExecutor { call, context ->
            enforceAuthority()
            executor.execute(call, context).also { outcome -> record.record(call, outcome) }
        }

        private fun enforceAuthority() {
            authority.check(turn)?.let { error("dispatch refused: ${it.reason}") }
        }

        /** What the validator may consult this turn (P1.5.2): store ids, current check status and the turn's own ops. */
        private inner class TurnRecord : ValidationContext {
            private val outcomes = ConcurrentHashMap<Int, ToolOutcome>()

            init {
                tools.state.validation = this
            }

            fun reset() {
                outcomes.clear()
                tools.state.opResults = emptyMap()
            }

            /** Reads run in parallel: the `op:N → #alias` map the state tool consults is rebuilt under the lock. */
            @Synchronized
            fun record(call: ToolCall, outcome: ToolOutcome) {
                outcomes[call.opId] = outcome
                tools.state.opResults = outcomes.entries.mapNotNull { (op, o) -> o.resultAlias?.takeIf { it != NO_ALIAS }?.let { op to it } }.toMap()
            }

            override fun evidenceExists(id: String): Boolean {
                Aliases.parse(id)?.let { return ev.aliases.resolve(ids.work, it) != null }
                return ev.observations.get(id) != null || ev.receipts.get(id) != null || ev.journal.get(id) != null
            }

            override fun currentVersion(path: String): io.astrolabe.id.FileVersion? = ws.registry.version(path)

            override fun knownVersions(path: String): Collection<io.astrolabe.id.FileVersion> =
                listOfNotNull(currentVersion(path)) + ws.workset.entries.filter { it.path == path }.map { it.version } + ws.workset.history(path)

            override fun acceptGreen(accept: String): Boolean {
                val currencies = currencies(ws.stamper.stamp().id)
                return ws.checks.forAcceptance(accept).any { currencies[it.id]?.certifies == true } || currencies[accept]?.certifies == true
            }

            override val redChecks: Set<String>
                get() = if (redOkUntilIncrementEnd) emptySet() else ws.checks.all().filter { it.last?.outcome == Outcome.Failed }.map { it.id }.toSet()

            override val greenOps: Set<Int> get() = outcomes.filterValues { it.green }.keys

            override val appliedOps: Set<Int> get() = outcomes.filterValues { it.applied }.keys
        }
    }

    private companion object {
        /** The alias executors give a result that observed nothing new. */
        const val NO_ALIAS = "#-"

        /** §5.1 `[A]`: at most four nudge lines per turn (D-372; [Anchor] applies the same cap). */
        const val MAX_NUDGES = 4

        /** The nudges that say how much of the cell is left: shown before the other nudges (D-372). */
        val PRIORITY_NUDGES = setOf(Gates.STALL, Gates.RESERVE, Gates.TURNS)
        const val SIGNATURE_CHARS = 120

        /** How much of a launcher's reason the checks view of `[A]` carries. */
        const val UNAVAILABLE_REASON_CHARS = 160
        val DIGITS = Regex("\\d+")
        val SPACES = Regex("\\s+")

        /** D-366: how many of the cell's own paths a reserve-repair refusal names before it counts the rest. */
        const val REPAIR_PATHS_SHOWN = 12

        /** Edit ops not addressed by a path: never a reserve repair (D-366). */
        val NOT_PATH_ADDRESSED = setOf("transform", "revert")

        /** How many of a rejection's details ride on its line. */
        const val DETAILS_IN_LINE = 2

        const val MILLIS_PER_SECOND = 1000.0

        /** How many harness frames a failure record names, innermost first: no stack trace is kept anywhere else. */
        const val SITE_FRAMES = 4

        fun site(failure: Throwable): String {
            val frames = failure.stackTrace.filter { it.className.startsWith("io.astrolabe.") && it.fileName != null && !it.methodName.startsWith("access\$") }
            return if (frames.isEmpty()) "" else frames.take(SITE_FRAMES).joinToString(" ← ", prefix = " at ") {
                "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            }
        }

        val JSON = Json { encodeDefaults = true }
        val ITEMS = ListSerializer(Item.serializer())
        val PREIMAGES = ListSerializer(Preimage.serializer())
    }
}
