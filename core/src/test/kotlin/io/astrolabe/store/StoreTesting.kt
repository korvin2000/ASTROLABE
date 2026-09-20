package io.astrolabe.store

import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Shared setup for the store tests: a store without a repository, and a generic row writer. */
internal val TEST_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")

internal val TEST_CLOCK: Clock = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC)

internal val TEST_WORK: WorkId = WorkId("work-1")

internal val TEST_CONTEXT: ContextId = ContextId("ctx-1")

internal val TEST_IDS: Identities = Identities(work = TEST_WORK, attempt = AttemptId("attempt-1"))

/** Thrown from a [CrashPoint] so a test can tell an injected crash from a real failure. */
internal class InjectedCrash(val point: BlobPoint) : RuntimeException("crash at $point")

/** Crashes at exactly one ordering point of [BlobStore.put]. */
internal fun crashAt(point: BlobPoint): FaultPoints = FaultPoints { reached ->
    if (reached == point) throw InjectedCrash(point)
}

/**
 * Opens a store rooted at [root] without going through a git repository, so the storage tests do
 * not pay for a fixture repo they never read.
 */
internal fun openStore(
    root: Path,
    clock: Clock = TEST_CLOCK,
    faults: FaultPoints = FaultPoints.NONE,
): Store {
    val layout = Layout(root).create()
    val lock = ProjectLock.acquire(layout, clock)
    val db = try {
        Db.open(layout).also { Migrations.apply(it, clock) }
    } catch (failure: Throwable) {
        lock.close()
        throw failure
    }
    val identity = RepoIdentity.of(emptyList(), root.toString())
    return Store(identity, layout, db, BlobStore(layout, db, clock, faults), lock)
}

/**
 * Inserts one row with the common columns plus [extra] typed columns. Tests assert over projections,
 * not over a writer that P1 has not built yet, so a generic writer is the honest shape here.
 */
internal fun Store.insertRow(
    table: String,
    vararg extra: Pair<String, Any?>,
    ids: Identities = TEST_IDS,
    createdAt: Instant = TEST_INSTANT,
    body: String = "{}",
) {
    val columns = COMMON_COLUMNS + extra.map { it.first }
    val values = listOf<Any?>(
        ids.work.value,
        ids.attempt.value,
        ids.candidate?.digest?.hex,
        ids.context?.value,
        Migrations.SCHEMA_VERSION,
        createdAt.toString(),
        body,
    ) + extra.map { it.second }
    val placeholders = columns.joinToString(", ") { "?" }
    db.tx {
        it.execute(
            "INSERT INTO $table (${columns.joinToString(", ")}) VALUES ($placeholders)",
            *values.toTypedArray(),
        )
    }
}

private val COMMON_COLUMNS = listOf(
    "work_id",
    "attempt_id",
    "candidate_id",
    "context_id",
    "schema_version",
    "created_at",
    "body",
)
