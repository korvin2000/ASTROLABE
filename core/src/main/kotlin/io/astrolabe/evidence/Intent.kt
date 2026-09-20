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
    @Serializable(with = InstantSerializer::class) val at: Instant,
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
        require(status.ordinal >= current.status.ordinal || status == IntentStatus.Unknown) {
            "intent $intentId cannot move from ${current.status} to $status"
        }
        rows[intentId] = current.copy(status = status)
    }

    @Synchronized
    override fun open(): List<Intent> = rows.values.filter { it.open }

    @Synchronized
    override fun get(intentId: String): Intent? = rows[intentId]
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
            journal.update(intent.intentId, IntentStatus.Unknown)
            return ActionOutcome.Unknown(intent.intentId, t)
        }
        journal.update(intent.intentId, IntentStatus.Observed)
        try {
            persist(observed)
        } catch (t: Throwable) {
            journal.update(intent.intentId, IntentStatus.Unknown)
            return ActionOutcome.Unknown(intent.intentId, t)
        }
        journal.update(intent.intentId, IntentStatus.Committed)
        return ActionOutcome.Completed(observed)
    }
}
