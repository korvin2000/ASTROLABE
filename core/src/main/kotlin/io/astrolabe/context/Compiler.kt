package io.astrolabe.context

import io.astrolabe.Config
import io.astrolabe.auth.Ceiling
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CompiledK
import io.astrolabe.cell.ContextPart
import io.astrolabe.cell.KSection
import io.astrolabe.cell.Layout
import io.astrolabe.cell.Role
import io.astrolabe.cell.Transcript
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.id.FileVersion
import io.astrolabe.kb.Note
import io.astrolabe.kb.NoteKind
import io.astrolabe.kb.NoteStatus
import io.astrolabe.kb.Skill
import io.astrolabe.kb.SkillConflict
import io.astrolabe.kb.SkillView
import io.astrolabe.kb.SkillViews
import io.astrolabe.provider.Message
import io.astrolabe.provider.Profile
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.verify.PreexistingLedger
import io.astrolabe.workspace.PathPattern
import java.math.BigDecimal

/** What [Compiler.compile] hands the controller (§6.1): a context to dispatch, or the reason none can be built. */
public sealed interface Compiled {
    /** The budget arithmetic of the attempt, kept for the manifest whatever the result. */
    public val selection: ContextSelection

    public data class Ready(val k: CompiledK, override val selection: ContextSelection) : Compiled

    /** `NEEDS_RESCOPING_OR_LARGER_PROFILE`: the mandatory part does not fit; nothing mandatory is dropped. */
    public data class NeedsRescoping(override val selection: ContextSelection, val reason: String) : Compiled

    /** `NEEDS_MORE_EVIDENCE`: required coverage is unmet — an acceptance id without its definition (IX-11). */
    public data class NeedsEvidence(override val selection: ContextSelection, val missing: List<String>) : Compiled
}

/**
 * What the full compile consumes beyond the contract (§6.1, P2.3.1). Everything defaults to absent, which is the S0
 * form: the mandatory contract slice alone.
 */
public data class CompileInputs @JvmOverloads constructor(
    /** `index/contracts.md`: mandatory; referenced where `[R]` already carries it, never duplicated into `[K]`. */
    val contractsIndex: String? = null,
    /** The notes this compile carries: the `Injection` selection (P4.1.3), `CON`/`ADR` in the write scope mandatory. */
    val notes: List<Note> = emptyList(),
    /** The carry-forward of the previous cell: mandatory when present. */
    val carry: Carry? = null,
    /** Seeds rendered at their hash, one block per entry (≤ 4K by [CarryForward]). */
    val seeds: SeedRender? = null,
    /** The plan cell's calibration block (P2.6.4), shown only to roles whose context view includes it. */
    val calibration: String? = null,
    /** The approved rules text that `[R]` must carry (§14.3); `null` when no rules file is approved. */
    val rules: String? = null,
    /** Current file versions, to recheck seeds at compile time; `null` skips the recheck. */
    val currentVersion: ((String) -> FileVersion?)? = null,
    /** Triggered, resolved skills (P4.3.1): each role view's core joins the mandatory set, its optional modules compete. */
    val skills: List<Skill> = emptyList(),
    /** Overlaps [skills] resolution reported (§12.2, D-112): rendered into `[K]` beside the skills, never merged silently. */
    val skillConflicts: List<SkillConflict> = emptyList(),
)

/**
 * The context compiler (§6.1). `mandatory` = the contract slice with complete acceptance definitions and the
 * pre-existing ledger, the contracts index, CON/ADR notes anchored in the increment's write scope and the
 * carry-forward; optional units follow in the §6.1 order — affected contracts, carry-forward, seeds, local
 * implementation, lessons/pitfalls, skills, background — chosen by the D-18/D-55 greedy cover within
 * `C·α − |S| − |R| − |T| − reserves`. A mandatory part that does not fit is `NEEDS_RESCOPING_OR_LARGER_PROFILE`,
 * never a silent drop; a coverage gap in the rendered projection is `NEEDS_MORE_EVIDENCE`.
 */
