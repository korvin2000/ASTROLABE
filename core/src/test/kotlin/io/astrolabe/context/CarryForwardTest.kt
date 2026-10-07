package io.astrolabe.context

import io.astrolabe.cell.CellPacket
import io.astrolabe.cell.ChangeOrigin
import io.astrolabe.cell.PacketChange
import io.astrolabe.cell.PacketStatus
import io.astrolabe.cell.Protocol
import io.astrolabe.cell.TouchKind
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
import kotlin.test.assertNull
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

    private fun carry(cap: Long = CarryForward.SEED_CAP_TOKENS, fallback: Boolean = false) = CarryForward.carry(
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
        fallback = fallback,
    )

    private val stored = CellPacket(
        ContextId("cell-1"), "I2", "implementing", PacketStatus.Partial, "TurnBudget: spent", 9, 1, null, null, null, register,
        listOf(
            PacketChange("src/handlers.py", TouchKind.Modified, v("handlers-0"), v("handlers-1"), ChangeOrigin.Edit),
            PacketChange("src/pay/fees.py", TouchKind.Modified, v("fees-0"), v("fees-1"), ChangeOrigin.Edit),
        ),
        listOf("rcpt-3"), listOf("gap one"),
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

    @Test
    fun `every carry is headed as data and a stored packet row gives the packet line and the touched ledger`() {
        val c = CarryForward.carry(register, emptyList(), null, { versions[it] }, { true }, emptyList(), emptyList(), stored = stored)
        assertEquals("partial (TurnBudget: spent) · gaps: gap one · receipts: rcpt-3", c.packetLine)
        assertEquals(listOf(CarriedTouch("src/handlers.py", v("handlers-1")), CarriedTouch("src/pay/fees.py", v("fees-1"))), c.touched)
        assertTrue(c.render().startsWith(Carry.DATA_HEADING + "\nCARRY-FORWARD from cell-1 (STATE v9)\n"), c.render())
        assertFalse(Regex("\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(c.render()), "no wall-clock in a carried block")
    }

    @Test
    fun `with no candidate from the attempt's rule the carry falls back to Seeds v2 and says so, and a v1 selection keeps its bytes`() {
        val planless = register.copy(plan = emptyList(), next = null, focus = null)
        val export = listOf(entry("src/router.py", v("router-2")), entry("src/handlers.py", v("handlers-1")))
        val none = CarryForward.carry(planless, export, null, { versions[it] }, { true }, emptyList(), emptyList(), touched = setOf("src/handlers.py"))
        assertTrue(none.seeds.isEmpty())
        assertNull(none.seedReason)
        assertEquals("v1", none.seedRule)
        val fell = CarryForward.carry(planless, export, null, { versions[it] }, { true }, emptyList(), emptyList(), touched = setOf("src/handlers.py"), fallback = true)
        assertEquals(listOf("src/handlers.py", "src/router.py"), fell.seeds.map { it.path }, "Seeds v2: touched first")
        assertEquals("fallback", fell.seedReason)
        assertEquals("v2", fell.seedRule)
        assertEquals(carry(), carry(fallback = true), "a rule that yields a candidate keeps its selection and bytes")
    }

    @Test
    fun `a parent carry holds decisions and dead ends longest under its cap and names what it cut`() {
        val tokens = { text: String -> text.length / 4L }
        val export = listOf(entry("src/handlers.py", v("handlers-1")), entry("src/pay/fees.py", v("fees-1")))
        val status = "boundary: cell_end · cell: cell-1\nverification: CHK-accept-AC-1 rcpt-3 (current)\nopen handles: (none)\narchived records: 0"
        fun parent(cap: Long) = CarryForward.parent("W-1", register, export, stored, { versions[it] }, { true },
            listOf(CarriedReceipt("CHK-accept-AC-1", "rcpt-3", "current")), status, cap, tokens)
        val full = parent(100_000)
        assertEquals("parent W-1 · cell-1", full.source)
        assertTrue(full.cut.isEmpty())
        val k = full.render()
        assertTrue(k.startsWith(Carry.DATA_HEADING) && "STATUS:" in k && "Decisions:" in k && "Dead ends:" in k, k)
        assertFalse("reproduce in tests" in k || "router dispatches by name" in k, "the parent's plan and facts stay home: $k")
        assertEquals(listOf("src/handlers.py", "src/pay/fees.py"), full.seeds.map { it.path }, "the parent's touched paths are seeds")

        val target = full.copy(status = null, receipts = emptyList(), touched = emptyList(), register = full.register.copy(open = emptyList())).render()
        val tight = parent(tokens(target))
        assertEquals("STATUS note", tight.cut.first(), tight.cut.toString())
        assertTrue(tight.cut.any { it.startsWith("touched ledger") } && tight.cut.none { it.startsWith("decisions") || it.startsWith("dead ends") }, tight.cut.toString())
        assertTrue(tokens(tight.render()) <= tokens(target))
        assertEquals(tight, parent(tokens(target)), "deterministic")

        // WR2 (P2): a cap below the decisions still holds — whatever else the block renders is cut too, and named.
        val bare = full.copy(status = null, receipts = emptyList(), touched = emptyList(), notSeen = emptyList(), packetLine = null, pinned = emptyList(),
            register = full.register.copy(open = emptyList(), deadEnds = emptyList(), decisions = emptyList(), amendments = emptyList(), facts = emptyList())).render()
        val floor = parent(tokens(bare))
        assertTrue(tokens(floor.render()) <= tokens(bare), "the cap holds: ${floor.cut}")
    }

    @Test
    fun `a direct parent carry with note ids holds parentCarryMaxTokens as the direct block renders it`() {
        val tokens = { text: String -> text.length / 4L }
        // Many short decisions and open items: each direct line adds its id, so the direct block outgrows the structured one.
        val notes = register.copy(
            decisions = (1..24).map { Decision(it, "keep $it", "why $it", null) },
            open = (1..12).map { OpenItem(it, "open $it") },
        )
        fun parent(cap: Long, protocol: Protocol) = CarryForward.parent("W-1", notes, emptyList(), stored, { versions[it] }, { true },
            emptyList(), null, cap, tokens, protocol = protocol)
        val structured = parent(100_000, Protocol.Structured).render(Protocol.Structured)
        val direct = parent(100_000, Protocol.Direct).render(Protocol.Direct)
        assertTrue("d24 keep 24" in direct && "o12 open 12" in direct, direct)
        assertTrue(tokens(direct) > tokens(structured), "the direct block is the larger one here")
        val cap = tokens(structured)
        assertTrue(parent(cap, Protocol.Structured).cut.isEmpty(), "the structured block fits the cap whole")
        val capped = parent(cap, Protocol.Direct)
        assertTrue(capped.cut.isNotEmpty(), "the direct block is cut by its own render")
        assertTrue(tokens(capped.render(Protocol.Direct)) <= cap, "the direct block holds the cap: ${capped.cut}")
    }
}
