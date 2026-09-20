package io.astrolabe.fixtures

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A manually advanced clock for deterministic tests (TODO P0.6.4). */
public class FakeClock(start: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    @Volatile
    private var now: Instant = start

    public fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    public fun set(instant: Instant) {
        now = instant
    }

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = FakeClock(now, zone)

    override fun instant(): Instant = now

    public companion object {
        @JvmStatic
        public fun at(iso: String): FakeClock = FakeClock(Instant.parse(iso))
    }
}
