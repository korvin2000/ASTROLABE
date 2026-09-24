package io.astrolabe.verify

import io.astrolabe.atlas.ImpactAnalysis
import io.astrolabe.atlas.ImpactScope
import io.astrolabe.evidence.Closure
import io.astrolabe.id.CanonicalEncoding
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.workspace.EnvFingerprint
import kotlinx.serialization.Serializable

/** How much of a closure a [ClosureManifest] could pin (§8.1): only `complete` can back a reuse proof (P3.1.2). */
@Serializable
public enum class ClosureCompleteness { Complete, Partial, Unknown }

/**
 * The `closure_manifest` of §8.1: the closure's paths at their raw-byte versions, the membership of every package
 * directory (so an added or deleted test file changes it), the lockfiles of the tree, the external fixtures, and
 * what was excluded and why. A path list alone never proves completeness; [completeness] says what this one proves.
 */
@Serializable
public data class ClosureManifest(
    val pathsAtVersions: Map<String, String>,
    val directoryMembership: Map<String, List<String>>,
    val configAndLockfiles: Map<String, String>,
    val fixtures: Map<String, String>,
    val completeness: ClosureCompleteness,
    val exclusions: List<String>,
) {
    /** Canonical identity of everything the manifest pins; two manifests are the same closure iff their digests are. */
    val digest: Digest
        get() = Digest.ofUtf8(
            CanonicalEncoding.encode(
                "closure-manifest", 1,
                // Keys are fixed; each entry is length-prefixed so no path or fixture name can forge another.
                pathsAtVersions.map { "path" to entry(it.key, it.value) } +
                    directoryMembership.map { "dir" to entry(it.key, it.value.joinToString("\n") { m -> "${m.length}:$m" }) } +
                    configAndLockfiles.map { "config" to entry(it.key, it.value) } +
                    fixtures.map { "fixture" to entry(it.key, it.value) } +
                    listOf("completeness" to completeness.name, "exclusions" to exclusions.joinToString("\n")),
            ),
        )

    public companion object {
        private fun entry(key: String, value: String): String = "${key.length}:$key=$value"

        /**
         * The manifest of [closure] over [tree] (the workspace's file list, e.g. the atlas rows): a `known` closure pins
         * its paths; a `package` closure pins every member under its directory and the membership itself; an
         * `unknown` closure pins nothing and stays `unknown`. [excluded] names scratch or ignored paths with a reason;
         * a path without a version (absent, unreadable) makes the manifest `partial`. Lockfiles of the tree are pinned
         * whatever the closure (they are environment inputs, §4.4); [fixtures] are external inputs the caller names.
         */
        @JvmStatic
        @JvmOverloads
        public fun of(
            closure: Closure,
            tree: Collection<String>,
            versionOf: (String) -> FileVersion?,
            fixtures: Map<String, String> = emptyMap(),
            excluded: (String) -> String? = { null },
        ): ClosureManifest {
            val lockfiles = tree.filter { it.substringAfterLast('/') in EnvFingerprint.LOCK_FILE_NAMES }.sorted()
                .mapNotNull { path -> versionOf(path)?.let { path to it.digest.hex } }.toMap()
            if (closure == Closure.Unknown) {
                return ClosureManifest(emptyMap(), emptyMap(), lockfiles, fixtures.toSortedMap(), ClosureCompleteness.Unknown, emptyList())
            }
            val (members, membership) = when (closure) {
                is Closure.Known -> closure.paths.sorted() to emptyMap()
                is Closure.Package -> {
                    val dir = closure.path.trimEnd('/')
                    val inside = tree.filter { dir.isEmpty() || it.startsWith("$dir/") }.sorted()
                    inside to mapOf(dir to inside)
                }
                Closure.Unknown -> error("handled above")
            }
            val pinned = sortedMapOf<String, String>()
            val exclusions = ArrayList<String>()
            var complete = true
            for (path in members) {
                val reason = excluded(path)
                if (reason != null) {
                    exclusions += "$path: $reason"
                    continue
                }
                val version = versionOf(path)
                if (version == null) {
                    exclusions += "$path: absent or unreadable"
                    complete = false
                } else {
                    pinned[path] = version.digest.hex
                }
            }
            return ClosureManifest(
                pinned, membership, lockfiles, fixtures.toSortedMap(),
                if (complete) ClosureCompleteness.Complete else ClosureCompleteness.Partial, exclusions,
            )
        }
    }
}

/** Closures the scheduler computes for its checks (§8.1, P3.1.1). */
public object Closures {
    /**
     * The input closure of the blast-selected test run (`CHK-tests-blast`) from an impact [analysis]: `known` over the
     * selected checks' test files and closures when the blast and every selected check's closure are complete and they
     * sit in one workspace; otherwise the conservative containing scope — the single verification scope's package
     * directory when [packagePath] names it, else `unknown` (never a guessed package, D-63).
     */
    @JvmStatic
    @JvmOverloads
    public fun blast(analysis: ImpactAnalysis, packagePath: (ImpactScope) -> String? = { null }): Closure {
        val selected = analysis.request.checks.filter { it.id in analysis.affectedTests }
        val files = selected.flatMap { it.testFiles + it.closure.orEmpty() }
        val exact = analysis.blastComplete && selected.all { it.closureComplete && it.closure != null } &&
            files.map { it.scope.workspace }.distinct().size <= 1
        if (exact) return Closure.Known(files.map { it.path }.toSortedSet())
        val scope = analysis.verificationScopes.singleOrNull()?.takeIf { it.packageId != null } ?: return Closure.Unknown
        return packagePath(scope)?.let { Closure.Package(it) } ?: Closure.Unknown
    }
}
