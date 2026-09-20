package io.astrolabe

import kotlinx.serialization.Serializable

/** Who answers `blocked` (§1.2): a human in [Interactive], policy in [Autonomous]. */
@Serializable
public enum class Mode { Interactive, Autonomous }

/**
 * Policy for D-class effects (§4.6): [Ask] ends the turn with a question in interactive mode; [Deny] refuses
 * with a recorded reason. In autonomous mode an `Ask` degrades to a denial unless the contract allowlists
 * the effect. Autonomous acceptance of a weakening is never a policy option.
 */
@Serializable
public enum class DClassPolicy { Ask, Deny }
