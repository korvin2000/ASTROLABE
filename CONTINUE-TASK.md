# Bugfix continuation
Checkpoint: `feature/bugfix`, 2026-09-27. Source checkpoint: `e74302a`.
**72 findings fixed across six sessions (8 latest); 70 open; F-034 previously resolved.**
P0-P6 remains 185/185 implemented. P7 excluded. Local commits only; nothing pushed.

## Read first
1. `findings.md` JSON `fix_progress`: exact fixed/open IDs and priorities.
2. `audit/BUGFIX-PROGRESS.md`: commits, regressions, compatibility and verification.
3. Selected finding sections and cited code/tests only. Do not repeat the audit.

## Next fixes
1. F-016 POSIX detached-process containment; F-017 complete Git command deadlines/bounds.
2. F-019 search CPU/memory/process bounds; F-027 raw membership hidden by clean filters.
3. F-031 shadow-ref crash recovery, F-032 executable modes/export fidelity, F-033 durable preimages.
4. F-036 Atlas/Sniff containment, then remaining queue in findings.md.

## Verification
Full Windows JDK 26 build passed on e74302a: 1637 passed, 13 skipped; ABI/packaging passed.
Log: `build/bugfix-sixth-build-verified.log`. No new Linux/CI or P7 evidence.
Five new real-symlink tests skip on this Windows host; Windows junction/process tests ran.

## Compatibility and decisions
F-013/F-015/F-028/F-029/F-030/F-037/F-038/F-046 repaired.
D-272: GC cleanup capped at 4096 entries/rows; mandatory reference repair remains proportional.
D-273: terminal publication waits for container quiescence; failed confirmation is Lost.
D-274: no-follow link capture; unreadable/staged failures and observed input movement abort.
D-275: Atlas rehashes content and refreshes collapsed totals; total work remains repository-sized.
D-276: every rendered STATE operation string obeys inline length/newline/fence validation.
No schema/public API change. Existing incomplete snapshots are not reconstructed.
GC cursors restart on reopen; detached POSIX groups and Git-filter membership remain open.
D-261 through D-271 still apply; see ledger. External-writer acquisition remains best-effort.

## Existing implementation debts
D-254 recovery wiring; D-70/D-71 replanning; D-241 S3 resume; D-252 retrieval;
D-113/D-120 behaviour maps; D-124/D-244 review evidence; D-220/D-221 eval arms;
D-200 QA scheduling; D-66 S0 suite; D-92 impact checks. See TODO decision rows.

## Workspace
Preserve existing CLAUDE.md/ISSUES.md edits and untracked continue_fixing.md/todo_findings.txt.
findings.md is the committed repair ledger; continue_fixing.md contains an older checkpoint.
