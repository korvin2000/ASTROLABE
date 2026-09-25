package io.astrolabe.delegate

import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CompletionDecision
import io.astrolabe.cell.PacketBase
import io.astrolabe.cell.RoleCompletion
import io.astrolabe.id.FileVersion
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.verify.Verifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** The §10.2 probe budget `[ESTIMATE]`: 15 turns and 40K tokens by default; a packet may reserve less, never more. */
public data class ProbeBudget @JvmOverloads constructor(val turns: Int = 15, val tokens: Tokens = Tokens(40_000)) {
    init {
        require(turns >= 1 && tokens.value > 0) { "a probe runs at least one turn on a positive budget" }
    }

    public companion object {
        @JvmField
        public val DEFAULT: ProbeBudget = ProbeBudget()
    }
}

/** The probe's final no-call text parsed at the boundary (§10.2 packet out), or what keeps it from being a packet. */
public sealed interface ProbeOutput {
    public data class Parsed(val findings: List<Finding>, val searched: Searched, val unresolved: List<String>) : ProbeOutput

    public data class Gaps(val gaps: List<String>) : ProbeOutput
}

/** How a finding's pointer stands against the tree now (FX-41): a stale pointer must be looked at again. */
public enum class PointerFreshness(public val wire: String) { Current("current"), Stale("stale"), Unknown("unknown") }

/**
 * The probe cell's packet rules (§10.2): the model ends with `{findings: [{claim, kind, evidence: ["path:lines" | "#id"]}],
 * searched: {scopes, complete, indexCoverage}, unresolved: []}` as its no-call text; the runtime binds every cited
 * range to the version the probe was actually shown (its packet's read versions) — a range on a path it never looked
 * at, or at another version, is a gap, never a finding. The parent receives a summary of at most
 * [MAX_SUMMARY_TOKENS] in `[T]`; findings are pointers it must `look` to make them KNOWN.
 */
public object Probe {
    public const val MAX_SUMMARY_TOKENS: Int = 400

    private val JSON = Json { ignoreUnknownKeys = true }
    private val LINES = Regex("""^\d+(-\d+)?$""")

    /** Parses [text] against [shown], the path → version map of what the probe was displayed. */
    @JvmStatic
    public fun parse(text: String, shown: Map<String, FileVersion>): ProbeOutput {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return ProbeOutput.Gaps(listOf(OUTPUT))
        val root = try {
            JSON.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject
        } catch (malformed: IllegalArgumentException) {
            null
        } ?: return ProbeOutput.Gaps(listOf("the packet is not a JSON object: $OUTPUT"))
        val gaps = ArrayList<String>()
        val findings = ArrayList<Finding>()
        (root["findings"] as? JsonArray ?: JsonArray(emptyList())).forEachIndexed { i, element ->
            val fields = element as? JsonObject
            val claim = fields?.text("claim")
            val kind = fields?.text("kind")?.let { k -> ClaimKind.entries.firstOrNull { it.wire == k } }
            if (claim == null || kind == null) {
                gaps += "finding ${i + 1}: needs a claim and kind observed|inferred"
                return@forEachIndexed
            }
            val refs = ArrayList<EvidenceRef>()
            for (pointer in fields.texts("evidence")) {
                when (val ref = pointer(pointer, shown)) {
                    is Pointer.Ok -> refs += ref.ref
                    is Pointer.Gap -> gaps += "finding ${i + 1} ('${claim.take(60)}'): ${ref.reason}"
                }
            }
            if (kind == ClaimKind.Observed && refs.isEmpty()) {
                gaps += "finding ${i + 1} ('${claim.take(60)}'): an observed claim points at evidence you were shown; mark it inferred otherwise"
                return@forEachIndexed
            }
            findings += Finding(claim, kind, refs)
        }
        val searchedFields = root["searched"] as? JsonObject
        val scopes = searchedFields?.texts("scopes").orEmpty()
        val complete = (searchedFields?.get("complete") as? JsonPrimitive)?.booleanOrNull
        val coverage = (searchedFields?.get("indexCoverage") as? JsonPrimitive)?.doubleOrNull
        if (searchedFields == null || scopes.isEmpty() || complete == null) gaps += "searched needs {scopes: [...], complete: true|false, indexCoverage: 0..1|null}"
        if (coverage != null && coverage !in 0.0..1.0) gaps += "searched.indexCoverage is a fraction"
        if (findings.isEmpty() && root.texts("unresolved").isEmpty() && gaps.isEmpty()) gaps += "no findings and nothing unresolved: report what you found or what stays open"
        if (gaps.isNotEmpty()) return ProbeOutput.Gaps(gaps)
        return ProbeOutput.Parsed(findings, Searched(scopes, complete!!, coverage), root.texts("unresolved"))
    }

