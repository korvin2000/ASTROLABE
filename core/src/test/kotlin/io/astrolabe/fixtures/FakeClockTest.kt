package io.astrolabe.fixtures

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FakeClockTest {

    @Test
    fun `at parses an iso instant and does not move on its own`() {
        val clock = FakeClock.at("2026-09-20T10:00:00Z")
        assertEquals(Instant.parse("2026-09-20T10:00:00Z"), clock.instant())
        assertEquals(clock.instant(), clock.instant())
        assertEquals(ZoneOffset.UTC, clock.zone)
        assertEquals(clock.instant().toEpochMilli(), clock.millis())
    }

    @Test
    fun `advance moves forward deterministically`() {
        val clock = FakeClock.at("2026-09-20T10:00:00Z")
        assertEquals(Instant.parse("2026-09-20T10:00:30Z"), clock.advance(Duration.ofSeconds(30)))
        assertEquals(Instant.parse("2026-09-20T11:00:30Z"), clock.advance(Duration.ofHours(1)))
        assertEquals(Instant.parse("2026-09-20T11:00:30Z"), clock.instant())
    }

    @Test
    fun `advance refuses to go backwards but set may replay a timestamp`() {
        val clock = FakeClock.at("2026-09-20T10:00:00Z")
        assertFailsWith<IllegalArgumentException> { clock.advance(Duration.ofSeconds(-1)) }
        assertEquals(Instant.parse("2026-09-20T10:00:00Z"), clock.instant())

        clock.set(Instant.parse("2020-01-01T00:00:00Z"))
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), clock.instant())
    }

    @Test
    fun `withZone keeps one timeline`() {
        val utc = FakeClock.at("2026-09-20T10:00:00Z")
        val berlin = utc.withZone(ZoneId.of("Europe/Berlin"))
        assertEquals(ZoneId.of("Europe/Berlin"), berlin.zone)
        assertSame(utc, utc.withZone(ZoneOffset.UTC))

        utc.advance(Duration.ofMinutes(5))
        assertEquals(utc.instant(), berlin.instant())
        assertEquals("2026-09-20T12:05", berlin.instant().atZone(berlin.zone).toLocalDateTime().toString())
    }

    @Test
    fun `concurrent advances all land`() {
        val clock = FakeClock.at("2026-09-20T10:00:00Z")
        val threads = 8
        val perThread = 500
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                start.await()
                repeat(perThread) {
                    clock.advance(Duration.ofMillis(1))
                    clock.instant()
                }
                done.countDown()
            }.apply { isDaemon = true }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "advancing threads did not finish")
        assertEquals(
            Instant.parse("2026-09-20T10:00:00Z").plusMillis((threads * perThread).toLong()),
            clock.instant(),
        )
    }
}
