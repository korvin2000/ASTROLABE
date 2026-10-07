package io.astrolabe.context

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.MessageKind
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T-50 (P8.C.17): the `[K]` slice names the objective and a requirement's status, and a revision's delta names a new objective. */
class ContractSliceTest {
    private val contract = Contract(
        workId = WorkId("W-7"), version = 1, attemptId = AttemptId("a1"), mode = Mode.Interactive, shape = Shape.S1,
        requests = listOf(
            UserRequest("U1", Instant.EPOCH, "Add the retry path to the client.", MessageKind.Request),
            UserRequest("U2", Instant.EPOCH, "keep the log format", MessageKind.Steering),
        ),
        requirements = listOf(
            Requirement("R1", "retry on 503", listOf("AC-1"), authorityRef = "U1"),
            Requirement("R2", "old backoff", listOf("AC-1"), authorityRef = "U1", status = RequirementStatus.Cancelled),
        ),
        acceptance = listOf(Acceptance.Run("AC-1", Command(listOf("pytest", "-q")), Origin.User)),
        constraints = emptyList(),
        exclusions = emptyList(),
        contractsTouched = emptyList(),
        scope = Scope(listOf("src/"), emptyList()),
        budget = Budget.of(Defaults(), Tokens(100_000)),
        authorization = Authorization(Stage.LocalCommit, DClassPolicy.Ask, "workspace-local-test-only"),
    )

    private val increment = Increment("I1", listOf("R1", "R2"), accept = listOf("AC-1"), writeScope = listOf("src/"), expectedFiles = 1)

    @Test
    fun `the slice names the objective once and a requirement's status other than pending`() {
        val k = ContractSlice.forIncrement(contract, increment).render()
        val lines = k.lines()
        assertEquals("objective: Add the retry path to the client.", lines[1])
        assertEquals(1, lines.count { it.startsWith("objective: ") })
        assertFalse("keep the log format" in k, "steering is pinned verbatim elsewhere, never the objective: $k")
        assertTrue(lines.any { it.startsWith("R1: retry on 503  accept: AC-1") && "status" !in it }, k)
        assertTrue(lines.any { it.startsWith("R2: old backoff") && it.endsWith("status: cancelled") }, k)
    }

    @Test
    fun `an amendment changes the objective and the delta names it on one line`() {
        val before = ContractSlice.forIncrement(contract, increment)
        val amended = contract.copy(version = 2, requests = contract.requests + UserRequest("U3", Instant.EPOCH, "also cover timeouts", MessageKind.Amendment))
        val after = ContractSlice.forIncrement(amended, increment)
        assertTrue(after.render().contains("objective: U1: Add the retry path to the client.\namended by U3: also cover timeouts\n"), after.render())
        val delta = after.delta(before)!!
        assertTrue(delta.contains("objective → U1: Add the retry path to the client. / amended by U3: also cover timeouts"), delta)
        assertEquals(1, delta.lines().size)
    }
}
