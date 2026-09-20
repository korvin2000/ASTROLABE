# P6.1.4 — scorecard and paired inference kernel (OOO-02)

Date: 2026-09-20. Baseline: clean `main` at `d1689ef` (one commit ahead of origin/main).
Authority: owner requested successive out-of-order implementations in proposal §2 order,
with protocols and documentation, within this session/context allowance. No prior partial kernel.
TODO §1.2 is the execution authority. Parent P6.1.3 remains TODO.

## Admission

| Consumed artifact | Existing file | Producer | State |
|---|---|---|---|
| Library convention, ABI, tests | build-logic/src/main/kotlin/astrolabe.kotlin-library.gradle.kts | P0.1.1 | DONE |
| Money | provider-api/src/main/kotlin/io/astrolabe/provider/Usage.kt | P0.3.3 | DONE |
| Digest | core/src/main/kotlin/io/astrolabe/id/Digest.kt | P0.2.1 | DONE |

Explicit dependencies: P0.1.1, P0.2.1, P0.3.3. New module `eval` depends on `core`.
Local immutable records describe supplied trial data, not an Accounting or campaign-store replacement.
P6.1.1 retains the runner; P6.1.2 retains frozen campaigns and integrity verification;
P1.11.2 supplies accounting; P6.1.3 connects them; P6.3.1 owns exports; P7 owns live data.

## Frozen design before results — D-57, scorecard-v1

- A trial is one complete task including all permitted attempts, helpers, review and integration.
  Pair key = repository/task/repetition. The predeclared design enumerates every expected pair;
  absent, extra, conflicting or mismatched rows cannot be discarded to improve the result.
  A matched-input digest binds start state, acceptance, environment and equal budget; configuration
  digests identify the fixed baseline and the whole predeclared candidate family.
- Independent unit = repository, allowing arbitrary dependence across its tasks/repetitions/strata.
  Independence across repositories and a fixed design independent of outcomes are assumptions verified
  by the caller, not facts inferred from labels. Related repositories must share a cluster label.
  Estimand = expected acceptance difference for this fixed, trial-weighted, stratified design;
  it is not an equal-repository mean or a claim about unrepresented workloads.
- Decimal weights sum to exactly one (no epsilon); at least half their mass is complex. Every
  positive-weight stratum must occur. Explicit per-stratum floors, currency, pilot cost thresholds,
  family confidence, minimum repository count (at least two), cost/latency ceilings are immutable.
  Cost ratio defaults to the specification's 0.60 only when explicitly supplied in policy.
- Arithmetic uses exact decimal totals and DECIMAL128/HALF_EVEN divisions, with counts preserved.
  Unknown money or incomplete billing is never zero. Zero accepted gives undefined cost, E=0 and
  a failed floor. Token volume is diagnostic; investments stay separate. Repayment is undefined
  without positive weighted per-accepted saving or known investment cost.
- Inference: deterministic bounded-cluster Hoeffding lower bound, not a bootstrap approximation.
  For weights v_s (w_s overall; renormalized complex weights for complex), n_s fixed trial counts:
  d = sum_s v_s * mean(candidate - baseline); a_g = sum_s v_s * n_gs / n_s.
  Cluster contribution lies in [-a_g,a_g], independently across g. Apply the two-sided bound to
  obtain a conservative one-sided lower bound L=max(-1, d-sqrt(2*sum_g(a_g^2)*ln(2/alpha))).
  alpha=(1-confidence)/(2*M), M=number of predeclared candidates: union bound covers both endpoints
  and all candidates, including dependent candidates. No seed or resampling count is needed.
  Decimal directed rounding plus outward floating rounding protect the lower bound. Small cluster
  counts are inconclusive. Constant observed differences do not collapse uncertainty to zero.
  Both lower bounds must be strictly greater than -0.02 in fraction units.
