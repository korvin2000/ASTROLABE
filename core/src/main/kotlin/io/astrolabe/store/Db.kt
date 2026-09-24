package io.astrolabe.store

import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A durability or capability guarantee the running SQLite cannot provide; the store fails closed (D-44). */
public class StoreUnsupported(message: String) : IllegalStateException(message)

/** A failed statement, carrying the SQL so a failure names the writer that produced it. */
public class StoreError(public val sql: String, cause: SQLException) :
    IllegalStateException("SQL failed: $sql — ${cause.message}", cause)

/**
 * The canonical record database (D-03): explicit SQL, no ORM, one connection per store used under
 * one lock. Serializing access is deliberate at this scale — a campaign writes tens of rows per
 * turn — and it removes SQLITE_BUSY from the failure space that resume has to classify.
 *
 * Pragmas set and **read back** at open (D-44): `foreign_keys=ON`, `journal_mode=WAL`,
 * `synchronous=FULL`, `busy_timeout`. A guarantee that cannot be read back throws
 * [StoreUnsupported] rather than leaving records more fragile than they claim to be.
 */
public class Db private constructor(
    public val path: Path,
    private val connection: Connection,
) : AutoCloseable {

    private val monitor = ReentrantLock()
    private var inTransaction = false

    /**
     * Runs [block] inside `BEGIN IMMEDIATE … COMMIT`, rolling back on any exception. The write lock
     * is taken at BEGIN, not at the first write, so two writers never discover a conflict halfway
     * through. Transactions do not nest: the state machine that owns a table owns the whole
     * transaction (L9).
     */
    public fun <T> tx(block: (Tx) -> T): T = monitor.withLock {
        check(!inTransaction) { "nested transactions are not supported; one writer owns one transaction" }
        inTransaction = true
        try {
            statement("BEGIN IMMEDIATE")
            val result = try {
                block(Tx(this))
            } catch (failure: Throwable) {
                runCatching { statement("ROLLBACK") }
                throw failure
            }
            statement("COMMIT")
            result
        } finally {
            inTransaction = false
        }
    }

    /** A read outside a transaction; SQLite reads a consistent snapshot without one. */
    public fun <T> query(sql: String, vararg params: Any?, map: (Row) -> T): List<T> =
        monitor.withLock { read(sql, params, map) }

    /** Convenience for the very common `SELECT count(*)` shape. */
    public fun count(sql: String, vararg params: Any?): Long =
        query(sql, *params) { it.long(1) }.firstOrNull() ?: 0L

    override fun close(): Unit = monitor.withLock { connection.close() }

    internal fun write(sql: String, params: Array<out Any?>): Int = try {
        prepare(sql, params).use { it.executeUpdate() }
    } catch (failure: SQLException) {
        throw StoreError(sql, failure)
    }

    internal fun <T> read(sql: String, params: Array<out Any?>, map: (Row) -> T): List<T> = try {
        prepare(sql, params).use { statement ->
            statement.executeQuery().use { results ->
                val rows = ArrayList<T>()
                val row = Row(results)
                while (results.next()) rows.add(map(row))
                rows
            }
        }
    } catch (failure: SQLException) {
        throw StoreError(sql, failure)
    }

    private fun prepare(sql: String, params: Array<out Any?>): PreparedStatement {
        val statement = try {
            connection.prepareStatement(sql)
        } catch (failure: SQLException) {
            throw StoreError(sql, failure)
        }
        try {
            params.forEachIndexed { index, value -> bind(statement, index + 1, value) }
        } catch (failure: Throwable) {
            statement.close()
            throw if (failure is SQLException) StoreError(sql, failure) else failure
        }
        return statement
    }

    private fun bind(statement: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> statement.setNull(index, java.sql.Types.NULL)
            is String -> statement.setString(index, value)
            is Int -> statement.setInt(index, value)
            is Long -> statement.setLong(index, value)
            is Boolean -> statement.setInt(index, if (value) 1 else 0)
            is ByteArray -> statement.setBytes(index, value)
            is Digest -> statement.setString(index, value.hex)
            is Instant -> statement.setString(index, value.toString())
            is JsonElement -> statement.setString(index, JSON.encodeToString(JsonElement.serializer(), value))
            is WorkId -> statement.setString(index, value.value)
            is AttemptId -> statement.setString(index, value.value)
            is ContextId -> statement.setString(index, value.value)
            is CandidateId -> statement.setString(index, value.digest.hex)
            else -> throw IllegalArgumentException("unsupported parameter type ${value::class.java.name}")
        }
    }

    private fun statement(sql: String) {
        try {
            connection.createStatement().use { it.execute(sql) }
        } catch (failure: SQLException) {
            throw StoreError(sql, failure)
        }
    }

    private fun pragmaRead(sql: String): String? = try {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { if (it.next()) it.getString(1) else null }
        }
    } catch (failure: SQLException) {
        throw StoreError(sql, failure)
    }

    public companion object {
        internal val JSON: Json = Json { prettyPrint = false }

        /** Milliseconds a writer waits for a competing lock before failing (D-44). */
        public const val BUSY_TIMEOUT_MILLIS: Int = 5_000

        /** `PRAGMA synchronous` value that means FULL. */
        private const val SYNCHRONOUS_FULL = "2"

        /** Opens (creating if absent) the store database at [Layout.database] with verified pragmas. */
        @JvmStatic
        public fun open(layout: Layout): Db {
            val path = layout.database.toAbsolutePath().normalize()
            val connection = DriverManager.getConnection("jdbc:sqlite:${path.toString().replace('\\', '/')}")
            val db = Db(path, connection)
            try {
                db.applyPragmas()
            } catch (failure: Throwable) {
                connection.close()
                throw failure
            }
            return db
        }
    }

    private fun applyPragmas() {
        connection.autoCommit = true
        // §4 D-44: each guarantee is set and then read back; a pragma SQLite silently ignores would
        // otherwise leave the records less durable than every ordering rule assumes.
        statement("PRAGMA foreign_keys=ON")
        verify("foreign_keys", pragmaRead("PRAGMA foreign_keys"), "1")
        statement("PRAGMA journal_mode=WAL")
        val journal = pragmaRead("PRAGMA journal_mode")
        if (!journal.equals("wal", ignoreCase = true)) {
            throw StoreUnsupported("journal_mode=WAL is required for crash safety, got '$journal' at $path")
        }
        statement("PRAGMA synchronous=FULL")
        verify("synchronous", pragmaRead("PRAGMA synchronous"), SYNCHRONOUS_FULL)
        statement("PRAGMA busy_timeout=$BUSY_TIMEOUT_MILLIS")
        verify("busy_timeout", pragmaRead("PRAGMA busy_timeout"), BUSY_TIMEOUT_MILLIS.toString())
    }

    private fun verify(name: String, actual: String?, expected: String) {
        if (actual != expected) {
            throw StoreUnsupported("PRAGMA $name could not be set to $expected (read back '$actual') at $path")
        }
    }
}

