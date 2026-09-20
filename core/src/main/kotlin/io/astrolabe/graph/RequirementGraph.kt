package io.astrolabe.graph

import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Ledger
import io.astrolabe.contract.LedgerEntry
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.verify.CompletionResult
import io.astrolabe.workspace.PathPattern
import kotlinx.serialization.Serializable
import java.util.Collections

/**
 * Controller-owned graph (§4.2, P2.1.1). Proposals must be validated before storage; their runtime status and
 * evidence are never authoritative. All transitions return a new snapshot, leaving cancelled history intact.
 * Persistence, leases and live currency refresh belong to the campaign controller (P2.2), not this component.
 */
@Serializable(with = GraphSerializer::class)
public class RequirementGraph(
    increments: List<Increment>,
    ownershipMap: Map<String, String> = emptyMap(),
    evidence: Map<String, IncrementEvidence> = emptyMap(),
) {
    public val increments: List<Increment> = frozen(increments.map { it.copy(
        requirementIds = frozen(it.requirementIds), accept = frozen(it.accept), writeScope = frozen(it.writeScope),
        dependsOn = frozen(it.dependsOn), cells = frozen(it.cells), evidenceKinds = frozen(it.evidenceKinds),
    ) })
    public val ownershipMap: Map<String, String> = frozen(ownershipMap)
    public val evidence: Map<String, IncrementEvidence> = frozen(evidence.mapValues { (_, value) ->
        value.copy(evidenceRefs = frozen(value.evidenceRefs))
    })

    init {
        require(increments.map { it.id }.toSet().size == increments.size) { "increment ids must be unique" }
    }

    public fun copy(
        increments: List<Increment> = this.increments,
        ownershipMap: Map<String, String> = this.ownershipMap,
        evidence: Map<String, IncrementEvidence> = this.evidence,
    ): RequirementGraph = RequirementGraph(increments, ownershipMap, evidence)

    override fun equals(other: Any?): Boolean = other is RequirementGraph &&
        increments == other.increments && ownershipMap == other.ownershipMap && evidence == other.evidence

    override fun hashCode(): Int = 31 * (31 * increments.hashCode() + ownershipMap.hashCode()) + evidence.hashCode()

    /** Derived, so a caller cannot silently drop a verified increment's regression obligation. */
    public val regressionObligations: Set<String>
        get() = increments.filter { it.status == IncrementStatus.Verified }.map { it.id }.toSortedSet()

    /** Deterministic diagnostics; missing prerequisites never look like an empty, successful plan. */
    public fun validate(contract: Contract): List<GraphIssue> {
        val issues = ArrayList<GraphIssue>()
        fun issue(code: GraphIssueCode, id: String?, detail: String) {
            issues += GraphIssue(code, listOfNotNull(id), detail)
        }
        val byId = increments.associateBy { it.id }
        val requirements = contract.requirements.associateBy { it.id }
        val acceptance = contract.acceptance.associateBy { it.id }
        val active = increments.filter { it.status != IncrementStatus.Cancelled }
        val coverage = active.flatMap { inc -> inc.requirementIds.map { it to inc } }.groupBy({ it.first }, { it.second })
        for (increment in active.sortedBy { it.id }) {
            val id = increment.id
            for (requirement in increment.requirementIds.distinct().sorted()) if (requirement !in requirements) {
                issue(GraphIssueCode.UnknownRequirement, id, "unknown requirement $requirement")
            }
            for (item in increment.accept.distinct().sorted()) if (item !in acceptance) {
                issue(GraphIssueCode.UnknownAcceptance, id, "unknown acceptance $item")
            }
            if (increment.accept.none { item ->
                    acceptance[item] is Acceptance.Run ||
                        acceptance[item] is Acceptance.Check && !increment.evidenceKinds[item].isNullOrBlank()
                }) {
                issue(GraphIssueCode.MissingExecutableAcceptance, id, "needs a run or check with a named evidence kind")
            }
            if (increment.produces == null) issue(GraphIssueCode.MissingProduction, id, "needs an artifact or named uncertainty")
            for (dependency in increment.dependsOn.distinct().sorted()) when (byId[dependency]?.status) {
                null -> issue(GraphIssueCode.UnknownDependency, id, "unknown dependency $dependency")
                IncrementStatus.Cancelled -> issue(GraphIssueCode.CancelledDependency, id, "dependency $dependency was cancelled; replan explicitly")
                else -> Unit
            }
            if (increment.status == IncrementStatus.Verified && !supported(increment)) {
                issue(GraphIssueCode.UnsupportedVerification, id, "verified status lacks evidence for this increment definition")
            }
        }
        for (requirement in contract.requirements.sortedBy { it.id }) {
            val assigned = coverage[requirement.id].orEmpty()
            if (assigned.isEmpty()) issue(GraphIssueCode.UncoveredRequirement, null, "uncovered requirement ${requirement.id}")
            val covered = assigned.flatMap { it.accept }.toSet()
            for (item in (requirement.acceptance.toSet() - covered).sorted()) {
                issue(GraphIssueCode.UncoveredAcceptance, null, "${requirement.id} has uncovered acceptance $item")
            }
            for (dependency in requirement.dependsOn.distinct().sorted()) if (dependency !in requirements) {
                issue(GraphIssueCode.UnknownRequirement, null, "${requirement.id} depends on unknown requirement $dependency")
            }
        }
        issues += requirementDependencies(contract, active, coverage)
        for (component in cycles(active.associate { inc -> inc.id to inc.dependsOn.toSet() })) {
            issues += GraphIssue(GraphIssueCode.DependencyCycle, component, "merge into one joint increment or record a planning conflict")
        }
        for ((path, owner) in ownershipMap.toSortedMap()) {
            val increment = byId[owner]
            if (increment == null || increment.status == IncrementStatus.Cancelled ||
                !contract.scope.allowsWrite(path) || increment.writeScope.none { PathPattern.matches(it, path) }) {
                issue(GraphIssueCode.InvalidOwnership, owner, "ownership of $path is outside the active owner's authorized scope")
            }
        }
        for (id in evidence.keys.sorted()) if (byId[id]?.status != IncrementStatus.Verified) {
            issue(GraphIssueCode.UnsupportedVerification, id, "evidence has no verified increment")
        }
        return issues
    }

    /**
     * Available cell slots AFTER the controller's reservations (D-54), not the contract's original budget.
     * This selects candidates only: token/money/lease admission must still run before dispatch.
     * Order = longest prerequisite depth, then id. Verified nodes are never executable, even when stale.
     */
    public fun readyFrontier(contract: Contract, availableCells: Int): List<Increment> {
        require(availableCells >= 0) { "available cells must be non-negative" }
        val verified = increments.filter { supported(it) && currentIdentity(contract, evidence.getValue(it.id)) }
            .mapTo(HashSet()) { it.id }
        val conflicts = validate(contract)
        check(conflicts.isEmpty()) { "invalid requirement graph: ${conflicts.joinToString { it.detail }}" }
        // Cancelled nodes remain terminal leaves for ordering; active edges to them cannot become ready.
        val depth = depths(increments.associate { inc ->
            inc.id to if (inc.status == IncrementStatus.Cancelled) emptySet() else inc.dependsOn.toSet()
        })
        return increments.asSequence().filter { inc ->
            (inc.status == IncrementStatus.Pending || inc.status == IncrementStatus.InProgress) &&
                inc.dependsOn.all { it in verified }
        }.sortedWith(compareBy<Increment> { depth.getValue(it.id) }.thenBy { it.id }).take(availableCells).toList()
    }

    /** Opens/continues one increment without resetting counters; repeating the current cell is idempotent. */
    public fun continueIncrement(contract: Contract, id: String, cell: ContextId): RequirementGraph {
        val increment = increments.single { it.id == id }
        check(increment.status == IncrementStatus.Pending || increment.status == IncrementStatus.InProgress) {
            "$id is ${increment.status}; verified work is regression-only (FX-42)"
        }
        check(readyFrontier(contract, increments.size).any { it.id == id }) { "$id has unfinished prerequisites" }
        if (increment.cells.lastOrNull() == cell) return this
        check(increments.none { cell in it.cells }) { "cell $cell was already used" }
        return replace(increment.copy(status = IncrementStatus.InProgress, cells = increment.cells + cell))
    }

    public fun cancel(id: String, reason: String): RequirementGraph {
        require(reason.isNotBlank()) { "cancellation requires a reason" }
        val increment = increments.single { it.id == id }
        check(increment.status != IncrementStatus.Verified && increment.status != IncrementStatus.Cancelled) {
            "$id is already terminal"
        }
        return replace(increment.copy(status = IncrementStatus.Cancelled, cancelledReason = reason))
    }

    /**
     * Commit a verifier result, or refresh regression evidence without starting another implementation cell.
     * The controller must check candidate/lease/generation before committing this snapshot (P2.2.2).
     */
    public fun recordAccepted(contract: Contract, result: CompletionResult.Accepted): RequirementGraph {
        require(result.workId == contract.workId) { "completion belongs to another work" }
        require(result.attemptId == contract.attemptId) { "completion belongs to another attempt" }
        require(result.contractVersion == contract.version) { "completion belongs to another contract version" }
        val increment = increments.single { it.id == result.incrementId }
        require(result.incrementDefinition == increment.definitionDigest()) { "increment changed since verification" }
        require(result.contextId == increment.cells.lastOrNull() && result.contextId != null) {
            "completion belongs to a superseded or unknown cell"
        }
        check(increment.status == IncrementStatus.InProgress || increment.status == IncrementStatus.Verified) {
            "completion requires an active increment or regression obligation"
        }
        require(validate(contract).isEmpty()) { "cannot accept an invalid requirement graph" }
        val record = IncrementEvidence(
            contract.workId, contract.attemptId, contract.version, result.resultingStamp, result.contextId,
            increment.definitionDigest(),
            result.evidenceRefs.distinct().sorted(),
        )
        return replace(increment.copy(status = IncrementStatus.Verified)).copy(evidence = evidence + (increment.id to record))
    }

    /**
     * Derive requirement progress from ALL assigned active increments and current verifier evidence.
     * A changed candidate/contract invalidates currency, not execution history. Cross-candidate reuse proofs
     * belong to P3.1.2; until then equality is deliberately conservative. Model-authored requirement.status
     * and the S0 verifier's per-increment ledger cannot certify a partially implemented S1 requirement.
     */
    public fun ledger(contract: Contract, stamp: CandidateId): Ledger {
        val active = increments.filter { it.status != IncrementStatus.Cancelled }
        val byRequirement = active.flatMap { inc -> inc.requirementIds.map { it to inc } }.groupBy({ it.first }, { it.second })
        val byId = increments.associateBy { it.id }
        val valid = validate(contract).isEmpty()
        val entries = contract.requirements.associate { requirement ->
            val assigned = byRequirement[requirement.id].orEmpty()
            val records = assigned.mapNotNull { evidence[it.id] }
            val covered = assigned.flatMap { it.accept }.toSet().containsAll(requirement.acceptance)
            val verified = valid && assigned.isNotEmpty() && covered && assigned.all { inc ->
                supported(inc) && evidence.getValue(inc.id).let {
                    currentIdentity(contract, it) && it.stamp == stamp
                }
            }
            val blocked = !valid || assigned.isEmpty() || assigned.any { inc ->
                inc.status == IncrementStatus.Blocked || inc.dependsOn.any {
                    byId[it] == null || byId[it]?.status == IncrementStatus.Cancelled || byId[it]?.status == IncrementStatus.Blocked
                }
            }
            val status = when {
                verified -> RequirementStatus.Verified
                blocked -> RequirementStatus.Blocked
                assigned.any { it.status == IncrementStatus.InProgress || it.status == IncrementStatus.Verified } -> RequirementStatus.InProgress
                else -> RequirementStatus.Pending
            }
            requirement.id to LedgerEntry(requirement.id, status, records.flatMap { it.evidenceRefs }.distinct().sorted(), verified)
        }.toMutableMap()
        // An invalid prerequisite invalidates its transitive dependents, including requirement cycles.
        val dependents = contract.requirements.flatMap { r -> r.dependsOn.map { it to r.id } }
            .groupBy({ it.first }, { it.second })
        val pending = ArrayDeque(entries.values.filter { !it.stampValid }.map { it.requirementId })
        while (pending.isNotEmpty()) for (id in dependents[pending.removeFirst()].orEmpty()) {
            val entry = entries.getValue(id)
            if (entry.stampValid) {
                entries[id] = entry.copy(status = RequirementStatus.InProgress, stampValid = false)
                pending.addLast(id)
            }
        }
        return Ledger(entries)
    }

    private fun supported(increment: Increment): Boolean = increment.status == IncrementStatus.Verified &&
        evidence[increment.id]?.let {
            it.definition == increment.definitionDigest() && it.contextId == increment.cells.lastOrNull()
        } == true

    private fun currentIdentity(contract: Contract, record: IncrementEvidence): Boolean =
        record.workId == contract.workId && record.attemptId == contract.attemptId && record.contractVersion == contract.version

    private fun replace(increment: Increment): RequirementGraph =
        copy(increments = increments.map { if (it.id == increment.id) increment else it })

}

private fun <T> frozen(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun <K, V> frozen(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
