package io.astrolabe.tool

import io.astrolabe.provider.ToolCall as ProviderCall
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P1.6.2 turn partition (§5.4): phases by effect, emitted op ids, backward-only conditions, one transform alone. */
class PartitionTest {
    /** Raw call templates by `family.op`; `%IF%` is replaced by a condition clause or nothing. */
    private val templates = mapOf(
        "look.read" to ("look" to """{"what":"read","target":"src/a.py:1-20"}"""),
        "kb.search" to ("kb" to """{"op":"search","query":"ctx","why":"handlers"}"""),
        "kb.propose" to ("kb" to """{"op":"propose","note":{"kind":"STATUS"}}"""),
        "edit.anchored" to ("edit" to """{"ops":[{"path":"src/a.py","expect":"c02e","hunks":[{"anchor":"x","new":"y"}]%IF%}],"why":"w"}"""),
        "edit.transform" to ("edit" to """{"ops":[{"transform":{"argv":["sed","-i","s/a/b/"],"scope_glob":"src/**","why":"rename"}}],"why":"w"}"""),
        "run.run" to ("run" to """{"argv":["pytest","-q"]%IF%}"""),
        "run.poll" to ("run" to """{"op":"poll","handle":"h1"}"""),
        "verify.tests" to ("verify" to """{"what":"tests","selection":"blast"}"""),
        "state.patch" to ("state" to """{"op":"patch","patch":[{"next":"x"}]}"""),
        "task.ask" to ("task" to """{"op":"ask","question":"which?"}"""),
    )

