package io.astrolabe.tool.state

import io.astrolabe.register.Condition
import io.astrolabe.register.Op
import io.astrolabe.register.Patch
import io.astrolabe.register.PatchOp
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A parsed `state(patch)`: every raw op became a typed [PatchOp], or the first defect is named and nothing applies (§5.4 error policy). */
public sealed interface ParsedPatch {
    public data class Valid(val patch: Patch) : ParsedPatch

    public data class Invalid(val index: Int, val reason: String) : ParsedPatch
}

/**
 * Parses the raw ops of `state(patch: [...])` (§5.2, §5.5): one key per op — `plan.add`, `plan.cursor`,
 * `plan.tick`, `plan.cancel`, `fact.add`, `fact.refute`, `deadend.add`, `decision.add`, `open.add`,
 * `open.close`, `focus.set`, `amend.propose`, `next` — whose value is the op's fields as an object, or a
 * scalar for the op's one required field (`{"next": "…"}`, `{"plan.tick": 2}`), plus an optional `if`.
 * Sibling keys that are declared fields of the one op key join its fields (D-355); a collision still refuses.
 * An `evidence` of the form `op:N` is resolved to the alias of that op's result through [opResults].
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
    public fun parse(raw: List<JsonElement>, opResults: Map<Int, String> = emptyMap()): ParsedPatch {
        val ops = ArrayList<PatchOp>()
        raw.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return ParsedPatch.Invalid(index + 1, "op ${index + 1} is not an object — $VOCABULARY")
            val keys = obj.keys - "if"
            val oneOp = "op ${index + 1} needs exactly one op key, got ${keys.sorted()} — $VOCABULARY"
            // D-355: one op key whose declared fields sit beside it (`{"plan.tick": 1, "evidence": "#3"}`) is that op.
            val name = keys.singleOrNull()
                ?: keys.filter { it in FORMS }.singleOrNull()?.takeIf { op -> (keys - op).all { it in FIELDS.getValue(op) } }
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
            val fields = own + siblings
            val resolved = fields.mapValues { (key, v) ->
                if (key == "evidence" && v is JsonPrimitive && v.isString && v.content.startsWith("op:")) {
                    val id = v.content.removePrefix("op:").toIntOrNull()
                    JsonPrimitive(id?.let { opResults[it] } ?: v.content)
                } else {
                    v
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
        return ParsedPatch.Valid(Patch(ops))
    }

    /** The `family.op` name of a raw patch op, for logs. */
    @JvmStatic
    public fun nameOf(element: JsonElement): String? = (element as? JsonObject)?.keys?.minus("if")?.singleOrNull()

    internal fun textOf(element: JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: runCatching { element.jsonObject["text"]?.jsonPrimitive?.content }.getOrNull()
}
