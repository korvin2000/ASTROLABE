package io.astrolabe.tool.run

import io.astrolabe.atlas.OsFamily
import io.astrolabe.auth.EffectPolicy
import io.astrolabe.auth.EffectPolicyConfig
import io.astrolabe.contract.Scope
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.RunArgs
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.ProtectedPaths
import io.astrolabe.workspace.WorkspacePath
import io.astrolabe.workspace.createJunction
import io.astrolabe.workspace.requireSupported
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-375: the file-system half of a delete or move classification, on a real workspace. */
class DiskContainmentTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    private val scope = Scope.repositoryMinus(ProtectedPaths())
    private val onWindows = OsFamily.of(System.getProperty("os.name").orEmpty()) == OsFamily.Windows

    private fun probe(limit: Int = 20_000): DiskContainment {
        val paths = WorkspacePath.of(root)
        return DiskContainment(paths, { scope.protects(it, ignoreCase = true) || paths.isProtected(it, Intent.Mutate) }, limit)
    }

    private fun write(relative: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, "x\n")
    }

    /** A directory link: a junction on Windows (no privilege needed), a symlink elsewhere; skipped visibly when refused. */
    private fun link(relative: String, target: Path): Path {
        val at = root.resolve(relative)
        Files.createDirectories(target)
        if (onWindows) return requireSupported(createJunction(at, target))
        Files.createDirectories(at.parent)
        return Files.createSymbolicLink(at, target)
    }

    @Test
    fun `plain files and directories without a protected entry are contained, a protected descendant is not`() {
        write("node_modules/a/index.js")
        write(".tools/bin/x.exe")
        write("notes.txt")
        write("packages/site/package-lock.json")
        write("packages/site/src/a.ts")
        write(".github/workflows/ci.yml")
        val probe = probe()
        for (path in listOf("node_modules", ".tools", "notes.txt", "packages/site/src", "docs/new.txt", "missing/deeper/x")) {
            assertTrue(probe.contained(path), path)
        }
        val refused = listOf("packages/site", "packages", "packages/site/package-lock.json", ".github", ".github/workflows", ".github/new.yml",
            "notes.txt/x", "a/../b", "") + if (WorkspacePath.of(root).caseInsensitive) listOf("PACKAGES/SITE", "Packages/Site/Package-Lock.json") else emptyList()
        for (path in refused) assertFalse(probe.contained(path), path)
        assertFalse(probe(limit = 1).contained("node_modules"), "more entries than the walk may visit are not inspected")
    }

    @Test
    fun `a link as the target, an ancestor or a descendant is never contained`() {
        write("src/a.txt")
        Files.writeString(Files.createDirectories(outside.resolve("victim")).resolve("keep.txt"), "keep\n")
        val links = listOf(link("src/linked", outside.resolve("victim")), link("build/cache/inner", outside.resolve("victim")))
        try {
            val probe = probe()
            assertTrue(probe.contained("src/a.txt"))
            assertFalse(probe.contained("src/linked"))
            assertFalse(probe.contained("src/linked/keep.txt"))
            assertFalse(probe.contained("src"), "src holds a link")
            assertFalse(probe.contained("build"), "build holds a link two levels down")

            // Through the run classification on this host's shell: the linked target is D, the plain file beside it W.
            val config = EffectPolicyConfig()
            fun classify(line: String) = EffectPolicy.classify(RunArgs(cmd = line), root.toString(), scope.protectedPaths, config, probe).effectClass
            assertEquals(EffectClass.D, classify(if (onWindows) "rd /s /q src\\linked" else "rm -rf src/linked"))
            assertEquals(EffectClass.D, classify(if (onWindows) "rd /s /q build" else "rm -rf build"))
            assertEquals(EffectClass.W, classify(if (onWindows) "del src\\a.txt" else "rm src/a.txt"))
        } finally {
            links.forEach(Files::delete)
        }
        assertTrue(Files.exists(outside.resolve("victim/keep.txt")))
    }
}
