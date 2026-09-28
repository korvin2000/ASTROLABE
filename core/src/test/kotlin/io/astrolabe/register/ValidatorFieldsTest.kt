package io.astrolabe.register

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.evidence.Anchor
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValidatorFieldsTest {
    private val validator = Validator(HeuristicEstimator(), registerCapTokens = 100_000, patchCapTokens = 100_000)
    private val context = object : ValidationContext {
        override fun evidenceExists(id: String): Boolean = true
        override fun acceptGreen(accept: String): Boolean = true
        override val redChecks: Set<String> = emptySet()
        override val greenOps: Set<Int> = emptySet()
        override val appliedOps: Set<Int> = emptySet()
    }
    private val base = Register.empty(ContextId("field-validation"), "I1", "fix dispatch").copy(
        plan = listOf(Step(1, Mark.Cursor, "investigate"), Step(2, Mark.Todo, "verify")),
        facts = listOf(Fact(1, ClaimKind.Verified, "existing fact", evidenceId = "#1")),
        open = listOf(OpenItem(1, "existing question")),
        next = "continue",
    )
    private val version = FileVersion(Digest.ofUtf8("anchor bytes"))
    private val fields: List<Pair<String, (String) -> Op>> = listOf(
        "plan text" to { Op.PlanAdd(it) },
        "plan accept" to { Op.PlanAdd("step", accept = it) },
        "plan req" to { Op.PlanAdd("step", req = it) },
        "tick evidence" to { Op.PlanTick(2, it) },
        "cancel reason" to { Op.PlanCancel(2, it) },
        "fact text" to { Op.FactAdd(ClaimKind.Verified, it, "#1") },
        "fact evidence" to { Op.FactAdd(ClaimKind.Verified, "fact", it) },
        "fact anchor path" to { Op.FactAdd(ClaimKind.Verified, "fact", "#1", Anchor(it, version)) },
        "refute evidence" to { Op.FactRefute(1, it) },
        "deadend text" to { Op.DeadendAdd(it, "#1", "scope", "reopen") },
        "deadend evidence" to { Op.DeadendAdd("dead end", it, "scope", "reopen") },
        "deadend scope" to { Op.DeadendAdd("dead end", "#1", it, "reopen") },
        "deadend reopen" to { Op.DeadendAdd("dead end", "#1", "scope", it) },
        "decision text" to { Op.DecisionAdd(it, "because", "rejected") },
        "decision because" to { Op.DecisionAdd("decision", it, "rejected") },
        "decision rejected" to { Op.DecisionAdd("decision", "because", it) },
        "decision probe" to { Op.DecisionAdd("decision", "because", "rejected", it) },
        "open text" to { Op.OpenAdd(it) },
        "open trip" to { Op.OpenAdd("question", trip = it) },
        "open needs" to { Op.OpenAdd("question", needs = it) },
        "close evidence" to { Op.OpenClose(1, it) },
        "focus dir" to { Op.FocusSet(it) },
        "amendment change" to { Op.AmendPropose(it, "reason") },
        "amendment reason" to { Op.AmendPropose("change", it) },
        "next text" to { Op.Next(it) },
    )

    @TestFactory
    fun `all rendered string fields enforce fence and line rules`(): List<DynamicTest> {
        val payloads = listOf(
            "backtick fence" to "before ```code``` after",
            "tilde fence" to "before ~~~code~~~ after",
            "line feed" to "before\nafter",
            "carriage return" to "before\rafter",
            "oversized line" to "x".repeat(241),
        )
        return fields.flatMap { (field, op) -> payloads.map { (kind, payload) ->
            DynamicTest.dynamicTest("$field rejects $kind") {
                val rejected = assertIs<Validation.Rejected>(check(op(payload)))
                if (kind.endsWith("fence")) assertEquals("no fenced code", rejected.rule)
                else assertTrue(rejected.rule.contains("line"), "must reject the line rule, got ${rejected.rule}")
            }
        } }
    }

    @TestFactory
    fun `ordinary inline commands remain valid in every field`(): List<DynamicTest> = fields.map { (field, op) ->
        DynamicTest.dynamicTest("$field accepts inline command") {
            assertIs<Validation.Applied>(check(op("run `pytest -k dispatch`")))
        }
    }

    private fun check(op: Op): Validation = validator.check(
        base,
        if (op is Op.Next) Patch.of(op) else Patch.of(op, Op.Next("continue")),
        context,
    )
}
