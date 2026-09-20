# P5.1.5 — symbolic write-scope intersection and difference (OOO-05)

Date: 2026-09-20. Baseline: `89d10c9` (P6.1.4 complete), clean tree after restoring generated ABI.
Authority: continuing owner request, proposal §2 order. No existing partial scope-algebra kernel.
Decision D-58. TODO §1.2 governs execution; parents P5.1.1/P5.1.4 remain TODO.

## Admission and boundary

The open-world use case exists: Scope.writePaths uses patterns, Edit.preflight implements create and
rename to absent targets (tool/edit/Edit.kt), and WorkspacePath canonicalises missing suffixes.
Neither contract nor S3 specification fixes a finite inventory or prohibits new paths. Current
RequirementGraph ownership checks inspect concrete paths and do not prove future-path disjointness.
This kernel therefore analyzes languages; it does not enable S3, install ownership or authorize writes.

| Consumed type | Existing file under core/src/main/kotlin/io/astrolabe | Producer | Status |
|---|---|---|---|
| Scope | contract/Contract.kt | P1.1.1 | DONE |
| PathPattern | workspace/PathPattern.kt | P1.7.8 | DONE |
| WorkspaceId | id/Ids.kt | P0.2.1 | DONE |

WorkspacePath (P1.2.6) supplies the lexical/physical boundary being documented, not a new import.
Parents retain worktrees, ownership installation, leases, physical path/case/symlink validation,
integration and S3 gates. Inputs are explicitly projected into ONE destination WorkspaceId;
different source workspaces never prove disjointness. Runtime still uses ScopeGuard/WorkspacePath.

## Frozen model before code — scope-algebra-v1

- L(scope) = union(write patterns) minus union(protected patterns). Intersection searches A∩B;
  difference searches A\B (a counterexample to child containment). Empty search returns Disjoint;
  inhabited search returns Overlap(witnessPath) with the relation retained; limits return Unknown.
- Match PathPattern exactly: backslashes become slashes; remove one leading `./`; empty or `/`
  pattern is empty. Bare names match the final filename at any depth; slashed literals and `dir/`
  match that exact path or descendants. Glob trailing slashes are trimmed. `*`/`?` stay in a segment;
  `**` uses Java regex dot semantics; `**/` is optional dot-star then slash. Bare `**` is special:
  it matches every path, including line terminators. Other regex metacharacters are literal.
- Alphabet: Unicode scalar values, excluding NUL and backslash. Supplementary characters count as
  one character; unpaired-surrogate patterns return Unknown (no unsound negative proof). Exact case,
  no Unicode normalization. Java dot excludes LF, CR, NEL, U+2028, U+2029; segment wildcards do not.
- CanonicalPaths is the platform-independent lexical universe: nonempty slash-separated segments,
  no empty/`.`/`..` segments, no leading/trailing slash, no initial ASCII-letter-colon drive prefix,
  not an all-whitespace path. It is a superset of physically representable paths on each platform.
  A lexical witness need not exist or be creatable on the current OS; physical checks stay mandatory.
  This is not a proof against case aliases, filesystem races or symlinks.
- Compile all four pattern unions into one tagged epsilon NFA. Lazy subset construction tracks all
  four languages together; combine it with the canonical-path DFA. Accept flags implement Boolean
  intersection/difference including exclusions. Iterative BFS proves emptiness only after exhausting
  the finite reachable product. Parent links reconstruct a shortest code-point-length witness.
- Alphabet equivalence classes split every literal and the wildcard/canonical predicates. Representatives
  suffice because all transitions are constant inside each class. Tie order: a..z, 0..9, `_-. /`
  without the space (i.e. `_ - . /` as four symbols), then remaining scalar values ascending.
  Unions are copied, sorted and deduplicated; output owns immutable Scope snapshots.
- Caller supplies maximum NFA states, product states and explored transitions. Every limit gives Unknown,
  never Disjoint. Invalid limits throw. A constructed witness is rechecked with existing Scope/PathPattern;
  incompatibility or regex resource failure returns Unknown. No arbitrary path-length cutoff or regex heuristic.
- Worst-case determinization is exponential in total pattern length. With S reachable product states,
  C character classes and N NFA states, traversal is O(S*C*N), storage O(S*N + N + C); sparse edges
  and bit sets reduce constants. Unicode category ranges are computed once over the fixed scalar space.
  Preprocessing additionally scans supplied patterns; limits bound automata and traversal, not caller allocation.

