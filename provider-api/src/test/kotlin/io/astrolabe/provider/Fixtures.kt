package io.astrolabe.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate

/** Test-local fixtures; the shared FakeAdapter and profiles live in core test fixtures (P0.3.5). */
internal object Fixtures {
    val dims = setOf(
        BillingDimension.UNCACHED_INPUT,
        BillingDimension.CACHE_READ,
        BillingDimension.CACHE_WRITE_5M,
        BillingDimension.CACHE_WRITE_1H,
        BillingDimension.OUTPUT,
    )

    val prices = PriceTable(
        date = LocalDate.of(2026, 9, 1),
        currency = "USD",
        perMillion = mapOf(
            BillingDimension.UNCACHED_INPUT to BigDecimal("3"),
            BillingDimension.CACHE_READ to BigDecimal("0.30"),
            BillingDimension.CACHE_WRITE_5M to BigDecimal("3.75"),
            BillingDimension.CACHE_WRITE_1H to BigDecimal("6"),
            BillingDimension.OUTPUT to BigDecimal("15"),
        ),
    )

    val capabilities = Capabilities(
        toolSchemaValidation = true,
        parallelToolCalls = true,
        streaming = true,
        outputLimitTokens = 8_000,
        contextLimitTokens = 100_000,
        nativeCompaction = false,
        continuation = true,
        cancellation = true,
        hostedExecution = false,
        caching = CacheCapability(breakpoints = true, maxBreakpoints = 4, minimumTokens = 1024, writeClasses = setOf(BillingDimension.CACHE_WRITE_5M, BillingDimension.CACHE_WRITE_1H)),
        usageFields = dims,
        schemaDialects = setOf(SchemaDialect.JSON_SCHEMA_2020_12),
    )

    val profile = Profile("main", "fake", "fake-main", capabilities, prices)

    val provenance = UsageProvenance("fake", "fake-main", "fake/1")

    val schema = ToolSchema("look", "observe", JsonObject(mapOf("type" to JsonPrimitive("object"))), SchemaDialect.JSON_SCHEMA_2020_12)

    /** Four characters per token, declared margin of 10 %: exact=false always. */
    object CharEstimator : TokenEstimator {
        override val id = "chars4"
        override val version = "1"
        override fun estimate(text: String): Estimate {
            val tokens = (text.length + 3) / 4L
            return Estimate(tokens, exact = false, estimatorId = id, version = version, marginTokens = tokens / 10)
        }
    }

    fun request(vararg segments: Segment, tools: List<ToolSchema> = listOf(schema), maxOutput: Int = 1_000, continuation: OpaqueContinuation? = null) =
        Request(segments.toList(), tools, profile, Effort.Medium, maxOutput, continuation = continuation)
}
