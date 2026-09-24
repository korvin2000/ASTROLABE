package io.astrolabe.context

import io.astrolabe.cell.CompiledK
import io.astrolabe.cell.Roles
import io.astrolabe.cell.Transcript
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.id.ContextId
import io.astrolabe.provider.Message
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Role as ItemRole
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import io.astrolabe.register.DeadEnd
import io.astrolabe.register.Register
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P2.5.1: the one rebuild mechanism, exercised with all five reasons; FX-56. */
class RebuildTest {
    private fun turn(n: Int, reasoning: Boolean = false) = listOfNotNull(
        if (reasoning) ReasoningRef("fake", JsonPrimitive("r$n")) else null,
        Message.text(ItemRole.Assistant, "turn $n"),
        ToolCall("call-$n", "look", """{"what":"read","target":"src/a$n.py"}"""),
        ToolResult.text("call-$n", "⟨stub #$n: recall #$n⟩"),
    )

    /** Eight complete turns (every third with opaque reasoning), then a turn whose call has no result yet. */
    private val items = (1..8).flatMap { turn(it, reasoning = it % 3 == 0) } +
        listOf(Message.text(ItemRole.Assistant, "turn 9"), ToolCall("call-9", "run", """{"cmd":"pytest"}"""))

    private val current = Projection(
        generation = 3, role = Roles.implementing, profile = FakeProfiles.main, repository = "repo prime\n",
        k = CompiledK(ContractSlice(1, "I1", emptyList(), emptyList(), emptyList(), emptyList())),
        transcript = Transcript(listOf("Fix the parser"), items), anchor = "old anchor",
    )

    private val register = Register.empty(ContextId("cell-1"), "I1", "parser").copy(
        deadEnds = listOf(DeadEnd(1, "monkeypatching the clock", "#8", "tests/", "fixtures isolated")),
    )
    private val carry = CarryForward.carry(register, emptyList(), null, { null }, { true }, emptyList(), listOf("Fix the parser"))

    private class Hooks : RebuildHooks {
        val calls = ArrayList<String>()
        override fun checkpoint(old: Projection, reason: RebuildReason) { calls += "checkpoint g${old.generation} ${reason.wire}" }
        override fun status(reason: RebuildReason) { calls += "status ${reason.wire}" }
    }

    private fun rebuild(reason: RebuildReason, hooks: Hooks = Hooks(), repository: String = "repo prime\n") =
        Rebuild.run(reason, current, carry, "digest: R1 open", repository, { role, profile -> current.k.copy(slice = current.k.slice.copy(incrementId = "${role.name}@${profile.id}")) }, hooks)

    @Test
    fun `pressure and resume keep the last six complete turns with their ids and opaque items intact (FX-56)`() {
        for (reason in listOf(RebuildReason.Pressure, RebuildReason.Resume)) {
            val (next, record) = rebuild(reason)
            val kept = next.transcript.items
            assertEquals((3..8).map { "call-$it" }, kept.filterIsInstance<ToolCall>().map { it.id }, reason.wire)
            assertEquals(kept.filterIsInstance<ToolCall>().map { it.id }, kept.filterIsInstance<ToolResult>().map { it.callId }, "every kept call keeps its result")
            assertEquals(listOf("r3", "r6"), kept.filterIsInstance<ReasoningRef>().map { (it.opaque as JsonPrimitive).content }, "required opaque items of kept turns stay")
            assertFalse(kept.any { it is ToolCall && it.id == "call-9" }, "no call without its result is inherited")
            assertEquals(4, next.generation)
            assertEquals(RebuildRecord(reason.wire, 3, 4, systemReused = true, repositoryReused = true, tailTurns = 6, droppedItems = items.size - kept.size), record)
            assertTrue(next.freshLineage, "no provider continuation survives a rebuild")
        }
    }

    @Test
    fun `role switch, alternative attempt and cell end start from validated state with an empty tail`() {
        val hooks = Hooks()
        val (switched, switchRecord) = rebuild(RebuildReason.RoleSwitch(Roles.plan), hooks)
        assertEquals(Roles.plan, switched.role)
        assertTrue(switched.transcript.items.isEmpty())
        assertFalse(switchRecord.systemReused, "a new role recompiles [S]")
        assertEquals("plan@${FakeProfiles.main.id}", switched.k.slice.incrementId, "[K] recompiled for the new role")

        val (alternative, altRecord) = rebuild(RebuildReason.AlternativeAttempt(FakeProfiles.escalation), hooks, repository = "repo prime v2\n")
        assertEquals(FakeProfiles.escalation, alternative.profile)
        assertTrue(alternative.anchor.contains("DEAD ENDS — do not repeat:\n- monkeypatching the clock (scope tests/)"), alternative.anchor)
        assertFalse(altRecord.repositoryReused, "a changed prime recompiles [R]")

        val (ended, _) = rebuild(RebuildReason.CellEnd(RebuildReason.CellEnd.Next.Continuation), hooks)
        assertTrue(ended.transcript.items.isEmpty())
        assertEquals(listOf("Fix the parser", "rebuilt: cell_end(continuation) (generation 4)"), ended.transcript.pinned)
        assertTrue(ended.anchor.startsWith("digest: R1 open\n") && ended.anchor.endsWith("KNOWN: seeds only (0) · NOT SEEN: everything else"), ended.anchor)
        assertFalse("DEAD ENDS" in ended.anchor, "dead ends are emphasised for an alternative attempt only")

        assertEquals(
            listOf(
                "checkpoint g3 role_switch(plan)", "status role_switch(plan)",
                "checkpoint g3 alternative_attempt(${FakeProfiles.escalation.id})",
                "checkpoint g3 cell_end(continuation)", "status cell_end(continuation)",
            ),
            hooks.calls,
            "the old projection is checkpointed before installation; STATUS at role switch and cell end",
        )
    }
}
