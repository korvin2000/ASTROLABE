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

- Status: not started. Next round: B01–B03. Rounds done: 0. FIXED: 0. REJECTED: 0.
- 100 code-bearing commits in 14 batches (≤1000 code lines each; `e5321f3`/F-016 alone); 18 docs/ABI-only commits SKIP.

## Ledger

Verdicts: `PENDING` → `IN_REVIEW` → per finding `ACCEPT` / `NOTE` / `FIXED (sha)` / `REJECTED (sha)`; `SKIP` for
docs/ABI-only commits. A finding spanning several commits gets its final verdict on its last row (earlier rows: `partial`).
Code± = changed lines excluding `*.md` and `*/api/*.api`; Main± = lines under `src/main`.

| # | Batch | Commit | Code± | Main± | Findings | Subject | Verdict | Notes |
|---|---|---|---|---|---|---|---|---|
| 001 | B01 | `9dbf774` | 107 | 64 | F-066 F-067 | fix: reject incomplete test reports and count all suite outcomes | PENDING |  |
| 002 | B01 | `24e53e3` | 59 | 35 | F-011 F-012 F-098 | fix: roll back failed commits and redact overlapping secret spans | PENDING |  |
| 003 | B01 | `4e9d774` | 36 | 16 | F-003 F-005 | fix: reject duplicate tool ids and saturate token arithmetic | PENDING |  |
| 004 | B01 | `d7551cc` | 75 | 30 | F-047 F-049 F-050 F-051 | fix: preserve recall coordinates and synchronize coverage updates | PENDING |  |
| 005 | B01 | `f060c97` | 94 | 42 | F-043 F-077 F-121 F-131 F-140 | fix: enforce receipt currency and conservative test integrity review | PENDING |  |
| 006 | B01 | `75e3280` | 54 | 25 | F-031 F-035 F-139 | fix: freeze generated tool definitions and type invalid path refusals | PENDING |  |
| 007 | B01 | `43f8a15` | 86 | 14 | F-007 F-008 F-009 F-116 | fix: preserve event order cancellation and root closure membership | PENDING |  |
| 008 | B01 | `210763d` | 19 | 11 | F-068 F-130 | fix: apply required check veto to cached reviews and partial XML reports | PENDING |  |
| 009 | B01 | `15bfcaa` | 31 | 4 | F-011 | fix: quarantine database connections when rollback and close fail | PENDING |  |
| 010 | B01 | `3f433f6` | 27 | 15 | F-043 | fix: distinguish expected QA exits from HTTP response status | PENDING |  |
| 011 | - | `491c344` | 0 | 0 | - | docs: record 24 audit fixes verification and remaining repair queue | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 012 | B01 | `6e4e671` | 60 | 27 | F-053 F-055 | fix: reject overlapping edit operations and redact diagnostics | PENDING |  |
| 013 | B01 | `b5ae2f3` | 74 | 44 | F-059 | fix: redact run views and report bounded log captures | PENDING |  |
| 014 | B01 | `2b30ba6` | 154 | 83 | F-059 F-070 F-078 F-079 F-080 | fix: bind verification results to complete stable candidates | PENDING |  |
| 015 | B01 | `137b89b` | 42 | 24 | F-081 | fix: withhold baseline failure ledgers when inputs change | PENDING |  |
| 016 | B01 | `e4e23eb` | 49 | 12 | F-060 F-074 | fix: bind authority replies to pending requests and current contracts | PENDING |  |
| 017 | B02 | `cf3e80d` | 57 | 30 | F-123 | fix: require every final campaign gate to certify the candidate | PENDING |  |
| 018 | B02 | `fb6bffc` | 35 | 12 | F-072 F-073 | fix: publish STATE changes only after persistence and track schema refusals | PENDING |  |
| 019 | B02 | `6e1f2ca` | 70 | 27 | F-071 F-080 | fix: restrict model verification commands and invalidate legacy receipts | PENDING |  |
| 020 | B02 | `da8bc42` | 33 | 8 | F-055 F-070 | fix: withhold unseen edit coverage and validate all acceptance selections | PENDING |  |
| 021 | B02 | `ae8fa2a` | 17 | 0 | (no finding: review as change) | test: align precompile fixtures and policy changes with receipt rules | PENDING |  |
| 022 | - | `6fdea0c` | 0 | 0 | - | docs: record 14 additional fixes and passing full validation | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 023 | B02 | `1316839` | 221 | 41 | F-022 | fix: bind amendment replies to current pending authority | PENDING |  |
| 024 | B02 | `c49ca61` | 77 | 44 | F-076 F-088 | fix: rebase selected check paths to their command directory | PENDING |  |
| 025 | B02 | `c2f6989` | 56 | 27 | F-054 | fix: preserve raw boundary whitespace in normalized anchors | PENDING |  |
| 026 | B03 | `742d834` | 445 | 97 | F-056 F-057 F-058 F-088 | fix: scope reverts and retain honest edit effects and recall links | PENDING |  |
| 027 | B03 | `ff510f7` | 295 | 112 | F-062 F-065 F-088 | fix: enforce handle ownership and drain terminal process logs | PENDING |  |
| 028 | B03 | `e769511` | 147 | 30 | F-088 F-094 | fix: revalidate live authority before effects and final completion | PENDING |  |
| 029 | B03 | `b13c18d` | 16 | 16 | F-088 | fix: preserve late archival completion without dispatching new effects | PENDING |  |
| 030 | - | `ce0051a` | 0 | 0 | - | docs: record ten further repairs and passing full validation | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 031 | B04 | `8491837` | 181 | 63 | F-023 | fix: persist amendment resolutions atomically with contract changes | PENDING |  |
| 032 | B04 | `9192a41` | 297 | 116 | F-001 F-002 | fix: freeze nested configuration and canonicalize attempt fingerprints | PENDING |  |
| 033 | B05 | `568f6bf` | 661 | 238 | F-061 F-063 F-064 | fix: retain durable run evidence and bound cancellable process capture | PENDING |  |
| 034 | - | `b7b74d0` | 0 | 0 | - | chore: refresh ABI for durable resolutions and run provenance | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 035 | - | `cb715da` | 0 | 0 | - | docs: record six repairs and passing full Windows build | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 036 | B06 | `6b7c801` | 82 | 0 | F-006 | fix: reconcile fake invocation cancellation without a response waiter | PENDING |  |
| 037 | B06 | `55e86ea` | 76 | 38 | F-004 | fix: account for protocol framing and unknown native request context | PENDING |  |
| 038 | B06 | `6bb0767` | 61 | 18 | F-014 | fix: reject durable state inside any registered workspace | PENDING |  |
| 039 | B06 | `c406b7a` | 159 | 79 | F-018 F-020 F-021 | fix: align Unicode search and refuse unsafe candidate enumeration | PENDING |  |
| 040 | B06 | `4c99034` | 168 | 102 | F-010 | fix: read composed projections from one snapshot and bound latest reads | PENDING |  |
| 041 | B06 | `1e3ef70` | 52 | 22 | F-024 F-025 | fix: preserve slice obligations and enforce contract digest capacity | PENDING |  |
| 042 | B06 | `88ab4f5` | 100 | 57 | F-026 | fix: resume failed coherence delivery before advancing recorded versions | PENDING |  |
| 043 | B06 | `eccb010` | 22 | 2 | F-004 | fix: honor profile request estimators at cell admission and refresh ABI | PENDING |  |
| 044 | - | `a7ccc15` | 0 | 0 | - | docs: record ten repairs and passing full Windows validation | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 045 | B06 | `0126918` | 212 | 173 | F-013 | fix: bound blob cleanup scans and prioritize referenced orphan repair | PENDING |  |
| 046 | B07 | `389634d` | 275 | 139 | F-015 | fix: confirm process container cleanup before publishing terminal outcomes | PENDING |  |
| 047 | B07 | `527c95b` | 476 | 219 | F-028 F-029 F-030 | fix: preserve snapshot link objects and reject incomplete or mixed captures | PENDING |  |
| 048 | B07 | `499addd` | 72 | 28 | F-037 F-038 | fix: revalidate atlas cache contents and refresh collapsed totals | PENDING |  |
| 049 | B07 | `aff4943` | 122 | 33 | F-046 | fix: validate every rendered STATE field for line and fence limits | PENDING |  |
| 050 | B07 | `e74302a` | 13 | 13 | F-015 | fix: preserve LocalOs constructor ABI with the internal process test seam | PENDING |  |
| 051 | - | `48a7313` | 0 | 0 | - | docs: record eight repairs and verified Windows build for continuation | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 052 | B07 | `9fc57c8` | 21 | 6 | F-040 | fix: preserve npm test lifecycle in inferred checks | PENDING |  |
| 053 | B07 | `331c113` | 6 | 3 | F-085 | fix: acknowledge loop episodes after an applied state patch | PENDING |  |
| 054 | B07 | `f77a20b` | 4 | 2 | F-099 | fix: emit a rebuild event at the durable cell boundary | PENDING |  |
| 055 | B08 | `f2a8e7b` | 24 | 16 | F-108 | fix: retain actual resident occurrences across pressure rebuild | PENDING |  |
| 056 | B08 | `b6b1421` | 31 | 5 | F-114 | fix: censor host-cancelled and externally stopped calibration work | PENDING |  |
| 057 | B08 | `ea4cd41` | 21 | 6 | F-083 | fix: flag local acceptance executables as review surfaces | PENDING |  |
| 058 | B08 | `5b6c1a7` | 29 | 5 | F-096 | fix: propagate exceptional campaign completion through await | PENDING |  |
| 059 | B08 | `2584c17` | 60 | 23 | F-141 | fix: retain fixture container failures in runner verdicts | PENDING |  |
| 060 | B08 | `f564b73` | 29 | 19 | F-097 | fix: classify unknown executables as unpredictable writes | PENDING |  |
| 061 | B08 | `3c0ff07` | 20 | 4 | F-090 | fix: show packet validation gaps in the next cell request | PENDING |  |
| 062 | B08 | `864cfe0` | 2 | 0 | F-040 | test: expect npm lifecycle in derived TypeScript acceptance | PENDING |  |
| 063 | B08 | `4604b89` | 3 | 2 | F-097 | fix: retain read-only classification for git short status | PENDING |  |
| 064 | - | `7dfb5e9` | 0 | 0 | - | docs: record ten out-of-order repairs and verified Windows build | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 065 | B08 | `05f064b` | 19 | 19 | F-039 | perf: precompute outline span membership | PENDING |  |
| 066 | B08 | `c0a2fed` | 74 | 55 | F-042 | fix: bound journal search to scoped paged views | PENDING |  |
| 067 | B08 | `47e16e7` | 135 | 100 | F-052 | fix: keep look views within their prompt budgets | PENDING |  |
| 068 | B08 | `cdbe39d` | 97 | 40 | F-069 | fix: preserve runner namespace in failure identities | PENDING |  |
| 069 | B08 | `22709c5` | 93 | 66 | F-110 | fix: make note supersession atomic | PENDING |  |
| 070 | B08 | `fbee771` | 99 | 48 | F-115 | fix: preserve frozen calibration series for historical attempts | PENDING |  |
| 071 | - | `58c01a3` | 0 | 0 | - | docs: record six bugfix repairs and verified Windows build | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 072 | B08 | `e9d84db` | 33 | 18 | F-132 | fix: reserve repair allowance before helper side effects | PENDING |  |
| 073 | B08 | `dc19437` | 24 | 15 | F-136 | fix: fence publication after approval and between stages | PENDING |  |
| 074 | B08 | `217db66` | 40 | 9 | F-134 | fix: recheck integration authority inside publication lock | PENDING |  |
| 075 | B08 | `c32bc22` | 19 | 10 | F-138 | fix: redact QA transcripts and observed output before persistence | PENDING |  |
| 076 | B08 | `81ce469` | 37 | 13 | F-084 F-118 | fix: enforce turn masks and retain incomplete impact obligations | PENDING |  |
| 077 | B08 | `76d60b5` | 27 | 4 | F-117 | fix: widen impact analysis for unresolved script aliases | PENDING |  |
| 078 | B08 | `b9312b0` | 75 | 41 | F-142 | fix: bind evaluation identities to comparator semantics and shape | PENDING |  |
| 079 | B09 | `a41a7fa` | 72 | 25 | F-119 F-120 | fix: validate transform effects and protect reusable artifacts | PENDING |  |
| 080 | - | `3bbdad2` | 0 | 0 | - | chore: update QA and evaluation API signatures | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 081 | - | `938a5e6` | 0 | 0 | - | docs: record ten bugfix repairs and selective verification | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 082 | B09 | `2e12762` | 81 | 52 | F-031 F-035 F-139 | fix: recover interrupted shadow snapshot publication | PENDING |  |
| 083 | B09 | `b82a7b2` | 55 | 24 | F-036 | fix: contain Atlas and command discovery filesystem reads | PENDING |  |
| 084 | B09 | `6b7ab81` | 92 | 53 | F-032 | fix: preserve snapshot modes and verify every exported tree entry | PENDING |  |
| 085 | B09 | `adf8840` | 68 | 27 | F-122 | fix: require isolated comparable candidates for flaky retries | PENDING |  |
| 086 | B09 | `3ffb27c` | 92 | 51 | F-143 | fix: bind promotion trials and fixtures to frozen campaign inputs | PENDING |  |
| 087 | B09 | `2799e18` | 57 | 30 | F-027 | fix: include raw tracked changes hidden by Git filters in candidates | PENDING |  |
| 088 | B09 | `ed14e95` | 41 | 10 | F-087 | fix: retain conservative funding for partial provider usage | PENDING |  |
| 089 | B09 | `5d530f0` | 49 | 15 | F-101 | fix: terminate Windows children when job assignment fails | PENDING |  |
| 090 | - | `52607da` | 0 | 0 | - | docs: record eight repairs and selective verification | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 091 | B09 | `d4d2524` | 163 | 88 | F-044 F-045 F-048 F-102 F-103 F-112 | fix: enforce edit permissions and current cell evidence | PENDING |  |
| 092 | B09 | `6fcc5a4` | 159 | 92 | F-111 F-112 F-125 F-126 | fix: redact and bound knowledge reads and validate note dependencies | PENDING |  |
| 093 | B10 | `cdb1f58` | 133 | 65 | F-091 F-092 F-093 F-102 F-106 F-111 F-126 | fix: recover finalization and bind workspace authority to committed state | PENDING |  |
| 094 | - | `2106397` | 0 | 0 | - | docs: record thirteen repairs and selective verification | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 095 | B10 | `6649cec` | 10 | 8 | F-128 | fix: retain child funding when terminal usage is unknown | PENDING |  |
| 096 | B10 | `5ae4892` | 32 | 15 | F-133 | fix: bind integration publication to the complete tested candidate | PENDING |  |
| 097 | B10 | `a471bc4` | 34 | 29 | F-129 | fix: validate probe citations against delivered evidence | PENDING |  |
| 098 | B10 | `96aa811` | 49 | 29 | F-137 | fix: require candidate-bound service lifecycle for HTTP QA | PENDING |  |
| 099 | B10 | `d99e21a` | 43 | 27 | F-041 | fix: resolve ancestor Gradle wrappers and launch Windows batch argv | PENDING |  |
| 100 | B10 | `b713e83` | 86 | 61 | F-075 | fix: collect invocation-bound JUnit reports during verification | PENDING |  |
| 101 | B10 | `7a12c08` | 52 | 36 | F-033 | fix: persist edit preimage associations before workspace mutation | PENDING |  |
| 102 | B10 | `e68cb48` | 81 | 64 | F-124 | fix: roll back knowledge admission atomically with revision ownership | PENDING |  |
| 103 | B10 | `c24c4fe` | 37 | 0 | F-017 | fix: bound Git command lifetime and captured output | PENDING |  |
| 104 | B10 | `0898a57` | 169 | 135 | F-107 F-109 F-113 | fix: rebuild carried context and persist fact and note coherence | PENDING |  |
| 105 | - | `f232375` | 0 | 0 | - | chore: update core ABI for recovery and evidence fixes | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 106 | - | `01c34b3` | 0 | 0 | - | docs: record twelve fixes and selective verification | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 107 | B10 | `0959ee7` | 102 | 38 | F-100 | fix: allowlist inherited Windows process handles | PENDING |  |
| 108 | B10 | `0ba3658` | 133 | 106 | F-019 | fix: bound search file reads regex work and process output | PENDING |  |
| 109 | B11 | `0fc1dbf` | 204 | 116 | F-135 | fix: recover interrupted integration publications from durable preimages | PENDING |  |
| 110 | B11 | `4f6eb64` | 356 | 201 | F-086 F-095 F-127 | fix: persist campaign funding and reconcile provider and extractor usage | PENDING |  |
| 111 | B11 | `1120f89` | 433 | 201 | F-082 F-089 F-104 F-105 | fix: complete independently assessed work and resume or replan blocked increment | PENDING |  |
| 112 | B11 | `745e229` | 3 | 3 | F-019 | fix: retain typed failures when search candidate enumeration fails | PENDING |  |
| 113 | B12 | `0d7ad78` | 21 | 21 | (no finding: review as change) | docs: clarify platform process ownership boundaries | PENDING |  |
| 114 | - | `bc742fe` | 0 | 0 | - | chore: update core ABI for campaign evidence and funding | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 115 | - | `a7189d4` | 0 | 0 | - | docs: record ten fixes and remaining POSIX containment work | SKIP (docs/ABI; covered by final docs+ABI step) |  |
| 116 | B13 | `e5321f3` | 878 | 657 | F-016 | fix: own detached Linux descendants with an isolated subreaper | PENDING |  |
| 117 | B14 | `1b507f2` | 9 | 9 | (no finding: review as change) | docs: close final finding after Linux containment validation [skip ci] | PENDING |  |
| 118 | - | `dba6344` | 0 | 0 | - | all issues fixed | SKIP (docs/ABI; covered by final docs+ABI step) |  |
