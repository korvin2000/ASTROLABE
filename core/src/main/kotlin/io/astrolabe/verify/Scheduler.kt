package io.astrolabe.verify

import io.astrolabe.Astrolabe
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.InputStability
import io.astrolabe.evidence.Limit
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.Receipt
import io.astrolabe.evidence.Receipts
import io.astrolabe.evidence.TestedInputs
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.tool.run.announceMoved
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import kotlin.io.path.relativeTo

/** What one check invocation produced, as the runner or checker reported it: status from exit code and parser, never model-authored (invariant 4). */
public data class Executed(
    val command: List<String>,
    val cwd: String?,
    val shell: Boolean,
    val exit: Int?,
    val outcome: Outcome,
    val counts: Counts?,
    val raw: Digest?,
    val limits: List<String> = emptyList(),
)

/** Paths a check may write without touching its inputs (declared scratch/output policy, D-45): caches, build output, reports. */
public data class ScratchPolicy(val prefixes: Set<String> = DEFAULT_PREFIXES) {
    public fun isScratch(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return prefixes.any { p -> normalized == p || normalized.startsWith("$p/") || normalized.split('/').any { it == p } }
    }

    public companion object {
        @JvmField
        public val DEFAULT_PREFIXES: Set<String> = setOf(
            ".pytest_cache", "__pycache__", ".mypy_cache", ".ruff_cache", ".hypothesis", ".tox", ".nox",
            "build", "dist", "target", "out", ".gradle", "node_modules", ".cache", "coverage", ".coverage", "tmp", ".astrolabe/tmp",
        )
    }
}

/** The currency of a check's last receipt for the candidate at hand (§8.4 refined by D-45). */
public data class Currency(
    val receiptId: String?,
    val applicability: Applicability,
    val eligible: Boolean,
    val green: Boolean,
    val reasons: List<String>,
) {
    /** Only a current, eligible, green receipt certifies the final tree for its check. */
    val certifies: Boolean get() = applicability == Applicability.Current && eligible && green
}

/**
 * Receipt emission and currency (§8.4, D-45, TODO P1.7.4). [runCheck] wraps one check invocation in the
 * exclusive protocol: the workspace mutation lock is held for the whole check, the check's tested inputs —
 * the declared closure's files, plus whatever the caller enumerates for an unknown closure — are hashed and
 * stat-ed before and rescanned after, so a write by the command, by a background process or by a human
 * during the check is recorded in `tested_inputs.mutated_during_check`. A restore-after-mutation writer
 * that leaves the bytes equal still moves the file's metadata and is caught the same way (IX-04). The
 * factual outcome is kept; such a receipt is simply ineligible for the final tree and the check reruns.
 * Declared scratch and output paths are excluded from the inputs, so a cache written by the runner never
 * invalidates a complete closure. `stamp_after == stamp_now` remains necessary, never sufficient.
 */
