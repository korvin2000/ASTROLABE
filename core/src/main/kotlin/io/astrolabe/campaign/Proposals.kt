package io.astrolabe.campaign

import io.astrolabe.cell.NoteCandidate
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Shape
import io.astrolabe.graph.Production
import io.astrolabe.graph.RedOkUntil
import io.astrolabe.graph.RequirementGraph
import io.astrolabe.id.ContextId
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.register.Register
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.tool.task.ProposalOutcome
import io.astrolabe.tool.task.Proposals
import io.astrolabe.verify.RefactorChecklist
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Clock

/** A request to split an increment (`task.propose(increment_split)`): the controller re-plans within authorized coverage. */
@Serializable
public data class IncrementSplit(val increment: String, val reason: String, val parts: List<String> = emptyList()) {
    init {
        require(increment.isNotBlank() && reason.isNotBlank()) { "a split names its increment and a reason" }
    }
}

/** A split request as stored: it reaches the plan role as a packet. */
@Serializable
public data class StoredSplit(val id: String, val cell: ContextId?, val split: IncrementSplit)

/** Split requests (`packets`, kind `increment_split`), oldest first: the plan role's inbox. */
public interface SplitRequests {
    public fun record(ids: Identities, split: IncrementSplit): StoredSplit

    public fun forPlanRole(work: WorkId): List<StoredSplit>
}

public class InMemorySplitRequests(private val idGen: IdGen) : SplitRequests {
    private val splits = ArrayList<Pair<WorkId, StoredSplit>>()

    @Synchronized
    override fun record(ids: Identities, split: IncrementSplit): StoredSplit =
        StoredSplit(idGen.next("split"), ids.context, split).also { splits += ids.work to it }

    @Synchronized
    override fun forPlanRole(work: WorkId): List<StoredSplit> = splits.filter { it.first == work }.map { it.second }
}

public class SqliteSplitRequests(private val store: Store, private val idGen: IdGen, private val clock: Clock) : SplitRequests {
    override fun record(ids: Identities, split: IncrementSplit): StoredSplit = store.db.tx { tx ->
        val stored = StoredSplit(idGen.next("split"), ids.context, split)
        tx.execute(
            "INSERT INTO packets (id, work_id, attempt_id, candidate_id, context_id, kind, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            stored.id, ids.work, ids.attempt, ids.candidate, ids.context, KIND, Migrations.SCHEMA_VERSION, clock.instant(),
            JSON.encodeToString(StoredSplit.serializer(), stored),
        )
        stored
    }

    override fun forPlanRole(work: WorkId): List<StoredSplit> = store.db.query(
        "SELECT body FROM packets WHERE work_id = ? AND kind = ? ORDER BY rowid", work, KIND,
    ) { JSON.decodeFromString(StoredSplit.serializer(), it.string("body")) }

    internal fun consumed(requests: List<StoredSplit>) {
        store.db.tx { tx -> requests.forEach { tx.execute("UPDATE packets SET kind = ? WHERE id = ? AND kind = ?", "increment_split_consumed", it.id, KIND) } }
    }

    private companion object {
        const val KIND = "increment_split"
        val JSON = Json { encodeDefaults = true }
    }
}

/**
 * The controller's [Proposals] intake (P2.1.5). A plan is parsed from its wire form (snake_case, below) into a
 * [PlanPacket] whose decision packets are the register's `decision.add` records — boundary-crossing ones also
 * become ADR candidates — and recorded with the validator's current gaps in the reply; a split is recorded for
 * the plan role. Neither touches the contract or the graph.
 */
