package io.astrolabe.tool.run

import io.astrolabe.evidence.Counts
import kotlinx.serialization.Serializable

/**
 * Namespaced runner-native test identity (D-27, D-50): `(check, project/module, file/suite, name,
 * parameterization)`. Two tests with the same [name] in different modules or files are different
 * identities, and a parameterized instance whose [parameterization] changed is a different identity —
 * ambiguous matching can never prove a pre-existing failure (D-50, I-13).
 */
@Serializable
public data class TestIdentity(
    /** The verification check this run belongs to (`tests`, `accept:AC-4`); null when the runner alone is known. */
    val check: String? = null,
    /** Project or Gradle/Maven module the test belongs to. */
    val module: String? = null,
    /** Source file or runner class name (`tests/test_x.py`, `com.acme.CartTest`). */
    val file: String? = null,
    /** Suite/describe/class nesting inside [file]. */
    val suite: String? = null,
    val name: String,
    /** The instance of a parameterized test (`3`, `case=empty`); null for a plain test. */
    val parameterization: String? = null,
) {
    init {
        require(name.isNotBlank()) { "a test identity needs a name" }
    }

    /**
     * Collision-free canonical string. Every component is emitted in a fixed position, an absent one as
     * `-`, and `|`/`%` inside a component are escaped, so no two distinct identities can render alike.
     */
    val canonical: String
        get() = buildString {
            append(field(check)).append('|').append(field(module)).append('|').append(field(file))
            append('|').append(field(suite)).append('|').append(field(name)).append('|').append(field(parameterization))
        }

    /** Readable form for the shaped view: `module/file::suite::name[param]`, absent parts skipped. */
    val display: String
        get() = buildString {
            module?.let { append(it).append('/') }
            val path = listOfNotNull(file, suite).joinToString("::")
            if (path.isNotEmpty()) append(path).append("::")
            append(name)
            parameterization?.let { append('[').append(it).append(']') }
        }

    override fun toString(): String = display

    private fun field(value: String?): String =
        value?.let { "=" + it.replace("%", "%25").replace("|", "%7C") } ?: "-"

    public companion object {
        /** Splits a trailing `[…]` off a runner-native test name into its parameterization. */
        @JvmStatic
        public fun splitParameterization(name: String): Pair<String, String?> {
            if (!name.endsWith("]")) return name to null
            val open = name.indexOf('[')
            if (open <= 0) return name to null
            return name.substring(0, open) to name.substring(open + 1, name.length - 1)
        }
    }
}

/** Per-test outcome; the suite-level [io.astrolabe.evidence.Outcome] is derived from counts and the exit code. */
@Serializable
public enum class TestOutcome { Passed, Failed, Error, Skipped }

/** One executed (or skipped) test as the runner reported it. */
@Serializable
public data class TestResult(
    val identity: TestIdentity,
    val outcome: TestOutcome,
    /** First assertion/error line, already trimmed; null when the runner printed none. */
    val message: String? = null,
    val durationMillis: Long? = null,
) {
    val failing: Boolean get() = outcome == TestOutcome.Failed || outcome == TestOutcome.Error
}

/** Multiplicity-preserving helpers over a parsed result list (D-27): duplicates are never collapsed. */
public object TestResults {
    /** Counts derived from the parsed list; [discovered] defaults to the number of reported entries. */
    @JvmStatic
    @JvmOverloads
    public fun counts(results: List<TestResult>, discovered: Int = results.size): Counts = Counts(
        passed = results.count { it.outcome == TestOutcome.Passed },
        failed = results.count { it.outcome == TestOutcome.Failed },
        errors = results.count { it.outcome == TestOutcome.Error },
        skipped = results.count { it.outcome == TestOutcome.Skipped },
        discovered = discovered,
    )

    /** Canonical identity → how many entries carry it; a value > 1 is a real repeat, never an overwrite. */
    @JvmStatic
    public fun multiplicities(results: List<TestResult>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        results.forEach { out.merge(it.identity.canonical, 1, Int::plus) }
        return out
    }

    /** Identities reported more than once: matching them against a baseline is ambiguous (D-27, D-50). */
    @JvmStatic
    public fun ambiguous(results: List<TestResult>): Set<String> =
        multiplicities(results).filterValues { it > 1 }.keys
}
