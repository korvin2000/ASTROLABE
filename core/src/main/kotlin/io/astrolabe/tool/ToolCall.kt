package io.astrolabe.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parsed tool arguments: one type per family, parsed once at the boundary (§2.3). */
public sealed interface Args {
    public data class Look(val args: LookArgs) : Args
    public data class Edit(val args: EditArgs) : Args
    public data class Run(val args: RunArgs) : Args
    public data class Verify(val args: VerifyArgs) : Args
    public data class State(val args: StateArgs) : Args
    public data class Task(val args: TaskArgs) : Args
    public data class Kb(val args: KbArgs) : Args
}

/**
 * A validated tool call of one turn. [opId] is the emitted order (1-based) and survives phase partitioning
 * (§5.4); [op] is the `family.op` name checked against the mask by the executor.
 */
public data class ToolCall(
    val opId: Int,
    val providerCallId: String,
    val family: ToolFamily,
    val op: String,
    val args: Args,
    val raw: JsonObject,
    /** D-373: what the harness normalised in the arguments (a syntax repair); the result shows each as a `note:` line. */
    val notes: List<String> = emptyList(),
) {
    val name: String get() = ToolOps.name(family, op)

    internal val operationNames: List<String>
        get() = (args as? Args.Edit)?.args?.ops?.map { ToolOps.name(family, it.kind) } ?: listOf(name)

    /** `if: green(op:N)` / `applied(op:N)` when present. */
    val condition: String?
        get() = when (val a = args) {
            is Args.Run -> a.args.condition
            is Args.Edit -> a.args.ops.firstNotNullOfOrNull { it.condition }
            else -> null
        }
}

public sealed interface ParsedCalls {
    public data class Valid(val calls: List<ToolCall>) : ParsedCalls

    /** §5.4 error policy: one line names the schema error; the cell refuses that call alone (D-372). */
    public data class Invalid(val providerCallId: String, val error: String) : ParsedCalls
}

public object ToolCalls {
    private val json = Json { ignoreUnknownKeys = false }

    /**
     * Parses every provider tool call of a turn; the first unparseable or unknown call makes the list [ParsedCalls.Invalid].
     * [truncated]: the provider stopped the response for length, so malformed arguments are cut, never repaired (D-375).
     */
    @JvmStatic
    @JvmOverloads
    public fun parse(calls: List<io.astrolabe.provider.ToolCall>, truncated: Boolean = false): ParsedCalls {
        val out = ArrayList<ToolCall>()
        calls.forEachIndexed { i, call ->
            val family = ToolFamily.byWire(call.name) ?: return ParsedCalls.Invalid(call.id, "unknown tool '${call.name}'")
            val notes = ArrayList<String>()
            val raw = try {
                json.parseToJsonElement(call.argsJson).jsonObject
            } catch (e: Exception) {
                val outcome = JsonRepair.repair(json, call.argsJson, truncated)
                val fixed = (outcome as? JsonRepair.Repaired)?.takeIf { it.element is JsonObject }
                    ?: return ParsedCalls.Invalid(call.id, "${call.name}: arguments are not a JSON object (${e.message?.lineSequence()?.first()})" +
                        JsonRepair.suffix(outcome) + markupNote(call.argsJson, null))
                notes += "arguments repaired: ${fixed.summary}"
                fixed.element.jsonObject
            }
            val args = try {
                decode(family, raw, notes, truncated)
            } catch (e: SerializationException) {
                return ParsedCalls.Invalid(call.id, "${call.name}: ${e.message?.lineSequence()?.first()}${markupNote(call.argsJson, raw)}")
            } catch (e: IllegalArgumentException) {
                return ParsedCalls.Invalid(call.id, "${call.name}: ${e.message}${markupNote(call.argsJson, raw)}")
            }
            out += ToolCall(i + 1, call.id, family, opName(family, args, raw), args, raw, notes)
        }
        return ParsedCalls.Valid(out)
    }

    /** F8: models that leak their native tool markup into the arguments get told so, not just the symptom. */
    private fun markupNote(argsJson: String, raw: JsonObject?): String {
        val carried = carriesMarkup(argsJson) ||
            listOf("patch", "ops").any { key -> (raw?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::carriesMarkup) == true }
        return if (carried) " — the arguments carry tool-call markup (<arg_key>); send the arguments as one JSON object" else ""
    }

    private fun carriesMarkup(text: String): Boolean = "<arg_key>" in text || "<arg_value>" in text

    private fun decode(family: ToolFamily, received: JsonObject, notes: MutableList<String>, truncated: Boolean): Args {
        val raw = InputTolerance.normalise(family, received, notes, truncated)
        refuseForeignDirectField(family, raw)
        return when (family) {
            ToolFamily.Look -> Args.Look(json.decodeFromJsonElement(LookArgs.serializer(), raw))
            ToolFamily.Edit -> Args.Edit(json.decodeFromJsonElement(EditArgs.serializer(), raw))
            ToolFamily.Run -> Args.Run(json.decodeFromJsonElement(RunArgs.serializer(), raw))
            ToolFamily.Verify -> Args.Verify(json.decodeFromJsonElement(VerifyArgs.serializer(), raw))
            ToolFamily.State -> Args.State(json.decodeFromJsonElement(StateArgs.serializer(), raw))
            ToolFamily.Task -> Args.Task(json.decodeFromJsonElement(TaskArgs.serializer(), raw))
            ToolFamily.Kb -> Args.Kb(json.decodeFromJsonElement(KbArgs.serializer(), raw))
        }
    }

    /**
     * A-D.3: `note` belongs to `state(note)` and `after_checks` to `task(finish)` alone. On every other op they stay the
     * unknown keys they were before the direct protocol: the first key that op's form does not know is refused, before
     * dispatch, by the decoder's own unknown-key error, so a structured call is refused as it was.
     */
    private fun refuseForeignDirectField(family: ToolFamily, raw: JsonObject) {
        val (field, owner, names) = when (family) {
            ToolFamily.State -> Triple("note", "note", StateArgs.serializer().descriptor.elementNames)
            ToolFamily.Task -> Triple("after_checks", "finish", TaskArgs.serializer().descriptor.elementNames)
            else -> return
        }
        if (field !in raw || (raw["op"] as? JsonPrimitive)?.content == owner) return
        val known = names.toSet() - field
        val first = raw.keys.first { it !in known }
        json.decodeFromJsonElement(NoFields.serializer(), JsonObject(mapOf(first to raw.getValue(first))))
    }

    /** No field at all: decoding any key into it raises the decoder's unknown-key error. */
    @Serializable
    private class NoFields

    private fun opName(family: ToolFamily, args: Args, raw: JsonObject): String = when (args) {
        is Args.Look -> args.args.what
        is Args.Edit -> if (args.args.ops.any { it.kind == "transform" }) "transform" else args.args.ops.first().kind
        is Args.Run -> args.args.op
        is Args.Verify -> args.args.what
        is Args.State -> args.args.op
        is Args.Task -> args.args.op
        is Args.Kb -> args.args.op
    }.also { require(ToolOps.name(family, it) in ToolOps.known) { "unknown ${family.wire} op '$it' in ${raw.keys}" } }

    /** Convenience for tests and fixtures: the `family.op` names present in a raw call. */
    @JvmStatic
    public fun opOf(raw: JsonObject, family: ToolFamily): String? = when (family) {
        ToolFamily.Look, ToolFamily.Verify -> raw["what"]?.jsonPrimitive?.content
        ToolFamily.Run -> raw["op"]?.jsonPrimitive?.content ?: "run"
        else -> raw["op"]?.jsonPrimitive?.content
    }
}
