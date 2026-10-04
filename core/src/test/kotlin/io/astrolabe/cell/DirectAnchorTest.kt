package io.astrolabe.cell

import io.astrolabe.Defaults
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.register.AmendmentLine
import io.astrolabe.register.Decision
import io.astrolabe.register.DeadEnd
import io.astrolabe.register.Fact
import io.astrolabe.register.NotesRender
import io.astrolabe.register.OpenItem
import io.astrolabe.register.Register
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P8.D.2: the direct protocol's `[A]` journal (rendered turn §5.10-D; kernel contract A-D.7 A1, A2). */
class DirectAnchorTest {
    private val estimator = HeuristicEstimator()
    private val c02e = FileVersion(Digest("c02e1f9a".repeat(8)))
    private val d1e7 = FileVersion(Digest("d1e7b30c".repeat(8)))

    private val digest =
        "── CONTRACT v3 (S0) ── \"Add idempotency-key handling to POST /payments; public API unchanged.\" + \"Also cover the retry path.\"\n" +
            "R2 in_progress → AC-4 red #42 · exclusions: refund flow"
    private val workset = "KNOWN: router.py:80-96@a9f1 · handlers/user.py:30-60@d1e7 · NOT SEEN: everything else; src/cli/main.py never read"
    private val checks = "── Checks @d1e7 ── types: ✓ @d1e7 · tests: now 1 @d1e7 (#42)"
    private val gauge = "⟨ctx 38% · reserve ok · checks types ✓ · tests ✗1 · known 5/2.6K · STATE v3 · turn 14/80⟩"
    private val enabled = "enabled this turn: all role tools except task.propose"

    private val register = Register.empty(ContextId("cell-1"), "I2", "thread ctx through handlers").copy(
        version = 3,
        facts = listOf(Fact(2, ClaimKind.Hypothesis, "handle_cli is the only caller outside src/handlers")),
        decisions = listOf(Decision(1, "pass ctx as a keyword argument; keep the positional order", "", null)),
        open = listOf(OpenItem(1, "the CLI path builds handlers without ctx")),
    )

    private fun runs() = RunsRender.lines(
        listOf(RunsRender.live("h2", listOf("uvicorn", "app.main:app"), "port 8000")),
        listOf(RunReceipt("#42", listOf("pytest", "-q", "-k", "ctx"), "red: 1 failed", red = true, stamp = "d1e7")),
    )

    @Test
    fun `the journal renders its blocks in the 5_10-D order with no STATE block`() {
        val touched = (1..5).map { Touched("src/f$it.py", TouchKind.Modified, 1, 0, c02e, d1e7, alias = "#$it") }
        val anchor = Anchor.renderDirect(
            estimator, digest, register, workset, touched, checks, runs(), gauge,
            nudges = (1..5).map { "nudge $it" }, diagnoses = listOf("diagnosis: helper line"), enabled = enabled,
        )

        assertEquals(
            """
            ── CONTRACT v3 (S0) ── "Add idempotency-key handling to POST /payments; public API unchanged." + "Also cover the retry path."
            R2 in_progress → AC-4 red #42 · exclusions: refund flow
            ── Workset  KNOWN: router.py:80-96@a9f1 · handlers/user.py:30-60@d1e7 · NOT SEEN: everything else; src/cli/main.py never read
            ── Touched  M src/f3.py (+1 −0) @c02e→d1e7 #3
                        M src/f4.py (+1 −0) @c02e→d1e7 #4
                        M src/f5.py (+1 −0) @c02e→d1e7 #5
            ── Checks @d1e7 ── types: ✓ @d1e7 · tests: now 1 @d1e7 (#42)
            ── Runs     h2 uvicorn app.main:app → running · ready (port 8000)
                        #42 pytest -q -k ctx → red: 1 failed @d1e7
            ── Notes (STATE v3)
                        o1 the CLI path builds handlers without ctx
                        d1 pass ctx as a keyword argument; keep the positional order
                        h2 handle_cli is the only caller outside src/handlers
            enabled this turn: all role tools except task.propose
            ⟨ctx 38% · reserve ok · checks types ✓ · tests ✗1 · known 5/2.6K · STATE v3 · turn 14/80⟩
            nudge 1
            nudge 2
            nudge 3
            nudge 4
            diagnosis: helper line

            """.trimIndent(),
            anchor.text,
        )
        assertEquals(listOf("Touched: showed the last 3 of 5", "nudges: showed 4 of 5"), anchor.reductions)
        assertTrue(anchor.tokens <= Defaults().directAnchorTargetTokens, "a typical S0 turn fits the target: ${anchor.tokens}")
        assertFalse(anchor.overBudget)
    }

    @Test
    fun `the notes block keeps whole lines within its cap and names every note it did not show`() {
        val many = register.copy(open = (1..60).map { OpenItem(it, "open question number $it about the retry path and the idempotency key") })
        val anchor = Anchor.renderDirect(estimator, digest, many, workset, enabled = enabled)
        val block = anchor.text.substringAfter("── Notes (STATE v3)\n").substringBefore("\nenabled this turn")
        val lines = block.lines().map { it.trim() }
        val overflow = lines.last()
        val shown = lines.dropLast(1)
        assertTrue(estimator.estimate(shown.joinToString("\n")).tokens <= Defaults().directNotesMaxTokens, block)
        assertTrue(shown.first().startsWith("o60 "), "newest first: $block")
        val hidden = NotesRender.anchorLines(many).size - shown.size
        assertTrue(overflow.startsWith("… +$hidden not shown: ") && overflow.endsWith(" … — look(recall, id=notes)"), overflow)
        assertEquals(30, overflow.substringAfter("not shown: ").substringBefore(" …").split(' ').size, "at most 30 ids, then …")
    }

