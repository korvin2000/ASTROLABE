package io.astrolabe.store

import io.astrolabe.Config
import io.astrolabe.fixtures.StoreInspector
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.os.Git
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** P0.5.1: opening a project store end to end from a repository (§4, D-44, D-15, D-26). */
class StoreTest {

    @TempDir
    lateinit var stateRoot: Path

    @TempDir
    lateinit var scratch: Path

    @Test
    fun `opening creates the layout, takes ownership and applies the schema`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "one")
            repo.commit("one")
            Store.open(stateRoot, repo.git, TEST_CLOCK).use { store ->
                assertEquals(Layout.resolve(stateRoot, store.identity).root, store.layout.root)
                assertTrue(store.layout.root.startsWith(stateRoot), "state must live under the configured root")
                assertTrue(
                    !store.layout.root.startsWith(repo.root),
                    "no authoritative state inside the source tree",
                )
                assertEquals(Migrations.SCHEMA_VERSION, store.schemaVersion)
                assertEquals(ProcessHandle.current().pid(), store.holder.pid)
                assertEquals(emptyList(), StoreInspector(store).missingTables())
                assertTrue(Files.exists(store.layout.database))
            }
        }
    }

    @Test
    fun `the host-configured state root comes from Config`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "one")
            repo.commit("one")
            val config = Config(stateRoot = stateRoot.toString())
            Store.open(config, repo.git, TEST_CLOCK).use { store ->
                assertTrue(store.layout.root.startsWith(stateRoot))
            }
        }
    }

    @Test
    fun `two sibling repositories do not collide`() {
        TempRepo.create().use { first ->
            TempRepo.create().use { second ->
                first.write("a.txt", "first")
                first.commit("first")
                second.write("a.txt", "second")
                second.commit("second")

                Store.open(stateRoot, first.git, TEST_CLOCK).use { a ->
                    Store.open(stateRoot, second.git, TEST_CLOCK).use { b ->
                        assertNotEquals(a.layout.root, b.layout.root)
                        a.insertRow("packets", "id" to "from-a", "kind" to "result")
                        assertEquals(1L, a.db.count("SELECT count(*) FROM packets"))
                        assertEquals(0L, b.db.count("SELECT count(*) FROM packets"), "stores are separate")
                    }
                }
            }
        }
    }

    @Test
    fun `linked worktrees share the store while keeping distinct workspaces`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "one")
            val commit = repo.commit("one")
            val linked = scratch.resolve("linked")
            repo.git.worktreeAdd(linked, commit)
            try {
                val mainRoot = Store.open(stateRoot, repo.git, TEST_CLOCK).use { store ->
                    store.insertRow(
                        "leases",
                        "workspace_id" to "workspace-main",
                        "holder" to "controller",
                        "expiry" to TEST_INSTANT.toString(),
                        "execution_generation" to 0,
                    )
                    store.layout.root
                }
                Store.open(stateRoot, Git(linked), TEST_CLOCK).use { store ->
                    assertEquals(mainRoot, store.layout.root, "worktrees share one project store")
                    store.insertRow(
                        "leases",
                        "workspace_id" to "workspace-linked",
                        "holder" to "controller",
                        "expiry" to TEST_INSTANT.toString(),
                        "execution_generation" to 0,
                    )
                    val workspaces = store.db.query("SELECT workspace_id FROM leases ORDER BY workspace_id") {
                        it.string("workspace_id")
                    }
                    assertEquals(
                        listOf("workspace-linked", "workspace-main"),
                        workspaces,
                        "shared store, distinct workspace ids — coverage is never shared",
                    )
                }
            } finally {
                repo.git.worktreeRemove(linked, force = true)
            }
        }
    }

    @Test
    fun `a second controller of the same project fails to acquire ownership`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "one")
            val commit = repo.commit("one")
            val linked = scratch.resolve("linked")
            repo.git.worktreeAdd(linked, commit)
            try {
                Store.open(stateRoot, repo.git, TEST_CLOCK).use {
                    assertFailsWith<ProjectLockHeld> { Store.open(stateRoot, repo.git, TEST_CLOCK) }
                    assertFailsWith<ProjectLockHeld> {
                        // A worktree is the same project, so it is the same single controller.
                        Store.open(stateRoot, Git(linked), TEST_CLOCK)
                    }
                }
                Store.open(stateRoot, repo.git, TEST_CLOCK).use { reopened ->
                    assertEquals(Migrations.SCHEMA_VERSION, reopened.schemaVersion)
                }
            } finally {
                repo.git.worktreeRemove(linked, force = true)
            }
        }
    }

    @Test
    fun `an unborn repository still gets a store`() {
        TempRepo.create().use { repo ->
            Store.open(stateRoot, repo.git, TEST_CLOCK).use { store ->
                assertTrue(store.identity.roots.isEmpty())
                assertEquals(Migrations.SCHEMA_VERSION, store.schemaVersion)
            }
        }
    }
}
