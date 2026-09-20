package io.astrolabe.tool

import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.event.Phase
import io.astrolabe.id.Identities
import io.astrolabe.register.Condition
import io.astrolabe.register.ConditionKind
import io.astrolabe.workset.Workset
import io.astrolabe.workset.WorksetView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * What an executor reports back: the result [body] with its envelope [header] (the cell renders both with
 * the gauge, P1.8.4) and the two facts later conditions need — whether an edit batch [applied] fully and
 * whether a run or verify came back [green]. [tokens] is what the body costs in the window and against the
 * turn's read budget.
 */
public data class ToolOutcome(
    val body: String,
    val header: EnvelopeHeader? = null,
    val applied: Boolean = false,
    val green: Boolean = false,
    val tokens: Long = 0,
) {
    init {
        require(tokens >= 0) { "tokens must be ≥ 0" }
    }

    /** The campaign-global `#n` of the result, when the executor recorded one. */
    val resultAlias: String? get() = header?.resultAlias

    /** Header line and body, for logs and host events; not the `[T]` rendering, which adds the gauge. */
    val text: String get() = listOfNotNull(header?.line(), body.takeIf { it.isNotEmpty() }).joinToString("\n")
}

/** Every accepted call id ends the turn with exactly one disposition (§5.4: a result or an explicit reason). */
public sealed interface Disposition {
    public val opId: Int

    public data class Executed(override val opId: Int, val outcome: ToolOutcome) : Disposition

    /** Never dispatched: the turn was rejected, a condition was unmet, the edit batch did not apply, or no budget was left. */
    public data class NotExecuted(override val opId: Int, val reason: String) : Disposition

    /** The executor threw: effects are unknown, and nothing downstream may assume the call applied or passed. */
    public data class Failed(override val opId: Int, val error: String) : Disposition
}

/**
 * The turn as the dispatcher fixed it before the first call ran. [coverage] is the Workset snapshot taken at
 * dispatch time: a read in the same turn never authorizes an edit of that turn (FX-50). [readBudget] is the
 * one budget every parallel read of the turn draws on (D-26).
 */
public class TurnContext(
    public val turn: Int,
    public val coverage: WorksetView,
    public val readBudget: Reservations,
)

/** One tool family's executor (P1.6.3–P1.6.10). It returns typed outcomes; a throw is a harness defect. */
public fun interface ToolExecutor {
    public suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome
}

/** Called once before the edit batch of a mutating turn: the shadow-ref checkpoint of §5.4. */
public fun interface TurnCheckpoint {
    public fun before(turn: Int)
}

/**
 * The dispositions of one turn in emitted order. The cell honours an end-turn request only after it has
 * persisted these and reconciled any handle the turn left running (§5.4).
 */
public data class TurnResult(
    val turn: Int,
    val dispositions: List<Disposition>,
    val mutating: Boolean,
    val editsApplied: Boolean,
    /** The partition's rejection, when the turn had no effects at all. */
    val rejected: String? = null,
) {
    public fun of(opId: Int): Disposition = dispositions.first { it.opId == opId }

    val executed: List<Disposition.Executed> get() = dispositions.filterIsInstance<Disposition.Executed>()
}

/**
 * Executes a partitioned turn (§5.4, TODO P1.6.2): reads in parallel under one shared budget, then the edit
 * batch behind one checkpoint, then runs and verifies — only if there was no edit batch or it applied fully,
 * and only when their conditions are met — then the metadata writes. Emits `Cell.ToolCalled` /
 * `Cell.ToolResulted` for the host. It interprets no result: executors decide `applied` and `green`.
 */
