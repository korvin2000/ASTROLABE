package io.astrolabe.verify

import io.astrolabe.auth.Redaction
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.InMemoryAliases
import io.astrolabe.evidence.InputStability
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.ChildCommands
import io.astrolabe.os.LocalOs
import io.astrolabe.store.Store
import io.astrolabe.tool.run.TestIdentity
import io.astrolabe.tool.run.TestOutcome
import io.astrolabe.tool.run.TestResult
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.workspace.DirtyState
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.ShadowRef
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.7.5 baseline receipt on the captured initial candidate (D-53), pre-existing ledger by identity + signature + environment (IX-13). */
class BaselineTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var workspace: Workspace
    private lateinit var os: LocalOs
    private lateinit var shadowRef: ShadowRef
    private lateinit var s0: io.astrolabe.id.CandidateId
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val env = EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1"))
    private val windows = ChildCommands.isWindows
    private val recorded: String = javaClass.getResourceAsStream("/shaper/pytest-fail-param.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("tests/test_discount.py", "def test_tier():\n    assert discount(cart, tier) == 5\n")
        repo.write("pytest_output.txt", recorded)
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
        os = LocalOs(clock)
        val stamper = Stamper(workspace, env)
        val dirtyState = DirtyState(workspace, store.blobs, stamper, ids, clock)
        shadowRef = ShadowRef(ids.work, ids.attempt, workspace, store, dirtyState, os, clock)
        shadowRef.open(dirtyState.capture(0))
        s0 = stamper.stamp().id
    }

    @AfterTest
    fun tearDown() {
        os.close()
        store.close()
        repo.close()
    }

    private fun baseline() = Baseline(shadowRef, store.layout, TrustedLocalRunner(os), os, SqliteReceipts(store, clock), InMemoryAliases(), store.blobs, Redaction(), HeuristicEstimator(), FixedIdGen(), ids, clock, env)

    @Test
    fun `changed restored and added baseline inputs cannot establish preexisting failures`() = runTest {
        val changes = listOf(
            "echo changed>tests/test_discount.py" to "echo changed > tests/test_discount.py",
            "copy /y tests\\test_discount.py saved.txt>nul&echo changed>tests/test_discount.py&type saved.txt>tests/test_discount.py&del saved.txt" to "cp tests/test_discount.py saved.txt; echo changed > tests/test_discount.py; cat saved.txt > tests/test_discount.py; rm saved.txt",
            "echo new>tests/new.py" to "echo new > tests/new.py",
        )
        val idGen = FixedIdGen()
        changes.forEachIndexed { index, (win, posix) ->
            val runner = Baseline(shadowRef, store.layout, TrustedLocalRunner(os), os, SqliteReceipts(store, clock), InMemoryAliases(), store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, clock, env)
            val result = runner.run(suite("$win&type pytest_output.txt&exit /b 1", "$posix; cat pytest_output.txt; exit 1"), 1, s0)
            assertFalse(result.receipt.testedInputs.eligible, "case $index")
            assertNull(result.ledger, "case $index must not classify failures from a changed tree as preexisting")
        }
    }

    @Test
    fun `report and coverage artifacts written by the suite keep the ledger, any other new file withholds it`() = runTest {
        val idGen = FixedIdGen()
        fun runner() = Baseline(shadowRef, store.layout, TrustedLocalRunner(os), os, SqliteReceipts(store, clock), InMemoryAliases(), store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, clock, env)
        val artifacts = runner().run(
            suite(
                "echo x>junit-report.xml&mkdir reports htmlcov pkg.egg-info&echo x>reports\\TEST-a.xml&echo x>coverage.xml&echo x>htmlcov\\index.html&echo x>pkg.egg-info\\PKG-INFO&type pytest_output.txt&exit /b 1",
                "echo x > junit-report.xml; mkdir -p reports htmlcov pkg.egg-info; echo x > reports/TEST-a.xml; echo x > coverage.xml; echo x > htmlcov/index.html; echo x > pkg.egg-info/PKG-INFO; cat pytest_output.txt; exit 1",
            ),
            1, s0,
        )
        assertEquals(emptySet(), artifacts.receipt.testedInputs.mutatedDuringCheck)
        assertTrue(artifacts.receipt.testedInputs.eligible)
        assertNotNull(artifacts.ledger)

        val other = runner().run(suite("echo x>notes.txt&type pytest_output.txt&exit /b 1", "echo x > notes.txt; cat pytest_output.txt; exit 1"), 1, s0)
        assertEquals(setOf("notes.txt"), other.receipt.testedInputs.mutatedDuringCheck)
        assertNull(other.ledger)
    }

    private fun suite(windowsLine: String, posixLine: String) = Check(
        "CHK-full", CheckKind.Full, Selector.All, Closure.Unknown, CostClass.Expensive, Trigger.CampaignEnd,
        command = Command(if (windows) listOf("cmd.exe", "/d", "/s", "/c", windowsLine) else listOf("/bin/sh", "-c", posixLine)),
    )

    private fun failing(name: String = "test_tier", file: String = "tests/test_discount.py", suite: String? = "TestDiscount", parameterization: String? = "3", message: String = "AssertionError: assert 4 == 5\n +  where 4 = discount(...)") =
        TestResult(TestIdentity(check = "CHK-full", file = file, suite = suite, name = name, parameterization = parameterization), TestOutcome.Failed, message)

    @Test
    fun `a baseline receipt records its tests by digest and is marked`() = runTest {
        val runner = baseline()
        val baseline = runner.run(suite("type pytest_output.txt&exit /b 1", "cat pytest_output.txt; exit 1"), contractVersion = 1, s0 = s0).receipt
        assertTrue(Regressions.isBaseline(baseline), baseline.limits.toString())
        val tests = assertNotNull(baseline.tests)
        val inherited = tests.failed.single()
        assertEquals("tests/test_discount.py::TestDiscount::test_tier[3]" to "AssertionError: assert 4 == 5", inherited.name to inherited.signature)
        assertEquals(Regressions.key(failing().identity), inherited.key, "the key digests the namespaced identity, never shown")
        assertEquals(emptyList(), tests.passed, "pytest's terminal summary names no passing test without -rA")
        assertEquals(baseline, SqliteReceipts(store, clock).get(baseline.receiptId), "the tests round-trip through the store")
        // A marker recorded before a run: never retried, never usable.
        val begun = runner.begin(suite("type pytest_output.txt", "cat pytest_output.txt"), 1, s0)
        assertTrue(Regressions.isBaseline(begun) && begun.outcome == Outcome.Unavailable)
    }

    private val stampA = io.astrolabe.id.CandidateId(Digest.ofUtf8("A"))
    private val stampB = io.astrolabe.id.CandidateId(Digest.ofUtf8("B"))
    private val stampC = io.astrolabe.id.CandidateId(Digest.ofUtf8("C"))
    private var serial = 0

    private fun key(name: String) = Regressions.key(TestIdentity(file = "tests/t.py", name = name))

    private fun failure(name: String, message: String = "AssertionError: assert 1 == 2") = io.astrolabe.evidence.FailedTest(key(name), "tests/t.py::$name", message)

    /** A receipt of the blast radius: [failed] and [passed] tests on [stamp]; [s0] marks a baseline. */
    private fun blast(
        outcome: Outcome, stamp: io.astrolabe.id.CandidateId, failed: List<io.astrolabe.evidence.FailedTest> = emptyList(), passed: List<String> = emptyList(),
        ambiguous: List<String> = emptyList(), command: List<String> = listOf("pytest", "tests"), s0: Boolean = false, counts: Counts? = null, envId: Digest = env.envId,
        truncated: Boolean = false, stability: InputStability = InputStability.Exclusive, incomplete: Boolean = false,
    ) = io.astrolabe.evidence.Receipt(
        receiptId = "r${++serial}", ids = ids, checkId = Checks.TESTS_BLAST, acceptanceIds = emptyList(), command = command, cwd = null, shell = false,
        stampBefore = stamp, stampAfter = stamp, envId = envId, verifierVersion = "v", checkDefinitionVersion = Digest.ofUtf8(command.joinToString(" ")), contractVersion = 1,
        outcome = outcome, parsed = counts ?: Counts(passed = passed.size, failed = failed.size, discovered = passed.size + failed.size), inputClosure = Closure.Unknown,
        testedInputs = io.astrolabe.evidence.TestedInputs(emptyMap(), stability), raw = null,
        limits = if (s0) listOf(io.astrolabe.evidence.Limit(Regressions.BASELINE, "on s0")) else emptyList(), at = clock.instant(),
        tests = io.astrolabe.evidence.TestOutcomes(failed, passed, ambiguous, truncated, incomplete),
    )

    private fun hold(history: List<io.astrolabe.evidence.Receipt>, stamp: io.astrolabe.id.CandidateId?, vararg baselines: io.astrolabe.evidence.Receipt) =
        Regressions.hold(history, baselines.toList(), stamp) { it.receiptId }

    @Test
    fun `a failure is held by its identity until it executed and passed alone in a finished run on this tree`() {
        val red = blast(Outcome.Failed, stampA, listOf(failure("total")), listOf(key("other")))
        assertEquals(listOf("r1"), hold(listOf(red), stampA)?.current, "a red run on this tree is current")
        // `pytest -x`: the next red stops before `total`; a later red never erases an earlier failure.
        val x = assertNotNull(hold(listOf(red, blast(Outcome.Failed, stampB, listOf(failure("legacy")))), stampB))
        assertTrue(x.unknown.any { it.startsWith("tests/t.py::total: failed in r1, not executed on this tree") }, x.toString())
        assertTrue(x.unknown.any { it.startsWith("tests/t.py::legacy") }, x.toString())
        // Removed, skipped or renamed: a green run that did not execute it proves nothing — unknown, not current.
        val deleted = assertNotNull(hold(listOf(red, blast(Outcome.Passed, stampB, passed = listOf(key("other")))), stampB))
        assertEquals(RedClass.Unknown to emptyList<String>(), deleted.kind to deleted.current)
        // Executed once and passed in a finished run on this tree: shown fixed — the one way a red leaves no trace.
        assertNull(hold(listOf(red, blast(Outcome.Passed, stampB, passed = listOf(key("total"), key("other")))), stampB))
        // A pass beside another case of the same identity is ambiguous; a cut or partly read record shows nothing fixed.
        assertTrue(assertNotNull(hold(listOf(red, blast(Outcome.Passed, stampB, passed = listOf(key("total")), ambiguous = listOf(key("total")))), stampB)).unknown.single().contains("ambiguous"))
        assertNotNull(hold(listOf(red, blast(Outcome.Passed, stampB, passed = listOf(key("total")), truncated = true)), stampB))
        // Codex round 3 A: a report read in part (a complete pass of `t`, then a cut-off failing `t`) clears nothing.
        val partial = blast(Outcome.Failed, stampB, listOf(failure("other")), listOf(key("total")), incomplete = true)
        assertTrue(assertNotNull(hold(listOf(red, partial), stampB)).unknown.any { it.startsWith("tests/t.py::total: failed in r1; the run on this tree did not finish with a complete record") })
        // A run on this tree that timed out clears nothing, and the failure it did see is held.
        val hung = blast(Outcome.Timeout, stampB, listOf(failure("y")), listOf(key("total")), counts = Counts(passed = 1, failed = 1, discovered = 3))
        val timedOut = assertNotNull(hold(listOf(red, hung), stampB))
        assertTrue(timedOut.unknown.any { it.startsWith("tests/t.py::total: failed in r1; the run on this tree did not finish") }, timedOut.toString())
        assertTrue(timedOut.unknown.any { it.startsWith("tests/t.py::y") } && timedOut.current == listOf(hung.receiptId), timedOut.toString())
        // A pass on an earlier tree is history; the stop reruns the red's command once per definition and tree.
        val fixedAtB = blast(Outcome.Passed, stampB, passed = listOf(key("total")))
        assertTrue(assertNotNull(hold(listOf(red, fixedAtB), stampC)).unknown.single().endsWith("not rerun on this tree"))
        assertEquals(listOf(red), Regressions.unconfirmed(listOf(red, fixedAtB), emptyList(), stampC))
        assertEquals(emptyList(), Regressions.unconfirmed(listOf(red, fixedAtB, blast(Outcome.Timeout, stampC)), emptyList(), stampC))
        val begun = blast(Outcome.NotRun, stampC, counts = Counts()).copy(tests = null, limits = listOf(io.astrolabe.evidence.Limit(Regressions.RERUN, "began")))
        assertEquals(emptyList(), Regressions.unconfirmed(listOf(red, fixedAtB, begun), emptyList(), stampC), "a rerun begun on record is never repeated")
        // Failed and passed on the same tree: flaky, unknown; a failure only an ineligible run saw is held, never classified by it.
        assertTrue(assertNotNull(hold(listOf(red, blast(Outcome.Passed, stampA, passed = listOf(key("total")))), stampA)).unknown.single().contains("flaky"))
        assertTrue(assertNotNull(hold(listOf(blast(Outcome.Failed, stampA, listOf(failure("total")), stability = InputStability.Unknown)), stampA)).unknown.single().contains("cannot certify"))
        assertNull(hold(listOf(blast(Outcome.Failed, stampA, listOf(failure("total")), s0 = true)), stampA), "a baseline is never a run of the change")
        // Failures counted but not identified, or cut from the record, stay whatever later passes say.
        val unidentified = blast(Outcome.Failed, stampA, counts = Counts(failed = 1, discovered = 1))
        assertTrue(assertNotNull(hold(listOf(unidentified, blast(Outcome.Passed, stampB, passed = listOf(key("total")), command = unidentified.command)), stampB)).unknown.single().contains("did not identify"))
        val many = (1..Regressions.MAX_FAILED).map { failure("t$it") }
        val cut = blast(Outcome.Failed, stampA, many, counts = Counts(failed = Regressions.MAX_FAILED + 1, discovered = Regressions.MAX_FAILED + 1), truncated = true)
        assertTrue(assertNotNull(hold(listOf(cut, blast(Outcome.Passed, stampB, passed = many.map { it.key })), stampB)).unknown.single().contains("did not identify"))
    }

    @Test
    fun `a held failure is new only if a finished baseline reported it once passed, and failed before when s0 reported it failing`() {
        val now = blast(Outcome.Failed, stampA, listOf(failure("total", "AssertionError: assert total(3) == 1234567")), listOf(key("other")))
        // Reported once, passed, by a finished baseline on s0, and failing now: new, a hard refusal.
        val passedOnS0 = blast(Outcome.Passed, stampA, passed = listOf(key("total"), key("other")), s0 = true)
        val regression = assertNotNull(hold(listOf(now), stampA, passedOnS0))
        assertEquals(RedClass.New, regression.kind)
        assertTrue(regression.regressions.single().contains("passed on s0"), regression.toString())
        // Two failures of one identity on this tree: still new, never "failed before", when s0 passed it.
        val twice = blast(Outcome.Failed, stampA, listOf(failure("total"), failure("total", "another way")), ambiguous = listOf(key("total")))
        assertEquals(RedClass.New, hold(listOf(twice), stampA, passedOnS0)?.kind)
        // Codex round 3 №2: s0 never reported it — a fail-fast baseline stopped first, or the test did not exist — unknown.
        val stopped = blast(Outcome.Failed, stampA, listOf(failure("a")), s0 = true, counts = Counts(failed = 1, discovered = 5))
        val failFast = assertNotNull(hold(listOf(blast(Outcome.Failed, stampA, listOf(failure("b")), listOf(key("a")))), stampA, stopped))
        assertEquals(RedClass.Unknown, failFast.kind)
        assertTrue(failFast.unknown.single().contains("not reported on s0"), failFast.toString())
        assertEquals(RedClass.Unknown, hold(listOf(blast(Outcome.Failed, stampA, listOf(failure("brand_new")))), stampA, passedOnS0)?.kind)
        // Codex round 3 №3: s0 reported it failing, whatever its text: failed before the change too — acknowledged by the runtime.
        val failedOnS0 = blast(Outcome.Failed, stampA, listOf(failure("total", "AssertionError: assert total(3) == 7654321")), listOf(key("other")), s0 = true)
        val before = assertNotNull(hold(listOf(now), stampA, failedOnS0))
        assertEquals(RedClass.FailedBefore to listOf("failed before the change too: tests/t.py::total"), before.kind to before.failedBefore)
        val ambiguousOnS0 = blast(Outcome.Failed, stampA, listOf(failure("total"), failure("total")), ambiguous = listOf(key("total")), s0 = true)
        assertEquals(RedClass.FailedBefore, hold(listOf(now), stampA, ambiguousOnS0)?.kind, "in any form")
        // Unknown: no baseline, one that did not finish or was read in part, another environment, a begun one, ambiguous on s0.
        assertTrue(assertNotNull(hold(listOf(now), stampA)).unknown.single().contains(Regressions.NO_BASELINE))
        assertEquals(RedClass.Unknown, hold(listOf(now), stampA, passedOnS0.copy(tests = passedOnS0.tests!!.copy(incomplete = true)))?.kind)
        assertEquals(RedClass.Unknown, hold(listOf(now), stampA, failedOnS0.copy(outcome = Outcome.Timeout))?.kind, "an unfinished s0 run: not even failed before")
        assertEquals(RedClass.Unknown, hold(listOf(now.copy(envId = Digest.ofUtf8("other-env"))), stampA, passedOnS0)?.kind)
        val begun = blast(Outcome.Unavailable, stampA, s0 = true).copy(tests = null)
        assertEquals(RedClass.Unknown, hold(listOf(now), stampA, passedOnS0, begun)?.kind, "the latest baseline answers: an interrupted one")
        assertEquals(RedClass.Unknown, hold(listOf(now), stampA, blast(Outcome.Passed, stampA, passed = listOf(key("total"), key("total")), ambiguous = listOf(key("total")), s0 = true))?.kind)
        // One baseline per check and attempt: any baseline, of any definition, ends the due.
        assertEquals(now, Regressions.baselineDue(listOf(now), emptyList(), stampA))
        assertNull(Regressions.baselineDue(listOf(now), listOf(begun), stampA))
    }

    @Test
    fun `what a run records shows redacted text, compares identities only, and counts ambiguity over every reported case`() {
        fun result(name: String, outcome: TestOutcome, message: String? = null) = TestResult(TestIdentity(file = "tests/t.py", name = name), outcome, message)
        val redact = { text: String -> text.replace(Regex("secret\\w+"), "[REDACTED]") }
        val one = Regressions.outcomes(listOf(result("test_login[secretAAA]", TestOutcome.Failed, "token secretAAA rejected"), result("same", TestOutcome.Passed), result("same", TestOutcome.Skipped)), redact)
        assertEquals("tests/t.py::test_login[[REDACTED]]" to "token [REDACTED] rejected", one.failed.first().name to one.failed.first().signature)
        assertEquals(Regressions.key(TestIdentity(file = "tests/t.py", name = "test_login[secretAAA]")), one.failed.first().key, "the key digests the unredacted identity")
        assertEquals(listOf(key("same")), one.ambiguous, "a passed and a skipped instance of one identity")
        assertTrue(Regressions.outcomes(listOf(result("x", TestOutcome.Passed)), redact, complete = false).incomplete)
    }


    @Test
    fun `the time a minutes limit leaves is read again just before the baseline's process starts`() = runTest {
        val runner = baseline().also { it.timeLeft = { 0L } }
        val result = runner.run(suite("type pytest_output.txt&exit /b 1", "cat pytest_output.txt; exit 1"), contractVersion = 1, s0 = s0)
        assertEquals(Outcome.Unavailable, result.receipt.outcome)
        assertTrue(result.receipt.limits.any { it.detail == io.astrolabe.budget.NO_ACTIVE_TIME }, result.receipt.limits.toString())
    }


    @Test
    fun `the baseline runs on the captured initial candidate even after edits and its ledger matches identity and signature, not counts or names (IX-13)`() = runTest {
        // Edits after the capture: the working tree now shows a passing run, the captured tree still fails.
        repo.write("pytest_output.txt", recorded.replace("tests/test_discount.py .F.", "tests/test_discount.py ...").replace("5 passed, 1 failed, 1 skipped", "7 passed"))
        repo.write("tests/test_discount.py", "def test_tier():\n    assert True\n")

        val result = baseline().run(suite("type pytest_output.txt&exit /b 1", "cat pytest_output.txt; exit 1"), contractVersion = 1, s0 = s0)
        assertTrue(result.materialized.ok, result.materialized.mismatches.toString())
        assertEquals(recorded, Files.readString(result.candidateDir.resolve("pytest_output.txt")), "the exported candidate is s0, not the edited tree")
        val receipt = result.receipt
        assertEquals(Outcome.Failed, receipt.outcome)
        assertEquals(Counts(passed = 5, failed = 1, skipped = 1, discovered = 7), receipt.parsed)
        assertEquals(s0, receipt.stampBefore)
        assertEquals(s0, receipt.stampAfter)
        assertEquals(InputStability.Isolated, receipt.testedInputs.stability)
        assertTrue(receipt.testedInputs.eligible)
        assertEquals(FileVersion.of("def test_tier():\n    assert discount(cart, tier) == 5\n".toByteArray()), receipt.testedInputs.versions["tests/test_discount.py"], "tested inputs are the manifest's bytes")
        assertEquals(env.envId, receipt.envId)
        assertEquals(1, receipt.exitCode)
        assertTrue(Files.exists(store.blobs.path(receipt.raw!!)))
        assertEquals(receipt, SqliteReceipts(store, clock).get(receipt.receiptId))

        val ledger = assertNotNull(result.ledger)
        assertEquals("#1", ledger.alias)
        val entry = ledger.entries.single()
        assertEquals("tests/test_discount.py::TestDiscount::test_tier[3]", entry.identity.display)
        assertEquals("AssertionError: assert 4 == 5", entry.signature)
        assertEquals(1, entry.multiplicity)
        assertTrue(ledger.render().startsWith("── Pre-existing failures (baseline #1 @${s0.hash8.take(4)}, 1) ──\n  tests/test_discount.py::TestDiscount::test_tier[3]: AssertionError: assert 4 == 5"), ledger.render())

        assertEquals(BaselineMatch.PreExisting, ledger.classify(failing()))
        assertEquals(BaselineMatch.New, ledger.classify(failing(file = "other/test_discount.py")), "same name in another module is a different test")
        assertEquals(BaselineMatch.New, ledger.classify(failing(parameterization = "5")), "another parameterized instance is a different test")
        assertEquals(BaselineMatch.Changed("AssertionError: assert 4 == 5"), ledger.classify(failing(message = "AssertionError: assert 3 == 5")))
        assertIs<BaselineMatch.Ambiguous>(ledger.classify(failing(), envId = Digest.ofUtf8("other-env")), "an incomparable environment never matches")
        assertEquals(BaselineMatch.New, ledger.classify(failing(name = "test_other")))
    }

    @Test
    fun `duplicate identities never match, signatures normalize volatile data, and no identities means nothing pre-existing`() {
        val duplicate = PreexistingLedger("rcpt-x", "#9", s0, env.envId, listOf(PreexistingFailure(failing().identity, "AssertionError", 2)), ambiguous = setOf(failing().identity.canonical))
        assertIs<BaselineMatch.Ambiguous>(duplicate.classify(failing()))
        assertTrue(duplicate.render().contains("(×2)"))
        assertEquals("Timeout at 0x… after <time> in <tmp>", PreexistingLedger.signatureOf(TestResult(failing().identity, TestOutcome.Error, "Timeout at 0x7f3a1c2b4d10 after 2026-09-20T10:00:00Z in /tmp/pytest-of-dev/run1\nsecond line")))
        assertEquals("src/pay/total.py:41 AssertionError E1234", PreexistingLedger.signatureOf(TestResult(failing().identity, TestOutcome.Failed, "src/pay/total.py:41 AssertionError E1234")), "paths, error codes and literals stay")
        assertEquals("failed", PreexistingLedger.signatureOf(TestResult(failing().identity, TestOutcome.Failed, null)))
        val empty = PreexistingLedger("rcpt-y", null, s0, env.envId, emptyList(), emptySet(), listOf("no test identities parsed by generic: nothing can be called pre-existing"))
        assertEquals(BaselineMatch.New, empty.classify(failing()))
        assertTrue(empty.render().endsWith("none · no test identities parsed by generic: nothing can be called pre-existing"), empty.render())
    }

    @Test
    fun `a suite that rewrites its inputs in the candidate cannot certify them, and a timed-out baseline yields no ledger`() = runTest {
        val shared = baseline()
        val mutating = shared.run(
            suite("type pytest_output.txt&echo changed> tests\\test_discount.py&exit /b 1", "cat pytest_output.txt; echo changed > tests/test_discount.py; exit 1"),
            contractVersion = 1, s0 = s0,
        )
        assertEquals(Outcome.Failed, mutating.receipt.outcome, "the factual outcome stands")
        assertEquals(setOf("tests/test_discount.py"), mutating.receipt.testedInputs.mutatedDuringCheck)
        assertFalse(mutating.receipt.testedInputs.eligible)
        assertTrue(mutating.receipt.limits.any { it.kind == "input_mutation" })
        assertEquals("def test_tier():\n    assert discount(cart, tier) == 5\n", Files.readString(repo.resolve("tests/test_discount.py")), "the working tree is untouched by the candidate run")
        assertNull(mutating.ledger, "a mutated baseline cannot establish preexisting failures")

        val slow = shared.run(suite("ping -n 61 127.0.0.1 >NUL", "sleep 60"), contractVersion = 1, s0 = s0, timeoutSeconds = 1)
        assertEquals(Outcome.Timeout, slow.receipt.outcome)
        assertNull(slow.ledger, "nothing may be called pre-existing without a usable baseline receipt")

        val missing = shared.run(Check("CHK-full", CheckKind.Full, Selector.All, Closure.Unknown, CostClass.Expensive, Trigger.CampaignEnd, command = Command(listOf("no-such-runner-xyz"))), 1, s0)
        assertEquals(Outcome.Unavailable, missing.receipt.outcome)
        assertNull(missing.ledger)
    }
}
