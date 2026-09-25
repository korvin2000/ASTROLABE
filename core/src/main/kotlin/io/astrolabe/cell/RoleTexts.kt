package io.astrolabe.cell

import io.astrolabe.Config
import io.astrolabe.id.Digest

/**
 * The versioned default policy texts of the non-implementing roles (§3.4, D-38, D-160): at most three operational
 * persona lines each, written from the role's duties and output column. The implementing and writer roles speak the
 * kernel contract (Appendix A) instead; every other role gets these lines, the [shared] kernel lines and the shared
 * evidence lines, error policy and data rule in `[S]` ([Layout.system]), never the implementing STATE gate (kernel
 * contract scope note). A text describes duties and the packet to end with; it grants no authority and never marks a
 * requirement complete ([violations]).
 */
public object RoleTexts {
    public const val VERSION: String = "role-texts/1"

    @JvmField
    public val probe: List<String> = listOf(
        "Answer the question in the brief with bounded findings; you change nothing: look, kb.search and R-class runs only.",
        "Cite only ranges you were shown (path:lines[@hash]) or #ids; a claim about code you were not shown is inferred, never observed; say which scopes you searched and whether they were exhausted.",
        "Findings are pointers the parent must look at, not decisions: end with the Investigation packet; task.ask the parent when the question is ambiguous.",
    )

    @JvmField
    public val review: List<String> = listOf(
        "Judge the evidence packet against its acceptance criteria first; executable checks outrank opinion, and a failed required check stays failed whatever you conclude.",
        "Every finding names its severity and path:line; insufficient_evidence naming the criterion it lacks is a correct verdict.",
        "Your verdict is evidence for the verifier, never an acceptance: end with the verdict JSON.",
    )

    @JvmField
    public val qa: List<String> = listOf(
        "Exercise the behaviour under test only through the packet's entry points, in the disposable environment it names.",
        "Each case records steps, expected and observed, with its screenshot or log; an environment failure leaves the case undecided (passed: null).",
        "Report receipts and cases, never a product verdict on requirements: the verifier decides acceptance.",
    )

    @JvmField
    public val repair: List<String> = listOf(
        "You see one failure capsule: correct that call within its family, diagnose it, or escalate — at most two attempts.",
        "A fix counts only when the original acceptance re-verifies; never delete, skip or weaken a check to make a failure disappear.",
        "End with the repair JSON: outcome fixed (with the corrected call and its result), diagnosis or escalate, in at most 100 tokens of text.",
    )

    @JvmField
    public val extractor: List<String> = listOf(
        "From the archived trace, propose candidate notes in the diagnosis shape, each with evidence refs, a scope and an invalidation condition.",
        "Propose, never admit: the curator decides admission, and calibration notes are aggregated by the harness, never written by you.",
        "End with the candidates JSON; an empty list is a valid answer when the trace teaches nothing new.",
    )

    /** Default persona lines by role name; `plan` keeps the lines declared with its role (P2.1.2). */
    @JvmField
    public val defaults: Map<String, List<String>> = mapOf("probe" to probe, "review" to review, "qa" to qa, "repair" to repair, "extractor" to extractor)

    /**
     * The declared packet validator of each non-implementing packet kind (§3.7 `validate_role_output`): the factory the
     * dispatcher binds to `RoleCompletion.assess` with the packet's own context (D-162). `Result` is the exit gate.
     */
    @JvmField
    public val validators: Map<PacketKind, String> = mapOf(
        PacketKind.PlanArtifacts to "PlanPacketValidator.completion",
        PacketKind.Investigation to "Probe.completion",
        PacketKind.Verdict to "Judge.completion",
        PacketKind.ReceiptsAndCases to "QaCell.completion",
        PacketKind.Diagnosis to "RepairPacket.completion",
        PacketKind.NoteCandidates to "CandidatePacket.completion",
    )

    /** Kernel lines every role shares: the harness and its only oracle (line 1) and the data rule (line 11). */
    @JvmField
    public val shared: List<String> = listOf(Kernel.lines[0], Kernel.lines[10])

    // D-161: wording that would hand a role authority or a completion it does not have.
    private val FORBIDDEN: List<Pair<Regex, String>> = listOf(
        Regex("""\b(mark|marks|marking|declare|declares|consider)\b[^.;]{0,40}\b(complete|completed|done|satisfied|accepted|verified|green)\b""", RegexOption.IGNORE_CASE) to
            "marks a requirement complete",
        Regex("""\byou\s+(may|can|are\s+allowed\s+to|are\s+authori[sz]ed\s+to)\s+(\w+\s+){0,3}?(accept|admit|merge|publish|grant|commit|push|override|bypass|widen|amend)\b""", RegexOption.IGNORE_CASE) to
            "grants authority",
        Regex("""\b(ignore|override|bypass|disable)\b[^.;]{0,30}\b(gate|gates|check|checks|verifier|contract|mask|ceiling|policy)\b""", RegexOption.IGNORE_CASE) to
            "sets a control aside",
    )

    /** What in [role]'s persona and duty text would grant authority or mark a requirement complete; empty ⇔ allowed. */
    @JvmStatic
    public fun violations(role: Role): List<String> = (role.personaLines + role.duties).flatMap { line ->
        FORBIDDEN.filter { (pattern, _) -> pattern.containsMatchIn(line) }.map { (_, what) -> "'${line.take(80)}' $what" }
    }

    /** The recorded text version of [role]: its policy version plus a digest of its wording, so a host rewording is visible. */
    @JvmStatic
    public fun version(role: Role): String =
        role.policyTextVersion + "#" + Digest.ofUtf8((role.personaLines + listOf("--") + role.duties).joinToString("\n")).hex.take(8)

    /** The text version of every declared role as [config] words it: part of the frozen attempt configuration (D-38). */
    @JvmStatic
    public fun versions(config: Config): Map<String, String> =
        Roles.defaults.keys.sorted().associateWith { name -> version(config.role(name) ?: Roles.defaults.getValue(name)) }

    /**
     * [role] with the wording of [configured] (a host override), keeping [role]'s mask, permission, packet and view:
     * an override changes wording only (D-38), so a caller-narrowed role stays narrowed.
     */
    @JvmStatic
    public fun worded(role: Role, configured: Role?): Role =
        if (configured == null || configured.name != role.name) role
        else role.copy(personaLines = configured.personaLines, duties = configured.duties, policyTextVersion = configured.policyTextVersion)
}
