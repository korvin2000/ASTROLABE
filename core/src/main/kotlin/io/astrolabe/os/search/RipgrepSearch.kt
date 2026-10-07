package io.astrolabe.os.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * The ripgrep backend. The invocation is pinned and explicit; output is parsed once, at this
 * boundary, from the JSON event stream into [Hit] records.
 *
 * `rg` is given the candidate list [Candidates] built — not a directory — so that both backends see
 * exactly the same files. That also means `rg`'s own ignore, hidden-file and binary handling never
 * runs: with explicit file arguments `rg` searches hidden files and reports matches from binary
 * files before their first NUL (verified against rg 15.2.0), so those rules are enforced once, in
 * [Candidates], for both backends.
 *
 * With a [fallback], an [executable] whose first start fails before any succeeded (a Windows `.cmd` shim, a broken
 * binary) hands this search and every later one to the fallback, said in one log line; without one, or after a start
 * succeeded, a failed start is that search's `Failed` outcome.
 */
internal class RipgrepSearch(private val executable: String, private val fallback: Search? = null) : Search {
    private val fellBack = AtomicReference<Search?>()

    @Volatile
    private var started = false

    override val backend: SearchBackend get() = fellBack.get()?.backend ?: SearchBackend.Ripgrep

    override fun find(request: SearchRequest): SearchOutcome {
        fellBack.get()?.let { return it.find(request) }
        val deadline = System.nanoTime() + 30_000_000_000L
        PatternSubset.check(request.pattern, request.mode)?.let { return it }
        val candidates = when (val resolved = Candidates.resolve(request)) {
            is CandidateSet.Failed -> return SearchOutcome.Failed(resolved.reason)
            is CandidateSet.Denied -> return SearchOutcome.Denied(resolved.reason, resolved.paths)
            is CandidateSet.Selected -> resolved.files
        }
        val collector = HitCollector(request.budgetBytes, request.maxHits)
        if (candidates.isEmpty()) return collector.outcome(request.scope, backend, 0)

        val root = request.scope.root.normalize()
        val known = candidates.associateBy { it.relPath }
        val command = baseCommand(request)
        for (chunk in chunks(candidates)) {
            val failure = try {
                runChunk(root, command, chunk, known, collector, deadline)
            } catch (unstartable: Unstartable) {
                return fallBack(unstartable.cause, request)
            }
            if (failure != null) return failure
            if (collector.truncation != null) break
        }
        return collector.outcome(request.scope, backend, candidates.size)
    }

    private fun baseCommand(request: SearchRequest): List<String> {
        val normalized = request.normalizedPattern()
        val command = ArrayList<String>()
        command += executable
        // Pinned invocation: no user config, no colour, machine-readable events, one thread so the
        // event order is the order of the file arguments and both backends truncate identically.
        // --crlf is required, not cosmetic: without it '$' and '.' see the '\r' of a CRLF file, so
        // 'beta$' misses "alpha beta\r\n" while the JVM backend, which strips the terminator before
        // matching, finds it (verified against rg 15.2.0).
        command += listOf(
            "--json", "--color", "never", "--no-config", "--line-number", "--column", "--crlf", "-j1",
        )
        if (request.mode == SearchMode.Literal) command += "-F"
        if (!normalized.caseSensitive) command += "-i"
        // --max-count is per file, not global (verified): cap it one above the global limit so a
        // single file can still reveal that more hits existed.
        request.maxHits?.let { command += listOf("--max-count", perFileCap(it).toString()) }
        command += listOf("-e", normalized.body, "--")
        return command
    }

    private fun perFileCap(maxHits: Int): Int = if (maxHits == Int.MAX_VALUE) maxHits else maxHits + 1

    /** No chunk has started yet, so nothing was collected: [request] runs whole on the fallback, which every later search keeps. */
    private fun fallBack(cause: IOException, request: SearchRequest): SearchOutcome {
        val to = checkNotNull(fallback)
        if (fellBack.compareAndSet(null, to)) {
            LoggerFactory.getLogger(RipgrepSearch::class.java).warn("search backend {} instead of ripgrep: '{}' could not start ({})", to.backend.name.lowercase(), executable, cause.message)
        }
        return to.find(request)
    }

