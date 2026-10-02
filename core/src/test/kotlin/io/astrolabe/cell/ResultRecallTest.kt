package io.astrolabe.cell

import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.runCmd
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.InMemoryObservations
import io.astrolabe.evidence.Observation
import io.astrolabe.evidence.Observations
import io.astrolabe.os.Command
import io.astrolabe.os.IdentityKey
import io.astrolabe.os.Os
import io.astrolabe.os.OwnerToken
import io.astrolabe.os.Poll
import io.astrolabe.os.Proc
import io.astrolabe.os.ProcStatus
import io.astrolabe.provider.ToolCall
import io.astrolabe.store.BlobKind
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolExecutor
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.run.Handle
import io.astrolabe.tool.run.SqliteHandles
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResultRecallTest {
    @TempDir
    lateinit var stateRoot: Path

    private suspend fun execute(f: CellFixture, executor: ToolExecutor, native: ToolCall) = executor.execute(
        (ToolCalls.parse(listOf(native)) as ParsedCalls.Valid).calls.single(),
        TurnContext(1, f.workset.snapshot(), Reservations(Tokens(100_000))),
    )

    @Test
    fun `foreground run result alias resolves its observation and recalls the captured log`() = runTest {
        CellFixture(stateRoot).use { f ->
            val result = execute(f, f.run, runCmd("run", "recallable-run-output"))
            val alias = f.aliases.resolve(f.ids.work, Aliases.parse(result.resultAlias!!)!!)!!
            val observation = assertNotNull(f.observations.get(alias.canonicalId))
            assertEquals(result.header!!.runtime.actionId, observation.actionId)

            val recalled = execute(f, f.look, call("recall", "look", """{"what":"recall","id":"${result.resultAlias}"}"""))
            assertEquals("ok", recalled.header!!.runtime.status)
            assertTrue(recalled.body.contains("recallable-run-output"), recalled.body)
        }
    }

    @Test
    fun `edit result alias recalls its report and still identifies the edit to revert`() = runTest {
        CellFixture(stateRoot).use { f ->
            execute(f, f.look, read("read", "src/a.py"))
            val result = execute(f, f.edit, anchored("edit", "src/a.py", f.version("src/a.py"), "    return 1", "    return 10"))
            assertTrue(result.applied, result.body)
            val alias = f.aliases.resolve(f.ids.work, Aliases.parse(result.resultAlias!!)!!)!!
            assertEquals("edit", alias.kind)
            assertTrue(f.preimages.of(alias.canonicalId).isNotEmpty())
            assertNotNull(f.observations.get(alias.canonicalId))

            val recalled = execute(f, f.look, call("recall", "look", """{"what":"recall","id":"${result.resultAlias}"}"""))
            assertEquals("ok", recalled.header!!.runtime.status)
            assertTrue(recalled.body.contains("return 10"), recalled.body)
            val reverted = execute(f, f.edit, call("undo", "edit", """{"ops":[{"revert":"${result.resultAlias}"}],"why":"undo fixture edit"}"""))
            assertTrue(reverted.applied, reverted.body)
            assertEquals(CellFixture.A_PY, Files.readString(f.repo.resolve("src/a.py")))
        }
    }

    @Test
    fun `successive polls under one run alias recall the latest observation and preserve earlier rows`() = runTest {
        var polls = 0
        CellFixture(stateRoot, osOverride = { local ->
            object : Os by local {
                override fun reattach(proc: Proc): Proc = proc
                override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
                    // Whole lines in a readable log: a running poll hands over complete lines and redacts a slice with the
                    // log before it; an unreadable log hides the slice (D-390).
                    val bytes = "poll-result-${++polls}\n".toByteArray()
                    Files.write(proc.log, bytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
                    return Poll(bytes, sinceCursorBytes + bytes.size, ProcStatus.Running, false)
                }
            }
        }).use { f ->
            val alias = f.aliases.allocate(f.ids.work, "retained-action", "result", f.ids.context, f.workspace.id)
            val proc = Proc(42, 1000, IdentityKey(42, 1000), OwnerToken.random(), stateRoot.resolve("retained.log").toString(), 0,
                ProcStatus.Running, Command.Argv(listOf("echo", "retained")), f.repo.root.toString())
            SqliteHandles(f.store, f.clock).save(Handle("retained-handle", f.ids, alias.canonicalId, alias.text,
                listOf("echo", "retained"), false, null, proc, "running", 0, f.stamper.report().candidateId.digest.hex))

            execute(f, f.run, call("poll1", "run", """{"op":"poll","handle":"retained-handle"}"""))
            val first = assertNotNull(f.observations.get(alias.canonicalId))
            execute(f, f.run, call("poll2", "run", """{"op":"poll","handle":"retained-handle"}"""))
            val latest = assertNotNull(f.observations.get(alias.canonicalId))
            assertEquals(first, f.observations.get(first.id), "looking up an observation ID retains that exact historical view")
            assertTrue(String(f.store.blobs.get(latest.contentRef)).contains("poll-result-2"))
            val recalled = execute(f, f.look, call("recall", "look", """{"what":"recall","id":"${alias.text}"}"""))
            assertEquals("ok", recalled.header!!.runtime.status)
            assertTrue(recalled.body.contains("poll-result-2"), recalled.body)
        }
    }

    @Test
    fun `both observation stores resolve the newest action view without changing historical observations`() {
        CellFixture(stateRoot).use { f ->
            for (observations in listOf<Observations>(InMemoryObservations(), f.observations)) {
                val first = Observation("poll-first", f.ids, "action", null, f.store.blobs.put("first".toByteArray(), BlobKind.OUTPUT, f.ids), emptyList(), emptyMap(), true, emptyMap(), true)
                val latest = first.copy(id = "poll-last", contentRef = f.store.blobs.put("last".toByteArray(), BlobKind.OUTPUT, f.ids))
                observations.record(first)
                observations.record(latest)
                assertEquals(latest, observations.get("action"))
                assertEquals(first, observations.get(first.id))
                assertEquals(latest, observations.get(latest.id))
            }
        }
    }
}
