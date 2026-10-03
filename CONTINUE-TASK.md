# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
**Active program: ASTROLABE 2.0** — plan `../ASTROLABE-2-PLAN.md` (changes only via its §17), status = phase P8 in
`TODO.md` (`rg -n '^#### P8' TODO.md`), cards/reports `../plan2/`. Next session: prompt A.2 of the plan with S4.

**Checkpoint (2026-10-03, S3 done):** P8 **27 DONE / 14 TODO**, none in progress; wave C 14/14. Merged `--no-ff`:
Dp1 (D-398), Dp2 spec (D-399), C1b (D-397, D-400), C3 + C3r (D-401, D-403), C11 (D-404), C14 (D-405), C10 with C12
(D-406); Studio in the root repository: C4 (D-402), C11s, C14s. Lines ran as background sub-agents; Codex and Fable
reviewed every line before its merge. The owner delegated the 14 spec questions (closed) and approved C10 and C11.

## Next (S4 per plan §7)
1. D1 (`Roles.direct`, `kernel-direct/1`, schema subset, `protocol` switch) → D2 ∥ D3. Spec: `docs/reference/kernel-contract.md`
   appendix A-D (+ `rendered-turn.md` §5.10-D, `continuity.md` §6.2). Re-check the path:line table A-D.7 first: wave C
   moved `Controller.kt`, `Cell.kt`, `Gates.kt`, `Resolution.kt`.
2. D3 also owns resumable stops for the direct turn budget (typed `BudgetStop`, D-401/D-405) and the handoff grant.

## Tails from S3 (owner task in brackets)
- C10: the last four fixes were read by the orchestrator only; an `Open` note still moves `[>]` past a held red; runners
  without per-test results and > 2000 passed tests end `unverified` (a follow-up: harness runs emit JUnit XML); receipts
  are read with strict JSON; atomic claims and `Receipt.workspaceId` of `v2/C10x` not ported.
- C11: in S1 a test change approved in I1 is asked again for I2; the old Studio campaigns API stops without a card.
- C14: the contract's money cap does not follow the policy on a reopen; unknown usage counts as the whole budget;
  `StopCode`, `CampaignOutcome`, `BalanceProfile` serialise constant names; Studio opens twice on a continuing reopen.
- C3/C3r: no deadline on a model call; a host wait inside a synchronous child counts as active time; no tests for the
  S2 reviewer in reserve and S3 writers under a limit. Dp1: `Defaults.seedsMaxTokens` not connected.
- Dp2: neighbouring docs change with D1–D3 (list in its report). Studio: e2e not run in S3; backend and frontend ship together. D5: exercise A1, A4, A5 (D-395).

## Debts, branches, leftovers
Frozen debts (plan §12): D-254, D-70/D-71/D-241, D-252, D-113/D-120, D-124/D-244, D-200; D-66, D-28, D-92. Transient:
`StamperTest` `git exited -1`; Windows `ProcOwnershipTest`; FX-22.
Kept branches: `v2/BL`, `v2/B4`, `v2/B4a3` (bench arms, never merge); `v2/C10x` (`e157dc7`, Codex's full regression rule:
sound core, breaks eligibility of every check and the report shapers — do not merge as is), worktree
`.claude/worktrees/agent-a8bf47cef32456645`. Older unregistered dirs there: `Remove-Item -LiteralPath '\\?\<path>' -Recurse -Force`.

## Last gate / blockers
Gate P8.C closed: CI 37154760649 green at `b3eae38` (Ubuntu + Windows), tag `v2-wave-C`; the last push only adds docs — check its run. S3 CI fix: the ripgrep step verifies `rg` and
falls back to the pinned GitHub release (choco exits 0 on a 503). No blockers. `gh` not installed; CI via
`curl -s https://api.github.com/repos/korvin2000/ASTROLABE/actions/runs?branch=main`.
`.llm-memory`: 30 entries stale by content, no missing paths — re-verify a card when its topic is touched.
