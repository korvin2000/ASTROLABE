# P2.1.1 — out-of-order analytical implementation

Date: 2026-09-20. Baseline: `d0ca86a`. Authority: the owner's request to prioritize analytical,
mathematical and algorithmic work while preserving the normal resume sequence and code integrity.
Execution status is authoritative in `TODO.md` §1.2; this file records decisions and evidence.

## Selection and boundaries

P2.1.1 is dependency-ready as a component: the contract, identities, and verifier already exist.
It contains a graph-theoretic core with independently checkable results. Iterative Tarjan detects
strongly connected components; Kahn traversal computes the longest prerequisite depth. A stable
depth/id ordering produces a bounded frontier. Neither algorithm recurses on the JVM stack.

The more statistical P2.6.4 calibration and P6.1.3 promotion-scorecard tasks require absent sizing,
controller or evaluation infrastructure. P2.3.1 context cover requires the absent compiler/KB path.
Their parent tasks remain TODO; no placeholder implementation or fictitious completion is recorded.

Selection is based on task characteristics. Official OpenAI model guidance describes Astra's
complex reasoning/science capabilities but does not establish a particular mathematics benchmark
rank: [official model guidance](https://developers.openai.com/api/docs/guides/latest-model).

## Implementation contract

- Keep `contract.Increment` and `contract.Ledger` in their existing packages. Extend Increment with
  defaulted fields; old S0 source calls and serialized records remain supported. Regenerate Kotlin ABI;
  this development snapshot does not promise binary compatibility with precompiled old constructors.
- Reject unknown references, uncovered requirements/acceptance, nodes without a concrete product,
  and nodes without executable acceptance or a check with a named evidence kind.
- Report exact cyclic SCCs, including self-loops, as planning conflicts. Keep cancelled history;
  cancellation is never evidence of a completed prerequisite. A replacement plan explicitly rewires it.
- Cell availability is a count AFTER reservations, not a token/cost estimate. Frontier selection
  receives the current contract and confers no dispatch authority; actual token/money/lease checks
  remain with the controller. Prerequisites from another work, attempt or contract revision do not unlock it.
- Preserve `Verified` execution history after candidate changes. Only regression evidence is refreshed;
  verified increments never become implementation continuations (FX-42).
- Derive a requirement's ledger row from all of its assigned active increments and their evidence.
  A partial increment's S0 verifier ledger is insufficient for S1 requirement completion.
- Graph snapshots and verifier results are host/controller records, not model-authored authority.
  P2.1.2 must sanitize proposals before committing them. No local data class is an authentication token.
- Graph construction, copy and deserialization defensively own all nested lists/maps. The verifier binds
  work, attempt, context, contract revision and the canonical increment definition. A result from an older
  continuation cell or a changed plan is refused. Run receipt IDs remain separately available; check
  assessment references and review request references are retained in the complete evidence list.
- Requirement dependency edges must be represented by transitive increment prerequisites or a joint
  increment. Stale prerequisite evidence propagates through the requirement ledger, including cycles.

SCC and depth traversal take O(V + E) before deterministic sorting, with O(V + E) memory. Frontier
sorting takes O(V log V); canonical definition hashing is performed per verified node, not per outgoing
edge. Checking the contract's dependency-to-increment mapping performs a bounded reachability search
for each of K increments that has requirement prerequisites: worst case O(K(V + E)). It stops when all
required producers are found. The graph is not claimed to solve an optimal scheduling problem.

## Verification log

1. Read the three handoff/plan files, architecture invariants and component ownership, §4.2, and existing
   contract, scope, contract-slice and verifier consumers. The owner committed the dirty audit documents
   as `d0ca86a` during selection; implementation began against that clean checkpoint.
2. Recorded ACTIVE P2.1.1 and the normal-queue return point in all three handoff files before code edits.
3. Added graph tests first. Initial test compilation failed on the absent graph API as expected.
4. Implemented the component. Eleven graph tests passed, including 100 random cyclic graphs checked
   against a Floyd–Warshall reachability oracle and a 20,000-node chain.
5. Added a replay regression: changing a plan after verification incorrectly accepted the old result.
   The test failed before the fix. Bound the verifier result to work, contract revision and canonical
   increment definition; 37 focused graph/contract/verification tests then passed, no skips.
6. Independent read-only review checked SCC/Kahn correctness, evidence, dependency semantics and
   complexity. Addressed work/attempt/revision/context replay, requirement dependencies, nested mutation,
   check/review evidence references and repeated prerequisite hashing. Eighteen graph tests now cover these
   cases. The follow-up review confirmed the graph corrections and identified an inherited verifier gap:
   review/acceptance-surface approvals did not check the current candidate. A new test failed before the fix;
   the verifier now checks their candidate (and integrity-approval contract revision). The final focused
   graph + consumer run passed **44 tests, zero failures/errors/skips** (18 graph + 26 existing-consumer tests).
7. The first full build, before review refinements, executed 770 core tests: **one failure, six skips**.
   `RunTest.a background run ... FX-22` observed `bg-end` while the polled status was still `running`
   (`RunTest.kt:255`). The separate rerun passed. The implementation/test of OS/run was not changed;
   this is a recorded polling-timing failure, not an established graph regression or a claimed fix.
   The failed XML is retained locally at `build/verification/p2.1.1/first-full-build/` (ignored build output).
   Keep this evidence with the reopened P0.6.1/P0.6.4 validation investigation.
8. Kotlin ABI regenerated in its own Gradle invocation. An intermediate full build passed after the main
   review refinements. The **final full build passed** after the verifier currency correction: core executed
   **777 tests, zero failures/errors, six existing platform skips**; provider-api reused 15 green test results.
   ABI checks passed. The log is `build/verification/p2.1.1/final-build.log`. The 19 added tests comprise 18 graph tests and one verifier
   currency regression. No existing test was disabled or deleted; the verifier test's expected evidence and
   refusal diagnostics were strengthened to include the newly retained refs and stale-review reason.
9. Marked P2.1.1 DONE at component scope, closed the out-of-order override and synchronized all three handoff
   documents. Recounted 176 task headings: 60 DONE, four IN_PROGRESS, 112 TODO (34.1% by task count).
   The normal queue and all remote/platform/live validation obligations are retained.

Reproduce on the recorded Windows JDK/cache (no live provider or network is needed):

```powershell
$env:JAVA_HOME = 'C:\Users\user\.gradle\jdks\eclipse_adoptium-26-amd64-windows.2'
$env:GRADLE_USER_HOME = 'C:\Users\user\.gradle'
./gradlew.bat :core:test --tests 'io.astrolabe.graph.*' --tests 'io.astrolabe.contract.*' --tests 'io.astrolabe.verify.ExitGateTest' --tests 'io.astrolabe.verify.ChecksTest' --tests 'io.astrolabe.verify.ScopeGuardTest' --offline --console=plain --no-configuration-cache
./gradlew.bat :core:updateKotlinAbi --offline --console=plain --no-configuration-cache
./gradlew.bat build --offline --console=plain --no-configuration-cache
```

Filtered test runs replace the core XML report. The full build may reuse unchanged provider-api test
results; distinguish executed tests from cached reports. Linux and remote CI are not validated here.

## Integration and resume ownership

| Next owner | Required use of this component |
|---|---|
| P2.1.2 | Validate sanitized plan proposals against the current authorized contract; reject forged runtime fields |
| P2.1.4 | Collect and persist sizing from actual cell outcomes; do not infer counters from retries of a transition |
| P2.2.2 | Persist graph/ledger transitions under one controller, enforce candidate/lease/generation and remaining budget; commit `graph.ledger(...)` for S1, not the S0 per-increment ledger |
| P2.2.4 | Restore graph snapshots from canonical SQLite records with evidence; reconcile actions before dispatch |
| P3.1.2 | Supply validated cross-candidate reuse proofs; current graph currency deliberately uses exact stamps |

No controller, durable graph repository, phase promotion or live benchmark is implemented by this slice.
The graph consumes the existing verifier's assessment semantics; `Assessment` is revision-bound and has no
candidate field. The controller must obtain current check assessments rather than treating graph evidence
as permission to replay arbitrary old assessments. This slice adds candidate checks for reviews/integrity
approvals without redesigning the assessment protocol.
The return point is **P0.1.2 + P0.6.1/P0.6.2/P0.6.4**, then **P1.8.2**. Do not automatically
start P2.1.2 after this early component completion. Preserve the open CI/Linux findings and all
live gates as `UNMEASURED`.
