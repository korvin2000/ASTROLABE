package io.astrolabe.atlas

import io.astrolabe.id.Digest
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * T-03/T-25 (D-427, one read per file): set as a workspace's read tap for one capture, it keeps the outline and short
 * hash of every file the capture read that the atlas lists, taken from those very bytes — a file no outline parser models
 * too, whose generic outline a symbol lookup reads (T-03 live) — and, for a parsed language, the import graph's
 * dynamic-import findings (T-59). The atlas built right after the capture ([Atlas.build]) uses them when the capture
 * recorded the same digest and the scan the same size, so a tool directory is read once per open instead of once by the
 * capture and again by the atlas, its symbol index or its import graph. Orientation only: nothing here is identity
 * (§7.1, I-05).
 */
internal class AtlasTap(root: Path) : (Path, ByteArray) -> Unit {
    private val root: Path = root.toAbsolutePath().normalize()

    /** One file's take: the short hash and outline of the bytes read, and the dynamic-import findings of a parsed language. */
    class Taken(val hash8: String, val outline: Outline, val dynamic: List<String>?)

    /** By repository-relative path: what the capture's read of the file gave. */
    val taken: MutableMap<String, Taken> = ConcurrentHashMap()

    override fun invoke(real: Path, bytes: ByteArray) {
        if (!real.startsWith(root) || bytes.size > Atlas.MAX_PARSED_BYTES) return
        val path = root.relativize(real).toString().replace('\\', '/')
        if (path.isEmpty() || collapseReasonFor(path, bytes.size.toLong()) != null) return
        taken[path] = Taken(Digest.of(bytes).hash8, Outline.of(path, bytes), ImportGraph.dynamicFindings(Language.of(path), bytes))
    }
}
