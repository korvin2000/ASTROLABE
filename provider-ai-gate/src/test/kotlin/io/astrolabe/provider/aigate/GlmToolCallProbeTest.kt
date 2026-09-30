package io.astrolabe.provider.aigate

import io.astrolabe.auth.Boundary
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.cell.Layout
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Shape
import io.astrolabe.provider.ContentPart
import io.astrolabe.provider.Effort
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Profile
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Request
import io.astrolabe.provider.Role
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.Text
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolMask
import io.astrolabe.provider.ToolResult
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolSchemas
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.ai.gate.Llm
import net.ai.gate.auth.Environment
import net.ai.gate.config.WireLog
import net.ai.gate.vendors.openai.OpenAiCompatible
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.test.Test

/**
 * Live probe — **network and billable** — that replays the plan-cell situation of Studio task
 * `W-tgxiilat7og6a33vtwva` (diags, 2026-09-30) against a real OpenRouter model through the same path the Studio
 * uses (AI Gate → openai-completions, streaming, `AiGateAdapter`), to find out where the corrupted tool-call
 * arguments seen in that task (`blocked<arg_key>evidence`, truncated JSON, missing `op`) come from.
 *
 * Runs only through `./gradlew :provider-ai-gate:liveTest --tests '*GlmToolCallProbeTest*'` with `OPENROUTER_API_KEY`
 * in the environment. Knobs: `ASTROLABE_PROBE_MODEL` (default `z-ai/glm-5.3-flash`), `ASTROLABE_PROBE_N` (calls per
 * scenario, default 6), `ASTROLABE_PROBE_SCENARIOS` (comma list of `masked-run,blocked-explicit,first-turn`),
 * `ASTROLABE_PROBE_OUT` (directory, default `build/probe`), `ASTROLABE_PROBE_CATALOG` (catalog snapshot file; default
 * the Studio's `%LOCALAPPDATA%/AstrolabeStudio/catalog-snapshot.json`, else the online catalog).
 *
 * Output: `<out>/probe.jsonl` — one line per call with every tool call's name, raw `argsJson` and flags; and
 * `<out>/wire.log` — the SDK wire log with request bodies (`WireLog.BODIES`), which `diags/tools/or_raw_capture.py`
 * replays directly against the provider to capture raw SSE frames.
 */
