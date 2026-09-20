package io.astrolabe.atlas

import io.astrolabe.id.WorkspaceId
import java.math.BigInteger
import java.util.Collections

/** Null package means the whole workspace as a suite, or unknown package on a file. */
public data class ImpactScope(val workspace: WorkspaceId, val packageId: String?) {
    init { packageId?.let(::impactLabel) }
}

/** Path is canonical, case-sensitive and workspace-relative; package identity is never inferred. */
public data class ImpactFile(val scope: ImpactScope, val path: String) {
    init {
        require(path.isNotEmpty() && path == normalizeRelative(path) && ':' !in path)
        require(path.none { it.isISOControl() })
    }
}

/** Directed importer -> dependency; blast traverses this edge in reverse. */
public data class ImpactImport(val importer: ImpactFile, val dependency: ImpactFile)
public data class ImpactDependency(val importer: ImpactFile, val description: String) {
    init { impactLabel(description) }
}

/** Supplied topology and coverage attestation, not the runtime ImportGraph or a reuse proof. */
public class ImpactGraph(
    public val version: String,
    public val provenance: String,
    files: Set<ImpactFile>,
    imports: Set<ImpactImport>,
    public val tier: IndexTier,
    public val complete: Boolean,
    coverage: Set<ImpactScope>,
    unresolved: Set<ImpactDependency>,
) {
    public val files: Set<ImpactFile> = impactFiles(files)
    public val imports: Set<ImpactImport> = impactSet(imports.sortedWith(
        compareBy<ImpactImport, ImpactFile>(impactFileOrder) { it.importer }.thenBy(impactFileOrder) { it.dependency },
    ))
    public val coverage: Set<ImpactScope> = impactScopes(coverage)
    public val unresolved: Set<ImpactDependency> = impactSet(unresolved.sortedWith(
        compareBy<ImpactDependency, ImpactFile>(impactFileOrder) { it.importer }.thenBy { it.description },
    ))

    init {
        impactLabel(version); impactLabel(provenance)
        require(this.imports.all { it.importer in this.files && it.dependency in this.files }) {
            "import endpoint absent from graph"
        }
        require(this.unresolved.all { it.importer in this.files }) { "unresolved importer absent from graph" }
    }
}

/** Zero-based [start, start+count), independently in the old and new file. */
public data class ImpactLines(val start: Long, val count: Long) {
    init { require(start >= 0 && count >= 0 && start <= Long.MAX_VALUE - count) }
}

/** A count from the enclosing symbol's index; unknown is null, lexical counts are never complete. */
public data class ImpactFanIn(val count: Long?, val tier: IndexTier, val complete: Boolean) {
    init {
        require(count == null || count >= 0)
        require(!complete || (count != null && tier != IndexTier.Lexical))
    }
}

public data class ImpactHunk(
    val file: ImpactFile,
    val oldLines: ImpactLines,
    val newLines: ImpactLines,
    val symbol: String?,
    val fanIn: ImpactFanIn,
) {
    init { symbol?.let(::impactLabel) }
}

/** Qualified selection inputs, not evidence. Null/partial closure requires the containing suite. */
public class ImpactCheck(
    public val id: String,
    public val scope: ImpactScope,
    testFiles: Set<ImpactFile>,
    namingTargets: Set<ImpactFile>,
    closure: Set<ImpactFile>?,
    public val closureComplete: Boolean,
) {
    public val testFiles: Set<ImpactFile> = impactFiles(testFiles)
    public val namingTargets: Set<ImpactFile> = impactFiles(namingTargets)
    public val closure: Set<ImpactFile>? = closure?.let(::impactFiles)
    init { impactLabel(id); require(!closureComplete || closure != null) }
}

/** Caller supplies only CON/ADR projections; incomplete anchors cannot prove absence of touch. */
public class ImpactContract(public val id: String, anchors: Set<ImpactFile>?, public val complete: Boolean) {
    public val anchors: Set<ImpactFile>? = anchors?.let(::impactFiles)
    init { impactLabel(id); require(!complete || anchors != null) }
}

public class ImpactRequest(
    public val graph: ImpactGraph,
    edits: Set<ImpactFile>,
    hunks: List<ImpactHunk>?,
    checks: List<ImpactCheck>,
    contracts: List<ImpactContract>,
    public val contractsComplete: Boolean,
) {
    public val edits: Set<ImpactFile> = impactFiles(edits)
    /** Null means unknown diff (e.g. pre-scan); otherwise the caller attests a complete hunk inventory. */
    public val hunks: List<ImpactHunk>? = hunks?.let { impactList(it.distinct().sortedWith(impactHunkOrder)) }
    public val checks: List<ImpactCheck> = impactList(checks.sortedBy { it.id })
    public val contracts: List<ImpactContract> = impactList(contracts.sortedBy { it.id })
    init {
        require(this.checks.map { it.id }.distinct().size == this.checks.size) { "duplicate check ID" }
        require(this.contracts.map { it.id }.distinct().size == this.contracts.size) { "duplicate contract ID" }
        require(this.hunks.orEmpty().all { it.file in this.edits }) { "hunk outside edit set" }
    }
}

/** Estimate is Double arithmetic, not a bound on semantic risk. Unknown threshold verdict stays null. */
public data class ImpactRisk(
    val changedLines: BigInteger?,
    val estimate: Double?,
    val complete: Boolean,
    val threshold: Int,
    val exceedsThreshold: Boolean?,
) {
    public val requiresSlowChecks: Boolean get() = exceedsThreshold != false
}

public class ImpactAnalysis internal constructor(
    public val request: ImpactRequest,
    blast: Set<ImpactFile>,
    public val blastComplete: Boolean,
    affectedTests: Set<String>,
    contractsTouched: Set<String>,
    public val contractsComplete: Boolean,
    verificationScopes: Set<ImpactScope>,
    public val risk: ImpactRisk,
    issues: Set<String>,
) {
    public val tier: IndexTier get() = request.graph.tier
    public val complete: Boolean get() = blastComplete && contractsComplete && risk.complete && issues.isEmpty()
    public val blast: Set<ImpactFile> = impactFiles(blast)
    public val affectedTests: Set<String> = impactSet(affectedTests.sorted())
    public val contractsTouched: Set<String> = impactSet(contractsTouched.sorted())
    public val verificationScopes: Set<ImpactScope> = impactScopes(verificationScopes)
    public val issues: Set<String> = impactSet(issues.sorted())
}

internal val impactScopeOrder: Comparator<ImpactScope> = compareBy({ it.workspace.value }, { it.packageId })
internal val impactFileOrder: Comparator<ImpactFile> =
    compareBy<ImpactFile, ImpactScope>(impactScopeOrder) { it.scope }.thenBy { it.path }
internal val impactHunkOrder: Comparator<ImpactHunk> = compareBy<ImpactHunk, ImpactFile>(impactFileOrder) { it.file }
    .thenBy { it.oldLines.start }.thenBy { it.oldLines.count }
    .thenBy { it.newLines.start }.thenBy { it.newLines.count }
    .thenBy { it.symbol }.thenBy { it.fanIn.count }.thenBy { it.fanIn.tier.level }.thenBy { it.fanIn.complete }

private fun impactLabel(value: String) {
    require(value.isNotBlank() && value == value.trim() && value.none { it.isISOControl() })
}
internal fun <T> impactSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
private fun <T> impactList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
private fun impactFiles(values: Set<ImpactFile>): Set<ImpactFile> = impactSet(values.sortedWith(impactFileOrder))
private fun impactScopes(values: Set<ImpactScope>): Set<ImpactScope> = impactSet(values.sortedWith(impactScopeOrder))
