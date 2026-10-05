package io.astrolabe.verify

import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** WD-10 (W1): the key of an acceptance decision request. */
class DecisionKeyTest {
    private val candidate = CandidateId(Digest.ofUtf8("tree"))

    @Test
    fun `two obligations never key like one obligation holding the separator`() {
        assertNotEquals(DecisionKey.of(null, candidate, 1, listOf("a", "b")), DecisionKey.of(null, candidate, 1, listOf("a\nb")))
        assertNotEquals(DecisionKey.of(null, candidate, 1, listOf("1:a", "b")), DecisionKey.of(null, candidate, 1, listOf("1:a1:b")))
    }

    @Test
    fun `the key ignores the order of the obligations and binds the scope, candidate and revision`() {
        val key = DecisionKey.of(null, candidate, 1, listOf("AC-1", "campaign:full-suite"))
        assertEquals(key, DecisionKey.of(null, candidate, 1, listOf("campaign:full-suite", "AC-1", "AC-1")))
        assertNotEquals(key, DecisionKey.of("campaign", candidate, 1, listOf("AC-1", "campaign:full-suite")))
        assertNotEquals(key, DecisionKey.of(null, CandidateId(Digest.ofUtf8("other")), 1, listOf("AC-1", "campaign:full-suite")))
        assertNotEquals(key, DecisionKey.of(null, candidate, 2, listOf("AC-1", "campaign:full-suite")))
    }

    @Test
    fun `the key binds the pinned inputs outside identity at their bytes, and the request carries them`() {
        val input = "build/input.json"
        val v1 = FileVersion.of("1\n".toByteArray())
        val v2 = FileVersion.of("2\n".toByteArray())
        val plain = DecisionKey.of(null, candidate, 1, listOf("AC-1"))
        assertEquals(plain, DecisionKey.of(null, candidate, 1, listOf("AC-1"), emptyMap()))
        val pinned = DecisionKey.of(null, candidate, 1, listOf("AC-1"), mapOf(input to v1))
        assertNotEquals(plain, pinned)
        assertNotEquals(pinned, DecisionKey.of(null, candidate, 1, listOf("AC-1"), mapOf(input to v2)))
        assertNotEquals(pinned, DecisionKey.of(null, candidate, 1, listOf("AC-1"), mapOf("build/other.json" to v1)))
        val item = DecisionItem("AC-1", ObligationKind.Run, ResultStatus.Unverified, "stale")
        val request = AcceptanceDecisionRequest("ask-1", 1, Identities(WorkId("W-1"), AttemptId("a1")), null, candidate, StopCode.AcceptanceDecision, listOf(item), outsideInputs = mapOf(input to v1))
        assertEquals(pinned, request.key)
    }
}
