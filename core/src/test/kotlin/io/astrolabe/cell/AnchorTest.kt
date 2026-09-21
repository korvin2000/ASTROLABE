package io.astrolabe.cell

import io.astrolabe.Defaults
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.provider.SegmentKind
import io.astrolabe.workspace.VersionChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P1.8.3: the volatile `[A]` tail — block order, per-block caps and the size metric (§5.1, §5.10). */
class AnchorTest {

    private val estimator = HeuristicEstimator()
    private val c02e = FileVersion(Digest("c02e1f9a".repeat(8)))
    private val d1e7 = FileVersion(Digest("d1e7b30c".repeat(8)))

    private val digest =
        "── CONTRACT v3 (S2) ── \"Add idempotency-key handling to POST /payments; public API unchanged.\"\n" +
            "R2 in_progress → AC-1 green @s41 STALE (closure moved) · AC-4 red #42 · exclusions: refund flow"
    private val register = "# STATE v15 · cell c1 · I2 \"thread ctx through handlers\"\n## Next       edit src/cli/main.py"
    private val workset = "KNOWN: src/router.py:80-96@a9f1 · NOT SEEN: everything else"
    private val checks = "── Checks @d1e7 ── types(touched): now 0 ✓ · tests(k ctx): 11 pass 1 fail #42"

    private fun touched() = listOf(
        Touched("src/handlers/user.py", TouchKind.Modified, added = 2, removed = 1, from = c02e, to = d1e7, note = "accept ctx", alias = "#41"),
    )

    @Test
    fun `blocks render in the 5_10 order with their labels`() {
        val anchor = Anchor.render(
            estimator, digest, register, workset, touched(), checks,
            focusZoom = "atlas src/cli/: 2 files\n  main.py \"entry\" handle_cli@31",
            gauge = "⟨ctx 38% · reserve ok · STATE v15 · turn 14/40⟩",
            nudges = listOf("── impact: `handle_user` signature changed; 3 references not inspected"),
            firedTrips = listOf("⟨trip Q1 fired: any edit under src/cli/ → check CLI path builds handlers⟩"),
        )

        assertEquals(
            """
            ── CONTRACT v3 (S2) ── "Add idempotency-key handling to POST /payments; public API unchanged."
            R2 in_progress → AC-1 green @s41 STALE (closure moved) · AC-4 red #42 · exclusions: refund flow
            # STATE v15 · cell c1 · I2 "thread ctx through handlers"
            ## Next       edit src/cli/main.py
            ── Workset  KNOWN: src/router.py:80-96@a9f1 · NOT SEEN: everything else
            ── Touched  M src/handlers/user.py (+2 −1) @c02e→d1e7 "accept ctx" #41
            ── Checks @d1e7 ── types(touched): now 0 ✓ · tests(k ctx): 11 pass 1 fail #42
            ── Focus    atlas src/cli/: 2 files
                          main.py "entry" handle_cli@31
            ⟨ctx 38% · reserve ok · STATE v15 · turn 14/40⟩
            ── impact: `handle_user` signature changed; 3 references not inspected
            ⟨trip Q1 fired: any edit under src/cli/ → check CLI path builds handlers⟩

            """.trimIndent(),
            anchor.text,
        )
        assertTrue(anchor.reductions.isEmpty(), "nothing needed reducing: ${anchor.reductions}")
        assertFalse(anchor.overBudget)
        assertEquals(estimator.estimate(anchor.text).tokens, anchor.tokens, "the [A] size is measured, not guessed")
    }

    @Test
    fun `an empty block is omitted rather than rendered as a heading`() {
        val anchor = Anchor.render(estimator, digest, register, workset = "", touched = emptyList(), checks = "")

        assertFalse(anchor.text.contains("── Workset"), anchor.text)
        assertFalse(anchor.text.contains("── Touched"), anchor.text)
        assertTrue(anchor.text.startsWith("── CONTRACT v3"), anchor.text)
    }

