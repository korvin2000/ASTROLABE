package io.astrolabe.campaign

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.ShapePolicy
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Risk
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P2.2.1: `select_shape` against every row of the §3.5 shape table, with the D-16/D-39 inputs logged. */
class ShapeSelectorTest {
    private val policy = ShapePolicy()

    private fun contract(
        requirements: Int = 1,
        review: Boolean = false,
        risk: Risk? = Risk(1, Reversibility.Easy, false),
        packages: List<String?> = listOf(null),
    ) = Contract(
        WorkId("W-shape"), 1, AttemptId("a1"), Mode.Autonomous, Shape.S0,
        listOf(UserRequest("U1", Instant.EPOCH, "change the parser")),
        (1..requirements).map { Requirement("R$it", "requirement $it", listOf("AC1"), authorityRef = "U1") },
        packages.mapIndexed { i, cwd -> Acceptance.Run(if (i == 0) "AC1" else "AC-pkg$i", Command(listOf("pytest"), cwd), Origin.User) } +
            (if (review) listOf(Acceptance.Review("AC-rev", "a maintainer approves the API", Origin.User)) else emptyList()),
        emptyList(), emptyList(), emptyList(), Scope(listOf("src/"), emptyList()),
        Budget.of(Defaults(), Tokens(10_000)), Authorization(Stage.Patch, DClassPolicy.Ask, "local"), risk = risk,
    )

    private data class Row(
        val name: String,
        val contract: Contract,
        val prescan: Prescan,
        val expected: String,
        val resumeExpected: Boolean = false,
        val ambiguousBug: Boolean = false,
        val capabilities: ShapeCapabilities = ShapeCapabilities(reviewCells = true, probes = true),
    )

    private fun outcome(d: ShapeDecision) = when (d) {
        is ShapeDecision.Selected -> d.shape.name
        is ShapeDecision.Unavailable -> "blocked"
    }

    @Test
    fun `every row of the shape table selects its shape`() {
        val known = Prescan(filesEstimated = 2, crossPackage = false, contractTouch = false)
        val rows = listOf(
            Row("S0: small, low risk, no review, no resume", contract(), known, "S0"),
            Row("S1: multi-file work", contract(), known.copy(filesEstimated = 6), "S1"),
            Row("S1: several requirements", contract(requirements = 2), known, "S1"),
            Row("S1: large (cross-package)", contract(packages = listOf(null, "web")), known, "S1"),
            Row("S1: multi-session (resume expected)", contract(), known, "S1", resumeExpected = true),
            Row("S1: medium blast radius", contract(risk = Risk(5, Reversibility.Easy, false)), known, "S1"),
            Row("S2: review acceptance", contract(review = true), known, "S2"),
            Row("S2: contract touch in the impact", contract(), known.copy(contractTouch = true), "S2"),
            Row("S2: declared contract touch", contract(risk = Risk(1, Reversibility.Easy, true)), known, "S2"),
            Row("S2: hard to reverse", contract(risk = Risk(1, Reversibility.Hard, false)), known, "S2"),
            Row("S2: high blast radius", contract(risk = Risk(20, Reversibility.Easy, false)), known, "S2"),
            Row("S2: ambiguous bug (D-39)", contract(requirements = 2), known, "S2", ambiguousBug = true),
            Row("S3 is never an initial choice", contract(requirements = 5, packages = listOf(null, "a", "b")), known, "S1"),
        )
        for (row in rows) {
            val decision = ShapeSelector.select(row.contract, row.prescan, policy.copy(s3Enabled = true), row.resumeExpected, row.ambiguousBug, row.capabilities)
            assertEquals(row.expected, outcome(decision), row.name)
        }
    }

