package io.astrolabe.kb

import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.DeclarationKind
import io.astrolabe.id.Identities
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.store.Store
import kotlinx.serialization.Serializable

/** A behaviour-map locator `path::symbol@hash` (§7.5); `hash` is the file's atlas `hash8` when the map was made. */
@Serializable
public data class BmapLocator @JvmOverloads constructor(val path: String, val hash: String, val symbol: String? = null) {
    init {
        require(path.isNotBlank() && "::" !in path && '@' !in path) { "a locator path is a workspace path, got '$path'" }
        require(hash.isNotBlank() && hash.all { it.isLetterOrDigit() }) { "a locator carries the file hash it was made at" }
        require(symbol == null || (symbol.isNotBlank() && '@' !in symbol)) { "a locator symbol is a name" }
    }

    public val text: String get() = path + (symbol?.let { "::$it" } ?: "") + "@" + hash

    public companion object {
        private val FORM = Regex("""^([^@]+?)(?:::([^@]+))?@([0-9A-Za-z]+)$""")

        @JvmStatic
        public fun parse(text: String): BmapLocator {
            val m = requireNotNull(FORM.matchEntire(text.trim())) { "a locator is path::symbol@hash, got '$text'" }
            return BmapLocator(m.groupValues[1], m.groupValues[3], m.groupValues[2].ifEmpty { null })
        }
    }
}

/** One behaviour of a subsystem (§7.5): entry points → implementation → state read/written → callers → tests. */
@Serializable
public data class Behaviour @JvmOverloads constructor(
    val name: String,
    val entryPoints: List<BmapLocator>,
    val implementation: List<BmapLocator> = emptyList(),
    /** Named state (tables, files, caches); `null` = not known (an index cannot see it), distinct from none. */
    val stateRead: List<String>? = null,
    val stateWritten: List<String>? = null,
    val callers: List<BmapLocator> = emptyList(),
    val tests: List<BmapLocator> = emptyList(),
) {
    init {
        require(name.isNotBlank() && '#' !in name) { "a behaviour needs a name without '#'" }
        require(entryPoints.isNotEmpty()) { "behaviour '$name' names at least one entry point" }
    }

    public val locators: List<BmapLocator> get() = (entryPoints + implementation + callers + tests).distinct()
}

/** Where a map's content came from (§7.5): the index where possible, validated worker observations otherwise. */
@Serializable
public enum class BmapOrigin { Index, Observations }

/** `BMAP-<subsystem>` content (§7.5), linked from its note as the `procedure` module (D-112). */
@Serializable
public data class BehaviourMap(
    val subsystem: String,
    /** The workspace directory the subsystem lives under. */
    val root: String,
    val version: Int,
    val origin: BmapOrigin,
    val behaviours: List<Behaviour>,
) {
    init {
        require(subsystem.isNotBlank() && subsystem.none { it == '#' || it.isWhitespace() }) { "a subsystem name has no '#' or spaces" }
        require(version >= 1) { "a map version is ≥ 1" }
        require(behaviours.map { it.name }.toSet().size == behaviours.size) { "duplicate behaviours in $subsystem" }
    }

    public val noteId: String get() = "BMAP-$subsystem"
}

/** A locator checked against the current index (§7.5): `Changed` = the file moved on but the symbol is still there. */
public enum class LocatorStatus { Current, Changed, Unresolved }

/** A map with every locator validated at one atlas (compile time or `look` time). */
public data class BmapValidation(val map: BehaviourMap, val status: Map<BmapLocator, LocatorStatus>) {
    public fun count(s: LocatorStatus): Int = status.values.count { it == s }
}

/** How far a map is disclosed (§7.5): subsystem → behaviour; the symbol level is a locator, the source level `look(read)`. */
public enum class BmapLevel { Subsystem, Behaviour }

/** Supplies the admitted map of a subsystem to `look(bmap)`. */
public fun interface BmapSource {
    public fun find(subsystem: String): BehaviourMap?
}

/**
 * Behaviour maps as pure functions of records (§7.5, `[HYPOTHESIS]`): generation from the index, validation of every
 * locator against the current atlas, progressive rendering and folding of validated observations. A map assists
 * discovery and never replaces current source: nothing here registers coverage, and every render says so.
 */
