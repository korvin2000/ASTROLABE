package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.resultText
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Constraint
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.Receipt
import io.astrolabe.evidence.SqliteAliases
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.FileVersion
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Item
import io.astrolabe.provider.Request
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.ToolResult
import io.astrolabe.store.Store
import io.astrolabe.verify.Applicability
import io.astrolabe.verify.Checks
import io.astrolabe.verify.RefactorMode
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Scheduler
import io.astrolabe.verify.Verdict
import io.astrolabe.verify.VerdictOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P3.8.2 Stage C scripted campaigns. (1) A cross-file migration whose later increments reuse the earlier acceptance
 * receipts through reuse proofs (§8.1/§8.4) instead of re-running them, with no requirement lost. (2) A 40-file
 * rename through `edit(transform)`: reconciled at the moved stamp, reversible with `revert:turn:N` (D-99),
 * red on a deliberately broken site (never a false green) and completed through the human review path (IX-03).
 * Invariants are asserted directly on the records ([invariantViolations]): no invariant metric API exists yet.
 */
class StageCCampaignTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val policy = CampaignPolicy(Tokens(2_000_000))

    /** A `-rA` pass log: identities compare, so the equivalence report of the refactor is comparable by identity. */
    private val passLog = """
        |============================= test session starts ==============================
        |platform linux -- Python 3.12.3, pytest-8.2.0, pluggy-1.5.0
        |rootdir: /home/dev/shop
        |collected 2 items
        |
        |tests/test_ab.py ..                                                      [100%]
        |
        |=========================== short test summary info ============================
        |PASSED tests/test_ab.py::test_a
        |PASSED tests/test_ab.py::test_b
        |============================== 2 passed in 0.05s ===============================
        |""".trimMargin()

    @AfterTest
    fun tearDown() {
        if (::repo.isInitialized) repo.close()
    }

    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

    /** Red only on a mixed tree (a site still on `dispatch` next to sites on `forward`): green at s0, green after the rename, red on a broken site. */
    private val consistency = if (WINDOWS) {
        Command(listOf("cmd.exe", "/d", "/s", "/c", "findstr /s /m dispatch src\\*.py >nul && findstr /s /m forward src\\*.py >nul && type pytest_fail.txt || type pytest_pass.txt"))
    } else {
        Command(listOf("/bin/sh", "-c", "if grep -rq dispatch src && grep -rq forward src; then cat pytest_fail.txt; else cat pytest_pass.txt; fi"))
    }

    private fun controller() = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen)

    private fun resource(name: String): String = javaClass.getResourceAsStream("/shaper/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    /** A host reviewer: the other authority paths stay autonomous. */
    private class Reviewer(private val outcome: VerdictOutcome, private val name: String) : Authority by AutonomousAuthority() {
        val requests = ArrayList<ReviewRequest>()

        override suspend fun review(request: ReviewRequest): Verdict {
            requests += request
            return Verdict(request.id, request.contractRevision, request.candidate, outcome, confidence = 0.9, signedBy = name)
        }
    }

    private fun scheduler(c: OpenedCampaign) =
        Scheduler(c.checks, c.workspace, c.registry, c.stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, c.ids.copy(context = ContextId("audit")), clock)

    /**
     * The Stage C invariants, each violation named: no requirement lost from the ledger or left unverified; every
     * verified increment's acceptance certified by a current green receipt at the final stamp; no result served
     * `current` at a stamp other than its own without a reuse proof whose closure still matches the tree.
     */
    private fun invariantViolations(c: OpenedCampaign, finalStamp: CandidateId): List<String> {
        val out = ArrayList<String>()
        val state = c.campaigns.load(c.request.work, c.request.attempt)!!
        for (r in c.contract.requirements) {
            val entry = state.ledger.entries[r.id]
            if (entry == null) out += "requirement ${r.id} lost from the ledger" else if (entry.status != RequirementStatus.Verified) out += "requirement ${r.id} ${entry.status}"
        }
        val scheduler = scheduler(c)
        for (increment in state.graph.increments.filter { it.status == IncrementStatus.Verified }) {
            for (ac in increment.accept) {
                val certified = c.checks.forAcceptance(ac).any { check -> scheduler.currency(check, finalStamp).let { it.certifies && it.green } }
                if (!certified) out += "increment ${increment.id} verified without a current green receipt for $ac at @${finalStamp.hash8}"
            }
        }
        for (check in c.checks.all()) {
            val last = check.last ?: continue
            if (last.applicability != Applicability.Current || last.stamp == finalStamp) continue
            val proof = last.reuseProof
            if (proof == null) {
                out += "${check.id}: ${last.receiptId} served current at @${finalStamp.hash8} from @${last.stamp.hash8} without a reuse proof"
                continue
            }
            val moved = proof.closureUnchanged.filter { (path, hex) -> c.registry.version(path)?.digest?.hex != hex }.keys
            if (moved.isNotEmpty()) out += "${check.id}: reuse proof over a moved closure: ${moved.sorted()}"
        }
        return out
    }

    private fun receiptsOf(c: OpenedCampaign, checkId: String): List<Receipt> = SqliteReceipts(c.store, clock).forCheck(checkId)

    private fun results(adapter: FakeAdapter): List<String> =
        adapter.calls.flatMap { it.request.segment(SegmentKind.T)?.items.orEmpty() }.filterIsInstance<ToolResult>().map(::resultText).distinct()

    // ------------------------------------------------------------------ 1. cross-file migration with reuse proofs

    private val migration = CampaignRequest(WorkId("W-mig"), AttemptId("a1"), "make a return 10, b return 20 and c return 30")
    private val modules = listOf("a", "b", "c")

    private fun openMigration() {
        repo = TempRepo.create()
        modules.forEachIndexed { i, m -> repo.write("src/$m.py", "def $m():\n    return ${i + 1}\n"); repo.write("tests/test_$m.py", "def test_$m():\n    assert $m()\n") }
        repo.write("pytest_pass.txt", passLog)
        repo.commit("initial")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(migration.work, migration.attempt, migration.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            val user = derived.requests.single().id
            contracts.open(
                derived.copy(
                    shape = Shape.S1,
                    scope = derived.scope.copy(writePaths = listOf("src/", "tests/")),
                    requirements = (1..3).map { i -> Requirement("R$i", "${modules[i - 1]} returns ${i * 10}", listOf("AC-$i"), authorityRef = user) },
                    acceptance = (1..3).map { i -> Acceptance.Run("AC-$i", printing, Origin.User) },
                ),
            )
        }
    }

    private val migrationPlan = """{"increments":[
        {"id":"I1","requirements":["R1"],"accept":["AC-1"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"},
        {"id":"I2","requirements":["R2"],"accept":["AC-1","AC-2"],"write_scope":["src/"],"expected_files":1,"depends_on":["I1"],"produces":"artifact"},
        {"id":"I3","requirements":["R3"],"accept":["AC-1","AC-2","AC-3"],"write_scope":["src/"],"expected_files":1,"depends_on":["I2"],"produces":"artifact"}]}"""

    private fun implement(c: OpenedCampaign, path: String, from: String, to: String, ids: String, extra: List<Scripted> = emptyList()): List<Scripted> {
        val v = c.registry.version(path)!!
        return listOf(
            Scripted.Reply(listOf<Item>(say("reading $path"), read("r-$path", path))),
            Scripted.Reply(listOf<Item>(say("editing $path"), anchored("e-$path", path, v, from, to))),
            Scripted.Reply(listOf<Item>(say("verifying"), call("v-$path", "verify", """{"what":"acceptance","ids":[$ids]}"""))),
        ) + extra + listOf(Scripted.Reply(listOf<Item>(say("done with $path"))))
    }

    @Test
    fun `a cross-file migration reuses the earlier acceptance receipts through reuse proofs and loses no requirement`() = runBlocking<Unit> {
        openMigration()
        controller().open(repo.root, migration, policy).use { c ->
            // Each acceptance run declares its closure (the module and its test); Checks.seed leaves them unknown (D-99).
            for (m in modules.indices) {
                val check = c.checks[Checks.acceptId("AC-${m + 1}")]!!
                c.checks.replace(check.copy(inputClosure = Closure.Known(setOf("src/${modules[m]}.py", "tests/test_${modules[m]}.py"))))
            }
            val replies = listOf(
                Scripted.Reply(listOf(say("planning the migration"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$migrationPlan}"""))),
                Scripted.Reply(listOf(say("plan ready"))),
            ) + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-2\"") +
                implement(c, "src/c.py", "    return 3", "    return 30", "\"AC-3\"")
            val adapter = FakeAdapter(ScriptedModel.of(*replies.toTypedArray()))

            val run = controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))

            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            val finish = run.finish!!
            assertEquals(listOf("green", "green", "green"), finish.acceptance.map { it.status })
            val state = c.campaigns.load(migration.work, migration.attempt)!!
            assertEquals(listOf("R1", "R2", "R3"), state.ledger.entries.keys.sorted())
            assertTrue(state.graph.increments.all { it.status == IncrementStatus.Verified })
            assertEquals(listOf("def a():\n    return 10\n", "def b():\n    return 20\n", "def c():\n    return 30\n"), modules.map { Files.readString(repo.root.resolve("src/$it.py")) })

            // Fewer redundant checks: past the plan cell's verify-on-stop at s0, each acceptance ran once, where a
            // no-reuse harness re-runs every owed acceptance at every increment boundary.
            val executed = (1..3).associate { "AC-$it" to receiptsOf(c, Checks.acceptId("AC-$it")).filter { r -> r.stampAfter != c.s0.stampId } }
            val summary = executed.values.flatten().joinToString("\n") { "${it.receiptId} ${it.checkId} ${it.ids.context?.value} ${it.outcome} @${it.stampAfter.hash8}" }
            assertEquals(mapOf("AC-1" to 1, "AC-2" to 1, "AC-3" to 1), executed.mapValues { it.value.size }, summary)
            val noReuse = state.graph.increments.sumOf { it.accept.size }
            assertEquals(6, noReuse)
            assertTrue(executed.values.sumOf { it.size } < noReuse)
            // The reuse proofs are visible on the checks' last results: AC-1 and AC-2 stand at the final stamp on their unchanged closures.
            for (m in 0..1) {
                val id = Checks.acceptId("AC-${m + 1}")
                val last = c.checks[id]!!.last!!
                assertEquals(Applicability.Current, last.applicability, last.toString())
                assertEquals(finish.stamp, last.reuseProof?.stamp, "reuse proof at the final stamp: $last")
                assertEquals(executed.getValue("AC-${m + 1}").single().receiptId, last.reuseOf)
                assertEquals(setOf("src/${modules[m]}.py", "tests/test_${modules[m]}.py"), last.reuseProof!!.closureUnchanged.keys)
                assertNotEquals(last.stamp, finish.stamp, "the reused receipt was taken at an earlier stamp")
            }
            val boundaries = c.journal.events(JournalScope(migration.work, kinds = setOf(JournalKind.Boundary))).map { it.text }
            assertTrue(boundaries.none { it.startsWith("regression obligations re-run") }, "nothing re-ran at campaign end: ${boundaries.takeLast(5)}")
            assertEquals(emptyList(), invariantViolations(c, finish.stamp))
        }
    }

    // ------------------------------------------------------------------ 2. 40-file rename via edit(transform)

    private val rename = CampaignRequest(WorkId("W-ren"), AttemptId("a1"), "rename router.dispatch to router.forward at every call site")
    private val files = (1..40).map { "src/m%02d.py".format(it) }

    private fun module(i: Int) = "# m$i\n\n\ndef f():\n    return router.dispatch($i)\n\n\ndef g():\n    return $i\n"

    private fun openRename() {
        repo = TempRepo.create()
        files.forEachIndexed { i, path -> repo.write(path, module(i + 1)) }
        repo.write("tests/test_m.py", "def test_m():\n    assert f() == 1\n")
        repo.write("pytest_pass.txt", passLog)
        repo.write("pytest_fail.txt", resource("pytest-fail-param.txt"))
        repo.commit("initial")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(rename.work, rename.attempt, rename.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            val user = derived.requests.single().id
            contracts.open(
                derived.copy(
                    shape = Shape.S1,
                    scope = derived.scope.copy(writePaths = listOf("src/", "tests/")),
                    requirements = listOf(
                        Requirement("R1", "every call site uses router.forward, none router.dispatch", listOf("AC-1"), authorityRef = user),
                        Requirement("R2", "the test suite stays green", listOf("AC-2"), authorityRef = user),
                    ),
                    acceptance = listOf(Acceptance.Run("AC-1", consistency, Origin.User), Acceptance.Run("AC-2", printing, Origin.User)),
                    constraints = derived.constraints + Constraint(RefactorMode.FLAG, "refactor_mode", user),
                ),
            )
        }
    }

    private val renamePlan = """{"increments":[
        {"id":"I1","requirements":["R1","R2"],"accept":["AC-1","AC-2"],"write_scope":["src/"],"expected_files":40,"produces":"artifact"}],
        "con":[{"summary":"router call-site contract","scope":"src/"}],
        "refactor_checklist":{"behaviour_to_preserve":"the test suite and its identities","interfaces_to_change":"router.dispatch → router.forward",
          "compatibility_duration":"none","callers_consumers":"src/m01.py … src/m40.py","data_configuration_dependencies":"none",
          "independent_acceptance_checks":"AC-1, AC-2","shared_decision":"CON router call-site contract"}}"""

    /** The rename as argv, portable: PowerShell rewrites bytes without a BOM, sh keeps LF. */
    private fun renameArgv(): String {
        val argv = if (WINDOWS) {
            listOf("powershell", "-NoProfile", "-Command", "Get-ChildItem src -Filter *.py | ForEach-Object { [IO.File]::WriteAllText(\$_.FullName, ([IO.File]::ReadAllText(\$_.FullName) -replace 'dispatch','forward')) }")
        } else {
            listOf("/bin/sh", "-c", "for f in src/*.py; do sed -i s/dispatch/forward/g \$f; done")
        }
        return JsonArray(argv.map(::JsonPrimitive)).toString()
    }

    private fun transform(id: String) = call(
        id, "edit",
        """{"ops":[{"transform":{"argv":${renameArgv()},"scope_glob":"src/**/*.py","expected_matches":{"min":40,"max":40},"why":"rename dispatch → forward"}}],"why":"rename router.dispatch → router.forward across every call site"}""",
    )

    /**
     * The rename cell, one reply per request: transform, verify (green), `revert:turn:N` back to the pre-transform
     * snapshot, transform again, break one site by hand, verify (red), repair it, verify (green), ask the human
     * review, propose completion. Versions and stamps are captured at reply time for the assertions.
     */
    private inner class RenameScript(private val c: OpenedCampaign) {
        var n = -1
        val pre: Map<String, FileVersion> = versions()
        var preTurn = -1
        lateinit var post: Map<String, FileVersion>
        lateinit var afterRevert: Map<String, FileVersion>
        lateinit var brokenStamp: CandidateId

        fun versions(): Map<String, FileVersion> = files.associateWith { c.registry.version(it)!! }

        fun next(request: Request): Scripted {
            n += 1
            val items: List<Item> = when (n) {
                0 -> listOf(say("planning the rename"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$renamePlan}"""))
                1 -> listOf(say("plan ready"))
                2 -> { preTurn = c.shadow.records().last().turn; listOf(say("renaming every call site"), transform("t1")) }
                3 -> { post = versions(); listOf(say("verifying the rename"), call("v1", "verify", """{"what":"acceptance","ids":["AC-1"]}""")) }
                4 -> listOf(say("undoing the rename to check it is reversible"), call("rv", "edit", """{"ops":[{"revert":"turn:$preTurn"}],"why":"prove the rename reversible"}"""))
                5 -> { afterRevert = versions(); listOf(say("renaming again"), transform("t2")) }
                6 -> listOf(say("reading one site"), read("r5", "src/m05.py"))
                7 -> listOf(say("breaking one site"), anchored("e-break", "src/m05.py", c.registry.version("src/m05.py")!!, "    return router.forward(5)", "    return router.dispatch(5)"))
                8 -> listOf(say("verifying the broken tree"), call("v2", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))
                9 -> { brokenStamp = c.stamper.report().candidateId; listOf(say("repairing the site"), anchored("e-fix", "src/m05.py", c.registry.version("src/m05.py")!!, "    return router.dispatch(5)", "    return router.forward(5)")) }
                10 -> listOf(say("verifying the repaired tree"), call("v3", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))
                11 -> listOf(say("asking for the campaign review"), call("rvw", "verify", """{"what":"review","scope":"campaign"}"""))
                12 -> listOf(say("done: every call site renamed"))
                else -> error("no reply $n is scripted: ${request.segment(SegmentKind.T)?.items?.size} items")
            }
            return Scripted.Reply(items)
        }
    }

    @Test
    fun `a 40-file rename via transform is reconciled, reversible, red on a broken site and completed through the human review`() = runBlocking<Unit> {
        openRename()
        controller().open(repo.root, rename, policy).use { c ->
            assertTrue(RefactorMode.isActive(c.contract))
            val s0 = c.stamper.report().candidateId
            val script = RenameScript(c)
            val reviewer = Reviewer(VerdictOutcome.Approve, "alice")
            val adapter = FakeAdapter(ScriptedModel(listOf(ScriptedModel.Turn({ true }, script::next, once = false))))

            val run = controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()), authority = reviewer)

            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            val finish = run.finish!!
            assertEquals("completed", finish.status)
            assertEquals(12, script.n, "every scripted reply was consumed: " + c.journal.events(JournalScope(rename.work, kinds = setOf(JournalKind.Boundary))).joinToString("\n") { it.text })

            // Reconciled: the transform moved every file and the stamp; the final tree is fully renamed.
            assertTrue(files.all { script.post.getValue(it) != script.pre.getValue(it) }, "40 files moved")
            assertNotEquals(s0, finish.stamp)
            files.forEachIndexed { i, path -> assertEquals(module(i + 1).replace("dispatch", "forward"), Files.readString(repo.root.resolve(path)), path) }
            val rendered = results(adapter)
            assertEquals(2, rendered.count { it.contains("transform: 40 files, 40 hunks") && it.contains("match_count 40 (expected 40–40)") }, rendered.filter { it.contains("transform") }.toString())

            // Reversible: revert:turn:N restored the pre-transform bytes of all 40 files (hashes), then the rename was redone.
            assertEquals(script.pre, script.afterRevert, "revert:turn:${script.preTurn} restored every preimage")
            assertTrue(rendered.any { it.contains("revert") && it.contains("src/m40.py") }, rendered.filter { it.contains("revert") }.toString())

            // No false green: the deliberately broken site turned AC-1 red at its stamp; only the repaired tree is green again.
            val cell = c.campaigns.load(rename.work, rename.attempt)!!.graph.increments.single().cells.single()
            val receipts = receiptsOf(c, Checks.acceptId("AC-1")).filter { it.ids.context == cell }
            // §8.10: the failed check is rerun once, alone, and agrees — two red receipts, never a flaky pardon.
            assertEquals(listOf(Outcome.Passed, Outcome.Failed, Outcome.Failed, Outcome.Passed), receipts.map { it.outcome }, receipts.map { "${it.receiptId} ${it.outcome} @${it.stampAfter.hash8}" }.toString())
            assertTrue(receipts.filter { it.outcome == Outcome.Failed }.all { it.stampAfter == script.brokenStamp }, "red at the broken stamp only")
            assertTrue(receipts.none { it.outcome == Outcome.Passed && it.stampAfter == script.brokenStamp }, "no green receipt at the broken stamp")
            val green = receipts.last()
            assertEquals(finish.stamp, green.stampAfter, "the increment closed on the receipt taken at the final tree")
            assertEquals(c.registry.version("src/m05.py"), green.testedInputs.versions["src/m05.py"], "the repaired site is among the tested inputs")
            assertEquals(c.registry.version("src/m40.py"), green.testedInputs.versions["src/m40.py"], "the transformed files are among the tested inputs")
            assertTrue(green.testedInputs.eligible && green.greenForFinalTree)

            // Reviewed through the human path (IX-03): the verdict signed in the cell is reused by the campaign gate.
            val request = reviewer.requests.single()
            assertNotNull(request.diffRef)
            val review = assertNotNull(finish.review)
            assertEquals("approve" to "alice", review.verdict to review.signedBy)
            assertTrue(review.reused)
            val equivalence = assertNotNull(finish.equivalence)
            assertTrue(equivalence.equivalent, equivalence.render())
            assertTrue(rendered.any { it.contains("── Review ──") && it.contains("approve by alice") })

            val state = c.campaigns.load(rename.work, rename.attempt)!!
            assertEquals(mapOf("R1" to RequirementStatus.Verified, "R2" to RequirementStatus.Verified), state.ledger.entries.mapValues { it.value.status })
            assertEquals(emptyList(), invariantViolations(c, finish.stamp))
        }
    }
}
