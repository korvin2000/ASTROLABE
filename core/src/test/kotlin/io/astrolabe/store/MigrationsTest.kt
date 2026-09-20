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
