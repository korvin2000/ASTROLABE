package io.astrolabe.os

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS)
class WindowsHandleInheritanceTest {
    @Test
    fun `a child inherits its standard streams but no unrelated inheritable event`(@TempDir root: Path) {
        Arena.ofConfined().use { arena ->
            val kernel = SymbolLookup.libraryLookup("kernel32", arena)
            val linker = Linker.nativeLinker()
            fun bind(name: String, descriptor: FunctionDescriptor) = linker.downcallHandle(kernel.find(name).orElseThrow(), descriptor)
            val create = bind("CreateEventW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS))
            val inherit = bind("SetHandleInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
            val wait = bind("WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
            val close = bind("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS))
            val event = create.invokeWithArguments(MemorySegment.NULL, 1, 0, MemorySegment.NULL) as MemorySegment
            assertTrue(event.address() != 0L)
            try {
                assertEquals(1, inherit.invokeWithArguments(event, 1, 1), "make the sentinel handle inheritable")
                val source = root.resolve("HandleProbe.java")
                Files.writeString(source, """
                    import java.lang.foreign.*;
                    public class HandleProbe {
                        public static void main(String[] args) throws Throwable {
                            var kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
                            var signal = Linker.nativeLinker().downcallHandle(kernel.find("SetEvent").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                            signal.invokeWithArguments(MemorySegment.ofAddress(Long.parseLong(args[0])));
                            System.out.println("stdout available");
                            System.err.println("stderr available");
                        }
                    }
                """.trimIndent())
                val java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
                val log = root.resolve("child.log")
                WindowsOwner().start(OwnedStart(Command.Argv(listOf(java, "--enable-native-access=ALL-UNNAMED", source.toString(), event.address().toString())),
                    root, System.getenv(), log)).use { child ->
                    assertTrue(child.awaitExit(60_000), "the probe must exit")
                    assertEquals(0, child.exitCode(), Files.readString(log))
                }
                assertEquals(258, wait.invokeWithArguments(event, 0), "the child must not be able to signal the host's inheritable event")
                val output = Files.readString(log)
                assertTrue("stdout available" in output && "stderr available" in output, output)
            } finally {
                close.invokeWithArguments(event)
            }
        }
    }
}
