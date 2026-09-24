package io.astrolabe.campaign

import io.astrolabe.auth.Stage
import io.astrolabe.cell.ChangeOrigin
import io.astrolabe.cell.ResultPacket
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Money
import io.astrolabe.store.BlobKind
import io.astrolabe.telemetry.Accounting
import io.astrolabe.verify.Currency
import io.astrolabe.workspace.DirtyState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
public data class RequirementLine(val id: String, val status: String, val blockers: List<String>)

/** One acceptance item at the final stamp: `green` only with a current certifying receipt; `log` ids are raw-output blobs. */
@Serializable
public data class AcceptanceLine(
    val id: String,
    val kind: String,
    val status: String,
    val stamp: String?,
    val currency: String?,
    val logIds: List<String>,
)

@Serializable
public data class ChangeSplit(
    val agent: List<String>,
    val byRun: List<String>,
    /** The user's pre-existing changes, left untouched. */
    val preExistingUserChanges: List<String>,
    val unattributed: List<String>,
)

@Serializable
public data class CheckRun(val checkId: String, val receiptId: String, val outcome: String, val verifierVersion: String, val envId: String)

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
 * decisions and memory candidates (P4). ADR candidates are the registers' boundary-crossing decisions (P2.1.3). [highestAuthorizedStage] is `patch` in P1 — never
 * "delivered" for a patch.
 */
@Serializable
public data class FinishReceipt(
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
)

/** Builds, stores and exports the [FinishReceipt] of an ended campaign. */
public object FinishReceipts {
    private val JSON = Json { encodeDefaults = true; prettyPrint = true }

    /**
     * The receipt of [c] as it ended, from the store and the cells' [packets]; [currencies] are the checks'
     * currencies at the final stamp, [receipts] resolves receipt ids.
     */
    @JvmStatic
    public fun build(
        c: OpenedCampaign,
        packets: List<ResultPacket>,
        currencies: Map<String, Currency>,
        receipts: (String) -> io.astrolabe.evidence.Receipt?,
    ): FinishReceipt {
        val state = checkNotNull(c.state) { "a finish receipt needs a campaign state" }
        val outcome = checkNotNull(state.outcome) { "the campaign has not ended" }
        val contract = c.contract
        val report = c.stamper.report()
        val blocked = state.graph.increments.filter { it.status == io.astrolabe.contract.IncrementStatus.Blocked }
        val requirements = contract.requirements.map { r ->
            val entry = state.ledger.entries[r.id]
            val blockers = blocked.filter { r.id in it.requirementIds }.map { "${it.id} blocked" } +
                listOfNotNull(state.reason.takeIf { entry?.status != RequirementStatus.Verified })
            RequirementLine(r.id, entry?.status?.wire ?: RequirementStatus.Pending.wire, blockers)
        }
        val acceptance = contract.acceptance.map { item ->
            when (item) {
                is Acceptance.Run -> {
                    val checks = c.checks.forAcceptance(item.id)
                    val currency = checks.firstNotNullOfOrNull { currencies[it.id]?.takeIf(Currency::certifies) } ?: checks.firstNotNullOfOrNull { currencies[it.id] }
                    val receipt = currency?.receiptId?.let(receipts)
                    val status = when {
                        currency == null -> "not_run"
                        currency.certifies -> "green"
                        receipt != null && receipt.outcome == io.astrolabe.evidence.Outcome.Failed -> "red"
                        else -> "missing_evidence"
                    }
                    AcceptanceLine(item.id, "run", status, receipt?.stampAfter?.hash8, currency?.applicability?.name?.lowercase(), listOfNotNull(receipt?.raw?.hex))
                }
                is Acceptance.Check -> AcceptanceLine(item.id, "check", "not_assessed", null, null, emptyList())
                is Acceptance.Review -> AcceptanceLine(item.id, "review", "not_reviewed", null, null, emptyList())
            }
        }
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
        val checksRun = c.checks.all().flatMap { check -> check.last?.receiptId?.let(receipts)?.let(::listOf).orEmpty() }
            .map { CheckRun(it.checkId, it.receiptId, it.outcome.name.lowercase(), it.verifierVersion, it.envId.hex) }
        val registers = packets.map { it.register }
        val totals = Accounting.totals(Accounting(c.store, java.time.Clock.systemUTC()).calls(c.ids.work), state.graph.increments.count { it.status == io.astrolabe.contract.IncrementStatus.Verified }, c.attempt.config.profiles.values.firstOrNull()?.priceTable?.currency ?: "USD")
        val billed = totals.quantities.values.takeIf { values -> values.none { it == null } }?.sumOf { it!! }
        val helper = packets.sumOf { it.cost.helperTokens }
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
            notVerified = requirements.filter { it.status != RequirementStatus.Verified.wire }.map { it.id } + acceptance.filter { it.status != "green" }.map { it.id },
            deadEnds = registers.flatMap { r -> r.deadEnds.map { it.text } },
            decisions = registers.flatMap { r -> r.decisions.map { "${it.text} because ${it.because}" } },
            // §4.2: a boundary-crossing decision is promoted to an ADR candidate; the curator admits it (P4.1).
            adrCandidates = registers.flatMap { r ->
                r.decisions.filter { it.adrCandidate }.map { d -> "${d.text} because ${d.because}" + (d.rejected?.let { "; rejected: $it" } ?: "") + (d.probe?.let { "; probe: $it" } ?: "") }
            },
            openItems = registers.flatMap { r -> r.open.filter { !it.closed }.map { it.text } },
            pendingAmendments = contract.amendmentsPending.map { it.change },
            routingDecisions = emptyList(),
            budget = BudgetLine(totals.quantities.mapKeys { it.key.id }, totals.money, billed?.takeIf { it > 0 }?.let { helper.toDouble() / it }),
            memoryCandidates = emptyList(),
            highestAuthorizedStage = Stage.Patch,
        )
    }

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
    /** §7.3 full-suite cadence: every K verified increments, and at campaign end. */
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
