package io.astrolabe.context

import io.astrolabe.AttemptConfig
import io.astrolabe.cell.Role
import io.astrolabe.cell.RoleTexts
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.provider.Profile
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.telemetry.PrecompileOutcome
import io.astrolabe.verify.Check
import io.astrolabe.verify.CostClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

/**
 * The full compile-input fingerprint of one `[K]` (§6.6, F06): the candidate stamp, the contract and authority
 * revision, the selected increment, the carry-forward and register version, the note, contracts-index and skill
 * versions, the role and its policy-text version (D-38), the profile and the frozen policy. Matching the tree stamp
 * alone is insufficient: checks, amendments or knowledge admission change non-tree inputs. Beyond the §6.6 list it
 * also pins the `[R]` prime, the rules and calibration texts, the estimator, the output limit and the pinned
 * messages (D-83): every input the compile reads, so a match implies a byte-identical compile.
 */
@Serializable
public data class Fingerprint(
    val stamp: String,
    val contractVersion: Int,
    val authorization: String,
    val increment: String,
    val incrementDefinition: String,
    val registerVersion: Int?,
    val carry: String?,
    val notes: List<String>,
    val contractsIndex: String?,
    val skills: List<String>,
    val role: String,
    val roleTextVersion: String,
    val profile: String,
    val policy: String,
    val prime: String,
    val rules: String?,
    val calibration: String?,
    val estimator: String,
    val maxOutputTokens: Int,
    val pinned: String,
) {
    /** One digest over every field, for journal lines and manifests. */
    val digest: Digest get() = Digest.ofUtf8(JSON.encodeToString(serializer(), this))

    /** The fields on which this and [other] differ, by name, in declaration order: the miss reason. */
    public fun differences(other: Fingerprint): List<String> = buildList {
        if (stamp != other.stamp) add("stamp")
        if (contractVersion != other.contractVersion) add("contract version")
        if (authorization != other.authorization) add("authorization")
        if (increment != other.increment) add("increment")
        if (incrementDefinition != other.incrementDefinition) add("increment definition")
        if (registerVersion != other.registerVersion) add("register version")
        if (carry != other.carry) add("carry-forward")
        if (notes != other.notes) add("notes")
        if (contractsIndex != other.contractsIndex) add("contracts index")
        if (skills != other.skills) add("skills")
        if (role != other.role) add("role")
        if (roleTextVersion != other.roleTextVersion) add("role text version")
        if (profile != other.profile) add("profile")
        if (policy != other.policy) add("policy")
        if (prime != other.prime) add("prime")
        if (rules != other.rules) add("rules")
        if (calibration != other.calibration) add("calibration")
        if (estimator != other.estimator) add("estimator")
        if (maxOutputTokens != other.maxOutputTokens) add("max output tokens")
        if (pinned != other.pinned) add("pinned")
    }

    public companion object {
        private val JSON = Json { encodeDefaults = true }

        /** The fingerprint of a [Compiler.compile] call with these arguments; every text input is digested. */
        @JvmStatic
        @JvmOverloads
        public fun of(
            stamp: CandidateId,
            contract: Contract,
            increment: Increment,
            role: Role,
            profile: Profile,
            attempt: AttemptConfig,
            prime: String,
            estimator: TokenEstimator,
            maxOutputTokens: Int,
            inputs: CompileInputs = CompileInputs(),
            registerVersion: Int? = null,
            pinned: List<String> = emptyList(),
        ): Fingerprint = Fingerprint(
            stamp = stamp.digest.hex,
            contractVersion = contract.version,
            authorization = Digest.ofUtf8(contract.authorization.toString()).hex,
            increment = increment.id,
            incrementDefinition = Digest.ofUtf8(
                listOf(increment.id, increment.title, increment.requirementIds, increment.accept, increment.writeScope, increment.dependsOn, increment.expectedFiles, increment.produces)
                    .joinToString("\u0000"),
            ).hex,
            registerVersion = registerVersion,
            carry = inputs.carry?.let { carry ->
                Digest.ofUtf8(carry.render() + "\u0000" + inputs.seeds?.shown.orEmpty().joinToString("\u0000") { "${it.path}:${it.range}@${it.version.digest.hex}" }).hex
            },
            notes = inputs.notes.map { "${it.id}@${it.status.wire}:${Digest.ofUtf8(it.summary + "\n" + it.body).hex}" }.sorted(),
            contractsIndex = inputs.contractsIndex?.let { Digest.ofUtf8(it).hex },
            skills = inputs.skills.map { "${it.id}@v${it.version}:${it.digest}" }.distinct().sorted(),
            role = role.name,
            // The compiled role's own wording: the frozen attempt map already enters through `policy`.
            roleTextVersion = RoleTexts.version(role),
            profile = profile.id,
            policy = attempt.fingerprint.hex,
            prime = Digest.ofUtf8(prime).hex,
            rules = inputs.rules?.let { Digest.ofUtf8(it).hex },
            calibration = inputs.calibration?.let { Digest.ofUtf8(it).hex },
            estimator = "${estimator.id}/${estimator.version}",
            maxOutputTokens = maxOutputTokens,
            pinned = Digest.ofUtf8(pinned.joinToString("\u0000")).hex,
        )
    }
}