public class Dispatcher(
    private val executors: Map<ToolFamily, ToolExecutor>,
    private val workset: Workset,
    private val ids: Identities,
    private val events: Events? = null,
    private val checkpoint: TurnCheckpoint? = null,
    private val maxParallelReads: Int = 4,
    /** Reserved for a `kb` read, which declares no budget of its own. */
    private val kbReadTokens: Int = 1_500,
) {
    init {
        require(maxParallelReads > 0 && kbReadTokens > 0) { "maxParallelReads and kbReadTokens must be positive" }
    }

    public suspend fun dispatch(turn: Int, calls: List<ToolCall>, readBudget: Tokens): TurnResult {
        val partition = Partition.of(calls)
        if (partition is Partition.Rejected) {
            val reason = "turn rejected before effects: ${partition.reason}"
            return TurnResult(turn, calls.map { Disposition.NotExecuted(it.opId, reason) }, mutating = false, editsApplied = false, rejected = partition.reason)
        }
        val ordered = partition as Partition.Ordered
        val context = TurnContext(turn, workset.snapshot(), Reservations(readBudget))
        val results = HashMap<Int, Disposition>()

        // Reads: admission in emitted order against the one budget (decided before anything runs), then
        // bounded parallel execution; a reservation is reconciled to what the result actually cost.
        val admitted = LinkedHashMap<ToolCall, Reservations.Reservation>()
        for (call in ordered.reads) {
            val tokens = readTokensOf(call).toLong()
            val reservation = context.readBudget.reserve(Tokens(tokens), "op ${call.opId}")
            if (reservation == null) {
                results[call.opId] = Disposition.NotExecuted(call.opId, "read budget exhausted: $tokens tokens requested, ${context.readBudget.available.value} available")
            } else {
                admitted[call] = reservation
            }
        }
        coroutineScope {
            val permits = Semaphore(maxParallelReads)
            admitted.map { (call, reservation) ->
                async {
                    permits.withPermit { run(call, context, TurnPhase.Read) }.also { d ->
                        if (d is Disposition.Executed) reservation.reconcile(Tokens(d.outcome.tokens)) else reservation.release()
                    }
                }
            }.awaitAll().forEach { results[it.opId] = it }
        }

        // Edits: one checkpoint, one batch; a call that did not apply stops the batch.
        var editsApplied = ordered.edits.isEmpty()
        if (ordered.edits.isNotEmpty()) {
            checkpoint?.before(turn)
            var applying = true
            for (call in ordered.edits) {
                results[call.opId] = if (applying) {
                    run(call, context, TurnPhase.Edit).also { applying = it is Disposition.Executed && it.outcome.applied }
                } else {
                    Disposition.NotExecuted(call.opId, "an earlier edit call of this batch did not apply")
                }
            }
            editsApplied = applying
        }

        // Runs and verifies: sequential, after a fully applied batch or none, each behind its condition.
        for (call in ordered.executes) {
            results[call.opId] = when {
                !editsApplied -> Disposition.NotExecuted(call.opId, "the edit batch did not apply fully; runs execute only after a fully applied batch or none")
                else -> unmet(call, results)?.let { Disposition.NotExecuted(call.opId, it) } ?: run(call, context, TurnPhase.Execute)
            }
        }

        // Metadata writes: state, task and kb.propose, after execution; conditional STATE ops are the state tool's.
        for (call in ordered.metadata) results[call.opId] = run(call, context, TurnPhase.Metadata)

        return TurnResult(turn, calls.map { results.getValue(it.opId) }, ordered.mutating, editsApplied)
    }

    private suspend fun run(call: ToolCall, context: TurnContext, phase: TurnPhase): Disposition {
        val executor = executors[call.family] ?: return Disposition.NotExecuted(call.opId, "no executor registered for ${call.family.wire}")
        val eventPhase = when (phase) {
            TurnPhase.Read -> Phase.Locate
            TurnPhase.Edit -> Phase.Edit
            TurnPhase.Execute -> Phase.Verify
            TurnPhase.Metadata -> Phase.Understand
        }
        events?.emit(AgentEvent.Cell.ToolCalled(ids, call.opId, call.family.wire, call.op, eventPhase))
        val disposition = try {
            Disposition.Executed(call.opId, executor.execute(call, context))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Disposition.Failed(call.opId, "${failure::class.simpleName}: ${failure.message}")
        }
        val header = when (disposition) {
            is Disposition.Executed -> disposition.outcome.header?.line() ?: disposition.outcome.body.lineSequence().first()
            is Disposition.Failed -> "failed: ${disposition.error}"
            is Disposition.NotExecuted -> disposition.reason
        }
        events?.emit(AgentEvent.Cell.ToolResulted(ids, call.opId, (disposition as? Disposition.Executed)?.outcome?.resultAlias, header, eventPhase))
        return disposition
    }

    /** The reason a validated condition is not met now, or `null`. */
    private fun unmet(call: ToolCall, results: Map<Int, Disposition>): String? {
        val condition = call.condition?.let(Condition::parse) ?: return null
        val target = results[condition.opId]
        val satisfied = target is Disposition.Executed && when (condition.kind) {
            ConditionKind.Applied -> target.outcome.applied
            ConditionKind.Green -> target.outcome.green
        }
        if (satisfied) return null
        val state = when (target) {
            is Disposition.Executed -> if (condition.kind == ConditionKind.Applied) "op ${condition.opId} did not apply" else "op ${condition.opId} is not green"
            is Disposition.NotExecuted -> "op ${condition.opId} was not executed (${target.reason})"
            is Disposition.Failed -> "op ${condition.opId} failed"
            null -> "op ${condition.opId} has no disposition yet"
        }
        return "condition $condition not met: $state"
    }

    private fun readTokensOf(call: ToolCall): Int = when (val a = call.args) {
        is Args.Look -> a.args.budget
        else -> kbReadTokens
    }
}
