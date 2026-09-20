package io.astrolabe.fixtures

import io.astrolabe.id.IdGen

/** Deterministic ids `<prefix>-1`, `<prefix>-2`, … per prefix (TODO P0.6.4). */
public class FixedIdGen : IdGen {
    private val counters = HashMap<String, Int>()

    @Synchronized
    override fun next(prefix: String): String {
        val n = (counters[prefix] ?: 0) + 1
        counters[prefix] = n
        return "$prefix-$n"
    }
}
