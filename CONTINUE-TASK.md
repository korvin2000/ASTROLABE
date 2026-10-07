# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
**Active program: ASTROLABE 2.0** — plan `../ASTROLABE-2-PLAN.md` (changes only via its §17), status = phase P8 in
`TODO.md`, cards/reports `../plan2/`, results `../session_4b_results.md`.

**Checkpoint (2026-10-07, session 4B closed):** wave W done — W6 spec `docs/runtime/task-workflow.md` (D-432…D-435),
W7 message kinds and task model, W8 goal apart from tests, W9 carry from the store, W10 (provider side), WAF (red tag
`v2-wave-WA` fixed), WR2/WR2s (11 integration P1 fixed). Gate P8.W ticked, tag `v2-wave-W` pushed — **its full CI suite
was not awaited: read it first.** P8: 46 DONE / 26 TODO. Tail ledger `../plan2/reports/TAILS-4B.md`: 28 → 0 open.

## Next — **session 5**, prompt `../session_5_fix.md` (D7 → C18 → D4 → integration review → C17 item 1 → D5; ∥ E1, B6; W11 if room)
1. Restore (§8.7); read the CI result of tag `v2-wave-W` **through the built-in browser** (owner may need to sign in).
2. D7 first; it also owns T-56: the WF suite is at 179.5 s of 180 — merge `AnswerScenarioTest` into
   `GoalEvidenceScenarioTest` and `UnreadableFileScenarioTest` into `MessageKindScenarioTest` before any new scenario.
3. E1 uses store schema **v7** (W9 took v6 for `packets`) — plan correction for §17 when its card is written.

## Owner rules
- **Never launch or call git credential manager / `git credential`.** Push with `git -c credential.helper= push`; CI
  and GitHub through the built-in browser, the owner logs in.
- Optimistic mode: targeted tests, no local full suite, reviews only after large change sets (one integration review
  per batch of lines; Codex `--model gpt-6.1-sol --effort xhigh`, read-only). Hard problems: Fable 5.1 xhigh or Codex
  `gpt-6-astra` high. Weekly limit stop at 95 % (99 % only on the last day of a window, owner 2026-10-07).
- Sub-agents cannot `git -C` the root repo: the orchestrator creates Studio worktrees (`.claude/worktrees/<ID>` in the
  root) and keeps `ASTROLABE/.claude/worktrees/studio-core` at core `main`. Never link `ASTROUI/frontend/node_modules`.
- A card that changes `campaign/Controller.kt` names `campaign.*` in its L2; parallel owners of a hot file put their
  hot-file hunks last, after the other line merges. WF suite at every merge; no guard is ever loosened.

## Tails carried (with tasks; all others closed or dropped — ledger)
- P8.C.17: T-19, T-26, T-27, T-31 (SQLite >246-char root), T-35, T-47, T-49, T-50, T-51, T-53, T-54, T-55, T-57.
- P8.W.11: T-03 (live open counts 3014 files / 21 MB at 1500 files — ×2 count or read), T-22, T-59, `canonicalise`.
- P8.B.6: T-12 (`eval-live` 2 opens per start), T-14. P8.E.1: T-30 (`session-id` header, live check).
- P8.D.7: T-56. P8.D.5: T-40 (pinned rows after tool results — other provider families). P8.F.3: T-08, T-43.

**Then:** session 6 (H0 → owner decisions → H4 → H1). Kept branches `v2/BL`, `v2/B4`, `v2/B4a3`, `v2/C10x` (never
merge). Live baseline for later sessions: `../plan2/reports/SESSION-4B.md` gate table (`bench/wb2`).
