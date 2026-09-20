package io.astrolabe.fixtures

import java.io.IOException
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class RunnersTest {

    private val here = Paths.get("").toAbsolutePath()

    @Test
    fun `stdout and stderr stay separate`() {
        val python = Runners.python()
        assumeTrue(python != null, "no python interpreter on this host")
        val result = Runners.run(
            listOf(
                python!!,
                "-c",
                "import sys; sys.stdout.write('out\\n'); sys.stderr.write('err\\n')",
            ),
            here,
            TIMEOUT,
        )
        assertEquals(0, result.exitCode)
        assertEquals("out", result.stdout.trim())
        assertEquals("err", result.stderr.trim())
        assertTrue(result.succeeded)
    }

    @Test
    fun `a non-zero exit is reported, not thrown`() {
        val python = Runners.python()
        assumeTrue(python != null, "no python interpreter on this host")
        val result = Runners.run(listOf(python!!, "-c", "raise SystemExit(3)"), here, TIMEOUT)
        assertEquals(3, result.exitCode)
        assertTrue(!result.timedOut)
        assertTrue(!result.succeeded)
    }

    @Test
    fun `a run past its timeout is killed and marked, never silently green`() {
        val python = Runners.python()
        assumeTrue(python != null, "no python interpreter on this host")
        val result = Runners.run(
            listOf(python!!, "-c", "import time; time.sleep(120)"),
            here,
            timeoutSeconds = 2,
        )
        assertTrue(result.timedOut, "the sleeper should have outlived its box")
        assertTrue(!result.succeeded)
    }

    @Test
    fun `a program that is not on PATH raises rather than reporting a failed run`() {
        assertFailsWith<IOException> {
            Runners.run(listOf("astrolabe-no-such-program"), here, TIMEOUT)
        }
    }

    @Test
    fun `probes agree with the fixtures that need them`() {
        // Not an assertion about this host, but about the contract: a probe either names a
        // program that runs or returns null, so a test can skip visibly.
        for (runner in listOfNotNull(Runners.python(), Runners.node(), Runners.gradle())) {
            assertTrue(runner.isNotBlank())
        }
        if (Runners.pytest()) assertTrue(Runners.python() != null)
    }

    private companion object {
        const val TIMEOUT = 60L
    }
}
