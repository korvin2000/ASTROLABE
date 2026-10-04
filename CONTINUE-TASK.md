# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
**Active program: ASTROLABE 2.0** — plan `../ASTROLABE-2-PLAN.md` (changes only via its §17), status = phase P8 in
`TODO.md` (`rg -n '^#### P8' TODO.md`), cards/reports `../plan2/`.

**Checkpoint (2026-10-04, live-run hotfix after S3):** P8 unchanged, **27 DONE / 14 TODO**. The owner's first live Studio
run failed on basics (`../diags/tasks/W-uyorz7p4tivk7xvm7iaq`, report `../ASTROLABE-DIAGNOSTICS-2026-10-04.md`). The hotfix
D-407–D-413 (TODO §3) is committed here locally, not pushed; its Studio half is uncommitted in the root repository.
**No live run has confirmed any of it**, and the owner has not decided whether the project continues.

## Next
1. Owner: a live Studio run of the recorded scenario (new repository without a commit, a flash model, a subscription
   model, `npm install`, a message sent mid-run) against the criteria in the report's last section. Then push / S4 or stop.
2. If it continues: S4 per plan §7 — D1 → D2 ∥ D3 (`docs/reference/kernel-contract.md` appendix A-D; re-check the
   path:line table A-D.7, the hotfix moved `Cell.kt`, `Gates.kt`, `EffectPolicy.kt`).

## Hotfix tails (found in the logs or by review, not done)
- Context: runs and verifies have no per-turn output budget (24 calls × 4 K worst case); call arguments are never stubbed,
  only a rebuild drops them; a second pressure still ends the cell `partial`; the gauge shows the window's percent.
- Permissions (D-412): a label on the command text, no sandbox; vocabularies are not on `Config` (OD-09); `pip install`
  outside a venv is W-class; Studio's `auto` mode denies every D-class effect that is not allow-listed.
- Verification: node per-test names are not parsed (a failing node run is an `unknown` hold); cargo or go output mixed
  with node keeps the old precedence; a `gradle` that is not on PATH leaves the check `unavailable`.
- State tool and loop gate: 58 rejected patches in the recorded runs (patch DSL, `red-not-recorded`, register cap); a
  material register change clears every loop signature; a signature holds the result body. Untouched.
- Studio: scrolling and the model-list refresh are unit-tested, never seen in a browser; a raised limit keeps the old
  number in the stored reason and `raise_limit` is offered for an unknown price; an attempt frozen before D-409 keeps its
  per-token profile on resume; `StatsService` shows plan calls as unpriced; the Changes panel counts build output.

## Tails from S3
C10: an `Open` note moves `[>]` past a held red. C11: a test change approved in I1 is asked again for I2. C14: the money
cap does not follow the policy on a reopen. C3: no deadline on a model call. Details: TODO `Log:` lines of P8.C.

## Debts, branches, last gate
Frozen debts (plan §12): D-254, D-70/D-71/D-241, D-252, D-113/D-120, D-124/D-244, D-200; D-66, D-28, D-92. Kept branches:
`v2/BL`, `v2/B4`, `v2/B4a3` (never merge), `v2/C10x` (`e157dc7`, do not merge as is). Transient: `StamperTest`
`git exited -1`; Windows `ProcOwnershipTest`; FX-22. Gate P8.C: CI 37154760649 green at `b3eae38`, tag `v2-wave-C`.
Hotfix: local `./gradlew build`, 2260 tests; the 4 failures were tests of the old permission default, updated and green
in a focused rerun; no CI run (nothing pushed). `.llm-memory` is stale on the default capability set and pressure.
