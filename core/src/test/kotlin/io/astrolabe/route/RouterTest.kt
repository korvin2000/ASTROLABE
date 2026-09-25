package io.astrolabe.route

import io.astrolabe.Config
import io.astrolabe.atlas.RiskFloorInput
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Risk
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Money
import io.astrolabe.provider.StratumOutcome
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterTest {
    private val table = TierTable("t1", null, mapOf(
        Tier.Low to setOf("helper"), Tier.High to setOf("main"), Tier.ExtraHigh to setOf("escalation"),
    ))
    private val candidates = FakeProfiles.all.filterKeys { it in setOf("helper", "main", "escalation") }
    private fun packet(risk: Risk? = null, context: Long = 10_000, output: Int = 4_000, suggestion: Tier? = null, previous: Tier? = null, feature: String? = null) =
        RoutingPacket(risk, context, output, suggestion, previous, feature)
    private fun policy(budget: RoutingBudget = RoutingBudget(), tiers: TierTable = table, attempts: Map<String, AttemptPolicy> = emptyMap(), pins: Set<String> = emptySet(), floor: BigDecimal? = null) =
        RoutingPolicy(tiers, candidates, budget, attemptPolicies = attempts, pins = pins, qualityFloor = floor)
    private fun usd(amount: String) = Money("USD", BigDecimal(amount))

    @Test fun `D-34 declared risk maps explicitly and impact supplements without erasing it`() {
        assertEquals(Tier.Low, Router.riskFloor(null, null))
        assertEquals(Tier.Medium, Router.riskFloor(Risk(2, Reversibility.Easy, false), null))
        assertEquals(Tier.High, Router.riskFloor(Risk(3, Reversibility.Easy, false), null))
        assertEquals(Tier.High, Router.riskFloor(Risk(1, Reversibility.Hard, false), null))
        assertEquals(Tier.High, Router.riskFloor(Risk(1, Reversibility.Easy, true), null))
        assertEquals(Tier.Medium, Router.riskFloor(null, RiskFloorInput(0, true, 5, true)))
        assertEquals(Tier.High, Router.riskFloor(null, RiskFloorInput(0, true, 20, true)))
        assertEquals(Tier.High, Router.riskFloor(null, RiskFloorInput(1, false, null, false)))
        // An incomplete impact that found nothing never lowers a declared high-risk obligation.
        assertEquals(Tier.High, Router.riskFloor(Risk(3, Reversibility.Easy, false), RiskFloorInput(0, false, null, false)))
    }

    @Test fun `tier is the max of function default plan suggestion and risk floor`() {
        val router = Router()
        val probe = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Probe, packet(), null, policy()))
        assertEquals(Tier.Medium, probe.tier)
        assertEquals("main", probe.profile.id, "nothing serves Medium below main; a tier is a floor")
        assertEquals(Effort.Medium, probe.effort)
        val suggested = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Probe, packet(suggestion = Tier.ExtraHigh), null, policy()))
        assertEquals(Tier.ExtraHigh, suggested.tier)
        assertEquals("escalation", suggested.profile.id)
        val risky = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Curation, packet(Risk(3, Reversibility.Easy, false)), null, policy()))
        assertEquals(RoutingTrace(Tier.High, Tier.High, Tier.High, Tier.High), risky.trace)
        val continued = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Continuation, packet(previous = Tier.ExtraHigh), null, policy()))
        assertEquals(Tier.ExtraHigh, continued.tier, "a continuation never drops below the failing cell's tier")
    }

    @Test fun `FX-55 floors are re-applied after calibration which only ever promotes`() {
        val router = Router()
        // D-35: 20 verified quadruples of the feature class at Low with > 30 % verified failure promote Low → Medium.
        repeat(20) { router.calibration.append(CalibrationEntry(RoutingFunction.Curation, Tier.Low, Effort.Low, if (it < 7) RoutingOutcome.VerifiedFailure else RoutingOutcome.Accepted, "f")) }
        val promoted = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Curation, packet(feature = "f"), null, policy()))
        assertEquals(RoutingTrace(Tier.Low, Tier.Low, Tier.Medium, Tier.Medium), promoted.trace)
        val other = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Curation, packet(feature = "g"), null, policy()))
        assertEquals(Tier.Low, other.tier, "another feature class has no evidence")
        // Six failures of twenty is 30 %, not more: no promotion.
        val flat = Router()
        repeat(20) { flat.calibration.append(CalibrationEntry(RoutingFunction.Curation, Tier.Low, Effort.Low, if (it < 6) RoutingOutcome.VerifiedFailure else RoutingOutcome.Accepted, "f")) }
        assertEquals(Tier.Low, assertIs<Routed.Selected>(flat.selectProfile(RoutingFunction.Curation, packet(feature = "f"), null, policy())).tier)
        // never_below(function): a plan suggestion of Low cannot take the plan cell under High.
        val plan = assertIs<Routed.Selected>(Router().selectProfile(RoutingFunction.Plan, packet(suggestion = Tier.Low), null, policy()))
        assertEquals(Tier.High, plan.tier)
        assertEquals(Tier.High, plan.trace.final)
        // The risk floor stands after calibration too: nothing in the trace is below it.
        val risky = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Curation, packet(Risk(2, Reversibility.Easy, false), feature = "f"), RiskFloorInput(0, true, 20, true), policy()))
        assertTrue(risky.trace.calibrated >= risky.trace.riskFloor && risky.trace.final >= risky.trace.riskFloor)
        assertEquals(Tier.High, risky.tier)
    }

    @Test fun `IX-23 high declared risk with zero discovered fan-in keeps its floor and fifty high-tier successes never demote`() {
        val high = Risk(1, Reversibility.Hard, false)
        val nothingFound = RiskFloorInput(0, true, 0, true)
        assertEquals(Tier.High, Router.riskFloor(high, nothingFound), "a complete impact that found no fan-in never lowers a declared floor")
        val router = Router()
        repeat(50) { router.calibration.append(CalibrationEntry(RoutingFunction.Curation, Tier.High, Effort.Medium, RoutingOutcome.Accepted, "f", "main")) }
        val risky = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Curation, packet(high, feature = "f"), nothingFound, policy()))
        assertEquals(RoutingTrace(Tier.High, Tier.High, Tier.High, Tier.High), risky.trace)
        repeat(50) { router.calibration.append(CalibrationEntry(RoutingFunction.Implementing, Tier.High, Effort.Medium, RoutingOutcome.Accepted, "f", "main")) }
        val implementing = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Implementing, packet(feature = "f"), null, policy()))
        assertEquals(Tier.High, implementing.tier, "successes at High are no calibration evidence for the uncalibrated Low tier")
        assertEquals(implementing.trace.requested, implementing.trace.calibrated, "calibration only ever promotes (D-35)")
    }

    @Test fun `FX-32 an unaffordable tier is refused with the function's options never clamped to a cheaper tier`() {
        val router = Router()
        val budget = RoutingBudget(remainingTokens = Tokens(12_000), reservedTokens = Tokens(2_000))
        val refused = assertIs<Routed.Refused>(router.selectProfile(RoutingFunction.Implementing, packet(context = 10_000, output = 4_000), null, policy(budget)))
        assertEquals(Tier.High, refused.tier)
        assertEquals(listOf(Refusal.NarrowUnit, Refusal.Checkpoint), refused.options)
        assertTrue("main needs 14000 tokens; 10000 remain after reserves" in refused.reason, refused.reason)
        assertTrue("escalation needs 14000 tokens" in refused.reason, refused.reason)
        assertEquals("does not serve tier High", refused.excluded["helper"], "the affordable Low profile is never offered")
        assertEquals(listOf(CalibrationEntry(RoutingFunction.Implementing, Tier.High, Effort.Medium, RoutingOutcome.Refused)), router.calibration.entries())
        // A monetary cap refuses the same way; an unpriced profile is unaffordable under it.
        val priced = RoutingBudget(remainingCost = usd("0.05"), reservedCost = usd("0.01"))
        val costly = assertIs<Routed.Refused>(router.selectProfile(RoutingFunction.Implementing, packet(), null, policy(priced)))
        assertTrue(costly.excluded.getValue("main").startsWith("costs 0.09"), costly.excluded.getValue("main"))
        val plan = assertIs<Routed.Refused>(Router().selectProfile(RoutingFunction.Plan, packet(), null, policy(priced)))
        assertEquals(listOf(Refusal.NarrowUnit, Refusal.AskForChangedConstraint), plan.options)
    }

    @Test fun `argmin expected total cost uses supplied attempt policies over the conservative estimate`() {
        val router = Router()
        val wide = TierTable("t2", null, mapOf(Tier.High to setOf("main", "escalation")))
        val cheap = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Implementing, packet(), null, policy(tiers = wide)))
        assertEquals("main", cheap.profile.id)
        assertEquals(0, BigDecimal("0.09").compareTo(cheap.conservativeCost!!.amount), message = "10k input at 3/M plus 4k output at 15/M")
        assertEquals(cheap.conservativeCost, cheap.expectedCost)
        assertEquals(Tokens(14_000), cheap.conservativeTokens)
        fun attempt(id: String, first: String, retry: String) = AttemptPolicy(id, "v1", "synthetic", "start", "USD", mapOf(
            "start" to AttemptState.Step(usd(first), usd("0"), listOf(
                AttemptBranch("ok", "accepted", BigDecimal("0.5"), usd("0")), AttemptBranch("again", "retry", BigDecimal("0.5"), usd("0")),
            )),
            "retry" to AttemptState.Step(usd(retry), usd("0"), listOf(AttemptBranch("ok", "accepted", BigDecimal.ONE, usd("0")))),
            "accepted" to AttemptState.Terminal(AttemptOutcome.Accepted, usd("0")),
        ))
        val attempts = mapOf("main" to attempt("main", "0.09", "2"), "escalation" to attempt("escalation", "0.45", "0.1"))
        val policy = RoutingPolicy(wide, candidates, attemptPolicies = attempts, remainingAttempts = 2)
        val total = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Implementing, packet(), null, policy))
        assertEquals("escalation", total.profile.id, "retries make the cheap first attempt dearer in total")
        assertEquals(0, BigDecimal("0.5").compareTo(total.expectedCost!!.amount))
        router.record(total, RoutingOutcome.Accepted)
        assertEquals(CalibrationEntry(RoutingFunction.Implementing, Tier.High, Effort.Medium, RoutingOutcome.Accepted, null, "escalation"), router.calibration.entries().single())
    }

    @Test fun `eligibility excludes pins context fit and an unmet calibrated quality floor with reasons`() {
        val router = Router()
        val wide = TierTable("t2", null, mapOf(Tier.High to setOf("main", "escalation")))
        val pinned = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Implementing, packet(), null, policy(tiers = wide, pins = setOf("escalation"))))
        assertEquals("escalation", pinned.profile.id)
        assertEquals("not pinned", pinned.excluded["main"])
        val big = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Implementing, packet(context = 250_000), null, policy(tiers = wide)))
        assertEquals("escalation", big.profile.id)
        assertEquals("context 250000+4000 does not fit 200000", big.excluded["main"])
        val measured = candidates + ("main" to FakeProfiles.main.copy(stratumOutcomes = listOf(StratumOutcome("edit", 10, 9))))
        val floored = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Implementing, packet(), null, RoutingPolicy(wide, measured, qualityFloor = BigDecimal("0.8"))))
        assertEquals("main", floored.profile.id)
        assertEquals("below the calibrated quality floor", floored.excluded["escalation"], "an unmeasured profile fails a set floor")
        assertEquals(Routed.Deterministic(RoutingFunction.Deterministic), router.selectProfile(RoutingFunction.Deterministic, packet(), null, policy()))
        assertTrue(router.calibration.entries().isEmpty())
    }

    @Test fun `tier tables serve a tier from it upwards and the configuration rejects untiered profile ids`() {
        assertEquals(setOf("main", "escalation"), table.serving(Tier.Medium))
        assertEquals(setOf("helper", "main", "escalation"), table.serving(Tier.Low))
        assertEquals(setOf("x"), TierTable.single("x").serving(Tier.ExtraHigh))
        assertFailsWith<IllegalArgumentException> { TierTable("t", null, mapOf(Tier.Deterministic to setOf("main"))) }
        val config = Config(profiles = FakeProfiles.all, tierTable = TierTable("t", null, mapOf(Tier.High to setOf("main", "ghost"))))
        assertEquals(listOf("tierTable.High: profile 'ghost' is not configured"), config.violations().map { it.toString() })
        assertNull(FunctionTable.DEFAULT.row(RoutingFunction.Plan).effort, "plan effort is configured")
        assertEquals(2, FunctionTable.DEFAULT.row(RoutingFunction.RepairHelper).maxAttempts)
    }
}
