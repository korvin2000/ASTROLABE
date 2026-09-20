package io.astrolabe.context

import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Constraint
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Requirement
import kotlinx.serialization.Serializable

/**
 * The mandatory `[K]` contract slice (§5.1, §6.1 as refined by D-52): this increment's requirements verbatim,
 * ALL constraints and exclusions, and the **complete** applicable acceptance definitions (command, criterion
 * text, origin, obligation version, requirement links). An id without its definition never passes coverage.
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
) {
    /** Acceptance ids the slice must define: every id the increment accepts plus every id its requirements name. */
    val requiredAcceptanceIds: Set<String>
        get() = (requirements.flatMap { it.acceptance }).toSet()

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
        requirements.forEach { r ->
            sb.append(r.id).append(": ").append(r.text).append("  accept: ").append(r.acceptance.joinToString(", ")).append('\n')
        }
        acceptance.forEach { a ->
            sb.append(a.id).append(" (").append(a.origin).append(", v").append(a.obligationVersion).append("): ").append(a.criterion)
            when (a) {
                is Acceptance.Run -> a.command.cwd?.let { sb.append("  cwd: ").append(it) }
                is Acceptance.Check -> a.evidenceRef?.let { sb.append("  evidence: ").append(it) }
                is Acceptance.Review -> a.signedBy?.let { sb.append("  signed: ").append(it) }
            }
            originalObligations[a.id]?.let { sb.append("\n  original obligation: ").append(it) }
            sb.append('\n')
        }
        if (constraints.isNotEmpty()) sb.append("constraints: ").append(constraints.joinToString(" · ") { "${it.id} ${it.text} (${it.authority})" }).append('\n')
        if (exclusions.isNotEmpty()) sb.append("exclusions: ").append(exclusions.joinToString(", ")).append('\n')
        if (contractsTouched.isNotEmpty()) sb.append("contracts touched: ").append(contractsTouched.joinToString(", ")).append('\n')
        return sb.toString()
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
            )
        }
    }
}

@Serializable
public data class SliceCoverage(val complete: Boolean, val missingAcceptance: List<String>)
