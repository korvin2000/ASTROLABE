package io.astrolabe.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `look(what, target, budget, near?, glob?, in, since?)` (§5.4). */
@Serializable
public data class LookArgs(
    val what: String,
    val target: String? = null,
    val budget: Int = 1_500,
    val near: String? = null,
    val glob: String? = null,
    @SerialName("in") val scope: String = "workspace",
    val since: String? = null,
    /** `recall(id, range?, since?)`: the result id to recall. */
    val id: String? = null,
    val range: String? = null,
) {
    init {
        require(what in ToolOps.look) { "unknown look op '$what'" }
        require(scope in setOf("workspace", "store", "kb")) { "unknown look scope '$scope'" }
        require(budget > 0) { "budget must be positive" }
    }
}

@Serializable
public data class HunkArgs(val anchor: String, val near: String? = null, val new: String)

@Serializable
public data class TransformArgs(
    val script: String? = null,
    val argv: List<String>? = null,
    @SerialName("scope_glob") val scopeGlob: String,
    val inventory: List<String>? = null,
    @SerialName("expected_matches") val expectedMatches: ExpectedMatches? = null,
    val preconditions: List<String>? = null,
    val why: String,
) {
    init {
        require((script != null) xor (argv != null)) { "transform needs exactly one of script or argv" }
    }
}

@Serializable
public data class ExpectedMatches(val min: Int, val max: Int) {
    init {
        require(min in 0..max) { "expected_matches needs 0 ≤ min ≤ max" }
    }
}

/** One edit op (§5.4): exactly one form per element. */
@Serializable
public data class EditOpArgs(
    val path: String? = null,
    val expect: String? = null,
    val hunks: List<HunkArgs>? = null,
    val create: String? = null,
    val content: String? = null,
    val delete: String? = null,
    val rename: String? = null,
    val to: String? = null,
    val revert: String? = null,
    val transform: TransformArgs? = null,
    @SerialName("if") val condition: String? = null,
) {
    val kind: String
        get() = when {
            transform != null -> "transform"
            revert != null -> "revert"
            rename != null -> "rename"
            delete != null -> "delete"
            create != null -> "create"
            path != null -> "anchored"
            else -> "invalid"
        }

    init {
        val forms = listOfNotNull(path, create, delete, rename, revert, transform).size
        require(forms == 1) { "an edit op needs exactly one form (path|create|delete|rename|revert|transform), got $forms" }
        // D-346: an omitted or short `expect` is resolved by the edit tool against the versions shown in this cell.
        if (path != null) require(!hunks.isNullOrEmpty()) { "anchored edits need hunks (§9.1)" }
        if (rename != null) require(!to.isNullOrBlank()) { "rename needs to" }
        if (create != null) require(content != null) { "create needs content" }
    }
}

/**
 * Input tolerance before typed decoding (D-347, D-348): shapes weaker models send that have exactly one reading.
 * `ops` and `patch` sent as a JSON string holding an array are parsed; an edit op's empty placeholders of the
 * *other* forms (and an empty `if`) are dropped by the per-form whitelist [FORM_FIELDS]. A form's own fields keep
 * their empty values (`content: ""` creates an empty file, `new: ""` deletes the match), unknown keys still refuse.
 */
internal object InputTolerance {
    /** The fields each edit form owns (§5.4); every other known field is a placeholder when empty. */
    private val FORM_FIELDS: Map<String, Set<String>> = mapOf(
        "path" to setOf("path", "expect", "hunks"),
        "create" to setOf("create", "content"),
        "delete" to setOf("delete", "expect"),
        "rename" to setOf("rename", "to", "expect"),
        "revert" to setOf("revert"),
        "transform" to setOf("transform"),
    )

    private val EDIT_FIELDS: Set<String> = FORM_FIELDS.values.flatten().toSet() + "if"

    private val json = Json { ignoreUnknownKeys = false }

    fun normalise(family: ToolFamily, raw: JsonObject): JsonObject = when (family) {
        ToolFamily.Edit -> raw.mapValue("ops") { ops -> parsedArray(ops).let { if (it is JsonArray) JsonArray(it.map(::editOp)) else it } }
        ToolFamily.State -> raw.mapValue("patch", ::parsedArray)
        else -> raw
    }

    private fun JsonObject.mapValue(key: String, change: (JsonElement) -> JsonElement): JsonObject {
        val value = this[key] ?: return this
        val changed = change(value)
        return if (changed == value) this else JsonObject(this + (key to changed))
    }

    /** A JSON string whose content is an array becomes that array; anything else is left for the schema to refuse. */
    private fun parsedArray(value: JsonElement): JsonElement {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return value
        if (!text.startsWith("[")) return value
        return (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray) ?: value
    }

    private fun editOp(op: JsonElement): JsonElement {
        if (op !is JsonObject) return op
        val form = FORM_FIELDS.keys.filter { key -> op[key]?.let { !empty(it) } == true }.singleOrNull() ?: return op
        val own = FORM_FIELDS.getValue(form)
        val kept = op.filter { (key, value) -> key !in EDIT_FIELDS || key in own || !empty(value) }
        return if (kept.size == op.size) op else JsonObject(kept)
    }

