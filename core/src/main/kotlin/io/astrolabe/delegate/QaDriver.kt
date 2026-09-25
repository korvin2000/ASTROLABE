package io.astrolabe.delegate

import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.InputStability
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.Receipt
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.IdGen
import io.astrolabe.id.WorkId
import io.astrolabe.os.Os
import io.astrolabe.os.ProcStatus
import io.astrolabe.os.SpawnSpec
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.tool.run.ConfinedRunner
import io.astrolabe.tool.run.Runner
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.Checks
import io.astrolabe.verify.CostClass
import io.astrolabe.verify.Executed
import io.astrolabe.verify.Layer
import io.astrolabe.verify.Layers
import io.astrolabe.verify.Scheduler
import io.astrolabe.verify.Selector
import io.astrolabe.verify.Trigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import io.astrolabe.os.Command as Launch

/** What a QA case expects of the product: the exit code (CLI) or HTTP status, and text its output must contain. */
public data class QaExpectation @JvmOverloads constructor(val code: Int? = null, val contains: List<String> = emptyList()) {
    init {
        require(code != null || contains.isNotEmpty()) { "an expectation checks a code or some output" }
    }

    val text: String get() = (listOfNotNull(code?.let { "code $it" }) + contains.map { "output contains '$it'" }).joinToString(" and ")

    public fun met(code: Int?, output: String): Boolean = (this.code == null || this.code == code) && contains.all { it in output }
}

/**
 * One case the QA cell drives (§10.3): a CLI entry point's target is one shell command line run in the disposable
 * root; an HTTP entry point's target is `[METHOD ]url` on a loopback address (D-200).
 */
public data class QaProbe(val id: String, val entryPoint: EntryPoint, val steps: List<String>, val expected: QaExpectation) {
    init {
        require(id.isNotBlank() && id.none { it.isWhitespace() }) { "a QA case id is one non-blank token" }
    }
}

/** What [QaDriver.drive] did: the validated QA packet out and its record, or why the run could not start. */
public sealed interface QaDrive {
    public data class Driven(val result: QaResult, val record: QaRunRecord, val gaps: List<String>) : QaDrive

    public data class Refused(val reason: String) : QaDrive
}

/**
 * The L3 driver (§10.3, §8.2 L3, P5.3.1). Each probe is a [CheckKind.Product] check of the [Layer.ProductUse] row, run
 * through the [scheduler] — which must isolate every check on an exported candidate (`isolateAll`) — so the receipt
 * row is the scheduler's (one writer, L9) and the transcript blob is published before it (artifact before row). The
 * product runs only in that copy or under a [ConfinedRunner], never in the live workspace; a case whose receipt is
 * not isolated or not on the packet's candidate is a gap, and the result is checked by [QaCell.validate] before its
 * record is kept for the finish receipt.
 */
