# Evaluation method, score and promotion

**ASTROLABE 1.0.1 · evaluation** · Owner: Offline evaluation runner.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §19, §19.1, §19.2, §19.4, §19.5, §19.6. **Read with:** [fixtures](fixtures.md) · [costs](../economics/costs.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F05](../../REVIEW.md#f05), [F10](../../REVIEW.md#f10).

> These are future runtime/evaluation requirements. This documentation revision did not execute an agent, test suite or benchmark.

<!-- source-section: 19 -->
<a id="sec-19"></a>

## 19. Evaluation and falsifiability
<!-- end-source-section: 19 -->

<!-- source-section: 19.1 -->
<a id="sec-19-1"></a>

### 19.1 Three different things to validate, and the comparators `[B §15.1, §15.3; A §19.1; C §16.2]`

1. **Runtime correctness**: processes, mutations, evidence identity, budget accounting, permissions and recovery behave as specified (fixtures, [§19.3](fixtures.md#sec-19-3)).
2. **Solver quality**: complete user tasks are solved coherently, including hidden/regression behaviour and maintainability.
3. **Architecture value**: the assembled mechanisms improve the declared objective against credible controls under matched budgets.

A green fixture suite does not prove better coding; a benchmark score does not prove crash recovery. Comparators, run as isolated additions before combinations, with model strength, tools, permissions, environment, check access and total budgets held fixed:

| Variant | Composition | Isolates |
|---|---|---|
| **B0** | plain single-model tool loop (read/write/bash), append-only transcript, summarise-when-full, same checks and authority | the bar everything must clear (mini-SWE-agent class) |
| **B-HELM** | HELM as specified in judje-2 [§7](../repository/navigation.md#sec-7), with J1's corrections | what the cell's changes add |
| **B1 = S0** | ASTROLABE cell alone (contract, registers, coherence, tools, checker, exit gate) | the deterministic execution core |
| **B2 = S1** | + campaign controller, increments, compiler, seeds, KB, role switching | context continuity and the increment boundary |
| **B3 = S2** | + probe/review cells, routing with refusal, escalation, capsule repair, alternative attempts | independent contexts and routing without a standing team |
| **B4 = S3** | + parallel writers, ownership, integrator | concurrency itself |
| **B5** | + generated tools, procedural learning, learned routing corrector, async checkers, dense retrieval | the most experimental extensions |
| **Target** | chosen components composed, with frozen offline improvements | the complete mid-weight architecture |
<!-- end-source-section: 19.1 -->

<!-- source-section: 19.2 -->
<a id="sec-19-2"></a>

### 19.2 Workload and evaluation integrity `[B §15.2; A §19.2; C §16.3; IM §13.1]`

Strata (complex strata weighted; at least half of workload weight on cross-module, migration and long-horizon tasks): local bug fix · cross-module defect · feature with contract change · API/configuration migration · 40-file mechanical refactor · behaviour-preserving structural refactor · long failure log · ambiguous contract where the correct answer is `blocked` · pre-existing dirty tree with failing tests · forced context pressure · multi-session continuation · task requiring a probe · parallelizable feature work · repeated tasks on one repository (memory warm vs cold). Repositories ≥ 50K LOC, several languages, at least one monorepo; public subsets (SWE-bench Verified, Terminal-Bench) supplement, never replace, the internal suite.

Integrity: reconstruct historical tasks' actual starting environments and validate both the failing condition and the reference repair; remove answer-bearing patches, notes and metadata; hidden acceptance stays outside the solver's workspace; development, policy-selection and final partitions split by repository or task family/time; mutable memory reset for cold-start comparisons, frozen and equally available for warm-memory runs; a repeatedly consulted selection set is no longer a holdout. Start with a pilot to estimate variance, then size the confirmatory campaign for the smallest effect that would justify the component; repeat stochastic trials; randomize or interleave baseline and candidate runs; report paired uncertainty clustered by repository or task, never treating repeated runs as independent tasks `[B §15.2]`.
<!-- end-source-section: 19.2 -->

<!-- source-section: 19.4 -->
<a id="sec-19-4"></a>

### 19.4 Metrics and the predeclared score `[B §15.4; A §19.4; C §16.3; IM §13.3]`

**Quality**: complete acceptance rate per stratum, complex-stratum acceptance, all-trials reliability, escaped regressions, structural quality (rubric on held-out diffs), human interventions by reason. **Economy**: billed cost per accepted task by cache class, tokens by class, round trips per verified change, repeated-read rate, stale-evidence incidents, overflow/rebuild counts, continuations per increment, rebuilds per cell, boundary cost share, pre-compilation hit rate, probe/review/helper cost share, `[A]` and STATE upkeep tokens, p50/p95 latency and critical-path wall clock. **Invariants, must be zero in every configuration**: ordinary anchored edits to unseen content (declared transforms are separately audited under [§9.2](../runtime/workspace-editing.md#sec-9-2)); destructive missteps; silent acceptance changes; stale bodies served as current; unauthorized-stage publications; late superseded results merged; false-green incidents. **Diagnostics**: stale edit rejections, test-integrity flags and outcomes, retrieval misses, injection usefulness, routing calibration, handoff loss (judge-detected missing context per packet), MAST-tagged failure distribution.

```text
Q  = 100 · Σ_s w_s · accepted_trials_s / total_trials_s
Cost_s = total_online_cost_s / accepted_trials_s     # includes helpers, retries, review and integration
E_s = 100 · clamp((Cost_high_s − Cost_s) / (Cost_high_s − Cost_low_s), 0, 1); E = Σ_s w_s · E_s
T_s = (Σ input_tokens_s + Σ output_tokens_s) / accepted_trials_s  # retained token-volume diagnostic, not E
eligible_score = 0.60·Q + 0.40·E        only among configurations passing the quality floors and invariant checks
```

Weights non-negative and summing to one; at least one trial per weighted stratum; `0 ≤ Cost_low_s < Cost_high_s` in one declared currency, fixed from the pilot and frozen; missing billing remains unknown and blocks an economic promotion claim; a stratum with no accepted trial has `E_s = 0` and fails its floor; one trial is one complete task including permitted internal retries; cached input counts in token volume while its monetary treatment is reported separately; tokenizer differences limit cross-model comparability, so provider-specific raw usage and money are reported beside the score `[B §15.4]`. Online solver cost is reported separately from one-off index/memory construction, calibration and harness search, with the accepted-task volume at which an investment repays itself `[B §15.4]`.
<!-- end-source-section: 19.4 -->

<!-- source-section: 19.5 -->
<a id="sec-19-5"></a>

### 19.5 Ablations (one at a time, model constant) `[A §19.5 ∪ C §16.5 ∪ B §15.3]`

Cell boundaries vs HELM pressure rebuild · workset seeds on/off · Δ+absolute vs delta-only · mark-then-stub vs immediate stub · `R_max` early stubbing · batched vs pressure-only eviction inside short cells · contract/STATE split vs STATE-only · conditional STATE ops · gauge · impact nudge · sync checker vs none vs async watchers · blast radius vs package tests vs full suite · closures with reuse proofs vs stamp-coarse invalidation · reserve on/off · transform path vs anchored-only on the 40-file refactor · test-integrity guard on/off (with injected weakening) · refactor mode on/off · boundary pre-compilation on/off · calibration prior on/off · KB injection off / frozen / live · notes in `[R]` vs `[A]` only · role-only vs task/dependency-weighted retrieval · behaviour maps on/off · skills module filtering vs whole skills · probe cells vs in-window exploration · review at increment scope only vs both scopes vs none · judge same-context vs fresh vs fresh + symmetric evidence · function routing with refusal vs all-high vs clamped · capsule repair vs kernel-only vs deterministic-only · alternative attempt vs refinement · S0 vs S1 vs S2 on matched strata · sequential vs S3 under equal resources · language-service adapter on/off · dense retrieval on/off after measured lexical misses.
<!-- end-source-section: 19.5 -->

<!-- source-section: 19.6 -->
<a id="sec-19-6"></a>

### 19.6 Promotion policy and what would falsify the central claims `[B §15.5; MB §16.6; A §19.6; C §16.6]`

**Promotion** (declared before results): a mechanism ships enabled only if, on paired tasks under equal budgets, it does not reduce complex-stratum acceptance and reduces billed cost per accepted task, or raises acceptance at ≤ equal cost (pre-set default: ≥ baseline pass rate at ≤ 60 % of baseline cost, or higher pass rate at equal cost — revisable); no serious regression in the validated set; no invariant violation in fixtures; a one-sided paired confidence bound above a −2 percentage-point acceptance margin in both overall and complex strata (a governance choice, not a paper-derived fact); cost and latency ceilings met; candidates frozen before final testing with multiple-selection accounted for. Inconclusive evidence keeps the baseline; a failed promotion is useful research; required runtime-correctness controls are never disabled to improve a token score.

**Falsification.** The campaign/cell decomposition is wrong if, at equal model strength and budget, B-HELM matches B2 on multi-session and refactor strata with no more lost constraints — decomposition overhead and re-orientation cost exceed what bounded, verified boundaries save. The cache-preserving invariant is wrong if billed cost per accepted task does not fall relative to immediate-stub and per-turn-eviction arms. The Workset is wrong if region-seen rejections cost more turns than the unseen-edit failures they prevent. The scheduler with closures is wrong if fixed step-boundary checks reach the same false-green rate at lower cost. The impact nudge is wrong if it does not reduce invented-interface repairs. Function routing with refusal is wrong if all-high is cheaper per accepted task on the complex strata. Each has a measurable arm above.

---
<!-- end-source-section: 19.6 -->

