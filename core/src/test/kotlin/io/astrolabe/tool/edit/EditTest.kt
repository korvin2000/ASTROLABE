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
import io.astrolabe.store.BlobPoint
import io.astrolabe.store.CrashPoint
import io.astrolabe.store.FaultPoints
import io.astrolabe.store.Store
import io.astrolabe.tool.Effects
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
import io.astrolabe.workspace.ChangeListener
import io.astrolabe.workspace.DirtyState
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.Preimages
import io.astrolabe.workspace.Ranges
import io.astrolabe.workspace.ShadowRef
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
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
    private var failNextBlob = false
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
        store = Store.open(stateRoot, repo.git, clock, FaultPoints(CrashPoint { point ->
            if (failNextBlob && point == BlobPoint.AFTER_MOVE_BEFORE_ROW) {
                failNextBlob = false
                throw IOException("postimage row failure")
            }
        }))
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

    private fun edit(os: Os = this.os, shadow: io.astrolabe.workspace.ShadowRef? = null, redaction: Redaction = Redaction()) = Edit(
        workspace, registry, workset, os, preimages, ScopeGuard(workspace), contracts, checks,
        SqliteObservations(store, clock), SqliteAliases(store, clock), store.blobs, redaction, HeuristicEstimator(), idGen, ids, syntax, shadow,
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
    fun `redacted and omitted edit views grant no new source coverage`() = runTest {
        val secret = "AKIA" + "IOSFODNN7EXAMPLE"
        val created = run("""{"ops":[{"create":"src/secret.py","content":"x = '$secret'\n"}],"why":"create fixture"}""")
        assertTrue(created.applied)
        assertFalse(workset.covers("src/secret.py", registry.version("src/secret.py")!!, LineRange(1, 1)))
        assertTrue(SqliteObservations(store, clock).get(SqliteAliases(store, clock).resolve(ids.work, 1)!!.canonicalId)!!.coverage("src/secret.py").isEmpty)
        val capped = edit(redaction = Redaction(io.astrolabe.auth.RedactionConfig(maxBytes = 48)))
        val out = run("""{"ops":[{"create":"src/first.py","content":"a = 1\n"},{"create":"src/last.py","content":"b = 2\n"}],"why":"w"}""", capped)
        assertTrue(out.header!!.truncated)
        val version = registry.version("src/last.py")!!
        assertFalse(workset.covers("src/last.py", version, LineRange(1, 1)))
        val refused = run(anchored("src/last.py", version, hunk("b = 2", "b = 3")))
        assertEquals("refused", status(refused))
        assertTrue(refused.body.contains("outside_displayed"))
    }

    @Test
    fun `repeated file operations and aliased create targets refuse the entire batch`() = runTest {
        val v = seen("src/b.py", 1, 2)
        val repeated = run("""{"ops":[{"path":"src/b.py","expect":"${v.digest.hex}","hunks":[${hunk("x = 1", "x = 3")}]},{"path":"src/b.py","expect":"${v.digest.hex}","hunks":[${hunk("y = 2", "y = 4")}]}],"why":"w"}""")
        assertEquals("refused", status(repeated))
        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/b.py")))
        assertTrue(preimages.of("edit-1").isEmpty())
        val created = run("""{"ops":[{"create":"src/new.py","content":"first"},{"create":"src/./new.py","content":"second"}],"why":"w"}""")
        assertEquals("refused", status(created))
        assertFalse(Files.exists(repo.resolve("src/new.py")))
        val renamed = run("""{"ops":[{"rename":"src/b.py","to":"src/new.py","expect":"${v.digest.hex}"},{"create":"src/new.py","content":"second"}],"why":"w"}""")
        assertEquals("refused", status(renamed))
        assertFalse(Files.exists(repo.resolve("src/new.py")))
        assertTrue(Files.exists(repo.resolve("src/b.py")))
    }

    private fun op(path: String, expect: FileVersion, vararg hunks: String) = """{"path":"$path","expect":"${expect.digest.hex}","hunks":[${hunks.joinToString(",")}]}"""

    private fun batch(vararg ops: String) = """{"ops":[${ops.joinToString(",")}],"why":"w"}"""

    @Test
    fun `one bad anchor in a ten-file batch refuses only its file and the turn's runs wait for it`() = runTest {
        val ops = (0 until 10).map { i ->
            repo.write("src/f$i.py", "v = $i\n")
            op("src/f$i.py", seen("src/f$i.py", 1, 1), hunk(if (i == 6) "v = 99" else "v = $i", "v = ${i * 10}"))
        }
        val ran = ArrayList<Int>()
        val dispatcher = io.astrolabe.tool.Dispatcher(
            mapOf(
                io.astrolabe.tool.ToolFamily.Edit to edit(),
                io.astrolabe.tool.ToolFamily.Run to io.astrolabe.tool.ToolExecutor { c, _ -> ran += c.opId; ToolOutcome("ran") },
            ),
            workset, ids,
        )
        val calls = (ToolCalls.parse(listOf(ProviderCall("c1", "edit", batch(*ops.toTypedArray())), ProviderCall("c2", "run", """{"argv":["pytest"]}"""))) as ParsedCalls.Valid).calls
        val turn = dispatcher.dispatch(1, calls, Tokens(10_000))

        val out = (turn.of(1) as io.astrolabe.tool.Disposition.Executed).outcome
        assertEquals("partial", status(out), out.body)
        assertFalse(out.applied)
        assertFalse(out.header!!.runtime.effectsUnknown, "a refused group is no unknown effect")
        for (i in 0 until 10) assertEquals(if (i == 6) "v = 6\n" else "v = ${i * 10}\n", Files.readString(repo.resolve("src/f$i.py")))
        assertTrue(out.body.contains("✗ src/f6.py refused · op 7 anchor: anchor 0× in 'src/f6.py'"), out.body)
        assertTrue(out.body.contains("9 of 10 files written; resend only the refused ops: src/f6.py (op 7)"), out.body)
        assertEquals((0 until 10).filter { it != 6 }.map { "src/f$it.py" }.toSet(), preimages.of("edit-1").map { it.path }.toSet(), "preimages cover exactly the applied files")
        assertEquals(9, out.header!!.versions.size)
        assertFalse(turn.editsApplied)
        val run = turn.of(2) as io.astrolabe.tool.Disposition.NotExecuted
        assertEquals("the edit batch applied partially (1 refused): fix those ops first", run.reason)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a group refuses as a whole and leaves every other group to apply`() = runTest {
        val va = seen("src/a.py", 1, 10)
        val vb = seen("src/b.py", 1, 2)
        // Two ops on one path, the second one bad: that path is untouched; the other path applies.
        val twice = run(batch(op("src/b.py", vb, hunk("x = 1", "x = 3")), op("src/b.py", vb, hunk("nope", "y = 4")), op("src/a.py", va, hunk("    return 1", "    return 10"))))
        assertEquals("partial", status(twice), twice.body)
        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/b.py")))
        assertTrue(Files.readString(repo.resolve("src/a.py")).contains("return 10"))
        assertTrue(twice.body.contains("1 of 2 files written; resend only the refused ops: src/b.py (ops 1, 2)"), twice.body)

        // A rename and an op on its target are one group: the missing target refuses both; the create applies.
        val renamed = run(batch("""{"rename":"src/b.py","to":"src/r.py","expect":"${vb.digest.hex}"}""", op("src/r.py", vb, hunk("x = 1", "x = 5")), """{"create":"src/c.py","content":"c = 1\n"}"""))
        assertEquals("partial", status(renamed), renamed.body)
        assertTrue(Files.exists(repo.resolve("src/b.py")))
        assertFalse(Files.exists(repo.resolve("src/r.py")))
        assertEquals("c = 1\n", Files.readString(repo.resolve("src/c.py")))
        assertTrue(renamed.body.contains("src/b.py, src/r.py refused"), renamed.body)

        // A protected or out-of-contract path refuses only its own group.
        val mixed = run(batch("""{"create":"migrations/0001.sql","content":"select 1;"}""", """{"create":"src/ok.py","content":"ok = 1\n"}""", """{"create":"docs/n.md","content":"#"}"""))
        assertEquals("partial", status(mixed), mixed.body)
        assertEquals("ok = 1\n", Files.readString(repo.resolve("src/ok.py")))
        assertFalse(Files.exists(repo.resolve("migrations/0001.sql")))
        assertFalse(Files.exists(repo.resolve("docs/n.md")))
        assertTrue(mixed.body.contains("scope: migrations/0001.sql: protected"), mixed.body)
        assertTrue(mixed.body.contains("1 of 3 files written; resend only the refused ops: migrations/0001.sql (op 1), docs/n.md (op 3)"), mixed.body)

        // Every group refused: nothing written, every diagnostic listed at once.
        val none = run(batch(op("src/ok.py", registry.version("src/ok.py")!!, hunk("zzz", "1")), """{"create":"docs/m.md","content":"#"}"""))
        assertEquals("refused", status(none), none.body)
        assertTrue(none.body.contains("anchor 0×") && none.body.contains("outsidecontract"), none.body)
        assertTrue(none.body.contains("0 of 2 files written"), none.body)
    }

    @Test
    fun `creates that differ only in case refuse the batch on a case-insensitive filesystem`() = runTest {
        org.junit.jupiter.api.Assumptions.assumeTrue(workspace.paths.caseInsensitive)
        val created = run("""{"ops":[{"create":"src/new.py","content":"first"},{"create":"src/NEW.py","content":"second"}],"why":"w"}""")
        assertEquals("refused", status(created))
        assertFalse(Files.exists(repo.resolve("src/new.py")))
    }

    @Test
    fun `stale and missing anchor diagnostics redact secrets before returning and storing`() = runTest {
        val secret = "AKIA" + "IOSFODNN7EXAMPLE"
        val v = seen("src/b.py", 1, 2)
        repo.write("src/b.py", "x = '$secret'\ny = 2\n")
        val stale = run(anchored("src/b.py", v, hunk("x = 1", "x = 3")))
        assertFalse(stale.body.contains(secret))
        assertTrue(stale.header!!.runtime.redactionApplied)
        val current = seen("src/b.py", 1, 2)
        val missing = run(anchored("src/b.py", current, hunk("x = 'missing'", "x = 3")))
        assertFalse(missing.body.contains(secret))
        for (out in listOf(stale, missing)) {
            val blob = io.astrolabe.id.Digest(out.header!!.runtime.artifactRefs.first())
            assertFalse(String(store.blobs.get(blob)).contains(secret))
        }
    }

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
        assertNotNull(SqliteObservations(store, clock).get(SqliteAliases(store, clock).resolve(ids.work, 1)!!.canonicalId))
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

    private fun anchoredBy(path: String, expect: String?, vararg hunks: String) =
        """{"ops":[{"path":"$path"${expect?.let { ""","expect":"$it"""" } ?: ""},"hunks":[${hunks.joinToString(",")}]}],"why":"w"}"""

    @Test
    fun `an omitted or short expect resolves only to a version shown in this cell and never throws`() = runTest {
        val unseen = run(anchoredBy("src/b.py", null, hunk("x = 1", "x = 3")))
        assertEquals("refused", status(unseen))
        assertTrue(unseen.body.contains("expect: expect omitted and no version of 'src/b.py' is KNOWN: read it first"), unseen.body)
        val v = seen("src/b.py", 1, 2)
        val malformed = run(anchoredBy("src/b.py", "xyz", hunk("x = 1", "x = 3")))
        assertTrue(malformed.body.contains("expect: expect 'xyz' is not a content hash"), malformed.body)
        val tooShort = run(anchoredBy("src/b.py", v.hash8.take(3), hunk("x = 1", "x = 3")))
        assertTrue(tooShort.body.contains("is not a content hash"), tooShort.body)
        val other = if (v.digest.hex.startsWith("0000")) "ffff" else "0000"
        val unknown = run(anchoredBy("src/b.py", other, hunk("x = 1", "x = 3")))
        assertTrue(unknown.body.contains("expect @$other names no version of 'src/b.py' shown in this cell (shown: @${v.hash8}); read it first"), unknown.body)
        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/b.py")), "every refusal wrote nothing")

        // The four characters a header shows (any case, with or without @) name the shown version.
        val short = run(anchoredBy("src/b.py", "@" + v.hash8.take(4).uppercase(), hunk("x = 1", "x = 3")))
        assertEquals("ok", status(short), short.body)
        val after = registry.version("src/b.py")!!
        // The old version was dropped as stale: its short hash still names it, and the CAS refuses with the diff.
        val stale = run(anchoredBy("src/b.py", v.hash8.take(4), hunk("y = 2", "y = 4")))
        assertTrue(stale.body.contains("stale_expect: 'src/b.py' is @${after.hash8} now, not @${v.hash8}; diff since expect:"), stale.body)
        // Omitted, it is the one version KNOWN now: the post-edit view.
        val omitted = run(anchoredBy("src/b.py", null, hunk("y = 2", "y = 4")))
        assertEquals("ok", status(omitted), omitted.body)
        assertEquals("x = 3\ny = 4\n", Files.readString(repo.resolve("src/b.py")))

        val current = registry.version("src/b.py")!!
        workset.register(Entry("src/b.py", Ranges.single(1, 1), FileVersion.of("other bytes".toByteArray()), EntrySource.Look, 2, "#other", 5))
        val ambiguous = run(anchoredBy("src/b.py", null, hunk("x = 3", "x = 5")))
        assertTrue(ambiguous.body.contains("expect omitted and 2 versions of 'src/b.py' are KNOWN"), ambiguous.body)
        assertEquals(current, registry.version("src/b.py"))
    }

    @Test
    fun `an omitted or short expect keeps the stale-write protection and the displayed-ranges check (FX-01)`() = runTest {
        val shown = seen("src/a.py", 1, 2)
        val outside = run(anchoredBy("src/a.py", null, hunk("    return 2", "    return 20")))
        assertTrue(outside.body.contains("outside_displayed: hunk at 'src/a.py:6' lies outside the displayed ranges of @${shown.hash8} (1-2)"), outside.body)
        // A change the harness has not heard of: the expect names the shown version, never the current bytes.
        repo.write("src/a.py", a.replace("return 1", "return 99"))
        for (expect in listOf(null, shown.hash8.take(4), shown.hash8)) {
            val out = run(anchoredBy("src/a.py", expect, hunk("    return 1", "    return 10")))
            assertEquals("refused", status(out))
            assertTrue(out.body.contains("stale_expect: 'src/a.py' is @"), out.body)
            assertTrue(out.body.contains("-    return 1\n+    return 99"), "diff since expect: ${out.body}")
        }
        assertEquals(a.replace("return 1", "return 99"), Files.readString(repo.resolve("src/a.py")), "no write on a stale expect")
        assertTrue((1..4).all { preimages.of("edit-$it").isEmpty() })
    }

    @Test
    fun `delete and rename resolve an omitted or short expect like an anchored edit`() = runTest {
        val refused = run("""{"ops":[{"delete":"src/b.py"}],"why":"rm"}""")
        assertEquals("refused", status(refused))
        assertTrue(refused.body.contains("expect omitted and no version of 'src/b.py' is KNOWN"), refused.body)
        assertTrue(Files.exists(repo.resolve("src/b.py")))
        val vb = seen("src/b.py", 1, 1)
        val renamed = run("""{"ops":[{"rename":"src/b.py","to":"src/b2.py","expect":"${vb.hash8.take(4)}"}],"why":"mv"}""")
        assertEquals("ok", status(renamed), renamed.body)
        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/b2.py")))
        seen("src/b2.py", 1, 2)
        val deleted = run("""{"ops":[{"delete":"src/b2.py"}],"why":"rm"}""")
        assertEquals("ok", status(deleted), deleted.body)
        assertFalse(Files.exists(repo.resolve("src/b2.py")))
    }

    @Test
    fun `a delete then create of one path in one batch replaces it under the delete's guards with a preimage`() = runTest {
        val replace = """{"ops":[{"delete":"src/b.py"},{"create":"src/b.py","content":"z = 3\n"}],"why":"rewrite"}"""
        val unknown = run(replace)
        assertEquals("refused", status(unknown))
        assertTrue(unknown.body.contains("expect omitted and no version of 'src/b.py' is KNOWN"), unknown.body)
        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/b.py")))

        val before = seen("src/b.py", 1, 2)
        val replaced = run(replace)
        assertEquals("ok", status(replaced), replaced.body)
        val after = registry.version("src/b.py")!!
        assertTrue(replaced.body.contains("✓ 2 replace src/b.py @${before.hash8}→@${after.hash8}"), replaced.body)
        assertTrue(replaced.body.contains("delete + create of one path in one batch"), replaced.body)
        assertEquals("z = 3\n", Files.readString(repo.resolve("src/b.py")))
        val preimage = preimages.of("edit-2").single()
        assertEquals(before, preimage.versionBefore)
        assertEquals(after, preimage.versionAfter)
        assertTrue(workset.covers("src/b.py", after, LineRange(1, 1)), "the new content is the post-edit view")

        val reverted = run("""{"ops":[{"revert":"#2"}],"why":"undo"}""", turn = 2)
        assertEquals("ok", status(reverted), reverted.body)
        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/b.py")), "the preimage restores the replaced bytes")

        val createOnly = run("""{"ops":[{"create":"src/b.py","content":"w"}],"why":"w"}""")
        assertTrue(createOnly.body.contains("exists: 'src/b.py' exists"), "a create without the batch's own delete still refuses: ${createOnly.body}")
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
    fun `a postimage persistence failure reports the file already written and its recovery preimage`() = runTest {
        val version = seen("src/b.py", 1, 2)
        val failing = object : Os by os {
            override fun replaceFileAtomically(path: Path, bytes: ByteArray) {
                os.replaceFileAtomically(path, bytes)
                failNextBlob = true
            }
        }

        val out = run(anchored("src/b.py", version, hunk("x = 1", "x = 3")), edit(os = failing))

        assertEquals("x = 3\ny = 2\n", Files.readString(repo.resolve("src/b.py")))
        assertEquals("partial", status(out), out.body)
        assertFalse(out.applied)
        assertNotEquals(Effects.None, out.header!!.effects)
        assertTrue(out.header!!.runtime.effectsUnknown)
        assertTrue(out.body.contains("src/b.py"), out.body)
        assertFalse(out.body.contains("nothing was written"), out.body)
        val preimage = preimages.of("edit-1", "src/b.py")!!
        assertEquals("x = 1\ny = 2\n", String(preimages.bytesOf(preimage)))
        assertTrue(out.body.contains(preimage.preimageDigest.hash8), out.body)
    }

    @Test
    fun `a coherence failure after publication still reports the written file`() = runTest {
        val version = seen("src/b.py", 1, 2)
        coherence.register(ChangeListener { throw IOException("coherence storage unavailable") }).use {
            val out = run(anchored("src/b.py", version, hunk("x = 1", "x = 3")))

            assertEquals("x = 3\ny = 2\n", Files.readString(repo.resolve("src/b.py")))
            assertEquals("partial", status(out), out.body)
            assertFalse(out.applied)
            assertNotEquals(Effects.None, out.header!!.effects)
            assertTrue(out.header!!.runtime.effectsUnknown)
            assertTrue(out.body.contains("src/b.py"), out.body)
            assertFalse(out.body.contains("nothing was written"), out.body)
        }
    }

    @Test
    fun `a rename whose source cannot be deleted reports the published target`() = runTest {
        val version = seen("src/b.py", 1, 2)
        val failing = object : Os by os {
            override fun replaceFileAtomically(path: Path, bytes: ByteArray) {
                os.replaceFileAtomically(path, bytes)
                val source = repo.resolve("src/b.py")
                Files.delete(source)
                Files.createDirectory(source)
                Files.writeString(source.resolve("concurrent.txt"), "concurrent writer\n")
            }
        }

        val out = run("""{"ops":[{"rename":"src/b.py","to":"src/renamed.py","expect":"${version.digest.hex}"}],"why":"rename"}""", edit(os = failing))

        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/renamed.py")))
        assertEquals("concurrent writer\n", Files.readString(repo.resolve("src/b.py/concurrent.txt")))
        assertEquals("partial", status(out), out.body)
        assertFalse(out.applied)
        assertNotEquals(Effects.None, out.header!!.effects)
        assertTrue(out.header!!.runtime.effectsUnknown)
        assertTrue(out.body.contains("src/renamed.py"), out.body)
        assertTrue(out.header!!.runtime.scope!!.contains("src/renamed.py"), out.header!!.runtime.scope)
        assertFalse(out.body.contains("nothing was written"), out.body)
        val preimage = preimages.of("edit-1", "src/b.py")!!
        assertEquals("x = 1\ny = 2\n", String(preimages.bytesOf(preimage)))
        assertTrue(out.body.contains(preimage.preimageDigest.hash8), out.body)
    }

    @Test
    fun `a selective revert losing path identity after publication reports unknown effects`() = runTest {
        val version = seen("src/b.py", 1, 2)
        assertEquals("ok", status(run(anchored("src/b.py", version, hunk("x = 1", "x = 3")))))
        val changedIdentity = object : Os by os {
            override fun replaceFileAtomically(path: Path, bytes: ByteArray) {
                os.replaceFileAtomically(path, bytes)
                Files.move(path, path.resolveSibling("reverted-bytes.py"))
                Files.createDirectory(path)
            }
        }

        val out = run("""{"ops":[{"revert":"#1"}],"why":"undo"}""", edit(os = changedIdentity), turn = 2)

        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/reverted-bytes.py")), "the inverse was published before its path changed")
        assertTrue(Files.isDirectory(repo.resolve("src/b.py")))
        assertFalse(out.applied)
        assertTrue(status(out) in setOf("partial", "unknown_outcome"), out.body)
        assertNotEquals(Effects.None, out.header!!.effects)
        assertTrue(out.header!!.runtime.effectsUnknown)
        assertTrue(out.body.contains("src/b.py"), out.body)
        assertFalse(out.body.contains("nothing was written"), out.body)
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
    fun `turn revert refuses every effect after the contract narrows`() = runTest {
        val env = EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1"))
        val dirty = DirtyState(workspace, store.blobs, Stamper(workspace, env), ids, clock)
        val shadow = ShadowRef(ids.work, ids.attempt, workspace, store, dirty, os, clock)
        shadow.open(dirty.capture(0))
        repo.write("src/a.py", a.replace("return 1", "return 10"))
        Files.delete(repo.resolve("src/b.py"))
        repo.write("src/new.py", "added = 1\n")
        repo.write("tests/test_a.py", "def test_a():\n    assert a() == 10\n")
        shadow.snapshot(1)
        val editor = edit(shadow = shadow)
        contracts.amendByUser(ids.work, "only tests may change now") {
            it.copy(scope = it.scope.copy(writePaths = listOf("tests/")))
        }

        val out = run("""{"ops":[{"revert":"turn:0"}],"why":"undo"}""", editor, turn = 2)

        assertEquals("refused", status(out), out.body)
        assertFalse(out.applied)
        assertTrue(out.body.contains("scope"), out.body)
        for (path in listOf("src/a.py", "src/b.py", "src/new.py")) assertTrue(out.body.contains(path), out.body)
        assertEquals(a.replace("return 1", "return 10"), Files.readString(repo.resolve("src/a.py")))
        assertFalse(Files.exists(repo.resolve("src/b.py")), "an excluded deleted file must not be recreated")
        assertEquals("added = 1\n", Files.readString(repo.resolve("src/new.py")), "an excluded new file must not be deleted")
        assertEquals("def test_a():\n    assert a() == 10\n", Files.readString(repo.resolve("tests/test_a.py")), "the allowed part must also stay unchanged when the batch refuses")
        assertTrue(syntaxCalls.isEmpty())
    }

    @Test
    fun `turn revert applies the current increment warning and justification rules`() = runTest {
        val env = EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1"))
        val dirty = DirtyState(workspace, store.blobs, Stamper(workspace, env), ids, clock)
        val shadow = ShadowRef(ids.work, ids.attempt, workspace, store, dirty, os, clock)
        shadow.open(dirty.capture(0))
        repo.write("src/b.py", "x = 10\ny = 2\n")
        shadow.snapshot(1)
        val editor = edit(shadow = shadow).also {
            it.increment = Increment("inc-1", listOf("R1"), accept = emptyList(), writeScope = listOf("tests/"), expectedFiles = 1, title = "tests only")
        }

        val first = run("""{"ops":[{"revert":"turn:0"}],"why":"undo"}""", editor, turn = 2)
        assertEquals("ok", status(first), first.body)
        assertTrue(first.body.contains("outside the increment's write scope (inside the contract): src/b.py"), first.body)
        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/b.py")))
        shadow.snapshot(2)

        val second = run("""{"ops":[{"revert":"turn:1"}],"why":"undo"}""", editor, turn = 3)
        assertEquals("refused", status(second), second.body)
        assertEquals("x = 1\ny = 2\n", Files.readString(repo.resolve("src/b.py")))
        val justified = run("""{"ops":[{"revert":"turn:1"}],"why":"src/b.py restores the implementation"}""", editor, turn = 3)
        assertEquals("ok", status(justified), justified.body)
        assertEquals("x = 10\ny = 2\n", Files.readString(repo.resolve("src/b.py")))
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
        assertEquals("partial", status(mixed))
        assertTrue(Files.exists(repo.resolve("src/new.py")), "D-371: a refused path refuses only its own group")
        assertFalse(Files.exists(repo.resolve("docs/new.md")))
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
        assertTrue(transform.body.contains("unsupported: transform unsupported: this cell has no transform runner (D-41)"), transform.body)
    }

    @Test
    fun `normalized line replacements preserve indentation and surrounding bytes`() = runTest {
        for ((index, ending) in listOf("\n", "\r\n").withIndex()) {
            val path = "src/normalized$index.py"
            val before = "def example():${ending}\treturn    1  ${ending}${ending}# untouched${ending}"
            repo.write(path, before)
            val version = seen(path, 1, 4)
            val out = run(anchored(path, version, hunk("    return 1\n", "    return 2\n")))
            assertEquals("ok", status(out), out.body)
            // D-324: the file is tab-indented, so the one-width space indentation of the replacement follows it.
            assertEquals("def example():${ending}\treturn 2${ending}${ending}# untouched${ending}", Files.readString(repo.resolve(path)))
        }
    }

    @Test
    fun `replacements take the dominant line ending and unambiguous tab indentation of the file`() = runTest {
        repo.write("src/crlf.py", "def a():\r\n    x = 1\r\n    y = 2\r\n")
        val crlf = seen("src/crlf.py", 1, 3)
        assertEquals("ok", status(run(anchored("src/crlf.py", crlf, hunk("\n    x = 1\n", "\n    x = 10\n    z = 3\n")))))
        val pure = Files.readString(repo.resolve("src/crlf.py"))
        assertEquals("def a():\r\n    x = 10\r\n    z = 3\r\n    y = 2\r\n", pure)
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(pure), "no bare LF in a CRLF file")

        repo.write("src/lf.py", "a = 1\r\nb = 2\nc = 3\nd = 4\n")
        val lf = seen("src/lf.py", 1, 4)
        assertEquals("ok", status(run(anchored("src/lf.py", lf, hunk("c = 3", "c = 30\ne = 5")))))
        assertEquals("a = 1\r\nb = 2\nc = 30\ne = 5\nd = 4\n", Files.readString(repo.resolve("src/lf.py")))

        repo.write("src/tabs.py", "def t():\n\tif x:\n\t\treturn 1\n")
        val tabs = seen("src/tabs.py", 1, 3)
        assertEquals("ok", status(run(anchored("src/tabs.py", tabs, hunk("\tif x:\n\t\treturn 1\n", "    if y:\n        return 2\n")))))
        assertEquals("def t():\n\tif y:\n\t\treturn 2\n", Files.readString(repo.resolve("src/tabs.py")))

        // A nested replacement uniformly at 8 spaces over two-tab lines is two 4-wide levels, not one 8-wide level.
        repo.write("src/nested.py", "def n():\n\tif x:\n\t\tlog()\n\t\treturn 1\n")
        val nested = seen("src/nested.py", 1, 4)
        assertEquals("ok", status(run(anchored("src/nested.py", nested, hunk("\t\tlog()\n\t\treturn 1\n", "        log()\n        return 2\n")))))
        assertEquals("def n():\n\tif x:\n\t\tlog()\n\t\treturn 2\n", Files.readString(repo.resolve("src/nested.py")))

        repo.write("src/mixed.py", "def m():\n\tif x:\n        return 1\n")
        val mixed = seen("src/mixed.py", 1, 3)
        assertEquals("ok", status(run(anchored("src/mixed.py", mixed, hunk("        return 1\n", "    return 2\n")))))
        assertEquals("def m():\n\tif x:\n    return 2\n", Files.readString(repo.resolve("src/mixed.py")), "mixed indentation is ambiguous")
    }

    @Test
    fun `normalized anchors apply to CRLF files without changing their line endings`() = runTest {
        val v = seen("src/win.py", 1, 2)
        val out = run(anchored("src/win.py", v, hunk("def w():\n\treturn 1", "def w():\n    return 2\n    # two")))
        assertEquals("ok", status(out))
        assertEquals("def w():\r\n    return 2\r\n    # two\r\n", Files.readString(repo.resolve("src/win.py")))
    }

    @Test
    fun `an anchor found only after CRLF and trailing-blank normalisation applies once and says so`() = runTest {
        repo.write("src/ws.py", "def f():\r\n    x = 1   \r\n    return x\r\n")
        val v = seen("src/ws.py", 1, 3)
        val out = run(anchored("src/ws.py", v, hunk("    x = 1\n    return x", "    x = 2\n    return x")))
        assertEquals("ok", status(out), out.body)
        assertTrue(out.body.contains("(anchor matched after whitespace normalisation)"), out.body)
        assertEquals("def f():\r\n    x = 2\r\n    return x\r\n", Files.readString(repo.resolve("src/ws.py")), "the file keeps CRLF")
        val exact = run(anchored("src/ws.py", registry.version("src/ws.py")!!, hunk("    x = 2", "    x = 3")))
        assertFalse(exact.body.contains("normalisation"), exact.body)

        repo.write("src/twice.py", "a = 1 \nb = 2\na = 1\t\n")
        val twice = seen("src/twice.py", 1, 3)
        val ambiguous = run(anchored("src/twice.py", twice, hunk("a  = 1", "a = 5")))
        assertEquals("refused", status(ambiguous))
        assertTrue(ambiguous.body.contains("anchor 2× in 'src/twice.py': sites 1, 3"), ambiguous.body)
        assertEquals("a = 1 \nb = 2\na = 1\t\n", Files.readString(repo.resolve("src/twice.py")))
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
