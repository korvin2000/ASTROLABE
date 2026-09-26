# Bugfix progress ? 2026-09-26

Branch: `feature/bugfix`. Baseline: `9a80e117445be357285fec2cffffe0fd45290a9a`.

**24 fixed this session; 118 open; F-034 resolved before this session.**
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

## Verification

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
- Receipt construction rejects contradictory passed evidence; malformed old persisted receipts may now fail validation. Before releasing these parser changes, bump the harness/verifier version so old receipts cannot retain currency under the old parser policy.

## Resume order

1. Check branch/status and read `findings.md` fix_progress; preserve pre-existing edits in CLAUDE.md and ISSUES.md and untracked todo_findings.txt.
2. F-053 same-path edit clobber and F-055/F-059 diagnostic/run secret disclosure; add focused regressions before fixes.
3. F-071 verification command authorization, then F-070/F-076?F-081/F-123 receipt certification and final-tree correctness.
4. Authority identity/currency F-022/F-060/F-074/F-088/F-094, then recovery and remaining findings in audit order.
5. Keep the 185 implemented P0?P6 task statuses intact; audit repair status belongs in findings.md. P7 remains excluded.

## Existing implementation debts

The original plan is still 185/185 implemented. Carry D-254 recovery wiring, D-70/D-71 replanning, D-241 S3 resume, D-252 retrieval, D-113/D-120 behaviour maps, D-124/D-244 review evidence, D-220/D-221 eval arms, D-200 QA scheduling, D-66 S0 suite and D-92 impact checks. Their related findings remain open unless explicitly listed above.

## Workspace provenance

`CLAUDE.md`, `ISSUES.md`, `findings.md` and `todo_findings.txt` were already modified/untracked at session start. The first two and todo_findings.txt were preserved. findings.md is now committed as the requested repair ledger. Local commits only; no push or PR.
