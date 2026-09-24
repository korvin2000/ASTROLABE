package io.astrolabe.store

import java.time.Clock

/**
 * The schema of `state.sqlite` and the versioned, idempotent migration runner (D-03, D-25, IX-21): v1 is the P0
 * schema, v2 adds `campaigns`, the controller's lifecycle state (P1.9.1).
 *
 * ## Shape of every table
 * Each row carries the four identity columns of §3.3 — `work_id`, `attempt_id`, `candidate_id`,
 * `context_id` — plus `schema_version` (D-25: every persisted record names the schema that wrote
 * it), `created_at` (ISO-8601 instant; a capture time, never part of an identity, I-05) and `body`,
 * the full typed record as JSON. Typed columns exist beside `body` **only** where a join, a
 * uniqueness rule or a foreign key needs them; the record types themselves are declared by later
 * phases (P1.1 contract, P1.4 evidence, P1.5 register), which add typed decoding over the same
 * `body`. Until then a row is read as [StoredRow].
 *
 * ## One writer per table (L9)
 * | Writer | Tables |
 * |---|---|
 * | controller | `contracts`, `requests`, `requirements`, `acceptance`, `constraints`, `amendments`, `increments`, `ledger`, `sizing`, `leases`, `campaigns`, `attempts` |
 * | verifier | `receipts` |
 * | runner | `journal` (call/result kinds), `intents`, `handles`, `usage` |
 * | cell runtime | `cells`, `turns`, `manifests`, `register_versions`, `workset_exports`, `observations`, `claims`, `packets` |
 * | curator | `notes`, `notes_fts`, `note_queue`, `note_usage` |
 * | router | `routing_log` |
 * | blob store | `blobs` |
 * | alias allocator | `aliases` |
 * | migration runner | `schema_version` |
 *
 * ## Referential integrity
 * `receipts.raw_blob` and `observations.content_blob` reference `blobs(digest)`. With
 * `foreign_keys=ON` (D-03) and [BlobStore.put] inserting the `blobs` row only after the bytes are
 * fsynced and renamed into place (§4.3 "artifact publication precedes the transaction that
 * references it"), an accepted receipt cannot name an artifact that is not on disk.
 *
 * `note_queue.note_id` and `note_usage.note_id` reference `notes(note_id)`: the curator is the sole
 * publisher, so a queue entry or a usage record for an unpublished note is a defect, not a state.
 *
 * ## Aliases
 * `aliases` allocates the campaign-global `#n` of D-46 with `UNIQUE (work_id, alias_no)`; the
 * `alias` rowid is monotone within a store so an allocation is never recycled on rebuild, cancel or
 * resume, and the row keeps the context and workspace that produced it.
 */
public object Migrations {
    /** The schema version this build writes; every row records it. */
    public const val SCHEMA_VERSION: Int = 3

    /** Every table of the current schema, in creation order (`notes_fts` is the FTS5 virtual table). */
    public val TABLES: List<String> = listOf(
        "schema_version",
        "blobs",
        "journal",
        "stamps",
        "receipts",
        "observations",
        "claims",
        "intents",
        "contracts",
        "requests",
        "requirements",
        "acceptance",
        "constraints",
        "amendments",
        "increments",
        "ledger",
        "sizing",
        "cells",
        "turns",
        "manifests",
        "register_versions",
        "workset_exports",
        "aliases",
        "notes",
        "notes_fts",
        "note_queue",
        "note_usage",
        "routing_log",
        "usage",
        "leases",
        "handles",
        "packets",
        "campaigns",
        "attempts",
    )

