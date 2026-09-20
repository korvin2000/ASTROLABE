# P2.3.4 — dependency-aware context selection (OOO-01)

Date: 2026-09-20. Baseline: clean `main` at `2c78d75`. Authority: owner requested selecting
and implementing proposed out-of-order tasks. TODO §1.2 is the execution authority.

## Selection and admission

Selected OOO-01 first by proposal ranking, then OOO-03: both deliver useful pure components
with existing producers. OOO-02 remains proposed: its independent evaluator, frozen statistical
design and simulation validation are a separate larger scope. OOO-04 remains proposed: graph
identity/completeness and scheduler projections deserve their own admission pass. Neither is blocked
by an invented dependency on this work; the owner asked us to select tasks, not implement all four.

| Consumed type | Existing file | Producer | Readiness |
|---|---|---|---|
| Tokens | core/src/main/kotlin/io/astrolabe/budget/Tokens.kt | P0.2.2 | DONE |
| New context selection projections | core/context/ContextCover.kt | P2.3.4 | This component |

P2.3.1 keeps P1.9.3/P2.4/P2.6/P1.3.1 prerequisites. It will convert real Note, CarryForward,
contract and seed sources to these projections, check versions and coverage, and render every part.
P2.3.2 persists manifests; P2.3.3 performs actual dispatch admission. No compiler or KB substitute.

## Frozen implementation policy (D-55, context-cover-v1)

- Identity is a canonical context-unit ID. Edges point from a unit to its prerequisites.
- Inputs carry nonnegative Long token counts, estimator/source labels and exact/estimated flags.
  Utility is a caller-supplied nonnegative integer in common points, not a learned quality estimate.
- Mandatory roots and every already-present S/R unit include their full transitive closure.
  Missing prerequisites or reachable cycles prevent selecting that root; mandatory faults prevent Fit.
  On refusal retain all existing members of the mandatory closure and diagnostic references.
- Optional order: affected contracts, carry-forward, seeds, local implementation, lessons/pitfalls,
  skills, background. Among feasible roots in the highest available class choose the greatest sum
  of newly covered utility / newly charged K tokens, then smallest canonical root ID. Dependencies
  can cross classes. Positive utility at zero cost precedes finite ratios; zero utility is skipped.
- Recompute every candidate's marginal cost/gain after every pick. Shared dependencies are charged
  once. Do not use stale heap priorities. This is a heuristic without an approximation guarantee.
- Effective limit = floor(profileTokens × decimal alpha), 0 < alpha ≤ 1. Subtract S, R,
  pinned history, retained protocol, separately priced effective history and reserves. Those inputs
  must be disjoint serialized contributions. Unknown effective history produces UnknownHistory;
  known charges and all mandatory diagnostics remain visible. UnknownHistory takes precedence
  over NeedsEvidence, then Capacity; other causes remain inspectable. No unknown-to-zero conversion.
- S/R units represent content already included in the matching segment charge; their total cost
  cannot exceed that charge. Their IDs count for selection/coverage, their cost is not added to K.
- BigInteger sums/cross-products and BigDecimal flooring prevent overflow and optimistic rounding.
  Fixed cost may exceed the limit; negative available budget is retained, not clamped to zero.
- Inputs and outputs own their collections. Source costs, policy version, mandatory IDs, ordered
  picks and omissions remain available for audit. Fit means planning fit only, never admission.

For n supplied vertices and m dependency references (including missing targets), iterative root
closures take O(n(n+m) log(n+m)) time including sorted-map lookups and diagnostic sorting.
Per-root missing-target diagnostics can occupy O(nm); total space is O(n(n+m)), rather than a
valid-DAG-only O(n²+m) bound. At most n greedy rounds scan n closures of size n: O(n³) arithmetic
operations and O(n³ log n) time including deterministic added-ID sorting and map lookups.
BigInteger arithmetic additionally depends on bit length. This simple bounded-candidate algorithm
is deliberate; optimize only against a measured compiler workload.

For a Fit result, the base set is a union of valid dependency closures. Every pick unions another
valid closure with it, so closure is preserved inductively. Charging only set difference makes each
K unit contribute once; S/R charges stay in the fixed subtotal. The exact remaining-budget check
preserves capacity. Every pick adds its previously absent root, so there are at most n picks.
Sorted root IDs, dependencies, diagnostics and exact comparisons make input permutation irrelevant.

## Implementation and verification sequence

1. Register task, producer mapping, D-55, ACTIVE card and all handoffs before code (done).
2. Add failing boundary/diamond tests, implement kernel, run focused context tests.
3. Add cycles, omissions, zero costs, large counters, mutation and permutation checks; compare a
   fixed-seed random DAG corpus with independent slow oracle. Enumerate small closed subsets to
   verify feasibility and measure objective gaps, without asserting equality to the optimum.
4. Review public contracts, correctness, determinism and scope; regenerate ABI in a separate run;
   run full offline Windows/JDK 26 build, record executed/cached/skipped results.
5. Mark only this kernel DONE, update all handoffs/index and commit code/tests/API/docs together.
   Continue selected OOO-03, then return to P0 validation → P1.8.2.

## Verification log

- RED: focused test compilation failed on the missing ContextCover API, as expected.
- GREEN: first two diamond/shared-dependency tests passed; expanded context suite: **11 tests,
  zero failures/errors/skips** on Windows/JDK 26.
- Independent Floyd–Warshall closure + small-number ratio oracle: seed **2354**, 160 random DAGs,
  8 vertices each; greedy selections/order and shuffled-input results agree. Exhaustive subset
  enumeration found 142 feasible mandatory sets, aggregate utility gap 76, maximum gap 21.
  These are synthetic utility-point gaps, not quality or approximation guarantees. Explicit
  knapsack counterexample: utility 12 vs optimum 18. Deep-chain case: 700 vertices.
- ABI regenerated with `:core:updateKotlinAbi --offline --console=plain --no-configuration-cache`;
  inspected 179 additive API lines, no existing API deletion/change.
- First full Windows build passed: core **788 tests, zero failures/errors, six platform skips**;
  provider-api UP-TO-DATE (15 green results).
- Independent review identified ambiguous combined refusal precedence and an understated complexity
  bound for invalid graphs. Added a combined missing-dependency/unknown-history regression: failed
  before moving UnknownHistory first, then passed. Added highest-feasible-priority/cross-class coverage.
  Corrected the complexity statement above, including diagnostic space and candidate sorting.
- Final focused suite: **13 tests passed, zero failures/errors/skips**. Follow-up independent review
  found no blocking findings. Final full build after the correction passed: **core 790 tests, zero failures/errors, six platform skips**; provider-api UP-TO-DATE (15 green results); ABI checks pass. Kernel DONE, parent integrations remain pending. Checkpoint commit includes this journal; next selected component is OOO-03/P2.6.5.

All Gradle invocations set JAVA_HOME to the recorded Temurin 26 path and GRADLE_USER_HOME to
`C:\Users\user\.gradle`. Focused command: `:core:test --tests 'io.astrolabe.context.*' --offline
--console=plain --no-configuration-cache`; integration command: `build` with the same flags.
Linux/remote CI findings and all live gates remain unchanged.
