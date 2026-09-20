# Glossary

**ASTROLABE 1.0.1 · reference** · Owner: Terminology.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §23.2. **Read with:** [components](../architecture/components.md). Load companion sections only when the task crosses that boundary.

<!-- source-section: 23.2 -->
<a id="sec-23-2"></a>

### 23.2 Glossary

- **Campaign** — the long-lived, harness-owned execution of one task: contract, requirement graph, ledger, evidence store, KB slice, workspace state; owned by the campaign controller (C's supervisor).
- **Cell** — one bounded model loop executing one increment from a freshly compiled context; HELM-class kernel; `context_id`.
- **Increment** — a bounded unit of a requirement with an executable `accept:` or a named evidence kind that produces an artifact or resolves a named uncertainty; the unit of context, verification and checkpoint boundaries.
- **Attempt** — one execution of a campaign under a frozen harness and profile policy; alternative attempts are new attempt ids.
- **Candidate / stamp** — whole-workspace identity: base commit, tracked delta hash, untracked manifest hash, environment id.
- **Task Contract** — harness-owned, user-authoritative requirements, acceptance (run / check / review with origins), constraints, exclusions, scope, budget, authorization; amendable only by authority.
- **Working Register (STATE)** — model-owned, harness-validated plan cursor, facts (h/v/x), dead ends, decisions with probes, open items, focus, amendments, next.
- **Workset** — harness-owned registry of `(path, range, version)` rendered in the window; defines KNOWN vs NOT SEEN; seeds the next cell.
- **Version registry** — `version(path)`, `displayed(path, v)`, `stamp(tree)`; the one component behind the coherence protocol.
- **Coherence protocol** — mark stale, never serve as current, never delete evidence — across five horizons: reads, facts, receipts, notes, delegated results.
- **Receipt** — immutable record binding a check to a candidate, environment, parsed counts, raw log, limitations and input closure; may carry a reuse proof.
- **Input closure** — the set of paths (or package, or `unknown`) whose change invalidates a check.
- **Verification scheduler** — registry of checks with closures, triggers, validity and reserve; owner of the exit gate.
- **Impact engine** — deterministic analysis over an edit set (fan-in, importers closure, affected tests, contracts touched, risk) feeding verification depth, the routing risk floor, shape and human anchors; fires the impact nudge.
- **Transform** — a scripted, jailed, diff-receipted edit with inventory and expected match counts for mechanical breadth.
- **Test-integrity guard / acceptance-surface line** — deterministic classifier flagging edits that weaken checks, rendered as one line and adjudicated against the original obligation.
- **Refactor mode** — behaviour snapshot, `red_ok_until`, transform receipts, contract-first interfaces, equivalence evidence, campaign-scope review.
- **Probe / Review / QA / Writer cell** — delegation kinds: read-only research; clean-context judge; product exercise; parallel implementer (S3).
- **Integrator** — the single authority applying S3 results through a merge queue, rejecting stale ones, re-verifying the combined tree.
- **Result Packet** — the typed output of a cell; the only thing the controller reads. **Finish receipt** — the campaign-level report.
- **Manifest** — the record of what a cell's compiled context contained and omitted, and why.
- **Rebuild** — the one mechanism that starts a context from validated state: pressure, resume, role switch, alternative attempt, cell end.
- **Pre-compilation** — deterministic construction of the next increment's `[K]` during the current cell's slow checks.
- **Calibration prior (`CAL` note)** — per-repository sizing statistics fed to the plan cell.
- **KB** — three-layer knowledge base (index → notes → raw) with admission, lint, invalidation, injection ranking, promotion, pruning; note kinds ADR, CON, LES, PIT, BMAP, NEG, SKILL, STATUS, CAL.
- **Shape S0–S3** — one cell · campaign · + probe/review/routing · + parallel writers. **Role** — a cell configuration.
- **Function routing** — tier and effort assignment by harness function with a never-cheap list and refusal instead of clamping.
- **Effect class R/W/D; execution mode trusted-local | confined** — runtime policy labels verified after the fact; the honest statement of confinement.
- **Gauge** — the ~20-token status line closing every tool result. **Δ + absolute** — the verification render rule.
- **Four quantities** — bytes transmitted, model-visible input, billed usage, durable state.

---
<!-- end-source-section: 23.2 -->

