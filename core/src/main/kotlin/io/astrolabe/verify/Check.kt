package io.astrolabe.verify

import io.astrolabe.atlas.PackageCommands
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.ClosureCompleteness
import io.astrolabe.evidence.ClosureManifest
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.Receipt
import io.astrolabe.id.CandidateId
import io.astrolabe.id.CanonicalEncoding
import io.astrolabe.id.Digest
import io.astrolabe.workspace.ChangeListener
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.VersionChange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class CheckKind { Syntax, Type, Lint, Unit, Integration, Acceptance, Full, Quality, Review }

/** What a check runs over (§8.1). */
@Serializable
public sealed interface Selector {
    @Serializable
    @SerialName("touched")
    public object Touched : Selector {
        override fun toString(): String = "touched"
    }

    @Serializable
    @SerialName("blast")
    public object Blast : Selector {
        override fun toString(): String = "blast"
    }

    @Serializable
    @SerialName("named")
    public data class Named(val command: Command) : Selector {
        override fun toString(): String = "named(${command.text})"
    }

    @Serializable
    @SerialName("all")
    public object All : Selector {
        override fun toString(): String = "all"
    }
}

@Serializable
public enum class CostClass { Inline, Fast, Slow, Expensive }

@Serializable
public enum class Trigger { EveryEdit, EndOfTurn, StepBoundary, RiskAboveTheta, IncrementEnd, CampaignEnd, OnDemand }

/** Applicability of a historical result to the current candidate (§8.4): computed, never stored on the receipt. */
@Serializable
public enum class Applicability {
    Current,
    Stale,
    Unknown,
    ;

    public companion object {
        /**
         * §8.1/§8.4 applicability of [receipt] to [now]: `unknown` without a current stamp; `stale` when the check
         * definition (argv/cwd/selector/parser) changed (FX-16); `current` on the tested stamp. On a moved stamp the
         * result stays `current` only with a [ReuseProof]: the pinned closure manifest is complete and re-pins to the
         * same digest over the current bytes (so a moved path in the closure, FX-54, or a new package member is
         * stale), the inputs did not move during the check, and the verifier version and a known environment id
         * (toolchain, lockfiles, external fixtures) are unchanged. An unknown or partial closure is stale: rerun at the
         * conservative containing scope. The receipt's outcome is never touched.
         */
        @JvmStatic
        public fun of(receipt: Receipt, now: CandidateNow): ApplicabilityVerdict {
            val stamp = now.stamp ?: return ApplicabilityVerdict(Unknown, "current stamp unknown")
            if (receipt.checkDefinitionVersion != now.definitionVersion) {
                return ApplicabilityVerdict(Stale, "check definition changed since ${receipt.receiptId}")
            }
            if (receipt.stampAfter == stamp) return ApplicabilityVerdict(Current)
            val tested = receipt.closureManifest
            val repinned = now.manifest
            val refusal = when {
                tested == null || receipt.inputClosure == Closure.Unknown || tested.completeness == ClosureCompleteness.Unknown ->
                    "closure unknown: rerun at the containing scope"
                tested.completeness != ClosureCompleteness.Complete ->
                    "closure partial (${tested.exclusions.joinToString("; ")}): a path list alone proves nothing"
                receipt.testedInputs.mutatedDuringCheck.isNotEmpty() -> "inputs moved during ${receipt.receiptId}"
                receipt.verifierVersion != now.verifierVersion -> "verifier version ${receipt.verifierVersion} → ${now.verifierVersion}"
                now.envId == null || !now.envKnown -> "environment unknown"
                receipt.envId != now.envId -> "environment moved"
                repinned == null -> "closure could not be re-pinned"
                repinned.digest != tested.digest -> "closure moved: ${tested.moved(repinned).joinToString(", ")}"
                else -> null
            }
            if (refusal != null) {
                return ApplicabilityVerdict(Stale, "candidate moved @${receipt.stampAfter.hash8} → @${stamp.hash8}; $refusal")
            }
            return ApplicabilityVerdict(Current, reuse = ReuseProof(receipt.receiptId, stamp, tested!!.pathsAtVersions, tested.digest))
        }
    }
}

