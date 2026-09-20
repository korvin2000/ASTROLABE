# Components, ownership and identities

**ASTROLABE 1.0.1 · specification** · Owner: Campaign controller / component owners.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §3, §3.1, §3.2, §3.3, §3.8. **Read with:** [evidence-coherence](../state/evidence-coherence.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F02](../../REVIEW.md#f02), [F08](../../REVIEW.md#f08).

<!-- source-section: 3 -->
<a id="sec-3"></a>

## 3. Architecture
<!-- end-source-section: 3 -->

<!-- source-section: 3.1 -->
<a id="sec-3-1"></a>

### 3.1 Two clocks, one unit `[FROM-A §3.1; controller = C's supervisor]`

```text
CAMPAIGN clock (deterministic, long-lived, per task)
  contract ─▶ impact pre-scan ─▶ shape + profile selection ─▶ requirement graph ─▶ increments (ready frontier)
           ─▶ cell_1 … cell_n (each: compile → run → verify → accept) ─▶ campaign receipt
  state: contract · ledger · evidence store · KB · verification registry · workspace (shadow ref) · four identities
  resumable after crash, sleep or a new session; owns budgets, leases, cancellation and authorization

CELL clock (model loop, bounded, per increment)
  compile ─▶ turn_1 … turn_m (≤ 40 soft) ─▶ terminal: done | blocked | partial | replan | waiting
  state: [S][R][K] prefix · [T] transcript · [A] anchor · STATE register · Workset · effects ledger
  ends by semantic completion; pressure rebuild is the in-cell fallback and is instrumented as a decomposition failure
```

The controller never reads a cell's transcript. It reads the **Result Packet** (typed, validated) and the **receipts** the scheduler recorded; the transcript is archived for audit, search, review-on-demand and post-cell extraction `[A §3.1; MB A3]`.
<!-- end-source-section: 3.1 -->

<!-- source-section: 3.2 -->
<a id="sec-3-2"></a>

### 3.2 Planes, components and ownership `[FROM-C §4.2–4.3, with A's components mapped in]`

| Plane | Component | Owns | Never owns | Minimal form |
|---|---|---|---|---|
| Control | **Campaign controller** (C's supervisor) | contract lifecycle and amendments; requirement graph, ledger, increment selection; cell lifecycle; budgets, leases, cancellation; authorization gates; shape and profile selection; resume/reconcile; cache-aware scheduling | semantic decomposition; any semantic judgement; model-generated status | one state machine over typed records |
| Context | **Context compiler** | `[S][R][K]` assembly; workset seeds; KB slice; coverage assertions; manifest; budget arithmetic; the one rebuild mechanism; boundary pre-compilation | permanent truth; permissions | a deterministic function of (increment, campaign state) |
| Execution | **Cell runtime** | the loop, layout, anchor render, register validation, gates, gauge, eviction, dispatch | global memory mutation; unbounded delegation; accepting its own completion | ~HELM's loop |
| Execution | **Tool layer + runner** | tool contracts, envelopes, shaping parsers, effect classes, execution modes, process handles, intent journal, reconciliation | approval of its own privileges | files, search, patch, run, poll/cancel, recall |
| Execution | **Workspace** | version registry, atlas, symbol and import index, shadow ref, dirty-state record, preimages | user branches, index, stash | regex/tree-sitter tier; LSP adapter optional |
| Platform | **Evidence store** | journal, blobs, stamps, receipts, packets, manifests, search; four identities | automatic promotion of summaries | SQLite + content-addressed blobs |
| Verification | **Verification scheduler / integrator** | check registry, triggers, closures, validity, baseline ledger, impact engine, rendering, exit gate, reserve, judge invocation, integration re-verification, completion acceptance | rewriting acceptance | code |
| Knowledge | **Knowledge base + curator** | notes, derived index, admission queue, lint, invalidation, injection ranking, skills, behaviour maps, promotion, pruning | authority over the contract; instructions | Markdown/YAML notes + FTS in SQLite |
| Execution | **Delegation service** | probe / review / writer / QA cell dispatch, packets, child budget allocations | decisions or integration acceptance | the same cell runtime with a different role configuration |
| Recovery/Routing | **Recovery ladder + router** | failure classification, fingerprints, capsule repair, alternative attempts, escalation, tier table, calibration logs | redefining the task or its budget | code + one small helper profile |
| Platform | **Provider adapters** | native request construction, event normalization, continuation, usage accounting by cache class | project memory | Responses, Messages, compat fallback |
| Platform | **Telemetry + evaluation runner** | phase tags, four quantities, manifests, exports; frozen campaigns, ablations, promotion/rollback | a live attempt (never changes the harness beneath it) | structured log + export; separate entry point |

All components are packages in one process. SQLite plus files is sufficient until measured requirements justify a queue, a service or a second database `[A §3.2; B §4.1; C §4.3]`. Deterministic control does not mean semantic decisions are reduced to rules: the controller *requests and records* model judgments (plan cell, review cell) and then applies evidence and authority policy `[B §4]`.
<!-- end-source-section: 3.2 -->

<!-- source-section: 3.3 -->
<a id="sec-3-3"></a>

### 3.3 Four identities and one version registry `[FROM-B §5.1; FROM-C §8.3]`

| Identity | Meaning | Why separate |
|---|---|---|
| `work_id` | the user's logical objective (campaign) | survives retries, resumes, model changes |
| `attempt_id` | one execution under a frozen harness/profile policy | reproducibility; honest failure accounting; alternative attempts |
| `candidate_id` (= stamp) | one set of artifact contents: base commit + tracked delta hash + untracked manifest hash + environment id | evidence from one candidate never certifies another |
| `context_id` (cell lineage) | one cell’s model-visible lineage; rebuilds have a projection generation | compaction, review and delegation without losing work identity |

The **version registry** is the single component behind coherence: `version(path)` = content hash of the working file (harness state directories excluded from stamps `[J1 §7.1]`); `displayed(path, v)` = union of line ranges shown to the model for exactly that version; `stamp(tree)` = candidate identity. Every read, fact, note, receipt and delegated result carries anchors into this registry ([§4.4](../state/evidence-coherence.md#sec-4-4)).

**Namespace rule.** `version(path)` is shorthand for `version(workspace_id, path)`; `displayed(path, v)` is shorthand for coverage in `(context_id, projection_generation, workspace_id, path, v)`. Shared content hashes do not share edit authority. A rebuild advances the projection generation; a new cell gets a new `context_id`, and alternative attempts get a controller-assigned `attempt_id`. These are qualifiers on existing records, not additional stores or a fifth campaign identity.
<!-- end-source-section: 3.3 -->

<!-- source-section: 3.8 -->
<a id="sec-3-8"></a>

### 3.8 Deterministic / semantic boundary `[A §3.6; MB §3.6; IM inv. 8]`

Never ask the model to: schedule a ready increment, add tokens, enforce a permission, decide whether a retry budget is exhausted, compute a hash or a stamp, decide that a check is current, decide that acceptance is met, or choose the shape. Always ask the model to: decompose requirements into increments, form and refute hypotheses, choose what to read, design the change, propose lessons and amendments, and judge (in a review cell) whether a change meets a requirement no check can express.

Context boundaries are **semantic** — increment done, new hypothesis family, independent review, role switch — never "the window is 60 % full". Pressure rebuild exists because plans are hypotheses and increments overrun; it is counted as a decomposition failure and fed back through the calibration prior ([§6.7](../context/continuity.md#sec-6-7)).

---
<!-- end-source-section: 3.8 -->

