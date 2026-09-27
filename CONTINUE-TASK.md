# Bugfix continuation
Checkpoint: `feature/bugfix`, 2026-09-28. Eight fix commits from `e9d84db` through `a41a7fa`.
**98 findings fixed (10 this continuation); 44 open; F-034 previously resolved.**
P0-P6 remains 185/185 implemented. P7 excluded. User authorized committing and pushing to origin/feature/bugfix.

## Read first
1. `findings.md` JSON `fix_progress`: exact fixed/open IDs and priorities.
2. `audit/BUGFIX-PROGRESS.md`: repairs, compatibility and verification.
3. Selected finding sections and cited code/tests only; do not repeat the audit.

## Next fixes
1. F-016 POSIX detached-process containment; F-017 Git deadlines; F-019 search bounds.
2. F-027 raw membership under Git filters; F-031/F-032/F-033 snapshot recovery/fidelity.
3. F-036 Atlas/Sniff containment. Smaller remaining candidates: F-122 isolated flaky retries, F-127 extractor billing.
4. F-143 evaluation trial/workload binding and F-137 HTTP QA candidate binding remain open.
5. Complete queue: `findings.md` JSON `fix_progress.remaining_open_ids`.

## This continuation
Fixed F-084/F-117/F-118/F-119/F-120/F-132/F-134/F-136/F-138/F-142.
Turn masks are enforced; publication rechecks authority; repair allowance is reserved before dispatch.
Incomplete references retain obligations; ambiguous bare imports widen dependency analysis.
Transforms validate actual changed paths and redact diffs; QA redacts transcripts and observations.
Characterization bytes use protected comparison storage. Evaluation IDs include shape/comparator semantics.

## Verification and compatibility
Eleven selected test classes passed on Windows JDK 26; core/eval ABI regenerated and checked.
No full suite/build or Linux/CI run this session, per the user's selective-testing request.
Last full Windows build: fbee771, 1667 tests, 13 skipped; packaging/ABI passed.
D-285 through D-290 record the fixes. No database schema change.
QaDriver's Kotlin default-constructor callers must rebuild; explicit Java overloads remain.
Regenerate old evaluation manifests/trial tables using VariantConfig/ArmConfig fingerprints.
Lexical refs cannot clear complete-reference obligations; plan rescoping remains available.
Existing raw/leaked artifacts are not scrubbed. Trusted-local transforms retain guarded recovery limits.

## Existing debts and workspace
D-254 recovery wiring; D-70/D-71 replanning; D-241 S3 resume; D-252 retrieval;
D-113/D-120 behaviour maps; D-124/D-244 review evidence; D-220/D-221 eval arms;
D-200 QA scheduling; D-66 S0 suite; D-92 impact checks. See TODO decision rows.
Preserve pre-existing CLAUDE.md/ISSUES.md edits and untracked continue_fixing.md/todo_findings.txt.
continue_fixing.md contains an older checkpoint; findings.md is the current repair ledger.
