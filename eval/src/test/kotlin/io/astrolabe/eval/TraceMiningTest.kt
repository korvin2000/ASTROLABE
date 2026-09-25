package io.astrolabe.eval

import io.astrolabe.event.Phase
import io.astrolabe.event.SpanId
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Money
import io.astrolabe.recover.ErrorSignature
import io.astrolabe.recover.FailureClass
import io.astrolabe.telemetry.TraceSnapshot
import io.astrolabe.telemetry.TraceSpan
import io.astrolabe.telemetry.TraceSpanStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceMiningTest {
    private val harness = Digest.ofUtf8("harness-v1")
    private val ids = Identities(WorkId("work-1"), AttemptId("attempt-1"))
    private val stale = ErrorSignature("edit", "anchor hash mismatch in src/a.kt")
    private val build = ErrorSignature("run", "gradle: dependency x unresolved")

    private fun span(id: String, phase: Phase, cost: Money) =
        TraceSpan(SpanId(id), null, phase, ids, "mono", 0, 10, cost, TraceSpanStatus.Completed)

    private fun trace(task: String, spans: List<TraceSpan>, failures: List<TraceFailure>, version: Digest = harness) =
        MinedTrace(version, TrialKey("repo", task, 0), TraceSnapshot("v1", "fixture", WorkId("work-1"), "USD",
            spans, emptyList(), emptySet(), true, true), failures)

    private val policy = MiningPolicy(2, decimal("0.5"))

    @Test fun `repeated failures count distinct tasks and the MAST distribution keeps untagged apart`() {
        val traces = listOf(
            trace("t1", listOf(span("a", Phase.Edit, money("1"))), listOf(
                TraceFailure(FailureClass.StaleAnchor, stale, MastCategory.TaskVerification, "edit"),
                TraceFailure(FailureClass.StaleAnchor, stale, null, "edit"))),
            trace("t2", listOf(span("b", Phase.Edit, money("1"))), listOf(
                TraceFailure(FailureClass.StaleAnchor, stale, MastCategory.TaskVerification, "edit"),
                TraceFailure(FailureClass.BuildEnvironment, build, MastCategory.SpecificationIssues, null))),
            trace("t3", listOf(span("c", Phase.Verify, money("1"))), listOf(
                TraceFailure(FailureClass.BuildEnvironment, build, null, null)), Digest.ofUtf8("harness-v2")),
        )
        val report = TraceMining.mine(harness, "USD", policy, traces.reversed())
        assertEquals(2, report.traces)
        assertEquals(listOf(TrialKey("repo", "t3", 0)), report.excluded)
        assertEquals(2, report.mast[MastCategory.TaskVerification])
        assertEquals(1, report.mast[MastCategory.SpecificationIssues])
        assertEquals(0, report.mast[MastCategory.InterAgentMisalignment])
        assertEquals(1, report.untagged)
        assertEquals(mapOf(FailureClass.StaleAnchor to 3, FailureClass.BuildEnvironment to 1), report.classes)
        val repeated = report.repeated.single()
        assertEquals(FailureClass.StaleAnchor, repeated.failureClass)
        assertEquals(2, repeated.tasks)
        assertEquals(3, repeated.occurrences)
        assertEquals(setOf("edit"), repeated.modules)
        assertEquals(report.repeated.map { it.signature }, TraceMining.mine(harness, "USD", policy, traces).repeated.map { it.signature })
    }

    @Test fun `cost concentration uses exclusive cost once and never reads unknown as zero`() {
        val known = TraceMining.mine(harness, "USD", policy, listOf(
            trace("t1", listOf(span("a", Phase.Verify, money("3")), span("a", Phase.Verify, money("3")),
                span("b", Phase.Edit, money("1"))), emptyList()),
        ))
        val verify = known.costs.first()
        assertEquals(Phase.Verify, verify.phase)
        assertEquals(0, verify.share!!.compareTo(decimal("0.75")))
        assertTrue(verify.concentrated)
        assertEquals(0, known.totalCost.amount.compareTo(decimal("4")))

        val unknown = TraceMining.mine(harness, "USD", policy, listOf(
            trace("t1", listOf(span("a", Phase.Verify, money("3")), span("b", Phase.Edit, Money.unknown("USD"))), emptyList()),
        ))
        assertTrue(unknown.totalCost.unknown)
        assertTrue(unknown.costs.all { it.share == null && !it.concentrated })
        assertNull(unknown.costs.single { it.phase == Phase.Edit }.share)

        assertFailsWith<IllegalArgumentException> {
            TraceMining.mine(harness, "USD", policy, listOf(trace("t1",
                listOf(span("a", Phase.Verify, money("3")), span("a", Phase.Verify, money("2"))), emptyList())))
        }
    }
}
