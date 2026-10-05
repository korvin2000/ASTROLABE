# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
**Active program: ASTROLABE 2.0** — plan `../ASTROLABE-2-PLAN.md` (changes only via its §17), status = phase P8 in
`TODO.md` (`rg -n '^#### P8\.W\.' TODO.md`), cards/reports `../plan2/`, diagnostics `../ASTROLABE-DIAGNOSTICS-2026-10-05.md`.

**Checkpoint (2026-10-06, session 4A closed):** W0–W5 merged and pushed (D-426–D-431), plus WG (`eval-live --mode
auto|ask`) and WR (fix round of the integration review: 4 cross-line P1, `DecisionKey` v3). Gate P8.W-A: see its line in
TODO.md (tag `v2-wave-WA`; the full CI suite on the tag is **not awaited** — read its result first thing next session and
fix failures with targeted tests). Results: `../session_4a_results.md`; stage tables: `../plan2/reports/SESSION-4A.md`;
integration review: `../plan2/reports/INTEGRATION-REVIEW-4A.md`. P8: 41 DONE / 30 TODO.

## Next — **session 4B (W6–W10)**, plan §6 wave W, §7 row 4B
1. Restore (§8.7), read the CI result of tag `v2-wave-WA`, plan limits (`get_usage`).
2. W6 task workflow specification first (t6 draft → Codex review → owner), then W7–W10 per TODO.
3. WF suite is at 171 s of tests (limit 180): the first 4B line that adds a scenario cuts time first
   (largest `DirtyRepoScenarioTest` 84 s, then `ReviewScenarioTest` 27 s, `FinalizationScenarioTest` 24 s).

## Owner rules
- Codex review: `--model gpt-6.1-sol --effort xhigh`, read-only, once per line; no re-review after a fix round.
- Hard algorithmic/math/implementation questions: Fable 5.1 xhigh or Codex `--model gpt-6-astra --effort high`.
- A card that changes `campaign/Controller.kt` names `campaign.*` in its L2 (W3 regression lesson).
- WF suite (`:core:test --tests 'io.astrolabe.workflow.*'`, Studio `*WorkflowScenario*`) at every merge; Studio builds
  against a core checkout given by `-Pstudio.astrolabeBuild=…` plus `-Pastrolabe.aiGateBuild=…`.

## Tails for 4B (owners in `../session_4a_results.md` §4)
- WF-1: a project with no declared checks still opens twice (core `CampaignPolicy` hook). WF-10: an exception inside a
  cell ends the run final `failed` (`Controller.kt`, `Lifecycle.kt`) — Studio can only follow up.
- Reads: open and finish read each untracked file about twice live (atlas parse, two fresh stamps); reopen re-reads the
  tree; `canonicalise` ≈ 60 % of cell time at 1500 files (D-47, separate measured change).
- Scratch: nested generated dirs (`tests/__pycache__`) are identity and raise integrity flags; "declare as output" (W6/W7).
- Review: compile and routing reserve the full model output; no end-to-end unavailable campaign-review scenario.
- Recap: full history has no context budget; decisions not bound to `obligationSet` in Studio (request lacks it).
- `eval-live` still opens like the old Studio (2 opens per start, `StudioAttempt`); W0 tails (shared-instance counters,
  UNC `dir`, lossless event barrier) unchanged.

**Then:** session 5 (D7 first, C18, D4, D5, E1, B6, C17) after Gate P8.W. Kept branches `v2/BL`, `v2/B4`, `v2/B4a3`,
`v2/C10x` (never merge).
