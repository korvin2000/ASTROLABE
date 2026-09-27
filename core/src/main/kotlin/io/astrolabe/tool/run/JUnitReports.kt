package io.astrolabe.tool.run

import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.WorkspacePath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Existing reports are archived before dispatch; only newly created reports can certify this invocation. */
internal class JUnitReports(private val root: Path, private val actionId: String) {
    private val paths = WorkspacePath.of(root)

    fun prepare(archive: Path) {
        for ((relative, file) in files()) {
            val target = archive.resolve(relative)
            Files.createDirectories(target.parent)
            Files.move(file, target)
        }
    }

    fun collect(): List<ReportArtifact> {
        var remaining = 16 * 1024 * 1024
        return files().map { (relative, file) ->
            val bytes = Files.newInputStream(file).use { it.readNBytes(remaining + 1) }
            if (bytes.size > remaining) throw IOException("JUnit reports exceed the 16 MiB capture limit")
            remaining -= bytes.size
            ReportArtifact(relative, ReportKind.JUnitXml, true, "invocation:$actionId", bytes)
        }
    }

    private fun files(): List<Pair<String, Path>> = Files.walk(root, 12).use { stream ->
        val found = ArrayList<Pair<String, Path>>()
        val iterator = stream.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            val relative = root.relativize(file).joinToString("/")
            if (!relative.endsWith(".xml") || !REPORT.matches(relative)) continue
            val resolved = paths.resolve(relative, Intent.Mutate) as? PathResolution.Resolved
                ?: throw IOException("report path refused: $relative")
            if (!Files.isRegularFile(resolved.real)) continue
            if (found.size == 4096) throw IOException("JUnit report count exceeds 4096")
            found += relative to resolved.real
        }
        found.sortedBy { it.first }
    }

    companion object {
        private val REPORT = Regex("(?:.*/)?(?:build/test-results/|target/(?:surefire|failsafe)-reports/).*\\.xml")
        fun forCommand(root: Path, argv: List<String>, actionId: String): JUnitReports? =
            if (argv.firstOrNull()?.replace('\\', '/')?.substringAfterLast('/')?.lowercase() in setOf("gradle", "gradlew", "gradlew.bat", "gradle.bat", "mvn", "mvn.cmd", "mvnw", "mvnw.cmd")) JUnitReports(root, actionId) else null
    }
}