- Source read before implementation: [Guntuboyina, Berkeley Stat 210b, Lecture 2, Theorem 0.1,
  pp. 2–3](https://www.stat.berkeley.edu/~aditya/resources/LectureTWO-210b-2014.pdf).
  This states the independent bounded-variable inequality and union-bound construction. Applying
  it to paired repository blocks is our derivation above. Original Hoeffding (1963) PDF mirrors
  could not be fetched; no claim is made to have read their full text in this session.
  This choice is deliberately conservative; limited power is reported, not corrected by tuning.
- Composite verdict requires floors, zero observed invariant violations/serious regressions,
  no observed complex loss, the preset economic tradeoff, ceilings and both paired bounds.
  Missing external integrity evidence keeps baseline. Synthetic data can demonstrate numerical
  eligibility only; the kernel never authorizes adoption or changes flags. Mandatory controls
  disabled in a research arm always disqualify it.

Expected cost: O(n log n + k*n) for canonical sorting and k strata (initial straightforward joins),
O(n+k+g) space; decimal arithmetic additionally depends on digit lengths. No new library.

## Predeclared verification

A: hand tables Q/E/score, failed attempts' cost, zero denominators, unknown billing, currencies,
floors, missing strata, Simpson-type complex loss, investment repayment, immutable inputs.
B: complete pairing, duplicates, frozen metadata, repeat correlation, unequal clusters,
one cluster, constant differences, row permutations; independent rational/formula oracle.
C: composite gates and deterministic synthetic simulation (java.util.Random seeds 614, 615,
616; 1,000 campaigns/model). Models: equal and unequal cluster sizes, repository-shared outcomes,
null, margin -0.02, harm -0.10, improvement +0.30, rare complex observations, four candidates.
Check estimator bias (within four Monte Carlo standard errors plus 0.005), simultaneous coverage,
and false noninferiority/promotion at margin/harm. Report event counts and one-sided 95% Wilson
Monte Carlo upper limits; require the upper limit <= family alpha=0.05. Power is reported,
not a tuned acceptance threshold. Missing pairs/degeneracy have separate deterministic checks.
Agreement of implementations alone does not validate coverage. No real promotion claim.

## Checkpoint

- **COMPLETE: P6.1.4 DONE**, A+B+C implemented; independent review found no required findings.
- Final Windows/JDK 26 focused and full-build eval runs: **18 tests, 0 failures/errors/skips** each,
  including 100 seed-614 arithmetic tables and 7,000 frozen simulation campaigns.
- Separate `:eval:updateKotlinAbi` and full `build --offline --console=plain --no-configuration-cache`
  pass; core and provider-api test tasks were UP-TO-DATE (803/0 failures/6 skips and 15/0 respectively).
  Their reports are prior results, not fresh executions. Linux/remote CI/live gates unchanged.
- API/assumptions and limitations: [eval README](../eval/README.md). Checkpoint commit contains this record.
- Return: P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2. Next proposal under this request: OOO-05.

## Verification log

- Initial sandbox run could not create the existing Gradle-cache lock outside the workspace; the
  approved escalated offline command used that cache. No dependency/toolchain change.
- RED A: test compilation failed on absent ScorePolicy/Scorecard API. GREEN A: 2 hand-table tests.
- RED B: absent PairedBound plus a test-lambda inference error; typed lambda parameters corrected.
- GREEN A/B: `:eval:test --tests 'io.astrolabe.eval.*' --offline --console=plain --no-configuration-cache`.
  Nine tests executed, all green. Baseline provider/core compilation and JAR tasks were UP-TO-DATE.
- RED C: absent PromotionReport/EvaluationEvidence; GREEN C: 13 focused tests, all green.
- Expanded run: **15 tests, 0 failures/errors/skips**, including all 7,000 simulated campaigns (15 seconds
  in the simulation test; this is test runtime, not a production benchmark). Independent formula oracle
  agrees within 1e-12 for point estimates/radii; all bias/coverage/false-eligibility checks pass.

| Model | Uncovered families / 1000 | Upper 95% MC bound | Both NI bounds pass | Numerical eligibility | Maximum absolute estimate bias |
|---|---:|---:|---:|---:|---:|
| null | 0 | 0.00269824 | 2 | 2 | 0.0045625 |
| margin | 1 | 0.00446972 | 1 | 1 | 0.001275 |
| harm | 0 | 0.00269824 | 0 | 0 | 0.0079 |
| improvement | 0 | 0.00269824 | 1000 | 1000 | 0.0021175 |
| unequal-margin | 1 | 0.00446972 | 1 | 1 | 0.001425 |
| rare-complex-margin | 0 | 0.00269824 | 0 | 0 | 0.019875 |
| family-margin | 0 | 0.00269824 | 0 | 0 | 0.0030375 |

For margin/harm rows the NI/eligibility counts are false positives; their MC bounds have the same
0/1-event values above. Null NI is not a false positive: true difference 0 exceeds margin -0.02.
Rare-complex bias has high MC variance and passed its predeclared SE-based criterion; it is not a
population bias estimate. These finite simulations support the implementation under the declared
models; the bounded-independent-block argument supplies the guarantee. No live adoption evidence.
- Review regression RED: moving one task's repeats between strata was accepted. Added a fixed-task
  stratum constraint to EvaluationDesign; GREEN: 18 tests including boundary/cost-ratio regressions.
- Independent read-only review (separate model, code-review-and-quality skill) checked statistical
  assumptions/formula, multiple selection, monetary/quality gates, unknowns, provenance and immutable
  snapshots: no required findings. It did not rerun Gradle. ABI inspected: only intended public eval
  records/results; no core/provider API changes, no suspend/Flow/value-class exposure.
- Final full build succeeded; no new dependencies, skipped tests, changed old acceptance or flags.
  Local checks do not close the pre-existing remote CI/Linux failures. Git diff/status/link/count
  validation is recorded with the final checkpoint; pushes remain with the owner.

## C details frozen before simulation execution

- Economic comparison is the policy-weighted mean of stratum cost-per-accepted values (same frozen
  workload mix for both arms), using exact cross-products for the 0.60/equal-cost decisions.
  Every positive-weight complex stratum also has a separate observed non-loss check. Ceilings apply
  to every weighted stratum's cost per accepted task and every candidate trial's latency, including failures.
  Repayment = max(0, candidate investment - baseline investment) / positive weighted saving.
  Unknown investment blocks repayment only; it does not falsify a known online-cost statistic.
- Seven simulation models, 1,000 campaigns each: equal-cluster null (delta 0, 80 repositories, seed 614),
  margin (-0.02, 80, seed 615), harm (-0.10, 80, seed 616), improvement (+0.30, 400, seed 614),
  unequal margin (-0.02, 80, 1..5 repetitions by repository, seed 615), rare-complex margin (-0.02,
  80, complex present every tenth repository, seed 616), four-candidate margin (-0.02, 80, seed 614).
  In single-candidate models P(gain)=0.4+delta/2, P(loss)=0.4-delta/2, P(both)=P(neither)=0.1.
  Entire repository shares its drawn pair, including cross-stratum and repeated observations.
  Four-candidate model shares Bernoulli(0.5) baseline and draws each Bernoulli(0.48) candidate;
  candidates may be dependent through baseline. Stratum weights are 0.5/0.5; design size is fixed.
  Simulation-only floors are 0.1 in each stratum; baseline/candidate cost per complete trial is 1/0.4.
  These choices expose the statistical gates without forcing the +0.30 model's baseline below a 0.4 floor.
- Simulations record any uncovered endpoint/candidate as one family error. On margin/harm models,
  any candidate passing both bounds is a false noninferiority event; any composite numerical pass
  is a false eligibility event. Bias is measured per candidate and endpoint with empirical Monte
  Carlo SE; power is reported for null/improvement. Lower-bound conservatism is expected.
- Monte Carlo upper limits use the approximate one-sided Wilson score formula, z=1.6448536269514722,
  as documented by [NIST](https://www.itl.nist.gov/div898/handbook/prc/section2/prc241.htm).
  All thresholds/seeds above precede simulation results; no tuning after a desired verdict.

Final bookkeeping check: 179 unique task IDs, 63 DONE / 4 IN_PROGRESS / 112 TODO; changed-document local file links resolve; `git diff --check` passes.
