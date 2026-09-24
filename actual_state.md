# ASTROLABE — implemented state

Snapshot, rewritten each session (≤60 lines). Progress truth is the TODO task statuses and `Log:` lines;
next work is `CONTINUE-TASK.md`; history is `audit/SESSION-HISTORY.md`.

## Counts (2026-09-25, from `#### P… · STATUS` headings)
**150/185 DONE, 0 IN_PROGRESS, 35 TODO.** P0 19/19 · P1 64/64 · P2 30/30 · P3 25/25 · P4 9/25 · P5 1/15 · P6 2/7.
Recount: `rg -c '^#### P\d+\.\d+\.\d+ .*· DONE' TODO.md` (per phase: `'^#### P4\.\d+\.\d+ .*· DONE'`).

## Completion levels
- **P0–P3:** complete, `FIXTURE_VALIDATED` on Windows + Linux CI (JDK 26). The P3 phase gate is CI 36059635140.
  - **P3.2 impact engine:** `ImportGraph` (tier 0, per-package, manifest edges, dynamic imports ⇒ incomplete),
    `ImpactAssembly` (four consumer projections), `look(refs|importers|impact)`, impact nudge + exit-gate binding,
    `CHK-tests-blast` blast selection (`tests(blast N | package p | workspace)`), impact pre-scan at open + refresh.
  - **P3.3 transforms:** `edit(transform)` with `TransformReceipt`, `touched-by-transform (NOT SEEN)`, guarded inverse
    (`restored | partial | unknown_outcome`). **P3.8:** FX-07 full, Stage C scripted campaigns (`StageCCampaignTest`).
- **P4 (Stage D) done:** P4.1 KB (queue, curator with lint and versioned batches, invalidation, usage, prune/promote,
  index regeneration, health telemetry, injection ranking D-37/D-103, focus notes, `kb.propose`, CON compiled in);
  P4.2 extractor (post-cell candidates in diagnosis shape, `CAL-<repo>` delta, typed `NEG` states, derived PIT
  candidates, review findings ⇒ `Open`); P4.4.1 packets + `Delegator` (not yet constructed by the controller);
  P4.5.1 tier/function tables + `Router.selectProfile` (controller routes every cell); kernels P4.5.4/P4.5.5.
- **Out of order, DONE as kernels:** P3.2.7, P4.5.4, P4.5.5, P5.1.5, P6.1.4, P6.1.5.
- Every live gate is `UNMEASURED` (P7). Optional layers are off by default (`precompile`, `calibrationPrior`,
  `otelExport`, `kbInjection = Off`). S3 is blocked; S2 review paths are the human path until P4.4.3.

## Key types by package (entry points only)
- **root:** `Astrolabe`, `Config` (+ `qualityGates`, `tierTable`), `Flags` (+ `kbInjection`). **`java`:** `AstrolabeJava`.
- **`campaign`:** `Controller` (`open` with `ImpactPrescan`, `run`, `runS0`, `route`, finish + extraction),
  `ShapeSelector` (`Prescan.fanIn`), `ImpactPrescan`, `Plan.kt`, `CampaignFinish`, `FinishReceipt`, `Economics`.
- **`atlas`:** `ImportGraph`, `ImpactAssembly`/`ImpactProjection`, `Impact.analyze` (kernel), `DefinitionChanges`.
- **`verify`:** `Checks` (+ `replace`), `Scheduler`, `Applicability.of`/`ReuseProof`, `Layers`, `Blast`, `ExitGate`,
  `ScopeGuard`, `TestIntegrity`, `RefactorMode`, `Equivalence`, `CampaignReview`.
- **`cell`:** `Cell.run`, `Gates.s0()` (14 gates incl. `impact`), `ImpactNudges`, `Roles`/`shapeMask`, `ResultPacket`.
- **`tool`:** `Look` (+ refs/importers/impact), `Edit` (+ `TransformExecution`), `Verify` (`selectBlast`, `runLayer`),
  `TaskTool` (+ delegate/collect), `KbTool` (+ propose).
- **`kb`:** `StoreKb`, `Queue`, `Curator`, `AdmissionPolicy`/`Lint`, `Usage`, `Injection`, `CellKnowledge`, `Extractor`,
  `NegativeEvidence`, `Derived`. **`delegate`:** `TaskPacket`, `InvestigationPacket`, `Delegator`.
- **`route`:** `Tier`, `TierTable`, `FunctionTable`, `Router`, `CalibrationLog`, `AttemptCost` (kernel).
- **`context`:** `Compiler` (+ notes, contracts index), `CarryForward`, `Manifest`, `Rebuild`, `Precompile`.
- **`telemetry`:** `PrecompileMetrics`, `KbHealth`.

## Store
Schema **v4** (P4.1 reuses `note_queue`/`note_usage`; no schema bump). `packets` also holds behaviour-snapshot and
campaign-review rows; receipt JSON carries the closure manifest.

## Last verification
| Gate (Ubuntu + Windows green) | CI run |
|---|---|
| P3.2 | 36054813717 |
| P3 phase (P3.3 + P3.8) | 36059635140 |
| P4.1 (+ P4.4.1, P4.5.1) | 36066888792 |
| P4.2 | see the TODO gate line |
- Local (Windows, JDK 26): the full build runs about 1170 core tests (6 platform skips), eval and provider-api.

## Recorded deviations
Local choices D-87–D-111 (TODO §3): import graph and impact (D-87–D-94), blast (D-93), transforms (D-95–D-98),
Stage C fixtures (D-99), KB (D-100–D-104, D-110–D-111), delegation (D-105–D-107), routing (D-108–D-109).
