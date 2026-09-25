package io.astrolabe.kb

import io.astrolabe.cell.CompletionDecision
import io.astrolabe.cell.RoleCompletion
import io.astrolabe.verify.Verifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The extractor's final no-call text parsed at the boundary (§12.1), or what keeps it from being a packet. */
public sealed interface CandidatesParsed {
    public data class Parsed(val candidates: List<Candidate>) : CandidatesParsed

    public data class Gaps(val gaps: List<String>) : CandidatesParsed
}

/**
 * The extractor role's NoteCandidates packet (§3.4 extractor row, §12.1, D-164): the model ends with
 * `{"candidates": [{kind, name, summary, scope, supersedes?, diagnosis: {symptom, conditions, attempted, observed, reason,
 * evidence: [...], invalidation}}]}`; the source revision is the trace's, never the model's. The model may propose
 * `LES`, `PIT`, `BMAP_DELTA` and `SKILL_DELTA`; a delta names the note it supersedes. `NEG` candidates carry a typed
 * state the harness derives from dead ends (P4.2.2) and a `CAL` delta is aggregated from statistics (D-42), so neither
 * is proposed here. The packet only proposes: [Extractor] enqueues and the curator admits.
 */
public object CandidatePacket {
    private val JSON = Json { ignoreUnknownKeys = true }

    /** The packet form the extractor is told to end with. */
    public const val OUTPUT: String = "end with the candidates as JSON: {\"candidates\": [{\"kind\": \"LES|PIT|BMAP_DELTA|SKILL_DELTA\", \"name\": \"one-token\", " +
        "\"summary\": …, \"scope\": …, \"supersedes\": \"<note id, deltas only>\", \"diagnosis\": {\"symptom\": …, \"conditions\": …, \"attempted\": …, " +
        "\"observed\": …, \"reason\": …, \"evidence\": [\"#id\" | \"receipt id\"], \"invalidation\": …}}]} — an empty list is valid"

    private val PROPOSABLE = setOf(CandidateKind.LES, CandidateKind.PIT, CandidateKind.BMAP_DELTA, CandidateKind.SKILL_DELTA)

    @JvmStatic
    public fun parse(text: String, sourceRevision: String): CandidatesParsed {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return CandidatesParsed.Gaps(listOf(OUTPUT))
        val root = try {
            JSON.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject
        } catch (malformed: IllegalArgumentException) {
            null
        } ?: return CandidatesParsed.Gaps(listOf("the packet is not a JSON object: $OUTPUT"))
        val list = root["candidates"] as? JsonArray ?: return CandidatesParsed.Gaps(listOf("no candidates array: $OUTPUT"))
        val gaps = ArrayList<String>()
        val candidates = ArrayList<Candidate>()
        list.forEachIndexed { i, element ->
            val fields = element as? JsonObject
            val at = "candidate ${i + 1}"
            val kind = fields?.text("kind")?.let { k -> CandidateKind.entries.firstOrNull { it.name == k } }
            when {
                fields == null || kind == null -> gaps += "$at: kind is one of ${PROPOSABLE.joinToString("|")}"
                kind !in PROPOSABLE -> gaps += "$at: ${kind.name} is derived by the harness, never proposed"
                else -> {
                    val d = fields["diagnosis"] as? JsonObject
                    val name = fields.text("name")
                    val summary = fields.text("summary")
                    val scope = fields.text("scope")
                    val supersedes = fields.text("supersedes")
                    val evidence = d?.texts("evidence").orEmpty()
                    when {
                        name == null || summary == null || scope == null -> gaps += "$at: needs name, summary and scope"
                        d == null || d.text("symptom") == null || evidence.isEmpty() -> gaps += "$at ($name): the diagnosis names its symptom and cites evidence"
                        kind in DELTAS && supersedes == null -> gaps += "$at ($name): a ${kind.name} names the note it supersedes"
                        else -> try {
                            candidates += Candidate(
                                kind, name, summary, scope,
                                Diagnosis(
                                    d.text("symptom")!!, d.text("conditions") ?: "", d.text("attempted") ?: "", d.text("observed") ?: "",
                                    d.text("reason") ?: "", evidence, sourceRevision, d.text("invalidation") ?: "",
                                ),
                                supersedes = supersedes,
                            )
                        } catch (bad: IllegalArgumentException) {
                            gaps += "$at ($name): ${bad.message}"
                        }
                    }
                }
            }
        }
        return if (gaps.isEmpty()) CandidatesParsed.Parsed(candidates) else CandidatesParsed.Gaps(gaps)
    }

    /**
     * The extractor role's [RoleCompletion] (§3.7 `validate_role_output` for the NoteCandidates packet): accepted once
     * the text parses into proposable candidates bound to [sourceRevision]; [onCandidates] receives them for the queue.
     * Gaps continue the cell, at most [maxFinalizations] times before an explicit incomplete exit.
     */
    @JvmStatic
    @JvmOverloads
    public fun completion(sourceRevision: String, onCandidates: (List<Candidate>) -> Unit, maxFinalizations: Int = Verifier().maxFinalizations): RoleCompletion {
        require(maxFinalizations >= 1) { "maxFinalizations must be ≥ 1" }
        return RoleCompletion { output, _ ->
            when (val parsed = parse(output.text, sourceRevision)) {
                is CandidatesParsed.Gaps ->
                    if (output.refusals + 1 >= maxFinalizations) CompletionDecision.CannotProgress(parsed.gaps) else CompletionDecision.Continue(parsed.gaps)
                is CandidatesParsed.Parsed -> {
                    onCandidates(parsed.candidates)
                    CompletionDecision.Accepted(parsed.candidates.flatMap { it.diagnosis.evidence }.distinct())
                }
            }
        }
    }

    private val DELTAS = setOf(CandidateKind.BMAP_DELTA, CandidateKind.SKILL_DELTA)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.texts(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.orEmpty()
}
