package io.astrolabe.tool

import io.astrolabe.auth.Capability
import io.astrolabe.id.Digest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One tool a mounted server describes (§15.3). Every field is the server's claim and is kept as data (D-21):
 * [description] is never an instruction, and [readOnlyHint]/[destructiveHint] are hints that can only lower
 * trust, never raise it (F11, D-145). [inputSchema] is the JSON Schema text of the tool's arguments; it must be
 * a JSON object and is frozen with the catalog.
 */
public data class MountDescriptor @JvmOverloads constructor(
    val name: String,
    val description: String,
    val inputSchema: String = "{\"type\":\"object\"}",
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
) {
    init {
        require(NAME.matches(name)) { "mount tool name '$name' must match ${NAME.pattern}" }
        require(parseObject(inputSchema) != null) { "mount tool '$name': inputSchema must be a JSON object" }
    }

    /** True when the server's own hints contradict a read-only approval (D-145). */
    public val claimsEffects: Boolean get() = readOnlyHint == false || destructiveHint == true

    internal companion object {
        val NAME: Regex = Regex("[A-Za-z0-9_.-]{1,64}")

        fun parseObject(text: String): JsonObject? = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (malformed: SerializationException) {
            null
        } catch (malformed: IllegalArgumentException) {
            null
        }
    }
}

/**
 * A mounted external server (§15.3, D-21): the tools it describes plus the host's **local** decisions about them.
 * [localApproval] names tools the host approved as read-only; [effectClassOverride] configures a tool's class
 * outright. Anything neither approved nor configured is `D`. [capabilities] are what an invocation needs from the
 * caller's ceiling — a stdio server needs `run-local`, a remote one adds `network` (D-147).
 */
public data class Mount @JvmOverloads constructor(
    val server: String,
    val tools: List<MountDescriptor>,
    val localApproval: Set<String> = emptySet(),
    val effectClassOverride: Map<String, EffectClass> = emptyMap(),
    val capabilities: Set<Capability> = setOf(Capability.RunLocal),
) {
    init {
        require(MountDescriptor.NAME.matches(server)) { "mount server name '$server' must match ${MountDescriptor.NAME.pattern}" }
        val names = tools.map { it.name }
        require(names.toSet().size == names.size) { "mount '$server' describes a tool twice: $names" }
        val unknown = (localApproval + effectClassOverride.keys) - names.toSet()
        require(unknown.isEmpty()) { "mount '$server': local decisions name undescribed tools $unknown" }
    }

    /**
     * The effect class of [tool] (§15.3): a configured override wins; a locally approved tool whose own hints do
     * not claim effects is `R`; everything else is `D` until configured.
     */
    public fun effectClass(tool: MountDescriptor): EffectClass = effectClassOverride[tool.name]
        ?: if (tool.name in localApproval && !tool.claimsEffects) EffectClass.R else EffectClass.D
}

/** One resolved catalog entry: `mcp:<server>/<tool>`, its frozen descriptor and its locally decided class. */
public data class CatalogEntry(
    val mount: Mount,
    val tool: MountDescriptor,
    val effectClass: EffectClass,
) {
    val program: String get() = "mcp:${mount.server}/${tool.name}"

    /** The `look(catalog)` one-liner; the description is data, cut to its first line (D-21). */
    val line: String
        get() {
            val first = tool.description.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val cut = if (first.length > DESCRIPTION_CHARS) first.take(DESCRIPTION_CHARS - 1) + "…" else first
            return "$program [${effectClass.name}]" + (if (cut.isEmpty()) "" else " — $cut")
        }

    /**
     * Validates [arguments] against the frozen schema: a JSON object carrying every `required` key and, when the
     * schema closes `additionalProperties`, no undeclared key. Type checks are the server's (D-146). `null` = valid.
     */
    public fun validate(arguments: String): String? {
        val args = MountDescriptor.parseObject(arguments) ?: return "arguments must be one JSON object"
        val schema = MountDescriptor.parseObject(tool.inputSchema)!!
        val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val missing = required.filter { it !in args }
        if (missing.isNotEmpty()) return "missing required argument${if (missing.size == 1) "" else "s"} ${missing.joinToString(", ")}"
        val closed = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
        val declared = (schema["properties"] as? JsonObject)?.keys.orEmpty()
        val extra = if (closed) args.keys - declared else emptySet()
        if (extra.isNotEmpty()) return "undeclared argument${if (extra.size == 1) "" else "s"} ${extra.sorted().joinToString(", ")}"
        return null
    }

    private companion object {
        const val DESCRIPTION_CHARS = 100
    }
}

/**
 * The session's catalog of mounted capabilities (§15.3): frozen at construction, so tool schemas never change
 * mid-session. A server that re-describes itself needs a new catalog, i.e. a new session; [digest] identifies
 * the frozen content.
 */
public class Catalog(mounts: List<Mount>) {
    // Snapshot at the authority boundary: a caller mutating its lists later cannot re-describe a tool.
    public val mounts: List<Mount> = mounts.map {
        it.copy(tools = it.tools.toList(), localApproval = it.localApproval.toSet(), effectClassOverride = it.effectClassOverride.toMap(), capabilities = it.capabilities.toSet())
    }

    init {
        val servers = this.mounts.map { it.server }
        require(servers.toSet().size == servers.size) { "a server is mounted twice: $servers" }
    }

    private val entries: Map<String, CatalogEntry> = this.mounts
        .flatMap { mount -> mount.tools.map { CatalogEntry(mount, it, mount.effectClass(it)) } }
        .sortedBy { it.program }
        .associateBy { it.program }

    /** `look(catalog)` one-liners, sorted by `mcp:<server>/<tool>`. */
    public val lines: List<String> = entries.values.map { it.line }

    /** Digest of every frozen descriptor, local decision and required capability. */
    public val digest: Digest = Digest.ofUtf8(
        entries.values.joinToString("\n") { e ->
            listOf(e.program, e.effectClass.name, e.tool.inputSchema, e.tool.description, e.tool.readOnlyHint, e.tool.destructiveHint, e.mount.capabilities.sortedBy { it.ordinal })
                .joinToString("\u0000")
        },
    )

    /** The entry `mcp:<server>/<tool>` names, or `null` when this session never mounted it. */
    public fun resolve(program: String): CatalogEntry? = entries[program.trim()]

    public val isEmpty: Boolean get() = entries.isEmpty()

    public companion object {
        @JvmField
        public val EMPTY: Catalog = Catalog(emptyList())

        @JvmStatic
        public fun of(vararg mounts: Mount): Catalog = Catalog(mounts.toList())
    }
}
