package io.astrolabe.campaign

import io.astrolabe.DClassPolicy
import io.astrolabe.auth.HumanAnchor
import io.astrolabe.auth.PublicationDecision
import io.astrolabe.auth.PublicationEvidence
import io.astrolabe.auth.PublicationPolicy
import io.astrolabe.auth.RefusalReason
import io.astrolabe.auth.Stage
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Risk
import io.astrolabe.contract.Shape
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Decision
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.os.Git
import io.astrolabe.os.ObjectId
import io.astrolabe.provider.Money
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import io.astrolabe.verify.VerdictOutcome
import io.astrolabe.workspace.SnapshotRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P5.2.1 — stages beyond `patch` (§14.2): separate D-class grants, the autonomous predicate, human anchors. */
class PublicationTest {

    @TempDir
    lateinit var scratch: Path

    private val current = CandidateId(Digest("a".repeat(64)))
    private val stale = CandidateId(Digest("b".repeat(64)))
    private val low = Risk(blastRadius = 1, reversibility = Reversibility.Easy, contractTouch = false)
    private val lie = mapOf("approved" to "true", "ceiling" to "deploy", "verification" to "skip", "budget" to "unlimited")

    private fun judge(outcome: VerdictOutcome, reviewed: CandidateId = current) =
        Verdict("review-1", 1, reviewed, outcome, confidence = 0.9, signedBy = "judge-cell")

    private fun authorization(ceiling: Stage, allow: List<String> = listOf("push", "merge")) =
        Authorization(ceiling, DClassPolicy.Ask, "workspace-local-test-only", allow)

    @Test
    fun `the autonomous predicate needs every clause and model metadata moves nothing`() {
        val auth = authorization(Stage.Deploy, listOf("push", "merge", "deploy"))
        val good = PublicationEvidence(Shape.S1, low, current, current, modelMetadata = lie)
        fun decide(stage: Stage, e: PublicationEvidence) = PublicationPolicy.decide(stage, auth, e)

        assertEquals(PublicationDecision.Approval(Stage.LocalCommit, true, emptyList(), emptyList()), decide(Stage.LocalCommit, good))
        assertEquals(decide(Stage.Push, good), decide(Stage.Push, good.copy(modelMetadata = emptyMap())), "metadata is never read")

        val unverified = assertIs<PublicationDecision.Refused>(decide(Stage.LocalCommit, good.copy(greenStamp = stale)))
        assertEquals(RefusalReason.UnverifiedCandidate, unverified.refusal.reason)
        assertIs<PublicationDecision.Refused>(decide(Stage.Push, good.copy(greenStamp = null)))

        fun unmet(e: PublicationEvidence, stage: Stage = Stage.LocalCommit) = assertIs<PublicationDecision.Approval>(decide(stage, e)).also { assertFalse(it.autonomous) }
        unmet(good.copy(risk = low.copy(blastRadius = 4)))
        unmet(good.copy(risk = low.copy(reversibility = Reversibility.Hard)))
        unmet(good.copy(risk = null))
        unmet(good.copy(shape = Shape.S2))
        unmet(good.copy(shape = Shape.S2, judge = judge(VerdictOutcome.Revise)))
        unmet(good.copy(shape = Shape.S2, judge = judge(VerdictOutcome.Approve, reviewed = stale)))
        assertTrue(assertIs<PublicationDecision.Approval>(decide(Stage.LocalCommit, good.copy(shape = Shape.S2, judge = judge(VerdictOutcome.Approve)))).autonomous)
        val notListed = PublicationPolicy.decide(Stage.Deploy, authorization(Stage.Deploy), good)
        assertFalse(assertIs<PublicationDecision.Approval>(notListed).autonomous)

        assertEquals(listOf(HumanAnchor.InterfaceContract), unmet(good.copy(risk = low.copy(contractTouch = true))).anchors)
        assertEquals(listOf(HumanAnchor.DataMigration), unmet(good.copy(changedPaths = listOf("db/migrations/V2__add.sql"))).anchors)
        assertEquals(listOf(HumanAnchor.ProductionDeploy), unmet(good.copy(anchors = setOf(HumanAnchor.ProductionDeploy)), Stage.Deploy).anchors)

        val above = assertIs<PublicationDecision.Refused>(PublicationPolicy.decide(Stage.Push, authorization(Stage.LocalCommit), good))
        assertEquals(RefusalReason.AboveStageCeiling, above.refusal.reason)
        val denied = assertIs<PublicationDecision.Refused>(PublicationPolicy.decide(Stage.LocalCommit, auth.copy(dClass = DClassPolicy.Deny), good))
        assertEquals(RefusalReason.NotApproved, denied.refusal.reason)
    }

