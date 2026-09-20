---
title: "WAYPOINT — a mid-weight SOTA coding-agent architecture: bounded cells, durable campaigns, evidence-bound work"
version: 1.0-proposal
date: 2026-09-19
status: research-proposal · not implemented · not benchmark-validated
derived_from:
  - "judje-2.md §7 (HELM synthesis: Keel + 25 transplants) and its rejection table §8"
  - "merged-best-harness-ideas.md (packets, three-layer KB, shapes S0–S3, routing, judge protocol, verification ladder, security, evaluation)"
  - "ideas-summary-mix.md (task contract, requirement ledger, ready frontier, artifact identity, staged reduction, missing-complement retrieval, behavior maps, 60/40 objective, improvement loops)"
  - "judje-1.md §5, §8.2 (falsifiability audit, adversarial acceptance cases) · Qwen38analyze.md §4 (staging, 'silence ≠ green', MCP/model seams) · candidate-1/3/5 (Keel watchers & eviction, TILLER transactions & gauge, WK atlas & hot buffers)"
citation_convention: "[HELM §x] = judje-2.md §7 unless another section is named; [J2 §x] = judje-2.md; [MB §x] = merged-best-harness-ideas.md; [IM §x] = ideas-summary-mix.md; [J1 §x] = judje-1.md; [QA §x] = Qwen38analyze.md; [C1]…[C5] = candidate-1…5. No [Sxx] identifiers are used in this document — the corpus contains three unrelated S-schemes."
provenance_labels: [BASELINE, TRANSPLANT, NEW, HYPOTHESIS, ESTIMATE]
caveat: "Every rating, budget, threshold, LOC figure and cost coefficient below is an engineering estimate or a declared default, not a measured result. The document argues from mechanisms; it does not claim benchmark outcomes."
---

# WAYPOINT

**A campaign is a route. An increment is a waypoint. A cell is one leg, sailed with a bounded window, a verified register and a hard landing gate. The harness owns the chart; the model owns the helm.**

---

## 0. Executive summary

### 0.1 Thesis in one paragraph

The three source tracks agree on a settled baseline — the transcript is a cache, a small model-owned register is the real memory, edits are compare-and-swap, the world is the only oracle, orientation is harness-built, decisions are centralized and execution may be isolated — and they disagree only on how much machinery to place around one loop. HELM answers "as little as possible" and is right about the loop; the merged dossier answers "as much as the task has earned" and is right about durability, knowledge and verification; the ideas synthesis answers "measure it" and is right about the objective. WAYPOINT resolves the disagreement structurally instead of by compromise: **a HELM-class loop is the execution kernel (the *cell*), and everything long-lived — the task contract, the requirement ledger, the evidence store, the knowledge base, the verification schedule, the routing and delegation policy — belongs to a deterministic *campaign* layer that compiles each cell's context from validated state and never from the previous cell's transcript.** The unit that ties the two clocks together is the *increment*: one waypoint of the route, small enough for one bounded window, large enough to carry an executable acceptance. Context boundaries, verification boundaries and checkpoint boundaries are therefore the same boundary, chosen by semantic completion rather than by context pressure. That single alignment is what lets the design work on mid- and large codebases, on long refactors and across sessions without either a growing window or a standing agent organization.

### 0.2 Design commitments

1. **Two clocks, one unit.** A deterministic **Campaign Controller** owns durable state and selects increments; a **Cell** (HELM-class loop: `look` / `edit` / `run` / `state`, register at the tail, gates, gauge, bounded residency) executes one increment with a freshly compiled context. `[NEW, composed from HELM §7 + MB §3 + IM §4]`
2. **Three memories with three owners.** The **Task Contract** (harness-owned, user-authoritative: requirements, acceptance, constraints, exclusions, budget, authorization ceiling), the **Working Register** (model-owned, harness-validated: plan cursor, h/v/x facts, dead ends, decisions, open items, focus, next), and the **Knowledge Base** (curated, versioned, evidence-linked notes with dependency invalidation). Acceptance criteria are never model-editable; the model may only *strengthen* them. `[NEW split; parts from IM §3.3, HELM §7.3, MB §4.2]`
3. **What is seen is a harness-owned structure.** The **Workset** records every `(path, range, version)` rendered in the current window; edits must land inside it; entries drop and *announce* their staleness on version change; the next cell is seeded from it. It unifies WK's hot buffers, ANCHOR's displayed ranges and HELM's KNOWN / NOT SEEN line. `[TRANSPLANT, unified]`
4. **Signal on delta, state the absolute.** Diagnostics render as `Δ +1 −2 · now 3 @v`. Silence is never green; an unchanged red state is still a line. `[J1 §5.3, QA §4.4 correction to HELM law 2]`
5. **Two edit paths.** Anchored compare-and-swap inside the Workset for hand edits; a **scripted transform** path (codemod run in the jail, diff receipt, mandatory blast-radius verification) for mechanical breadth. Refactors of forty files stop being forty displayed regions. `[NEW]`
6. **Verification is a scheduler, not a checklist.** A registry of checks with input closures, validity stamps, cost classes and triggers (edit / step / increment / campaign); a reserve so verification is never skipped to fit a budget; a hard exit gate; independent review as a clean-context cell when the risk floor says so. `[HELM §7.5 + MB §9 + IM §9]`
7. **Delegation by kind, routing by function.** Baseline delegation has exactly two kinds — **probe** (read-only research cell) and **review** (clean-context judge) — both packet-in / packet-out. Parallel **writer** cells are an extension gated by ownership maps and a single integrator. Model tiers attach to *functions* (main cell, probe, review, extraction), never to role titles. `[MB §5, §7, §9.3; IM §10]`
8. **Learn by admission, promote to code.** Post-cell extraction into an admission queue; curator lint; precision-gated injection at compile time; usage-aware pruning; recurring lessons become tests and linters rather than prompt lines. `[MB §10, IM §8]`
9. **Deterministic control plane.** Budgets, gates, leases, permissions, hashes, stamps, accounting, loop detection and retry exhaustion are code. The model decides what matters; the harness enforces that it decided, and that it cannot act on stale, unseen or unverified state. `[HELM law 3; MB A8; IM §3.4]`
10. **Collapsibility.** A small task is one cell with a two-line contract and no delegation. Every layer above that must pay for itself in the ablation program (§19) or ship off by default. `[MB A10; IM §14]`

### 0.3 What is genuinely new relative to the sources

| # | Contribution | Why the sources did not have it |
|---|---|---|
| N1 | **Campaign / Cell / Increment decomposition** that makes the context boundary, the verification boundary and the checkpoint boundary coincide | HELM rebuilds under *pressure* at an arbitrary point; MB compiles per *task* but keeps a long-lived supervisor transcript; IM has the outer/inner loop but leaves the window policy to the inner loop |
| N2 | **Contract / Register / KB ownership split** with acceptance outside the model's write authority | HELM's `Acceptance` lives in model-edited STATE; IM names the invariant but not the register mechanics |
| N3 | **Workset as one structure** serving region-seen edits, explicit ignorance, stale-drop and cross-cell seeding | Three separate mechanisms in C2, C5, HELM |
| N4 | **Delta + absolute rendering** and the removal of "silence means green" from the laws | J1 and QA flagged it; no design fixed it |
| N5 | **Scripted-transform edit path** with diff receipts and mandatory blast-radius closure | HELM's task set includes a 40-file refactor that its region-seen rule makes prohibitively expensive |
| N6 | **Verification scheduler** with input closures, reserve and increment-level red tolerance | HELM has a pipeline; IM has the reserve; J1 has "coherent boundaries"; none combines them with validity closure |
| N7 | **Test-integrity guard**: deterministic detection of weakened acceptance (test/snapshot/skip/CI edits) surfaced as a gate | Named as an adversarial case in J1 §8.2 and MB §15.2, never mechanized |
| N8 | **Refactor mode**: baseline behaviour snapshot, pre-existing-failure ledger, temporarily-red increments, transform receipts | Scattered across C2 (dirty state), J1 (temporarily failing migration), C4 (blast radius) |
| N9 | **Function-based routing table** with an explicit "never routed cheap" list | MB routes by role prior; IM says "measure first"; neither names the functions |
| N10 | **Cross-cell coherence of facts**: evidence ids checked at compile time, facts about changed files auto-staled, `v` without evidence rejected | HELM has R3 within a session only |

### 0.4 The one diagram

```
                       ┌──────────────────────────────────────────────────────────────┐
  user / issue ──────▶ │  CAMPAIGN CONTROLLER  (deterministic)                        │
  amendments           │  Task Contract · Requirement Graph & Ledger · increments ·   │
                       │  cell lifecycle · budgets · authorization · routing policy   │
                       └──────┬──────────────────────────────────────────▲────────────┘
                              │ compile(increment)                       │ Result Packet
                              ▼                                          │ + Completion Receipt
                    ┌──────────────────────┐                  ┌──────────┴───────────┐
                    │ CONTEXT COMPILER     │                  │ VERIFICATION         │
                    │ [S][R][K] prefix ·   │                  │ SCHEDULER            │
                    │ workset seeds ·      │                  │ checks · closures ·  │
                    │ KB slice · manifest  │                  │ stamps · exit gate   │
                    └──────────┬───────────┘                  └──────────▲───────────┘
                               ▼                                         │ receipts
     ┌───────────────────────────────────────────────────────────────────┴──────────────┐
     │  CELL  (one increment, bounded window)                                           │
     │  [S] system · [R] repo prime · [K] compiled knowledge · [T] transcript · [A] anchor│
     │  tools: look · edit · run · state · (delegate: probe | review | writer*)          │
     │  register at the tail · workset · gates · gauge · batched eviction · rebuild      │
     └───────┬──────────────────────┬───────────────────────────┬────────────────────────┘
             │                      │                           │
   ┌─────────▼────────┐   ┌─────────▼──────────┐   ┌────────────▼─────────────┐
   │ WORKSPACE        │   │ EVIDENCE STORE     │   │ KNOWLEDGE BASE           │
   │ jail · shadow ref│   │ journal · blobs ·  │   │ index → notes → raw ·    │
   │ dirty-state rec. │   │ receipts · stamps  │   │ admission · lint ·       │
   │ atlas · symbols  │   │ searchable · ids   │   │ invalidation · promotion │
   └──────────────────┘   └────────────────────┘   └──────────────────────────┘

   Shapes: S0 one cell, minimal contract · S1 campaign of cells + KB · S2 + probe/review cells + routing
           · S3 + parallel writer cells (ownership map, single integrator)          * S3 only
```

### 0.5 How to read this document

Sections 1–3 fix the objective, the failure taxonomy and the laws. Sections 4–6 specify the durable layer, the cell and the compiler — the backbone. Sections 7–14 specify the subsystems by concern. Sections 15–17 consolidate economics and defaults. Sections 18–19 give the build order and the falsification program. Section 20 lists what was rejected and why; §21 the open questions; §22 the traceability of every mechanism to its source; §23 the glossary. Labels: `[BASELINE]` = settled across the sources; `[TRANSPLANT]` = taken from a named source with its adaptation stated; `[NEW]` = this document's contribution; `[HYPOTHESIS]` = plausible, unvalidated; `[ESTIMATE]` = a number chosen, not measured.

---

## 1. Objective, scope and failure taxonomy

### 1.1 The objective

WAYPOINT optimizes **reliable autonomous completion of difficult repository work** under a quality-constrained 60/40 objective `[IM §1.2]`:

```text
score = 0.60·Q + 0.40·E        eligible only if quality floors and regression limits hold
Q  = complete acceptance on complex tasks, repeated-run reliability, regressions, maintainability evidence
E  = end-to-end tokens per accepted task, repeated-read waste, overflow/compaction incidents, latency
cost_per_accepted_task = total cost of all attempts, helpers, delegates, retries / accepted tasks
```

Three consequences shape the design. First, **sufficiency outranks brevity**: a missing caller contract costs more than 2K extra tokens of relevant source `[MB A1, IM §6.1]`. Second, **every helper counts**: probe cells, review cells, extraction, verification and failed cells are all in the numerator `[MB §7.6]`. Third, **whole-task success dominates partial progress**: a harness that produces many plausible partial patches cheaply scores worse than one that completes fewer tasks fully `[IM §13.3]`.

### 1.2 Target workload

Medium and large repositories (10⁴–10⁶ LOC, monorepos included); tasks spanning local fixes, cross-module features, API and configuration migrations, multi-file refactors with behaviour preservation, ambiguous debugging with long logs, and work that outlives one context window or one session. Interactive and autonomous operation are both first-class; the difference is a policy on who answers `blocked`.

### 1.3 Unified failure taxonomy → mechanism map

Keel's discipline is adopted: no mechanism enters the design without a failure it addresses `[C1 §1, J2 §1.2]`. The taxonomy unifies Keel's F1–F11, MAST's categories `[MB §15.1]` and IM's failure classes `[IM §4.3]`.

