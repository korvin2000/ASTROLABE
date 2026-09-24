# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
History: `audit/SESSION-HISTORY.md` (never read at startup).

**Checkpoint:** branch `claude/nifty-feynman-wk181l` (draft PR korvin2000/ASTROLABE#1 → `main`). P1.9.2–P1.9.6,
P1.11.1–P1.11.2, P1.12.2–P1.12.3 DONE; 90/185 DONE. Start the next session from `main` once the gate is merged,
else from this branch.
**Last gate:** P1.9 + P1.11 block at `8a5cbb1` — [CI run 35989613165](https://github.com/korvin2000/ASTROLABE/actions/runs/35989613165)
**green on Ubuntu + Windows**; `main` fast-forwarded to `8a5cbb1`. Ungated since then: P1.12.2, P1.12.3, schema v3 rekey, a
`CellTest` race fix and the ABI dump (`0c33262`) — they ride the P1.12 phase gate.

## Next block — P1.12 remainder (phase end, P1 gate)
1. **P1.12.1** harness fixture tests: map each listed FX/AX/IX id to an existing test or add the missing one
   (many exist: FX-22/23/24/25/26/48/49/59, IX-02/18 are covered by Run/Controller/Controls/Accounting tests;
   grep test names for the ids first). Size L — consider a Fable worktree agent per fixture group.
2. **P1.12.4** platform validation (ProcessOwner, file replacement, `WorkspacePath` aliases, encodings) —
   mostly Windows/Linux CI evidence; document supported execution modes and D-44 recovery coverage.
3. Then the **P1 phase gate** (full build → push → CI both platforms), tick `Gate P1.12`, set P1 `FIXTURE_VALIDATED`.

## Carried-forward debts (owner task in bold)
1. Exit gate (TODO §3.1): red = a `failed` receipt; inconclusive is missing evidence, not red.
2. **P1.12.2** done; **P2.2.4** owns full resume (lost cells are only recorded `lost`; a new cell re-verifies).
3. Recall pointers exist only for `look` results (**P1.12.1/P2**).
4. Packet gaps by design: no `diffstat`; `transforms` empty until P3.3; `waiting` null until P2 handles.
5. S0 finish does not run the full suite (D-66); `Views` has no finish-receipt projection (export + blob only).
6. Controller-built cells use `CliSyntax` without interpreters (`not_run`) until the host names them (D-66).
7. Recorded, not diagnosed: transient `StamperTest` `git exited -1` under load (`TempRepo.runGit`, no timeout).

## Blockers
None. No owner decision pending (D-65/D-66 local, D-67 provisional). No ACTIVE out-of-order card.