    private fun calls(vararg specs: String): List<ToolCall> {
        val provider = specs.mapIndexed { i, spec ->
            val (name, condition) = spec.split("@").let { it[0] to it.getOrNull(1) }
            val (family, json) = templates.getValue(name)
            ProviderCall("c${i + 1}", family, json.replace("%IF%", condition?.let { ""","if":"$it"""" } ?: ""))
        }
        return (ToolCalls.parse(provider) as ParsedCalls.Valid).calls
    }

    private val expectedPhase = mapOf(
        "look.read" to TurnPhase.Read, "kb.search" to TurnPhase.Read, "kb.propose" to TurnPhase.Metadata,
        "edit.anchored" to TurnPhase.Edit, "edit.transform" to TurnPhase.Edit, "run.run" to TurnPhase.Execute,
        "run.poll" to TurnPhase.Execute, "verify.tests" to TurnPhase.Execute, "state.patch" to TurnPhase.Metadata, "task.ask" to TurnPhase.Metadata,
    )

    @Test
    fun `phases follow effects, not family names`() {
        for ((name, phase) in expectedPhase) {
            assertEquals(phase, Partition.phaseOf(calls(name).single()), name)
        }
    }

    @Test
    fun `the fused turn of §5_5 executes edit, run, state in that order whatever the emitted order`() {
        val fused = assertIs<Partition.Ordered>(Partition.of(calls("edit.anchored", "run.run@applied(op:1)", "state.patch")))
        assertEquals(listOf(1, 2, 3), fused.order.map { it.opId })
        assertTrue(fused.mutating)

        val shuffled = assertIs<Partition.Ordered>(Partition.of(calls("state.patch", "run.run@applied(op:4)", "look.read", "edit.anchored")))
        assertEquals(listOf(3, 4, 2, 1), shuffled.order.map { it.opId }, "op ids are the emitted order; execution order is by phase")
        assertEquals(TurnPhase.Edit, shuffled.phaseOf(4))
        assertEquals(null, shuffled.phaseOf(9))
    }

    @Test
    fun `a condition must point backward in execution order and name the right kind of op`() {
        val forward = assertIs<Partition.Rejected>(Partition.of(calls("edit.anchored@green(op:2)", "run.run")))
        assertEquals(1, forward.opId)
        assertTrue(forward.reason.contains("points forward"), forward.reason)

        assertIs<Partition.Rejected>(Partition.of(calls("run.run@green(op:2)", "run.run")), "a later run is forward even inside one phase")
        assertIs<Partition.Ordered>(Partition.of(calls("run.run", "run.run@green(op:1)")))
        assertIs<Partition.Ordered>(Partition.of(calls("run.run@applied(op:2)", "edit.anchored")), "run after edit is valid whatever the emitted order")

        val kind = assertIs<Partition.Rejected>(Partition.of(calls("edit.anchored", "run.run@green(op:1)")))
        assertTrue(kind.reason.contains("must name a run or verify op"), kind.reason)
        assertIs<Partition.Rejected>(Partition.of(calls("look.read", "run.run@applied(op:1)")))
        assertIs<Partition.Rejected>(Partition.of(calls("run.run@applied(op:7)")))
        assertTrue(assertIs<Partition.Rejected>(Partition.of(calls("run.run@ok(op:1)"))).reason.contains("malformed"))
    }

    @Test
    fun `a transform runs alone in its turn`() {
        assertIs<Partition.Ordered>(Partition.of(calls("look.read", "edit.transform", "run.run")))
        val two = assertIs<Partition.Rejected>(Partition.of(calls("edit.anchored", "edit.transform")))
        assertEquals(2, two.opId)
        assertIs<Partition.Ordered>(Partition.of(calls("edit.anchored", "edit.anchored")), "several anchored edit calls form one batch")
    }

    @Test
    fun `property test, every ordered turn is a phase-sorted permutation and rejection matches the reference rule`() {
        val names = expectedPhase.keys.filter { it != "edit.transform" }.toList()
        val random = Random(20260920)
        repeat(400) {
            val n = 1 + random.nextInt(8)
            val picked = List(n) { names[random.nextInt(names.size)] }
            val specs = picked.mapIndexed { i, name ->
                val conditional = name == "run.run" || name == "edit.anchored"
                if (conditional && random.nextInt(3) == 0) {
                    val target = 1 + random.nextInt(n)
                    val kind = if (random.nextBoolean()) "green" else "applied"
                    "$name@$kind(op:$target)"
                } else {
                    name
                }
            }
            val parsed = calls(*specs.toTypedArray())
            val expectRejected = specs.withIndex().any { (i, spec) ->
                val condition = spec.substringAfter("@", "")
                if (condition.isEmpty()) return@any false
                val kind = condition.substringBefore("(")
                val target = condition.substringAfter("op:").substringBefore(")").toInt()
                val targetPhase = expectedPhase.getValue(picked[target - 1])
                val kindOk = if (kind == "applied") targetPhase == TurnPhase.Edit else targetPhase == TurnPhase.Execute
                val myPhase = expectedPhase.getValue(picked[i])
                val backward = targetPhase.ordinal < myPhase.ordinal || (targetPhase == myPhase && target < i + 1)
                !(kindOk && backward)
            }
            when (val partition = Partition.of(parsed)) {
                is Partition.Rejected -> assertTrue(expectRejected, "unexpected rejection for $specs: ${partition.reason}")
                is Partition.Ordered -> {
                    assertTrue(!expectRejected, "expected a rejection for $specs")
                    val order = partition.order
                    assertEquals(parsed.map { it.opId }.sorted(), order.map { it.opId }.sorted(), "a permutation of the emitted calls")
                    val phases = order.map { Partition.phaseOf(it) }
                    assertEquals(phases.sortedBy { it.ordinal }, phases, "phases are non-decreasing along the execution order: $specs")
                    phases.distinct().forEach { phase ->
                        val inPhase = order.filter { Partition.phaseOf(it) == phase }.map { it.opId }
                        assertEquals(inPhase.sorted(), inPhase, "emitted order inside phase $phase: $specs")
                    }
                    order.forEach { assertEquals(Partition.phaseOf(it), partition.phaseOf(it.opId)) }
                    assertEquals(partition.mutating, parsed.any { it.family == ToolFamily.Edit })
                }
            }
        }
    }
}
