package io.astrolabe.tool.edit

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Redaction
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.SqliteAliases
import io.astrolabe.evidence.SqliteObservations
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.ChildCommands
import io.astrolabe.os.LocalOs
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.store.BlobKind
import io.astrolabe.store.Store
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.verify.Checks
import io.astrolabe.verify.RunnerCommands
import io.astrolabe.verify.ScopeGuard
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.Preimages
import io.astrolabe.workspace.Ranges
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P3.3.1 `edit(transform)` diff receipt over a 40-file rename; P3.3.2 out-of-scope and count failures (FX-40). */
class TransformTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var workspace: Workspace
    private lateinit var registry: VersionRegistry
    private lateinit var coherence: Coherence
    private lateinit var preimages: Preimages
    private lateinit var os: LocalOs
    private lateinit var contracts: Contracts
    private lateinit var checks: Checks
    private lateinit var stamper: Stamper
    private val workset = Workset()
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val idGen = FixedIdGen()
    private val syntaxCalls = ArrayList<String>()
    private val syntax = SyntaxCheck { relative, _, _ -> syntaxCalls += relative; SyntaxResult.Ok }
    private val windows = ChildCommands.isWindows

    private val files = (1..40).map { "src/m%02d.py".format(it) }

    private fun module(i: Int) = "# m$i\n\n\ndef f():\n    return router.dispatch($i)\n\n\ndef g():\n    return $i\n"

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        files.forEachIndexed { i, path -> repo.write(path, module(i + 1)) }
        repo.write("src/keep.txt", "not python\n")
        repo.write("tests/test_m.py", "def test_m():\n    assert f() == 1\n")
        repo.write("docs/readme.md", "# docs\n")
        repo.write("pyproject.toml", "[project]\nname = \"p\"\n\n[tool.pytest.ini_options]\ntestpaths = [\"tests\"]\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
        registry = VersionRegistry(workspace)
        coherence = Coherence(registry).also { it.register(workset) }
        os = LocalOs(clock)
        preimages = Preimages(workspace, store.blobs, ids, clock)
        stamper = Stamper(workspace, EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1")))
        contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
        val derived = contracts.deriveS0(ids.work, ids.attempt, "rename dispatch", Atlas.build(repo.root), Config(), Tokens(10_000))
        contracts.open(derived.contract.copy(scope = derived.contract.scope.copy(writePaths = listOf("src/", "tests/"))))
        checks = Checks.seed(contracts.current(ids.work)!!, RunnerCommands.of(derived.primary!!))
    }

    @AfterTest
    fun tearDown() {
        coherence.close()
        os.close()
        store.close()
        repo.close()
    }

    private fun edit(execution: TransformExecution? = TransformExecution(TrustedLocalRunner(os), stamper, stateRoot.resolve("logs"))) = Edit(
        workspace, registry, workset, os, preimages, ScopeGuard(workspace), contracts, checks,
        SqliteObservations(store, clock), SqliteAliases(store, clock), store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, syntax,
        transforms = execution,
    )

    private fun call(json: String) = (ToolCalls.parse(listOf(ProviderCall("c1", "edit", json))) as ParsedCalls.Valid).calls.single()

    private fun context(turn: Int = 1) = TurnContext(turn, workset.snapshot(), Reservations(Tokens(10_000)))

    private suspend fun run(json: String, editor: Edit, turn: Int = 1): ToolOutcome = editor.execute(call(json), context(turn))

    /** The rename as argv, portable: PowerShell rewrites bytes without a BOM, sh keeps LF; [extra] appends a second command. */
    private fun renameArgv(extra: String = ""): String {
        val argv = if (windows) {
            listOf("powershell", "-NoProfile", "-Command", "Get-ChildItem src -Filter *.py | ForEach-Object { [IO.File]::WriteAllText(\$_.FullName, ([IO.File]::ReadAllText(\$_.FullName) -replace 'dispatch','route')) }$extra")
        } else {
            listOf("/bin/sh", "-c", "for f in src/*.py; do sed -i s/dispatch/route/g \$f; done$extra")
        }
        return JsonArray(argv.map(::JsonPrimitive)).toString()
    }

    private fun transform(argv: String, expected: String? = null, inventory: String? = null) =
        """{"ops":[{"transform":{"argv":$argv,"scope_glob":"src/**/*.py"${expected?.let { ""","expected_matches":$it""" } ?: ""}${inventory?.let { ""","inventory":$it""" } ?: ""},"why":"rename Router.dispatch → route"}}],"why":"rename dispatch → route across call sites"}"""

    private fun versions(): Map<String, FileVersion> = files.associateWith { registry.version(it)!! }

    @Test
    fun `a 40-file rename produces one diff receipt and the files are touched-by-transform NOT SEEN`() = runTest {
        val before = versions()
        val editor = edit()
        val out = run(transform(renameArgv()), editor)
        assertTrue(out.applied, out.body)
        assertEquals("ok", out.header!!.runtime.status)
        val receipt = editor.transforms.single()
        assertTrue(receipt.accepted)
        assertEquals(40, receipt.filesChanged)
        assertEquals(40, receipt.hunks)
        assertEquals(40, receipt.matchCount)
        assertEquals(InventoryVerdict.Unspecified, receipt.inventoryOk, "an omitted inventory is unspecified, never ok")
        assertEquals(emptyList(), receipt.touchedOutsideScope)
        assertEquals(40, receipt.perFile.size)
        assertTrue(receipt.perFile.all { it.added == 1 && it.removed == 1 && it.hunks == 1 })
        assertEquals(3, receipt.representativeSites.size)
        assertEquals(listOf("src/m01.py", "src/m21.py", "src/m40.py"), receipt.representativeSites.map { it.path })
        assertTrue(receipt.representativeSites.all { it.line == 5 && it.text == "return router.route(${it.path.substring(5, 7).toInt()})" }, receipt.representativeSites.toString())
        assertEquals(emptyList(), receipt.unusualSites, "every hunk has the same shape")
        assertEquals(40, receipt.syntax.size)
        assertEquals(files, syntaxCalls.sorted(), "inline syntax ran on every changed file")
        assertTrue(store.blobs.exists(receipt.diffRef), "the full diff is recallable")
        val diff = String(store.blobs.get(receipt.diffRef), Charsets.UTF_8)
        assertTrue(diff.contains("=== src/m07.py @") && diff.contains("-    return router.dispatch(7)") && diff.contains("+    return router.route(7)"), diff.take(400))
        assertEquals(0, receipt.exitCode)
        assertEquals(ExecutionMode.TrustedLocal, receipt.executionMode)
        // Preimages of every in-scope file were recorded, the postimages published, the registry announced each move.
        assertEquals(40, preimages.of(receipt.editId).size)
        assertTrue(preimages.of(receipt.editId).all { it.versionAfter != null })
        val after = versions()
        files.forEach { assertTrue(after[it] != before[it], it) }
        assertEquals(module(7).replace("dispatch", "route"), Files.readString(repo.root.resolve("src/m07.py")), "bytes rewritten with LF kept")
        assertEquals(40, out.header!!.runtime.effectsObserved.size)
        assertTrue(out.header!!.runtime.artifactRefs.contains(receipt.diffRef.hex), "the diff id travels with the result for review (P4.4.3)")
        // The receipt as the model sees it: bounded per-file lines, the label, the NOT SEEN rule.
        assertTrue(out.body.contains("transform: 40 files, 40 hunks · diff #${receipt.diffRef.hash8} · match_count 40 (expected unspecified) · inventory_ok unspecified · touched_outside_scope: none · syntax ok 40/40 · exit 0"), out.body)
        assertTrue(out.body.contains("+28 more files (recall the diff)"), out.body)
        assertTrue(out.body.contains("transformation-based validation"), out.body)
        assertTrue(out.body.contains("a diff is not a jail"), out.body)
        // Workset: touched-by-transform (NOT SEEN) grants no coverage and renders as such.
        val entries = workset.entries.filter { it.source == EntrySource.Transform }
        assertEquals(40, entries.size)
        assertTrue(entries.all { it.coverage.isEmpty && it.version == after[it.path] })
        assertTrue(workset.render(HeuristicEstimator()).contains("touched-by-transform (NOT SEEN): 40 files"), workset.render(HeuristicEstimator()))
    }

    @Test
    fun `an anchored edit on a transformed file requires a fresh read`() = runTest {
        val editor = edit()
        assertTrue(run(transform(renameArgv()), editor).applied)
        val v = registry.version("src/m03.py")!!
        val anchored = """{"ops":[{"path":"src/m03.py","expect":"${v.digest.hex}","hunks":[{"anchor":"return router.route(3)","new":"return router.route(3, fast=True)"}]}],"why":"w"}"""
        val refused = run(anchored, editor, turn = 2)
        assertFalse(refused.applied, refused.body)
        assertTrue(refused.body.contains("outside_displayed") && refused.body.contains("read it first"), refused.body)
        assertEquals(v, registry.version("src/m03.py"), "nothing written")
        // A fresh read at the transformed version is what an anchored edit needs.
        val content = registry.read("src/m03.py")!!
        store.blobs.put(content.bytes, BlobKind.PREIMAGE, ids, recovery = true)
        workset.register(Entry("src/m03.py", Ranges.single(1, 9), content.version, EntrySource.Look, 2, "#look", 50))
        val ok = run(anchored, editor, turn = 3)
        assertTrue(ok.applied, ok.body)
        assertTrue(Files.readString(repo.root.resolve("src/m03.py")).contains("route(3, fast=True)"))
    }

    @Test
    fun `an out-of-scope write rejects the transform with a guarded inverse and an honest partial effect (FX-40)`() = runTest {
        val before = versions()
        val readme = registry.version("docs/readme.md")!!
        val editor = edit()
        val out = run(transform(renameArgv(if (windows) "; Add-Content docs/readme.md x" else "; echo x >> docs/readme.md")), editor)
        assertFalse(out.applied, out.body)
        assertEquals("rejected", out.header!!.runtime.status)
        val receipt = editor.transforms.single()
        assertFalse(receipt.accepted)
        assertEquals(listOf("docs/readme.md"), receipt.touchedOutsideScope)
        assertEquals(40, receipt.filesChanged)
        assertTrue(receipt.rejection!!.startsWith("touched outside scope_glob src/**/*.py: docs/readme.md"), receipt.rejection)
        assertEquals(TransformEffect.Partial, receipt.effect)
        assertEquals(files, receipt.restored)
        assertEquals(listOf("docs/readme.md: outside scope_glob, no preimage recorded"), receipt.notRestored)
        // In-scope files are back at their preimages; the out-of-scope effect stands and is reported, never claimed undone.
        assertEquals(before, versions())
        assertContentEquals(module(7).toByteArray(), Files.readAllBytes(repo.root.resolve("src/m07.py")))
        assertTrue(registry.version("docs/readme.md") != readme)
        assertTrue(out.body.contains("rejected: touched outside scope_glob") && out.body.contains("effect: partial · restored 40 · not restored: docs/readme.md: outside scope_glob"), out.body)
        assertTrue(out.body.contains("no unit rollback and no undo of external effects is claimed"), out.body)
        assertEquals(41, out.header!!.runtime.effectsObserved.size, "every path that moved is an observed effect, restored or not")
        assertTrue(workset.entries.none { it.source == EntrySource.Transform }, "a rejected transform registers nothing as touched")
    }

    @Test
    fun `a match count outside expected_matches rejects the transform and restores every file (FX-40)`() = runTest {
        val before = versions()
        val editor = edit()
        val out = run(transform(renameArgv(), expected = """{"min":50,"max":60}"""), editor)
        assertFalse(out.applied, out.body)
        val receipt = editor.transforms.single()
        assertFalse(receipt.accepted)
        assertEquals(40, receipt.matchCount)
        assertEquals("match count 40 outside expected 50–60", receipt.rejection)
        assertEquals(TransformEffect.Restored, receipt.effect)
        assertEquals(40, receipt.restored.size)
        assertEquals(emptyList(), receipt.notRestored)
        assertEquals(before, versions())
        assertTrue(out.body.contains("effect: restored · restored 40"), out.body)
        // The receipt still carries the counts and the diff, so the retry can fix the policy rather than re-run blind.
        assertTrue(out.body.contains("match_count 40 (expected 50–60)"), out.body)
    }

    @Test
    fun `an inventory is compared, not assumed, and a transform is its own batch`() = runTest {
        val editor = edit()
        val out = run(transform(renameArgv(), inventory = """["src/m01.py","src/m02.py"]"""), editor)
        assertTrue(out.applied, out.body)
        assertEquals(InventoryVerdict.Failed, editor.transforms.single().inventoryOk, "38 changed files were not in the declared inventory")
        assertTrue(out.body.contains("inventory_ok false"), out.body)
        val two = """{"ops":[{"transform":{"argv":${renameArgv()},"scope_glob":"src/**/*.py","why":"w"}},{"create":"src/new.py","content":"x = 1\n"}],"why":"w"}"""
        val refused = run(two, editor, turn = 2)
        assertFalse(refused.applied)
        assertTrue(refused.body.contains("a transform is a batch of its own"), refused.body)
        assertNull(registry.read("src/new.py"))
    }

    @Test
    fun `no runner or a confinement requirement is unsupported, nothing runs (D-41)`() = runTest {
        val before = versions()
        val none = run(transform(renameArgv()), edit(execution = null))
        assertFalse(none.applied)
        assertTrue(none.body.contains("unsupported: this cell has no transform runner (D-41)"), none.body)
        val confined = run(transform(renameArgv()), edit(execution = TransformExecution(TrustedLocalRunner(os), stamper, stateRoot.resolve("logs"), executionMode = ExecutionMode.Confined)))
        assertFalse(confined.applied)
        assertTrue(confined.body.contains("confined execution required; no backend"), confined.body)
        assertEquals(before, versions())
        assertNotNull(registry.read("src/m01.py"))
    }
}
