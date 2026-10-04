package io.astrolabe.evallive

import io.astrolabe.RunSpec
import io.astrolabe.fixtures.FakeProfiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * WP-B2: the start scripts put the bench's own location into the runner's environment (`eval-live.bat` sets `APP_HOME`
 * and `CLASSPATH` with `set`, so the JVM inherits them; `JAVA_OPTS` and `EVAL_LIVE_OPTS` are read from it). The agent's
 * processes (`run`, `verify`, transforms) never inherit the environment implicitly: the core hands them the platform
 * essentials plus the attempt's `redaction.envAllowlist` (`EnvPolicy`). None of these names may be on it.
 */
class EnvironmentTest {
    @TempDir
    lateinit var state: Path

    @Test
    fun `no variable of the start scripts reaches the agent's processes`() {
        val allowlist = RunSpec.defaults(FakeProfiles.main, state.toString()).config.redaction.envAllowlist.map { it.uppercase() }.toSet()
        val launcher = setOf("APP_HOME", "APP_BASE_NAME", "DIRNAME", "CLASSPATH", "JAVA_OPTS", "EVAL_LIVE_OPTS", "DEFAULT_JVM_OPTS", "JAVA_EXE", "CMD_LINE_ARGS")
        assertTrue(allowlist.intersect(launcher).isEmpty(), "launcher variables on the allowlist: ${allowlist.intersect(launcher)}")
    }
}
