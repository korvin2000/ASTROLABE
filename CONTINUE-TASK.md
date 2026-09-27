# Bugfix continuation
Checkpoint: `feature/bugfix`, 2026-09-28. Six fix commits through `fbee771`.
**88 findings fixed (6 in this continuation); 54 open; F-034 previously resolved.**
P0-P6 remains 185/185 implemented. P7 excluded. Owner reviewed the fixes and authorized commit and push.

## Read first
1. `findings.md` JSON `fix_progress`: exact fixed/open IDs and priorities.
2. `audit/BUGFIX-PROGRESS.md`: regressions, compatibility and verification.
3. Selected finding sections and cited code/tests only. Do not repeat the audit.

## Next fixes
1. F-016 POSIX detached-process containment; F-017 Git deadlines; F-019 search bounds.
2. F-027 raw membership under Git filters; F-031/F-032/F-033 snapshot recovery/fidelity.
3. F-036 Atlas/Sniff containment. For smaller work: F-122/F-127/F-142; F-110 is now fixed.
4. The complete remaining queue is `findings.md` JSON `fix_progress.remaining_open_ids`.

## Verification
Full Windows JDK 26 build after all six fixes passed: 1667 tests, 13 skipped, zero failures/errors; ABI/packaging passed.
F-110 also passed NotesTest RED/GREEN, StoreKbTest and CuratorTest. No new Linux/CI or P7 evidence.

## Compatibility and decisions
F-040/F-083/F-085/F-090/F-096/F-097/F-099/F-108/F-114/F-141 repaired.
D-277: npm test discovery preserves lifecycle hooks and environment.
D-278: applied STATE clears loop episodes; durable rebuild events and resident-position tails.
D-279/D-280: partial calibration censoring; local acceptance executables require review.
D-281/D-282: await propagates internal failures; fixture container failures veto green.
D-283: new unknown commands are W/unknown and require WorkspaceWrite, preventing replay.
D-284: two bounded validator gaps reach the next anchor; all remain in the packet/journal.
Old persisted intents classified replay-safe are not rewritten; audit before trusting legacy replay.
Fixture-report JSON adds `containerFailures`; fixture-test counts keep their meaning.
No database schema or core/eval public ABI change. D-261 through D-276 still apply.
Current fixes change calibration series keys and runner shaper versions; journal search uses SQLite JSON projection. See the repair ledger.

## Existing implementation debts
D-254 recovery wiring; D-70/D-71 replanning; D-241 S3 resume; D-252 retrieval;
D-113/D-120 behaviour maps; D-124/D-244 review evidence; D-220/D-221 eval arms;
D-200 QA scheduling; D-66 S0 suite; D-92 impact checks. See TODO decision rows.

## Workspace
Preserve existing CLAUDE.md/ISSUES.md edits and untracked continue_fixing.md/todo_findings.txt.
The six fixes are committed. `findings.md` is the repair ledger; continue_fixing.md contains an older checkpoint.
