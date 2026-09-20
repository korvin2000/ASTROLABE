package io.astrolabe.budget

/**
 * Token reservations over one capacity (§8.1, FX-25): [reserve] is atomic, so concurrent callers can never
 * overspend; a reservation holds until [Reservation.reconcile] or [Reservation.release]. An external call with
 * uncertain usage keeps its conservative hold until reconciled (D-51). Overruns are recorded, never hidden.
 */
public class Reservations(public val capacity: Tokens) {
    private val lock = Any()
    private var committed = 0L
    private var held = 0L
    private var overrun = 0L
    private var nextId = 1

    public val spent: Tokens get() = synchronized(lock) { Tokens(committed) }
    public val heldTokens: Tokens get() = synchronized(lock) { Tokens(held) }
    public val overrunTokens: Tokens get() = synchronized(lock) { Tokens(overrun) }
    public val available: Tokens get() = synchronized(lock) { Tokens(maxOf(0L, capacity.value - committed - held)) }

    /** Returns `null` when [amount] exceeds what is still available; nothing is consumed then. */
    public fun reserve(amount: Tokens, purpose: String): Reservation? = synchronized(lock) {
        if (amount.value > capacity.value - committed - held) return null
        held += amount.value
        Reservation(nextId++, amount, purpose)
    }

    public inner class Reservation internal constructor(
        public val id: Int,
        public val amount: Tokens,
        public val purpose: String,
    ) {
        public var state: State = State.Held
            private set

        /** Settles the hold against the actual usage; usage above the hold is recorded as overrun. */
        public fun reconcile(actual: Tokens): Unit = synchronized(lock) {
            check(state == State.Held) { "reservation $id already ${state.name.lowercase()}" }
            held -= amount.value
            committed += actual.value
            if (actual.value > amount.value) overrun += actual.value - amount.value
            state = State.Reconciled
        }

        /** Frees the hold without spend (the call never dispatched). */
        public fun release(): Unit = synchronized(lock) {
            check(state == State.Held) { "reservation $id already ${state.name.lowercase()}" }
            held -= amount.value
            state = State.Released
        }
    }

    public enum class State { Held, Reconciled, Released }
}
