# Recovery, attempts and resumption

**ASTROLABE 1.0.1 · specification** · Owner: Controller / runner / recovery ladder.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §13, §13.1, §13.2, §13.3, §13.4. **Read with:** [evidence-coherence](../state/evidence-coherence.md) · [residency-rebuild](../runtime/residency-rebuild.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F07](../../REVIEW.md#f07), [F09](../../REVIEW.md#f09).

<!-- source-section: 13 -->
<a id="sec-13"></a>

## 13. Recovery and durability
<!-- end-source-section: 13 -->

<!-- source-section: 13.1 -->
<a id="sec-13-1"></a>

### 13.1 Reconcile before retry `[A §13.1; B §5.5; C §8.5; IM §4.4]`

```text
recover(failure):
    classify execution state and completed effects (intent recorded · dispatched · running · effect observed · durably completed · unknown)
    if outcome unknown: reconcile workspace / process / external state before any retry
    if transient and safe within budget: bounded deterministic retry (≤ 2, with backoff; classified safe operations only)
    elif localized and repairable within granted capability: one scoped repair (capsule) and re-verify the original intent
    else: return to the owning cell with evidence and an explicit reason
```

Timeouts after non-idempotent effects never auto-replay; lease expiry or cancellation revokes publication authority, not the fact that an action may still be running. The controller increments an execution generation on reassignment/supersession; the runner checks that generation, lease, authority and atomic budget reservation before dispatch and the verifier/integrator rechecks before acceptance/publication. Late observations and actual effects are persisted even when publication is refused. Reconcile an old owner’s unknown effects before granting another writer the same workspace; a lease alone cannot fence an arbitrary OS process.  A partial tool chain records completed effects first; a missing or unresponsive poll is not proof that a job stopped — reconcile with the execution backend, never launch a duplicate because an observation timed out `[B §5.5]`. Long-lived processes carry a backend handle plus identity sufficient to avoid PID-reuse confusion, a log cursor and cancellation status; the OS adapter tests process-group or job-object behaviour on its platform `[B §8.2]`.
<!-- end-source-section: 13.1 -->

<!-- source-section: 13.2 -->
<a id="sec-13-2"></a>

### 13.2 Failure classes → bounded responses `[C §10.1; IM §4.3; B §11.4]`

| Class | Evidence inspected | Bounded response | Handler | What escalation must not conceal `[B]` |
|---|---|---|---|---|
| Transport / rate limit | provider status, retry metadata | backoff within the provider budget; task state preserved | adapter | — |
| Invalid tool arguments | schema error, intended operation | correct the call; hypothesis unchanged | cell (hint) | no need to rethink the design |
| Stale anchor / region unseen | current content, expected hash | re-read the unit; regenerate the hunk (diff-since-expect) | cell | anchor uniqueness does not repair a stale base |
| Build / environment failure | dependency and runtime diagnostics | repair within scope or record a concrete blocker | cell → repair helper | failing setup ≠ failing implementation |
| Behavioural test failure | failing assertion, path, diff | revise the implementation hypothesis | cell | a repair helper cannot redefine intended behaviour |
| Missing repository contract | unresolved caller, config, schema, fixture | retrieve the complement (`look(refs/impact)`, `kb.search`, probe cell) | cell / probe | a stronger model still lacks evidence |
| Repeated failed hypothesis | same fingerprint after repairs | stop repeating; dead end; alternative attempt ([§13.3](#sec-13-3)) | recovery ladder | more calls with unchanged assumptions are not a new strategy |
| Truncated model response | finish status, incomplete action | provider continuation path; never execute a partial call | adapter | — |
| Unknown action outcome | open intent, process state, tree stamp | reconcile before any retry | runner | never blind-replay a non-idempotent chain |
| Lost constraint or evidence after a rebuild | manifest, projection validation | restore the previous projection and relevant source | compiler | a schema-valid rebuild can still be inadequate |
| Authorization denial | policy record | never search for a bypass; ask or record blocked | cell | — |
| Budget exhaustion | remaining acceptance, spend | persist progress; `partial` with STATE as the report | controller | — |
| Superseded unit | parent goal changed | cancel; keep evidence; count spend | controller | — |

**Recovery ladder — cheapest competent layer first** `[C §10.2; MB §8.4]`: (1) deterministic guards — doom loop (same tool + same args ≥ 3 without a state change), per-tool error budgets, request caps, cancellation; (2) **failure fingerprints** `hash(normalized error, attempted fix, relevant state, affected requirement)`, campaign-scoped, so equivalent no-progress patterns across *any* cell of the campaign count against one global no-progress budget (stops distributed doom loops); a meaningful edit or new observation changes the state — identical commands against changed inputs are not a loop `[B §11.4]`; (3) the tool-provided hint from the envelope; (4) a scoped repair attempt: the repair helper receives a **failure capsule** (intended operation, relevant acceptance criterion, exact call arguments, environment, error/exit status, current artifact versions, completed effects, raw evidence refs, previous attempts, allowed fixes, remaining budget), ≤ 2 attempts, tools masked to the failing family; outputs `fixed (corrected call + verified result) | diagnosis | escalate`; it never rewrites a failure into apparent success (fixture: deleting a failing test ⇒ the original acceptance still fails); (5) escalate to the owning cell for failures involving intent or architecture, to the plan cell for contract questions, to the user via `task.ask` when missing information changes the intended result. Invariant: the caller's `[A]` always receives a ≤ 100-token diagnosis line — the learning signal stays in the main context `[MB §8.4]`; recurring diagnoses become `PIT` candidates and tool-description improvements.
<!-- end-source-section: 13.2 -->

<!-- source-section: 13.3 -->
<a id="sec-13-3"></a>

### 13.3 Alternative attempts and refinement `[C §10.3; B §11.1; IM §10.2]`

When feedback identifies a local defect, refinement in place is cheaper. When the same hypothesis fails twice, an **alternative attempt** avoids inheriting its assumptions: `rebuild(alternative_attempt)` ([§5.8](../runtime/residency-rebuild.md#sec-5-8)) starts a new attempt id with the same contract, STATE's Dead ends and Decisions attached, an empty transcript tail and, optionally, the escalation profile. Both attempts' receipts are kept; selection is by acceptance evidence, never by plurality over text; at most `budget.attempts` total substantive attempts per increment (default 2: initial attempt plus one alternative/escalated attempt); then `blocked`. Transport retries and scoped repair calls have their own existing bounded counters and still consume the same campaign budget. A controller-assigned new attempt id preserves previous evidence; it never resets the increment’s attempt count. No fixed branch count, no speculative branching before simpler recovery.
<!-- end-source-section: 13.3 -->

<!-- source-section: 13.4 -->
<a id="sec-13-4"></a>

### 13.4 Resume protocol `[A §13.3; C §13.4; IM §4.4]`

Identities: work (stable) · attempt (per execution) · candidate (tree stamp) · context (cell). On resume (crash, sleep, new session): load contract, ledger, last register, Workset export, live process handles, frozen attempt configuration; diff the current tree stamp against the last recorded one and list external changes as Touched `(external)`; check bg handles (`running / exited / lost`); open intents without receipts → `unknown_outcome` → reconcile before any retry; recompute receipt applicability by closure; delegated results with moved bases → `stale-for-integration`; `rebuild(resume)`. The register is never trusted over the workspace; the first turn after resume sees `KNOWN: seeds only` and a one-line resume note. Preserve already granted authorization across resumes; new approval is needed only when an action exceeds it, not because a plan reached a new phase `[B §8.6]`. Crash intervals to test: during a command, after a mutation but before its receipt, during a rebuild, after an external effect whose acknowledgement was lost `[IM §13.4]`.

---
<!-- end-source-section: 13.4 -->

