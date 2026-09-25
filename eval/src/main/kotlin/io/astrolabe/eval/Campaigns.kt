package io.astrolabe.eval

import io.astrolabe.Astrolabe
import io.astrolabe.AttemptConfig
import io.astrolabe.Config
import io.astrolabe.Flags
import io.astrolabe.contract.Shape
import io.astrolabe.id.Digest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path

/** The §19.1 comparators. B0 and B-HELM need live providers and other harnesses: configuration only (D-28). */
public enum class Variant(public val wire: String, public val composition: String, public val isolates: String, public val live: Boolean, public val maxShape: Shape?) {
    B0("B0", "plain single-model tool loop (read/write/bash), append-only transcript, summarise-when-full, same checks and authority", "the bar everything must clear", true, null),
    BHelm("B-HELM", "HELM as specified in judje-2 §7, with J1's corrections", "what the cell's changes add", true, null),
    B1("B1", "S0: ASTROLABE cell alone", "the deterministic execution core", false, Shape.S0),
    B2("B2", "S1: + campaign controller, increments, compiler, seeds, KB, role switching", "context continuity and the increment boundary", false, Shape.S1),
    B3("B3", "S2: + probe/review cells, routing with refusal, escalation, capsule repair, alternative attempts", "independent contexts and routing without a standing team", false, Shape.S2),
    B4("B4", "S3: + parallel writers, ownership, integrator", "concurrency itself", false, Shape.S3),
    B5("B5", "+ generated tools, procedural learning, learned routing corrector, async checkers, dense retrieval", "the most experimental extensions", false, Shape.S3),
    Target("Target", "chosen components composed, with frozen offline improvements", "the complete mid-weight architecture", false, null),
}

/** A comparator's frozen configuration; [attempt] is null for a live-only comparator (config only, never run offline). */
public data class VariantConfig(val variant: Variant, val attempt: AttemptConfig?, val maxShape: Shape?)

public object Variants {
    /**
     * B1–B4 are the production [base] under a shape cap (B4 also enables `s3Writers`); B5 adds the experimental
     * flags; Target takes the frozen [target] configuration (D-221). Every offline comparator is a production attempt.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(variant: Variant, base: Config, target: Config? = null, harnessVersion: String = Astrolabe.VERSION): VariantConfig {
        if (variant.live) return VariantConfig(variant, null, null)
        val config = when (variant) {
            Variant.B4 -> base.withFlags(base.flags.copy(s3Writers = true))
            Variant.B5 -> base.withFlags(base.flags.copy(s3Writers = true, generatedTools = true, skillsPromotion = true,
                calibrationPrior = true, asyncChecker = true, denseRetrieval = true))
            Variant.Target -> requireNotNull(target) { "Target needs its frozen configuration" }
            else -> base
        }
        return VariantConfig(variant, AttemptConfig.freeze(config, harnessVersion), variant.maxShape)
    }
}

/** The only status an offline build records for a live gate (I-19); a measured outcome is P7's. */
public enum class LiveGateStatus { UNMEASURED }

/** A live gate with what must exist before it can run and the evidence its report must carry (D-28, I-19). */
public class LiveGate internal constructor(public val id: String, public val claim: String, prerequisites: List<String>, evidence: List<String>) {
    public val status: LiveGateStatus = LiveGateStatus.UNMEASURED
    public val prerequisites: List<String> = immutable(prerequisites)
    public val evidence: List<String> = immutable(evidence)
}

public object LiveGates {
    private val EVIDENCE = listOf("campaign manifest fingerprint", "variant attempt fingerprints", "workload split fingerprint",
        "integrity verdict (hidden acceptance, answer removal, memory reset/freeze, holdout)", "fixture report with every invariant measured and zero",
        "baseline and candidate scorecards with complete billing", "paired bounds overall and complex", "one-off investment and repayment volume",
        "promotion report and verdict")
    private val LIVE = listOf("live provider transport and usage normalizer (P7)", "frozen campaign manifest", "pilot variance estimate and confirmatory sizing")

