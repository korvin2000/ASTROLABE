# ASTROLABE — implemented state

Snapshot, rewritten each session (≤60 lines). Progress truth is the TODO task statuses and `Log:` lines;
next work is `CONTINUE-TASK.md`; history is `audit/SESSION-HISTORY.md`.

## Counts (2026-09-24, from `#### P… · STATUS` headings)
**120/185 DONE, 0 IN_PROGRESS, 65 TODO.** P0 19/19 · P1 64/64 · P2 30/30 · P3 2/25 · P4 2/25 · P5 1/15 · P6 2/7.
Recount: `rg -c '^#### P\d+\.\d+\.\d+ .*· DONE' TODO.md` (per phase: `'^#### P3\.\d+\.\d+ .*· DONE'`).

## Completion levels
- **P0, P1:** complete, `FIXTURE_VALIDATED` on Windows + Linux CI (JDK 26). P1 gate: CI run 35995814928.
- **P2:** complete. The P2 phase gate (P2.2 remainder + P2.7) runs in korvin2000/ASTROLABE#5; P2 becomes
  `FIXTURE_VALIDATED` when that run is green on both platforms.
  - The S1 campaign runs as follows:
    - `Controller.run` starts the plan cell: `task.propose(plan)`, then `PlanPacketValidator`, `PlanIntake` and `Transition.Planned`.
    - It then runs one cell per ready increment, each compiled with carry-forward and hash-checked seeds.
    - After each cell: verify/commit, then a regression refresh.
    - At the end, the harness re-runs stale `run:` acceptances (§4.1), then finish: review predicate, full suite, cadence.
  - Pressure rebuilds in place; a second pressure ends the cell partial and a continuation cell carries it forward.
  - Manifests name the boundary reason (D-71).
  - `Economics.report/export` → `exports/<work>/economics.md`.
- **P3:** P3.1.1 (closure manifests, blast closures from impact) and the out-of-order P3.2.7 kernel.
- **Out of order, DONE as kernels:** P3.2.7, P4.5.4, P4.5.5, P5.1.5, P6.1.4, P6.1.5.
- Every live gate is `UNMEASURED` (P7), including B2 ≥ B1 (D-28).
- Optional layers are off: `calibrationPrior` and `otelExport` exist but are disabled.
- S2/S3 are blocked.

## Key types by package (entry points only)
- **root:** `Astrolabe` (`campaign` → `Controller.run`), `Config` (supported-modes KDoc), `AttemptConfig`.
- **`java`:** `AstrolabeJava`, `JavaCampaignHandle`, `JavaAuthority`.
- **`campaign`:**
  - `Controller` (`open`, `run`, `runS0`, harness verify for full suite and regressions);
  - `ShapeSelector`, `Lifecycle`;
  - `Plan.kt`, `Proposals.kt`, `Calibration`;
  - `CampaignFinish`, `FinishReceipts`, `Attempts`;
  - `Economics`.
- **`context`:**
  - `Compiler`, `ContextCover`, `ContractSlice`;
  - `CarryForward`, `FactCoherence`, `Seeds`, `StatusNotes`;
  - `Manifest` (+ `boundaryReason`), `ContextAdmission`, `Rebuild`.
- **`kb`:** `Note`, `KbWriter`, `Notes`, `KbIndex`, `KbExport`, `StoreKb`, `NoteHorizon`, `CalibrationStats`.
- **`cell`:** `Cell.run` (admission, in-cell pressure rebuild); `Cell.ModelRequested` carries `anchorTokens`.
- **`verify`:** `Checks` (registry ids incl. `CHK-tests-blast`/`-review-*`/`-quality-gate`), `Scheduler`, `Checker`,
  `ClosureManifest`, `Closures.blast`, `ExitGate`, `ScopeGuard`, `TestIntegrity`.
- **`atlas`:** `Impact.analyze` (P3.2.7 kernel).

## Store
Schema **v4**:
- `note_revisions` is append-only.
- `packets` holds plan proposals and split requests.
- `manifests` has one row per compiled context.
- `sizing` has one row per (increment, latest cell).
- `attempts` has JSON views under `campaigns/`.
- `exports/<work>/` holds `finish-receipt.json` and `economics.md`.

## Last verification
- Gates green on Ubuntu + Windows:

  | Gate | CI run |
  |---|---|
  | P1.12 | 35995814928 |
  | P2.1 | 35998105732 |
  | P2.4 | 35999628531 |
  | P2.3 + P2.6 | 36001412845 |
  | P2.5 | 36003465907 |

  P2.2 + P2.7 are in korvin2000/ASTROLABE#5.
- Local (Linux sandbox, JDK 25 scratch copy): full build of 1066 tests; only the 2 known environmental failures, with 6 skips.

## Recorded deviations (details in task `Log:` lines)
- Gradle 9.7.1 · JUnit Jupiter 6.1.3 · no `kotlinx-coroutines-jdk8`.
- `Identities.candidate` is a `CandidateId`.
- Generic shapers return `Inconclusive` without counts; a counted log is needed for a green full suite.
- `Astrolabe` is a class.
- Local choices D-65–D-71: plan intake origins, model amendments are weakening, S1 loop, manifest boundaries.
