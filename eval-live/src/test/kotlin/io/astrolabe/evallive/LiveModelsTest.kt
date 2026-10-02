package io.astrolabe.evallive

import net.ai.gate.Llm
import net.ai.gate.auth.Environment
import net.ai.gate.vendors.openai.OpenAiCompatible
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Without a key the runner stops before any run and names the variable the SDK reads; offline, no key value is read. */
class LiveModelsTest {
    @Test
    fun `a missing OpenRouter key is a clear error naming OPENROUTER_API_KEY`() {
        Llm.builder().provider(OpenAiCompatible.openRouter()).environment(Environment.none()).catalog { it.offline() }.build().use { llm ->
            val failure = assertFailsWith<MissingKey> { LiveModels(llm, "openrouter", LocalDate.of(2026, 10, 2)).requireKey() }
            assertTrue("OPENROUTER_API_KEY" in failure.message.orEmpty(), failure.message)
        }
    }
}
