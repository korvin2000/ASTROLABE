package io.astrolabe.atlas

import io.astrolabe.Defaults
import io.astrolabe.evidence.Closure
import io.astrolabe.verify.Check

/** One diff hunk of E in the kernel's coordinates: zero-based half-open old and new line ranges. */
public data class EditHunk(
    val path: String,
    val oldStart: Long,
    val oldCount: Long,
    val newStart: Long,
    val newCount: Long,
) {
    init {
        require(path.isNotBlank()) { "a hunk needs a path" }
        require(oldStart >= 0 && oldCount >= 0 && newStart >= 0 && newCount >= 0) { "negative hunk coordinates" }
    }
}

/**
 * E of §7.4: the edited paths with their complete hunk inventory, or paths only ([hunks] `null`)
 * when no diff is known yet (the P3.2.6 pre-scan).
 */
public data class EditSet(val paths: Set<String>, val hunks: List<EditHunk>? = null) {
    init {
        require(paths.isNotEmpty()) { "an edit set names at least one path" }
        val normalized = paths.mapTo(HashSet(), ::normalizeRelative)
        require(hunks.orEmpty().all { normalizeRelative(it.path) in normalized }) { "hunk outside the edit set" }
    }

    public companion object {
        /** Paths only: the diff is unknown, so risk stays unknown (pre-scan). */
        @JvmStatic
        public fun paths(vararg paths: String): EditSet = EditSet(paths.toSet())
    }
}

/**
 * A CON/ADR note's file anchors (§7.4 `contracts_touched`). Loading them from the KB is P4.1, so the
 * caller supplies the list (D-89); `null` anchors are an unknown anchor set, which can never prove
 * absence of a touch.
 */
public data class ContractAnchors(val id: String, val anchors: Set<String>?, val complete: Boolean = anchors != null) {
    init {
        require(id.isNotBlank()) { "a contract needs an id" }
        require(!complete || anchors != null) { "complete anchors must be listed" }
    }
}

/** The routing consumer's input (§7.4 → §11.2 risk floor, wired by P4.5.1): values only. */
public data class RiskFloorInput(
    val contractsTouched: Int,
    val contractsComplete: Boolean,
    val maxFanIn: Long?,
    val fanInComplete: Boolean,
)

/**
 * One analysis, four consumers (§7.4): the projections each consumer reads from an [ImpactAnalysis].
 * These are flags and values; the scheduler (P3.2.5), the router (P4.5.1) and the shape selector
 * (P3.2.6) keep their own wiring.
 *
 * [interfaceChange] is supplied by the caller until the P3.2.4 outline diff produces it (D-90).
 */
public class ImpactProjection internal constructor(
    public val analysis: ImpactAnalysis,
    public val interfaceChange: Boolean,
    blastChecks: Set<String>,
) {
    public val tier: IndexTier get() = analysis.tier
    public val complete: Boolean get() = analysis.complete

    // Verification depth: slow checks fire early when risk > θ, or when risk is unknown.
    public val slowChecksEarly: Boolean get() = analysis.risk.requiresSlowChecks
    public val affectedTests: Set<String> get() = analysis.affectedTests
    public val verificationScopes: Set<ImpactScope> get() = analysis.verificationScopes

    /**
     * Check IDs whose test files, naming targets or closure intersect the blast (the §7.4 union
     * without the incomplete-graph widening; D-91). At tier 0 [affectedTests] retains every check,
     * so this is what `look(impact)` shows as "what changing E touches"; selection never uses it.
     */
    public val blastChecks: Set<String> = impactSet(blastChecks.sorted())

    // Routing: contracts touched or high fan-in raise the risk floor (§11.2).
    public val riskFloor: RiskFloorInput = RiskFloorInput(
        contractsTouched = analysis.contractsTouched.size,
        contractsComplete = analysis.contractsComplete,
        maxFanIn = analysis.request.hunks?.mapNotNull { it.fanIn.count }?.maxOrNull(),
        fanInComplete = analysis.request.hunks?.all { it.fanIn.complete } ?: false,
    )

    // Shape and human anchors.
    public val contractTouch: Boolean get() = analysis.contractsTouched.isNotEmpty()

    /** Contract touch ⇒ S2 with an ADR in the main line. */
    public val requiresMainLineAdr: Boolean get() = contractTouch

    /** Interface change ⇒ never auto-merged. */
    public val neverAutoMerged: Boolean get() = interfaceChange

    /** Interface change ⇒ never in an S3 child. */
    public val neverInS3Child: Boolean get() = interfaceChange
}

