# P4.5.5 — exact small-DAG scheduling oracle (OOO-08)

Date: 2026-09-20. Baseline: `eb143e8`, clean `feature/out-of-order-kernels`, after P6.1.5 DONE.
Authority: continuing owner request, proposal §2 order. Decision D-60. P4.5.5/D-60 were free;
P4.5.4 remains the candidate for subsequent OOO-06, not a dependency or an implemented task.

## Admission / ownership

P4.5.3 is not dependency-ready (P4.5.1/P4.5.2 router/escalation absent). Its independent offline
sequencing oracle can consume existing RequirementGraph/Increment (P2.1.1), Money (P0.3.3),
Digest/CanonicalEncoding (P0.2.1), all DONE. Internal graph.cycles is available within core;
do not reimplement SCC. New narrow cost/context projections belong to P4.5.5, not runtime Role/Profile.
P4.5.3 retains real cost calibration, controller hook, shadow campaigns and FX-45 runtime fixture;
P2.2.2 owns dispatch. No automatic routing/scheduling activation or live comparison.

## Frozen model before code

- Explicit selected increment IDs from immutable RequirementGraph. Only Pending increments can be
  reordered; Verified, Cancelled, Blocked and InProgress are not executable inputs. Every dependency
  outside the slice needs explicit caller confirmation in completedPrerequisites. These confirmations
  are supplied facts, not an oracle-produced proof. No selected ID may also be marked completed.
- Additional precedence edges encode semantic boundaries/review/latency constraints. Caller explicitly
  declares that all mandatory order constraints are represented. Unsupported history-dependent costs
  (TTL, several retained prefixes, history/dynamic latency) or incomplete order constraints yield
  Unsupported. The oracle does not convert a global deadline into pairwise constraints.
- Frozen context per increment records role label, profile configuration digest, S/R prefix versions
  and cache namespace. The caller supplies all initial and directed pairwise switch charges, plus a
  separate fixed charge per increment, in one currency with provenance/version. No free transition
  is inferred from matching role/profile. Incompatible cache states require their explicit cold cost.
  Missing/unknown charges yield UnknownCosts; invalid currency/negative prices are rejected.
- Objective: sum fixed charges + initial(first) + sum switches(previous,next). Exact decimal Money
  additions, no rounding, no provider-bill/latency savings claim. Only this declared pairwise model
  admits state (completed subset,last). Fixed charges are included once even though they do not
  affect ordering. No end charge unless included in the supplied model as an explicit node.
- Forward subset DP, adding only prerequisite-ready nodes, with predecessor reconstruction.
  Deterministic traversal: sorted IDs, ascending subset mask/last/next; strict improvement only;
  final tie chooses lowest last ID. This is deterministic, not a promise of lexicographically
  smallest whole order. Empty slice returns exact zero with no initial charge.
- O(n^2 * 2^n) arithmetic operations, O(n * 2^n) table/parent memory. Caller supplies limits for
  increment count, table entries and attempted DP transitions. Check dense allocation with Long
  arithmetic before allocating; reject n>30 or arrays beyond Int indexing. Limit exhaustion is
  ResourceLimit with no optimal witness, never infeasibility. Decimal bit complexity is additional.
- Costs and problem receive canonical fingerprints (sorted sets/maps/edges, normalized decimals,
  graph increment definition/status, completed facts and constraints). Results retain immutable
  inputs, order, fixed/variable/total cost, visited states/transitions and refusal diagnostics.

Method source inspected: [Applegate, Cook, Dash, Johnson, Dynamic Programming chapter](https://www.math.uwaterloo.ca/~bico/papers/comp_chapterDP.pdf),
subset/last recurrence and path reconstruction. Prerequisite constraints and model boundary above
are local adaptation. No new dependencies.

## Planned validation and checkpoint

RED minimal diamond/dependency test; DP; then independent enumeration of every topological order
for 200 seed-455 DAGs (n<=7), local-greedy counterexample, zero/equal/exact decimals, unknown/mixed
currency, cycles, explicit external prerequisites, verified-work refusal, mandatory precedence,
permutations, snapshot mutation and resource limits. Review, core ABI, full build, API guide and commit.
Admission recorded before code. Return P0 validation -> P1.8.2; next authorized proposal OOO-06.

## Execution log

- RED: `:core:test --tests 'io.astrolabe.route.DagScheduleTest'` failed test compilation on missing
  Schedule API, as expected. Production `Schedule.kt`/`DagSchedule.kt` then passed the diamond test.
- A/B/C: 12 focused tests passed, zero failures/errors/skips. Independent enumeration of every
  permutation filtered by prerequisites/extra edges agrees on 200 seed-455 DAGs (1..7 nodes),
  including exact costs and reconstructed witness; reversed inputs preserve fingerprints/order/counts.
  Tests include a nearest-choice counterexample, zero/equal/sub-DECIMAL128 differences, complete price
  requirements, external completion/graph-status contradiction, no rerun of Verified/Blocked/InProgress/
  Cancelled, allocation/transition limits, immutable snapshots and frozen context/provenance.
- Separate `:core:updateKotlinAbi` passed; dump inspected, only Schedule/DagSchedule additions.
  Independent review and full build pending. Earlier core 813/6-skips XML was recorded in P6.1.5
  before focused runs replaced it; do not mistake the focused report for the previous complete suite.

Commands use `./gradlew.bat`, flags `--offline --console=plain --no-configuration-cache` and the
pinned Windows JDK 26 installation `C:/Users/user/.gradle/jdks/eclipse_adoptium-26-amd64-windows.2`
with GRADLE_USER_HOME `C:/Users/user/.gradle`. Full check is `./gradlew.bat build` with the same flags.

- Independent read-only review completed with no required findings. Reviewed recurrence/reconstruction,
  readiness and external-completion constraints, cost accounting, model refusal, deterministic ties,
  resource bounds, fingerprints/snapshots and the exhaustive oracle. Full build still running.

## Final checkpoint and resume

P4.5.5 DONE. Full offline Windows/JDK 26 build passed in **3m18s**. Core **825 tests, zero failures/
errors, six existing platform skips** and eval **30 tests, zero failures/errors/skips** executed.
Provider-api reused **15 green results** (UP-TO-DATE). All ABI checks pass; no tests disabled, no
dependency additions or threshold reductions. `git diff --check`, unique IDs and document links checked.
This session completed OOO-07 then OOO-08, with 24 focused tests and both independent reviews resolved.
The commit contains code, tests, ABI, API guide, decision/producer mapping, protocol and handoff files.

Counts: **66/182 DONE, 4 IN_PROGRESS, 112 TODO**. No ACTIVE override. No parent/runtime/CI/Linux/live
claim: P4.5.3 owns integration and FX-45; live gates remain UNMEASURED. Session stops at a complete
checkpoint. Next out-of-order task is **OOO-06, candidate P4.5.4** (not registered/started), followed
by OOO-09 and OOO-04. Recheck admission and D-61 availability before code. Normal return remains
P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2. Do not reconstruct either completed kernel.
