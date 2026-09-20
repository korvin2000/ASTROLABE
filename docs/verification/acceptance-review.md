# Scope, acceptance and independent review

**ASTROLABE 1.0.1 · specification** · Owner: Verifier / controller / review cell.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §8.6, §8.7, §8.8. **Read with:** [contracts](../state/contracts.md) · [delegation](../operations/delegation.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F01](../../REVIEW.md#f01).

<!-- source-section: 8.6 -->
<a id="sec-8-6"></a>

### 8.6 Scope guard and test-integrity guard `[A §8.6 classifier + C §9.7 rendering + B §9.1 original obligation — NEW N6]`

- **Scope guard**: the diff of each edit is classified against `increment.write_scope` and `contract.scope`. Outside the increment but inside the contract: warning once, justification on repeat. Outside the contract: rejected until an authorized amendment has been approved and committed to the contract; a pending proposal grants nothing. Revalidate the action against that committed version and the executor’s capability ceiling before dispatch. Protected paths are D-class.
- **Test-integrity guard**: a deterministic per-language classifier over the diff detects (a) deleted or renamed test functions/files, (b) weakened assertions (`assert x == y` → `assert x`, widened tolerances), (c) added skip/xfail/only markers and `.skip` calls, (d) snapshot/golden updates, (e) CI/config changes that alter which checks run (`pytest.ini`, `jest.config.*`, `conftest.py`), (f) edits to acceptance commands. Each detection is rendered as the **acceptance-surface line** in the edit result and in the finish receipt under `acceptance_surface_modified` with the worker's recorded reason. A detection that *weakens* an existing required check forces a review cell (or authorized human review), even inside the intended write scope, and the judge receives the **original obligation** (the pre-change test or assertion) beside the diff, so that deleting a failure can never satisfy its behaviour requirement `[B §9.1; MB §15.2]`. The guard is heuristic and says so; it exists to make weakening *visible*, not to forbid legitimate test maintenance.
<!-- end-source-section: 8.6 -->

<!-- source-section: 8.7 -->
<a id="sec-8-7"></a>

### 8.7 Hard exit gate `[A §8.7; C §9.6; C3 §5]`

A cell's completion proposal is accepted only if: every `run:` acceptance item of the increment has a green receipt whose applicability is current; every `check:` item has an evidence reference the reviewer or user accepted; every `review:` item is signed by the judge or a human; no verify line is red without an `Open` entry; no `[ ]` or `[>]` step remains without completion, cancellation or an explicit non-completed disposition; test-integrity flags are justified; no impact nudge is unresolved for a changed public definition. The model may request `state(blocked)` / `task.ask` with evidence instead of completion; runtime budget, cancellation and process-wait outcomes also remain explicit non-completed exits ([§5.9](../runtime/gates-termination.md#sec-5-9)). Only the **verifier** accepts completion, against the approved contract, with a receipt bound to `base_stamp`, `patch_hash`, `resulting_stamp` and environment; "the worker said done" is never the condition. The campaign gate additionally requires the full suite green (or its failures in the pre-existing ledger), all contract acceptance at the final stamp, and campaign-level review whenever the precise [§8.8](#sec-8-8) predicate requires it. Partial completion is a valid terminal outcome; nothing loops to manufacture green `[J1 §7.5]`. Recording a failure in `Open` does not waive a required acceptance item or unresolved contract violation. A `check:` reference must have a recorded assessment of the stated criterion, not merely exist. Required reviews must approve before the controller closes the increment; their assessments bind the contract version, reviewed candidate/patch, criterion and relevant evidence versions. Changed dependencies invalidate approval; unassessed freshness is `unknown`. Incidental red diagnostics may be tracked in `Open`, but cannot hide a required failure.
<!-- end-source-section: 8.7 -->

<!-- source-section: 8.8 -->
<a id="sec-8-8"></a>

### 8.8 Review cells and the judge protocol — two scopes `[A §8.8 + C §9.5 + B §9.4 — NEW N8]`

**Increment scope** fires on the risk floor, a contract/ADR touch, cheap-tier output, a test-integrity flag or a `review:` item. **Campaign scope** fires at the end of every S2+ campaign with ≥ 3 increments or any refactor-mode campaign: this is the precise predicate `(shape >= S2 and increment_count >= 3) or refactor_mode`, additionally including explicitly required campaign reviews; summaries elsewhere use this rule. The judge receives the *whole* final diff, the contract, all receipts and the structural rubric (module boundaries, compatibility, reuse of existing mechanisms, comprehensibility, unnecessary abstraction, duplication, dead code, error handling) and answers whether the result fits the existing architecture, duplicates a subsystem, changes a public contract unintentionally, or replaces comprehensible code with unnecessary abstraction `[B §6.4]`.

Inputs (evidence packet, never the proposer's transcript by default): contract slice, the diff, receipts with parsed counts, CON/ADR notes touching the paths, test-integrity flags with original obligations, the pre-existing ledger, coverage report, rubric. Tools: `look` (read-only), `kb.search`, `verify(tests)` in an **isolated copy** of the candidate tree — a coding judge keeps its execution tools `[C §9.5; IM §9.4]`. Budget ≤ 10 `look` calls / 30K tokens for increment scope, 60K for campaign scope `[ESTIMATE]`.

```yaml
verdict: approve | revise | reject | insufficient_evidence | escalate
findings: [{severity: blocker|major|minor|nit, location: path:line@hash, issue, suggested_fix, kind: correctness|contract|quality|test-integrity}]
coverage: {files_reviewed, ranges, unread: []}          # from telemetry
contract_violations: []   confidence: 0.0–1.0
```

Rules: score against acceptance criteria first, taste second; executable checks outrank opinion; `insufficient_evidence` with a named missing criterion is a correct outcome (fixture: two migrations judged without the rollback requirement); findings ≥ major become `Open` items in the implementing cell's next register and `PIT` candidates; competing proposals are presented symmetrically in randomized order with equal length budgets; the judge is calibrated against labelled fixtures; ties and `escalate` go to the extra-high tier or a human. Review can identify missing tests or design regressions; it cannot override a failed required check, and passing tests cannot erase a demonstrated unmet contract `[B §9.4]`. Independence means independence from the proposer's reasoning trail and completeness on requirements and evidence: a fresh judge needs evidence, not ignorance `[MB A5]`.
<!-- end-source-section: 8.8 -->