| ID | Failure | Primary mechanisms (section) |
|---|---|---|
| F1 | Goal drift, dropped scope, silently narrowed requirements | Task Contract outside model authority; contract slice in `[K]`; user messages pinned; scope guard (§4.1, §5.1, §8.6) |
| F2 | Edits against stale content | CAS on content hash, mandatory `expect`; stale Workset entries dropped and announced (§5.3, §9.1) |
| F3 | Acting on unseen code, invented interfaces | Region-seen precondition; `KNOWN / NOT SEEN` render; atlas is harness-built (§5.3, §7.1) |
| F4 | Hypotheses laundered into facts | h/v/x facts with evidence ids; validator rejects `v` without id; conditional ops; cross-cell staleness (§5.2, §6.4) |
| F5 | Stall, rabbit hole, undirected exploration | Progress events; stall nudge; exactly one `[>]`; probe cells for exploration-heavy questions (§5.6, §10.2) |
| F6 | Fear tax — over-confirmation because mistakes are expensive | Shadow ref per turn, O(1) revert, per-edit revert, dirty-state protection (§4.5, §9.3) |
| F7 | Loops and repeated equivalent actions | Loop gate on `(tool,args,result)`; failure fingerprints across cells (§5.6, §13.2) |
| F8 | Premature or false completion | Executable acceptance in the contract; hard exit gate with current stamps; `blocked` as the only escape (§8.7) |
| F9 | Verification too late, too often, or unbound to state | Verification scheduler: inline / step / increment / campaign triggers; stamps; closures (§8.1–8.4) |
| F10 | Context bloat and attention decay | Cell boundaries at increments; bounded residency; batched eviction; stubs + recall; register at the tail (§5.1, §5.7, §6) |
| F11 | Prompt injection through repository or tool content | Harness-owned result delimiters; approved-instructions channel; capability enforcement in the executor (§14.3) |
| F12 | Cold-start tax and repeated discovery across sessions | Atlas cache; KB with precision-gated injection; behaviour maps; workset seeds (§7, §12) |
| F13 | Coordination conflicts between concurrent workers | Decisions never in parallel cells; ownership map; single integrator; stale-result rejection (§10.4) |
| F14 | Coverage overclaiming (claims about unread code) | Manifest and Workset produce factual coverage fields; review reports unread scope (§6.5, §8.8) |
| F15 | Unsafe retry after unknown outcome | Intent journaling; reconcile before retry; timeout never replays (§13.1) |
| F16 | Scope creep and drive-by damage | Write-path scope in contract; touched-outside-scope gate; smallest-hunk contract line (§8.6) |
| F17 | Acceptance weakened to get green | Test-integrity guard: deterministic classifier over test/snapshot/skip/CI edits; review flag (§8.6) |
| F18 | Memory poisoning, stale advice | Admission queue; provenance + confidence; dependency invalidation; usage-aware pruning; scoped candidates (§12) |

---

## 2. Laws

Twelve laws. Each is enforced by code somewhere in §4–§14; a law without an enforcing mechanism is a slogan and was cut.

| # | Law | Enforced by |
|---|---|---|
| L1 | **Context is a cache.** Loads have widths, results have lifetimes, `evicted ⇒ refetchable ∨ noted`, the register is written back. `[HELM law 1, C3 §6.3]` | budgets on every tool; stubs + `recall`; register validator |
| L2 | **Signal on delta; state the absolute.** Window growth ∝ surprise, but every verification line carries current absolute status and a version tag. `[HELM law 2 amended by J1 §5.3]` | watcher renderer (§8.3) |
| L3 | **The model decides what matters; the harness enforces that it decided** and cannot act on stale, unseen or unverified state. `[HELM law 3]` | gates, CAS, region-seen, stamps |
| L4 | **What is not seen is labelled unseen.** File bodies exist only in live, version-matched Workset entries. `[HELM law 4, C5 §2.3]` | Workset (§5.3) |
| L5 | **The contract outlives the conversation, the cell and the worker.** Requirements, constraints and exclusions survive every boundary and are never rewritten by compaction. `[IM §3.4 inv. 1, 10]` | Task Contract store; compiler coverage assertion |
| L6 | **Done is a receipt.** Completion binds acceptance criteria to checks run against a recorded workspace stamp; a passing log from a previous state is evidence about that state. `[MB 0.3, IM §9.2]` | receipts, stamps, exit gate |
| L7 | **One owner per transformation and per decision.** Raw output → shaped view → stub; contract → increments → cells; no two components summarize the same evidence or finalize the same task. `[IM §3.1]` | ownership table (§3.2) |
| L8 | **A missing result is unknown, not absent.** An empty scoped search is not absence; an unreconciled action is not "did not run". `[C2, HELM A5, IM inv. 7]` | `find` completeness fields; reconcile protocol |
| L9 | **Decisions are centralized; execution may be isolated; verification is independent.** `[MB 0.4]` | delegation kinds (§10) |
| L10 | **Relevance is not authorization.** KB scopes, skill filters and role tags are retrieval hints; capability is enforced in the executor. `[MB A13]` | jail, classes, capability ceiling |
| L11 | **Repository is truth; memory is claims; provider state is transient.** `[MB A14]` | KB validity; adapter design |
| L12 | **Every layer above one cell must pay for itself.** Features ship off unless their ablation shows a credible 60/40 gain. `[MB A10, IM §14]` | evaluation program (§19) |

---

## 3. Architecture

### 3.1 Two clocks

```text
CAMPAIGN clock (deterministic, long-lived, per task)
  contract ─▶ requirement graph ─▶ increments (ready frontier) ─▶ cell_1 … cell_n ─▶ campaign receipt
  state: contract · ledger · evidence store · KB · verification registry · workspace (shadow ref)
  resumable after crash, sleep, or a new session; owns budgets and authorization

CELL clock (model loop, bounded, per increment)
  compile ─▶ turn_1 … turn_m (≤ 40 soft) ─▶ terminal: done | blocked | partial | replan
  state: [S][R][K] prefix · [T] transcript · [A] anchor · register · workset
  ends by semantic completion; pressure rebuild is the in-cell fallback, not the plan
```

The controller never reads a cell's transcript. It reads the **Result Packet** (typed, validated) and the **receipts** the scheduler recorded. The transcript is archived for audit, search and post-cell extraction.

### 3.2 Components and ownership

| Component | Owns | Does not own | Minimal form |
|---|---|---|---|
| **Campaign Controller** | contract lifecycle, requirement graph and ledger, increment selection, cell lifecycle, budgets, leases, authorization gates, routing policy, resume/reconcile | semantic decomposition, any semantic judgement | one state machine over typed records |
| **Context Compiler** | `[S][R][K]` assembly, workset seeds, KB slice, coverage assertions, manifest, budget arithmetic | permanent truth; permissions | a deterministic function of (increment, campaign state) |
| **Cell runtime** | the loop, layout, register validation, gates, gauge, eviction/rebuild, tool dispatch | global memory mutation; unbounded delegation | ~HELM's loop |
| **Tool executor** | `look`/`edit`/`run` contracts, jail, classes, envelopes, process handles, shaping parsers | approval of its own privileges | files, search, patch, run, poll/cancel, recall |
| **Workspace** | atlas, symbol index, import graph, shadow ref, dirty-state record, preimages | — | regex/tree-sitter tier; LSP adapter optional |
| **Evidence Store** | journal, blobs, receipts, stamps, search | automatic promotion of summaries | SQLite/JSONL + content-addressed blobs |
| **Verification Scheduler** | check registry, triggers, closures, validity, rendering, exit gate, reserve | rewriting acceptance | code |
| **Knowledge Base** | notes, derived index, admission queue, lint, invalidation, injection ranking, promotion | authority over the contract | Markdown/YAML notes + FTS |
| **Delegation service** | probe / review / writer cells, packets, budgets, integrator | decisions | same cell runtime with a different config |
| **Provider adapters** | native request construction, event normalization, continuation, usage accounting | project memory | Responses, Messages, compat fallback |
| **Telemetry** | phase tags, four quantities, cost per accepted task, manifests, exports | — | structured log + export |

These are packages in one process. No queue, no service mesh, no vector store, no second database until a measured requirement says so `[MB §3.2, IM §3.1]`.

### 3.3 Orchestration shapes and collapsibility

| Shape | Composition | Default trigger | Added cost |
|---|---|---|---|
| **S0** | one cell; contract = request + auto-derived acceptance (`run:` sniffed test command) + write scope; no KB injection unless a note matches strongly; no delegation | ≤ ~3 files expected, one increment | none beyond HELM |
| **S1** | campaign of ≥2 increments; KB read/write; workset seeding; continuation cells | multi-file or multi-session work | compile + boundary cost (§16.3) |
| **S2** | S1 + probe cells for exploration-heavy questions + review cell when the risk floor says so + function routing | contract/ADR-touching changes, ambiguous bugs, cheap-tier work | delegate budgets |
| **S3** | S2 + parallel writer cells under an ownership map, worktrees, single integrator | decomposable work with disjoint write scopes and stable contracts | coordination, integration re-verification |

Shape is selected by the controller from contract metadata (expected files, blast radius, session length, user preference) and upgraded only on traced evidence (context pressure inside a cell, a probe request, a risk floor). Downgrade is aggressive: design decisions and interface changes never run in S3 cells `[MB §3.5, §5.5]`.

### 3.4 Data flow for one increment

```text
controller.select_increment()             ready frontier of the requirement graph; regression obligations kept
compiler.compile(increment)               contract slice + contracts/ADRs touching scope + workset seeds
                                           + precision-gated KB notes + focus atlas + skills(modules) → manifest
cell.run()                                turns: look/edit/run/state; scheduler runs checks by trigger;
                                           register validated on each patch; gauge on every result
cell.terminate()                          done (accept green @ current stamp) | blocked | partial | replan
scheduler.exit_gate()                     refuses "done" without current green on every run: item of the increment
controller.accept(result_packet)          ledger update; receipts stored; touched files → invalidation of dependents
extractor.run(trace)                      low tier, post-cell: candidate notes → admission queue → curator lint
controller.next()                         continue | review cell | ask user | finish campaign (full suite + receipt)
```

### 3.5 Controller and cell, in pseudocode

```python
def campaign(request, repo, policy):
    C   = Contract.from_request(request, policy)          # verbatim request pinned; acceptance derived or asked
    W   = Workspace.open(repo)                            # atlas, dirty-state record, shadow ref
    S   = Store.open(C.work_id); KB = KnowledgeBase.open(repo)
    G   = plan_cell(C, W, S, KB) if C.needs_plan else G_single(C)   # requirement graph + increments (model proposes, harness stores)
    while (inc := G.next_ready(C.budget)) is not None:
        ctx  = compile(inc, C, W, S, KB, seeds=G.carry_forward(inc))  # §6; persists manifest
        res  = cell(ctx, inc, budget=policy.cell_budget)              # §5
        S.receipts += res.receipts; G.ledger.update(res)              # verified/blocked/partial
        W.invalidate(res.touched); KB.enqueue(extract(res.trace))     # §8.4, §12.1
        match res.status:
            case "done":    G.close(inc)
            case "partial": G.continue_(inc, carry=res.register, seeds=res.workset)
            case "blocked": if not policy.resolve(res.question): return G.report(partial=True)
            case "replan":  G = replan_cell(C, G, res)                # decisions stay in the main line
        if policy.review_required(inc, res): S.receipts += review_cell(inc, res, C, KB)
    return finish(C, G, S, W)   # full suite + all acceptance @ current stamp, else blocked report

def cell(ctx, inc, budget):
    T, A, reg, ws = [pinned(ctx.user_msgs)], None, ctx.register, ctx.workset
    for turn in range(budget.turns):
        A   = anchor(reg, ws, ledger.view(), sched.render(), focus(reg), gauge(), nudges())
        out = model(ctx.S + ctx.R + ctx.K + T + A)
        if not out.calls:                                    # completion proposal
            if sched.exit_gate(inc, reg): return Result.done(reg, ws, receipts=sched.receipts)
            T.append(note(EXIT_REFUSAL)); continue           # hard gate; escape only via state(blocked)
        for call in out.calls:
            res = dispatch(call, reg, ws, sched)             # look | edit | run | state | delegate
            T.append(result(res.view, id=res.id))            # inside harness-owned delimiters
            if res.terminal: return res.packet               # blocked / replan
        if turn % k == 0: T = evict(T, k, order=refetchability)
        if tokens(...) > α·C_max: T = pressure_rebuild(T, reg, m)
    return Result.partial(reg, ws)                           # controller compiles a continuation cell
```

### 3.6 Deterministic/semantic boundary

Never ask the model to: schedule a ready increment, add tokens, enforce a permission, decide whether a retry budget is exhausted, compute a hash or a stamp, decide that a check is current, or decide that acceptance is met `[MB §3.6, IM inv. 8]`. Always ask the model to: decompose requirements into increments, form and refute hypotheses, choose what to read, design the change, propose lessons, and judge (in a review cell) whether a change meets a requirement that no check can express.

