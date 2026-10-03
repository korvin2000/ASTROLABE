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
| Entry | first non-register edit while none of the increment's acceptance items is in the contract and no plan step carries an `accept:` (acceptance the contract already defines — harness-derived `run:` items, the host's review item — needs no plan step, D-372) | nudge; if acceptance cannot be written crisply, the right move is one question (`task.ask`) |
| Exit (hard) | completion proposal resolved by the §8.7 rule (D-337): a required obligation is red, a reviewer rejected with substance, or any `[ ]`/`[>]` step (or other agent-owned gap) is open | refused; the anchor lists exactly what to fix; a rejection's findings are pinned whole (D-341). What cannot be verified is not refused: the cell ends and the proposal awaits an acceptance decision (D-339) |
| Sufficiency (plan §4.4, C1b) | the implementing cell, before it proposes completion: every `run:` item of the increment is green on the tree now and the §8.7 rule leaves the agent nothing to close (a reviewer's word, which the proposal obtains, aside) | once per cell, in `[A]` (never `[S]`): "evidence suffices: AC-1 green on this tree and nothing left to close — finish now; further checks are optional" (or it names the review that follows the proposal and the known reds); never in a cell an exit refusal or a `rework` decision already spoke to, nor while a mandatory check is red on this tree, whatever `Open` says |
| Pressure | `tokens > α·C_max` | fold into register; rebuild ([§5.8](residency-rebuild.md#sec-5-8)); second rebuild ⇒ `partial` + replan hint |
| Stall | 5 turns (`stallTurns`) without a progress event (evidence-backed tick, h→v with id, green run advancing an AC, verified new fact, new dead end) or work (an applied edit batch; a finished `run`/`verify` whose `(tool, args, result)` signature is new in the cell — a repeat with the same result and reads are not, D-366) — a live build producing output is work, not a stall; the nudge repeats every `stallTurns` idle turns (D-358) | one line: re-read plan · zoom out · run the pending decision probe · surface the blocker · or request a probe cell |
| Loop | identical `(tool, args, result hash)` twice | one line; third occurrence ends the turn with a required `state` op |
| Refusal loop (D-358) | identical refused call `(tool, args, refusal reason)` twice — a masked op, a schema error or a partition rejection repeated verbatim | one line naming the exits (`state(blocked)`, `task.ask`, `task.propose`); the third ends the cell `blocked` with the refusal as its reason, so the campaign surfaces it instead of spending the turn budget |
| No cursor / two cursors | register invariant | patch rejected with the rule |
| Red not recorded | `[>]` advances while a mandatory verify line is red and no `Open` item references it — every check the harness runs itself (an acceptance item's, the full suite, a quality gate, the blast radius, the types of touched files, any check of unknown origin); only the model's own `CHK-model-*`, lint and a check the host or the user declared that no acceptance item requires (not a full suite or quality gate) are optional: the runtime records their red as "known red since receipt #N" — in the exit resolution and the finish receipt's open items — until a later `passed` receipt of the same check on the tree now (a timeout or an inconclusive run never ends it) (plan §4.3, C1b) | patch rejected |
| Stale fact in Next | `Next` or a decision rests on an `h` or `v(stale)` fact | flagged risk line |
| **Impact** `[C §9.3]` | a changed definition (signature, visibility, export) of a symbol with `fanin > 0` whose references were not inspected since the change; never for a file the cell created (absent at its start), and at most 3 new nudges a turn — the rest are one summary line ("impact: … and N more: look(impact, paths)") and never exit obligations (D-366) | "impact: `Router.dispatch` signature changed; 6 references not inspected → look(refs) or scope the plan" |
| **Contract touch** `[C §8.8]` | an edit set touches anchors of a `CON` note | "contract payments-api@7 touched: an ADR in the main line is required before this lands" |
| **Repeated failure signature** `[C §8.8]` | same normalized error after 2 repairs | "same failure twice: change the hypothesis, record a dead end, or request an alternative attempt" |
| Scope `[A §8.6]` | an edit touches a path outside `increment.write_scope` (inside contract scope) | allowed once with a warning; the second requires `task.propose(increment_split)` or a justification in `why` |
| Acceptance surface / test integrity `[A §8.6; C §9.7]` | an edit or run touches test files, snapshots, skip markers, CI config or acceptance commands | rendered as a flagged line with the classifier's kind; must be justified in the Result Packet; forces review when the change weakens an existing check |
| Reserve `[C §8.8]` | verification reserve reached | "reserve reached: verify and report; no new edits" — on a reserve reached by the turn count an edit batch whose every op targets a path the cell already changed still runs; any other edit is refused with "reserve reached: edits are limited to files this cell already changed (…); verify and report" (D-366) |
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

`done` is a *proposal* until the scheduler's exit gate accepted it; the controller's ledger changes only on receipts. `blocked` with a question is a success path, not a failure `[MB §5.4]`. Campaign-level outcomes are `completed · answered · waiting_for_process · waiting_for_input · blocked_external · budget_exhausted · cancelled · failed` `[B §9.6]` — `answered` (D-344) only on the model's explicit `task(op=answer)` with the harness's check that the tree is still snapshot 0 and nothing with effects ran, never on a silent stop; a budget stop is `partial`, never `verified`. A `waiting_for_input` stop for acceptance carries a machine-readable stop code — `acceptance_decision` (something could not be verified) or `review_rejected` (a rejection stood after its rework round) — in the campaign state and the `campaign.finished` event (D-339). Unsupported repeated finalization requests trigger gap-directed recovery, not an endless gate that consumes the remaining budget `[B §9.6]`.

**Campaign finish receipt** `[C §13.5]` lists: requirements with status and blockers; acceptance items with kind, status, stamp, currency and log ids; changes split into `agent`, `by_run` and `pre_existing_user_changes` (untouched); `acceptance_surface_modified` with recorded reasons; checks run with verifier version and environment; `not_verified`; dead ends, decisions, ADR candidates, open items, pending amendments; routing decisions with reasons; budget by cache class and helper share; memory candidates proposed/admitted/queued; `highest_authorized_stage` — never "delivered" for a patch, never "verified" for plausible tests. Each acceptance line says how the item was accepted — `tested`, `reviewed` or `accepted` by whom (user or policy) and why — and an item accepted without verification is also listed in `not_verified` (D-342). **Provenance axis** (ASTROLABE 2.0 C2): each requirement line names who set it (`user` when its authority is a verbatim request, else `host`) and the items that check it (its own and the model's that strengthen it); each acceptance line names who created the check (`user`; `host` for a harness-derived suite or an authorized amendment; `model` only for `model(strengthens …)`), the command and receipt, the tree, the result and who took the residual risk (`runtime`, `user`, or `policy` for accept-unverified). The receipt and the `campaign.finished` event carry the summary class `independent`, `agent_test` or `unverified`: a requirement is independent when it has a declared (host or user) check and every one passed at the final tree; otherwise agent test when none of its checks failed and one of the model's passed — its items, or the model's own checks that name the requirement (`CHK-model-*`, C1a), which never make a requirement independent; otherwise unverified — a decider's acceptance is never a verification. A change since s0 to a required check's acceptance surface that no approving review covered (`acceptance_surface_unreviewed`: classified from its bytes at s0 and at the end, whichever cell made it) makes the run checks it touches the agent's evidence; a path back at its s0 text (line endings and blank lines aside) is no change, a renamed test reads as a deleted one (conservative), and a change that touches only checks without acceptance items (the full suite, quality gates) stays outside the class. An approving review clears such a change only for this contract and the path's current version (or, with no version recorded, this very candidate), and under human integrity approval only on the human path (D-320). Only a person's approval verifies independently (owner, 2026-10-03): a verdict says who reviewed — `reviewer` is `model` unless the host states `human` (the review cell's judge and a model a host answers with are both `model`) — so a `check:`/`review:` item a model approved is the agent's evidence, and a surface change only a model approved lets completion proceed as before while the run checks it touches stay the agent's evidence (`acceptance_surface_model_approved`); `verified_by` reads `runtime`, the judge's tier, `host_model` or `human`. Checks run name each check's command (argv and cwd) beside its origin, evidence kind and last outcome, so a host can declare an agent's check as the project's own (plan §4.4, C1b). A mandatory check outside the acceptance items and the campaign gate (the blast radius, the types of touched files) still red on the final tree — completed past on an `Open` item — makes the campaign's class `unverified` and is listed in `not_verified`; the requirements keep theirs, and a known red of an optional check changes no class (C1b). The campaign takes the worst class of its requirements (none: unverified); campaign-level gates accepted without verification stay in `not_verified`. The status stays `completed`.
<!-- end-source-section: 5.9 -->

