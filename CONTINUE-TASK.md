# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.

**Checkpoint (2026-09-28):** the owner-selected fine-tune follow-ups (`fine-tune-fixes.md`, 21 items after the bugfix
review) are merged `--no-ff` from `fix/fine-tune` into `main` and pushed with owner approval. Per-item verdicts:
`audit/BUGFIX-REVIEW.md` § Fine-tune follow-up; `findings.md` carries a `Fine-tune` line per touched finding.
P0–P6 remain 185/185 DONE; P7 stays out of scope.

## This session
- CI on `main` @ 30cc45c was red (8 core tests on both OSes; Windows `index-treesitter` git spawns exited 0xC0000142).
  Fixed first in seven `fix(ci)` commits; StageCCampaignTest is fixed by F-122.
- 21/21 items DONE (F-105 part 2 only: per-question unblocking needs a schema change; cross-version assessment reuse
  deferred). New config: `unknownOutcomeReconciliation` (Host), `Defaults.gitDeadlineSeconds` 600,
  `providerTerminalWaitSeconds` 60, `checkerFallbackTimeBoxSeconds` 120, `digestTokensPerRequirement` 8,
  `digestCapCeilingTokens` 2000. New D-rows D-321–D-325; updated D-261/270/274/276/278/283/287/293/294/297/302/303/
  310/314/316/317/318/320.
- Public ABI grew (Defaults/Config fields, `UnknownOutcomeReconciliation`, `Intent.workspaceConfined`,
  `PathPattern.matches`/`Scope.protects` ignoreCase, `Git.configBool`/`timeoutMillis`, `Checker.run`, `Scheduler`
  `retryCandidates`, `Validator` `referenceMaxChars`); core dump regenerated.
- Incident: a `git bisect run` leaked GIT_DIR into test fixtures, which rewrote `.git/config` and moved `main`;
  repaired with owner approval. Never run fixture tests under `git bisect run`/hooks without unsetting repo env.

## Next
1. Watch CI for the pushed `main` on both OSes; fix failures first. POSIX-only new tests (StamperTest core.fileMode,
   GitTest daemon survival) run there first. Windows `index-treesitter` 0xC0000142 (DLL init) did not reproduce
   locally and its CI log was never archived; if it recurs, suspect runner process pressure from parallel test tasks.
2. Remove worktrees `bugfix-review`, `fine-tune`, `ft-a`…`ft-d`, `bisect2` once CI is green.
3. Possible later D-rows: per-question blocked-work unblocking (F-105 (1)); cross-version assessment reuse.

## Carried-forward debts (unchanged)
D-254 recovery, D-70/D-71/D-241 replan and S3 re-selection, D-252 retrieval, D-113/D-120 behaviour maps,
D-124/D-244 review evidence, D-220/D-221 eval arms, D-200 QA scheduling, D-66, D-28, D-92.
Transient: `StamperTest` `git exited -1`; Windows `ProcOwnershipTest` READY timeout; FX-22 `bg-end` order.

## Blockers
None. `gh` is not installed locally: CI is polled through the public Actions API; job logs need a signed-in browser.