public class Compiler(
    private val estimator: TokenEstimator,
    private val config: Config = Config(),
) {
    private val skillViews = SkillViews(estimator)

    @JvmOverloads
    public fun compile(
        increment: Increment,
        contract: Contract,
        profile: Profile,
        role: Role,
        prime: String,
        preexisting: PreexistingLedger? = null,
        pinned: List<String> = emptyList(),
        maxOutputTokens: Int = profile.capabilities.outputLimitTokens,
        inputs: CompileInputs = CompileInputs(),
    ): Compiled {
        val slice = ContractSlice.forIncrement(contract, increment)
        val mask = role.effectiveOps(contract.shape, Ceiling.of(contract.authorization, config.executionMode))
        val transcript = Transcript(contract.requests.map { it.text } + pinned)
        val fixed = Layout.render(role, mask, config.executionMode, prime, CompiledK(slice, preexisting), transcript)
        val defaults = config.defaults
        val reserves = maxOutputTokens.toLong() + defaults.anchorMaxTokens + maxOf(defaults.lookBudgetTokens, defaults.runBudgetTokens)
        val budget = ContextBudget(
            profileTokens = Tokens(profile.capabilities.contextLimitTokens.toLong()),
            alpha = BigDecimal.valueOf(defaults.alpha),
            system = cost(fixed, SegmentKind.S),
            repository = cost(fixed, SegmentKind.R),
            pinnedHistory = cost(fixed, SegmentKind.T),
            retainedProtocol = ContextCost(Tokens(0), SOURCE, estimated = false),
            // A fresh lineage: no effective history yet, which is known, not unknown (D-06).
            effectiveHistory = ContextCost(Tokens(0), SOURCE, estimated = false),
            reserves = ContextCost(Tokens(reserves), "reserve(output + [A]_max + next observation)", estimated = false),
        )
        val sections = candidates(increment, contract, role, prime, inputs)
        val units = listOf(ContextUnit(MANDATORY, cost(fixed, SegmentKind.K), mandatory = true)) + sections.map { it.unit }
        val selection = ContextCover.select(units, budget)
        val chosen = sections.filter { it.unit.id in selection.selectedIds }.sortedWith(compareBy({ !it.unit.mandatory }, { it.unit.priority }, { it.order }))
        val k = CompiledK(slice, preexisting, chosen.filter { it.section != null }.map { it.section!! })
        val missing = coverage(contract, increment, Layout.compiled(k), prime, transcript.pinned, inputs)
        return when {
            missing.isNotEmpty() -> Compiled.NeedsEvidence(selection, missing)
            selection.status == ContextSelectionStatus.Fit -> Compiled.Ready(k, selection)
            else -> Compiled.NeedsRescoping(
                selection,
                "mandatory [K] of ${increment.id} needs ${selection.arithmetic.selectedTokens} tokens; ${selection.arithmetic.availableTokens} remain of ${selection.arithmetic.limitTokens} on ${profile.id} — split the increment or use a larger profile",
            )
        }
    }

    /**
     * The coverage assertion of §6.1 over a rendered projection: every constraint and acceptance definition in `[K]`,
     * every pinned request (the user's amendments) in `[T]`, every CON note anchored in the write scope in `[K]` or
     * `[R]`, and the approved rules in `[R]`. Returns what is missing; the rebuild validation (P2.5.3) reuses it.
     */
    public fun coverage(contract: Contract, increment: Increment, k: String, repository: String, pinned: List<String>, inputs: CompileInputs): List<String> {
        val missing = ArrayList<String>()
        missing += ContractSlice.forIncrement(contract, increment).coverage().missingAcceptance.map { "acceptance $it" }
        for (c in contract.constraints) if ("${c.id} ${c.text}" !in k) missing += "constraint ${c.id}"
        for (a in ContractSlice.forIncrement(contract, increment).acceptance) if ("${a.id} (" !in k) missing += "acceptance ${a.id}"
        for (request in contract.requests) if (request.text !in pinned) missing += "request ${request.id}"
        for (note in anchored(increment, inputs.notes)) if (note.line !in k && note.line !in repository) missing += "note ${note.id}"
        inputs.rules?.let { if (it !in repository) missing += "rules" }
        return missing.distinct()
    }

    /** The same assertion over an already compiled `[K]`: what a pre-compiled context (§6.6) would miss now. */
    public fun coverage(contract: Contract, increment: Increment, k: CompiledK, repository: String, pinned: List<String>, inputs: CompileInputs): List<String> =
        coverage(contract, increment, Layout.compiled(k), repository, contract.requests.map { it.text } + pinned, inputs)

    private class Candidate(val unit: ContextUnit, val section: KSection?, val order: Int)

    private fun candidates(increment: Increment, contract: Contract, role: Role, prime: String, inputs: CompileInputs): List<Candidate> {
        val out = ArrayList<Candidate>()
        fun add(id: String, title: String, text: String, mandatory: Boolean, priority: ContextPriority, placement: ContextPlacement = ContextPlacement.K) {
            val section = if (placement == ContextPlacement.K) KSection(id, title, text) else null
            val tokens = estimator.estimate(if (section != null) "## $title\n${text.trimEnd()}\n" else text).upperBoundTokens
            val unit = ContextUnit(ContextUnitId(id), ContextCost(Tokens(tokens), "${estimator.id}/${estimator.version}", estimated = true),
                mandatory = mandatory, priority = priority, gain = if (mandatory) 0 else 1, placement = placement)
            out += Candidate(unit, section, out.size)
        }
        inputs.contractsIndex?.takeIf { it.isNotBlank() }?.let { index ->
            if (index in prime) add("contracts-index", "contracts", index, mandatory = true, ContextPriority.AffectedContracts, ContextPlacement.Repository)
            else add("contracts-index", "Contracts (index/contracts.md)", index, mandatory = true, ContextPriority.AffectedContracts)
        }
        val anchored = anchored(increment, inputs.notes)
        for (note in anchored) add("note.${note.id}", "${note.kind.name} ${note.id}", note.line + "\n" + note.body, mandatory = true, ContextPriority.AffectedContracts)
        inputs.carry?.let { add("carry-forward", "Carry-forward", it.render(), mandatory = true, ContextPriority.CarryForward) }
        inputs.seeds?.let { seeds ->
            seeds.shown.zip(seeds.blocks).forEachIndexed { i, (entry, block) ->
                val current = inputs.currentVersion?.invoke(entry.path)
                if (inputs.currentVersion == null || current == entry.version) {
                    add("seed-$i", "Seed ${entry.path}:${entry.range}", block, mandatory = false, ContextPriority.Seeds)
                }
            }
        }
        // P4.1.3: the ranker (Injection) chose `inputs.notes`; the role's note scope and the CAL view still bound them.
        val calibrationNote = ContextPart.CalibrationPrior in role.contextView && inputs.notes.any { it.kind == NoteKind.CAL && it.status == NoteStatus.Admitted }
        if (ContextPart.Notes in role.contextView) {
            val slice = inputs.notes.filter { n ->
                n.status == NoteStatus.Admitted && n !in anchored && n.kind != NoteKind.STATUS &&
                    (n.kind.name in role.noteScope || (n.scope == "global" && "GLOBAL" in role.noteScope) || (n.kind == NoteKind.CAL && calibrationNote))
            }.sortedBy { it.id }
            for (note in slice) {
                val priority = when (note.kind) {
                    NoteKind.CON -> ContextPriority.AffectedContracts
                    NoteKind.LES, NoteKind.PIT -> ContextPriority.Lessons
                    else -> ContextPriority.Background
                }
                add("note.${note.id}", "${note.kind.name} ${note.id}", note.line + "\n" + note.body, mandatory = false, priority)
            }
        }
        // §6.1, F06: a skill's prerequisites, invariants and mandatory modules are mandatory, charged to the total budget.
        val allowedSkill = { id: String -> "*" in role.skillFilter || id in role.skillFilter }
        for (skill in inputs.skills.filter { allowedSkill(it.id) }.distinctBy { it.id }.sortedBy { it.id }) {
            val view = skillViews.view(skill, role.name)
            add("skill.${skill.id}", "SKILL ${skill.id}@v${skill.version}", view.core, mandatory = true, ContextPriority.Skills)
            for (m in view.optional) add("skill.${skill.id}.${m.id}", "SKILL ${skill.id} module ${m.id}", SkillView.moduleText(m), mandatory = false, ContextPriority.Skills)
        }
        // D-112: a conflict between skills this role may see is part of the procedure it follows, so it is mandatory.
        inputs.skillConflicts.filter { allowedSkill(it.first) || allowedSkill(it.second) }.map { it.line }.distinct().sorted().takeIf { it.isNotEmpty() }?.let { lines ->
            add("skill.conflicts", "SKILL conflicts", lines.joinToString("\n"), mandatory = true, ContextPriority.Skills)
        }
        // D-42: an admitted CAL note replaces the P2 statistics block; never both.
        if (ContextPart.CalibrationPrior in role.contextView && !calibrationNote) {
            inputs.calibration?.takeIf { it.isNotBlank() }?.let { add("calibration", "Calibration prior", it, mandatory = false, ContextPriority.Background) }
        }
        return out
    }

    /** CON and ADR notes whose anchors fall in the increment's write scope: mandatory (§6.1). */
    private fun anchored(increment: Increment, notes: List<Note>): List<Note> = notes.filter { n ->
        n.status == NoteStatus.Admitted && (n.kind == NoteKind.CON || n.kind == NoteKind.ADR) &&
            n.anchors.any { a -> increment.writeScope.any { PathPattern.matches(it, a.path) } }
    }.sortedBy { it.id }

    private fun cost(segments: List<Segment>, kind: SegmentKind): ContextCost {
        val texts = segments.filter { it.kind == kind }.flatMap { it.items }.filterIsInstance<Message>().map { it.text }
        val tokens = texts.sumOf { estimator.estimate(it).upperBoundTokens }
        return ContextCost(Tokens(tokens), "${estimator.id}/${estimator.version}", estimated = true)
    }

    public companion object {
        /** The mandatory contract-slice `[K]` unit. */
        @JvmField
        public val MANDATORY: ContextUnitId = ContextUnitId("k-mandatory")

        private const val SOURCE: String = "compiler"
    }
}
