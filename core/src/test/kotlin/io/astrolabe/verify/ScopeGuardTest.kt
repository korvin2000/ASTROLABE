package io.astrolabe.verify

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Increment
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.contract.Scope
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.workspace.PathPattern
import io.astrolabe.workspace.ProtectedPaths
import io.astrolabe.workspace.Workspace
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P1.7.8 scope guard: committed scope only, protected targets by real path, pending proposals grant nothing (IX-02, FX-52 baseline). */
class ScopeGuardTest {
    private val repo = TempRepo.create().also {
        it.write("src/pay/total.py", "def total(xs):\n    return sum(xs)\n")
        it.write("src/other.py", "x = 1\n")
        it.write("docs/readme.md", "# docs\n")
        it.write("migrations/0001.sql", "select 1;\n")
        it.write("web/package-lock.json", "{}\n")
        it.write("pyproject.toml", "[project]\nname = \"pay\"\n\n[tool.pytest.ini_options]\ntestpaths = [\"tests\"]\n")
        it.commit("initial")
    }
    private val work = WorkId("W-1")
    private val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), FakeClock.at("2026-09-20T10:00:00Z"))
    private val workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
    private val guard = ScopeGuard(workspace)

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private fun openS0(): io.astrolabe.contract.Contract {
        val derived = contracts.deriveS0(work, AttemptId("a1"), "fix total", Atlas.build(repo.root), Config(), Tokens(1_000))
        return contracts.open(derived.contract.copy(scope = derived.contract.scope.copy(writePaths = listOf("src/"))))
    }

    @Test
    fun `paths inside the committed scope pass and protected targets are refused by real path and by contract`() {
        val contract = openS0()
        val allowed = assertIs<ScopeVerdict.Allowed>(guard.check(listOf("src/pay/total.py", "./src\\other.py", "src/new.py"), contract))
        assertEquals(1, allowed.contractVersion)
        assertEquals(listOf("src/pay/total.py", "src/other.py", "src/new.py"), allowed.paths, "resolved identities, not the caller's spelling")

        val refused = assertIs<ScopeVerdict.Refused>(guard.check(listOf("src/pay/total.py", "migrations/0001.sql", "web/package-lock.json", "../outside.py"), contract))
        assertEquals(
            listOf("migrations/0001.sql" to ScopeRefusalKind.Protected, "web/package-lock.json" to ScopeRefusalKind.Protected, "../outside.py" to ScopeRefusalKind.PathRejected),
            refused.refusals.map { it.path to it.kind },
        )
        assertTrue(refused.refusals.none { it.path == "src/pay/total.py" }, "only the refused paths are listed; nothing in the batch is written")
    }

    @Test
    fun `an uncommitted scope expansion is rejected until an authorized amendment is committed (FX-52 baseline, IX-02)`() {
        openS0()
        val outside = assertIs<ScopeVerdict.Refused>(guard.check(listOf("docs/readme.md"), contracts.current(work)!!))
        assertEquals(ScopeRefusalKind.OutsideContract, outside.refusals.single().kind)
        assertTrue(outside.refusals.single().detail.contains("contract v1"), outside.refusals.single().detail)

        contracts.propose(work, null, "also write docs/", "docs must change", weakening = false)
        assertEquals(1, contracts.current(work)!!.amendmentsPending.size)
        assertIs<ScopeVerdict.Refused>(guard.check(listOf("docs/readme.md"), contracts.current(work)!!), "a pending proposal grants nothing")

        val amended = contracts.amendByUser(work, "also update docs/") { it.copy(scope = it.scope.copy(writePaths = it.scope.writePaths + "docs/")) }
        val allowed = assertIs<ScopeVerdict.Allowed>(guard.check(listOf("docs/readme.md"), amended))
        assertEquals(2, allowed.contractVersion, "the verdict names the committed version it validated against")
        assertIs<ScopeVerdict.Refused>(guard.check(listOf("docs/readme.md"), contracts.history(work).first()), "the old version still refuses")
    }

    @Test
    fun `authorized protected work is a committed contract plus a workspace bound to it, never a blanket prompt`() {
        val contract = openS0().let { c -> c.copy(scope = Scope(listOf("src/", "migrations/"), c.scope.protectedPaths - "migrations/")) }
        val defaultWorkspace = assertIs<ScopeVerdict.Refused>(guard.check(listOf("migrations/0002.sql"), contract))
        assertEquals(ScopeRefusalKind.Protected, defaultWorkspace.refusals.single().kind, "the path contract fails closed until it is bound to the contract")

        val bound = Workspace(WorkspaceId("ws-1"), repo.root, repo.git, ProtectedPaths(writeDeniedPrefixes = setOf(".git", ".github", ".gitlab")))
        val allowed = assertIs<ScopeVerdict.Allowed>(ScopeGuard(bound).check(listOf("migrations/0002.sql"), contract))
        assertEquals(listOf("migrations/0002.sql"), allowed.paths)
        assertIs<ScopeVerdict.Refused>(ScopeGuard(bound).check(listOf("web/package-lock.json"), contract), "the contract's own protected list still applies")
    }

    @Test
    fun `inside the contract but outside the increment is allowed with a warning`() {
        val contract = openS0()
        val increment = Increment("I1", listOf("R1"), accept = listOf("AC-1"), writeScope = listOf("src/pay/"), expectedFiles = 1)
        val verdict = assertIs<ScopeVerdict.Allowed>(guard.check(listOf("src/pay/total.py", "src/other.py"), contract, increment))
        assertEquals(listOf("src/other.py"), verdict.outsideIncrement)
        assertTrue(assertIs<ScopeVerdict.Allowed>(guard.check(emptyList(), contract, increment)).paths.isEmpty())
    }

    @Test
    fun `path patterns follow the one scope convention`() {
        assertTrue(PathPattern.matches("**", "any/where.py"))
        assertTrue(PathPattern.matches("src/", "src/a.py"))
        assertFalse(PathPattern.matches("src/", "srcs/a.py"))
        assertFalse(PathPattern.matches("src", "src/a.py"), "a bare name is a file-name rule, not a directory")
        assertTrue(PathPattern.matches("src/pay", "src/pay/total.py"), "a slashed path without a trailing slash covers itself and what lies below")
        assertTrue(PathPattern.matches("package-lock.json", "web/package-lock.json"))
        assertTrue(PathPattern.matches("package-lock.json", "package-lock.json"))
        assertFalse(PathPattern.matches("package-lock.json", "web/my-package-lock.json"))
        assertTrue(PathPattern.matches("**/conftest.py", "conftest.py"))
        assertTrue(PathPattern.matches("**/conftest.py", "tests/unit/conftest.py"))
        assertTrue(PathPattern.matches("src/**/*.py", "src/a/b/c.py"))
        assertFalse(PathPattern.matches("src/*.py", "src/a/b.py"))
        assertTrue(PathPattern.matches("src/?.py", "src/a.py"))
        assertFalse(PathPattern.matches("", "src/a.py"))
        assertFalse(PathPattern.matches("src/", ""))
        val scope = Scope(listOf("src/"), listOf("migrations/", "package-lock.json"))
        assertTrue(scope.allowsWrite("src/a.py"))
        assertFalse(scope.allowsWrite("src/package-lock.json"))
        assertFalse(scope.allowsWrite("docs/a.md"))
        assertTrue(scope.protects("migrations/0001.sql"))
    }
}
