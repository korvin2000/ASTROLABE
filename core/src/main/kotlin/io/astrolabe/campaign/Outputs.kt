package io.astrolabe.campaign

import io.astrolabe.contract.Contract
import io.astrolabe.contract.OutputDeclarer
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import java.time.Clock

/**
 * "Declare as the output of this task" (task-workflow §5.1, D-435). A user's or a host's declaration applies at once — a
 * visible journal line and a host-origin revision of the contract (`version` + 1, so the `DecisionKey` moves with the
 * identity it will change); the model's proposal goes through the task tool (a question in ask mode, refused in auto mode
 * unless the policy allows it). The harness validates every declaration. It takes effect from the next attempt, whose
 * effective output policy freezes the contract's outputs before its `s0`; [applyNow] is the explicit, journalled way to
 * end the running attempt for it. Nothing here touches the running attempt's identity, receipts or keys.
 */
public object DeclaredOutputs {
    /**
     * Why [path] cannot be declared an output of [c]'s task, or `null` when it can: it must be inside the repository,
     * untracked, no dependency lock file, not protected, not the directory of a toolchain a registered check launches, and
     * not a declared input of any registered check's closure (D-374: such a path stays pinned and re-read).
     */
    @JvmStatic
    public fun refusal(c: OpenedCampaign, path: String): String? {
        val normalized = normalize(path)
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.split('/').any { it == ".." || it == "." } || ':' in normalized) {
            return "$path is not a path inside the repository"
        }
        (c.workspace.resolve(normalized, Intent.Read) as? PathResolution.Rejected)?.let { return "$path is refused: ${it.detail}" }
        if (c.contract.outputs.any { normalize(it.path) == normalized }) return "$normalized is declared already"
        if (normalized.substringAfterLast('/') in EnvFingerprint.LOCK_FILE_NAMES) return "$normalized is a dependency lock file: it stays an input (D-429)"
        if (c.workspace.git.lsFiles().any { it.path == normalized }) return "$normalized is tracked: a tracked path is never excluded (D-429)"
        if (c.contract.scope.protects(normalized, ignoreCase = true) || c.workspace.paths.isProtected(normalized, Intent.Mutate)) return "$normalized is a protected path"
        for (check in c.checks.all()) {
            val program = check.command?.argv?.firstOrNull()?.replace('\\', '/')?.removePrefix("./")
            if (program != null && under(program, normalized)) return "$normalized holds the toolchain ${check.id} launches ($program)"
            val input = when (val closure = check.inputClosure) {
                is Closure.Known -> closure.paths.map(::normalize).firstOrNull { under(it, normalized) }
                is Closure.Package -> normalize(closure.path).takeIf { under(normalized, it) || under(it, normalized) }
                Closure.Unknown -> null
            }
            if (input != null) return "$normalized holds $input, a declared input of ${check.id}: it stays pinned (D-374)"
        }
        return null
    }

    /**
     * Records [path] as the task's output declared [by] the user or the host (or the model once its proposal was approved),
     * after [refusal]; throws [IllegalArgumentException] with the reason when it is refused. The running attempt's identity
     * does not move: the declaration applies from the next attempt, or now through [applyNow].
     */
    @JvmStatic
    @JvmOverloads
    public fun declare(c: OpenedCampaign, path: String, by: OutputDeclarer, reason: String, cell: ContextId? = null, clock: Clock = Clock.systemUTC()): Contract {
        refusal(c, path)?.let { throw IllegalArgumentException(it) }
        val normalized = normalize(path)
        val declared = c.contracts.declareOutput(c.ids.work, normalized, by, reason, cell)
        c.journal.append(JournalEvent("ev-output-${c.ids.attempt.value}-v${declared.version}", c.ids, null, JournalKind.Reconcile, refs = listOf(normalized),
            text = "declared output $normalized by ${by.name.lowercase()} (contract v${declared.version}): $reason — it leaves candidate identity from the next attempt", at = clock.instant()))
        return declared
    }

    /**
     * "Apply now" (§5.1): the user or the host ends the running attempt of [c] so that [next] opens on the current tree — a
     * new `s0` under the effective policy with the declared outputs, the baseline per §8.5 — and keeps the old attempt's
     * receipts as history. The contract moves to [next] in a host-origin revision; the host then opens [next]. Refused
     * when no declared output waits for the next attempt.
     */
    @JvmStatic
    @JvmOverloads
    public fun applyNow(c: OpenedCampaign, next: AttemptId, clock: Clock = Clock.systemUTC()): Contract {
        require(next != c.ids.attempt) { "the next attempt has another id than ${next.value}" }
        val pending = c.contract.outputs.map { normalize(it.path) }.filterNot { c.attempt.scratch.isOutput(it) }
        require(pending.isNotEmpty()) { "no declared output waits for the next attempt" }
        c.journal.append(JournalEvent("ev-apply-${c.ids.attempt.value}-${next.value}", c.ids, null, JournalKind.Boundary, refs = pending,
            text = "attempt ${c.ids.attempt.value} ends: declared output ${pending.joinToString(", ")} applied now; attempt ${next.value} opens on the current tree", at = clock.instant()))
        return c.contracts.amendByHost(c.ids.work, "attempt ${next.value}: declared output ${pending.joinToString(", ")} applied now") { it.copy(attemptId = next) }
    }

    private fun normalize(path: String): String = path.replace('\\', '/').trim().trimEnd('/')

    private fun under(path: String, root: String): Boolean = path == root || path.startsWith("$root/")
}