/**
 * The candidate a receipt's applicability is computed against (§8.4): the current stamp and what reuse needs
 * unchanged — the check's current [definitionVersion], the [verifierVersion], the environment id, and the receipt's
 * closure re-pinned over the current bytes ([manifest]; `null` when it could not be or need not be).
 */
public data class CandidateNow @JvmOverloads constructor(
    val stamp: CandidateId?,
    val definitionVersion: Digest,
    val verifierVersion: String,
    val envId: Digest? = null,
    val envKnown: Boolean = false,
    val manifest: ClosureManifest? = null,
)

/** A computed applicability, with the reason when not current and the reuse proof when current on a moved stamp. */
public data class ApplicabilityVerdict @JvmOverloads constructor(
    val applicability: Applicability,
    val reason: String? = null,
    val reuse: ReuseProof? = null,
)

/**
 * The recorded reuse proof of §8.1 (`reuse_of: rcpt-19, closure_unchanged: [paths@hashes]`): [reuseOf]'s result
 * applies to [stamp] because its complete closure — [closureUnchanged] at raw-byte hashes, [manifest] the digest of
 * the whole manifest with membership and lockfiles — is unchanged under the same definition, verifier and environment.
 */
@Serializable
public data class ReuseProof(
    val reuseOf: String,
    val stamp: CandidateId,
    val closureUnchanged: Map<String, String>,
    val manifest: Digest,
)

/**
 * The check's last receipt as the registry caches it. [stamp] is the receipt's `stamp_after` and
 * [definitionVersion] the [Check.definitionVersion] the check had when it ran; [applicability] is the
 * computed view ([Checks.refresh], [Checks.onChange]) and [outcome] the historical fact that never changes.
 */
@Serializable
public data class LastResult(
    val receiptId: String,
    val stamp: CandidateId,
    val definitionVersion: Digest,
    val outcome: Outcome,
    val counts: Counts?,
    val applicability: Applicability,
    val reuseOf: String? = null,
    val staleReason: String? = null,
    val reuseProof: ReuseProof? = null,
) {
    /** This result with [verdict]'s applicability, reason and reuse proof; the outcome stays. */
    public fun applied(verdict: ApplicabilityVerdict): LastResult =
        copy(applicability = verdict.applicability, staleReason = verdict.reason, reuseOf = verdict.reuse?.reuseOf, reuseProof = verdict.reuse)
}

/**
 * A registered check (§8.1). [definitionVersion] hashes the definition, argv/cwd/selector and parser policy so
 * a changed command or parser invalidates every earlier receipt (FX-16). Closures join the coherence protocol.
 */
@Serializable
public data class Check(
    val id: String,
    val kind: CheckKind,
    val selector: Selector,
    val inputClosure: Closure,
    val costClass: CostClass,
    val trigger: Trigger,
    val acceptanceIds: List<String> = emptyList(),
    val command: Command? = null,
    val parserPolicy: String = "shaper/1",
    val last: LastResult? = null,
) {
    init {
        require(id.isNotBlank()) { "check needs an id" }
    }

    val definitionVersion: Digest
        get() = Digest.ofUtf8(
            CanonicalEncoding.encode(
                "check-definition", 1,
                listOf(
                    "id" to id, "kind" to kind.name, "selector" to selector.toString(),
                    "argv" to (command?.argv?.joinToString("") ?: ""), "cwd" to (command?.cwd ?: ""),
                    "parser" to parserPolicy, "acceptance" to acceptanceIds.joinToString(","),
                ),
            ),
        )

    val required: Boolean get() = kind == CheckKind.Acceptance || acceptanceIds.isNotEmpty()
}

/** Runner commands as sniffed from the repository manifests (the atlas package produces them; the registry consumes argv only). */
@Serializable
public data class RunnerCommands(
    val test: Command? = null,
    val build: Command? = null,
    val lint: Command? = null,
    val typecheck: Command? = null,
) {
    public companion object {
        /** The sniffed declarations of one package as registry commands; a package below the root keeps its `cwd`. */
        @JvmStatic
        public fun of(pkg: PackageCommands): RunnerCommands {
            val cwd = pkg.dir.takeIf { it != PackageCommands.ROOT }
            fun command(argv: List<String>?) = argv?.let { Command(it, cwd) }
            return RunnerCommands(command(pkg.test), command(pkg.build), command(pkg.lint), command(pkg.typecheck))
        }
    }
}

