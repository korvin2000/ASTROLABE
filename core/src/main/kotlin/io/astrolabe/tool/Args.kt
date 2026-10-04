package io.astrolabe.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `look(what, target, budget, near?, glob?, in, since?)` (§5.4). An omitted [budget] is the configured
 * `Defaults.lookBudgetTokens`, resolved by the executor.
 */
@Serializable
public data class LookArgs(
    val what: String,
    val target: String? = null,
    val budget: Int? = null,
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
        require(budget == null || budget > 0) { "budget must be positive" }
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
 *
 * `state` gets two more repairs, each with one reading only (F8). A key that carries the model's own tool markup
 * (`blocked<arg_key>evidence`: the nested `blocked` object flattened, its first field glued to the parent key) is split
 * at the first `<arg_key>`, the `</arg_key>`/`<arg_value>`/`</arg_value>` remnants are dropped from both halves, and
 * when the outer half is `blocked` or `retrieval_miss` the object is rebuilt with that form's fields (the glued one and
 * its top-level siblings `reason|evidence|question`, `need|why`) nested again; any other key stays where it is and
 * the schema refuses it by name. Then a call without `op` gets `op` = the one of `patch`, `blocked`, `retrieval_miss`
 * that it carries; with none or several present (or an `op` already given) nothing is added.
 * A `patch`/`ops` string that opens like JSON (`[` or `{`) but does not parse is refused with the parser's own message.
 *
 * D-365: an `edit` whose op fields sit at the top level is one op — without `ops` they become `ops: [that op]`; with
 * `ops` holding exactly one op, every top-level `path`/`expect`/`hunks`/`if` it lacks moves into it (D-373), one it holds
 * with another value is refused naming both; any other mix is refused naming the accepted form. `hunks` sent as a JSON string holding an array is parsed like `ops`. A `look` `id` sent as a
 * number is that number as text.
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

    /** The nested `state` forms and the fields each owns; `patch` is an array, so it is never rebuilt. */
    private val STATE_NESTED: Map<String, Set<String>> = mapOf(
        "blocked" to setOf("reason", "evidence", "question"),
        "retrieval_miss" to setOf("need", "why"),
        // A-D.4: the direct protocol's note; its executor reads the object raw.
        "note" to setOf("kind", "text", "evidence", "closes", "refutes"),
    )

    private val STATE_FORMS: List<String> = listOf("patch") + STATE_NESTED.keys

    private const val ARG_KEY = "<arg_key>"
    private val MARKUP_REMNANTS = listOf("</arg_key>", "<arg_value>", "</arg_value>")

    private val json = Json { ignoreUnknownKeys = false }

    /** [notes] collects what was repaired (D-373), for the call's result; a [truncated] response repairs nothing (D-375). */
    fun normalise(family: ToolFamily, raw: JsonObject, notes: MutableList<String> = ArrayList(), truncated: Boolean = false): JsonObject = when (family) {
        ToolFamily.Edit -> lifted(raw.mapValue("ops") { parsedArray("ops", it, notes, truncated) })
            .mapValue("ops") { ops -> if (ops is JsonArray) JsonArray(ops.map { editOp(it, notes, truncated) }) else ops }
        ToolFamily.State -> noteLifted(withInferredOp(unglued(raw.mapValue("patch") { parsedArray("patch", it, notes, truncated) })))
        ToolFamily.Look -> raw.mapValue("id") { id -> if (id is JsonPrimitive && !id.isString && id.content.toIntOrNull() != null) JsonPrimitive(id.content) else id }
        else -> raw
    }

    /** The fields a single op's top-level copy may hand to the one op of `ops` (D-373: `path` and `hunks` too). */
    private val LIFTABLE: Set<String> = setOf("path", "expect", "hunks", "if")

    private const val EDIT_FORM: String =
        "{\"ops\":[{\"path\":\"…\",\"expect\":\"…\",\"hunks\":[{\"anchor\":\"…\",\"new\":\"…\"}]}],\"why\":\"…\"} — every op field inside its op"

    /** D-365: op fields at the top level of an `edit` call, moved into the one op they can belong to. */
    private fun lifted(raw: JsonObject): JsonObject {
        val loose = raw.filterKeys { it in EDIT_FIELDS }
        if (loose.isEmpty()) return raw
        val rest = raw.filterKeys { it !in EDIT_FIELDS }
        val ops = raw["ops"] ?: return JsonObject(rest + ("ops" to JsonArray(listOf(JsonObject(loose)))))
        val single = ((ops as? JsonArray)?.singleOrNull() as? JsonObject)
        if (single == null || loose.keys.any { it !in LIFTABLE }) {
            throw IllegalArgumentException("top-level ${loose.keys.joinToString(", ") { "'$it'" }} beside ops${(ops as? JsonArray)?.let { " (${it.size} ops)" } ?: ""} has no single op to belong to; send $EDIT_FORM")
        }
        loose.entries.firstOrNull { (key, value) -> single[key]?.let { !empty(it) && it != value } == true }?.let { (key, value) ->
            throw IllegalArgumentException("top-level '$key' $value and the op's '$key' ${single[key]} differ; send $EDIT_FORM")
        }
        return JsonObject(rest + ("ops" to JsonArray(listOf(JsonObject(single + loose)))))
    }

    private fun JsonObject.mapValue(key: String, change: (JsonElement) -> JsonElement): JsonObject {
        val value = this[key] ?: return this
        val changed = change(value)
        return if (changed == value) this else JsonObject(this + (key to changed))
    }

    /**
     * A JSON string whose content is an array becomes that array; text that opens like JSON but does not parse gets the
     * syntax repair of [JsonRepair] (D-373, D-375, noted), else is refused with the parser's message and position;
     * anything else is left for the schema to refuse.
     */
    private fun parsedArray(key: String, value: JsonElement, notes: MutableList<String>, truncated: Boolean): JsonElement {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return value
        if (!text.startsWith("[") && !text.startsWith("{")) return value
        val parsed = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            val outcome = JsonRepair.repair(json, text, truncated)
            val fixed = (outcome as? JsonRepair.Repaired)?.takeIf { it.element is JsonArray }
                ?: throw IllegalArgumentException("$key is a string holding invalid JSON: ${e.message?.lineSequence()?.first()}${JsonRepair.suffix(outcome)}")
            notes += "$key string repaired: ${fixed.summary}"
            fixed.element
        }
        return if (parsed is JsonArray) parsed else value
    }

    private fun markupFree(text: String): String = MARKUP_REMNANTS.fold(text) { acc, tag -> acc.replace(tag, "") }.trim()

    /** `blocked<arg_key>evidence` and its siblings become `blocked: {evidence, ...}` again; other shapes are left alone. */
    private fun unglued(raw: JsonObject): JsonObject {
        val glued = raw.keys.filter { ARG_KEY in it }
        if (glued.isEmpty()) return raw
        val rebuilt = LinkedHashMap<String, LinkedHashMap<String, JsonElement>>()
        val moved = HashSet<String>()
        for (key in glued) {
            val outer = markupFree(key.substringBefore(ARG_KEY))
            val inner = markupFree(key.substringAfter(ARG_KEY))
            if (outer !in STATE_NESTED || inner.isEmpty() || outer in raw) continue
            rebuilt.getOrPut(outer) { LinkedHashMap() }[inner] = raw.getValue(key)
            moved += key
        }
        if (rebuilt.isEmpty()) return raw
        for ((outer, nested) in rebuilt) {
            for ((key, value) in raw) {
                if (key in STATE_NESTED.getValue(outer)) {
                    nested.putIfAbsent(key, value)
                    moved += key
                }
            }
        }
        val kept: Map<String, JsonElement> = raw.filterKeys { it !in moved }
        return JsonObject(kept + rebuilt.mapValues { (_, nested) -> JsonObject(nested) })
    }

    /** A-D.4: the flat `state(op=note, kind=…, text=…)` is the nested `note` object; the note's fields move into it. */
    private fun noteLifted(raw: JsonObject): JsonObject {
        if ((raw["op"] as? JsonPrimitive)?.content != "note" || "note" in raw) return raw
        val fields = STATE_NESTED.getValue("note")
        val loose = raw.filterKeys { it in fields }
        if (loose.isEmpty()) return raw
        return JsonObject(raw.filterKeys { it !in fields } + ("note" to JsonObject(loose)))
    }

    /** D-347 style: a `state` call without `op` that carries exactly one form names that form. */
    private fun withInferredOp(raw: JsonObject): JsonObject {
        if ("op" in raw) return raw
        // A-D.4: `note` names the op only alone, so a structured form beside a stray `note` key infers as before.
        val forms = STATE_FORMS.filter { it in raw }
        val op = (if (forms.size > 1) forms - "note" else forms).singleOrNull() ?: return raw
        return JsonObject(raw + ("op" to JsonPrimitive(op)))
    }

    private fun editOp(element: JsonElement, notes: MutableList<String>, truncated: Boolean): JsonElement {
        if (element !is JsonObject) return element
        val op = element.mapValue("hunks") { parsedArray("hunks", it, notes, truncated) }
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

/**
 * `run(argv|cmd, …)`, `run(op=poll, handle, since?)`, `run(op=wait, handle, until_line?, until_port?)`,
 * `run(op=cancel, handle)` (§5.4). An omitted [budget] or [timeout] (seconds) is the configured
 * `Defaults.runBudgetTokens` / `runTimeoutSeconds`, resolved by the executor. [untilLine] (a regex over output lines)
 * and [untilPort] (a loopback port that accepts connections) are readiness conditions: on a launch they imply `bg`.
 */
@Serializable
public data class RunArgs @JvmOverloads constructor(
    val op: String = "run",
    val argv: List<String>? = null,
    val cmd: String? = null,
    val cwd: String? = null,
    val shape: String = "auto",
    val budget: Int? = null,
    val timeout: Int? = null,
    val bg: Boolean = false,
    val intent: String? = null,
    @SerialName("class_hint") val classHint: String? = null,
    @SerialName("if") val condition: String? = null,
    val handle: String? = null,
    val since: Long? = null,
    @SerialName("until_line") val untilLine: String? = null,
    @SerialName("until_port") val untilPort: Int? = null,
) {
    init {
        require(op in ToolOps.run) { "unknown run op '$op'" }
        require(untilPort == null || untilPort in 1..65_535) { "until_port must be a TCP port (1-65535)" }
        when (op) {
            "run" -> require((argv != null && argv.isNotEmpty()) xor (!cmd.isNullOrBlank())) { "run needs exactly one of argv or cmd" }
            else -> require(!handle.isNullOrBlank()) { "$op needs a handle" }
        }
        require((budget == null || budget > 0) && (timeout == null || timeout > 0)) { "budget and timeout must be positive" }
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
public data class StateArgs @JvmOverloads constructor(
    val op: String,
    val patch: List<JsonElement>? = null,
    val blocked: BlockedArgs? = null,
    @SerialName("retrieval_miss") val retrievalMiss: RetrievalMissArgs? = null,
    /** `state(note)` of the direct protocol (kernel contract A-D.4), kept raw: its executor reads it. */
    val note: JsonElement? = null,
) {
    init {
        require(ToolOps.name(ToolFamily.State, op) in ToolOps.known) { "unknown state op '$op'" }
        when (op) {
            "patch" -> require(!patch.isNullOrEmpty()) { "state(patch) needs ops" }
            "blocked" -> require(blocked != null) { "state(blocked) needs reason and evidence" }
            "retrieval_miss" -> require(retrievalMiss != null) { "state(retrieval_miss) needs need and why" }
        }
    }
}

@Serializable
public data class TaskArgs @JvmOverloads constructor(
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
    /** `finish(after_checks)` of the direct protocol (kernel contract A-D.5). */
    @SerialName("after_checks") val afterChecks: Boolean? = null,
) {
    init {
        require(ToolOps.name(ToolFamily.Task, op) in ToolOps.known) { "unknown task op '$op'" }
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