    /** Every live gate, all `UNMEASURED`: the P7 campaign gates, then one promotion gate per production flag. */
    @JvmStatic
    public fun all(): List<LiveGate> = buildList {
        add(LiveGate("b1-vs-b0", "B1 vs B0/B-HELM", LIVE + "B0 and B-HELM harnesses at equal model, tools, checks and budget", EVIDENCE))
        add(LiveGate("b2-ge-b1", "B2 ≥ B1 on multi-session and refactor strata", LIVE, EVIDENCE))
        add(LiveGate("kb-warm-vs-cold", "warm vs cold KB", LIVE + "memory reset for cold runs, frozen equal memory for warm runs", EVIDENCE))
        add(LiveGate("routing-savings", "function routing savings vs all-high", LIVE + "tier-table calibration", EVIDENCE))
        add(LiveGate("s3-vs-sequential", "S3 vs sequential under equal resources", LIVE, EVIDENCE))
        add(LiveGate("tier-table-calibration", "tier-table calibration suite", LIVE + "labelled calibration tasks", EVIDENCE))
        add(LiveGate("judge-calibration", "judge calibration on labelled fixtures with real models", LIVE + "labelled review fixtures", EVIDENCE))
        add(LiveGate("offline-improvements", "offline improvement experiments", LIVE + "frozen candidates with multiple selection accounted", EVIDENCE))
        EvalArm.entries.filter { it.flag != null }.forEach {
            add(LiveGate("flag.${it.flag}", "promotion of ${it.wire}", LIVE + "arm ${it.name} paired against the production default", EVIDENCE))
        }
    }
}

/** Mutable memory policy of a campaign (§19.2): reset for cold starts, frozen and equal for warm runs. */
public enum class MemoryMode { Cold, Warm }

/**
 * A frozen campaign manifest (§19.2, invariant 12): harness version, the comparators' and arms' frozen attempts (flags
 * included), strata, repositories and the validated workload partition by repository/task family/time. Construction
 * validates the partition through [WorkloadSplit]; [fingerprint] binds everything and is the design's `manifest`.
 */
public class CampaignManifest(
    public val id: String,
    public val harnessVersion: String,
    variants: List<VariantConfig>,
    arms: List<ArmConfig>,
    public val workload: WorkloadDesign,
    assignment: Map<WorkloadTrialKey, WorkloadPartition>,
    public val memory: MemoryMode,
) {
    public val variants: List<VariantConfig> = immutable(variants.sortedBy { it.variant.ordinal })
    public val arms: List<ArmConfig> = immutable(arms.sortedWith(compareBy({ it.arm.ordinal }, { it.arm.levels.indexOf(it.level) })))
    public val split: WorkloadSplitResult = WorkloadSplit.validate(workload, workload.fingerprint, assignment)

    init {
        label(id)
        require(id.all { it.isLetterOrDigit() || it in "-_." }) { "manifest id '$id' is not a file name" }
        require(this.variants.map { it.variant }.distinct().size == this.variants.size) { "duplicate comparator" }
        require(this.arms.map { it.arm to it.level }.distinct().size == this.arms.size) { "duplicate arm level" }
        require(split.status == WorkloadSplitStatus.Feasible || split.status == WorkloadSplitStatus.Optimal) {
            "workload partition is ${split.status}: ${split.issues}"
        }
        (this.variants.mapNotNull { it.attempt } + this.arms.mapNotNull { it.attempt }).forEach {
            require(it.harnessVersion == harnessVersion) { "attempt frozen for harness ${it.harnessVersion}, manifest $harnessVersion" }
        }
    }

    public val strata: Map<String, Boolean> get() = workload.policy.strata
    public val repositories: List<String> = immutable(workload.tasks.mapNotNull { it.repository }.distinct().sorted())
    public val assignment: Map<WorkloadTrialKey, WorkloadPartition> get() = split.assignment

    public val fingerprint: Digest = fingerprint("campaign-manifest", buildList {
        addAll(listOf(id, harnessVersion, memory, workload.fingerprint, this@CampaignManifest.variants.size))
        this@CampaignManifest.variants.forEach { addAll(listOf(it.variant.wire, it.attempt?.fingerprint, it.maxShape)) }
        add(this@CampaignManifest.arms.size)
        this@CampaignManifest.arms.forEach { addAll(listOf(it.arm.name, it.level, it.attempt?.fingerprint, it.maxShape)) }
        val keys = split.assignment.keys.sortedWith(compareBy(WorkloadTrialKey::task, WorkloadTrialKey::repetition))
        add(keys.size)
        keys.forEach { addAll(listOf(it.task, it.repetition, split.assignment.getValue(it))) }
    })

    /** The derived JSON record written under `campaigns/`. */
    public fun json(): String = JSON.encodeToString(buildJsonObject {
        put("kind", "astrolabe.campaign-manifest/1")
        put("id", id); put("fingerprint", fingerprint.hex); put("harnessVersion", harnessVersion); put("memory", memory.name)
        put("workload", workload.fingerprint.hex); put("workloadPolicy", workload.policy.version); put("split", split.status.name)
        putJsonArray("strata") { strata.forEach { (s, complex) -> add(buildJsonObject { put("id", s); put("complex", complex) }) } }
        putJsonArray("repositories") { repositories.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("variants") { this@CampaignManifest.variants.forEach { v -> add(buildJsonObject {
            put("variant", v.variant.wire); put("live", v.variant.live); put("maxShape", v.maxShape?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("attempt", v.attempt?.fingerprint?.hex?.let(::JsonPrimitive) ?: JsonNull)
            put("flags", v.attempt?.config?.flags?.let { JSON.encodeToJsonElement(Flags.serializer(), it) } ?: JsonNull)
        }) } }
        putJsonArray("arms") { this@CampaignManifest.arms.forEach { a -> add(buildJsonObject {
            put("arm", a.arm.wire); put("level", a.level); put("runnable", a.attempt != null); put("promotionEligible", a.promotionEligible)
            put("attempt", a.attempt?.fingerprint?.hex?.let(::JsonPrimitive) ?: JsonNull)
        }) } }
        putJsonArray("partitions") {
            split.assignment.entries.sortedWith(compareBy({ it.key.task }, { it.key.repetition })).forEach { (k, p) -> add(buildJsonObject {
                put("task", k.task); put("repetition", k.repetition); put("partition", p.name)
            }) }
        }
    })

    private companion object {
        val JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}

/** The `campaigns/` directory: one file per manifest id; a written manifest is frozen and never rewritten differently. */
public object CampaignManifests {
    /** Writes [manifest] to `<dir>/<id>.json`; the same fingerprint again is a no-op, a different one is refused. */
    @JvmStatic
    public fun freeze(dir: Path, manifest: CampaignManifest): Path {
        val file = dir.resolve("${manifest.id}.json")
        recorded(file)?.let { existing ->
            check(existing == manifest.fingerprint.hex) { "campaign ${manifest.id} is frozen with fingerprint $existing" }
            return file
        }
        Files.createDirectories(dir)
        Files.writeString(file, manifest.json())
        return file
    }

    /** True when `<dir>/<id>.json` records exactly [manifest]'s fingerprint. */
    @JvmStatic
    public fun matches(dir: Path, manifest: CampaignManifest): Boolean = recorded(dir.resolve("${manifest.id}.json")) == manifest.fingerprint.hex

    private fun recorded(file: Path): String? = if (!Files.exists(file)) null
        else Json.parseToJsonElement(Files.readString(file)).jsonObject["fingerprint"]?.jsonPrimitive?.content
}
