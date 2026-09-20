package io.astrolabe.contract

import io.astrolabe.Config
import io.astrolabe.DClassPolicy
import io.astrolabe.Mode
import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.PackageCommands
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Tokens
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Fixture
import io.astrolabe.fixtures.FixtureRepos
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.verify.Checks
import io.astrolabe.verify.RunnerCommands
import io.astrolabe.workspace.ProtectedPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.1.2: the S0 contract derived from a bare request (§4.1 auto-derivation, §3.5 S0, D-31). */
class DeriveS0Test {
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private val config = Config(mode = Mode.Autonomous, ceiling = Stage.LocalCommit, dClass = DClassPolicy.Deny)
    private val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)

    private fun derive(root: java.nio.file.Path, text: String = "Fix the rounding bug in the total") =
        contracts.deriveS0(WorkId("W-1"), AttemptId("a1"), text, Atlas.build(root), config, Tokens(500_000))

    @Test
    fun `fixture repositories yield their declared suite as a harness-origin touched-scope acceptance`() {
        val expected = mapOf(
            Fixture.PythonSmall to listOf("python", "-m", "pytest", "-q"),
            Fixture.TsSmall to listOf("node", "--test"),
            Fixture.GradleSmall to listOf("gradle", "test"),
        )
        for ((fixture, argv) in expected) {
            FixtureRepos.materialize(fixture).use { repo ->
                val derived = derive(repo.root)
                val contract = derived.contract
                val run = assertIs<Acceptance.Run>(contract.acceptance.single(), fixture.dir)
                assertEquals("AC-1", run.id)
                assertEquals(Command(argv), run.command, fixture.dir)
                assertEquals(Origin.Harness, run.origin)
                assertEquals(Contracts.TOUCHED, run.scope)
                assertEquals("run: ${Command(argv).text} (scope touched)", run.criterion)
                assertEquals(PackageCommands.ROOT, derived.primary!!.dir)
                assertEquals(Command(argv), RunnerCommands.of(derived.primary!!).test, "the primary package seeds the registry")
                val checks = Checks.seed(contract, RunnerCommands.of(derived.primary!!))
                assertTrue(Checks.acceptId("AC-1") in checks.all().map { it.id }, fixture.dir)
                assertTrue(Checks.FULL in checks.all().map { it.id }, fixture.dir)
            }
        }
    }

    @Test
    fun `the request is R1 verbatim and the rest of the contract is the S0 default`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val contract = derive(repo.root, "Fix the rounding bug in the total").contract
            assertEquals(1, contract.version)
            assertEquals(Shape.S0, contract.shape)
            assertEquals(Mode.Autonomous, contract.mode)
            val request = contract.requests.single()
            assertEquals("U-1", request.id)
            assertEquals(clock.instant(), request.at)
            assertEquals("Fix the rounding bug in the total", request.text)
            val r1 = contract.requirements.single()
            assertEquals(Requirement("R1", "Fix the rounding bug in the total", listOf("AC-1"), authorityRef = "U-1"), r1)
            assertEquals(listOf(Scope.REPOSITORY), contract.scope.writePaths)
            assertTrue(".git/" in contract.scope.protectedPaths)
            assertTrue("migrations/" in contract.scope.protectedPaths)
            assertTrue("package-lock.json" in contract.scope.protectedPaths, "lock files are protected by name, anywhere")
            assertEquals(Authorization(Stage.LocalCommit, DClassPolicy.Deny, "workspace-local-test-only"), contract.authorization)
            assertEquals(Tokens(500_000), contract.budget.tokens)
            assertEquals(config.defaults.turnsPerCell, contract.budget.turnsPerCell)
            assertNull(contract.risk, "risk is not assessed by derivation (D-16: unknown, never low)")
            assertTrue(contract.constraints.isEmpty() && contract.exclusions.isEmpty() && contract.contractsTouched.isEmpty())
            assertFalse(contract.goalAcceptanceStated, "a sniffed suite is not goal-level acceptance")
            assertTrue(contract.strengthen(Acceptance.Check("AC-2", "total rounds half-up", Origin.Model("R1"))).goalAcceptanceStated)
            assertEquals(contract, contracts.open(contract))
            assertEquals(contract, contracts.current(WorkId("W-1")))
        }
    }

    @Test
    fun `no declared suite still derives a contract with no acceptance and no guessed command`() {
        TempRepo.create().use { repo ->
            repo.write("README.md", "# no manifest\n")
            repo.write("src/total.py", "def total(xs):\n    return sum(xs)\n")
            repo.commit("initial")
            val derived = derive(repo.root)
            assertTrue(derived.sniffed.isEmpty)
            assertNull(derived.primary)
            assertTrue(derived.contract.acceptance.isEmpty())
            assertEquals(emptyList(), derived.contract.requirements.single().acceptance)
            assertFalse(derived.contract.goalAcceptanceStated)
            assertEquals(Checks.empty().all(), Checks.seed(derived.contract, RunnerCommands()).all())
            contracts.open(derived.contract)
        }
    }

    @Test
    fun `a monorepo gets one acceptance per declared suite with its own cwd and the root package as primary`() {
        TempRepo.create().use { repo ->
            repo.write("packages/web/package.json", """{"scripts": {"test": "vitest run"}}""" + "\n")
            repo.write("services/pay/pyproject.toml", "[project]\nname = \"pay\"\n\n[tool.pytest.ini_options]\ntestpaths = [\"tests\"]\n")
            repo.write("Makefile", "test:\n\tmake -C services/pay test\n")
            repo.commit("initial")
            val derived = derive(repo.root)
            val runs = derived.contract.acceptance.map { assertIs<Acceptance.Run>(it) }
            assertEquals(listOf("AC-1", "AC-2", "AC-3"), runs.map { it.id })
            assertEquals(listOf(null, "packages/web", "services/pay"), runs.map { it.command.cwd })
            assertEquals(listOf(listOf("make", "test"), listOf("npm", "test"), listOf("python", "-m", "pytest", "-q")), runs.map { it.command.argv })
            assertEquals(listOf("AC-1", "AC-2", "AC-3"), derived.contract.requirements.single().acceptance)
            assertEquals(PackageCommands.ROOT, derived.primary!!.dir)

            // Without a root suite the first declaring package is primary and keeps its cwd in the registry commands.
            repo.write("Makefile", "lint:\n\techo lint\n")
            val noRoot = derive(repo.root)
            assertEquals("packages/web", noRoot.primary!!.dir)
            assertEquals(Command(listOf("npm", "test"), cwd = "packages/web"), RunnerCommands.of(noRoot.primary!!).test)
            assertEquals(listOf("AC-1", "AC-2"), noRoot.contract.acceptance.map { it.id })
        }
    }

    @Test
    fun `the protected list follows the path contract in force`() {
        FixtureRepos.materialize(Fixture.TsSmall).use { repo ->
            val custom = ProtectedPaths(writeDeniedPrefixes = setOf(".git", "infra"), writeDeniedNames = setOf("yarn.lock"))
            val derived = contracts.deriveS0(WorkId("W-2"), AttemptId("a1"), "add a flag", Atlas.build(repo.root), config, Tokens(1), custom)
            assertEquals(Scope(listOf("**"), listOf(".git/", "infra/", "yarn.lock")), derived.contract.scope)
        }
    }
}
