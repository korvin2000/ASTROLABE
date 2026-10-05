package io.astrolabe.workflow

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The §7.2 fixture itself: the shape the scenarios rely on, and what each variant's check does to the tree. These tests
 * count no reads, so their fixtures skip the racy-window wait.
 */
class DirtyRepoTest {
    @Test
    fun `the dirty repository has no gitignore, the untracked tool directory and the big file`() {
        DirtyRepo.create(300, settle = false).use { dirty ->
            assertFalse(Files.exists(dirty.root.resolve(".gitignore")))
            val untracked = dirty.repo.git.status().untracked.count { it.startsWith("${DirtyRepo.UNTRACKED_DIR}/") }
            assertTrue(untracked >= 300, "untracked under ${DirtyRepo.UNTRACKED_DIR}/: $untracked")
            assertTrue(Files.size(dirty.root.resolve(DirtyRepo.BIG)) >= DirtyRepo.BIG_BYTES)
        }
    }

    @Test
    fun `the scratch variant writes build output and the data variant rewrites the tracked data file`() {
        DirtyRepo.create(1, DirtyRepo.Variant.ScratchOutput, settle = false).use { dirty ->
            assertEquals(0, runCheck(dirty))
            assertTrue(Files.exists(dirty.root.resolve("build/report.txt")))
        }
        DirtyRepo.create(1, DirtyRepo.Variant.RewritesData, settle = false).use { dirty ->
            assertEquals(0, runCheck(dirty))
            assertEquals("[2]", Files.readString(dirty.root.resolve(DirtyRepo.DATA)).trim())
        }
    }

    @Test
    fun `the locked variant makes its file unreadable while the lock is held`() {
        DirtyRepo.create(1, DirtyRepo.Variant.LockedFile, settle = false).use { dirty ->
            when (val lock = dirty.lock()) {
                is DirtyRepo.Lock.Unsupported -> assumeTrue(false, lock.reason)
                is DirtyRepo.Lock.Held -> lock.use {
                    assertTrue(runCatching { Files.readAllBytes(dirty.root.resolve(DirtyRepo.LOCKED)) }.isFailure)
                }
            }
            assertTrue(Files.readAllBytes(dirty.root.resolve(DirtyRepo.LOCKED)).isNotEmpty(), "readable again after release")
        }
    }

    private fun runCheck(dirty: DirtyRepo): Int {
        val process = ProcessBuilder(dirty.check.argv).directory(dirty.root.toFile()).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue()
    }
}
