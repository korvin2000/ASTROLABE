# Gates, Result Packet and finish receipt

**ASTROLABE 1.0.1 · specification** · Owner: Cell runtime / verifier / controller.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §5.6, §5.9. **Read with:** [acceptance-review](../verification/acceptance-review.md) · [lifecycle](../architecture/lifecycle.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F01](../../REVIEW.md#f01), [F09](../../REVIEW.md#f09).

<!-- source-section: 5.6 -->
<a id="sec-5-6"></a>

### 5.6 Gates and nudges `[A §5.6 ∪ C §8.8; one line each; fire once per condition; computed by the harness, no judge model]`

| Gate | Trigger | Effect |
|---|---|---|
| Entry | first non-register edit while the register has no plan step with an `accept:` or the increment's acceptance is unresolved | nudge; if acceptance cannot be written crisply, the right move is one question (`task.ask`) |
| Exit (hard) | completion proposal while any `run:` acceptance lacks a green receipt at the current stamp, a `check:` lacks accepted current evidence, a required review lacks a current approval, a required obligation is red, or any `[ ]`/`[>]` step lacks disposition | refused; the anchor lists exactly what is missing; escape only via `state(blocked)` / `task.ask` with evidence |
| Pressure | `tokens > α·C_max` | fold into register; rebuild ([§5.8](residency-rebuild.md#sec-5-8)); second rebuild ⇒ `partial` + replan hint |
| Stall | 3 turns without a progress event (evidence-backed tick, h→v with id, green run advancing an AC, verified new fact, new dead end) — a live build producing output is work, not a stall | one line: re-read plan · zoom out · run the pending decision probe · surface the blocker · or request a probe cell |
| Loop | identical `(tool, args, result hash)` twice | one line; third occurrence ends the turn with a required `state` op |
| No cursor / two cursors | register invariant | patch rejected with the rule |
| Red not recorded | `[>]` advances while a verify line is red and no `Open` item references it | patch rejected |
| Stale fact in Next | `Next` or a decision rests on an `h` or `v(stale)` fact | flagged risk line |
| **Impact** `[C §9.3]` | a changed definition (signature, visibility, export) of a symbol with `fanin > 0` whose references were not inspected since the change | "impact: `Router.dispatch` signature changed; 6 references not inspected → look(refs) or scope the plan" |
| **Contract touch** `[C §8.8]` | an edit set touches anchors of a `CON` note | "contract payments-api@7 touched: an ADR in the main line is required before this lands" |
| **Repeated failure signature** `[C §8.8]` | same normalized error after 2 repairs | "same failure twice: change the hypothesis, record a dead end, or request an alternative attempt" |
| Scope `[A §8.6]` | an edit touches a path outside `increment.write_scope` (inside contract scope) | allowed once with a warning; the second requires `task.propose(increment_split)` or a justification in `why` |
| Acceptance surface / test integrity `[A §8.6; C §9.7]` | an edit or run touches test files, snapshots, skip markers, CI config or acceptance commands | rendered as a flagged line with the classifier's kind; must be justified in the Result Packet; forces review when the change weakens an existing check |
| Reserve `[C §8.8]` | verification reserve reached | "reserve reached: verify and report; no new edits" |
| Turn budget | 80 % of cell turns | nudge: reach a coherent boundary and checkpoint |
<!-- end-source-section: 5.6 -->

<!-- source-section: 5.9 -->
<a id="sec-5-9"></a>

### 5.9 Cell termination and the Result Packet `[A §5.9 + C §6.1 + B §9.6]`

```yaml
result:
  work: W-0042  attempt: a2  cell: cell-8  increment: I2
  contract_version: 3  execution_generation: 1       # controller/runner-owned, checked before acceptance
  base: {stamp: s7, workspace: ws-main}                # recorded dispatch base, not the final stamp
  read_versions: {"src/router.py": a9f1}              # runtime-observed dependency versions, not model assertions
  status: done | blocked | partial | replan | waiting        # validated by the verifier, never accepted from the model's word
  waiting: {handle: P6, reason: "full suite running"}?      # B: verified waiting is distinct from stalled work
  register: <final STATE>        workset_export: [(path, range, version)]
  changes: [{path, kind, diffstat, v_before, v_after}]   transforms: [{script_hash, files: 14, diff: "#57", inventory_ok: true}]
  receipts: [rcpt-19, rcpt-20]   # produced by the scheduler, referenced not restated
  stamp: s8
  coverage: {ranges_displayed: n, files_touched_unread: [], probe_findings_used: [...]}   # from telemetry, not the model
  flags: {scope_warnings: 1, test_integrity: [{path, kind: "skip-marker added", justification: "…"}], impact_nudges_unresolved: 0}
  not_tested: ["concurrency above 8 callers"]
  notes_to_persist: [{kind: LES, summary, scope, evidence_refs, anchors}]
  open_questions: []   blocked: {reason, evidence, question}?
  self_assessment: {complexity_observed, confidence, risk_flags}     # informs routing calibration; never decides
  cost: {uncached_in, cache_read, cache_write, output, tool_seconds, helper_tokens}
```

`done` is a *proposal* until the scheduler's exit gate accepted it; the controller's ledger changes only on receipts. `blocked` with a question is a success path, not a failure `[MB §5.4]`. Campaign-level outcomes are `completed · waiting_for_process · waiting_for_input · blocked_external · budget_exhausted · cancelled · failed` `[B §9.6]`; a budget stop is `partial`, never `verified`. Unsupported repeated finalization requests trigger gap-directed recovery, not an endless gate that consumes the remaining budget `[B §9.6]`.

**Campaign finish receipt** `[C §13.5]` lists: requirements with status and blockers; acceptance items with kind, status, stamp, currency and log ids; changes split into `agent`, `by_run` and `pre_existing_user_changes` (untouched); `acceptance_surface_modified` with recorded reasons; checks run with verifier version and environment; `not_verified`; dead ends, decisions, ADR candidates, open items, pending amendments; routing decisions with reasons; budget by cache class and helper share; memory candidates proposed/admitted/queued; `highest_authorized_stage` — never "delivered" for a patch, never "verified" for plausible tests.
<!-- end-source-section: 5.9 -->

