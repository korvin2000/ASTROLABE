package io.astrolabe.cell

import io.astrolabe.atlas.EditHunk

/**
 * One coarse hunk per edited file for the `risk > θ` trigger (§7.4): the lines between the longest common prefix and
 * suffix of the preimage and the current bytes. It over-counts a file with distant edits, never under-counts (D-152).
 */
internal object EditHunks {
    fun of(before: Map<String, ByteArray>, current: (String) -> ByteArray?): List<EditHunk> = before.mapNotNull { (path, bytes) ->
        between(path, lines(bytes), lines(current(path)))
    }

    private fun lines(bytes: ByteArray?): List<String> =
        bytes?.toString(Charsets.UTF_8)?.split('\n')?.let { if (it.lastOrNull() == "") it.dropLast(1) else it }.orEmpty()

    private fun between(path: String, old: List<String>, new: List<String>): EditHunk? {
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix && old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++
        val oldCount = old.size - prefix - suffix
        val newCount = new.size - prefix - suffix
        if (oldCount == 0 && newCount == 0) return null
        return EditHunk(path, prefix.toLong(), oldCount.toLong(), prefix.toLong(), newCount.toLong())
    }
}
