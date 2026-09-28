# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.

**Checkpoint (2026-09-28):** the bugfix review is complete. `review/bugfix` (from `feature/bugfix` `dba6344`) was
merged `--no-ff` into **local** `main`; **not pushed** — pushing `main` needs owner approval and then CI runs the
full `check` on Ubuntu + Windows. Ledger and per-finding verdicts: `audit/BUGFIX-REVIEW.md`.
P0–P6 remain 185/185 DONE; P7 stays out of scope.

## Review outcome
- 142 findings: 28 corrected by `review(F-nnn)` commits, 52 ACCEPT, 62 NOTE (residual risks, no action), 0 rejected,
  0 reopened. F-034 recheck still holds. `findings.md` carries a `Review fix` line per corrected finding.
- Two tests that already failed at `dba6344` were fixed (ControllerTest interrupted finalization via F-105;
  ResultPacketTest assertion obsolete under F-086 terminal usage).
- Public ABI: `Observed.truncated`, `RunCapture.executionRoot`; core dump regenerated; compile gate green.

## Next (owner)
1. Approve pushing `main`, then watch CI on both OSes (the review ran only targeted Windows tests; Linux-only
   regressions for F-016 locale and the POSIX JUnit report walk are compile-verified only).
2. Decide on the review NOTEs worth follow-up, notably: F-089 (model reviewCell resolves test-integrity flags in S2,
   D-23 names Authority.review), F-097 (tiny read-only allowlist makes `git log`/`rg` W-class), F-024 (default
   150-token digest cap vs ~35+ requirements), F-135 (Integrator swallows cancellation), F-086 (no deadline on terminal).
3. `.github/workflows/process-ownership.yml` triggers only on `feature/bugfix`; retarget or remove after merge.
4. Remove the `bugfix-review` worktree once the merge is confirmed.

## Carried-forward debts (unchanged)
D-254 recovery, D-70/D-71/D-241 replan and S3 re-selection, D-252 retrieval, D-113/D-120 behaviour maps,
D-124/D-244 review evidence, D-220/D-221 eval arms, D-200 QA scheduling, D-66, D-28, D-92.
Transient: `StamperTest` `git exited -1`; Windows `ProcOwnershipTest` READY timeout; FX-22 `bg-end` order.

## Blockers
None. `gh` is not installed locally: CI is polled through the public Actions API.
