# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
**Active program: ASTROLABE 2.0** — plan `../ASTROLABE-2-PLAN.md` (changes only via its §17), status = phase P8 in
`TODO.md` (`rg -n '^#### P8' TODO.md`), cards/reports `../plan2/`. Next session: prompt A.2 of the plan with S3.
Owner 2026-10-03 (plan §11 №12–13, §17): KB is configurable, not frozen; reserve group P8.G (G1 KB off/live run, G2 small project memory) after D and E; D5's confirmation set gets "second task in the same project" pairs (P8.D.5 Build).

**Checkpoint (2026-10-03, S2 done):** P8 **17 DONE / 19 TODO** (incl. P8.G ×2), none in progress. Merged `--no-ff`:
B2 (B2a + B2b, D-388), C9 (D-390), B1 (+ B1r, D-391), C8 (D-392), C1a (D-394), C2 (D-396). B4 + replication (128 runs) +
two ablations → every wave-A line kept (D-393); benchmark method D-395; owner defaults №9a, №3 (D-389).
Reviews: Fable ×2 each on C9, C8, C1a, C2; Codex — B1 ×2 (math), B4 (statistics); all fixed or listed below.

## Next (S3 per plan §7)
1. C1b → C3 → C4 (ships with C2: provenance labels; owner decides whether a judge-only approval is independent —
   `AcceptanceLine.verifiedBy`) ∥ Dp1, Dp2 (direct spec, t6). Owner §11 №2–4, 6, 7 default to the plan (D-389).
2. First: CI 37081214729 (S2 push with C2) green? A red run reopens only the tasks it implicates.

## Tails from S2 (owner task in brackets)
- C1a: a live background handle during stop verification is invisible to `Scheduler.exclusive` [C1b/C3]; `lostPin`
  wording; tests for the `make -i` refusal and v1.0 constructors; `nmake /I /K`.
- C2: a model check without `requirementIds` strengthens every requirement [C1b]; judge-only independence [C4].
- C9: a key block begun > `maxBytes` before the cursor; a handle-less slice keeps the body's mask; a `wait` tail may
  show half of a single-line secret. C8: `router.record`/cadence/S1 boundary not redone after a crash; S3 writers.
- B1/E1: per-result sizes in events for an exact Residency replay. D5: the set did not exercise A1 (small window), A4,
  A5 (background commands); pin the upstream, adjacent AB/BA blocks (D-395).

## Debts, branches, leftovers
Frozen debts (plan §12): D-254, D-70/D-71/D-241, D-252, D-113/D-120, D-124/D-244, D-200; D-66, D-28, D-92. Transient:
`StamperTest` `git exited -1`; Windows `ProcOwnershipTest`; FX-22.
Kept branches: `v2/BL` (baseline), `v2/B4` (base-arm shim `00ff898`), `v2/B4a3` (A3 ablation arm — never merge). Bench
`../bench/b4-2026-10-02/` (not in git). Unregistered dirs in `.claude/worktrees/` held by closed sessions
(`wonderful-yalow-*`, `jolly-visvesvaraya-*`, `bold-leavitt-*`, `b4-nokey`, older ones): `Remove-Item -LiteralPath '\\?\<path>' -Recurse -Force`.

## Last gate / blockers
Gate P8.B closed: CI 37078198525 green at `c592289` (Ubuntu + Windows). S2 push `d7ab99d` (adds C2): CI 37081214729 — check first. No blockers.
`gh` not installed; CI via `curl -s https://api.github.com/repos/korvin2000/ASTROLABE/actions/runs?branch=main`.
`.llm-memory`: 26 entries stale by content, no missing paths — re-verify a card when its topic is touched.
