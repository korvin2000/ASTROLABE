# ASTROLABE — implemented state

Snapshot, rewritten each session (≤60 lines). Progress truth is the TODO task statuses and `Log:` lines;
next work is `CONTINUE-TASK.md`; history is `audit/SESSION-HISTORY.md`.

## Counts (2026-09-24, from `#### P… · STATUS` headings)
**133/185 DONE, 0 IN_PROGRESS, 52 TODO.** P0 19/19 · P1 64/64 · P2 30/30 · P3 15/25 · P4 2/25 · P5 1/15 · P6 2/7.
Recount: `rg -c '^#### P\d+\.\d+\.\d+ .*· DONE' TODO.md` (per phase: `'^#### P3\.\d+\.\d+ .*· DONE'`).

## Completion levels
- **P0, P1, P2:** complete, `FIXTURE_VALIDATED` on Windows + Linux CI (JDK 26). The P2 phase gate is CI 36008104604.
- **P3:** these groups are complete (details in the task `Log:` lines):
  - **P3.1 scheduler:** closure manifests pinned on receipts; `Applicability.of` with reuse proofs; verify-on-stop;
    `Layers` (step boundary, increment end, full suite + quality gates, integration row); `DiagnosticsParser` for the
    checkers; isolated candidates; `unavailable` ⇒ blocked.
  - **P3.4 guards:** scope warn-once/justify (D-74); test-integrity classifier with kinds and original obligations;
    contract-touch, repeated-failure, scope and acceptance-surface gates.
  - **P3.5 refactor mode:** detection (D-75), behaviour snapshot at `s0`, `red_ok_until`, equivalence report,
    CON references and the mandatory signed campaign review (D-23 human path).
  - **P3.6:** flaky policy (one isolated rerun; disagreement ⇒ inconclusive); configured cadence K and quality gates.
  - **P3.7:** `Precompile` behind `Flags.precompile`, with a full-input fingerprint.
- **P3 open:** P3.2.1–P3.2.6 (runtime impact engine; the P3.2.7 kernel is DONE), P3.3 transforms, P3.8 validation.
- **Out of order, DONE as kernels:** P3.2.7, P4.5.4, P4.5.5, P5.1.5, P6.1.4, P6.1.5.
- Every live gate is `UNMEASURED` (P7). Optional layers are off by default (`precompile`, `calibrationPrior`, `otelExport`).
- S2/S3 are blocked.

## Key types by package (entry points only)
- **root:** `Astrolabe`, `Config` (+ `qualityGates`), `Flags`. **`java`:** `AstrolabeJava`. **`evidence`:** `Receipt`, `ClosureManifest`.
- **`campaign`:**
  - `Controller` (`open`, `run`, `runS0`, finish gate: full suite → acceptance → equivalence → review);
  - `Plan.kt` (`PlanPacket.refactorChecklist`/`conReferences`), `CampaignFinish`, `FinishReceipt` (+ equivalence, review), `Economics`.
- **`verify`:**
  - `Checks`, `Scheduler` (`runCheck` exclusive or isolated, `refresh`, `currency`, `flaky`);
  - `Applicability.of`/`ReuseProof`, `Layers`;
  - `Checker` (+ `tool/run/DiagnosticsParser`), `ExitGate`, `ScopeGuard`, `TestIntegrity.classify`;
  - `RefactorMode`, `BehaviourSnapshots`, `Equivalence`, `CampaignReview`.
- **`context`:** `Compiler`, `CarryForward`, `Manifest`, `Rebuild`, `Precompile`/`Fingerprint`.
- **`cell`:**
  - `Cell.run`: verify-on-stop, step-boundary layer, `not_tested`, unavailable ⇒ blocked, refactor `red_ok_until`,
    precompile trigger;
  - `Gates.s0()` with 13 gates.
- **`tool`:** `Verify` (`runLayer`, `onStop`, `review(scope=campaign)`), `Edit` (classified flags, `flagsOf`).
- **`kb`:** `Kb.contractAnchors()` (`StoreKb`). **`telemetry`:** `PrecompileMetrics`. **`atlas`:** `Impact.analyze` (kernel).

## Store
Schema **v4**. `packets` also holds `behaviour-snapshot`/`campaign-review` rows; receipt JSON carries the closure manifest.

## Last verification
| Gate (Ubuntu + Windows green) | CI run |
|---|---|
| P3.1 | 36018242027 |
| P3.4 | 36019777002 |
| P3.6 | 36023934566 |
| P3.5 | 36026485370 |
- P3.7 (session end, with the handoff): 36031110215.
- Local (Linux sandbox, JDK 25): the full build runs about 1130 tests; only the 2 known environmental failures fail.

## Recorded deviations
Local choices D-72–D-86 (TODO §3): checker counts, isolation, scope repeat, refactor mode, review/equivalence, precompile.
