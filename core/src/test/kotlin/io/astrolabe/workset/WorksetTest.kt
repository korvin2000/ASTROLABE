package io.astrolabe.workset

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.Ranges
import io.astrolabe.workspace.VersionChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorksetTest {
    private val v1 = FileVersion(Digest.ofUtf8("v1"))
    private val v2 = FileVersion(Digest.ofUtf8("v2"))
    private val estimator = HeuristicEstimator()

    private fun entry(path: String, from: Int, to: Int, version: FileVersion = v1, id: String? = "#1", tokens: Long = 100, hidden: Ranges = Ranges.EMPTY) =
        Entry(path, Ranges.single(from, to), version, EntrySource.Look, turn = 1, resultId = id, tokens = tokens, hidden = hidden)

    @Test
    fun `only rendered source bytes make a region known and redacted lines grant nothing (IX-10)`() {
        val ws = Workset()
        assertFalse(ws.covers("src/a.py", v1, LineRange(10, 12)), "an outline never makes a body KNOWN")
        ws.register(entry("src/a.py", 10, 30, hidden = Ranges.single(15, 16)))
        assertTrue(ws.covers("src/a.py", v1, LineRange(17, 30)))
        assertFalse(ws.covers("src/a.py", v1, LineRange(14, 17)), "a hidden line inside the anchor grants no coverage")
        assertFalse(ws.covers("src/a.py", v2, LineRange(17, 30)), "coverage is version-exact")
        ws.register(entry("src/a.py", 31, 40))
        assertTrue(ws.covers("src/a.py", v1, LineRange(20, 40)), "adjacent ranges merge")
    }

    @Test
    fun `a version change drops the entry and announces it the same turn and large bodies stub immediately`() {
        val ws = Workset(immediateStubTokens = 800)
        ws.register(entry("src/a.py", 1, 50, tokens = 100, id = "#1"))
        ws.register(entry("src/b.py", 1, 900, tokens = 1_200, id = "#2"))
        ws.onChange(VersionChange("src/b.py", v1, v2, "edited by transform #40"))
        val drops = ws.pendingDrops
        assertEquals(1, drops.size)
        assertTrue(drops.single().stubNow)
        assertEquals("src/b.py:1-900 stale @${v1.hash8} (edited by transform #40) → recall #2 or read again", drops.single().text)
        assertFalse(ws.covers("src/b.py", v1, LineRange(1, 10)))
        assertTrue(ws.covers("src/a.py", v1, LineRange(1, 10)))
        ws.onChange(VersionChange("src/a.py", null, v2, "touched by run #3"))
        assertFalse(ws.pendingDrops.last().stubNow)
        assertEquals("touched by run #3", ws.pendingDrops.last().cause, "an unknown old version drops every entry not at the new one")
        assertEquals(2, ws.pendingDrops.size)
        val rendered = ws.render(estimator)
        assertTrue(rendered.startsWith("KNOWN: (nothing) · NOT SEEN: everything else"), rendered)
        assertTrue(rendered.contains("stale"), rendered)
        assertEquals(2, ws.takeAnnouncements().size)
        assertTrue(ws.pendingDrops.isEmpty())
    }

    @Test
    fun `dispatch-time snapshot ignores reads registered later in the same batch (FX-50)`() {
        val ws = Workset()
        val view = ws.snapshot()
        ws.register(entry("src/a.py", 1, 10))
        assertFalse(view.covers("src/a.py", v1, LineRange(1, 5)))
        assertTrue(ws.snapshot().covers("src/a.py", v1, LineRange(1, 5)))
    }

    @Test
    fun `stubbing removes coverage and recall restores it at the recorded version or reports historical`() {
        val ws = Workset()
        val e = entry("src/a.py", 1, 20, id = "#7")
        ws.register(e)
        ws.stub("#7")
        assertFalse(ws.covers("src/a.py", v1, LineRange(1, 5)))
        val known = ws.recall(e, currentVersion = v1, turn = 5)
        assertIs<Workset.RecallResult.Known>(known)
        assertTrue(ws.covers("src/a.py", v1, LineRange(1, 5)))
        assertEquals(EntrySource.Recall, ws.entries.single().source)
        ws.stub("#7")
        val historical = ws.recall(e, currentVersion = v2, turn = 6)
        assertIs<Workset.RecallResult.Historical>(historical)
        assertEquals(v2, historical.currentVersion)
        assertFalse(ws.covers("src/a.py", v1, LineRange(1, 5)), "historical bytes are not KNOWN")
    }

    @Test
    fun `render stays within the cap and export round trips as seeds`() {
        val ws = Workset(renderCapTokens = 60)
        (1..40).forEach { ws.register(entry("src/module_$it/file_$it.py", 1, 50, id = "#$it")) }
        val rendered = ws.render(estimator)
        assertTrue(estimator.estimate(rendered).tokens <= 60, "${estimator.estimate(rendered).tokens} tokens: $rendered")
        val exported = ws.export()
        assertEquals(40, exported.size)
        val next = Workset()
        next.seed(exported)
        assertTrue(next.entries.all { it.source == EntrySource.Seed })
        assertTrue(next.covers("src/module_3/file_3.py", v1, LineRange(1, 50)))
    }
}
