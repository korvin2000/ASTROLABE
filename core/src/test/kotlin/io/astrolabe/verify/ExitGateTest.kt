package io.astrolabe.verify

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
import io.astrolabe.contract.Ledger
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
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
import kotlin.test.assertTrue

/** P1.7.7: the exit gate lists exactly what is missing, `Open` never waives a required acceptance, the verifier binds stamps and leaves the ledger untouched on refusal. */
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
    private fun red(receipt: String = "rcpt-2") = Currency(receipt, Applicability.Current, eligible = true, green = false, reasons = listOf("outcome failed"))
    private val assessed = Assessment("AC-2", "no public signature change in src/api/", "#44", accepted = true, by = "user", contractVersion = 2)
    private val signed = Verdict("rev-1", 2, s9, VerdictOutcome.Approve, confidence = 0.9, signedBy = "human:alice")

    private fun evaluate(
        register: Register = done,
        currencies: Map<String, Currency> = mapOf("CHK-accept-AC-1" to green()),
        assessments: List<Assessment> = listOf(assessed),
        reviews: Map<String, Verdict> = mapOf("AC-3" to signed),
        flags: List<TestIntegrityFlag> = emptyList(),
        nudges: List<String> = emptyList(),
    ) = ExitGate.evaluate(register, contract, increment, currencies, assessments, reviews, flags, nudges)

    @Test
    fun `every obligation satisfied by evidence is accepted with the receipts that certify it`() {
        val accepted = assertIs<GateResult.Accepted>(evaluate())
        assertEquals(listOf("rcpt-1"), accepted.receiptIds)
        assertIs<GateResult.Accepted>(evaluate(currencies = mapOf("AC-1" to green("rcpt-7"))), "currencies may be keyed by acceptance id")
    }

    @Test
    fun `the refusal names exactly what is missing and an Open item never waives a required acceptance`() {
        val refused = assertIs<GateResult.Refused>(evaluate(currencies = mapOf("CHK-accept-AC-1" to stale())))
        assertEquals(listOf("AC-1: run: pytest -q (scope touched) — candidate moved @s8 → @s9 (no reuse proof)"), refused.missing)
        assertEquals(listOf("AC-1: run: pytest -q (scope touched) — no receipt"), assertIs<GateResult.Refused>(evaluate(currencies = emptyMap())).missing)
        assertEquals(listOf("AC-1: run: pytest -q (scope touched) — outcome failed"), assertIs<GateResult.Refused>(evaluate(currencies = mapOf("CHK-accept-AC-1" to red()))).missing)
        val ineligible = Currency("rcpt-3", Applicability.Current, eligible = false, green = true, reasons = listOf("inputs moved during the check: src/a.py"))
        assertEquals(listOf("AC-1: run: pytest -q (scope touched) — inputs moved during the check: src/a.py"), assertIs<GateResult.Refused>(evaluate(currencies = mapOf("CHK-accept-AC-1" to ineligible))).missing)

        val waived = done.copy(open = listOf(OpenItem(1, "AC-1 red: CHK-accept-AC-1 fails on the legacy path, tracked")))
        val still = assertIs<GateResult.Refused>(evaluate(register = waived, currencies = mapOf("CHK-accept-AC-1" to red())))
        assertEquals(1, still.missing.size, "recording a required failure in Open does not waive it")
    }

    @Test
    fun `check items need an accepted assessment of the stated criterion at this contract version`() {
        assertEquals(listOf("AC-2: check: no public signature change in src/api/ — no assessment recorded"), assertIs<GateResult.Refused>(evaluate(assessments = emptyList())).missing)
        assertEquals(listOf("AC-2: check: no public signature change in src/api/ — assessment does not name the stated criterion"), assertIs<GateResult.Refused>(evaluate(assessments = listOf(assessed.copy(criterion = "AC-2")))).missing)
        assertEquals(listOf("AC-2: check: no public signature change in src/api/ — assessment bound to another contract version"), assertIs<GateResult.Refused>(evaluate(assessments = listOf(assessed.copy(contractVersion = 1)))).missing)
        assertEquals(listOf("AC-2: check: no public signature change in src/api/ — assessment not accepted"), assertIs<GateResult.Refused>(evaluate(assessments = listOf(assessed.copy(accepted = false)))).missing)
    }

    @Test
    fun `review items need an approving signed verdict at this contract version`() {
        assertEquals(listOf("AC-3: review: rounding matches the finance policy — no signed review"), assertIs<GateResult.Refused>(evaluate(reviews = emptyMap())).missing)
        assertEquals(listOf("AC-3: review: rounding matches the finance policy — review signed for contract v1, not v2"), assertIs<GateResult.Refused>(evaluate(reviews = mapOf("AC-3" to signed.copy(contractRevision = 1)))).missing)
        assertEquals(listOf("AC-3: review: rounding matches the finance policy — review revise by human:alice"), assertIs<GateResult.Refused>(evaluate(reviews = mapOf("AC-3" to signed.copy(outcome = VerdictOutcome.Revise)))).missing)
    }

    @Test
    fun `undisposed steps, red verify lines without Open, unjustified or unreviewed surface flags and impact nudges refuse`() {
        val pending = done.copy(plan = done.plan + Step(2, Mark.Cursor, "update call sites") + Step(3, Mark.Todo, "docs"))
        assertEquals(
            listOf("step 2 [>] 'update call sites' has no disposition (done, cancelled or an explicit non-completed exit)", "step 3 [ ] 'docs' has no disposition (done, cancelled or an explicit non-completed exit)"),
            assertIs<GateResult.Refused>(evaluate(register = pending)).missing,
        )
        val lintRed = mapOf("CHK-accept-AC-1" to green(), "CHK-lint" to red("rcpt-9"))
        assertEquals(listOf("CHK-lint is red without an Open item naming it"), assertIs<GateResult.Refused>(evaluate(currencies = lintRed)).missing)
        assertIs<GateResult.Accepted>(evaluate(register = done.copy(open = listOf(OpenItem(1, "CHK-lint: 3 style errors in legacy module, tracked"))), currencies = lintRed))
        assertIs<GateResult.Refused>(evaluate(register = done.copy(open = listOf(OpenItem(1, "CHK-lint tracked", closed = true))), currencies = lintRed), "a closed item is no longer open")

        val flag = TestIntegrityFlag("tests/test_total.py", AcceptanceSurface.TestFile, "edit #3", listOf("CHK-accept-AC-1"))
        assertEquals(
            listOf("acceptance surface tests/test_total.py touches CHK-accept-AC-1 without a recorded justification", "acceptance surface tests/test_total.py touches CHK-accept-AC-1 without an approving review"),
            assertIs<GateResult.Refused>(evaluate(flags = listOf(flag))).missing,
        )
        assertEquals(listOf("acceptance surface tests/test_total.py touches CHK-accept-AC-1 without an approving review"), assertIs<GateResult.Refused>(evaluate(flags = listOf(flag.copy(reason = "asserted the old rounding")))).missing)
        assertIs<GateResult.Accepted>(evaluate(flags = listOf(flag.copy(reason = "asserted the old rounding", verdict = signed))))
        assertIs<GateResult.Accepted>(evaluate(flags = listOf(flag.copy(requiredChecks = emptyList()))), "a flag off the required set is visible, not blocking")
        assertEquals(listOf("unresolved impact nudge: public def total() changed; 3 importers unread"), assertIs<GateResult.Refused>(evaluate(nudges = listOf("public def total() changed; 3 importers unread"))).missing)
    }

    @Test
    fun `the verifier binds stamps, updates the ledger only on acceptance, and turns repeated refusals into recovery`() {
        val verifier = Verifier(maxFinalizations = 2)
        val ledger = Ledger.initial(contract)
        val proposal = CompletionProposal("I1", "done", 2, baseStamp = s8, resultingStamp = s9, patchHash = Digest.ofUtf8("patch"), envId = env)

        val moved = assertIs<CompletionResult.Refused>(verifier.accept(proposal, contract, increment, done, ledger, stampNow = s8, currencies = mapOf("CHK-accept-AC-1" to green()), assessments = listOf(assessed), reviews = mapOf("AC-3" to signed)))
        assertEquals(listOf(
            "resulting stamp @${s9.hash8} is not the tree now @${s8.hash8}",
            "AC-3: review does not certify the current candidate",
        ), moved.missing)
        assertEquals(1, moved.attempts)
        assertFalse(moved.recoveryDirected)
        assertEquals(RequirementStatus.Pending, ledger["R1"]!!.status, "the ledger is untouched on refusal")

        val again = assertIs<CompletionResult.Refused>(verifier.accept(proposal.copy(contractVersion = 1), contract, increment, done, ledger, s9, mapOf("CHK-accept-AC-1" to stale()), listOf(assessed), mapOf("AC-3" to signed)))
        assertEquals(2, again.attempts)
        assertTrue(again.recoveryDirected, "the second unsupported finalization goes to gap-directed recovery, not an endless gate")
        assertTrue(again.missing.first().startsWith("proposal binds contract v1"))
        assertEquals(2, again.missing.size)

        val accepted = assertIs<CompletionResult.Accepted>(verifier.accept(proposal, contract, increment, done, ledger, s9, mapOf("CHK-accept-AC-1" to green()), listOf(assessed), mapOf("AC-3" to signed)))
        assertEquals(s8, accepted.baseStamp)
        assertEquals(s9, accepted.resultingStamp)
        assertEquals(Digest.ofUtf8("patch"), accepted.patchHash)
        assertEquals(env, accepted.envId)
        assertEquals(listOf("rcpt-1"), accepted.receiptIds)
        assertEquals(RequirementStatus.Verified, accepted.ledger["R1"]!!.status)
        assertEquals(listOf("rcpt-1", "#44", "rev-1"), accepted.evidenceRefs)
        assertEquals(accepted.evidenceRefs, accepted.ledger["R1"]!!.evidence)
        assertTrue(accepted.ledger["R1"]!!.stampValid)
        assertEquals(RequirementStatus.Pending, ledger["R1"]!!.status, "the controller commits the returned ledger; the input is immutable")

        assertEquals(CompletionResult.NotCompleted(ExitKind.Blocked, "needs the finance policy"), verifier.accept(proposal.copy(claimedStatus = "blocked", reason = "needs the finance policy"), contract, increment, done, ledger, s9, emptyMap()))
        assertEquals(CompletionResult.NotCompleted(ExitKind.BudgetExhausted, "budget_exhausted"), verifier.accept(proposal.copy(claimedStatus = "budget_exhausted"), contract, increment, done, ledger, s9, emptyMap()))
        assertEquals(ExitKind.Waiting, assertIs<CompletionResult.NotCompleted>(verifier.accept(proposal.copy(claimedStatus = "waiting", reason = "full suite running"), contract, increment, done, ledger, s9, emptyMap())).kind)
        assertTrue(runCatching { verifier.accept(proposal.copy(claimedStatus = "verified"), contract, increment, done, ledger, s9, emptyMap()) }.isFailure, "the model cannot name a status the protocol does not have")
    }

    @Test
    fun `stale review and acceptance surface approvals never certify the current candidate`() {
        val proposal = CompletionProposal("I1", "done", contract.version, s8, s9, null, env)
        fun complete(review: Verdict, flags: List<TestIntegrityFlag> = emptyList()) = Verifier().accept(
            proposal, contract, increment, done, Ledger.initial(contract), s9,
            mapOf("AC-1" to green()), listOf(assessed), mapOf("AC-3" to review), flags,
        )
        assertIs<CompletionResult.Refused>(complete(signed.copy(reviewedCandidate = s8)))
        val flag = TestIntegrityFlag(
            "tests/test_total.py", AcceptanceSurface.TestFile, "edit", listOf("CHK-accept-AC-1"),
            reason = "correct old expectation", verdict = signed,
        )
        assertIs<CompletionResult.Refused>(complete(signed, listOf(flag.copy(verdict = signed.copy(reviewedCandidate = s8)))))
        assertIs<CompletionResult.Refused>(complete(signed, listOf(flag.copy(verdict = signed.copy(contractRevision = 1)))))
        assertIs<CompletionResult.Accepted>(complete(signed, listOf(flag)))
    }
}
