# Offline evaluation kernel

P6.1.4 supplies score arithmetic and paired inference over explicit records. It is not a campaign
runner and does not enable features. The runner, frozen manifests and integrity verification remain
P6.1.1/P6.1.2/P6.1.3; Accounting remains P1.11.2. Live evaluation remains P7, UNMEASURED.

Create a `ScorePolicy` before results: decimal stratum weights, complexity labels, pilot cost ranges,
acceptance floors, family confidence, minimum cluster count, cost ratio and cost/latency ceilings.
Weights sum exactly to one and complex strata carry at least half. `EvaluationDesign` fixes the
baseline/candidate configuration digests and every `PlannedTrial`. A pair's `matchedInputs` digest
must bind its start state, acceptance, environment and equal budget. The caller verifies these facts.

Supply one `EvaluationTrial` per configuration and planned repository/task/repetition key. Its
cost includes all attempts, helpers, review and integration. Repetitions stay in the same task stratum;
repository labels identify independent clusters, including related repositories under one label.
Do not provide intermediate attempts as extra observations. Missing rows, duplicates, mismatched
inputs or currencies produce explicit issues and prevent a valid comparison.

```kotlin
val baselineScore = Scorecard.calculate(design, design.baseline, baselineTrials)
val candidateScore = Scorecard.calculate(design, candidateConfiguration, candidateTrials)
val report = PromotionReport.evaluate(
    baselineScore, candidateScore, externallyVerifiedEvidence,
    baselineInvestment, candidateInvestment,
)
```

The report retains the design, source rows/provenance, input digests, arithmetic, bounds and every
issue. Inputs and returned collections are defensive immutable snapshots. Invalid policy parameters
throw `IllegalArgumentException`; invalid or missing trial data is reported as issues. Unknown token
volume is diagnostic, unknown billing blocks an economic claim, and unknown latency blocks a ceiling
claim. All counts remain visible. `Money.unknown` is never treated as an exact zero cost.

Q and E follow evaluation §19.4. Money totals use exact decimal addition and DECIMAL128 division.
Floors, cost ratios and ceilings use exact cross-products. Economic comparison uses the frozen
weighted mean of stratum costs per accepted task; observed loss in any weighted complex stratum
vetoes eligibility. Latency ceilings apply to every candidate trial. One-off investments are separate;
repayment is incremental investment divided by positive weighted savings. Unknown investment or
non-positive savings leaves repayment undefined, with separate `investmentIssues`.

`PairedBound` estimates the fixed-design, trial-weighted acceptance difference, overall and within
renormalized complex strata. Repository blocks are independent; within-block dependence is unrestricted.
The deterministic Hoeffding bound allocates family alpha over both endpoints and every declared
candidate. It needs no PRNG, normal approximation or estimated cluster variance. Constant observations
retain uncertainty. Insufficient clusters produce no bound; otherwise the lower bound must be strictly
above -0.02, in fraction units. The [derivation and assumptions](../audit/OUT-OF-ORDER-P6.1.4.md) also
document directed numerical rounding and reproducible Monte Carlo checks.

This bound can be very wide. With equal cluster weights, 95% family confidence and one candidate,
zero observed difference requires over 21,910 independent repositories to clear the 0.02 margin.
That is a consequence of the chosen worst-case bound, not a recommended campaign size. Pilot design
and any future more powerful method need a separately frozen, validated policy; repeated runs inside
one repository do not manufacture independent observations.

`numericalPass` describes arithmetic/statistical conditions conditional on the declared design.
`EligibleForReview` additionally requires caller-supplied measured, frozen, independent, intact
evaluation with mandatory controls enabled. It still authorizes no adoption. Synthetic or missing
evidence gives `Inconclusive`; known violations give `KeepBaseline`; malformed trial tables give
`InvalidInput`. Every result leaves runtime configuration unchanged.

Run `./gradlew :eval:test` (Windows: `./gradlew.bat :eval:test`) on the pinned JDK 26 toolchain.
The tests include independent arithmetic/formula oracles and seven predeclared 1,000-campaign
simulations. Public API changes require a separate `:eval:updateKotlinAbi` invocation before `build`.

## Workload partitioning

