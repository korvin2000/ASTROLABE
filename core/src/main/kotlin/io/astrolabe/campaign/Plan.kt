package io.astrolabe.campaign

import io.astrolabe.Mode
import io.astrolabe.cell.CompletionDecision
import io.astrolabe.cell.NoteCandidate
import io.astrolabe.cell.RoleCompletion
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Shape
import io.astrolabe.event.Authority
import io.astrolabe.graph.RequirementGraph
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.IdGen
import io.astrolabe.id.WorkId
import io.astrolabe.register.Decision
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.verify.Verifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

/** One acceptance item the plan cell proposes (§4.1): an addition only, `model(strengthens R)` for requirement R. */
@Serializable
public data class AcceptanceProposal(val item: Acceptance) {
    init {
        require(item.origin is Origin.Model) { "a proposed acceptance item carries origin model(strengthens <requirement>)" }
    }

    val requirementId: String get() = (item.origin as Origin.Model).strengthens
}

/**
 * The plan cell's output (§3.4 plan row, §4.2): a proposal the controller validates and stores, never an
 * authority. Increments and the ownership map travel as [graphProposal]; decision packets come from the
 * register's `decision.add` records, not from the model's closing text.
 */
@Serializable
public data class PlanPacket @JvmOverloads constructor(
    val graphProposal: RequirementGraph,
    val acceptanceProposals: List<AcceptanceProposal> = emptyList(),
    val decisionPackets: List<Decision> = emptyList(),
    val conCandidates: List<NoteCandidate> = emptyList(),
    val adrCandidates: List<NoteCandidate> = emptyList(),
    val shapeSuggestion: Shape? = null,
)

/** A plan proposal as stored: [id] is the evidence the completion seam cites. */
@Serializable
public data class StoredPlan(val id: String, val cell: ContextId?, val packet: PlanPacket)

/**
 * Where plan proposals are kept (`packets`, kind `plan`). Recording changes neither the contract nor the graph;
 * `task.propose(plan)` (P2.1.5) is the producer.
 */
public interface PlanProposals {
    public fun record(ids: Identities, packet: PlanPacket): StoredPlan

    /** The latest proposal of [cell], or of the campaign when [cell] is `null`. */
    public fun latest(work: WorkId, cell: ContextId?): StoredPlan?
}

public class InMemoryPlanProposals(private val idGen: IdGen) : PlanProposals {
    private val plans = ArrayList<Pair<WorkId, StoredPlan>>()

    @Synchronized
    override fun record(ids: Identities, packet: PlanPacket): StoredPlan =
        StoredPlan(idGen.next("plan"), ids.context, packet).also { plans += ids.work to it }

    @Synchronized
    override fun latest(work: WorkId, cell: ContextId?): StoredPlan? =
        plans.lastOrNull { (w, plan) -> w == work && (cell == null || plan.cell == cell) }?.second
}

public class SqlitePlanProposals(private val store: Store, private val idGen: IdGen, private val clock: Clock) : PlanProposals {
    override fun record(ids: Identities, packet: PlanPacket): StoredPlan = store.db.tx { tx ->
        val plan = StoredPlan(idGen.next("plan"), ids.context, packet)
        tx.execute(
            "INSERT INTO packets (id, work_id, attempt_id, candidate_id, context_id, kind, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            plan.id, ids.work, ids.attempt, ids.candidate, ids.context, KIND, Migrations.SCHEMA_VERSION, clock.instant(),
            JSON.encodeToString(StoredPlan.serializer(), plan),
        )
        plan
    }

    override fun latest(work: WorkId, cell: ContextId?): StoredPlan? = store.db.query(
        "SELECT body FROM packets WHERE work_id = ? AND kind = ? AND (? IS NULL OR context_id = ?) ORDER BY rowid DESC LIMIT 1",
        work, KIND, cell, cell,
    ) { JSON.decodeFromString(StoredPlan.serializer(), it.string("body")) }.firstOrNull()

