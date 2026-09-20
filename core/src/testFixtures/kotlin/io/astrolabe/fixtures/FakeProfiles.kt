package io.astrolabe.fixtures

import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.CacheCapability
import io.astrolabe.provider.Capabilities
import io.astrolabe.provider.LatencyClass
import io.astrolabe.provider.PriceTable
import io.astrolabe.provider.Profile
import io.astrolabe.provider.SchemaDialect
import java.math.BigDecimal
import java.time.LocalDate

/** Fake main/helper/escalation profiles with dated price tables and declared limits (TODO P0.3.5). */
public object FakeProfiles {
    public const val PROVIDER: String = "fake"

    public val dimensions: Set<BillingDimension> = setOf(
        BillingDimension.UNCACHED_INPUT,
        BillingDimension.CACHE_READ,
        BillingDimension.CACHE_WRITE_5M,
        BillingDimension.CACHE_WRITE_1H,
        BillingDimension.OUTPUT,
    )

    public val dialects: Set<SchemaDialect> = setOf(SchemaDialect.JSON_SCHEMA_2020_12)

    public fun prices(input: String, cacheRead: String, write5m: String, write1h: String, output: String): PriceTable = PriceTable(
        date = LocalDate.of(2026, 9, 1),
        currency = "USD",
        perMillion = mapOf(
            BillingDimension.UNCACHED_INPUT to BigDecimal(input),
            BillingDimension.CACHE_READ to BigDecimal(cacheRead),
            BillingDimension.CACHE_WRITE_5M to BigDecimal(write5m),
            BillingDimension.CACHE_WRITE_1H to BigDecimal(write1h),
            BillingDimension.OUTPUT to BigDecimal(output),
        ),
    )

    public fun capabilities(
        contextLimitTokens: Int,
        outputLimitTokens: Int,
        dialects: Set<SchemaDialect> = this.dialects,
        continuation: Boolean = true,
        breakpoints: Boolean = true,
    ): Capabilities = Capabilities(
        toolSchemaValidation = true,
        parallelToolCalls = true,
        streaming = true,
        outputLimitTokens = outputLimitTokens,
        contextLimitTokens = contextLimitTokens,
        nativeCompaction = continuation,
        continuation = continuation,
        cancellation = true,
        hostedExecution = false,
        caching = CacheCapability(
            breakpoints = breakpoints,
            maxBreakpoints = if (breakpoints) 4 else null,
            minimumTokens = if (breakpoints) 64 else null,
            writeClasses = setOf(BillingDimension.CACHE_WRITE_5M, BillingDimension.CACHE_WRITE_1H),
        ),
        usageFields = dimensions,
        schemaDialects = dialects,
    )

    public val main: Profile = Profile("main", PROVIDER, "fake-main", capabilities(200_000, 16_000), prices("3", "0.30", "3.75", "6", "15"))

    public val helper: Profile = Profile(
        "helper", PROVIDER, "fake-helper", capabilities(100_000, 8_000),
        prices("0.8", "0.08", "1", "1.6", "4"), latency = LatencyClass.Fast,
    )

    public val escalation: Profile = Profile(
        "escalation", PROVIDER, "fake-escalation", capabilities(400_000, 32_000),
        prices("15", "1.5", "18.75", "30", "75"), latency = LatencyClass.Slow,
    )

    /** A profile whose provider validates only the strict dialect (IX-24). */
    public val strictOnly: Profile = Profile(
        "strict", PROVIDER, "fake-strict", capabilities(200_000, 16_000, dialects = setOf(SchemaDialect.OPENAI_STRICT)),
        main.priceTable,
    )

    /** A small-window profile for admission and pressure tests. */
    public val tiny: Profile = Profile("tiny", PROVIDER, "fake-tiny", capabilities(2_000, 500, breakpoints = false, continuation = false), main.priceTable)

    public val all: Map<String, Profile> = listOf(main, helper, escalation, strictOnly, tiny).associateBy { it.id }
}
