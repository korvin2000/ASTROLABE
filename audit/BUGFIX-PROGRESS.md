# Bugfix progress ? 2026-09-26

Branch: `feature/bugfix`. Baseline: `9a80e117445be357285fec2cffffe0fd45290a9a`.

**38 fixed across two sessions (14 in this continuation); 104 open; F-034 previously resolved.**
The complete remaining queue is `findings.md` ? JSON `fix_progress.remaining_open_ids`.

## Fixed findings

| Finding | Commit | Change and evidence |
|---|---|---|
| F-003 | `4e9d774` | Duplicate call IDs and reused completed IDs make protocol pairing invalid; provider regression tests. |
| F-005 | `4e9d774` | Token sums saturate and admission compares capacity without overflow; maximum-Long regressions. |
| F-007 | `43f8a15` | Sequence assignment, replay and subscriber enqueue share the event lock; concurrent-emitter regression. |
| F-008 | `43f8a15` | Flow buffer explicitly drops oldest records; stalled collector receives the final event after overflow. |
| F-009 | `43f8a15` | Java authority cancellation propagates without cancelling the host future; ordinary failures are logged by type. |
| F-011 | `24e53e3` | COMMIT is inside rollback protection; failed rollback quarantines even when close fails (`15bfcaa`). Deferred-FK and injected rollback/close failure regressions pass. |
| F-012 | `24e53e3` | Blob and lock-holder publication drain their ByteBuffers before force/publication. Existing storage tests pass; forced short-write injection was not performed. |
| F-035 | `75e3280` | InvalidPathException becomes an IllegalCharacter refusal; Windows invalid-name regression. |
| F-043 | `f060c97` | Passed receipts reject failures/errors and incompatible exits. QA preserves explicit expected CLI exits and keeps HTTP status separate (`3f433f6`); receipt and QA regressions pass. |
| F-047 | `d7551cc` | Recall with no current file version is Historical and grants no Workset coverage. |
| F-049 | `d7551cc` | All Workset collection access is synchronized; concurrent registrations and snapshots preserve all entries. |
| F-050 | `d7551cc` | A narrowed recall persists its selected blob, preserving source coordinates on subsequent recalls. |
| F-051 | `d7551cc` | Read observations retain the full captured redaction mask; recalling a hidden tail grants no coverage. |
| F-066 | `9dbf774` | Unittest OK summaries account for skipped cases; all-skipped output stays inconclusive. |
| F-067 | `9dbf774` | Cargo aggregates all suite summaries, including later failures. |
| F-068 | `210763d` | Malformed/incomplete Jest and pytest JSON reports, unknown entries and partial pytest XML reports cannot fall back to green terminal output. |
| F-077 | `f060c97` | Verifier-version validation precedes same-stamp receipt reuse; supplied changed environments also invalidate. |
| F-098 | `24e53e3` | Overlapping redaction matches redact the full interval union, including transitive overlaps. |
| F-116 | `43f8a15` | Equivalent root-package spellings pin identical file sets and detect content changes. |
| F-121 | `f060c97` | Order-aware changed spans detect assertion/control-flow reorderings. Existing test-file additions conservatively require review (D-261). |
| F-130 | `210763d` | Cached approvals pass through current required-check veto; persisted ReviewRecord includes failedRequiredChecks and approved reflects the veto. |
| F-131 | `f060c97` | Cache preference is accepted only when remaining unit jobs can meet all deadlines; seeded schedule regressions. |
| F-139 | `75e3280` | Generated tools deep-copy and freeze script/capability/test collections; capability requirements and unambiguous argv contribute to the digest. |
| F-140 | `f060c97` | Watcher results render stale when their candidate differs from the current candidate, including unknown current candidates. |

## Continuation fixes

| Finding | Commit | Change and evidence |
|---|---|---|
| F-053 | `6e4e671` | Canonical edit paths, including rename targets, cannot appear in multiple operations; reverts run alone. Conflict regressions refuse before any write. |
| F-055 | `6e4e671, da8bc42` | The entire edit report is redacted before return and persistence. Redacted or capped reports grant no new post-edit coverage; composite observations carry no source ranges. Diagnostic and coverage regressions pass. |
| F-059 | `b5ae2f3, 2b30ba6` | Run foreground/background/poll/refusal views and Verify output are redacted at their output boundaries. Stored log masks and scan limitations propagate into run capture metadata; secret and bounded-log regressions pass. |
| F-060 | `e4e23eb` | D-class replies must match request ID, request revision and current contract after the await; coroutine cancellation is checked before proceeding. Wrong identity/revision and authority-change regressions prevent dispatch. |
| F-070 | `2b30ba6, da8bc42` | Both acceptance selections refuse missing registrations. Aggregate green requires final-stamp Scheduler.currency certification; an earlier pass invalidated by a later check is non-green. |
| F-071 | `6e1f2ca` | D-262 conservatively denies new model-origin verification commands unless exact argv/cwd also has non-model contract authorization. Working directories resolve inside the execution root; denied-command, escaping-cwd and approved-command regressions pass. |
| F-072 | `fb6bffc` | STATE persistence precedes publishing the new in-memory register or clearing its last rejection; injected save failure leaves both unchanged. |
| F-073 | `fb6bffc` | Schema-invalid STATE patches now record a typed rejection with measured sizes for the loop gate. A subsequently saved valid patch clears it. |
| F-074 | `e4e23eb` | Task answers must name the pending question and match the current contract after the await; wrong and superseded answers cannot amend it. |
| F-078 | `2b30ba6` | Exclusive checks enumerate under the mutation lock and compare before/after membership and content/metadata. Enumerated unknown closures rescan the workspace, not only a caller-supplied stale path list. |
| F-079 | `2b30ba6` | Isolated export reads unchanged bytes from immutable Git objects, checks dirty bytes against stamped digests, and checks candidate currency before/after export. A stale report is refused; unsupported entry modes fall back to exclusive execution. |
| F-080 | `2b30ba6, 6e1f2ca` | Isolated exports restore and validate POSIX executable status; post-run snapshots include executable state. The POSIX runtime regression is present but skipped on this Windows host. |
| F-081 | `137b89b` | Baseline snapshots compare membership, versions, timestamps and executable state. Mutated, restored or expanded input trees cannot publish a pre-existing-failure ledger. |
| F-123 | `cf3e80d` | Every declared quality/full-suite gate must have current eligible passing evidence at one final stamp. Missing runners, mutating suites and quality gates stale after the suite remain uncertified. |

