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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
) : Proposals {
    override fun plan(ids: Identities, proposal: JsonElement): ProposalOutcome {
        val packet = try {
            decode(PlanWire.serializer(), proposal).toPacket(register()?.decisions.orEmpty())
        } catch (bad: SerializationException) {
            return ProposalOutcome.Refused(bad.message?.lineSequence()?.first() ?: "malformed plan")
        } catch (bad: IllegalArgumentException) {
            return ProposalOutcome.Refused(bad.message ?: "malformed plan")
        } catch (bad: IllegalStateException) {
            return ProposalOutcome.Refused(bad.message ?: "malformed plan")
        }
        val stored = plans.record(ids, packet)
        val gaps = contract()?.let { PlanPacketValidator.gaps(it, packet) }
        val check = when {
            gaps == null -> "not checked (no contract)"
            gaps.isEmpty() -> "passes the controller's checks"
            else -> "gaps: " + gaps.joinToString("; ")
        }
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
) {
    fun toPacket(decisions: List<io.astrolabe.register.Decision>): PlanPacket = PlanPacket(
        RequirementGraph(increments.map { it.toIncrement() }, ownership),
        acceptance.map { AcceptanceProposal(it.toAcceptance()) },
        decisions,
        con.map { it.toCandidate("CON") },
        adr.map { it.toCandidate("ADR") } + decisions.filter { it.adrCandidate }.map { d ->
            NoteCandidate("ADR", "${d.text} because ${d.because}" + (d.rejected?.let { "; rejected: $it" } ?: ""), "decision ${d.n}", emptyList(), emptyList())
        },
        shape,
    )
}

@Serializable
internal data class IncrementWire(
    val id: String,
    val requirements: List<String>,
    val accept: List<String>,
    @SerialName("write_scope") val writeScope: List<String> = emptyList(),
    @SerialName("expected_files") val expectedFiles: Int = 0,
    val title: String = "",
    @SerialName("depends_on") val dependsOn: List<String> = emptyList(),
    val produces: String? = null,
    @SerialName("evidence_kinds") val evidenceKinds: Map<String, String> = emptyMap(),
    @SerialName("red_ok_until") val redOkUntil: String? = null,
) {
    fun toIncrement(): Increment = Increment(
        id, requirements, accept, writeScope, expectedFiles, title = title, dependsOn = dependsOn,
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
