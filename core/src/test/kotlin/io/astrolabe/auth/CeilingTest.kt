package io.astrolabe.auth

import io.astrolabe.Config
import io.astrolabe.DClassPolicy
import io.astrolabe.contract.Authorization
import io.astrolabe.provider.ToolMask
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.RunArgs
import io.astrolabe.tool.ToolOps
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.10.2 — capability ceiling and effect policy (§14.1, L10, D-11). */
class CeilingTest {

    private val root = "/w"
    private val protectedPaths = listOf(".git", "ci/**", "*.lock", "db/migrations")

    private fun classify(vararg argv: String, cwd: String? = null) =
        EffectPolicy.classify(argv.toList(), cwd, root, protectedPaths)

    @Test
    fun `the D-class table — outside workspace, protected paths, network, git refs, install, privilege, destructive`() {
        val expected = listOf(
            Triple(listOf("pip", "install", "requests"), EffectClass.D, Capability.PackageInstall),
            Triple(listOf("npm", "i"), EffectClass.D, Capability.PackageInstall),
            Triple(listOf("curl", "-s", "https://example.com/x"), EffectClass.D, Capability.Network),
            Triple(listOf("ssh", "host", "uptime"), EffectClass.D, Capability.Network),
            Triple(listOf("git", "push"), EffectClass.D, Capability.GitRefs),
            Triple(listOf("git", "-C", "sub", "reset", "--hard"), EffectClass.D, Capability.GitRefs),
            Triple(listOf("git", "checkout", "."), EffectClass.D, Capability.GitRefs),
            Triple(listOf("sudo", "make", "install"), EffectClass.D, Capability.Privilege),
            Triple(listOf("rm", "-rf", "../x"), EffectClass.D, Capability.OutsideWorkspace),
            Triple(listOf("cp", "a.txt", "/etc/x"), EffectClass.D, Capability.OutsideWorkspace),
        )
        for ((argv, effect, capability) in expected) {
            val classification = EffectPolicy.classify(argv, null, root, protectedPaths)
            assertEquals(effect, classification.effectClass, "$argv → $classification")
            assertContains(classification.requiredCapabilities, capability, "$argv → $classification")
            assertTrue(classification.reasons.isNotEmpty(), "$argv carries no reason")
        }

        // Protected paths are D even inside the workspace (D-31).
        assertEquals(EffectClass.D, classify("cp", "a.txt", ".git/config").effectClass)
        assertEquals(EffectClass.D, classify("sed", "-i", "s/a/b/", "ci/release.yml").effectClass)
        assertEquals(EffectClass.D, classify("touch", "db/migrations/003.sql").effectClass)
        assertContains(classify("touch", "poetry.lock").reasons.toString(), "protected path '*.lock'")

        // git that does not move a ref is not D.
        assertEquals(EffectClass.R, classify("git", "status").effectClass)
        assertEquals(EffectClass.R, classify("git", "status", "--short").effectClass)
        assertEquals(EffectClass.R, classify("git", "diff", "--stat").effectClass)
    }

    @Test
    fun `W covers builders, formatters, test runners, scripts and workspace deletes, and R is the remaining label`() {
        assertEquals(EffectClass.W, classify("pytest", "-q").effectClass)
        assertEquals(EffectClass.W, classify("./gradlew", ":core:test").effectClass)
        assertEquals(EffectClass.W, classify("ruff", "format", "src").effectClass)
        assertEquals(EffectClass.W, classify("rm", "-rf", "tmp/cache").effectClass)
        assertContains(classify("pytest", "-q").requiredCapabilities, Capability.WorkspaceWrite)

        assertEquals(EffectClass.R, classify("ls", "-la").effectClass)
        assertEquals(EffectClass.R, classify("echo", "hello").effectClass)
        assertEquals(setOf(Capability.RunLocal, Capability.WorkspaceRead), classify("ls", "-la").requiredCapabilities)

        // A script is W by argv shape, and says so: its real effects are known only from the stamp diff.
        val script = classify("python", "scripts/gen.py")
        assertEquals(EffectClass.W, script.effectClass)
        assertTrue(script.effectsUnknown)
        assertContains(script.reasons.toString(), "effects are unknown until the stamp diff")
        assertFalse(script.approximate)
    }

