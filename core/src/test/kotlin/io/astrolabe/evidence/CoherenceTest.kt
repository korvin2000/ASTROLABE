package io.astrolabe.evidence

import io.astrolabe.atlas.Atlas
import io.astrolabe.contract.Command
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.WorkspaceId
import io.astrolabe.register.Fact
import io.astrolabe.register.Register
import io.astrolabe.verify.Applicability
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.Checks
import io.astrolabe.verify.CostClass
import io.astrolabe.verify.LastResult
import io.astrolabe.verify.Selector
import io.astrolabe.verify.Trigger
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.Ranges
import io.astrolabe.workspace.VersionChange
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1.4.4: one registry, one rule — a version change marks the turn, cell and verification horizons, schedules
 * the checker, and nothing moved is ever served as current (§4.4). FX-07 in its P1 form (reads, facts and
 * receipts invalidated by a known closure after a run touched a file) and FX-16 (old green ⇒ stale after a
 * source, lockfile or check-definition change; reuse proofs are P3.1.2).
 */
class CoherenceTest {
    private val repo = TempRepo.create().also {
        it.write("src/a.py", "def a():\n    return 1\n")
        it.write("src/b.py", "def b():\n    return 2\n")
        it.write("README.md", "# fixture\n")
        it.commit("initial")
    }
    private val workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
    private val registry = VersionRegistry(workspace)
    private val coherence = Coherence(registry)

    private val s1 = CandidateId(Digest.ofUtf8("s1"))
    private val s2 = CandidateId(Digest.ofUtf8("s2"))

    @AfterTest
    fun tearDown() {
        coherence.close()
        repo.close()
    }

    private fun check(id: String, closure: Closure, kind: CheckKind = CheckKind.Type): Check =
        Check(id, kind, Selector.Touched, closure, CostClass.Fast, Trigger.EndOfTurn, command = Command(listOf("pyright")))

    private fun green(check: Check, stamp: CandidateId, definition: Digest = check.definitionVersion) =
        LastResult("rcpt-${check.id}", stamp, definition, Outcome.Passed, Counts(passed = 3, discovered = 3), Applicability.Current)

    private fun entry(path: String, version: FileVersion, id: String) =
        Entry(path, Ranges.single(1, 2), version, EntrySource.Look, turn = 1, resultId = id, tokens = 20)

    @Test
    fun `a run that touches a file drops reads, marks facts and receipts by closure, and schedules the checker (FX-07)`() {
        val va = registry.version("src/a.py")!!
        val vb = registry.version("src/b.py")!!
        val heard = ArrayList<String>()

        val workset = Workset()
        workset.register(entry("src/a.py", va, "#1"))
        workset.register(entry("src/b.py", vb, "#2"))
        var register = Register.empty(ContextId("cell-1"), "I1", "fix b").copy(
            facts = listOf(
                Fact(1, ClaimKind.Verified, "a returns 1", Anchor("src/a.py", va, 2), "#1"),
                Fact(2, ClaimKind.Verified, "b returns 2", Anchor("src/b.py", vb, 2), "#2"),
            ),
        )
        val checks = Checks.empty()
        checks.register(check("CHK-types-touched", Closure.Known(setOf("src/b.py"))))
        checks.register(check("CHK-accept-AC-1", Closure.Known(setOf("src/a.py")), CheckKind.Acceptance))
        checks.register(check("CHK-full", Closure.Unknown, CheckKind.Full))
        checks.all().forEach { checks.record(it.id, green(it, s1)) }
        var atlas = Atlas.build(repo.root)
        val hashBefore = atlas.row("src/b.py")!!.hash8

        coherence.register { heard += "workset" }
        coherence.register(workset)
        coherence.register { register = register.markStale(it) }
        coherence.register(checks)
        coherence.register { heard += "last" }

        // A formatter run rewrites b.py behind the model's back; the runner announces it from the stamp diff.
        repo.write("src/b.py", "def b():\n    return 2  # formatted\n")
        val vb2 = registry.version("src/b.py")!!
        registry.change("src/b.py", vb, vb2, "touched by run #7")
        registry.change("src/b.py", vb, vb2, "touched by run #7")

        assertEquals(listOf("workset", "last"), heard, "horizons hear one change each, in registration order")
        assertFalse(workset.covers("src/b.py", vb, LineRange(1, 2)), "the live read at the old version left KNOWN")
        assertTrue(workset.covers("src/a.py", va, LineRange(1, 2)))
        val drop = workset.pendingDrops.single()
        assertEquals("src/b.py:1-2 stale @${vb.hash8} (touched by run #7) → recall #2 or read again", drop.text)
        assertEquals(vb, register.fact(2)!!.staleAt)
        assertNull(register.fact(1)!!.staleAt)
        assertEquals(Applicability.Stale, checks["CHK-types-touched"]!!.last!!.applicability)
        assertEquals("closure moved: src/b.py (touched by run #7)", checks["CHK-types-touched"]!!.last!!.staleReason)
        assertEquals("closure unknown; src/b.py changed (touched by run #7)", checks["CHK-full"]!!.last!!.staleReason)
        assertEquals(Applicability.Current, checks["CHK-accept-AC-1"]!!.last!!.applicability, "a known closure that did not move survives")
        assertEquals(Outcome.Passed, checks["CHK-types-touched"]!!.last!!.outcome, "the historical outcome never changes")

        assertEquals(setOf("src/b.py"), coherence.scheduled)
        val touched = coherence.takeScheduled()
        assertEquals(setOf("src/b.py"), touched)
        assertTrue(coherence.scheduled.isEmpty())
        atlas = atlas.refresh(touched)
        assertEquals(Digest.of(Files.readAllBytes(repo.resolve("src/b.py"))).hash8, atlas.row("src/b.py")!!.hash8)
        assertTrue(atlas.row("src/b.py")!!.hash8 != hashBefore)

        assertEquals(Served.Stale(mapOf("src/b.py" to vb2)), coherence.serve(mapOf("src/b.py" to vb)))
        assertEquals(Served.Current, coherence.serve(Anchor("src/a.py", va, 2)))
        assertEquals(Served.Current, coherence.serve(mapOf("src/a.py" to va, "src/b.py" to vb2)))
    }

