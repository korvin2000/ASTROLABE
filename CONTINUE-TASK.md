# Bugfix continuation
Checkpoint: `feature/bugfix`, 2026-09-28; source/ABI through `bc742fe`.
**141 findings fixed (10 this continuation); 1 open; F-034 previously resolved.**
P0-P6 remains 185/185 implemented. P7 excluded. Commit/push to origin/feature/bugfix authorized.

## Remaining issue
**F-016: POSIX detached-process containment.** Process groups cannot own descendants that call
setsid/setpgid. Windows jobs contain descendants. Ownership comments now state the limitation.
Implement an enforceable Linux lifecycle boundary and validate detached-child termination; do not
mark this fixed by documentation or descendant polling. Linux runtime evidence is still missing.

## This continuation
F-019/F-100: bounded search work, reads and process output; explicit Windows handle inheritance.
F-082/F-089: independent Check/review/test-integrity evidence reaches cell and final completion.
F-086/F-095/F-127: durable campaign funding, provider terminal settlement, extractor accounting.
F-104/F-105: live split replanning and host-backed blocked-work resume, preserving verified history.
F-135: durable publication manifests, rollback and recovery on reopen without worktrees.
Details and compatibility: audit/BUGFIX-PROGRESS.md and findings.md JSON fix_progress.

## Verification
32 distinct selected tests passed on Windows JDK 26 after documented corrections.
Core ABI regenerated/checked; eval and index-treesitter test sources compiled.
No full suite/build, Linux/CI or P7 run. Last full Windows build: fbee771, 1667 tests, 13 skipped.
Defaulted JSON additions; no schema bump. JVM consumers need rebuilding. D-312 through D-318 apply.
Provider cancellation waits for terminal reconciliation. Unknown usage retains conservative funding.
Legacy unbound assessments need reassessment; integration recovery refuses conflicting host edits.

## Existing debts and workspace
D-254 recovery wiring; D-241 S3 resume; D-252 retrieval; D-113/D-120 behaviour maps;
D-220/D-221 eval arms; D-200 QA scheduling; D-66 S0 suite; D-92 impact checks.
D-70/D-71 replanning and D-124/D-244 completion evidence now have the repairs documented above.
Preserve pre-existing CLAUDE.md/ISSUES.md and untracked continue_fixing.md/todo_findings.txt.
continue_fixing.md is an older checkpoint; findings.md is the current repair ledger.
