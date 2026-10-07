package io.astrolabe.atlas

import io.astrolabe.id.Digest
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * T-03/T-25 (D-427, one read per file): set as a workspace's read tap for one capture, it keeps the outline and short
 * hash of every file the capture read that the atlas parses, taken from those very bytes. The atlas built right after the
 * capture ([Atlas.build]) uses them when the capture recorded the same digest and the scan the same size, so a tool
 * directory of sources is read once per open instead of once by the capture and again by the atlas. Orientation only:
 * nothing here is identity (§7.1, I-05).
 */
internal class AtlasTap(root: Path) : (Path, ByteArray) -> Unit {
    private val root: Path = root.toAbsolutePath().normalize()

    /** By repository-relative path: the short hash and the outline of the bytes the capture read. */
    val taken: MutableMap<String, Pair<String, Outline>> = ConcurrentHashMap()

    override fun invoke(real: Path, bytes: ByteArray) {
        if (!real.startsWith(root) || bytes.size > Atlas.MAX_PARSED_BYTES) return
        val path = root.relativize(real).toString().replace('\\', '/')
        if (path.isEmpty() || Language.of(path) == Language.Other || collapseReasonFor(path, bytes.size.toLong()) != null) return
        taken[path] = Digest.of(bytes).hash8 to Outline.of(path, bytes)
    }
}
