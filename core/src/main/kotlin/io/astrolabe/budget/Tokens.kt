package io.astrolabe.budget

import kotlinx.serialization.Serializable
import kotlin.math.ceil

/** A non-negative token quantity. */
@Serializable
public data class Tokens(val value: Long) : Comparable<Tokens> {
    init {
        require(value >= 0) { "tokens must be ≥ 0, got $value" }
    }

    public operator fun plus(other: Tokens): Tokens = Tokens(value + other.value)

    /** Saturating subtraction: never below zero. */
    public operator fun minus(other: Tokens): Tokens = Tokens(maxOf(0L, value - other.value))

    /** Fraction of this quantity, rounded up. */
    public fun fraction(f: Double): Tokens {
        require(f >= 0.0) { "fraction must be ≥ 0" }
        return Tokens(ceil(value * f).toLong())
    }

    override fun compareTo(other: Tokens): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    public companion object {
        @JvmField
        public val ZERO: Tokens = Tokens(0)

        @JvmStatic
        public fun of(value: Long): Tokens = Tokens(value)

        @JvmStatic
        public fun max(a: Tokens, b: Tokens): Tokens = if (a >= b) a else b
    }
}