public object BehaviourMaps {
    /** The `[R]` excerpt cap for the focus subsystem (§7.5). */
    public const val EXCERPT_MAX_TOKENS: Int = 300

    /** The `look(bmap, subsystem)` cap: the map at subsystem level is ~200–400 tokens (§7.5). */
    public const val SUBSYSTEM_MAX_TOKENS: Int = 400

    public const val NOT_SOURCE: String = "A map assists discovery and never replaces current source: read a locator before editing it."

    /**
     * A map generated from the tier-0 index (D-113): one behaviour per non-test source file under [root], its exported
     * declarations as entry points, the file as implementation, importers outside [root] as callers and the test files
     * that target it as tests; state is unknown to an index.
     */
    @JvmStatic
    @JvmOverloads
    public fun fromIndex(atlas: Atlas, subsystem: String, root: String, version: Int = 1): BehaviourMap {
        val prefix = root.trimEnd('/') + "/"
        val rows = atlas.rows.filter { it.path.startsWith(prefix) && !it.isTest && it.exports.isNotEmpty() }.sortedBy { it.path }
        val behaviours = rows.map { row ->
            val decls = atlas.outline(row.path).entries.filter { it.exported && it.kind != DeclarationKind.Import && it.kind != DeclarationKind.Export }
            val entries = row.exports.filter { name -> decls.any { it.name == name } }.ifEmpty { row.exports }
            val tests = atlas.rows.filter { it.isTest && row.path in it.testsFor }.map { BmapLocator(it.path, it.hash8) }
            val callers = atlas.importers(row.path).filter { !it.startsWith(prefix) }.mapNotNull { atlas.row(it) }.filter { !it.isTest }.map { BmapLocator(it.path, it.hash8) }
            Behaviour(
                name = row.path.removePrefix(prefix).substringBeforeLast('.').replace('#', '_'),
                entryPoints = entries.map { BmapLocator(row.path, row.hash8, it) },
                implementation = listOf(BmapLocator(row.path, row.hash8)),
                callers = callers, tests = tests,
            )
        }
        return BehaviourMap(subsystem, root.trimEnd('/'), version, BmapOrigin.Index, behaviours)
    }

    /** Every locator against [atlas]: gone file or symbol ⇒ `Unresolved`, other hash ⇒ `Changed`. */
    @JvmStatic
    public fun validate(map: BehaviourMap, atlas: Atlas): BmapValidation =
        BmapValidation(map, map.behaviours.flatMap { it.locators }.distinct().associateWith { status(it, atlas) })

    @JvmStatic
    public fun status(locator: BmapLocator, atlas: Atlas): LocatorStatus {
        val row = atlas.row(locator.path) ?: return LocatorStatus.Unresolved
        if (locator.symbol != null && atlas.outline(locator.path).entries.none { it.name == locator.symbol && it.kind != DeclarationKind.Import }) return LocatorStatus.Unresolved
        return if (row.hash8.startsWith(locator.hash) || locator.hash.startsWith(row.hash8)) LocatorStatus.Current else LocatorStatus.Changed
    }

    /**
     * Folds a worker-observed behaviour into the next version of [map] only when every locator resolves at [atlas]
     * (validated observations, §7.5). Returns the new map, or null with nothing folded when a locator is unresolved.
     */
    @JvmStatic
    public fun fold(map: BehaviourMap, observed: Behaviour, atlas: Atlas): BehaviourMap? {
        if (observed.locators.any { status(it, atlas) == LocatorStatus.Unresolved }) return null
        val behaviours = map.behaviours.filter { it.name != observed.name } + observed
        return map.copy(version = map.version + 1, origin = BmapOrigin.Observations, behaviours = behaviours.sortedBy { it.name })
    }

