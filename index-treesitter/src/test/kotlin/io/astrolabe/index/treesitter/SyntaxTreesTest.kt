package io.astrolabe.index.treesitter

import io.astrolabe.atlas.DeclarationKind
import io.astrolabe.tool.edit.SyntaxResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** P5.4.1: trees cached per (path, version), incremental re-parse, and the D-10 ERROR/MISSING verdict. */
class SyntaxTreesTest {
    private val trees = SyntaxTrees(GrammarLoader(Grammar::load), capacity = 2)

    @AfterTest
    fun close() = trees.close()

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `one version is parsed once and a new version is parsed incrementally from the old tree`() {
        val v1 = bytes("def a():\n    return 1\n\ndef b():\n    return 2\n")
        val v2 = bytes("def a():\n    return 1\n\ndef inserted():\n    pass\n\ndef b():\n    return 2\n")

        val first = assertNotNull(trees.outline("m.py", v1, Grammar.Python))
        assertEquals(first, trees.outline("m.py", v1.copyOf(), Grammar.Python), "same bytes, same version")
        assertEquals(1, trees.fullParses)
        assertEquals(1, trees.hits)

        val second = assertNotNull(trees.outline("m.py", v2, Grammar.Python))
        assertEquals(1, trees.incrementalParses)
        assertEquals(listOf("a", "inserted", "b"), second.entries.map { it.name })
        assertEquals(7..8, second.entries.single { it.name == "b" }.let { it.from..it.to })

        // Back to the first version: still incremental, and the answer equals a fresh parse.
        assertEquals(first, trees.outline("m.py", v1, Grammar.Python))
        assertEquals(2, trees.incrementalParses)
        assertEquals(1, trees.fullParses)
    }

    @Test
    fun `the cache is bounded and evicts the least recently used tree`() {
        val source = bytes("x = 1\n")
        trees.outline("a.py", source, Grammar.Python)
        trees.outline("b.py", source, Grammar.Python)
        trees.outline("a.py", source, Grammar.Python)
        trees.outline("c.py", source, Grammar.Python)
        trees.outline("a.py", source, Grammar.Python)
        assertEquals(3, trees.fullParses)
        trees.outline("b.py", source, Grammar.Python)
        assertEquals(4, trees.fullParses, "b.py was evicted as least recently used")
    }

    @Test
    fun `error and missing nodes both fail the syntax verdict`() {
        assertEquals(SyntaxResult.Ok, trees.syntax("ok.py", bytes("def f():\n    return 1\n"), Grammar.Python))

        val error = assertIs<SyntaxResult.Error>(trees.syntax("bad.py", bytes("x = 1\ndef (:\n"), Grammar.Python))
        assertEquals(2, error.line)

        val missing = assertIs<SyntaxResult.Error>(
            trees.syntax("A.java", bytes("class A {\n  void f() {\n    int x = 1\n  }\n}\n"), Grammar.Java),
        )
        assertTrue("missing" in missing.message, missing.message)
        assertEquals(3, missing.line)

        val script = assertIs<SyntaxResult.Error>(trees.syntax("a.ts", bytes("export function f( {\n"), Grammar.TypeScript))
        assertEquals(1, script.line)
    }

    @Test
    fun `an outline of a version answers the same after the path moved on`() {
        val v1 = bytes("class A:\n    pass\n")
        val v2 = bytes("class B:\n    pass\n")
        val a = assertNotNull(trees.outline("k.py", v1, Grammar.Python))
        trees.outline("k.py", v2, Grammar.Python)
        assertEquals(a, trees.outline("k.py", v1, Grammar.Python))
        assertEquals(DeclarationKind.Class, a.entries.single().kind)
    }
}
