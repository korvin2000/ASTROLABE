package io.astrolabe.verify

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.contract.Origin
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Decision
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Fixture
import io.astrolabe.fixtures.FixtureRepos
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.7.8 conservative acceptance-surface policy (§8.6 baseline; FX-14 baseline form; IX-02 weakening part). */
class TestIntegrityTest {
    private val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), FakeClock.at("2026-09-20T10:00:00Z"))
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"))
    private val candidate = CandidateId(Digest.ofUtf8("s1"))

    private fun s0(): Pair<Contract, Checks> = FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
        val derived = contracts.deriveS0(ids.work, ids.attempt, "fix rounding", Atlas.build(repo.root), Config(), Tokens(1_000))
        derived.contract to Checks.seed(derived.contract, RunnerCommands.of(derived.primary!!))
    }

    private class RecordingReviewer(private val outcome: VerdictOutcome) : Authority {
        var request: ReviewRequest? = null
        override suspend fun ask(question: Question): Answer? = null
        override suspend fun approve(request: DClassRequest): Decision = error("unused")
        override suspend fun resolve(proposal: AmendmentProposal): Resolution = error("unused")
        override suspend fun review(request: ReviewRequest): Verdict {
            this.request = request
            return Verdict(request.id, request.contractRevision, request.candidate, outcome, confidence = 0.9, signedBy = "human:alice")
        }
    }

    @Test
    fun `touching a test file, a check definition, CI config or an acceptance command input is flagged and source is not`() {
        val (contract, checks) = s0()
        val touched = listOf("tests/test_total.py", "pyproject.toml", ".github/workflows/ci.yml", "jest.config.ts", "src/total.py", "tests/conftest.py")
        val flags = TestIntegrity.baseline(touched, "edit #3", contract, checks)
        assertEquals(
            listOf(
                "tests/test_total.py" to AcceptanceSurface.TestFile,
                "pyproject.toml" to AcceptanceSurface.CheckDefinition,
                ".github/workflows/ci.yml" to AcceptanceSurface.CiConfig,
                "jest.config.ts" to AcceptanceSurface.CheckDefinition,
                "tests/conftest.py" to AcceptanceSurface.CheckDefinition,
            ),
            flags.map { it.path to it.surface },
        )
        flags.forEach {
            assertEquals(listOf("CHK-accept-AC-1"), it.requiredChecks, "${it.path}: the auto-derived suite (unknown closure) is tied conservatively")
            assertEquals(TestIntegrity.UNCLASSIFIED, it.kind)
            assertTrue(it.blocksCompletion)
        }
        assertEquals(
            "acceptance surface: tests/test_total.py (test file) modified by edit #3 · unclassified-weakening-risk · required: CHK-accept-AC-1 · review: pending",
            flags.first().line,
        )
        assertEquals(TestIntegrity.unresolved(flags), flags)
        assertTrue(TestIntegrity.baseline(listOf("src/total.py", "README.md"), "run #7", contract, checks).isEmpty())
    }

    @Test
    fun `an acceptance command's input is a surface even outside test directories and ties only the checks that name it`() {
        val (base, _) = s0()
        val contract = base.strengthen(Acceptance.Run("AC-2", Command(listOf("python", "scripts/check_api.py")), Origin.Model("R1")))
        val checks = Checks.seed(contract, RunnerCommands(test = Command(listOf("python", "-m", "pytest", "-q"))))
        val known = checks.register(Check("CHK-known", CheckKind.Acceptance, Selector.Touched, io.astrolabe.evidence.Closure.Known(setOf("scripts/other.py")), CostClass.Fast, Trigger.EndOfTurn, acceptanceIds = listOf("AC-1")))
        val flag = TestIntegrity.baseline(listOf("scripts/check_api.py"), "edit #4", contract, checks).single()
        assertEquals(AcceptanceSurface.AcceptanceCommand, flag.surface)
        assertEquals(listOf("CHK-accept-AC-1", "CHK-accept-AC-2"), flag.requiredChecks, "the unknown-closure suite and the command that names the path; not ${known.id}")
        assertNull(TestIntegrity.surfaceOf("scripts/helper.py", contract))

        val cwdScoped = base.strengthen(Acceptance.Run("AC-3", Command(listOf("npm", "test"), cwd = "packages/web"), Origin.Model("R1")))
        assertEquals(AcceptanceSurface.AcceptanceCommand, TestIntegrity.surfaceOf("packages/web/src/index.ts", cwdScoped))
    }

    @Test
    fun `a weakened required check cannot complete without an approving verdict that saw the original obligation (FX-14 baseline)`() = runTest {
        val (contract, checks) = s0()
        val flags = TestIntegrity.baseline(listOf("tests/test_total.py"), "edit #3", contract, checks).map { it.copy(reason = "rounding test asserted the old behaviour") }
        val request = TestIntegrity.reviewRequest("rev-1", flags, contract, checks, ids, candidate, "blob:packet-1", originals = mapOf("tests/test_total.py" to "preimage #12"))
        assertEquals(1, request.contractRevision)
        assertEquals(ReviewScope.Increment, request.scope)
        assertEquals(listOf("run: python -m pytest -q (scope touched)", flags.single().line), request.criteria)
        assertEquals(listOf("AC-1 (harness, v1): run: python -m pytest -q (scope touched)", "original tests/test_total.py: preimage #12"), request.originalObligations)
        assertTrue(flags.single().line.contains("reason: rounding test asserted the old behaviour"))

        assertTrue(TestIntegrity.resolve(request, flags, AutonomousAuthority()).all { it.blocksCompletion }, "no reviewer ⇒ still unaccepted, never silently green")

        val rejecting = RecordingReviewer(VerdictOutcome.Reject)
        val rejected = TestIntegrity.resolve(request, flags, rejecting)
        assertTrue(rejected.all { it.blocksCompletion })
        assertTrue(rejected.single().line.endsWith("review: reject by human:alice"), rejected.single().line)

        val approving = RecordingReviewer(VerdictOutcome.Approve)
        val approved = TestIntegrity.resolve(request, flags, approving)
        assertEquals(request, approving.request)
        assertFalse(approved.single().blocksCompletion)
        assertTrue(TestIntegrity.unresolved(approved).isEmpty())
        assertTrue(approved.single().line.endsWith("review: approved by human:alice"), approved.single().line)
    }

    @Test
    fun `a flag on a path no required check can reach is visible but does not block`() {
        val (contract, _) = s0()
        val checks = Checks.empty()
        checks.register(Check("CHK-accept-AC-1", CheckKind.Acceptance, Selector.Touched, io.astrolabe.evidence.Closure.Known(setOf("tests/test_pay.py")), CostClass.Fast, Trigger.EndOfTurn, acceptanceIds = listOf("AC-1")))
        val flag = TestIntegrity.baseline(listOf("tests/test_other.py"), "edit #5", contract, checks).single()
        assertTrue(flag.requiredChecks.isEmpty())
        assertFalse(flag.blocksCompletion)
        assertTrue(flag.line.endsWith("review: not required"), flag.line)
        assertEquals(listOf("CHK-accept-AC-1"), TestIntegrity.baseline(listOf("tests/test_pay.py"), "edit #5", contract, checks).single().requiredChecks)
    }

    @Test
    fun `the classifier names weakened assertions, skips, deleted tests and snapshot updates with the original obligation (FX-14)`() = runTest {
        val (contract, checks) = s0()
        val before = "def test_total():\n    assert total([1, 2]) == 3\n\n\ndef test_empty():\n    assert total([]) == 0\n"
        val changes = listOf(
            SurfaceChange("tests/test_total.py", before, "def test_total():\n    assert total([1, 2])\n\n\ndef test_empty():\n    assert total([]) == 0\n"),
            SurfaceChange("tests/test_skip.py", before, "import pytest\n\n@pytest.mark.skip\n$before"),
            SurfaceChange("tests/test_gone.py", before, "def test_total():\n    assert total([1, 2]) == 3\n"),
            SurfaceChange("tests/__snapshots__/total.snap", "3\n", "4\n"),
            SurfaceChange("tests/test_tol.py", "def test_pi():\n    assert pi() == approx(3.14, rel=1e-6)\n", "def test_pi():\n    assert pi() == approx(3.14, rel=1e-1)\n"),
            SurfaceChange("tests/test_more.py", before, before + "\n\ndef test_two():\n    assert total([2]) == 2\n"),
            SurfaceChange("pyproject.toml", "[tool.pytest.ini_options]\n", "[tool.pytest.ini_options]\naddopts = \"-k not slow\"\n"),
            SurfaceChange("tests/test_unknown.py", null, null),
            SurfaceChange("src/total.py", "a\n", "b\n"),
        )
        val kinds = TestIntegrity.classify(changes, "edit #4", contract, checks).associate { it.path to it.kind }
        assertEquals(
            mapOf(
                "tests/test_total.py" to TestIntegrity.WEAKENED_ASSERTION,
                "tests/test_skip.py" to TestIntegrity.SKIP_MARKER,
                "tests/test_gone.py" to "${TestIntegrity.DELETED_TEST},${TestIntegrity.WEAKENED_ASSERTION}",
                "tests/__snapshots__/total.snap" to TestIntegrity.SNAPSHOT_UPDATE,
                "tests/test_tol.py" to TestIntegrity.WEAKENED_ASSERTION,
                "tests/test_more.py" to TestIntegrity.ADDITIONS_ONLY,
                "pyproject.toml" to TestIntegrity.CHECK_CONFIG,
                "tests/test_unknown.py" to TestIntegrity.UNCLASSIFIED,
            ),
            kinds,
        )
        assertEquals(kinds, TestIntegrity.classify(changes, "edit #4", contract, checks).associate { it.path to it.kind }, "deterministic")

        val flags = TestIntegrity.classify(changes, "edit #4", contract, checks)
        val weakened = flags.first { it.path == "tests/test_total.py" }
        assertEquals("    assert total([1, 2]) == 3", weakened.originalObligation)
        assertTrue(weakened.line.contains("· weakened-assertion · required:"), weakened.line)
        assertTrue(weakened.blocksCompletion)
        assertFalse(flags.first { it.path == "tests/test_more.py" }.blocksCompletion, "a pure addition is rendered, not blocking")

        val reviewer = RecordingReviewer(VerdictOutcome.Approve)
        val request = TestIntegrity.reviewRequest("rv-1", TestIntegrity.unresolved(flags), contract, checks, ids, candidate, "packet-1")
        assertTrue(request.originalObligations.any { it == "original tests/test_total.py:     assert total([1, 2]) == 3" }, request.originalObligations.toString())
        TestIntegrity.resolve(request, flags, reviewer)
        assertEquals(contract.acceptance, contracts.current(ids.work)?.acceptance ?: contract.acceptance, "the contract's acceptance is unchanged")
    }
}
