package io.astrolabe.recover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GuardsTest {
    private val error = "C:\\work\\repo\\src\\pay\\total.py:41 AssertionError E1234 at 0x7f3a1c2b4d10 (2026-09-25T10:00:00Z)"
    private val signature = ErrorSignature.of("run.run", error, "C:\\work\\repo")

    @Test fun `the error signature normalizes volatile data only and stays workspace-relative`() {
        assertEquals("src\\pay\\total.py:41 AssertionError E1234 at 0x… (<time>)", signature.text)
        assertNotEquals(signature, ErrorSignature.of("run.run", error.replace("E1234", "E1235"), "C:\\work\\repo"), "error codes stay")
        assertEquals(signature, ErrorSignature.of("run.run", error.replace("0x7f3a1c2b4d10", "0x55aa"), "C:\\work\\repo"))
    }

    @Test fun `FX-33 a distributed doom loop across cells counts against one global no-progress budget`() {
        val guards = Guards(GuardLimits(noProgressBudget = 3))
        val same = Fingerprint(signature, attemptedFix = "fix:d1", relevantState = "stamp:s7", requirement = "R1")
        // No cell repeats itself, so no per-cell guard can see the loop; the campaign's fingerprints do.
        assertEquals(GuardVerdict.Pass, guards.failure("cell-1", same))
        assertEquals(Guards.NO_PROGRESS, assertIs<GuardVerdict.Nudge>(guards.failure("cell-2", same)).guard)
        assertIs<GuardVerdict.Nudge>(guards.failure("cell-3", same))
        val trip = assertIs<GuardVerdict.Trip>(guards.failure("cell-4", same))
        assertEquals(Guards.NO_PROGRESS, trip.guard)
        assertTrue("cell-2" in trip.line && "cell-4" in trip.line, trip.line)
        assertEquals(3, guards.noProgressEvents.size)
        // A changed fix makes a new fingerprint but cannot erase the repeated error: after two repairs the signature gate nudges.
        val fresh = Guards()
        assertEquals(GuardVerdict.Pass, fresh.failure("cell-1", same))
        assertEquals(GuardVerdict.Pass, fresh.failure("cell-1", same.copy(attemptedFix = "fix:d2")))
        val nudge = assertIs<GuardVerdict.Nudge>(fresh.failure("cell-2", same.copy(attemptedFix = "fix:d3")))
        assertEquals(Guards.REPEATED_SIGNATURE, nudge.guard)
        assertTrue("alternative attempt" in nudge.line)
        assertTrue(fresh.noProgressEvents.isEmpty(), "different fixes are not no-progress events")
    }

    @Test fun `identical commands against changed inputs are not a loop`() {
        val guards = Guards()
        val args = """{"argv":["pytest","tests/test_total.py"]}"""
        repeat(6) { n -> assertEquals(GuardVerdict.Pass, guards.call("cell-1", "run.run", args, "stamp:s$n"), "state s$n changed") }
        repeat(6) { n ->
            assertEquals(GuardVerdict.Pass, guards.failure("cell-1", Fingerprint(signature, "fix:d1", "stamp:s$n", "R1")), "a new state is a new fingerprint")
        }
        assertEquals(GuardVerdict.Pass, guards.call("cell-1", "run.run", args, "stamp:s9"))
        assertEquals(GuardVerdict.Pass, guards.call("cell-1", "run.run", args, "stamp:s9"))
        assertEquals(Guards.DOOM_LOOP, assertIs<GuardVerdict.Trip>(guards.call("cell-1", "run.run", args, "stamp:s9")).guard)
        assertEquals(GuardVerdict.Pass, guards.call("cell-1", "run.run", args, "stamp:s10"), "a new observation resets the doom loop")
    }
}
