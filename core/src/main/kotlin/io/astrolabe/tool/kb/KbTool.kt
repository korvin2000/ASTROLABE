package io.astrolabe.tool.kb

import io.astrolabe.auth.InstructionShape
import io.astrolabe.id.IdGen
import io.astrolabe.kb.Kb
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

/**
 * The `kb` family (§5.4, TODO P1.6.10): `search`, `get` and `skill` read the [Kb]; `propose` is masked until
 * P4.1. The envelope carries the knowledge base's own completeness: on the S0 empty base every search is
 * complete and empty — never "absent" — and a stale hit is labelled, never served as current.
 */
public class KbTool(
    private val kb: Kb,
    private val estimator: TokenEstimator,
    private val idGen: IdGen,
    private val mask: ToolMask = ToolOps.implementingS0,
    private val maxHits: Int = 20,
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

    private fun result(status: String, body: String, scope: String, complete: Boolean, truncated: Boolean = false): ToolOutcome {
        val header = EnvelopeHeader(
            resultAlias = "#-", tool = "kb", effectClass = EffectClass.R, versions = emptyMap(), stamp = null, truncated = truncated, effects = Effects.None,
            flags = InstructionShape.detect(body).flags,
            runtime = RuntimeFields(idGen.next("act"), status, null, null, scope, if (complete) "complete" else "incomplete", captureComplete = complete, displayTruncated = truncated),
        )
        return ToolOutcome(body, header, tokens = estimator.estimate(body).tokens)
    }
}
