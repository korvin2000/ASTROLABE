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
import io.astrolabe.evidence.IntentJournal
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.evidence.Observation
import io.astrolabe.evidence.Observations
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
    fun `a port that is open when a wait starts is probed once and left to the line or the end`() = runTest {
        val scripted = ScriptedOs(listOf("" to ProcStatus.Running, "" to ProcStatus.Running, "listening on 8080\n" to ProcStatus.Running))
        scripted.portOpen = { true }
        val tool = runner(os = scripted)
        run("""{"argv":["git","status"],"bg":true}""", tool)

        val out = run("""{"op":"wait","handle":"handle-1","until_line":"listening","until_port":8080}""", tool)

        assertTrue(out.body.contains("ready: line matched: listening on 8080"), out.body)
        assertTrue(out.body.contains("port 8080 was already open before the wait"), out.body)
        assertFalse(out.body.contains("accepts connections"), out.body)
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
     * the poll's own timeout passes on the injected clock. The launch's first look is poll 0.
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
            clock.advance(java.time.Duration.ofSeconds(observationTimeoutSeconds))
            return Poll(log.copyOfRange(sinceCursorBytes.toInt(), log.size), log.size.toLong(), status, false)
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
}
