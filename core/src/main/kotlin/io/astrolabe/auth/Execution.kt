package io.astrolabe.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The honest execution-mode label for `[S]` and every report (§14.1, §4.6). The harness never calls a
 * denylist or a worktree a sandbox: the label states what the mode does and does not do. It is a description;
 * enforcement is the executor's capability ceiling and the confinement backend, never this string.
 */
public object ExecutionModeLabel {
    /** `trusted-local` / `confined`. */
    @JvmStatic
    public fun short(mode: ExecutionMode): String = when (mode) {
        ExecutionMode.TrustedLocal -> "trusted-local"
        ExecutionMode.Confined -> "confined"
    }

    /** One `[S]` line naming the mode and its limitations. */
    @JvmStatic
    public fun render(mode: ExecutionMode): String = when (mode) {
        ExecutionMode.TrustedLocal ->
            "execution: trusted-local — no confinement; effect classes are policy labels verified after the fact " +
                "by the stamp diff, an R label is not proof of read-only execution, and a command denylist is not a sandbox."
        ExecutionMode.Confined ->
            "execution: confined — external runner with harness-supplied policy: writable roots = workspace + tmp, " +
                "env allowlist, network off by default, resource limits and timeouts."
    }
}

/** Whether a run may be dispatched, and under which label. */
@Serializable
public sealed interface ExecutionDecision {
    /** Dispatch is authorized. [backend] is the confinement backend id, `null` in trusted-local. */
    @Serializable
    @SerialName("dispatch")
    public data class Dispatch(val mode: ExecutionMode, val backend: String?, val label: String) : ExecutionDecision

    @Serializable
    @SerialName("refused")
    public data class Refused(val refusal: Refusal) : ExecutionDecision
}

/**
 * Selects the runner for a configured [ExecutionMode] (D-11). A host that *requires* `Confined` fails at
 * configuration or dispatch until a backend exists (P7): trusted-local is **never** substituted and a
 * trusted-local run is never relabelled as confined.
 */
public object Executors {
    /** The refusal text used when confinement is required and no backend is registered. */
    public const val NO_BACKEND: String = "confined execution required; no backend"

    /**
     * @param availableBackends ids of registered confinement backends (container, bwrap, sandbox-exec,
     *   firejail). Empty until P7 ships one.
     */
    @JvmStatic
    @JvmOverloads
    public fun require(mode: ExecutionMode, availableBackends: Set<String> = emptySet()): ExecutionDecision =
        when (mode) {
            ExecutionMode.TrustedLocal ->
                ExecutionDecision.Dispatch(mode, null, ExecutionModeLabel.render(mode))
            ExecutionMode.Confined -> {
                val backend = availableBackends.minOrNull()
                if (backend == null) {
                    ExecutionDecision.Refused(
                        Refusal("execution", RefusalReason.ConfinementUnavailable, NO_BACKEND),
                    )
                } else {
                    ExecutionDecision.Dispatch(mode, backend, ExecutionModeLabel.render(mode))
                }
            }
        }
}
