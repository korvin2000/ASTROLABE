package io.astrolabe.os.search

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The in-process fallback. It reads the same candidate list [Candidates] hands ripgrep, in the same
 * order, and applies the same budget, so its [Hits] are identical for every pattern inside the
 * [PatternSubset] common subset.
 *
 * Lines are matched one at a time with the terminator removed, so `^` and `$` are line-oriented
 * without `Pattern.MULTILINE` — which is exactly ripgrep's line-oriented behaviour.
 */
internal class JvmSearch : Search {

    override val backend: SearchBackend = SearchBackend.Jvm

    override fun find(request: SearchRequest): SearchOutcome {
        PatternSubset.check(request.pattern, request.mode)?.let { return it }
        val candidates = when (val resolved = Candidates.resolve(request)) {
            is CandidateSet.Failed -> return SearchOutcome.Failed(resolved.reason)
            is CandidateSet.Denied -> return SearchOutcome.Denied(resolved.reason, resolved.paths)
            is CandidateSet.Selected -> resolved.files
        }
        val collector = HitCollector(request.budgetBytes, request.maxHits)
        // The engine is only touched once there is something to search, so that an empty candidate
        // set produces the same Found(empty) from both backends.
        if (candidates.isEmpty()) return collector.outcome(request.scope, backend, 0)
        val regex = try {
            compile(request)
        } catch (e: PatternSyntaxException) {
            // Safety net: the subset validator should have refused this first, so a pattern that
            // reaches here is still refused rather than silently searched with different semantics.
            return SearchOutcome.Unsupported("the JVM regex engine rejected the pattern: ${e.description}")
        }
        val denied = ArrayList<String>()
        for (candidate in candidates) {
            val bytes = try {
                Files.readAllBytes(candidate.absPath)
            } catch (_: AccessDeniedException) {
                denied += candidate.relPath
                continue
            } catch (_: NoSuchFileException) {
                continue
            } catch (e: IOException) {
                return SearchOutcome.Failed("could not read '${candidate.relPath}': ${e.message}")
            }
            if (!scan(candidate.relPath, String(bytes, UTF_8), regex, collector)) break
        }
        if (denied.isNotEmpty()) {
            return SearchOutcome.Denied("the filesystem denied access to ${denied.size} file(s)", denied.sorted())
        }
        return collector.outcome(request.scope, backend, candidates.size)
    }

    private fun compile(request: SearchRequest): Pattern {
        val normalized = request.normalizedPattern()
        val flags = if (normalized.caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        val body = when (request.mode) {
            SearchMode.Literal -> Pattern.quote(normalized.body)
            SearchMode.Regex -> normalized.body
        }
        return Pattern.compile(body, flags)
    }

    /** Feeds every matching line of [content] to [collector]; returns false once the search must stop. */
    private fun scan(relPath: String, content: String, regex: Pattern, collector: HitCollector): Boolean {
        var lineNumber = 1
        var start = 0
        while (true) {
            val newline = content.indexOf('\n', start)
            if (newline < 0 && start >= content.length) return true
            val end = if (newline < 0) content.length else newline
            // \n and \r\n both terminate a line; a lone \r does not, and a trailing \r at end of
            // file is content, not a terminator — which is what `rg --crlf` also does.
            val textEnd = if (newline >= 0 && end > start && content[end - 1] == '\r') end - 1 else end
            val text = content.substring(start, textEnd)
            val matcher = regex.matcher(text)
            if (matcher.find() && !collector.offer(Hit(relPath, lineNumber, matcher.start() + 1, text))) {
                return false
            }
            if (newline < 0) return true
            start = newline + 1
            lineNumber++
        }
    }
}
