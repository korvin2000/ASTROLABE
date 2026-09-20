package io.astrolabe.workspace

import io.astrolabe.fixtures.StoreInspector
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.CapturedStamp
import io.astrolabe.id.Digest
import io.astrolabe.id.Stamp
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.FileMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * P1.2.2 / IX-05: candidate identity over a versioned canonical encoding. Equal trees stamp equal
 * whenever they are captured; an edit, a changed lock file or a changed tool version do not.
 */
class StamperTest {

    @Test
    fun `equal trees stamp equal across runs and capture times`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "def a():\n    return 7\n")
            fixture.repo.untracked("notes.txt", "scratch\n")

            val first = fixture.stamper.capture(fixture.clock)
            fixture.clock.advance(Duration.ofHours(3))
            val second = fixture.stamper.capture(fixture.clock)

            assertEquals(first.report.stamp, second.report.stamp)
            assertEquals(first.report.stamp.id, second.report.stamp.id)
            assertNotEquals(first.at, second.at, "the capture time moved and the identity did not (I-05)")
        }
    }

    @Test
    fun `editing a file changes the stamp and the diff names the path`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val before = fixture.stamper.report()

            fixture.repo.modify("src/a.py", "def a():\n    return 7\n")
            val after = fixture.stamper.report()

            assertNotEquals(before.stamp, after.stamp)
            assertEquals(before.baseCommit, after.baseCommit, "the base commit did not move; the delta did")
            assertNotEquals(before.stamp.trackedDeltaHash, after.stamp.trackedDeltaHash)
            assertEquals(before.stamp.untrackedManifestHash, after.stamp.untrackedManifestHash)
            assertEquals(setOf("src/a.py"), Stamper.diff(before, after))
        }
    }

    @Test
    fun `an untracked file enters the untracked manifest and an ignored one is counted, not hashed`(
        @TempDir state: Path,
    ) {
        WorkspaceFixture.create(state) { repo ->
            repo.write(".gitignore", "build/\n")
            repo.commit("ignore build")
        }.use { fixture ->
            val clean = fixture.stamper.report()
            assertEquals(0, clean.ignoredCount)

            fixture.repo.untracked("build/out.o", "binary\n")
            val ignored = fixture.stamper.report()
            assertTrue((ignored.ignoredCount ?: 0) >= 1, "the exclusion is recorded: ${ignored.ignoredCount}")
            assertEquals(clean.stamp, ignored.stamp, "an ignored file is outside candidate identity")

            fixture.repo.untracked("scratch.txt", "notes\n")
            val untracked = fixture.stamper.report()
            assertEquals(listOf("scratch.txt"), untracked.untracked.map { it.path })
            assertNotEquals(clean.stamp.untrackedManifestHash, untracked.stamp.untrackedManifestHash)
            assertEquals(setOf("scratch.txt"), Stamper.diff(ignored, untracked))
        }
    }

    @Test
    fun `a deleted tracked file is a member with no digest`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            Files.delete(fixture.repo.resolve("src/b.py"))

            val report = fixture.stamper.report()
            val entry = report.members.getValue("src/b.py")

            assertEquals(EntryType.Deleted, entry.type)
            assertEquals(FileMode.ABSENT, entry.mode)
            assertNull(entry.digest)
        }
    }

    @Test
    fun `a staged change is part of the tracked delta even with a clean worktree`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val before = fixture.stamper.report()

            fixture.repo.write("src/a.py", "def a():\n    return 5\n")
            fixture.repo.stage("src/a.py")
            val after = fixture.stamper.report()

            assertTrue("src/a.py" in after.members, "staged or not, the tracked path differs from HEAD")
            assertNotEquals(before.stamp, after.stamp)
        }
    }

    // ---------------------------------------------------------- environment

    @Test
    fun `a changed lock file changes the env id and therefore the stamp`(@TempDir state: Path) {
        WorkspaceFixture.create(state) { repo ->
            repo.write("poetry.lock", "version = 1\n")
            repo.commit("lock")
        }.use { fixture ->
            val firstLocks = EnvFingerprint.lockDigests(fixture.workspace)
            assertEquals(setOf("poetry.lock"), firstLocks.keys)

            val base = EnvInputs(osName = "os", osArch = "x64", lockDigests = firstLocks)
            val before = EnvFingerprint.compute(base)

            fixture.repo.write("poetry.lock", "version = 2\n")
            val after = EnvFingerprint.compute(base.copy(lockDigests = EnvFingerprint.lockDigests(fixture.workspace)))

            assertNotEquals(before.envId, after.envId)

            // Both stamps are taken over the same tree, so only the environment can separate them.
            val withOldEnv = Stamper(fixture.workspace, before).stamp()
            val withNewEnv = Stamper(fixture.workspace, after).stamp()
            assertEquals(withOldEnv.trackedDeltaHash, withNewEnv.trackedDeltaHash)
            assertNotEquals(withOldEnv, withNewEnv)
            assertNotEquals(withOldEnv.id, withNewEnv.id)
        }
    }

    @Test
    fun `a changed tool version changes the env id`() {
        val base = EnvInputs(osName = "os", osArch = "x64", toolVersions = mapOf("python" to "3.12.4"))

        val before = EnvFingerprint.compute(base)
        val after = EnvFingerprint.compute(base.copy(toolVersions = mapOf("python" to "3.13.0")))

        assertNotEquals(before.envId, after.envId)
        assertTrue(before.envKnown && after.envKnown)
    }

    @Test
    fun `environment values enter the identity only as digests`() {
        val base = EnvInputs(osName = "os", osArch = "x64", environmentValues = mapOf("TOKEN" to "s3cret"))

        val fingerprint = EnvFingerprint.compute(base)

        assertEquals(mapOf("TOKEN" to Digest.ofUtf8("s3cret")), fingerprint.environmentValueDigests)
        assertNotEquals(
            fingerprint.envId,
            EnvFingerprint.compute(base.copy(environmentValues = mapOf("TOKEN" to "other"))).envId,
            "D-13: names alone are insufficient, so the value's digest is in the identity",
        )
        assertTrue(
            !fingerprint.toString().contains("s3cret"),
            "the raw value is never carried: ${fingerprint.environmentValueDigests}",
        )
    }

    @Test
    fun `an unknown relevant input leaves envKnown false without changing the run`() {
        val known = EnvFingerprint.compute(EnvInputs(osName = "os", osArch = "x64"))
        val unknown = EnvFingerprint.compute(
            EnvInputs(osName = "os", osArch = "x64", unknownInputs = listOf("node: not on PATH")),
        )

        assertTrue(known.envKnown)
        assertTrue(!unknown.envKnown, "D-13: an unresolved relevant input blocks cross-candidate reuse")
        assertNotEquals(known.envId, unknown.envId)
    }

    @Test
    fun `the canonical encodings are ordered by path and independent of insertion order`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.untracked("z.txt", "z\n")
            fixture.repo.untracked("a.txt", "a\n")
            val report = fixture.stamper.report()

            assertEquals(listOf("a.txt", "z.txt"), report.untracked.map { it.path })
        }
    }

    // --------------------------------------------------------------- storage

    @Test
    fun `a captured stamp is one row whose at column is metadata`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val captured = fixture.stamper.capture(fixture.clock).captured
            Stamper.record(fixture.store, fixture.ids, captured)
            fixture.clock.advance(Duration.ofMinutes(5))
            val later = CapturedStamp(captured.stamp, fixture.clock.instant())
            Stamper.record(fixture.store, fixture.ids, later)

            val inspector = StoreInspector(fixture.store)
            assertEquals(1L, inspector.count("stamps"), "the same candidate captured twice keeps one row")
            val row = inspector.rows("stamps", "stamp_id").single()
            assertEquals(captured.stamp.id.digest.hex, row.key)
        }
    }

    @Test
    fun `a repository without commits stamps against the no-commit marker`(@TempDir state: Path) {
        val repo = TempRepo.create()
        repo.use {
            repo.write("only.txt", "hello\n")
            val workspace = Workspace(WorkspaceId("ws-unborn"), repo.root, repo.git)
            val stamper = Stamper(workspace, TEST_ENV)

            val report = stamper.report()

            assertEquals(Stamp.NO_COMMIT, report.baseCommit)
            assertEquals(listOf("only.txt"), report.untracked.map { it.path })
            assertTrue(report.trackedDelta.isEmpty())
        }
    }
}
