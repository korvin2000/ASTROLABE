package io.astrolabe.kb

import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.store.BlobKind
import io.astrolabe.store.Store
import io.astrolabe.workspace.PathPattern
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** One module of a [Skill] (§12.2 `modules[]{applies_to, mandatory}`): the unit filters keep or drop. */
@Serializable
public data class SkillModule @JvmOverloads constructor(
    val id: String,
    val body: String,
    /** Role names the module is written for; `*` = every role. Filters optional modules only (D-112). */
    val appliesTo: Set<String> = setOf("*"),
    val mandatory: Boolean = false,
) {
    init {
        require(id.isNotBlank() && id != PROCEDURE_MODULE) { "a skill module needs an id other than '$PROCEDURE_MODULE'" }
        require(body.isNotBlank()) { "skill module '$id' has no body" }
        require(appliesTo.isNotEmpty()) { "skill module '$id' applies to no role" }
    }
}

/** When a skill applies: path globs over the changed paths or terms in the change text (D-112). */
@Serializable
public data class SkillTrigger @JvmOverloads constructor(val paths: List<String> = emptyList(), val terms: List<String> = emptyList()) {
    init {
        require(paths.isNotEmpty() || terms.isNotEmpty()) { "a skill trigger names paths or terms" }
    }

    /** The changed paths this trigger matches; empty for a terms-only match. */
    public fun matchedPaths(change: StateChange): List<String> = change.paths.filter { p -> paths.any { PathPattern.matches(it, p) } }

    public fun matches(change: StateChange): Boolean =
        matchedPaths(change).isNotEmpty() || terms.any { it.isNotBlank() && it.lowercase() in change.text.lowercase() }

    public fun render(): String = listOfNotNull(
        paths.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "paths "),
        terms.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "terms "),
    ).joinToString(" · ")
}

/**
 * A skill (§12.2): a compact procedure — trigger, prerequisites, ordered steps, expected artifacts, verification,
 * failure exit, freshness, token budget and modules. It is procedure, never authority: it has no field that grants a
 * capability or marks a requirement complete (B §10.3). [authority] names the contract/ADR ids it follows, used only
 * to resolve overlaps against the task's authority. A version is immutable: views are cached per (version, role).
 */
@Serializable
public data class Skill @JvmOverloads constructor(
    val id: String,
    val version: Int,
    val trigger: SkillTrigger,
    val prerequisites: List<String>,
    val steps: List<String>,
    val expectedArtifacts: List<String>,
    val verification: List<String>,
    val failureExit: String,
    /** What keeps the procedure current (a source revision, a contract version, an anchor). */
    val freshness: String,
    val tokenBudget: Int,
    val modules: List<SkillModule> = emptyList(),
    /** Invariants that survive every filter with the prerequisites and mandatory modules (§12.2, F06). */
    val invariants: List<String> = emptyList(),
    val authority: List<String> = emptyList(),
) {
    init {
        require(id.startsWith("SKILL-") && id.length > "SKILL-".length) { "skill id '$id' must be SKILL-<name>" }
        require(version >= 1) { "a skill version is ≥ 1" }
        require(steps.isNotEmpty() && steps.none { it.isBlank() }) { "a skill has ordered, non-blank steps" }
        require(failureExit.isNotBlank()) { "a skill names its failure exit" }
        require(tokenBudget > 0) { "a skill's token budget is positive" }
        require(modules.map { it.id }.toSet().size == modules.size) { "duplicate module ids in $id" }
    }

    /** Content digest of this version: a changed body under the same version is refused by [SkillViews]. */
    val digest: String get() = Digest.ofUtf8(NOTE_JSON.encodeToString(serializer(), this)).hex
}

/** Kinds of meaningful state change at which triggers are evaluated — never per turn (§12.2, IM §8.4; D-112). */
public enum class StateChangeKind { IncrementOpened, FocusChanged, CheckFailed, ContractAmended, Replanned }

/** A meaningful state change: the paths it concerns and its text (increment title, failing check, amendment). */
public data class StateChange @JvmOverloads constructor(val kind: StateChangeKind, val paths: List<String> = emptyList(), val text: String = "") {
    init {
        require(paths.isNotEmpty() || text.isNotBlank()) { "a state change concerns paths or text" }
    }
}

/**
 * A role's view of a skill (§12.2). [core] is what survives every filter: the procedure head, prerequisites,
 * invariants and every mandatory module; [optional] are the modules still selectable; [omitted] names each dropped
 * module with why (`role`, `filter`, `budget`), so an omission never reads as absence.
 */
