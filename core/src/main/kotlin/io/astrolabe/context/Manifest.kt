package io.astrolabe.context

import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.id.Identities
import io.astrolabe.kb.Note
import io.astrolabe.provider.Profile
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

/** Why the context was (re)built (§6.5). */
@Serializable
public enum class BoundaryReason(public val wire: String) {
    @SerialName("done") Done("done"),
    @SerialName("partial") Partial("partial"),
    @SerialName("replan") Replan("replan"),
    @SerialName("pressure") Pressure("pressure"),
    @SerialName("resume") Resume("resume"),
}

@Serializable
public data class ManifestNote(val id: String, val kind: String, val status: String)

@Serializable
public data class ManifestSeed(val path: String, val range: String, val hash: String)

@Serializable
public data class ManifestOmission(val unit: String, val reason: String)

/** The §6.1 budget arithmetic, in tokens; `null` where effective history was unknown (D-06). */
@Serializable
public data class ManifestArithmetic(
    val limit: Long,
    val knownFixed: Long,
    val available: Long?,
    val selected: Long,
    val total: Long?,
    val policy: String,
)

/**
 * The context manifest (§6.5, P2.3.2), one per compiled context and persisted per cell: what was injected (notes,
 * seeds, units), what was left out and why, the budget arithmetic and the estimate — so "absent" and "misread" are
 * distinguishable afterwards. [actualUsage] is the first response's reported input tokens, `null` until it arrives
 * and while the provider did not report it.
 */
@Serializable
public data class Manifest(
    val id: String,
    val incrementId: String,
    val work: String,
    val attempt: String,
    val cell: String,
    val contractVersion: Int,
    val registerVersionIn: Int?,
    val notesInjected: List<ManifestNote>,
    val seeds: List<ManifestSeed>,
    val skills: List<String>,
    val profile: String,
    val effort: String?,
    val arithmetic: ManifestArithmetic,
    val selectedUnits: List<String>,
    val omissions: List<ManifestOmission>,
    val continuationLineage: List<String>,
    val reductionOps: List<String>,
    val estimatedTokens: Long,
    val actualUsage: Long? = null,
    val boundaryReason: BoundaryReason? = null,
    /** `ready`, `needs_rescoping` or `needs_more_evidence`. */
    val outcome: String,
) {
    public companion object {
        /** The manifest of [compiled] for the cell in [ids]; [notes]/[seeds] are the compile's inputs, filtered to what was selected. */
        @JvmStatic
        @JvmOverloads
        public fun of(
            id: String,
            compiled: Compiled,
            increment: Increment,
            contract: Contract,
            ids: Identities,
            profile: Profile,
            inputs: CompileInputs = CompileInputs(),
            registerVersionIn: Int? = null,
            boundaryReason: BoundaryReason? = null,
            effort: String? = null,
        ): Manifest {
            val selection = compiled.selection
            val selected = selection.selectedIds.map { it.value }
            val injected = inputs.notes.filter { "note.${it.id}" in selected }
            val seeds = inputs.seeds?.shown.orEmpty().filterIndexed { i, _ -> "seed-$i" in selected }
            val a = selection.arithmetic
            return Manifest(
                id = id,
                incrementId = increment.id,
                work = ids.work.value,
                attempt = ids.attempt.value,
                cell = checkNotNull(ids.context) { "a manifest belongs to a cell" }.value,
                contractVersion = contract.version,
                registerVersionIn = registerVersionIn,
                notesInjected = injected.map(Note::manifestNote),
                seeds = seeds.map { ManifestSeed(it.path, it.range.toString(), it.version.digest.hex) },
                skills = inputs.skills.filter { "skill.${it.id}" in selected }.map { skill ->
                    "${skill.id}@v${skill.version}" + skill.modules.filter { "skill.${skill.id}.${it.id}" in selected }.joinToString("") { "+${it.id}" }
                }.distinct().sorted(),
                profile = profile.id,
                effort = effort,
                arithmetic = ManifestArithmetic(
                    a.limitTokens.toLong(), a.knownFixedTokens.toLong(), a.availableTokens?.toLong(), a.selectedTokens.toLong(),
                    a.totalTokens?.toLong(), selection.policyVersion,
                ),
                selectedUnits = selected,
                omissions = selection.omissions.map { (unit, reason) -> ManifestOmission(unit.value, reason.name.lowercase()) },
                continuationLineage = increment.cells.map { it.value }.filter { it != ids.context!!.value },
                reductionOps = emptyList(),
                estimatedTokens = (a.totalTokens ?: a.knownFixedTokens + a.selectedTokens).toLong(),
                boundaryReason = boundaryReason,
                outcome = when (compiled) {
                    is Compiled.Ready -> "ready"
                    is Compiled.NeedsRescoping -> "needs_rescoping"
                    is Compiled.NeedsEvidence -> "needs_more_evidence"
                },
            )
        }
    }
}

private fun Note.manifestNote(): ManifestNote = ManifestNote(id, kind.name, status.wire)

/** Manifests in the store (`manifests`, cell-runtime-owned): one row per compiled context. */
public class SqliteManifests(private val store: Store, private val clock: Clock) {
    public fun save(ids: Identities, manifest: Manifest): Unit = store.db.tx { tx ->
        tx.execute(
            "INSERT OR REPLACE INTO manifests (id, work_id, attempt_id, candidate_id, context_id, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            manifest.id, ids.work, ids.attempt, ids.candidate, ids.context, Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(Manifest.serializer(), manifest),
        )
    }

    public fun get(id: String): Manifest? =
        store.db.query("SELECT body FROM manifests WHERE id = ?", id) { JSON.decodeFromString(Manifest.serializer(), it.string("body")) }.firstOrNull()

    /** Fills [Manifest.actualUsage] once, from the first response; a later call never overwrites it. */
    public fun recordFirstUsage(ids: Identities, id: String, inputTokens: Long) {
        val manifest = get(id) ?: return
        if (manifest.actualUsage != null) return
        save(ids, manifest.copy(actualUsage = inputTokens))
    }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
