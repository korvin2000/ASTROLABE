package io.astrolabe.os

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P0.6.1 resume semantics (`§13.4`): what a harness that did not launch a process may conclude
 * about it. A handle it cannot confirm is [ProcStatus.Lost] — never [ProcStatus.Running], and
 * never a reason to relaunch (IX-20, D-43).
 */
class ProcResolutionTest {

    private lateinit var directory: Path
    private lateinit var os: LocalOs
    private val logCounter = AtomicInteger()
    private val json = Json { encodeDefaults = true }

    @BeforeEach
    fun setUp(@TempDir temporary: Path) {
        directory = temporary
        os = LocalOs()
    }

    @AfterEach
    fun tearDown() {
        os.close()
    }

    @Test
    fun `a record with no terminal status from a dead harness resolves as lost`() {
        val proc = spawn(ChildCommands.print("done"))
        val settled = os.awaitTerminal(proc)
        assertEquals(ProcStatus.Exited(0), settled.status)

        // The harness died between launch and terminal record: a foreign token, still "running".
        val orphan = settled.copy(
            ownerToken = OwnerToken("harness-that-crashed"),
            status = ProcStatus.Running,
            finishedAtEpochMillis = null,
        )
        writeSidecar(orphan)

        assertEquals(ProcStatus.Lost, assertNotNull(os.resolve(orphan.sidecarPath)).status)
    }

    @Test
    fun `a recycled pid resolves as lost even though the pid is alive`() {
        val live = spawn(ChildCommands.sleep(120))
        assertNotNull(live.identityKey.startEpochMillis, "identity beyond the pid is required (§13.1)")

        // Same pid, but the recorded process started a minute earlier: this is a different process.
        val reused = live.copy(
            ownerToken = OwnerToken("harness-that-crashed"),
            identityKey = live.identityKey.copy(
                startEpochMillis = live.identityKey.startEpochMillis - 60_000L,
            ),
            logPath = directory.resolve("recycled.log").toString(),
            status = ProcStatus.Running,
        )
        writeSidecar(reused)

        assertTrue(ChildCommands.isAlive(reused.pid), "the pid must really be alive for this to prove anything")
        assertEquals(ProcStatus.Lost, assertNotNull(os.resolve(reused.sidecarPath)).status)
    }

    @Test
    fun `an unknown start time never resolves as running`() {
        val live = spawn(ChildCommands.sleep(120))
        val unidentified = live.copy(
            ownerToken = OwnerToken("harness-that-crashed"),
            identityKey = IdentityKey(live.pid, startEpochMillis = null),
            logPath = directory.resolve("unidentified.log").toString(),
            status = ProcStatus.Running,
        )
        writeSidecar(unidentified)

        assertEquals(ProcStatus.Lost, assertNotNull(os.resolve(unidentified.sidecarPath)).status)
    }

    @Test
    fun `a live pid whose identity matches resolves as running but unowned`() {
        val live = spawn(ChildCommands.sleep(120))
        val foreign = live.copy(
            ownerToken = OwnerToken("harness-that-crashed"),
            logPath = directory.resolve("unowned.log").toString(),
            status = ProcStatus.Running,
        )
        writeSidecar(foreign)

        val resolved = assertNotNull(os.resolve(foreign.sidecarPath))

        assertEquals(ProcStatus.Running, resolved.status)
        assertTrue(resolved.ownerToken != os.ownerToken, "the record stays attributed to its owner")

        // The exit code of an unowned process is unobtainable, so once it disappears the only
        // honest answer is `lost` — not a completed run, and not a reason to relaunch.
        os.terminate(live)
        assertTrue(ChildCommands.awaitPidGone(foreign.pid, 15_000L))
        assertEquals(ProcStatus.Lost, assertNotNull(os.resolve(foreign.sidecarPath)).status)
    }

    @Test
    fun `a terminal record written by another harness is trusted as it stands`() {
        val proc = spawn(ChildCommands.exitWith(3))
        val settled = os.awaitTerminal(proc)
        writeSidecar(settled.copy(ownerToken = OwnerToken("harness-that-crashed")))

        assertEquals(ProcStatus.Exited(3), assertNotNull(os.resolve(settled.sidecarPath)).status)
    }

    @Test
    fun `resolving a missing sidecar yields null rather than a guess`() {
        assertNull(os.resolve(directory.resolve("absent.log.proc.json")))
    }

    @Test
    fun `the sidecar lives next to the log and survives a round trip`() {
        val proc = spawn(ChildCommands.print("round-trip"))
        os.awaitTerminal(proc)

        assertEquals(
            directory.resolve("child-1.log.proc.json").toAbsolutePath().normalize(),
            proc.sidecarPath,
        )
        val reloaded = assertNotNull(os.resolve(proc.sidecarPath))
        assertEquals(proc.pid, reloaded.pid)
        assertEquals(proc.identityKey, reloaded.identityKey)
        assertEquals(proc.command, reloaded.command)
        assertEquals(proc.ownerToken, reloaded.ownerToken)
    }

    private fun writeSidecar(proc: Proc) {
        os.replaceFileAtomically(proc.sidecarPath, json.encodeToString(Proc.serializer(), proc).toByteArray())
    }

    private fun spawn(command: Command): Proc = os.spawn(
        SpawnSpec(
            command = command,
            workingDirectory = directory,
            logPath = directory.resolve("child-${logCounter.incrementAndGet()}.log"),
        ),
    )
}
