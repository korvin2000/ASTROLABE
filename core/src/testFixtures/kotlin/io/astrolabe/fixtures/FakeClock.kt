package io.astrolabe.fixtures

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A [Clock] that only moves when a test moves it (TODO P0.6.4).
 *
 * Every policy in ASTROLABE is a pure function of its records with the clock injected (§2.3), so
 * fixtures need a clock whose readings are inputs rather than wall time. Reads and moves are
 * serialized: the supervisor threads of P0.6.1 and the drains of [Runners] observe a clock a test
 * body is advancing.
 */
public class FakeClock private constructor(
    private val ticks: Ticks,
    private val zone: ZoneId,
) : Clock() {

    public constructor(start: Instant, zone: ZoneId = ZoneOffset.UTC) : this(Ticks(start), zone)

    override fun instant(): Instant = ticks.get()

    override fun getZone(): ZoneId = zone

    /**
     * A view in another zone that **shares this clock's instant**: advancing either moves both.
     * [Clock.withZone] only promises a different zone, and a fixture wants one timeline.
     */
    override fun withZone(zone: ZoneId): FakeClock =
        if (zone == this.zone) this else FakeClock(ticks, zone)

    /** Moves the clock forward by [amount] and returns the new reading. */
    public fun advance(amount: Duration): Instant = ticks.advance(amount)

    /** Moves the clock to [instant], forward or back; a test may replay a timestamp on purpose. */
    public fun set(instant: Instant) {
        ticks.set(instant)
    }

    override fun equals(other: Any?): Boolean =
        other is FakeClock && other.ticks === ticks && other.zone == zone

    override fun hashCode(): Int = 31 * System.identityHashCode(ticks) + zone.hashCode()

    override fun toString(): String = "FakeClock[${ticks.get()}, $zone]"

    /** The shared, mutable reading behind every zone view of one clock. */
    private class Ticks(private var instant: Instant) {
        private val lock = Any()

        fun get(): Instant = synchronized(lock) { instant }

        fun advance(amount: Duration): Instant = synchronized(lock) {
            require(!amount.isNegative) { "a clock advances forward, got $amount" }
            instant = instant.plus(amount)
            instant
        }

        fun set(value: Instant) {
            synchronized(lock) { instant = value }
        }
    }

    public companion object {
        /** `FakeClock.at("2026-09-20T10:00:00Z")` — an ISO-8601 instant, UTC. */
        public fun at(instant: String, zone: ZoneId = ZoneOffset.UTC): FakeClock =
            FakeClock(Instant.parse(instant), zone)
    }
}
