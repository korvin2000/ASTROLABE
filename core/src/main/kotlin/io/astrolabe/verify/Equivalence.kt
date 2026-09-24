package io.astrolabe.verify

import io.astrolabe.evidence.Receipt
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.store.BlobStore
import io.astrolabe.tool.run.RunCapture
import io.astrolabe.tool.run.Shapers
import io.astrolabe.tool.run.TestResult
import kotlinx.serialization.Serializable

/** What became of one test identity between `s0` and `s_n` (§8.9 item 5). */
@Serializable
public enum class TestDelta { Preserved, ChangedOutcome, Missing, New }

/** One identity compared; [s0] and [sn] are its outcomes at each side, multiplicity preserved (D-27). */
@Serializable
public data class TestComparison(val identity: String, val delta: TestDelta, val s0: List<String>, val sn: List<String>)

/**
 * One affected suite compared: its baseline receipt and the receipt at `s_n`. [comparable] is false when either
 * side yielded no per-test identities, in which case counts alone can never prove equivalence.
 */
@Serializable
public data class SuiteComparison(
    val checkId: String,
    val s0ReceiptId: String,
    val snReceiptId: String?,
    val s0Tests: Int,
    val snTests: Int,
    val s0Outcome: String,
    val snOutcome: String?,
    val comparable: Boolean,
)

/** One characterization output (golden, fixture) at `s0` against the final tree. */
@Serializable
public data class GoldenComparison(val path: String, val s0Blob: Digest, val same: Boolean, val detail: String? = null)

/**
 * The equivalence evidence of §8.9 item 5: identities, outcomes and characterization outputs of the preserved
 * tests at [sn] versus [s0]. Equal counts are not equivalence; a new test is new behaviour that needs a
 * requirement, not a silent extension.
 */
@Serializable
public data class EquivalenceReport(
    val s0: CandidateId,
    val sn: CandidateId,
    val suites: List<SuiteComparison>,
    val preserved: Int,
    val changed: List<TestComparison>,
    val missing: List<TestComparison>,
    val added: List<TestComparison>,
    val goldens: List<GoldenComparison>,
    val limitations: List<String> = emptyList(),
) {
    /** Every preserved identity kept its outcome, every golden its bytes, and every suite was comparable by identity. */
    val equivalent: Boolean
        get() = suites.isNotEmpty() && suites.all { it.comparable } && changed.isEmpty() && missing.isEmpty() && goldens.all { it.same }

    /** New tests, each a behaviour the contract must name (§8.9 item 5). */
    val newBehaviour: List<String>
        get() = added.map { "new test ${it.identity}: new behaviour needs a requirement, not a silent extension" }

    public fun render(maxLines: Int = 12): String {
        val head = "equivalence @${sn.hash8} vs @${s0.hash8}: " + (if (equivalent) "equivalent" else "NOT equivalent") +
            " · $preserved preserved, ${changed.size} changed, ${missing.size} missing, ${added.size} new, " +
            "${goldens.count { it.same }}/${goldens.size} goldens unchanged"
        val lines = ArrayList<String>()
        suites.forEach { lines += "  ${it.checkId}: ${it.s0Tests} tests ${it.s0Outcome} (${it.s0ReceiptId}) → ${it.snTests} tests ${it.snOutcome ?: "not run"} (${it.snReceiptId ?: "-"})" + (if (it.comparable) "" else " · not comparable by identity") }
        changed.forEach { lines += "  changed ${it.identity}: ${it.s0.joinToString("/")} → ${it.sn.joinToString("/")}" }
        missing.forEach { lines += "  missing ${it.identity}: was ${it.s0.joinToString("/")}" }
        newBehaviour.forEach { lines += "  $it" }
        goldens.filterNot { it.same }.forEach { lines += "  golden ${it.path} differs" + (it.detail?.let { d -> " ($d)" } ?: "") }
        limitations.forEach { lines += "  limit: $it" }
        val shown = lines.take(maxLines)
        val more = if (lines.size > maxLines) "\n  +${lines.size - maxLines} more lines" else ""
        return head + (if (shown.isEmpty()) "" else "\n" + shown.joinToString("\n")) + more
    }
}

