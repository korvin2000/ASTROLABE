package io.astrolabe.atlas

import io.astrolabe.fixtures.FakeLanguageService
import io.astrolabe.java.JavaLanguageService
import io.astrolabe.tool.run.Diagnostic
import io.astrolabe.tool.run.DiagnosticSeverity
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P5.4.2: the tier-2 language-service adapter contract, a scripted fake, and the Java SPI bridge. */
class LanguageServiceTest {
    private val definition = Location("pay/router.py", 12, DeclarationKind.Function, "charge")
    private val use = Reference("pay/handlers/user.py", 7, "charge(order)")
    private val dynamic = UnresolvedCase("pay/plugins/loader.py", 3, "dispatched through a plugin registry")
    private val diagnostic = Diagnostic("pay/router.py", 12, 5, DiagnosticSeverity.Error, "unresolved-attr", "no attribute 'chrage'")

    @Test
    fun `every answer carries tier 2 and the adapter's own declared scope`() = runTest {
        val service: LanguageService = FakeLanguageService(
            scope = AdapterScope("package pay"),
            defsByName = mapOf("charge" to listOf(definition)),
            refsByName = mapOf("charge" to (listOf(use) to listOf(dynamic))),
            diagnosticsByPath = mapOf("pay/router.py" to listOf(diagnostic)),
        )
        assertEquals(IndexTier.LanguageService, service.tier)
        val defs = service.defs("charge")
        assertEquals(listOf(definition), defs.locations)
        assertEquals(AdapterScope("package pay"), defs.scope)
        assertTrue(defs.complete)
        val refs = service.refs("charge")
        assertEquals(listOf(use), refs.references)
        assertEquals(listOf(dynamic), refs.unresolved, "dynamic dispatch is reported, never silently dropped (§7.2)")
        assertEquals(listOf(diagnostic), service.diagnostics(listOf("pay/router.py", "pay/other.py")))
        val incremental = service.incrementalTypeCheck(listOf("pay/router.py"))
        assertEquals(listOf("pay/router.py"), incremental.changed)
        assertEquals(listOf(diagnostic), incremental.diagnostics)
    }

    @Test
    fun `a name or path outside the script answers empty rather than throwing`() = runTest {
        val service: LanguageService = FakeLanguageService()
        assertEquals(emptyList(), service.defs("unknown").locations)
        assertEquals(emptyList(), service.refs("unknown").references)
        assertEquals(emptyList(), service.refs("unknown").unresolved)
        assertEquals(emptyList(), service.diagnostics(listOf("nowhere.py")))
    }

    @Test
    fun `java language-service bridge maps futures and unwraps exceptional completions`() = runTest {
        val java = object : JavaLanguageService {
            override fun defs(name: String): CompletableFuture<LanguageDefs> = CompletableFuture.completedFuture(LanguageDefs(name, listOf(definition), AdapterScope("workspace"), true))
            override fun refs(name: String): CompletableFuture<LanguageRefs> =
                if (name == "fail") CompletableFuture.failedFuture(IllegalStateException("adapter crashed"))
                else CompletableFuture.completedFuture(LanguageRefs(name, listOf(use), emptyList(), AdapterScope("workspace"), true))
            override fun diagnostics(paths: List<String>): CompletableFuture<List<Diagnostic>> = CompletableFuture.completedFuture(listOf(diagnostic))
            override fun incrementalTypeCheck(changed: List<String>): CompletableFuture<IncrementalCheck> = CompletableFuture.completedFuture(IncrementalCheck(changed, emptyList(), true))
        }
        val service = LanguageServices.fromJava(java)
        assertEquals(listOf(definition), service.defs("charge").locations)
        assertEquals(listOf(use), service.refs("charge").references)
        assertEquals(listOf(diagnostic), service.diagnostics(listOf("pay/router.py")))
        assertEquals(listOf("pay/router.py"), service.incrementalTypeCheck(listOf("pay/router.py")).changed)
        val failure = kotlin.runCatching { service.refs("fail") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException && failure.message == "adapter crashed", "the host's own exception surfaces, not a wrapper")
    }
}