@EnabledIfSystemProperty(named = "astrolabe.live", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class GlmToolCallProbeTest {
    private val json = Json { prettyPrint = false }

    @Test
    fun probe() {
        val modelId = System.getenv("ASTROLABE_PROBE_MODEL")?.takeIf { it.isNotBlank() } ?: "z-ai/glm-5.3-flash"
        val n = System.getenv("ASTROLABE_PROBE_N")?.toIntOrNull() ?: 6
        val scenarios = (System.getenv("ASTROLABE_PROBE_SCENARIOS")?.takeIf { it.isNotBlank() } ?: "masked-run,blocked-explicit,first-turn").split(',').map { it.trim() }
        val out = Path.of(System.getenv("ASTROLABE_PROBE_OUT")?.takeIf { it.isNotBlank() } ?: "build/probe")
        Files.createDirectories(out)
        val catalog = System.getenv("ASTROLABE_PROBE_CATALOG")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: System.getenv("LOCALAPPDATA")?.let { Path.of(it, "AstrolabeStudio", "catalog-snapshot.json") }?.takeIf(Files::exists)

        // The SDK's wire log speaks System.Logger; without a bridge that is java.util.logging.
        val wire = Logger.getLogger("net.ai.gate.wire")
        val handler = FileHandler(out.resolve("wire.log").toString(), false).apply { formatter = SimpleFormatter(); level = Level.ALL }
        wire.addHandler(handler)
        wire.level = Level.ALL

        val builder = Llm.builder().provider(OpenAiCompatible.openRouter()).environment(Environment.system()).http { it.wireLog(WireLog.BODIES) }
        if (catalog != null) builder.catalog { it.snapshotFile(catalog).manualRefresh() }
        builder.build().use { llm ->
            val draft = AiGateProfiles.draft(llm, "openrouter", modelId, "probe", LocalDate.now())
            val merged = (draft.config["gate"] as JsonObject) + (GateTestKit.gate("""{"options":{"retry":{"maxAttempts":1}}}""")["gate"] as JsonObject)
            val profile = draft.copy(config = JsonObject(mapOf("gate" to JsonObject(merged))))
            println("[probe] $modelId · ${scenarios.size} scenario(s) × $n · out=$out · caching=${profile.capabilities.caching}")
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                Files.newBufferedWriter(out.resolve("probe.jsonl")).use { log ->
                    for (scenario in scenarios) for (i in 1..n) {
                        val request = Scenarios.request(scenario, profile)
                        val id = InvocationId("probe-$scenario-$i")
                        val record = try {
                            runBlocking {
                                val invocation = adapter.start(request, id)
                                val response = withTimeout(180_000) { invocation.await() }
                                val usage = withTimeout(60_000) { invocation.terminal() }.usage
                                buildJsonObject {
                                    put("scenario", scenario); put("i", i); put("stop", response.stop.name)
                                    put("usage", usage?.quantities?.toString() ?: "")
                                    put("items", JsonArray(response.items.map { item(it) }))
                                    put("flags", JsonArray(response.items.filterIsInstance<ToolCall>().flatMap { flags(it) }.map(::JsonPrimitive)))
                                }
                            }
                        } catch (failure: Throwable) {
                            buildJsonObject { put("scenario", scenario); put("i", i); put("error", failure.toString()) }
                        }
                        val line = json.encodeToString(JsonObject.serializer(), record)
                        log.write(line); log.newLine(); log.flush()
                        println("[probe] $scenario#$i ${record["stop"] ?: record["error"]} flags=${record["flags"]}")
                    }
                }
            }
        }
        handler.close()
    }

    private fun item(item: Item): JsonObject = buildJsonObject {
        when (item) {
            is ToolCall -> { put("type", "tool_call"); put("id", item.id); put("name", item.name); put("argsJson", item.argsJson) }
            is Message -> { put("type", "message"); put("role", item.role.name); put("text", item.text) }
            is ReasoningRef -> { put("type", "reasoning"); put("text", (item.opaque as? JsonObject)?.get("text")?.toString()?.take(400) ?: "") }
            is ToolResult -> { put("type", "tool_result") }
            else -> { put("type", item::class.simpleName ?: "item") }
        }
    }

    /** Deterministic markers of the corruption seen in the diagnostics. */
    private fun flags(call: ToolCall): List<String> {
        val flags = ArrayList<String>()
        val parsed = runCatching { json.parseToJsonElement(call.argsJson) }.getOrNull()
        if (parsed !is JsonObject) flags += "${call.name}: args not a JSON object"
        if ("<arg_key>" in call.argsJson || "<arg_value>" in call.argsJson) flags += "${call.name}: native GLM tool tokens leaked"
        if (parsed is JsonObject) {
            parsed.keys.filter { key -> key.any { it == '<' || it == '>' || it == '\n' || it == '\r' } }.forEach { flags += "${call.name}: garbled key '$it'" }
            if (call.name in setOf("state", "task", "kb") && "op" !in parsed) flags += "${call.name}: missing op"
            if (call.name == "look" && "what" !in parsed) flags += "look: missing what"
            (parsed["patch"] as? JsonPrimitive)?.let { flags += "state: patch sent as a string" }
        }
        return flags
    }
}

/** The three replayed situations, rendered with the real `[S]` text, tool schemas and result delimiters. */
private object Scenarios {
    private val mask: ToolMask = ToolMask(Roles.plan.toolMask.allowed.filter { Roles.shapeMask(Shape.S1).allows(it) }.toSet())
    private val system: String = Layout.system(Roles.plan, mask, ExecutionMode.TrustedLocal)
    private val tools = ToolFamily.entries.map { ToolSchemas.schema(it) }

