package io.astrolabe.id

import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class StampTest {
    private val tracked = Digest.ofUtf8("tracked")
    private val untracked = Digest.ofUtf8("untracked")
    private val env = Digest.ofUtf8("env")

    @Test
    fun `equal candidates captured at different times have equal ids`() {
        val a = CapturedStamp(Stamp("c0ffee", tracked, untracked, env), Instant.parse("2026-01-01T00:00:00Z"))
        val b = CapturedStamp(Stamp("c0ffee", tracked, untracked, env), Instant.parse("2026-06-01T00:00:00Z"))
        assertEquals(a.stamp, b.stamp)
        assertEquals(a.stamp.id, b.stamp.id)
        assertNotEquals(a, b)
    }

    @Test
    fun `every identity component changes the id`() {
        val base = Stamp("c0ffee", tracked, untracked, env)
        assertNotEquals(base.id, Stamp("dead", tracked, untracked, env).id)
        assertNotEquals(base.id, Stamp("c0ffee", Digest.ofUtf8("x"), untracked, env).id)
        assertNotEquals(base.id, Stamp("c0ffee", tracked, Digest.ofUtf8("x"), env).id)
        assertNotEquals(base.id, Stamp("c0ffee", tracked, untracked, Digest.ofUtf8("x")).id)
    }

    @Test
    fun `id is the digest of the versioned canonical encoding`() {
        val stamp = Stamp("c0ffee", tracked, untracked, env)
        val expected = "astrolabe/stamp/v1\nbase=c0ffee\ntracked=${tracked.hex}\nuntracked=${untracked.hex}\nenv=${env.hex}\n"
        assertEquals(CandidateId(Digest.ofUtf8(expected)), stamp.id)
    }

    @Test
    fun `serialized form excludes the derived id and round trips`() {
        val stamp = Stamp("c0ffee", tracked, untracked, env)
        val text = Json.encodeToString(Stamp.serializer(), stamp)
        assertFalse(text.contains("\"id\""), text)
        assertEquals(stamp, Json.decodeFromString(Stamp.serializer(), text))
    }

    @Test
    fun `canonical encoding is injective on newlines and backslashes`() {
        val a = CanonicalEncoding.encode("m", 1, listOf("k" to "a\nb=c"))
        val b = CanonicalEncoding.encode("m", 1, listOf("k" to "a", "b" to "c"))
        assertNotEquals(a, b)
        assertEquals("astrolabe/m/v1\nk=a\\nb=c\n", a)
        assertEquals("x\\\\y\\r", CanonicalEncoding.escape("x\\y\r"))
    }
}
