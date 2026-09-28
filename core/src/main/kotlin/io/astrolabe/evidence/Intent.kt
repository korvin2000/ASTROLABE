package io.astrolabe.evidence

import io.astrolabe.id.Identities
import io.astrolabe.id.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/** Intent lifecycle (§4.3): recorded before any D-class or externally visible action. */
@Serializable
public enum class IntentStatus { Recorded, Dispatched, Running, Observed, Committed, Unknown }

@Serializable
public data class Intent(
    val intentId: String,
    val ids: Identities,
    val actionId: String,
    val argv: List<String>,
    val cwd: String?,
    val expectedEffect: String,
    val idempotencyKey: String? = null,
    val status: IntentStatus = IntentStatus.Recorded,
    /**
     * A read-only action whose argv predicts its effects: once the workspace is reconciled it may run again.
     * Every other unknown outcome blocks a relaunch of the same command until it is reconciled (§13.1, D-65).
     */
    val replaySafe: Boolean = false,
    @Serializable(with = InstantSerializer::class) val at: Instant,
    /** Durable authority/evidence reference explaining the disposition of an unknown outcome. */
    val reconciliation: String? = null,
    /**
     * A foreground action whose classified effects (argv-predicted, no D-class, network, install, git-ref,
     * privilege or outside-workspace capability) stay inside the workspace, so a stamp of the tree observes them
     * all. Legacy rows default to `false` and stay host-reconciled (D-321).
     */
    val workspaceConfined: Boolean = false,
) {
    init {
        require(intentId.isNotBlank() && actionId.isNotBlank()) { "intent needs ids" }
    }

    /** Open intents at startup are unknown outcomes until reconciled (invariant 6). */
    val open: Boolean get() = status != IntentStatus.Committed
}

/** Persistence seam for intents; the SQLite implementation lives in the store package. */
public interface IntentJournal {
    public fun record(intent: Intent)

    public fun update(intentId: String, status: IntentStatus)

    /** Close an unknown outcome only after the host has established and recorded its disposition. */
    public fun reconcile(intentId: String, evidence: String): Unit =
        throw UnsupportedOperationException("this journal cannot persist reconciliation")

    /** Intents that never reached `committed`, in recording order. */
    public fun open(): List<Intent>

    public fun get(intentId: String): Intent?
}

public class InMemoryIntentJournal : IntentJournal {
    private val rows = LinkedHashMap<String, Intent>()

    @Synchronized
    override fun record(intent: Intent) {
        require(intent.intentId !in rows) { "intent ${intent.intentId} already recorded" }
        rows[intent.intentId] = intent
    }

    @Synchronized
    override fun update(intentId: String, status: IntentStatus) {
        val current = rows[intentId] ?: throw IllegalArgumentException("unknown intent $intentId")
        require(intentTransition(current.status, status)) {
            "intent $intentId cannot move from ${current.status} to $status"
        }
        rows[intentId] = current.copy(status = status)
    }

    @Synchronized
    override fun reconcile(intentId: String, evidence: String) {
        val current = rows[intentId] ?: throw IllegalArgumentException("unknown intent $intentId")
        rows[intentId] = reconciledIntent(current, evidence)
    }

    @Synchronized
    override fun open(): List<Intent> = rows.values.filter { it.open }

    @Synchronized
    override fun get(intentId: String): Intent? = rows[intentId]
}

internal fun intentTransition(from: IntentStatus, to: IntentStatus): Boolean = to == from || when (from) {
    IntentStatus.Recorded -> to in setOf(IntentStatus.Dispatched, IntentStatus.Unknown)
    IntentStatus.Dispatched -> to in setOf(IntentStatus.Running, IntentStatus.Observed, IntentStatus.Committed, IntentStatus.Unknown)
    IntentStatus.Running -> to in setOf(IntentStatus.Observed, IntentStatus.Committed, IntentStatus.Unknown)
    IntentStatus.Observed -> to in setOf(IntentStatus.Committed, IntentStatus.Unknown)
    IntentStatus.Committed, IntentStatus.Unknown -> false
}

internal fun reconciledIntent(current: Intent, evidence: String): Intent {
    require(evidence.isNotBlank()) { "reconciliation needs an authority or evidence reference" }
    if (current.status == IntentStatus.Committed && current.reconciliation == evidence) return current
    require(current.status == IntentStatus.Unknown) { "only unknown intents can be reconciled" }
    return current.copy(status = IntentStatus.Committed, reconciliation = evidence)
}

/** Outcome of a consequential action as observed by [Consequential.run]. */
public sealed interface ActionOutcome<out T> {
    public data class Completed<T>(val value: T) : ActionOutcome<T>

    /** Dispatch or observation failed in a way that leaves the effect unknown: reconcile before any retry. */
    public data class Unknown(val intentId: String, val cause: Throwable?) : ActionOutcome<Nothing>

    /** Never dispatched (reservation refused or preflight failed); safe to retry. */
    public data class NotDispatched(val reason: String) : ActionOutcome<Nothing>
}

/**
 * The consequential-action ordering of §4.3 (invariant 6, TODO P1.4.3):
 * reserve → record intent → dispatch → observe → persist → commit. A crash between any two steps leaves
 * a classifiable intent status; nothing is retried while an intent is open.
 */
public object Consequential {
    public suspend fun <T> run(
        journal: IntentJournal,
        intent: Intent,
        reserve: () -> Boolean,
        dispatch: suspend () -> T,
        persist: suspend (T) -> Unit,
    ): ActionOutcome<T> {
        if (!reserve()) return ActionOutcome.NotDispatched("budget reservation refused")
        journal.record(intent)
        journal.update(intent.intentId, IntentStatus.Dispatched)
        val observed = try {
            dispatch()
        } catch (t: Throwable) {
            markUnknown(journal, intent.intentId, t)
            return ActionOutcome.Unknown(intent.intentId, t)
        }
        journal.update(intent.intentId, IntentStatus.Observed)
        try {
            persist(observed)
        } catch (t: Throwable) {
            markUnknown(journal, intent.intentId, t)
            return ActionOutcome.Unknown(intent.intentId, t)
        }
        journal.update(intent.intentId, IntentStatus.Committed)
        return ActionOutcome.Completed(observed)
    }

    private fun markUnknown(journal: IntentJournal, intentId: String, failure: Throwable) {
        if (failure is kotlinx.coroutines.CancellationException) {
            runCatching { journal.update(intentId, IntentStatus.Unknown) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        journal.update(intentId, IntentStatus.Unknown)
    }
}