    private const val PRIME = "[R] repository · 1131 files · packages: client (npm), server (gradle), play3\n" +
        "tree (depth 2): .gitignore README.md build.gradle.kts gradle.properties gradlew gradlew.bat settings.gradle.kts " +
        "client/ (package.json index.html vite.config.ts src/) server/ (build.gradle.kts src/) play3/ devtools/ (jdk-26.0.2.1+1/ gradle-9.7.1/)\n" +
        "commands: root: ./gradlew.bat test · client: npm test · server: ../gradlew.bat test\n" +
        "Commands run on Windows 10 amd64 without a shell: Unix programs such as python3, ls, which or xdg-open may be missing.\n"

    private const val CONTRACT = "[K] contract v1 · increment plan\n" +
        "R1: в папке 'devtools' находится Java и gradle , используй их для проверки, компиляции, тестов и исправлений. проверь что проект рабочий  accept: AC-1, AC-2, AC-3\n" +
        "AC-1 (Harness, v1): run: ./gradlew.bat test\n" +
        "AC-2 (Harness, v1): run: npm test  cwd: client\n" +
        "AC-3 (Harness, v1): run: ../gradlew.bat test  cwd: server\n"

    private const val PINNED = "[Context from earlier in this task. Background only: it is not a new requirement and nothing in it has to be redone.]\n" +
        "Earlier request: создай клиент серверное приложение где клиент хорошо структурированное модульное react приложение, а сервер spring boot на Java …\n" +
        "Outcome: finished and verified. Summary: ## What I built **Server — Spring Boot on Java 21, built by Gradle** (`server/`, 57 main sources + 4 test classes) …\n" +
        "Files changed so far: .gitignore, README.md, build.gradle.kts, client/.env.example, client/index.html, client/package.json, client/src/app/App.tsx\n" +
        "[End of context]\n\n" +
        "в папке 'devtools' находится Java и gradle , используй их для проверки, компиляции, тестов и исправлений. проверь что проект рабочий\n\n" +
        "Working notes: if the request is a question or a greeting and needs no change to the files, write your answer and end the task with the task tool, op \"answer\", {\"text\": your answer}: do not make a plan and do not call other tools without need. " +
        "Otherwise keep your notes with the state tool, op \"patch\". Every item of \"patch\" is an object with exactly one of these keys and nothing else: " +
        "\"plan.add\" {\"text\"}, \"plan.tick\" {\"n\", \"evidence\"}, \"plan.cancel\" {\"n\", \"reason\"}, \"plan.cursor\" n, \"fact.add\" {\"kind\", \"text\", \"evidence\"} where kind is \"v\" for what a tool result showed (evidence names that result, \"op:1\" for the first call of the same turn or an alias such as \"#2\") and \"h\" for an assumption, " +
        "\"decision.add\" {\"text\", \"because\", \"rejected\"}, \"open.add\" {\"text\"}, \"focus.set\" {\"dir\"}, \"next\" \"text\". " +
        "Example: [{\"plan.add\":{\"text\":\"write the file\"}},{\"plan.tick\":{\"n\":1,\"evidence\":\"op:1\"}},{\"next\":\"run the checks\"}]. " +
        "When the work is done and checked, reply with a short summary and no tool call: that proposes completion (op \"answer\" is only for a request that changes no file). " +
        "If one part cannot be done here (for example opening a browser), finish the rest and say so in that summary instead of stopping as blocked. " +
        "Commands run without a shell: pass the program and its arguments, not \"sh -c\". " +
        "The user reads what you write in messages: use plain words, say what you changed and how you checked it, and leave the ids of requirements, acceptance items and notes out of them.\n" +
        "The result is checked with ./gradlew.bat test, npm test and ../gradlew.bat test."

    private const val GAUGE = "gauge: context 9% · budget 2% · checks none · workset 1 file"

