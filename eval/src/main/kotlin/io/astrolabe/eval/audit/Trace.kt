package io.astrolabe.eval.audit

import io.astrolabe.provider.SerializableBigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The provider route that served a call — the unit prices are fitted in (§10.4: one route and tier). [upstream] is `null` before A2a logged it. */
@Serializable
public data class Binding(val provider: String, val model: String, val upstream: String?) {
    override fun toString(): String = "$provider/$model" + (upstream?.let { " @ $it" } ?: "")
}

/** A call's tokens by billing class; a class the response did not report is `null`, never 0. [reasoning] is part of [output]. */
@Serializable
public data class CallUsage(val uncachedInput: Long?, val cacheRead: Long?, val cacheWrite: Long?, val output: Long?, val reasoning: Long?) {
    /** Uncached + cache read + cache write; `null` when uncached or cache read is unknown (an unreported write class is none). */
    val input: Long? get() = if (uncachedInput == null || cacheRead == null) null else uncachedInput + cacheRead + (cacheWrite ?: 0)
}

/**
 * One dispatched model call, from its `cell.model_requested` and `cell.model_responded`. A request with no response
 * keeps [usage] `null` and [failure] `no response`. [billed] is the provider's reported charge (`usage.billed`; before
 * A2a OpenRouter's native `usage.cost`, the field A2a maps), never a computed price.
 */
@Serializable
public data class ModelCall(
    val index: Int,
    val cell: String,
    val turn: Int,
    val profileId: String?,
    val estimatedTokens: Long?,
    val anchorTokens: Long?,
    val binding: Binding?,
    val stop: String?,
    val failure: String?,
    val usage: CallUsage?,
    val billed: SerializableBigDecimal?,
    val billedCurrency: String?,
    val billedUpstream: SerializableBigDecimal?,
    val latencyMillis: Long?,
    val firstOutputMillis: Long?,
)

/** A tool result as its envelope header states it; [command] is the `run` command line where the journal kept the call's arguments. */
@Serializable
public data class ToolOutcome(val tool: String, val status: String?, val paths: List<String>, val command: String? = null, val op: String? = null)

/**
 * What one turn of a cell did around its model call. [results] counts the results the bus reported; [outcomes] are the
 * results with their commands where the journal links them (Studio), else the bus headers. [eviction] is the trigger of
 * a batch the journal logged at the end of the turn; [editArgsChars], [visibleChars] and [created] (characters of each
 * `create` body by path) exist only where the journal kept the model's output (`journal.call`).
 */
@Serializable
public data class TurnActivity(
    val cell: String,
    val turn: Int,
    val ops: List<String>,
    val results: Int,
    val outcomes: List<ToolOutcome>,
    val gates: List<String>,
    val worksetDropped: List<String>,
    val eviction: String?,
    val rebuilt: Boolean,
    val editArgsChars: Long?,
    val visibleChars: Long?,
    val created: Map<String, Long>,
)

/** A run's model calls in request order and the activity of every turn, keyed `cell` then `turn`. */
public class RunTrace(calls: List<ModelCall>, activity: List<TurnActivity>, public val gateTexts: List<Pair<String, String>>) {
    public val calls: List<ModelCall> = calls.toList()
    public val activity: List<TurnActivity> = activity.toList()
    private val byTurn = activity.associateBy { it.cell to it.turn }

    public fun at(cell: String, turn: Int): TurnActivity? = byTurn[cell to turn]

    /** `run` commands the model used as checks: one that came out `passed`, or went both red and green (its `completed` is green). */
    public val checkCommands: Set<String> = activity.flatMap { it.outcomes }.filter { it.tool == "run" && it.command != null && (it.op == null || it.op == "run") }
        .groupBy { it.command!! }
        .filterValues { runs -> runs.any { it.status == "passed" } || (runs.any { it.status == "failed" } && runs.any { it.status == "completed" }) }.keys

    /**
     * The check a result is a verdict of, and whether it is green: a `verify` verdict, a `run` test verdict
     * (`passed`/`failed`), or a run of a [checkCommands] command. Without command lines every `run` verdict is one check.
     */
    public fun verdict(o: ToolOutcome): Pair<String, Boolean>? = when {
        o.op != null && o.op != "run" -> null
        o.tool == "verify" && o.status in VERDICTS -> "verify" to (o.status == "passed")
        o.tool == "run" && o.command != null && o.command in checkCommands && o.status in RUN_VERDICTS -> "run `${o.command}`" to (o.status != "failed")
        o.tool == "run" && o.command == null && o.status in VERDICTS -> "run tests" to (o.status == "passed")
        else -> null
    }

