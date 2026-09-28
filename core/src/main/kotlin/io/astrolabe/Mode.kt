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

/**
 * Who closes an unknown outcome left by a crash (§13.1, D-171, D-321). [Host]: only
 * [io.astrolabe.evidence.IntentJournal.reconcile] by the host lifts the workspace fence. [Automatic]: at open the
 * controller itself reconciles, with persisted evidence, this work's replay-safe read-only intents and the
 * foreground intents whose classified effects stay inside the workspace, now observed by the stamp; D-class,
 * external and background effects keep the fence.
 */
@Serializable
public enum class UnknownOutcomeReconciliation { Host, Automatic }
