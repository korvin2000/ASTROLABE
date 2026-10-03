package io.astrolabe.tool.state

import io.astrolabe.register.Condition
import io.astrolabe.register.Op
import io.astrolabe.register.Patch
import io.astrolabe.register.PatchOp
import io.astrolabe.register.ValidationContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A parsed `state(patch)`: every raw op became a typed [PatchOp], or the first defect is named and nothing applies (§5.4
 * error policy). D-373: with a context, a defective op is skipped and named in [Valid.notes] while the others apply.
 */
public sealed interface ParsedPatch {
    /** [notes] name what was normalised or ignored (D-365); the result shows them. */
    public data class Valid(val patch: Patch, val notes: List<String> = emptyList()) : ParsedPatch

    public data class Invalid(val index: Int, val reason: String) : ParsedPatch
}

/**
 * Parses the raw ops of `state(patch: [...])` (§5.2, §5.5): one key per op — `plan.add`, `plan.cursor`,
 * `plan.tick`, `plan.cancel`, `fact.add`, `fact.refute`, `deadend.add`, `decision.add`, `open.add`,
 * `open.close`, `focus.set`, `amend.propose`, `next` — whose value is the op's fields as an object, or a
 * scalar for the op's one required field (`{"next": "…"}`, `{"plan.tick": 2}`), plus an optional `if`.
 * Sibling keys that are declared fields of the one op key join its fields (D-355); a collision still refuses.
 * An `evidence` of the form `op:N` is resolved to the alias of that op's result through [opResults].
 *
 * D-365, with a [context]: an unknown key beside or inside a known op is ignored and named in a note; an `evidence`
 * naming several ids (`"#35 #36"`, `"op:4,op:5"`, an array) keeps the first in the op's one slot and names the rest in a
 * note, each id resolving or the op refused naming it; a fact anchor whose `version` is a short hash takes the one
 * known version of that path it prefixes, and any other non-hash `version` drops the anchor with a note. A `v` fact
 * whose anchor is dropped is kept as `h`: unanchored, its staleness could not be tracked, and an anchor naming a
 * version this cell never showed (one carried from an earlier cell, say) is not current.
 *
 * D-373, with a [context]: an object carrying several op keys is split into one op per op key in key order, each other key
 * joining the one op whose form declares it (else ignored with a note); an op that does not parse is skipped with a note
 * and the others apply — unless a later op refers to the plan, facts or open items of a skipped add, or none is left;
 * an `evidence` naming several ids keeps the first stored one and names the rest.
 */
public object PatchParser {
    private val json = Json { ignoreUnknownKeys = false }

    private val scalarField = mapOf(
        "plan.add" to "text", "plan.cursor" to "n", "plan.tick" to "n", "open.add" to "text", "focus.set" to "dir", "next" to "text",
    )

    /** The fields of every op (§5.2), named in the `state` schema and in every schema refusal (D-349). */
    internal val FORMS: Map<String, String> = linkedMapOf(
        "plan.add" to "text, accept?, after?, req?",
        "plan.cursor" to "n",
        "plan.tick" to "n, evidence?",
        "plan.cancel" to "n, reason",
        "fact.add" to "kind: h|v|x, text, evidence?, anchor?: {path, version, line?}",
        "fact.refute" to "n, evidence",
        "deadend.add" to "text, evidence?, scope, reopen",
        "decision.add" to "text, because, rejected?, probe?, adrCandidate?",
        "open.add" to "text, trip?, needs?",
        "open.close" to "n, evidence",
        "focus.set" to "dir",
        "amend.propose" to "change, reason",
        "next" to "text",
    )

    /** The top-level field names of every form: [FORMS] without `?`, `: type` and the nested `{…}` of `anchor` (D-355). */
    private val FIELDS: Map<String, Set<String>> = FORMS.mapValues { (_, form) ->
        form.replace(Regex("""\{[^}]*\}"""), "").split(',').map { it.substringBefore(':').trim().removeSuffix("?") }.filter { it.isNotEmpty() }.toSet()
    }

