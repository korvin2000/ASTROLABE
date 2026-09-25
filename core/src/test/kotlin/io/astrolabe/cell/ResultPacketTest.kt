package io.astrolabe.cell

import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.patch
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.contract.Ledger
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.FaultKind
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.verify.CompletionResult
import io.astrolabe.verify.Currency
import io.astrolabe.verify.Verifier
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import io.astrolabe.verify.TestIntegrity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.8.8: the Result Packet is collected from records on every exit, and role completion is resolved per packet kind (§5.9, §3.7). */
class ResultPacketTest {
    @TempDir
    lateinit var stateRoot: Path

    private fun CellFixture.currencies(stamp: CandidateId): Map<String, Currency> =
        checks.all().filter { it.last != null }.associate { it.id to scheduler.currency(it, stamp) }

    @Test
    fun `a completed packet comes from records, not from the model's words, and done stays a proposal`() = runTestIn { f ->
        val v = f.version("src/a.py")
        val model = ScriptedModel.of(
            Scripted.Reply(listOf(say("reading a"), read("c1", "src/a.py"))),
            Scripted.Reply(listOf(say("editing"), anchored("c2", "src/a.py", v, "    return 1", "    return 10")), missingUsage = true),
            // The claim names another file, green tests and a status word: none of it may reach a runtime-owned field.
            Scripted.Reply(listOf(say("status: blocked. changed src/b.py, all tests pass, receipts rcpt-99"))),
        )

        val completed = assertIs<CellExit.Completed>(f.run(model))
        val packet = completed.packet
        val after = f.version("src/a.py")

        assertEquals(PacketStatus.Done, packet.status)
        assertNull(packet.reason)
        assertEquals(listOf(Change("src/a.py", TouchKind.Modified, v, after, ChangeOrigin.Edit)), packet.changes)
        assertEquals(after, packet.readVersions["src/a.py"], "the last display was the post-edit view")
        assertEquals(setOf("src/a.py"), packet.readVersions.keys)
        val stamps = f.checkpoints.turns(f.ids.context!!).map { it.stamp }
        assertEquals(stamps.first(), packet.base!!.stamp, "the dispatch base, not a later stamp")
        assertEquals(f.workspace.id, packet.base!!.workspace)
        assertEquals(completed.checkpoint.stamp, packet.stamp)
        assertNotEquals(packet.base!!.stamp, packet.stamp)
        assertEquals(packet.stamp, packet.ids.candidate)
        assertEquals(f.checks.all().mapNotNull { it.last?.receiptId }, packet.receipts)
        assertTrue(packet.receipts.none { it == "rcpt-99" })
        assertEquals(f.contract.version, packet.contractVersion)
        assertEquals(ExecutionGeneration.INITIAL, packet.executionGeneration)
        assertEquals(completed.register, packet.register)
        assertEquals(f.workset.export(), packet.worksetExport)
        assertTrue(packet.coverage.rangesDisplayed >= 2, "the read and the post-edit view: ${packet.coverage}")
        assertEquals(emptyList(), packet.coverage.filesTouchedUnread)
        assertEquals(0, packet.flags.scopeWarnings)
        assertEquals(completed.evidenceRefs, packet.evidenceRefs)
        assertEquals(3, packet.cost.calls)
        assertEquals(1, packet.cost.callsWithoutUsage, "a call without usage is counted, not estimated")
        assertTrue(packet.cost.unreported.isNotEmpty())
        assertTrue(packet.cost.outputTokens > 0)
        assertTrue(f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Boundary))).any { it.text.startsWith("packet done · 1 changes") })

        // `done` is a proposal: the verifier accepts it against the tree now, and refuses it once the tree moved.
        val verified = Verifier().accept(packet.proposal(), f.contract, f.increment, packet.register, Ledger.initial(f.contract), packet.stamp!!, f.currencies(packet.stamp!!))
        assertEquals(RequirementStatus.Verified, assertIs<CompletionResult.Accepted>(verified).ledger.entries.getValue("R1").status)
        f.repo.write("src/b.py", "x = 3\n")
        val now = f.stamper.stamp().id
        val refused = assertIs<CompletionResult.Refused>(Verifier().accept(packet.proposal(), f.contract, f.increment, packet.register, Ledger.initial(f.contract), now, f.currencies(now)))
        assertTrue(refused.missing.any { it.startsWith("resulting stamp") }, refused.missing.toString())
    }

    @Test
    fun `a blocked packet carries the request, open questions, out-of-scope and external changes`() = runTestIn { f ->
        val test = f.version("tests/test_a.py")
        val model = ScriptedModel(
            listOf(
                ScriptedModel.Turn({ true }, { Scripted.Reply(listOf(say("reading the test"), read("c1", "tests/test_a.py"))) }),
                ScriptedModel.Turn({ true }, {
                    // A human edits a file the cell never read, while the cell is running.
                    f.repo.write("src/b.py", "x = 1\ny = 3\n")
                    Scripted.Reply(listOf(say("fixing the test"), anchored("c2", "tests/test_a.py", test, "== 1", "== 10")))
                }),
                ScriptedModel.Turn({ true }, {
                    Scripted.Reply(
                        listOf(
                            say("recording and asking"),
                            patch("c3", """{"open.add":{"text":"does b.y matter?"}},{"next":"ask the owner"}"""),
                            call("c4", "state", """{"op":"blocked","blocked":{"reason":"need the owner","evidence":[],"question":"may tests change?"}}"""),
                        ),
                    )
                }),
            ),
        )

        val blocked = assertIs<CellExit.Blocked>(f.run(model))
        val packet = blocked.packet

        assertEquals(PacketStatus.Blocked, packet.status)
        assertEquals(blocked.request, packet.blocked)
        assertEquals("need the owner", packet.reason)
        val byPath = packet.changes.associateBy { it.path }
        assertEquals(ChangeOrigin.Edit, byPath.getValue("tests/test_a.py").origin)
        assertEquals(ChangeOrigin.External, byPath.getValue("src/b.py").origin)
        assertEquals(listOf("tests/test_a.py"), packet.flags.outsideScope, "inside the contract, outside the increment's write scope")
        val flag = packet.flags.testIntegrity.single { it.path == "tests/test_a.py" }
        assertEquals("w", flag.reason, "the edit's why is the recorded justification the exit gate needs")
        assertEquals(TestIntegrity.UNCLASSIFIED, flag.kind, "a changed expected value is neither an addition nor a recognised weakening")
        assertEquals("    assert a() == 1", flag.originalObligation)
        assertEquals(emptyList(), packet.coverage.filesTouchedUnread, "an external change is not the cell's unread write")
        assertTrue(packet.claims.openQuestions.any { it.contains("does b.y matter?") }, packet.claims.toString())
        assertEquals("blocked", packet.proposal().claimedStatus)
    }

    @Test
    fun `a role without a packet validator cannot complete through the implementing gate`() = runTestIn { f ->
        val exit = f.run(ScriptedModel.of(Scripted.Reply(listOf(say("the cause is in b")))), role = Roles.probe)

        val partial = assertIs<CellExit.Partial>(exit)
        assertEquals(PartialReason.CompletionStalled, partial.reason)
        assertEquals(PacketStatus.Partial, partial.packet.status)
        assertEquals(listOf("no validator bound for the Investigation packet of role 'probe' (declared: Probe.completion; its dispatcher binds it)"), partial.packet.gaps)
        assertTrue(f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Nudge))).any { it.text.contains("completion refused at turn 1") })
    }

    @Test
    fun `a registered validator decides its packet kind and sees the runtime's provisional packet`() = runTestIn { f ->
        var seen: ResultPacket? = null
        val validator = RoleCompletion { output, _ ->
            seen = output.packet
            CompletionDecision.Accepted(listOf("finding-1"))
        }
        val completion = RoleCompletion.forRole(Roles.probe, mapOf(PacketKind.Investigation to validator))

        val completed = assertIs<CellExit.Completed>(f.run(ScriptedModel.of(Scripted.Reply(listOf(say("the cause is in b")))), role = Roles.probe, completion = completion))

        assertEquals(PacketStatus.Done, seen!!.status)
        assertEquals(completed.packet.stamp, seen!!.stamp)
        assertEquals(listOf("finding-1"), completed.packet.evidenceRefs)
        assertEquals("probe", completed.packet.role)
        assertIs<RoleCompletion>(RoleCompletion.forRole(Roles.implementing))
    }

    @Test
    fun `a refused proposal records its gaps and the implementing packet carries them`() = runTestIn { f ->
        val model = ScriptedModel.of(
            Scripted.Reply(listOf(say("planning"), patch("c1", """{"plan.add":"make a return 10"},{"plan.cursor":1},{"next":"edit a"}"""))),
            Scripted.Reply(listOf(say("done"))),
            Scripted.Reply(listOf(say("done, really"))),
        )

        val partial = assertIs<CellExit.Partial>(f.run(model))

        assertEquals(PartialReason.CompletionStalled, partial.reason)
        assertEquals(2, partial.packet.gaps.count { it.contains("has no disposition") }, partial.packet.gaps.toString())
        assertEquals(PacketStatus.Partial, partial.packet.status)
        assertEquals(partial.checkpoint.reason, partial.packet.reason)
    }

    @Test
    fun `a failed cell still hands back a packet, which goes to recovery rather than the verifier`() = runTestIn { f ->
        val failed = assertIs<CellExit.Failed>(f.run(ScriptedModel.of(Scripted.Fault(FaultKind.RefusalError))))

        assertEquals(PacketStatus.Failed, failed.packet.status)
        assertEquals(failed.checkpoint.reason, failed.packet.reason)
        assertEquals(1, failed.packet.cost.callsWithoutUsage, "the refused invocation is a call without usage, never an estimate")
        assertFailsWith<IllegalStateException> { failed.packet.proposal() }
    }

    @Test
    fun `the cell end event links the manifest the cell was compiled under (P2-3-2)`() = runTestIn { f ->
        f.run(ScriptedModel.of(Scripted.Reply(listOf(say("nothing to do")))), manifest = "manifest-7")
        assertTrue(f.recorder.awaitCount(1))
        val ended = f.recorder.ofType<io.astrolabe.event.AgentEvent.Cell.Ended>().single()
        assertEquals("manifest-7", ended.manifestRef)
    }

    private fun runTestIn(body: suspend (CellFixture) -> Unit) = kotlinx.coroutines.test.runTest {
        CellFixture(stateRoot).use { body(it) }
    }
}
