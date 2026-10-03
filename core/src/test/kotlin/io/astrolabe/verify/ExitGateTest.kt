package io.astrolabe.verify

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.GateState
import io.astrolabe.cell.Gates
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Ledger
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.evidence.Closure
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.register.Mark
import io.astrolabe.register.OpenItem
import io.astrolabe.register.Register
import io.astrolabe.register.Step
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D-337 (§8.7): the acceptance resolver — one case per cell of the two result tables (run items by receipt currency,
 * check/review items by verdict), the resolution order, decisions, provenance (I7), and the verifier that binds an
 * accepted completion to its stamps. Only an executed red check or a substantive rejection is "not done" (I2);
 * everything the harness cannot establish awaits a decision (I1).
 */
class ExitGateTest {
    private val contract = Contract(
        workId = WorkId("W-1"), version = 2, attemptId = AttemptId("a1"), mode = Mode.Autonomous, shape = Shape.S0,
        requests = listOf(UserRequest("U1", Instant.EPOCH, "fix rounding")),
        requirements = listOf(Requirement("R1", "total rounds half-up", listOf("AC-1", "AC-2", "AC-3"), authorityRef = "U1")),
        acceptance = listOf(
            Acceptance.Run("AC-1", Command(listOf("pytest", "-q")), Origin.Harness, scope = "touched"),
            Acceptance.Check("AC-2", "no public signature change in src/api/", Origin.User),
            Acceptance.Review("AC-3", "rounding matches the finance policy", Origin.User),
        ),
        constraints = emptyList(), exclusions = emptyList(), contractsTouched = emptyList(),
        scope = Scope(listOf("src/"), listOf("migrations/")), budget = Budget.of(Defaults(), Tokens(100_000)),
        authorization = Authorization(Stage.Patch, DClassPolicy.Ask, "workspace-local-test-only"),
    )
    private val increment = Increment("I1", listOf("R1"), accept = listOf("AC-1", "AC-2", "AC-3"), writeScope = listOf("src/"), expectedFiles = 1)
    private val done = Register.empty(ContextId("cell-1"), "I1", "fix rounding").copy(plan = listOf(Step(1, Mark.Done, "round half-up", evidence = "#12")))
    private val s8 = CandidateId(Digest.ofUtf8("s8"))
    private val s9 = CandidateId(Digest.ofUtf8("s9"))
    private val env = Digest.ofUtf8("env")

    private fun green(receipt: String = "rcpt-1") = Currency(receipt, Applicability.Current, eligible = true, green = true, reasons = emptyList())
    private fun stale(receipt: String = "rcpt-1") = Currency(receipt, Applicability.Stale, eligible = true, green = true, reasons = listOf("candidate moved @s8 → @s9 (no reuse proof)"))
    private fun red(receipt: String = "rcpt-2") = Currency(receipt, Applicability.Current, eligible = true, green = false, reasons = listOf("outcome failed"), red = true)
    private fun notGreen(outcome: String, receipt: String = "rcpt-5") = Currency(receipt, Applicability.Current, eligible = true, green = false, reasons = listOf("outcome $outcome"), red = false)
    private val approve = Verdict("rev-1", 2, s9, VerdictOutcome.Approve, confidence = 0.9, signedBy = "human:alice")
    private val major = Finding(Severity.Major, "src/total.py:12", "rounds half-even, not half-up", kind = FindingKind.Correctness)
    private val revise = approve.copy(outcome = VerdictOutcome.Revise, findings = listOf(major))

    private fun resolve(
        register: Register = done,
        currencies: Map<String, Currency> = mapOf("CHK-accept-AC-1" to green()),
        verdicts: Map<String, Verdict> = mapOf("AC-2" to approve, "AC-3" to approve),
        unavailable: Map<String, String> = emptyMap(),
        flags: List<TestIntegrityFlag> = emptyList(),
        nudges: List<String> = emptyList(),
        decision: DecisionRecord? = null,
        reworkSpent: Boolean = false,
    ) = Resolver.increment(register, contract, increment, currencies, verdicts, unavailable, flags, nudges, s9, decision, reworkSpent)

    private fun decision(kind: DecisionKind, obligations: List<String>, decider: Decider = Decider.User, spent: Boolean = false, reason: String = "checked it by hand") =
        DecisionRecord("dec-1", "I1", AcceptanceDecision("ask-1", 2, s9, kind, decider, if (decider == Decider.User) "user:local" else "studio:policy(auto)", reason), obligations, spent)

    private fun result(resolved: Resolved, id: String): ObligationResult = resolved.results.single { it.obligation == id }

    // --------------------------------------------------------------- table 1: run items by their receipt

    @Test
    fun `a run item passes only on a current eligible green receipt and fails only on a current eligible red one`() {
        val passed = result(resolve(), "AC-1")
        assertEquals(ResultStatus.Passed, passed.status)
        assertEquals("rcpt-1", passed.evidenceRef)
        assertEquals(ResultStatus.Passed, result(resolve(currencies = mapOf("AC-1" to green("rcpt-7"))), "AC-1").status, "currencies may be keyed by acceptance id")
        val failed = result(resolve(currencies = mapOf("CHK-accept-AC-1" to red())), "AC-1")
        assertEquals(ResultStatus.Failed, failed.status)
        assertTrue(failed.executedFailure)
        assertEquals("AC-1: run: pytest -q (scope touched) — outcome failed", failed.detail)
    }

