package io.astrolabe.delegate

import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.Reserves
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Cancellation
import io.astrolabe.cell.CellFixture
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Origin
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.evidence.Outcome
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.register.Register
import io.astrolabe.route.Tier
import io.astrolabe.verify.FindingKind
import io.astrolabe.verify.ReviewScope
import io.astrolabe.verify.VerdictOutcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P4.8.2: labelled injected-defect diffs the review cell must catch (D-172). The judge is scripted: what is checked is
 * the protocol — the defect reaches the judge's brief, the verdict parses and validates, a declined verdict keeps the
 * increment unaccepted and steers the owner at the labelled location — never the quality of a real model (P7).
 */
class InjectedDefectTest {
    @TempDir
    lateinit var stateRoot: Path

    private val criterion = Acceptance.Review("AC-rev", "a() returns 10 and its public signature is unchanged", Origin.User)

    /** One labelled defect: the injected diff line, where it is, what kind of finding catches it, and the scripted judge replies. */
    private data class Defect(
        val label: String,
        val kind: FindingKind,
        val location: String,
        val injected: String,
        val diff: String,
        val replies: List<String>,
        val red: Boolean = false,
    )

    private fun diff(path: String, minus: String, plus: String) = "--- a/$path\n+++ b/$path\n@@ -1,2 +1,2 @@\n$minus\n$plus\n"

    private fun finding(severity: String, location: String, issue: String, kind: String) =
        """{"severity":"$severity","location":"$location","issue":"$issue","suggestedFix":"restore the criterion","kind":"$kind"}"""

    private val defects = listOf(
        Defect(
            "off-by-one return", FindingKind.Correctness, "src/a.py:2", "+    return 9",
            diff("src/a.py", "-    return 1", "+    return 9"),
            listOf("""{"verdict":"revise","confidence":0.8,"findings":[${finding("major", "src/a.py:2", "returns 9, AC-rev needs 10", "correctness")}]}"""),
        ),
        Defect(
            "weakened assertion", FindingKind.TestIntegrity, "tests/test_a.py:2", "+    assert a() is not None",
            diff("tests/test_a.py", "-    assert a() == 1", "+    assert a() is not None"),
            listOf("""{"verdict":"reject","confidence":0.9,"findings":[${finding("blocker", "tests/test_a.py:2", "the assertion no longer checks the value", "test-integrity")}]}"""),
        ),
        Defect(
            "public signature changed", FindingKind.Contract, "src/a.py:1", "+def a(scale):",
            diff("src/a.py", "-def a():", "+def a(scale):"),
            listOf("""{"verdict":"reject","confidence":0.85,"contractViolations":["AC-rev: a() gained a parameter"],"findings":[${finding("major", "src/a.py:1", "a() gained a required parameter", "contract")}]}"""),
        ),
        // Protocol: an approval that carries a major finding is a gap, never a verdict; the judge must restate it.
        Defect(
            "approval hiding a major finding", FindingKind.Correctness, "src/a.py:2", "+    return 11",
            diff("src/a.py", "-    return 1", "+    return 11"),
            listOf(
                """{"verdict":"approve","confidence":0.7,"findings":[${finding("major", "src/a.py:2", "returns 11", "correctness")}]}""",
                """{"verdict":"revise","confidence":0.7,"findings":[${finding("major", "src/a.py:2", "returns 11, AC-rev needs 10", "correctness")}]}""",
            ),
        ),
        // Protocol: a finding without a path:line location is a gap.
        Defect(
            "unlocated finding", FindingKind.Quality, "src/b.py:1", "+x = None",
            diff("src/b.py", "-x = 1", "+x = None"),
            listOf(
                """{"verdict":"revise","confidence":0.6,"findings":[${finding("major", "somewhere in b", "x is None", "quality")}]}""",
                """{"verdict":"revise","confidence":0.6,"findings":[${finding("major", "src/b.py:1", "x is None", "quality")}]}""",
            ),
        ),
        // Protocol: an approval never stands over a failed required check, whatever the judge says.
        Defect(
            "approval over a red required check", FindingKind.Correctness, "", "+    return 7",
            diff("src/a.py", "-    return 1", "+    return 7"),
            listOf("""{"verdict":"approve","confidence":0.95,"findings":[]}"""),
            red = true,
        ),
    )

