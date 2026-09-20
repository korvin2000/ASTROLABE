# P1.11.3 — immutable trace analytics (OOO-09)

Date: 2026-09-20. Baseline: clean `817a32b`, `feature/out-of-order-kernels`, P4.5.4 DONE.
Authority: continuing owner request for ordered out-of-order implementation with protocols/checkpoints.
P1.11.3 and D-62 checked free. OOO-09 precedes OOO-04. Normal return P0 validation -> P1.8.2.

**Final status: DONE**, commit containing this checkpoint. Final evidence is recorded below.

## Admission

P1.11.1/P1.11.2 require absent runtime producers; retain their full metrics/emission/accounting/export
scope. The independent snapshot kernel consumes only ready contracts:

| Type | Source | Producer/status |
|---|---|---|
| SpanId, Phase | core/.../event/AgentEvent.kt | P0.4.1 DONE |
| WorkId, Identities | core/.../id/Ids.kt | P0.2.1 DONE |
| Money | provider-api/.../provider/Usage.kt | P0.3.3 DONE |

New Trace* types belong to this kernel, not runtime Span. No store/emission, invoices, reconciliation,
OpenTelemetry exporter or live measurement. Source: adapters §§15.2/15.5, evaluation §19.4,
proposal §8.5/§9.1. Parent integration and FX-59 remain untouched.

## Model frozen before code (D-62)

- A versioned/provenanced snapshot concerns one WorkId, one currency, and can contain multiple roots,
  attempts and contexts. SpanId identifies a delivered payload within this snapshot. Identical spans
  and execution units deduplicate; conflicting payloads, missing parents/owners/edge targets, ancestry
  or causal cycles are InvalidInput, without a fabricated total. Numerically equal money decimals
  compare equally for deduplication. Results retain immutable supplied inputs and diagnostics.
- Parent/child edges define only exclusive/inclusive MONEY. Bottom-up leaf accumulation adds each
  span's exclusive Money exactly once; only root inclusive totals form the grand total. Unknown Money
  retains its known amount and unknown flag and propagates to ancestors. Children may outlive parents
  (asynchronous spans); ancestry does not impose causal or lifetime containment between spans.
- Explicit named TraceUnit execution/wait intervals are owned by spans, independent of span lifetimes.
  They represent exclusive activity, never inclusive parent durations. Units of the same span must not
  overlap and must lie inside its known lifetime. Concurrent work must have distinct producing spans.
  Caller attests unit completeness. Wait units contribute to critical path, not worker/busy time.
- Timestamps are signed Long nanosecond coordinates in the owner's clock domain. Each end is either
  >= start or absent; wrap/skew is rejected, no clamping. BigInteger differences/sums protect duration
  and elapsed arithmetic across Long extremes. A single domain is required for cross-span timing;
  caller must explicitly transform timestamps before input if using several clocks. No inferred offset.
- On closed, complete, comparable inputs, worker time sums Work-unit durations; busy time and
  concurrency bands come from an endpoint sweep. Intervals are [start,end), simultaneous endpoint
  deltas are combined before the next segment; zero-length intervals add no workers. Gaps have zero
  concurrency. Elapsed time is max span end - min span start, separately from activity and causality.
  Open/missing end, mixed clocks, or incomplete units leave relevant complete totals unknown. Closed
  known span elapsed can remain available when only the unit inventory is incomplete.
- The caller supplies a separate DAG over execution AND wait units. Each explicit edge means predecessor
  end <= successor start in the comparable domain; contradictory edges are invalid/skew, never repaired.
  Kahn longest-path DP sums unit durations and returns a deterministic witness. No parent edge is added.
  With incomplete causality/unit inventory, the computed path is only a known lower bound. Missing unit
  duration/clock comparability makes it Unknown. Even complete causal path is distinct from observed elapsed;
  omitted resource/wait edges may only be certified complete by the caller, not inferred from the tree.
- Cost tree and causal DP O(V+E), endpoint sweep O(U log U), deterministic input sorting additional.
  Iterative queues avoid stack overflow on deep traces. BigInteger/BigDecimal bit complexity additional;
  decimal precision/scale bounded by an explicit analysis limit, no rounding or currency conversion.

## Planned validation

