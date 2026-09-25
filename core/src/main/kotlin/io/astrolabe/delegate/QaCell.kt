package io.astrolabe.delegate

import io.astrolabe.auth.ExecutionMode
import io.astrolabe.cell.CompletionDecision
import io.astrolabe.cell.RoleCompletion
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Identities
import io.astrolabe.tool.run.ConfinedRunner
import io.astrolabe.tool.run.Runner
import io.astrolabe.verify.Verifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URISyntaxException

/** How the QA cell drives the product (§10.3). */
public enum class QaSurface(public val wire: String) { Cli("cli"), Http("http"), Browser("browser") }

/** One entry point the QA cell exercises: a command, an endpoint or a page of the product under test. */
public data class EntryPoint(val surface: QaSurface, val target: String) {
    init {
        require(target.isNotBlank()) { "an entry point names its target" }
    }
}

/**
 * The disposable environment an L3 run needs (§10.3, §8.2 L3): never production, never the live workspace. Either a
 * confined backend, or an isolated candidate copy whose run is explicitly labelled `trusted-local` (D-125) — the
 * label is how a report says the copy is not confined.
 */
public sealed interface QaEnvironment {
    public val label: String

    public data class Confined(val backend: String) : QaEnvironment {
        init {
            require(backend.isNotBlank()) { "a confined environment names its backend" }
        }

        override val label: String get() = "confined:$backend"
    }

    public data class IsolatedCandidate(val candidate: CandidateId, val root: String, val mode: ExecutionMode) : QaEnvironment {
        init {
            require(root.isNotBlank()) { "an isolated candidate has a root" }
        }

        override val label: String get() = (if (mode == ExecutionMode.TrustedLocal) "trusted-local" else "confined") + ":candidate@${candidate.hash8}"
    }

    public companion object {
        /** A confined runner is disposable by construction; a trusted-local one only on an isolated candidate copy. */
        @JvmStatic
        public fun of(runner: Runner, candidate: CandidateId?, root: String?): QaEnvironment? = when {
            runner is ConfinedRunner -> Confined(runner.backend)
            candidate != null && !root.isNullOrBlank() -> IsolatedCandidate(candidate, root, runner.mode)
            else -> null
        }
    }
}

/** The QA packet in (§10.3): the contract slice, the behaviour under test and its entry points, and where to run. */
public data class QaPacket(
    val ids: Identities,
    val incrementId: String,
    val contractVersion: Int,
    val candidate: CandidateId,
    val criteria: List<ReviewCriterion>,
    val behaviour: String,
    val entryPoints: List<EntryPoint>,
    val environment: QaEnvironment,
) {
    init {
        require(incrementId.isNotBlank() && behaviour.isNotBlank()) { "a QA packet names its increment and the behaviour under test" }
        require(contractVersion >= 1) { "contract version starts at 1" }
        require(entryPoints.isNotEmpty()) { "a QA packet names at least one entry point" }
        require(criteria.isNotEmpty()) { "a QA packet carries the acceptance it exercises" }
    }
}

/** One case the QA cell ran: steps against an entry point, expected and observed, with its screenshots/logs as blobs. */
public data class QaCase(
    val id: String,
    val entryPoint: EntryPoint,
    val steps: List<String>,
    val expected: String,
    val observed: String,
    /** `null` when the case could not be decided (an environment failure, not a product verdict). */
    val passed: Boolean?,
    val artifacts: List<String>,
) {
    init {
        require(id.isNotBlank() && expected.isNotBlank()) { "a case has an id and an expectation" }
    }
}

/** The QA packet out (§10.3): receipts, cases and artifacts, bound to the environment and candidate it ran on. */
public data class QaResult(
    val ids: Identities,
    val incrementId: String,
    val contractVersion: Int,
    val candidate: CandidateId,
    val environment: QaEnvironment,
    val receipts: List<String>,
    val cases: List<QaCase>,
    val unresolved: List<String>,
)

