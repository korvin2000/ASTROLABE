package io.astrolabe.route

import io.astrolabe.provider.Profile
import io.astrolabe.provider.StringWrapperSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The binding a model call runs on (plan §4.6 "binding physics", §11 №5): model × gateway × upstream × wire API. Cache,
 * speed and prices are properties of the binding, not of the model, so every measurement is kept per key.
 * - [model]: the profile's model, never the reply's dated name;
 * - [gateway]: the profile's provider (`openrouter`, `openai-codex`, …);
 * - [upstream]: the provider the gateway routed the call to (`CallFacts.upstream`); `null` is the unknown bucket;
 * - [wireApi]: the wire API of the profile (`gate.api` of `Profile.config`); `null` is the unknown bucket.
 *
 * An unknown member is its own bucket and never merges with a known one: [canonical] renders it as [UNKNOWN] and
 * percent-escapes `%`, `|` and `?` inside known members, so no known value spells the unknown bucket.
 */
@Serializable(with = BindingKey.Serializer::class)
public data class BindingKey @JvmOverloads constructor(
    val model: String,
    val gateway: String,
    val upstream: String? = null,
    val wireApi: String? = null,
) {
    init {
        require(model.isNotBlank() && gateway.isNotBlank()) { "a binding names its model and gateway" }
        require(upstream == null || upstream.isNotBlank()) { "a known upstream is not blank; an unknown one is null" }
        require(wireApi == null || wireApi.isNotBlank()) { "a known wire API is not blank; an unknown one is null" }
    }

    /** `model|gateway|upstream|wireApi`, the store and routing-log form; [parse] reads it back. */
    public val canonical: String
        get() = listOf(model, gateway, upstream, wireApi).joinToString(SEPARATOR) { it?.let(::escape) ?: UNKNOWN }

    /** This binding with the upstream unknown: the key a profile names before any call reports where it was routed. */
    public val route: BindingKey get() = if (upstream == null) this else copy(upstream = null)

    /** True when [other] runs the same model through the same gateway and wire API, on any upstream. */
    public fun sameRoute(other: BindingKey): Boolean = route == other.route

    override fun toString(): String = canonical

    public object Serializer : StringWrapperSerializer<BindingKey>("BindingKey", BindingKey::parse, BindingKey::canonical)

    public companion object {
        /** The canonical spelling of an unknown member. */
        public const val UNKNOWN: String = "?"
        private const val SEPARATOR = "|"

        /** The key of a call on [profile] that [upstream] served; a blank upstream is unknown. */
        @JvmStatic
        @JvmOverloads
        public fun of(profile: Profile, upstream: String? = null): BindingKey =
            BindingKey(profile.model, profile.provider, upstream?.takeIf { it.isNotBlank() }, wireApi(profile))

        /** The wire API [profile] declares (`config.gate.api`, frozen by the gate adapter), or `null` when it declares none. */
        @JvmStatic
        public fun wireApi(profile: Profile): String? =
            ((profile.config["gate"] as? JsonObject)?.get("api") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        /** Reads a [canonical] form back; throws on any other shape. */
        @JvmStatic
        public fun parse(canonical: String): BindingKey {
            val parts = canonical.split(SEPARATOR)
            require(parts.size == 4) { "a binding key has four members: '$canonical'" }
            val members = parts.map { if (it == UNKNOWN) null else unescape(it) }
            return BindingKey(requireNotNull(members[0]) { "unknown model" }, requireNotNull(members[1]) { "unknown gateway" }, members[2], members[3])
        }

        private fun escape(value: String): String =
            value.replace("%", "%25").replace("|", "%7C").replace("?", "%3F")

        private fun unescape(value: String): String =
            value.replace("%3F", "?").replace("%7C", "|").replace("%25", "%")
    }
}
