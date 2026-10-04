package io.astrolabe.evallive

import io.astrolabe.Astrolabe
import io.astrolabe.Defaults
import io.astrolabe.RunSpec
import io.astrolabe.atlas.PackageCommands
import io.astrolabe.atlas.Sniff
import io.astrolabe.auth.ContainmentProbe
import io.astrolabe.auth.EffectPolicy
import io.astrolabe.auth.EffectPolicyConfig
import io.astrolabe.contract.Scope
import io.astrolabe.telemetry.CallAccount
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathKind
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.ProtectedPaths
import io.astrolabe.workspace.WorkspacePath
import io.astrolabe.budget.LimitDecision
import io.astrolabe.budget.LimitKind
import io.astrolabe.budget.LimitRule
import io.astrolabe.budget.LimitSpend
import io.astrolabe.budget.TaskLimits
import io.astrolabe.campaign.BudgetStop
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.contract.Command
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.event.Phase
import io.astrolabe.id.AttemptId
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Charge
import io.astrolabe.provider.Profile
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Invocation
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Money
import io.astrolabe.provider.OpaqueContinuation
import io.astrolabe.provider.ProblemKind
import io.astrolabe.provider.ProviderError
import io.astrolabe.provider.Request
import io.astrolabe.provider.Response
import io.astrolabe.provider.Role
import io.astrolabe.provider.SchemaDialect
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.Text
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.ToolSchema
import io.astrolabe.provider.UsageItem
import io.astrolabe.provider.Validation
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.RunArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.math.MathContext
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The reference arm `loop` (plan §6 B5, §9.1): one transcript and four tools over `provider-api`, held to the rules of
 * a fair comparison in `eval-live/README.md` — the same request and host facts, permissions, task limits, effort,
 * output headroom, cache points and accounting events as the core arm. It is a yardstick, not a product interface.
 */
internal class LoopAttempt(private val clock: Clock, private val idGen: IdGen, private val osName: String = System.getProperty("os.name")) {
    suspend fun run(
        workspace: Path,
        prompt: String,
        binding: ModelBinding,
        events: Events,
        spec: RunSpec,
        deadline: Duration,
        script: SessionScript = SessionScript(),
        work: WorkId = WorkId(idGen.next("W")),
        budget: LoopBudget = LoopBudget(),
    ): AttemptOutcome = coroutineScope {
        val session = Session(workspace.toAbsolutePath().normalize(), prompt, binding, events, spec, script, work, budget)
        val watchdog = launch {
            delay(deadline.toMillis())
            session.halt(Halt.Deadline, "eval-live: the attempt passed its deadline of ${deadline.toMinutes()} minutes")
        }
        val interrupter = script.interruptAfterResponses?.let { k -> Interrupter(events, k) { session.halt(Halt.Interrupted, StudioAttempt.INTERRUPTED) } }
        val closer = script.closeAfterResponses?.let { k -> Interrupter(events, k) { session.halt(Halt.Closed, StudioAttempt.CLOSED) } }
        val messenger = script.message?.let { m -> Interrupter(events, m.afterResponses) { session.messageDue.set(true) } }
        try {
            session.run()
        } finally {
            session.closeTime()
            watchdog.cancel()
            interrupter?.close()
            closer?.close()
            messenger?.close()
        }
    }

    private enum class Halt { Interrupted, Closed, Deadline }

