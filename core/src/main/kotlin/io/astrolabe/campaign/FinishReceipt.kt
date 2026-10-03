package io.astrolabe.campaign

import io.astrolabe.auth.Stage
import io.astrolabe.cell.ChangeOrigin
import io.astrolabe.cell.ResultPacket
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Origin
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.delegate.QaRunRecord
import io.astrolabe.evidence.EvidenceKind
import io.astrolabe.delegate.QaRuns
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Money
import io.astrolabe.store.BlobKind
import io.astrolabe.telemetry.Accounting
import io.astrolabe.verify.Author
import io.astrolabe.verify.CampaignReview
import io.astrolabe.verify.CampaignReviewRecord
import io.astrolabe.verify.Checks
import io.astrolabe.verify.Currency
import io.astrolabe.verify.EquivalenceReport
import io.astrolabe.verify.Obligations
import io.astrolabe.verify.ProvenanceClass
import io.astrolabe.verify.ProvenanceKind
import io.astrolabe.verify.ResultStatus
import io.astrolabe.verify.ReviewerKind
import io.astrolabe.verify.RiskAcceptor
import io.astrolabe.verify.SurfaceChange
import io.astrolabe.verify.TestIntegrity
import io.astrolabe.workspace.DirtyState
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.StampReport
import io.astrolabe.workspace.WorkspacePath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * One requirement with its provenance axis (§4.4 C2): who set it ([by], from [authorityRef]), the [acceptance] items
 * that check it — its own and the model's that strengthen it — the model's own checks that name it ([agentChecks],
 * C1a), and the class they give it.
 */
@Serializable
public data class RequirementLine @JvmOverloads constructor(
    val id: String,
    val status: String,
    val blockers: List<String>,
    val by: Author? = null,
    val authorityRef: String? = null,
    val acceptance: List<String> = emptyList(),
    val provenanceClass: ProvenanceClass = ProvenanceClass.Unverified,
    val agentChecks: List<String> = emptyList(),
)

/**
 * One acceptance item at the final stamp: `green` only with a current certifying receipt, `assessed`/`approved` only with
 * a reviewer's current approval, `accepted` when a decider accepted it without verification (I7, D-342) — then
 * [acceptedBy], [decider] and [acceptedReason] say who and why. `log` ids are raw-output blobs.
 *
 * Its provenance axis (§4.4 C2): who created the check ([origin], [checkBy]), what ran ([command], [receiptId], and what
 * a pass proves — [evidenceKind]), on which tree ([stamp], [currency]), the [result] at the final tree before any
 * decision, who verified it ([verifiedBy]: `runtime` for a receipt, the judge's tier or `human` for an approval), who
 * took the residual risk ([riskAcceptedBy], `null` while nobody did) and the class this gives the item.
 */
@Serializable
public data class AcceptanceLine @JvmOverloads constructor(
    val id: String,
    val kind: String,
    val status: String,
    val stamp: String?,
    val currency: String?,
    val logIds: List<String>,
    /** `tested` · `reviewed` · `accepted`; `null` while the item is not accepted. */
    val provenance: String? = null,
    val acceptedBy: String? = null,
    val decider: String? = null,
    val acceptedReason: String? = null,
    val origin: Origin? = null,
    val checkBy: Author? = null,
    val command: String? = null,
    val receiptId: String? = null,
    val riskAcceptedBy: RiskAcceptor? = null,
    val result: ResultStatus = ResultStatus.Unverified,
    val provenanceClass: ProvenanceClass = ProvenanceClass.Unverified,
    val evidenceKind: EvidenceKind? = null,
    val verifiedBy: String? = null,
)

@Serializable
public data class ChangeSplit(
    val agent: List<String>,
    val byRun: List<String>,
    /** The user's pre-existing changes, left untouched. */
    val preExistingUserChanges: List<String>,
    val unattributed: List<String>,
)

