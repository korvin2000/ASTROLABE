package io.astrolabe.campaign

import io.astrolabe.evidence.Intent
import io.astrolabe.evidence.Observation
import io.astrolabe.workspace.Preimage
import kotlinx.serialization.json.Json

/**
 * Task-workflow §3.5 (D-344, WD-22): whether a task ends with an answer. Execution authority — effect classes, replay
 * classes, `Intent.replaySafe` — keeps its meaning and its enforcement; what an answer reads is the **observed durable
 * effect** derived from the record: an applied edit or transform, a D-class execution, or an execution whose effects the
 * tree cannot observe. A W-class test or build confined to the workspace whose observed effect is only output outside the
 * candidate (the attempt's frozen output policy, D-429) is no durable effect; one that moved the candidate is caught by
 * the candidate itself, which must be snapshot 0's. The controller and the task tool's `answerCheck` read this one predicate.
 */
internal object Answers {
    /** Why [c] may not end with an answer now, or `null` when it may: no durable effect, and the candidate is at s0. */
    fun refusal(c: OpenedCampaign): String? {
        val stamp = c.stamper.report(fresh = true).candidateId
        if (stamp != c.s0.stampId) return "the tree changed since the task started (@${c.s0.stampId.hash8} → @${stamp.hash8})"
        return durableEffect(c)
    }

    /** The first durable effect the record shows, or `null`. */
    fun durableEffect(c: OpenedCampaign): String? {
        val edited = c.store.db.query("SELECT body FROM packets WHERE work_id = ? AND kind LIKE ?", c.ids.work, "preimage:%") {
            Json.decodeFromString(Preimage.serializer(), it.string("body"))
        }.firstOrNull { it.versionAfter != null }
        if (edited != null) return "an edit was applied to ${edited.path}"
        val executions = executions(c)
        for (intent in executions) {
            if (intent.expectedEffect.startsWith("D ")) return "an action with effects ran: ${intent.argv.joinToString(" ")} (D-class)"
            // D-321: effects a stamp of the tree cannot observe (network, install, outside the workspace, a background process);
            // a script interpreter's are observed by the stamp (§9.4), so the candidate at s0 shows it left none.
            if (!intent.workspaceConfined && !intent.stampObserved) return "an action with effects ran: ${intent.argv.joinToString(" ")} (effects outside what the tree shows)"
        }
        // WR2 (P1 #11, T-44): sticky — an execution after which the recorded candidate differed from s0 is a durable effect,
        // though a later one restored the bytes (§3.5: "any execution after which the candidate differs from s0").
        val argv = executions.associate { it.actionId to it.argv }
        val moved = c.store.db.query("SELECT body FROM observations WHERE work_id = ? AND attempt_id = ? ORDER BY rowid", c.ids.work, c.ids.attempt) {
            Json.decodeFromString(Observation.serializer(), it.string("body"))
        }.firstOrNull { it.actionId in argv && it.candidate != null && it.candidate != c.s0.stampId }
        if (moved != null) return "an action moved the tree: ${argv.getValue(moved.actionId).joinToString(" ")} (@${c.s0.stampId.hash8} → @${moved.candidate?.hash8})"
        return null
    }

    /** The executions the answer rests on (its evidence): every recorded action that is not a replay-safe read. */
    fun runs(c: OpenedCampaign): List<String> = executions(c).map { it.argv.joinToString(" ") }

    private fun executions(c: OpenedCampaign): List<Intent> = c.store.db.query("SELECT body FROM intents WHERE work_id = ?", c.ids.work) {
        Json.decodeFromString(Intent.serializer(), it.string("body"))
    }.filterNot { it.replaySafe }
}
