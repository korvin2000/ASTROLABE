# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.

**Checkpoint (2026-10-01):** owner-requested out-of-order **efficiency fix** (`../harness-efficiency-analysis.md`):
live Studio tasks took 40–48 turns and 30–50 minutes for simple requests. Cause: the launcher could not start `.cmd`
shims, tools refused unambiguous calls, gates and the exit rule demanded ritual, snapshots re-read the tree. Owner
directive: the harness helps the model; strictness stays at the acceptance boundary. Decisions **D-363–D-375**
(TODO §3). Branch `fix/efficiency` (thirteen worktree branches, `--no-ff`) merged into `main` and pushed. P0–P6 remain 185/185 DONE.

## This session
- D-363 Windows program resolution (`PATH`×`PATHEXT`, batch via `cmd /d /v:off /s /c`). D-364 `ContentCache` for
  stamps and snapshots; D-374/D-375 fresh stamps at every acceptance, publication and delegation boundary.
- D-365/D-373/D-375 input tolerance: look first page, `range`, recall ids, empty files; edit lifted fields,
  delete+create, unread delete; state leniency; `JsonRepair` for malformed (never truncated) arguments of any family.
- D-366 stall counts work, 80 turns, repair edits on reserve, impact nudges capped and code-only, host block in `[R]`,
  language line (`kernel/2`, `role-texts/2`, `error-policy/5`).
- D-367 binding-only refusal spends no attempt. D-368 open plan steps do not block a proven acceptance (`leftOpen`).
  D-369 acceptance `cwd` is no surface; package scripts are followed one level (D-374).
- D-370 raised and wired defaults; read budget grants what is left, bounded by context headroom.
- D-371 edit batches independent per path group, per-op dispositions, coverage survives own edits.
- D-372 a refused call refuses only itself. D-375 delete/move is W only with proven containment; null sinks per platform.
- Reviews: Codex three passes (strategy; D-363–D-372: 5 bugs + 3 risks; D-373/D-374: 10 findings) — all applied or
  recorded. Live runs through Studio: see the analysis §4.
- Studio (root repo): resume after spent attempts starts a follow-up; unfinished run's recap is labelled unverified;
  agent notes on argv/cmd and `AGENTS.md` as project memory; two settings wired.

## Next
1. "Direct" cell profile for S0 / simple S1: STATE optional, plan and facts derived by the harness from receipts,
   short kernel, compact schemas (Codex design in the analysis §3/§5). The register is the largest remaining tax.
2. Offer the model's own test command as the project's acceptance (review-only contracts end `unverified`).
3. Compile reserve scaled to small windows (`context/Compiler.kt:101`); defaults assume ≥ 64K.
4. Recursive delete of a directory holding links stays D (`node_modules/.bin` on POSIX): relax for `rm`/`rd` only.
5. Residuals: `EffectPolicy` newline in `cmd` is not a separator, redirect targets not checked literal, tmp rule has
   no link check; `Transform` classifies without a probe; dropped fact anchors lose staleness; `BlobStore.holds`
   does not pin against a future GC; fact anchor `version` 64-hex in the register type.
6. Phase-0 residuals unchanged (pending-save crash re-proposal; D-356 branch `phase0/next-from-cursor` not merged).

## Carried-forward debts (unchanged)
D-254 recovery, D-70/D-71/D-241 replan and S3 re-selection, D-252 retrieval, D-113/D-120 behaviour maps,
D-124/D-244 review evidence, D-220/D-221 eval arms, D-200 QA scheduling, D-66, D-28, D-92.
Transient: `StamperTest` `git exited -1`; Windows `ProcOwnershipTest` READY timeout; FX-22 `bg-end` order.
Gotcha: two Gradle runs in one checkout corrupt `build/test-results`; a shared daemon prints another worktree's
failures — trust the checkout's own XML. Worker agents ran in `.claude/worktrees/agent-*`.

## Last gate
Full `./gradlew build` on Windows (JDK 26) at the tip (2026-10-01): green, core 1831 tests. No CI run yet for D-363–D-375 (pushed to `main`, CI triggers on push).

## Blockers
None. `gh` is not installed locally.
