package io.astrolabe.auth

import io.astrolabe.ConfigViolation
import io.astrolabe.evidence.RedactionMask
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.Ranges
import kotlinx.serialization.Serializable

/** One labelled secret pattern. [kind] names the finding in `[REDACTED:<kind>]` and in the per-observation log. */
@Serializable
public data class RedactionPattern(val kind: String, val regex: String) {
    init {
        require(kind.isNotBlank()) { "a redaction pattern needs a kind" }
        // The replacement marker must not be re-matchable, or `apply` would not be idempotent.
        require(kind.none { it == ':' || it == ']' || it == '=' }) { "a kind must not contain ':', '=' or ']': '$kind'" }
        require(regex.isNotBlank()) { "a redaction pattern needs a regex" }
    }
}

/**
 * Redaction policy (D-14): a configurable regex set, an env allowlist and a scan cap. Heuristic and labelled —
 * the limitations of a capture are recorded per observation rather than claimed away.
 */
@Serializable
public data class RedactionConfig(
    val patterns: List<RedactionPattern> = DEFAULT_PATTERNS,
    /**
     * Environment variable names the runner may pass through to a child process; everything else is dropped.
     * Consumed by the runner as `io.astrolabe.os.EnvPolicy.inheritedNames` (P1.6.5).
     */
    val envAllowlist: Set<String> = DEFAULT_ENV_ALLOWLIST,
    /** Bytes scanned per capture; beyond it the tail is **not** returned and the limit is recorded. */
    val maxBytes: Int = 262_144,
) {
    /** Patterns that do not compile, and bounds that would make redaction meaningless (D-48 style). */
    public fun violations(): List<ConfigViolation> = buildList {
        if (maxBytes <= 0) add(ConfigViolation("redaction.maxBytes", "must be positive, got $maxBytes"))
        patterns.forEachIndexed { index, pattern ->
            runCatching { Regex(pattern.regex) }.onFailure {
                add(ConfigViolation("redaction.patterns[$index] (${pattern.kind})", "does not compile: ${it.message}"))
            }
        }
        patterns.groupBy { it.kind }.filterValues { it.size > 1 }.keys.forEach {
            add(ConfigViolation("redaction.patterns", "duplicate kind '$it'"))
        }
    }

    public companion object {
        /** The declared default set (D-14); a host replaces or extends it, never silently narrows it. */
        @JvmField
        public val DEFAULT_PATTERNS: List<RedactionPattern> = listOf(
            RedactionPattern("private-key-block", "-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----[\\s\\S]*?-----END (?:[A-Z]+ )?PRIVATE KEY-----"),
            RedactionPattern("aws-access-key-id", "AKIA[0-9A-Z]{16}"),
            RedactionPattern("github-token", "gh[pousr]_[A-Za-z0-9]{36}"),
            RedactionPattern("openai-key", "sk-[A-Za-z0-9_-]{20,}"),
            RedactionPattern("slack-token", "xox[baprs]-[A-Za-z0-9-]{10,}"),
            RedactionPattern("jwt", "eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            RedactionPattern("bearer-token", "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
            RedactionPattern("url-credentials", "\\b[A-Za-z][A-Za-z0-9+.-]*://[^/\\s:@]+:[^/\\s:@]+@"),
            // An optional prefix catches `AWS_SECRET_ACCESS_KEY=…`, where `_` leaves no word boundary.
            RedactionPattern(
                "secret-assignment",
                "(?i)(?:[A-Za-z0-9_.-]{0,32}[_.-])?(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|client[_-]?secret|private[_-]?key)\\s*[:=]\\s*(?:\"[^\"\\n]{3,}\"|'[^'\\n]{3,}'|[^\\s\"',;]{3,})",
            ),
        )

        /** Names a child process may inherit; nothing here carries a credential. */
        @JvmField
        public val DEFAULT_ENV_ALLOWLIST: Set<String> = setOf(
            "PATH", "HOME", "USERPROFILE", "TMPDIR", "TEMP", "TMP", "LANG", "LC_ALL", "TZ",
            "SystemRoot", "SystemDrive", "COMSPEC", "PATHEXT", "WINDIR", "NUMBER_OF_PROCESSORS",
            "PROCESSOR_ARCHITECTURE", "SHELL", "TERM", "JAVA_HOME",
        )
    }
}

/** One redacted span, for the `capture.redacted` accounting of an observation. */
@Serializable
public data class RedactionHit(val kind: String, val line: Int)

/**
 * The result of a redaction pass. [text] is the only form that may reach the model, a note, a recall or an
 * export; [mask] travels with the [io.astrolabe.evidence.Observation] so redacted lines grant no coverage (D-49).
 */
@Serializable
public data class Redacted(val text: String, val mask: RedactionMask, val hits: List<RedactionHit> = emptyList()) {
    /** `capture.redacted` for the result envelope (§5.4 runtime fields). */
    val applied: Boolean get() = mask.applied

    val limitations: List<String> get() = mask.limitations
}

/**
 * What a capture is, for the rewrite guard. Redaction rewrites what the model and reusable evidence see; it
 * never rewrites the two stores that must stay byte-exact.
 */
@Serializable
public enum class ContentClass {
    /** Tool results, views, notes, packets — redacted before the model sees them. */
    ModelFacing,

    /** Receipts, observations and raw captures kept for reuse — redacted before persistence. */
    ReusableEvidence,

    /** Signed opaque provider items under protected `native/` (D-25): replayed unchanged or not at all. */
    NativeReplay,

    /** Exact rollback preimages in protected recovery storage (D-14): access-restricted, never rewritten. */
    RecoveryPreimage,
}

/**
 * Secret redaction before model exposure and before reusable evidence persistence (§14.3, D-14).
 *
 * The pass is heuristic and labelled: what it replaced is recorded as [RedactionHit]s, what it could not scan
 * is recorded as a limitation on the mask, and the unscanned tail is never returned. Redacted lines are marked
 * hidden in the [RedactionMask], and [io.astrolabe.evidence.Observation.coverage] subtracts them, so a redacted
 * line inside a multi-line anchor grants no coverage while the surrounding exact lines stay usable (D-49).
 *
 * Two stores are exempt by contract and refused by [neverRewrite]:
 * * signed opaque provider items in protected `native/` — rewriting them would produce an invalid replay
 *   artifact, so they are stored as captured and replayed only on a compatible lineage (D-25);
 * * exact rollback preimages — access-restricted local recovery artifacts, never silently redacted into
 *   unusable inverse patches and never exposed to the model or the KB (§14.3, D-14). A `revert` therefore
 *   restores the original bytes exactly, redaction notwithstanding.
 */
public class Redaction @JvmOverloads constructor(public val config: RedactionConfig = RedactionConfig()) {
    private val compiled: List<Pair<RedactionPattern, Regex>>

    init {
        val violations = config.violations()
        require(violations.isEmpty()) { "invalid redaction config: ${violations.joinToString("; ")}" }
        compiled = config.patterns.map { it to Regex(it.regex) }
    }

    /** Redacts model-facing text. */
    public fun apply(text: String): Redacted = apply(text, ContentClass.ModelFacing)

    /** Redacts [text] for [content]; refuses a content class that must stay byte-exact. */
    public fun apply(text: String, content: ContentClass): Redacted {
        val refusal = refuse(content)
        require(refusal == null) { refusal.toString() }
        if (text.isEmpty()) return Redacted(text, RedactionMask.NONE)

        val limitations = ArrayList<String>()
        val (capped, wasCapped) = capToBytes(text, config.maxBytes)
        var scanned = capped
        if (wasCapped) {
            limitations += "not scanned beyond ${config.maxBytes} bytes; the tail is not rendered"
            val lastBreak = capped.lastIndexOf('\n')
            if (lastBreak >= 0) {
                scanned = capped.substring(0, lastBreak + 1)
            } else {
                limitations += "the byte cap fell inside a line; a value split by the cap may be partially rendered"
            }
        }

        val found = ArrayList<Found>()
        compiled.forEachIndexed { index, (pattern, regex) ->
            regex.findAll(scanned).forEach { match ->
                if (!match.range.isEmpty()) found += Found(match.range.first, match.range.last + 1, pattern.kind, index)
            }
        }
        if (wasCapped) unterminatedBlock(scanned)?.let {
            found += it
            limitations += "the byte cap cut a private-key block; the rest of the capture is hidden"
        }

        found.sortWith(compareBy({ it.start }, { it.priority }, { -it.end }))
        val lineStarts = lineStarts(scanned)
        val out = StringBuilder(scanned.length)
        val hidden = ArrayList<LineRange>()
        val hits = ArrayList<RedactionHit>()
        var cursor = 0
        for (match in found) {
            if (match.start < cursor) continue
            out.append(scanned, cursor, match.start).append("[REDACTED:").append(match.kind).append(']')
            val from = lineOf(lineStarts, match.start)
            val to = lineOf(lineStarts, match.end - 1)
            // §14.3, D-49 the mask maps source lines to rendered lines: a multi-line secret keeps its line
            // breaks so the rendered view never shifts the numbering of the lines around it.
            repeat(to - from) { out.append('\n') }
            hidden += LineRange(from, to)
            hits += RedactionHit(match.kind, from)
            cursor = match.end
        }
        out.append(scanned, cursor, scanned.length)
        return Redacted(out.toString(), RedactionMask(Ranges.of(hidden), limitations), hits)
    }

    /**
     * Redacts captured bytes (runner logs). Binary content is hidden as a whole: it cannot be line-mapped, so
     * rendering part of it could expose a key that no text pattern matches.
     */
    public fun applyBytes(bytes: ByteArray): Redacted = applyBytes(bytes, ContentClass.ModelFacing)

    public fun applyBytes(bytes: ByteArray, content: ContentClass): Redacted {
        val refusal = refuse(content)
        require(refusal == null) { refusal.toString() }
        if (bytes.isEmpty()) return Redacted("", RedactionMask.NONE)
        if (looksBinary(bytes)) {
            return Redacted(
                "[REDACTED:binary]",
                RedactionMask(Ranges.single(1, 1), listOf("binary capture: ${bytes.size} bytes hidden, not line-mappable")),
                listOf(RedactionHit("binary", 1)),
            )
        }
        return apply(String(bytes, Charsets.UTF_8), content)
    }

    /** `null` when [content] may be rewritten; a typed refusal for the two byte-exact stores. */
    public fun refuse(content: ContentClass): Refusal? = when (content) {
        ContentClass.ModelFacing, ContentClass.ReusableEvidence -> null
        ContentClass.NativeReplay -> Refusal(
            "native-replay",
            RefusalReason.ProtectedContent,
            "signed opaque provider items are stored as captured and never rewritten (D-25)",
        )
        ContentClass.RecoveryPreimage -> Refusal(
            "recovery-preimage",
            RefusalReason.ProtectedContent,
            "exact rollback preimages are access-restricted recovery artifacts and are never redacted (D-14)",
        )
    }

    private fun unterminatedBlock(text: String): Found? {
        val open = PRIVATE_KEY_BEGIN.find(text) ?: return null
        if (text.indexOf("-----END", open.range.first) >= 0) return null
        return Found(open.range.first, text.length, "private-key-block", priority = -1)
    }

    private data class Found(val start: Int, val end: Int, val kind: String, val priority: Int)

    public companion object {
        private val PRIVATE_KEY_BEGIN = Regex("-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----")

        /** Content classes that must never be rewritten (D-25 native replay items, D-14 recovery preimages). */
        @JvmStatic
        public fun neverRewrite(content: ContentClass): Boolean =
            content == ContentClass.NativeReplay || content == ContentClass.RecoveryPreimage

        /** Prefix of [text] that fits in [maxBytes] UTF-8 bytes, plus whether anything was cut. */
        private fun capToBytes(text: String, maxBytes: Int): Pair<String, Boolean> {
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                val width = when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + width > maxBytes) return text.substring(0, i) to true
                bytes += width
                i += Character.charCount(codePoint)
            }
            return text to false
        }

        private fun lineStarts(text: String): IntArray {
            val starts = ArrayList<Int>()
            starts += 0
            text.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
            return starts.toIntArray()
        }

        /** 1-based source line of [offset]: the last line start at or before it. */
        private fun lineOf(lineStarts: IntArray, offset: Int): Int {
            var low = 0
            var high = lineStarts.size - 1
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (lineStarts[mid] <= offset) low = mid else high = mid - 1
            }
            return low + 1
        }

        private fun looksBinary(bytes: ByteArray): Boolean {
            val window = minOf(bytes.size, 8_192)
            var control = 0
            for (i in 0 until window) {
                val b = bytes[i].toInt() and 0xff
                if (b == 0) return true
                if (b < 0x20 && b != 0x09 && b != 0x0a && b != 0x0d) control++
            }
            return control * 100 > window * 10
        }
    }
}
