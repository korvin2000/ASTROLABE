package io.astrolabe.telemetry

import io.astrolabe.Config
import io.astrolabe.id.Digest
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** The `accounting.json` export: totals with the four §11.5 quantities, durable state measured at export time. */
@Serializable
public data class AccountingExport(
    val work: WorkId,
    val acceptedTasks: Int,
    val totals: AccountTotals,
    val quantities: Quantities,
)

/**
 * JSON exports under `exports/<work>/` (§15.5 "packets, receipts, manifests and traces are files"): derived views
 * of the store, rewritten on every export, never read back as an authority. `usage.json` lists every priced call,
 * `accounting.json` the totals; `otel-spans.json` (OpenTelemetry GenAI span shape) only when [Config.flags]
 * enables `otelExport` — an optional layer, off by default.
 */
public class Export(private val store: Store, private val config: Config = Config()) {
    @JvmOverloads
    public fun write(work: WorkId, acceptedTasks: Int, currency: String, spans: Spans? = null): List<Path> {
        val accounting = Accounting(store, java.time.Clock.systemUTC())
        val calls = accounting.calls(work)
        val totals = Accounting.totals(calls, acceptedTasks, currency)
        val dir = store.layout.exports.resolve(work.value)
        Files.createDirectories(dir)
        val quantities = Quantities(
            bytesTransmitted = calls.map { it.quantities.bytesTransmitted }.sumOrNull(),
            modelVisibleInput = calls.map { it.quantities.modelVisibleInput }.sumOrNull(),
            billedUsage = calls.map { it.quantities.billedUsage }.sumOrNull(),
            durableState = Accounting.durableState(store.layout.root),
        )
        val written = ArrayList<Path>()
        written.add(file(dir.resolve("usage.json"), Accounting.JSON.encodeToString(ListSerializer(CallAccount.serializer()), calls)))
        written.add(file(dir.resolve("accounting.json"), Accounting.JSON.encodeToString(AccountingExport.serializer(), AccountingExport(work, acceptedTasks, totals, quantities))))
        if (config.flags.otelExport && spans != null) written.add(file(dir.resolve("otel-spans.json"), otel(work, spans, calls).toString()))
        return written
    }

    private fun file(path: Path, text: String): Path = Files.writeString(path, text + "\n")

    /** Spans in the OpenTelemetry GenAI shape: phase spans, and one `chat` span per model call under its cell. */
    private fun otel(work: WorkId, spans: Spans, calls: List<CallAccount>): JsonElement {
        val trace = hex(work.value, 32)
        val recorded = spans.all().filter { it.ids.work == work }
        return buildJsonArray {
            for (span in recorded) add(buildJsonObject {
                put("traceId", trace)
                put("spanId", hex(span.id.value, 16))
                span.parent?.let { put("parentSpanId", hex(it.value, 16)) }
                put("name", span.phase.name.lowercase())
                put("attributes", buildJsonObject {
                    put("astrolabe.phase", span.phase.name.lowercase())
                    put("astrolabe.status", span.status.name.lowercase())
                    span.ids.context?.let { put("astrolabe.context", it.value) }
                })
            })
            for (call in calls) add(buildJsonObject {
                put("traceId", trace)
                put("spanId", hex(call.invocationId, 16))
                recorded.lastOrNull { it.ids.context == call.ids.context && call.ids.context != null }?.let { put("parentSpanId", hex(it.id.value, 16)) }
                put("name", "chat ${call.profileId}")
                put("attributes", buildJsonObject {
                    put("gen_ai.operation.name", "chat")
                    put("gen_ai.system", call.usage?.provenance?.provider ?: "unknown")
                    put("gen_ai.request.model", call.usage?.provenance?.model ?: call.profileId)
                    put("gen_ai.usage.input_tokens", call.quantities.modelVisibleInput?.let(::JsonPrimitive) ?: JsonPrimitive(null as Long?))
                    put("gen_ai.usage.output_tokens", call.usage?.quantities?.get(io.astrolabe.provider.BillingDimension.OUTPUT)?.let(::JsonPrimitive) ?: JsonPrimitive(null as Long?))
                })
            })
        }
    }

    private fun hex(value: String, length: Int): String = Digest.ofUtf8(value).hex.take(length)

    private fun List<Long?>.sumOrNull(): Long? = if (any { it == null }) null else sumOf { it!! }
}
