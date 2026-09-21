package io.astrolabe.fixtures

import io.astrolabe.id.Digest
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Capabilities
import io.astrolabe.provider.Estimate
import io.astrolabe.provider.Invocation
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.InvocationState
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Problem
import io.astrolabe.provider.ProblemKind
import io.astrolabe.provider.Profile
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.provider.ProviderError
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Request
import io.astrolabe.provider.Response
import io.astrolabe.provider.Role
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.Terminal
import io.astrolabe.provider.Text
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.UsageNormalizer
import io.astrolabe.provider.UsageProvenance
import io.astrolabe.provider.Validation
import io.astrolabe.provider.Validations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections

/** Which cache-write class each new cache write is billed in; cycles so mixed invoices are reproducible (IX-16). */
public class FakeCachePolicy(private val writeClasses: List<BillingDimension> = listOf(BillingDimension.CACHE_WRITE_5M)) {
    private var i = 0

    init {
        require(writeClasses.isNotEmpty() && writeClasses.all { it.isCacheWrite })
    }

    @Synchronized
    public fun next(): BillingDimension = writeClasses[i++ % writeClasses.size]
}

/**
 * The only model this plan validates against (TODO P0.3.5): scripted replies, deterministic cache simulation
 * with its own tokenizer, injectable faults, and D-51 invocation states with cancellation races.
 *
 * Cache simulation (D-29, a simulation, not a prediction): the leading segments that are byte-identical to the
 * previous request's and were marked as breakpoints there are billed as `cache_read`; a segment that merely
 * *appends* items after such a previous breakpoint segment still reads that prefix from cache and pays only for
 * its tail (the prefix the breakpoint closed is unchanged, as with a provider's prefix cache); everything else is
 * `uncached_input`, and every breakpoint segment not already cached is additionally billed as a cache write, for
 * the uncached part, in the class chosen by [cachePolicy]. A rewrite anywhere before a segment's previous end is
 * therefore a miss for that segment and everything after it (F26, P1.8.6).
 */
