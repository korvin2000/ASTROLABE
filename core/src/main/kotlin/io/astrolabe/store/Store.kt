package io.astrolabe.store

import io.astrolabe.Config
import io.astrolabe.id.Identities
import io.astrolabe.id.InstantSerializer
import io.astrolabe.os.Git
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/**
 * One row of a store table, read generically. The typed record of most tables is declared by a later
 * phase (P1.1 contract, P1.4 evidence, P1.5 register), so until then a reader gets the identity
 * columns, the schema version that wrote the row, its capture time and the record itself as JSON —
 * enough for the read projections of [io.astrolabe.event.Views] and for exports, and nothing that a
 * later typed decoding has to undo.
 */
@Serializable
public data class StoredRow(
    val table: String,
    val key: String,
    val identities: Identities,
    val schemaVersion: Int,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val body: JsonElement,
)

/**
 * Maps the current row to a [StoredRow]. [key] is the caller-assembled primary key of the row, so a
 * projection can name a record without knowing which columns key its table.
 */
public fun Row.storedRow(table: String, key: String): StoredRow = StoredRow(
    table = table,
    key = key,
    identities = identities(),
    schemaVersion = int("schema_version"),
    createdAt = instant("created_at"),
    body = json("body"),
)

/**
 * The durable state of one project: identity, layout, ownership, records and blobs (§4, D-44).
 *
 * `open` performs the whole ordering once — repository identity → layout → controller lock →
 * database with verified pragmas → migrations → blob store — and fails closed at the first step that
 * cannot give the guarantee the records assume. A second controller process never reaches the
 * database, because the lock precedes it.
 *
 * The durability this store provides per platform is stated on [Layout].
 */
public class Store internal constructor(
    public val identity: RepoIdentity,
    public val layout: Layout,
    public val db: Db,
    public val blobs: BlobStore,
    private val lock: ProjectLock,
) : AutoCloseable {

    /** The schema version the database is at; every row written carries it. */
    public val schemaVersion: Int get() = Migrations.version(db)

    /** The controller process that owns this store. */
    public val holder: LockHolder get() = lock.holder

    /** Releases the database and then ownership, in that order. */
    override fun close() {
        try {
            db.close()
        } finally {
            lock.close()
        }
    }

    public companion object {
        /**
         * Opens the store of [git]'s repository under [stateRoot] (`null` selects the OS user-state
         * directory). All linked worktrees of one repository resolve to the same store.
         */
        @JvmStatic
        public fun open(
            stateRoot: Path?,
            git: Git,
            clock: Clock,
            faults: FaultPoints = FaultPoints.NONE,
        ): Store {
            val identity = RepoIdentity.of(git)
            val layout = Layout.resolve(stateRoot, identity).create()
            val lock = ProjectLock.acquire(layout, clock)
            try {
                val db = Db.open(layout)
                try {
                    Migrations.apply(db, clock)
                    return Store(identity, layout, db, BlobStore(layout, db, clock, faults), lock)
                } catch (failure: Throwable) {
                    db.close()
                    throw failure
                }
            } catch (failure: Throwable) {
                lock.close()
                throw failure
            }
        }

        /** The same, taking the host-configured root from [Config.stateRoot] (D-44). */
        @JvmStatic
        public fun open(
            config: Config,
            git: Git,
            clock: Clock,
            faults: FaultPoints = FaultPoints.NONE,
        ): Store = open(config.stateRoot?.let(Path::of), git, clock, faults)
    }
}
