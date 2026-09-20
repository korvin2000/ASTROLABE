# Architecture overview

**ASTROLABE 1.0.1 · specification** · Owner: System architecture.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §0, §0.1, §0.4, §0.6. **Read with:** [components](components.md) · [principles](principles.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F12](../../REVIEW.md#f12).

<!-- source-section: 0 -->
<a id="sec-0"></a>

## 0. Executive summary
<!-- end-source-section: 0 -->

<!-- source-section: 0.1 -->
<a id="sec-0-1"></a>

### 0.1 Thesis

The three candidate proposals were written from the same corpus and converge on a settled kernel: a HELM-class loop with compare-and-swap edits, a model-owned register at the tail of the window, bounded residency, stamped receipts, a hard exit gate, and a harness-owned contract the model cannot weaken. They diverge on three structural questions, and the divergences are complementary rather than contradictory:

- **A (WAYPOINT)** answers *where the context boundary belongs*: at the **increment**, so that the context boundary, the verification boundary and the checkpoint boundary coincide and pressure rebuild becomes an instrumented failure of decomposition rather than the plan. It also mechanizes the guards the others only name — a verification scheduler with input closures, a test-integrity classifier, a scripted-transform path, a refactor mode.
- **C (SEXTANT)** answers *what the complete system looks like as a build specification*: planes with one owner per decision, roles as configurations with tool masks, a deterministic shape-selection policy, a four-horizon coherence protocol, one impact engine feeding four consumers, one rebuild mechanism serving four uses, cache-aware staleness, a recovery ladder, a finish receipt and a kernel contract the model actually reads.
- **B** answers *what must be true for any of it to be trusted and measured*: four identities that must not collapse, evidence states separated from authority and freshness, the crash-window ordering of consequential actions, the arithmetic showing that fewer prompt tokens can cost *more* money when cache reuse drops, provider-specific accounting, routing that refuses to clamp a quality floor to a budget, a predeclared score with a promotion policy and confidence bounds, and twelve external sources checked against their own limits.

ASTROLABE therefore takes **A's skeleton, C's organs and B's nervous system**: the campaign / cell / increment decomposition and its scheduler are the architecture; SEXTANT's planes, roles, tool families, coherence protocol, impact engine and recovery ladder are how the architecture is built; B's identities, evidence model, economics, accounting and evaluation discipline are how it is kept honest. Where the three genuinely conflict ([§20.2](../reference/decisions.md#sec-20-2)) the resolution is argued, not averaged. For this maintenance revision, inherited source-check claims remain provenance rather than a claim that every upstream document was inspected: the supplied A/B/C proposals and the separately listed primary-source checks are distinguished in the source register. Unavailable HELM/candidate files and `SOTA-RESEARCH-NOTES.md` are not treated as newly verified evidence.
<!-- end-source-section: 0.1 -->

<!-- source-section: 0.4 -->
<a id="sec-0-4"></a>

### 0.4 Design commitments

1. **Two clocks, one unit.** A deterministic **campaign controller** owns durable state and selects increments from a requirement graph; a **cell** (HELM-class loop) executes one increment from a freshly compiled context and ends by semantic completion. Context, verification and checkpoint boundaries coincide. `[FROM-A]`
2. **Three memories, three owners, four identities.** Task Contract (harness-owned, user-authoritative), Working Register (model-owned, harness-validated), Knowledge Base (curated, evidence-linked). Every record carries `work_id / attempt_id / candidate_id / context_id`. `[MERGED A+B]`
3. **One version registry, five horizons.** Live reads, register facts, knowledge notes, receipts and delegated results all carry `(path, version)` anchors and obey one rule: *mark stale, never serve as current, never delete the evidence.* Receipts join the protocol through input closures. `[MERGED C+A]`
4. **Signal on delta, state the absolute.** Every verification line carries current absolute status, scope and version. Silence is never green. `[BASELINE, J1 §5.3]`
5. **Two edit paths, one precondition.** Anchored compare-and-swap inside the Workset for hand edits; a jailed scripted transform with an inventory, expected counts, a diff receipt and mandatory blast-radius closure for mechanical breadth. `[MERGED A+B+C]`
6. **Verification is a scheduler, not a checklist.** Checks with input closures, cost classes, triggers and validity; a reserve that cannot be spent on generation; verify-on-stop reuses valid receipts with a reuse proof; a hard exit gate; independent review at two scopes. `[MERGED A+B]`
7. **One impact engine, four consumers.** Deterministic impact analysis over an edit set drives verification depth, the routing risk floor, the shape decision and the human-anchor policy, and fires the impact nudge. `[FROM-C]`
8. **Roles are configurations; shapes are policy.** A role is a context view × tool mask × permission set × tier prior × duties. Shape S0–S3 is chosen by a logged deterministic policy and upgraded only on traced evidence. Decisions are never made in children. `[FROM-C, MB]`
9. **Routing by function, refusal over clamping.** Tiers attach to harness functions, never to titles; an unaffordable quality floor narrows the unit or checkpoints — it never lowers the floor. `[MERGED A+B+C]`
10. **Learn by admission, promote to code.** Post-cell extraction → admission queue → curator lint → precision-gated injection → usage-aware pruning → executable promotion. `[BASELINE]`
11. **Deterministic control plane; cheapest competent handler.** Budgets, hashes, stamps, gates, leases, permissions, loop detection and retry exhaustion are code. `[BASELINE]`
12. **Collapsibility and paid-for layers.** S0 is one cell with a two-line contract. Every layer above it ships off until its ablation shows a credible gain under the predeclared promotion policy. `[BASELINE, B §15.5]`
<!-- end-source-section: 0.4 -->

<!-- source-section: 0.6 -->
<a id="sec-0-6"></a>

### 0.6 The one diagram

```text
 user / issue ─────▶ ┌──────────────────── CONTROL PLANE — CAMPAIGN CONTROLLER (deterministic) ─────────────────────┐
 amendments          │ Task Contract · requirement graph & ledger · increments (ready frontier) · shapes S0–S3 ·        │
                     │ budgets · leases · cancellation · authorization ladder · routing policy · cache-aware scheduling  │
                     └───────┬─────────────────────────────────────────────────────────────────────▲───────────────────┘
                compile(inc) │                                                      Result Packet │ + receipts
                             ▼                                                                    │
      ┌──────── CONTEXT PLANE ────────┐                                     ┌───────── VERIFICATION PLANE ───────────┐
      │ context compiler: mandatory → │                                     │ scheduler: checks · closures · stamps ·│
      │ contracts → seeds → notes ·   │                                     │ validity · reserve · verify-on-stop ·  │
      │ coverage assertions · manifest│                                     │ impact engine · ladder L0–L5 · judge · │
      │ [S][R][K] render · one rebuild│                                     │ exit gate · integration re-verify      │
      │ (pressure/resume/role/alt/end)│                                     └───────────────────▲────────────────────┘
      └───────────────┬───────────────┘                                                         │ receipts
                      ▼                                                                         │
 ┌────────────────────────────── EXECUTION PLANE — THE CELL (one increment, bounded window) ────┴────────────────────┐
 │ [S] system · [R] repo prime + knowledge · [K] compiled increment context · [T] transcript · [A] anchor              │
 │ registers: contract digest (read-only) · STATE (typed ops) · Workset KNOWN/NOT SEEN · effects ledger               │
 │ tools: look · edit · run · verify · state · task · kb   (byte-stable per role; masked, never removed)              │
 │ runner: effect classes R/W/D · trusted-local | confined · bg handles · intent journal · shadow ref · gates · gauge │
 └──────────┬───────────────────────────────┬──────────────────────────────────┬────────────────────────────────────┘
            ▼                               ▼                                  ▼
 ┌─── WORKSPACE ───────────┐   ┌─── EVIDENCE STORE ──────────┐   ┌─── KNOWLEDGE PLANE ─────────────────────┐
 │ version registry · atlas│   │ journal · blobs · stamps ·  │   │ index → notes → raw · curator · lint ·   │
 │ symbol/import index ·   │   │ receipts · manifests ·      │   │ dependency invalidation · skills · BMAPs │
 │ shadow ref · dirty state│   │ packets · four identities   │   │ admission queue · promotion · pruning    │
 └─────────────────────────┘   └─────────────────────────────┘   └──────────────────────────────────────────┘
 ┌─── RECOVERY & ROUTING PLANE ────────────────────────────┐   ┌─── PLATFORM PLANE ─────────────────────────┐
 │ failure classes · guards → fingerprints → hint → capsule │   │ adapters (Responses · Messages · compat) · │
 │ → escalate · alternative attempt · tier table · effort   │   │ MCP mounts · accounting by cache class ·   │
 │ per call class · calibration                             │   │ observability · security · evaluation runner│
 └──────────────────────────────────────────────────────────┘   └────────────────────────────────────────────┘
 Shapes: S0 one cell · S1 campaign of cells + KB · S2 + probe/review cells + routing · S3 + parallel writers + integrator
```
<!-- end-source-section: 0.6 -->

