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

/**
 * Who may approve a blocking test-integrity flag (§8.6, D-23, D-320). [Autonomous]: in S2+, and for a flag-only
 * review (no Check/Review item) in any shape, the review cell's approval suffices, with the human path as
 * fallback, so long autonomous campaigns can proceed. [Human]: only a verdict from
 * [io.astrolabe.event.Authority.review] resolves the flag; the review cell is not asked.
 */
@Serializable
public enum class IntegrityApproval { Autonomous, Human }
