package io.astrolabe.verify

import io.astrolabe.contract.Contract
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.Outcome
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import io.astrolabe.store.Layout
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.workspace.ShadowRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.io.path.relativeTo

/** One characterization output (a CLI golden, an API fixture) as it was at `s0`: its path and the blob of its raw bytes. */
@Serializable
public data class CharacterizationOutput(val path: String, val blob: Digest, val sizeBytes: Long)

/** The baseline receipt of one affected suite at `s0`. */
@Serializable
public data class SnapshotReceipt(val checkId: String, val receiptId: String, val outcome: Outcome)

/**
 * The behaviour snapshot of §8.9 item 1: the baseline receipts over the affected suites plus the project's
 * characterization outputs, recorded as blobs at [s0]. Equivalence evidence (P3.5.2) compares `s_n` against it.
 */
@Serializable
public data class BehaviourSnapshot(
    val s0: CandidateId,
    val suites: List<SnapshotReceipt>,
    val characterization: List<CharacterizationOutput>,
    val limitations: List<String> = emptyList(),
) {
    /** Rendered once at capture; bounded. */
    public fun render(maxOutputs: Int = 5): String {
        val suitesText = if (suites.isEmpty()) "no affected suite" else suites.joinToString(", ") { "${it.checkId} ${it.outcome.name.lowercase()} (${it.receiptId})" }
        val outputs = characterization.take(maxOutputs).joinToString(", ") { "${it.path}@${it.blob.hash8}" }
        val more = if (characterization.size > maxOutputs) ", +${characterization.size - maxOutputs} more" else ""
        val limits = if (limitations.isEmpty()) "" else " · limits: " + limitations.joinToString(" · ")
        return "behaviour snapshot @${s0.hash8}: $suitesText · ${characterization.size} characterization outputs" +
            (if (characterization.isEmpty()) "" else " ($outputs$more)") + limits
    }
}

/**
 * Captures and stores behaviour snapshots. The suites run through [Baseline] on the captured initial candidate
 * (never the live tree); the characterization outputs are read from that same materialized `s0` and published
 * as blobs before the record row (artifact-before-row). The record lives in `packets` (kind [KIND]) with a
 * `Boundary` journal line; one snapshot per attempt.
 */