/**
 * The check registry (§8.1, TODO P1.7.1). Seeded from sniffed commands and contract acceptance: `CHK-types-touched`,
 * `CHK-lint`, `CHK-accept-<AC>`, `CHK-full`. S0 closures are `Known(touched paths)` for touched selectors and
 * `Unknown` otherwise; the scheduler refines them (P3.1.1).
 *
 * It is the verification horizon of the coherence protocol (§4.4): [onChange] marks the checks whose closure
 * a change moved, and [refresh] recomputes applicability against the current stamp (§8.4). Both leave the
 * historical outcome untouched.
 */
public class Checks private constructor(private val checks: LinkedHashMap<String, Check>) : ChangeListener {
    public fun all(): List<Check> = checks.values.toList()

    public operator fun get(id: String): Check? = checks[id]

    public fun byTrigger(trigger: Trigger): List<Check> = checks.values.filter { it.trigger == trigger }

    public fun forAcceptance(acceptanceId: String): List<Check> = checks.values.filter { acceptanceId in it.acceptanceIds }

    public fun required(): List<Check> = checks.values.filter { it.required }

    /** Records a result; the historical outcome never changes, only [LastResult.applicability] does. */
    public fun record(id: String, last: LastResult): Check {
        val check = checks[id] ?: throw IllegalArgumentException("unknown check $id")
        return check.copy(last = last).also { checks[id] = it }
    }

    /** Marks a check's last result stale (§4.4 verification horizon); a check with an unknown closure is stale conservatively. */
    public fun markStale(id: String, reason: String): Check? {
        val check = checks[id] ?: return null
        val last = check.last ?: return check
        if (last.applicability == Applicability.Stale) return check
        return check.copy(last = last.copy(applicability = Applicability.Stale, staleReason = reason)).also { checks[id] = it }
    }

    /** Checks whose closure intersects [changedPaths] or is unknown. */
    public fun affectedBy(changedPaths: Collection<String>): List<Check> = checks.values.filter { c ->
        when (val closure = c.inputClosure) {
            is Closure.Known -> closure.paths.any { it in changedPaths }
            is Closure.Package -> changedPaths.any { it == closure.path || it.startsWith(closure.path.trimEnd('/') + "/") }
            Closure.Unknown -> true
        }
    }

    /**
     * §4.4 verification horizon: a moved path marks every check whose closure contains it stale, a check with an
     * unknown closure stale conservatively, and a moved lock/dependency file every check (it is an input of the
     * environment id, whatever the closure says). The reason names the path and the cause.
     */
    override fun onChange(change: VersionChange) {
        val environment = change.path.substringAfterLast('/') in EnvFingerprint.LOCK_FILE_NAMES
        val affected = if (environment) checks.values.toList() else affectedBy(listOf(change.path))
        for (check in affected) {
            val reason = when {
                environment -> "environment moved: ${change.path} (${change.cause})"
                check.inputClosure == Closure.Unknown -> "closure unknown; ${change.path} changed (${change.cause})"
                else -> "closure moved: ${change.path} (${change.cause})"
            }
            markStale(check.id, reason)
        }
    }