    @Test
    fun `every other run receipt is unverified - none, stale, stale red, ineligible, timed out, unavailable, inconclusive`() {
        val cases = mapOf(
            "no receipt" to null,
            "stale green" to stale(),
            "stale red" to red().copy(applicability = Applicability.Stale),
            "ineligible" to Currency("rcpt-3", Applicability.Current, eligible = false, green = true, reasons = listOf("inputs moved during the check: src/a.py")),
            "ineligible red" to red().copy(eligible = false),
            "timeout" to notGreen("timeout"),
            "unavailable" to notGreen("unavailable"),
            "inconclusive" to notGreen("inconclusive"),
        )
        for ((name, currency) in cases) {
            val resolved = resolve(currencies = currency?.let { mapOf("CHK-accept-AC-1" to it) } ?: emptyMap())
            assertEquals(ResultStatus.Unverified, result(resolved, "AC-1").status, name)
            assertEquals(Resolution.Await, resolved.resolution, "$name never refuses (I1)")
            assertEquals(StopCode.AcceptanceDecision, resolved.code, name)
        }
        assertEquals("AC-1: run: pytest -q (scope touched) — no receipt", result(resolve(currencies = emptyMap()), "AC-1").detail)
    }

    // ------------------------------------------------------ table 2: check and review items by verdict

    @Test
    fun `a reviewer's approval passes, a rejection with a blocker or major finding fails`() {
        assertEquals(ResultStatus.Passed, result(resolve(), "AC-2").status)
        assertEquals("human:alice", result(resolve(), "AC-3").by)
        val rejected = resolve(verdicts = mapOf("AC-2" to approve, "AC-3" to revise))
        val failed = result(rejected, "AC-3")
        assertEquals(ResultStatus.Failed, failed.status)
        assertTrue(failed.reviewFailure)
        assertEquals(listOf(major), failed.findings)
        assertEquals(Resolution.Rework, rejected.resolution)
        assertEquals(listOf(failed), rejected.rejections)
        val blocker = revise.copy(outcome = VerdictOutcome.Reject, findings = listOf(major.copy(severity = Severity.Blocker)))
        assertEquals(ResultStatus.Failed, result(resolve(verdicts = mapOf("AC-2" to blocker, "AC-3" to approve)), "AC-2").status)
    }

    @Test
    fun `no verdict, insufficient evidence, a rejection without substance or another revision or candidate is unverified`() {
        val cases = mapOf(
            "no verdict" to null,
            "insufficient evidence" to approve.copy(outcome = VerdictOutcome.InsufficientEvidence, missingCriterion = "program output"),
            "minor findings only" to revise.copy(findings = listOf(major.copy(severity = Severity.Minor))),
            "no location" to revise.copy(findings = listOf(major.copy(location = " "))),
            "no findings" to revise.copy(findings = emptyList()),
            "another revision" to approve.copy(contractRevision = 1),
            "another candidate" to approve.copy(reviewedCandidate = s8),
        )
        for ((name, verdict) in cases) {
            val resolved = resolve(verdicts = listOfNotNull(verdict?.let { "AC-3" to it }).toMap() + ("AC-2" to approve), unavailable = if (verdict == null) mapOf("AC-3" to "the reviewer gave no verdict") else emptyMap())
            assertEquals(ResultStatus.Unverified, result(resolved, "AC-3").status, name)
            assertEquals(Resolution.Await, resolved.resolution, name)
        }
        assertEquals("AC-3: review: rounding matches the finance policy — the reviewer gave no verdict", result(resolve(verdicts = mapOf("AC-2" to approve), unavailable = mapOf("AC-3" to "the reviewer gave no verdict")), "AC-3").detail)
    }

    // -------------------------------------------------------------------------- resolution order

    @Test
    fun `an executed red check is rework whatever is decided (I2, 8-8)`() {
        val red = mapOf("CHK-accept-AC-1" to red())
        assertEquals(Resolution.Rework, resolve(currencies = red).resolution)
        val accepted = resolve(currencies = red, decision = decision(DecisionKind.Accept, listOf("AC-1", "AC-2", "AC-3")))
        assertEquals(Resolution.Rework, accepted.resolution, "a decision never overrides a failed required check")
        assertEquals(GapKind.Failed, accepted.gaps.single().kind)
        assertTrue(runCatching {
            AcceptanceDecisionRequest("ask-1", 2, Identities(WorkId("W-1"), AttemptId("a1")), "I1", s9, StopCode.AcceptanceDecision,
                listOf(DecisionItem("AC-1", ObligationKind.Run, ResultStatus.Failed, "red")))
        }.isFailure, "an executed red check is never put to a decision")
    }

