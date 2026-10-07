package io.astrolabe.contract

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.PackageCommands
import io.astrolabe.atlas.Sniff
import io.astrolabe.atlas.Sniffed
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.budget.Budget
import io.astrolabe.budget.Tokens
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Authority
import io.astrolabe.event.Events
import io.astrolabe.event.Proposer
import io.astrolabe.event.ResolutionOutcome
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Money
import io.astrolabe.store.Tx
import io.astrolabe.workspace.ProtectedPaths
import java.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Persistence seam for versioned contract rows; the SQLite implementation lives in the store package. */
public interface ContractRepository {
    /** All versions of a work's contract in ascending version order; failed attempts are preserved. */
    public fun history(work: WorkId): List<Contract>

    /** Latest authority only; persistent implementations avoid decoding the historical bodies. */
    public fun latest(work: WorkId): Contract? = history(work).lastOrNull()

    /** Appends a new version; rejects a version that is not `latest + 1` (or 1 for a new work). */
    public fun append(contract: Contract)

    /** Replaces the latest row at the same version (pending proposals, strengthening). */
    public fun replaceLatest(contract: Contract)

    /** Commits the contract and final amendment provenance in one transaction. */
    public fun commitResolution(contract: Contract, amendment: Amendment)

    /** Durable resolutions, optionally scoped to one work. */
    public fun resolved(work: WorkId? = null): List<Amendment>
}

/** In-memory repository for tests and dry runs. */
public class InMemoryContractRepository : ContractRepository {
    private val rows = LinkedHashMap<WorkId, MutableList<Contract>>()
    private val resolutions = LinkedHashMap<Pair<WorkId, String>, Amendment>()

    @Synchronized
    override fun history(work: WorkId): List<Contract> = rows[work]?.toList() ?: emptyList()

    @Synchronized
    override fun latest(work: WorkId): Contract? = rows[work]?.lastOrNull()

    @Synchronized
    override fun append(contract: Contract) {
        val list = rows.getOrPut(contract.workId) { ArrayList() }
        val expected = (list.lastOrNull()?.version ?: 0) + 1
        require(contract.version == expected) { "contract version ${contract.version} must be $expected" }
        list += contract
    }

    @Synchronized
    override fun replaceLatest(contract: Contract) {
        val list = rows[contract.workId] ?: throw IllegalStateException("no contract for ${contract.workId}")
        require(list.last().version == contract.version) { "replaceLatest must keep version ${list.last().version}" }
        list[list.lastIndex] = contract
    }

    @Synchronized
    override fun commitResolution(contract: Contract, amendment: Amendment) {
        require(amendment.status != AmendmentStatus.Pending) { "resolution must be final" }
        if (amendment.status == AmendmentStatus.Accepted) append(contract) else replaceLatest(contract)
        resolutions[contract.workId to amendment.id] = amendment
    }

    @Synchronized
    override fun resolved(work: WorkId?): List<Amendment> =
        resolutions.filterKeys { work == null || it.first == work }.values.toList()
}

/**
 * The contract store (§4.1, TODO P1.1.1/P1.1.3): the controller owns every write. Versions bump only on an
 * authorized amendment; model proposals stay pending until the authority resolves them, and a weakening is
 * never auto-accepted. Factual answers are evidence and never bump the version.
 */
