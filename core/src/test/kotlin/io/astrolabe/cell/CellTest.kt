package io.astrolabe.cell

import io.astrolabe.Defaults
import io.astrolabe.budget.CellBudget
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.patch
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.resultText
import io.astrolabe.cell.CellFixture.Companion.runCmd
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellFixture.Companion.tree
import io.astrolabe.contract.Command
import io.astrolabe.event.AgentEvent
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FaultKind
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.StoreInspector
import io.astrolabe.os.Os
import io.astrolabe.os.Poll
import io.astrolabe.os.Proc
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.Items
import io.astrolabe.provider.Profile
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.Validation
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.Checks
import io.astrolabe.verify.CostClass
import io.astrolabe.verify.Layers
import io.astrolabe.verify.Selector
import io.astrolabe.verify.Trigger
import io.astrolabe.workspace.LineRange
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.8.7: the cell turn loop of §3.7 — order, fail-closed validation, gates, every exit kind, and a checkpoint on every path out (fault injection). */
class CellTest {
    @TempDir
    lateinit var stateRoot: Path

    private fun edited(): String = CellFixture.A_PY.replace("return 1", "return 10")

    @Test
    fun `a read edit patch cell runs to a completion the exit gate accepts and checkpoints every turn`() = runTest {
        CellFixture(stateRoot).use { f ->
            val v = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("reading a"), read("c1", "src/a.py"), patch("c2", """{"fact.add":{"kind":"v","text":"a returned 1 before the edit","evidence":"op:1","anchor":{"path":"src/a.py","version":"${v.digest.hex}"}}},{"plan.add":"make a return 10"},{"plan.cursor":1},{"next":"edit a"}"""))),
                Scripted.Reply(listOf(say("editing"), anchored("c3", "src/a.py", v, "    return 1", "    return 10"))),
                Scripted.Reply(listOf(say("ticking"), patch("c4", """{"plan.tick":{"n":1,"evidence":"#2"}},{"next":"done"}"""))),
                Scripted.Reply(listOf(say("done: a returns 10"))),
            )

            val exit = f.run(model)

            val completed = assertIs<CellExit.Completed>(exit)
            assertEquals(4, completed.turns)
            assertEquals("done: a returns 10", completed.text)
            assertEquals(edited(), Files.readString(f.repo.resolve("src/a.py")))
            val after = f.version("src/a.py")

            // Checkpoints: one per turn, the final one carries the exit status; the store rows agree.
            val turns = f.checkpoints.turns(f.ids.context!!)
            assertEquals(listOf(1, 2, 3, 4), turns.map { it.turn })
            assertEquals(CellStatus.Completed, f.checkpoints.latest(f.ids.context!!)!!.status)
            assertEquals(completed.checkpoint, f.checkpoints.latest(f.ids.context!!))
            assertNotEquals(turns[0].stamp, turns[1].stamp, "the edit moved the candidate")
            assertEquals(turns[1].stamp, turns[3].stamp)
            assertEquals(listOf("src/a.py"), turns[1].touched)
            val inspector = StoreInspector(f.store)
            assertEquals(1L, inspector.count("cells"))
            assertEquals(4L, inspector.count("turns"))
            assertEquals(4L, inspector.count("workset_exports"))
            assertEquals(f.state.register, f.registerVersions.latest(f.ids.context!!))

            // Coherence order: the Workset dropped the read, the register fact went stale, the check registry heard it, the Touched ledger has the line.
            assertEquals(2, completed.register.version)
            val fact = completed.register.facts.single()
            assertEquals(v, fact.staleAt, "the v fact anchored at the old version is marked stale by the cell horizon")
            assertTrue(f.workset.entries.none { it.path == "src/a.py" && it.version == v }, "the old read left KNOWN")
            assertTrue(f.workset.covers("src/a.py", after, LineRange(1, 5)), "the post-edit view is KNOWN at the new version")
            assertTrue(f.anchorText(3).contains("── Touched  M src/a.py @${v.hash8.take(4)}→${after.hash8.take(4)}"), f.anchorText(3))
            assertTrue(f.anchorText(3).contains("v(stale @${v.hash8})"), f.anchorText(3))
            assertTrue(f.anchorText(3).contains("stale @${v.hash8}"), "the drop is announced in the Workset line: " + f.anchorText(3))

            // The end-of-turn checker was drained from the scheduled paths and its receipt recorded.
            val receipt = f.receipts.forCheck(Checks.TYPES_TOUCHED).single()
            assertEquals(listOf("src/a.py"), receipt.testedInputs.versions.keys.toList())
            assertTrue(f.anchorText(3).contains("types(touched): inconclusive"), f.anchorText(3))
            assertEquals(listOf("src/a.py"), f.verify.touched.toList())

            // Journal: native output before results; the edit's preimage journaled under its alias; a boundary per turn.
            val events = f.journal.events(JournalScope(f.ids.work))
            val turn2 = events.filter { it.turn == 2 }
            assertEquals(JournalKind.Call, turn2.first().kind)
            assertTrue(turn2.indexOfFirst { it.kind == JournalKind.Result } > turn2.indexOfFirst { it.kind == JournalKind.Call })
            val preimage = f.preimages.of("edit-1").single()
            val editOutcome = turn2.single { it.kind == JournalKind.EditOutcome }
            assertEquals(listOf("#2", preimage.preimageDigest.hex), editOutcome.refs)
            assertEquals(1, turn2.count { it.kind == JournalKind.Check })
            val boundaries = events.filter { it.kind == JournalKind.Boundary && it.text.startsWith("turn ") }.map { it.text.substringBefore(" ·") }
            assertEquals(listOf("turn 1 running", "turn 2 running", "turn 3 running", "turn 4 running", "turn 4 completed"), boundaries)
            assertTrue(events.none { it.kind == JournalKind.Reconcile }, "every move was announced by its mutator")

            // [T]: calls precede results, every unit is complete, every result carries the gauge, and the adapter accepted every request.
            val history = f.transcript(4)
            assertFalse(Items.pairs(history).broken)
            val firstResult = history.filterIsInstance<ToolResult>().first()
            assertTrue(resultText(firstResult).contains("⟦result #1 tool=look"), resultText(firstResult))
            assertTrue(resultText(firstResult).contains("ctx ") && resultText(firstResult).contains("turn 1/12"), resultText(firstResult))
            assertTrue(f.adapter.validations.all { it.result == Validation.Ok })
            assertEquals(4, f.adapter.calls.size)

            // Host events.
            assertTrue(f.recorder.awaitCount(12))
            assertEquals(1, f.recorder.ofType<AgentEvent.Cell.Started>().size)
            assertEquals(listOf(1, 2, 3, 4), f.recorder.ofType<AgentEvent.Cell.TurnStarted>().map { it.turn })
            assertEquals("completed", f.recorder.ofType<AgentEvent.Cell.Ended>().single().status)
        }
    }

    @Test
    fun `leaving a step runs its accept check at the step boundary and the packet records what was not tested`() = runTest {
        val pass = javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
        CellFixture(stateRoot, files = CellFixture.DEFAULT_FILES + ("pytest_pass.txt" to pass)).use { f ->
            val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
            f.checks.register(Check("CHK-step", CheckKind.Unit, Selector.Named(printing), Closure.Known(setOf("src/a.py")), CostClass.Fast, Trigger.StepBoundary, acceptanceIds = listOf("AC-S"), command = printing))
            val v = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("planning"), patch("c1", """{"plan.add":{"text":"make a return 10","accept":"AC-S"}},{"plan.cursor":1},{"next":"edit a"}"""))),
                Scripted.Reply(listOf(say("editing"), anchored("c2", "src/a.py", v, "    return 1", "    return 10"))),
                Scripted.Reply(listOf(say("ticking"), patch("c3", """{"plan.tick":{"n":1,"evidence":"#1"}},{"next":"done"}"""))),
                Scripted.Reply(listOf(say("done: a returns 10"))),
            )

            val completed = assertIs<CellExit.Completed>(f.run(model))

            val receipt = f.receipts.forCheck("CHK-step").single()
            assertEquals(f.version("src/a.py"), receipt.testedInputs.versions["src/a.py"], "it ran on the edited tree, after the step was left")
            val events = f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Check)))
            assertEquals(listOf(3), events.filter { it.text == "step boundary CHK-step: passed" }.map { it.turn })
            assertEquals(listOf(Layers.NO_BLAST), completed.packet.claims.notTested)
        }
    }

    @Test
    fun `an unparseable call or a bad dependency refuses the whole turn and executes nothing`() = runTest {
        CellFixture(stateRoot).use { f ->
            val v = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("read and edit"), read("c1", "src/a.py"), call("c2", "edit", """{"ops":[],"why":"w"}"""))),
                Scripted.Reply(listOf(say("run after read"), read("c3", "src/a.py"), runCmd("c4", "hi", extra = ",\"if\":\"applied(op:1)\""), anchored("c5", "src/a.py", v, "    return 1", "    return 10"))),
            )

            val exit = f.run(model)

            assertIs<CellExit.Completed>(exit)
            val history = f.transcript(3)
            val results = history.filterIsInstance<ToolResult>().map { resultText(it) }
            assertEquals(5, results.size)
            assertTrue(results.take(2).all { it.contains("not executed: schema error in call c2") && it.contains("no call of this turn executed") }, results.toString())
            assertTrue(results.drop(2).all { it.contains("not executed: op 2: condition applied(op:1) must name an edit op") }, results.toString())
            assertFalse(Items.pairs(history).broken)
            assertNull(f.aliases.resolve(f.ids.work, 1), "no read executed: no alias was ever allocated")
            assertEquals(CellFixture.A_PY, Files.readString(f.repo.resolve("src/a.py")), "no edit executed")
            assertTrue(f.intents.open().isEmpty() && f.intents.get("intent-1") == null, "no run executed")
            assertEquals(listOf(1, 2, 3), f.checkpoints.turns(f.ids.context!!).map { it.turn })
        }
    }

    @Test
    fun `identical calls are nudged, then the turn ends with a required state op that gates the next turn`() = runTest {
        CellFixture(stateRoot).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("look"), tree("c1"))),
                Scripted.Reply(listOf(say("look again"), tree("c2"))),
                Scripted.Reply(listOf(say("and again"), tree("c3"))),
                Scripted.Reply(listOf(say("once more"), tree("c4"))),
                Scripted.Reply(listOf(say("recording"), patch("c5", """{"next":"move on"}"""), tree("c6"))),
            )

            val exit = f.run(model)

            assertIs<CellExit.Completed>(exit)
            assertTrue(f.anchorText(3).contains("loop: look.tree returned the same result 2 times — change the question"), f.anchorText(3))
            assertTrue(f.anchorText(4).contains("loop: look.tree returned the same result 3 times — turn ended; a state op is required"), f.anchorText(4))
            val refused = resultText(f.transcript(5).filterIsInstance<ToolResult>().last())
            assertTrue(refused.contains("not executed: the loop gate ended the last turn: a state op is required"), refused)
            val recorded = f.transcript(6).filterIsInstance<ToolResult>().map { resultText(it) }
            assertTrue(recorded.any { it.contains("STATE v1 · applied 1 op") }, recorded.toString())
            assertEquals(1, f.recorder.ofType<AgentEvent.Cell.GateFired>().count { it.text.contains("same result 2 times") })
        }
    }

    @Test
    fun `the first pressure rebuilds the projection, a second one ends the cell partial, at admission or from the gate`() = runTest {
        CellFixture(stateRoot).use { f ->
            val overflow = f.run(ScriptedModel.of(Scripted.Reply(listOf(say("look"), tree("c1")))), profile = FakeProfiles.tiny)
            val partial = assertIs<CellExit.Partial>(overflow)
            assertEquals(PartialReason.Pressure, partial.reason)
            assertTrue(partial.hint.contains("does not fit the window after a rebuild"), partial.hint)
            assertTrue(f.adapter.calls.isEmpty(), "refused before any spend")
            assertEquals(1, partial.checkpoint.rebuilds)
            assertEquals(CellStatus.Partial, f.checkpoints.latest(f.ids.context!!)!!.status)
        }
        CellFixture(stateRoot.resolve("gate"), defaults = Defaults(alpha = 0.1)).use { f ->
            val small = Profile("small", FakeProfiles.PROVIDER, "fake-small", FakeProfiles.capabilities(12_000, 500), FakeProfiles.main.priceTable)
            val deadEnd = """{"deadend.add":{"text":"monkeypatching the clock","evidence":null,"scope":"tests/","reopen":"fixtures isolated"}},{"next":"look again"}"""
            val model = ScriptedModel.of(Scripted.Reply(listOf(say("look"), tree("c1"), patch("c0", deadEnd))), Scripted.Reply(listOf(say("look again"), tree("c2"))))
            val exit = f.run(model, profile = small, profiles = FakeProfiles.all + (small.id to small))
            val partial = assertIs<CellExit.Partial>(exit)
            assertEquals(PartialReason.Pressure, partial.reason)
            assertTrue(partial.hint.contains("pressure: context") && partial.hint.contains("second pressure"), partial.hint)
            assertEquals(2, f.adapter.calls.size, "the rebuilt projection took one more turn")
            assertEquals(2, partial.checkpoint.turn)
            assertEquals(1, partial.checkpoint.rebuilds)
            assertTrue(f.transcript(2).filterIsInstance<io.astrolabe.provider.Message>().any { it.text.startsWith("rebuilt: pressure (generation 1)") }, "the rebuild is announced in the pinned transcript")
            assertTrue(f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Boundary))).any { it.text.startsWith("rebuilt: pressure (generation 1)") })
            // FX-11 through pressure: the scoped dead end survives the rebuild and KNOWN is declared as the seeds only.
            assertTrue(f.anchorText(2).contains("monkeypatching the clock") && f.anchorText(2).contains("scope: tests/"), f.anchorText(2))
            assertTrue(f.transcript(2).filterIsInstance<io.astrolabe.provider.Message>().any { "KNOWN: seeds only" in it.text })
        }
    }

    @Test
    fun `the turn budget ends the cell partial after reserve turns that refuse edits`() = runTest {
        CellFixture(stateRoot).use { f ->
            val v = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("look"), tree("c1"))),
                Scripted.Reply(listOf(say("edit on the reserve"), anchored("c2", "src/a.py", v, "    return 1", "    return 10"))),
                Scripted.Reply(listOf(say("look on the reserve"), tree("c3"))),
                Scripted.Reply(listOf(say("never sent"), tree("c4"))),
            )

            val exit = f.run(model, turns = 3)

            val partial = assertIs<CellExit.Partial>(exit)
            assertEquals(PartialReason.TurnBudget, partial.reason)
            assertEquals(3, partial.turns)
            assertEquals(3, f.adapter.calls.size)
            assertTrue(f.anchorText(2).contains(CellBudget.GATE), f.anchorText(2))
            assertFalse(f.request(2).mask!!.allows("edit.anchored"), "edits are masked on a reserve turn")
            val refused = resultText(f.transcript(3).filterIsInstance<ToolResult>().last())
            assertTrue(refused.contains("not executed: ${CellBudget.GATE}; the edit refused the whole turn"), refused)
            assertEquals(CellFixture.A_PY, Files.readString(f.repo.resolve("src/a.py")))
            val turn3 = f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Result))).filter { it.turn == 3 }
            assertTrue(turn3.single().text.contains("tool=look"), "a read still runs on a reserve turn: $turn3")
            assertEquals(CellStatus.Partial, partial.checkpoint.status)
            assertTrue(partial.checkpoint.reason!!.contains("TurnBudget"))
        }
    }

    @Test
    fun `state blocked ends the cell blocked after reconciliation with a checkpoint`() = runTest {
        CellFixture(stateRoot).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("asking"), call("c1", "state", """{"op":"blocked","blocked":{"reason":"need the API key","evidence":[],"question":"which key?"}}"""))),
            )

            val exit = f.run(model)

            val blocked = assertIs<CellExit.Blocked>(exit)
            assertEquals("which key?", blocked.request.question)
            assertEquals(1, blocked.turns)
            assertEquals(CellStatus.Blocked, f.checkpoints.latest(f.ids.context!!)!!.status)
            assertEquals("blocked", f.recorder.ofType<AgentEvent.Cell.Ended>().single().status)
        }
    }

    // ------------------------------------------------------- fault injection

    @Test
    fun `a lost observation during a command leaves an open intent that every later checkpoint carries`() = runTest {
        val lossy = { local: io.astrolabe.os.LocalOs ->
            object : Os by local {
                private var failed = false
                override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
                    if (!failed) {
                        failed = true
                        throw IOException("observation lost")
                    }
                    return local.poll(proc, sinceCursorBytes, observationTimeoutSeconds)
                }
            }
        }
        CellFixture(stateRoot, osOverride = lossy).use { f ->
            val model = ScriptedModel.build {
                reply(say("running"), runCmd("c1", "hello"))
                fault(FaultKind.Transport)
            }

            val exit = f.run(model)

            val failed = assertIs<CellExit.Failed>(exit)
            assertTrue(failed.error.contains("provider Transport"), failed.error)
            val result = resultText(f.transcript(2).filterIsInstance<ToolResult>().single())
            assertTrue(result.contains("unknown_outcome") && result.contains("intent intent-1 stays open"), result)
            assertEquals(IntentStatus.Unknown, f.intents.get("intent-1")!!.status)
            val turns = f.checkpoints.turns(f.ids.context!!)
            assertEquals(listOf("intent-1"), turns.first().openIntents)
            assertEquals(listOf("intent-1"), failed.checkpoint.openIntents)
            assertEquals(CellStatus.Failed, f.checkpoints.latest(f.ids.context!!)!!.status)
        }
    }

    /**
     * The edit's publications, in order, once the read has already published the file's bytes (so the preimage put
     * is a no-op): the postimage (1st), then the rendered result body (2nd). A crash at the 1st lands after the
     * write and before the registry hears of it; a crash at the 2nd lands after the announcement and the post-edit
     * view, before the observation — "after a mutation, before the receipt" in both of its forms.
     */
    private fun crashAfterMutation(root: Path, nth: Int) = runTest {
        val crash = ArmedCrash(nth)
        CellFixture(root, faults = crash.faults).use { f ->
            val v = f.version("src/a.py")
            val model = ScriptedModel.build {
                reply(say("reading"), read("c1", "src/a.py"))
                on({ true }) { crash.arm(); Scripted.Reply(listOf(say("editing"), anchored("c2", "src/a.py", v, "    return 1", "    return 10"))) }
            }

            val exit = f.run(model)

            assertEquals(1, crash.crashes)
            assertIs<CellExit.Completed>(exit)
            assertEquals(edited(), Files.readString(f.repo.resolve("src/a.py")), "the mutation happened")
            val after = f.version("src/a.py")
            val result = resultText(f.transcript(3).filterIsInstance<ToolResult>().last())
            assertTrue(result.contains("failed: InjectedCrash") && result.contains("effects unknown; reconciled at the turn boundary"), result)
            assertEquals(after, f.registry.recorded("src/a.py"), "the registry knows the new version, announced by the edit or by the boundary reconcile")
            assertTrue(f.workset.entries.none { it.path == "src/a.py" }, "neither the stale read nor an unseen post-edit view stays KNOWN: ${f.workset.entries}")
            val turns = f.checkpoints.turns(f.ids.context!!)
            assertNotEquals(turns[0].stamp, turns[1].stamp)
            assertEquals(listOf("src/a.py"), turns[1].touched)
            assertEquals(after, f.preimages.of("edit-1").single().versionAfter, "the preimage record survived the crash: the edit is reversible")
            assertEquals(1, f.receipts.forCheck(Checks.TYPES_TOUCHED).size, "the checker ran on the touched path")
            assertNull(f.observations.get("obs-2"), "no observation was recorded for the crashed edit")
            assertEquals(CellStatus.Completed, f.checkpoints.latest(f.ids.context!!)!!.status)
            val reconciles = f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Reconcile)))
            if (nth == 1) assertEquals(listOf("src/a.py"), reconciles.single().refs, "the boundary announced what the crashed edit could not") else assertTrue(reconciles.isEmpty(), "the edit itself announced the move")
        }
    }

    @Test
    fun `a crash after a mutation and before the registry hears of it is reconciled at the boundary and checkpointed`() = crashAfterMutation(stateRoot, nth = 1)

    @Test
    fun `a crash after the announcement and before the observation drops the unseen coverage and checkpoints`() = crashAfterMutation(stateRoot, nth = 2)

    @Test
    fun `an external effect with a lost ack stays an open intent through the exit`() = runTest {
        // The run's first publication is its log, inside the persist step: the process ran, the ack was lost.
        val crash = ArmedCrash(nth = 1)
        CellFixture(stateRoot, faults = crash.faults).use { f ->
            val model = ScriptedModel.build {
                on({ true }) { crash.arm(); Scripted.Reply(listOf(say("running"), runCmd("c1", "effect"))) }
            }

            val exit = f.run(model)

            assertEquals(1, crash.crashes)
            assertIs<CellExit.Completed>(exit)
            val result = resultText(f.transcript(2).filterIsInstance<ToolResult>().single())
            assertTrue(result.contains("unknown_outcome") && result.contains("never relaunch"), result)
            assertEquals(IntentStatus.Unknown, f.intents.get("intent-1")!!.status)
            assertEquals(listOf("intent-1"), exit.checkpoint.openIntents)
            assertEquals(listOf("intent-1"), f.checkpoints.turns(f.ids.context!!).first().openIntents)
        }
    }

    @Test
    fun `a provider fault exits failed with a checkpoint and no spend recorded twice`() = runTest {
        CellFixture(stateRoot).use { f ->
            val exit = f.run(ScriptedModel.build { fault(FaultKind.Transport) })

            val failed = assertIs<CellExit.Failed>(exit)
            assertTrue(failed.error.contains("connection reset"), failed.error)
            assertEquals(1, f.adapter.calls.size)
            assertEquals(1, failed.checkpoint.turn)
            assertEquals(CellStatus.Failed, f.checkpoints.latest(f.ids.context!!)!!.status)
            assertEquals(1L, StoreInspector(f.store).count("turns"))
        }
    }

    @Test
    fun `cancellation persists a cancelled checkpoint before it propagates`() = runTest {
        CellFixture(stateRoot).use { f ->
            val context = f.context(ScriptedModel.of(Scripted.Reply(listOf(say("look"), tree("c1")))), holdResponses = true)
            val job = launch { f.cell().run(context, f.increment, f.budget()) }
            while (f.adapter.invocation(InvocationId("inv-1")) == null) yield()

            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            val latest = assertNotNull(f.checkpoints.latest(f.ids.context!!))
            assertEquals(CellStatus.Cancelled, latest.status)
            assertEquals(1, latest.turn)
            // The bus delivers on its own dispatcher: wait for the event rather than race it.
            val deadline = System.nanoTime() + 5_000_000_000L
            while (f.recorder.ofType<AgentEvent.Cell.Ended>().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("cancelled", f.recorder.ofType<AgentEvent.Cell.Ended>().single().status)
        }
    }

    @Test
    fun `dispatch authority refuses before any spend`() = runTest {
        CellFixture(stateRoot).use { f ->
            val exit = f.run(ScriptedModel.of(Scripted.Reply(listOf(say("look"), tree("c1")))), authority = DispatchAuthority { DispatchRefusal("execution generation superseded", cancelled = true) })

            val cancelled = assertIs<CellExit.Cancelled>(exit)
            assertEquals("execution generation superseded", cancelled.reason)
            assertTrue(f.adapter.calls.isEmpty())
            assertEquals(CellStatus.Cancelled, f.checkpoints.latest(f.ids.context!!)!!.status)
        }
    }

    @Test
    fun `results are stubbed on the k cadence and the history stays a valid protocol sequence`() = runTest {
        CellFixture(stateRoot, defaults = Defaults(k = 2)).use { f ->
            val model = ScriptedModel.of(*(1..5).map { Scripted.Reply(listOf(say("look $it"), tree("c$it"))) }.toTypedArray())

            val exit = f.run(model)

            assertIs<CellExit.Completed>(exit)
            val history = f.transcript(5)
            val stubs = history.filterIsInstance<ToolResult>().map { resultText(it) }.filter { it.startsWith("⟦stub ") }
            assertEquals(2, stubs.size, "turns 1 and 2 have lived k turns by the batch of turn 4: $stubs")
            assertTrue(stubs.first().startsWith("⟦stub #1 look.tree tree /") && stubs.first().contains("recall #1"), stubs.first())
            assertFalse(Items.pairs(history).broken)
            assertTrue(f.adapter.validations.all { it.result == Validation.Ok })
            assertTrue(f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Boundary))).any { it.text.startsWith("eviction age at turn 4: 2 stubbed") })
        }
    }
}
