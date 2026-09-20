package io.astrolabe.tool.edit

import io.astrolabe.workspace.LineRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.6.4 anchor location (D-33): exact, then whitespace-normalized with original spans; near; sites; nearest candidates. */
class AnchorsTest {
    private val text = """
        def a():
            return 1


        def b():
            return 2

        def b():
            return 3  # twin
    """.trimIndent() + "\n"

    @Test
    fun `an exact anchor locates once with its lines and offsets`() {
        val one = assertIs<Location.One>(Anchors.locate(text, "def a():\n    return 1"))
        assertEquals(LineRange(1, 2), one.span.lines)
        assertTrue(one.span.exact)
        assertEquals("def a():\n    return 1", text.substring(one.span.start, one.span.end))
    }

    @Test
    fun `whitespace normalization finds the anchor and maps back to the original span`() {
        val one = assertIs<Location.One>(Anchors.locate(text, "def a():\r\n\treturn   1  "))
        assertEquals(LineRange(1, 2), one.span.lines)
        assertTrue(!one.span.exact)
        assertEquals("def a():\n    return 1", text.substring(one.span.start, one.span.end), "the span covers the original bytes, indentation included after the first content char")

        val inner = assertIs<Location.One>(Anchors.locate("x = f(  1,   2 )\n", "f( 1,  2 )"))
        assertEquals("f(  1,   2 )", "x = f(  1,   2 )\n".substring(inner.span.start, inner.span.end), "runs collapse to one blank; blanks are never removed")
        assertIs<Location.None>(Anchors.locate("x = f(  1,   2 )\n", "f(1, 2)"), "a missing blank is a different anchor, not a fuzzy match")
    }

    @Test
    fun `several sites are reported and near selects among them`() {
        val many = assertIs<Location.Many>(Anchors.locate(text, "def b():"))
        assertEquals(listOf(LineRange(5, 5), LineRange(8, 8)), many.sites.map { it.lines })
        val twin = assertIs<Location.One>(Anchors.locate(text, "def b():", near = "return 2"))
        assertEquals(LineRange(8, 8), twin.span.lines, "near looks at the twenty preceding lines: line 6 precedes the second site only")
        val inside = assertIs<Location.One>(Anchors.locate(text, "    return", near = "twin"))
        assertEquals(LineRange(9, 9), inside.span.lines, "near may also sit inside the span")
        assertIs<Location.Many>(Anchors.locate(text, "def b():", near = "nowhere"), "a near that selects nothing leaves every site listed")
    }

    @Test
    fun `no match offers the three nearest lines`() {
        val none = assertIs<Location.None>(Anchors.locate(text, "def c():\n    return 4"))
        assertEquals(listOf(1, 5, 8), none.candidates.map { it.line }, "lines sharing the most identifier tokens, ties by position")
        assertEquals("def a():", none.candidates.first().text)
        assertTrue(assertIs<Location.None>(Anchors.locate(text, "zzz")).candidates.isEmpty())
    }

    @Test
    fun `line diff shows what moved since expect with context and a bound`() {
        val diff = LineDiff.unified("a\nb\nc\nd\ne\nf\n", "a\nb\nC\nd\ne\nf\n")!!
        assertEquals(" a\n b\n-c\n+C\n d\n e", diff)
        assertEquals("(no line differs; whitespace or encoding changed)", LineDiff.unified("a\n", "a\n"))
        assertNull(LineDiff.unified((1..5000).joinToString("\n"), "x"))
        val bounded = LineDiff.unified((1..300).joinToString("\n"), (1..300).joinToString("\n") { "x$it" }, maxOutputLines = 10)!!
        assertTrue(bounded.endsWith("more diff lines"), bounded)
    }
}
