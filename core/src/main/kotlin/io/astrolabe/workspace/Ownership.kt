package io.astrolabe.workspace

import io.astrolabe.contract.Scope
import io.astrolabe.id.WorkspaceId

/** One increment's claim on the destination: its write scope and whether it changes an interface (§10.4). */
public data class OwnershipClaim @JvmOverloads constructor(
    val increment: String,
    val scope: Scope,
    val interfaceChange: Boolean = false,
) {
    init {
        require(increment.isNotBlank()) { "a claim names its increment" }
    }
}

/** What [Ownership.claim] decided: the claim runs in parallel, or it waits behind [behind]. */
public sealed interface OwnershipAdmission {
    public val claim: OwnershipClaim

    public data class Granted(override val claim: OwnershipClaim) : OwnershipAdmission

    public data class Serialized(override val claim: OwnershipClaim, val behind: String, val reason: String) : OwnershipAdmission
}

/**
 * The ownership map `paths → increment` of one integration [destination] (§10.4). A claim is granted only when
 * [ScopeAlgebra] proves its write scope disjoint from every granted claim's; an overlap or an unproved answer
 * serializes it, and an interface change never runs beside another claim (it needs a `CON`/ADR in the main line
 * first). Lexical: physical aliases stay the path contract's and the scope guard's (D-58).
 */
public class Ownership @JvmOverloads constructor(
    public val destination: WorkspaceId,
    private val limits: ScopeSearchLimits = DEFAULT_LIMITS,
) {
    private val granted = LinkedHashMap<String, OwnershipClaim>()

    /** The granted claims in grant order. */
    public val claims: List<OwnershipClaim> get() = synchronized(granted) { granted.values.toList() }

    public fun claim(claim: OwnershipClaim): OwnershipAdmission = synchronized(granted) {
        require(claim.increment !in granted) { "increment ${claim.increment} already owns a scope" }
        for (other in granted.values) {
            if (claim.interfaceChange || other.interfaceChange) {
                return OwnershipAdmission.Serialized(claim, other.increment, "an interface change never runs in parallel increments; it needs a CON/ADR in the main line first")
            }
            when (val result = ScopeAlgebra.intersection(destination, claim.scope, other.scope, limits).result) {
                ScopeResult.Disjoint -> continue
                is ScopeResult.Overlap -> return OwnershipAdmission.Serialized(claim, other.increment, "write scopes overlap at ${result.witnessPath}")
                is ScopeResult.Unknown -> return OwnershipAdmission.Serialized(claim, other.increment, "disjointness unproved (${result.reason}: ${result.detail})")
            }
        }
        granted[claim.increment] = claim
        OwnershipAdmission.Granted(claim)
    }

    /** Ends [increment]'s ownership; `false` when it held none. */
    public fun release(increment: String): Boolean = synchronized(granted) { granted.remove(increment) != null }

    /** The increment that owns [relative] for writing, or `null`. */
    public fun owner(relative: String): String? = synchronized(granted) {
        granted.values.firstOrNull { it.scope.allowsWrite(relative) }?.increment
    }

    public companion object {
        /** Bounds of one pairwise disjointness proof; an exhausted bound is Unknown and serializes. */
        @JvmField
        public val DEFAULT_LIMITS: ScopeSearchLimits = ScopeSearchLimits(10_000, 20_000, 2_000_000)
    }
}