    @Test
    fun `full closure invalidation covers package members, new files, deletions and the environment (FX-07 full)`() {
        val checks = Checks.empty()
        checks.register(check("CHK-pkg-src", Closure.Package("src"), CheckKind.Unit))
        checks.register(check("CHK-pkg-docs", Closure.Package("docs"), CheckKind.Unit))
        checks.register(check("CHK-known-a", Closure.Known(setOf("src/a.py"))))
        checks.all().forEach { checks.record(it.id, green(it, s1)) }
        coherence.register(checks)
        fun stale() = checks.all().filter { it.last!!.applicability == Applicability.Stale }.map { it.id }

        // A new file inside a package closure is a member the old receipt never saw.
        repo.write("src/c.py", "def c():\n    return 3\n")
        registry.change("src/c.py", null, registry.version("src/c.py"), "created by edit #3")
        assertEquals(listOf("CHK-pkg-src"), stale())
        assertEquals("closure moved: src/c.py (created by edit #3)", checks["CHK-pkg-src"]!!.last!!.staleReason)

        // A deletion inside a known closure moves it, whatever the old version was.
        val va = registry.version("src/a.py")!!
        Files.delete(repo.resolve("src/a.py"))
        registry.change("src/a.py", va, null, "deleted by transform #4")
        assertEquals(listOf("CHK-pkg-src", "CHK-known-a"), stale())
        assertEquals(Applicability.Current, checks["CHK-pkg-docs"]!!.last!!.applicability, "a package that did not move survives")

        // A lockfile is an input of every check's environment, whatever the closure says.
        repo.write("Pipfile.lock", "# lock\n")
        registry.change("Pipfile.lock", null, registry.version("Pipfile.lock"), "touched by run #5")
        assertEquals(listOf("CHK-pkg-src", "CHK-pkg-docs", "CHK-known-a"), stale())
        assertEquals("environment moved: Pipfile.lock (touched by run #5)", checks["CHK-pkg-docs"]!!.last!!.staleReason)
        assertTrue(checks.all().all { it.last!!.outcome == Outcome.Passed }, "the historical outcomes never change")
    }