    @Test
    fun `over the target the journal drops settled runs and then halves the notes and says so`() {
        val receipts = (1..8).map { RunReceipt("#${10 + it}", listOf("pytest", "tests/test_$it.py"), "green", red = false, stamp = "d1e7") } +
            RunReceipt("#9", listOf("pytest", "tests/test_red.py"), "red: 2 failed", red = true, stamp = "d1e7")
        val runs = RunsRender.lines(listOf(RunsRender.live("handle-1", listOf("npm", "run", "dev"), null)), receipts)
        val many = register.copy(open = (1..60).map { OpenItem(it, "open question number $it about the retry path") })
        val defaults = Defaults().copy(directAnchorTargetTokens = 150)

        val anchor = Anchor.renderDirect(estimator, digest, many, workset, runs = runs, defaults = defaults, enabled = enabled)

        assertEquals("Runs: live handles and red receipts only, the anchor was over 150 tokens", anchor.reductions[0])
        assertEquals("Notes: capped at 100 tokens, the anchor was over 150 tokens", anchor.reductions[1])
        assertTrue(anchor.reductions[2].startsWith("over the 150-token target: "), anchor.reductions.toString())
        assertTrue("── Runs     handle-1 npm run dev → running\n            #9 pytest tests/test_red.py → red: 2 failed @d1e7\n" in anchor.text, anchor.text)
        assertFalse("#18 " in anchor.text, "settled receipts dropped")
        assertFalse(anchor.overBudget, "the hard cap keeps its meaning")
    }

    @Test
    fun `runs list live handles then the last receipts red first and by the higher alias`() {
        val long = listOf("python", "-m", "pytest", "tests/integration/test_payments_idempotency_retry_path.py", "-k", "retry")
        val lines = RunsRender.lines(
            listOf(RunsRender.live("handle-1", listOf("uvicorn", "app:app"), "Uvicorn running on http://127.0.0.1:8000"), RunsRender.live("handle-2", listOf("npm", "start"), null)),
            listOf(
                RunReceipt("#40", listOf("ruff", "check"), "green", red = false, stamp = "c02e", stale = true),
                RunReceipt("#45", listOf("mypy", "src"), "red: 3 failed", red = true, stamp = "d1e7", knownRed = true),
                RunReceipt("#47", long, "red: 1 failed", red = true, stamp = "d1e7"),
                RunReceipt("#46", listOf("pytest", "-q"), "timeout", red = false, stamp = "d1e7"),
            ),
        )

        assertEquals(
            listOf(
                "handle-1 uvicorn app:app → running · ready (Uvicorn running on http://127.0.0.1:8000)",
                "handle-2 npm start → running",
                "#47 python -m pytest tests/integration/test_payments_idempotenc… → red: 1 failed @d1e7",
                "#45 mypy src → red: 3 failed @d1e7 · known red, not required",
                "#46 pytest -q → timeout @d1e7",
                "#40 ruff check → green @c02e (stale)",
            ),
            lines.map { it.text },
        )
        assertEquals(listOf(true, true, true, true, false, false), lines.map { it.essential })
        val block = Anchor.renderDirect(estimator, digest, register.copy(open = emptyList(), facts = emptyList(), decisions = emptyList()), "", runs = lines).text
        assertTrue(block.contains("#45 mypy src → red: 3 failed @d1e7 · known red, not required\n            +2 more\n"), "five lines at most, the rest collapsed: $block")
        assertFalse(block.contains("── Notes"), "an empty block is omitted")
    }

    @Test
    fun `notes read by kind newest first with the ids of A-D_4`() {
        val stale = FileVersion(Digest("ab12cd34".repeat(8)))
        val r = register.copy(
            facts = listOf(
                Fact(1, ClaimKind.Verified, "total() rounds half-even", evidenceId = "#12"),
                Fact(2, ClaimKind.Hypothesis, "rounding happens in total()"),
                Fact(3, ClaimKind.Verified, "the router builds handlers", staleAt = stale),
                Fact(4, ClaimKind.Refuted, "the cache is stale", refutedBy = "#20"),
            ),
            deadEnds = listOf(DeadEnd(1, "patching the CLI alone", "#14", "task", "new evidence")),
            amendments = listOf(AmendmentLine("drop the retry path", "stated in the change", id = "AM-1")),
        )

        assertEquals(
            listOf("o1", "dead1", "d1", "a1", "v3(stale @ab12)", "h2", "v1"),
            NotesRender.anchorLines(r).map { it.id },
        )
        assertEquals("dead1 patching the CLI alone [#14]", NotesRender.anchorLines(r)[1].toString())
        assertEquals("x4 the cache is stale [refuted #20]", NotesRender.activeLines(r).last().toString(), "recall and carry list what [A] leaves out")
        assertTrue(NotesRender.carry(r).startsWith("Notes:\n  - o1 the CLI path builds handlers without ctx\n"), NotesRender.carry(r))
    }

    @Test
    fun `the structured anchor keeps its bytes`() {
        val state = "# STATE v15 · cell c1 · I2 \"thread ctx through handlers\"\n## Next       edit src/cli/main.py"
        val anchor = Anchor.render(estimator, digest, state, workset, emptyList(), checks, gauge = gauge, nudges = listOf("nudge 1"), enabled = enabled)
        assertEquals("$digest\n$state\n── Workset  $workset\n$checks\n$enabled\n$gauge\nnudge 1\n", anchor.text)
    }
}