/** The write side of one transaction; only reachable inside [Db.tx]. */
public class Tx internal constructor(private val db: Db) {
    /** Runs an INSERT/UPDATE/DELETE and returns the affected row count. */
    public fun execute(sql: String, vararg params: Any?): Int = db.write(sql, params)

    /** Reads inside the transaction, so a writer sees its own uncommitted rows. */
    public fun <T> query(sql: String, vararg params: Any?, map: (Row) -> T): List<T> = db.read(sql, params, map)
}

/**
 * Typed access to the current result row. The instance is reused across rows of one query, so a
 * mapper must read what it needs rather than keep the [Row].
 */
public class Row internal constructor(private val results: ResultSet) {
    public fun string(column: String): String =
        results.getString(column) ?: throw IllegalStateException("column '$column' is null")

    public fun string(index: Int): String =
        results.getString(index) ?: throw IllegalStateException("column $index is null")

    public fun stringOrNull(column: String): String? = results.getString(column)

    public fun long(column: String): Long = results.getLong(column)

    public fun double(column: String): Double = results.getDouble(column)

    public fun long(index: Int): Long = results.getLong(index)

    public fun longOrNull(column: String): Long? {
        val value = results.getLong(column)
        return if (results.wasNull()) null else value
    }

    public fun int(column: String): Int = results.getInt(column)

    /** SQLite has no boolean type; 0/1 integers are the stored form. */
    public fun bool(column: String): Boolean = results.getInt(column) != 0

    public fun bytes(column: String): ByteArray = results.getBytes(column)

    public fun json(column: String): JsonElement =
        Db.JSON.parseToJsonElement(string(column))

    public fun instant(column: String): Instant = Instant.parse(string(column))

    public fun digest(column: String): Digest = Digest(string(column))

    public fun digestOrNull(column: String): Digest? = stringOrNull(column)?.let(::Digest)

    /** The four identity columns every persisted record carries (§3.3). */
    public fun identities(): Identities = Identities(
        work = WorkId(string("work_id")),
        attempt = AttemptId(string("attempt_id")),
        candidate = stringOrNull("candidate_id")?.let { CandidateId(Digest(it)) },
        context = stringOrNull("context_id")?.let(::ContextId),
    )
}
