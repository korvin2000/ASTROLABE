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

/** A parsed `state(patch)`: every raw op became a typed [PatchOp], or the first defect is named and nothing applies (§5.4 error policy). */
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
 * known version of that path it prefixes, and any other non-hash `version` drops the anchor with a note.
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
        raw.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return ParsedPatch.Invalid(index + 1, "op ${index + 1} is not an object — $VOCABULARY")
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
                    ids.firstOrNull { context != null && !context.evidenceExists(it) }?.let { missing ->
                        return ParsedPatch.Invalid(index + 1, "op ${index + 1} ($name): evidence '$missing' is not a stored result — name each by its alias #N, or a run or verify call of this turn as op:N")
                    }
                    notes += "op ${index + 1} ($name): one evidence slot — kept ${ids.first()}, also cited ${ids.drop(1).joinToString(" ")}"
                }
                resolved["evidence"] = JsonPrimitive(ids.first())
            }
            if (context != null) {
                (resolved["anchor"] as? JsonObject)?.let { anchor ->
                    val fixed = anchored(anchor, context) { notes += "op ${index + 1} ($name): $it" }
                    if (fixed == null) resolved.remove("anchor") else resolved["anchor"] = fixed
                }
            }
            val op = try {
                json.decodeFromJsonElement(Op.serializer(), JsonObject(mapOf("type" to JsonPrimitive(name)) + resolved))
            } catch (e: SerializationException) {
                return ParsedPatch.Invalid(index + 1, "op ${index + 1} ($name): ${e.message?.lineSequence()?.first()} — form: ${form(name)}")
            } catch (e: IllegalArgumentException) {
                return ParsedPatch.Invalid(index + 1, "op ${index + 1} ($name): ${e.message} — form: ${form(name)}")
            }
            ops += PatchOp(op, condition)
        }
        if (ops.isEmpty()) return ParsedPatch.Invalid(0, "state(patch) needs at least one op")
        return ParsedPatch.Valid(Patch(ops), notes)
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

    /** A fact anchor with a full version, a short one resolved against the path's known versions, or `null` (dropped, [note] says why). */
    private fun anchored(anchor: JsonObject, context: ValidationContext, note: (String) -> Unit): JsonObject? {
        val path = (anchor["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return anchor
        val given = (anchor["version"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val hex = given.removePrefix("@").lowercase()
        val isHex = hex.isNotEmpty() && hex.all { it in '0'..'9' || it in 'a'..'f' }
        if (isHex && hex.length == FULL_HASH) return if (hex == given) anchor else JsonObject(anchor + ("version" to JsonPrimitive(hex)))
        if (isHex && hex.length >= MIN_HASH && hex.length < FULL_HASH) {
            val matches = context.knownVersions(path).filter { it.digest.hex.startsWith(hex) }.distinct()
            matches.singleOrNull()?.let { return JsonObject(anchor + ("version" to JsonPrimitive(it.digest.hex))) }
            note("anchor $path @$hex matches ${matches.size} known versions; fact kept without an anchor")
            return null
        }
        note("anchor $path version '$given' is not a content hash; fact kept without an anchor")
        return null
    }

    private const val MIN_HASH = 4
    private const val FULL_HASH = 64

    /** The `family.op` name of a raw patch op, for logs. */
    @JvmStatic
    public fun nameOf(element: JsonElement): String? = (element as? JsonObject)?.keys?.minus("if")?.singleOrNull()

    internal fun textOf(element: JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: runCatching { element.jsonObject["text"]?.jsonPrimitive?.content }.getOrNull()
}
