package io.astrolabe.tool.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P3.1.4 diagnostics parsers: tool recognition from argv, exact error counts, warnings never count, success signatures. */
class DiagnosticsTest {
    private fun parse(tool: DiagnosticTool, resource: String, exit: Int) = DiagnosticsParser.parse(tool, Recorded.text(resource), exit)

    @Test
    fun `recognises the tool through interpreters, package runners and one shell wrapper`() {
        assertEquals(DiagnosticTool.Ruff, DiagnosticsParser.recognise(listOf("ruff", "check", "src")))
        assertEquals(DiagnosticTool.Ruff, DiagnosticsParser.recognise(listOf("/opt/venv/bin/ruff", "check")))
        assertNull(DiagnosticsParser.recognise(listOf("ruff", "format", "--check", "src")), "a formatter is not a checker")
        assertEquals(DiagnosticTool.Mypy, DiagnosticsParser.recognise(listOf("python3", "-m", "mypy", "src")))
        assertEquals(DiagnosticTool.Mypy, DiagnosticsParser.recognise(listOf("C:\\py\\python.exe", "-m", "mypy")))
        assertEquals(DiagnosticTool.Mypy, DiagnosticsParser.recognise(listOf("uv", "run", "mypy", "src")))
        assertEquals(DiagnosticTool.Pyright, DiagnosticsParser.recognise(listOf("npx", "pyright", "src")))
        assertEquals(DiagnosticTool.Pyright, DiagnosticsParser.recognise(listOf("basedpyright")))
        assertEquals(DiagnosticTool.Eslint, DiagnosticsParser.recognise(listOf("npx", "--no-install", "eslint", ".")))
        assertEquals(DiagnosticTool.Eslint, DiagnosticsParser.recognise(listOf("node", "node_modules/eslint/bin/eslint.js", "src")))
        assertEquals(DiagnosticTool.Eslint, DiagnosticsParser.recognise(listOf("node_modules\\.bin\\eslint.cmd", "src")))
        assertEquals(DiagnosticTool.Tsc, DiagnosticsParser.recognise(listOf("tsc", "--noEmit")))
        assertEquals(DiagnosticTool.Tsc, DiagnosticsParser.recognise(listOf("pnpm", "exec", "tsc", "--noEmit", "-p", "web")))
        assertEquals(DiagnosticTool.CargoCheck, DiagnosticsParser.recognise(listOf("cargo", "check", "--all-targets")))
        assertEquals(DiagnosticTool.CargoCheck, DiagnosticsParser.recognise(listOf("cargo", "+nightly", "check")))
        assertNull(DiagnosticsParser.recognise(listOf("cargo", "test")), "cargo test is a test runner, not a checker")
        assertEquals(DiagnosticTool.GoVet, DiagnosticsParser.recognise(listOf("go", "vet", "./...")))
        assertNull(DiagnosticsParser.recognise(listOf("go", "build", "./...")))
        assertEquals(DiagnosticTool.Tsc, DiagnosticsParser.recognise(listOf("/bin/sh", "-c", "npx tsc --noEmit")))
        assertEquals(DiagnosticTool.GoVet, DiagnosticsParser.recognise(listOf("cmd.exe", "/d", "/s", "/c", "go vet ./...")))
        assertNull(DiagnosticsParser.recognise(listOf("/bin/sh", "-c", "cat diag.txt")))
        assertNull(DiagnosticsParser.recognise(listOf("pytest", "-q")))
        assertNull(DiagnosticsParser.recognise(emptyList()))
        assertNull(DiagnosticsParser.parse(listOf("pytest"), "x", 0))
    }

    @Test
    fun `ruff counts violations exactly in the concise and the full format and reads its summary`() {
        val concise = parse(DiagnosticTool.Ruff, "ruff-fail.txt", 1)
        assertEquals(3, concise.errorCount)
        assertEquals(3, concise.summaryErrors)
        assertFalse(concise.successSignature)
        assertEquals(
            listOf("src/shop/cart.py:1:8: `os` imported but unused", "src/shop/cart.py:14:80: Line too long (96 > 88)", "src/shop/pricing.py:7:5: Local variable `total` is assigned to but never used"),
            concise.errors.map { it.render() },
        )
        assertEquals(listOf("F401", "E501", "F841"), concise.diagnostics.map { it.code })

        val full = parse(DiagnosticTool.Ruff, "ruff-full-fail.txt", 1)
        assertEquals(2, full.errorCount)
        assertEquals(Diagnostic("src/shop/cart.py", 14, 80, DiagnosticSeverity.Error, "E501", "Line too long (96 > 88)"), full.diagnostics[1])
        assertEquals(2, full.summaryErrors)

        val clean = parse(DiagnosticTool.Ruff, "ruff-pass.txt", 0)
        assertEquals(0, clean.errorCount)
        assertTrue(clean.successSignature)
        assertFalse(DiagnosticsParser.parse(DiagnosticTool.Ruff, "", 0).successSignature, "ruff is never silently clean")
    }

