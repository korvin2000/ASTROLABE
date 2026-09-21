package io.astrolabe.cell

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.evidence.InMemoryObservations
import io.astrolabe.evidence.Observation
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FakeTokenizer
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Effort
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.Item
import io.astrolabe.provider.Items
import io.astrolabe.provider.Message
import io.astrolabe.provider.ProblemKind
import io.astrolabe.provider.Request
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.Validation
import io.astrolabe.provider.estimate
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.Ranges
import io.astrolabe.workspace.VersionChange
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.astrolabe.provider.Role as ItemRole

/** P1.8.6: eviction in batches, stubs as recoverable pointers, the `R_max` bound and the `C(t)` accounting (§5.7). */
class ResidencyTest {

    private val estimator = HeuristicEstimator()
    private val residency = Residency(k = 8, rMaxTokens = 16_000, estimator = estimator)

    private fun body(alias: String, lines: Int): String =
        (1..lines).joinToString("\n") { "$it  val line$it = route(\"$alias\", $it)" }

    private fun message(text: String, turn: Int, role: ItemRole = ItemRole.Assistant): Resident {
        val item = Message.text(role, text)
        return Resident.message(item, turn, estimator.estimate(text).tokens)
    }

    private fun call(id: String, turn: Int): Resident {
        val item = ToolCall(id, "look", """{"what":"read","target":"src/$id.kt"}""")
        return Resident.call(item, turn, estimator.estimate(item.argsJson).tokens)
    }

    private fun result(
        callId: String,
        alias: String,
        turn: Int,
        lines: Int,
        resultClass: ResultClass = ResultClass.Observation,
        recoverable: Boolean = true,
        pinned: Boolean = false,
    ): Resident {
        val text = body(alias, lines)
        val pointer = if (recoverable) RecallPointer(alias, "obs-${alias.drop(1)}", Digest.ofUtf8(text)) else null
        return Resident.result(
            ToolResult.text(callId, text), turn, estimator.estimate(text).tokens, resultClass, pointer,
            label = "look.read src/$callId.kt v=a9f1", pinned = pinned,
        )
    }

    /** One turn as `[T]` receives it: the model message, its calls, then the results (assistant calls precede results). */
    private fun turn(t: Int, results: Int, lines: Int, resultClass: ResultClass = ResultClass.Observation): List<Resident> = buildList {
        add(message("turn $t: reading\nthen editing once the bytes are known", t))
        val ids = (1..results).map { "c$t-$it" }
        ids.forEach { add(call(it, t)) }
        ids.forEachIndexed { i, id -> add(result(id, "#${t * 10 + i}", t, lines, resultClass)) }
    }

    private fun request(items: List<Item>, anchor: String = "anchor"): Request = Request(
        segments = listOf(
            Segment(SegmentKind.S, listOf(Message.text(ItemRole.System, "kernel contract " + "rule ".repeat(40))), breakpoint = true),
            Segment(SegmentKind.T, items, breakpoint = true),
            Segment(SegmentKind.A, listOf(Message.text(ItemRole.User, anchor))),
        ),
        tools = emptyList(),
        profile = FakeProfiles.main,
        effort = Effort.Medium,
        maxOutputTokens = 1_000,
    )

    @Test
    fun `results live k turns then become stubs in place and every call-result unit stays complete`() {
        var residents = (1..8).flatMap { turn(it, results = 1, lines = 20) }
        assertEquals(EvictionTrigger.Age, residency.due(residents, 8))
        val first = residency.batch(residents, 8)
        assertFalse(first.rewritten, "at turn 8 nothing has lived k turns yet; a batch with nothing to do rewrites nothing")

        residents = first.residents + (9..16).flatMap { turn(it, results = 1, lines = 20) }
        val second = residency.batch(residents, 16)
        assertEquals(EvictionTrigger.Age, second.trigger)
        assertEquals(residents.size, second.residents.size, "eviction replaces in place; it never removes an item")
        assertEquals((1..8).map { "#${it * 10}" }, second.stubbed.map { it.alias }, "turns 1..8 have lived ≥ k turns")
        assertTrue(second.residents.filter { it.isLiveResult }.all { it.turn >= 9 })
        second.residents.zip(residents).forEach { (after, before) ->
            if (before.item is ToolResult) assertEquals(before.item.callId, (after.item as ToolResult).callId)
            else assertEquals(before.item, after.item, "calls and messages are untouched by the age rule")
        }
        assertFalse(Items.pairs(second.items).broken)

        // FX-21: the adapter accepts the stubbed history, and rejects what a naive eviction would have sent.
        val adapter = FakeAdapter(ScriptedModel.of())
        val accepted = request(second.items)
        assertEquals(Validation.Ok, adapter.validate(accepted, accepted.estimate(estimator)))
        val naive = request(second.items.filterNot { it is ToolResult && it.callId == "c1-1" })
        val rejected = assertIs<Validation.Rejected>(adapter.validate(naive, naive.estimate(estimator)))
        assertEquals(listOf(ProblemKind.BrokenToolPairing), rejected.problems.map { it.kind })
    }

