# P2.6.5 — calibration statistics kernel (OOO-03)

Date: 2026-09-20. Baseline: clean `main` at `5ec1e0c` (completed OOO-01/P2.3.4).
Authority: ongoing owner request to select and implement proposed out-of-order tasks.
TODO §1.2 is the execution authority; this journal is the implementation plan and evidence.

## Admission and integration boundary

| Consumed type | Existing file | Producer | Readiness |
|---|---|---|---|
| WorkId, AttemptId | id/Ids.kt | P0.2.1 | DONE |
| Increment.expectedFiles / sizing | contract/Contract.kt | P1.1.1, extended by P2.1.1 | DONE |
| Sizing | graph/Planning.kt | P2.1.1 | DONE |

Paths in the table are relative to `core/src/main/kotlin/io/astrolabe/`.
Input rows are trusted harness projections, never parsed CAL prose. `fromIncrement` copies only
expectedFiles and immutable Sizing; it checks the increment identity. Explicit terminal outcome
is independent of verification status: a failed execution is still an eligible observation.
P2.1.4 still collects real sizing; P2.6.4 stores aggregates, emits controller Warning events and
renders the gated ≤150-token block. P4.2.1 owns later CAL notes. No new DB, event loop or Note type.
Existing parent prerequisites and Build/Done are retained; P2.6.4 stays TODO.

## Frozen policy (D-56, calibration-stats-v1)

- Observation identity = repository + work + attempt + increment. One complete sizing snapshot per
  increment, including all its continuations/rebuilds. Identical deliveries are deduplicated and
  counted separately; any conflicting values/provenance/version for an identity reject aggregation.
  The caller must resolve durable checkpoint order; input order is never last-write-wins.
- Series = repository + harness version + sizing-policy version. Do not pool versions or repositories.
  Retain the aggregation policy's own version and actual band bounds in every result.
- Bands are explicit caller-supplied strictly increasing inclusive upper bounds, ending at Int.MAX_VALUE.
  First lower bound is zero; following lower bound = preceding upper + 1. Tests use [0], [1..3],
  [4..10], [11..Int.MAX_VALUE]; these are fixture choices, not measured or runtime defaults.
- Subsystem is one caller-supplied stable label per increment; null is an explicit unknown group.
  Do not infer it from model text or multiply a multi-subsystem increment into multiple observations.
- Completed and Failed are eligible terminal outcomes. Cancelled and Unfinished are censored, excluded
  from medians/rates/ratios, counted by their explicit exclusion reason (outcome), and retained in the
  source rows. Thus reported estimates are conditional on non-cancelled terminal observations, not
  unbiased estimates of all initiated work. Failure rows remain eligible to avoid success-only bias.
- overrun = continuations > 0 OR rebuilds > 0. Denominator = eligible unique observations in the group.
  Empty eligible groups have null rate and median. expectedFiles=0 stays in this denominator and
  median, while its ratio is undefined and separately counted. Mean ratio is mean of defined
  filesTouched/expectedFiles values, not ratio of sums.
- Ratios use BigDecimal DECIMAL128 (34 significant digits, HALF_EVEN) per division, exact accumulation
  of those decimal ratios, and DECIMAL128 division by the defined count. No floating sum overflow or
  order sensitivity. Median uses sorted Int turns with Long addition before dividing by 2.0.
- Warning for a proposed band uses exact integer comparison 2×overruns > eligible, not rounded rate.
  Null warning means no triggered diagnostic; empty groups/rates explicitly show missing history.
  A warning value is not an emitted event. No learned correction, window, decay or routing change.
- Inputs/outputs own collections; provenance and source rows survive aggregation. Reusing version
  labels with changed policies is a producer error; the actual policy snapshot remains inspectable.

Sorting/deduplication/grouping and per-group medians take O(n log n + g·b) time and O(n + g·b)
space, for n input rows, g series and b explicit bands; each row contributes to overall, one band
and one subsystem. Band lookup is linear in b in the initial implementation, making total time
O(n log n + n·b + g·b). BigDecimal costs additionally depend on input digit lengths.

## Implementation sequence

1. Register task/producer mapping/D-56/ACTIVE card/handoffs before code (done).
2. Write failing median, ratio and threshold tests; implement the pure aggregate and warning.
3. Test zero/empty/large values, all outcome classes, duplicates/conflicts, unequal groups and
   shuffled inputs. Compare seeded small data with an independent exact-rational oracle.
4. Independent read-only review; resolve findings; focused tests; separate ABI update; full Windows
   offline build. Record results/skips/cached tests and leave Linux/live gates unchanged.
5. Close only P2.6.5, update all state/index files and commit. Return to normal P0 validation → P1.8.2.

## Verification log

- RED: focused test compilation failed on the absent calibration API, as expected.
- GREEN: three initial ratio/median/warning/no-history tests passed. Expanded focused run passed
  **12 calibration + 13 context tests**, zero failures/errors/skips, Windows/JDK 26.
- Exact-rational oracle: seed **265**, **120 histories × 24 rows**, overall/band/subsystem slices;
  maximum absolute decimal mean difference **1E-31**, within the declared 1E-30 synthetic check.
  Shuffled rows produce identical summaries and provenance ordering. This validates arithmetic,
  not a predictive calibration or a promotion gate.
- Kotlin compiler reported generated-copy visibility on the initial internal summary constructor;
  `@ConsistentCopyVisibility` makes the generated copy obey that constructor visibility. Expanded
  compilation is clean; no diagnostic suppression or build threshold was introduced.
- Separate `:core:updateKotlinAbi` succeeded; inspected **156 additive API lines**, no existing API removal.
- First full offline Windows build passed: core **802 tests, zero failures/errors, six existing
  platform skips**; provider-api UP-TO-DATE (15 green results), ABI checks passed.
- Independent read-only review found no implementation blocker; it requested an exact decimal
  regression to distinguish per-ratio rounding from rounding only the final rational mean.
  Added 1/2 and 1/3 → 0.4166666666666666666666666666666666 (single final rounding would end in 7).
  Follow-up review approved the implementation, conditional on execution/build.
- Expanded focused run passed **13 calibration + 13 context tests**, zero failures/errors/skips.
  Final full build with the added regression passed: **core 803 tests, zero failures/errors, six existing platform skips**; provider-api UP-TO-DATE (15 green results); ABI checks passed. No production/API change followed review. Kernel DONE; parent P2.6.4 remains TODO. This checkpoint commit includes code/tests/ABI/state documents. Return to P0 validation, then P1.8.2; OOO-02/04 remain proposals.

Commands used the recorded JAVA_HOME/GRADLE_USER_HOME, with `--offline --console=plain
--no-configuration-cache`: focused `:core:test --tests 'io.astrolabe.kb.*' --tests
'io.astrolabe.context.*'`, separate `:core:updateKotlinAbi`, then `build`.
