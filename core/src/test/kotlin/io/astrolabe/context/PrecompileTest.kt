package io.astrolabe.context

import io.astrolabe.AttemptConfig
import io.astrolabe.Config
import io.astrolabe.Flags
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.ShapeSelector
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.contract.Increment
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.kb.Note
import io.astrolabe.kb.NoteKind
import io.astrolabe.kb.NoteStatus
import io.astrolabe.store.Store
import io.astrolabe.telemetry.PrecompileOutcome
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.evidence.Closure
import io.astrolabe.verify.CostClass
import io.astrolabe.verify.Selector
import io.astrolabe.verify.Trigger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** P3.7.1 `Precompile` (§6.6, F06): the full compile-input fingerprint and the serve-or-discard rule; no stale seed is ever served. */
class PrecompileTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val work = WorkId("W-1")
    private val ids = Identities(work, AttemptId("a1"), context = ContextId("cell-1"))
    private val stampA = CandidateId(Digest.ofUtf8("tree-a"))
    private val stampB = CandidateId(Digest.ofUtf8("tree-b"))
    private val attempt = AttemptConfig.freeze(Config(profiles = FakeProfiles.all))
    private val estimator = HeuristicEstimator()

    private fun contract(repo: TempRepo): Contract {
        repo.write("Makefile", "test:\n\techo ok\n")
        repo.commit("initial")
        return Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
            .deriveS0(work, AttemptId("a1"), "make a return 10", Atlas.build(repo.root), Config(), Tokens(200_000)).contract
    }

    private fun fingerprint(
        contract: Contract,
        increment: Increment,
        stamp: CandidateId = stampA,
        inputs: CompileInputs = CompileInputs(),
        registerVersion: Int? = null,
        pinned: List<String> = emptyList(),
        role: io.astrolabe.cell.Role = Roles.implementing,
        profile: io.astrolabe.provider.Profile = FakeProfiles.main,
        policy: AttemptConfig = attempt,
        prime: String = "repo: 1 files\n",
        maxOutputTokens: Int = 4_000,
    ): Fingerprint = Fingerprint.of(stamp, contract, increment, role, profile, policy, prime, estimator, maxOutputTokens, inputs, registerVersion, pinned)

    @Test
    fun `the same inputs give the same fingerprint and each listed input moves it`() = TempRepo.create().use { repo ->
        val contract = contract(repo)
        val increment = ShapeSelector.single(contract).increments.single()
        val base = fingerprint(contract, increment)
        assertEquals(base, fingerprint(contract, increment))
        assertEquals(base.digest, fingerprint(contract, increment).digest)
        val note = Note("CON-1", NoteKind.CON, NoteStatus.Admitted, "money is integer cents", "Callers pass cents.", "global")
        val moved = linkedMapOf(
            "stamp" to fingerprint(contract, increment, stamp = stampB),
            "contract version" to fingerprint(contract.copy(version = contract.version + 1), increment),
            "authorization" to fingerprint(contract.copy(authorization = contract.authorization.copy(capabilitySet = "other")), increment),
            "increment" to fingerprint(contract, increment.copy(id = "I-other")),
            "increment definition" to fingerprint(contract, increment.copy(title = "another title")),
            "register version" to fingerprint(contract, increment, registerVersion = 3),
            "notes" to fingerprint(contract, increment, inputs = CompileInputs(notes = listOf(note))),
            "contracts index" to fingerprint(contract, increment, inputs = CompileInputs(contractsIndex = "# contracts\n")),
            "role text version" to fingerprint(contract, increment, role = Roles.implementing.copy(policyTextVersion = "roles/next")),
            "role" to fingerprint(contract, increment, role = Roles.plan),
            "profile" to fingerprint(contract, increment, profile = FakeProfiles.tiny),
            "policy" to fingerprint(contract, increment, policy = AttemptConfig.freeze(Config(profiles = FakeProfiles.all, flags = Flags(precompile = true)))),
            "prime" to fingerprint(contract, increment, prime = "repo: 2 files\n"),
            "rules" to fingerprint(contract, increment, inputs = CompileInputs(rules = "never rewrite events")),
            "calibration" to fingerprint(contract, increment, inputs = CompileInputs(calibration = "prior: 0.5")),
            "max output tokens" to fingerprint(contract, increment, maxOutputTokens = 8_000),
            "pinned" to fingerprint(contract, increment, pinned = listOf("resumed: cell cell-0 ended failed")),
        )
        for ((field, other) in moved) {
            assertNotEquals(base, other, field)
            assertNotEquals(base.digest, other.digest, field)
            assertTrue(field in base.differences(other), "$field is named in the differences: ${base.differences(other)}")
        }
        // The note version, not only its presence: a changed body is a different input.
        val revised = fingerprint(contract, increment, inputs = CompileInputs(notes = listOf(note.copy(body = "Callers pass cents, rounded."))))
        assertNotEquals(moved.getValue("notes"), revised)
    }

    @Test
    fun `every field of the record moves the digest and is named in the differences`() = TempRepo.create().use { repo ->
        val contract = contract(repo)
        val base = fingerprint(contract, ShapeSelector.single(contract).increments.single(), registerVersion = 1, inputs = CompileInputs(contractsIndex = "idx", rules = "r", calibration = "c"))
        val mutations: Map<String, (Fingerprint) -> Fingerprint> = linkedMapOf(
            "stamp" to { it.copy(stamp = stampB.digest.hex) },
            "contract version" to { it.copy(contractVersion = it.contractVersion + 1) },
            "authorization" to { it.copy(authorization = "x") },
            "increment" to { it.copy(increment = "I-x") },
            "increment definition" to { it.copy(incrementDefinition = "x") },
            "register version" to { it.copy(registerVersion = null) },
            "carry-forward" to { it.copy(carry = "x") },
            "notes" to { it.copy(notes = listOf("CON-1@admitted:x")) },
            "contracts index" to { it.copy(contractsIndex = null) },
            "skills" to { it.copy(skills = listOf("skill-1@1")) },
            "role" to { it.copy(role = "plan") },
            "role text version" to { it.copy(roleTextVersion = "roles/next") },
            "profile" to { it.copy(profile = "tiny") },
            "policy" to { it.copy(policy = "x") },
            "prime" to { it.copy(prime = "x") },
            "rules" to { it.copy(rules = null) },
            "calibration" to { it.copy(calibration = null) },
            "estimator" to { it.copy(estimator = "other/1") },
            "max output tokens" to { it.copy(maxOutputTokens = 1) },
            "pinned" to { it.copy(pinned = "x") },
        )
        for ((field, mutate) in mutations) {
            val other = mutate(base)
            assertNotEquals(base.digest, other.digest, field)
            assertEquals(listOf(field), base.differences(other), field)
        }
        assertEquals(emptyList(), base.differences(base))
    }

    private fun boundaryLines(store: Store): List<String> = Journal(store, clock).events(JournalScope(work, kinds = setOf(JournalKind.Boundary))).map { it.text }

    private fun check(id: String, cost: CostClass): Check =
        Check(id, CheckKind.Acceptance, Selector.Touched, Closure.Unknown, cost, Trigger.IncrementEnd, acceptanceIds = listOf("AC-1"))

    @Test
    fun `only slow or expensive remaining checks qualify a boundary for pre-compilation`() = TempRepo.create().use { repo ->
        Store.open(stateRoot, repo.git, clock).use { store ->
            val precompile = Precompile(Journal(store, clock), idGen, clock)
            assertTrue(precompile.eligible(emptyList()))
            assertTrue(precompile.eligible(listOf(check("c1", CostClass.Slow), check("c2", CostClass.Expensive))))
            assertFalse(precompile.eligible(listOf(check("c1", CostClass.Slow), check("c3", CostClass.Fast))))
            assertFalse(precompile.eligible(listOf(check("c4", CostClass.Inline))))
        }
    }

    @Test
    fun `FX-44 a pre-compiled K is served on an equal fingerprint with its coverage revalidated`() = TempRepo.create().use { repo ->
        val contract = contract(repo)
        val increment = ShapeSelector.single(contract).increments.single()
        val compiled = Compiler(estimator).compile(increment, contract, FakeProfiles.main, Roles.implementing, "repo: 1 files\n")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val precompile = Precompile(Journal(store, clock), idGen, clock)
            val fp = fingerprint(contract, increment)
            val coverageCalls = ArrayList<Compiled>()
            val take = runBlocking {
                coroutineScope {
                    precompile.start(this, ids, fp, increment.id, listOf(check("c1", CostClass.Slow))) { compiled }
                    assertTrue(precompile.isPending)
                    precompile.take(fingerprint(contract, increment)) { coverageCalls += it; emptyList() }
                }
            }
            assertEquals(PrecompileOutcome.Hit, take.outcome)
            assertSame(compiled, take.compiled)
            assertNull(take.reason)
            assertEquals(listOf(compiled), coverageCalls, "coverage is revalidated on the served context")
            assertFalse(precompile.isPending)
            val lines = boundaryLines(store)
            assertTrue(lines.any { it.startsWith("precompile ${increment.id} started @${stampA.hash8}") && "c1 slow" in it }, lines.toString())
            assertTrue(lines.any { it.startsWith("precompile ${increment.id} hit: [K] reused") }, lines.toString())
            // Nothing pending afterwards: a second boundary finds none.
            assertEquals(PrecompileOutcome.None, runBlocking { precompile.take(fp) { emptyList() } }.outcome)
        }
    }

    @Test
    fun `FX-44 a moved stamp, register version or coverage discards the pre-compiled K and journals the miss`() = TempRepo.create().use { repo ->
        val contract = contract(repo)
        val increment = ShapeSelector.single(contract).increments.single()
        val compiled = Compiler(estimator).compile(increment, contract, FakeProfiles.main, Roles.implementing, "repo: 1 files\n")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val precompile = Precompile(Journal(store, clock), idGen, clock)
            val fp = fingerprint(contract, increment)
            suspend fun attempt(now: Fingerprint, coverage: List<String> = emptyList()): PrecompileTake = coroutineScope {
                precompile.start(this, ids, fp, increment.id, emptyList()) { compiled }
                precompile.take(now) { coverage }
            }
            runBlocking {
                val stamp = attempt(fingerprint(contract, increment, stamp = stampB))
                assertEquals(PrecompileOutcome.Miss, stamp.outcome)
                assertNull(stamp.compiled)
                assertEquals("stamp moved (fp ${fp.digest.hash8} → ${fingerprint(contract, increment, stamp = stampB).digest.hash8})", stamp.reason)

                val register = attempt(fingerprint(contract, increment, registerVersion = 2))
                assertEquals(PrecompileOutcome.Miss, register.outcome)
                assertTrue(register.reason!!.startsWith("register version moved"), register.reason)

                val amended = attempt(fingerprint(contract.copy(version = contract.version + 1), increment))
                assertEquals(PrecompileOutcome.Miss, amended.outcome)
                assertTrue(amended.reason!!.startsWith("contract version moved"), amended.reason)

                val coverage = attempt(fp, coverage = listOf("note CON-1"))
                assertEquals(PrecompileOutcome.Miss, coverage.outcome)
                assertEquals("required coverage no longer met: note CON-1", coverage.reason)
            }
            assertFalse(precompile.isPending)
            val misses = boundaryLines(store).filter { it.startsWith("precompile ${increment.id} miss:") }
            assertEquals(4, misses.size, misses.toString())
            assertTrue(misses.all { it.endsWith("· discarded, recompiled") }, misses.toString())
        }
    }

    @Test
    fun `a cell that does not close completed discards its pre-compile and a later proposal supersedes the pending one`() = TempRepo.create().use { repo ->
        val contract = contract(repo)
        val increment = ShapeSelector.single(contract).increments.single()
        val compiled = Compiler(estimator).compile(increment, contract, FakeProfiles.main, Roles.implementing, "repo: 1 files\n")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val precompile = Precompile(Journal(store, clock), idGen, clock)
            val fp = fingerprint(contract, increment)
            runBlocking {
                coroutineScope {
                    precompile.start(this, ids, fp, increment.id, emptyList()) { compiled }
                    precompile.discard("cell cell-1 ended partial: never a continuation of a red increment")
                    assertFalse(precompile.isPending)
                    assertEquals(PrecompileOutcome.None, precompile.take(fp) { emptyList() }.outcome)
                }
                coroutineScope {
                    precompile.start(this, ids, fp, increment.id, emptyList()) { compiled }
                    val later = fingerprint(contract, increment, stamp = stampB)
                    precompile.start(this, ids, later, increment.id, emptyList()) { compiled }
                    val take = precompile.take(later) { emptyList() }
                    assertEquals(PrecompileOutcome.Hit, take.outcome, "the later proposal's compile is the one served")
                }
            }
            val lines = boundaryLines(store)
            assertTrue(lines.any { it == "precompile ${increment.id} discarded: cell cell-1 ended partial: never a continuation of a red increment" }, lines.toString())
            assertTrue(lines.any { it.startsWith("precompile ${increment.id} superseded: a later completion proposal") }, lines.toString())
        }
    }
}
