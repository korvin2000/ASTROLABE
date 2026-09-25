package io.astrolabe.fixtures

import io.astrolabe.atlas.AdapterScope
import io.astrolabe.atlas.IncrementalCheck
import io.astrolabe.atlas.LanguageDefs
import io.astrolabe.atlas.LanguageRefs
import io.astrolabe.atlas.LanguageService
import io.astrolabe.atlas.Location
import io.astrolabe.atlas.Reference
import io.astrolabe.atlas.UnresolvedCase
import io.astrolabe.tool.run.Diagnostic

/**
 * A scripted tier-2 [LanguageService] for test fixtures (TODO P5.4.2): answers come from the maps the test
 * supplies, never computed, so a test states exactly what the adapter is asserting and at what [scope].
 * A name or path absent from the script answers empty rather than throwing, staying honest about [complete].
 */
public class FakeLanguageService(
    private val scope: AdapterScope = AdapterScope("fixture project"),
    private val defsByName: Map<String, List<Location>> = emptyMap(),
    private val refsByName: Map<String, Pair<List<Reference>, List<UnresolvedCase>>> = emptyMap(),
    private val diagnosticsByPath: Map<String, List<Diagnostic>> = emptyMap(),
    private val complete: Boolean = true,
) : LanguageService {
    override suspend fun defs(name: String): LanguageDefs = LanguageDefs(name, defsByName[name].orEmpty(), scope, complete)

    override suspend fun refs(name: String): LanguageRefs {
        val (references, unresolved) = refsByName[name] ?: (emptyList<Reference>() to emptyList())
        return LanguageRefs(name, references, unresolved, scope, complete)
    }

    override suspend fun diagnostics(paths: List<String>): List<Diagnostic> = paths.flatMap { diagnosticsByPath[it].orEmpty() }

    override suspend fun incrementalTypeCheck(changed: List<String>): IncrementalCheck =
        IncrementalCheck(changed, changed.flatMap { diagnosticsByPath[it].orEmpty() }, complete)
}
