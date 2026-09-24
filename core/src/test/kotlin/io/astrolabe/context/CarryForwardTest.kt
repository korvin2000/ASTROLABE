package io.astrolabe.context

import io.astrolabe.evidence.Anchor
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.register.AmendmentLine
import io.astrolabe.register.DeadEnd
import io.astrolabe.register.Decision
import io.astrolabe.register.Fact
import io.astrolabe.register.Mark
import io.astrolabe.register.OpenItem
import io.astrolabe.register.Register
import io.astrolabe.register.Step
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workspace.Ranges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P2.4.1 `CarryForward` (§6.2): FX-11 — amendments and scoped dead ends survive a rollover; KNOWN = seeds only, declared. */
class CarryForwardTest {
    private fun v(text: String) = FileVersion(Digest.ofUtf8(text))

    private val versions = mutableMapOf(
        "src/router.py" to v("router-2"),
        "src/handlers.py" to v("handlers-1"),
        "src/pay/fees.py" to v("fees-1"),
        "src/unrelated.py" to v("unrelated-1"),
        "src/big.py" to v("big-1"),
    )

    private fun entry(path: String, version: FileVersion, tokens: Long = 300) =
        Entry(path, Ranges.single(1, 40), version, EntrySource.Look, 3, "#4", tokens)

    private val register = Register(
        version = 9,
        cell = ContextId("cell-1"),
        increment = "I2",
        incrementTitle = "route the retry path",
        plan = listOf(
            Step(1, Mark.Done, "reproduce in tests/test_router.py", evidence = "#3"),
            Step(2, Mark.Cursor, "edit handlers.py so retry reaches router.py; also check big.py"),
        ),
        facts = listOf(
            Fact(1, ClaimKind.Verified, "router dispatches by name", Anchor("src/router.py", v("router-1")), "#5"),
            Fact(2, ClaimKind.Verified, "handlers are pure", Anchor("src/handlers.py", v("handlers-1")), "#6"),
            Fact(3, ClaimKind.Refuted, "the cache causes the race", refutedBy = "#7"),
            Fact(4, ClaimKind.Verified, "fees round half-up", evidenceId = "#gone"),
        ),
        deadEnds = listOf(DeadEnd(1, "monkeypatching the clock", "#8", "tests/", "fixtures become isolated")),
        decisions = listOf(Decision(1, "pass ctx explicitly", "tests construct handlers", "contextvar", adrCandidate = true)),
        open = listOf(OpenItem(1, "CLI path?", trip = "src/cli/**"), OpenItem(2, "closed item", closed = true, closedEvidence = "#9")),
        focus = "src/pay",
        amendments = listOf(AmendmentLine("drop AC-3", "obsolete")),
        next = "edit handlers",
    )

    private fun carry(cap: Long = CarryForward.SEED_CAP_TOKENS) = CarryForward.carry(
        previous = register,
        export = listOf(
            entry("src/router.py", v("router-1")),
            entry("src/handlers.py", v("handlers-1")),
            entry("src/pay/fees.py", v("fees-1")),
            entry("src/unrelated.py", v("unrelated-1")),
            entry("src/big.py", v("big-1"), tokens = 4_100),
        ),
        packet = null,
        currentVersion = { versions[it] },
        evidenceExists = { it != "#gone" },
        receipts = listOf(CarriedReceipt("CHK-accept-AC-1", "rcpt-3", "stale")),
        pinned = listOf("Fix the retry path", "Also keep the old CLI flag (user amendment)"),
        seedCapTokens = cap,
    )

    @Test
    fun `a rollover keeps amendments, dead ends and rejected hypotheses and declares KNOWN as the seeds only (FX-11)`() {
        val c = carry()
        assertEquals(listOf("src/handlers.py", "src/pay/fees.py"), c.seeds.map { it.path }, "referenced by the next step or focus, at the current version")
        assertTrue(c.seeds.all { it.source == EntrySource.Seed })
        assertEquals(listOf("src/big.py" to "over the 4000-token seed budget", "src/router.py" to "changed"), c.notSeen.map { it.path to it.reason })
        assertTrue("src/unrelated.py" !in c.seeds.map { it.path } + c.notSeen.map { it.path }, "unreferenced entries are not carried")

        assertEquals(v("router-1"), c.register.fact(1)!!.staleAt, "a v fact whose anchor moved is tagged stale")
        assertEquals(null, c.register.fact(2)!!.staleAt)
        assertEquals(listOf(4), c.unresolvedEvidence)

        val k = c.render()
        assertTrue("Also keep the old CLI flag (user amendment)" in k)
        assertTrue("  - monkeypatching the clock · scope tests/ · reopen fixtures become isolated [#8]" in k, k)
        assertTrue("  - x the cache causes the race [#7]" in k)
        assertTrue("  - pass ctx explicitly because tests construct handlers · rejected contextvar (→ candidate ADR)" in k)
        assertTrue("  - drop AC-3 (pending)" in k)
        assertTrue("  - CLI path? · trip src/cli/**" in k)
        assertFalse("closed item" in k)
        assertTrue("Verification: CHK-accept-AC-1 rcpt-3 (stale)" in k)
        assertTrue(k.endsWith(c.known))
        assertTrue(c.known.startsWith("KNOWN: seeds only (2) · NOT SEEN: everything else; src/big.py:1-40 over the 4000-token seed budget"), c.known)
    }

    @Test
    fun `the carry is deterministic and never exceeds the seed budget`() {
        assertEquals(carry(), carry())
        val tight = carry(cap = 500)
        assertTrue(tight.seedTokens <= 500)
        assertEquals(listOf("src/handlers.py"), tight.seeds.map { it.path })
        assertTrue(tight.notSeen.any { it.path == "src/pay/fees.py" && it.reason.startsWith("over the 500-token") })
    }
}
