package io.astrolabe.context

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellFixture
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.PartialReason
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Estimate
import io.astrolabe.provider.Role as ItemRole
import io.astrolabe.provider.Message
import io.astrolabe.provider.OpaqueContinuation
import io.astrolabe.provider.Request
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.estimate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P2.3.3: the hard admission check before every dispatch (§6.1, IX-17). */
class ContextAdmissionTest {
    @TempDir
    lateinit var stateRoot: Path

    private val estimator = HeuristicEstimator()
    private val profile = FakeProfiles.main

    private fun request(text: String, continuation: OpaqueContinuation? = null, output: Int = 1_000) =
        Request(listOf(Segment(SegmentKind.K, listOf(Message.text(ItemRole.User, text)))), emptyList(), profile, Effort.Medium, output, continuation = continuation)

    @Test
    fun `adversarial text is never exact, unknown history never fits and misses widen the margin (IX-17)`() {
        val admission = ContextAdmission()
        val adversarial = request("⟦⟧".repeat(200) + " ​".repeat(300) + "zzzzzzzzzzzzzzzzzzzzzzz".repeat(40))
        val estimate = adversarial.estimate(estimator)
        val first = assertIs<AdmissionDecision.Admitted>(admission.check(adversarial, estimate))
        assertFalse(first.record.exact, "a heuristic count is an estimate, never an exact fit")
        assertEquals(estimate.upperBoundTokens, first.record.boundTokens)

        val replay = request("continue", OpaqueContinuation("fake", JsonPrimitive("resp-1")))
        val unknown = assertIs<AdmissionDecision.Capacity>(admission.check(replay, replay.estimate(estimator)))
        assertEquals(CapacityCondition.UnknownHistory, unknown.condition)

        admission.observed(estimate, estimate.upperBoundTokens + 400)
        assertEquals(400, admission.learnedMargin, "an under-estimate widens the margin")
        admission.rejected(estimate)
        assertTrue(admission.learnedMargin > 400, "a provider rejection is an estimation miss")
        assertEquals(estimate.upperBoundTokens + admission.learnedMargin, (admission.check(adversarial, estimate) as AdmissionDecision.Admitted).record.boundTokens)
    }

    @Test
    fun `an exact count fits to the token and output headroom is part of the bound`() {
        val admission = ContextAdmission()
        val limit = profile.capabilities.contextLimitTokens.toLong()
        val exact = Estimate(limit - 1_000, exact = true, estimatorId = "tokenizer", version = "1")
        assertIs<AdmissionDecision.Admitted>(admission.check(request("x", output = 1_000), exact))
        val over = assertIs<AdmissionDecision.Capacity>(admission.check(request("x", output = 1_001), exact))
        assertEquals(CapacityCondition.OverWindow, over.condition)
    }

    @Test
    fun `a small context window stops the cell for a rebuild before any oversize request is sent`() = runBlocking<Unit> {
        CellFixture(stateRoot).use { f ->
            val tiny = FakeProfiles.main.copy(capabilities = FakeProfiles.main.capabilities.copy(contextLimitTokens = 3_000, outputLimitTokens = 1_000))
            val exit = f.run(ScriptedModel.of(Scripted.Reply(listOf(say("never sent")))), profile = tiny, profiles = FakeProfiles.all + (tiny.id to tiny))
            val partial = assertIs<CellExit.Partial>(exit)
            assertEquals(PartialReason.Pressure, partial.reason)
            assertEquals(0, f.adapter.calls.size, "no request was dispatched")
        }
    }
}