/**
 * A check's last receipt; [checkOrigin] says whose check it is (`model(…)`: the agent's own, C1a), [evidenceKind] what a
 * pass proves and [command] the check's definition — argv and workspace-relative cwd — so a host can declare an agent's
 * check as the project's own (`run:` item, plan §4.4 C1b).
 */
@Serializable
public data class CheckRun @JvmOverloads constructor(
    val checkId: String,
    val receiptId: String,
    val outcome: String,
    val verifierVersion: String,
    val envId: String,
    val checkOrigin: Origin? = null,
    val evidenceKind: EvidenceKind? = null,
    val command: io.astrolabe.contract.Command? = null,
)

@Serializable
public data class BudgetLine(
    /** Billed tokens per cache class; `null` for a class some call did not report (never zero). */
    val byCacheClass: Map<String, Long?>,
    val money: Money,
    /** Helper tokens over billed tokens; `null` while the total is unknown. */
    val helperShare: Double?,
)

/**
 * The campaign finish receipt (§5.9) in its S0 form. [status] is `completed` only for a campaign that finished
 * verified; a budget stop is `partial`, never verified; every other outcome keeps its own word. Fields whose
 * producer comes later are present and empty: acceptance-surface reasons (P3.4, `null` = not assessed), routing
 * decisions and memory candidates (P4). ADR candidates are the registers' boundary-crossing decisions (P2.1.3). [highestAuthorizedStage] is `patch` as built;
 * `Publisher.report` raises it to the highest stage the attempt's publisher was authorized to reach — never
 * "delivered" for a patch (§14.2). [provenanceClass] says who verified the result (§4.4 C2): `independent`, `agent_test`
 * or `unverified`, beside a status that stays `completed`; [acceptanceSurfaceUnreviewed] names the changes since s0 to a
 * required check's acceptance surface that no approving review covered: the run checks they touch are the agent's evidence.
 */
@Serializable
public data class FinishReceipt @JvmOverloads constructor(
    val work: WorkId,
    val attempt: AttemptId,
    val contractVersion: Int,
    val outcome: String,
    val status: String,
    val reason: String?,
    val stamp: CandidateId,
    val requirements: List<RequirementLine>,
    val acceptance: List<AcceptanceLine>,
    val changes: ChangeSplit,
    val acceptanceSurfaceModified: List<String>?,
    val checksRun: List<CheckRun>,
    val notVerified: List<String>,
    val deadEnds: List<String>,
    val decisions: List<String>,
    val adrCandidates: List<String>,
    val openItems: List<String>,
    val pendingAmendments: List<String>,
    val routingDecisions: List<String>,
    val budget: BudgetLine,
    val memoryCandidates: List<String>,
    val highestAuthorizedStage: Stage,
    /** §8.9 item 5 equivalence evidence at the final stamp (refactor mode); `null` when none was computed. */
    val equivalence: EquivalenceReport? = null,
    /** The campaign-scope review (§8.8, D-23 human path); `null` when none was owed. */
    val review: ReviewLine? = null,
    /** L3 product use (§10.3): each QA run with its cases, receipts and log blobs; empty when no QA cell ran. */
    val qa: List<QaRunRecord> = emptyList(),
    /** Items accepted on a decider's word without verification (I7, D-342): never shown as verified. */
    val acceptedWithoutVerification: List<io.astrolabe.verify.ItemProvenance> = emptyList(),
    val provenanceClass: ProvenanceClass = ProvenanceClass.Unverified,
    val acceptanceSurfaceUnreviewed: List<String> = emptyList(),
    /** Such changes only a model approved (owner 2026-10-03): completion went ahead, the run checks they touch stay the agent's evidence. */
    val acceptanceSurfaceModelApproved: List<String> = emptyList(),
    /** How a task limit ended the campaign, with the best verified candidate it names (C3); `null` for every other ending. */
    val limit: LimitStop? = null,
)

