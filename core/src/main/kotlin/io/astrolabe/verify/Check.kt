package io.astrolabe.verify

import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.Outcome
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
public enum class Applicability { Current, Stale, Unknown }

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
)

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
)

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
     * §8.4 applicability, recomputed for every check with a result: `current = (stamp_after == stamp_now)` with the
     * check definition unchanged since the run; a different stamp is `stale` (until P3.1.2 can attach a reuse proof
     * for an unchanged closure), a changed definition is `stale` whatever the stamp (FX-16), and a missing current
     * stamp is `unknown`. A stamp that returned to the tested candidate makes the result current again: the
     * receipt is evidence about those exact bytes (L7). Eligibility for the final tree (D-45) stays on the receipt.
     */
    public fun refresh(stampNow: CandidateId?): List<Check> = checks.values.filter { it.last != null }.map { check ->
        val last = check.last!!
        val next = when {
            stampNow == null -> last.copy(applicability = Applicability.Unknown, staleReason = "current stamp unknown")
            last.definitionVersion != check.definitionVersion ->
                last.copy(applicability = Applicability.Stale, staleReason = "check definition changed since ${last.receiptId}")
            last.stamp != stampNow -> last.copy(
                applicability = Applicability.Stale,
                staleReason = last.staleReason?.takeIf { last.applicability == Applicability.Stale }
                    ?: "candidate moved @${last.stamp.digest.hash8} → @${stampNow.digest.hash8} (no reuse proof)",
            )
            else -> last.copy(applicability = Applicability.Current, staleReason = null)
        }
        if (next == last) check else check.copy(last = next).also { checks[check.id] = it }
    }

    public fun register(check: Check): Check {
        require(check.id !in checks) { "check ${check.id} already registered" }
        checks[check.id] = check
        return check
    }

    public companion object {
        public const val TYPES_TOUCHED: String = "CHK-types-touched"
        public const val LINT: String = "CHK-lint"
        public const val FULL: String = "CHK-full"

        public fun acceptId(acceptanceId: String): String = "CHK-accept-$acceptanceId"

        @JvmStatic
        public fun empty(): Checks = Checks(LinkedHashMap())

        /** The S0 seed set (§8.1 layer table): fast checks on touched files, every `run:` acceptance, the full suite. */
        @JvmStatic
        public fun seed(contract: Contract, commands: RunnerCommands, touched: Set<String> = emptySet()): Checks {
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
            return registry
        }
    }
}