    @Test
    fun `read-only search, inspection and git read forms are R, and each writing or executing option is W unknown`() {
        val readOnly = listOf(
            listOf("rg", "-n", "TODO", "src"), listOf("grep", "-rn", "x", "src"), listOf("head", "-n", "5", "a.txt"),
            listOf("wc", "-l", "a.txt"), listOf("find", "src", "-name", "*.kt"), listOf("findstr", "/s", "x", "*.kt"),
            listOf("tree", "src"), listOf("sort", "-u", "a.txt"), listOf("uniq", "-c", "a.txt"), listOf("date", "+%F"),
            listOf("hostname"), listOf("dir"), listOf("file", "a.txt"), listOf("git", "log", "--oneline", "-5"),
            listOf("git", "show", "HEAD"), listOf("git", "diff", "HEAD~1"), listOf("git", "blame", "a.txt"),
            listOf("git", "grep", "-n", "x"), listOf("git", "ls-files"), listOf("git", "rev-parse", "HEAD"),
            listOf("git", "cat-file", "-p", "HEAD"), listOf("git", "branch", "-a"), listOf("git", "branch", "--list", "f*"),
            listOf("git", "tag", "-l"), listOf("git", "remote", "-v"), listOf("git", "config", "--get", "user.name"),
            listOf("git", "shortlog", "-sn"),
        )
        for (argv in readOnly) {
            val classification = classify(*argv.toTypedArray())
            assertEquals(EffectClass.R, classification.effectClass, "$argv")
            assertFalse(classification.effectsUnknown, "$argv")
            assertEquals(setOf(Capability.RunLocal, Capability.WorkspaceRead), classification.requiredCapabilities, "$argv")
        }
        val guarded = listOf(
            listOf("rg", "--pre", "prog", "x"), listOf("rg", "--pre-glob", "*.gz", "x"),
            listOf("find", ".", "-exec", "touch", "{}", ";"), listOf("find", ".", "-execdir", "x", ";"),
            listOf("find", ".", "-ok", "x", ";"), listOf("find", ".", "-okdir", "x", ";"), listOf("find", ".", "-delete"),
            listOf("find", ".", "-fprint", "out"), listOf("find", ".", "-fprint0", "out"), listOf("find", ".", "-fprintf", "out", "%p"),
            listOf("find", ".", "-fls", "out"), listOf("tree", "-o", "out"), listOf("sort", "-o", "out", "a.txt"),
            listOf("sort", "-uo", "out", "a.txt"), listOf("sort", "--output=out", "a.txt"), listOf("sort", "--out=out", "a.txt"),
            listOf("sort", "--compress-program=gzip", "a.txt"), listOf("uniq", "a.txt", "out.txt"), listOf("date", "-s", "tomorrow"),
            listOf("date", "0101"), listOf("hostname", "newname"), listOf("file", "-C", "-m", "magic"),
            listOf("git", "log", "--output=out"), listOf("git", "diff", "--output", "out"), listOf("git", "show", "--ext-diff"),
            listOf("git", "grep", "-O", "x"), listOf("git", "grep", "--open-files-in-pager=vi", "x"),
            listOf("git", "-C", "sub", "status"), listOf("git", "-c", "core.pager=x", "log"), listOf("git", "--git-dir=x", "log"),
            listOf("git", "branch", "feature"), listOf("git", "branch", "-m", "a", "b"), listOf("git", "tag", "v1"),
            listOf("git", "remote", "add", "o", "u"), listOf("git", "config", "user.name", "x"),
            listOf("git", "cat-file", "--batch"), listOf("sed", "-n", "1p", "a.txt"), listOf("awk", "1", "a.txt"),
            listOf("env"), listOf("xargs", "rm"), listOf("printenv"), listOf("/usr/bin/grep", "x", "a.txt"),
        )
        for (argv in guarded) {
            val classification = classify(*argv.toTypedArray())
            assertEquals(EffectClass.W, classification.effectClass, "$argv")
            assertTrue(classification.effectsUnknown, "$argv")
        }
        // Redirects and substitutions are classified independently of the allowlist.
        assertEquals(EffectClass.W, EffectPolicy.classify(RunArgs(cmd = "rg x src > out.txt"), root, protectedPaths).effectClass)
        assertEquals(EffectClass.W, EffectPolicy.classify(RunArgs(cmd = "git log >> out.txt"), root, protectedPaths).effectClass)
        assertTrue(EffectPolicy.classify(RunArgs(cmd = "grep x $(touch y)"), root, protectedPaths).effectsUnknown)
        assertTrue(EffectPolicy.classify(RunArgs(cmd = "cat <(touch y)"), root, protectedPaths).effectsUnknown)
        assertEquals(EffectClass.R, EffectPolicy.classify(RunArgs(cmd = "git log --oneline | head -5"), root, protectedPaths).effectClass)
    }

