package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.quote
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.CellStatus
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.evidence.SqliteAliases
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.kb.Candidate
import io.astrolabe.kb.CandidateKind
import io.astrolabe.kb.Diagnosis
import io.astrolabe.kb.Extraction
import io.astrolabe.kb.ExtractionResult
import io.astrolabe.kb.ExtractionTrace
import io.astrolabe.kb.KbResolver
import io.astrolabe.kb.KbWriter
import io.astrolabe.kb.Lint
import io.astrolabe.kb.NoteAnchor
import io.astrolabe.kb.Notes
import io.astrolabe.kb.Queue
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Item
import io.astrolabe.store.BlobPoint
import io.astrolabe.store.FaultPoints
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import io.astrolabe.telemetry.Export
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A process death: neither the dispatcher nor the cell may swallow it, so nothing settles after it. */
internal class ProcessDeath : Error("the controller process died")

/**
 * P1.12.2 first vertical slice (§18.2): a scripted S0 campaign on a repository with a cross-file defect —
 * locate/read, one deliberately stale guarded patch, the real patch, a background test run polled on its handle,
 * a forced interruption between the edit and its receipt, a reopen that reconciles, verification of the
 * candidate by a new cell and the finish receipt, with the user's initially dirty file preserved and separated.
 */
class VerticalSliceTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-1"), AttemptId("a1"), "total() must scale every value by 10")
    private val helper = "def scale(x):\n    return x * 1\n"

    @Volatile
    private var armed = false

    private val faults = FaultPoints { point -> if (armed && point == BlobPoint.BEFORE_FSYNC) { armed = false; throw ProcessDeath() } }

    private fun config() = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all)

    private fun model(vararg turns: (Unit) -> Scripted): CellModel =
        CellModel(FakeAdapter(ScriptedModel(turns.map { t -> ScriptedModel.Turn({ true }, { t(Unit) }) })), FakeProfiles.main, HeuristicEstimator())

    private fun reply(vararg items: Item): (Unit) -> Scripted = { Scripted.Reply(items.toList()) }

    @Test
    fun `the first vertical slice survives an interruption between edit and receipt`() = runBlocking<Unit> {
        TempRepo.create().use { repo ->
            repo.write("src/helper.py", helper)
            repo.write("src/calc.py", "from helper import scale\n\n\ndef total(xs):\n    return sum(scale(x) for x in xs)\n")
            repo.write("tests/test_calc.py", "def test_total():\n    assert total([1, 2]) == 30\n")
            repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
            repo.commit("initial")
            repo.write("NOTES.md", "the user's own draft, never touched\n")
            val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
            Store.open(stateRoot, repo.git, clock).use { store ->
                val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
                val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), Tokens(400_000)).contract
                contracts.open(derived.copy(acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.Harness, scope = Contracts.TOUCHED))))
            }
            val policy = CampaignPolicy(Tokens(400_000))

            // ---- first run: the controller dies between the edit and the acceptance receipt -------------------
            val firstTurns: Int
            val firstAliases: List<io.astrolabe.evidence.Alias>
            Controller(config(), clock, idGen, faults = faults).let { controller ->
                controller.open(repo.root, request, policy).use { c ->
                    val v = c.registry.version("src/helper.py")!!
                    val stale = FileVersion(Digest.ofUtf8("an older helper.py"))
                    val bg = "echo slice-test"
                    val first = model(
                        reply(say("locating"), read("c1", "src/calc.py"), read("c2", "src/helper.py")),
                        reply(say("patching from memory"), anchored("c3", "src/helper.py", stale, "    return x * 1", "    return x * 10")),
                        reply(say("patching the current version"), anchored("c4", "src/helper.py", v, "    return x * 1", "    return x * 10")),
                        reply(say("running the test in the background"), call("c5", "run", """{"cmd":${quote(bg)},"bg":true}""")),
                        reply(say("polling"), call("c6", "run", """{"op":"poll","handle":"handle-1","timeout":20}""")),
                        { armed = true; Scripted.Reply(listOf(say("verifying"), call("c7", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))) },
                    )
                    assertFailsWith<ProcessDeath> { controller.runS0(c, first) }
                    assertEquals("def scale(x):\n    return x * 10\n", Files.readString(repo.root.resolve("src/helper.py")), "the guarded patch applied")
                    val running = c.campaigns.load(request.work, request.attempt)!!.running!!
                    firstTurns = io.astrolabe.cell.SqliteCheckpoints(c.store, clock).latest(running.cell)!!.turn
                    firstAliases = aliases(SqliteAliases(c.store, clock), request.work)
                }
            }

            // ---- reopen: reconcile the lost cell and the tree, then a new cell verifies the candidate ----------
            // P4.2.1: a scripted extractor reads the archived trace at finish; its candidates are lint-passing and queued.
            var traced: ExtractionTrace? = null
            val extraction = Extraction { trace, _, _ ->
                traced = trace
                val evidence = (trace.receipts + trace.packet.evidenceRefs).distinct()
                val diagnosis = Diagnosis(
                    "total() off by 10x", "when scale() multiplies by 1", "a patch against a stale helper.py", "the stale edit refused, the current one applied",
                    "helper.py carries the factor", evidence, trace.sourceRevision, "scale() leaves helper.py",
                )
                ExtractionResult(listOf(
                    Candidate(CandidateKind.LES, "scale-factor-lives-in-helper", "the scale factor lives in src/helper.py, not in total()", "subsystem:src", diagnosis, listOf(NoteAnchor("src/helper.py")), confidence = 0.5),
                    Candidate(CandidateKind.PIT, "stale-anchored-patch", "an anchored patch from memory is refused when helper.py moved", "subsystem:src", diagnosis, listOf(NoteAnchor("src/helper.py")), confidence = 0.5),
                ))
            }
            val controller = Controller(config(), clock, idGen, extraction = extraction)
            controller.open(repo.root, request, policy).use { c ->
                val state = c.state!!
                assertEquals(CampaignPhase.Running, state.phase)
                assertEquals(CellStatus.Failed, state.cells.single().status, "the running cell is recorded lost, not completed")
                assertTrue(c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Reconcile))).any { "lost" in it.text })
                assertTrue(c.reconciliation.external.any { it.path == "src/helper.py" }, "the unreceipted edit is reconciled from the tree")

                val second = model(
                    reply(say("verifying the candidate"), call("c1", "verify", """{"what":"acceptance","ids":["AC-1"]}""")),
                    reply(say("done: scale multiplies by 10")),
                )
                val run = controller.runS0(c, second)
                assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
                val finish = run.finish!!
                assertEquals(listOf("NOTES.md"), finish.changes.preExistingUserChanges, "the initially dirty file is preserved and separated")
                assertEquals("the user's own draft, never touched\n", Files.readString(repo.root.resolve("NOTES.md")))
                assertTrue("src/helper.py" in finish.changes.agent + finish.changes.unattributed)
                assertEquals("green", finish.acceptance.single().status)

                // P4.2.1: the extractor ran post-cell on the archived trace (final STATE, journal, receipts, packet), never inside it.
                val trace = traced!!
                assertEquals(run.exit!!.packet, trace.packet)
                assertTrue(trace.journal.all { it.ids.context == trace.packet.ids.context } && trace.journal.isNotEmpty(), "the cell's own journal")
                assertTrue(trace.journalDigest().lines().isNotEmpty())
                val notes = Notes(c.store)
                val receipts = SqliteReceipts(c.store, clock)
                val resolver = KbResolver.of({ Files.exists(repo.root.resolve(it)) }) { ref ->
                    when {
                        // The CAL delta's evidence is the stored campaign rows its statistics came from.
                        ref.startsWith("campaign:") -> ref.removePrefix("campaign:").split('/').let { (w, a) -> c.campaigns.load(WorkId(w), AttemptId(a)) != null }
                        else -> receipts.get(ref) != null || Aliases.parse(ref)?.let { n -> SqliteAliases(c.store, clock).resolve(request.work, n) } != null
                    }
                }
                val extraction = c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Boundary))).filter { "extraction of" in it.text || "calibration delta" in it.text }
                assertEquals(2, extraction.size, "one extraction line per packet and the CAL delta")
                assertTrue("enqueued 2, refused 0" in extraction[0].text, extraction[0].text)
                val queued = Queue(c.store, KbWriter(c.store, HeuristicEstimator(), clock), idGen, clock).pending().map { it.noteId }
                assertEquals(listOf("LES-scale-factor-lives-in-helper", "PIT-stale-anchored-patch", "CAL-" + repo.root.fileName), queued)
                for (id in queued) assertEquals(emptyList(), Lint.check(notes.get(id)!!, notes.all(), resolver), "lint-passing candidate $id")

                // Per-call billed usage is visible in the exports; the turn counts are the fake-adapter baseline.
                val calls = Accounting(c.store, clock).calls(request.work)
                assertEquals(6 + run.exit!!.turns, calls.size, "six calls before the death, each priced before it acted")
                assertTrue(calls.all { it.usage != null && it.quantities.billedUsage != null })
                val usage = Export(c.store).write(request.work, acceptedTasks = 1, currency = "USD").first()
                assertTrue(Files.readString(usage).contains("\"billedUsage\""))
                assertEquals(5, firstTurns, "the last settled checkpoint is the turn before the death")
                assertEquals(2, run.exit!!.turns)
                assertIs<io.astrolabe.verify.CompletionResult.Accepted>(run.completion)

                // IX-06 (P1 form): cell 1's `#n` keep their meaning after the crash and reopen; cell 2 continues the numbering.
                val all = aliases(SqliteAliases(c.store, clock), request.work)
                assertEquals(firstAliases, all.take(firstAliases.size), "earlier references resolve unchanged")
                assertTrue(all.size > firstAliases.size, "the second cell allocated its own aliases")
                assertEquals(all.size, all.map { it.canonicalId }.toSet().size, "no number designates two artifacts")
                val firstContexts = firstAliases.map { it.context }.toSet()
                assertTrue(all.drop(firstAliases.size).none { it.context in firstContexts }, "provenance names the producing cell")
                assertTrue(firstAliases.any { it.kind == "edit" }, "the guarded-revert resolver still finds cell 1's edit")
            }
        }
    }

    private fun aliases(store: SqliteAliases, work: WorkId) =
        generateSequence(1) { it + 1 }.map { store.resolve(work, it) }.takeWhile { it != null }.filterNotNull().toList()
}