    @Test
    fun `eslint stylish attaches the file header, keeps warnings out of the error count and is clean when silent`() {
        val failing = parse(DiagnosticTool.Eslint, "eslint-fail.txt", 1)
        assertEquals(2, failing.errorCount)
        assertEquals(1, failing.warningCount)
        assertEquals(2, failing.summaryErrors)
        assertFalse(failing.successSignature)
        assertEquals(Diagnostic("/home/dev/shop/src/cart.js", 3, 7, DiagnosticSeverity.Error, "no-unused-vars", "'total' is assigned a value but never used"), failing.diagnostics[0])
        assertEquals(Diagnostic("/home/dev/shop/src/cart.js", 10, 1, DiagnosticSeverity.Warning, "no-console", "Unexpected console statement"), failing.diagnostics[1])
        assertEquals(Diagnostic("/home/dev/shop/src/pricing.js", 1, 1, DiagnosticSeverity.Error, null, "Parsing error: Unexpected token )"), failing.diagnostics[2])

        val warnings = parse(DiagnosticTool.Eslint, "eslint-warnings.txt", 0)
        assertEquals(0, warnings.errorCount)
        assertTrue(warnings.successSignature, "a summary with 0 errors is the success signature")

        assertTrue(DiagnosticsParser.parse(DiagnosticTool.Eslint, "", 0).successSignature, "silent exit 0 is clean for eslint")
        assertFalse(DiagnosticsParser.parse(DiagnosticTool.Eslint, "", 2).successSignature, "silent but non-zero exit is not")
    }

    @Test
    fun `mypy reads optional columns and codes, ignores notes and warnings, and reads both summaries`() {
        val failing = parse(DiagnosticTool.Mypy, "mypy-fail.txt", 1)
        assertEquals(2, failing.errorCount)
        assertEquals(4, failing.diagnostics.size)
        assertEquals(2, failing.summaryErrors)
        assertFalse(failing.successSignature)
        assertEquals(Diagnostic("src/shop/cart.py", 12, null, DiagnosticSeverity.Error, "return-value", "Incompatible return value type (got \"str\", expected \"int\")"), failing.diagnostics[0])
        assertEquals(DiagnosticSeverity.Note, failing.diagnostics[1].severity)
        assertEquals(Diagnostic("src/shop/pricing.py", 7, 5, DiagnosticSeverity.Error, "arg-type", "Argument 1 to \"discount\" has incompatible type \"None\"; expected \"float\""), failing.diagnostics[2])
        assertEquals(DiagnosticSeverity.Warning, failing.diagnostics[3].severity)
        assertEquals("src/shop/cart.py:12: Incompatible return value type (got \"str\", expected \"int\")", failing.errors[0].render())

        val clean = parse(DiagnosticTool.Mypy, "mypy-pass.txt", 0)
        assertEquals(0, clean.errorCount)
        assertTrue(clean.successSignature)
        assertFalse(DiagnosticsParser.parse(DiagnosticTool.Mypy, "", 0).successSignature, "mypy is never silently clean")
    }

    @Test
    fun `pyright counts errors, keeps warnings and information apart, and its zero-error summary is the signature`() {
        val failing = parse(DiagnosticTool.Pyright, "pyright-fail.txt", 1)
        assertEquals(2, failing.errorCount)
        assertEquals(1, failing.warningCount)
        assertEquals(4, failing.diagnostics.size)
        assertEquals(2, failing.summaryErrors)
        assertFalse(failing.successSignature)
        assertEquals(Diagnostic("/home/dev/shop/src/shop/cart.py", 12, 12, DiagnosticSeverity.Error, null, "Type \"str\" is not assignable to return type \"int\""), failing.diagnostics[0])
        assertEquals("reportArgumentType", failing.diagnostics[2].code)
        assertEquals(DiagnosticSeverity.Note, failing.diagnostics[3].severity)

        val clean = parse(DiagnosticTool.Pyright, "pyright-pass.txt", 0)
        assertEquals(0, clean.errorCount)
        assertTrue(clean.successSignature)
        assertFalse(DiagnosticsParser.parse(DiagnosticTool.Pyright, "", 0).successSignature, "pyright always prints its summary")
    }