    /** The live run's three denied `run` calls (D-373), verbatim from its events. */
    private val live: Map<String, String> = javaClass.getResourceAsStream("/live/d373-calls.jsonl")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
        .lines().filter { it.isNotBlank() }.map { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }
        .filter { (it.getValue("name") as kotlinx.serialization.json.JsonPrimitive).content == "run" }
        .associate { o -> (o.getValue("id") as kotlinx.serialization.json.JsonPrimitive).content to (o.getValue("argsJson") as kotlinx.serialization.json.JsonPrimitive).content }

    private fun cmd(line: String) = EffectPolicy.classify(RunArgs(cmd = line), root, protectedPaths)

    @Test
    fun `read-only probes outside the workspace are R and pass the workspace ceiling, and anything that writes or runs more is not`() {
        val ceiling = Ceiling(CapabilitySet.WORKSPACE_LOCAL_TEST_ONLY, Stage.Patch, ExecutionMode.TrustedLocal)
        for (id in listOf("call_02_9532tzlo1xubzye3tzub0v1z", "call_01_yplspwwizis04wf1z1f8nllx")) {
            val args = kotlinx.serialization.json.Json.decodeFromString(RunArgs.serializer(), live.getValue(id))
            val classification = EffectPolicy.classify(args, root, protectedPaths)
            assertEquals(EffectClass.R, classification.effectClass, "${args.cmd} -> $classification")
            assertNull(ceiling.allows(classification), "${args.cmd}")
        }
        for (line in listOf("where chrome", "which node", "ver", "ls -la /usr/lib", "type C:/x/a.txt", "cat /etc/hosts",
            "if not exist \"C:/Program Files/x.exe\" (echo NONE) else (echo SOME)", "where chrome && dir /b C:/Windows || echo none")) {
            assertEquals(EffectClass.R, cmd(line).effectClass, "$line -> ${cmd(line)}")
        }
        for (line in listOf("dir /b C:/Windows > out.txt", "type C:/x 2> ../err.txt", "if exist C:/x (del C:/x)", "where chrome & curl -s https://e.x",
            "cat /etc/hosts $(touch y)", "echo hi > C:/x.txt", "if exist C:/x (C:/x/run.exe)", "where chrome & npm i left-pad")) {
            assertTrue(cmd(line).effectClass != EffectClass.R, "$line -> ${cmd(line)}")
        }
        assertEquals(EffectClass.D, cmd("dir /b C:/Windows > out.txt").effectClass, "a probe that writes a file is classified as before")
    }

    @Test
    fun `deleting or moving paths inside the workspace is W and the root, a protected path or an outside target stays D`() {
        val rmdir = cmd(kotlinx.serialization.json.Json.decodeFromString(RunArgs.serializer(), live.getValue("call_00_jtg3raywrogarhtrukhkvr4g")).cmd!!)
        assertEquals(EffectClass.W, rmdir.effectClass, rmdir.toString())
        assertContains(rmdir.reasons.toString(), "delete or move inside the workspace: 'rmdir /s'")
        for (line in listOf("rd /s /q build/out", "del /s /q .tools/*.tmp", "erase notes.txt", "rm -rf node_modules", "rm -rf src/*",
            "mv a.txt b.txt", "move a.txt docs/a.txt", "Remove-Item -Recurse -Force .tools")) {
            assertEquals(EffectClass.W, cmd(line).effectClass, "$line -> ${cmd(line)}")
        }
        for (line in listOf("rmdir /s /q .", "rmdir /s /q ../x", "rm -rf *", "rm -rf ./*", "rm -rf ci", "rm -rf ci/x", "del /s /q .git", "rmdir /s /q C:/",
            "rm -rf /", "mv a.txt ../b.txt", "move a.txt C:/b.txt", "rm -rf ~/x", "rm -rf db", "Remove-Item -Recurse -Force C:/Users")) {
            assertEquals(EffectClass.D, cmd(line).effectClass, "$line -> ${cmd(line)}")
        }
    }