/** The campaign review as the receipt reports it: the request, the diff it saw, and the signed verdict or why none arrived. */
@Serializable
public data class ReviewLine(
    val requestId: String,
    val diffRef: String?,
    val verdict: String?,
    val signedBy: String?,
    val confidence: Double?,
    val unavailable: String?,
    val reused: Boolean,
)

/** Builds, stores and exports the [FinishReceipt] of an ended campaign. */
public object FinishReceipts {
    private val JSON = Json { encodeDefaults = true; prettyPrint = true }

    /** [build] at the stamp read fresh now (D-374). */
    @JvmStatic
    public fun build(
        c: OpenedCampaign,
        packets: List<ResultPacket>,
        currencies: Map<String, Currency>,
        receipts: (String) -> io.astrolabe.evidence.Receipt?,
    ): FinishReceipt = build(c, packets, currencies, c.stamper.report(fresh = true), receipts)

    /**
     * The receipt of [c] as it ended, from the store and the cells' [packets]; [currencies] are the checks'
     * currencies at the final stamp of [report] — read fresh (D-374), the one stamp the whole receipt speaks for —
     * and [receipts] resolves receipt ids.
     */
    @JvmStatic
    public fun build(
        c: OpenedCampaign,
        packets: List<ResultPacket>,
        currencies: Map<String, Currency>,
        report: StampReport,
        receipts: (String) -> io.astrolabe.evidence.Receipt?,
    ): FinishReceipt {
        val state = checkNotNull(c.state) { "a finish receipt needs a campaign state" }
        val outcome = checkNotNull(state.outcome) { "the campaign has not ended" }
        val contract = c.contract
        val blocked = state.graph.increments.filter { it.status == io.astrolabe.contract.IncrementStatus.Blocked }
        val reviews = c.store.db.query("SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid DESC",
            state.work, state.attempt, io.astrolabe.delegate.ReviewCell.KIND) {
            Json.decodeFromString(io.astrolabe.delegate.ReviewRecord.serializer(), it.string("body"))
        }
        val assessments = reviews.filter { it.approved && it.contractVersion == contract.version && it.candidate == report.candidateId &&
            it.evidenceVersions.all { (path, version) -> c.registry.version(path) == version } }
        // I7: the decider's acceptance of an item for this candidate — at increment level or at the campaign gate.
        val decided = LinkedHashMap<String, io.astrolabe.verify.ItemProvenance>()
        state.graph.increments.filter { it.status == io.astrolabe.contract.IncrementStatus.Verified }.mapNotNull { state.graph.evidence[it.id] }
            .filter { it.stamp == report.candidateId && it.contractVersion == contract.version }
            .flatMap { it.provenance }.filter { it.how == io.astrolabe.verify.ProvenanceKind.Accepted }.forEach { decided[it.item] = it }
        if (outcome == CampaignOutcome.Completed) {
            Acceptances(c.store, java.time.Clock.systemUTC()).decisions(state.work, state.attempt)
                .filter { it.incrementId == null && it.decision.kind == io.astrolabe.verify.DecisionKind.Accept && it.appliesTo(report.candidateId, contract.version) }
                .forEach { record -> record.obligations.forEach { item ->
                    decided.putIfAbsent(item, io.astrolabe.verify.ItemProvenance(item, io.astrolabe.verify.ProvenanceKind.Accepted, record.decision.by, record.decision.decider, record.decision.reason, record.decision.requestId,
                        contract.acceptance(item)?.origin))
                } }
        }
        fun accepted(line: AcceptanceLine): AcceptanceLine = decided[line.id]?.takeIf { line.provenance == null }?.let {
            line.copy(status = "accepted", provenance = "accepted", acceptedBy = it.by, decider = it.decider?.name?.lowercase(), acceptedReason = it.reason,
                result = it.result ?: line.result)
        } ?: line
        val changes = packets.flatMap { it.changes }
        // A cell that never handed back a packet (lost or interrupted) still named what it touched in its checkpoint.
        val reported = packets.mapNotNull { it.ids.context }.toSet()
        val unreported = state.cells.filter { it.cell !in reported && it.status != io.astrolabe.cell.CellStatus.Running }
            .flatMap { io.astrolabe.cell.SqliteCheckpoints(c.store, java.time.Clock.systemUTC()).latest(it.cell)?.touched.orEmpty() }
        val separated = DirtyState.separate(
            c.s0, report,
            agentEdits = (changes.filter { it.origin == ChangeOrigin.Edit }.map { it.path } + unreported).toSet(),
            runTouched = changes.filter { it.origin == ChangeOrigin.Run }.map { it.path }.toSet(),
        )
        // §8.6 at the final tree: what changed since s0 — by the agent, a run or no one known — on a required check's
        // acceptance surface, classified from its bytes then and now, unless an approving review covered it. Its
        // run checks are the agent's evidence, whichever cell made the change (§4.4 C2).
        val surface = TestIntegrity.classify(
            (separated.agent + separated.byRun + separated.unattributed).distinct()
                .filter { TestIntegrity.surfaceOf(it, contract, c.checks.packageManifest) != null }
                .map { SurfaceChange(it, textAtS0(c, it), textNow(c, it)) }.filterNot { sameText(it.before, it.after) },
            "finish", contract, c.checks,
        ).filter { flag -> flag.requiredChecks.isNotEmpty() && flag.kind != TestIntegrity.ADDITIONS_ONLY }
        val approvals = surface.associate { it.path to reviewed(c, it.path, reviews, report) }
        val unreviewedSurface = surface.filter { approvals[it.path] == null }.map { it.path }.sorted()
        // Owner 2026-10-03: a model's approval (the judge's, or a host's model's) lets completion proceed, but only a person's
        // clears the change for the class — the run checks it touches stay the agent's evidence.
        val tainted = surface.filter { approvals[it.path] != ReviewerKind.Human }
        val surfaceTouched = tainted.flatMap { it.requiredChecks }.flatMap { c.checks[it]?.acceptanceIds.orEmpty() }.toSet()
        // The certifying (else the latest) receipt of each `run:` item at the final stamp.
        val runs = contract.acceptance.filterIsInstance<Acceptance.Run>().associate { item ->
            val checks = c.checks.forAcceptance(item.id)
            val currency = checks.firstNotNullOfOrNull { currencies[it.id]?.takeIf(Currency::certifies) } ?: checks.firstNotNullOfOrNull { currencies[it.id] }
            item.id to Triple(currency, currency?.receiptId?.let(receipts), checks.firstNotNullOfOrNull { it.evidenceKind })
        }
        // A `check:`/`review:` item a model approved (the review cell's judge, or a host's model) is the agent's evidence (owner 2026-10-03).
        val modelApproved = contract.acceptance.filter { item -> item !is Acceptance.Run && assessments.firstOrNull { item.id in it.criteria }?.let { it.verdict?.reviewer != ReviewerKind.Human } == true }
            .map { it.id }.toSet()
        fun evidenceBy(item: Acceptance): Author =
            if (item.id in surfaceTouched || item.id in modelApproved || runs[item.id]?.second?.independent == false) Author.Model else Author.of(item.origin)
        // §4.4 C2: who created the check, who took the residual risk, and the class this gives the item at the final tree.
        fun axis(line: AcceptanceLine, item: Acceptance): AcceptanceLine = line.copy(
            origin = item.origin,
            checkBy = Author.of(item.origin),
            riskAcceptedBy = if (line.result == ResultStatus.Passed) RiskAcceptor.Runtime else decided[line.id]?.takeIf { line.provenance == "accepted" }?.riskAcceptedBy,
            provenanceClass = ProvenanceClass.item(evidenceBy(item), line.result),
            verifiedBy = line.verifiedBy ?: "runtime".takeIf { line.provenance == "tested" },
        )
        val acceptance = contract.acceptance.map { item ->
            when (item) {
                is Acceptance.Run -> {
                    val (currency, receipt, kind) = runs.getValue(item.id)
                    val status = when {
                        currency == null -> "not_run"
                        currency.certifies -> "green"
                        receipt != null && receipt.outcome == io.astrolabe.evidence.Outcome.Failed -> "red"
                        else -> "missing_evidence"
                    }
                    AcceptanceLine(item.id, "run", status, receipt?.stampAfter?.hash8, currency?.applicability?.name?.lowercase(), listOfNotNull(receipt?.raw?.hex),
                        provenance = "tested".takeIf { status == "green" }, command = item.command.text, receiptId = currency?.receiptId,
                        result = Obligations.run(item.id, item.criterion, currency).status, evidenceKind = receipt?.evidenceKind ?: kind)
                }
                is Acceptance.Check -> assessments.firstOrNull { item.id in it.criteria }?.let {
                    AcceptanceLine(item.id, "check", "assessed", it.candidate.hash8, "current", listOf(it.packetId), provenance = "reviewed", acceptedBy = it.verdict?.signedBy, result = ResultStatus.Passed,
                        verifiedBy = verifiedBy(it))
                } ?: AcceptanceLine(item.id, "check", "not_assessed", null, null, emptyList())
                is Acceptance.Review -> assessments.firstOrNull { item.id in it.criteria }?.let {
                    AcceptanceLine(item.id, "review", "approved", it.candidate.hash8, "current", listOf(it.packetId), provenance = "reviewed", acceptedBy = it.verdict?.signedBy, result = ResultStatus.Passed,
                        verifiedBy = verifiedBy(it))
                } ?: AcceptanceLine(item.id, "review", "not_reviewed", null, null, emptyList())
            }.let(::accepted).let { axis(it, item) }
        }
        // `model(strengthens R1+R2)`: an item strengthens one requirement, the model's own check (C1a) every one its increment serves.
        fun strengthens(origin: Origin?, requirement: String): Boolean = (origin as? Origin.Model)?.strengthens?.split('+')?.contains(requirement) == true
        val modelChecks = c.checks.all().filter { it.id.startsWith(Checks.MODEL_PREFIX) }
        val requirements = contract.requirements.map { r ->
            val entry = state.ledger.entries[r.id]
            val blockers = blocked.filter { r.id in it.requirementIds }.map { "${it.id} blocked" } +
                listOfNotNull(state.reason.takeIf { entry?.status != RequirementStatus.Verified })
            // §4.4 C2: a requirement is checked by its own items, the model's items that strengthen it, and the model's own checks that name it.
            val items = (r.acceptance + contract.acceptance.filter { strengthens(it.origin, r.id) }.map { it.id }).distinct()
            val (agent, declared) = acceptance.filter { it.id in items }.partition { line -> evidenceBy(checkNotNull(contract.acceptance(line.id))) == Author.Model }
            val own = modelChecks.filter { strengthens(it.origin, r.id) }
            val provenanceClass = if (entry?.status != RequirementStatus.Verified) ProvenanceClass.Unverified
                else ProvenanceClass.requirement(declared.map { it.result }, agent.map { it.result } + own.map { Obligations.run(it.id, "model check", currencies[it.id]).status })
            RequirementLine(r.id, entry?.status?.wire ?: RequirementStatus.Pending.wire, blockers, Author.of(r, contract.requests), r.authorityRef, items, provenanceClass,
                own.map { it.id })
        }
        // C1b: a mandatory check outside the acceptance items and the campaign gate (the blast radius, the types of touched
        // files) red on the final tree — completed past on an Open item — leaves the campaign unverified; a known red does not.
        val redOnFinalTree = c.checks.all()
            .filter { Obligations.mandatory(it) && !it.required && it.kind != io.astrolabe.verify.CheckKind.Full && it.kind != io.astrolabe.verify.CheckKind.Quality }
            .filter { check -> currencies[check.id]?.let { it.red && it.applicability == io.astrolabe.verify.Applicability.Current } == true }.map { it.id }
        val checksRun = c.checks.all().mapNotNull { check -> check.last?.receiptId?.let(receipts)?.let { check to it } }
            .map { (check, it) -> CheckRun(it.checkId, it.receiptId, it.outcome.name.lowercase(), it.verifierVersion, it.envId.hex, it.checkOrigin, it.evidenceKind, check.command) }
        val registers = packets.map { it.register }
        val totals = Accounting.totals(Accounting(c.store, java.time.Clock.systemUTC()).calls(c.ids.work), state.graph.increments.count { it.status == io.astrolabe.contract.IncrementStatus.Verified }, c.attempt.config.profiles.values.firstOrNull()?.priceTable?.currency ?: "USD")
        val billed = totals.quantities.values.takeIf { values -> values.none { it == null } }?.sumOf { it!! }
        val helper = packets.sumOf { it.cost.helperTokens }
        val review = c.store.db.query(
            "SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid DESC LIMIT 1",
            state.work, state.attempt, CampaignReview.KIND,
        ) { Json.decodeFromString(CampaignReviewRecord.serializer(), it.string("body")) }.firstOrNull()
        return FinishReceipt(
            work = state.work,
            attempt = state.attempt,
            contractVersion = contract.version,
            outcome = outcome.wire,
            status = when (outcome) {
                CampaignOutcome.Completed -> "completed"
                CampaignOutcome.BudgetExhausted -> "partial"
                else -> outcome.wire
            },
            reason = state.reason,
            stamp = report.candidateId,
            requirements = requirements,
            acceptance = acceptance,
            changes = ChangeSplit(separated.agent.toList(), separated.byRun.toList(), separated.preExistingUserChanges.toList(), separated.unattributed.toList()),
            acceptanceSurfaceModified = null,
            checksRun = checksRun,
            // I7: accepted is never verified — each such item says who accepted it and why, campaign-level obligations included.
            notVerified = requirements.filter { it.status != RequirementStatus.Verified.wire }.map { it.id } +
                acceptance.filter { it.provenance == null }.map { it.id } +
                acceptance.filter { it.provenance == "accepted" }.map { "${it.id}: accepted without verification by ${it.acceptedBy} (${it.decider}): ${it.acceptedReason}" } +
                decided.values.filter { p -> acceptance.none { it.id == p.item } }.map { "${it.item}: accepted without verification by ${it.by} (${it.decider?.name?.lowercase()}): ${it.reason}" } +
                redOnFinalTree.map { "$it: red on the final tree" },
            deadEnds = registers.flatMap { r -> r.deadEnds.map { it.text } },
            decisions = registers.flatMap { r -> r.decisions.map { "${it.text} because ${it.because}" } },
            // §4.2: a boundary-crossing decision is promoted to an ADR candidate; the curator admits it (P4.1).
            adrCandidates = registers.flatMap { r ->
                r.decisions.filter { it.adrCandidate }.map { d -> "${d.text} because ${d.because}" + (d.rejected?.let { "; rejected: $it" } ?: "") + (d.probe?.let { "; probe: $it" } ?: "") }
            },
            openItems = registers.flatMap { r -> r.open.filter { !it.closed }.map { it.text } } +
                state.graph.increments.filter { it.status == io.astrolabe.contract.IncrementStatus.Verified }
                    .flatMap { inc -> state.graph.evidence[inc.id]?.leftOpen.orEmpty().map { "${inc.id}: $it" } } +
                // Plan §4.3 (C1b): an optional check that is known red, recorded by the runtime in place of the agent's Open item.
                c.checks.all().filterNot(Obligations::mandatory).mapNotNull { check -> currencies[check.id]?.let { Obligations.knownRed(check.id, it) } },
            pendingAmendments = contract.amendmentsPending.map { it.change },
            routingDecisions = emptyList(),
            budget = BudgetLine(totals.quantities.mapKeys { it.key.id }, totals.money, billed?.takeIf { it > 0 }?.let { helper.toDouble() / it }),
            memoryCandidates = emptyList(),
            highestAuthorizedStage = Stage.Patch,
            equivalence = review?.equivalence,
            review = review?.let {
                ReviewLine(
                    it.request.id, it.request.diffRef, it.verdict?.outcome?.name?.lowercase(), it.verdict?.signedBy, it.verdict?.confidence, it.unavailable, it.reused,
                )
            },
            qa = QaRuns.forAttempt(c.store, state.work, state.attempt),
            acceptedWithoutVerification = acceptance.mapNotNull { line -> decided[line.id]?.takeIf { line.provenance == "accepted" } } +
                decided.values.filter { p -> acceptance.none { it.id == p.item } },
            provenanceClass = if (redOnFinalTree.isEmpty()) ProvenanceClass.campaign(requirements.map { it.provenanceClass }) else ProvenanceClass.Unverified,
            acceptanceSurfaceUnreviewed = unreviewedSurface,
            acceptanceSurfaceModelApproved = surface.filter { approvals[it.path] == ReviewerKind.Model }.map { it.path }.sorted(),
        )
    }

