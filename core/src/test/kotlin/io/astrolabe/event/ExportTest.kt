package io.astrolabe.event

import io.astrolabe.store.openStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** P0.4.3: `exports/` is a derived, diffable, deterministic view of the store (§4, risk 19). */
class ExportTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `every view is written once, with stable names`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val written = Export.write(
                Views(store),
                SEEDED_WORK,
                store.layout.exports,
                contexts = listOf(SEEDED_CONTEXT),
            )

            assertEquals(
                listOf(
                    "contract.json",
                    "ledger.json",
                    "checks.json",
                    "budget.json",
                    "receipts.json",
                    "finish-receipt.json",
                    "register-${SEEDED_CONTEXT.value}.json",
                    "workset-${SEEDED_CONTEXT.value}.json",
                    Export.SUMMARY,
                ),
                written.map { it.fileName.toString() },
            )
            written.forEach { assertTrue(Files.exists(it), "$it was not written") }
        }
    }

    @Test
    fun `writing twice over unchanged rows produces byte-identical files`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val views = Views(store)
            val first = root.resolve("exports-first")
            val second = root.resolve("exports-second")

            val a = Export.write(views, SEEDED_WORK, first, contexts = listOf(SEEDED_CONTEXT))
            val b = Export.write(views, SEEDED_WORK, second, contexts = listOf(SEEDED_CONTEXT))

            assertEquals(a.map { it.fileName.toString() }, b.map { it.fileName.toString() })
            for ((left, right) in a.zip(b)) {
                assertContentEquals(
                    Files.readAllBytes(left),
                    Files.readAllBytes(right),
                    "${left.fileName} is not deterministic",
                )
            }

            // And rewriting in place is a no-op for a diff.
            val rewritten = Export.write(views, SEEDED_WORK, first, contexts = listOf(SEEDED_CONTEXT))
            for ((original, again) in a.zip(rewritten)) {
                assertContentEquals(Files.readAllBytes(original), Files.readAllBytes(again))
            }
        }
    }

    @Test
    fun `exports are LF-terminated UTF-8 and carry no value that is not in a row`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val written = Export.write(Views(store), SEEDED_WORK, store.layout.exports)

            for (file in written) {
                val bytes = Files.readAllBytes(file)
                assertFalse(bytes.toList().contains('\r'.code.toByte()), "${file.fileName} must use LF endings")
                assertEquals('\n'.code.toByte(), bytes.last(), "${file.fileName} must end with a newline")
            }

            val summary = String(Files.readAllBytes(store.layout.exports.resolve(Export.SUMMARY)), StandardCharsets.UTF_8)
            assertTrue(summary.startsWith("# ${SEEDED_WORK.value}\n"), summary.take(80))
            assertTrue(summary.contains("- contract versions: 2 (latest v2)"), summary)
            assertTrue(summary.contains("- receipts: 3"), summary)
            assertTrue(summary.contains("- `check-unit`: 2"), summary)
            assertTrue(summary.contains("- invocations: 2"), summary)
            assertTrue(summary.contains("- `packet-finish`"), summary)
        }
    }

    @Test
    fun `an empty store still exports a complete, readable set of views`() {
        openStore(root).use { store ->
            val written = Export.write(Views(store), SEEDED_WORK, store.layout.exports)
            assertEquals(7, written.size, "six views plus the summary")

            val summary = String(Files.readAllBytes(store.layout.exports.resolve(Export.SUMMARY)), StandardCharsets.UTF_8)
            assertTrue(summary.contains("- contract versions: 0"), summary)
            assertTrue(summary.contains("- not issued"), summary)
            assertTrue(summary.contains("- none exported"), summary)
        }
    }

    @Test
    fun `the exported JSON is the projection, indented and ordered`() {
        openStore(root).use { store ->
            store.seedCampaign()
            Export.write(Views(store), SEEDED_WORK, store.layout.exports)

            val checks = String(
                Files.readAllBytes(store.layout.exports.resolve("checks.json")),
                StandardCharsets.UTF_8,
            )
            assertTrue(checks.contains("\"work\": \"${SEEDED_WORK.value}\""), checks.take(120))
            assertTrue(
                checks.indexOf("\"check-build\"") < checks.indexOf("\"check-unit\""),
                "check groups keep the projection's order",
            )
            assertTrue(checks.contains("\n  \""), "pretty printed with two spaces")
        }
    }
}
