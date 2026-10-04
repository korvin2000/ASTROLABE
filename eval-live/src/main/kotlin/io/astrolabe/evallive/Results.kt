package io.astrolabe.evallive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The result of one run (`runs/<task>/<model>/r<n>/result.json`). Every quantity the run could not observe is `null`,
 * never 0; the events behind [totals] are in `events.jsonl` next to it.
 */
@Serializable
internal data class RunResult(
    val schema: Int = SCHEMA,
    val task: String,
    val taskClass: String,
    val provider: String,
    val model: String,
    val repeat: Int,
    /** Position in the seeded run order of the bench (1-based). */
    val order: Int,
    val seed: Long,
    val harnessVersion: String,
    val effort: String,
    val maxCells: Int,
    val profileId: String?,
    val contextLimitTokens: Int?,
    val outputHeadroomTokens: Int?,
    val workId: String?,
    val attemptFingerprint: String?,
    val shape: String?,
    val verification: VerificationSetup?,
    /** The campaign outcome (`completed`, `waiting_for_input`, …), or `null` when the attempt did not end with one. */
    val outcome: String?,
    val stopCode: String?,
    val reason: String?,
    /** Why the run could not produce an outcome (a harness or transport failure), or `null`. */
    val failure: String?,
    val cells: Int?,
    val policyDecisions: List<PolicyDecision>,
    val acceptance: AcceptanceResult?,
    /** SHA-256 of the hidden acceptance files the run was judged by. */
    val acceptanceDigest: String,
    val startedAt: String,
    val endedAt: String,
    val attemptWallMillis: Long?,
    val totals: Totals?,
    val eventsDropped: Long?,
    val changedFiles: Int?,
    /**
     * The scripted interruption of a task with `interrupt` (WP-B2), else `null`. The fields above then describe the
     * run as a whole: outcome, stop and ids of the last segment, cells, policy decisions, wall time and totals of both.
     */
    val interrupt: InterruptResult? = null,
    /** False when the workspace was a repository without a commit (WP-B7). */
    val baseCommit: Boolean = true,
    /** The user's message sent while the agent worked (WP-B7 `message`), else `null`. */
    val message: MessageResult? = null,
    /**
     * The two sessions of a task with `reopen` (WP-B7), else `null`; as with [interrupt], the fields above describe the
     * run as a whole and the outcome is the last session's.
     */
    val reopen: ReopenResult? = null,
) {
    companion object {
        const val SCHEMA: Int = 1
    }
}

/** The user's message: due after [afterResponses], delivered after [deliveredAt] responses as contract version [contractVersion]; both `null` when the run ended first. */
@Serializable
internal data class MessageResult(val afterResponses: Int, val text: String, val deliveredAt: Int?, val contractVersion: Int?)

/** A task in two sessions: the first closed after [closedAt] responses (`null` when it ended first and no second ran), both of the same work. */
@Serializable
internal data class ReopenResult(val afterResponses: Int, val closedAt: Int?, val segments: List<SegmentResult>)

/** How the user's constraint reached the agent: after a stop (`cancelResume`), or after the agent ended first (`followUp`). */
@Serializable
internal enum class InterruptMode {
    @SerialName("cancelResume")
    CancelResume,

    @SerialName("followUp")
    FollowUp,
}

/**
 * One scripted interruption: the runner stopped the first segment after [afterResponses] model responses ([atResponse])
 * — or the agent ended before that — and the second segment ran as the Studio's follow-up with [constraint]. [mode] is
 * `null` when the first segment failed and no second one ran.
 */
@Serializable
internal data class InterruptResult(
    val afterResponses: Int,
    val constraint: String,
    val mode: InterruptMode?,
    val atResponse: Int?,
    val segments: List<SegmentResult>,
)

/** One attempt (or session) of an interrupted or reopened run; [totals] count only its own events. */
@Serializable
internal data class SegmentResult(
    val workId: String?,
    val outcome: String?,
    val stopCode: String?,
    val reason: String?,
    val failure: String?,
    val cells: Int?,
    val attemptWallMillis: Long?,
    val totals: Totals?,
    /** The contract version the segment's first open found: 1 for a new work, the stored one for a reopened work. */
    val openedContractVersion: Int? = null,
)

/** `summary.json` (every result) and `summary.csv` (one flat row per run), rewritten after each run. */
internal object Summary {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val compact = Json { ignoreUnknownKeys = true }

    val COLUMNS: List<String> = listOf(
        "order", "task", "class", "provider", "model", "repeat", "outcome", "stop_code", "accepted", "acceptance_exit",
        "attempt_wall_s", "model_requests", "uncached_input", "cache_read", "cache_write", "output", "cost", "cost_priced_part",
        "currency", "cells", "turns", "tool_calls", "changed_files", "failure", "interrupt_mode",
    )

    fun write(out: Path, results: List<RunResult>) {
        val sorted = results.sortedBy { it.order }
        out.resolve("summary.json").writeText(json.encodeToString(ListSerializer(RunResult.serializer()), sorted))
        out.resolve("summary.csv").writeText(buildString {
            appendLine(COLUMNS.joinToString(","))
            for (r in sorted) appendLine(row(r).joinToString(",") { csv(it) })
        })
    }

    fun row(r: RunResult): List<String?> = listOf(
        r.order.toString(), r.task, r.taskClass, r.provider, r.model, r.repeat.toString(), r.outcome, r.stopCode,
        r.acceptance?.passed?.toString(), r.acceptance?.exitCode?.toString(),
        r.attemptWallMillis?.let { "%.1f".format(java.util.Locale.ROOT, it / 1000.0) }, r.totals?.modelRequests?.toString(),
        r.totals?.uncachedInputTokens?.toString(), r.totals?.cacheReadTokens?.toString(), r.totals?.cacheWriteTokens?.toString(),
        r.totals?.outputTokens?.toString(), r.totals?.cost, r.totals?.costPricedPart, r.totals?.currency, r.cells?.toString(),
        r.totals?.turns?.toString(), r.totals?.toolCalls?.toString(), r.changedFiles?.toString(), r.failure, r.interrupt?.mode?.let(::wire),
    )

    /** The name [mode] has in `result.json`. */
    fun wire(mode: InterruptMode): String = InterruptMode.serializer().descriptor.getElementName(mode.ordinal)

    /** An unknown value is an empty cell, never 0. */
    private fun csv(value: String?): String {
        if (value == null) return ""
        val flat = value.replace('\n', ' ').replace('\r', ' ')
        return if (flat.any { it == ',' || it == '"' }) "\"" + flat.replace("\"", "\"\"") + "\"" else flat
    }

    fun encode(result: RunResult): String = json.encodeToString(RunResult.serializer(), result)

    fun read(file: Path): RunResult = compact.decodeFromString(RunResult.serializer(), file.readText())
}