    @Test
    fun `what the agent must close is rework - steps, red lines without Open, flags, nudges`() {
        val pending = done.copy(plan = done.plan + Step(2, Mark.Cursor, "update call sites") + Step(3, Mark.Todo, "docs"))
        val steps = resolve(register = pending, verdicts = mapOf("AC-2" to approve), unavailable = mapOf("AC-3" to "the reviewer gave no verdict"))
        assertEquals(Resolution.Rework, steps.resolution)
        assertEquals(
            listOf("step 2 [>] 'update call sites' has no disposition (done, cancelled or an explicit non-completed exit)", "step 3 [ ] 'docs' has no disposition (done, cancelled or an explicit non-completed exit)"),
            steps.missing.take(2),
        )
        // C1b: a mandatory check outside the increment's items (here the campaign gate's full suite) keeps the I2 rule.
        val fullRed = mapOf("CHK-accept-AC-1" to green(), "CHK-full" to red("rcpt-9"))
        assertEquals(listOf("CHK-full is red without an Open item naming it"), resolve(currencies = fullRed).missing)
        assertEquals(Resolution.Complete, resolve(register = done.copy(open = listOf(OpenItem(1, "CHK-full: 3 failures in the legacy module, tracked"))), currencies = fullRed).resolution)
        assertEquals(Resolution.Rework, resolve(register = done.copy(open = listOf(OpenItem(1, "CHK-full tracked", closed = true))), currencies = fullRed).resolution, "a closed item is no longer open")
        val waived = done.copy(open = listOf(OpenItem(1, "AC-1 red: CHK-accept-AC-1 fails on the legacy path, tracked")))
        assertEquals(Resolution.Rework, resolve(register = waived, currencies = mapOf("CHK-accept-AC-1" to red())).resolution, "recording a required failure in Open does not waive it")
        assertEquals(listOf("unresolved impact nudge: public def total() changed; 3 importers unread"), resolve(nudges = listOf("public def total() changed; 3 importers unread")).missing)
    }

    @Test
    fun `a test-integrity flag needs a justification from the agent and an approving review or a decision`() {
        val flag = TestIntegrityFlag("tests/test_total.py", AcceptanceSurface.TestFile, "edit #3", listOf("CHK-accept-AC-1"))
        val bare = resolve(flags = listOf(flag))
        assertEquals(Resolution.Rework, bare.resolution)
        assertEquals(
            listOf("acceptance surface tests/test_total.py touches CHK-accept-AC-1 without a recorded justification",
                "integrity:tests/test_total.py: acceptance surface tests/test_total.py touches CHK-accept-AC-1 — no approving review of the change to a required check"),
            bare.missing,
        )
        val justified = resolve(flags = listOf(flag.copy(reason = "asserted the old rounding")))
        assertEquals(Resolution.Await, justified.resolution, "a missing approval is unverified, not a refusal")
        assertEquals(ResultStatus.Unverified, result(justified, "integrity:tests/test_total.py").status)
        assertEquals(Resolution.Complete, resolve(flags = listOf(flag.copy(reason = "asserted the old rounding", verdict = approve))).resolution)
        assertEquals(Resolution.Complete, resolve(flags = listOf(flag.copy(requiredChecks = emptyList()))).resolution, "a flag off the required set is visible, not blocking")
        assertEquals(Resolution.Await, resolve(flags = listOf(flag.copy(reason = "r", verdict = approve.copy(reviewedCandidate = s8)))).resolution, "an approval of another candidate never certifies this one")
        val accepted = resolve(flags = listOf(flag.copy(reason = "r")), decision = decision(DecisionKind.Accept, listOf("integrity:tests/test_total.py")))
        assertEquals(Resolution.Complete, accepted.resolution)
    }

    @Test
    fun `a reviewer's rejection is reworked once, then goes to the authority, who may accept over it (I4)`() {
        val verdicts = mapOf("AC-2" to approve, "AC-3" to revise)
        assertEquals(Resolution.Rework, resolve(verdicts = verdicts).resolution)
        val spent = resolve(verdicts = verdicts, reworkSpent = true)
        assertEquals(Resolution.Await, spent.resolution)
        assertEquals(StopCode.ReviewRejected, spent.code)
        val respent = resolve(verdicts = verdicts).spent()
        assertEquals(Resolution.Await to StopCode.ReviewRejected, respent.resolution to respent.code, "the last round re-resolves the same inputs as spent")
        assertEquals(listOf("AC-3"), spent.undecided.map { it.obligation })
        val accepted = resolve(verdicts = verdicts, decision = decision(DecisionKind.Accept, listOf("AC-3")))
        assertEquals(Resolution.Complete, accepted.resolution, "the decider's word is stronger than a review result")
        val provenance = accepted.provenance.single { it.item == "AC-3" }
        assertEquals(ProvenanceKind.Accepted, provenance.how)
        assertEquals("user:local", provenance.by)
        assertEquals(Decider.User, provenance.decider)
        assertEquals("checked it by hand", provenance.reason)
    }

