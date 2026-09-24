package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.Flags
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.context.Manifest
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Shape
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
import io.astrolabe.provider.Item
import io.astrolabe.store.Store
import io.astrolabe.telemetry.PrecompileMetrics
import io.astrolabe.telemetry.PrecompileReport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P3.7.1 boundary pre-compilation in the S1 loop (§6.6, FX-44): behind `Flags.precompile`, the next increment's
 * `[K]` is built at the completion proposal while verify-on-stop runs, served at the boundary only on a full
 * fingerprint match, and discarded with a journaled reason when an input moved.
 */
class PrecompileCampaignTest {
    @TempDir
    lateinit var root: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-p"), AttemptId("a1"), "make every f return its index times ten")
    private val policy = CampaignPolicy(Tokens(4_000_000))
    private val files = (1..3).map { "src/f$it.py" }

    /**
     * The acceptance command. A [mutating] one writes a build-artifact-like file into the tree on its second run
     * (the first run — the plan cell's verify-on-stop — only leaves [sentinel] outside the repository), so the tree
     * moves while I1's checks run and stays put afterwards.
     */
    private fun printing(mutating: Boolean, sentinel: Path): Command = when {
        WINDOWS && mutating -> Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt & if exist $sentinel (echo checked> src\\marker.txt) else (echo x> $sentinel)"))
        WINDOWS -> Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt"))
        mutating -> Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt; if [ -e $sentinel ]; then echo checked > src/marker.txt; else echo x > $sentinel; fi"))
        else -> Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
    }

    private val plan = (1..3).joinToString(",", prefix = """{"increments":[""", postfix = "]}") { i ->
        val depends = if (i > 1) ""","depends_on":["I${i - 1}"]""" else ""
        """{"id":"I$i","requirements":["R$i"],"accept":["AC-$i"],"write_scope":["src/"],"expected_files":1$depends,"produces":"artifact"}"""
    }

    private class Outcome(val run: S0Run, val boundary: List<String>, val manifests: List<Manifest>, val report: PrecompileReport, val samples: Int, val marker: Boolean)

