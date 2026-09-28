# Bugfix review — `feature/bugfix` → `main`

Analytical review of the 142 fixes (plus F-034, already resolved) recorded in `findings.md`, integrated into `main` only
after each is accepted, corrected or reverted. This file is the protocol **and** the resumable ledger; it is the only
progress authority for this review. Commit it after every round.

## /goal prompt

```
/goal Complete the bugfix review defined in C:/work.ai/ASTROLABE/.claude/worktrees/bugfix-review/audit/BUGFIX-REVIEW.md.
Work only in that worktree (branch review/bugfix) until its Finalize merge step. On every start and after compaction:
read that file's "Fixed facts", "Protocol" and "Checkpoint" sections and the next PENDING/IN_REVIEW ledger rows, run
git status and git log --oneline -5 in the worktree, then continue the round loop. This review replaces the TODO
workflow and the CONTINUE-TASK "Next" list. Do not run the full test suite or gradlew build; run only targeted tests
for code you change. Done when: every ledger row and every finding has a final verdict (ACCEPT, NOTE, FIXED, REJECTED
or SKIP), every Finalize box is ticked, and review/bugfix is merged --no-ff into local main (not pushed).
```

## Fixed facts

- Worktree: `C:/work.ai/ASTROLABE/.claude/worktrees/bugfix-review`, branch `review/bugfix`, created from
  `feature/bugfix` tip `dba6344`. Base: `main` = `9a80e11` (merge-base). Reviewed code = `main...feature/bugfix`.
- Finding → commit map (from each ``- Fix (date, `sha`)`` line in `findings.md`, else `audit/BUGFIX-PROGRESS.md`)
  is already resolved into the ledger below: all 142 fixed findings map to 97 commits. Do not rebuild it.
- Read one finding: `awk '/^### F-061 /{p=1;next} /^### F-/{p=0} p' findings.md` (use Problem, Trigger, Fix lines).
- Read one fix: `git show --stat <sha>`, then `git show <sha> -- . ':!*.md' ':!*/api/*.api'`.
- Later changes to the same file: `git log --oneline <sha>..dba6344 -- <path>`. Judge a fix at the tip, not only its diff.
- Gradle (Git Bash, from the worktree root): `export JAVA_HOME=/c/Users/user/.gradle/jdks/eclipse_adoptium-26-amd64-windows.2`,
  then `./gradlew :core:test --tests 'io.astrolabe.<pkg>.<Class>Test' -q --console=plain`.
- Known flaky (re-run once): `StamperTest` `git exited -1`, `ProcOwnershipTest` READY timeout, RunTest FX-22 `bg-end`.

## Protocol

**Roles.** The session is the orchestrator. It keeps only this file, reviewer verdicts and fix diffs in context and
never reads whole batches itself. Reviewers are read-only subagents. Fixes are applied sequentially by the orchestrator
(diff fits one sentence) or by one `t3` worker (larger change, known cause) with the confirmed defect as its brief.

**Round loop:**
1. Take the next ≤3 batches with PENDING rows (by batch id). Mark their rows `IN_REVIEW`.
2. Spawn one reviewer per batch in parallel (foreground): `subagent_type: t5` if the batch touches `io/astrolabe/os/`,
   FFM/native code, process lifecycle, SQLite transactions or crash recovery; otherwise `t4`. Use the brief below
   verbatim with the batch rows filled in.
3. Verify every DEFECT/REJECT yourself by reading the cited lines (bounded); never take it on trust. Downgrade it to
   NOTE unless it is a real defect: wrong result, crash, leak, lost data, security or contract break.
4. Fix each confirmed defect in its own commit `review(F-nnn): <what>`; add or adjust one regression test when the
   defect is testable; run only the affected test classes. Verdict `FIXED (<sha>)`.
5. Reject only when a fix is wrong at its root and cannot be corrected locally: `git revert --no-edit <sha>`. If the
   revert conflicts because later commits build on it, FIX instead. Set that finding's `Status: open` in `findings.md`
   with `- Review (date): rejected because …`, run the affected test classes. Verdict `REJECTED (<revert sha>)`.
6. Write verdicts into the ledger (Verdict column; per-finding one-liners in Notes), update Checkpoint, commit
   `review: batches Bxx-Byy`. Next round.

**No polishing.** Style, naming, optional tests and hypothetical risks are NOTE, never code changes. A sound fix whose
test misses the trigger is NOTE unless the gap hides a likely defect.

**Context.** Everything durable lives in this file and git. Past ~75% context, finish the current round, commit, and
continue after compaction from the Checkpoint.

### Reviewer brief (fill `<rows>`)