/** The QA cell's final no-call text parsed at the boundary, or what keeps it from being a packet. */
public sealed interface QaParsed {
    public data class Parsed(val result: QaResult) : QaParsed

    public data class Gaps(val gaps: List<String>) : QaParsed
}

/** Whether a QA run may start. */
public sealed interface QaAdmission {
    public data class Ready(val packet: QaPacket) : QaAdmission

    public data class Refused(val reason: String) : QaAdmission
}

/**
 * The QA cell's contract (§10.3, L3 of §8.2): packet in, packet out, never deciding interfaces. [QaDriver] runs it
 * (P5.3.1); a host enables it with `Flags.qaCell` and passes the flag as [admit]'s `available`. Browser surfaces are
 * out of scope and an HTTP entry point must be a loopback address of the disposable environment (D-200).
 */
public object QaCell {
    /** Implemented since P5.3.1; still an optional layer behind `Flags.qaCell`. */
    public const val AVAILABLE: Boolean = true

    @JvmStatic
    @JvmOverloads
    public fun admit(packet: QaPacket, available: Boolean = AVAILABLE): QaAdmission {
        if (!available) return QaAdmission.Refused("QA cells are off: Flags.qaCell enables L3 product use")
        packet.entryPoints.firstOrNull { it.surface == QaSurface.Browser }?.let {
            return QaAdmission.Refused("browser entry point ${it.target}: browser surfaces are out of scope (D-200)")
        }
        packet.entryPoints.firstOrNull { it.surface == QaSurface.Http && !loopback(it.target) }?.let {
            return QaAdmission.Refused("HTTP entry point ${it.target} is not a loopback address: QA never drives production (D-200)")
        }
        val env = packet.environment
        if (env is QaEnvironment.IsolatedCandidate && env.candidate != packet.candidate) {
            return QaAdmission.Refused("the isolated copy is @${env.candidate.hash8}, not the candidate under test @${packet.candidate.hash8}")
        }
        return QaAdmission.Ready(packet)
    }

    /**
     * Gaps of [result] against [packet] (§10.3): same candidate, contract version and environment; every case on one
     * of the packet's entry points; every decided case carries at least one artifact that exists; a run with cases
     * has receipts. Empty ⇔ the packet may be published.
     */
    @JvmStatic
    public fun validate(result: QaResult, packet: QaPacket, artifactExists: (String) -> Boolean): List<String> {
        val gaps = ArrayList<String>()
        if (result.candidate != packet.candidate) gaps += "ran @${result.candidate.hash8}, the packet names @${packet.candidate.hash8}"
        if (result.contractVersion != packet.contractVersion) gaps += "bound to contract v${result.contractVersion}, the packet to v${packet.contractVersion}"
        if (result.environment != packet.environment) gaps += "ran in ${result.environment.label}, the packet requires ${packet.environment.label}"
        if (result.cases.isEmpty() && result.unresolved.isEmpty()) gaps += "no cases and nothing unresolved"
        if (result.cases.isNotEmpty() && result.receipts.isEmpty()) gaps += "cases ran without receipts"
        if (result.cases.map { it.id }.toSet().size != result.cases.size) gaps += "case ids are unique"
        for (case in result.cases) {
            if (case.entryPoint !in packet.entryPoints) gaps += "case ${case.id}: ${case.entryPoint.surface.wire} ${case.entryPoint.target} is not an entry point of the packet"
            if (case.passed != null && case.artifacts.isEmpty()) gaps += "case ${case.id}: a decided case carries its screenshot or log"
            case.artifacts.filterNot(artifactExists).forEach { gaps += "case ${case.id}: artifact $it is not a published blob" }
        }
        return gaps
    }

    /** Whether an HTTP [target] (`[METHOD ]url`) addresses this host's loopback interface; anything else may be production. */
    @JvmStatic
    public fun loopback(target: String): Boolean {
        val host = try {
            URI(target.trim().substringAfterLast(' ')).host
        } catch (malformed: URISyntaxException) {
            null
        } ?: return false
        return host == "localhost" || host == "[::1]" || LOOPBACK_V4.matches(host)
    }

