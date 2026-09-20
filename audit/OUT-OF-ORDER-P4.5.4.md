# P4.5.4 — finite attempt-policy cost evaluation (OOO-06)

Date: 2026-09-20. Baseline: clean `feature/out-of-order-kernels` at `955fb77`.
Authority: owner request to implement sequential proposals with protocols and documentation, within
one session / approximately 75% context. OOO-06 precedes OOO-09 and OOO-04. Decision D-61.

## Admission and ownership

P4.5.1 is not dependency-ready: earlier P4 work and controller/routing inputs are absent. P4.5.4
is an independent computational task, with explicit ready dependencies:

| Consumed type | Existing source | Producer/status |
|---|---|---|
| Money | provider-api/.../provider/Usage.kt | P0.3.3 DONE |
| Budget | core/.../budget/Budget.kt | P0.2.2 DONE |

New Attempt* projections belong to P4.5.4. They do not represent a runtime Router, Profile estimator,
or telemetry. P4.5.1/P4.5.2 retain conditional outcome calibration, real eligibility/floor evidence,
controller wiring, escalation and FX-31/32/55. Automatic demotion stays OFF. No existing acceptance
or dependencies are weakened; P4.5.5 has explicit independent dependencies.

## Frozen model before code

- A policy supplies named finite states, initial state, version and provenance. A terminal state
  supplies its complete-acceptance/failed/blocked/cancelled/exhausted outcome and final total charge.
  A step consumes exactly one substantive attempt and charges its base cost plus a conditional
  branch cost (helpers/review/integration), then visits the successor with one less attempt.
  Those auxiliary operations belong inside that attempt; they are not extra attempt-consuming states.
  History relevant to conditional probabilities must be encoded in the supplied state. No conversion
  from marginal Profile outcomes and no independence assumption across attempts.
- At zero remaining attempts a step charges its explicit exhaustion cleanup cost and produces only
  Exhausted. A terminal is still processed, including terminal cost, at zero attempts. Cycles are
  allowed because the horizon strictly decreases. The selector bounds remaining attempts by Budget.
- Exact finite-decimal BigDecimal addition/multiplication, no rounding/tolerance or renormalization:
  known probabilities lie in [0,1] and sum exactly to 1. A missing probability remains unknown even
  if subtraction could fill it in. Known partial mass must not exceed 1. One nonnegative currency.
  Invalid structure is rejected separately from unknown reachable data. Exact zero branches do not
  transmit unknowns. Unknown prices leave acceptance calculable; unknown probabilities make both
  distribution and cost unknown. Point estimates are not confidence bounds; intervals are deferred.
- Backward finite-horizon recurrence includes every failure and terminal cost. State values contain
  expected cost and all terminal masses. Induction on h proves equivalence to trajectory enumeration;
  terminal masses sum to 1 and Accepted is the complete-acceptance probability, not partial progress.
- O(H*(V+E)) arithmetic operations, O(V+E) storage with rolling state rows (terminal vocabulary fixed).
  Caller supplies max state evaluations as a resource limit, checked before work. Exact decimal bit
  sizes grow with horizon/input precision; arithmetic cost is additional, not assumed constant time.
- Selection first checks caller-supplied eligibility and floor evidence independently, then compares
  a supplied conservative charge with remaining cost minus explicit monetary reserves. Only then
  minimize expected total cost; ties use policy ID. Unknown admitted model prevents claiming a minimum.
  The result is an offline comparison, never authority or a runtime reservation. No E[cost]/P objective.
- Defensive immutable copies at boundaries; sorted state/branch/policy IDs make evaluation and
  diagnostics deterministic. Results retain the frozen policy, horizon and selection inputs.

Method reference inspected: [MIT/Bertsekas lecture 1](https://ocw.mit.edu/courses/6-231-dynamic-programming-and-stochastic-control-fall-2015/304cf17d604e774625dae63810777264_MIT6_231F15_Lec1.pdf),
finite-horizon additive costs and sufficient state. The state/charge/unknown/admission contract above
is a local implementation decision, not a new project default.

## Validation plan and execution

A: contract, RED cheap-start/fallback test; B: recurrence and terminal/exhaustion cases;
C: independent complete path enumeration on seeded small cyclic policies, probability/currency/unknown
edges, permutations, input mutation, limits and separate admission gates. Then review, ABI, full build,
API documentation, synchronized state and checkpoint commit. No new dependencies.

Admission recorded before code. Current task IN_PROGRESS; next: minimal RED test.
Return: P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2. Next proposal after full completion: OOO-09.

### Verified implementation checkpoint

- RED: `:core:test --tests io.astrolabe.route.AttemptCostTest` failed compilation because the API
  did not exist. GREEN: initial fallback example passed. Expanded suite: **15 tests, 0 failures/errors/
  skips**; 300 seed-454 cyclic policies, H=0..5, 1..5 step states and five terminal reasons, checked
  in two input orders. Independent oracle enumerates whole paths and accumulated costs at leaves.
- Recurrence: C_h(s)=base(s)+sum_b p_b*(branchCost_b+C_(h-1)(target_b)); distributions use the same
  weighted sum without charges. C_0(step)=exhaustedCost, outcome Exhausted; a terminal always costs
  terminalCost and gives its declared outcome. Splitting trajectories on their first edge and
  induction on h prove equivalence, unit terminal mass and termination even for self-loops.
- Sorting occurs once at construction. Work limit is (H+1)*V, calculated as Long; edge fan-out also
  contributes to O(H*(V+E)) work. `maxDecimalDigits` bounds precision and absolute scale before input
  arithmetic and at DP intermediates. Limits cover the complete supplied table, even unreachable
  states; exceeding one gives ResourceLimit, never a rounded answer. Scalar/currency errors throw;
  structural probability/reference errors return InvalidInput. Point estimates carry no confidence claim.
- Five-axis author review covered recurrence/units/terminal edges, domain ownership, immutable
  snapshots, validation and complexity. Fixed decimal checks occurring after probability addition
  and unbounded budget subtraction; extreme-scale/growing-precision regressions pass. This was
  author review, not independent-agent review. No remaining required findings identified.
- Core ABI updated/inspected: additive Attempt* records and static evaluate/select. Full build running.
  All commands use JDK 26, `--offline --console=plain --no-configuration-cache`; ABI update separate.

## Final checkpoint

P4.5.4 DONE. Full `./gradlew.bat build --offline --console=plain --no-configuration-cache` passed in
3m19s. Core **840 tests, 0 failures/errors, 6 existing platform skips** and eval **30 tests, 0 failures/
errors/skips** executed. Provider-api reused 15 green results. ABI checks passed; no new dependencies,
disabled tests or weakened requirements. API guide, producer/D-61 records and handoffs updated.
Counts **67/183 DONE, 4 IN_PROGRESS, 112 TODO**. Commit containing this checkpoint; no active override.
Normal return P0 validation -> P1.8.2; next authorized candidate OOO-09/P1.11.3, then OOO-04.
Parents, remote CI/Linux and live gates remain unchanged; no runtime/promotion claim.