    @Test
    fun `a stub is a recoverable pointer to the captured bytes, about twenty tokens, not a summary`() {
        val text = body("#10", 40)
        val observations = InMemoryObservations()
        val blobs = HashMap<Digest, ByteArray>()
        val digest = Digest.ofUtf8(text)
        blobs[digest] = text.toByteArray(Charsets.UTF_8)
        observations.record(
            Observation(
                id = "obs-10", ids = Identities(WorkId("W-1"), AttemptId("a1")), actionId = "act-1", candidate = null, contentRef = digest,
                paths = listOf("src/c1-1.kt"), ranges = mapOf("src/c1-1.kt" to Ranges.single(1, 40)), complete = true,
                sourceVersions = mapOf("src/c1-1.kt" to FileVersion.of(text.toByteArray())), captureComplete = true,
            ),
        )
        val residents = turn(1, results = 1, lines = 40) + (2..9).flatMap { turn(it, results = 1, lines = 5) }

        val eviction = residency.batch(residents, 9, EvictionTrigger.Age)

        val stub = eviction.stubbed.single()
        assertEquals("#10", stub.alias)
        assertTrue(stub.recoverable)
        val stubbed = eviction.residents.first { it.residence == Residence.Stubbed }
        val line = (stubbed.item as ToolResult).content.let { (it.single() as io.astrolabe.provider.Text).text }
        assertEquals(stub.render(), line)
        assertTrue(line.startsWith("⟦stub #10 look.read src/c1-1.kt v=a9f1 · "), line)
        assertTrue(line.endsWith(" tok · recall #10⟧"), line)
        assertFalse(line.contains("val line"), "a stub carries no body bytes")
        assertTrue(stubbed.tokens in 10..30, "≈20 tokens, measured: ${stubbed.tokens}")
        assertTrue(stub.tokens > 100, "the stub records how much it replaced: ${stub.tokens}")

        // recall: the pointer resolves through the observation store to the very bytes the model saw.
        val pointer = assertNotNull(residency.recall(eviction.residents, "#10"))
        val observation = assertNotNull(observations.get(pointer.observationId))
        assertEquals(pointer.contentRef, observation.contentRef)
        assertEquals(text, String(blobs.getValue(pointer.contentRef), Charsets.UTF_8))
        assertNull(residency.recall(eviction.residents, "#20"), "a live result is not a stub")
    }

