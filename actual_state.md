# ASTROLABE — implemented state

Snapshot: TODO owns task status; CONTINUE-TASK.md owns next work; audit/SESSION-HISTORY.md records history.

## Counts (2026-10-08, from `#### P… · STATUS` headings)
**P0–P6 185/185 DONE.** P8 (ASTROLABE 2.0, plan `../ASTROLABE-2-PLAN.md`): **53 DONE / 21 TODO / 1 IN_PROGRESS** — waves A, B
(B5–B7), C (C15–C18; C17 item 2 open), D (Dp3, D1–D4, D7, D5) and W (W0–W11) done; gates P8.W-A, P8.W, P8.D ticked, tags
`v2-wave-WA`, `v2-wave-W`, `v2-wave-D`; E1 done (store v7); then H (session 6), E2–E4, F, release. New small tasks: P8.C.19, P8.D.8, P8.W.12.
Recount: `rg -c '^#### P8\..*· DONE' TODO.md`. P7 out of scope except the AI Gate transport.
**Live** (gate P8.D, `bench/wd`, deepseek-v4.1-flash): `real-dirty-repo` auto / ask / `real-dirty-reopen` complete, acceptance passed;
opens 1/2/2, 1509 files per open at 1500 untracked, requests 10/8/10 (`../plan2/reports/SESSION-5.md`).

## Completion levels
- `IMPLEMENTED` → `FIXTURE_VALIDATED` → `PROMOTED` per TODO; live gates `UNMEASURED` unless a gate table says otherwise.
- WF suite `io.astrolabe.workflow`: 43 tests in 10 classes (T-56 merged two), 160–179 s on Windows; WF-2 has an absolute guard
  (38 / 26 / 37 git processes per open). Studio `*WorkflowScenario*` 25. Registry `docs/reference/workflow-invariants.md`.

## Direct protocol (wave D)
- `Config.protocol` (default `Structured`); `Roles.direct` with `kernel-direct/1`; goldens of `[S]` and schema fingerprints for all 9
  roles (`cell/LayoutTest`); 15 direct fixtures (`DirectFixturesTest` DX-01…06 + D1–D3). Crash windows: orphaned handoff reconciled
  first at open (D7); handoff cause, integrity flags and public impact live in the terminal packet (D-442); retention protocol by the
  resolved runtime role (D-439, D-443). Studio: settings `protocol` (auto | structured | direct) and `protocolByModelClass` → `Config.protocol`.
- D5 (`../plan2/reports/D5.md`): structured stays default (D-445); direct proven not worse on requests, uncached input and time on
  deepseek, money not proven; ranked losses vs `loop`: reasoning volume, uncached input per request (cache share), first-request constant.

## Binding physics (E1)
- `route/BindingKey` (model × gateway × upstream × wire-API, unknown bucket), `binding_physics` table and `binding_snapshots` per
  (work, attempt) in store **v7**, `routing_log` rows per decision (wired in `Controller` and `Repair`), estimators `route/BindingEstimators`
  (Codex-W). Host subscription of the physics feed is E3's.

## Verification and tooling
- `VerifyOutput.cap`: one output cap per `verify` call, recall of a receipt's raw log by alias, D-424 exemption dropped (C17).
- Settings reachability: `SettingsReachabilityTest` over the real composition (93 fields: 71 reached, 6 exceptions, 11 unwired → P8.C.19).
- Search backend chosen once per open (ripgrep when launchable, else JVM; D-441). `look` schema without `in=kb`/`since`.
- Capture: T-03 fixed (outline kept for every read file), snapshots write objects from retained bytes with one `fast-import` (T-22).

## eval-live and benchmarks
- Arms `default`, `direct`, `loop`; one open per start; `DirtSpec.dir` absolute/UNC; pair mode (`after`); summary rebuilt from every
  `result.json`; a `blocked_external` run keeps its state; OpenRouter upstream InferenceNet ignored for `z-ai/*` (D-444).
- Tasks: 8 screening + 3 debugging long (`port-framework`, `large-tree`, `two-sessions`) + 5 closed long (`scenario-1004`, `api-callers`,
  `merge-conflict`, `node-api`, `second-task-pairs`), each validated base/wrong/reference.
- Benches: `bench/d5a` (screening, 96 runs; glm core contaminated), `bench/wd` (gate + partial long stratum), `bench/wd2` (clean glm, empty);
  scripts `../plan2/bench/{d5-screening.sh,d5-aggregate.py,d5-context.py}`, resume `../plan2/bench/D5-RESUME.md`; auditor `./gradlew :eval:audit`
  with the D-421 re-pricing.

## Studio
- Protocol choice (D4), host guidance by the attempt's protocol (WR5s), InferenceNet ignore (GLM2). `*WorkflowScenario*` 25/0 against core `main`.

## Known debts (see CONTINUE-TASK.md and `../plan2/reports/TAILS-5.md`)
- C17 item 2; P8.C.19 dead settings; P8.D.8 (grant renewal window, STATUS recovery); P8.W.12 (form probes, 38 vs 35 git);
  H5: T-47, T-57; auditor lacks context per request (scripts cover it); glm screening to re-run from `bench/wd2`.