    private val LOOPBACK_V4 = Regex("""127\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

    /** The packet form the QA cell is told to end with. */
    public const val OUTPUT: String = "end with the QA packet as JSON: {\"cases\": [{\"id\": …, \"entryPoint\": {\"surface\": \"cli|http|browser\", \"target\": …}, " +
        "\"steps\": […], \"expected\": …, \"observed\": …, \"passed\": true|false|null, \"artifacts\": [\"blob digest\"]}], \"unresolved\": […]}"

    /**
     * The QA cell's no-call text parsed at the boundary (D-164): cases and what stays unresolved come from the model;
     * the candidate, contract version and environment come from [packet] and the receipts from the cell's own run
     * records ([receipts]), never from the model's words.
     */
    @JvmStatic
    public fun parse(text: String, packet: QaPacket, receipts: List<String>): QaParsed {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return QaParsed.Gaps(listOf(OUTPUT))
        val root = try {
            JSON.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject
        } catch (malformed: IllegalArgumentException) {
            null
        } ?: return QaParsed.Gaps(listOf("the packet is not a JSON object: $OUTPUT"))
        val gaps = ArrayList<String>()
        val cases = ArrayList<QaCase>()
        (root["cases"] as? JsonArray ?: JsonArray(emptyList())).forEachIndexed { i, element ->
            val fields = element as? JsonObject
            val entry = fields?.get("entryPoint") as? JsonObject
            val surface = entry?.text("surface")?.let { s -> QaSurface.entries.firstOrNull { it.wire == s } }
            val target = entry?.text("target")
            val id = fields?.text("id")
            val expected = fields?.text("expected")
            if (fields == null || id == null || expected == null || surface == null || target == null) {
                gaps += "case ${i + 1}: needs id, entryPoint {surface, target} and expected"
                return@forEachIndexed
            }
            val passed = (fields["passed"] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.booleanOrNull }
            cases += QaCase(id, EntryPoint(surface, target), fields.texts("steps"), expected, fields.text("observed") ?: "", passed, fields.texts("artifacts"))
        }
        if (gaps.isNotEmpty()) return QaParsed.Gaps(gaps)
        return QaParsed.Parsed(QaResult(packet.ids, packet.incrementId, packet.contractVersion, packet.candidate, packet.environment, receipts.toList(), cases, root.texts("unresolved")))
    }

    /**
     * The QA role's [RoleCompletion] (§3.7 `validate_role_output` for the ReceiptsAndCases packet): the text parses and
     * [validate] finds no gap against [packet]; [onResult] receives the result. Gaps continue the cell, at most
     * [maxFinalizations] times. A model-driven QA cell binds it; [QaDriver] applies [validate] to its own run.
     */
    @JvmStatic
    @JvmOverloads
    public fun completion(packet: QaPacket, artifactExists: (String) -> Boolean, onResult: (QaResult) -> Unit, maxFinalizations: Int = Verifier().maxFinalizations): RoleCompletion {
        require(maxFinalizations >= 1) { "maxFinalizations must be ≥ 1" }
        return RoleCompletion { output, _ ->
            val parsed = parse(output.text, packet, output.packet.receipts)
            val result = (parsed as? QaParsed.Parsed)?.result
            val gaps = if (result == null) (parsed as QaParsed.Gaps).gaps else validate(result, packet, artifactExists)
            when {
                gaps.isEmpty() && result != null -> {
                    onResult(result)
                    CompletionDecision.Accepted(result.receipts)
                }
                output.refusals + 1 >= maxFinalizations -> CompletionDecision.CannotProgress(gaps)
                else -> CompletionDecision.Continue(gaps)
            }
        }
    }

    private val JSON = Json { ignoreUnknownKeys = true }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.texts(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.orEmpty()
}
