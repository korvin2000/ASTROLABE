package io.astrolabe.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * D-373: one deterministic repair of tool-call JSON whose brackets do not balance — the shape weaker models emit when
 * they pattern-complete `}}` after the last op of a list. A string-aware scan (quotes and `\` escapes respected) keeps a
 * bracket stack and changes closers only:
 * - a closer that does not match the top of the stack, or arrives with an empty stack, is dropped;
 * - an object or array opening where an object expects a key (`…}, {` inside an object whose parent is an array) closes
 *   that object first: the one missing `}` is inserted before the comma;
 * - the closers the stack still needs are appended at the end.
 *
 * The caller accepts the result only if it parses as strict JSON ([parsed]); at most [MAX_CHANGES] closers may change,
 * anything else stays the parser's own error.
 */
internal object JsonRepair {
    const val MAX_CHANGES: Int = 3

    data class Repaired(val text: String, val changes: List<String>) {
        val summary: String get() = changes.joinToString(", ")
    }

    /**
     * [text] repaired and parsed, or `null`; the tree parser's unquoted literals do not count as JSON, and text carrying
     * leaked tool-call markup (F8) is never repaired — its brackets are not the defect.
     */
    fun parsed(json: Json, text: String): Pair<JsonElement, Repaired>? {
        if ("<arg_key>" in text || "<arg_value>" in text) return null
        val repair = repair(text) ?: return null
        val element = try {
            json.parseToJsonElement(repair.text)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return if (strict(element)) element to repair else null
    }

    private fun strict(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.all(::strict)
        is JsonArray -> element.all(::strict)
        is JsonPrimitive -> element.isString || element.content in LITERALS || NUMBER.matches(element.content)
    }

    private val LITERALS = setOf("true", "false", "null")
    private val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

    fun repair(text: String): Repaired? {
        val out = StringBuilder(text.length + 4)
        val stack = ArrayList<Char>()
        val changes = ArrayList<String>()
        var inString = false
        var escaped = false
        var previous = ' '
        var commaAt = -1
        var commaOffset = -1
        for ((i, c) in text.withIndex()) {
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> {
                        inString = false
                        previous = '"'
                    }
                }
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    out.append(c)
                }
                '{', '[' -> {
                    if (previous == ',' && stack.lastOrNull() == '{' && stack.getOrNull(stack.size - 2) == '[') {
                        out.insert(commaAt, '}')
                        stack.removeAt(stack.size - 1)
                        changes += "1 missing '}' inserted at offset $commaOffset"
                    }
                    stack += c
                    out.append(c)
                    previous = c
                }
                '}', ']' -> {
                    val opener = if (c == '}') '{' else '['
                    if (stack.lastOrNull() == opener) {
                        stack.removeAt(stack.size - 1)
                        out.append(c)
                        previous = c
                    } else {
                        changes += "1 surplus '$c' dropped at offset $i"
                    }
                }
                else -> {
                    if (c == ',') {
                        commaAt = out.length
                        commaOffset = i
                    }
                    out.append(c)
                    if (!c.isWhitespace()) previous = c
                }
            }
        }
        if (inString) return null
        for (opener in stack.asReversed()) {
            val closer = if (opener == '{') '}' else ']'
            out.append(closer)
            changes += "1 missing '$closer' appended"
        }
        if (changes.isEmpty() || changes.size > MAX_CHANGES) return null
        return Repaired(out.toString(), changes)
    }
}