public class QaDriver @JvmOverloads constructor(
    private val scheduler: Scheduler,
    private val checks: Checks,
    private val os: Os,
    private val runner: Runner,
    private val blobs: BlobStore,
    /** Store-owned directory for the product's process logs. */
    private val logs: Path,
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build(),
    private val deadlineSeconds: Long = 60,
) {
    init {
        require(deadlineSeconds > 0) { "a QA case has a positive deadline" }
    }

    /** Runs [probes] for [packet] when `Flags.qaCell` ([enabled]) admits it; every probe names one of the packet's entry points. */
    @JvmOverloads
    public suspend fun drive(packet: QaPacket, probes: List<QaProbe>, enabled: Boolean = QaCell.AVAILABLE): QaDrive {
        val admitted = when (val admission = QaCell.admit(packet, enabled)) {
            is QaAdmission.Refused -> return QaDrive.Refused(admission.reason)
            is QaAdmission.Ready -> admission.packet
        }
        val environment = admitted.environment
        if (environment is QaEnvironment.Confined && runner !is ConfinedRunner) return QaDrive.Refused("the packet requires ${environment.label}, the runner is ${runner.mode}")
        if (environment is QaEnvironment.IsolatedCandidate && environment.mode != runner.mode) return QaDrive.Refused("the packet requires ${environment.label}, the runner is ${runner.mode}")
        probes.firstOrNull { it.entryPoint !in admitted.entryPoints }?.let { return QaDrive.Refused("case ${it.id}: ${it.entryPoint.target} is not an entry point of the packet") }
        require(probes.map { it.id }.toSet().size == probes.size) { "QA case ids are unique" }

        val byCheck = probes.associateBy { checkIdOf(admitted, it) }
        byCheck.forEach { (id, probe) -> if (checks[id] == null) checks.register(checkOf(id, probe)) }
        val selected = Layers.select(Layer.ProductUse, checks, emptyList()) { it.id in byCheck }.run
        val gaps = ArrayList<String>()
        val cases = ArrayList<QaCase>()
        val receipts = ArrayList<String>()
        for (check in selected) {
            val probe = byCheck.getValue(check.id)
            var observed = ""
            val receipt = scheduler.runCheck(check, admitted.contractVersion) { root ->
                val (executed, output) = execute(admitted, probe, root)
                observed = output
                executed
            }
            receipts += receipt.receiptId
            if (environment is QaEnvironment.IsolatedCandidate && receipt.testedInputs.stability != InputStability.Isolated) {
                gaps += "case ${probe.id}: ran ${receipt.testedInputs.stability.name.lowercase()}, not on an isolated candidate"
            }
            if (receipt.stampAfter != admitted.candidate) gaps += "case ${probe.id}: ran @${receipt.stampAfter.hash8}, the packet names @${admitted.candidate.hash8}"
            cases += QaCase(probe.id, probe.entryPoint, probe.steps, probe.expected.text, observed, passedOf(receipt), listOfNotNull(receipt.raw?.hex))
        }
        val unresolved = probes.filter { checkIdOf(admitted, it) !in selected.map(Check::id) }.map { "case ${it.id}: not selected by the L3 row" }
        val result = QaResult(admitted.ids, admitted.incrementId, admitted.contractVersion, admitted.candidate, environment, receipts, cases, unresolved)
        gaps += QaCell.validate(result, admitted, ::published)
        return QaDrive.Driven(result, QaRunRecord.of(admitted, result, gaps), gaps)
    }

    private fun published(hex: String): Boolean = try {
        blobs.exists(Digest(hex))
    } catch (malformed: IllegalArgumentException) {
        false
    }

    private suspend fun execute(packet: QaPacket, probe: QaProbe, root: Path): Pair<Executed, String> = when (probe.entryPoint.surface) {
        QaSurface.Cli -> cli(packet, probe, root)
        QaSurface.Http -> http(packet, probe)
        QaSurface.Browser -> error("browser surfaces are refused at admission")
    }

    private suspend fun cli(packet: QaPacket, probe: QaProbe, root: Path): Pair<Executed, String> = withContext(Dispatchers.IO) {
        val target = probe.entryPoint.target
        val output = StringBuilder()
        val status = try {
            Files.createDirectories(logs)
            val log = Files.createTempFile(logs, "qa-${probe.id}-", ".log")
            var proc = runner.start(SpawnSpec(Launch.Shell(target), root, log, deadlineSeconds = deadlineSeconds))
            var cursor = 0L
            while (!proc.status.isTerminal) {
                val poll = os.poll(proc, cursor, deadlineSeconds)
                output.append(poll.text())
                cursor = poll.nextCursorBytes
                proc = proc.copy(status = poll.status)
            }
            output.append(os.poll(proc, cursor, 0).text())
            proc.status
        } catch (failure: IOException) {
            output.append("cannot start: ").append(failure.message)
            null
        }
        val exit = (status as? ProcStatus.Exited)?.exitCode
        val transcript = "$ $target\n$output\n[${status?.let { if (it is ProcStatus.Exited) "exit ${it.exitCode}" else it::class.simpleName?.lowercase() } ?: "not started"}]\n"
        val outcome = when {
            status == null -> Outcome.Unavailable
            status == ProcStatus.DeadlineExceeded -> Outcome.Timeout
            exit == null -> Outcome.InfraError
            probe.expected.met(exit, output.toString()) -> Outcome.Passed
            else -> Outcome.Failed
        }
        executed(packet, listOf(target), root.toString(), true, exit, outcome, transcript) to output.toString()
    }

    private suspend fun http(packet: QaPacket, probe: QaProbe): Pair<Executed, String> = withContext(Dispatchers.IO) {
        val target = probe.entryPoint.target.trim()
        val method = target.substringBefore(' ', "GET").uppercase()
        val url = target.substringAfterLast(' ')
        val (code, body) = try {
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(deadlineSeconds)).method(method, HttpRequest.BodyPublishers.noBody()).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() to response.body().orEmpty()
        } catch (failure: IOException) {
            null to "cannot reach: ${failure.message}"
        } catch (malformed: IllegalArgumentException) {
            null to "malformed request: ${malformed.message}"
        }
        val transcript = "$method $url\n${code?.let { "HTTP $it" } ?: "no response"}\n\n$body\n"
        val outcome = when {
            code == null -> Outcome.Unavailable
            probe.expected.met(code, body) -> Outcome.Passed
            else -> Outcome.Failed
        }
        executed(packet, listOf(method, url), null, false, code, outcome, transcript) to body
    }

    private fun executed(packet: QaPacket, command: List<String>, cwd: String?, shell: Boolean, exit: Int?, outcome: Outcome, transcript: String): Executed {
        // Artifact before row: the scheduler records the receipt only after this blob is published.
        val raw = blobs.put(transcript.toByteArray(Charsets.UTF_8), BlobKind.LOG, packet.ids)
        val counts = when (outcome) {
            Outcome.Passed -> Counts(passed = 1, discovered = 1)
            Outcome.Failed -> Counts(failed = 1, discovered = 1)
            else -> null
        }
        return Executed(command, cwd, shell, exit, outcome, counts, raw)
    }

    private fun passedOf(receipt: Receipt): Boolean? = when (receipt.outcome) {
        Outcome.Passed -> true
        Outcome.Failed -> false
        else -> null
    }

    private fun checkIdOf(packet: QaPacket, probe: QaProbe): String = "CHK-qa-${packet.incrementId}-${probe.id}"

    private fun checkOf(id: String, probe: QaProbe): Check = Check(
        id, CheckKind.Product, Selector.Named(Command(listOf(probe.entryPoint.surface.wire, probe.entryPoint.target))), Closure.Unknown,
        CostClass.Expensive, Trigger.OnDemand, command = Command(listOf(probe.entryPoint.surface.wire, probe.entryPoint.target)),
        parserPolicy = "qa/1: ${probe.expected.text}",
    )
}

