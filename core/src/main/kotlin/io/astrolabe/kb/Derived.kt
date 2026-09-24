package io.astrolabe.kb

import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.Digest
import io.astrolabe.register.OpenItem
import io.astrolabe.register.Register
import io.astrolabe.verify.Finding
import io.astrolabe.verify.Severity

/**
 * Candidates derived from records, never from the model's words (§12.2, §8.8): `x` facts at campaign end, review
 * findings at or above major, and repair diagnoses (dead ends) that recur across cells become `PIT` candidates;
 * the findings also become `Open` items of the next register. Pure functions of the records.
 */
public object Derived {
    /** Everything the extractor derives from one trace: refuted facts, the campaign review's findings and dead ends recurring in [earlier] registers. */
    @JvmStatic
    public fun candidates(trace: ExtractionTrace, findings: List<Finding>, earlier: List<Register>): List<Candidate> =
        refuted(trace) + fromFindings(findings, trace.sourceRevision, trace.packet.increment) + recurring(earlier + trace.finalState, trace.sourceRevision)

    /** Every `x` fact with evidence: the refuting evidence, else the fact's own, is the diagnosis's; unevidenced facts are no candidate. */
    @JvmStatic
    public fun refuted(trace: ExtractionTrace): List<Candidate> {
        val register = trace.finalState
        return register.facts.filter { it.kind == ClaimKind.Refuted }.mapNotNull { fact ->
            val evidence = listOfNotNull(fact.refutedBy, fact.evidenceId)
            if (evidence.isEmpty()) return@mapNotNull null
            val anchors = listOfNotNull(fact.anchor?.let { NoteAnchor(it.path, it.version.digest.hex.take(12)) })
            Candidate(
                CandidateKind.PIT, "refuted-${register.cell.value}-${fact.n}", fact.text.take(Note.MAX_SUMMARY_CHARS), scope(anchors.map { it.path }, trace),
                Diagnosis(
                    fact.text, "while implementing ${register.increment} (${register.incrementTitle})", "held as a hypothesis", "refuted by ${evidence.first()}",
                    "the record was refuted, not merely unproven", evidence, trace.sourceRevision, "the anchor moves or the evidence is superseded",
                ),
                anchors, confidence = 0.5,
            )
        }
    }

    /** §8.8: findings at or above major become `PIT` candidates anchored at their location. */
    @JvmStatic
    public fun fromFindings(findings: List<Finding>, sourceRevision: String, increment: String): List<Candidate> =
        findings.filter { it.severity <= Severity.Major }.map { finding ->
            val path = finding.location.substringBefore(':').substringBefore('@')
            val anchors = if (path.isNotBlank()) listOf(NoteAnchor(path)) else emptyList()
            Candidate(
                CandidateKind.PIT, "finding-" + Digest.ofUtf8(finding.location + finding.issue).hex.take(12), finding.issue.take(Note.MAX_SUMMARY_CHARS),
                anchors.firstOrNull()?.let { subsystem(it.path) } ?: "task:$increment",
                Diagnosis(
                    finding.issue, "when reviewed at ${finding.location}", finding.suggestedFix ?: "no fix proposed", "${finding.severity.name.lowercase()} ${finding.kind.name.lowercase()} finding",
                    finding.issue, listOf(finding.location), sourceRevision, "the location changes or the finding is closed",
                ),
                anchors, confidence = 0.5,
            )
        }

    /** §8.8: the same findings as `Open` items for the next register, numbered from [from]. */
    @JvmStatic
    public fun openItems(findings: List<Finding>, from: Int): List<OpenItem> =
        findings.filter { it.severity <= Severity.Major }.mapIndexed { i, finding ->
            OpenItem(from + i, "review ${finding.severity.name.lowercase()}: ${finding.issue} at ${finding.location}", trip = finding.location.substringBefore(':').substringBefore('@').ifBlank { null }, needs = finding.suggestedFix)
        }

    /** A dead end whose text recurs in two or more cells' registers is a recurring repair diagnosis; its evidence is the dead ends'. */
    @JvmStatic
    public fun recurring(registers: List<Register>, sourceRevision: String): List<Candidate> {
        val byText = LinkedHashMap<String, MutableList<Pair<Register, io.astrolabe.register.DeadEnd>>>()
        for (register in registers) for (deadEnd in register.deadEnds) byText.getOrPut(normalize(deadEnd.text)) { ArrayList() } += register to deadEnd
        return byText.values.filter { hits -> hits.map { it.first.cell }.distinct().size >= 2 }.mapNotNull { hits ->
            val evidence = hits.mapNotNull { it.second.evidence }.distinct()
            if (evidence.isEmpty()) return@mapNotNull null
            val (register, first) = hits.first()
            Candidate(
                CandidateKind.PIT, "recurring-" + Digest.ofUtf8(normalize(first.text)).hex.take(12), first.text.take(Note.MAX_SUMMARY_CHARS), "task:${register.increment}",
                Diagnosis(
                    first.text, "under ${first.scope}; recurred in ${hits.map { it.first.cell.value }.distinct().size} cells", "repair in each cell", "the same dead end again",
                    "reopen ${first.reopen}", evidence, sourceRevision, "reopen: ${first.reopen}",
                ),
                confidence = 0.5,
            )
        }
    }

    private fun scope(paths: List<String>, trace: ExtractionTrace): String =
        (paths + trace.diffSummary.map { it.path }).firstOrNull()?.let(::subsystem) ?: "task:${trace.packet.increment}"

    /** `subsystem:<directory>` of a path, or the task family when the path has none. */
    private fun subsystem(path: String): String? = path.substringBeforeLast('/', "").takeIf { it.isNotBlank() }?.let { "subsystem:$it" }

    private fun normalize(text: String): String = text.lowercase().replace(Regex("\\s+"), " ").trim()
}
