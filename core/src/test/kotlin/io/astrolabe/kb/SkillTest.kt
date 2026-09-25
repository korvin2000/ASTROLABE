package io.astrolabe.kb

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.ShapeSelector
import io.astrolabe.cell.Roles
import io.astrolabe.context.Compiled
import io.astrolabe.context.CompileInputs
import io.astrolabe.context.Compiler
import io.astrolabe.context.ContextOmission
import io.astrolabe.context.ContextUnitId
import io.astrolabe.context.Manifest
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** P4.3.1: skills filter at module granularity; mandatory modules, prerequisites and invariants survive every filter (F06). */
class SkillTest {
    @TempDir
    lateinit var stateRoot: Path

    private val estimator = HeuristicEstimator()
    private val clock = FakeClock.at("2026-09-25T10:00:00Z")

    private fun migration(rollbackBody: String = "Write the down migration next to the up one.", tokenBudget: Int = 2_000, lockBody: String = "Take the schema lock; never edit an applied migration.") = Skill(
        id = "SKILL-migrate", version = 1,
        trigger = SkillTrigger(paths = listOf("db/migrations/**"), terms = listOf("migration")),
        prerequisites = listOf("the migration tool runs locally"),
        steps = listOf("add a new numbered migration", "run the migration check", "update the schema snapshot"),
        expectedArtifacts = listOf("db/migrations/NNN_*.sql", "schema.sql"),
        verification = listOf("migration check passes on a fresh database"),
        failureExit = "stop and report the failing migration as Open",
        freshness = "valid while CON-4@v2 stands",
        tokenBudget = tokenBudget,
        modules = listOf(
            // The counterexample (MB §8.5): the module a role filter would drop is the one that must survive.
            SkillModule("schema-lock", lockBody, appliesTo = setOf("review"), mandatory = true),
            SkillModule("rollback", rollbackBody),
            SkillModule("review-notes", "Check the index names.", appliesTo = setOf("review")),
        ),
        invariants = listOf("an applied migration is immutable"),
        authority = listOf("CON-4"),
    )

    private fun contract(): Contract = TempRepo.create().use { repo ->
        repo.write("Makefile", "test:\n\techo ok\n")
        repo.commit("initial")
        Contracts(InMemoryContractRepository(), FixedIdGen(), FakeClock.at("2026-09-24T10:00:00Z"))
            .deriveS0(WorkId("W-1"), AttemptId("a1"), "make a return 10", Atlas.build(repo.root), Config(), Tokens(200_000)).contract
    }

    @Test
    fun `the migration skill keeps its mandatory module under an aggressive filter`() {
        val views = SkillViews(estimator)
        val view = views.view(migration(), Roles.implementing.name)
        assertEquals(listOf("rollback"), view.optional.map { it.id })
        assertEquals(mapOf("review-notes" to "role"), view.omitted)

        val aggressive = view.filter(estimator, keep = emptySet(), maxTokens = 0).render()
        for (kept in listOf("Take the schema lock", "the migration tool runs locally", "an applied migration is immutable", "3. update the schema snapshot", "procedure, not authority")) {
            assertTrue(kept in aggressive, "'$kept' survives the filter:\n$aggressive")
        }
        assertFalse("down migration" in aggressive)
        assertTrue("rollback (filter)" in aggressive && "review-notes (role)" in aggressive, "omissions are named, never absent")

        // The compiler charges the mandatory part to the total budget and lets optional modules compete for the rest.
        val contract = contract()
        val increment = ShapeSelector.single(contract).increments.single()
        val huge = migration(rollbackBody = "rollback step ".repeat(80_000), tokenBudget = 1_000_000)
        val inputs = CompileInputs(skills = listOf(huge))
        val ready = assertIs<Compiled.Ready>(Compiler(estimator).compile(increment, contract, FakeProfiles.main, Roles.implementing, "repo: 1 files\n", inputs = inputs))
        assertTrue(ContextUnitId("skill.SKILL-migrate") in ready.selection.mandatoryIds)
        assertEquals(ContextOmission.Budget, ready.selection.omissions[ContextUnitId("skill.SKILL-migrate.rollback")])
        assertTrue(ready.k.sections.any { "Take the schema lock" in it.text })
        val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
        assertEquals(listOf("SKILL-migrate@v1"), Manifest.of("m-1", ready, increment, contract, ids, FakeProfiles.main, inputs).skills)

        // A mandatory module that cannot fit is a rescoping request, never a silent drop.
        val heavy = migration(lockBody = "lock step ".repeat(100_000))
        val rescope = assertIs<Compiled.NeedsRescoping>(Compiler(estimator).compile(increment, contract, FakeProfiles.main, Roles.implementing, "repo: 1 files\n", inputs = CompileInputs(skills = listOf(heavy))))
        assertTrue(ContextUnitId("skill.SKILL-migrate") in rescope.selection.mandatoryIds)
    }

