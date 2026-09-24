package io.astrolabe.kb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Note kinds (§4.5). `STATUS` is same-task only; `CAL` is the calibration prior (§6.7). */
@Serializable
public enum class NoteKind { ADR, CON, LES, PIT, BMAP, NEG, SKILL, STATUS, CAL }

/** Note status (§4.5). Only [Admitted] notes are active: indexed and injectable. */
@Serializable
public enum class NoteStatus(public val wire: String) {
    @SerialName("candidate")
    Candidate("candidate"),

    @SerialName("admitted")
    Admitted("admitted"),

    @SerialName("stale")
    Stale("stale"),

    @SerialName("superseded")
    Superseded("superseded"),

    @SerialName("deprecated")
    Deprecated("deprecated"),

    @SerialName("rejected")
    Rejected("rejected"),
}

@Serializable
public data class NoteAnchor @JvmOverloads constructor(val path: String, val version: String? = null, val symbol: String? = null) {
    init {
        require(path.isNotBlank()) { "an anchor names a path" }
    }
}

@Serializable
public data class NoteBasis @JvmOverloads constructor(val requirementRefs: List<String> = emptyList(), val evidenceRefs: List<String> = emptyList())

/** `depends_on: [contract@v, path@hash]`, the stamp it was last validated at and what invalidates it (§4.4). */
@Serializable
public data class NoteValidity @JvmOverloads constructor(
    val dependsOn: List<String> = emptyList(),
    val lastValidated: String? = null,
    val invalidationTrigger: String? = null,
)

@Serializable
public data class NoteOrigin @JvmOverloads constructor(
    val work: String? = null,
    val cell: String? = null,
    val extractor: String? = null,
    /** `harness` for STATUS/CAL (D-36), `policy` or a person for admitted candidates. */
    val admittedBy: String? = null,
)

@Serializable
public data class NoteUsage @JvmOverloads constructor(val injected: Int = 0, val cited: Int = 0, val lastCited: String? = null)

/** A linked, versioned detail unit of a `BMAP`/`SKILL` note; its mandatory parts still count against the compile budget. */
@Serializable
public data class NoteModule(val id: String, val version: Int, val digest: String) {
    init {
        require(id.isNotBlank() && version >= 1 && digest.isNotBlank()) { "a module needs an id, a version ≥ 1 and a digest" }
    }
}

/**
 * A knowledge-base note (§4.5 front matter). The body is a compact unit — at most [MAX_BODY_TOKENS] tokens (checked by
 * [KbWriter]) and never a code body; `BMAP`/`SKILL` detail lives in [modules]. SQLite is canonical; the Markdown
 * export is a derived view (D-24).
 */
@Serializable
public data class Note @JvmOverloads constructor(
    val id: String,
    val kind: NoteKind,
    val status: NoteStatus,
    val summary: String,
    val body: String,
    /** `global`, `subsystem:<name>`, a path glob, a task family or roles. */
    val scope: String,
    val anchors: List<NoteAnchor> = emptyList(),
    val confidence: Double? = null,
    val basis: NoteBasis = NoteBasis(),
    val validity: NoteValidity = NoteValidity(),
    val supersedes: String? = null,
    val signedBy: String? = null,
    val origin: NoteOrigin = NoteOrigin(),
    val usage: NoteUsage = NoteUsage(),
    val modules: List<NoteModule> = emptyList(),
) {
    init {
        require(id.startsWith("${kind.name}-") && id.length > kind.name.length + 1) { "note id '$id' must be ${kind.name}-<name>" }
        require(summary.isNotBlank() && summary.length <= MAX_SUMMARY_CHARS) { "summary is the index line: 1..$MAX_SUMMARY_CHARS chars, got ${summary.length}" }
        require("```" !in body) { "a note body carries no code body; link a module or an anchor" }
        require(scope.isNotBlank()) { "a note is scoped" }
        require(confidence == null || confidence in 0.0..1.0) { "confidence is in [0, 1]" }
        require(modules.isEmpty() || kind == NoteKind.BMAP || kind == NoteKind.SKILL) { "only BMAP and SKILL notes link modules" }
    }

    /** The one-line index entry. */
    val line: String get() = "- $id: $summary"

    public companion object {
        public const val MAX_SUMMARY_CHARS: Int = 200
        public const val MAX_BODY_TOKENS: Int = 120
    }
}
