package io.astrolabe.cell

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Estimate
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.TokenEstimator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-327: a routed cell model takes the routed profile's estimator and output headroom. */
class CellModelTest {
    private val adapter = FakeAdapter(ScriptedModel.of())

    private class Named(override val id: String) : TokenEstimator {
        override val version: String = "1"
        override fun estimate(text: String): Estimate = Estimate(text.length.toLong(), false, id, version)
    }

    private val perProfile = EstimatorFactory { Named("est-${it.id}") }

    @Test
    fun `routing to a profile with a smaller output limit takes that limit instead of failing`() {
        val main = CellModel(adapter, FakeProfiles.main, HeuristicEstimator())
        // The pre-D-327 construction kept the main headroom and failed CellModel's own limit check.
        assertFailsWith<IllegalArgumentException> { CellModel(adapter, FakeProfiles.helper, main.estimator, Effort.Low, main.maxOutputTokens) }

        val routed = main.rebind(FakeProfiles.helper, Effort.Low, perProfile)
        assertEquals(FakeProfiles.helper.capabilities.outputLimitTokens, routed.maxOutputTokens)
        assertEquals("est-helper", routed.estimator.id)
        assertEquals(Effort.Low, routed.effort)
        assertFalse(routed.narrowedOutput)
        assertEquals(FakeProfiles.escalation.capabilities.outputLimitTokens, main.rebind(FakeProfiles.escalation, Effort.High, perProfile).maxOutputTokens)
    }

    @Test
    fun `a caller's narrowing survives routing, capped by the routed limit`() {
        val narrowed = CellModel(adapter, FakeProfiles.main, HeuristicEstimator(), maxOutputTokens = 10_000)
        assertTrue(narrowed.narrowedOutput)
        assertEquals(8_000, narrowed.rebind(FakeProfiles.helper, Effort.Medium, perProfile).maxOutputTokens)
        val wide = narrowed.rebind(FakeProfiles.escalation, Effort.Medium, perProfile)
        assertEquals(10_000, wide.maxOutputTokens)
        assertTrue(wide.narrowedOutput)
    }
}
