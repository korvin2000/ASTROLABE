package io.astrolabe.eval

import io.astrolabe.id.Digest
import java.nio.file.Path

/** §19.2 integrity failures. Each rejects the campaign except a consumed holdout the evidence is not drawn from. */
public enum class ContaminationKind {
    HiddenAcceptanceInWorkspace, HiddenAcceptanceCopied, AnswerInMemory,
    ColdMemoryNotReset, WarmMemoryNotFrozen, HoldoutConsumed, FinalConsultedBeforeFreeze,
}

public data class ContaminationFinding(val kind: ContaminationKind, val subject: String, val detail: String, val rejects: Boolean)

/** A hidden acceptance artifact: absolute [location] and content [digest]; it must stay outside every solver workspace. */
public data class HiddenAcceptance(val task: String, val location: String, val digest: Digest) {
    init { label(task); require(Path.of(location).isAbsolute) { "hidden acceptance location must be absolute" } }
}

/** What one solver run could read: its absolute [root] and each workspace-relative file's content digest. */
public class SolverWorkspace(public val run: String, public val root: String, files: Map<String, Digest>) {
    public val files: Map<String, Digest> = frozenMap(files.toSortedMap())
    init { label(run); require(Path.of(root).isAbsolute) { "workspace root must be absolute" } }
}

/** A task's answer-bearing content: the lines its reference repair adds. */
public class AnswerKey(public val task: String, lines: Collection<String>) {
    public val lines: List<String> = immutable(lines.map(::normalized).filter { it.isNotEmpty() }.distinct().sorted())
    init { label(task) }
}

public enum class MemoryItemKind { Note, Patch, Metadata }

public data class MemoryItem(val id: String, val kind: MemoryItemKind, val text: String) {
    init { label(id) }
}

/** The mutable memory one run could consult (notes, patches, metadata), as captured when it started. */
public class RunMemory(public val run: String, public val mode: MemoryMode, items: List<MemoryItem>) {
    public val items: List<MemoryItem> = immutable(items.sortedBy { it.id })
    public val digest: Digest = memoryDigest(this.items)
    init { label(run); require(this.items.map { it.id }.distinct().size == this.items.size) { "duplicate memory item" } }

    public companion object {
        /** The digest warm runs must all start from: the frozen memory snapshot. */
        @JvmStatic
        public fun snapshotDigest(items: List<MemoryItem>): Digest = memoryDigest(items.sortedBy { it.id })
    }
}

/** One look at a partition's outcomes; [sequence] orders it against the manifest freeze. */
public data class Consultation(val partition: WorkloadPartition, val sequence: Long, val purpose: String) {
    init { require(sequence >= 0); require(purpose.isNotBlank()) }
}

/**
 * Everything the integrity check reads (D-222): hidden acceptance, solver workspaces, answer keys, per-run memory, the
 * frozen warm snapshot, the consultation ledger and the manifest freeze point. [evidencePartition] is the partition
 * the promotion evidence comes from; a holdout is consumed after more than [maxConsultations] looks.
 */
public class IntegrityInput @JvmOverloads constructor(
    hidden: List<HiddenAcceptance>,
    workspaces: List<SolverWorkspace>,
    answers: List<AnswerKey>,
    memories: List<RunMemory>,
    public val frozenMemory: Digest?,
    consultations: List<Consultation>,
    public val freezeSequence: Long,
    public val evidencePartition: WorkloadPartition = WorkloadPartition.Final,
    public val maxConsultations: Int = 1,
    public val minAnswerChars: Int = 12,
) {
    public val hidden: List<HiddenAcceptance> = immutable(hidden)
    public val workspaces: List<SolverWorkspace> = immutable(workspaces)
    public val answers: List<AnswerKey> = immutable(answers)
    public val memories: List<RunMemory> = immutable(memories)
    public val consultations: List<Consultation> = immutable(consultations.sortedBy { it.sequence })
    init { require(freezeSequence >= 0 && maxConsultations >= 1 && minAnswerChars >= 1) }
}

/** [contaminated] rejects the campaign (FX-47); [evidence] is what `EvaluationEvidence.integrity` takes. */
public class IntegrityVerdict internal constructor(findings: List<ContaminationFinding>) {
    public val findings: List<ContaminationFinding> = immutable(findings)
    public val contaminated: Boolean = this.findings.any { it.rejects }
    public val evidence: EvidenceCheck = if (contaminated) EvidenceCheck.Fail else EvidenceCheck.Pass

    /** Partitions that can no longer serve as a holdout. */
    public val consumed: Set<WorkloadPartition> = frozenSet(this.findings.filter { it.kind == ContaminationKind.HoldoutConsumed }
        .map { WorkloadPartition.valueOf(it.subject) })
}

public object CampaignIntegrity {
    @JvmStatic
    public fun check(input: IntegrityInput): IntegrityVerdict = IntegrityVerdict(buildList {
        // Hidden acceptance stays outside the solver's workspace, by location and by content (§19.2).
        for (ws in input.workspaces) {
            val root = Path.of(ws.root).normalize()
            val digests = ws.files.entries.groupBy({ it.value }, { it.key })
            for (h in input.hidden) {
                if (Path.of(h.location).normalize().startsWith(root))
                    add(ContaminationFinding(ContaminationKind.HiddenAcceptanceInWorkspace, ws.run, "${h.task}: ${h.location}", true))
                digests[h.digest]?.forEach { add(ContaminationFinding(ContaminationKind.HiddenAcceptanceCopied, ws.run, "${h.task}: $it", true)) }
            }
        }
        // Answer-bearing patches, notes and metadata are removed: no reachable memory item carries an answer line.
        for (memory in input.memories) for (item in memory.items) {
            val text = normalized(item.text)
            for (key in input.answers) key.lines.firstOrNull { it.length >= input.minAnswerChars && it in text }?.let {
                add(ContaminationFinding(ContaminationKind.AnswerInMemory, memory.run, "${item.kind} ${item.id} carries ${key.task}'s answer", true))
            }
        }
        // Cold runs start from reset memory; warm runs from the one frozen snapshot, equal for all.
        for (memory in input.memories) when (memory.mode) {
            MemoryMode.Cold -> if (memory.items.isNotEmpty())
                add(ContaminationFinding(ContaminationKind.ColdMemoryNotReset, memory.run, "${memory.items.size} items", true))
            MemoryMode.Warm -> if (input.frozenMemory == null || memory.digest != input.frozenMemory)
                add(ContaminationFinding(ContaminationKind.WarmMemoryNotFrozen, memory.run, "memory ${memory.digest.hex}", true))
        }
        // A repeatedly consulted selection or final set is no longer a holdout; the final set is not seen before the freeze.
        val looks = input.consultations.groupBy { it.partition }
        for (partition in listOf(WorkloadPartition.Selection, WorkloadPartition.Final)) {
            val count = looks[partition].orEmpty().size
            if (count > input.maxConsultations)
                add(ContaminationFinding(ContaminationKind.HoldoutConsumed, partition.name, "$count consultations", partition == input.evidencePartition))
        }
        looks[WorkloadPartition.Final].orEmpty().filter { it.sequence < input.freezeSequence }.forEach {
            add(ContaminationFinding(ContaminationKind.FinalConsultedBeforeFreeze, WorkloadPartition.Final.name, "${it.purpose} at ${it.sequence}", true))
        }
    })
}

private fun normalized(text: String): String = text.trim().replace(Regex("\\s+"), " ")

private fun memoryDigest(items: List<MemoryItem>): Digest = fingerprint("run-memory", buildList {
    add(items.size); items.forEach { addAll(listOf(it.id, it.kind, it.text)) }
})
