package io.astrolabe.context

import io.astrolabe.cell.CompiledK
import io.astrolabe.cell.Role
import io.astrolabe.cell.Transcript
import io.astrolabe.id.Digest
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Profile
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Role as ItemRole
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.UsageItem
import io.astrolabe.register.RegisterRender

/** The five uses of the one rebuild mechanism (§5.8). */
public sealed interface RebuildReason {
    public val wire: String

    /** How many complete protocol turns the new `[T]` keeps (§5.8: 6, or 0 at a role switch, alternative or cell end). */
    public val tailTurns: Int

    public data object Pressure : RebuildReason {
        override val wire: String get() = "pressure"
        override val tailTurns: Int get() = 6
    }

    public data object Resume : RebuildReason {
        override val wire: String get() = "resume"
        override val tailTurns: Int get() = 6
    }

    public data class RoleSwitch(val role: Role) : RebuildReason {
        override val wire: String get() = "role_switch(${role.name})"
        override val tailTurns: Int get() = 0
    }

    /** [profile] is the escalation profile, or `null` to keep the current one; the controller assigns the new attempt id. */
    public data class AlternativeAttempt(val profile: Profile? = null) : RebuildReason {
        override val wire: String get() = "alternative_attempt" + (profile?.let { "(${it.id})" } ?: "")
        override val tailTurns: Int get() = 0
    }

    public data class CellEnd(val next: Next) : RebuildReason {
        override val wire: String get() = "cell_end(${next.name.lowercase()})"
        override val tailTurns: Int get() = 0

        public enum class Next { NextIncrement, Continuation }
    }
}

/**
 * One installed context projection: the `[S]` inputs (role, profile), `[R]`, the compiled `[K]`, the `[T]` transcript,
 * the `[A]` state text and its [generation]. [freshLineage] means no provider continuation carries over (§5.8).
 */
public data class Projection(
    val generation: Int,
    val role: Role,
    val profile: Profile,
    val repository: String,
    val k: CompiledK,
    val transcript: Transcript,
    val anchor: String,
    val freshLineage: Boolean = true,
) {
    init {
        require(generation >= 0) { "generation must be ≥ 0" }
    }
}

/** What a rebuild did, for the journal and the manifest: generations, reuse of `[S]`/`[R]`, the kept tail. */
public data class RebuildRecord(
    val reason: String,
    val fromGeneration: Int,
    val toGeneration: Int,
    val systemReused: Boolean,
    val repositoryReused: Boolean,
    val tailTurns: Int,
    val droppedItems: Int,
)

/** The side effects of a rebuild, owned by the controller: checkpoint, STATUS note, new attempt id. */
public interface RebuildHooks {
    /** Persists STATE, the Workset export, receipts and the journal for [old] before the new projection is installed. */
    public fun checkpoint(old: Projection, reason: RebuildReason)

    /** Writes the STATUS note revision (role switch, cell end). */
    public fun status(reason: RebuildReason)
}

/**
 * The one rebuild mechanism (§5.8, P2.5.1): checkpoint the old projection, then build the whole new one — `[S][R]`
 * reused only when their inputs still match, `[K]` from [compileK] (seeds + carry-forward), `[T]` = pinned messages +
 * previous packet + `rebuilt: <reason>` + the last `m` complete protocol turns, `[A]` = contract digest + validated
 * STATE + the declared KNOWN line — and a new generation. No model summarises anything; no provider continuation is
 * reused, and a kept turn keeps every call with its result and its opaque reasoning items.
 */
public object Rebuild {
    @JvmStatic
    public fun run(
        reason: RebuildReason,
        current: Projection,
        carry: Carry,
        digest: String,
        repository: String,
        compileK: (Role, Profile) -> CompiledK,
        hooks: RebuildHooks,
    ): Pair<Projection, RebuildRecord> {
        hooks.checkpoint(current, reason)
        if (reason is RebuildReason.RoleSwitch || reason is RebuildReason.CellEnd) hooks.status(reason)
        val role = (reason as? RebuildReason.RoleSwitch)?.role ?: current.role
        val profile = (reason as? RebuildReason.AlternativeAttempt)?.profile ?: current.profile
        val systemReused = role == current.role && profile == current.profile
        val repositoryReused = Digest.ofUtf8(repository) == Digest.ofUtf8(current.repository)
        val tail = tail(current.transcript.items, reason.tailTurns)
        val pinned = carry.pinned + listOfNotNull(carry.packetLine?.let { "previous packet: $it" }) + "rebuilt: ${reason.wire} (generation ${current.generation + 1})"
        val deadEnds = if (reason is RebuildReason.AlternativeAttempt && carry.register.deadEnds.isNotEmpty()) {
            "DEAD ENDS — do not repeat:\n" + carry.register.deadEnds.joinToString("\n") { "- ${it.text} (scope ${it.scope})" } + "\n"
        } else {
            ""
        }
        val anchor = digest.trimEnd() + "\n" + deadEnds + RegisterRender.markdown(carry.register) + carry.known
        val next = Projection(
            generation = current.generation + 1,
            role = role,
            profile = profile,
            repository = repository,
            k = compileK(role, profile),
            transcript = Transcript(pinned, tail),
            anchor = anchor,
            freshLineage = true,
        )
        return next to RebuildRecord(reason.wire, current.generation, next.generation, systemReused, repositoryReused, reason.tailTurns, current.transcript.items.size - tail.size)
    }

    /**
     * The last [turns] complete protocol turns of [items]: a turn opens at an assistant item and closes when every
     * call it made has its result. An incomplete turn is never kept, so no call is left without its result; usage
     * items are accounting and never replayed.
     */
    @JvmStatic
    public fun tail(items: List<Item>, turns: Int): List<Item> {
        if (turns <= 0) return emptyList()
        val groups = ArrayList<MutableList<Item>>()
        for (item in items) {
            if (item is UsageItem) continue
            val last = groups.lastOrNull()
            // A model turn (reasoning, text, calls) opens once the previous turn already holds results or user input.
            val opens = assistantSide(item) && (last == null || last.any { !assistantSide(it) })
            if (opens || last == null) groups += arrayListOf(item) else last += item
        }
        return groups.filter { group -> group.any(::assistantSide) && complete(group) }.takeLast(turns).flatten()
    }

    private fun assistantSide(item: Item): Boolean =
        item is ReasoningRef || item is ToolCall || (item is Message && item.role == ItemRole.Assistant)

    private fun complete(group: List<Item>): Boolean {
        val calls = group.filterIsInstance<ToolCall>().map { it.id }.toSet()
        val results = group.filterIsInstance<ToolResult>().map { it.callId }.toSet()
        return calls == results
    }
}
