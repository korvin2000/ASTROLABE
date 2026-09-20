package io.astrolabe.id

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** SHA-256 digest in lowercase hex. [hash8] is the display form used in envelopes and registers. */
@Serializable(with = Digest.Serializer::class)
public data class Digest(val hex: String) {
    init {
        require(hex.length == HEX_LENGTH && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Digest must be $HEX_LENGTH lowercase hex characters, got '${hex.take(16)}…' (${hex.length})"
        }
    }

    /** Short display form (`c02e1f9a`); never an identity by itself. */
    val hash8: String get() = hex.substring(0, 8)

    override fun toString(): String = hex

    public object Serializer : StringWrapperSerializer<Digest>("Digest", ::Digest, Digest::hex)

    public companion object {
        public const val HEX_LENGTH: Int = 64

        @JvmStatic
        public fun of(bytes: ByteArray): Digest = Digest(Hashing.sha256Hex(bytes))

        @JvmStatic
        public fun ofUtf8(text: String): Digest = of(text.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Identity of one file version = SHA-256 of the **raw bytes** (I-05). Metadata such as size or
 * mtime may cache a lookup but never establishes this value at a consequential boundary.
 */
@Serializable(with = FileVersion.Serializer::class)
public data class FileVersion(val digest: Digest) {
    val hash8: String get() = digest.hash8

    override fun toString(): String = digest.hex

    public object Serializer :
        StringWrapperSerializer<FileVersion>("FileVersion", { FileVersion(Digest(it)) }, { it.digest.hex })

    public companion object {
        @JvmStatic
        public fun of(bytes: ByteArray): FileVersion = FileVersion(Digest.of(bytes))
    }
}

public object Hashing {
    private const val HEX: String = "0123456789abcdef"

    @JvmStatic
    public fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    @JvmStatic
    public fun sha256Hex(bytes: ByteArray): String = hex(sha256(bytes))

    @JvmStatic
    public fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }
}