public data class SkillView(
    val skillId: String,
    val version: Int,
    val role: String,
    val core: String,
    val optional: List<SkillModule>,
    val omitted: Map<String, String>,
) {
    /** A narrower view: optional modules kept only if in [keep] (null = all) and within [maxTokens]; [core] is untouched. */
    @JvmOverloads
    public fun filter(estimator: TokenEstimator, keep: Set<String>? = null, maxTokens: Int? = null): SkillView {
        val kept = ArrayList<SkillModule>()
        val dropped = LinkedHashMap(omitted)
        var used = 0L
        for (m in optional) {
            val cost = estimator.estimate(moduleText(m)).upperBoundTokens
            when {
                keep != null && m.id !in keep -> dropped[m.id] = "filter"
                maxTokens != null && used + cost > maxTokens -> dropped[m.id] = "budget"
                else -> { kept += m; used += cost }
            }
        }
        return copy(optional = kept, omitted = dropped)
    }

    public fun render(): String = buildString {
        append(core)
        for (m in optional) append(moduleText(m))
        if (omitted.isNotEmpty()) append("omitted modules (not absent; kb.skill for the full procedure): ")
            .append(omitted.entries.joinToString(", ") { "${it.key} (${it.value})" }).append('\n')
    }

    public companion object {
        @JvmStatic
        public fun moduleText(m: SkillModule): String = "module ${m.id}${if (m.mandatory) " (mandatory)" else ""}:\n${m.body.trimEnd()}\n"
    }
}

/** Rendered role views cached per `(skill id, version, role)` (§12.2); a version is immutable. */
public class SkillViews(private val estimator: TokenEstimator) {
    private data class Key(val id: String, val version: Int, val role: String)

    private val cache = HashMap<Key, Pair<String, SkillView>>()

    /** Views rendered so far. */
    public val size: Int @Synchronized get() = cache.size

    @Synchronized
    public fun view(skill: Skill, role: String): SkillView {
        val key = Key(skill.id, skill.version, role)
        val digest = skill.digest
        cache[key]?.let { (known, view) ->
            require(known == digest) { "${skill.id}@v${skill.version} changed without a new version" }
            return view
        }
        val view = render(skill, role)
        cache[key] = digest to view
        return view
    }

    private fun render(skill: Skill, role: String): SkillView {
        val core = buildString {
            append("${skill.id}@v${skill.version} for $role: procedure, not authority (grants no capability, completes no requirement)\n")
            append("trigger: ").append(skill.trigger.render()).append('\n')
            list("prerequisites", skill.prerequisites)
            list("invariants", skill.invariants)
            append("steps:\n")
            skill.steps.forEachIndexed { i, s -> append("${i + 1}. ").append(s).append('\n') }
            if (skill.expectedArtifacts.isNotEmpty()) append("expected artifacts: ").append(skill.expectedArtifacts.joinToString("; ")).append('\n')
            list("verification", skill.verification)
            append("failure exit: ").append(skill.failureExit).append('\n')
            append("freshness: ").append(skill.freshness).append('\n')
            // F06: mandatory modules survive every filter, the role's applies_to included.
            for (m in skill.modules.filter { it.mandatory }) append(SkillView.moduleText(m))
        }
        val applicable = skill.modules.filter { !it.mandatory && ("*" in it.appliesTo || role in it.appliesTo) }
        val roleOmitted = skill.modules.filter { !it.mandatory && it !in applicable }.associate { it.id to "role" }
        val remaining = (skill.tokenBudget - estimator.estimate(core).upperBoundTokens).coerceAtLeast(0)
        val view = SkillView(skill.id, skill.version, role, core, applicable, emptyMap()).filter(estimator, maxTokens = remaining.toInt())
        return view.copy(omitted = roleOmitted + view.omitted)
    }

    private fun StringBuilder.list(title: String, items: List<String>) {
        if (items.isEmpty()) return
        append(title).append(":\n")
        for (i in items) append("- ").append(i).append('\n')
    }
}

/** An explicit overlap between two triggered skills (§12.2); [winner] is null when the task's authority does not decide it. */
public data class SkillConflict(val first: String, val second: String, val paths: List<String>, val winner: String?, val reason: String) {
    public val line: String get() = "skill conflict $first vs $second on ${paths.joinToString(", ")}: ${winner?.let { "$it applies ($reason)" } ?: "unresolved ($reason)"}"
}

