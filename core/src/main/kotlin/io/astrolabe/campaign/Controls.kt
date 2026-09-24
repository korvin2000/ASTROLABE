package io.astrolabe.campaign

import io.astrolabe.AttemptConfig
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.Identities
import io.astrolabe.id.InstantSerializer
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A campaign's cancellation token (§3.7 `enforce_cancellation…`, D-26): checked before every dispatch and before
 * any publication. Cancelling never discards effects that already happened: they are reconciled and archived,
 * only their publication is refused.
 */
public class Cancellation {
    @Volatile
    public var reason: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    public val cancelled: Boolean get() = reason != null

    /** Requests cancellation; the first reason wins, and listeners (the in-flight cell) hear it once. */
    public fun cancel(reason: String) {
        require(reason.isNotBlank()) { "a cancellation records why" }
        val first = synchronized(this) {
            if (this.reason != null) false else {
                this.reason = reason
                true
            }
        }
        if (first) listeners.forEach { it(reason) }
    }

    internal fun onCancel(listener: (String) -> Unit): AutoCloseable {
        listeners += listener
        reason?.let(listener)
        return AutoCloseable { listeners -= listener }
    }
}

/**
 * The single-writer lease of a workspace (§13.1, D-26): [holder] may publish into [workspace] until [expiry] under
 * [generation]. Expiry revokes publication authority only — it does not stop an action that may still be running.
 */
@Serializable
public data class Lease(
    val workspace: WorkspaceId,
    val holder: String,
    @Serializable(with = InstantSerializer::class) val expiry: Instant,
    val generation: ExecutionGeneration,
) {
    public fun validAt(now: Instant): Boolean = now.isBefore(expiry)
}

/** Why publication or dispatch is not authorized now, or `null` when it is. */
public fun interface PublicationAuthority {
    public fun refusal(): String?
}

/**
 * Leases in the store (`leases` table; the controller is the writer). A workspace has one holder at a time: a
 * different holder acquires only after expiry, and each reassignment increments the execution generation so a
 * superseded writer's late result is recognisable (§13.1).
 */
public class Leases(private val store: Store, private val clock: Clock) {
    public fun current(workspace: WorkspaceId): Lease? = store.db.query(
        "SELECT body FROM leases WHERE workspace_id = ?", workspace.value,
    ) { JSON.decodeFromString(Lease.serializer(), it.string("body")) }.firstOrNull()

    /** Acquires or renews [workspace] for [holder] for [duration]; refuses while another holder's lease is live. */
    public fun acquire(workspace: WorkspaceId, ids: Identities, holder: String, duration: Duration): Lease = store.db.tx { tx ->
        val now = clock.instant()
        val existing = tx.query("SELECT body FROM leases WHERE workspace_id = ?", workspace.value) { JSON.decodeFromString(Lease.serializer(), it.string("body")) }.firstOrNull()
        if (existing != null && existing.holder != holder && existing.validAt(now)) {
            throw LeaseHeld(existing)
        }
        val generation = when {
            existing == null -> ExecutionGeneration.INITIAL
            existing.holder == holder -> existing.generation
            else -> existing.generation.next()
        }
        val lease = Lease(workspace, holder, now.plus(duration), generation)
        tx.execute(
            "INSERT OR REPLACE INTO leases (workspace_id, work_id, attempt_id, candidate_id, context_id, holder, expiry, execution_generation, schema_version, created_at, body) VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?)",
            workspace.value, ids.work, ids.attempt, holder, lease.expiry.toString(), generation.value, Migrations.SCHEMA_VERSION, now,
            JSON.encodeToString(Lease.serializer(), lease),
        )
        lease
    }

    /** The authority [lease] still grants: none once it expired or another generation holds the workspace. */
    public fun authority(lease: Lease): PublicationAuthority = PublicationAuthority {
        val stored = current(lease.workspace)
        when {
            stored == null || stored.holder != lease.holder || stored.generation != lease.generation ->
                "lease of ${lease.workspace.value} superseded (generation ${stored?.generation?.value ?: "none"}, held ${lease.generation.value})"
            !stored.validAt(clock.instant()) -> "lease of ${lease.workspace.value} expired at ${stored.expiry}"
            else -> null
        }
    }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}

/** Thrown when another holder's live lease owns the workspace. */
public class LeaseHeld(public val lease: Lease) :
    IllegalStateException("workspace ${lease.workspace.value} is leased to ${lease.holder} until ${lease.expiry}")

/**
 * The frozen [AttemptConfig] of each attempt (`attempts` table; the controller is the writer). The first open
 * freezes it; every later open of the same attempt reads it back, so a configuration edit takes effect only at
 * the next attempt (invariant 12, IX-02, IX-18).
 */
public class Attempts(private val store: Store, private val clock: Clock) {
    public fun load(work: WorkId, attempt: AttemptId): AttemptConfig? = store.db.query(
        "SELECT body FROM attempts WHERE work_id = ? AND attempt_id = ?", work, attempt,
    ) { JSON.decodeFromString(AttemptConfig.serializer(), it.string("body")) }.firstOrNull()

    public fun save(work: WorkId, attempt: AttemptId, frozen: AttemptConfig): Unit = store.db.tx { tx ->
        tx.execute(
            "INSERT INTO attempts (work_id, attempt_id, candidate_id, context_id, fingerprint, schema_version, created_at, body) VALUES (?, ?, NULL, NULL, ?, ?, ?, ?)",
            work, attempt, frozen.fingerprint.hex, Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(AttemptConfig.serializer(), frozen),
        )
    }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
