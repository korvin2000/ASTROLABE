package io.astrolabe.eval.audit

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P8.C.17 (D-421): the same flows re-priced at each profile's list prices — sums by hand, item shares adding up to 100 %. */
class RepricingTest {
    private val flows = Flows(requests = 40, uncachedInput = 1_000_000, cacheRead = 2_000_000, cacheWrite = 100_000, output = 500_000, reasoning = 200_000)

    private fun profile(name: String) = Repricing.PROFILES.single { it.name == name }

    @Test
    fun `synthetic flows cost what the profile's prices say`() {
        val expected = mapOf(
            // 1 M × 10 + 2 M × 1 + 0.1 M × 10 (no write price: as input) + 0.5 M × 50
            "Fable 5" to "38",
            "Qwen3.8 Max" to "5.7",
            "Grok 4.7" to "6.2",
            // no cache-read price: the 2 M read at input price 0.43
            "MiMo-V2.6-Pro, cache at input price" to "1.768",
            "MiMo-V2.6-Pro, cache read at 10 % of input" to "0.994",
            "deepseek-v4.1-flash" to "1.2093",
        )
        for ((name, money) in expected) {
            val repriced = Repricing.price(flows, profile(name))
            assertEquals(0, BigDecimal(money).compareTo(repriced.money), "$name: ${repriced.money}")
        }
        assertEquals(Repricing.PROFILES.map { it.name }, Repricing.of(flows).map { it.profile })
        assertEquals(emptyList<Repriced>(), Repricing.of(null), "unknown flows are not priced")
    }

    @Test
    fun `the item shares of every profile add up to 100 percent`() {
        for (r in Repricing.of(flows)) {
            val shares = listOf(r.uncachedInputShare, r.cacheReadShare, r.cacheWriteShare, r.outputShare).map { it!! }
            assertEquals(1.0, shares.sum(), 1e-9, r.profile)
            assertTrue(shares.all { it in 0.0..1.0 }, "$r")
        }
        val fable = Repricing.price(flows, profile("Fable 5"))
        assertEquals(25.0 / 38, fable.outputShare!!, 1e-9)
        val none = Repricing.price(Flows(0, 0, 0, 0, 0, 0), profile("Fable 5"))
        assertEquals(0, BigDecimal.ZERO.compareTo(none.money))
        assertNull(none.outputShare, "no money: no shares, never a division by zero")
    }

    @Test
    fun `flows sum over runs`() {
        assertEquals(Flows(80, 2_000_000, 4_000_000, 200_000, 1_000_000, 400_000), flows + flows)
    }
}
