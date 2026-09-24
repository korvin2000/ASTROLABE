package io.astrolabe.cell

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.ReserveVerdict
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.WorkId
import io.astrolabe.register.DeadEnd
import io.astrolabe.register.Decision
import io.astrolabe.register.Fact
import io.astrolabe.register.Mark
import io.astrolabe.register.Register
import io.astrolabe.register.Sizes
import io.astrolabe.register.Step
import io.astrolabe.register.Validation
import io.astrolabe.tool.Args
import io.astrolabe.tool.EditArgs
import io.astrolabe.tool.EditOpArgs
import io.astrolabe.tool.LookArgs
import io.astrolabe.tool.ToolCall
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.verify.AcceptanceSurface
import io.astrolabe.verify.Applicability
import io.astrolabe.verify.Currency
import io.astrolabe.verify.TestIntegrity
import io.astrolabe.verify.TestIntegrityFlag
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** P1.8.5: the S0 gate set of §5.6 — computed by the harness, one line each, fired once per condition. */
class GatesTest {
    private val contract = Contract(
        workId = WorkId("W-1"), version = 2, attemptId = AttemptId("a1"), mode = Mode.Autonomous, shape = Shape.S0,
        requests = listOf(UserRequest("U1", Instant.EPOCH, "fix rounding")),
        requirements = listOf(Requirement("R1", "total rounds half-up", listOf("AC-1"), authorityRef = "U1")),
        acceptance = listOf(Acceptance.Run("AC-1", Command(listOf("pytest", "-q")), Origin.Harness, scope = "touched")),
        constraints = emptyList(), exclusions = emptyList(), contractsTouched = emptyList(),
        scope = Scope(listOf("src/"), listOf("migrations/")), budget = Budget.of(Defaults(), Tokens(100_000)),
        authorization = Authorization(Stage.Patch, DClassPolicy.Ask, "workspace-local-test-only"),
    )
    private val increment = Increment("I1", listOf("R1"), accept = listOf("AC-1"), writeScope = listOf("src/"), expectedFiles = 1)
    private val register = Register.empty(ContextId("cell-1"), "I1", "fix rounding")
        .copy(plan = listOf(Step(1, Mark.Cursor, "round half-up", accept = "AC-1")))
    private val gates = Gates.s0()

    private fun state(turn: Int = 1, register: Register = this.register, fired: Set<GateKey> = emptySet()) =
        GateState(turn, register, contract, increment, fired = fired, lastProgressTurn = if (turn > 1) turn - 1 else 0, turnsMax = 40)

    private fun edit(opId: Int = 1) = ToolCall(opId, "c$opId", ToolFamily.Edit, "create", Args.Edit(EditArgs(listOf(EditOpArgs(create = "src/a.py", content = "x")), "add")), buildJsonObject { put("why", "add") })
    private fun look(opId: Int = 1) = ToolCall(opId, "c$opId", ToolFamily.Look, "read", Args.Look(LookArgs("read", "src/a.py")), buildJsonObject { put("what", "read") })
    private fun signature(body: String) = CallSignature.of(look(), ToolOutcome(body))

    /** Runs [state] through the gates turn after turn, threading the fired memory as the loop will. */
    private fun turns(vararg states: GateState): List<GateReport> {
        var fired = emptySet<GateKey>()
        return states.map { s -> gates.evaluate(s.copy(fired = fired)).also { fired = it.fired } }
    }

    @Test
    fun `entry fires once on the first non-register edit without an accept step, and never on reads`() {
        val bare = register.copy(plan = listOf(Step(1, Mark.Cursor, "round half-up")))
        val reports = turns(
            state(1, bare).copy(calls = listOf(look())),
            state(2, bare).copy(calls = listOf(edit())),
            state(3, bare).copy(calls = listOf(edit())),
        )
        assertEquals(listOf(0, 1, 0), reports.map { it.nudges.count { n -> n.key.gate == Gates.ENTRY } })
        assertTrue(reports[1].nudges.single().line.startsWith("entry: editing while no plan step carries an accept:"), reports[1].lines.toString())
        assertEquals(0, gates.evaluate(state(1).copy(calls = listOf(edit()))).outcomes.size, "a step with accept: opens the gate")
        val undeclared = gates.evaluate(state(1).copy(increment = increment.copy(accept = listOf("AC-9")), calls = listOf(edit())))
        assertTrue(undeclared.nudges.single().line.contains("AC-9 is not in contract v2"), undeclared.lines.toString())
    }

