package io.astrolabe.atlas

import kotlin.test.Test
import kotlin.test.assertEquals

/** P3.2.4: the outline diff behind the impact nudge (§7.4) — signature, visibility, export, removal; bodies never count. */
class DefinitionChangesTest {
    private fun changes(path: String, before: String?, after: String?) =
        DefinitionChanges.of(path, before?.toByteArray(), after?.toByteArray()).map { Triple(it.symbol, it.change, it.public) }

    @Test
    fun `a body-only edit changes no definition, a parameter list does`() {
        val before = "def total(xs):\n    return sum(xs)\n"
        assertEquals(emptyList(), changes("pay/calc.py", before, "def total(xs):\n    return round(sum(xs), 2)\n"))
        assertEquals(listOf(Triple("total", DefinitionChange.Signature, true)), changes("pay/calc.py", before, "def total(xs, places):\n    return sum(xs)\n"))
    }

    @Test
    fun `export and visibility changes are told apart, and removal is reported`() {
        assertEquals(
            listOf(Triple("route", DefinitionChange.Export, true)),
            changes("src/router.ts", "export function route(a: string) {\n  return a\n}\n", "function route(a: string) {\n  return a\n}\n"),
        )
        assertEquals(
            listOf(Triple("helper", DefinitionChange.Visibility, false)),
            changes("src/Util.kt", "internal fun helper(x: Int): Int {\n    return x\n}\n", "private fun helper(x: Int): Int {\n    return x\n}\n"),
        )
        assertEquals(
            listOf(Triple("dispatch", DefinitionChange.Removed, true)),
            changes("src/router.py", "def dispatch(req):\n    return req\n", "def other(req):\n    return req\n").filter { it.first == "dispatch" },
        )
        assertEquals(listOf(Triple("dispatch", DefinitionChange.Removed, true)), changes("src/router.py", "def dispatch(req):\n    return req\n", null))
        assertEquals(emptyList(), changes("src/new.py", null, "def fresh():\n    pass\n"), "a new definition breaks no reference")
    }

    @Test
    fun `a multi-line header is compared up to the line that opens the body`() {
        val before = "fun handle(\n    request: Request,\n): Response {\n    return ok()\n}\n"
        val after = "fun handle(\n    request: Request,\n    deadline: Long,\n): Response {\n    return ok()\n}\n"
        assertEquals(listOf(Triple("handle", DefinitionChange.Signature, true)), changes("src/Handler.kt", before, after))
    }
}
