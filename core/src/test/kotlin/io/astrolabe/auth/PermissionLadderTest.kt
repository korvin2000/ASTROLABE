package io.astrolabe.auth

import io.astrolabe.DClassPolicy
import io.astrolabe.contract.Authorization
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.10.4 — permission ladder (§14.2): separate grants, ceiling from the contract, refusals recorded. */
class PermissionLadderTest {

    @Test
    fun `patch is granted, a stage above the ceiling is refused and recorded`() {
        val ladder = PermissionLadder.of(Authorization(Stage.Patch, DClassPolicy.Ask, "workspace-local-test-only"))
        assertNull(ladder.highestAuthorizedStage, "nothing is authorized before it is asked for")

        assertEquals(StageGrant.Granted(Stage.Patch), ladder.request(Stage.Patch))
        assertEquals(Stage.Patch, ladder.highestAuthorizedStage)

        val refused = assertIs<StageGrant.Refused>(ladder.request(Stage.LocalCommit))
        assertEquals(Stage.LocalCommit, refused.stage)
        assertEquals(RefusalReason.AboveStageCeiling, refused.refusal.reason)
        assertContains(refused.refusal.detail, "authorizes up to 'patch'")
        assertEquals(listOf(refused.refusal), ladder.refusals)
        assertEquals(Stage.Patch, ladder.highestAuthorizedStage, "a refusal never raises the reached stage")

        for (stage in listOf(Stage.Push, Stage.Merge, Stage.Deploy)) {
            assertIs<StageGrant.Refused>(ladder.request(stage))
        }
        assertEquals(4, ladder.refusals.size)
        assertEquals("highest authorized stage: patch (ceiling patch, 4 refused)", ladder.report())
    }

    @Test
    fun `a stage inside the ceiling that this release cannot perform is refused, not silently granted`() {
        val ladder = PermissionLadder(ceiling = Stage.Deploy)
        assertEquals(setOf(Stage.Patch), ladder.reachable, "only patch is reachable in P1 (commit policy P5.2)")

        assertIs<StageGrant.Granted>(ladder.request(Stage.Patch))
        val refused = assertIs<StageGrant.Refused>(ladder.request(Stage.LocalCommit))
        assertEquals(RefusalReason.StageNotImplemented, refused.refusal.reason)
        assertContains(refused.refusal.detail, "P5.2")
        assertEquals(Stage.Patch, ladder.highestAuthorizedStage)

        // A later release widens `reachable`; the ceiling still bounds it.
        val future = PermissionLadder(Stage.LocalCommit, setOf(Stage.Patch, Stage.LocalCommit, Stage.Push))
        assertIs<StageGrant.Granted>(future.request(Stage.LocalCommit))
        assertEquals(Stage.LocalCommit, future.highestAuthorizedStage)
        assertEquals(RefusalReason.AboveStageCeiling, assertIs<StageGrant.Refused>(future.request(Stage.Push)).refusal.reason)
    }

    @Test
    fun `the reached stage is monotone and the report never claims delivery`() {
        val ladder = PermissionLadder(Stage.Push, setOf(Stage.Patch, Stage.LocalCommit, Stage.Push))
        assertEquals("highest authorized stage: none (ceiling push)", ladder.report())
        ladder.request(Stage.Push)
        ladder.request(Stage.Patch)
        assertEquals(Stage.Push, ladder.highestAuthorizedStage, "an earlier stage never lowers the reached one")
        ladder.record(Stage.Patch)
        assertEquals(Stage.Push, ladder.highestAuthorizedStage)
        assertEquals("highest authorized stage: push (ceiling push)", ladder.report())
        assertTrue(ladder.refusals.isEmpty())
        assertTrue(ladder.report().startsWith("highest authorized stage"))
    }

    @Test
    fun `the ceiling type refuses the same stages as the ladder`() {
        val ceiling = Ceiling(CapabilitySet.WORKSPACE_LOCAL_TEST_ONLY, Stage.Patch, ExecutionMode.TrustedLocal)
        assertNull(ceiling.allows(Stage.Patch))
        assertEquals(RefusalReason.AboveStageCeiling, ceiling.allows(Stage.LocalCommit)?.reason)
        assertEquals(RefusalReason.AboveStageCeiling, ceiling.allows(Stage.Deploy)?.reason)
        assertNull(ceiling.copy(stage = Stage.Deploy).allows(Stage.Merge))
    }
}
