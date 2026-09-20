package io.astrolabe.graph

import io.astrolabe.contract.Increment
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Deserialization goes through the same defensive snapshot boundary as ordinary construction. */
public object GraphSerializer : KSerializer<RequirementGraph> {
    @Serializable
    @SerialName("io.astrolabe.graph.RequirementGraph")
    private data class Snapshot(
        val increments: List<Increment>,
        val ownershipMap: Map<String, String> = emptyMap(),
        val evidence: Map<String, IncrementEvidence> = emptyMap(),
    )

    override val descriptor: SerialDescriptor = Snapshot.serializer().descriptor

    override fun serialize(encoder: Encoder, value: RequirementGraph): Unit = encoder.encodeSerializableValue(
        Snapshot.serializer(), Snapshot(value.increments, value.ownershipMap, value.evidence),
    )

    override fun deserialize(decoder: Decoder): RequirementGraph {
        val snapshot = decoder.decodeSerializableValue(Snapshot.serializer())
        return RequirementGraph(snapshot.increments, snapshot.ownershipMap, snapshot.evidence)
    }
}
