package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.Flags
import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.IndexTier
import io.astrolabe.atlas.OutlineIndex
import io.astrolabe.atlas.OutlineSource
import io.astrolabe.auth.Capability
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.kb.KbHit
import io.astrolabe.kb.RetrievalSource
import io.astrolabe.kb.Retriever
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Request
import io.astrolabe.provider.Text
import io.astrolabe.provider.ToolResult
import io.astrolabe.store.Store
import io.astrolabe.tool.Catalog
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.GeneratedTool
import io.astrolabe.tool.Mount
import io.astrolabe.tool.MountDescriptor
import io.astrolabe.tool.ToolLevel
import io.astrolabe.tool.ToolRegistration
import io.astrolabe.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P5.8.1 optional layers through the controller: FX-46 (a missing or failing tier-1 index or dense retrieval service
 * degrades to tier 0 / lexical and never blocks a cell) and FX-39 (generated tools and mounts inherit the caller's
 * ceiling); every layer is read only under its flag (D-251–D-253).
 */
class OptionalLayersCampaignTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-layers"), AttemptId("a1"), "make src/a.py return 10")
    private val policy = CampaignPolicy(Tokens(200_000))
    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("Makefile", "test:\n\techo ok\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
    }

    /** A fresh work per configuration: the attempt configuration, flags included, is frozen at its first open (invariant 12). */
    private fun work(id: String): CampaignRequest {
        val request = request.copy(work = WorkId(id))
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.Harness, scope = Contracts.TOUCHED))))
        }
        return request
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private fun controller(flags: Flags, layers: OptionalLayers) =
        Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, flags = flags), clock, idGen, layers = layers)

    /** Reads, runs [extra] calls, edits, verifies and completes; the campaign must not be blocked by any of [extra]. */
    private suspend fun complete(ctl: Controller, c: OpenedCampaign, vararg extra: Item): FakeAdapter {
        val v = c.registry.version("src/a.py")!!
        val adapter = FakeAdapter(ScriptedModel.of(
            Scripted.Reply(listOf(say("reading"), read("c1", "src/a.py")) + extra),
            Scripted.Reply(listOf(say("editing"), anchored("c2", "src/a.py", v, "    return 1", "    return 10"))),
            Scripted.Reply(listOf(say("verifying"), call("c3", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
            Scripted.Reply(listOf(say("done: a returns 10"))),
        ))
        val run = ctl.run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        return adapter
    }

    private fun text(request: Request): String = request.segments.flatMap { it.items }.joinToString("\n") { item ->
        when (item) {
            is Message -> item.text
            is ToolResult -> item.content.filterIsInstance<Text>().joinToString("") { it.text }
            else -> ""
        }
    }

    private fun boundary(c: OpenedCampaign): List<String> = c.journal.events(JournalScope(c.ids.work, kinds = setOf(JournalKind.Boundary))).map { it.text }

    private val kbSearch = call("k1", "kb", """{"op":"search","query":"return value","why":"prior notes on a"}""")

    @Test
    fun `FX-46 a failing tier-1 index degrades to tier 0 and never blocks the cell`() = runBlocking<Unit> {
        val asked = AtomicInteger()
        val failing = OutlineIndex { asked.incrementAndGet(); throw UnsatisfiedLinkError("no tree-sitter native for this platform") }
        // Off by default: the index is never consulted.
        val off = controller(Flags(), OptionalLayers(outlines = failing))
        off.open(repo.root, work("W-off"), policy).use { c -> complete(off, c) }
        assertEquals(0, asked.get())

        val ctl = controller(Flags(treeSitterIndex = true), OptionalLayers(outlines = failing))
        ctl.open(repo.root, work("W-on"), policy).use { c ->
            complete(ctl, c)
            assertTrue(asked.get() > 0)
            val degraded = boundary(c).filter { it.startsWith("index: ") }
            assertEquals(listOf("index: tier-1 index unavailable (no tree-sitter native for this platform); tier 0 outlines"), degraded)
        }
    }

    @Test
    fun `a tier-1 index that answers feeds the pre-scan graph and a per-file failure degrades that graph to tier 0`() = runBlocking<Unit> {
        val tier1 = OutlineIndex { atlas -> OutlineSource { path -> atlas.outline(path).copy(tier = IndexTier.Syntax, complete = true) } }
        val tier0 = controller(Flags(treeSitterIndex = true), OptionalLayers()).open(repo.root, work("W-0"), policy).use { it.impactPrescan }
        val syntax = controller(Flags(treeSitterIndex = true), OptionalLayers(outlines = tier1)).open(repo.root, work("W-1"), policy).use { it.impactPrescan }
        val lexical = "lexical graph is incomplete"
        assertTrue(lexical in tier0.unresolved && lexical !in syntax.unresolved, "a tier-1 graph is not the lexical one (D-213): ${tier0.unresolved} / ${syntax.unresolved}")

        val flaky = OutlineIndex { atlas -> OutlineSource { path -> if (path.endsWith(".py")) error("parser crashed on $path") else atlas.outline(path).copy(tier = IndexTier.Syntax, complete = true) } }
        val ctl = controller(Flags(treeSitterIndex = true), OptionalLayers(outlines = flaky))
        ctl.open(repo.root, work("W-2"), policy).use { c ->
            assertTrue(lexical in c.impactPrescan.unresolved, "one file at tier 0 keeps the graph at tier 0")
            complete(ctl, c)
            assertTrue(boundary(c).any { it.startsWith("index: tier-1 outline failed (parser crashed on src/a.py)") }, boundary(c).toString())
        }
    }

    @Test
    fun `FX-46 a failing dense retrieval service degrades kb search to lexical and never blocks the cell`() = runBlocking<Unit> {
        val cold = object : Retriever {
            override val source = RetrievalSource.Dense
            override suspend fun candidates(query: String, limit: Int): List<KbHit> = throw IllegalStateException("embedding service cold")
        }
        val ctl = controller(Flags(denseRetrieval = true), OptionalLayers(dense = cold))
        ctl.open(repo.root, work("W-cold"), policy).use { c ->
            val adapter = complete(ctl, c, kbSearch)
            val shown = text(adapter.calls[1].request)
            assertTrue("0 notes match 'return value' in kb · complete · degraded: dense retrieval unavailable: embedding service cold" in shown, shown)
        }

        val warm = object : Retriever {
            override val source = RetrievalSource.Dense
            override suspend fun candidates(query: String, limit: Int): List<KbHit> = listOf(KbHit("PIT-7", "PIT", "a() once returned a string"))
        }
        // Flag off: the dense source is never asked; flag on: its candidate joins the lexical answer.
        val off = controller(Flags(), OptionalLayers(dense = warm))
        off.open(repo.root, work("W-off"), policy).use { c ->
            assertTrue("PIT-7" !in text(complete(off, c, kbSearch).calls[1].request))
        }
        val on = controller(Flags(denseRetrieval = true), OptionalLayers(dense = warm))
        on.open(repo.root, work("W-on"), policy).use { c ->
            assertTrue("PIT-7 [PIT]: a() once returned a string" in text(complete(on, c, kbSearch).calls[1].request))
        }
    }

    @Test
    fun `FX-39 generated tools and mounts run under the caller's ceiling through the controller`() = runBlocking<Unit> {
        val tools = ToolRegistry()
        val fetcher = GeneratedTool(
            "fetcher", 1, ToolLevel.Project, listOf("git", "status", "--short"), EffectClass.R, setOf(Capability.RunLocal, Capability.Network),
            readme = "Fetches a page.", tests = listOf("CHK-tool-fetcher"),
        )
        assertIs<ToolRegistration.Registered>(tools.register(fetcher))
        val web = Mount("web", listOf(MountDescriptor("fetch", "Fetch a URL.")), localApproval = setOf("fetch"), capabilities = setOf(Capability.RunLocal, Capability.Network))
        val ctl = controller(Flags(generatedTools = true), OptionalLayers(tools = tools, mounts = Catalog.of(web)))
        ctl.open(repo.root, work("W-tools"), policy).use { c ->
            val adapter = complete(
                ctl, c,
                call("t1", "run", """{"argv":["tool:fetcher"]}"""),
                call("m1", "run", """{"argv":["mcp:web/fetch","{}"]}"""),
            )
            val shown = text(adapter.calls[1].request)
            assertEquals(2, Regex("denied by the capability ceiling: needs network").findAll(shown).count(), shown)
        }
    }
}
