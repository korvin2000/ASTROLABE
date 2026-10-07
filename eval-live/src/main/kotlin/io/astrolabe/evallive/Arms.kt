package io.astrolabe.evallive

import io.astrolabe.Astrolabe
import io.astrolabe.RunSpec
import io.astrolabe.provider.Profile
import io.astrolabe.provider.aigate.AiGateAdapter
import kotlinx.serialization.Serializable
import net.ai.gate.Llm
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** What runs an arm's attempts: the core through the Studio's launch, or the reference loop over `provider-api`. */
internal enum class ArmRunner(val wire: String) { Core("core"), Loop("loop") }

/** The core's protocol: the structured one it has, or the direct one D1 brings. */
internal enum class Protocol(val wire: String) { Structured("structured"), Direct("direct") }

/**
 * An arm (plan §6 B5): a named configuration of one bench over [RunSpec.defaults], so arms are compared as
 * configurations of one `eval-live`, not as branches. [shape] (H2) forces the core's shape; [models] (H3) maps a
 * function to a model. A field the core cannot honour yet is [unsupported], and such an arm is refused, never run as if
 * the field were not set.
 */
internal data class Arm(
    val name: String,
    val runner: ArmRunner,
    val protocol: Protocol = Protocol.Structured,
    val shape: String? = null,
    val models: Map<String, String>? = null,
) {
    init {
        require(NAME.matches(name)) { "arm name '$name' must match ${NAME.pattern}" }
    }

    /** Why this arm cannot run on this core: one line per field it sets that nothing honours yet; empty when it can. */
    fun unsupported(): List<String> = buildList {
        if (runner == ArmRunner.Loop) {
            if (protocol != Protocol.Structured || shape != null || models != null) add("the loop arm has no core protocol, shape or model table")
            return@buildList
        }
        if (protocol == Protocol.Direct) add("protocol ${protocol.wire}: the core has no direct protocol yet (D1)")
        if (shape != null) add("shape forcing '$shape': the core cannot force a shape yet (H2)")
        if (models != null) add("model table ${models.toSortedMap()}: the core runs one model for every function (H3)")
    }

    /** Every field, in a fixed order: the arm's part of a result's configuration fingerprint. */
    val canonical: String
        get() = "arm=$name;runner=${runner.wire};protocol=${protocol.wire};shape=${shape ?: "-"};models=${models?.toSortedMap() ?: "-"}"

    /** The arm's [RunSpec] over the default arm's: every field an arm can set today leaves it as it is. */
    fun spec(base: RunSpec): RunSpec {
        check(unsupported().isEmpty()) { "arm $name cannot run: ${unsupported().joinToString("; ")}" }
        return base
    }

    companion object {
        private val NAME = Regex("[a-z0-9][a-z0-9-]{0,31}")
    }
}

/** The arms `--arm` names. */
internal object Arms {
    val DEFAULT: Arm = Arm("default", ArmRunner.Core)
    val LOOP: Arm = Arm("loop", ArmRunner.Loop)
    val DIRECT: Arm = Arm("direct", ArmRunner.Core, protocol = Protocol.Direct)

    val all: List<Arm> = listOf(DEFAULT, LOOP, DIRECT)

    /** The arm called [name], refused with a [UsageError] when it is unknown or sets a field the core cannot honour. */
    fun named(name: String): Arm {
        val arm = all.firstOrNull { it.name == name } ?: throw UsageError("unknown arm '$name' (known: ${all.joinToString(", ") { it.name }})")
        val unsupported = arm.unsupported()
        if (unsupported.isNotEmpty()) throw UsageError("arm '$name' cannot run: ${unsupported.joinToString("; ")}")
        return arm
    }
}

/**
 * What a kept `result.json` must match to stand for a run (plan §6 B5): the [arm], and the fingerprints of the
 * configuration (the arm's fields and the bench's knobs), of the code and of the task. A result of another key is run
 * again; a result without one (written before B5) never stands.
 */
@Serializable
internal data class ResultKey(val arm: String, val config: String, val code: String, val task: String) {
    /** The parts of [other] that differ from this key, by name. */
    fun differences(other: ResultKey?): List<String> = if (other == null) listOf("no key") else buildList {
        if (arm != other.arm) add("arm")
        if (config != other.config) add("config")
        if (code != other.code) add("code")
        if (task != other.task) add("task")
    }
}

internal object Fingerprints {
    fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))

    /** The run's configuration: the arm, provider, model and the bench's knobs, with the core's launch constants. */
    fun config(plan: BenchPlan, model: String): String = sha256(
        listOfNotNull(
            plan.arm.canonical, "provider=${plan.provider}", "model=$model", "effort=${plan.effort.name}", "effortExplicit=${plan.effortExplicit}",
            "maxCells=${plan.maxCells}", "deadlineSeconds=${plan.deadline.seconds}", "limits=${RunSpec.LIMITS}", "leaseMinutes=${RunSpec.LEASE_MINUTES}",
            "guardWindows=${RunSpec.TOKEN_GUARD_WINDOWS}",
            // Only an `ask` bench names its mode: `auto` results keep their fingerprints.
            plan.mode.takeIf { it != HostMode.Auto }?.let { "mode=${it.wire}" },
        ).joinToString("\n"),
    )

    /**
     * The code that runs and accounts a run: the classes or jars of `eval-live`, the core, `provider-api`, the AI Gate
     * adapter and the SDK, byte for byte, with the core's version. Computed once per process.
     */
    val code: String by lazy {
        val sources = listOf(Bench::class.java, Astrolabe::class.java, Profile::class.java, AiGateAdapter::class.java, Llm::class.java)
            .mapNotNull { runCatching { Path.of(it.protectionDomain.codeSource.location.toURI()) }.getOrNull() }.distinct().sorted()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Astrolabe.VERSION.toByteArray(StandardCharsets.UTF_8))
        for (source in sources) {
            digest.update(0.toByte())
            if (Files.isDirectory(source)) {
                val files = Files.walk(source).use { paths -> paths.filter(Files::isRegularFile).toList() }.sortedBy { source.relativize(it).joinToString("/") }
                for (file in files) {
                    digest.update(source.relativize(file).joinToString("/").toByteArray(StandardCharsets.UTF_8))
                    digest.update(0.toByte())
                    digest.update(Files.readAllBytes(file))
                }
            } else if (Files.isRegularFile(source)) {
                digest.update(Files.readAllBytes(source))
            }
        }
        HexFormat.of().formatHex(digest.digest())
    }

    /** What the agent is given and judged by: the request, the task file's fields, the base tree and the hidden acceptance. */
    fun task(task: BenchTask): String = sha256(
        listOfNotNull(
            "id=${task.id}", "class=${task.kind}", "title=${task.title}", "prompt=${task.prompt}", "acceptance=${task.acceptance}",
            "interrupt=${task.interrupt}", "baseCommit=${task.baseCommit}", "message=${task.message}", "reopen=${task.reopen}",
            "base=${HiddenFiles.read(task.base).digest}", "hidden=${task.hidden.digest}",
            // Only a dirty task names its dirt: the other tasks keep their fingerprints.
            task.dirt?.let { "dirt=$it" },
            // Only the second task of a pair names its first: the other tasks keep their fingerprints.
            task.first?.let { "after=${this.task(it)}" },
        ).joinToString("\n"),
    )
}
