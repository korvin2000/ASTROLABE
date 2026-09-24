package io.astrolabe.cell

import io.astrolabe.Config
import io.astrolabe.Defaults
import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.Prime
import io.astrolabe.atlas.Sniff
import io.astrolabe.auth.Redaction
import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reserves
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.contract.Increment
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.Events
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.InMemoryIntentJournal
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.SqliteAliases
import io.astrolabe.evidence.SqliteObservations
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.kb.EmptyKb
import io.astrolabe.os.LocalOs
import io.astrolabe.os.Os
import io.astrolabe.os.search.Searches
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Profile
import io.astrolabe.provider.Request
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.Text
import io.astrolabe.provider.ToolResult
import io.astrolabe.register.Register
import io.astrolabe.register.SqliteRegisterVersions
import io.astrolabe.register.Validator
import io.astrolabe.store.BlobPoint
import io.astrolabe.store.FaultPoints
import io.astrolabe.store.Store
import io.astrolabe.tool.edit.Edit
import io.astrolabe.tool.edit.SyntaxCheck
import io.astrolabe.tool.edit.SyntaxResult
import io.astrolabe.tool.kb.KbTool
import io.astrolabe.tool.look.Look
import io.astrolabe.tool.run.Run
import io.astrolabe.tool.run.SqliteHandles
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.tool.state.StateTool
import io.astrolabe.tool.task.TaskTool
import io.astrolabe.tool.verify.Verify
import io.astrolabe.verify.Checker
import io.astrolabe.verify.Checks
import io.astrolabe.verify.RunnerCommands
import io.astrolabe.verify.Scheduler
import io.astrolabe.verify.ScopeGuard
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.Preimages
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import java.nio.file.Path
import io.astrolabe.provider.Role as ItemRole
import io.astrolabe.provider.ToolCall as NativeCall

internal val WINDOWS: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/** A command that prints [text] and exits 0, in the platform's shell form. */
internal fun echo(text: String): Command =
    if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "echo $text")) else Command(listOf("/bin/sh", "-c", "echo $text"))

/** Thrown from a [FaultPoints] crash point, so a test can tell an injected crash from a real failure. */
internal class InjectedCrash(val point: BlobPoint) : RuntimeException("injected crash at $point")

/**
 * A fault schedule a test arms at the moment it wants — typically from the scripted model's reply, which
 * runs just before the turn's calls dispatch: once armed, the [nth] blob publication crashes before its
 * fsync and the schedule disarms itself.
 */
internal class ArmedCrash(private val nth: Int) {
    @Volatile
    private var armed: Boolean = false

    @Volatile
    var crashes: Int = 0
        private set

    private var puts = 0

    val faults: FaultPoints = FaultPoints { point ->
        if (!armed || point != BlobPoint.BEFORE_FSYNC) return@FaultPoints
        puts += 1
        if (puts == nth) {
            armed = false
            crashes += 1
            throw InjectedCrash(point)
        }
    }

    fun arm() {
        puts = 0
        armed = true
    }
}

/**
 * The whole S0 runtime wired together the way P1.9 will wire it: a repository, a store, the workspace
 * surface, every tool family, the check registry with an end-of-turn checker, and a scripted model.
 */
