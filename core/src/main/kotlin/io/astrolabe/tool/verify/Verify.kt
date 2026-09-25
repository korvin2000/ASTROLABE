package io.astrolabe.tool.verify

import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.EditSet
import io.astrolabe.atlas.ImpactAssembly
import io.astrolabe.atlas.ImportGraph
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
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.Receipt
import io.astrolabe.id.CandidateId
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
import io.astrolabe.tool.run.Executions
import io.astrolabe.tool.run.RunCapture
import io.astrolabe.tool.run.Runner
import io.astrolabe.tool.run.ShapeBudget
import io.astrolabe.tool.run.Shapers
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
import io.astrolabe.verify.Scheduler
import io.astrolabe.verify.Selector
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.Workspace
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
    private val envAllowlist: Set<String> = RedactionConfig.DEFAULT_ENV_ALLOWLIST,
    /** The campaign-scope human review path `review(scope=campaign)` routes to (P3.5.2, D-23); `null` ⇒ unavailable. */
    private val campaignReview: CampaignReview? = null,
    /** `review(scope=increment)`: the review cell (P4.4.3, S2+); `null` ⇒ unavailable. */
    private val incrementReview: IncrementReview? = null,
) : ToolExecutor {
    init {
        require(ids.context != null) { "verify runs inside a cell: ids.context is its lineage" }
        require(timeoutSeconds > 0 && checkerTimeBoxSeconds > 0) { "timeouts must be positive" }
    }

    /** The paths touched since the checker last ran; the cell keeps it current (`Coherence.takeScheduled`). */
    public var touched: Collection<String> = emptyList()

    /** The enumerated tree for checks with an unknown closure (the atlas rows); the cell keeps it current. */
    public var inputs: Collection<String> = emptyList()

    /** The campaign's current atlas; the blast selection builds its import graph from it (P3.2.5). */
    public var atlas: Atlas? = null

    private var graphOf: Pair<Atlas, ImportGraph>? = null

    /**
     * §7.3 blast radius of [touched]: registers (or replaces) `CHK-tests-blast`, the test command of `CHK-full` narrowed
     * to the blast's tests, or widened to the package or workspace suite when the graph is incomplete.
     */
    public fun selectBlast(): BlastSelection {
        val atlas = atlas ?: return BlastSelection.NotSelected("blast radius: no atlas for this candidate")
        val test = checks[Checks.FULL]?.command ?: return BlastSelection.NotSelected("blast radius: no test command declared by the repository")
        if (touched.isEmpty()) return BlastSelection.NotSelected("blast radius: nothing touched")
        val graph = graphOf?.takeIf { it.first === atlas }?.second ?: ImportGraph.of(atlas, workspace.id).also { graphOf = atlas to it }
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

    private fun check(args: VerifyArgs, contract: Contract): ToolOutcome {
        val runner = checker ?: return refused(args, "unavailable", "no end-of-turn checker is configured for this cell")
        val paths = args.paths?.takeIf { it.isNotEmpty() } ?: touched
        if (paths.isEmpty()) return refused(args, "ok", "nothing touched: no check to run")
        val results = runner.run(paths, checkerTimeBoxSeconds)
        if (results.isEmpty()) return refused(args, "unavailable", "no type or lint runner is registered for this repository")
        val receipts = results.map { scheduler.record(it, contract.version) }
        val lines = results.zip(receipts).map { (result, receipt) -> result.line(scheduler.aliasOf(receipt.receiptId)) }
        val stamp = receipts.last().stampAfter
        val body = ChecksRender.render(stamp, lines, maxLines = lines.size.coerceAtLeast(1)) + "\n" + results.joinToString("\n") { r -> "  ${r.checkId}: " + (r.errorLines.take(5).joinToString(" · ").ifEmpty { r.reason ?: "no error-shaped lines" }) }
        return outcome(args, "ok", body, receipts, stamp)
    }

    private suspend fun tests(args: VerifyArgs, contract: Contract): ToolOutcome {
        val selected: List<Check> = when (args.selection ?: "accept") {
            "accept" -> checks.required().filter { it.kind == CheckKind.Acceptance }
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
        val selected = wanted.flatMap { checks.forAcceptance(it) }.distinctBy { it.id }
        if (selected.isEmpty()) return refused(args, "unavailable", "no registered check executes ${wanted.joinToString(", ")}")
        return runAll(args, contract, selected)
    }

    private suspend fun baselineRun(args: VerifyArgs, contract: Contract): ToolOutcome {
        val runner = baseline ?: return refused(args, "unavailable", "no baseline is configured for this cell (captured initial candidate missing)")
        val stamp = s0 ?: return refused(args, "unavailable", "the initial candidate stamp s0 is unknown")
        val suite = checks[Checks.FULL] ?: checks.all().firstOrNull { it.kind == CheckKind.Full } ?: return refused(args, "unavailable", "no full-suite check is registered; the baseline has nothing to run")
        val result = runner.run(suite, contract.version, stamp, timeoutSeconds)
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
            currency.receiptId == null || currency.applicability != Applicability.Current || !currency.eligible
        }
        return LayerRun(layer, selection.run.map { runTriaged(it, contract).first }, selection.notTested)
    }

    /** Verify-on-stop (§8.1, P3.1.3): the increment's acceptance on a completion proposal; never the full suite. */
    public suspend fun onStop(acceptanceIds: Collection<String>): LayerRun = runLayer(Layer.IncrementAcceptance, acceptanceIds)

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
        val (first, view) = runOne(check, contract)
        if (first.outcome != Outcome.Failed) return first to view
        val (second, again) = runOne(check, contract)
        if (second.outcome == first.outcome) return second to "$view\n$again"
        val flaky = scheduler.flaky(check, contract.version, first, second)
        return flaky to "$view\n$again\n  ${check.id}: flaky — ${first.outcome.name.lowercase()} then ${second.outcome.name.lowercase()} ⇒ inconclusive; record an Open item (state patch open.add) before relying on it"
    }

    private suspend fun runOne(check: Check, contract: Contract): Pair<Receipt, String> {
        val command = check.command ?: return scheduler.runCheck(check, contract.version, inputs) {
            Executed(listOf(check.id), null, false, null, Outcome.Unavailable, null, null, listOf("check ${check.id} declares no command"))
        } to "  ${check.id}: unavailable (no command)"
        var view = ""
        val receipt = scheduler.runCheck(check, contract.version, inputs) { root ->
            val actionId = idGen.next("act")
            val cwd = command.cwd?.let { root.resolve(it) } ?: root
            val proc = try {
                runner.start(SpawnSpec(Command.Argv(command.argv), cwd, logPath(check.id, actionId), EnvPolicy(inheritedNames = envAllowlist, extra = mapOf("CI" to "1", "NO_COLOR" to "1")), timeoutSeconds))
            } catch (failure: IOException) {
                view = "  ${check.id}: unavailable — cannot start ${command.argv.first()}: ${failure.message}"
                return@runCheck Executed(command.argv, command.cwd, false, null, Outcome.Unavailable, null, null, listOf("cannot start ${command.argv.first()}: ${failure.message}"))
            }
            val observed = Executions.observe(os, proc, POLL_SLICE_SECONDS, timeoutSeconds)
            val blob = blobs.put(redaction.applyBytes(observed.output, ContentClass.ReusableEvidence).text.toByteArray(Charsets.UTF_8), BlobKind.LOG, ids)
            val capture = RunCapture(
                actionId = actionId, argv = command.argv, shell = false, cwd = command.cwd,
                exitCode = (observed.proc.status as? ProcStatus.Exited)?.exitCode, timedOut = observed.proc.status == ProcStatus.DeadlineExceeded,
                output = observed.output, captureComplete = !observed.lost && observed.proc.status !is ProcStatus.Lost, checkId = check.id, selector = check.selector.toString(),
            )
            val shaped = Shapers.shape(capture, ShapeBudget(estimator = estimator))
            val outcome = when {
                observed.lost || observed.proc.status is ProcStatus.Lost -> Outcome.UnknownOutcome
                observed.proc.status == ProcStatus.DeadlineExceeded -> Outcome.Timeout
                else -> shaped.status
            }
            view = "  ${check.id}: " + shaped.view.lines().joinToString("\n  ")
            Executed(command.argv, command.cwd, false, capture.exitCode, outcome, shaped.counts, blob, shaped.limitations)
        }
        return receipt to view
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
            check.selector == Selector.Blast -> "tests"
            check.kind == CheckKind.Acceptance -> "accept ${check.acceptanceIds.joinToString("+")}"
            check.kind == CheckKind.Type -> "types"
            check.kind == CheckKind.Full -> "full"
            else -> check.kind.name.lowercase()
        }
        return CheckLine(label, Blast.scope(check), null, state, receipt.stampAfter.hash8, alias)
    }

    private fun outcome(args: VerifyArgs, status: String, body: String, receipts: List<Receipt>, stamp: CandidateId?): ToolOutcome {
        val moved = receipts.any { it.stampBefore != it.stampAfter }
        val header = EnvelopeHeader(
            resultAlias = receipts.lastOrNull()?.let { scheduler.aliasOf(it.receiptId) } ?: "#-", tool = "verify", effectClass = if (moved) EffectClass.W else EffectClass.R,
            versions = emptyMap(), stamp = stamp, truncated = false, effects = if (moved) Effects.Observed else Effects.None, flags = InstructionShape.detect(body).flags,
            runtime = RuntimeFields(
                actionId = idGen.next("act"), status = status, candidateBefore = receipts.firstOrNull()?.stampBefore, candidateAfter = stamp,
                scope = args.what + (args.selection?.let { "($it)" } ?: "") + (args.ids?.let { " " + it.joinToString(",") } ?: ""), completeness = "complete",
                artifactRefs = receipts.mapNotNull { it.raw?.hex }, effectsObserved = if (moved) listOf("checks moved the tree") else emptyList(),
                effectsUnknown = receipts.any { it.outcome == Outcome.UnknownOutcome },
            ),
        )
        return ToolOutcome(body, header, green = receipts.isNotEmpty() && receipts.all { it.greenForFinalTree }, tokens = estimator.estimate(body).tokens)
    }

    private fun refused(args: VerifyArgs, status: String, detail: String): ToolOutcome {
        val header = EnvelopeHeader(
            resultAlias = "#-", tool = "verify", effectClass = null, versions = emptyMap(), stamp = null, truncated = false, effects = Effects.None,
            runtime = RuntimeFields(idGen.next("act"), status, null, null, args.what + (args.selection?.let { "($it)" } ?: ""), "complete"),
        )
        return ToolOutcome(detail, header, tokens = estimator.estimate(detail).tokens)
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
    }
}

/** What [Verify.runLayer] recorded: the receipts of the checks it ran, and what the layer could not test. */
public data class LayerRun(val layer: Layer, val receipts: List<Receipt>, val notTested: List<String>)
