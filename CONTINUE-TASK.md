# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
History: `audit/SESSION-HISTORY.md` (never read at startup).

**Checkpoint:** `3681040` on `main` and `claude/blissful-shannon-a5ey09` (P1.9.1 code at `12fc5d7`).
**Last gate:** [CI run 35941844915](https://github.com/korvin2000/ASTROLABE/actions/runs/35941844915) green on
Ubuntu + Windows (JDK 26, `check` incl. `checkKotlinAbi`) at `12fc5d7`. Nothing ungated since then except
workflow files (no code).

## Next block — P1.9 remainder, then P1.11 + P1.12 (phase end, P1 gate)
1. **P1.9.2** campaign open and reconciliation — `Lifecycle.open` + `Transition.Reconciled` (+ `Resumed` on
   reopen of a resumable campaign), saved through `Campaigns`. Carries debt 1 below.
2. **P1.9.3** S0 run + `Compiler` — `Dispatched → Returned → disposition → Committed → Finishing → Finished`;
   S0 maps `Continue` to its `fallback` (D-64).
3. **P1.9.4** lifecycle controls (Deps include P1.11.2 — check before starting) · **P1.9.5** `FinishReceipt` ·
   **P1.9.6** `Astrolabe`/`AstrolabeJava`.
4. Then P1.11.1–P1.11.2, P1.12.1–P1.12.4. `Deps` and TODO §2.4 producer readiness govern, not numbering.

## Carried-forward debts (owner task in bold)
1. **P1.9.2:** open the derived contract after workspace capture, bind `ProtectedPaths`, pass `Sniff` commands
   into `Checks.seed` and approved rules into `Prime`.
2. **P1.9.3:** `Lifecycle.open` and `graph.recordAccepted` require `RequirementGraph.validate(contract)` empty, so
   the `G_single(C)` increment needs `produces`, a run/check acceptance with an evidence kind, full coverage.
3. Exit gate (TODO §3.1): red = a `failed` receipt; inconclusive is missing evidence, not red.
4. **P1.12.2:** recall pointers exist only for `look` results.
5. Packet gaps by design: no `diffstat` (**P1.9.5**), `transforms` empty until P3.3, `waiting` null until P2
   handles (so `waiting_for_process` has no producer yet).
6. Recorded, not diagnosed: transient `StamperTest` `git exited -1` under load (`TempRepo.runGit`, no timeout).

## Blockers
None. No owner decision pending. No ACTIVE out-of-order card (TODO §1.2).