    /**
     * The probe role's [RoleCompletion] (§3.7 `validate_role_output` for the Investigation packet): accepted once the
     * text parses into a packet whose ranges are bound to shown versions; [onPacket] receives it with the dispatch
     * [base]. Gaps continue the cell, at most [maxFinalizations] times before an explicit incomplete exit.
     */
    @JvmStatic
    @JvmOverloads
    public fun completion(base: PacketBase, onPacket: (InvestigationPacket) -> Unit, maxFinalizations: Int = Verifier().maxFinalizations): RoleCompletion {
        require(maxFinalizations >= 1) { "maxFinalizations must be ≥ 1" }
        return RoleCompletion { output, _ ->
            val packet = output.packet
            when (val parsed = parse(output.text, packet.readVersions)) {
                is ProbeOutput.Gaps ->
                    if (output.refusals + 1 >= maxFinalizations) CompletionDecision.CannotProgress(parsed.gaps) else CompletionDecision.Continue(parsed.gaps)
                is ProbeOutput.Parsed -> {
                    val investigation = InvestigationPacket(
                        packet.ids, packet.increment, packet.contractVersion, packet.executionGeneration, base, packet.readVersions,
                        parsed.findings, parsed.searched, parsed.unresolved, packet.cost,
                    )
                    onPacket(investigation)
                    CompletionDecision.Accepted(parsed.findings.flatMap { f -> f.evidence.map { it.wire } }.distinct())
                }
            }
        }
    }

    /** A range is current while its path is still at the cited version; an alias is a durable record. */
    @JvmStatic
    public fun freshness(ref: EvidenceRef, current: (String) -> FileVersion?): PointerFreshness = when (ref) {
        is EvidenceRef.Alias -> PointerFreshness.Current
        is EvidenceRef.Range -> when (current(ref.path)) {
            null -> PointerFreshness.Unknown
            ref.version -> PointerFreshness.Current
            else -> PointerFreshness.Stale
        }
    }

    /** Findings with at least one pointer that is no longer current (FX-41). */
    @JvmStatic
    public fun stale(packet: InvestigationPacket, current: (String) -> FileVersion?): List<Finding> =
        packet.findings.filter { f -> f.evidence.any { freshness(it, current) != PointerFreshness.Current } }

    /**
     * The parent's `[T]` view of [packet] (§10.2): coverage, then one line per finding with each pointer's freshness,
     * then what stays unresolved, cut to at most [maxTokens]. A stale pointer names its path to look at again.
     */
    @JvmStatic
    @JvmOverloads
    public fun summary(handle: String, packet: InvestigationPacket, current: (String) -> FileVersion?, estimator: TokenEstimator, maxTokens: Int = MAX_SUMMARY_TOKENS): String {
        val stale = stale(packet, current).size
        val searched = packet.searched
        val lines = ArrayList<String>()
        lines += "probe $handle: ${packet.findings.size} findings ($stale stale) · searched ${searched.scopes.joinToString(", ")} " +
            (if (searched.complete) "complete" else "incomplete") + (searched.indexCoverage?.let { " · index ${(it * 100).toInt()}%" } ?: "")
        packet.findings.forEachIndexed { i, f ->
            val pointers = f.evidence.joinToString(", ") { ref ->
                when (freshness(ref, current)) {
                    PointerFreshness.Current -> ref.wire
                    PointerFreshness.Stale -> "${ref.wire} STALE — ${(ref as EvidenceRef.Range).path} changed since; look it again"
                    PointerFreshness.Unknown -> "${ref.wire} (path gone or unknown)"
                }
            }
            lines += "  ${i + 1}. [${f.kind.wire}] ${f.claim}" + (if (pointers.isEmpty()) "" else " — $pointers")
        }
        if (packet.unresolved.isNotEmpty()) lines += "  unresolved: ${packet.unresolved.joinToString("; ")}"
        val tail = "pointers only: look a range to make it KNOWN before relying on it"
        val out = ArrayList<String>()
        for ((i, line) in lines.withIndex()) {
            // The cut marker and the tail always fit: a line enters only while both still do.
            val cut = "  … ${lines.size - i} more lines cut at $maxTokens tokens"
            if (estimator.estimate((out + line + cut + tail).joinToString("\n")).upperBoundTokens > maxTokens) {
                out += cut
                break
            }
            out += line
        }
        out += tail
        return out.joinToString("\n")
    }

    private sealed interface Pointer {
        data class Ok(val ref: EvidenceRef) : Pointer

        data class Gap(val reason: String) : Pointer
    }

    private fun pointer(text: String, shown: Map<String, FileVersion>): Pointer {
        if (text.startsWith("#")) return if (text.length > 1) Pointer.Ok(EvidenceRef.Alias(text)) else Pointer.Gap("'#' names no alias")
        val at = text.lastIndexOf('@').takeIf { it > 0 }
        val located = if (at == null) text else text.substring(0, at)
        val hash = at?.let { text.substring(it + 1) }
        val colon = located.lastIndexOf(':')
        if (colon <= 0 || !LINES.matches(located.substring(colon + 1))) return Pointer.Gap("'$text' is not path:lines[@hash] or #id")
        val path = located.substring(0, colon)
        val version = shown[path] ?: return Pointer.Gap("$path was never shown to you: look it before citing it")
        if (hash != null && !version.digest.hex.startsWith(hash)) return Pointer.Gap("$path@$hash is not the version you were shown (@${version.hash8})")
        return Pointer.Ok(EvidenceRef.Range(path, located.substring(colon + 1), version))
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.texts(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.orEmpty()

    /** The packet form the probe is told to end with (its brief's required output). */
    public const val OUTPUT: String = "end with the packet as JSON: {\"findings\": [{\"claim\": …, \"kind\": \"observed|inferred\", \"evidence\": [\"path:lines\" | \"#id\"]}], " +
        "\"searched\": {\"scopes\": […], \"complete\": true|false, \"indexCoverage\": 0..1}, \"unresolved\": […]}"
}