    private inner class Session(
        private val workspace: Path,
        private val prompt: String,
        private val binding: ModelBinding,
        private val events: Events,
        private val spec: RunSpec,
        private val script: SessionScript,
        private val work: WorkId,
        private val budget: LoopBudget,
    ) {
        private val profile = binding.profile
        private val adapter = binding.adapter
        private val estimator = binding.estimators.estimatorFor(profile)
        private val capabilities = adapter.capabilities(profile)
        private val defaults = spec.config.defaults
        private val limits = spec.policy.limits ?: TaskLimits.NONE
        private val headroom = spec.outputHeadroom(profile)
        private val ids = Identities(work, AttemptId(Astrolabe.FIRST_ATTEMPT))
        private val tools = LoopTools(workspace, defaults, spec.config.redaction.envAllowlist, osName)
        private val setup = LoopTools.verification(workspace)
        private val system = LoopTools.SYSTEM + "\n\n" + StudioPolicy.platform(osName) + "\n" + StudioPolicy.verificationText(setup)
        private val schemas = LoopTools.schemas(dialect())
        private val transcript = ArrayList<Item>(listOf(Message.text(Role.User, prompt)))
        private val halted = AtomicReference<Pair<Halt, String>?>()
        private val decisions = ArrayList<PolicyDecision>()
        private val started = clock.millis()
        private var timeClosed = false

        /** Adds this session's active time to the work's budget, once. */
        fun closeTime() {
            if (timeClosed) return
            timeClosed = true
            budget.elapsedMillis += (clock.millis() - started).coerceAtLeast(0)
        }

        @Volatile
        private var current: Invocation? = null
        val messageDue = AtomicBoolean()
        private var messageAt: Int? = null
        private var responses = 0
        private var opId = 0

        fun halt(kind: Halt, reason: String) {
            if (halted.compareAndSet(null, kind to reason)) current?.cancel()
        }

        private fun dialect(): SchemaDialect = capabilities.schemaDialects.let { d ->
            if (SchemaDialect.JSON_SCHEMA_2020_12 in d) SchemaDialect.JSON_SCHEMA_2020_12 else d.firstOrNull() ?: SchemaDialect.JSON_SCHEMA_2020_12
        }

        // README rule 6: [S] and [T] each closed by a breakpoint when the profile takes explicit ones, as `Layout.render`.
        private fun segments(): List<Segment> {
            val breakpoints = capabilities.caching.breakpoints
            return listOf(Segment(SegmentKind.S, listOf(Message.text(Role.System, system)), breakpoints), Segment(SegmentKind.T, transcript.toList(), breakpoints))
        }

        suspend fun run(): AttemptOutcome {
            var checks = 0
            var retries = 0
            var stubbed = false
            val reserveNoted = HashSet<LimitKind>()
            while (true) {
                halted.get()?.let { return end(it) }
                script.message?.let { m ->
                    if (messageAt == null && messageDue.get()) {
                        transcript += Message.text(Role.User, m.text)
                        messageAt = responses
                    }
                }
                val request = Request(segments(), schemas, profile, spec.effort, headroom, sessionKey = work.sessionKey)
                val estimate = estimator.estimate(request)
                when (val validation = adapter.validate(request, estimate)) {
                    Validation.Ok -> Unit
                    is Validation.Rejected -> {
                        val problems = validation.problems.joinToString("; ") { "${it.kind}: ${it.detail}" }
                        if (validation.problems.any { it.kind == ProblemKind.ContextOverflow }) {
                            if (!stubbed && stub()) { stubbed = true; continue }
                            return finished(CampaignOutcome.Failed, null, "context window full: $problems")
                        }
                        return finished(CampaignOutcome.Failed, null, "request refused by ${adapter.id}: $problems")
                    }
                }
                val hold = conservative(profile, estimate.upperBoundTokens, headroom.toLong())
                val spend = spend()
                when (val decision = LimitRule.decide(limits, spend, LimitRule.nextCost(spend, hold))) {
                    is LimitDecision.Exhausted -> return finished(CampaignOutcome.BudgetExhausted, BudgetStop.entries.first { it.limit == decision.kind }.wire, decision.reason)
                    is LimitDecision.Reserve -> if (reserveNoted.add(decision.kind)) {
                        transcript += Message.text(Role.User, RESERVE_NOTE + decision.reason)
                        continue
                    }
                    LimitDecision.Within -> Unit
                }

                events.emit(AgentEvent.Cell.TurnStarted(ids, budget.calls.size + 1, limits.maxRequests ?: Int.MAX_VALUE))
                val id = InvocationId(idGen.next("inv"))
                events.emit(AgentEvent.Cell.ModelRequested(ids, id.value, estimate.tokens, profile.id))
                var invocation: Invocation? = null
                var received: Response? = null
                var failure: Throwable? = null
                try {
                    invocation = adapter.start(request, id)
                    current = invocation
                    if (halted.get() != null) invocation.cancel()
                    received = invocation.await()
                } catch (error: Throwable) {
                    failure = error
                    invocation?.cancel()
                } finally {
                    current = null
                }
                // README rule 8: every dispatched call ends in one `ModelResponded` with its reconciled usage, as in the core.
                val terminal = withContext(NonCancellable) {
                    invocation?.let { inv -> withTimeoutOrNull(defaults.providerTerminalWaitSeconds * 1_000L) { runCatching { inv.terminal() }.getOrNull() } }
                }
                val usage = terminal?.usage ?: received?.usage
                val cancelled = failure is CancellationException || terminal?.cancelled == true || received?.stop == StopReason.Cancelled
                val stop = when {
                    cancelled -> StopReason.Cancelled
                    received != null -> received.stop
                    else -> terminal?.response?.stop ?: when (failure) {
                        is ProviderError.OutputLimit -> StopReason.OutputLimit
                        is ProviderError.Refusal -> StopReason.Refusal
                        else -> StopReason.Truncated
                    }
                }
                val failed = failure?.takeUnless { it is CancellationException }?.let { it::class.simpleName ?: "error" }
                events.emit(AgentEvent.Cell.ModelResponded(ids, id.value, stop, usage, facts = (terminal?.response ?: received)?.facts, failure = failed))
                responses++
                // README rule 3: the call's money is chosen as `Totals` chooses it (CallPrice), the hold standing in for an unknown amount.
                budget.calls += CallPrice.account(id.value, ids, profile.id, usage, profile.priceTable, profile.priceTable.currency, hold)
                if (failure is CancellationException) throw failure

                if (failure != null) {
                    when (failure) {
                        is ProviderError.ContextOverflow -> {
                            if (!stubbed && stub()) { stubbed = true; continue }
                            return finished(CampaignOutcome.Failed, null, "context window full: the provider refused the request for size (${failure.message})")
                        }
                        is ProviderError.Authentication -> return finished(CampaignOutcome.BlockedExternal, null, "provider authentication failed for profile ${profile.id}: ${failure.message}")
                        is ProviderError.Transport, is ProviderError.RateLimit, is ProviderError.Timeout -> {
                            if (retries < RETRIES) { retries++; continue }
                            return finished(CampaignOutcome.Failed, null, "provider ${failure::class.simpleName} after ${RETRIES + 1} calls: ${failure.message}")
                        }
                        is ProviderError -> return finished(CampaignOutcome.Failed, null, "provider ${failure::class.simpleName}: ${failure.message}")
                        else -> throw failure
                    }
                }
                retries = 0
                val response = checkNotNull(received)
                if (cancelled) {
                    halted.get()?.let { return end(it) }
                    return finished(CampaignOutcome.Cancelled, null, "the provider cancelled the invocation")
                }
                transcript += response.items.filter { it !is UsageItem && it !is OpaqueContinuation }
                if (response.stop == StopReason.Refusal) return finished(CampaignOutcome.Failed, null, "the model refused: ${response.text.lineSequence().firstOrNull().orEmpty()}")
                val calls = response.toolCalls
                if (calls.isEmpty()) {
                    if (response.stop == StopReason.OutputLimit || response.stop == StopReason.Truncated) {
                        transcript += Message.text(Role.User, CUT_NOTE)
                        continue
                    }
                    // README rule 10: the final check runs the setup's commands; a red one goes back, at most twice.
                    if (setup.commands.isNotEmpty() && checks < FINAL_CHECKS) {
                        val red = try {
                            withContext(Dispatchers.IO) { tools.check(setup.commands) }
                        } catch (e: java.io.IOException) {
                            return finished(CampaignOutcome.Completed, null, "the model finished; check unavailable: ${e.message}")
                        }
                        if (red != null) {
                            checks++
                            transcript += Message.text(Role.User, red)
                            continue
                        }
                    }
                    return finished(CampaignOutcome.Completed, null, if (checks >= FINAL_CHECKS) "the model finished; the final check stayed red" else "the model finished")
                }
                for (call in calls) {
                    events.emit(AgentEvent.Cell.ToolCalled(ids, ++opId, FAMILY, call.name, LoopTools.phase(call.name)))
                    val result = withContext(Dispatchers.IO) { tools.execute(call) }
                    result.refused?.let { decisions += PolicyDecision("effect", "skipped", it) }
                    transcript += ToolResult.text(call.id, result.text, result.error)
                }
            }
        }

        /** README rule 9: the content of every tool result but the last [KEEP_RESULTS] becomes a stub; false when none changed. */
        private fun stub(): Boolean {
            val results = transcript.withIndex().filter { it.value is ToolResult }.dropLast(KEEP_RESULTS)
            var changed = false
            for ((i, item) in results) {
                val result = item as ToolResult
                if (result.content == listOf(Text(STUB))) continue
                transcript[i] = result.copy(content = listOf(Text(STUB)))
                changed = true
            }
            return changed
        }

        /** The work's spend so far — earlier sessions included — by the core's own `LimitSpend.of`. */
        private fun spend(): LimitSpend =
            LimitSpend.of(budget.calls, budget.elapsedMillis + (clock.millis() - started).coerceAtLeast(0), profile.priceTable.currency)

        private fun end(halt: Pair<Halt, String>): AttemptOutcome = when (halt.first) {
            Halt.Closed -> outcome(null, null, halt.second).copy(closedAt = responses)
            Halt.Interrupted -> outcome(CampaignOutcome.Cancelled, null, halt.second).copy(interruptedAt = script.interruptAfterResponses)
            Halt.Deadline -> outcome(CampaignOutcome.Cancelled, null, halt.second)
        }

        private fun finished(outcome: CampaignOutcome, stopCode: String?, reason: String): AttemptOutcome = outcome(outcome, stopCode, reason)

        private fun outcome(outcome: CampaignOutcome?, stopCode: String?, reason: String): AttemptOutcome = AttemptOutcome(
            workId = work.value,
            fingerprint = null,
            shape = null,
            verification = setup,
            outcome = outcome?.wire,
            stopCode = stopCode,
            reason = reason,
            failure = null,
            cells = null,
            decisions = decisions.toList(),
            messageAt = messageAt,
        )
    }