    /** A path's text at s0: its recovery blob when s0 recorded it, else the base commit's; `null` when it did not exist. */
    private fun textAtS0(c: OpenedCampaign, path: String): String? {
        val entry = c.s0.entries.firstOrNull { it.path == path }
        val bytes = if (entry != null) c.dirty.bytesOf(entry)
            else runCatching { c.workspace.git.catFile(c.workspace.git.revParse("${c.s0.baseCommit}:$path")) }.getOrNull()
        return bytes?.toString(Charsets.UTF_8)
    }

    /** A path's text in the workspace now, read through [WorkspacePath] (D-47); `null` when it is gone. */
    private fun textNow(c: OpenedCampaign, path: String): String? =
        (WorkspacePath.of(c.workspace.root).resolve(path, Intent.Read) as? PathResolution.Resolved)?.real
            ?.takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it).toString(Charsets.UTF_8) }

    /**
     * Text that differs in line endings, trailing blanks or blank lines only is the same text: a path back at its s0
     * bytes, or close to them, holds nothing for the §8.6 diff to find.
     */
    private fun sameText(a: String?, b: String?): Boolean = a == b || a != null && b != null && content(a) == content(b)

    private fun content(text: String): List<String> = text.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }

    /**
     * Who approved the change of [path] as it is at [report]'s stamp — a `reviewed` integrity obligation of an increment
     * verified at that stamp and contract, or an approving review that [covers] it — [ReviewerKind.Human] when a person
     * did (the verdict says so), else [ReviewerKind.Model]; `null` when no approval covered it.
     */
    private fun reviewed(c: OpenedCampaign, path: String, reviews: List<io.astrolabe.delegate.ReviewRecord>, report: StampReport): ReviewerKind? {
        val state = checkNotNull(c.state)
        val obligation = Obligations.INTEGRITY + path
        val human = c.attempt.config.integrityApproval == io.astrolabe.IntegrityApproval.Human
        // The obligation's evidence is its verdict's request, which is the reviewed packet's id.
        val obligations = state.graph.evidence.values.filter { e -> e.stamp == report.candidateId && e.contractVersion == c.contract.version }
            .flatMap { e -> e.provenance.filter { it.item == obligation && it.how == ProvenanceKind.Reviewed } }
            .map { p -> reviews.firstOrNull { it.packetId == p.evidenceRef }?.verdict?.reviewer ?: ReviewerKind.Model }
        val covering = reviews.filter { covers(it, path, c.registry.version(path), report.candidateId, c.contract.version, human) }.map { it.verdict?.reviewer ?: ReviewerKind.Model }
        val approvals = obligations + covering
        return if (ReviewerKind.Human in approvals) ReviewerKind.Human else approvals.firstOrNull()
    }

    /** Who verified an approving review (§4.4 C2): `human` for a person, `host_model` for a host's answer not marked a person's, else the judge's tier. */
    private fun verifiedBy(r: io.astrolabe.delegate.ReviewRecord): String? = when {
        r.verdict?.reviewer == ReviewerKind.Human -> HUMAN
        r.path.lastOrNull() == HUMAN -> HOST_MODEL
        else -> r.path.lastOrNull() ?: r.verdict?.signedBy
    }

    /**
     * Whether approving review [r] covers [path] as it is now — [current] at [candidate], contract v[contractVersion]: it
     * names the path among its integrity lines, binds this contract, and saw this very version of the path (or, when it
     * recorded none, this very candidate); under [human] integrity approval only the human path clears a flag (D-320).
     */
    internal fun covers(r: io.astrolabe.delegate.ReviewRecord, path: String, current: io.astrolabe.id.FileVersion?, candidate: io.astrolabe.id.CandidateId, contractVersion: Int, human: Boolean): Boolean =
        r.approved && r.contractVersion == contractVersion && (!human || r.path.lastOrNull() == HUMAN) &&
            r.integrity.any { it.startsWith("acceptance surface: $path (") } &&
            (r.evidenceVersions[path]?.let { it == current } ?: (r.candidate == candidate))

    /** The last tier of a review that went to the host's authority (`ReviewCell`). */
    private const val HUMAN: String = "human"

    /** `verifiedBy` of a host's verdict that does not say a person reviewed (owner 2026-10-03: a model's). */
    private const val HOST_MODEL: String = "host_model"

    /** Stores [receipt] as a packet blob and writes `exports/<work>/finish-receipt.json`; returns the blob ref and file. */
    @JvmStatic
    public fun export(c: OpenedCampaign, receipt: FinishReceipt): Pair<String, Path> {
        val bytes = JSON.encodeToString(FinishReceipt.serializer(), receipt).toByteArray(Charsets.UTF_8)
        val digest = c.store.blobs.put(bytes, BlobKind.PACKET, Identities(receipt.work, receipt.attempt))
        val dir = c.store.layout.exports.resolve(receipt.work.value)
        Files.createDirectories(dir)
        return digest.hex to Files.write(dir.resolve("finish-receipt.json"), bytes)
    }
}

