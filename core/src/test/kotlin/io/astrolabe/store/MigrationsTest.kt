package io.astrolabe.store

import io.astrolabe.fixtures.StoreInspector
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** P0.5.2: schema v1 and an idempotent migration runner (IX-21, D-03, D-25). */
class MigrationsTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `schema v1 creates every declared table`() {
        openStore(root).use { store ->
            val inspector = StoreInspector(store)
            assertEquals(emptyList(), inspector.missingTables(), "declared tables missing from the database")
            assertEquals(Migrations.SCHEMA_VERSION, store.schemaVersion)
        }
    }

    @Test
    fun `applying the migrations twice changes nothing`() {
        openStore(root).use { store ->
            val inspector = StoreInspector(store)
            val before = inspector.tables()
            val applied = store.db.count("SELECT count(*) FROM schema_version")

            assertEquals(Migrations.SCHEMA_VERSION, Migrations.apply(store.db, TEST_CLOCK))
            assertEquals(Migrations.SCHEMA_VERSION, Migrations.apply(store.db, TEST_CLOCK))

            assertEquals(before, inspector.tables(), "the table list must not change")
            assertEquals(applied, store.db.count("SELECT count(*) FROM schema_version"), "one row per version")
            assertEquals(Migrations.SCHEMA_VERSION, store.schemaVersion)
        }
    }

    @Test
    fun `a store written by a newer schema is refused rather than downgraded`() {
        openStore(root).use { store ->
            store.db.tx {
                it.execute(
                    "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
                    Migrations.SCHEMA_VERSION + 1,
                    TEST_INSTANT.toString(),
                )
            }
            val failure = assertFailsWith<StoreUnsupported> { Migrations.apply(store.db, TEST_CLOCK) }
            assertTrue(failure.message!!.contains("newer"), failure.message!!)
        }
    }

    @Test
    fun `the version of a database without a schema_version table is zero`() {
        val layout = Layout(root).create()
        Db.open(layout).use { db ->
            assertEquals(0, Migrations.version(db))
            Migrations.apply(db, TEST_CLOCK)
            assertEquals(Migrations.SCHEMA_VERSION, Migrations.version(db))
        }
    }

    @Test
    fun `the notes FTS5 index answers a MATCH query`() {
        openStore(root).use { store ->
            store.insertRow(
                "notes",
                "note_id" to "note-1",
                "kind" to "pitfall",
                "status" to "published",
                "summary" to "gradle daemon refuses a stale toolchain",
                "anchors" to "build.gradle.kts",
            )
            store.insertRow(
                "notes",
                "note_id" to "note-2",
                "kind" to "pitfall",
                "status" to "published",
                "summary" to "sqlite busy timeout is per connection",
                "anchors" to "store/Db.kt",
            )
            store.db.tx { tx ->
                tx.execute(
                    "INSERT INTO notes_fts (note_id, summary, anchors) " +
                        "SELECT note_id, summary, anchors FROM notes",
                )
            }

            val hits = store.db.query(
                "SELECT note_id FROM notes_fts WHERE notes_fts MATCH ? ORDER BY note_id",
                "toolchain",
            ) { it.string("note_id") }
            assertEquals(listOf("note-1"), hits)

            // A column filter needs a quoted phrase: `Db.kt` tokenizes to the two tokens Db and kt.
            val anchored = store.db.query(
                "SELECT note_id FROM notes_fts WHERE notes_fts MATCH ? ORDER BY note_id",
                "anchors:\"Db.kt\"",
            ) { it.string("note_id") }
            assertEquals(listOf("note-2"), anchored)
        }
    }

    @Test
    fun `the alias allocator keeps one alias number per work item`() {
        openStore(root).use { store ->
            store.insertRow("aliases", "alias_no" to 1, "canonical_id" to "event-1", "kind" to "look")
            val clash = assertFailsWith<StoreError> {
                store.insertRow("aliases", "alias_no" to 1, "canonical_id" to "event-2", "kind" to "look")
            }
            assertTrue(clash.message!!.contains("UNIQUE", ignoreCase = true), clash.message!!)

            store.insertRow("aliases", "alias_no" to 2, "canonical_id" to "event-2", "kind" to "look")
            val rowids = store.db.query("SELECT alias FROM aliases ORDER BY alias") { it.long("alias") }
            assertEquals(listOf(1L, 2L), rowids, "aliases are allocated monotonically and never recycled")
        }
    }

    @Test
    fun `schema v6 indexes packets by cell and a v5 store migrates to it keeping its rows`() {
        openStore(root).use { store ->
            val index = "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'packets_by_context'"
            assertEquals(1L, store.db.count(index))
            // A store written by v5: the index absent, versions 6 and 7 not recorded, a packet row present.
            revertV7(store)
            store.db.tx {
                it.execute("DROP INDEX packets_by_context")
                it.execute("DELETE FROM schema_version WHERE version = 6")
            }
            store.insertRow("packets", "id" to "packet-cell-1", "kind" to "cell_packet", ids = TEST_IDS.copy(context = io.astrolabe.id.ContextId("cell-1")))
            assertEquals(5, Migrations.version(store.db))
            assertEquals(Migrations.SCHEMA_VERSION, Migrations.apply(store.db, TEST_CLOCK))
            assertEquals(1L, store.db.count(index))
            assertEquals(1L, store.db.count("SELECT count(*) FROM packets WHERE context_id = 'cell-1'"))
        }
    }

    @Test
    fun `schema v7 adds binding physics and orders routing_log and a v6 store migrates to it once keeping its rows`() {
        openStore(root).use { store ->
            val inspector = StoreInspector(store)
            val current = inspector.tables()
            // A store written by v6: no binding tables, routing_log in its v1 shape with a row, version 7 not recorded.
            revertV7(store)
            store.insertRow("routing_log", "id" to "r-1", "function" to "Implementing", "tier" to "High", "outcome" to "Accepted")
            assertEquals(6, Migrations.version(store.db))
            assertTrue("binding_physics" !in inspector.tables())

            assertEquals(7, Migrations.apply(store.db, TEST_CLOCK))
            val applied = store.db.count("SELECT count(*) FROM schema_version")
            assertEquals(7, Migrations.apply(store.db, TEST_CLOCK), "IX-21: a second run applies nothing")
            assertEquals(applied, store.db.count("SELECT count(*) FROM schema_version"))
            assertEquals(current, inspector.tables())
            assertEquals(1L, store.db.count("SELECT count(*) FROM routing_log WHERE id = 'r-1' AND seq IS NULL AND binding_key IS NULL"), "the v6 row is kept")
            assertEquals(1L, store.db.count("SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'routing_log_by_attempt'"))
            store.insertRow("routing_log", "id" to "r-2", "function" to "Plan", "tier" to "High", "outcome" to "Selected", "seq" to 1)
            assertFailsWith<StoreError> {
                store.insertRow("routing_log", "id" to "r-3", "function" to "Plan", "tier" to "High", "outcome" to "Selected", "seq" to 1)
            }
        }
    }

    /** Undoes v7 on a current store: what a v6 build left behind. */
    private fun revertV7(store: Store) = store.db.tx {
        it.execute("DROP TABLE binding_physics")
        it.execute("DROP TABLE binding_snapshots")
        it.execute("DROP INDEX routing_log_by_attempt")
        it.execute("ALTER TABLE routing_log DROP COLUMN seq")
        it.execute("ALTER TABLE routing_log DROP COLUMN binding_key")
        it.execute("DELETE FROM schema_version WHERE version = 7")
    }

    @Test
    fun `the journal sequence is unique within a work item`() {
        openStore(root).use { store ->
            store.insertRow("journal", "event_id" to "e-1", "seq" to 1, "kind" to "call")
            assertFailsWith<StoreError> {
                store.insertRow("journal", "event_id" to "e-2", "seq" to 1, "kind" to "result")
            }
            store.insertRow("journal", "event_id" to "e-2", "seq" to 2, "kind" to "result")
            assertEquals(2L, store.db.count("SELECT count(*) FROM journal"))
        }
    }
}
