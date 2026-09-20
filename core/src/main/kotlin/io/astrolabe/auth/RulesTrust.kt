package io.astrolabe.auth

import io.astrolabe.RulesBinding
import io.astrolabe.id.Digest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * A repository file that *looks* like a rules file (§14.3, D-32). Discovery proposes; it never promotes.
 * Until a binding names this exact path and digest, the bytes are repository data like any other file.
 */
@Serializable
public data class RulesCandidate(
    /** Canonical workspace-relative path, forward slashes. */
    val path: String,
    val digest: Digest,
    val sizeBytes: Long,
) {
    init {
        require(path.isNotBlank()) { "a rules candidate needs a canonical path" }
        require(sizeBytes >= 0) { "sizeBytes must be ≥ 0" }
    }
}

/** Who bound a rules file (D-32): configuration of the host, or an explicit act of user authority. */
@Serializable
public sealed interface Provenance {
    /** Stored in [RulesBinding.provenance]. */
    public val label: String

    @Serializable
    @SerialName("host-config")
    public object HostConfig : Provenance {
        override val label: String get() = "host-config"

        override fun toString(): String = label
    }

    @Serializable
    @SerialName("user-authority")
    public data class UserAuthority(val who: String) : Provenance {
        init {
            require(who.isNotBlank()) { "user authority must name who approved the rules file" }
        }

        override val label: String get() = "user:$who"

        override fun toString(): String = label
    }
}

/** An approved rules snapshot: the only repository text that is instruction, not data (§14.3). */
@Serializable
public data class RulesSnapshot(val binding: RulesBinding, val text: String, val digest: Digest) {
    init {
        require(digest == binding.digest) { "a snapshot only exists for the approved digest" }
    }
}

/** Trust state of the configured rules path. Text is carried by [Approved] alone. */
@Serializable
public sealed interface RulesStatus {
    /** No binding names this file: it stays repository data (the default for every discovered candidate). */
    @Serializable
    @SerialName("untrusted")
    public object Untrusted : RulesStatus {
        override fun toString(): String = "untrusted"
    }

    /** The bound path no longer exists. */
    @Serializable
    @SerialName("missing")
    public object Missing : RulesStatus {
        override fun toString(): String = "missing"
    }

    /** The bound path could not be read as workspace content (I/O error, or it resolves outside the workspace). */
    @Serializable
    @SerialName("unreadable")
    public data class Unreadable(val reason: String) : RulesStatus

    /** The bytes changed after approval: they drop back to data until re-approved (I-08). */
    @Serializable
    @SerialName("changed")
    public data class ChangedSinceApproval(val approved: Digest, val current: Digest) : RulesStatus

    @Serializable
    @SerialName("approved")
    public data class Approved(val snapshot: RulesSnapshot) : RulesStatus
}

/**
 * Rules-file trust (§14.3, D-32). Discovery proposes candidates; only a binding from host configuration or
 * explicit user authority — canonical path + digest + provenance — promotes a snapshot to instruction text.
 * The binding lives in [io.astrolabe.Config.rulesFile], so an approved snapshot is reused across resumes by
 * construction: a fresh [RulesTrust] with the same binding returns the same snapshot while the bytes match.
 *
 * Editing the rules path elevates nothing: the digest of the current bytes is compared against the approved
 * one on every read, and a mismatch returns [RulesStatus.ChangedSinceApproval] **without** the text.
 */
public class RulesTrust(private val workspaceRoot: Path) {

    /** Files proposed for approval, in discovery order; all of them are data until bound. */
    public fun discover(): List<RulesCandidate> = CANDIDATE_PATHS.mapNotNull { candidate(it) }

    /** Reads one proposed path, or `null` when it is absent, unreadable or outside the workspace. */
    public fun candidate(relativePath: String): RulesCandidate? {
        val canonical = canonicalize(relativePath) ?: return null
        val bytes = readBytes(canonical) ?: return null
        return RulesCandidate(canonical, Digest.of(bytes), bytes.size.toLong())
    }

    /**
     * Records an authority's approval of exactly these bytes at this path. Pure: it performs no I/O and
     * approves no other version — if the file changed since [candidate] read it, [status] reports
     * [RulesStatus.ChangedSinceApproval] rather than silently approving the current bytes.
     */
    public fun bind(candidate: RulesCandidate, provenance: Provenance): RulesBinding =
        RulesBinding(candidate.path, candidate.digest, provenance.label)

    /** Trust state of [binding] against the bytes on disk right now. */
    public fun status(binding: RulesBinding?): RulesStatus {
        if (binding == null) return RulesStatus.Untrusted
        val canonical = canonicalize(binding.path)
            ?: return if (exists(binding.path)) RulesStatus.Unreadable("rules path resolves outside the workspace") else RulesStatus.Missing
        if (canonical != binding.path) {
            return RulesStatus.Unreadable("bound path '${binding.path}' canonicalizes to '$canonical'")
        }
        val bytes = readBytes(canonical) ?: return RulesStatus.Missing
        val digest = Digest.of(bytes)
        if (digest != binding.digest) return RulesStatus.ChangedSinceApproval(binding.digest, digest)
        return RulesStatus.Approved(RulesSnapshot(binding, String(bytes, Charsets.UTF_8), digest))
    }

    /** The instruction text, or `null` for every state other than [RulesStatus.Approved]. */
    public fun approved(binding: RulesBinding?): RulesSnapshot? = (status(binding) as? RulesStatus.Approved)?.snapshot

    /** Trust state of one discovered candidate: [RulesStatus.Untrusted] unless the binding names that path. */
    public fun status(candidate: RulesCandidate, binding: RulesBinding?): RulesStatus =
        if (binding == null || binding.path != candidate.path) RulesStatus.Untrusted else status(binding)

    private fun exists(relativePath: String): Boolean =
        runCatching { Files.exists(workspaceRoot.resolve(relativePath)) }.getOrDefault(false)

    private fun readBytes(canonicalPath: String): ByteArray? = try {
        val file = workspaceRoot.resolve(canonicalPath)
        if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    } catch (_: IOException) {
        null
    }

    /**
     * Canonical path = real-path resolution, then workspace-relative with forward slashes, compared
     * case-sensitively (D-47). `null` when the file does not exist or escapes the workspace root; the
     * [io.astrolabe.workspace.WorkspacePath] contract takes this over in P1.2.6.
     */
    private fun canonicalize(relativePath: String): String? = try {
        val root = workspaceRoot.toRealPath()
        val real = root.resolve(relativePath).toRealPath()
        if (!real.startsWith(root)) null else root.relativize(real).toString().replace('\\', '/')
    } catch (_: IOException) {
        null
    }

    public companion object {
        /** Names discovery proposes (D-32), in order. Being on this list grants nothing. */
        @JvmField
        public val CANDIDATE_PATHS: List<String> = listOf(".astrolabe/rules.md", "AGENTS.md", "CLAUDE.md")
    }
}
