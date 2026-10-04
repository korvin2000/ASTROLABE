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
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Ledger
import io.astrolabe.register.ContractDigest
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
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.Validation
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.Checks
import io.astrolabe.verify.CostClass
import io.astrolabe.evidence.Outcome
import io.astrolabe.verify.Selector
import io.astrolabe.verify.Trigger
import io.astrolabe.workspace.LineRange
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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
    @Test
    fun `create permission cannot admit a later delete in the same edit call`() = runTest {
        CellFixture(stateRoot).use { f ->
            val role = Roles.implementing.copy(toolMask = io.astrolabe.provider.ToolMask.of("look.read", "edit.create"))
            f.run(ScriptedModel.of(
                Scripted.Reply(listOf(read("read", "src/a.py"))),
                Scripted.Reply(listOf(call("mixed", "edit", """{"ops":[{"create":"src/new.py","content":"new"},{"delete":"src/a.py","expect":"${f.version("src/a.py").digest.hex}"}],"why":"mixed"}"""))),
                Scripted.Reply(listOf(say("stop")), stop = io.astrolabe.provider.StopReason.Refusal),
            ), role = role)
            assertTrue(Files.exists(f.repo.root.resolve("src/a.py")))
            assertFalse(Files.exists(f.repo.root.resolve("src/new.py")))
        }
    }

    @Test
    fun `plan and probe completion reach their validator without running product acceptance`() = runTest {
        for (role in listOf(Roles.plan, Roles.probe)) {
            CellFixture(stateRoot.resolve(role.name)).use { f ->
                var assessed = false
                val exit = f.run(ScriptedModel.of(Scripted.Reply(listOf(say("packet")))), role = role,
                    completion = RoleCompletion { _, _ -> assessed = true; CompletionDecision.Accepted(emptyList()) })
                assertTrue(assessed)
                assertIs<CellExit.Completed>(exit)
                assertTrue(f.checks.required().all { it.last == null }, "product checks belong to implementing completion")
            }
        }
    }

    @Test
    fun `every request of a cell carries its campaign's session key`() = runTest {
        CellFixture(stateRoot).use { f ->
            f.run(ScriptedModel.of(Scripted.Reply(listOf(tree("c1"))), Scripted.Reply(listOf(say("done")))))
            assertTrue(f.adapter.calls.size >= 2)
            assertEquals(setOf(f.ids.work.sessionKey), f.adapter.calls.map { it.request.sessionKey }.toSet())
        }
    }

    @Test
    fun `partial usage retains the generation reservation including known overruns`() = runTest {
        val input = io.astrolabe.provider.BillingDimension.UNCACHED_INPUT
        val output = io.astrolabe.provider.BillingDimension.OUTPUT
        for ((index, quantities) in listOf(mapOf(input to 1L), mapOf(output to 1L), mapOf(output to 100_000L)).withIndex()) {
            CellFixture(stateRoot.resolve("usage-$index")).use { f ->
                val base = f.context(ScriptedModel.of(Scripted.Reply(listOf(say("refused")), stop = io.astrolabe.provider.StopReason.Refusal)))
                val budget = f.budget()
                var held = 0L
                val adapter = object : io.astrolabe.provider.ProviderAdapter by base.model.adapter {
                    override fun start(request: io.astrolabe.provider.Request, id: InvocationId): io.astrolabe.provider.Invocation {
                        held = budget.working.heldTokens.value
                        val original = base.model.adapter.start(request, id)
                        return object : io.astrolabe.provider.Invocation by original {
                            override suspend fun terminal(): io.astrolabe.provider.Terminal = original.terminal().copy(
                                usage = io.astrolabe.provider.BillableUsage(quantities,
                                    io.astrolabe.provider.UsageProvenance("fake", "main", "test"), setOf(input, output) - quantities.keys),
                            )
                            override suspend fun await(): io.astrolabe.provider.Response = original.await().copy(
                                usage = io.astrolabe.provider.BillableUsage(quantities,
                                    io.astrolabe.provider.UsageProvenance("fake", "main", "test"), setOf(input, output) - quantities.keys),
                            )
                        }
                    }
                }
                val context = CellContext(base.ids, base.role, base.contracts,
                    CellModel(adapter, base.model.profile, base.model.estimator), base.tools, base.workspace, base.evidence, base.prime)
                f.cell().run(context, f.increment, budget)
                assertTrue(held > 1)
                assertTrue(budget.working.spent.value >= held, "partial usage released conservative funding")
                assertTrue(budget.working.spent.value >= quantities.values.sum(), "known overrun was lost")
            }
        }
    }

    @Test
    fun `role masked edits and runs are refused before dispatch`() = runTest {
        for (role in listOf(Roles.probe, Roles.plan, Roles.review)) {
            CellFixture(stateRoot.resolve(role.name)).use { f ->
                val before = f.version("src/a.py")
                // Plan and probe run R-class commands; an edit is what their masks refuse.
                val masked = if (role == Roles.review) runCmd("c2", "echo masked > src/forbidden.txt")
                    else anchored("c2", "src/a.py", before, "    return 1", "    return 10")
                f.run(ScriptedModel.of(
                    Scripted.Reply(listOf(read("c1", "src/a.py"))),
                    Scripted.Reply(listOf(masked)),
                    Scripted.Reply(listOf(say("done"))),
                ), role = role)
                assertEquals(before, f.version("src/a.py"))
                assertFalse(Files.exists(f.repo.root.resolve("src/forbidden.txt")))
                assertTrue(f.transcript(3).filterIsInstance<ToolResult>().any { "is not available to the ${role.name} role in this cell (any turn)" in resultText(it) })
            }
        }
    }

    @Test
    fun `a custom role that may poll may wait in its request mask refusals and lines`() = runTest {
        CellFixture(stateRoot).use { f ->
            val poller = Roles.implementing.copy(name = "poller", toolMask = io.astrolabe.provider.ToolMask(Roles.implementing.toolMask.allowed - "run.wait"))
            f.run(ScriptedModel.of(
                Scripted.Reply(listOf(call("c1", "run", """{"op":"wait","handle":"handle-9","timeout":1}"""))),
                Scripted.Reply(listOf(say("done"))),
            ), role = poller)

            assertTrue(f.request(1).mask!!.allows("run.wait"), f.request(1).mask.toString())
            val system = f.request(1).segment(SegmentKind.S)!!.items.filterIsInstance<io.astrolabe.provider.Message>().joinToString("\n") { it.text }
            assertTrue(system.lineSequence().first { it.startsWith("tools: ") }.contains("run(run, poll, wait, cancel)"), system)
            assertFalse(f.anchorText(1).lineSequence().first { it.startsWith("enabled this turn") }.contains("run.wait"), f.anchorText(1))
            val result = f.transcript(2).filterIsInstance<ToolResult>().joinToString("\n") { resultText(it) }
            assertFalse(result.contains("is not available to the poller role"), result)
            assertTrue(result.contains("no handle 'handle-9'"), result)
        }
    }

    @Test
    fun `profile request estimator participates in dispatch admission`() = runTest {
        CellFixture(stateRoot).use { f ->
            val base = f.context(ScriptedModel.of())
            var counted = 0
            val estimator = object : io.astrolabe.provider.TokenEstimator by f.estimator {
                override fun estimate(request: io.astrolabe.provider.Request): io.astrolabe.provider.Estimate {
                    counted++
                    return io.astrolabe.provider.Estimate(Long.MAX_VALUE, true, id, version)
                }
            }
            val context = CellContext(base.ids, base.role, base.contracts,
                CellModel(base.model.adapter, base.model.profile, estimator), base.tools, base.workspace, base.evidence, base.prime)
            val exit = assertIs<CellExit.Partial>(f.cell().run(context, f.increment, f.budget()))
            assertEquals(PartialReason.Pressure, exit.reason)
            assertTrue(counted > 0)
            assertTrue(f.adapter.calls.isEmpty())
        }
    }

    @Test
    fun `a digest that cannot fit stops before provider dispatch`() = runTest {
        CellFixture(stateRoot, defaults = Defaults(digestCapTokens = 1)).use { f ->
            val exit = assertIs<CellExit.Partial>(f.run(ScriptedModel.of()))
            assertEquals(PartialReason.Pressure, exit.reason)
            assertTrue(exit.hint.contains("contract digest needs"), exit.hint)
            assertTrue(f.adapter.calls.isEmpty())
            assertEquals(CellStatus.Partial, f.checkpoints.latest(f.ids.context!!)!!.status)
        }
    }

    @Test
    fun `a 60-requirement contract runs without digest pressure under defaults`() = runTest {
        val sixty: (Contract) -> Contract = { c ->
            val r1 = c.requirements.first()
            c.copy(requirements = (1..60).map { i -> r1.copy(id = "R$i", text = "requirement $i of the contract") })
        }
        CellFixture(stateRoot.resolve("scaled"), shapeContract = sixty).use { f ->
            val estimator = f.estimator
            fun cost(c: Contract) =
                estimator.estimate(ContractDigest.render(c, Ledger.initial(c), emptyList(), estimator, 100_000)).tokens
            val perRequirement = (cost(f.contract) - cost(f.contract.copy(requirements = f.contract.requirements.take(1)))) / 59.0
            assertTrue(perRequirement <= Defaults().digestTokensPerRequirement, "measured $perRequirement tokens per requirement")
            val exit = f.run(ScriptedModel.of(Scripted.Reply(listOf(say("done")))))
            assertFalse(exit is CellExit.Partial && exit.reason == PartialReason.Pressure, exit.toString())
            assertTrue(f.adapter.calls.isNotEmpty())
            assertTrue(f.anchorText(1).contains("R60 pending"), f.anchorText(1))
        }
        CellFixture(stateRoot.resolve("pinned"), defaults = Defaults(digestTokensPerRequirement = 0), shapeContract = sixty).use { f ->
            val exit = assertIs<CellExit.Partial>(f.run(ScriptedModel.of()))
            assertEquals(PartialReason.Pressure, exit.reason)
            assertTrue(exit.hint.contains("contract digest needs"), exit.hint)
        }
    }

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
    fun `a fully displayed reference lookup resolves the public signature impact nudge`() = runTest {
        CellFixture(stateRoot).use { f ->
            val v = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("reading a"), read("c1", "src/a.py"), patch("c2", """{"plan.add":"make a return 10"},{"plan.cursor":1},{"next":"edit a"}"""))),
                Scripted.Reply(listOf(say("editing"), anchored("c3", "src/a.py", v, "def a():", "def a(scale=1):"))),
                Scripted.Reply(listOf(say("ticking"), patch("c4", """{"plan.tick":{"n":1,"evidence":"#2"}},{"next":"done"}"""),
                    call("c5", "look", """{"what":"refs","target":"a","budget":400}"""))),
                Scripted.Reply(listOf(say("done"))),
                Scripted.Reply(listOf(say("done"))),
            )

            f.run(model)

            assertTrue((4..f.adapter.calls.size).none { f.anchorText(it).contains("unresolved impact nudge") },
                (4..f.adapter.calls.size).joinToString("\n---\n") { f.anchorText(it) })
        }
    }

    @Test
    fun `an incomplete reference lookup leaves the public signature impact nudge unresolved`() = runTest {
        CellFixture(stateRoot).use { f ->
            val v = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("reading a"), read("c1", "src/a.py"), patch("c2", """{"plan.add":"make a return 10"},{"plan.cursor":1},{"next":"edit a"}"""))),
                Scripted.Reply(listOf(say("editing"), anchored("c3", "src/a.py", v, "def a():", "def a(scale=1):"))),
                Scripted.Reply(listOf(say("ticking"), patch("c4", """{"plan.tick":{"n":1,"evidence":"#2"}},{"next":"done"}"""),
                    call("c5", "look", """{"what":"refs","target":"a","budget":15}"""))),
                Scripted.Reply(listOf(say("done"))),
                Scripted.Reply(listOf(say("done"))),
            )

            val exit = f.run(model)

            // Past its rework round the unresolved nudge goes to the decider (I2): never accepted without a word.
            assertEquals(io.astrolabe.verify.StopCode.AcceptanceDecision, assertIs<CellExit.Completed>(exit).pending?.code)
            val line = "impact: `a` (src/a.py) signature changed; 1 reference not inspected → look(refs) or scope the plan"
            assertTrue(f.anchorText(3).contains(line), f.anchorText(3))
            assertFalse(f.anchorText(4).contains(line), "once per changed symbol: " + f.anchorText(4))
            assertTrue(f.anchorText(5).contains("unresolved impact nudge: `a` (src/a.py) signature changed"), f.anchorText(5))
        }
    }

    @Test
    fun `deleting a scratch file the cell created raises no impact nudge`() = runTest {
        CellFixture(stateRoot).use { f ->
            val scratch = "def a():\n    return 0\n"
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("probe"), call("c1", "edit", """{"ops":[{"create":"src/scratch.py","content":${CellFixture.quote(scratch)}}],"why":"probe"}"""))),
                Scripted.Reply(listOf(say("reading"), read("c2", "src/scratch.py"))),
                Scripted.Reply(listOf(say("cleanup"), call("c3", "edit", """{"ops":[{"delete":"src/scratch.py","expect":"${io.astrolabe.id.Digest.of(scratch.toByteArray()).hex}"}],"why":"cleanup"}"""))),
                Scripted.Reply(listOf(say("look"), tree("c4"))),
                Scripted.Reply(listOf(say("look"), tree("c5"))),
            )

            f.run(model, turns = 5)

            assertFalse(Files.exists(f.repo.resolve("src/scratch.py")), "the scratch file was deleted")
            assertFalse(f.anchorText(4).contains("impact:"), f.anchorText(4))
        }
    }

    @Test
    fun `a created module a pre-existing file already imported raises the impact nudge`() = runTest {
        // The second mentions the module only on a line that also declares its stem, which `refs` leaves out.
        listOf(
            "from src.helpers import helper\n\nprint(helper())\n",
            "def helpers(): return __import__(\"src.helpers\", fromlist=[\"helper\"]).helper()\n",
        ).forEachIndexed { n, app -> createdModuleNudge(stateRoot.resolve("app-$n"), app) }
    }

    private suspend fun createdModuleNudge(root: java.nio.file.Path, app: String) {
        CellFixture(root, files = CellFixture.DEFAULT_FILES + ("src/app.py" to app)).use { f ->
            val created = "def helper():\n    return 0\n"
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("extract"), call("c1", "edit", """{"ops":[{"create":"src/helpers.py","content":${CellFixture.quote(created)}}],"why":"extract"}"""))),
                Scripted.Reply(listOf(say("reading"), read("c2", "src/helpers.py"))),
                Scripted.Reply(listOf(say("widen"), anchored("c3", "src/helpers.py", io.astrolabe.id.FileVersion.of(created.toByteArray()), "def helper():", "def helper(scale=1):"))),
                Scripted.Reply(listOf(say("look"), tree("c4"))),
                Scripted.Reply(listOf(say("look"), tree("c5"))),
            )

            f.run(model, turns = 5)

            assertTrue(f.anchorText(4).contains("impact: `helper` (src/helpers.py) signature changed"), "$app\n" + f.anchorText(4))
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
            assertEquals(listOf("blast radius: no test command declared by the repository"), completed.packet.claims.notTested)
        }
    }

    @Test
    fun `leaving a step runs the blast-selected tests, widened to the workspace suite by the tier-0 graph, and the verify line says so`() = runTest {
        val pass = javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
        CellFixture(stateRoot, files = CellFixture.DEFAULT_FILES + ("pytest_pass.txt" to pass)).use { f ->
            val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
            f.checks.register(Check(Checks.FULL, CheckKind.Full, Selector.All, Closure.Unknown, CostClass.Expensive, Trigger.CampaignEnd, command = printing))
            val v = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("planning"), read("c0", "src/a.py"), patch("c1", """{"plan.add":{"text":"make a return 10","accept":"AC-1"}},{"plan.cursor":1},{"next":"edit a"}"""))),
                Scripted.Reply(listOf(say("editing"), anchored("c2", "src/a.py", v, "    return 1", "    return 10"))),
                Scripted.Reply(listOf(say("ticking"), patch("c3", """{"plan.tick":{"n":1,"evidence":"#2"}},{"next":"done"}"""))),
                Scripted.Reply(listOf(say("done: a returns 10"))),
            )

            f.run(model)

            val receipt = f.receipts.forCheck(Checks.TESTS_BLAST).single()
            assertEquals(Outcome.Passed, receipt.outcome)
            assertEquals(Closure.Unknown, f.checks[Checks.TESTS_BLAST]!!.inputClosure, "no manifest: the package is unknown, the workspace suite runs")
            assertTrue(f.anchorText(4).contains("tests(workspace)"), f.anchorText(4))
        }
    }

    @Test
    fun `an unparseable call is refused alone and a bad dependency among valid calls refuses the whole turn`() = runTest {
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
            assertFalse(results[0].contains("not executed"), "D-372: the read beside the unparseable call ran: $results")
            assertTrue(results[1].contains("not executed: schema error in call c2") && results[1].contains("this call was refused; the other 1 call of the turn ran"), results.toString())
            assertFalse(results[1].contains("no call of this turn executed"), results[1])
            assertTrue(results.drop(2).all { it.contains("not executed: op 2: condition applied(op:1) must name an edit op") && it.contains("no call of this turn executed") }, results.toString())
            assertFalse(Items.pairs(history).broken)
            assertEquals(CellFixture.A_PY, Files.readString(f.repo.resolve("src/a.py")), "no edit executed")
            assertTrue(f.intents.open().isEmpty() && f.intents.get("intent-1") == null, "no run executed")
            assertEquals(listOf(1, 2, 3), f.checkpoints.turns(f.ids.context!!).map { it.turn })
        }
    }

    @Test
    fun `the budget and stall lines of the anchor come before impact lines, up to four nudges`() = runTest {
        CellFixture(stateRoot).use { f ->
            val impact = object : Gate {
                override val name: String = Gates.IMPACT
                override fun evaluate(state: GateState): List<GateOutcome> =
                    (1..4).map { GateOutcome.Nudge(GateKey(name, "s$it@${state.turn}"), "impact: symbol $it changed; references not inspected") }
            }
            val turns = object : Gate {
                override val name: String = Gates.TURNS
                override fun evaluate(state: GateState): List<GateOutcome> =
                    listOf(GateOutcome.Nudge(GateKey(name, "t${state.turn}"), "turns: turn ${state.turn} of ${state.turnsMax} — verify and report"))
            }
            val cell = Cell(f.clock, f.idGen, f.defaults, Gates(listOf(impact, turns)), f.events)

            cell.run(f.context(ScriptedModel.of(Scripted.Reply(listOf(say("look"), tree("c1"))), Scripted.Reply(listOf(say("done"))))), f.increment, f.budget())

            val anchor = f.anchorText(2)
            assertTrue(anchor.contains("turns: turn 1 of 12 — verify and report"), anchor)
            assertEquals(3, (1..4).count { anchor.contains("impact: symbol $it changed") }, anchor)
            assertTrue(anchor.indexOf("turns: turn 1") < anchor.indexOf("impact: symbol 1"), anchor)
        }
    }

    @Test
    fun `the checks view carries the launcher's reason for an unavailable check, bounded`() = runTest {
        CellFixture(stateRoot).use { f ->
            val check = f.checks[Checks.TYPES_TOUCHED]!!
            val stamp = f.stamper.report().candidateId
            val reason = "cannot start npm: 'npm' was not found in the working directory or on PATH (PATHEXT .COM;.EXE;.BAT;.CMD)" + " and more".repeat(20)
            f.scheduler.record(io.astrolabe.verify.CheckerResult(check.id, "chk-x", check.kind, check.selector, Outcome.Unavailable, emptyList(), null, null, stamp, stamp, null, 0, emptyList(), emptyList(), reason = reason), f.contract.version)

            f.run(ScriptedModel.of(Scripted.Reply(listOf(say("look"), tree("c1")))))

            val anchor = f.anchorText(1)
            assertTrue(anchor.contains("unavailable (${reason.take(159)}…)"), anchor)
            assertFalse(anchor.contains("runner missing"), anchor)
        }
    }

    @Test
    fun `a malformed state call beside a valid edit refuses only the state call`() = runTest {
        CellFixture(stateRoot).use { f ->
            val v = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("read"), read("c1", "src/a.py"))),
                Scripted.Reply(listOf(say("edit and record"), anchored("c2", "src/a.py", v, "    return 1", "    return 10"), call("c3", "state", "not json"))),
                Scripted.Reply(listOf(say("done"))),
            )

            f.run(model)

            assertTrue(Files.readString(f.repo.resolve("src/a.py")).contains("return 10"), "the edit applied")
            val results = f.transcript(3).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertFalse(results.getValue("c2").contains("not executed"), results.getValue("c2"))
            val refused = results.getValue("c3")
            assertTrue(refused.contains("not executed: schema error in call c3: state: arguments are not a JSON object"), refused)
            assertTrue(refused.contains("this call was refused; the other 1 call of the turn ran") && !refused.contains("no call of this turn executed"), refused)
        }
    }

    @Test
    fun `a state call with one surplus closing brace applies and its result names the repair`() = runTest {
        CellFixture(stateRoot).use { f ->
            val args = """{"op":"patch","patch":[{"plan.add":"edit a"},{"next":"edit a"}}]}"""
            f.run(ScriptedModel.of(Scripted.Reply(listOf(say("record"), call("c1", "state", args))), Scripted.Reply(listOf(say("done")))))

            val result = f.transcript(2).filterIsInstance<ToolResult>().single().let(::resultText)
            assertTrue(result.contains("note: arguments repaired: dropped '}' at ${args.length - 3}"), result)
            assertTrue(result.contains("applied 2 ops") && !result.contains("not executed"), result)
        }
    }

    @Test
    fun `three valid reads run beside a look with a bad argument`() = runTest {
        CellFixture(stateRoot).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("read"), read("c1", "src/a.py"), read("c2", "src/b.py"), call("c3", "look", """{"what":"read","target":"README.md","budget":0}"""), read("c4", "tests/test_a.py"))),
                Scripted.Reply(listOf(say("done"))),
            )

            f.run(model)

            val results = f.transcript(2).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertTrue(listOf("c1", "c2", "c4").none { results.getValue(it).contains("not executed") }, results.toString())
            assertTrue(results.getValue("c4").contains("assert a() == 1"), results.getValue("c4"))
            val refused = results.getValue("c3")
            assertTrue(refused.contains("not executed: schema error in call c3: look: budget must be positive") && refused.contains("the other 3 calls of the turn ran"), refused)
        }
    }

    @Test
    fun `a refused edit holds back the turn's runs and the calls conditioned on a refused op while reads run`() = runTest {
        CellFixture(stateRoot).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("read, edit, run"), read("c1", "src/a.py"), call("c2", "edit", """{"ops":[],"why":"w"}"""), runCmd("c3", "hi"))),
                Scripted.Reply(listOf(say("run behind a broken run"), call("c4", "run", "not json"), runCmd("c5", "hi", extra = ",\"if\":\"green(op:1)\""), read("c6", "src/b.py"))),
                Scripted.Reply(listOf(say("done"))),
            )

            f.run(model)

            val results = f.transcript(3).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertFalse(results.getValue("c1").contains("not executed"), results.getValue("c1"))
            assertTrue(results.getValue("c2").contains("schema error in call c2") && results.getValue("c2").contains("the other 1 call of the turn ran"), results.getValue("c2"))
            assertTrue(results.getValue("c3").contains("not executed: the edit batch did not apply fully: op 2 was refused; runs execute only after a fully applied batch or none"), results.getValue("c3"))
            assertTrue(results.getValue("c5").contains("not executed: depends on op 1, which was refused"), results.getValue("c5"))
            assertFalse(results.getValue("c6").contains("not executed"), results.getValue("c6"))
            assertTrue(f.intents.open().isEmpty() && f.intents.get("intent-1") == null, "no run executed")
        }
    }

    @Test
    fun `plan and probe cells may wait on a run handle, the cell gate does not mask run wait`() = runTest {
        for (role in listOf(Roles.plan, Roles.probe)) {
            CellFixture(stateRoot.resolve(role.name)).use { f ->
                val model = ScriptedModel.of(
                    Scripted.Reply(listOf(say("wait"), call("c1", "run", """{"op":"wait","handle":"handle-x","timeout":5}"""))),
                    Scripted.Reply(listOf(say("packet"))),
                )

                f.run(model, role = role, completion = RoleCompletion { _, _ -> CompletionDecision.Accepted(emptyList()) })

                val waited = resultText(f.transcript(2).filterIsInstance<ToolResult>().single { it.callId == "c1" })
                assertFalse(waited.contains("not available to the ${role.name} role"), "${role.name}: $waited")
                assertTrue(waited.contains("no handle 'handle-x'"), "${role.name}: the call reached the run executor: $waited")
            }
        }
    }

    @Test
    fun `a masked op is refused alone while the other calls of the turn run`() = runTest {
        CellFixture(stateRoot).use { f ->
            val before = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("look, edit, record"), read("c1", "src/a.py"), anchored("c2", "src/a.py", before, "    return 1", "    return 10"), patch("c3", """{"next":"hand the plan over"}"""))),
                Scripted.Reply(listOf(say("packet"))),
            )

            f.run(model, role = Roles.plan, completion = RoleCompletion { _, _ -> CompletionDecision.Accepted(emptyList()) })

            assertEquals(before, f.version("src/a.py"))
            val results = f.transcript(2).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertFalse(results.getValue("c1").contains("not executed"), results.getValue("c1"))
            assertTrue(results.getValue("c3").contains("STATE v1 · applied 1 op"), results.getValue("c3"))
            val refused = results.getValue("c2")
            assertTrue(refused.contains("edit.anchored is not available to the plan role in this cell (any turn)") && refused.contains("the other 2 calls of the turn ran"), refused)
        }
    }

    @Test
    fun `a call refused three times beside calls that ran still ends the cell blocked`() = runTest {
        CellFixture(stateRoot).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(read("c1", "src/a.py"), call("c2", "state", "not json"))),
                Scripted.Reply(listOf(read("c3", "src/b.py"), call("c4", "state", "not json"))),
                Scripted.Reply(listOf(read("c5", "README.md"), call("c6", "state", "not json"))),
                Scripted.Reply(listOf(say("never sent"))),
            )

            val blocked = assertIs<CellExit.Blocked>(f.run(model))

            assertEquals(3, f.adapter.calls.size, "the third identical refusal ends the cell")
            assertTrue(blocked.request.reason.startsWith("refusal loop: schema error in call c2: state: arguments are not a JSON object"), blocked.request.reason)
            assertEquals(listOf("3 identical refused calls of state since turn 1"), blocked.request.evidence)
            assertTrue(f.anchorText(3).contains("refusal loop: state was refused 2 times for the same reason"), f.anchorText(3))
        }
    }

    @Test
    fun `a direct cell sends its own S and schemas and refuses the declared note and finish typed until they exist`() = runTest {
        CellFixture(stateRoot).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(
                    call("c1", "state", """{"op":"note","note":{"kind":"hypothesis","text":"a returns 1"}}"""),
                    call("c2", "task", """{"op":"finish","after_checks":true}"""),
                    call("c3", "task", """{"op":"propose","kind":"plan","proposal":{"increments":[]}}"""),
                )),
                Scripted.Reply(listOf(call("c4", "state", """{"op":"blocked","blocked":{"reason":"stop here"}}"""))),
            )

            assertIs<CellExit.Blocked>(f.run(model, role = Roles.direct))

            assertEquals(listOf("look", "edit", "run", "verify", "state", "task"), f.request(1).tools.map { it.name })
            assertEquals(f.request(1).tools, f.request(2).tools, "one schema set for the line")
            val system = (f.request(1).segment(SegmentKind.S)!!.items.single() as io.astrolabe.provider.Message).text
            assertTrue(system.startsWith("astrolabe · role direct · kernel-direct/1 · ") && KernelDirect.render() in system, system)
            assertTrue(f.anchorText(1).contains("enabled this turn: all role tools except task.propose"), f.anchorText(1))
            val results = f.transcript(2).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertTrue(results.getValue("c1").contains("state.note is declared by the direct protocol and not implemented in this harness version"), results.getValue("c1"))
            assertTrue(results.getValue("c2").contains("task.finish is declared by the direct protocol and not implemented in this harness version"), results.getValue("c2"))
            assertTrue(results.getValue("c3").contains("task.propose is not enabled in shape S0"), results.getValue("c3"))
        }
    }

    @Test
    fun `a state op the loop gate requires still refuses the whole turn`() = runTest {
        CellFixture(stateRoot).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("look"), tree("c1"))),
                Scripted.Reply(listOf(say("look again"), tree("c2"))),
                Scripted.Reply(listOf(say("and again"), tree("c3"))),
                Scripted.Reply(listOf(say("read and record badly"), read("c4", "src/a.py"), call("c5", "state", "not json"))),
                Scripted.Reply(listOf(say("done"))),
            )

            f.run(model)

            val results = f.transcript(5).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertTrue(results.getValue("c4").contains("not executed: the loop gate ended the last turn: a state op is required before anything else runs; no call of this turn executed"), results.getValue("c4"))
            assertTrue(results.getValue("c5").contains("not executed: schema error in call c5") && results.getValue("c5").contains("no call of this turn executed"), results.getValue("c5"))
        }
    }

    @Test
    fun `a call refused three times for the same reason ends the cell blocked with that reason`() = runTest {
        CellFixture(stateRoot).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("run it"), runCmd("c1", "make"))),
                Scripted.Reply(listOf(say("run it again"), runCmd("c2", "make"))),
                Scripted.Reply(listOf(say("and again"), runCmd("c3", "make"))),
            )

            val blocked = assertIs<CellExit.Blocked>(f.run(model, role = Roles.plan))

            assertEquals(3, f.adapter.calls.size, "the third identical refusal ends the cell")
            val reason = blocked.request.reason
            // D-360: the plan role may call run, but only R-class commands; `make` is a W-class build, denied by the executor.
            assertTrue(reason.startsWith("refusal loop: denied: the plan role runs R-class commands only; this command is W-class"), reason)
            assertEquals(listOf("3 identical refused calls of run.run since turn 1"), blocked.request.evidence)
            assertNull(blocked.request.question)
            assertTrue(f.anchorText(3).contains("refusal loop: run.run was refused 2 times for the same reason"), f.anchorText(3))
            assertEquals(CellStatus.Blocked, f.checkpoints.latest(f.ids.context!!)!!.status)
        }
    }

    @Test
    fun `three identical masked edits refused by the validator end the plan cell blocked`() = runTest {
        CellFixture(stateRoot).use { f ->
            val before = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(anchored("c1", "src/a.py", before, "    return 1", "    return 10"))),
                Scripted.Reply(listOf(anchored("c2", "src/a.py", before, "    return 1", "    return 10"))),
                Scripted.Reply(listOf(anchored("c3", "src/a.py", before, "    return 1", "    return 10"))),
            )

            val blocked = assertIs<CellExit.Blocked>(f.run(model, role = Roles.plan))

            assertEquals(3, f.adapter.calls.size)
            assertTrue(blocked.request.reason.startsWith("refusal loop: edit.anchored is not available to the plan role in this cell (any turn)"), blocked.request.reason)
            assertEquals(before, f.version("src/a.py"))
        }
    }

    @Test
    fun `denials interleaved with other calls still end the cell because the refusal history lives for the cell`() = runTest {
        CellFixture(stateRoot).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(runCmd("c1", "make"))),
                Scripted.Reply(listOf(read("c2", "src/a.py"))),
                Scripted.Reply(listOf(runCmd("c3", "make"))),
                Scripted.Reply(listOf(read("c4", "src/a.py"))),
                Scripted.Reply(listOf(runCmd("c5", "make"))),
            )

            val blocked = assertIs<CellExit.Blocked>(f.run(model, role = Roles.plan))

            assertEquals(5, f.adapter.calls.size, "reads between the denials do not reset the refusal history")
            assertTrue(blocked.request.reason.startsWith("refusal loop: denied: the plan role runs R-class commands only"), blocked.request.reason)
            assertEquals(listOf("3 identical refused calls of run.run since turn 1"), blocked.request.evidence)
        }
    }

    @Test
    fun `a terminal ask survives a whole-turn refusal and runs alone`() = runTest {
        CellFixture(stateRoot).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(
                    say("running and asking"),
                    runCmd("c1", "hi", extra = ",\"if\":\"applied(op:2)\""),
                    call("c2", "task", """{"op":"ask","question":"Which value should a return?"}"""),
                )),
            )

            val blocked = assertIs<CellExit.Blocked>(f.run(model))

            assertEquals("Which value should a return?", blocked.request.question)
            assertEquals(1, blocked.turns)
            val results = f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Result))).filter { it.turn == 1 }.map { it.text }
            val refused = results.single { it.startsWith("call c1: ") }
            assertTrue(refused.contains("condition applied(op:2) must name an edit op") && refused.endsWith(" — the terminal call task(ask) ran alone"), refused)
            assertFalse(refused.contains("no call of this turn executed"), refused)
            assertTrue(results.single { it.startsWith("call c2: ") }.contains("tool=task"), results.toString())
            assertTrue(f.intents.open().isEmpty() && f.intents.get("intent-1") == null, "the refused run never started")
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
                Scripted.Reply(listOf(say("a different source"), read("c7", "src/a.py"))),
            )

            val exit = f.run(model)

            assertIs<CellExit.Completed>(exit)
            assertTrue(f.anchorText(3).contains("loop: look.tree returned the same result 2 times — change the question"), f.anchorText(3))
            assertTrue(f.anchorText(4).contains("loop: look.tree returned the same result 3 times — turn ended; a state op is required"), f.anchorText(4))
            val refused = resultText(f.transcript(5).filterIsInstance<ToolResult>().last())
            assertTrue(refused.contains("not executed: the loop gate ended the last turn: a state op is required"), refused)
            val recorded = f.transcript(6).filterIsInstance<ToolResult>().map { resultText(it) }
            assertTrue(recorded.any { it.contains("STATE v1 · applied 1 op") }, recorded.toString())
            val later = f.transcript(7).filterIsInstance<ToolResult>().map { resultText(it) }
            assertTrue(later.any { it.contains("src/a.py") && !it.contains("state op is required") }, later.toString())
            assertEquals(1, f.recorder.ofType<AgentEvent.Cell.GateFired>().count { it.text.contains("same result 2 times") })
        }
    }

    @Test
    fun `a repeated identical next patch does not reset the loop gate but a material change does`() = runTest {
        CellFixture(stateRoot).use { f ->
            val next = """{"next":"look around"}"""
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("look"), patch("p1", next), tree("c1"))),
                Scripted.Reply(listOf(say("look again"), patch("p2", next), tree("c2"))),
                Scripted.Reply(listOf(say("and again"), patch("p3", next), tree("c3"))),
                Scripted.Reply(listOf(say("once more"), patch("p4", next), tree("c4"))),
                Scripted.Reply(listOf(say("recording"), patch("p5", """{"next":"read a.py"}"""), tree("c5"))),
                Scripted.Reply(listOf(say("look after the change"), tree("c6"))),
                Scripted.Reply(listOf(say("done"))),
            )

            f.run(model)

            assertTrue(f.anchorText(4).contains("look.tree returned the same result 2 times"), f.anchorText(4))
            assertTrue(f.anchorText(5).contains("look.tree returned the same result 3 times — turn ended"), f.anchorText(5))
            assertFalse(f.anchorText(7).contains("loop: look.tree"), f.anchorText(7))
        }
    }

    @Test
    fun `a small window bounds one turn's reads by the context headroom and the cell continues`() = runTest {
        val reads = (0 until 12).map { read("c$it", "src/r$it.py") }
        fun results(f: CellFixture) = f.transcript(2).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
        CellFixture(stateRoot).use { f ->
            (0 until 12).forEach { f.repo.write("src/r$it.py", "r = $it\n") }
            val small = Profile("small32", FakeProfiles.PROVIDER, "fake-small32", FakeProfiles.capabilities(32_000, 2_000), FakeProfiles.main.priceTable)
            f.run(ScriptedModel.of(Scripted.Reply(listOf(say("read")) + reads), Scripted.Reply(listOf(say("done")))), profile = small, profiles = FakeProfiles.all + (small.id to small))

            assertEquals(2, f.adapter.calls.size, "the cell continued to the next turn")
            val spent = results(f).filterValues { it.contains("read budget of this turn spent") }
            assertTrue(spent.isNotEmpty() && spent.size < reads.size, results(f).toString())
            assertTrue(results(f).getValue("c0").contains("r = 0"), results(f).getValue("c0"))
        }
        CellFixture(stateRoot.resolve("large")).use { f ->
            (0 until 12).forEach { f.repo.write("src/r$it.py", "r = $it\n") }
            f.run(ScriptedModel.of(Scripted.Reply(listOf(say("read")) + reads), Scripted.Reply(listOf(say("done")))))

            assertTrue(results(f).values.none { it.contains("read budget of this turn spent") }, "a large window is unaffected")
        }
    }

    @Test
    fun `a response that spends the window leaves one turn only the read floor`() = runTest {
        val reads = (0 until 12).map { read("c$it", "src/r$it.py") }
        CellFixture(stateRoot).use { f ->
            (0 until 12).forEach { f.repo.write("src/r$it.py", "r = $it\n") }
            val small = Profile("small32", FakeProfiles.PROVIDER, "fake-small32", FakeProfiles.capabilities(32_000, 2_000), FakeProfiles.main.priceTable)
            // The headroom before this response is appended is positive; the response itself spends it.
            val long = say("reasoning ".repeat(3_200))
            f.run(ScriptedModel.of(Scripted.Reply(listOf(long) + reads), Scripted.Reply(listOf(say("done")))), profile = small, profiles = FakeProfiles.all + (small.id to small))

            val results = f.transcript(2).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            val spent = results.filterValues { it.contains("read budget of this turn spent") }
            assertEquals(reads.size - 1, spent.size, results.toString())
            assertTrue(results.getValue("c0").contains("r = 0"), results.getValue("c0"))
        }
    }

    /** The recorded failure (diags W-wzzpswxif6dzrqdxkoiq): one response with hundreds of repeated calls became hundreds of units in `[T]`. */
    @Test
    fun `a response that repeats a call is cut at the third repeat, the dropped calls never enter the transcript, and the next anchor says so`() = runTest {
        CellFixture(stateRoot).use { f ->
            val runaway = listOf<io.astrolabe.provider.Item>(say("looping")) + (1..40).map { tree("t$it") }
            f.run(ScriptedModel.of(Scripted.Reply(runaway), Scripted.Reply(listOf(say("done")))))

            val second = f.transcript(2)
            assertEquals(listOf("t1", "t2"), second.filterIsInstance<io.astrolabe.provider.ToolCall>().map { it.id }, "the first two stand: positions are kept, only the tail goes")
            assertEquals(listOf("t1", "t2"), second.filterIsInstance<ToolResult>().map { it.callId })
            assertTrue(f.anchorText(2).contains("calls: the response held 40 tool calls; the first 2 were kept and the other 38 were dropped unrun"), f.anchorText(2))
            val journaled = f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Call))).first()
            assertTrue(journaled.text.contains("2 calls (40 received, the other 38 dropped unrun"), journaled.text)
        }
        // Distinct calls past the limit: the first `callsPerResponseMax` run in their positions.
        CellFixture(stateRoot.resolve("limit"), defaults = Defaults(callsPerResponseMax = 3)).use { f ->
            val targets = listOf("src/a.py", "src/b.py", "README.md", "tests/test_a.py")
            val many = listOf<io.astrolabe.provider.Item>(say("reading")) + targets.mapIndexed { i, t -> read("r${i + 1}", t) } +
                listOf(tree("r5"), call("r6", "look", """{"what":"tree","target":"src"}"""), call("r7", "look", """{"what":"tree","target":"tests"}"""))
            f.run(ScriptedModel.of(Scripted.Reply(many), Scripted.Reply(listOf(say("done")))))
            assertEquals(listOf("r1", "r2", "r3"), f.transcript(2).filterIsInstance<ToolResult>().map { it.callId })
            assertTrue(f.anchorText(2).contains("a response runs at most 3 calls"), f.anchorText(2))
        }
        // A kept call whose condition names a dropped op is held alone: the rest of the turn still runs.
        CellFixture(stateRoot.resolve("forward"), defaults = Defaults(callsPerResponseMax = 2)).use { f ->
            val calls = listOf<io.astrolabe.provider.Item>(say("go"), runCmd("d1", "x", ""","if":"applied(op:3)""""), read("d2", "src/a.py"), tree("d3"))
            f.run(ScriptedModel.of(Scripted.Reply(calls), Scripted.Reply(listOf(say("done")))))
            val results = f.transcript(2).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertEquals(setOf("d1", "d2"), results.keys)
            assertTrue(results.getValue("d1").contains("depends on op 3, which was dropped unrun when the response was cut"), results.getValue("d1"))
            assertTrue(results.getValue("d2").contains("def a"), results.getValue("d2"))
        }
        // An edit among the dropped calls leaves the batch incomplete: the kept run waits, as it does after a refused edit.
        CellFixture(stateRoot.resolve("cut-edit"), defaults = Defaults(callsPerResponseMax = 2)).use { f ->
            val calls = listOf<io.astrolabe.provider.Item>(
                say("edit and run"), anchored("e1", "src/a.py", f.version("src/a.py"), "    return 1", "    return 10"), runCmd("x1", "x"),
                anchored("e2", "src/b.py", f.version("src/b.py"), "x = 1", "x = 2"),
            )
            f.run(ScriptedModel.of(Scripted.Reply(calls), Scripted.Reply(listOf(say("done")))))
            val results = f.transcript(2).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertEquals(setOf("e1", "x1"), results.keys)
            assertTrue(results.getValue("x1").contains("the edit batch is not whole"), results.getValue("x1"))
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
            val deadEnd = """{"deadend.add":{"text":"monkeypatching the clock","evidence":null,"scope":"tests/","reopen":"fixtures isolated"}},{"next":"look again"},{"focus.set":"src/a.py"}"""
            val model = ScriptedModel.of(Scripted.Reply(listOf(say("look"), read("c1", "src/a.py"), patch("c0", deadEnd))), Scripted.Reply(listOf(say("look again"), tree("c2"))))
            val exit = f.run(model, profile = small, profiles = FakeProfiles.all + (small.id to small))
            val partial = assertIs<CellExit.Partial>(exit)
            assertEquals(PartialReason.Pressure, partial.reason)
            assertTrue(partial.hint.contains("pressure: context") && partial.hint.contains("second pressure"), partial.hint)
            assertEquals(2, f.adapter.calls.size, "the rebuilt projection took one more turn")
            assertTrue(f.adapter.calls[1].request.segments.any { segment -> segment.kind == io.astrolabe.provider.SegmentKind.K && segment.items.filterIsInstance<io.astrolabe.provider.Message>().any { "SEED src/a.py" in it.text } }, "carried source is rendered into rebuilt K")
            assertEquals(2, partial.checkpoint.turn)
            assertEquals(1, partial.checkpoint.rebuilds)
            assertTrue(f.transcript(2).filterIsInstance<io.astrolabe.provider.Message>().any { it.text.startsWith("rebuilt: pressure (generation 1)") }, "the rebuild is announced in the pinned transcript")
            assertEquals(1, f.recorder.ofType<AgentEvent.Cell.Rebuilt>().size)
            assertEquals(1, f.recorder.ofType<AgentEvent.Cell.Rebuilt>().single().generation.value)
            assertTrue(f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Boundary))).any { it.text.startsWith("rebuilt: pressure (generation 1)") })
            // FX-11 through pressure: the scoped dead end survives the rebuild and KNOWN is declared as the seeds only.
            assertTrue(f.anchorText(2).contains("monkeypatching the clock") && f.anchorText(2).contains("scope: tests/"), f.anchorText(2))
            assertTrue(f.transcript(2).filterIsInstance<io.astrolabe.provider.Message>().any { "KNOWN: seeds only" in it.text })
        }
    }

    @Test
    fun `a pressure rebuild takes its seeds from the attempt's seed rule`() = runTest {
        // Neither Next nor Focus names the file read, so Seeds v1 carries nothing and Seeds v2 carries the recent read.
        suspend fun rebuiltK(rule: io.astrolabe.context.SeedRule): String = CellFixture(stateRoot.resolve(rule.wire), defaults = Defaults(alpha = 0.1, seedRule = rule)).use { f ->
            val small = Profile("small", FakeProfiles.PROVIDER, "fake-small", FakeProfiles.capabilities(12_000, 500), FakeProfiles.main.priceTable)
            val model = ScriptedModel.of(Scripted.Reply(listOf(say("look"), read("c1", "src/a.py"), patch("c0", """{"next":"look again"}"""))), Scripted.Reply(listOf(say("look again"), tree("c2"))))
            assertIs<CellExit.Partial>(f.run(model, profile = small, profiles = FakeProfiles.all + (small.id to small)))
            assertEquals(2, f.adapter.calls.size, "one rebuilt turn")
            f.adapter.calls[1].request.segments.filter { it.kind == io.astrolabe.provider.SegmentKind.K }
                .flatMap { it.items.filterIsInstance<io.astrolabe.provider.Message>() }.joinToString("\n") { it.text }
        }
        assertFalse("SEED src/a.py" in rebuiltK(io.astrolabe.context.SeedRule.V1))
        assertTrue("SEED src/a.py" in rebuiltK(io.astrolabe.context.SeedRule.V2))
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
            // Invariant 12: the reserve narrows the mask in [A] only; the cached regions and the schema set keep their bytes.
            assertTrue(f.anchorText(2).contains("enabled this turn: all role tools except ") && f.anchorText(2).contains("edit.anchored"), f.anchorText(2))
            assertFalse(f.anchorText(1).lineSequence().first { it.startsWith("enabled this turn") }.contains("edit.anchored"), f.anchorText(1))
            val cached = setOf(SegmentKind.S, SegmentKind.R, SegmentKind.K)
            assertEquals(f.request(1).segments.filter { it.kind in cached }, f.request(2).segments.filter { it.kind in cached })
            assertEquals(f.request(1).tools, f.request(2).tools)
            assertEquals(f.request(1).segments.filter { it.breakpoint }.map { it.kind }, f.request(2).segments.filter { it.breakpoint }.map { it.kind })
            val refused = resultText(f.transcript(3).filterIsInstance<ToolResult>().last())
            assertTrue(refused.contains("not executed: ${CellBudget.GATE}; no call of this turn executed"), refused)
            assertEquals(CellFixture.A_PY, Files.readString(f.repo.resolve("src/a.py")))
            val turn3 = f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Result))).filter { it.turn == 3 }
            assertTrue(turn3.single().text.contains("tool=look"), "a read still runs on a reserve turn: $turn3")
            assertEquals(CellStatus.Partial, partial.checkpoint.status)
            assertTrue(partial.checkpoint.reason!!.contains("TurnBudget"))
        }
    }

    @Test
    fun `a turn-count reserve admits a repair of the cell's own file and refuses an edit to a new path`() = runTest {
        CellFixture(stateRoot).use { f ->
            val v = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("scratch"), call("c1", "edit", """{"ops":[{"create":"src/new.py","content":"new"}],"why":"scratch"}"""))),
                Scripted.Reply(listOf(say("look"), tree("c2"))),
                Scripted.Reply(listOf(say("edit a file this cell never changed"), anchored("c3", "src/a.py", v, "    return 1", "    return 10"), read("c3r", "README.md"), runCmd("c3x", "hi"))),
                Scripted.Reply(listOf(say("remove my scratch file"), call("c4", "edit", """{"ops":[{"delete":"src/new.py","expect":"${io.astrolabe.id.Digest.of("new".toByteArray()).hex}"}],"why":"repair"}"""))),
            )

            val exit = f.run(model, turns = 4)

            assertEquals(PartialReason.TurnBudget, assertIs<CellExit.Partial>(exit).reason)
            assertTrue(f.request(3).mask!!.allows("edit.delete") && f.request(3).mask!!.allows("edit.anchored"), "path-addressed edits stay enabled for a repair")
            assertFalse(f.request(3).mask!!.allows("edit.transform"), "a transform is never a repair")
            // [A] agrees with the executor: the enabled edits are held to the cell's own files, and the gate says so.
            val anchor3 = f.anchorText(3)
            assertTrue(anchor3.lineSequence().first { it.startsWith("enabled this turn") }.endsWith(" (edits: own files only)"), anchor3)
            assertTrue(anchor3.contains("reserve reached: verify and report; repairs to your own files only"), anchor3)
            assertFalse(anchor3.contains(CellBudget.GATE), anchor3)
            val refused = resultText(f.transcript(4).filterIsInstance<ToolResult>().last { "c3" == it.callId })
            assertTrue(refused.contains("not executed: reserve reached: edits are limited to files this cell already changed (src/new.py); verify and report; this call was refused; the other 1 call of the turn ran"), refused)
            val beside = f.transcript(4).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertFalse(beside.getValue("c3r").contains("not executed"), "D-372: a read beside the refused reserve edit runs")
            assertTrue(beside.getValue("c3x").contains("not executed: the edit batch did not apply fully: op 1 was refused"), beside.getValue("c3x"))
            assertEquals(CellFixture.A_PY, Files.readString(f.repo.resolve("src/a.py")))
            assertFalse(Files.exists(f.repo.resolve("src/new.py")), "the repair of the cell's own file ran on the reserve")
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
            // Host events are delivered asynchronously: wait for the end event rather than racing it under load.
            val deadline = System.nanoTime() + 5_000_000_000L
            while (f.recorder.ofType<AgentEvent.Cell.Ended>().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
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
            if (nth == 1) {
                assertTrue(result.contains("status=partial") && result.contains("already written: src/a.py (preimage"), result)
            } else {
                assertTrue(result.contains("failed: InjectedCrash") && result.contains("effects unknown; reconciled at the turn boundary"), result)
            }
            assertEquals(after, f.registry.recorded("src/a.py"), "the registry knows the new version, announced by the edit or by the boundary reconcile")
            assertTrue(f.workset.entries.none { it.path == "src/a.py" }, "neither the stale read nor an unseen post-edit view stays KNOWN: ${f.workset.entries}")
            val turns = f.checkpoints.turns(f.ids.context!!)
            assertNotEquals(turns[0].stamp, turns[1].stamp)
            assertEquals(listOf("src/a.py"), turns[1].touched)
            assertEquals(after, f.preimages.of("edit-1").single().versionAfter, "the preimage record survived the crash: the edit is reversible")
            assertEquals(1, f.receipts.forCheck(Checks.TYPES_TOUCHED).size, "the checker ran on the touched path")
            if (nth == 1) assertNotNull(f.observations.get("edit-1"), "the partial publication is recorded")
            else assertNull(f.observations.get("edit-1"), "the rendering crash prevented its observation")
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
    fun `a provider overflow after admission rebuilds once, and a second one ends the cell partial`() = runTest {
        CellFixture(stateRoot).use { f ->
            val exit = f.run(ScriptedModel.build {
                fault(FaultKind.ContextOverflow)
                fault(FaultKind.ContextOverflow)
            })

            val partial = assertIs<CellExit.Partial>(exit)
            assertEquals(PartialReason.Pressure, partial.reason)
            assertTrue(partial.hint.contains("refused the request for size after a rebuild"), partial.hint)
            assertEquals(2, f.adapter.calls.size, "both calls were dispatched and accounted")
            assertEquals(1, partial.checkpoint.rebuilds)
        }
    }

    @Test
    fun `a provider authentication failure blocks on the host instead of failing the increment`() = runTest {
        CellFixture(stateRoot).use { f ->
            val exit = f.run(ScriptedModel.build { fault(FaultKind.Authentication) })

            val blocked = assertIs<CellExit.Blocked>(exit)
            assertTrue(blocked.request.reason.contains("authentication") && blocked.request.reason.contains("invalid API key"), blocked.request.reason)
            assertNull(blocked.request.question, "an external blocker, not a question for the user")
            assertEquals(CellStatus.Blocked, f.checkpoints.latest(f.ids.context!!)!!.status)
        }
    }

    @Test
    fun `a spent subscription quota is a typed resumable stop on the host, not a failed increment`() = runTest {
        CellFixture(stateRoot).use { f ->
            val exit = f.run(ScriptedModel.of(Scripted.Fault(FaultKind.QuotaExhausted, retryAfterSeconds = 3_600)))

            val blocked = assertIs<CellExit.Blocked>(exit)
            assertTrue(blocked.request.reason.contains("plan quota exhausted") && blocked.request.reason.contains("3600 s"), blocked.request.reason)
            assertNull(blocked.request.question, "an external blocker, not a question for the user")
            assertEquals(CellStatus.Blocked, f.checkpoints.latest(f.ids.context!!)!!.status)
            val stop = assertIs<io.astrolabe.campaign.Disposition.Stop>(io.astrolabe.campaign.Lifecycle.disposition(exit, null))
            assertEquals(io.astrolabe.campaign.CampaignOutcome.BlockedExternal, stop.outcome)
            assertTrue(stop.outcome.resumable, "a reopen resumes once the quota refills")
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
    fun `lease expiry while a provider response is held prevents its edit and scheduled checks`() = runTest {
        CellFixture(stateRoot).use { f ->
            val expiry = f.clock.instant().plusSeconds(30)
            val authority = DispatchAuthority {
                if (f.clock.instant().isBefore(expiry)) null else DispatchRefusal("lease expired", cancelled = false)
            }
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("reading"), read("r1", "src/a.py"))),
                Scripted.Reply(listOf(say("editing"), anchored("e1", "src/a.py", f.version("src/a.py"), "    return 1", "    return 10"))),
            )
            val context = f.context(model, holdResponses = true)
            val running = async { f.cell(authority = authority).run(context, f.increment, f.budget()) }
            while (f.adapter.invocation(InvocationId("inv-1")) == null) yield()
            f.adapter.release(InvocationId("inv-1"))
            while (f.adapter.invocation(InvocationId("inv-2")) == null) yield()

            f.clock.advance(Duration.ofSeconds(31))
            f.adapter.release(InvocationId("inv-2"))
            val exit = running.await()

            assertEquals(CellFixture.A_PY, Files.readString(f.repo.resolve("src/a.py")), "the expired response must not edit the workspace")
            assertTrue(f.receipts.forCheck(Checks.TYPES_TOUCHED).isEmpty(), "the expired response must not launch end-of-turn checks")
            assertIs<CellExit.Failed>(exit)
            assertEquals(CellStatus.Failed, f.checkpoints.latest(f.ids.context!!)!!.status)
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
