package io.astrolabe.os

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * The NTFS `ChangeTime` of a file (`GetFileInformationByHandleEx(FileBasicInfo)`), in nanoseconds since the epoch. Like a
 * POSIX `ctime` it moves on every write and on every metadata change, restoring the modification time included, and no
 * ordinary tool sets it back — so an unchanged one proves a file unwritten where the modification time cannot (WD-02,
 * D-374). `null` off Windows, on a file system without one (FAT reports 0) and when the file cannot be opened for its
 * attributes; a caller then re-reads the bytes instead.
 */
internal object WindowsChangeTime {
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private const val FILE_READ_ATTRIBUTES = 0x80
    private const val FILE_SHARE_ALL = 0x7
    private const val OPEN_EXISTING = 3
    private const val FILE_FLAG_BACKUP_SEMANTICS = 0x0200_0000
    private const val FILE_FLAG_OPEN_REPARSE_POINT = 0x0020_0000
    private const val FILE_BASIC_INFO_CLASS = 0
    private const val FILE_BASIC_INFO_BYTES = 40L
    private const val CHANGE_TIME_OFFSET = 24L
    private const val INVALID_HANDLE = -1L

    /** 100 ns ticks between 1601-01-01 and 1970-01-01. */
    private const val EPOCH_TICKS = 116_444_736_000_000_000L

    private class Calls(val createFile: MethodHandle, val basicInfo: MethodHandle, val close: MethodHandle)

    private val calls: Calls? by lazy {
        if (!windows) return@lazy null
        runCatching {
            val linker = Linker.nativeLinker()
            val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
            fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(kernel32.find(name).orElseThrow(), descriptor)
            Calls(
                bind("CreateFileW", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                bind("GetFileInformationByHandleEx", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                bind("CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
            )
        }.getOrNull()
    }

    fun of(file: Path): Long? {
        val bound = calls ?: return null
        return try {
            Arena.ofConfined().use { arena ->
                val absolute = file.toAbsolutePath().toString()
                val spelled = if (absolute.length > 2 && absolute[1] == ':' && absolute[2] == '\\') "\\\\?\\$absolute" else absolute
                val handle = bound.createFile.invokeWithArguments(
                    arena.allocateFrom(spelled, StandardCharsets.UTF_16LE), FILE_READ_ATTRIBUTES, FILE_SHARE_ALL, MemorySegment.NULL,
                    OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS or FILE_FLAG_OPEN_REPARSE_POINT, MemorySegment.NULL,
                ) as MemorySegment
                if (handle.address() == INVALID_HANDLE || handle.address() == 0L) return null
                try {
                    val info = arena.allocate(FILE_BASIC_INFO_BYTES, 8)
                    val ok = bound.basicInfo.invokeWithArguments(handle, FILE_BASIC_INFO_CLASS, info, FILE_BASIC_INFO_BYTES.toInt()) as Int
                    val ticks = info.get(ValueLayout.JAVA_LONG, CHANGE_TIME_OFFSET)
                    if (ok == 0 || ticks <= 0L) null else (ticks - EPOCH_TICKS) * 100L
                } finally {
                    bound.close.invokeWithArguments(handle)
                }
            }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            null
        }
    }
}
