package io.astrolabe.route

import kotlinx.serialization.Serializable

/** Routing tiers (§11.1). The tier table binding tiers to profiles arrives with P4.5.1. */
@Serializable
public enum class Tier { Low, Medium, High, ExtraHigh, Deterministic }
