package io.astrolabe.cell

import io.astrolabe.Defaults
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
import io.astrolabe.id.IdGen
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.ProblemKind
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
import io.astrolabe.register.ContractDigest
import io.astrolabe.register.ObligationStatus
import io.astrolabe.register.Register
import io.astrolabe.register.RegisterRender
import io.astrolabe.register.ValidationContext
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
import io.astrolabe.tool.state.BlockedRequest
import io.astrolabe.verify.Applicability
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.CheckLine
import io.astrolabe.verify.CheckState
import io.astrolabe.verify.ChecksRender
import io.astrolabe.verify.Currency
import io.astrolabe.verify.Selector
import io.astrolabe.verify.TestIntegrity
import io.astrolabe.verify.TestIntegrityFlag
import io.astrolabe.workset.StaleDrop
import io.astrolabe.workspace.ChangeListener
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

    /** The turn's calls after validation: all of them, or none (§5.4 error policy, fail closed). */
    private sealed interface Validated {
        class Calls(val calls: List<ToolCall>) : Validated
        class Refused(val reason: String) : Validated
    }

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
        private var fired: Set<GateKey> = emptySet()
        private val signatures = ArrayList<CallSignature>()
        private var lastProgressTurn = 0
        private var requiredOp: String? = null
        private var refusals = 0
        private val touched = LinkedHashSet<String>()
        private val touchedLedger = ArrayList<Touched>()
        private val flags = LinkedHashMap<String, TestIntegrityFlag>()
        private var drops: List<StaleDrop> = emptyList()
        private var nudges: List<String> = emptyList()
        private var lastReport: StampReport? = null
        private var reconciledTurn = 0
        private var occupancy: Occupancy? = null
        private var atlas = ws.atlas
        private var rebuilds = 0
        private val rebuildNotes = ArrayList<String>()

        // The packet's runtime-owned fields, collected as the cell runs (§5.9).
        private var base: StampReport? = null
        private var contractVersion = 0
        private val readVersions = LinkedHashMap<String, FileVersion>()
        private val displayed = LinkedHashMap<Pair<String, FileVersion>, Ranges>()
        private val origins = LinkedHashMap<String, ChangeOrigin>()
        private val gaps = ArrayList<String>()
        private var cost = PacketCost()

        private val register: Register get() = tools.state.register

        suspend fun run(): CellExit {
            events?.emit(AgentEvent.Cell.Started(ids, increment.id, ctx.role.name))
            // §4.4: the horizons hear every transition in this order — turn, cell, verification — then the Touched ledger.
            subscriptions += ws.coherence.register(ws.workset)
            subscriptions += ws.coherence.register(ChangeListener { tools.state.markStale(it) })
            subscriptions += ws.coherence.register(ws.checks)
            subscriptions += ws.coherence.register(ChangeListener { touchedLedger += Touched.of(it) })
            tools.edit?.increment = increment
            tools.verify?.inputs = atlas.rows.map { it.path }
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
                val error = "${failure::class.simpleName}: ${failure.message}"
                val checkpoint = settle(CellStatus.Failed, error)
                return CellExit.Failed(budget.turnsTaken, register, checkpoint, persistPacket(packet(PacketStatus.Failed, error)), error)
            } finally {
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

            // Render: [A] first (rebuilt every turn), then the cached regions, then admission.
            val contract = contract()
            contractVersion = contract.version
            val mask = maskFor(contract, reserveTurn)
            val schemas = when (val selection = ToolSchemas.forLineage(ctx.model.adapter, ctx.model.profile, mask)) {
                is SchemaSelection.Supported -> selection.set
                is SchemaSelection.Unsupported -> return failed("tool schemas unsupported for ${selection.profileId}: ${selection.reason}")
            }
            val anchor = renderAnchor(contract)
            val layout = Layout.render(ctx.role, mask, ctx.config.executionMode, ctx.prime, CompiledK(ContractSlice.forIncrement(contract, increment), ctx.preexisting, ctx.sections), transcript(contract))
            val request = Request(layout + anchor.segment(), schemas.schemas, ctx.model.profile, ctx.model.effort, ctx.model.maxOutputTokens, mask)
            val estimate = request.estimate(estimator)
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
            val response = try {
                ctx.model.adapter.start(request, invocationId).await()
            } catch (error: ProviderError) {
                admission.release()
                cost += null
                ctx.accounting?.record(ids, invocationId.value, ctx.model.profile, request, null)
                return failed("provider ${error::class.simpleName}: ${error.message}")
            }
            val usage = response.usage
            contextAdmission.observed(estimate, usage?.takeIf { it.isComplete }?.totalInput)
            cost += usage
            ctx.accounting?.record(ids, invocationId.value, ctx.model.profile, request, usage)
            admission.reconcile(Tokens(usage?.let { it.totalInput + (it.quantities[BillingDimension.OUTPUT] ?: 0L) } ?: admission.estimate.value))
            events?.emit(AgentEvent.Cell.ModelResponded(ids, invocationId.value, response.stop, usage))

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
            val validated = if (native.isEmpty()) null else validateCalls(native, reserveTurn)
            val calls = (validated as? Validated.Calls)?.calls.orEmpty()

            // Partition and dispatch — or refuse the whole turn.
            record.reset()
            val dispatchedAt = clock.millis()
            val result = if (validated is Validated.Calls) dispatcher.dispatch(turn, calls, Tokens(defaults.rMaxTokens.toLong())) else null
            cost = cost.plusToolSeconds((clock.millis() - dispatchedAt) / MILLIS_PER_SECOND)
            val after = reconcile("turn $turn")
            val gauge = gauge(currencies(after.candidateId))
            var liveRunOutput = false
            val editedPaths = LinkedHashSet<String>()
            when (validated) {
                null -> Unit
                is Validated.Refused -> for (call in native) {
                    appendResult(call.id, "${Boundary.RESULT_OPEN}not executed: ${validated.reason}${Boundary.RESULT_CLOSE}\n${gauge.line()}", isError = true, label = call.name, resultClass = ResultClass.Verdict, alias = null)
                    journalResult(call.id, validated.reason, emptyList())
                }
                is Validated.Calls -> for (call in calls) {
                    val disposition = result!!.of(call.opId)
                    val outcome = (disposition as? Disposition.Executed)?.outcome
                    val text = when (disposition) {
                        is Disposition.Executed -> Gauges.result(disposition.outcome, gauge)
                        is Disposition.NotExecuted -> "${Boundary.RESULT_OPEN}not executed: ${disposition.reason}${Boundary.RESULT_CLOSE}\n${gauge.line()}"
                        is Disposition.Failed -> "${Boundary.RESULT_OPEN}failed: ${disposition.error} — effects unknown; reconciled at the turn boundary${Boundary.RESULT_CLOSE}\n${gauge.line()}"
                    }
                    val alias = outcome?.resultAlias?.takeIf { it != NO_ALIAS }
                    val refs = listOfNotNull(alias) + outcome?.header?.runtime?.artifactRefs.orEmpty()
                    appendResult(call.providerCallId, text, isError = outcome == null, label = label(call, outcome), resultClass = ResultClass.of(call.name), alias = alias)
                    journalResult(call.providerCallId, outcome?.header?.line() ?: text.lineSequence().first(), refs)
                    if (outcome == null) continue
                    signatures += CallSignature.of(call, outcome)
                    if (call.family == ToolFamily.Run && outcome.header?.runtime?.status == "running") liveRunOutput = true
                    val mutated = mutatedPaths(call, outcome)
                    if (mutated.isNotEmpty()) {
                        editedPaths += mutated
                        val origin = if (call.family == ToolFamily.Edit) ChangeOrigin.Edit else ChangeOrigin.Run
                        mutated.forEach { path -> origins.merge(path, origin) { old, new -> if (old == ChangeOrigin.External) new else old } }
                        // §8.6 run-side collection: every mutation, by edit or by run, is checked against the acceptance surface.
                        TestIntegrity.baseline(mutated, "${call.family.wire} ${alias ?: "op ${call.opId}"}", contract, ws.checks).forEach { flags.putIfAbsent(it.path, it) }
                    }
                    if (call.family == ToolFamily.Edit && alias != null) journalPreimages(alias)
                }
            }
            editedPaths.addAll(movedThisTurn(before, after, contract))
            tools.state.fireTrips(editedPaths)
            dropUnseenCoverage(calls, result)
            observeWorkset()

            // End-of-turn checker on the paths the horizons scheduled; the atlas follows the same set.
            val proposal = native.isEmpty() && (response.stop == StopReason.EndTurn || response.stop == StopReason.ToolUse)
            val turnEnd = drainScheduled(contract) ?: after
            // §8.1 verify-on-stop: a completion proposal runs only the missing or stale acceptance checks; reused receipts stand.
            val stoppedChecks = if (proposal) tools.verify?.onStop(increment.accept).orEmpty() else emptyList()
            for (receipt in stoppedChecks) {
                ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Check, refs = listOf(receipt.receiptId), text = "verify-on-stop ${receipt.checkId}: ${receipt.outcome.name.lowercase()}", at = clock.instant()))
            }
            val stampNow = if (stoppedChecks.isEmpty()) turnEnd else ws.stamper.report()
            lastReport = stampNow
            ws.scheduler.refresh(stampNow.candidateId, stampNow.env)
            worksetDrops()
            residency.due(residents, turn)?.let { trigger -> evict(trigger) }
            val current = residency.occupancy(prefix(layout), residents, turn, anchor.tokens, pinnedTokens(contract))
            occupancy = current

            // Gates on records.
            val currenciesNow = currencies(stampNow.candidateId)
            val certifiedAfter = certified(currenciesNow)
            if (Progress.events(registerBefore, register, turn, certifiedBefore, certifiedAfter).isNotEmpty()) lastProgressTurn = turn
            val unresolved = TestIntegrity.unresolved(flags.values.toList())
            val state = GateState(
                turn = turn, register = register, contract = contract, increment = increment, calls = calls, signatures = signatures.toList(),
                patchRejection = if (calls.any { it.family == ToolFamily.State && it.op == "patch" }) tools.state.lastRejection else null,
                lastProgressTurn = lastProgressTurn, liveRunOutput = liveRunOutput,
                contextTokens = current.totalTokens, contextMaxTokens = capabilities.contextLimitTokens.toLong(), rebuilds = rebuilds,
                reserve = budget.verdict(outstanding(currenciesNow)), turnsMax = budget.turns, completionProposed = proposal,
                currencies = currenciesNow, flags = unresolved, fired = fired, defaults = defaults,
            )
            val report = gates.evaluate(state)
            fired = report.fired
            report.outcomes.forEach { events?.emit(AgentEvent.Cell.GateFired(ids, it.key.gate, it.line)) }
            report.rejections.firstOrNull { it.endsTurn }?.let { requiredOp = it.requiredOp }
            // §5.1: at most two lines, the hard gates' refusals before the nudges.
            nudges = (report.rejections.map { it.line + it.details.take(DETAILS_IN_LINE).joinToString("") { d -> " · $d" } } + report.nudges.map { it.line } + truncationLine(response)).take(MAX_NUDGES)

            // Checkpoint: the turn's boundary is durable before any exit is decided.
            persist(checkpoint(CellStatus.Running, stampNow.candidateId, null))

            // Terminal requests, the completion path, pressure.
            (tools.state.pendingBlock ?: tools.task?.pendingBlock)?.let { return blocked(it) }
            if (proposal) {
                val output = RoleOutput(turn, response.text, register, certifiedAfter.mapNotNull { currenciesNow.certifiedReceipt(it) }, refusals, packet(PacketStatus.Done, null))
                when (val decision = completion.assess(output, report)) {
                    is CompletionDecision.Accepted -> return completed(response.text, decision.evidenceRefs)
                    is CompletionDecision.CannotProgress -> {
                        recordGaps(decision.gaps)
                        return partial(PartialReason.CompletionStalled, "gaps this cell cannot close: ${decision.gaps.joinToString("; ")}")
                    }
                    is CompletionDecision.Continue -> {
                        recordGaps(decision.gaps)
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
            val digest = ContractDigest.render(contract, ctx.ledger ?: Ledger.initial(contract), obligations(contract, currencies), estimator, defaults.digestCapTokens)
            val worksetLine = ws.workset.render(estimator) + drops.joinToString("") { "; ${it.text}" }
            return Anchor.render(
                estimator, digest, RegisterRender.markdown(register), worksetLine, touchedLedger.toList(),
                ChecksRender.render(stampNow, checkLines(currencies)), null, null, gauge(currencies).line(), nudges,
                RegisterRender.firedTrips(register), defaults,
            )
        }

        private fun transcript(contract: Contract): Transcript = Transcript(pinned(contract), residency.items(residents))

        /** Pinned verbatim (invariant 1): the contract's requests, the caller's messages and every question this cell asked. */
        private fun pinned(contract: Contract): List<String> =
            contract.requests.map { it.text } + ctx.pinned + tools.task?.asked.orEmpty().map { asked ->
                "question ${asked.question.id}: ${asked.question.text}" + (asked.answer?.let { "\nanswer: ${it.text}" } ?: "\nanswer: none")
            } + rebuildNotes

        private fun pinnedTokens(contract: Contract): Long = pinned(contract).sumOf { estimator.estimate(it).tokens }

        private fun prefix(layout: List<Segment>): PrefixTokens {
            fun tokens(kind: SegmentKind): Long = layout.firstOrNull { it.kind == kind }?.items?.sumOf { it.estimate(estimator).tokens } ?: 0L
            return PrefixTokens(tokens(SegmentKind.S), tokens(SegmentKind.R), tokens(SegmentKind.K))
        }

        private fun gauge(currencies: Map<String, Currency>) = Gauges.of(
            occupancy?.percentOf(capabilities.contextLimitTokens) ?: 0, budget.snapshot(), checksSummary(currencies), ws.workset, register, turn, budget.turns,
        )

        /** Role mask ∩ shape ∩ ceiling; a reserve turn also masks the edit family (§5.9 "no new edits"). */
        private fun maskFor(contract: Contract, reserveTurn: Boolean): ToolMask {
            val effective = ctx.role.effectiveOps(contract.shape, Ceiling.of(contract.authorization, ctx.config.executionMode, ctx.hostSets))
            return if (reserveTurn) ToolMask(effective.allowed.filterNot { it.startsWith(ToolFamily.Edit.wire + ".") }.toSet()) else effective
        }

        private fun contract(): Contract = ctx.contracts.current(ids.work) ?: throw IllegalStateException("no committed contract for ${ids.work}")

        // --------------------------------------------------------- validate

        /**
         * §3.7 `validate_complete_calls_and_dependencies`: one unparseable call, one forward dependency, a missing
         * required `state` op after the loop gate or an edit on a reserve turn refuses every call of the turn.
         */
        private fun validateCalls(native: List<NativeCall>, reserveTurn: Boolean): Validated {
            val calls = when (val parsed = ToolCalls.parse(native)) {
                is ParsedCalls.Invalid -> return Validated.Refused("schema error in call ${parsed.providerCallId}: ${parsed.error}; no call of this turn executed")
                is ParsedCalls.Valid -> parsed.calls
            }
            (Partition.of(calls) as? Partition.Rejected)?.let { return Validated.Refused("${it.reason}; no call of this turn executed") }
            requiredOp?.let { op ->
                if (calls.none { it.family.wire == op }) return Validated.Refused("the loop gate ended the last turn: a $op op is required before anything else runs; no call of this turn executed")
                requiredOp = null
            }
            if (reserveTurn && calls.any { it.family == ToolFamily.Edit }) return Validated.Refused("${CellBudget.GATE}; the edit refused the whole turn")
            return Validated.Calls(calls)
        }

        // ---------------------------------------------------------- results

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
        private fun journalPreimages(alias: String) {
            val preimages = ev.preimages ?: return
            val editId = Aliases.parse(alias)?.let { ev.aliases.resolve(ids.work, it) }?.takeIf { it.kind == "edit" }?.canonicalId ?: return
            val saved = preimages.of(editId)
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
        private fun drainScheduled(contract: Contract): StampReport? {
            val scheduled = ws.coherence.takeScheduled()
            if (scheduled.isEmpty()) return null
            touched += scheduled
            atlas = atlas.refresh(scheduled)
            tools.look?.atlas = atlas
            tools.verify?.touched = touched.toList()
            tools.verify?.inputs = atlas.rows.map { it.path }
            val checker = ws.checker ?: return null
            val results = checker.run(scheduled, defaults.checkerTimeBoxSeconds.toLong())
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
            rebuilds += 1
            val kept = Rebuild.tail(residency.items(residents), RebuildReason.Pressure.tailTurns).toSet()
            residents = residents.filter { it.item in kept }
            val carry = CarryForward.carry(
                register, ws.workset.export(), null, { ws.registry.version(it) },
                { id -> Aliases.parse(id)?.let { ev.aliases.resolve(ids.work, it) } != null }, emptyList(), emptyList(),
            )
            ws.workset.rebuild(carry.seeds)
            tools.state.validated(carry.register)
            val note = "rebuilt: pressure (generation $rebuilds) — $why · ${carry.known}"
            rebuildNotes += note
            ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Boundary, text = note, at = clock.instant()))
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
        private fun completed(text: String, refs: List<String>) = Exit(CellStatus.Completed, null, evidenceRefs = refs) { t, r, cp, p -> CellExit.Completed(t, r, cp, p, text, refs) }

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
                changes = changes, transforms = emptyList(), receipts = ws.checks.all().mapNotNull { it.last?.receiptId },
                stamp = report?.candidateId, envId = report?.env?.envId,
                coverage = PacketCoverage(displayed.values.sumOf { it.ranges.size }, own.filter { it.path !in shown }.map { it.path }),
                flags = PacketFlags(own.filter { c -> increment.writeScope.none { PathPattern.matches(it, c.path) } }.map { it.path }, flags.values.toList()),
                claims = PacketClaims(openQuestions = register.open.filter { !it.closed }.map { it.text } + tools.task?.asked.orEmpty().filter { it.answer == null }.map { it.question.text }),
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
        private fun recordGaps(found: List<String>) {
            gaps += found
            ev.journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Nudge, text = "completion refused at turn $turn: ${found.joinToString("; ")}", at = clock.instant()))
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

        /** Acceptance ids of the increment certified on the tree now. */
        private fun certified(currencies: Map<String, Currency>): Set<String> =
            increment.accept.filter { id -> ws.checks.forAcceptance(id).any { currencies[it.id]?.certifies == true } }.toSet()

        private fun Map<String, Currency>.certifiedReceipt(acceptanceId: String): String? =
            ws.checks.forAcceptance(acceptanceId).firstNotNullOfOrNull { check -> this[check.id]?.takeIf { it.certifies }?.receiptId }

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
                Outcome.Unavailable -> CheckState.Unavailable("runner missing")
                Outcome.UnknownOutcome -> CheckState.Unavailable("unknown outcome; reconcile before retry")
                else -> CheckState.Inconclusive(last.outcome.name.lowercase())
            }
            CheckLine(labelOf(check), if (check.selector == Selector.Touched) "touched" else null, null, state, last.stamp.hash8, ws.scheduler.aliasOf(last.receiptId))
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

        private fun labelOf(check: Check): String = when (check.kind) {
            CheckKind.Acceptance -> "accept ${check.acceptanceIds.joinToString("+")}"
            CheckKind.Type -> "types"
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
            executor.execute(call, context).also { outcome -> record.record(call, outcome) }
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

            override fun acceptGreen(accept: String): Boolean {
                val currencies = currencies(ws.stamper.stamp().id)
                return ws.checks.forAcceptance(accept).any { currencies[it.id]?.certifies == true } || currencies[accept]?.certifies == true
            }

            override val redChecks: Set<String> get() = ws.checks.all().filter { it.last?.outcome == Outcome.Failed }.map { it.id }.toSet()

            override val greenOps: Set<Int> get() = outcomes.filterValues { it.green }.keys

            override val appliedOps: Set<Int> get() = outcomes.filterValues { it.applied }.keys
        }
    }

    private companion object {
        /** The alias executors give a result that observed nothing new. */
        const val NO_ALIAS = "#-"

        /** §5.1 `[A]`: at most two nudge lines per turn. */
        const val MAX_NUDGES = 2

        /** How many of a rejection's details ride on its line. */
        const val DETAILS_IN_LINE = 2

        const val MILLIS_PER_SECOND = 1000.0

        val JSON = Json { encodeDefaults = true }
        val ITEMS = ListSerializer(Item.serializer())
        val PREIMAGES = ListSerializer(Preimage.serializer())
    }
}
