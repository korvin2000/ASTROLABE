package io.astrolabe.eval.samples

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.fail

/** Deliberately red fixtures for the runner's own tests; the build excludes the tag (eval/build.gradle.kts). */
@Tag("runner-sample")
class RunnerSampleFixtures {
    @Test
    fun `FX-02 sample - a hunk in a never-displayed region is rejected`() {}

    @Test
    fun `FX-50 sample - an unseen-body edit slips through`() {
        fail("edit applied to an unread body")
    }

    @Test
    fun `FX-8 sample - skipped on this platform`() = assumeTrue(false, "platform")

    @Test
    fun `AX-01 sample - adapter fixture`() {}

    @Test
    fun `a plain test without a fixture id`() {}
}

/**
 * A @ParameterizedTest generates its invocations at execution time, past `PostDiscoveryFilter`'s reach
 * (it can only prune the static tree, where the method is a container); neither the method nor any
 * invocation names a fixture id here, unlike `core`'s own (non-fixture) parameterized tests (D-260).
 */
@Tag("runner-sample")
class RunnerSampleParameterized {
    @ParameterizedTest
    @ValueSource(strings = ["a", "b"])
    fun `a parameterized test that names no fixture`(value: String) {}
}