    companion object {
        /** The family every loop tool reports in `cell.tool_called`. */
        const val FAMILY: String = "loop"

        /** README rule 8: a transport error, rate limit or timeout is sent again at most this many times. */
        const val RETRIES: Int = 2

        /** README rule 10: rounds of a red final check given back to the model. */
        const val FINAL_CHECKS: Int = 2

        /** README rule 9: tool results kept whole when the window is full. */
        const val KEEP_RESULTS: Int = 4

        const val STUB: String = "[output removed: the context window was full]"
        const val CUT_NOTE: String = "Your reply was cut at the output limit. Continue, in smaller steps."
        const val RESERVE_NOTE: String = "A task limit is nearly spent: start nothing new, check your work and finish with a short summary. "

        /**
         * The conservative price of one call (README rule 3): the rule of the core's internal `Accounting.estimateCost`,
         * repeated — every input token at the dearest input rate of any tier it may reach plus the full output headroom;
         * zero for an unpriced profile; unknown when a tier lacks the price of an input dimension the profile can be
         * billed in (uncached input, its declared input usage fields, its cache-write classes) or of output.
         */
        fun conservative(profile: Profile, inputTokens: Long, outputTokens: Long): Money {
            val table = profile.priceTable
            val currency = table.currency
            if (table.charge == Charge.Unpriced) return Money.zero(currency)
            val billable = setOf(BillingDimension.UNCACHED_INPUT) + profile.capabilities.usageFields.filter { it.isInput } + profile.capabilities.caching.writeClasses
            val reachable = listOf(table.at(0)) + table.tiers.filter { it.inputTokensAbove < inputTokens }.map { table.at(it.inputTokensAbove + 1) }
            var worst = Money.zero(currency)
            for (flat in reachable) {
                if (billable.any { it !in flat.perMillion }) return Money.unknown(currency)
                val rate = flat.perMillion.filterKeys { it.isInput }.values.max()
                val output = flat.price(BillingDimension.OUTPUT, outputTokens) ?: return Money.unknown(currency)
                val cost = Money(currency, rate.multiply(BigDecimal.valueOf(inputTokens)).divide(MILLION, MathContext.DECIMAL64)) + output
                if (cost.amount > worst.amount) worst = cost
            }
            return worst
        }

        private val MILLION = BigDecimal.valueOf(1_000_000)
    }
}

