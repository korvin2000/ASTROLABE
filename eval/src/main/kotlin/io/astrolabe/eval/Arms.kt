package io.astrolabe.eval

import io.astrolabe.Astrolabe
import io.astrolabe.AttemptConfig
import io.astrolabe.Config
import io.astrolabe.Controls
import io.astrolabe.Flags
import io.astrolabe.contract.Shape
import io.astrolabe.kb.KbInjection

/**
 * How an arm varies the solver: a production `[O]` flag, a mandatory control a research arm disables (D-48), the
 * shape cap, or a research switch the harness does not expose in `Config` (recorded, not runnable offline, D-221).
 */
public enum class ArmKind { Flag, Control, Shape, Research }

/**
 * The one table of evaluation arms (§19.5, D-48): every ablation, one at a time with the model constant, plus every
 * production `Flags` switch's own gate. The first level is the production default. Research arms live here, never
 * in production `Config`; the level that disables a mandatory control ([controlLevel]) is runnable but never
 * promotion-eligible (I-18).
 */
public enum class EvalArm(
    public val wire: String,
    public val kind: ArmKind,
    levels: List<String>,
    public val flag: String? = null,
    public val control: String? = null,
    public val controlLevel: String? = null,
    /** `§19.5` for a listed ablation, `[O] flag` for a production switch whose gate §19.5 does not list. */
    public val source: String = "§19.5",
) {
    CellBoundaries("cell boundaries vs HELM pressure rebuild", ArmKind.Research, listOf("cell boundaries", "HELM pressure rebuild")),
    WorksetSeeds("workset seeds on/off", ArmKind.Research, listOf("on", "off")),
    DeltaPlusAbsolute("Δ+absolute vs delta-only", ArmKind.Control, listOf("Δ+absolute", "delta-only"), control = "deltaPlusAbsolute", controlLevel = "delta-only"),
    MarkThenStub("mark-then-stub vs immediate stub", ArmKind.Research, listOf("mark-then-stub", "immediate stub")),
    EarlyStubbing("R_max early stubbing", ArmKind.Research, listOf("on", "off")),
    BatchedEviction("batched vs pressure-only eviction inside short cells", ArmKind.Research, listOf("batched", "pressure-only")),
    ContractStateSplit("contract/STATE split vs STATE-only", ArmKind.Research, listOf("split", "STATE-only")),
    ConditionalStateOps("conditional STATE ops", ArmKind.Research, listOf("on", "off")),
    Gauge("gauge", ArmKind.Research, listOf("on", "off")),
    ImpactNudge("impact nudge", ArmKind.Research, listOf("on", "off")),
    Checker("sync checker vs none vs async watchers", ArmKind.Flag, listOf("sync", "async"), flag = "asyncChecker"),
    BlastRadius("blast radius vs package tests vs full suite", ArmKind.Research, listOf("blast radius", "package tests", "full suite")),
    ReuseProofs("closures with reuse proofs vs stamp-coarse invalidation", ArmKind.Research, listOf("reuse proofs", "stamp-coarse")),
    Reserve("reserve on/off", ArmKind.Control, listOf("on", "off"), control = "reserve", controlLevel = "off"),
    TransformPath("transform path vs anchored-only on the 40-file refactor", ArmKind.Research, listOf("transform path", "anchored-only")),
    TestIntegrityGuard("test-integrity guard on/off (with injected weakening)", ArmKind.Control, listOf("on", "off"), control = "testIntegrityGuard", controlLevel = "off"),
    RefactorMode("refactor mode on/off", ArmKind.Research, listOf("on", "off")),
    Precompile("boundary pre-compilation on/off", ArmKind.Flag, listOf("off", "on"), flag = "precompile"),
    CalibrationPrior("calibration prior on/off", ArmKind.Flag, listOf("off", "on"), flag = "calibrationPrior"),
    KnowledgeInjection("KB injection off / frozen / live", ArmKind.Flag, listOf("off", "frozen", "live"), flag = "kbInjection"),
    NotesPlacement("notes in [R] vs [A] only", ArmKind.Research, listOf("[R]", "[A] only")),
    WeightedRetrieval("role-only vs task/dependency-weighted retrieval", ArmKind.Research, listOf("role-only", "weighted")),
    BehaviourMaps("behaviour maps on/off", ArmKind.Research, listOf("on", "off")),
    SkillsFiltering("skills module filtering vs whole skills", ArmKind.Research, listOf("module filtering", "whole skills")),
    ProbeCells("probe cells vs in-window exploration", ArmKind.Research, listOf("probe cells", "in-window exploration")),
    ReviewScope("review at increment scope only vs both scopes vs none", ArmKind.Research, listOf("increment scope", "both scopes", "none")),
    JudgeContext("judge same-context vs fresh vs fresh + symmetric evidence", ArmKind.Research, listOf("same-context", "fresh", "fresh + symmetric evidence")),
    FunctionRouting("function routing with refusal vs all-high vs clamped", ArmKind.Control, listOf("routing with refusal", "all-high", "clamped"), control = "floors", controlLevel = "clamped"),
    CapsuleRepair("capsule repair vs kernel-only vs deterministic-only", ArmKind.Research, listOf("capsule repair", "kernel-only", "deterministic-only")),
    AlternativeAttempt("alternative attempt vs refinement", ArmKind.Research, listOf("alternative attempt", "refinement")),
    Shapes("S0 vs S1 vs S2 on matched strata", ArmKind.Shape, listOf("S0", "S1", "S2")),
    S3Writers("sequential vs S3 under equal resources", ArmKind.Flag, listOf("sequential", "S3"), flag = "s3Writers"),
    LanguageService("language-service adapter on/off", ArmKind.Flag, listOf("off", "on"), flag = "languageService"),
    DenseRetrieval("dense retrieval on/off after measured lexical misses", ArmKind.Flag, listOf("off", "on"), flag = "denseRetrieval"),
    TreeSitterIndex("tree-sitter index on/off", ArmKind.Flag, listOf("off", "on"), flag = "treeSitterIndex", source = "[O] flag"),
    GeneratedTools("generated tools on/off", ArmKind.Flag, listOf("off", "on"), flag = "generatedTools", source = "[O] flag"),
    SkillsPromotion("skills promotion on/off", ArmKind.Flag, listOf("off", "on"), flag = "skillsPromotion", source = "[O] flag"),
    QaCell("QA cell on/off", ArmKind.Flag, listOf("off", "on"), flag = "qaCell", source = "[O] flag"),
    L4Gates("L4 gates on/off", ArmKind.Flag, listOf("off", "on"), flag = "l4Gates", source = "[O] flag"),
    OtelExport("OpenTelemetry span export on/off", ArmKind.Flag, listOf("off", "on"), flag = "otelExport", source = "[O] flag"),
    WorthTestEstimate("worth-test estimate on/off", ArmKind.Flag, listOf("off", "on"), flag = "worthTestEstimate", source = "[O] flag"),
    ;

    public val levels: List<String> = immutable(levels)

    init {
        require(this.levels.size >= 2 && this.levels.distinct().size == this.levels.size)
        require((kind == ArmKind.Flag) == (flag != null))
        require((kind == ArmKind.Control) == (control != null && controlLevel in this.levels && controlLevel != this.levels.first()))
    }

    /** False only for the level that disables a mandatory control. */
    public fun promotionEligible(level: String): Boolean = level in levels && level != controlLevel

    /** Runnable offline: flags, controls and shapes are expressible in a frozen attempt; research switches are not (D-221). */
    public val runnable: Boolean get() = kind != ArmKind.Research
}

