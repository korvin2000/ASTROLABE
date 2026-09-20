package io.astrolabe.workspace

import kotlinx.serialization.Serializable

/** A closed 1-based line range. */
@Serializable
public data class LineRange(val from: Int, val to: Int) {
    init {
        require(from >= 1 && to >= from) { "line range must be 1-based and non-empty, got $from-$to" }
    }

    val lines: Int get() = to - from + 1

    public operator fun contains(line: Int): Boolean = line in from..to

    public fun overlapsOrTouches(other: LineRange): Boolean = from <= other.to + 1 && other.from <= to + 1

    override fun toString(): String = if (from == to) "$from" else "$from-$to"
}

/**
 * Sorted, merged, non-overlapping line ranges (§3.3 `displayed(path, v)` and redaction masks). Immutable;
 * every operation returns a normalized value.
 */
@Serializable
public data class Ranges(val ranges: List<LineRange>) {
    init {
        require(ranges.zipWithNext().all { (a, b) -> a.to + 1 < b.from }) { "ranges must be sorted, merged and non-overlapping: $ranges" }
    }

    val isEmpty: Boolean get() = ranges.isEmpty()

    val lines: Int get() = ranges.sumOf { it.lines }

    public operator fun contains(line: Int): Boolean = ranges.any { line in it }

    /** True when every line of [range] is covered. */
    public fun covers(range: LineRange): Boolean = ranges.any { it.from <= range.from && range.to <= it.to }

    public operator fun plus(range: LineRange): Ranges = of(ranges + range)

    public operator fun plus(other: Ranges): Ranges = of(ranges + other.ranges)

    /** Lines of this set not in [other]. */
    public operator fun minus(other: Ranges): Ranges {
        if (other.isEmpty || isEmpty) return this
        val out = ArrayList<LineRange>()
        for (r in ranges) {
            var cursor = r.from
            for (h in other.ranges) {
                if (h.to < cursor) continue
                if (h.from > r.to) break
                if (h.from > cursor) out += LineRange(cursor, h.from - 1)
                cursor = maxOf(cursor, h.to + 1)
            }
            if (cursor <= r.to) out += LineRange(cursor, r.to)
        }
        return Ranges(out)
    }

    public fun intersect(other: Ranges): Ranges = this - (this - other)

    override fun toString(): String = ranges.joinToString(",")

    public companion object {
        @JvmField
        public val EMPTY: Ranges = Ranges(emptyList())

        /** Normalizes arbitrary ranges: sorts and merges overlapping or adjacent ones. */
        @JvmStatic
        public fun of(ranges: Collection<LineRange>): Ranges {
            if (ranges.isEmpty()) return EMPTY
            val sorted = ranges.sortedWith(compareBy({ it.from }, { it.to }))
            val merged = ArrayList<LineRange>()
            var current = sorted.first()
            for (next in sorted.drop(1)) {
                current = if (current.overlapsOrTouches(next)) LineRange(current.from, maxOf(current.to, next.to)) else {
                    merged += current
                    next
                }
            }
            merged += current
            return Ranges(merged)
        }

        @JvmStatic
        public fun of(vararg ranges: LineRange): Ranges = of(ranges.toList())

        @JvmStatic
        public fun single(from: Int, to: Int): Ranges = Ranges(listOf(LineRange(from, to)))
    }
}