    @Test
    fun `exit calls the P1 7 7 gate and refuses every proposal with exactly what is missing`() {
        val proposed = state(5).copy(completionProposed = true)
        val refused = assertIs<GateOutcome.Rejection>(gates.evaluate(proposed).outcomes.single())
        assertEquals(Gates.EXIT, refused.key.gate)
        assertEquals(listOf("AC-1: run: pytest -q (scope touched) — no receipt", "step 1 [>] 'round half-up' has no disposition (done, cancelled or an explicit non-completed exit)"), refused.details)
        assertEquals("exit refused: 2 missing — escape only via state(blocked) or task.ask with evidence", refused.line)
        val again = gates.evaluate(proposed.copy(fired = gates.evaluate(proposed).fired))
        assertEquals(1, again.rejections.size, "a hard gate refuses the same proposal again; it is never deduplicated")

        val green = mapOf("CHK-accept-AC-1" to Currency("rcpt-1", Applicability.Current, eligible = true, green = true, reasons = emptyList()))
        val done = register.copy(plan = listOf(Step(1, Mark.Done, "round half-up", accept = "AC-1", evidence = "rcpt-1")))
        assertEquals(emptyList(), gates.evaluate(state(5, done).copy(completionProposed = true, currencies = green)).outcomes)
        assertEquals(emptyList(), gates.evaluate(state(5)).outcomes, "no proposal, no exit gate")
    }

    @Test
    fun `pressure fires once per rebuild count above alpha`() {
        val reports = turns(
            state(1).copy(contextTokens = 60_000, contextMaxTokens = 100_000),
            state(2).copy(contextTokens = 70_000, contextMaxTokens = 100_000),
            state(3).copy(contextTokens = 72_000, contextMaxTokens = 100_000),
            state(4).copy(contextTokens = 70_000, contextMaxTokens = 100_000, rebuilds = 1),
            state(5).copy(contextTokens = 70_000, contextMaxTokens = 100_000, rebuilds = 1),
        )
        assertEquals(listOf(0, 1, 0, 1, 0), reports.map { it.nudges.size })
        assertEquals("pressure: context 70% > α 65% — fold what matters into STATE; the harness rebuilds", reports[1].nudges.single().line)
        assertEquals("pressure: context 70% > α 65% — second rebuild: partial with a replan hint", reports[3].nudges.single().line)
    }

    @Test
    fun `stall fires once after three turns without progress, resets on progress and yields to a live build`() {
        val reports = turns(
            state(3).copy(lastProgressTurn = 1),
            state(4).copy(lastProgressTurn = 1),
            state(5).copy(lastProgressTurn = 1),
            state(6).copy(lastProgressTurn = 6),
            state(9).copy(lastProgressTurn = 6, liveRunOutput = true),
            state(9).copy(lastProgressTurn = 6),
        )
        assertEquals(listOf(0, 1, 0, 0, 0, 1), reports.map { it.nudges.count { n -> n.key.gate == Gates.STALL } })
        assertEquals("stall: 3 turns without progress — re-read the plan · zoom out · run the pending decision probe · surface the blocker · or request a probe cell", reports[1].nudges.single().line)
        assertEquals(1, gates.evaluate(state(3).copy(lastProgressTurn = 0)).nudges.size, "no progress since cell start counts from turn 0")
    }

    @Test
    fun `a stall suggests the latest decision probe by name`() {
        val decisions = listOf(
            Decision(1, "parse eagerly", "one pass", "lazy parse", probe = "pytest -k eager"),
            Decision(2, "keep the cache", "cheap", null, probe = "pytest -k cache", adrCandidate = true),
            Decision(3, "rename helper", "clarity", null),
        )
        val line = gates.evaluate(state(4, register.copy(decisions = decisions)).copy(lastProgressTurn = 1)).nudges.single().line
        assertTrue(line.contains("run the pending decision probe (decision 2: pytest -k cache) · surface the blocker"), line)
    }

    @Test
    fun `loop nudges on the second identical call and ends the turn on the third with a required state op`() {
        val same = signature("def route(): ...")
        val other = signature("def route(ctx): ...")
        val reports = turns(
            state(1).copy(signatures = listOf(same)),
            state(2).copy(signatures = listOf(same, other, same)),
            state(3).copy(signatures = listOf(same, other, same, other)),
            state(4).copy(signatures = listOf(same, other, same, other, same)),
        )
        assertEquals(listOf(0, 1, 1, 0), reports.map { it.nudges.count { n -> n.key.gate == Gates.LOOP } })
        assertEquals("loop: look.read returned the same result 2 times — change the question or record what you learned", reports[1].nudges.single().line)
        assertTrue(reports[2].nudges.single().key.condition.contains(other.resultDigest.hash8), "turn 3's nudge is for the other signature, seen twice there")
        val ended = reports[3].rejections.single()
        assertTrue(ended.endsTurn)
        assertEquals("state", ended.requiredOp)
        assertEquals("loop: look.read returned the same result 3 times — turn ended; a state op is required", ended.line)
        assertEquals(1, gates.evaluate(state(5).copy(signatures = listOf(same, same, same), fired = reports[3].fired)).rejections.size, "the third occurrence is refused again if replayed")
    }