    /**
     * §8.4 applicability, recomputed for every check with a result. [assess] computes it from the receipt
     * ([Applicability.of], attaching a reuse proof, P3.1.2); without it, or when it has no receipt (`null`):
     * `current = (stamp_after == stamp_now)` with the check definition unchanged since the run, a different stamp is
     * `stale` (no reuse proof), a changed definition is `stale` whatever the stamp (FX-16), and a missing current
     * stamp is `unknown`. A stamp that returned to the tested candidate makes the result current again: the
     * receipt is evidence about those exact bytes (L7). Eligibility for the final tree (D-45) stays on the receipt.
     */
    @JvmOverloads
    public fun refresh(stampNow: CandidateId?, assess: ((Check, LastResult) -> ApplicabilityVerdict?)? = null): List<Check> = checks.values.filter { it.last != null }.map { check ->
        val last = check.last!!
        val verdict = stampNow?.let { assess?.invoke(check, last) }
        val next = when {
            stampNow == null -> last.applied(ApplicabilityVerdict(Applicability.Unknown, "current stamp unknown"))
            verdict != null -> last.applied(verdict)
            last.definitionVersion != check.definitionVersion ->
                last.applied(ApplicabilityVerdict(Applicability.Stale, "check definition changed since ${last.receiptId}"))
            last.stamp != stampNow -> last.applied(
                ApplicabilityVerdict(
                    Applicability.Stale,
                    last.staleReason?.takeIf { last.applicability == Applicability.Stale }
                        ?: "candidate moved @${last.stamp.digest.hash8} → @${stampNow.digest.hash8} (no reuse proof)",
                ),
            )
            else -> last.applied(ApplicabilityVerdict(Applicability.Current))
        }
        if (next == last) check else check.copy(last = next).also { checks[check.id] = it }
    }

    /** Registers [check] or replaces the one with its id, keeping the last result (its applicability is recomputed). */
    public fun replace(check: Check): Check {
        val next = check.copy(last = checks[check.id]?.last)
        checks[check.id] = next
        return next
    }

    public fun register(check: Check): Check {
        require(check.id !in checks) { "check ${check.id} already registered" }
        checks[check.id] = check
        return check
    }

    public companion object {
        public const val TYPES_TOUCHED: String = "CHK-types-touched"
        public const val TESTS_BLAST: String = "CHK-tests-blast"
        public const val LINT: String = "CHK-lint"
        public const val FULL: String = "CHK-full"
        public const val REVIEW_INCREMENT: String = "CHK-review-inc"
        public const val REVIEW_CAMPAIGN: String = "CHK-review-campaign"
        public const val QUALITY_GATE: String = "CHK-quality-gate"

        public fun acceptId(acceptanceId: String): String = "CHK-accept-$acceptanceId"

        @JvmStatic
        public fun empty(): Checks = Checks(LinkedHashMap())

        /**
         * The S0 seed set (§8.1 layer table): fast checks on touched files, every `run:` acceptance, the full suite,
         * and one `CHK-quality-gate` check per configured [qualityGates] command (none are ever invented).
         */
        @JvmStatic
        @JvmOverloads
        public fun seed(contract: Contract, commands: RunnerCommands, touched: Set<String> = emptySet(), qualityGates: List<Command> = emptyList()): Checks {
            val registry = Checks(LinkedHashMap())
            commands.typecheck?.let {
                registry.register(Check(TYPES_TOUCHED, CheckKind.Type, Selector.Touched, Closure.Known(touched), CostClass.Fast, Trigger.EndOfTurn, command = it))
            }
            commands.lint?.let {
                registry.register(Check(LINT, CheckKind.Lint, Selector.Touched, Closure.Known(touched), CostClass.Fast, Trigger.EndOfTurn, command = it))
            }
            contract.acceptance.filterIsInstance<Acceptance.Run>().forEach { a ->
                registry.register(
                    Check(
                        acceptId(a.id), CheckKind.Acceptance, Selector.Named(a.command), Closure.Unknown, CostClass.Slow, Trigger.IncrementEnd,
                        acceptanceIds = listOf(a.id), command = a.command,
                    ),
                )
            }
            commands.test?.let {
                registry.register(Check(FULL, CheckKind.Full, Selector.All, Closure.Unknown, CostClass.Expensive, Trigger.CampaignEnd, command = it))
            }
            qualityGates.forEachIndexed { i, command ->
                val id = if (i == 0) QUALITY_GATE else "$QUALITY_GATE-${i + 1}"
                registry.register(Check(id, CheckKind.Quality, Selector.Named(command), Closure.Unknown, CostClass.Expensive, Trigger.CampaignEnd, command = command))
            }
            return registry
        }
    }
}