public class Contracts(
    private val repository: ContractRepository,
    private val idGen: IdGen,
    private val clock: Clock,
    private val events: Events? = null,
) {
    public fun current(work: WorkId): Contract? = repository.latest(work)

    public fun history(work: WorkId): List<Contract> = repository.history(work)

    /** Stores version 1 of a new contract. */
    public fun open(contract: Contract): Contract = synchronized(repository) {
        require(contract.version == 1) { "a new contract starts at version 1" }
        repository.append(contract)
        return contract
    }

    /**
     * C14: the host raises [work]'s contract tokens to [tokens] at its current version — a budget is not an amendment, so
     * nothing bound to the version is invalidated. Under this monitor, the latest version is re-read and replaced in one
     * transaction with what [record] writes (the journal line); at or below the stored tokens nothing changes (`null`).
     */
    internal fun raiseTokens(work: WorkId, tokens: Tokens, record: (Tx, Contract, Contract) -> Unit): Contract? = synchronized(repository) {
        val rows = repository as? SqliteContractRepository
            ?: throw IllegalStateException("a contract budget raise commits with its journal line: it needs the store's repository")
        rows.replaceLatest(work, { c -> c.takeIf { tokens.value > it.budget.tokens.value }?.let { it.copy(budget = it.budget.copy(tokens = tokens)) } }, record)
    }

    /** Model-side strengthening: adds an item at the same version (the model may only ADD). */
    public fun strengthen(work: WorkId, item: Acceptance): Contract = synchronized(repository) {
        val next = requireCurrent(work).strengthen(item)
        repository.replaceLatest(next)
        return next
    }

    /** Model proposal (`amend.propose`): recorded as pending; grants nothing until resolved. */
    public fun propose(work: WorkId, cell: ContextId?, change: String, reason: String, weakening: Boolean): Amendment = synchronized(repository) {
        val current = requireCurrent(work)
        val amendment = Amendment(idGen.next("AM"), Proposer.Model, cell, change, reason, weakening)
        repository.replaceLatest(current.copy(amendmentsPending = current.amendmentsPending + amendment))
        events?.emit(AgentEvent.Contract.AmendmentProposed(ids(current), amendment.id, weakening))
        return amendment
    }

    /**
     * An explicit user amendment (§4.1, task-workflow §2.1): the request is appended verbatim as an `amendment` and the
     * version bumps, because the authority is the message itself. [apply] derives the amended content from the appended
     * contract. A message of any other kind goes through [message]; nothing turns a confirmation into this (§2.3).
     */
    public fun amendByUser(work: WorkId, text: String, apply: (Contract) -> Contract = { it }): Contract = synchronized(repository) {
        val current = requireCurrent(work)
        val request = UserRequest(idGen.next("U"), clock.instant(), text, MessageKind.Amendment)
        val amended = apply(current.copy(requests = current.requests + request)).copy(version = current.version + 1)
        repository.append(amended)
        events?.emit(AgentEvent.Contract.MessageRecorded(ids(amended), request.id, MessageKind.Amendment.wire))
        events?.emit(AgentEvent.Contract.Amended(ids(amended), amended.version, "user", requestId = request.id))
        return amended
    }

    /**
     * Records a message sent to [work] (task-workflow §1.1, §2.1) exactly once — a second delivery with the same [hostRef]
     * returns the contract unchanged. An [MessageKind.Amendment] (and an answer that [changesRequirements], D-317) bumps
     * the version and derives its requirement verbatim (§2.4 B) — or, with [changes], cancels or replaces the requirements
     * they name (§2.4 C); every other kind is appended at the same version, so nothing bound to the revision moves (§2.3).
     * The controller derives the work each message gets at the next run (response increment, `inc-n`).
     */
    @JvmOverloads
    public fun message(
        work: WorkId,
        kind: MessageKind,
        text: String,
        hostRef: String? = null,
        answers: String? = null,
        changesRequirements: Boolean = false,
        changes: List<Narrowing> = emptyList(),
    ): Contract = synchronized(repository) {
        require(kind != MessageKind.Request) { "a work's request is recorded at its first open; a later message has another kind" }
        require(kind != MessageKind.Answer || !answers.isNullOrBlank()) { "an answer names the question it answers" }
        require(changes.isEmpty() || kind == MessageKind.Amendment) { "only the explicit \"change the task\" cancels or replaces a requirement" }
        val current = requireCurrent(work)
        if (hostRef != null) current.requests.firstOrNull { it.hostRef == hostRef }?.let { return current }
        val amends = kind == MessageKind.Amendment || kind == MessageKind.Answer && changesRequirements
        val recorded = if (amends && kind == MessageKind.Answer) MessageKind.Amendment else kind
        val request = UserRequest(idGen.next("U"), clock.instant(), text, recorded, answers, hostRef)
        val appended = current.copy(requests = current.requests + request)
        if (!amends) {
            repository.replaceLatest(appended)
            events?.emit(AgentEvent.Contract.MessageRecorded(ids(appended), request.id, recorded.wire))
            return appended
        }
        val amended = derive(appended, request, changes).copy(version = current.version + 1)
        repository.append(amended)
        events?.emit(AgentEvent.Contract.MessageRecorded(ids(amended), request.id, recorded.wire))
        events?.emit(AgentEvent.Contract.Amended(ids(amended), amended.version, "user", requestId = request.id))
        return amended
    }

    /**
     * §2.4 B and C: the requirements an amendment derives — its text verbatim as `R-n` when it carries no structured
     * change, else each [Narrowing.Replace] as `R-m` with the replaced one `superseded_by` it and each [Narrowing.Cancel]
     * `cancelled(reason)`. Texts never change; only the status fields move. A new requirement is accepted by the
     * contract's regression `run:` items (the goal acceptance the model states joins it, W8).
     */
    private fun derive(contract: Contract, request: UserRequest, changes: List<Narrowing>): Contract {
        val regression = regressionItems(contract)
        var next = (contract.requirements.mapNotNull { it.id.removePrefix("R").toIntOrNull() }.maxOrNull() ?: 0) + 1
        fun requirement(text: String) = Requirement("R${next++}", text, regression, authorityRef = request.id)
        if (changes.isEmpty()) return contract.copy(requirements = contract.requirements + requirement(request.text))
        var requirements = contract.requirements
        for (change in changes) {
            val target = requirements.firstOrNull { it.id == change.requirementId && !it.lapsed }
                ?: throw IllegalArgumentException("no open requirement ${change.requirementId} to ${change::class.simpleName?.lowercase()}")
            requirements = when (change) {
                is Narrowing.Cancel -> requirements.map { if (it.id == target.id) it.copy(cancelledReason = change.reason) else it }
                is Narrowing.Replace -> {
                    val replacement = requirement(change.text)
                    requirements.map { if (it.id == target.id) it.copy(supersededBy = replacement.id) else it } + replacement
                }
            }
        }
        return contract.copy(requirements = requirements)
    }

    /**
     * Records a declared task output (task-workflow §5.1, D-435) as a host-origin revision: `version` + 1, so the
     * `DecisionKey` moves with the candidate identity it will change; append-only. Validation and its effect from the
     * next attempt are W8's.
     */
    @JvmOverloads
    public fun declareOutput(work: WorkId, path: String, by: OutputDeclarer, reason: String, cell: ContextId? = null): Contract {
        val current = requireCurrent(work)
        require(current.outputs.none { it.path == path }) { "$path is declared already" }
        return amendByHost(work, "declared output $path") { it.copy(outputs = it.outputs + DeclaredOutput(path, by, reason, current.version + 1, cell)) }
    }

    /**
     * A structural change the host makes to the contract (D-345) — an acceptance item, protected paths — with the host
     * as its author: the version bumps, but no request is appended, so the objective and the pinned requests stay the
     * user's own words. [reason] is what the host did, for the event.
     */
    public fun amendByHost(work: WorkId, reason: String, apply: (Contract) -> Contract): Contract = synchronized(repository) {
        require(reason.isNotBlank()) { "a host amendment says what it changes" }
        val current = requireCurrent(work)
        val changed = apply(current)
        require(changed.requests == current.requests) { "a host amendment never writes the user's requests" }
        val amended = changed.copy(version = current.version + 1)
        repository.append(amended)
        events?.emit(AgentEvent.Contract.Amended(ids(amended), amended.version, "host: $reason"))
        return amended
    }

    /**
     * Resolves a pending proposal through [authority]: accepted ⇒ [apply] derives the new content and the
     * version bumps; rejected ⇒ recorded and dropped from the pending list; pending ⇒ unchanged.
     */
    public suspend fun resolve(work: WorkId, amendmentId: String, authority: Authority, apply: (Contract) -> Contract): Contract {
        val current = requireCurrent(work)
        val amendment = current.amendmentsPending.firstOrNull { it.id == amendmentId }
            ?: throw IllegalArgumentException("no pending amendment $amendmentId")
        val proposal = AmendmentProposal(amendment.id, current.version, ids(current), amendment.by, amendment.change, amendment.reason, amendment.weakening)
        val resolution = authority.resolve(proposal)
        currentCoroutineContext().ensureActive()
        val next = synchronized(repository) {
            val latest = requireCurrent(work)
            if (resolution.proposalId != proposal.id || resolution.contractRevision != proposal.contractRevision ||
                latest.version != proposal.contractRevision || amendment !in latest.amendmentsPending
            ) return latest
            val remaining = latest.amendmentsPending.filter { it.id != amendmentId }
            when (resolution.outcome) {
                ResolutionOutcome.Accepted -> {
                    apply(latest).copy(version = latest.version + 1, amendmentsPending = remaining).also {
                        repository.commitResolution(it, amendment.copy(status = AmendmentStatus.Accepted, resolvedBy = resolution.byAuthority))
                    }
                }
                ResolutionOutcome.Rejected -> {
                    latest.copy(amendmentsPending = remaining).also {
                        repository.commitResolution(it, amendment.copy(status = AmendmentStatus.Rejected, resolvedBy = resolution.byAuthority))
                    }
                }
                ResolutionOutcome.Pending -> latest
            }
        }
        events?.emit(AgentEvent.Contract.AmendmentResolved(ids(next), amendment.id, resolution.outcome.name))
        if (resolution.outcome == ResolutionOutcome.Accepted) events?.emit(AgentEvent.Contract.Amended(ids(next), next.version, resolution.byAuthority))
        return next
    }

    /** Resolved amendments, kept for the finish receipt. */
    public fun resolved(): List<Amendment> = repository.resolved()

    public fun resolved(work: WorkId): List<Amendment> = repository.resolved(work)

    /**
     * §4.1 auto-derivation for S0 (TODO P1.1.2): the request becomes `R1` verbatim, every package whose manifest
     * declares a test command becomes `AC-n: run <suite> (origin harness, scope touched)`, the write scope is the
     * D-31 default and nothing is guessed — a repository without a declared suite still yields a contract, with
     * no acceptance, and [Contract.goalAcceptanceStated] stays false until the model states one or the user
     * amends. The result is not stored; the campaign opens it ([open]) once the workspace is captured.
     */
    public fun deriveS0(
        work: WorkId,
        attempt: AttemptId,
        text: String,
        atlas: Atlas,
        config: Config,
        tokens: Tokens,
        protected: ProtectedPaths = ProtectedPaths(),
        cost: Money? = null,
        /** The attempt's frozen output policy (W3), recorded in the contract; `null` records none. */
        scratch: io.astrolabe.verify.ScratchPolicy? = null,
    ): S0Derivation {
        val request = UserRequest(idGen.next("U"), clock.instant(), text)
        val sniffed = Sniff.commands(atlas)
        val suites = sniffed.packages.filter { it.test != null }
        val acceptance = suites.mapIndexed { index, pkg ->
            Acceptance.Run(
                id = "AC-${index + 1}",
                command = Command(pkg.test!!, cwd = pkg.dir.takeIf { it != PackageCommands.ROOT }),
                origin = Origin.Harness,
                scope = TOUCHED,
            )
        }
        val contract = Contract(
            workId = work,
            version = 1,
            attemptId = attempt,
            mode = config.mode,
            shape = Shape.S0,
            requests = listOf(request),
            requirements = listOf(Requirement("R1", text, acceptance.map { it.id }, authorityRef = request.id)),
            acceptance = acceptance,
            constraints = emptyList(),
            exclusions = emptyList(),
            contractsTouched = emptyList(),
            scope = Scope.repositoryMinus(protected),
            budget = Budget.of(config.defaults, tokens, cost),
            authorization = Authorization(config.ceiling, config.dClass, config.capabilitySet),
            // Risk is not assessed here: incomplete discovery is unknown, never low (D-16); the pre-scan is P3.2.6.
            risk = null,
            scratch = scratch?.takeIf { it.id != null },
        )
        return S0Derivation(contract, sniffed, primary = suites.firstOrNull { it.dir == PackageCommands.ROOT } ?: suites.firstOrNull())
    }

    private fun requireCurrent(work: WorkId): Contract = current(work) ?: throw IllegalStateException("no contract for $work")

    private fun ids(contract: Contract) = Identities(contract.workId, contract.attemptId)

    public companion object {
        /** The scope word of an auto-derived suite: the runner narrows it to touched files (§8.1). */
        public const val TOUCHED: String = "touched"

        /**
         * The contract's regression `run:` items (task-workflow §2.4 A, B): those at `scope: touched`, else every `run:`
         * item. W8 reads the explicit evidence purpose instead.
         */
        internal fun regressionItems(contract: Contract): List<String> {
            val runs = contract.acceptance.filterIsInstance<Acceptance.Run>()
            return runs.filter { it.scope == TOUCHED }.ifEmpty { runs }.map { it.id }
        }
    }
}

/** Structured content of the explicit "change the task" (task-workflow §2.4 C): it narrows, never steering. */
public sealed interface Narrowing {
    public val requirementId: String

    /** [requirementId] is `cancelled(reason)`; its text and receipts stay as history. */
    public data class Cancel(override val requirementId: String, val reason: String) : Narrowing {
        init {
            require(requirementId.isNotBlank() && reason.isNotBlank()) { "a cancellation names the requirement and why" }
        }
    }

    /** [requirementId] is `superseded_by` a new requirement whose text is [text] verbatim. */
    public data class Replace(override val requirementId: String, val text: String) : Narrowing {
        init {
            require(requirementId.isNotBlank() && text.isNotBlank()) { "a replacement names the requirement and its new text" }
        }
    }
}

/**
 * What [Contracts.deriveS0] produced. [primary] is the package whose commands seed the check registry
 * (`RunnerCommands.of`): the root package when it declares a suite, else the first package that does; S0 is a
 * one-package shape (D-16), so a monorepo's other suites are acceptance items with their own `cwd` and nothing
 * more until the impact pre-scan (P3.2.6) can narrow them.
 */
public data class S0Derivation(
    val contract: Contract,
    val sniffed: Sniffed,
    val primary: PackageCommands?,
)
