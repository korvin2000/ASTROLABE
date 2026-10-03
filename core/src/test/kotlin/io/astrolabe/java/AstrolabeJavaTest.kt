package io.astrolabe.java

import io.astrolabe.Config
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.EventRecord
import io.astrolabe.event.EventSink
import io.astrolabe.event.Question
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.Message
import io.astrolabe.provider.Role
import io.astrolabe.verify.ProvenanceClass
import io.astrolabe.verify.ReviewRequest
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The Java facade mirrors the campaign's finish receipt and its provenance class (§4.4 C2) without `suspend` or `Flow`. */
class AstrolabeJavaTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        // A sniffed suite lets the campaign run; one refused at open has no finish receipt.
        repo.write("Makefile", "test:\n\techo ok\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `the Java handle returns the finish receipt whose class the campaign finished event carries`() {
        val auto = AutonomousAuthority()
        val authority = object : JavaAuthority {
            override fun ask(question: Question) = GlobalScope.future { auto.ask(question) }
            override fun approve(request: DClassRequest) = GlobalScope.future { auto.approve(request) }
            override fun resolve(proposal: AmendmentProposal) = GlobalScope.future { auto.resolve(proposal) }
            override fun review(request: ReviewRequest) = GlobalScope.future { auto.review(request) }
        }
        val adapter = FakeAdapter(ScriptedModel.of(Scripted.Reply(listOf(Message.text(Role.Assistant, "done, trust me")))))
        val seen = CopyOnWriteArrayList<EventRecord>()
        val config = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all)
        AstrolabeJava(config, adapter, authority, EstimatorFactory { HeuristicEstimator() }).use { sdk ->
            sdk.subscribe(EventSink { seen += it }).use {
                sdk.open(repo.root).use { project ->
                    val handle = sdk.campaignBlocking(project, "make a return 10")
                    handle.await().get(60, TimeUnit.SECONDS)
                    val finish = assertNotNull(handle.finish(), "an ended campaign leaves its finish receipt on the handle")
                    assertEquals(handle.workId(), finish.work)
                    assertEquals(ProvenanceClass.Unverified, finish.provenanceClass, "a done claim without receipts verifies nothing")
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (seen.none { it.event is AgentEvent.Campaign.Finished } && System.nanoTime() < deadline) Thread.sleep(10)
                    val finished = seen.map { it.event }.filterIsInstance<AgentEvent.Campaign.Finished>().single()
                    assertEquals(finish.provenanceClass.wire, finished.provenanceClass)
                }
            }
        }
    }
}
