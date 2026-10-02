package io.astrolabe.eval.audit

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditCliTest {
    @Test fun `the CLI audits an eval-live results directory into audit json and md`(@TempDir root: Path) {
        val results = root.resolve("results-x")
        val run = Files.createDirectories(results.resolve("runs/task/m_one/r1"))
        val log = AuditLog(JournalFormat.Bus).turn(1).call(5_000, 0, 100).turn(2).call(1_100, 4_900, 120).finished("completed")
        Files.write(run.resolve("events.jsonl"), log.lines(), StandardCharsets.UTF_8)
        Files.writeString(run.resolve("result.json"), """{"task": "task", "provider": "openrouter", "model": "m/one", "repeat": 1, "outcome": "completed",
            "acceptance": {"passed": true, "exitCode": 0, "timedOut": false}}""")
        Files.writeString(results.resolve("catalog-snapshot.json"),
            """{"models": [{"provider": "openrouter", "id": "m/one", "prices": {"currency": "USD", "input": 1, "output": 2, "cacheRead": 0.1}}]}""")
        val out = root.resolve("out")

        AuditCli.main(arrayOf(results.toString(), "--out", out.toString()))

        val report = Json.decodeFromString(AuditReport.serializer(), Files.readString(out.resolve("audit.json")))
        val audited = report.runs.single()
        assertEquals("runs/task/m_one/r1/events.jsonl", audited.run.source)
        assertEquals("results-x · openrouter/m/one", audited.run.group)
        assertEquals(MoneyBasis.Estimate, audited.anatomy.basis)
        assertEquals("passed (exit 0)", audited.provenance.external)
        assertEquals(1, report.groups.single().externallyAccepted)
        val md = Files.readString(out.resolve("audit.md"))
        assertTrue(md.contains("## Groups (model × arm)") && md.contains("results-x · openrouter/m/one"))
    }
}
