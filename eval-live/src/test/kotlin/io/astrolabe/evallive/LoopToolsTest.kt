package io.astrolabe.evallive

import io.astrolabe.Defaults
import io.astrolabe.provider.ToolCall
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP-B5 review: the loop's tools hold the core's permissions — the run tool's containment probe and the path contract. */
class LoopToolsTest {
    @TempDir
    lateinit var dir: Path

    private val workspace: Path by lazy { Files.createDirectories(dir.resolve("workspace")) }
    private val tools: LoopTools by lazy { LoopTools(workspace, Defaults(), emptyList(), System.getProperty("os.name")) }

    private fun call(name: String, args: String) = tools.execute(ToolCall("c1", name, args))

    @Test
    fun `a delete inside the workspace is allowed as the core allows it, one outside is refused`() {
        workspace.resolve("old.txt").writeText("old\n")
        dir.resolve("outside.txt").writeText("keep\n")

        val inside = call("shell", """{"command":"rm old.txt"}""")
        val outside = call("shell", """{"command":"rm ../outside.txt"}""")

        assertNull(inside.refused, "a contained delete is W-class, as for the core's run tool: ${inside.text}")
        assertNotNull(outside.refused, "a delete outside the workspace is D-class: ${outside.text}")
        assertTrue(outside.error && outside.text.startsWith("refused"), outside.text)
        assertEquals("keep\n", dir.resolve("outside.txt").readText())
    }

    @Test
    fun `file tools never reach outside the workspace through a link nor into git in any case`() {
        Files.createDirectories(workspace.resolve(".git"))
        workspace.resolve(".git").resolve("config").writeText("[core] secret\n")
        dir.resolve("outside.txt").writeText("outside secret\n")

        for (path in listOf(".git/config", ".GIT/config", "../outside.txt")) {
            val read = call("read", """{"path":"$path"}""")
            assertTrue(read.error && "secret" !in read.text, "$path: ${read.text}")
        }
        val write = call("write", """{"path":".Git/hooks/pre-commit","content":"x"}""")
        assertTrue(write.error, write.text)
        assertTrue(Files.notExists(workspace.resolve(".git").resolve("hooks")))
        assertTrue(".git" !in call("read", """{"path":"."}""").text.lowercase(), "the listing hides .git")

        val linked = runCatching { Files.createSymbolicLink(workspace.resolve("out"), dir) }.isSuccess
        assumeTrue(linked, "symbolic links need a privilege on this machine")
        val throughLink = call("read", """{"path":"out/outside.txt"}""")
        assertTrue(throughLink.error && "secret" !in throughLink.text, throughLink.text)
        val writeThrough = call("write", """{"path":"out/new.txt","content":"x"}""")
        assertTrue(writeThrough.error, writeThrough.text)
        assertTrue(Files.notExists(dir.resolve("new.txt")))
    }
}
