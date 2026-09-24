package io.astrolabe.verify

import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.tool.run.TestIdentity
import io.astrolabe.tool.run.TestOutcome
import io.astrolabe.tool.run.TestResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P3.5.2 §8.9 item 5: equivalence is proven by identities and outcomes, never by counts. */
class EquivalenceTest {
    private val s0 = CandidateId(Digest.ofUtf8("s0"))
    private val sn = CandidateId(Digest.ofUtf8("sn"))

    private fun t(name: String, outcome: TestOutcome = TestOutcome.Passed, file: String = "tests/test_a.py") =
        TestResult(TestIdentity(check = "CHK-full", file = file, name = name), outcome)

    private fun report(comparisons: List<TestComparison>, comparable: Boolean = true, goldens: List<GoldenComparison> = emptyList()) = EquivalenceReport(
        s0 = s0, sn = sn,
        suites = listOf(SuiteComparison("CHK-full", "r-s0", "r-sn", 2, 2, "passed", "passed", comparable)),
        preserved = comparisons.count { it.delta == TestDelta.Preserved },
        changed = comparisons.filter { it.delta == TestDelta.ChangedOutcome },
        missing = comparisons.filter { it.delta == TestDelta.Missing },
        added = comparisons.filter { it.delta == TestDelta.New },
        goldens = goldens,
    )

    @Test
    fun `the same identities with the same outcomes are equivalent`() {
        val base = listOf(t("test_one"), t("test_two"), t("test_skip", TestOutcome.Skipped))
        val comparisons = Equivalence.compare(base, base.reversed())
        assertEquals(List(3) { TestDelta.Preserved }, comparisons.map { it.delta })
        val report = report(comparisons)
        assertTrue(report.equivalent, report.render())
        assertEquals(3, report.preserved)
        assertEquals(emptyList(), report.newBehaviour)
        assertTrue(report.render().startsWith("equivalence @${sn.hash8} vs @${s0.hash8}: equivalent · 3 preserved"), report.render())
    }

    @Test
    fun `a removed test or a newly failing one breaks equivalence`() {
        val base = listOf(t("test_one"), t("test_two"))
        val removed = Equivalence.compare(base, listOf(t("test_one")))
        assertEquals(listOf(TestDelta.Preserved, TestDelta.Missing), removed.map { it.delta })
        assertFalse(report(removed).equivalent)
        assertEquals("tests/test_a.py::test_two", removed.last().identity)

        val failing = Equivalence.compare(base, listOf(t("test_one"), t("test_two", TestOutcome.Failed)))
        assertEquals(listOf(TestDelta.Preserved, TestDelta.ChangedOutcome), failing.map { it.delta })
        assertEquals(listOf("passed") to listOf("failed"), failing.last().s0 to failing.last().sn)
        val report = report(failing)
        assertFalse(report.equivalent)
        assertTrue(report.render().contains("changed tests/test_a.py::test_two: passed → failed"), report.render())
    }

    @Test
    fun `equal counts with different identities are not equivalence`() {
        val comparisons = Equivalence.compare(listOf(t("test_one"), t("test_two")), listOf(t("test_one"), t("test_two", file = "tests/test_b.py")))
        assertEquals(listOf(TestDelta.Preserved, TestDelta.Missing, TestDelta.New), comparisons.map { it.delta })
        val report = report(comparisons)
        assertFalse(report.equivalent, "2 tests before and 2 after, one of them a different identity")
        assertEquals(listOf("new test tests/test_b.py::test_two: new behaviour needs a requirement, not a silent extension"), report.newBehaviour)
        assertFalse(report(comparisons.filter { it.delta == TestDelta.Preserved }, comparable = false).equivalent, "a suite without identities never proves equivalence by counts")
    }

    @Test
    fun `a new test is new behaviour that needs a requirement while preserved behaviour stays equivalent`() {
        val comparisons = Equivalence.compare(listOf(t("test_one")), listOf(t("test_one"), t("test_extra")))
        assertEquals(listOf(TestDelta.Preserved, TestDelta.New), comparisons.map { it.delta })
        val report = report(comparisons)
        assertTrue(report.equivalent, "the preserved test kept its outcome")
        assertEquals(1, report.newBehaviour.size)
        assertTrue(report.render().contains("new test tests/test_a.py::test_extra: new behaviour needs a requirement"), report.render())
        // Multiplicities are compared, never collapsed (D-27): a repeated identity that lost one run is a change.
        val repeated = Equivalence.compare(listOf(t("test_p"), t("test_p")), listOf(t("test_p")))
        assertEquals(TestDelta.ChangedOutcome, repeated.single().delta)
        // A changed golden breaks equivalence on its own.
        val golden = GoldenComparison("golden/out.txt", Digest.ofUtf8("g"), same = false, detail = "3 bytes now, 2 at s0")
        assertFalse(report(comparisons, goldens = listOf(golden)).equivalent)
    }
}
