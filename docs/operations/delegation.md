# Delegation and S3 integration

**ASTROLABE 1.0.1 · specification** · Owner: Delegation dispatcher / verifier-integrator.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §10, §10.1, §10.2, §10.3, §10.4. **Read with:** [roles-shapes](../architecture/roles-shapes.md) · [recovery](recovery.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F02](../../REVIEW.md#f02), [F09](../../REVIEW.md#f09).

<!-- source-section: 10 -->
<a id="sec-10"></a>

## 10. Delegation and concurrency
<!-- end-source-section: 10 -->

<!-- source-section: 10.1 -->
<a id="sec-10-1"></a>

### 10.1 Worth test `[A §10.1; B §11.2; MB §5.3; IM §10.1]`

Delegate only when there is a bounded deliverable and at least one of: exploration that would cost the parent > ~10 turns of window (context isolation), genuinely independent review, a disjoint write scope with a stable contract (S3), or a specialized capability (L3 QA). Estimate the full overhead before deciding:

```text
delegated_cost = context duplication + child generation + tool work + parent interpretation + validation + integration + retries
delegate iff the expected quality / latency / context benefit justifies this cost, under shared budgets,
            stable boundaries and a checkable deliverable                                                   # B §11.2
```

Never delegate a task whose recipient would need the parent's full context and must make tightly coupled decisions. Distinct files are not evidence of independent behaviour. Children return packets, never conversations; the parent owns integration and completion; a child's confident summary is not verified evidence. A Task Packet carries the existing work/attempt/context and increment ids, role, exact applicable requirements/constraints and authority refs, contract version, dispatch candidate/workspace, required evidence and uncertainties, allowed read/write scope, tool/capability ceiling, reserved budget and execution generation. Its Result Packet preserves that dispatch base and runtime-observed dependency versions alongside the final candidate and receipts ([§5.9](../runtime/gates-termination.md#sec-5-9)). A child’s interface assumptions are dependencies even when their files are not in its write scope. Children publish immutable observations even after cancellation if an already-started action produced effects, but cannot integrate a patch or accept work under a superseded generation `[B §11.2]`.
<!-- end-source-section: 10.1 -->

<!-- source-section: 10.2 -->
<a id="sec-10-2"></a>

### 10.2 Probe cell (read-only research) `[A §10.2; C investigator]`

Packet in: question, scope (paths/packages), evidence the parent already has (ids), budget (default 15 turns / 40K tokens `[ESTIMATE]`), required output. Tools: `look`, `kb.search`, `run` (R class only), own STATE, `task.ask` to the parent. Packet out: `findings[] {claim, kind: observed|inferred, evidence: path:range@hash | #id}`, `searched: {scopes, complete, index_coverage}`, `unresolved[]`, `cost`. The parent receives a ≤400-token summary in `[T]`; findings' ranges are *pointers* — the parent must `look` them to make them KNOWN (dedup makes that cheap). Probe findings are also candidates for `NEG` and `BMAP` notes. Parallel probes need only read isolation and are allowed in S2.
<!-- end-source-section: 10.2 -->

<!-- source-section: 10.3 -->
<a id="sec-10-3"></a>

### 10.3 Review cells and QA cells

The review cell is specified in [§8.8](../verification/acceptance-review.md#sec-8-8) (two scopes). The QA cell implements L3 of the ladder ([§8.2](../verification/scheduler.md#sec-8-2)): it drives the product (CLI, HTTP, browser) in a disposable environment, never production, and returns receipts with screenshots or logs as artifacts. Both are packet-in / packet-out and never decide interfaces.
<!-- end-source-section: 10.3 -->

<!-- source-section: 10.4 -->
<a id="sec-10-4"></a>

### 10.4 Writer cells and the integrator (S3) `[A §10.4; C §11; MB §5.5]`

- **When allowed**: the plan cell produced ≥ 2 increments with disjoint write ownership, stable `CON` notes at fixed versions, no interface change in any unit, cheaply checkable results, measured slack — and never for decisions.
- **Ownership map** `paths → increment`; overlapping ownership serializes; interface changes are forbidden in parallel increments (they need a `CON`/ADR in the main line first).
- Each writer cell runs the same kernel in its own git worktree with its own shadow ref, a child contract slice with `base.stamp` and `read_versions`, the implementer mask minus delegation and minus CON/ADR writes, and a budget charged to the parent. A contract question is `task.ask` to the parent, never decided locally. A worktree is edit isolation, not a security boundary `[J1 §5.7]`. Its shadow ref includes the workspace qualifier ([§4.6](../runtime/workspace-editing.md#sec-4-6)), because ordinary `refs/` are shared across Git worktrees; ref updates use an expected old object id. A worker cannot reuse another workspace’s displayed-range authority.
- **Single integrator** (deterministic + one cell when conflicts need judgement):

```text
integrate(result):
    validate result.base == recorded_dispatch_base and current contract/authority/execution generation
    if main.current_stamp != result.base.stamp or any recorded read dependency moved:
        mark stale-for-integration; rebase in the child's worktree and re-run acceptance, or reject with evidence
    merge queue (serialized under destination ownership): capture integration_base → apply patch in an isolated integration candidate
        → combined-tree checker → blast radius over UNION of merged edit sets → all affected acceptance
        → contract lint and required current review
    publish only if main still matches integration_base and authority/generation remain valid
    receipts bind integration_base, patch hash, resulting stamp and environment; controller alone updates ledger
```

A clean textual merge proves nothing semantic; two workers can pass their own tests while changing opposite sides of a protocol incompatibly — in that case the shared decision returns to the main line, and no voting over worker confidence replaces integration `[B §9.5]`. Global limits: max parallel cells (3), task-tree budget, depth 1 for writers and 2 for probes, leases with timeouts, cancellation checked before start and before publication; late results from superseded units are rejected and their spend counted. S3 ships off by default and must beat sequential S1 under equal budgets on decomposable tasks before it is enabled `[MB §16.5; RN R09]`.

---
<!-- end-source-section: 10.4 -->

