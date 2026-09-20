package io.astrolabe.graph

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Ledger
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.WorkId
import io.astrolabe.register.Register
import io.astrolabe.verify.Applicability
import io.astrolabe.verify.Assessment
import io.astrolabe.verify.CompletionProposal
import io.astrolabe.verify.CompletionResult
import io.astrolabe.verify.Currency
import io.astrolabe.verify.Verifier
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequirementGraphTest {
    private val stamp = CandidateId(Digest.ofUtf8("candidate"))
    private val contract = Contract(
        WorkId("W-graph"), 1, AttemptId("a1"), Mode.Autonomous, Shape.S1,
        listOf(UserRequest("U1", Instant.EPOCH, "Implement the two paths")),
        listOf(Requirement("R1", "both paths work", listOf("AC1", "AC2"), authorityRef = "U1")),
        listOf("AC1", "AC2").map { Acceptance.Run(it, Command(listOf("check", it)), Origin.User) },
        emptyList(), emptyList(), emptyList(), Scope(listOf("src/"), emptyList()),
        Budget.of(Defaults(), Tokens(10_000)), Authorization(Stage.Patch, DClassPolicy.Ask, "local"),
    )

    private fun node(id: String, vararg dependencies: String): Increment = Increment(
        id, listOf("R1"), listOf("AC1", "AC2"), listOf("src/"), 1,
        title = "Implement $id", dependsOn = dependencies.toList(), produces = Production.Artifact,
    )

    private fun completion(
        graph: RequirementGraph, id: String, at: CandidateId = stamp, currentContract: Contract = contract,
    ): CompletionResult.Accepted {
        val increment = graph.increments.single { it.id == id }
        val result = Verifier().accept(
            CompletionProposal(id, "done", currentContract.version, at, at, null, Digest.ofUtf8("env")),
            currentContract, increment, Register.empty(increment.cells.last(), id, "implement"),
            Ledger.initial(currentContract), at,
            increment.accept.associateWith { Currency("rcpt-$id-$it", Applicability.Current, true, true, emptyList()) },
        )
        return assertIs<CompletionResult.Accepted>(result)
    }

    private fun accept(graph: RequirementGraph, id: String, at: CandidateId = stamp): RequirementGraph =
        graph.recordAccepted(contract, completion(graph, id, at))

    @Test
    fun `validation covers requirements and every acceptance obligation without trusting model status`() {
        assertTrue(RequirementGraph(listOf(node("I1"))).validate(contract).isEmpty())
        val incomplete = RequirementGraph(listOf(node("I1").copy(accept = listOf("AC1"))))
        assertTrue(incomplete.validate(contract).any { it.code == GraphIssueCode.UncoveredAcceptance })
        assertTrue(RequirementGraph(emptyList()).validate(contract).any { it.code == GraphIssueCode.UncoveredRequirement })
        val forged = RequirementGraph(listOf(node("I1").copy(status = IncrementStatus.Verified)))
        assertTrue(forged.validate(contract).any { it.code == GraphIssueCode.UnsupportedVerification })
        assertFalse(forged.ledger(contract, stamp)["R1"]!!.stampValid)
    }

    @Test
    fun `think more and review only nodes are rejected but named evidence and uncertainty are accepted`() {
        val think = node("I1").copy(produces = null)
        assertTrue(RequirementGraph(listOf(think)).validate(contract).any { it.code == GraphIssueCode.MissingProduction })
        val check = Acceptance.Check("AC1", "public signature unchanged", Origin.User)
        val c = contract.copy(acceptance = listOf(check), requirements = listOf(contract.requirements.single().copy(acceptance = listOf("AC1"))))
        val investigate = node("I1").copy(accept = listOf("AC1"), produces = Production.Resolves("Q1"))
        assertTrue(RequirementGraph(listOf(investigate)).validate(c).any { it.code == GraphIssueCode.MissingExecutableAcceptance })
        assertTrue(RequirementGraph(listOf(investigate.copy(evidenceKinds = mapOf("AC1" to "diff")))).validate(c).isEmpty())
        val review = c.copy(acceptance = listOf(Acceptance.Review("AC1", "review the design", Origin.User)))
        assertTrue(RequirementGraph(listOf(investigate)).validate(review).any { it.code == GraphIssueCode.MissingExecutableAcceptance })
    }

    @Test
    fun `unknown duplicate and cancelled references cannot create a phantom frontier`() {
        assertFailsWith<IllegalArgumentException> { RequirementGraph(listOf(node("I1"), node("I1"))) }
        val dangling = RequirementGraph(listOf(node("I1", "missing")))
        assertTrue(dangling.validate(contract).any { it.code == GraphIssueCode.UnknownDependency })
        assertFailsWith<IllegalStateException> { dangling.readyFrontier(contract, 3) }
        val cancelled = RequirementGraph(listOf(node("I1"), node("I2", "I1"))).cancel("I1", "replaced")
        assertTrue(cancelled.validate(contract).any { it.code == GraphIssueCode.CancelledDependency })
        assertFailsWith<IllegalStateException> { cancelled.readyFrontier(contract, 3) }
        assertEquals(RequirementStatus.Blocked, cancelled.ledger(contract, stamp)["R1"]!!.status)
    }

    @Test
    fun `Tarjan reports exact cyclic components including self loops and preserves independent nodes`() {
        val graph = RequirementGraph(listOf(node("D"), node("C", "C"), node("B", "A"), node("A", "B"), node("E", "A")))
        val cycles = graph.validate(contract).filter { it.code == GraphIssueCode.DependencyCycle }.map { it.incrementIds }
        assertEquals(listOf(listOf("A", "B"), listOf("C")), cycles)
        assertFailsWith<IllegalStateException> { graph.readyFrontier(contract, 10) }
    }

    @Test
    fun `cycle detection agrees with exhaustive reachability on random small graphs`() {
        val random = Random(402)
        repeat(100) {
            val size = 8
            val edges = Array(size) { BooleanArray(size) { random.nextInt(5) == 0 } }
            val reachable = Array(size) { edges[it].copyOf() }
            for (k in 0 until size) for (i in 0 until size) for (j in 0 until size) {
                reachable[i][j] = reachable[i][j] || reachable[i][k] && reachable[k][j]
            }
            val expected = (0 until size).filter { reachable[it][it] }
                .map { i -> (0 until size).filter { j -> reachable[i][j] && reachable[j][i] }.map { "I$it" } }
                .distinct().sortedBy { it.first() }
            val nodes = (0 until size).map { i -> node("I$i", *(0 until size).filter { edges[i][it] }.map { "I$it" }.toTypedArray()) }
            val actual = RequirementGraph(nodes.shuffled(random)).validate(contract)
                .filter { it.code == GraphIssueCode.DependencyCycle }.map { it.incrementIds }
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `frontier sorts by longest prerequisite depth then id and respects remaining cell budget`() {
        var graph = RequirementGraph(listOf(node("Z"), node("B", "A"), node("A"), node("C", "B"), node("Y", "A")))
        graph = accept(graph.continueIncrement(contract, "A", ContextId("cell-A")), "A")
        graph = accept(graph.continueIncrement(contract, "B", ContextId("cell-B")), "B")
        val expected = listOf("Z", "Y", "C")
        val random = Random(42)
        repeat(30) {
            val reordered = graph.copy(increments = graph.increments.shuffled(random))
            assertEquals(expected, reordered.readyFrontier(contract, 10).map { it.id })
            assertEquals(expected.take(2), reordered.readyFrontier(contract, 2).map { it.id })
        }
        assertTrue(graph.readyFrontier(contract, 0).isEmpty())
        assertFailsWith<IllegalArgumentException> { graph.readyFrontier(contract, -1) }
    }

    @Test
    fun `a long dependency chain validates without consuming the JVM call stack`() {
        val graph = RequirementGraph((0 until 20_000).map { i ->
            if (i == 0) node("I$i") else node("I$i", "I${i - 1}")
        })
        assertTrue(graph.validate(contract).isEmpty())
        assertEquals(listOf("I0"), graph.readyFrontier(contract, 100).map { it.id })
    }

    @Test
    fun `continuation preserves history and sizing while cancellation remains in the graph`() {
        val initial = RequirementGraph(listOf(node("I1").copy(sizing = Sizing(7, 1, 2, 3))))
        val started = initial.continueIncrement(contract, "I1", ContextId("cell-1"))
        val continued = started.continueIncrement(contract, "I1", ContextId("cell-2"))
        assertEquals(listOf(ContextId("cell-1"), ContextId("cell-2")), continued.increments.single().cells)
        assertEquals(Sizing(7, 1, 2, 3), continued.increments.single().sizing)
        assertEquals(continued, continued.continueIncrement(contract, "I1", ContextId("cell-2")))
        val cancelled = continued.cancel("I1", "hypothesis refuted")
        assertEquals("hypothesis refuted", cancelled.increments.single().cancelledReason)
        assertFailsWith<IllegalStateException> { cancelled.readyFrontier(contract, 1) }
        assertFailsWith<IllegalStateException> { cancelled.continueIncrement(contract, "I1", ContextId("cell-3")) }
        assertFailsWith<IllegalArgumentException> { initial.cancel("I1", " ") }
    }

    @Test
    fun `verified increments never restart and moved stamps create regression obligations only FX-42`() {
        val accepted = accept(RequirementGraph(listOf(node("I1"))).continueIncrement(contract, "I1", ContextId("cell-I1")), "I1")
        assertEquals(setOf("I1"), accepted.regressionObligations)
        assertTrue(accepted.readyFrontier(contract, 100).isEmpty())
        assertFailsWith<IllegalStateException> { accepted.continueIncrement(contract, "I1", ContextId("cell-again")) }
        assertFailsWith<IllegalStateException> { accepted.cancel("I1", "try again") }
        val ledger = accepted.ledger(contract, stamp)
        assertEquals(RequirementStatus.Verified, ledger["R1"]!!.status)
        assertEquals(listOf("rcpt-I1-AC1", "rcpt-I1-AC2"), ledger["R1"]!!.evidence)
        val moved = CandidateId(Digest.ofUtf8("changed"))
        assertFalse(accepted.ledger(contract, moved)["R1"]!!.stampValid)
        assertEquals(RequirementStatus.InProgress, accepted.ledger(contract, moved)["R1"]!!.status)
        assertTrue(accepted.readyFrontier(contract, 1).isEmpty())
        val refreshed = accept(accepted, "I1", moved)
        assertTrue(refreshed.ledger(contract, moved)["R1"]!!.stampValid)
        assertEquals(accepted.increments.single().cells, refreshed.increments.single().cells)
    }

    @Test
    fun `partial requirement coverage never becomes verified and contract amendments invalidate currency`() {
        var graph = RequirementGraph(listOf(node("I1").copy(accept = listOf("AC1")), node("I2").copy(accept = listOf("AC2"))))
        assertTrue(graph.validate(contract).isEmpty())
        graph = accept(graph.continueIncrement(contract, "I1", ContextId("cell-I1")), "I1")
        assertEquals(RequirementStatus.InProgress, graph.ledger(contract, stamp)["R1"]!!.status)
        graph = accept(graph.continueIncrement(contract, "I2", ContextId("cell-I2")), "I2")
        assertEquals(RequirementStatus.Verified, graph.ledger(contract, stamp)["R1"]!!.status)
        val edited = graph.copy(increments = graph.increments.map { it.copy(accept = listOf("AC1", "AC2")) })
        assertEquals(2, edited.validate(contract).count { it.code == GraphIssueCode.UnsupportedVerification })
        assertFalse(edited.ledger(contract, stamp)["R1"]!!.stampValid)
        assertFalse(graph.ledger(contract.copy(version = 2), stamp)["R1"]!!.stampValid)
    }

    @Test
    fun `graph round trip retains evidence cancellation and old S0 increment JSON still decodes`() {
        val graph = accept(RequirementGraph(listOf(node("I1"), node("old"))).cancel("old", "replaced")
            .continueIncrement(contract, "I1", ContextId("cell-I1")), "I1")
        val restored = Json.decodeFromString<RequirementGraph>(Json.encodeToString(graph))
        assertEquals(graph, restored)
        assertTrue(restored.readyFrontier(contract, 2).isEmpty())
        assertTrue(restored.ledger(contract, stamp)["R1"]!!.stampValid)
        val old = """{"id":"S0","requirementIds":["R1"],"accept":["AC1"],"writeScope":["src/"],"expectedFiles":1}"""
        val increment = Json.decodeFromString<Increment>(old)
        assertEquals(emptyList(), increment.dependsOn)
        assertEquals(Sizing(), increment.sizing)
    }

    @Test
    fun `completion cannot be replayed against another plan work or contract revision`() {
        val graph = RequirementGraph(listOf(node("I1"))).continueIncrement(contract, "I1", ContextId("cell-I1"))
        val result = completion(graph, "I1")
        val changed = graph.copy(increments = graph.increments.map { it.copy(writeScope = listOf("src/other/")) })
        assertFailsWith<IllegalArgumentException> { changed.recordAccepted(contract, result) }
        assertFailsWith<IllegalArgumentException> { graph.recordAccepted(contract.copy(version = 2), result) }
        assertFailsWith<IllegalArgumentException> { graph.recordAccepted(contract.copy(workId = WorkId("other")), result) }
        assertFailsWith<IllegalArgumentException> { graph.recordAccepted(contract.copy(attemptId = AttemptId("a2")), result) }
        val continued = graph.continueIncrement(contract, "I1", ContextId("cell-next"))
        assertFailsWith<IllegalArgumentException> { continued.recordAccepted(contract, result) }
    }

    @Test
    fun `historical verification never unlocks dependents in another work attempt or contract revision`() {
        var graph = RequirementGraph(listOf(node("I1"), node("I2", "I1")))
        graph = accept(graph.continueIncrement(contract, "I1", ContextId("cell-I1")), "I1")
        assertEquals(listOf("I2"), graph.readyFrontier(contract, 2).map { it.id })
        for (changed in listOf(contract.copy(version = 2), contract.copy(attemptId = AttemptId("a2")), contract.copy(workId = WorkId("other")))) {
            assertTrue(graph.readyFrontier(changed, 2).isEmpty())
            assertFailsWith<IllegalStateException> { graph.continueIncrement(changed, "I2", ContextId("cell-I2")) }
        }
    }

    @Test
    fun `a check only increment retains the accepted evidence reference in its ledger`() {
        val c = contract.copy(
            acceptance = listOf(Acceptance.Check("AC1", "signature unchanged", Origin.User)),
            requirements = listOf(contract.requirements.single().copy(acceptance = listOf("AC1"))),
        )
        val graph = RequirementGraph(listOf(node("I1").copy(accept = listOf("AC1"), evidenceKinds = mapOf("AC1" to "diff"))))
            .continueIncrement(c, "I1", ContextId("cell-I1"))
        val result = assertIs<CompletionResult.Accepted>(Verifier().accept(
            CompletionProposal("I1", "done", 1, stamp, stamp, null, Digest.ofUtf8("env")),
            c, graph.increments.single(), Register.empty(ContextId("cell-I1"), "I1", "check"),
            Ledger.initial(c), stamp, emptyMap(),
            assessments = listOf(Assessment("AC1", "signature unchanged", "#44", true, "user", 1)),
        ))
        val ledger = graph.recordAccepted(c, result).ledger(c, stamp)
        assertEquals(RequirementStatus.Verified, ledger["R1"]!!.status)
        assertEquals(listOf("#44"), ledger["R1"]!!.evidence)
    }

    @Test
    fun `requirement dependencies must be represented by increment prerequisites or a joint increment`() {
        val c = contract.copy(requirements = listOf(
            Requirement("R1", "base", listOf("AC1"), authorityRef = "U1"),
            Requirement("R2", "dependent", listOf("AC2"), dependsOn = listOf("R1"), authorityRef = "U1"),
        ))
        val first = node("I1").copy(accept = listOf("AC1"))
        val second = node("I2").copy(requirementIds = listOf("R2"), accept = listOf("AC2"))
        assertTrue(RequirementGraph(listOf(first, second)).validate(c).isNotEmpty())
        assertTrue(RequirementGraph(listOf(first, second.copy(dependsOn = listOf("I1")))).validate(c).isEmpty())
        val joint = RequirementGraph(listOf(node("joint").copy(requirementIds = listOf("R1", "R2"))))
        assertTrue(joint.validate(c).isEmpty())
        val cycle = c.copy(requirements = listOf(c.requirements[0].copy(dependsOn = listOf("R2")), c.requirements[1]))
        assertTrue(joint.validate(cycle).isEmpty(), "a joint increment can resolve a requirement cycle")
        assertTrue(joint.validate(c.copy(requirements = c.requirements.map { it.copy(dependsOn = listOf("unknown")) })).isNotEmpty())
    }

    @Test
    fun `graph owns nested collections so input mutation cannot rewrite a validated plan`() {
        val dependencies = mutableListOf<String>()
        val nodes = mutableListOf(node("I1").copy(dependsOn = dependencies))
        val graph = RequirementGraph(nodes)
        nodes.clear()
        dependencies += "missing"
        assertEquals(listOf("I1"), graph.increments.map { it.id })
        assertTrue(graph.validate(contract).isEmpty())
        assertFailsWith<UnsupportedOperationException> { (graph.increments as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (graph.increments.single().dependsOn as MutableList).add("missing") }
    }

    @Test
    fun `snapshot and deserialization own every nested collection with multiple elements`() {
        val requirements = mutableListOf("R1", "R2")
        val acceptance = mutableListOf("AC1", "AC2")
        val scopes = mutableListOf("src/a/", "src/b/")
        val dependencies = mutableListOf("I2", "I3")
        val cells = mutableListOf(ContextId("cell-1"), ContextId("cell-2"))
        val kinds = mutableMapOf("AC1" to "diff", "AC2" to "refs")
        val owners = mutableMapOf("src/a/a.kt" to "I1", "src/b/b.kt" to "I1")
        val refs = mutableListOf("rcpt-1", "rcpt-2")
        val increment = node("I1").copy(
            requirementIds = requirements, accept = acceptance, writeScope = scopes, dependsOn = dependencies,
            cells = cells, evidenceKinds = kinds, status = IncrementStatus.Verified,
        )
        val evidence = mutableMapOf("I1" to IncrementEvidence(
            contract.workId, contract.attemptId, 1, stamp, cells.last(), increment.definitionDigest(), refs,
        ))
        val graph = RequirementGraph(listOf(increment), owners, evidence)
        val before = Json.encodeToString(graph)
        listOf(requirements, acceptance, scopes, dependencies, cells, refs).forEach { it.clear() }
        kinds.clear()
        owners.clear()
        evidence.clear()
        assertEquals(before, Json.encodeToString(graph))
        val restored = Json.decodeFromString<RequirementGraph>(before)
        assertEquals(graph, restored)
        assertFailsWith<UnsupportedOperationException> { (restored.increments.single().accept as MutableList)[0] = "changed" }
        assertFailsWith<UnsupportedOperationException> { (restored.ownershipMap as MutableMap)["new"] = "I1" }
        assertFailsWith<UnsupportedOperationException> { (restored.evidence.getValue("I1").evidenceRefs as MutableList)[0] = "changed" }
    }

    @Test
    fun `stale requirement evidence propagates to dependents until regression verification refreshes it`() {
        val c = contract.copy(requirements = listOf(
            Requirement("R1", "base", listOf("AC1"), authorityRef = "U1"),
            Requirement("R2", "dependent", listOf("AC2"), dependsOn = listOf("R1"), authorityRef = "U1"),
        ))
        var graph = RequirementGraph(listOf(
            node("I1").copy(accept = listOf("AC1")),
            node("I2", "I1").copy(requirementIds = listOf("R2"), accept = listOf("AC2")),
        )).continueIncrement(c, "I1", ContextId("cell-I1"))
        graph = graph.recordAccepted(c, completion(graph, "I1", currentContract = c))
            .continueIncrement(c, "I2", ContextId("cell-I2"))
        val moved = CandidateId(Digest.ofUtf8("after-I2"))
        graph = graph.recordAccepted(c, completion(graph, "I2", moved, c))
        assertFalse(graph.ledger(c, moved)["R2"]!!.stampValid)
        graph = graph.recordAccepted(c, completion(graph, "I1", moved, c))
        assertTrue(graph.ledger(c, moved).entries.values.all { it.stampValid })
        assertTrue(graph.readyFrontier(c, 2).isEmpty())
    }
}
