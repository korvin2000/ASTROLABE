# P3.2.7 — snapshot impact analysis (OOO-04)

Date: 2026-09-20. Baseline: clean `main` at `3f3edc4`.
Authority: owner request to implement the last proposal in order, on main, with protocols/docs.
P3.2.7 and D-63 checked free. Final status: DONE (commit containing this checkpoint). Return: P0 validation -> P1.8.2.

## Admission

P3.2.2 implicitly needs P3.2.1 (ImportGraph extraction/resolution), which is TODO. Its full
runtime implementation is not dependency-ready. Register a separate calculation kernel with explicit Deps.

| Consumed type | Existing file | Producer/status |
|---|---|---|
| WorkspaceId | core/src/main/kotlin/io/astrolabe/id/Ids.kt | P0.2.1 DONE |
| Defaults.theta | core/src/main/kotlin/io/astrolabe/Defaults.kt | P0.1.3 DONE |
| IndexTier | core/src/main/kotlin/io/astrolabe/atlas/Language.kt | P1.3.3 DONE |

The existing evidence.Closure uses unqualified paths and has no completeness attestation. This
kernel's check projection carries qualified file sets, containing suite and explicit completeness;
it is not a replacement receipt/reuse closure. It does not consume Closure, Atlas or SymbolIndex
instances. Producers must translate actual index/check/KB records into these narrow projections.
New Impact* records belong to P3.2.7. ImportGraph stays P3.2.1; runtime Impact assembly P3.2.2;
tools P3.2.3; nudges P3.2.4; scheduler/verify P3.2.5; pre-scan P3.2.6. No prerequisite cycle.

## Model frozen before code (D-63)

- Identity = (WorkspaceId, package ID or unknown, canonical workspace-relative path). Package ID
  is opaque; paths/labels are validated, never guessed or resolved by a language server.
- A versioned, provenanced immutable graph contains edges importer -> dependency, declared covered
  package/workspace scopes, tier, completeness and explicit unresolved dependencies. Reverse BFS
  includes E, visits each vertex once, permits cycles/self-edges and never traverses forward imports.
  Missing edited vertices, lexical tier, incomplete coverage or unresolved dependencies prevent a
  complete blast claim. Missing edge endpoints are invalid input, not an invented graph node.
- Tests join by test file in blast or supplied naming target in blast. Acceptance/check closures
  intersect blast, including transitive-only intersections. Unknown/partial closures retain their
  check and require its containing package suite; unknown package means workspace suite. Incomplete
  graphs require all covered/represented scopes and retain all supplied checks, including disconnected
  unresolved importers. Scope requirements are data, never executed commands or receipt reuse proofs.
- CON/ADR projections intersect anchors with E, not blast. Missing/partial anchors and an incomplete
  contract inventory remain explicit uncertainty; empty touched IDs cannot prove absence in that case.
- A supplied complete diff uses zero-based half-open old/new line ranges. changed_lines = deleted +
  added (replacement counts both), summed with BigInteger. Exact duplicate hunks deduplicate;
  other overlaps in either old or new coordinates are invalid, so they cannot inflate risk or cancel
  as signed deltas. Zero-length ranges do not overlap. A null hunk inventory is unknown (pre-scan).
- Each hunk supplies its enclosing-symbol label and fan-in count/tier/completeness; no inference from
  imports. Known counts use Double log2(1+fanin), deterministic summation order. Null fan-in makes
  the total estimate unknown; an incomplete numeric count remains an estimate, never proven zero.
  risk completeness is separate from graph and contract completeness. Compare strictly > Defaults.theta;
  uncertain risk has unknown threshold verdict and conservatively requests early slow checks.
- Inputs/results defensively copy collections. Invalid scalar/structural inputs throw
  IllegalArgumentException. Missing information is an explicit partial result, not an exception.
- Complexity: reverse-adjacency construction/BFS O(V+E), plus deterministic sorting. Joins scan
  supplied test/naming/closure/anchor memberships with hash lookups. Hunk sort/overlap validation
  O(H log H); sums account separately for BigInteger arithmetic. No recursion or new dependency.
- Oracle: independent Floyd-Warshall on small seeded directed graphs (including cycles), direct
  set joins, and integer line-set checks; hand examples 40/41, incomplete runtime edges (FX-37
  calculation only), transitive check intersection (FX-54 calculation only). Neither runtime fixture
  is declared complete before its producer/consumer path exists.

## Checkpoints

- Admission recorded before tests/code. Next: minimal failing test, then snapshot/reachability slice.
- Baseline historical full build: core 855 tests/0 failures/errors/6 platform skips; eval 30 green;
  provider-api 15 green results. This is not validation of the forthcoming diff.

## Calculation and oracle checkpoint

- Minimal RED test failed compilation on absent ImpactScope. Initial GREEN passed reverse reachability;
  the first implementation compile required explicit comparator type parameters (no semantic change).
- Expanded focused suite: 19 tests, 0 failures/errors/skips. Seed 327: 500 small directed graphs in two
  permutations, including cycles/self-edges, compared with independent dense Floyd-Warshall closure
  and direct set-intersection joins. Seed 3271: 300 hunk sets in two permutations with duplicate delivery,
  compared with integer old/new line enumeration and exact power-of-two fan-in factors. No random flakiness.
