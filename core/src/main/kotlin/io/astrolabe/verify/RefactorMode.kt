package io.astrolabe.verify

import io.astrolabe.contract.Contract
import kotlinx.serialization.Serializable

/**
 * Whether refactor mode (§8.9) is active for a contract and why. [reasons] name the explicit flag or the
 * behaviour-preserving requirement that activated it; the detection is a keyword heuristic and labelled as such.
 */
public data class RefactorDetection(val active: Boolean, val reasons: List<String> = emptyList())

/**
 * The §8.9 checklist the plan cell records before the first increment of a refactor: behaviour to preserve,
 * interfaces to change, compatibility duration, callers/consumers, data/configuration dependencies, independent
 * acceptance checks, and the shared decision (a `CON`/ADR in the main line) separated from the mechanical edits.
 */
@Serializable
public data class RefactorChecklist(
    val behaviourToPreserve: String,
    val interfacesToChange: String,
    val compatibilityDuration: String,
    val callersConsumers: String,
    val dataConfigurationDependencies: String,
    val independentAcceptanceChecks: String,
    /** The shared decision, named separately from the mechanical edits; it travels as a CON/ADR candidate or a decision packet. */
    val sharedDecision: String,
) {
    /** Blank entries, named the way §8.9 names them; empty ⇔ the checklist is recorded. */
    public fun gaps(): List<String> = listOf(
        "behaviour to preserve" to behaviourToPreserve,
        "interfaces to change" to interfacesToChange,
        "compatibility duration" to compatibilityDuration,
        "callers/consumers" to callersConsumers,
        "data/configuration dependencies" to dataConfigurationDependencies,
        "independent acceptance checks" to independentAcceptanceChecks,
        "shared decision" to sharedDecision,
    ).filter { (_, value) -> value.isBlank() }.map { (name, _) -> "refactor checklist: $name is blank" }
}

/**
 * Refactor mode activation (§8.9, §1.2 broad refactor class): an explicit contract flag — a constraint whose id is
 * [FLAG] or whose text is `refactor_mode`, `refactor_mode: true` — or behaviour-preserving requirement keywords
 * (refactor, extract, rename, migrate API). A constraint `refactor_mode: false` switches the keyword heuristic off:
 * the contract's authority outranks a guess about its words. Requirements are the text; the requests are read only
 * when a contract carries no requirement yet.
 */
public object RefactorMode {
    public const val FLAG: String = "refactor_mode"

    private val FLAG_ON = Regex("""^refactor[_\- ]?mode(\s*[:=]\s*(true|on|yes))?$""", RegexOption.IGNORE_CASE)
    private val FLAG_OFF = Regex("""^refactor[_\- ]?mode\s*[:=]\s*(false|off|no)$""", RegexOption.IGNORE_CASE)
    private val KEYWORDS = Regex(
        """\b(refactor(s|ed|ing)?|extract(s|ed|ing|ion)?|renam(e|es|ed|ing)|migrat(e|es|ed|ing|ion)\s+(the\s+|an?\s+|its\s+|our\s+)?(public\s+)?api|api\s+migration)\b""",
        RegexOption.IGNORE_CASE,
    )

    @JvmStatic
    public fun detect(contract: Contract): RefactorDetection {
        val texts = contract.constraints.map { it.text.trim() }
        val flaggedOn = contract.constraints.filter { it.id == FLAG || FLAG_ON.matches(it.text.trim()) }
        if (flaggedOn.isNotEmpty()) return RefactorDetection(true, flaggedOn.map { "constraint ${it.id}: $FLAG" })
        if (texts.any { FLAG_OFF.matches(it) }) return RefactorDetection(false, listOf("constraint $FLAG: off"))
        val requirements = contract.requirements.map { it.id to it.text }.ifEmpty { contract.requests.map { it.id to it.text } }
        val reasons = requirements.mapNotNull { (id, text) ->
            KEYWORDS.find(text)?.let { "$id: behaviour-preserving requirement ('${it.value}')" }
        }
        return RefactorDetection(reasons.isNotEmpty(), reasons)
    }

    @JvmStatic
    public fun isActive(contract: Contract): Boolean = detect(contract).active

    /** The `red_ok_until` a refactor increment declares (§8.9 item 2): red is not recorded before the increment end. */
    public const val RED_OK_UNTIL: String = "increment_end"
}