    private class Unstartable(override val cause: IOException) : RuntimeException(cause)

    /** Runs one chunk; returns a terminal outcome on failure, or null when the chunk finished. */
    private fun runChunk(
        root: Path,
        command: List<String>,
        chunk: List<Candidate>,
        known: Map<String, Candidate>,
        collector: HitCollector,
        deadline: Long,
    ): SearchOutcome? {
        if (System.nanoTime() >= deadline) return SearchOutcome.Failed("search deadline exceeded")
        val process = try {
            ProcessBuilder(command + chunk.map { it.relPath }).directory(root.toFile()).start()
        } catch (e: IOException) {
            if (!started && fallback != null) throw Unstartable(e)
            return SearchOutcome.Failed("could not start '$executable': ${e.message}")
        }
        started = true
        val stderr = ByteArrayOutputStream()
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val timer = java.util.Timer("search-deadline", true)
        timer.schedule(object : java.util.TimerTask() {
            override fun run() { timedOut.set(true); process.destroyForcibly() }
        }, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1))
        val drain = Thread { try { process.errorStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                val keep = minOf(n, (65536 - stderr.size()).coerceAtLeast(0))
                stderr.write(buffer, 0, keep)
            }
        } } catch (_: IOException) { } }
        drain.isDaemon = true
        drain.start()

        var stopped = false
        var broken: String? = null
        try {
            process.inputStream.bufferedReader(UTF_8).use { reader ->
                while (true) {
                    val line = buildString {
                        while (true) {
                            val ch = reader.read()
                            if (ch < 0 || ch == 10) break
                            if (length >= 1024 * 1024) throw IOException("search JSON line exceeds 1 MiB")
                            append(ch.toChar())
                        }
                    }
                    if (line.isEmpty()) break
                    if (line.isBlank()) continue
                    when (val event = parse(line, known)) {
                        is ParsedEvent.Other -> Unit
                        is ParsedEvent.Broken -> {
                            broken = event.reason
                            stopped = true
                        }

                        is ParsedEvent.Match -> if (!collector.offer(event.hit)) stopped = true
                    }
                    if (stopped) break
                }
            }
        } catch (e: IOException) {
            broken = "could not read '$executable' output: ${e.message}"
            stopped = true
        } finally {
            if (stopped) process.destroyForcibly()
        }

        val exit = try {
            if (!process.waitFor(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                timedOut.set(true)
                process.destroyForcibly()
            }
            if (timedOut.get()) -1 else process.exitValue()
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return SearchOutcome.Failed("the search was interrupted")
        } finally { timer.cancel() }
        drain.join(DRAIN_MILLIS)
        if (timedOut.get()) return SearchOutcome.Failed("search deadline exceeded")
        broken?.let { return SearchOutcome.Failed(it) }
        if (stopped) return null // we killed it; its exit status is ours, not a ripgrep verdict
        return classify(exit, String(stderr.toByteArray(), UTF_8))
    }

    /** Exit 0 = matches, 1 = no matches, 2 = error; permission-only errors are a denial, not a failure. */
    private fun classify(exit: Int, stderr: String): SearchOutcome? = when (exit) {
        0, 1 -> null
        else -> {
            val denied = RipgrepStderr.deniedPaths(stderr)
            val detail = stderr.trim().ifEmpty { "no diagnostic output" }
            when {
                denied != null ->
                    SearchOutcome.Denied("the filesystem denied access to ${denied.size} path(s)", denied)

                // Safety net mirroring the JVM backend: a pattern the subset validator let through
                // but the engine rejects is refused, never reported as an ordinary failure.
                "regex parse error" in stderr ->
                    SearchOutcome.Unsupported("the ripgrep regex engine rejected the pattern: $detail")

                else -> SearchOutcome.Failed("'$executable' exited $exit: $detail")
            }
        }
    }

    private fun parse(line: String, known: Map<String, Candidate>): ParsedEvent {
        val event = try {
            JSON.decodeFromString(RgEvent.serializer(), line)
        } catch (e: SerializationException) {
            return ParsedEvent.Broken("unparseable '$executable' JSON event: ${e.message}")
        }
        if (event.type != "match") return ParsedEvent.Other
        val data = event.data ?: return ParsedEvent.Broken("a ripgrep match event carried no data")
        val reported = data.path?.asText()
            ?: return ParsedEvent.Broken("a ripgrep match event carried no path")
        val relPath = reported.replace('\\', '/')
        if (!known.containsKey(relPath)) {
            return ParsedEvent.Broken("ripgrep reported '$relPath', which was not in the candidate list")
        }
        val lineNumber = data.lineNumber
            ?: return ParsedEvent.Broken("ripgrep reported a match in '$relPath' without a line number")
        val raw = data.lines?.asBytes()
            ?: return ParsedEvent.Broken("ripgrep reported a match in '$relPath' without line content")
        // submatches[].start is a byte offset into the reported line; the public column is a 1-based
        // UTF-16 index, which is what the JVM backend reports, so convert through the byte prefix.
        val start = (data.submatches.firstOrNull()?.start ?: 0).coerceIn(0, raw.size)
        val column = String(raw, 0, start, UTF_8).length + 1
        return ParsedEvent.Match(Hit(relPath, lineNumber, column, stripLineTerminator(String(raw, UTF_8))))
    }

    private fun chunks(files: List<Candidate>): List<List<Candidate>> {
        val out = ArrayList<List<Candidate>>()
        var current = ArrayList<Candidate>()
        var length = 0
        for (file in files) {
            val cost = file.relPath.length + 3
            if (current.isNotEmpty() && (length + cost > MAX_ARGUMENT_CHARS || current.size >= MAX_ARGUMENT_COUNT)) {
                out += current
                current = ArrayList()
                length = 0
            }
            current += file
            length += cost
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private sealed interface ParsedEvent {
        data class Match(val hit: Hit) : ParsedEvent
        data class Broken(val reason: String) : ParsedEvent
        data object Other : ParsedEvent
    }

    private companion object {
        // Windows caps a command line near 32 KiB; stay far below it and chunk the file list.
        const val MAX_ARGUMENT_CHARS = 8_000
        const val MAX_ARGUMENT_COUNT = 1_000
        const val DRAIN_MILLIS = 2_000L
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** Classifies ripgrep's diagnostics so a denial is never reported as a generic failure. */
internal object RipgrepStderr {
    private val PERMISSION_MARKERS = listOf("permission denied", "access is denied", "os error 5", "os error 13")

    /** The denied paths when every diagnostic line is a permission error, else null. */
    fun deniedPaths(stderr: String): List<String>? {
        val lines = stderr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        if (!lines.all { line -> PERMISSION_MARKERS.any { marker -> line.lowercase().contains(marker) } }) return null
        return lines.map(::pathOf).distinct().sorted()
    }

    private fun pathOf(line: String): String {
        val body = line.removePrefix("rg: ")
        val separator = body.indexOf(": ")
        return if (separator > 0) body.substring(0, separator) else body
    }
}

@Serializable
private class RgEvent(val type: String, val data: RgData? = null)

@Serializable
private class RgData(
    val path: RgContent? = null,
    val lines: RgContent? = null,
    @SerialName("line_number") val lineNumber: Int? = null,
    val submatches: List<RgSubmatch> = emptyList(),
)

@Serializable
private class RgContent(val text: String? = null, val bytes: String? = null) {
    /** ripgrep sends `text` for valid UTF-8 and base64 `bytes` otherwise. */
    fun asBytes(): ByteArray? = text?.toByteArray(UTF_8) ?: bytes?.let(::decode)

    fun asText(): String? = text ?: bytes?.let(::decode)?.let { String(it, UTF_8) }

    private fun decode(value: String): ByteArray? = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}

@Serializable
private class RgSubmatch(val start: Int = 0)
