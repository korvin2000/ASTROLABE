package io.astrolabe.cell

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.context.ContextAdmission
import io.astrolabe.context.PrecompileTrigger
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Ledger
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.IntentJournal
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.Observations
import io.astrolabe.evidence.Receipts
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.Identities
import io.astrolabe.provider.Effort
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.Profile
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.recover.Diagnoses
import io.astrolabe.register.Register
import io.astrolabe.register.RegisterVersions
import io.astrolabe.telemetry.Accounting
import io.astrolabe.tool.ToolExecutor
import io.astrolabe.tool.TurnCheckpoint
import io.astrolabe.tool.edit.Edit
import io.astrolabe.tool.look.Look
import io.astrolabe.tool.state.StateTool
import io.astrolabe.tool.task.TaskTool
import io.astrolabe.tool.verify.Verify
import io.astrolabe.verify.Checker
import io.astrolabe.verify.Checks
import io.astrolabe.verify.PreexistingLedger
import io.astrolabe.verify.Scheduler
import io.astrolabe.verify.Verifier
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.Preimages
import io.astrolabe.workspace.Stamper
import io.astrolabe.kb.CellKnowledge
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace

/** The model side of a cell: the adapter, the routed profile and the estimator admission is decided with (D-06). */
public class CellModel @JvmOverloads constructor(
    public val adapter: ProviderAdapter,
    public val profile: Profile,
    public val estimator: TokenEstimator,
    public val effort: Effort = Effort.Medium,
    /** Output headroom of every request; the provider's own limit unless the caller narrows it. */
    public val maxOutputTokens: Int = profile.capabilities.outputLimitTokens,
    /** Whether the caller narrowed [maxOutputTokens] below the profile limit; [rebind] keeps a narrowing. */
    public val narrowedOutput: Boolean = maxOutputTokens != profile.capabilities.outputLimitTokens,
    /**
     * The host chose [effort] itself (C14): the attempt's balance profile never steps it. `false` — the default — is effort
     * "by approach": the profile's step for the model's price class moves [effort] (`BalanceProfiles.effort`). [rebind] keeps it.
     * A routing row with an effort of its own (the function table, e.g. `ReviewCritical` at high) still runs at that row's
     * effort, as it always did: the flag only outranks the profile's step.
     */
    public val effortExplicit: Boolean = false,
) {
    init {
        require(maxOutputTokens in 1..profile.capabilities.outputLimitTokens) {
            "maxOutputTokens must be within 1..${profile.capabilities.outputLimitTokens}, got $maxOutputTokens"
        }
    }

    /**
     * The same adapter bound to a routed profile (D-327): that profile's estimator from [estimators] and its own output
     * headroom, or the caller's narrowing capped by the routed limit.
     */
    public fun rebind(routed: Profile, effort: Effort, estimators: EstimatorFactory): CellModel {
        val limit = routed.capabilities.outputLimitTokens
        val headroom = if (narrowedOutput) minOf(maxOutputTokens, limit) else limit
        return CellModel(adapter, routed, estimators.estimatorFor(routed), effort, headroom, narrowedOutput, effortExplicit)
    }
}

/**
 * The seven executors of one cell (P1.6.3–P1.6.10). The families the loop has to reach into are typed —
 * `state` for the register and `blocked`, `look` for the atlas it refreshes, `verify` for the touched set,
 * `edit` for the increment scope, `task` for pinned questions — the rest are plain executors. A family
 * that is absent is not dispatched: its calls get an explicit `NotExecuted` disposition.
 */
public class CellTools @JvmOverloads constructor(
    public val state: StateTool,
    public val look: Look? = null,
    public val edit: Edit? = null,
    public val run: ToolExecutor? = null,
    public val verify: Verify? = null,
    public val task: TaskTool? = null,
    public val kb: ToolExecutor? = null,
)

