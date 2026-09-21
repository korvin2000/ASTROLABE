package io.astrolabe.cell

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Ledger
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.IntentJournal
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.Observations
import io.astrolabe.evidence.Receipts
import io.astrolabe.id.Identities
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Profile
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.register.Register
import io.astrolabe.register.RegisterVersions
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
) {
    init {
        require(maxOutputTokens in 1..profile.capabilities.outputLimitTokens) {
            "maxOutputTokens must be within 1..${profile.capabilities.outputLimitTokens}, got $maxOutputTokens"
        }
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
)

/** What the completion seam decided (§3.7 `assess_role_completion`). */
public sealed interface CompletionDecision {
    public data class Accepted(val evidenceRefs: List<String>) : CompletionDecision

    /** Gaps recorded; the cell continues with them in `[A]`. */
    public data class Continue(val gaps: List<String>) : CompletionDecision

    /** The gaps cannot be closed by this cell: an explicit incomplete exit (§3.7 `cannot_progress`). */
    public data class CannotProgress(val gaps: List<String>) : CompletionDecision
}

/**
 * The named seam for role completion: `validate_role_output` + `assess_role_completion` of §3.7. P1.8.8
 * builds the `ResultPacket` and `Completion.assess` behind it; until then [exitGate] routes the implementing
 * role's no-call turn through the P1.7.7 exit gate as the S0 gate set evaluates it, and refuses more than
 * [Verifier.maxFinalizations] times before directing recovery. `done` stays a proposal: the verifier's
 * acceptance belongs to the controller.
 */
public fun interface RoleCompletion {
    public suspend fun assess(output: RoleOutput, gates: GateReport): CompletionDecision

    public companion object {
        @JvmStatic
        @JvmOverloads
        public fun exitGate(maxFinalizations: Int = Verifier().maxFinalizations): RoleCompletion {
            require(maxFinalizations >= 1) { "maxFinalizations must be ≥ 1" }
            return RoleCompletion { output, gates ->
                val exit = gates.rejections.firstOrNull { it.key.gate == Gates.EXIT }
                when {
                    exit == null -> CompletionDecision.Accepted(output.certified)
                    output.refusals + 1 >= maxFinalizations -> CompletionDecision.CannotProgress(exit.details)
                    else -> CompletionDecision.Continue(exit.details)
                }
            }
        }
    }
}

