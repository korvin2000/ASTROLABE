package io.astrolabe

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Collections

/** Invariant 12: own every nested collection; canonicalize maps/sets while retaining sequence order. */
internal fun configSnapshot(config: Config): Config = config.copy(
    profiles = frozenMap(config.profiles.mapValues { (_, profile) ->
        profile.copy(
            capabilities = profile.capabilities.let {
                it.copy(
                    caching = it.caching.copy(writeClasses = frozenSet(it.caching.writeClasses)),
                    usageFields = frozenSet(it.usageFields), schemaDialects = frozenSet(it.schemaDialects),
                )
            },
            priceTable = profile.priceTable.copy(
                perMillion = frozenMap(profile.priceTable.perMillion),
                tiers = frozenList(profile.priceTable.tiers.map { it.copy(perMillion = frozenMap(it.perMillion)) }),
            ),
            config = frozenJson(profile.config) as JsonObject,
            stratumOutcomes = frozenList(profile.stratumOutcomes),
        )
    }),
    roles = frozenMap((io.astrolabe.cell.Roles.defaults + config.roles).mapValues { (_, role) ->
        role.copy(
            contextView = frozenSet(role.contextView), noteScope = frozenSet(role.noteScope),
            skillFilter = frozenSet(role.skillFilter), toolMask = role.toolMask.copy(allowed = frozenSet(role.toolMask.allowed)),
            duties = frozenList(role.duties), personaLines = frozenList(role.personaLines),
            deniedNoteKinds = frozenSet(role.deniedNoteKinds),
        )
    }),
    redaction = config.redaction.copy(patterns = frozenList(config.redaction.patterns), envAllowlist = frozenSet(config.redaction.envAllowlist)),
    qualityGates = frozenList(config.qualityGates.map { it.copy(argv = frozenList(it.argv)) }),
    tierTable = config.tierTable.copy(profiles = frozenMap(config.tierTable.profiles.mapValues { frozenSet(it.value) })),
)

internal fun <K, V> frozenMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(values.entries.sortedBy { it.key.toString() }.associateTo(LinkedHashMap()) { it.toPair() })

private fun <T> frozenSet(values: Set<T>): Set<T> =
    Collections.unmodifiableSet(values.sortedBy { it.toString() }.toCollection(LinkedHashSet()))

private fun <T> frozenList(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

private fun frozenJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(frozenMap(value.mapValues { frozenJson(it.value) }))
    is JsonArray -> JsonArray(frozenList(value.map(::frozenJson)))
    else -> value
}
