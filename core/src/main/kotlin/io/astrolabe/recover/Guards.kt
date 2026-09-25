package io.astrolabe.recover

import io.astrolabe.Defaults

/** Bounds of the deterministic guards (§13.2 ladder step 1–2); the global values are D-136 defaults. */
public data class GuardLimits @JvmOverloads constructor(
    val doomLoopSameCalls: Int = 3,
    val repeatedSignatureRepairs: Int = 2,
    /** Equivalent no-progress failures (a fingerprint seen again) the whole campaign tolerates. */
    val noProgressBudget: Int = 3,
    /** Failed calls per tool (`family.op`) per campaign. */
    val toolErrors: Int = 8,
    /** Model requests per campaign. */
    val requestCap: Int = 480,
) {
    init {
        require(doomLoopSameCalls >= 2 && repeatedSignatureRepairs >= 1 && noProgressBudget >= 1 && toolErrors >= 1 && requestCap >= 1)
    }

    public companion object {
        /** The §17 rows (`doomLoopSameCalls`, `repeatedSignatureRepairs`) with the D-136 campaign defaults. */
        @JvmStatic
        public fun of(defaults: Defaults): GuardLimits = GuardLimits(
            doomLoopSameCalls = defaults.doomLoopSameCalls,
            repeatedSignatureRepairs = defaults.repeatedSignatureRepairs,
            requestCap = defaults.turnsPerCell * defaults.campaignCells,
        )
    }
}

/** A guard's verdict: pass, a nudge line for `[A]`, or a trip that stops the repetition. */
public sealed interface GuardVerdict {
    public data object Pass : GuardVerdict

    public data class Nudge(val guard: String, val line: String) : GuardVerdict

    public data class Trip(val guard: String, val line: String) : GuardVerdict
}

/**
 * The deterministic guards of one campaign (§13.2 steps 1–2), shared by every cell of it so a distributed doom loop
 * counts against one global no-progress budget (FX-33). The doom loop is per cell over `(tool, args, state)`: a
 * changed state — a meaningful edit or a new observation — resets it. Fingerprints and signatures are
 * campaign-scoped; the signature gate outlives a changed fix. Thread-safe: parallel cells report concurrently.
 */
public class Guards @JvmOverloads constructor(public val limits: GuardLimits = GuardLimits()) {
    private data class LastCall(val tool: String, val args: String, val state: String, val count: Int)

    private val lastCall = HashMap<String, LastCall>()
    private val toolErrors = HashMap<String, Int>()
    private var requests = 0
    private val seen = HashMap<String, String>()
    private val fixes = HashMap<ErrorSignature, LinkedHashSet<String>>()
    private val nudged = HashSet<Pair<ErrorSignature, Int>>()
    private val noProgress = ArrayList<String>()

    /** Global equivalent no-progress failures so far, as `cell:fingerprint8`. */
    public val noProgressEvents: List<String> get() = synchronized(this) { noProgress.toList() }

    /** Doom loop: the same tool and arguments [GuardLimits.doomLoopSameCalls] times in a row in [cell] without a state change. */
    public fun call(cell: String, tool: String, args: String, state: String): GuardVerdict = synchronized(this) {
        val previous = lastCall[cell]
        val count = if (previous != null && previous.tool == tool && previous.args == args && previous.state == state) previous.count + 1 else 1
        lastCall[cell] = LastCall(tool, args, state, count)
        if (count >= limits.doomLoopSameCalls) {
            GuardVerdict.Trip(DOOM_LOOP, "doom loop: $tool with the same arguments $count times without a state change — change the input or record what you learned")
        } else GuardVerdict.Pass
    }

    /** A failed call of [tool]; the campaign's per-tool error budget. */
    public fun toolError(tool: String): GuardVerdict = synchronized(this) {
        val n = (toolErrors[tool] ?: 0) + 1
        toolErrors[tool] = n
        if (n >= limits.toolErrors) GuardVerdict.Trip(TOOL_ERRORS, "error budget of $tool spent: $n failed calls this campaign") else GuardVerdict.Pass
    }

    /** One model request; the campaign's request cap. */
    public fun request(): GuardVerdict = synchronized(this) {
        requests += 1
        if (requests > limits.requestCap) GuardVerdict.Trip(REQUEST_CAP, "request cap ${limits.requestCap} reached") else GuardVerdict.Pass
    }

    /**
     * A verified failure in [cell]. A fingerprint seen before anywhere in the campaign is a no-progress event
     * against the global budget; the same signature after [GuardLimits.repeatedSignatureRepairs] different fixes
     * nudges toward a changed hypothesis, a dead end or an alternative attempt (§13.2, §13.3).
     */
    public fun failure(cell: String, fingerprint: Fingerprint): GuardVerdict = synchronized(this) {
        val key = fingerprint.hash.hex
        val first = seen[key]
        if (first == null) seen[key] = cell else noProgress += "$cell:${key.take(8)}"
        val tried = fixes.getOrPut(fingerprint.signature) { LinkedHashSet() }.apply { add(fingerprint.attemptedFix) }
        val repairs = tried.size - 1
        when {
            noProgress.size >= limits.noProgressBudget -> GuardVerdict.Trip(
                NO_PROGRESS,
                "no progress: ${noProgress.size} equivalent failures across the campaign (${noProgress.joinToString(", ")}) — record a dead end or ask",
            )
            repairs >= limits.repeatedSignatureRepairs && nudged.add(fingerprint.signature to repairs) -> GuardVerdict.Nudge(
                REPEATED_SIGNATURE,
                "same error after $repairs repairs: ${fingerprint.signature.text} — change the hypothesis, record a dead end or request an alternative attempt",
            )
            first != null -> GuardVerdict.Nudge(NO_PROGRESS, "no progress: the same failure, fix and state as in $first — this is not a new strategy")
            else -> GuardVerdict.Pass
        }
    }

    public companion object {
        public const val DOOM_LOOP: String = "doom-loop"
        public const val TOOL_ERRORS: String = "tool-errors"
        public const val REQUEST_CAP: String = "request-cap"
        public const val NO_PROGRESS: String = "no-progress"
        public const val REPEATED_SIGNATURE: String = "repeated-signature"
    }
}
