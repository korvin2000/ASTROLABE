package io.astrolabe.recover

/** Who owns a failure class's bounded response (§13.2 "Handler"). */
public enum class Handler { Adapter, Cell, RepairHelper, Probe, RecoveryLadder, Runner, Compiler, Controller }

/**
 * The failure classes of §13.2, one row each, as data: the evidence inspected, the bounded response, the handler
 * and what escalation must not conceal. [transient] marks the only class a bounded deterministic retry may serve;
 * [repairable] the only class a scoped capsule repair may serve (D-135).
 */
public enum class FailureClass(
    public val evidence: String,
    public val response: String,
    public val handler: Handler,
    public val mustNotConceal: String?,
    public val transient: Boolean = false,
    public val repairable: Boolean = false,
) {
    TransportRateLimit("provider status, retry metadata", "backoff within the provider budget; task state preserved", Handler.Adapter, null, transient = true),
    InvalidToolArguments("schema error, intended operation", "correct the call; hypothesis unchanged", Handler.Cell, "no need to rethink the design"),
    StaleAnchor("current content, expected hash", "re-read the unit; regenerate the hunk (diff-since-expect)", Handler.Cell, "anchor uniqueness does not repair a stale base"),
    BuildEnvironment("dependency and runtime diagnostics", "repair within scope or record a concrete blocker", Handler.RepairHelper, "failing setup ≠ failing implementation", repairable = true),
    BehaviouralTestFailure("failing assertion, path, diff", "revise the implementation hypothesis", Handler.Cell, "a repair helper cannot redefine intended behaviour"),
    MissingRepositoryContract("unresolved caller, config, schema, fixture", "retrieve the complement (look(refs/impact), kb.search, probe cell)", Handler.Probe, "a stronger model still lacks evidence"),
    RepeatedFailedHypothesis("same fingerprint after repairs", "stop repeating; dead end; alternative attempt", Handler.RecoveryLadder, "more calls with unchanged assumptions are not a new strategy"),
    TruncatedModelResponse("finish status, incomplete action", "provider continuation path; never execute a partial call", Handler.Adapter, null),
    UnknownActionOutcome("open intent, process state, tree stamp", "reconcile before any retry", Handler.Runner, "never blind-replay a non-idempotent chain"),
    LostConstraint("manifest, projection validation", "restore the previous projection and relevant source", Handler.Compiler, "a schema-valid rebuild can still be inadequate"),
    AuthorizationDenial("policy record", "never search for a bypass; ask or record blocked", Handler.Cell, null),
    BudgetExhaustion("remaining acceptance, spend", "persist progress; partial with STATE as the report", Handler.Controller, null),
    SupersededUnit("parent goal changed", "cancel; keep evidence; count spend", Handler.Controller, null),
}

/** The execution state of the failing action and its effects (§13.1 `classify`). */
public enum class ExecutionState { IntentRecorded, Dispatched, Running, EffectObserved, DurablyCompleted, Unknown }
