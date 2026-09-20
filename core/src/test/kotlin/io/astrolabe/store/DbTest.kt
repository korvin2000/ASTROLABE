package io.astrolabe.store

import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

/** P0.5.1: pragmas verified at open, transaction boundaries and typed row mappers (D-03, D-44). */
class DbTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `every durability pragma is set and readable back`() {
        val layout = Layout(root).create()
        Db.open(layout).use { db ->
            assertEquals(1L, db.query("PRAGMA foreign_keys") { it.long(1) }.first())
            assertEquals("wal", db.query("PRAGMA journal_mode") { it.string(1) }.first().lowercase())
            assertEquals(2L, db.query("PRAGMA synchronous") { it.long(1) }.first())
            assertEquals(
                Db.BUSY_TIMEOUT_MILLIS.toLong(),
                db.query("PRAGMA busy_timeout") { it.long(1) }.first(),
            )
        }
    }

    @Test
    fun `a transaction commits its writes and rolls back on failure`() {
        openStore(root).use { store ->
            store.insertRow("packets", "id" to "p-1", "kind" to "result")
            assertEquals(1L, store.db.count("SELECT count(*) FROM packets"))

            assertFailsWith<IllegalStateException> {
                store.db.tx { tx ->
                    tx.execute(
                        "INSERT INTO packets (id, work_id, attempt_id, schema_version, created_at, body, kind) " +
                            "VALUES ('p-2', 'w', 'a', 1, '2026-01-01T00:00:00Z', '{}', 'result')",
                    )
                    assertEquals(2L, tx.query("SELECT count(*) AS n FROM packets") { it.long("n") }.first())
                    throw IllegalStateException("abort")
                }
            }
            assertEquals(1L, store.db.count("SELECT count(*) FROM packets"), "the failed transaction left no row")
        }
    }

    @Test
    fun `transactions do not nest`() {
        openStore(root).use { store ->
            val failure = assertFailsWith<IllegalStateException> {
                store.db.tx { store.db.tx { } }
            }
            assertTrue(failure.message!!.contains("nested"), "message names the rule: ${failure.message}")
        }
    }

    @Test
    fun `typed row mappers round trip the identity columns and the record`() {
        openStore(root).use { store ->
            val ids = Identities(
                work = WorkId("work-7"),
                attempt = AttemptId("attempt-7"),
                candidate = CandidateId(Digest.ofUtf8("candidate")),
                context = ContextId("ctx-7"),
            )
            store.insertRow(
                "claims",
                "id" to "claim-1",
                "kind" to "h",
                "evidence_state" to "hypothesis",
                ids = ids,
                body = """{"text":"a claim"}""",
            )

            val row = store.db.query(
                "SELECT id, work_id, attempt_id, candidate_id, context_id, schema_version, created_at, body, " +
                    "kind, evidence_state FROM claims",
            ) { Triple(it.identities(), it.storedRow("claims", it.string("id")), it.string("evidence_state")) }.single()

            assertEquals(ids, row.first)
            assertEquals(ids, row.second.identities)
            assertEquals(Migrations.SCHEMA_VERSION, row.second.schemaVersion)
            assertEquals(TEST_INSTANT, row.second.createdAt)
            assertEquals(JsonPrimitive("a claim"), row.second.body.jsonObject["text"])
            assertEquals("hypothesis", row.third)
        }
    }

    @Test
    fun `null identity columns stay null`() {
        openStore(root).use { store ->
            store.insertRow("packets", "id" to "p-1", "kind" to "result")
            val identities = store.db.query(
                "SELECT work_id, attempt_id, candidate_id, context_id FROM packets",
            ) { it.identities() }.single()
            assertEquals(null, identities.candidate)
            assertEquals(null, identities.context)
        }
    }

    @Test
    fun `foreign keys are enforced, so a receipt cannot reference an unpublished blob`() {
        openStore(root).use { store ->
            val missing = Digest.ofUtf8("never published").hex
            val failure = assertFailsWith<StoreError> {
                store.insertRow(
                    "receipts",
                    "receipt_id" to "r-1",
                    "check_id" to "check-1",
                    "outcome" to "pass",
                    "raw_blob" to missing,
                )
            }
            assertTrue(
                failure.message!!.contains("FOREIGN KEY", ignoreCase = true),
                "expected a foreign key failure, got: ${failure.message}",
            )
            assertEquals(0L, store.db.count("SELECT count(*) FROM receipts"))
        }
    }

    @Test
    fun `an unsupported parameter type is refused at the boundary`() {
        openStore(root).use { store ->
            assertFailsWith<IllegalArgumentException> {
                store.db.tx { it.execute("INSERT INTO packets (id) VALUES (?)", listOf("not bindable")) }
            }
        }
    }
}