public data class SkillResolution(val active: List<Skill>, val conflicts: List<SkillConflict>)

/**
 * Skill selection as pure functions of records (§12.2): triggers are evaluated only at a [StateChange], and two skills
 * that both claim a changed path are resolved against the task's authority — the one citing it applies; otherwise
 * both stay active and the conflict is rendered, never merged silently (D-112).
 */
public object Skills {
    @JvmStatic
    public fun triggered(skills: List<Skill>, change: StateChange): List<Skill> = skills.filter { it.trigger.matches(change) }.sortedBy { it.id }

    @JvmStatic
    public fun resolve(skills: List<Skill>, change: StateChange, authority: Set<String>): SkillResolution {
        val triggered = triggered(skills, change)
        val conflicts = ArrayList<SkillConflict>()
        val losers = HashSet<String>()
        for ((i, a) in triggered.withIndex()) for (b in triggered.drop(i + 1)) {
            val shared = a.trigger.matchedPaths(change).intersect(b.trigger.matchedPaths(change).toSet()).sorted()
            if (shared.isEmpty()) continue
            val aCites = a.authority.filter { it in authority }
            val bCites = b.authority.filter { it in authority }
            conflicts += when {
                aCites.isNotEmpty() && bCites.isEmpty() -> SkillConflict(a.id, b.id, shared, a.id, "cites ${aCites.joinToString(", ")}").also { losers += b.id }
                bCites.isNotEmpty() && aCites.isEmpty() -> SkillConflict(a.id, b.id, shared, b.id, "cites ${bCites.joinToString(", ")}").also { losers += a.id }
                else -> SkillConflict(a.id, b.id, shared, null, "the task's authority decides neither; follow the contract and report it as Open")
            }
        }
        return SkillResolution(triggered.filter { it.id !in losers }, conflicts)
    }
}

/** The one module id under which a `SKILL`/`BMAP` note links its whole record as a blob (D-112). */
public const val PROCEDURE_MODULE: String = "procedure"

/**
 * Skill records over the store (§4.5): the note stays a compact index unit and links the procedure as a content-addressed
 * blob (artifact before row); the curator admits the note as usual, so a skill becomes visible only once admitted.
 */
public class SkillStore(private val store: Store) {
    /** Publishes [skill]'s procedure and returns its candidate note, ready for `Queue.enqueue`. */
    @JvmOverloads
    public fun candidate(skill: Skill, summary: String, scope: String, ids: Identities, origin: NoteOrigin = NoteOrigin(work = ids.work.value)): Note {
        val module = putModule(store, skill.version, Skill.serializer(), skill, ids)
        val modules = skill.modules.joinToString(", ") { it.id + if (it.mandatory) "*" else "" }
        val body = "Procedure in module $PROCEDURE_MODULE@v${skill.version}: ${skill.steps.size} steps" +
            (if (modules.isEmpty()) "" else "; modules (* mandatory): $modules").take(240) + ". Read it with kb.skill(${skill.id})."
        return Note(
            id = skill.id, kind = NoteKind.SKILL, status = NoteStatus.Candidate, summary = summary, body = body, scope = scope,
            basis = NoteBasis(requirementRefs = skill.authority), validity = NoteValidity(invalidationTrigger = skill.freshness),
            origin = origin, modules = listOf(module),
        )
    }

    /** The skill a `SKILL` note links; null for another kind or a note without a procedure module. */
    public fun load(note: Note): Skill? {
        if (note.kind != NoteKind.SKILL) return null
        val skill = getModule(store, note, Skill.serializer()) ?: return null
        require(skill.id == note.id) { "${note.id} links the procedure of ${skill.id}" }
        return skill
    }
}

internal fun <T> putModule(store: Store, version: Int, serializer: KSerializer<T>, value: T, ids: Identities): NoteModule {
    val digest = store.blobs.put(NOTE_JSON.encodeToString(serializer, value).toByteArray(Charsets.UTF_8), BlobKind.MODULE, ids)
    return NoteModule(PROCEDURE_MODULE, version, digest.hex)
}

internal fun <T> getModule(store: Store, note: Note, serializer: KSerializer<T>): T? {
    val module = note.modules.firstOrNull { it.id == PROCEDURE_MODULE } ?: return null
    return NOTE_JSON.decodeFromString(serializer, store.blobs.get(Digest(module.digest)).toString(Charsets.UTF_8))
}
