# Core implementation notes

## Trace snapshots (P1.11.3)

`telemetry.TraceAnalytics.analyze(snapshot, limits)` is pure analytics over a caller-supplied,
versioned `TraceSnapshot`. A snapshot has one WorkId/currency; each span retains Phase, Identities,
parent, clock domain, start/end, recorded exclusive Money and open/completed/cancelled status.
Identical deliveries deduplicate (money equality is numerical); conflicting payloads, orphan/cyclic
ancestry, wrong work/currency, invalid ownership or causal graphs return InvalidInput. Recorded costs
propagate from leaves to parents, retaining unknown amounts; only root totals form totalCost.

`TraceUnit` is an explicit exclusive Work or Wait interval, not an inclusive span lifetime. Units must
fit their owner's known lifetime and cannot overlap other nonempty units of that span. Spans can have
concurrent children and children may outlive parents. For complete closed inputs in a single clock
domain, workerNanos sums Work durations, busyNanos measures their union, and bands/maxConcurrency
describe parallel work. Wait units contribute only to the explicit causal path. elapsedNanos is the
observed span envelope, separate from both activity and causal length. Coordinates are half-open Long
nanoseconds; duration differences/sums are BigInteger, without overflow or clamping skew to zero.

The causal DAG consists solely of supplied TraceEdges. Longest-path output includes a witness and
`complete`; incomplete causal/unit inventories give a known lower bound. Unknown duration or mixed
clock domains leaves the causal path unknown. Open spans/incomplete unit inventories leave complete
worker/busy totals unknown; closed elapsed can still be reported. Callers must transform clocks
explicitly before constructing a comparable snapshot and preserve that provenance. Completeness is
their attestation, not a conclusion drawn from parentage. Unknown Money remains unknown independently
of timing. Complete describes the supplied analytic model, not acceptance or runtime readiness.

Snapshot/results copy collections. Scalar skew/negative-cost/label errors throw IllegalArgumentException;
structural errors, incomplete data and decimal resource bounds have distinct statuses. Cost/causal
traversals are iterative O(V+E), sweep is O(U log U), with deterministic sorting and arbitrary-precision
arithmetic costs additional. P1.11.1/P1.11.2 retain runtime capture/emission, real completeness evidence,
native usage/pricing/reconciliation, FX-59 and exports. [Protocol](../audit/OUT-OF-ORDER-P1.11.3.md).

## Finite attempt costs (P4.5.4)

`route.AttemptCost.evaluate(policy, remainingAttempts, limits)` evaluates a frozen `AttemptPolicy`.
Each `Step` consumes one substantive attempt, charges its base plus the selected branch's auxiliary
cost, and visits the successor. Include conditional helpers, reviews and integration in branch charges.
At zero attempts a step charges `exhaustedCost` and ends Exhausted; a Terminal still charges its final
cost and records Accepted/Failed/Blocked/Cancelled/Exhausted. Accepted means complete acceptance.

Probabilities are supplied conditional finite decimals summing exactly to one; null remains unknown.
States must encode relevant history. All charges use one nonnegative currency. Exact arithmetic
preserves failure costs and all terminal masses; an unknown price leaves acceptance calculable but
cost null, while unknown reachable probabilities leave both unknown. Zero-probability edges do not
transmit unknowns. There is no statistical confidence bound or inference from marginal Profile rates.

`AttemptCost.select(input, limits)` checks supplied whole-policy eligibility and floor evidence,
then conservative cost against remaining money minus reserves, before minimizing expected cost.
The caller owns those facts; conservative cost is independent of the expectation. Budget bounds the
remaining attempt count. Unknown eligible alternatives prevent a minimum claim; ties use policy ID.
Selection creates no reservation and grants no dispatch authority. `E[cost]/P(accepted)` is not its objective.

Snapshots/results retain version/provenance and immutable collections. Malformed scalar/currency
values throw `IllegalArgumentException`; InvalidInput, Unknown and ResourceLimit are separate results.
Limits bound `(H+1)*V` and decimal precision/scale, including unreachable rows. The rolling recurrence
uses O(H*(V+E)) arithmetic operations and O(V+E) storage; decimal bit complexity is additional.
P4.5.1/P4.5.2 retain real outcome calibration, floor/eligibility evidence, Router/controller and FX gates.
[Model, proof, tests and checkpoint](../audit/OUT-OF-ORDER-P4.5.4.md).

## Symbolic write scopes (P5.1.5)

