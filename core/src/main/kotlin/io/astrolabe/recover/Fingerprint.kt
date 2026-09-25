package io.astrolabe.recover

import io.astrolabe.id.Digest
import io.astrolabe.verify.PreexistingLedger

/**
 * The coarse error signature (D-19): the operation plus the first error line with only identified volatile data
 * normalized. Workspace-relative paths, modules, error codes and meaningful literals stay, so two different
 * errors never collapse into one; it is kept apart from the [Fingerprint] so a changed fix cannot erase
 * repeated-error detection.
 */
public data class ErrorSignature(val operation: String, val text: String) {
    init {
        require(operation.isNotBlank()) { "an error signature names its operation" }
    }

    public companion object {
        /** The signature of [error] from [operation]; an absolute [workspaceRoot] prefix is made workspace-relative first. */
        @JvmStatic
        @JvmOverloads
        public fun of(operation: String, error: String, workspaceRoot: String? = null): ErrorSignature {
            var first = error.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "failed"
            if (!workspaceRoot.isNullOrBlank()) {
                for (root in listOf(workspaceRoot, workspaceRoot.replace('\\', '/'), workspaceRoot.replace('/', '\\')).distinct()) {
                    val prefix = root.trimEnd('/', '\\')
                    first = first.replace("$prefix/", "").replace("$prefix\\", "")
                }
            }
            return ErrorSignature(operation, PreexistingLedger.normalize(first))
        }
    }
}

/**
 * The full failure fingerprint (§13.2 ladder step 2): `hash(errorSignature, attempted fix, relevant state, affected
 * requirement)`, campaign-scoped. A meaningful edit or new observation changes [relevantState], so identical
 * commands against changed inputs yield a new fingerprint.
 */
public data class Fingerprint @JvmOverloads constructor(
    val signature: ErrorSignature,
    val attemptedFix: String,
    val relevantState: String,
    val requirement: String? = null,
) {
    init {
        require(relevantState.isNotBlank()) { "a fingerprint binds the relevant state" }
    }

    public val hash: Digest
        get() = Digest.ofUtf8(listOf(signature.operation, signature.text, attemptedFix, relevantState, requirement.orEmpty()).joinToString("\u0000"))
}