/** One arm at one level, frozen: [attempt] is null exactly when the arm is not runnable offline. */
public data class ArmConfig(
    val arm: EvalArm,
    val level: String,
    val attempt: AttemptConfig?,
    val maxShape: Shape?,
) {
    val promotionEligible: Boolean get() = arm.promotionEligible(level) && attempt?.let { it.production && it.controls.allEnabled } != false
}

public object EvalArms {
    /** Configures [arm] at [level] over the production [base]; a disabled control yields a research attempt (D-48). */
    @JvmStatic
    @JvmOverloads
    public fun configure(arm: EvalArm, level: String, base: Config, harnessVersion: String = Astrolabe.VERSION): ArmConfig {
        require(level in arm.levels) { "unknown level '$level' of ${arm.wire}" }
        val index = arm.levels.indexOf(level)
        return when (arm.kind) {
            ArmKind.Research -> ArmConfig(arm, level, null, null)
            ArmKind.Shape -> ArmConfig(arm, level, AttemptConfig.freeze(base, harnessVersion), Shape.valueOf(level))
            ArmKind.Flag -> ArmConfig(arm, level, AttemptConfig.freeze(base.withFlags(set(base.flags, arm.flag!!, index)), harnessVersion), null)
            ArmKind.Control -> if (level == arm.controlLevel) {
                ArmConfig(arm, level, AttemptConfig.researchArm(base, disable(arm.control!!), harnessVersion), null)
            } else ArmConfig(arm, level, AttemptConfig.freeze(base, harnessVersion), null)
        }
    }

