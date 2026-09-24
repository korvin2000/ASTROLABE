package io.astrolabe.verify

import io.astrolabe.auth.ContentClass
import io.astrolabe.auth.Redaction
import io.astrolabe.auth.RedactionConfig
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.Outcome
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.os.Command
import io.astrolabe.os.EnvPolicy
import io.astrolabe.os.Os
import io.astrolabe.os.ProcStatus
import io.astrolabe.os.SpawnSpec
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import io.astrolabe.tool.run.DiagnosticsParser
import io.astrolabe.tool.run.GenericShaper
import io.astrolabe.tool.run.Runner
import io.astrolabe.tool.run.announceMoved
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * One end-of-turn checker run (§8.1 layer table). [errorLines] are the rendered error diagnostics of a
 * recognised tool ([DiagnosticsParser]: `path:line:col: message`), or the error-shaped lines of the capture
 * for a tool without a parser; [delta] is the set difference against the superseded result of the same
 * check, so "no new errors while failures persist" renders as `no change · still N` (FX-18). [supersedes]
 * names the archived previous result.
 */
public data class CheckerResult(
    val checkId: String,
    val resultId: String,
    val kind: CheckKind,
    val selector: Selector,
    val outcome: Outcome,
    val errorLines: List<String>,
    val delta: Delta?,
    val exit: Int?,
    val stampBefore: CandidateId,
    val stampAfter: CandidateId?,
    val log: Digest?,
    val durationMillis: Long,
    val touched: List<String>,
    val changedPaths: List<String>,
    val supersedes: String? = null,
    val reason: String? = null,
) {
    val errors: Int get() = errorLines.size

    /**
     * The receipt's parsed counts: the error diagnostics when there are any; for a pass, the files given to
     * the checker as `discovered` (D-72: a checker pass has no test count, its evidence of scope is the touched
     * file set the tool was pointed at, and a passed receipt needs parsed counts); otherwise none.
     */
    val counts: Counts? get() = when {
        errors > 0 -> Counts(errors = errors)
        outcome == Outcome.Passed -> Counts(discovered = touched.size) // D-72
        else -> null
    }

    /** The §8.3 line: signal on delta, state the absolute (L2). */
    public fun line(receiptAlias: String? = null): CheckLine {
        val label = when (kind) {
            CheckKind.Type -> "types"
            else -> kind.name.lowercase()
        }
        val state = when (outcome) {
            Outcome.Passed -> CheckState.Green("✓ ${touched.size} ${if (touched.size == 1) "file" else "files"}")
            Outcome.Failed -> CheckState.Red("", errors)
            Outcome.Timeout -> CheckState.Timeout(reason ?: "time box")
            Outcome.NotRun -> CheckState.NotRun
            Outcome.Unavailable -> CheckState.Unavailable(reason ?: "runner missing")
            Outcome.UnknownOutcome -> CheckState.Unavailable("unknown outcome; reconcile before retry")
            else -> CheckState.Inconclusive(reason ?: "no structured evidence")
        }
        return CheckLine(
            label = label,
            scope = if (selector == Selector.Touched) "touched" else null,
            delta = delta,
            state = state,
            stampHash = stampAfter?.hash8,
            receiptAlias = receiptAlias,
            unchangedRed = outcome == Outcome.Failed && delta != null && !delta.changed,
        )
    }
}

/**
 * The synchronous, time-boxed end-of-turn checker (§8.1, TODO P1.7.2): after any mutation, at the step
 * boundary, it invokes the registered type and lint runners directly (D-09) on the touched files and
 * reports Δ against the previous result plus the absolute status, never silence. One time box covers the
 * whole batch: a check the box no longer allows to start is `not_run`; a check that started and was killed
 * at the box is `timeout` (FX-58). Superseded results are archived here and never presented as current.
 *
 * Verdicts come from the tool's own output (P3.1.4, [DiagnosticsParser]): a recognised tool is red with its
 * exact error diagnostics when it reported any or exited non-zero, and green only on exit 0 with zero
 * errors and its success signature. A tool without a parser is red on a non-zero exit or error-shaped
 * output and `inconclusive` on exit 0 (D-50): an exit code alone never becomes a count (§8.3). A runner that
 * cannot start is `unavailable`. A check that moves files is announced like any run (§9.4).
 */