    @Test
    fun `unverified obligations await a decision that covers exactly what its request listed`() {
        val none = mapOf("AC-2" to approve)
        val waiting = resolve(verdicts = none)
        assertEquals(Resolution.Await, waiting.resolution)
        assertEquals(StopCode.AcceptanceDecision, waiting.code)
        assertEquals(listOf("AC-3"), waiting.undecided.map { it.obligation })
        assertEquals(Resolution.Complete, resolve(verdicts = none, decision = decision(DecisionKind.Accept, listOf("AC-3"), Decider.Policy)).resolution)
        assertEquals(Resolution.Await, resolve(verdicts = none, decision = decision(DecisionKind.Accept, listOf("AC-2"))).resolution, "a decision covers only what it was asked")
        assertEquals(Resolution.Await, resolve(verdicts = none, decision = decision(DecisionKind.Accept, listOf("AC-3"), spent = true)).resolution, "a spent decision covers nothing")
        val rework = resolve(verdicts = none, decision = decision(DecisionKind.Rework, listOf("AC-3"), reason = "the rounding table is missing"))
        assertEquals(Resolution.Rework, rework.resolution)
        assertEquals("rework requested by user:local: the rounding table is missing", rework.missing.last())
    }

    @Test
    fun `provenance records per item whether it was tested, reviewed or accepted, and by whom (I7)`() {
        val mixed = resolve(verdicts = mapOf("AC-2" to approve), decision = decision(DecisionKind.Accept, listOf("AC-3"), Decider.Policy, reason = "cannot verify a browser action"))
        assertEquals(Resolution.Complete, mixed.resolution)
        assertEquals(
            mapOf("AC-1" to ProvenanceKind.Tested, "AC-2" to ProvenanceKind.Reviewed, "AC-3" to ProvenanceKind.Accepted),
            mixed.provenance.associate { it.item to it.how },
        )
        val accepted = mixed.provenance.single { it.item == "AC-3" }
        assertEquals(Decider.Policy, accepted.decider)
        assertEquals("cannot verify a browser action", accepted.reason)
        assertEquals(listOf("rcpt-1"), mixed.receiptIds)
        assertEquals(listOf("rcpt-1", "rev-1", "ask-1"), mixed.evidenceRefs)
        assertEquals(mixed.decision?.id, "dec-1")
        assertNull(resolve().decision, "nothing applied when every item is verified")
    }

    @Test
    fun `a policy decision covers what could not be verified, never a reviewer's rejection (D-338)`() {
        val verdicts = mapOf("AC-2" to approve, "AC-3" to revise)
        assertEquals(Resolution.Await, resolve(verdicts = verdicts, reworkSpent = true, decision = decision(DecisionKind.Accept, listOf("AC-3"), Decider.Policy)).resolution)
        assertEquals(Resolution.Complete, resolve(verdicts = verdicts, reworkSpent = true, decision = decision(DecisionKind.Accept, listOf("AC-3"))).resolution)
    }

    @Test
    fun `after the rework round what the agent left open goes to the decider, never a failure (I2)`() {
        val open = done.copy(plan = done.plan + Step(2, Mark.Todo, "docs"))
        val nudge = listOf("public def total() changed; 3 importers unread")
        assertEquals(Resolution.Rework, resolve(register = open, nudges = nudge).resolution)
        val spent = resolve(register = open, nudges = nudge, reworkSpent = true)
        assertEquals(Resolution.Await, spent.resolution)
        assertEquals(listOf("open:1"), spent.undecided.map { it.obligation })
        assertEquals(Resolution.Complete, resolve(register = open, nudges = nudge, reworkSpent = true, decision = decision(DecisionKind.Accept, listOf("open:1"))).resolution)
        val moved = Verifier().accept(CompletionProposal("I1", "done", 2, s8, s8, null, env), contract, increment, done, Ledger.initial(contract), s9,
            mapOf("CHK-accept-AC-1" to green()), mapOf("AC-2" to approve, "AC-3" to approve), reworkSpent = true)
        assertIs<CompletionResult.Refused>(moved, "a proposal about another tree is never put to a decider")
    }

    @Test
    fun `open plan steps do not block an exit whose acceptance is proven, and stay visible (D-368)`() {
        val open = done.copy(plan = done.plan + Step(2, Mark.Cursor, "update call sites") + Step(3, Mark.Todo, "docs"))
        val proven = resolve(register = open)
        assertEquals(Resolution.Complete, proven.resolution)
        assertEquals(emptyList(), proven.missing)
        assertEquals(listOf("step 2 'update call sites' left open by the agent", "step 3 'docs' left open by the agent"), proven.leftOpen)
        val accepted = assertIs<CompletionResult.Accepted>(Verifier().accept(CompletionProposal("I1", "done", 2, s8, s9, null, env), contract, increment, open, Ledger.initial(contract), s9,
            mapOf("CHK-accept-AC-1" to green()), mapOf("AC-2" to approve, "AC-3" to approve)))
        assertEquals(proven.leftOpen, accepted.leftOpen)

        val unverified = resolve(register = open, verdicts = mapOf("AC-2" to approve), unavailable = mapOf("AC-3" to "the reviewer gave no verdict"))
        assertEquals(Resolution.Rework, unverified.resolution, "an unproven acceptance keeps an unticked step a gap")
        assertTrue(unverified.missing.any { it.startsWith("step 3 [ ] 'docs' has no disposition") }, unverified.missing.toString())
        assertEquals(emptyList(), unverified.leftOpen)

        val failed = resolve(register = open, currencies = mapOf("CHK-accept-AC-1" to red()))
        assertEquals(Resolution.Rework, failed.resolution)
        assertEquals(3, failed.gaps.size, failed.missing.toString())
        assertEquals(emptyList(), failed.leftOpen)

        val lintRed = resolve(register = open, currencies = mapOf("CHK-accept-AC-1" to green(), "CHK-lint" to red("rcpt-9")))
        assertTrue(lintRed.missing.any { it.contains("has no disposition") }, "a red check outside the required set keeps steps gaps")
        val flag = TestIntegrityFlag("tests/test_total.py", AcceptanceSurface.TestFile, "edit #3", listOf("CHK-accept-AC-1"), reason = "renamed fixture")
        assertTrue(resolve(register = open, flags = listOf(flag)).missing.any { it.contains("has no disposition") }, "an unreviewed integrity flag keeps steps gaps")
        assertEquals(emptyList(), resolve(register = open, flags = listOf(flag.copy(verdict = approve))).missing, "a justified, approved flag is proven")
    }

