package io.astrolabe.index.treesitter

import io.astrolabe.atlas.Outline
import io.astrolabe.id.FileVersion
import io.astrolabe.tool.edit.SyntaxResult
import org.treesitter.TSInputEdit
import org.treesitter.TSInputEncoding
import org.treesitter.TSParser
import org.treesitter.TSPoint
import org.treesitter.TSReader
import org.treesitter.TSTree
import java.util.EnumMap

/**
 * Syntax trees cached per `(path, version)` (§7.2 tier 1). One tree is kept per path, for the last
 * version seen; a new version of that path is parsed incrementally from it, with the edit derived
 * from the common prefix and suffix of the two byte strings. Derived answers (outline, syntax
 * verdict) are memoized on the entry, so they are computed once per version.
 *
 * Every method is synchronized: a tree-sitter parser is single-threaded, and a tree must not be
 * edited while another caller walks it.
 */
internal class SyntaxTrees(
    private val loader: GrammarLoader,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity >= 1) { "the tree cache holds at least one tree; got $capacity" }
    }

    private class Entry(val grammar: Grammar, val version: FileVersion, val bytes: ByteArray, val tree: TSTree) {
        var outline: Outline? = null
        var syntax: SyntaxResult? = null
    }

    private val parsers = EnumMap<Grammar, TSParser>(Grammar::class.java)
    private val failures = EnumMap<Grammar, String>(Grammar::class.java)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    var fullParses: Int = 0
        private set
    var incrementalParses: Int = 0
        private set
    var hits: Int = 0
        private set

    /** Why each unavailable grammar could not be loaded, in [Grammar] order. */
    @Synchronized
    fun failures(): Map<Grammar, String> = EnumMap(failures)

    /** The tier-1 outline of [bytes] at [path], or null when [grammar] is unavailable. */
    @Synchronized
    fun outline(path: String, bytes: ByteArray, grammar: Grammar): Outline? {
        val entry = entry(path, bytes, grammar) ?: return null
        return entry.outline ?: TreeOutline.of(path, grammar, entry.tree.rootNode, entry.bytes).also { entry.outline = it }
    }

    /** The syntax verdict for [bytes] at [path] from ERROR and MISSING nodes, or null when [grammar] is unavailable. */
    @Synchronized
    fun syntax(path: String, bytes: ByteArray, grammar: Grammar): SyntaxResult? {
        val entry = entry(path, bytes, grammar) ?: return null
        return entry.syntax ?: TreeSyntax.of(entry.tree.rootNode).also { entry.syntax = it }
    }

    @Synchronized
    fun close() {
        for (entry in entries.values) entry.tree.close()
        entries.clear()
        for (parser in parsers.values) parser.close()
        parsers.clear()
    }

    private fun entry(path: String, bytes: ByteArray, grammar: Grammar): Entry? {
        val version = FileVersion.of(bytes)
        val previous = entries[path]
        if (previous != null && previous.grammar == grammar && previous.version == version) {
            hits++
            return previous
        }
        val parser = parser(grammar) ?: return null
        val copy = bytes.copyOf()
        val reusable = previous?.takeIf { it.grammar == grammar }
        val tree = try {
            if (reusable != null) reusable.tree.edit(editBetween(reusable.bytes, copy))
            parser.parse(ByteArray(READ_CHUNK_BYTES), reusable?.tree, reader(copy), TSInputEncoding.TSInputEncodingUTF8)
        } catch (failure: RuntimeException) {
            fail(grammar, failure)
            null
        } catch (failure: LinkageError) {
            fail(grammar, failure)
            null
        }
        // The previous tree was edited in place (or is stale): it is never served again.
        if (previous != null) {
            entries.remove(path)
            previous.tree.close()
        }
        if (tree == null) return null
        if (reusable != null) incrementalParses++ else fullParses++
        val entry = Entry(grammar, version, copy, tree)
        entries[path] = entry
        while (entries.size > capacity) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            eldest.value.tree.close()
        }
        return entry
    }

    private fun parser(grammar: Grammar): TSParser? {
        parsers[grammar]?.let { return it }
        if (grammar in failures) return null
        return try {
            TSParser().also {
                check(it.setLanguage(loader.load(grammar))) { "grammar ABI not supported by the runtime" }
                parsers[grammar] = it
            }
        } catch (failure: RuntimeException) {
            fail(grammar, failure)
            null
        } catch (failure: LinkageError) {
            // ExceptionInInitializerError, UnsatisfiedLinkError, NoClassDefFoundError: the natives did not load.
            fail(grammar, failure)
            null
        }
    }

    private fun fail(grammar: Grammar, failure: Throwable) {
        val detail = (failure.cause ?: failure).let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        failures[grammar] = detail.lineSequence().first().take(FAILURE_DETAIL_CHARS)
        parsers.remove(grammar)?.close()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 512
        private const val READ_CHUNK_BYTES = 64 * 1024
        private const val FAILURE_DETAIL_CHARS = 200

        private fun reader(bytes: ByteArray) = TSReader { buffer, offset, _ ->
            if (offset >= bytes.size) {
                0
            } else {
                val count = minOf(buffer.size, bytes.size - offset)
                System.arraycopy(bytes, offset, buffer, 0, count)
                count
            }
        }

        /** The single edit turning [old] into [new]: everything between their common prefix and suffix. */
        internal fun editBetween(old: ByteArray, new: ByteArray): TSInputEdit {
            val limit = minOf(old.size, new.size)
            var prefix = 0
            while (prefix < limit && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < limit - prefix && old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++
            val oldEnd = old.size - suffix
            val newEnd = new.size - suffix
            return TSInputEdit(prefix, oldEnd, newEnd, pointAt(old, prefix), pointAt(old, oldEnd), pointAt(new, newEnd))
        }

        /** Row and byte column of [offset]; tree-sitter points count bytes, not characters. */
        private fun pointAt(bytes: ByteArray, offset: Int): TSPoint {
            var row = 0
            var lineStart = 0
            for (i in 0 until offset) {
                if (bytes[i] == '\n'.code.toByte()) {
                    row++
                    lineStart = i + 1
                }
            }
            return TSPoint(row, offset - lineStart)
        }
    }
}
