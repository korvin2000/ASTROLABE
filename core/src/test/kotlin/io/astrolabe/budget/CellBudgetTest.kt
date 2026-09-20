package io.astrolabe.budget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.7.6 reserve enforcement: partitions by purpose, reserve spend never on generation, the gate, and `partial` with outstanding checks (FX-43). */
class CellBudgetTest {
    private fun budget(known: Tokens = Tokens.ZERO) = CellBudget.of(Tokens(100_000), 40, Reserves(), known)

    @Test
    fun `reserves are held from cell start and generation never draws on them`() {
        val cell = budget()
        assertEquals(Tokens(80_000), cell.working.capacity)
        assertEquals(Tokens(15_000), cell.verification.capacity)
        assertEquals(Tokens(5_000), cell.recovery.capacity)
        assertEquals(8, cell.reserve.turns)
        assertEquals(32, cell.generationTurnsLeft)

        val generation = assertIs<Admission.Admitted>(cell.admit(Spend.Generation, Tokens(80_000)))
        assertEquals(listOf(CellBudget.Partition.Working), generation.partitions)
        generation.reconcile(Tokens(80_000))
        assertTrue(cell.reserveReached)
        val refused = assertIs<Admission.Refused>(cell.admit(Spend.Generation, Tokens(1)))
        assertTrue(refused.reason.startsWith("reserve reached"), refused.reason)
        assertIs<Admission.Refused>(cell.admit(Spend.Edit, Tokens(1)), "edits are generation-class spend")
        assertEquals(Tokens(20_000), Tokens(cell.verification.available.value + cell.recovery.available.value), "the reserves are untouched")

        val check = assertIs<Admission.Admitted>(cell.admit(Spend.Check, Tokens(9_000)))
        assertEquals(listOf(CellBudget.Partition.Verification), check.partitions)
        check.reconcile(Tokens(9_500))
        assertEquals(Tokens(500), cell.verification.overrunTokens)
        val patch = assertIs<Admission.Admitted>(cell.admit(Spend.RegisterPatch, Tokens(4_000)))
        patch.reconcile(Tokens(4_000))
        assertIs<Admission.Refused>(cell.admit(Spend.Check, Tokens(3_000)), "the verification reserve is spent")
        val packet = assertIs<Admission.Admitted>(cell.admit(Spend.ResultPacket, Tokens(3_000)))
        assertEquals(listOf(CellBudget.Partition.Recovery), packet.partitions, "packets never draw on the verification reserve")
        packet.reconcile(Tokens(3_000))
        assertIs<Admission.Admitted>(cell.admit(Spend.Receipt, Tokens(1_000))).release()
        assertIs<Admission.Admitted>(cell.admit(Spend.StatusNote, Tokens(2_000))).reconcile(Tokens(2_000))
        assertIs<Admission.Refused>(cell.admit(Spend.StatusNote, Tokens(1)))
        assertEquals(98, cell.snapshot().percentUsed)
        assertFalse(cell.snapshot().reserveOk)
    }

    @Test
    fun `a check larger than one partition splits across working and verification in draw order`() {
        val cell = budget()
        assertIs<Admission.Admitted>(cell.admit(Spend.Generation, Tokens(75_000))).reconcile(Tokens(75_000))
        val split = assertIs<Admission.Admitted>(cell.admit(Spend.Check, Tokens(12_000)))
        assertEquals(listOf(CellBudget.Partition.Working, CellBudget.Partition.Verification), split.partitions)
        split.reconcile(Tokens(12_000))
        assertEquals(Tokens(80_000), cell.working.spent)
        assertEquals(Tokens(7_000), cell.verification.spent)
        assertTrue(cell.reserveReached)
        assertIs<Admission.Refused>(cell.admit(Spend.Check, Tokens(9_000)), "8,000 verification tokens remain")
    }

    @Test
    fun `known check costs raise the verification reserve and shrink the working budget`() {
        val raised = budget(known = Tokens(22_000))
        assertEquals(Tokens(22_000), raised.verification.capacity)
        assertEquals(Tokens(73_000), raised.working.capacity)
        assertTrue(runCatching { CellBudget(Tokens(10), 4, Reserve.cell(Tokens(100_000), 40, Reserves())) }.isFailure, "reserves larger than the budget leave no working budget")
    }

    @Test
    fun `turns are partitioned the same way and the reserve gate ends with partial when checks are outstanding (FX-43)`() {
        val cell = budget()
        repeat(32) { assertIs<Admission.Admitted>(cell.startTurn(Spend.Generation)) }
        assertEquals(0, cell.generationTurnsLeft)
        assertTrue(cell.reserveReached, "no generation turns left counts as reserve reached")
        val refused = assertIs<Admission.Refused>(cell.startTurn(Spend.Generation))
        assertTrue(refused.reason.contains("held for verification and reporting"), refused.reason)
        assertIs<Admission.Refused>(cell.startTurn(Spend.Edit))

        val verdict = cell.verdict(outstandingChecks = listOf("CHK-accept-AC-1", "CHK-types-touched"))
        assertTrue(verdict.reached)
        assertEquals(CellBudget.GATE, verdict.gate)
        assertEquals("partial: reserve reached with checks outstanding — unverified: CHK-accept-AC-1, CHK-types-touched", verdict.partial)
        assertNull(cell.verdict().partial, "nothing outstanding: the cell can verify and report")

        repeat(8) { assertIs<Admission.Admitted>(cell.startTurn(if (it % 2 == 0) Spend.Check else Spend.ResultPacket)) }
        assertEquals(0, cell.turnsLeft)
        assertIs<Admission.Refused>(cell.startTurn(Spend.ResultPacket))
        assertEquals(40, cell.turnsTaken)

        val fresh = budget()
        assertFalse(fresh.verdict(listOf("CHK-full")).reached, "before the reserve nothing is partial")
        assertNull(fresh.verdict(listOf("CHK-full")).gate)
    }
}
