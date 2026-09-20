package io.astrolabe.auth

import kotlinx.serialization.Serializable

/**
 * Permission ladder stages (§14.2): separate grants, the contract sets the ceiling. The harness reports the
 * highest *authorized* stage reached, never "delivered" for a patch.
 */
@Serializable
public enum class Stage { Patch, LocalCommit, Push, Merge, Deploy }
