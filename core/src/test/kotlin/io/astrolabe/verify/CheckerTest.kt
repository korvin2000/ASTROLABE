package io.astrolabe.verify

import io.astrolabe.auth.Redaction
import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.SqliteObservations
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.ChildCommands
import io.astrolabe.os.LocalOs
import io.astrolabe.store.Store
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.Ranges
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.7.2 end-of-turn checker: Δ + absolute (FX-18), time box (FX-58), unavailable/inconclusive verdicts, archived supersession, mutation announcement. */
class CheckerTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var workspace: Workspace
    private lateinit var registry: VersionRegistry
    private lateinit var coherence: Coherence
    private lateinit var os: LocalOs
    private lateinit var stamper: Stamper
    private lateinit var diagnostics: Path
    private val workset = Workset()
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val windows = ChildCommands.isWindows

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
        registry = VersionRegistry(workspace)
        coherence = Coherence(registry).also { it.register(workset) }
        os = LocalOs(clock)
        stamper = Stamper(workspace, EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1")))
        diagnostics = stateRoot.resolve("diag.txt")
    }

    @AfterTest
    fun tearDown() {
        coherence.close()
        os.close()
        store.close()
        repo.close()
    }

    /** A fake type checker: prints the diagnostics file (outside the workspace) and exits non-zero when it is not empty. */
    private fun printsDiagnostics(): Command {
        val file = diagnostics.toString()
        return if (windows) {
            // No inner quotes: the JDK's Windows argument quoting would escape them for cmd.exe (the temp path has no spaces).
            Command(listOf("cmd.exe", "/d", "/s", "/c", "type $file&for %s in ($file) do if %~zs gtr 0 exit /b 1"))
        } else {
            Command(listOf("/bin/sh", "-c", "cat '$file'; if [ -s '$file' ]; then exit 1; fi"))
        }
    }

    /**
     * A fake checker whose argv names the tool (`<dir>/ruff` or `ruff.cmd`), so the diagnostics parser recognises it:
     * it prints the recorded output [resource] and exits with [exit]. The script lives outside the workspace.
     */
    private fun fakeTool(name: String, resource: String, exit: Int): Command {
        val dir = Files.createDirectories(stateRoot.resolve("bin"))
        val output = dir.resolve("$name-$exit.txt")
        Files.writeString(output, recorded(resource))
        val script = if (windows) {
            dir.resolve("$name.cmd").also { Files.writeString(it, "@echo off\r\ntype $output\r\nexit /b $exit\r\n") }
        } else {
            dir.resolve(name).also {
                Files.writeString(it, "#!/bin/sh\ncat '$output'\nexit $exit\n")
                Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
        return Command(listOf(script.toString(), "check"))
    }

    private fun recorded(name: String) = javaClass.getResourceAsStream("/shaper/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    private fun shell(windowsLine: String, posixLine: String): Command =
        if (windows) Command(listOf("cmd.exe", "/d", "/s", "/c", windowsLine)) else Command(listOf("/bin/sh", "-c", posixLine))

    private fun check(id: String, command: Command, kind: CheckKind = CheckKind.Type, selector: Selector = Selector.All) =
        Check(id, kind, selector, Closure.Known(setOf("src/a.py")), CostClass.Fast, Trigger.EndOfTurn, command = command)

    private fun checker(checks: Checks) = Checker(checks, TrustedLocalRunner(os), os, stamper, registry, workspace, store.blobs, Redaction(), FixedIdGen(), ids, stateRoot.resolve("logs"))

    @Test
    fun `delta against the superseded result and the absolute count, no new errors while failures persist (FX-18)`() {
        val checks = Checks.empty()
        checks.register(check("CHK-types-touched", printsDiagnostics()))
        val checker = checker(checks)
        Files.writeString(diagnostics, "src/a.py:1:5: error: bad\nsrc/b.py:2:1: error: worse\n")

        val first = checker.run(listOf("src/a.py")).single()
        assertEquals(Outcome.Failed, first.outcome)
        assertEquals(2, first.errors)
        assertNull(first.delta, "the first run has nothing to differ from")
        assertEquals("types: now 2 @${first.stampAfter!!.hash8.take(4)}", ChecksRender.line(first.line()))
        assertEquals(first.resultId, checks["CHK-types-touched"]!!.last!!.receiptId)
        assertEquals(2, checks["CHK-types-touched"]!!.last!!.counts!!.errors)

        val same = checker.run(listOf("src/a.py")).single()
        assertEquals(Delta(0, 0), same.delta)
        assertEquals(first.resultId, same.supersedes)
        assertEquals("types: no change · still 2 @${same.stampAfter!!.hash8.take(4)}", ChecksRender.line(same.line()))
        assertEquals(listOf(first.resultId), checker.history("CHK-types-touched").map { it.resultId }, "the superseded result is archived")
        assertEquals(same.resultId, checker.latest().single().resultId)

        Files.writeString(diagnostics, "src/b.py:2:1: error: worse\n")
        val fewer = checker.run(listOf("src/a.py")).single()
        assertEquals(Delta(0, 1), fewer.delta)
        assertEquals("types: Δ +0 −1 · now 1 @${fewer.stampAfter!!.hash8.take(4)}", ChecksRender.line(fewer.line()))

        Files.writeString(diagnostics, "src/b.py:2:1: error: worse\nsrc/c.py:9:1: error: new\nsrc/d.py:1:1: error: newer\n")
        val more = checker.run(listOf("src/a.py")).single()
        assertEquals(Delta(2, 0), more.delta)
        assertTrue(checker.render(more.stampAfter).startsWith("── Checks @${more.stampAfter!!.hash8.take(4)} ── types: Δ +2 −0 · now 3"), checker.render(more.stampAfter))
        assertEquals(3, checker.history("CHK-types-touched").size)
        assertTrue(Files.exists(store.blobs.path(more.log!!)), "the capture is a blob")
    }

    @Test
    fun `a check killed at the time box is timeout, one the box no longer allows is not_run (FX-58)`() {
        val checks = Checks.empty()
        checks.register(check("CHK-types-touched", shell("ping -n 61 127.0.0.1 >NUL", "sleep 60")))
        checks.register(check("CHK-lint", shell("echo lint", "echo lint"), kind = CheckKind.Lint))
        val results = checker(checks).run(listOf("src/a.py"), timeBoxSeconds = 1)
        assertEquals(listOf(Outcome.Timeout, Outcome.NotRun), results.map { it.outcome })
        assertTrue(ChecksRender.line(results[0].line()).startsWith("types: timeout (started, killed at the 1s time box)"), ChecksRender.line(results[0].line()))
        assertEquals("lint: not run", ChecksRender.line(results[1].line()).substringBefore(" @"))
        assertTrue(results[1].reason!!.contains("exhausted before dispatch"))
        assertEquals(Outcome.Timeout, checks["CHK-types-touched"]!!.last!!.outcome)
    }

    @Test
    fun `a missing runner is unavailable and exit 0 without a parser is inconclusive, never green`() {
        val checks = Checks.empty()
        checks.register(check("CHK-types-touched", Command(listOf("no-such-checker-xyz", "--strict"))))
        checks.register(check("CHK-lint", shell("echo all good", "echo all good"), kind = CheckKind.Lint))
        val (missing, clean) = checker(checks).run(listOf("src/a.py"))
        assertEquals(Outcome.Unavailable, missing.outcome)
        assertTrue(ChecksRender.line(missing.line()).startsWith("types: unavailable (cannot start no-such-checker-xyz"), ChecksRender.line(missing.line()))
        assertEquals(Outcome.Inconclusive, clean.outcome)
        assertEquals(0, clean.exit)
        assertTrue(ChecksRender.line(clean.line()).startsWith("lint: inconclusive (exit 0 · no diagnostics parser for"), ChecksRender.line(clean.line()))
        assertNull(checks["CHK-lint"]!!.last!!.counts)
    }

    @Test
    fun `a recognised tool is green on its success signature and red with its exact diagnostics, warnings never counting`() {
        val checks = Checks.empty()
        checks.register(check("CHK-lint", fakeTool("ruff", "ruff-pass.txt", 0), kind = CheckKind.Lint, selector = Selector.Touched))
        checks.register(check("CHK-types-touched", fakeTool("mypy", "mypy-fail.txt", 1)))
        checks.register(check("CHK-cargo", fakeTool("cargo", "cargo-check-pass.txt", 0), kind = CheckKind.Type))
        val (clean, failing, cargo) = checker(checks).run(listOf("src/a.py", "src/b.py"))

        assertEquals(Outcome.Passed, clean.outcome)
        assertEquals(0, clean.errors)
        assertNull(clean.reason)
        assertEquals("lint(touched): ✓ 2 files @${clean.stampAfter!!.hash8.take(4)}", ChecksRender.line(clean.line()))
        assertEquals(Counts(discovered = 2), checks["CHK-lint"]!!.last!!.counts, "a pass records the files given to the checker (D-72)")
        assertEquals(Outcome.Passed, checks["CHK-lint"]!!.last!!.outcome)

        assertEquals(Outcome.Failed, failing.outcome)
        assertEquals(
            listOf(
                "src/shop/cart.py:12: Incompatible return value type (got \"str\", expected \"int\")",
                "src/shop/pricing.py:7:5: Argument 1 to \"discount\" has incompatible type \"None\"; expected \"float\"",
            ),
            failing.errorLines,
            "the note and the warning are not errors",
        )
        assertEquals("types: now 2 @${failing.stampAfter!!.hash8.take(4)}", ChecksRender.line(failing.line()))
        assertEquals(Counts(errors = 2), checks["CHK-types-touched"]!!.last!!.counts)

        // `cargo check` with a warning and `Finished` is green: warnings are not errors (§8.3).
        assertEquals(Outcome.Passed, cargo.outcome)
        assertEquals(emptyList(), cargo.errorLines)
        assertEquals(Counts(discovered = 2), checks["CHK-cargo"]!!.last!!.counts)
    }

    @Test
    fun `touched selectors append the touched paths and a check that moves files is announced like a run`() {
        val touched = check("CHK-types-touched", Command(listOf("pyright", "--outputjson")), selector = Selector.Touched)
        assertEquals(listOf("pyright", "--outputjson", "src/a.py", "src/b.py"), Checker.argvFor(touched, listOf("src\\b.py", "src/a.py", "src/a.py")))
        assertEquals(listOf("pyright", "--outputjson"), Checker.argvFor(touched.copy(selector = Selector.All), listOf("src/a.py")))

        val v = registry.version("src/a.py")!!
        workset.register(Entry("src/a.py", Ranges.single(1, 2), v, EntrySource.Look, 1, "#1", 10))
        val checks = Checks.empty()
        checks.register(check("CHK-lint", shell("echo x> src\\a.py", "echo x > src/a.py"), kind = CheckKind.Lint))
        val result = checker(checks).run(listOf("src/a.py")).single()
        assertEquals(listOf("src/a.py"), result.changedPaths)
        assertTrue(result.stampBefore != result.stampAfter)
        assertEquals(registry.version("src/a.py"), registry.recorded("src/a.py"), "announced to the registry")
        assertTrue(workset.pendingDrops.single().cause.startsWith("check CHK-lint"), "coherence dropped the read with the cause")
        assertTrue(checker(Checks.empty()).run(emptyList()).isEmpty(), "nothing touched, nothing checked")
        assertNull(SqliteObservations(store, clock).get("obs-1"), "the checker records results and blobs, not observations")
    }
}