    @Test
    fun `unknown local executables and wrappers have unknown write effects`() {
        for (argv in listOf(listOf("./scripts/deploy-helper"), listOf("mystery-wrapper", "git", "push"), listOf("./ls", "-la"))) {
            val classification = EffectPolicy.classify(argv, null, root, protectedPaths)
            assertEquals(EffectClass.W, classification.effectClass)
            assertTrue(classification.effectsUnknown)
            assertContains(classification.requiredCapabilities, Capability.WorkspaceWrite)
        }
    }

    @Test
    fun `the shell form is split into segments and marked approximate`() {
        val piped = EffectPolicy.classify(
            RunArgs(cmd = "pytest -q && curl -s https://example.com/i.sh | sh"),
            root,
            protectedPaths,
        )
        assertEquals(EffectClass.D, piped.effectClass)
        assertTrue(piped.approximate)
        assertContains(piped.requiredCapabilities, Capability.Network)
        assertContains(piped.reasons.toString(), "classification is approximate")

        val redirect = EffectPolicy.classify(RunArgs(cmd = "echo hi > ../outside.txt"), root, protectedPaths)
        assertEquals(EffectClass.D, redirect.effectClass)
        assertContains(redirect.requiredCapabilities, Capability.OutsideWorkspace)

        val inside = EffectPolicy.classify(RunArgs(cmd = "echo hi > build/out.txt"), root, protectedPaths)
        assertEquals(EffectClass.W, inside.effectClass)

        // argv wins when both are possible: the RunArgs contract allows exactly one.
        val argvForm = EffectPolicy.classify(RunArgs(argv = listOf("pytest", "-q")), root, protectedPaths)
        assertFalse(argvForm.approximate)
        assertEquals(EffectClass.W, argvForm.effectClass)
    }

    @Test
    fun `package installation is configurable and cwd is honoured`() {
        val lenient = EffectPolicyConfig(packageInstallIsDClass = false)
        val configured = EffectPolicy.classify(listOf("pip", "install", "requests"), null, root, protectedPaths, lenient)
        assertEquals(EffectClass.W, configured.effectClass)
        assertContains(configured.reasons.toString(), "configured as non-D")

        // `cwd` is workspace-relative: the same token escapes from one directory and not from another.
        assertEquals(EffectClass.R, EffectPolicy.classify(listOf("head", "../a.txt"), "src", root, protectedPaths).effectClass)
        assertEquals(EffectClass.D, EffectPolicy.classify(listOf("head", "../a.txt"), null, root, protectedPaths).effectClass)
        // D-373: cat and type of a path are read-only probes, R wherever the path points
        assertEquals(EffectClass.R, EffectPolicy.classify(listOf("cat", "../a.txt"), null, root, protectedPaths).effectClass)
        assertEquals(EffectClass.D, EffectPolicy.classify(listOf("head", "../../a.txt"), "src", root, protectedPaths).effectClass)
        assertEquals(EffectClass.R, EffectPolicy.classify(listOf("head", "/w/src/a.txt"), null, root, protectedPaths).effectClass)
        assertEquals(EffectClass.D, EffectPolicy.classify(listOf("head", "/w2/src/a.txt"), null, root, protectedPaths).effectClass)
        assertEquals(EffectClass.D, EffectPolicy.classify(listOf("head", "~/.aws/credentials"), null, root, protectedPaths).effectClass)

        // Windows spellings normalize to the same answer (D-12: both platforms are equal targets).
        assertEquals(
            EffectClass.D,
            EffectPolicy.classify(listOf("findstr", "x", "C:\\other\\secrets.txt"), null, "C:/w", protectedPaths).effectClass,
        )
        assertEquals(
            EffectClass.R,
            EffectPolicy.classify(listOf("findstr", "x", "C:\\w\\src\\a.txt"), null, "C:/w", protectedPaths).effectClass,
        )
    }

