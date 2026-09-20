package io.astrolabe.id

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant

/**
 * Candidate identity (§3.3, §8.4): base commit + tracked delta hash + untracked manifest hash + environment id.
 * [id] hashes a versioned canonical encoding of these four fields; the capture time lives in [CapturedStamp]
 * and never enters equality (I-05). The component hashes are computed by the workspace `Stamper` (P1.2.2)
 * over sorted membership with explicit path/type/mode/content fields.
 */
@Serializable
public data class Stamp(
    val baseCommit: String,
    val trackedDeltaHash: Digest,
    val untrackedManifestHash: Digest,
    val envId: Digest,
) {
    init {
        require(baseCommit.isNotBlank() && baseCommit.none { it.isWhitespace() }) { "baseCommit must be a git id or '$NO_COMMIT'" }
    }

    @Transient
    val id: CandidateId = CandidateId(
        Digest.ofUtf8(
            CanonicalEncoding.encode(
                kind = "stamp",
                version = ENCODING_VERSION,
                fields = listOf(
                    "base" to baseCommit,
                    "tracked" to trackedDeltaHash.hex,
                    "untracked" to untrackedManifestHash.hex,
                    "env" to envId.hex,
                ),
            ),
        ),
    )

    val hash8: String get() = id.hash8

    public companion object {
        public const val ENCODING_VERSION: Int = 1

        /** Base commit marker for a repository without commits. */
        public const val NO_COMMIT: String = "none"
    }
}

/** A stamp plus the moment it was captured; the time is metadata outside identity (I-05). */
@Serializable
public data class CapturedStamp(
    val stamp: Stamp,
    @Serializable(with = InstantSerializer::class) val at: Instant,
)

/**
 * Versioned canonical text encoding used for every hashed identity (stamps, manifests, environment
 * fingerprints). Output: a header line `astrolabe/<kind>/v<version>` then one `key=value` line per field
 * in the caller's order (callers sort membership themselves). Keys must match `[A-Za-z0-9_.-]+`; values are
 * escaped (`\` → `\\`, newline → `\n`, CR → `\r`) so the encoding is injective.
 */
public object CanonicalEncoding {
    @JvmStatic
    public fun encode(kind: String, version: Int, fields: List<Pair<String, String>>): String {
        require(kind.isNotEmpty() && kind.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "bad kind '$kind'" }
        require(version >= 1) { "version must be ≥ 1" }
        val sb = StringBuilder()
        sb.append("astrolabe/").append(kind).append("/v").append(version).append('\n')
        for ((key, value) in fields) {
            require(key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) { "bad key '$key'" }
            sb.append(key).append('=').append(escape(value)).append('\n')
        }
        return sb.toString()
    }

    @JvmStatic
    public fun escape(value: String): String {
        if (value.none { it == '\\' || it == '\n' || it == '\r' }) return value
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
