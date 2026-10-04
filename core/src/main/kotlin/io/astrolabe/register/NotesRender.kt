package io.astrolabe.register

import io.astrolabe.evidence.ClaimKind

/** One note of a direct register: its id (A-D.4, `h<n>`, `v<n>`, `d<n>`, `dead<n>`, `o<n>`, `a<n>`, `x<n>`) and its line. */
public data class NoteLine(val id: String, val text: String) {
    override fun toString(): String = "$id $text"
}

/**
 * The direct protocol's view of the register (kernel contract A-D.4, rendered turn §5.10-D): notes with ids, never the
 * STATE block. Deterministic: the same register gives the same lines; no clock, no counter beyond the register's own.
 */
public object NotesRender {
    /** The id of fact [fact]: `h<n>`, `v<n>`, or `x<n>` once refuted. */
    @JvmStatic
    public fun factId(fact: Fact): String = when (fact.kind) {
        ClaimKind.Hypothesis -> "h${fact.n}"
        ClaimKind.Verified -> "v${fact.n}"
        ClaimKind.Refuted -> "x${fact.n}"
    }

    /**
     * The `[A]` notes, in the §5.10-D order — open items that are not closed, dead ends, decisions, pending amendments,
     * stale verified notes, hypotheses, verified notes — each kind newest first.
     */
    @JvmStatic
    public fun anchorLines(register: Register): List<NoteLine> {
        val out = ArrayList<NoteLine>()
        register.open.filter { !it.closed }.sortedByDescending { it.n }.forEach { out += NoteLine("o${it.n}", it.text) }
        register.deadEnds.sortedByDescending { it.n }.forEach { out += NoteLine("dead${it.n}", it.text + (it.evidence?.let { e -> " [$e]" } ?: "")) }
        register.decisions.sortedByDescending { it.n }.forEach { out += NoteLine("d${it.n}", decision(it)) }
        val positions = register.amendmentPositions()
        register.amendments.withIndex().filter { it.value.status == PENDING }.sortedByDescending { positions[it.index] }
            .forEach { (i, a) -> out += NoteLine("a${positions[i]}", "${a.change} (${a.status})") }
        val verified = register.facts.filter { it.kind == ClaimKind.Verified }
        verified.filter { it.stale }.sortedByDescending { it.n }.forEach { out += NoteLine("v${it.n}(stale @${it.staleAt!!.hash8.take(4)})", it.text) }
        register.facts.filter { it.kind == ClaimKind.Hypothesis }.sortedByDescending { it.n }.forEach { out += NoteLine("h${it.n}", it.text) }
        verified.filter { !it.stale }.sortedByDescending { it.n }.forEach { out += NoteLine("v${it.n}", it.text + (it.evidenceId?.let { e -> " [$e]" } ?: "")) }
        return out
    }

    /**
     * Every active note with its id: the `[A]` order, then what `[A]` never lists — decided amendments and refuted facts
     * that are not archived yet. What `look(recall, id="notes")` and the carry-forward show.
     */
    @JvmStatic
    public fun activeLines(register: Register): List<NoteLine> {
        val positions = register.amendmentPositions()
        val decided = register.amendments.withIndex().filter { it.value.status != PENDING }.sortedByDescending { positions[it.index] }
            .map { (i, a) -> NoteLine("a${positions[i]}", "${a.change} (${a.status})") }
        val refuted = register.facts.filter { it.kind == ClaimKind.Refuted }.sortedByDescending { it.n }.map(::refuted)
        return anchorLines(register) + decided + refuted
    }

    /** The archived notes (`range="archive"`): closed open items, refuted facts, decided amendments — oldest first. */
    @JvmStatic
    public fun archiveLines(register: Register): List<NoteLine> =
        register.archive.open.sortedBy { it.n }.map { NoteLine("o${it.n}", it.text + " (closed" + (it.closedEvidence?.let { e -> " $e" } ?: "") + ")") } +
            register.archive.facts.sortedBy { it.n }.map(::refuted) +
            register.archive.amendments.sortedBy { it.position }.map { NoteLine("a${it.position}", "${it.line.change} (${it.line.status})") }

    /** A-D.4 carry-forward of a direct line: the whole active register with ids, under one header. */
    @JvmStatic
    public fun carry(register: Register): String {
        val lines = activeLines(register)
        if (lines.isEmpty()) return ""
        return "Notes:\n" + lines.joinToString("") { "  - $it\n" }
    }

    /** The register as the direct protocol shows it where the structured one shows STATE: a header and the active notes. */
    @JvmStatic
    public fun text(register: Register): String =
        "── Notes (STATE v${register.version})\n" + activeLines(register).joinToString("") { "$it\n" }

    private fun decision(d: Decision): String = d.text + (if (d.because.isBlank()) "" else " — because ${d.because}")

    private fun refuted(f: Fact): NoteLine = NoteLine("x${f.n}", f.text + (f.refutedBy?.let { " [refuted $it]" } ?: " [refuted]"))

    private const val PENDING = "pending"
}