P6.1.5 supplies `WorkloadSplit` for metadata-only development/selection/final design. Construct a
`WorkloadPolicy` with stratum complexity labels, explicit repository/family grouping rules, optional
additional `WorkloadLink`s, per-task allowed partitions and optional `WorkloadWindow`s for all three
partitions. Windows are half-open and may have unbounded endpoints. Empty grouping explicitly selects
task-only isolation. Supply an overall `WorkloadQuota` for every partition and optional stratum quotas.
Each quota has inclusive minimum/maximum task mass, a target and an absolute-deviation penalty.

`WorkloadDesign` takes canonical `WorkloadTask` metadata with explicit repetition IDs. Weight belongs
to the task and is counted once across repetitions. This differs from the scorecard's trial-weighted
estimand: the future runner must explicitly freeze how task mass maps into its scoring design.
The workload must have positive total mass and at least half in complex strata. The kernel does not
require half in each partition; express any additional partition balance through stratum quotas.

```kotlin
val split = WorkloadSplit.solve(design, maxNodes = 100_000)
val checked = WorkloadSplit.validate(design, design.fingerprint, suppliedTrialAssignments)
```

Assignments identify every task/repetition. The validator checks coverage, no split repetitions or
must-link components, allowed partitions, time and all quotas. The expected fingerprint binds the
entire design, including policy. `Feasible` means a supplied assignment passed; `Optimal` means bounded
exact search completed with an optimum of the declared decimal objective. `Infeasible` includes a
static contradiction or an exhaustive-search reason. `SearchLimit` may carry a feasible incumbent;
inspect its assignment/objective, and never treat that incumbent as optimal. `UnknownMetadata` names
every required missing field. Invalid construction parameters throw; invalid supplied assignments
give `InvalidInput` with issues and no objective/totals. No task is silently dropped.

Results retain immutable input records, policy/design digests, components and allowed partitions,
assignment, search count/completion and partition/stratum task counts, trial counts, total mass and
complex mass. Empty stratum cells remain visible in valid assignments. Exact decimal arithmetic
avoids rounding over quota boundaries; deterministic component/partition ordering resolves ties.

Search is worst-case exponential (three choices per component); `maxNodes` bounds attempted component
assignments, not input size or preprocessing. Suffix bounds use O(components × quota cells) memory.
The [protocol](../audit/OUT-OF-ORDER-P6.1.5.md) records the model, proof and independent oracle.
This is evidence only about supplied metadata/links. It neither detects unknown answer leakage nor
restores a repeatedly used holdout: `CampaignManifest` and `CampaignIntegrity` (below) do.

## Fixture runner