    @Test
    fun `the bound stubs early by refetchability - stale and old observations first, then searches, verdicts last, the current turn exempt`() {
        val tight = Residency(k = 8, rMaxTokens = 400, estimator = estimator)
        val lines = 25 // ≈ 300 tokens each
        var residents = listOf(
            message("turn 1", 1), call("c1", 1), call("c2", 1), call("c3", 1),
            result("c1", "#1", 1, lines, ResultClass.Observation),
            result("c2", "#2", 1, lines, ResultClass.Verdict),
            result("c3", "#3", 1, lines, ResultClass.Search),
            message("turn 2", 2), call("c4", 2), call("c5", 2),
            result("c4", "#4", 2, lines, ResultClass.Observation),
            result("c5", "#5", 2, lines, ResultClass.Observation),
            message("turn 3", 3), call("c6", 3),
            result("c6", "#6", 3, lines, ResultClass.Observation),
        )
        residents = tight.markStale(residents, setOf("#4"))
        val tokens = residents.first { it.alias == "#1" }.tokens
        assertEquals(tokens / 3.0, tight.refetchability(residents.first { it.alias == "#1" }, 3), 1e-9, "p_reuse = 1/(1+age), c_refetch = size")
        assertEquals(0.0, tight.refetchability(residents.first { it.alias == "#4" }, 3), "a stale body will be re-read anyway")
        assertEquals(EvictionTrigger.Budget, tight.due(residents, 3))

        val eviction = tight.batch(residents, 3)

        assertEquals(listOf("#4", "#1", "#5", "#3", "#2"), eviction.stubbed.map { it.alias })
        assertEquals(listOf("#6"), eviction.residents.filter { it.isLiveResult }.map { it.alias }, "the turn's own results are never stubbed before the model reads them")
        assertTrue(eviction.liveResultTokensAfter <= 400 && !eviction.overBound)
        assertFalse(Items.pairs(eviction.items).broken)

        // With more room the order is the same and stops as soon as the bound holds.
        val roomier = Residency(k = 8, rMaxTokens = 1_000, estimator = estimator).batch(residents, 3)
        assertEquals(listOf("#4", "#1", "#5"), roomier.stubbed.map { it.alias })
        assertTrue(roomier.liveResultTokensAfter <= 1_000)
    }

    @Test
    fun `the residency bound holds at every request with multi-result turns`() {
        var residents = emptyList<Resident>()
        val triggers = ArrayList<EvictionTrigger>()
        for (t in 1..40) {
            residents = residents + turn(t, results = 3, lines = 40 + (t * 7) % 50)
            residency.due(residents, t)?.let { trigger ->
                val eviction = residency.batch(residents, t, trigger)
                triggers += trigger
                assertFalse(eviction.overBound, "turn $t: $eviction")
                residents = eviction.residents
            }
            assertTrue(residency.liveResultTokens(residents) <= 16_000, "turn $t: ${residency.liveResultTokens(residents)} live tokens")
            assertTrue(residents.filter { it.turn == t && it.isResult }.all { it.isLiveResult }, "turn $t: its own results are live")
            assertFalse(Items.pairs(residency.items(residents)).broken, "turn $t")
        }
        assertTrue(EvictionTrigger.Budget in triggers, "the workload must exceed R_max for this test to mean anything: $triggers")
        assertTrue(EvictionTrigger.Age in triggers)
    }

    @Test
    fun `batched eviction costs one cache miss per k turns in the fake adapter's accounting`() = runTest {
        val adapter = FakeAdapter(ScriptedModel.of())
        var residents = emptyList<Resident>()
        val evictions = HashMap<Int, Eviction>()
        var previous: Request? = null
        val misses = ArrayList<Int>()
        for (t in 1..40) {
            val request = request(residency.items(residents), anchor = "anchor for turn $t")
            adapter.start(request, InvocationId("inv-$t")).await()
            val call = adapter.calls.last()
            val prefixTokens = FakeTokenizer.count(request.segment(SegmentKind.S)!!.items)
            previous?.let { prev ->
                val previousT = FakeTokenizer.count(prev.segment(SegmentKind.T)!!.items)
                val tCached = call.cacheReadTokens - prefixTokens
                if (tCached < previousT) misses += t else assertEquals(previousT, tCached, "turn $t: an append reads the whole previous [T] from cache")
            }
            previous = request
            residents = residents + turn(t, results = 2, lines = 12)
            residency.due(residents, t)?.let { trigger ->
                val eviction = residency.batch(residents, t, trigger)
                evictions[t] = eviction
                residents = eviction.residents
            }
        }
        assertEquals(listOf(8, 16, 24, 32, 40), evictions.keys.sorted(), "batches run on the k cadence only; R_max never bound here")
        assertTrue(evictions.values.all { it.trigger == EvictionTrigger.Age })
        assertFalse(evictions.getValue(8).rewritten, "nothing is old enough at the first batch")
        assertEquals(listOf(17, 25, 33), misses, "one miss per k turns, on the request after each rewriting batch")
        assertTrue(evictions.getValue(32).trimmed > 0, "model messages older than 3k turns were trimmed inside the same batch")
        assertTrue(residency.liveResultTokens(residents) < 16_000)
    }

