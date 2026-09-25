package io.astrolabe.eval

import io.astrolabe.id.Digest
import io.astrolabe.id.IdGen
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExperimentTest {
    private val ids: IdGen = object : IdGen {
        private var n = 0
        override fun next(prefix: String): String = "$prefix-${++n}"
    }
    private val clock = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC)
    private val tuning = Digest.ofUtf8("tuning-set-v1")
    private val transfer = Digest.ofUtf8("final-set-v1")
    private val reasoning = Digest.ofUtf8("stronger-reasoning-v1")
    private val context = Digest.ofUtf8("better-context-v1")
    private val attempt = Digest.ofUtf8("another-attempt-v1")
    private val hypothesis = Hypothesis("stale anchors re-read whole files", "tool.edit", decimal("0.05"), decimal("-0.10"),
        "StaleAnchor repeated in 12 tasks")

    private fun admitted(): ChangeProposal = assertIs<ProposalOutcome.Admitted>(ChangeProposals(ids)
        .propose(hypothesis, setOf(HarnessSurface.ToolBehaviour), baseline, candidate, "diff-since-expect on re-read")).proposal

    private fun plan(p: ChangeProposal = admitted()) = ExperimentPlan(p, money("500"), listOf(
        ExperimentArm(ExperimentArmKind.Baseline, baseline, money("500")),
        ExperimentArm(ExperimentArmKind.Candidate, candidate, money("500")),
        ExperimentArm(ExperimentArmKind.StrongerReasoning, reasoning, money("500")),
        ExperimentArm(ExperimentArmKind.BetterContext, context, money("500")),
        ExperimentArm(ExperimentArmKind.AnotherAttempt, attempt, money("500")),
    ), tuning, transfer)

    /** A measured paired report; [better] makes the candidate dominate, otherwise both arms are identical. */
    private fun report(base: Digest, cand: Digest, set: Digest, better: Boolean = true): PromotionReport {
        val keys = (0 until 200).flatMap { listOf(planned("r$it", "s", "simple"), planned("r$it", "c", "complex")) }
        val d = EvaluationDesign(policy(), set, base, setOf(cand), keys)
        val b = Scorecard.calculate(d, base, keys.mapIndexed { i, k -> trial(k, i % 4 < 2, "2", base) })
        val c = Scorecard.calculate(d, cand, keys.mapIndexed { i, k -> if (better) trial(k, true, "1", cand) else trial(k, i % 4 < 2, "2", cand) })
        val ev = EvaluationEvidence(cand, set, EvaluationOrigin.Measured, EvidenceCheck.Pass, EvidenceCheck.Pass,
            EvidenceCheck.Pass, "fixture-attestation-v1")
        return PromotionReport.evaluate(b, c, ev, money("0"), money("0"))
    }

    private fun comparison(weakAgainst: ExperimentArmKind? = null) = ExperimentEvidence.Comparison(
        report(baseline, candidate, tuning),
        mapOf(ExperimentArmKind.StrongerReasoning to reasoning, ExperimentArmKind.BetterContext to context,
            ExperimentArmKind.AnotherAttempt to attempt).mapValues { (k, v) -> report(v, candidate, tuning, k != weakAgainst) },
    )

    private fun ExperimentTransition.record(): ExperimentRecord = assertIs<ExperimentTransition.Advanced>(this).record

    @Test fun `a proposal touching a protected surface or more than one scope is refused`() {
        val refused = assertIs<ProposalOutcome.Refused>(ChangeProposals(ids).propose(hypothesis,
            setOf(HarnessSurface.Recovery, HarnessSurface.AcceptanceCriteria, HarnessSurface.BudgetAccounting),
            baseline, candidate, "x"))
        assertEquals(listOf("protected surface: AcceptanceCriteria", "protected surface: BudgetAccounting"), refused.reasons)
        val wide = assertIs<ProposalOutcome.Refused>(ChangeProposals(ids).propose(hypothesis,
            setOf(HarnessSurface.Recovery, HarnessSurface.ContextPolicy), baseline, baseline, "x"))
        assertEquals(2, wide.reasons.size)
        assertTrue(HarnessSurface.entries.count { !it.editable } == 4)
    }

    @Test fun `the plan matches total budget across baseline candidate and every alternative`() {
        val p = admitted()
        assertFailsWith<IllegalArgumentException> {
            ExperimentPlan(p, money("500"), plan(p).arms.map {
                if (it.kind == ExperimentArmKind.StrongerReasoning) it.copy(budget = money("900")) else it }, tuning, transfer)
        }
        assertFailsWith<IllegalArgumentException> {
            ExperimentPlan(p, money("500"), plan(p).arms.filter { it.kind != ExperimentArmKind.AnotherAttempt }, tuning, transfer)
        }
        assertFailsWith<IllegalArgumentException> { ExperimentPlan(p, money("500"), plan(p).arms, tuning, tuning) }
    }

    @Test fun `a full cycle promotes for subsequent attempts and rolls back to the frozen baseline`() {
        val xp = Experiments(clock, ids)
        var r = xp.open(plan())
        assertEquals(baseline, r.harnessForSubsequentAttempts)
        assertIs<ExperimentTransition.Refused>(xp.advance(r, ExperimentEvidence.Adoption("owner", "attempt-9")))
        r = xp.advance(r, ExperimentEvidence.Checks(true, true, "smoke-v1")).record()
        r = xp.advance(r, comparison()).record()
        assertEquals(ExperimentStage.Compared, r.stage)
        val integrated = Digest.ofUtf8("candidate-plus-other-v1")
        r = xp.advance(r, ExperimentEvidence.Integration(integrated, setOf(Digest.ofUtf8("other-change")),
            report(baseline, integrated, tuning))).record()
        assertIs<ExperimentTransition.Refused>(xp.advance(r, ExperimentEvidence.Transfer(report(baseline, candidate, tuning))))
        r = xp.advance(r, ExperimentEvidence.Transfer(report(baseline, candidate, transfer))).record()
        assertEquals(baseline, r.harnessForSubsequentAttempts)
        r = xp.advance(r, ExperimentEvidence.Adoption("owner", "attempt-9")).record()
        assertEquals(ExperimentStage.Promoted, r.stage)
        assertEquals(candidate, r.harnessForSubsequentAttempts)
        r = xp.advance(r, ExperimentEvidence.Rollback("post-merge reverts")).record()
        assertEquals(baseline, r.harnessForSubsequentAttempts)
        assertEquals(ExperimentStage.entries.filter { it != ExperimentStage.Rejected }, r.history.map { it.stage })
        assertIs<ExperimentTransition.Refused>(xp.advance(r, ExperimentEvidence.Rejection("late")))
    }

    @Test fun `losing to the same spend on stronger reasoning rejects the candidate`() {
        val xp = Experiments(clock, ids)
        val checked = xp.advance(xp.open(plan()), ExperimentEvidence.Checks(true, true, "smoke-v1")).record()
        val lost = xp.advance(checked, comparison(weakAgainst = ExperimentArmKind.StrongerReasoning)).record()
        assertEquals(ExperimentStage.Rejected, lost.stage)
        assertTrue(lost.history.last().reason!!.startsWith("vs StrongerReasoning"))
        val missing = ExperimentEvidence.Comparison(report(baseline, candidate, tuning), emptyMap())
        assertIs<ExperimentTransition.Refused>(xp.advance(checked, missing))
        val failedSmoke = xp.advance(xp.open(plan()), ExperimentEvidence.Checks(true, false, "smoke-v1")).record()
        assertEquals(ExperimentStage.Rejected, failedSmoke.stage)
        assertEquals("smoke run failed", failedSmoke.history.last().reason)
    }
}