P6.1.1 `FixtureRunner` runs the JUnit tests whose names carry `FX-nn`/`AX-nn` ids as a program, outside the
build: `./gradlew :eval:fixtures` (all of core's fixtures; report in `eval/build/reports/fixtures/report.json`),
or `FixtureRunner.main` with `--class`/`--package`, `--report FILE`, `--junit-xml DIR` (compare with the build's
JUnit XML) and `--configuration LABEL`. The report carries per-test status and the seven §19.4 invariant metrics;
an invariant none of whose fixtures ran is unmeasured, never zero (D-220).

## Campaigns, comparators and arms

P6.1.2 `CampaignManifest` freezes a campaign: harness version, the comparators' (`Variants.configure`, §19.1) and
arms' (`EvalArms.configure`, §19.5) frozen `AttemptConfig`s with their flags, strata, repositories, memory mode and
the workload partition validated by `WorkloadSplit`. `CampaignManifests.freeze(dir, manifest)` writes
`campaigns/<id>.json` once; a different manifest under the same id is refused. B0 and B-HELM are configuration only;
`LiveGates.all()` lists every live gate as `UNMEASURED` with its prerequisites and required evidence (D-28, I-19).
`CampaignIntegrity.check` verifies hidden acceptance, answer removal, memory reset/freeze and holdout use (FX-47,
D-222); its `evidence` feeds `EvaluationEvidence.integrity`.

The arms table below is rendered from `EvalArm` (`EvalArms.table()`); a test keeps it identical. Every production
`Flags` switch appears exactly once. A level that disables a mandatory control runs only as a research attempt and
is never promotion-eligible (D-48); research switches that `Config` does not expose are recorded, not runnable (D-221).

<!-- arms-table:start -->
| Arm | Kind | Levels (first = production) | Flag or control | Promotion-ineligible level | Source |
|---|---|---|---|---|---|
| cell boundaries vs HELM pressure rebuild | Research | cell boundaries / HELM pressure rebuild | — | — | §19.5 |
| workset seeds on/off | Research | on / off | — | — | §19.5 |
| Δ+absolute vs delta-only | Control | Δ+absolute / delta-only | `Controls.deltaPlusAbsolute` | delta-only | §19.5 |
| mark-then-stub vs immediate stub | Research | mark-then-stub / immediate stub | — | — | §19.5 |
| R_max early stubbing | Research | on / off | — | — | §19.5 |
| batched vs pressure-only eviction inside short cells | Research | batched / pressure-only | — | — | §19.5 |
| contract/STATE split vs STATE-only | Research | split / STATE-only | — | — | §19.5 |
| conditional STATE ops | Research | on / off | — | — | §19.5 |
| gauge | Research | on / off | — | — | §19.5 |
| impact nudge | Research | on / off | — | — | §19.5 |
| sync checker vs none vs async watchers | Flag | sync / async | `Flags.asyncChecker` | — | §19.5 |
| blast radius vs package tests vs full suite | Research | blast radius / package tests / full suite | — | — | §19.5 |
| closures with reuse proofs vs stamp-coarse invalidation | Research | reuse proofs / stamp-coarse | — | — | §19.5 |
| reserve on/off | Control | on / off | `Controls.reserve` | off | §19.5 |
| transform path vs anchored-only on the 40-file refactor | Research | transform path / anchored-only | — | — | §19.5 |
| test-integrity guard on/off (with injected weakening) | Control | on / off | `Controls.testIntegrityGuard` | off | §19.5 |
| refactor mode on/off | Research | on / off | — | — | §19.5 |
| boundary pre-compilation on/off | Flag | off / on | `Flags.precompile` | — | §19.5 |
| calibration prior on/off | Flag | off / on | `Flags.calibrationPrior` | — | §19.5 |
| KB injection off / frozen / live | Flag | off / frozen / live | `Flags.kbInjection` | — | §19.5 |
| notes in [R] vs [A] only | Research | [R] / [A] only | — | — | §19.5 |
| role-only vs task/dependency-weighted retrieval | Research | role-only / weighted | — | — | §19.5 |
| behaviour maps on/off | Research | on / off | — | — | §19.5 |
| skills module filtering vs whole skills | Research | module filtering / whole skills | — | — | §19.5 |
| probe cells vs in-window exploration | Research | probe cells / in-window exploration | — | — | §19.5 |
| review at increment scope only vs both scopes vs none | Research | increment scope / both scopes / none | — | — | §19.5 |
| judge same-context vs fresh vs fresh + symmetric evidence | Research | same-context / fresh / fresh + symmetric evidence | — | — | §19.5 |
| function routing with refusal vs all-high vs clamped | Control | routing with refusal / all-high / clamped | `Controls.floors` | clamped | §19.5 |
| capsule repair vs kernel-only vs deterministic-only | Research | capsule repair / kernel-only / deterministic-only | — | — | §19.5 |
| alternative attempt vs refinement | Research | alternative attempt / refinement | — | — | §19.5 |
| S0 vs S1 vs S2 on matched strata | Shape | S0 / S1 / S2 | — | — | §19.5 |
| sequential vs S3 under equal resources | Flag | sequential / S3 | `Flags.s3Writers` | — | §19.5 |
| language-service adapter on/off | Flag | off / on | `Flags.languageService` | — | §19.5 |
| dense retrieval on/off after measured lexical misses | Flag | off / on | `Flags.denseRetrieval` | — | §19.5 |
| tree-sitter index on/off | Flag | off / on | `Flags.treeSitterIndex` | — | [O] flag |
| generated tools on/off | Flag | off / on | `Flags.generatedTools` | — | [O] flag |
| skills promotion on/off | Flag | off / on | `Flags.skillsPromotion` | — | [O] flag |
| QA cell on/off | Flag | off / on | `Flags.qaCell` | — | [O] flag |
| L4 gates on/off | Flag | off / on | `Flags.l4Gates` | — | [O] flag |
| OpenTelemetry span export on/off | Flag | off / on | `Flags.otelExport` | — | [O] flag |
| worth-test estimate on/off | Flag | off / on | `Flags.worthTestEstimate` | — | [O] flag |
<!-- arms-table:end -->
