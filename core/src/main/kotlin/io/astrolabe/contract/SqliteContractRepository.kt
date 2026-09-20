package io.astrolabe.contract

import io.astrolabe.id.WorkId
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.store.Tx
import kotlinx.serialization.json.Json
import java.time.Clock

/**
 * Contract rows in the store (§4.1, schema v1): `contracts` keeps every version's full record; `requests`
 * accumulates verbatim requests by id; `requirements`, `acceptance`, `constraints` and `amendments` hold the
 * current version's projection for [io.astrolabe.event.Views]. The controller is the only writer (L9).
 */
public class SqliteContractRepository(private val store: Store, private val clock: Clock) : ContractRepository {
    override fun history(work: WorkId): List<Contract> = store.db.query(
        "SELECT body FROM contracts WHERE work_id = ? ORDER BY version",
        work,
    ) { JSON.decodeFromString(Contract.serializer(), it.string("body")) }

    override fun append(contract: Contract): Unit = store.db.tx { tx ->
        val latest = tx.query("SELECT coalesce(max(version), 0) AS v FROM contracts WHERE work_id = ?", contract.workId) { it.long("v").toInt() }.first()
        require(contract.version == latest + 1) { "contract version ${contract.version} must be ${latest + 1}" }
        val now = clock.instant()
        tx.execute(
            "INSERT INTO contracts (work_id, attempt_id, candidate_id, context_id, version, schema_version, created_at, body) VALUES (?, ?, NULL, NULL, ?, ?, ?, ?)",
            contract.workId, contract.attemptId, contract.version, Migrations.SCHEMA_VERSION, now, JSON.encodeToString(Contract.serializer(), contract),
        )
        projection(tx, contract)
    }

    override fun replaceLatest(contract: Contract): Unit = store.db.tx { tx ->
        val latest = tx.query("SELECT coalesce(max(version), 0) AS v FROM contracts WHERE work_id = ?", contract.workId) { it.long("v").toInt() }.first()
        require(latest == contract.version) { "replaceLatest must keep version $latest, got ${contract.version}" }
        val updated = tx.execute(
            "UPDATE contracts SET body = ?, attempt_id = ? WHERE work_id = ? AND version = ?",
            JSON.encodeToString(Contract.serializer(), contract), contract.attemptId, contract.workId, contract.version,
        )
        check(updated == 1) { "contract ${contract.workId} v${contract.version} missing" }
        projection(tx, contract)
    }

    /** Rewrites the current-version projection rows; requests are append-only by id. */
    private fun projection(tx: Tx, c: Contract) {
        val now = clock.instant()
        val version = Migrations.SCHEMA_VERSION
        c.requests.forEachIndexed { i, r ->
            tx.execute(
                "INSERT OR IGNORE INTO requests (id, work_id, attempt_id, candidate_id, context_id, seq, schema_version, created_at, body) VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, ?)",
                r.id, c.workId, c.attemptId, i + 1, version, now, JSON.encodeToString(UserRequest.serializer(), r),
            )
        }
        for (table in listOf("requirements", "acceptance", "constraints")) tx.execute("DELETE FROM $table WHERE work_id = ?", c.workId)
        c.requirements.forEach { r ->
            tx.execute(
                "INSERT INTO requirements (id, work_id, attempt_id, candidate_id, context_id, contract_version, schema_version, created_at, body) VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, ?)",
                r.id, c.workId, c.attemptId, c.version, version, now, JSON.encodeToString(Requirement.serializer(), r),
            )
        }
        c.acceptance.forEach { a ->
            val kind = when (a) {
                is Acceptance.Run -> "run"
                is Acceptance.Check -> "check"
                is Acceptance.Review -> "review"
            }
            tx.execute(
                "INSERT INTO acceptance (id, work_id, attempt_id, candidate_id, context_id, contract_version, kind, schema_version, created_at, body) VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?)",
                a.id, c.workId, c.attemptId, c.version, kind, version, now, JSON.encodeToString(Acceptance.serializer(), a),
            )
        }
        c.constraints.forEach { k ->
            tx.execute(
                "INSERT INTO constraints (id, work_id, attempt_id, candidate_id, context_id, contract_version, schema_version, created_at, body) VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, ?)",
                k.id, c.workId, c.attemptId, c.version, version, now, JSON.encodeToString(Constraint.serializer(), k),
            )
        }
        c.amendmentsPending.forEach { a ->
            tx.execute(
                "INSERT INTO amendments (id, work_id, attempt_id, candidate_id, context_id, status, schema_version, created_at, body) VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET status = excluded.status, body = excluded.body",
                a.id, c.workId, c.attemptId, a.status.name, version, now, JSON.encodeToString(Amendment.serializer(), a),
            )
        }
    }

    /** Records a resolved amendment's final status in the projection. */
    public fun recordResolved(work: WorkId, amendment: Amendment): Unit = store.db.tx { tx ->
        tx.execute(
            "UPDATE amendments SET status = ?, body = ? WHERE id = ? AND work_id = ?",
            amendment.status.name, JSON.encodeToString(Amendment.serializer(), amendment), amendment.id, work,
        )
    }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
