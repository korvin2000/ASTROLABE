# ASTROLABE — implemented state

Snapshot, rewritten each session (≤60 lines). Progress truth is the TODO task statuses and `Log:` lines;
next work is `CONTINUE-TASK.md`; history is `audit/SESSION-HISTORY.md`.

## Counts (2026-09-24, from `#### P… · STATUS` headings)
**81/185 DONE, 0 IN_PROGRESS, 104 TODO.** P0 19/19 · P1 53/64 · P2 3/30 · P3 1/25 · P4 2/25 · P5 1/15 · P6 2/7.
Recount: `rg -c '^#### P\d+\.\d+\.\d+ .*· DONE' TODO.md` (per phase: `'^#### P1\.\d+\.\d+ .*· DONE'`).

## Completion levels
- **P0:** complete, `FIXTURE_VALIDATED` on Windows + Linux CI (JDK 26).
- **P1:** contract (S0), workspace/versioning/recovery, atlas/sniff, evidence store, register/workset, all seven
  tool families with partition/dispatcher, verification baseline (checker, receipts/currency, baseline ledger,
  reserve, exit gate/verifier, scope guard/test integrity), authority baseline, role table, the complete S0 cell
  runtime (P1.8: `Layout`, `Anchor`, `Gauges`, `Gates`, `Residency`, `Cell.run`, `ResultPacket`/completion) and
  the controller state machine (P1.9.1 `campaign.Lifecycle`, `SqliteCampaigns`, store schema v2).
- **Not yet end-to-end:** no controller open/run (P1.9.2–P1.9.5), no `Astrolabe`/`AstrolabeJava` facade (P1.9.6),
  no telemetry store (P1.11.1–P1.11.2); the Java smoke test reads `Astrolabe.MODULE` only.
- **Out of order, DONE as kernels (parent integration still TODO):** P2.1.1, P2.3.4, P2.6.5, P3.2.7, P4.5.4,
  P4.5.5, P5.1.5, P6.1.4, P6.1.5, P1.11.3. Proposal queue empty.
- Every live gate `UNMEASURED` (P7). Optional layers off; S3 off.

## Key types by package (entry points only)
- `campaign`: `Lifecycle.open/apply/disposition`, `CampaignState`, `Transition`, `CampaignOutcome`, `SqliteCampaigns`.
- `cell`: `Cell.run`, `Role`, `ResultPacket`, `RoleCompletion.forRole`.
- `graph`: `RequirementGraph` (`validate`, `recordAccepted`, `block`/`unblock`); `contract`: `Contract`, `Ledger`.
- `verify`: `Checker`, `ExitGate`/`Verifier`, `PreexistingLedger` (Baseline.kt), `ScopeGuard`.
- `tool`: look · edit · run · verify · state · task.ask · kb (contract only; `EmptyKb`).

## Last verification
- Gate: CI run 35941844915 green on Ubuntu + Windows at `12fc5d7` (see `CONTINUE-TASK.md`).
- Local (Linux sandbox, JDK 25 scratch copy): full core 950 tests, 2 known environmental failures, 6 skips.

## Recorded deviations (details in task `Log:` lines)
Gradle 9.7.1 (not 9.7.0) · JUnit Jupiter 6.1.3 for "JUnit 5" · no `kotlinx-coroutines-jdk8` ·
`Identities.candidate` is a `CandidateId` · `Config.redaction` section (P1.10.3) · `auth.ExecutionMode`,
`auth.Stage`, `route.Tier`, root `Mode`/`DClassPolicy` declared by P0.1.3 · generic shapers return
`Inconclusive` for ruff/tsc/eslint until P3.1.4.