    @Test
    fun `no or two cursors and red not recorded arrive as the validator's rejection, with the rule`() {
        val sizes = Sizes(100, 1_200, 40, 400)
        val cursor = gates.evaluate(state(2).copy(patchRejection = Validation.Rejected("exactly one [>]", "2 cursors", sizes))).rejections.single()
        assertEquals("cursor: patch rejected — exactly one [>]: 2 cursors", cursor.line)
        val none = gates.evaluate(state(2).copy(patchRejection = Validation.Rejected("one [>] while [ ] exists", "no cursor with 2 open steps", sizes))).rejections.single()
        assertTrue(none.line.startsWith("cursor: patch rejected"), none.line)
        val red = gates.evaluate(state(2).copy(patchRejection = Validation.Rejected("red not recorded", "red [CHK-1] without an Open item before [>] advances", sizes))).rejections.single()
        assertEquals("red-not-recorded: patch rejected — red not recorded: red [CHK-1] without an Open item before [>] advances", red.line)
        val cap = gates.evaluate(state(2).copy(patchRejection = Validation.Rejected("patch cap", "patch is 500 tokens > 400", sizes))).rejections.single()
        assertEquals("register: patch rejected — patch cap: patch is 500 tokens > 400", cap.line)
        assertEquals(emptyList(), gates.evaluate(state(2)).outcomes, "an applied patch is not a gate")
    }

    @Test
    fun `a stale or hypothetical fact under Next is flagged once per fact, even when the harness marked it stale`() {
        val version = FileVersion(Digest.ofUtf8("v1"))
        val withFacts = register.copy(
            facts = listOf(
                Fact(1, ClaimKind.Hypothesis, "router dispatches before validation"),
                Fact(2, ClaimKind.Verified, "handlers validate input", evidenceId = "#3", staleAt = version),
            ),
            next = "move validation into the router dispatch path",
        )
        val reports = turns(state(2, withFacts), state(3, withFacts))
        assertEquals(listOf("risk: h fact 1 rests under Next/active step: router dispatches before validation"), reports[0].lines)
        assertEquals(emptyList(), reports[1].lines)
        val stale = withFacts.copy(next = "handlers validate input first")
        assertEquals(listOf("risk: stale fact 2 rests under Next/active step: handlers validate input"), gates.evaluate(state(2, stale)).lines)
    }

    @Test
    fun `reserve fires once with the budget's own line`() {
        val reached = ReserveVerdict(true, "reserve reached: verify and report; no new edits", null)
        val reports = turns(state(20), state(21).copy(reserve = reached), state(22).copy(reserve = reached))
        assertEquals(listOf(emptyList(), listOf("reserve reached: verify and report; no new edits"), emptyList()), reports.map { it.lines })
    }

    @Test
    fun `turn budget fires once at eighty percent of the cell's turns`() {
        val reports = turns(state(31), state(32), state(33), state(40))
        assertEquals(listOf(0, 1, 0, 0), reports.map { it.nudges.size })
        assertEquals("turn budget: turn 32/40 — reach a coherent boundary and checkpoint", reports[1].nudges.single().line)
        assertEquals(emptyList(), gates.evaluate(state(39).copy(turnsMax = 0)).outcomes, "no turn cap, no gate")
    }

    @Test
    fun `progress events are the five evidence-backed movements and nothing else`() {
        val before = register.copy(
            plan = listOf(Step(1, Mark.Cursor, "round half-up", accept = "AC-1"), Step(2, Mark.Todo, "wire the handler")),
            facts = listOf(Fact(1, ClaimKind.Hypothesis, "totals round half-up")),
        )
        val after = before.copy(
            plan = listOf(Step(1, Mark.Done, "round half-up", accept = "AC-1"), Step(2, Mark.Done, "wire the handler", evidence = "#7"), Step(3, Mark.Todo, "new step")),
            facts = before.facts + listOf(
                Fact(2, ClaimKind.Verified, "Totals round  half-up", evidenceId = "#8"),
                Fact(3, ClaimKind.Verified, "the handler is registered", evidenceId = "#9"),
                Fact(4, ClaimKind.Hypothesis, "caching is the cause"),
            ),
            deadEnds = listOf(DeadEnd(1, "patching Decimal directly", "#10", "src/money.py", "if Decimal gains a context hook")),
            next = "run the suite",
        )
        val events = Progress.events(before, after, turn = 4, certifiedBefore = emptySet(), certifiedAfter = setOf("CHK-accept-AC-1"))
        assertEquals(
            listOf(
                ProgressEvent(ProgressKind.EvidenceTick, 4, "step 1"),
                ProgressEvent(ProgressKind.EvidenceTick, 4, "step 2"),
                ProgressEvent(ProgressKind.HypothesisVerified, 4, "fact 2"),
                ProgressEvent(ProgressKind.VerifiedFact, 4, "fact 3"),
                ProgressEvent(ProgressKind.GreenAcceptanceRun, 4, "CHK-accept-AC-1"),
                ProgressEvent(ProgressKind.DeadEnd, 4, "dead end 1"),
            ),
            events,
        )
        val prose = before.copy(plan = before.plan + Step(3, Mark.Todo, "another step"), facts = before.facts + Fact(2, ClaimKind.Hypothesis, "maybe"), next = "think harder")
        assertEquals(emptyList(), Progress.events(before, prose, 5), "plan additions, hypotheses and Next are not progress")
        assertEquals(emptyList(), Progress.events(after, after, 6, setOf("CHK-accept-AC-1"), setOf("CHK-accept-AC-1")), "an acceptance that stays green is not new progress")
    }

