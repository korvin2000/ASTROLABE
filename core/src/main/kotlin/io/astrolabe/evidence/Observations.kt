package io.astrolabe.evidence

import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.json.Json
import java.time.Clock

/** Persistence seam for observations (§4.3): what the model actually saw, one row per rendered result. */
public interface Observations {
    /** Records [observation]; its content blob must already be published (artifact before referencing row). */
    public fun record(observation: Observation)

    public fun get(id: String): Observation?
}

public class InMemoryObservations : Observations {
    private val rows = LinkedHashMap<String, Observation>()

    @Synchronized
    override fun record(observation: Observation) {
        require(observation.id !in rows) { "observation ${observation.id} already recorded" }
        rows[observation.id] = observation
    }

    @Synchronized
    override fun get(id: String): Observation? = rows[id]
}

/** Observations in the store (`observations` table; the tool layer is the writer). */
public class SqliteObservations(private val store: Store, private val clock: Clock) : Observations {
    override fun record(observation: Observation): Unit = store.db.tx { tx ->
        tx.execute(
            "INSERT INTO observations (id, work_id, attempt_id, candidate_id, context_id, action_id, content_blob, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            observation.id, observation.ids.work, observation.ids.attempt, observation.candidate, observation.ids.context, observation.actionId,
            observation.contentRef, Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(Observation.serializer(), observation),
        )
    }

    override fun get(id: String): Observation? =
        store.db.query("SELECT body FROM observations WHERE id = ?", id) { JSON.decodeFromString(Observation.serializer(), it.string("body")) }.firstOrNull()

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