/**
 * Runtime assembly of `impact(E)` (§7.4, P3.2.2): builds the [ImpactRequest] the P3.2.7 kernel
 * consumes from the [ImportGraph], the [SymbolIndex] (fan-in of each hunk's enclosing symbol), the
 * registered [Check]s (acceptance `run:` items with their input closures) and supplied CON/ADR
 * anchors, then runs [Impact.analyze] once and projects it for the four consumers.
 */
public class ImpactAssembly @JvmOverloads constructor(
    private val graph: ImportGraph,
    private val index: SymbolIndex,
    private val defaults: Defaults = Defaults(),
) {
    private val fanIns = HashMap<String, ImpactFanIn>()

    /**
     * The kernel request for [edits]. A `null` [contracts] means no inventory was supplied
     * (`contractsComplete = false`, D-89); an empty list is an attested empty inventory.
     */
    public fun request(edits: EditSet, checks: Collection<Check>, contracts: Collection<ContractAnchors>?): ImpactRequest {
        val files = edits.paths.mapTo(LinkedHashSet(), graph::file)
        val hunks = edits.hunks?.map(::hunkOf)
        return ImpactRequest(
            graph = graph.graph,
            edits = files,
            hunks = hunks,
            checks = checks.map(::checkOf),
            contracts = contracts.orEmpty().map { ImpactContract(it.id, it.anchors?.mapTo(LinkedHashSet(), graph::file), it.complete) },
            contractsComplete = contracts != null,
        )
    }

    @JvmOverloads
    public fun analyze(
        edits: EditSet,
        checks: Collection<Check>,
        contracts: Collection<ContractAnchors>?,
        interfaceChange: Boolean = false,
    ): ImpactProjection {
        val request = request(edits, checks, contracts)
        val analysis = Impact.analyze(request, defaults)
        val blast = analysis.blast
        val inBlast = request.checks.filter { check ->
            check.testFiles.any { it in blast } || check.namingTargets.any { it in blast } ||
                check.closure.orEmpty().any { it in blast }
        }.mapTo(LinkedHashSet()) { it.id }
        return ImpactProjection(analysis, interfaceChange, inBlast)
    }

    private fun hunkOf(hunk: EditHunk): ImpactHunk {
        val path = normalizeRelative(hunk.path)
        val file = graph.file(path)
        val line = (if (hunk.newCount > 0) hunk.newStart + 1 else maxOf(1L, hunk.newStart)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val symbol = graph.atlas.outline(path).entries
            .filter { it.kind != DeclarationKind.Import && it.kind != DeclarationKind.Export && it.from <= line && line <= it.to }
            .minWithOrNull(compareBy<Declaration> { it.to - it.from }.thenByDescending { it.from })
        val fanIn = if (symbol == null) {
            // No enclosing symbol: a module-level change reaches every importer of the file (D-90).
            ImpactFanIn(graph.importers(path).size.toLong(), graph.graph.tier, complete = false)
        } else {
            fanIns.getOrPut(symbol.name) {
                val refs = index.refs(symbol.name)
                ImpactFanIn(refs.references.size.toLong(), refs.tier, refs.complete && !refs.truncated)
            }
        }
        return ImpactHunk(
            file = file,
            oldLines = ImpactLines(hunk.oldStart, hunk.oldCount),
            newLines = ImpactLines(hunk.newStart, hunk.newCount),
            symbol = symbol?.name,
            fanIn = fanIn,
        )
    }

    private fun checkOf(check: Check): ImpactCheck {
        val closurePaths: Set<String>? = when (val closure = check.inputClosure) {
            is Closure.Known -> closure.paths.mapTo(LinkedHashSet(), ::normalizeRelative).filterTo(LinkedHashSet()) { it.isNotEmpty() }
            is Closure.Package -> graph.filesUnder(closure.path).mapTo(LinkedHashSet()) { it.path }
            Closure.Unknown -> null
        }
        val closure = closurePaths?.mapTo(LinkedHashSet(), graph::file)
        val scope = when (val declared = check.inputClosure) {
            is Closure.Package -> graph.scopeOfDirectory(declared.path)
            is Closure.Known -> closure.orEmpty().map { it.scope }.distinct().singleOrNull() ?: ImpactScope(graph.workspace, null)
            Closure.Unknown -> check.command?.cwd?.let { graph.scopeOfDirectory(it) } ?: ImpactScope(graph.workspace, null)
        }
        val testFiles = closure.orEmpty().filterTo(LinkedHashSet()) { isTestPath(it.path) }
        val namingTargets = testFiles.flatMapTo(LinkedHashSet()) { test ->
            graph.atlas.row(test.path)?.testsFor.orEmpty().map(graph::file)
        }
        return ImpactCheck(
            id = check.id,
            scope = scope,
            testFiles = testFiles,
            namingTargets = namingTargets,
            closure = closure,
            closureComplete = closure != null,
        )
    }
}