public class Scheduler(
    private val checks: Checks,
    private val workspace: Workspace,
    private val registry: VersionRegistry,
    private val stamper: Stamper,
    private val receipts: Receipts,
    private val aliases: Aliases,
    private val idGen: IdGen,
    private val ids: Identities,
    private val clock: Clock,
    private val verifierVersion: String = Astrolabe.VERSION,
    private val scratch: ScratchPolicy = ScratchPolicy(),
) {
    private val aliasByReceipt = HashMap<String, String>()

    /** The campaign-global `#n` of [receiptId], when this scheduler recorded it. */
    public fun aliasOf(receiptId: String): String? = aliasByReceipt[receiptId]

    /**
     * Runs [execute] for [check] under the exclusive protocol and records the receipt. [inputs] is the
     * caller's enumeration of the tree for an unknown closure (the atlas rows, typically); without it an
     * unknown closure yields `input_stability = unknown` and the receipt can never be eligible.
     */
    public suspend fun runCheck(check: Check, contractVersion: Int, inputs: Collection<String> = emptyList(), execute: suspend () -> Executed): Receipt {
        val paths = testedInputsFor(check, inputs)
        val limits = ArrayList<Limit>()
        return workspace.mutation.withLock {
            val before = stamper.report()
            val seenBefore = paths.associateWith { snapshot(it) }
            val executed = execute()
            val after = stamper.report()
            announceMoved(registry, before, after, "check ${check.id}")
            val mutated = paths.filter { snapshot(it) != seenBefore.getValue(it) }.toSet()
            val stability = when {
                check.inputClosure == Closure.Unknown && paths.isEmpty() -> {
                    limits += Limit("input_stability", "closure unknown and no inputs enumerated: the tested inputs could not be rescanned")
                    InputStability.Unknown
                }
                else -> InputStability.Exclusive
            }
            if (mutated.isNotEmpty()) limits += Limit("input_mutation", "inputs moved during the check: ${mutated.sorted().joinToString(", ")}; the receipt is ineligible for the final tree — rerun")
            executed.limits.forEach { limits += Limit("runner", it) }
            val outcome = if (executed.outcome == Outcome.Passed && (executed.counts == null || (executed.counts.executed == 0 && executed.counts.discovered == 0))) {
                limits += Limit("evidence", "a pass without parsed counts is inconclusive, never green (D-50)")
                Outcome.Inconclusive
            } else {
                executed.outcome
            }
            val receipt = Receipt(
                receiptId = idGen.next("rcpt"), ids = ids, checkId = check.id, acceptanceIds = check.acceptanceIds,
                command = executed.command, cwd = executed.cwd, shell = executed.shell,
                stampBefore = before.candidateId, stampAfter = after.candidateId, envId = before.env.envId,
                verifierVersion = verifierVersion, checkDefinitionVersion = check.definitionVersion, contractVersion = contractVersion,
                outcome = outcome, parsed = executed.counts, inputClosure = check.inputClosure,
                testedInputs = TestedInputs(seenBefore.mapNotNull { (path, seen) -> seen.version?.let { path to it } }.toMap(), stability, mutated),
                raw = executed.raw, limits = limits, exitCode = executed.exit, at = clock.instant(),
            )
            receipts.record(receipt)
            aliasByReceipt[receipt.receiptId] = aliases.allocate(ids.work, receipt.receiptId, "receipt", ids.context, workspace.id).text
            checks.record(check.id, LastResult(receipt.receiptId, receipt.stampAfter, check.definitionVersion, outcome, executed.counts, Applicability.Current))
            receipt
        }
    }

    /** §8.4 applicability plus D-45 eligibility of a check's last receipt against [stampNow]. */
    public fun currency(check: Check, stampNow: CandidateId?): Currency {
        val last = checks[check.id]?.last ?: return Currency(null, Applicability.Unknown, false, false, listOf("no receipt for ${check.id}"))
        val refreshed = checks.refresh(stampNow).firstOrNull { it.id == check.id }?.last ?: last
        val receipt = receipts.get(last.receiptId)
        val reasons = ArrayList<String>()
        refreshed.staleReason?.let { reasons += it }
        if (receipt == null) reasons += "receipt ${last.receiptId} is not in the store"
        val eligible = receipt?.testedInputs?.eligible ?: false
        if (receipt != null && !eligible) {
            reasons += if (receipt.testedInputs.mutatedDuringCheck.isNotEmpty()) "inputs moved during the check: ${receipt.testedInputs.mutatedDuringCheck.sorted().joinToString(", ")}" else "input stability ${receipt.testedInputs.stability.name.lowercase()} cannot certify the final tree"
        }
        val green = receipt?.outcome?.green ?: false
        if (receipt != null && !green) reasons += "outcome ${receipt.outcome.name.lowercase()}"
        return Currency(last.receiptId, refreshed.applicability, eligible, green, reasons)
    }

    /** The paths whose stability the receipt vouches for: the declared closure minus scratch, or [inputs] for an unknown closure. */
    public fun testedInputsFor(check: Check, inputs: Collection<String>): List<String> {
        val declared: Collection<String> = when (val closure = check.inputClosure) {
            is Closure.Known -> closure.paths
            is Closure.Package -> filesUnder(closure.path) + inputs
            Closure.Unknown -> inputs
        }
        return declared.map { it.replace('\\', '/') }.filterNot { scratch.isScratch(it) }.distinct().sorted()
    }

    private fun filesUnder(prefix: String): List<String> {
        val resolved = workspace.resolve(prefix, Intent.Read) as? PathResolution.Resolved ?: return emptyList()
        if (!Files.isDirectory(resolved.real)) return listOf(resolved.relative)
        return try {
            Files.walk(resolved.real).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { it.relativeTo(workspace.root).joinToString("/") { part -> part.toString() } }
                    .filter { !it.startsWith(".git/") }
                    .toList()
            }
        } catch (failure: IOException) {
            emptyList()
        }
    }

    /** Content and metadata of one input: a restore-after-write leaves the version equal but moves the metadata. */
    private data class Seen(val version: FileVersion?, val sizeBytes: Long?, val modifiedEpochMillis: Long?)

    private fun snapshot(path: String): Seen {
        val resolved = workspace.resolve(path, Intent.Read) as? PathResolution.Resolved ?: return Seen(null, null, null)
        val attributes = try {
            Files.readAttributes(resolved.real, BasicFileAttributes::class.java)
        } catch (missing: IOException) {
            return Seen(null, null, null)
        }
        if (attributes.isDirectory) return Seen(null, null, null)
        return Seen(registry.read(path)?.version, attributes.size(), attributes.lastModifiedTime().toMillis())
    }
}
