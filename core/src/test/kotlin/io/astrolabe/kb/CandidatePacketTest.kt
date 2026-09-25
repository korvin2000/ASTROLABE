package io.astrolabe.kb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P4.4.6: the extractor role's NoteCandidates packet validator (D-164). */
class CandidatePacketTest {
    private fun candidate(kind: String, name: String, extra: String = "", evidence: String = "\"#12\"") =
        """{"kind":"$kind","name":"$name","summary":"s","scope":"subsystem:src/pay"$extra,"diagnosis":{"symptom":"import fails","conditions":"under pytest","attempted":"a","observed":"o","reason":"r","evidence":[$evidence],"invalidation":"pay moves"}}"""

    @Test
    fun `proposable candidates parse bound to the trace revision and harness-owned kinds are gaps`() {
        val parsed = assertIs<CandidatesParsed.Parsed>(CandidatePacket.parse("""{"candidates":[${candidate("PIT", "pay-path")},${candidate("BMAP_DELTA", "pay", ",\"supersedes\":\"BMAP-pay\"")}]}""", "abc123"))
        assertEquals(listOf("PIT-pay-path", "BMAP-pay"), parsed.candidates.map { it.id })
        assertTrue(parsed.candidates.all { it.diagnosis.sourceRevision == "abc123" }, "the revision is the trace's, never the model's")
        assertEquals("BMAP-pay", parsed.candidates[1].supersedes)
        assertIs<CandidatesParsed.Parsed>(CandidatePacket.parse("""{"candidates":[]}""", "abc123"), "nothing new is a valid answer")

        val gaps = assertIs<CandidatesParsed.Gaps>(
            CandidatePacket.parse(
                """{"candidates":[${candidate("CAL_DELTA", "repo")},${candidate("NEG", "x")},${candidate("SKILL_DELTA", "s")},${candidate("LES", "l", evidence = "")},{"kind":"FACT"}]}""",
                "abc123",
            ),
        ).gaps
        assertEquals(5, gaps.size, gaps.toString())
        assertTrue(gaps[0].contains("CAL_DELTA is derived by the harness") && gaps[1].contains("NEG is derived by the harness"), gaps.toString())
        assertTrue(gaps[2].contains("names the note it supersedes") && gaps[3].contains("cites evidence"), gaps.toString())
        assertIs<CandidatesParsed.Gaps>(CandidatePacket.parse("no json", "abc123"))
    }
}
