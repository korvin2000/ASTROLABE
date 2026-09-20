# Task contract, requirement graph and ledger

**ASTROLABE 1.0.1 · specification** · Owner: Campaign controller.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §4, §4.1, §4.2. **Read with:** [evidence-coherence](evidence-coherence.md) · [acceptance-review](../verification/acceptance-review.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F01](../../REVIEW.md#f01), [F08](../../REVIEW.md#f08).

<!-- source-section: 4 -->
<a id="sec-4"></a>

## 4. The durable layer (campaign state)

Everything in this section lives outside the source tree (`.astrolabe/` beside the repo, or a user cache keyed by repo hash) so that its own updates never disturb workspace stamps `[J1 §7.1]`. Nothing here is reachable through `edit`; the model reaches it only through `state` and `task` ops the harness validates. Storage layout: `state.sqlite` (canonical structured records, event ordering), `blobs/<digest>` (captured bytes, preimages, diffs, logs), `kb/` ([§4.5](../knowledge/records.md#sec-4-5)), `exports/` (derived human-readable views), `indexes/` (disposable), `candidates/` (worktrees or snapshot refs), `campaigns/` (frozen evaluation manifests). The database is canonical for structured records; Markdown exports are derived, never competing authorities `[B §4.1]`. This includes contract authority, runtime progress, KB record revisions and usage counters: the examples below are joined read projections, not separately writable copies. KB Markdown files are immutable exported views; KB search indexes are rebuildable, not a second authority. The curator is the sole KB publisher, the verifier/integrator owns acceptance, and the controller alone commits resulting ledger transitions.
<!-- end-source-section: 4 -->

<!-- source-section: 4.1 -->
<a id="sec-4-1"></a>

### 4.1 Task Contract `[FROM-A §4.1 + C §5.1 acceptance kinds and origins + B §5.2 authority references]`

```yaml
contract:
  work_id: W-0042                       # stable across attempts and sessions
  version: 3                            # increments only on authorized amendment
  attempt_id: a2                        # current attempt; failed attempts are preserved, never overwritten
  mode: interactive | autonomous
  shape: S2                             # selected by policy, logged with inputs
  request:                              # verbatim, append-only; grows by user amendments
    - {id: U1, at: <ts>, text: "Add idempotency-key handling to POST /payments; public API unchanged."}
    - {id: U2, at: <ts>, text: "Also cover the retry path."}
  requirements:
    - id: R1
      text: "idempotency key stored and checked per merchant"
      acceptance: [AC-1]
      depends_on: []
      authority_ref: U1
      status: verified                  # pending | in_progress | verified | blocked — harness-derived, never model-written
  acceptance:
    - {id: AC-1, kind: run,    cmd: "pytest tests/payments -q",                 origin: user,           last: {receipt: rcpt-19, stamp: s57, current: true}}
    - {id: AC-2, kind: check,  text: "no public signature change in src/api/",  origin: user,           evidence: "#44"}
    - {id: AC-3, kind: review, text: "retry semantics cannot duplicate side effects", origin: user,     signed_by: null}
    - {id: AC-4, kind: run,    cmd: "pytest -k idempot",                         origin: model, strengthens: R1}   # the model may only ADD
  constraints:  [{id: C1, text: "do not change the refund flow", authority: user}, {id: C2, text: "no new runtime dependencies", authority: rules-file}]
  exclusions:   ["refund flow", "billing UI"]
  contracts_touched: ["CON-payments-api@7"]         # from impact pre-scan + plan cell; always compiled in
  scope: {write_paths: ["src/pay/", "tests/payments/"], protected_paths: ["migrations/", ".github/"]}
  budget: {cells: 12, turns_per_cell: 40, tokens: 2_500_000, cost: "<user>", attempts: 2,
           reserve: {verification: 0.15, recovery_and_persist: 0.05}}
  authorization: {ladder_ceiling: local_commit, d_class: ask, capability_set: "workspace-local-test-only"}
  risk: {blast_radius: 2, reversibility: easy, contract_touch: true}
  amendments_pending: [{by: model, cell: cell-8, change: "AC-1 cmd → … -k 'not slow'", reason: "slow suite needs a live DB"}]
```

**Acceptance kinds.** `run:` executed by the harness, green only with a *current* stamp; `check:` a claim needing an evidence `#id` (diff, `refs` result, run) recorded in STATE; `review:` requires the judge or a human `[C §5.1]`. A green acceptance item is a **regression obligation**: re-run at campaign end and whenever the impact engine says its inputs moved `[IM §4.2]`.

**Origins and amendments.** Every acceptance item carries `origin ∈ {user, harness, model(strengthens), amended@vN}`. The plan cell proposes missing criteria; interactive mode approves them, autonomous mode freezes them as `model`-origin with the receipt listing them. Thereafter the model may **add** items (`strengthens:`) and may **propose** narrowing or removing an item through `amend.propose`; such a proposal is `pending` until the user (interactive) or a preconfigured policy resolves it — and the policy never auto-accepts a weakening `[A §4.1; C §5.1]`. A user message may amend anything; the harness applies it because the authority is the message, stored verbatim. A parent model’s answer to a child is not a user amendment and cannot expand authority. A factual answer that leaves requirements and permissions unchanged is recorded as evidence without inventing a new authority revision. This closes F1 and F17 at the data model.

**Auto-derivation for S0.** When the request names no acceptance, the controller sniffs the test command from the atlas and inserts `AC-1: run: <suite> (origin: harness, scope: touched)`; the model must still state goal-level acceptance in its first register patch or ask one question `[HELM §7.3; A §4.1]`.
<!-- end-source-section: 4.1 -->

<!-- source-section: 4.2 -->
<a id="sec-4-2"></a>

### 4.2 Requirement graph, increments and the ledger `[FROM-A §4.2 + B §6.1 + IM §4.2]`

```yaml
graph:
  increments:
    - id: I2
      requirement_ids: [R2]
      title: "thread ctx through handlers"
      accept: [AC-1, AC-4]                 # subset of contract acceptance + model-added per-step accept
      depends_on: [I1]
      write_scope: ["src/handlers/", "src/cli/main.py", "tests/payments/"]
      expected_files: 4
      risk: {blast_radius: 2, reversibility: easy}
      red_ok_until: null | increment_end   # refactor mode (§8.9)
      produces: artifact | resolves(Q1)     # every node yields a candidate artifact or resolves a named uncertainty (B §6.1)
      status: verified | in_progress | pending | blocked | cancelled(reason)
      cells: [cell-7, cell-8]
      sizing: {turns: 31, continuations: 1, rebuilds: 0}      # telemetry, feeds the calibration prior (§6.7)
  regression_obligations: [I1]
  ownership_map: {}                        # S3 only: paths → increment
ledger:                                    # harness-derived, never model-written
  R1: {status: verified, evidence: [rcpt-19], stamp_valid: true}
  R2: {status: in_progress}
```

The **plan cell** (first cell of S1+) proposes the increments; the controller stores them and validates that every requirement is covered, that each increment has ≥1 executable `accept:` or an explicit `check:` with a named evidence kind, that each node produces an artifact or resolves a named uncertainty ("think more" is not a node) `[B §6.1]`, and that dependency cycles become one joint increment or an explicit planning conflict rather than an unreachable frontier `[B §6.1]`. Selection is deterministic over the ready frontier; the model may reorder within the frontier through its register but cannot mark anything verified — only receipts do. An increment that exceeds one cell is *continued* with carry-forward, not restarted; an increment that turns out to be wrong is cancelled with a reason and replaced; cancellations are kept `[HELM R5]`. Repository import cycles do not dictate task order; source coupling is distinct from a prerequisite that truly requires an earlier accepted artifact `[B §6.1]`.

**Decision packets** `[FROM-B §6.1]`: an expensive or consequential design choice is recorded in STATE as `decision.add(text, because, rejected, probe?)` and, when it crosses a boundary, promoted to an ADR candidate. The optional `probe` names a cheap falsifying check; the stall gate may suggest running it ([§5.6](../runtime/gates-termination.md#sec-5-6)). Private reasoning is not serialized — the artifact is the decision and the evidence needed to revisit it.
<!-- end-source-section: 4.2 -->

