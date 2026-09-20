package io.astrolabe.fixtures

import io.astrolabe.provider.ContentPart
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Opaque
import io.astrolabe.provider.OpaqueContinuation
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Request
import io.astrolabe.provider.Text
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.UsageItem

/**
 * The fake provider's own deterministic tokenizer (I-17): whitespace-delimited pieces plus one token per
 * symbol character. Deliberately different from the core heuristic so estimation drift is observable.
 */
public object FakeTokenizer {
    public fun count(text: String): Long {
        var pieces = 0L
        var inPiece = false
        var symbols = 0L
        for (c in text) {
            if (c.isWhitespace()) {
                inPiece = false
            } else {
                if (!inPiece) {
                    pieces++
                    inPiece = true
                }
                if (!c.isLetterOrDigit()) symbols++
            }
        }
        return pieces + symbols
    }

    public fun count(part: ContentPart): Long = when (part) {
        is Text -> count(part.text)
        is Opaque -> count(part.payload.toString())
    }

    public fun count(item: Item): Long = when (item) {
        is Message -> item.parts.sumOf { count(it) }
        is ToolCall -> count(item.name) + count(item.argsJson)
        is ToolResult -> item.content.sumOf { count(it) }
        is ReasoningRef -> item.opaque?.let { count(it.toString()) } ?: 0L
        is UsageItem -> 0L
        is OpaqueContinuation -> item.effectiveHistoryTokens ?: 0L
    }

    public fun count(items: List<Item>): Long = items.sumOf { count(it) }

    /** Input tokens of a request: tools, every segment, and the continuation's effective history. */
    public fun count(request: Request): Long =
        request.tools.sumOf { count(it.name) + count(it.description) + count(it.jsonSchema.toString()) } +
            request.segments.sumOf { count(it.items) } +
            (request.continuation?.let { count(it) } ?: 0L)
}
