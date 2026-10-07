package io.astrolabe.campaign

import io.astrolabe.BalanceProfile
import io.astrolabe.Config
import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.IntegrityApproval
import io.astrolabe.Mode
import io.astrolabe.PlanCellPolicy
import io.astrolabe.ProfileRoles
import io.astrolabe.RulesBinding
import io.astrolabe.ShapePolicy
import io.astrolabe.UnknownOutcomeReconciliation
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.RedactionConfig
import io.astrolabe.auth.RedactionPattern
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.patch
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.Protocol
import io.astrolabe.cell.Roles
import io.astrolabe.cell.WINDOWS
import io.astrolabe.context.SeedRule
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Risk
import io.astrolabe.contract.Shape
import io.astrolabe.event.AgentEvent
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Request
import io.astrolabe.route.Tier
import io.astrolabe.route.TierTable
import io.astrolabe.workflow.DirtyRepo
import io.astrolabe.workflow.Scenario
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Plan §6 C18 item 4 (§14: "a setting or a schema promise does not reach through the module assembly"): every field of
 * [Defaults] and [Config] a host sets — through the constructors or Studio's `ConfigSupport`, which decodes the whole
 * `Config` — reaches a consumer through the real composition: [Controller] open, a cell, the dispatcher and the tools,
 * with the fake adapter as the only model ([Scenario]).
 *
 * The oracle is differential: a field set to a non-default value is reached when what the run shows — every model request,
 * the events (sorted; tool and telemetry events, whose order or timing parallel reads move, left out) and the outcome —
 * differs from both baseline runs of its context in a line the two baselines agree on. A field with no such effect fails
 * with its name. Fields whose effect cannot be observed offline are [Excepted] with the reason; fields with no consumer in
 * the code are [Unwired] with the tail that owns them — both named, never silent. A new field without a row fails
 * [every host setting has a row].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettingsReachabilityTest {
    @TempDir
    lateinit var tmp: Path

    /** Every run of the class (two baselines per context, one per reached row), played on first use on a few threads. */
    private val played: Map<String, Result<Played>> by lazy { playAll() }

    @Test
    fun `every host setting has a row`() {
        val declared = Defaults.serializer().descriptor.elementNames.map { "defaults.$it" } +
            Config.serializer().descriptor.elementNames.map { "config.$it" }
        val named = ROWS.map { it.field }
        assertEquals(named.size, named.toSet().size, "a field has two rows: ${named.groupBy { it }.filterValues { it.size > 1 }.keys}")
        assertEquals(emptyList(), declared - named.toSet(), "settings without a reachability row")
        assertEquals(emptyList(), named - declared.toSet(), "rows for fields that do not exist")
        ROWS.filterIsInstance<Unwired>().forEach { println("SETTINGS-unwired ${it.field}: ${it.reason}") }
        ROWS.filterIsInstance<Excepted>().forEach { println("SETTINGS-excepted ${it.field}: ${it.reason}") }
        ROWS.filterIsInstance<Deferred>().forEach { println("SETTINGS-deferred ${it.field}: ${it.reason}") }
    }

    @Test
    fun `every context replays the same way`() {
        val unstable = ROWS.filterIsInstance<Reached>().map { it.baseKey }.distinct().mapNotNull { key ->
            val (b1, b2) = baselines(key)
            val noise = b1.lines.indices.count { b1.lines[it] != b2.lines.getOrNull(it) }
            println("SETTINGS-context $key lines=${b1.lines.size}/${b2.lines.size} noisy=$noise ${b1.outcome}")
            b1.lines.indices.filter { b1.lines[it] != b2.lines.getOrNull(it) }.take(3).forEach { println("SETTINGS-noise $key ${b1.lines[it].take(160)}") }
            key.takeIf { b1.lines.size != b2.lines.size || (noise > 2 && noise * 20 > b1.lines.size) }
        }
        assertEquals(emptyList(), unstable, "contexts whose two identical runs differ in shape or in more than 5% (and 2) of their lines")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reachedFields")
    fun `a host setting reaches its consumer`(field: String) {
        val row = ROWS.single { it.field == field } as Reached
        val (b1, b2) = baselines(row.baseKey)
        val altered = if (row.set == null) b1 else played.getValue(field).getOrElse { throw AssertionError("$field: the run failed", it) }
        row.check?.let { check -> check(altered, b1)?.let { fail("$field: $it") } }
        if (row.set == null) return
        val difference = difference(altered, b1, b2)
        println("SETTINGS-reach $field ctx=${row.context} reached=${difference != null} ${difference.orEmpty().take(300)}")
        if (difference == null) fail("$field (${row.context}): no observable effect through Controller → cell → dispatcher → tools — the setting does not reach a consumer")
    }

    fun reachedFields(): List<String> = ROWS.filterIsInstance<Reached>().map { it.field }

    // ------------------------------------------------------------------ oracle

    private fun baselines(key: String): Pair<Played, Played> =
        played.getValue("$key#1").getOrElse { throw AssertionError("baseline $key failed", it) } to
            played.getValue("$key#2").getOrElse { throw AssertionError("baseline $key failed", it) }

    /** The first line where [altered] differs from both baselines, ignoring lines the baselines disagree on; `null`: none. */
    private fun difference(altered: Played, b1: Played, b2: Played): String? {
        val noise = b1.lines.indices.filter { b1.lines[it] != b2.lines.getOrNull(it) }.toSet()
        fun against(b: Played): String? {
            if (altered.lines.size != b.lines.size) return "shape ${b.lines.size} → ${altered.lines.size} lines; first change: " +
                (altered.lines.indices.firstOrNull { altered.lines[it] != b.lines.getOrNull(it) }?.let { altered.lines[it] } ?: "(fewer lines)")
            return altered.lines.indices.firstOrNull { it !in noise && altered.lines[it] != b.lines[it] }?.let { "line $it: ${b.lines[it]} → ${altered.lines[it]}" }
        }
        return against(b1)?.let { first -> against(b2)?.let { first } }
    }

    private fun playAll(): Map<String, Result<Played>> {
        val jobs = LinkedHashMap<String, () -> Played>()
        for (row in ROWS.filterIsInstance<Reached>()) {
            for (n in 1..2) jobs.getOrPut("${row.baseKey}#$n") { { play(row.context, "${row.baseKey}-$n") { c -> row.base?.invoke(c) ?: c } } }
            row.set?.let { set -> jobs[row.field] = { play(row.context, row.field) { c -> set(row.base?.invoke(c) ?: c) } } }
        }
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val futures = jobs.mapValues { (_, job) -> pool.submit(Callable { job() }) }
            return futures.mapValues { (_, future) ->
                try {
                    Result.success(future.get())
                } catch (failure: ExecutionException) {
                    Result.failure(failure.cause ?: failure)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ runs

    /** What one run showed: its normalised lines, and the raw requests and events for the rows' own checks. */
    class Played(val lines: List<String>, val requests: List<Request>, val events: List<AgentEvent>, val outcome: String, val stateRoot: Path, val store: Path)

    enum class Context { S0, PRESSURE, CARRY, CARRY_PATH, FOLLOW_UP, REVIEW, DIRECT }

    private fun play(context: Context, name: String, configure: (Config) -> Config): Played = runBlocking {
        DirtyRepo.create(1, bigBytes = 0, settle = false).use { dirty ->
            dirty.repo.write(BIG, (1..300).joinToString("\n", postfix = "\n") { if (it == 2) "TOKEN = 'REACHME-0042'" else "line$it = $it" })
            dirty.repo.write(RULES, RULES_TEXT)
            val state = tmp.resolve(name.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
            Scenario(dirty.root, state, configure = { configure(contextConfig(context, it)) }).use { s ->
                val requests = ArrayList<Request>()
                fun collect() {
                    requests += checkNotNull(s.adapter).calls.map { it.request }
                }
                when (context) {
                    Context.S0, Context.PRESSURE -> {
                        s.seed(dirty.check)
                        s.play(::s0Script)
                        collect()
                    }
                    Context.CARRY, Context.CARRY_PATH -> {
                        s.policy = CampaignPolicy(Tokens(200_000), resumeExpected = true)
                        s.seed(dirty.check, shape = Shape.S1) { it.copy(budget = it.budget.copy(turnsPerCell = CARRY_TURNS)) }
                        // CARRY_PATH: the next step names the edited paths, so the seed rule selects them (no fallback).
                        val next = if (context == Context.CARRY_PATH) "verify the change in ${DirtyRepo.SOURCE} and $UTIL" else "verify the change"
                        s.playWith(maxCells = 2) { c -> ScriptedModel.of(*(carryCell(c, next) + List(CARRY_TURNS) { Scripted.Reply(listOf(say("looking"), call("t$it", "look", """{"what":"tree"}"""))) }).toTypedArray()) }
                        collect()
                    }
                    Context.FOLLOW_UP -> {
                        s.policy = CampaignPolicy(Tokens(200_000), resumeExpected = true)
                        s.seed(dirty.check, shape = Shape.S1)
                        s.play(::parentCell)
                        collect()
                        s.policy = s.policy.copy(declaredChecks = listOf(Acceptance.Run("saved", dirty.check, Origin.User)))
                        s.open(CampaignRequest(WorkId("W-2"), AttemptId("a1"), "also skip None items", parentWork = s.request.work))
                        s.playWith(maxCells = 1) { ScriptedModel.of(Scripted.Reply(listOf(say("done")))) }
                        collect()
                    }
                    Context.REVIEW -> {
                        s.policy = CampaignPolicy(Tokens(400_000))
                        s.seed(dirty.check) { derived ->
                            derived.copy(
                                shape = Shape.S2,
                                risk = Risk(1, Reversibility.Hard, false),
                                requirements = listOf(Requirement("R1", "total ignores negative items", listOf("AC-1"), authorityRef = derived.requests.single().id)),
                                acceptance = derived.acceptance + Acceptance.Review("AC-R", "a maintainer approves the whole change", Origin.User),
                            )
                        }
                        s.play(::reviewScript)
                        collect()
                    }
                    Context.DIRECT -> {
                        s.seed(dirty.check)
                        s.play { c -> directScript(c, dirty.check) }
                        collect()
                    }
                }
                check(s.recorder.awaitCount(s.events.lastSeq.toInt())) { "events up to ${s.events.lastSeq} were not delivered" }
                val events = s.recorder.events
                val outcome = "outcome ${s.outcome?.wire}: ${s.last?.state?.reason}"
                // Boundary pre-compilation reports to the controller's metrics, not to the model or the events.
                val precompiles = "precompiles ${s.controller.precompiles.samples().size}"
                val lines = requests.flatMapIndexed { i, r -> listOf("request $i") + r.toString().lines() } +
                    events.filter(::shown).map { it.toString() }.sorted() + outcome + precompiles
                Played(lines.map { normalise(it, dirty.root, state) }, requests, events, outcome, state, checkNotNull(s.campaign).store.layout.blobsRecovery)
            }
        }
    }

    private fun contextConfig(context: Context, c: Config): Config = when (context) {
        // A ceiling a few thousand tokens above the fixed prompt: the reads of the cell press it into a rebuild.
        Context.PRESSURE -> c.copy(defaults = c.defaults.copy(contextCeilingTokens = 31_000))
        Context.DIRECT -> c.copy(protocol = Protocol.Direct)
        else -> c
    }

    /** Tool events follow parallel reads and telemetry carries timings: the requests already hold every result. */
    private fun shown(event: AgentEvent): Boolean = event !is AgentEvent.Telemetry && event !is AgentEvent.Cell.ToolCalled &&
        event !is AgentEvent.Cell.ToolResulted && event !is AgentEvent.Cell.ModelProgress && event !is AgentEvent.Cell.ModelResponded &&
        event !is AgentEvent.Run.Output

    private fun normalise(line: String, root: Path, state: Path): String = listOf(root to "<root>", state to "<state>").fold(line) { acc, (path, mark) ->
        val text = path.toString()
        acc.replace(text.replace("\\", "\\\\"), mark).replace(text, mark).replace(text.replace('\\', '/'), mark)
    }

    // ------------------------------------------------------------------ scripts

    private fun s0Script(c: OpenedCampaign): List<Scripted> = listOf(
        Scripted.Reply(listOf(say("reading"), read("r1", DirtyRepo.SOURCE), read("r2", UTIL))),
        Scripted.Reply(listOf(say("reading the big file"), read("r3", BIG))),
        Scripted.Reply(listOf(say("editing"), editSource(c), editUtil(c), patch("s1", PATCH), patch("s2", """{"focus.set":{"dir":"src"}}"""))),
        Scripted.Reply(listOf(say("verifying"), call("v1", "verify", ACCEPTANCE))),
        Scripted.Reply(listOf(say("done"))),
    )

    private fun carryCell(c: OpenedCampaign, next: String): List<Scripted> = listOf(
        Scripted.Reply(listOf(say("reading"), read("r1", DirtyRepo.SOURCE), read("r2", UTIL), read("r3", "tests/test_app.py"))),
        Scripted.Reply(listOf(say("editing"), editSource(c), editUtil(c))),
        Scripted.Reply(listOf(say("recording"), patch("s1", """{"plan.add":"finish the change"},{"plan.cursor":1},$DECIDED,{"next":"$next"}"""))),
    )

    private fun parentCell(c: OpenedCampaign): List<Scripted> = listOf(
        Scripted.Reply(listOf(say("reading"), read("r1", DirtyRepo.SOURCE), read("r2", UTIL))),
        Scripted.Reply(listOf(say("editing"), editSource(c), editUtil(c))),
        Scripted.Reply(listOf(say("recording"), patch("s1", PATCH))),
        Scripted.Reply(listOf(say("verifying"), call("v1", "verify", ACCEPTANCE))),
        Scripted.Reply(listOf(say("done"))),
    )

    private fun reviewScript(c: OpenedCampaign): List<Scripted> = listOf(
        Scripted.Reply(listOf(say("planning one increment"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$PLAN}"""))),
        Scripted.Reply(listOf(say("plan ready"))),
        Scripted.Reply(listOf(say("reading"), read("r1", DirtyRepo.SOURCE))),
        Scripted.Reply(listOf(say("editing"), editSource(c))),
        Scripted.Reply(listOf(say("verifying"), call("v1", "verify", ACCEPTANCE))),
        Scripted.Reply(listOf(say("done"))),
        // The increment's review cell, then the campaign's.
        Scripted.Reply(listOf(say("checking the change"), read("rv1", DirtyRepo.SOURCE), read("rv2", UTIL))),
        Scripted.Reply(listOf(say(VERDICT))),
        Scripted.Reply(listOf(say("checking the whole change"), read("rv3", DirtyRepo.SOURCE))),
        Scripted.Reply(listOf(say(VERDICT))),
    )

    private fun directScript(c: OpenedCampaign, check: Command): List<Scripted> {
        val argv = """{"argv":${JsonArray(check.argv.map(::JsonPrimitive))}}"""
        return listOf(
            Scripted.Reply(listOf(say("reading"), read("r1", DirtyRepo.SOURCE))),
            Scripted.Reply(listOf(say("editing"), editSource(c))),
            Scripted.Reply(listOf(say("running"), call("x1", "run", argv), call("x2", "run", """{"argv":["pytest","-q"]}"""))),
            Scripted.Reply(listOf(say("noting"), call("n1", "state", """{"op":"note","note":{"kind":"hypothesis","text":"negatives are dropped before the sum, so total([-1, 2]) is 2"}}"""))),
            Scripted.Reply(listOf(call("f1", "task", """{"op":"finish","text":"total ignores negative items"}"""))),
        )
    }

    private fun editSource(c: OpenedCampaign) =
        anchored("e1", DirtyRepo.SOURCE, checkNotNull(c.registry.version(DirtyRepo.SOURCE)), "    return sum(items)", "    return sum(x for x in items if x >= 0)")

    private fun editUtil(c: OpenedCampaign) =
        anchored("e2", UTIL, checkNotNull(c.registry.version(UTIL)), "    return max(lo, min(x, hi))", "    return min(hi, max(x, lo))")

    // ------------------------------------------------------------------ rows

    sealed interface Row {
        val field: String
    }

    /**
     * [set] in [context] (after [base], which the context's baselines get too) has an observable effect; [check] is a
     * row's own assertion on the run and a baseline (`null` when it holds). A `null` [set] checks the baseline itself.
     */
    class Reached(
        override val field: String,
        val context: Context,
        val set: ((Config) -> Config)?,
        val base: ((Config) -> Config)? = null,
        val check: ((Played, Played) -> String?)? = null,
    ) : Row {
        val baseKey: String
            get() = if (base == null) context.name else "${this.field}-base"
    }

    /** Observable only online or by waiting on a wall-clock deadline: named with the reason, never run. */
    class Excepted(override val field: String, val reason: String) : Row

    /** No consumer reads it outside `Defaults.kt` (C18 finding): named with the tail that owns the wiring. */
    class Unwired(override val field: String, val reason: String) : Row

    /** Read only on a path this class does not play (a delegated child cell): named with the tail that owns its scenario. */
    class Deferred(override val field: String, val reason: String) : Row

    private companion object {
        const val THREADS = 8
        const val BIG = "src/big.py"
        const val UTIL = "src/util.py"
        const val RULES = "AGENTS.md"
        const val RULES_TEXT = "Keep every change inside src/ and name the requirement it serves.\n"
        const val CARRY_TURNS = 4
        const val ACCEPTANCE = """{"what":"acceptance","ids":["AC-1"]}"""
        const val VERDICT = """{"verdict":"approve","confidence":0.9,"findings":[]}"""
        const val PLAN = """{"increments":[{"id":"I1","requirements":["R1"],"accept":["AC-1"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"}]}"""
        const val DECIDED = """{"decision.add":{"text":"move the clamp before the sum","because":"negatives must not count","rejected":"filter in the caller"}},""" +
            """{"deadend.add":{"text":"patching the test","evidence":null,"scope":"tests/","reopen":"the contract changes"}}"""
        const val PATCH = """$DECIDED,{"next":"verify the change"}"""
        const val TAIL_UNWIRED = "tail C18-unwired: declared, validated and offered by Studio, but no consumer reads it"
        const val TAIL_DELEGATION = "tail C18-delegation: read when a model delegates (task.delegate) to a child cell; the scenario of a delegated child is not played here"

        val PRINTING: Command = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

        fun d(field: String, context: Context = Context.S0, base: ((Defaults) -> Defaults)? = null, check: ((Played, Played) -> String?)? = null, set: (Defaults) -> Defaults): Reached =
            Reached("defaults.$field", context, { c -> c.copy(defaults = set(c.defaults)) }, base?.let { b -> { c: Config -> c.copy(defaults = b(c.defaults)) } }, check)

        /** A Defaults field that is the default of a Config field: the host's Config is built from those Defaults. */
        fun dHost(field: String, set: (Defaults) -> Defaults): Reached = Reached("defaults.$field", Context.S0, { c -> fresh(c, set(c.defaults)) })

        fun cfg(field: String, context: Context = Context.S0, set: (Config) -> Config): Reached = Reached("config.$field", context, set)

        fun fresh(c: Config, defaults: Defaults): Config = Config(
            defaults = defaults, profiles = c.profiles, rulesFile = c.rulesFile, redaction = c.redaction, stateRoot = c.stateRoot, flags = c.flags,
            roles = c.roles, qualityGates = c.qualityGates, tierTable = c.tierTable, modelChecks = c.modelChecks, balance = c.balance, protocol = c.protocol,
        )

        /** C18 item 1: the host's look budget is the budget the read of a larger file was cut to; the default shows it whole. */
        val LOOK_BUDGET: (Played, Played) -> String? = { altered, baseline ->
            fun bigRead(p: Played): String? = p.requests.asSequence().flatMap { it.toString().lines() }.firstOrNull { "tool=look" in it && "$BIG:" in it }
            val cut = bigRead(altered)
            val whole = bigRead(baseline)
            when {
                cut == null || whole == null -> "no result header of the read of $BIG (altered=$cut, baseline=$whole)"
                "truncated=yes" !in cut -> "the read of $BIG on a 300-token budget was not cut: $cut"
                "truncated=no" !in whole -> "the read of $BIG on the default budget was cut: $whole"
                else -> null
            }
        }

        val ROWS: List<Row> = listOf(
            // ---- Defaults
            d("shapePolicy", Context.CARRY) { it.copy(shapePolicy = ShapePolicy(planCell = PlanCellPolicy.Always)) },
            d("turnsPerCell", check = { a, _ ->
                val maxes = a.events.filterIsInstance<AgentEvent.Cell.TurnStarted>().map { it.turnsMax }.toSet()
                if (maxes == setOf(4)) null else "the cells ran with turn budgets $maxes, not 4"
            }) { it.copy(turnsPerCell = 4) },
            d("turnNudgeFraction") { it.copy(turnNudgeFraction = 0.01) },
            Excepted("defaults.providerTerminalWaitSeconds", "a wall-clock wait for a provider terminal that never arrives; the fake adapter always ends its stream"),
            d("alpha") { it.copy(alpha = 0.05) },
            d("k", Context.PRESSURE) { it.copy(k = 1) },
            // Reached in one of two runs only: the fixture's reads do not reliably press the cell into a rebuild (tail C18-pressure).
            Deferred("defaults.m", "tail C18-pressure: the turns a pressure rebuild keeps; this fixture does not reliably press a cell into a rebuild"),
            d("rMaxTokens") { it.copy(rMaxTokens = 600) },
            d("anchorMaxTokens") { it.copy(anchorMaxTokens = 200) },
            d("immediateStubTokens") { it.copy(immediateStubTokens = 1) },
            d("lookBudgetTokens", check = LOOK_BUDGET) { it.copy(lookBudgetTokens = 300) },
            d("runBudgetTokens") { it.copy(runBudgetTokens = 40) },
            d("growthReserveFullWindowTokens") { it.copy(growthReserveFullWindowTokens = 10_000_000) },
            d("registerCapTokens") { it.copy(registerCapTokens = 40) },
            d("digestCapTokens") { it.copy(digestCapTokens = 10) },
            d("digestTokensPerRequirement", base = { it.copy(digestCapTokens = 10) }) { it.copy(digestTokensPerRequirement = 500) },
            d("digestCapCeilingTokens", base = { it.copy(digestCapTokens = 10, digestTokensPerRequirement = 500) }) { it.copy(digestCapCeilingTokens = 20) },
            d("patchCapTokens") { it.copy(patchCapTokens = 20) },
            d("factLineMaxChars") { it.copy(factLineMaxChars = 10) },
            Unwired("defaults.noteBodyMaxTokens", "$TAIL_UNWIRED (kb.Note.MAX_BODY_TOKENS is the constant in force)"),
            Unwired("defaults.noteSummaryMaxChars", "$TAIL_UNWIRED (kb.Note.MAX_SUMMARY_CHARS is the constant in force)"),
            // Reached on the follow-up's parent carry only: the cell-boundary carry and the pressure rebuild call
            // CarryForward.carry without seedCapTokens and get its 4000 constant (tail C18-seeds).
            d("seedsMaxTokens", Context.FOLLOW_UP) { it.copy(seedsMaxTokens = 1) },
            d("seedRule", Context.CARRY_PATH) { it.copy(seedRule = SeedRule.V2) },
            Unwired("defaults.injectionMaxNotes", "$TAIL_UNWIRED (Controller ranks with InjectionWeights() defaults; hot file)"),
            Unwired("defaults.injectionMaxTokens", "$TAIL_UNWIRED (Controller ranks with InjectionWeights() defaults; hot file)"),
            d("focusNotesMaxTokens") { it.copy(focusNotesMaxTokens = 1) },
            d("focusZoomMaxTokens") { it.copy(focusZoomMaxTokens = 1) },
            d("touchedInAnchor") { it.copy(touchedInAnchor = 1) },
            Excepted("defaults.checkerTimeBoxSeconds", "a wall-clock time box: the fixture's checks end in milliseconds; seen only by waiting it out"),
            Excepted("defaults.checkerFallbackTimeBoxSeconds", "a wall-clock time box of a project-wide fallback check; seen only by waiting it out"),
            d("theta") { it.copy(theta = 0) },
            d("fullSuiteCadence") { it.copy(fullSuiteCadence = 1) },
            d("reserveVerification") { it.copy(reserveVerification = 0.5) },
            d("reserveRecoveryAndPersist") { it.copy(reserveRecoveryAndPersist = 0.4) },
            Unwired("defaults.campaignRecoveryReserve", TAIL_UNWIRED),
            d("stallTurns") { it.copy(stallTurns = 1) },
            d("loopIdentical") { it.copy(loopIdentical = 1) },
            d("repeatedSignatureRepairs") { it.copy(repeatedSignatureRepairs = 1) },
            d("doomLoopSameCalls") { it.copy(doomLoopSameCalls = 1) },
            Unwired("defaults.probeTurns", "$TAIL_UNWIRED (delegate.ProbeBudget.DEFAULT turns 15 is in force)"),
            Deferred("defaults.probeTokens", TAIL_DELEGATION),
            Unwired("defaults.probeTier", "$TAIL_UNWIRED (the probe's tier comes from its role)"),
            d("reviewLookMax", Context.REVIEW) { it.copy(reviewLookMax = 1) },
            d("reviewIncrementTokens", Context.REVIEW) { it.copy(reviewIncrementTokens = 3_000) },
            d("reviewCampaignTokens", Context.REVIEW) { it.copy(reviewCampaignTokens = 3_000) },
            Unwired("defaults.reviewTier", "$TAIL_UNWIRED (Controller obtains every review at Tier.Medium; hot file)"),
            Unwired("defaults.reviewRoutineTier", "$TAIL_UNWIRED (Controller obtains every review at Tier.Medium; hot file)"),
            d("repairCalls") { it.copy(repairCalls = 1) },
            d("attemptsPerIncrement") { it.copy(attemptsPerIncrement = 1) },
            Deferred("defaults.writerDepth", TAIL_DELEGATION),
            Deferred("defaults.probeDepth", TAIL_DELEGATION),
            Deferred("defaults.parallelCells", "$TAIL_DELEGATION (and S3 writers, off by default)"),
            d("campaignCells") { it.copy(campaignCells = 1) },
            Unwired("defaults.flakyIsolatedReruns", "$TAIL_UNWIRED (verify/ owns the flaky policy)"),
            Unwired("defaults.admissionConfidenceMax", "$TAIL_UNWIRED (kb admission)"),
            dHost("profileRoles") { it.copy(profileRoles = ProfileRoles(main = "helper", helper = "main")) },
            dHost("mode") { it.copy(mode = Mode.Autonomous) },
            dHost("executionMode") { it.copy(executionMode = ExecutionMode.Confined) },
            dHost("dClass") { it.copy(dClass = DClassPolicy.Deny) },
            dHost("integrityApproval") { it.copy(integrityApproval = IntegrityApproval.Human) },
            dHost("unknownOutcomeReconciliation") { it.copy(unknownOutcomeReconciliation = UnknownOutcomeReconciliation.Automatic) },
            dHost("ceiling") { it.copy(ceiling = Stage.LocalCommit) },
            Excepted("defaults.runTimeoutSeconds", "a wall-clock deadline of a run without its own timeout; the fixture's commands end in milliseconds"),
            Excepted("defaults.gitDeadlineSeconds", "a wall-clock deadline of one git command; the fixture's git commands end in milliseconds"),
            d("contextCeilingTokens") { it.copy(contextCeilingTokens = 3_000) },
            d("callsPerResponseMax") { it.copy(callsPerResponseMax = 1) },
            dHost("capabilitySet") { it.copy(capabilitySet = "workspace-local-test-only") },
            d("directRunsMaxLines", Context.DIRECT) { it.copy(directRunsMaxLines = 1) },
            d("directNotesMaxTokens", Context.DIRECT) { it.copy(directNotesMaxTokens = 1) },
            d("directAnchorTargetTokens", Context.DIRECT) { it.copy(directAnchorTargetTokens = 50) },
            d("runTurnBudgetTokens", Context.DIRECT, base = { it.copy(runBudgetTokens = 40) }) { it.copy(runTurnBudgetTokens = 40) },
            d("seedFallback", Context.CARRY) { it.copy(seedFallback = false) },
            d("parentCarryMaxTokens", Context.FOLLOW_UP) { it.copy(parentCarryMaxTokens = 40) },
            // ---- Config
            Reached("config.defaults", Context.S0, null, check = { _, _ -> null }),
            Excepted("config.profiles", "the provider profiles a live host's adapters serve; offline the fake adapter's profiles are fixed, and config.tierTable routes through them"),
            cfg("profileRoles") { it.copy(profileRoles = ProfileRoles(main = "helper", helper = "main")) },
            cfg("mode") { it.copy(mode = Mode.Autonomous) },
            cfg("executionMode") { it.copy(executionMode = ExecutionMode.Confined) },
            cfg("dClass") { it.copy(dClass = DClassPolicy.Deny) },
            cfg("integrityApproval") { it.copy(integrityApproval = IntegrityApproval.Human) },
            cfg("unknownOutcomeReconciliation") { it.copy(unknownOutcomeReconciliation = UnknownOutcomeReconciliation.Automatic) },
            cfg("ceiling") { it.copy(ceiling = Stage.LocalCommit) },
            cfg("rulesFile") { it.copy(rulesFile = RulesBinding(RULES, Digest.of(RULES_TEXT.toByteArray()), "host")) },
            cfg("redaction") { it.copy(redaction = RedactionConfig(patterns = RedactionConfig().patterns + RedactionPattern("reach", "REACHME-\\d+"))) },
            Reached("config.stateRoot", Context.S0, null, check = { a, _ ->
                if (a.store.startsWith(a.stateRoot)) null else "the store ${a.store} is not under the configured state root ${a.stateRoot}"
            }),
            cfg("flags", Context.CARRY) { it.copy(flags = it.flags.copy(precompile = true)) },
            cfg("roles") { it.copy(roles = mapOf(Roles.implementing.name to Roles.implementing.copy(personaLines = listOf("Keep replies short.")))) },
            cfg("qualityGates") { it.copy(qualityGates = listOf(PRINTING)) },
            cfg("tierTable") { it.copy(tierTable = TierTable("reach-1", profiles = Tier.entries.filter { t -> t.model }.associateWith { setOf("helper") })) },
            cfg("modelChecks", Context.DIRECT) { it.copy(modelChecks = false) },
            cfg("balance") { it.copy(balance = BalanceProfile.Economy) },
            cfg("capabilitySet") { it.copy(capabilitySet = "workspace-local-test-only") },
            cfg("protocol") { it.copy(protocol = Protocol.Direct) },
        )
    }
}
