package io.astrolabe.fixtures

import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.store.StoredRow
import io.astrolabe.store.storedRow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Read-only window into a [Store] for tests (TODO P0.6.4): what tables exist, how many rows they
 * hold, what those rows are and which blob files are on disk. The inspector never writes, so a test
 * that uses it cannot accidentally become the second writer of a table (L9).
 */
public class StoreInspector(private val store: Store) {

    /** Table and virtual-table names actually present in the database, sorted. */
    public fun tables(): List<String> = store.db.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
    ) { it.string("name") }

    /** The tables schema v1 declares that are missing from the database. */
    public fun missingTables(): List<String> = Migrations.TABLES - tables().toSet()

    /** Row count of one table. */
    public fun count(table: String): Long = store.db.count("SELECT count(*) FROM $table")

    /** Row counts of every declared table, in [Migrations.TABLES] order. */
    public fun counts(): Map<String, Long> {
        val present = tables().toSet()
        return Migrations.TABLES.filter { it in present }.associateWith(::count)
    }

    /**
     * Every row of [table] as a [StoredRow], keyed by [keyColumn] and ordered by it. Only usable on
     * the record tables — `schema_version` and `notes_fts` do not carry the common columns.
     */
    public fun rows(table: String, keyColumn: String): List<StoredRow> = store.db.query(
        "SELECT $keyColumn, work_id, attempt_id, candidate_id, context_id, schema_version, created_at, body " +
            "FROM $table ORDER BY $keyColumn",
    ) { it.storedRow(table, it.string(keyColumn)) }

    /** Digests of every published blob file on disk, including the recovery area, sorted. */
    public fun blobFiles(): List<String> =
        (listNames(store.layout.blobs) + listNames(store.layout.blobsRecovery)).sorted()

    /** Names of the files left in `blobs/tmp`, sorted; a healthy store between operations has none. */
    public fun temporaryBlobFiles(): List<String> = listNames(store.layout.blobsTemp).sorted()

    /**
     * Digests referenced by a receipt or an observation. The crash-safety tests assert that every
     * one of these is present as both a row and a file — no accepted receipt may reference a
     * missing artifact (§4.3).
     */
    public fun referencedBlobs(): List<String> = store.db.query(
        "SELECT raw_blob AS digest FROM receipts WHERE raw_blob IS NOT NULL " +
            "UNION SELECT content_blob AS digest FROM observations WHERE content_blob IS NOT NULL " +
            "ORDER BY digest",
    ) { it.string("digest") }

    private fun listNames(directory: Path): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream ->
            stream.asSequence().filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.toList()
        }
    }
}
