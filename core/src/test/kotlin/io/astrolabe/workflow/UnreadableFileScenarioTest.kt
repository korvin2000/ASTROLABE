package io.astrolabe.workflow

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan §7.2 WF-4: a file another process holds (a tool's lock file, as Gradle's was in R2) does not end the run. The
 * outcome is a resumable stop that names the path — never a failed cell, a thrown run (`agent_error` in a host) or a
 * candidate that silently leaves the file out.
 */
class UnreadableFileScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    @Test
    fun `WF-4 a locked file stops the run resumably and names the path`(): Unit = runBlocking {
        DirtyRepo.create(10, DirtyRepo.Variant.LockedFile).use { dirty ->
            Scenario(dirty.root, stateRoot).use { s ->
                s.seed(dirty.check)
                s.open()
                when (val lock = dirty.lock()) {
                    is DirtyRepo.Lock.Unsupported -> assumeTrue(false, lock.reason)
                    is DirtyRepo.Lock.Held -> lock.use {
                        val played = runCatching {
                            s.play { c -> Scenario.editThenVerify(c, DirtyRepo.SOURCE, "    return sum(items)", "    return sum(x for x in items if x >= 0)") }
                        }
                        assertNull(played.exceptionOrNull(), "the run returns an outcome instead of throwing: ${played.exceptionOrNull()?.stackTraceToString()?.take(4000)}")
                        val state = assertNotNull(played.getOrThrow().state)
                        val outcome = assertNotNull(state.outcome, "the run stops: ${state.reason}")
                        assertTrue(outcome.resumable, "the stop is resumable, not ${outcome.wire}: ${state.reason}")
                        assertTrue(DirtyRepo.LOCKED in state.reason.orEmpty(), "the stop names ${DirtyRepo.LOCKED}: ${state.reason}")
                    }
                }
            }
        }
    }
}