    private fun packet(f: CellFixture, d: Defect) = EvidencePacket(
        id = "evidence-${d.label.replace(' ', '-')}", ids = f.ids, scope = ReviewScope.Increment, incrementId = f.increment.id, contractVersion = f.contract.version,
        candidate = f.stamper.report().candidateId, requirements = emptyList(), criteria = listOf(ReviewCriterion.of(criterion)),
        diff = d.diff, diffRef = null,
        receipts = listOf(
            if (d.red) ReviewReceipt("rcpt-1", "CHK-accept", Outcome.Failed, "2 passed, 1 failed, 0 errors, 0 skipped", true)
            else ReviewReceipt("rcpt-1", "CHK-accept", Outcome.Passed, "3 passed, 0 failed, 0 errors, 0 skipped", true),
        ),
        notes = emptyList(), testIntegrity = emptyList(), preexisting = emptyList(), coverage = null, rubric = EvidencePacket.RUBRIC,
        evidenceVersions = listOf("src/a.py", "src/b.py", "tests/test_a.py").associateWith { f.version(it) }, triggers = listOf("review: item AC-rev"),
    )

    @Test
    fun `every labelled injected defect is caught through the judge protocol and steers the owner at its location`() = runTest {
        for (d in defects) {
            CellFixture(stateRoot.resolve(d.label.replace(' ', '-'))).use { f ->
                val p = packet(f, d)
                val model = ScriptedModel.of(*d.replies.map { Scripted.Reply(listOf(say(it))) }.toTypedArray())
                val briefs = ArrayList<String>()
                val cell = ChildCell { _, role, completion, budget, brief ->
                    briefs += brief
                    f.cell(completion = completion).run(f.context(model, role = role), f.increment, CellBudget.of(budget.tokens, budget.turns, Reserves()))
                }
                val cells = ReviewCell(CellReviewJudge(cell, f.idGen, Cancellation(), ReviewBudget(incrementTokens = Tokens(60_000))), AutonomousAuthority(), f.store, f.idGen, f.clock, f.journal)

                val declined = assertIs<ReviewOutcome.Declined>(cells.obtain(p, Tier.Medium, f.registry::version), d.label)
                assertTrue(d.injected in briefs.single(), "${d.label}: the injected line reaches the judge")
                assertEquals(0, model.remaining, "${d.label}: every scripted protocol round was asked for")
                val verdict = declined.record.verdict!!
                assertTrue(verdict.signedBy.startsWith("review-cell:"), d.label)
                assertEquals(p.id, verdict.requestId, d.label)
                if (d.red) {
                    assertEquals(VerdictOutcome.Approve, verdict.outcome)
                    assertTrue("CHK-accept failed" in declined.reason && "review cannot override them" in declined.reason, declined.reason)
                    continue
                }
                assertTrue(verdict.outcome == VerdictOutcome.Revise || verdict.outcome == VerdictOutcome.Reject, "${d.label}: ${verdict.outcome}")
                val caught = verdict.findings.single()
                assertEquals(d.kind, caught.kind, d.label)
                assertTrue(caught.location.startsWith(d.location + "@"), "${d.label}: ${caught.location} is pinned to the reviewed version")
                val steered = declined.steer(Register.empty(f.ids.context!!, f.increment.id, f.increment.title))
                assertTrue(steered.open.single().text.endsWith("at ${caught.location}"), "${d.label}: ${steered.open}")
                if (d.kind == FindingKind.Contract) assertEquals(listOf("AC-rev: a() gained a parameter"), verdict.contractViolations)
            }
        }
    }
}
