package io.astrolabe.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * D-373, D-375: the deterministic syntax repair of tool-call JSON that a model emitted malformed but complete. A cut
 * output is never completed: when the provider stopped for length ([repair]'s `truncated`), or the text ends inside a
 * string or right after `:` or `,` (a value is missing), nothing is repaired. Otherwise a string-aware scan applies at
 * most [MAX_CHANGES] pure syntax fixes, none of which touches a string's content:
 * - (a) a closer that does not match the open container, or follows the complete value, is dropped;
 * - (b) the closers still open at the end are appended;
 * - (c) a `}`/`]` is inserted before a `,` whose next token cannot continue that container (`…}, {` in an op object of
 *   an array, `…, "key":` in an array of an object);
 * - (d) a trailing `,` before `}`/`]` is removed;
 * - (e) a `,` is inserted between adjacent values `}{`, `]{`, `}[` in an array, and between two strings when the second
 *   is a key (followed by `:`) in an object or is followed by `,`/`]` in an array.
 * Single-quoted strings, comments and anything else are not repaired. The result counts only if it parses as strict
 * JSON; text carrying leaked tool-call markup (F8, D-361) is never repaired — its brackets are not the defect.
 */
internal object JsonRepair {
    const val MAX_CHANGES: Int = 4
    const val CUT: String = "the output was cut; send the call again in full"

    sealed interface Outcome

    data class Repaired(val element: JsonElement, val changes: List<String>) : Outcome {
        val summary: String get() = changes.joinToString("; ")
    }

    /** The output was cut: a length stop, an unterminated string or a missing value at the end. */
    data object Cut : Outcome

    data class NotRepaired(val why: String?) : Outcome

    /** The text appended to a parse error for [outcome] when it is not [Repaired]. */
    fun suffix(outcome: Outcome): String = when (outcome) {
        is Repaired -> ""
        Cut -> " — $CUT"
        is NotRepaired -> outcome.why?.let { " — not repaired: $it" }.orEmpty()
    }

    fun repair(json: Json, text: String, truncated: Boolean): Outcome {
        if (truncated) return Cut
        if ("<arg_key>" in text || "<arg_value>" in text) return NotRepaired(null)
        val tokens = when (val scanned = tokens(text)) {
            is Scan.Tokens -> scanned.tokens
            is Scan.Stop -> return scanned.outcome
        }
        if (tokens.lastOrNull()?.kind.let { it == ':' || it == ',' }) return Cut
        val edits = Repairer(text, tokens).run() ?: return NotRepaired(null)
        if (edits.isEmpty()) return NotRepaired(null)
        if (edits.size > MAX_CHANGES) return NotRepaired("more than $MAX_CHANGES syntax fixes would be needed")
        val element = try {
            json.parseToJsonElement(apply(text, edits))
        } catch (e: IllegalArgumentException) {
            return NotRepaired(null)
        }
        return if (strict(element)) Repaired(element, edits.map { it.note }) else NotRepaired(null)
    }

    private fun strict(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.all(::strict)
        is JsonArray -> element.all(::strict)
        is JsonPrimitive -> element.isString || element.content in LITERALS || NUMBER.matches(element.content)
    }

    private val LITERALS = setOf("true", "false", "null")
    private val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

    /** One token: a structural character, `s` a string or `v` a bare literal, spanning [start] until [end]. */
    private class Token(val kind: Char, val start: Int, val end: Int)

    private sealed interface Scan {
        class Tokens(val tokens: List<Token>) : Scan
        class Stop(val outcome: Outcome) : Scan
    }

    private fun tokens(text: String): Scan {
        val out = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c in "{}[]:," -> out += Token(c, i, ++i)
                c == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"') j += if (text[j] == '\\') 2 else 1
                    if (j >= text.length) return Scan.Stop(Cut)
                    out += Token('s', i, j + 1)
                    i = j + 1
                }
                c == '\'' -> return Scan.Stop(NotRepaired("single-quoted strings"))
                c == '/' && text.getOrNull(i + 1).let { it == '/' || it == '*' } -> return Scan.Stop(NotRepaired("comments"))
                c.isLetterOrDigit() || c in "+-." -> {
                    var j = i
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] in "+-.")) j++
                    out += Token('v', i, j)
                    i = j
                }
                else -> return Scan.Stop(NotRepaired(null))
            }
        }
        return Scan.Tokens(out)
    }

    /** A change at [offset] of the original text: [insert] before it, or the character there dropped. */
    private class Edit(val offset: Int, val insert: Char?, val note: String)

    private fun apply(text: String, edits: List<Edit>): String {
        val out = StringBuilder(text.length + edits.size)
        for (i in 0..text.length) {
            edits.filter { it.offset == i && it.insert != null }.forEach { out.append(it.insert) }
            if (i < text.length && edits.none { it.offset == i && it.insert == null }) out.append(text[i])
        }
        return out.toString()
    }

    private enum class State { KeyOrEnd, Key, Colon, Value, ValueOrEnd, ArrayValue, Next }

    private class Frame(val opener: Char, var state: State)

    /** The edits that make [tokens] one JSON value, or `null` when a defect is none of (a)–(e). */
    private class Repairer(private val text: String, private val tokens: List<Token>) {
        private val stack = ArrayList<Frame>()
        private val edits = ArrayList<Edit>()
        private var done = false
        private var comma = -1

        fun run(): List<Edit>? {
            for (k in tokens.indices) {
                if (!step(k)) return null
                if (edits.size > MAX_CHANGES) return edits
            }
            if (!done && stack.isEmpty()) return null
            // Only the innermost container can end here; every outer one waits for its open child.
            if (stack.lastOrNull()?.state.let { it != null && it != State.Next && it != State.KeyOrEnd && it != State.ValueOrEnd }) return null
            for (frame in stack.asReversed()) {
                val closer = closer(frame.opener)
                edits += Edit(text.length, closer, "appended '$closer' at ${text.length}")
            }
            return edits
        }

        private fun step(k: Int): Boolean {
            val token = tokens[k]
            val c = token.kind
            val top = stack.lastOrNull()
            if (top == null) {
                return when {
                    !done && opens(c) -> value(token).let { true }
                    c == '}' || c == ']' -> drop(token)
                    else -> false
                }
            }
            return when (top.state) {
                State.KeyOrEnd -> when (c) {
                    's' -> true.also { top.state = State.Colon }
                    '}' -> close()
                    ']' -> drop(token)
                    else -> false
                }
                State.Key -> when (c) {
                    's' -> true.also { top.state = State.Colon }
                    '}' -> removeComma().let { close() }
                    ']' -> drop(token)
                    '{', '[', 'v' -> closeBeforeComma(k)
                    else -> false
                }
                State.Colon -> (c == ':').also { if (it) top.state = State.Value }
                State.Value -> opens(c).also { if (it) value(token) }
                State.ValueOrEnd -> when (c) {
                    ']' -> close()
                    '}' -> drop(token)
                    else -> opens(c).also { if (it) value(token) }
                }
                State.ArrayValue -> when {
                    c == 's' && tokens.getOrNull(k + 1)?.kind == ':' -> closeBeforeComma(k)
                    c == ']' -> removeComma().let { close() }
                    c == '}' -> drop(token)
                    else -> opens(c).also { if (it) value(token) }
                }
                State.Next -> next(k, top)
            }
        }

        private fun next(k: Int, top: Frame): Boolean {
            val token = tokens[k]
            val c = token.kind
            val previous = tokens[k - 1].kind
            val following = tokens.getOrNull(k + 1)?.kind
            return when {
                c == ',' -> true.also {
                    comma = k
                    top.state = if (top.opener == '{') State.Key else State.ArrayValue
                }
                c == closer(top.opener) -> close()
                c == '}' || c == ']' -> drop(token)
                top.opener == '{' && c == 's' && previous == 's' && following == ':' -> {
                    edits += Edit(token.start, ',', "inserted ',' at ${token.start}")
                    top.state = State.Colon
                    true
                }
                top.opener == '[' && ((previous == '}' && (c == '{' || c == '[')) || (previous == ']' && c == '{') ||
                    (previous == 's' && c == 's' && (following == ',' || following == ']'))) -> {
                    edits += Edit(token.start, ',', "inserted ',' at ${token.start}")
                    value(token)
                    true
                }
                else -> false
            }
        }

        private fun opens(c: Char): Boolean = c == '{' || c == '[' || c == 's' || c == 'v'

        private fun value(token: Token) {
            when (token.kind) {
                '{' -> stack += Frame('{', State.KeyOrEnd)
                '[' -> stack += Frame('[', State.ValueOrEnd)
                else -> completed()
            }
        }

        private fun completed() {
            val top = stack.lastOrNull()
            if (top == null) done = true else top.state = State.Next
        }

        private fun close(): Boolean {
            stack.removeAt(stack.size - 1)
            completed()
            return true
        }

        private fun drop(token: Token): Boolean {
            edits += Edit(token.start, null, "dropped '${token.kind}' at ${token.start}")
            return true
        }

        private fun removeComma() {
            val at = tokens[comma].start
            edits += Edit(at, null, "removed ',' at $at")
        }

        /** (c): the container before the last comma ends there; the comma and token [k] then belong to its parent. */
        private fun closeBeforeComma(k: Int): Boolean {
            val frame = stack.last()
            val at = tokens[comma].start
            val closer = closer(frame.opener)
            edits += Edit(at, closer, "inserted '$closer' at $at")
            close()
            val parent = stack.lastOrNull() ?: return false
            parent.state = if (parent.opener == '{') State.Key else State.ArrayValue
            return step(k)
        }

        private fun closer(opener: Char): Char = if (opener == '{') '}' else ']'
    }
}
