package io.astrolabe.verify

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Constraint
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P3.5.1: refactor mode activates on the explicit flag or behaviour-preserving requirement keywords, never on unrelated text (§8.9). */
class RefactorModeTest {
    private fun contract(vararg requirements: String, request: String = requirements.firstOrNull() ?: "do the thing", constraints: List<Constraint> = emptyList()) = Contract(
        WorkId("W-1"), 1, AttemptId("a1"), Mode.Autonomous, Shape.S1,
        listOf(UserRequest("U1", Instant.EPOCH, request)),
        requirements.mapIndexed { i, text -> Requirement("R${i + 1}", text, emptyList(), authorityRef = "U1") },
        emptyList(), constraints, emptyList(), emptyList(), Scope(listOf("src/"), emptyList()),
        Budget.of(Defaults(), Tokens(10_000)), Authorization(Stage.Patch, DClassPolicy.Ask, "local"),
    )

    @Test
    fun `behaviour-preserving requirement keywords activate the mode and name the requirement`() {
        for (text in listOf(
            "Refactor the parser into smaller functions",
            "extract the discount rules into their own module",
            "Rename UserRepo to UserStore across the code base",
            "migrate the API of the cart service to v2 without changing behaviour",
            "API migration: callers move to the new signature",
        )) {
            val detection = RefactorMode.detect(contract(text))
            assertTrue(detection.active, text)
            assertTrue(detection.reasons.single().startsWith("R1: behaviour-preserving requirement"), detection.reasons.toString())
        }
        val second = RefactorMode.detect(contract("make a return 10", "then rename b to c"))
        assertEquals(listOf("R2: behaviour-preserving requirement ('rename')"), second.reasons)
    }

    @Test
    fun `unrelated text never activates it, and the requests count only while no requirement exists`() {
        for (text in listOf("make a return 10", "fix the currency routing bug", "add a migration for the users table", "the extractor role reads only")) {
            assertFalse(RefactorMode.detect(contract(text)).active, text)
        }
        val requestOnly = Contract(
            WorkId("W-1"), 1, AttemptId("a1"), Mode.Autonomous, Shape.S1, listOf(UserRequest("U1", Instant.EPOCH, "refactor the parser")),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Scope(listOf("src/"), emptyList()),
            Budget.of(Defaults(), Tokens(10_000)), Authorization(Stage.Patch, DClassPolicy.Ask, "local"),
        )
        assertTrue(RefactorMode.detect(requestOnly).active, "no requirement yet: the request is the text")
        assertFalse(RefactorMode.detect(contract("fix the bug", request = "refactor everything")).active, "a requirement outranks the request text")
    }

    @Test
    fun `the explicit contract flag activates the mode and an off flag silences the keywords`() {
        val byId = RefactorMode.detect(contract("make a return 10", constraints = listOf(Constraint(RefactorMode.FLAG, "keep behaviour", "U1"))))
        assertEquals(RefactorDetection(true, listOf("constraint refactor_mode: refactor_mode")), byId)
        val byText = RefactorMode.detect(contract("make a return 10", constraints = listOf(Constraint("C1", "refactor_mode: true", "U1"))))
        assertTrue(byText.active && byText.reasons == listOf("constraint C1: refactor_mode"), byText.toString())
        val off = RefactorMode.detect(contract("refactor the parser", constraints = listOf(Constraint("C1", "refactor_mode: false", "U1"))))
        assertEquals(RefactorDetection(false, listOf("constraint refactor_mode: off")), off)
        assertFalse(RefactorMode.isActive(contract("make a return 10", constraints = listOf(Constraint("C1", "no refactor_mode here", "U1")))))
    }

    @Test
    fun `the checklist names its blank entries with the vocabulary of §8-9`() {
        val full = RefactorChecklist("parser output", "Parser.parse signature", "one release", "cli, api", "none", "golden CLI outputs", "CON parser contract")
        assertEquals(emptyList(), full.gaps())
        val blank = full.copy(compatibilityDuration = " ", sharedDecision = "")
        assertEquals(listOf("refactor checklist: compatibility duration is blank", "refactor checklist: shared decision is blank"), blank.gaps())
    }
}