    @Test
    fun `model messages older than 3k turns keep their first line and their calls`() {
        val short = Residency(k = 2, rMaxTokens = 16_000, estimator = estimator)
        assertEquals(6, short.modelTrimTurns)
        val residents = turn(1, results = 1, lines = 3) + listOf(message("one line only", 1)) + (2..8).flatMap { turn(it, results = 0, lines = 0) }

        val early = short.batch(residents, 6, EvictionTrigger.Age)
        assertEquals(0, early.trimmed, "age 5 < 3k")

        val late = short.batch(residents, 7, EvictionTrigger.Age)
        assertEquals(1, late.trimmed, "only the two-line message of turn 1 has reached 3k; a one-line message has nothing to trim")
        val trimmed = late.residents.first()
        assertEquals(Residence.Trimmed, trimmed.residence)
        assertEquals("turn 1: reading", (trimmed.item as Message).text)
        assertTrue(trimmed.tokens < residents.first().tokens)
        assertEquals(residents[1].item, late.residents[1].item, "the call the message made is a separate item and stays verbatim")
        assertEquals("one line only", (late.residents[3].item as Message).text)
        assertEquals(Residence.Live, late.residents[3].residence)
    }

    @Test
    fun `user messages and the packet are pinned whatever their age or the bound, and the overflow is reported`() {
        val tight = Residency(k = 2, rMaxTokens = 100, estimator = estimator)
        val residents = listOf(
            message("fix the currency routing bug\nthe failing test is test_route_eur", 0, ItemRole.User),
            call("packet", 0),
            result("packet", "#1", 0, 40, ResultClass.Verdict, pinned = true),
            Resident.message(Message.text(ItemRole.Assistant, "done\npacket follows"), 0, 10, pinned = true),
        )
        assertTrue(residents[0].pinned, "a user message is pinned by default")

        val eviction = tight.batch(residents, 40, EvictionTrigger.Age)

        assertEquals(residents, eviction.residents, "nothing pinned is rewritten")
        assertFalse(eviction.rewritten)
        assertTrue(eviction.overBound, "pinned results over R_max are reported, not hidden")
        assertEquals(eviction.liveResultTokensBefore, eviction.liveResultTokensAfter)
        assertEquals(residents, tight.stubNow(residents, setOf("#1"), 40).residents, "an immediate stub respects the pin too")
    }

    @Test
    fun `a body with no captured copy is never stubbed early, and by age only with a recorded loss`() {
        val tight = Residency(k = 8, rMaxTokens = 400, estimator = estimator)
        val residents = listOf(
            message("turn 1", 1), call("c1", 1), call("c2", 1),
            result("c1", "#1", 1, 25, recoverable = false),
            result("c2", "#2", 1, 25),
            message("turn 2", 2),
        )

        val budget = tight.batch(residents, 2, EvictionTrigger.Budget)
        assertEquals(listOf("#2"), budget.stubbed.map { it.alias }, "only a refetchable result is stubbed early")
        assertTrue(budget.losses.isEmpty())
        assertTrue(budget.residents[3].isLiveResult)

        val age = tight.batch(budget.residents, 9, EvictionTrigger.Age)
        val loss = age.losses.single()
        assertEquals(9, loss.turn)
        assertEquals("look.read src/c1.kt v=a9f1", loss.label)
        assertTrue(loss.tokens > 100)
        val stub = age.stubbed.single()
        assertFalse(stub.recoverable)
        assertNull(stub.alias)
        assertTrue(stub.render().endsWith("· not recoverable: no captured copy⟧"), stub.render())
        assertFalse(Items.pairs(age.items).broken)
    }

