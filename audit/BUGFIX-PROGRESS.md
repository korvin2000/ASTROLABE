# Bugfix progress ? 2026-09-27

Branch: `feature/bugfix`. Baseline: `9a80e117445be357285fec2cffffe0fd45290a9a`.

**64 fixed across five sessions (10 in the latest continuation); 78 open; F-034 previously resolved.**
The complete remaining queue is `findings.md` ? JSON `fix_progress.remaining_open_ids`.

## Fifth continuation: ten further repairs

| Finding | Commit | Change and evidence |
|---|---|---|
| F-004 | `55e86ea, eccb010` | Generic request counts include roles, call/result IDs and explicit planning margins, never claim exactness, and flag native-only replay/non-text content as unknown. Cell dispatch honors profile-specific request-estimator overrides. Exact-text, long-ID, native-context and dispatch regressions pass. Generic framing allowances remain planning estimates, not measured provider limits. |
| F-006 | `6b7c801` | Cancel and release settle held invocations independently of response waiters; cancellation of await requests provider cancellation and preserves the single terminal usage record. Direct cancellation, cancelled waiter and release-without-waiter regressions pass. |
| F-010 | `4c99034` | Composed views and all export inputs share one SQLite read transaction. Current register reads use LIMIT 1 plus COUNT; Contracts.current uses repository.latest with a bounded SQL implementation. A commit injected between SELECTs preserves the original snapshot; malformed historical bodies do not affect current reads. |
| F-014 | `6bb0767` | Store.open canonicalizes existing ancestors of the configured base and final layout, and rejects state inside any registered Git worktree before creating directories. Direct, nested, linked-worktree and Windows junction regressions pass. |
| F-018 | `c406b7a` | JVM matching uses Unicode character classes and explicit LF/CRLF semantics matching ripgrep, including dot and end anchors. Backend comparisons cover accented words, Arabic digits, Unicode whitespace/boundaries and Unicode/CR line separators. |
| F-020 | `c406b7a` | Enumeration and binary-probe I/O failures propagate as Failed/Denied. Directory-walk failures are no longer hidden; Git errors inside a detected repository cannot fall back to an unrestricted walk. Corrupt-index regression passes; unreadable-directory regression is present but skipped on Windows. |
| F-021 | `c406b7a` | Search candidates resolve through WorkspacePath before content probes. Link ancestors and outside-root resolutions are denied for both engines. A tracked directory replaced with an external Windows junction is rejected. Concurrent external-writer races remain the documented best-effort path limitation. |
| F-024 | `1e3ef70` | Digest reduction terminates by removing optional content, without the 64-step cutoff. Mandatory overflow throws DigestCapacity; Cell checkpoints a pressure exit before provider dispatch. Tiny caps, long exclusions and 100 historical requests are covered. |
| F-025 | `1e3ef70` | ContractSlice persists incrementAcceptanceIds independently of retained definitions and includes them in coverage. Removing an increment-only acceptance item stays incomplete after copy and JSON round-trip. Legacy slices infer only the IDs still present and should be rebuilt from their authoritative increment. |
| F-026 | `88ab4f5` | Registry and Coherence retain per-listener delivery progress under serialized transition delivery. Failed callbacks resume before later changes; acknowledged callbacks are skipped; recorded versions advance only after all acknowledgements. Failed listeners must tolerate replay of their own partial effects. Registry/horizon fault regressions and run/edit checks pass. |

### Verification and compatibility

- Focused Windows JDK 26 regressions passed for provider accounting/cancellation, store containment, search, composed projections, current contract reads, context slices/digests and coherence. Failures were reproduced before fixes. Some initial regression attempts required fixture or compilation corrections before reaching the intended behavior.
- Review found that Cell bypassed the request-estimator override; its dispatch regression failed before the follow-up correction and passed afterward.
- Full Windows JDK 26 build on `eccb010`: **passed**. Core: 1394 tests, 1386 passed, 8 skipped; provider-api 19, eval 49, index-treesitter 17 passed. Total: **1471 passed, 8 skipped**, zero failures/errors. Compilation, packaging and ABI passed. Log: ignored `build/bugfix-fifth-build.log`.
- Core ABI regenerated. ContractRepository.latest has a compatible default; SQLite and memory repositories override it. ContractSlice gains a defaulted serialized incrementAcceptanceIds field and changed constructor/copy signatures; consumers must rebuild. DigestCapacity is the explicit failure for an irreducible digest.
- Existing slice JSON decodes by inferring IDs still present. Missing historical increment-only obligations cannot be reconstructed from an already incomplete slice; rebuild it from the authoritative contract/increment. No database schema bump.
- Generic framing margins are planning estimates. Native-only replay and non-text content need provider-specific effective accounting; no live provider capacity claim is made. D-269 records the policy.
- D-270 records contract capacity/serialization compatibility. D-271 records retry semantics: acknowledged listeners are not repeated, failed listeners must tolerate their own partial-effect retry, and an unrecovered failure blocks later transition delivery.
- Windows junction regressions ran. The new POSIX unreadable-directory regression is skipped on Windows. No new Linux/CI, live-provider or P7 evidence.
- F-013, F-015/F-016/F-017 and F-019 remain open: bounded GC, process containment/settlement, Git deadlines and search resource limits require further work. Raw snapshot findings F-027 onward also remain open.
- Local commits only. Nothing pushed; existing CLAUDE.md/ISSUES.md and the two untracked task files are preserved.