    @Test
    fun `a later gate registers through the same interface and shares the once-per-condition memory`() {
        val impact = object : Gate {
            override val name: String get() = "impact"
            override fun evaluate(state: GateState): List<GateOutcome> =
                state.unresolvedImpactNudges.map { GateOutcome.Nudge(GateKey(name, it), "impact: $it") }
        }
        val extended = gates.with(impact)
        assertEquals(gates.gates.size + 1, extended.gates.size)
        val s = state(2).copy(unresolvedImpactNudges = listOf("Router.dispatch signature changed; 6 references not inspected"))
        val first = extended.evaluate(s)
        assertEquals(listOf("impact: Router.dispatch signature changed; 6 references not inspected"), first.lines)
        assertEquals(emptyList(), extended.evaluate(s.copy(fired = first.fired)).lines)
        assertTrue(runCatching { gates.with(impact, impact) }.isFailure, "gate names are unique")
        val liar = object : Gate {
            override val name: String get() = "liar"
            override fun evaluate(state: GateState) = listOf(GateOutcome.Nudge(GateKey("stall", "x"), "not mine"))
        }
        assertTrue(runCatching { gates.with(liar).evaluate(state(2)) }.isFailure, "a gate may only fire under its own name")
    }

    @Test
    fun `contract touch, repeated failure, scope and acceptance surface gates each fire once per condition`() {
        val flag = TestIntegrityFlag("tests/test_a.py", AcceptanceSurface.TestFile, "edit #2", listOf("CHK-accept-AC-1"), TestIntegrity.WEAKENED_ASSERTION)
        val added = flag.copy(path = "tests/test_b.py", kind = TestIntegrity.ADDITIONS_ONLY)
        val busy = state(turn = 2).copy(
            editedPaths = setOf("src/api.py", "src/a.py"),
            contractAnchors = mapOf("CON-payments-api" to setOf("src/api.py"), "CON-other" to setOf("src/other.py")),
            repeatedFailures = listOf("CHK-types-touched: src/a.py:#:#: error: bad"),
            outsideIncrement = listOf("tests/test_a.py"),
            surfaceFlags = listOf(flag, added),
        )
        val (first, second) = turns(busy, busy.copy(turn = 3, lastProgressTurn = 2))
        val lines = first.outcomes.map { it.key.gate to it.line }
        assertEquals(
            listOf(
                Gates.CONTRACT_TOUCH to "contract CON-payments-api touched (src/api.py): an ADR in the main line is required before this lands",
                Gates.REPEATED_FAILURE to "same failure twice (CHK-types-touched: src/a.py:#:#: error: bad): change the hypothesis, record a dead end, or request an alternative attempt",
                Gates.SCOPE to "scope: tests/test_a.py outside the increment's write scope — the next crossing needs task.propose(increment_split) or the path justified in why",
                Gates.ACCEPTANCE_SURFACE to "acceptance surface: tests/test_a.py · weakened-assertion — justify it in the packet; a weakened required check needs review",
            ),
            lines.filter { it.first in setOf(Gates.CONTRACT_TOUCH, Gates.REPEATED_FAILURE, Gates.SCOPE, Gates.ACCEPTANCE_SURFACE) },
        )
        assertTrue(second.outcomes.none { it.key.gate in setOf(Gates.CONTRACT_TOUCH, Gates.REPEATED_FAILURE, Gates.SCOPE, Gates.ACCEPTANCE_SURFACE) }, "once per condition")
        assertTrue(gates.evaluate(state()).outcomes.none { it.key.gate == Gates.CONTRACT_TOUCH }, "no CON note, no contract-touch gate")
    }
}
