# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
History: `audit/SESSION-HISTORY.md` (never read at startup).

**Checkpoint:** branch `claude/dreamy-fermi-tkxyry`, merged into `main` at the session end. **133/185 DONE.**
P3 is 15/25: P3.1, P3.4, P3.5, P3.6 and P3.7 are complete; the P3.1/P3.4/P3.5/P3.6 gates are green on both platforms.
The P3.7 gate rides the session-end CI run. Start the next session from `main`.

## Next block: P3.2 impact engine, then P3.3 transforms, then P3.8 Stage C validation
1. **P3.2.1** `ImportGraph` and `tests_for`. It was delegated this session, but the worktree agent was lost in a container
   restart before it reported. Start it fresh. Produce into the P3.2.7 kernel types (`atlas/Impact.kt`,
   `ImpactSnapshot.kt`), never parallel types. Mark dynamic imports `complete=false`. FX-37.
2. **P3.2.2–P3.2.6**: `Impact.analyze(E)`, `look(refs|importers|impact)`, the impact nudge and exit-gate binding,
   blast selection (unmask `verify(tests(blast))`, register `CHK-tests-blast`; `Layers.NO_BLAST` then disappears),
   and the pre-scan at open. Then run the **P3.2 gate**.
3. **P3.3.1–P3.3.2** transform path (Deps include P3.2.5). **P3.8.1–P3.8.2** Stage C fixtures, then the **P3 phase gate**.

## Carried-forward debts (owner task in bold)
1. S1 compiles pass no KB notes or contracts index (`EmptyKb`), so the CON and precompile note fields stay empty: **P4.1**.
2. Plan packets carry no register decisions (the intake register supplier is `null`): **P4.2**.
3. Replan and `increment_split` routing back to the plan role are not wired (D-70), and manifests never say `replan` (D-71): **P3/P4**.
4. Opening a new attempt of existing work: **P4.6.4**.
5. A red full suite is not compared with a pre-existing-failure ledger in S1: **P3.8**/P4.
6. S0 finish runs no full suite and no campaign gate, so an S0 refactor campaign gets no review or equivalence (D-66).
7. `Economics` takes ρ from the caller. B2 ≥ B1 is `UNMEASURED` (D-28).
8. `RequirementGraph.ledger` still requires stamp equality; reuse proofs reach it only through re-acceptance.
9. Blast and `risk > θ` layer triggers wait for P3.2.2/P3.2.5. Isolated candidates run only with `Flags.s3Writers` (D-73).
10. Transient and recorded:
    - `StamperTest` `git exited -1`;
    - Windows `ProcOwnershipTest` launcher READY timeout (one re-run passed, CI 36026485370).

## Blockers
None. D-72–D-86 are local choices. No owner decision is pending and no out-of-order card is ACTIVE.