    /** The disclosure of [level]; [behaviour] names the one to open at [BmapLevel.Behaviour]. */
    @JvmStatic
    @JvmOverloads
    public fun render(validation: BmapValidation, level: BmapLevel, behaviour: String? = null): String {
        val map = validation.map
        val out = StringBuilder()
        out.append("${map.noteId} v${map.version} · ${map.root} · from ${map.origin.name.lowercase()} · locators ")
            .append("${validation.count(LocatorStatus.Current)} current, ${validation.count(LocatorStatus.Changed)} changed, ${validation.count(LocatorStatus.Unresolved)} unresolved\n")
        out.append(NOT_SOURCE).append('\n')
        when (level) {
            BmapLevel.Subsystem -> {
                for (b in map.behaviours) {
                    out.append("- ").append(b.name).append(": ").append(b.entryPoints.joinToString(", ") { loc(validation, it) })
                    out.append(" · impl ${b.implementation.size} · state ${state(b)} · callers ${b.callers.size} · tests ${b.tests.size}\n")
                }
                out.append("detail: look(bmap, \"${map.subsystem}#<behaviour>\"); source: look(read, path::symbol)\n")
            }
            BmapLevel.Behaviour -> {
                val b = map.behaviours.firstOrNull { it.name == behaviour }
                if (b == null) {
                    out.append("no behaviour '$behaviour' in ${map.noteId}; behaviours: ${map.behaviours.joinToString(", ") { it.name }}\n")
                } else {
                    out.append("behaviour ").append(b.name).append('\n')
                    section(out, "entry points", b.entryPoints, validation)
                    section(out, "implementation", b.implementation, validation)
                    out.append("state: ").append(state(b)).append('\n')
                    section(out, "callers", b.callers, validation)
                    section(out, "tests", b.tests, validation)
                }
            }
        }
        return out.toString()
    }

    /** The subsystem view cut to whole lines within [maxTokens] for the `[R]` focus excerpt (§7.5). */
    @JvmStatic
    @JvmOverloads
    public fun excerpt(validation: BmapValidation, estimator: TokenEstimator, maxTokens: Int = EXCERPT_MAX_TOKENS): String {
        val kept = ArrayList<String>()
        for (line in render(validation, BmapLevel.Subsystem).trimEnd('\n').split('\n')) {
            if (estimator.estimate((kept + line).joinToString("\n")).upperBoundTokens > maxTokens) break
            kept += line
        }
        return kept.joinToString("\n")
    }

    private fun section(out: StringBuilder, title: String, locators: List<BmapLocator>, validation: BmapValidation) {
        out.append(title).append(": ")
        out.append(if (locators.isEmpty()) "none mapped" else locators.joinToString(", ") { loc(validation, it) }).append('\n')
    }

    private fun loc(validation: BmapValidation, l: BmapLocator): String = l.text + when (validation.status[l]) {
        LocatorStatus.Unresolved -> " unresolved"
        LocatorStatus.Changed -> " (changed since mapped)"
        else -> ""
    }

    private fun state(b: Behaviour): String =
        if (b.stateRead == null && b.stateWritten == null) "unknown" else "r:${b.stateRead?.joinToString("/")?.ifEmpty { "none" } ?: "unknown"} w:${b.stateWritten?.joinToString("/")?.ifEmpty { "none" } ?: "unknown"}"
}

/** Behaviour maps over the store: the note is the compact index unit, the map its `procedure` blob; only admitted or stale notes answer. */
public class BmapStore(private val store: Store) : BmapSource {
    private val notes = Notes(store)

    /** Publishes [map] and returns its candidate note, superseding [supersedes] when this is a new version. */
    @JvmOverloads
    public fun candidate(map: BehaviourMap, summary: String, ids: Identities, supersedes: String? = null, origin: NoteOrigin = NoteOrigin(work = ids.work.value)): Note {
        val module = putModule(store, map.version, BehaviourMap.serializer(), map, ids)
        val body = "Behaviour map of ${map.root} (${map.behaviours.size} behaviours, from ${map.origin.name.lowercase()}) in module $PROCEDURE_MODULE@v${map.version}: " +
            "look(bmap, ${map.subsystem}). It assists discovery; read current source before editing."
        return Note(
            id = if (supersedes == null) map.noteId else "${map.noteId}-v${map.version}", kind = NoteKind.BMAP, status = NoteStatus.Candidate,
            summary = summary, body = body, scope = "subsystem:${map.subsystem}", supersedes = supersedes, origin = origin, modules = listOf(module),
        )
    }

    public fun load(note: Note): BehaviourMap? = if (note.kind == NoteKind.BMAP) getModule(store, note, BehaviourMap.serializer()) else null

    override fun find(subsystem: String): BehaviourMap? = notes.all()
        .filter { it.kind == NoteKind.BMAP && (it.status == NoteStatus.Admitted || it.status == NoteStatus.Stale) && it.scope == "subsystem:$subsystem" }
        .mapNotNull(::load).maxByOrNull { it.version }
}
