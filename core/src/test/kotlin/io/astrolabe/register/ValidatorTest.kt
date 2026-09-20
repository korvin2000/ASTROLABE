package io.astrolabe.register

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.ContextId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValidatorTest {
    private val validator = Validator(HeuristicEstimator())

    private class Ctx(
        val ids: Set<String> = setOf("#12", "#17", "#22", "#31"),
        val green: Set<String> = emptySet(),
        override val redChecks: Set<String> = emptySet(),
        override val greenOps: Set<Int> = emptySet(),
        override val appliedOps: Set<Int> = emptySet(),
    ) : ValidationContext {
        override fun evidenceExists(id: String) = id in ids
        override fun acceptGreen(accept: String) = accept in green
    }

    private val base = Register.empty(ContextId("cell-1"), "I1", "fix it")

    private fun applied(v: Validation): Register = assertIs<Validation.Applied>(v).register

    private fun rejected(v: Validation): String = assertIs<Validation.Rejected>(v, v.toString()).rule

    @Test
    fun `a patch applies atomically and bumps the version`() {
        val patch = Patch.of(
            Op.PlanAdd("locate dispatch"),
            Op.PlanAdd("pass ctx", accept = "run: pytest -k ctx", req = "R1/AC-1"),
            Op.PlanCursor(1),
            Op.FactAdd(ClaimKind.Hypothesis, "handlers are keyword-only"),
            Op.Next("read src/router.py"),
        )
        val r = applied(validator.check(base, patch, Ctx()))
        assertEquals(1, r.version)
        assertEquals(Mark.Cursor, r.step(1)!!.mark)
        assertEquals("read src/router.py", r.next)
        assertEquals(1, r.facts.size)
    }

    @Test
    fun `every invariant rejects and leaves the register unchanged`() {
        val ready = applied(validator.check(base, Patch.of(Op.PlanAdd("a"), Op.PlanAdd("b"), Op.PlanCursor(1), Op.Next("go")), Ctx()))
        assertEquals("exactly one Next", rejected(validator.check(ready, Patch.of(Op.PlanAdd("c")), Ctx())))
        assertEquals("exactly one Next", rejected(validator.check(ready, Patch.of(Op.Next("a"), Op.Next("b")), Ctx())))
        assertEquals("v needs an existing evidence id", rejected(validator.check(ready, Patch.of(Op.FactAdd(ClaimKind.Verified, "x", evidence = "#99"), Op.Next("n")), Ctx())))
        assertEquals("v needs an existing evidence id", rejected(validator.check(ready, Patch.of(Op.FactAdd(ClaimKind.Verified, "x"), Op.Next("n")), Ctx())))
        assertEquals("tick needs green accept or an evidence id", rejected(validator.check(ready, Patch.of(Op.PlanTick(1), Op.PlanCursor(2), Op.Next("n")), Ctx())))
        assertEquals("[~] needs a reason", rejected(validator.check(ready, Patch.of(Op.PlanCancel(2, " "), Op.Next("n")), Ctx())))
        assertEquals("dead ends need scope and reopen", rejected(validator.check(ready, Patch.of(Op.DeadendAdd("x", null, "", "later"), Op.Next("n")), Ctx())))
        assertEquals("line ≤ 240 chars", rejected(validator.check(ready, Patch.of(Op.FactAdd(ClaimKind.Hypothesis, "x".repeat(241)), Op.Next("n")), Ctx())))
        assertEquals("no fenced code", rejected(validator.check(ready, Patch.of(Op.FactAdd(ClaimKind.Hypothesis, "```py\nx```"), Op.Next("n")), Ctx())))
        assertEquals("one [>] while [ ] exists", rejected(validator.check(ready, Patch.of(Op.PlanCancel(1, "dup"), Op.Next("n")), Ctx())))
        assertEquals("exactly one [>]", rejected(validator.check(ready.copy(plan = ready.plan.map { it.copy(mark = Mark.Cursor) }), Patch.of(Op.PlanAdd("c"), Op.Next("n")), Ctx())))
        assertEquals("patch cap", rejected(validator.check(ready, Patch.of(Op.FactAdd(ClaimKind.Hypothesis, "w ".repeat(120)), Op.FactAdd(ClaimKind.Hypothesis, "w ".repeat(120)), Op.FactAdd(ClaimKind.Hypothesis, "w ".repeat(120)), Op.FactAdd(ClaimKind.Hypothesis, "w ".repeat(120)), Op.FactAdd(ClaimKind.Hypothesis, "w ".repeat(120)), Op.FactAdd(ClaimKind.Hypothesis, "w ".repeat(120)), Op.Next("n")), Ctx())))
        assertEquals("unknown step", rejected(validator.check(ready, Patch.of(Op.PlanCursor(9), Op.Next("n")), Ctx())))
        assertEquals(1, ready.version, "rejections never change the register")
    }

    @Test
    fun `tick needs green accept or evidence, refuted facts are kept, red lines block the cursor`() {
        val ready = applied(validator.check(base, Patch.of(Op.PlanAdd("a", accept = "run: pytest -k a"), Op.PlanAdd("b"), Op.PlanCursor(1), Op.FactAdd(ClaimKind.Verified, "dispatch takes ctx", evidence = "#17"), Op.Next("go")), Ctx()))
        val byGreen = applied(validator.check(ready, Patch.of(Op.PlanTick(1), Op.PlanCursor(2), Op.Next("next")), Ctx(green = setOf("run: pytest -k a"))))
        assertEquals(Mark.Done, byGreen.step(1)!!.mark)
        assertEquals(Mark.Cursor, byGreen.step(2)!!.mark)
        val byEvidence = applied(validator.check(ready, Patch.of(Op.PlanTick(1, "#12"), Op.PlanCursor(2), Op.Next("next")), Ctx()))
        assertEquals("#12", byEvidence.step(1)!!.evidence)

        val refuted = applied(validator.check(ready, Patch.of(Op.FactRefute(1, "#31"), Op.Next("n")), Ctx()))
        assertEquals(ClaimKind.Refuted, refuted.fact(1)!!.kind)
        assertEquals("#31", refuted.fact(1)!!.refutedBy)
        assertEquals(1, refuted.facts.size, "refuted facts stay in history")

        assertEquals("red not recorded", rejected(validator.check(ready, Patch.of(Op.PlanTick(1, "#12"), Op.PlanCursor(2), Op.Next("n")), Ctx(redChecks = setOf("AC-4")))))
        val recorded = applied(validator.check(ready, Patch.of(Op.OpenAdd("AC-4 red: TypeError in handle_cli"), Op.PlanTick(1, "#12"), Op.PlanCursor(2), Op.Next("n")), Ctx(redChecks = setOf("AC-4"))))
        assertEquals(1, recorded.open.size)
    }

    @Test
    fun `conditional ops are dropped when their condition failed and the drop is reported`() {
        val ready = applied(validator.check(base, Patch.of(Op.PlanAdd("a", accept = "run: pytest -k a"), Op.PlanCursor(1), Op.Next("go")), Ctx()))
        val patch = Patch(
            listOf(
                PatchOp(Op.PlanTick(1), Condition(ConditionKind.Green, 2)),
                PatchOp(Op.FactAdd(ClaimKind.Verified, "handlers accept ctx", evidence = "#17"), Condition(ConditionKind.Green, 2)),
                PatchOp(Op.FactAdd(ClaimKind.Hypothesis, "maybe"), Condition(ConditionKind.Applied, 1)),
                PatchOp(Op.Next("update remaining call sites")),
            ),
        )
        val result = assertIs<Validation.Applied>(validator.check(ready, patch, Ctx(appliedOps = setOf(1))))
        assertEquals(2, result.dropped.size)
        assertEquals(Mark.Cursor, result.register.step(1)!!.mark)
        assertEquals(listOf("maybe"), result.register.facts.map { it.text })
        assertEquals(Condition(ConditionKind.Green, 2), Condition.parse("green(op:2)"))
        assertEquals(null, Condition.parse("green(2)"))
    }

    @Test
    fun `an h fact under the active step is flagged as risk`() {
        val ready = applied(validator.check(base, Patch.of(Op.PlanAdd("thread context through handlers"), Op.PlanCursor(1), Op.FactAdd(ClaimKind.Hypothesis, "handlers are keyword-only"), Op.Next("edit handlers to accept context")), Ctx()))
        val result = assertIs<Validation.Applied>(validator.check(ready, Patch.of(Op.Next("edit handlers now")), Ctx()))
        assertTrue(result.flags.any { it.contains("h fact 1") }, result.flags.toString())
    }
}
