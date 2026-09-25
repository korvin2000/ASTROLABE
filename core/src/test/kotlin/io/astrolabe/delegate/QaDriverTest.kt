package io.astrolabe.delegate

import com.sun.net.httpserver.HttpServer
import io.astrolabe.Config
import io.astrolabe.Flags
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.CampaignRequest
import io.astrolabe.campaign.Controller
import io.astrolabe.campaign.FinishReceipts
import io.astrolabe.campaign.Transition
import io.astrolabe.cell.WINDOWS
import io.astrolabe.atlas.Atlas
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.store.Store
import io.astrolabe.evidence.InputStability
import io.astrolabe.evidence.SqliteAliases
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.WorkId
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.verify.Scheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P5.3.1: the QA cell drives a fixture product end to end on an isolated candidate; its logs reach the finish receipt. */
class QaDriverTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var server: HttpServer
    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val idGen = FixedIdGen()

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("report.txt", "total 10.05\n")
        repo.commit("fixture product")
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/report") { exchange ->
            val body = "total 10.05".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        repo.close()
    }

    @Test
    fun `a fixture product is exercised end to end on an isolated candidate and its logs reach the finish receipt`() = runTest {
        val config = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, flags = Flags(qaCell = true))
        val request = CampaignRequest(WorkId("W-1"), AttemptId("a1"), "report totals")
        val policy = CampaignPolicy(Tokens(200_000))
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type report.txt")) else Command(listOf("/bin/sh", "-c", "cat report.txt"))
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.Harness, scope = Contracts.TOUCHED))))
        }
        Controller(config, clock, idGen).open(repo.root, request, policy).use { c ->
            val candidate = c.stamper.report().candidateId
            val cli = EntryPoint(QaSurface.Cli, if (WINDOWS) "type report.txt" else "cat report.txt")
            val http = EntryPoint(QaSurface.Http, "GET http://127.0.0.1:${server.address.port}/report")
            val environment = QaEnvironment.IsolatedCandidate(candidate, c.store.layout.candidates.toString(), ExecutionMode.TrustedLocal)
            val packet = QaPacket(
                c.ids, "I1", c.contract.version, candidate, listOf(ReviewCriterion.of(Acceptance.Check("AC-2", "the report total rounds half-up", Origin.User))),
                "report totals round half-up", listOf(cli, http), environment,
            )
            val probes = listOf(
                QaProbe("cli-total", cli, listOf("print the report"), QaExpectation(0, listOf("total 10.05"))),
                QaProbe("http-total", http, listOf("GET /report"), QaExpectation(200, listOf("total 10.05"))),
            )
            val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, c.ids, clock, candidates = c.store.layout.candidates, isolateAll = true)
            val driver = QaDriver(scheduler, c.checks, c.os, TrustedLocalRunner(c.os), c.store.blobs, stateRoot.resolve("qa-logs"))

            assertIs<QaDrive.Refused>(driver.drive(packet, probes, enabled = false), "an optional layer: off unless Flags.qaCell")
            val production = packet.copy(entryPoints = listOf(EntryPoint(QaSurface.Http, "https://prod.example/report")))
            assertTrue(assertIs<QaDrive.Refused>(driver.drive(production, emptyList(), enabled = true)).reason.contains("never drives production"))

            val driven = assertIs<QaDrive.Driven>(driver.drive(packet, probes, c.attempt.config.flags.qaCell))
            assertEquals(emptyList(), driven.gaps)
            assertEquals(listOf(true, true), driven.result.cases.map { it.passed }, driven.result.cases.toString())
            val receipts = SqliteReceipts(c.store, clock)
            for (id in driven.result.receipts) {
                val receipt = receipts.get(id)!!
                assertEquals(InputStability.Isolated, receipt.testedInputs.stability, "QA ran on the exported candidate, never the live workspace")
                assertEquals(candidate, receipt.stampAfter)
                assertTrue(c.store.blobs.exists(receipt.raw!!), "the log blob was published before the receipt row")
            }
            assertTrue(String(c.store.blobs.get(Digest(driven.result.cases[1].artifacts.single()))).contains("HTTP 200"))
            QaRuns.record(c.store, idGen, clock, packet, driven.record)

            c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "fixture: the implementing cell did not run"))
            val finish = FinishReceipts.build(c, emptyList(), emptyMap()) { receipts.get(it) }
            val (_, file) = FinishReceipts.export(c, finish)
            val qa = finish.qa.single()
            assertEquals(environment.label, qa.environment)
            assertEquals(listOf("passed", "passed"), qa.cases.map { it.outcome })
            assertTrue(qa.cases.all { case -> case.artifacts.isNotEmpty() && case.artifacts.all { c.store.blobs.exists(Digest(it)) } })
            assertTrue(finish.checksRun.map { it.receiptId }.containsAll(qa.receipts), "QA receipts are L3 check runs")
            val exported = Files.readString(file)
            qa.cases.flatMap { it.artifacts }.forEach { assertTrue(it in exported, "artifact $it is attached to the exported receipt") }
        }
    }
}