public class Checker(
    private val checks: Checks,
    private val runner: Runner,
    private val os: Os,
    private val stamper: Stamper,
    private val registry: VersionRegistry,
    private val workspace: Workspace,
    private val blobs: BlobStore,
    private val redaction: Redaction,
    private val idGen: IdGen,
    private val ids: Identities,
    private val logsDir: Path,
    private val envAllowlist: Set<String> = RedactionConfig.DEFAULT_ENV_ALLOWLIST,
    private val events: Events? = null,
) {
    private val current = LinkedHashMap<String, CheckerResult>()
    private val archive = LinkedHashMap<String, MutableList<CheckerResult>>()

    /** The latest result of every check that ran at least once, in registry order. */
    public fun latest(): List<CheckerResult> = current.values.toList()

    /** Superseded results of [checkId], oldest first; never rendered as current. */
    public fun history(checkId: String): List<CheckerResult> = archive[checkId]?.toList() ?: emptyList()

    /** The `── Checks ──` block for `[A]` (P1.8.3) at [stampNow]. */
    public fun render(stampNow: CandidateId?): String = ChecksRender.render(stampNow, current.values.map { it.line() })

    /**
     * Runs every end-of-turn type/lint/syntax check on [touched] within one time box of [timeBoxSeconds].
     * Nothing touched ⇒ nothing to check.
     */
    public fun run(touched: Collection<String>, timeBoxSeconds: Long = 20): List<CheckerResult> {
        require(timeBoxSeconds > 0) { "timeBoxSeconds must be positive" }
        val paths = touched.map { it.replace('\\', '/') }.distinct().sorted()
        if (paths.isEmpty()) return emptyList()
        val candidates = checks.byTrigger(Trigger.EndOfTurn).filter { it.command != null && it.kind in INLINE_KINDS }
        val deadline = System.nanoTime() + timeBoxSeconds * NANOS_PER_SECOND
        return candidates.map { check ->
            val remainingNanos = deadline - System.nanoTime()
            val result = if (remainingNanos <= 0) {
                notRun(check, paths, "time box of ${timeBoxSeconds}s exhausted before dispatch")
            } else {
                execute(check, paths, (remainingNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND)
            }
            record(check, result)
        }
    }

    private fun execute(check: Check, touched: List<String>, remainingSeconds: Long): CheckerResult {
        val argv = argvFor(check, touched)
        val started = System.nanoTime()
        val before = stamper.report()
        events?.emit(AgentEvent.Check.Started(ids, check.id))
        val log = logPath(check.id)
        val spec = SpawnSpec(
            Command.Argv(argv), check.command!!.cwd?.let { workspace.root.resolve(it) } ?: workspace.root, log,
            EnvPolicy(inheritedNames = envAllowlist, extra = mapOf("CI" to "1", "NO_COLOR" to "1")), deadlineSeconds = remainingSeconds,
        )
        var proc = try {
            runner.start(spec)
        } catch (failure: IOException) {
            return CheckerResult(check.id, idGen.next("chk"), check.kind, check.selector, Outcome.Unavailable, emptyList(), null, null, before.candidateId, before.candidateId, null, elapsed(started), touched, emptyList(), reason = "cannot start ${argv.first()}: ${failure.message}")
        }
        val output = java.io.ByteArrayOutputStream()
        var cursor = 0L
        val outcomeOverride: Outcome? = try {
            while (!proc.status.isTerminal) {
                val poll = os.poll(proc, cursor, minOf(POLL_SLICE_SECONDS, remainingSeconds + 1))
                output.write(poll.newBytes)
                cursor = poll.nextCursorBytes
                proc = proc.copy(status = poll.status)
            }
            output.write(os.poll(proc, cursor, 0).newBytes)
            null
        } catch (failure: IOException) {
            Outcome.UnknownOutcome
        }
        val after = stamper.report()
        val changed = announceMoved(registry, before, after, "check ${check.id}")
        val text = redaction.applyBytes(output.toByteArray(), ContentClass.ReusableEvidence).text
        val blob = blobs.put(text.toByteArray(Charsets.UTF_8), BlobKind.LOG, ids)
        val exit = (proc.status as? ProcStatus.Exited)?.exitCode
        val parsed = DiagnosticsParser.parse(argv, text, exit)
        val errorLines = parsed?.errors?.map { it.render() } ?: GenericShaper.errorShapedLines(text)
        val (outcome, reason) = when {
            outcomeOverride != null -> outcomeOverride to "the observation was lost; reconcile before retry"
            proc.status == ProcStatus.DeadlineExceeded -> Outcome.Timeout to "started, killed at the ${remainingSeconds}s time box"
            proc.status is ProcStatus.Lost -> Outcome.UnknownOutcome to "process lost"
            proc.status is ProcStatus.Cancelled -> Outcome.UnknownOutcome to "cancelled"
            // A recognised tool: its diagnostics and summary decide; exit 0 alone is never green (§8.3).
            parsed != null && (exit != 0 || parsed.errorCount > 0 || (parsed.summaryErrors ?: 0) > 0) -> Outcome.Failed to null
            parsed != null && parsed.successSignature -> Outcome.Passed to null
            parsed != null -> Outcome.Inconclusive to "exit 0 · ${parsed.tool.id} printed no success signature"
            exit != null && exit != 0 -> Outcome.Failed to null
            errorLines.isNotEmpty() -> Outcome.Failed to null
            else -> Outcome.Inconclusive to "exit 0 · no diagnostics parser for ${argv.first()}"
        }
        return CheckerResult(check.id, idGen.next("chk"), check.kind, check.selector, outcome, errorLines, null, exit, before.candidateId, after.candidateId, blob, elapsed(started), touched, changed, reason = reason)
    }

    private fun notRun(check: Check, touched: List<String>, reason: String): CheckerResult {
        val stamp = stamper.report().candidateId
        return CheckerResult(check.id, idGen.next("chk"), check.kind, check.selector, Outcome.NotRun, emptyList(), null, null, stamp, stamp, null, 0, touched, emptyList(), reason = reason)
    }

    /** Δ against the superseded result, archive it, and make the new one the check's current result. */
    private fun record(check: Check, fresh: CheckerResult): CheckerResult {
        val previous = current[check.id]
        val delta = previous?.let { old ->
            val oldSet = old.errorLines.map { it.trim() }.toSet()
            val newSet = fresh.errorLines.map { it.trim() }.toSet()
            Delta(added = (newSet - oldSet).size, removed = (oldSet - newSet).size)
        }
        val result = fresh.copy(delta = delta, supersedes = previous?.resultId)
        previous?.let { archive.getOrPut(check.id) { ArrayList() } += it }
        current[check.id] = result
        checks.record(
            check.id,
            LastResult(
                result.resultId, result.stampAfter ?: result.stampBefore, check.definitionVersion, result.outcome,
                result.counts, Applicability.Current,
            ),
        )
        events?.emit(AgentEvent.Check.Finished(ids, check.id, result.resultId, result.outcome.name.lowercase()))
        return result
    }

    private fun logPath(checkId: String): Path {
        Files.createDirectories(logsDir)
        return logsDir.resolve("${checkId}-${idGen.next("log")}.log")
    }

    private fun elapsed(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    public companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val POLL_SLICE_SECONDS = 5L
        private val INLINE_KINDS = setOf(CheckKind.Type, CheckKind.Lint, CheckKind.Syntax)

        /** The runner's argv: a `touched` selector appends the touched paths, sorted; other selectors run as declared. */
        @JvmStatic
        public fun argvFor(check: Check, touched: Collection<String>): List<String> {
            val command = requireNotNull(check.command) { "check ${check.id} declares no command" }
            return if (check.selector == Selector.Touched) command.argv + touched.map { it.replace('\\', '/') }.distinct().sorted() else command.argv
        }
    }
}
