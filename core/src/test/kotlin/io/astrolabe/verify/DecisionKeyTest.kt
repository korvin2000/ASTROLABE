package io.astrolabe.verify

import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
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
}
