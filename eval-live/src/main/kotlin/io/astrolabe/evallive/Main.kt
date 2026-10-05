package io.astrolabe.evallive

import io.astrolabe.RunSpec
import io.astrolabe.id.RandomIdGen
import io.astrolabe.provider.Effort
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlin.system.exitProcess

private val USAGE = """eval-live — the ASTROLABE headless live runner

  eval-live run --models <id,...> --out <dir> [--tasks <id,...|all>] [--provider openrouter] [--repeats 1] [--seed 0]
                [--effort Low|Medium|High] [--max-cells ${RunSpec.MAX_CELLS}] [--deadline-minutes 60] [--tasks-dir <dir>] [--python <program>]
                [--catalog <file>] [--credentials <file>] [--temp <dir>] [--keep-workspaces] [--arm default|loop]
                [--mode auto|ask]
  eval-live tasks [--tasks-dir <dir>]
  eval-live check [--tasks <id,...|all>] [--tasks-dir <dir>] [--python <program>] [--temp <dir>]

Keys come from the SDK's resolution: the provider's environment variable (OPENROUTER_API_KEY for openrouter), or the
SDK credential store file given with --credentials. Results: <out>/runs/<arm>/<task>/<model>/r<n>/{result.json,
events.jsonl, acceptance.log, workspace.diff} and <out>/summary.{csv,json}."""

/** A usage error: printed with the usage, exit status 2. */
internal class UsageError(message: String) : IllegalArgumentException(message)

/** `--name value` options and bare `--flag`s after the command word. */
internal class Options(args: List<String>, private val flags: Set<String>) {
    private val values = LinkedHashMap<String, String>()
    private val set = HashSet<String>()

    init {
        var i = 0
        while (i < args.size) {
            val name = args[i].takeIf { it.startsWith("--") }?.removePrefix("--") ?: throw UsageError("unexpected argument '${args[i]}'")
            if (name in flags) set += name
            else values[name] = args.getOrNull(++i) ?: throw UsageError("--$name needs a value")
            i++
        }
    }

    fun get(name: String): String? = values.remove(name)
    fun require(name: String): String = get(name) ?: throw UsageError("--$name is required")
    fun list(name: String): List<String>? = get(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    fun int(name: String, default: Int): Int = get(name)?.let { it.toIntOrNull() ?: throw UsageError("--$name needs a number") } ?: default
    fun long(name: String, default: Long): Long = get(name)?.let { it.toLongOrNull() ?: throw UsageError("--$name needs a number") } ?: default
    fun flag(name: String): Boolean = name in set

    /** Fails on options no command read. */
    fun done() {
        if (values.isNotEmpty()) throw UsageError("unknown option(s): ${values.keys.joinToString(", ") { "--$it" }}")
    }
}

public fun main(args: Array<String>) {
    val status = try {
        Cli.execute(args.toList(), Clock.systemUTC())
    } catch (e: UsageError) {
        System.err.println("eval-live: ${e.message}\n\n$USAGE")
        2
    } catch (e: MissingKey) {
        System.err.println("eval-live: ${e.message}")
        2
    }
    exitProcess(status)
}

internal object Cli {
    fun execute(args: List<String>, clock: Clock, log: (String) -> Unit = ::println): Int {
        val command = args.firstOrNull() ?: throw UsageError("a command is needed")
        val options = Options(args.drop(1), setOf("keep-workspaces"))
        return when (command) {
            "run" -> run(options, clock, log)
            "tasks" -> {
                val tasks = BenchTask.all(tasksDir(options))
                options.done()
                tasks.forEach { log("${it.id}\t${it.kind}\t${it.title}") }
                0
            }
            "check" -> check(options, log)
            "help", "--help" -> { log(USAGE); 0 }
            else -> throw UsageError("unknown command '$command'")
        }
    }

