package io.astrolabe.delegate

import io.astrolabe.auth.ExecutionMode
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Origin
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.os.Proc
import io.astrolabe.os.SpawnSpec
import io.astrolabe.tool.run.ConfinedRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P4.4.4: the QA cell's packet contract and validator; the cell itself stays masked until P5.3. */
class QaCellTest {
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val candidate = CandidateId(Digest.ofUtf8("c1"))
    private val cli = EntryPoint(QaSurface.Cli, "app report --total")
    private val environment = QaEnvironment.IsolatedCandidate(candidate, "candidates/qa-1", ExecutionMode.TrustedLocal)
    private val packet = QaPacket(
        ids, "I1", 1, candidate, listOf(ReviewCriterion.of(Acceptance.Check("AC-2", "the report total rounds half-up", Origin.User))),
        "report totals round half-up", listOf(cli), environment,
    )

    private fun result(cases: List<QaCase>, receipts: List<String> = listOf("rcpt-9"), env: QaEnvironment = environment) =
        QaResult(ids, "I1", 1, candidate, env, receipts, cases, emptyList())

    @Test
    fun `the QA contract needs a disposable environment and sits behind its flag`() {
        val confined = object : ConfinedRunner {
            override val backend: String = "bwrap"
            override val mode: ExecutionMode = ExecutionMode.Confined
            override fun start(spec: SpawnSpec): Proc = error("unused")
        }
        assertEquals(QaEnvironment.Confined("bwrap"), QaEnvironment.of(confined, null, null))
        val local = object : io.astrolabe.tool.run.Runner {
            override val mode: ExecutionMode = ExecutionMode.TrustedLocal
            override fun start(spec: SpawnSpec): Proc = error("unused")
        }
        assertNull(QaEnvironment.of(local, null, null), "trusted-local on the live workspace is never disposable")
        assertEquals(environment, QaEnvironment.of(local, candidate, "candidates/qa-1"))
        assertEquals("trusted-local:candidate@${candidate.hash8}", environment.label, "the isolated copy is labelled trusted-local")
        assertTrue(assertIs<QaAdmission.Refused>(QaCell.admit(packet, available = false)).reason.contains("Flags.qaCell"))
        assertIs<QaAdmission.Ready>(QaCell.admit(packet))
        assertIs<QaAdmission.Refused>(QaCell.admit(packet.copy(entryPoints = listOf(EntryPoint(QaSurface.Browser, "http://127.0.0.1/app")))), "browser is out of scope")
        val elsewhere = packet.copy(environment = environment.copy(candidate = CandidateId(Digest.ofUtf8("other"))))
        assertIs<QaAdmission.Refused>(QaCell.admit(elsewhere, available = true))
        assertEquals(null, ChildKind.of("qa"), "QA runs as the L3 row, not as a model-dispatched child (D-200)")
    }

    @Test
    fun `the result validator binds cases to the packet's entry points, artifacts and environment`() {
        val published = setOf("blob-log-1")
        val ok = result(listOf(QaCase("case-1", cli, listOf("run app report --total"), "total 10.05", "total 10.05", true, listOf("blob-log-1"))))
        assertEquals(emptyList(), QaCell.validate(ok, packet, published::contains))
        val bad = result(
            listOf(
                QaCase("case-1", EntryPoint(QaSurface.Http, "https://prod.example/report"), emptyList(), "200", "200", true, listOf("blob-missing")),
                QaCase("case-2", cli, emptyList(), "total 10.05", "total 10.04", false, emptyList()),
            ),
            receipts = emptyList(), env = QaEnvironment.Confined("bwrap"),
        )
        val gaps = QaCell.validate(bad, packet, published::contains)
        assertTrue(gaps.any { "requires trusted-local:candidate@" in it }, gaps.toString())
        assertTrue(gaps.any { "cases ran without receipts" in it }, gaps.toString())
        assertTrue(gaps.any { "is not an entry point of the packet" in it }, gaps.toString())
        assertTrue(gaps.any { "artifact blob-missing is not a published blob" in it }, gaps.toString())
        assertTrue(gaps.any { "case-2: a decided case carries its screenshot or log" in it }, gaps.toString())
    }

    @Test
    fun `the QA packet parses at the boundary with the packet's binding and the cell's own receipts`() {
        val text = """{"cases":[{"id":"case-1","entryPoint":{"surface":"cli","target":"app report --total"},"steps":["run it"],"expected":"total 10.05","observed":"total 10.05","passed":true,"artifacts":["blob-log-1"]},""" +
            """{"id":"case-2","entryPoint":{"surface":"cli","target":"app report --total"},"expected":"no crash","passed":null}],"unresolved":["the browser surface is not reachable"]}"""
        val parsed = assertIs<QaParsed.Parsed>(QaCell.parse(text, packet, listOf("rcpt-9"))).result
        assertEquals(result(parsed.cases).copy(unresolved = listOf("the browser surface is not reachable")), parsed, "candidate, version and environment come from the packet")
        assertNull(parsed.cases[1].passed, "an undecided case is an environment outcome")
        assertEquals(emptyList(), QaCell.validate(parsed, packet, setOf("blob-log-1")::contains))
        assertIs<QaParsed.Gaps>(QaCell.parse("""{"cases":[{"id":"x","expected":"y"}]}""", packet, emptyList()))
        assertIs<QaParsed.Gaps>(QaCell.parse("no packet", packet, emptyList()))
    }
}
