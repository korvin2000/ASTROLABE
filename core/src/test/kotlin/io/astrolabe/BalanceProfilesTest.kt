package io.astrolabe

import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Effort
import io.astrolabe.provider.PriceTier
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** C3 (plan §4.6, §11 №8): the profile table, its application at freeze and the owner's slowdown ceiling. */
class BalanceProfilesTest {
    @Test
    fun `no profile is 2x slower than Balanced (soft) nor 3x (hard) in requests or time`() {
        for (profile in BalanceProfile.entries) {
            val slowdown = BalanceProfiles.slowdown(BalanceProfiles.vector(profile))
            assertTrue(slowdown.requests <= BalanceProfiles.SOFT_SLOWDOWN && slowdown.time <= BalanceProfiles.SOFT_SLOWDOWN, "$profile: $slowdown")
            assertTrue(slowdown.worst < BalanceProfiles.HARD_SLOWDOWN, "$profile: $slowdown")
        }
        assertEquals(Slowdown(1.0, 1.0), BalanceProfiles.slowdown(BalanceProfiles.vector(BalanceProfile.Balanced)))
        val economy = BalanceProfiles.slowdown(BalanceProfiles.vector(BalanceProfile.Economy))
        assertEquals(16.0 / 9, economy.requests, 1e-9)
        assertEquals(16.0 / 9, economy.time, 1e-9, "lower effort and fewer checks are never credited")
        val thorough = BalanceProfiles.slowdown(BalanceProfiles.vector(BalanceProfile.Thorough))
        assertEquals(1.0, thorough.requests, 1e-9, "larger results never page into more calls")
        assertEquals(1.5 * (0.7 + 0.3 / 0.6), thorough.time, 1e-9)
    }

    @Test
    fun `the bound catches the starting numbers the owner's ceiling replaced`() {
        // F §4.2 Economy relative to Balanced: results 2k of 4k, window 0.25 of 0.4 — over the hard ceiling.
        val f = BalanceVector(-1, -1, VerificationDepth.Declared, 0.5, 0.625, 2.0)
        val slowdown = BalanceProfiles.slowdown(f)
        assertEquals(3.2, slowdown.requests, 1e-9)
        assertTrue(slowdown.worst >= BalanceProfiles.HARD_SLOWDOWN)
        // Thorough with effort +2 on dear models and the full suite every other increment: over the soft ceiling.
        assertTrue(BalanceProfiles.slowdown(BalanceVector(0, 2, VerificationDepth.Extended, 2.0, 1.0, 4.0)).time > BalanceProfiles.SOFT_SLOWDOWN)
    }

    @Test
    fun `Balanced is the declared defaults - choosing no profile changes nothing`() {
        val config = Config(profiles = FakeProfiles.all)
        assertSame(config, BalanceProfiles.applied(config, BalanceProfile.Balanced))
        assertEquals(Defaults(), BalanceProfiles.defaults(Defaults(), BalanceProfiles.vector(BalanceProfile.Balanced)))
        assertSame(FakeProfiles.main, BalanceProfiles.bounded(FakeProfiles.main, BalanceProfiles.vector(BalanceProfile.Balanced)))
        for (effort in Effort.entries) for (model in ModelClass.entries) {
            assertEquals(effort, BalanceProfiles.effort(effort, BalanceProfiles.vector(BalanceProfile.Balanced), model))
        }
        // A Balanced attempt keeps the fingerprint it had before profiles existed: the default is not encoded.
        assertTrue("balance" !in Json { encodeDefaults = true }.encodeToString(Config.serializer(), config))
        assertTrue("\"balance\":\"Economy\"" in Json { encodeDefaults = true }.encodeToString(Config.serializer(), config.withBalance(BalanceProfile.Economy)))
        assertNotEquals(AttemptConfig.freeze(config).fingerprint, AttemptConfig.freeze(BalanceProfiles.applied(config, BalanceProfile.Economy)).fingerprint)
    }