    /**
     * Applies every migration newer than the recorded version inside one transaction and returns
     * the resulting version. Idempotent: a second call on an up-to-date store applies nothing and
     * leaves the `schema_version` rows untouched (IX-21).
     */
    @JvmStatic
    public fun apply(db: Db, clock: Clock): Int = db.tx { tx ->
        tx.execute(
            "CREATE TABLE IF NOT EXISTS schema_version (" +
                "version INTEGER PRIMARY KEY NOT NULL, applied_at TEXT NOT NULL)",
        )
        val current = tx.query("SELECT coalesce(max(version), 0) AS version FROM schema_version") {
            it.long("version").toInt()
        }.first()
        // Fail closed rather than write v1 rows into a schema a newer build owns (D-44).
        if (current > SCHEMA_VERSION) {
            throw StoreUnsupported("store schema v$current is newer than this build's v$SCHEMA_VERSION at ${db.path}")
        }
        for (migration in MIGRATIONS) {
            if (migration.version <= current) continue
            migration.statements.forEach { tx.execute(it) }
            tx.execute(
                "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
                migration.version,
                clock.instant().toString(),
            )
        }
        SCHEMA_VERSION
    }

    /** The version recorded in the store, or 0 when nothing has been applied yet. */
    @JvmStatic
    public fun version(db: Db): Int {
        val present = db.query(
            "SELECT count(*) AS present FROM sqlite_master WHERE type = 'table' AND name = 'schema_version'",
        ) { it.long("present") }.first()
        if (present == 0L) return 0
        return db.query("SELECT coalesce(max(version), 0) AS version FROM schema_version") {
            it.long("version").toInt()
        }.first()
    }

    private class Migration(val version: Int, val statements: List<String>)

    /** Identity columns (§3.3) in their canonical order and nullability. */
    private const val IDS =
        "work_id TEXT NOT NULL, attempt_id TEXT NOT NULL, candidate_id TEXT, context_id TEXT"

    /** Identity columns where the context is the row's own key and therefore required. */
    private const val IDS_CONTEXT_KEY =
        "work_id TEXT NOT NULL, attempt_id TEXT NOT NULL, candidate_id TEXT, context_id TEXT NOT NULL"

    /** D-25 provenance and the record itself. */
    private const val META =
        "schema_version INTEGER NOT NULL, created_at TEXT NOT NULL, body TEXT NOT NULL"

