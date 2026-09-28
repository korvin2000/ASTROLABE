package io.astrolabe.os;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Internal, dependency-free child JVM. Never installs a subreaper or SIGCHLD policy in the host JVM. */
final class LinuxSubreaper {
    static final int STARTED = 0x41535431;
    static final int ROOT_EXIT = 0x41535432;
    static final int FINISHED = 0x41535433;
    private static final int WNOHANG_ALL = 1 | 0x40000000; // WNOHANG | __WALL (including clone children)
    private static final int ECHILD = 10;
    private static final int EINTR = 4;
    private static final int ESRCH = 3;
    private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final AtomicBoolean stop = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final DataOutputStream replies = new DataOutputStream(new FileOutputStream(FileDescriptor.out));
    private boolean peerGone;
    private int root;
    private Integer rootExit;

    public static void main(String[] ignored) {
        var supervisor = new LinuxSubreaper();
        int result = 1;
        try {
            supervisor.run();
            result = 0;
        } catch (Throwable failure) {
            System.err.println("ASTROLABE Linux supervisor: " + failure);
        } finally {
            supervisor.finished.countDown();
        }
        System.exit(result);
    }

    private void run() throws Throwable {
        try (var arena = Arena.ofConfined()) {
            var capture = arena.allocate(Native.CAPTURE);
            checked("setsid", Native.setsid, capture);
            checked("PR_SET_CHILD_SUBREAPER", Native.prctl, capture, 36, 1L, 0L, 0L, 0L);
            var enabled = arena.allocate(ValueLayout.JAVA_INT);
            checked("PR_GET_CHILD_SUBREAPER", Native.prctl, capture, 37, enabled.address(), 0L, 0L, 0L);
            if (enabled.get(ValueLayout.JAVA_INT, 0) != 1) throw new IOException("kernel did not enable subreaping");
            // This isolated JVM owns every wait. SIG_IGN/SA_NOCLDWAIT would invalidate the ECHILD proof.
            var previous = (MemorySegment) Native.signal.invokeWithArguments(capture, 17, MemorySegment.NULL);
            if (previous.address() == -1) throw new IOException("cannot reset SIGCHLD");
            // Refuse unsupported procfs before any target can run.
            children();

            var input = new DataInputStream(System.in);
            String log = string(input);
            var argv = strings(input);
            var environment = strings(input);
            if (argv.isEmpty()) throw new IOException("empty command");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                stop.set(true);
                try { finished.await(12, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "astrolabe-linux-shutdown"));
            root = spawn(arena, capture, log, argv, environment);
            // EOF also covers host JVM death. The target's file actions replace all three stdio FDs.
            Thread.ofPlatform().daemon().name("astrolabe-linux-control").start(() -> {
                try { input.read(); } catch (IOException ignored) { }
                stop.set(true);
            });
            emit(STARTED, null);
            supervise(arena, capture);
        }
    }

    private void supervise(Arena arena, MemorySegment capture) throws Throwable {
        var status = arena.allocate(ValueLayout.JAVA_INT);
        long cleanupAt = 0;
        while (true) {
            if (stop.get() || rootExit != null) {
                if (cleanupAt == 0) cleanupAt = System.nanoTime();
                // No wait/reap occurs between collecting and signaling these direct children.
                // Their PIDs cannot be reused while this sole parent still holds them unreaped.
                for (int pid : children()) {
                    int killed = call(Native.kill, capture, pid, 9);
                    if (killed != 0 && errno(capture) != ESRCH) throw new IOException("kill child " + pid + ": errno=" + errno(capture));
                }
            }
            for (int reaped = 0; reaped < 256; reaped++) {
                int pid = call(Native.waitpid, capture, -1, status, WNOHANG_ALL);
                if (pid > 0) {
                    if (pid == root) {
                        int value = status.get(ValueLayout.JAVA_INT, 0);
                        rootExit = (value & 0x7f) == 0 ? (value >> 8) & 0xff : 128 + (value & 0x7f);
                        emit(ROOT_EXIT, rootExit);
                        break; // Start descendant cleanup immediately, even under a stream of exiting children.
                    }
                    continue;
                }
                if (pid == 0) break;
                int error = errno(capture);
                if (error == EINTR) continue;
                if (error == ECHILD && rootExit != null) {
                    // Ancestry, including detached orphans, ends only when the kernel has no children left.
                    emit(FINISHED, null);
                    return;
                }
                throw new IOException("waitpid: errno=" + error + ", root exit=" + rootExit);
            }
            if (cleanupAt != 0 && System.nanoTime() - cleanupAt >= CLEANUP_NANOS)
                throw new IOException("descendant cleanup deadline exceeded; effects unknown");
            Thread.sleep(10);
        }
    }

    private static List<Integer> children() throws IOException {
        var result = new LinkedHashSet<Integer>();
        try (var tasks = Files.newDirectoryStream(Path.of("/proc/self/task"))) {
            for (Path task : tasks) {
                String text;
                try { text = Files.readString(task.resolve("children")).trim(); }
                catch (NoSuchFileException vanished) {
                    if (Files.exists(task)) throw vanished; // procfs lacks the required children interface
                    continue;
                }
                if (!text.isEmpty()) for (String pid : text.split("\\s+")) result.add(Integer.parseInt(pid));
            }
        }
        return List.copyOf(result);
    }

