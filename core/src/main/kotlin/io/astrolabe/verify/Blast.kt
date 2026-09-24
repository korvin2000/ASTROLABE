package io.astrolabe.verify

import io.astrolabe.atlas.ImpactAnalysis
import io.astrolabe.atlas.ImpactFile
import io.astrolabe.atlas.ImpactScope
import io.astrolabe.atlas.isTestPath
import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure

/** What the blast-radius selection produced (§7.3, P3.2.5): the `CHK-tests-blast` check, or why there is none. */
public sealed interface BlastSelection {
    public data class Selected(val check: Check) : BlastSelection

    public data class NotSelected(val reason: String) : BlastSelection
}

/**
 * `blast(E) = closure(importers(E)) ∪ E`; tests = `tests_for(blast(E))` (§7.3). Only a complete blast over a runner that
 * takes test paths selects files; an incomplete graph (every tier-0 graph, D-88) widens to the single package suite,
 * else the workspace suite, and the verify line says which (D-93).
 */
public object Blast {
    private val PATH_RUNNERS = setOf("pytest", "py.test", "jest", "vitest", "mocha")

    @JvmStatic
    public fun select(
        analysis: ImpactAnalysis,
        test: Command,
        testsFor: (Set<ImpactFile>) -> Set<ImpactFile>,
        packagePath: (ImpactScope) -> String?,
    ): BlastSelection {
        val blast = analysis.blast
        if (blast.isEmpty()) return BlastSelection.NotSelected("blast radius: nothing touched")
        if (analysis.blastComplete && takesPaths(test)) {
            val tests = (testsFor(blast) + blast.filter { isTestPath(it.path) }).map { it.path }.toSortedSet()
            if (tests.isEmpty()) return BlastSelection.NotSelected("blast radius: no tests select ${blast.size} files")
            val closure = Closure.Known((tests + blast.map { it.path }).toSortedSet())
            return BlastSelection.Selected(check(Command(test.argv + tests, test.cwd), closure))
        }
        // §7.3: widen to the package suite of the blast (one package with a directory), else the workspace suite; the
        // kernel's all-coverage scopes (D-63) would make every tier-0 monorepo edit a workspace run (D-93).
        val closure = blast.map { it.scope }.distinct().singleOrNull()?.let(packagePath)?.let { Closure.Package(it) } ?: Closure.Unknown
        val cwd = (closure as? Closure.Package)?.path ?: test.cwd
        return BlastSelection.Selected(check(Command(test.argv, cwd), closure))
    }

    /** The verify-line scope of a check: `touched`, `blast N` (files under test), `package p` or `workspace` (widened). */
    @JvmStatic
    public fun scope(check: Check): String? = when (check.selector) {
        Selector.Touched -> "touched"
        Selector.Blast -> when (val closure = check.inputClosure) {
            is Closure.Known -> "blast ${closure.paths.size}"
            is Closure.Package -> "package ${closure.path}"
            Closure.Unknown -> "workspace"
        }
        else -> null
    }

    private fun takesPaths(test: Command): Boolean = test.argv.any { it.substringAfterLast('/').substringAfterLast('\\') in PATH_RUNNERS }

    private fun check(command: Command, closure: Closure): Check =
        Check(Checks.TESTS_BLAST, CheckKind.Unit, Selector.Blast, closure, CostClass.Slow, Trigger.StepBoundary, command = command)
}