/**
 * What one work has spent under the loop's task limits (README rule 3): its calls, accounted as `Totals` accounts them,
 * and the active time of its sessions. A second session of the same work continues it, as the core's limits do.
 */
internal class LoopBudget {
    val calls: MutableList<CallAccount> = ArrayList()
    var elapsedMillis: Long = 0
}

/**
 * The containment probe of the core's `run` (`DiskContainment`, internal to the core), repeated over [paths]: a
 * workspace-relative path is contained only when it was inspected, lies strictly inside the root by its real path,
 * passes through no link and holds nothing protected ([protects]) or linked below it; whatever was not inspected answers false.
 */
internal class LoopContainment(private val paths: WorkspacePath, private val protects: (String) -> Boolean, private val limit: Int = 20_000) : ContainmentProbe {
    override fun contained(relative: String): Boolean = guarded { inspect(relative, innerLinks = false) }

    override fun containedWithInnerLinks(relative: String): Boolean = guarded { inspect(relative, innerLinks = true) }

    private inline fun guarded(answer: () -> Boolean): Boolean = try {
        answer()
    } catch (e: java.io.IOException) {
        false
    } catch (e: java.nio.file.InvalidPathException) {
        false
    } catch (e: java.nio.file.DirectoryIteratorException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun inspect(relative: String, innerLinks: Boolean): Boolean {
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        var path = paths.root
        for ((index, segment) in segments.withIndex()) {
            path = path.resolve(segment)
            when (WorkspacePath.kindOf(path)) {
                PathKind.Missing -> return !protects(relative)
                PathKind.Directory -> Unit
                PathKind.Regular -> if (index < segments.lastIndex) return false
                else -> return false
            }
        }
        val real = path.toRealPath()
        if (real == paths.root || !real.startsWith(paths.root) || protects(relativeOf(real))) return false
        if (WorkspacePath.kindOf(real) != PathKind.Directory) return true
        val pending = ArrayDeque(listOf(real))
        var seen = 0
        while (pending.isNotEmpty()) {
            Files.newDirectoryStream(pending.removeLast()).use { entries ->
                for (entry in entries) {
                    if (++seen > limit) return false
                    when (WorkspacePath.kindOf(entry)) {
                        PathKind.Directory -> pending.addLast(entry)
                        PathKind.Regular -> Unit
                        PathKind.Symlink, PathKind.Junction -> if (!innerLinks || !inside(entry.toRealPath())) return false
                        else -> return false
                    }
                    if (protects(relativeOf(entry))) return false
                }
            }
        }
        return true
    }

    private fun inside(real: Path): Boolean = real != paths.root && real.startsWith(paths.root) && !protects(relativeOf(real))

    private fun relativeOf(real: Path): String = paths.root.relativize(real).joinToString("/") { it.toString() }
}

/** What a loop tool answered: [text] for the model, [error] marking a failure, [refused] the command a permission refused. */
internal data class ToolAnswer(val text: String, val error: Boolean = false, val refused: String? = null)

/** The loop's four tools on one workspace, with the core's output budgets, timeout, permissions and environment. */
internal class LoopTools(private val workspace: Path, private val defaults: Defaults, private val envAllowlist: Collection<String>, private val osName: String) {
    private val windows = osName.startsWith("Windows")
    private val scripts: Path = workspace.resolveSibling("loop-shell")
    private var scriptCount = 0

