package io.astrolabe.register

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.evidence.Anchor
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.workspace.VersionChange
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RegisterTest {
    private val v = FileVersion(Digest("a9f1" + "0".repeat(60)))
    private val old = FileVersion(Digest("c02e" + "0".repeat(60)))

    /** The §5.2 example, as typed records. */
    private val example = Register(
        version = 14,
        cell = ContextId("cell-8"),
        increment = "I2",
        incrementTitle = "thread ctx through handlers",
        constraints = listOf("keep refund flow untouched (exclusion)", "keep Router API (C1)"),
        plan = listOf(
            Step(1, Mark.Done, "locate dispatch", evidence = "#12"),
            Step(2, Mark.Cursor, "pass ctx into handlers", accept = "run: pytest -k ctx", req = "R2/AC-4"),
            Step(3, Mark.Todo, "update 3 call sites", after = 2),
            Step(4, Mark.Cancelled, "cancelled: rename Router", reason = "out of scope (C1)"),
        ),
        facts = listOf(
            Fact(1, ClaimKind.Verified, "`Router.dispatch(req, ctx)`", Anchor("src/router.py", v, 88), "#17"),
            Fact(2, ClaimKind.Hypothesis, "handlers are all keyword-only"),
            Fact(3, ClaimKind.Refuted, "popleft is atomic here", refutedBy = "#31"),
            Fact(4, ClaimKind.Verified, "handle_user takes 1 arg", Anchor("src/handlers/user.py", old, 42), "#22", staleAt = old),
        ),
        deadEnds = listOf(DeadEnd(1, "monkeypatching ctx → import cycle", "#22", "handlers built directly in tests", "fixtures isolated")),
        decisions = listOf(Decision(1, "pass ctx explicitly, not via contextvar", "tests construct handlers directly", "contextvar", "run pytest -k \"direct_construct\"", adrCandidate = true)),
        open = listOf(OpenItem(1, "does CLI path build handlers?", trip = "any edit under src/cli/ → check", needs = "CON-007")),
        focus = "src/handlers/",
        amendments = listOf(AmendmentLine("AC-1 cmd → \"pytest tests/payments -q -k 'not slow'\"", "the slow suite needs a live DB")),
        next = "edit src/handlers/user.py:42 signature, then run accept",
    )

    @Test
    fun `renders the §5-2 example shape deterministically`() {
        val expected = """
            # STATE v14 · cell cell-8 · I2 "thread ctx through handlers"
            ## Constraints (inferred)  - keep refund flow untouched (exclusion) · - keep Router API (C1)
            ## Plan       1. [x] locate dispatch (#12)
                          2. [>] pass ctx into handlers  accept: run: pytest -k ctx  → R2/AC-4
                          3. [ ] update 3 call sites  after: 2
                          4. [~] cancelled: rename Router — out of scope (C1)
            ## Facts      - v `Router.dispatch(req, ctx)`  src/router.py:88 @a9f10000 [#17]
                          - h handlers are all keyword-only
                          - x popleft is atomic here  (refuted #31; kept)
                          - v(stale @c02e0000) handle_user takes 1 arg  src/handlers/user.py:42 @c02e0000 [#22]  ← harness-rendered; re-look
            ## Dead ends  - monkeypatching ctx → import cycle (#22)   scope: handlers built directly in tests   reopen: fixtures isolated
            ## Decisions  - D1: pass ctx explicitly, not via contextvar — because tests construct handlers directly; rejected: contextvar; probe: run pytest -k "direct_construct"   (→ candidate ADR)
            ## Open       - Q1: does CLI path build handlers? (trip: any edit under src/cli/ → check)  (needs: CON-007)
            ## Focus      src/handlers/
            ## Amendments - propose AC-1 cmd → "pytest tests/payments -q -k 'not slow'" because the slow suite needs a live DB (pending)
            ## Next       edit src/handlers/user.py:42 signature, then run accept

        """.trimIndent()
        val rendered = RegisterRender.markdown(example)
        assertEquals(expected, rendered)
        assertEquals(rendered, RegisterRender.markdown(example))
        val tokens = RegisterRender.tokens(example, HeuristicEstimator())
        assertTrue(tokens in 200..1_200, "example measures $tokens tokens against the 1,200 cap")
    }

    @Test
    fun `serializes and round trips including harness-only fields`() {
        val text = Json.encodeToString(Register.serializer(), example)
        assertEquals(example, Json.decodeFromString(Register.serializer(), text))
    }

    @Test
    fun `harness marks stale facts and fires trips once`() {
        val fresh = example.copy(facts = example.facts.map { it.copy(staleAt = null) })
        val marked = fresh.markStale(VersionChange("src/handlers/user.py", old, v, "edit #40"))
        assertTrue(marked.fact(4)!!.stale)
        assertEquals(old, marked.fact(4)!!.staleAt)
        assertTrue(!marked.fact(1)!!.stale)
        assertTrue(!fresh.markStale(VersionChange("src/handlers/user.py", null, old, "run #7")).fact(4)!!.stale, "a fact at the new version stays current")
        assertTrue(fresh.markStale(VersionChange("src/handlers/user.py", null, v, "touched by run #7")).fact(4)!!.stale, "an unknown old version marks conservatively")
        val fired = example.fireTrips(listOf("src/cli/main.py"))
        assertTrue(fired.openItem(1)!!.fired)
        assertEquals(listOf("⟨trip Q1 fired: any edit under src/cli/ → check → does CLI path build handlers?⟩"), RegisterRender.firedTrips(fired))
        assertTrue(!example.fireTrips(listOf("src/handlers/user.py")).openItem(1)!!.fired)
        assertTrue(Trips.matches("src/**/*.py", "src/a/b/c.py"))
        assertTrue(!Trips.matches("src/*.py", "src/a/b.py"))
        assertTrue(Trips.matches("tests/", "tests/test_x.py"))
    }
}
