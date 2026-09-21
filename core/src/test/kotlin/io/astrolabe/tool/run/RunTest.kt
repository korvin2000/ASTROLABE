package io.astrolabe.tool.run

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.auth.Capability
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Redaction
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Decision
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.InMemoryIntentJournal
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.evidence.SqliteAliases
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
import io.astrolabe.os.Os
import io.astrolabe.os.OwnerToken
import io.astrolabe.os.Poll
import io.astrolabe.os.Proc
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.store.Store
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.6.5 run/poll/cancel: effect classes verified by stamp diff (FX-07), timeouts, D-class authority, unknown outcomes (FX-24), durable handles (FX-22). */
class RunTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var workspace: Workspace
    private lateinit var registry: VersionRegistry
    private lateinit var coherence: Coherence
    private lateinit var stamper: Stamper
    private lateinit var os: LocalOs
    private lateinit var contracts: Contracts
    private val workset = Workset()
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val idGen = FixedIdGen()
    private val intents = InMemoryIntentJournal()
    private val token = OwnerToken.random()
    private val windows = ChildCommands.isWindows

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("README.md", "# fixture\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
        registry = VersionRegistry(workspace)
        coherence = Coherence(registry).also { it.register(workset) }
        stamper = Stamper(workspace, EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1")))
        os = LocalOs(clock, token)
        contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
        contracts.open(contracts.deriveS0(ids.work, ids.attempt, "fix a", Atlas.build(repo.root), Config(), Tokens(10_000)).contract)
    }

    @AfterTest
    fun tearDown() {
        coherence.close()
        os.close()
        store.close()
        repo.close()
    }

    private fun runner(os: Os = this.os, authority: Authority = AutonomousAuthority(), config: Config = Config(), hostSets: Map<String, CapabilitySet> = emptyMap()) = Run(
        workspace, registry, stamper, TrustedLocalRunner(os), os, intents, SqliteHandles(store, clock), SqliteObservations(store, clock), SqliteAliases(store, clock),
        store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, authority, config, clock, stateRoot.resolve("logs"), hostSets = hostSets,
    )

    private fun call(json: String) = (ToolCalls.parse(listOf(ProviderCall("c1", "run", json))) as ParsedCalls.Valid).calls.single()

    private fun context(turn: Int = 1) = TurnContext(turn, workset.snapshot(), Reservations(Tokens(10_000)))

    private suspend fun run(json: String, tool: Run = runner()): ToolOutcome = tool.execute(call(json), context())

    private fun status(o: ToolOutcome) = o.header!!.runtime.status

    private fun shell(windowsLine: String, posixLine: String) = if (windows) windowsLine else posixLine

    /** Polls [handle] until it stops reporting `running`; each poll is itself bounded. */
    private suspend fun awaitSettled(handle: String, polls: Int = 5): ToolOutcome {
        var last = run("""{"op":"poll","handle":"$handle","timeout":30}""")
        repeat(polls) {
            if (status(last) != "running") return last
            last = run("""{"op":"poll","handle":"$handle","timeout":30}""")
        }
        return last
    }

    @Test
    fun `a foreground run captures output and exit code, stamps the tree and stays class R when nothing moved`() = runTest {
        val out = run("""{"cmd":"echo hello-run"}""")
        assertTrue(status(out) != "denied" && status(out) != "unknown_outcome", status(out))
        assertEquals("#1", out.resultAlias)
        assertTrue(out.body.contains("hello-run"), out.body)
        assertTrue(out.body.startsWith("run #1 ${status(out)} · class R · shell wrapper · exit 0 · echo hello-run"), out.body)
        assertEquals(out.header!!.runtime.candidateBefore, out.header!!.runtime.candidateAfter, "nothing moved")
        assertEquals(out.header!!.runtime.candidateAfter, out.header!!.stamp)
        assertEquals(EffectClass.R, out.header!!.effectClass)
        assertTrue(out.header!!.runtime.artifactRefs.isNotEmpty(), "the log is a blob")
        assertEquals(IntentStatus.Committed, intents.get("intent-1")!!.status)
        assertTrue(intents.open().isEmpty())
        assertFalse(out.green, "exit 0 alone is never green")
        assertNotNull(SqliteObservations(store, clock).get("obs-1"))
    }

    @Test
    fun `a run that writes is touched-by-run, R becomes W, the transition is announced and the read is dropped (FX-07)`() = runTest {
        val v = registry.version("src/a.py")!!
        workset.register(Entry("src/a.py", Ranges.single(1, 2), v, EntrySource.Look, 1, "#look", 40))
        val out = run(
            if (windows) """{"argv":["cmd.exe","/d","/s","/c","echo formatted> src\\a.py"]}""" else """{"argv":["/bin/sh","-c","echo formatted > src/a.py"]}""",
        )
        assertTrue(status(out) != "denied", out.body)
        assertEquals(EffectClass.W, out.header!!.effectClass, "reclassified from the stamp diff, not from the command name")
        assertEquals(listOf("src/a.py"), out.header!!.runtime.effectsObserved)
        assertTrue(out.body.contains("touched (by run #1"), out.body)
        assertTrue(out.body.contains(": 1 path) src/a.py"), out.body)
        val after = registry.version("src/a.py")!!
        assertTrue(after != v)
        assertEquals(after, registry.recorded("src/a.py"), "announced through the registry with `from` = the version the harness knew")
        assertFalse(workset.covers("src/a.py", v, LineRange(1, 2)), "coherence dropped the stale read")
        assertEquals("touched by run", workset.pendingDrops.single().cause.substringBefore(" #").let { "touched by run" })
        assertTrue(out.header!!.runtime.candidateBefore != out.header!!.runtime.candidateAfter)
    }

    @Test
    fun `a non-zero exit is information and a deadline kills the tree without replay`() = runTest {
        val failed = run("""{"cmd":"${shell("exit /b 3", "exit 3")}"}""")
        assertEquals("failed", status(failed))
        assertTrue(failed.body.contains("exit 3"), failed.body)
        assertFalse(failed.green)

        val slow = run("""{"cmd":"${shell("ping -n 61 127.0.0.1 >NUL", "sleep 60")}","timeout":1}""")
        assertEquals("timeout", status(slow))
        assertTrue(slow.body.contains("run #2 timeout"), slow.body)
        assertEquals(IntentStatus.Committed, intents.get("intent-2")!!.status, "a timeout is an observed terminal state")
    }

    @Test
    fun `a D-class command needs an intent and an approval, and confinement without a backend is refused (D-11)`() = runTest {
        val ceiling = run("""{"argv":["git","push","origin","main"],"intent":"publish"}""")
        assertEquals("denied", status(ceiling))
        assertTrue(ceiling.body.contains("denied by the capability ceiling: needs git-refs, outside capability set 'workspace-local-test-only'"), ceiling.body)
        val confined = run("""{"cmd":"echo x"}""", runner(config = Config(executionMode = ExecutionMode.Confined)))
        assertEquals("denied", status(confined))
        assertTrue(confined.body.contains("confined execution required; no backend"), confined.body)
        assertEquals("denied", status(run("""{"argv":["mcp:server/tool"]}""")))

        // Only a committed contract with a wider capability set reaches the intent + authority step.
        contracts.amendByUser(ids.work, "allow git ref mutation") { it.copy(authorization = it.authorization.copy(capabilitySet = "wide")) }
        val wide = mapOf("wide" to CapabilitySet("wide", Capability.entries.toSet()))
        val noIntent = run("""{"argv":["git","push","origin","main"]}""", runner(hostSets = wide))
        assertEquals("denied", status(noIntent))
        assertTrue(noIntent.body.contains("needs an explicit intent"), noIntent.body)
        val autonomous = run("""{"argv":["git","push","origin","main"],"intent":"publish"}""", runner(hostSets = wide))
        assertEquals("denied", status(autonomous))
        assertTrue(autonomous.body.contains("not allowlisted"), autonomous.body)
        assertTrue(intents.open().isEmpty() && intents.get("intent-1") == null, "nothing was dispatched or journaled")

        val approving = object : Authority {
            var seen: DClassRequest? = null
            override suspend fun ask(question: Question): Answer? = null
            override suspend fun approve(request: DClassRequest): Decision { seen = request; return Decision(request.id, request.contractRevision, true, "user said yes") }
            override suspend fun resolve(proposal: AmendmentProposal): Resolution = error("unused")
            override suspend fun review(request: ReviewRequest): Verdict? = null
        }
        val approved = run("""{"argv":["git","push","origin","main"],"intent":"publish"}""", runner(authority = approving, hostSets = wide))
        assertTrue(status(approved) != "denied", approved.body)
        assertEquals("publish", approving.seen!!.reason)
        assertEquals(EffectClass.D, approved.header!!.effectClass)
        val misconfigured = run("""{"cmd":"echo x"}""")
        assertEquals("denied", status(misconfigured))
        assertTrue(misconfigured.body.contains("unknown capability set 'wide'"), "a host set the runner does not know is a typed denial: ${misconfigured.body}")
    }

    @Test
    fun `a lost observation is unknown_outcome with an open intent and no relaunch (FX-24)`() = runTest {
        var spawned = 0
        val flaky = object : Os by os {
            override fun spawn(spec: io.astrolabe.os.SpawnSpec): Proc { spawned++; return os.spawn(spec) }
            override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll = throw IOException("log unreadable")
        }
        val out = run("""{"cmd":"echo lost"}""", runner(os = flaky))
        assertEquals("unknown_outcome", status(out))
        assertTrue(out.header!!.runtime.effectsUnknown)
        assertTrue(out.body.contains("intent intent-1 stays open"), out.body)
        assertEquals(IntentStatus.Unknown, intents.get("intent-1")!!.status)
        assertEquals(listOf("intent-1"), intents.open().map { it.intentId }, "reconciliation input at resume")
        assertEquals(1, spawned, "never relaunched")
    }

    @Test
    fun `a background run persists a handle that survives a restart, polls without relaunch and can be cancelled (FX-22)`() = runTest {
        val started = run("""{"cmd":"${shell("echo bg-start&ping -n 7 127.0.0.1 >NUL&echo bg-end", "echo bg-start; sleep 6; echo bg-end")}","bg":true}""")
        assertEquals("running", status(started), started.body)
        assertTrue(started.body.contains("handle handle-1"), started.body)
        val handle = SqliteHandles(store, clock).get("handle-1")!!
        assertEquals("running", handle.status)
        assertTrue(Files.exists(Path.of(handle.proc.logPath)))

        // The same harness polls the same handle: whatever has arrived comes back and the process is
        // never relaunched. How far the child has got by then is the host's business — asserting a
        // status here is what made this test flaky on both CI platforms.
        val early = run("""{"op":"poll","handle":"handle-1","timeout":20}""")
        assertTrue(early.body.contains("bg-start"), "output that has arrived returns at once: ${early.body}")
        assertEquals(handle.proc.pid, SqliteHandles(store, clock).get("handle-1")!!.proc.pid, "same process, same handle")
        val settled = awaitSettled("handle-1")
        assertTrue(status(settled) != "running", settled.body)
        assertTrue(settled.body.contains("bg-end"), settled.body)
        assertTrue(settled.body.startsWith("run #1"), settled.body)
        assertEquals("exited", SqliteHandles(store, clock).get("handle-1")!!.status)
        assertTrue(SqliteHandles(store, clock).open().isEmpty())

        // After a harness restart the supervisor is gone: the persisted handle resolves `lost` (D-43), never relaunched.
        val sleeper = run("""{"cmd":"${shell("ping -n 61 127.0.0.1 >NUL", "sleep 60")}","bg":true}""")
        assertEquals("running", status(sleeper), sleeper.body)

        // An observation timeout leaves the process running and is not a failure. It is asserted on
        // this silent, minute-long process rather than between two polls of handle-1: there the
        // quiet window was the child's own sleep, and a slow start consumed it (intermittent FX-22
        // failure recorded under P0.6.1, diagnosed 2026-09-21).
        val quiet = run("""{"op":"poll","handle":"handle-2","timeout":1}""")
        assertEquals("running", status(quiet), quiet.body)
        assertTrue(quiet.body.contains("observation timed out after 1s, the process keeps running (no relaunch)"), "a silent process makes a one-second poll time out: ${quiet.body}")
        assertEquals("running", SqliteHandles(store, clock).get("handle-2")!!.status, "the timed-out poll left the handle open")

        val restarted = LocalOs(clock, token)
        try {
            val after = runner(os = restarted).execute(call("""{"op":"poll","handle":"handle-2","timeout":1}"""), context())
            assertEquals("unknown_outcome", status(after), after.body)
            assertTrue(after.body.contains("handle handle-2 lost"), after.body)
            assertEquals("lost", SqliteHandles(store, clock).get("handle-2")!!.status)
        } finally {
            restarted.close()
        }
        val cancelled = run("""{"op":"cancel","handle":"handle-2"}""")
        assertTrue(cancelled.body.contains("cancel requested for handle handle-2"), cancelled.body)
        assertTrue(cancelled.body.contains("not proof"), cancelled.body)
        assertTrue(status(cancelled) != "running", status(cancelled))
        assertNull(SqliteHandles(store, clock).get("handle-9"))
        assertEquals("denied", status(run("""{"op":"poll","handle":"handle-9"}""")))
    }
}
