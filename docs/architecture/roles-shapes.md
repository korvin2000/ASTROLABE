# Roles and orchestration shapes

**ASTROLABE 1.0.1 · specification** · Owner: Campaign controller.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §3.4, §3.5. **Read with:** [components](components.md) · [delegation](../operations/delegation.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F09](../../REVIEW.md#f09).

<!-- source-section: 3.4 -->
<a id="sec-3-4"></a>

### 3.4 Roles as configurations `[FROM-C §4.4; MB §5.1]`

A role is `context view × note scope × skill filter × tool mask × permission level × tier prior × verification duties × ask-back rights × output packet`. Persona text is at most a few operational lines; roles are declared in configuration, not code; a role tag is never a security boundary `[MB A13, R3]`. Effective operations are the intersection of the role mask, enabled shape/stage and current authorization. The plan role cannot spawn a probe in S1 merely because its role table lists that operation; a required unavailable role causes an explicit upgrade at a context boundary or an honest wait/block. Only shared interface/contract decisions are centralized: children may make ordinary local implementation choices within their packet, as already implied by their implementing duty.

| Role (cell configuration) | Runs in | Context view | Tool mask ([§5.4](../runtime/tools.md#sec-5-4)) | Tier prior | Duties | Output |
|---|---|---|---|---|---|---|
| **Plan cell** (planner / architect) | main line only, never a child | contract, prime, GLOBAL + CON + ADR notes, behaviour maps, calibration prior | look, kb, state, task.ask, task.delegate(probe), verify.baseline | high | requirement graph, acceptance proposals, increments with write scopes, ownership map, decision packets, CON/ADR candidates, shape suggestion | contract amendments (proposed), graph, packets |
| **Implementing cell** | the cell runtime | `[S][R][K]` + own `[T][A]` | all families; task.delegate(writer) only in S3 | high by default; medium when risk is low and verification strong | execute one increment to green acceptance; maintain STATE; propose notes | Result Packet + receipts |
| **Probe cell** (investigator) | fresh read-only cell | question, scope, evidence refs | look, kb.search, run (R class only), state (own), task.ask | medium | bounded findings with coverage and completeness | Investigation packet |
| **Review cell** (judge) | fresh context, no proposer transcript | evidence packet | look, verify.tests on an isolated copy, kb.search | high (contract/design), medium (routine) | verdict against acceptance and contracts; findings; `insufficient_evidence` allowed | Judge verdict |
| **QA cell** (tester) | cell or deterministic runner | contract, behaviour under test, entry points | look, run, verify, state | medium | independent cases; L3 product exercise in a disposable environment | receipts, cases |
| **Writer cell** (S3) | own worktree | child contract slice | implementer mask minus delegation, minus CON/ADR writes | by risk | one packet to green acceptance; never decides interfaces | Result Packet |
| **Repair helper** | fresh small context | failure capsule only | failing family + look + run within capsule scope | low | ≤2 attempts: fixed / diagnosis / escalate | ≤100-token diagnosis + optional corrected call |
| **Extractor / curator** | post-cell, helper tier | final STATE, journal digest, diff summary, receipts | kb.* | low | candidate notes with evidence; dedupe; supersession; lint | note candidates |
<!-- end-source-section: 3.4 -->

<!-- source-section: 3.5 -->
<a id="sec-3-5"></a>

### 3.5 Shapes and collapsibility `[FROM-C §4.5; A §3.3; MB §3.5]`

| Shape | Composition | Default trigger | Added cost |
|---|---|---|---|
| **S0** | one implementing cell; contract = request + auto-derived acceptance (sniffed test command) + write scope; KB read-only; no delegation; extraction at finish | ≤ ~3 files expected, one increment, low risk, no `review:` items, no resume expected | `[A]` per turn only |
| **S1** | campaign of ≥2 increments; plan cell; KB read/write through the curator; workset seeds; continuation cells; sequential role switching in one executor | multi-file or multi-session work; anything that must leave durable notes or auditable completion | compile + boundary cost ([§16.3](../economics/costs.md#sec-16-3)) |
| **S2** | S1 + probe cells + review cells (increment and campaign scope) + function routing + escalation ladder + capsule repair + alternative attempts | contract/ADR-touching changes, `review:` acceptance, ambiguous bugs, cheap-tier work needing independent checks | delegate and judge budgets |
| **S3** | S2 + parallel writer cells in worktrees under an ownership map, single integrator, merge queue | ≥2 increments with disjoint write ownership, stable contracts, no interface change in any unit, measured slack | coordination, integration re-verification |

```text
select_shape(contract, impact, plan=None):                        # deterministic, logged; initial pass cannot choose S3
    size = class(requirements, files_estimated, cross_package)    # S | M | L
    risk = max(contract.risk.blast_radius, impact.contract_touch ? high : low, reversibility)
    if size == S and risk == low and no review: items and not resume_expected:  return S0
    shape = S2 if (review: items or risk >= high or impact.contract_touch or ambiguous_bug) else S1
    if plan is not None and plan.units >= 2 and disjoint(write_paths) and no interface change in units
       and contracts stable and measured slack allows and S3.enabled:            shape = S3
    return shape        # upgrade only on traced evidence (pressure in a cell, a probe request, a risk floor);
                        # downgrade aggressively; design decisions and interface changes never run in S3 children
```

**What is active per shape** (the collapsibility contract). Required review obligations, including enabled refactor mode, select at least S2 (or use an explicitly authorized human review); a downgrade never removes an outstanding required check or review. The last row is non-negotiable in every shape: deleting lifecycle controls makes an architecture broken, not smaller `[C F25; MB §15.2]`.

| Component | S0 | S1 | S2 | S3 |
|---|---|---|---|---|
| Cell runtime, registers, coherence, tools, runner, shadow git, exit gate | ✓ | ✓ | ✓ | ✓ |
| Contract | minimal | full ledger + graph | full | full |
| Packets, receipts, manifests | minimal receipt + context manifest; no delegation packet | ✓ | ✓ | ✓ |
| KB read (`[R]` notes, `kb.search`) | ✓ | ✓ | ✓ | ✓ |
| KB write (curator) | extraction at finish | ✓ | ✓ | ✓ |
| Plan cell, increments, seeds, carry-forward, calibration prior | — | ✓ | ✓ | ✓ |
| Probe cells, review cells, routing, escalation, capsule repair, alternative attempts | — | — | ✓ | ✓ |
| Worktrees, ownership map, integrator, merge queue | — | — | — | ✓ |
| P0 lifecycle controls: cancellation, leases, budgets, reconciliation, accounting | ✓ | ✓ | ✓ | ✓ |
<!-- end-source-section: 3.5 -->

