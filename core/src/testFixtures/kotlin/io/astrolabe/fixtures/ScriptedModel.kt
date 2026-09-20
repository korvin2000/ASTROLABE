package io.astrolabe.fixtures

import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.OpaqueContinuation
import io.astrolabe.provider.Request
import io.astrolabe.provider.Role
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.ToolCall

/** What the scripted model does for one request: a normal reply or an injected provider fault. */
public sealed interface Scripted {
    public data class Reply(
        val items: List<Item>,
        val stop: StopReason = if (items.any { it is ToolCall }) StopReason.ToolUse else StopReason.EndTurn,
        val continuation: OpaqueContinuation? = null,
        val missingUsage: Boolean = false,
    ) : Scripted

    public data class Fault(val kind: FaultKind, val partialItems: List<Item> = emptyList(), val retryAfterSeconds: Long? = null) : Scripted
}

/** Provider faults the fake can inject (AX-01, AX-03, AX-04, AX-05, AX-09 and transport classes). */
public enum class FaultKind {
    /** Stream interrupted mid tool call: partial text survives, no tool call is exposed, stop = Truncated. */
    InterruptedStream,

    /** Output limit reached: stop = OutputLimit, partial text kept, no tool call. */
    OutputLimit,

    /** The model refused as a normal completion: stop = Refusal. */
    RefusalStop,

    /** The provider refused the request: `await` throws ProviderError.Refusal. */
    RefusalError,

    ExpiredContinuation,
    UnsupportedSchema,
    Transport,
    RateLimit,
}

/**
 * Deterministic model script: ordered turns, each consumed once when its matcher accepts the request; when no
 * turn matches, [fallback] answers. Thread-safe.
 */
public class ScriptedModel(
    private val turns: List<Turn>,
    private val fallback: Scripted = Scripted.Reply(listOf(Message.text(Role.Assistant, "script exhausted"))),
) {
    public class Turn(public val matcher: (Request) -> Boolean, public val scripted: (Request) -> Scripted, public val once: Boolean = true) {
        @Volatile
        internal var consumed: Boolean = false
    }

    @Synchronized
    public fun next(request: Request): Scripted {
        val turn = turns.firstOrNull { !it.consumed && it.matcher(request) } ?: return fallback
        if (turn.once) turn.consumed = true
        return turn.scripted(request)
    }

    public val remaining: Int get() = turns.count { !it.consumed }

    public class Builder {
        private val turns = ArrayList<Turn>()
        private var fallback: Scripted = Scripted.Reply(listOf(Message.text(Role.Assistant, "script exhausted")))

        /** A turn taken by any request, in order. */
        public fun reply(vararg items: Item): Builder = on({ true }, Scripted.Reply(items.toList()))

        public fun reply(scripted: Scripted): Builder = on({ true }, scripted)

        public fun on(matcher: (Request) -> Boolean, scripted: Scripted): Builder = apply { turns += Turn(matcher, { scripted }) }

        public fun on(matcher: (Request) -> Boolean, scripted: (Request) -> Scripted): Builder = apply { turns += Turn(matcher, scripted) }

        /** Matches when the concatenated user/assistant text of the request contains [needle]. */
        public fun whenTextContains(needle: String, scripted: Scripted): Builder =
            on({ req -> req.items.filterIsInstance<Message>().any { it.text.contains(needle) } }, scripted)

        public fun fault(kind: FaultKind, vararg partial: Item): Builder = reply(Scripted.Fault(kind, partial.toList()))

        public fun fallback(scripted: Scripted): Builder = apply { fallback = scripted }

        public fun build(): ScriptedModel = ScriptedModel(turns.toList(), fallback)
    }

    public companion object {
        @JvmStatic
        public fun build(configure: Builder.() -> Unit): ScriptedModel = Builder().apply(configure).build()

        /** Sequential replies, one per request. */
        @JvmStatic
        public fun of(vararg replies: Scripted): ScriptedModel = ScriptedModel(replies.map { r -> Turn({ true }, { r }) })
    }
}