## Fourth continuation: six further repairs

| Finding | Commit | Change and evidence |
|---|---|---|
| F-001 | `9192a41` | Attempt construction, copy and decoding own immutable nested collections, including provider JSON and default role definitions. Host mutations and exposed-reference mutation attempts cannot change frozen inputs. Controller compares normalized snapshots. |
| F-002 | `9192a41` | Fingerprint encoding `attempt-config/v2` sorts maps and sets while preserving lists and JSON arrays. Reordered equivalent configurations match; changed prices, permissions and sequence order differ. |
| F-023 | `8491837` | Contract changes and final amendment provenance commit atomically. Accepted/rejected records, projections and exports survive reopening; injected SQL failures roll back both contract and resolution. |
| F-061 | `568f6bf` | Handles, foreground/MCP reconciliation and observations persist before intent commit. All open unsafe intents fence replay, including Observed after failed commit updates. Injected handle, observation and commit failures cannot permit a duplicate launch. |
| F-063 | `568f6bf` | Background handles persist classification, pre-run members and base commit. Poll/cancel retain D/unknown labels. Dirty-to-clean and clean HEAD changes invalidate evidence; pre-existing dirt is excluded. Concurrent changes are reported as interval changes with unknown attribution. |
| F-064 | `568f6bf` | Cancellation interrupts waiting and continuously producing polls, terminates owned processes and propagates after intent accounting. Shared capture retains at most 8 MiB while draining to terminal EOF; capped/missing logs remain incomplete and cannot certify green. |

### Verification and compatibility

- Regression-first checks reproduced four amendment failures, seven run/capture failures and three configuration failures before their fixes. Two initial compile attempts used tests that were still being completed; those compile issues were corrected before behavioral RED runs.
- Focused batches passed: 40 initial tests, 100 broader tests, and 48 final configuration/lifecycle/run/capture tests. These batches overlap and are not additive totals.
- Review corrections cover continuously producing polls, interrupted I/O cleanup, clean HEAD transitions, default-role snapshots and normalized Controller config comparisons.
- Full Windows JDK 26 build on `b7b74d0`: **passed**. Core: 1377 tests, 1370 passed, 7 skipped, zero failures/errors; provider-api 17, eval 49, index-treesitter 17 passed. Total: **1453 passed, 7 skipped**. Compilation, packaging and ABI checks passed. Log: ignored `build/bugfix-fourth-build.log`.
- ABI regenerated in `b7b74d0`. `ContractRepository` implementors must provide atomic `commitResolution` and durable `resolved`; `Contracts.resolved(work)` adds work filtering. Handle constructor/copy signatures gain defaulted provenance fields. Consumers must rebuild.
- AttemptConfig retains constructor/copy/components and JSON field shape, but uses a custom serializer instead of the generated implementation. Existing JSON decodes into immutable inputs. Its v2 fingerprint is intentionally recomputed on old snapshots, invalidating compiled-context caches; stored historical SQL fingerprint values are not rewritten.
- No database schema bump. Legacy handle JSON loads conservatively as D/unknown without reconstructed launch membership. Legacy amendment rows that were already left Pending are not repaired automatically: their signed resolution provenance was never recorded.
- D-266 records background interval attribution; D-267 records the 8 MiB capture cap; D-268 records immutable inputs and fingerprint compatibility. Output beyond the cap is unavailable from the captured blob; this is explicit incomplete evidence, not a complete recall log.
- Local commits only. No push, PR, Linux/CI, live provider or P7 validation. Existing user edits remain preserved.

## Third continuation: ten further repairs