    fun execute(call: ToolCall): ToolAnswer = try {
        val args = Json.parseToJsonElement(call.argsJson).jsonObject
        when (call.name) {
            "read" -> read(args.text("path"))
            "write" -> write(args.text("path"), args.text("content"))
            "edit" -> edit(args.text("path"), args.text("old"), args.text("new"))
            "shell" -> shell(args.text("command"))
            else -> ToolAnswer("unknown tool '${call.name}'", error = true)
        }
    } catch (e: Exception) {
        ToolAnswer("${e::class.java.simpleName}: ${e.message}", error = true)
    }

    private fun JsonObject.text(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw IllegalArgumentException("'$name' must be a string")

    /** The core's path contract (D-47): real paths, links, protected paths and case, as the core's own tools resolve them. */
    private val paths: WorkspacePath = WorkspacePath.of(workspace)

    /** The default contract's scope (`Contracts`: the repository minus the protected defaults), as the core's `run` gets it. */
    private val scope: Scope = Scope.repositoryMinus(ProtectedPaths())

    /**
     * A workspace path the model named, resolved by [WorkspacePath]: never outside the workspace through `..` or a link,
     * never `.git` in any case, and for a write never through a link or into a protected path.
     */
    private fun resolve(path: String, intent: Intent): Path {
        if (intent == Intent.Read && path.trim().trimEnd('/', '\\') in setOf("", ".")) return paths.root
        return when (val resolved = paths.resolve(path, intent)) {
            is PathResolution.Resolved -> resolved.real
            is PathResolution.Rejected -> throw IllegalArgumentException("'$path' refused: ${resolved.detail}")
        }
    }

    private fun read(path: String): ToolAnswer {
        val target = resolve(path, Intent.Read)
        if (Files.isDirectory(target)) {
            val entries = Files.list(target).use { list -> list.toList() }.filterNot { it.fileName.toString().equals(".git", ignoreCase = true) }
                .map { if (Files.isDirectory(it)) "${it.fileName}/" else it.fileName.toString() }.sorted()
            return ToolAnswer(cut(entries.joinToString("\n").ifEmpty { "(empty directory)" }, defaults.lookBudgetTokens))
        }
        if (!Files.isRegularFile(target)) return ToolAnswer("no file '$path'", error = true)
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        return ToolAnswer(cut(decoder.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(target))).toString(), defaults.lookBudgetTokens))
    }

