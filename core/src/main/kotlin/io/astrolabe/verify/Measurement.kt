package io.astrolabe.verify

import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
import io.astrolabe.id.Digest
import kotlinx.serialization.Serializable

/** A claim category that needs L4 evidence before it may be accepted (§8.2 L4). */
@Serializable
public enum class MeasurementClaim { Performance, AgentBehaviour, Safety }

/** How the measurement's spread was sampled (§8.2 L4 "variability"): never invented, only what the run declared. */
@Serializable
public enum class VarianceBasis { SingleRun, Repeated, Distribution }

/** The spread disclosure an L4 artifact carries; [runs] is how many observations [basis] rests on. */
@Serializable
public data class Variability(val basis: VarianceBasis, val runs: Int, val spread: String? = null) {
    init {
        require(runs >= 1) { "variability needs at least one observed run" }
        require(basis != VarianceBasis.SingleRun || runs == 1) { "single-run variability declares exactly one run" }
    }
}

/**
 * The evidence an L4 measurement gate publishes (§8.2 L4, TODO P5.3.2): [workload] names what was driven,
 * [environment] where, and [variability] discloses the spread across [Variability.runs] — the three facts a
 * performance, agent-behaviour or safety claim needs before it is accepted; [blob] is the raw measurement log
 * or dataset. Bound to the check that produced it via [checkId] and the receipt that ran it via [receiptId].
 */
@Serializable
public data class MeasurementArtifact(
    val checkId: String,
    val receiptId: String,
    val workload: String,
    val environment: String,
    val variability: Variability,
    val blob: Digest,
) {
    init {
        require(checkId.isNotBlank() && receiptId.isNotBlank()) { "a measurement artifact names its check and receipt" }
        require(workload.isNotBlank()) { "a measurement artifact names its workload" }
        require(environment.isNotBlank()) { "a measurement artifact names its environment" }
    }
}

/**
 * A project-defined measurement command (the runner hook, TODO P5.3.2 Build): never invented — an L4 gate
 * exists only when a project configures one, the same discipline `CHK-quality-gate` already applies to
 * `Config.qualityGates` (P3.6.2). [workload]/[environment] seed the labels every run of this command reports
 * in its [MeasurementArtifact] unless the run itself narrows them further.
 */
@Serializable
public data class MeasurementCommand(val command: Command, val workload: String, val environment: String) {
    init {
        require(workload.isNotBlank() && environment.isNotBlank()) { "a measurement command names its workload and environment" }
    }
}

/**
 * The L4 measurement variant of a `quality` check (§8.2 L4, TODO P5.3.2): [seed] is the runner hook turning
 * project-defined [MeasurementCommand]s into registered [Check]s — `CheckKind.Quality` with an id the checker
 * dispatches like any other named command, `[O]` until a project configures one; [validate] is the claim-matched
 * gap list a performance, agent-behaviour or safety claim's completion must clear before it may cite this check.
 * Wiring these checks into the scheduler's trigger table and the disposable environment they need is P5.3 (the
 * QA cell's `[O gate: requires a disposable environment]` applies equally here); this task fixes the contract.
 */
public object MeasurementGate {
    public const val PREFIX: String = "CHK-l4-measurement"

    @JvmStatic
    public fun checkId(name: String): String = if (name.isEmpty()) PREFIX else "$PREFIX-$name"

    /** One [Check] per configured [MeasurementCommand], named [names] in order (defaulting to `1, 2, …`); run only on demand (D-125-style masking: no automatic trigger without a disposable environment, P5.3). */
    @JvmStatic
    @JvmOverloads
    public fun seed(commands: List<MeasurementCommand>, names: List<String> = emptyList()): List<Check> {
        require(names.isEmpty() || names.size == commands.size) { "one name per command, or none" }
        return commands.mapIndexed { i, gate ->
            Check(
                id = checkId(names.getOrElse(i) { (i + 1).toString() }),
                kind = CheckKind.Quality,
                selector = Selector.Named(gate.command),
                inputClosure = Closure.Unknown,
                costClass = CostClass.Expensive,
                trigger = Trigger.OnDemand,
                command = gate.command,
            )
        }
    }

    /**
     * Gaps a [claim] finds against [artifact] (§8.2 L4): no artifact at all is the first gap; otherwise every
     * [MeasurementArtifact] field must be present — the type system already refuses a blank workload or
     * environment, so only the disclosure a caller could still omit through a stale or foreign artifact is
     * checked here (wrong check, wrong receipt). Empty ⇔ the claim may cite this artifact as its L4 evidence.
     */
    @JvmStatic
    public fun validate(claim: MeasurementClaim, checkId: String, receiptId: String, artifact: MeasurementArtifact?): List<String> {
        if (artifact == null) return listOf("${claim.name.lowercase()} claims need an L4 measurement artifact with workload, environment and variability (§8.2)")
        val gaps = ArrayList<String>()
        if (artifact.checkId != checkId) gaps += "artifact is evidence for check ${artifact.checkId}, not $checkId"
        if (artifact.receiptId != receiptId) gaps += "artifact is bound to receipt ${artifact.receiptId}, not $receiptId"
        return gaps
    }
}
