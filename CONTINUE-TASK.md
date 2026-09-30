# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.

**Checkpoint (2026-09-30):** owner-requested out-of-order **phase 0** (`../next-goal.md`): the owner's acceptance rule
in the core — every obligation is passed, failed or unverified; unverified never becomes blocked or failed; the user
(or the host's policy) decides. Branch `phase0/acceptance`, merged `--no-ff` into `main` and pushed on the owner's request.
Journal: `audit/OUT-OF-ORDER-PHASE0.md`. Decisions D-337–D-355. P0–P6 remain 185/185 DONE.

## This session
- A1–A7 (D-337–D-343): `verify/Resolution.kt` (`Resolver`, `ObligationResult`, `Resolved`, `AcceptanceDecision*`,
  `StopCode`), cell `Defer` → `CellExit.Completed(pending)`, durable `PendingCompletion` + `acceptance_decisions`
  (schema v5), resume without a cell, per-item provenance in ledger and finish receipt, campaign-level resolution,
  `Authority.decide` (default null) and `JavaAuthority.decide`.
- A8/A9 (D-344/D-345): `answered` outcome via `task` op `answer` (only when nothing changed); host notes
  (`CampaignPolicy.hostNotes`) and `Contracts.amendByHost`.
- Part E (D-346–D-352) and follow-ups (D-353–D-355): `expect` from shown versions, JSON-string `ops`/`patch`, empty
  placeholders, state forms named, harmless register normalisation, plain run exit 0 reads `completed` (header too;
  outcome stays inconclusive), `cwd` "."/"" = root, optional dead-end evidence / rejected alternative, sibling fields
  merged into the one op key; the answer denial names the no-call turn.
- Live crash fixed (`8f17590`): a generic outline name cut at 80 chars kept a trailing space and failed `impactLabel`;
  a cell's failure reason now names its innermost harness frames.
- Live acceptance through Studio on `openai-codex/gpt-6-luna` and `openrouter/z-ai/glm-5.3-flash` (numbers in
  `../phase0-report.md`). SDK fix for Codex `complete()` (untyped SSE) lives in `../llm-transport-sdk`.

## Next
1. Full `./gradlew build` on Windows and Linux CI (owner: targeted tests only this session; ~40 min locally).
2. Residual (journal): a crash between the cell's return and the pending save re-proposes with a new cell; explicit
   model `verify` and harness regression/full-suite reruns are not suppressed on the same candidate; a fact anchor
   `version` still needs the full 64-hex digest (models send 4); `deadend.add.evidence` is not checked for existence.
3. Owner decision (report): S3/S4 still 5–10 calls — Next from `[>]` when STATE has none, short anchor versions.
4. F-5 (Studio): the core review cell for `check:` items in the simple shape — out of phase 0 scope.

## Carried-forward debts (unchanged)
D-254 recovery, D-70/D-71/D-241 replan and S3 re-selection, D-252 retrieval, D-113/D-120 behaviour maps,
D-124/D-244 review evidence, D-220/D-221 eval arms, D-200 QA scheduling, D-66, D-28, D-92.
Transient: `StamperTest` `git exited -1`; Windows `ProcOwnershipTest` READY timeout; FX-22 `bg-end` order.

## Blockers
None. `gh` is not installed locally.