| Finding | Commit | Change and evidence |
|---|---|---|
| F-022 | `1316839` | Amendment replies must match the proposal, revision and still-pending amendment after suspension. Serialized mutations use the latest contract, preserving concurrent strengthening/proposals; resolution memory follows successful persistence. Seven regressions pass. |
| F-054 | `c2f6989` | Normalized anchor spans include requested boundary indentation and line endings, including whitespace after the final newline. Tabs, CRLF/LF, inline surroundings and end-to-end replacements are covered. |
| F-056 | `742d834` | Turn-revert scope preflight uses the exact snapshot tree difference, including clean-at-target files, creations and deletions. Current contract scope and increment warnings/justification apply before restoration. |
| F-057 | `742d834` | Publication bookkeeping precedes postimage persistence and coherence. Partial rename targets retain recovery references; failed post-write revalidation and transform observation report partial/unknown effects. Failed symlink restoration cannot claim unchanged bytes. Fault-injection regressions cover persistence, coherence, rename, transform observation and selective revert. |
| F-058 | `742d834` | New edit observations use their edit alias identity. Observation lookup resolves exact IDs first, then the latest result for an action ID, preserving historical poll observations. Run/edit recall, edit revert and repeated-poll regressions pass for SQL and memory stores. |
| F-062 | `ff510f7` | Poll/cancel require matching work and workspace alias provenance before accessing the process or log. Same-work, same-workspace resume across attempts remains supported. Foreign work/workspace and resume regressions pass. |
| F-065 | `ff510f7` | The shared observer drains terminal processes until an empty cursor chunk and treats a nonadvancing nonempty cursor as lost. Run, Verify, Checker, Baseline, QA and inline syntax use it. Initially-terminal, terminal-transition and foreground-run regressions preserve the final failure marker. |
| F-076 | `c49ca61` | Checker and blast arguments are relative to command cwd while evidence paths remain workspace-relative. Nested checkers ignore unrelated package paths; known file-oriented commands receive selected files and project/unknown commands run as declared. Actual nested-file reading and argument regressions pass. |
| F-088 | `e769511, 742d834, ff510f7, c49ca61, b13c18d` | Cell rechecks authority after accounted provider responses and before each tool/check dispatch. Built-in mutation, transform/syntax, process, baseline and ask-answer boundaries recheck after preparation or suspension. Expired provider-response, approval and ask-reply regressions prevent effects. Late completion may still be archived using existing evidence; all new scheduled work is skipped and publication remains fenced. |
| F-094 | `e769511` | After final review, completion revalidates the candidate, full contract and live publication authority. Tree edits, amendments, same-version strengthening, cancellation and lease expiry cannot complete the campaign. |

Windows JDK 26 full build passed on b13c18d: 1353 core tests: 1346 passed, 7 skipped, zero failures/errors; provider-api 17, eval 49, index-treesitter 17 passed. Compilation, packaging and ABI checks passed. No new Linux/remote CI or P7 validation.
Total: **1,429 passed, 7 skipped**, zero failures/errors. Log: ignored `build/bugfix-third-build-verified.log`.

- Focused repro batches failed before correction. Final focused checks passed after stale test assumptions about observation IDs and generic crash reporting were updated; original coverage/reconciliation checks remain.
- The first full run had 1,339 core passes, seven failures and seven skips. Its seven unchanged lifecycle tests exposed an overly broad guard: plain late completion must remain archivable from existing evidence. That source correction skips new checks/effects and retains the publication veto; focused lifecycle checks and the final full build passed.
- Independent review corrections cover trailing anchor indentation, transform/syntax/baseline dispatch fences, selective-revert revalidation and failed symlink restoration.
- No public signatures or schema changed. Exact observation IDs retain old views; action aliases select the latest captured poll. Old edit aliases without a stored observation association are not migrated.
- D-265: handle ownership is work plus workspace; later attempts may resume earlier handles, while absent/foreign workspace provenance is denied.
- F-061 durability, F-063 background effect attribution, F-064 cancellation/unbounded capture and F-023 durable amendment resolutions remain open.
- Local commits only. No push, PR, new Linux/CI, live-provider or P7 validation.

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

1. Read findings.md fix_progress; preserve existing CLAUDE.md/ISSUES.md and untracked continuation files.
2. Continue F-013, F-015/F-016/F-017, F-019, then F-027 onward. Exact queue: fix_progress.remaining_open_ids.
3. Keep P0-P6 at 185/185 implemented; repair status belongs in findings.md. P7 remains excluded.

## Existing implementation debts

The original plan is still 185/185 implemented. Carry D-254 recovery wiring, D-70/D-71 replanning, D-241 S3 resume, D-252 retrieval, D-113/D-120 behaviour maps, D-124/D-244 review evidence, D-220/D-221 eval arms, D-200 QA scheduling, D-66 S0 suite and D-92 impact checks. Their related findings remain open unless explicitly listed above.

## Workspace provenance

`CLAUDE.md`, `ISSUES.md`, `findings.md` and `todo_findings.txt` were already modified/untracked at session start. The first two and todo_findings.txt were preserved. findings.md is now committed as the requested repair ledger. Local commits only; no push or PR.