    @Test
    fun `a stale red optional check is history, not a red line (D-337)`() {
        val staleRed = mapOf("CHK-accept-AC-1" to green(), "CHK-lint" to red("rcpt-9").copy(applicability = Applicability.Stale))
        assertEquals(Resolution.Complete, resolve(currencies = staleRed).resolution)
    }

    // ------------------------------------------------------------ C1b: the runtime records a red optional check

    @Test
    fun `only the model's checks, lint and declared checks no item requires are optional, every check the harness runs keeps the old rule`() {
        val commands = RunnerCommands(test = Command(listOf("pytest")), lint = Command(listOf("ruff", "check")), typecheck = Command(listOf("mypy")))
        val checks = Checks.seed(contract, commands, qualityGates = listOf(Command(listOf("make", "audit"))))
        val mandatory = checks.all().associate { it.id to Obligations.mandatory(it) }
        assertEquals(
            mapOf(Checks.TYPES_TOUCHED to true, Checks.LINT to false, "CHK-accept-AC-1" to true, Checks.FULL to true, Checks.QUALITY_GATE to true),
            mandatory,
        )
        val pytest = Command(listOf("pytest", "tests/"))
        fun check(id: String, kind: CheckKind, origin: Origin?, trigger: Trigger = Trigger.OnDemand, selector: Selector = Selector.Named(pytest)) =
            Check(id, kind, selector, Closure.Unknown, CostClass.Slow, trigger, command = pytest, origin = origin)
        assertTrue(Obligations.mandatory(check(Checks.TESTS_BLAST, CheckKind.Unit, null, Trigger.StepBoundary, Selector.Blast)), "the blast radius")
        assertFalse(Obligations.mandatory(Checks.modelCheck(Command(listOf("pytest", "-q")), io.astrolabe.evidence.EvidenceKind.Tests, "R1")), "the model's own check")
        assertFalse(Obligations.mandatory(check("CHK-user-smoke", CheckKind.Unit, Origin.User)), "a user's check no item requires")
        assertFalse(Obligations.mandatory(check("CHK-host-smoke", CheckKind.Unit, Origin.Amended(3))), "a host's amendment no item requires")
        assertTrue(Obligations.mandatory(check("CHK-user-gate", CheckKind.Quality, Origin.User)), "a quality gate, whatever its trigger")
        assertTrue(Obligations.mandatory(check("CHK-user-suite", CheckKind.Full, Origin.User)), "a full suite")
        assertTrue(Obligations.mandatory(check("CHK-sniffed", CheckKind.Unit, Origin.Harness)), "the harness's own")
        assertTrue(Obligations.mandatory(check("CHK-unknown", CheckKind.Unit, null)), "a check of unknown origin")
    }

    @Test
    fun `a regression the blast radius or the types of touched files catches is rework as before, unless Open records it`() {
        // S0: AC-1 runs tests/test_a.py; the change breaks tests/test_b.py, which only the blast radius runs.
        for (id in listOf(Checks.TESTS_BLAST, Checks.TYPES_TOUCHED)) {
            val regression = resolve(currencies = mapOf("CHK-accept-AC-1" to green(), id to red("rcpt-9")))
            assertEquals(Resolution.Rework to listOf("$id is red without an Open item naming it"), regression.resolution to regression.missing, id)
            assertEquals(emptyList(), regression.knownRed)
            assertEquals(Resolution.Complete, resolve(register = done.copy(open = listOf(OpenItem(1, "$id red: tests/test_b.py, tracked"))), currencies = mapOf("CHK-accept-AC-1" to green(), id to red("rcpt-9"))).resolution)
        }
    }