    public companion object {
        private val HEADER = Regex("""⟦result (\S+) tool=(\w+)([^⟧]*)⟧""")
        private val EVICTION = Regex("""^eviction (\w+) at turn (\d+):""")
        private val CALL_RESULT = Regex("""^call (\S+): (⟦.*)""", RegexOption.DOT_MATCHES_ALL)
        private val lenient = Json { ignoreUnknownKeys = true }
        private val VERDICTS = setOf("passed", "failed")
        private val RUN_VERDICTS = setOf("passed", "failed", "completed")

        @JvmStatic
        public fun of(journal: Journal): RunTrace {
            val turnOf = HashMap<String, Int>()
            val requests = LinkedHashMap<String, Pair<JournalEvent, Int>>()
            val responses = HashMap<String, JournalEvent>()
            val acts = LinkedHashMap<Pair<String, Int>, Builder>()
            val gateTexts = ArrayList<Pair<String, String>>()
            fun act(cell: String, turn: Int) = acts.getOrPut(cell to turn) { Builder(cell, turn) }
            for (e in journal.events) {
                val cell = e.cell
                when (e.kind) {
                    "cell.turn_started" -> if (cell != null) e.data.int("turn")?.let { turnOf[cell] = it; act(cell, it) }
                    "cell.model_requested" -> e.data.str("invocationId")?.let { requests[it] = e to (cell?.let(turnOf::get) ?: 0) }
                    "cell.model_responded" -> e.data.str("invocationId")?.let { responses[it] = e }
                    "cell.tool_called" -> if (cell != null) act(cell, turnOf[cell] ?: 0).ops += "${e.data.str("family")}.${e.data.str("op")}"
                    "cell.tool_resulted" -> if (cell != null) act(cell, turnOf[cell] ?: 0).also { a ->
                        a.results++
                        header(e.data.str("header"))?.let { a.busOutcomes += it }
                    }
                    "cell.gate_fired" -> if (cell != null) {
                        val gate = e.data.str("gate") ?: "?"
                        act(cell, turnOf[cell] ?: 0).gates += gate
                        gateTexts += gate to (e.data.str("text") ?: "")
                    }
                    "cell.workset_changed" -> if (cell != null) act(cell, turnOf[cell] ?: 0).dropped +=
                        e.data.array("dropped").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                    "cell.rebuilt" -> if (cell != null) act(cell, turnOf[cell] ?: 0).rebuilt = true
                    "journal.boundary" -> if (cell != null) EVICTION.find(e.data.str("text") ?: "")?.let { m ->
                        act(cell, m.groupValues[2].toInt()).eviction = m.groupValues[1]
                    }
                    "journal.call" -> if (cell != null) act(cell, e.turn ?: turnOf[cell] ?: 0).call(e.data)
                    "journal.result" -> if (cell != null) CALL_RESULT.find(e.data.str("text") ?: "")?.let { m ->
                        val a = act(cell, e.turn ?: turnOf[cell] ?: 0)
                        header(m.groupValues[2])?.let { a.journalOutcomes += a.withCommand(m.groupValues[1], it) }
                    }
                }
            }
            val calls = requests.entries.mapIndexed { index, (id, request) -> call(index, request.first, request.second, responses[id]) }
            return RunTrace(calls, acts.values.map { it.build() }, gateTexts)
        }

        private fun call(index: Int, request: JournalEvent, turn: Int, response: JournalEvent?): ModelCall {
            val r = response?.data
            val usage = r?.obj("usage")
            val provenance = usage?.obj("provenance")
            val facts = r?.obj("facts")
            val provider = provenance?.str("provider")
            val native = usage?.obj("native")
            val reported = usage?.obj("billed")
            val openRouter = provider == "openrouter"
            val billed = reported?.decimal("amount") ?: native?.decimal("cost")?.takeIf { openRouter }
            val upstreamBill = usage?.obj("billedUpstream")?.decimal("amount")
                ?: native?.obj("cost_details")?.decimal("upstream_inference_cost")?.takeIf { openRouter }
            return ModelCall(
                index = index,
                cell = request.cell ?: "",
                turn = turn,
                profileId = request.data.str("profileId"),
                estimatedTokens = request.data.long("estimatedTokens"),
                anchorTokens = request.data.long("anchorTokens"),
                binding = if (provider == null) null else Binding(provider, provenance.str("model") ?: "?", facts?.str("upstream")),
                stop = r?.str("stop"),
                failure = if (response == null) "no response" else r?.str("failure"),
                usage = usage?.let(::usageOf),
                billed = billed,
                billedCurrency = if (billed == null) null else reported?.str("currency") ?: "USD",
                billedUpstream = upstreamBill,
                latencyMillis = facts?.long("latencyMillis"),
                firstOutputMillis = facts?.long("firstOutputMillis"),
            )
        }

        /** The normalized usage of `BillableUsage`; reasoning from `reasoningTokens`, else the OpenAI-shaped native count. */
        private fun usageOf(usage: JsonObject): CallUsage {
            val quantities = usage.obj("quantities") ?: JsonObject(emptyMap())
            val unknown = usage.array("unknown").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
            fun dimension(id: String): Long? = if (id in unknown) null else quantities.long(id)
            val writes = quantities.keys.filter { it.startsWith("cache_write") }
            val write = when {
                unknown.any { it.startsWith("cache_write") } -> null
                writes.isEmpty() -> null
                else -> writes.sumOf { quantities.long(it) ?: 0 }
            }
            val reasoning = usage.long("reasoningTokens")
                ?: (usage.obj("native").path("completion_tokens_details", "reasoning_tokens") as? JsonPrimitive)?.content?.toLongOrNull()
            return CallUsage(dimension("uncached_input"), dimension("cache_read"), write, dimension("output"), reasoning)
        }

        private fun header(text: String?): ToolOutcome? {
            val m = HEADER.find(text ?: return null) ?: return null
            val rest = m.groupValues[3]
            val status = Regex("""status=(\w+)""").find(rest)?.groupValues?.get(1)
            val versions = Regex("""v=\{([^}]*)}""").find(rest)?.groupValues?.get(1)
            val paths = versions?.split(", ")?.mapNotNull { it.substringBeforeLast(": ", "").takeIf(String::isNotEmpty) }.orEmpty()
            return ToolOutcome(m.groupValues[2], status, paths)
        }

        /** The command line of a `run` call: `argv` joined, else `cmd`, prefixed by its `cwd`; a `poll`/`wait` names its op instead. */
        internal fun command(args: JsonObject): Pair<String?, String?> {
            val op = args.str("op")
            if (op != null && op != "run") return null to op
            val argv = args.array("argv")?.mapNotNull { (it as? JsonPrimitive)?.content }
            val line = argv?.joinToString(" ") ?: args.str("cmd") ?: return null to op
            return (args.str("cwd")?.takeIf { it.isNotEmpty() && it != "." }?.let { "$it> $line" } ?: line) to op
        }

        private class Builder(val cell: String, val turn: Int) {
            val ops = ArrayList<String>()
            var results = 0
            val busOutcomes = ArrayList<ToolOutcome>()
            val journalOutcomes = ArrayList<ToolOutcome>()
            val gates = ArrayList<String>()
            val dropped = ArrayList<String>()
            var eviction: String? = null
            var rebuilt = false
            var editArgs: Long? = null
            var visible: Long? = null
            val created = LinkedHashMap<String, Long>()
            val args = HashMap<String, Pair<String, JsonObject?>>()

            fun call(data: JsonObject) {
                var edits = editArgs ?: 0
                var shown = visible ?: 0
                for (item in data.array("payload").orEmpty()) {
                    val part = item as? JsonObject ?: continue
                    when (part.str("type")) {
                        "tool_call" -> {
                            val raw = part.str("argsJson") ?: ""
                            val name = part.str("name") ?: ""
                            shown += raw.length
                            val parsed = runCatching { lenient.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                            part.str("id")?.let { args[it] = name to parsed }
                            if (name == "edit") {
                                edits += raw.length
                                for (op in parsed?.array("ops").orEmpty()) {
                                    val o = op as? JsonObject ?: continue
                                    o.str("create")?.let { path -> created.merge(path, o.toString().length.toLong(), Long::plus) }
                                }
                            }
                        }
                        "message" -> shown += part.array("parts").orEmpty().sumOf { (it as? JsonObject)?.str("text")?.length ?: 0 }
                    }
                }
                editArgs = edits
                visible = shown
            }

            fun withCommand(callId: String, outcome: ToolOutcome): ToolOutcome {
                val (name, parsed) = args[callId] ?: return outcome
                if (name != "run" || parsed == null) return outcome
                val (line, op) = command(parsed)
                return outcome.copy(command = line, op = op ?: "run")
            }

            fun build() = TurnActivity(
                cell, turn, ops.toList(), results, (journalOutcomes.ifEmpty { busOutcomes }).toList(), gates.toList(), dropped.toList(),
                eviction, rebuilt, editArgs, visible, created.toMap(),
            )
        }
    }
}