    @Test
    fun `the profile table with the declared defaults`() {
        val economy = BalanceProfiles.applied(Config(), BalanceProfile.Economy)
        assertEquals(BalanceProfile.Economy, economy.balance)
        assertEquals(3_000, economy.defaults.lookBudgetTokens)
        assertEquals(3_000, economy.defaults.runBudgetTokens)
        assertEquals(Int.MAX_VALUE, economy.defaults.fullSuiteCadence, "the full suite at the campaign end only")
        val thorough = BalanceProfiles.applied(Config(), BalanceProfile.Thorough)
        assertEquals(8_000, thorough.defaults.lookBudgetTokens)
        assertEquals(8_000, thorough.defaults.runBudgetTokens)
        assertEquals(3, thorough.defaults.fullSuiteCadence)
        assertTrue(economy.defaults.violations().isEmpty() && thorough.defaults.violations().isEmpty())
        assertEquals(listOf(2.0, 3.0, 4.0), BalanceProfile.entries.map { BalanceProfiles.vector(it).stopLossFactor }, "E4 shadow values only")
        // Everything but the applied knobs stays the host's.
        assertEquals(Defaults().copy(lookBudgetTokens = 3_000, runBudgetTokens = 3_000, fullSuiteCadence = Int.MAX_VALUE), economy.defaults)
    }

    @Test
    fun `effort follows the owner's rule by price class - a cheap model up to high, a dear one to medium`() {
        assertEquals(ModelClass.Expensive, BalanceProfiles.modelClass(FakeProfiles.main), "15 USD per million output")
        val cheapPrices = FakeProfiles.main.priceTable.copy(perMillion = FakeProfiles.main.priceTable.perMillion + (BillingDimension.OUTPUT to BigDecimal("1.10")))
        val cheap = FakeProfiles.main.copy(priceTable = cheapPrices)
        assertEquals(ModelClass.Cheap, BalanceProfiles.modelClass(cheap))
        val economy = BalanceProfiles.vector(BalanceProfile.Economy)
        val thorough = BalanceProfiles.vector(BalanceProfile.Thorough)
        // With the owner's Balanced efforts configured (cheap high, dear medium), the table of F §4.2 follows.
        assertEquals(Effort.Medium, BalanceProfiles.effort(Effort.High, economy, ModelClass.Cheap))
        assertEquals(Effort.Low, BalanceProfiles.effort(Effort.Medium, economy, ModelClass.Expensive))
        assertEquals(Effort.High, BalanceProfiles.effort(Effort.High, thorough, ModelClass.Cheap))
        assertEquals(Effort.High, BalanceProfiles.effort(Effort.Medium, thorough, ModelClass.Expensive))
        assertEquals(Effort.Low, BalanceProfiles.effort(Effort.Low, economy, ModelClass.Cheap), "never stepped below low")
        assertEquals(Effort.High, BalanceProfiles.effort(Effort.High, thorough, ModelClass.Expensive), "never above high")
    }

    @Test
    fun `the economy window stays below the first price tier only as far as the owner's ceiling allows`() {
        val economy = BalanceProfiles.vector(BalanceProfile.Economy)
        assertEquals(150_000, BalanceProfiles.contextLimitTokens(FakeProfiles.main, economy))
        val prices = FakeProfiles.main.priceTable
        val tiered = FakeProfiles.main.copy(priceTable = prices.copy(tiers = listOf(PriceTier(128_000, prices.perMillion), PriceTier(64_000, prices.perMillion))))
        val cheapTiered = tiered.copy(priceTable = tiered.priceTable.copy(perMillion = prices.perMillion + (BillingDimension.OUTPUT to BigDecimal("1.10"))))
        // The tier at 64k would leave 0.32 of the window: (1/0.75)·(1/0.32) = 4.17. The ceiling's floor keeps 2/3 of it.
        assertEquals(133_334, BalanceProfiles.contextLimitTokens(tiered, economy))
        assertEquals(133_334, BalanceProfiles.bounded(tiered, economy).capabilities.contextLimitTokens)
        val tight = FakeProfiles.main.copy(priceTable = prices.copy(tiers = listOf(PriceTier(160_000, prices.perMillion))))
        assertEquals(150_000, BalanceProfiles.contextLimitTokens(tight, economy), "below the tier and above the floor: the share")
        for (profile in listOf(FakeProfiles.main, tiered, cheapTiered, tight)) for (balance in BalanceProfile.entries) {
            val slowdown = BalanceProfiles.slowdown(BalanceProfiles.vector(balance), profile)
            assertTrue(slowdown.worst <= BalanceProfiles.SOFT_SLOWDOWN + 1e-9, "$balance on ${profile.priceTable.tiers} (${BalanceProfiles.modelClass(profile)}): $slowdown")
        }
        assertEquals(ModelClass.Cheap, BalanceProfiles.modelClass(cheapTiered))
        assertEquals(200_000, BalanceProfiles.contextLimitTokens(tiered, BalanceProfiles.vector(BalanceProfile.Thorough)), "a whole window is not bounded")
    }
}