    @Test
    fun `unknown impact is logged and never establishes S0 by itself`() {
        val unknownSmall = assertIs<ShapeDecision.Selected>(ShapeSelector.select(contract(risk = null), Prescan.UNKNOWN, policy))
        assertEquals(Shape.S0, unknownSmall.shape, "D-65: positive small facts with an unassessed pre-scan stay S0")
        assertEquals(listOf("risk unknown (pre-scan P3.2.6)", "files unestimated (pre-scan P3.2.6)"), unknownSmall.limitations)
        val inputs = unknownSmall.inputs!!
        assertEquals(RiskLevel.Unknown, inputs.risk)
        assertEquals(null, inputs.size)
        assertEquals(
            "requirements=1 files=unknown packages=1 crossPackage=false size=unknown risk=unknown contractTouch=unknown review=0 resumeExpected=false ambiguousBug=false",
            inputs.log,
        )

        val unknownLarger = assertIs<ShapeDecision.Selected>(ShapeSelector.select(contract(requirements = 2, risk = null), Prescan.UNKNOWN, policy))
        assertEquals(Shape.S1, unknownLarger.shape, "missing coverage does not make two requirements small")
    }

    @Test
    fun `required review is met by the authority as a recorded substitution, other missing capabilities block`() {
        val known = Prescan(filesEstimated = 2, crossPackage = false, contractTouch = false)
        val substituted = assertIs<ShapeDecision.Selected>(ShapeSelector.select(contract(review = true), known, policy, capabilities = ShapeCapabilities(humanReview = true)))
        assertEquals(Shape.S2, substituted.shape)
        assertEquals(listOf("required review by Authority.review, sequential (D-23)"), substituted.substitutions)

        val noReviewer = assertIs<ShapeDecision.Unavailable>(ShapeSelector.select(contract(review = true), known, policy))
        assertEquals("capability unavailable: required review", noReviewer.reason)
        val noProbes = assertIs<ShapeDecision.Unavailable>(
            ShapeSelector.select(contract(review = true), known, policy, ambiguousBug = true, capabilities = ShapeCapabilities(humanReview = true)),
        )
        assertEquals("capability unavailable: probes (ambiguous bug, D-39)", noProbes.reason, "human review never implies probes")
        assertTrue(noProbes.inputs!!.ambiguousBug)
    }

    @Test
    fun `upgrades need traced evidence and a downgrade keeps outstanding reviews`() {
        assertEquals(Shape.S1, ShapeSelector.adjust(Shape.S1, Shape.S2, evidence = emptyList(), outstandingReviews = 0))
        assertEquals(Shape.S2, ShapeSelector.adjust(Shape.S1, Shape.S2, evidence = listOf("pressure in cell-3"), outstandingReviews = 0))
        assertEquals(Shape.S1, ShapeSelector.adjust(Shape.S2, Shape.S1, evidence = emptyList(), outstandingReviews = 0), "downgrade eagerly")
        assertEquals(Shape.S2, ShapeSelector.adjust(Shape.S2, Shape.S0, evidence = emptyList(), outstandingReviews = 1))
        assertEquals(Shape.S2, ShapeSelector.adjust(Shape.S3, Shape.S1, evidence = emptyList(), outstandingReviews = 2))
        assertEquals(Shape.S1, ShapeSelector.adjust(Shape.S1, Shape.S0, evidence = emptyList(), outstandingReviews = 1))
    }

    @Test
    fun `a high lexical fan-in raises an unassessed risk to medium, never to high or low (P3 2 6)`() {
        fun risk(fanIn: Long, declared: Risk? = null) =
            (ShapeSelector.select(contract(risk = declared), Prescan(filesEstimated = 1, fanIn = fanIn), policy) as ShapeDecision.Selected).inputs!!.risk
        assertEquals(RiskLevel.Medium, risk(policy.largeMinFiles.toLong()))
        assertEquals(RiskLevel.Unknown, risk(policy.largeMinFiles - 1L))
        assertEquals(RiskLevel.Low, risk(1_000, Risk(1, Reversibility.Easy, false)), "a declared risk is never overridden by a lexical count")
        assertEquals(Shape.S1, (ShapeSelector.select(contract(risk = null), Prescan(filesEstimated = 1, fanIn = 100), policy) as ShapeDecision.Selected).shape)
    }
}
