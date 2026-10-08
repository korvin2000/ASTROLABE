# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
**Active program: ASTROLABE 2.0** — plan `../ASTROLABE-2-PLAN.md` (changes only via its §17), status = phase P8 in
`TODO.md`, cards/reports `../plan2/`, results `../session_5_results.md`.

**Checkpoint (2026-10-08, session 5 closed):** wave D done — D7 (crash windows, retention by role), C18 (module gaps,
`SettingsReachabilityTest`), D4 (goldens of 9 roles, 15 direct fixtures, Studio `protocol`), E1 + E1w (binding key, store **v7**,
`routing_log`), B6 (8 long tasks, pair mode), C17 item 1 + P2 tails, W11 (T-03 live 3014 → 1509), integration review (4 P1 → WR5/WR5s),
WG2, GLM/GLM2 (D-444), D5 (**not proven → structured stays default, D-445**). Gate P8.D ticked, tag `v2-wave-D` at `ebc8c47`
pushed — **its full CI suite was not awaited: read it first** (tag `v2-wave-W` had 2 Linux + 1 Windows failures, fixed by WAF2).
P8: 53 DONE / 21 TODO / 1 IN_PROGRESS (C17 item 2). Tail ledger `../plan2/reports/TAILS-5.md`: 33 → 0 open (24 new, all decided).

## Next — session 6 (plan §7: H0 → owner decisions → H4 → H1), plus the D5 buys if the owner wants them
1. Restore (§8.7); read the CI result of tag `v2-wave-D` through the built-in browser (owner signs in; raw log via the job menu).
2. Owner decisions pending: D-445 (default protocol stays structured — confirm or override); P8.W.12 (one repository-form probe per
   phase changes nothing semantic but is the open's cost); the D5 buys ranked in `../plan2/reports/D5.md` §5 (clean glm screening 48
   runs ≈ 2 h; rest of the long stratum 22 runs ≈ 3 h; crossover pilot 24–36; direct ablations) — commands in `../plan2/bench/D5-RESUME.md`.
3. Scheduled tasks for session 6: P8.C.19 (11 settings without a consumer, with H0's role/tier decisions), P8.D.8 (grant renewal window,
   STATUS recovery), P8.W.12; P8.H.5 takes T-47, T-57 (Studio).
4. Cleanup: the merged worktree directories under `.claude/worktrees/` (B6a, B6b, C17, C18, D4, E1, E1w, GLM, W11, WG2, WR5, WR5s,
   d5bench) and root `.claude/worktrees/{D4,GLM,WR5s}` resisted deletion (locked by line processes) — `Remove-Item -LiteralPath '\\?\<path>'
   -Recurse -Force` then `git worktree prune`; branches are merged and deleted except the kept ones.

## Owner rules (unchanged)
- **Never launch or call git credential manager / `git credential`.** Push with `git -c credential.helper= push`; CI and GitHub through the
  built-in browser, the owner logs in. The session's permission classifier may block a push — the owner lifts it in chat (2026-10-08).
- Optimistic mode: targeted tests, no local full suite, one integration review per batch of lines (Codex `--model gpt-6.1-sol
  --effort xhigh`, read-only); Codex-W for estimators; hard problems Fable xhigh or Codex. Weekly stop 95 % (99 % last day).
- One background Gradle task per checkout (two in one checkout broke a merge check again). Live runs: cheap models only, `--keep-workspaces`
  when raw arguments may be needed; a `blocked_external` run now keeps its state.
- Hot files: `campaign/Controller.kt` and `cell/Cell.kt` next owner — H1 (session 6); `TaskService.java` — H5.

## Tails carried (ledger has the decisions)
- P8.C.17: T-19, T-26, T-27, T-35, T-53, T-54, T-55 (P3), item 2 (wrapper substitution). P8.C.19: 11 dead settings + 5 deferred reachability rows.
- P8.D.8: review P2 №5, №6. P8.W.12: form probes, 38 vs 35 git. P8.H.5: T-47, T-57. P8.H.0: glm plan-role stall (T5-56). P8.H.2: loop fairness
  (`Reserve`, `LoopContainment`). P8.F.3: auditor context/input breakdown (T-32). SDK: `optString("arguments")` drops object arguments.
- D4 tails → P8.H.1: `(pending)` after a decided amendment, `AgentEvent.Blocked` on a lifted block, «poll the handle», A-D.5 rows 1–2.

**Kept branches** `v2/BL`, `v2/B4`, `v2/B4a3`, `v2/C10x` (never merge). Live baseline for later sessions: `../plan2/reports/SESSION-5.md` gate table (`bench/wd`).
