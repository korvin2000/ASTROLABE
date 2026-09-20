# Source metadata and historical synthesis

**ASTROLABE 1.0.1 · historical** · Owner: Historical editorial assessment.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** metadata, §0.2, §0.5, §0.7. **Read with:** [candidate-review](candidate-review.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F12](../../REVIEW.md#f12).

> Historical comparison/provenance retained for traceability, not a second build specification. Rankings and inherited claims about unavailable upstream sources are editorial; only the primary-source checks listed in the source register were reverified in this revision.

<!-- source-section: meta -->
<a id="sec-meta"></a>

```text
---
title: "ASTROLABE — a mid-weight SOTA coding-agent architecture synthesized from WAYPOINT (A), the evidence-guided runtime (B) and SEXTANT (C)"
version: 1.0.1-proposal
date: 2026-09-20
status: research proposal · not implemented · not benchmark-validated
baseline_architecture: "SOTA-CODING-AGENT_A.md (WAYPOINT) — campaign / cell / increment decomposition, contract–register–KB ownership split, verification scheduler"
kernel_and_system_donor: "SOTA-CODING-AGENT_C.md (SEXTANT) — planes, roles as configurations, shape policy, four-horizon coherence protocol, impact engine, cache-aware staleness, tool families, recovery ladder, finish receipt"
analytics_and_contracts_donor: "SOTA-CODING-AGENT_B.md — four identities, evidence states, event ordering and crash windows, cache-reuse economics, provider accounting, routing eligibility, completion outcomes, predeclared scoring and promotion policy, external-evidence corrections (SOTA-RESEARCH-NOTES.md)"
upstream_sources: "judje-2.md §7 (HELM) · judje-1.md §5/§8.2 · Qwen38analyze.md §4 · merged-best-harness-ideas.md · ideas-summary-mix.md · candidate-1…5.md"
citation_convention: "[A §x] [B §x] [C §x] = the three candidate proposals; [HELM §x] = judje-2.md §7; [J1 §x] = judje-1.md; [J2 §x] = judje-2.md; [QA §x] = Qwen38analyze.md; [MB §x] = merged-best-harness-ideas.md; [IM §x] = ideas-summary-mix.md; [RN Rxx] = SOTA-RESEARCH-NOTES.md; [C1]…[C5] = candidate-1…5.md. No inherited [Sxx] identifiers are used: the corpus contains three unrelated S-schemes."
provenance_labels: [BASELINE, FROM-A, FROM-B, FROM-C, MERGED, NEW, HYPOTHESIS, ESTIMATE]
caveat: "Every rating, budget, threshold, LOC figure and cost coefficient is an engineering estimate or a declared default, never a measured result. Rankings of the three candidates are editorial judgments made against the criteria in §22, with the rubric shown so that a different weighting can be applied."
---

# ASTROLABE

**An astrolabe fixes a position from several known references at once. This harness fixes what a model may act on — code, facts, notes, receipts, other workers' results — against one version registry, and fixes *where* it acts — one increment, one bounded cell, one hard landing gate — against a harness-owned contract.**

---
```
<!-- end-source-section: meta -->

<!-- source-section: 0.2 -->
<a id="sec-0-2"></a>

### 0.2 Verdict on the three candidates (full rubric in [§22](candidate-review.md#sec-22))

| Rank | Candidate | Weighted score ([§22.1](candidate-review.md#sec-22-1)) | One-line verdict |
|---|---|---|---|
| **1** | **A — WAYPOINT** | **8.5 / 10** | Best *architecture*: the increment-aligned cell is the single most consequential idea in the set for long, multi-session, refactor-heavy work, and its scheduler, guards and refactor mode directly serve the quality half of the objective. Fewest reversals needed to reach the final design. |
| 2 | C — SEXTANT | 8.45 / 10 | Best *specification*: the most complete and buildable document, the best cache/staleness economics, the impact engine and the coherence protocol. Loses only on the missing increment boundary, a heavier per-turn anchor and a wide tool surface. A near tie with A; a rubric that weights implementability above continuity flips the order. |
| 3 | B — evidence-guided runtime | 7.5 / 10 | Best *analysis*: the only candidate that verified its external evidence, that showed token reduction and billed cost can move in opposite directions, and that predeclared a scoring and promotion policy. Least concrete at the cell-kernel level: B specifies a render order, completion assessment and initial policies, but not A/C’s detailed STATE, gauge and rendered turn. |

The user's hypothesis — A and C architecturally strong, B analytically strong — is confirmed by this review. The one qualification: B is not *architecturally* weak in what it commits to; it is under-specified at the kernel level, which for a proposal that must be "practically feasible" is the decisive gap.
<!-- end-source-section: 0.2 -->

<!-- source-section: 0.5 -->
<a id="sec-0-5"></a>

### 0.5 What is new relative to A ∪ B ∪ C

| # | Contribution | Why none of the three had it |
|---|---|---|
| N1 | **Five-horizon coherence**: receipts join C's four-horizon protocol through A's input closures, so one version registry invalidates reads, facts, notes, receipts and delegated results with one rule | C's receipts go stale on *any* tree change (coarse); A's closures were a scheduler feature disconnected from the coherence protocol; B named applicability but not the mechanism |
| N2 | **Cell termination as a rebuild reason**: C's one-rebuild-four-uses mechanism gains a fifth use — cell end / continuation — so A's campaign layer and C's kernel share one implementation of "start from validated state" | A described compile-per-cell and pressure rebuild as separate paths; C had no increment boundary |
| N3 | **Cache-preserving boundary invariant** with a break-even estimate: `[S][R]` byte-stable across cells per role and profile, `[K]` stable within a cell, eviction only in batches, stale reads marked now and stubbed at the batch — derived from B's cache-reuse arithmetic applied to A's boundaries with C's staleness policy | B's arithmetic was an economics section, not a design rule; A adopted HELM's cache-hostile immediate stub; C had no cell |
| N4 | **Deterministic boundary pre-compilation**: while a cell's last slow checks run, the compiler pre-builds the next increment's `[K]` (no model call) and discards it if the stamp moves | Session latency at boundaries was A's acknowledged cost and nobody hid it |
| N5 | **Decomposition calibration prior**: per-repository sizing statistics (turns per increment, overrun rate by files touched) flow from telemetry into the KB and into the plan cell's context, closing A's load-bearing assumption with data | A listed decomposition quality as its top risk and measured it; no one fed the measurement back |
| N6 | **Test-integrity classifier rendered as the acceptance-surface line and adjudicated against the original obligation** — A's detector, C's rendering, B's rule that the judge sees the pre-change obligation | Three partial mechanisms in three documents |
| N7 | **Refusal-based routing**: A's never-cheap list + C's risk floor + B's eligible/affordable split with "narrow or checkpoint, never clamp" | C clamped the tier to the budget; A had no unaffordable branch; B had no function table |
| N8 | **Two review scopes**: per-increment review on contract touch (A/C) plus a campaign-level structural review of the final diff against the contract and a fixed maintainability rubric (B [§6.4](../context/continuity.md#sec-6-4) emphasis) | A/C reviewed increments; B reviewed designs; neither made the campaign-level architectural review a gate for S2+ campaigns |
| N9 | **Decision probes**: `decision.add` carries an optional cheap falsifying probe (B's decision packet) that the stall gate can suggest running | B described decision packets in prose; A/C had Decisions without a probe field |
| N10 | **Unified disagreement ledger** ([§20.2](decisions.md#sec-20-2)): every A/B/C conflict with the adopted resolution and its ablation arm | The three documents each had a rejection table against the *sources*, not against each other |
<!-- end-source-section: 0.5 -->

<!-- source-section: 0.7 -->
<a id="sec-0-7"></a>

### 0.7 Reading map

| Need | Read |
|---|---|
| Ranking of A, B, C with the rubric, per-candidate pros and cons | [§22](candidate-review.md#sec-22) |
| Where each mechanism came from | [§0.3](traceability.md#sec-0-3), [§23](traceability.md#sec-23) |
| Objective, invariants, failure model, laws | [§1](../architecture/principles.md#sec-1)–[§2](../architecture/principles.md#sec-2) |
| Controller, planes, identities, roles, shapes, pseudocode | [§3](../architecture/components.md#sec-3) |
| Contract, graph, evidence store, coherence protocol, KB, workspace | [§4](../state/contracts.md#sec-4) |
| The cell: layout, register, Workset, tools, gates, residency, rebuild, packets | [§5](../runtime/context-layout.md#sec-5) |
| Context compilation between cells, pre-compilation, calibration prior | [§6](../context/compiler.md#sec-6) |
| Repository understanding, impact engine | [§7](../repository/navigation.md#sec-7) |
| Verification scheduler, ladder, guards, exit gate, review, refactor mode | [§8](../verification/scheduler.md#sec-8) |
| Editing paths | [§9](../runtime/workspace-editing.md#sec-9) |
| Delegation, routing, learning, recovery, safety, platform | [§10](../operations/delegation.md#sec-10)–[§15](../platform/adapters.md#sec-15) |
| Economics, defaults, implementation plan, evaluation | [§16](../economics/costs.md#sec-16)–[§19](../evaluation/method.md#sec-19) |
| Rejections and the A/B/C disagreement ledger | [§20](decisions.md#sec-20) |
| Risks, traceability, glossary, kernel contract, rendered turn | [§21](risks.md#sec-21), [§23](traceability.md#sec-23), Appendices |

---
<!-- end-source-section: 0.7 -->