    private fun result(text: String, error: Boolean = false): String =
        (if (error) "${Boundary.RESULT_OPEN}not executed: $text${Boundary.RESULT_CLOSE}" else text) + "\n" + GAUGE

    private val turnsSoFar: List<Item> = listOf(
        ToolCall("call_probe_1", "state", """{"op":"patch","patch":[{"plan.add":{"text":"Run root build test suite with the bundled toolchain: ./gradlew.bat test","accept":"AC-1"}},{"plan.add":{"text":"Run client checks: npm test (cwd client)","accept":"AC-2"}},{"plan.add":{"text":"Run server tests: ../gradlew.bat test (cwd server)","accept":"AC-3"}},{"next":"run the root test suite"}]}"""),
        ToolResult("call_probe_1", listOf(Text(result("${Boundary.RESULT_OPEN}result #- tool=state class=R truncated=no effects=none status=ok${Boundary.RESULT_CLOSE}")))),
        ToolCall("call_probe_2", "look", """{"what":"read","target":"gradle.properties"}"""),
        ToolResult("call_probe_2", listOf(Text(result("${Boundary.RESULT_OPEN}result #1 tool=look class=R v={gradle.properties: 4627} truncated=no effects=none status=ok${Boundary.RESULT_CLOSE}\norg.gradle.jvmargs=-Xmx2g\norg.gradle.caching=true\n")))),
        ToolCall("call_probe_3", "run", """{"argv":["./gradlew.bat","test","-Dorg.gradle.java.home=devtools/jdk-26.0.2.1+1"],"intent":"AC-1 root test suite with bundled JDK","timeout":600}"""),
        ToolResult("call_probe_3", listOf(Text(result("run.run is masked in this turn; no call of this turn executed", error = true))), isError = true),
    )

    private fun anchor(turn: Int, withState: Boolean, extra: String = ""): String = buildString {
        append("[A] contract v1 · increment plan · turn $turn/40\n")
        if (withState) {
            append("STATE v1\nPlan\n")
            append(" 1 [>] Run root build test suite with the bundled toolchain: ./gradlew.bat test  accept: AC-1\n")
            append(" 2 [ ] Run client checks: npm test (cwd client)  accept: AC-2\n")
            append(" 3 [ ] Run server tests: ../gradlew.bat test (cwd server)  accept: AC-3\n")
            append("Next: run the root test suite\nWorkset: gradle.properties@4627\n")
        } else append("STATE v0 (empty)\nWorkset: none\n")
        append("Checks: none\n").append(GAUGE).append('\n')
        if (withState) append("nudges:\n  stall: 3 turns without progress — re-read the plan · zoom out · run the pending decision probe · surface the blocker · or request a probe cell\n")
        append(extra)
    }

    fun request(scenario: String, profile: Profile): Request {
        val bp = profile.capabilities.caching.breakpoints
        val (items, anchor) = when (scenario) {
            "masked-run" -> turnsSoFar to anchor(4, withState = true)
            "blocked-explicit" -> turnsSoFar to anchor(4, withState = true, extra = "Execution is not available to this role. Record the blocker now with the state tool, op \"blocked\": reason, evidence (one entry per refused call) and a question for the parent.\n")
            "first-turn" -> emptyList<Item>() to anchor(1, withState = false)
            else -> throw IllegalArgumentException("unknown scenario $scenario")
        }
        val transcript: List<Item> = listOf(Message.text(Role.User, PINNED)) + items
        return Request(
            listOf(
                Segment(SegmentKind.S, listOf(Message.text(Role.System, system)), bp),
                Segment(SegmentKind.R, listOf(Message.text(Role.User, PRIME)), bp),
                Segment(SegmentKind.K, listOf(Message.text(Role.User, CONTRACT)), bp),
                Segment(SegmentKind.T, transcript, bp),
                Segment(SegmentKind.A, listOf(Message.text(Role.User, anchor))),
            ),
            tools, profile, Effort.High, 4_096,
        )
    }
}
