package io.astrolabe.id

import io.astrolabe.fixtures.FixedIdGen
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class IdsTest {
    private val json = Json

    @Test
    fun `ids are validated at construction`() {
        assertFailsWith<IllegalArgumentException> { WorkId("") }
        assertFailsWith<IllegalArgumentException> { WorkId("W 42") }
        assertFailsWith<IllegalArgumentException> { AttemptId("a/2") }
        assertFailsWith<IllegalArgumentException> { ContextId("x".repeat(MAX_ID_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { Generation(-1) }
        assertFailsWith<IllegalArgumentException> { Digest("abc") }
        assertEquals("W-0042", WorkId("W-0042").value)
    }

    @Test
    fun `identities serialize as bare strings and round trip`() {
        val ids = Identities(
            work = WorkId("W-0042"),
            attempt = AttemptId("a2"),
            candidate = CandidateId(Digest.ofUtf8("candidate")),
            context = ContextId("cell-8"),
        )
        val text = json.encodeToString(Identities.serializer(), ids)
        assertEquals(
            """{"work":"W-0042","attempt":"a2","candidate":"${ids.candidate}","context":"cell-8"}""",
            text,
        )
        assertEquals(ids, json.decodeFromString(Identities.serializer(), text))
        assertEquals(
            """{"work":"W-1","attempt":"a1"}""",
            json.encodeToString(Identities.serializer(), Identities(WorkId("W-1"), AttemptId("a1"))),
        )
    }

    @Test
    fun `digest matches the sha256 test vector and hashes raw bytes`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Digest.ofUtf8("abc").hex,
        )
        assertEquals("ba7816bf", Digest.ofUtf8("abc").hash8)
        assertNotEquals(FileVersion.of("a\nb".toByteArray()), FileVersion.of("a\r\nb".toByteArray()))
        assertEquals(FileVersion.of(byteArrayOf(1, 2)), FileVersion.of(byteArrayOf(1, 2)))
    }

    @Test
    fun `fixed id generator is deterministic and random one is unique`() {
        val fixed = FixedIdGen()
        assertEquals("cell-1", fixed.next("cell"))
        assertEquals("cell-2", fixed.next("cell"))
        assertEquals("rcpt-1", fixed.next("rcpt"))
        val random = RandomIdGen()
        val a = random.next("W")
        val b = random.next("W")
        assertNotEquals(a, b)
        WorkId(a) // canonical
        assertEquals(22, a.length)
    }

    @Test
    fun `generations advance`() {
        assertEquals(Generation(1), Generation.INITIAL.next())
        assertEquals(ExecutionGeneration(1), ExecutionGeneration.INITIAL.next())
    }

    @Test
    fun `instant serializer uses ISO text`() {
        val captured = CapturedStamp(stamp(), Instant.parse("2026-09-20T10:00:00Z"))
        val text = json.encodeToString(CapturedStamp.serializer(), captured)
        assert(text.contains("\"at\":\"2026-09-20T10:00:00Z\"")) { text }
        assertEquals(captured, json.decodeFromString(CapturedStamp.serializer(), text))
    }

    private fun stamp() = Stamp("abc123", Digest.ofUtf8("t"), Digest.ofUtf8("u"), Digest.ofUtf8("e"))
}