public class CampaignProposals(
    private val plans: PlanProposals,
    private val splits: SplitRequests,
    private val contract: () -> Contract?,
    private val register: () -> Register?,
    /** The knowledge base's `CON` notes for the §8.9 item 4 check; none by default. */
    private val conAnchors: () -> Map<String, Set<String>> = { emptyMap() },
) : Proposals {
    override fun plan(ids: Identities, proposal: JsonElement): ProposalOutcome {
        val current = contract()
        val ignored = ArrayList<String>()
        val packet = try {
            PlanForm.lint(proposal, PlanWire.serializer().descriptor, "", ignored)?.let { return ProposalOutcome.Refused("$it; $PLAN_FORM") }
            LENIENT.decodeFromJsonElement(PlanWire.serializer(), proposal).toPacket(register()?.decisions.orEmpty(), current)
        } catch (bad: SerializationException) {
            return ProposalOutcome.Refused((bad.message?.lineSequence()?.first() ?: "malformed plan") + "; $PLAN_FORM")
        } catch (bad: IllegalArgumentException) {
            return ProposalOutcome.Refused((bad.message ?: "malformed plan") + "; $PLAN_FORM")
        } catch (bad: IllegalStateException) {
            return ProposalOutcome.Refused((bad.message ?: "malformed plan") + "; $PLAN_FORM")
        }
        val stored = plans.record(ids, packet)
        val anchors = conAnchors()
        val gaps = current?.let { PlanPacketValidator.gaps(it, packet, anchors) }
        val planning = if (anchors.isEmpty()) current?.let { PlanPacketValidator.planningGaps(it, packet) }.orEmpty() else emptyList()
        val check = when {
            gaps == null -> "not checked (no contract)"
            gaps.isEmpty() -> "passes the controller's checks"
            else -> "gaps: " + gaps.joinToString("; ")
        } + (if (planning.isEmpty()) "" else "; " + planning.joinToString("; ")) +
            (if (ignored.isEmpty()) "" else "; ignored keys: ${ignored.joinToString(", ")} — see the form")
        return ProposalOutcome.Recorded(stored.id, "${packet.graphProposal.increments.size} increments, ${packet.acceptanceProposals.size} acceptance proposals; $check")
    }

    override fun incrementSplit(ids: Identities, proposal: JsonElement): ProposalOutcome {
        val split = try {
            decode(IncrementSplit.serializer(), proposal)
        } catch (bad: SerializationException) {
            return ProposalOutcome.Refused(bad.message?.lineSequence()?.first() ?: "malformed split")
        } catch (bad: IllegalArgumentException) {
            return ProposalOutcome.Refused(bad.message ?: "malformed split")
        }
        val stored = splits.record(ids, split)
        return ProposalOutcome.Recorded(stored.id, "split of ${split.increment} queued for the plan role")
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, element: JsonElement): T = WIRE.decodeFromJsonElement(serializer, element)

    private companion object {
        val WIRE = Json { ignoreUnknownKeys = false }

        /** Unknown plan keys were already named with their true paths by [PlanForm.lint]; they never refuse. */
        val LENIENT = Json { ignoreUnknownKeys = true }
    }
}

/** The one-line plan form that plan refusals and the "no plan proposed" gap quote (under 400 characters). */
internal const val PLAN_FORM: String =
    """plan form: {"increments":[{"id":"inc-1","requirements":["R1"],"accept":["AC-1"],"title":"…","write_scope":["src/**"]?,"depends_on":[]?,"produces":"artifact"|"resolves:<question>"?}],"acceptance":[{"id":"AC-4","requirement":"R1","run":["npm","test"]|"check":"…"|"review":"…","cwd":"client"?}]?,"ownership":{path:"inc-id"}?}"""

/**
 * A structural lint of a wire proposal against its serializer's descriptor, run before decoding so every finding
 * carries its true path (`increments[0].check`): unknown keys are collected, a wrong type or a missing required
 * field is the returned refusal reason.
 */
