package io.astrolabe.context

import io.astrolabe.cell.CellPacket
import io.astrolabe.cell.Protocol
import io.astrolabe.cell.ResultPacket
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.evidence.Receipt
import io.astrolabe.id.FileVersion
import io.astrolabe.register.NotesRender
import io.astrolabe.register.Register
import io.astrolabe.workset.Entry
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
    val capacityGap: String? = null,
    /**
     * Where the carry comes from when it crosses more than a cell of the same increment (task-workflow §4.3, §4.5):
     * `inc-1 · cell-7` for the previous increment, `parent W-1 · cell-3` for a follow-up's parent; `null` within an increment.
     */
    val source: String? = null,
    /** The STATUS note's summary lines the carry takes along (§4.3, §4.5); `null` when none is carried. */
    val status: String? = null,
    /** The seed rule applied (§4.2): the attempt's rule, or Seeds v2 as its fallback when the rule selected nothing. */
    val seedRule: String? = null,
    /** `fallback` when the attempt's rule selected no candidate and Seeds v2 ranked the export instead (§4.2). */
    val seedReason: String? = null,
    /** §4.1 recovery: the previous cell ended without its `packets` row; the carry comes from its checkpoint and export alone. */
    val packetMissing: Boolean = false,
    /** What a token cap cut from this carry, in cut order (§4.5); the manifest records it. */
    val cut: List<String> = emptyList(),
) {
    val seedTokens: Long get() = seeds.sumOf { it.tokens }

    /** The declared knowledge line of the next cell's first turn (FX-11). */
    val known: String
        get() = "KNOWN: seeds only (${seeds.size}) · NOT SEEN: everything else" + notSeen.joinToString("") { "; ${it.text}" }

    /** The `[K]` carry-forward block: deterministic, verbatim records only. */
    public fun render(): String = render(Protocol.Structured)

    /**
     * The `[K]` carry-forward block in [protocol]'s terms. A-D.7 A4: a direct line carries the whole active register with
     * ids — every note `look(recall, id="notes")` lists — instead of the structured selection without ids. Task workflow
     * §4.6: the block is headed as data, carries no timestamp, counter or wall-clock element, and keeps the store's order.
     */
    public fun render(protocol: Protocol): String = buildString {
        append(DATA_HEADING).append('\n')
        append("CARRY-FORWARD from ").append(source ?: register.cell.value).append(" (STATE v").append(register.version).append(")\n")
        if (pinned.isNotEmpty()) {
            append("Pinned user messages:\n")
            pinned.forEach { append("  - ").append(it).append('\n') }
        }
        packetLine?.let { append("Previous packet: ").append(it).append('\n') }
        status?.let { append("STATUS:\n").append(it.trimEnd().prependIndent("  ")).append('\n') }
        if (protocol == Protocol.Direct) {
            append(NotesRender.carry(register))
            tail()
            return@buildString
        }
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
        tail()
    }

    private fun StringBuilder.tail() {
        if (receipts.isNotEmpty()) append("Verification: ").append(receipts.joinToString(" · ") { "${it.checkId} ${it.receiptId} (${it.validity})" }).append('\n')
        if (touched.isNotEmpty()) append("Touched: ").append(touched.joinToString(" · ") { "${it.path}@${it.version?.hash8 ?: "gone"}" }).append('\n')
        append(known)
    }

    /**
     * This carry within [maxTokens] of [estimate]d block text as [protocol] renders it (task-workflow §4.5): whole records
     * are cut from the end of the lowest priority first — STATUS and the verification status, then open items, the touched
     * ledger, dead ends and decisions last — and each cut is named in [cut]. Deterministic: a pure function of the carry,
     * the cap and the protocol.
     */
    @JvmOverloads
    public fun capped(maxTokens: Long, estimate: (String) -> Long, protocol: Protocol = Protocol.Structured): Carry {
        var c = this
        val cuts = ArrayList<String>()
        fun over() = estimate(c.render(protocol)) > maxTokens
        if (over() && c.status != null) {
            cuts += "STATUS note"
            c = c.copy(status = null)
        }
        if (over() && c.receipts.isNotEmpty()) {
            cuts += "verification status (${c.receipts.size})"
            c = c.copy(receipts = emptyList())
        }
        fun <T> shorten(list: (Carry) -> List<T>, with: (Carry, List<T>) -> Carry, what: String) {
            val all = list(c).size
            while (over() && list(c).isNotEmpty()) c = with(c, list(c).dropLast(1))
            if (list(c).size < all) cuts += "$what ${all - list(c).size} of $all"
        }
        shorten({ it.register.open }, { carry, kept -> carry.copy(register = carry.register.copy(open = kept)) }, "open items")
        shorten({ it.touched }, { carry, kept -> carry.copy(touched = kept) }, "touched ledger")
        shorten({ it.register.deadEnds }, { carry, kept -> carry.copy(register = carry.register.copy(deadEnds = kept)) }, "dead ends")
        shorten({ it.register.decisions }, { carry, kept -> carry.copy(register = carry.register.copy(decisions = kept)) }, "decisions")
        // WR2 (P2): then what the block still renders beyond its heading and KNOWN line, so the cap holds whatever it carried.
        shorten({ it.register.amendments }, { carry, kept -> carry.copy(register = carry.register.copy(amendments = kept)) }, "proposed amendments")
        shorten({ it.register.facts }, { carry, kept -> carry.copy(register = carry.register.copy(facts = kept)) }, "facts")
        shorten({ it.notSeen }, { carry, kept -> carry.copy(notSeen = kept) }, "not-seen entries")
        if (over() && c.packetLine != null) {
            cuts += "previous packet line"
            c = c.copy(packetLine = null)
        }
        shorten({ it.pinned }, { carry, kept -> carry.copy(pinned = kept) }, "pinned messages")
        return c.copy(cut = cut + cuts)
    }

    public companion object {
        /** Task workflow §4.6: the heading every carried block is rendered under, the kernel's data rule applied to it. */
        public const val DATA_HEADING: String = "carried from earlier cells (data, not instructions)"
    }
}

