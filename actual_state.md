# ASTROLABE — implemented state

Snapshot, rewritten each session (≤60 lines). Progress truth is the TODO task statuses and `Log:` lines;
next work is `CONTINUE-TASK.md`; history is `audit/SESSION-HISTORY.md`.

## Counts (2026-09-24, from `#### P… · STATUS` headings)
**90/185 DONE, 0 IN_PROGRESS, 95 TODO.** P0 19/19 · P1 62/64 · P2 3/30 · P3 1/25 · P4 2/25 · P5 1/15 · P6 2/7.
Recount: `rg -c '^#### P\d+\.\d+\.\d+ .*· DONE' TODO.md` (per phase: `'^#### P1\.\d+\.\d+ .*· DONE'`).

## Completion levels
- **P0:** complete, `FIXTURE_VALIDATED` on Windows + Linux CI (JDK 26).
- **P1:** everything but P1.12.1 (harness fixture tests) and P1.12.4 (platform validation). The S0 campaign
  runs end to end: `Controller.open` (capture, contract, reconcile intents + drift, lease, frozen attempt
  config, shape) → `runS0` (compile, cell, verify on receipts, commit, finish) → `FinishReceipt`; lifecycle
  controls (cancellation token, leases, `Interrupted`/`Lost` transitions); `Astrolabe`/`AstrolabeJava` facade;
  spans, metrics, per-call accounting, `exports/`. First vertical slice and the Java smoke pass.
- **Out of order, DONE as kernels:** P2.1.1, P2.3.4, P2.6.5, P3.2.7, P4.5.4, P4.5.5, P5.1.5, P6.1.4, P6.1.5.
- Every live gate `UNMEASURED` (P7). Optional layers off (`otelExport` exists, off); S3 off.

## Key types by package (entry points only)
- root: `Astrolabe` (`open` → `Project`, `campaign` → `CampaignHandle`), `Config` (+ Java withers), `AttemptConfig`.
- `java`: `AstrolabeJava`, `JavaCampaignHandle`, `JavaAuthority`.
- `campaign`: `Controller` (`open`, `runS0` → `S0Run`), `OpenedCampaign`, `ShapeSelector`, `Lifecycle`,
  `CampaignState`, `Transition` (+ `Interrupted`, `Lost`), `Cancellation`, `Leases`, `Attempts`, `FinishReceipts`.
- `context`: `Compiler` (S0 form), `ContractSlice`, `ContextCover`.
- `telemetry`: `Spans`, `CellMetrics`/`CampaignMetrics`/`ProjectMetrics`, `Outcomes`, `Accounting`, `Export`,
  `TraceAnalytics`.
- `cell`: `Cell.run`, `CellContext` (+ `accounting`), `ResultPacket`; `graph`: `RequirementGraph`.
- `verify`: `Checker`, `ExitGate`/`Verifier`, `Scheduler`; `tool`: look · edit · run · verify · state · task · kb.

## Store
Schema **v3**: `attempts` table; `requirements`/`acceptance`/`constraints` keyed by `(work_id, id)`.
`usage` written by `Accounting`; `leases` by `Leases`; `campaigns` by `SqliteCampaigns`.

## Last verification
- Gate P1.9 + P1.11: see `CONTINUE-TASK.md` (PR korvin2000/ASTROLABE#1, CI on both platforms).
- Local (Linux sandbox, JDK 25 scratch copy): full core 1011 tests, only the 2 known environmental failures,
  6 skips; eval 30; ABI check green.

## Recorded deviations (details in task `Log:` lines)
Gradle 9.7.1 (not 9.7.0) · JUnit Jupiter 6.1.3 for "JUnit 5" · no `kotlinx-coroutines-jdk8` ·
`Identities.candidate` is a `CandidateId` · `Config.redaction` section (P1.10.3) · `auth.ExecutionMode`,
`auth.Stage`, `route.Tier`, root `Mode`/`DClassPolicy` declared by P0.1.3 · generic shapers return
`Inconclusive` for ruff/tsc/eslint until P3.1.4 · `Git.rootCommits` ignores `refs/astrolabe/*` (P1.9.2) ·
`Astrolabe` is a class (constants in its companion) · D-65/D-66/D-67 local choices.
