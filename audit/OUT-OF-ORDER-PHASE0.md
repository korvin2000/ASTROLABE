# Phase 0 — the acceptance rule in the core (owner request, out of order)

Date: 2026-09-30. Baseline: `main` at `25c297c`; branch `phase0/acceptance`. Authority: owner request `next-goal.md`
(workspace root, one level above this repository) — "фаза 0: правило приёмки в ядре, надёжная Studio" — with the owner's
approach: the three projects are experimental, the core is used nowhere else, backwards compatibility of the core API and
of the store schema is not required, tests that pin the old behaviour are rewritten with the specification. D-337–D-343.
P0–P6 counts unchanged. Verification tier by owner instruction: focused tests only, no full `./gradlew build`.

## The owner's rule

Verification has three outcomes and only one of them means "not done":

| Outcome | When | What happens |
|---|---|---|
| passed | a check ran and approved | done |
| failed | a check ran and found concrete problems (a red test, a finding with file, place and substance) | not done: the agent reworks |
| unverified | the check cannot run, is incomplete, lacks something, the reviewer failed or said it cannot tell | never "not done": ask the authority, or accept with a label |

Invariants I1–I7 (next-goal §2): no `blocked`/`failed` for missing verification (I1); "not done" only from a substantive
rejection, an executed red check, or the user (I2); an unchanged candidate is never verified twice (I3); one rework round
per cell, then the user decides (I4); waiting for and answering a decision costs no model call (I5); every
verification-related stop names its reason and offers "done" / "not done, rework" (I6); provenance per item (I7).

## What was built (A1–A7)

- `verify/Resolution.kt`: `ResultStatus`, `ObligationResult`, `Obligations` (results from receipt currency and verdicts),
  `Resolver` (one rule, D-337), decision types `AcceptanceDecisionRequest`/`AcceptanceDecision`/`DecisionRecord`
  (D-338), `StopCode` (D-339), `ItemProvenance` (D-342).
- `verify/ExitGate.kt`: `Assessment`, `GateResult`, `ExitGate` removed; `Verifier.accept` resolves and returns
  `Accepted | Refused | Pending | NotCompleted`; `Verifier.commit` builds an acceptance from a complete resolution.
- `cell/`: the exit gate refuses only a rework; `CompletionDecision.Defer` ends the cell with
  `CellExit.Completed(pending)`; reviewer rejections are pinned whole; FX-13 blocking removed.
- `campaign/Acceptances.kt` + schema v5 (`pending_completions`, `acceptance_decisions`); `Controller`: settle / decide /
  resume (`resumePending`), rework notes and spending, `verify`, `commit`, campaign gate over results (`campaignResults`,
  `carriedAcceptances`), regression re-acceptance with decisions; `Lifecycle` stop codes; router outcome from the verifier.
- `delegate/ReviewCell.kt`: any recorded review of the same candidate is reused; empty diff at an equal candidate is current.
- `tool/verify/Verify.kt`: settled unverified receipts at the same candidate are not scheduled again.
- Provenance in `IncrementEvidence`, `LedgerEntry`, the finish receipt and `campaign.increment_closed`; `Views.acceptance`.

## Findings while building

| Where | Finding | Resolution |
|---|---|---|
| `Controller.runS0` and `runS1` created a fresh `Verifier()` per completion | the controller-level finalization counter never reaches 2; a controller refusal in S0 always fell back to `failed` | kept (S0 has no continuation of its own); the rework round lives in the cell, where the count is real |
| `CellExit.Completed` for an awaiting proposal | `Transition.Committed` requires the increment's latest cell to be `Completed`; a later `accept` must commit without a cell | the awaiting cell ends `Completed` with a `pending` marker; the controller never commits it without a decision |
| `ReviewRecord.freshness` | empty `evidenceVersions` (an empty diff) was `Unknown`, so an unchanged candidate was reviewed again | the candidate stamp covers the tree: current (D-341) |

| Independent review (Codex, read-only) | a stale red optional check and a stale red full suite counted as red; a policy decision could accept over a review's rejection; agent-owned gaps could still fail a campaign after the rework round; a decision was not re-validated against the tree after the authority answered | fixed: red means current and eligible; a red optional check without `Open` is an executed failure (never decided); policy covers only unverified results; agent-owned gaps go to the decider as `open:N` past the round, binding gaps (another stamp or contract) never; `Settled.Void` when the tree moved |
| Residual (recorded, not fixed) | a crash between `Returned` and saving the pending completion loses the proposal (the next cell re-proposes: model calls, no wrong state); an explicit model `verify` and the harness's regression/full-suite runs still re-run unverified checks on the same candidate | out of phase 0 scope |

## Verification (focused, owner instruction: no full build)

`ExitGateTest`, `AcceptanceDecisionTest`, `GatesTest`, `CellTest`, `ResultPacketTest`, `RefactorModeCellTest`, `ReviewCellTest`,
`RequirementGraphTest`, `MigrationsTest`, and the campaign/cell/graph/event/verify packages (284 + 207 tests over the
iterations, green at the end); `:core:updateKotlinAbi`; `:eval:testClasses`, `:provider-ai-gate:testClasses` compile.