    private companion object {
        const val KIND = "plan"
        val JSON = Json { encodeDefaults = true }
    }
}

/**
 * The declared completion validator of the plan role (§3.7 `validate_role_output`, P2.1.2): the cell may end
 * only when its latest proposal would pass the controller's checks against the contract with the proposed
 * acceptance added. A requirement nothing can decide must carry a `review:` naming the judgment (D17).
 */
public object PlanPacketValidator {
    /** Gaps of [packet] against [contract]; empty ⇔ the controller can admit it once its acceptance is resolved. */
    @JvmStatic
    public fun gaps(contract: Contract, packet: PlanPacket?): List<String> {
        if (packet == null) return listOf("no plan proposed: end with task.propose(plan)")
        val gaps = ArrayList<String>()
        val graph = packet.graphProposal
        for (increment in graph.increments) {
            if (increment.status != IncrementStatus.Pending || increment.cells.isNotEmpty()) {
                gaps += "increment ${increment.id}: a plan proposes pending increments; status and cells are harness-owned"
            }
        }
        if (graph.evidence.isNotEmpty()) gaps += "a plan carries no evidence; only receipts verify (${graph.evidence.keys.sorted().joinToString()})"
        val proposed = packet.acceptanceProposals.map { it.item }
        for ((id, count) in proposed.groupingBy { it.id }.eachCount().toSortedMap()) if (count > 1) gaps += "acceptance $id is proposed $count times"
        for (proposal in packet.acceptanceProposals) {
            val item = proposal.item
            if (contract.requirement(proposal.requirementId) == null) gaps += "acceptance ${item.id} strengthens unknown requirement ${proposal.requirementId}"
            if (contract.acceptance(item.id) != null) gaps += "acceptance ${item.id} already exists; a plan adds items, never edits one"
            if (item is Acceptance.Review && item.text.isBlank()) gaps += "review ${item.id} must name the judgment it needs"
            if (item is Acceptance.Check && item.text.isBlank()) gaps += "check ${item.id} must state its claim"
        }
        if (gaps.isNotEmpty()) return gaps
        val preview = contract.copy(acceptance = contract.acceptance + proposed)
        gaps += graph.validate(preview).map { issue ->
            (listOf(issue.code.name) + issue.incrementIds).joinToString(" ") + ": " + issue.detail
        }
        gaps += unjudged(preview)
        for (candidate in packet.conCandidates) if (candidate.kind != "CON") gaps += "CON candidate of kind ${candidate.kind}"
        for (candidate in packet.adrCandidates) if (candidate.kind != "ADR") gaps += "ADR candidate of kind ${candidate.kind}"
        return gaps
    }

    /** Requirements with no acceptance of their own: the plan must name an oracle or the judgment (D17). */
    internal fun unjudged(contract: Contract): List<String> = contract.requirements.filter { requirement ->
        requirement.acceptance.isEmpty() && contract.acceptance.none { (it.origin as? Origin.Model)?.strengthens == requirement.id }
    }.map {
        "${it.id} has no acceptance: propose an executable run: or check:, or a review: naming the judgment it needs — never an invented oracle"
    }

    /**
     * The plan role's [RoleCompletion]: accepted with the proposal id as evidence, else the gaps continue the cell,
     * refused at most [maxFinalizations] times before an explicit incomplete exit.
     */
    @JvmStatic
    @JvmOverloads
    public fun completion(
        contract: () -> Contract,
        proposals: PlanProposals,
        maxFinalizations: Int = Verifier().maxFinalizations,
    ): RoleCompletion {
        require(maxFinalizations >= 1) { "maxFinalizations must be ≥ 1" }
        return RoleCompletion { output, _ ->
            val plan = proposals.latest(output.packet.ids.work, output.packet.ids.context)
            val gaps = gaps(contract(), plan?.packet)
            when {
                gaps.isEmpty() -> CompletionDecision.Accepted(listOf(plan!!.id))
                output.refusals + 1 >= maxFinalizations -> CompletionDecision.CannotProgress(gaps)
                else -> CompletionDecision.Continue(gaps)
            }
        }
    }
}

