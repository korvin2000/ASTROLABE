package io.astrolabe.delegate

import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reserves
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Cancellation
import io.astrolabe.cell.CellFixture
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Origin
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.evidence.Outcome
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.register.Register
import io.astrolabe.route.RoutingFunction
import io.astrolabe.route.Tier
import io.astrolabe.verify.AcceptanceSurface
import io.astrolabe.verify.Finding
import io.astrolabe.verify.FindingKind
import io.astrolabe.verify.Resolution
import io.astrolabe.verify.Resolver
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.ReviewScope
import io.astrolabe.verify.Severity
import io.astrolabe.verify.TestIntegrityFlag
import io.astrolabe.verify.Verdict
import io.astrolabe.verify.VerdictOutcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P4.4.3: the review cell and the judge protocol at increment scope (FX-30, FX-53, FX-13 review path). */
class ReviewCellTest {
    @TempDir
    lateinit var stateRoot: Path

    private val review = Acceptance.Review("AC-rev", "the two migrations are safe to apply in order", Origin.User)

    private fun packet(f: CellFixture, receipts: List<ReviewReceipt> = listOf(ReviewReceipt("rcpt-1", "CHK-accept", Outcome.Passed, "3 passed, 0 failed, 0 errors, 0 skipped", true))) = EvidencePacket(
        id = "evidence-1", ids = f.ids, scope = ReviewScope.Increment, incrementId = f.increment.id, contractVersion = f.contract.version,
        candidate = f.stamper.report().candidateId, requirements = emptyList(), criteria = listOf(ReviewCriterion.of(review)),
        diff = "--- a/src/a.py\n+++ b/src/a.py\n@@ -1,2 +1,2 @@\n def a():\n-    return 1\n+    return 10\n", diffRef = null, receipts = receipts,
        notes = emptyList(), testIntegrity = emptyList(), preexisting = emptyList(), coverage = null, rubric = EvidencePacket.RUBRIC,
        evidenceVersions = mapOf("src/a.py" to f.version("src/a.py")), triggers = listOf("review: item AC-rev"),
    )

    private fun verdict(p: EvidencePacket, outcome: VerdictOutcome, findings: List<Finding> = emptyList()) =
        Verdict(p.id, p.contractVersion, p.candidate, outcome, findings, confidence = 0.8, signedBy = "review-cell:judge")

    /** A scripted judge: one answer per call, recording the tiers it was asked at. */
    private class ScriptedJudge(vararg answers: JudgeRun) : ReviewJudge {
        private val queue = ArrayDeque(answers.toList())
        val tiers = ArrayList<Tier>()

        override suspend fun judge(packet: EvidencePacket, tier: Tier): JudgeRun {
            tiers += tier
            return queue.removeFirst()
        }
    }

