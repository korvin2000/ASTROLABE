package io.astrolabe

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultsTest {
    /** The §17 table (docs/reference/defaults.md), row label → Defaults fields that carry it. */
    private val table = mapOf(
        "Shape" to listOf("shapePolicy"),
        "Cell turn budget" to listOf("turnsPerCell", "turnNudgeFraction"),
        "α pressure threshold" to listOf("alpha"),
        "k eviction batch / m turns kept on rebuild" to listOf("k", "m"),
        "R_max total live results / [A] max" to listOf("rMaxTokens", "anchorMaxTokens"),
        "Immediate-stub threshold for stale reads" to listOf("immediateStubTokens"),
        "look.budget / run.budget" to listOf("lookBudgetTokens", "runBudgetTokens"),
        "Register cap / contract digest cap / patch cap" to listOf("registerCapTokens", "digestCapTokens", "patchCapTokens"),
        "Fact line / note body / note summary" to listOf("factLineMaxChars", "noteBodyMaxTokens", "noteSummaryMaxChars"),
        "Workset seeds per cell / KB injection / focus notes / focus zoom" to
            listOf("seedsMaxTokens", "injectionMaxNotes", "injectionMaxTokens", "focusNotesMaxTokens", "focusZoomMaxTokens"),
        "Touched ledger in [A]" to listOf("touchedInAnchor"),
        "Checker time box" to listOf("checkerTimeBoxSeconds"),
        "θ risk threshold for early slow checks" to listOf("theta"),
        "Full-suite cadence" to listOf("fullSuiteCadence"),
        "Reserves" to listOf("reserveVerification", "reserveRecoveryAndPersist", "campaignRecoveryReserve"),
        "Stall / loop / repeated signature / doom-loop guard" to listOf("stallTurns", "loopIdentical", "repeatedSignatureRepairs", "doomLoopSameCalls"),
        "Probe cell" to listOf("probeTurns", "probeTokens", "probeTier"),
        "Review cell" to listOf("reviewLookMax", "reviewIncrementTokens", "reviewCampaignTokens", "reviewTier", "reviewRoutineTier"),
        "Repair helper / substantive attempts per increment / delegation depth / parallel cells" to
            listOf("repairCalls", "attemptsPerIncrement", "writerDepth", "probeDepth", "parallelCells"),
        "Campaign cells" to listOf("campaignCells"),
        "Flaky policy" to listOf("flakyIsolatedReruns"),
        "Memory admission" to listOf("admissionConfidenceMax"),
        "Profiles" to listOf("profileRoles"),
        "Mode" to listOf("mode", "executionMode", "dClass", "ceiling"),
        "Timeouts" to listOf("runTimeoutSeconds"),
    )

    private val fields = Defaults::class.java.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .map { it.name }
        .toSet()

    @Test
    fun `every §17 row maps to existing fields and every field belongs to a row`() {
        val mapped = table.values.flatten()
        assertEquals(mapped.size, mapped.toSet().size, "a field is listed twice")
        val missing = mapped.filter { it !in fields }
        assertTrue(missing.isEmpty(), "rows name fields that do not exist: $missing")
        val unowned = fields - mapped.toSet()
        assertTrue(unowned.isEmpty(), "fields without a §17 row: $unowned")
        assertEquals(25, table.size)
    }

    @Test
    fun `declared values match the table`() {
        val d = Defaults()
        assertEquals(40, d.turnsPerCell)
        assertEquals(0.65, d.alpha)
        assertEquals(8, d.k)
        assertEquals(6, d.m)
        assertEquals(16_000, d.rMaxTokens)
        assertEquals(2_500, d.anchorMaxTokens)
        assertEquals(800, d.immediateStubTokens)
        assertEquals(1_500, d.lookBudgetTokens)
        assertEquals(1_200, d.runBudgetTokens)
        assertEquals(1_200, d.registerCapTokens)
        assertEquals(150, d.digestCapTokens)
        assertEquals(400, d.patchCapTokens)
        assertEquals(20, d.checkerTimeBoxSeconds)
        assertEquals(40, d.theta)
        assertEquals(5, d.fullSuiteCadence)
        assertEquals(0.15, d.reserveVerification)
        assertEquals(0.05, d.reserveRecoveryAndPersist)
        assertEquals(0.10, d.campaignRecoveryReserve)
        assertEquals(12, d.campaignCells)
        assertEquals(120, d.runTimeoutSeconds)
        assertEquals(2, d.attemptsPerIncrement)
        assertTrue(d.violations().isEmpty())
    }

    @Test
    fun `every optional flag defaults off`() {
        val flags = Flags()
        val on = Flags::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .onEach { it.isAccessible = true }
            // A boolean flag is off when false; a multi-arm flag (e.g. `kbInjection`, P4.1.3) when its arm is `Off`.
            .filter { f -> if (f.type == java.lang.Boolean.TYPE) f.getBoolean(flags) else (f.get(flags) as Enum<*>).name != "Off" }
            .map { it.name }
        assertTrue(on.isEmpty(), "flags on by default: $on")
    }
}