    private fun write(path: String, content: String): ToolAnswer {
        val target = resolve(path, Intent.Mutate)
        target.parent?.let(Files::createDirectories)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        Files.write(target, bytes)
        return ToolAnswer("wrote ${bytes.size} bytes to $path")
    }

    private fun edit(path: String, old: String, new: String): ToolAnswer {
        val target = resolve(path, Intent.Mutate)
        if (!Files.isRegularFile(target)) return ToolAnswer("no file '$path'", error = true)
        require(old.isNotEmpty()) { "'old' must not be empty" }
        val text = Files.readString(target, StandardCharsets.UTF_8)
        val at = text.indexOf(old)
        val count = if (at < 0) 0 else if (text.indexOf(old, at + 1) < 0) 1 else 2
        if (count != 1) return ToolAnswer("'old' occurs ${if (count == 0) "nowhere" else "more than once"} in $path; it must occur exactly once", error = true)
        Files.writeString(target, text.substring(0, at) + new + text.substring(at + old.length), StandardCharsets.UTF_8)
        return ToolAnswer("edited $path")
    }

    // README rule 2: the core's command classification; a D-class line is refused as the Studio's auto mode refuses it.
    private fun shell(command: String): ToolAnswer {
        require(command.isNotBlank()) { "'command' must not be blank" }
        // As the core's `run` (Run.kt): the default scope's protected paths and a containment probe over the real disk.
        val probe = LoopContainment(paths, protects = { relative -> scope.protects(relative, ignoreCase = true) || paths.isProtected(relative, Intent.Mutate) })
        val classification = EffectPolicy.classify(RunArgs(cmd = command), paths.root.toString(), scope.protectedPaths, EffectPolicyConfig(), probe)
        if (classification.effectClass == EffectClass.D) {
            return ToolAnswer("refused: this command needs the user's approval (${classification.reasons.joinToString("; ")})", error = true, refused = command.take(DETAIL_CHARS))
        }
        val argv = if (windows) {
            Files.createDirectories(scripts)
            val file = scripts.resolve("s${++scriptCount}.cmd")
            Files.writeString(file, "@echo off\r\n$command\r\n", StandardCharsets.UTF_8)
            listOf("cmd.exe", "/d", "/c", file.toString())
        } else {
            listOf("sh", "-c", command)
        }
        return answer(Proc.run(argv, workspace, Duration.ofSeconds(defaults.runTimeoutSeconds.toLong()), environment(), inherit = false))
    }

    private fun answer(result: ProcResult): ToolAnswer {
        val head = if (result.timedOut) "timed out after ${defaults.runTimeoutSeconds} s" else "exit ${result.exitCode}"
        return ToolAnswer(head + "\n" + cut(result.output, defaults.runBudgetTokens), error = result.timedOut || result.exitCode != 0)
    }

