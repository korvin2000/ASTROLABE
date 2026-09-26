# Bugfix continuation

Checkpoint: `feature/bugfix`, 2026-09-26. Source checkpoint: `ae8fa2a`.
**38 findings fixed across two sessions (14 this continuation); 104 open; F-034 previously resolved.**
P0-P6 remains 185/185 implemented. P7 is excluded. Local commits only; nothing pushed.

## Read first
1. `findings.md` JSON `fix_progress`: exact fixed/open IDs and priorities.
2. `audit/BUGFIX-PROGRESS.md`: commits, regressions, compatibility and verification.
3. Read selected finding sections and their cited code/tests only. Do not repeat the audit.

## Next fixes
1. F-076 nested-package checker/blast paths.
2. F-022/F-088/F-094 remaining authority identity and currency.
3. F-054 normalized anchors; F-056 revert scope; F-057 partial effects; F-058 recall aliases.
4. F-061/F-062/F-064/F-065 run durability, handle ownership, cancellation and capture drain.
5. Continue remaining open findings in audit order with focused regressions and local commits.

## Verification
All continuation fix groups pass focused Windows JDK 26 tests.
Full Windows build passed: 1314 core tests: 1307 passed, 7 skipped, zero failures/errors; other modules, compilation, packaging and ABI passed.
POSIX executable-mode test is present but skipped on Windows. No new Linux/CI evidence.
Final log: ignored build/bugfix-continuation-build-verified.log. Final checkpoint ae8fa2a.

## Compatibility and decisions
No public signatures changed in this continuation; prior-session JVM consumers still need rebuilding.
D-262: model-added verification commands need exact argv/cwd authorization from non-model acceptance.
D-263: one edit operation per canonical path; combine hunks in that operation; reverts run alone.
D-264: default parser policy shaper/2 invalidates prior receipt definitions, requiring re-verification.
Redacted/capped edit reports grant no new source coverage; use look before subsequent anchored edits.
Isolated export reads clean files from immutable Git objects; large-repository performance unmeasured.

## Existing implementation debts
D-254 recovery wiring; D-70/D-71 replanning; D-241 S3 resume; D-252 retrieval;
D-113/D-120 behaviour maps; D-124/D-244 review evidence; D-220/D-221 eval arms;
D-200 QA scheduling; D-66 S0 full suite; D-92 impact checks. See TODO decision rows.

## Workspace
Preserve existing CLAUDE.md/ISSUES.md edits and untracked continue_fixing.md/todo_findings.txt.
No unfinished source changes are intended; findings.md is the committed repair ledger.