    private val MIGRATIONS: List<Migration> = listOf(
        Migration(
            version = 1,
            statements = listOf(
                // ---- evidence store (§4.3) --------------------------------------------------
                "CREATE TABLE blobs (" +
                    "digest TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "bytes INTEGER NOT NULL, kind TEXT NOT NULL, recovery INTEGER NOT NULL, $META)",
                "CREATE TABLE journal (" +
                    "event_id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "seq INTEGER NOT NULL, turn INTEGER, kind TEXT NOT NULL, $META, " +
                    "UNIQUE (work_id, seq))",
                "CREATE TABLE stamps (" +
                    "stamp_id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "base_commit TEXT NOT NULL, tracked_delta_hash TEXT NOT NULL, " +
                    "untracked_manifest_hash TEXT NOT NULL, env_id TEXT NOT NULL, at TEXT NOT NULL, $META)",
                "CREATE TABLE receipts (" +
                    "receipt_id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "check_id TEXT NOT NULL, stamp_before TEXT, stamp_after TEXT, outcome TEXT NOT NULL, " +
                    "raw_blob TEXT REFERENCES blobs (digest), $META)",
                "CREATE TABLE observations (" +
                    "id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "action_id TEXT NOT NULL, content_blob TEXT REFERENCES blobs (digest), $META)",
                "CREATE TABLE claims (" +
                    "id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "kind TEXT NOT NULL, evidence_state TEXT NOT NULL, $META)",
                "CREATE TABLE intents (" +
                    "intent_id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "action_id TEXT NOT NULL, status TEXT NOT NULL, $META)",
                // ---- task contract (§4.1) ---------------------------------------------------
                "CREATE TABLE contracts (" +
                    "$IDS, version INTEGER NOT NULL, $META, PRIMARY KEY (work_id, version))",
                "CREATE TABLE requests (" +
                    "id TEXT PRIMARY KEY NOT NULL, $IDS, seq INTEGER NOT NULL, $META, " +
                    "UNIQUE (work_id, seq))",
                "CREATE TABLE requirements (" +
                    "id TEXT PRIMARY KEY NOT NULL, $IDS, contract_version INTEGER NOT NULL, $META)",
                "CREATE TABLE acceptance (" +
                    "id TEXT PRIMARY KEY NOT NULL, $IDS, contract_version INTEGER NOT NULL, " +
                    "kind TEXT NOT NULL, $META)",
                "CREATE TABLE constraints (" +
                    "id TEXT PRIMARY KEY NOT NULL, $IDS, contract_version INTEGER NOT NULL, $META)",
                "CREATE TABLE amendments (" +
                    "id TEXT PRIMARY KEY NOT NULL, $IDS, status TEXT NOT NULL, $META)",
                // ---- requirement graph and ledger (§4.2) ------------------------------------
                "CREATE TABLE increments (" +
                    "id TEXT NOT NULL, $IDS, status TEXT NOT NULL, $META, PRIMARY KEY (work_id, id))",
                "CREATE TABLE ledger (" +
                    "requirement_id TEXT NOT NULL, $IDS, status TEXT NOT NULL, $META, " +
                    "PRIMARY KEY (work_id, requirement_id))",
                "CREATE TABLE sizing (" +
                    "increment_id TEXT NOT NULL, cell_id TEXT NOT NULL, $IDS, $META, " +
                    "PRIMARY KEY (work_id, increment_id, cell_id))",
                // ---- cell runtime (§5) ------------------------------------------------------
                "CREATE TABLE cells (" +
                    "$IDS_CONTEXT_KEY, increment_id TEXT NOT NULL, status TEXT NOT NULL, $META, " +
                    "PRIMARY KEY (context_id))",
                "CREATE TABLE turns (" +
                    "$IDS_CONTEXT_KEY, turn INTEGER NOT NULL, $META, PRIMARY KEY (context_id, turn))",
                "CREATE TABLE manifests (" +
                    "id TEXT PRIMARY KEY NOT NULL, $IDS_CONTEXT_KEY, $META)",
                "CREATE TABLE register_versions (" +
                    "$IDS_CONTEXT_KEY, version INTEGER NOT NULL, $META, PRIMARY KEY (context_id, version))",
                "CREATE TABLE workset_exports (" +
                    "id TEXT NOT NULL, $IDS_CONTEXT_KEY, $META, PRIMARY KEY (context_id, id))",
                // ---- evidence aliases (D-46) ------------------------------------------------
                "CREATE TABLE aliases (" +
                    "alias INTEGER PRIMARY KEY AUTOINCREMENT, $IDS, " +
                    "alias_no INTEGER NOT NULL, canonical_id TEXT NOT NULL, kind TEXT NOT NULL, " +
                    "workspace_id TEXT, $META, UNIQUE (work_id, alias_no))",
                // ---- knowledge base (§4.5) --------------------------------------------------
                "CREATE TABLE notes (" +
                    "note_id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "kind TEXT NOT NULL, status TEXT NOT NULL, summary TEXT NOT NULL, " +
                    "anchors TEXT NOT NULL, $META)",
                "CREATE VIRTUAL TABLE notes_fts USING fts5 (note_id UNINDEXED, summary, anchors)",
                "CREATE TABLE note_queue (" +
                    "id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "note_id TEXT NOT NULL REFERENCES notes (note_id), status TEXT NOT NULL, $META)",
                "CREATE TABLE note_usage (" +
                    "note_id TEXT NOT NULL REFERENCES notes (note_id), $IDS_CONTEXT_KEY, " +
                    "used_at TEXT NOT NULL, $META, PRIMARY KEY (note_id, context_id, used_at))",
                // ---- routing, accounting, execution (§11, §15.2, §13.1) ---------------------
                "CREATE TABLE routing_log (" +
                    "id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "function TEXT NOT NULL, tier TEXT NOT NULL, outcome TEXT NOT NULL, $META)",
                "CREATE TABLE usage (" +
                    "invocation_id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "profile_id TEXT NOT NULL, native TEXT NOT NULL, normalized TEXT NOT NULL, $META)",
                "CREATE TABLE leases (" +
                    "workspace_id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "holder TEXT NOT NULL, expiry TEXT NOT NULL, execution_generation INTEGER NOT NULL, $META)",
                "CREATE TABLE handles (" +
                    "handle_id TEXT PRIMARY KEY NOT NULL, $IDS, " +
                    "status TEXT NOT NULL, log_path TEXT NOT NULL, cursor INTEGER NOT NULL, $META)",
                "CREATE TABLE packets (" +
                    "id TEXT PRIMARY KEY NOT NULL, $IDS, kind TEXT NOT NULL, $META)",
                // ---- indexes the read projections join on ------------------------------------
                "CREATE INDEX blobs_by_created ON blobs (created_at)",
                "CREATE INDEX receipts_by_work_check ON receipts (work_id, check_id, created_at)",
                "CREATE INDEX observations_by_work ON observations (work_id, created_at)",
                "CREATE INDEX requirements_by_work ON requirements (work_id, contract_version)",
                "CREATE INDEX acceptance_by_work ON acceptance (work_id, contract_version)",
                "CREATE INDEX constraints_by_work ON constraints (work_id, contract_version)",
                "CREATE INDEX amendments_by_work ON amendments (work_id, created_at)",
                "CREATE INDEX usage_by_work ON usage (work_id, created_at)",
                "CREATE INDEX packets_by_work_kind ON packets (work_id, kind, created_at)",
                "CREATE INDEX note_usage_by_context ON note_usage (context_id)",
            ),
        ),
        Migration(
            version = 2,
            statements = listOf(
                // ---- campaign lifecycle (§3.2 "one state machine over typed records") -----------
                "CREATE TABLE campaigns (" +
                    "$IDS, phase TEXT NOT NULL, outcome TEXT, seq INTEGER NOT NULL, $META, " +
                    "PRIMARY KEY (work_id, attempt_id))",
            ),
        ),
        Migration(
            version = 3,
            statements = listOf(
                // ---- attempt freeze (invariant 12, D-48): the configuration an attempt runs under ---------
                "CREATE TABLE attempts (" +
                    "$IDS, fingerprint TEXT NOT NULL, $META, " +
                    "PRIMARY KEY (work_id, attempt_id))",
                // ---- contract projections are per work: `R1`/`AC-1` recur in every campaign of a project ----
                "CREATE TABLE requirements_v3 (" +
                    "id TEXT NOT NULL, $IDS, contract_version INTEGER NOT NULL, $META, PRIMARY KEY (work_id, id))",
                "INSERT INTO requirements_v3 SELECT id, work_id, attempt_id, candidate_id, context_id, contract_version, schema_version, created_at, body FROM requirements",
                "DROP TABLE requirements",
                "ALTER TABLE requirements_v3 RENAME TO requirements",
                "CREATE TABLE acceptance_v3 (" +
                    "id TEXT NOT NULL, $IDS, contract_version INTEGER NOT NULL, kind TEXT NOT NULL, $META, PRIMARY KEY (work_id, id))",
                "INSERT INTO acceptance_v3 SELECT id, work_id, attempt_id, candidate_id, context_id, contract_version, kind, schema_version, created_at, body FROM acceptance",
                "DROP TABLE acceptance",
                "ALTER TABLE acceptance_v3 RENAME TO acceptance",
                "CREATE TABLE constraints_v3 (" +
                    "id TEXT NOT NULL, $IDS, contract_version INTEGER NOT NULL, $META, PRIMARY KEY (work_id, id))",
                "INSERT INTO constraints_v3 SELECT id, work_id, attempt_id, candidate_id, context_id, contract_version, schema_version, created_at, body FROM constraints",
                "DROP TABLE constraints",
                "ALTER TABLE constraints_v3 RENAME TO constraints",
                "CREATE INDEX requirements_by_work ON requirements (work_id, contract_version)",
                "CREATE INDEX acceptance_by_work ON acceptance (work_id, contract_version)",
                "CREATE INDEX constraints_by_work ON constraints (work_id, contract_version)",
            ),
        ),
    )
}
