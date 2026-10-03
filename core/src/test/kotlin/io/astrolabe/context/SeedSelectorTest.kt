package io.astrolabe.context

import io.astrolabe.Defaults
import io.astrolabe.cell.Change
import io.astrolabe.cell.ChangeOrigin
import io.astrolabe.cell.PacketBase
import io.astrolabe.cell.PacketClaims
import io.astrolabe.cell.PacketCost
import io.astrolabe.cell.PacketCoverage
import io.astrolabe.cell.PacketFlags
import io.astrolabe.cell.PacketStatus
import io.astrolabe.cell.ResultPacket
import io.astrolabe.cell.TouchKind
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.InputStability
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.Receipt
import io.astrolabe.evidence.TestedInputs
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.register.Mark
import io.astrolabe.register.OpenItem
import io.astrolabe.register.Register
import io.astrolabe.register.Step
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workspace.Ranges
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Dp1: the seed selector seam (§6.2) — Seeds v1 stays the default rule, Seeds v2 picks touched ∪ red ∪ noted ∪ recent. */
class SeedSelectorTest {
    private fun v(text: String) = FileVersion(Digest.ofUtf8(text))

    private val versions = mapOf(
        "src/edit.py" to v("edit-2"),
        "src/fail.py" to v("fail-1"),
        "tests/test_fail.py" to v("tf-1"),
        "pkg/core/x.py" to v("x-1"),
        "src/cli.py" to v("cli-1"),
        "docs/notes.md" to v("notes-1"),
        "src/old.py" to v("old-1"),
        "src/moved.py" to v("moved-2"),
    )

    private fun entry(path: String, turn: Int, tokens: Long = 300, version: FileVersion = versions.getValue(path), source: EntrySource = EntrySource.Look) =
        Entry(path, Ranges.single(1, 20), version, source, turn, "#$turn", tokens)

    private val register = Register(
        version = 3, cell = ContextId("cell-1"), increment = "I1", incrementTitle = "fix the CLI",
        plan = listOf(Step(1, Mark.Cursor, "edit nothing in particular")),
        open = listOf(OpenItem(1, "does cli.py parse --flag?"), OpenItem(2, "notes.md is outdated", closed = true, closedEvidence = "#9")),
        next = "keep going",
    )

    private fun receipt(id: String, outcome: Outcome, closure: Closure, command: List<String>): Receipt {
        val stamp = CandidateId(Digest.ofUtf8("stamp"))
        return Receipt(
            id, Identities(WorkId("w"), AttemptId("a")), "CHK-$id", emptyList(), command, null, false, stamp, stamp, Digest.ofUtf8("env"), "test",
            Digest.ofUtf8("def"), 1, outcome, if (outcome == Outcome.Passed) Counts(passed = 1) else Counts(failed = 1), closure,
            TestedInputs(emptyMap(), InputStability.Exclusive), null, at = Instant.EPOCH,
        )
    }

    private val red = receipt("r1", Outcome.Failed, Closure.Known(setOf("src/fail.py")), listOf("pytest", "tests/test_fail.py"))

    /** One entry per source class, displayed at turns that would put them in the reverse order by recency alone. */
    private val export = listOf(
        entry("docs/notes.md", turn = 9),
        entry("src/old.py", turn = 2),
        entry("src/cli.py", turn = 3),
        entry("tests/test_fail.py", turn = 4),
        entry("src/fail.py", turn = 5),
        entry("src/edit.py", turn = 1, version = v("edit-2"), source = EntrySource.PostEdit),
    )

    private fun inputs(export: List<Entry> = this.export, touched: Set<String> = setOf("src/edit.py"), receipts: List<Receipt> = listOf(red)) =
        SeedInputs(register, export, touched, receipts)

    @Test
    fun `seeds v1 is the default rule and the default selector of the carry`() {
        assertEquals(SeedRule.V1, Defaults().seedRule)
        assertEquals(SeedSelector.V1, SeedRule.V1.selector)
        assertEquals(SeedSelector.V2, SeedRule.V2.selector)
        val referenced = register.copy(focus = "src", next = "look at notes.md")
        val default = CarryForward.carry(referenced, export, null, { versions[it] }, { true }, emptyList(), emptyList())
        val v1 = CarryForward.carry(referenced, export, null, { versions[it] }, { true }, emptyList(), emptyList(), selector = SeedSelector.V1, touched = setOf("src/edit.py"), latestReceipts = listOf(red))
        assertEquals(default, v1, "v1 reads neither the touched paths nor the receipts")
        assertEquals(default.render(), v1.render())
        assertEquals(listOf("docs/notes.md", "src/cli.py", "src/edit.py", "src/fail.py", "src/old.py"), default.seeds.map { it.path }, "Next and Focus, in path order")
    }

    @Test
    fun `seeds v2 ranks touched, red, noted, then recent, whatever the display order`() {
        val candidates = SeedSelector.V2.candidates(inputs())
        assertEquals(
            listOf(
                "src/edit.py" to SeedReason.Touched,
                "src/fail.py" to SeedReason.Red,
                "tests/test_fail.py" to SeedReason.Red,
                "src/cli.py" to SeedReason.Noted,
                "docs/notes.md" to SeedReason.Recent,
                "src/old.py" to SeedReason.Recent,
            ),
            candidates.map { it.entry.path to it.reason },
            "red: the later display first; a closed open item names nothing; recent: the later display first",
        )
        assertEquals(candidates, SeedSelector.V2.candidates(inputs(export = export.reversed())), "the order never depends on the export's order")
        assertEquals(candidates, SeedSelector.V2.candidates(inputs(export = export + export)), "a repeated entry is one candidate")
    }

