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
    private var preparedComplete = true

    fun prepare(archive: Path) {
        val scan = scan()
        preparedComplete = scan.complete
        for ((relative, file) in scan.files) {
            val target = archive.resolve(relative)
            Files.createDirectories(target.parent)
            Files.move(file, target)
        }
    }

    fun collect(): List<ReportArtifact> {
        var remaining = 16 * 1024 * 1024
        val scan = scan()
        return scan.files.map { (relative, file) ->
            val bytes = Files.newInputStream(file).use { it.readNBytes(remaining + 1) }
            if (bytes.size > remaining) throw IOException("JUnit reports exceed the 16 MiB capture limit")
            remaining -= bytes.size
            ReportArtifact(relative, ReportKind.JUnitXml, true, "invocation:$actionId", bytes, collectionComplete = preparedComplete && scan.complete)
        }
    }

    // Unrelated unreadable paths are skipped, but missing report bytes must invalidate the collection.
    private fun scan(): ReportScan {
        val found = ArrayList<Pair<String, Path>>()
        var complete = true
        Files.walkFileTree(root, emptySet(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.fileName.toString() == ".git") FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(file).joinToString("/")
                if (attrs.isSymbolicLink && !REPORT.matches(relative)) complete = false
                if (!relative.endsWith(".xml") || !REPORT.matches(relative)) return FileVisitResult.CONTINUE
                val resolved = paths.resolve(relative, Intent.Mutate) as? PathResolution.Resolved
                    ?: throw IOException("report path refused: $relative")
                if (!Files.isRegularFile(resolved.real)) {
                    complete = false
                    return FileVisitResult.CONTINUE
                }
                if (found.size == 4096) throw IOException("JUnit report count exceeds 4096")
                found += relative to resolved.real
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                val relative = root.relativize(file).joinToString("/")
                if (REPORT.matches(relative) || REPORT_DIRECTORY.matches(relative)) throw IOException("JUnit report path could not be read: $relative", exc)
                complete = false
                return FileVisitResult.CONTINUE
            }
        })
        return ReportScan(found.sortedBy { it.first }, complete)
    }

    private data class ReportScan(val files: List<Pair<String, Path>>, val complete: Boolean)

    companion object {
        private val REPORT = Regex("(?:.*/)?(?:build/test-results/|target/(?:surefire|failsafe)-reports/).*\\.xml")
        private val REPORT_DIRECTORY = Regex("(?:.*/)?(?:build(?:/test-results(?:/.*)?)?|target(?:/(?:surefire|failsafe)-reports(?:/.*)?)?)")
        fun forCommand(root: Path, argv: List<String>, actionId: String): JUnitReports? =
            if (argv.firstOrNull()?.replace('\\', '/')?.substringAfterLast('/')?.lowercase() in setOf("gradle", "gradlew", "gradlew.bat", "gradle.bat", "mvn", "mvn.cmd", "mvnw", "mvnw.cmd")) JUnitReports(root, actionId) else null
    }
}