    private fun form(name: String): String = "$name{${FORMS.getValue(name)}}"

    /** The op vocabulary as one line: forms, the scalar shorthand, `if` and the evidence names. */
    internal val VOCABULARY: String =
        "one op key per object, its value the fields: " + FORMS.keys.joinToString(" · ", transform = ::form) +
            "; a scalar stands for the one required field (" + scalarField.keys.joinToString(", ") + "), e.g. {\"next\": \"…\"}, {\"plan.tick\": 2}" +
            "; optional if: green(op:N)|applied(op:N); evidence is #N (a stored result) or op:N (a run or verify call of this turn)"

    @JvmStatic
    @JvmOverloads
    public fun parse(raw: List<JsonElement>, opResults: Map<Int, String> = emptyMap(), context: ValidationContext? = null): ParsedPatch {
        val ops = ArrayList<PatchOp>()
        val notes = ArrayList<String>()
        var firstInvalid: ParsedPatch.Invalid? = null
        // D-373: the families whose add was skipped, with the op that was; a later op of that family depends on it.
        val skippedAdds = LinkedHashMap<String, Int>()
        raw.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return ParsedPatch.Invalid(index + 1, "op ${index + 1} is not an object — $VOCABULARY")
            for (unit in split(index, obj, context, notes)) {
                val name = unit.keys.filter { it in FORMS }.singleOrNull()
                FAMILY_OF[name]?.let { family ->
                    skippedAdds[family]?.let { at -> return ParsedPatch.Invalid(index + 1, "op ${index + 1} ($name) refers to the $family of op $at, which was skipped") }
                }
                when (val one = one(index, unit, opResults, context, notes)) {
                    is ParsedPatch.Invalid -> {
                        if (context == null) return one
                        notes += skippedNote(index, one.reason)
                        if (firstInvalid == null) firstInvalid = one
                        ADDS[name]?.let { family -> skippedAdds.putIfAbsent(family, index + 1) }
                    }
                    is ParsedPatch.Valid -> ops += one.patch.ops
                }
            }
        }
        if (ops.isEmpty()) return firstInvalid ?: ParsedPatch.Invalid(0, "state(patch) needs at least one op")
        return ParsedPatch.Valid(Patch(ops), notes)
    }

    /** The plan, fact or open-item family an add creates, and the ops that refer to its numbers (D-373 dependency rule). */
    private val ADDS: Map<String?, String> = mapOf("plan.add" to "plan", "fact.add" to "facts", "open.add" to "open items")
    private val FAMILY_OF: Map<String?, String> = mapOf(
        "plan.cursor" to "plan", "plan.tick" to "plan", "plan.cancel" to "plan", "fact.refute" to "facts", "open.close" to "open items",
    )

    private fun skippedNote(index: Int, reason: String): String {
        val prefix = Regex("""^op ${index + 1}( \([^)]*\))?:? """).find(reason)
        return if (prefix == null) "op ${index + 1} skipped: $reason" else "op ${index + 1}${prefix.groupValues[1]} skipped: ${reason.substring(prefix.range.last + 1)}"
    }

    /** D-373: an object with several op keys becomes one object per op key, in key order; with one op key it is itself. */
    private fun split(index: Int, obj: JsonObject, context: ValidationContext?, notes: MutableList<String>): List<JsonObject> {
        val opKeys = obj.keys.filter { it in FORMS }
        if (context == null || opKeys.size < 2) return listOf(obj)
        val condition = obj["if"]?.let { mapOf("if" to it) }.orEmpty()
        val joined = opKeys.associateWith { LinkedHashMap<String, JsonElement>() }
        val ignored = ArrayList<String>()
        for (key in obj.keys - opKeys.toSet() - "if") {
            val owners = opKeys.filter { op ->
                val value = obj.getValue(op)
                key in FIELDS.getValue(op) && (value as? JsonObject)?.containsKey(key) != true && !(value is JsonPrimitive && scalarField[op] == key)
            }
            if (owners.size == 1) joined.getValue(owners.single())[key] = obj.getValue(key) else ignored += key
        }
        notes += "op ${index + 1}: split into ${opKeys.joinToString(", ")} (one op per op key)" +
            (if (ignored.isEmpty()) "" else "; ignored ${ignored.joinToString(", ") { "'$it'" }} — no single op of the object declares it")
        return opKeys.map { op -> JsonObject(mapOf(op to obj.getValue(op)) + condition + joined.getValue(op)) }
    }

    /** One op object as a one-op [ParsedPatch.Valid], or the defect that refuses it. */
    private fun one(index: Int, obj: JsonObject, opResults: Map<Int, String>, context: ValidationContext?, notes: MutableList<String>): ParsedPatch {
        val keys = obj.keys - "if"
        val oneOp = "op ${index + 1} needs exactly one op key, got ${keys.sorted()} — $VOCABULARY"
        // D-355: one op key whose declared fields sit beside it (`{"plan.tick": 1, "evidence": "#3"}`) is that op.
        // D-365: with a context, other keys beside the one op key are ignored and named.
        val name = keys.singleOrNull()
            ?: keys.filter { it in FORMS }.singleOrNull()?.takeIf { op -> context != null || (keys - op).all { it in FIELDS.getValue(op) } }
            ?: return ParsedPatch.Invalid(index + 1, oneOp)
        if (name !in FORMS) return ParsedPatch.Invalid(index + 1, "op ${index + 1}: unknown op '$name' — $VOCABULARY")
        val condition = obj["if"]?.let { c ->
            val text = (c as? JsonPrimitive)?.content ?: return ParsedPatch.Invalid(index + 1, "op ${index + 1}: if must be a string")
            Condition.parse(text) ?: return ParsedPatch.Invalid(index + 1, "op ${index + 1}: malformed condition '$text' (expected green(op:N) or applied(op:N))")
        }
        val value = obj.getValue(name)
        val own: Map<String, JsonElement> = when (value) {
            is JsonObject -> value
            is JsonPrimitive -> {
                val field = scalarField[name] ?: return ParsedPatch.Invalid(index + 1, "op ${index + 1}: $name needs an object of fields: ${form(name)}")
                mapOf(field to value)
            }
            else -> return ParsedPatch.Invalid(index + 1, "op ${index + 1}: $name needs an object or a scalar: ${form(name)}")
        }
        val siblings = obj.filterKeys { it != name && it != "if" }
        if (siblings.keys.any { it in own }) return ParsedPatch.Invalid(index + 1, oneOp)
        val declared = FIELDS.getValue(name)
        val fields = (own + siblings).let { all ->
            if (context == null || all.keys.all { it in declared }) all
            else all.filterKeys { it in declared }.also { notes += "op ${index + 1} ($name): ignored ${(all.keys - declared).joinToString(", ") { "'$it'" }} — form: ${form(name)}" }
        }
        val resolved = LinkedHashMap<String, JsonElement>(fields)
        fields["evidence"]?.let(::evidenceIds)?.let { named ->
            val ids = named.map { id -> if (id.startsWith("op:")) id.removePrefix("op:").toIntOrNull()?.let { opResults[it] } ?: id else id }
            if (ids.size > 1) {
                // D-373: the slot keeps the first stored id; the validator judges the one kept.
                val missing = if (context == null) emptyList() else ids.filter { !context.evidenceExists(it) }
                val kept = (ids - missing.toSet()).firstOrNull() ?: ids.first()
                val also = ids.filter { it != kept && it !in missing }
                notes += "op ${index + 1} ($name): one evidence slot — kept $kept" + (if (also.isEmpty()) "" else ", also cited ${also.joinToString(" ")}") +
                    (if (missing.isEmpty()) "" else "; not stored results: ${missing.joinToString(" ")}")
                resolved["evidence"] = JsonPrimitive(kept)
            } else {
                resolved["evidence"] = JsonPrimitive(ids.first())
            }
        }
        if (context != null) {
            (resolved["anchor"] as? JsonObject)?.let { anchor ->
                // A `v` fact without its anchor could never go stale: one whose version names no shown version is kept as `h`.
                val verified = (resolved["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "v"
                val kept = if (verified) "v fact kept as h without an anchor (its staleness could not be tracked); add it again with the @hash a look showed" else "fact kept without an anchor"
                val fixed = anchored(anchor, context, kept) { notes += "op ${index + 1} ($name): $it" }
                if (fixed == null) {
                    resolved.remove("anchor")
                    if (verified) resolved["kind"] = JsonPrimitive("h")
                } else {
                    resolved["anchor"] = fixed
                }
            }
        }
        val op = try {
            json.decodeFromJsonElement(Op.serializer(), JsonObject(mapOf("type" to JsonPrimitive(name)) + resolved))
        } catch (e: SerializationException) {
            return ParsedPatch.Invalid(index + 1, "op ${index + 1} ($name): ${e.message?.lineSequence()?.first()} — form: ${form(name)}")
        } catch (e: IllegalArgumentException) {
            return ParsedPatch.Invalid(index + 1, "op ${index + 1} ($name): ${e.message} — form: ${form(name)}")
        }
        return ParsedPatch.Valid(Patch(listOf(PatchOp(op, condition))))
    }

    private val SEPARATORS = Regex("[\\s,;]+")
    private val EVIDENCE_ID = Regex("""#\d+|op:\d+""")

    /**
     * The ids an `evidence` value names: one string; a string of several `#N`/`op:N` ids (any other text stays one
     * string, as before); or an array of strings. `null` leaves the value to the schema.
     */
    private fun evidenceIds(value: JsonElement): List<String>? = when {
        value is JsonPrimitive && value.isString -> value.content.trim().split(SEPARATORS).filter { it.isNotEmpty() }
            .takeIf { ids -> ids.size > 1 && ids.all(EVIDENCE_ID::matches) } ?: listOf(value.content)
        value is JsonArray && value.all { it is JsonPrimitive && it.isString } -> value.map { (it as JsonPrimitive).content.trim() }.filter { it.isNotEmpty() }
        else -> null
    }?.takeIf { it.isNotEmpty() }

    /** A fact anchor with a full version, a short one resolved against the path's known versions, or `null` (dropped, [note] says why and what was [kept]). */
    private fun anchored(anchor: JsonObject, context: ValidationContext, kept: String, note: (String) -> Unit): JsonObject? {
        val path = (anchor["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return anchor
        val given = (anchor["version"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val hex = given.removePrefix("@").lowercase()
        val isHex = hex.isNotEmpty() && hex.all { it in '0'..'9' || it in 'a'..'f' }
        if (isHex && hex.length == FULL_HASH) return if (hex == given) anchor else JsonObject(anchor + ("version" to JsonPrimitive(hex)))
        if (isHex && hex.length >= MIN_HASH && hex.length < FULL_HASH) {
            val matches = context.knownVersions(path).filter { it.digest.hex.startsWith(hex) }.distinct()
            matches.singleOrNull()?.let { return JsonObject(anchor + ("version" to JsonPrimitive(it.digest.hex))) }
            note("anchor $path @$hex matches ${matches.size} known versions; $kept")
            return null
        }
        note("anchor $path version '$given' is not a content hash; $kept")
        return null
    }

    private const val MIN_HASH = 4
    private const val FULL_HASH = 64

    /** The `family.op` name of a raw patch op, for logs. */
    @JvmStatic
    public fun nameOf(element: JsonElement): String? = (element as? JsonObject)?.keys?.minus("if")?.singleOrNull()

    internal fun textOf(element: JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: runCatching { element.jsonObject["text"]?.jsonPrimitive?.content }.getOrNull()
}