    @Test
    fun `each v2 source alone`() {
        fun reasons(i: SeedInputs) = SeedSelector.V2.candidates(i).filter { it.reason != SeedReason.Recent }.map { it.entry.path to it.reason }
        assertEquals(listOf("src/cli.py" to SeedReason.Noted), reasons(inputs(touched = emptySet(), receipts = emptyList())))
        assertEquals(listOf("src/edit.py" to SeedReason.Touched, "src/cli.py" to SeedReason.Noted), reasons(inputs(receipts = emptyList())))
        val timeout = receipt("r2", Outcome.Timeout, Closure.Known(setOf("src/fail.py")), listOf("pytest"))
        val green = receipt("r3", Outcome.Passed, Closure.Known(setOf("src/fail.py")), listOf("pytest"))
        assertEquals(listOf("src/cli.py" to SeedReason.Noted), reasons(inputs(touched = emptySet(), receipts = listOf(timeout, green))), "only a failed receipt is red")
        val pkg = receipt("r4", Outcome.Failed, Closure.Package("pkg/core/"), listOf("pytest"))
        val unknown = receipt("r5", Outcome.Failed, Closure.Unknown, listOf("python", "-m", "pytest", "-q"))
        val packaged = inputs(export = export + entry("pkg/core/x.py", turn = 6), touched = emptySet(), receipts = listOf(pkg, unknown))
        assertEquals(listOf("pkg/core/x.py" to SeedReason.Red, "src/cli.py" to SeedReason.Noted), reasons(packaged), "a package closure covers its files; an unknown closure adds only what the command names")
        val needs = register.copy(open = listOf(OpenItem(1, "is the flag parsed?", needs = "a read of src/old.py")))
        assertEquals(listOf("src/old.py" to SeedReason.Noted), reasons(SeedInputs(needs, export)), "an open item's needs name paths too")
    }

    @Test
    fun `a carried seed counts as older than anything displayed in this cell`() {
        val carried = entry("src/old.py", turn = 40, source = EntrySource.Seed)
        val order = SeedSelector.V2.candidates(SeedInputs(register, listOf(carried, entry("docs/notes.md", turn = 2)))).map { it.entry.path }
        assertEquals(listOf("docs/notes.md", "src/old.py"), order, "a seed's turn belongs to the cell it came from")
    }

    @Test
    fun `the v2 carry keeps the priority under the seed budget and announces only what it was asked to keep`() {
        val big = export.map { if (it.path == "src/cli.py") it.copy(tokens = 900) else it }
        val packet = null
        val carry = CarryForward.carry(
            register, big, packet, { versions[it] }, { true }, emptyList(), emptyList(), seedCapTokens = 1_000,
            selector = SeedRule.V2.selector, touched = setOf("src/edit.py"), latestReceipts = listOf(red),
        )
        assertEquals(listOf("src/edit.py", "src/fail.py", "tests/test_fail.py"), carry.seeds.map { it.path })
        assertTrue(carry.seeds.all { it.source == EntrySource.Seed })
        assertTrue(carry.seedTokens <= 1_000)
        assertEquals(listOf("src/cli.py" to "over the 1000-token seed budget"), carry.notSeen.map { it.path to it.reason }, "noted is announced; the recent filler is not")

        val roomy = CarryForward.carry(register, big, packet, { versions[it] }, { true }, emptyList(), emptyList(), seedCapTokens = 1_250, selector = SeedSelector.V2, touched = setOf("src/edit.py"), latestReceipts = listOf(red))
        assertEquals(listOf("src/edit.py", "src/fail.py", "tests/test_fail.py", "docs/notes.md"), roomy.seeds.map { it.path }, "first fit: a smaller later candidate takes the rest")
        assertEquals(roomy, CarryForward.carry(register, big.reversed(), packet, { versions[it] }, { true }, emptyList(), emptyList(), seedCapTokens = 1_250, selector = SeedSelector.V2, touched = setOf("src/edit.py"), latestReceipts = listOf(red)))
    }

    @Test
    fun `v2 takes touched paths from the packet and announces a moved touched file, never a moved recent one`() {
        val moved = listOf(entry("src/moved.py", turn = 3, version = v("moved-1")), entry("src/old.py", turn = 2, version = v("old-0")), entry("src/cli.py", turn = 1))
        val stamp = CandidateId(Digest.ofUtf8("stamp"))
        val ids = Identities(WorkId("w"), AttemptId("a"), context = register.cell)
        val packet = ResultPacket(
            ids, "I1", "implement", 1, ExecutionGeneration.INITIAL, PacketBase(stamp, WorkspaceId("ws-main")), emptyMap(), PacketStatus.Done, null, null,
            register, moved, listOf(Change("src/moved.py", TouchKind.Modified, v("moved-0"), v("moved-1"), ChangeOrigin.Edit)), emptyList(), emptyList(), stamp,
            Digest.ofUtf8("env"), PacketCoverage(0, emptyList()), PacketFlags(emptyList(), emptyList()), PacketClaims(), null, emptyList(), emptyList(), PacketCost(),
        )
        val carry = CarryForward.carry(register, moved, packet, { versions[it] }, { true }, emptyList(), emptyList(), selector = SeedSelector.V2)
        assertEquals(listOf("src/cli.py"), carry.seeds.map { it.path })
        assertEquals(listOf("src/moved.py" to "changed"), carry.notSeen.map { it.path to it.reason }, "the touched file is announced, the moved recent read is not")
        assertEquals(listOf(CarriedTouch("src/moved.py", v("moved-1"))), carry.touched, "the touched ledger is unchanged")
    }
}