/** The workspace side of a cell: one registry, one coherence, the stamper, the check registry and the atlas. */
public class CellWorkspace @JvmOverloads constructor(
    public val workspace: Workspace,
    public val registry: VersionRegistry,
    /**
     * The registry's single subscriber, **without horizons registered**: the loop registers the Workset, the
     * register holder and the check registry in that order (§4.4) and unsubscribes them when the cell ends.
     */
    public val coherence: Coherence,
    public val stamper: Stamper,
    public val workset: Workset,
    public val checks: Checks,
    public val scheduler: Scheduler,
    public val atlas: Atlas,
    public val checker: Checker? = null,
)

/** The evidence stores a cell writes to or reads for validation; each is the seam its P1.4 task declared. */
public class CellEvidence @JvmOverloads constructor(
    public val journal: Journal,
    public val observations: Observations,
    public val aliases: Aliases,
    public val receipts: Receipts,
    public val intents: IntentJournal,
    public val registerVersions: RegisterVersions,
    public val checkpoints: Checkpoints,
    public val preimages: Preimages? = null,
)

/**
 * The compiled context of one cell run — the `ctx` of §3.7 — assembled by the caller (the controller from
 * P1.9; a test directly). The loop owns nothing here: it renders, dispatches, reconciles and persists through
 * these components and hands back a [CellExit].
 */
public class CellContext @JvmOverloads constructor(
    /** Work, attempt and the cell's own context id; the candidate is stamped by the loop. */
    public val ids: Identities,
    public val role: Role,
    public val contracts: Contracts,
    public val model: CellModel,
    public val tools: CellTools,
    public val workspace: CellWorkspace,
    public val evidence: CellEvidence,
    /** The `[R]` text its compiler produced (P1.3.2); byte-stable for the cell. */
    public val prime: String,
    /** The requirement ledger the digest renders; the committed one when the controller holds it. */
    public val ledger: Ledger? = null,
    /** The pre-existing-failure ledger for `[K]` (P1.7.5), when a baseline ran. */
    public val preexisting: PreexistingLedger? = null,
    public val config: Config = Config(),
    public val hostSets: Map<String, CapabilitySet> = emptyMap(),
    /** The shadow-ref checkpoint before a mutating turn's edit batch (§5.4); the controller numbers it. */
    public val turnCheckpoint: TurnCheckpoint? = null,
    /** Pinned main-line user messages beyond the contract's requests (invariant 1). */
    public val pinned: List<String> = emptyList(),
    /** The controller's execution generation (§13.1), carried into the packet and checked before acceptance. */
    public val generation: ExecutionGeneration = ExecutionGeneration.INITIAL,
    /** Per-call accounting (P1.11.2): every response, and every call whose usage never arrived, is priced and stored. */
    public val accounting: Accounting? = null,
    /** The id of the context manifest this cell was compiled under (§6.5, P2.3.2); `Cell.Ended` links it. */
    public val manifest: String? = null,
    /** The hard admission check (§6.1, P2.3.3); one per lineage, created by the cell when absent. */
    public val admission: ContextAdmission? = null,
    /** The compiled `[K]` sections after the slice (§6.1: carry-forward, seeds, notes); the slice re-renders every turn. */
    public val sections: List<KSection> = emptyList(),
    /** Boundary pre-compilation (§6.6, `precompile` flag): told at each completion proposal which checks still run. */
    public val precompile: PrecompileTrigger? = null,
    /** Focus notes and register citations (§6.3, P4.1.3); absent on the empty base. */
    public val knowledge: CellKnowledge? = null,
    /** Repair-helper diagnosis lines addressed to this cell (§13.2): rendered in every later `[A]` (P4.6.3). */
    public val diagnoses: Diagnoses? = null,
    public val noteHorizon: io.astrolabe.kb.NoteHorizon? = null,
    public val completionEvidence: (suspend (List<io.astrolabe.verify.TestIntegrityFlag>) -> CompletionEvidence)? = null,
    /** The controller runs this cell to rework the increment on a decider's `rework` answer (D-340); set by the controller, never inferred from text. */
    public val rework: Boolean = false,
    /** P8.C.10: red receipts accepted increments acknowledged with an `Open` item; the exit gate honours them as the verifier does. */
    public val acknowledged: List<String> = emptyList(),
    /**
     * A-D.6: the loop that runs this cell has no continuation of its own (`runS0`), so a direct cell whose turn budget is
     * spent with work done in the epoch hands off instead of ending `TurnBudget`. The cell never reads the shape.
     */
    public val turnBudgetHandoff: Boolean = false,
    /**
     * A-D.6: the test-integrity flags of the cell this epoch continues; the cell starts its flag set with them, so a test
     * weakened before the handoff binds the completion after it. Empty for every other cell.
     */
    public val carriedFlags: List<io.astrolabe.verify.TestIntegrityFlag> = emptyList(),
    /**
     * A-D.6: the impact-nudge ledger the cell keeps — an epoch's starts with its predecessor's unresolved public nudges, and
     * the controller reads it back at a handoff. `null`: a fresh ledger of the cell's own.
     */
    public val impact: ImpactNudges? = null,
) {
    init {
        require(ids.context != null) { "a cell runs under its own context id" }
    }
}

