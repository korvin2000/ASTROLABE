# Retained alternatives and synthesis decisions

**ASTROLABE 1.0.1 · rationale** · Owner: Architecture rationale.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §20, §20.1, §20.2. **Read with:** [overview](../architecture/overview.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F05](../../REVIEW.md#f05), [F08](../../REVIEW.md#f08), [F10](../../REVIEW.md#f10), [F12](../../REVIEW.md#f12).

<!-- source-section: 20 -->
<a id="sec-20"></a>

## 20. Deliberately not adopted, and the A/B/C disagreement ledger
<!-- end-source-section: 20 -->

<!-- source-section: 20.1 -->
<a id="sec-20-1"></a>

### 20.1 Not adopted from the sources `[A §20 ∪ C §17 ∪ B §14, condensed]`

| From | Rejected | Reason |
|---|---|---|
| WK `[C5]` | one action per turn; one file per patch; full register rewrite per turn; user text dies after turn 1 | turn tax; output-token cost; highest-value tokens discarded `[J2 §8]` |
| TILLER `[C3]` | "no permission prompts ever"; rollback of a whole transaction on a red run; seven verbs in one `do()` | jail blocks legitimate work; red is information; policy attaches at modality boundaries |
| TRACE `[C4]` | line-range edits; commits on the user's branch; LLM phoenix restart; top-5 facts by undefined relevance | strictly less safe than CAS + region; shadow ref instead; rebuild from validated state; the harness never chooses what the model believes |
| ANCHOR `[C2]` | append-until-pressure with an LLM checkpoint; optional ungated verification | bounded residency and cell boundaries; hard gate |
| HELM `[J2 §7]` | law "silence means green"; acceptance inside the model-edited register; immediate physical stubbing of every stale read; `look(ask)` as a detached second model; exactly three tools; composite metric mixing tokens and missteps; single-session scope | amended to Δ+absolute; acceptance moved to the contract; mark-then-stub; probe cells with packets; seven byte-stable families; Q and E reported separately with invariants as zero constraints; campaign layer |
| MB | standing role roster and persona text; role-based tier priors as the primary signal; failure-capsule repair in S0/S1; provider-managed agent runtimes as the architecture; retention by role prestige; graph/vector databases in the baseline | roles are cell configurations; routing by function; capsule repair from S2; build-vs-buy comparators only; retention by reconstruction cost; files + SQLite + FTS until measured misses |
| IM | learned action vetoes, speculative macro execution, dynamic harness generation, model training in the baseline; persistent REPL as durable memory; dense retrieval by default | P3 research investments; hidden state and restart problems; lexical + symbol + BMAP first |
| general | LLM summarization as primary compaction; mid-session tool-schema mutation; autonomous push/merge; queues, service meshes, second databases; a homemade security platform | evidence and economics in the sources; every one is an extension seam or an explicit non-goal |
<!-- end-source-section: 20.1 -->

<!-- source-section: 20.2 -->
<a id="sec-20-2"></a>

### 20.2 The A/B/C disagreement ledger `[NEW N10]`

| # | Question | A | B | C | Adopted resolution | Ablation arm |
|---|---|---|---|---|---|---|
| D1 | Where is the context boundary? | the increment (cell); pressure rebuild is a decomposition failure | a "coherent investigation segment" with capacity and economic compaction triggers | a unit/packet with pressure rebuild and role switch; long kernel sessions in S0/S1 | **A's increment boundary**, executed by C's rebuild mechanism (fifth use); B's economic trigger tunes `α`, `k` and turn budgets rather than deciding live ([§3.1](../architecture/components.md#sec-3-1), [§5.8](../runtime/residency-rebuild.md#sec-5-8), [§16.3](../economics/costs.md#sec-16-3)) | cells vs HELM pressure rebuild |
| D2 | Stale live reads | stubbed immediately (HELM W2) | replaced with references at aging boundaries | marked now, stubbed at the batch; > 800 tokens immediately | **C**: correctness rests on the region-seen precondition; the prefix cache survives edit turns ([§5.3](../runtime/register-workset.md#sec-5-3)) | immediate vs mark-then-stub |
| D3 | Tool surface | five tools with `what` enums | "an interface choice"; capability contracts only | ~30 operations in 10 families, per-role masks, catalog | **seven families with explicit operations**: HELM's modality boundaries + C's masks and catalog + B's envelope; every op must beat bash in eval ([§5.4](../runtime/tools.md#sec-5-4)) | invalid-call rate vs a 5-tool and a 30-op kernel |
| D4 | Model-written summaries | none; STATE is the only model summary | staged reduction with a validated semantic checkpoint when deterministic reduction is insufficient | none; STATE only | **none in the baseline**; B's validated semantic checkpoint is an ablatable extension (B5), never primary; two pressure rebuilds ⇒ `partial` + replan instead of summarizing ([§5.8](../runtime/residency-rebuild.md#sec-5-8)) | rebuild-only vs rebuild + validated checkpoint |
| D5 | Receipt validity | input closures per check | status separate from applicability; reuse proof when the declared dependency set is unchanged | stale on any tree change | **A + B**: closures joined to the coherence protocol; reuse proofs; `unknown` closure ⇒ conservative ([§4.4](../state/evidence-coherence.md#sec-4-4), [§8.1](../verification/scheduler.md#sec-8-1)) | closures vs stamp-coarse |
| D6 | Routing under budget pressure | function table, never-cheap list | eligible → affordable → lowest total cost; never clamp the floor | `clamp(max(prior, suggestion, risk_floor), budget)` | **B's refusal + A's floor + C's risk floor** ([§11.2](../operations/routing.md#sec-11-2)) | routed-with-refusal vs clamped vs all-high |
| D7 | Memory admission in autonomous mode | queue | queue; deterministic cases admitted without a model call | auto-admit with confidence ≤ 0.7 | **queue by default; auto-admit only lint-passing, anchored, scoped factual `LES`/conditional `PIT` at ≤ 0.6** ([§4.5](../knowledge/records.md#sec-4-5)) | off / frozen / live |
| D8 | An evidence graph? | evidence store + notes with `depends_on` + BMAPs | one versioned evidence graph with provenance edges as the main integration contribution (and its first risk) | version registry + anchors | **B's identities and typed provenance edges as rows in the evidence store, scoped to active behaviour and explicit dependencies** — no general ontology ([§4.3](../state/evidence-coherence.md#sec-4-3), [§4.4](../state/evidence-coherence.md#sec-4-4)) | graph projections vs plain metadata |
| D9 | Contract text placement and register size | contract slice in `[K]`; register 1,200 | full contract durable; compact active projection | contract digest ≤ 300 in `[A]` every turn; STATE 1,500 | **requirements in `[K]` (cached); ≤ 150-token digest with acceptance status in `[A]`; register 1,200** ([§5.1](../runtime/context-layout.md#sec-5-1)) | digest in `[A]` vs `[K]` only |
| D10 | Weakened acceptance | deterministic classifier over the diff | changes visible against the original obligations | acceptance-surface line on touched test files | **all three composed** ([§8.6](../verification/acceptance-review.md#sec-8-6)) | guard on/off with injected weakening |
| D11 | Reserves | 15 % verification | 20 % verification + 10 % recovery/persist, raised to known check costs | 6 turns + 15 % tokens | **cell 15 % + 5 %; campaign recovery 10 %; raised to known check costs before start** ([§17](defaults.md#sec-17)) | reserve on/off |
| D12 | Async watchers | Stage C with a tier-2 adapter | inline or async, reconciled | sync time-boxed checker; async as a seam | **agreement**: sync first; async only with a tier-2 adapter and an ablation ([§8.1](../verification/scheduler.md#sec-8-1)) | sync vs async |
| D13 | Pinning user messages | pinned verbatim, always | not every historical sentence into every child context; authority through structured references with exact excerpts | pinned verbatim | **pinned in the main cell; children receive packets with exact applicable excerpts and authority refs** ([§5.1](../runtime/context-layout.md#sec-5-1), [§10](../operations/delegation.md#sec-10)) | — |
| D14 | Capsule repair | P2 experiment | scoped repair helper for bounded operational defects | in S2 | **S2**, ≤ 2 attempts, ≤ 100-token diagnosis returned ([§13.2](../operations/recovery.md#sec-13-2)) | capsule vs kernel-only vs deterministic-only |
| D15 | The composite metric | 60/40 with floors | predeclared Q/E normalization, strata weights, CI margins | Q and E separately, invariants as zero constraints | **B's normalization and promotion policy, using billed cost for E as required by [§1](../architecture/principles.md#sec-1)/[§16](../economics/costs.md#sec-16), with C's zero-invariants and A's floors** ([§19.4](../evaluation/method.md#sec-19-4)–19.6) | — |
| D16 | Review scope | per increment on risk | design review of the final code against a rubric | per increment; judge with isolated tests | **both scopes** ([§8.8](../verification/acceptance-review.md#sec-8-8)) | increment-only vs both vs none |
| D17 | Auto-proposed acceptance in autonomous mode | model may only add; harness derives | obligations created before implementation; judgment named when no oracle exists | plan cell proposes; autonomous mode freezes as `agent-proposed` | **C's freezing with origins in the receipt; B's rule that an unexpressible requirement names the needed judgment instead of a fabricated oracle** ([§4.1](../state/contracts.md#sec-4-1)) | — |
| D18 | Whole-turn atomicity | no writes on preflight rejection; partial I/O reported | journaled batches; commands have separate outcomes; no global transaction | same as A | **preflight atomicity only; filesystem publication and commands retain separate outcomes** ([§5.5](../runtime/tools.md#sec-5-5)) | — |

---
<!-- end-source-section: 20.2 -->

