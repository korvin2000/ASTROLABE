package io.astrolabe.delegate

import io.astrolabe.cell.PacketBase
import io.astrolabe.cell.PacketCost
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities

/** How a probe holds a claim (§10.2): seen in evidence, or inferred from it. */
public enum class ClaimKind(public val wire: String) { Observed("observed"), Inferred("inferred") }

/** Where a finding points (§10.2 `evidence: path:range@hash | #id`); a pointer the parent must `look` at, never a body. */
public sealed interface EvidenceRef {
    public val wire: String

    /** A line range of a path at the version the probe was shown. */
    public data class Range(val path: String, val lines: String, val version: FileVersion) : EvidenceRef {
        init {
            require(path.isNotBlank() && lines.isNotBlank()) { "a range names a path and lines" }
        }

        override val wire: String get() = "$path:$lines@${version.hash8}"
    }

    /** A durable result alias (`#id`). */
    public data class Alias(val id: String) : EvidenceRef {
        init {
            require(id.startsWith("#") && id.length > 1) { "an alias is `#id`" }
        }

        override val wire: String get() = id
    }
}

/** One finding (§10.2): an observed claim points at evidence; an inferred one may stand on none. */
public data class Finding(val claim: String, val kind: ClaimKind, val evidence: List<EvidenceRef>) {
    init {
        require(claim.isNotBlank()) { "a finding claims something" }
        require(kind != ClaimKind.Observed || evidence.isNotEmpty()) { "an observed finding points at evidence" }
    }
}

/** What the probe covered (§10.2 `searched`): the scopes, whether it finished them, and the index coverage it relied on. */
public data class Searched(val scopes: List<String>, val complete: Boolean, val indexCoverage: Double?) {
    init {
        require(indexCoverage == null || indexCoverage in 0.0..1.0) { "index coverage is a fraction" }
    }
}

/**
 * The Investigation Packet a probe ends with (§10.2; shape here, the cell is P4.4.2). Findings carry versions
 * (FX-41 pre-condition): every range a finding cites names a version, and that version is one of [readVersions],
 * the paths the probe was actually shown, so the parent can tell a stale finding from a current one. [dependencies]
 * are those observed versions: a probe's assumptions about a file are dependencies of whoever consumes them (§10.1).
 */
public data class InvestigationPacket(
    val ids: Identities,
    val incrementId: String,
    val contractVersion: Int,
    val executionGeneration: ExecutionGeneration,
    val base: PacketBase,
    val readVersions: Map<String, FileVersion>,
    val findings: List<Finding>,
    val searched: Searched,
    val unresolved: List<String>,
    val cost: PacketCost,
) {
    init {
        require(ids.context != null) { "a packet belongs to a cell" }
        require(incrementId.isNotBlank()) { "a packet names its increment" }
        require(contractVersion >= 1) { "contract version starts at 1" }
        findings.forEach { f ->
            f.evidence.filterIsInstance<EvidenceRef.Range>().forEach { r ->
                require(readVersions[r.path] == r.version) { "finding '${f.claim}' cites ${r.wire}, which the probe was not shown at that version" }
            }
        }
    }

    val role: String get() = ChildKind.Probe.role

    val dependencies: Map<String, FileVersion> get() = readVersions
}