    @Test
    fun `tsc reads both location formats, config errors without a location, and is clean when silent`() {
        val plain = parse(DiagnosticTool.Tsc, "tsc-fail.txt", 2)
        assertEquals(2, plain.errorCount)
        assertNull(plain.summaryErrors)
        assertFalse(plain.successSignature)
        assertEquals(Diagnostic("src/cart.ts", 3, 7, DiagnosticSeverity.Error, "TS2322", "Type 'string' is not assignable to type 'number'."), plain.diagnostics[0])

        val pretty = parse(DiagnosticTool.Tsc, "tsc-pretty-fail.txt", 2)
        assertEquals(plain.diagnostics, pretty.diagnostics, "the pretty format parses to the same diagnostics")
        assertEquals(2, pretty.summaryErrors)

        val config = parse(DiagnosticTool.Tsc, "tsc-config-error.txt", 1)
        assertEquals(1, config.errorCount)
        assertEquals(Diagnostic(null, null, null, DiagnosticSeverity.Error, "TS5057", "Cannot find a tsconfig.json file at the specified directory: './web'."), config.diagnostics.single())
        assertEquals("Cannot find a tsconfig.json file at the specified directory: './web'.", config.diagnostics.single().render())

        assertTrue(DiagnosticsParser.parse(DiagnosticTool.Tsc, "", 0).successSignature)
        assertFalse(DiagnosticsParser.parse(DiagnosticTool.Tsc, "", 1).successSignature)
    }

    @Test
    fun `cargo check locates each error and warning at its primary span, and Finished is the signature`() {
        val failing = parse(DiagnosticTool.CargoCheck, "cargo-check-fail.txt", 101)
        assertEquals(2, failing.errorCount)
        assertEquals(1, failing.warningCount)
        assertEquals(3, failing.diagnostics.size, "the `generated 1 warning` and `could not compile` lines are summaries, not diagnostics")
        assertFalse(failing.successSignature)
        assertEquals(Diagnostic("src/pricing.rs", 4, 9, DiagnosticSeverity.Warning, null, "unused variable: `rate`"), failing.diagnostics[0])
        assertEquals(Diagnostic("src/cart.rs", 12, 5, DiagnosticSeverity.Error, "E0308", "mismatched types"), failing.diagnostics[1])
        assertEquals(Diagnostic("src/cart.rs", 20, 9, DiagnosticSeverity.Error, "E0425", "cannot find value `discount` in this scope"), failing.diagnostics[2])
        assertEquals(listOf("src/cart.rs:12:5: mismatched types", "src/cart.rs:20:9: cannot find value `discount` in this scope"), failing.errors.map { it.render() })

        val clean = parse(DiagnosticTool.CargoCheck, "cargo-check-pass.txt", 0)
        assertEquals(0, clean.errorCount)
        assertEquals(1, clean.warningCount)
        assertTrue(clean.successSignature)
        assertFalse(DiagnosticsParser.parse(DiagnosticTool.CargoCheck, "", 0).successSignature, "cargo always prints Finished")
    }

    @Test
    fun `go vet reads package-grouped diagnostics with the optional vet prefix and is clean only when silent`() {
        val failing = parse(DiagnosticTool.GoVet, "go-vet-fail.txt", 2)
        assertEquals(3, failing.errorCount)
        assertFalse(failing.successSignature)
        assertEquals(Diagnostic("cart/cart.go", 12, 2, DiagnosticSeverity.Error, null, "unreachable code"), failing.diagnostics[0])
        assertEquals(Diagnostic("cart/cart.go", 20, 9, DiagnosticSeverity.Error, null, "fmt.Printf format %d has arg s of wrong type string"), failing.diagnostics[1])
        assertEquals(Diagnostic("pricing/pricing.go", 7, 1, DiagnosticSeverity.Error, null, "undefined: discount"), failing.diagnostics[2])

        assertTrue(DiagnosticsParser.parse(DiagnosticTool.GoVet, "\n", 0).successSignature)
        assertFalse(DiagnosticsParser.parse(DiagnosticTool.GoVet, "# example.com/shop\n", 0).successSignature, "output without diagnostics is not the signature")
    }

    @Test
    fun `an exit code alone never becomes a count and colour codes are stripped`() {
        val exitOnly = DiagnosticsParser.parse(listOf("mypy", "src"), "", 1)!!
        assertEquals(0, exitOnly.errorCount)
        assertNull(exitOnly.summaryErrors)
        assertFalse(exitOnly.successSignature)
        val coloured = DiagnosticsParser.parse(DiagnosticTool.Ruff, "\u001B[1msrc/a.py\u001B[0m:1:8: \u001B[1;31mF401\u001B[0m `os` imported but unused\r\nFound 1 error.\r\n", 1)
        assertEquals(1, coloured.errorCount)
        assertEquals("src/a.py:1:8: `os` imported but unused", coloured.errors.single().render())
    }
}
