package io.astrolabe.verify

import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathPattern
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.RejectionReason
import io.astrolabe.workspace.Workspace

public enum class ScopeRefusalKind {
    /** A D-class target: protected by the path contract (real path, D-47) or by the contract's protected list. */
    Protected,

    /** Inside the repository but outside the committed write scope; an approved, committed amendment is the only way in. */
    OutsideContract,

    /** The path contract refused the spelling (traversal, absolute, link ancestor, illegal character, …). */
    PathRejected,
}

public data class ScopeRefusal(val path: String, val kind: ScopeRefusalKind, val detail: String)

/** The verdict of the scope guard for one edit batch; [contractVersion] is the committed version it was checked against. */
public sealed interface ScopeVerdict {
    public val contractVersion: Int

    /**
     * Every path may be written. [paths] are the resolved workspace-relative identities the edit must use;
     * [outsideIncrement] are inside the contract but outside the increment's write scope (§8.6: warning
     * once, justification on repeat — the cell keeps that count).
     */
    public data class Allowed(
        override val contractVersion: Int,
        val paths: List<String>,
        val outsideIncrement: List<String> = emptyList(),
    ) : ScopeVerdict

    /** At least one path is refused; nothing in the batch is written (preflight is all-or-nothing, §9.1). */
    public data class Refused(override val contractVersion: Int, val refusals: List<ScopeRefusal>) : ScopeVerdict
}

/**
 * The baseline scope guard (§8.6, TODO P1.7.8, I-02): every built-in edit path must lie inside the
 * **committed** contract's write scope and outside its protected paths, and must pass the workspace path
 * contract with a mutation intent, which refuses protected targets by their real path (D-47).
 *
 * The guard reads only the contract it is handed. Proposals in `amendmentsPending` are never consulted: an
 * expansion of scope takes effect when an authorized amendment has been applied and committed as a new
 * contract version, and the caller re-validates against that version before dispatch. Already-authorized
 * CI, lock-file or migration work is therefore expressed as a committed contract whose scope names those
 * paths — and the workspace's own protected list must be bound to that contract at campaign open (P1.9.2),
 * or the path contract keeps refusing what the contract allows (fail closed).
 */
public class ScopeGuard(private val workspace: Workspace) {

    public fun check(paths: Collection<String>, contract: Contract, increment: Increment? = null): ScopeVerdict {
        val allowed = ArrayList<String>()
        val outsideIncrement = ArrayList<String>()
        val refusals = ArrayList<ScopeRefusal>()
        for (path in LinkedHashSet(paths)) {
            when (val resolved = workspace.resolve(path, Intent.Mutate)) {
                is PathResolution.Rejected -> refusals += ScopeRefusal(
                    path,
                    if (resolved.reason == RejectionReason.Protected) ScopeRefusalKind.Protected else ScopeRefusalKind.PathRejected,
                    resolved.detail,
                )
                is PathResolution.Resolved -> {
                    val relative = resolved.relative
                    when {
                        contract.scope.protects(relative) ->
                            refusals += ScopeRefusal(path, ScopeRefusalKind.Protected, "'$relative' is protected by contract v${contract.version}")
                        !contract.scope.covers(relative) ->
                            refusals += ScopeRefusal(path, ScopeRefusalKind.OutsideContract, "'$relative' is outside the write scope of contract v${contract.version}: ${contract.scope.writePaths}")
                        else -> {
                            allowed += relative
                            if (increment != null && increment.writeScope.none { PathPattern.matches(it, relative) }) outsideIncrement += relative
                        }
                    }
                }
            }
        }
        return if (refusals.isEmpty()) {
            ScopeVerdict.Allowed(contract.version, allowed, outsideIncrement)
        } else {
            ScopeVerdict.Refused(contract.version, refusals)
        }
    }
}
