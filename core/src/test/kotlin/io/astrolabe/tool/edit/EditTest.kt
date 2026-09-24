package io.astrolabe.tool.edit

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.Language
import io.astrolabe.auth.Redaction
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Increment
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
import io.astrolabe.id.Generation
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.LocalOs
import io.astrolabe.os.Os
import io.astrolabe.os.OsFailure
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.store.BlobKind
import io.astrolabe.store.Store
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.verify.Checks
import io.astrolabe.verify.RunnerCommands
import io.astrolabe.verify.ScopeGuard
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.Preimages
import io.astrolabe.workspace.Ranges
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.6.4 anchored CAS batch: FX-01..05, IX-02, explicit create/delete/rename, unsupported kinds, CRLF, acceptance-surface flags. */
class EditTest {
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
    private val workset = Workset()
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val idGen = FixedIdGen()
    private val syntaxCalls = ArrayList<String>()
    private val syntax = SyntaxCheck { relative, real, language ->
        syntaxCalls += "$relative:${language.id}"
        if (language == Language.Python && Files.readString(real).contains("def broken(")) SyntaxResult.Error(1, "SyntaxError") else SyntaxResult.Ok
    }

    private val a = "def a():\n    return 1\n\n\ndef b():\n    return 2\n\ndef b():\n    return 3  # twin\n"

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", a)
        repo.write("src/b.py", "x = 1\ny = 2\n")
        repo.write("src/win.py", "def w():\r\n    return 1\r\n")
        repo.write("src/blob.bin", byteArrayOf(0, 1, 2, -1, -2))
        repo.write("src/bom.py", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "v = 'é'\r\nw = 1\r\n".toByteArray())
        repo.write("tests/test_a.py", "def test_a():\n    assert a() == 1\n")
        repo.write("docs/readme.md", "# docs\n")
        repo.write("pyproject.toml", "[project]\nname = \"p\"\n\n[tool.pytest.ini_options]\ntestpaths = [\"tests\"]\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
        registry = VersionRegistry(workspace)
        coherence = Coherence(registry).also { it.register(workset) }
        os = LocalOs(clock)
        preimages = Preimages(workspace, store.blobs, ids, clock)
        contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
        val derived = contracts.deriveS0(ids.work, ids.attempt, "fix a", Atlas.build(repo.root), Config(), Tokens(10_000))
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

    private fun edit(os: Os = this.os, shadow: io.astrolabe.workspace.ShadowRef? = null) = Edit(
        workspace, registry, workset, os, preimages, ScopeGuard(workspace), contracts, checks,
        SqliteObservations(store, clock), SqliteAliases(store, clock), store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, syntax, shadow,
    )

    /** What a `look` leaves behind: the read registered at its version, its raw bytes published under that version. */
    private fun seen(path: String, from: Int, to: Int): FileVersion {
        val content = registry.read(path)!!
        store.blobs.put(content.bytes, BlobKind.PREIMAGE, ids, recovery = true)
        workset.register(Entry(path, Ranges.single(from, to), content.version, EntrySource.Look, 1, "#look", 50))
        return content.version
    }

    private fun call(json: String) = (ToolCalls.parse(listOf(ProviderCall("c1", "edit", json))) as ParsedCalls.Valid).calls.single()

    private fun context(turn: Int = 1) = TurnContext(turn, workset.snapshot(), Reservations(Tokens(10_000)))

    private suspend fun run(json: String, editor: Edit = edit(), turn: Int = 1): ToolOutcome = editor.execute(call(json), context(turn))

    private fun anchored(path: String, expect: FileVersion, vararg hunks: String, extra: String = "") =
        """{"ops":[{"path":"$path","expect":"${expect.digest.hex}","hunks":[${hunks.joinToString(",")}]$extra}],"why":"w"}"""

    private fun hunk(anchor: String, new: String, near: String? = null) =
        """{"anchor":${quote(anchor)},"new":${quote(new)}${near?.let { ""","near":${quote(it)}""" } ?: ""}}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun status(o: ToolOutcome) = o.header!!.runtime.status

    @Test
    fun `an anchored hunk applies under CAS with preimage, postimage, post-edit coverage, syntax and diffstat`() = runTest {
        val v = seen("src/a.py", 1, 10)
        val out = run(anchored("src/a.py", v, hunk("    return 1", "    return 10")))
        assertEquals("ok", status(out))
        assertTrue(out.applied)
        assertEquals("#1", out.resultAlias)
        val after = registry.version("src/a.py")!!
        assertEquals(a.replace("return 1", "return 10"), Files.readString(repo.resolve("src/a.py")))
        assertEquals(mapOf("src/a.py" to after), out.header!!.versions)
        assertEquals(after, registry.recorded("src/a.py"), "the transition was announced to the registry")
        assertFalse(workset.covers("src/a.py", v, LineRange(1, 2)), "the old read left KNOWN through coherence")
        assertTrue(workset.covers("src/a.py", after, LineRange(1, 5)), "post-edit view ±3 lines is displayed at the new version")
        assertEquals(Ranges.single(1, 5), registry.displayed(ids.context!!, Generation.INITIAL, workspace.id, "src/a.py", after))
        val preimage = preimages.of("edit-1").single()
        assertEquals(v, preimage.versionBefore)
        assertEquals(after, preimage.versionAfter)
        assertEquals(a, String(preimages.bytesOf(preimage)))
        assertTrue(store.blobs.exists(after.digest), "the postimage bytes are published under the new version")
        assertEquals(listOf("src/a.py:python"), syntaxCalls)
        assertTrue(out.body.contains("✓ 1 anchored src/a.py @${v.hash8}→@${after.hash8} +1 −1 · syntax ok"), out.body)
        assertTrue(out.body.contains("post-edit src/a.py:1-5 @${after.hash8}\n  1| def a():\n  2|     return 10"), out.body)
        assertEquals("edit", SqliteAliases(store, clock).resolve(ids.work, 1)!!.kind)
        assertNotNull(SqliteObservations(store, clock).get("obs-1"))
    }

    @Test
    fun `a stale expect writes nothing and returns the diff since expect (FX-01)`() = runTest {
        val shown = seen("src/a.py", 1, 10)
        repo.write("src/a.py", a.replace("return 1", "return 99"))
        val out = run(anchored("src/a.py", shown, hunk("    return 1", "    return 10")))
        assertEquals("refused", status(out))
        assertFalse(out.applied)
        assertTrue(out.body.contains("stale_expect: 'src/a.py' is @"), out.body)
        assertTrue(out.body.contains("-    return 1\n+    return 99"), "diff since expect: $out")
        assertEquals(a.replace("return 1", "return 99"), Files.readString(repo.resolve("src/a.py")), "no write on a stale expect")
        assertTrue(preimages.of("edit-1").isEmpty())
        assertTrue(syntaxCalls.isEmpty())
    }

    @Test
    fun `a hunk outside the displayed range is refused with the outline and the displayed ranges (FX-02)`() = runTest {
        val v = seen("src/a.py", 1, 2)
        val out = run(anchored("src/a.py", v, hunk("    return 2", "    return 20")))
        assertEquals("refused", status(out))
        assertTrue(out.body.contains("outside_displayed: hunk at 'src/a.py:6' lies outside the displayed ranges of @${v.hash8} (1-2); read it first"), out.body)
        assertTrue(out.body.contains("outline src/a.py: function a 1-2 · function b 5-6 · function b 8-9"), out.body)
        assertEquals(a, Files.readString(repo.resolve("src/a.py")))
    }

    @Test
    fun `an ambiguous or missing second hunk applies nothing (FX-03)`() = runTest {
        val v = seen("src/a.py", 1, 10)
        val ambiguous = run(anchored("src/a.py", v, hunk("    return 1", "    return 10"), hunk("def b():", "def c():")))
        assertEquals("refused", status(ambiguous))
        assertTrue(ambiguous.body.contains("anchor 2× in 'src/a.py': sites 5, 8 — add near="), ambiguous.body)
        assertEquals(a, Files.readString(repo.resolve("src/a.py")), "preflight rejection of any hunk writes none")

        val missing = run(anchored("src/a.py", v, hunk("def zed():", "def z():")))
        assertTrue(missing.body.contains("anchor 0× in 'src/a.py'; nearest: 1: def a():"), missing.body)

        val disambiguated = run(anchored("src/a.py", v, hunk("    return 1", "    return 10"), hunk("def b():", "def c():", near = "return 2")))
        assertEquals("ok", status(disambiguated))
        assertEquals(a.replace("return 1", "return 10").replace("def b():\n    return 3", "def c():\n    return 3"), Files.readString(repo.resolve("src/a.py")))

        val overlap = run(anchored("src/a.py", registry.version("src/a.py")!!, hunk("def a():\n    return 10", "x"), hunk("return 10", "y")).also { seen("src/a.py", 1, 10) })
        assertTrue(overlap.body.contains("overlap"), overlap.body)
    }

    @Test
    fun `a mid-batch IO failure reports the actual per-file state with preimage ids (FX-04)`() = runTest {
        val va = seen("src/a.py", 1, 10)
        val vb = seen("src/b.py", 1, 2)
        val failing = object : Os by os {
            override fun replaceFileAtomically(path: Path, bytes: ByteArray) {
                if (path.fileName.toString() == "b.py") throw OsFailure("replaceFileAtomically", 0, "disk full")
                os.replaceFileAtomically(path, bytes)
            }
        }
        val out = run(
            """{"ops":[{"path":"src/a.py","expect":"${va.digest.hex}","hunks":[${hunk("    return 1", "    return 10")}]},{"path":"src/b.py","expect":"${vb.digest.hex}","hunks":[${hunk("x = 1", "x = 11")}]}],"why":"w"}""",
            editor = edit(os = failing),
        )
        assertEquals("partial", status(out))
        assertFalse(out.applied)
        assertTrue(out.header!!.runtime.effectsUnknown)
        assertTrue(Files.readString(repo.resolve("src/a.py")).contains("return 10"), "the first file stays written")
        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/b.py")), "the second file is untouched")
        val preimage = preimages.of("edit-1").single { it.path == "src/a.py" }
        assertTrue(out.body.contains("✗ op 2 io: disk full while applying op 2 on 'src/b.py'; already written: src/a.py (preimage ${preimage.preimageDigest.hex.take(8)}); later ops not attempted, nothing rolled back"), out.body)
        assertNotNull(preimages.of("edit-1", "src/b.py"), "the preimage was saved before the failed write")
    }

    @Test
    fun `human edits after an agent patch make the inverse refuse divergent content (FX-05)`() = runTest {
        val v = seen("src/a.py", 1, 10)
        run(anchored("src/a.py", v, hunk("    return 1", "    return 10")))
        val edited = registry.version("src/a.py")!!
        repo.write("src/a.py", Files.readString(repo.resolve("src/a.py")).replace("return 2", "return 22"))
        val diverged = run("""{"ops":[{"revert":"#1"}],"why":"undo"}""", turn = 2)
        assertEquals("refused", status(diverged))
        assertTrue(diverged.body.contains("divergent: 'src/a.py' is @"), diverged.body)
        assertTrue(diverged.body.contains("not the @${edited.hash8} that edit edit-1 produced; the inverse is refused (FX-05)"), diverged.body)
        assertTrue(Files.readString(repo.resolve("src/a.py")).contains("return 22"), "the human's bytes stand")

        repo.write("src/a.py", Files.readString(repo.resolve("src/a.py")).replace("return 22", "return 2"))
        val reverted = run("""{"ops":[{"revert":"#1"}],"why":"undo"}""", turn = 3)
        assertEquals("ok", status(reverted))
        assertEquals(a, Files.readString(repo.resolve("src/a.py")))
        assertTrue(reverted.body.contains("✓ 1 revert src/a.py @${edited.hash8}→@${v.hash8} +1 −1 · syntax ok"), reverted.body)
        assertEquals(v, registry.recorded("src/a.py"))
        assertEquals("refused", status(run("""{"ops":[{"revert":"turn:1"}],"why":"undo"}""")), "no shadow ref given ⇒ turn reverts are unsupported here")
        assertEquals("refused", status(run("""{"ops":[{"revert":"#7"}],"why":"undo"}""")))
    }

    @Test
    fun `an out-of-contract or protected path is refused before any write (IX-02)`() = runTest {
        val v = seen("docs/readme.md", 1, 1)
        val outside = run(anchored("docs/readme.md", v, hunk("# docs", "# documentation")))
        assertEquals("refused", status(outside))
        assertTrue(outside.body.contains("scope: docs/readme.md: outsidecontract"), outside.body)
        assertEquals("# docs\n", Files.readString(repo.resolve("docs/readme.md")))
        val protectedWrite = run("""{"ops":[{"create":"migrations/0001.sql","content":"select 1;"}],"why":"w"}""")
        assertTrue(protectedWrite.body.contains("scope: migrations/0001.sql: protected"), protectedWrite.body)
        assertFalse(Files.exists(repo.resolve("migrations/0001.sql")))
        val mixed = run("""{"ops":[{"create":"src/new.py","content":"x = 1\n"},{"create":"docs/new.md","content":"#"}],"why":"w"}""")
        assertEquals("refused", status(mixed))
        assertFalse(Files.exists(repo.resolve("src/new.py")), "one refused path refuses the batch before any write")
    }

    @Test
    fun `crossing the increment's write scope warns once, then each crossing names its paths in why`() = runTest {
        val editor = edit().also { it.increment = Increment("inc-1", listOf("R1"), accept = emptyList(), writeScope = listOf("src/"), expectedFiles = 1, title = "t") }
        val first = run("""{"ops":[{"create":"tests/test_b.py","content":"x = 1\n"}],"why":"w"}""", editor)
        assertEquals("ok", status(first), first.body)
        assertTrue(first.body.contains("outside the increment's write scope (inside the contract): tests/test_b.py"), first.body)

        val second = run("""{"ops":[{"create":"tests/test_c.py","content":"x = 1\n"}],"why":"w"}""", editor)
        assertEquals("refused", status(second))
        assertTrue(second.body.contains("outside the increment's write scope again: tests/test_c.py"), second.body)
        assertFalse(Files.exists(repo.resolve("tests/test_c.py")))

        assertEquals("ok", status(run("""{"ops":[{"create":"tests/test_c.py","content":"x = 1\n"}],"why":"test_c.py pins the new branch of a"}""", editor)))
        assertEquals("ok", status(run("""{"ops":[{"create":"src/d.py","content":"x = 1\n"}],"why":"w"}""", editor)), "inside the increment needs nothing")
    }

    @Test
    fun `a pending amendment dispatches nothing out of scope until the amendment is committed (FX-52)`() = runTest {
        val editor = edit()
        contracts.propose(ids.work, null, "also write docs/", "docs must change", weakening = false)
        val pending = run("""{"ops":[{"create":"docs/new.md","content":"# new\n"}],"why":"docs/new.md documents a"}""", editor)
        assertEquals("refused", status(pending))
        assertTrue(pending.body.contains("scope: docs/new.md: outsidecontract"), pending.body)
        assertFalse(Files.exists(repo.resolve("docs/new.md")), "a pending proposal grants nothing")

        contracts.amendByUser(ids.work, "also update docs/") { it.copy(scope = it.scope.copy(writePaths = it.scope.writePaths + "docs/")) }
        assertEquals("ok", status(run("""{"ops":[{"create":"docs/new.md","content":"# new\n"}],"why":"docs/new.md documents a"}""", editor)), "revalidated against the committed version at dispatch")
        assertTrue(Files.exists(repo.resolve("docs/new.md")))
    }

    @Test
    fun `create, delete and rename are explicit operations and unsupported kinds are named`() = runTest {
        val created = run("""{"ops":[{"create":"src/c.py","content":"def c():\n    return 3\n"}],"why":"add"}""")
        assertEquals("ok", status(created))
        val vc = registry.version("src/c.py")!!
        assertTrue(workset.covers("src/c.py", vc, LineRange(1, 2)), "a created file is displayed in full at its version")
        assertTrue(created.body.contains("✓ 1 create src/c.py @new→@${vc.hash8} +2 −0 · syntax ok"), created.body)
        assertEquals("refused", status(run("""{"ops":[{"create":"src/c.py","content":"again"}],"why":"dup"}""")))

        val vb = registry.version("src/b.py")!!
        val renamed = run("""{"ops":[{"rename":"src/b.py","expect":"${vb.digest.hex}","to":"src/b2.py"}],"why":"mv"}""")
        assertEquals("ok", status(renamed))
        assertFalse(Files.exists(repo.resolve("src/b.py")))
        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/b2.py")))
        assertEquals(vb, registry.recorded("src/b2.py"))
        assertNull(registry.recorded("src/b.py"))
        assertEquals("refused", status(run("""{"ops":[{"rename":"src/b2.py","expect":"${vb.digest.hex}","to":"src/B2.py"}],"why":"case"}""")))

        val deleted = run("""{"ops":[{"delete":"src/c.py","expect":"${vc.digest.hex}"}],"why":"rm"}""")
        assertEquals("ok", status(deleted))
        assertFalse(Files.exists(repo.resolve("src/c.py")))
        assertTrue(deleted.body.contains("✓ 1 delete src/c.py @${vc.hash8}→@gone +0 −2"), deleted.body)
        assertEquals("refused", status(run("""{"ops":[{"delete":"src/c.py","expect":"${vc.digest.hex}"}],"why":"rm"}""")))

        val vbin = seen("src/blob.bin", 1, 1)
        val binary = run(anchored("src/blob.bin", vbin, hunk("x", "y")))
        assertTrue(binary.body.contains("unsupported: 'src/blob.bin' is not valid UTF-8 text"), binary.body)
        val transform = run("""{"ops":[{"transform":{"argv":["sed"],"scope_glob":"src/**","why":"w"}}],"why":"w"}""")
        assertTrue(transform.body.contains("unsupported: edit.transform is masked in this role"), transform.body)
    }

    @Test
    fun `normalized anchors apply to CRLF files without changing their line endings`() = runTest {
        val v = seen("src/win.py", 1, 2)
        val out = run(anchored("src/win.py", v, hunk("def w():\n\treturn 1", "def w():\n    return 2\n    # two")))
        assertEquals("ok", status(out))
        assertEquals("def w():\r\n    return 2\r\n    # two\r\n", Files.readString(repo.resolve("src/win.py")))
    }

    @Test
    fun `a UTF-8 BOM, non-ASCII text and CRLF endings survive an edit byte-exactly outside the hunk`() = runTest {
        val v = seen("src/bom.py", 1, 2)
        assertEquals("ok", status(run(anchored("src/bom.py", v, hunk("w = 1", "w = 2")))))
        val bytes = Files.readAllBytes(repo.resolve("src/bom.py"))
        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "v = 'é'\r\nw = 2\r\n".toByteArray(), bytes)
        assertNotEquals(v, registry.version("src/bom.py"), "the version hashes the new raw bytes")
    }

    @Test
    fun `editing a test file renders the acceptance-surface line and a syntax error is reported not hidden`() = runTest {
        val vt = seen("tests/test_a.py", 1, 2)
        val out = run(anchored("tests/test_a.py", vt, hunk("assert a() == 1", "assert a()")))
        assertEquals("ok", status(out))
        assertTrue(out.body.contains("acceptance surface: tests/test_a.py (test file) modified by edit #1 · weakened-assertion · required: CHK-accept-AC-1 · reason: w · review: pending"), out.body)

        val va = seen("src/a.py", 1, 10)
        val broken = run(anchored("src/a.py", va, hunk("def a():", "def broken(")))
        assertEquals("ok", status(broken), "a syntax error is information about the new version, not an op failure")
        assertTrue(broken.body.contains("syntax error:1 SyntaxError"), broken.body)
    }
}