internal class CellFixture(
    stateRoot: Path,
    faults: FaultPoints = FaultPoints.NONE,
    val defaults: Defaults = Defaults(),
    files: Map<String, String> = DEFAULT_FILES,
    osOverride: ((LocalOs) -> Os)? = null,
) : AutoCloseable {
    val repo: TempRepo = TempRepo.create().also { repo -> files.forEach { (path, text) -> repo.write(path, text) }; repo.commit("initial") }
    val clock = FakeClock.at("2026-09-21T10:00:00Z")
    val store: Store = Store.open(stateRoot, repo.git, clock, faults)
    val workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
    val registry = VersionRegistry(workspace)
    val coherence = Coherence(registry)
    val stamper = Stamper(workspace, EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1")))
    val workset = Workset(immediateStubTokens = defaults.immediateStubTokens)
    val localOs = LocalOs(clock)
    val os: Os = osOverride?.invoke(localOs) ?: localOs
    val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    val idGen = FixedIdGen()
    val estimator = HeuristicEstimator()
    val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
    val atlas: Atlas = Atlas.build(repo.root)
    val contract: Contract = contracts.deriveS0(ids.work, ids.attempt, REQUEST, atlas, Config(), Tokens(200_000)).contract
        .let { it.copy(scope = it.scope.copy(writePaths = listOf("src/", "tests/"))) }
        .also { contracts.open(it) }
    val checks: Checks = Checks.seed(contract, RunnerCommands(typecheck = echo("types ok")))
    val journal = Journal(store, clock)
    val aliases = SqliteAliases(store, clock)
    val observations = SqliteObservations(store, clock)
    val receipts = SqliteReceipts(store, clock)
    val intents = InMemoryIntentJournal()
    val registerVersions = SqliteRegisterVersions(store, clock)
    val checkpoints = SqliteCheckpoints(store, clock)
    val preimages = Preimages(workspace, store.blobs, ids, clock)
    val scheduler = Scheduler(checks, workspace, registry, stamper, receipts, aliases, idGen, ids, clock)
    val logs: Path = stateRoot.resolve("logs")
    val checker = Checker(checks, TrustedLocalRunner(os), os, stamper, registry, workspace, store.blobs, Redaction(), idGen, ids, logs)
    val increment = Increment("inc-1", listOf("R1"), accept = contract.acceptance.map { it.id }, writeScope = listOf("src/"), expectedFiles = 1, title = REQUEST)
    val events = Events(clock)
    val recorder = EventRecorder().also { events.subscribe(it) }

    val state = StateTool(Validator(estimator), registerVersions, journal, estimator, idGen, ids, clock, Register.empty(ids.context!!, increment.id, increment.title), events)
    val look = Look(workspace, registry, workset, atlas, Searches.jvm(), journal, observations, aliases, store.blobs, Redaction(), estimator, idGen, ids)
    val edit = Edit(workspace, registry, workset, os, preimages, ScopeGuard(workspace), contracts, checks, observations, aliases, store.blobs, Redaction(), estimator, idGen, ids, SyntaxCheck { _, _, _ -> SyntaxResult.Ok })
    val run = Run(workspace, registry, stamper, TrustedLocalRunner(os), os, intents, SqliteHandles(store, clock), observations, aliases, store.blobs, Redaction(), estimator, idGen, ids, contracts, AutonomousAuthority(), Config(), clock, logs)
    val verify = Verify(checks, scheduler, checker, null, null, workspace, TrustedLocalRunner(os), os, stamper, store.blobs, Redaction(), estimator, idGen, ids, contracts, logs)
    val task = TaskTool(AutonomousAuthority(), contracts, journal, estimator, idGen, ids, clock, events)
    val kb = KbTool(EmptyKb, estimator, idGen)

    lateinit var adapter: FakeAdapter
        private set

    fun context(model: ScriptedModel, profile: Profile = FakeProfiles.main, profiles: Map<String, Profile> = FakeProfiles.all, holdResponses: Boolean = false, role: Role = Roles.implementing, manifest: String? = null): CellContext {
        adapter = FakeAdapter(model, profiles, holdResponses = holdResponses)
        return CellContext(
            ids = ids,
            role = role,
            contracts = contracts,
            model = CellModel(adapter, profile, estimator),
            tools = CellTools(state, look, edit, run, verify, task, kb),
            workspace = CellWorkspace(workspace, registry, coherence, stamper, workset, checks, scheduler, atlas, checker),
            evidence = CellEvidence(journal, observations, aliases, receipts, intents, registerVersions, checkpoints, preimages),
            prime = Prime.render(atlas, Sniff.commands(atlas)),
            manifest = manifest,
        )
    }

    fun budget(turns: Int = 12, tokens: Long = 400_000): CellBudget = CellBudget.of(Tokens(tokens), turns, Reserves())

    fun cell(defaults: Defaults = this.defaults, completion: RoleCompletion? = null, authority: DispatchAuthority = DispatchAuthority.NONE): Cell =
        Cell(clock, idGen, defaults, Gates.s0(), events, completion, authority)

    suspend fun run(
        model: ScriptedModel,
        turns: Int = 12,
        profile: Profile = FakeProfiles.main,
        profiles: Map<String, Profile> = FakeProfiles.all,
        defaults: Defaults = this.defaults,
        authority: DispatchAuthority = DispatchAuthority.NONE,
        role: Role = Roles.implementing,
        completion: RoleCompletion? = null,
        manifest: String? = null,
    ): CellExit = cell(defaults, completion, authority).run(context(model, profile, profiles, role = role, manifest = manifest), increment, budget(turns))

    /** The `[T]` items of the [n]th request the adapter received (1-based). */
    fun transcript(n: Int): List<Item> = request(n).segment(SegmentKind.T)?.items.orEmpty()

    fun request(n: Int): Request = adapter.calls[n - 1].request

    fun anchorText(n: Int): String = (request(n).segment(SegmentKind.A)!!.items.single() as Message).text

    fun version(path: String): FileVersion = registry.version(path)!!

    override fun close() {
        events.close()
        runCatching { localOs.close() }
        runCatching { store.close() }
        runCatching { repo.close() }
    }

    companion object {
        const val REQUEST = "make a return 10"
        const val A_PY = "def a():\n    return 1\n\n\ndef b():\n    return 2\n"
        val DEFAULT_FILES: Map<String, String> = mapOf(
            "src/a.py" to A_PY,
            "src/b.py" to "x = 1\ny = 2\n",
            "tests/test_a.py" to "def test_a():\n    assert a() == 1\n",
            "README.md" to "# fixture\n",
        )

        fun say(text: String): Message = Message.text(ItemRole.Assistant, text)

        fun call(id: String, family: String, json: String): NativeCall = NativeCall(id, family, json)

        fun tree(id: String): NativeCall = call(id, "look", """{"what":"tree"}""")

        fun read(id: String, target: String): NativeCall = call(id, "look", """{"what":"read","target":"$target"}""")

        fun anchored(id: String, path: String, expect: FileVersion, anchor: String, new: String): NativeCall =
            call(id, "edit", """{"ops":[{"path":"$path","expect":"${expect.digest.hex}","hunks":[{"anchor":${quote(anchor)},"new":${quote(new)}}]}],"why":"w"}""")

        fun patch(id: String, ops: String): NativeCall = call(id, "state", """{"op":"patch","patch":[$ops]}""")

        fun runCmd(id: String, text: String, extra: String = ""): NativeCall {
            val argv = echo(text).argv.joinToString(",") { quote(it) }
            return call(id, "run", """{"argv":[$argv]$extra}""")
        }

        fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

        fun resultText(item: Item): String = (item as ToolResult).content.filterIsInstance<Text>().joinToString("") { it.text }
    }
}