/** What the controller made of a plan proposal. Only [Admitted] carries a graph a campaign may open over. */
public sealed interface PlanAdmission {
    public val contract: Contract

    /** Acceptance ids per resolution: [frozen] autonomous `model` items, [approved]/[rejected]/[pending] interactive ones. */
    public val frozen: List<String>
    public val approved: List<String>
    public val rejected: List<String>
    public val pending: List<String>

    public data class Admitted(
        override val contract: Contract,
        val graph: RequirementGraph,
        override val frozen: List<String>,
        override val approved: List<String>,
        override val rejected: List<String>,
        override val pending: List<String>,
    ) : PlanAdmission

    public data class Refused(
        override val contract: Contract,
        val gaps: List<String>,
        override val frozen: List<String> = emptyList(),
        override val approved: List<String> = emptyList(),
        override val rejected: List<String> = emptyList(),
        override val pending: List<String> = emptyList(),
    ) : PlanAdmission
}

/**
 * The controller's intake of a plan proposal (§4.1, §4.2): the proposed acceptance is resolved first —
 * approved one by one through the [Authority] in an interactive contract, frozen as `model`-origin in an autonomous one
 * (listed in the admission for the finish receipt) — and the graph is admitted only if it then validates
 * against the resulting contract. Items are additions, so nothing here can weaken the contract.
 */
public class PlanIntake(private val contracts: Contracts) {
    public suspend fun admit(work: WorkId, plan: StoredPlan, authority: Authority): PlanAdmission {
        val before = contracts.current(work) ?: throw IllegalArgumentException("no contract for $work")
        val precheck = PlanPacketValidator.gaps(before, plan.packet)
        if (precheck.isNotEmpty()) return PlanAdmission.Refused(before, precheck)
        val frozen = ArrayList<String>()
        val approved = ArrayList<String>()
        val rejected = ArrayList<String>()
        val pending = ArrayList<String>()
        for (proposal in plan.packet.acceptanceProposals) {
            val item = proposal.item
            when (before.mode) {
                Mode.Autonomous -> {
                    val current = contracts.current(work)!!
                    contracts.strengthen(work, item.boundTo(current.version))
                    frozen += item.id
                }
                Mode.Interactive -> {
                    val amendment = contracts.propose(
                        work, plan.cell, "add acceptance ${item.id} for ${proposal.requirementId}: ${item.criterion}",
                        "plan proposal ${plan.id}", weakening = false,
                    )
                    val after = contracts.resolve(work, amendment.id, authority) { c -> c.copy(acceptance = c.acceptance + item.boundTo(c.version + 1)) }
                    when {
                        after.acceptance(item.id) != null -> approved += item.id
                        after.amendmentsPending.any { it.id == amendment.id } -> pending += item.id
                        else -> rejected += item.id
                    }
                }
            }
        }
        val contract = contracts.current(work)!!
        val gaps = plan.packet.graphProposal.validate(contract).map { (listOf(it.code.name) + it.incrementIds).joinToString(" ") + ": " + it.detail } +
            PlanPacketValidator.unjudged(contract)
        return if (gaps.isEmpty()) {
            PlanAdmission.Admitted(contract, plan.packet.graphProposal, frozen, approved, rejected, pending)
        } else {
            PlanAdmission.Refused(contract, gaps, frozen, approved, rejected, pending)
        }
    }
}

/** The item as an obligation of contract version [version] (D-52); its `model` origin is kept. */
private fun Acceptance.boundTo(version: Int): Acceptance = when (this) {
    is Acceptance.Run -> copy(obligationVersion = version)
    is Acceptance.Check -> copy(obligationVersion = version)
    is Acceptance.Review -> copy(obligationVersion = version)
}
