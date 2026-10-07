package io.astrolabe.os

import io.astrolabe.fixtures.TempRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T-13 (plan §7.2): a phase's counters are its own calls' counts, not differences of totals on instances it shares with
 * a host — a host's `git` call on the same instance while the phase runs is not the phase's.
 */
class PhaseTallyTest {
    @Test
    fun `a phase counts its own git processes and never a concurrent caller's on the same instance`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "a\n")
            repo.commit("initial")
            val git = repo.git
            val before = git.processesStarted
            git.revParse("HEAD")
            val one = git.processesStarted - before
            val tally = PhaseTally.open()
            val host = Thread { repeat(3) { git.revParse("HEAD") } }
            tally.within {
                host.start()
                git.revParse("HEAD")
                host.join()
            }
            assertEquals(one, tally[PhaseTally.Count.GitProcesses], "the host's three calls are not the phase's")
            assertEquals(one * 5, git.processesStarted - before, "the instance total counts everyone")
        }
    }

    @Test
    fun `a nested phase adds to the phase it runs in and a suspending phase keeps its tally across threads`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "a\n")
            repo.commit("initial")
            val git = repo.git
            val before = git.processesStarted
            git.revParse("HEAD")
            val one = git.processesStarted - before
            val outer = PhaseTally.open()
            lateinit var inner: PhaseTally
            outer.within {
                inner = PhaseTally.open()
                inner.within { git.revParse("HEAD") }
                git.revParse("HEAD")
            }
            assertEquals(one, inner[PhaseTally.Count.GitProcesses])
            assertEquals(2 * one, outer[PhaseTally.Count.GitProcesses])

            val suspending = PhaseTally.open()
            runBlocking {
                withContext(suspending.element) {
                    withContext(Dispatchers.IO) { git.revParse("HEAD") }
                    git.revParse("HEAD")
                }
                git.revParse("HEAD")
            }
            assertEquals(2 * one, suspending[PhaseTally.Count.GitProcesses], "the element follows the coroutine onto the IO thread")
        }
    }
}
