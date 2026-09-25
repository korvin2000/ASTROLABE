package io.astrolabe.index.treesitter

import io.astrolabe.atlas.DeclarationKind
import io.astrolabe.atlas.IndexTier
import io.astrolabe.atlas.Language
import io.astrolabe.atlas.Outline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** P5.4.1: precise tier-1 outlines, spans and imports per D-09 grammar (§7.2). */
class TreeSitterOutlineTest {
    private val trees = SyntaxTrees(GrammarLoader(Grammar::load))

    @AfterTest
    fun close() = trees.close()

    private fun outline(path: String, source: String): Outline {
        val outline = trees.outline(path, source.trimIndent().toByteArray(Charsets.UTF_8), assertNotNull(Grammar.of(path)))
        return assertNotNull(outline, "grammar for $path did not load: ${trees.failures()}")
    }

    private fun span(outline: Outline, kind: DeclarationKind, name: String): IntRange {
        val entry = outline.entries.singleOrNull { it.kind == kind && it.name == name }
        assertNotNull(entry, "${outline.path} has no $kind '$name' (has ${outline.entries})")
        return entry.from..entry.to
    }

    @Test
    fun `python spans come from the tree and decorators stay outside them`() {
        val outline = outline(
            "pay/api.py",
            """
            from . import util
            import os.path, json as j

            @route("/x")
            def handler(x):
                def inner():
                    pass
                return x

            if TYPE_CHECKING:
                def typed(): ...

            class _Hidden:
                def method(self):
                    return 1
            LIMIT: int = 3
            """,
        )
        assertEquals(IndexTier.Syntax, outline.tier)
        assertTrue(outline.complete)
        assertEquals("pay.api", outline.namespace)
        assertEquals(listOf(".", "json", "os.path"), outline.importTargets, "sorted by line, then name, as at tier 0")
        assertEquals(5..8, span(outline, DeclarationKind.Function, "handler"))
        assertEquals(6..7, span(outline, DeclarationKind.Method, "inner"))
        assertEquals(11..11, span(outline, DeclarationKind.Function, "typed"))
        assertEquals(13..15, span(outline, DeclarationKind.Class, "_Hidden"))
        assertEquals(16..16, span(outline, DeclarationKind.Const, "LIMIT"))
        assertEquals(listOf("handler", "LIMIT"), outline.exports)
    }

    @Test
    fun `typescript multi-line imports, re-exports, require and members`() {
        val outline = outline(
            "src/app.ts",
            """
            import {
              a,
              b,
            } from './lib';
            export { b as c } from './other';
            const fs = require('fs');
            export abstract class Box<T> {
              private open(x: number): void {
                const local = 1;
              }
            }
            interface Shape { area(): number }
            export type Id = string;
            """,
        )
        assertEquals(Language.TypeScript, outline.language)
        assertEquals(listOf("./lib", "./other", "fs"), outline.importTargets)
        assertEquals(1..4, span(outline, DeclarationKind.Import, "./lib"))
        assertEquals(5..5, span(outline, DeclarationKind.Export, "c"))
        assertEquals(7..11, span(outline, DeclarationKind.Class, "Box"))
        assertEquals(8..10, span(outline, DeclarationKind.Method, "open"))
        assertEquals(12..12, span(outline, DeclarationKind.Method, "area"))
        assertEquals(6..6, span(outline, DeclarationKind.Const, "fs"))
        assertTrue(outline.entries.none { it.name == "local" }, "a local binding is not a declaration")
        assertEquals(listOf("c", "Box", "Id"), outline.exports)
    }

    @Test
    fun `tsx and javascript parse with their own grammars`() {
        val tsx = outline("src/View.tsx", "export function View() {\n  return <div className=\"x\" />;\n}\n")
        assertTrue(tsx.complete)
        assertEquals(1..3, span(tsx, DeclarationKind.Function, "View"))
        val js = outline("lib/a.mjs", "export default class A { m() {} }\nfunction f() {}\n")
        assertEquals(listOf("A"), js.exports)
        assertEquals(2..2, span(js, DeclarationKind.Function, "f"))
    }

    @Test
    fun `java members without modifiers and annotated methods are found`() {
        val outline = outline(
            "src/main/java/pay/Router.java",
            """
            package pay;

            import java.util.List;
            import static pay.Util.limit;

            public class Router {
                private static final int LIMIT = 10, MAX = 20;

                @Override
                public String route(String path) {
                    return path;
                }

                void packagePrivate() {}

                enum Mode { A; void run() {} }
            }
            """,
        )
        assertEquals("pay", outline.namespace)
        assertEquals(listOf("java.util.List", "pay.Util.limit"), outline.importTargets)
        assertEquals(6..17, span(outline, DeclarationKind.Class, "Router"))
        assertEquals(10..12, span(outline, DeclarationKind.Method, "route"))
        assertEquals(14..14, span(outline, DeclarationKind.Method, "packagePrivate"))
        assertEquals(16..16, span(outline, DeclarationKind.Method, "run"))
        assertEquals(7..7, span(outline, DeclarationKind.Other, "MAX"))
        assertEquals(listOf("Router"), outline.exports)
    }

    @Test
    fun `kotlin declarations, visibility, backtick names and constructor properties`() {
        val outline = outline(
            "src/main/kotlin/pay/Router.kt",
            """
            package pay.core

            import pay.handlers.*
            import pay.Request as Req

            data class Request(val path: String, val id: Int)

            internal fun hidden() = 1

            class Router {
                fun `route a request`(r: Request): Int {
                    val local = 1
                    return local
                }
            }
            val LIMIT = 3
            """,
        )
        assertEquals("pay.core", outline.namespace)
        assertEquals(listOf("pay.handlers", "pay.Request"), outline.importTargets)
        assertEquals(6..6, span(outline, DeclarationKind.Class, "Request"))
        assertEquals(6..6, span(outline, DeclarationKind.Other, "path"))
        assertEquals(10..15, span(outline, DeclarationKind.Class, "Router"))
        assertEquals(11..14, span(outline, DeclarationKind.Method, "route a request"))
        assertEquals(16..16, span(outline, DeclarationKind.Const, "LIMIT"))
        assertTrue(outline.entries.none { it.name == "local" })
        assertEquals(listOf("Request", "Router", "LIMIT"), outline.exports)
    }

    @Test
    fun `a broken file still yields an outline that is marked incomplete`() {
        val outline = outline("broken.py", "def ok():\n    return 1\n\ndef (:\n")
        assertFalse(outline.complete)
        assertTrue(outline.entries.any { it.name == "ok" })
    }
}
