package io.astrolabe.provider.aigate

import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.CacheCapability
import io.astrolabe.provider.Capabilities
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.PriceTable
import io.astrolabe.provider.Profile
import io.astrolabe.provider.Request
import io.astrolabe.provider.Role
import io.astrolabe.provider.SchemaDialect
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.ai.gate.Llm
import net.ai.gate.Provider
import net.ai.gate.auth.Environment
import net.ai.gate.json.JsonObject as GateObject
import net.ai.gate.spi.http.HttpCall
import net.ai.gate.spi.http.HttpReply
import net.ai.gate.spi.http.HttpTransport
import net.ai.gate.spi.http.TransportOptions
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** A scripted provider endpoint: records every call and answers with queued replies, so real presets and codecs run offline. */
class WireScript : HttpTransport {
    val calls: MutableList<HttpCall> = CopyOnWriteArrayList()
    private val replies = ConcurrentLinkedQueue<HttpReply>()

    fun json(status: Int, body: String): WireScript = reply(status, "application/json", body)

    /** One SSE event per data string. */
    fun sse(vararg data: String): WireScript = reply(200, "text/event-stream", data.joinToString("") { "data: $it\n\n" })

    private fun reply(status: Int, type: String, body: String): WireScript = apply {
        replies += HttpReply.of(status, mapOf("content-type" to listOf(type)), body.toByteArray(StandardCharsets.UTF_8))
    }

    override fun send(call: HttpCall, options: TransportOptions): HttpReply {
        calls += call
        return replies.poll() ?: throw AssertionError("no scripted reply for $call")
    }

    fun body(index: Int): GateObject = calls[index].body().orElseThrow() as GateObject

    /** A runtime with [provider] bound to this endpoint and a test key in [keyVariable]. */
    fun runtime(provider: Provider, keyVariable: String): Llm = Llm.builder().provider(provider.toBuilder().transport(this).build())
        .environment(Environment.of(mapOf(keyVariable to "test-key-0123456789"))).catalog { it.offline() }.build()
}

object GateTestKit {
    val prices: PriceTable = PriceTable(
        LocalDate.of(2026, 9, 1), "USD",
        mapOf(
            BillingDimension.UNCACHED_INPUT to BigDecimal("3"), BillingDimension.CACHE_READ to BigDecimal("0.3"),
            BillingDimension.CACHE_WRITE_5M to BigDecimal("3.75"), BillingDimension.CACHE_WRITE_1H to BigDecimal("6"),
            BillingDimension.OUTPUT to BigDecimal("15"),
        ),
    )

    fun capabilities(
        context: Int,
        output: Int,
        breakpoints: Boolean,
        usage: Set<BillingDimension> = setOf(BillingDimension.UNCACHED_INPUT, BillingDimension.CACHE_READ, BillingDimension.OUTPUT),
        continuation: Boolean = false,
    ): Capabilities = Capabilities(
        toolSchemaValidation = true, parallelToolCalls = true, streaming = true, outputLimitTokens = output, contextLimitTokens = context,
        nativeCompaction = false, continuation = continuation, cancellation = true, hostedExecution = false,
        caching = CacheCapability(breakpoints, if (breakpoints) 4 else null, if (breakpoints) 1024 else null,
            if (breakpoints) setOf(BillingDimension.CACHE_WRITE_5M, BillingDimension.CACHE_WRITE_1H) else emptySet()),
        usageFields = usage, schemaDialects = setOf(SchemaDialect.JSON_SCHEMA_2020_12),
    )

    fun gate(json: String): JsonObject = Json.parseToJsonElement("""{"gate":$json}""") as JsonObject

    /** The SDK `FakeProvider`'s model `fake` (128 000 context, 4 096 output, automatic caching). */
    fun fakeProfile(id: String = "main", config: JsonObject = JsonObject(emptyMap()), output: Int = 4_000, context: Int = 128_000): Profile =
        Profile(id, "fake", "fake", capabilities(context, output, breakpoints = false), prices, config)

    val sonnetUsage: Set<BillingDimension> = setOf(
        BillingDimension.UNCACHED_INPUT, BillingDimension.CACHE_READ, BillingDimension.CACHE_WRITE_5M, BillingDimension.CACHE_WRITE_1H, BillingDimension.OUTPUT,
    )

    fun sonnetProfile(config: JsonObject = JsonObject(emptyMap()), output: Int = 32_000): Profile =
        Profile("sonnet", "anthropic", "claude-sonnet-4-5", capabilities(200_000, output, breakpoints = true, usage = sonnetUsage), prices, config)

    val lookSchema: ToolSchema = ToolSchema(
        "look", "Observe the repository",
        Json.parseToJsonElement("""{"type":"object","properties":{"what":{"type":"string"},"target":{"type":"string"}},"required":["what"]}""") as JsonObject,
        SchemaDialect.JSON_SCHEMA_2020_12,
    )

    /** `[S][R][K]` fixed text, `[T]` items and an `[A]` anchor, each cached region closed by a breakpoint when [breakpoints]. */
    fun request(profile: Profile, transcript: List<Item> = emptyList(), breakpoints: Boolean = profile.capabilities.caching.breakpoints, maxOutput: Int = 1_000, effort: Effort = Effort.Medium): Request =
        Request(
            segments = buildList {
                add(Segment(SegmentKind.S, listOf(Message.text(Role.System, "You operate a coding harness.")), breakpoints))
                add(Segment(SegmentKind.R, listOf(Message.text(Role.User, "repository prime")), breakpoints))
                add(Segment(SegmentKind.K, listOf(Message.text(Role.User, "contract slice")), breakpoints))
                if (transcript.isNotEmpty()) add(Segment(SegmentKind.T, transcript, breakpoints))
                add(Segment(SegmentKind.A, listOf(Message.text(Role.User, "anchor: turn 1"))))
            },
            tools = listOf(lookSchema), profile = profile, effort = effort, maxOutputTokens = maxOutput,
        )
}

/** A scripted endpoint routed by method and path suffix, for flows that also list models or probe the base URL. */
class RouteScript : HttpTransport {
    val calls: MutableList<HttpCall> = CopyOnWriteArrayList()
    private val routes = CopyOnWriteArrayList<Triple<String, String, ConcurrentLinkedQueue<HttpReply>>>()

    fun json(method: String, suffix: String, body: String, status: Int = 200): RouteScript =
        add(method, suffix, HttpReply.of(status, mapOf("content-type" to listOf("application/json")), body.toByteArray(StandardCharsets.UTF_8)))

    fun sse(method: String, suffix: String, vararg data: String): RouteScript =
        add(method, suffix, HttpReply.of(200, mapOf("content-type" to listOf("text/event-stream")), data.joinToString("") { "data: $it\n\n" }.toByteArray(StandardCharsets.UTF_8)))

    private fun add(method: String, suffix: String, reply: HttpReply): RouteScript = apply {
        val route = routes.firstOrNull { it.first == method && it.second == suffix } ?: Triple(method, suffix, ConcurrentLinkedQueue<HttpReply>()).also { routes += it }
        route.third += reply
    }

    override fun send(call: HttpCall, options: TransportOptions): HttpReply {
        calls += call
        val path = call.uri().toString().substringBefore('?').trimEnd('/')
        val route = routes.firstOrNull { it.first == call.method() && path.endsWith(it.second.trimEnd('/')) && it.third.isNotEmpty() }
        return route?.third?.poll() ?: throw AssertionError("no scripted reply for ${call.method()} ${call.uri()}")
    }

    fun posts(): List<GateObject> = calls.filter { it.method() == "POST" }.map { it.body().orElseThrow() as GateObject }
}
