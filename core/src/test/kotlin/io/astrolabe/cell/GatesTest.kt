package io.astrolabe.cell

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.atlas.DefinitionChanges
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.ReserveVerdict
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.EvidencePurpose
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
import io.astrolabe.tool.Effects
import io.astrolabe.tool.EnvelopeHeader
import io.astrolabe.tool.LookArgs
import io.astrolabe.tool.RunArgs
import io.astrolabe.tool.RuntimeFields
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
        // The project's suite the host declared the check of R1 (a goal item); a sniffed one alone is regression (W8).
        acceptance = listOf(Acceptance.Run("AC-1", Command(listOf("pytest", "-q")), Origin.Harness, scope = "touched", purpose = EvidencePurpose.Goal)),
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
    private fun run(opId: Int = 1) = ToolCall(opId, "c$opId", ToolFamily.Run, "run", Args.Run(RunArgs(argv = listOf("pytest", "-q"))), buildJsonObject { put("argv", "pytest -q") })
    private fun look(opId: Int = 1) = ToolCall(opId, "c$opId", ToolFamily.Look, "read", Args.Look(LookArgs("read", "src/a.py")), buildJsonObject { put("what", "read") })
    private fun signature(body: String) = CallSignature.of(look(), ToolOutcome(body))

    /** Runs [state] through the gates turn after turn, threading the fired memory as the loop will. */
    private fun turns(vararg states: GateState): List<GateReport> {
        var fired = emptySet<GateKey>()
        return states.map { s -> gates.evaluate(s.copy(fired = fired)).also { fired = it.fired } }
    }

    @Test
    fun `entry fires once on the first edit when neither the contract nor a plan step holds the acceptance, and never on reads`() {
        val bare = register.copy(plan = listOf(Step(1, Mark.Cursor, "round half-up")))
        val undeclared = increment.copy(accept = listOf("AC-9"))
        val reports = turns(
            state(1, bare).copy(increment = undeclared, calls = listOf(look())),
            state(2, bare).copy(increment = undeclared, calls = listOf(edit())),
            state(3, bare).copy(increment = undeclared, calls = listOf(edit())),
        )
        assertEquals(listOf(0, 1, 0), reports.map { it.nudges.count { n -> n.key.gate == Gates.ENTRY } })
        assertTrue(reports[1].nudges.single().line.startsWith("entry: editing while acceptance AC-9 is not in contract v2 and no plan step carries an accept:"), reports[1].lines.toString())
        assertEquals(0, gates.evaluate(state(1).copy(increment = undeclared, calls = listOf(edit()))).outcomes.size, "a step with accept: opens the gate")
        val none = gates.evaluate(state(1, bare).copy(increment = increment.copy(accept = emptyList()), calls = listOf(edit())))
        assertTrue(none.nudges.single().line.contains("the increment declares no acceptance"), none.lines.toString())
    }

    @Test
    fun `entry is silent on the first edit when the contract already holds the increment's acceptance`() {
        val bare = register.copy(plan = listOf(Step(1, Mark.Cursor, "round half-up")))
        assertEquals(emptyList(), gates.evaluate(state(1, bare).copy(calls = listOf(edit()))).outcomes, "a goal run: item")
        val reviewed = contract.copy(acceptance = contract.acceptance + Acceptance.Review("AC-R", "the host reviews the change", Origin.Harness, purpose = EvidencePurpose.Goal))
        assertEquals(emptyList(), gates.evaluate(state(1, bare).copy(contract = reviewed, increment = increment.copy(accept = listOf("AC-R")), calls = listOf(edit()))).outcomes, "the host's review item")
    }

    @Test
    fun `entry asks once for a goal criterion when the increment's acceptance is regression only, and a response increment is exempt`() {
        val bare = register.copy(plan = listOf(Step(1, Mark.Cursor, "round half-up")))
        val sniffed = contract.copy(acceptance = listOf(Acceptance.Run("AC-1", Command(listOf("pytest", "-q")), Origin.Harness, scope = "touched")))
        val first = gates.evaluate(state(1, bare).copy(contract = sniffed, calls = listOf(edit())))
        assertEquals(listOf(GateKey(Gates.ENTRY, "first-edit")), first.nudges.map { it.key })
        assertTrue(first.nudges.single().line.startsWith("entry: editing while acceptance AC-1 is regression only"), first.lines.toString())
        assertEquals(emptyList(), gates.evaluate(state(1, bare).copy(contract = sniffed, calls = listOf(edit()), fired = first.fired)).nudges, "once")
        val response = increment.copy(id = "inc-U1", produces = io.astrolabe.graph.Production.Resolves("U1"))
        assertEquals(emptyList(), gates.evaluate(state(1, bare).copy(contract = sniffed, increment = response, calls = listOf(edit()))).nudges, "a response increment")
    }

    @Test
    fun `exit calls the D-337 resolver and refuses a rework with exactly what is missing`() {
        val proposed = state(5).copy(completionProposed = true)
        val refused = assertIs<GateOutcome.Rejection>(gates.evaluate(proposed).outcomes.single())
        assertEquals(Gates.EXIT, refused.key.gate)
        assertEquals(listOf("step 1 [>] 'round half-up' has no disposition (done, cancelled or an explicit non-completed exit)", "AC-1: run: pytest -q (scope touched) — no receipt"), refused.details)
        assertEquals("exit refused: 2 to fix — fix them and propose completion again", refused.line)
        val undisposedOnly = register.copy(plan = listOf(Step(1, Mark.Done, "round half-up", evidence = "rcpt-1")))
        assertEquals(emptyList(), gates.evaluate(state(5, undisposedOnly).copy(completionProposed = true)).rejections, "an unverified item alone awaits a decision: no refusal (I1)")
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
    fun `pressure fires at the absolute ceiling under a window whose alpha share is far above it`() {
        val window = 1_048_576L
        val reports = turns(
            state(1).copy(contextTokens = 120_000, contextMaxTokens = window),
            state(2).copy(contextTokens = 395_000, contextMaxTokens = window),
            state(3).copy(contextTokens = 607_000, contextMaxTokens = window, rebuilds = 1),
        )
        assertEquals(listOf(0, 1, 1), reports.map { it.nudges.size })
        assertEquals("pressure: context 395000 tokens > the 128000-token ceiling — fold what matters into STATE; the harness rebuilds", reports[1].nudges.single().line)
        assertEquals("pressure: context 607000 tokens > the 128000-token ceiling — second rebuild: partial with a replan hint", reports[2].nudges.single().line)
    }

    @Test
    fun `stall fires every five idle turns, resets on progress and yields to a live build`() {
        val reports = turns(
            state(5).copy(lastProgressTurn = 1),
            state(6).copy(lastProgressTurn = 1),
            state(7).copy(lastProgressTurn = 1),
            state(11).copy(lastProgressTurn = 1),
            state(12).copy(lastProgressTurn = 1),
            state(16).copy(lastProgressTurn = 1),
            state(16).copy(lastProgressTurn = 16),
            state(21).copy(lastProgressTurn = 16, liveRunOutput = true),
            state(21).copy(lastProgressTurn = 16),
        )
        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0, 0, 1), reports.map { it.nudges.count { n -> n.key.gate == Gates.STALL } })
        assertEquals("stall: 5 turns without progress — re-read the plan · zoom out · run the pending decision probe · surface the blocker · or request a probe cell", reports[1].nudges.single().line)
        assertEquals("stall: 15 turns without progress — re-read the plan · zoom out · run the pending decision probe · surface the blocker · or request a probe cell", reports[5].nudges.single().line)
        assertEquals(1, gates.evaluate(state(5).copy(lastProgressTurn = 0)).nudges.size, "no progress since cell start counts from turn 0")
    }

    @Test
    fun `an applied edit and a new run result are progress, a repeated identical run and reads are not`() {
        fun header(status: String) = EnvelopeHeader("#1", "run", null, emptyMap(), null, false, Effects.None, runtime = RuntimeFields("a1", status, null, null, null, "complete"))
        val pytest = run(2)
        val applied = edit() to ToolOutcome("applied", applied = true)
        val failedEdit = edit() to ToolOutcome("anchor not found")
        val red = pytest to ToolOutcome("1 failed", header("failed"))
        val green = pytest to ToolOutcome("1 passed", header("ok"))
        val read = look() to ToolOutcome("def total(): ...")

        assertEquals(listOf(ProgressEvent(ProgressKind.EditApplied, 3, "op 1")), Progress.work(3, listOf(applied, read), emptySet()))
        assertEquals(listOf(ProgressEvent(ProgressKind.NewResult, 3, "op 2")), Progress.work(3, listOf(red, red), emptySet()), "the same result twice in one turn is one piece of news")
        val seen = setOf(CallSignature.of(red.first, red.second))
        assertEquals(emptyList(), Progress.work(4, listOf(red), seen), "the same command with the same result is not new information")
        assertEquals(listOf(ProgressEvent(ProgressKind.NewResult, 4, "op 2")), Progress.work(4, listOf(green), seen), "the same command with a new result is")
        assertEquals(emptyList(), Progress.work(4, listOf(read, failedEdit), emptySet()), "reads and an edit that did not apply are not progress")
        assertEquals(emptyList(), Progress.work(4, listOf(pytest to ToolOutcome("started", header("running")), pytest to ToolOutcome("denied", header("denied"))), emptySet()), "a live handle or a denial is no result")

        // Through the gate: work keeps lastProgressTurn moving, so a cell that edits and tests never stalls; reads alone do.
        var last = 0
        var seenRuns = emptySet<CallSignature>()
        var fired = emptySet<GateKey>()
        val week = listOf(listOf(read), listOf(applied), listOf(read), listOf(red), listOf(read), listOf(read), listOf(red), listOf(read), listOf(read), listOf(read), listOf(read))
        val stalls = week.mapIndexed { index, executed ->
            val turn = index + 1
            if (Progress.work(turn, executed, seenRuns).isNotEmpty()) last = turn
            seenRuns = seenRuns + executed.filter { it.first.family == ToolFamily.Run }.map { CallSignature.of(it.first, it.second) }
            val report = gates.evaluate(state(turn).copy(lastProgressTurn = last, fired = fired))
            fired = report.fired
            report.nudges.count { it.key.gate == Gates.STALL }
        }
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0), stalls, "progress at turns 2 and 4; the repeated run at 7 is not; the stall fires at 4 + 5")
    }

    @Test
    fun `a stall suggests the latest decision probe by name`() {
        val decisions = listOf(
            Decision(1, "parse eagerly", "one pass", "lazy parse", probe = "pytest -k eager"),
            Decision(2, "keep the cache", "cheap", null, probe = "pytest -k cache", adrCandidate = true),
            Decision(3, "rename helper", "clarity", null),
        )
        val line = gates.evaluate(state(6, register.copy(decisions = decisions)).copy(lastProgressTurn = 1)).nudges.single().line
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
    fun `refusal loop nudges on the second identical refusal and ends the turn on the third`() {
        val masked = RefusalSignature.of("run.run", """{"argv":["make"]}""", "run.run is not available to the plan role in this cell (any turn)")
        val other = RefusalSignature.of("run.run", """{"argv":["make"]}""", "the loop gate ended the last turn")
        val reports = turns(
            state(1).copy(refusals = listOf(masked)),
            state(2).copy(refusals = listOf(masked, other, masked)),
            state(3).copy(refusals = listOf(masked, other, masked, masked)),
        )
        assertEquals(listOf(0, 1, 0), reports.map { it.nudges.count { n -> n.key.gate == Gates.REFUSAL_LOOP } })
        assertEquals(
            "refusal loop: run.run was refused 2 times for the same reason — change the call or end with state(blocked), task.ask or task.propose",
            reports[1].nudges.single { it.key.gate == Gates.REFUSAL_LOOP }.line,
        )
        val ended = reports[2].rejections.single()
        assertEquals(Gates.REFUSAL_LOOP, ended.key.gate)
        assertTrue(ended.endsTurn)
        assertEquals(null, ended.requiredOp)
        assertEquals("refusal loop: run.run refused 3 times — the cell ends blocked", ended.line)
        assertEquals(emptyList(), gates.evaluate(state(4)).outcomes.filter { it.key.gate == Gates.REFUSAL_LOOP }, "no refusal since the last executed call")
        val names = gates.gates.map { it.name }
        assertEquals(names.indexOf(Gates.LOOP) + 1, names.indexOf(Gates.REFUSAL_LOOP), "registered right after the loop gate")
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
        // G6: a direct cell's refusal names the note; the key is the same.
        val note = gates.evaluate(state(2).copy(protocol = Protocol.Direct, patchRejection = Validation.Rejected("unknown open item", "open.close(4)", sizes))).rejections.single()
        assertEquals("register: note rejected — unknown open item: open.close(4)", note.line)
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
            override val name: String get() = "later"
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

    @Test
    fun `impact fires once per changed symbol, look refs or a rescoped plan resolves it, and the exit gate refuses while a public one is unresolved`() {
        val before = "def dispatch(req):\n    return req\n\ndef _helper(x):\n    return x\n".toByteArray()
        val after = "def dispatch(req, timeout):\n    return req\n\ndef _helper(x, y):\n    return x\n".toByteArray()
        val changes = DefinitionChanges.of("src/router.py", before, after)
        val ledger = ImpactNudges()
        ledger.changed(2, changes) { if (it.symbol == "dispatch") 6 else 1 }
        ledger.changed(3, changes) { if (it.symbol == "dispatch") 6 else 1 }
        assertEquals(listOf("dispatch", "_helper"), ledger.unresolved.map { it.definition.symbol })
        assertEquals(listOf(2, 2), ledger.unresolved.map { it.turn }, "a re-change of a pending symbol keeps its first turn")

        val done = register.copy(plan = listOf(Step(1, Mark.Done, "round half-up", accept = "AC-1", evidence = "rcpt-1")))
        val green = mapOf("CHK-accept-AC-1" to Currency("rcpt-1", Applicability.Current, eligible = true, green = true, reasons = emptyList()))
        fun at(turn: Int, proposed: Boolean = false) = state(turn, done).copy(
            completionProposed = proposed, currencies = green,
            impactNudges = ledger.unresolved, unresolvedImpactNudges = ledger.unresolvedPublic.map { it.missing },
        )
        val reports = turns(at(2), at(3))
        assertEquals(
            listOf(
                "impact: `dispatch` (src/router.py) signature changed; 6 references not inspected → look(refs) or scope the plan",
                "impact: `_helper` (src/router.py) signature changed; 1 reference not inspected → look(refs) or scope the plan",
            ),
            reports[0].nudges.filter { it.key.gate == Gates.IMPACT }.map { it.line },
        )
        assertTrue(reports[1].nudges.none { it.key.gate == Gates.IMPACT }, "once per changed symbol")

        val refused = assertIs<GateOutcome.Rejection>(gates.evaluate(at(4, proposed = true)).rejections.single())
        assertEquals(listOf("unresolved impact nudge: `dispatch` (src/router.py) signature changed; 6 references not inspected"), refused.details, "only a public definition binds the exit gate")

        ledger.inspected("Router.dispatch")
        ledger.rescoped(done, done.copy(plan = done.plan + Step(2, Mark.Todo, "adapt _helper callers")))
        assertEquals(emptyList(), ledger.unresolved)
        assertTrue(gates.evaluate(at(5, proposed = true)).outcomes.isEmpty(), "resolved: the exit gate passes")
    }

    @Test
    fun `impact nudges come only from code files and never for a symbol without a letter or digit`() {
        val css = "body {\n  margin: 0;\n}\n.app {\n  color: red;\n}\n".toByteArray()
        val readmeBefore = "# Todo\n\n```\nnpm run smoke\n```\n".toByteArray()
        val readmeAfter = "# Todo\n\n```bash\nnpm start\n```\n".toByteArray()
        val js = listOf(
            io.astrolabe.atlas.ChangedDefinition("public/app.js", "}", io.astrolabe.atlas.DeclarationKind.Other, io.astrolabe.atlas.DefinitionChange.Removed, false),
            io.astrolabe.atlas.ChangedDefinition("public/app.js", "api", io.astrolabe.atlas.DeclarationKind.Const, io.astrolabe.atlas.DefinitionChange.Removed, true),
        )
        val generic = DefinitionChanges.of("public/styles.css", css, null) + DefinitionChanges.of("README.md", readmeBefore, readmeAfter)
        assertTrue(generic.isNotEmpty(), "the generic outline reports changed 'definitions' for these files")
        val ledger = ImpactNudges()
        ledger.changed(2, generic + js) { 498 }

        assertEquals(listOf("public/app.js" to "api"), ledger.unresolved.map { it.definition.path to it.definition.symbol })
        assertEquals(emptyList(), ledger.overflow)
    }

    @Test
    fun `impact skips files the cell created and caps new nudges at three a turn, the rest summarised and never binding the exit`() {
        val before = (1..5).joinToString("") { "def f$it(x):\n    return x\n\n" }.toByteArray()
        val after = (1..5).joinToString("") { "def f$it(x, y):\n    return x\n\n" }.toByteArray()
        val scratch = DefinitionChanges.of("src/__probe__/old_env.py", "def env():\n    return 1\n\n\ndef name():\n    return 2\n".toByteArray(), null)
        assertEquals(2, scratch.size, "deleting the scratch file removes both definitions")
        val ledger = ImpactNudges()
        ledger.changed(2, DefinitionChanges.of("src/api.py", before, after) + scratch, created = setOf("src/__probe__/old_env.py")) { 426 }

        assertEquals(listOf("f1", "f2", "f3"), ledger.unresolved.map { it.definition.symbol }, "the cell's own scratch file raises nothing; three new nudges are kept")
        assertEquals(listOf("f4", "f5"), ledger.overflow.map { it.definition.symbol })
        val done = register.copy(plan = listOf(Step(1, Mark.Done, "round half-up", accept = "AC-1", evidence = "rcpt-1")))
        val green = mapOf("CHK-accept-AC-1" to Currency("rcpt-1", Applicability.Current, eligible = true, green = true, reasons = emptyList()))
        val turn2 = state(2, done).copy(currencies = green, impactNudges = ledger.unresolved, unresolvedImpactNudges = ledger.unresolvedPublic.map { it.missing }, impactOverflow = ledger.overflow)
        val lines = gates.evaluate(turn2).nudges.filter { it.key.gate == Gates.IMPACT }.map { it.line }
        assertEquals(4, lines.size, lines.toString())
        assertEquals("impact: … and 2 more: look(impact, src/api.py)", lines.last())

        val refused = assertIs<GateOutcome.Rejection>(gates.evaluate(turn2.copy(completionProposed = true)).rejections.single())
        assertEquals(listOf("f1", "f2", "f3"), refused.details.map { it.substringAfter('`').substringBefore('`') }, "summarised nudges are not exit obligations")

        ledger.changed(3, emptyList()) { 426 }
        assertEquals(emptyList(), ledger.overflow, "the overflow is the last batch's only")
    }

    @Test
    fun `the direct protocol changes gate wording only, never a predicate, and names no operation a shape can hide`() {
        val notes = Register.empty(ContextId("cell-1"), "I1", "fix rounding")
        val same = signature("def route(): ...")
        val masked = RefusalSignature.of("run.run", """{"argv":["make"]}""", "run.run is masked")
        val changes = DefinitionChanges.of("src/api.py", "def f(x):\n    return x\n".toByteArray(), "def f(x, y):\n    return x\n".toByteArray())
        val ledger = ImpactNudges().also { it.changed(2, changes) { 4 } }
        val cases = listOf(
            state(4, notes).copy(signatures = listOf(same, same, same)),
            state(1, notes).copy(increment = increment.copy(accept = listOf("AC-9")), calls = listOf(edit())),
            state(1, notes).copy(contextTokens = 70_000, contextMaxTokens = 100_000),
            state(1, notes).copy(contextTokens = 70_000, contextMaxTokens = 100_000, rebuilds = 1),
            state(6, notes).copy(lastProgressTurn = 1),
            state(2, notes).copy(refusals = listOf(masked, masked)),
            state(1, notes).copy(outsideIncrement = listOf("tests/test_a.py")),
            state(2, notes).copy(impactNudges = ledger.unresolved, impactOverflow = listOf(ledger.unresolved.single().copy(turn = 2))),
        )
        val structured = cases.map { gates.evaluate(it) }
        val direct = cases.map { gates.evaluate(it.copy(protocol = Protocol.Direct)) }
        for ((s, d) in structured.zip(direct)) {
            assertEquals(s.outcomes.map { it.key }, d.outcomes.map { it.key }, "the same predicates fire")
            assertEquals(s.rejections.map { it.endsTurn to it.requiredOp }, d.rejections.map { it.endsTurn to it.requiredOp }, "the same required op")
        }
        fun line(i: Int, gate: String) = direct[i].outcomes.single { it.key.gate == gate }.line
        assertEquals("loop: look.read returned the same result 3 times — turn ended; a note is required: state(note) what you learned, or end with state(blocked) or task(ask)", direct[0].rejections.single().line)
        assertEquals("entry: editing while acceptance AC-9 is not in contract v2 — name the command that will check the result and run it, or ask one question (task.ask)", line(1, Gates.ENTRY))
        assertTrue(line(2, Gates.PRESSURE).endsWith(" — record what matters with state(note); the harness rebuilds"), line(2, Gates.PRESSURE))
        assertTrue(line(3, Gates.PRESSURE).endsWith(" — second rebuild: the work continues in a fresh cell"), line(3, Gates.PRESSURE))
        assertEquals("stall: 5 turns without progress — zoom out · run the check · record a dead end · or surface the blocker (task.ask, state(blocked))", line(4, Gates.STALL))
        assertEquals("refusal loop: run.run was refused 2 times for the same reason — change the call or end with state(blocked) or task.ask", line(5, Gates.REFUSAL_LOOP))
        assertEquals("scope: tests/test_a.py outside the increment's write scope — justify each path in `why`", line(6, Gates.SCOPE))
        assertEquals(listOf("impact: `f` (src/api.py) signature changed; 4 references not inspected → look(refs)", "impact: … and 1 more in src/api.py → look(refs)"),
            direct[7].nudges.filter { it.key.gate == Gates.IMPACT }.map { it.line })
        val all = direct.flatMap { it.lines }
        assertTrue(all.none { "task.propose" in it || "STATE" in it || "plan" in it }, all.toString())
        assertEquals("loop: look.read returned the same result 3 times — turn ended; a state op is required", structured[0].rejections.single().line, "the structured bytes stay")
    }

    private val greenAc1 = mapOf("CHK-accept-AC-1" to Currency("rcpt-1", Applicability.Current, eligible = true, green = true, reasons = emptyList()))

    private fun hints(report: GateReport): List<String> = report.nudges.filter { it.key.gate == Gates.SUFFICIENCY }.map { it.line }

    @Test
    fun `the sufficiency hint fires once per cell when the increment's checks are green and nothing is left to close`() {
        val ready = state(2).copy(currencies = greenAc1, implementing = true)
        val (first, second) = turns(ready, ready.copy(turn = 3, lastProgressTurn = 2))
        assertEquals(listOf("evidence suffices: AC-1 green on this tree and nothing left to close — finish now; further checks are optional"), hints(first))
        assertEquals(emptyList(), hints(second), "once per cell")
        assertEquals(emptyList(), hints(gates.evaluate(state(2).copy(implementing = true))), "no receipt: the checks are not green yet")
        assertEquals(emptyList(), hints(gates.evaluate(ready.copy(implementing = false))), "only the cell that implements the increment")
        assertEquals(emptyList(), hints(gates.evaluate(ready.copy(completionProposed = true))), "the proposal is already made")
        val stale = mapOf("CHK-accept-AC-1" to greenAc1.getValue("CHK-accept-AC-1").copy(applicability = Applicability.Stale))
        assertEquals(emptyList(), hints(gates.evaluate(ready.copy(currencies = stale))), "green for another tree")
        val fullRed = Currency("rcpt-2", Applicability.Current, eligible = true, green = false, reasons = listOf("outcome failed"), red = true)
        assertEquals(emptyList(), hints(gates.evaluate(ready.copy(currencies = greenAc1 + ("CHK-full" to fullRed)))), "a red mandatory check is the agent's to close")
        val noted = register.copy(open = listOf(io.astrolabe.register.OpenItem(1, "CHK-full: 3 failures in the legacy module, tracked")))
        assertEquals(emptyList(), hints(gates.evaluate(ready.copy(register = noted, currencies = greenAc1 + ("CHK-full" to fullRed)))),
            "an Open item lets the exit pass a red mandatory check, never the hint call the evidence sufficient")
        assertEquals(
            listOf("evidence suffices: AC-1 green on this tree and nothing left to close — finish now; CHK-lint known red since receipt #7 (recorded by the runtime)"),
            hints(gates.evaluate(ready.copy(currencies = greenAc1 + ("CHK-lint" to fullRed.copy(mandatory = false, knownRed = "#7"))))),
            "a red optional check is the runtime's record, and the hint names it",
        )
        assertEquals(emptyList(), hints(gates.evaluate(ready.copy(unresolvedImpactNudges = listOf("public def total() changed; 3 importers unread")))))
        val sniffed = contract.copy(acceptance = listOf(Acceptance.Run("AC-1", Command(listOf("pytest", "-q")), Origin.Harness, scope = "touched")))
        assertEquals(emptyList(), hints(gates.evaluate(ready.copy(contract = sniffed))), "regression obligations alone never say finish now (WD-20)")
    }

    @Test
    fun `no sufficiency hint in a cell an exit refusal or a rework decision already spoke to`() {
        val ready = state(4).copy(currencies = greenAc1, implementing = true)
        val refused = ready.copy(fired = setOf(GateKey(Gates.EXIT, "v3:1a2b3c4d")))
        assertEquals(emptyList(), hints(gates.evaluate(refused)), "after an exit refusal")
        assertEquals(emptyList(), hints(gates.evaluate(ready.copy(reworked = true))), "in a cell that continues a rework decision")
        assertEquals(1, hints(gates.evaluate(ready)).size)
    }

    @Test
    fun `the sufficiency hint names the review the proposal obtains, once the plan is closed`() {
        val reviewed = contract.copy(acceptance = contract.acceptance + Acceptance.Review("AC-2", "rounding matches the finance policy", Origin.User))
        val both = increment.copy(accept = listOf("AC-1", "AC-2"))
        val open = state(2).copy(contract = reviewed, increment = both, currencies = greenAc1, implementing = true)
        assertEquals(emptyList(), hints(gates.evaluate(open)), "acceptance is not proven yet, so the open step is the agent's to close")
        val closed = open.copy(register = register.copy(plan = listOf(Step(1, Mark.Done, "round half-up", evidence = "#12"))))
        assertEquals(listOf("evidence suffices: AC-1 green on this tree and nothing left to close — finish now; the review of AC-2 follows the proposal"), hints(gates.evaluate(closed)))
    }
}
