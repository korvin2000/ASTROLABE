package io.astrolabe.register

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.ContextId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValidatorTest {
    @Test
    fun `new historical facts are stale immediately including missing anchors`() {
        val old = io.astrolabe.id.FileVersion(io.astrolabe.id.Digest.ofUtf8("old"))
        val now = io.astrolabe.id.FileVersion(io.astrolabe.id.Digest.ofUtf8("now"))
        for (current in listOf(old, now, null)) {
            val ctx = object : ValidationContext by Ctx() {
                override fun currentVersion(path: String) = current
            }
            val fact = applied(validator.check(base, Patch.of(
                Op.FactAdd(ClaimKind.Verified, "historical claim", anchor = io.astrolabe.evidence.Anchor("a.py", old), evidence = "#12"), Op.Next("inspect"),
            ), ctx)).facts.single()
            assertEquals(current != old, fact.stale)
        }
    }

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
        assertEquals("exactly one Next", rejected(validator.check(base, Patch.of(Op.FactAdd(ClaimKind.Hypothesis, "c")), Ctx())), "no Next in the patch nor in STATE, and no open step")
        assertEquals("exactly one Next", rejected(validator.check(ready, Patch.of(Op.Next("a"), Op.Next("b")), Ctx())))
        // D-373: a per-op rule skips that op and the others apply; an op alone rejects the patch with its rule. An unbacked
        // tick and a v fact without stored evidence are recorded (D-373 tests below).
        assertEquals("[~] needs a reason", rejected(validator.check(ready, Patch.of(Op.PlanCancel(2, " ")), Ctx())))
        assertEquals("dead ends need scope and reopen", rejected(validator.check(ready, Patch.of(Op.DeadendAdd("x", null, "", "later")), Ctx())))
        assertEquals("line ≤ 600 chars", rejected(validator.check(ready, Patch.of(Op.FactAdd(ClaimKind.Hypothesis, "x".repeat(601))), Ctx())))
        assertIs<Validation.Applied>(validator.check(ready, Patch.of(Op.FactAdd(ClaimKind.Hypothesis, "x".repeat(500)), Op.Next("n")), Ctx()), "D-370: a 500-char fact line fits the default")
        assertEquals("line ≤ 240 chars", rejected(Validator(HeuristicEstimator(), factLineMaxChars = 240).check(ready, Patch.of(Op.FactAdd(ClaimKind.Hypothesis, "x".repeat(500))), Ctx())), "the configured cap applies")
        assertEquals("no fenced code", rejected(validator.check(ready, Patch.of(Op.FactAdd(ClaimKind.Hypothesis, "```py\nx```")), Ctx())))
        assertEquals("exactly one [>]", rejected(validator.check(ready.copy(plan = ready.plan.map { it.copy(mark = Mark.Cursor) }), Patch.of(Op.PlanAdd("c"), Op.Next("n")), Ctx())))
        val facts = Array<Op>(20) { Op.FactAdd(ClaimKind.Hypothesis, "w ".repeat(120)) }
        assertEquals("patch cap", rejected(validator.check(ready, Patch.of(*facts, Op.Next("n")), Ctx())))
        val six = validator.check(ready, Patch.of(*facts.copyOf(6).requireNoNulls(), Op.Next("n")), Ctx())
        assertTrue(six is Validation.Applied && six.sizes.patchTokens > 400, "D-365: a patch over the old 400-token cap applies: $six")
        assertEquals("unknown step", rejected(validator.check(ready, Patch.of(Op.PlanCursor(9)), Ctx())))
        assertEquals(1, ready.version, "rejections never change the register")
    }

    @Test
    fun `a missing Next keeps the previous one and a missing cursor goes to the first open step, never past a red line`() {
        val ready = applied(validator.check(base, Patch.of(Op.PlanAdd("a"), Op.PlanAdd("b"), Op.PlanAdd("c"), Op.PlanCursor(1), Op.Next("go")), Ctx()))
        val kept = applied(validator.check(ready, Patch.of(Op.PlanAdd("d")), Ctx()))
        assertEquals("go", kept.next)
        assertEquals(Mark.Cursor, kept.step(1)!!.mark)

        val cancelled = applied(validator.check(ready, Patch.of(Op.PlanCancel(1, "dup"), Op.Next("n")), Ctx()))
        assertEquals(Mark.Cursor, cancelled.step(2)!!.mark, "the cursor goes to the first open step")
        assertEquals(1, cancelled.cursors)
        val ticked = applied(validator.check(ready, Patch.of(Op.PlanTick(1, "#12")), Ctx()))
        assertEquals(Mark.Cursor, ticked.step(2)!!.mark)
        assertEquals("go", ticked.next)
        val fresh = applied(validator.check(base, Patch.of(Op.PlanAdd("x"), Op.PlanAdd("y"), Op.Next("start")), Ctx()))
        assertEquals(Mark.Cursor, fresh.step(1)!!.mark)

        val red = validator.check(ready, Patch.of(Op.PlanTick(1, "#12")), Ctx(redChecks = setOf("AC-4")))
        assertEquals("red not recorded", rejected(red))
        assertTrue((red as Validation.Rejected).detail.contains("the patch left no [>]; it would go to step 2"), red.detail)
        applied(validator.check(ready, Patch.of(Op.OpenAdd("AC-4 red: TypeError"), Op.PlanTick(1, "#12")), Ctx(redChecks = setOf("AC-4"))))
    }

    @Test
    fun `with no Next yet the active step becomes Next, and without an open step the patch still refuses`() {
        val first = applied(validator.check(base, Patch.of(Op.PlanAdd("write hello.py"), Op.PlanAdd("run it"), Op.PlanCursor(2)), Ctx()))
        assertEquals("run it", first.next, "Next is the [>] step the patch placed")
        val placed = applied(validator.check(base, Patch.of(Op.PlanAdd("write hello.py"), Op.PlanAdd("run it")), Ctx()))
        assertEquals(Mark.Cursor, placed.step(1)!!.mark)
        assertEquals("write hello.py", placed.next, "the cursor placed by D-350 names Next too")
        val named = applied(validator.check(base, Patch.of(Op.PlanAdd("write hello.py"), Op.Next("check the output")), Ctx()))
        assertEquals("check the output", named.next, "a named Next wins")
        assertEquals("write hello.py", applied(validator.check(placed, Patch.of(Op.PlanTick(1, "#12")), Ctx())).next, "an existing Next is kept")

        val done = validator.check(base, Patch.of(Op.PlanAdd("write hello.py"), Op.PlanTick(1, "#12")), Ctx())
        assertEquals("exactly one Next", rejected(done))
        assertTrue((done as Validation.Rejected).detail.contains("no open step to take it from"), done.detail)
    }

    @Test
    fun `a tick without usable evidence is recorded without it and a v fact whose evidence does not resolve is kept as h`() {
        val ready = applied(validator.check(base, Patch.of(Op.PlanAdd("a", accept = "run: pytest -k a"), Op.PlanAdd("b"), Op.PlanCursor(1), Op.Next("go")), Ctx()))
        val tick = assertIs<Validation.Applied>(validator.check(ready, Patch.of(Op.PlanTick(1, "op:3"), Op.Next("n")), Ctx()))
        assertEquals(Mark.Done, tick.register.step(1)!!.mark)
        assertEquals(null, tick.register.step(1)!!.evidence, "the unusable evidence is dropped")
        assertEquals(setOf(1), tick.unbackedTicks)
        assertEquals(listOf("tick 1 recorded without evidence: 'op:3' names no call of this turn; a stored result is #N"), tick.notes)
        assertEquals(emptyList(), io.astrolabe.cell.Progress.events(ready, tick.register, 2, unbackedTicks = tick.unbackedTicks), "an unbacked tick is not progress")
        val backed = assertIs<Validation.Applied>(validator.check(ready, Patch.of(Op.PlanTick(1, "#12")), Ctx()))
        assertEquals(emptySet(), backed.unbackedTicks)
        assertEquals(1, io.astrolabe.cell.Progress.events(ready, backed.register, 2).size)

        val fact = assertIs<Validation.Applied>(validator.check(ready, Patch.of(Op.FactAdd(ClaimKind.Verified, "x", evidence = "#99"), Op.Next("n")), Ctx()))
        assertEquals(ClaimKind.Hypothesis, fact.register.facts.single().kind)
        assertEquals(null, fact.register.facts.single().evidenceId)
        assertTrue(fact.notes.single().startsWith("op 1 (fact.add) kept as h: '#99' is not a stored result"), fact.notes.toString())
        assertEquals(ClaimKind.Verified, applied(validator.check(ready, Patch.of(Op.FactAdd(ClaimKind.Verified, "x", evidence = "#12")), Ctx())).facts.single().kind, "a v fact with stored evidence stays v")
    }

    @Test
    fun `a cursor on a done or unknown step is ignored with a note and the cursor goes to the first open step`() {
        val ready = applied(validator.check(base, Patch.of(Op.PlanAdd("a"), Op.PlanAdd("b"), Op.PlanCursor(1), Op.Next("go")), Ctx()))
        // the live run's last patch: tick, cursor 0, next
        val live = assertIs<Validation.Applied>(validator.check(ready, Patch.of(Op.PlanTick(1, "#12"), Op.PlanCursor(0), Op.Next("increment complete")), Ctx()))
        assertEquals(listOf("op 2 (plan.cursor) ignored: unknown step 0"), live.notes)
        assertEquals(Mark.Cursor, live.register.step(2)!!.mark, "the first open step takes the cursor")
        val done = assertIs<Validation.Applied>(validator.check(live.register, Patch.of(Op.PlanCursor(1), Op.Next("n")), Ctx()))
        assertEquals(listOf("op 1 (plan.cursor) ignored: step 1 is [x]"), done.notes)
        assertEquals(Mark.Cursor, done.register.step(2)!!.mark)
    }

    @Test
    fun `an invalid op is skipped and named while the rest apply unless a later op depends on it`() {
        val ready = applied(validator.check(base, Patch.of(Op.PlanAdd("a"), Op.PlanCursor(1), Op.Next("go")), Ctx()))
        val partly = assertIs<Validation.Applied>(validator.check(ready, Patch.of(Op.PlanAdd("b"), Op.OpenAdd("q"), Op.PlanCancel(1, " "), Op.Next("n")), Ctx()))
        assertEquals(listOf("op 3 (plan.cancel) skipped: [~] needs a reason — plan.cancel(1)"), partly.notes)
        assertEquals(listOf("a", "b"), partly.register.plan.map { it.text })
        assertEquals(1, partly.register.open.size)
        assertEquals(3, partly.appliedOps.size)
        val dependent = validator.check(ready, Patch.of(Op.PlanAdd("x".repeat(601)), Op.PlanTick(2, "#12"), Op.Next("n")), Ctx())
        assertEquals("depends on a skipped op", rejected(dependent))
        assertTrue((dependent as Validation.Rejected).detail.contains("op 2 (plan.tick) names step 2, which op 1 (skipped) would have created"), dependent.detail)
        assertIs<Validation.Applied>(validator.check(ready, Patch.of(Op.PlanAdd("x".repeat(601)), Op.PlanTick(1, "#12"), Op.Next("n")), Ctx()), "a tick of an existing step does not depend on it")
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
