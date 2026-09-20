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
import io.astrolabe.workspace.ProtectedPaths
import java.time.Clock

/** Persistence seam for versioned contract rows; the SQLite implementation lives in the store package. */
public interface ContractRepository {
    /** All versions of a work's contract in ascending version order; failed attempts are preserved. */
    public fun history(work: WorkId): List<Contract>

    /** Appends a new version; rejects a version that is not `latest + 1` (or 1 for a new work). */
    public fun append(contract: Contract)

    /** Replaces the latest row at the same version (pending proposals, strengthening). */
    public fun replaceLatest(contract: Contract)
}

/** In-memory repository for tests and dry runs. */
public class InMemoryContractRepository : ContractRepository {
    private val rows = LinkedHashMap<WorkId, MutableList<Contract>>()

    @Synchronized
    override fun history(work: WorkId): List<Contract> = rows[work]?.toList() ?: emptyList()

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
    private val resolvedHistory = LinkedHashMap<String, Amendment>()

    public fun current(work: WorkId): Contract? = repository.history(work).lastOrNull()

    public fun history(work: WorkId): List<Contract> = repository.history(work)

    /** Stores version 1 of a new contract. */
    public fun open(contract: Contract): Contract {
        require(contract.version == 1) { "a new contract starts at version 1" }
        repository.append(contract)
        return contract
    }

    /** Model-side strengthening: adds an item at the same version (the model may only ADD). */
    public fun strengthen(work: WorkId, item: Acceptance): Contract {
        val next = requireCurrent(work).strengthen(item)
        repository.replaceLatest(next)
        return next
    }

    /** Model proposal (`amend.propose`): recorded as pending; grants nothing until resolved. */
    public fun propose(work: WorkId, cell: ContextId?, change: String, reason: String, weakening: Boolean): Amendment {
        val current = requireCurrent(work)
        val amendment = Amendment(idGen.next("AM"), Proposer.Model, cell, change, reason, weakening)
        repository.replaceLatest(current.copy(amendmentsPending = current.amendmentsPending + amendment))
        events?.emit(AgentEvent.Contract.AmendmentProposed(ids(current), amendment.id, weakening))
        return amendment
    }

    /**
     * A user message amends anything: the request is appended verbatim and the version bumps, because the
     * authority is the message itself (§4.1). [apply] derives the amended content from the appended contract.
     */
    public fun amendByUser(work: WorkId, text: String, apply: (Contract) -> Contract = { it }): Contract {
        val current = requireCurrent(work)
        val request = UserRequest(idGen.next("U"), clock.instant(), text)
        val amended = apply(current.copy(requests = current.requests + request)).copy(version = current.version + 1)
        repository.append(amended)
        events?.emit(AgentEvent.Contract.Amended(ids(amended), amended.version, "user"))
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
        val remaining = current.amendmentsPending.filter { it.id != amendmentId }
        val next = when (resolution.outcome) {
            ResolutionOutcome.Accepted -> {
                resolvedHistory[amendment.id] = amendment.copy(status = AmendmentStatus.Accepted, resolvedBy = resolution.byAuthority)
                apply(current).copy(version = current.version + 1, amendmentsPending = remaining).also(repository::append)
            }
            ResolutionOutcome.Rejected -> {
                resolvedHistory[amendment.id] = amendment.copy(status = AmendmentStatus.Rejected, resolvedBy = resolution.byAuthority)
                current.copy(amendmentsPending = remaining).also(repository::replaceLatest)
            }
            ResolutionOutcome.Pending -> current
        }
        events?.emit(AgentEvent.Contract.AmendmentResolved(ids(next), amendment.id, resolution.outcome.name))
        if (resolution.outcome == ResolutionOutcome.Accepted) events?.emit(AgentEvent.Contract.Amended(ids(next), next.version, resolution.byAuthority))
        return next
    }

    /** Resolved amendments, kept for the finish receipt. */
    public fun resolved(): List<Amendment> = resolvedHistory.values.toList()

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
            authorization = Authorization(config.ceiling, config.dClass, CapabilitySet.WORKSPACE_LOCAL_TEST_ONLY.name),
            // Risk is not assessed here: incomplete discovery is unknown, never low (D-16); the pre-scan is P3.2.6.
            risk = null,
        )
        return S0Derivation(contract, sniffed, primary = suites.firstOrNull { it.dir == PackageCommands.ROOT } ?: suites.firstOrNull())
    }

    private fun requireCurrent(work: WorkId): Contract = current(work) ?: throw IllegalStateException("no contract for $work")

    private fun ids(contract: Contract) = Identities(contract.workId, contract.attemptId)

    public companion object {
        /** The scope word of an auto-derived suite: the runner narrows it to touched files (§8.1). */
        public const val TOUCHED: String = "touched"
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
