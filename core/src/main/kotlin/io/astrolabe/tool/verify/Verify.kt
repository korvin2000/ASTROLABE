package io.astrolabe.tool.verify

import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.EditHunk
import io.astrolabe.atlas.EditSet
import io.astrolabe.atlas.ImpactAssembly
import io.astrolabe.atlas.ImportGraph
import io.astrolabe.atlas.IndexTiers
import io.astrolabe.atlas.SymbolIndex
import io.astrolabe.verify.Blast
import io.astrolabe.verify.BlastSelection
import io.astrolabe.auth.ContentClass
import io.astrolabe.auth.InstructionShape
import io.astrolabe.auth.Redaction
import io.astrolabe.auth.RedactionConfig
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.evidence.FailedTest
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.Receipt
import io.astrolabe.evidence.RedactionMask
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.os.Command
import io.astrolabe.os.EnvPolicy
import io.astrolabe.os.Os
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
import io.astrolabe.tool.RuntimeFields
import io.astrolabe.tool.ToolCall
import io.astrolabe.tool.ToolExecutor
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOps
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.VerifyArgs
import io.astrolabe.tool.run.CommandMatch
import io.astrolabe.tool.run.DiagnosticsParser
import io.astrolabe.tool.run.EvidenceKinds
import io.astrolabe.tool.run.JUnitReports
import io.astrolabe.tool.run.Executions
import io.astrolabe.tool.run.RunCapture
import io.astrolabe.tool.run.Runner
import io.astrolabe.tool.run.ShapeBudget
import io.astrolabe.tool.run.Shaped
import io.astrolabe.tool.run.Shapers
import io.astrolabe.tool.run.namesWorkspaceRoot
import io.astrolabe.verify.Applicability
import io.astrolabe.verify.Baseline
import io.astrolabe.verify.CampaignReview
import io.astrolabe.verify.CampaignReviewOutcome
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.CheckLine
import io.astrolabe.verify.CheckState
import io.astrolabe.verify.Checker
import io.astrolabe.verify.Checks
import io.astrolabe.verify.ChecksRender
import io.astrolabe.verify.Currency
import io.astrolabe.verify.Executed
import io.astrolabe.verify.Layer
import io.astrolabe.verify.Layers
import io.astrolabe.verify.PreexistingLedger
import io.astrolabe.verify.Regressions
import io.astrolabe.verify.Scheduler
import io.astrolabe.verify.Selector
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.Workspace
import io.astrolabe.workspace.WorkspacePath
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.Intent
import io.astrolabe.delegate.IncrementReview
import io.astrolabe.delegate.ReviewOutcome
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `verify` family (§5.4, TODO P1.6.7): `check(paths?)` runs the end-of-turn checker now; `tests(selection =
 * accept | ids | full)` and `acceptance(ids?)` execute registered checks through the scheduler's exclusive
 * protocol and record receipts with stamps and currency; `baseline()` records the baseline receipt on the
 * captured initial candidate; `review(scope=campaign)` is the human review path (P3.5.2, D-23), `review(scope=increment)`
 * the review cell (P4.4.3); `blast` runs `CHK-tests-blast` from the impact analysis of the touched paths (P3.2.5). Every executed check
 * yields a receipt — a runner that cannot start yields an explicit `unavailable` one (FX-13) — and the result
 * is rendered as the `── Checks ──` block plus each shaped view. Status words are the runner's, never the model's.
 */
