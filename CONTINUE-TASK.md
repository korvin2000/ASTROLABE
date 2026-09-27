# Bugfix continuation
Checkpoint: `feature/bugfix`, 2026-09-28. Eight fix commits from `2e12762` through `5d530f0`.
**106 findings fixed (8 this continuation); 36 open; F-034 previously resolved.**
P0-P6 remains 185/185 implemented. P7 excluded. User authorized committing and pushing to origin/feature/bugfix.

## Read first
1. `findings.md` JSON `fix_progress`: exact fixed/open IDs and priorities.
2. `audit/BUGFIX-PROGRESS.md`: repairs, compatibility and verification.
3. Selected finding sections and cited code/tests only; do not repeat the audit.

## Next fixes
1. F-016 POSIX detached-process containment; F-017 Git deadlines; F-019 search bounds.
2. F-033 durable preimage associations; F-041 Gradle wrapper launch; F-044 unknown-intent reconciliation.
3. F-045 fact freshness; F-075 Gradle/Maven report acquisition; F-086 provider terminal reconciliation.
4. F-127 extractor billing and F-137 HTTP QA candidate binding remain open.
5. Complete queue: `findings.md` JSON `fix_progress.remaining_open_ids`.

## This continuation
Fixed F-027/F-031/F-032/F-036/F-087/F-101/F-122/F-143.
Raw tracked bytes are checked even when Git reports clean; hidden changes enter recovery snapshots.
Shadow publication has a durable pending index; export/restore preserves supported modes and validates types.
Atlas/Sniff uses WorkspacePath. Failed Windows launches terminate unassigned suspended children.
Partial usage keeps conservative funding; retries require isolation; promotion binds final trials and fixtures.

## Verification and compatibility
Eleven selected classes and one CellTest method passed on Windows JDK 26, with platform skips; eval ABI regenerated and core/eval checked.
No full suite/build or Linux/CI run this session, per the user's selective-testing request.
Last full Windows build: fbee771, 1667 tests, 13 skipped; packaging/ABI passed.
D-291 through D-296 record the fixes. No database schema change.
Pass a design to PromotionEvidence to establish independence; fixture labels must be config fingerprints.
Stamp cost is proportional to tracked bytes; large-repository performance was not benchmarked.
Old incomplete snapshots and ref/index gaps without pending records are not migrated.
No candidate directory or changed candidate/unknown environment means isolated retry is unavailable.

## Existing debts and workspace
D-254 recovery wiring; D-70/D-71 replanning; D-241 S3 resume; D-252 retrieval;
D-113/D-120 behaviour maps; D-124/D-244 review evidence; D-220/D-221 eval arms;
D-200 QA scheduling; D-66 S0 suite; D-92 impact checks. See TODO decision rows.
Preserve pre-existing CLAUDE.md/ISSUES.md edits and untracked continue_fixing.md/todo_findings.txt.
continue_fixing.md contains an older checkpoint; findings.md is the current repair ledger.