Method source read: [Cornell CS212, product construction, Problem 7](https://www.cs.cornell.edu/courses/cs212/1998sp/psets/ps4.html).
The Boolean language predicates, Unicode partition, lexical DFA and limits are local design decisions.

## Verification plan

1. Register explicit Deps, types, D-58 and ACTIVE/handoff before code (done).
2. RED hand cases: src/** × **/billing.kt; protections; child outside parent; empty languages.
3. Implement tagged NFA, canonical DFA and bounded BFS; verify shortest witnesses with PathPattern.
4. Independent recursive matcher on a small alphabet; exhaustive bounded paths and generated pattern
   pairs check classification and minimum witness length, not a finite-enumeration proof of emptiness.
   Include literal glob metacharacters, repeated stars, directory/bare-name semantics, Unicode/newlines,
   malformed Unicode, deep patterns, union/exclusion permutations and every resource limit.
5. Review, focused tests, separate core ABI update, full build, docs/counts/links and checkpoint commit.

## Checkpoint

- **COMPLETE: P5.1.5 DONE.** NFA/canonical-path product and iterative BFS implemented; ten focused
  tests/oracles pass, independent review clear, separate core ABI and full build pass.
- Final full Windows/JDK 26 offline build executed core **813 tests, 0 failures/errors, 6 existing
  platform skips**, and eval **18 tests, 0 failures/errors/skips**. Provider-api reused its 15 green results.
- Counts: **64/180 DONE, 4 IN_PROGRESS, 112 TODO**. Return P0 validation -> P1.8.2; no active override.
- Next proposal after completion: OOO-07 (candidate P6.1.5). CI/Linux/live gates unchanged.

## Verification log

- RED: focused Kotlin test compilation failed on absent ScopeAlgebra/ScopeSearchLimits.
- GREEN: four hand-case tests passed on Windows/JDK 26; exact `src/billing.kt` witness, protections,
  child containment/difference and canonical-path exclusions.
- Expanded run: 10 tests, 1 failure in a handwritten expected witness, not the implementation:
  `a/*` protects `a/a` but not `a/a/a`. Existing matcher confirms this; corrected expected shortest
  witness to `a/a/a` and retained explicit assertions for both protected/nonprotected paths.
  Both independent-oracle tests passed (exhaustive short strings; 250 seeded scope pairs × two relations).
- GREEN expanded run: **10 tests, zero failures/errors/skips**. The prior full core report (803 tests,
  six platform skips) was replaced by this filtered run; it remains historical in the P6.1.4 journal.
- Oracle sizes: 163 pattern entries × 154 canonical short strings for independent matcher parity;
  250 scope pairs (seed 515) × intersection/difference, each checked against 2,097 canonical paths
  up to five symbols (enumeration stops at the first witness). Every emitted witness is independently
  checked, including when its characters are outside the finite enumerated alphabet. Bounded enumeration
  is not presented as an emptiness proof; exhaustive automaton traversal supplies negative results.
- Independent read-only review (same code-review-and-quality workflow, separate model) found no required
  findings across exact grammar, Unicode classes, canonical-path DFA, exclusions/difference, shortest
  BFS witnesses, immutable states and limit honesty. It did not rerun Gradle.
- Separate `:core:updateKotlinAbi --offline --console=plain --no-configuration-cache` passed. Dump
  review shows only the six intended public workspace types and their result subclasses; prior APIs
  unchanged. Full build succeeded in 3m 15s; core and eval test tasks executed, provider-api UP-TO-DATE.
- No existing tests/acceptance weakened, new dependencies or production flags. Full builds do not
  validate Linux/remote CI or live quality. [Public API guide](../core/README.md). Checkpoint commit
  includes code/tests/core ABI and all state/index files; push remains with the owner.
- Final bookkeeping: 180 unique task headings, 64 DONE / 4 IN_PROGRESS / 112 TODO. All local links
  and anchors in the changed state/protocol/API documents resolve. Remaining proposal order verified:
  OOO-07 -> OOO-08 -> OOO-06 -> OOO-09 -> OOO-04. No next kernel activated. `git diff --check` passes.