RED overlapping [0,6), [4,10) => workers 12, busy 10, peak 2 and root cost 1+2+3=6.
Then dedup/conflict/orphan/cycles, money/clock/open/cancelled states, adjacent/zero intervals, large
counters/deep trees, independent integer-cell sweep and exhaustive small-DAG paths, permutations and
mutation protection. Author review, ABI/full build, API guide, synchronized state and commit.
At admission: IN_PROGRESS, next RED test. After DONE next authorized proposal OOO-04.

## Verified implementation checkpoint

- RED overlap test failed compilation on the absent API; GREEN after the implementation. Expanded
  trace suite passed **15 tests, zero failures/errors/skips**, including 300 seed-1113 traces in two
  permutations, integer-cell occupancy (worker/busy/peak/every profile band), ancestor-walk inclusive
  cost oracle, and independent exhaustive path enumeration with witness validation.
- Manual cases include worker=12/busy=10/peak=2, 1+2+3 root cost=6, explicit wait path=10 vs worker=4
  vs observed elapsed=20, zero/adjacent/gapped intervals, multi-root/attempt/asynchronous children,
  open/cancelled/unknown/mixed-clock cases, currency/ref/ownership/causal violations and numeric limits.
  Long.MIN_VALUE..Long.MAX_VALUE produces 18446744073709551615 ns, two workers twice that number.
  20,000-node ancestry and 5,000-unit causal chains run without recursion in production.
- Proof: each leaf's inclusive cost is final before it is added once to its sole parent; remaining
  child count reaches zero exactly after all contributions. Processing fewer than V nodes proves a
  cycle (references already validated). Summing only root totals equals summing exclusive charges.
  Sweep active count is constant between adjacent endpoint coordinates; sum active*length is worker
  time and sum length for active>0 is busy time. Equal timestamps use one net delta, preserving [a,b).
  Kahn order makes all predecessor lengths final before relaxation; path reconstruction follows only
  retained explicit edges. No ancestry duration is introduced into this recurrence.
- Author five-axis review found order-dependent dedup counts/diagnostics for conflicting deliveries.
  Regression first FAILED (expected 1 duplicate, got 0 after reversal). Fixed by deduplicating distinct
  normalized payloads per ID and refusing conflicts before choosing ancestry/ownership. Expanded
  suite now passes. Review also checked unknown propagation, complete-vs-lower-bound claims, skew,
  immutable results, namespaces, finite iterative work and dependency scope; no remaining required
  findings identified. This is author review, not an independent-agent review.
- Core ABI updated separately; full build next. All commands use installed JDK 26 and Gradle wrapper,
  `--offline --console=plain --no-configuration-cache`. `:core:test --tests
  io.astrolabe.telemetry.TraceAnalyticsTest` replaces filtered XML reports. Earlier full P4.5.4 result
  (840/6 core, 30 eval) remains historical; do not mistake it for validation of this trace diff.

## Final checkpoint and resume

P1.11.3 DONE. `./gradlew.bat build --offline --console=plain --no-configuration-cache` passed in
**3m18s** on Windows/JDK 26. Core **855 tests, 0 failures/errors, 6 existing platform skips** and
eval **30 tests, 0 failures/errors/skips** executed; provider-api reused **15 green results**.
ABI checked and inspected (additions only); no disabled tests/stubs/new dependencies. API docs,
producer/D-62 records, proposal queue and both handoffs synchronized. Final unique-ID/count, local-link,
queue and `git diff --check` checks passed; OOO-04 is the sole remaining proposal and is not registered.

Session completed **OOO-06/P4.5.4** (`817a32b`) then **OOO-09/P1.11.3**, with 30 new tests and
600 seeded oracle cases. **68/184 DONE, 4 IN_PROGRESS, 112 TODO**. No active override. Stop at this
complete checkpoint to preserve a bounded session and resumable state. Next proposal **OOO-04**,
candidate P3.2.7, remains unregistered/unstarted; check D-63 and producer readiness before its code.
Normal return remains P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2. P1.11.1/P1.11.2 keep runtime capture,
full metrics/causality/inventory evidence, invoices/reconciliation/export and FX-59. No CI/Linux or
live gate is closed by these local kernel tests; all live gates remain UNMEASURED.
