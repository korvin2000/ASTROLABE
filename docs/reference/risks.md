# Open risks and reversal criteria

**ASTROLABE 1.0.1 · rationale** · Owner: Architecture/evaluation owners.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §21. **Read with:** [method](../evaluation/method.md). Load companion sections only when the task crosses that boundary.

<!-- source-section: 21 -->
<a id="sec-21"></a>

## 21. Open questions and risks `[A §21 ∪ C §19 ∪ B §17, with reversal criteria]`

| # | Risk / question | Early signal | Mitigation or reversal |
|---|---|---|---|
| 1 | **Decomposition quality is load-bearing.** Mis-sized increments make boundary overhead dominate | continuations per increment > 1, rebuilds per cell > 0, boundary cost share rising | calibration prior ([§6.7](../context/continuity.md#sec-6-7)); continuation cells reuse seeds; plan cell re-run with failure evidence; if B-HELM matches B2 on long strata, revert to pressure-driven boundaries |
| 2 | `[A]` uncached cost outweighs anti-drift value on short tasks | high `[A]` share of billed cost in the local stratum with no drift incidents | S0 keeps the digest minimal; measure per stratum; shrink focus notes first |
| 3 | Mark-then-batch leaves stale bytes in the window up to `k` turns | edits rejected on `expect` rather than region; model confusion in traces | region-seen guarantees safety; 800-token threshold; ablate vs immediate |
| 4 | Sync checker latency dominates on large TypeScript/Rust projects | checker `not_run` rate high; p95 turn latency | time box; incremental checkers; tier-2 adapter; async ablation |
| 5 | Wider tool surface degrades tool selection | invalid-call rate vs the 5-tool arm | masks per role; catalog for rare ops; drop ops that lose to bash in eval |
| 6 | Test-integrity classifier precision | false-flag rate; justification lines per cell | per-language tuning; flags cost one line; review only on weakening |
| 7 | Transform path safety — a wrong codemod that compiles and passes tests | review findings on transform diffs; post-merge reverts | inventory + expected counts + representative sites + blast closure + review; the residual risk is a human codemod's |
| 8 | KB staleness and injection harm; memory reinforces a plausible error | repeated failures associated with one injected note | dependency invalidation; usage decay; quarantine and rollback; off/frozen/live ablation |
| 9 | Shared evidence rows become an expensive partial ontology | high upkeep, frequent stale edges, no acceptance gain | scope to active behaviour and explicit dependencies; retain direct search `[B §17]` |
| 10 | Compiler confidently omits a needed fact | repeated ask-backs or repairs caused by missing contracts | raise complement coverage; relax token targets; compare a simpler context policy |
| 11 | Function routing under tier drift; routing degrades difficult tasks | complex-stratum floor fails despite aggregate savings | table is data with a calibration date; never-cheap list; restore the capable default per stratum |
| 12 | Review becomes ritual or produces false findings | high review cost, little confirmed defect yield | narrow triggers; improve packets; measure the reviewer itself against fixtures |
| 13 | Parallel work shares hidden dependencies | frequent semantic integration repairs | serialize that task family; improve contracts, not managers |
| 14 | Recovery relies on environment assumptions (process groups, job objects, file replacement) | unreconciled effects or orphaned jobs in fault injection | narrow supported execution modes until the adapter contract is correct; platform validation before claims |
| 15 | Pre-compilation wastes compiles or serves a stale seed | miss rate high; any stale seed served (must be zero) | stamp equality is mandatory; disable when the miss rate exceeds the latency saved |
| 16 | Calibration prior overfits one repository's history | overrun rate unchanged or worse after adoption | prior is data with decay; ablate on/off; never lets the model skip planning |
| 17 | Evaluation becomes the optimizer's training set | gains vanish on new tasks/repositories | freeze search; replace a compromised final set; report failed transfer |
| 18 | Harness bugs devalue evidence | any invariant metric non-zero | fixtures are harness tests first, agent evaluations second `[QA §2]` |
| 19 | Interactive ergonomics: is contract + ledger + register enough transparency? | user interventions with reason "unclear state" | outside this document; the exports exist for a UI to consume |
| 20 | How much of `[K]` should be seeds vs notes vs skills for a given increment | manifests and retrieval-miss logs | answer from data, not intuition |

---
<!-- end-source-section: 21 -->

