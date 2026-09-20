# P6.1.5 — constrained workload partitioning (OOO-07)

Date: 2026-09-20. Baseline: clean `main` at `3e5c1ce`; working branch
`feature/out-of-order-kernels`. Authority: owner requested successive implementations in proposal §2
order, with protocols/documentation and a checkpoint within this session/context allowance. D-59.

## Admission and boundary

P6.1.2 requires the absent P6.1.1 runner. A standalone metadata-only partition kernel is useful
without it. Existing `eval` convention (P0.1.1) and `id.Digest`/`CanonicalEncoding` (P0.2.1) are DONE.
P6.1.4 supplies module-local immutable/fingerprint utilities, not a runtime manifest dependency.
P6.1.5 and D-59 were unoccupied; no partial implementation was found. No core -> eval dependency.
Parents retain frozen manifest production, answer-artifact removal, warm/cold memory, holdout access
tracking and FX-47 runtime validation. No candidate outcomes enter this API.

## Frozen model before implementation

- One task owns canonical metadata and an explicit nonempty set of repetition IDs. Task weight is
  nonnegative decimal mass counted ONCE, independently of the number of repeated trials. Total mass
  must be positive; complex mass must be at least half of it. Report task/trial counts and masses
  for every partition and declared stratum, including zero cells. No rows are silently excluded.
- Three partitions: Development, Selection, Final. Explicit policy selects repository/family
  must-links (neither/both allowed), additional task links, task-specific allowed partitions and
  optional half-open time windows. Combining rules means transitive union, never cutting a group.
  Unknown metadata required by the policy gives UnknownMetadata. Missing unused labels are allowed.
- Quotas specify inclusive min/max mass, target mass and nonnegative deviation penalty, for each
  partition total and any desired partition/stratum cells. Objective is exact decimal
  sum(penalty * abs(observed mass - target)). No statistical independence/holdout claim follows.
  Repetitions share metadata intrinsically; supplied assignments still identify each trial so the
  validator detects split repetitions, missing/extra trials and stale design fingerprints.
- Iterative DSU with rank/path compression then iterative branch-and-bound over sorted components
  and fixed partition order. Suffix attainable mass gives sound feasibility and objective bounds;
  overestimation of simultaneous attainability only weakens pruning. First optimal assignment wins
  deterministic ties. No recursion proportional to input size.
- Exact decimal addition/multiplication/comparison; no rounding in feasibility or objective. Frozen
  policy/design digests include typed null presence, normalized decimals, sorted membership and
  all quota/link/time/allowance fields. Limits count attempted assignment edges and are caller supplied.
- Optimal requires complete search (including sound pruning); Infeasible requires a static
  contradiction or exhausted search. SearchLimit can retain a feasible incumbent but never calls it
  optimal. Feasible is a successfully validated supplied assignment. InvalidInput covers malformed
  assignments; invalid construction parameters throw IllegalArgumentException.
- For n tasks, m links, c components, k=3 partitions and q quota cells: DSU O((n+m) alpha(n))
  after deterministic sorting; bounded search worst-case O(k^c * q), suffix storage O(c*q), stack O(c).
  Decimal bit complexity depends on input precision. Limits bound search, not input/preprocessing size.

Method reference inspected: [scikit-learn grouped cross-validation](https://scikit-learn.org/stable/modules/cross_validation.html#cross-validation-iterators-for-grouped-data)
distinguishes group isolation from stratification; its greedy approaches do not promise optimal balance.
The exact objective and search policy above are local choices, not scikit-learn defaults.

## Planned checks / execution log

A: RED API test, immutable policy/task records, must-link components and supplied-assignment validation.
B: bounded exact assignment and certificates. C: independent exhaustive trial/task assignment oracle
on seed 615 small tables, giant/rare/zero strata, transitive/time conflicts, repeated trials, decimal
boundaries, mutation/digests and permutations. Then review, separate eval ABI update and full build.

Checkpoint: admitted before code; checks pending. Return: P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2.

- A/B: first focused command failed at test compilation on absent Workload API (expected RED).
  `Workload.kt` and `WorkloadSplit.kt` then compiled and the repeated-task mass/validator test passed.
  Expanded focused run: 11 tests, zero failures/errors/skips; hybrid conflict, unknown metadata,
  complex weight, 20,000-task component, limit with/without incumbent, invalid assignment, exact
  decimals, time cutoff, immutable snapshots and exhaustive oracle. Oracle strengthened before final
  checks to 300 seed-615 tables, at least 100 feasible optimizations, plus reversed input/policy order.
- Environment note: Windows PowerShell's default native pipe encoding damaged non-ASCII characters
  in the first documentation script; detected immediately, repaired with apply_patch, subsequent
  scripts use UTF-8 explicitly. No source or pre-existing document text was lost.
- C: strengthened 300-table oracle passes (at least 100 feasible optima); input permutations preserve
  design fingerprint, assignment and search count. Eleven focused tests pass. Separate
  `:eval:updateKotlinAbi --offline --console=plain --no-configuration-cache` passed; ABI adds only
  Workload types. Full `build` with the same flags passed in 19 seconds on Windows/JDK 26:
  eval 29 tests executed (0 failures/errors/skips); core 813/0 failures/6 platform skips and
  provider-api 15/0 failures reused UP-TO-DATE results. Review pending; no CI/Linux/live claim.

Reproduction (PowerShell): set JAVA_HOME to
`C:/Users/user/.gradle/jdks/eclipse_adoptium-26-amd64-windows.2` and GRADLE_USER_HOME to
`C:/Users/user/.gradle`; focused command is `./gradlew.bat :eval:test --tests
'io.astrolabe.eval.WorkloadSplitTest' --offline --console=plain --no-configuration-cache`.
Metadata/quota preprocessing additionally costs O(n*q); valid output summaries scan supplied trial
membership for partition/stratum counts. Node limits do not bound these preprocessing/reporting scans.

- Independent review requested one correction: static design infeasibility hid malformed supplied
  assignments (e.g. zero-mass repeated task split across partitions). Validation now checks required
  metadata, then supplied assignments, then static design feasibility. Added regression for split
  zero-mass repeats, valid zero-mass assignment and no allowed partition. All 12 focused tests pass;
  full build rerun because production code changed. Public API unchanged by this correction.

## Final checkpoint

P6.1.5 DONE. Reviewer rechecked the correction: no remaining required findings. Final full Windows/
JDK 26 build passed in 19 seconds; eval **30 tests executed, zero failures/errors/skips**. Core
**813 tests, zero failures/errors, six platform skips** and provider-api **15 green results** were
UP-TO-DATE, not re-executed. ABI checks pass. No new dependencies, disabled tests or weakened gates.
Code/API guide/ABI/task-producer mapping and all handoff files included in the checkpoint commit.
Normal return remains P0 validation -> P1.8.2; continuing owner request permits admitting OOO-08 next.