```
You review bugfix commits in the git worktree C:/work.ai/ASTROLABE/.claude/worktrees/bugfix-review
(Kotlin/JVM SDK; Windows and Linux are equal targets). READ-ONLY: do not edit files, run Gradle or run tests.
Rows (commit, findings, subject):
<rows>
For each commit: read each listed finding with
  awk '/^### F-nnn /{p=1;next} /^### F-/{p=0} p' findings.md      (Problem, Trigger, Fix lines)
then `git show --stat <sha>` and `git show <sha> -- . ':!*.md' ':!*/api/*.api'`. Read only the enclosing functions
and types you need. Check `git log --oneline <sha>..dba6344 -- <path>` and judge the code at the tip.
A commit without findings is reviewed as an ordinary change (test edits must not weaken assertions).
Check in order:
 1. Root cause: the change removes the finding's trigger on every path (errors, cancellation, restart, both OSes).
 2. Regressions: broken callers/contracts; invariants (one writer per table; durable ordering of consequential
    actions; SQLite transaction scope and rollback; all filesystem access through WorkspacePath; FileVersion hashes raw
    bytes; redacted bytes never grant coverage; policies deterministic with injected Clock/IdGen; io.astrolabe.java has
    no suspend/Flow/value class; explicitApi; no Kotlin Result); resource leaks; races; unbounded work; JSON/schema
    compatibility (new fields have defaults).
 3. Tests: the new regression exercises the trigger; no deleted or loosened assertion (grep '^-.*assert' in the diff).
 4. Overreach: new behavior beyond the finding that adds risk.
Reply ONLY in this format, no preamble, at most 10 lines per finding:
UNIT <sha>
F-nnn: ACCEPT
F-nnn: NOTE <one line: residual risk or gap; no action needed>
F-nnn: DEFECT <path:line> <trigger -> wrong outcome> | FIX: <minimal change> | TEST: <class, scenario>
F-nnn: REJECT <why the approach is wrong> | REVERT-SAFE: yes/no (<dependent commits>)
Use F-none for commits without findings. Report DEFECT only when you can name a concrete trigger.
```

## Finalize (tick when done)

- [ ] F-034 (`resolved_on_recheck`, no fix commit): confirm its recheck note still holds at the tip; verdict in Checkpoint.
- [ ] Docs: review `git diff main...review/bugfix -- '*.md' ':!findings.md' ':!audit/*'` (CLAUDE.md, ISSUES.md,
      TODO.md D-rows, CHANGELOG, eval/README). Keep accurate D-rows; revert unintended `CLAUDE.md`/`ISSUES.md` edits.
- [ ] If a FIXED/REJECTED changed public API: `./gradlew :core:updateKotlinAbi` (plus `:provider-api:`/`:eval:` if
      touched) and commit the dumps. Regenerate, never hand-edit.
- [ ] Compile once, no tests: `./gradlew assemble testClasses checkKotlinAbi -q --console=plain`.
- [ ] `findings.md`: each FIXED adds ``- Review fix (date, `sha`): …``; each REJECTED is `Status: open`.
- [ ] Rewrite `CONTINUE-TASK.md` (≤40 lines: review outcome, reopened findings, debts), refresh `actual_state.md`,
      append ≤10 lines to `audit/SESSION-HISTORY.md`. Commit.
- [ ] Merge in the main checkout `C:/work.ai/ASTROLABE`: its untracked `findings.md` must be byte-identical to
      `feature/bugfix:findings.md` (`git hash-object findings.md` = `git rev-parse feature/bugfix:findings.md`); if so,
      delete it, then `git merge --no-ff review/bugfix -m "Merge reviewed bugfixes (findings.md F-001..F-143)"`.
      ABI dump conflicts: regenerate. Do **not** push: the owner approves pushing `main` (CI then runs the full check
      on both OSes).
- [ ] Leave the worktree in place until the owner confirms the merge.

## Checkpoint

- Status: all batches reviewed. Open: rows 110 (4f6eb64: F-095 funding wedge + ResultPacketTest regression) and
  111 (1120f89: ControllerTest "interrupted finalization" regression) — a worker is fixing them; then Finalize.
  FIXED: 26 so far. REJECTED: 0. F-034 recheck: still resolved at the tip (Controller passes `shadowRef =
  tree.shadow`; Edit accepts `turn:N`). Docs: owner's dba6344 CLAUDE.md/ISSUES.md edits kept (intentional);
  D-rows aligned in 66a192d.
- Round 1 review fixes: 261871f F-066, 1db9a3e F-043, 49f4b8c F-009, 2fcd391 F-053, f15acab F-088, 40950b0 F-059,
  72d3b3c F-078, f6b3654 F-050. Round 2: b465b26 F-014, 8afc54f F-026, 69cf936 F-064. Round 3: 43a1d6e F-029,
  c0b6ddf F-028, ebf1a92+7aa5736 F-027, 8baf840 F-083, 3d224ae F-096, c19aef6 F-097, 4054157 F-069, c924b42 F-036,
  edf8040 F-125, 82c0615 F-118, f5c39cf F-090. Round 4: 829997b F-019, 53ab668 F-075, 5f043fc F-093. B13: d8e5918 F-016.
- Public API changed: `Observed.truncated` (69cf936), `RunCapture.executionRoot` (4054157) → Finalize ABI regen.
- Pre-existing failures at dba6344 (bisected): ResultPacketTest "a failed cell still hands back a packet…"
  (broken by 4f6eb64) and ControllerTest "interrupted finalization rechecks acceptance…" (broken by 1120f89).
- 100 code-bearing commits in 14 batches (≤1000 code lines each; `e5321f3`/F-016 alone); 18 docs/ABI-only commits SKIP.

## Ledger

Verdicts: `PENDING` → `IN_REVIEW` → per finding `ACCEPT` / `NOTE` / `FIXED (sha)` / `REJECTED (sha)`; `SKIP` for
docs/ABI-only commits. A finding spanning several commits gets its final verdict on its last row (earlier rows: `partial`).
Code± = changed lines excluding `*.md` and `*/api/*.api`; Main± = lines under `src/main`.