    private fun campaign(name: String, precompile: Boolean, mutating: Boolean): Outcome = TempRepo.create().use { repo ->
        val stateRoot = Files.createDirectories(root.resolve(name))
        if (!WINDOWS) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        files.forEachIndexed { i, path -> repo.write(path, "def f():\n    return ${i + 1}\n") }
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
        // One sentinel per acceptance: every command runs once in the plan cell, so only AC-1 moves the tree, in I1.
        fun check(i: Int) = printing(mutating, root.resolve("$name-sentinel-$i").toAbsolutePath())
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            val authority = derived.requests.single().id
            contracts.open(
                derived.copy(
                    shape = Shape.S1,
                    requirements = (1..3).map { Requirement("R$it", "f$it returns ${it * 10}", listOf("AC-$it"), authorityRef = authority) },
                    acceptance = (1..3).map { Acceptance.Run("AC-$it", check(it), Origin.User) },
                ),
            )
        }
        val config = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, flags = Flags(precompile = precompile))
        val ticks = AtomicLong(0)
        val metrics = PrecompileMetrics { ticks.addAndGet(1_000) }
        runBlocking {
            Controller(config, clock, idGen).open(repo.root, request, policy).use { c ->
                val replies = listOf<Scripted>(
                    Scripted.Reply(listOf(say("planning three increments"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$plan}"""))),
                    Scripted.Reply(listOf(say("plan ready"))),
                ) + files.flatMapIndexed { i, path ->
                    val v = c.registry.version(path)!!
                    listOf(
                        Scripted.Reply(listOf<Item>(say("reading $path"), read("r$i", path))),
                        Scripted.Reply(listOf<Item>(say("editing $path"), anchored("e$i", path, v, "    return ${i + 1}", "    return ${(i + 1) * 10}"))),
                        Scripted.Reply(listOf<Item>(say("done with $path"))),
                    )
                }
                val run = Controller(config, clock, idGen, precompiles = metrics).run(c, CellModel(FakeAdapter(ScriptedModel.of(*replies.toTypedArray())), FakeProfiles.main, HeuristicEstimator()), maxCells = 6)
                val boundary = c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Boundary, JournalKind.Check))).map { it.text }
                val manifests = c.store.db.query("SELECT body FROM manifests ORDER BY rowid") { Json.decodeFromString(Manifest.serializer(), it.string("body")) }
                Outcome(run, boundary, manifests, metrics.report(), metrics.samples().size, Files.exists(repo.root.resolve("src/marker.txt")))
            }
        }
    }

    private fun List<String>.precompile(): List<String> = filter { it.startsWith("precompile ") }

    @Test
    fun `FX-44 the next K is pre-built at the completion proposal and served at the boundary on a matching fingerprint`() {
        val on = campaign("on", precompile = true, mutating = false)
        assertEquals(CampaignOutcome.Completed, on.run.outcome, on.run.state?.reason)
        val lines = on.boundary.precompile()
        // Only the slow acceptance check remains at the proposal, so the boundary qualifies (§6.6).
        assertTrue(lines.any { it.startsWith("precompile I2 started @") && it.endsWith(" slow") }, lines.toString())
        assertTrue(lines.any { it.startsWith("precompile I2 hit: [K] reused @") && it.endsWith("· coverage revalidated") }, lines.toString())
        assertTrue(lines.any { it.startsWith("precompile I3 started @") }, lines.toString())
        assertTrue(lines.any { it.startsWith("precompile I3 hit: [K] reused @") }, lines.toString())
        assertTrue(lines.any { it == "precompile skipped: no increment ready after I3" }, lines.toString())
        assertEquals(Triple(2, 0, 2), Triple(on.report.hits, on.report.misses, on.report.boundaries), on.report.toString())
        assertNotNull(on.report.p50BoundaryNanos)
        assertNotNull(on.report.p95BoundaryNanos)
        assertTrue(on.report.p95BoundaryNanos!! >= on.report.p50BoundaryNanos!!)
    }

    @Test
    fun `FX-44 a tree that moved while the checks ran discards the pre-compiled K and recompiles at the boundary`() {
        val on = campaign("moved", precompile = true, mutating = true)
        assertEquals(CampaignOutcome.Completed, on.run.outcome, on.run.state?.reason)
        assertTrue(on.marker, "the check wrote src/marker.txt: " + on.boundary.toString())
        val lines = on.boundary.precompile()
        // I1's check writes src/marker.txt: the stamp at close differs from the stamp the I2 pre-compile was tagged with.
        assertTrue(lines.any { it.startsWith("precompile I2 started @") }, lines.toString())
        assertTrue(lines.any { it.startsWith("precompile I2 miss: stamp moved (fp ") && it.endsWith("· discarded, recompiled") }, on.boundary.toString())
        assertTrue(lines.none { it.startsWith("precompile I2 hit") }, lines.toString())
        // I2's check rewrites the same bytes: the tree is stable, so I3's pre-compile is served.
        assertTrue(lines.any { it.startsWith("precompile I3 hit: [K] reused @") }, lines.toString())
        assertEquals(1, on.report.hits)
        assertEquals(1, on.report.misses)
        assertEquals(2, on.report.boundaries)
    }

    @Test
    fun `with the flag off no K is pre-built, nothing is journaled and nothing is measured`() {
        val off = campaign("off", precompile = false, mutating = false)
        assertEquals(CampaignOutcome.Completed, off.run.outcome, off.run.state?.reason)
        assertEquals(emptyList(), off.boundary.precompile())
        assertEquals(0, off.samples)
        assertEquals(PrecompileReport(0, 0, 0, null, null), off.report)
    }

    @Test
    fun `invariant a served K is what a fresh compile produces so the manifests match a run without pre-compilation`() {
        val on = campaign("inv-on", precompile = true, mutating = false)
        val off = campaign("inv-off", precompile = false, mutating = false)
        assertEquals(CampaignOutcome.Completed, on.run.outcome, on.run.state?.reason)
        assertEquals(CampaignOutcome.Completed, off.run.outcome, off.run.state?.reason)
        assertEquals(2, on.report.hits, "the served contexts are the ones compared")
        fun projection(m: Manifest) = listOf(m.incrementId, m.outcome, m.selectedUnits, m.omissions, m.arithmetic, m.estimatedTokens, m.seeds, m.notesInjected, m.boundaryReason?.wire)
        val served = on.manifests.filter { it.incrementId in setOf("I2", "I3") }.map(::projection)
        val fresh = off.manifests.filter { it.incrementId in setOf("I2", "I3") }.map(::projection)
        assertEquals(2, served.size, on.manifests.map { it.incrementId }.toString())
        assertEquals(fresh, served)
        assertEquals(0, on.report.misses)
    }
}