## A8 and A9 (after the first live round)

- D-344 `answered`: `task(op=answer, text)`, facts checked by the controller (`answerable`: stamp = snapshot 0, no
  effectful intent) at the call and at the turn's end; `CellExit.Completed.answer`, `Transition.Answered`,
  `CampaignOutcome.Answered`. Tests: answer → `answered` with no authority call; answer after an edit refused; a silent
  stop is ordinary acceptance.
- D-345 host notes: `CampaignPolicy.hostNotes` pinned first under a host heading; `Contracts.amendByHost` refuses to
  write requests. Tests: `ContractsTest` (objective unchanged), a campaign whose two requests carry the same pinned
  prefix with the host block.

## Open / next

- Studio (part B) answers `decide`, shows the two decision cards and derives its states from the stop code.
- A8 (`answered`), A9 (host context channel) and part E after the live acceptance run.

## Part E — tool ergonomics (2026-09-30, D-346–D-352)

- E1 D-346 `edit` `expect`: full hash as given; 4–63 hex → the one version shown in the cell it prefixes (KNOWN or
  stale history); omitted → the one version KNOWN at dispatch; never the current bytes. CAS, displayed ranges and the
  atomic refusal unchanged; unresolved/ambiguous/malformed → kind `expect` (was an unhandled exception). Delete and
  rename resolve alike. `Workset.history`, `WorksetView.versions` (internal).
- E2 D-347 / E3 D-348 `InputTolerance` before typed decoding: `ops`/`patch` JSON strings holding an array are parsed;
  empty placeholders of an edit op's other forms and an empty `if` are dropped by a per-form whitelist.
- E4 D-349 `PatchParser.FORMS`: the `state` description, the `patch` schema and every schema refusal name the forms;
  the Validator's tick and `v` refusals name `op:N` (same turn) and `#N` (earlier result). Evidence rules unchanged.
- E5 D-350 Validator: no `next` keeps the previous Next; open steps without `[>]` get it on the first open step, which
  counts as advancing (red rule still refuses).
- E6 D-351 presentation: a plain `run` exit 0 without counts reads `completed, exit code 0` (first line and shaped view);
  status `inconclusive`, `green`, receipts and certification unchanged; checks, wrappers and mounted tools excluded.
- E7 D-352 `run` `cwd` blank/`.`/`./`/`.\` is the root (normalised at the executor entry; also a contract command's cwd).
- Tests changed because they pinned old behaviour: `ToolContractsTest` (missing expect now parses), `ValidatorTest`
  (no-Next and no-cursor cases now apply), `RunTest` and `GeneratedToolTest` (first line of a plain exit 0).
- Verification (focused): ToolContractsTest 6, EditTest 28, StateToolTest 9, ValidatorTest 8, GatesTest 14,
  GenericShaperTest 9, RunTest 27, GeneratedToolTest 3, MountTest 2, SchedulerTest 14 (1 skipped), DispatcherTest 5,
  PartitionTest 6, VerifyTest 14, CellTest 29 — all green; `:core:checkKotlinAbi` passes (no public API change).
- Next: shorten Studio's `Guidance.NOTES` (§9: after E2–E5), in the UI repository.

## After part E — owner decisions and live finding (2026-09-30, D-353–D-355)

- D-353 (owner): where the D-351 predicate holds, the `run` header says `status=completed` (`GenericShaper.COMPLETED_STATUS`),
  foreground and background completion alike; `Outcome` stays Inconclusive, `green` false; receipts, Scheduler, gauge unchanged.
- D-354 (owner): `deadend.add` `evidence?` and `decision.add` `rejected?` (defaults, constructor order kept); blank is stored as
  none; scope and reopen stay required. ABI: one synthetic default constructor of `Op.DeadendAdd` (additive).
- D-355 (local, found live with z-ai/glm-5.3-flash): `PatchParser` merges declared sibling fields of the one op key
  (`{"plan.tick": 1, "evidence": "#3"}`); collisions, foreign keys, zero/two op keys and flat items still refuse.
- Tests changed because they pinned old behaviour: `RunTest` (header of a plain exit 0 is now `completed`). Added: RunTest
  wrapper/background case, StateToolTest optional-field and sibling-merge cases.
- Verification (focused): RunTest 28, GenericShaperTest 9, GeneratedToolTest 3, DispatcherTest 5, StateToolTest 11,
  ValidatorTest 8, ValidatorFieldsTest 151, ToolContractsTest 6, CellTest 29, GatesTest 14, ControllerTest 21,
  RoleWiringTest 1, MountTest 2, VerifyTest 14 — all green; `:core:updateKotlinAbi`, `:core:checkKotlinAbi` pass.
- Live round 2 (both models): a model denied `task.answer` after changing files did not know how to finish ("propose
  completion"); the denial now says to reply with a summary and no tool call (D-344 text only). AcceptanceDecisionTest 10 green.