    private fun run(options: Options, clock: Clock, log: (String) -> Unit): Int {
        val out = Path.of(options.require("out")).toAbsolutePath()
        val models = options.list("models")?.takeIf { it.isNotEmpty() } ?: throw UsageError("--models is required")
        val provider = options.get("provider") ?: "openrouter"
        val tasks = BenchTask.select(tasksDir(options), options.list("tasks") ?: listOf("all"))
        val named = options.get("effort")?.let { e -> Effort.entries.firstOrNull { it.name.equals(e, ignoreCase = true) } ?: throw UsageError("unknown effort '$e'") }
        val plan = BenchPlan(
            tasks = tasks, models = models, provider = provider,
            repeats = options.int("repeats", 1), seed = options.long("seed", 0), out = out,
            temp = temp(options), effort = named ?: RunSpec.EFFORT, maxCells = options.int("max-cells", RunSpec.MAX_CELLS),
            deadline = Duration.ofMinutes(options.long("deadline-minutes", 60)), keepWorkspaces = options.flag("keep-workspaces"),
            effortExplicit = named != null, arm = Arms.named(options.get("arm") ?: Arms.DEFAULT.name),
            mode = HostMode.named(options.get("mode") ?: HostMode.Auto.wire),
        )
        val interpreters = Interpreters.detect(options.get("python"))
        val catalog = options.get("catalog")?.let(Path::of) ?: out.resolve("catalog-snapshot.json")
        val credentials = options.get("credentials")?.let(Path::of)
        options.done()
        LiveModels.runtime(catalog, credentials).use { llm ->
            val models = LiveModels(llm, provider, LocalDate.now(clock))
            models.requireKey()
            log("eval-live: ${plan.runs().size} run(s) of ${tasks.size} task(s) x ${plan.models.size} model(s) x ${plan.repeats}, seed ${plan.seed}, results in $out")
            val results = Bench(plan, models, interpreters, clock, RandomIdGen(), log).run()
            log("eval-live: ${results.count { it.acceptance?.passed == true }}/${results.size} accepted; summary in ${out.resolve("summary.csv")}")
        }
        return 0
    }

    private fun check(options: Options, log: (String) -> Unit): Int {
        val tasks = BenchTask.select(tasksDir(options), options.list("tasks") ?: listOf("all"))
        val interpreters = Interpreters.detect(options.get("python"))
        val temp = temp(options)
        options.done()
        java.nio.file.Files.createDirectories(temp)
        val acceptance = Acceptance(interpreters, temp)
        var sound = 0
        for (task in tasks) {
            val v = acceptance.validate(task, Acceptance.VISIBLE_TESTS)
            if (v.sound) sound++
            log("${task.id}: base ${if (v.onBase.passed) "PASSES (unsound)" else "fails"} (exit ${v.onBase.exitCode})" +
                ", wrong ${if (v.onWrong.passed) "PASSES (unsound)" else "fails"} (exit ${v.onWrong.exitCode})" +
                ", reference ${if (v.onReference.passed) "passes" else "FAILS (unsound)"} (exit ${v.onReference.exitCode})" +
                ", visible tests on reference ${when (v.visibleGreenOnReference) { true -> "green"; false -> "RED"; null -> "not run" }}")
        }
        log("$sound/${tasks.size} task(s) sound")
        return if (sound == tasks.size) 0 else 1
    }

    /**
     * `--tasks-dir`, else `-Devallive.tasks`, else `tasks/` of the installed distribution (beside `lib/`); there the
     * hidden parts come from the packaged [HiddenBundle].
     */
    private fun tasksDir(options: Options): Path {
        options.get("tasks-dir")?.let { return Path.of(it) }
        System.getProperty("evallive.tasks")?.let { return Path.of(it) }
        val jar = Path.of(Cli::class.java.protectionDomain.codeSource.location.toURI())
        return jar.parent.parent.resolve("tasks")
    }

    private fun temp(options: Options): Path =
        options.get("temp")?.let(Path::of) ?: Path.of(System.getProperty("java.io.tmpdir"), "eval-live")
}
