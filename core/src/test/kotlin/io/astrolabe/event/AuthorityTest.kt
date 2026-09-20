package io.astrolabe.event

import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.java.JavaAuthority
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.ReviewScope
import io.astrolabe.verify.Verdict
import io.astrolabe.verify.VerdictOutcome
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorityTest {
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"))
    private val candidate = CandidateId(Digest.ofUtf8("c"))

    @Test
    fun `autonomous policy never accepts a weakening, denies non-allowlisted D-class effects and cannot answer`() = runTest {
        val authority = AutonomousAuthority()
        assertNull(authority.ask(Question("q1", 1, ids, "which DB?")))
        val denied = authority.approve(DClassRequest("d1", 1, ids, "run", listOf("pip", "install", "x"), null, "installs a package", "needs dep"))
        assertFalse(denied.approved)
        val allowed = authority.approve(DClassRequest("d2", 1, ids, "run", listOf("git", "push"), null, "pushes", "contract", contractAllowlisted = true))
        assertTrue(allowed.approved)
        val weakening = authority.resolve(AmendmentProposal("p1", 1, ids, Proposer.Model, "AC-1 cmd → -k 'not slow'", "slow suite", weakening = true))
        assertEquals(ResolutionOutcome.Rejected, weakening.outcome)
        val neutral = authority.resolve(AmendmentProposal("p2", 1, ids, Proposer.Model, "add AC-5", "more coverage", weakening = false))
        assertEquals(ResolutionOutcome.Pending, neutral.outcome)
        assertEquals(ResolutionOutcome.Accepted, AutonomousAuthority(AutonomousPolicy(acceptNonWeakening = true)).resolve(neutral.let {
            AmendmentProposal("p2", 1, ids, Proposer.Model, "add AC-5", "more coverage", weakening = false)
        }).outcome)
        assertNull(authority.review(ReviewRequest("r1", 1, ids, ReviewScope.Increment, candidate, "pkt-1", listOf("rollback safe"))))
    }

    @Test
    fun `a reply for a superseded revision is rejected not applied`() {
        val answer = Answer("q1", contractRevision = 2, text = "postgres")
        assertEquals(ReplyValidity.Superseded, Replies.check(answer, currentRevision = 3))
        assertEquals(ReplyValidity.Current, Replies.check(answer, currentRevision = 2))
        val verdict = Verdict("r1", 1, candidate, VerdictOutcome.Approve, confidence = 0.9, signedBy = "human:alice")
        assertEquals(ReplyValidity.Superseded, Replies.check(verdict, 2))
    }

    @Test
    fun `factual answers are evidence and requirement-changing answers are amendments at the data level`() {
        val factual = Answer("q1", 1, "the CLI builds handlers directly")
        assertFalse(factual.changesRequirements)
        val amending = Answer("q2", 1, "also cover the retry path", changesRequirements = true)
        assertTrue(amending.changesRequirements)
    }

    @Test
    fun `verdict validation and review request validation`() {
        assertFailsWith<IllegalArgumentException> { Verdict("r", 1, candidate, VerdictOutcome.InsufficientEvidence, confidence = 0.5, signedBy = "j") }
        val ok = Verdict("r", 1, candidate, VerdictOutcome.InsufficientEvidence, confidence = 0.5, signedBy = "j", missingCriterion = "rollback")
        assertFalse(ok.approved)
        assertFailsWith<IllegalArgumentException> { Verdict("r", 1, candidate, VerdictOutcome.Approve, confidence = 1.5, signedBy = "j") }
        assertFailsWith<IllegalArgumentException> { ReviewRequest("", 1, ids, ReviewScope.Campaign, candidate, "p", emptyList()) }
    }

    @Test
    fun `java authority bridge maps futures, null answers and exceptional completions`() = runTest {
        val java = object : JavaAuthority {
            override fun ask(question: Question): CompletableFuture<Answer?> =
                if (question.text == "fail") CompletableFuture.failedFuture(IllegalStateException("ui closed"))
                else CompletableFuture.completedFuture(Answer(question.id, question.contractRevision, "yes"))

            override fun approve(request: DClassRequest): CompletableFuture<Decision> =
                CompletableFuture.completedFuture(Decision(request.id, request.contractRevision, approved = true, reason = "human"))

            override fun resolve(proposal: AmendmentProposal): CompletableFuture<Resolution> =
                CompletableFuture.completedFuture(Resolution(proposal.id, proposal.contractRevision, ResolutionOutcome.Rejected, "human:bob"))

            override fun review(request: ReviewRequest): CompletableFuture<Verdict?> = CompletableFuture.completedFuture(null)
        }
        val authority = Authorities.fromJava(java)
        assertEquals("yes", assertNotNull(authority.ask(Question("q1", 1, ids, "ok?"))).text)
        assertNull(authority.ask(Question("q2", 1, ids, "fail")))
        assertTrue(authority.approve(DClassRequest("d", 1, ids, "run", listOf("x"), null, "e", "r")).approved)
        assertEquals(ResolutionOutcome.Rejected, authority.resolve(AmendmentProposal("p", 1, ids, Proposer.Model, "c", "r", true)).outcome)
        assertNull(authority.review(ReviewRequest("r1", 1, ids, ReviewScope.Increment, candidate, "pkt", listOf("c"))))
    }
}
