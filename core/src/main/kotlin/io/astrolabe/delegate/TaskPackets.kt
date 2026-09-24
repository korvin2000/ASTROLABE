package io.astrolabe.delegate

import io.astrolabe.auth.Ceiling
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Contract
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkspaceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A `task.delegate` request assembled into a packet, or why it could not be. */
public sealed interface Assembled {
    public data class Packet(val packet: TaskPacket) : Assembled

    public data class Invalid(val reason: String) : Assembled
}

/**
 * Assembles a [TaskPacket] from a cell's `task.delegate` request (D-105): the model names requirement and constraint
 * ids, scopes, evidence, uncertainties and a budget; the runtime copies the exact excerpts and authority refs from
 * the committed contract at its current version. The model never writes an excerpt, so a child can only ever see
 * contract text (D13). The dispatch candidate is the dispatching cell's own candidate.
 */
public class TaskPackets(
    private val workspace: WorkspaceId,
    private val ceiling: Ceiling,
    private val generation: ExecutionGeneration,
) {
    public fun assemble(contract: Contract, ids: Identities, kind: ChildKind, request: JsonElement?): Assembled {
        val fields = request as? JsonObject ?: return Assembled.Invalid("delegate needs a packet object: {\"increment\": …, \"requirements\": [ids], \"constraints\": [ids], \"uncertainties\": […], \"requiredEvidence\": […], \"readScope\": […], \"writeScope\": […], \"budgetTokens\": n}")
        val increment = fields.text("increment") ?: return Assembled.Invalid("a packet names its increment")
        val candidate = ids.candidate ?: return Assembled.Invalid("no dispatch candidate: the dispatching cell has no stamp")
        val requirementIds = fields.texts("requirements")
        val constraintIds = fields.texts("constraints")
        val requirements = requirementIds.map { id ->
            val r = contract.requirements.firstOrNull { it.id == id } ?: return Assembled.Invalid("unknown requirement '$id' in contract v${contract.version}")
            Excerpt(r.id, r.text, r.authorityRef)
        }
        val constraints = constraintIds.map { id ->
            val c = contract.constraints.firstOrNull { it.id == id } ?: return Assembled.Invalid("unknown constraint '$id' in contract v${contract.version}")
            Excerpt(c.id, c.text, c.authority)
        }
        val uncertainties = fields.texts("uncertainties")
        if (requirements.isEmpty() && uncertainties.isEmpty()) return Assembled.Invalid("a packet carries a bounded deliverable: requirement ids or uncertainties")
        val budget = (fields["budgetTokens"] as? JsonPrimitive)?.longOrNull ?: return Assembled.Invalid("a packet reserves a budget: budgetTokens")
        if (budget <= 0) return Assembled.Invalid("budgetTokens must be positive")
        val writeScope = fields.texts("writeScope")
        if (kind != ChildKind.Writer && writeScope.isNotEmpty()) return Assembled.Invalid("a ${kind.wire} is read-only: no writeScope")
        return try {
            Assembled.Packet(
                TaskPacket(
                    ids = ids, incrementId = increment, role = kind.role, requirements = requirements, constraints = constraints,
                    contractVersion = contract.version, dispatchCandidate = candidate, workspace = workspace,
                    requiredEvidence = fields.texts("requiredEvidence"), uncertainties = uncertainties,
                    readScope = fields.texts("readScope"), writeScope = writeScope, capabilityCeiling = ceiling,
                    reservedBudget = Tokens(budget), executionGeneration = generation,
                ),
            )
        } catch (invalid: IllegalArgumentException) {
            Assembled.Invalid(invalid.message ?: "invalid packet")
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.texts(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.orEmpty()
}