Context boundaries are **semantic** (increment done, new hypothesis family, independent review) — never "the window is 60% full". Pressure rebuild exists because plans are hypotheses and increments overrun; it is instrumented as a failure of decomposition, not as normal operation `[MB §3.6, IM §4.2]`.

---
## 4. The durable layer (campaign state)

Everything in this section lives outside the source tree (`.waypoint/` beside the repo or in a user cache keyed by repo hash), so that its own updates never disturb workspace stamps `[J1 §7.1]`. Nothing here is reachable through `edit`; the model reaches it only through `state` ops the harness validates.

### 4.1 Task Contract `[IM §3.3 + MB §6.1, hardened]`

```yaml
contract:
  work_id: W-0042                      # stable across attempts and sessions
  version: 3                           # increments only on authorized amendment
  request:                             # verbatim, append-only
    - {id: U1, at: <ts>, text: "<user message>"}
    - {id: U2, at: <ts>, text: "<amendment>"}
  requirements:
    - id: R1
      text: "POST /payments accepts an idempotency key and dedups within 24h"
      acceptance:
        - {id: R1.a, kind: run,    cmd: "pytest tests/payments/test_idempotency.py -q", origin: user}
        - {id: R1.b, kind: check,  text: "public API unchanged (contract:payments-api@7)", origin: user}
        - {id: R1.c, kind: run,    cmd: "pytest -k idempot", origin: model, strengthens: R1}   # model may only ADD
      depends_on: []
  constraints:
    - {id: C1, text: "do not change the refund flow", authority: user}
    - {id: C2, text: "no new runtime dependencies", authority: rules-file}
  exclusions: ["refund flow", "billing UI"]
  scope:
    write_paths: ["src/pay/", "tests/payments/"]
    protected_paths: ["migrations/", ".github/"]         # edits here are D-class (§14.2)
  budget: {cells: 12, turns_per_cell: 40, tokens: 2_500_000, cost: "<user>", verification_reserve: 0.15}
  authorization:
    ladder_ceiling: local_commit         # patch | local_commit | push | merge | deploy
    d_class: ask                         # ask | deny | allow(list)
  mode: interactive | autonomous
```

**Amendment rules.** A user message may amend anything; the model records the mapping (`state({propose: amendment, from: U2, …})`) and the harness applies it because the authority is the message, which is stored verbatim and auditable. The model on its own may **add** acceptance items (`origin: model, strengthens:`) and may **propose** narrowing or removing an item; such a proposal is `pending` until the user (interactive) or policy (autonomous: never auto-accept a weakening) resolves it. This closes F1 and F17 at the data model rather than by exhortation.

**Auto-derivation for S0.** When the request names no acceptance, the controller sniffs the test command from the atlas (`Makefile`, `pyproject`, `package.json`, `Cargo.toml`, `go.mod`) and inserts `R1.a: run: <suite> (origin: harness, scope: touched)`; the model must still state `Goal`-level acceptance in its first register patch or ask one question `[HELM §7.3 entry gate; C3 §5]`.

### 4.2 Requirement graph, increments and the ledger `[IM §4.2, §3.3; MB §3.4]`

```yaml
graph:
  increments:
    - id: I1
      requirement_ids: [R1]
      title: "add IdempotencyKey model + storage"
      accept: [R1.c]                       # subset of contract acceptance + model-added per-step accept
      depends_on: []
      write_scope: ["src/pay/models.py", "src/pay/store.py", "tests/payments/"]
      expected_files: 3
      risk: {blast_radius: 1, reversibility: easy}
      status: verified | in_progress | pending | blocked | cancelled(reason)
      cells: [cell-7, cell-8]
    - id: I2 …
  regression_obligations: [I1]            # verified increments remain checks for later ones
ledger:                                  # harness-derived, never model-written
  R1: {status: in_progress, evidence: [rcpt-19], stamp_valid: true}
  R2: {status: pending}
```

The **plan cell** (first cell of S1+) proposes the increments; the controller stores them, validates that every requirement is covered by at least one increment and that each increment has ≥1 executable `accept:` or an explicit `check:` with a named evidence kind. Increment selection is deterministic over the ready frontier (dependencies verified, budget available); the model may reorder within the frontier through its register but cannot mark anything verified — only receipts do `[IM §4.2]`. An increment that exceeds one cell is *continued* (same increment, carry-forward), not restarted. An increment that turns out to be wrong is cancelled with a reason and replaced; cancellations are kept `[J2 R5]`.

### 4.3 Evidence Store: journal, blobs, receipts, stamps `[C2; HELM A2, A6; IM §3.3]`

| Record | Fields | Notes |
|---|---|---|
| **journal event** | `event_id, cell, turn, kind (call|result|edit-intent|edit-outcome|check|nudge|boundary), args_digest, refs` | append-only JSONL; searchable via `look(find, in="store")` |
| **blob** | content-addressed: tool outputs, preimages, post-images, diffs, logs | truncation never applied to blobs; only to views |
| **stamp** | `stamp_id, base_commit, tracked_delta_hash, untracked_manifest_hash, env_id, at` | whole-workspace identity; computed before and after any `run(verify=true)` and at every cell boundary `[C2 §8, HELM A2]` |
| **receipt** | `receipt_id, check_id, acceptance_ids[], cmd, cwd, stamp_before, stamp_after, outcome (pass|fail|infra_error|inconclusive|timeout|unavailable), parsed {passed, failed, errors, skipped}, raw: blob, limits[]` | immutable; new state ⇒ new receipt `[J1 §5.3, IM §9.2]` |
| **artifact identity** | `artifact_id = (stamp, env_id)` | what a Result Packet's "done" is about |

Three evidence lines are contract text in `[S]` `[HELM A5]`: *exit 0 proves that invocation only*; *an empty limited-scope search is not absence*; *"pre-existing failure" requires a baseline receipt* (§8.5).

### 4.4 Knowledge Base `[MB §4.2, §10; IM §8]`

Three layers, derived index, typed notes, raw traces:

```text
.waypoint/kb/
  index/global.md          ≤ 1.5K tok: conventions, active ADRs, active CONTRACT notes — one line each; regenerated, never edited
  index/subsystem-<s>.md   ≤ 1K tok per subsystem view
  notes/ADR-012.md  CON-007.md  LES-231.md  PIT-003.md  BMAP-payments.md  NEG-019.md  STATUS-W-0042.md
  raw/W-0042/cell-7/…      traces, packets, manifests — append-only, indexed by work/cell id
  queue/                   candidate notes awaiting admission
  schema.md                how the KB is structured and maintained
```

Note kinds: `ADR` (decision, signed), `CON` (cross-boundary contract; always visible when its paths are in scope), `LES` (lesson), `PIT` (pitfall), `BMAP` (behaviour-to-code map, §7.4), `NEG` (negative-evidence ledger entry: unknown · unsearched · searched-empty(scope, version) · contradicted · verified-absent `[MB §10.4]`), `STATUS` (campaign checkpoint), `SKILL` (procedure with trigger, prerequisites, checks `[IM §8.4]`).

Front matter (every note): `id, kind, status (candidate|admitted|superseded|deprecated|invalidated), summary (the index line), scope (global|subsystem|path-glob|task-family), confidence, basis {evidence_refs, requirement_refs}, validity {depends_on: [contract@v, path@hash], invalidation_trigger}, supersedes, author (cell id), signed_by, use_count, last_used`.

Operations: `enqueue` (from post-cell extraction), `admit` (curator: dedupe, evidence present, scope bounded, no contradiction with admitted notes, secrets redacted), `query` (id, tag, FTS over summaries; dense retrieval only after measured lexical misses `[IM §7.4]`), `lint`, `regenerate-index`, `invalidate` (dependency-driven: a `depends_on` target changed ⇒ dependents flagged `stale-recheck`), `promote` (recurring `LES`/`PIT` ⇒ a test, linter or schema, then the note is demoted to a pointer `[MB §10.3]`), `prune` (usage-aware; superseded and invalidated notes leave the index but not the store).

**Injection is precision-gated at compile time** (§6.3): cap 8 notes / 1.5K tokens per cell; rank by scope match × dependency match × freshness × use-value; return nothing when nothing is strongly relevant; never re-inject unchanged advice inside a cell `[IM §8.3]`. Retrieval misses ("I needed CON-007 and did not have it") are logged from the register's `Open` items and feed ranking `[MB §4.4]`.

### 4.5 Workspace: shadow ref, dirty state, jail `[C3 §7.2 → HELM T1; C2 → HELM A3; HELM T8]`

- **Initial dirty-state record**: tracked delta and untracked manifest captured at campaign start; `revert` never crosses it; the final report separates agent changes from pre-existing user modifications.
- **Shadow ref** `refs/waypoint/<work_id>/head`: snapshot after every mutating turn; O(1) whole-tree revert to any turn; never touches user branches, index or stash; per-edit `revert:#id` from preimages for surgical undo.
- **Jail** (base layer): writes confined to workspace + tmp, env scrubbed of secrets, timeouts with process-group kill, network off by default; **explicitly labelled trusted-local mode when no real confinement exists** — a denylist is not a sandbox `[J1 §5.7]`.
- **Atlas, symbol index, import graph**: §7.

---

## 5. The Cell (execution kernel)

The cell is HELM's loop `[HELM §7]` with four changes: the contract slice and knowledge slice arrive as a compiled `[K]` segment; the register no longer carries acceptance; the Workset is explicit; verification lines carry absolute status. Everything else — three world-facing tools, CAS edits, delta watchers, stubs and recall, entry/exit gates, gauge, jail — is carried as baseline.

### 5.1 Context layout `[HELM §7.2 + MB §4.1 render order]`

```text
[S] system  (~900 tok, byte-stable across cells)
    role · contract lines (act, don't narrate; each turn changes the world, the register, or asks) ·
    tool schemas (all five; delegate masked in S0) · evidence-category lines · error policy · data/instruction rule
[R] repo prime  (~1–1.5K tok, stable per repo version)
    tree digest · languages · commands (build/test/lint sniffed) · rules file (the ONLY trusted repo text) · hubs
[K] compiled knowledge  (~1–4K tok, stable within the cell)
    contract slice: requirements of this increment (verbatim), ALL constraints and exclusions, acceptance ids ·
    CON/ADR notes touching write_scope · workset seeds (harness-served, hashed) · ≤8 KB notes · skill modules ·
    carry-forward: dead ends, open items, last verification status
[T] transcript  (append-only between eviction batches)
    user messages pinned verbatim · model messages · calls · results | stubs
[A] anchor  (~0.6–2.5K tok, rebuilt every turn, never persisted)
    STATE register · Workset KNOWN / NOT SEEN · Touched (≤10) · Verify lines (Δ + absolute + stamp) ·
    focus atlas zoom (≤300 tok) · gauge · nudges (each once)
```

Cache discipline: `[S]+[R]+[K]` is written once per cell and read cached on every turn; `[T]` is append-only with batched eviction; `[A]` rotates at the tail. No timestamps or counters in the prefix; tools are masked, never removed, so schemas stay byte-stable `[MB A2, §14.1]`.

### 5.2 The Working Register (STATE) `[HELM §7.3, C3 §5, minus acceptance]`

```markdown
# STATE v14 · cell 8 · I2 "thread ctx through handlers"
## Plan       1. [x] locate dispatch (#12)   2. [>] pass ctx into handlers  accept: run: pytest -k ctx
              3. [ ] update 3 call sites     4. [~] cancelled: rename Router — out of scope (C1)
## Facts      - v `Router.dispatch(req, ctx)`  src/router.py:88 @a9f1 [#17]
              - h handlers are all keyword-only            (h in NEXT ⇒ flagged risk)
              - x popleft is atomic here                   (refuted #31; kept)
              - v(stale @c02e) handle_user takes 1 arg     src/handlers/user.py:42 [#22]   ← file changed; re-look
## Dead ends  - tried monkeypatching ctx → import cycle (#22)
## Decisions  - D1: pass ctx explicitly, not via contextvar — because tests construct handlers directly; rejected: contextvar
## Open       - Q1: does CLI path build handlers? (trip: any edit under src/cli/ → check)  (needs: CON-007)
## Focus      src/handlers/
## Next       edit src/handlers/user.py:42 signature, then run accept
```

Harness-enforced invariants `[HELM §7.3, T2, W4, W5, R2, R3]`:
- size cap 1,200 tokens (acceptance moved to `[K]`); exactly one `[>]` while `[ ]` exists; exactly one `Next`;
- `v` requires an evidence id that exists in the store; a `v` fact whose pointer file changed is rendered `v(stale @old)` by the harness — the model did not write that tag and cannot remove it except by re-verifying;
- fact lines ≤ 240 chars, no fenced code; refuted facts are kept;
- a red verification line must be fixed or recorded in `Open` before `[>]` advances (R2);
- `[~]` requires a reason; `Open` items may carry `(trip: <condition>)` and `(needs: <note-id>)` — the latter is the retrieval-miss signal.