/** Builds [EquivalenceReport]s. The comparison is a pure function of the two identity lists. */
public object Equivalence {
    /** Identity-by-identity comparison (D-27 multiplicities, D-50 no ambiguous matching): outcomes as sorted lists. */
    @JvmStatic
    public fun compare(s0: List<TestResult>, sn: List<TestResult>): List<TestComparison> {
        val before = LinkedHashMap<String, MutableList<TestResult>>()
        s0.forEach { before.getOrPut(it.identity.canonical) { ArrayList() } += it }
        val after = LinkedHashMap<String, MutableList<TestResult>>()
        sn.forEach { after.getOrPut(it.identity.canonical) { ArrayList() } += it }
        val out = ArrayList<TestComparison>()
        for ((key, olds) in before) {
            val news = after[key]
            val oldOutcomes = olds.map { it.outcome.name.lowercase() }.sorted()
            if (news == null) {
                out += TestComparison(olds.first().identity.display, TestDelta.Missing, oldOutcomes, emptyList())
                continue
            }
            val newOutcomes = news.map { it.outcome.name.lowercase() }.sorted()
            out += TestComparison(olds.first().identity.display, if (oldOutcomes == newOutcomes) TestDelta.Preserved else TestDelta.ChangedOutcome, oldOutcomes, newOutcomes)
        }
        for ((key, news) in after) {
            if (key in before) continue
            out += TestComparison(news.first().identity.display, TestDelta.New, emptyList(), news.map { it.outcome.name.lowercase() }.sorted())
        }
        return out
    }

    /** Re-shapes the raw log of [receipt] with the same shaper that produced it; empty without a raw blob. */
    @JvmStatic
    public fun reshape(receipt: Receipt, blobs: BlobStore): List<TestResult> {
        val raw = receipt.raw ?: return emptyList()
        val capture = RunCapture(
            actionId = receipt.receiptId, argv = receipt.command, shell = receipt.shell, cwd = receipt.cwd, exitCode = receipt.exitCode,
            output = blobs.get(raw), checkId = receipt.checkId,
        )
        return Shapers.shape(capture).tests
    }

    /**
     * The report for [snapshot] against [sn]: [snReceipt] names the receipt that certifies each suite at `s_n`,
     * [receipt] resolves receipt ids and [finalBytes] reads a path of the final tree (`null` when absent).
     */
    @JvmStatic
    public fun report(
        snapshot: BehaviourSnapshot,
        sn: CandidateId,
        blobs: BlobStore,
        receipt: (String) -> Receipt?,
        snReceipt: (String) -> Receipt?,
        finalBytes: (String) -> ByteArray?,
    ): EquivalenceReport {
        val limits = ArrayList(snapshot.limitations)
        val suites = ArrayList<SuiteComparison>()
        val comparisons = ArrayList<TestComparison>()
        for (suite in snapshot.suites) {
            val base = receipt(suite.receiptId)
            if (base == null) {
                limits += "${suite.checkId}: baseline receipt ${suite.receiptId} is missing"
                suites += SuiteComparison(suite.checkId, suite.receiptId, null, 0, 0, suite.outcome.name.lowercase(), null, comparable = false)
                continue
            }
            val final = snReceipt(suite.checkId)
            val before = reshape(base, blobs)
            val after = final?.let { reshape(it, blobs) }.orEmpty()
            val comparable = before.isNotEmpty() && after.isNotEmpty()
            if (final == null) limits += "${suite.checkId}: no receipt at @${sn.hash8}"
            else if (!comparable) limits += "${suite.checkId}: no per-test identities parsed on ${if (before.isEmpty()) "the baseline" else "the final"} side; counts alone are not equivalence"
            suites += SuiteComparison(suite.checkId, suite.receiptId, final?.receiptId, before.size, after.size, base.outcome.name.lowercase(), final?.outcome?.name?.lowercase(), comparable)
            if (comparable) comparisons += compare(before, after)
        }
        val goldens = snapshot.characterization.map { output ->
            val now = finalBytes(output.path)
            when {
                now == null -> GoldenComparison(output.path, output.blob, same = false, detail = "absent from the final tree")
                now.contentEquals(blobs.get(output.blob)) -> GoldenComparison(output.path, output.blob, same = true)
                else -> GoldenComparison(output.path, output.blob, same = false, detail = "${now.size} bytes now, ${output.sizeBytes} at s0")
            }
        }
        if (snapshot.suites.isEmpty()) limits += "no affected suite in the behaviour snapshot: nothing to compare by identity"
        return EquivalenceReport(
            s0 = snapshot.s0, sn = sn, suites = suites,
            preserved = comparisons.count { it.delta == TestDelta.Preserved },
            changed = comparisons.filter { it.delta == TestDelta.ChangedOutcome },
            missing = comparisons.filter { it.delta == TestDelta.Missing },
            added = comparisons.filter { it.delta == TestDelta.New },
            goldens = goldens, limitations = limits,
        )
    }
}
