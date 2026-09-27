# Bugfix continuation

Checkpoint: `feature/bugfix`, 2026-09-27. Source checkpoint: `b7b74d0`.
**54 findings fixed across four sessions (6 latest); 88 open; F-034 previously resolved.**
P0-P6 remains 185/185 implemented. P7 excluded. Local commits only; nothing pushed.

## Read first
1. `findings.md` JSON `fix_progress`: exact fixed/open IDs and priorities.
2. `audit/BUGFIX-PROGRESS.md`: commits, regressions, compatibility and verification.
3. Selected finding sections and cited code/tests only. Do not repeat the audit.

## Next fixes
1. F-004 generic request estimation and native/protocol context.
2. Continue remaining findings in audit order: F-006, F-010, F-013, F-014, F-015, F-016.

## Verification
Full Windows JDK 26 build passed: 1370 core passed, 7 skipped; all modules 1453 passed.
Zero failures/errors; packaging and ABI passed. Log: `build/bugfix-fourth-build.log`.
Focused regressions passed. No new Linux/CI or P7 evidence.

## Compatibility and decisions
F-001/F-002/F-023/F-061/F-063/F-064 repaired; see the ledger for evidence.
D-266: background changes describe an interval, with unknown exclusive attribution.
D-267: captures retain at most 8 MiB; capped/missing evidence cannot certify green.
D-268: immutable config includes default roles; canonical v2 fingerprints invalidate old caches.
ContractRepository adds atomic resolution/read methods; Handle gains defaulted provenance fields.
Core consumers must rebuild. Existing JSON decodes; legacy handles default to D/unknown.
Historical amendment rows lacking signed resolutions are not reconstructed.
D-262/D-263/D-264/D-265 still apply. No database schema bump.

## Existing implementation debts
D-254 recovery wiring; D-70/D-71 replanning; D-241 S3 resume; D-252 retrieval;
D-113/D-120 behaviour maps; D-124/D-244 review evidence; D-220/D-221 eval arms;
D-200 QA scheduling; D-66 S0 suite; D-92 impact checks. See TODO decision rows.

## Workspace
Preserve existing CLAUDE.md/ISSUES.md edits and untracked continue_fixing.md/todo_findings.txt.
No unfinished source changes intended. findings.md is the committed repair ledger.