    private int spawn(Arena arena, MemorySegment capture, String log, List<String> argv, List<String> environment) throws Throwable {
        var actions = arena.allocate(256, 16); // glibc file-actions struct, same supported ABI as the previous owner
        checkedCode("file_actions_init", call(Native.actionsInit, actions));
        try {
            checkedCode("stdin", call(Native.addOpen, actions, 0, arena.allocateFrom("/dev/null"), 0, 0));
            checkedCode("stdout", call(Native.addOpen, actions, 1, arena.allocateFrom(log), 0x441, 0644));
            checkedCode("stderr", call(Native.addDup2, actions, 1, 2));
            var attributes = arena.allocate(512, 16);
            checkedCode("spawnattr_init", call(Native.attrInit, attributes));
            try {
                // Target group signals (including kill(0, SIGKILL)) must never kill its owner.
                checkedCode("POSIX_SPAWN_SETSID", call(Native.attrFlags, attributes, (short) 0x80));
                var pid = arena.allocate(ValueLayout.JAVA_INT);
                // posix_spawnp searches its caller's environment, not envp. Resolve against the requested PATH.
                String executable = executable(argv.getFirst(), environment);
                checkedCode("posix_spawn", call(Native.spawn, pid, arena.allocateFrom(executable), actions,
                    attributes, vector(arena, argv), vector(arena, environment)));
                return pid.get(ValueLayout.JAVA_INT, 0);
            } finally { call(Native.attrDestroy, attributes); }
        } finally { call(Native.actionsDestroy, actions); }
    }

    private static String executable(String name, List<String> environment) throws IOException {
        if (name.contains("/")) return name;
        String path = environment.stream().filter(v -> v.startsWith("PATH=")).findFirst().orElse("PATH=/bin:/usr/bin").substring(5);
        for (String directory : path.split(":", -1)) {
            Path file = Path.of(directory.isEmpty() ? "." : directory).resolve(name);
            if (Files.isRegularFile(file) && Files.isExecutable(file)) return file.toAbsolutePath().toString();
        }
        throw new IOException("executable not found in requested PATH: " + name);
    }

    private void emit(int message, Integer value) {
        if (peerGone) return;
        try {
            replies.writeInt(message);
            if (value != null) replies.writeInt(value);
            replies.flush();
        } catch (IOException closed) { peerGone = true; stop.set(true); }
    }

    private static String string(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) throw new IOException("invalid launch string length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("incomplete launch request");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<String> strings(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 65_536) throw new IOException("invalid launch vector size");
        var values = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) values.add(string(input));
        return values;
    }

    private static MemorySegment vector(Arena arena, List<String> strings) {
        var result = arena.allocate(ValueLayout.ADDRESS, strings.size() + 1L);
        for (int i = 0; i < strings.size(); i++) result.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(strings.get(i)));
        return result;
    }

    private static int errno(MemorySegment capture) { return capture.get(ValueLayout.JAVA_INT, Native.ERRNO); }
    private static int call(MethodHandle handle, Object... arguments) throws Throwable { return (int) handle.invokeWithArguments(arguments); }
    private static void checked(String name, MethodHandle handle, MemorySegment capture, Object... args) throws Throwable {
        var all = new ArrayList<Object>(); all.add(capture); all.addAll(List.of(args));
        if ((int) handle.invokeWithArguments(all) < 0) throw new IOException(name + ": errno=" + errno(capture));
    }
    private static void checkedCode(String name, int code) throws IOException {
        if (code != 0) throw new IOException(name + ": errno=" + code);
    }

    private static final class Native {
        static final Linker LINKER = Linker.nativeLinker();
        static final SymbolLookup LIBC = LINKER.defaultLookup();
        static final MemoryLayout CAPTURE = Linker.Option.captureStateLayout();
        static final long ERRNO = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
        static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
        static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
        static final MethodHandle setsid = bind("setsid", FunctionDescriptor.of(I), true);
        static final MethodHandle prctl = bind("prctl", FunctionDescriptor.of(I, I, L, L, L, L), true);
        static final MethodHandle signal = bind("signal", FunctionDescriptor.of(ValueLayout.ADDRESS, I, ValueLayout.ADDRESS), true);
        static final MethodHandle kill = bind("kill", FunctionDescriptor.of(I, I, I), true);
        static final MethodHandle waitpid = bind("waitpid", FunctionDescriptor.of(I, I, ValueLayout.ADDRESS, I), true);
        static final MethodHandle spawn = bind("posix_spawn", FunctionDescriptor.of(I, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), false);
        static final MethodHandle actionsInit = bind("posix_spawn_file_actions_init", FunctionDescriptor.of(I, ValueLayout.ADDRESS), false);
        static final MethodHandle actionsDestroy = bind("posix_spawn_file_actions_destroy", FunctionDescriptor.of(I, ValueLayout.ADDRESS), false);
        static final MethodHandle attrInit = bind("posix_spawnattr_init", FunctionDescriptor.of(I, ValueLayout.ADDRESS), false);
        static final MethodHandle attrDestroy = bind("posix_spawnattr_destroy", FunctionDescriptor.of(I, ValueLayout.ADDRESS), false);
        static final MethodHandle attrFlags = bind("posix_spawnattr_setflags", FunctionDescriptor.of(I, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT), false);
        static final MethodHandle addOpen = bind("posix_spawn_file_actions_addopen", FunctionDescriptor.of(I,
            ValueLayout.ADDRESS, I, ValueLayout.ADDRESS, I, I), false);
        static final MethodHandle addDup2 = bind("posix_spawn_file_actions_adddup2", FunctionDescriptor.of(I, ValueLayout.ADDRESS, I, I), false);
        private static MethodHandle bind(String name, FunctionDescriptor descriptor, boolean capture) {
            return LINKER.downcallHandle(LIBC.find(name).orElseThrow(), descriptor,
                capture ? new Linker.Option[] { Linker.Option.captureCallState("errno") } : new Linker.Option[0]);
        }
    }
}
