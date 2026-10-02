package io.astrolabe.eval.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** The two layouts of a run's event log the auditor reads (§4.7). */
public enum class JournalFormat {
    /** One bus `EventRecord` per line, `{seq, at, event: {type, ids, …}}`: eval-live's `events.jsonl`. */
    Bus,

    /**
     * Studio's run journal `W-*.jsonl`, `{at, source, kind, ids, cell, turn, data, seq}` per line: the bus events plus
     * the journal's own entries (`journal.call` with the model's tool arguments, `journal.result`, `journal.boundary`).
     */
    Studio,
}

/** One line of either layout: its [kind] (`cell.model_responded`, `journal.boundary`, …), the cell, the turn the line states, and its fields. */
public data class JournalEvent(val kind: String, val cell: String?, val turn: Int?, val data: JsonObject)

/** A run's event log in file order; lines that are neither layout are counted in [unreadableLines], never guessed at. */
public class Journal(public val format: JournalFormat, events: List<JournalEvent>, public val unreadableLines: Int) {
    public val events: List<JournalEvent> = events.toList()

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @JvmStatic
        public fun read(file: Path): Journal = parse(Files.readAllLines(file, StandardCharsets.UTF_8))

        /** The layout is decided per line; a log with no bus line is a Studio journal. */
        @JvmStatic
        public fun parse(lines: List<String>): Journal {
            val events = ArrayList<JournalEvent>()
            var unreadable = 0
            var bus = 0
            for (line in lines) {
                if (line.isBlank()) continue
                val root = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject
                val event = root?.obj("event")
                when {
                    event?.str("type") != null -> {
                        bus++
                        val type = event.str("type")!!
                        events += JournalEvent(type, event.obj("ids")?.str("context"), event.int("turn"), event)
                    }
                    root?.str("kind") != null && root["data"] is JsonObject -> {
                        val data = root.obj("data")!!
                        val turn = root.int("turn") ?: data.int("turn")
                        events += JournalEvent(root.str("kind")!!, root.str("cell") ?: root.obj("ids")?.str("context"), turn, data)
                    }
                    else -> unreadable++
                }
            }
            return Journal(if (bus > 0) JournalFormat.Bus else JournalFormat.Studio, events, unreadable)
        }
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

/** A JSON number, or a decimal string such as `Money.amount`, read exactly (never through a double). */
internal fun JsonObject.decimal(key: String): BigDecimal? = (this[key] as? JsonPrimitive)?.let { value ->
    if (value is JsonNull) null else value.content.toBigDecimalOrNull()
}

internal fun JsonElement?.path(vararg keys: String): JsonElement? {
    var at: JsonElement? = this
    for (key in keys) at = (at as? JsonObject)?.get(key)
    return at
}