public class Verify(
    private val checks: Checks,
    private val scheduler: Scheduler,
    private val checker: Checker?,
    private val baseline: Baseline?,
    private val s0: CandidateId?,
    private val workspace: Workspace,
    private val runner: Runner,
    private val os: Os,
    private val stamper: Stamper,
    private val blobs: BlobStore,
    private val redaction: Redaction,
    private val estimator: TokenEstimator,
    private val idGen: IdGen,
    private val ids: Identities,
    private val contracts: Contracts,
    private val logsDir: Path,
    private val mask: ToolMask = ToolOps.implementingS0,
    private val timeoutSeconds: Long = 600,
    private val checkerTimeBoxSeconds: Long = 20,
    private val checkerFallbackTimeBoxSeconds: Long = 120,
    private val envAllowlist: Set<String> = RedactionConfig.DEFAULT_ENV_ALLOWLIST,
    /** The campaign-scope human review path `review(scope=campaign)` routes to (P3.5.2, D-23); `null` ⇒ unavailable. */
    private val campaignReview: CampaignReview? = null,
    /** `review(scope=increment)`: the review cell (P4.4.3, S2+); `null` ⇒ unavailable. */
    private val incrementReview: IncrementReview? = null,
    /** Where the blast selection's import graph takes its outlines: tier 0, or a host tier-1 index (D-251). */
    private val tiers: IndexTiers = IndexTiers.TIER_0,
) : ToolExecutor {
    internal var beforeDispatch: () -> Unit = {}
        set(value) {
            field = value
            checker?.beforeDispatch = value
            baseline?.beforeDispatch = value
        }

    /** C3r: the whole seconds of active time a task's minutes limit leaves, read at each dispatch; `null` without one. */
    internal var timeLeft: () -> Long? = { null }

    /** [seconds] cut to [left], the active time a minutes limit leaves (C3r). */
    private fun cut(seconds: Long, left: Long?): Long = if (left == null) seconds else minOf(seconds, left)
    init {
        require(ids.context != null) { "verify runs inside a cell: ids.context is its lineage" }
        require(timeoutSeconds > 0 && checkerTimeBoxSeconds > 0 && checkerFallbackTimeBoxSeconds > 0) { "timeouts must be positive" }
    }

    /** The paths touched since the checker last ran; the cell keeps it current (`Coherence.takeScheduled`). */
    public var touched: Collection<String> = emptyList()

    /** The enumerated tree for checks with an unknown closure (the atlas rows); the cell keeps it current. */
    public var inputs: Collection<String> = emptyList()

    /** The campaign's current atlas; the blast selection builds its import graph from it (P3.2.5). */
    public var atlas: Atlas? = null

    /** The requirements the cell's increment serves: what the model's own check strengthens (C1a); the cell sets it, and none means no model check (C1b). */
    internal var requirementIds: List<String> = emptyList()

    private var graphOf: Pair<Atlas, ImportGraph>? = null

    /**
     * §7.3 blast radius of [touched]: registers (or replaces) `CHK-tests-blast`, the test command of `CHK-full` narrowed
     * to the blast's tests, or widened to the package or workspace suite when the graph is incomplete.
     */
    public fun selectBlast(): BlastSelection {
        val atlas = atlas ?: return BlastSelection.NotSelected("blast radius: no atlas for this candidate")
        val test = checks[Checks.FULL]?.command ?: return BlastSelection.NotSelected("blast radius: no test command declared by the repository")
        if (touched.isEmpty()) return BlastSelection.NotSelected("blast radius: nothing touched")
        val graph = graphOf?.takeIf { it.first === atlas }?.second ?: tiers.graph(atlas, workspace.id).also { graphOf = atlas to it }
        val others = checks.all().filter { it.id != Checks.TESTS_BLAST }
        val analysis = ImpactAssembly(graph, SymbolIndex(atlas)).analyze(EditSet(touched.toSet()), others, contracts = null).analysis
        val selection = Blast.select(analysis, test, { files -> graph.testsFor(files.map { it.path }) }) { scope ->
            scope.packageId?.takeIf { graph.scopeOfDirectory(it) == scope && graph.filesUnder(it).isNotEmpty() }
        }
        if (selection is BlastSelection.Selected) checks.replace(selection.check)
        return selection
    }

    override suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome {
        require(call.family == ToolFamily.Verify) { "not a verify call: ${call.name}" }
        val args = (call.args as Args.Verify).args
        if (!mask.allows(call.name)) return refused(args, "masked", "${call.name} is masked in this role")
        val contract = contracts.current(ids.work) ?: return refused(args, "denied", "no committed contract for ${ids.work}")
        return when (args.what) {
            "check" -> check(args, contract)
            "tests" -> tests(args, contract)
            "acceptance" -> acceptance(args, contract)
            "baseline" -> baselineRun(args, contract)
            "review" -> review(args, contract)
            else -> refused(args, "masked", "${call.name} is masked in this role")
        }
    }

    /**
     * `review(scope=campaign)` in its human form (P3.5.2, D-23): the host authority receives the full diff `s0 → now`,
     * the contract, the current receipts and the rubric; its signed verdict is recorded and the campaign gate reuses an
     * approving one at the same stamp. No reviewer ⇒ `unavailable`, never a pass. Increment scope is the review cell (P4.4.3).
     */
    private suspend fun review(args: VerifyArgs, contract: Contract): ToolOutcome {
        val scope = args.scope ?: "campaign"
        if (scope == "increment") return incrementReview(args)
        if (scope != "campaign") return refused(args, "denied", "review scope is increment or campaign, got '$scope'")
        val reviewer = campaignReview ?: return refused(args, "unavailable", "no campaign review path is wired for this cell")
        val base = s0 ?: return refused(args, "unavailable", "no captured initial candidate: the review has no diff base")
        val stamp = stamper.report().candidateId
        val currencies = checks.all().filter { it.last != null }.associate { it.id to scheduler.currency(it, stamp) }
        val equivalence = reviewer.equivalence(stamp, currencies)
        val current = currencies.values.filter { it.certifies }.mapNotNull { it.receiptId }.distinct()
        val outcome = reviewer.review(contract, base, current, equivalence, why = "review requested by the cell")
        val record = outcome.record
        val head = "── Review ──\ncampaign review ${record.request.id} @${stamp.hash8}: " + when (outcome) {
            is CampaignReviewOutcome.Approved -> "approve by ${record.verdict!!.signedBy} (confidence ${record.verdict.confidence})" + (if (record.reused) " · reused" else "")
            is CampaignReviewOutcome.Declined -> outcome.reason
            is CampaignReviewOutcome.Unavailable -> "unavailable — ${outcome.reason}"
        }
        val body = head + "\n  diff: #${record.request.diffRef?.take(8) ?: "-"} · receipts: ${current.ifEmpty { listOf("none current") }.joinToString(", ")}" +
            (equivalence?.let { "\n" + it.render() } ?: "")
        val status = when (outcome) {
            is CampaignReviewOutcome.Approved -> "ok"
            is CampaignReviewOutcome.Declined -> "declined"
            is CampaignReviewOutcome.Unavailable -> "unavailable"
        }
        return refused(args, status, body)
    }

    /** `review(scope=increment)` ⇒ the review cell (§8.8), whose human fallback is `Authority.review`; a current approval is reused. */
    private suspend fun incrementReview(args: VerifyArgs): ToolOutcome {
        val reviewer = incrementReview ?: return refused(args, "unavailable", "no increment review cell is wired for this cell (review cells run in S2+)")
        val outcome = reviewer.review("review requested by the cell")
        val record = outcome.record
        val (status, text) = when (outcome) {
            is ReviewOutcome.Approved -> "ok" to "approve by ${record.verdict!!.signedBy}" + (if (record.reused) " · reused" else "")
            is ReviewOutcome.Declined -> "declined" to outcome.reason
            is ReviewOutcome.Unavailable -> "unavailable" to "unavailable — ${outcome.reason}"
        }
        val findings = record.verdict?.findings.orEmpty().joinToString("") { "\n  ${it.severity.name.lowercase()} ${it.location}: ${it.issue}" + (it.suggestedFix?.let { fix -> " → $fix" } ?: "") }
        return refused(args, status, "── Review ──\nincrement review ${record.packetId} @${record.candidate.hash8}: $text$findings")
    }

    // ------------------------------------------------------------------ ops

    private suspend fun check(args: VerifyArgs, contract: Contract): ToolOutcome {
        val runner = checker ?: return refused(args, "unavailable", "no end-of-turn checker is configured for this cell")
        val paths = args.paths?.takeIf { it.isNotEmpty() } ?: touched
        if (paths.isEmpty()) return refused(args, "ok", "nothing touched: no check to run")
        val left = timeLeft()
        if (left != null && left <= 0) return refused(args, "denied", io.astrolabe.budget.NO_ACTIVE_TIME)
        val results = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { runner.run(paths, cut(checkerTimeBoxSeconds, left), cut(checkerFallbackTimeBoxSeconds, left)) }
        if (results.isEmpty()) return refused(args, "unavailable", "no type or lint runner is registered for this repository")
        val receipts = results.map { scheduler.record(it, contract.version) }
        val lines = results.zip(receipts).map { (result, receipt) -> result.line(scheduler.aliasOf(receipt.receiptId)) }
        val stamp = receipts.last().stampAfter
        val body = ChecksRender.render(stamp, lines, maxLines = lines.size.coerceAtLeast(1)) + "\n" + results.joinToString("\n") { r -> "  ${r.checkId}: " + (r.errorLines.take(5).joinToString(" · ").ifEmpty { r.reason ?: "no error-shaped lines" }) }
        return outcome(args, "ok", body, receipts, stamp)
    }

    private suspend fun tests(args: VerifyArgs, contract: Contract): ToolOutcome {
        val selected: List<Check> = when (args.selection ?: "accept") {
            "accept" -> return acceptance(args.copy(ids = null), contract)
            "full" -> listOfNotNull(checks[Checks.FULL])
            "ids" -> {
                val wanted = args.ids ?: return refused(args, "denied", "tests(selection=ids) needs ids")
                val unknown = wanted.filter { checks[it] == null }
                if (unknown.isNotEmpty()) return refused(args, "denied", "unknown check ids: ${unknown.joinToString(", ")}")
                wanted.map { checks[it]!! }
            }
            "blast" -> when (val blast = selectBlast()) {
                is BlastSelection.Selected -> listOf(checks[Checks.TESTS_BLAST]!!)
                is BlastSelection.NotSelected -> return refused(args, "unavailable", "${blast.reason}; name ids or run accept/full")
            }
            else -> return refused(args, "denied", "unknown tests selection '${args.selection}'")
        }
        if (selected.isEmpty()) return refused(args, "unavailable", "no check matches selection '${args.selection ?: "accept"}'")
        return runAll(args, contract, selected)
    }

    private suspend fun acceptance(args: VerifyArgs, contract: Contract): ToolOutcome {
        val wanted = args.ids ?: contract.acceptance.filterIsInstance<Acceptance.Run>().map { it.id }
        val unknown = wanted.filter { id -> contract.acceptance(id) !is Acceptance.Run }
        if (unknown.isNotEmpty()) return refused(args, "denied", "not run: acceptance items of contract v${contract.version}: ${unknown.joinToString(", ")}")
        val missing = wanted.filter { checks.forAcceptance(it).isEmpty() }
        if (missing.isNotEmpty()) return refused(args, "unavailable", "no registered check executes ${missing.joinToString(", ")}")
        val selected = wanted.flatMap { checks.forAcceptance(it) }.distinctBy { it.id }
        if (selected.isEmpty()) return refused(args, "unavailable", "no registered check executes ${wanted.joinToString(", ")}")
        return runAll(args, contract, selected)
    }

    private suspend fun baselineRun(args: VerifyArgs, contract: Contract): ToolOutcome {
        val runner = baseline ?: return refused(args, "unavailable", "no baseline is configured for this cell (captured initial candidate missing)")
        val stamp = s0 ?: return refused(args, "unavailable", "the initial candidate stamp s0 is unknown")
        val suite = checks[Checks.FULL] ?: checks.all().firstOrNull { it.kind == CheckKind.Full } ?: return refused(args, "unavailable", "no full-suite check is registered; the baseline has nothing to run")
        val left = timeLeft()
        if (left != null && left <= 0) return refused(args, "denied", io.astrolabe.budget.NO_ACTIVE_TIME)
        val result = runner.run(suite, contract.version, stamp, cut(timeoutSeconds, left))
        val receipt = result.receipt
        val line = ChecksRender.line(lineOf(suite, receipt, null, scheduler.aliasOf(receipt.receiptId)))
        val body = "baseline @${stamp.hash8.take(4)}: $line" + (result.ledger?.let { "\n" + it.render() } ?: "\nno pre-existing-failure ledger: the baseline produced no usable evidence (${receipt.outcome.name.lowercase()})")
        return outcome(args, if (result.ledger == null) "unavailable" else "ok", body, listOf(receipt), stamp)
    }

    /**
     * Runs one scheduled row of the §8.1 layer table ([Layers.select]) on the harness's authority: only checks of
     * [acceptanceIds] (and the layer's own checks) with no receipt or without a current, eligible one at the tree
     * now; a receipt kept current by a reuse proof stands, and a current red one is evidence too.
     */
    public suspend fun runLayer(layer: Layer, acceptanceIds: Collection<String> = emptyList()): LayerRun {
        val contract = contracts.current(ids.work) ?: return LayerRun(layer, emptyList(), listOf("no committed contract for ${ids.work}"))
        val stampNow = stamper.stamp().id
        val blast = if (layer == Layer.BlastAndStepAccept || layer == Layer.IntegrationReverification) selectBlast() else null
        val selection = Layers.select(layer, checks, acceptanceIds, (blast as? BlastSelection.NotSelected)?.reason ?: "blast radius: not selected") { check ->
            val currency = scheduler.currency(check, stampNow)
            currency.receiptId == null || currency.applicability != Applicability.Current || (!currency.eligible && !settledUnverified(check, stampNow))
        }
        return LayerRun(layer, selection.run.map { runTriaged(it, contract).first }, selection.notTested)
    }

    /**
     * The `risk > θ` row of the §8.1 layer table (§7.4 verification depth): when this turn's edit [hunks] carry an impact
     * risk estimate above θ the blast layer runs now instead of at the next step boundary. Only a numeric estimate above
     * θ fires; an unknown risk waits for the step boundary and verify-on-stop (D-152). `null` when it does not fire.
     */
    public suspend fun riskAboveTheta(hunks: List<EditHunk>): LayerRun? {
        val atlas = atlas ?: return null
        if (hunks.isEmpty()) return null
        val graph = graphOf?.takeIf { it.first === atlas }?.second ?: tiers.graph(atlas, workspace.id).also { graphOf = atlas to it }
        val edits = EditSet(hunks.mapTo(LinkedHashSet()) { it.path }, hunks)
        val risk = ImpactAssembly(graph, SymbolIndex(atlas)).analyze(edits, checks.all(), contracts = null).analysis.risk
        val above = risk.exceedsThreshold == true || (risk.estimate ?: 0.0) > risk.threshold
        return if (above) runLayer(Layer.BlastAndStepAccept) else null
    }

    /**
     * I3 (D-341): a check that already ran on this very candidate and could not verify it — timed out, found no runner,
     * hit an infrastructure error, was denied or inconclusive — is a stored unverified result; the harness does not
     * schedule it again for the same tree. An explicit `verify` call by the model still runs it.
     */
    private fun settledUnverified(check: Check, stampNow: CandidateId): Boolean {
        val last = checks[check.id]?.last ?: return false
        return last.stamp == stampNow && last.outcome in SETTLED_UNVERIFIED
    }

    /**
     * Verify-on-stop (§8.1, P3.1.3): the increment's acceptance on a completion proposal; never the full suite. Then, before
     * the decision, the baseline of every held red of the blast radius or the types of touched files that none classifies
     * yet (P8.C.10): its command once on `s0`, per definition and attempt; without a baseline runner nothing runs and the
     * red stays unclassified.
     */
    public suspend fun onStop(acceptanceIds: Collection<String>): LayerRun {
        // P8.C.12: a live background run may still change the tree, so the cell's runs are settled first; one still live
        // after its cancellation leaves every receipt uncertifying until a later stop finds the tree quiet.
        scheduler.unquiet = settleRuns?.invoke().orEmpty()
        return runLayer(Layer.IncrementAcceptance, acceptanceIds).also { settleBaselines() }
    }

    /** P8.C.12: settles the cell's live background runs before its stop's verification (the `run` tool sets it); returns those still live. */
    internal var settleRuns: (suspend () -> List<String>)? = null

    /** P8.C.10 п. 3: the baseline receipts verify-on-stop records; they are the check's history, never its last result. */
    private suspend fun settleBaselines(): List<Receipt> {
        val runner = baseline ?: return emptyList()
        val stamp = s0 ?: return emptyList()
        val contract = contracts.current(ids.work) ?: return emptyList()
        return Regressions.CHECKS.mapNotNull { checks[it] }.flatMap { check ->
            scheduler.unbaselined(check).mapNotNull { red ->
                // A baseline that cannot be exported leaves the red unclassified (the D-400 rule), never the stop broken.
                try {
                    runner.run(check.copy(command = io.astrolabe.contract.Command(red.command, red.cwd)), contract.version, stamp, timeoutSeconds).receipt
                } catch (failure: IOException) {
                    null
                } catch (failure: IllegalStateException) {
                    null
                } catch (failure: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    // --------------------------------------------------------------- running

    private suspend fun runAll(args: VerifyArgs, contract: Contract, selected: List<Check>): ToolOutcome {
        val receipts = ArrayList<Receipt>()
        val views = ArrayList<String>()
        for (check in selected) {
            val (receipt, view) = runTriaged(check, contract)
            receipts += receipt
            views += view
        }
        val stampNow = stamper.stamp().id
        val lines = selected.zip(receipts).map { (check, receipt) -> lineOf(check, receipt, scheduler.currency(check, stampNow), scheduler.aliasOf(receipt.receiptId)) }
        val body = ChecksRender.render(stampNow, lines, maxLines = lines.size.coerceAtLeast(1)) + "\n" + views.joinToString("\n")
        // The status is the worst runner outcome of the batch, in the §8.4 vocabulary; never "ok" over a red check.
        val worst = SEVERITY.firstOrNull { severity -> receipts.any { it.outcome == severity } } ?: Outcome.Passed
        return outcome(args, wire(worst), body, receipts, stampNow)
    }

    /**
     * §8.10 flaky policy: a failed check is rerun once, alone; two disagreeing outcomes are `inconclusive` (a third
     * receipt citing both), never the favourable one, and nothing reruns again. Every attempt stays a receipt.
     */
    private suspend fun runTriaged(check: Check, contract: Contract): Pair<Receipt, String> {
        val (first, view) = checkNotNull(runOne(check, contract))
        if (first.outcome != Outcome.Failed) return first to view
        val (second, again) = runOne(check, contract, first)
            ?: return first to "$view\n  ${check.id}: isolated retry unavailable for the original candidate and environment; first failure retained"
        if (second.outcome == first.outcome) return second to "$view\n$again"
        val flaky = scheduler.flaky(check, contract.version, first, second)
        return flaky to "$view\n$again\n  ${check.id}: flaky — ${first.outcome.name.lowercase()} then ${second.outcome.name.lowercase()} ⇒ inconclusive; record an Open item (state patch open.add) before relying on it"
    }

    private suspend fun runOne(check: Check, contract: Contract, retryOf: Receipt? = null): Pair<Receipt, String>? {
        val command = check.command ?: return scheduler.runCheck(check, contract.version, inputs) {
            Executed(listOf(check.id), null, false, null, Outcome.Unavailable, null, null, listOf("check ${check.id} declares no command"))
        } to "  ${check.id}: unavailable (no command)"
        var view = ""
        val execute: suspend (Path) -> Executed = execution@ { root ->
            val modelAdded = check.acceptanceIds.any { contract.acceptance(it)?.origin is Origin.Model }
            val approvedCommand = contract.acceptance.filterIsInstance<Acceptance.Run>().any { it.origin !is Origin.Model && it.command == command }
            // D-262: adding an obligation never grants authority to launch a new executable command.
            val refusal = when {
                contracts.current(ids.work)?.version != contract.version -> "contract changed before verification dispatch"
                // Plan §4.4: the model's own check launches only through `run`, under the effect policy.
                check.id.startsWith(Checks.MODEL_PREFIX) -> "${check.id} is the model's own check: it runs through run(${command.argv.joinToString(" ")}) under the effect policy, never on verify's authority (D-262)"
                modelAdded && !approvedCommand -> "model-added verification command needs explicit host/user authorization"
                else -> null
            }
            if (refusal != null) {
                view = "  ${check.id}: denied — $refusal"
                return@execution Executed(command.argv, command.cwd, false, null, Outcome.Denied, null, null, listOf(refusal))
            }
            val invocation = invoke(check, command, root, idGen.next("act"), timeoutSeconds, ShapeBudget(estimator = estimator))
            view = "  ${check.id}: " + invocation.view.lines().joinToString("\n  ")
            invocation.executed
        }
        val receipt = if (retryOf == null) scheduler.runCheck(check, contract.version, inputs, execute)
            else scheduler.retryIsolated(check, contract.version, retryOf, inputs, execute) ?: return null
        return receipt to view
    }

    /**
     * One invocation of [check]'s [command] in [root] (§8.4): started by the runner, observed to its end, its output kept
     * as a redacted log blob and shaped once at the boundary with the check's identity (D-27, D-50). Refusals are the
     * caller's; a runner that cannot start is `unavailable` (FX-13).
     */
    private suspend fun invoke(check: Check, command: io.astrolabe.contract.Command, root: Path, actionId: String, timeoutSeconds: Long, budget: ShapeBudget): Invocation {
        val cwd = when (val path = command.cwd) {
            null -> root
            else -> if (namesWorkspaceRoot(path)) root else (WorkspacePath.of(root).resolve(path, Intent.Read) as? PathResolution.Resolved)?.real
        }
        if (cwd == null || !Files.isDirectory(cwd)) {
            return Invocation(Executed(command.argv, command.cwd, false, null, Outcome.Denied, null, null, listOf("working directory refused")), "denied — working directory must be a directory inside the verification workspace")
        }
        // C3r: the check's deadline is cut at dispatch to the active time a minutes limit leaves; with none left it is not run.
        val left = timeLeft()
        if (left != null && left <= 0) {
            return Invocation(Executed(command.argv, command.cwd, false, null, Outcome.NotRun, null, null, listOf(io.astrolabe.budget.NO_ACTIVE_TIME)), "not run — ${io.astrolabe.budget.NO_ACTIVE_TIME}")
        }
        val deadline = cut(timeoutSeconds, left)
        val reports = JUnitReports.forCommand(cwd, command.argv, actionId)
        val proc = try {
            beforeDispatch()
            reports?.prepare(logsDir.resolve("reports-$actionId"))
            runner.start(SpawnSpec(Command.Argv(command.argv), cwd, logPath(check.id, actionId), EnvPolicy(inheritedNames = envAllowlist, extra = mapOf("CI" to "1", "NO_COLOR" to "1")), deadline))
        } catch (failure: IOException) {
            val reason = "cannot start ${command.argv.first()}: ${failure.message}"
            return Invocation(Executed(command.argv, command.cwd, false, null, Outcome.Unavailable, null, null, listOf(reason)), "unavailable — $reason")
        }
        val observed = Executions.observeCancellable(os, proc, POLL_SLICE_SECONDS, deadline)
        // D-390: the capture is a live stream, so a key block it opens and never closes stays hidden in the stored log.
        val safeLog = redaction.applyLive(observed.output, ContentClass.ReusableEvidence, openAtEnd = false)
        val blob = blobs.put(safeLog.text.toByteArray(Charsets.UTF_8), BlobKind.LOG, ids)
        val lost = observed.lost || observed.proc.status is ProcStatus.Lost
        val collected = try { reports?.collect().orEmpty() } catch (failure: IOException) {
            return Invocation(Executed(command.argv, command.cwd, false, null, Outcome.Inconclusive, null, blob, listOf("report capture failed: ${failure.message}")), "report capture failed: ${failure.message}", lost = lost, mask = safeLog.mask)
        }
        val capture = RunCapture(
            reports = collected,
            actionId = actionId, argv = command.argv, shell = false, cwd = command.cwd,
            executionRoot = runCatching { cwd.toRealPath() }.getOrDefault(cwd.toAbsolutePath()).toString(),
            exitCode = (observed.proc.status as? ProcStatus.Exited)?.exitCode, timedOut = observed.proc.status == ProcStatus.DeadlineExceeded,
            output = observed.output, captureComplete = !observed.lost && !observed.truncated && observed.proc.status !is ProcStatus.Lost, checkId = check.id, selector = check.selector.toString(),
        )
        val shaped = Shapers.shape(capture, budget)
        return Invocation(executedOf(check, command, capture, shaped, lost, blob, safeLog.limitations), shaped.view, shaped, capture, lost, safeLog.mask)
    }

    /** The scheduler's record of a shaped invocation: the runner's outcome, or a host or user build or typecheck passing on its exit. */
    private fun executedOf(check: Check, command: io.astrolabe.contract.Command, capture: RunCapture, shaped: Shaped, lost: Boolean, blob: Digest?, limits: List<String>): Executed {
        val passes = !lost && passesOnExit(check, capture, shaped)
        val outcome = when {
            lost -> Outcome.UnknownOutcome
            capture.timedOut -> Outcome.Timeout
            passes -> Outcome.Passed
            else -> shaped.status
        }
        val note = if (passes) listOf("declared ${check.evidence?.wire} evidence of a host or user command: exit ${capture.exitCode}, no test counts (plan §4.4, D-50 relaxed)") else emptyList()
        // P8.C.10: the failing identities, so a red can be compared with the baseline's; the signature is stored redacted.
        val failures = shaped.tests.filter { it.failing }.map { FailedTest(it.identity, redaction.apply(PreexistingLedger.signatureOf(it), ContentClass.ReusableEvidence).text) }
        return Executed(command.argv, command.cwd, false, capture.exitCode, outcome, shaped.counts, blob, shaped.limitations + limits + note, failures = failures)
    }

    /**
     * Plan §4.4 (D-50 relaxed by the owner): a build or a typecheck the host or the user declared passes on its exit 0 alone
     * — no exit-hiding wrapper, nothing lost or cut from the capture, and no error diagnostic from a typecheck tool the
     * harness can parse. A recognised kind is a label only (`gradle build` and `mvn install` run tests too); tests still
     * need parsed counts, and the model's own check never passes this way.
     */
    private fun passesOnExit(check: Check, capture: RunCapture, shaped: Shaped): Boolean {
        val kind = check.evidence ?: return false
        if (!kind.exitSuffices || check.origin is Origin.Model) return false
        if (shaped.status != Outcome.Inconclusive || shaped.counts != null || shaped.wrapper != null) return false
        // A command line whose exit is not its one command's (`false; exit 0`) proves nothing by its exit.
        if (!CommandMatch.exitPropagates(capture.argv)) return false
        if (capture.exitCode != 0 || capture.timedOut || !capture.captureComplete || shaped.captureTruncated) return false
        val diagnostics = DiagnosticsParser.parse(capture.argv, capture.text(), capture.exitCode) ?: return true
        return diagnostics.errorCount == 0 && (diagnostics.summaryErrors ?: 0) == 0
    }

    // ----------------------------------------------------------- run · C1a

    /**
     * C1a (plan §4.4): the registered checks a `run` of [requested] in [cwd] realizes, recognised before dispatch so the
     * scheduler runs the command once. A declared check — a `run:` item of the host or the user, the sniffed suite, its
     * blast narrowing, a quality gate — matches on the normalized command and the directory ([CommandMatch]). Otherwise,
     * with [modelChecks], a test, build or typecheck command becomes the model's own check ([Checks.modelCheck]): an agent
     * test, never an acceptance item nor a required check — returned unregistered, and [adopt]ed only once the run passed
     * its gates. Recognition reads records only and grants no authority; every status stays the runner's and the
     * scheduler's (L9). Empty: a plain run.
     */
    internal fun recognize(requested: List<String>, shell: Boolean, cwd: String?, contract: Contract, modelChecks: Boolean): List<Check> {
        val tokens = CommandMatch.tokens(requested, shell) ?: return emptyList()
        val dir = directoryOf(cwd) ?: return emptyList()
        fun realizes(check: Check) = check.command?.let { CommandMatch.matches(tokens, it.argv) && directoryOf(it.cwd) == dir } == true
        val matching = checks.all().filter { declared(it, contract) && realizes(it) }
        // One execution is the receipt of exactly the declared command it ran: another declaration shares it only verbatim.
        val first = matching.firstOrNull()?.command
        val declared = matching.filter { it.command!!.argv == first!!.argv && directoryOf(it.command.cwd) == directoryOf(first.cwd) }
        if (declared.isNotEmpty() || !modelChecks) return declared
        checks.all().firstOrNull { it.id.startsWith(Checks.MODEL_PREFIX) && realizes(it) }?.let { return listOf(it) }
        val kind = EvidenceKinds.recognize(tokens) ?: return emptyList()
        // C1b: never every requirement of the contract — without the increment's requirements the run stays plain.
        val strengthens = requirementIds.joinToString("+").ifEmpty { return emptyList() }
        val check = Checks.modelCheck(io.astrolabe.contract.Command(tokens, dir.ifEmpty { null }), kind, strengthens)
        // An id taken by another command (a digest collision) is never reused: that run stays plain.
        val known = checks[check.id] ?: return listOf(check)
        return if (known.command == check.command) listOf(known) else emptyList()
    }

    /** Registers the model's own check of a recognised run that passed its gates (C1a): a refused run leaves no check behind. */
    internal fun adopt(recognized: List<Check>) {
        recognized.filter { checks[it.id] == null }.forEach { checks.register(it) }
    }

    /** Declared by the host or the user (plan §4.4): `run:` items of another origin than the model's, the sniffed suite and its blast narrowing, quality gates. */
    private fun declared(check: Check, contract: Contract): Boolean {
        if (check.command == null || check.origin is Origin.Model || check.selector == Selector.Touched) return false
        return when (check.kind) {
            CheckKind.Acceptance -> check.acceptanceIds.isNotEmpty() && check.acceptanceIds.all { id -> (contract.acceptance(id) as? Acceptance.Run)?.origin.let { it != null && it !is Origin.Model } }
            CheckKind.Full, CheckKind.Quality -> true
            else -> check.selector == Selector.Blast
        }
    }

    /** A command directory as a workspace-relative path, `""` for the root; null when it is not a directory inside the workspace. */
    private fun directoryOf(cwd: String?): String? {
        if (cwd == null || namesWorkspaceRoot(cwd)) return ""
        val resolved = workspace.resolve(cwd, Intent.Read) as? PathResolution.Resolved ?: return null
        return resolved.relative.takeIf { Files.isDirectory(resolved.real) }
    }

    /**
     * C1a: the one execution of a recognised foreground `run` — in the workspace, under the scheduler's exclusive protocol
     * with a fresh stamp — and a receipt for each of [recognized] that shares its inputs; no flaky rerun follows it.
     */
    internal suspend fun runRecognized(recognized: List<Check>, contract: Contract, actionId: String, timeoutSeconds: Long, budget: ShapeBudget): RecognizedRun {
        val check = recognized.first()
        val command = checkNotNull(check.command) { "a recognised check declares a command" }
        var invocation: Invocation? = null
        val scheduled = scheduler.runInWorkspace(recognized, contract.version, inputs) { root ->
            invoke(check, command, root, actionId, timeoutSeconds, budget).also { invocation = it }.executed
        }
        return RecognizedRun(scheduled.receipts, checkNotNull(invocation), scheduled.changed)
    }

    /**
     * C1a, a recognised background `run`: pins [recognized]'s inputs and archives earlier JUnit reports before its launch;
     * null when the reports cannot be archived (a stale report must never read as this run's), and the run stays plain.
     */
    internal suspend fun pinRecognized(recognized: List<Check>, contract: Contract, actionId: String): PinnedRun? {
        val check = recognized.first()
        val command = check.command ?: return null
        val cwd = directoryOf(command.cwd)?.let { if (it.isEmpty()) workspace.root else workspace.root.resolve(it) } ?: return null
        val reports = JUnitReports.forCommand(cwd, command.argv, actionId)
        try {
            reports?.prepare(logsDir.resolve("reports-$actionId"))
        } catch (failure: IOException) {
            return null
        }
        return PinnedRun(scheduler.pin(recognized, inputs), check, command, contract.version, reports, runCatching { cwd.toRealPath() }.getOrDefault(cwd.toAbsolutePath()).toString())
    }

    /**
     * The receipts of a pinned background run that ended (C1a): [capture] (the run's own, raw: evidence is read before any
     * redaction) is bound to the check — its declared command, identity and fresh reports — shaped once, and recorded by
     * the scheduler after its rescan.
     */
    internal suspend fun settleRecognized(pinned: PinnedRun, capture: RunCapture, blob: Digest?, limits: List<String>, budget: ShapeBudget): SettledRun {
        val collected = try { pinned.reports?.collect().orEmpty() } catch (failure: IOException) { null }
        val bound = capture.copy(
            argv = pinned.command.argv, shell = false, cwd = pinned.command.cwd, reports = collected.orEmpty(),
            checkId = pinned.check.id, selector = pinned.check.selector.toString(), executionRoot = pinned.executionRoot,
        )
        val shaped = Shapers.shape(bound, budget)
        val executed = if (collected == null) {
            Executed(pinned.command.argv, pinned.command.cwd, false, null, Outcome.Inconclusive, null, blob, listOf("report capture failed"))
        } else {
            executedOf(pinned.check, pinned.command, bound, shaped, lost = false, blob, limits)
        }
        return SettledRun(scheduler.settle(pinned.pin, pinned.contractVersion, executed), shaped, bound)
    }

    /** A pinned background run that could not start: its checks still get an explicit `unavailable` receipt (FX-13). */
    internal suspend fun settleUnavailable(pinned: PinnedRun, reason: String): List<Receipt> =
        scheduler.settle(pinned.pin, pinned.contractVersion, Executed(pinned.command.argv, pinned.command.cwd, false, null, Outcome.Unavailable, null, null, listOf("cannot start: $reason")))

    /** One line per receipt for the `run` view (C1a): the check, its state at the stamp now, the receipt alias, and what it proves. */
    internal fun receiptLines(receipts: List<Receipt>): String {
        val stampNow = stamper.stamp().id
        return receipts.joinToString("\n") { receipt ->
            val check = checks[receipt.checkId]
                ?: return@joinToString "receipt ${receipt.checkId}: ${receipt.outcome.name.lowercase()} (${scheduler.aliasOf(receipt.receiptId) ?: receipt.receiptId})"
            val line = ChecksRender.line(lineOf(check, receipt, scheduler.currency(check, stampNow), scheduler.aliasOf(receipt.receiptId)))
            val proves = receipt.evidenceKind?.let { kind ->
                when {
                    !receipt.independent -> "${kind.wire} · the model's own check: an agent test, not independent acceptance"
                    receipt.passesOnExit && receipt.outcome == Outcome.Passed && receipt.parsed == null -> "${kind.wire} passes on exit ${receipt.exitCode} (no test counts)"
                    else -> kind.wire
                }
            }
            val background = receipt.limits.any { it.kind == "input_stability" && it.detail.startsWith(Scheduler.BACKGROUND) }
            "receipt ${receipt.checkId}: $line" + (proves?.let { " · $it" } ?: "") +
                (if (background) " · a background run is evidence of its outcome, not of the final tree: a foreground run or the stop's verification certifies it" else "")
        }
    }

    // ---------------------------------------------------------------- render

    private fun lineOf(check: Check, receipt: Receipt, currency: Currency?, alias: String?): CheckLine {
        val counts = receipt.parsed
        val absolute = counts?.let { "${it.passed} pass ${it.failed} fail" + (if (it.errors > 0) " ${it.errors} err" else "") + (if (it.skipped > 0) " ${it.skipped} skip" else "") } ?: ""
        val state = when (receipt.outcome) {
            Outcome.Passed -> if (currency != null && !currency.certifies) CheckState.Stale(currency.reasons.joinToString("; ")) else CheckState.Green("✓ $absolute".trim())
            Outcome.Failed -> CheckState.Red(absolute, (counts?.failed ?: 0) + (counts?.errors ?: 0))
            Outcome.Timeout -> CheckState.Timeout(receipt.limits.firstOrNull { it.kind == "timeout" }?.detail ?: "deadline")
            Outcome.Unavailable -> CheckState.Unavailable(receipt.limits.firstOrNull()?.detail ?: "runner missing")
            Outcome.UnknownOutcome -> CheckState.Unavailable("unknown outcome; reconcile before retry")
            else -> CheckState.Inconclusive(receipt.limits.firstOrNull()?.detail ?: receipt.outcome.name.lowercase())
        }
        val label = when {
            check.id.startsWith(Checks.MODEL_PREFIX) -> "agent ${check.evidenceKind?.wire ?: "check"}"
            check.selector == Selector.Blast -> "tests"
            check.kind == CheckKind.Acceptance -> "accept ${check.acceptanceIds.joinToString("+")}"
            check.kind == CheckKind.Type -> "types"
            check.kind == CheckKind.Full -> "full"
            else -> check.kind.name.lowercase()
        }
        return CheckLine(label, Blast.scope(check), null, state, receipt.stampAfter.hash8, alias)
    }

    private fun outcome(args: VerifyArgs, status: String, body: String, receipts: List<Receipt>, stamp: CandidateId?): ToolOutcome {
        val safe = redaction.apply(body)
        val moved = receipts.any { it.stampBefore != it.stampAfter }
        val header = EnvelopeHeader(
            resultAlias = receipts.lastOrNull()?.let { scheduler.aliasOf(it.receiptId) } ?: "#-", tool = "verify", effectClass = if (moved) EffectClass.W else EffectClass.R,
            versions = emptyMap(), stamp = stamp, truncated = safe.limitations.isNotEmpty(), effects = if (moved) Effects.Observed else Effects.None, flags = InstructionShape.detect(safe.text).flags,
            runtime = RuntimeFields(
                actionId = idGen.next("act"), status = status, candidateBefore = receipts.firstOrNull()?.stampBefore, candidateAfter = stamp,
                scope = args.what + (args.selection?.let { "($it)" } ?: "") + (args.ids?.let { " " + it.joinToString(",") } ?: ""), completeness = "complete",
                artifactRefs = receipts.mapNotNull { it.raw?.hex }, effectsObserved = if (moved) listOf("checks moved the tree") else emptyList(),
                effectsUnknown = receipts.any { it.outcome == Outcome.UnknownOutcome },
                redactionApplied = safe.applied, displayTruncated = safe.limitations.isNotEmpty(),
            ),
        )
        val green = stamp != null && receipts.isNotEmpty() && receipts.all { receipt ->
            receipt.greenForFinalTree && checks[receipt.checkId]?.let { scheduler.currency(it, stamp).certifies } == true
        }
        return ToolOutcome(safe.text, header, green = green, tokens = estimator.estimate(safe.text).tokens)
    }

    private fun refused(args: VerifyArgs, status: String, detail: String): ToolOutcome {
        val safe = redaction.apply(detail)
        val header = EnvelopeHeader(
            resultAlias = "#-", tool = "verify", effectClass = null, versions = emptyMap(), stamp = null, truncated = safe.limitations.isNotEmpty(), effects = Effects.None,
            runtime = RuntimeFields(idGen.next("act"), status, null, null, args.what + (args.selection?.let { "($it)" } ?: ""), if (safe.limitations.isEmpty()) "complete" else "truncated", redactionApplied = safe.applied),
        )
        return ToolOutcome(safe.text, header, tokens = estimator.estimate(safe.text).tokens)
    }

    private fun logPath(checkId: String, actionId: String): Path {
        Files.createDirectories(logsDir)
        return logsDir.resolve("$checkId-$actionId.log")
    }

    private fun wire(outcome: Outcome): String = outcome.name.replace(Regex("(?<=[a-z])([A-Z])")) { "_" + it.value }.lowercase()

    private companion object {
        const val POLL_SLICE_SECONDS = 5L

        /** Worst first: an unknown outcome outranks a missing runner, which outranks a plain failure. */
        val SEVERITY = listOf(Outcome.UnknownOutcome, Outcome.Unavailable, Outcome.Timeout, Outcome.InfraError, Outcome.Failed, Outcome.Denied, Outcome.Inconclusive, Outcome.NotRun)

        /** Outcomes that are a final unverified result for their candidate (D-341); `NotRun` and `UnknownOutcome` are not. */
        val SETTLED_UNVERIFIED = setOf(Outcome.Timeout, Outcome.Unavailable, Outcome.InfraError, Outcome.Denied, Outcome.Inconclusive)
    }
}

/** What [Verify.runLayer] recorded: the receipts of the checks it ran, and what the layer could not test. */
public data class LayerRun(val layer: Layer, val receipts: List<Receipt>, val notTested: List<String>)

/** One invocation of a check's command: the scheduler's record, and what the caller shows of it. */
internal class Invocation(
    val executed: Executed,
    val view: String,
    val shaped: Shaped? = null,
    val capture: RunCapture? = null,
    val lost: Boolean = false,
    val mask: RedactionMask = RedactionMask.NONE,
)

/** A recognised foreground `run` (C1a): the receipts of its one execution, the invocation, and the paths it moved. */
internal class RecognizedRun(val receipts: List<Receipt>, val invocation: Invocation, val changed: List<String>)

/** A recognised background `run` between its launch and its end (C1a): held in memory, so a restart records no receipt. */
internal class PinnedRun(
    val pin: Scheduler.Pin,
    val check: Check,
    val command: io.astrolabe.contract.Command,
    val contractVersion: Int,
    val reports: JUnitReports?,
    val executionRoot: String,
)

/** A pinned background run at its end: its receipts, and the capture bound to the check with its shaped view. */
internal class SettledRun(val receipts: List<Receipt>, val shaped: Shaped, val capture: RunCapture)
