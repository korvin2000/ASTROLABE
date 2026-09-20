package io.astrolabe.atlas

import io.astrolabe.Defaults
import java.math.BigInteger
import kotlin.math.log2

/** Pure calculation over supplied snapshots (§7.3–7.4, D-63); performs no discovery or verification. */
public object Impact {
    @JvmStatic
    @JvmOverloads
    public fun analyze(request: ImpactRequest, defaults: Defaults = Defaults()): ImpactAnalysis {
        require(defaults.theta >= 0) { "negative risk threshold" }
        val graph = request.graph
        val issues = linkedSetOf<String>()
        val scopes = linkedSetOf<ImpactScope>()
        val reverse = HashMap<ImpactFile, MutableList<ImpactFile>>()
        for (edge in graph.imports) reverse.getOrPut(edge.dependency, ::ArrayList).add(edge.importer)
        val blast = LinkedHashSet(request.edits)
        val queue = ArrayDeque(request.edits)
        while (queue.isNotEmpty()) {
            for (importer in reverse[queue.removeFirst()].orEmpty()) {
                if (blast.add(importer)) queue.addLast(importer)
            }
        }

        if (!graph.complete) issues += "graph marked incomplete"
        if (graph.tier == IndexTier.Lexical) issues += "lexical graph is incomplete"
        for (entry in graph.unresolved) issues += "unresolved dependency: ${entry.importer}: ${entry.description}"
        val represented = graph.files + request.edits
        for (file in represented) {
            if (!covered(file, graph.coverage)) issues += "graph coverage missing: $file"
            if (file.scope.packageId == null) issues += "file package unknown: $file"
        }
        for (file in request.edits - graph.files) issues += "edited file absent from graph: $file"
        val blastComplete = issues.isEmpty()
        if (!blastComplete) {
            scopes += graph.coverage
            scopes += represented.map { it.scope }
        }

        val affected = linkedSetOf<String>()
        for (check in request.checks) {
            val unknownPackages = (check.testFiles + check.namingTargets + check.closure.orEmpty())
                .filter { it.scope.packageId == null }
            val uncertain = !check.closureComplete || unknownPackages.isNotEmpty()
            if (!blastComplete || uncertain || check.testFiles.any { it in blast } ||
                check.namingTargets.any { it in blast } || check.closure.orEmpty().any { it in blast }
            ) affected += check.id
            if (uncertain || !blastComplete) {
                scopes += check.scope
                scopes += unknownPackages.map { it.scope }
                if (uncertain) issues += "check closure incomplete: ${check.id}"
            }
        }

        val touched = linkedSetOf<String>()
        var contractsComplete = request.contractsComplete
        if (!request.contractsComplete) issues += "contract inventory incomplete"
        for (contract in request.contracts) {
            if (contract.anchors.orEmpty().any { it in request.edits }) touched += contract.id
            if (!contract.complete || contract.anchors.orEmpty().any { it.scope.packageId == null }) {
                issues += "contract anchors incomplete: ${contract.id}"
                contractsComplete = false
            }
        }
        val risk = risk(request.hunks, defaults.theta)
        if (!risk.complete) issues += "risk incomplete: missing diff or enclosing-symbol fan-in"
        // A workspace suite subsumes the package suites in that workspace, without merging workspaces.
        val workspaceSuites = scopes.filter { it.packageId == null }.mapTo(HashSet()) { it.workspace }
        scopes.removeIf { it.packageId != null && it.workspace in workspaceSuites }
        return ImpactAnalysis(request, blast, blastComplete, affected, touched, contractsComplete, scopes, risk, issues)
    }

    private fun covered(file: ImpactFile, scopes: Set<ImpactScope>): Boolean =
        file.scope in scopes || ImpactScope(file.scope.workspace, null) in scopes

    private fun risk(hunks: List<ImpactHunk>?, threshold: Int): ImpactRisk {
        if (hunks == null) return ImpactRisk(null, null, false, threshold, null)
        for (group in hunks.groupBy { it.file }.values) {
            requireDisjoint(group.map { it.oldLines })
            requireDisjoint(group.map { it.newLines })
        }
        var lines = BigInteger.ZERO
        var estimate = 0.0
        var compensation = 0.0
        var numeric = true
        var complete = true
        for (hunk in hunks) {
            val changed = BigInteger.valueOf(hunk.oldLines.count) + BigInteger.valueOf(hunk.newLines.count)
            lines += changed
            if (changed.signum() == 0) continue
            val count = hunk.fanIn.count
            if (count == null) numeric = false
            if (!hunk.fanIn.complete || hunk.symbol == null) complete = false
            if (count != null) {
                val contribution = changed.toDouble() * (1.0 + log2(1.0 + count.toDouble()))
                val corrected = contribution - compensation
                val sum = estimate + corrected
                compensation = (sum - estimate) - corrected
                estimate = sum
            }
        }
        return ImpactRisk(lines, estimate.takeIf { numeric }, complete, threshold,
            if (complete) estimate > threshold else null)
    }

    private fun requireDisjoint(ranges: List<ImpactLines>) {
        var end = 0L
        for (range in ranges.filter { it.count > 0 }.sortedBy { it.start }) {
            require(range.start >= end) { "overlapping non-identical hunks in old or new coordinates" }
            end = range.start + range.count
        }
    }
}