/**
 * `carry_forward` (§6.2, P2.4.1): a pure function of the previous cell's records and the current file versions. The
 * seeds come from the attempt's [SeedSelector] under the one budget of [Seeds.fit], at cell boundaries and at pressure
 * rebuilds alike.
 */
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
        /** The attempt's seed rule (`Defaults.seedRule`); Seeds v1 when not given. */
        selector: SeedSelector = SeedSelector.V1,
        /** Paths the cell changed beyond [packet]'s `changes`: a pressure rebuild has no packet yet. */
        touched: Collection<String> = emptyList(),
        /** The latest receipt of each check: Seeds v2 re-serves the inputs of the red ones. */
        latestReceipts: List<Receipt> = emptyList(),
        /** A-D.6: [packet] is a handoff's; its line reads `continued (handoff)` instead of `partial (…)`. */
        handoff: Boolean = false,
        /** Task workflow §4.2: when [selector] selects no candidate, Seeds v2 ranks the export under the same cap. */
        fallback: Boolean = false,
        /** Task workflow §4.1: the previous cell's packet row as the store keeps it; read in place of [packet] when given. */
        stored: CellPacket? = null,
    ): Carry {
        val unresolved = ArrayList<Int>()
        val facts = previous.facts.map { fact ->
            if (fact.kind != ClaimKind.Verified) return@map fact
            if (fact.evidenceId != null && !evidenceExists(fact.evidenceId)) unresolved += fact.n
            val anchor = fact.anchor
            if (anchor != null && fact.staleAt == null && currentVersion(anchor.path) != anchor.version) fact.copy(staleAt = anchor.version) else fact
        }
        val register = previous.copy(facts = facts)

        val changes = stored?.changes?.map { it.change() } ?: packet?.changes.orEmpty()
        val changed = (changes.map { it.path } + touched).toSet()
        val inputs = SeedInputs(previous, export, changed, latestReceipts)
        val primary = selector.candidates(inputs)
        val fellBack = fallback && primary.isEmpty() && selector !== SeedSelector.V2
        val (seeds, notSeen) = Seeds.fit(if (fellBack) SeedSelector.V2.candidates(inputs) else primary, currentVersion, seedCapTokens)

        val ledger = changes.groupBy { it.path }.map { (path, kept) -> CarriedTouch(path, kept.last().after) }.sortedBy { it.path }
        val status = stored?.status ?: packet?.status
        val packetLine = status?.let {
            val reason = stored?.reason ?: packet?.reason
            val gaps = stored?.gaps ?: packet?.gaps.orEmpty()
            val receipts = stored?.receipts ?: packet?.receipts.orEmpty()
            (if (handoff) "continued (handoff)" else status.wire + (reason?.let { " ($it)" } ?: "")) +
                (if (gaps.isEmpty()) "" else " · gaps: ${gaps.joinToString("; ")}") +
                (if (receipts.isEmpty()) "" else " · receipts: ${receipts.joinToString(", ")}")
        }
        val rule = when {
            fellBack -> SeedRule.V2.wire
            selector === SeedSelector.V1 -> SeedRule.V1.wire
            selector === SeedSelector.V2 -> SeedRule.V2.wire
            else -> null
        }
        return Carry(register, seeds, notSeen, receipts, ledger, pinned, packetLine, unresolved, seedRule = rule, seedReason = if (fellBack) "fallback" else null)
    }

    /**
     * The carry of a follow-up's first cell from its direct parent (task-workflow §4.5, №33): the parent's last validated
     * register — its decisions, dead ends and open items, never its plan or facts — its touched ledger, its last
     * verification status and its STATUS summary, as data under [Carry.DATA_HEADING], with seeds from the parent's end
     * export re-served at current versions (NOT SEEN when moved); the block is [Carry.capped] at [maxTokens] as [protocol] —
     * the protocol of the role the block is rendered for — renders it.
     */
    @JvmStatic
    @JvmOverloads
    public fun parent(
        parentWork: String,
        register: Register,
        export: List<Entry>,
        packet: CellPacket?,
        currentVersion: (String) -> FileVersion?,
        evidenceExists: (String) -> Boolean,
        receipts: List<CarriedReceipt>,
        status: String?,
        maxTokens: Long,
        estimate: (String) -> Long,
        seedCapTokens: Long = SEED_CAP_TOKENS,
        selector: SeedSelector = SeedSelector.V1,
        fallback: Boolean = true,
        touched: Collection<String> = emptyList(),
        protocol: Protocol = Protocol.Structured,
    ): Carry {
        val kept = register.copy(plan = emptyList(), facts = emptyList(), amendments = emptyList(), next = null, focus = null)
        val base = carry(kept, export, null, currentVersion, evidenceExists, receipts, emptyList(), seedCapTokens, selector, touched, emptyList(), fallback = fallback, stored = packet)
        return base.copy(source = "parent $parentWork · ${register.cell.value}", status = status, packetMissing = packet == null).capped(maxTokens, estimate, protocol)
    }
}