    @Test
    fun `an oversized stale body is stubbed at once and the cadence is untouched`() {
        val version = FileVersion(Digest("a9f10b2c".repeat(8)))
        val next = FileVersion(Digest("0b2ca9f1".repeat(8)))
        val workset = Workset(immediateStubTokens = 800)
        var residents = listOf(
            message("turn 1", 1), call("c1", 1), call("c2", 1),
            result("c1", "#1", 1, 5),
            result("c2", "#2", 1, 120),
            message("turn 2", 2),
        )
        residents.filter { it.isResult }.forEach {
            workset.register(Entry("src/router.kt", Ranges.single(1, 10), version, EntrySource.Look, 1, it.alias, it.tokens))
        }
        assertTrue(residents.first { it.alias == "#2" }.tokens > 800)

        workset.onChange(VersionChange("src/router.kt", version, next, "edit"))
        val drops = workset.takeAnnouncements()
        assertEquals(mapOf("#1" to false, "#2" to true), drops.associate { it.recallId!! to it.stubNow })
        residents = residency.markStale(residents, drops.map { it.recallId!! }.toSet())
        val eviction = residency.stubNow(residents, drops.filter { it.stubNow }.map { it.recallId!! }.toSet(), 3)
        eviction.stubbed.forEach { workset.stub(it.alias!!) }

        assertEquals(EvictionTrigger.Immediate, eviction.trigger)
        assertEquals(listOf("#2"), eviction.stubbed.map { it.alias })
        val small = eviction.residents.first { it.alias == "#1" }
        assertTrue(small.isLiveResult && small.stale, "the small stale body waits for the batch, with p_reuse = 0")
        assertEquals(0.0, residency.refetchability(small, 3))
        assertNull(residency.due(eviction.residents, 3), "an immediate stub does not move the k cadence")
        assertTrue(workset.entries.isEmpty(), "no live coverage remains: the change dropped both entries, the stub removed #2's stale one")
    }

    @Test
    fun `occupancy is the C(t) of section 5-7 by region`() {
        val stubbedItem = ToolResult.text("c0", "⟦stub #5 · 1.2K tok · recall #5⟧")
        val residents = listOf(
            Resident.message(Message.text(ItemRole.User, "pinned request"), 0, 50),
            Resident.message(Message.text(ItemRole.Assistant, "turn 1"), 1, 30),
            Resident.call(ToolCall("c0", "look", "{}"), 1, 20),
            Resident(stubbedItem, 1, 20, resultClass = ResultClass.Observation, residence = Residence.Stubbed),
            Resident.call(ToolCall("c1", "look", "{}"), 1, 20),
            Resident.result(ToolResult.text("c1", "bytes"), 1, 500, ResultClass.Observation),
            Resident.message(Message.text(ItemRole.Assistant, "turn 2"), 2, 30),
            Resident.call(ToolCall("c2", "run", "{}"), 2, 20),
            Resident.result(ToolResult.text("c2", "exit 0"), 2, 700, ResultClass.Verdict),
        )

        val c = residency.occupancy(PrefixTokens(1_000, 2_000, 3_000), residents, turn = 2, anchorTokens = 1_500, pinnedTokens = 100)

        assertEquals(1_200, c.liveResultTokens)
        assertEquals(20, c.stubTokens)
        assertEquals(1, c.stubCount)
        assertEquals(100 + 50 + 30 + 20 + 20 + 30 + 20, c.messageTokens)
        assertEquals(1_200 + 20 + 270, c.tLiveTokens)
        assertEquals(6_000 + 1_490 + 1_500, c.totalTokens)
        assertEquals(30 + 20 + 700, c.newTokens, "what entered [T] this turn is the uncached part besides the anchor")
        assertEquals(1_500 + 750, c.uncachedTokens)
        assertEquals(c.totalTokens - c.uncachedTokens, c.cachedTokens)
        assertEquals(4, c.percentOf(200_000))
        assertEquals(100, c.percentOf(5_000), "an overflow reads 100, never more")
    }

    @Test
    fun `result classes follow the tool family and op`() {
        assertEquals(ResultClass.Observation, ResultClass.of("look.read"))
        assertEquals(ResultClass.Observation, ResultClass.of("look.recall"))
        assertEquals(ResultClass.Observation, ResultClass.of("look.outline"))
        assertEquals(ResultClass.Search, ResultClass.of("look.find"))
        assertEquals(ResultClass.Search, ResultClass.of("look.impact"))
        assertEquals(ResultClass.Search, ResultClass.of("kb.get"))
        assertEquals(ResultClass.Verdict, ResultClass.of("edit.anchored"))
        assertEquals(ResultClass.Verdict, ResultClass.of("run.exec"))
        assertEquals(ResultClass.Verdict, ResultClass.of("verify"))
        assertEquals(ResultClass.Verdict, ResultClass.of("state.patch"))
    }
}