/** The §8.7 campaign gate's policy parts (P2.2.6). */
public object CampaignFinish {
    /** §7.3 full-suite cadence default (`Defaults.fullSuiteCadence` is what the controller reads): every K verified increments, and at campaign end. */
    public const val FULL_SUITE_EVERY: Int = 5

    /**
     * The campaign review predicate `(shape ≥ S2 ∧ increments ≥ 3) ∨ refactor_mode ∨ explicitly required`: the reason a
     * review is owed, or `null`. An unsigned `review:` acceptance item is an explicit requirement; refactor mode arrives
     * with its flag (P3). The human path is P3.5.2, the review cell P4.4.3.
     */
    @JvmStatic
    @JvmOverloads
    public fun reviewRequired(contract: io.astrolabe.contract.Contract, increments: Int, refactorMode: Boolean = false): String? {
        val unsigned = contract.acceptance.filterIsInstance<io.astrolabe.contract.Acceptance.Review>().filter { it.signedBy == null }.map { it.id }
        return when {
            contract.shape >= io.astrolabe.contract.Shape.S2 && increments >= 3 -> "campaign review required: shape ${contract.shape} with $increments increments (P3.5.2/P4.4.3)"
            refactorMode -> "campaign review required: refactor mode (P3.5.2/P4.4.3)"
            unsigned.isNotEmpty() -> "campaign review required: unsigned review items ${unsigned.joinToString(", ")} (P3.5.2/P4.4.3)"
            else -> null
        }
    }
}
