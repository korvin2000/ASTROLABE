# Historical A/B/C comparison

**ASTROLABE 1.0.1 · historical** · Owner: Historical editorial assessment.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §22, §22.1, §22.2, §22.3, §22.4, §22.5, §22.6. **Read with:** [decisions](decisions.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F12](../../REVIEW.md#f12).

> Historical comparison/provenance retained for traceability, not a second build specification. Rankings and inherited claims about unavailable upstream sources are editorial; only the primary-source checks listed in the source register were reverified in this revision.

<!-- source-section: 22 -->
<a id="sec-22"></a>

## 22. Critical evaluation of the three candidates
<!-- end-source-section: 22 -->

<!-- source-section: 22.1 -->
<a id="sec-22-1"></a>

### 22.1 Rubric and scores

Criteria follow GOAL.md's balance — quality on complex work in mid/large repositories (0.30), token/context/session economy (0.20) — plus what a proposal must have to be worth building: analytical rigor and evidence (0.15), implementability as a build specification (0.20), and architectural coherence without over-engineering (0.15). Scores are editorial, 1–10; the weights are shown so that anyone who disagrees can re-weight. All three candidates were read in full and checked against the sources they cite ([§22.5](#sec-22-5)).

| Criterion (weight) | A — WAYPOINT | B — evidence-guided runtime | C — SEXTANT |
|---|---|---|---|
| Complex-task capability: continuity, verification depth, refactor support, large-repo orientation (0.30) | **9.0** — increment-aligned cells; scheduler with closures and reserve; refactor mode; transform path; test-integrity guard; probe/review kinds; carry-forward | 7.5 — every capability named and correctly constrained; evidence graph adds coherence; less mechanized (no scheduler, no register, no refactor mode) | 8.5 — impact engine; four-horizon coherence; judge with isolated tests; recovery ladder; alternative attempts; no increment boundary, no closures, no refactor mode |
| Token / context / session economy (0.20) | 8.5 — cells, seeds, Δ+absolute, transforms, verify-on-stop, five tools; but carried HELM's cache-hostile immediate stub | 7.5 — best economic *analysis* (cache-reuse arithmetic, four quantities, accounting); no layout, residency bounds or numbers to execute it | **8.5** — mark-then-stub, cache-aware scheduling, effort per call class, breakpoints; but `[A]` up to 3K uncached per turn over 80-turn units |
| Analytical rigor and evidence (0.15) | 7.5 — provenance labels, falsification statement, J1 audit applied; several ESTIMATEs without arithmetic | **9.5** — twelve primary sources with limits; provider docs checked; four identities; worked cost arithmetic; predeclared score, CI margins; adapter fixtures | 8.0 — formal coherence protocol; budget equation; honest effort estimate; 36 fixtures; fewer external checks |
| Implementability as a build specification (0.20) | 8.5 — modules with LOC, stages with gates, controller and cell pseudocode, rendered turn, defaults | 6.5 — roadmap, vertical slice and interface priorities are good; the cell-level STATE, gauge and detailed turn are less specified | **9.0** — the most complete spec: tool families with signatures, roles, shapes with a selection function, gates, defaults, appendices, LOC and effort |
| Coherence, originality, restraint (0.15) | **8.5** — the two-clock frame; ten well-argued NEW items; nothing to reverse | 7.0 — "shared evidence, separate authority" is a strong thesis; the graph is its own first risk; prose-heavy, few tables of mechanisms | 8.0 — planes and the "one X, four uses" unifications are elegant; ~30 operations and 25 failures verge on breadth for breadth's sake |
| **Weighted** | **8.50** | **7.53** | **8.45** |
<!-- end-source-section: 22.1 -->

<!-- source-section: 22.2 -->
<a id="sec-22-2"></a>

### 22.2 A — WAYPOINT: why it is the baseline

**Strengths.** (1) The campaign / cell / increment alignment is the one structural idea that addresses GOAL.md's hardest requirement — work that outlives a window or a session on a large codebase — *and* the economy requirement at the same time: a boundary chosen by semantic completion bounds the window, makes verification coherent, and makes resumption exact. HELM rebuilds under pressure at an arbitrary point; the merged dossier compiles per task but keeps a long supervisor transcript; A is the only candidate that says the boundary *is* the increment and treats pressure rebuild as a measured planning failure. (2) The contract / register / KB split with acceptance outside the model's write authority closes the "weaken the test to pass" hole at the data model, not by exhortation; C reached the same split independently ([§22.4](#sec-22-4)), which is strong evidence that it is right. (3) A mechanizes what the others name: a verification scheduler with input closures and a reserve; a deterministic test-integrity classifier; a scripted-transform path with diff receipts; a refactor mode with `red_ok_until` and equivalence evidence; cross-cell fact coherence; a function-based routing table with a never-cheap list. (4) It states what would falsify its central claim and instruments the load-bearing assumption (continuations per increment, rebuilds per cell). (5) By judje-2's own criterion for a baseline — *how much must be reversed to reach the final design* — nothing in A is reversed here except one inherited policy (D2) and one placement (D9); everything else is addition.

**Weaknesses.** (1) It carried HELM W2's immediate stale stub without noticing the cache cost C computes; on edit-heavy increments this alone can erase a good share of the boundary savings A argues for. (2) Its `[K]` size range (1–4K) is inconsistent with its own components (seeds ≤ 4K + notes ≤ 1.5K + contract slice + CON notes + carry-forward). (3) No impact nudge — A has `look(importers)` but nothing deterministic fires when a public definition changes with uninspected references; C's ~30-token gate is the cheapest anti-F3 mechanism in the set. (4) No alternative-attempt mechanism (only an escalation ladder), no effort-per-call-class, no cache-aware scheduling, no formal role table with tool masks, no explicit `attempt_id`. (5) Its economics section is an estimate without B's cache-reuse arithmetic; the boundary break-even in [§16.2](../economics/costs.md#sec-16-2) is not in A. (6) Delegation kinds are clean but the judge lacks C's isolated test copy.
<!-- end-source-section: 22.2 -->

<!-- source-section: 22.3 -->
<a id="sec-22-3"></a>

### 22.3 B — the evidence-guided runtime: why it is the analytical donor and not the baseline

**Strengths.** (1) It is the only candidate that verified its external evidence and recorded each paper's *limits* (research notes R01–R12): pool-based retrieval coverage is not a repair rate; SoL-Pi's efficiency stack lowers its score; Complexity Trap supports masking as a comparator, not summarization as a rule; scaling-agent results are workload-dependent; harness-evolution gains must be compared under matched budgets. Those corrections are load-bearing in [§20.1](decisions.md#sec-20-1) and [§12.3](../knowledge/learning.md#sec-12-3). (2) Its worked economics ([§7.5](../repository/navigation.md#sec-7-5)) is the single most important analytic result in the three documents: a selective context at 50 % cache reuse costs *more* than an accumulating one at 90 %. ASTROLABE turns that into a design invariant ([§16.2](../economics/costs.md#sec-16-2)) instead of leaving it as a warning. (3) Four identities; evidence states separated from authority and freshness; negative results with a bounded domain; the crash-window ordering of consequential actions; "a hash is not a lock"; check status vs applicability with a reuse proof; flaky-check triage; unsupported mutation kinds rejected explicitly; PID-reuse and process-group caveats; provider accounting without double counting; adapter acceptance fixtures; completion outcomes as distinct states; routing that refuses rather than clamps; a scoring convention with normalization, strata weights and a promotion policy with a paired one-sided bound; evaluation-integrity rules. Every one of these is adopted. (4) Its roadmap begins with a controlled research baseline and a vertical slice — the right order for a research project.

**Weaknesses.** (1) It does not specify the kernel: it supplies a render order ([§7.1](../repository/navigation.md#sec-7-1)), completion assessment (§9.6), capability contracts ([§8.1](../verification/scheduler.md#sec-8-1)) and initial policies (§11.6), but lacks A/C’s concrete STATE format, gauge and KNOWN/NOT SEEN rendering, full operation signatures and LOC estimates. "Tool count is an interface choice" and "the model-facing action set can be a few named tools or namespaced operations" hand the hardest design decisions to the implementer. A and C each give a builder a turn they can render; B does not. (2) It keeps a model-written semantic checkpoint as a legitimate stage. That is consistent with the Complexity Trap's "hybrid worth testing" and with the merged dossier's "compaction as explicit fallback", so it is not wrong — but A and C's stricter rule (STATE is the only model summary; two rebuilds ⇒ replan) is the safer default, and B's validation rules are preserved here for the ablation arm (D4). (3) Its "shared evidence graph" is presented as the main integration contribution and listed as its own first risk; the value it names (a discovered caller supplies context, extends check scope and invalidates a memory at once) is delivered in ASTROLABE by anchors, `depends_on` and closures over one version registry without a general ontology (D8). (4) Prose-heavy: most mechanisms are described in paragraphs rather than tables or schemas, which makes it harder to audit for completeness and harder to build from. (5) It has no worked turn, no diagram of the loop, and no failure taxonomy of its own.
<!-- end-source-section: 22.3 -->

<!-- source-section: 22.4 -->
<a id="sec-22-4"></a>

### 22.4 C — SEXTANT: why it is the specification donor and a near tie

**Strengths.** (1) It is the most complete build specification: planes with one owner per decision; a role table with tool masks, tier priors and duties; a deterministic `select_shape` with a per-shape activation table; `[S][R][T][A]` sizes with cache breakpoints; ten tool families with signatures; a normative error-policy table; sixteen gates; a budget equation; defaults; LOC per plane; a five-stage roadmap; 36 fixtures; a rejected-ideas table; a kernel contract the model reads (Appendix B); a rendered turn (Appendix A). (2) Three unifications are genuinely architectural and are taken whole: the four-horizon coherence protocol (one version registry, one rule); the impact engine with four consumers plus the impact nudge; one rebuild mechanism with four uses. (3) Cache-aware staleness (mark now, stub at the batch, 800-token threshold) is a real improvement over HELM W2, argued with the cache cost and given an ablation. (4) Effect classes verified after the fact (R reclassified to W by the stamp diff), the intent journal, `verify.baseline()`, acceptance kinds with origins, the Amendments channel, the judge's isolated test copy, the recovery ladder with the ≤ 100-token diagnosis invariant, alternative attempts through rebuild, effort per call class, cache-aware scheduling, MCP as mounts, and the finish receipt are all adopted. (5) Its effort estimate ("small team, two to three quarters") is the most honest in the set.

**Weaknesses.** (1) No increment boundary: in S0/S1 the kernel's own plan drives a long session with pressure rebuilds and role switches; the requirement graph exists but does not shape the window. C therefore pays A's cost (an `[A]` of up to 3K uncached tokens every turn for 80 turns) without A's benefit (a boundary that coincides with verification and checkpointing). (2) Receipts go stale on *any* tree change — coarse; an unrelated edit invalidates every green (D5). (3) `clamp(tier, budget)` can route hard work down to fit a budget; B's refusal rule is safer (D6). (4) ~30 operations is a lot for a surface that must beat bash per operation and whose selection quality degrades with count; C lists this as its own risk #7. (5) Acceptance-surface detection flags touched test files but does not classify *weakening*; A's classifier does (D10). (6) No refactor mode, no transform inventory or expected counts, no `red_ok_until`. (7) Auto-admission of memory at confidence ≤ 0.7 in autonomous mode is the most permissive of the three (D7). (8) Twenty-five failures and twelve laws are complete but partly redundant (F4/F10, F1/F13), which is why [§1.4](../architecture/principles.md#sec-1-4) deduplicates the union to twenty-eight with B's two additions.
<!-- end-source-section: 22.4 -->

<!-- source-section: 22.5 -->
<a id="sec-22-5"></a>

### 22.5 Faithfulness to the sources and shared blind spots

Spot checks against the corpus: Keel's risk formula, the `k·B̄` residency bound and the ρ ≈ 0.1 / 0.21·C coefficients cited by A and C exist as cited `[C1 §6, §7]`; HELM's immediate stale stub exists as C describes it and A carries it `[J2 §7.6]`; the merged dossier does call compaction an "explicit fallback", so B's position has support in the primary sources and A/C are stricter than the merged dossier `[MB §4.5]`; B's provider-accounting claims match the documentation it cites; judje-1's audit (`k·B` is not a bound with multiple results per turn; batched eviction ties append-only only at `C_e ≈ 0.47·C_a`) is honored by all three through `R_max` and by B's arithmetic.

Blind spots shared by all three, carried here as open questions rather than solved: none quantifies the anti-drift value of the uncached anchor against its cost beyond "measure it" ([§21](risks.md#sec-21) #2); none specifies interactive-mode ergonomics ([§21](risks.md#sec-21) #19); all depend on judge calibration fixtures that do not yet exist; all inherit the corpus's untested assumption that models keep `h/v/x` facts current without heavy nudging `[J2 §9]`; none addresses several concurrent campaigns from different users on one repository (out of scope here as well); and every LOC and cost number in all four documents is an estimate.
<!-- end-source-section: 22.5 -->

<!-- source-section: 22.6 -->
<a id="sec-22-6"></a>

### 22.6 Why the ranking could flip, honestly

A and C are 0.05 apart under this rubric. Weight implementability at 0.30 and continuity at 0.20 and C wins; weight economy analysis higher and B closes the gap. The choice of A as the baseline does not rest on the score alone but on the reversal criterion: from A, the final design is reached by *adding* C's organs and B's nervous system and reversing one inherited policy; from C, the kernel's exit condition and unit lifecycle must be restructured around increments and closures added to receipts; from B, the kernel must be written. That asymmetry, not the decimal, decides.

---
<!-- end-source-section: 22.6 -->

