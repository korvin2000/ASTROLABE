# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
History: `audit/SESSION-HISTORY.md` (never read at startup).

**Checkpoint:** local Windows session, branch `claude/p3-2-impact`, fast-forwarded into `main` at every gate. **150/185 DONE.**
P3 is complete (25/25, phase gate CI 36059635140). P4 is 9/25: P4.1 (gate CI 36066888792), P4.2 (gate: see TODO), P4.4.1,
P4.5.1, plus the earlier kernels P4.5.4/P4.5.5. Start the next session from `main`.

## Next block (queue order, Deps govern)
1. **P4.3.1–P4.3.2** `Skill` notes / module filtering, `BMAP` notes + `look(bmap)` (unmask `look.bmap`). Then the **P4.3 gate**.
2. **P4.4.2–P4.4.6** probe cell, `ReviewCell` + judge protocol (two scopes), `QaCell` contract, worth estimate, role
   texts + packet validators. The `Delegator` (P4.4.1) exists but `Controller` does not construct one yet: wire it with
   the probe/review child runners here. Then the **P4.4 gate**.
3. **P4.5.2–P4.5.3** escalation ladder, cache-aware scheduling; **P4.6.1–P4.6.4** recovery; **P4.7.1** mounts;
   **P4.8.1–P4.8.2** fixtures, then the **P4 phase gate**.

## Carried-forward debts (owner task in bold)
1. Plan packets carry no register decisions (intake register supplier `null`): **P4.4.6**.
2. Replan and `increment_split` routing to the plan role not wired (D-70), manifests never say `replan` (D-71): **P4.4/P4.6**.
3. Opening a new attempt of existing work: **P4.6.4**. Red full suite not compared with a pre-existing-failure ledger in S1: **P4.6**.
4. S0 finish runs no full suite and no campaign gate (D-66). `Economics` takes ρ from the caller; B2 ≥ B1 `UNMEASURED` (D-28).
5. `RequirementGraph.ledger` still requires stamp equality; reuse proofs reach it only through re-acceptance.
6. The `risk > θ` layer trigger is unwired (projection exists: `ImpactProjection.slowChecksEarly`): **P4.5.2**.
7. The tier-0 import graph is `Lexical`, so blast selection always widens to the package or workspace suite (D-88, D-93):
   **P5.4** (tree-sitter).
8. KB: the controller never calls `Curator.admit` (admission cadence is host-side); the extractor takes its tier from the
   §11.1 row without `Router.selectProfile`; extraction cost is journaled, not priced; promoted tasks are returned, not
   filed: **P4.4.5/P4.5.2**. `RoutingPacket` stands in for `TaskPacket` and the `CalibrationLog` is in memory: **P4.4.2/P4.5.3**.
9. Contract touch is live since P4.1: an S0/S1 cell touching a `CON`-anchored path stops `blocked_external` at the
   pre-scan refresh (D-94, D-103). The controller's `Verifier.accept` does not re-check impact nudges (D-92).
10. Transient and recorded: `StamperTest` `git exited -1`; Windows `ProcOwnershipTest` launcher READY timeout.

## Blockers
None. D-87–D-111 are local choices (owner review suggested: D-97 transforms in every shape, D-106 review depth).
No owner decision is pending and no out-of-order card is ACTIVE. `gh` is not installed locally: gates push to `main`
(owner-authorized) and CI is polled through the public Actions API.