    @Test
    fun `a new failure against the baseline is rework whatever Open says, an inherited one is no gap, an unclassified one keeps the Open rule while current`() {
        val noted = done.copy(open = listOf(OpenItem(1, "CHK-tests-blast CHK-types-touched red: tracked")))
        for (id in listOf(Checks.TESTS_BLAST, Checks.TYPES_TOUCHED)) {
            fun held(hold: RegressionHold) = red("rcpt-9").copy(hold = hold)
            val regression = held(RegressionHold(listOf("#9"), listOf("rcpt-9"), "pytest tests/test_b.py", regressions = listOf("tests/test_b.py::test_b — assert 2 == 1 (did not fail on s0)")))
            for (register in listOf(done, noted)) {
                val resolved = resolve(register = register, currencies = mapOf("CHK-accept-AC-1" to green(), id to regression))
                assertEquals(Resolution.Rework, resolved.resolution, id)
                // The instruction and the command come first, so a cut line still says what to do.
                assertTrue(resolved.missing.single().startsWith("$id: fix and rerun `pytest tests/test_b.py` — new failures against the baseline at s0, which no Open item clears: tests/test_b.py::test_b"), resolved.missing.toString())
                assertEquals(GapKind.Failed, resolved.gaps.single().kind, "an executed red check: no decision covers it")
            }
            // Inherited only: complete without an Open item; the finish receipt discloses it.
            val inherited = held(RegressionHold(listOf("#9"), listOf("rcpt-9"), "pytest", inherited = listOf("tests/test_c.py::test_c — assert 0 (unchanged since baseline rcpt-3 on s0)")))
            assertEquals(Resolution.Complete to emptyList<String>(), resolve(currencies = mapOf("CHK-accept-AC-1" to green(), id to inherited)).let { it.resolution to it.missing })
            assertEquals(listOf("$id: pre-existing failure tests/test_c.py::test_c — assert 0 (unchanged since baseline rcpt-3 on s0)"), Obligations.disclosure(id, inherited.hold!!))
            // Unclassified and current: the D-400 rule — an Open item, which the acceptance then acknowledges.
            val unclassified = held(RegressionHold(listOf("#9"), listOf("rcpt-9"), "pytest", unclassified = listOf("x — y (${Regressions.NO_BASELINE})")))
            assertEquals(listOf("$id is red without an Open item naming it"), resolve(currencies = mapOf("CHK-accept-AC-1" to green(), id to unclassified)).missing)
            val acknowledged = resolve(register = noted, currencies = mapOf("CHK-accept-AC-1" to green(), id to unclassified))
            assertEquals(Resolution.Complete to listOf("rcpt-9"), acknowledged.resolution to acknowledged.acknowledged)
            // An earlier acceptance's acknowledgment of the same receipt stands for every later resolution (D-337), registers aside.
            assertEquals(Resolution.Complete, Resolver.increment(Register.empty(ContextId("cell-final"), "I1", "fix rounding"), contract, increment,
                mapOf("CHK-accept-AC-1" to green(), id to unclassified), mapOf("AC-2" to approve, "AC-3" to approve), acknowledged = listOf("rcpt-9")).resolution)
            // Not current (a green run on this tree did not execute it): no gap, disclosed, the class stays unverified (FinishReceipt).
            val stale = held(RegressionHold(listOf("#9"), emptyList(), "pytest", unclassified = listOf("x: failed in rcpt-9, not executed on this tree (removed, skipped or renamed)")))
            assertEquals(Resolution.Complete, resolve(currencies = mapOf("CHK-accept-AC-1" to green(), id to stale)).resolution)
            assertEquals(listOf("$id: failure not classified (x: failed in rcpt-9, not executed on this tree (removed, skipped or renamed))"), Obligations.disclosure(id, stale.hold!!))
            // Without the scheduler's record, a current eligible red is unclassified; a stale or ineligible one holds nothing.
            assertEquals(RedClass.Unclassified, Obligations.hold(id, red("rcpt-9"))?.kind)
            assertNull(Obligations.hold(id, red("rcpt-9").copy(applicability = Applicability.Stale)))
            assertNull(Obligations.hold(id, red("rcpt-9").copy(eligible = false)))
        }
        // Not a harness regression check: the full suite keeps the D-400 rule, a hold on it changes nothing.
        val full = red("rcpt-9").copy(hold = RegressionHold(listOf("#9"), listOf("rcpt-9"), "pytest", regressions = listOf("x (new)")))
        assertEquals(Resolution.Complete, resolve(register = done.copy(open = listOf(OpenItem(1, "CHK-full red: tracked"))), currencies = mapOf("CHK-accept-AC-1" to green(), Checks.FULL to full)).resolution)
        assertNull(Obligations.hold(Checks.FULL, full))
    }

    @Test
    fun `the verifier keeps what an acceptance acknowledged, so the final reacceptance answers as the increment's did`() {
        val unclassified = red("rcpt-9").copy(hold = RegressionHold(listOf("#9"), listOf("rcpt-9"), "pytest", unclassified = listOf("x — y (${Regressions.NO_BASELINE})")))
        val currencies = mapOf("CHK-accept-AC-1" to green(), Checks.TESTS_BLAST to unclassified)
        val proposal = CompletionProposal("I1", "done", 2, s8, s9, null, env)
        val noted = done.copy(open = listOf(OpenItem(1, "CHK-tests-blast red: tracked")))
        val accepted = assertIs<CompletionResult.Accepted>(Verifier().accept(proposal, contract, increment, noted, Ledger.initial(contract), s9, currencies, mapOf("AC-2" to approve, "AC-3" to approve)))
        assertEquals(listOf("rcpt-9"), accepted.acknowledged)
        // The final reacceptance has no register (Controller.reaccept): the acknowledgment is the record it reads.
        val empty = Register.empty(ContextId("cell-1"), "I1", "fix rounding")
        assertIs<CompletionResult.Refused>(Verifier().accept(proposal, contract, increment, empty, Ledger.initial(contract), s9, currencies, mapOf("AC-2" to approve, "AC-3" to approve)))
        assertIs<CompletionResult.Accepted>(Verifier().accept(proposal, contract, increment, empty, Ledger.initial(contract), s9, currencies, mapOf("AC-2" to approve, "AC-3" to approve),
            acknowledged = accepted.acknowledged))
    }


