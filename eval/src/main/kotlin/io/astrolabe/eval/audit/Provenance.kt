package io.astrolabe.eval.audit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Who set the requirements, which checks ran and whose they were, and how acceptance ended (§9.1). An acceptance
 * decision is asked only when obligations stay unverified (D-339), so an accepting decision is [acceptUnverified].
 * [provenanceClass] is the class the last `campaign.finished` carried (§4.4 C2): `independent`, `agent_test` or
 * `unverified`; `null` for a log written before it. Every other field of `campaign.finished` and of eval-live's
 * `result.json` read nowhere here lands in [extra], so a new field is reported from the run it first appears in.
 */
@Serializable
public data class Provenance @JvmOverloads constructor(
    val requirements: List<String>,
    val verification: String?,
    val checks: List<String>,
    val outcome: String?,
    val acceptance: String?,
    val acceptUnverified: Boolean?,
    val acceptanceSurface: List<String>,
    val testEdits: List<String>,
    val external: String?,
    val extra: Map<String, JsonElement>,
    val provenanceClass: String? = null,
) {
    public companion object {
        private val FINISH_READ = setOf("type", "ids", "phase", "span", "parent", "outcome", "finishReceiptRef", "stopCode", "provenanceClass")
        private val RESULT_READ = setOf(
            "schema", "task", "taskClass", "provider", "model", "repeat", "order", "seed", "harnessVersion", "effort", "maxCells", "profileId",
            "contextLimitTokens", "outputHeadroomTokens", "workId", "attemptFingerprint", "shape", "verification", "outcome", "stopCode",
            "reason", "failure", "cells", "policyDecisions", "acceptance", "acceptanceDigest", "startedAt", "endedAt", "attemptWallMillis",
            "totals", "eventsDropped", "changedFiles",
        )
        private val DECISION = Regex("""^acceptance decision \S+: (.*?)(: not verified.*)?$""")
        private val TEST_PATH = Regex("""(^|/)(tests?|__tests__|spec)/|(^|/)test_[^/]*$|_test\.[^/]+$|\.(test|spec)\.[^/]+$|Tests?\.[^/]+$""")

        @JvmStatic
        public fun of(journal: Journal, trace: RunTrace, result: JsonObject?): Provenance {
            val requirements = ArrayList<String>()
            result?.str("task")?.let { requirements += "task $it" + (result.str("taskClass")?.let { c -> " ($c)" } ?: "") }
            var verification = result?.obj("verification")?.let { v ->
                val commands = v.array("commands").orEmpty().map { c -> (c as? JsonArray)?.joinToString(" ") { (it as? JsonPrimitive)?.content ?: "" } ?: "" }
                "${v.str("kind")} (${v.str("source")})" + if (commands.isEmpty()) "" else ": " + commands.joinToString("; ")
            }
            var outcome: String? = null
            var acceptance: String? = null
            var accepted: Boolean? = null
            var provenanceClass: String? = null
            val extra = LinkedHashMap<String, JsonElement>()
            val checks = LinkedHashMap<String, MutableMap<String, Int>>()
            for (e in journal.events) {
                when (e.kind) {
                    "studio.user_message" -> requirements += "user ${e.data.str("role") ?: "message"}"
                    "contract.amended" -> requirements += "amended v${e.data.int("version")} by ${e.data.str("by")}"
                    "studio.verification" -> verification = "${e.data.str("kind")} (${e.data.str("source")})"
                    "check.finished" -> checks.getOrPut("check ${e.data.str("checkId")} (harness)") { sortedMapOf() }
                        .merge(e.data.str("outcome") ?: "?", 1, Int::plus)
                    "campaign.finished" -> {
                        outcome = (e.data.str("outcome") ?: "?") + (e.data.str("stopCode")?.let { " · $it" } ?: "")
                        if (outcome?.startsWith("completed") == true && accepted == null) accepted = false
                        provenanceClass = e.data.str("provenanceClass")
                        e.data.filterKeys { it !in FINISH_READ }.forEach { (k, v) -> extra["campaign.finished.$k"] = v }
                    }
                    "studio.run_ended" -> e.data.str("reason")?.takeIf { it != e.data.str("outcome") }?.let { outcome = "$outcome · $it" }
                    "journal.boundary" -> DECISION.find(e.data.str("text") ?: "")?.let { m ->
                        acceptance = m.groupValues[1] + if (m.groupValues[2].isNotEmpty()) " (not verified)" else ""
                        if (m.groupValues[1].startsWith("accept")) accepted = true
                    }
                }
            }
            result?.let { r ->
                r.str("outcome")?.let { o -> outcome = o + (r.str("stopCode")?.let { " · $it" } ?: "") + (r.str("failure")?.let { " · $it" } ?: "") }
                val decisions = r.array("policyDecisions").orEmpty().mapNotNull { it as? JsonObject }
                if (decisions.isNotEmpty()) acceptance = decisions.joinToString("; ") { "${it.str("kind")} ${it.str("outcome")}" }
                accepted = when {
                    decisions.any { it.str("kind") == "acceptance" && it.str("outcome") == "accepted" } -> true
                    r.str("outcome") == "completed" -> false
                    else -> accepted
                }
                r.filterKeys { it !in RESULT_READ }.forEach { (k, v) -> extra["result.$k"] = v }
            }
            for (a in trace.activity) for (o in a.outcomes) {
                val status = o.status ?: continue
                val key = when {
                    o.tool == "verify" -> "verify (declared checks)"
                    else -> trace.verdict(o)?.first?.let { "$it (model)" } ?: continue
                }
                checks.getOrPut(key) { sortedMapOf() }.merge(status, 1, Int::plus)
            }
            val external = result?.obj("acceptance")?.let { a ->
                when {
                    a.bool("timedOut") == true -> "timed out"
                    a.bool("passed") == true -> "passed (exit ${a.int("exitCode")})"
                    else -> "failed (exit ${a.int("exitCode")})"
                }
            }
            val edited = trace.activity.flatMap { a -> a.outcomes.filter { it.tool == "edit" && it.status in setOf("ok", "partial") }.flatMap { it.paths } }
            return Provenance(
                requirements = requirements,
                verification = verification,
                checks = checks.map { (k, v) -> "$k: " + v.entries.joinToString(", ") { "${it.key}×${it.value}" } },
                outcome = outcome,
                acceptance = acceptance,
                acceptUnverified = accepted,
                acceptanceSurface = trace.gateTexts.filter { it.first == "acceptance-surface" }.map { it.second },
                testEdits = edited.filter { TEST_PATH.containsMatchIn(it) }.distinct(),
                external = external,
                extra = extra,
                provenanceClass = provenanceClass,
            )
        }
    }
}
