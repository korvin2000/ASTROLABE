# Handoff ? bugfix continuation

Checkpoint: `feature/bugfix`, 2026-09-26. Owner requested fixes from `findings.md`.
**24 fixed this session; 118 open; F-034 was already resolved.** P0?P6 remains 185/185 implemented.
P7 remains out of scope. No remote push, PR or CI was performed in this session.

## Read first
1. `findings.md` JSON `fix_progress`: exact fixed/open IDs and next priorities.
2. `audit/BUGFIX-PROGRESS.md`: commits, verification, compatibility changes and remaining work.
3. Read only the selected finding sections and their cited code/tests.

## Next fixes
1. F-053 same-path edit clobber; F-055/F-059 diagnostic/run secret disclosure.
2. F-071 verifier command authorization; F-070/F-076?F-081/F-123 final-tree certification.
3. F-022/F-060/F-074/F-088/F-094 authority identity and currency, then recovery and remaining audit order.
Use focused regression tests per group; update findings and this handoff as fixes land.

## Verification
Focused tests passed for all fix groups; core ABI regenerated.
Full core run: 1,286 passed, 1 failed, 6 skipped; QA failure fixed, final focused 29 passed.
Final build -x :core:test passed (all other modules, packaging/ABI); full core suite not rerun.
No new Linux/CI evidence. Prior plan final gate: CI 36172349269 (before these fixes).

## Compatibility
`ReviewRecord` adds required-check veto evidence; `Receipt`/`Executed` add expectedExitCode. JVM consumers must rebuild.
`Historical.currentVersion` is nullable for deleted files. Generated-tool fingerprints now include capabilities.
D-261: additions to existing test files require review; new-file classification remains heuristic.

## Existing implementation debts
D-254 recovery wiring; D-70/D-71 replanning; D-241 S3 resume; D-252 retrieval;
D-113/D-120 behaviour maps; D-124/D-244 review evidence; D-220/D-221 eval arms;
D-200 QA scheduling; D-66 S0 full suite; D-92 impact checks. See TODO decision rows.

## Workspace
Preserve existing uncommitted user changes in CLAUDE.md and ISSUES.md and untracked todo_findings.txt.
findings.md was initially untracked and is now the committed repair ledger.
Do not repeat the historical audit; repair the remaining findings. No unfinished source edits intended.