Register patches are the `state` tool's typed ops (`plan.tick`, `plan.cursor`, `fact.add {kind, text, evidence}`, `fact.refute`, `deadend.add`, `decision.add`, `open.add|close`, `focus`, `next`), optionally conditional on ops in the same turn (`if: green(op:2)`) so a fact is never recorded as verified before its evidence exists `[HELM T5]`. Output cost per turn is the patch, ~30–150 tokens; the anchor is harness-rendered input `[J2 §8 rejects WK's full rewrite]`.

### 5.3 The Workset `[NEW unification of C5 §7, C2 §4.2, HELM A1/W1/W2]`

```text
workset = { (path, range, version, source: look|post-edit|seed, turn) }   token-budgeted, not file-counted
KNOWN    : entries whose version == current file hash and whose bytes are live (unstubbed) in [T] or [K]
NOT SEEN : everything else — rendered as one line, plus named stale drops:
           "src/handlers/user.py:30-60 went stale @c02e (edited by transform #40) — look before edit"
```

Rules: a `look(read|def|outline)` registers displayed ranges; an `edit` post-view registers the new range at the new version; a **seed** is a harness-served excerpt in `[K]` with its hash (rendered ⇒ displayed); an anchored hunk outside KNOWN is rejected with the file outline; a version change drops the entry immediately and announces it (not after `k` turns) `[HELM W2]`; stubbing a result removes its ranges from KNOWN (the bytes are no longer in the window) — the model can `recall` to make them KNOWN again at the recorded version, but an edit still needs a *current*-version read if the file changed. The Workset is exported at cell end; the compiler re-serves the entries the next increment's plan references (§6.2).

### 5.4 Tools

Five tools; three face the world, one faces the register, one faces the campaign. Policy attaches at the modality boundary `[J2 §8 on TILLER's single `do()`]`.

```text
look(what, target, budget=1500, near?, glob?, in="workspace"|"store"|"kb")
  what ∈ { tree, outline, read, find, def, refs, importers, recall, bmap }
  → { text, truncated, more?, scope, complete, versions{path: v}, id }
  · whole-file reads above budget are refused → outline + "name a range or ::Symbol"          [HELM]
  · dedup: same (what, target, version) live in window → "see #17 (unchanged)"                 [HELM]
  · find returns scope + complete + truncated; in="store" searches journal/blobs; in="kb" searches notes   [HELM A4, A6]
  · importers(path|symbol) → reverse edges from the import graph, with completeness flag        [C4 R1, J1 §5.6]
  · several independent looks in one turn share one output budget                              [J1 §7.3]

edit(ops, why)
  ops: [ { path, expect: v /*required*/, hunks: [{anchor, near?, new}], if?: "green(op:N)" }
       | { create, content } | { delete, expect } | { rename, expect }
       | { revert: "#id" }
       | { transform: { cmd | script, scope_glob, why } } ]                                      [NEW §9.2]
  → { ok, views[], versions, syntax{path: ok|error}, diffstat, touched_outside_scope[], test_integrity[], error?: {kind, candidates[]} }
  · CAS on content hash; anchors unique (exact → ws-normalised); hunks inside KNOWN ranges       [HELM, A1]
  · all anchored ops in a turn succeed or none (preflight all, then apply; a mid-batch I/O failure reports actual partial state — no false rollback claim)   [J1 §5.2]
  · inline syntax check; watchers scheduled; preimages saved; shadow snapshot                   [HELM W3, A3, T1]
  · transform ops run in the jail and return a diff receipt; they do not populate KNOWN

run(cmd, shape="auto", budget=1200, timeout=120, verify=false, bg=false, if?: "applied(op:N)")
  → { exit, view, truncated, id, handle?, stamp_before?, stamp_after?, current, parsed? }
  · full output to the store; shaped view (pytest/jest/cargo/go/tsc/mypy/ruff/… parsers) with absolute counts  [HELM]
  · non-zero exit is information, never an op failure; timeout kills the process group, never replays  [HELM A4]
  · bg=true returns a handle; poll(handle) returns only NEW output; cancel(handle)                 [IM §5.1]
  · verify=true records stamps; any later mutating action marks the receipt stale                 [HELM A2]
  · classes R / W run inside the jail; D ends the turn with a question (never in autonomous mode silently)   [HELM T8]

state(op)
  op ∈ { patch: {…typed register ops…}, propose: plan | amendment | increment_split,
         blocked: {reason, evidence[], question?}, retrieval_miss: {need, why} }
  · patches validated against §5.2 invariants; rejected patches return the violated rule, nothing applied  [C5 §19 fail-closed]

delegate(kind, packet, mode="sync"|"async")                                                   masked in S0
  kind ∈ { probe, review, writer* }         (*S3 only)
  → { handle } | { summary(≤budget), packet_id, findings[], unresolved[], cost }
```

Undo, notes, plan and memory proposals are all expressible through these five; there is no sixth tool. MCP or other external tools are wrapped as `run`-class capabilities with the same envelope and jail classes; a tool with external side effects is D-class unless the contract allows it `[QA §4.6]`.

### 5.5 Transactional turns `[C3 §4.2 → HELM T5, made unambiguous; J1 §7.3]`

The canonical verified cycle is one round trip:

```text
edit([{path:"src/handlers/user.py", expect:"c02e", hunks:[…]}], why="accept ctx")
run("pytest -q -k ctx", verify=true, if:"applied(op:1)")
state({patch:[{plan.tick:2, if:"green(op:2)"}, {fact.add:{kind:"v", text:"handlers accept ctx", evidence:"op:2"}, if:"green(op:2)"}],
       next:"update remaining call sites"})
```

Semantics: anchored edits are all-or-nothing on *op failure*; a non-zero `run` exit is information; a failed check leaves the applied code and its failure evidence in place (no automatic rollback of a legitimate intermediate state); conditional `state` ops fire only when their condition is met, otherwise they are dropped and the drop is rendered. "Batch what is decided; turn on what is discovered": ops whose *arguments* depend on an earlier result stay in the next turn. Independent `look` calls in one turn run in parallel under a shared budget `[IM §5.3]`.

### 5.6 Gates and nudges `[HELM §7.7 + additions]`

Each is one line in `[A]`, fires once per condition; only the exit gate blocks repeatedly.

| Gate | Trigger | Effect |
|---|---|---|
| Entry | first non-register `edit` while the register has no plan step with an `accept:` or the increment's acceptance is unresolved | nudge; if acceptance cannot be written crisply, the right move is one question (`state(blocked)`) |
| Exit (hard) | completion proposal while any `run:` acceptance of the increment lacks a green receipt at the current stamp, or a red verify line is unrecorded, or `[ ]` steps remain | refused; the anchor lists exactly what is missing; escape only via `state(blocked)` with evidence |
| Pressure | `tokens > α·C_max` | fold into register; rebuild (§5.8) |
| Stall | 3 turns without a progress event (evidence-backed tick, h→v with id, green run, verified new fact) | one line: re-read plan · zoom out · surface blocker · or request a probe |
| Loop | identical `(tool, args, result hash)` twice | one line; third occurrence ends the turn with a required `state` op |
| No cursor / two cursors | register invariant | patch rejected with the rule |
| Red not recorded | `[>]` advances while a verify line is red and no `Open` item references it | patch rejected |
| Stale fact in Next | `Next` or a decision rests on an `h` or `v(stale)` fact | flagged risk line |
| Scope | an edit touches a path outside `increment.write_scope` (inside contract scope) | allowed once with a rendered warning; the second requires `state(propose: increment_split)` or a justification in `why` — recorded in the Result Packet |
| Test integrity (§8.6) | an edit modifies test files, snapshots, skip markers, CI config or acceptance commands | rendered as a flagged line; must be justified in the Result Packet; forces review when the change weakens an existing check |
| Turn budget | 80 % of cell turns | nudge: reach a coherent boundary and checkpoint |

### 5.7 Gauge and residency `[C3 §6.2 → HELM T3; HELM §7.6; C1 §7]`

Every tool result ends with `⟨ctx 41% · turn 12/40 · 2 since verify · known 6 ranges/3.1K · STATE v12⟩` (~20 tokens). Tool results live in full for `k = 10` turns, then become ~20-token stubs; because a turn may carry several results and pinned items exist, the age rule alone does not bound residency `[J1 §5.5]` — a **total live-result budget** (default 16K tokens) is enforced in addition, stubbing the oldest refetchable results early when it is exceeded. Within a batch the stubbing order is by refetchability — raw observations of current repo state first, verdicts (diffs, exit codes, receipts) last `[HELM T7]`. A live read whose file changed is stubbed *immediately* as stale. Model messages older than `3k` turns are trimmed to their first line plus the calls made. User messages are pinned. Stubs are `recall`-able by id; the store is searchable, so an evicted result is a recoverable *pointer to captured bytes*, not a bare address `[J1 §5.4]`.

### 5.8 Pressure rebuild (in-cell fallback) `[HELM §7.6, C1 §7.5]`

At `α = 0.65`: one nudge to fold what is still needed into the register; after that turn `[T] := pinned user messages + note("rebuilt at turn t") + last m = 6 turns (with stubs)`. `[K]` is recompiled to include the current register's referenced ranges as seeds. No model summarization. A cell that rebuilds twice is terminated as `partial` with a `replan` hint — two rebuilds mean the increment was mis-sized `[NEW policy]`.

### 5.9 Cell termination and the Result Packet `[MB §6.2, IM §9.3]`

```yaml
result:
  cell: cell-8   increment: I2   status: done | blocked | partial | replan
  register: <final STATE>        workset_export: [(path, range, version)]
  changes: [{path, kind, diffstat}]   transforms: [{script_hash, files: 14, diff: blob}]
  receipts: [rcpt-19, rcpt-20]   # produced by the scheduler, referenced not restated
  stamp: s7
  coverage: {ranges_displayed: n, files_touched_unread: [], probe_findings_used: [...]}   # from telemetry, not the model
  flags: {scope_warnings: 1, test_integrity: [{path, kind: "skip-marker added", justification: "…"}]}
  not_tested: ["concurrency above 8 callers"]
  notes_to_persist: [{kind: LES, summary, scope, evidence_refs}]
  open_questions: []   blocked: {reason, evidence, question}?
  cost: {input_uncached, cache_read, cache_write, output, tool_seconds}
```

`done` is a *proposal* until the scheduler's exit gate accepted it; the controller's ledger changes only on receipts. `blocked` with a question is a success path, not a failure `[MB §5.4]`.

### 5.10 One turn, rendered (abridged)

```text
[K]  I2 "thread ctx through handlers" · R1: … · accept: R1.c run: pytest -k ctx · C1 do not change refund flow ·
     CON-007 handler signature contract (v3) · seed src/router.py:80-96 @a9f1 · PIT-003 "handlers built directly in tests"
…[T]…
#41 edit → ok · views: src/handlers/user.py:40-46 @d1e7 · syntax ok · diffstat +2 −1
#42 run pytest -q -k ctx (verify) → exit 1 · 11 passed, 1 failed · FAILED test_cli_ctx — TypeError handle_cli() missing ctx
     (full: #42, 212 lines) · stamp s8
⟨ctx 38% · turn 14/40 · 0 since verify · known 5 ranges/2.6K · STATE v15⟩
[A]  # STATE v15 … 2. [>] pass ctx into handlers … Next: edit src/cli/main.py handle_cli signature
     ## Workset  KNOWN: router.py:80-96@a9f1 · handlers/user.py:30-60@d1e7 · … · NOT SEEN: everything else; src/cli/main.py never read
     ## Touched  M src/handlers/user.py (+2 −1) @d1e7 "accept ctx"
     ## Verify   types(touched): Δ +0 −1 · now 0 @d1e7 ✓ · tests(k ctx): 11 pass 1 fail #42 @s8 · accept R1.c: red · full: stale (s3)
     ## Focus    src/cli/  main.py "entry"·handle_cli@31  args.py …
     ⟨trip Q1 fired: edit under src/cli/ pending → check CLI path builds handlers⟩
```

---

## 6. Context compilation (between cells) `[MB §4.1, §4.7; IM §6.1; NEW carry-forward]`

### 6.1 The compile function

```text
compile(increment, C, W, S, KB, seeds):
    mandatory = contract_slice(C, increment)                # requirements (verbatim), ALL constraints, exclusions, acceptance ids
              ∪ CON/ADR notes whose paths ∩ increment.write_scope ≠ ∅
              ∪ carry_forward(register: dead ends, open items, last verify lines)
    budget    = C_max·α − |S| − |R| − reserve(output + next observation + [A] max)
    if |mandatory| > budget: return NEEDS_RESCOPING              # never drop a constraint; controller asks for increment_split
    selected  = mandatory
    selected += workset_seeds(seeds, budget_share=4K)            # re-served at current versions; changed files become NOT SEEN + a note
    selected += kb_slice(KB, increment, cap=8 notes/1.5K)        # precision-gated; may be empty
    selected += skill_modules(increment)                         # module granularity; mandatory sections survive filters
    selected += focus_zoom(W.atlas, register.focus or increment.write_scope)
    assert coverage(selected, required=[constraints, acceptance, CON for touched paths])
    persist manifest(selected, versions, omissions, budget, reasons)
    return render([S], [R], [K]=selected)
```

Selection order: mandatory requirements → affected contracts → carry-forward → workset seeds → lessons/pitfalls → skills → background `[MB §4.1]`. Coverage assertions check presence, not understanding `[MB §4.1]`.

### 6.2 What carries forward across cells `[NEW]`

| Carried | How | Not carried |
|---|---|---|
| Register | validated; `v` facts re-checked against the store and file versions (stale ones tagged) | transcript |
| Workset seeds | entries referenced by the next step's plan text, `Focus`, or `Next`; re-served at *current* versions with hashes; ≤4K tokens | entries for files that changed (announced as NOT SEEN) |
| Dead ends, open items, decisions | verbatim into `[K]` | model prose |
| Verification status | last receipt per check with validity | raw logs (recallable by id) |
| Touched ledger | compressed: paths + versions | diffs (in store) |
| Pinned user messages | always | — |

The store is campaign-scoped, so `recall #17` works across cells; a stub index of ids referenced by facts is rendered on request, not by default.

### 6.3 KB injection ranking `[IM §8.3, MB §4.4]`

`score = w_scope·match(scope, write_scope) + w_dep·overlap(validity.depends_on, contracts in play) + w_fresh·freshness + w_use·use_value − w_len·tokens`; inject the top notes above a threshold, at most 8 / 1.5K tokens; `CON` notes for touched paths bypass the cap (they are mandatory). Log `(injected, used-in-register?, outcome)` per note for usage-aware pruning. A `retrieval_miss` op or an `Open (needs: …)` line is a labelled negative for the ranker.

### 6.4 Cross-cell fact coherence `[NEW, generalizing HELM R3]`

At compile time the harness re-validates every `v` fact: the evidence id must resolve; if the fact carries a `path@hash` pointer and the file's hash changed, the fact is rendered `v(stale @old)`. A fact stale for two consecutive cells with no reference is moved to the STATUS note and dropped from the register. `x` facts are kept until the campaign ends and are offered to the extractor as `PIT` candidates.

### 6.5 Manifest `[MB §4.7]`

Per cell: increment id, contract version, register version in, notes injected (ids, versions), seeds (path, range, hash), skills, model profile, budget arithmetic, omissions with reasons, and the reason for the boundary (done / partial / replan / pressure). Manifests make "the information was absent" distinguishable from "the model misread it" and are the input for tuning injection and seed budgets from data.

---
## 7. Repository understanding at scale

### 7.1 Atlas: harness-built structure without bodies `[C5 §6 → HELM W6; C3 map(); C1 §10]`

Per file: `path | bytes | lang | hash8 | exports[] | imports[] | tests_for?`. Built lazily on first boot, refreshed O(touched) after every edit or transform, cached by repo hash. Rendered by focus: `/` → top-level dirs with sizes; a directory → children with export counts and one-line names; a file → its outline. Commands (build/test/lint/format) are sniffed from manifests and shown in `[R]`. Generated, vendored and build outputs are excluded from the default walk but remain reachable by explicit `look` `[IM §7.1]`. **Models invent files; the atlas never lies about existence.**

### 7.2 Symbol index tiers `[C1 §10 → HELM; J1 §5.6]`

| Tier | Mechanism | Gives | Honesty |
|---|---|---|---|
| 0 | regex / ctags-grade | outlines, definitions by name | `complete: false` on `refs` |
| 1 | tree-sitter | incremental syntax trees, precise outlines, declaration spans | still no cross-module resolution |
| 2 | language-service adapter (optional, P1) | definitions, references, diagnostics with project semantics; incremental type checking that makes fast watchers viable in large repos | adapter reports scope and unresolved dynamic cases |

Tier 2 is the first optional adapter and the one with the largest expected effect on both Q and E in large repositories: without it, `refs` and blast-radius selection are heuristics and type checks are per-file. Every `refs`/`importers` result carries `complete` and `scope` so an incomplete index is never mistaken for absence `[L8]`.

### 7.3 Import graph and blast radius `[C4 §9 → HELM R1]`

`blast(E) = closure(importers(E)) ∪ E`, from the import graph (tier 1+); tests are selected as `tests_for(blast(E))` using naming conventions and the atlas `tests_for` edges. When the graph is incomplete for a file (dynamic imports, reflection, plugins), the scheduler widens to the package suite and says so in the verify line. Full-suite cadence: every `K = 5` verified increments and at campaign end `[ESTIMATE]`.

### 7.4 Behaviour-to-code maps `[IM §7.3]`

`BMAP-<subsystem>` notes: behaviour → entry points → implementation → state read/written → important callers → tests → locators (`path::symbol@hash`). Curated by the extractor from cells that touched the subsystem; locators validated at compile time and marked `unresolved` when their symbol moved. Disclosed progressively: subsystem → behaviour → symbol → source. A `look(bmap, "payments")` costs ~200–400 tokens and typically replaces a multi-turn search storm on repeat visits `[HYPOTHESIS, to be measured in §19]`.

### 7.5 Missing-complement retrieval protocol `[IM §7.2]`

A contract line in `[S]`, and the rationale for probe cells: *"Before deciding, name the fact you lack — a caller, a config, a schema, a test assumption — and search for that complement, not for another similar snippet. Stop when the decision is supported; otherwise record the specific gap in Open."* The cheap checklist is the baseline; a multi-call retrieval controller is a probe cell (§10.2), used when the parent would otherwise spend more than ~10 turns of exploration in its own window `[ESTIMATE]`.

### 7.6 Large-monorepo policy `[IM §7.1]`

Scope searches by package and likely dependency direction, then widen; `find` results always carry `scope` and `complete`; the atlas root render never lists more than one level; `Focus` in the register selects the zoom; a probe cell can own a wider scope without polluting the parent's window.

---

## 8. Verification and truthful completion

### 8.1 The verification scheduler `[NEW composition of HELM §7.5, IM §9, J1 §7.5, MB §9.1]`

```yaml
check:
  id: CHK-types-touched | CHK-tests-blast | CHK-accept-R1.c | CHK-full | CHK-review | CHK-lint | CHK-quality-gate
  kind: syntax | type | lint | unit | integration | acceptance | full | quality | review
  selector: touched | blast | named(cmd) | all
  input_closure: known(paths[]) | package(p) | unknown        # what invalidates it
  cost_class: inline | fast | slow | expensive
  trigger: every_edit | step_boundary | risk>θ | increment_end | campaign_end | on_demand
  last: {receipt_id, stamp, outcome, counts}                  # validity computed, never assumed
```

Triggers and cost `[HELM §7.5]`:

| Layer | Trigger | Window cost |
|---|---|---|
| Inline syntax | every anchored edit (sync) | one line per error, in the edit result |
| Fast watcher: type/lint on touched files | every edit (async; sync when tier-2 adapter makes it <2 s) | Δ + absolute line, version-tagged, superseded on later edits |
| Blast-radius tests ∪ step `accept:` | `[>]` moves, or `risk > θ`, or fused `run(if: applied)` | shaped view with counts; receipt + stamps |
| Increment acceptance | increment end | receipt; exit gate input |
| Full suite + quality gates | every K increments; campaign end | shaped view |
| Independent review (L5) | risk floor, contract/ADR touch, cheap-tier output, test-integrity flag | review cell (§8.8) |

**Validity**: any mutation (edit, transform, `run` that changed files — detected by stamp diff) marks stale every check whose input closure intersects the changed paths; a check with `unknown` closure is marked stale conservatively `[IM §9.2]`. **Reserve**: `verification_reserve` (default 15 % of the cell's tokens and turns) is unspendable on anything but checks and the final register patch; if the reserve is reached, the cell checkpoints as `partial` and says what is unverified `[IM §9.5]`. **Verify-on-stop** reuses valid receipts and runs only missing checks — never a blind full suite per completion proposal.

### 8.2 Claim-matched ladder `[MB §9.1]`

L0 static (always) · L1 unit (code changes) · L2 integration/e2e (cross-module, contract touches) · L3 product use by a QA-style run (user-visible behaviour: CLI/browser drives the product; logs/screenshots as evidence) · L4 measurement (performance, agent-behaviour claims) · L5 independent clean-context review. Depth follows the claim, not ritual: a formatting change needs L0; a one-line authorization change may need L2 + L5. Tests written by the same cell are useful but not independent proof; existing regression checks are never dropped; `not_tested` is recorded.

### 8.3 Rendering: delta + absolute `[L2; J1 §5.3; QA §4.4]`

```text
## Verify   types(touched): Δ +1 −2 · now 3 @d1e7 (#44)      ← never "0 new" alone
            tests(blast 14): 13 pass 1 fail #42 @s8 · accept R1.c: red
            full: stale (s3, 2 increments ago)  · review: not run
            (unchanged red states are still rendered, compressed: "types: no change · still 3 @d1e7")
```

Green is a scoped observation: the line names invocation scope (`touched`, `blast 14`, `k ctx`) and the stamp. Parsed counts come from runner output; a generic exit code never becomes a count `[J1 §5.3]`. A wrapper's exit status is not the status of the tests inside it; runners are invoked directly by the scheduler, never through `|| echo` constructions `[J1 §9]`.

### 8.4 Stamps and invalidation `[C2 §8 → HELM A2; IM §9.2]`

Stamp = base commit + tracked delta hash + untracked manifest hash + environment id. Receipts record `stamp_before/after`; `current = (stamp_after == stamp_now)`. A commit hash alone is insufficient in a dirty tree. After any mutation the scheduler recomputes validity; receipts are immutable — a new state gets a new receipt.

### 8.5 Baseline run and pre-existing failures `[C2 → HELM A5; NEW as a first-class ledger]`

At campaign start (S1+) or on first `run(verify=true)` (S0), the scheduler records a **baseline receipt**: the relevant suite on the initial dirty tree. Failures present there are the **pre-existing-failure ledger**, rendered once in `[K]` (`pre-existing: 2 failing (test_legacy_x, test_flaky_y) @s0`). A later red that matches the ledger is `pre-existing (unchanged)`; a new red is steering. Nothing may be called pre-existing without this receipt `[HELM A5]`.

### 8.6 Scope guard and test-integrity guard `[NEW; cases from J1 §8.2, MB §15.2]`

- **Scope guard**: the diff of each edit is classified against `increment.write_scope` and `contract.scope`. Outside the increment but inside the contract: warning once, justification on repeat. Outside the contract: rejected unless `state(propose: amendment)` is pending and the mode allows. Protected paths are D-class.
- **Test-integrity guard**: a deterministic classifier over the diff detects (a) deleted or renamed test functions/files, (b) weakened assertions (`assert x == y` → `assert x`), (c) added skip/xfail/only markers and `.skip` calls, (d) snapshot/golden updates, (e) CI/config changes that alter which checks run, (f) edits to acceptance commands. Each detection is rendered in the edit result, must be justified in the Result Packet, and forces a review cell when the change *weakens* an existing check on code the increment did not intend to change. The guard is heuristic (per-language patterns) and says so; it exists to make weakening *visible*, not to forbid legitimate test maintenance.

### 8.7 Hard exit gate `[C3 §5 → HELM T4; C5 done_when]`

A cell's completion proposal is accepted only if: every `run:` acceptance item of the increment has a green receipt whose stamp is current; every `check:` item has an evidence reference the reviewer or user accepted; no verify line is red without an `Open` entry; no `[ ]` steps remain uncancelled; test-integrity flags are justified. The only escape is `state(blocked: {reason, evidence, question?})`, which ends the cell with an honest partial report. The campaign gate additionally requires the full suite green (or its failures in the pre-existing ledger) and all contract acceptance items at the final stamp.

### 8.8 Independent review cell and judge protocol `[MB §9.3; IM §9.4]`

Inputs (evidence packet, never the proposer's transcript): contract slice, the diff, receipts with parsed counts, CON/ADR notes touching the paths, test-integrity flags, the pre-existing ledger, and a structural-quality rubric (duplication, unnecessary abstraction, naming, dead code, error handling). Read-only tools (`look` only, ≤10 calls, budget 30K tokens `[ESTIMATE]`). Output:

```yaml
verdict: approve | revise | reject | insufficient_evidence | escalate
findings: [{severity: blocker|major|minor|nit, location: path:line@hash, issue, suggested_fix, kind: correctness|contract|quality|test-integrity}]
coverage: {files_reviewed, ranges, unread: []}          # from telemetry
contract_violations: []   confidence: 0.0–1.0
```

Rules: `insufficient_evidence` with a named missing criterion is a correct outcome; executable checks outrank opinion; findings ≥ major become `Open` items in the implementing cell's next register (steering) and `PIT` candidates; for competing proposals, candidates are presented symmetrically in randomized order; the judge is calibrated against labelled fixtures; ties and `escalate` go to the user or the extra-high tier. Reviews of *quality* (L5) are advisory except where a configured quality gate (complexity, duplication thresholds) makes them executable.

### 8.9 Refactor mode `[NEW composition; C2, C4, J1 §8.2]`

Activated when the contract's requirements are behaviour-preserving ("refactor", "extract", "rename", "migrate API"). Adds:
1. **Behaviour snapshot**: baseline receipt over the affected suites plus, where the project has them, characterization outputs (CLI goldens, API fixtures) recorded as blobs at `s0`.
2. **Temporarily-red increments**: an increment declares `red_ok_until: increment_end`; inline syntax still runs, type checks render deltas, but the step-boundary gate does not fire until the declared coherent boundary (signature + implementation + callers) `[J1 §7.5, §8.2]`.
3. **Transform receipts** (§9.2) with mandatory blast-radius closure before the increment can close.
4. **Contract-first for interfaces**: an increment that changes a cross-boundary interface must reference a `CON` note (new or superseded) in `[K]`; the review cell checks the diff against it `[MB §5.5]`.
5. **Equivalence evidence** in the receipt: "same tests, same counts, same goldens @s_n vs @s0" is the acceptance form for pure refactors; new behaviour requires a new requirement, not a silent extension.

---

## 9. Editing at scale

### 9.1 Anchored compare-and-swap path `[C1 §5.2 + C2 §4.2 → HELM edit]`

Mandatory `expect` (content hash of the file version displayed); anchors unique (exact, then whitespace-normalised); `near` hint disambiguates; hunks must lie inside KNOWN ranges; three nearest candidates with line numbers on a failed anchor; *diff since `expect`* on a stale failure so the retry costs no re-read; ±3-line post-edit views register the new range; all ops preflighted before any write; preimages saved; a mid-batch I/O failure reports the actual partial state and never claims rollback `[J1 §5.2]`. Line-range identities are not offered `[J2 §8]`.

### 9.2 Scripted transform path `[NEW]`

```text
edit([{transform: {script: "<python/node/sed/comby/ast-grep source or path>", scope_glob: "src/**/*.py",
                   why: "rename Router.dispatch → route across call sites"}}])
→ { ok, files_changed: 14, hunks: 31, per_file: [{path, +n −m, version_after}], diff: "#57", syntax: {…}, touched_outside_scope: [] }
```

Semantics: the script runs in the jail against the workspace; the harness computes the diff, snapshots the shadow ref, records preimages, runs inline syntax on every changed file, refreshes atlas rows, and returns a **diff receipt** — a bounded per-file summary plus a recallable full diff. Changed files enter the Workset as `touched-by-transform (NOT SEEN)`; a subsequent anchored edit needs a current read. The scheduler treats the transform as touching all changed files: blast-radius tests (usually the package or full suite) are mandatory before the increment closes, and the review cell receives the full diff id. Codemods should be idempotent and scoped; a transform that changes files outside `scope_glob` is reverted as a unit and reported. This is the path for renames, signature migrations, import rewrites and formatter sweeps — mechanical breadth reviewed as a diff rather than as forty displayed regions.

### 9.3 Reversibility `[C3 §7.2 → HELM T1; C2 → HELM A3]`

`revert:#id` (per edit, from preimages) and shadow-ref revert to any turn; both produce a diff receipt and re-run inline syntax; neither crosses the initial dirty-state record; user branches, index and stash are untouched. Reversibility is what lets the model experiment instead of over-confirming (F6).

### 9.4 Formatters, generators and foreign writes `[J1 §8.2]`

Any `run` whose stamp-after differs from stamp-before is a mutation: the scheduler diffs the tree, refreshes atlas rows, drops affected Workset entries with an announcement, and invalidates checks. Formatter-induced changes are shown as a compact `touched-by-run` line so the model is never surprised by a moved anchor.

---

## 10. Delegation and concurrency

### 10.1 Worth test `[MB §5.3; IM §10.1]`

Delegate only when there is a bounded deliverable and at least one of: exploration that would cost the parent >~10 turns of window (context isolation), genuinely independent review, a disjoint write scope with a stable contract (S3), or a specialized capability. Never delegate a task whose recipient would need the parent's full context and must make tightly coupled decisions. Children return packets, never conversations; the parent owns integration and completion; a child's confident summary is not verified evidence `[IM §10.1]`.

### 10.2 Probe cell (read-only research) `[C1 look(ask) done as a packet; MB §5.4]`

Packet in: question, scope (paths/packages), evidence the parent already has (ids), budget (default 15 turns / 40K tokens `[ESTIMATE]`), required output. Tools: `look` only, own Workset, medium tier. Packet out: `findings[] {claim, kind: observed|inferred, evidence: path:range@hash | #id}`, `searched: {scopes, complete}`, `unresolved[]`, `cost`. The parent receives a ≤ 400-token summary in `[T]`; findings' ranges are *pointers* — the parent must `look` them to make them KNOWN (dedup makes that cheap). Probe findings are also candidates for `NEG` and `BMAP` notes.

### 10.3 Review cell — §8.8.

### 10.4 Writer cells and the integrator (S3 extension) `[MB §5.5, E11; IM §10.1]`

- **Ownership map** from the plan cell: `paths → increment`; overlapping ownership serializes; interface changes are forbidden in parallel increments (they need a `CON`/ADR in the main line first).
- Each writer cell runs in its own worktree with its own shadow ref, contract slice, and acceptance; decisions are never made there.
- **Single integrator** (deterministic + one cell when conflicts need judgement): applies results in dependency order, rejects results whose base or read-dependencies moved (stale-result rejection), re-runs the **combined** blast radius, and only then updates the ledger. A clean textual merge proves nothing semantic; the integrator's checks are what count.
- Global limits: max parallel cells, task-tree budget, depth 1 (writers do not delegate writers), cancellation of superseded children with their cost accounted.

S3 ships off by default and must beat sequential S1 under equal budgets on decomposable tasks before it is enabled `[MB §16.5]`.

---

## 11. Model profiles and routing

### 11.1 Function-based routing table `[NEW framing; MB §7; IM §10.3]`

| Function | Default tier | Escalate when | Never |
|---|---|---|---|
| Plan cell (decomposition, contracts) | high | — | low/medium |
| Main implementing cell | high (capable default) | extra-high after two verified failures of the same increment with different hypotheses | routed down on "looks routine" alone |
| Continuation cell of a red increment | same as the failing cell | see above | — |
| Probe cell | medium | high if findings insufficient twice | — |
| Review cell | high for contract/ADR/test-integrity; medium for routine diffs | escalate on `escalate` verdict | low |
| Extraction / curation | low | medium when lint finds contradictions | — |
| Log shaping, parsing, stamps, hashes | deterministic | — | any model |
| Failure-capsule repair (P2) | low, ≤2 attempts | owner cell | — |

Tiers are versioned data with calibration dates, never code; a model qualifies for a tier through a harness-specific calibration suite (tool-call validity, edit accuracy, test-fix rate), not through catalog benchmarks `[MB §7.2]`. A risk floor (blast radius, hard reversibility, protected paths) raises the tier; a user pin always wins. `(function, tier, outcome)` triples are logged; a learned corrector can be added later without protocol change `[MB §7.5]`.

### 11.2 Escalation ladder `[MB §7.4]`

Verified failure → escalate with the failure evidence attached and a stated change (stronger model, more evidence via probe, narrower increment, revised hypothesis). Repeating the same attempt under a different label is not recovery. At most `budget.attempts` per increment, then `blocked`.

### 11.3 Economics `[MB §7.6; IM §1.2]`

Every invocation — cells, probes, reviews, extraction, retries — is priced by provider accounting (uncached input, cache read, cache write, output) and summed into `cost_per_accepted_task`. Four quantities are tracked separately: bytes transmitted, model-visible input, billed usage, durable state `[MB §11.3]`. Cache-hit rate is a diagnostic, not an objective.

---

## 12. Learning across cells and sessions

### 12.1 Pipeline `[MB §10.2; IM §8.2]`

`trace + Result Packet → extractor (low tier, post-cell, from the archived trace — never the busy cell) → candidates (LES, PIT, BMAP-delta, NEG, SKILL-delta) with evidence refs and scope → admission queue → curator (lint: evidence present, scope bounded, no contradiction, secrets redacted, not a one-off generalization) → admitted → injected by ranking (§6.3) → use tracking → promotion or pruning`.

### 12.2 Rules

- A failed approach is conditional evidence with its conditions, not a permanent ban `[MB §10.2]`.
- Diagnoses, not dumps: `symptom | conditions | attempted | observed | reason | evidence | revision | invalidation` `[IM §8.2]`.
- Executable promotion: a recurring, deterministic invariant becomes a test, linter rule or schema check, and the note becomes a pointer to it; evidence on held-out tasks is required before a rule is generalized `[MB §10.3]`.
- Negative evidence is typed (`NEG` states) with scope and version, so a later cell never reads "not found" as "absent" `[MB §10.4]`.
- Skills: compact procedures with trigger, prerequisites, checks, failure exit and token budget; module-level filtering; loaded on trigger, not every turn; a skill never grants authority and never marks a requirement complete `[IM §8.4, MB §8.5]`.
- Poisoning defences: provenance and confidence on every note, ADR sign-off, contradiction lint, scoped candidates until validated, usage-aware pruning, versioned memory so a regression rolls back independently of code `[MB §12]`.
- Health telemetry: candidate count, admission rate, injection count, used-in-register rate, harmful/stale injections, repeated mistakes — instrument the denominator `[IM §8.3, §8.5]`. An optional embedding server being cold never blocks a cell.

---

## 13. Recovery and durability

### 13.1 Reconcile before retry `[IM §4.3, §4.4; MB §8.4; HELM A4]`

```text
recover(failure):
    classify execution state and completed effects (intent recorded · dispatched · running · effect observed · durably completed)
    if outcome unknown: reconcile workspace/process/external state before any retry
    if transient and safe within budget: bounded deterministic retry
    elif localized and repairable within granted capability: one scoped repair (P2 capsule) and re-verify the original intent
    else: return to the owning cell with evidence and an explicit reason
```

Timeouts after non-idempotent effects never auto-replay; a partial tool chain records completed effects first `[MB §15.2]`.

### 13.2 Failure classes → bounded responses `[IM §4.3]`

Transport/rate limit → backoff within provider budget · invalid tool args → correct the call, keep the hypothesis · stale anchor → re-look the unit, regenerate · build/env failure → repair within scope or record a concrete blocker · behavioural test failure → revise the hypothesis · missing repository contract → missing-complement retrieval or probe · repeated failed hypothesis (same fingerprint) → stop repeating; alternative or independent attempt · truncated model response → provider continuation path; never execute a partial call · unknown outcome → reconcile · budget exhaustion → checkpoint, honest partial. **Failure fingerprints** `hash(normalized error, attempted fix, state)` are campaign-scoped, so equivalent no-progress patterns across cells trigger escalation rather than a fresh cell repeating them `[MB §8.4]`.

### 13.3 Resume protocol `[IM §4.4; HELM §7.6]`

On resume (crash, sleep, new session): load contract, ledger, last register, workset export, live process handles; reconcile the workspace against the last stamp (foreign edits become NOT SEEN and invalidate checks); recompute check validity; compile a continuation cell. The register is never trusted over the workspace. Crash intervals to test: during a command, after a mutation but before its receipt, during a rebuild, after an external effect whose acknowledgement was lost `[IM §13.4]`.

---

## 14. Safety and integrity

### 14.1 Jail and classes `[HELM T8, §7.8; J1 §5.7]`

R (read) and W (write inside workspace + tmp) run without prompts inside the jail; D (writes outside the workspace, protected paths, network-to-shell, sudo, mutation of user git refs, external side effects) ends the turn with a question in interactive mode and is denied or allowlisted by contract in autonomous mode. When no real confinement exists, the mode is labelled `trusted-local` in `[S]` and in the report — never implied to be a sandbox.

### 14.2 Permission ladder `[MB §9.4]`

`patch → local commit (shadow ref or branch) → push → merge → deploy` are separate grants; the contract sets the ceiling; interface-contract changes, data migrations, deploys and new network access always require a human signature by default. The harness reports the highest *authorized* stage reached, never "delivered" for a patch.

### 14.3 Instruction/data boundary `[HELM §7.8, A7; MB §12]`

Every tool result, KB note body and file body enters the window inside harness-owned delimiters; `[S]` states that content inside them is data. Instructions come from user messages and the configured rules file only (`[R]`); no other repository text is an instruction source. Instruction-shaped content inside data is flagged in the envelope. Capability is enforced in the executor regardless of what the model requests; generated scripts inherit the caller's ceiling; secrets are redacted before persistence; late results from superseded cells are rejected.

### 14.4 Threat table (condensed) `[MB §12]`

| Threat | Control |
|---|---|
| Prompt injection via repo/tool content | delimiters; rules-file-only channel; executor-enforced authorization |
| Confused deputy via scripts/transforms | jail; capability ceiling; no network/credential grants from tool generation |
| Secrets in memory | redaction before notes/blobs; env allowlists |
| Unsafe retry | intent journal; reconcile-before-retry; `unknown_outcome` class |
| Stale/forged verification | receipts bound to stamps; executor-assigned status; parsed counts only |
| Runaway spend / doom loops | budgets at cell, increment, campaign; fingerprints; loop gate; cancellation before publish |
| Memory poisoning | admission queue; provenance; lint; scoped candidates; versioned rollback |
| Weakened acceptance | contract outside model authority; test-integrity guard; review |

---
## 15. Observability and economics

**Per cell**: tokens by cache class (uncached input, cache read, cache write, output), tool calls, seconds in tools, checks run by layer, gates fired, rebuilds, turns, boundary reason, manifest. **Per campaign**: cost per accepted task, first-attempt increment pass rate, verified/blocked/cancelled increments, probes and reviews with their cost and whether their findings were used, escalations, human interventions with reasons (missing requirement · scope decision · environment · approval of an external effect · incorrect implementation) `[IM §12.2]`. **Per project**: KB usage rates, retrieval misses, routing calibration triples, MAST-tagged failure distribution, post-merge reverts and churn as deployment outcomes `[MB §13, IM §13.3]`.

Phase tags on every event: `understand · locate · edit · verify · recover · retrieve · compact · delegate · plan · review` `[IM §12.1]`. Packets, receipts, manifests and traces are files — diffable, replayable, agent-readable; emit OpenTelemetry GenAI spans where available `[MB §13]`. Missing usage is recorded as missing, never as zero.

---

## 16. Token and session economy — where the 40 % comes from

### 16.1 Mechanism → saving map

| Mechanism | Saves | Costs / risk | Source |
|---|---|---|---|
| Cell boundaries at increments (fresh compiled context) | the O(N) window of a long session; attention decay | one prefix cache-write per cell; re-orientation if decomposition is poor | NEW |
| Bounded residency (`k` batches + total live-result budget, stubs ~20 tok, recall) | tool-result residency bounded (~16K tokens) regardless of cell length | one cache miss per `k` turns on `[T]` | C1 §7 / HELM, bound per J1 §5.5 |
| Register at the tail + stable `[S][R][K]` prefix | cached reads of the prefix on every turn | none | HELM, C3 |
| Delta + absolute verification lines | a correct edit costs one ~5-token line; a wrong one costs one line per new error | watcher compute (free of window) | C1 §6 amended |
| Transactional turns + fused `run(if: applied)` | a verified cycle in one round trip instead of three or four | none when semantics are explicit | C3 / HELM T5 |
| Fuzzy anchor diagnostics + diff-since-expect | the "invalid edit → re-read → retry" cycle | none | C1, C3 |
| Post-edit views registering ranges | re-reads after edits | ~50 tok per edit | HELM |
| Atlas + focus zoom + sniffed commands | the cold-start grep/glob storm | O(touched) refresh | C5, C3 |
| Whole-file reads refused above budget | multi-thousand-token dumps | occasional second `look` | HELM |
| Workset seeds across cells | re-discovery of the files the next step needs | ≤4K tok per cell | NEW |
| Precision-gated KB injection, BMAP notes | repeated discovery across sessions | ≤1.5K tok per cell; stale-advice risk (mitigated §12) | IM §8, MB §4 |
| Probe cells | exploration noise dies with the probe; parent keeps a 400-token summary | probe cost (counted) | MB §5, C1 ask |
| Scripted transforms | forty displayed regions become one diff receipt | mandatory blast-radius run | NEW |
| Verify-on-stop reuse of valid receipts | ceremonial full-suite reruns | none | IM §9.5 |
| Deferred/masked tool schemas | schema tokens for unused tools without cache breaks | none | MB §8, IM §5.3 |
| Process handles with new-output-only polling | re-injected accumulated logs | none | IM §5.1 |
| Gauge | behaviour regulation for ~20 tok/result | — | C3 |

### 16.2 Session efficiency (round trips)

Target shapes `[ESTIMATE, to be measured]`: cold start = 1 compile + 0–1 `look(tree/outline)` turns instead of ~10–15 exploration turns; hand edit + check = 1 turn; failed anchor = same-turn correction; increment close = 1 verification turn (fused) + 0–1 review; continuation after a crash = 1 compile, 0 model turns lost.

### 16.3 Boundary economics `[ESTIMATE; coefficients from C1 §7.4 as audited by J1 §5.5]`

With cached-read price `ρ ≈ 0.1` of uncached: a 100K-token window costs ≈10K token-equivalents per turn just to be read; a freshly compiled cell of ≈12K costs ≈12K on its first turn (cache write) and ≈1.2K + new content per turn thereafter. A boundary pays back within roughly one to two turns and also removes context rot. The assumption that can break this is decomposition quality: an increment that needs three continuation cells pays three compiles and three re-orientations. That is why decomposition quality (`continuations per increment`, `rebuilds per cell`) is a first-class metric (§19.4) and why pressure rebuild is instrumented as a planning failure.

### 16.4 Output-token discipline `[J2 §8 vs WK]`

The anchor is input, rendered by the harness; the model emits register *patches*, not the register. Typical per-turn output: tool calls + a 30–150-token patch. Full rewrites of the register are rejected by the validator above 400 tokens per patch.

---

## 17. Defaults

| Parameter | Default | Note |
|---|---|---|
| cell turn budget | 40 (soft; nudge at 80 %) | continuation cell on exhaustion |
| `α` pressure threshold | 0.65 of `C_max` | gauge every result |
| `k` eviction batch | 10 turns | ablation: batched vs pressure-only within short cells |
| total live-result budget | 16K tokens | enforced in addition to `k`; oldest refetchable results stub first |
| `m` turns kept on in-cell rebuild | 6 | second rebuild ⇒ `partial` + replan hint |
| `look.budget` / `run.budget` | 1,500 / 1,200 tokens | shared across parallel looks in one turn |
| register cap | 1,200 tokens | acceptance lives in `[K]` |
| fact line | ≤ 240 chars, no code | |
| workset seeds per cell | ≤ 4K tokens | re-served at current versions |
| KB injection | ≤ 8 notes / 1.5K tokens; `CON` for touched paths uncapped | |
| Touched ledger in `[A]` | ≤ 10 files | rest via recall |
| `θ` risk threshold (early slow watcher) | 40 | `Σ Δlines·(1+log2(1+fanin))` `[C1 §6]` |
| full-suite cadence | every 5 verified increments and at campaign end | |
| verification reserve | 15 % of cell tokens and turns | unspendable elsewhere |
| stall | 3 turns without a progress event | |
| loop | identical call+result twice | third ends the turn |
| probe cell | 15 turns / 40K tokens, `look` only, medium tier | |
| review cell | ≤10 `look` calls / 30K tokens, high tier for contract touches | |
| campaign cells | 12 (soft) | user override |
| S3 parallel cells | off by default; max 3 when enabled | |
| timeouts | `run` 120 s; process-group kill; never replay | |

All numbers are declared defaults for the first evaluation round, not derived optima `[ESTIMATE]`.

---

## 18. Implementation plan

### 18.1 Modules and honest size `[ESTIMATE; J1 §7.6 and QA §4.5 warn against "weekend-scale" claims]`

| Module | Contents | ~LOC |
|---|---|---|
| cell runtime | loop, layout, anchor render, gauge, gates, eviction, rebuild, dispatch | 900 |
| register | parser, typed patch ops, validator, conditional ops, cross-cell coherence | 450 |
| workset | range registry, KNOWN/NOT SEEN render, stale drop, export/seed | 250 |
| store | journal, blobs, receipts, stamps, search | 450 |
| look | tree, find, outline/def/refs/importers, capped read, dedup, recall, kb/bmap views | 700 |
| edit | CAS, region check, anchors, atomic apply, inline syntax, views, revert, transform path, scope/test-integrity classifiers | 800 |
| run | jail adapter, classes, shaping parsers, timeouts, handles/poll/cancel, stamps | 700 |
| verification scheduler | registry, triggers, closures, validity, baseline ledger, render, exit gate, reserve | 600 |
| workspace | atlas, tree-sitter tier, import graph, blast radius, focus zoom, shadow git, dirty state | 750 |
| campaign controller | contract, amendments, requirement graph, increments, ledger, cell lifecycle, resume/reconcile, fingerprints | 850 |
| context compiler | selection, seeds, KB ranking, skills modules, coverage, manifest, budget | 500 |
| knowledge base | notes, index generation, queue, curator lint, invalidation, promotion hooks, extraction prompts | 900 |
| delegation | probe/review/writer cells, packets, judge protocol, integrator (S3) | 600 |
| provider adapters | Responses, Messages, compat fallback; item model; usage; continuation | 900 |
| telemetry | phase tags, four quantities, cost accounting, exports | 350 |
| **Total** | | **≈ 10.7K** (plan for 11–14K with tests; Python or TypeScript; no server, no database beyond SQLite, no second model in Stage A) |

Stage A (S0 cell + minimal contract + store + workspace + scheduler-lite) is ≈ 5.5K of that.

### 18.2 Stages and exit gates `[QA §4 staging + IM §14 + MB §17]`

| Stage | Build | Gate before expanding |
|---|---|---|
| **A — Dependable cell (S0)** | contract (auto-derived acceptance), store, shadow ref + dirty state, `look`/`edit`/`run`/`state`, CAS + region-seen, inline syntax, **synchronous** checks at step boundaries, register + gates, gauge, jail/trusted-local label, baseline receipt, exit gate | J1 §8.2 adversarial cases pass as harness tests; stale/ambiguous edits fail safely; user dirty changes survive; a failed command cannot become a green receipt |
| **B — Continuity (S1)** | campaign controller, requirement graph, plan cell, increments, compiler with seeds and coverage, cross-cell fact coherence, stubs/recall, pressure rebuild, resume/reconcile, telemetry | a forced boundary and a crash both resume with exact constraints, open failures and recoverable evidence; continuation cells do not redo verified increments |
| **C — Verification depth & refactor mode** | scheduler with closures and reserve, async fast watchers with Δ+absolute, blast radius, transform path, test-integrity and scope guards, refactor mode | cross-file migrations complete with fewer redundant checks and no lost requirements; transform receipts + blast closure work on a 40-file rename |
| **D — Knowledge & delegation (S2)** | KB with queue/curator/invalidation/injection, extraction, BMAP, probe cells, review cell + judge protocol, function routing table, calibration suite | warm-memory runs beat cold on held-out tasks without stale-advice regressions; review catches injected defects on fixtures; routing saves cost with no complex-task loss |
| **E — Measured adapters & scale (S3)** | language-service adapter, dense retrieval if lexical misses persist, writer cells + integrator, skills promotion, learned routing corrector | each feature passes its ablation (§19.5) or ships off |
| **F — Offline improvement loop** | trace mining, versioned harness changes, matched-budget experiments, promotion/rollback | generalization on held-out repos beats simple baselines `[IM §11]` |

No stage starts before the previous gate is measured. Features that fail their gate remain in the codebase behind a flag with their ablation data attached `[MB §16.6]`.

---

## 19. Evaluation and falsifiability

### 19.1 Objective and comparators `[IM §13; MB §16; HELM §9]`

Primary: `0.60·Q + 0.40·E` under predeclared quality floors, with `cost_per_accepted_task` and the raw metrics reported beside it. Comparators: **B0** plain tool loop (read/write/bash, append-only, summarize-when-full, same model and budgets); **B-HELM** a HELM cell alone (S0 forever, pressure rebuilds); **B1** WAYPOINT S1; **B2** + S2; **B3** + S3. Run isolated additions before combinations; keep model strength constant across arms `[HELM §9]`.

### 19.2 Task suite

Strata: local bug fix · cross-module defect · feature with contract change · API/configuration migration · 40-file mechanical refactor · behaviour-preserving structural refactor · long failure log · ambiguous contract where the correct answer is `blocked` · pre-existing dirty tree with failing tests · forced context pressure · multi-session continuation · task requiring a probe. Repositories: ≥ 50K LOC, several languages, at least one monorepo. Public subsets (SWE-bench Verified, Terminal-Bench) supplement the internal suite; they do not replace it `[IM §13.1, MB §16.3]`. Cold-start and warm-memory runs are reported separately; held-out repositories for transfer.

### 19.3 Adversarial acceptance cases (harness tests) `[J1 §8.2 ∪ MB §15.2 ∪ NEW]`

| Scenario | Required behaviour |
|---|---|
| File changes after inspection; old anchor still unique | reject on `expect` mismatch; return diff since expect |
| Second hunk ambiguous before application | apply none of the batch |
| I/O failure after the first file was replaced | report actual partial state; no rollback claim, no blind retry |
| Initially dirty or staged user changes | preserved through edits, failed checks, reverts; separated in the final report |
| Formatter/generator modifies another file | shown as `touched-by-run`; Workset entries dropped; checks invalidated |
| Failed test wrapped in a successful shell command | runners invoked directly; parsed counts; wrapper exit never equals suite status |
| Logs exceed prompt and capture limits | both limits distinguished; retrieval path exposed |
| Context rollover after rejected hypotheses and user amendments | amendments and scoped dead ends survive into the next cell |
| Multi-file interface migration temporarily fails compilation | `red_ok_until: increment_end`; no forced per-file rollback |
| Required check cannot run | `unavailable` receipt; `blocked` report; no endless gating |
| Acceptance test weakened to pass | test-integrity guard flags; review required; original contract acceptance unchanged |
| External command with uncertain timeout outcome | reconcile; never assume replay is safe |
| Unchanged red state across turns | rendered as "no change · still N" — never silent |
| Continuation cell for a verified increment | ledger prevents re-execution; regression obligation only |
| Probe returns findings for ranges that changed since | pointers marked stale; parent must re-look |
| Two writer cells: clean merge, semantic conflict | integrator's combined blast radius fails; result rejected |
| Review lacks a criterion (rollback requirement omitted) | `insufficient_evidence` naming the criterion |
| KB note references a superseded contract | dependency invalidation flags it before injection |
| Repository file instructs the agent to change policy | treated as data; flagged; authorization unchanged |
| Transform touches files outside `scope_glob` | reverted as a unit; reported |
| Model attempts to remove an acceptance item | recorded as `pending` proposal; never applied autonomously |
| Cell hits reserve with checks outstanding | `partial` with unverified scope named; no "done" |

### 19.4 Metrics

Quality: complete acceptance rate, complex-stratum acceptance, repeated-run reliability, regressions introduced, structural quality (review rubric on held-out diffs), human interventions by reason. Economy: tokens per accepted task by cache class, cost per accepted task, round trips per verified change, repeated-read rate, stale-evidence incidents, overflow/rebuild counts, **continuations per increment**, **rebuilds per cell**, boundary cost share, probe/review cost share, latency p50/p95. Diagnostics: edits to unseen content (must be 0 by construction), stale edit rejections, false-green incidents (must be 0), test-integrity flags and their outcomes, retrieval misses, injection usefulness, routing calibration.

### 19.5 Ablations (one at a time, model constant)

Cell boundaries vs HELM pressure rebuild · workset seeds on/off · Δ+absolute vs delta-only · transform path vs anchored-only on the 40-file refactor · verification scheduler with closures vs fixed cadence · reserve on/off · test-integrity guard on/off (with injected weakening) · KB injection on/off and frozen vs live · probe cells vs in-window exploration · review cell vs none on contract-touching tasks · function routing vs all-high · S3 vs S1 under equal budgets · `k` batched vs pressure-only eviction inside cells · language-service adapter on/off. Decision default: a feature ships enabled only with ≥ baseline quality at ≤ 60 % of baseline cost, or higher quality at equal cost, on the relevant stratum; thresholds are fixed before results are examined `[MB §16.6]`.

### 19.6 What would falsify the central claim

The campaign/cell decomposition is wrong if, at equal model strength and budget, B-HELM (one cell with pressure rebuilds) matches B1 on multi-session and refactor strata with no more lost constraints — i.e., if decomposition overhead and re-orientation cost exceed what bounded, verified boundaries save. The Workset is wrong if region-seen rejections cost more turns than the unseen-edit failures they prevent. The scheduler is wrong if fixed step-boundary checks reach the same false-green rate at lower cost. Each has a measurable arm above.

---

## 20. Deliberately not adopted

| From | Rejected | Reason |
|---|---|---|
| WK `[C5]` | one action per turn; one file per patch; full register rewrite per turn; user text dies after turn 1 | turn tax; output-token cost; highest-value tokens discarded `[J2 §8]` |
| TILLER `[C3]` | "no permission prompts ever"; rollback of a whole transaction on a red run; seven verbs in one `do()` | jail blocks legitimate work; red is information; policy attaches at modality boundaries `[J2 §8]` |
| TRACE `[C4]` | line-range edits; commits on the user's branch; LLM phoenix restart; top-5 facts by undefined relevance | strictly less safe than CAS+region; shadow ref instead; rebuild from validated state; the harness never chooses what the model believes `[J2 §8]` |
| ANCHOR `[C2]` | append-until-pressure with an LLM checkpoint; optional ungated verification | bounded residency and cell boundaries instead; hard gate `[J2 §8]` |
| HELM `[J2 §7]` | law "silence means green"; acceptance inside the model-edited register; `look(ask)` as a detached second model; single-session scope | amended to Δ+absolute; acceptance moved to the contract; probe cells with packets; campaign layer |
| MB `[MB §5.1]` | a standing role roster (analyst, architect, lead, routine dev, tester…) and persona text; role-based tier priors | roles are four cell configurations (plan, implement, probe, review) plus writer/integrator in S3; routing is by function `[MB R3, §14.3]` |
| MB `[MB §8.4]` | failure-capsule repair micro-agent in the baseline | deterministic guards and fingerprints first; capsule repair is a P2 experiment |
| MB `[MB §11.2]` | provider-managed agent runtimes as the architecture | build-vs-buy comparators only; owning the loop is the point |
| IM `[IM §10.4, §11.5]` | learned action vetoes, speculative macro execution, dynamic harness generation, model training in the baseline | P3 research investments; require volume, calibration and isolation the baseline does not have |
| IM `[IM §7.4]` | dense/vector retrieval and knowledge graphs by default | lexical + symbol + BMAP first; dense only after measured lexical misses |
| general | LLM summarization as the primary compaction; graph databases; vector stores; queues; service meshes; mid-session tool-schema mutation; autonomous push/merge | evidence and economics in the sources; every one is an extension seam, not a foundation |

---

## 21. Open questions and risks

1. **Decomposition quality is the load-bearing assumption.** If plan cells mis-size increments often, boundary overhead dominates. Mitigations: continuation cells reuse seeds; `continuations per increment` is a first-class metric; the plan cell can be re-run with the failure evidence. `[HYPOTHESIS]`
2. **Async watchers vs synchronous checks.** Stage A ships synchronous checks at step boundaries; async delta watchers arrive in Stage C only with a tier-2 adapter that makes them fast. Whether the async path is worth its supersession machinery is an ablation `[QA §4.4]`.
3. **Batched eviction inside short cells.** With ≤ 40-turn cells the `k`-batch may be unnecessary; pressure-only eviction is the alternative arm `[J2 §9]`.
4. **Test-integrity classifier precision.** Per-language heuristics will produce false flags; the cost is a justification line and, rarely, a review. Measure the flag rate and its outcomes.
5. **Transform path safety.** Codemods can be wrong at scale; the mandatory blast closure and review mitigate, but a subtly wrong rename that compiles and passes tests is the residual risk — the same risk a human codemod carries.
6. **KB staleness and injection harm.** Dependency invalidation covers explicit references; implicit dependencies decay silently. Confidence decay and usage-aware pruning are tunable; the on/off ablation is the guard.
7. **Function routing under tier drift.** Cheap frontier-class models move tier boundaries every generation `[MB §14.3]`; the table is data with a calibration date, and the "never routed cheap" list is conservative by design.
8. **How much of `[K]` should be seeds vs notes** for a given increment — answer from manifests and retrieval-miss logs, not from intuition.
9. **Interactive ergonomics.** The user should see the contract, the ledger and the current register as the progress report; whether that is enough transparency for autonomous mode is a UX question outside this document.
10. **Harness bugs devalue evidence.** An evidence-bound design is only as trustworthy as its stamps, parsers and gates; the adversarial cases in §19.3 are therefore *harness* tests first and agent evaluations second `[QA §2]`.

---

## 22. Traceability: mechanism → source

| Mechanism | Source(s) | Label |
|---|---|---|
| Three tools with widths; whole-file refusal; dedup; stubs + recall; k-batched eviction; anchor at the tail; entry/exit/stall/loop gates; result delimiters; repo prime | C1 → HELM §7.2, §7.4, §7.6, §7.7 | BASELINE |
| Mandatory `expect`; region-seen; source stamps; dirty-state record; timeout/replay policy; find completeness; evidence-category lines; rules-file-only channel | C2 → HELM §5.3, A1–A7 | BASELINE |
| Shadow ref; h/v/x facts; gauge; executable acceptance + hard gate; conditional ops; tripwires; refetchability order; jail baseline | C3 → HELM T1–T8 | BASELINE |
| KNOWN/NOT SEEN; version invalidation of live reads; inline syntax; dead ends; code-free facts; focus zoom; error-policy table; soft turn budget | C5 → HELM W1–W8 | BASELINE |
| Blast-radius tests; red-not-recorded; fact coherence; per-step accept; cancel-with-reason; probes over deliberation | C4 → HELM R1–R6 | BASELINE |
| Δ + absolute rendering; synchronous checks first; honest LOC; MCP-compatible tool schemas; per-function model seam | J1 §5.3, §8.1; QA §4 | TRANSPLANT |
| Task contract outliving the conversation; requirement ledger; ready frontier; regression obligations; artifact identity; verification reserve; verify-on-stop; staged reduction; observation types; four stores; admission queue; precision gating; skills schema; failure classes; reconcile; phase tags; 60/40 objective; stage gates | IM §3.3, §4, §6, §8, §9, §11–§14 | TRANSPLANT |
| Packets and receipts; five dimensions; shapes S0–S3; deterministic control plane; context compiler + manifest + coverage; three-layer KB; dependency invalidation; negative-evidence ledger; executable promotion; judge protocol with bias controls; verification ladder L0–L5; permission ladder; function/tier table as data; escalation with evidence; security table; MAST map; fixtures; ablation gates; native adapters + four quantities | MB §0–§17 | TRANSPLANT |
| Campaign/Cell/Increment alignment; contract–register–KB ownership split; Workset unification; scripted transform path; verification scheduler with closures; test-integrity + scope guards; refactor mode; function-based routing table; cross-cell fact coherence; carry-forward set; second-rebuild ⇒ partial policy | this document | NEW |

---

## 23. Glossary

- **Campaign** — the long-lived, harness-owned execution of one task: contract, requirement graph, ledger, evidence store, KB slice, workspace state.
- **Cell** — one bounded model loop executing one increment from a freshly compiled context; HELM-class kernel.
- **Increment** — a waypoint: a bounded unit of a requirement with an executable `accept:` or a named evidence kind; the unit of context, verification and checkpoint boundaries.
- **Task Contract** — harness-owned, user-authoritative requirements, acceptance, constraints, exclusions, scope, budget, authorization.
- **Working Register (STATE)** — model-owned, harness-validated plan cursor, facts (h/v/x), dead ends, decisions, open items, focus, next.
- **Workset** — harness-owned registry of `(path, range, version)` rendered in the window; defines KNOWN vs NOT SEEN; seeds the next cell.
- **Seed** — a Workset entry re-served by the compiler in `[K]` at the current version, with its hash.
- **Stamp** — whole-workspace identity (base commit, tracked delta, untracked manifest, environment).
- **Receipt** — immutable record binding a check to a stamp, parsed counts, raw log and limits.
- **Verification scheduler** — registry of checks with closures, triggers, validity and reserve; owner of the exit gate.
- **Baseline receipt / pre-existing-failure ledger** — the initial suite run and the failures it recorded.
- **Transform** — a scripted, jailed, diff-receipted edit for mechanical breadth.
- **Test-integrity guard** — deterministic classifier flagging edits that weaken checks.
- **Probe cell / Review cell / Writer cell** — delegation kinds: read-only research; clean-context judge; parallel implementer (S3).
- **Integrator** — the single authority applying S3 results, rejecting stale ones, re-verifying combined state.
- **Result Packet** — the typed output of a cell; the only thing the controller reads.
- **Manifest** — the record of what a cell's compiled context contained and omitted.
- **KB** — the three-layer knowledge base (index → notes → raw) with admission, lint, invalidation, promotion, pruning.
- **BMAP / NEG / CON / ADR / LES / PIT / SKILL / STATUS** — note kinds (behaviour map, negative evidence, contract, decision, lesson, pitfall, skill, checkpoint).
- **Gauge** — the ~20-token status line closing every tool result.
- **Shape S0–S3** — one cell · campaign · + probe/review/routing · + parallel writers.
- **Function routing** — tier assignment by harness function, not by role title.
- **Δ + absolute** — the verification render rule: what changed and what is true now, with a version tag.

*End of WAYPOINT proposal — v1.0, 2026-09-19. Proposal, not a validated system; every number is a declared default or an estimate.*