    private fun empty(value: JsonElement): Boolean = when (value) {
        is JsonNull -> true
        is JsonPrimitive -> if (value.isString) value.content.isBlank() else value.content == "0" || value.content == "false"
        is JsonArray -> value.all(::empty)
        is JsonObject -> value.values.all(::empty)
    }
}

@Serializable
public data class EditArgs(val ops: List<EditOpArgs>, val why: String) {
    init {
        require(ops.isNotEmpty()) { "edit needs at least one op" }
    }
}

/** `run(argv|cmd, …)`, `run(op=poll, handle, since?)`, `run(op=cancel, handle)` (§5.4). */
@Serializable
public data class RunArgs(
    val op: String = "run",
    val argv: List<String>? = null,
    val cmd: String? = null,
    val cwd: String? = null,
    val shape: String = "auto",
    val budget: Int = 1_200,
    val timeout: Int = 120,
    val bg: Boolean = false,
    val intent: String? = null,
    @SerialName("class_hint") val classHint: String? = null,
    @SerialName("if") val condition: String? = null,
    val handle: String? = null,
    val since: Long? = null,
) {
    init {
        require(op in ToolOps.run) { "unknown run op '$op'" }
        when (op) {
            "run" -> require((argv != null && argv.isNotEmpty()) xor (!cmd.isNullOrBlank())) { "run needs exactly one of argv or cmd" }
            else -> require(!handle.isNullOrBlank()) { "$op needs a handle" }
        }
        require(budget > 0 && timeout > 0) { "budget and timeout must be positive" }
    }
}

@Serializable
public data class VerifyArgs(
    val what: String,
    val paths: List<String>? = null,
    val selection: String? = null,
    val ids: List<String>? = null,
    val scope: String? = null,
) {
    init {
        require(what in ToolOps.verify) { "unknown verify op '$what'" }
        selection?.let { require(it in setOf("blast", "accept", "full", "ids")) { "unknown tests selection '$it'" } }
    }
}

@Serializable
public data class BlockedArgs(val reason: String, val evidence: List<String> = emptyList(), val question: String? = null)

@Serializable
public data class RetrievalMissArgs(val need: String, val why: String)

/** `state(patch: [...])` carries raw patch ops (one key per op, optional `if`), parsed by the state tool (P1.6.8). */
@Serializable
public data class StateArgs(
    val op: String,
    val patch: List<JsonElement>? = null,
    val blocked: BlockedArgs? = null,
    @SerialName("retrieval_miss") val retrievalMiss: RetrievalMissArgs? = null,
) {
    init {
        require(op in ToolOps.state) { "unknown state op '$op'" }
        when (op) {
            "patch" -> require(!patch.isNullOrEmpty()) { "state(patch) needs ops" }
            "blocked" -> require(blocked != null) { "state(blocked) needs reason and evidence" }
            "retrieval_miss" -> require(retrievalMiss != null) { "state(retrieval_miss) needs need and why" }
        }
    }
}

@Serializable
public data class TaskArgs(
    val op: String,
    val question: String? = null,
    val options: List<String>? = null,
    val kind: String? = null,
    val packet: JsonElement? = null,
    val mode: String? = null,
    val handle: String? = null,
    val proposal: JsonElement? = null,
    /** `answer` (D-344): the reply to a request that needs no change to the files. */
    val text: String? = null,
) {
    init {
        require(op in ToolOps.task) { "unknown task op '$op'" }
        if (op == "ask") require(!question.isNullOrBlank()) { "task(ask) needs a question" }
        if (op == "answer") require(!text.isNullOrBlank()) { "task(answer) needs the answer as text" }
        if (op == "propose") {
            require(kind in PROPOSAL_KINDS) { "task(propose) needs kind ${PROPOSAL_KINDS.joinToString("|")}, got '$kind'" }
            require(proposal != null) { "task(propose) needs a proposal object" }
        }
    }

    public companion object {
        public val PROPOSAL_KINDS: List<String> = listOf("plan", "increment_split", "amendment")
    }
}

@Serializable
public data class KbArgs(
    val op: String,
    val query: String? = null,
    val kinds: List<String>? = null,
    val scope: String? = null,
    val why: String? = null,
    val id: String? = null,
    val note: JsonElement? = null,
    val offset: Int = 0,
    val version: String? = null,
) {
    init {
        require(offset >= 0) { "kb offset must be nonnegative" }
        require(offset == 0 || !version.isNullOrBlank()) { "continued kb pages need the returned version" }
        require(op in ToolOps.kb) { "unknown kb op '$op'" }
        when (op) {
            "search" -> require(!query.isNullOrBlank() && !why.isNullOrBlank()) { "kb(search) needs query and why" }
            "get", "skill" -> require(!id.isNullOrBlank()) { "kb($op) needs an id" }
            "propose" -> require(note != null) { "kb(propose) needs a note" }
        }
    }
}
