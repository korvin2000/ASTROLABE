package io.astrolabe.tool

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
) {
    val name: String get() = ToolOps.name(family, op)

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

    /** Fail closed (§5.4 error policy): no call of the turn executes; one line names the schema error. */
    public data class Invalid(val providerCallId: String, val error: String) : ParsedCalls
}

public object ToolCalls {
    private val json = Json { ignoreUnknownKeys = false }

    /** Parses every provider tool call of a turn; any unparseable or unknown call rejects the whole turn. */
    @JvmStatic
    public fun parse(calls: List<io.astrolabe.provider.ToolCall>): ParsedCalls {
        val out = ArrayList<ToolCall>()
        calls.forEachIndexed { i, call ->
            val family = ToolFamily.byWire(call.name) ?: return ParsedCalls.Invalid(call.id, "unknown tool '${call.name}'")
            val raw = try {
                json.parseToJsonElement(call.argsJson).jsonObject
            } catch (e: Exception) {
                return ParsedCalls.Invalid(call.id, "${call.name}: arguments are not a JSON object (${e.message?.lineSequence()?.first()})")
            }
            val args = try {
                decode(family, raw)
            } catch (e: SerializationException) {
                return ParsedCalls.Invalid(call.id, "${call.name}: ${e.message?.lineSequence()?.first()}")
            } catch (e: IllegalArgumentException) {
                return ParsedCalls.Invalid(call.id, "${call.name}: ${e.message}")
            }
            out += ToolCall(i + 1, call.id, family, opName(family, args, raw), args, raw)
        }
        return ParsedCalls.Valid(out)
    }

    private fun decode(family: ToolFamily, raw: JsonObject): Args = when (family) {
        ToolFamily.Look -> Args.Look(json.decodeFromJsonElement(LookArgs.serializer(), raw))
        ToolFamily.Edit -> Args.Edit(json.decodeFromJsonElement(EditArgs.serializer(), raw))
        ToolFamily.Run -> Args.Run(json.decodeFromJsonElement(RunArgs.serializer(), raw))
        ToolFamily.Verify -> Args.Verify(json.decodeFromJsonElement(VerifyArgs.serializer(), raw))
        ToolFamily.State -> Args.State(json.decodeFromJsonElement(StateArgs.serializer(), raw))
        ToolFamily.Task -> Args.Task(json.decodeFromJsonElement(TaskArgs.serializer(), raw))
        ToolFamily.Kb -> Args.Kb(json.decodeFromJsonElement(KbArgs.serializer(), raw))
    }

    private fun opName(family: ToolFamily, args: Args, raw: JsonObject): String = when (args) {
        is Args.Look -> args.args.what
        is Args.Edit -> if (args.args.ops.any { it.kind == "transform" }) "transform" else args.args.ops.first().kind
        is Args.Run -> args.args.op
        is Args.Verify -> args.args.what
        is Args.State -> args.args.op
        is Args.Task -> args.args.op
        is Args.Kb -> args.args.op
    }.also { require(it in ToolOps.of(family)) { "unknown ${family.wire} op '$it' in ${raw.keys}" } }

    /** Convenience for tests and fixtures: the `family.op` names present in a raw call. */
    @JvmStatic
    public fun opOf(raw: JsonObject, family: ToolFamily): String? = when (family) {
        ToolFamily.Look, ToolFamily.Verify -> raw["what"]?.jsonPrimitive?.content
        ToolFamily.Run -> raw["op"]?.jsonPrimitive?.content ?: "run"
        else -> raw["op"]?.jsonPrimitive?.content
    }
}