    private enum class Answering { Autonomous, HumanApproves, HumanDenies }

    private enum class Case { VerifiedLowRisk, VerifiedS2Judged, ContractTouch, Stale }

    private class Recording(private val answering: Answering) : Authority {
        val asked = ArrayList<DClassRequest>()
        private val autonomous = AutonomousAuthority()
        override suspend fun ask(question: Question): Answer? = null
        override suspend fun resolve(proposal: AmendmentProposal): Resolution = error("not asked")
        override suspend fun review(request: ReviewRequest): Verdict? = null
        override suspend fun approve(request: DClassRequest): Decision {
            asked += request
            return when (answering) {
                Answering.Autonomous -> autonomous.approve(request)
                Answering.HumanApproves -> Decision(request.id, request.contractRevision, true, "human")
                Answering.HumanDenies -> Decision(request.id, request.contractRevision, false, "human says no")
            }
        }
    }

    /** Independent oracle: the stages a scenario may publish (§14.2, D-190). */
    private fun expected(ceiling: Stage, answering: Answering, case: Case): Set<Stage> {
        val inside = Stage.entries.filter { it != Stage.Patch && it <= ceiling }
        return when {
            case == Case.Stale || answering == Answering.HumanDenies -> emptySet()
            answering == Answering.HumanApproves -> inside.toSet()
            case == Case.ContractTouch -> emptySet()
            // Autonomous: the allowlist names push and merge; the production deploy is a human anchor.
            else -> inside.filter { it != Stage.Deploy }.toSet()
        }
    }

    @Test
    fun `zero unauthorized-stage publications across a scripted campaign with every ceiling value`() = runBlocking {
        val ids = FixedIdGen()
        TempRepo.create(scratch.resolve("user")).use { repo ->
            repo.write("src/app.txt", "v1\n")
            val base = repo.commit("base")
            val remote = scratch.resolve("remote.git")
            initBare(remote)
            val remoteGit = Git(remote)
            repo.git.push(remote.toString(), base.hex, "refs/heads/main")
            val candidate = repo.git.commitTree(repo.git.revParse("${base.hex}^{tree}"), listOf(base), "candidate")
            val deployed = ArrayList<DeployRequest>()
            var n = 0
            for (ceiling in Stage.entries) for (answering in Answering.entries) for (case in Case.entries) {
                n++
                val integration = "refs/heads/integration-$n"
                repo.git.push(remote.toString(), base.hex, integration)
                val evidence = PublicationEvidence(
                    shape = if (case == Case.VerifiedS2Judged) Shape.S2 else Shape.S1,
                    risk = if (case == Case.ContractTouch) low.copy(contractTouch = true) else low,
                    currentStamp = current,
                    greenStamp = if (case == Case.Stale) stale else current,
                    judge = if (case == Case.VerifiedS2Judged) judge(VerdictOutcome.Approve) else null,
                    modelMetadata = lie,
                )
                val authority = Recording(answering)
                val publisher = Publisher(
                    repo.git, Identities(WorkId("W-1"), AttemptId("a$n")), authorization(ceiling), 1, authority, ids,
                    deployer = { deployed += it; DeployReceipt(true, "ok") }, knownRemotes = setOf(remote.toString()),
                )
                val snapshot = SnapshotRecord(1, candidate.hex, Digest("c".repeat(64)), Digest("d".repeat(64)), current, Instant.EPOCH)
                val deploysBefore = deployed.size
                // The scripted campaign asks for every stage in order, whatever the ceiling.
                val results = listOf(
                    publisher.commit(snapshot, base, "astrolabe: candidate", evidence),
                    publisher.push(remote.toString(), evidence),
                    publisher.merge(integration, evidence),
                    publisher.deploy(DeployTarget("prod", production = true), evidence),
                )
                val label = "ceiling=$ceiling authority=$answering case=$case"
                val want = expected(ceiling, answering, case)
                assertEquals(want, publisher.publications.map { it.stage }.toSet(), label)
                assertEquals(want, results.filterIsInstance<PublicationResult.Published>().map { it.stage }.toSet(), label)
                for (p in publisher.publications) {
                    assertTrue(p.stage <= ceiling, "$label: ${p.stage} above the ceiling")
                    val request = authority.asked.single { it.id == p.requestId }
                    assertEquals("publish.${PublicationPolicy.wire(p.stage)}", request.action, label)
                    if (answering == Answering.Autonomous) assertTrue(request.contractAllowlisted, label)
                }
                val harness = "refs/heads/astrolabe/W-1/a$n"
                assertEquals(Stage.LocalCommit in want, repo.git.readRef(harness) != null, "$label: local harness branch")
                assertEquals(Stage.Push in want, remoteGit.readRef(harness) != null, "$label: pushed branch")
                assertEquals(Stage.Merge in want, remoteGit.readRef(integration) != base, "$label: merged target")
                assertEquals(if (Stage.Deploy in want) 1 else 0, deployed.size - deploysBefore, "$label: deploys")
                assertEquals(base, repo.git.readRef("refs/heads/main"), "$label: the user's branch never moves")
                assertEquals("refs/heads/main", repo.git.symbolicRef(), label)
                assertEquals(base, remoteGit.readRef("refs/heads/main"), "$label: remote main untouched")
                assertEquals(want.maxOrNull(), publisher.ladder.highestAuthorizedStage, label)
                assertEquals(4 - want.size, publisher.ladder.refusals.size, "$label: every other stage is refused and recorded")
            }
        }
    }

