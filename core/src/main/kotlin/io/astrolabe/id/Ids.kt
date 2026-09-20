package io.astrolabe.id

import kotlinx.serialization.Serializable
import java.security.SecureRandom

/**
 * The four identities of §3.3 plus their qualifiers. Every id is validated at construction (D-08):
 * non-empty, no whitespace, ≤ [MAX_ID_LENGTH] characters from `[A-Za-z0-9._-]`.
 */
public const val MAX_ID_LENGTH: Int = 128

internal fun requireCanonicalId(kind: String, value: String): String {
    require(value.isNotEmpty() && value.length <= MAX_ID_LENGTH) { "$kind must be 1..$MAX_ID_LENGTH characters" }
    require(value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' || it == '.' }) {
        "$kind '$value' contains characters outside [A-Za-z0-9._-]"
    }
    return value
}

/** The user's logical objective (campaign); survives retries, resumes and model changes. */
@Serializable(with = WorkId.Serializer::class)
public data class WorkId(val value: String) {
    init {
        requireCanonicalId("WorkId", value)
    }

    override fun toString(): String = value

    public object Serializer : StringWrapperSerializer<WorkId>("WorkId", ::WorkId, WorkId::value)
}

/** One execution under a frozen harness/profile policy. */
@Serializable(with = AttemptId.Serializer::class)
public data class AttemptId(val value: String) {
    init {
        requireCanonicalId("AttemptId", value)
    }

    override fun toString(): String = value

    public object Serializer : StringWrapperSerializer<AttemptId>("AttemptId", ::AttemptId, AttemptId::value)
}

/** One cell's model-visible lineage; rebuilds advance the projection [Generation], not this id. */
@Serializable(with = ContextId.Serializer::class)
public data class ContextId(val value: String) {
    init {
        requireCanonicalId("ContextId", value)
    }

    override fun toString(): String = value

    public object Serializer : StringWrapperSerializer<ContextId>("ContextId", ::ContextId, ContextId::value)
}

/** Qualifier of `version(path)` and displayed coverage (§3.3 namespace rule); worktrees get distinct ids. */
@Serializable(with = WorkspaceId.Serializer::class)
public data class WorkspaceId(val value: String) {
    init {
        requireCanonicalId("WorkspaceId", value)
    }

    override fun toString(): String = value

    public object Serializer : StringWrapperSerializer<WorkspaceId>("WorkspaceId", ::WorkspaceId, WorkspaceId::value)
}

/** Candidate identity: the [Stamp.id]. Records carry this digest; the stamps table keeps the components. */
@Serializable(with = CandidateId.Serializer::class)
public data class CandidateId(val digest: Digest) {
    val hash8: String get() = digest.hash8

    override fun toString(): String = digest.hex

    public object Serializer :
        StringWrapperSerializer<CandidateId>("CandidateId", { CandidateId(Digest(it)) }, { it.digest.hex })
}

/** Projection generation of a context lineage; a rebuild advances it (§3.3). */
@Serializable
public data class Generation(val value: Int) {
    init {
        require(value >= 0) { "Generation must be ≥ 0" }
    }

    public fun next(): Generation = Generation(value + 1)

    public companion object {
        @JvmField
        public val INITIAL: Generation = Generation(0)
    }
}

/** Incremented by the controller on reassignment or supersession; checked before dispatch and publication (§13.1). */
@Serializable
public data class ExecutionGeneration(val value: Long) {
    init {
        require(value >= 0) { "ExecutionGeneration must be ≥ 0" }
    }

    public fun next(): ExecutionGeneration = ExecutionGeneration(value + 1)

    public companion object {
        @JvmField
        public val INITIAL: ExecutionGeneration = ExecutionGeneration(0)
    }
}

/** The identity columns every persisted record carries (§3.3). */
@Serializable
public data class Identities(
    val work: WorkId,
    val attempt: AttemptId,
    val candidate: CandidateId? = null,
    val context: ContextId? = null,
) {
    public fun withCandidate(candidate: CandidateId?): Identities = copy(candidate = candidate)

    public fun withContext(context: ContextId?): Identities = copy(context = context)
}

/** Generates new ids; injected so tests are deterministic (§2.3). */
public fun interface IdGen {
    /** Returns `<prefix>-<token>`; the token is unique for the generator's lifetime. */
    public fun next(prefix: String): String
}

/** Cryptographically random ids, `<prefix>-<20 base32 chars>`. */
public class RandomIdGen(private val random: SecureRandom = SecureRandom()) : IdGen {
    override fun next(prefix: String): String {
        val bytes = ByteArray(RANDOM_BYTES).also(random::nextBytes)
        return prefix + "-" + base32(bytes)
    }

    private fun base32(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 8 / 5 + 1)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer ushr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        return sb.toString()
    }

    private companion object {
        const val RANDOM_BYTES = 12
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    }
}