## Continuation verification

- Every fix group passed focused Windows JDK 26 tests, including before-fix failures for the new regressions. The POSIX executable-mode test is skipped on Windows.
- Independent review found two gaps in the first implementation: coverage from hidden edit views and the alternate acceptance selector. Both were corrected and retested (`da8bc42`).
- The first build was cancelled for review corrections. The next full core run had 1,305 passed, two failed and seven skipped; the two stale test fixtures were corrected and both classes passed focused reruns. Final `./gradlew.bat build -q --console=plain` **passed** on `ae8fa2a`: **1314 core tests: 1307 passed, 7 skipped, zero failures/errors**; provider-api 17 passed, eval 49 passed, index-treesitter 17 passed. Compilation, packaging and ABI checks passed. Log: ignored `build/bugfix-continuation-build-verified.log`.
- No public signatures changed in this continuation. D-264 changes the default parser policy to `shaper/2`, invalidating older check-definition digests and requiring re-verification.
- D-262: new model-origin verification commands require exact non-model contract authorization. Interactive approval through Verify is unavailable; a host/user must commit that authorization. D-263: one operation per canonical edit path; multiple hunks stay supported.
- Isolated export now reads unchanged content from Git's object store, one blob read per file; large-repository export performance was not benchmarked.
- Local commits only; no push, PR, Linux, CI, live-provider or P7 validation.

## Previous session verification

- Each fix group passed focused tests. Regression tests reproduced the defects before correction, except the short-write loop was verified by inspection and existing storage tests.
- Initial full Windows JDK 26 core run: **1,293 tests; 1,286 passed, one failed, six skipped**, in 13 minutes. The sole failure was QaDriverTest recording HTTP 200 as a process exit. Fixed in `3f433f6`.
- Final focused DbTest, QaDriverTest, SchedulerTest and EvidenceTest: **29 passed**, zero failures/errors/skips.
- `./gradlew.bat build -x :core:test -q --console=plain`: **passed** after the correction; compilation, packaging and ABI checks passed; provider-api 17, eval 49 and index-treesitter 17 tests passed. The full core suite was **not rerun** after the final fixes.
- Commands used JAVA_HOME=C:/Users/user/.gradle/jdks/eclipse_adoptium-26-amd64-windows.2. Local logs: ignored build/bugfix-build.log and build/bugfix-build-final.log. Two JVM attach pipe diagnostics in the initial log came from progress sampling, not test failures.
- Core ABI dump regenerated. Provider API signatures did not change.
- No Linux, remote CI, live provider, or P7 validation in this session.

## Compatibility and decisions

- `ReviewRecord` gains defaulted serialized `failedRequiredChecks`; `Receipt` and `Executed` gain `expectedExitCode` (default zero, nullable for output-only QA). Old ordinary JSON remains readable; JVM callers must rebuild against the changed constructor/copy ABI. Historical HTTP receipts with exitCode=200 need re-verification under the corrected semantics.
- `Workset.RecallResult.Historical.currentVersion` is nullable when the source file is gone.
- Generated-tool digests now include capabilities and unambiguous argv; existing digest-based caches may invalidate.
- D-261: additions to an existing test file require integrity review. New test files without detected skip markers retain the additions-only category. This is a conservative heuristic, not semantic proof.
- Receipt construction rejects contradictory passed evidence; malformed old persisted receipts may now fail validation. D-264 now bumps the default check parser policy to shaper/2, so old receipt definitions cannot retain currency under the old parser policy.

## Resume order

1. Check branch/status and read `findings.md` fix_progress. Preserve pre-existing CLAUDE.md/ISSUES.md changes and untracked continue_fixing.md/todo_findings.txt.
2. F-076 nested-package checker and blast command paths.
3. F-022/F-088/F-094 remaining authority identity/currency boundaries.
4. F-054 normalized anchors; F-056 revert scope; F-057 partial-write reporting; F-058 result recall links.
5. F-061/F-062/F-064/F-065 run durability, ownership, cancellation and capture drain; then remaining findings in audit order.
6. Keep the 185 implemented P0-P6 task statuses intact; repair status belongs in findings.md. P7 remains excluded.

## Existing implementation debts

The original plan is still 185/185 implemented. Carry D-254 recovery wiring, D-70/D-71 replanning, D-241 S3 resume, D-252 retrieval, D-113/D-120 behaviour maps, D-124/D-244 review evidence, D-220/D-221 eval arms, D-200 QA scheduling, D-66 S0 suite and D-92 impact checks. Their related findings remain open unless explicitly listed above.

## Workspace provenance

`CLAUDE.md`, `ISSUES.md`, `findings.md` and `todo_findings.txt` were already modified/untracked at session start. The first two and todo_findings.txt were preserved. findings.md is now committed as the requested repair ledger. Local commits only; no push or PR.
