# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
**Active program: ASTROLABE 2.0** — plan `../ASTROLABE-2-PLAN.md` (changes only via its §17), status = phase P8 in
`TODO.md` (`rg -n '^#### P8\.W\.' TODO.md`), cards/reports `../plan2/`, diagnostics `../ASTROLABE-DIAGNOSTICS-2026-10-05.md`.

**Checkpoint (2026-10-05, session 4A stage 1 closed):** W0 (D-426), W2 (D-427) and W1 (D-428) are merged and `main` is
pushed. The WF suite `:core:test --tests 'io.astrolabe.workflow.*'` (177–188 s of tests on Windows) runs at every merge (CLAUDE.md
§ Workflow, `../plan2/COMMON.md`). Guards green: WF-2, WF-3, WF-4, WF-5/6/7 (core part). Registry:
`docs/reference/workflow-invariants.md`. Stage table and limit spend: `../plan2/reports/SESSION-4A.md`.
P8: 38 DONE / 33 TODO.

## Next — **stage 2 of session 4A** (prompt `../session_4a_fix.md` §0 "Stage 2", relaunch with the same prompt)
1. Restore the state (§8.7), read the fast CI result of the stage-1 push, check plan limits (`get_usage`; stop rules §0).
2. **W3 (P8.W.3)** — spec first (one page, `docs/verification/scheduler.md` §8.4, D-45, D-53, D-374, I-12), Codex
   review of the spec, then code. Owner of `workspace/Stamper.kt` after W2. Inputs: WD-06, WD-14; c5, c15; W2 tails below.
3. **W4 (P8.W.4)** after W1 — owner of `campaign/Controller.kt` and `cell/Cell.kt` now. Also: a failed open emits no
   `phase.counted` (W0 review); c13, c14.
4. **W5 (P8.W.5)**, root repo (Studio) — decision lookup by `AcceptanceDecisionRequest.key` (`DecisionKey`, D-428);
   `*WorkflowScenario*` guards WF-1, WF-6 (Studio), WF-8, WF-9 (Studio), WF-10, WF-11; c6, c8, c16.
5. One Codex integration review of merged `main` over c1–c16 (`--effort medium`), then Gate P8.W-A: live
   `real-dirty-repo` in `auto` and `ask` on a cheap model, counters → `SESSION-4A.md` baseline; push `main`, tag
   `v2-wave-WA`; `../session_4a_results.md`.

## Owner rules (2026-10-05)
- Codex review once per line at `--effort medium`, no re-review after a fix round; after a stage everything is merged
  into `main` and pushed. No Fable in session 4A; lines Opus, tests t1/background.
- WF suite over budget: 188 s of tests on merged `main` (limit 180 s) — the first stage-2 line cuts time (largest: `DirtyRepoScenarioTest` 120 s).

## Stage-1 tails (owners)
- W2: a modified tracked CRLF/filtered file is read twice (`DirtyState.kt:375-378`); `hash-object` recovery-blob reads
  uncounted (`ShadowRef.kt:446`); unreadable input during `open` capture still throws to the host; reopen re-reads the
  whole tree and Atlas reads all of `devtools/` (→ W3 scratch).
- W1: c4, c9, c12 repeat with explanatory text on a third entry; restored receipts lack the `Scheduler.kt:536-538`
  workspace filter (not shown exploitable); rebound items carry provenance `accepted`.
- W0: counters are deltas on shared instances; `real-dirty-repo` `dir` accepts absolute/UNC paths (`eval-live` `Tasks.kt`);
  scenario event barrier assumes lossless delivery; public `CountedPhase` duplicates the event's string field.

**Then:** session 4B (W6–W10) after Gate P8.W-A; session 5 (D7 first, C18, D4, D5, E1, B6, C17) after Gate P8.W. Frozen
debts (plan §12), kept branches `v2/BL`, `v2/B4`, `v2/B4a3`, `v2/C10x` (never merge), transient tests — as before.
