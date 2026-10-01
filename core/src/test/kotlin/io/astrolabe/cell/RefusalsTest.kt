package io.astrolabe.cell

import io.astrolabe.contract.Shape
import io.astrolabe.provider.ToolMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F2a: a masked op is refused for the whole cell, with what is available and the role's intended exit. */
class RefusalsTest {
    private val available = ToolMask.of("look.read", "kb.search", "task.ask", "kb.get")

    @Test
    fun `an op outside the role mask is refused in any turn with the plan role's hand-over exit`() {
        assertEquals(
            "edit.anchored is not available to the plan role in this cell (any turn); available: kb.get, kb.search, look.read, task.ask; " +
                "to get commands executed, hand the work over with task.propose(plan); if the plan cannot be made, end with state(blocked) or task.ask",
            Refusals.masked("edit.anchored", Roles.plan, Shape.S1, available, null),
        )
    }

    @Test
    fun `an op the role holds but the shape does not enable names the shape and the implementing exit`() {
        val text = Refusals.masked("task.delegate", Roles.implementing, Shape.S1, available, null)
        assertTrue(text.startsWith("task.delegate is not enabled in shape S1; available: kb.get, kb.search, look.read, task.ask; "), text)
        assertTrue(text.endsWith("; end with task.ask or state(blocked) if the increment cannot proceed without it"), text)
    }

    @Test
    fun `an op the role and shape allow was removed by the capability ceiling`() {
        val text = Refusals.masked("run.run", Roles.implementing, Shape.S2, available, "'run.run' needs exec, outside capability set 'read-only'")
        assertTrue(text.startsWith("run.run is outside this cell's capability ceiling (any turn): 'run.run' needs exec, outside capability set 'read-only'; available: "), text)
        assertTrue(Refusals.masked("run.run", Roles.probe, Shape.S2, ToolMask(emptySet()), null).contains("; available: none; "), "an empty mask says so")
    }
}
