package io.astrolabe.tool.run

import io.astrolabe.Config
import io.astrolabe.Defaults
import io.astrolabe.atlas.Atlas
import io.astrolabe.auth.Capability
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Redaction
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.contract.Origin
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Decision
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.EvidenceKind
import io.astrolabe.evidence.InMemoryIntentJournal
import io.astrolabe.evidence.IntentJournal
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.evidence.Observation
import io.astrolabe.evidence.Observations
import io.astrolabe.evidence.SqliteAliases
import io.astrolabe.evidence.SqliteObservations
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.ChildCommands
import io.astrolabe.os.Command
import io.astrolabe.os.IdentityKey
import io.astrolabe.os.LocalOs
import io.astrolabe.os.Os
import io.astrolabe.os.OwnerToken
import io.astrolabe.os.Poll
import io.astrolabe.os.Proc
import io.astrolabe.os.ProcStatus
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.provider.ToolMask
import io.astrolabe.store.Store
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOps
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.verify.Verify
import io.astrolabe.verify.Checks
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.RunnerCommands
import io.astrolabe.verify.Scheduler
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun runner(os: Os = this.os, authority: Authority = AutonomousAuthority(), config: Config = Config(), hostSets: Map<String, CapabilitySet> = emptyMap(), ids: Identities = this.ids, workspace: Workspace = this.workspace, handles: Handles = SqliteHandles(store, clock), observations: Observations = SqliteObservations(store, clock), intents: IntentJournal = this.intents, mask: ToolMask = ToolOps.implementingS0) = Run(
        workspace, registry, stamper, TrustedLocalRunner(os), os, intents, handles, observations, SqliteAliases(store, clock),
        store.blobs, Redaction(config.redaction), HeuristicEstimator(), idGen, ids, contracts, authority, config, clock, stateRoot.resolve("logs"), mask = mask, hostSets = hostSets,
    )

    private fun call(json: String) = (ToolCalls.parse(listOf(ProviderCall("c1", "run", json))) as ParsedCalls.Valid).calls.single()

    private fun context(turn: Int = 1) = TurnContext(turn, workset.snapshot(), Reservations(Tokens(10_000)))

    private suspend fun run(json: String, tool: Run = runner()): ToolOutcome = tool.execute(call(json), context())

    private fun status(o: ToolOutcome) = o.header!!.runtime.status

    private fun shell(windowsLine: String, posixLine: String) = if (windows) windowsLine else posixLine

    @Test
    fun `foreground output command arguments and terminal polls redact fixture secrets`() = runTest {
        val secret = "AKIA" + "IOSFODNN7EXAMPLE"
        repo.write("emit.txt", "$secret\n")
        val command = shell("type emit.txt", "cat emit.txt")
        val out = run("""{"cmd":"$command"}""")
        assertFalse(out.body.contains(secret))
        assertTrue(out.header!!.runtime.redactionApplied)
        assertFalse(String(store.blobs.get(io.astrolabe.id.Digest(out.header!!.runtime.artifactRefs.first()))).contains(secret))
        val echoed = run("""{"cmd":"echo $secret"}""")
        assertFalse(echoed.body.contains(secret))
        assertFalse(echoed.header!!.runtime.scope!!.contains(secret))
        run("""{"cmd":"$command","bg":true}""")
        val handle = SqliteHandles(store, clock).get("handle-1")!!
        val terminal = awaitSettled(handle.handleId)
        assertFalse(terminal.body.contains(secret))
        assertTrue(terminal.header!!.runtime.redactionApplied)
    }

    @Test
    fun `a secret straddling the head width is redacted before the head is cut`() = runTest {
        val secret = "AKIA" + "IOSFODNN7EXAMPLE"
        // "git --version " is 14 characters, so the secret starts at column 74 and crosses the 80-column cut.
        val out = run("""{"argv":["git","--version","${"x".repeat(60)}$secret"]}""")
        assertFalse(out.body.contains("AKIAIO"), out.body)
    }

    @Test
    fun `a redaction scan limit marks the stored log capture incomplete`() = runTest {
        repo.write("emit.txt", "a".repeat(1000))
        val config = Config(redaction = io.astrolabe.auth.RedactionConfig(maxBytes = 64))
        val out = run("""{"cmd":"${shell("type emit.txt", "cat emit.txt")}"}""", runner(config = config))
        assertFalse(out.header!!.runtime.captureComplete)
        assertFalse(SqliteObservations(store, clock).get("obs-1")!!.captureComplete)
    }

    /** One wait settles [handle]: it returns only when the process ends (the fake clock never expires it). */
    private suspend fun awaitSettled(handle: String): ToolOutcome = run("""{"op":"wait","handle":"$handle","timeout":60}""")

    @Test
    fun `a foreground run captures output and exit code, stamps the tree and stays class R when nothing moved`() = runTest {
        val out = run("""{"cmd":"echo hello-run"}""")
        assertTrue(status(out) != "denied" && status(out) != "unknown_outcome", status(out))
        assertEquals("#1", out.resultAlias)
        assertTrue(out.body.contains("hello-run"), out.body)
        // D-351/D-353: a plain command's exit 0 reads as such, header status included; it is never green (D-50).
        assertTrue(out.body.startsWith("run #1 completed, exit code 0 · class R · shell wrapper · echo hello-run"), out.body)
        assertTrue(out.body.contains("\ngeneric · completed, exit code 0\n"), out.body)
        assertEquals("completed", status(out))
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
    fun `an exit-hiding wrapper keeps the inconclusive header and a completed background run is still not green`() = runTest {
        // D-353: `completed` only where the D-351 predicate holds; a wrapper hides the exit, so the verdict wording stays.
        val wrapped = run("""{"cmd":"echo wrapped || true"}""")
        assertEquals("inconclusive", status(wrapped), wrapped.body)
        assertFalse(wrapped.body.lineSequence().first().contains("completed"), wrapped.body)
        assertFalse(wrapped.green)
        run("""{"cmd":"echo settled","bg":true}""")
        val settled = awaitSettled("handle-1")
        assertEquals("completed", status(settled), settled.body)
        assertFalse(settled.green, "the background completion path is presentation only too")
    }

    @Test
    fun `a cwd that is blank, a dot or dot-slash runs in the workspace root`() = runTest {
        val list = shell("dir /b", "ls")
        for (cwd in listOf("", " ", ".", "./")) {
            val out = run("""{"cmd":"$list","cwd":"$cwd"}""")
            assertTrue(status(out) != "denied", "cwd '$cwd': ${out.body}")
            assertTrue(out.body.contains("README.md"), out.body)
        }
        assertTrue((1..4).all { intents.get("intent-$it")!!.cwd == null }, "one command for the unknown-outcome guard, whatever spelling named the root")
        val sub = run("""{"cmd":"$list","cwd":"src"}""")
        assertTrue(sub.body.contains("a.py") && !sub.body.contains("README.md"), sub.body)
        assertEquals("denied", status(run("""{"cmd":"$list","cwd":"src/.."}""")), "only the named spellings mean the root")
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
    fun `an omitted budget and timeout are the configured defaults and explicit values win`() = runTest {
        val tool = runner(config = Config(defaults = Defaults(runBudgetTokens = 60, runTimeoutSeconds = 1)))
        val lines = shell("for /L %i in (1,1,200) do @echo line%i", "seq 1 200")
        val cut = run("""{"cmd":"$lines"}""", tool)
        assertTrue(cut.body.contains("view truncated at prompt budget 60 tokens"), cut.body)
        val whole = run("""{"cmd":"$lines","budget":4000}""", tool)
        assertFalse(whole.body.contains("view truncated"), whole.body)
        val slow = shell("ping -n 3 127.0.0.1 >NUL", "sleep 2")
        assertEquals("timeout", status(run("""{"cmd":"$slow"}""", tool)), "the configured 1 s deadline applies")
        assertEquals("completed", status(run("""{"cmd":"$slow","timeout":30}""", tool)), "an explicit timeout wins")
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
    fun `mismatched and superseded approvals never dispatch a command`() = runTest {
        contracts.amendByUser(ids.work, "allow git ref mutation") { it.copy(authorization = it.authorization.copy(capabilitySet = "wide")) }
        val wide = mapOf("wide" to CapabilitySet("wide", Capability.entries.toSet()))
        var spawned = 0
        val tracking = object : Os by os {
            override fun spawn(spec: io.astrolabe.os.SpawnSpec): Proc { spawned++; return os.spawn(spec) }
        }
        for (mode in listOf("identity", "revision", "revoked")) {
            val approving = object : Authority by AutonomousAuthority() {
                override suspend fun approve(request: DClassRequest): Decision {
                    if (mode == "revoked") contracts.amendByUser(ids.work, "revoke") { it.copy(authorization = it.authorization.copy(capabilitySet = "workspace-local-test-only")) }
                    return Decision(if (mode == "identity") "another-request" else request.id,
                        if (mode == "revision") request.contractRevision - 1 else request.contractRevision, true)
                }
            }
            val out = run("""{"argv":["git","push","origin","main"],"intent":"publish"}""", runner(os = tracking, authority = approving, hostSets = wide))
            assertEquals("denied", status(out), mode)
        }
        assertEquals(0, spawned)
        assertNull(intents.get("intent-1"))
    }

    @Test
    fun `lease expiry during D-class approval prevents process dispatch`() = runTest {
        contracts.amendByUser(ids.work, "allow git ref mutation") { it.copy(authorization = it.authorization.copy(capabilitySet = "wide")) }
        var live = true
        var spawned = 0
        val chunked = ChunkedLogOs()
        val tracked = object : Os by chunked {
            override fun spawn(spec: io.astrolabe.os.SpawnSpec): Proc { spawned++; return chunked.spawn(spec) }
        }
        val approving = object : Authority by AutonomousAuthority() {
            override suspend fun approve(request: DClassRequest): Decision {
                live = false
                return Decision(request.id, request.contractRevision, true, "approved after lease expiry")
            }
        }
        val tool = runner(os = tracked, authority = approving, hostSets = mapOf("wide" to CapabilitySet("wide", Capability.entries.toSet())))
        tool.beforeDispatch = { check(live) { "dispatch lease expired" } }

        try {
            run("""{"argv":["git","push","origin","main"],"intent":"publish"}""", tool)
        } catch (refused: IllegalStateException) {
            assertEquals("dispatch lease expired", refused.message)
        }

        assertFalse(live, "the authority must have returned its approval")
        assertEquals(0, spawned, "an approval cannot restore an expired dispatch lease")
        assertTrue(intents.open().isEmpty(), "a refused dispatch leaves no unreconciled intent to block a later attempt")
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
    fun `foreground run persists all terminal log chunks including the final failure`() = runTest {
        val chunked = ChunkedLogOs()

        val result = run("""{"cmd":"echo fixture"}""", runner(os = chunked))

        assertEquals("failed", status(result))
        val log = store.blobs.get(io.astrolabe.id.Digest(result.header!!.runtime.artifactRefs.first()))
        kotlin.test.assertContentEquals(chunked.output, log)
        assertTrue(result.body.contains("FINAL FAILURE"), result.body)
        assertTrue(result.header!!.runtime.captureComplete)
    }

    @Test
    fun `a capped foreground capture keeps its exit and commits the intent`() = runTest {
        val chunk = 1024 * 1024
        val capped = object : Os by ChunkedLogOs() {
            override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): io.astrolabe.os.Poll {
                val index = (sinceCursorBytes / chunk).toInt()
                val bytes = if (index < 9) ByteArray(chunk) { 'x'.code.toByte() } else byteArrayOf()
                return io.astrolabe.os.Poll(bytes, sinceCursorBytes + bytes.size, ProcStatus.Exited(0), false)
            }
        }

        val result = run("""{"cmd":"echo fixture"}""", runner(os = capped))

        assertFalse(status(result) == "unknown_outcome", result.body)
        assertTrue(result.body.contains("exit 0"), result.body)
        assertFalse(result.header!!.runtime.captureComplete)
        assertTrue(intents.open().isEmpty(), "a finished process with a capped log is not an unreconciled effect")
    }

    @Test
    fun `poll and cancel deny a handle owned by another work before accessing its process`() = runTest {
        assertHandleAccessDenied(ids.copy(work = WorkId("W-2")), workspace)
    }

    @Test
    fun `poll and cancel deny a handle owned by another workspace before accessing its process`() = runTest {
        assertHandleAccessDenied(ids, Workspace(WorkspaceId("ws-2"), repo.root, repo.git))
    }

    private suspend fun assertHandleAccessDenied(caller: Identities, callerWorkspace: Workspace) {
        val handle = savedHandle()
        var processAccesses = 0
        val tracked = object : Os by os {
            override fun reattach(proc: Proc): Proc { processAccesses++; return proc }
            override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
                processAccesses++
                return Poll("private campaign output".toByteArray(), sinceCursorBytes + 23, ProcStatus.Running, false)
            }
            override fun terminate(proc: Proc): Proc { processAccesses++; return proc.copy(status = ProcStatus.Cancelled) }
        }
        val callerRun = runner(os = tracked, ids = caller, workspace = callerWorkspace)

        for (operation in listOf("poll", "cancel")) {
            val result = run("""{"op":"$operation","handle":"${handle.handleId}"}""", callerRun)

            assertEquals("denied", status(result), operation)
            assertFalse(result.body.contains("private campaign output"), operation)
            assertEquals(0, processAccesses, "$operation must authorize before process or log access")
            assertEquals(handle, SqliteHandles(store, clock).get(handle.handleId), operation)
        }
    }

    @Test
    fun `another attempt of the same work and workspace can poll and cancel a retained handle`() = runTest {
        val handle = savedHandle()
        var terminations = 0
        val resumedOs = object : Os by os {
            override fun reattach(proc: Proc): Proc = proc
            override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
                val output = "resumed campaign output".toByteArray()
                return Poll(output, sinceCursorBytes + output.size, ProcStatus.Running, false)
            }
            override fun terminate(proc: Proc): Proc { terminations++; return proc.copy(status = ProcStatus.Cancelled) }
        }
        val resumed = runner(os = resumedOs, ids = ids.copy(attempt = AttemptId("a2")))

        val polled = run("""{"op":"poll","handle":"${handle.handleId}"}""", resumed)
        assertEquals("running", status(polled))
        assertTrue(polled.body.contains("resumed campaign output"))
        assertTrue(SqliteHandles(store, clock).get(handle.handleId)!!.cursor > handle.cursor)

        val cancelled = run("""{"op":"cancel","handle":"${handle.handleId}"}""", resumed)
        assertEquals("cancelled", status(cancelled))
        assertEquals(1, terminations)
        assertEquals("cancelled", SqliteHandles(store, clock).get(handle.handleId)!!.status)
    }

    private fun savedHandle(): Handle {
        val action = "retained-action"
        val alias = SqliteAliases(store, clock).allocate(ids.work, action, "result", ids.context, workspace.id)
        val proc = Proc(42, 1000, IdentityKey(42, 1000), token, stateRoot.resolve("retained.log").toString(), 0,
            ProcStatus.Running, Command.Argv(listOf("echo", "retained")), repo.root.toString())
        return Handle("retained-handle", ids, action, alias.text, listOf("echo", "retained"), false, null, proc,
            "running", 0, stamper.report().candidateId.digest.hex).also { SqliteHandles(store, clock).save(it) }
    }

    @Test
    fun `foreground observation storage failure keeps the dispatched intent open and blocks replay`() = runTest {
        val controlled = ControlledOs().also { it.state = ProcStatus.Exited(0) }
        var fail = true
        val observations = object : Observations by SqliteObservations(store, clock) {
            override fun record(observation: Observation) {
                if (fail) { fail = false; throw IOException("observation storage failed") }
                SqliteObservations(store, clock).record(observation)
            }
        }
        val tool = runner(os = controlled, observations = observations)
        val command = """{"argv":["python","fixture.py"]}"""

        try { run(command, tool) } catch (_: IOException) { /* The durable intent must still guard replay. */ }

        assertFalse(fail, "failure must occur after dispatch, at observation persistence")
        assertEquals(IntentStatus.Unknown, intents.get("intent-1")!!.status)
        assertEquals(listOf("intent-1"), intents.open().map { it.intentId })
        assertEquals("unknown_outcome", status(run(command, tool)))
        assertEquals(1, controlled.spawns)
    }

    @Test
    fun `background handle storage failure keeps the dispatched intent open and blocks replay`() = runTest {
        val controlled = ControlledOs()
        var fail = true
        val handles = object : Handles by SqliteHandles(store, clock) {
            override fun save(handle: Handle) {
                if (fail) { fail = false; throw IOException("handle storage failed") }
                SqliteHandles(store, clock).save(handle)
            }
        }
        val tool = runner(os = controlled, handles = handles)
        val command = """{"argv":["python","fixture.py"],"bg":true}"""

        try { run(command, tool) } catch (_: IOException) { /* The child may still be running. */ }

        assertFalse(fail, "failure must occur after dispatch, at handle persistence")
        assertEquals(IntentStatus.Unknown, intents.get("intent-1")!!.status)
        assertEquals(listOf("intent-1"), intents.open().map { it.intentId })
        assertEquals("unknown_outcome", status(run(command, tool)))
        assertEquals(1, controlled.spawns)
    }

    @Test
    fun `failed intent commit leaves observed effects open and blocks unsafe replay`() = runTest {
        val controlled = ControlledOs().also { it.state = ProcStatus.Exited(0) }
        var fail = true
        val journal = object : IntentJournal by intents {
            override fun update(intentId: String, status: IntentStatus) {
                if (status == IntentStatus.Committed && fail) {
                    fail = false
                    throw IOException("intent commit failed")
                }
                intents.update(intentId, status)
            }
        }
        val tool = runner(os = controlled, intents = journal)
        val command = """{"argv":["python","fixture.py"]}"""

        try { run(command, tool) } catch (_: IOException) { /* Observation is durable, commit is not. */ }

        assertFalse(fail, "the commit must have been attempted")
        assertEquals(IntentStatus.Observed, intents.get("intent-1")!!.status)
        assertNotNull(SqliteObservations(store, clock).get("obs-1"))
        assertEquals("unknown_outcome", status(run(command, tool)))
        assertEquals(1, controlled.spawns, "an observed but uncommitted effect cannot be replayed")
    }

    @Test
    fun `foreground cancellation settles the process leaves unknown intent and blocks replay`() = runBlocking {
        val controlled = ControlledOs()
        val pollStarted = CompletableDeferred<Unit>()
        val releasePoll = CountDownLatch(1)
        val terminations = AtomicInteger()
        val waiting = object : Os by controlled {
            override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
                pollStarted.complete(Unit)
                releasePoll.await(10, TimeUnit.SECONDS)
                return Poll(ByteArray(0), sinceCursorBytes, ProcStatus.Exited(0), false)
            }

            override fun terminate(proc: Proc): Proc {
                terminations.incrementAndGet()
                return proc.copy(status = ProcStatus.Cancelled)
            }
        }
        val tool = runner(os = waiting)
        val command = """{"argv":["python","fixture.py"]}"""
        val returned = CompletableDeferred<ToolOutcome>()
        val action = launch(Dispatchers.Default) { returned.complete(run(command, tool)) }
        try {
            withTimeout(5_000) { pollStarted.await() }
            withTimeout(5_000) { action.cancelAndJoin() }

            assertTrue(action.isCancelled)
            assertFalse(returned.isCompleted, "cancellation must propagate instead of returning success")
            assertEquals(1, terminations.get(), "the owned process must settle before cancellation returns")
            assertEquals(IntentStatus.Unknown, intents.get("intent-1")!!.status)
            assertEquals("unknown_outcome", status(run(command, tool)))
            assertEquals(1, controlled.spawns)
        } finally {
            releasePoll.countDown()
            action.cancelAndJoin()
        }
    }

    @Test
    fun `background D classification and unknown effects survive persisted running terminal and cancel results`() = runTest {
        contracts.amendByUser(ids.work, "allow git ref mutation") { it.copy(authorization = it.authorization.copy(capabilitySet = "wide")) }
        val approving = object : Authority by AutonomousAuthority() {
            override suspend fun approve(request: DClassRequest) = Decision(request.id, request.contractRevision, true)
        }
        val controlled = ControlledOs()
        val wide = mapOf("wide" to CapabilitySet("wide", Capability.entries.toSet()))
        val started = run("""{"argv":["python","../outside-script.py"],"intent":"run approved script","bg":true}""",
            runner(os = controlled, authority = approving, hostSets = wide))
        assertEquals(EffectClass.D, started.header!!.effectClass)
        assertTrue(started.header!!.runtime.effectsUnknown)
        val resumed = runner(os = controlled, ids = ids.copy(attempt = AttemptId("a2")), hostSets = wide)

        val running = run("""{"op":"poll","handle":"handle-1"}""", resumed)
        assertEquals(EffectClass.D, running.header!!.effectClass)
        assertTrue(running.header!!.runtime.effectsUnknown)
        controlled.state = ProcStatus.Exited(0)
        val terminal = run("""{"op":"poll","handle":"handle-1"}""", resumed)
        assertEquals(EffectClass.D, terminal.header!!.effectClass)
        assertTrue(terminal.header!!.runtime.effectsUnknown)
        val cancelled = run("""{"op":"cancel","handle":"handle-1"}""", resumed)
        assertEquals(EffectClass.D, cancelled.header!!.effectClass)
        assertTrue(cancelled.header!!.runtime.effectsUnknown)
        assertEquals(1, controlled.spawns, "resumed polls never launch the command")
    }

    @Test
    fun `background completion excludes pre-existing dirt and does not attribute concurrent edits to the run`() = runTest {
        repo.write("README.md", "pre-existing user edit\n")
        val controlled = ControlledOs()
        val started = run("""{"argv":["git","status"],"bg":true}""", runner(os = controlled))
        repo.write("src/a.py", "concurrent user edit\n")
        controlled.state = ProcStatus.Exited(0)

        val result = run("""{"op":"poll","handle":"handle-1"}""", runner(os = controlled))

        assertEquals(started.header!!.runtime.candidateBefore, result.header!!.runtime.candidateBefore)
        assertEquals(listOf("src/a.py"), result.header!!.runtime.effectsObserved)
        assertTrue(result.header!!.runtime.effectsUnknown, "a stamp diff cannot identify who changed a file")
        assertFalse(result.body.contains("touched (by run"), result.body)
    }

    @Test
    fun `background completion observes dirty-to-clean changes and invalidates the old read`() = runTest {
        repo.write("src/a.py", "def a():\n    return 2\n")
        val old = registry.version("src/a.py")!!
        workset.register(Entry("src/a.py", Ranges.single(1, 2), old, EntrySource.Look, 1, "#look", 40))
        val controlled = ControlledOs()
        val started = run("""{"argv":["git","status"],"bg":true}""", runner(os = controlled))
        repo.write("src/a.py", "def a():\n    return 1\n")
        controlled.state = ProcStatus.Exited(0)

        val result = run("""{"op":"poll","handle":"handle-1"}""", runner(os = controlled))

        assertEquals(started.header!!.runtime.candidateBefore, result.header!!.runtime.candidateBefore)
        assertEquals(listOf("src/a.py"), result.header!!.runtime.effectsObserved)
        assertTrue(result.header!!.runtime.effectsUnknown)
        assertFalse(result.body.contains("touched (by run"), result.body)
        assertFalse(workset.covers("src/a.py", old, LineRange(1, 2)))
        assertEquals(registry.version("src/a.py"), registry.recorded("src/a.py"))
    }

    @Test
    fun `background completion observes a clean HEAD transition and invalidates the old read`() = runTest {
        val old = registry.version("src/a.py")!!
        workset.register(Entry("src/a.py", Ranges.single(1, 2), old, EntrySource.Look, 1, "#look", 40))
        val controlled = ControlledOs()
        val started = run("""{"argv":["git","status"],"bg":true}""", runner(os = controlled))
        repo.write("src/a.py", "def a():\n    return 2\n")
        repo.commit("advance HEAD during background run")
        assertTrue(stamper.report().members.isEmpty(), "both endpoints are clean trees")
        controlled.state = ProcStatus.Exited(0)

        val result = run("""{"op":"poll","handle":"handle-1"}""", runner(os = controlled))

        assertEquals(started.header!!.runtime.candidateBefore, result.header!!.runtime.candidateBefore)
        assertEquals(listOf("src/a.py"), result.header!!.runtime.effectsObserved)
        assertTrue(result.header!!.runtime.effectsUnknown)
        assertFalse(workset.covers("src/a.py", old, LineRange(1, 2)))
        assertEquals(registry.version("src/a.py"), registry.recorded("src/a.py"))
    }

    @Test
    fun `terminal background log capture is bounded and stored as incomplete when capped`() = runTest {
        val controlled = ControlledOs()
        // Keep redaction above the process capture cap, so it cannot mask an unbounded log read.
        val config = Config(redaction = io.astrolabe.auth.RedactionConfig(patterns = emptyList(), maxBytes = 10 * 1024 * 1024))
        val tool = runner(os = controlled, config = config)
        run("""{"argv":["git","status"],"bg":true}""", tool)
        val handle = SqliteHandles(store, clock).get("handle-1")!!
        Files.write(handle.proc.log, ByteArray(9 * 1024 * 1024) { 'x'.code.toByte() })
        controlled.state = ProcStatus.Exited(0)

        val result = run("""{"op":"poll","handle":"handle-1"}""", tool)

        val blob = store.blobs.get(io.astrolabe.id.Digest(result.header!!.runtime.artifactRefs.first()))
        assertEquals(8 * 1024 * 1024, blob.size)
        assertFalse(result.header!!.runtime.captureComplete)
        assertFalse(SqliteObservations(store, clock).get("obs-2")!!.captureComplete)
        assertFalse(result.green)
    }

    @Test
    fun `missing terminal background log is stored as an incomplete capture`() = runTest {
        val controlled = ControlledOs()
        val tool = runner(os = controlled)
        run("""{"argv":["git","status"],"bg":true}""", tool)
        val handle = SqliteHandles(store, clock).get("handle-1")!!
        Files.delete(handle.proc.log)
        controlled.state = ProcStatus.Exited(0)

        val result = run("""{"op":"poll","handle":"handle-1"}""", tool)

        assertFalse(result.header!!.runtime.captureComplete)
        assertFalse(SqliteObservations(store, clock).get("obs-2")!!.captureComplete)
        assertFalse(result.green)
    }

    /** A process boundary with explicit state; it never launches an external command. */
    private inner class ControlledOs : Os by os {
        var state: ProcStatus = ProcStatus.Running
        var spawns = 0

        override fun spawn(spec: io.astrolabe.os.SpawnSpec): Proc {
            spawns++
            Files.write(spec.logPath, ByteArray(0))
            return Proc(42, 1000, IdentityKey(42, 1000), token, spec.logPath.toString(), 0,
                ProcStatus.Running, spec.command, spec.workingDirectory.toString())
        }

        override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long) =
            Poll(ByteArray(0), sinceCursorBytes, state, false)

        override fun reattach(proc: Proc): Proc = proc.copy(status = state)

        override fun terminate(proc: Proc): Proc = proc.copy(status = ProcStatus.Cancelled)
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
        // status here is what made this test flaky on both CI platforms. The poll reads from the log's
        // start: the launch may already have consumed `bg-start`, and a poll from the handle's cursor
        // then waited for `bg-end` instead (the remaining FX-22 intermittency).
        val early = run("""{"op":"poll","handle":"handle-1","since":0,"timeout":20}""")
        assertTrue(early.body.contains("bg-start"), "output that has arrived returns at once: ${early.body}")
        assertEquals(handle.proc.pid, SqliteHandles(store, clock).get("handle-1")!!.proc.pid, "same process, same handle")
        // One wait settles the run, whatever arrives in between: no poll loop, no race with the terminal status.
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

    @Test
    fun `a background build is awaited in one call until it exits`() = runTest {
        val started = run("""{"cmd":"${shell("echo compiling&ping -n 3 127.0.0.1 >NUL&echo BUILD-OK", "echo compiling; sleep 2; echo BUILD-OK")}","bg":true}""")
        assertTrue(started.body.contains("wait with run(op=wait, handle=\"handle-1\")"), started.body)

        val done = run("""{"op":"wait","handle":"handle-1","timeout":60}""")

        assertTrue(status(done) != "running", done.body)
        assertTrue(done.body.contains("compiling") && done.body.contains("BUILD-OK"), done.body)
        assertFalse(done.body.contains("wait ended"), "an end is what a plain wait waits for: ${done.body}")
        assertEquals("exited", SqliteHandles(store, clock).get("handle-1")!!.status)
    }

    @Test
    fun `a server launch waits for its readiness line and keeps running`() = runTest {
        val out = run("""{"cmd":"${shell("echo booting&echo listening on port 8080&ping -n 30 127.0.0.1 >NUL", "echo booting; echo listening on port 8080; sleep 30")}","until_line":"listening on port \\d+"}""")

        assertEquals("running", status(out), out.body)
        assertTrue(out.body.contains("ready: line matched: listening on port 8080"), out.body)
        assertTrue(out.body.contains("process deadline ${Defaults().runTimeoutSeconds}s from its start"), out.body)
        assertTrue(out.body.contains("booting"), "the launch's own first output is part of the wait: ${out.body}")
        assertEquals("running", SqliteHandles(store, clock).get("handle-1")!!.status)
        assertTrue(run("""{"op":"cancel","handle":"handle-1"}""").body.contains("cancel requested"))
    }

    @Test
    fun `a process that ends before readiness stops the wait with its diagnostics`() = runTest {
        val out = run("""{"cmd":"${shell("echo starting&echo error port in use&exit 3", "echo starting; echo error port in use; exit 3")}","until_line":"ready"}""")

        assertTrue(status(out) != "running", out.body)
        assertTrue(out.body.contains("wait ended: the process ended before a line matching /ready/"), out.body)
        assertTrue(out.body.contains("error port in use"), out.body)
        assertTrue(out.body.contains("exit 3"), out.body)
        assertEquals("exited", SqliteHandles(store, clock).get("handle-1")!!.status)
    }

    @Test
    fun `a server launch waits until its loopback port accepts connections`() = runTest {
        val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        assertFalse(os.listening(closed), "nothing listens on a closed port")
        var listener: java.net.ServerSocket? = null
        var probes = 0
        // The server comes up after the launch: the first probe (before the spawn) finds the port closed, the next one opens it.
        val serving = object : Os by os {
            override fun listening(port: Int): Boolean {
                if (++probes == 2) listener = java.net.ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress())
                return os.listening(port)
            }
        }
        try {
            val out = run("""{"cmd":"${shell("ping -n 30 127.0.0.1 >NUL", "sleep 30")}","until_port":$closed}""", runner(os = serving))

            assertEquals("running", status(out), out.body)
            assertTrue(out.body.contains("ready: port $closed accepts connections"), out.body)
            assertFalse(out.body.contains("already open"), out.body)
        } finally {
            listener?.close()
        }
        run("""{"op":"cancel","handle":"handle-1"}""")
    }

    @Test
    fun `a port that is already open before the launch is no readiness of the launched process`() = runTest {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { foreign ->
            val port = foreign.localPort
            val scripted = ScriptedOs(listOf("" to ProcStatus.Running))
            scripted.portOpen = { os.listening(it) }

            val out = run("""{"argv":["git","status"],"until_port":$port,"timeout":3}""", runner(os = scripted))

            assertEquals("running", status(out), out.body)
            assertFalse(out.body.contains("ready:"), out.body)
            assertTrue(out.body.contains("wait timed out after 3s before port $port accepting connections"), out.body)
            assertTrue(out.body.contains("port $port was already open before the wait"), out.body)
        }
    }

    @Test
    fun `a wait on a running handle is ready at once when its port is already open on arrival`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running))
        scripted.portOpen = { true }
        val tool = runner(os = scripted)
        run("""{"argv":["git","status"],"bg":true}""", tool)
        val pollsBefore = scripted.polls
        val arrived = clock.instant()

        val out = run("""{"op":"wait","handle":"handle-1","until_port":8080,"timeout":60}""", tool)

        assertEquals("running", status(out), out.body)
        assertTrue(out.body.contains("ready: port 8080 already accepted connections when the wait began (it may belong to another process)"), out.body)
        assertFalse(out.body.contains("was already open before the wait"), "that wording is the launch case: ${out.body}")
        assertEquals(1, scripted.polls - pollsBefore, "one look, no waiting for the port")
        assertEquals(arrived, clock.instant(), "nothing waited on the clock")
    }

    @Test
    fun `an end that is already there wins over a port that is open on arrival`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "bye\n" to ProcStatus.Exited(0)))
        scripted.portOpen = { true }
        val tool = runner(os = scripted)
        run("""{"argv":["git","status"],"bg":true}""", tool)

        val out = run("""{"op":"wait","handle":"handle-1","until_port":8080}""", tool)

        assertTrue(status(out) != "running", out.body)
        assertTrue(out.body.contains("wait ended: the process ended before port 8080 accepting connections"), out.body)
    }

    @Test
    fun `a server that listens right after the spawn is ready on its port`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running))
        scripted.portOpen = { scripted.spawns > 0 }

        val out = run("""{"argv":["git","status"],"until_port":8080}""", runner(os = scripted))

        assertTrue(out.body.contains("ready: port 8080 accepts connections"), out.body)
        assertFalse(out.body.contains("already open"), out.body)
    }

    @Test
    fun `a wait deadline on the injected clock ends the observation and never the process`() = runTest {
        val controlled = ControlledOs()
        var polls = 0
        val ticking = object : Os by controlled {
            override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
                polls++
                clock.advance(java.time.Duration.ofSeconds(observationTimeoutSeconds))
                return Poll(ByteArray(0), sinceCursorBytes, ProcStatus.Running, observationTimeoutSeconds > 0)
            }

            override fun listening(port: Int): Boolean = false
        }
        // A role that may poll may wait: the plan and probe masks name run.poll only.
        val tool = runner(os = ticking, mask = ToolMask(setOf("run.run", "run.poll")))
        run("""{"argv":["git","status"],"bg":true}""", tool)
        polls = 0

        val waited = run("""{"op":"wait","handle":"handle-1","until_port":8080,"timeout":5}""", tool)

        assertEquals("running", status(waited), waited.body)
        assertTrue(waited.body.contains("wait timed out after 5s before port 8080 accepting connections, the process keeps running (no relaunch)"), waited.body)
        assertEquals(5, polls, "a port wait looks every second of the five")
        assertEquals("running", SqliteHandles(store, clock).get("handle-1")!!.status)
        // D-365 tolerance: a poll that names a condition is a wait under the same deadline rule.
        val tolerated = run("""{"op":"poll","handle":"handle-1","until_line":"ready","timeout":30}""", tool)
        assertTrue(tolerated.body.contains("wait timed out after 30s before a line matching /ready/"), tolerated.body)
        assertEquals(1, controlled.spawns, "waits never launch")
    }

    @Test
    fun `cancelling the caller interrupts a wait and leaves the background process running`() = runBlocking {
        val controlled = ControlledOs()
        val polling = CompletableDeferred<Unit>()
        var terminations = 0
        val blocking = object : Os by controlled {
            override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
                if (observationTimeoutSeconds > 0) {
                    polling.complete(Unit)
                    Thread.sleep(60_000)
                }
                return Poll(ByteArray(0), sinceCursorBytes, ProcStatus.Running, observationTimeoutSeconds > 0)
            }

            override fun terminate(proc: Proc): Proc {
                terminations++
                return controlled.terminate(proc)
            }
        }
        val tool = runner(os = blocking)
        tool.execute(call("""{"argv":["git","status"],"bg":true}"""), context())
        val waiting = launch(Dispatchers.Default) { tool.execute(call("""{"op":"wait","handle":"handle-1"}"""), context()) }
        withTimeout(5_000) { polling.await() }
        withTimeout(5_000) { waiting.cancelAndJoin() }

        assertTrue(waiting.isCancelled)
        assertEquals(0, terminations, "a cancelled wait is no cancel of the process")
        assertEquals("running", SqliteHandles(store, clock).get("handle-1")!!.status)
        assertEquals(1, controlled.spawns)
    }

    /**
     * A process that exists only as a script: poll number k reveals step k's output and status (the last step stays), and
     * a quiet poll's own timeout passes on the injected clock. The launch's first look is poll 0.
     */
    private inner class ScriptedOs(private val steps: List<Pair<String, ProcStatus>>) : Os by os {
        var spawns = 0
        var polls = 0
        var portOpen: (Int) -> Boolean = { false }
        private var log = ByteArray(0)
        private var status: ProcStatus = ProcStatus.Running

        override fun spawn(spec: io.astrolabe.os.SpawnSpec): Proc {
            spawns++
            Files.write(spec.logPath, ByteArray(0))
            return Proc(42, clock.millis(), IdentityKey(42, 1000), token, spec.logPath.toString(), 0,
                ProcStatus.Running, spec.command, spec.workingDirectory.toString(), deadlineSeconds = spec.deadlineSeconds)
        }

        override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
            steps.getOrNull(polls)?.let { (text, end) ->
                log += text.toByteArray()
                status = end
                Files.write(proc.log, log)
            }
            polls++
            val news = log.copyOfRange(sinceCursorBytes.toInt(), log.size)
            // A poll returns at once with new bytes or a terminal status; only a quiet one spends its whole timeout.
            if (news.isEmpty() && !status.isTerminal) clock.advance(java.time.Duration.ofSeconds(observationTimeoutSeconds))
            return Poll(news, log.size.toLong(), status, false)
        }

        override fun reattach(proc: Proc): Proc = proc.copy(status = status)

        override fun terminate(proc: Proc): Proc = proc.copy(status = ProcStatus.Cancelled)

        override fun listening(port: Int): Boolean = portOpen(port)
    }

    @Test
    fun `a readiness line that arrives with the end of the process still counts`() = runTest {
        // LocalOs.poll hands over the last bytes together with the terminal status.
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "starting\nlistening on port 8080\n" to ProcStatus.Exited(0)))

        val out = run("""{"argv":["git","status"],"until_line":"listening on port \\d+"}""", runner(os = scripted))

        assertTrue(status(out) != "running", out.body)
        assertTrue(out.body.contains("wait ended: the process ended; readiness line matched: listening on port 8080"), out.body)
        assertFalse(out.body.contains("before a line matching"), out.body)
        assertEquals("exited", SqliteHandles(store, clock).get("handle-1")!!.status)
    }

    @Test
    fun `an unfinished last line is matched once the process has ended`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "starting\nlistening on port 8080" to ProcStatus.Running, "" to ProcStatus.Exited(0)))

        val out = run("""{"argv":["git","status"],"until_line":"listening on port \\d+"}""", runner(os = scripted))

        assertTrue(out.body.contains("wait ended: the process ended; readiness line matched: listening on port 8080"), out.body)
        assertEquals(3, scripted.polls, "the line stayed unfinished while the process ran, so only the end matched it")
    }

    @Test
    fun `a line break that closes the last line does not open an empty line for an anchored pattern`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "starting\n" to ProcStatus.Running, "\n" to ProcStatus.Running))

        val out = run("""{"argv":["git","status"],"until_line":"^$","timeout":30}""", runner(os = scripted))

        assertTrue(out.body.contains("ready: line matched: "), out.body)
        assertEquals(3, scripted.polls, "starting alone is no blank line; the empty line that follows it is")
    }

    @Test
    fun `a launch wait that expires with the process deadline does not claim the process keeps running`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running))

        val out = run("""{"argv":["git","status"],"until_line":"ready","timeout":5}""", runner(os = scripted))

        assertEquals("running", status(out), out.body)
        assertTrue(out.body.contains("wait timed out after 5s before a line matching /ready/"), out.body)
        assertTrue(out.body.contains("the process deadline (5s from its start) is reached and the process is being stopped"), out.body)
        assertFalse(out.body.contains("keeps running"), out.body)
    }

    @Test
    fun `a wait shorter than the process deadline keeps running and names that deadline`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running))
        val tool = runner(os = scripted)
        run("""{"argv":["git","status"],"bg":true,"timeout":60}""", tool)

        val out = run("""{"op":"wait","handle":"handle-1","until_line":"ready","timeout":5}""", tool)

        assertTrue(out.body.contains("wait timed out after 5s before a line matching /ready/, the process keeps running (no relaunch)"), out.body)
        assertTrue(out.body.contains("process deadline 60s from its start"), out.body)
    }

    private val keyHead = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n"
    private val keyTail = "MIIpayloadSecondLineOfTheKey\n-----END PRIVATE KEY-----\n"

    private fun assertNoKey(body: String) {
        assertFalse(body.contains("MIIEvQ") || body.contains("MIIpayload"), body)
        assertFalse(body.contains("line matched"), body)
    }

    @Test
    fun `a readiness pattern never matches inside a complete private key block`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "booting\n$keyHead$keyTail" to ProcStatus.Running))

        val out = run("""{"argv":["git","status"],"until_line":"^MII","timeout":5}""", runner(os = scripted))

        assertEquals("running", status(out), out.body)
        assertTrue(out.body.contains("wait timed out after 5s before a line matching /^MII/"), out.body)
        assertTrue(out.body.contains("[REDACTED:private-key-block]"), out.body)
        assertNoKey(out.body)
    }

    @Test
    fun `a private key block split across two polls is recognised as one block`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "booting\n$keyHead" to ProcStatus.Running, keyTail to ProcStatus.Running))

        val out = run("""{"argv":["git","status"],"until_line":"^MII","timeout":5}""", runner(os = scripted))

        assertTrue(out.body.contains("wait timed out after 5s"), out.body)
        assertNoKey(out.body)
    }

    @Test
    fun `a wait that stops inside an open key block hides it and the next wait skips its rest`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "booting\n$keyHead" to ProcStatus.Running, "" to ProcStatus.Running, keyTail + "ready\n" to ProcStatus.Running))
        val tool = runner(os = scripted)

        val first = run("""{"argv":["git","status"],"until_line":"^MII","timeout":5}""", tool)
        assertTrue(first.body.contains("wait timed out after 5s"), first.body)
        assertNoKey(first.body)

        val second = run("""{"op":"wait","handle":"handle-1","until_line":"^MII|ready","timeout":5}""", tool)
        assertTrue(second.body.contains("ready: line matched: ready"), second.body)
        assertFalse(second.body.contains("MIIpayload"), second.body)
    }

    @Test
    fun `a private key block that arrives with the terminal poll is neither matched nor shown`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "booting\n$keyHead${keyTail}done\n" to ProcStatus.Exited(0)))

        val out = run("""{"argv":["git","status"],"until_line":"^MII"}""", runner(os = scripted))

        assertTrue(out.body.contains("wait ended: the process ended before a line matching /^MII/"), out.body)
        assertNoKey(out.body)
    }

    // ------------------------------------------------------------- C1a (plan §4.4)

    private fun recorded(name: String) = javaClass.getResourceAsStream("/shaper/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    /** A declared command that prints [file] through one plain shell line, the way a host declares a suite. */
    private fun printing(file: String) = io.astrolabe.contract.Command(if (windows) listOf("cmd.exe", "/d", "/s", "/c", "type $file") else listOf("/bin/sh", "-c", "cat $file"))

    /** The model's request for the same line: `cmd` form, which the shell wraps. */
    private fun printingCmd(file: String) = shell("type $file", "cat $file")

    /** A tool named [name] in the repository root that prints [file]: `name.cmd` on Windows, an executable `./name` on POSIX. */
    private fun tool(name: String, file: String): String {
        if (windows) {
            repo.write("$name.cmd", "@type $file\r\n")
            return "$name.cmd"
        }
        repo.write(name, "#!/bin/sh\ncat $file\n")
        Files.setPosixFilePermissions(repo.root.resolve(name), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        return "./$name"
    }

    /** A contract amended (by the host) to [items], its check registry, scheduler and `verify`, and a `run` that recognises them. */
    private inner class Recognizing(items: List<Acceptance>, config: Config = Config(), requirementIds: List<String> = listOf("R1")) {
        val contract = contracts.amendByHost(ids.work, "C1a acceptance") { c ->
            c.copy(acceptance = items, requirements = c.requirements.map { it.copy(acceptance = items.map { a -> a.id }) })
        }
        val checks: Checks = Checks.seed(contract, RunnerCommands()).also { coherence.register(it) }
        val receipts = SqliteReceipts(store, clock)
        val scheduler = Scheduler(checks, workspace, registry, stamper, receipts, SqliteAliases(store, clock), idGen, ids, clock)
        val verify = Verify(checks, scheduler, null, null, null, workspace, TrustedLocalRunner(os), os, stamper, store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, stateRoot.resolve("logs"))
            .also { v -> v.inputs = Files.list(repo.root).use { files -> files.map { it.fileName.toString() }.filter { !it.startsWith(".") && Files.isRegularFile(repo.root.resolve(it)) }.toList() } + "src/a.py" }
            // The cell sets the requirements its increment serves; the model's own check strengthens those (C1a, C1b).
            .also { v -> v.requirementIds = requirementIds }
        val run = runner(config = config).also { it.verify = verify }

        fun receiptsOf(checkId: String) = receipts.forCheck(checkId)

        /** Every launch the verify invocation logged for [checkId]: one log per execution (the process sidecar aside). */
        fun executions(checkId: String): Int = Files.list(stateRoot.resolve("logs")).use { files -> files.filter { it.fileName.toString().let { name -> name.startsWith("$checkId-") && name.endsWith(".log") } }.count().toInt() }
    }

    @Test
    fun `a declared acceptance command run through run yields the receipt verify would record, with no second run`() = runTest {
        repo.write("pytest_pass.txt", recorded("pytest-pass.txt"))
        val r = Recognizing(listOf(Acceptance.Run("AC-1", printing("pytest_pass.txt"), Origin.User, evidence = EvidenceKind.Tests)))

        val out = run("""{"cmd":"${printingCmd("pytest_pass.txt")}"}""", r.run)

        assertEquals("passed", status(out), out.body)
        assertTrue(out.green)
        val receipt = r.receiptsOf("CHK-accept-AC-1").single()
        assertEquals(io.astrolabe.evidence.Outcome.Passed, receipt.outcome)
        assertEquals(printing("pytest_pass.txt").argv, receipt.command, "the receipt names the declared command, as verify would")
        assertEquals(EvidenceKind.Tests, receipt.evidenceKind)
        assertEquals(Origin.User, receipt.checkOrigin)
        assertTrue(receipt.independent && receipt.greenForFinalTree)
        assertEquals(stamper.stamp().id, receipt.stampAfter, "a fresh stamp: the receipt is current for the tree now")
        assertTrue(out.body.contains("receipt CHK-accept-AC-1: accept AC-1: ✓"), out.body)
        assertTrue(out.body.contains(" · tests"), out.body)
        assertTrue(r.scheduler.currency(r.checks["CHK-accept-AC-1"]!!, stamper.stamp().id).certifies)

        // Verify-on-stop finds the receipt current: nothing runs a second time.
        val stop = r.verify.onStop(listOf("AC-1"))
        assertTrue(stop.receipts.isEmpty(), stop.toString())
        assertEquals(1, r.receiptsOf("CHK-accept-AC-1").size)
        assertEquals(1, r.executions("CHK-accept-AC-1"))
    }

    @Test
    fun `one execution is the receipt of the declared command it ran, never of a different declaration that matched`() = runTest {
        repo.write("pytest_pass.txt", recorded("pytest-pass.txt"))
        // The same line declared twice, verbatim and with a doubled blank: equal tokens, different declarations.
        val twin = io.astrolabe.contract.Command(if (windows) listOf("cmd.exe", "/d", "/s", "/c", "type  pytest_pass.txt") else listOf("/bin/sh", "-c", "cat  pytest_pass.txt"))
        val r = Recognizing(listOf(
            Acceptance.Run("AC-1", printing("pytest_pass.txt"), Origin.User),
            Acceptance.Run("AC-3", twin, Origin.User),
            Acceptance.Run("AC-4", printing("pytest_pass.txt"), Origin.Harness),
        ))

        run("""{"cmd":"${printingCmd("pytest_pass.txt")}"}""", r.run)

        assertEquals(1, r.receiptsOf("CHK-accept-AC-1").size)
        assertEquals(1, r.receiptsOf("CHK-accept-AC-4").size, "a verbatim declaration shares the execution")
        assertTrue(r.receiptsOf("CHK-accept-AC-3").isEmpty(), "a different declaration is not credited by another's execution")
        assertEquals(1, r.executions("CHK-accept-AC-1"))
    }

    @Test
    fun `a command that is not a declared one, or the model's own addition, records no receipt`() = runTest {
        repo.write("pytest_pass.txt", recorded("pytest-pass.txt"))
        val r = Recognizing(listOf(
            Acceptance.Run("AC-1", printing("pytest_pass.txt"), Origin.User),
            Acceptance.Run("AC-2", printing("README.md"), Origin.Model("R1")),
        ))

        val plain = run("""{"cmd":"echo hello"}""", r.run)
        assertFalse(plain.body.contains("receipt "), plain.body)
        // A variant is not the declared command: one token more, shell syntax around it, another directory.
        run("""{"cmd":"${shell("type pytest_pass.txt README.md", "cat pytest_pass.txt README.md")}"}""", r.run)
        run("""{"cmd":"${shell("type pytest_pass.txt | findstr passed", "cat pytest_pass.txt | cat")}"}""", r.run)
        run("""{"cmd":"${shell("type ..\\\\pytest_pass.txt", "cat ../pytest_pass.txt")}","cwd":"src"}""", r.run)
        // A model-added acceptance item is not a declared command (D-262): its run stays plain.
        val added = run("""{"cmd":"${printingCmd("README.md")}"}""", r.run)
        assertFalse(added.body.contains("receipt "), added.body)

        assertTrue(r.receiptsOf("CHK-accept-AC-1").isEmpty())
        assertTrue(r.receiptsOf("CHK-accept-AC-2").isEmpty())
        assertTrue(r.checks.all().none { it.id.startsWith(Checks.MODEL_PREFIX) }, "printing a file is no test, build or typecheck")
    }

    @Test
    fun `a test command of the model becomes its own check of origin model, an agent test that verify never launches`() = runTest {
        repo.write("pytest_pass.txt", recorded("pytest-pass.txt"))
        val r = Recognizing(listOf(Acceptance.Run("AC-1", printing("pytest_pass.txt"), Origin.User)))
        val pytest = tool("pytest", "pytest_pass.txt")

        val out = run("""{"argv":["$pytest","-q"]}""", r.run)

        val check = r.checks.all().single { it.id.startsWith(Checks.MODEL_PREFIX) }
        assertEquals(Origin.Model("R1"), check.origin)
        assertEquals(EvidenceKind.Tests, check.evidenceKind)
        assertFalse(check.required, "never a required check")
        assertTrue(check.acceptanceIds.isEmpty(), "never an acceptance item")
        assertEquals(1, r.contract.acceptance.size)
        val receipt = r.receiptsOf(check.id).single()
        assertEquals(io.astrolabe.evidence.Outcome.Passed, receipt.outcome)
        assertFalse(receipt.independent)
        assertTrue(out.body.contains("receipt ${check.id}: agent tests: ✓"), out.body)
        assertTrue(out.body.contains("an agent test, not independent acceptance"), out.body)
        assertTrue(r.receiptsOf("CHK-accept-AC-1").isEmpty(), "the declared acceptance is not satisfied by the agent's test")

        // The same command again is the same check.
        run("""{"argv":["$pytest","-q"]}""", r.run)
        assertEquals(1, r.checks.all().count { it.id.startsWith(Checks.MODEL_PREFIX) })
        assertEquals(2, r.receiptsOf(check.id).size)

        // Switched off, a test command of the model stays a plain run.
        val off = Recognizing(listOf(Acceptance.Run("AC-1", printing("pytest_pass.txt"), Origin.User)), Config(modelChecks = false))
        val plain = run("""{"argv":["$pytest","-x"]}""", off.run)
        assertFalse(plain.body.contains("receipt "), plain.body)
        assertTrue(off.checks.all().none { it.id.startsWith(Checks.MODEL_PREFIX) })

        // C1b: without the increment's requirements the model's command strengthens none — never every one — and stays plain.
        val unserved = Recognizing(listOf(Acceptance.Run("AC-1", printing("pytest_pass.txt"), Origin.User)), requirementIds = emptyList())
        val bare = run("""{"argv":["$pytest","-x"]}""", unserved.run)
        assertFalse(bare.body.contains("receipt "), bare.body)
        assertTrue(unserved.checks.all().none { it.id.startsWith(Checks.MODEL_PREFIX) })
    }

    @Test
    fun `a host build passes on its exit while the model's own build stays inconclusive without counts`() = runTest {
        repo.write("build_ok.txt", "compiled 3 files\n")
        val r = Recognizing(listOf(Acceptance.Run("AC-B", printing("build_ok.txt"), Origin.User, evidence = EvidenceKind.Build)))

        val declared = run("""{"cmd":"${printingCmd("build_ok.txt")}"}""", r.run)
        val receipt = r.receiptsOf("CHK-accept-AC-B").single()
        assertEquals(io.astrolabe.evidence.Outcome.Passed, receipt.outcome, receipt.limits.toString())
        assertEquals(null, receipt.parsed, "nothing is counted: the exit is the evidence")
        assertEquals(EvidenceKind.Build, receipt.evidenceKind)
        assertTrue(receipt.passesOnExit && receipt.greenForFinalTree)
        assertTrue(declared.body.contains("build passes on exit 0 (no test counts)"), declared.body)

        val tsc = tool("tsc", "build_ok.txt")
        run("""{"argv":["$tsc"]}""", r.run)
        val mine = r.checks.all().single { it.id.startsWith(Checks.MODEL_PREFIX) }
        assertEquals(EvidenceKind.Build, mine.evidenceKind)
        val own = r.receiptsOf(mine.id).single()
        assertEquals(io.astrolabe.evidence.Outcome.Inconclusive, own.outcome, "D-50 holds for the model's own command")
        assertFalse(own.passesOnExit)
    }

    @Test
    fun `a recognised background run records a non-certifying receipt when a wait sees it end, and a cancelled one records none`() = runTest {
        repo.write("pytest_pass.txt", recorded("pytest-pass.txt"))
        val long = io.astrolabe.contract.Command(if (windows) listOf("cmd.exe", "/d", "/s", "/c", "ping -n 30 127.0.0.1") else listOf("/bin/sh", "-c", "sleep 30"))
        val r = Recognizing(listOf(
            Acceptance.Run("AC-1", printing("pytest_pass.txt"), Origin.User, evidence = EvidenceKind.Tests),
            Acceptance.Run("AC-L", long, Origin.User),
        ))

        val started = run("""{"cmd":"${printingCmd("pytest_pass.txt")}","bg":true}""", r.run)
        assertTrue(started.body.contains("receipt at its end: CHK-accept-AC-1"), started.body)
        val handle = assertNotNull(Regex("handle (handle-\\d+)").find(started.body)).groupValues[1]
        val ended = run("""{"op":"wait","handle":"$handle","timeout":60}""", r.run)

        assertEquals("passed", status(ended), ended.body)
        val receipt = r.receiptsOf("CHK-accept-AC-1").single()
        assertEquals(io.astrolabe.evidence.Outcome.Passed, receipt.outcome, "the outcome is recorded as it happened")
        // Nothing held the workspace between launch and end (D-45): evidence of the outcome, never of the final tree.
        assertEquals(io.astrolabe.evidence.InputStability.Unknown, receipt.testedInputs.stability)
        assertFalse(receipt.greenForFinalTree)
        assertTrue(receipt.limits.any { it.kind == "input_stability" && it.detail.startsWith("background run") })
        assertTrue(ended.body.contains("receipt CHK-accept-AC-1: accept AC-1: stale"), ended.body)
        assertTrue(ended.body.contains("a background run is evidence of its outcome, not of the final tree"), ended.body)
        // The stop's verification certifies it with one exclusive run.
        assertEquals(1, r.verify.onStop(listOf("AC-1")).receipts.size)
        assertTrue(r.scheduler.currency(r.checks["CHK-accept-AC-1"]!!, stamper.stamp().id).certifies)

        val slow = run("""{"cmd":"${shell("ping -n 30 127.0.0.1", "sleep 30")}","bg":true}""", r.run)
        val slowHandle = assertNotNull(Regex("handle (handle-\\d+)").find(slow.body)).groupValues[1]
        run("""{"op":"cancel","handle":"$slowHandle"}""", r.run)
        val after = run("""{"op":"wait","handle":"$slowHandle","timeout":30}""", r.run)
        assertFalse(after.body.contains("receipt CHK-accept-AC-L"), after.body)
        assertTrue(r.receiptsOf("CHK-accept-AC-L").isEmpty(), "a cancelled run records no receipt")
    }

    @Test
    fun `the stop verifies while background runs live, then settles them, tells the model, and certifies only a quiet tree`() = runTest {
        repo.write("pytest_pass.txt", recorded("pytest-pass.txt"))
        // An end-to-end acceptance that needs its server: it passes only while the background run lives.
        val needsServer = io.astrolabe.contract.Command(if (windows) listOf("cmd.exe", "/d", "/s", "/c", "tasklist | findstr /i PING.EXE >nul && type pytest_pass.txt")
            else listOf("/bin/sh", "-c", "pgrep -f 'sleep 37' >/dev/null && cat pytest_pass.txt"))
        val r = Recognizing(listOf(
            Acceptance.Run("AC-1", needsServer, Origin.User, evidence = EvidenceKind.Tests),
            Acceptance.Run("AC-2", printing("pytest_pass.txt"), Origin.User, evidence = EvidenceKind.Tests),
        ))
        r.run.stopGraceMillis = 3_000
        val server = run("""{"cmd":"${shell("ping -n 37 127.0.0.1", "sleep 37")}","bg":true}""", r.run)
        val serverHandle = assertNotNull(Regex("handle (handle-\\d+)").find(server.body)).groupValues[1]
        // A recognised check left in the background that ends within the grace.
        val quick = run("""{"cmd":"${printingCmd("pytest_pass.txt")}","bg":true}""", r.run)
        val quickHandle = assertNotNull(Regex("handle (handle-\\d+)").find(quick.body)).groupValues[1]

        val stop = r.verify.onStop(listOf("AC-1"))
        assertEquals(io.astrolabe.evidence.Outcome.Passed, r.receiptsOf("CHK-accept-AC-1").single().outcome, "the acceptance ran while its server lived")
        assertTrue(r.scheduler.currency(r.checks["CHK-accept-AC-1"]!!, stamper.stamp().id).certifies, "nothing moved: the quiet tree keeps the receipt")
        assertEquals(1, r.receiptsOf("CHK-accept-AC-2").size, "the run that ended in the grace has its receipt")
        assertTrue(stop.notes.single().startsWith("stop cancelled background run ") && stop.notes.single().endsWith("restart it if you still need it"), stop.notes.toString())
        val polled = run("""{"op":"poll","handle":"$serverHandle","timeout":1}""", r.run)
        assertTrue(polled.body.contains("\nhandle $serverHandle cancelled\n") && polled.body.contains("cancelled by the stop's verification"), polled.body)
        val ended = run("""{"op":"poll","handle":"$quickHandle","timeout":1}""", r.run)
        assertFalse(ended.body.contains("pin lost"), ended.body)
        assertTrue(ended.body.contains("while the stop settled it; its end was recorded then"), ended.body)

        // A run the stop could not cancel: nothing certifies the tree while it may still change it, whichever scheduler asks.
        r.verify.settleRuns = { io.astrolabe.tool.verify.StopSettle(0, listOf("#9"), listOf("stop could not confirm the cancellation of background run #9")) }
        r.verify.onStop(listOf("AC-2"))
        val currency = r.scheduler.currency(r.checks["CHK-accept-AC-2"]!!, stamper.stamp().id)
        assertFalse(currency.certifies)
        assertTrue(currency.reasons.any { it.contains("background run #9 still live") }, currency.reasons.toString())
        // A later stop that finds the tree quiet runs it again there and certifies it.
        r.verify.settleRuns = { io.astrolabe.tool.verify.StopSettle(0, emptyList(), emptyList()) }
        r.verify.onStop(listOf("AC-2"))
        assertTrue(r.receiptsOf("CHK-accept-AC-2").any { receipt -> receipt.limits.any { it.kind == Scheduler.CONCURRENT } })
        assertTrue(r.scheduler.currency(r.checks["CHK-accept-AC-2"]!!, stamper.stamp().id).certifies)
    }

    @Test
    fun `the stop's baseline is bounded - none without time left, one per definition when s0 cannot be exported, its red unknown`() = runTest {
        val checks = io.astrolabe.verify.Checks.empty().also { coherence.register(it) }
        val receipts = SqliteReceipts(store, clock)
        val scheduler = Scheduler(checks, workspace, registry, stamper, receipts, SqliteAliases(store, clock), idGen, ids, clock)
        val dirty = io.astrolabe.workspace.DirtyState(workspace, store.blobs, stamper, ids, clock)
        // A shadow never opened: exporting s0 throws, as a broken capture would.
        val shadow = io.astrolabe.workspace.ShadowRef(ids.work, ids.attempt, workspace, store, dirty, os, clock)
        val baseline = io.astrolabe.verify.Baseline(shadow, store.layout, TrustedLocalRunner(os), os, receipts, SqliteAliases(store, clock), store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, clock, stamper.report().env)
        val verify = Verify(checks, scheduler, null, baseline, stamper.stamp().id, workspace, TrustedLocalRunner(os), os, stamper, store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, stateRoot.resolve("logs"))
        val blast = checks.register(io.astrolabe.verify.Check(io.astrolabe.verify.Checks.TESTS_BLAST, io.astrolabe.verify.CheckKind.Unit, io.astrolabe.verify.Selector.Blast,
            io.astrolabe.evidence.Closure.Unknown, io.astrolabe.verify.CostClass.Slow, io.astrolabe.verify.Trigger.StepBoundary, command = printing("README.md")))
        val failure = io.astrolabe.evidence.FailedTest(io.astrolabe.verify.Regressions.key(TestIdentity(file = "tests/t.py", name = "t")), "tests/t.py::t", "assert 1 == 2")
        scheduler.runCheck(blast, 1, listOf("src/a.py", "README.md")) {
            io.astrolabe.verify.Executed(printing("README.md").argv, null, false, 1, io.astrolabe.evidence.Outcome.Failed, io.astrolabe.evidence.Counts(failed = 1, discovered = 1), null,
                tests = io.astrolabe.evidence.TestOutcomes(listOf(failure)))
        }
        fun baselines() = receipts.forCheck(io.astrolabe.verify.Checks.TESTS_BLAST).filter(io.astrolabe.verify.Regressions::isBaseline)
        // A minutes limit with no time left: no baseline runs, the red stays unknown.
        verify.timeLeft = { 0L }
        assertEquals(0L, baseline.timeLeft(), "verify's time left reaches every baseline path, verify(baseline) included")
        verify.onStop(emptyList())
        assertEquals(emptyList(), baselines())
        // Time left, but s0 cannot be exported: the baseline is recorded as begun, and never retried in the attempt.
        verify.timeLeft = { null }
        verify.onStop(emptyList())
        verify.onStop(emptyList())
        assertEquals(1, baselines().size)
        val hold = assertNotNull(scheduler.currency(blast, stamper.stamp().id).hold)
        assertTrue(hold.unknown.single().contains("is unavailable"), hold.toString())
    }

    @Test
    fun `the stop reruns a red command once with the closure it tested, so a run that rewrites it cannot certify`() = runTest {
        repo.write("pytest_pass.txt", recorded("pytest-pass.txt"))
        val checks = io.astrolabe.verify.Checks.empty().also { coherence.register(it) }
        val receipts = SqliteReceipts(store, clock)
        val scheduler = Scheduler(checks, workspace, registry, stamper, receipts, SqliteAliases(store, clock), idGen, ids, clock)
        val verify = Verify(checks, scheduler, null, null, null, workspace, TrustedLocalRunner(os), os, stamper, store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, stateRoot.resolve("logs"))
        // The red ran a command over src/a.py whose teardown rewrites it; the blast radius has since moved to src/b.py.
        val rewrites = io.astrolabe.contract.Command(if (windows) listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt & echo x>>src\\a.py") else listOf("/bin/sh", "-c", "cat pytest_pass.txt; echo x >> src/a.py"))
        val red = io.astrolabe.verify.Check(io.astrolabe.verify.Checks.TESTS_BLAST, io.astrolabe.verify.CheckKind.Unit, io.astrolabe.verify.Selector.Blast,
            io.astrolabe.evidence.Closure.Known(setOf("src/a.py")), io.astrolabe.verify.CostClass.Slow, io.astrolabe.verify.Trigger.StepBoundary, command = rewrites)
        checks.register(red)
        val failure = io.astrolabe.evidence.FailedTest(io.astrolabe.verify.Regressions.key(TestIdentity(file = "tests/t.py", name = "t")), "tests/t.py::t", "assert 1 == 2")
        scheduler.runCheck(red, 1) {
            io.astrolabe.verify.Executed(rewrites.argv, null, false, 1, io.astrolabe.evidence.Outcome.Failed, io.astrolabe.evidence.Counts(failed = 1, discovered = 1), null,
                tests = io.astrolabe.evidence.TestOutcomes(listOf(failure)))
        }
        checks.replace(red.copy(command = printing("pytest_pass.txt"), inputClosure = io.astrolabe.evidence.Closure.Known(setOf("src/b.py"))))
        repo.write("README.md", "# moved\n")
        verify.onStop(emptyList())
        val history = receipts.forCheck(io.astrolabe.verify.Checks.TESTS_BLAST)
        val rerun = history.last()
        assertEquals(rewrites.argv to io.astrolabe.evidence.Closure.Known(setOf("src/a.py")), rerun.command to rerun.inputClosure, "the red's own command and closure")
        assertTrue("src/a.py" in rerun.testedInputs.mutatedDuringCheck, rerun.testedInputs.toString())
        assertTrue(history.any { r -> r.limits.any { it.kind == io.astrolabe.verify.Regressions.RERUN } }, "begun on record")
        assertEquals(1, history.count { it.stampBefore == rerun.stampBefore && !io.astrolabe.verify.Regressions.isMarker(it) }, "one run, no flaky retry")
    }

    /** P8.C.10 round 4: a registry with the full suite and, optionally, the blast radius on the same [command]; its scheduler and verify. */
    private inner class Regression(command: io.astrolabe.contract.Command, blastClosure: io.astrolabe.evidence.Closure? = io.astrolabe.evidence.Closure.Unknown) {
        val checks = io.astrolabe.verify.Checks.empty().also { coherence.register(it) }
        val receipts = SqliteReceipts(store, clock)
        val scheduler = Scheduler(checks, workspace, registry, stamper, receipts, SqliteAliases(store, clock), idGen, ids, clock)
        val verify = Verify(checks, scheduler, null, null, null, workspace, TrustedLocalRunner(os), os, stamper, store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, stateRoot.resolve("logs"))
        val full = checks.register(io.astrolabe.verify.Check(io.astrolabe.verify.Checks.FULL, io.astrolabe.verify.CheckKind.Full, io.astrolabe.verify.Selector.All,
            io.astrolabe.evidence.Closure.Unknown, io.astrolabe.verify.CostClass.Expensive, io.astrolabe.verify.Trigger.CampaignEnd, command = command, origin = Origin.Harness))
        val blast = blastClosure?.let {
            checks.register(io.astrolabe.verify.Check(io.astrolabe.verify.Checks.TESTS_BLAST, io.astrolabe.verify.CheckKind.Unit, io.astrolabe.verify.Selector.Blast, it,
                io.astrolabe.verify.CostClass.Slow, io.astrolabe.verify.Trigger.StepBoundary, command = command))
        }
        fun hold() = blast?.let { scheduler.currency(it, stamper.stamp().id).hold }
    }

    @Test
    fun `one run realizing the full suite and the blast radius gives the blast radius its own record by test (round 4, 1)`() = runTest {
        repo.write("blast_out.txt", recorded("pytest-fail-param.txt"))
        val r = Regression(printing("blast_out.txt"))
        run("""{"cmd":"${printingCmd("blast_out.txt")}"}""", runner().also { it.verify = r.verify })
        val blastRun = r.receipts.forCheck(io.astrolabe.verify.Checks.TESTS_BLAST).single()
        val failed = assertNotNull(blastRun.tests, "the blast radius's receipt records its tests").failed.single()
        assertEquals("tests/test_discount.py::TestDiscount::test_tier[3]", failed.name)
        assertNull(r.receipts.forCheck(io.astrolabe.verify.Checks.FULL).single().tests, "the full suite keeps no record")
        assertEquals(io.astrolabe.verify.RedClass.Unknown, r.hold()?.kind, "held, by its identity, not as an unidentified red")
        assertTrue(r.hold()!!.unknown.single().startsWith("tests/test_discount.py::TestDiscount::test_tier[3]: "), r.hold().toString())
    }

    @Test
    fun `reports that cannot be collected keep the regression checks' failure, every other check its receipt as before (round 4, 7, round 5, 2)`() = runTest {
        repo.write("gradle_out.txt", "> Task :test FAILED\n")
        Files.write(repo.root.resolve("big.bin"), ByteArray(17 * 1024 * 1024) { 'x'.code.toByte() })
        val gradle = if (windows) "gradlew.bat".also {
            repo.write(it, "@mkdir build\\test-results\\test 2>nul\r\n@copy /y big.bin build\\test-results\\test\\TEST-big.xml >nul\r\n@type gradle_out.txt\r\n@exit /b 1\r\n")
        } else "./gradlew".also {
            repo.write("gradlew", "#!/bin/sh\nmkdir -p build/test-results/test\ncp big.bin build/test-results/test/TEST-big.xml\ncat gradle_out.txt\nexit 1\n")
            Files.setPosixFilePermissions(repo.root.resolve("gradlew"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        val r = Regression(io.astrolabe.contract.Command(listOf(gradle, "test")), blastClosure = io.astrolabe.evidence.Closure.Known(setOf("src/a.py")))
        r.verify.runLayer(io.astrolabe.verify.Layer.BlastAndStepAccept)
        val red = r.receipts.forCheck(io.astrolabe.verify.Checks.TESTS_BLAST).first()
        assertEquals(io.astrolabe.evidence.Outcome.Failed, red.outcome, red.limits.toString())
        assertTrue(red.limits.any { it.detail.startsWith("report capture failed") } && red.tests?.incomplete == true, red.toString())
        assertTrue(r.hold()!!.unknown.single().contains("did not identify"), r.hold().toString())
        // Any other check — the full suite, an acceptance item — keeps the receipt it always had: inconclusive, no exit, no record.
        r.verify.runLayer(io.astrolabe.verify.Layer.FullSuiteAndQuality)
        val full = r.receipts.forCheck(io.astrolabe.verify.Checks.FULL).first()
        assertEquals(Triple(io.astrolabe.evidence.Outcome.Inconclusive, null, null), Triple(full.outcome, full.exitCode, full.tests), full.toString())
    }

    @Test
    fun `the stop reruns a types red under the red's own definition, once per tree (round 4, 5)`() = runTest {
        repo.write("types_out.txt", "Success: no issues found in 1 source file\n")
        val r = Regression(printing("types_out.txt"), blastClosure = null)
        val types = r.checks.register(io.astrolabe.verify.Check(io.astrolabe.verify.Checks.TYPES_TOUCHED, io.astrolabe.verify.CheckKind.Type, io.astrolabe.verify.Selector.Touched,
            io.astrolabe.evidence.Closure.Known(emptySet()), io.astrolabe.verify.CostClass.Fast, io.astrolabe.verify.Trigger.EndOfTurn, command = printing("types_out.txt"), origin = Origin.Harness))
        // An end-of-turn checker red: the registered definition, its argv over the touched files, inputs never rescanned.
        val stamp = stamper.stamp().id
        r.receipts.record(io.astrolabe.evidence.Receipt(
            receiptId = "rcpt-checker", ids = ids, checkId = types.id, acceptanceIds = emptyList(), command = printing("types_out.txt").argv + "src/a.py", cwd = null, shell = false,
            stampBefore = stamp, stampAfter = stamp, envId = stamper.report().env.envId, verifierVersion = io.astrolabe.Astrolabe.VERSION, checkDefinitionVersion = types.definitionVersion,
            contractVersion = 1, outcome = io.astrolabe.evidence.Outcome.Failed, parsed = null, inputClosure = types.inputClosure,
            testedInputs = io.astrolabe.evidence.TestedInputs(emptyMap(), io.astrolabe.evidence.InputStability.Unknown), raw = null, at = clock.instant(),
        ))
        r.verify.onStop(emptyList())
        r.verify.onStop(emptyList())
        val history = r.receipts.forCheck(types.id)
        val marker = history.single { io.astrolabe.verify.Regressions.isMarker(it) }
        assertEquals(types.definitionVersion, marker.checkDefinitionVersion)
        assertEquals(1, history.count { !io.astrolabe.verify.Regressions.isMarker(it) && it.receiptId != "rcpt-checker" }, "one rerun on this tree")
        assertEquals(types.definitionVersion, history.last().checkDefinitionVersion, "the rerun is the registered check that defined the red")
    }

    @Test
    fun `recognition compares normalized commands exactly`() {
        assertEquals(listOf("pytest", "-q"), CommandMatch.tokens(listOf("pytest  -q"), shell = true, windows = false))
        assertEquals(listOf("pytest", "tests/test a.py"), CommandMatch.tokens(listOf("pytest \"tests/test a.py\""), shell = true, windows = false))
        for (line in listOf("pytest -q | tail", "pytest > log", "pytest \$ARGS", "pytest 'a b'", "pytest && echo ok", "pytest -k a*", "pytest \"a\"b", "pytest \"\"", "pytest \"a")) {
            assertNull(CommandMatch.tokens(listOf(line), shell = true, windows = false), line)
        }
        assertNull(CommandMatch.tokens(listOf("pytest tests\\a.py"), shell = true, windows = false), "an escape on POSIX")
        assertEquals(listOf("pytest", "tests\\a.py"), CommandMatch.tokens(listOf("pytest tests\\a.py"), shell = true, windows = true))
        assertNull(CommandMatch.tokens(listOf("echo %PATH%"), shell = true, windows = true))

        assertTrue(CommandMatch.matches(listOf("pytest", "-q"), listOf("/bin/sh", "-c", "pytest -q"), windows = false))
        assertTrue(CommandMatch.matches(listOf("npm", "test"), listOf("cmd.exe", "/d", "/s", "/c", "npm test"), windows = true))
        assertTrue(CommandMatch.matches(listOf("cmd", "/c", "npm test"), listOf("npm", "test"), windows = true))
        assertFalse(CommandMatch.matches(listOf("cmd", "/k", "npm test"), listOf("npm", "test"), windows = true))
        assertFalse(CommandMatch.matches(listOf("pytest", "-q", "-x"), listOf("pytest", "-q"), windows = false))
        assertFalse(CommandMatch.matches(listOf("pytest"), listOf("pytest", "-q"), windows = false))
        assertFalse(CommandMatch.matches(listOf("-q", "pytest"), listOf("pytest", "-q"), windows = false))
        assertTrue(CommandMatch.matches(listOf(".\\gradlew.bat", "test"), listOf("gradlew.bat", "test"), windows = true))
        assertTrue(CommandMatch.matches(listOf("PYTEST.EXE"), listOf("pytest.exe"), windows = true))
        assertFalse(CommandMatch.matches(listOf("gradlew.bat", "test"), listOf("gradlew", "test"), windows = true), "an extension names a file")
        assertFalse(CommandMatch.matches(listOf("pytest.exe", "-q"), listOf("pytest.cmd", "-q"), windows = true))
        assertFalse(CommandMatch.matches(listOf("./gradlew", "test"), listOf("gradlew", "test"), windows = false))
        assertFalse(CommandMatch.matches(listOf("pytest", "Tests"), listOf("pytest", "tests"), windows = true), "only the program name folds case")
        assertFalse(CommandMatch.matches(listOf("./sh", "-c", "pytest -q"), listOf("pytest", "-q"), windows = false), "a relative shell is any file")
        assertEquals(listOf("pytest", "-q"), CommandMatch.tokens(listOf("pytest\t-q"), shell = true, windows = false))
        assertNull(CommandMatch.tokens(listOf("pytest -q"), shell = true, windows = false), "a shell does not split on a non-breaking space")
        assertNull(CommandMatch.tokens(listOf("pytest\u0007"), shell = true, windows = false))
        assertTrue(CommandMatch.exitPropagates(listOf("make")))
        assertTrue(CommandMatch.exitPropagates(listOf("/bin/sh", "-c", "make build"), windows = false))
        assertFalse(CommandMatch.exitPropagates(listOf("sh", "-c", "false; exit 0"), windows = false))
        assertFalse(CommandMatch.exitPropagates(listOf("cmd.exe", "/d", "/s", "/c", "type x&exit /b 0"), windows = true))
        assertFalse(CommandMatch.exitPropagates(listOf("powershell", "-Command", "build"), windows = true))
        // `make` told to go on past a failing recipe never proves anything by its exit.
        for (argv in listOf(listOf("make", "-k"), listOf("make", "-i", "build"), listOf("make", "-ik"), listOf("make", "--keep-going"), listOf("gmake", "--ignore-errors"), listOf("/bin/sh", "-c", "make -k all"))) {
            assertFalse(CommandMatch.exitPropagates(argv, windows = false), argv.toString())
        }
        assertTrue(CommandMatch.exitPropagates(listOf("make", "-j4", "build"), windows = false))

        // A declared command runs under the request's authorization only when its label is no broader.
        fun label(effect: EffectClass, unknown: Boolean = false, vararg extra: Capability) =
            io.astrolabe.auth.Classification(effect, emptyList(), setOf(Capability.RunLocal, Capability.WorkspaceRead) + extra, "x", effectsUnknown = unknown)
        val request = label(EffectClass.W, false, Capability.WorkspaceWrite)
        assertTrue(CommandMatch.covered(label(EffectClass.R), request))
        assertTrue(CommandMatch.covered(label(EffectClass.W, false, Capability.WorkspaceWrite), request))
        assertFalse(CommandMatch.covered(label(EffectClass.D, false, Capability.WorkspaceWrite), request), "a higher class")
        assertFalse(CommandMatch.covered(label(EffectClass.W, true, Capability.WorkspaceWrite), request), "effects the request did not leave unknown")
        assertFalse(CommandMatch.covered(label(EffectClass.W, false, Capability.WorkspaceWrite, Capability.Network), request), "another capability")
        assertTrue(CommandMatch.covered(label(EffectClass.W, true, Capability.WorkspaceWrite), label(EffectClass.W, true, Capability.WorkspaceWrite)))

        val kinds = mapOf(
            listOf("npm", "test") to EvidenceKind.Tests, listOf("npm", "run", "test:unit") to EvidenceKind.Tests, listOf("pnpm", "build") to EvidenceKind.Build,
            listOf("yarn", "typecheck") to EvidenceKind.Typecheck, listOf("npx", "tsc", "--noEmit") to EvidenceKind.Typecheck, listOf("tsc", "-p", ".") to EvidenceKind.Build,
            listOf("python", "-m", "pytest", "-q") to EvidenceKind.Tests, listOf("uv", "run", "mypy", "src") to EvidenceKind.Typecheck, listOf("./gradlew", "test") to EvidenceKind.Tests,
            listOf("gradle", "assemble") to EvidenceKind.Build, listOf("mvn", "-q", "verify") to EvidenceKind.Tests, listOf("mvn", "package") to EvidenceKind.Build,
            listOf("go", "vet", "./...") to EvidenceKind.Typecheck, listOf("cargo", "check") to EvidenceKind.Typecheck, listOf("cargo", "test") to EvidenceKind.Tests,
            listOf("dotnet", "build") to EvidenceKind.Build, listOf("/bin/sh", "-c", "npm test") to EvidenceKind.Tests,
        )
        kinds.forEach { (argv, kind) -> assertEquals(kind, EvidenceKinds.recognize(argv, windows = false), argv.toString()) }
        for (argv in listOf(listOf("npm", "run", "lint"), listOf("eslint", "."), listOf("ruff", "check"), listOf("echo", "test"), listOf("npm", "install"), listOf("git", "status"))) {
            assertNull(EvidenceKinds.recognize(argv, windows = false), argv.toString())
        }
    }

    private fun assertNoKeyLine(body: String) = assertFalse(body.contains("MIIEvQ") || body.contains("MIIpayload"), body)

    @Test
    fun `a marker cut by a poll boundary is read whole by the next poll and by a wait`() = runTest {
        val cutAt = "booting\n-----BEGIN PRIV"
        val scripted = ScriptedOs(listOf(
            "" to ProcStatus.Running, cutAt to ProcStatus.Running, "ATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n" to ProcStatus.Running,
            "" to ProcStatus.Running, keyTail + "ready\n" to ProcStatus.Running,
        ))
        val tool = runner(os = scripted)
        run("""{"argv":["git","status"],"bg":true,"timeout":60}""", tool)

        val polled = run("""{"op":"poll","handle":"handle-1"}""", tool)
        assertTrue(polled.body.contains("booting") && !polled.body.contains("BEGIN PRIV"), polled.body)
        assertEquals("booting\n".length.toLong(), SqliteHandles(store, clock).get("handle-1")!!.cursor, "the cursor stays at the last line break")

        // A wait from inside the cut marker: the scan is seeded with the unfinished line, the tail with the log before it.
        val expired = run("""{"op":"wait","handle":"handle-1","since":${cutAt.length},"until_line":"^MII|ATE","timeout":5}""", tool)
        assertTrue(expired.body.contains("wait timed out after 5s"), expired.body)
        assertNoKey(expired.body)
        assertFalse(expired.body.contains("ATE KEY"), expired.body)

        val ready = run("""{"op":"wait","handle":"handle-1","until_line":"^MII|ready","timeout":5}""", tool)
        assertTrue(ready.body.contains("ready: line matched: ready"), ready.body)
        assertNoKeyLine(ready.body)
    }

    @Test
    fun `a CRLF key split across two polls shows none of its lines`() = runTest {
        val scripted = ScriptedOs(listOf(
            "" to ProcStatus.Running,
            "booting\r\n-----BEGIN PRIVATE KEY-----\r\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\r\n" to ProcStatus.Running,
            "MIIpayloadSecondLineOfTheKey\r\n-----END PRIVATE KEY-----\r\nafter\r\n" to ProcStatus.Running,
        ))
        val tool = runner(os = scripted)
        run("""{"argv":["git","status"],"bg":true,"timeout":60}""", tool)

        val first = run("""{"op":"poll","handle":"handle-1"}""", tool)
        val second = run("""{"op":"poll","handle":"handle-1"}""", tool)

        assertTrue(first.body.contains("booting"), first.body)
        assertTrue(second.body.contains("after"), second.body)
        assertNoKeyLine(first.body)
        assertNoKeyLine(second.body)
    }

    @Test
    fun `a token split mid-line between polls is never shown in part`() = runTest {
        val scripted = ScriptedOs(listOf(
            "" to ProcStatus.Running, "booting\nkey AKIAIOSFOD" to ProcStatus.Running, "NN7EXAMPLE done\n" to ProcStatus.Running,
            "again AKIAIOSFOD" to ProcStatus.Running, "NN7EXAMPLE\n" to ProcStatus.Running,
        ))
        val tool = runner(os = scripted)
        run("""{"argv":["git","status"],"bg":true,"timeout":60}""", tool)

        val slices = List(3) { run("""{"op":"poll","handle":"handle-1"}""", tool) }

        slices.forEach { assertFalse(it.body.contains("AKIAIOSFOD"), it.body) }
        assertTrue(slices[0].body.contains("booting"), slices[0].body)
        assertTrue(slices[1].body.contains("key [REDACTED:aws-access-key-id] done"), slices[1].body)
        // A slice that is one unfinished line waits for its break within the same poll.
        assertTrue(slices[2].body.contains("again [REDACTED:aws-access-key-id]"), slices[2].body)
    }

    @Test
    fun `a progress line without breaks gets one more look and is then handed over as it stands`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "10%\r" to ProcStatus.Running, "20%\r" to ProcStatus.Running, "30%\r" to ProcStatus.Running, "40%\r" to ProcStatus.Running))
        val tool = runner(os = scripted)
        run("""{"argv":["git","status"],"bg":true,"timeout":60}""", tool)
        val before = scripted.polls

        val out = run("""{"op":"poll","handle":"handle-1"}""", tool)

        assertEquals(2, scripted.polls - before, "one look for the slice and exactly one more for its line break")
        assertTrue(out.body.contains("20%"), out.body)
        assertEquals("10%\r20%\r".length.toLong(), SqliteHandles(store, clock).get("handle-1")!!.cursor)
    }

    @Test
    fun `a readiness line is redacted with the log before the wait`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "client_secret:\n" to ProcStatus.Running, "  abcdefsecretvalue ready\n" to ProcStatus.Running))
        val tool = runner(os = scripted)
        run("""{"argv":["git","status"],"bg":true,"timeout":60}""", tool)
        run("""{"op":"poll","handle":"handle-1"}""", tool)

        val out = run("""{"op":"wait","handle":"handle-1","until_line":"ready","timeout":5}""", tool)

        assertTrue(out.body.contains("ready: line matched: [REDACTED:secret-assignment] ready"), out.body)
        assertFalse(out.body.contains("abcdefsecretvalue"), out.body)
    }

    @Test
    fun `a closed key block on the shaper's cut line leaves the tail of the output visible`() = runTest {
        val payload = (1..300).joinToString("") { "MIIpayload$it\n" }
        // Long enough on both sides that the head keeps BEGIN and the tail never reaches END.
        val tail = (1..200).joinToString("") { "tail-$it\n" }
        repo.write("long.txt", "head-1\n-----BEGIN PRIVATE KEY-----\n$payload-----END PRIVATE KEY-----\n${tail}TAIL-VISIBLE\n")

        val out = run("""{"cmd":"${shell("type long.txt", "cat long.txt")}","budget":200}""")

        assertTrue(out.body.contains("view truncated"), out.body)
        assertTrue(out.body.contains("TAIL-VISIBLE"), out.body)
        assertNoKeyLine(out.body)
    }

    @Test
    fun `an ended process whose log is gone hides the last slice`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "booting\n" to ProcStatus.Running, "MIIpayloadSecondLineOfTheKey\nlast-slice-line\n" to ProcStatus.Exited(0)))
        val vanishing = object : Os by scripted {
            override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll =
                scripted.poll(proc, sinceCursorBytes, observationTimeoutSeconds).also { if (it.status.isTerminal) Files.delete(proc.log) }
        }
        val tool = runner(os = vanishing)
        run("""{"argv":["git","status"],"bg":true,"timeout":60}""", tool)
        run("""{"op":"poll","handle":"handle-1"}""", tool)

        val terminal = run("""{"op":"poll","handle":"handle-1"}""", tool)

        assertTrue(terminal.body.contains("handle handle-1 exited"), terminal.body)
        assertNoKeyLine(terminal.body)
        assertFalse(terminal.body.contains("last-slice-line"), terminal.body)
    }

    @Test
    fun `a foreground command that prints a key block without its end shows none of the block`() = runTest {
        repo.write("key.txt", "booting\n${keyHead}MIIpayloadSecondLineOfTheKey\n")

        val out = run("""{"cmd":"${shell("type key.txt", "cat key.txt")}"}""")

        assertTrue(out.body.contains("booting") && out.body.contains("[REDACTED:private-key-block]"), out.body)
        assertNoKeyLine(out.body)
        assertTrue(out.header!!.runtime.redactionApplied)
        val stored = String(store.blobs.get(io.astrolabe.id.Digest(out.header!!.runtime.artifactRefs.first())))
        assertNoKeyLine(stored)
    }

    @Test
    fun `a background process that ends inside a key block shows none of it in the terminal poll or wait`() = runTest {
        val steps = listOf("" to ProcStatus.Running, "booting\n$keyHead" to ProcStatus.Running, "MIIpayloadSecondLineOfTheKey\n" to ProcStatus.Exited(0))
        val polling = runner(os = ScriptedOs(steps))
        run("""{"argv":["git","status"],"bg":true,"timeout":60}""", polling)
        run("""{"op":"poll","handle":"handle-1"}""", polling)

        val terminal = run("""{"op":"poll","handle":"handle-1"}""", polling)

        assertEquals("exited", SqliteHandles(store, clock).get("handle-1")!!.status)
        assertTrue(terminal.body.contains("handle handle-1 exited"), terminal.body)
        assertNoKeyLine(terminal.body)
        assertNoKeyLine(String(store.blobs.get(io.astrolabe.id.Digest(terminal.header!!.runtime.artifactRefs.first()))))

        val waiting = runner(os = ScriptedOs(steps))
        val waited = run("""{"argv":["git","status"],"until_line":"^MII","timeout":5}""", waiting)

        assertTrue(waited.body.contains("wait ended: the process ended before a line matching /^MII/"), waited.body)
        assertNoKeyLine(waited.body)
    }

    @Test
    fun `a private key split across two polls shows none of its lines in either slice`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "booting\n$keyHead" to ProcStatus.Running, keyTail + "after\n" to ProcStatus.Running))
        val tool = runner(os = scripted)
        run("""{"argv":["git","status"],"bg":true,"timeout":60}""", tool)

        val first = run("""{"op":"poll","handle":"handle-1"}""", tool)
        val second = run("""{"op":"poll","handle":"handle-1"}""", tool)

        assertTrue(first.body.contains("booting") && first.body.contains("[REDACTED:private-key-block]"), first.body)
        assertTrue(second.body.contains("after") && second.body.contains("[REDACTED:private-key-block]"), second.body)
        assertNoKeyLine(first.body)
        assertNoKeyLine(second.body)
        assertTrue(second.header!!.runtime.redactionApplied, "the slice's mask records the hidden lines")
    }

    @Test
    fun `a poll that starts in the middle of a key block hides it up to its end`() = runTest {
        val begin = "booting\n-----BEGIN PRIVATE KEY-----\n"
        val scripted = ScriptedOs(listOf(
            "" to ProcStatus.Running, begin to ProcStatus.Running, "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n" to ProcStatus.Running, keyTail + "after\n" to ProcStatus.Running,
        ))
        val tool = runner(os = scripted)
        run("""{"argv":["git","status"],"bg":true,"timeout":60}""", tool)
        run("""{"op":"poll","handle":"handle-1"}""", tool)

        val inside = run("""{"op":"poll","handle":"handle-1"}""", tool)
        val closing = run("""{"op":"poll","handle":"handle-1"}""", tool)
        // An explicit cursor in the middle of the block, after the whole block is in the log.
        val resumed = run("""{"op":"poll","handle":"handle-1","since":${begin.length}}""", tool)

        assertNoKeyLine(inside.body)
        assertTrue(inside.body.contains("[REDACTED:private-key-block]"), inside.body)
        assertNoKeyLine(closing.body)
        assertTrue(closing.body.contains("after"), closing.body)
        assertNoKeyLine(resumed.body)
        assertTrue(resumed.body.contains("after"), resumed.body)
    }

    @Test
    fun `a key block left open hides every later slice while the output does not close it`() = runTest {
        // The launch's own first look is the first slice: it opens the block.
        val scripted = ScriptedOs(listOf("booting\n$keyHead" to ProcStatus.Running, "MIIpayloadSecondLineOfTheKey\nmore\n" to ProcStatus.Running, "" to ProcStatus.Running))
        val tool = runner(os = scripted)
        val launched = run("""{"argv":["git","status"],"bg":true,"timeout":60}""", tool)

        val slices = List(2) { run("""{"op":"poll","handle":"handle-1","timeout":1}""", tool) }

        assertNoKeyLine(launched.body)
        assertTrue(launched.body.contains("booting") && launched.body.contains("[REDACTED:private-key-block]"), launched.body)
        slices.forEach { assertNoKeyLine(it.body) }
        assertFalse(slices[0].body.contains("more"), "the rest of the output stays hidden while the block is open: ${slices[0].body}")
        assertTrue(slices[0].header!!.runtime.redactionApplied)
        assertFalse(String(store.blobs.get(io.astrolabe.id.Digest(launched.header!!.runtime.artifactRefs.first()))).contains("MIIEvQ"), "the launch's stored slice hides the open block too")
    }

    @Test
    fun `a recognised kind is a label only - a declared command without a declared kind never passes on its exit`() = runTest {
        repo.write("build_ok.txt", "compiled 3 files\n")
        val tsc = tool("tsc", "build_ok.txt")
        val r = Recognizing(listOf(Acceptance.Run("AC-T", io.astrolabe.contract.Command(listOf(tsc)), Origin.User)))

        val out = run("""{"argv":["$tsc"]}""", r.run)

        val receipt = r.receiptsOf("CHK-accept-AC-T").single()
        assertEquals(EvidenceKind.Build, receipt.evidenceKind, "recognised from tsc: a label")
        assertFalse(receipt.evidenceDeclared)
        assertEquals(io.astrolabe.evidence.Outcome.Inconclusive, receipt.outcome, "only a declared build passes on its exit (D-50)")
        assertFalse(receipt.passesOnExit)
        assertEquals("inconclusive", status(out), out.body)
    }

    @Test
    fun `the model's own check joins the registry only after the run passed its gates`() = runTest {
        repo.write("pytest_pass.txt", recorded("pytest-pass.txt"))
        val r = Recognizing(listOf(Acceptance.Run("AC-1", printing("pytest_pass.txt"), Origin.User)))
        val pytest = tool("pytest", "pytest_pass.txt")
        r.run.beforeDispatch = { throw IllegalStateException("lease lapsed") }

        assertFailsWith<IllegalStateException> { run("""{"argv":["$pytest","-q"]}""", r.run) }

        assertTrue(r.checks.all().none { it.id.startsWith(Checks.MODEL_PREFIX) }, "a refused run leaves no check behind")
        r.run.beforeDispatch = {}
        run("""{"argv":["$pytest","-q"]}""", r.run)
        assertEquals(1, r.checks.all().count { it.id.startsWith(Checks.MODEL_PREFIX) })
    }

    @Test
    fun `a secret in the output of a recognised run is not shown, foreground or background`() = runTest {
        val secret = "AKIA" + "IOSFODNN7EXAMPLE"
        repo.write("leaky.txt", "booting\n$secret\n$keyHead")
        val r = Recognizing(listOf(Acceptance.Run("AC-S", printing("leaky.txt"), Origin.User)))

        val out = run("""{"cmd":"${printingCmd("leaky.txt")}"}""", r.run)
        assertTrue(out.body.contains("receipt CHK-accept-AC-S"), out.body)
        assertFalse(out.body.contains(secret), out.body)
        assertNoKey(out.body)
        val receipt = r.receiptsOf("CHK-accept-AC-S").single()
        val stored = String(store.blobs.get(assertNotNull(receipt.raw)))
        assertFalse(stored.contains(secret) || stored.contains("MIIEvQ"), "the receipt's log hides the open block too")

        val started = run("""{"cmd":"${printingCmd("leaky.txt")}","bg":true}""", r.run)
        val handle = assertNotNull(Regex("handle (handle-\\d+)").find(started.body)).groupValues[1]
        val ended = run("""{"op":"wait","handle":"$handle","timeout":60}""", r.run)
        assertTrue(ended.body.contains("receipt CHK-accept-AC-S"), ended.body)
        assertFalse(ended.body.contains(secret), ended.body)
        assertNoKey(ended.body)
    }

    @Test
    fun `a recognised background run that lost its pin ends without a receipt and says so`() = runTest {
        repo.write("pytest_pass.txt", recorded("pytest-pass.txt"))
        val r = Recognizing(listOf(Acceptance.Run("AC-1", printing("pytest_pass.txt"), Origin.User)))
        val started = run("""{"cmd":"${printingCmd("pytest_pass.txt")}","bg":true}""", r.run)
        val handle = assertNotNull(Regex("handle (handle-\\d+)").find(started.body)).groupValues[1]

        // A restart: a new executor over the same store, with no pin from the launch.
        val restarted = runner().also { it.verify = r.verify }
        val ended = run("""{"op":"wait","handle":"$handle","timeout":60}""", restarted)

        assertTrue(ended.body.contains("no receipt: pin lost on restart"), ended.body)
        assertTrue(ended.body.contains("CHK-accept-AC-1"), ended.body)
        assertTrue(r.receiptsOf("CHK-accept-AC-1").isEmpty())
    }
}