public class BehaviourSnapshots(
    private val baseline: Baseline?,
    private val shadowRef: ShadowRef,
    private val layout: Layout,
    private val blobs: BlobStore,
    private val store: Store,
    private val journal: Journal,
    private val ids: Identities,
    private val idGen: IdGen,
    private val clock: Clock,
    private val scratch: ScratchPolicy = ScratchPolicy(),
) {
    public suspend fun capture(contract: Contract, checks: Checks, s0: CandidateId, timeoutSeconds: Long = 600): BehaviourSnapshot {
        val limits = ArrayList<String>()
        val receipts = ArrayList<SnapshotReceipt>()
        var candidate: Path? = null
        val suites = affectedSuites(checks)
        if (baseline == null && suites.isNotEmpty()) limits += "no baseline runner: the affected suites (${suites.joinToString { it.id }}) did not run"
        if (baseline != null) {
            for (suite in suites) {
                val result = baseline.run(suite, contract.version, s0, timeoutSeconds)
                receipts += SnapshotReceipt(suite.id, result.receipt.receiptId, result.receipt.outcome)
                candidate = if (result.materialized.ok) result.candidateDir else null
                result.receipt.limits.forEach { limits += "${suite.id}: ${it.detail}" }
            }
        }
        val root = candidate ?: materialize(limits)
        val outputs = ArrayList<CharacterizationOutput>()
        if (root != null) {
            val files = characterizationPaths(root)
            for (path in files.take(MAX_OUTPUTS)) {
                val bytes = try {
                    Files.readAllBytes(root.resolve(path))
                } catch (failure: IOException) {
                    limits += "$path: unreadable (${failure.message})"
                    continue
                }
                if (bytes.size > MAX_OUTPUT_BYTES) {
                    limits += "$path: ${bytes.size} bytes exceeds the $MAX_OUTPUT_BYTES-byte cap; not recorded"
                    continue
                }
                outputs += CharacterizationOutput(path, blobs.put(bytes, BlobKind.OUTPUT, ids), bytes.size.toLong())
            }
            if (files.size > MAX_OUTPUTS) limits += "${files.size - MAX_OUTPUTS} characterization outputs beyond the $MAX_OUTPUTS cap were not recorded"
        }
        val snapshot = BehaviourSnapshot(s0, receipts, outputs, limits)
        record(snapshot)
        journal.append(
            JournalEvent(
                idGen.next("ev"), ids, null, JournalKind.Boundary,
                refs = receipts.map { it.receiptId } + outputs.map { it.blob.hex }, text = snapshot.render(), at = clock.instant(),
            ),
        )
        return snapshot
    }

    /** The snapshot of this attempt, or `null` when none was captured. */
    public fun latest(): BehaviourSnapshot? = store.db.query(
        "SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid DESC LIMIT 1",
        ids.work, ids.attempt, KIND,
    ) { JSON.decodeFromString(BehaviourSnapshot.serializer(), it.string("body")) }.firstOrNull()

    private fun record(snapshot: BehaviourSnapshot) {
        store.db.tx { tx ->
            tx.execute(
                "INSERT INTO packets (id, work_id, attempt_id, candidate_id, context_id, kind, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                idGen.next("snap"), ids.work, ids.attempt, ids.candidate, ids.context, KIND, Migrations.SCHEMA_VERSION, clock.instant(),
                JSON.encodeToString(BehaviourSnapshot.serializer(), snapshot),
            )
        }
    }

    /** `s0` exported under `candidates/` and verified against its manifest (D-53); `null` when it does not match. */
    private fun materialize(limits: MutableList<String>): Path? {
        val dir = layout.candidates.resolve("${ids.work.value}-${ids.attempt.value}-s0")
        if (Files.exists(dir)) Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        val materialized = shadowRef.materialize(0, dir)
        materialized.limitations.forEach { limits += "materialize: $it" }
        if (!materialized.ok) {
            limits += "exported candidate differs from its manifest: ${materialized.mismatches.joinToString(", ")}; no characterization output recorded (D-53)"
            return null
        }
        return dir
    }

    private fun characterizationPaths(root: Path): List<String> {
        val found = ArrayList<String>()
        try {
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    val relative = file.relativeTo(root).joinToString("/") { it.toString() }
                    if (!scratch.isScratch(relative) && isCharacterization(relative)) found += relative
                }
            }
        } catch (ignored: IOException) {
            // A partially readable tree records what it could; the limits name unreadable files individually.
        }
        return found.sorted()
    }

    public companion object {
        public const val KIND: String = "behaviour-snapshot"
        public const val MAX_OUTPUTS: Int = 200
        public const val MAX_OUTPUT_BYTES: Int = 1 shl 20

        private val JSON = Json { encodeDefaults = true }

        /** The affected suites at `s0`: the full suite when registered, else every unit or acceptance check with a command. */
        @JvmStatic
        public fun affectedSuites(checks: Checks): List<Check> {
            checks[Checks.FULL]?.takeIf { it.command != null }?.let { return listOf(it) }
            return checks.all().filter { it.command != null && (it.kind == CheckKind.Full || it.kind == CheckKind.Unit || it.kind == CheckKind.Acceptance) }
        }

        /**
         * The path conventions of characterization outputs: `golden*` directories, `*.golden` files,
         * `__snapshots__` directories and `fixtures/api` trees.
         */
        @JvmStatic
        public fun isCharacterization(relative: String): Boolean {
            val segments = relative.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return false
            val dirs = segments.dropLast(1)
            val name = segments.last()
            if (name.endsWith(".golden")) return true
            if (dirs.any { it.startsWith("golden", ignoreCase = true) || it == "__snapshots__" }) return true
            return dirs.zipWithNext().any { (a, b) -> a == "fixtures" && b == "api" }
        }
    }
}