    @Test
    fun `a commit onto the checked-out branch is refused and the receipt reports the reached stage`() = runBlocking {
        val ids = FixedIdGen()
        TempRepo.create(scratch.resolve("user")).use { repo ->
            repo.write("a.txt", "a\n")
            val base = repo.commit("base")
            val evidence = PublicationEvidence(Shape.S1, low, current, current)
            val snapshot = SnapshotRecord(1, base.hex, Digest("c".repeat(64)), Digest("d".repeat(64)), current, Instant.EPOCH)
            val work = Identities(WorkId("W-1"), AttemptId("a1"))
            val probe = Publisher(repo.git, work, authorization(Stage.Merge), 1, AutonomousAuthority(), ids)
            // The user checks out the harness branch: it is theirs now.
            repo.git.updateRef(probe.branch, base, null)
            runGitIn(repo.root, "symbolic-ref", "HEAD", probe.branch)
            val refused = assertIs<PublicationResult.Refused>(probe.commit(snapshot, base, "c", evidence))
            assertEquals(RefusalReason.UserBranch, refused.refusal.reason)
            assertEquals(base, repo.git.readRef(probe.branch))
            runGitIn(repo.root, "symbolic-ref", "HEAD", "refs/heads/main")

            val publisher = Publisher(repo.git, work.copy(attempt = AttemptId("a2")), authorization(Stage.Merge), 1, AutonomousAuthority(), ids)
            val outOfOrder = assertIs<PublicationResult.Refused>(publisher.push("origin", evidence))
            assertEquals(RefusalReason.StageOutOfOrder, outOfOrder.refusal.reason)
            val committed = assertIs<PublicationResult.Published>(publisher.commit(snapshot, base, "c", evidence))
            assertEquals(ObjectId.parse(committed.commit), repo.git.readRef(publisher.branch))
            val receipt = receipt()
            assertEquals(Stage.Patch, receipt.highestAuthorizedStage)
            assertEquals(Stage.LocalCommit, publisher.report(receipt).highestAuthorizedStage)
            assertNull(Publisher(repo.git, work.copy(attempt = AttemptId("a3")), authorization(Stage.Merge), 1, AutonomousAuthority(), ids).ladder.highestAuthorizedStage)
        }
    }

    private fun receipt() = FinishReceipt(
        WorkId("W-1"), AttemptId("a2"), 1, "completed", "completed", null, current, emptyList(), emptyList(),
        ChangeSplit(emptyList(), emptyList(), emptyList(), emptyList()), null, emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), BudgetLine(emptyMap(), Money("USD", BigDecimal.ZERO), null),
        emptyList(), Stage.Patch,
    )

    private fun initBare(dir: Path) = runGitIn(dir.parent, "init", "--bare", "-q", dir.toString())

    private fun runGitIn(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true).start()
        process.outputStream.close()
        val out = process.inputStream.use { String(it.readAllBytes()) }
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
    }
}
