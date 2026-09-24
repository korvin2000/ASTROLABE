package io.astrolabe.campaign

import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.ContractAnchors
import io.astrolabe.atlas.EditSet
import io.astrolabe.atlas.ImpactAssembly
import io.astrolabe.atlas.ImportGraph
import io.astrolabe.atlas.SymbolIndex
import io.astrolabe.id.WorkspaceId

/** The D-40 candidate paths of a pre-scan, by source, as logged inputs. */
public data class PrescanInputs(
    /** Atlas paths the request names (whole path or a unique-enough suffix). */
    val named: List<String>,
    /** Request identifiers (code-shaped, D-94) → the paths that declare them. */
    val identifiers: Map<String, List<String>>,
    /** Atlas hubs of the focus subsystem (the top-level directories of the paths above). */
    val hubs: List<String>,
    /** Paths the cells actually touched, once known (the refresh, I-23). */
    val touched: List<String> = emptyList(),
) {
    val paths: List<String> get() = (named + identifiers.values.flatten() + hubs + touched).distinct().sorted()

    val log: String
        get() = "named=[${named.joinToString(",")}] identifiers=[${identifiers.entries.joinToString(",") { (k, v) -> "$k:${v.size}" }}] " +
            "hubs=[${hubs.joinToString(",")}]" + if (touched.isEmpty()) "" else " touched=[${touched.joinToString(",")}]"
}

/**
 * The §3.7 `impact_prescan` at campaign open (P3.2.6, D-40): [Impact] over the request's candidate paths, recorded as
 * **incomplete discovery** — its coverage, unresolved dependencies and explicit risk. Zero hits never means no contract
 * impact: without candidates [prescan] stays unknown, and a contract touch is only ever `true` or unknown.
 */
public data class ImpactPrescan(
    val inputs: PrescanInputs,
    val prescan: Prescan,
    val blast: List<String>,
    val packages: List<String>,
    val complete: Boolean,
    val unresolved: List<String>,
    val contractsTouched: List<String>,
) {
    /** One deterministic line for the shape log and the journal. */
    val log: String
        get() = "prescan ${inputs.log} blast=${blast.size} packages=[${packages.joinToString(",")}] complete=$complete " +
            "contractsTouched=[${contractsTouched.joinToString(",")}] fanIn=${prescan.fanIn ?: "unknown"} unresolved=${unresolved.size}"

    public companion object {
        private const val HUBS = 3
        private const val NAMED_MAX = 20
        private val WORD = Regex("`([^`]+)`|[A-Za-z_][A-Za-z0-9_]*(?:[./-][A-Za-z0-9_]+)*(\\()?")

        /** The D-40 candidates of [request] in [atlas]; hubs are ranked by importers in the [workspace] import graph. */
        @JvmStatic
        public fun inputs(atlas: Atlas, workspace: WorkspaceId, request: String): PrescanInputs {
            val named = LinkedHashSet<String>()
            val identifiers = LinkedHashMap<String, List<String>>()
            val index = SymbolIndex(atlas)
            for (match in WORD.findAll(request)) {
                val token = (match.groups[1]?.value ?: match.value.removeSuffix("(")).trim()
                if ('/' in token || '.' in token) {
                    val hits = atlas.rows.map { it.path }.filter { it == token || it.endsWith("/$token") }
                    if (hits.size <= NAMED_MAX) named += hits
                }
                val name = token.substringAfterLast('.')
                if (codeShaped(name, backticked = match.groups[1] != null, called = match.groups[2] != null) && name !in identifiers) {
                    val defs = index.def(name).map { it.path }.distinct()
                    if (defs.isNotEmpty()) identifiers[name] = defs
                }
            }
            val focus = (named + identifiers.values.flatten()).mapTo(HashSet()) { it.substringBefore('/', "") }
            val hubs = if (focus.isEmpty()) emptyList() else ImportGraph.of(atlas, workspace).graph.imports
                .map { it.dependency.path }.filter { it.substringAfterLast('/') !in ImportGraph.MANIFESTS && it.substringBefore('/', "") in focus }
                .groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(HUBS).map { it.key }
            return PrescanInputs(named.toList(), identifiers, hubs)
        }

        /** Runs the pre-scan over [inputs]; [anchors] are the known `CON` anchors (none until the KB has them). */
        @JvmStatic
        @JvmOverloads
        public fun of(atlas: Atlas, workspace: WorkspaceId, inputs: PrescanInputs, anchors: Map<String, Set<String>> = emptyMap()): ImpactPrescan {
            val paths = inputs.paths
            if (paths.isEmpty()) return ImpactPrescan(inputs, Prescan.UNKNOWN, emptyList(), emptyList(), false, listOf("no candidate paths: discovery found nothing, which proves nothing"), emptyList())
            val graph = ImportGraph.of(atlas, workspace)
            val index = SymbolIndex(atlas)
            val contracts = anchors.map { (id, files) -> ContractAnchors(id, files, complete = false) }
            val analysis = ImpactAssembly(graph, index).analyze(EditSet(paths.toSet()), emptyList(), contracts).analysis
            val blast = analysis.blast.map { it.path }
            val packages = analysis.blast.mapNotNull { it.scope.packageId }.distinct().sorted()
            val fanIn = inputs.identifiers.keys.maxOfOrNull { index.refs(it).references.size.toLong() }
            val touched = analysis.contractsTouched.toList()
            val prescan = Prescan(
                filesEstimated = blast.size,
                crossPackage = if (packages.size > 1) true else null,
                contractTouch = if (touched.isNotEmpty()) true else null,
                fanIn = fanIn,
            )
            return ImpactPrescan(inputs, prescan, blast, packages, analysis.complete, analysis.issues.toList(), touched)
        }

        // D-94: backticked, called, snake_case or an inner capital (camel/Pascal with two humps); never a plain word.
        private fun codeShaped(name: String, backticked: Boolean, called: Boolean): Boolean {
            if (name.length < 3 || !(name[0].isLetter() || name[0] == '_') || !name.all { it.isLetterOrDigit() || it == '_' }) return false
            if (backticked || called || '_' in name.trim('_')) return true
            return (1 until name.length).any { name[it].isUpperCase() && name[it - 1].isLowerCase() }
        }
    }
}
