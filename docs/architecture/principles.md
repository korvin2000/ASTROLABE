# Objective, invariants and laws

**ASTROLABE 1.0.1 · specification** · Owner: All components.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §1, §1.1, §1.2, §1.3, §1.4, §2. **Read with:** [components](components.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F02](../../REVIEW.md#f02), [F12](../../REVIEW.md#f12).

<!-- source-section: 1 -->
<a id="sec-1"></a>

## 1. Objective, workload and failure model
<!-- end-source-section: 1 -->

<!-- source-section: 1.1 -->
<a id="sec-1-1"></a>

### 1.1 The objective `[IM §1.2; A §1.1; B §3.1; C §1]`

```text
score = 0.60·Q + 0.40·E        ranks only among eligible configurations
eligible ⇔ predeclared quality floors, regression limits, authorization and required-behaviour coverage all hold
Q  = complete acceptance per stratum · complex-stratum acceptance · repeated-run (all-trials) reliability ·
     escaped regressions · maintainability under a fixed rubric
E  = end-to-end cost per accepted task (uncached input, cache read, cache write, output, helpers, judges, retries,
     extraction, tool seconds) · round trips per verified change · waste (rereads, overflow, rebuilds, stale-edit failures)
cost_per_accepted_task = total cost of all attempts, helpers, delegates and retries / accepted tasks   (undefined at zero)
```

Consequences adopted from all three candidates: **sufficiency outranks brevity** (a missing caller contract costs more than 2K tokens of relevant source `[MB A1]`); **every helper counts** (probe, review, extraction, verification, failed cells and pre-compilation all sit in the numerator `[B §12.3]`); **whole-task success dominates partial progress** `[IM §13.3]`; **quality is an eligibility constraint before it is a score** — a cheaper configuration that drops difficult requirements is ineligible regardless of aggregate `[B §3.1]`; and **tokens are not money** — the score's economy term is reported in provider-priced cost with the raw usage beside it, because prompt reduction and invoice reduction can diverge ([§16.2](../economics/costs.md#sec-16-2)) `[B §7.5]`.
<!-- end-source-section: 1.1 -->

<!-- source-section: 1.2 -->
<a id="sec-1-2"></a>

### 1.2 Target workload `[A §1.2; B §3.2]`

Medium and large repositories (10⁴–10⁶ LOC, monorepos included), several languages, dirty trees, expensive builds. Repository size matters through dependency complexity, build cost and localization difficulty; LOC is an evaluation stratum, not an algorithmic switch `[B §1.1]`. Interactive and autonomous operation are both first-class; the difference is a policy on who answers `blocked`.

| Task class | Representative difficulty | Expected shape and mechanisms |
|---|---|---|
| Local correction | Clear behaviour, small surface, strong tests | S0 · one cell · auto-derived acceptance · inline checks |
| Cross-module defect | Failure far from cause; incomplete contracts; several plausible causes | S1/S2 · behaviour map · missing-complement retrieval · decision probes · alternative attempt · review if contract-touching |
| Architectural feature | New boundary or algorithm interacting with subsystems | S2 · plan cell with decision packets · CON/ADR notes · staged increments · campaign-level review |
| Broad refactor / migration | Many edits, compatibility obligations, transitional red states | S1/S2 · refactor mode · scripted transforms · `red_ok_until` · blast closure · equivalence evidence |
| Long investigation | Several windows, expensive builds, intermittent results | S1 · increments as hypotheses · probe cells · bg handles · negative-evidence ledger · resume |
| Separable implementation | Stable interfaces, disjoint write scopes | S3 · ownership map · worktrees · single integrator · combined re-verification |
<!-- end-source-section: 1.2 -->

<!-- source-section: 1.3 -->
<a id="sec-1-3"></a>

### 1.3 Non-negotiable invariants `[B §3.3 hardened with IM §3.4 and A L5–L8]`

1. The full user objective, its authorized amendments, constraints and exclusions outlive every plan, attempt, cell, provider session and rebuild, and are never rewritten by compaction.
2. A plan change cannot silently delete or weaken an acceptance obligation; supersession records its source, reason and authority.
3. Source identity (version), observed coverage (displayed range) and semantic understanding remain three distinct properties `[J1 §5.1]`.
4. Tool status, hashes, stamps, usage, process outcomes and capture limits are runtime facts, never model-authored fields.
5. A check supports only its recorded candidate, environment and actual scope; applicability to a later candidate is computed from closures, never assumed.
6. An unknown execution outcome is reconciled before any action that could duplicate an effect.
7. Every mutation has one owner; integration has one authority per destination workspace.
8. Memory and skills are fallible claims; retrieval relevance is separate from permission and instruction authority.
9. Context reduction never removes the only recoverable copy of required state or evidence without recording the loss.
10. Every child, retry, rebuild, review, verification and pre-compilation consumes the originating work's budget.
11. `completed` is a supported state; `waiting`, `blocked`, `budget_exhausted` and `cancelled` are distinct outcomes, never disguised as completion.
12. Harness changes are versioned and take effect only at attempt boundaries; a candidate harness never edits the evaluator, the acceptance, the accounting or its own promotion verdict.
<!-- end-source-section: 1.3 -->

<!-- source-section: 1.4 -->
<a id="sec-1-4"></a>

### 1.4 Unified failure taxonomy → mechanisms `[A §1.3 ∪ C §2 ∪ B §15.6, deduplicated]`

No mechanism enters the design without a named failure `[C1 §1]`. Twenty-eight failures cover the union of A's F1–F18, C's F1–F25 and the two failures only B names (false economy, evaluation contamination).

| ID | Failure | Primary mechanisms (section) |
|---|---|---|
| F1 | Goal drift, scope creep, silently narrowed requirements | Contract outside model authority; contract digest at the tail; user messages pinned; scope guard ([§4.1](../state/contracts.md#sec-4-1), [§5.1](../runtime/context-layout.md#sec-5-1), [§8.6](../verification/acceptance-review.md#sec-8-6)) |
| F2 | Edits against stale content | CAS on content hash with mandatory `expect`; Workset stale-drop and announcement ([§5.3](../runtime/register-workset.md#sec-5-3), [§9.1](../runtime/workspace-editing.md#sec-9-1)) |
| F3 | Acting on unseen code, invented interfaces | Region-seen precondition; KNOWN / NOT SEEN; harness-built atlas; **impact nudge** ([§5.3](../runtime/register-workset.md#sec-5-3), [§7.1](../repository/navigation.md#sec-7-1), [§7.4](../repository/navigation.md#sec-7-4)) |
| F4 | Hypotheses laundered into facts; early wrong decision compounded | h/v/x facts with evidence ids; conditional ops; Decisions with probes; Dead ends with scope and reopen ([§5.2](../runtime/register-workset.md#sec-5-2)) |
| F5 | Stall, rabbit hole, losing the place in the plan | Progress events; stall nudge; one cursor; ready frontier; probe cells ([§5.6](../runtime/gates-termination.md#sec-5-6), [§4.2](../state/contracts.md#sec-4-2), [§10.2](../operations/delegation.md#sec-10-2)) |
| F6 | Fear tax — over-confirmation because mistakes are expensive | Shadow ref per turn; guarded snapshot restore; per-edit revert; dirty-state protection ([§4.6](../runtime/workspace-editing.md#sec-4-6), [§9.3](../runtime/workspace-editing.md#sec-9-3)) |
| F7 | Loops, repeated equivalent actions, distributed doom loops | Loop gate; campaign-scoped failure fingerprints; global no-progress budget ([§5.6](../runtime/gates-termination.md#sec-5-6), [§13.2](../operations/recovery.md#sec-13-2)) |
| F8 | Premature or false completion | Executable acceptance; hard exit gate at current stamp; completion issued only by the verifier ([§8.7](../verification/acceptance-review.md#sec-8-7), [§5.9](../runtime/gates-termination.md#sec-5-9)) |
| F9 | Verification too late, too often, or unbound to state | Scheduler with triggers, closures, validity, reserve; verify-on-stop with reuse proof ([§8.1](../verification/scheduler.md#sec-8-1)) |
| F10 | Context bloat, attention decay, signal buried in noise | Cell boundaries at increments; bounded residency (`k`, `R_max`); shaped views; stubs + recall; register at the tail ([§5.1](../runtime/context-layout.md#sec-5-1), [§5.7](../runtime/residency-rebuild.md#sec-5-7), [§6](../context/compiler.md#sec-6)) |
| F11 | Prompt injection through repository, notes or tool content | Harness-owned delimiters; rules-file-only channel; executor-enforced capability ([§14.3](../platform/security.md#sec-14-3)) |
| F12 | Cross-session amnesia and cold-start tax | Atlas cache; KB with precision-gated injection; behaviour maps; workset seeds; calibration prior ([§7](../repository/navigation.md#sec-7), [§12](../knowledge/learning.md#sec-12), [§6.7](../context/continuity.md#sec-6-7)) |
| F13 | Coordination conflicts between concurrent workers | Decisions never in children; ownership map; single integrator; stale-result rejection ([§10.4](../operations/delegation.md#sec-10-4)) |
| F14 | Coverage overclaiming | Manifest and Workset produce coverage fields from telemetry; review reports unread scope ([§6.5](../context/continuity.md#sec-6-5), [§8.8](../verification/acceptance-review.md#sec-8-8)) |
| F15 | Unsafe retry after unknown outcome | Intent journal; reserve→dispatch→observe→persist→commit ordering; reconcile before retry ([§4.3](../state/evidence-coherence.md#sec-4-3), [§13.1](../operations/recovery.md#sec-13-1)) |
| F16 | Green that proves nothing (wrapper exit 0, stale green, nothing collected) | Status vocabulary; parsed counts; stamps; runners invoked directly ([§8.4](../verification/scheduler.md#sec-8-4)) |
| F17 | Acceptance quietly weakened to reach green | Contract amendments channel; **test-integrity classifier**; acceptance-surface line; judge sees the original obligation ([§8.6](../verification/acceptance-review.md#sec-8-6)) |
| F18 | Memory poisoning, stale advice | Admission queue; provenance and confidence; dependency invalidation; usage-aware pruning; versioned rollback ([§12](../knowledge/learning.md#sec-12)) |
| F19 | Hidden mutations by commands (formatters, codemods, installs) | Stamp diff after every run; Touched `(by run)`; invalidation of reads, facts, notes, receipts ([§5.4](../runtime/tools.md#sec-5-4), [§9.4](../runtime/workspace-editing.md#sec-9-4)) |
| F20 | Edit misfires and retry loops (whitespace, non-unique anchors) | Unique anchors with candidates; diff-since-expect; post-edit views ([§9.1](../runtime/workspace-editing.md#sec-9-1)) |
| F21 | Output-token waste | Typed STATE ops; one intent line per turn; patch cap; anchor rendered by the harness ([§5.2](../runtime/register-workset.md#sec-5-2), [§16.4](../economics/costs.md#sec-16-4)) |
| F22 | User's work damaged | Shadow ref; dirty-state record; guarded revert; permission ladder; no `reset`/`clean` ([§4.6](../runtime/workspace-editing.md#sec-4-6), [§14.2](../platform/security.md#sec-14-2)) |
| F23 | Over-isolation breaks integration (role silo hides a contract) | CON and GLOBAL notes always compiled in; `contracts_touched` mandatory in packets; retrieval-miss logging ([§6.1](../context/compiler.md#sec-6-1), [§6.3](../context/continuity.md#sec-6-3)) |
| F24 | Misrouting (cheap tier fails silently; expensive tier everywhere) | Function table with never-cheap list; risk floor; eligible/affordable refusal; escalation on verified failure; calibration ([§11](../operations/routing.md#sec-11)) |
| F25 | Judge bias or ignorance | Symmetric evidence packets; `insufficient_evidence`; executable checks outrank opinion; calibration fixtures ([§8.8](../verification/acceptance-review.md#sec-8-8)) |
| F26 | **False economy**: fewer prompt tokens, higher bill (cache reuse lost) or lower quality | Cache-preserving boundary invariant; batched eviction; economics by billed class; boundary break-even as a metric ([§16](../economics/costs.md#sec-16)) |
| F27 | **Evaluation contamination and self-certification** | Frozen harness per attempt; hidden acceptance outside the workspace; evaluator outside the mutation surface; held-out final set ([§19.2](../evaluation/method.md#sec-19-2), [§12.3](../knowledge/learning.md#sec-12-3)) |
| F28 | Minimalism becomes underspecification | P0 lifecycle controls (cancellation, leases, reconciliation, budgets, accounting) active in every shape ([§3.5](roles-shapes.md#sec-3-5)) |

---
<!-- end-source-section: 1.4 -->

<!-- source-section: 2 -->
<a id="sec-2"></a>

## 2. Laws

Twelve laws; each is enforced by code somewhere in [§3](components.md#sec-3)–[§15](../platform/adapters.md#sec-15). A law without an enforcing mechanism is a slogan and was cut `[A §2; C §3]`.

| # | Law | Enforced by |
|---|---|---|
| L1 | **Context is a cache, not a log.** Loads have widths, results have lifetimes, `evicted ⇒ refetchable ∨ noted`, the register is written back. `[HELM law 1; C3 §6.3]` | tool budgets; stubs + `recall`; register validator |
| L2 | **Signal on delta; state the absolute.** Window growth ∝ surprise, but every verification line carries current status, scope and version. `[J1 §5.3; QA §4]` | checker renderer ([§8.3](../verification/scheduler.md#sec-8-3)) |
| L3 | **Compile sufficient context, not merely short context.** Never silently drop an invariant to fit a budget: rescope, split, or raise the profile. `[MB A1]` | compiler `NEEDS_RESCOPING` ([§6.1](../context/compiler.md#sec-6-1)) |
| L4 | **The model decides what matters; the harness enforces that it decided** — and that it cannot act on stale, unseen or unverified state. `[HELM law 3]` | gates, CAS, region-seen, stamps |
| L5 | **What is not seen is labelled unseen, at every horizon.** Bodies exist only in live, version-matched Workset entries; facts, notes, receipts and delegated results whose anchors moved are labelled stale; absence claims carry scope and completeness. `[C law 5]` | version registry ([§4.4](../state/evidence-coherence.md#sec-4-4)) |
| L6 | **The contract outlives the conversation and is not the model's to weaken.** Verbatim request, constraints, exclusions and acceptance are harness-owned and versioned; the model proposes, the user or policy disposes. `[IM inv. 1, 10; J1 §7.5]` | contract store; amendments channel; coverage assertion |
| L7 | **Done is a receipt about a stamped candidate, matched to the claim.** A passing log from a previous candidate is evidence about that candidate; applicability to the current one is computed. `[MB §0.3; B §9.2]` | receipts, closures, exit gate |
| L8 | **A missing result is unknown, not absent.** An empty scoped search is not absence; an unreconciled action is not "did not run". `[C2; IM inv. 7]` | `complete` fields; reconcile protocol |
| L9 | **One owner per transformation and per decision.** Raw → shaped → stub; contract → increments → cells; decisions centralized, execution isolated, verification independent. `[IM §3.1; MB §0.4]` | ownership table ([§3.2](components.md#sec-3-2)) |
| L10 | **Relevance is not authorization.** Scopes, filters and role tags are retrieval hints; capability is enforced in the executor. `[MB A13]` | jail, classes, ceiling |
| L11 | **Repository is truth; registers and memory are claims; provider state is transient.** `[MB A14]` | KB validity; adapters; four quantities |
| L12 | **Every layer above one cell pays for itself, in money, under a predeclared gate.** `[MB A10; B §15.5]` | evaluation program ([§19](../evaluation/method.md#sec-19)) |

---
<!-- end-source-section: 2 -->

