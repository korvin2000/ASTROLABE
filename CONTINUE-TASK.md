# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.

**Checkpoint (2026-10-01):** owner-requested out-of-order **plan-handoff fix** (`../harness-fiasco-analysis.md`): a live
S1 task (Studio `W-tgxiilat7og6a33vtwva`, `../diags/`) burned two 40-turn plan cells because the plan role could not
execute, the masked refusal read as transient, the loop gate ignored refusals and the plan wire form was invisible.
Branch `fix/plan-handoff` (merged `--no-ff` from worktree branches `wt/f1-f2`, `wt/f3`, `wt/f4`, `wt/f8`), **not merged
into `main`, not pushed**. Decisions D-357–D-362. P0–P6 remain 185/185 DONE.

## This session
- D-357 `cell/Refusals.kt`: masked-op refusal names the cause (role mask / shape / ceiling), the available ops and the
  role's exit; a valid terminal call (`task.ask|answer`, `state(blocked)`) runs alone in a refused turn (`Validated.Partial`).
- D-358 `RefusalSignature`, gate `refusal-loop` (2nd identical refusal nudges, 3rd ends the cell `blocked`); policy-denied
  `run` counts too and leaves the ordinary loop history; history lives for the cell, reset on contract version change;
  stall re-fires every 3 idle turns; `error-policy/2`.
- D-359 `Proposals.kt` `PlanForm.lint` + `PLAN_FORM`: true paths, unknown keys ignored and reported, one-increment
  defaults; plan persona `roles/4`; `task.proposal` description; gap-free plan tells the model to end the turn.
- D-360 `ShapePolicy.planCell = WhenNeeded`: `PlanNeed.trivialGraph` installs `G_single(C)` without a plan cell for an
  eligible S1 contract; plan role runs R-class commands; `Run(readOnlyRole)` enforces R-only for plan and probe.
- D-361 `InputTolerance`: `op` inference, glued `<arg_key>` keys rebuilt, real inner-JSON error, markup note.
- D-362 `gate.body` (provider-ai-gate) vendor pass-through via `ChatOptions.payload`; Studio drafts `openrouter/z-ai/*`
  with `provider.ignore: [Together]`. Live probe `GlmToolCallProbeTest` (liveTest) + `scripts/probe/or_raw_capture.py`.
- Reviews: Codex (F4 design, denial-loop patch), Opus (hypotheses, denial-loop patch); findings applied or recorded.
- ABI dumps regenerated (`core/api/core.api`). Docs: gates-termination, tools, roles-shapes, lifecycle, state/contracts.
- Studio (root repo `ASTROUI`, uncommitted): recap says "verified" only when a check passed; "Done · not verified" label.

## Next
1. Owner: review `fix/plan-handoff`, then merge `--no-ff` into `main` and push; full `./gradlew build` on both platforms
   (this session: `core` packages cell/tool/campaign/graph/delegate/verify/context + provider-ai-gate offline tests only).
2. Live re-run of the failed scenario through Studio on `C:\temp\play3` with `z-ai/glm-5.3-flash` ×3 (the analysis §4
   names the expectation: no plan cell, implementing cell runs the three acceptance commands).
3. Residuals: a plan recorded through `task.propose` is lost when the cell then ends blocked (`Controller.plan` returns
   before reading it); `CellTesting.run` is not the plan/probe executor; probe tests for the R-only enforcement beyond
   plan; `Run.kt` approval-mismatch denial (contract race) also counts toward the refusal loop.
4. Phase-0 residuals unchanged: pending-save crash re-proposal; explicit `verify` reruns; fact anchor `version` 64-hex;
   `deadend.add.evidence` existence; D-356 branch `phase0/next-from-cursor` not merged; F-5 Studio review cell.

## Carried-forward debts (unchanged)
D-254 recovery, D-70/D-71/D-241 replan and S3 re-selection, D-252 retrieval, D-113/D-120 behaviour maps,
D-124/D-244 review evidence, D-220/D-221 eval arms, D-200 QA scheduling, D-66, D-28, D-92.
Transient: `StamperTest` `git exited -1`; Windows `ProcOwnershipTest` READY timeout; FX-22 `bg-end` order.
Gotcha (this session): two Gradle `test` runs in one checkout corrupt `build/test-results` (EOFException /
NoSuchFileException) — never overlap them; worker agents ran in `.claude/worktrees/agent-*` for that reason.

## Blockers
None. `gh` is not installed locally.
