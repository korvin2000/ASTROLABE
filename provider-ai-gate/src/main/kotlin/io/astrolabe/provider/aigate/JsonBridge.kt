package io.astrolabe.provider.aigate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import net.ai.gate.json.JsonNull as GateNull
import net.ai.gate.json.JsonObject as GateObject
import net.ai.gate.json.JsonValue as GateValue
import net.ai.gate.json.Json as GateJson

/**
 * kotlinx JSON ↔ AI Gate JSON through their text forms (G-10): both parsers keep numbers as written and nulls
 * explicit, so a round trip is lossless. Used for tool schemas, reasoning replay data and option templates only;
 * message text never passes through here.
 */
internal object JsonBridge {
    fun toGate(element: JsonElement): GateValue = if (element is JsonNull) GateNull.INSTANCE else GateJson.parse(element.toString())

    fun toGateObject(element: JsonObject): GateObject = GateJson.parse(element.toString()) as GateObject

    fun toKotlin(value: GateValue): JsonElement = if (value is GateNull) JsonNull else Json.parseToJsonElement(value.toJson())
}