    @Test
    fun `a red optional check is the runtime's known red, never a gap, until the scheduler ends it`() {
        val known = "CHK-lint known red since receipt #12 (recorded by the runtime)"
        val lintRed = red("rcpt-9").copy(mandatory = false, knownRed = "#12")
        val red = resolve(currencies = mapOf("CHK-accept-AC-1" to green(), "CHK-lint" to lintRed))
        assertEquals(Resolution.Complete, red.resolution, red.missing.toString())
        assertEquals(listOf(known), red.knownRed, "no Open item is asked of the agent")
        // A timeout after the red does not end it (the scheduler keeps the record, SchedulerTest).
        assertEquals(listOf(known), resolve(currencies = mapOf("CHK-accept-AC-1" to green(), "CHK-lint" to notGreen("timeout").copy(mandatory = false, knownRed = "#12"))).knownRed)
        // Without the scheduler's history, a red receipt names itself.
        assertEquals(listOf("CHK-lint known red since receipt rcpt-9 (recorded by the runtime)"), resolve(currencies = mapOf("CHK-accept-AC-1" to green(), "CHK-lint" to red("rcpt-9").copy(mandatory = false))).knownRed)
        // A passed receipt on the tree now: no record.
        val green = resolve(currencies = mapOf("CHK-accept-AC-1" to green(), "CHK-lint" to green("rcpt-11").copy(mandatory = false)))
        assertEquals(Resolution.Complete to emptyList<String>(), green.resolution to green.knownRed)
        // The model's prefix frees nothing by itself: only an optional currency is the runtime's record.
        val model = resolve(currencies = mapOf("CHK-accept-AC-1" to green(), "CHK-model-1a2b3c4d" to red("rcpt-10").copy(mandatory = false, knownRed = "#10")))
        assertEquals(Resolution.Complete to listOf("CHK-model-1a2b3c4d known red since receipt #10 (recorded by the runtime)"), model.resolution to model.knownRed)
        assertEquals(listOf("CHK-model-1a2b3c4d is red without an Open item naming it"), resolve(currencies = mapOf("CHK-accept-AC-1" to green(), "CHK-model-1a2b3c4d" to red("rcpt-10"))).missing)
        // A required red check is untouched: still the increment's failed result.
        val required = resolve(currencies = mapOf("CHK-accept-AC-1" to red().copy(mandatory = false)))
        assertEquals(Resolution.Rework to emptyList<String>(), required.resolution to required.knownRed)
        // The exit gate refuses nothing for the optional red check.
        val gate = Gates.s0().evaluate(GateState(turn = 3, register = done, contract = contract, increment = increment, completionProposed = true,
            currencies = mapOf("CHK-accept-AC-1" to green(), "CHK-lint" to lintRed), verdicts = mapOf("AC-2" to approve, "AC-3" to approve)))
        assertTrue(gate.rejections.none { it.key.gate == Gates.EXIT }, gate.lines.toString())
    }

    // ------------------------------------------------------------ one rule for gate and verifier (A1)

    @Test
    fun `the cell's exit gate refuses exactly the reworks the verifier refuses, and never an await`() {
        fun gate(currencies: Map<String, Currency>, verdicts: Map<String, Verdict>) = Gates.s0().evaluate(GateState(
            turn = 3, register = done, contract = contract, increment = increment, completionProposed = true, currencies = currencies, verdicts = verdicts,
        )).rejections.filter { it.key.gate == Gates.EXIT }
        val proposal = CompletionProposal("I1", "done", 2, s8, s9, null, env)
        val cases = listOf(
            mapOf("CHK-accept-AC-1" to green()) to mapOf("AC-2" to approve, "AC-3" to approve),
            mapOf("CHK-accept-AC-1" to red()) to mapOf("AC-2" to approve, "AC-3" to approve),
            mapOf("CHK-accept-AC-1" to notGreen("timeout")) to mapOf("AC-2" to approve, "AC-3" to approve),
            mapOf("CHK-accept-AC-1" to green()) to mapOf("AC-2" to approve, "AC-3" to revise),
            mapOf("CHK-accept-AC-1" to green()) to mapOf("AC-2" to approve),
        )
        for ((currencies, verdicts) in cases) {
            val verified = Verifier().accept(proposal, contract, increment, done, Ledger.initial(contract), s9, currencies, verdicts)
            val refused = gate(currencies, verdicts)
            when (verified) {
                is CompletionResult.Refused -> assertEquals(verified.missing, refused.single().details)
                is CompletionResult.Accepted, is CompletionResult.Pending -> assertTrue(refused.isEmpty(), "$verified")
                is CompletionResult.NotCompleted -> error("unexpected $verified")
            }
        }
    }