    @Test
    fun `role views are cached per version and role and kb skill serves the admitted procedure`() {
        val views = SkillViews(estimator)
        val skill = migration()
        assertSame(views.view(skill, "implementing"), views.view(skill, "implementing"))
        views.view(skill, "review")
        assertEquals(2, views.size)
        assertFailsWith<IllegalArgumentException> { views.view(skill.copy(steps = listOf("edit the applied migration")), "implementing") }
        assertEquals(3, views.view(skill.copy(version = 2), "implementing").let { views.size })

        TempRepo.create().use { repo ->
            repo.write("a.txt", "a")
            repo.commit("initial")
            Store.open(stateRoot, repo.git, clock).use { store ->
                val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
                val candidate = SkillStore(store).candidate(skill, "database migrations: numbered, checked, never edited once applied", "subsystem:db", ids)
                assertEquals(listOf(PROCEDURE_MODULE), candidate.modules.map { it.id })
                val writer = KbWriter(store, estimator, clock)
                writer.write(candidate, ids)
                val kb = StoreKb(store, WorkId("W-1"), { null })
                assertNull(kb.skill("SKILL-migrate"), "a candidate skill is not served")
                writer.setStatus("SKILL-migrate", NoteStatus.Admitted, ids)
                val text = kb.skill("SKILL-migrate")!!.text
                assertTrue("modules: procedure@v1" in text && "Take the schema lock" in text && "review-notes (role)" in text, text)
                assertEquals(skill, SkillStore(store).load(Notes(store).get("SKILL-migrate")!!))
            }
        }
    }

    @Test
    fun `triggers fire on state changes and overlaps resolve against the task's authority`() {
        val migrate = migration()
        val seed = Skill(
            "SKILL-seed", 1, SkillTrigger(paths = listOf("db/**")), emptyList(), listOf("regenerate the seed data"), emptyList(), emptyList(),
            "report the seed diff", "valid at r1", 400,
        )
        val change = StateChange(StateChangeKind.IncrementOpened, paths = listOf("db/migrations/007_add_index.sql"))
        assertEquals(listOf("SKILL-migrate", "SKILL-seed"), Skills.triggered(listOf(seed, migrate), change).map { it.id })
        assertEquals(listOf("SKILL-migrate"), Skills.triggered(listOf(seed, migrate), StateChange(StateChangeKind.CheckFailed, text = "Migration check failed")).map { it.id })

        val decided = Skills.resolve(listOf(seed, migrate), change, authority = setOf("CON-4", "R-1"))
        assertEquals(listOf("SKILL-migrate"), decided.active.map { it.id })
        assertEquals("SKILL-migrate", decided.conflicts.single().winner)

        val open = Skills.resolve(listOf(seed, migrate), change, authority = setOf("R-1"))
        assertEquals(listOf("SKILL-migrate", "SKILL-seed"), open.active.map { it.id })
        assertTrue("unresolved" in open.conflicts.single().line)

        // P5.6.2 (D-112): the conflict reaches the implementing [K] as a mandatory line beside both skills.
        val contract = contract()
        val increment = ShapeSelector.single(contract).increments.single()
        val ready = assertIs<Compiled.Ready>(
            Compiler(estimator).compile(increment, contract, FakeProfiles.main, Roles.implementing, "repo: 1 files\n", inputs = CompileInputs(skills = open.active, skillConflicts = open.conflicts)),
        )
        assertTrue(ContextUnitId("skill.conflicts") in ready.selection.mandatoryIds)
        assertTrue(ready.k.sections.any { open.conflicts.single().line in it.text }, ready.k.sections.joinToString("\n") { it.text })
    }
}
