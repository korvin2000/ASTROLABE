package io.astrolabe.verify

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.context.ContractSlice
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Constraint
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Ledger
import io.astrolabe.contract.LedgerEntry
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.Outcome
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.WorkId
import io.astrolabe.register.ContractDigest
import io.astrolabe.register.ObligationStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChecksTest {
    private val estimator = HeuristicEstimator()

    private val contract = Contract(
        workId = WorkId("W-0042"), version = 3, attemptId = AttemptId("a2"), mode = Mode.Interactive, shape = Shape.S2,
        requests = listOf(
            UserRequest("U1", Instant.EPOCH, "Add idempotency-key handling to POST /payments; public API unchanged."),
            UserRequest("U2", Instant.EPOCH, "Also cover the retry path."),
        ),
        requirements = listOf(
            Requirement("R1", "idempotency key stored and checked per merchant", listOf("AC-1"), authorityRef = "U1", status = RequirementStatus.Verified),
            Requirement("R2", "retry path covered", listOf("AC-4", "AC-2"), authorityRef = "U2", status = RequirementStatus.InProgress),
        ),
        acceptance = listOf(
            Acceptance.Run("AC-1", Command(listOf("pytest", "tests/payments", "-q")), Origin.User),
            Acceptance.Check("AC-2", "no public signature change in src/api/", Origin.User),
            Acceptance.Review("AC-3", "retry semantics cannot duplicate side effects", Origin.User),
            Acceptance.Run("AC-4", Command(listOf("pytest", "-k", "idempot")), Origin.Model("R1")),
        ),
        constraints = listOf(Constraint("C1", "do not change the refund flow", "user"), Constraint("C2", "no new runtime dependencies", "rules-file")),
        exclusions = listOf("refund flow", "billing UI"),
        contractsTouched = listOf("CON-payments-api@7"),
        scope = Scope(listOf("src/pay/"), listOf("migrations/")),
        budget = Budget.of(Defaults(), Tokens(2_500_000)),
        authorization = Authorization(Stage.LocalCommit, DClassPolicy.Ask, "workspace-local-test-only"),
    )

    private val ledger = Ledger(mapOf("R1" to LedgerEntry("R1", RequirementStatus.Verified, listOf("rcpt-19"), true), "R2" to LedgerEntry("R2", RequirementStatus.InProgress)))

    @Test
    fun `digest stays under the cap, keeps the newest request and truncates deterministically`() {
        val obligations = listOf(ObligationStatus("AC-1", "green @s41 STALE (closure moved)"), ObligationStatus("AC-4", "red #42"), ObligationStatus("AC-2", "needs #id"))
        val digest = ContractDigest.render(contract, ledger, obligations, estimator)
        assertTrue(estimator.estimate(digest).tokens <= 150)
        assertTrue(digest.startsWith("── CONTRACT v3 (S2) ── \"Add idempotency-key"), digest)
        assertTrue(digest.contains("R1 verified · R2 in_progress → AC-1 green @s41 STALE (closure moved) · AC-4 red #42"), digest)
        assertTrue(digest.contains("exclusions: refund flow, billing UI"))
        assertEquals(digest, ContractDigest.render(contract, ledger, obligations, estimator))

        val longRequest = contract.copy(requests = listOf(UserRequest("U1", Instant.EPOCH, "word ".repeat(400)), UserRequest("U2", Instant.EPOCH, "latest objective")))
        val truncated = ContractDigest.render(longRequest, ledger, obligations, estimator)
        assertTrue(estimator.estimate(truncated).tokens <= 150, truncated)
        assertTrue(truncated.contains("latest objective"), "the current objective survives: $truncated")
        assertTrue(truncated.contains("(+1 earlier)") || truncated.contains(ContractDigest.TRUNCATED), truncated)
        assertTrue(truncated.contains("exclusions:"))

        val tiny = ContractDigest.render(longRequest.copy(requests = listOf(UserRequest("U1", Instant.EPOCH, "word ".repeat(400)))), ledger, obligations, estimator, capTokens = 60)
        assertTrue(tiny.contains(ContractDigest.TRUNCATED), tiny)
        assertTrue(estimator.estimate(tiny).tokens <= 60, tiny)
    }

    @Test
    fun `slice carries complete acceptance definitions and an id-only slice fails coverage (IX-11)`() {
        val increment = Increment("I2", listOf("R2"), accept = listOf("AC-4"), writeScope = listOf("src/pay/"), expectedFiles = 3)
        val slice = ContractSlice.forIncrement(contract, increment, originalObligations = mapOf("AC-4" to "pytest -k idempot (before weakening)"))
        assertEquals(listOf("R2"), slice.requirements.map { it.id })
        assertEquals(listOf("AC-4", "AC-2"), slice.acceptance.map { it.id })
        assertEquals(2, slice.constraints.size)
        assertTrue(slice.coverage().complete)
        val text = slice.render()
        assertTrue(text.contains("AC-4 (model(strengthens R1), v1): run: pytest -k idempot"), text)
        assertTrue(text.contains("AC-2 (user, v1): check: no public signature change in src/api/"), text)
        assertTrue(text.contains("original obligation: pytest -k idempot (before weakening)"), text)
        assertTrue(text.contains("constraints: C1 do not change the refund flow (user) · C2"), text)
        assertTrue(text.contains("exclusions: refund flow, billing UI"))
        assertEquals(text, slice.render())

        val idsOnly = slice.copy(acceptance = slice.acceptance.filter { it.id != "AC-2" })
        val coverage = idsOnly.coverage()
        assertFalse(coverage.complete)
        assertEquals(listOf("AC-2"), coverage.missingAcceptance)
        assertFailsWith<IllegalArgumentException> { ContractSlice.forIncrement(contract, increment.copy(accept = listOf("AC-9"))) }
    }

    @Test
    fun `registry seeds the S0 set and definition versions change with argv or parser policy (FX-16 input)`() {
        val commands = RunnerCommands(test = Command(listOf("python", "-m", "pytest", "-q")), lint = Command(listOf("ruff", "check")), typecheck = Command(listOf("pyright")))
        val checks = Checks.seed(contract, commands, touched = setOf("src/pay/x.py"))
        assertEquals(listOf("CHK-types-touched", "CHK-lint", "CHK-accept-AC-1", "CHK-accept-AC-4", "CHK-full"), checks.all().map { it.id })
        assertEquals(Closure.Known(setOf("src/pay/x.py")), checks[Checks.TYPES_TOUCHED]!!.inputClosure)
        assertEquals(Closure.Unknown, checks[Checks.FULL]!!.inputClosure)
        assertEquals(listOf("CHK-accept-AC-1", "CHK-accept-AC-4"), checks.required().map { it.id })
        val accept = checks["CHK-accept-AC-4"]!!
        assertNotEquals(accept.definitionVersion, accept.copy(command = Command(listOf("pytest", "-k", "idempot", "-x"))).definitionVersion)
        assertNotEquals(accept.definitionVersion, accept.copy(parserPolicy = "shaper/2").definitionVersion)
        assertEquals(accept.definitionVersion, accept.copy(last = LastResult("r", CandidateId(Digest.ofUtf8("s")), accept.definitionVersion, Outcome.Passed, Counts(1), Applicability.Current)).definitionVersion)

        val stamp = CandidateId(Digest.ofUtf8("s8"))
        checks.record("CHK-accept-AC-4", LastResult("rcpt-20", stamp, accept.definitionVersion, Outcome.Failed, Counts(11, 1), Applicability.Current))
        assertEquals(listOf("CHK-accept-AC-1", "CHK-accept-AC-4", "CHK-full", "CHK-types-touched", "CHK-lint").sorted(), checks.affectedBy(listOf("src/pay/x.py")).map { it.id }.sorted())
        assertEquals(listOf("CHK-accept-AC-1", "CHK-accept-AC-4", "CHK-full"), checks.affectedBy(listOf("docs/readme.md")).map { it.id })
        val stale = checks.markStale("CHK-accept-AC-4", "closure moved")!!
        assertEquals(Applicability.Stale, stale.last!!.applicability)
        assertEquals(Outcome.Failed, stale.last.outcome, "the historical outcome never changes")
    }

    @Test
    fun `checks render delta plus absolute and never zero-new alone`() {
        val stamp = CandidateId(Digest("d1e7" + "0".repeat(60)))
        val lines = listOf(
            CheckLine("types", "touched", Delta(1, 2), CheckState.Red("", 3), "d1e7", "#44"),
            CheckLine("tests", "blast 14", null, CheckState.Red("13 pass 1 fail", 1), "s8", "#42"),
            CheckLine("accept AC-4", null, null, CheckState.Red("", 1), null, null, unchangedRed = true),
            CheckLine("full", null, null, CheckState.Stale("s3, closure moved 2 increments ago"), null, null),
            CheckLine("review", null, null, CheckState.NotRun, null, null),
        )
        val rendered = ChecksRender.render(stamp, lines, maxLines = 3)
        val expected = """
            ── Checks @d1e7 ── types(touched): Δ +1 −2 · now 3 @d1e7 (#44) · tests(blast 14): now 1 13 pass 1 fail @s8 (#42)
                               +3 more: accept AC-4, full, review
        """.trimIndent()
        assertEquals(expected, rendered)
        assertEquals("accept AC-4: no change · still 1", ChecksRender.line(lines[2]))
        assertEquals("full: stale (s3, closure moved 2 increments ago)", ChecksRender.line(lines[3]))
        assertEquals("types(touched): 14 files ✓ @d1e7", ChecksRender.line(CheckLine("types", "touched", Delta(0, 0), CheckState.Green("14 files ✓"), "d1e7", null)))
        assertEquals("── Checks @none ── no checks registered", ChecksRender.render(null, emptyList()))
    }
}
