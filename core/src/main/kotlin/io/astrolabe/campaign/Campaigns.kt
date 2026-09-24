package io.astrolabe.campaign

import io.astrolabe.contract.Increment
import io.astrolabe.contract.LedgerEntry
import io.astrolabe.graph.Sizing
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.json.Json
import java.time.Clock

/**
 * Persistence of [CampaignState]; the controller is the one writer (L9). A save must extend the stored state by
 * exactly one transition, so a second writer or a replayed older state is refused instead of overwriting.
 */
public interface Campaigns {
    public fun save(state: CampaignState)

    public fun load(work: WorkId, attempt: AttemptId): CampaignState?
}

/** Thrown when a save does not extend the stored campaign state by one transition. */
public class StaleCampaignState(message: String) : IllegalStateException(message)

public class InMemoryCampaigns : Campaigns {
    private val states = HashMap<Pair<WorkId, AttemptId>, CampaignState>()

    @Synchronized
    override fun save(state: CampaignState) {
        extends(states[state.work to state.attempt]?.seq, state)
        states[state.work to state.attempt] = state
    }

    @Synchronized
    override fun load(work: WorkId, attempt: AttemptId): CampaignState? = states[work to attempt]
}

/**
 * Campaign state in the store: the `campaigns` row holds the full record, and the `increments` and `ledger` rows
 * — the projections [io.astrolabe.event.Views.ledger] reads — are rewritten from it in the same transaction, so
 * the ledger on disk is always the one of the stored state.
 */
public class SqliteCampaigns(private val store: Store, private val clock: Clock) : Campaigns {
    override fun save(state: CampaignState): Unit = store.db.tx { tx ->
        val stored = tx.query("SELECT seq FROM campaigns WHERE work_id = ? AND attempt_id = ?", state.work, state.attempt) { it.long("seq") }.firstOrNull()
        extends(stored, state)
        val now = clock.instant()
        tx.execute(
            "INSERT OR REPLACE INTO campaigns (work_id, attempt_id, candidate_id, context_id, phase, outcome, seq, schema_version, created_at, body) VALUES (?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?)",
            state.work, state.attempt, state.phase.name, state.outcome?.wire, state.seq, Migrations.SCHEMA_VERSION, now,
            JSON.encodeToString(CampaignState.serializer(), state),
        )
        for (increment in state.graph.increments) tx.execute(
            "INSERT OR REPLACE INTO increments (id, work_id, attempt_id, candidate_id, context_id, status, schema_version, created_at, body) VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, ?)",
            increment.id, state.work, state.attempt, increment.status.name, Migrations.SCHEMA_VERSION, now,
            JSON.encodeToString(Increment.serializer(), increment),
        )
        // P2.1.4: one sizing row per (increment, cell), holding the increment's sizing as of that cell's end.
        for (increment in state.graph.increments) increment.cells.lastOrNull()?.let { cell ->
            tx.execute(
                "INSERT OR REPLACE INTO sizing (increment_id, cell_id, work_id, attempt_id, candidate_id, context_id, schema_version, created_at, body) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?)",
                increment.id, cell.value, state.work, state.attempt, cell, Migrations.SCHEMA_VERSION, now,
                JSON.encodeToString(Sizing.serializer(), increment.sizing),
            )
        }
        for (entry in state.ledger.entries.values) tx.execute(
            "INSERT OR REPLACE INTO ledger (requirement_id, work_id, attempt_id, candidate_id, context_id, status, schema_version, created_at, body) VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, ?)",
            entry.requirementId, state.work, state.attempt, entry.status.wire, Migrations.SCHEMA_VERSION, now,
            JSON.encodeToString(LedgerEntry.serializer(), entry),
        )
    }

    override fun load(work: WorkId, attempt: AttemptId): CampaignState? = store.db.query(
        "SELECT body FROM campaigns WHERE work_id = ? AND attempt_id = ?", work, attempt,
    ) { JSON.decodeFromString(CampaignState.serializer(), it.string("body")) }.firstOrNull()

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}

private fun extends(stored: Long?, state: CampaignState) {
    val expected = stored?.plus(1) ?: 0L
    if (state.seq != expected) throw StaleCampaignState("campaign ${state.work.value}/${state.attempt.value} is at seq ${stored ?: "none"}; a save of seq ${state.seq} does not extend it")
}