/** One QA case as the finish receipt reports it: its outcome word, the receipt behind it and its log blobs. */
@Serializable
public data class QaCaseLine(
    val id: String,
    val surface: String,
    val target: String,
    val expected: String,
    val observed: String,
    /** `passed`, `failed` or `undecided` (an environment outcome, not a product verdict). */
    val outcome: String,
    val artifacts: List<String>,
)

/** A QA run as stored (`packets`, kind [QaRuns.KIND]) and attached to the finish receipt (§5.9, §10.3). */
@Serializable
public data class QaRunRecord(
    val incrementId: String,
    val contractVersion: Int,
    val candidate: String,
    val environment: String,
    val behaviour: String,
    val receipts: List<String>,
    val cases: List<QaCaseLine>,
    val unresolved: List<String>,
    /** Validator gaps (§3.7): a run with gaps is reported, never read as passing product use. */
    val gaps: List<String>,
) {
    public companion object {
        @JvmStatic
        public fun of(packet: QaPacket, result: QaResult, gaps: List<String>): QaRunRecord = QaRunRecord(
            result.incrementId, result.contractVersion, result.candidate.digest.hex, result.environment.label, packet.behaviour, result.receipts.toList(),
            result.cases.map { c ->
                val outcome = when (c.passed) {
                    true -> "passed"
                    false -> "failed"
                    null -> "undecided"
                }
                QaCaseLine(c.id, c.entryPoint.surface.wire, c.entryPoint.target, c.expected, c.observed.take(OBSERVED_CHARS), outcome, c.artifacts.toList())
            },
            result.unresolved.toList(), gaps.toList(),
        )

        /** The observed text kept in the record; the full transcript is the case's log blob. */
        public const val OBSERVED_CHARS: Int = 400
    }
}

/** QA run records of an attempt (`packets`, kind [KIND]); the controller's finish reads them into the receipt. */
public object QaRuns {
    public const val KIND: String = "qa-run"

    private val JSON = Json { encodeDefaults = true }

    @JvmStatic
    public fun record(store: Store, idGen: IdGen, clock: Clock, packet: QaPacket, record: QaRunRecord): String = store.db.tx { tx ->
        val id = idGen.next("qa")
        tx.execute(
            "INSERT INTO packets (id, work_id, attempt_id, candidate_id, context_id, kind, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, packet.ids.work, packet.ids.attempt, packet.candidate, packet.ids.context, KIND, Migrations.SCHEMA_VERSION, clock.instant(),
            JSON.encodeToString(QaRunRecord.serializer(), record),
        )
        id
    }

    @JvmStatic
    public fun forAttempt(store: Store, work: WorkId, attempt: AttemptId): List<QaRunRecord> = store.db.query(
        "SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid", work, attempt, KIND,
    ) { JSON.decodeFromString(QaRunRecord.serializer(), it.string("body")) }
}
