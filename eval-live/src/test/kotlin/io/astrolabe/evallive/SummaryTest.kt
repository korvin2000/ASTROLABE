package io.astrolabe.evallive

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** P8.C.17: `summary.csv` holds every run of its results directory, so a second arm or model never overwrites the first's rows. */
class SummaryTest {
    @TempDir
    lateinit var out: Path

    private fun result(task: String, model: String, arm: String, repeat: Int, order: Int, mode: String = HostMode.Auto.wire) = RunResult(
        task = task, taskClass = "bugfix", provider = "fake", model = model, repeat = repeat, order = order, seed = 0, harnessVersion = "0",
        effort = "Medium", maxCells = 1, profileId = null, contextLimitTokens = null, outputHeadroomTokens = null, workId = null,
        attemptFingerprint = null, shape = null, verification = null, outcome = "completed", stopCode = null, reason = null, failure = null,
        cells = null, policyDecisions = emptyList(), acceptance = null, acceptanceDigest = "d", startedAt = "s", endedAt = "e",
        attemptWallMillis = null, totals = null, eventsDropped = null, changedFiles = null, arm = arm, mode = mode,
    )

    private fun keep(dir: Path, result: RunResult) {
        Files.createDirectories(dir)
        dir.resolve("result.json").writeText(Summary.encode(result))
    }

    private fun rows(): List<List<String>> = out.resolve("summary.csv").readLines().filter { it.isNotBlank() }.drop(1).map { it.split(",") }

    @Test
    fun `a second arm and model add their rows to the first bench's summary`() {
        val first = listOf(result("t1", "m1", "default", 1, 1), result("t2", "m1", "default", 1, 2))
        first.forEach { keep(out.resolve("runs/${it.arm}/${it.task}/${it.model}/r${it.repeat}"), it) }
        Summary.write(out, first)
        assertEquals(2, rows().size)

        // The second bench: another arm and another model in the same --out, and a set-aside result that is not a run.
        val second = listOf(result("t1", "m2", "loop", 1, 1), result("t1", "m2", "loop", 2, 2))
        second.forEach { keep(out.resolve("runs/${it.arm}/${it.task}/${it.model}/r${it.repeat}"), it) }
        keep(out.resolve("runs/loop/t1/m2/r1.stale-1"), result("t1", "m2", "loop", 1, 9))
        Summary.write(out, second)

        val col = Summary.COLUMNS.withIndex().associate { (i, name) -> name to i }
        val keys = rows().map { r -> listOf(r[col.getValue("task")], r[col.getValue("model")], r[col.getValue("arm")], r[col.getValue("mode")], r[col.getValue("repeat")]) }
        assertEquals(
            listOf(
                listOf("t1", "m1", "default", "auto", "1"), listOf("t2", "m1", "default", "auto", "1"),
                listOf("t1", "m2", "loop", "auto", "1"), listOf("t1", "m2", "loop", "auto", "2"),
            ),
            keys,
        )
        assertEquals(4, Summary.merged(out, second).size, "summary.json holds the same runs")

        // The bench's own result wins its key over a kept one.
        val rerun = result("t1", "m1", "default", 1, 1).copy(outcome = "waiting_for_input")
        Summary.write(out, listOf(rerun))
        assertEquals(4, rows().size)
        assertEquals("waiting_for_input", rows().first()[col.getValue("outcome")])
    }
}