    @Test
    fun `each block is capped on its own, dropping whole lines and saying how many`() {
        val fatRegister = (1..400).joinToString("\n") { "- fact $it: the router normalizes the currency code before dispatch" }
        val defaults = Defaults()
        val anchor = Anchor.render(estimator, digest, fatRegister, workset, touched(), checks, defaults = defaults)

        val stateLines = anchor.text.lineSequence().filter { it.startsWith("- fact ") }.toList()
        assertTrue(stateLines.isNotEmpty() && stateLines.size < 400, "STATE was capped, not dropped: ${stateLines.size}")
        assertTrue(anchor.text.contains("… +"), "the cap says how many lines it dropped")
        assertTrue(
            anchor.reductions.any { it.startsWith("STATE: capped at ${defaults.registerCapTokens} tokens") },
            "every reduction is named: ${anchor.reductions}",
        )
        // A capped block ends on a line boundary.
        assertTrue(anchor.text.lineSequence().none { it.isNotEmpty() && it.endsWith("disp") }, anchor.text)
    }

    @Test
    fun `the touched ledger keeps the most recent entries`() {
        val many = (1..25).map { Touched("src/f$it.py", TouchKind.Modified, alias = "#$it") }
        val anchor = Anchor.render(estimator, digest, register, workset, many, checks)

        assertTrue(anchor.text.contains("M src/f25.py #25"), "the newest entry is shown")
        assertFalse(anchor.text.contains("M src/f15.py"), "the 16th-newest is not")
        assertEquals(Defaults().touchedInAnchor, anchor.text.lineSequence().count { it.contains("M src/f") })
        assertTrue(anchor.reductions.any { it.startsWith("Touched: showed the last 10 of 25") }, anchor.reductions.toString())
    }

    @Test
    fun `an oversized anchor sheds the optional blocks in the declared order and reports what is left`() {
        val fatRegister = (1..600).joinToString("\n") { "- fact $it: a long claim about the currency router and its handlers" }
        val small = Defaults(anchorMaxTokens = 400)
        val anchor = Anchor.render(
            estimator, digest, fatRegister, workset, touched(), checks,
            focusZoom = "atlas src/cli/: 2 files",
            focusNotes = "CON-007 handler signature contract (v3)",
            gauge = "⟨ctx 38% · turn 14/40⟩",
            nudges = listOf("── impact: 3 references not inspected"),
            defaults = small,
        )

        val order = anchor.reductions.filter { it.contains("dropped, the anchor was over") }
        assertEquals(
            listOf(
                "focus notes: dropped, the anchor was over 400 tokens",
                "focus zoom: dropped, the anchor was over 400 tokens",
            ),
            order,
            "the optional blocks go first, in the declared order",
        )
        // What a cell must not miss survives every reduction.
        assertTrue(anchor.text.startsWith("── CONTRACT v3"), anchor.text.take(80))
        assertTrue(anchor.text.contains("── Checks @d1e7"), "checks are never reduced")
        assertTrue(anchor.text.contains("⟨ctx 38% · turn 14/40⟩"), "the gauge is never reduced")
        assertTrue(anchor.text.contains("── impact: 3 references not inspected"), "a nudge is never reduced")
        assertFalse(anchor.text.contains("CON-007"), "focus notes went")
    }

    @Test
    fun `at most two nudges reach the turn`() {
        val anchor = Anchor.render(
            estimator, digest, register, workset, touched(), checks,
            nudges = listOf("first", "second", "third"),
        )

        assertTrue(anchor.text.contains("first") && anchor.text.contains("second"), anchor.text)
        assertFalse(anchor.text.contains("third"), anchor.text)
        assertTrue(anchor.reductions.any { it == "nudges: showed 2 of 3" }, anchor.reductions.toString())
    }

    @Test
    fun `the anchor is the volatile tail - kind A and never a breakpoint`() {
        val segment = Anchor.render(estimator, digest, register, workset, touched(), checks).segment()

        assertEquals(SegmentKind.A, segment.kind)
        assertFalse(segment.breakpoint, "[A] is rebuilt every turn and never cached (§5.1)")
    }

    @Test
    fun `a touched entry reads its kind and versions from the registry transition`() {
        assertEquals(
            "M src/pay/total.py (+2 −1) @c02e→d1e7 \"accept ctx\" #41",
            Touched.of(VersionChange("src/pay/total.py", c02e, d1e7, "edit #41"), 2, 1, "accept ctx", "#41").render(),
        )
        assertEquals("A src/new.py @none→d1e7", Touched.of(VersionChange("src/new.py", null, d1e7, "edit")).render())
        assertEquals("D src/old.py @c02e→none", Touched.of(VersionChange("src/old.py", c02e, null, "edit")).render())
    }
}
