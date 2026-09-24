package io.astrolabe.evidence

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

    /** What differs from [now]: moved or added/removed paths, packages whose membership changed, lockfiles, fixtures. */
    public fun moved(now: ClosureManifest): List<String> {
        fun <V> changed(a: Map<String, V>, b: Map<String, V>) = (a.keys + b.keys).filter { a[it] != b[it] }
        return (
            changed(pathsAtVersions, now.pathsAtVersions) +
                changed(directoryMembership, now.directoryMembership).map { "$it/ (membership)" } +
                changed(configAndLockfiles, now.configAndLockfiles) +
                changed(fixtures, now.fixtures).map { "fixture $it" }
            ).distinct().sorted().ifEmpty { if (digest == now.digest) emptyList() else listOf("completeness or exclusions") }
    }

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
