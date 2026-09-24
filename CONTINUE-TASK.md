# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
History: `audit/SESSION-HISTORY.md` (never read at startup).

**Checkpoint:** branch `claude/compassionate-cerf-j4qunn`; `main` = `6b6e081` (P1 phase gate + P2.1/P2.3/P2.4/P2.5/P2.6
gates merged via korvin2000/ASTROLABE#2, #3, #4). **P1 is `FIXTURE_VALIDATED`.** P2: 27/30 DONE — only P2.7 remains.
116/185 DONE overall. Start the next session from `main` once PR #5 is merged, else from this branch.
**Last gate:** P2.2 block (P2.2.3–P2.2.6) in draft PR korvin2000/ASTROLABE#5 at `0b0b1c1` — if its CI is green, tick
`Gate P2.2` in TODO with the run link, mark the PR ready and merge it (owner allows merging at gate time).

## Next block — P2.7 (phase end, P2 gate)
1. **P2.7.1** fixtures and crash intervals (L): map FX-11/19/20/22/23/35/42/45/46/49(S1)/51/56/57, IX-03/06/11/17 to
   tests (many exist: `CarryForwardTest` FX-11, `CompilerFullTest`/`RebuildTest` FX-19, `FactCoherenceTest` FX-20/57,
   `ResumeTest` + `VerticalSliceTest` FX-22/23 and the four §13.4 intervals, `NoteHorizonTest` FX-35, graph FX-42,
   `StoreKbTest` FX-46, `RebuildTest` FX-56, `SeedsTest`/`StoredEvidenceTest` IX-06, `ContextAdmissionTest` IX-17);
   add FX-45, FX-49 S1 parameterization, FX-51, IX-03 (human-review substitution), IX-11 in S1.
2. **P2.7.2** scripted long refactor under context pressure (small fake window, several increments, STATUS, seeds).
3. **P2.7.3** economics report from manifests (boundary cost share, continuations, rebuilds, `[A]` share; B2 ≥ B1 deferred).
4. Then the **P2 phase gate** (full build → push → CI both platforms), set P2 `FIXTURE_VALIDATED`.

## Carried-forward debts (owner task in bold)
1. S1 compiles pass no KB notes/contracts index (`EmptyKb` in `Controller`): a host note store is **P4.1**.
2. Plan packets carry no register decisions (intake register supplier is `null`): **P2.7.2** or P4.2.
3. Replan / `increment_split` routing back to the plan role is not wired (D-70): **P3/P4**.
4. Opening a new attempt of an existing work (new contract attempt id): **P4.6.4**.
5. A red full suite is not compared with a pre-existing-failure ledger in S1: **P3.1**.
6. S0 finish runs no full suite (D-66); `Views` has no finish-receipt projection (export + blob only).
7. Recorded, not diagnosed: transient `StamperTest` `git exited -1` under load (`TempRepo.runGit`, no timeout).

## Blockers
None. No owner decision pending (D-68–D-70 local choices). No ACTIVE out-of-order card.