| # | Batch | Commit | Code± | Main± | Findings | Subject | Verdict | Notes |
|---|---|---|---|---|---|---|---|---|
| 001 | B01 | `9dbf774` | 107 | 64 | F-066 F-067 | fix: reject incomplete test reports and count all suite outcomes | F-066 FIXED (261871f); F-067 ACCEPT | F-066: unittest `expected failures=` counted as failures; Jest `todo` rejected the report (Jest counts todo apart from pending). `success:false` with no failed assertion stays Inconclusive (conservative). |
| 002 | B01 | `24e53e3` | 59 | 35 | F-011 F-012 F-098 | fix: roll back failed commits and redact overlapping secret spans | F-011 partial; F-012 NOTE; F-098 ACCEPT | F-012: write loops drain the buffer; the injected short-write regression was not added. |
| 003 | B01 | `4e9d774` | 36 | 16 | F-003 F-005 | fix: reject duplicate tool ids and saturate token arithmetic | F-003 NOTE; F-005 ACCEPT | F-003: duplicate ids set `broken`, but the problem text does not name the duplicated ids. |
| 004 | B01 | `d7551cc` | 75 | 30 | F-047 F-049 F-050 F-051 | fix: preserve recall coordinates and synchronize coverage updates | F-047 NOTE; F-049 ACCEPT; F-050 FIXED (f6b3654); F-051 ACCEPT | F-047: historical recall persists ranges at the recorded version; `Observation.coverage` has no main caller. F-050: a one-hit find recall treated summary+hit body as source and covered line+1; find aliases now kind `search`, recalled by view line. |
| 005 | B01 | `f060c97` | 94 | 42 | F-043 F-077 F-121 F-131 F-140 | fix: enforce receipt currency and conservative test integrity review | F-043 partial; F-077 NOTE; F-121 NOTE; F-131 ACCEPT; F-140 ACCEPT | F-043: see row 010. F-077: exact-stamp branch passes `envId=null`, so an env change on the same stamp stays Current. F-121: test-file edits touching required checks block until reviewed (D-261, intended). Pre-fix stored Passed QA HTTP receipts (exit 200) no longer decode (pre-release store). |
| 006 | B01 | `75e3280` | 54 | 25 | F-031 F-035 F-139 | fix: freeze generated tool definitions and type invalid path refusals | F-031 partial; F-035 partial; F-139 partial | No F-031 code here (all in 2e12762, row 082). F-035/F-139 sound so far; final verdicts on row 082. |
| 007 | B01 | `43f8a15` | 86 | 14 | F-007 F-008 F-009 F-116 | fix: preserve event order cancellation and root closure membership | F-007 ACCEPT; F-008 NOTE; F-009 FIXED (49f4b8c); F-116 ACCEPT | F-008: DROP_OLDEST buffer drops are not counted in `Subscription.dropped` (seq gap shows them). F-009: a host cancelling its own future was rethrown as coroutine cancellation; now only the caller's own cancellation propagates, host cancel = no answer. |
| 008 | B01 | `210763d` | 19 | 11 | F-068 F-130 | fix: apply required check veto to cached reviews and partial XML reports | F-068 ACCEPT; F-130 ACCEPT |  |
| 009 | B01 | `15bfcaa` | 31 | 4 | F-011 | fix: quarantine database connections when rollback and close fail | F-011 NOTE | SQLite auto-rollback (FULL/IOERR, ON CONFLICT ROLLBACK) makes the explicit ROLLBACK fail and quarantines a clean connection; fails closed, the store must be reopened. |
| 010 | B01 | `3f433f6` | 27 | 15 | F-043 | fix: distinguish expected QA exits from HTTP response status | F-043 FIXED (1db9a3e) | `deriveStatus` passed a wrapped runner at nonzero exit (`jest && echo ok`), which the new Receipt init rejects, so recordRun threw; a nonzero exit is now never a pass. |
| 011 | - | `491c344` | 0 | 0 | - | docs: record 24 audit fixes verification and remaining repair queue | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 012 | B01 | `6e4e671` | 60 | 27 | F-053 F-055 | fix: reject overlapping edit operations and redact diagnostics | F-053 FIXED (2fcd391); F-055 partial | F-053: two creates differing only in case passed the overlap check on a case-insensitive FS; identity now folds case there. F-055: final on row 020. |
| 013 | B01 | `b5ae2f3` | 74 | 44 | F-059 | fix: redact run views and report bounded log captures | F-059 partial | Head defect fixed on row 014. |
| 014 | B01 | `2b30ba6` | 154 | 83 | F-059 F-070 F-078 F-079 F-080 | fix: bind verification results to complete stable candidates | F-059 FIXED (40950b0); F-070 partial; F-078 FIXED (72d3b3c); F-079 ACCEPT; F-080 partial | F-059: run head cut argv to 80 columns before redacting, leaking a straddling secret prefix. F-078: mutated-input check was O(n²) list membership under `workspace.mutation`; now hash sets. Unknown closures hash the whole repo twice and store every path (NOTE). |
| 015 | B01 | `137b89b` | 42 | 24 | F-081 | fix: withhold baseline failure ledgers when inputs change | F-081 NOTE | Any new non-scratch file the suite writes (junit.xml, htmlcov/) withholds the ledger: conservative, triage lost. |
| 016 | B01 | `e4e23eb` | 49 | 12 | F-060 F-074 | fix: bind authority replies to pending requests and current contracts | F-060 ACCEPT; F-074 ACCEPT |  |
| 017 | B02 | `cf3e80d` | 57 | 30 | F-123 | fix: require every final campaign gate to certify the candidate | F-123 NOTE | A suite writing non-scratch output moves the stamp, so such a campaign never finishes green (intended). CadenceTest reaches private `fullSuite` by reflection. |
| 018 | B02 | `fb6bffc` | 35 | 12 | F-072 F-073 | fix: publish STATE changes only after persistence and track schema refusals | F-072 ACCEPT; F-073 ACCEPT |  |
| 019 | B02 | `6e1f2ca` | 70 | 27 | F-071 F-080 | fix: restrict model verification commands and invalidate legacy receipts | F-071 NOTE; F-080 NOTE | F-071: `Baseline.run` still resolves `command.cwd` without containment (suite commands only, not model items). F-080: exec-bit regression is POSIX-only (Linux CI). |
| 020 | B02 | `da8bc42` | 33 | 8 | F-055 F-070 | fix: withhold unseen edit coverage and validate all acceptance selections | F-055 NOTE; F-070 ACCEPT | F-055: any redaction in the report withholds coverage for every post-edit view (conservative, D-49). |
| 021 | B02 | `ae8fa2a` | 17 | 0 | (no finding: review as change) | test: align precompile fixtures and policy changes with receipt rules | F-none ACCEPT | Fixture paths moved to scratch `build/`; assertions unchanged. |
| 022 | - | `6fdea0c` | 0 | 0 | - | docs: record 14 additional fixes and passing full validation | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 023 | B02 | `1316839` | 221 | 41 | F-022 | fix: bind amendment replies to current pending authority | F-022 NOTE | A stale/mismatched reply silently returns `latest` with no event or typed stale classification. |
| 024 | B02 | `c49ca61` | 77 | 44 | F-076 F-088 | fix: rebase selected check paths to their command directory | F-076 NOTE; F-088 partial | F-076: unrecognised Touched-selector tools and tsc now run project-wide (slower, may hit the 20s box). F-088: an authority lapse mid-`Checker.run` drops receipts of checks already run (effects stay fenced). |
| 025 | B02 | `c2f6989` | 56 | 27 | F-054 | fix: preserve raw boundary whitespace in normalized anchors | F-054 NOTE | Replacement indentation/line endings replace the file's (tab→spaces, CRLF→LF), per the anchor contract. |
| 026 | B03 | `742d834` | 445 | 97 | F-056 F-057 F-058 F-088 | fix: scope reverts and retain honest edit effects and recall links | F-056 ACCEPT; F-057 NOTE; F-058 NOTE; F-088 partial | F-057: a lease lapse mid-batch throws outside the op try, reported "effects unknown" (honest, less precise). F-058: `id = ? OR action_id = ?` has no index on action_id (full scan per get). |
| 027 | B03 | `ff510f7` | 295 | 112 | F-062 F-065 F-088 | fix: enforce handle ownership and drain terminal process logs | F-062 ACCEPT; F-065 ACCEPT; F-088 partial | F-065: drain-to-EOF is sound. The 8 MiB cap marking a finished foreground run `lost` (unknown_outcome + open intent) comes from 568f6bf: handled under F-064, row 033. |
| 028 | B03 | `e769511` | 147 | 30 | F-088 F-094 | fix: revalidate live authority before effects and final completion | F-088 partial; F-094 ACCEPT |  |
| 029 | B03 | `b13c18d` | 16 | 16 | F-088 | fix: preserve late archival completion without dispatching new effects | F-088 FIXED (f15acab) | Run/mcp fence threw inside `Consequential.run` after the intent was Dispatched, leaving an open intent that refused the argv in later attempts; fence now runs before the intent. |
| 030 | - | `ce0051a` | 0 | 0 | - | docs: record ten further repairs and passing full validation | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 031 | B04 | `8491837` | 181 | 63 | F-023 | fix: persist amendment resolutions atomically with contract changes | F-023 NOTE | No backfill of rows pre-fix resolves left Pending; unscoped `resolved()` orders by proposal time (in-memory: resolution order); `commitResolution`/`resolved` are new abstract members of public `ContractRepository`; `check(updated == 1)` throws on a cross-work amendment id collision. |
| 032 | B04 | `9192a41` | 297 | 116 | F-001 F-002 | fix: freeze nested configuration and canonicalize attempt fingerprints | F-001 NOTE; F-002 NOTE | F-001: snapshot sorts profiles by key, so receipt currency comes from the alphabetically first profile (differs only with mixed currencies). F-002: v1 fingerprints no longer match on load; Precompile keys miss once after upgrade. |
| 033 | B05 | `568f6bf` | 661 | 238 | F-061 F-063 F-064 | fix: retain durable run evidence and bound cancellable process capture | F-061 NOTE; F-063 NOTE; F-064 FIXED (69cf936) | F-061: if render/observation fails after `handles.save`, `Run.unknown` hides the handle id and leaves the log blob unlinked (fence holds). F-063: a base-commit change lists every member as changed (conservative, noisy). F-064: capped capture was `lost` → a normal >8 MiB foreground run became unknown_outcome with an open intent (Verify/Checker/Baseline/QA/Syntax likewise); `Observed.truncated` now separate, capture marked incomplete, partial log never passes. Also NOTE: `Edit` wrapped in `runInterruptible` drops the partial EditResult on cancel; cap keeps the log head. |
| 034 | - | `b7b74d0` | 0 | 0 | - | chore: refresh ABI for durable resolutions and run provenance | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 035 | - | `cb715da` | 0 | 0 | - | docs: record six repairs and passing full Windows build | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 036 | B06 | `6b7c801` | 82 | 0 | F-006 | fix: reconcile fake invocation cancellation without a response waiter | F-006 ACCEPT | Intermediate cancel states are no longer observable in the fake (no fixture coverage for them). |
| 037 | B06 | `55e86ea` | 76 | 38 | F-004 | fix: account for protocol framing and unknown native request context | F-004 partial | Final on row 043. |
| 038 | B06 | `6bb0767` | 61 | 18 | F-014 | fix: reject durable state inside any registered workspace | F-014 FIXED (b465b26) | `git worktree list -z` needs git 2.36 (D-04 minimum 2.20; Ubuntu 22.04 ships 2.34), so Store.open failed there; newline porcelain form now. NOTE: a home dir inside a git worktree now needs `Config.stateRoot`. |
| 039 | B06 | `c406b7a` | 159 | 79 | F-018 F-020 F-021 | fix: align Unicode search and refuse unsafe candidate enumeration | F-018 ACCEPT; F-020 NOTE; F-021 NOTE | F-020: a file deleted mid-walk in a non-git root fails the search instead of being skipped. F-021: link ancestors above a subdirectory search root are not checked; rg re-traversal TOCTOU documented. |
| 040 | B06 | `4c99034` | 168 | 102 | F-010 | fix: read composed projections from one snapshot and bound latest reads | F-010 NOTE | Export holds the Db monitor and a read tx while JSON-encoding every view (writers wait). |
| 041 | B06 | `1e3ef70` | 52 | 22 | F-024 F-025 | fix: preserve slice obligations and enforce contract digest capacity | F-024 NOTE; F-025 ACCEPT | F-024: contracts over ~35-40 requirements exceed the default 150-token digest cap and get Partial(Pressure) every cell until `digestCapTokens` is raised; Anchor.kt:156 overflow path still line-caps the digest. |
| 042 | B06 | `88ab4f5` | 100 | 57 | F-026 | fix: resume failed coherence delivery before advancing recorded versions | F-026 FIXED (8afc54f) | A resumed delivery replayed into listeners/horizons that had unsubscribed (closed cells), and an unsubscribed always-failing listener blocked every later change(); resumed deliveries now skip unsubscribed listeners. |
| 043 | B06 | `eccb010` | 22 | 2 | F-004 | fix: honor profile request estimators at cell admission and refresh ABI | F-004 NOTE | Per-item estimates return 0 for native/opaque items (resident accounting undercounts once P7 emits them; admission still rejects via UnknownHistorySize). New margins can reject long transcripts admitted before. |
| 044 | - | `a7ccc15` | 0 | 0 | - | docs: record ten repairs and passing full Windows validation | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 045 | B06 | `0126918` | 212 | 173 | F-013 | fix: bound blob cleanup scans and prioritize referenced orphan repair | F-013 NOTE | DirectoryStream handles stay open between passes until Store.close; cursors restart on reopen. |
| 046 | B07 | `389634d` | 275 | 139 | F-015 | fix: confirm process container cleanup before publishing terminal outcomes | F-015 partial | Final on row 050. |
| 047 | B07 | `527c95b` | 476 | 219 | F-028 F-029 F-030 | fix: preserve snapshot link objects and reject incomplete or mixed captures | F-028 FIXED (c0b6ddf); F-029 FIXED (43a1d6e); F-030 NOTE | F-028: dropping `replace('\\','/')` everywhere made Git-for-Windows symlinks (`sub\target` on disk) never match Git's object; normalized on Windows only. F-029: Directory kind now threw, so an untracked nested repo, a file replaced by a directory, or (via 2799e18's lsFiles loop) any submodule gitlink failed every stamp; Directory entries restored, gitlinks skipped. F-030: capture throws on a concurrent writer and nothing retries. |
| 048 | B07 | `499addd` | 72 | 28 | F-037 F-038 | fix: revalidate atlas cache contents and refresh collapsed totals | F-037 NOTE; F-038 ACCEPT | F-037: every load reads all non-collapsed files and compares size + hash8 (adequate for an orientation cache). |
| 049 | B07 | `aff4943` | 122 | 33 | F-046 | fix: validate every rendered STATE field for line and fence limits | F-046 NOTE | 240-char/no-newline limits now also apply to plan accept/req, anchor paths and evidence ids. |
| 050 | B07 | `e74302a` | 13 | 13 | F-015 | fix: preserve LocalOs constructor ABI with the internal process test seam | F-015 NOTE | Windows job kill confirmed before settle; POSIX half replaced by e5321f3. terminate() waits 10s like release's tree wait (can return non-terminal); Supervision.terminateTree throws out of close(), leaving later supervisions unterminated. |
| 051 | - | `48a7313` | 0 | 0 | - | docs: record eight repairs and verified Windows build for continuation | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 052 | B07 | `9fc57c8` | 21 | 6 | F-040 | fix: preserve npm test lifecycle in inferred checks | F-040 partial | Final on row 062. |
| 053 | B07 | `331c113` | 6 | 3 | F-085 | fix: acknowledge loop episodes after an applied state patch | F-085 NOTE | Any applied patch clears the cell's whole signature history (a same-turn patch can skip the loop gate). |
| 054 | B07 | `f77a20b` | 4 | 2 | F-099 | fix: emit a rebuild event at the durable cell boundary | F-099 ACCEPT |  |
| 055 | B08 | `f2a8e7b` | 24 | 16 | F-108 | fix: retain actual resident occurrences across pressure rebuild | F-108 ACCEPT |  |
| 056 | B08 | `b6b1421` | 31 | 5 | F-114 | fix: censor host-cancelled and externally stopped calibration work | F-114 ACCEPT |  |
| 057 | B08 | `ea4cd41` | 21 | 6 | F-083 | fix: flag local acceptance executables as review surfaces | F-083 FIXED (8baf840) | `normalize` stripped `./` before the separator test, so a root-level `./check.sh` / `.\check.bat` was still not an acceptance surface. |
| 058 | B08 | `5b6c1a7` | 29 | 5 | F-096 | fix: propagate exceptional campaign completion through await | F-096 FIXED (3d224ae) | After `Astrolabe.close()` the deferred is cancelled, so `await()` threw CancellationException into a live caller (Java future completed cancelled); now returns Cancelled unless the caller itself is cancelled. |
| 059 | B08 | `2584c17` | 60 | 23 | F-141 | fix: retain fixture container failures in runner verdicts | F-141 ACCEPT |  |
| 060 | B08 | `f564b73` | 29 | 19 | F-097 | fix: classify unknown executables as unpredictable writes | F-097 partial | Final on row 063. |
| 061 | B08 | `3c0ff07` | 20 | 4 | F-090 | fix: show packet validation gaps in the next cell request | F-090 FIXED (f5c39cf) | Gap lines were prepended ahead of this turn's hard rejections and could push them out of MAX_NUDGES (§5.1 order). No dedicated regression (fixture cost); ResultPacketTest gap test still passes. |
| 062 | B08 | `864cfe0` | 2 | 0 | F-040 | test: expect npm lifecycle in derived TypeScript acceptance | F-040 ACCEPT | Windows npm.cmd launch handled by d99e21a; derived TS acceptance now needs npm on PATH. |
| 063 | B08 | `4604b89` | 3 | 2 | F-097 | fix: retain read-only classification for git short status | F-097 FIXED (c19aef6) | Unknown executables are now pre-labelled W, but post-hoc escalation of protected-path writes to D only ran for R labels; now for every label. NOTE: tiny read-only allowlist makes `git log`, `rg`, `grep` W/unknown (need WorkspaceWrite). |
| 064 | - | `7dfb5e9` | 0 | 0 | - | docs: record ten out-of-order repairs and verified Windows build | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 065 | B08 | `05f064b` | 19 | 19 | F-039 | perf: precompute outline span membership | F-039 NOTE | Semantics preserved; no equivalence/scaling test; repeated BlockScanner cost remains. |
| 066 | B08 | `c0a2fed` | 74 | 55 | F-042 | fix: bound journal search to scoped paged views | F-042 ACCEPT |  |
| 067 | B08 | `47e16e7` | 135 | 100 | F-052 | fix: keep look views within their prompt budgets | F-052 NOTE | Refusals after `allocate()` leave alias rows with no observation (never shown). |
| 068 | B08 | `cdbe39d` | 97 | 40 | F-069 | fix: preserve runner namespace in failure identities | F-069 FIXED (4054157) | Shapers only got the workspace-relative `cwd`, so Jest identities kept absolute host paths and never matched between workspace and isolated baseline; `RunCapture.executionRoot` (public, ABI) now carries the absolute process directory from Verify/Baseline. |
| 069 | B08 | `22709c5` | 93 | 66 | F-110 | fix: make note supersession atomic | F-110 ACCEPT |  |
| 070 | B08 | `fbee771` | 99 | 48 | F-115 | fix: preserve frozen calibration series for historical attempts | F-115 ACCEPT |  |
| 071 | - | `58c01a3` | 0 | 0 | - | docs: record six bugfix repairs and verified Windows build | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 072 | B08 | `e9d84db` | 33 | 18 | F-132 | fix: reserve repair allowance before helper side effects | F-132 ACCEPT |  |
| 073 | B08 | `dc19437` | 24 | 15 | F-136 | fix: fence publication after approval and between stages | F-136 NOTE | The "cancelled before publish" test was repurposed; the entry fence in Publications has no remaining test. |
| 074 | B08 | `217db66` | 40 | 9 | F-134 | fix: recheck integration authority inside publication lock | F-134 ACCEPT |  |
| 075 | B08 | `c32bc22` | 19 | 10 | F-138 | fix: redact QA transcripts and observed output before persistence | F-138 NOTE | QaDriver's public constructor defaults to `Redaction()`, ignoring `config.redaction` when host-built; raw spawn logs stay in `logs/`. |
| 076 | B08 | `81ce469` | 37 | 13 | F-084 F-118 | fix: enforce turn masks and retain incomplete impact obligations | F-084 ACCEPT; F-118 FIXED (82c0615) | F-118: `look(refs)` always reports `complete: no`, so nothing could clear the obligation although the nudge says "→ look(refs)"; now clears when every found reference was displayed untruncated/unredacted (the finding's own ask). |
| 077 | B08 | `76d60b5` | 27 | 4 | F-117 | fix: widen impact analysis for unresolved script aliases | F-117 NOTE | Broader than asked: any npm package or builtin import makes the importer incomplete, so no JS/TS repo gets a narrow blast. |
| 078 | B08 | `b9312b0` | 75 | 41 | F-142 | fix: bind evaluation identities to comparator semantics and shape | F-142 ACCEPT |  |
| 079 | B09 | `a41a7fa` | 72 | 25 | F-119 F-120 | fix: validate transform effects and protect reusable artifacts | F-119 NOTE; F-120 ACCEPT | F-119: a transform-created file in a gitignored path inside the glob is not a Stamper member, so never revalidated (pre-existing gap). |
| 080 | - | `3bbdad2` | 0 | 0 | - | chore: update QA and evaluation API signatures | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 081 | - | `938a5e6` | 0 | 0 | - | docs: record ten bugfix repairs and selective verification | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 082 | B09 | `2e12762` | 81 | 52 | F-031 F-035 F-139 | fix: recover interrupted shadow snapshot publication | F-031 NOTE; F-035 ACCEPT; F-139 NOTE | F-031: read-path recovery (`readIndex`) can delete a writer's pending file between its pending write and updateRef; a crash after updateRef then recreates the F-031 state. F-139: `register()` validates the caller's mutable object before freezing. |
| 083 | B09 | `b82a7b2` | 55 | 24 | F-036 | fix: contain Atlas and command discovery filesystem reads | F-036 FIXED (c924b42) | Every tracked in-repo symlink to a regular file vanished from the atlas (§7.1); links whose contained target is regular are accepted again. |
| 084 | B09 | `6b7ab81` | 92 | 53 | F-032 | fix: preserve snapshot modes and verify every exported tree entry | F-032 NOTE | Unprivileged Windows cannot materialize tracked symlinks (unavailable, intended); restored deleted files come back 0600; POSIX fileMode ignores core.fileMode=false. |
| 085 | B09 | `adf8840` | 68 | 27 | F-122 | fix: require isolated comparable candidates for flaky retries | F-122 NOTE | Without `candidates` (S0–S2 default) no flake retry runs; the removed agreeing-failures assertion has no isolated replacement. |
| 086 | B09 | `3ffb27c` | 92 | 51 | F-143 | fix: bind promotion trials and fixtures to frozen campaign inputs | F-143 NOTE | Fixture binding compares a caller-supplied label; `FixtureRunner.run` default "as-written" always blocks promotion; fixtures still not per arm (D-220). |
| 087 | B09 | `2799e18` | 57 | 30 | F-027 | fix: include raw tracked changes hidden by Git filters in candidates | F-027 FIXED (ebf1a92, 7aa5736) | Raw-vs-object comparison made every file a member under autocrlf/smudge/eol attributes (owner's machine has autocrlf=true): whole-tree pre-existing dirty capture. Now raw bytes equal to the smudged checkout (`cat-file --batch --filters`, git ≥2.11) are clean; batch parser resyncs on the next header. NOTE: every stamp still hashes all tracked files. |
| 088 | B09 | `ed14e95` | 41 | 10 | F-087 | fix: retain conservative funding for partial provider usage | F-087 ACCEPT |  |
| 089 | B09 | `5d530f0` | 49 | 15 | F-101 | fix: terminate Windows children when job assignment fails | F-101 ACCEPT |  |
| 090 | - | `52607da` | 0 | 0 | - | docs: record eight repairs and selective verification | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 091 | B09 | `d4d2524` | 163 | 88 | F-044 F-045 F-048 F-102 F-103 F-112 | fix: enforce edit permissions and current cell evidence | F-044 NOTE; F-045 NOTE; F-048 ACCEPT; F-102 partial; F-103 ACCEPT; F-112 partial | F-044: `reconcile` accepts any non-blank evidence string; Unknown Run intents stay blocked until a host reconciles. F-045: Validator sets staleAt to the new version (others use anchor.version). F-102: cdb1f58 (row 093) needed. |
| 092 | B09 | `6fcc5a4` | 159 | 92 | F-111 F-112 F-125 F-126 | fix: redact and bound knowledge reads and validate note dependencies | F-111 partial; F-112 NOTE; F-125 FIXED (edf8040); F-126 partial | F-112: STATUS text beyond the redaction scan cap unreachable; a budget smaller than the cursor suffix returns an empty page. F-125: dependency recursion was exponential on diamond DAGs and each search hit reloaded all notes; memoized, notes loaded once per search. NOTE: notes pinned to `contract@vN` are never injected. |
| 093 | B10 | `cdb1f58` | 133 | 65 | F-091 F-092 F-093 F-102 F-106 F-111 F-126 | fix: recover finalization and bind workspace authority to committed state | F-091 ACCEPT; F-092 NOTE; F-093 FIXED (5f043fc); F-102 ACCEPT; F-106 ACCEPT; F-111 NOTE; F-126 ACCEPT | F-092: committed protection uses exact-case patterns; a not-yet-existing path keeps typed case on Windows. F-093: open polled handles without saving, so an exited handle fenced every reopen; terminal states now saved (running/lost still fence, per the finding). No dedicated regression (handle fixture cost). F-111: [A]/[K] injected note bodies rely on admission-time redaction. |
| 094 | - | `2106397` | 0 | 0 | - | docs: record thirteen repairs and selective verification | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 095 | B10 | `6649cec` | 10 | 8 | F-128 | fix: retain child funding when terminal usage is unknown | F-128 NOTE | Unknown charge uses the whole allowance permanently; runChild turns CancellationException into Failed (pre-existing). |
| 096 | B10 | `5ae4892` | 32 | 15 | F-133 | fix: bind integration publication to the complete tested candidate | F-133 NOTE | Combined checks writing non-ignored build artifacts now reject every integration. |
| 097 | B10 | `a471bc4` | 34 | 29 | F-129 | fix: validate probe citations against delivered evidence | F-129 NOTE | Negative test never exercises partial coverage; seed/carry-forward aliases not in shownAliases; alias-backed findings summarized as stale. |
| 098 | B10 | `96aa811` | 49 | 29 | F-137 | fix: require candidate-bound service lifecycle for HTTP QA | F-137 ACCEPT |  |
| 099 | B10 | `d99e21a` | 43 | 27 | F-041 | fix: resolve ancestor Gradle wrappers and launch Windows batch argv | F-041 NOTE | Wrapper names follow the host OS and are persisted, so a campaign resumed on the other OS runs the wrong wrapper; batch argv with %, !, " or CR/LF is refused. |
| 100 | B10 | `b713e83` | 86 | 61 | F-075 | fix: collect invocation-bound JUnit reports during verification | F-075 FIXED (53ab668) | `Files.walk` threw UncheckedIOException (uncaught by Verify's IOException handling) on any unreadable directory under the command dir; now walkFileTree skipping failures and `.git`. |
| 101 | B10 | `7a12c08` | 52 | 36 | F-033 | fix: persist edit preimage associations before workspace mutation | F-033 ACCEPT |  |
| 102 | B10 | `e68cb48` | 81 | 64 | F-124 | fix: roll back knowledge admission atomically with revision ownership | F-124 ACCEPT |  |
| 103 | B10 | `c24c4fe` | 37 | 0 | F-017 | fix: bound Git command lifetime and captured output | F-017 NOTE | All failures report START_FAILED; early-exit git before reading stdin becomes GitError; finally kills descendants seen (may kill detached auto-gc/fsmonitor); 120 s default for large-repo ops. |
| 104 | B10 | `0898a57` | 169 | 135 | F-107 F-109 F-113 | fix: rebuild carried context and persist fact and note coherence | F-107 ACCEPT; F-109 ACCEPT; F-113 NOTE | F-113: reconcile at open durably marks Stale admitted notes with unpinned/unknown dependsOn, removing them from kb.get/search. |
| 105 | - | `f232375` | 0 | 0 | - | chore: update core ABI for recovery and evidence fixes | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 106 | - | `01c34b3` | 0 | 0 | - | docs: record twelve fixes and selective verification | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 107 | B10 | `0959ee7` | 102 | 38 | F-100 | fix: allowlist inherited Windows process handles | F-100 NOTE | Handles still created inheritable; JDK ProcessBuilder children (git, rg) inherit all, so a concurrent one can hold a log handle open. |
| 108 | B10 | `0ba3658` | 133 | 106 | F-019 | fix: bound search file reads regex work and process output | F-019 partial | Final on row 112. |
| 109 | B11 | `0fc1dbf` | 204 | 116 | F-135 | fix: recover interrupted integration publications from durable preimages | F-135 NOTE | A conflicting later edit sets the intent Unknown and every later open/integrate throws until a host reconciles (fail-closed); Integrator.kt:313 `catch Throwable` swallows CancellationException into Rejected; crash leftovers (`.astrolabe-integration-*.tmp`) not removed. |
| 110 | B11 | `4f6eb64` | 356 | 201 | F-086 F-095 F-127 | fix: persist campaign funding and reconcile provider and extractor usage | F-086 NOTE; F-095 FIXED (9469e27); F-127 NOTE | F-086: terminal consumed under NonCancellable with no deadline (a provider that never settles hangs the cell); its ResultPacketTest assertion (callsWithoutUsage=1) was obsolete under terminal-reported usage and failed at dba6344 — updated in 4988bd7. F-095: an unsettled call stored reserved money as unknown, so every later reservation and routing refused ("funding exhausted") under a cost cap; now kept as known funding (also failed extraction). F-127: extraction without maxCost never runs under a cost cap; null-context invocation ids can collide. |
| 111 | B11 | `1120f89` | 433 | 201 | F-082 F-089 F-104 F-105 | fix: complete independently assessed work and resume or replan blocked increment | F-082 NOTE; F-089 NOTE; F-104 NOTE; F-105 FIXED (36c589f) | F-082: Verifier.accept ignores Assessments without a candidate. F-089: in S2 a model reviewCell verdict resolves test-integrity flags (D-23 names Authority.review) — owner to confirm. F-104: a replan reusing a split id with a narrower definition blocks the campaign. F-105: stale requirements came only from the graph ledger, so a resumed interrupted finalization re-accepted nothing (ControllerTest failed at dba6344); now union with the durable ledger. NOTE: any amendment unblocks every Blocked increment; each host answer bumps the contract version, re-asking all assessments. |
| 112 | B11 | `745e229` | 3 | 3 | F-019 | fix: retain typed failures when search candidate enumeration fails | F-019 FIXED (829997b) | The 10M character-read budget was search-wide (JVM backend failed any no-match search over ~10 MB); the 8 MiB check ran before the binary probe (one large binary failed every search, both backends). Now per-line budget + global deadline; binaries skipped. NOTE: oversized text and >1 MiB rg JSON lines still fail the search explicitly. |
| 113 | B12 | `0d7ad78` | 21 | 21 | (no finding: review as change) | docs: clarify platform process ownership boundaries | F-none ACCEPT | Comments and named Win32 constants only (values verified). |
| 114 | - | `bc742fe` | 0 | 0 | - | chore: update core ABI for campaign evidence and funding | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 115 | - | `a7189d4` | 0 | 0 | - | docs: record ten fixes and remaining POSIX containment work | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 116 | B13 | `e5321f3` | 878 | 657 | F-016 | fix: own detached Linux descendants with an isolated subreaper | F-016 FIXED (d8e5918) | Root cause removed (subreaper adopts setsid/double-fork orphans; FINISHED only after ECHILD; failures → Lost; deadline classification kept; Windows unchanged). Defect: helper JVM started with an empty env → C locale → non-ASCII classpath/PATH failed every Linux launch; host locale vars now passed (Linux-only test, compile-verified here). NOTE: 10 s cleanup budget equals host confirm budget (near-limit cleanup → Lost); EPERM on uid-changed descendants and daemon-handed work escape (reported Lost); nested jar `require` throws IllegalArgumentException; process-ownership.yml triggers only on feature/bugfix. |
| 117 | B14 | `1b507f2` | 9 | 9 | (no finding: review as change) | docs: close final finding after Linux containment validation [skip ci] | F-none ACCEPT | Docs + one KDoc line; claims match e5321f3. |
| 118 | - | `dba6344` | 0 | 0 | - | all issues fixed | SKIP (docs/ABI; covered by final docs+ABI step) |  |
