package io.astrolabe.tool.look

import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.DeclarationKind
import io.astrolabe.atlas.EditSet
import io.astrolabe.atlas.Focus
import io.astrolabe.atlas.ImpactAssembly
import io.astrolabe.atlas.ImportGraph
import io.astrolabe.atlas.Outline
import io.astrolabe.atlas.SymbolIndex
import io.astrolabe.atlas.decodeLines
import io.astrolabe.auth.ContentClass
import io.astrolabe.auth.InstructionShape
import io.astrolabe.auth.Redaction
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalScope
import io.astrolabe.evidence.Observation
import io.astrolabe.evidence.Observations
import io.astrolabe.evidence.RedactionMask
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Generation
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.kb.BehaviourMaps
import io.astrolabe.kb.BmapLevel
import io.astrolabe.kb.BmapSource
import io.astrolabe.os.search.Search
import io.astrolabe.os.search.SearchMode
import io.astrolabe.os.search.SearchOutcome
import io.astrolabe.os.search.SearchRequest
import io.astrolabe.os.search.SearchScope
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.ToolMask
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import io.astrolabe.tool.Args
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.Effects
import io.astrolabe.tool.EnvelopeHeader
import io.astrolabe.tool.LookArgs
import io.astrolabe.tool.RuntimeFields
import io.astrolabe.tool.ToolCall
import io.astrolabe.tool.ToolExecutor
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOps
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.verify.Checks
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.Ranges
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace

/** A `look` target: `path`, `path:a-b` or `path::Symbol` (§5.4). */
public sealed interface LookTarget {
    public val path: String

    public data class Whole(override val path: String) : LookTarget

    public data class Lines(override val path: String, val from: Int, val to: Int) : LookTarget

    public data class Symbol(override val path: String, val name: String) : LookTarget

