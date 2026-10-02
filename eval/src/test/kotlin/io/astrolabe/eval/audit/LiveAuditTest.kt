package io.astrolabe.eval.audit

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The B1 control on the live journals of 2026-10-01 (`diags/live-2026-10-01`, beside the repository, never in it): the
 * auditor reproduces F §6.1's 86,7 % hit share within 5 points and F §2.1's anatomy within 2 points. Skipped without them
 * (CI); `-Pastrolabe.audit.live=<dir>` points a worktree at them.
 */
class LiveAuditTest {
    private val dir: Path? = System.getProperty("astrolabe.audit.live")?.takeIf { it.isNotBlank() }?.let(Path::of)

    @Test fun `the live journals reproduce the measured hit share and anatomy`() {
        assumeTrue(dir?.isDirectory() == true, "no live journals at $dir")
        val report = Audit.run(Audit.discover(dir!!))
        fun run(id: String) = report.runs.single { id in it.run.source }

        val long = run("W-letk4")
        assertEquals(0.867, assertNotNull(long.cache.hitShare), 0.05)

        val deepseek = report.prices.single { it.binding.model == "deepseek/deepseek-v4.1-flash" }
        assertEquals(Agreement.Exact, deepseek.agreement)
        for ((c, f) in mapOf(PriceClass.UncachedInput to "0.1396", PriceClass.CacheRead to "0.01396", PriceClass.Output to "0.558")) {
            val fitted = assertNotNull(deepseek.perMillion[c])
            assertTrue(fitted.subtract(BigDecimal(f)).abs() <= BigDecimal(f).movePointLeft(3), "$c: $fitted vs F $f")
        }

        // F §2.1: uncached / cache read / output / of it reasoning, as shares of the run's cost.
        val table = mapOf(
            "W-letk4" to listOf(34.0, 22.2, 43.8, 31.8),
            "W-c4cu4" to listOf(28.0, 23.5, 48.5, 20.3),
            "W-z2lub" to listOf(72.0, 17.0, 13.0, 2.0),
        )
        for ((id, shares) in table) {
            val a = run(id).anatomy
            val measured = listOf(a.uncachedInput, a.cacheRead, a.output, a.reasoning).map { 100 * assertNotNull(it.share) }
            for ((m, f) in measured.zip(shares)) assertTrue(abs(m - f) <= 2.0, "$id: $measured vs F $shares")
        }

        for (r in report.runs) for (w in r.wastes) assertTrue(w.share != null || w.unmeasured != null, "${r.run.source} ${w.waste}: neither measured nor explained")
        println(AuditMarkdown.render(report).lineSequence().take(40).joinToString("\n"))
    }
}
