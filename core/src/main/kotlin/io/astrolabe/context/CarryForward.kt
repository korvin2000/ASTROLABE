package io.astrolabe.context

import io.astrolabe.cell.ResultPacket
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.FileVersion
import io.astrolabe.register.Mark
import io.astrolabe.register.Register
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workspace.Ranges

/** The last receipt of one check with its validity at the next cell's base (§6.2 "verification status"). */
public data class CarriedReceipt(val checkId: String, val receiptId: String, val validity: String)

/** One touched path, compressed to its version (§6.2 "touched ledger"; diffs stay in the store). */
public data class CarriedTouch(val path: String, val version: FileVersion?)

/** A Workset entry that is not re-served: its file changed since it was displayed, or it did not fit the seed budget. */
public data class NotSeen(val path: String, val range: Ranges, val was: FileVersion, val now: FileVersion?, val reason: String) {
    val text: String get() = "$path:$range $reason @${was.hash8}" + (now?.let { "→@${it.hash8}" } ?: "") + " · read again"
}

/**
 * What one cell hands the next (§6.2). [register] is validated — `v` facts whose anchor moved are tagged stale and
 * those whose evidence does not resolve are listed in [unresolvedEvidence]; dead ends, open items, decisions and
 * refuted facts travel verbatim. [seeds] are the only KNOWN entries, re-served at their current versions; transcript,
 * model prose and raw logs are never carried.
 */
public data class Carry(
    val register: Register,
    val seeds: List<Entry>,
    val notSeen: List<NotSeen>,
    val receipts: List<CarriedReceipt>,
    val touched: List<CarriedTouch>,
    val pinned: List<String>,
    /** Runtime-owned facts of the previous packet: status, reason, gaps, receipts — never its claims. */
    val packetLine: String?,
    val unresolvedEvidence: List<Int>,
) {
    val seedTokens: Long get() = seeds.sumOf { it.tokens }

    /** The declared knowledge line of the next cell's first turn (FX-11). */
    val known: String
        get() = "KNOWN: seeds only (${seeds.size}) · NOT SEEN: everything else" + notSeen.joinToString("") { "; ${it.text}" }

    /** The `[K]` carry-forward block: deterministic, verbatim records only. */
    public fun render(): String = buildString {
        append("CARRY-FORWARD from ").append(register.cell.value).append(" (STATE v").append(register.version).append(")\n")
        if (pinned.isNotEmpty()) {
            append("Pinned user messages:\n")
            pinned.forEach { append("  - ").append(it).append('\n') }
        }
        packetLine?.let { append("Previous packet: ").append(it).append('\n') }
        val refuted = register.facts.filter { it.kind == ClaimKind.Refuted }
        if (refuted.isNotEmpty()) {
            append("Refuted:\n")
            refuted.forEach { append("  - x ").append(it.text).append(it.refutedBy?.let { e -> " [$e]" } ?: "").append('\n') }
        }
        if (register.deadEnds.isNotEmpty()) {
            append("Dead ends:\n")
            register.deadEnds.forEach { append("  - ").append(it.text).append(" · scope ").append(it.scope).append(" · reopen ").append(it.reopen).append(it.evidence?.let { e -> " [$e]" } ?: "").append('\n') }
        }
        val open = register.open.filter { !it.closed }
        if (open.isNotEmpty()) {
            append("Open:\n")
            open.forEach { append("  - ").append(it.text).append(it.trip?.let { t -> " · trip $t" } ?: "").append('\n') }
        }
        if (register.decisions.isNotEmpty()) {
            append("Decisions:\n")
            register.decisions.forEach { d ->
                append("  - ").append(d.text).append(" because ").append(d.because).append(d.rejected?.let { " · rejected $it" } ?: "")
                    .append(if (d.adrCandidate) " (→ candidate ADR)" else "").append('\n')
            }
        }
        if (register.amendments.isNotEmpty()) {
            append("Amendments proposed:\n")
            register.amendments.forEach { append("  - ").append(it.change).append(" (").append(it.status).append(")\n") }
        }
        if (receipts.isNotEmpty()) append("Verification: ").append(receipts.joinToString(" · ") { "${it.checkId} ${it.receiptId} (${it.validity})" }).append('\n')
        if (touched.isNotEmpty()) append("Touched: ").append(touched.joinToString(" · ") { "${it.path}@${it.version?.hash8 ?: "gone"}" }).append('\n')
        append(known)
    }
}

/** `carry_forward` (§6.2, P2.4.1): a pure function of the previous cell's records and the current file versions. */
public object CarryForward {
    public const val SEED_CAP_TOKENS: Long = 4_000

    @JvmStatic
    @JvmOverloads
    public fun carry(
        previous: Register,
        export: List<Entry>,
        packet: ResultPacket?,
        currentVersion: (String) -> FileVersion?,
        evidenceExists: (String) -> Boolean,
        receipts: List<CarriedReceipt>,
        pinned: List<String>,
        seedCapTokens: Long = SEED_CAP_TOKENS,
    ): Carry {
        val unresolved = ArrayList<Int>()
        val facts = previous.facts.map { fact ->
            if (fact.kind != ClaimKind.Verified) return@map fact
            if (fact.evidenceId != null && !evidenceExists(fact.evidenceId)) unresolved += fact.n
            val anchor = fact.anchor
            if (anchor != null && fact.staleAt == null && currentVersion(anchor.path) != anchor.version) fact.copy(staleAt = anchor.version) else fact
        }
        val register = previous.copy(facts = facts)

        val next = previous.cursor ?: previous.plan.firstOrNull { it.mark == Mark.Todo }
        val texts = listOfNotNull(next?.text, next?.accept, previous.next)
        val referenced = export.filter { entry -> mentions(texts, entry.path) || inFocus(previous.focus, entry.path) }
            .sortedWith(compareBy({ it.path }, { it.range.ranges.first().from }))
        val seeds = ArrayList<Entry>()
        val notSeen = ArrayList<NotSeen>()
        var budget = seedCapTokens
        for (entry in referenced) {
            val now = currentVersion(entry.path)
            when {
                now != entry.version -> notSeen += NotSeen(entry.path, entry.range, entry.version, now, "changed")
                entry.tokens > budget -> notSeen += NotSeen(entry.path, entry.range, entry.version, now, "over the ${seedCapTokens}-token seed budget")
                else -> {
                    seeds += entry.copy(source = EntrySource.Seed)
                    budget -= entry.tokens
                }
            }
        }

        val touched = packet?.changes.orEmpty().groupBy { it.path }.map { (path, changes) -> CarriedTouch(path, changes.last().after) }.sortedBy { it.path }
        val packetLine = packet?.let { p ->
            "${p.status.wire}" + (p.reason?.let { " ($it)" } ?: "") +
                (if (p.gaps.isEmpty()) "" else " · gaps: ${p.gaps.joinToString("; ")}") +
                (if (p.receipts.isEmpty()) "" else " · receipts: ${p.receipts.joinToString(", ")}")
        }
        return Carry(register, seeds, notSeen, receipts, touched, pinned, packetLine, unresolved)
    }

    private fun mentions(texts: List<String>, path: String): Boolean {
        val name = path.substringAfterLast('/')
        return texts.any { text -> path in text || Regex("(^|[^\\w.])${Regex.escape(name)}($|[^\\w])").containsMatchIn(text) }
    }

    private fun inFocus(focus: String?, path: String): Boolean {
        val dir = focus?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return false
        return path == dir || path.startsWith("$dir/")
    }
}
