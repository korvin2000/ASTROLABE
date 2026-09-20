package io.astrolabe.provider

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.time.LocalDate

/** Decimal money and prices travel as plain decimal strings (exact, provider-neutral). */
public object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BigDecimal): Unit = encoder.encodeString(value.toPlainString())
    override fun deserialize(decoder: Decoder): BigDecimal = BigDecimal(decoder.decodeString())
}

public object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate): Unit = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

public typealias SerializableBigDecimal = @Serializable(with = BigDecimalSerializer::class) BigDecimal

public typealias SerializableLocalDate = @Serializable(with = LocalDateSerializer::class) LocalDate

/** Serializes a single-string wrapper as its bare string. */
public abstract class StringWrapperSerializer<T>(
    name: String,
    private val wrap: (String) -> T,
    private val unwrap: (T) -> String,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T): Unit = encoder.encodeString(unwrap(value))
    override fun deserialize(decoder: Decoder): T = wrap(decoder.decodeString())
}

internal fun requireToken(kind: String, value: String, maxLength: Int = 128): String {
    require(value.isNotEmpty() && value.length <= maxLength) { "$kind must be 1..$maxLength characters" }
    require(value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' || it == '.' }) {
        "$kind '$value' contains characters outside [A-Za-z0-9._-]"
    }
    return value
}