    @Test
    fun `FX-30 - a fresh judge lacking the rollback criterion answers insufficient_evidence naming it`() = runTest {
        CellFixture(stateRoot).use { f ->
            val p = packet(f)
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("""{"verdict":"insufficient_evidence","confidence":0.4}"""))),
                Scripted.Reply(listOf(say("""{"verdict":"insufficient_evidence","confidence":0.4,"missingCriterion":"rollback: every migration has a down step","findings":[]}"""))),
            )
            val briefs = ArrayList<String>()
            val cell = ChildCell { seat, role, completion, budget, brief ->
                briefs += brief
                assertEquals(Roles.review, role)
                assertEquals(RoutingFunction.ReviewRoutine, seat.function)
                f.cell(completion = completion).run(f.context(model, role = role), f.increment, CellBudget.of(budget.tokens, budget.turns, Reserves()))
            }
            // The fake profile reserves more output headroom per turn than the 30K estimate of §8.8 admits (D-123).
            val cells = ReviewCell(CellReviewJudge(cell, f.idGen, Cancellation(), ReviewBudget(incrementTokens = Tokens(60_000))), AutonomousAuthority(), f.store, f.idGen, f.clock, f.journal)

            val obtained = cells.obtain(p, Tier.Medium, f.registry::version)
            val outcome = assertIs<ReviewOutcome.Declined>(obtained, obtained.toString())

            val verdict = outcome.record.verdict!!
            assertEquals(VerdictOutcome.InsufficientEvidence, verdict.outcome)
            assertEquals("rollback: every migration has a down step", verdict.missingCriterion)
            assertTrue(outcome.reason.contains("insufficient_evidence") && outcome.reason.contains("missing criterion: rollback: every migration has a down step"), outcome.reason)
            assertTrue(verdict.signedBy.startsWith("review-cell:"), verdict.signedBy)
            assertEquals(listOf("src/a.py"), verdict.coverage!!.unread, "coverage is telemetry: the judge never looked at the changed file")
            assertEquals(p.candidate, verdict.reviewedCandidate)
            val brief = briefs.single()
            assertTrue(brief.contains("AC-rev [user, v1]: review: the two migrations are safe to apply in order") && brief.contains("rcpt-1 CHK-accept: passed"), brief)
            assertFalse(brief.contains(CellFixture.REQUEST + "\n"), "the evidence packet, never the proposer's transcript")
            assertEquals(listOf(outcome.record), cells.records(f.ids))
        }
    }

    @Test
    fun `FX-53 - a required review returning revise after local tests pass keeps the increment unaccepted and steers the continuation`() = runTest {
        CellFixture(stateRoot).use { f ->
            val p = packet(f)
            val finding = Finding(Severity.Major, "src/a.py:2", "the migration has no down step", "add a down step", FindingKind.Contract)
            val cells = ReviewCell(ScriptedJudge(JudgeRun(verdict(p, VerdictOutcome.Revise, listOf(finding)), Tokens(900))), AutonomousAuthority(), f.store, f.idGen, f.clock, f.journal)

            val outcome = assertIs<ReviewOutcome.Declined>(cells.obtain(p, Tier.Medium, f.registry::version))

            assertTrue(p.failedRequired.isEmpty(), "the local tests passed")
            val contract = f.contract.copy(acceptance = f.contract.acceptance + review)
            val increment = f.increment.copy(accept = listOf(review.id))
            val register = Register.empty(f.ids.context!!, increment.id, increment.title)
            val gate = Resolver.increment(register, contract, increment, emptyMap(), verdicts = mapOf(review.id to outcome.record.verdict!!), candidate = p.candidate)
            assertEquals(Resolution.Rework, gate.resolution, "a substantive rejection is reworked (D-341)")
            assertTrue(gate.missing.single().contains("revise by review-cell:judge"), gate.missing.toString())
            val steered = outcome.steer(register)
            assertEquals(listOf("review major: the migration has no down step at src/a.py:2"), steered.open.map { it.text })
            assertEquals(1, outcome.pits.size, "findings at or above major are PIT candidates")
        }
    }

    @Test
    fun `FX-13 review path - a required review that cannot run is unavailable after one try per tier, never an endless gate`() = runTest {
        CellFixture(stateRoot).use { f ->
            val p = packet(f)
            val silent = ScriptedJudge(JudgeRun(null, Tokens(300), "the review cell ended failed: provider unavailable"))
            val none = assertIs<ReviewOutcome.Unavailable>(ReviewCell(silent, AutonomousAuthority(), f.store, f.idGen, f.clock, f.journal).obtain(p, Tier.Medium, f.registry::version))
            assertEquals(listOf(Tier.Medium), silent.tiers)
            assertEquals(listOf("medium", "human"), none.record.path)
            assertTrue(none.reason.contains("provider unavailable") && none.reason.contains("unverified"), none.reason)

            // I3 (D-341): the same candidate is never reviewed twice — the unverified result is reused, the judge is not asked.
            val again = ScriptedJudge(JudgeRun(verdict(p, VerdictOutcome.Approve), Tokens(100)))
            val reused = assertIs<ReviewOutcome.Unavailable>(ReviewCell(again, AutonomousAuthority(), f.store, f.idGen, f.clock, f.journal).obtain(p, Tier.Medium, f.registry::version))
            assertEquals(emptyList(), again.tiers)
            assertTrue(reused.record.reused)

            val other = p.copy(id = "evidence-2", criteria = p.criteria + ReviewCriterion("AC-extra", "review: rollback documented", "user", 1))
            val escalating = ScriptedJudge(*Array(3) { JudgeRun(verdict(other, VerdictOutcome.Escalate), Tokens(100)) })
            val escalated = assertIs<ReviewOutcome.Unavailable>(ReviewCell(escalating, AutonomousAuthority(), f.store, f.idGen, f.clock, f.journal).obtain(other, Tier.Medium, f.registry::version))
            assertEquals(listOf(Tier.Medium, Tier.High, Tier.ExtraHigh), escalating.tiers, "escalate goes one tier up, then to a human")
            assertEquals(listOf("medium", "high", "extrahigh", "human"), escalated.record.path)
        }
    }

    @Test
    fun `under human integrity approval a stored model review is asked again for a person, and only a person's is reused`() = runTest {
        CellFixture(stateRoot).use { f ->
            val flag = TestIntegrityFlag("tests/test_a.py", AcceptanceSurface.TestFile, "edit #1", listOf("CHK-accept"), humanOnly = true)
            val p = packet(f).copy(testIntegrity = listOf(flag))
            var kind = io.astrolabe.verify.ReviewerKind.Model
            val asked = ArrayList<ReviewRequest>()
            val host = object : Authority by AutonomousAuthority() {
                override suspend fun review(request: ReviewRequest): Verdict { asked += request; return verdict(p, VerdictOutcome.Approve).copy(requestId = request.id, signedBy = "host", reviewer = kind) }
            }
            // The host path: the judge always defers to the host's authority.
            val cells = ReviewCell(ReviewJudge { _, _ -> JudgeRun(null, Tokens.ZERO, "host assessment required") }, host, f.store, f.idGen, f.clock, f.journal)
            assertIs<ReviewOutcome.Approved>(cells.obtain(p, Tier.Medium, f.registry::version))
            assertTrue(asked.single().humanOnly, "the request says only a person's verdict resolves its flag")
            kind = io.astrolabe.verify.ReviewerKind.Human
            val second = assertIs<ReviewOutcome.Approved>(cells.obtain(p.copy(id = "evidence-2"), Tier.Medium, f.registry::version))
            assertEquals(2, asked.size, "a model's stored review is no person's: the host is asked again")
            assertFalse(second.record.reused)
            val third = assertIs<ReviewOutcome.Approved>(cells.obtain(p.copy(id = "evidence-3"), Tier.Medium, f.registry::version))
            assertEquals(2, asked.size, "a person's review of this candidate is reused")
            assertTrue(third.record.reused && third.record.verdict?.reviewer == io.astrolabe.verify.ReviewerKind.Human)
            // Without human integrity approval a stored model review is reused as before (I3).
            val plain = p.copy(id = "evidence-4", testIntegrity = listOf(flag.copy(path = "tests/test_b.py", humanOnly = false)))
            kind = io.astrolabe.verify.ReviewerKind.Model
            assertIs<ReviewOutcome.Approved>(cells.obtain(plain, Tier.Medium, f.registry::version))
            assertFalse(asked.last().humanOnly)
            val before = asked.size
            assertTrue(assertIs<ReviewOutcome.Approved>(cells.obtain(plain.copy(id = "evidence-5"), Tier.Medium, f.registry::version)).record.reused)
            assertEquals(before, asked.size)
        }
    }

    @Test
    fun `a person's late answer to an earlier ask of the same question counts, and an unusable one is asked again`() = runTest {
        CellFixture(stateRoot).use { f ->
            val flag = TestIntegrityFlag("tests/test_a.py", AcceptanceSurface.TestFile, "edit #1", listOf("CHK-accept"), humanOnly = true)
            val p = packet(f).copy(testIntegrity = listOf(flag))
            val answers = ArrayDeque<(ReviewRequest) -> Verdict?>()
            val asked = ArrayList<ReviewRequest>()
            val host = object : Authority by AutonomousAuthority() {
                override suspend fun review(request: ReviewRequest): Verdict? { asked += request; return answers.removeFirst()(request) }
            }
            val cells = ReviewCell(ReviewJudge { _, _ -> JudgeRun(null, Tokens.ZERO, "host assessment required") }, host, f.store, f.idGen, f.clock, f.journal)
            fun person(id: String, candidate: io.astrolabe.id.CandidateId = p.candidate) =
                verdict(p, VerdictOutcome.Approve).copy(requestId = id, reviewedCandidate = candidate, signedBy = "user:alice", reviewer = io.astrolabe.verify.ReviewerKind.Human)
            // The person is not there yet: no verdict.
            answers += { null }
            assertIs<ReviewOutcome.Unavailable>(cells.obtain(p, Tier.Medium, f.registry::version))
            // A person's answer about another candidate is unusable: never reused as theirs, the host is asked again.
            answers += { person(it.id, io.astrolabe.id.CandidateId(io.astrolabe.id.Digest.ofUtf8("other"))) }
            assertIs<ReviewOutcome.Unavailable>(cells.obtain(p.copy(id = "evidence-2"), Tier.Medium, f.registry::version))
            assertEquals(listOf("evidence-1", "evidence-1"), asked.map { it.id }, "the same question keeps the id it was first asked under")
            // The person answers the first ask late, with its id: it counts, and it is reused afterwards.
            answers += { person("evidence-1") }
            assertTrue(assertIs<ReviewOutcome.Approved>(cells.obtain(p.copy(id = "evidence-3"), Tier.Medium, f.registry::version)).record.byPerson)
            assertTrue(assertIs<ReviewOutcome.Approved>(cells.obtain(p.copy(id = "evidence-4"), Tier.Medium, f.registry::version)).record.reused)
            assertEquals(3, asked.size)
            // Another question: an answer that names the packet asked now counts too.
            val q = p.copy(id = "evidence-5", criteria = p.criteria + ReviewCriterion("AC-extra", "review: rollback documented", "user", 1))
            answers += { null }
            assertIs<ReviewOutcome.Unavailable>(cells.obtain(q, Tier.Medium, f.registry::version))
            answers += { person("evidence-6") }
            assertTrue(assertIs<ReviewOutcome.Approved>(cells.obtain(q.copy(id = "evidence-6"), Tier.Medium, f.registry::version)).record.byPerson)
            assertEquals("evidence-5", asked.last().id)
        }
    }

    @Test
    fun `campaign scope asks the review cell first and the human path only without a verdict`() = runTest {
        CellFixture(stateRoot).use { f ->
            val p = packet(f).copy(scope = ReviewScope.Campaign, incrementId = null, triggers = listOf(ReviewTriggers.CAMPAIGN))
            val human = object : Authority by AutonomousAuthority() {
                override suspend fun review(request: ReviewRequest): Verdict = verdict(p, VerdictOutcome.Approve).copy(signedBy = "human:owner")
            }
            val judged = ReviewCellAuthority(human, ScriptedJudge(JudgeRun(verdict(p, VerdictOutcome.Revise), Tokens(10))), Tier.High) { p }
            assertEquals(VerdictOutcome.Revise, judged.review(p.request())!!.outcome)
            val fallback = ReviewCellAuthority(human, ScriptedJudge(JudgeRun(null, Tokens.ZERO, "no profile")), Tier.High) { p }
            assertEquals("human:owner", fallback.review(p.request())!!.signedBy)
            assertEquals(RoutingFunction.ReviewCritical, ReviewTriggers.function(p.triggers))
        }
    }

    @Test
    fun `a current approval is reused once, a changed dependency invalidates it, and no approval stands over a failed required check`() = runTest {
        CellFixture(stateRoot).use { f ->
            val p = packet(f)
            val judge = ScriptedJudge(JudgeRun(verdict(p, VerdictOutcome.Approve), Tokens(500)), JudgeRun(verdict(p, VerdictOutcome.Approve), Tokens(500)))
            val cells = ReviewCell(judge, AutonomousAuthority(), f.store, f.idGen, f.clock, f.journal)
            assertIs<ReviewOutcome.Approved>(cells.obtain(p, Tier.Medium, f.registry::version))
            val reused = assertIs<ReviewOutcome.Approved>(cells.obtain(p.copy(id = "evidence-2"), Tier.Medium, f.registry::version))
            assertTrue(reused.record.reused)
            assertEquals(1, judge.tiers.size, "obtain_required_review_once: the judge is not asked twice")
            val newlyRed = p.copy(receipts = listOf(ReviewReceipt("red", "CHK-accept", Outcome.Failed, "1 failed", true)))
            repeat(2) {
                val vetoed = assertIs<ReviewOutcome.Declined>(cells.obtain(newlyRed, Tier.Medium, f.registry::version))
                assertFalse(vetoed.record.approved)
                assertFalse(cells.records(p.ids).last().approved)
            }


            f.repo.write("src/a.py", CellFixture.A_PY.replace("return 1", "return 10"))
            assertEquals(Freshness.Stale, reused.record.freshness(p.contractVersion, p.candidate, f.registry::version))
            // D-341: the candidate stamp covers the tree, so an empty diff at the same candidate is current, never re-reviewed.
            assertEquals(Freshness.Current, reused.record.copy(evidenceVersions = emptyMap()).freshness(p.contractVersion, p.candidate, f.registry::version))
            assertIs<ReviewOutcome.Approved>(cells.obtain(p, Tier.Medium, f.registry::version))
            assertEquals(2, judge.tiers.size, "a changed dependency needs a fresh assessment")

            val red = packet(f, listOf(ReviewReceipt("rcpt-2", "CHK-accept", Outcome.Failed, "2 passed, 1 failed, 0 errors, 0 skipped", true))).copy(id = "evidence-3")
            val overridden = assertIs<ReviewOutcome.Declined>(ReviewCell(ScriptedJudge(JudgeRun(verdict(red, VerdictOutcome.Approve), Tokens(1))), AutonomousAuthority(), f.store, f.idGen, f.clock).obtain(red, Tier.Medium, f.registry::version))
            assertTrue(overridden.reason.contains("CHK-accept failed") && overridden.reason.contains("review cannot override them"), overridden.reason)
        }
    }

    @Test
    fun `increment triggers follow the protocol and a reviewer is never reviewed again, competing proposals are symmetric`() {
        CellFixture(stateRoot).use { f ->
            val contract = f.contract.copy(acceptance = f.contract.acceptance + review)
            val increment = f.increment.copy(accept = f.increment.accept + review.id)
            val flag = TestIntegrityFlag("tests/test_a.py", AcceptanceSurface.TestFile, "edit", listOf("CHK-accept"), kind = "weakened-assertion")
            val input = IncrementReviewInput(contract, increment, Roles.implementing, Tier.Low, listOf(flag), listOf("src/api.py"), mapOf("CON-api" to setOf("src/api.py")))
            val reasons = ReviewTriggers.increment(input)
            assertEquals(4, reasons.size, reasons.toString())
            assertTrue(reasons[0].startsWith("contract/ADR touch: CON-api") && reasons[1].startsWith("cheap-tier output") && reasons[2].startsWith("test-integrity flag") && reasons[3] == "review: item AC-rev", reasons.toString())
            assertEquals(RoutingFunction.ReviewCritical, ReviewTriggers.function(reasons))
            assertTrue(ReviewTriggers.increment(input.copy(role = Roles.review)).isEmpty(), "the reviewer never demands a review of its own verdict")
            assertTrue(ReviewTriggers.increment(IncrementReviewInput(f.contract, f.increment, Roles.implementing, Tier.High, emptyList(), listOf("src/a.py"), emptyMap())).isEmpty())
            assertFalse("verify.review" in Roles.review.toolMask.allowed)

            val estimator = HeuristicEstimator()
            val proposals = listOf(Proposal("p1", "short plan"), Proposal("p2", "a much longer plan ".repeat(200)))
            val shown = Judge.present(proposals, seed = 7, budgetTokens = 50, estimator = estimator)
            assertEquals(Judge.present(proposals, seed = 7, budgetTokens = 50, estimator = estimator), shown, "reproducible from the recorded seed")
            assertEquals(listOf("Proposal A", "Proposal B"), shown.map { it.label })
            assertEquals(setOf("p1", "p2"), shown.map { it.proposalId }.toSet())
            assertTrue(shown.all { estimator.estimate(it.text).upperBoundTokens <= 50 }, "equal budgets")
            assertEquals(Escalation.HigherTier(Tier.ExtraHigh), Judge.escalation(Tier.High))
            assertEquals(Escalation.Human, Judge.escalation(Tier.ExtraHigh))
        }
    }
}
