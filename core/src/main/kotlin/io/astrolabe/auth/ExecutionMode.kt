package io.astrolabe.auth

import kotlinx.serialization.Serializable

/**
 * Execution mode label (§4.6, §14.1). [TrustedLocal] has no confinement: effect classes only, labelled as such
 * in `[S]` and every report. [Confined] requires an external isolation backend; a host that requires it fails
 * at configuration or dispatch until one exists (D-11) — trusted-local is never substituted. The label
 * describes limitations; it enforces nothing.
 */
@Serializable
public enum class ExecutionMode { TrustedLocal, Confined }
