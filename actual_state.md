# ASTROLABE — implemented state

Snapshot, rewritten each session (≤60 lines). Progress truth is the TODO task statuses and `Log:` lines;
next work is `CONTINUE-TASK.md`; history is `audit/SESSION-HISTORY.md`.

## Counts (2026-09-24, from `#### P… · STATUS` headings)
**116/185 DONE, 0 IN_PROGRESS, 69 TODO.** P0 19/19 · P1 64/64 · P2 27/30 · P3 1/25 · P4 2/25 · P5 1/15 · P6 2/7.
Recount: `rg -c '^#### P\d+\.\d+\.\d+ .*· DONE' TODO.md` (per phase: `'^#### P2\.\d+\.\d+ .*· DONE'`).

## Completion levels
- **P0, P1:** complete, `FIXTURE_VALIDATED` on Windows + Linux CI (JDK 26). P1 gate: CI run 35995814928.
- **P2:** everything but P2.7 (validation). The S1 campaign runs: `Controller.run` → plan cell (`task.propose(plan)`,
  `PlanPacketValidator`, `PlanIntake`, `Transition.Planned`) → one cell per ready increment compiled with carry-forward
  and hash-checked seeds → verify/commit → regression refresh → finish (review predicate, full suite, cadence).
  Pressure rebuilds in place; resume carries lost cells forward with a resume note; STATUS notes at boundaries.
- **Out of order, DONE as kernels:** P3.2.7, P4.5.4, P4.5.5, P5.1.5, P6.1.4, P6.1.5.
- Every live gate `UNMEASURED` (P7). Optional layers off (`calibrationPrior`, `otelExport` exist, off); S2/S3 blocked.

## Key types by package (entry points only)
- root: `Astrolabe` (`campaign` → `Controller.run`), `Config` (supported-modes KDoc, P1.12.4), `AttemptConfig`.
- `java`: `AstrolabeJava`, `JavaCampaignHandle`, `JavaAuthority`.
- `campaign`: `Controller` (`open`, `run`, `runS0`), `ShapeSelector` (S0–S2, `ShapeInputs`, capabilities),
  `Lifecycle` (+ `Planned`), `Plan.kt` (`PlanPacket`, `PlanIntake`, proposals), `Proposals.kt` (`CampaignProposals`,
  splits), `Calibration`, `CampaignFinish`, `FinishReceipts`, `Attempts` (`next`, campaigns/ view).
- `context`: `Compiler` (full, `CompileInputs`), `ContextCover`, `ContractSlice`, `CarryForward`, `FactCoherence`,
  `Seeds`, `StatusNotes`, `Manifest`/`SqliteManifests`, `ContextAdmission`, `Rebuild`.
- `kb`: `Note`, `KbWriter`, `Notes`, `KbIndex`, `KbExport`, `StoreKb`, `NoteHorizon`, `CalibrationStats`.
- `cell`: `Cell.run` (admission, in-cell pressure rebuild), `CellContext` (+ `sections`, `manifest`, `admission`).
- `graph`: `RequirementGraph` (+ `recordCell`, sizing); `tool.task`: `TaskTool` (+ `propose`), `Proposals`.

## Store
Schema **v4**: `note_revisions` (append-only). `packets` hold plan proposals and split requests; `manifests` one
row per compiled context; `sizing` one row per (increment, latest cell); `attempts` + `campaigns/` JSON views.

## Last verification
- Gates green on Ubuntu + Windows: P1.12 (35995814928), P2.1 (35998105732), P2.4 (35999628531),
  P2.3+P2.6 (36001412845), P2.5 (36003465907). P2.2 pending in PR korvin2000/ASTROLABE#5.
- Local (Linux sandbox, JDK 25 scratch copy): full build 1061 tests, only the 2 known environmental failures, 6 skips.

## Recorded deviations (details in task `Log:` lines)
Gradle 9.7.1 · JUnit Jupiter 6.1.3 · no `kotlinx-coroutines-jdk8` · `Identities.candidate` is a `CandidateId` ·
generic shapers return `Inconclusive` without counts (a counted log is needed for a green full suite) ·
`Astrolabe` is a class · local choices D-65–D-70 (plan intake origins, model amendments are weakening, S1 loop).