/** A refusal of dispatch authority (§3.7 `enforce_dispatch_authority`): the cell ends before any spend. */
public data class DispatchRefusal(val reason: String, val cancelled: Boolean = false)

/**
 * Dispatch authority as the controller sees it (§3.7): cancellation, a superseded execution generation, an
 * expired lease. P1.9 registers the real one; the loop asks before every turn and never spends past a refusal.
 */
public fun interface DispatchAuthority {
    public fun check(turn: Int): DispatchRefusal?

    public companion object {
        /** No controller: nothing refuses dispatch. */
        @JvmField
        public val NONE: DispatchAuthority = DispatchAuthority { null }
    }
}

/** The model's no-call output as the completion seam receives it: text and evidence, never a status word it chose. */
public data class RoleOutput(
    val turn: Int,
    val text: String,
    val register: Register,
    /** Receipt ids that certify the increment's `run:` items on the tree now. */
    val certified: List<String>,
    /** Completion proposals already refused in this cell. */
    val refusals: Int,
    /** The packet the runtime would hand back if this proposal is accepted: its status is `done`, a proposal. */
    val packet: ResultPacket,
    /** Resolved evidence aliases actually delivered by this cell. */
    val shownAliases: Set<String> = emptySet(),
    /** The implementing proposal's acceptance as the loop resolved it (D-337); `null` for other packet kinds. */
    val resolved: io.astrolabe.verify.Resolved? = null,
    /**
     * A-D.5: a conditional proposal — a direct `task(finish)` in a turn whose edits, runs and verifies all passed. It is
     * never counted: it never yields [CompletionDecision.CannotProgress] and never takes the last round of D-341.
     */
    val conditional: Boolean = false,
) {
    /** The v1.0 constructor: a plain proposal. Kept for Java callers. */
    public constructor(
        turn: Int, text: String, register: Register, certified: List<String>, refusals: Int, packet: ResultPacket,
        shownAliases: Set<String>, resolved: io.astrolabe.verify.Resolved?,
    ) : this(turn, text, register, certified, refusals, packet, shownAliases, resolved, false)
}

/** What the completion seam decided (§3.7 `assess_role_completion`). */
public sealed interface CompletionDecision {
    public data class Accepted(val evidenceRefs: List<String>) : CompletionDecision

    /** Gaps recorded; the cell continues with them in `[A]`. */
    public data class Continue(val gaps: List<String>) : CompletionDecision

    /** The gaps cannot be closed by this cell: an explicit incomplete exit (§3.7 `cannot_progress`). */
    public data class CannotProgress(val gaps: List<String>) : CompletionDecision

    /**
     * The work is done and its acceptance needs an authority's decision (D-339): the cell ends without another turn and
     * the controller keeps the proposal as a pending completion. Neither a refusal nor a partial.
     */
    public data class Defer(val gaps: List<String>, val code: io.astrolabe.verify.StopCode) : CompletionDecision
}