    /** README rule 10: the first of [commands] that fails, as a message for the model, or `null` when all pass. */
    fun check(commands: List<List<String>>): String? {
        for (argv in commands) {
            val result = Proc.run(argv, workspace, Duration.ofSeconds(defaults.runTimeoutSeconds.toLong()), environment(), inherit = false)
            if (!result.timedOut && result.exitCode == 0) continue
            return "The check `${Command(argv).text}` failed (${answer(result).text.substringBefore('\n')}). Fix what it reports, then finish:\n" +
                cut(result.output, defaults.runBudgetTokens)
        }
        return null
    }

    // The core's process environment: the platform essentials (`WindowsOwner`, `PosixOwner`) plus the allowlist.
    private fun environment(): Map<String, String> =
        ((if (windows) WINDOWS_ESSENTIALS else POSIX_ESSENTIALS) + envAllowlist).mapNotNull { name -> System.getenv(name)?.let { name to it } }.toMap()

    companion object {
        const val SYSTEM: String = "You are a coding agent working in a repository on this machine. Work with the tools: " +
            "read {path} shows a file or lists a directory; write {path, content} creates or replaces a file; " +
            "edit {path, old, new} replaces the one occurrence of old in a file; shell {command} runs one command line in the " +
            "repository root. Paths are relative to the repository root. Inspect before you change, keep changes small, run the " +
            "project's tests, and when the work is done and checked, reply with a short summary of what you changed and how " +
            "you checked it, without a tool call: that ends the task."

        /** `Guidance.platform` and the core's shaper: 3.6 characters per token. */
        private const val CHARS_PER_TOKEN: Double = 3.6
        private const val DETAIL_CHARS = 2_000
        private val WINDOWS_ESSENTIALS = listOf("SystemRoot", "SystemDrive", "windir", "PATH", "PATHEXT", "COMSPEC", "TEMP", "TMP", "USERPROFILE")
        private val POSIX_ESSENTIALS = listOf("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR")

        /** README rule 7: [text] cut to [budgetTokens], head and tail kept. */
        fun cut(text: String, budgetTokens: Int): String {
            val max = (budgetTokens * CHARS_PER_TOKEN).toInt()
            if (text.length <= max) return text
            val half = max / 2
            return text.substring(0, half) + "\n[… ${text.length - 2 * half} characters cut …]\n" + text.substring(text.length - half)
        }

        fun phase(tool: String): Phase = when (tool) {
            "read" -> Phase.Locate
            "write", "edit" -> Phase.Edit
            else -> Phase.Verify
        }

        /**
         * README rule 1: the verification setup the default arm's contract would accept against — the root package's
         * declared test command, else the Studio's review setup with its declared hints.
         */
        fun verification(workspace: Path): VerificationSetup {
            val paths = Files.walk(workspace).use { all -> all.filter(Files::isRegularFile).toList() }
                .map { workspace.relativize(it).joinToString("/") }.filterNot { it == ".git" || it.startsWith(".git/") }.toSet()
            val sniffed = Sniff.commands(workspace, paths)
            val root = sniffed.packages.firstOrNull { it.dir == PackageCommands.ROOT } ?: sniffed.packages.firstOrNull()
            val test = root?.test ?: return StudioPolicy.choose(sniffed)
            return VerificationSetup("tests", "declared", listOf(test))
        }

        fun schemas(dialect: SchemaDialect): List<ToolSchema> {
            fun schema(vararg properties: Pair<String, String>): JsonObject = Json.parseToJsonElement(
                """{"type":"object","properties":{${properties.joinToString(",") { (name, description) -> "\"$name\":{\"type\":\"string\",\"description\":${JsonPrimitive(description)}}" }}},""" +
                    """"required":[${properties.joinToString(",") { "\"${it.first}\"" }}],"additionalProperties":false}""",
            ).jsonObject
            return listOf(
                ToolSchema("read", "Show a file of the repository, or list a directory.", schema("path" to "path relative to the repository root"), dialect),
                ToolSchema("write", "Create or replace a file with the given content.", schema("path" to "path relative to the repository root", "content" to "the whole new content"), dialect),
                ToolSchema("edit", "Replace the one occurrence of old in a file with new.", schema("path" to "path relative to the repository root", "old" to "text that occurs exactly once", "new" to "its replacement"), dialect),
                ToolSchema("shell", "Run one command line in the repository root and show its exit code and output.", schema("command" to "the command line"), dialect),
            )
        }
    }
}
