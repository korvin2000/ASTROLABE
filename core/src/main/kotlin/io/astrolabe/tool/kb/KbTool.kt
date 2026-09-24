package io.astrolabe.tool.kb

import io.astrolabe.auth.InstructionShape
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.kb.Kb
import io.astrolabe.kb.Note
import io.astrolabe.kb.NoteAnchor
import io.astrolabe.kb.NoteBasis
import io.astrolabe.kb.NoteKind
import io.astrolabe.kb.NoteOrigin
import io.astrolabe.kb.NoteRefused
import io.astrolabe.kb.NoteStatus
import io.astrolabe.kb.NoteValidity
import io.astrolabe.kb.Queue
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.ToolMask
import io.astrolabe.tool.Args
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.Effects
import io.astrolabe.tool.EnvelopeHeader
import io.astrolabe.tool.KbArgs
import io.astrolabe.tool.RuntimeFields
import io.astrolabe.tool.ToolCall
import io.astrolabe.tool.ToolExecutor
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOps
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The model's `kb(propose)` payload (§4.5, P4.1.3): a candidate's front matter; id, status and origin are the harness's. */
@Serializable
public data class ProposedNote(
    val kind: NoteKind,
    val summary: String,
    val body: String,
    val scope: String,
    val anchors: List<NoteAnchor> = emptyList(),
    val confidence: Double? = null,
    val evidenceRefs: List<String> = emptyList(),
    val requirementRefs: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
)

/**
 * The `kb` family (§5.4, TODO P1.6.10): `search`, `get` and `skill` read the [Kb]; `propose` (P4.1.3) queues a
 * candidate for the curator — the model proposes, never admits (L9). The envelope carries the knowledge base's own
 * completeness: on the S0 empty base every search is complete and empty — never "absent" — and a stale hit is
 * labelled, never served as current.
 */
public class KbTool @JvmOverloads constructor(
    private val kb: Kb,
    private val estimator: TokenEstimator,
    private val idGen: IdGen,
    private val mask: ToolMask = ToolOps.implementingS0,
    private val maxHits: Int = 20,
    /** The admission queue; without one `propose` answers `unsupported`. */
    private val queue: Queue? = null,
    private val ids: Identities? = null,
    private val events: Events? = null,
) : ToolExecutor {
    /** The `CON` anchors the contract-touch gate reads (§5.6). */
    public fun contractAnchors(): Map<String, Set<String>> = kb.contractAnchors()

    init {
        require(maxHits > 0) { "maxHits must be positive" }
    }

    override suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome {
        require(call.family == ToolFamily.Kb) { "not a kb call: ${call.name}" }
        val args = (call.args as Args.Kb).args
        if (!mask.allows(call.name)) return result("masked", "${call.name} is masked in this role (the curator admits proposals from P4.1)", "kb", complete = true)
        return when (args.op) {
            "search" -> search(args)
            "get" -> entry(args, "note") { kb.get(it) }
            "skill" -> entry(args, "skill") { kb.skill(it) }
            "propose" -> propose(args)
            else -> result("masked", "${call.name} is masked in this role", "kb", complete = true)
        }
    }

    private fun search(args: KbArgs): ToolOutcome {
        val hits = kb.search(args.query!!, args.kinds?.toSet(), args.scope, args.why!!)
        val shown = hits.hits.take(maxHits)
        val head = "${hits.hits.size} note${if (hits.hits.size == 1) "" else "s"} match '${args.query}' in ${hits.scope}" +
            (if (hits.complete) " · complete" else " · incomplete: the scope was not fully searched") +
            (if (shown.size < hits.hits.size) " · showing ${shown.size}" else "") +
            (hits.degradation?.let { " · degraded: $it" } ?: "")
        val lines = shown.map { "  ${it.id} [${it.kind}]${if (it.stale) " stale" else ""}: ${it.summary}" }
        return result("ok", (listOf(head) + lines).joinToString("\n"), hits.scope, complete = hits.complete, truncated = hits.truncated || shown.size < hits.hits.size)
    }

    private fun entry(args: KbArgs, what: String, lookup: (String) -> io.astrolabe.kb.KbEntry?): ToolOutcome {
        val id = args.id!!
        val found = lookup(id) ?: return result("not_found", "no $what '$id' in the knowledge base (complete: the base was searched)", "kb", complete = true)
        val label = if (found.stale) " · stale: anchors moved, not current" else ""
        return result("ok", "$what ${found.id} [${found.kind}]$label\n${found.text}", "kb", complete = true)
    }

    private fun propose(args: KbArgs): ToolOutcome {
        val queue = queue ?: return result("unsupported", "kb(propose): this cell has no admission queue", "kb", complete = true)
        val ids = ids ?: return result("unsupported", "kb(propose): this cell has no identities to file a candidate under", "kb", complete = true)
        val proposed = try {
            PROPOSAL_JSON.decodeFromJsonElement(ProposedNote.serializer(), args.note!!)
        } catch (malformed: IllegalArgumentException) {
            return result("refused", "kb(propose): malformed note: ${malformed.message}", "kb", complete = true)
        }
        if (proposed.kind == NoteKind.STATUS || proposed.kind == NoteKind.CAL) return result("refused", "kb(propose): ${proposed.kind} notes are harness-written (D-36)", "kb", complete = true)
        val note = try {
            Note(
                id = "${proposed.kind.name}-${idGen.next("note")}", kind = proposed.kind, status = NoteStatus.Candidate, summary = proposed.summary, body = proposed.body,
                scope = proposed.scope, anchors = proposed.anchors, confidence = proposed.confidence,
                basis = NoteBasis(proposed.requirementRefs, proposed.evidenceRefs), validity = NoteValidity(proposed.dependsOn),
                origin = NoteOrigin(work = ids.work.value, cell = ids.context?.value, extractor = "kb.propose"),
            )
        } catch (invalid: IllegalArgumentException) {
            return result("refused", "kb(propose): ${invalid.message}", "kb", complete = true)
        }
        val entry = try {
            queue.enqueue(note, ids)
        } catch (refused: NoteRefused) {
            return result("refused", "kb(propose): ${refused.message}", "kb", complete = true)
        }
        events?.emit(AgentEvent.Kb.Proposed(ids, note.id, note.kind.name))
        return result("queued", "queued ${note.id} [${note.kind}] as ${entry.id}: the curator admits, supersedes or rejects it; nothing is injected until then", "kb", complete = true)
    }

    private fun result(status: String, body: String, scope: String, complete: Boolean, truncated: Boolean = false): ToolOutcome {
        val header = EnvelopeHeader(
            resultAlias = "#-", tool = "kb", effectClass = EffectClass.R, versions = emptyMap(), stamp = null, truncated = truncated, effects = Effects.None,
            flags = InstructionShape.detect(body).flags,
            runtime = RuntimeFields(idGen.next("act"), status, null, null, scope, if (complete) "complete" else "incomplete", captureComplete = complete, displayTruncated = truncated),
        )
        return ToolOutcome(body, header, tokens = estimator.estimate(body).tokens)
    }

    private companion object {
        val PROPOSAL_JSON = Json { ignoreUnknownKeys = true }
    }
}