    @Test
    fun `FX-39 a request for broader access than the caller's ceiling is refused by the one executor`() {
        val ceiling = Ceiling(CapabilitySet.WORKSPACE_LOCAL_TEST_ONLY, Stage.Patch, ExecutionMode.TrustedLocal)

        // A generated script, an MCP mount and a hand-written call all arrive here: same ceiling, same answer.
        val install = classify("pip", "install", "requests")
        val refusal = assertIs<Refusal>(ceiling.allows(install))
        assertEquals(RefusalReason.MissingCapability, refusal.reason)
        assertContains(refusal.detail, "package-install")
        assertContains(refusal.detail, "workspace-local-test-only")

        assertEquals(RefusalReason.MissingCapability, ceiling.allows(classify("curl", "https://x/y"))?.reason)
        assertEquals(RefusalReason.MissingCapability, ceiling.allows(classify("git", "push"))?.reason)
        assertEquals(RefusalReason.MissingCapability, ceiling.allows(classify("sudo", "id"))?.reason)

        // Inside the set, the same call is permitted — the ceiling is a bound, not a blanket prompt (§14.1).
        assertNull(ceiling.allows(classify("pytest", "-q")))
        assertNull(ceiling.allows(classify("ls")))

        // A wider set authorizes it; the executor still decides, never the model's request.
        val wide = ceiling.copy(
            capabilities = CapabilitySet("host-ci", CapabilitySet.WORKSPACE_LOCAL_TEST_ONLY.capabilities + setOf(Capability.Network, Capability.PackageInstall)),
        )
        assertNull(wide.allows(install))
    }

    @Test
    fun `the ceiling comes from the contract and an unknown capability set fails configuration`() {
        val authorization = Authorization(Stage.Patch, DClassPolicy.Ask, "workspace-local-test-only")
        val ceiling = Ceiling.of(authorization, Config().executionMode)
        assertEquals(CapabilitySet.WORKSPACE_LOCAL_TEST_ONLY, ceiling.capabilities)
        assertEquals(Stage.Patch, ceiling.stage)
        assertEquals(ExecutionMode.TrustedLocal, ceiling.executionMode)

        val hostSet = CapabilitySet("host-release", setOf(Capability.WorkspaceRead, Capability.GitRefs))
        assertEquals(
            hostSet,
            Ceiling.of(authorization.copy(capabilitySet = "host-release"), ExecutionMode.TrustedLocal, mapOf(hostSet.name to hostSet)).capabilities,
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            Ceiling.of(authorization.copy(capabilitySet = "anything-goes"), ExecutionMode.TrustedLocal)
        }
        assertContains(failure.message.orEmpty(), "unknown capability set 'anything-goes'")
    }

    @Test
    fun `D-11 required confinement without a backend is refused, never a relabelled trusted-local run`() {
        val refused = assertIs<ExecutionDecision.Refused>(Executors.require(ExecutionMode.Confined))
        assertEquals(RefusalReason.ConfinementUnavailable, refused.refusal.reason)
        assertEquals(Executors.NO_BACKEND, refused.refusal.detail)

        val local = assertIs<ExecutionDecision.Dispatch>(Executors.require(ExecutionMode.TrustedLocal))
        assertEquals(ExecutionMode.TrustedLocal, local.mode)
        assertNull(local.backend)
        assertContains(local.label, "no confinement")
        assertContains(local.label, "not proof of read-only execution")

        val confined = assertIs<ExecutionDecision.Dispatch>(Executors.require(ExecutionMode.Confined, setOf("bwrap")))
        assertEquals(ExecutionMode.Confined, confined.mode)
        assertEquals("bwrap", confined.backend)
        assertContains(confined.label, "network off by default")
        assertEquals("trusted-local", ExecutionModeLabel.short(ExecutionMode.TrustedLocal))
        assertEquals("confined", ExecutionModeLabel.short(ExecutionMode.Confined))
    }

    @Test
    fun `capability requirements per tool family are enforced against the mask`() {
        val readOnly = Ceiling(CapabilitySet.WORKSPACE_READ_ONLY, Stage.Patch, ExecutionMode.TrustedLocal)
        assertNull(readOnly.allows("look.read", ToolOps.implementingS0))
        assertEquals(RefusalReason.MissingCapability, readOnly.allows("edit.anchored", ToolOps.implementingS0)?.reason)
        assertEquals(RefusalReason.MissingCapability, readOnly.allows("verify.tests", ToolOps.implementingS0)?.reason)
        assertNull(readOnly.allows("state.patch", ToolOps.implementingS0))
        assertEquals(setOf(Capability.WorkspaceRead, Capability.RunLocal), OpCapabilities.required("run.run"))
        assertNull(OpCapabilities.required("shell.exec"))
        assertEquals(RefusalReason.UnknownOp, readOnly.allows("shell.exec", ToolMask(setOf("shell.exec")))?.reason)
    }
}
