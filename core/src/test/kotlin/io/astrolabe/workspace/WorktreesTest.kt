package io.astrolabe.workspace

import io.astrolabe.campaign.GrantRefused
import io.astrolabe.campaign.Leases
import io.astrolabe.contract.Scope
import io.astrolabe.id.ContextId
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.Generation
import io.astrolabe.id.WorkspaceId
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** P5.1.1: worktrees are workspace-qualified (FX-51), removal never touches user branches, ownership serializes overlaps. */
class WorktreesTest {

    @Test
    fun `FX-51 the same path and hash in two worktrees keep separate coverage and shadow refs`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "def a():\n    return 42\n")
            fixture.repo.write("notes.txt", "untracked\n")
            val workspaces = Workspaces(fixture.workspace, fixture.store.layout.candidates, TEST_ENV)
            val main = Stamper(fixture.workspace, TEST_ENV).report().candidateId
            val a = workspaces.createWorktree(fixture.ids.work, fixture.ids.attempt, "I1")
            val b = workspaces.createWorktree(fixture.ids.work, fixture.ids.attempt, "I2")
            assertNotEquals(a.id, b.id)
            for (tree in listOf(a, b)) {
                assertEquals(main, tree.base, "a worktree reproduces the main candidate")
                assertEquals(main, Stamper(tree.workspace, TEST_ENV).report().candidateId)
                assertTrue(tree.root.startsWith(fixture.store.layout.candidates.toRealPath()), "worktrees live under candidates/")
            }
            assertContentEquals(fixture.bytes("src/a.py"), Files.readAllBytes(b.root.resolve("src/a.py")))

            val ra = VersionRegistry(a.workspace)
            val rb = VersionRegistry(b.workspace)
            val va = ra.version("src/a.py")!!
            assertEquals(va, rb.version("src/a.py"), "same path, same content hash")
            val context = ContextId("ctx-1")
            ra.show(context, Generation.INITIAL, a.id, "src/a.py", va, LineRange(1, 2))
            assertFalse(ra.displayed(context, Generation.INITIAL, a.id, "src/a.py", va).isEmpty)
            assertTrue(ra.displayed(context, Generation.INITIAL, b.id, "src/a.py", va).isEmpty, "coverage is keyed by workspace")
            assertTrue(rb.displayed(context, Generation.INITIAL, b.id, "src/a.py", va).isEmpty, "the other worktree was shown nothing")

            fun shadow(tree: Worktree) = ShadowRef(fixture.ids.work, fixture.ids.attempt, tree.workspace, fixture.store, DirtyState(tree.workspace, fixture.store.blobs, Stamper(tree.workspace, TEST_ENV), fixture.ids, fixture.clock), fixture.os, fixture.clock)
            val sa = shadow(a)
            val sb = shadow(b)
            assertNotEquals(sa.ref, sb.ref, "ordinary refs are shared across worktrees: the shadow ref carries the workspace (F02)")
            sa.open(DirtyState(a.workspace, fixture.store.blobs, Stamper(a.workspace, TEST_ENV), fixture.ids, fixture.clock).capture())
            val bHead = sb.open(DirtyState(b.workspace, fixture.store.blobs, Stamper(b.workspace, TEST_ENV), fixture.ids, fixture.clock).capture()).commit
            Files.writeString(a.root.resolve("src/a.py"), "def a():\n    return 43\n")
            sa.snapshot(1)
            assertEquals(bHead, sb.head()!!.hex, "a snapshot in one worktree never moves the other's ref")
            assertEquals(2, sa.records().size)
            assertEquals(1, sb.records().size)
            workspaces.remove(a)
            workspaces.remove(b)
        }
    }

    @Test
    fun `worktree removal never touches user branches or refs`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.rawGit("branch", "feature")
            val refs = fixture.userRefs()
            val head = fixture.rawGit("symbolic-ref", "HEAD")
            val workspaces = Workspaces(fixture.workspace, fixture.store.layout.candidates, TEST_ENV)
            val tree = workspaces.createWorktree(fixture.ids.work, fixture.ids.attempt, "I1")
            assertTrue(fixture.rawGit("worktree", "list", "--porcelain").contains("detached"), "a worktree is detached: it owns no branch")
            Files.writeString(tree.root.resolve("src/b.py"), "def b():\n    return 9\n")
            workspaces.remove(tree)
            assertFalse(Files.exists(tree.root))
            assertEquals(refs, fixture.userRefs(), "no branch or ref was created, moved or deleted")
            assertEquals(head, fixture.rawGit("symbolic-ref", "HEAD"))
            assertEquals(1, fixture.rawGit("worktree", "list", "--porcelain").lines().count { it.startsWith("worktree ") })
            assertFailsWith<WorktreeRefused> { workspaces.remove(tree) }
        }
    }

    @Test
    fun `ownership grants disjoint scopes and serializes overlaps, unknowns and interface changes`() {
        val ownership = Ownership(WorkspaceId("ws-main"))
        fun scope(vararg writes: String) = Scope(writes.toList(), emptyList())
        assertIs<OwnershipAdmission.Granted>(ownership.claim(OwnershipClaim("I1", scope("src/billing/"))))
        assertIs<OwnershipAdmission.Granted>(ownership.claim(OwnershipClaim("I2", scope("src/report/**"))))
        val overlap = assertIs<OwnershipAdmission.Serialized>(ownership.claim(OwnershipClaim("I3", scope("src/**/total.py"))))
        assertEquals("I1", overlap.behind)
        assertTrue(overlap.reason.startsWith("write scopes overlap at src/billing/"), overlap.reason)
        assertIs<OwnershipAdmission.Serialized>(ownership.claim(OwnershipClaim("I4", scope("docs/"), interfaceChange = true)))
        assertEquals("I2", ownership.owner("src/report/x.py"))
        assertEquals(null, ownership.owner("docs/a.md"))
        val tight = Ownership(WorkspaceId("ws-main"), ScopeSearchLimits(1, 1, 1))
        tight.claim(OwnershipClaim("I1", scope("src/a/")))
        assertTrue(assertIs<OwnershipAdmission.Serialized>(tight.claim(OwnershipClaim("I2", scope("src/b/")))).reason.startsWith("disjointness unproved"))
        assertTrue(ownership.release("I1"))
        assertIs<OwnershipAdmission.Granted>(ownership.claim(OwnershipClaim("I3", scope("src/billing/total.py"))), "a released scope is free again")
    }

    @Test
    fun `a workspace another writer held is granted only after its unknown effects are reconciled`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val leases = Leases(fixture.store, fixture.clock)
            val ws = WorkspaceId("wt-I1")
            val first = leases.acquire(ws, fixture.ids, "writer-1", Duration.ofMinutes(1))
            fixture.clock.advance(Duration.ofMinutes(2))
            val refused = assertFailsWith<GrantRefused> { leases.acquire(ws, fixture.ids, "writer-2", Duration.ofMinutes(1), listOf("intent-7")) }
            assertEquals(first, refused.previous)
            assertTrue(refused.message!!.contains("intent-7"))
            assertEquals(ExecutionGeneration.INITIAL.next(), leases.acquire(ws, fixture.ids, "writer-2", Duration.ofMinutes(1)).generation)
            assertEquals(first.generation, leases.acquire(WorkspaceId("wt-I2"), fixture.ids, "writer-1", Duration.ofMinutes(1), listOf("intent-7")).generation, "a fresh workspace has no previous owner")
        }
    }
}
