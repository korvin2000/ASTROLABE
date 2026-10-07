package io.astrolabe.route

import io.astrolabe.budget.Tokens
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.store.TEST_CLOCK
import io.astrolabe.store.openStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** E1 (plan §4.6, §11.1): every routing decision is a `routing_log` row of its attempt, naming the binding snapshot. */
class RoutingLogTest {
    @TempDir
    lateinit var root: Path

    private val table = TierTable("t1", null, mapOf(Tier.Low to setOf("helper"), Tier.High to setOf("main"), Tier.ExtraHigh to setOf("escalation")))
    private val candidates = FakeProfiles.all.filterKeys { it in setOf("helper", "main", "escalation") }
    private val work = WorkId("W-1")
    private val a1 = Identities(work, AttemptId("a1"))
    private fun policy(budget: RoutingBudget = RoutingBudget()) = RoutingPolicy(table, candidates, budget)
    private fun packet() = RoutingPacket(null, 10_000, 4_000, featureClass = "s1:2")

    @Test
    fun `every routing decision writes one row in attempt order and all read one frozen snapshot`() {
        openStore(root).use { store ->
            val physics = BindingPhysics(store, TEST_CLOCK)
            val log = RoutingLog(store, TEST_CLOCK)
            val router = Router()
            physics.observe(BindingObservation(BindingKey.of(FakeProfiles.main, "relace"), a1, "i0", outputTokens = 10))

            val selected = assertIs<Routed.Selected>(router.selectProfile(RoutingFunction.Implementing, packet(), null, policy(), log, a1))
            // A call between decisions changes the live table, not the attempt's snapshot.
            physics.observe(BindingObservation(BindingKey.of(FakeProfiles.main, "relace"), a1, "i1", outputTokens = 20))
            val tight = RoutingBudget(remainingTokens = Tokens(12_000), reservedTokens = Tokens(2_000))
            assertIs<Routed.Refused>(router.selectProfile(RoutingFunction.Implementing, packet(), null, policy(tight), log, a1))
            assertIs<Routed.Deterministic>(router.selectProfile(RoutingFunction.Deterministic, packet(), null, policy(), log, a1))
            router.selectProfile(RoutingFunction.Probe, packet(), null, policy(), log, a1.copy(attempt = AttemptId("a2")))

            val decisions = log.decisions(work, AttemptId("a1"))
            assertEquals(listOf(1L, 2L, 3L), decisions.map { it.seq })
            assertEquals(listOf(RoutingDecisionKind.Selected, RoutingDecisionKind.Refused, RoutingDecisionKind.Deterministic), decisions.map { it.kind })
            assertEquals(listOf(1L, 1L, 1L), decisions.map { it.snapshotSeq }, "the attempt's decisions read the snapshot frozen at its first")
            assertEquals(1L, physics.frozen(work, AttemptId("a1"))?.asOfSeq)
            val first = decisions.first()
            assertEquals(selected.profile.id, first.profileId)
            assertEquals(BindingKey.of(selected.profile), first.bindingKey)
            assertEquals(Tier.High, first.tier)
            assertEquals("s1:2", first.featureClass)
            assertTrue(first.reason.startsWith("selected main at tier High"), first.reason)
            assertTrue("needs 14000 tokens" in decisions[1].reason, decisions[1].reason)
            assertEquals(Tier.Deterministic, decisions[2].tier)

            val other = log.decisions(work, AttemptId("a2")).single()
            assertEquals(1L, other.seq, "each attempt numbers its own decisions")
            assertEquals(2L, other.snapshotSeq)
            assertEquals(4L, store.db.count("SELECT count(*) FROM routing_log"))
            assertEquals(1L, store.db.count("SELECT count(*) FROM routing_log WHERE attempt_id = 'a1' AND seq = 1 AND outcome = 'Selected' AND binding_key = 'fake-main|fake|?|?'"))
        }
    }
}