`workspace.ScopeAlgebra` compares `contract.Scope` records in one integration destination namespace:

```kotlin
val overlap = ScopeAlgebra.intersection(destination, leftScope, rightScope, limits)
val outside = ScopeAlgebra.difference(destination, childScope, parentScope, limits)
```

`ScopeSearchLimits` explicitly bounds NFA states, reached product states and explored transitions.
The returned `ScopeAnalysis` retains immutable scope snapshots, namespace, relation, limits and
actual counts. For intersection, `Disjoint` proves no common lexical path; `Overlap.witnessPath`
is a shortest common path. For difference, `Disjoint` proves lexical containment and a witness names
a child path outside the parent. `Unknown` identifies resource exhaustion or unsupported input.

The matcher preserves `PathPattern` semantics: bare filenames match at any depth, slashed literals
and directory prefixes include descendants, single stars stay within a segment, double stars span
directories. Bare `**` includes line terminators while regex double stars do not. Unicode is matched
as scalar values without normalization; malformed surrogate input is Unknown. Search uses a finite
character-class partition and complete iterative automaton traversal, not sampled paths.

Witness ordering is shortest code-point length, then `a..z`, `0..9`, `_`, `-`, `.`, `/`, then other
scalar values ascending. Canonical lexical paths exclude empty/dot/dot-dot segments, NUL/backslash,
absolute drive prefixes and wholly blank paths. A witness can name a future path or an OS-invalid
filename: this language is a portable lexical superset, not filesystem authorization. Real paths,
case aliases, symlinks and race checks remain with `WorkspacePath`/`ScopeGuard`. Different child
workspace IDs never prove non-conflict at the common destination. S3 remains gated/off.

The full grammar, proof outline, complexity, limits and independent oracle evidence are in the
[P5.1.5 protocol](../audit/OUT-OF-ORDER-P5.1.5.md). Run the focused `ScopeAlgebraTest` and `ScopeOracleTest`
with `:core:test`; public API changes require separate `:core:updateKotlinAbi` before the full build.

## Exact offline DAG sequencing (P4.5.5)

P4.5.5 provides `route.DagSchedule`, an exact comparison oracle for a small selected slice of a
`RequirementGraph`. It does not dispatch work or enable a routing policy. Supply `ScheduleProblem`
with selected IDs, explicitly completed external prerequisites, additional mandatory precedence
edges, a declaration that all order constraints are represented and frozen `ScheduleCosts`.
Only Pending increments are accepted; verified work is never rescheduled. If an external prerequisite
is present in the graph, its status must agree with the caller's completion declaration (Verified).
Contract/evidence validity and dispatch authority remain controller responsibilities.

`ScheduleContext` records the role/policy label, profile digest, S/R prefix digests and cache namespace.
Costs contain an initial charge for every selected ID, every directed pair's switch charge, a fixed
charge per ID, one currency, version and provenance. The complete matrix is required even for
transitions that dependencies would make unreachable. Unknown/missing charges or context give
`UnknownCosts`; negative or mixed-currency charges fail construction. Matching role/profile/context
never implies a zero charge. Represent incompatible cache states with their explicit cold price.

```kotlin
val result = DagSchedule.solve(problem, ScheduleLimits(
    maxIncrements = 16,
    maxTableEntries = 1_048_576,
    maxTransitions = 10_000_000,
))
```

The objective is fixed charges + initial(first) + pairwise switches; sums use exact decimal Money
without rounding. `Optimal` carries order, variable/fixed/total cost, input fingerprints, state count
and transition count. Empty selection has zero cost. Equal optima resolve deterministically by
ascending mask/last/next traversal and lowest final last ID, not lexicographically smallest whole order.
`HistoryDependent` costs (TTL, multiple retained prefixes or changing latency), or undeclared mandatory
constraints, yield `Unsupported`. Runtime scheduling hints/calibration/shadow evaluation and FX-45
stay in P4.5.3/P2.2.2/P6; synthetic optima establish no provider-billing savings.

The dense DP uses O(n²·2ⁿ) arithmetic operations and O(n·2ⁿ) table entries. Limits are explicit resource
policy. Table sizing uses Long arithmetic before allocation; n>30 and tables beyond JVM Int indexing
are refused. The configured entry limit should fit available heap (reference + parent per slot, plus
BigDecimal objects for reached states). `ResourceLimit` has no optimum/witness, including when the
transition limit is exhausted after exploring partial paths. See the
[model, tests and protocol](../audit/OUT-OF-ORDER-P4.5.5.md).