internal object PlanForm {
    fun lint(element: JsonElement, descriptor: SerialDescriptor, path: String, ignored: MutableList<String>): String? {
        val at = path.ifEmpty { "the plan" }
        if (element is JsonNull) return if (descriptor.isNullable) null else "$at must not be null"
        return when (descriptor.kind) {
            StructureKind.CLASS -> {
                val fields = element as? JsonObject ?: return "$at must be an object"
                for ((key, value) in fields) {
                    val index = descriptor.getElementIndex(key)
                    val child = if (path.isEmpty()) key else "$path.$key"
                    if (index == CompositeDecoder.UNKNOWN_NAME) ignored += child
                    else lint(value, descriptor.getElementDescriptor(index), child, ignored)?.let { return it }
                }
                (0 until descriptor.elementsCount).firstOrNull { !descriptor.isElementOptional(it) && descriptor.getElementName(it) !in fields }
                    ?.let { (if (path.isEmpty()) "" else "$path.") + descriptor.getElementName(it) + " is missing" }
            }
            StructureKind.LIST -> {
                val items = element as? JsonArray ?: return "$at must be an array"
                items.forEachIndexed { i, item -> lint(item, descriptor.getElementDescriptor(0), "$path[$i]", ignored)?.let { return it } }
                null
            }
            StructureKind.MAP -> {
                val entries = element as? JsonObject ?: return "$at must be an object"
                for ((key, value) in entries) lint(value, descriptor.getElementDescriptor(1), "$path.$key", ignored)?.let { return it }
                null
            }
            PrimitiveKind.STRING -> if ((element as? JsonPrimitive)?.isString == true) null else "$at must be a string"
            PrimitiveKind.INT, PrimitiveKind.LONG ->
                if ((element as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull != null) null else "$at must be an integer"
            PrimitiveKind.BOOLEAN ->
                if ((element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null) null else "$at must be true or false"
            SerialKind.ENUM -> {
                val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
                if ((element as? JsonPrimitive)?.takeIf { it.isString }?.content in names) null else "$at must be one of ${names.joinToString("|")}"
            }
            else -> null
        }
    }
}

/** The model-facing plan form: `produces` is `artifact` or `resolves:<question>`; one of `run`/`check`/`review` per item. */
@Serializable
internal data class PlanWire(
    val increments: List<IncrementWire>,
    val ownership: Map<String, String> = emptyMap(),
    val acceptance: List<AcceptanceWire> = emptyList(),
    val con: List<CandidateWire> = emptyList(),
    val adr: List<CandidateWire> = emptyList(),
    val shape: Shape? = null,
    /** Ids of existing `CON` notes the plan changes or supersedes (§8.9 item 4). */
    @SerialName("con_refs") val conRefs: List<String> = emptyList(),
    /** The §8.9 checklist in refactor mode (P3.5.1); snake_case keys. */
    @SerialName("refactor_checklist") val refactorChecklist: RefactorChecklistWire? = null,
) {
    /** [contract], when committed, supplies the defaults of an increment's absent `requirements` and `accept`. */
    fun toPacket(decisions: List<io.astrolabe.register.Decision>, contract: Contract? = null): PlanPacket = PlanPacket(
        RequirementGraph(resolved(contract).map { it.toIncrement() }, ownership),
        acceptance.map { AcceptanceProposal(it.toAcceptance()) },
        decisions,
        con.map { it.toCandidate("CON") },
        adr.map { it.toCandidate("ADR") } + decisions.filter { it.adrCandidate }.map { d ->
            NoteCandidate("ADR", "${d.text} because ${d.because}" + (d.rejected?.let { "; rejected: $it" } ?: ""), "decision ${d.n}", emptyList(), emptyList())
        },
        shape,
        refactorChecklist?.toChecklist(),
        conRefs,
    )

    /**
     * The proportional defaults, for a one-increment plan only: `id` → `inc-1`; `requirements` → the contract's
     * requirements whose acceptance (or this plan's proposed items) lists any of `accept`, else all of them;
     * `accept` → the chosen requirements' items; `produces` → artifact. A multi-increment plan states all four:
     * S3 admission tells decision work apart only by `produces: resolves:`, so it is never defaulted there.
     */
    private fun resolved(contract: Contract?): List<IncrementWire> {
        if (increments.size > 1) {
            increments.forEachIndexed { index, wire ->
                val missing = listOf("id" to wire.id, "requirements" to wire.requirements, "accept" to wire.accept, "produces" to wire.produces)
                    .firstOrNull { it.second == null }?.first ?: return@forEachIndexed
                throw IllegalArgumentException(
                    "increments[$index].$missing is required in a multi-increment plan" + if (missing == "produces") ": artifact | resolves:<question>" else "",
                )
            }
            return increments
        }
        val proposedFor = acceptance.groupBy({ it.requirement }, { it.id })
        return increments.map { wire ->
            val accepted = wire.accept
            val requirements = wire.requirements ?: when {
                contract == null -> emptyList()
                accepted == null -> contract.requirements.map { it.id }
                else -> contract.requirements.filter { r -> (r.acceptance + proposedFor[r.id].orEmpty()).any { it in accepted } }.map { it.id }
                    .also { require(it.isNotEmpty()) { "increments[0].requirements is absent and no requirement lists accept $accepted" } }
            }
            val accept = accepted ?: requirements.flatMap { r -> contract?.requirement(r)?.acceptance.orEmpty() + proposedFor[r].orEmpty() }.distinct()
            wire.copy(id = wire.id ?: "inc-1", requirements = requirements, accept = accept, produces = wire.produces ?: "artifact")
        }
    }
}

@Serializable
internal data class RefactorChecklistWire(
    @SerialName("behaviour_to_preserve") val behaviourToPreserve: String = "",
    @SerialName("interfaces_to_change") val interfacesToChange: String = "",
    @SerialName("compatibility_duration") val compatibilityDuration: String = "",
    @SerialName("callers_consumers") val callersConsumers: String = "",
    @SerialName("data_configuration_dependencies") val dataConfigurationDependencies: String = "",
    @SerialName("independent_acceptance_checks") val independentAcceptanceChecks: String = "",
    @SerialName("shared_decision") val sharedDecision: String = "",
) {
    fun toChecklist(): RefactorChecklist = RefactorChecklist(
        behaviourToPreserve, interfacesToChange, compatibilityDuration, callersConsumers, dataConfigurationDependencies, independentAcceptanceChecks, sharedDecision,
    )
}

@Serializable
internal data class IncrementWire(
    val id: String? = null,
    val requirements: List<String>? = null,
    val accept: List<String>? = null,
    @SerialName("write_scope") val writeScope: List<String> = emptyList(),
    @SerialName("expected_files") val expectedFiles: Int = 0,
    val title: String = "",
    @SerialName("depends_on") val dependsOn: List<String> = emptyList(),
    val produces: String? = null,
    @SerialName("evidence_kinds") val evidenceKinds: Map<String, String> = emptyMap(),
    @SerialName("red_ok_until") val redOkUntil: String? = null,
) {
    fun toIncrement(): Increment = Increment(
        id.orEmpty(), requirements.orEmpty(), accept.orEmpty(), writeScope, expectedFiles, title = title, dependsOn = dependsOn,
        redOkUntil = when (redOkUntil) {
            null -> null
            "increment_end" -> RedOkUntil.IncrementEnd
            else -> throw IllegalArgumentException("increment $id: red_ok_until must be increment_end")
        },
        produces = when {
            produces == null -> null
            produces == "artifact" -> Production.Artifact
            produces.startsWith("resolves:") -> Production.Resolves(produces.removePrefix("resolves:").trim())
            else -> throw IllegalArgumentException("increment $id: produces must be artifact or resolves:<question>")
        },
        evidenceKinds = evidenceKinds,
    )
}

@Serializable
internal data class AcceptanceWire(
    val id: String,
    val requirement: String,
    val run: List<String>? = null,
    val cwd: String? = null,
    val check: String? = null,
    val review: String? = null,
) {
    fun toAcceptance(): Acceptance {
        require(listOfNotNull(run, check, review).size == 1) { "acceptance $id needs exactly one of run, check, review" }
        val origin = Origin.Model(requirement)
        return when {
            run != null -> Acceptance.Run(id, Command(run, cwd), origin)
            check != null -> Acceptance.Check(id, check, origin)
            else -> Acceptance.Review(id, review!!, origin)
        }
    }
}

@Serializable
internal data class CandidateWire(
    val summary: String,
    val scope: String = "",
    val evidence: List<String> = emptyList(),
    val anchors: List<String> = emptyList(),
) {
    fun toCandidate(kind: String): NoteCandidate = NoteCandidate(kind, summary, scope, evidence, anchors)
}
