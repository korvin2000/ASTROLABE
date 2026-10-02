package io.astrolabe.eval.audit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess

/**
 * `audit <journal | results directory>… [--out <dir>] [--catalog <catalog-snapshot.json>]` writes `audit.json` and
 * `audit.md` to `--out`, by default the first directory given (or the first journal's folder). A results directory's
 * own `catalog-snapshot.json` supplies list prices where no billed call fits a route.
 */
public object AuditCli {
    @JvmStatic
    public fun main(args: Array<String>) {
        val paths = ArrayList<Path>()
        var out: Path? = null
        var catalog: Path? = null
        var i = 0
        while (i < args.size) {
            when (val arg = args[i++]) {
                "--out" -> out = Path.of(args.getOrNull(i++) ?: usage())
                "--catalog" -> catalog = Path.of(args.getOrNull(i++) ?: usage())
                else -> if (arg.startsWith("--")) usage() else paths.add(Path.of(arg))
            }
        }
        if (paths.isEmpty()) usage()
        val inputs = paths.flatMap(Audit::discover)
        val report = Audit.run(inputs, Audit.catalog(paths.filter { it.isDirectory() }, catalog))
        val target = out ?: paths.first().let { if (it.isDirectory()) it else it.toAbsolutePath().parent }
        Files.createDirectories(target)
        Files.writeString(target.resolve("audit.json"), Audit.toJson(report), StandardCharsets.UTF_8)
        Files.writeString(target.resolve("audit.md"), AuditMarkdown.render(report), StandardCharsets.UTF_8)
        println("audit: ${report.runs.size} runs, ${report.groups.size} groups -> ${target.resolve("audit.md")}")
    }

    private fun usage(): Nothing {
        System.err.println("usage: audit <journal | results directory>... [--out <dir>] [--catalog <catalog-snapshot.json>]")
        exitProcess(2)
    }
}