public class FakeAdapter(
    private val model: ScriptedModel,
    private val profiles: Map<String, Profile> = FakeProfiles.all,
    private val cachePolicy: FakeCachePolicy = FakeCachePolicy(),
    /** Items the provider emits after a cancellation was requested (AX-08). */
    private val lateOutputOnCancel: List<Item> = emptyList(),
    /** When false a cancelled call reports no usage at all (unknown), so the caller's hold must stay. */
    private val usageOnCancel: Boolean = true,
    /** When true, `await` suspends until [release] so tests can interleave cancellation (IX-15). */
    private val holdResponses: Boolean = false,
) : ProviderAdapter {
    override val id: String = "fake"

    public data class RecordedCall(
        val invocation: InvocationId,
        val request: Request,
        val segmentKinds: List<SegmentKind>,
        val breakpoints: List<SegmentKind>,
        val fakeInputTokens: Long,
        val cacheReadTokens: Long,
        val cacheWriteTokens: Long,
        val outcome: String,
    )

    public data class RecordedValidation(val request: Request, val estimate: Estimate, val fakeInputTokens: Long, val result: Validation) {
        /** Fake count minus the caller's estimate: positive means the heuristic under-counted (IX-17). */
        val driftTokens: Long get() = fakeInputTokens - estimate.tokens
    }

    private val callsMutable = Collections.synchronizedList(ArrayList<RecordedCall>())
    private val validationsMutable = Collections.synchronizedList(ArrayList<RecordedValidation>())
    private val invocations = Collections.synchronizedMap(LinkedHashMap<InvocationId, FakeInvocation>())
    private val lock = Any()
    private var previousSegments: List<SegmentFingerprint> = emptyList()

    public val calls: List<RecordedCall> get() = synchronized(callsMutable) { callsMutable.toList() }
    public val validations: List<RecordedValidation> get() = synchronized(validationsMutable) { validationsMutable.toList() }

    override fun capabilities(profile: Profile): Capabilities = (profiles[profile.id] ?: profile).capabilities

    override fun validate(request: Request, estimate: Estimate): Validation {
        val capabilities = capabilities(request.profile)
        val problems = ArrayList<Problem>()
        (Validations.standard(request, estimate, capabilities) as? Validation.Rejected)?.let { problems += it.problems }
        // AX-07: opaque reasoning from another model family is never replayed here.
        request.items.filterIsInstance<ReasoningRef>().filter { it.providerTag != FakeProfiles.PROVIDER }.forEach {
            problems += Problem(ProblemKind.InvalidRequest, "reasoning_ref from provider '${it.providerTag}' cannot be replayed on ${request.profile.model}")
        }
        val fakeCount = FakeTokenizer.count(request)
        if (!estimate.unknownHistory && fakeCount + request.maxOutputTokens > capabilities.contextLimitTokens) {
            problems += Problem(ProblemKind.ContextOverflow, "provider count $fakeCount + output ${request.maxOutputTokens} exceeds ${capabilities.contextLimitTokens}")
        }
        val result = if (problems.isEmpty()) Validation.Ok else Validation.Rejected(problems)
        validationsMutable += RecordedValidation(request, estimate, fakeCount, result)
        return result
    }

    override fun start(request: Request, id: InvocationId): Invocation {
        require(id !in invocations) { "invocation $id already registered" }
        return FakeInvocation(id, request).also { invocations[id] = it }
    }

    override val normalizer: UsageNormalizer = UsageNormalizer { native, profile ->
        Json.decodeFromString(BillableUsage.serializer(), native.toString()).copy(
            provenance = UsageProvenance(FakeProfiles.PROVIDER, profile.model, PROTOCOL),
        )
    }

    /** Lets a held invocation proceed (only meaningful with `holdResponses = true`). */
    public fun release(id: InvocationId) {
        invocations[id]?.gate?.complete(Unit)
    }

    public fun invocation(id: InvocationId): Invocation? = invocations[id]

    private data class ItemFingerprint(val digest: Digest, val tokens: Long)

    private data class SegmentFingerprint(val kind: SegmentKind, val items: List<ItemFingerprint>, val breakpoint: Boolean) {
        val tokens: Long get() = items.sumOf { it.tokens }

        /** Tokens of the leading items of this segment that [previous] closed under its breakpoint; 0 unless every previous item is still in place. */
        fun cachedPrefixTokens(previous: SegmentFingerprint): Long {
            if (!previous.breakpoint || previous.items.size > items.size) return 0
            for (i in previous.items.indices) if (previous.items[i].digest != items[i].digest) return 0
            return previous.tokens
        }
    }

    private fun fingerprints(request: Request): List<SegmentFingerprint> = request.segments.map { segment ->
        SegmentFingerprint(segment.kind, segment.items.map { ItemFingerprint(Digest.ofUtf8(Json.encodeToString(Item.serializer(), it)), FakeTokenizer.count(it)) }, segment.breakpoint)
    }

    private data class Bill(val cacheRead: Long, val uncached: Long, val writes: Map<BillingDimension, Long>)

    /** Bills input by segment stability against the previous request; updates the remembered prefix. */
    private fun bill(request: Request, current: List<SegmentFingerprint>): Bill = synchronized(lock) {
        val toolTokens = request.tools.sumOf { FakeTokenizer.count(it.name) + FakeTokenizer.count(it.description) + FakeTokenizer.count(it.jsonSchema.toString()) }
        val historyTokens = request.continuation?.effectiveHistoryTokens ?: 0L
        var cacheRead = 0L
        var uncached = toolTokens + historyTokens
        val writes = LinkedHashMap<BillingDimension, Long>()
        var prefixStable = true
        current.forEachIndexed { i, seg ->
            val prev = previousSegments.getOrNull(i)
            val identical = prefixStable && prev != null && prev.breakpoint && prev.items == seg.items
            if (identical) {
                cacheRead += seg.tokens
            } else {
                val cachedPrefix = if (prefixStable && prev != null) seg.cachedPrefixTokens(prev) else 0L
                prefixStable = false
                cacheRead += cachedPrefix
                val tail = seg.tokens - cachedPrefix
                uncached += tail
                if (seg.breakpoint && tail > 0) writes.merge(cachePolicy.next(), tail, Long::plus)
            }
        }
        previousSegments = current
        Bill(cacheRead, uncached, writes)
    }

    private fun usage(bill: Bill, outputTokens: Long, model: String): BillableUsage {
        val quantities = LinkedHashMap<BillingDimension, Long>()
        quantities[BillingDimension.UNCACHED_INPUT] = bill.uncached
        quantities[BillingDimension.CACHE_READ] = bill.cacheRead
        quantities[BillingDimension.OUTPUT] = outputTokens
        bill.writes.forEach { (dim, n) -> quantities.merge(dim, n, Long::plus) }
        val native = JsonObject(quantities.entries.associate { it.key.id to JsonPrimitive(it.value) })
        return BillableUsage(quantities, UsageProvenance(FakeProfiles.PROVIDER, model, PROTOCOL), native = native)
    }

    private inner class FakeInvocation(override val id: InvocationId, private val request: Request) : Invocation {
        internal val gate = CompletableDeferred<Unit>()

        @Volatile
        override var state: InvocationState = InvocationState.Requested
            private set

        private val terminalDeferred = CompletableDeferred<Terminal>()

        @Volatile
        private var terminalRecord: Terminal? = null

        @Volatile
        private var cancelRequested = false

        private val stateLock = Any()

        override suspend fun await(): Response {
            if (holdResponses && !cancelRequested) gate.await()
            val terminal = synchronized(stateLock) {
                terminalRecord ?: run {
                    val t = if (cancelRequested) cancelledTerminal() else complete()
                    state = InvocationState.TerminalReconciled
                    terminalRecord = t
                    terminalDeferred.complete(t)
                    t
                }
            }
            terminal.error?.let { throw it }
            return checkNotNull(terminal.response)
        }

        override fun cancel() {
            synchronized(stateLock) {
                if (state == InvocationState.TerminalReconciled) return
                cancelRequested = true
                state = InvocationState.CancelRequested
                gate.complete(Unit)
            }
        }

        override suspend fun terminal(): Terminal = terminalDeferred.await()

        /** Cancellation observed before completion: no tool call escapes; late output and usage go to the terminal only. */
        private fun cancelledTerminal(): Terminal {
            val bill = bill(request, fingerprints(request))
            val usage = if (usageOnCancel) usage(bill, FakeTokenizer.count(lateOutputOnCancel), request.profile.model) else null
            callsMutable += record(bill, "cancelled")
            return Terminal(id, Response(emptyList(), StopReason.Cancelled), null, lateOutputOnCancel, usage, cancelled = true)
        }

        private fun complete(): Terminal {
            state = InvocationState.ProviderAcknowledged
            val bill = bill(request, fingerprints(request))
            return when (val scripted = model.next(request)) {
                is Scripted.Reply -> {
                    val usage = if (scripted.missingUsage) {
                        BillableUsage.missing(UsageProvenance(FakeProfiles.PROVIDER, request.profile.model, PROTOCOL), FakeProfiles.dimensions)
                    } else {
                        usage(bill, FakeTokenizer.count(scripted.items), request.profile.model)
                    }
                    val response = Response(scripted.items, scripted.stop, usage, scripted.continuation)
                    callsMutable += record(bill, "reply:${scripted.stop}")
                    Terminal(id, response, null, emptyList(), usage, cancelled = false)
                }
                is Scripted.Fault -> fault(scripted, bill)
            }
        }

        private fun fault(fault: Scripted.Fault, bill: Bill): Terminal {
            val textOnly: List<Item> = fault.partialItems.filter { it !is ToolCall }
            val usage = usage(bill, FakeTokenizer.count(textOnly), request.profile.model)
            callsMutable += record(bill, "fault:${fault.kind}")
            fun response(stop: StopReason) = Terminal(id, Response(textOnly, stop, usage), null, emptyList(), usage, cancelled = false)
            fun error(e: ProviderError) = Terminal(id, null, e, textOnly, usage, cancelled = false)
            return when (fault.kind) {
                FaultKind.InterruptedStream -> response(StopReason.Truncated)
                FaultKind.OutputLimit -> response(StopReason.OutputLimit)
                FaultKind.RefusalStop -> Terminal(
                    id, Response(listOf(Message(Role.Assistant, listOf(Text("I cannot help with that.")))), StopReason.Refusal, usage), null, emptyList(), usage, false,
                )
                FaultKind.RefusalError -> error(ProviderError.Refusal("provider refused the request"))
                FaultKind.ExpiredContinuation -> error(ProviderError.ExpiredContinuation("continuation expired; start a fresh lineage"))
                FaultKind.UnsupportedSchema -> error(ProviderError.UnsupportedSchema("tool schema rejected by provider"))
                FaultKind.Transport -> error(ProviderError.Transport("connection reset"))
                FaultKind.RateLimit -> error(ProviderError.RateLimit("rate limited", fault.retryAfterSeconds))
            }
        }

        private fun record(bill: Bill, outcome: String) = RecordedCall(
            invocation = id,
            request = request,
            segmentKinds = request.segments.map { it.kind },
            breakpoints = request.segments.filter(Segment::breakpoint).map { it.kind },
            fakeInputTokens = FakeTokenizer.count(request),
            cacheReadTokens = bill.cacheRead,
            cacheWriteTokens = bill.writes.values.sum(),
            outcome = outcome,
        )
    }

    public companion object {
        public const val PROTOCOL: String = "fake/1"
    }
}
