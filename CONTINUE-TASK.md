# Bugfix completion
Checkpoint: `feature/bugfix`, 2026-09-28; implementation `e5321f3`, pushed to origin.
**142 findings fixed; 0 open; F-034 previously resolved.**
The audit repair queue is complete. P0-P6 remains 185/185 implemented; P7 remains excluded.

## Final fix: F-016
Linux commands now run beneath an isolated kernel subreaper. Detached children cannot escape
ancestry by setsid/setpgid/double-fork. The helper terminates and reaps until waitpid ECHILD;
only then may LocalOs publish a confirmed terminal status. Errors/timeouts remain Lost.
Root-exit and quiescence signals are separate, preserving execution deadline classification.
The target has its own session and requested PATH. Closing the host control pipe requests cleanup.

## Verification
Linux JDK 26: 32 targeted tests passed, 1 Windows-only test skipped, 0 failures.
All 6 new lifecycle/PATH regressions passed. CI: https://github.com/korvin2000/ASTROLABE/actions/runs/36364095425
Windows JDK 26: 4 settlement tests passed; Linux-only tests skipped. Core ABI check passed.
No full suite/build or P7 run. Last full Windows build: fbee771, 1667 tests, 13 skipped.
Details: audit/BUGFIX-PROGRESS.md; machine-readable status: findings.md fix_progress.

## Compatibility and existing debts
No schema or public ABI change. Linux Proc.pid identifies the supervisor; exit codes are the command's.
One helper JVM per Linux launch; requires local java.home launcher/classpath and procfs. See D-319.
Unrelated implementation debts remain in TODO.md: D-254 recovery, D-241 S3 resume, D-252 retrieval,
D-113/D-120 behaviour maps, D-220/D-221 eval arms, D-200 QA scheduling, D-66 S0 suite, D-92 impact checks.
Preserve pre-existing CLAUDE.md/ISSUES.md and untracked continue_fixing.md/todo_findings.txt.
continue_fixing.md is an old checkpoint; it does not describe current audit status.
