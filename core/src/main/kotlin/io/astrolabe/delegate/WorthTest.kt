package io.astrolabe.delegate

import io.astrolabe.Defaults
import io.astrolabe.event.DelegatedCost

/**
 * The §10.1 worth test's cost side (`[O]`, P4.4.5): a deterministic estimate of what delegating [TaskPacket] costs
 * beyond the parent's own work (D-126). It is recorded on `Delegation.Dispatched` as advisory data and never decides a
 * dispatch alone: the model's choice to delegate, the Delegator's limits and the budget reservation still govern.
 */
public object WorthTest {
    /** The expected share of a child's cost spent again on a retry (D-126, `[ESTIMATE]`). */
    public const val RETRY_SHARE: Double = 0.25

    /**
     * [briefTokens] is the child's brief as the estimator counts it; [fixedContextTokens] the fixed context a fresh child
     * compiles again (the dispatching cell's own compiled size is the proxy). The child generation is the rest of the
     * packet's reservation.
     */
    @JvmStatic
    @JvmOverloads
    public fun estimate(kind: ChildKind, packet: TaskPacket, briefTokens: Long, fixedContextTokens: Long, defaults: Defaults = Defaults()): DelegatedCost {
        require(briefTokens >= 0 && fixedContextTokens >= 0) { "token counts are ≥ 0" }
        val duplication = fixedContextTokens + briefTokens
        val generation = (packet.reservedBudget.value - duplication).coerceAtLeast(0)
        val look = defaults.lookBudgetTokens.toLong()
        val run = defaults.runBudgetTokens.toLong()
        val scopes = packet.readScope.size.coerceAtLeast(1).toLong()
        val (tool, interpretation, validation, integration) = when (kind) {
            // A probe reads its scopes; the parent reads its summary and looks at the pointers it relies on (§10.2).
            ChildKind.Probe -> listOf(scopes * look, Probe.MAX_SUMMARY_TOKENS.toLong(), look, 0L)
            // A judge reads the changed paths and may run tests; its verdict is read and bound by the verifier (§8.8).
            ChildKind.Review -> listOf(scopes * look + run, Probe.MAX_SUMMARY_TOKENS.toLong(), 0L, 0L)
            // A writer reads and runs in its scope; its packet is re-verified and integrated by the parent (§10.4).
            ChildKind.Writer -> listOf(scopes * (look + run), 2L * Probe.MAX_SUMMARY_TOKENS, run, run)
        }
        val retries = ((duplication + generation + tool) * RETRY_SHARE).toLong()
        return DelegatedCost(duplication, generation, tool, interpretation, validation, integration, retries)
    }
}