    @Test
    fun `serve never says current for a moved, deleted, externally rewritten or refused anchor`() {
        val va = registry.version("src/a.py")!!
        val readme = registry.version("README.md")!!

        Files.delete(repo.resolve("README.md"))
        registry.change("README.md", readme, null, "deleted by run #8")
        assertEquals(Served.Stale(mapOf("README.md" to null)), coherence.serve(mapOf("README.md" to readme)))

        // An external rewrite the registry was never told about is still caught: serve hashes the bytes now.
        repo.write("src/a.py", "def a():\n    return 10\n")
        val served = assertIs<Served.Stale>(coherence.serve(mapOf("src/a.py" to va)))
        assertEquals(registry.version("src/a.py"), served.moved["src/a.py"])
        assertEquals(Served.Current, coherence.serve(mapOf("src/a.py" to registry.version("src/a.py")!!)))

        assertEquals(Served.Unknown(setOf("../outside.py")), coherence.serve(mapOf("../outside.py" to va)))
        assertIs<Served.Stale>(coherence.serve(mapOf("../outside.py" to va, "src/a.py" to va)), "a moved anchor outranks an unresolved one")
        assertEquals(Served.Current, coherence.serve(emptyMap()))
        assertEquals(setOf("README.md"), coherence.scheduled, "a deletion is scheduled too; the checker and the atlas drop it")
    }

    @Test
    fun `an old green result is stale after a source, lockfile or check-definition change (FX-16)`() {
        val checks = Checks.empty()
        val types = checks.register(check("CHK-types-touched", Closure.Known(setOf("src/a.py"))))
        val full = checks.register(check("CHK-full", Closure.Unknown, CheckKind.Full))
        checks.record(types.id, green(types, s1))
        checks.record(full.id, green(full, s1))
        coherence.register(checks)

        assertEquals(listOf(Applicability.Current, Applicability.Current), checks.refresh(s1).map { it.last!!.applicability })

        // Source moved outside the known closure: the stamp differs, and without a reuse proof that is stale.
        val stale = checks.refresh(s2)
        assertEquals(Applicability.Stale, stale.first { it.id == types.id }.last!!.applicability)
        assertEquals("candidate moved @${s1.digest.hash8} → @${s2.digest.hash8} (no reuse proof)", stale.first { it.id == types.id }.last!!.staleReason)
        assertEquals(Outcome.Passed, stale.first { it.id == types.id }.last!!.outcome)
        assertEquals(Applicability.Current, checks.refresh(s1).first { it.id == types.id }.last!!.applicability, "back on the tested candidate the receipt applies again (L7)")

        // Source moved inside the closure: marked at once, and the closure reason survives the next refresh.
        val va = registry.version("src/a.py")!!
        repo.write("src/a.py", "def a():\n    return 3\n")
        registry.change("src/a.py", va, registry.version("src/a.py"), "edit #4")
        assertEquals("closure moved: src/a.py (edit #4)", checks[types.id]!!.last!!.staleReason)
        assertEquals("closure moved: src/a.py (edit #4)", checks.refresh(s2).first { it.id == types.id }.last!!.staleReason)

        // A lock file is an environment input of every check, whatever its closure says. (The first stale reason of
        // a result is kept, so both checks are re-recorded green first.)
        checks.record(types.id, green(types, s2))
        checks.record(full.id, green(full, s2))
        repo.write("package-lock.json", "{}\n")
        registry.change("package-lock.json", null, registry.version("package-lock.json"), "run #9 (npm install)")
        assertEquals("environment moved: package-lock.json (run #9 (npm install))", checks[types.id]!!.last!!.staleReason)
        assertEquals("environment moved: package-lock.json (run #9 (npm install))", checks[full.id]!!.last!!.staleReason)

        // A changed check definition (argv or parser policy) invalidates the result even on the same candidate.
        val redefined = Checks.empty()
        redefined.register(types.copy(parserPolicy = "shaper/2", last = green(types, s2)))
        val refreshed = redefined.refresh(s2).single().last!!
        assertEquals(Applicability.Stale, refreshed.applicability)
        assertEquals("check definition changed since rcpt-CHK-types-touched", refreshed.staleReason)

        assertEquals(Applicability.Unknown, redefined.refresh(null).single().last!!.applicability, "no current stamp ⇒ unknown, never current")
    }

    @Test
    fun `closing the subscription stops the fan-out and a change needs a cause`() {
        val heard = ArrayList<VersionChange>()
        coherence.register { heard += it }
        val va = registry.version("src/a.py")!!
        coherence.close()
        registry.change("src/a.py", va, null, "deleted")
        assertTrue(heard.isEmpty())
        assertTrue(runCatching { VersionChange("src/a.py", va, va, "edit") }.isFailure, "from == to is not a transition")
        assertTrue(runCatching { VersionChange("src/a.py", va, null, " ") }.isFailure, "the announcement names its cause")
    }
}