/**
 * The named seam for role completion: `validate_role_output` + `assess_role_completion` of §3.7. The cell
 * resolves it per role ([forRole]): the implementing packet goes through the P1.7.7 exit gate as the S0 gate
 * set evaluated it, refused at most [Verifier.maxFinalizations] times before gap-directed recovery; every
 * other packet kind needs its declared validator (P2/P4), and without one the cell cannot complete — it never
 * falls back to the implementing gate, so a reviewer is never asked to review its own verdict. `done` stays a
 * proposal: the verifier's acceptance belongs to the controller.
 */
public fun interface RoleCompletion {
    public suspend fun assess(output: RoleOutput, gates: GateReport): CompletionDecision

    public companion object {
        @JvmStatic
        @JvmOverloads
        public fun exitGate(maxFinalizations: Int = Verifier().maxFinalizations): RoleCompletion {
            require(maxFinalizations >= 1) { "maxFinalizations must be ≥ 1" }
            return RoleCompletion { output, gates ->
                val resolved = output.resolved
                // A-D.5 counter rule 1: a conditional proposal is never the last one.
                val last = !output.conditional && output.refusals + 1 >= maxFinalizations
                if (resolved == null) {
                    val exit = gates.rejections.firstOrNull { it.key.gate == Gates.EXIT }
                    return@RoleCompletion when {
                        exit == null -> CompletionDecision.Accepted(output.certified)
                        last -> CompletionDecision.CannotProgress(exit.details)
                        else -> CompletionDecision.Continue(exit.details)
                    }
                }
                // I4: one rework round per cell; on the last one a reviewer's rejection goes to the authority (D-341).
                val final = if (last && resolved.resolution == io.astrolabe.verify.Resolution.Rework) resolved.spent() else resolved
                when (final.resolution) {
                    io.astrolabe.verify.Resolution.Complete -> CompletionDecision.Accepted((output.certified + final.evidenceRefs).distinct())
                    io.astrolabe.verify.Resolution.Await -> CompletionDecision.Defer(final.missing, checkNotNull(final.code))
                    io.astrolabe.verify.Resolution.Rework -> if (last) CompletionDecision.CannotProgress(final.missing) else CompletionDecision.Continue(final.missing)
                }
            }
        }

        /**
         * `assess_role_completion` for [role]: a registered validator for its packet kind, else the exit gate for
         * the implementing `Result` packet, else an explicit incomplete exit naming the missing validator.
         */
        @JvmStatic
        @JvmOverloads
        public fun forRole(
            role: Role,
            validators: Map<PacketKind, RoleCompletion> = emptyMap(),
            maxFinalizations: Int = Verifier().maxFinalizations,
        ): RoleCompletion = validators[role.packetKind] ?: when (role.packetKind) {
            PacketKind.Result -> exitGate(maxFinalizations)
            else -> RoleCompletion { _, _ ->
                CompletionDecision.CannotProgress(listOf("no validator bound for the ${role.packetKind.name} packet of role '${role.name}' (declared: ${RoleTexts.validators[role.packetKind] ?: "none"}; its dispatcher binds it)"))
            }
        }
    }
}

/**
 * Independent evidence obtained before the implementing completion gate (D-337): the reviewers' [verdicts] on
 * `check:`/`review:` items at the current candidate — approvals and rejections alike — with [unavailable] saying why an
 * item has none, the resolved test-integrity [flags], the current acceptance [decision] and whether a `rework`
 * decision was already spent on this candidate ([reworkSpent], D-340).
 */
public data class CompletionEvidence(
    val verdicts: Map<String, io.astrolabe.verify.Verdict> = emptyMap(),
    val unavailable: Map<String, String> = emptyMap(),
    val flags: List<io.astrolabe.verify.TestIntegrityFlag> = emptyList(),
    val decision: io.astrolabe.verify.DecisionRecord? = null,
    val reworkSpent: Boolean = false,
)
