package io.astrolabe.eval.samples

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
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
