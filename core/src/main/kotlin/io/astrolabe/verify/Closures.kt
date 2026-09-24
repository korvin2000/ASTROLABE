package io.astrolabe.verify

import io.astrolabe.atlas.ImpactAnalysis
import io.astrolabe.atlas.ImpactScope
import io.astrolabe.evidence.Closure

/** Closures the scheduler computes for its checks (§8.1, P3.1.1). */
public object Closures {
    /**
     * The input closure of the blast-selected test run (`CHK-tests-blast`) from an impact [analysis]: `known` over the
     * selected checks' test files and closures when the blast and every selected check's closure are complete and they
     * sit in one workspace; otherwise the conservative containing scope — the single verification scope's package
     * directory when [packagePath] names it, else `unknown` (never a guessed package, D-63).
     */
    @JvmStatic
    @JvmOverloads
    public fun blast(analysis: ImpactAnalysis, packagePath: (ImpactScope) -> String? = { null }): Closure {
        val selected = analysis.request.checks.filter { it.id in analysis.affectedTests }
        val files = selected.flatMap { it.testFiles + it.closure.orEmpty() }
        val exact = analysis.blastComplete && selected.all { it.closureComplete && it.closure != null } &&
            files.map { it.scope.workspace }.distinct().size <= 1
        if (exact) return Closure.Known(files.map { it.path }.toSortedSet())
        val scope = analysis.verificationScopes.singleOrNull()?.takeIf { it.packageId != null } ?: return Closure.Unknown
        return packagePath(scope)?.let { Closure.Package(it) } ?: Closure.Unknown
    }
}
