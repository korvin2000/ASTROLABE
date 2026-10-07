package io.astrolabe.context

import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Constraint
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Requirement
import kotlinx.serialization.Serializable

/**
 * The mandatory `[K]` contract slice (§5.1, §6.1 as refined by D-52): the objective (task-workflow §1.2), this
 * increment's requirements verbatim with their status, ALL constraints and exclusions, and the **complete** applicable
 * acceptance definitions (command, criterion text, origin, obligation version, requirement links). An id without its
 * definition never passes coverage.
 */
@Serializable
public data class ContractSlice(
    val contractVersion: Int,
    val incrementId: String,
    val requirements: List<Requirement>,
    val constraints: List<Constraint>,
    val exclusions: List<String>,
    val acceptance: List<Acceptance>,
    /** Original obligations shown beside a weakened check (§8.6); empty until the test-integrity guard reports one. */
    val originalObligations: Map<String, String> = emptyMap(),
    val contractsTouched: List<String> = emptyList(),
    /** Independent obligations retained across copying and serialization, including increment-only checks. */
    val incrementAcceptanceIds: Set<String> = acceptance.map { it.id }.toSet(),
    /** T-50: the currently authorized objective — the original request plus every amendment ([Contract.objective]). */
    val objective: String = "",
) {
    /** The constructor before [objective] (P8.C.17). Kept for Java callers. */
    public constructor(
        contractVersion: Int, incrementId: String, requirements: List<Requirement>, constraints: List<Constraint>, exclusions: List<String>,
        acceptance: List<Acceptance>, originalObligations: Map<String, String>, contractsTouched: List<String>, incrementAcceptanceIds: Set<String>,
    ) : this(contractVersion, incrementId, requirements, constraints, exclusions, acceptance, originalObligations, contractsTouched, incrementAcceptanceIds, "")

    /** Acceptance ids the slice must define: every id the increment accepts plus every id its requirements name. */
    val requiredAcceptanceIds: Set<String>
        get() = incrementAcceptanceIds + requirements.flatMap { it.acceptance }

    /** IX-11: an acceptance id present without its complete definition fails coverage. */
    public fun coverage(): SliceCoverage {
        val defined = acceptance.map { it.id }.toSet()
        val missing = requiredAcceptanceIds - defined
        return SliceCoverage(complete = missing.isEmpty(), missingAcceptance = missing.sorted())
    }

    /** Verbatim `[K]` text; byte-stable for equal inputs. */
    public fun render(): String {
        val sb = StringBuilder()
        sb.append("[K] contract v").append(contractVersion).append(" · increment ").append(incrementId).append('\n')
        if (objective.isNotEmpty()) sb.append("objective: ").append(objective).append('\n')
        requirements.forEach { sb.append(line(it)).append('\n') }
        acceptance.forEach { sb.append(line(it)).append('\n') }
        if (constraints.isNotEmpty()) sb.append("constraints: ").append(constraints.joinToString(" · ") { "${it.id} ${it.text} (${it.authority})" }).append('\n')
        if (exclusions.isNotEmpty()) sb.append("exclusions: ").append(exclusions.joinToString(", ")).append('\n')
        if (contractsTouched.isNotEmpty()) sb.append("contracts touched: ").append(contractsTouched.joinToString(", ")).append('\n')
        return sb.toString()
    }

    /**
     * The authoritative delta from [previous] to this slice (task-workflow §4.4, WF-15): the one line a revision made
     * mid-cell appends to `[T]` while `[K]` stays as built — `[contract v4 delta] R2 added: …; AC-5 added: …; AC-1 → …`.
     * Each item is named by id with its new rendered line, in this slice's order, removals last; byte-stable for equal
     * inputs. `null` when nothing differs, the version included.
     */
    public fun delta(previous: ContractSlice): String? {
        if (this == previous) return null
        val parts = ArrayList<String>()
        fun <T> diff(before: List<T>, after: List<T>, id: (T) -> String, render: (T) -> String, renderBefore: (T) -> String = render) {
            val old = before.associateBy(id)
            val ids = after.map(id).toSet()
            for (item in after) {
                val was = old[id(item)]
                when {
                    was == null -> parts += "${id(item)} added: ${render(item)}"
                    renderBefore(was) != render(item) -> parts += "${id(item)} → ${render(item)}"
                }
            }
            before.filter { id(it) !in ids }.forEach { parts += "${id(it)} removed" }
        }
        diff(previous.requirements, requirements, { it.id }, { line(it).substringAfter(": ") })
        diff(previous.acceptance, acceptance, { it.id }, { line(it).substringAfter(' ') }, { previous.line(it).substringAfter(' ') })
        diff(previous.constraints, constraints, { it.id }, { "${it.text} (${it.authority})" })
        diff(previous.exclusions, exclusions, { "exclusion $it" }, { it })
        diff(previous.contractsTouched, contractsTouched, { "contract $it" }, { it })
        if (objective != previous.objective) parts += "objective → ${objective.replace("\n", " / ")}"
        if (incrementId != previous.incrementId) parts += "increment ${previous.incrementId} → $incrementId"
        if (parts.isEmpty()) parts += "no change to increment $incrementId"
        return "[contract v$contractVersion delta] " + parts.joinToString("; ")
    }

    private fun line(r: Requirement): String = "${r.id}: ${r.text}  accept: ${r.acceptance.joinToString(", ")}" +
        // The contract's own status: the campaign ledger, not the contract, tracks progress, so `pending` is not shown.
        (if (r.status == io.astrolabe.contract.RequirementStatus.Pending) "" else "  status: ${r.status.wire}")

    private fun line(a: Acceptance): String = buildString {
        append(a.id).append(" (").append(a.origin).append(", v").append(a.obligationVersion).append("): ").append(a.criterion)
        when (a) {
            is Acceptance.Run -> a.command.cwd?.let { append("  cwd: ").append(it) }
            is Acceptance.Check -> a.evidenceRef?.let { append("  evidence: ").append(it) }
            is Acceptance.Review -> a.signedBy?.let { append("  signed: ").append(it) }
        }
        originalObligations[a.id]?.let { append("\n  original obligation: ").append(it) }
    }

    public companion object {
        /** Builds the slice for [increment]: its requirements, all constraints/exclusions, complete acceptance definitions. */
        @JvmStatic
        public fun forIncrement(contract: Contract, increment: Increment, originalObligations: Map<String, String> = emptyMap()): ContractSlice {
            val requirements = increment.requirementIds.map { id ->
                contract.requirement(id) ?: throw IllegalArgumentException("increment ${increment.id} names unknown requirement $id")
            }
            val ids = (increment.accept + requirements.flatMap { it.acceptance }).distinct()
            val acceptance = ids.map { id -> contract.acceptance(id) ?: throw IllegalArgumentException("increment ${increment.id} names unknown acceptance $id") }
            return ContractSlice(
                contractVersion = contract.version,
                incrementId = increment.id,
                requirements = requirements,
                constraints = contract.constraints,
                exclusions = contract.exclusions,
                acceptance = acceptance,
                originalObligations = originalObligations,
                contractsTouched = contract.contractsTouched,
                incrementAcceptanceIds = increment.accept.toSet(),
                objective = contract.objective,
            )
        }
    }
}

@Serializable
public data class SliceCoverage(val complete: Boolean, val missingAcceptance: List<String>)
