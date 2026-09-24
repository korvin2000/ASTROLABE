# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
History: `audit/SESSION-HISTORY.md` (never read at startup).

**Checkpoint:** branch `claude/compassionate-cerf-j4qunn`. **P2 is complete** (30/30): the P2.2 remainder and P2.7 gate
together in korvin2000/ASTROLABE#5 (see TODO `Gate P2.2`/`Gate P2.7` for the run link and merge commit). P3.1.1 DONE
after the gate commit. 120/185 DONE overall. Start the next session from `main` once #5 is merged, else from this branch.

## Next block — P3.1 scheduler (then P3.2 impact runtime)
1. **P3.1.2** validity/applicability/reuse proofs.
   - Attach a `ClosureManifest` (P3.1.1, `verify/Closures.kt`) to each receipt at run time in `Scheduler.runCheck`: tree = atlas rows, `versionOf` = registry.
   - In `Checks.refresh`, a moved stamp stays `current` with `reuse_of` only when four things hold:
     - the manifest is `Complete`;
     - its digest is unchanged;
     - the definition, argv/cwd/selector and parser are unchanged;
     - the env id and fixtures are unchanged.
   - Anything else is `stale`, and an unknown closure reruns. Tests: FX-16, FX-54, and an added test file invalidating a package closure.
2. **P3.1.3** verify-on-stop reusing proven receipts.
3. **P3.1.4** layer wiring and shapers.
4. **P3.1.5** D-45 isolation.
5. **P3.1.6** `unavailable` receipts.
6. Then the **P3.1 gate**.

## Carried-forward debts (owner task in bold)
1. S1 compiles pass no KB notes/contracts index (`EmptyKb` in `Controller`): a host note store is **P4.1**.
2. Plan packets carry no register decisions (intake register supplier is `null`): **P4.2**.
3. Replan / `increment_split` routing back to the plan role is not wired (D-70); manifests never say `replan` (D-71): **P3/P4**.
4. Opening a new attempt of an existing work (new contract attempt id): **P4.6.4**.
5. A red full suite is not compared with a pre-existing-failure ledger in S1: **P3.1**/P3.6.2.
6. S0 finish runs no full suite (D-66); `Views` has no finish-receipt projection (export + blob only).
7. `Economics` takes ρ from the caller (default 0.1), not the profile price table; B2 ≥ B1 `UNMEASURED` (D-28).
8. Recorded, not diagnosed: transient `StamperTest` `git exited -1` under load (`TempRepo.runGit`, no timeout).

## Blockers
None. No owner decision pending (D-68–D-71 local choices). No ACTIVE out-of-order card.