    public companion object {
        private val LINES = Regex("""^(.+?):(\d+)-(\d+)$""")

        @JvmStatic
        public fun parse(target: String): LookTarget {
            val text = target.trim()
            val symbolAt = text.indexOf("::")
            if (symbolAt > 0) return Symbol(text.substring(0, symbolAt), text.substring(symbolAt + 2))
            LINES.matchEntire(text)?.let { m -> return Lines(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
            return Whole(text)
        }
    }
}

/**
 * The `look` family (§5.4, TODO P1.6.3): `tree`, `outline`, `read`, `find`, `def`, `recall`, `catalog`;
 * `refs`, `importers` and `impact` over the tier-0 import graph (P3.2.3, §7.3–7.4); `bmap` over admitted behaviour maps (P4.3.2, §7.5).
 * Every result is an [Observation] with a content blob; only rendered source bytes (reads, find hits,
 * recalls) register coverage in the version registry and the Workset, at the exact version they were read
 * from and minus every redacted line (D-49). Outlines, symbol locations and trees never make a body KNOWN.
 *
 * Budgets are prompt limits: a view is cut at [LookArgs.budget] tokens and says so, the blob keeps the whole
 * rendered selection, and `recall(#n, range)` serves the rest (FX-10). A search's own capture limit is
 * reported separately as `capture_complete`. A whole-file read above budget is refused with the outline.
 *
 * Dedup (D-46, IX-07): a `read` whose lines are already KNOWN at the current version through a live look
 * result answers `see #n (unchanged)`; an evicted, stale or narrower result never does.
 */
public class Look(
    private val workspace: Workspace,
    private val registry: VersionRegistry,
    private val workset: Workset,
    atlas: Atlas,
    private val search: Search,
    private val journal: Journal?,
    private val observations: Observations,
    private val aliases: Aliases,
    private val blobs: BlobStore,
    private val redaction: Redaction,
    private val estimator: TokenEstimator,
    private val idGen: IdGen,
    private val ids: Identities,
    private val mask: ToolMask = ToolOps.implementingS0,
    private val findMaxHits: Int = 200,
    private val findCaptureBytes: Long = 256L * 1024 * 1024,
    /** The registered checks `look(impact)` joins against; null renders no affected checks. */
    private val checks: Checks? = null,
    /** The admitted behaviour maps `look(bmap)` discloses (P4.3.2); null answers that none exist. */
    private val bmaps: BmapSource? = null,
) : ToolExecutor {
    init {
        require(ids.context != null) { "look runs inside a cell: ids.context is its lineage" }
        require(findMaxHits > 0 && findCaptureBytes > 0) { "find limits must be positive" }
    }

    /** The campaign's current atlas; the cell replaces it after `Atlas.refresh` at a turn boundary. */
    public var atlas: Atlas = atlas

    /** The projection generation coverage is keyed by; a rebuild advances it (P2.5). */
    public var generation: Generation = Generation.INITIAL

    override suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome {
        require(call.family == ToolFamily.Look) { "not a look call: ${call.name}" }
        val args = (call.args as Args.Look).args
        if (!mask.allows(call.name)) {
            return refused(args, "masked", "${call.name} is masked in this role; see look(catalog)")
        }
        return when (args.what) {
            "read" -> read(args, context)
            "find" -> find(args, context)
            "recall" -> recall(args, context)
            "tree" -> tree(args)
            "outline" -> outline(args)
            "def" -> def(args)
            "refs" -> refs(args)
            "importers" -> importers(args)
            "impact" -> impact(args)
            "catalog" -> catalog(args)
            "bmap" -> bmap(args)
            else -> refused(args, "masked", "${call.name} is masked in this role; see look(catalog)")
        }
    }

    // ------------------------------------------------------------------ read

    private fun read(args: LookArgs, context: TurnContext): ToolOutcome {
        val target = args.target?.let(LookTarget::parse) ?: return refused(args, "refused", "read needs a target: path | path:a-b | path::Symbol")
        val content = readFile(target.path) ?: return refused(args, "refused", refusalFor(target.path))
        val lines = decodeLines(content.bytes)
        val outlineOf = { Outline.of(target.path, content.bytes) }
        val span: LineRange = when (target) {
            is LookTarget.Lines -> {
                if (target.from < 1 || target.to < target.from || target.from > lines.size) {
                    return refused(args, "refused", "${target.path} has ${lines.size} lines; ${target.from}-${target.to} is not a range in it")
                }
                LineRange(target.from, minOf(target.to, lines.size))
            }
            is LookTarget.Symbol -> {
                val candidates = outlineOf().entries.filter { it.name == target.name && it.kind != DeclarationKind.Import }
                val chosen = when {
                    candidates.isEmpty() -> return refused(args, "refused", "no symbol '${target.name}' in ${target.path}\n" + renderOutline(outlineOf()))
                    candidates.size == 1 -> candidates.single()
                    args.near != null -> candidates.filter { c -> (c.from..c.to).any { lines[it - 1].contains(args.near) } }.singleOrNull()
                        ?: return refused(args, "refused", "'${target.name}' is ambiguous in ${target.path} and near='${args.near}' selects ${candidates.count { c -> (c.from..c.to).any { lines[it - 1].contains(args.near) } }}: " + candidates.joinToString(", ") { "${it.from}-${it.to}" })
                    else -> return refused(args, "refused", "'${target.name}' is ambiguous in ${target.path}: " + candidates.joinToString(", ") { "${it.kind.name.lowercase()} ${it.from}-${it.to}" } + " — add near=")
                }
                LineRange(chosen.from, minOf(chosen.to, lines.size))
            }
            is LookTarget.Whole -> {
                if (lines.isEmpty()) return refused(args, "refused", "${target.path} is empty")
                val whole = LineRange(1, lines.size)
                if (tokensOf(rendered(lines, whole)) > args.budget) {
                    return refused(args, "refused", "${target.path}: ${lines.size} lines exceed budget ${args.budget}; name a range (${target.path}:a-b) or ::Symbol\n" + renderOutline(outlineOf()))
                }
                whole
            }
        }
        // Dedup: the same lines KNOWN at this version through a live look result (D-46).
        workset.entries.firstOrNull { it.path == target.path && it.version == content.version && it.source != EntrySource.PostEdit && it.coverage.covers(span) }?.resultId?.let { alias ->
            return refused(args, "unchanged", "see $alias (unchanged)", versions = mapOf(target.path to content.version))
        }
        val actionId = idGen.next("act")
        val alias = allocate()
        // The raw bytes are published under their own hash (= the version), so a later stale `expect` can be
        // diffed against exactly what the model was shown (§9.1 diff since expect). Recovery storage, never rendered.
        blobs.put(content.bytes, BlobKind.PREIMAGE, ids, recovery = true)
        val full = redaction.apply(rendered(lines, span), ContentClass.ReusableEvidence)
        val hidden = shift(full.mask.hiddenLines, span.from - 1)
        val view = fit(full.text.lines(), args.budget)
        val displayed = LineRange(span.from, span.from + view.lines.size - 1)
        val more = if (view.truncated) "… ${span.to - displayed.to} more lines: recall ${alias.text} range ${displayed.to + 1}-${span.to}" else null
        val body = view.lines.joinToString("\n") + (more?.let { "\n$it" } ?: "")
        val mask = RedactionMask(hidden.intersect(Ranges.of(displayed)), full.mask.limitations)
        val blob = blobs.put(full.text.toByteArray(Charsets.UTF_8), BlobKind.OUTPUT, ids)
        observations.record(
            Observation(
                id = alias.canonicalId, ids = ids, actionId = actionId, candidate = null, contentRef = blob,
                paths = listOf(target.path), ranges = mapOf(target.path to Ranges.of(displayed)), complete = !view.truncated,
                sourceVersions = mapOf(target.path to content.version), captureComplete = true, redaction = mask, truncated = view.truncated,
            ),
        )
        show(target.path, content.version, Ranges.of(displayed), mask, alias.text, context.turn, view.tokens)
        return outcome(
            alias.text, actionId, "ok", body, view.tokens,
            versions = mapOf(target.path to content.version), scope = "${target.path}:${displayed.from}-${displayed.to}" + (if (content.raced) " (raced)" else ""),
            complete = !view.truncated, captureComplete = true, displayTruncated = view.truncated, redacted = mask.applied, artifact = blob,
        )
    }

    // ------------------------------------------------------------------ find

    private fun find(args: LookArgs, context: TurnContext): ToolOutcome {
        val pattern = args.target?.takeIf { it.isNotEmpty() } ?: return refused(args, "refused", "find needs a pattern in target")
        return when (args.scope) {
            "workspace" -> findInWorkspace(args, pattern, context)
            "store" -> findInStore(args, pattern)
            else -> refused(args, "unavailable", "find in=kb: the knowledge base arrives in P2.6; nothing was searched (complete=false)", complete = false)
        }
    }

    private fun findInWorkspace(args: LookArgs, pattern: String, context: TurnContext): ToolOutcome {
        val scope = args.glob?.let { SearchScope.Glob(workspace.root, it) } ?: SearchScope.All(workspace.root)
        val scopeText = "workspace" + (args.glob?.let { " glob=$it" } ?: "")
        val hits = when (val outcome = search.find(SearchRequest(pattern, SearchMode.Regex, scope, findCaptureBytes, maxHits = findMaxHits))) {
            is SearchOutcome.Found -> outcome.hits
            is SearchOutcome.Incomplete -> outcome.hits
            is SearchOutcome.Failed -> return refused(args, "failed", "find failed: ${outcome.reason}", complete = false, scope = scopeText)
            is SearchOutcome.Denied -> return refused(args, "denied", "find denied: ${outcome.reason} (${outcome.paths.joinToString(", ")})", complete = false, scope = scopeText)
            is SearchOutcome.Unsupported -> return refused(args, "unsupported", "pattern not supported by the search backend: ${outcome.reason}", complete = false, scope = scopeText)
        }
        val actionId = idGen.next("act")
        val alias = allocate()
        // Hit lines are rendered from bytes hashed now, so the coverage they grant matches their version.
        val files = hits.hits.map { it.path }.distinct().associateWith { readFile(it) }
        val lines = ArrayList<String>()
        val perPath = LinkedHashMap<String, MutableList<Int>>()
        for (hit in hits.hits) {
            val file = files[hit.path]
            val text = file?.let { decodeLines(it.bytes).getOrNull(hit.line - 1) }
            if (text == null) {
                lines += "${hit.path}:${hit.line}: (moved since the search)"
            } else {
                lines += "${hit.path}:${hit.line}: ${text.trim()}"
                perPath.getOrPut(hit.path) { ArrayList() } += hit.line
            }
        }
        val summary = "${hits.hits.size} match${if (hits.hits.size == 1) "" else "es"} for /$pattern/ in $scopeText" +
            (hits.filesSearched?.let { " · $it files searched" } ?: "") + (if (hits.complete) "" else " · capture incomplete (${hits.backend.name.lowercase()} limit)")
        val full = redaction.apply((listOf(summary) + lines).joinToString("\n"), ContentClass.ReusableEvidence)
        val view = fit(full.text.lines(), args.budget)
        val body = view.lines.joinToString("\n") + (if (view.truncated) "\n… ${lines.size + 1 - view.lines.size} more lines: recall ${alias.text}" else "")
        val versions = files.filterValues { it != null }.mapValues { it.value!!.version }
        val blob = blobs.put(full.text.toByteArray(Charsets.UTF_8), BlobKind.OUTPUT, ids)
        // Coverage: only hit lines actually displayed, minus lines the redaction hid.
        val shownLines = view.lines.size - 1
        val hiddenIndex = full.mask.hiddenLines
        val ranges = LinkedHashMap<String, Ranges>()
        var index = 2 // rendered line number of the first hit (line 1 is the summary)
        for (hit in hits.hits) {
            val shown = index - 1 <= shownLines
            val visible = shown && !hiddenIndex.covers(LineRange(index, index))
            if (visible && files[hit.path] != null && decodeLines(files[hit.path]!!.bytes).size >= hit.line) {
                ranges[hit.path] = (ranges[hit.path] ?: Ranges.EMPTY) + Ranges.single(hit.line, hit.line)
            }
            index++
        }
        observations.record(
            Observation(
                id = alias.canonicalId, ids = ids, actionId = actionId, candidate = null, contentRef = blob,
                paths = perPath.keys.toList(), ranges = ranges, complete = hits.complete && !view.truncated,
                sourceVersions = versions, captureComplete = hits.complete, redaction = RedactionMask(Ranges.EMPTY, full.mask.limitations), truncated = view.truncated,
            ),
        )
        for ((path, covered) in ranges) show(path, versions.getValue(path), covered, RedactionMask.NONE, alias.text, context.turn, 0)
        return outcome(
            alias.text, actionId, "ok", body, view.tokens, versions = versions, scope = scopeText,
            complete = hits.complete && !view.truncated, captureComplete = hits.complete, displayTruncated = view.truncated,
            redacted = full.mask.applied, artifact = blob,
        )
    }

    private fun findInStore(args: LookArgs, pattern: String): ToolOutcome {
        val store = journal ?: return refused(args, "unavailable", "find in=store: no journal is attached to this cell", complete = false, scope = "store")
        val actionId = idGen.next("act")
        val alias = allocate()
        val hits = store.search(pattern, JournalScope(ids.work), limit = findMaxHits)
        val lines = listOf("${hits.events.size} journal event${if (hits.events.size == 1) "" else "s"} match '$pattern' in store" + (if (hits.complete) "" else " · more than $findMaxHits: narrow the pattern")) +
            hits.events.map { "#${it.seq} ${it.kind.name.lowercase()}" + (it.turn?.let { t -> " turn $t" } ?: "") + ": " + it.text.lineSequence().first() }
        val full = redaction.apply(lines.joinToString("\n"), ContentClass.ReusableEvidence)
        val view = fit(full.text.lines(), args.budget)
        val body = view.lines.joinToString("\n") + (if (view.truncated) "\n… recall ${alias.text}" else "")
        val blob = blobs.put(full.text.toByteArray(Charsets.UTF_8), BlobKind.OUTPUT, ids)
        observations.record(Observation(alias.canonicalId, ids, actionId, null, blob, emptyList(), emptyMap(), hits.complete && !view.truncated, emptyMap(), hits.complete, RedactionMask(Ranges.EMPTY, full.mask.limitations), view.truncated))
        return outcome(alias.text, actionId, "ok", body, view.tokens, scope = "store", complete = hits.complete && !view.truncated, captureComplete = hits.complete, displayTruncated = view.truncated, redacted = full.mask.applied, artifact = blob)
    }

    // ---------------------------------------------------------------- recall

    private fun recall(args: LookArgs, context: TurnContext): ToolOutcome {
        val number = args.id?.let(Aliases::parse) ?: return refused(args, "refused", "recall needs id=#n")
        if (args.since != null) return refused(args, "unsupported", "recall since=: background output arrives with run handles (P1.6.5)")
        val alias = aliases.resolve(ids.work, number) ?: return refused(args, "refused", "no result #$number in this campaign")
        val observation = observations.get(alias.canonicalId) ?: return refused(args, "refused", "#$number is not a recallable observation (${alias.kind})")
        val stored = String(blobs.get(observation.contentRef), Charsets.UTF_8).lines()
        val path = observation.paths.singleOrNull()?.takeIf { observation.ranges[it] != null }
        val sourceRange = path?.let { observation.ranges.getValue(it).ranges.singleOrNull() }
        val range = args.range?.let { text ->
            Regex("""^(\d+)-(\d+)$""").matchEntire(text.trim())?.let { LineRange(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
                ?: return refused(args, "refused", "range must be a-b")
        }
        // A read's lines are addressed by source line number; any other view by its own line numbers.
        val selected: List<String>
        val selectedSource: LineRange?
        if (sourceRange != null) {
            val first = sourceRange.from
            val wanted = range ?: LineRange(first, first + stored.size - 1)
            val fromIndex = wanted.from - first
            val toIndex = minOf(wanted.to - first, stored.size - 1)
            if (fromIndex < 0 || fromIndex > toIndex) return refused(args, "refused", "#$number holds ${path}:${first}-${first + stored.size - 1}; ${wanted.from}-${wanted.to} is outside it")
            selected = stored.subList(fromIndex, toIndex + 1)
            selectedSource = LineRange(wanted.from, first + toIndex)
        } else {
            val wanted = range ?: LineRange(1, stored.size)
            if (wanted.from > stored.size) return refused(args, "refused", "#$number has ${stored.size} lines")
            selected = stored.subList(wanted.from - 1, minOf(wanted.to, stored.size))
            selectedSource = null
        }
        val actionId = idGen.next("act")
        val recalled = allocate()
        val view = fit(selected, args.budget)
        val shown = selectedSource?.let { LineRange(it.from, it.from + view.lines.size - 1) }
        var status = "ok"
        var label = "recall of #$number"
        val versions = LinkedHashMap<String, FileVersion>()
        var known = false
        if (path != null && shown != null) {
            val recorded = observation.sourceVersions.getValue(path)
            val now = registry.version(path)
            val hidden = observation.redaction.hiddenLines.intersect(Ranges.of(shown))
            val entry = Entry(path, Ranges.of(shown), recorded, EntrySource.Recall, context.turn, recalled.text, view.tokens, hidden)
            when (val result = workset.recall(entry, now, context.turn)) {
                is Workset.RecallResult.Known -> {
                    known = true
                    versions[path] = recorded
                    registry.show(ids.context!!, generation, workspace.id, path, recorded, Ranges.of(shown), RedactionMask(hidden))
                }
                is Workset.RecallResult.Historical -> {
                    status = "historical"
                    label = "recall of #$number · historical v=${recorded.hash8} (now ${result.currentVersion.hash8}); not KNOWN, read again for current bytes"
                    versions[path] = result.currentVersion
                }
            }
            if (now == null) {
                status = "historical"
                label = "recall of #$number · historical v=${recorded.hash8} (file gone); not KNOWN"
            }
        }
        val more = if (view.truncated) "\n… recall #$number range ${(shown?.to ?: view.lines.size) + 1}-${selectedSource?.to ?: selected.size}" else ""
        val body = label + "\n" + view.lines.joinToString("\n") + more
        val blob = observation.contentRef
        observations.record(
            Observation(
                id = recalled.canonicalId, ids = ids, actionId = actionId, candidate = null, contentRef = blob,
                paths = listOfNotNull(path), ranges = if (known && path != null && shown != null) mapOf(path to Ranges.of(shown)) else emptyMap(),
                complete = !view.truncated, sourceVersions = if (path != null) mapOf(path to observation.sourceVersions.getValue(path)) else emptyMap(),
                captureComplete = observation.captureComplete, redaction = observation.redaction, truncated = view.truncated,
            ),
        )
        return outcome(recalled.text, actionId, status, body, view.tokens, versions = versions, scope = "recall #$number", complete = !view.truncated, captureComplete = observation.captureComplete, displayTruncated = view.truncated, redacted = observation.redaction.applied, artifact = blob)
    }

    // ------------------------------------------------- tree · outline · def · catalog

    private fun tree(args: LookArgs): ToolOutcome {
        val focus = args.target?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() && it != "." }?.let { Focus.Dir(it.replace('\\', '/')) } ?: Focus.Root
        if (focus is Focus.Dir && atlas.filesUnder(focus.path) == 0 && atlas.children(focus.path).isEmpty()) {
            return refused(args, "refused", "no directory '${focus.path}' in the atlas")
        }
        return textResult(args, Focus.render(atlas, focus, args.budget), scope = "tree " + (if (focus is Focus.Dir) focus.path else "/"))
    }

    private fun outline(args: LookArgs): ToolOutcome {
        val path = args.target?.let(LookTarget::parse)?.path ?: return refused(args, "refused", "outline needs a path")
        val content = readFile(path) ?: return refused(args, "refused", refusalFor(path))
        return textResult(args, renderOutline(Outline.of(path, content.bytes)), scope = "outline $path", versions = mapOf(path to content.version))
    }

    private fun def(args: LookArgs): ToolOutcome {
        val name = args.target?.trim()?.removePrefix("::")?.takeIf { it.isNotEmpty() } ?: return refused(args, "refused", "def needs a symbol name")
        val index = SymbolIndex(atlas, search)
        val locations = index.def(name)
        val lines = listOf("${locations.size} definition${if (locations.size == 1) "" else "s"} of '$name' · tier ${index.tier.name.lowercase()} · complete: no") +
            locations.map { "${it.path}:${it.line} ${it.kind.name.lowercase()} ${it.name}" }
        return textResult(args, lines.joinToString("\n"), scope = "def $name", complete = false)
    }

    // §7.4/§7.7: every answer carries tier and complete; tier 0 never claims completeness, dispatch is never guessed.
    private fun refs(args: LookArgs): ToolOutcome {
        val raw = args.target?.trim()?.takeIf { it.isNotEmpty() } ?: return refused(args, "refused", "refs needs a symbol: name | Owner.name | path::name")
        val home = raw.substringBefore("::", "").takeIf { raw.contains("::") }
        val qualified = raw.substringAfter("::")
        val name = qualified.substringAfterLast('.').takeIf { it.isNotEmpty() } ?: return refused(args, "refused", "refs needs a symbol name")
        val owner = qualified.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
        val index = SymbolIndex(atlas, search)
        val found = index.refs(name)
        val graph = importGraph()
        val first = (home ?: index.def(name).firstOrNull()?.path)?.let { graph.packageOf(it) }
        val grouped = found.references.groupBy { graph.packageOf(it.path) }.toList()
            .sortedWith(compareBy({ it.first != first }, { it.first ?: "\uFFFF" }))
        val lines = ArrayList<String>()
        lines += "${found.references.size} reference${if (found.references.size == 1) "" else "s"} to '$name' · tier ${found.tier.name.lowercase()} · complete: no" +
            (if (found.truncated) " · truncated" else "")
        owner?.let { lines += "dispatch unresolved: matched by name; receiver '$it' is not resolved at tier ${found.tier.level}, so calls through other receivers are listed too and dynamic calls may be missing" }
        for ((pkg, refs) in grouped) {
            lines += "package ${pkg ?: "(unknown)"}${if (pkg == first) " (first)" else ""}: ${refs.size}"
            refs.forEach { lines += "  ${it.path}:${it.line} ${it.text}" }
        }
        return textResult(args, lines.joinToString("\n"), scope = "refs $raw", complete = false)
    }

    private fun importers(args: LookArgs): ToolOutcome {
        val path = args.target?.trim()?.takeIf { it.isNotEmpty() } ?: return refused(args, "refused", "importers needs a path")
        if (atlas.row(path) == null) return refused(args, "refused", refusalFor(path))
        val graph = importGraph()
        val pkg = graph.packageOf(path)
        val importers = graph.importers(path).sortedWith(compareBy({ it.scope.packageId != pkg }, { it.path }))
        val dynamic = graph.graph.unresolved.map { it.importer.path }.distinct()
        val lines = ArrayList<String>()
        lines += "${importers.size} importer${if (importers.size == 1) "" else "s"} of $path · package ${pkg ?: "(unknown)"} · tier ${graph.graph.tier.name.lowercase()} · complete: no"
        importers.forEach { lines += "  ${it.path}${if (it.scope.packageId != pkg) " (package ${it.scope.packageId ?: "(unknown)"})" else ""}" }
        if (dynamic.isNotEmpty()) lines += "unresolved: ${dynamic.size} file${if (dynamic.size == 1) "" else "s"} with dynamic or unresolved imports may also import it: " + dynamic.take(UNRESOLVED_SHOWN).joinToString(", ") + if (dynamic.size > UNRESOLVED_SHOWN) " …" else ""
        return textResult(args, lines.joinToString("\n"), scope = "importers $path", complete = false)
    }

    private fun impact(args: LookArgs): ToolOutcome {
        val paths = args.target?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        if (paths.isEmpty()) return refused(args, "refused", "impact needs paths: a.py[,b.py]")
        paths.firstOrNull { atlas.row(it) == null }?.let { return refused(args, "refused", refusalFor(it)) }
        val graph = importGraph()
        val projection = ImpactAssembly(graph, SymbolIndex(atlas, search)).analyze(EditSet(paths), checks?.all().orEmpty(), contracts = null)
        val analysis = projection.analysis
        val lines = ArrayList<String>()
        lines += "impact of ${paths.sorted().joinToString(", ")} · tier ${projection.tier.name.lowercase()} · complete: ${if (projection.complete) "yes" else "no"}"
        lines += "blast ${analysis.blast.size}: " + analysis.blast.joinToString(", ") { it.path }
        lines += "checks in blast: " + projection.blastChecks.joinToString(", ").ifEmpty { "(none)" } +
            " · affected (with widening): " + projection.affectedTests.joinToString(", ").ifEmpty { "(none)" }
        lines += "verify scopes: " + analysis.verificationScopes.joinToString(", ") { it.packageId?.let { p -> "package $p" } ?: "workspace" }.ifEmpty { "blast only" }
        lines += "contracts touched: " + analysis.contractsTouched.joinToString(", ").ifEmpty { "(none found)" } + if (analysis.contractsComplete) "" else " · inventory incomplete"
        lines += "risk: " + (analysis.risk.estimate?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "unknown (no diff)") + " · θ ${analysis.risk.threshold}"
        analysis.issues.forEach { lines += "unresolved: $it" }
        return textResult(args, lines.joinToString("\n"), scope = "impact ${paths.sorted().joinToString(",")}", complete = projection.complete)
    }

    private var graphOf: Pair<Atlas, ImportGraph>? = null

    private fun importGraph(): ImportGraph = graphOf?.takeIf { it.first === atlas }?.second
        ?: ImportGraph.of(atlas, workspace.id).also { graphOf = atlas to it }

    // §7.5: a map is validated against the current atlas and rendered without versions, so it never grants coverage.
    private fun bmap(args: LookArgs): ToolOutcome {
        val target = args.target?.trim()?.takeIf { it.isNotEmpty() } ?: return refused(args, "refused", "bmap needs a subsystem: subsystem | subsystem#behaviour")
        val subsystem = target.substringBefore('#')
        val behaviour = target.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val map = bmaps?.find(subsystem)
            ?: return refused(args, "not_found", "no admitted BMAP-$subsystem (complete: the knowledge base was searched); navigate with tree, outline and find", scope = "bmap $subsystem")
        val validation = BehaviourMaps.validate(map, atlas)
        return if (behaviour == null) {
            textResult(args.copy(budget = minOf(args.budget, BehaviourMaps.SUBSYSTEM_MAX_TOKENS)), BehaviourMaps.render(validation, BmapLevel.Subsystem), scope = "bmap $subsystem")
        } else {
            textResult(args, BehaviourMaps.render(validation, BmapLevel.Behaviour, behaviour), scope = "bmap $target")
        }
    }

    private fun catalog(args: LookArgs): ToolOutcome {
        val lines = ToolFamily.entries.map { family ->
            val ops = ToolOps.of(family)
            val allowed = ops.filter { mask.allows(ToolOps.name(family, it)) }
            val masked = ops - allowed.toSet()
            "${family.wire}: ${allowed.joinToString(" ").ifEmpty { "(none)" }}" + (if (masked.isEmpty()) "" else " · masked: ${masked.joinToString(" ")}")
        }
        return textResult(args, lines.joinToString("\n"), scope = "catalog")
    }

    // -------------------------------------------------------------- helpers

    private class Fit(val lines: List<String>, val tokens: Long, val truncated: Boolean)

    /** Whole lines that fit [budget] tokens, at least one. */
    private fun fit(lines: List<String>, budget: Int): Fit {
        var total = 0L
        val kept = ArrayList<String>()
        for (line in lines) {
            val cost = tokensOf(line) + 1
            if (kept.isNotEmpty() && total + cost > budget) break
            kept += line
            total += cost
        }
        return Fit(kept, total, kept.size < lines.size)
    }

    private fun tokensOf(text: String): Long = estimator.estimate(text).tokens

    private fun rendered(lines: List<String>, span: LineRange): String =
        (span.from..span.to).joinToString("\n") { "$it| ${lines[it - 1]}" }

    private fun renderOutline(outline: Outline): String {
        val header = "outline ${outline.path} (${outline.language.name.lowercase()}, tier ${outline.tier.name.lowercase()}, ${outline.entries.size} declarations)"
        if (outline.entries.isEmpty()) return header
        return header + "\n" + outline.entries.joinToString("\n") { "  ${it.kind.name.lowercase()} ${it.name}  ${it.from}-${it.to}" + (if (it.exported) " exported" else "") }
    }

    private fun readFile(path: String): io.astrolabe.workspace.FileContent? = registry.read(path)

    private fun refusalFor(path: String): String = when (val resolved = workspace.resolve(path, Intent.Read)) {
        is PathResolution.Rejected -> "'$path' refused: ${resolved.detail}"
        is PathResolution.Resolved -> "no file at '${resolved.relative}'"
    }

    private fun shift(ranges: Ranges, by: Int): Ranges =
        if (by == 0 || ranges.isEmpty) ranges else Ranges.of(ranges.ranges.map { LineRange(it.from + by, it.to + by) })

    private fun allocate() = aliases.allocate(ids.work, idGen.next("obs"), "result", ids.context, workspace.id)

    private fun show(path: String, version: FileVersion, ranges: Ranges, mask: RedactionMask, alias: String, turn: Int, tokens: Long) {
        registry.show(ids.context!!, generation, workspace.id, path, version, ranges, mask)
        val granted = ranges - mask.hiddenLines
        if (!granted.isEmpty) workset.register(Entry(path, ranges, version, EntrySource.Look, turn, alias, tokens, mask.hiddenLines.intersect(ranges)))
    }

    /** A structural result (tree, outline, def, catalog): observed and aliased, no coverage. */
    private fun textResult(args: LookArgs, text: String, scope: String, versions: Map<String, FileVersion> = emptyMap(), complete: Boolean = true): ToolOutcome {
        val actionId = idGen.next("act")
        val alias = allocate()
        val full = redaction.apply(text, ContentClass.ReusableEvidence)
        val view = fit(full.text.lines(), args.budget)
        val body = view.lines.joinToString("\n") + (if (view.truncated) "\n… recall ${alias.text}" else "")
        val blob = blobs.put(full.text.toByteArray(Charsets.UTF_8), BlobKind.OUTPUT, ids)
        observations.record(Observation(alias.canonicalId, ids, actionId, null, blob, versions.keys.toList(), emptyMap(), complete && !view.truncated, versions, true, RedactionMask(Ranges.EMPTY, full.mask.limitations), view.truncated))
        return outcome(alias.text, actionId, "ok", body, view.tokens, versions = versions, scope = scope, complete = complete && !view.truncated, captureComplete = true, displayTruncated = view.truncated, redacted = full.mask.applied, artifact = blob)
    }

    /** A result that observed nothing new: refusal, masked op, dedup pointer. Not aliased, not stored. */
    private fun refused(args: LookArgs, status: String, body: String, versions: Map<String, FileVersion> = emptyMap(), complete: Boolean = true, scope: String? = null): ToolOutcome =
        outcome("#-", idGen.next("act"), status, body, tokensOf(body), versions = versions, scope = scope ?: args.target, complete = complete, captureComplete = true, displayTruncated = false, redacted = false, artifact = null)

    private fun outcome(
        alias: String,
        actionId: String,
        status: String,
        body: String,
        tokens: Long,
        versions: Map<String, FileVersion> = emptyMap(),
        scope: String?,
        complete: Boolean,
        captureComplete: Boolean,
        displayTruncated: Boolean,
        redacted: Boolean,
        artifact: Digest?,
    ): ToolOutcome {
        val header = EnvelopeHeader(
            resultAlias = alias, tool = "look", effectClass = EffectClass.R, versions = versions, stamp = null,
            truncated = displayTruncated, effects = Effects.None, flags = InstructionShape.detect(body).flags,
            runtime = RuntimeFields(
                actionId = actionId, status = status, candidateBefore = null, candidateAfter = null, scope = scope,
                completeness = when {
                    !captureComplete -> "incomplete"
                    displayTruncated -> "truncated"
                    complete -> "complete"
                    else -> "incomplete"
                },
                artifactRefs = listOfNotNull(artifact?.hex), captureComplete = captureComplete, displayTruncated = displayTruncated, redactionApplied = redacted,
            ),
        )
        return ToolOutcome(body, header, tokens = tokens)
    }
}

/** How many files with unresolved imports `look(importers)` names before eliding. */
private const val UNRESOLVED_SHOWN = 5