- Manual cases cover 40/41 and custom theta, add/delete/replacement, duplicate/overlapping hunks,
  Long.MAX_VALUE counts (BigInteger total), null versus incomplete numeric fan-in, namespaces,
  unknown/partial closures/anchors, unknown graph coverage, runtime-only unresolved edges, and immutable
  nested input/output collections. A 20,000-file import cycle terminates without recursion.
- Proof: the BFS visited set initially equals E. Processing v adds precisely its recorded importers;
  each newly added vertex is queued once. Induction gives only reverse-reachable vertices, and any
  reverse path is discovered in length order. Cycles cannot cause a second enqueue. Hash joins use
  the final blast for tests and closures, and the original E for contract anchors. Sorting each
  coordinate's nonempty hunk ranges detects every overlap by comparing each start to the prior end;
  disjoint ranges and exact duplicate dedup count each added/deleted line once. Missing observations
  never participate as proof of zero. Compensated summation preserves deterministic numeric estimates.
- Author five-axis review found unknown package identity could retain a complete attestation despite
  an unmatchable qualification. Regression first FAILED at blastComplete; corrected graph/check/anchor
  uncertainty propagation. Unknown file packages now force workspace-suite requirements for graph/check
  selection and prevent contract absence claims. This refines D-63's existing unknown policy, not acceptance.
  Readability/ownership/security/performance review found no other required changes: no IO, dependencies,
  authority expansion, recursion, disabled tests or fabricated reuse proofs. This is author review,
  not an independent-agent review. Oracles are independent algorithms, not independent acceptance.
- Commands use installed JDK 26 and wrapper, `--offline --console=plain --no-configuration-cache`:
  `:core:test --tests io.astrolabe.atlas.ImpactTest` (initial 1, then 16 GREEN),
  `:core:test --tests io.astrolabe.atlas.Impact*Test` (19 GREEN),
  `:core:test --tests '*ImpactTest.unknown file packages*'` (1 expected failure),
  `:core:test --tests io.astrolabe.atlas.*` (post-review: 76 tests, 0 failures/errors/skips; 20 impact tests included).
  Filtered XML is replaced by each run; the counts above preserve earlier evidence.
- At this intermediate checkpoint: implementation/oracles/review complete; next ABI update and full build.

## Full-build diagnostic

The first full build observed the previously recorded FX-22 polling race at RunTest.kt:255:
`run #1 running ... handle handle-1 running ... bg-end`. The test expects the first poll returning
the final output to have a terminal status; output visibility can precede process termination.
This exact signature already exists in [P2.1.1's protocol](OUT-OF-ORDER-P2.1.1.md).
LocalOs.poll reads status before log bytes and intentionally returns as soon as bytes arrive; a final
line is not itself process-termination evidence. No run/OS/supervisor code is changed by this kernel.
First full run: 3m17s, core **875 tests, 1 failure, 0 errors, 6 existing platform skips**; eval **30
tests, 0 failures/errors/skips** executed; provider-api reused **15 green results**. Failed XML/logs
copied before the filtered rerun to `build/verification/p3.2.7/first-full-build/` (ignored local evidence).
Preserve this failure even if isolated/full reruns pass; a rerun does not fix the existing polling-timing
issue. Pending: isolated FX-22 reproduction and another full build. No tests/thresholds will be disabled.

Isolated `:core:test --tests '*RunTest.a background run*'` passed (1 test, 0 failures/errors/skips,
11s). A second full build is running on the unchanged source/test tree; this repeat addresses the
unresolved first-run failure, not an unnecessary rerun of already-green checks. FX-22 remains with
the existing P0.6.1/P0.6.4 validation investigation; it is not claimed fixed here.


## Final checkpoint and resume

The final `./gradlew.bat build --offline --console=plain --no-configuration-cache` passed in 3m15s on Windows/JDK 26.
Core **875 tests, 0 failures/errors, 6 existing platform skips** executed; eval **30** and provider-api
**15** green results were UP-TO-DATE. Eval also executed successfully in the first full run. The 20 new
impact tests include the review regression; the complete atlas run passed 76 tests. API dump is additive
only (179 lines); old public APIs and all 184 pre-existing task statuses/acceptance remain unchanged.

P3.2.7 DONE, D-63, on main. All nine proposals are now complete. TODO producer/decision/task/card records,
API guide, navigation integration note, changelog and both handoffs describe this same checkpoint.
Final counts: **69/185 DONE, 4 IN_PROGRESS, 112 TODO**. No ACTIVE override. Normal return:
**P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2**. Do not implement this kernel again. P3.2.1–P3.2.6 retain
all runtime producers/consumers and full FX-37/54. Remote CI/Linux remain open, live gates UNMEASURED.
The repeated FX-22 failure is evidence for the existing P0 investigation, not a repaired runtime claim.
Final unique task/decision IDs, producer prerequisites, unchanged parent statuses, all nine proposal
dispositions, handoff counts, local file links and `git diff --check` passed before the checkpoint commit.
