package io.astrolabe.tool.run

import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.WorkspacePath
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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

    // walkFileTree, not Files.walk: an unreadable or vanishing directory elsewhere in the tree is skipped
    // instead of escaping as UncheckedIOException past callers that handle IOException.
    private fun files(): List<Pair<String, Path>> {
        val found = ArrayList<Pair<String, Path>>()
        Files.walkFileTree(root, emptySet(), 12, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.fileName.toString() == ".git") FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(file).joinToString("/")
                if (!relative.endsWith(".xml") || !REPORT.matches(relative)) return FileVisitResult.CONTINUE
                val resolved = paths.resolve(relative, Intent.Mutate) as? PathResolution.Resolved
                    ?: throw IOException("report path refused: $relative")
                if (!Files.isRegularFile(resolved.real)) return FileVisitResult.CONTINUE
                if (found.size == 4096) throw IOException("JUnit report count exceeds 4096")
                found += relative to resolved.real
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return found.sortedBy { it.first }
    }

    companion object {
        private val REPORT = Regex("(?:.*/)?(?:build/test-results/|target/(?:surefire|failsafe)-reports/).*\\.xml")
        fun forCommand(root: Path, argv: List<String>, actionId: String): JUnitReports? =
            if (argv.firstOrNull()?.replace('\\', '/')?.substringAfterLast('/')?.lowercase() in setOf("gradle", "gradlew", "gradlew.bat", "gradle.bat", "mvn", "mvn.cmd", "mvnw", "mvnw.cmd")) JUnitReports(root, actionId) else null
    }
}
