package io.astrolabe.recover

import io.astrolabe.campaign.PublicationAuthority
import io.astrolabe.id.ExecutionGeneration

/**
 * One classified failure as the ladder reads it (§13.1): the class, the execution state of the action, what it
 * already did, and how many retries and repairs it has consumed. [safeToRetry] is the operation's classification
 * (idempotent / read-only), never the model's opinion; [reconciled] is set once the state was reconciled.
 */
public data class Failure @JvmOverloads constructor(
    val failureClass: FailureClass,
    val state: ExecutionState,
    val operation: String,
    val detail: String,
    val evidenceRefs: List<String> = emptyList(),
    val completedEffects: List<String> = emptyList(),
    val safeToRetry: Boolean = false,
    val retries: Int = 0,
    val repairs: Int = 0,
    val reconciled: Boolean = false,
) {
    init {
        require(operation.isNotBlank()) { "a failure names its intended operation" }
        require(retries >= 0 && repairs >= 0)
    }
}

/** The ladder's bounds (§13.1: ≤ 2 deterministic retries with backoff, one scoped repair). */
public data class LadderLimits @JvmOverloads constructor(
    val retries: Int = 2,
    val repairs: Int = 1,
    val backoffBaseMillis: Long = 1_000,
) {
    init {
        require(retries in 0..2) { "at most two deterministic retries (§13.1), got $retries" }
        require(repairs in 0..1) { "at most one scoped repair per recovery (§13.1), got $repairs" }
        require(backoffBaseMillis > 0)
    }
}

/** What `recover` decided. Every step names its reason; a return carries the evidence and what it must not conceal. */
public sealed interface Recovery {
    public val failure: Failure

    /** The outcome is unknown: reconcile workspace / process / external state before any retry. */
    public data class Reconcile(override val failure: Failure) : Recovery {
        public val message: String get() = "reconcile ${failure.operation} before any retry: ${failure.failureClass.mustNotConceal ?: "outcome unknown"}"
    }

    /** A bounded deterministic retry of a classified-safe operation, [attempt] of at most two, after [backoffMillis]. */
    public data class Retry(override val failure: Failure, val attempt: Int, val backoffMillis: Long) : Recovery

    /** One scoped repair within granted capability; the capsule helper re-verifies the original intent (P4.6.3). */
    public data class Repair(override val failure: Failure) : Recovery {
        public val message: String get() = "scoped repair of ${failure.operation}: ${failure.detail} — must not conceal: ${failure.failureClass.mustNotConceal}"
    }

    /** Back to the owning handler with evidence and an explicit reason (§13.1 `else`). */
    public data class Return(override val failure: Failure, val handler: Handler, val reason: String) : Recovery {
        public val evidenceRefs: List<String> get() = failure.evidenceRefs
        public val message: String
            get() = "${failure.failureClass.name} → ${handler.name}: ${failure.failureClass.response} · $reason" +
                (failure.failureClass.mustNotConceal?.let { " · must not conceal: $it" } ?: "")
    }
}

/**
 * `recover(failure)` of §13.1 as a pure function of the failure record: reconcile an unknown outcome first, then a
 * bounded retry only for a transient failure of a classified-safe operation that has not had effects replayed,
 * then one scoped repair for a repairable class, else return to the class's handler. Nothing here dispatches.
 */
public class Ladder @JvmOverloads constructor(public val limits: LadderLimits = LadderLimits()) {
    public fun recover(failure: Failure): Recovery {
        val kind = failure.failureClass
        if ((failure.state == ExecutionState.Unknown || kind == FailureClass.UnknownActionOutcome) && !failure.reconciled) {
            return Recovery.Reconcile(failure)
        }
        // §13.1: timeouts after non-idempotent effects never auto-replay.
        if (kind.transient && failure.safeToRetry && failure.state != ExecutionState.Unknown && failure.retries < limits.retries) {
            return Recovery.Retry(failure, failure.retries + 1, limits.backoffBaseMillis shl failure.retries)
        }
        if (kind.repairable && failure.repairs < limits.repairs) return Recovery.Repair(failure)
        val reason = when {
            kind.transient && failure.retries >= limits.retries -> "retries exhausted (${failure.retries}): ${failure.detail}"
            kind.transient && !failure.safeToRetry -> "not a classified-safe operation, never replayed: ${failure.detail}"
            kind.repairable -> "repair spent (${failure.repairs}): ${failure.detail}"
            else -> failure.detail
        }
        return Recovery.Return(failure, kind.handler, reason)
    }
}

/**
 * The §13.1 fences as pure checks. Dispatch needs the current execution generation, the lease's authority and an
 * atomic budget reservation; publication rechecks generation and authority, and a refused publication still
 * persists the late observation; a workspace is granted to another writer only after the previous owner's
 * unknown effects were reconciled (a lease alone cannot fence an OS process).
 */
public object Fence {
    @JvmStatic
    public fun dispatch(held: ExecutionGeneration, current: ExecutionGeneration, authority: PublicationAuthority, reserved: Boolean): String? = when {
        held != current -> "execution generation ${held.value} superseded by ${current.value}"
        else -> authority.refusal() ?: if (!reserved) "no atomic budget reservation" else null
    }

    /** A publication decision; [persistObservation] is always true: late observations are kept even when refused. */
    public data class Publication(val refusal: String?) {
        public val allowed: Boolean get() = refusal == null
        public val persistObservation: Boolean get() = true
    }

    @JvmStatic
    public fun publish(held: ExecutionGeneration, current: ExecutionGeneration, authority: PublicationAuthority): Publication =
        Publication(if (held != current) "execution generation ${held.value} superseded by ${current.value}" else authority.refusal())

    @JvmStatic
    public fun grant(previousOwnerUnknownEffects: List<String>): String? =
        if (previousOwnerUnknownEffects.isEmpty()) null
        else "reconcile the previous owner's unknown effects first: ${previousOwnerUnknownEffects.joinToString(", ")}"
}
