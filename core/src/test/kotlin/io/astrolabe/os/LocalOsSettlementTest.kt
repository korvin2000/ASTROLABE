package io.astrolabe.os

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Terminal records must describe the whole owned launch, including descendants. */
class LocalOsSettlementTest {

    @Test
    fun `root exit cannot publish a terminal sidecar while descendant cleanup is blocked`(@TempDir directory: Path) {
        val child = GatedProcess()
        LocalOs(owner = FakeOwner(child)).use { os ->
            val proc = os.spawn(spec(directory))
            child.rootExited.countDown()
            try {
                assertTrue(child.cleanupStarted.await(10, TimeUnit.SECONDS), "supervisor never started cleanup")

                val persisted = Json.decodeFromString(Proc.serializer(), Files.readString(proc.sidecarPath))
                assertEquals(ProcStatus.Running, persisted.status, "descendants can still write while cleanup is blocked")

                Files.writeString(proc.log, "last descendant output\n")
            } finally {
                child.cleanupAllowed.countDown()
            }

            assertEquals(ProcStatus.Exited(0), os.awaitTerminal(proc).status)
            val finalPoll = os.poll(proc, 0, 0)
            assertEquals(ProcStatus.Exited(0), finalPoll.status)
            assertEquals("last descendant output\n", finalPoll.text())
        }
    }

    @Test
    fun `failed descendant cleanup cannot be reported as successful root exit`(@TempDir directory: Path) {
        val child = GatedProcess(failCleanup = true)
        child.cleanupAllowed.countDown()
        LocalOs(owner = FakeOwner(child)).use { os ->
            val proc = os.spawn(spec(directory))
            child.rootExited.countDown()

            assertEquals(ProcStatus.Lost, os.awaitTerminal(proc).status)
        }
    }

    @Test
    fun `accepted termination cannot publish terminal status until the owned tree exit is confirmed`(@TempDir directory: Path) {
        val child = GatedProcess(blockConfirmation = true)
        child.cleanupAllowed.countDown()
        LocalOs(owner = FakeOwner(child)).use { os ->
            val proc = os.spawn(spec(directory))
            child.rootExited.countDown()
            try {
                assertTrue(child.confirmationStarted.await(10, TimeUnit.SECONDS), "supervisor never waited for tree exit")

                val persisted = Json.decodeFromString(Proc.serializer(), Files.readString(proc.sidecarPath))
                assertEquals(ProcStatus.Running, persisted.status, "a successful kill request does not confirm tree exit")
            } finally {
                child.confirmationAllowed.countDown()
            }

            assertEquals(ProcStatus.Exited(0), os.awaitTerminal(proc).status)
        }
    }

    @Test
    fun `unconfirmed descendant exit cannot be reported as successful root exit`(@TempDir directory: Path) {
        val child = GatedProcess(confirmTreeExit = false)
        child.cleanupAllowed.countDown()
        LocalOs(owner = FakeOwner(child)).use { os ->
            val proc = os.spawn(spec(directory))
            child.rootExited.countDown()

            assertEquals(ProcStatus.Lost, os.awaitTerminal(proc).status)
        }
    }

    private fun spec(directory: Path): SpawnSpec = SpawnSpec(
        command = Command.Argv(listOf("test-child")),
        workingDirectory = directory,
        logPath = directory.resolve("child.log"),
    )

    private class FakeOwner(private val child: OwnedProcess) : ProcessOwner {
        override val essentialEnvironmentNames: Set<String> = emptySet()
        override fun start(start: OwnedStart): OwnedProcess = child
        override fun terminateUnowned(pid: Long): Boolean = false
    }

    private class GatedProcess(
        private val failCleanup: Boolean = false,
        blockConfirmation: Boolean = false,
        private val confirmTreeExit: Boolean = true,
    ) : OwnedProcess {
        override val pid: Long = 123_456_789L
        override val startEpochMillis: Long = 1L
        val rootExited = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val cleanupAllowed = CountDownLatch(1)
        val confirmationStarted = CountDownLatch(1)
        val confirmationAllowed = CountDownLatch(if (blockConfirmation) 1 else 0)

        override fun awaitExit(timeoutMillis: Long): Boolean = rootExited.await(timeoutMillis, TimeUnit.MILLISECONDS)
        override fun exitCode(): Int = 0

        override fun terminateTree() {
            cleanupStarted.countDown()
            check(cleanupAllowed.await(10, TimeUnit.SECONDS)) { "test did not release descendant cleanup" }
            if (failCleanup) throw OsFailure("terminateTree", 1, "descendants could not be terminated")
            rootExited.countDown()
        }

        override fun awaitTreeExit(timeoutMillis: Long): Boolean {
            confirmationStarted.countDown()
            return confirmationAllowed.await(timeoutMillis, TimeUnit.MILLISECONDS) && confirmTreeExit
        }

        override fun close(): Unit = Unit
    }
}
