package io.astrolabe

import io.astrolabe.fixtures.JavaConsumerSmoke
import io.astrolabe.fixtures.TestKit
import kotlin.test.Test
import kotlin.test.assertEquals

class SkeletonTest {
    @Test
    fun `java test fixture compiles against core`() {
        assertEquals("astrolabe-core via astrolabe-provider-api", JavaConsumerSmoke.describe())
        assertEquals("astrolabe-core-testFixtures", TestKit.NAME)
    }
}
