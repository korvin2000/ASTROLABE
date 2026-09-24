package io.astrolabe

import io.astrolabe.campaign.CampaignOutcome
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
import io.astrolabe.java.AstrolabeJava
import io.astrolabe.java.JavaAuthority
import io.astrolabe.provider.JavaInvocation
import io.astrolabe.provider.JavaProviderAdapter
import io.astrolabe.provider.Message
import io.astrolabe.provider.Role
import io.astrolabe.verify.ReviewRequest
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** P1.9.6 facade: `Astrolabe` open/campaign/await/cancel/amend/events and `AstrolabeJava` futures over the Java SPIs. */
class AstrolabeTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("Makefile", "test:\n\techo ok\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private val config get() = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all)

    @Test
    fun `a campaign opens in a project, runs, reports its events and ends with an honest outcome`() = runBlocking {
        Astrolabe(config, FakeAdapter(ScriptedModel.of(Scripted.Reply(listOf(Message.text(Role.Assistant, "done, trust me"))))), AutonomousAuthority()).use { sdk ->
            sdk.open(repo.root).use { project ->
                val handle = sdk.campaign(project, "make a return 10")
                val opened = withTimeout(10_000) { handle.events.first { it is AgentEvent.Campaign.Opened } }
                assertEquals(handle.workId, opened.ids.work)
                val outcome = withTimeout(60_000) { handle.await() }
                assertTrue(outcome != CampaignOutcome.Completed, "a done claim without receipts never completes")
                assertTrue(handle.done)
                assertEquals(1, project.views.contract(handle.workId).contracts.size)
            }
        }
    }

    @Test
    fun `cancel ends the campaign cancelled and a project runs one campaign at a time`() = runBlocking {
        val adapter = FakeAdapter(ScriptedModel.of(Scripted.Reply(listOf(Message.text(Role.Assistant, "thinking")))), holdResponses = true)
        Astrolabe(config, adapter, AutonomousAuthority()).use { sdk ->
            sdk.open(repo.root).use { project ->
                val handle = sdk.campaign(project, "make a return 10")
                withTimeout(10_000) { handle.events.first { it is AgentEvent.Cell.ModelRequested } }
                assertFailsWith<IllegalStateException> { sdk.campaign(project, "another request") }
                handle.amend("also keep b unchanged")
                handle.cancel()
                assertEquals(CampaignOutcome.Cancelled, withTimeout(10_000) { handle.await() })
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `AstrolabeJava drives a campaign through futures with Java SPI implementations`() {
        val kotlinAdapter = FakeAdapter(ScriptedModel.of(Scripted.Reply(listOf(Message.text(Role.Assistant, "done, trust me")))))
        val javaAdapter = object : JavaProviderAdapter {
            override fun id() = kotlinAdapter.id
            override fun capabilities(profile: io.astrolabe.provider.Profile) = kotlinAdapter.capabilities(profile)
            override fun validate(request: io.astrolabe.provider.Request, estimate: io.astrolabe.provider.Estimate) = kotlinAdapter.validate(request, estimate)
            override fun normalizer() = kotlinAdapter.normalizer
            override fun start(request: io.astrolabe.provider.Request, id: io.astrolabe.provider.InvocationId): JavaInvocation {
                val invocation = kotlinAdapter.start(request, id)
                return object : JavaInvocation {
                    override fun id() = invocation.id
                    override fun state() = invocation.state
                    override fun response() = GlobalScope.future { invocation.await() }
                    override fun cancel() = invocation.cancel()
                    override fun terminal() = GlobalScope.future { invocation.terminal() }
                }
            }
        }
        val auto = AutonomousAuthority()
        val authority = object : JavaAuthority {
            override fun ask(question: Question) = GlobalScope.future { auto.ask(question) }
            override fun approve(request: DClassRequest) = GlobalScope.future { auto.approve(request) }
            override fun resolve(proposal: AmendmentProposal) = GlobalScope.future { auto.resolve(proposal) }
            override fun review(request: ReviewRequest) = GlobalScope.future { auto.review(request) }
        }
        val seen = CopyOnWriteArrayList<EventRecord>()
        AstrolabeJava(config, javaAdapter, authority).use { sdk ->
            sdk.open(repo.root).use { project ->
                val handle = sdk.campaignBlocking(project, "make a return 10")
                handle.subscribe(EventSink { seen += it }).use {
                    val outcome = handle.await().get(60, TimeUnit.SECONDS)
                    assertTrue(outcome != CampaignOutcome.Completed)
                }
                assertTrue(handle.isDone())
                assertTrue(seen.all { it.event.ids.work == handle.workId() })
            }
        }
    }
}
