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
        repo.write("build/test-results/test/TEST-stale.xml", """<testsuite tests="1"><testcase name="stale"/></testsuite>""")
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
    fun `baseline reports from s0 never become execution evidence through timestamps`() = runTest {
        val runner = baseline().also {
            it.beforeDispatch = {
                Files.walk(store.layout.candidates).use { files ->
                    files.filter { it.fileName.toString() == "TEST-stale.xml" }.forEach { path ->
                        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2100-01-01T00:00:00Z")))
                    }
                }
            }
        }
        val result = runner.run(suite("exit /b 1", "exit 1"), 1, s0)
        assertEquals(Outcome.Failed, result.receipt.outcome)
        assertTrue(result.receipt.tests!!.passed.isEmpty(), "a stale report was archived before dispatch")
        assertFalse(result.receipt.tests.reportComplete)
    }

    @Test
    fun `baseline dispatches use separate initial candidate directories`() = runTest {
        val runner = baseline().also { it.timeLeft = { 0L } }
        val check = suite("exit /b 0", "exit 0")
        val first = runner.run(check, 1, s0)
        val second = runner.run(check.copy(id = "another-check"), 1, s0)
        assertTrue(first.candidateDir != second.candidateDir, "concurrent checks must never share or replace a baseline export")
        assertEquals(recorded, Files.readString(first.candidateDir.resolve("pytest_output.txt")))
    }

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
        val begun = runner.begin(suite("type pytest_output.txt", "cat pytest_output.txt"), 1, s0)
        assertTrue(Regressions.isBaseline(begun) && begun.outcome == Outcome.Unavailable)
        val baseline = runner.run(suite("type pytest_output.txt&exit /b 1", "cat pytest_output.txt; exit 1"), contractVersion = 1, s0 = s0).receipt
        assertTrue(Regressions.isBaseline(baseline), baseline.limits.toString())
        val tests = assertNotNull(baseline.tests)
        val inherited = tests.failed.single()
        assertEquals("tests/test_discount.py::TestDiscount::test_tier[3]" to "AssertionError: assert 4 == 5", inherited.name to inherited.signature)
        assertEquals(Regressions.key(failing().identity), inherited.key, "the key digests the namespaced identity, never shown")
        assertEquals(emptyList(), tests.passed, "pytest's terminal summary names no passing test without -rA")
        assertEquals(baseline, SqliteReceipts(store, clock).get(baseline.receiptId), "the tests round-trip through the store")
        // A marker recorded before a run: never retried, never usable.
        assertNull(runner.tryBegin(suite("type pytest_output.txt", "cat pytest_output.txt"), 1, s0))
    }

    private val stampA = io.astrolabe.id.CandidateId(Digest.ofUtf8("A"))
    private val stampB = io.astrolabe.id.CandidateId(Digest.ofUtf8("B"))
    private val stampC = io.astrolabe.id.CandidateId(Digest.ofUtf8("C"))
    private var serial = 0

    private fun key(name: String) = Regressions.key(TestIdentity(file = "tests/t.py", name = name))

    private fun failure(name: String, message: String = "AssertionError: assert 1 == 2") =
        io.astrolabe.evidence.FailedTest(key(name), "tests/t.py::$name", Regressions.fingerprint(message, emptyList()), message, contentComplete = true)

    /** A receipt of the blast radius: [failed] and [passed] tests on [stamp]; [s0] marks a baseline. */
    private fun blast(
        outcome: Outcome, stamp: io.astrolabe.id.CandidateId, failed: List<io.astrolabe.evidence.FailedTest> = emptyList(), passed: List<String> = emptyList(),
        ambiguous: List<String> = emptyList(), command: List<String> = listOf("pytest", "tests"), s0: Boolean = false, counts: Counts? = null, envId: Digest = env.envId,
        truncated: Boolean = false, stability: InputStability = InputStability.Exclusive,
    ) = io.astrolabe.evidence.Receipt(
        receiptId = "r${++serial}", ids = ids, checkId = Checks.TESTS_BLAST, acceptanceIds = emptyList(), command = command, cwd = null, shell = false,
        stampBefore = stamp, stampAfter = stamp, envId = envId, verifierVersion = "v", checkDefinitionVersion = Digest.ofUtf8(command.joinToString(" ")), contractVersion = 1,
        outcome = outcome, parsed = counts ?: Counts(passed = passed.size, failed = failed.size, discovered = passed.size + failed.size), inputClosure = Closure.Known(emptySet()),
        testedInputs = io.astrolabe.evidence.TestedInputs(emptyMap(), stability), raw = null,
        limits = if (s0) listOf(io.astrolabe.evidence.Limit(Regressions.BASELINE, "on s0")) else emptyList(), at = clock.instant(),
        tests = io.astrolabe.evidence.TestOutcomes(failed, passed, ambiguous, truncated, reportComplete = true,
            multiplicity = (failed.map { it.key } + passed).groupingBy { it }.eachCount().mapValues { (key, count) -> if (key in ambiguous) maxOf(2, count) else count }),
    )

    private fun hold(history: List<io.astrolabe.evidence.Receipt>, stamp: io.astrolabe.id.CandidateId?, vararg baselines: io.astrolabe.evidence.Receipt) =
        Regressions.hold(history, baselines.toList(), stamp) { it.receiptId }

    @Test
    fun `later matching fingerprints cannot erase an earlier distinct failure`() {
        val baseline = blast(Outcome.Failed, stampA, listOf(failure("t", "OLD")), s0 = true)
        val changed = blast(Outcome.Failed, stampB, listOf(failure("t", "NEW")))
        val matching = blast(Outcome.Failed, stampB, listOf(failure("t", "OLD")))
        for (runs in listOf(listOf(changed, matching), listOf(matching, changed))) {
            assertEquals(RedClass.Unclassified, hold(runs, stampB, baseline)?.kind)
        }
    }

    @Test
    fun `an incomplete observation cannot acknowledge away a positively proven current regression`() {
        val baseline = blast(Outcome.Passed, stampA, passed = listOf(key("t")), s0 = true)
        val partial = blast(Outcome.Timeout, stampB, listOf(failure("t")))
        val complete = blast(Outcome.Failed, stampB, listOf(failure("t")))
        assertEquals(RedClass.Unclassified, hold(listOf(partial), stampB, baseline)?.kind)
        assertEquals(RedClass.New, hold(listOf(partial, complete), stampB, baseline)?.kind)
        assertEquals(RedClass.New, hold(listOf(complete, partial), stampB, baseline)?.kind)
        assertEquals(RedClass.Unclassified,
            hold(listOf(complete.copy(stampBefore = stampA, stampAfter = stampA), partial), stampB, baseline)?.kind)
    }

    @Test
    fun `report local totals never prove that an absent baseline identity passed`() {
        val baseline = blast(Outcome.Failed, stampA, listOf(failure("module_a")), s0 = true, counts = Counts(failed = 1, discovered = 1))
        val red = blast(Outcome.Failed, stampB, listOf(failure("module_b")))
        assertEquals(RedClass.Unclassified, hold(listOf(red), stampB, baseline)?.kind)
    }

    @Test
    fun `a failed receipt with only passing counts retains an unidentified failure`() {
        val red = blast(Outcome.Failed, stampA, passed = listOf(key("t")))
        val green = blast(Outcome.Passed, stampB, passed = listOf(key("other")))
        val held = assertNotNull(hold(listOf(red, green), stampB))
        assertEquals(RedClass.Unclassified, held.kind)
        assertTrue(held.unclassified.single().contains("did not identify"))
    }

    @Test
    fun `a partial red report never discharges an earlier failing identity`() {
        val red = blast(Outcome.Failed, stampA, listOf(failure("t", "NEW")))
        val baseline = blast(Outcome.Failed, stampA, listOf(failure("legacy", "OLD")), s0 = true)
        val partial = blast(Outcome.Failed, stampB, listOf(failure("legacy", "OLD")), listOf(key("t")))
            .let { it.copy(tests = it.tests!!.copy(reportComplete = false)) }
        val held = assertNotNull(hold(listOf(red, partial), stampB, baseline))
        assertEquals(RedClass.Unclassified, held.kind)
        assertTrue(held.unclassified.any { it.startsWith("tests/t.py::t") }, held.toString())
    }

    @Test
    fun `truncated ambiguity evidence cannot establish inherited failures`() {
        val duplicatePasses = (1..200).flatMap { n -> List(2) { TestResult(TestIdentity(file = "tests/t.py", name = "p$n"), TestOutcome.Passed) } }
        val duplicateFailures = listOf("NEW", "OLD").map { TestResult(TestIdentity(file = "tests/t.py", name = "t"), TestOutcome.Failed, it,
            failureContent = listOf(io.astrolabe.tool.run.FailureContent("failure", body = it)), failureContentComplete = true) }
        val outcomes = Regressions.outcomes(duplicatePasses + duplicateFailures, { it }, emptyList(), reportComplete = true)
        assertTrue(outcomes.truncated)
        val red = blast(Outcome.Failed, stampB, listOf(failure("t", "OLD"))).copy(tests = outcomes)
        val baseline = blast(Outcome.Failed, stampA, listOf(failure("t", "OLD")), s0 = true)
            .copy(tests = Regressions.outcomes(listOf(duplicateFailures.last()), { it }, emptyList(), reportComplete = true))
        assertEquals(RedClass.Unclassified, hold(listOf(red), stampB, baseline)?.kind)
    }

    @Test
    fun `an explicit pass discharges only earlier failures of the covered command and closure`() {
        val inputs = io.astrolabe.evidence.TestedInputs(mapOf("a" to FileVersion.of(byteArrayOf(1)), "b" to FileVersion.of(byteArrayOf(2))), InputStability.Exclusive)
        val red = blast(Outcome.Failed, stampA, listOf(failure("t"))).copy(inputClosure = Closure.Known(setOf("a", "b")), testedInputs = inputs)
        val pass = blast(Outcome.Passed, stampB, passed = listOf(key("t"))).copy(inputClosure = Closure.Known(setOf("a", "b")), testedInputs = inputs)
        assertNull(hold(listOf(red, pass), stampB))
        assertNotNull(hold(listOf(red, pass.copy(command = listOf("pytest", "other"))), stampB))
        assertNotNull(hold(listOf(red, pass.copy(inputClosure = Closure.Known(setOf("a")))), stampB))
        assertNotNull(hold(listOf(pass, red.copy(stampBefore = stampB, stampAfter = stampB)), stampB))
        assertNull(hold(listOf(red.copy(stampBefore = stampB, stampAfter = stampB), pass), stampB))
        assertNotNull(hold(listOf(red.copy(inputClosure = Closure.Unknown), pass.copy(inputClosure = Closure.Unknown)), stampB))
        assertNotNull(hold(listOf(red, pass.copy(testedInputs = inputs.copy(versions = emptyMap()))), stampB))
    }

    @Test
    fun `uncapped receipt sequences have positive discharge or inheritance evidence for every failure`() {
        val baseline = blast(Outcome.Failed, stampA, listOf(failure("t", "OLD")), s0 = true)
        val red = blast(Outcome.Failed, stampB, listOf(failure("t", "OLD")))
        val pass = blast(Outcome.Passed, stampB, passed = listOf(key("t")))
        val choices = listOf(red, red.copy(tests = red.tests!!.copy(failed = listOf(failure("t", "NEW")))),
            red.copy(stampBefore = stampA, stampAfter = stampA), pass,
            pass.copy(tests = pass.tests!!.copy(reportComplete = false)),
            pass.copy(tests = pass.tests.copy(ambiguous = listOf(key("t")), multiplicity = mapOf(key("t") to 2))),
            pass.copy(command = listOf("other")), pass.copy(stampBefore = stampA, stampAfter = stampA))
        for (length in 1..4) {
            var sequences = listOf(emptyList<Int>())
            repeat(length) { sequences = sequences.flatMap { prefix -> choices.indices.map { prefix + it } } }
            for (sequence in sequences) for (check in Regressions.CHECKS) {
                val records = sequence.mapIndexed { index, choice -> choices[choice].copy(receiptId = "sequence-$index", checkId = check) }
                val result = hold(records, stampB, baseline.copy(checkId = check))
                if (result != null && result.kind != RedClass.Inherited) continue
                val pending = sequence.indices.filter { sequence[it] in setOf(0, 1, 2) && sequence.drop(it + 1).none { later -> later == 3 } }
                val inherited = pending.all { sequence[it] == 0 || sequence[it] == 2 } && pending.any { sequence[it] == 0 }
                assertTrue(pending.isEmpty() || inherited, "uncapped without positive evidence for $check sequence $sequence")
            }
        }
    }

    @Test
    fun `full content fingerprints distinguish literal root markers and normalize roots before framing`() {
        assertTrue(Regressions.fingerprint("assert /tmp/run/x", listOf("/tmp/run")) != Regressions.fingerprint("assert <root>/x", emptyList()))
        assertTrue(Regressions.fingerprint("expected text a\r\nb", emptyList()) != Regressions.fingerprint("expected text a\nb", emptyList()))
        fun content(body: String) = TestResult(TestIdentity(file = "tests/t.py", name = "t"), TestOutcome.Failed,
            failureContent = listOf(io.astrolabe.tool.run.FailureContent("failure", mapOf("message" to body), body)), failureContentComplete = true)
        val a = Regressions.outcomes(listOf(content("at C:\\repo\\tests\\t.py:1")), { it }, listOf("C:\\repo"), reportComplete = true)
        val b = Regressions.outcomes(listOf(content("at /much/longer/candidate/tests/t.py:1")), { it }, listOf("/much/longer/candidate"), reportComplete = true)
        assertEquals(a.failed.single().fingerprint, b.failed.single().fingerprint)
        assertTrue(a.failed.single().contentComplete)
    }

    @Test
    fun `ledger comparison uses complete unredacted evidence and positive baseline passes`() {
        fun result(body: String) = TestResult(TestIdentity(file = "test", name = "t"), TestOutcome.Failed, "same summary",
            failureContent = listOf(io.astrolabe.tool.run.FailureContent("failure", mapOf("message" to "same summary"), body)), failureContentComplete = true)
        val first = result("expected 0x01 but secretAAA")
        val evidence = Regressions.outcomes(listOf(first, TestResult(TestIdentity(file = "test", name = "passed"), TestOutcome.Passed)), { "[REDACTED]" }, emptyList(), reportComplete = true)
        val ledger = PreexistingLedger("baseline", null, stampA, env.envId, emptyList(), emptySet(), evidence = evidence)
        assertEquals(BaselineMatch.PreExisting, ledger.classify(first))
        assertIs<BaselineMatch.Changed>(ledger.classify(result("expected 0x02 but secretAAA")))
        assertIs<BaselineMatch.Changed>(ledger.classify(result("expected 0x01 but secretBBB")))
        assertEquals(BaselineMatch.New, ledger.classify(first.copy(identity = TestIdentity(file = "test", name = "passed"))))
        assertIs<BaselineMatch.Ambiguous>(ledger.classify(first.copy(identity = TestIdentity(file = "test", name = "absent"))))
        assertFalse(ledger.render().contains(evidence.failed.single().key))
        assertFalse(ledger.render().contains(evidence.failed.single().fingerprint))
    }

    @Test
    fun `a failure is held by its identity until it executed and passed alone in a finished run on this tree`() {
        val red = blast(Outcome.Failed, stampA, listOf(failure("total")), listOf(key("other")))
        assertEquals(listOf("r1"), hold(listOf(red), stampA)?.current, "a red run on this tree is current")
        // `pytest -x`: the next red stops before `total`; a later red never erases an earlier failure.
        val legacy = blast(Outcome.Failed, stampB, listOf(failure("legacy")))
        val x = assertNotNull(hold(listOf(red, legacy), stampB))
        assertTrue(x.unclassified.any { it.startsWith("tests/t.py::total: failed in r1, not executed on this tree") }, x.toString())
        assertTrue(x.unclassified.any { it.startsWith("tests/t.py::legacy") }, x.toString())
        // Removed, skipped or renamed: a green run that did not execute it proves nothing — unclassified, not current.
        val deleted = assertNotNull(hold(listOf(red, blast(Outcome.Passed, stampB, passed = listOf(key("other")))), stampB))
        assertEquals(RedClass.Unclassified to emptyList<String>(), deleted.kind to deleted.current)
        // Executed and passed alone in a finished run on this tree: shown fixed.
        assertNull(hold(listOf(red, blast(Outcome.Passed, stampB, passed = listOf(key("total"), key("other")))), stampB))
        // Codex 2: a pass reported beside another case of the same identity (a skipped one) is ambiguous: nothing shown.
        val twice = assertNotNull(hold(listOf(red, blast(Outcome.Passed, stampB, passed = listOf(key("total")), ambiguous = listOf(key("total")))), stampB))
        assertTrue(twice.unclassified.single().contains("ambiguous"), twice.toString())
        // Codex 1: a run on this tree that timed out clears nothing, and the failure it did see is held.
        val hung = blast(Outcome.Timeout, stampB, listOf(failure("y")), listOf(key("total")), counts = Counts(passed = 1, failed = 1, discovered = 3))
        val timedOut = assertNotNull(hold(listOf(red, hung), stampB))
        assertTrue(timedOut.unclassified.any { it.startsWith("tests/t.py::total: failed in r1; the run on this tree did not finish") }, timedOut.toString())
        assertTrue(timedOut.unclassified.any { it.startsWith("tests/t.py::y") } && timedOut.current == listOf(hung.receiptId), timedOut.toString())
        // A truncated record clears nothing either (Codex 3).
        assertNotNull(hold(listOf(red, blast(Outcome.Passed, stampB, passed = listOf(key("total")), truncated = true)), stampB))
        // A pass on an earlier tree is history: the failure returned at C is held, and the stop reruns its command there.
        val fixedAtB = blast(Outcome.Passed, stampB, passed = listOf(key("total")))
        val atC = assertNotNull(hold(listOf(red, fixedAtB), stampC))
        assertTrue(atC.unclassified.single().endsWith("not rerun on this tree"), atC.toString())
        assertEquals(listOf(red), Regressions.unconfirmed(listOf(red, fixedAtB), emptyList(), stampC))
        assertEquals(emptyList(), Regressions.unconfirmed(listOf(red, fixedAtB, blast(Outcome.Timeout, stampC)), emptyList(), stampC), "once per definition and tree")
        val begun = blast(Outcome.NotRun, stampC, counts = Counts()).copy(tests = null, limits = listOf(io.astrolabe.evidence.Limit(Regressions.RERUN, "began")))
        assertEquals(emptyList(), Regressions.unconfirmed(listOf(red, fixedAtB, begun), emptyList(), stampC), "a rerun begun on record is never repeated (Codex 9)")
        assertTrue(assertNotNull(hold(listOf(red, fixedAtB, begun), stampC)).unclassified.single().endsWith("not rerun on this tree"), "the marker reports nothing")
        // The strict rule discharges earlier failures even on this tree, but never a later failure.
        assertNull(hold(listOf(red, blast(Outcome.Passed, stampA, passed = listOf(key("total")))), stampA))
        assertNotNull(hold(listOf(blast(Outcome.Passed, stampA, passed = listOf(key("total"))), red), stampA))
        // A failure an ineligible run saw is held all the same, never classified by it; a baseline is never a run of the change.
        val background = assertNotNull(hold(listOf(blast(Outcome.Failed, stampA, listOf(failure("total")), stability = InputStability.Unknown)), stampA))
        assertTrue(background.unclassified.single().contains("cannot certify"), background.toString())
        assertNull(hold(listOf(blast(Outcome.Failed, stampA, listOf(failure("total")), s0 = true)), stampA))
        // Codex 3: failures counted but not identified, or cut from the record, stay whatever later passes say.
        val unidentified = blast(Outcome.Failed, stampA, counts = Counts(failed = 1, discovered = 1))
        assertTrue(assertNotNull(hold(listOf(unidentified, blast(Outcome.Passed, stampB, passed = listOf(key("total")), command = unidentified.command)), stampB))
            .unclassified.single().contains("did not identify"), "a passing run of the same command proves nothing one by one")
        val many = (1..Regressions.MAX_FAILED).map { failure("t$it") }
        val cut = blast(Outcome.Failed, stampA, many, counts = Counts(failed = Regressions.MAX_FAILED + 1, discovered = Regressions.MAX_FAILED + 1), truncated = true)
        val allPass = blast(Outcome.Passed, stampB, passed = many.map { it.key })
        assertTrue(assertNotNull(hold(listOf(cut, allPass), stampB)).unclassified.single().contains("did not identify"), "the 201st failure stays")
    }

    @Test
    fun `a failure is new only on positive evidence from s0, and inherited only on the same fingerprint`() {
        val now = blast(Outcome.Failed, stampA, listOf(failure("total", "AssertionError: assert total(3) == 1234567")), listOf(key("other")))
        val clean = blast(Outcome.Passed, stampA, passed = listOf(key("total"), key("other")), s0 = true)
        val regression = assertNotNull(hold(listOf(now), stampA, clean))
        assertEquals(RedClass.New, regression.kind)
        assertTrue(regression.regressions.single().contains("passed on s0"), regression.toString())
        assertEquals(RedClass.Unclassified, hold(listOf(blast(Outcome.Failed, stampA, listOf(failure("brand_new")))), stampA, clean)?.kind, "only an explicitly reported pass at s0 proves New")
        // Codex 7: a fail-fast baseline that stopped before the test proves nothing about it.
        val stopped = blast(Outcome.Failed, stampA, listOf(failure("a")), s0 = true, counts = Counts(failed = 1, discovered = 5))
        val failFast = assertNotNull(hold(listOf(blast(Outcome.Failed, stampA, listOf(failure("b")), listOf(key("a")))), stampA, stopped))
        assertEquals(RedClass.Unclassified, failFast.kind)
        assertTrue(failFast.unclassified.single().contains("stopped before showing it on s0"), failFast.toString())
        // The same failure on s0: inherited.
        val same = blast(Outcome.Failed, stampA, listOf(failure("total", "AssertionError: assert total(3) == 1234567")), listOf(key("other")), s0 = true)
        assertEquals(RedClass.Inherited, hold(listOf(now), stampA, same)?.kind)
        // Another number in the assertion is another failure: never inherited.
        val number = blast(Outcome.Failed, stampA, listOf(failure("total", "AssertionError: assert total(3) == 7654321")), s0 = true)
        assertTrue(assertNotNull(hold(listOf(now), stampA, number)).unclassified.single().contains("failed on s0 another way"))
        // Codex 5: durations and addresses in the text are the assertion's: they tell failures apart.
        assertTrue(Regressions.fingerprint("expected latency 1 ms, got 2 ms", emptyList()) != Regressions.fingerprint("expected latency 1 ms, got 999 ms", emptyList()))
        assertTrue(Regressions.fingerprint("flag 0x01", emptyList()) != Regressions.fingerprint("flag 0x02", emptyList()))
        // Only the run's roots are neutral (Codex 11: whichever separators); a shifted line is another failure, unclassified, never new.
        assertEquals(Regressions.fingerprint("assert x at C:\\repo\\tests\\x.py:12", listOf("C:\\repo")), Regressions.fingerprint("assert x at /repo/tests/x.py:12", listOf("/repo")))
        assertTrue(Regressions.fingerprint("assert x at tests/x.py:12", emptyList()) != Regressions.fingerprint("assert x at tests/x.py:14", emptyList()))
        // Unclassified: an ambiguous identity, another environment, no baseline, a baseline with unidentified failures, a begun one.
        assertEquals(RedClass.Unclassified, hold(listOf(now.copy(tests = now.tests!!.copy(ambiguous = listOf(key("total"))))), stampA, same)?.kind)
        assertEquals(RedClass.Unclassified, hold(listOf(now.copy(envId = Digest.ofUtf8("other-env"))), stampA, same)?.kind)
        assertTrue(assertNotNull(hold(listOf(now), stampA)).unclassified.single().contains(Regressions.NO_BASELINE))
        assertEquals(RedClass.Unclassified, hold(listOf(now), stampA, blast(Outcome.Failed, stampA, listOf(failure("total")), s0 = true, counts = Counts(failed = 2, discovered = 2)))?.kind)
        val begun = blast(Outcome.Unavailable, stampA, s0 = true).copy(tests = null)
        assertEquals(RedClass.Unclassified, hold(listOf(now), stampA, clean, begun)?.kind, "the latest baseline of the definition answers: an interrupted one")
        // Codex 8: one baseline per check and attempt — any baseline, of any definition, ends the due.
        assertEquals(now, Regressions.baselineDue(listOf(now), emptyList(), stampA))
        assertNull(Regressions.baselineDue(listOf(now), listOf(begun), stampA))
        assertNull(Regressions.baselineDue(listOf(now), listOf(blast(Outcome.Passed, stampA, passed = listOf(key("x")), command = listOf("pytest", "other"), s0 = true)), stampA))
    }

    @Test
    fun `what a run records shows redacted text and compares the whole unredacted failure, ambiguity over every reported case`() {
        fun result(name: String, outcome: TestOutcome, message: String? = null, detail: String? = null) = TestResult(TestIdentity(file = "tests/t.py", name = name), outcome, message, null, detail)
        val redact = { text: String -> text.replace(Regex("secret\\w+"), "[REDACTED]") }
        val one = Regressions.outcomes(listOf(result("test_login[secretAAA]", TestOutcome.Failed, "token secretAAA rejected"), result("same", TestOutcome.Passed), result("same", TestOutcome.Skipped)), redact, emptyList())
        val two = Regressions.outcomes(listOf(result("test_login[secretAAA]", TestOutcome.Failed, "token secretBBB rejected")), redact, emptyList())
        assertEquals("tests/t.py::test_login[[REDACTED]]" to "token [REDACTED] rejected", one.failed.first().name to one.failed.first().signature)
        assertEquals(one.failed.first().signature, two.failed.single().signature)
        assertTrue(one.failed.first().fingerprint != two.failed.single().fingerprint, "another secret is another failure")
        assertEquals(listOf(key("same")), one.ambiguous, "a passed and a skipped instance of one identity")
        // Codex 6: the JUnit XML body is part of the failure — `expected=1` and `expected=2` under one type differ.
        fun xml(body: String) = """<testsuite name="s"><testcase classname="C" name="t"><failure type="AssertionError">$body</failure></testcase></testsuite>""".toByteArray()
        val first = io.astrolabe.tool.run.JUnitXml.parse(xml("expected=1"), null, null).tests.single()
        val second = io.astrolabe.tool.run.JUnitXml.parse(xml("expected=2"), null, null).tests.single()
        assertEquals(first.message, second.message, "what is shown is the same")
        assertTrue(Regressions.outcomes(listOf(first), { it }, emptyList()).failed.single().fingerprint != Regressions.outcomes(listOf(second), { it }, emptyList()).failed.single().fingerprint)
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

        assertIs<BaselineMatch.Ambiguous>(ledger.classify(failing()), "terminal summaries do not establish complete failure content")
        assertIs<BaselineMatch.Ambiguous>(ledger.classify(failing(file = "other/test_discount.py")), "an absent identity is not a positive pass")
        assertIs<BaselineMatch.Ambiguous>(ledger.classify(failing(parameterization = "5")), "another instance was not reported passed")
        assertIs<BaselineMatch.Ambiguous>(ledger.classify(failing(message = "AssertionError: assert 3 == 5")), "an incomplete baseline cannot classify even a different message")
        assertIs<BaselineMatch.Ambiguous>(ledger.classify(failing(), envId = Digest.ofUtf8("other-env")), "an incomparable environment never matches")
        assertIs<BaselineMatch.Ambiguous>(ledger.classify(failing(name = "test_other")))
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
        assertIs<BaselineMatch.Ambiguous>(empty.classify(failing()))
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