    // ------------------------------------------------------------------------------- the verifier

    @Test
    fun `the verifier binds stamps, updates the ledger only on acceptance, and turns repeated refusals into recovery`() {
        val verifier = Verifier(maxFinalizations = 2)
        val ledger = Ledger.initial(contract)
        val proposal = CompletionProposal("I1", "done", 2, baseStamp = s8, resultingStamp = s9, patchHash = Digest.ofUtf8("patch"), envId = env)
        val verdicts = mapOf("AC-2" to approve, "AC-3" to approve)

        val moved = assertIs<CompletionResult.Refused>(verifier.accept(proposal, contract, increment, done, ledger, stampNow = s8, currencies = mapOf("CHK-accept-AC-1" to green()), verdicts = verdicts))
        assertEquals("resulting stamp @${s9.hash8} is not the tree now @${s8.hash8}", moved.missing.first())
        assertEquals(1, moved.attempts)
        assertFalse(moved.recoveryDirected)
        assertEquals(RequirementStatus.Pending, ledger["R1"]!!.status, "the ledger is untouched on refusal")

        val again = assertIs<CompletionResult.Refused>(verifier.accept(proposal.copy(contractVersion = 1), contract, increment, done, ledger, s9, mapOf("CHK-accept-AC-1" to red()), verdicts))
        assertEquals(2, again.attempts)
        assertTrue(again.recoveryDirected, "the second unsupported finalization goes to gap-directed recovery, not an endless gate")

        val accepted = assertIs<CompletionResult.Accepted>(verifier.accept(proposal, contract, increment, done, ledger, s9, mapOf("CHK-accept-AC-1" to green()), verdicts))
        assertEquals(s8, accepted.baseStamp)
        assertEquals(s9, accepted.resultingStamp)
        assertEquals(Digest.ofUtf8("patch"), accepted.patchHash)
        assertEquals(env, accepted.envId)
        assertEquals(listOf("rcpt-1"), accepted.receiptIds)
        assertTrue(accepted.verified)
        assertEquals(RequirementStatus.Verified, accepted.ledger["R1"]!!.status)
        assertEquals(listOf("rcpt-1", "rev-1"), accepted.evidenceRefs)
        assertEquals(accepted.provenance, accepted.ledger["R1"]!!.provenance)
        assertTrue(accepted.ledger["R1"]!!.stampValid)
        assertEquals(RequirementStatus.Pending, ledger["R1"]!!.status, "the controller commits the returned ledger; the input is immutable")

        assertEquals(CompletionResult.NotCompleted(ExitKind.Blocked, "needs the finance policy"), verifier.accept(proposal.copy(claimedStatus = "blocked", reason = "needs the finance policy"), contract, increment, done, ledger, s9, emptyMap()))
        assertEquals(ExitKind.Waiting, assertIs<CompletionResult.NotCompleted>(verifier.accept(proposal.copy(claimedStatus = "waiting", reason = "full suite running"), contract, increment, done, ledger, s9, emptyMap())).kind)
        assertTrue(runCatching { verifier.accept(proposal.copy(claimedStatus = "verified"), contract, increment, done, ledger, s9, emptyMap()) }.isFailure, "the model cannot name a status the protocol does not have")
    }

    @Test
    fun `the verifier holds an unverified completion pending and applies only a decision for this candidate and contract`() {
        val proposal = CompletionProposal("I1", "done", 2, s8, s9, null, env)
        val currencies = mapOf("CHK-accept-AC-1" to notGreen("unavailable"))
        val verdicts = mapOf("AC-2" to approve, "AC-3" to approve)
        val pending = assertIs<CompletionResult.Pending>(Verifier().accept(proposal, contract, increment, done, Ledger.initial(contract), s9, currencies, verdicts))
        assertEquals(StopCode.AcceptanceDecision, pending.code)
        assertEquals(listOf("AC-1"), pending.resolved.undecided.map { it.obligation })
        val accept = decision(DecisionKind.Accept, listOf("AC-1"))
        val accepted = assertIs<CompletionResult.Accepted>(Verifier().accept(proposal, contract, increment, done, Ledger.initial(contract), s9, currencies, verdicts, decision = accept))
        assertFalse(accepted.verified)
        assertEquals(ProvenanceKind.Accepted, accepted.provenance.single { it.item == "AC-1" }.how)
        assertEquals(accept, accepted.decision)
        val elsewhere = accept.copy(decision = accept.decision.copy(candidate = s8))
        assertIs<CompletionResult.Pending>(Verifier().accept(proposal, contract, increment, done, Ledger.initial(contract), s9, currencies, verdicts, decision = elsewhere), "a decision about another tree is history")
        val amended = accept.copy(decision = accept.decision.copy(contractRevision = 1))
        assertIs<CompletionResult.Pending>(Verifier().accept(proposal, contract, increment, done, Ledger.initial(contract), s9, currencies, verdicts, decision = amended), "a decision about another contract version is history")
    }
}
