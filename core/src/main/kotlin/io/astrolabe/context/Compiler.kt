package io.astrolabe.context

import io.astrolabe.Config
import io.astrolabe.auth.Ceiling
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CompiledK
import io.astrolabe.cell.Layout
import io.astrolabe.cell.Role
import io.astrolabe.cell.Transcript
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.provider.Message
import io.astrolabe.provider.Profile
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.verify.PreexistingLedger
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
 * The context compiler (§6.1) in its S0 form: the mandatory `[K]` only — the contract slice with complete
 * acceptance definitions, the approved rules in `[R]`, the pre-existing-failure ledger and the pinned requests —
 * sized against the profile's actual window with the reserves of the next request. Seeds, notes, skills, focus
 * and carry-forward join this same class in P2.3 with their producers.
 */
public class Compiler(
    private val estimator: TokenEstimator,
    private val config: Config = Config(),
) {
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
    ): Compiled {
        val slice = ContractSlice.forIncrement(contract, increment)
        val k = CompiledK(slice, preexisting)
        val mask = role.effectiveOps(contract.shape, Ceiling.of(contract.authorization, config.executionMode))
        val segments = Layout.render(role, mask, config.executionMode, prime, k, Transcript(contract.requests.map { it.text } + pinned))
        val defaults = config.defaults
        val reserves = maxOutputTokens.toLong() + defaults.anchorMaxTokens + maxOf(defaults.lookBudgetTokens, defaults.runBudgetTokens)
        val budget = ContextBudget(
            profileTokens = Tokens(profile.capabilities.contextLimitTokens.toLong()),
            alpha = BigDecimal.valueOf(defaults.alpha),
            system = cost(segments, SegmentKind.S),
            repository = cost(segments, SegmentKind.R),
            pinnedHistory = cost(segments, SegmentKind.T),
            retainedProtocol = ContextCost(Tokens(0), SOURCE, estimated = false),
            // A fresh lineage: no effective history yet, which is known, not unknown (D-06).
            effectiveHistory = ContextCost(Tokens(0), SOURCE, estimated = false),
            reserves = ContextCost(Tokens(reserves), "reserve(output + [A]_max + next observation)", estimated = false),
        )
        val units = listOf(ContextUnit(MANDATORY, cost(segments, SegmentKind.K), mandatory = true))
        val selection = ContextCover.select(units, budget)
        val coverage = slice.coverage()
        return when {
            !coverage.complete -> Compiled.NeedsEvidence(selection, coverage.missingAcceptance)
            selection.status == ContextSelectionStatus.Fit -> Compiled.Ready(k, selection)
            else -> Compiled.NeedsRescoping(
                selection,
                "mandatory [K] of ${increment.id} needs ${selection.arithmetic.selectedTokens} tokens; ${selection.arithmetic.availableTokens} remain of ${selection.arithmetic.limitTokens} on ${profile.id} — split the increment or use a larger profile",
            )
        }
    }

    private fun cost(segments: List<Segment>, kind: SegmentKind): ContextCost {
        val texts = segments.filter { it.kind == kind }.flatMap { it.items }.filterIsInstance<Message>().map { it.text }
        val tokens = texts.sumOf { estimator.estimate(it).upperBoundTokens }
        return ContextCost(Tokens(tokens), "${estimator.id}/${estimator.version}", estimated = true)
    }

    public companion object {
        /** The one mandatory `[K]` unit of the S0 form. */
        @JvmField
        public val MANDATORY: ContextUnitId = ContextUnitId("k-mandatory")

        private const val SOURCE: String = "compiler-s0"
    }
}