/**
 * The cell's seam for §6.6: called at each completion proposal with the checks verify-on-stop is about to run at
 * [stamp]. The controller decides whether only slow or expensive checks remain and pre-builds the next `[K]`.
 */
public fun interface PrecompileTrigger {
    public fun completionProposed(stamp: CandidateId, remaining: List<Check>)
}

/** What [Precompile.take] found: the precompiled context on a hit, else the reason it was not served. */
public data class PrecompileTake(val outcome: PrecompileOutcome, val compiled: Compiled?, val reason: String?)

/**
 * Deterministic boundary pre-compilation (§6.6, F06). After a cell's last mutation, when only `slow|expensive`
 * checks remain, the next increment's `[K]` is compiled locally — no provider request, no cache population —
 * tagged with its [Fingerprint]. At cell close it is served only if the fingerprint and the required coverage
 * still match; anything else is discarded and recompiled, journaled. It applies to `cell_end(next_increment)`
 * only: a cell that does not close `completed` discards its pending compile. Provider pre-warm requests are a
 * separate, budgeted, off-by-default option and are not made here.
 */
public class Precompile(
    private val journal: Journal,
    private val idGen: IdGen,
    private val clock: Clock,
) {
    private class Pending(val cell: ContextId, val ids: Identities, val fingerprint: Fingerprint, val incrementId: String, val job: Deferred<Compiled>)

    @Volatile
    private var pending: Pending? = null

    /** Whether a precompile is pending, for tests and reports. */
    public val isPending: Boolean get() = pending != null

    /** §6.6's condition: nothing but slow or expensive checks (or none at all) remains to run. */
    public fun eligible(remaining: List<Check>): Boolean = remaining.all { it.costClass == CostClass.Slow || it.costClass == CostClass.Expensive }

    /**
     * Compiles [incrementId]'s `[K]` in [scope] with [compile], tagged with [fingerprint]. A compile still pending
     * from an earlier proposal of the same cell is superseded: the tree moved since, so it could never be served.
     */
    @Synchronized
    public fun start(scope: CoroutineScope, ids: Identities, fingerprint: Fingerprint, incrementId: String, remaining: List<Check>, compile: () -> Compiled) {
        val cell = checkNotNull(ids.context) { "a precompile starts from a cell" }
        pending?.let { previous ->
            previous.job.cancel(CancellationException("superseded by a later completion proposal"))
            journal.append(JournalEvent(idGen.next("ev"), previous.ids, null, JournalKind.Boundary, text = "precompile ${previous.incrementId} superseded: a later completion proposal (fp ${previous.fingerprint.digest.hash8})", at = clock.instant()))
        }
        val job = scope.async { compile() }
        pending = Pending(cell, ids, fingerprint, incrementId, job)
        val checks = if (remaining.isEmpty()) "no checks remain" else remaining.joinToString(", ") { "${it.id} ${it.costClass.name.lowercase()}" }
        journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "precompile $incrementId started @${fingerprint.stamp.take(8)} fp ${fingerprint.digest.hash8} · $checks", at = clock.instant()))
    }

    /** Drops the pending compile: the cell did not close as `cell_end(next_increment)`. */
    @Synchronized
    public fun discard(reason: String) {
        val previous = pending ?: return
        pending = null
        previous.job.cancel(CancellationException(reason))
        journal.append(JournalEvent(idGen.next("ev"), previous.ids, null, JournalKind.Boundary, text = "precompile ${previous.incrementId} discarded: $reason", at = clock.instant()))
    }

    /**
     * Serves the pending `[K]` iff its fingerprint equals [fingerprint] and [coverage] of it is empty; else discards
     * it and reports the miss with what moved. `None` when nothing was pending. Never serves a stale seed.
     */
    public suspend fun take(fingerprint: Fingerprint, coverage: (Compiled) -> List<String>): PrecompileTake {
        val previous = synchronized(this) { pending.also { pending = null } } ?: return PrecompileTake(PrecompileOutcome.None, null, null)
        val compiled = try {
            previous.job.await()
        } catch (cancelled: CancellationException) {
            return miss(previous, "compile cancelled (${cancelled.message})")
        }
        val moved = previous.fingerprint.differences(fingerprint)
        if (moved.isNotEmpty()) return miss(previous, "${moved.joinToString(", ")} moved (fp ${previous.fingerprint.digest.hash8} → ${fingerprint.digest.hash8})")
        val missing = coverage(compiled)
        if (missing.isNotEmpty()) return miss(previous, "required coverage no longer met: ${missing.joinToString(", ")}")
        journal.append(JournalEvent(idGen.next("ev"), previous.ids, null, JournalKind.Boundary, text = "precompile ${previous.incrementId} hit: [K] reused @${fingerprint.stamp.take(8)} fp ${fingerprint.digest.hash8} · coverage revalidated", at = clock.instant()))
        return PrecompileTake(PrecompileOutcome.Hit, compiled, null)
    }

    private fun miss(previous: Pending, reason: String): PrecompileTake {
        journal.append(JournalEvent(idGen.next("ev"), previous.ids, null, JournalKind.Boundary, text = "precompile ${previous.incrementId} miss: $reason · discarded, recompiled", at = clock.instant()))
        return PrecompileTake(PrecompileOutcome.Miss, null, reason)
    }
}