    /** Every production `Flags` switch by its serialized name; the arms table must cover each exactly once. */
    @JvmStatic
    public fun flagNames(): List<String> = Flags.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName) }

    /** The documentation table, rendered from [EvalArm] only (eval/README.md carries it verbatim). */
    @JvmStatic
    public fun table(): String = buildString {
        appendLine("| Arm | Kind | Levels (first = production) | Flag or control | Promotion-ineligible level | Source |")
        appendLine("|---|---|---|---|---|---|")
        EvalArm.entries.forEach { a ->
            appendLine("| ${a.wire} | ${a.kind} | ${a.levels.joinToString(" / ")} | ${a.flag?.let { "`Flags.$it`" } ?: a.control?.let { "`Controls.$it`" } ?: "—"} | ${a.controlLevel ?: "—"} | ${a.source} |")
        }
    }

    private fun set(flags: Flags, name: String, index: Int): Flags = when (name) {
        "precompile" -> flags.copy(precompile = index == 1)
        "calibrationPrior" -> flags.copy(calibrationPrior = index == 1)
        "treeSitterIndex" -> flags.copy(treeSitterIndex = index == 1)
        "languageService" -> flags.copy(languageService = index == 1)
        "denseRetrieval" -> flags.copy(denseRetrieval = index == 1)
        "generatedTools" -> flags.copy(generatedTools = index == 1)
        "skillsPromotion" -> flags.copy(skillsPromotion = index == 1)
        "asyncChecker" -> flags.copy(asyncChecker = index == 1)
        "qaCell" -> flags.copy(qaCell = index == 1)
        "l4Gates" -> flags.copy(l4Gates = index == 1)
        "s3Writers" -> flags.copy(s3Writers = index == 1)
        "otelExport" -> flags.copy(otelExport = index == 1)
        "worthTestEstimate" -> flags.copy(worthTestEstimate = index == 1)
        "kbInjection" -> flags.copy(kbInjection = KbInjection.entries[index])
        else -> throw IllegalArgumentException("no Flags switch '$name'")
    }

    private fun disable(control: String): Controls = when (control) {
        "reserve" -> Controls(reserve = false)
        "testIntegrityGuard" -> Controls(testIntegrityGuard = false)
        "deltaPlusAbsolute" -> Controls(deltaPlusAbsolute = false)
        "floors" -> Controls(floors = false)
        "lifecycleControls" -> Controls(lifecycleControls = false)
        else -> throw IllegalArgumentException("no mandatory control '$control'")
    }
}
