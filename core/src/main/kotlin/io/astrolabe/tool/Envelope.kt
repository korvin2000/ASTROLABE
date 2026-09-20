package io.astrolabe.tool

import io.astrolabe.auth.Boundary
import io.astrolabe.id.CandidateId
import io.astrolabe.id.FileVersion
import kotlinx.serialization.Serializable

@Serializable
public enum class Effects { Observed, Unknown, None }

/**
 * Runtime-owned fields of every result (§5.4): tool status, hashes, stamps and capture limits are runtime
 * facts, never model-authored (invariant 4). Recorded in the journal in full; the header shows the compact set.
 */
@Serializable
public data class RuntimeFields(
    val actionId: String,
    val status: String,
    val candidateBefore: CandidateId?,
    val candidateAfter: CandidateId?,
    val scope: String?,
    val completeness: String,
    val artifactRefs: List<String> = emptyList(),
    val captureComplete: Boolean = true,
    val displayTruncated: Boolean = false,
    val redactionApplied: Boolean = false,
    val effectsObserved: List<String> = emptyList(),
    val effectsUnknown: Boolean = false,
    val retryClass: String? = null,
)

/** The header line of a result envelope. */
@Serializable
public data class EnvelopeHeader(
    /** Campaign-global alias `#57` (D-46). */
    val resultAlias: String,
    val tool: String,
    val effectClass: EffectClass?,
    val versions: Map<String, FileVersion>,
    val stamp: CandidateId?,
    val truncated: Boolean,
    val effects: Effects,
    /** Model-facing flags such as `⚠ instruction-shaped content` (never filtered, never executed). */
    val flags: List<String> = emptyList(),
    val runtime: RuntimeFields,
) {
    public fun line(): String {
        val sb = StringBuilder(Boundary.RESULT_OPEN).append("result ").append(resultAlias).append(" tool=").append(tool)
        effectClass?.let { sb.append(" class=").append(it.name) }
        if (versions.isNotEmpty()) {
            sb.append(" v={").append(versions.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}: ${it.value.hash8.take(4)}" }).append('}')
        }
        stamp?.let { sb.append(" stamp=").append(it.hash8.take(4)) }
        sb.append(" truncated=").append(if (truncated) "yes" else "no")
        sb.append(" effects=").append(effects.name.lowercase())
        if (runtime.status.isNotEmpty()) sb.append(" status=").append(runtime.status)
        flags.forEach { sb.append(' ').append(it) }
        return sb.append(Boundary.RESULT_CLOSE).toString()
    }
}

/** The ~20-token gauge appended to every result and after the turn (§5.7). */
@Serializable
public data class Gauge(
    val contextPercent: Int,
    val reserveOk: Boolean,
    /** `@c02e: types ✓ · tests stale` — the checks summary at the current stamp. */
    val checks: String,
    val knownFiles: Int,
    val knownTokens: Long,
    val stateVersion: Int,
    val turn: Int,
    val turnsMax: Int,
) {
    public fun line(): String = Boundary.GAUGE_OPEN +
        "ctx $contextPercent% · reserve ${if (reserveOk) "ok" else "reached"} · checks $checks · known $knownFiles/${tokens(knownTokens)} · STATE v$stateVersion · turn $turn/$turnsMax" +
        Boundary.GAUGE_CLOSE

    private fun tokens(n: Long): String = if (n >= 1_000) String.format(java.util.Locale.ROOT, "%.1fK", n / 1_000.0) else n.toString()
}

/** Result envelope renderer (§5.4): delimiters are harness-owned; payload bytes that look like them are escaped. */
public object Envelope {
    @JvmStatic
    public fun render(header: EnvelopeHeader, body: String, gauge: Gauge): String {
        val sb = StringBuilder(header.line()).append('\n')
        val escaped = Boundary.escape(body)
        if (escaped.isNotEmpty()) {
            escaped.lineSequence().forEach { sb.append("  ").append(it).append('\n') }
        }
        sb.append(Boundary.RESULT_OPEN).append("/result").append(Boundary.RESULT_CLOSE).append('\n')
        sb.append(gauge.line())
        return sb.toString()
    }
}
