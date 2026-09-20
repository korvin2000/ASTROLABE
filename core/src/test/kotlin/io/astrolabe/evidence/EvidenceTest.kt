package io.astrolabe.evidence

import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.Ranges
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvidenceTest {
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"))
    private val stamp = CandidateId(Digest.ofUtf8("s1"))
    private val now = Instant.parse("2026-09-20T10:00:00Z")

    private fun receipt(outcome: Outcome, parsed: Counts?, stability: InputStability = InputStability.Exclusive, mutated: Set<String> = emptySet()) = Receipt(
        receiptId = "rcpt-1", ids = ids, checkId = "CHK-accept-AC-1", acceptanceIds = listOf("AC-1"),
        command = listOf("pytest", "-q"), cwd = null, shell = false, stampBefore = stamp, stampAfter = stamp,
        envId = Digest.ofUtf8("env"), verifierVersion = "0.1.0", checkDefinitionVersion = Digest.ofUtf8("def"),
        contractVersion = 1, outcome = outcome, parsed = parsed, inputClosure = Closure.Known(setOf("src/a.py")),
        testedInputs = TestedInputs(mapOf("src/a.py" to FileVersion.of(byteArrayOf(1))), stability, mutated),
        raw = Digest.ofUtf8("log"), at = now,
    )

    @Test
    fun `a passed receipt needs parsed counts with something executed and stable inputs`() {
        assertFailsWith<IllegalArgumentException> { receipt(Outcome.Passed, null) }
        assertFailsWith<IllegalArgumentException> { receipt(Outcome.Passed, Counts()) }
        val green = receipt(Outcome.Passed, Counts(passed = 3, discovered = 3))
        assertTrue(green.greenForFinalTree)
        assertFalse(receipt(Outcome.Passed, Counts(passed = 3), stability = InputStability.Unknown).greenForFinalTree)
        assertFalse(receipt(Outcome.Passed, Counts(passed = 3), mutated = setOf("src/a.py")).greenForFinalTree)
        assertFalse(receipt(Outcome.Inconclusive, Counts()).outcome.green)
        val text = Json.encodeToString(Receipt.serializer(), green)
        assertEquals(green, Json.decodeFromString(Receipt.serializer(), text))
    }

    @Test
    fun `observation coverage excludes redacted lines (D-49)`() {
        val observation = Observation(
            id = "obs-1", ids = ids, actionId = "act-1", candidate = stamp, contentRef = Digest.ofUtf8("view"),
            paths = listOf("src/a.py"), ranges = mapOf("src/a.py" to Ranges.single(10, 30)), complete = true,
            sourceVersions = mapOf("src/a.py" to FileVersion.of(byteArrayOf(1))), captureComplete = true,
            redaction = RedactionMask(Ranges.of(LineRange(15, 16), LineRange(20, 20)), listOf("regex set v1")),
        )
        assertEquals(Ranges.of(LineRange(10, 14), LineRange(17, 19), LineRange(21, 30)), observation.coverage("src/a.py"))
        assertEquals(Ranges.EMPTY, observation.coverage("src/b.py"))
        assertTrue(observation.redaction.applied)
    }

    @Test
    fun `ranges normalize, subtract and intersect`() {
        val r = Ranges.of(LineRange(5, 7), LineRange(1, 3), LineRange(4, 4), LineRange(10, 12))
        assertEquals("1-7,10-12", r.toString())
        assertTrue(r.covers(LineRange(2, 6)))
        assertFalse(r.covers(LineRange(6, 10)))
        assertEquals(Ranges.of(LineRange(1, 1), LineRange(7, 7), LineRange(10, 12)), r - Ranges.single(2, 6))
        assertEquals(Ranges.single(5, 7), r.intersect(Ranges.single(5, 9)))
        assertEquals(18, (r + Ranges.single(20, 27)).lines)
        assertFailsWith<IllegalArgumentException> { Ranges(listOf(LineRange(1, 3), LineRange(3, 5))) }
        assertFailsWith<IllegalArgumentException> { LineRange(0, 1) }
    }

    @Test
    fun `verified claims need evidence ids and aliases are campaign-global`() {
        assertFailsWith<IllegalArgumentException> { Claim("c1", "x", ClaimKind.Verified, EvidenceState.Supported, ClaimAuthority.Observed, Freshness.Current) }
        val aliases = InMemoryAliases()
        val a = aliases.allocate(WorkId("W-1"), "journal-9", "result", null, null)
        val b = aliases.allocate(WorkId("W-1"), "journal-12", "result", null, null)
        assertEquals("#1", a.text)
        assertEquals("#2", b.text)
        assertEquals(a, aliases.resolve(WorkId("W-1"), 1))
        assertEquals(b, aliases.byCanonical(WorkId("W-1"), "journal-12"))
        assertEquals(17, Aliases.parse("#17"))
        assertNull(Aliases.parse("17"))
        assertEquals("#1", aliases.allocate(WorkId("W-2"), "j", "result", null, null).text)
    }

    @Test
    fun `consequential ordering leaves a classifiable intent at every boundary (FX-23, FX-24)`() = runTest {
        val journal = InMemoryIntentJournal()
        val intent = Intent("int-1", ids, "act-1", listOf("pip", "install", "x"), null, "installs x", at = now)

        val refused = Consequential.run(journal, intent, reserve = { false }, dispatch = { "never" }, persist = {})
        assertIs<ActionOutcome.NotDispatched>(refused)
        assertNull(journal.get("int-1"))

        val crashed = Consequential.run(journal, intent, reserve = { true }, dispatch = { throw IllegalStateException("lost ack") }, persist = {})
        assertIs<ActionOutcome.Unknown>(crashed)
        assertEquals(IntentStatus.Unknown, journal.get("int-1")!!.status)
        assertEquals(listOf("int-1"), journal.open().map { it.intentId })

        val second = intent.copy(intentId = "int-2")
        val persistFailed = Consequential.run(journal, second, reserve = { true }, dispatch = { 42 }, persist = { throw IllegalStateException("disk") })
        assertIs<ActionOutcome.Unknown>(persistFailed)
        assertEquals(IntentStatus.Unknown, journal.get("int-2")!!.status)

        val third = intent.copy(intentId = "int-3")
        val ok = Consequential.run(journal, third, reserve = { true }, dispatch = { 7 }, persist = {})
        assertEquals(ActionOutcome.Completed(7), ok)
        assertEquals(IntentStatus.Committed, journal.get("int-3")!!.status)
        assertEquals(listOf("int-1", "int-2"), journal.open().map { it.intentId })
        assertFailsWith<IllegalArgumentException> { journal.update("int-3", IntentStatus.Recorded) }
    }
}
