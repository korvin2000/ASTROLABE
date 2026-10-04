# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
**Active program: ASTROLABE 2.0** — plan `../ASTROLABE-2-PLAN.md` (changes only via its §17), status = phase P8 in
`TODO.md` (`rg -n '^#### P8' TODO.md`), cards/reports `../plan2/`, session 4 findings `../session_4_results.md`.

**Checkpoint (2026-10-04, session 4 closed):** the direct protocol is in the core, off by default
(`Config.protocol = Structured`): Dp3 specification for S0–S3 (D-414), D1 role / kernel / schemas / switch (D-419),
D2 anchor and `state(note)` (D-422), D3 `finish` and epoch handoff (D-423), D3r integration fixes (D-425). Also merged:
B7 one `RunSpec` (D-415), B5 arms and the reference arm `loop` (D-418), C16 nominal price of subscription models
(D-417), C15 hotfix tails (D-416, D-420, D-424), Studio steps (root repo). P8: **35 DONE / 23 TODO**.

## Next — session 5 (plan §7)
1. **P8.D.7 first** — before `Direct` is switched on anywhere: orphan handoff recovery before sequence-changing
   transitions, fact retention by the cell's protocol. Codex review (owner: remaining reviews go to Codex).
2. D4 (golden `[S]` per protocol, ≤ 15 direct fixtures, protocol choice in Studio), then D5 (structured / direct / loop);
   E1; B6. Before D5: P8.C.17 item 1 (cap of one `verify`), the `summary.csv` overwrite, a re-pricing what-if in the auditor.
3. Read the fast CI result of the session-4 push first (compile + ABI); the full suite runs only on a wave tag.

## Owner positions and rules from session 4
- The `loop` arm is a yardstick only; the core is kept and made cheaper, not replaced.
- D-421: money is judged over price profiles with output/input ratios of about 2, 3, 5 and "tens", decisions rest first
  on flows (requests, uncached input, cache read, output). First screening (8 tasks, deepseek flash, one repeat): both
  arms 8/8; the core loses on uncached input (cache-prefix misses), on turns after green (22 of 75) and on
  `state.patch`-only turns (13 of 75).
- Push to `main` is allowed after the checks pass. Economy rules: plan §8.8; line estimates were 3–5× too high.
## Tails (owners in `../session_4_results.md` §4)
- D4: golden `[S]` of the other structured roles; an S0 run with `Direct`; "poll the handle" text; the ninth role in
  Studio settings; fixture "`state(blocked)` after a split replans"; no rollback across D1 on a live `stateRoot`.
- H1: `kind=probe` check, typed review approval for both scopes. H3: resume after a quota refill needs a runtime-owned
  block cause; `maxHandoffs` is not in the facade or `RunSpec`. H8: writer packet per dispatch, integrity flags per epoch.
- Studio: no price input for an unpriced subscription model; `StatsService` vs the core on a billed subscription call;
  nothing of session 4 was checked in a browser.

## Debts, branches, last gate
Frozen debts (plan §12): D-254, D-70/D-71/D-241, D-252, D-113/D-120, D-124/D-244, D-200; D-66, D-28, D-92. Older tails of
sessions 3 (C10, C11, C14, C3) and of the hotfix (state tool, loop gate) are untouched. Kept branches: `v2/BL`, `v2/B4`,
`v2/B4a3` (never merge), `v2/C10x` (`e157dc7`, do not merge as is). Transient: `StamperTest`, Windows `ProcOwnershipTest`,
FX-22. Gate P8.D is open (D7, D4, D5). Session 4 was verified by targeted tests per line and L2 on every merged state, no
full suite; CI fast job green at `b0901dc`, the session-4 push is unread. `.llm-memory` cards lag behind sessions 3–4.
