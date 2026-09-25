package io.astrolabe.delegate

import io.astrolabe.auth.ExecutionMode
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Identities
import io.astrolabe.tool.run.ConfinedRunner
import io.astrolabe.tool.run.Runner

/** How the QA cell drives the product (§10.3). */
public enum class QaSurface(public val wire: String) { Cli("cli"), Http("http"), Browser("browser") }

/** One entry point the QA cell exercises: a command, an endpoint or a page of the product under test. */
public data class EntryPoint(val surface: QaSurface, val target: String) {
    init {
        require(target.isNotBlank()) { "an entry point names its target" }
    }
}

/**
 * The disposable environment an L3 run needs (§10.3, §8.2 L3): never production, never the live workspace. Either a
 * confined backend, or an isolated candidate copy whose run is explicitly labelled `trusted-local` (D-125) — the
 * label is how a report says the copy is not confined.
 */
public sealed interface QaEnvironment {
    public val label: String

    public data class Confined(val backend: String) : QaEnvironment {
        init {
            require(backend.isNotBlank()) { "a confined environment names its backend" }
        }

        override val label: String get() = "confined:$backend"
    }

    public data class IsolatedCandidate(val candidate: CandidateId, val root: String, val mode: ExecutionMode) : QaEnvironment {
        init {
            require(root.isNotBlank()) { "an isolated candidate has a root" }
        }

        override val label: String get() = (if (mode == ExecutionMode.TrustedLocal) "trusted-local" else "confined") + ":candidate@${candidate.hash8}"
    }

    public companion object {
        /** A confined runner is disposable by construction; a trusted-local one only on an isolated candidate copy. */
        @JvmStatic
        public fun of(runner: Runner, candidate: CandidateId?, root: String?): QaEnvironment? = when {
            runner is ConfinedRunner -> Confined(runner.backend)
            candidate != null && !root.isNullOrBlank() -> IsolatedCandidate(candidate, root, runner.mode)
            else -> null
        }
    }
}

/** The QA packet in (§10.3): the contract slice, the behaviour under test and its entry points, and where to run. */
public data class QaPacket(
    val ids: Identities,
    val incrementId: String,
    val contractVersion: Int,
    val candidate: CandidateId,
    val criteria: List<ReviewCriterion>,
    val behaviour: String,
    val entryPoints: List<EntryPoint>,
    val environment: QaEnvironment,
) {
    init {
        require(incrementId.isNotBlank() && behaviour.isNotBlank()) { "a QA packet names its increment and the behaviour under test" }
        require(contractVersion >= 1) { "contract version starts at 1" }
        require(entryPoints.isNotEmpty()) { "a QA packet names at least one entry point" }
        require(criteria.isNotEmpty()) { "a QA packet carries the acceptance it exercises" }
    }
}

/** One case the QA cell ran: steps against an entry point, expected and observed, with its screenshots/logs as blobs. */
public data class QaCase(
    val id: String,
    val entryPoint: EntryPoint,
    val steps: List<String>,
    val expected: String,
    val observed: String,
    /** `null` when the case could not be decided (an environment failure, not a product verdict). */
    val passed: Boolean?,
    val artifacts: List<String>,
) {
    init {
        require(id.isNotBlank() && expected.isNotBlank()) { "a case has an id and an expectation" }
    }
}

/** The QA packet out (§10.3): receipts, cases and artifacts, bound to the environment and candidate it ran on. */
public data class QaResult(
    val ids: Identities,
    val incrementId: String,
    val contractVersion: Int,
    val candidate: CandidateId,
    val environment: QaEnvironment,
    val receipts: List<String>,
    val cases: List<QaCase>,
    val unresolved: List<String>,
)

/** Whether a QA run may start. */
public sealed interface QaAdmission {
    public data class Ready(val packet: QaPacket) : QaAdmission

    public data class Refused(val reason: String) : QaAdmission
}

/**
 * The QA cell's contract (§10.3, L3 of §8.2): packet in, packet out, never deciding interfaces. The implementation is
 * `[O]` in P5.3; until then QA is masked — not a [ChildKind], and [admit] refuses every packet (D-125) — while the
 * packet shapes and [validate], the declared result validator, are fixed here.
 */
public object QaCell {
    /** Masked until P5.3 implements the cell. */
    public const val AVAILABLE: Boolean = false

    @JvmStatic
    @JvmOverloads
    public fun admit(packet: QaPacket, available: Boolean = AVAILABLE): QaAdmission {
        if (!available) return QaAdmission.Refused("QA cells are masked until P5.3: L3 product use needs its implementation")
        val env = packet.environment
        if (env is QaEnvironment.IsolatedCandidate && env.candidate != packet.candidate) {
            return QaAdmission.Refused("the isolated copy is @${env.candidate.hash8}, not the candidate under test @${packet.candidate.hash8}")
        }
        return QaAdmission.Ready(packet)
    }

    /**
     * Gaps of [result] against [packet] (§10.3): same candidate, contract version and environment; every case on one
     * of the packet's entry points; every decided case carries at least one artifact that exists; a run with cases
     * has receipts. Empty ⇔ the packet may be published.
     */
    @JvmStatic
    public fun validate(result: QaResult, packet: QaPacket, artifactExists: (String) -> Boolean): List<String> {
        val gaps = ArrayList<String>()
        if (result.candidate != packet.candidate) gaps += "ran @${result.candidate.hash8}, the packet names @${packet.candidate.hash8}"
        if (result.contractVersion != packet.contractVersion) gaps += "bound to contract v${result.contractVersion}, the packet to v${packet.contractVersion}"
        if (result.environment != packet.environment) gaps += "ran in ${result.environment.label}, the packet requires ${packet.environment.label}"
        if (result.cases.isEmpty() && result.unresolved.isEmpty()) gaps += "no cases and nothing unresolved"
        if (result.cases.isNotEmpty() && result.receipts.isEmpty()) gaps += "cases ran without receipts"
        if (result.cases.map { it.id }.toSet().size != result.cases.size) gaps += "case ids are unique"
        for (case in result.cases) {
            if (case.entryPoint !in packet.entryPoints) gaps += "case ${case.id}: ${case.entryPoint.surface.wire} ${case.entryPoint.target} is not an entry point of the packet"
            if (case.passed != null && case.artifacts.isEmpty()) gaps += "case ${case.id}: a decided case carries its screenshot or log"
            case.artifacts.filterNot(artifactExists).forEach { gaps += "case ${case.id}: artifact $it is not a published blob" }
        }
        return gaps
    }
}
