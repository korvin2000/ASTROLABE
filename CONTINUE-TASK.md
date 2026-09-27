# Bugfix continuation

Checkpoint: `feature/bugfix`, 2026-09-27. Source checkpoint: `eccb010`.
**64 findings fixed across five sessions (10 latest); 78 open; F-034 previously resolved.**
P0-P6 remains 185/185 implemented. P7 excluded. Local commits only; nothing pushed.

## Read first
1. `findings.md` JSON `fix_progress`: exact fixed/open IDs and priorities.
2. `audit/BUGFIX-PROGRESS.md`: commits, regressions, compatibility and verification.
3. Selected finding sections and cited code/tests only. Do not repeat the audit.

## Next fixes
1. F-013 bounded blob GC and referenced orphan adoption.
2. F-015/F-016 process settlement and POSIX containment; F-017 Git deadlines.
3. F-019 search CPU/memory/process bounds, then F-027 onward (raw snapshot fidelity).

## Verification
Full Windows JDK 26 build passed on eccb010: 1471 passed, 8 skipped, zero failures/errors.
Compilation, packaging and ABI passed. Log: `build/bugfix-fifth-build.log`.
No new Linux/CI or P7 evidence. New POSIX permission test is skipped on Windows.

## Compatibility and decisions
F-004/F-006/F-010/F-014/F-018/F-020/F-021/F-024/F-025/F-026 repaired.
D-269: generic request framing is approximate; unknown native context requires provider accounting.
D-270: digest capacity exits before dispatch; slices retain independent increment obligations.
D-271: coherence retries unacknowledged listeners before later transitions; failures block delivery.
ContractRepository.latest adds a default method. ContractSlice constructor/copy ABI changed.
Rebuild consumers. Old slice JSON decodes but cannot recover previously omitted obligations.
Rebuild old slices from their authoritative increment. No database schema bump.
D-261 through D-268 still apply; see the ledger for earlier compatibility limits.

## Existing implementation debts
D-254 recovery wiring; D-70/D-71 replanning; D-241 S3 resume; D-252 retrieval;
D-113/D-120 behaviour maps; D-124/D-244 review evidence; D-220/D-221 eval arms;
D-200 QA scheduling; D-66 S0 suite; D-92 impact checks. See TODO decision rows.

## Workspace
Preserve existing CLAUDE.md/ISSUES.md edits and untracked continue_fixing.md/todo_findings.txt.
No unfinished source changes intended. findings.md is the committed repair ledger.
