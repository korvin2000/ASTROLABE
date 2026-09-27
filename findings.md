# Implementation audit findings

**Remediation:** 64 fixed on `feature/bugfix` (10 in this continuation); 78 remain open; F-034 was already resolved. See [bugfix progress](audit/BUGFIX-PROGRESS.md). Original audit evidence remains historical; `fix_progress` is the current repair status.

```json
{
  "schema_version": 1,
  "audit_status": "scoped_review_complete",
  "baseline_commit": "ecc88c838aabab666e49b3281f31440fcb0c3a73",
  "started_on": "2026-09-24",
  "last_completed_task": "P6.3.1",
  "current_task": null,
  "next_task": null,
  "order": "TODO.md heading order; implemented tasks only",
  "source_changes_allowed": false,
  "report_language": "en",
  "task_count": 185,
  "implemented_task_count": 185,
  "verification": "This session: V-008/V-009/V-010, 239 passed, zero failures/errors/skips; V-011 reproduced five new findings. All 185 task rows inspected across sessions. All 29 queued historical changed-source findings rechecked on current commit (28 remain open, F-034 resolved). Historical reproductions remain tied to their recorded baseline. No full build, remote CI, Linux or P7 validation.",
  "reviewed_or_skimmed_tasks": 185,
  "pending_implemented_tasks": 0,
  "deferred_unimplemented_tasks": 0,
  "finding_count": 143,
  "finding_severity_counts": {
    "high": 110,
    "medium": 33
  },
  "finding_confidence_counts": {
    "reproduced": 42,
    "potential": 9,
    "confirmed_source": 92
  },
  "last_checkpoint": "2026-09-25 audit completed: 185/185 P0-P6 tasks reviewed or explicitly skimmed; P7 excluded. This continuation reviewed 47 tasks and added 20 findings; 143 recorded (142 open, F-034 resolved). Rechecked all 29 queued historical findings. 239 tests passed and 5 new runtime probes reproduced defects. No active processes or source/test changes.",
  "verification_runs": [
    {
      "id": "V-001",
      "core_tests": 19,
      "failures": 0,
      "errors": 0,
      "skips": 0,
      "selection": [
        "AttemptConfigTest",
        "budget.BudgetTest",
        "event.EventsTest",
        "store.DbTest"
      ],
      "provider_api": "15 results reused UP-TO-DATE"
    },
    {
      "id": "V-002",
      "core_tests": 143,
      "failures": 0,
      "errors": 0,
      "skips": 0,
      "selection": [
        "atlas.*",
        "evidence.*",
        "register.*",
        "workset.*",
        "tool.ToolContractsTest",
        "tool.PartitionTest",
        "tool.DispatcherTest",
        "tool.look.LookTest",
        "tool.edit.EditTest",
        "tool.edit.AnchorsTest"
      ],
      "session_id": 79923,
      "terminal": "BUILD SUCCESSFUL in 46s"
    },
    {
      "id": "V-003",
      "core_tests": 70,
      "failures": 0,
      "errors": 0,
      "skips": 0,
      "selection": [
        "tool.run.*",
        "tool.verify.VerifyTest",
        "tool.state.StateToolTest",
        "tool.task.TaskToolTest",
        "tool.kb.KbToolTest"
      ],
      "session_id": 74160,
      "terminal": "BUILD SUCCESSFUL in 31s"
    },
    {
      "id": "V-004",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "core_tests": 44,
      "failures": 0,
      "errors": 0,
      "skips": 0,
      "selection": [
        "verify.ChecksTest",
        "verify.CheckerTest",
        "verify.SchedulerTest",
        "verify.BaselineTest",
        "budget.CellBudgetTest",
        "verify.ExitGateTest",
        "verify.ScopeGuardTest",
        "verify.TestIntegrityTest"
      ],
      "session_id": 40576,
      "terminal": "Gradle exit 0; XML totals verified",
      "runtime_probes": [
        "F-076 cwd/argv",
        "F-077 same-stamp verifier",
        "F-078 exclusive membership",
        "F-079 export helper stamp gap",
        "F-081 mutated baseline ledger",
        "F-083 executable path helper"
      ],
      "limitations": "Probes ran via stdin JShell against compiled classes in isolated ignored build fixtures. Initial JAVA_HOME, JShell argument, fixture-classpath and constructor errors were corrected; failed probe attempts are not evidence."
    },
    {
      "id": "V-005",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "core_tests": 241,
      "failures": 0,
      "errors": 0,
      "skips": 0,
      "selection": [
        "cell.*",
        "campaign.*",
        "auth.*",
        "telemetry.*"
      ],
      "session_id": 66048,
      "terminal": "Gradle exit 0; selected XML totals verified",
      "runtime_probes": [
        "F-097 unknown executable classification",
        "F-098 overlapping default redaction"
      ],
      "limitations": "No source/test edits. A sampled worker stack was doing Git I/O; run subsequently completed successfully, no hang finding inferred."
    },
    {
      "id": "V-006",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "core_tests": 83,
      "failures": 0,
      "errors": 0,
      "skips": 1,
      "selection": [
        "graph.*",
        "context.*",
        "kb.NotesTest",
        "kb.StoreKbTest",
        "kb.NoteHorizonTest",
        "kb.CalibrationStatsTest",
        "java.JavaConsumptionSmokeTest",
        "os.ProcOwnershipTest"
      ],
      "session_id": 28286,
      "terminal": "Gradle exit 0; selected XML totals verified",
      "limitations": "POSIX ownership case skipped on Windows; no Linux validation."
    },
    {
      "id": "V-007",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "core_tests": 106,
      "failures": 0,
      "errors": 0,
      "skips": 0,
      "selection": [
        "verify.*",
        "atlas.ImportGraphTest",
        "atlas.ImpactTest",
        "atlas.ImpactAssemblyTest",
        "tool.edit.TransformTest",
        "tool.run.DiagnosticsTest"
      ],
      "session_id": 36402,
      "terminal": "Gradle exit 0; 20 selected class XML reports verified",
      "runtime_probes": [
        "F-116 empty complete root-package closure",
        "F-121 assertion reorder classified additions-only"
      ],
      "limitations": "Synthetic stdin-only probes; no source/test edits, full build, remote CI or P7 validation."
    },
    {
      "id": "V-008",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "core_tests": 135,
      "eval_tests": 49,
      "test_cases": 184,
      "failures": 0,
      "errors": 0,
      "skips": 0,
      "session_id": 17254,
      "terminal": "Gradle exit 0; 35 core + 10 eval XML class reports inspected",
      "selection": [
        "kb.*",
        "delegate.*",
        "route.*",
        "recover.*",
        "tool.run.MountTest",
        "tool.run.GeneratedToolTest",
        "eval:test"
      ],
      "limitations": "Focused Windows JDK 26 tests only; no full build, remote CI, Linux or P7 validation."
    },
    {
      "id": "V-009",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "core_tests": 25,
      "index_treesitter_tests": 17,
      "test_cases": 42,
      "failures": 0,
      "errors": 0,
      "skips": 0,
      "session_id": 45395,
      "terminal": "Gradle exit 0; 4 core + 4 index XML class reports inspected",
      "selection": [
        "workspace.WorktreesTest",
        "workspace.ScopeAlgebraTest",
        "campaign.S3CampaignTest",
        "verify.WatcherTest",
        "index-treesitter:test"
      ],
      "limitations": "Initial additional filters used nonexistent publication/measurement class names and therefore selected no such classes; corrected and executed separately in V-010."
    },
    {
      "id": "V-010",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "core_tests": 13,
      "test_cases": 13,
      "failures": 0,
      "errors": 0,
      "skips": 0,
      "session_id": 90396,
      "terminal": "Gradle exit 0; all 4 selected XML class reports inspected",
      "selection": [
        "auth.PermissionLadderTest",
        "campaign.PublicationTest",
        "campaign.PublicationCampaignTest",
        "verify.MeasurementGateTest"
      ]
    },
    {
      "id": "V-011",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "type": "stdin_runtime_probes",
      "findings": [
        "F-129",
        "F-131",
        "F-139",
        "F-140",
        "F-142"
      ],
      "successful_probes": 5,
      "limitations": "No new source/test files. F-142 initial empty Config was invalid; corrected probe used existing FakeProfiles. Only successful corrected outputs support findings."
    }
  ],
  "active_processes": [],
  "follow_up_checks": [],
  "current_review_commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
  "release_scope": "ASTROLABE 1.0.1; all 185 P0-P6 tasks implemented; P7 excluded",
  "prior_evidence_policy": "F-001-F-074 runtime evidence and V-001-V-003 retain original-baseline provenance. All 29 queued findings with changed source were rechecked by source in this session; per-finding notes record the result. Other historical findings retain earlier scoped provenance. Task coverage is scoped inspection, not defect absence or production certification.",
  "audit_plan": [
    {
      "step": 1,
      "status": "done",
      "scope": "Reconcile all 185 task headings/statuses/links; P7 remains excluded."
    },
    {
      "step": 2,
      "status": "done",
      "scope": "P1.7 verification chain: registry, checker, receipt currency, baseline, reserve, exit gate and integrity. Read tests/spec selectively, trace complex failure paths; skip trivial carriers."
    },
    {
      "step": 3,
      "status": "done",
      "scope": "P1.8-P1.12 runtime: cancellation, admission, residency, completion and controller persistence; recheck changed P0/P1 findings at their consumers."
    },
    {
      "step": 4,
      "status": "done",
      "scope": "P2-P3 continuity and deeper verification in TODO order: resume, ledger/contract currency, context compilation, closure/provenance and test integrity."
    },
    {
      "step": 5,
      "status": "done",
      "scope": "P4-P6 implemented complex paths: knowledge admission, delegation/routing/recovery, parallel publication, generated tools and evaluation integrity. P7 transports/backends/live gates excluded."
    },
    {
      "step": 6,
      "status": "done",
      "scope": "Revalidate remaining changed historical finding locations; reconcile coverage/counts and leave explicit next task. No source fixes."
    }
  ],
  "progress_percent": 100.0,
  "remaining_task_count": 0,
  "remaining_by_phase": {
    "P4": 0,
    "P5": 0,
    "P6": 0
  },
  "remaining_task_ids": [],
  "session_summary": {
    "tasks_reviewed_or_skimmed_this_session": 47,
    "findings_added_this_session": 20,
    "source_changes": false,
    "new_runtime_reproductions": 5,
    "test_runs": [
      "V-008",
      "V-009",
      "V-010",
      "V-011"
    ],
    "test_cases": 239,
    "passed": 239,
    "failed": 0,
    "errors": 0,
    "skipped": 0,
    "historical_findings_rechecked": 29,
    "historical_findings_resolved": 1
  },
  "historical_revalidation_queue": [],
  "completed_follow_ups": [
    {
      "task": "P2.2.4",
      "result": "Inspected resume/preimage/shadow consumers; F-031/F-033/F-061 remain relevant, new F-105."
    },
    {
      "task": "P2.6.2",
      "result": "Inspected real KB/STATUS render paths; F-111/F-112 record missing redaction and budget enforcement."
    },
    {
      "task": "P5.1.3",
      "result": "Traced S3 worktree verification/publication and final isolated verification: S3 TreeVerification uses an exclusive scheduler on its worktree, so F-079/F-080 are not directly its export path. Their ordinary isolated final-verification path remains unchanged; independent S3 certification/publication defects recorded in F-133-F-135."
    }
  ],
  "historical_revalidation_completed": [
    {
      "finding": "F-001",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "AttemptConfig.freeze still retains Config/maps and Controller.open uses frozen.config (455); no defensive snapshot added."
    },
    {
      "finding": "F-002",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "AttemptConfig.fingerprint still hashes ordinary encodeDefaults JSON without canonical map/set ordering."
    },
    {
      "finding": "F-012",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "BlobStore.publish still performs one FileChannel.write before force/move (239). Short-write risk remains potential; no failure reproduced."
    },
    {
      "finding": "F-013",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "BlobStore.gc still eagerly enumerates files and all DB digests; young temp entries consume the sole orphan budget (167?224)."
    },
    {
      "finding": "F-017",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Git.exec still blocks on readAllBytes, waitFor and unbounded joins without a deadline (389?427)."
    },
    {
      "finding": "F-033",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Controller creates Preimages per cell (1243); saved remains memory-only; Cell.journalPreimages (625) records mappings only after tool completion (395)."
    },
    {
      "finding": "F-034",
      "status": "resolved_on_recheck",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Controller now passes shadowRef = tree.shadow to Edit (1274?1278), and Edit preflight accepts turn:N when a snapshot exists (287?297). The missing fallback wiring is resolved. Create/delete/rename selective inverses remain the previously documented limitation, not a new unresolved defect under this finding."
    },
    {
      "finding": "F-044",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Both intent journals still enforce ordinal transitions, while Controller marks unresolved intents Unknown (501); reconciliation cannot transition Unknown to Committed."
    },
    {
      "finding": "F-047",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Workset.recall still registers Known for null currentVersion (136); Look applies the historical label only after coverage registration."
    },
    {
      "finding": "F-048",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "ToolCall.condition still exposes only first non-null edit condition; Partition and Dispatcher consume that single condition. Edit.run validates operations/scopes but does not enforce per-op conditions/masks."
    },
    {
      "finding": "F-049",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Dispatcher still runs read calls with async (140); Workset live/stale/drops remain unsynchronized ArrayLists shared with Look."
    },
    {
      "finding": "F-050",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Look.recall derives blob origin from observation.ranges and reuses the original contentRef on the narrowed recalled observation (318?395)."
    },
    {
      "finding": "F-051",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Look.read persists only hidden.intersect(displayed) as redaction mask (210); recall grants new coverage from that truncated mask."
    },
    {
      "finding": "F-052",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Look.fit (507) retains an over-budget first line; whole-file refusal/outline rendering remains outside that budget bound."
    },
    {
      "finding": "F-053",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Edit preflights all operations before mutation (247), applies each complete plan.oldText (379) and revalidate checks path identity only (522), so same-path snapshot clobber remains."
    },
    {
      "finding": "F-054",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Anchors.normalized still trims normalized anchor and maps only retained characters (69); Edit.replace inserts complete replacement text (528)."
    },
    {
      "finding": "F-055",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Anchors.nearest returns raw lines; Edit.current builds raw stale diffs; Edit.render joins error.detail and persists body without whole-body redaction (558?600)."
    },
    {
      "finding": "F-057",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Edit records AppliedOp after publication/postimage/registry work; rename writes target before deleting source. ioError still infers effects from the incomplete applied list (515)."
    },
    {
      "finding": "F-058",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Run alias names actionId (186), Edit alias names editId; render stores fresh obs IDs (Run 493, Edit 591), while Look.recall resolves canonicalId directly."
    },
    {
      "finding": "F-059",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Run.finish and terminal poll shape raw capture; render concatenates result.view unchanged and hard-codes redactionApplied=false (354?511)."
    },
    {
      "finding": "F-060",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Run.authorize now shares logic with mounts but still checks only approval.approved, with no request/revision/current-authority validation after await (240?257)."
    },
    {
      "finding": "F-061",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Run Consequential.persist still stores only a LOG blob before intent completion; handles.save and observations.record execute afterward (200?233)."
    },
    {
      "finding": "F-062",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Run.poll/cancel still use project-global handles.get without checking work/attempt/workspace ownership (403,460)."
    },
    {
      "finding": "F-063",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Terminal poll still reconstructs change provenance from current members and registry.recorded; reports R/W instead of retaining launch D/unknown classification (424?454)."
    },
    {
      "finding": "F-064",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Run.launch still blocks in a polling loop with ByteArrayOutputStream and no cancellation check; only process deadline/exit ends it (333?346)."
    },
    {
      "finding": "F-065",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "Run.launch still performs one terminal tail poll (343); Executions.observe/LocalOs bounded chunks retain the earlier incomplete-drain risk."
    },
    {
      "finding": "F-071",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "PlanIntake still strengthens autonomous model acceptance; Verify.runOne directly starts command.argv with root.resolve(cwd), no Run authority/classification path (327?356)."
    },
    {
      "finding": "F-073",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "StateTool schema-invalid branch returns before setting lastRejection (116); Cell still feeds that field to the patch rejection gate (455)."
    },
    {
      "finding": "F-074",
      "status": "open",
      "verification": "current_source",
      "commit": "9a80e117445be357285fec2cffffe0fd45290a9a",
      "result": "TaskTool.ask still compares answer to pre-await contract.version only and omits questionId check (115?130)."
    }
  ],
  "finding_status_counts": {
    "open": 142,
    "resolved_on_recheck": 1
  },
  "fix_progress": {
    "branch": "feature/bugfix",
    "date": "2026-09-27",
    "status": "session_complete_remaining_findings_open",
    "fixed_this_session": [
      "F-004",
      "F-006",
      "F-010",
      "F-014",
      "F-018",
      "F-020",
      "F-021",
      "F-024",
      "F-025",
      "F-026"
    ],
    "fixed_this_session_count": 10,
    "previously_resolved": [
      "F-034"
    ],
    "remaining_open_count": 78,
    "remaining_open_ids": [
      "F-013",
      "F-015",
      "F-016",
      "F-017",
      "F-019",
      "F-027",
      "F-028",
      "F-029",
      "F-030",
      "F-031",
      "F-032",
      "F-033",
      "F-036",
      "F-037",
      "F-038",
      "F-039",
      "F-040",
      "F-041",
      "F-042",
      "F-044",
      "F-045",
      "F-046",
      "F-048",
      "F-052",
      "F-069",
      "F-075",
      "F-082",
      "F-083",
      "F-084",
      "F-085",
      "F-086",
      "F-087",
      "F-089",
      "F-090",
      "F-091",
      "F-092",
      "F-093",
      "F-095",
      "F-096",
      "F-097",
      "F-099",
      "F-100",
      "F-101",
      "F-102",
      "F-103",
      "F-104",
      "F-105",
      "F-106",
      "F-107",
      "F-108",
      "F-109",
      "F-110",
      "F-111",
      "F-112",
      "F-113",
      "F-114",
      "F-115",
      "F-117",
      "F-118",
      "F-119",
      "F-120",
      "F-122",
      "F-124",
      "F-125",
      "F-126",
      "F-127",
      "F-128",
      "F-129",
      "F-132",
      "F-133",
      "F-134",
      "F-135",
      "F-136",
      "F-137",
      "F-138",
      "F-141",
      "F-142",
      "F-143"
    ],
    "next_priority": [
      "F-013",
      "F-015",
      "F-016",
      "F-017",
      "F-019",
      "F-027",
      "F-028",
      "F-029",
      "F-030"
    ],
    "handoff": "CONTINUE-TASK.md",
    "detail": "audit/BUGFIX-PROGRESS.md",
    "verification": "Full Windows JDK 26 build passed on eccb010: core 1394 tests, 1386 passed, 8 skipped; provider-api 19, eval 49, index-treesitter 17 passed. Total 1471 passed, 8 skipped, zero failures/errors. Compilation, packaging and ABI passed. No new Linux/CI or P7 evidence.",
    "source_changes_allowed": true,
    "verification_results": {
      "full_build_command": "./gradlew.bat build -q --console=plain",
      "full_build_exit": 0,
      "log": "build/bugfix-fifth-build.log",
      "abi": "core dump regenerated",
      "linux_ci": "not run",
      "p7": "excluded",
      "modules": {
        "core": {
          "tests": 1394,
          "failures": 0,
          "errors": 0,
          "skipped": 8,
          "passed": 1386
        },
        "provider-api": {
          "tests": 19,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 19
        },
        "eval": {
          "tests": 49,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 49
        },
        "index-treesitter": {
          "tests": 17,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 17
        }
      },
      "total_passed": 1471,
      "total_skipped": 8,
      "review_corrections": [
        "Cell admission must call estimator.estimate(request) to honor provider-specific overrides; regression reproduced before correction."
      ],
      "platform_limits": [
        "New POSIX directory-permission regression skipped on Windows; Windows junction regressions ran."
      ]
    },
    "active_processes": [],
    "previous_session_fixed": [
      "F-001",
      "F-002",
      "F-003",
      "F-005",
      "F-007",
      "F-008",
      "F-009",
      "F-011",
      "F-012",
      "F-022",
      "F-023",
      "F-035",
      "F-043",
      "F-047",
      "F-049",
      "F-050",
      "F-051",
      "F-053",
      "F-054",
      "F-055",
      "F-056",
      "F-057",
      "F-058",
      "F-059",
      "F-060",
      "F-061",
      "F-062",
      "F-063",
      "F-064",
      "F-065",
      "F-066",
      "F-067",
      "F-068",
      "F-070",
      "F-071",
      "F-072",
      "F-073",
      "F-074",
      "F-076",
      "F-077",
      "F-078",
      "F-079",
      "F-080",
      "F-081",
      "F-088",
      "F-094",
      "F-098",
      "F-116",
      "F-121",
      "F-123",
      "F-130",
      "F-131",
      "F-139",
      "F-140"
    ],
    "fixed_on_branch": [
      "F-001",
      "F-002",
      "F-003",
      "F-004",
      "F-005",
      "F-006",
      "F-007",
      "F-008",
      "F-009",
      "F-010",
      "F-011",
      "F-012",
      "F-014",
      "F-018",
      "F-020",
      "F-021",
      "F-022",
      "F-023",
      "F-024",
      "F-025",
      "F-026",
      "F-035",
      "F-043",
      "F-047",
      "F-049",
      "F-050",
      "F-051",
      "F-053",
      "F-054",
      "F-055",
      "F-056",
      "F-057",
      "F-058",
      "F-059",
      "F-060",
      "F-061",
      "F-062",
      "F-063",
      "F-064",
      "F-065",
      "F-066",
      "F-067",
      "F-068",
      "F-070",
      "F-071",
      "F-072",
      "F-073",
      "F-074",
      "F-076",
      "F-077",
      "F-078",
      "F-079",
      "F-080",
      "F-081",
      "F-088",
      "F-094",
      "F-098",
      "F-116",
      "F-121",
      "F-123",
      "F-130",
      "F-131",
      "F-139",
      "F-140"
    ],
    "fixed_on_branch_count": 64,
    "previous_session_verification_results": {
      "initial_full_core": {
        "tests": 1293,
        "passed": 1286,
        "failures": 1,
        "errors": 0,
        "skipped": 6,
        "failed_test": "QaDriverTest HTTP status stored as process exit; fixed in 3f433f6"
      },
      "final_focused_core": {
        "tests": 29,
        "failures": 0,
        "errors": 0,
        "skipped": 0
      },
      "final_build_command": "./gradlew.bat build -x :core:test -q --console=plain",
      "final_build_exit": 0,
      "other_modules": {
        "provider-api": {
          "tests": 17,
          "failures": 0,
          "errors": 0,
          "skipped": 0
        },
        "eval": {
          "tests": 49,
          "failures": 0,
          "errors": 0,
          "skipped": 0
        },
        "index-treesitter": {
          "tests": 17,
          "failures": 0,
          "errors": 0,
          "skipped": 0
        }
      },
      "full_core_rerun": false
    },
    "source_checkpoint": "eccb010",
    "previous_continuation_verification_results": {
      "first_continuation_full_core": {
        "tests": 1314,
        "passed": 1305,
        "failures": 2,
        "errors": 0,
        "skipped": 7,
        "corrections": "CoherenceTest policy marker now shaper/future; PrecompileCampaignTest writes its artifact under declared build scratch. Both classes passed focused rerun."
      },
      "full_build_command": "./gradlew.bat build -q --console=plain",
      "full_build_exit": 0,
      "modules": {
        "core": {
          "tests": 1314,
          "failures": 0,
          "errors": 0,
          "skipped": 7,
          "passed": 1307
        },
        "provider-api": {
          "tests": 17,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 17
        },
        "eval": {
          "tests": 49,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 49
        },
        "index-treesitter": {
          "tests": 17,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 17
        }
      },
      "full_core_rerun": true,
      "log": "build/bugfix-continuation-build-verified.log",
      "linux_or_remote_ci": false
    },
    "previous_third_verification_results": {
      "first_full_core": {
        "tests": 1353,
        "passed": 1339,
        "failures": 7,
        "errors": 0,
        "skipped": 7,
        "correction": "The initial guard also blocked plain late completion. Preserve archival completion from existing evidence while blocking all new effects/checks; the seven existing lifecycle tests remain unchanged."
      },
      "full_build_command": "./gradlew.bat build -q --console=plain",
      "full_build_exit": 0,
      "modules": {
        "core": {
          "tests": 1353,
          "failures": 0,
          "errors": 0,
          "skipped": 7,
          "passed": 1346
        },
        "provider-api": {
          "tests": 17,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 17
        },
        "eval": {
          "tests": 49,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 49
        },
        "index-treesitter": {
          "tests": 17,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 17
        }
      },
      "full_core_rerun": true,
      "log": "build/bugfix-third-build-verified.log",
      "linux_or_remote_ci": false,
      "focused": "New repro batches failed before correction. Final focused checks passed after three stale observation/crash assertions were updated to verify the new alias/partial-effect behavior. Review corrections added whitespace, dispatch and selective-revert coverage."
    },
    "previous_fourth_verification_results": {
      "full_build_command": "./gradlew.bat build -q --console=plain",
      "full_build_exit": 0,
      "log": "build/bugfix-fourth-build.log",
      "abi": "core dump regenerated",
      "linux_ci": "not run",
      "p7": "excluded",
      "modules": {
        "core": {
          "tests": 1377,
          "failures": 0,
          "errors": 0,
          "skipped": 7,
          "passed": 1370
        },
        "provider-api": {
          "tests": 17,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 17
        },
        "eval": {
          "tests": 49,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 49
        },
        "index-treesitter": {
          "tests": 17,
          "failures": 0,
          "errors": 0,
          "skipped": 0,
          "passed": 17
        }
      },
      "total_passed": 1453,
      "total_skipped": 7,
      "focused_final": {
        "tests": 48,
        "failures": 0,
        "errors": 0,
        "skipped": 0
      },
      "review_corrections": [
        "continuous-output interruption",
        "interrupted-I/O termination",
        "clean HEAD background changes",
        "default role snapshot and normalized comparison"
      ]
    }
  }
}
```

## Revised audit plan (2026-09-25)

The machine-readable `audit_plan` above replaces the stale 133-implemented/52-deferred assumption. All P0-P6 tasks are implemented and eligible for review; only [P7](TODO.md#p7-deferred-out-of-scope-boundary) is excluded. Preserve historical findings without claiming they were reproduced on 1.0.1. Use bounded reads of complex control flow, tests and cited contracts; record trivial exclusions and reuse evidence where source is unchanged. Audit only: modify this report, not production code or tests.

## Resume protocol

1. Read the metadata, coverage ledger, and last checkpoint below. Compare the baseline with the current tree before reusing conclusions.
2. Continue in TODO heading order from `next_task`. Review complex implementation and its tests/spec; skim trivial records and record the exclusion. Never infer completion from historical progress summaries.
3. Modify only this report. Do not repair production code, tests, configuration, or other documentation.
4. After each reviewed task/group update its coverage row, findings and checkpoint. Preserve stable finding IDs. A reviewed task means scoped inspection, not proof of defect absence.
5. Distinguish confirmed source defects, potential risks, measured failures and intentional/deferred limitations. Include trigger, impact, evidence, alternatives and a future regression check.
6. Follow-up references to later tasks do not mark those tasks reviewed. Recheck cross-component consequences when reaching them.

## Status and severity

Coverage: `pending`, `in_progress`, `reviewed`, `skimmed_trivial`, `deferred_unimplemented`. Findings: `open`, `resolved_on_recheck`, `dismissed_with_evidence`. Severity: `critical` (loss/security boundary failure), `high` (wrong results or broken required behavior), `medium` (bounded correctness/performance/reliability problem), `low` (material documentation or maintenance issue). Confidence: `confirmed_source`, `reproduced`, `potential`. No network/CI validation is implied by this report.

## Coverage ledger (TODO order)

| Task / plan link | Implementation status | Review status | Inspected classes/files and scope | Findings |
|---|---|---|---|---|
| [P0.1.1](TODO.md#L532) [C] Gradle multi-module skeleton | DONE | skimmed_trivial | settings.gradle.kts; build-logic convention; wrapper properties: module boundaries, JVM target, ABI and checksum configuration. No build executed. | - |
| [P0.1.2](TODO.md#L540) [V] CI matrix | DONE | skimmed_trivial | .github/workflows/ci.yml: OS matrix, toolchain/fixture dependencies, report publishing. Historical CI results not revalidated. | - |
| [P0.1.3](TODO.md#L548) [C] `Config` and `Defaults` | DONE | reviewed | Config, Defaults, AttemptConfig; DefaultsTest, AttemptConfigTest; Controller.open call site. Numeric field declarations skimmed. | F-001, F-002 |
| [P0.2.1](TODO.md#L556) [C] `id` package | DONE | reviewed | Digest, FileVersion, Stamp, CanonicalEncoding; identifier wrappers and serializer declarations skimmed. Raw-byte hash and timestamp exclusion inspected. | - |
| [P0.2.2](TODO.md#L562) [C][M] `budget` package | DONE | reviewed | Tokens, Budget, Reserve, Reservations, HeuristicEstimator; BudgetTest. Lock/hold/reconcile and reserve arithmetic inspected; CellBudget belongs to P1.7.6 and is pending. | - |
| [P0.3.1](TODO.md#L570) [C] Item model | DONE | reviewed | Item hierarchy, Items.pairs; ItemsTest. Serialization records skimmed; pairing algorithm inspected. | F-003 |
| [P0.3.2](TODO.md#L577) [C] Request/Response and tool schema types | DONE | reviewed | Request, Response, Estimate, Request.estimate, Item.estimate; RequestTest. Admission accounting and continuation size handling inspected. | F-004, F-005 |
| [P0.3.3](TODO.md#L582) [C] Capabilities, profile, usage, money | DONE | reviewed | Capabilities, Profile, PriceTable, BillableUsage, Money, UsageNormalizer. Pricing and unknown propagation inspected; live normalizers intentionally deferred. | - |
| [P0.3.4](TODO.md#L588) [C] `ProviderAdapter` contract and errors | DONE | reviewed | Validations.standard, Invocation/Terminal contract, ProviderAdapters Java bridge. Adapter-specific validation remains P7. | F-003, F-005 |
| [P0.3.5](TODO.md#L594) [M] Fake adapter + scripted model (test fixtures) | DONE | reviewed | FakeAdapter/FakeInvocation, FakeCachePolicy, FakeTokenizer, ScriptedModel; FakeAdapterTest cancellation paths. Cache simulation is intentionally synthetic. | F-006 |
| [P0.4.1](TODO.md#L602) [C] `AgentEvent` model and `Events` bus | DONE | reviewed | Events, Subscriber, records; EventsTest. AgentEvent payload declarations skimmed. | F-007, F-008 |
| [P0.4.2](TODO.md#L608) [C] `Authority` (inbound host hooks) and `Mode` | DONE | reviewed | Authority, AutonomousAuthority, Replies, Authorities Java bridge; JavaAuthority. Reply identity checks in consumers remain for later task review. | F-009 |
| [P0.4.3](TODO.md#L614) [C] `Views` (read projections) and exports | DONE | reviewed | Views, Export and projection records: query scope, ordering, export composition and register reads. | F-010 |
| [P0.5.1](TODO.md#L622) [C][M] External project state root, `ProjectLock`, `Db`, `BlobStore` | DONE | reviewed | Store, Layout, ProjectLock, RepoIdentity, Db/Tx, BlobStore; DbTest and BlobStoreTest recovery cases. Windows ACL and power-loss limits are explicitly documented. | F-011, F-012, F-013, F-014 |
| [P0.5.2](TODO.md#L629) [C] Schema v1 | DONE | reviewed | Migrations v1-v4: transaction/version guard, table identities, foreign keys and indexes. Later component writes remain pending. | - |
| [P0.6.1](TODO.md#L635) [C][M] `Os` and `Proc` | DONE | reviewed | LocalOs supervision, sidecars, cursor/poll, atomic writes; ProcessOwner, PosixOwner/WindowsOwner lifecycle portions; Proc/Os contracts. Native ABI tables and quoting helpers not deeply re-audited; Linux execution unavailable. | F-015, F-016 |
| [P0.6.2](TODO.md#L647) [C][M] `Git` CLI wrapper | DONE | reviewed | Git CLI reads/writes, temp-index protection, environment, process I/O, root identity and parsing. Simple GitTypes records skimmed. | F-017 |
| [P0.6.3](TODO.md#L655) [C][M] `Search` | DONE | reviewed | PatternSubset, Candidates, HitCollector, JvmSearch, RipgrepSearch; SearchBackendParityTest. Unicode parity probed on JDK 26 and local rg. | F-018, F-019, F-020, F-021 |
| [P0.6.4](TODO.md#L660) [M] Test kit | DONE | reviewed | TempRepo process runner, Runners timeout/probing, ScriptedModel; trivial TestKit/clock/ID fixture declarations skimmed. Fixture project suites not rerun. | F-017 |
| [P1.1.1](TODO.md#L676) [C][M] Contract records and store | DONE | reviewed | Contract/Acceptance/Scope validation and strengthening; Contracts, InMemoryContractRepository, SqliteContractRepository; ContractsTest. Trivial records skimmed; P2 graph extension deferred to its task. | F-010 (same full-history-read pattern in Contracts.current) |
| [P1.1.2](TODO.md#L685) [M] S0 auto-derivation | DONE | reviewed | Contracts.deriveS0: Sniff mapping, package cwd, origin, scope and budget derivation; DeriveS0Test. Sniff internals belong to P1.3.4. | - |
| [P1.1.3](TODO.md#L692) [M] Amendments channel | DONE | reviewed | Contracts.propose/amendByUser/resolve/resolved; SqliteContractRepository.recordResolved/projection; ContractsTest. Plan consumer identified for later P2.1.2 review. | F-022, F-023 |
| [P1.1.4](TODO.md#L699) [M] Contract digest and contract slice | DONE | reviewed | ContractSlice factory/render/coverage and ContractDigest reduction loop. Complete definitions include cwd; capacity and independent coverage checked. | F-024, F-025 |
| [P1.2.1](TODO.md#L707) [C][M] `Workspace` and `VersionRegistry` | DONE | reviewed | Workspace, VersionRegistry: hashing, CAS, coverage namespacing, change notification and race/error paths; VersionRegistryTest. Ranges implementation reviewed with Workset at P1.5.3. | F-026 |
| [P1.2.2](TODO.md#L716) [M] `Stamper` | DONE | reviewed | Stamper.report/stamp/diff/entry encoding, EnvFingerprint; StamperTest scenarios. Raw-filter equivalence reproduced; caller environment resolution awaits controller review. | F-027, F-028, F-029 |
| [P1.2.3](TODO.md#L723) [M] Dirty-state record | DONE | reviewed | Snapshot, DirtyState capture/staged/raw/mode/separation paths; DirtyStateTest. Snapshot and stamp capture consistency inspected. | F-027, F-028, F-029, F-030 |
| [P1.2.4](TODO.md#L729) [M] `ShadowRef` snapshots and `revert:turn:N` | DONE | reviewed | ShadowRef snapshot commit ordering, restore guards/publication, materialize/validation and index; ShadowRefTest. Cross-read Baseline.materialize consumer. | F-028, F-031, F-032 |
| [P1.2.5](TODO.md#L736) [M] `Preimages` and `revert:#id` | DONE | reviewed | Preimages save/write/postimage/revert; PreimagesTest; Controller construction, Edit create/delete/rename/revert, Cell preimage journal call sites. | F-033, F-034 |
| [P1.2.6](TODO.md#L741) [C][M] `WorkspacePath` contract | DONE | reviewed | WorkspacePath lexical/canonical/containment/protection/link/revalidation and case probe; WorkspacePathTest. Invalid Windows filename reproduced. External-writer CAS limitation is intentional. | F-035 |
| [P1.3.1](TODO.md#L750) [M] `Atlas` | DONE | reviewed | Atlas scan/build/cache/refresh, ImportResolver, Focus; AtlasTest/Focus rendering tests. Runtime cache and collapsed refresh probes. | F-036, F-037, F-038 |
| [P1.3.2](TODO.md#L757) [M] `Prime` (`[R]` content) | DONE | reviewed | Prime tree/command/rule/index rendering, RulesSnapshot seam and PrimeTest; approved rules binding is reviewed separately at P1.10.1. | F-036 (upstream filesystem input) |
| [P1.3.3](TODO.md#L763) [M] `Outline` and `SymbolIndex` tier 0 | DONE | reviewed | Outline language dispatch, BlockScanner, Python/curly extraction and SymbolIndex.def/refs/importers; OutlineTest and tier flags. Approximate spans/import resolution are explicitly tier 0. | F-039 |
| [P1.3.4](TODO.md#L769) [M] `Sniff` commands | DONE | reviewed | Sniff manifest parsers, package command selection, wrapper detection; SniffTest; TrustedLocalRunner and native argv launch path cross-read. | F-040, F-041 |
| [P1.4.1](TODO.md#L775) [M] `Journal` | DONE | reviewed | Journal append/sequence/search/scope, StoredEvidenceTest and append-only SQL; no FTS index implemented (explicitly documented). | F-042 |
| [P1.4.2](TODO.md#L782) [C][M] `Receipt`, `Observation`, `Claim` | DONE | reviewed | Receipt, Counts, TestedInputs, Observation/RedactionMask, Claim and immutable SQL stores; EvidenceTest and record constraints. ClosureManifest remains P3.1.1. | F-043 |
| [P1.4.3](TODO.md#L788) [M] `Intent` journal and consequential-action ordering | DONE | reviewed | Intent, Consequential, both intent journals, Alias allocation; EvidenceTest/StoredEvidenceTest fault paths and Controller open/Run unknown-effect consumers. | F-044 |
| [P1.4.4](TODO.md#L794) [M] `Coherence` (turn, cell, verification horizons) | DONE | reviewed | Coherence serve/fan-out/scheduling, CoherenceTest and registry callback contract. Actual horizon wiring cross-read; no-throw promise alone does not provide recovery. | F-026 (confirmed fan-out limitation), F-029 (upstream missing/read-error distinction) |
| [P1.5.1](TODO.md#L802) [C][M] `Register` model and Markdown render | DONE | reviewed | Register/Fact stale marking, trips, RegisterRender and RegisterVersions; RegisterTest/declared invariants. Simple carrier records skimmed; current STATE durability checked again under P1.6.8. | - |
| [P1.5.2](TODO.md#L809) [M] Typed ops, `Patch`, `Validator`, conditional ops | DONE | reviewed | Validator, Op/Patch/Condition, ValidationContext; ValidatorTest and StateTool/Cell validation consumers. Limits, conditional filtering, atomic pure validation and evidence existence checked. | F-045, F-046 |
| [P1.5.3](TODO.md#L815) [M] `Workset` | DONE | reviewed | Workset and WorksetView register/stale/stub/recall/export/seed/render, WorksetTest; Ranges merge/subtract/intersect reviewed (P1.2.1 carry-forward). Look recall integration inspected. | F-047 |
| [P1.6.1](TODO.md#L823) [C] Tool contracts, schemas, `Envelope`, `Gauge` | DONE | reviewed | ToolFamily/ToolOps, Args validation, ToolCalls parsing, ToolSchemas, Envelope/Gauge, ToolContractsTest. Native schema mappings remain P7. | F-003 (duplicate IDs), F-048 |
| [P1.6.2](TODO.md#L830) [M] Turn partition and `Dispatcher` | DONE | reviewed | Partition dependency ordering, Dispatcher admission/parallel reads/edit/execute phases, PartitionTest/DispatcherTest; actual Cell dispatch use checked. | F-048, F-049 |
| [P1.6.3](TODO.md#L836) [M] `look` (tree, outline, read, find, recall, catalog, def) | DONE | reviewed | Look read/find/recall/structural results, coverage, redaction and budget paths; LookTest. Repeated recall and truncated-mask bugs reproduced with existing fixture. | F-047, F-050, F-051, F-052 |
| [P1.6.4](TODO.md#L843) [M] `edit` anchored CAS batch | DONE | reviewed | Edit full preflight/apply/render, Anchors normalization/uniqueness, LineDiff, CliSyntax; EditTest/AnchorsTest, controller wiring. Batch clobber, normalized span and raw error disclosure probed. | F-032, F-033, F-034, F-048, F-053, F-054, F-055, F-056, F-057, F-058 |
| [P1.6.5](TODO.md#L851) [M] `run`, `Runner`, effect classes, handles | DONE | reviewed | Run launch/finish/poll/cancel/render, Runner, Handles, Executions and RunTest; actual synthetic-secret run and alias lookup probed. Capability policy internals remain P1.10.2. | F-041, F-044, F-058, F-059, F-060, F-061, F-062, F-063, F-064, F-065 |
| [P1.6.6](TODO.md#L859) [M] Shaping parsers (`Shaper`) | DONE | reviewed | Shapers registry/status/view, Pytest terminal/JSON, Jest terminal/JSON, JUnit StAX, GenericSummaries, TestIdentity; recorded-output tests inspected. Diagnostics-specific additions remain P3.1.4. | F-066, F-067, F-068, F-069 |
| [P1.6.7](TODO.md#L865) [M] `verify` op | DONE | reviewed | Verify selection, checker/baseline/review dispatch, runAll/runOne/outcome and VerifyTest; Scheduler currencies and model-origin PlanIntake cross-referenced, deeper verification remains P1.7/P3.1. | F-059 (same raw shaped view path), F-064, F-065, F-070, F-071 |
| [P1.6.8](TODO.md#L873) [M] `state` op | DONE | reviewed | StateTool, PatchParser, RegisterVersions and StateToolTest. Injected save failure reproduced against the actual StateTool; schema/gate state inspected. | F-072, F-073 |
| [P1.6.9](TODO.md#L878) [M] `task.ask` | DONE | reviewed | TaskTool.ask/propose boundary, Asked pinning and TaskToolTest. Wrong-question/stale answer during a contract update reproduced; full proposal intake remains P2.1.5. | F-074 |
| [P1.6.10](TODO.md#L885) [C] `kb` op contract | DONE | reviewed | Kb interface/EmptyKb, KbTool search/get/skill masks and KbToolTest. Empty/scoped completeness and stale labels checked; persisted notes/read-budget sizing reviewed later at P2.6. | - |
| [P1.7.1](TODO.md#L891) [C][M] `Check` and `Checks` registry (S0 set) | DONE | reviewed | Checks seed/replace/refresh and definition identity; registry tests and Sniff -> Controller -> Verify -> JUnitXmlShaper integration. Carrier enums/records skimmed. | F-075; F-041 (historical wrapper issue) |
| [P1.7.2](TODO.md#L898) [M] End-of-turn `Checker` (synchronous, time-boxed) | DONE | reviewed | Checker dispatch/time box, cwd/argv, capture, mutation notification and receipt handoff; CheckerTest. Package-relative argv probe. | F-076; F-064/F-065 (same capture pattern, source cross-check) |
| [P1.7.3](TODO.md#L904) [M] `ChecksRender` (Δ + absolute) | DONE | skimmed_trivial | ChecksRender and CheckerResult.line: delta/absolute/status rendering; routine formatting skimmed. Runtime stale-display integration remains P1.8.3. | - |
| [P1.7.4](TODO.md#L910) [M] Receipt emission and currency | DONE | reviewed | Scheduler exclusive/isolated execution, export, input membership, receipts and currency; Applicability; SchedulerTest. Runtime membership/verifier/export probes. | F-077, F-078, F-079, F-080; F-029/F-043 (historical); F-070 rechecked |
| [P1.7.5](TODO.md#L916) [M] `Baseline` receipt and pre-existing-failure ledger | DONE | reviewed | Baseline materialization, capture, rescan, report discovery and pre-existing ledger; BaselineTest. Mutating-suite ledger probe. | F-081; F-065 (shared capture loop), F-069 (upstream identity) |
| [P1.7.6](TODO.md#L923) [M] `Reserve` enforcement | DONE | reviewed | CellBudget partition admission/reconcile/turn reserves; CellBudgetTest and Cell spend call sites. Concurrent pool reservations inspected; broader runtime admission remains P1.8.7. | - |
| [P1.7.7](TODO.md#L930) [M] `ExitGate` and `Verifier.accept` | DONE | reviewed | ExitGate/Verifier obligation checks, candidate/version binding, ledger writes and refusal count; ExitGateTest; Assessment producer/consumer search and Cell gate construction. | F-082 |
| [P1.7.8](TODO.md#L938) [M] Baseline `ScopeGuard` and conservative acceptance-surface policy (S0) | DONE | reviewed | ScopeGuard batch/path/committed-scope checks; TestIntegrity baseline surface detection, required-check association and review binding; existing tests. Executable-path probe. Precise diff classifier deferred to P3.4.2. | F-083; F-056 (historical caller bypass) |
| [P1.8.1](TODO.md#L947) [C] `Role` configuration | DONE | reviewed | Role.effectiveOps, shape masks, configuration restrictions and actual controller executor wiring; declarative role tables skimmed. | F-084 |
| [P1.8.2](TODO.md#L954) [M] `Layout` render `[S][R][K][T]` | DONE | reviewed | Layout.system/compiled/render, native transcript ordering and role-specific controller pinned briefs. Static text/formatting skipped. | - |
| [P1.8.3](TODO.md#L961) [M] `Anchor` render `[A]` | DONE | reviewed | Anchor reduction algorithm and Cell.renderAnchor/checkLines consumers; over-budget signal, obligation/current-receipt rendering. No formatting audit. | - |
| [P1.8.4](TODO.md#L967) [M] `Gauge` on every result | DONE | skimmed_trivial | Gauges inputs/result envelope integration inspected; simple numeric/rendering carriers skipped. | - |
| [P1.8.5](TODO.md#L972) [M] `Gates` and nudges (S0 set) | DONE | reviewed | Gates progress, suppression, loop, reserve/pressure and hard exit behavior with Cell state; corresponding test scenarios. | F-085; F-073 remains |
| [P1.8.6](TODO.md#L978) [M] `Residency`: eviction, stubs, recall | DONE | reviewed | Residency cadence/budget eviction, preserved call-result pairing, stubs/losses, Cell/Workset integration; no new independent algorithm finding. | F-047/F-050/F-051/F-058 remain relevant; run/edit alias loss confirmed at Cell.appendResult |
| [P1.8.7](TODO.md#L986) [M] `Cell` turn loop | DONE | reviewed | Cell full turn execution, request admission, dispatch, reconciliation, cancellation, accounting, persistence, rebuild and result provenance; CellTest fault/cancellation cases. | F-084, F-086, F-087, F-088 |
| [P1.8.8](TODO.md#L996) [M] Role completion and `ResultPacket` | DONE | reviewed | RoleCompletion, runtime packet construction, completion feedback, actual controller review timing; ResultPacketTest. Rechecked missing Assessment producer F-082. | F-089, F-090; F-082 |
| [P1.9.1](TODO.md#L1003) [C][M] `Lifecycle` state machines | DONE | reviewed | Lifecycle.apply/disposition and verifiedLedger; controller persisted reopen/finish paths. Trivial state records skimmed. | F-091 |
| [P1.9.2](TODO.md#L1010) [M] Campaign open and reconciliation | DONE | reviewed | Controller.open capture, contracts/checks, reconciliation, interrupted cells and lease acquisition; ControllerTest; protected-path construction. | F-092, F-093; F-044 historical |
| [P1.9.3](TODO.md#L1017) [M] S0 run and `Compiler` (S0 form) | DONE | reviewed | S0 compile/dispatch/return/verify/finish, controller tool wiring and final review sequencing. CliSyntax null interpreters is explicitly accepted D-66, not a new finding. | F-084/F-089; F-094 |
| [P1.9.4](TODO.md#L1024) [M] P0 lifecycle controls | DONE | reviewed | Cancellation, lease generation/fencing, attempt persistence and controller budget handoff; ControlsTest. Budget scope across cells traced to route/runCell. | F-088, F-093, F-095 |
| [P1.9.5](TODO.md#L1031) [M] `FinishReceipt` (S0 form) | DONE | reviewed | FinishReceipts.build/export, change attribution, acceptance/currency, accounting and final stamp projection. Static output formatting skipped. | F-094; incomplete check/review projection follows F-082/F-089 |
| [P1.9.6](TODO.md#L1037) [C][M] `Astrolabe` facade and `AstrolabeJava` | DONE | reviewed | Astrolabe project/campaign ownership and single-active guard; CampaignHandle await/cancel; AstrolabeJava future bridge. Thin DTO/getter wrappers skipped. | F-096 |
| [P1.10.1](TODO.md#L1048) [M] `Boundary`: delimiters and instruction-shape flag | DONE | reviewed | Boundary escaping/instruction detection integration, RulesTrust canonical path and digest approval, Layout policy/data roles. Small text declarations skipped. | F-084 (downstream authority, distinct from prompt delimiting) |
| [P1.10.2](TODO.md#L1055) [M] Capability ceiling and effect policy | DONE | reviewed | EffectPolicy argv/shell classification, paths, capability union and Ceiling; Run replay-safety consumer. Pure unknown-executable probe; confined backend remains P7. | F-097; F-060/F-071 historical; F-084 current |
| [P1.10.3](TODO.md#L1061) [M] `Redaction` | DONE | reviewed | Redaction byte cap, pattern overlap, line masks, content classes and binary behavior; default-pattern overlapping-match probe. | F-098; F-051/F-055/F-059 historical consumers |
| [P1.10.4](TODO.md#L1066) [C] `PermissionLadder` data model | DONE | skimmed_trivial | PermissionLadder grant/stage ordering and refusal semantics inspected; simple data/rendering paths skipped. Actual publication belongs to P5.2.1. | - |
| [P1.11.1](TODO.md#L1073) [M] `Span`s and metrics | DONE | reviewed | Spans start/end and clock-domain snapshots; Metrics event aggregation and actual Cell producers; scoped telemetry tests selected. | F-099; F-086 |
| [P1.11.2](TODO.md#L1081) [M] `Accounting` and exports | DONE | reviewed | Accounting record/unknown propagation/totals and Export consumers; currency/dimension boundaries inspected. Runtime charges cross-checked against Cell and Controller. | F-086/F-087/F-095 |
| [P1.11.3](TODO.md#L1088) [M] Trace analytics kernel (OOO-09) | DONE | reviewed | TraceAnalytics duplicate normalization, parent aggregation/cycle rejection, causal DAG path, interval sweep, completeness and decimal resource guards; TraceSnapshot contracts. No new defect established. | - |
| [P1.12.1](TODO.md#L1103) [V] Harness fixture tests | DONE | reviewed | FixtureRepos/TestKit harness behavior and representative fixture coverage cross-checked with prior audit; runner/report integration gaps recorded at their owning tasks. No full fixture rerun. | F-017/F-075 |
| [P1.12.2](TODO.md#L1108) [V] First vertical slice | DONE | reviewed | VerticalSliceTest/ControllerTest/CellTest execution and fault coverage mapped to real controller-cell seams; broad current runtime tests selected, terminal result tracked separately. | F-084-F-096 |
| [P1.12.3](TODO.md#L1114) [V] Java consumption smoke | DONE | reviewed | JavaConsumptionSmokeTest and AstrolabeJava async bridge/facade contract; simple wrappers skimmed. Kotlin outcome handling remains shared. | F-096 |
| [P1.12.4](TODO.md#L1120) [V] Platform validation | DONE | reviewed | Native process-owner launch/ABI layouts/handles, POSIX spawn/session/reap and Win32 jobs/quoting/cleanup; ProcOwnershipTest. Historical native deep-review follow-up completed statically; Linux unavailable. | F-100, F-101; F-015/F-016/F-017 historical |
| [P2.1.1](TODO.md#L1134) [C][M] `RequirementGraph`, `Increment`, `Ledger` | DONE | reviewed | RequirementGraph validation/coverage, iterative SCC/depth traversal, frontier/continuation, evidence binding and ledger dependency invalidation; graph tests inspected. | - |
| [P2.1.2](TODO.md#L1143) [M] Plan cell role and packet validator | DONE | reviewed | PlanPacketValidator and PlanIntake validation/authority mutation; controller plan dispatch/install and registry integration; PlanTest. | F-102, F-103; F-022/F-023 historical, F-071 qualified by missing registration |
| [P2.1.3](TODO.md#L1151) [M] Decision packets | DONE | skimmed_trivial | Decision packet propagation into plan/ADR candidates and finish receipt verified at call sites; simple register carriers skipped. | - |
| [P2.1.4](TODO.md#L1157) [M] Sizing telemetry | DONE | reviewed | Sizing.afterCell and graph continuation/cell recording; Lifecycle returned/lost/interrupted paths and persistence. Simple counters skimmed. | F-099 event metrics are separate from stored sizing |
| [P2.1.5](TODO.md#L1163) [M] `task.propose(plan | increment_split | amendment)` | DONE | reviewed | CampaignProposals decode/record and split inbox, TaskTool consumers, actual S1/S2 main-line wiring; PlanTest split coverage. | F-104 |
| [P2.2.1](TODO.md#L1174) [M] `ShapeSelector` (S0–S2) and activation table | DONE | reviewed | ShapeSelector initial/risk/S3 gating and adjustment; unknown-input S0 policy checked against explicit D-65, not newly reported as a bug. S3 ownership kernel remains P5. | - |
| [P2.2.2](TODO.md#L1181) [M] Campaign loop | DONE | reviewed | S1 plan/dispatch/continuation/regression refresh, finishing and budget paths; CampaignLoopTest, review ordering and rescoping behavior. | F-089/F-094/F-095/F-102/F-103/F-104 |
| [P2.2.3](TODO.md#L1188) [M] Sequential role switching | DONE | reviewed | Role-switch boundaries, fresh cell projection, STATUS checkpoints, pinned child briefs and actual role tool masks. | F-084/F-089/F-099 |
| [P2.2.4](TODO.md#L1195) [M] `Resume` protocol | DONE | reviewed | Reopen reconciliation, interrupted/blocked graph states, carryFrom/Seeds and old alias/preimage/shadow-state consumers; vertical interruption fixture. | F-105; F-031/F-033/F-061 remain source-supported, F-091/F-093 |
| [P2.2.5](TODO.md#L1202) [M] Attempt freeze | DONE | reviewed | Attempts first-write/frozen snapshot/load, alternative ID allocation and controller effective-config consumers; existing attempt tests. | F-001 historical mutable frozen input; no separate new finding |
| [P2.2.6](TODO.md#L1208) [M] Campaign finish | DONE | reviewed | Final regression reaccept, all-Run acceptance currency, full suite/review and FinishReceipts projection. | F-094/F-095; F-082/F-089 |
| [P2.3.1](TODO.md#L1217) [M] `Compiler.compile()` (full) | DONE | reviewed | Compiler budgets/mandatory coverage/optional selection and actual runCell/Workset consumers; role render boundary, seeds and note candidates. | F-106 |
| [P2.3.2](TODO.md#L1226) [M] `Manifest` | DONE | reviewed | Manifest.of selected-unit/omission lineage and SQL first-usage update; runCell actual usage call site. Projection accuracy tied to compiler integration. | F-106 |
| [P2.3.3](TODO.md#L1232) [M] Hard admission check before every dispatch | DONE | reviewed | ContextAdmission unknown-history refusal, exact/estimated bound, learned margin update and Cell integration; corresponding admission tests inspected. | F-002 historical overflow caveat; F-087 budget reconciliation is separate |
| [P2.3.4](TODO.md#L1238) [M] Dependency-aware context selection kernel (OOO-01) | DONE | reviewed | ContextCover dependency closure/cycle handling, mandatory bundle union, deterministic optional gain/cost ordering and BigInteger arithmetic; public cost/placement invariants. | - |
| [P2.4.1](TODO.md#L1250) [M] `CarryForward` | DONE | reviewed | CarryForward current-anchor/seed budget selection, referenced paths and packet carry; consumer integration inspected. | F-106/F-107 |
| [P2.4.2](TODO.md#L1257) [M] Cross-cell fact coherence and bounded retention | DONE | reviewed | FactCoherence stale streaks, demotion/archive/bounded retention and caller search; compared with actual carryFrom and pressure rebuild. | F-107 |
| [P2.4.3](TODO.md#L1263) [M] `STATUS` note (campaign checkpoint) | DONE | reviewed | StatusNotes checkpoint/revision/archive reader and actual controller boundary arguments; note write/export ordering. | F-107/F-110/F-111 |
| [P2.4.4](TODO.md#L1270) [M] Workset export/seed round trip and cross-cell recall | DONE | reviewed | Seeds.cellEnd/render/stubIndex, current-version and hidden-range handling, controller workset seeding and recall alias resolution. | F-106; F-047/F-050/F-051/F-058 |
| [P2.5.1](TODO.md#L1279) [M] `Rebuild(reason)` | DONE | reviewed | Rebuild.run reason routing/hooks/compile/coverage and protocol-tail grouping; actual runtime bypass compared explicitly. | F-108/F-109 |
| [P2.5.2](TODO.md#L1287) [M] Pressure use and decomposition-failure accounting | DONE | reviewed | Cell.rebuild tail identity filtering, Workset carry and stored sizing; existing pressure/long-refactor tests. | F-108/F-109; F-099 |
| [P2.5.3](TODO.md#L1293) [M] Projection validation after rebuild | DONE | reviewed | Rebuild lost-coverage/rehydrate refusal, Compiler.coverage and production call search; pure tests versus cell path. | F-109 |
| [P2.6.1](TODO.md#L1302) [C][M] `Note` model, `Kb` store, index generation, Markdown export | DONE | reviewed | Note records, Notes/KbWriter revisions/FTS/supersession, index regeneration and Markdown export; trivial formatting skipped. | F-110 |
| [P2.6.2](TODO.md#L1309) [M] `kb.search` / `kb.get` read path | DONE | reviewed | StoreKb FTS/fallback/visibility/staleness, KbTool search/get/skill result paths, unbounded STATUS read and redaction/budget handling. | F-111, F-112 |
| [P2.6.3](TODO.md#L1315) [I] Project-horizon coherence | DONE | reviewed | NoteHorizon anchor/dependency invalidation, controller Coherence registration and Injection/StoreKb fallback checks. | F-113 |
| [P2.6.4](TODO.md#L1320) [M] `Calibration` statistics (harness-rendered prior) | DONE | reviewed | Calibration observation extraction, band policy/plan prior/turn suggestions and actual controller/extractor consumers. | F-114/F-115 |
| [P2.6.5](TODO.md#L1327) [M] Calibration statistics kernel (OOO-03) | DONE | reviewed | CalibrationStats dedup/conflicts, series grouping, censored outcomes, medians/ratios/bands and warning threshold; kernel tests inspected. | F-114/F-115 originate in adapter, not aggregation kernel |
| [P2.7.1](TODO.md#L1339) [V] Fixtures and crash intervals | DONE | reviewed | ResumeTest/VerticalSliceTest crash intervals mapped to current controller/cell paths; campaign tests passed in V-005, context/graph selection pending V-006. | F-091/F-093/F-105/F-107/F-109 |
| [P2.7.2](TODO.md#L1344) [V] Scripted long refactor under context pressure | DONE | reviewed | LongRefactorTest checked for actual continuation, manifest, pressure and final-regression behavior; included in passing V-005. | F-106/F-108/F-109 are uncovered variants |
| [P2.7.3](TODO.md#L1349) [V] Economics report from manifests | DONE | reviewed | Economics.report ownership/accounting/boundary shares/cache classes/break-even and LongRefactorTest report assertions; live evaluation explicitly excluded. | F-086/F-095 upstream; journal/checkpoint rebuild metrics avoid F-099 |
| [P3.1.1](TODO.md#L1363) [M] Check definitions with closures and manifests | DONE | reviewed | ClosureManifest pinning/membership/exclusions and Closures blast fallback; declared package path semantics. Root-package probe. | F-116 |
| [P3.1.2](TODO.md#L1370) [M] Validity, applicability, reuse proofs | DONE | reviewed | Applicability/Scheduler repinning and reuse-proof consumers rechecked against deeper closure paths; prior scoped review reused. | F-077/F-078/F-079/F-116 |
| [P3.1.3](TODO.md#L1376) [M] Verify-on-stop | DONE | reviewed | Layers selection/due predicates, Verify.onStop/runLayer, missing registrations and final batch currency. | F-070/F-102/F-103/F-123 |
| [P3.1.4](TODO.md#L1381) [M] Layer wiring and additional runners | DONE | reviewed | Diagnostics recognition/wrapper handling and ruff/eslint/mypy/pyright/tsc/cargo/go parsing; layer wiring and actual Checker/Verify calls. Parser formatting skipped. | F-075/F-076; no additional diagnostics finding established |
| [P3.1.5](TODO.md#L1387) [M] No-concurrent-writer boundary during checks | DONE | reviewed | Scheduler exclusive lock/rescan versus isolated export, controller mode selection and mutation eligibility; earlier probes reused. | F-078/F-079/F-080 |
| [P3.1.6](TODO.md#L1393) [M] `unavailable` receipts and blocked path | DONE | reviewed | Missing-runner Unavailable receipts, Cell.unavailable before completion and blocked lifecycle/resume. | F-103/F-105 |
| [P3.2.1](TODO.md#L1401) [M] `ImportGraph` and `tests_for` | DONE | reviewed | ImportGraph package/import resolution, dynamic/incomplete markers, testsFor/build edges and tier provenance; ImportGraphTest. | F-117 |
| [P3.2.2](TODO.md#L1408) [M] `Impact.analyze(E)` | DONE | reviewed | Impact reverse traversal, completeness fallback, check/contract selection and compensated hunk risk; ImpactAssembly adapter. | F-117 upstream completeness |
| [P3.2.3](TODO.md#L1415) [M] `look(refs | importers | impact)` | DONE | reviewed | Look structural results and impact/ref lookup consumers cross-checked with Cell inspection credit; earlier Look audit reused. | F-118 |
| [P3.2.4](TODO.md#L1420) [M] Impact nudge and exit-gate binding | DONE | reviewed | ImpactNudges changed/inspected/rescoped state and Cell exit-gate coupling; public definitions and truncated look results. | F-118 |
| [P3.2.5](TODO.md#L1426) [M] Blast radius selection | DONE | reviewed | Blast narrow test/closure selection versus conservative package/workspace fallback, TakesPaths/cwd handling. | F-076 also applies to workspace-relative appended test paths; F-117 |
| [P3.2.6](TODO.md#L1432) [I] Impact pre-scan at campaign open | DONE | reviewed | ImpactPrescan path/identifier/hub discovery, unknown propagation and Controller.open/refresh routing inputs. | F-117 tier completeness; D-65 unknown-risk choice retained |
| [P3.2.7](TODO.md#L1437) [C][M] Snapshot impact calculation kernel (OOO-04) | DONE | reviewed | ImpactSnapshot identity/immutability, graph invariants, deduped hunk arithmetic, reverse closure and risk bounds; independent graph-test cases inspected. | - |
| [P3.3.1](TODO.md#L1454) [M] `edit(transform)` with diff receipt | DONE | reviewed | Transform inventory/pins/effect classification, preimage order, process/capture, stamp diff, NOT SEEN coverage and syntax handoff. | F-119/F-120; F-057 extends to observation IOException misreported as launch failure |
| [P3.3.2](TODO.md#L1462) [M] Out-of-scope and count-failure handling | DONE | reviewed | Transform rejection priority, count/outside-scope inverse guards and partial/unknown effects; TransformTest rollback cases. | F-119; no automatic rollback of external effects claimed |
| [P3.4.1](TODO.md#L1470) [M] `ScopeGuard` | DONE | reviewed | ScopeGuard and Edit actual preflight, new transform paths and turn revert caller behavior; current F-056 source recheck. | F-056 still present at revertPaths(turn:); F-092/F-119 |
| [P3.4.2](TODO.md#L1477) [M] `TestIntegrity` classifier and acceptance-surface line | DONE | reviewed | TestIntegrity precise classifier, ordered semantics versus line multisets, additions-only exemption and runtime flags; assertion-reorder probe. | F-121; F-083 |
| [P3.4.3](TODO.md#L1483) [I] Gate registrations | DONE | reviewed | Scope/integrity/contract/impact gate registration and Cell runtime state/event consumers; substantive actions only. | F-085/F-089/F-113/F-118/F-121 |
| [P3.5.1](TODO.md#L1491) [M] Activation, behaviour snapshot, `red_ok_until` | DONE | reviewed | RefactorMode activation, BehaviourSnapshots baseline/output capture and Cell redOkUntilIncrementEnd; refactor campaign tests. | F-081/F-120; default syntax unavailability remains D-66 |
| [P3.5.2](TODO.md#L1498) [M] Contract-first interfaces, equivalence evidence, mandatory campaign review | DONE | reviewed | Equivalence identity/outcome/golden comparison and receipt re-shaping, contract-first plan checklist, CampaignReview current-candidate binding and controller finalization. | F-075/F-081/F-089/F-094 |
| [P3.6.1](TODO.md#L1507) [M] `Flaky` policy | DONE | reviewed | Verify.runTriaged retry predicate and Scheduler.flaky evidence aggregation; actual default versus isolated runner roots. | F-122 |
| [P3.6.2](TODO.md#L1513) [M] Full-suite cadence and quality gates | DONE | reviewed | Controller full-suite cadence, configured quality-gate dispatch and final certification decisions; Layers scheduling. | F-123 |
| [P3.7.1](TODO.md#L1521) [O][M] `Precompile` | DONE | reviewed | Fingerprint construction/difference, pending precompile cancellation/consumption/coverage, controller lifecycle and optional flag wiring. No new correctness finding established. | F-106/F-109 are underlying projection issues |
| [P3.8.1](TODO.md#L1531) [V] Fixtures | DONE | reviewed | Stage C fixture assertions mapped to closure/impact/transform/integrity/current-review behavior; targeted P3 tests tracked as V-007. | F-116-F-123 uncovered cases |
| [P3.8.2](TODO.md#L1536) [V] Scripted migration and 40-file rename | DONE | reviewed | StageCCampaignTest and RefactorCampaignTest migration/40-file transform/revert/receipt/review assertions; passed with campaign tests in V-005. | F-094/F-119/F-121/F-123 |
| [P4.1.1](TODO.md#L1550) [M] `Queue`, `Curator`, admission policy | DONE | reviewed | Curator/Queue/Admission; Injection/KnowledgeUse/controller; Extractor/Derived/NEG; SkillViews/Skills; BMAP validation and lookup; scoped tests; simple carriers skimmed | F-124 to F-127 |
| [P4.1.2](TODO.md#L1557) [M] Invalidation, usage tracking, pruning, promotion, index regeneration | DONE | reviewed | Curator/Queue/Admission; Injection/KnowledgeUse/controller; Extractor/Derived/NEG; SkillViews/Skills; BMAP validation and lookup; scoped tests; simple carriers skimmed | F-124 to F-127 |
| [P4.1.3](TODO.md#L1562) [M] `Injection` ranking, focus notes, retrieval-miss logging | DONE | reviewed | Curator/Queue/Admission; Injection/KnowledgeUse/controller; Extractor/Derived/NEG; SkillViews/Skills; BMAP validation and lookup; scoped tests; simple carriers skimmed | F-124 to F-127 |
| [P4.2.1](TODO.md#L1572) [M] Post-cell `Extractor` | DONE | reviewed | Curator/Queue/Admission; Injection/KnowledgeUse/controller; Extractor/Derived/NEG; SkillViews/Skills; BMAP validation and lookup; scoped tests; simple carriers skimmed | F-124 to F-127 |
| [P4.2.2](TODO.md#L1580) [M] Typed negative evidence and derived candidates | DONE | reviewed | Curator/Queue/Admission; Injection/KnowledgeUse/controller; Extractor/Derived/NEG; SkillViews/Skills; BMAP validation and lookup; scoped tests; simple carriers skimmed | F-124 to F-127 |
| [P4.3.1](TODO.md#L1589) [M] `Skill` notes and module filtering | DONE | reviewed | Curator/Queue/Admission; Injection/KnowledgeUse/controller; Extractor/Derived/NEG; SkillViews/Skills; BMAP validation and lookup; scoped tests; simple carriers skimmed | F-124 to F-127 |
| [P4.3.2](TODO.md#L1596) [M] `BMAP` notes and `look(bmap)` | DONE | reviewed | Curator/Queue/Admission; Injection/KnowledgeUse/controller; Extractor/Derived/NEG; SkillViews/Skills; BMAP validation and lookup; scoped tests; simple carriers skimmed | F-124 to F-127 |
| [P4.4.1](TODO.md#L1605) [C][M] Packets and `Delegator` | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.4.2](TODO.md#L1612) [M] `Probe` cell | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.4.3](TODO.md#L1618) [M] `ReviewCell` and judge protocol (two scopes) | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.4.4](TODO.md#L1625) [C] `QaCell` contract (L3) | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.4.5](TODO.md#L1631) [O] Worth test estimate | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.4.6](TODO.md#L1636) [C] Role policy texts and packet validators (probe, review, QA, repair, extractor) | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.5.1](TODO.md#L1646) [M] Tier table, function table, `Router.selectProfile` | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.5.2](TODO.md#L1654) [M] `Escalation` ladder and attempt allowance | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.5.3](TODO.md#L1660) [M] Cache-aware scheduling and shadow-routing seam | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.5.5](TODO.md#L1667) [M][V] Exact small-DAG scheduling oracle (OOO-08) | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.5.4](TODO.md#L1679) [M] Finite attempt-policy cost kernel (OOO-06) | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.6.1](TODO.md#L1694) [M] Failure classes and `recover()` | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.6.2](TODO.md#L1701) [M] `Fingerprint`s, global no-progress budget, guards | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.6.3](TODO.md#L1707) [M] Failure `Capsule` and `Repair` helper | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.6.4](TODO.md#L1714) [M] `Alternative` attempts | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.7.1](TODO.md#L1723) [C] `Mount` contract and catalog | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.8.1](TODO.md#L1733) [V] Fixtures | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P4.8.2](TODO.md#L1738) [V] Review, recovery and routing fixtures | DONE | reviewed | Delegator/ChildCells/TaskPackets; Probe/Judge/ReviewCell/QA validators and role texts; Router/Escalation, cache scheduling, exact DAG and finite-policy kernels; Ladder/Guards/Repair/Alternative and CampaignRecovery; catalog and dispatch boundaries; selected fixtures (test run pending); simple records skimmed | F-128 to F-132; F-095 |
| [P5.1.1](TODO.md#L1752) [M] Worktrees and workspace-qualified identities | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.1.2](TODO.md#L1760) [M] `Writer` role | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.1.3](TODO.md#L1765) [M] `Integrator` and `MergeQueue` | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.1.4](TODO.md#L1773) [M] `select_shape` S3 branch and global limits | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.1.5](TODO.md#L1780) [M][V] Symbolic write-scope algebra (OOO-05) | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.2.1](TODO.md#L1793) [M] Stages beyond `patch` | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.3.1](TODO.md#L1803) [O][M] `QaCell` implementation | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.3.2](TODO.md#L1809) [O][C] L4 measurement gate contract | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.4.1](TODO.md#L1817) [O][M] `index-treesitter` module (tier 1) | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.4.2](TODO.md#L1824) [O][C] Language-service adapter contract (tier 2) | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.5.1](TODO.md#L1832) [O][C] `Retriever` interface | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.6.1](TODO.md#L1840) [O][M] Generated tool lifecycle | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.6.2](TODO.md#L1846) [O][M] Skills and executable promotion tasks | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.7.1](TODO.md#L1854) [O][C] `Watcher` interface | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P5.8.1](TODO.md#L1863) [V] Fixtures and publication invariant | DONE | reviewed | Workspaces/Writers/Integrator/S3Round/shape intake; ScopeAlgebra NFA/product search; PublicationPolicy/Publisher; QA driver and measurement validator; tree cache/syntax/index, language-service seam; retrieval merge; generated registration; promotion proposals and watcher feed; scoped fixtures, simple carriers skimmed | F-133 to F-140; F-079/F-080 follow-up |
| [P6.1.1](TODO.md#L1878) [M] Fixture harness runner | DONE | reviewed | FixtureRunner collector/XML/exit verdict; CampaignManifest/Arms/Integrity; Scorecard/Promotion/PairedBound and provenance validation; WorkloadSplit exact search/grouping/quotas; TraceMining/Experiments and synthetic tests; live gates excluded | F-141 to F-143; V-008 |
| [P6.1.2](TODO.md#L1884) [M] Frozen campaigns, comparators, ablation switches, workload manifests | DONE | reviewed | FixtureRunner collector/XML/exit verdict; CampaignManifest/Arms/Integrity; Scorecard/Promotion/PairedBound and provenance validation; WorkloadSplit exact search/grouping/quotas; TraceMining/Experiments and synthetic tests; live gates excluded | F-141 to F-143; V-008 |
| [P6.1.3](TODO.md#L1891) [M] Scorecard and promotion policy | DONE | reviewed | FixtureRunner collector/XML/exit verdict; CampaignManifest/Arms/Integrity; Scorecard/Promotion/PairedBound and provenance validation; WorkloadSplit exact search/grouping/quotas; TraceMining/Experiments and synthetic tests; live gates excluded | F-141 to F-143; V-008 |
| [P6.1.4](TODO.md#L1898) [M][V] Standalone scorecard and paired inference kernel (OOO-02) | DONE | reviewed | FixtureRunner collector/XML/exit verdict; CampaignManifest/Arms/Integrity; Scorecard/Promotion/PairedBound and provenance validation; WorkloadSplit exact search/grouping/quotas; TraceMining/Experiments and synthetic tests; live gates excluded | F-141 to F-143; V-008 |
| [P6.1.5](TODO.md#L1908) [M][V] Constrained workload partition kernel (OOO-07) | DONE | reviewed | FixtureRunner collector/XML/exit verdict; CampaignManifest/Arms/Integrity; Scorecard/Promotion/PairedBound and provenance validation; WorkloadSplit exact search/grouping/quotas; TraceMining/Experiments and synthetic tests; live gates excluded | F-141 to F-143; V-008 |
| [P6.2.1](TODO.md#L1922) [O][M] Trace mining and experiment bookkeeping | DONE | reviewed | FixtureRunner collector/XML/exit verdict; CampaignManifest/Arms/Integrity; Scorecard/Promotion/PairedBound and provenance validation; WorkloadSplit exact search/grouping/quotas; TraceMining/Experiments and synthetic tests; live gates excluded | F-141 to F-143; V-008 |
| [P6.3.1](TODO.md#L1931) [V] Runner and scorecard checks | DONE | reviewed | FixtureRunner collector/XML/exit verdict; CampaignManifest/Arms/Integrity; Scorecard/Promotion/PairedBound and provenance validation; WorkloadSplit exact search/grouping/quotas; TraceMining/Experiments and synthetic tests; live gates excluded | F-141 to F-143; V-008 |

## Findings (TODO order)

### F-001 - Attempt configuration remains mutable after freeze

- Task: [P0.1.3](TODO.md#L548); follow-up: P1.9.4, P2.2.5.
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-27).
- Locations: [AttemptConfig.freeze / fingerprint](core/src/main/kotlin/io/astrolabe/AttemptConfig.kt#L47), [Config collection fields](core/src/main/kotlin/io/astrolabe/Config.kt#L38), [Controller.open](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L338).
- Problem: `freeze` stores the original Config and roleTextVersions objects without a defensive snapshot. Kotlin read-only Map/List/Set interfaces do not prevent mutation through the caller's retained mutable collection or Java references. Nested profile prices/capabilities, roles/tool masks, redaction configuration and quality commands are also collection-bearing. The fingerprint is computed only once, so it continues to describe the old contents after mutation.
- Trigger and impact: open an attempt using a mutable profile/role map, then replace a value or mutate a nested collection. The first-open controller directly uses `frozen.config`; behavior and accounting inputs can change mid-attempt while the stored fingerprint and persisted attempt still describe the previous values. This violates invariant 12 and undermines reproducibility. A reopened attempt is deserialized separately, which does not protect the first-open path.
- Evidence: source data-flow inspection; AttemptConfigTest covers equal construction and invalid controls but no retained-reference mutation. Runtime reproduction is recorded below.
- Possible solutions: deep-copy the complete configuration at the authority boundary and expose immutable collections; alternatively retain canonical serialized bytes as the snapshot and deserialize into privately owned immutable records. Copying only the outer map is insufficient.
- Future regression: mutate both outer and nested host collections after freeze/open; all frozen values, fingerprint and effective behavior must remain unchanged. Also try mutation through Java getters.

- Runtime evidence (2026-09-24, after fresh core compilation): clearing the host profile map leaves frozen profiles=0 while fingerprint remains unchanged. JShell snippets ran from stdin; no source/test file was added.
- Current-source recheck (2026-09-25, 9a80e117): AttemptConfig.freeze still retains Config/maps and Controller.open uses frozen.config (455); no defensive snapshot added. Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-27, `9192a41`): Every constructor, copy and deserialization takes a deep immutable configuration snapshot, including default roles and nested provider JSON. Controller compares normalized snapshots. Retained-source and exposed-reference mutation regressions pass.


### F-002 - Configuration fingerprints depend on insertion order

- Task: [P0.1.3](TODO.md#L548).
- Severity: medium. Confidence: reproduced. Status: fixed (2026-09-27).
- Location: [AttemptConfig.fingerprint / STABLE_JSON](core/src/main/kotlin/io/astrolabe/AttemptConfig.kt#L58).
- Problem: `Json { encodeDefaults = true }` is labeled stable but does not canonicalize Map/Set iteration order. Equal Config objects with the same profile/role/price mappings inserted in a different order can serialize differently and receive different fingerprints. roleTextVersions has the same issue.
- Impact: equivalent configuration can invalidate attempt/compile fingerprints and create unnecessary rebuild/cache misses or confusing provenance differences. Exact downstream impact should be rechecked at P2.2.5/P3.7.1.
- Evidence: direct hash-of-serialization implementation; existing stability test constructs the same single-entry ordering twice and does not cover permutations.
- Possible solutions: recursively canonicalize unordered mappings/sets before hashing, while preserving semantically ordered lists; use a versioned encoding and account for stored fingerprint compatibility.
- Future regression: construct equal configurations with opposite map/set insertion orders and require equal fingerprints; a real price/permission change must still change the hash.

- Runtime evidence (2026-09-24, after fresh core compilation): equal role-version maps inserted in reverse order produce different fingerprints. JShell snippets ran from stdin; no source/test file was added.
- Current-source recheck (2026-09-25, 9a80e117): AttemptConfig.fingerprint still hashes ordinary encodeDefaults JSON without canonical map/set ordering. Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-27, `9192a41`): Versioned attempt-config/v2 fingerprints canonicalize map/set order, including provider JSON objects, while retaining ordered lists and arrays. Equivalent insertion orders match; real value and sequence changes differ. Legacy JSON remains readable but recomputes the new fingerprint.


### F-003 - Duplicate tool-call IDs pass pairing validation

- Task: [P0.3.1](TODO.md#L570); related [P0.3.4](TODO.md#L588).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Items.pairs](provider-api/src/main/kotlin/io/astrolabe/provider/Item.kt#L148), [Validations.standard](provider-api/src/main/kotlin/io/astrolabe/provider/ProviderAdapter.kt#L72).
- Trigger: `[ToolCall("a", "look", "{}"), ToolCall("a", "run", "{}"), ToolResult.text("a", "one result")]`.
- Problem: the call index overwrites an existing ID, while the calls list retains both calls. Final mapping attaches the same result to every call with that ID; no orphan, duplicate result or unmatched call is reported, so `broken == false`. A later reused ID can even acquire an earlier result.
- Impact: the common validator certifies an ambiguous native history as valid. Dispatch/replay can associate the wrong result or fail at a provider boundary; later cell-level call validation should be checked separately.
- Evidence: traced the loop and final `calls.map`; ItemsTest tests duplicate results but not duplicate calls.
- Possible solutions: explicitly reject duplicate call IDs in the supported history namespace and report them in Pairing/Validation; if a provider permits reuse across turns, model that scope explicitly rather than use a history-wide result map.
- Future regression: duplicate calls before a result and ID reuse after a completed pair must be rejected or correctly scoped; ordinary parallel distinct calls must remain valid.

- Runtime evidence (2026-09-24, after fresh core compilation): duplicate calls produce broken=false and two pairs sharing one result. JShell snippets ran from stdin; no source/test file was added.
- Fix (2026-09-26, `4e9d774`): Duplicate call IDs and reused completed IDs make protocol pairing invalid; provider regression tests.


### F-004 - Generic request estimator can claim exactness while omitting protocol/native context

- Task: [P0.3.2](TODO.md#L577).
- Severity: high. Confidence: potential. Status: fixed (2026-09-27).
- Locations: [Request.estimate / Item.estimate](provider-api/src/main/kotlin/io/astrolabe/provider/Estimate.kt#L83), [Item.native](provider-api/src/main/kotlin/io/astrolabe/provider/Item.kt#L15), [TokenEstimator default request method](provider-api/src/main/kotlin/io/astrolabe/provider/Estimate.kt#L75).
- Problem: request estimation adds text fragments/schema strings but omits roles, call/result IDs, framing and all `native` content. A ReasoningRef with opaque=null contributes zero even if native contains replay material. If the text estimator returns exact=true, the sum remains exact=true with zero margin, despite not counting the effective provider request. Tokenization of fragments separately also does not establish exactness for their serialized composition.
- Impact: near-limit requests can be admitted despite exceeding the actual effective context. Live transports are intentionally P7, so this is a contract/integration risk rather than a measured provider failure; an adapter overriding request estimation and admission can avoid it.
- Evidence: exhaustive cases of Item.estimate; RequestTest validates the same fragment sum, not a provider serialization. No external provider behavior claimed.
- Possible solutions: require profile-specific estimation of the fully serialized/effective request at dispatch; make the generic path explicitly approximate with a defensible framing margin and mark unknown native/history contributions as unknown. Avoid double counting native payload that replaces the normalized form.
- Future regression: near-limit requests containing many short messages, long call IDs and native-only replay data; an exact text tokenizer alone must never yield an exact whole-request count.
- Fix (2026-09-27, `55e86ea, eccb010`): Generic request counts include roles, call/result IDs and explicit planning margins, never claim exactness, and flag native-only replay/non-text content as unknown. Cell dispatch honors profile-specific request-estimator overrides. Exact-text, long-ID, native-context and dispatch regressions pass. Generic framing allowances remain planning estimates, not measured provider limits.


### F-005 - Overflow can turn an excessive admission count into a valid request

- Task: [P0.3.2](TODO.md#L577); related [P0.3.4](TODO.md#L588).
- Severity: medium. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Estimate.upperBoundTokens](provider-api/src/main/kotlin/io/astrolabe/provider/Estimate.kt#L25), [Validations.standard needed calculation](provider-api/src/main/kotlin/io/astrolabe/provider/ProviderAdapter.kt#L103).
- Trigger: supply an allowed nonnegative Estimate with tokens=Long.MAX_VALUE, marginTokens=0 and any positive maxOutputTokens, or overflow tokens+margin. This can also originate in an invalid adapter-reported effectiveHistoryTokens value.
- Problem: unchecked Long addition wraps to a negative number; `needed > contextLimitTokens` becomes false. Counts are range-checked individually but their total is not.
- Impact: admission fails open for extreme/malformed provider or estimator counts. These are not realistic physical token volumes, so severity reflects input-validation robustness rather than normal throughput.
- Possible solutions: checked/saturating addition, or subtraction-based capacity checks that reject any individual term above the supported context limit before summing.
- Future regression: maximum Long tokens/margins/history and values close to overflow must yield a structured rejection, never Ok or an unclassified exception.


- Runtime evidence (2026-09-24, after fresh core compilation): Long.MAX_VALUE input estimate plus one output token is accepted as Validation.Ok. JShell snippets ran from stdin; no source/test file was added.
- Fix (2026-09-26, `4e9d774`): Token sums saturate and admission compares capacity without overflow; maximum-Long regressions.


### F-006 - Fake invocation terminal reconciliation depends on an active await caller

- Task: [P0.3.5](TODO.md#L594).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-27).
- Location: [FakeAdapter.FakeInvocation](core/src/testFixtures/kotlin/io/astrolabe/fixtures/FakeAdapter.kt#L198).
- Problem: only `await()` creates/completes terminalRecord and terminalDeferred. `cancel()` merely sets flags and opens the gate. If the coroutine suspended in gate.await() is cancelled, it exits without invoking cancel or producing a terminal. Calling cancel explicitly and then terminal without another await also never completes.
- Impact: the fixture violates Invocation's cancellation/accounting contract and cannot faithfully validate a runtime which cancels its response waiter and separately drains terminal usage. Such cancellation tests can hang or leave reservations unresolved; tests explicitly calling await after cancel conceal the gap.
- Possible solutions: let the fake own completion independently of the waiting coroutine, including provider cancellation acknowledgement and terminal production; response-wait cancellation must request cancellation without cancelling the completion owner.
- Future regression: start with holdResponses, cancel the await job, then bound terminal() with a test timeout and assert exactly one cancelled terminal with late usage. Also test cancel followed directly by terminal.
- Fix (2026-09-27, `6b7c801`): Cancel and release settle held invocations independently of response waiters; cancellation of await requests provider cancellation and preserves the single terminal usage record. Direct cancellation, cancelled waiter and release-without-waiter regressions pass.


### F-007 - Concurrent event emission can reorder delivery and duplicate replay

- Task: [P0.4.1](TODO.md#L602).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Locations: [Events.emit / subscribe](core/src/main/kotlin/io/astrolabe/event/Events.kt#L69).
- Trigger: emitter A allocates seq=1 and leaves the lock; B allocates seq=2 and offers it first; A then offers seq=1. Alternatively, subscribe between A's replay insertion and A's subscriber iteration.
- Problem: sequence/replay mutation is synchronized, but subscriber enqueue is outside that serialization boundary. The first schedule delivers 2 then 1; the second subscriber can receive seq=1 both from replay and from live delivery.
- Impact: host projections can regress or apply an event twice even without any dropped events, undermining the documented monotonically ordered gap-recovery protocol.
- Evidence: explicit possible thread interleavings; EventsTest's ordering and late replay tests emit sequentially.
- Possible solutions: serialize sequence allocation, replay and nonblocking queue offers together, still invoking host sinks outside all locks; or add a single ordered dispatch queue with a subscription watermark.
- Future regression: barrier-controlled concurrent emit/subscribe; every subscriber must see strictly increasing unique sequence IDs, except documented gaps.
- Fix (2026-09-26, `43f8a15`): Sequence assignment, replay and subscriber enqueue share the event lock; concurrent-emitter regression.


### F-008 - Flow subscribers silently drop newest events instead of retaining the latest state

- Task: [P0.4.1](TODO.md#L602).
- Severity: medium. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Location: [Events.records](core/src/main/kotlin/io/astrolabe/event/Events.kt#L102).
- Problem: records() adds a second callbackFlow buffer behind the per-subscriber DROP_OLDEST channel. Its sink calls `trySend` and ignores failure; when that second buffer fills, incoming/newest events are discarded, without updating Subscription.dropped.
- Impact: a slow Flow collector may drain old queued events and permanently miss the final Finished/Blocked event. If no later event arrives, no sequence gap announces the loss. This differs from the promised same semantics as subscribe and latest-tail retention.
- Possible solutions: explicitly configure drop-oldest buffering for the flow path or expose the original bounded channel as a flow without a second silently dropping queue; make overflow observable.
- Future regression: hold a Flow collector, emit beyond capacity with Finished last, stop emitting and resume collection; the final state must be delivered or an explicit resync signal must exist.
- Fix (2026-09-26, `43f8a15`): Flow buffer explicitly drops oldest records; stalled collector receives the final event after overflow.


### F-009 - Java authority bridge converts coroutine cancellation into no answer

- Task: [P0.4.2](TODO.md#L608).
- Severity: medium. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Location: [Authorities.awaitNullable](core/src/main/kotlin/io/astrolabe/event/Authorities.kt#L24).
- Problem: the RuntimeException catch includes CancellationException from suspendCancellableCoroutine. ask/review return null on cancellation instead of propagating cancellation to their caller. Host failure logging promised by JavaAuthority is also absent here.
- Impact: cancellation can be handled as an unanswered question/review and run non-suspending blocked/evidence side effects before a later suspension notices the cancelled job. This is a bridge-level contract defect; exact campaign outcome requires checking the later consumer paths.
- Possible solutions: rethrow CancellationException before mapping ordinary host failures to no answer; distinguish and log genuine host completion failures without cancelling its future.
- Future regression: cancel a job awaiting an incomplete host future; the coroutine must exit cancelled, the future must remain uncancelled, and the caller must not observe a normal null reply.
- Fix (2026-09-26, `43f8a15`): Java authority cancellation propagates without cancelling the host future; ordinary failures are logged by type.


### F-010 - UI resynchronization has inconsistent multi-query snapshots and growing history cost

- Task: [P0.4.3](TODO.md#L614).
- Severity: medium. Confidence: confirmed_source. Status: fixed (2026-09-27).
- Locations: [Views.contract / register](core/src/main/kotlin/io/astrolabe/event/Views.kt#L114), [Export.write](core/src/main/kotlin/io/astrolabe/event/Export.kt#L40), [Db.query](core/src/main/kotlin/io/astrolabe/store/Db.kt#L75).
- Problem: a contract projection and a full export consist of independently locked SELECTs. A writer can commit a new revision between them, producing old contracts with new requirements/acceptance in one view. register() also reads/deserializes every historical register body merely to return the newest row and history count; cost grows with history even though the live register is bounded.
- Impact: gap-repair UI reads can present a state that never existed atomically; long campaigns pay unnecessary full-history reads. The first issue needs concurrent access; sequential unchanged exports remain deterministic as tested.
- Possible solutions: perform a composed view read under one coherent DB snapshot/lock; obtain latest register with ORDER BY ... LIMIT 1 plus COUNT in that snapshot. If whole-history exports are needed, stream/page them separately.
- Future regression: interleave an amendment transaction with a composite read and require a wholly old or wholly new revision; query/deserialize bounded rows for latest-register reads with large history.

- P1.1.1 follow-up: [Contracts.current](core/src/main/kotlin/io/astrolabe/contract/Contracts.kt#L73) likewise obtains the latest contract by loading/deserializing its entire history through SqliteContractRepository.history. Add a latest-only repository query for frequent current-authority reads.
- Fix (2026-09-27, `4c99034`): Composed views and all export inputs share one SQLite read transaction. Current register reads use LIMIT 1 plus COUNT; Contracts.current uses repository.latest with a bounded SQL implementation. A commit injected between SELECTs preserves the original snapshot; malformed historical bodies do not affect current reads.


### F-011 - Failed COMMIT does not roll back or quarantine the connection

- Task: [P0.5.1](TODO.md#L622).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [Db.tx](core/src/main/kotlin/io/astrolabe/store/Db.kt#L52).
- Problem: rollback is only inside the catch around block(Tx(this)); COMMIT executes outside that catch. A commit failure resets inTransaction=false in finally without ensuring the underlying transaction ended. A failure to roll back the body is also swallowed while the connection remains reusable.
- Impact: after a failed commit that leaves SQLite's transaction active, subsequent reads can observe uncommitted state and the next BEGIN fails with a nested-transaction error. The process must reopen the store instead of recovering cleanly. SQLite explicitly permits failed COMMIT to leave a transaction active: [official transaction documentation](https://www.sqlite.org/lang_transaction.html).
- Possible solutions: guard the full begin/body/commit sequence; on failure attempt rollback and preserve the original exception; if cleanup cannot establish a clean connection, close/quarantine it. Preserve uncertain-commit semantics for storage failures.
- Future regression: a deferred foreign-key violation at COMMIT followed by a new transaction must leave no uncommitted row and allow reuse, or explicitly mark the DB unusable. Add an injected rollback failure case.

- Runtime evidence: on fresh compiled code, a deferred foreign-key COMMIT failed; a subsequent SELECT still saw the uncommitted child row (count=1), and the next tx failed with `cannot start a transaction within a transaction`. The probe used a new isolated database under ignored build/ and did not touch project state. JShell needed java.sql enabled; the corrected probe completed.
- Fix (2026-09-26, `24e53e3`): COMMIT is inside rollback protection; failed rollback quarantines the connection even if close also fails. Deferred-FK and injected rollback/close failure regressions pass (follow-up `15bfcaa`).


### F-012 - Blob publication assumes a single channel write writes all bytes

- Task: [P0.5.1](TODO.md#L622).
- Severity: high. Confidence: potential. Status: fixed (2026-09-26).
- Locations: [BlobStore.publish](core/src/main/kotlin/io/astrolabe/store/BlobStore.kt#L230), [ProjectLock.acquire holder write](core/src/main/kotlin/io/astrolabe/store/ProjectLock.kt#L129).
- Problem: one `channel.write(ByteBuffer.wrap(bytes))` is followed by force/rename without checking remaining bytes. The [FileChannel.write contract](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/nio/channels/FileChannel.html#write(java.nio.ByteBuffer)) does not guarantee a full write.
- Impact: a short write can publish a truncated blob under the full input's digest and insert its full size into SQLite. A receipt can then reference an unreadable recovery/evidence artifact. Lock-holder truncation affects diagnostics only. No short write was reproduced on the local filesystem.
- Possible solutions: loop until buffer.hasRemaining is false, as LocalOs.replaceFileAtomically already does; detect persistent zero-progress/error and do not publish. Consider checking publication length before inserting a row.
- Future regression: inject a channel that writes a bounded prefix per call; persisted bytes must match the original digest and length, including large payloads.
- Current-source recheck (2026-09-25, 9a80e117): BlobStore.publish still performs one FileChannel.write before force/move (239). Short-write risk remains potential; no failure reproduced. Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `24e53e3`): Blob and lock-holder publication drain their ByteBuffers before force/publication. Existing storage tests pass; forced short-write injection was not performed.


### F-013 - Blob GC is unbounded in scan cost and can starve later orphans

- Task: [P0.5.1](TODO.md#L622).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Locations: [BlobStore.gc](core/src/main/kotlin/io/astrolabe/store/BlobStore.kt#L169), [BlobStore.listFiles](core/src/main/kotlin/io/astrolabe/store/BlobStore.kt#L295).
- Problem: every pass eagerly lists/sorts all temp files, reads every blob digest into a set, lists/sorts both blob directories, and loads/deletes all collectable rows. MAX_ORPHANS_PER_PASS only limits part of the post-enumeration processing. More than 4096 young temp files consume that budget on every pass, so no later orphan is reached until aging; there is no continuation cursor despite the next-pass-continues claim.
- Impact: memory, sorting and delete time grow with total project history, and a backlog can delay referenced-orphan adoption or trigger a false missing-blob failure when the budget prevents reaching it. No throughput benchmark yet; complexity follows directly from eager list/query operations.
- Possible solutions: paginate DB and directory work with a stable cursor and total work budget; resolve referenced digests directly before opportunistic orphan scanning; prioritize integrity repair independently of cleanup quotas.
- Future regression: more than one pass of young temp files plus a referenced orphan beyond the cursor; adoption must succeed and repeated bounded passes must make progress. Measure max work per pass, not only its orphan counter.
- Current-source recheck (2026-09-25, 9a80e117): BlobStore.gc still eagerly enumerates files and all DB digests; young temp entries consume the sole orphan budget (167?224). Earlier runtime evidence retains its original baseline; this recheck is source inspection.

### F-014 - Configured state root may be inside the workspace

- Task: [P0.5.1](TODO.md#L622).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-27).
- Locations: [Layout.resolve](core/src/main/kotlin/io/astrolabe/store/Layout.kt#L121), [Store.open](core/src/main/kotlin/io/astrolabe/store/Store.kt#L91).
- Trigger: a host supplies Config.stateRoot equal to the repository root or an unignored child directory, including a symlink alias resolving there.
- Problem: the root is used verbatim and created before any containment check against the working tree. The documented external-state invariant is not validated.
- Impact: SQLite/WAL, logs, blobs and exports enter repository scans/stamps. Runtime writes can make the candidate perpetually drift; captures may recursively include prior artifacts and expose private state to workspace reads. The exact amplification depends on ignore rules and the later capture paths.
- Possible solutions: canonicalize both roots and reject durable state under any relevant workspace before creating directories; alternatively require and validate an explicit external-storage policy at Project/Store open.
- Future regression: direct and symlinked in-workspace state roots must fail before any store file is created; an external root shared by linked worktrees remains valid.
- Fix (2026-09-27, `6bb0767`): Store.open canonicalizes existing ancestors of the configured base and final layout, and rejects state inside any registered Git worktree before creating directories. Direct, nested, linked-worktree and Windows junction regressions pass.


### F-015 - Terminal process status is published before descendant cleanup

- Task: [P0.6.1](TODO.md#L635).
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [LocalOs.supervise / settle / Supervision.release](core/src/main/kotlin/io/astrolabe/os/LocalOs.kt#L222).
- Problem: after the root exits, supervise calls settle first (publishes terminal status, writes sidecar, signals waiters), removes supervision, and only then release terminates descendants. Poll's comment relies on terminal status guaranteeing no further process output, but the implementation leaves a window in which children can still write logs or workspace files. terminate() and close() can also return after settle but before release completes.
- Impact: a caller can start post-run stamping or verification while a child is still mutating files, or miss trailing output after treating terminal as final. This is independent of the already-documented test race where output arrives before root exit.
- Possible solutions: terminate/drain the owned process container before publishing a terminal outcome; confirm containment cleanup or report a distinct uncertain state when it fails. Do not merely reorder a fire-and-forget kill and claim all writers are gone.
- Future regression: parent exits with a writing grandchild; block cleanup deterministically and prove no terminal outcome is visible before cleanup is confirmed. Recheck run/check receipt stability in P1.6.5/P3.1.5.

### F-016 - POSIX process groups do not enforce the advertised no-breakaway guarantee

- Task: [P0.6.1](TODO.md#L635).
- Severity: high. Confidence: potential. Status: open.
- Locations: [Os ownership contract](core/src/main/kotlin/io/astrolabe/os/Os.kt#L15), [PosixOwner / PosixProcess.terminateTree](core/src/main/kotlin/io/astrolabe/os/PosixOwner.kt#L17).
- Problem: the POSIX backend owns one process group and kills only that group. A descendant starting another session/group (ordinary daemonizing software can do this) no longer belongs to the killed group. The Os contract nevertheless says no descendant can break away and the code promises the whole tree is terminable.
- Impact: on Linux a detached child may continue writing after cancellation/deadline or root exit. Trusted-local execution avoids claiming security confinement, but does not itself prevent cooperative tools from daemonizing. The already documented survival after harness death is separate and is not a new finding.
- Evidence: inspected the group-based launch/kill path; no Linux runtime reproduction in this session. Validate with a child calling setsid before choosing the final remedy.
- Possible solutions: narrow the supported process-lifecycle contract and reject unsupported daemonizing workloads, or use an OS-supported descendant containment mechanism whose lifecycle matches the promised guarantee. Keep confined-runner work explicitly separate from this ownership decision.
- Future regression: a grandchild creates a new session, then the root exits or is cancelled; it must be terminated or the outcome must explicitly report unsupported/unknown effects.

### F-017 - Git operations can block indefinitely outside the owned-process deadline path

- Task: [P0.6.2](TODO.md#L647); related [P0.6.4](TODO.md#L660).
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [Git.exec](core/src/main/kotlin/io/astrolabe/os/Git.kt#L360), [TempRepo.runGit](core/src/testFixtures/kotlin/io/astrolabe/fixtures/TempRepo.kt).
- Problem: blocking readAllBytes, waitFor and thread joins have no deadline or comprehensive finally cleanup; this path uses ProcessBuilder directly, not LocalOs supervision. Interrupting the coroutine does not automatically stop blocking reads or the git process. A stalled git executable, filesystem or configured Git helper can freeze store opening, stamps and snapshots before the cell's own controls run. Captured output is also unbounded.
- Evidence: direct control-flow inspection. TODO already records intermittent fixture git failure and the absence of timeout; this finding includes the production Git wrapper and does not claim that timeout explains that historical failure.
- Possible solutions: bound the complete command lifecycle including pipe draining, terminate its owned process tree on deadline/cancellation/error, and preserve stderr plus a typed timeout diagnostic. Stream large outputs or apply an operation-specific capture limit without silently truncating authoritative data.
- Future regression: a fake git executable hangs before stdout closes, writes unlimited stderr, or blocks stdin; each operation must terminate with bounded diagnostics and leave no child.
- Current-source recheck (2026-09-25, 9a80e117): Git.exec still blocks on readAllBytes, waitFor and unbounded joins without a deadline (389?427). Earlier runtime evidence retains its original baseline; this recheck is source inspection.

### F-018 - Supported Unicode regex patterns produce different search results by backend

- Task: [P0.6.3](TODO.md#L655).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-27).
- Locations: [JvmSearch.compile](core/src/main/kotlin/io/astrolabe/os/search/JvmSearch.kt#L60), [PatternSubset accepted escapes](core/src/main/kotlin/io/astrolabe/os/search/Search.kt#L250), [RipgrepSearch.baseCommand](core/src/main/kotlin/io/astrolabe/os/search/RipgrepSearch.kt#L47).
- Reproduction: one UTF-8 file containing U+00E9 followed by LF, pattern `^\w+$`, Regex mode and caseSensitive=true. Current compiled code on JDK 26: JVM returns Found with no hits; ripgrep returns the line as a hit. Both claim complete=true and filesSearched=1.
- Problem: Java Pattern is compiled without UNICODE_CHARACTER_CLASS while ripgrep's normal regex semantics use Unicode classes. PatternSubset allows \w/\d/\s and their negations without restricting this difference. This example is Latin-1 and does not fall under the documented beyond-Latin-1 case-folding limitation.
- Impact: installing or removing rg changes which source lines the agent finds, including false complete zero-match results.
- Possible solutions: define one precise character-class policy and align both engines or explicitly reject unsupported semantics. Include word boundaries and Unicode line-separator/dot behavior when validating that policy.
- Future regression: compare both engines on accented letters, non-ASCII digits, spaces, boundaries and line separators; assert actual hit lists, not just that both accept the syntax.
- Fix (2026-09-27, `c406b7a`): JVM matching uses Unicode character classes and explicit LF/CRLF semantics matching ripgrep, including dot and end anchors. Backend comparisons cover accented words, Arabic digits, Unicode whitespace/boundaries and Unicode/CR line separators.


### F-019 - Search output budgets do not bound regex work or file memory

- Task: [P0.6.3](TODO.md#L655).
- Severity: high. Confidence: potential. Status: open.
- Locations: [PatternSubset.scan](core/src/main/kotlin/io/astrolabe/os/search/Search.kt#L314), [JvmSearch.find / scan](core/src/main/kotlin/io/astrolabe/os/search/JvmSearch.kt#L39), [RipgrepSearch.runChunk](core/src/main/kotlin/io/astrolabe/os/search/RipgrepSearch.kt#L83).
- Problem: the subset admits nested repetition such as `(a+)+$` (admission reproduced). JVM matching runs a backtracking engine synchronously with no work/time bound. The fallback reads each entire file and creates its decoded copy and line strings before result-budget enforcement; ripgrep output processing also allows a single unbounded JSON line. Search processes have no integrated deadline. The TODO explicitly acknowledges whole-file reads but leaves their resource consequences unresolved.
- Impact: a repository containing huge files or a long near-matching line can consume excessive heap/CPU or overflow the regex stack, even when the model requests a tiny output budget. No adversarial long-running benchmark was executed.
- Possible solutions: use a bounded/linear-time matcher or a rigorously safer subset; stream input with explicit large-file/line policy and report incompleteness when capped. Execute external search with a deadline. A coroutine timeout alone cannot preempt a synchronous regex loop.
- Future regression: long near-miss nested-repetition inputs and a large single-line file under a tiny output budget must end within explicit work/memory limits with honest status.

### F-020 - Candidate enumeration hides access/I/O failures as complete search

- Task: [P0.6.3](TODO.md#L655).
- Severity: medium. Confidence: confirmed_source. Status: fixed (2026-09-27).
- Locations: [Candidates.resolve / isBinary / walk](core/src/main/kotlin/io/astrolabe/os/search/Search.kt#L596).
- Problem: walk.visitFileFailed always continues; generic IOException during attributes is skipped, and IOException during the binary probe becomes Gone. gitListFiles returns null for every nonzero exit and falls back to a no-ignore tree walk, including failures unrelated to being outside a repository.
- Impact: an unreadable subtree can disappear from candidate enumeration and still yield Found(empty), complete=true; a broken Git invocation can unexpectedly include ignored files. This violates the explicit distinction between complete zero matches, failure and denial.
- Evidence: error branches inspected; current permission test covers an unreadable file selected by enumeration, not an unreadable directory skipped during the walk.
- Possible solutions: propagate denied/failed paths and distinguish true absence from I/O errors. Only use the no-repository fallback after establishing that condition; treat Git failure as failed or explicitly partial.
- Future regression: inaccessible directory, injected read error and failing git inside a repository must not produce a complete negative result or silently broaden ignore scope.
- Fix (2026-09-27, `c406b7a`): Enumeration and binary-probe I/O failures propagate as Failed/Denied. Directory-walk failures are no longer hidden; Git errors inside a detected repository cannot fall back to an unrestricted walk. Corrupt-index regression passes; unreadable-directory regression is present but skipped on Windows.


### F-021 - Candidate filtering checks symlinks only at the final file component

- Task: [P0.6.3](TODO.md#L655); related P1.2.6/P1.6.3.
- Severity: high. Confidence: potential. Status: fixed (2026-09-27).
- Location: [Candidates.resolve](core/src/main/kotlin/io/astrolabe/os/search/Search.kt#L622).
- Trigger: a tracked directory is replaced by a symlink/junction to another directory that contains the same tracked filenames; git's cached file list still supplies those relative paths.
- Problem: readAttributes(NOFOLLOW_LINKS) applies to the final file only; ancestor links are traversed. isBinary and both backends then read the resolved external file without WorkspacePath validation, contrary to the search no-follow contract.
- Impact: direct Search API consumers can receive out-of-workspace content and the process reads bytes outside its declared scope. Look.find rereads returned hit lines through its own readFile path, which may mitigate model exposure; that downstream guard is not yet certified here.
- Possible solutions: resolve each candidate through the canonical WorkspacePath authority and validate all ancestors/containment before opening; retain the documented external-writer race limitation where no handle-based confinement exists.
- Future regression: tracked ancestor swapped to an external symlink/junction must be denied/skipped with honest completeness on both engines; ordinary internal files remain searchable.
- Fix (2026-09-27, `c406b7a`): Search candidates resolve through WorkspacePath before content probes. Link ancestors and outside-root resolutions are denied for both engines. A tracked directory replaced with an external Windows junction is rejected. Concurrent external-writer races remain the documented best-effort path limitation.


### F-022 - Amendment resolution does not validate reply identity or current revision

- Task: [P1.1.3](TODO.md#L692); related P0.4.2/P2.1.2.
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Location: [Contracts.resolve](core/src/main/kotlin/io/astrolabe/contract/Contracts.kt#L120).
- Problem: after authority.resolve suspends, the function acts only on resolution.outcome. Neither proposalId nor contractRevision is checked, current(work) is not reloaded, and pending membership is not revalidated. It applies the pre-suspension Contract object.
- Trigger and impact: an Accepted reply for a different proposal/revision can authorize this change. While a reply is pending, a second same-version proposal/strengthening can be added; replacing/appending from the older snapshot loses that update. A different-version update causes an append/version failure rather than a classified stale reply. A late Rejected reply can overwrite same-version additions as well.
- Evidence: Replies helpers exist but are never called here; tests use only immediately returned correctly matching replies. The production Plan path calls this method.
- Possible solutions: bind every decision to proposal ID and revision; after the await re-read authority and require the same still-pending proposal. Use transactional compare-and-swap on a revision/content generation so same-version strengthening cannot be lost. Apply changes only to the current validated snapshot.
- Future regression: wrong proposal ID, wrong revision, concurrent user amendment and same-version strengthening during a suspended authority reply; no unauthorized change or lost update is allowed.
- Fix (2026-09-26, `1316839`): Amendment replies must match the proposal, revision and still-pending amendment after suspension. Serialized mutations use the latest contract, preserving concurrent strengthening/proposals; resolution memory follows successful persistence. Seven regressions pass.


### F-023 - Resolved amendments are neither persisted nor restored atomically

- Task: [P1.1.3](TODO.md#L692).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-27).
- Locations: [Contracts.resolvedHistory / resolve](core/src/main/kotlin/io/astrolabe/contract/Contracts.kt#L71), [SqliteContractRepository.projection / recordResolved](core/src/main/kotlin/io/astrolabe/contract/SqliteContractRepository.kt#L75).
- Problem: resolve stores the Accepted/Rejected record only in an in-memory map and removes it from amendmentsPending. The SQL projection upserts only still-pending amendments, leaving the old row Pending. recordResolved exists but has no call sites. A new Contracts instance has an empty resolvedHistory. The map is also updated before the contract repository operation succeeds.
- Impact: UI/export can show a resolved amendment as pending; reopening loses the authoritative resolution/provenance used for audit/finish. A failed append can leave memory claiming acceptance without a committed amendment. Historical contract bodies may preserve proposal text, but do not preserve the signed resolution record correctly.
- Possible solutions: persist contract change and amendment resolution in one repository transaction, reload resolved history by work, and derive memory/events after commit. Avoid a separate manually invoked repair method that can be omitted or crash between writes.
- Future regression: accept and reject, close/reopen and compare the contract, amendment table, resolved() and exports. Inject a repository failure before commit and require no accepted resolution in memory or storage.
- Fix (2026-09-27, `8491837`): Contract revisions/projections and final amendment provenance commit in one repository transaction. Resolutions are read from the repository after reopen, with optional work scoping. Accepted/rejected reopen, exports and injected SQL-failure rollback regressions pass.


### F-024 - Contract digest returns over-cap text without a capacity outcome

- Task: [P1.1.4](TODO.md#L699).
- Severity: medium. Confidence: confirmed_source. Status: fixed (2026-09-27).
- Location: [ContractDigest.render](core/src/main/kotlin/io/astrolabe/register/ContractDigest.kt#L21).
- Problem: the reduction loop breaks when the request is short and statuses are exhausted, or after 64 steps, and returns text without a final cap check. Requirement statuses and exclusions are never shortened, so a large valid contract can exceed capTokens even after every allowed reduction. More than 64 historical requests can exhaust the iteration limit before adequate reduction.
- Impact: the advertised 150-token bound is not enforced. A mandatory anchor region can unexpectedly overflow and consume admission headroom; whether a later anchor reducer catches it must be checked at P1.8.3. Dropping mandatory exclusions silently would be an unsafe remedy.
- Possible solutions: compute mandatory minimum size and return a typed capacity failure when it cannot fit; otherwise use a terminating reduction strategy that checks its postcondition. Keep complete mandatory definitions in the appropriate region and document any digest reference strategy explicitly.
- Future regression: many requirements, long exclusions, more than 64 user messages and capTokens=1; result must either satisfy the declared bound or explicitly refuse with an actionable capacity result.
- Fix (2026-09-27, `1e3ef70`): Digest reduction terminates by removing optional content, without the 64-step cutoff. Mandatory overflow throws DigestCapacity; Cell checkpoints a pressure exit before provider dispatch. Tiny caps, long exclusions and 100 historical requests are covered.


### F-025 - Slice coverage forgets acceptance obligations owned only by the increment

- Task: [P1.1.4](TODO.md#L699).
- Severity: medium. Confidence: potential. Status: fixed (2026-09-27).
- Location: [ContractSlice.requiredAcceptanceIds / coverage / forIncrement](core/src/main/kotlin/io/astrolabe/context/ContractSlice.kt#L29).
- Problem: forIncrement correctly unions increment.accept with requirement acceptance IDs, but the resulting slice retains no independent copy of increment.accept. coverage recomputes required IDs only from requirements. Removing an increment-only acceptance definition from a copied/deserialized slice therefore still reports complete=true.
- Impact: the independent coverage check cannot prove its documented contract. The normal factory currently includes the item, so an actual omission needs a later transformation, reconstruction or caller-supplied slice; audit Compiler/Manifest before claiming an end-to-end false-green path.
- Possible solutions: carry the full required acceptance ID set in the slice, or validate against the authoritative Increment at the boundary rather than derive expected coverage from partially retained content.
- Future regression: an increment with an extra acceptance item not listed on its requirements; remove that definition and require coverage to report it missing.
- Fix (2026-09-27, `1e3ef70`): ContractSlice persists incrementAcceptanceIds independently of retained definitions and includes them in coverage. Removing an increment-only acceptance item stays incomplete after copy and JSON round-trip. Legacy slices infer only the IDs still present and should be rebuilt from their authoritative increment.


### F-026 - Failed coherence notification is permanently suppressed on retry

- Task: [P1.2.1](TODO.md#L707); follow-up P1.4.4.
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-27).
- Location: [VersionRegistry.change](core/src/main/kotlin/io/astrolabe/workspace/VersionRegistry.kt#L238).
- Problem: current[path] is advanced before invoking listeners, outside the lock. If an early listener throws, later listeners never receive the change; retrying the same transition immediately returns because the new version is already recorded. Concurrent change calls can also deliver transitions in a different order from their recorded state.
- Impact: after a recoverable coherence/storage error, some horizons can retain stale evidence while the registry claims the transition was announced. Actual serialized runtime mutation reduces concurrent-call exposure but does not repair the exception path. Recheck how Coherence handles partial propagation in P1.4.4.
- Possible solutions: make fan-out completion explicit and retryable/idempotent, or fail the context closed and force reconciliation before any further consequential action; serialize transition delivery in order. Do not mark notification complete before required consumers acknowledge it.
- Future regression: first listener throws once, second tracks staleness; retry/reconciliation must reach every horizon and never certify stale data as current.
- P1.4.4 follow-up: Coherence.onChange adds the pending path then invokes horizons without error isolation/retry. Its KDoc requires horizons not to throw; that documents the precondition but does not enforce cleanup if a real horizon violates it. Assess the actual failing-horizon path before assigning an end-to-end stale-acceptance consequence.
- Fix (2026-09-27, `88ab4f5`): Registry and Coherence retain per-listener delivery progress under serialized transition delivery. Failed callbacks resume before later changes; acknowledged callbacks are skipped; recorded versions advance only after all acknowledgements. Failed listeners must tolerate replay of their own partial effects. Registry/horizon fault regressions and run/edit checks pass.


### F-027 - Raw-byte candidate changes can disappear behind Git clean filters

- Task: [P1.2.2](TODO.md#L716); related [P1.2.3](TODO.md#L723), P1.2.4/P1.7.4.
- Severity: high. Confidence: reproduced. Status: open.
- Locations: [Stamper.trackedDelta](core/src/main/kotlin/io/astrolabe/workspace/Stamper.kt#L220), [DirtyState.capture](core/src/main/kotlin/io/astrolabe/workspace/DirtyState.kt#L216).
- Reproduction: in a new isolated Git repo configure `filter.strip.clean = git stripspace` for `*.txt`; write `hello SPACE LF` and commit, then replace it with equal-length `hello TAB LF`. Raw bytes differ. Fresh compiled Stamper returns equal stamps and empty member sets before/after because both normalize to the same Git blob.
- Problem: raw-byte hashing occurs only for paths reported changed by git status. Git's normalization/clean-filter equivalence determines that membership and can hide a change before the SDK hashes it. DirtyState selects from the same status list, so no recovery entry is captured for this raw change either.
- Impact: candidate equality, change detection and recovery can miss actual source bytes consumed by tools. A prior receipt may look current for a different raw workspace. The documented clean-file export limitation does not make raw stamp equality sound.
- Possible solutions: derive consequential candidate membership from a raw baseline/manifest rather than Git's normalized dirty list alone; capture exact raw content for clean tracked paths when filters or checkout conversion can change it. Fail closed or explicitly exclude unsupported filtered repositories until fidelity is established.
- Future regression: normalization-equivalent raw changes under a real clean filter must alter candidate identity and survive snapshot/materialization. The preliminary LF-to-CRLF probe did change the stamp on this machine; it is not the reproduced counterexample.

### F-028 - Snapshot code follows symlinks before deciding what object to capture

- Task: [P1.2.2](TODO.md#L716); related [P1.2.3](TODO.md#L723), [P1.2.4](TODO.md#L729).
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [Stamper.stampEntry](core/src/main/kotlin/io/astrolabe/workspace/Stamper.kt#L255), [DirtyState.worktreeEntry](core/src/main/kotlin/io/astrolabe/workspace/DirtyState.kt#L285), [WorkspacePath.resolve](core/src/main/kotlin/io/astrolabe/workspace/WorkspacePath.kt#L190), [ShadowRef.currentDigest](core/src/main/kotlin/io/astrolabe/workspace/ShadowRef.kt#L412).
- Problem: WorkspacePath returns a canonical real path following a valid final symlink, while preserving the original link kind separately as resolved.kind. These consumers instead call kindOf(resolved.real), which now describes the target. A valid in-workspace symlink to a file is captured as a regular file containing target bytes; a link to a directory becomes a directory/deletion case. External links are refused and enter the unreadable/deletion path.
- Impact: link target/type identity is lost, changing a link between equal-content targets can be missed, and materialization can turn links into ordinary files. Broken-link behavior differs because canonicalization cannot follow that link. No symlink runtime probe executed on Windows.
- Possible solutions: introduce an explicit no-follow metadata capture path that validates ancestors/containment and reads the final link object/target text; reserve follow-target reads for ordinary content observations. Preserve exact link target spelling on POSIX rather than unconditionally replacing backslashes.
- Future regression: tracked/untracked valid, dangling, relative and directory symlinks must preserve type and link target through stamping and snapshot export; target contents must not stand in for link identity.

### F-029 - Unreadable entries can produce ordinary candidate identities indistinguishable from deletion

- Task: [P1.2.2](TODO.md#L716); related [P1.2.3](TODO.md#L723).
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [Stamper.stampEntry / stamp](core/src/main/kotlin/io/astrolabe/workspace/Stamper.kt#L255), [DirtyState.worktreeEntry / stagedEntries](core/src/main/kotlin/io/astrolabe/workspace/DirtyState.kt#L258), [WorkspacePath.kindOf](core/src/main/kotlin/io/astrolabe/workspace/WorkspacePath.kt#L395).
- Problem: refused/unreadable tracked entries are encoded as Deleted while unreadable untracked entries are dropped. unreadable is report metadata and does not enter Stamp; stamp() discards it entirely. WorkspacePath.kindOf maps every IOException to Missing, and failed staged cat-file reads are silently skipped without adding an unreadable marker.
- Impact: a capture with unknown bytes can look like a valid absent-file candidate. Consumers using only Stamp cannot distinguish complete identity from incomplete acquisition, and recovery can omit staged content without an explicit failure. No permission-induced full acceptance path was executed here.
- Possible solutions: distinguish absent from denied/error/unsupported at capture boundaries and refuse issuance of an authoritative complete stamp/snapshot when required inputs are unresolved; propagate typed incompleteness into verification/reuse. Record failed staged reads as explicit limitations or abort capture.
- Future regression: deny a tracked path, refuse an external link, and inject cat-file failure; no complete candidate or successful recoverable snapshot may result from silently substituted deletions.

### F-030 - Dirty manifest and its recorded stamp are captured from different reads

- Task: [P1.2.3](TODO.md#L723).
- Severity: high. Confidence: potential. Status: open.
- Location: [DirtyState.capture](core/src/main/kotlin/io/astrolabe/workspace/DirtyState.kt#L216).
- Problem: capture reads dirty files into recovery blobs, then separately calls stamper.report which rereads status and file bytes, then separately captures the index. No consistency check binds these reads to one candidate or compares captured manifest hashes with the returned report.
- Trigger and impact: a file changes between its blob read and the stamp read. Snapshot entries describe version A while stampId describes version B; a materialized baseline can execute A while recording B as s0. A snapshot-wide atomic view of external writers is an acknowledged platform limitation, but a known mixed capture must not be certified as one candidate.
- Possible solutions: derive the stamp from the captured immutable manifest and common base/environment inputs; validate membership/base/index before and after acquisition, retry boundedly and mark unresolved movement as unknown. Use isolated snapshots where required.
- Future regression: inject a writer between entry capture and stamping; snapshot identity must match exported bytes or acquisition must fail explicitly, never mix the two versions.

### F-031 - Crash after shadow-ref update leaves the durable snapshot index behind

- Task: [P1.2.4](TODO.md#L729).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [ShadowRef.commit](core/src/main/kotlin/io/astrolabe/workspace/ShadowRef.kt#L324).
- Problem: commit moves the Git ref before publishing the manifest blob and before atomically writing the turn index. There is no persisted intent/recovery step in this class that reconciles a moved ref with the older index. On the next commit, expectedOld is taken from that older index, so updateRef rejects the harness's own newer head. At snapshot 0, reopen can also think there is no initial snapshot while the ref already exists.
- Impact: an ordinary crash/storage failure in this interval can block campaign reopen or all later snapshots. The commit message contains a manifest digest, but the corresponding serialized manifest blob need not have been published yet, so reconstruction is not always available. Controller.open/drift call sites inspected; broader P2 resume review remains pending.
- Possible solutions: publish recovery material before ref movement and persist an intent containing old/new refs and index update; on open reconcile the crash interval without overwriting unrelated ref changes. Keep genuine external CAS conflicts distinguishable from recoverable local publication.
- Future regression: terminate or inject failure after updateRef, after manifest publication and before index rename; reopen must recover exactly one snapshot or explicitly return a recoverable unknown state, preserving snapshot-0 authority.

### F-032 - Snapshot export/restore drops executable modes and validates bytes only

- Task: [P1.2.4](TODO.md#L729); related P0.6.1, P1.2.3/P1.6.4/P1.7.5.
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [ShadowRef.materialize / restore](core/src/main/kotlin/io/astrolabe/workspace/ShadowRef.kt#L229), [DirtyState.worktreeEntry mode](core/src/main/kotlin/io/astrolabe/workspace/DirtyState.kt#L316), [LocalOs.replaceFileAtomically](core/src/main/kotlin/io/astrolabe/os/LocalOs.kt#L162), [MaterializeResult.ok](core/src/main/kotlin/io/astrolabe/workspace/ShadowRef.kt#L72).
- Problem: exported regular files use Files.write without applying entry.mode; restore and ordinary atomic replacement replace the inode with a default-mode temporary file without preserving executable permissions. DirtyState marks every untracked regular file REGULAR because reportedMode is ABSENT, even on POSIX. Validation checks digest alone, not mode/type for all exported paths; limitations do not make ok false.
- Impact: a tracked executable such as gradlew or scripts/check.sh can lose execute permission in a supposedly verified candidate or after an edit/revert. The isolated baseline can fail solely because export changed the filesystem semantics. A symlink fallback can similarly satisfy a byte digest while having the wrong type. POSIX execution was not available in this session; the omitted mode application is visible in source.
- Possible solutions: capture actual supported modes for untracked files, preserve/apply permissions deliberately during publication, and verify type plus mode plus bytes across the entire candidate. Treat unsupported fidelity as unavailable rather than a successful materialization with a warning.
- Future regression: on Linux export, edit and restore a 0755 script, invoke it directly and compare modes; include a symlink whose creation is refused and require an unavailable/mismatch result.

### F-033 - Preimage lookup is lost across cells/restart and its journal index is written after mutation

- Task: [P1.2.5](TODO.md#L736); related P1.4.3/P1.6.4/P1.8.7/P2.2.4.
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [Preimages.saved / saveThenWrite](core/src/main/kotlin/io/astrolabe/workspace/Preimages.kt#L89), [Controller cell construction](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L887), [Cell.journalPreimages](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L588).
- Problem: edit-to-path/preimage/postimage mappings live only in saved. A fresh Preimages instance is created for each cell, with no reload API or journal reconstruction call. Cell writes its serialized preimage index only after the tool has returned, rather than before mutation. Blobs are durable before writes, but the metadata needed to locate and guard them is not durably attached to the edit at that point.
- Impact: an edit alias from a previous cell or resumed session cannot be inverted by the built-in lookup, despite campaign-global aliases and surviving recovery bytes. A crash after mutation but before post-tool journaling can lose the reliable preimage association. Snapshot-based recovery may still exist, but does not fulfill revert-by-edit behavior.
- Possible solutions: persist the typed preimage association before mutation and postimage/result atomically with the action outcome; reload by campaign/edit ID across cells and resume. Keep uncertain postimage outcomes explicit and refuse unsafe inverses.
- Future regression: edit in cell 1 then revert its alias in cell 2 and after reopen; inject a crash after write/before tool return and prove the recovery metadata is discoverable and correctly guarded.
- Current-source recheck (2026-09-25, 9a80e117): Controller creates Preimages per cell (1243); saved remains memory-only; Cell.journalPreimages (625) records mappings only after tool completion (395). Earlier runtime evidence retains its original baseline; this recheck is source inspection.

### F-034 - Selective inverses are limited and their advertised turn fallback is not wired

- Task: [P1.2.5](TODO.md#L736); integration owner P1.6.4.
- Severity: high. Confidence: confirmed_source. Status: resolved_on_recheck.
- Locations: [Preimage / Preimages.revert](core/src/main/kotlin/io/astrolabe/workspace/Preimages.kt#L23), [Edit create/delete/rename and revert paths](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L369).
- Problem: Preimage requires an existing versionBefore; versionAfter=null means unknown/unrecorded, not an explicit absent postimage. Edit create records no preimage, delete leaves versionAfter null, and rename saves only the source preimage without a typed source/destination inverse. The generic RevertEditPlan then calls a function that requires a present postimage and restores only one file's bytes.
- Impact: create aliases are unknown to preimage lookup; delete/rename aliases are found but cannot be reverted even immediately. P1.6.4's Log explicitly documents the create/delete limitation and names turn:N as the fallback, so that portion is a known implementation limitation rather than an undisclosed defect. However, [Controller cell construction](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L895) creates Edit without its optional shadowRef, and Edit refuses every turn:N revert when it is null. The default SDK path therefore cannot use the documented fallback either. Rename also lacks a selective inverse.
- Possible solutions: wire the existing shadow ref to the production Edit path only after closing the current-scope guard gap in F-056; retain explicit documented limits on selective inverses. For full selective support, model before/after as present/absent file states and preserve operation shape/paths with guards for all affected names. Mixed batches require honest partial-effect reporting.
- Future regression: create, delete, rename and mixed batch followed immediately by revert:#id restore exact prior bytes/names/modes; external modification of either rename endpoint must refuse safely.
- Current-source recheck (2026-09-25, 9a80e117): Controller now passes shadowRef = tree.shadow to Edit (1274?1278), and Edit preflight accepts turn:N when a snapshot exists (287?297). The missing fallback wiring is resolved. Create/delete/rename selective inverses remain the previously documented limitation, not a new unresolved defect under this finding. Earlier runtime evidence retains its original baseline; this recheck is source inspection.

### F-035 - Illegal Windows filenames escape the typed path-refusal boundary

- Task: [P1.2.6](TODO.md#L741).
- Severity: medium. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [WorkspacePath.lexicalSegments / resolve](core/src/main/kotlin/io/astrolabe/workspace/WorkspacePath.kt#L190).
- Reproduction: on Windows/JDK 26 `workspace.resolve("bad|name", Intent.Read)` throws InvalidPathException instead of returning PathResolution.Rejected.
- Problem: lexical validation catches a failing Path.of only to continue; candidate.resolve(segment) then executes outside the IOException catch. Illegal platform characters other than NUL therefore bypass the typed refusal protocol.
- Impact: malformed model/host paths can become an unclassified tool/cell exception rather than a recoverable input refusal. They do not bypass containment; the problem is availability and error semantics.
- Possible solutions: catch InvalidPathException at the complete lexical-to-Path conversion boundary and return IllegalCharacter with a bounded diagnostic. Keep accepted POSIX names distinct from Windows-specific invalidity.
- Future regression: Windows-invalid characters, malformed drive/UNC inputs and NUL always yield typed rejection; valid Unicode and supported POSIX punctuation remain accepted on their platforms.
- Fix (2026-09-26, `75e3280`): InvalidPathException becomes an IllegalCharacter refusal; Windows invalid-name regression.


### F-036 - Atlas and command discovery bypass the canonical filesystem boundary

- Task: [P1.3.1](TODO.md#L750); related P1.3.2/P1.3.4/P1.2.6.
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [Atlas scanRepository/readRelative/resolveRelative](core/src/main/kotlin/io/astrolabe/atlas/Atlas.kt#L425), [Sniff.commands](core/src/main/kotlin/io/astrolabe/atlas/Sniff.kt#L102).
- Problem: Git supplies lexical file names, then Atlas uses following readAttributes/readAllBytes without WorkspacePath containment or protected-path validation. Sniff rereads manifests through the same helper. A tracked final symlink or ancestor link can lead outside the workspace or into protected Git metadata; the no-Git tree walk has different link semantics.
- Impact: outside file contents can enter outlines/export names and repository commands, and an external linked package.json can define inferred acceptance commands. Look's guarded reread does not protect the prime or Sniff path. F-021 described the analogous search boundary; this is a separate production consumer.
- Evidence: source data flow through gitListFiles -> scanRepository -> parseAll -> readRelative, and Sniff.commands. No link runtime reproduction in this checkpoint.
- Possible solutions: bind Atlas/Sniff to WorkspacePath and an explicit read policy; preserve a visible unavailable/unsupported entry when safe access cannot be established. Do not follow a manifest link and silently treat its target as trusted in-repository configuration.
- Future regression: tracked link/ancestor pointing outside or at .git must not expose external declarations or commands; permitted internal aliases must use canonical identity.

### F-037 - Atlas cache can return stale declarations after a same-size, same-time rewrite

- Task: [P1.3.1](TODO.md#L750).
- Severity: medium. Confidence: reproduced. Status: open.
- Location: [Atlas.load/save](core/src/main/kotlin/io/astrolabe/atlas/Atlas.kt#L311).
- Reproduction: build/save an atlas for a.py containing def old(); replace old with new (same byte count), restore mtime, then load. The cache returns exports=[old], while a fresh build returns [new].
- Problem: unchanged size/mtime reuses cached hash8, so comparing the reconstructed repoKey against its cached value does not revalidate content. save also records current mtime separately from when row content was captured. Its documentation claims this cannot yield a stale-content cache hit.
- Impact: navigation can reference removed symbols or miss new ones until explicit refresh/rebuild. This remains an orientation defect, not proof of an edit-authority bypass; consequential consumers should continue using raw versions.
- Possible solutions: validate raw hashes before claiming a content-validated cache hit, or explicitly expose a metadata-only freshness hint and revalidate rows on use. Preserve the content acquisition metadata with each row rather than recapturing it when saving.
- Future regression: equal-size rewrite with restored mtime and an edit between build and save must not silently return old declarations as current.

### F-038 - Incremental atlas refresh leaves collapsed totals stale and still scans the whole index

- Task: [P1.3.1](TODO.md#L750).
- Severity: medium. Confidence: reproduced. Status: open.
- Locations: [Atlas.refresh](core/src/main/kotlin/io/astrolabe/atlas/Atlas.kt#L199), [Focus directory rendering](core/src/main/kotlin/io/astrolabe/atlas/Focus.kt#L82).
- Reproduction: build with build/generated.py, delete that file, refresh only its touched path. The incremental result still says Collapsed(build, files=1, bytes=4); a fresh build has no collapsed entry.
- Problem: refresh skips paths with a collapsed ancestor and removes collapsed entries only when their own aggregate path exactly matches touched. It never updates the aggregate for a touched descendant. Separately, every refresh filters/copies/sorts all rows, rebuilds byPath and, for JVM changes, consults all JVM outlines. The documented O(touched) claim covers reparsing only, not total cost.
- Impact: rendered directory existence/size/count can remain stale indefinitely, and repeated small edits have repository-sized update cost. No throughput benchmark was run.
- Possible solutions: maintain per-directory aggregate deltas and update the affected collapsed ancestor; index rows/imports by path and preserve unchanged structure where worthwhile, or honestly budget/document O(repository size) refresh work.
- Future regression: add/delete/resize collapsed descendants and compare incremental to fresh aggregates; measure single-path refresh work as repository size grows.

### F-039 - Curly-language outline extraction has quadratic span-membership work

- Task: [P1.3.3](TODO.md#L763).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [Outline.parseCurly](core/src/main/kotlin/io/astrolabe/atlas/Outline.kt#L499).
- Problem: nestedAt scans every container span and insideBody scans every function span for each source line/declaration. In a file with N independent one-line functions, deciding top-level status performs roughly N squared body-range comparisons. BlockScanner can also be invoked repeatedly for each declaration; the 1 MiB Atlas file cap limits bytes but does not prevent tens of thousands of tiny declarations.
- Impact: initial Atlas.build or a touched-file refresh can spend disproportionate synchronous CPU on a valid compact generated/source file, despite bounded model output. No timing claim or adversarial stress run is made.
- Possible solutions: sweep sorted span boundaries while parsing, use an interval stack/index, or precompute membership once; retain explicit tier-0 incompleteness and a parser work budget for unusually complex files.
- Future regression: files with increasing counts of independent declarations should show approximately linear membership work; malformed nesting must terminate under the same budget.

### F-040 - Direct node test inference drops declared npm lifecycle checks

- Task: [P1.3.4](TODO.md#L769); related P1.1.2/P1.7.1.
- Severity: high. Confidence: reproduced. Status: open.
- Location: [Sniff.packageJson](core/src/main/kotlin/io/astrolabe/atlas/Sniff.kt#L152).
- Reproduction: package.json has pretest=node prepare.js, test=node --test and posttest=node check.js. Sniff returns only [node, --test].
- Problem: a bare test script is optimized into the direct runner without checking lifecycle hooks or npm-provided execution environment. npm test runs pretest, test and posttest; bypassing npm omits required setup and post-test checks. See [official npm script lifecycle](https://docs.npmjs.com/cli/v11/using-npm/scripts/#npm-test).
- Impact: an auto-derived acceptance command can test a different contract from the repository's declared test entry point, including skipping a failing posttest quality gate or running against stale generated fixtures.
- Possible solutions: keep the package-manager test entry point by default; optimize only with an explicit proven-equivalent environment/hook policy, and record that policy as part of check identity.
- Future regression: a failing pretest/posttest must prevent acceptance just as npm test does; generated setup and lifecycle environment must be preserved.

### F-041 - Sniffed Gradle wrapper commands have no executable-resolution implementation

- Task: [P1.3.4](TODO.md#L769); related P1.6.5/P1.7.2.
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [Sniff.gradle/hasWrapper](core/src/main/kotlin/io/astrolabe/atlas/Sniff.kt#L190), [TrustedLocalRunner.start](core/src/main/kotlin/io/astrolabe/tool/run/Runner.kt#L25), [WindowsOwner.renderCommandLine](core/src/main/kotlin/io/astrolabe/os/WindowsOwner.kt#L161), [PosixOwner.start](core/src/main/kotlin/io/astrolabe/os/PosixOwner.kt#L29).
- Problem: Sniff returns the bare executable gradlew even when the wrapper is in an ancestor directory. Its KDoc promises translation to ./gradlew or gradlew.bat, but the production runner passes the argv unchanged to native launch. No gradlew resolver exists in the main source tree. The Windows native Argv path also does not select a command interpreter for .bat/.cmd launchers.
- Impact: common Gradle repositories cannot run their inferred acceptance on POSIX PATHs without the current directory, and Windows cannot use the extensionless shell wrapper as an executable. Subpackage cwd loses the known ancestor wrapper location. Fixture Runners supplies its own launcher, so green fixture tests do not establish production command viability.
- Possible solutions: resolve/pin a platform-specific launcher and its exact path when discovering the manifest, retaining package cwd; provide a deliberate safe batch-file execution strategy on Windows. Apply the same check to other package-manager launchers rather than relying on shell behavior in a direct native spawn.
- Future regression: execute the inferred command from a child Gradle project with a root wrapper on both platforms and no globally installed Gradle; verify no PATH-dependent alternate program is selected.

### F-042 - Bounded journal search deserializes the entire campaign history

- Task: [P1.4.1](TODO.md#L775).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [Journal.events/search](core/src/main/kotlin/io/astrolabe/evidence/Journal.kt#L94).
- Problem: even with a context/kind scope and limit=1, events() selects every body for the work, deserializes every JSON payload under the single DB lock, then filters scope in memory. search lowercases/scans all matches and only applies limit after allocating the full matching list.
- Impact: lookup latency and heap grow with complete campaign history and full payload size, not the requested bounded result. While Db.query maps rows, other journal/checkpoint writers are blocked. The TODO explicitly defers FTS, but the current full-body loading is avoidable without FTS.
- Possible solutions: push context/kind predicates into SQL; use a paginated or streaming text/ref scan with limit+1 to establish completeness, and load full bodies only for returned records. Add a text-view index/FTS if measured workloads justify it.
- Future regression: a narrow search over a large multi-context history should deserialize only the necessary records and return correct complete=false once an extra match is found; measure DB lock occupancy as well as result count.

### F-043 - A contradictory failed execution can be represented as a green receipt

- Task: [P1.4.2](TODO.md#L782).
- Severity: medium. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Location: [Receipt init / greenForFinalTree](core/src/main/kotlin/io/astrolabe/evidence/Receipt.kt#L123).
- Trigger: construct Outcome.Passed with Counts(failed=1), stable tested inputs and optionally a nonzero exitCode. It satisfies the nonempty-count check and greenForFinalTree returns true.
- Problem: the constructor checks for some parsed activity but never checks that passed receipts have no failures/errors or contradictory process exit. Kotlin/JSON construction can therefore certify inconsistent evidence.
- Impact: a malformed producer/adapter result can cross the nominal receipt invariant and be treated as green. This is a defensive contract gap; the normal parser paths still need separate inspection, and this finding does not claim the model can directly create trusted receipts.
- Possible solutions: validate universally contradictory pass/failure fields at receipt construction; where checker counts differ from test counts, model that distinction explicitly instead of inferring success merely from discovered>0. Preserve intentionally inconclusive/unknown inputs as non-green.
- Future regression: failed/errors>0 or an incompatible nonzero exit cannot produce a Passed final-tree receipt; legitimate compiler/linter passes with discovered files remain supported.
- Fix (2026-09-26, `f060c97`): Passed receipts reject failures, errors and exits contradicting the declared expected exit. QA persists expected CLI exits and keeps HTTP status out of process-exit metadata (follow-up `3f433f6`); receipt and QA regressions pass.


### F-044 - Unknown intents cannot transition to reconciled completion

- Task: [P1.4.3](TODO.md#L788); related P1.9.2/P2.2.4.
- Severity: high. Confidence: reproduced. Status: open.
- Locations: [InMemoryIntentJournal.update](core/src/main/kotlin/io/astrolabe/evidence/Intent.kt#L59), [SqliteIntentJournal.update](core/src/main/kotlin/io/astrolabe/evidence/StoredEvidence.kt#L21), [Controller.open reconciliation](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L379).
- Problem: transition validity is ordinal-based and Unknown is declared after Committed. Once marked Unknown, the only accepted next state is Unknown. The API has no separate reconciled terminal disposition. Conversely Committed -> Unknown is allowed, reopening a completed action.
- Impact: a normal crash-recovery path marks open intents Unknown permanently; even an authority which has established the outcome cannot close one through IntentJournal.update. Run then continues refusing the same non-replay-safe command forever. Existing reopen test jumps directly from Dispatched to Committed and misses the actual Controller path.
- Possible solutions: replace ordinal ordering with explicit allowed transitions and a recorded reconciliation result; permit a proven unknown outcome to close without pretending the original observation existed, and keep committed terminal states immutable.
- Future regression: Recorded -> Dispatched -> Unknown -> explicit reconciled completion across reopen; unknown side effects must stay blocked until supporting evidence/authority is persisted, then become resolved. Committed must not reopen accidentally.
- Runtime evidence: corrected stdin JShell probe against current classes raised `intent i cannot move from Unknown to Committed` after successfully recording and marking i Unknown. The earlier ambiguous Java import probe was discarded.
- Current-source recheck (2026-09-25, 9a80e117): Both intent journals still enforce ordinal transitions, while Controller marks unresolved intents Unknown (501); reconciliation cannot transition Unknown to Committed. Earlier runtime evidence retains its original baseline; this recheck is source inspection.

### F-045 - A newly added verified fact is not checked for already-stale anchors

- Task: [P1.5.2](TODO.md#L809); related P1.4.4/P1.8.7.
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [Validator FactAdd](core/src/main/kotlin/io/astrolabe/register/Validator.kt#L103), [Register.markStale](core/src/main/kotlin/io/astrolabe/register/Register.kt#L131), [StateTool.patch](core/src/main/kotlin/io/astrolabe/tool/state/StateTool.kt#L126).
- Problem: FactAdd(Verified) validates only evidence ID existence, then creates Fact with staleAt=null even if its supplied anchor points to an old version. markStale acts only on subsequent version-change notifications, so a change which occurred before insertion will never mark this new fact. The validation interface cannot ask whether an anchor/evidence is current.
- Trigger and impact: retain an old observation, edit the file, then add a verified fact anchored to the old version and that valid observation ID. STATE renders it as fresh v until another change or rebuild. A gate looking only at Fact.stale cannot warn about reliance on this obsolete fact. This is a freshness defect, not a claim that the harness can mechanically prove all fact text.
- Possible solutions: initialize fact freshness from Coherence.serve/current raw versions and evidence provenance when accepting the patch; retain historical facts with a harness-owned stale/unknown marker. Do not let a model-provided old anchor reset freshness by adding a new fact.
- Future regression: after an anchor changes, add a verified fact with an existing historical observation; it must render stale/unknown immediately and trigger applicable stale-fact controls.

### F-046 - STATE line and code-fence validation omits auxiliary rendered fields

- Task: [P1.5.2](TODO.md#L809).
- Severity: medium. Confidence: reproduced. Status: open.
- Locations: [Validator.opText](core/src/main/kotlin/io/astrolabe/register/Validator.kt#L176), [RegisterRender.markdown](core/src/main/kotlin/io/astrolabe/register/RegisterRender.kt#L45).
- Reproduction: DecisionAdd with short text and a fenced-code because value, plus Next, returns Validation.Applied. The fenced content is then rendered verbatim in STATE.
- Problem: per-line/fence validation checks only one selected text field per operation. because/rejected/probe, dead-end scope/reopen, open trip/needs, plan accept/req and focus can inject fences or additional lines without this check. The total patch/register cap still applies; it does not enforce the advertised line/format invariant.
- Impact: the supposedly harness-shaped STATE can contain arbitrary extra Markdown blocks or oversized lines, obscuring the visible meaning of decisions/open items. This does not directly alter executable acceptance authority.
- Possible solutions: validate every field rendered as a line according to its own type, including newline/fence handling; render structured values with a consistent escaping policy where multiline content is legitimate.
- Future regression: put fences/newlines and overlong text in every auxiliary rendered field, not only FactAdd.text; reject or safely render it while preserving ordinary quoted command text.

### F-047 - Recalling a missing file grants KNOWN coverage despite a historical label

- Task: [P1.5.3](TODO.md#L815); related P1.6.3.
- Severity: medium. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Workset.recall](core/src/main/kotlin/io/astrolabe/workset/Workset.kt#L130), [Look.recall](core/src/main/kotlin/io/astrolabe/tool/look/Look.kt#L330).
- Reproduction: recall an Entry at version v with currentVersion=null. It returns RecallResult.Known and covers(path,v,range) becomes true.
- Problem: null currentVersion is treated as a match, although VersionRegistry uses null for missing/refused/unreadable paths. Look later changes only the text/status to historical; it has already set known=true, registered coverage with the registry and will persist nonempty observation ranges.
- Impact: the current projection/export contains historical coverage while the user/model sees a not-KNOWN label. CAS still prevents editing an absent or different-version file, which limits the direct write risk; the persisted coverage/freshness contract is nevertheless wrong.
- Possible solutions: make missing/unknown current versions a distinct non-KNOWN recall result and decide freshness before any Workset/registry/observation mutation. Historical bytes can remain visible with empty edit coverage.
- Future regression: read, delete, recall and inspect Workset, VersionRegistry and stored Observation; all must retain historical content without current coverage. Repeat with denied/unreadable paths.
- Current-source recheck (2026-09-25, 9a80e117): Workset.recall still registers Known for null currentVersion (136); Look applies the historical label only after coverage registration. Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `d7551cc`): Recall with no current file version is Historical and grants no Workset coverage.


### F-048 - Mixed edit operations lose their individual masks and dependency checks

- Task: [P1.6.1](TODO.md#L823); related P1.6.2/P1.6.4.
- Severity: high. Confidence: reproduced. Status: open.
- Locations: [ToolCall.condition / ToolCalls.opName](core/src/main/kotlin/io/astrolabe/tool/ToolCall.kt#L31), [Partition.of](core/src/main/kotlin/io/astrolabe/tool/Partition.kt#L66), [Edit.execute/run](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L162).
- Problem: an edit call is named by its first operation (unless any transform exists), and condition exposes only the first non-null condition. Cell/Executor mask checks see that single name; Partition validates that one condition; Edit never evaluates per-operation conditions.
- Reproduction: edit call 1 creates a; edit call 2 contains create b if applied(op:1) and create c if green(op:3); run call 3 is later in execution order. Partition accepts Ordered, silently missing the illegal forward dependency of c.
- Impact: a later operation can execute despite an unmet/unvalidated condition. A role mask permitting only edit.create can also admit a create-first batch containing delete/rename/revert; the outer contract scope guard remains separate and does not enforce the operation mask.
- Possible solutions: validate the mask and all dependency predicates for every sub-operation before effects, and evaluate conditions at their permitted execution boundary. If mixed conditional semantics cannot be supported atomically, reject them explicitly instead of collapsing metadata.
- Future regression: denied second op, malformed/forward second condition, and a false prerequisite must reject or produce explicit nonexecuted dispositions without world effects.
- Current-source recheck (2026-09-25, 9a80e117): ToolCall.condition still exposes only first non-null edit condition; Partition and Dispatcher consume that single condition. Edit.run validates operations/scopes but does not enforce per-op conditions/masks. Earlier runtime evidence retains its original baseline; this recheck is source inspection.

### F-049 - Parallel look execution mutates a non-thread-safe Workset

- Task: [P1.6.2](TODO.md#L830); related P1.5.3/P1.6.3.
- Severity: high. Confidence: potential. Status: fixed (2026-09-26).
- Locations: [Dispatcher read async phase](core/src/main/kotlin/io/astrolabe/tool/Dispatcher.kt#L145), [Workset live/register/entries](core/src/main/kotlin/io/astrolabe/workset/Workset.kt#L78), [Look read/show](core/src/main/kotlin/io/astrolabe/tool/look/Look.kt#L173).
- Problem: admitted reads run as async children on the caller's dispatcher. With a multithreaded dispatcher, multiple Look executions inspect and mutate the same ArrayList-backed live state via unsynchronized iteration, indexOfFirst, replacement and append. The registry maps and SQL alias allocation are synchronized, but the Workset is not.
- Impact: concurrent reads can lose entries, observe inconsistent coverage or throw during iteration/copy. Missing coverage can reject legitimate later edits or cause repeated reads; corrupted/mixed state must not be used for authority. No nondeterministic failure was forced in this checkpoint.
- Evidence: shared state and dispatcher data flow inspected. DispatcherTest uses runTest's normal single-thread scheduling and its fake synchronous reads do not exercise simultaneous ArrayList mutation.
- Possible solutions: serialize Workset publication after parallel acquisition, or provide an atomic synchronized state boundary covering reads/registration/snapshots; preserve the pre-dispatch snapshot rule.
- Future regression: real parallel look completions under Dispatchers.Default with coordinated barriers, repeated snapshots and exact coverage assertions; all successful displayed results must appear exactly once.
- Current-source recheck (2026-09-25, 9a80e117): Dispatcher still runs read calls with async (140); Workset live/stale/drops remain unsynchronized ArrayLists shared with Look. Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `d7551cc`): All Workset collection access is synchronized; concurrent registrations and snapshots preserve all entries.


### F-050 - Recall loses the mapping between blob lines and source lines

- Task: [P1.6.3](TODO.md#L836).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [Look.recall](core/src/main/kotlin/io/astrolabe/tool/look/Look.kt#L293).
- Reproduction: read/capture source lines 1-200 with a small display budget; recall #1 range 20-22 creates #2 displaying lines 20-22 correctly. Recall #2 range 20-22 displays original blob lines 1-3 instead, while selectedSource and coverage still refer to 20-22.
- Problem: the recalled observation reuses the original full contentRef but stores only its newly displayed source ranges. A later recall treats ranges.first.from as the original blob's line origin. Single-file search observations with contiguous hit ranges are also treated as raw contiguous source blobs, although the blob starts with a summary and may have a different layout.
- Impact: Workset/registry can certify source lines the response did not actually show, violating the region-seen edit precondition. This is an authorization-data mismatch, not only wrong display numbering.
- Possible solutions: persist an explicit immutable mapping from each captured body line to source path/line/version, distinct from the subset displayed this time; alternatively store the actual recalled slice with its correct origin. Structural/search bodies must not be inferred as raw source from range shape.
- Future regression: repeated recalls of partial recalls, one-hit searches, sparse/multi-file search results and cropped redacted views must show exactly the source lines they grant coverage for.
- Current-source recheck (2026-09-25, 9a80e117): Look.recall derives blob origin from observation.ranges and reuses the original contentRef on the narrowed recalled observation (318?395). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `d7551cc`): A narrowed recall persists its selected blob, preserving source coordinates on subsequent recalls.


### F-051 - Redaction masks beyond the initial display are lost before recall

- Task: [P1.6.3](TODO.md#L836).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Look.read observation mask](core/src/main/kotlin/io/astrolabe/tool/look/Look.kt#L184), [Look.recall coverage](core/src/main/kotlin/io/astrolabe/tool/look/Look.kt#L330).
- Reproduction: a three-line file has a synthetic secret on line 2. Read range 1-3 with budget=1 so only line 1 displays, then recall line 2. The body contains a REDACTED placeholder, yet Workset.covers(line 2) returns true.
- Problem: the full captured text is redacted, but Observation.redaction is intersected with the initially displayed range. Recall later relies on that cropped mask when granting newly displayed coverage. It cannot know that a previously undisplayed captured line was masked.
- Impact: hidden source bytes can become editable as if they were shown, and redactionApplied metadata may become false for a visibly redacted recall.
- Possible solutions: persist the full capture redaction/source mapping and separately track displayed ranges; derive coverage as displayed minus full captured hidden lines at every read/recall/seed boundary.
- Future regression: secret before/after the first display cutoff and multiple successive partial recalls must never grant coverage to hidden lines; ordinary unredacted recalled lines still become known.
- Current-source recheck (2026-09-25, 9a80e117): Look.read persists only hidden.intersect(displayed) as redaction mask (210); recall grants new coverage from that truncated mask. Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `d7551cc`): Read observations retain the full captured redaction mask; recalling a hidden tail grants no coverage.


### F-052 - A single long source line bypasses the requested observation budget

- Task: [P1.6.3](TODO.md#L836); related P1.6.2/P1.8.6.
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [Look.fit](core/src/main/kotlin/io/astrolabe/tool/look/Look.kt#L404).
- Problem: fit keeps at least one line even if that line alone exceeds budget. A whole-file request has an early refusal, but a range/symbol/search/recall request can reach fit with a huge first line. Returned tokens count kept content but excludes added recall/truncation text, and refusals can render an unbounded outline without applying the requested budget.
- Impact: a read reserved for a small number of tokens can introduce a much larger body, overrun Reservations and pressure/capacity controls, or crowd out useful context. The redaction scan cap bounds some captures but is far above the ordinary read budget.
- Possible solutions: refuse with a bounded structural hint when no whole line fits, or support a clearly partial character view which grants no full-line coverage; count all rendered result contributions and bound error/outline hints.
- Future regression: very long first lines and a large declaration outline under a tiny read budget must stay within the promised budget or return a bounded capacity/refusal result with honest coverage.
- Current-source recheck (2026-09-25, 9a80e117): Look.fit (507) retains an over-budget first line; whole-file refusal/outline rendering remains outside that budget bound. Earlier runtime evidence retains its original baseline; this recheck is source inspection.

### F-053 - Multiple operations on one path can overwrite earlier successful edits in the same batch

- Task: [P1.6.4](TODO.md#L843).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Edit.run preflight list](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L221), [Edit.apply AnchoredPlan](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L355).
- Reproduction: src/b.py starts x=1, y=2. Two anchored operations use the same valid initial expect: first changes x to 3, second changes y to 4. The tool returns applied=true for the batch, but the final file is x=1, y=4.
- Problem: each plan captures oldText/oldBytes independently before any writes. apply reconstructs each full file from that old snapshot; it does not merge operations for a path or recheck an in-batch postimage. Path conflicts across create/delete/rename targets are similarly not globally preflighted.
- Impact: previously reported successful work is silently lost; per-op views/versions/preimage bookkeeping can describe intermediate content that is no longer present.
- Possible solutions: reject repeated/conflicting canonical paths before effects, or compile all compatible hunks per file into one validated final image with one preimage and coherent post-view. Cross-path rename/create/delete dependencies need explicit semantics.
- Future regression: disjoint anchored operations on the same file, overlapping aliases/case spellings, duplicate create targets and rename-target conflicts must be combined correctly or rejected atomically.
- Current-source recheck (2026-09-25, 9a80e117): Edit preflights all operations before mutation (247), applies each complete plan.oldText (379) and revalidate checks path identity only (522), so same-path snapshot clobber remains. Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `6e4e671`): Canonical edit paths, including rename targets, cannot appear in multiple operations; reverts run alone. Conflict regressions refuse before any write.


### F-054 - Normalized anchor mapping duplicates indentation and trailing newlines on replacement

- Task: [P1.6.4](TODO.md#L843).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Anchors.normalized/normalize](core/src/main/kotlin/io/astrolabe/tool/edit/Anchors.kt#L74), [Edit.replace](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L504).
- Reproduction: original is four spaces + return + four spaces + 1 + LF; anchor is four spaces + return 1 + LF; replacement is four spaces + return 2 + LF. The normalized span is start=4,end=15; applying Edit's replacement operation leaves eight leading spaces and two newlines.
- Problem: normalization drops leading whitespace and trim removes outer whitespace/newlines from the anchor. The origin map only spans the remaining characters, while the replacement still contains its complete intended indentation/newline. Prefix/suffix bytes excluded from the matched span are retained around it.
- Impact: a whitespace-tolerant edit can change Python block structure or produce syntax errors despite matching the intended unique line. Inline syntax reports errors after mutation and cannot repair the semantic mistake.
- Possible solutions: retain start/end boundaries for the complete original span represented by the anchor, including its intended leading/trailing whitespace, or define and apply a consistent replacement normalization contract. Do not merely trim the replacement, which would destroy meaningful indentation.
- Future regression: tabs/spaces, leading indentation, CRLF/LF and anchors with terminal newlines should replace exactly the intended raw span and preserve surrounding bytes.
- Current-source recheck (2026-09-25, 9a80e117): Anchors.normalized still trims normalized anchor and maps only retained characters (69); Edit.replace inserts complete replacement text (528). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `c2f6989`): Normalized anchor spans include requested boundary indentation and line endings, including whitespace after the final newline. Tabs, CRLF/LF, inline surroundings and end-to-end replacements are covered.


### F-055 - Edit error diagnostics expose raw secrets to the model and reusable storage

- Task: [P1.6.4](TODO.md#L843); related P1.10.3.
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Edit.current stale diff](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L326), [Anchors.nearest](core/src/main/kotlin/io/astrolabe/tool/edit/Anchors.kt#L135), [Edit.render](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L546).
- Reproduction: after observing an old src/b.py version, add a line containing the public synthetic AWS-style fixture token and issue an edit with the stale expect. The refused outcome body contains that token verbatim.
- Problem: stale diffs and nearest-anchor candidates are built from raw recovery/current bytes, inserted into error.detail, then joined into the body and persisted without a whole-body redaction pass. Only post-edit view text is individually redacted.
- Impact: a refused edit can leak previously unseen secret-bearing lines into the model transcript and general OUTPUT blobs; delimiter escaping and instruction-shape flags do not remove secrets. No real credential was used in the probe.
- Possible solutions: pass every model-facing/reusable diagnostic through the redaction boundary before output or persistence, while retaining exact raw preimages only in restricted recovery storage. Bound diagnostic captures and carry their limitations/masks honestly.
- Future regression: stale diffs, no-match candidate lists, syntax errors and partial-write errors containing fixture secrets must be redacted in both returned bodies and stored reusable blobs.
- Current-source recheck (2026-09-25, 9a80e117): Anchors.nearest returns raw lines; Edit.current builds raw stale diffs; Edit.render joins error.detail and persists body without whole-body redaction (558?600). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `6e4e671, da8bc42`): The entire edit report is redacted before return and persistence. Redacted or capped reports grant no new post-edit coverage; composite observations carry no source ranges. Diagnostic and coverage regressions pass.


### F-056 - Turn reverts do not preflight the current contract's write scope

- Current-tree recheck (2026-09-25): still present; Edit.revertPaths returns an empty list for turn: targets at Edit.kt:256, so current ScopeGuard preflight cannot inspect those paths.

- Task: [P1.6.4](TODO.md#L843).
- Severity: high. Confidence: potential. Status: fixed (2026-09-26).
- Locations: [Edit.revertPaths](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L229), [Edit RevertTurnPlan application](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L424), [ShadowRef.restore](core/src/main/kotlin/io/astrolabe/workspace/ShadowRef.kt#L191).
- Problem: revertPaths returns an empty list for turn:N, so ScopeGuard has no paths to check. ShadowRef.restore uses the workspace's static path protection but knows nothing about the current committed contract/increment scope.
- Trigger and impact: an embedding host supplies shadowRef to Edit; after an amendment narrows scope, reverting an older turn can write paths no longer authorized by the contract. The default controller currently omits shadowRef (F-034 follow-up), so this path is not reachable through that default wiring yet; wiring the fallback alone would activate this risk.
- Possible solutions: expose/compute the full canonical restore write/delete set and run current authority/scope checks before any restore publication; revalidate authority generation if resolution can suspend.
- Future regression: create snapshots under broad scope, narrow committed scope, request turn revert, and assert refusal with zero changes outside the new scope.
- Fix (2026-09-26, `742d834`): Turn-revert scope preflight uses the exact snapshot tree difference, including clean-at-target files, creations and deletions. Current contract scope and increment warnings/justification apply before restoration.


### F-057 - Post-write failures can be reported as if nothing was written

- Task: [P1.6.4](TODO.md#L843).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Locations: [Edit.apply](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L355), [Edit.ioError/render](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L490).
- Problem: applied bookkeeping is added only after several post-mutation steps. For rename, target publication happens before source deletion and before either AppliedOp is recorded; for anchored/create, postimage/blob/coherence work can fail after bytes are published but before the list is updated.
- Trigger and impact: source deletion fails after a rename target was written, or revalidation/postimage persistence fails after a file replacement. ioError can say nothing was written and the header can say effects=None even though filesystem effects exist; RuntimeFields.effectsUnknown only partially compensates. Later cell reconciliation may discover changes, but the per-operation recovery evidence and immediate outcome are inaccurate.
- Possible solutions: record each publication boundary as soon as it occurs, and on failure inspect affected paths against preimages/planned postimages before constructing the result. Distinguish preflight refusal, partial publication and unknown effects consistently; preserve recovery references even if later bookkeeping fails.
- Future regression: fail after target rename publication/before source deletion, after file replacement/before postimage row and inside coherence; outcomes must name actual or explicitly unknown effects and never claim no writes.
- Current-source recheck (2026-09-25, 9a80e117): Edit records AppliedOp after publication/postimage/registry work; rename writes target before deleting source. ioError still infers effects from the incomplete applied list (515). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `742d834`): Publication bookkeeping precedes postimage persistence and coherence. Partial rename targets retain recovery references; failed post-write revalidation and transform observation report partial/unknown effects. Failed symlink restoration cannot claim unchanged bytes. Fault-injection regressions cover persistence, coherence, rename, transform observation and selective revert.


### F-058 - Run/edit result aliases do not resolve to their stored observations

- Task: [P1.6.4](TODO.md#L843); related [P1.6.5](TODO.md#L851), P1.6.3/P1.8.6.
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Edit.execute/render](core/src/main/kotlin/io/astrolabe/tool/edit/Edit.kt#L164), [Run alias allocation/render](core/src/main/kotlin/io/astrolabe/tool/run/Run.kt#L188), [Look.recall lookup](core/src/main/kotlin/io/astrolabe/tool/look/Look.kt#L293), [Cell.appendResult](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L546).
- Problem: run allocates its alias against actionId, edit against editId, but both persist their Observation with a fresh unrelated obs ID. Look.recall and Cell recall-pointer construction resolve alias.canonicalId directly as an observation ID.
- Reproduction: a completed real fixture run returns #1 -> act-1; SqliteObservations.get(act-1) is null despite a stored obs-1. Its advertised log recall cannot resolve.
- Impact: truncated logs/post-edit results cannot be recalled by the provided alias; Cell cannot attach a valid RecallPointer for later eviction. The content blob existing is insufficient without the link.
- Possible solutions: retain the edit/action alias semantics but add an explicit result-observation association, or align canonical result IDs consistently. Resolve recall by that relationship rather than assume every alias identifies an observation row.
- Future regression: every run/edit alias with a stored view/log must support recall before/after eviction and across cells; edit aliases must still resolve correctly for selective revert.
- Current-source recheck (2026-09-25, 9a80e117): Run alias names actionId (186), Edit alias names editId; render stores fresh obs IDs (Run 493, Edit 591), while Look.recall resolves canonicalId directly. Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `742d834`): New edit observations use their edit alias identity. Observation lookup resolves exact IDs first, then the latest result for an action ID, preserving historical poll observations. Run/edit recall, edit revert and repeated-poll regressions pass for SQL and memory stores.


### F-059 - Foreground and terminal background run views bypass redaction

- Task: [P1.6.5](TODO.md#L851); related P1.10.3.
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Run.finish](core/src/main/kotlin/io/astrolabe/tool/run/Run.kt#L278), [Run.poll terminal branch](core/src/main/kotlin/io/astrolabe/tool/run/Run.kt#L333), [Run.render](core/src/main/kotlin/io/astrolabe/tool/run/Run.kt#L392).
- Reproduction: a fixture file contains a synthetic AWS-style token; run executes type emit.txt (the command contains no secret). The returned body contains the token verbatim, although the stored LOG blob was separately redacted.
- Problem: raw output is passed into Shapers, whose view includes raw head/tail/errors, then rendered without redacting the final view. Redacting only the saved log does not protect the model transcript. Command/scope/error strings are likewise rendered raw, and redactionApplied is hard-coded false.
- Impact: normal successful/failed runs can leak secrets to model/native history. A redaction scan cap can also shorten the stored log while the raw parser/view and captureComplete metadata still describe a full capture.
- Possible solutions: parse trusted structured outcome from captured bytes as needed, but redact every exposed/persisted reusable view at the final boundary; propagate the redaction/capture limitations and preserve exact raw data only where its storage policy allows it.
- Future regression: synthetic secrets in stdout/stderr, parser failures, command arguments and terminal polls must not appear in model-facing text; large redaction-limited logs must not claim fully recallable capture.
- P1.6.7 follow-up: Verify.runOne repeats the same pattern (redacts only the saved blob, then shapes observed.output and returns the raw shaped view). Include acceptance/baseline verification output in the fix scope, not only Run.
- Current-source recheck (2026-09-25, 9a80e117): Run.finish and terminal poll shape raw capture; render concatenates result.view unchanged and hard-codes redactionApplied=false (354?511). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `b5ae2f3, 2b30ba6`): Run foreground/background/poll/refusal views and Verify output are redacted at their output boundaries. Stored log masks and scan limitations propagate into run capture metadata; secret and bounded-log regressions pass.


### F-060 - D-class approval is accepted without matching identity or rechecking current authority

- Task: [P1.6.5](TODO.md#L851); related P0.4.2/P1.9.4.
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Location: [Run.run approval-to-dispatch path](core/src/main/kotlin/io/astrolabe/tool/run/Run.kt#L172).
- Problem: after authority.approve suspends, Run checks only approval.approved. requestId and contractRevision are ignored; the pre-await contract/ceiling/classification are used without rereading current authority.
- Impact: a late or mismatched approval can authorize a different D-class action, or an action whose permission was revoked/narrowed while approval was pending. Replies validation exists but is not used here; an earlier cell-level pre-dispatch guard does not cover the suspension interval.
- Possible solutions: bind approval to request ID, exact command/effect and revision/generation; revalidate committed authority and cancellation immediately before the consequential dispatch boundary.
- Future regression: wrong request ID/revision and a contract revocation/cancel during a suspended approval must leave no launched process or external effect.
- Current-source recheck (2026-09-25, 9a80e117): Run.authorize now shares logic with mounts but still checks only approval.approved, with no request/revision/current-authority validation after await (240?257). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `e4e23eb`): D-class replies must match request ID, request revision and current contract after the await; coroutine cancellation is checked before proceeding. Wrong identity/revision and authority-change regressions prevent dispatch.


### F-061 - Run commits its intent before durable handle/result evidence exists

- Task: [P1.6.5](TODO.md#L851); related P1.4.3/P1.9.2.
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-27).
- Location: [Run.run Consequential callback and outcome handling](core/src/main/kotlin/io/astrolabe/tool/run/Run.kt#L198).
- Problem: Consequential.persist publishes only a log blob, then commits the intent. For a background launch, Handles.save runs afterwards; for a foreground launch, stamp reconciliation, structured result and Observation persistence happen afterwards.
- Trigger and impact: crash or storage failure after intent commit but before handle/result save. The child may still run, yet the store has no handle association and the intent is absent from open-intent reconciliation. A sidecar alone is not scanned/rebound by the observed Controller.open path. The same command can therefore escape the unknown-effect retry block despite an unresolved launch.
- Possible solutions: durably persist the process handle and enough observed result/recovery metadata before committing the intent; keep background actions open or explicitly terminally classified according to their lifecycle. Include a recoverable intent-to-sidecar link at launch.
- Future regression: inject faults after log publication, at intent commit and before handle/result insertion; reopen must recover ownership/outcome or preserve an unknown effect that blocks duplicate launch.
- Current-source recheck (2026-09-25, 9a80e117): Run Consequential.persist still stores only a LOG blob before intent completion; handles.save and observations.record execute afterward (200?233). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-27, `568f6bf`): Handle and observation persistence plus foreground/MCP reconciliation precede intent commit. Every open unsafe intent blocks retry, including an Observed intent left by a failed commit update. Handle/observation/commit fault regressions preserve the retry fence.


### F-062 - Poll and cancel accept handles belonging to another campaign

- Task: [P1.6.5](TODO.md#L851).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Locations: [Run.poll/cancel](core/src/main/kotlin/io/astrolabe/tool/run/Run.kt#L313), [SqliteHandles.get](core/src/main/kotlin/io/astrolabe/tool/run/Handles.kt#L87).
- Problem: handle lookup is project-global by ID. Neither poll nor cancel compares handle.ids.work/attempt or workspace ownership with the current executor before reading logs, updating rows or terminating the process. The error wording nevertheless calls this campaign-scoped.
- Impact: a known/retained handle ID can expose another campaign's output or cancel its process in a shared project store. Random IDs make discovery harder but do not enforce the authority boundary.
- Possible solutions: scope handle resolution by the authorized work/attempt/workspace, with a deliberate cross-attempt resume policy; validate authorization before any observation, update or termination.
- Future regression: create two works sharing one store, attempt cross-work poll/cancel by exact handle ID, and require denial without reading/updating/terminating the foreign process; same-work resume remains supported.
- Current-source recheck (2026-09-25, 9a80e117): Run.poll/cancel still use project-global handles.get without checking work/attempt/workspace ownership (403,460). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `ff510f7`): Poll/cancel require matching work and workspace alias provenance before accessing the process or log. Same-work, same-workspace resume across attempts remains supported. Foreign work/workspace and resume regressions pass.


### F-063 - Background completion loses effect classification and its actual pre-run path state

- Task: [P1.6.5](TODO.md#L851).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-27).
- Locations: [Handle fields](core/src/main/kotlin/io/astrolabe/tool/run/Handles.kt#L19), [Run.poll / announceFromNow](core/src/main/kotlin/io/astrolabe/tool/run/Run.kt#L333).
- Problem: Handle stores only the pre-run candidate ID, not the pre-run member map or original effect class/unknown-effect qualification. Terminal poll walks current dirty members against registry.recorded and labels the result R when none are announced, otherwise W. It cannot preserve an originally D/unknown classification or compute the actual before/after path set.
- Impact: a destructive/external background command can be reported R after completion; pre-existing dirty files can be attributed to it, and a change returning a file to a clean HEAD state can disappear because that path is absent from current members. Concurrent unrelated edits can also be attributed to the background run.
- Possible solutions: persist launch classification and baseline/provenance needed for a conservative diff; preserve D and unknown flags through all polls. Attribute only what can be established, otherwise report unknown touched effects rather than infer them from the current registry.
- Future regression: background D command with no tree changes, pre-existing dirty files, dirty-to-clean restoration and a concurrent unrelated edit must retain honest effect labels and change provenance.
- Current-source recheck (2026-09-25, 9a80e117): Terminal poll still reconstructs change provenance from current members and registry.recorded; reports R/W instead of retaining launch D/unknown classification (424?454). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-27, `568f6bf`): Handles persist launch classification and dirty-member/base-commit provenance. Poll/cancel retain D labels; terminal polls compare both membership and changed Git base trees. Interval changes invalidate evidence without claiming exclusive process attribution. Legacy handles default to D/unknown.


### F-064 - Synchronous run observation delays cancellation and accumulates unbounded output in heap

- Task: [P1.6.5](TODO.md#L851); related P1.9.4.
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-27).
- Locations: [Run.launch](core/src/main/kotlin/io/astrolabe/tool/run/Run.kt#L241), [Executions.observe](core/src/main/kotlin/io/astrolabe/tool/run/Executions.kt#L20), [LocalOs.poll](core/src/main/kotlin/io/astrolabe/os/LocalOs.kt#L91).
- Problem: a suspend Run path calls a blocking polling loop with no suspension/cancellation check, while collecting all output into ByteArrayOutputStream. Job.cancel does not interrupt this ordinary blocking loop; process ownership/deadline eventually acts, but cancellation is not promptly delivered to the foreground child. Output budgets/redaction limits are applied after accumulation.
- Impact: a requested campaign cancel can wait until process exit/deadline, with the child continuing effects; a noisy long-running process can exhaust host heap before shaping its small requested view.
- Possible solutions: observe through a cancellation-aware owned-process lifecycle, request termination on cancellation and independently preserve terminal accounting; spool/stream bounded captures rather than accumulate the entire log in memory. If evidence is capped, report explicit capture incompleteness.
- Future regression: cancel a long foreground process immediately and verify bounded termination/settlement latency; stream output larger than the memory budget and preserve bounded heap plus honest recall/capture semantics.
- Current-source recheck (2026-09-25, 9a80e117): Run.launch still blocks in a polling loop with ByteArrayOutputStream and no cancellation check; only process deadline/exit ends it (333?346). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-27, `568f6bf`): Foreground observation is interruptible and cancellation terminates owned processes before propagating. The shared observer retains at most 8 MiB while draining the log and marks discarded capture unknown. Terminal background reads are capped and missing logs are incomplete. Waiting/noisy-poll, interrupted-I/O, terminal-cap and end-to-end run cancellation regressions pass.


### F-065 - Terminal process status stops log draining before all cursor chunks are read

- Task: [P1.6.5](TODO.md#L851); related P1.7.2/P1.7.5.
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Locations: [Executions.observe](core/src/main/kotlin/io/astrolabe/tool/run/Executions.kt#L24), [Run.launch](core/src/main/kotlin/io/astrolabe/tool/run/Run.kt#L255), [LocalOs.readLog MAX_POLL_BYTES](core/src/main/kotlin/io/astrolabe/os/LocalOs.kt#L277).
- Problem: each poll returns at most 1 MiB. Observation loops stop when status becomes terminal, then perform exactly one final poll. A terminal result does not imply the log cursor reached EOF. If several MiB are already buffered, only the first one/two chunks are captured, with lost=false/captureComplete=true.
- Impact: final errors or test summaries can be silently omitted. Parsers can inspect an earlier partial summary and receive falsely complete capture metadata; users cannot recall omitted bytes from the captured blob even though the sidecar log still has them.
- Possible solutions: after terminal status, keep draining by cursor until no bytes remain, without relaunching or waiting for execution; couple any capture cap to explicit completeness metadata.
- Future regression: a process exits after writing more than three poll windows with a failure marker in the final window; the complete observer must include it or explicitly report truncation. Cover already-terminal input Proc as well as a transition observed mid-poll.
- Current-source recheck (2026-09-25, 9a80e117): Run.launch still performs one terminal tail poll (343); Executions.observe/LocalOs bounded chunks retain the earlier incomplete-drain risk. Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `ff510f7`): The shared observer drains terminal processes until an empty cursor chunk and treats a nonadvancing nonempty cursor as lost. Run, Verify, Checker, Baseline, QA and inline syntax use it. Initially-terminal, terminal-transition and foreground-run regressions preserve the final failure marker.


### F-066 - Successful unittest output with skips is counted as executed passes

- Task: [P1.6.6](TODO.md#L859).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [GenericSummaries.unittest](core/src/main/kotlin/io/astrolabe/tool/run/GenericShaper.kt#L177).
- Reproduction: output says Ran 1 test ... followed by OK (skipped=1), exit 0. Shapers returns Passed with passed=1, skipped=0.
- Problem: skipped counts are parsed only inside a FAILED(...) line. Successful OK(skipped=N) is ignored, then passed is inferred as total minus zero failures/errors/skips.
- Impact: a suite in which nothing executed can satisfy acceptance, and mixed suites overstate executed/pass counts. This contradicts the no-tests/all-skipped inconclusive rule.
- Possible solutions: parse the successful completion breakdown as well as failure breakdowns; retain expected-failure/skip categories explicitly and derive executed counts conservatively.
- Future regression: all-skipped, partially skipped, expected-failure and ordinary successful unittest outputs must produce truthful counts; all-skipped never becomes green.
- Fix (2026-09-26, `9dbf774`): Unittest OK summaries account for skipped cases; all-skipped output stays inconclusive.


### F-067 - Cargo shaping ignores later suite failures after the first summary

- Task: [P1.6.6](TODO.md#L859).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [GenericSummaries.cargo](core/src/main/kotlin/io/astrolabe/tool/run/GenericShaper.kt#L118).
- Reproduction: output contains a successful first test-result summary and a failed second summary with a FAILED test line; command is cargo test || true and shell exit is 0. Shapers returns Passed/counts(1 passed,0 failed) while its own tests list contains one failing identity.
- Problem: counts come only from the first matching summary, but test identities are collected from the whole log. Shared status logic trusts those incomplete counts, including for the explicitly supported wrapper path.
- Impact: a multi-target/workspace/doc-test failure can become a green verification result. Without a wrapper the result can still be mislabeled inconclusive rather than failed.
- Possible solutions: aggregate all independently scoped summaries with deduplication/provenance rules, reconcile counts against parsed failing identities, and never accept a pass when any authoritative segment reports failure.
- Future regression: multiple Cargo unit/integration/doc-test groups, including later failures and wrapper exits, must preserve total/failure counts and return failed when any group failed.
- Fix (2026-09-26, `9dbf774`): Cargo aggregates all suite summaries, including later failures.


### F-068 - Incomplete structured reports can still produce Passed in Jest/pytest shaping

- Task: [P1.6.6](TODO.md#L859).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [JestShaper.shape/JestJson.parse](core/src/main/kotlin/io/astrolabe/tool/run/JestShaper.kt#L32), [PytestShaper.shape/PytestJson.parse](core/src/main/kotlin/io/astrolabe/tool/run/PytestShaper.kt#L31).
- Reproductions: (1) a fresh malformed Jest JSON report plus a green console summary returns Passed with a could-not-be-parsed limitation; (2) a fresh pytest report declares total=2 but contains only one passed test, returning Passed with discovered=2 and executed=1.
- Problem: Jest does not feed report parse problems into StatusInputs.evidenceIncomplete. Both JSON consumers ignore totals versus parsed case count and silently skip malformed/unknown case entries; a nonempty surviving subset can become authoritative. The stricter JUnitXmlShaper total check is not shared.
- Impact: missing failed tests or a corrupted fresh report can certify acceptance despite explicitly incomplete evidence; a printed limitation does not make the verdict non-green.
- Possible solutions: carry parser completeness and skipped-entry reasons through one shared status validator; validate declared totals/outcomes against parsed cases, and fail closed on contradictory/incomplete fresh evidence. Define any fallback policy explicitly rather than silently accept a console summary.
- Future regression: malformed report with green stdout, missing case entries, unknown status and total mismatch must remain non-green; complete valid reports retain their current behavior.
- Fix (2026-09-26, `210763d`): Malformed/incomplete Jest and pytest JSON reports, unknown entries and partial pytest XML reports cannot fall back to green terminal output.


### F-069 - Runner parsers discard namespaces needed for baseline failure identity

- Task: [P1.6.6](TODO.md#L859); related P1.7.5/P3.6.1.
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [JestJson file extraction](core/src/main/kotlin/io/astrolabe/tool/run/JestShaper.kt#L226), [GenericSummaries.go](core/src/main/kotlin/io/astrolabe/tool/run/GenericShaper.kt#L146), [ReportArtifact.moduleOrDerived](core/src/main/kotlin/io/astrolabe/tool/run/Shaper.kt#L42).
- Problem: Jest JSON paths are reduced to basename, so services/a/test.ts and services/b/test.ts become the same file identity. Go parsing assigns every case to the last package line in the entire capture, rather than the package for that case. The inferred JUnit module keeps only the last directory before build/target, which can likewise collide in nested monorepos.
- Impact: different tests can share canonical identity, or a test's identity can change when an unrelated package is appended. In-run multiplicity flags catch some collisions, but a baseline/current run each containing one different same-named failure can appear to be the same pre-existing failure.
- Possible solutions: preserve normalized repository/project-relative runner identities separately from display shortening; associate Go case groups with their own package records or consume structured events. Treat unresolved namespace as ambiguous rather than guessing.
- Future regression: same basename/suite/test under two packages, reordered/appended Go packages, and nested modules with equal leaf names must remain distinct across baseline and current runs.

### F-070 - Verify's green flag does not prove the requested checks certify the final tree

- Current-tree recheck (2026-09-25, 9a80e117): still present at [acceptance selection](core/src/main/kotlin/io/astrolabe/tool/verify/Verify.kt#L240) and [aggregate green](core/src/main/kotlin/io/astrolabe/tool/verify/Verify.kt#L383). No new runtime reproduction claimed.

- Task: [P1.6.7](TODO.md#L865).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Locations: [Verify.acceptance](core/src/main/kotlin/io/astrolabe/tool/verify/Verify.kt#L188), [Verify.runAll/outcome](core/src/main/kotlin/io/astrolabe/tool/verify/Verify.kt#L231).
- Problem: runAll computes current Scheduler.currency for display, but outcome.green uses only receipt.greenForFinalTree (historical Passed + stable inputs), without testing currency at the final batch stamp. acceptance also flatMaps requested acceptance IDs to registered checks and silently drops any requested Run item with no matching registration when another item does have a check.
- Impact: the text can mark an earlier receipt stale while green(op:N) still succeeds; or a requested acceptance set can return green after only a subset was executed. Conditional STATE facts/ticks then rely on a stronger result than was established.
- Possible solutions: compute aggregate green from the complete requested obligation/check set and final-stamp Currency.certifies; record explicit unavailable/missing dispositions for unmapped requested obligations. Keep historical outcomes distinct from the aggregate current verdict.
- Future regression: two passing receipts where an earlier one is stale at the final batch stamp, and a requested set with one missing registered check; neither may yield green=true.
- Fix (2026-09-26, `2b30ba6, da8bc42`): Both acceptance selections refuse missing registrations. Aggregate green requires final-stamp Scheduler.currency certification; an earlier pass invalidated by a later check is non-green.


### F-071 - Model-added verification commands bypass run execution policy and D-class approval

- Task: [P1.6.7](TODO.md#L865); related P1.1.1/P1.10.2/P2.1.2.
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Locations: [Verify.runOne](core/src/main/kotlin/io/astrolabe/tool/verify/Verify.kt#L254), [PlanIntake autonomous acceptance](core/src/main/kotlin/io/astrolabe/campaign/Plan.kt#L282), [Contract.strengthen](core/src/main/kotlin/io/astrolabe/contract/Contract.kt#L270).
- Problem: an autonomous plan can add a model-origin Acceptance.Run with arbitrary argv/cwd. Verify runs its command directly through Runner.start; unlike Run, it has no EffectPolicy/ceiling/Authority approval/intent checks for that executable. Command.cwd is resolved with root.resolve without WorkspacePath validation.
- Impact: a model can route a command which run would deny or require D-class approval for through a nominally strengthening acceptance item. A working directory outside the workspace can also pass this path. Trusted-local explicitly lacks OS confinement, but that does not justify skipping the harness's declared command-authority checks.
- Possible solutions: make verification dispatch use the same validated command/cwd/capability/approval boundary as ordinary run, with an explicit policy for approved test commands; adding a logical obligation must not itself grant executable authority.
- Future regression: in autonomous mode propose an acceptance command classified D/denied and an escaping cwd; verifying it must refuse before effects unless committed execution authority explicitly covers it. Ordinary approved test runners remain usable.
- Current-source recheck (2026-09-25, 9a80e117): PlanIntake still strengthens autonomous model acceptance; Verify.runOne directly starts command.argv with root.resolve(cwd), no Run authority/classification path (327?356). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `6e1f2ca`): D-262 conservatively denies new model-origin verification commands unless exact argv/cwd also has non-model contract authorization. Working directories resolve inside the execution root; denied-command, escaping-cwd and approved-command regressions pass.


### F-072 - Failed STATE persistence leaves the patch applied in memory

- Task: [P1.6.8](TODO.md#L873).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [StateTool.patch Applied branch](core/src/main/kotlin/io/astrolabe/tool/state/StateTool.kt#L137).
- Reproduction: RegisterVersions.save throws an injected storage error for a valid Next-only patch. execute throws, but StateTool.register is already version 1 with the new Next value, while persistence failed.
- Problem: register is assigned and lastRejection cleared before versions.save succeeds.
- Impact: the dispatcher/cell sees a failed tool but subsequent logic uses the changed STATE; restart sees an older version. A later checkpoint can persist the state of a patch that never reported success, violating atomic commit/failure semantics.
- Possible solutions: durably save the validated candidate before publishing it as current and emitting events; if a larger transaction is needed, publish only after commit and preserve the previous state on failure.
- Future regression: fail save before/at commit; memory and persistent latest version must remain aligned or the context must fail closed with an explicit uncertain commit, never silently advance.
- Fix (2026-09-26, `fb6bffc`): STATE persistence precedes publishing the new in-memory register or clearing its last rejection; injected save failure leaves both unchanged.


### F-073 - Schema-rejected STATE patches do not update the gate's rejection record

- Task: [P1.6.8](TODO.md#L873); related P1.8.5/P1.8.7.
- Severity: medium. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Locations: [StateTool.patch parser rejection](core/src/main/kotlin/io/astrolabe/tool/state/StateTool.kt#L121), [Cell GateState.patchRejection](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L427).
- Problem: ParsedPatch.Invalid returns a rejected ToolOutcome immediately without setting lastRejection. Only Validator.Rejected updates that field. Cell passes lastRejection to register gates whenever the turn contains a patch.
- Impact: repeated malformed patches can be invisible to the dedicated rejection guard, or reuse an unrelated previous rejection as the current cause. State itself remains unchanged; the defect is recovery/gate accuracy and wasted turns.
- Possible solutions: represent parser and invariant failures through one current-patch rejection record, clearing/replacing it deterministically for every attempt; keep rule/index/size diagnostics appropriate to each failure.
- Future regression: successful patch followed by repeated schema failures, and invariant rejection followed by a different schema failure, must feed the correct current rejection into the gate.
- Current-source recheck (2026-09-25, 9a80e117): StateTool schema-invalid branch returns before setting lastRejection (116); Cell still feeds that field to the patch rejection gate (455). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `fb6bffc`): Schema-invalid STATE patches now record a typed rejection with measured sizes for the loop gate. A subsequently saved valid patch clears it.


### F-074 - Task answers are matched against a stale contract snapshot and not their question ID

- Task: [P1.6.9](TODO.md#L878); related P0.4.2/P1.1.3.
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [TaskTool.ask](core/src/main/kotlin/io/astrolabe/tool/task/TaskTool.kt#L100).
- Reproduction: during Authority.ask, amend the contract from v1 to v2, then return Answer(questionId=wrong-question, contractRevision=1). The tool returns answered and pins that mismatched answer despite latest revision=2.
- Problem: Replies.check compares against the contract read before the suspend, not the current committed revision; questionId is never compared with the pending Question.id.
- Impact: late/misdirected replies become factual evidence or even user amendments for the wrong question/authority state. Existing tests return an already-mismatched revision immediately and do not cover an intervening update.
- Possible solutions: validate question identity and re-read the current contract after the await; preserve superseded replies as historical evidence without applying them. Bind any amendment application transactionally to the still-current pending question/revision.
- Future regression: wrong question ID, contract update while waiting and stale changesRequirements=true replies must all block/supersede without changing current authority.
- Current-source recheck (2026-09-25, 9a80e117): TaskTool.ask still compares answer to pre-await contract.version only and omits questionId check (115?130). Earlier runtime evidence retains its original baseline; this recheck is source inspection.
- Fix (2026-09-26, `e4e23eb`): Task answers must name the pending question and match the current contract after the await; wrong and superseded answers cannot amend it.


### F-075 - Normal Gradle/Maven verification never supplies the reports required to pass

- Task: [P1.7.1](TODO.md#L891); integration with P1.6.7.
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [Checks.seed](core/src/main/kotlin/io/astrolabe/verify/Check.kt#L346), [Verify.runOne](core/src/main/kotlin/io/astrolabe/tool/verify/Verify.kt#L330), [JUnitXmlShaper](core/src/main/kotlin/io/astrolabe/tool/run/JUnitXmlShaper.kt#L30).
- Trigger/problem: Sniff registers Gradle/Maven tests as acceptance/full-suite commands. Even when the executable launches and produces valid JUnit XML, Verify constructs RunCapture without reports (default empty). JUnitXmlShaper requires fresh XML evidence; successful console output alone yields no counts and cannot pass. Baseline has a report collector, but ordinary verification does not use it.
- Impact: supported default Java/JVM projects cannot satisfy these run obligations through normal verify. This is separate from F-041 executable resolution and from P7 live transports.
- Possible solution: collect invocation-bound JUnit reports from the actual execution root, with explicit freshness/cache provenance, and pass them to the existing shaper.
- Future regression: successful and failing Gradle/Maven fixtures through Verify, including isolated roots and stale prior reports; success requires fresh parsed tests, never exit code alone.

### F-076 - Touched checker paths are resolved twice for nested package commands

- Task: [P1.7.2](TODO.md#L898).
- Severity: medium. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Checker.execute](core/src/main/kotlin/io/astrolabe/verify/Checker.kt#L158), [Checker.argvFor](core/src/main/kotlin/io/astrolabe/verify/Checker.kt#L251), [Cell.drainScheduled](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L676).
- Reproduction: a touched check with argv=[mypy], cwd=pkg and workspace path pkg/src/a.py produces argv=[mypy, pkg/src/a.py] while executing inside pkg; the intended file is therefore addressed as pkg/pkg/src/a.py. Coherence supplies workspace-relative paths and Checker does not rebase/filter them.
- Impact: nested-package checks fail on nonexistent paths or inspect unintended files. All seeded type/lint commands also receive file arguments regardless of their runner's supported selection syntax; that broader compatibility issue needs runner-specific validation.
- Possible solution: translate selected paths relative to the command cwd, restrict them to the relevant package, and encode runner-specific selection or conservatively run its declared project command.
- Future regression: nested package edits, edits outside that package, and project-wide checker commands; assert actual files checked, not just the appended argv list.
- Fix (2026-09-26, `c49ca61`): Checker and blast arguments are relative to command cwd while evidence paths remain workspace-relative. Nested checkers ignore unrelated package paths; known file-oriented commands receive selected files and project/unknown commands run as declared. Actual nested-file reading and argument regressions pass.


### F-077 - Matching candidate stamps bypass verifier-version invalidation

- Task: [P1.7.4](TODO.md#L910).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Applicability.of](core/src/main/kotlin/io/astrolabe/verify/Check.kt#L88), [Scheduler.assess](core/src/main/kotlin/io/astrolabe/verify/Scheduler.kt#L325).
- Reproduction: pass a real receipt's stamp/definition with CandidateNow.verifierVersion=changed-verifier. Applicability returns Current without a reason or reuse proof. Version/environment comparisons occur only after the equal-stamp early return; Scheduler likewise omits current environment on that branch.
- Impact: receipts from a prior verifier implementation can remain current after a verifier upgrade even when its interpretation of evidence changed. Existing version-change tests first change the workspace stamp, so they miss this branch.
- Possible solution: enforce verifier/definition compatibility before the equal-stamp return; define environment requirements consistently for exact-stamp and closure-based applicability.
- Future regression: unchanged candidate and command definition with changed verifier must be stale; unchanged compatible verifier remains current.
- Fix (2026-09-26, `f060c97`): Verifier-version validation precedes same-stamp receipt reuse; supplied changed environments also invalidate.


### F-078 - Exclusive checks certify newly added inputs without detecting their mutation

- Task: [P1.7.4](TODO.md#L910).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Scheduler.runCheck](core/src/main/kotlin/io/astrolabe/verify/Scheduler.kt#L126), [mutation comparison](core/src/main/kotlin/io/astrolabe/verify/Scheduler.kt#L135).
- Reproduction: in the existing SchedulerTest package-closure fixture, add src/pkg/new.py inside the execute callback and return a parsed pass. Result: stampBefore != stampAfter, mutatedDuringCheck=[], currency.certifies=true.
- Problem: paths are enumerated before dispatch (even before acquiring the mutation lock), and only those same paths are rescanned afterwards. New members are absent from the comparison. The receipt then binds stampAfter, which includes the untested member.
- Impact: a generated/replaced test or source member can join the final tree during verification without invalidating its green receipt. Unknown closures with a caller-supplied old tree list have the same problem.
- Possible solution: enumerate under the lock and compare before/after membership as well as versions/metadata across the full applicable input scope, excluding only declared scratch outputs.
- Future regression: add/delete/rename package and unknown-closure inputs during execution, including additions before lock acquisition; every unexplained input change makes the receipt ineligible.
- Fix (2026-09-26, `2b30ba6`): Exclusive checks enumerate under the mutation lock and compare before/after membership and content/metadata. Enumerated unknown closures rescan the workspace, not only a caller-supplied stale path list.


### F-079 - Isolated export accepts bytes that no longer match its candidate stamp

- Task: [P1.7.4](TODO.md#L910); follow-up P5.1.3.
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Scheduler.runIsolated](core/src/main/kotlin/io/astrolabe/verify/Scheduler.kt#L156), [Scheduler.export](core/src/main/kotlin/io/astrolabe/verify/Scheduler.kt#L217).
- Reproduction: capture a StampReport, change src/a.py, then invoke the real export helper with that report. Export succeeds and contains the changed bytes although a fresh candidate stamp differs. This deterministically reproduces the stamp-to-copy gap; concurrent end-to-end scheduling was not exercised.
- Problem: export compares destination hashes only with the bytes just read for copying, not the stamped tree. The harness mutation lock excludes cooperating writers, not external editors/background processes. runIsolated subsequently labels the result with the earlier report.candidateId.
- Impact: verification can certify one candidate using another candidate's bytes; an external edit followed by restore makes this particularly misleading. Default S0 runs exclusively; isolated callers/S3 are affected.
- Possible solution: materialize a pinned snapshot tied to the report, or verify copied membership/versions against a complete stamped manifest and reject any intervening change before dispatch.
- Future regression: inject an edit between stamp acquisition and export reads; no receipt may certify the old stamp from new bytes.
- Fix (2026-09-26, `2b30ba6`): Isolated export reads unchanged bytes from immutable Git objects, checks dirty bytes against stamped digests, and checks candidate currency before/after export. A stale report is refused; unsupported entry modes fall back to exclusive execution.


### F-080 - Isolated verification drops executable file modes

- Task: [P1.7.4](TODO.md#L910); related snapshot-fidelity finding F-028.
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Locations: [Scheduler.export Files.write](core/src/main/kotlin/io/astrolabe/verify/Scheduler.kt#L227), [Scheduler.scan](core/src/main/kotlin/io/astrolabe/verify/Scheduler.kt#L235).
- Trigger/problem: export creates every file using Files.write and preserves no source executable permission; scan verifies only bytes, size and mtime. A tracked executable script/launcher is accepted as a faithful isolated candidate although its execution mode changed.
- Impact: on POSIX, checks invoking ./gradlew or another executable script can fail with permission denied, or exercise different behavior, while the receipt is labelled with the original candidate. This separate exporter does not use ShadowRef's materialization policy. POSIX runtime reproduction was unavailable on this Windows host.
- Possible solution: preserve and validate required entry modes as part of candidate export, or explicitly reject unsupported materialization before dispatch.
- Future regression: Linux executable launcher and a mode-only change through the isolated scheduler; copied executable status and reported candidate must agree.
- Fix (2026-09-26, `2b30ba6, 6e1f2ca`): Isolated exports restore and validate POSIX executable status; post-run snapshots include executable state. The POSIX runtime regression is present but skipped on this Windows host.


### F-081 - A baseline that changed its inputs still publishes pre-existing failures

- Task: [P1.7.5](TODO.md#L916).
- Severity: medium. Confidence: reproduced. Status: fixed (2026-09-26).
- Locations: [Baseline mutation scan](core/src/main/kotlin/io/astrolabe/verify/Baseline.kt#L207), [ledger construction](core/src/main/kotlin/io/astrolabe/verify/Baseline.kt#L230), [PreexistingLedger.classify](core/src/main/kotlin/io/astrolabe/verify/Baseline.kt#L82).
- Reproduction: existing BaselineTest fixture plus a suite that changes tests/test_discount.py and emits the recorded failing report returns Failed, eligible=false, mutatedDuringCheck=[tests/test_discount.py], but a ledger with one pre-existing-failure entry and no ledger limitations.
- Problem: ledger creation checks only outcome, not the receipt's input stability. classify can return PreExisting from that ledger without checking whether the baseline execution actually describes s0. Its rescan also compares original file bytes only, missing added members and restored writes.
- Impact: failure triage and rendered baseline context can mislabel failures from a modified candidate as pre-existing. Required acceptance is not automatically waived; the direct impact is misleading evidence/steering.
- Possible solution: withhold pre-existing classification when baseline inputs changed or stability is unknown, carry the reason into the ledger view, and use full membership/metadata validation.
- Future regression: mutate an input before a failure, restore it before exit, or create a new input; none may establish a comparable pre-existing-failure ledger.
- Fix (2026-09-26, `137b89b`): Baseline snapshots compare membership, versions, timestamps and executable state. Mutated, restored or expanded input trees cannot publish a pre-existing-failure ledger.


### F-082 - Check acceptance assessments have no production path into completion

- Task: [P1.7.7](TODO.md#L930); follow-up P1.8.8/P1.9.3.
- Severity: high. Confidence: confirmed_source. Status: open.
- Locations: [Assessment and gate](core/src/main/kotlin/io/astrolabe/verify/ExitGate.kt#L21), [assessment requirement](core/src/main/kotlin/io/astrolabe/verify/ExitGate.kt#L77), [Cell GateState construction](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L454), [Controller completion](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L772).
- Trigger/problem: an increment containing a valid Acceptance.Check requires an accepted Assessment. Production source contains no Assessment construction/recording path; Cell builds GateState without assessments and controller Verifier.accept calls omit them. verify acceptance supports Run items; review verdicts remain separate and are not converted into assessments. Unit tests provide Assessment lists directly.
- Impact: the built-in campaign cannot complete such an increment even after the criterion is satisfied and the host is willing to assess it. This is an implemented acceptance-kind integration gap, not a P7 transport limitation.
- Possible solution: provide a host/reviewer evidence path for check assessments, persist their obligation/candidate binding and pass current assessments through cell gates and final verifier. Never satisfy them from a model's unsupported claim.
- Future regression: a public-facade campaign with one Check obligation must refuse initially, then complete after a valid current assessment; stale or mismatched assessments must remain refused.

### F-083 - Acceptance-surface detection ignores a repository-local executable

- Task: [P1.7.8](TODO.md#L938).
- Severity: high. Confidence: reproduced. Status: open.
- Locations: [TestIntegrity.surfaceOf](core/src/main/kotlin/io/astrolabe/verify/TestIntegrity.kt#L193), [TestIntegrity.namesPath](core/src/main/kotlin/io/astrolabe/verify/TestIntegrity.kt#L268).
- Reproduction: the actual namesPath helper returns false for argv=[./scripts/check.sh], cwd=null, path=scripts/check.sh. It drops argv[0] before identifying command inputs. A script under that name is neither a recognized test path nor a built-in config filename.
- Impact: editing the executable that defines a required acceptance command can bypass acceptance-surface flagging and its review requirement. The helper also cannot tie that executable to its required checks through command-path matching.
- Possible solution: include argv[0] when it resolves to a workspace-local executable, preserving the distinction from externally resolved runner names; resolve cwd and path spellings canonically.
- Future regression: rewrite a required ./scripts/check.sh at repository root and in a package cwd; both must flag the relevant acceptance obligation and require review before completion.

### F-084 - Masked role operations still reach executors with the implementing role's defaults

- Task: [P1.8.1](TODO.md#L947).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L1272).
- Analysis: Controller.runCell registers Edit/Run/Verify/State/Kb for every role without supplying their actual effective mask; they retain ToolOps.implementingS0. Cell.maskFor restricts the advertised request, but validateCalls and Dispatcher do not enforce that mask on returned calls. A probe/reviewer/plan response that emits a masked edit or run can therefore execute it under the campaign's ordinary authority. Read-only delegation and review isolation promises are broken; this is not a claim that a role is an OS security boundary. Enforce the effective turn mask before dispatch and pass consistent role capabilities to executors. Regression: script a valid but masked edit/run from probe/review/plan and assert no filesystem or process effects. Role-mask intersection unit tests do not exercise this path.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-085 - The loop gate keeps demanding STATE after its recovery action was performed

- Task: [P1.8.5](TODO.md#L972).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/cell/Gates.kt#L377).
- Analysis: Cell.signatures accumulates every successful call for the whole cell. Gates.Loop recounts the entire history on every turn and re-emits its hard rejection for every historical count above the threshold. validateCalls clears requiredOp when a STATE op is present, but the unchanged history immediately sets it again at the end of that turn. Subsequent unrelated tool-only turns are refused indefinitely. Clear or acknowledge the offending episode after the required recovery, or restrict the gate to a current repetition window. Regression: three identical calls, a valid STATE recovery, then an unrelated look/edit without STATE; the last turn must execute. Existing CellTest ends at recovery/completion and misses the following tool turn.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-086 - Provider terminal reconciliation is never consumed by the cell runtime

- Task: [P1.8.7](TODO.md#L986).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L321).
- Analysis: The loop immediately discards the Invocation handle after start(...).await(). No production caller consumes Invocation.terminal(), although its SPI explicitly delivers late output/usage after cancellation. ProviderError releases the reservation outright; coroutine cancellation skips Accounting.record and reservation reconciliation. A billed cancelled or transport-failed call can disappear from final accounting and its late evidence is never archived. Keep the handle, reconcile terminal state exactly once even after cancellation, archive late items without executing them, and retain unknown-cost reservations until settled. Regression: fake invocation cancelled during await with later nonzero terminal usage; final call accounting must contain that charge once. This concerns the implemented provider-neutral lifecycle, not P7 HTTP transport.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-087 - Incomplete provider usage can release the cell's conservative token reservation

- Task: [P1.8.7](TODO.md#L986).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L328).
- Analysis: Admission reserves estimated input plus output. Reconciliation uses any non-null response.usage, summing totalInput and OUTPUT with a zero default, without requiring usage.isComplete. The preceding contextAdmission.observed deliberately checks completeness, but budget reconciliation does not. Partial usage therefore charges only reported dimensions and frees the rest, permitting additional work beyond the intended token bound. Preserve the conservative reservation for unknown dimensions until terminal reconciliation, and account complete usage once. Regression: partial input-only/output-only usage must not lower the committed charge below the known-plus-unknown conservative bound.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-088 - Dispatch authority can expire during model generation and still allow tool effects

- Task: [P1.8.7](TODO.md#L986).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Location: [execution path](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L253).
- Analysis: DispatchAuthority is checked at turn entry, before awaiting the provider. After await, calls and scheduler-owned checks execute without another authority check; Dispatcher has no authority callback. Controller dispatch reads lease expiry/generation, but expiry itself does not cancel the coroutine. A lease expiring during a long model request can therefore be followed by edits/process effects; later publication refusal cannot undo them. Recheck authority after await and at consequential dispatch boundaries, fencing effects against current generation. Regression: advance the fake clock beyond lease expiry while the provider is held, then release an editing response; no effect may execute. User cancellation does have a controller coroutine hook; this finding specifically covers expiry/supersession without that signal.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.
- Fix (2026-09-26, `e769511, 742d834, ff510f7, c49ca61, b13c18d`): Cell rechecks authority after accounted provider responses and before each tool/check dispatch. Built-in mutation, transform/syntax, process, baseline and ask-answer boundaries recheck after preparation or suspension. Expired provider-response, approval and ask-reply regressions prevent effects. Late completion may still be archived using existing evidence; all new scheduled work is skipped and publication remains fenced.


### F-089 - Required review and test-integrity approvals cannot reach the cell completion gate

- Task: [P1.8.8](TODO.md#L996).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L450).
- Analysis: GateState is constructed without reviews and with unresolved local test-integrity flags. The loop never reads persisted Verify review verdicts or resolves those flags. Controller.incrementReview, which can obtain the necessary approval, is called only after CellExit.Completed, while RoleCompletion.exitGate refuses completion until the review/flag is approved. A review acceptance item or a justified required test edit can therefore loop to CompletionStalled even after verify.review obtains an approval. Bind current signed review evidence to the cell gate and surface flags, or explicitly sequence review between provisional completion and final acceptance. Regression: an S2 increment with a review item and one with an approved required test edit must progress; wrong candidate/revision approvals remain refused. Related F-082 covers the separate missing Check assessment producer.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-090 - Role validator feedback is persisted but not returned to the model

- Task: [P1.8.8](TODO.md#L996).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L480).
- Analysis: When a packet validator returns CompletionDecision.Continue(gaps), Cell only calls recordGaps, which appends to an in-memory packet list and Journal. The next Anchor uses nudges computed before completion.assess, not those gaps; no feedback message is appended to residents or pinned context. Non-implementing roles can therefore be asked to retry an invalid packet without being told which fields/evidence failed, then terminate after the refusal limit. Feed validator gaps into the next rendered request while retaining the journal record. Regression: a first invalid plan/review/probe packet and a corrected second response; the second request must contain the validator's specific rejection.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-091 - A crash after the Finishing transition leaves the campaign unrecoverable on reopen

- Task: [P1.9.1](TODO.md#L1003).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L1540).
- Analysis: Finishing and Finished are saved as two independent transitions. If the process stops between them, Controller.open reloads Finishing but only resumes Ended/resumable states, reconciles Opened states and repairs Running cells. Neither runS0 nor runS1 can dispatch from the remaining Finishing state, and no reopen branch completes or rolls back finalization. A valid saved intermediate state therefore strands the campaign. Add idempotent finalization recovery that rechecks current acceptance before completing, or store the durable final state atomically. Regression: reopen a campaign persisted immediately after Transition.Finishing and verify safe progress without manually editing the database.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-092 - Committed scope changes cannot authorize paths hard-coded as protected at open

- Task: [P1.9.2](TODO.md#L1010).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L442).
- Analysis: The controller always constructs Workspace with default ProtectedPaths before loading the committed contract and never rebuilds/binds its path policy. ScopeGuard resolves Intent.Mutate through that immutable policy before checking the current contract. Removing package-lock.json, CI or migrations from contract protection via an approved amendment therefore still fails the path guard, including after reopen. The task log says binding is implemented, but actual construction only passes those defaults into initial deriveS0. Preserve unconditional Git-metadata protection while binding the configurable write restrictions to committed authority and revalidating changes. Regression: an approved scope/protection amendment enabling a lockfile or migration permits the intended edit; pending amendments do not.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-093 - Open-time reconciliation removes unknown effects from the writer-reassignment fence

- Task: [P1.9.2](TODO.md#L1010).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L499).
- Analysis: Open marks all uncommitted campaign intents Unknown and merely polls existing process handles. The unreconciled list passed to Leases.acquire is then intents.open().filter { status != Unknown }, excluding precisely those unresolved effects. Fence.grant requires previous-owner unknown effects to be reconciled before another writer receives the workspace. An expired previous holder can thus be replaced while a background process is still running or its effects remain unknown; marking an intent Unknown is not terminal reconciliation. Include unresolved unknown intents and live/lost handles in the handoff fence, and grant only after a safe disposition. Regression: reopen after lease expiry with a live old process/Unknown intent; new write authority must remain denied until reconciliation. Same-command replay suppression in Run does not fence a different new command.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-094 - Final review can approve an old tree while the controller completes the changed campaign

- Task: [P1.9.3](TODO.md#L1017).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L1513).
- Analysis: stopOrFinish captures stamp, currencies and receipts before suspending in CampaignReview.review. CampaignReview validates the returned verdict against its own pre-await stamp/contract snapshot. On approval, the controller publishes Finishing/Finished using the earlier stamp without rereading the workspace, current contract or publication authority after the await. An external edit, contract amendment or cancellation during host/model review can therefore yield Completed for stale evidence; FinishReceipts later stamps the new tree. Revalidate candidate, obligations and authority after asynchronous review and immediately before committing completion. Regression: a reviewer edits the tree/amends the contract/cancels before returning an otherwise matching approval; completion must be refused or verification repeated.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.
- Fix (2026-09-26, `e769511`): After final review, completion revalidates the candidate, full contract and live publication authority. Tree edits, amendments, same-version strengthening, cancellation and lease expiry cannot complete the campaign.


### F-095 - Each new cell is funded from the original campaign budget without deducting prior spend

- Task: [P1.9.4](TODO.md#L1024).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L1320).
- Analysis: runCell constructs a fresh CellBudget.of(contract.budget.tokens, ...) for every main-line, plan and continuation cell. route sets RoutingBudget.remainingCost to the unchanged contract.budget.cost and never subtracts persisted Accounting totals. Neither gate shares a campaign reservation across these cells, so a multi-cell campaign can spend the full token allowance repeatedly and monetary affordability resets on every route. Per-cell limits and maxCells bound individual runs but do not enforce the declared campaign allowance. Introduce campaign-level remaining/reserved accounting and allocate each cell from it, including helpers and unresolved usage. Regression: individually affordable cells whose aggregate exceeds tokens or money must stop before the overspending call; resume must retain previous charges.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-096 - CampaignHandle.await reports internal failures as user cancellation

- Task: [P1.9.6](TODO.md#L1037).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/Astrolabe.kt#L176).
- Analysis: await calls Deferred.join and returns Cancelled whenever job.isCancelled. A Deferred completed exceptionally is also cancelled in coroutine state, so exceptions from controller persistence, compiler/wiring or final export are silently converted to CampaignOutcome.Cancelled instead of being propagated or reported Failed. The Java facade inherits this result. Await the Deferred result and distinguish deliberate cancellation from exceptional failure, retaining the original cause. Regression: force a controller/export exception and assert an exceptional future or Failed outcome, while host cancellation still yields Cancelled.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-097 - Unknown executables are classified as read-only and safe to replay

- Task: [P1.10.2](TODO.md#L1055).
- Severity: high. Confidence: reproduced. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/auth/EffectPolicy.kt#L180).
- Analysis: Pure runtime probe: classify([./scripts/deploy-helper], cwd=null, workspaceRoot=C:/repo) returns effect=R, effectsUnknown=false. classifyOne starts at R/known and only promotes recognized command families; an arbitrary repository executable matches none. Run derives replaySafe directly from R && !effectsUnknown, so a lost acknowledgement permits relaunch of that same unknown executable. It may write, delete or publish despite the harness describing it as read-only/predictable. Default unknown commands to unknown effects and non-replay-safe, and recognize safe argv forms positively. Regression: an unrecognized executable and a wrapper around a mutating command must never be automatically replayable. No executable was launched by the probe.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; successful stdin JShell probe against current compiled classes; synthetic inputs only.

### F-098 - Overlapping redaction matches can expose a secret recognized by the default rules

- Task: [P1.10.3](TODO.md#L1061).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [execution path](core/src/main/kotlin/io/astrolabe/auth/Redaction.kt#L175).
- Analysis: Runtime probe with synthetic text password=Bearer abcdefghijklmnopqrstuvwxyz returns [REDACTED:secret-assignment] abcdefghijklmnopqrstuvwxyz. The assignment match starts first and consumes only password=Bearer; the longer bearer-token match overlaps that consumed prefix and is discarded wholesale by match.start < cursor, leaving its secret tail visible. This is a matching-algorithm failure even when both configured detectors recognize the input. Merge overlapping secret spans or extend the covered interval before rendering replacements. Regression: nested and partially overlapping default/custom matches, including multi-line masks; all bytes covered by any sensitive match must remain hidden.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; successful stdin JShell probe against current compiled classes; synthetic inputs only.
- Fix (2026-09-26, `24e53e3`): Overlapping redaction matches redact the full interval union, including transitive overlaps.


### F-099 - Real context rebuilds never produce the events counted by runtime metrics

- Task: [P1.11.1](TODO.md#L1073).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L743).
- Analysis: Cell.rebuild increments rebuilds, rewrites transcript/Workset and journals a Boundary, but never emits AgentEvent.Cell.Rebuilt. A production-source search finds the event only in its declaration and Metrics consumers. CellMetrics.rebuilds and CampaignMetrics.rebuildsPerCell therefore report zero for actual pressure rebuilds, corrupting the telemetry used to assess context sizing/continuations. Emit the event from the successful rebuild boundary with its actual generation, or derive metrics from the canonical persisted rebuild record. Regression: run the existing pressure-rebuild fixture and compute Metrics from its real events; counts must agree with checkpoint.rebuilds.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-100 - Concurrent Windows launches inherit each other's transient handles

- Task: [P1.12.4](TODO.md#L1120).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/os/WindowsOwner.kt#L32).
- Analysis: WindowsOwner creates inheritable log/NUL handles and calls CreateProcessW with bInheritHandles=1 using ordinary STARTUPINFOW. It supplies no per-child handle list; LocalOs.spawn/WindowsOwner.start are not serialized. During overlapping starts, the assertion that only this launch's two handles are inheritable is false: a child can inherit another launch's handles or a host's unrelated inheritable handle, retaining access/resources outside its intended stdio set. Use an explicit STARTUPINFOEX handle allowlist and close all temporary handles reliably. Regression: concurrent launches with distinct inheritable sentinel handles; each child must receive only its intended handles. Static finding; concurrent native reproduction not performed.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-101 - A failed Windows job assignment leaks the newly created suspended process

- Task: [P1.12.4](TODO.md#L1120).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/os/WindowsOwner.kt#L98).
- Analysis: CreateProcessW succeeds with CREATE_SUSPENDED, then AssignProcessToJobObject may fail. The catch calls TerminateJobObject on the job that did not receive the process and closes the process handle, but never terminates that unassigned process itself. LocalOs cannot clean it up because owner.start threw before returning OwnedProcess. The suspended process survives the failed launch and later owner shutdown. On post-create failure terminate the process directly when assignment is not known to have succeeded, wait for exit and close all handles. Regression: fault-inject job assignment failure after process creation and prove the child no longer exists.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-102 - Accepted plan obligations never enter the running check registry

- Task: [P2.1.2](TODO.md#L1143).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L944).
- Analysis: Controller.open seeds Checks from the pre-plan contract. PlanIntake can add Run acceptance items, but the admitted-plan path only installs the graph and writes a boundary; no production controller path registers/replaces checks for newly committed acceptance. The new increment therefore names an obligation with no executable check. Verify.acceptance either refuses the whole selection or silently drops an unmapped item (F-070), while the exit/final gate cannot certify it. Reconcile check definitions with each committed contract revision before dispatch, retaining historical results only with valid definition identity. Regression: a plan adds a valid Run criterion to a requirement and its implementation executes that new check before completion. This also limits the currently reachable autonomous command-bypass scenario described historically in F-071.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-103 - Non-implementing role completion runs implementation acceptance before validating its packet

- Task: [P2.1.2](TODO.md#L1143).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L429).
- Analysis: Every no-call completion proposal runs tools.verify.onStop(increment.accept), then unavailable(currenciesNow) can return Blocked before RoleCompletion.assess is called. Controller constructs the plan increment with all campaign acceptance items; probe/review child contexts also use the shared loop. A valid plan or investigation packet can therefore be blocked by an unavailable product test runner, and planning/review unnecessarily executes implementation checks outside its declared completion duties. Dispatch completion verification by packet/role requirements; implementation acceptance belongs to Result completion, while other roles should execute only their own declared validation obligations. Regression: a valid plan/probe packet with an unavailable campaign acceptance runner must still reach its own validator and preserve that missing test evidence for the implementing stage.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-104 - Increment-split proposals have no live controller re-planning path

- Task: [P2.1.5](TODO.md#L1163).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L929).
- Analysis: CampaignProposals is installed only for the initial plan cell. Ordinary S1 implementation uses TaskTool with its default S0 mask/no proposal intake; S2 may advertise task.propose but still has no intake. SqliteSplitRequests.forPlanRole is never called in production, and runS1 only plans before any increment has cells. Consequently the rescoping/scope-gate instruction to request increment_split cannot produce a new plan: calls are masked/unavailable, or an inbox record has no consumer. Wire main-line proposal intake, consume pending splits at a safe boundary and replan the remaining authorized work while preserving completed nodes. Regression: an implementing cell submits a split, the plan role receives it and the controller runs the resulting pending nodes; no graph changes happen merely on proposal.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-105 - A resumed blocked increment never becomes eligible for dispatch

- Task: [P2.2.4](TODO.md#L1195).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L494).
- Analysis: Lifecycle.Returned marks a CellExit.Blocked increment Blocked. Reopen changes a resumable Ended campaign to Opened/Running, but does not reassess or unblock its graph nodes. readyFrontier excludes Blocked nodes, and the controller's empty-frontier branch only refreshes Verified regressions before stopping again. Transition.Unblocked exists but has no production caller. Resolving a missing tool or providing the requested host input therefore cannot resume such an increment through the built-in flow without manual lifecycle manipulation. Add an explicit evidence-backed unblock/reassessment step on resume. Regression: stop for a missing executable/question, resolve the prerequisite, reopen and continue the same increment without re-executing verified work.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-106 - Seeds omitted from the compiled prompt still grant KNOWN edit coverage

- Task: [P2.3.1](TODO.md#L1217).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L1237).
- Analysis: Compiler makes seed blocks optional and includes only ContextCover.selectedIds in compiled.k.sections. Controller nevertheless passes every Seeds.render(...).shown entry to runCell, which immediately Workset.seed(seeds) before any request. It never filters seeds by the compiler's actual selection. Under a small context budget, a seed can be omitted from the model's prompt yet retain authoritative KNOWN coverage; Cell.observeWorkset also records it as displayed/read. This permits anchored edits against bytes the new cell never received and falsifies packet coverage. Seed Workset only from content actually serialized into the accepted request, with source/version/range mapping. Regression: force one current seed out of the context selection, then attempt an anchored edit using it; it must require look/read first.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-107 - Bounded cross-cell fact retention is never applied by the runtime

- Task: [P2.4.2](TODO.md#L1257).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/context/FactCoherence.kt#L41).
- Analysis: FactCoherence.retain implements missing-evidence demotion, stale streak tracking, archive and capacity-gap reporting, but has no production caller. Controller.carryFrom and Cell.rebuild only call CarryForward.carry, which marks moved anchors but preserves unresolved verified facts and never archives old stale/refuted records. Controller.boundary always passes an empty archived list into STATUS. Repeated continuations therefore retain unusable facts indefinitely and can fill mandatory STATE until compilation stops; tests of the standalone retention policy do not prove runtime behavior. Apply retention at the boundary, persist streak/archive state and surface an unresolvable capacity gap. Regression: continue twice with a stale unreferenced fact and missing evidence; it must be archived/demoted while remaining recallable.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-108 - Pressure rebuild retains old messages that merely equal a recent message

- Task: [P2.5.2](TODO.md#L1287).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L745).
- Analysis: Cell converts Rebuild.tail(...) to a Set<Item>, then filters the entire resident history by value membership. Message equality is by content, so an older assistant message equal to one in the retained tail is reintroduced even though its turn was evicted. Repeated short replies or repeated long explanations can defeat the six-turn bound and cause the supposedly reduced context to overflow again. Retain the selected resident positions/identities, not all historical items with equal values. Regression: many protocol turns sharing the same assistant text; pressure rebuild must retain only the actual tail occurrences with their matched calls/results.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-109 - The production pressure rebuild bypasses recompilation and lost-coverage validation

- Task: [P2.5.3](TODO.md#L1293).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L743).
- Analysis: Rebuild.run provides compileK, projection coverage validation and a rehydrate/refusal path, but has no production caller. Cell's custom pressure path truncates residents, computes carry seeds and grants their Workset coverage, then leaves ctx.sections unchanged; it neither renders those newly carried seeds into K nor checks mandatory information lost from the tail. A selected carry seed whose original tool result was dropped can therefore remain KNOWN without any surviving serialized source view. Use the common projection rebuild or equivalently recompile/revalidate before publishing the new Workset. Regression: pressure evicts a referenced source observation; it must be serialized as a seed in the rebuilt request or lose KNOWN authority, and missing mandatory evidence must trigger rehydration/refusal.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-110 - Note supersession commits removal of the old note before validating its replacement

- Task: [P2.6.1](TODO.md#L1302).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/kb/Notes.kt#L74).
- Analysis: KbWriter.supersede calls write(old.copy(Superseded)) and then write(replacement), each in its own transaction. Replacement size/kind validation or a database failure can occur after the old admitted note has already been removed from active retrieval. The failed supersession thus leaves no active replacement and loses authoritative guidance despite reporting failure. Validate both records before mutation and commit their revisions/index changes atomically. Regression: oversized or conflicting replacement, and a fault during its insert; the old admitted note must remain active until a complete replacement commits.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-111 - Knowledge reads return reusable note text without applying redaction

- Task: [P2.6.2](TODO.md#L1309).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/tool/kb/KbTool.kt#L130).
- Analysis: KbTool.entry returns StoreKb's full Markdown directly; search includes the raw query and summaries, and result only detects instruction shapes/estimates size. Neither StoreKb nor KbTool applies the configured Redaction boundary. In particular harness-generated STATUS archives can contain register/decision text without going through candidate-note admission, so a secret embedded there can be replayed to the model through kb.get even when file reads would redact it. Apply the same model-facing redaction/mask policy at every KB rendering boundary and preserve protected raw recovery separately. Regression: synthetic secrets in STATUS, a note body/summary and a search query must never appear in the rendered tool result.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-112 - KB entry reads can exceed the turn read budget by an unbounded amount

- Task: [P2.6.2](TODO.md#L1309).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/tool/kb/KbTool.kt#L130).
- Analysis: The dispatcher reserves a fixed KB read estimate, but kb.get/skill renders the entire entry and does not consult TurnContext.readBudget or a result token cap. STATUS is explicitly exempt from Note.MAX_BODY_TOKENS and accumulates archived records, so a legitimate checkpoint can grow without bound and be returned in a single result. Post-execution reconciliation observes the overrun after all bytes are already in the transcript, potentially forcing pressure exit and repeated loss/retrieval. Bound the rendered view using the admitted budget and provide a durable paging/recall mechanism. Regression: a large STATUS read must stay within the actual admitted result budget while preserving access to omitted records.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-113 - Knowledge dependency invalidation is disconnected from live workspace changes

- Task: [P2.6.3](TODO.md#L1315).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/kb/NoteHorizon.kt#L19).
- Analysis: NoteHorizon has no production registration or construction. Cell Coherence registers Workset, register, Checks and touched-ledger listeners only. StoreKb.stale checks anchors but ignores validity.dependsOn; Injection only checks dependency-note status and anchor bytes, not pinned dependency versions. A note depending solely on src/config.py@oldHash or another contract version can thus remain Admitted and be retrieved/injected as current after that dependency changes. Register the project horizon and reconcile it on reopen, and validate pinned dependencies at serve time as a fail-closed fallback. Regression: mutate a dependency-only path or contract version during a real campaign/reopen; kb.get/search and injection must report or exclude the stale note.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-114 - Cancelled or externally blocked campaigns are counted as failed calibration samples

- Task: [P2.6.4](TODO.md#L1320).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Calibration.kt#L49).
- Analysis: The observation adapter maps every non-verified/non-explicitly-cancelled increment in any Ended campaign to CalibrationOutcome.Failed. Host cancellation, waiting_for_input, blocked_external and budget interruption commonly leave the increment InProgress/Blocked, so they enter the kernel's eligible Completed-or-Failed denominator instead of being censored Cancelled/Unfinished. This biases turn medians and overrun rates that guide later sizing. Derive observation outcome from both campaign disposition and increment evidence, preserving censoring. Regression: identical completed work plus a host-cancelled partial must leave eligible sizing statistics unchanged.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-115 - Calibration rewrites all historical samples into the current harness and sizing series

- Task: [P2.6.4](TODO.md#L1320).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Calibration.kt#L45).
- Analysis: observations(store, series) reads every saved campaign and assigns the caller's one CalibrationSeries to every row. Controller.extract supplies the current attempt harness version/current policy, so older attempts from different frozen versions or sizing policies are relabelled as current rather than kept in separate groups. CalibrationStats correctly groups its input but cannot recover lost provenance. Read series provenance from each attempt's frozen configuration/metadata and keep incompatible histories separate. Regression: store campaigns from two harness/policy versions, aggregate after upgrade, and verify that their series and derived priors remain distinct.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-116 - A root package closure can be complete while pinning no files

- Task: [P3.1.1](TODO.md#L1363).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [execution path](core/src/main/kotlin/io/astrolabe/evidence/ClosureManifest.kt#L79).
- Analysis: Pure runtime probe: ClosureManifest.of(Closure.Package("."), [src/a.py, tests/test_a.py], versionOf) returns Complete with paths={} and membership={.=[]}. Package membership compares paths against './' without normalizing the valid root spelling used elsewhere (PackageCommands.ROOT is '.'). Re-pinning that closure can therefore remain unchanged when actual package inputs change, producing an unsound reuse proof. Canonicalize root/package paths consistently before enumeration and reject a completeness claim when enumeration failed. Regression: '.', normalized root and equivalent package spellings must pin the same files and invalidate on membership/content changes.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; successful stdin JShell probe against current compiled classes; synthetic inputs only.
- Fix (2026-09-26, `43f8a15`): Equivalent root-package spellings pin identical file sets and detect content changes.


### F-117 - Unresolved TypeScript path aliases can be treated as complete dependency information

- Task: [P3.2.1](TODO.md#L1401).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/atlas/ImportGraph.kt#L218).
- Analysis: For a non-relative JS/TS import, graph construction tries only known package.json names and otherwise silently treats the import as external. It does not inspect tsconfig paths/baseUrl or mark such unresolved local aliases uncertain. Under a syntax-tier outline source, graph.complete can consequently be true while an internal '@app/helper' dependency is absent; Impact then narrows the blast/test set using incomplete edges. Resolve declared aliases or conservatively mark ambiguous bare imports incomplete. Regression: a syntax-tier project with a tsconfig path alias and a test importing through it must include that dependent test or widen scope. Default lexical-tier impact already widens conservatively; no tier-1 runtime reproduction claimed.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-118 - A truncated reference lookup clears all impact-review obligations for the symbol

- Task: [P3.2.4](TODO.md#L1420).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/cell/Cell.kt#L399).
- Analysis: Cell calls impact.inspected(target) for every look(refs) outcome with status=ok, without requiring complete, untruncated results or any displayed reference coverage. ImpactNudges.inspected removes all pending definitions sharing that symbol. A tiny-budget lookup can thus show only a fraction of the callers, yet remove the public-definition exit-gate requirement for all of them. Track inspected reference identities/ranges and preserve missing obligations when a result is incomplete/truncated. Regression: change a public symbol with more callers than fit the reference result; one small lookup must not clear every caller obligation.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-119 - A transform can create unauthorized files inside its declared glob and still be accepted

- Task: [P3.3.1](TODO.md#L1454).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/tool/edit/Transform.kt#L219).
- Analysis: Edit scope-checks only the pre-existing inventory before launching a transform. Post-run changedInScope is classified solely by scopeGlob, without ScopeGuard/contract/protected-path validation of newly created paths. With only src/a.py initially allowed, a broad glob can pass preflight, then create src/b.py or a new protected path inside the glob and return accepted=true. Trusted-local limitations explain why effects cannot always be prevented, but not accepting unauthorized mutations as successful edits. Revalidate every actual changed/created path against current committed scope/protection and report/refuse unauthorized effects with guarded recovery. Regression: a transform creates a previously absent, contract-forbidden file under an otherwise matching glob; it must not be accepted.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-120 - Transform diffs are stored as reusable artifacts before any redaction

- Task: [P3.3.1](TODO.md#L1454).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/tool/edit/Transform.kt#L261).
- Analysis: TransformRun builds a diff from raw before/after source and stores it directly as BlobKind.DIFF without Redaction or the recovery flag used for protected preimages/postimages. BehaviourSnapshots similarly stores characterization bytes as ordinary BlobKind.OUTPUT. Synthetic credentials embedded in changed lines/goldens therefore survive in reusable artifacts even if later display text is redacted. Redact model-facing/reusable views before storage; retain byte-exact comparison/recovery data only in the explicitly protected raw class, with safe derived views. Regression: secrets in a transformed source line and characterization file must be absent from reusable artifacts while protected inverse/equivalence data remains usable.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-121 - Reordering existing test lines is classified as a harmless addition

- Task: [P3.4.2](TODO.md#L1477).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [execution path](core/src/main/kotlin/io/astrolabe/verify/TestIntegrity.kt#L134).
- Analysis: Runtime probe: change a Python test from 'assert False; return' to 'return; assert False' by reordering the two existing lines. TestIntegrity returns kind=additions-only, blocksCompletion=false. The classifier uses multisetMinus(lines(before), lines(after)), losing order; identical line multisets produce no removals/additions, so the additions-only exemption disables review even though the failing assertion became unreachable. Use an order-aware diff and conservatively classify control-flow changes; additions also need semantic caution because redefining a test can disable earlier checks. Regression: reordered return/assert, duplicate-test redefinition and control-flow-only changes must not be labelled harmless.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; successful stdin JShell probe against current compiled classes; synthetic inputs only.
- Fix (2026-09-26, `f060c97`): Order-aware changed spans detect assertion/control-flow reorderings. Existing test-file additions conservatively require review (D-261).


### F-122 - The flaky retry reuses the ordinary execution path instead of forcing an isolated rerun

- Task: [P3.6.1](TODO.md#L1507).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [execution path](core/src/main/kotlin/io/astrolabe/tool/verify/Verify.kt#L318).
- Analysis: runTriaged reruns every Failed check by calling runOne again with the same scheduler. Controller configures candidates only for isolation-enabled/S3 paths, so normal S0-S2 retries run again on the same live tree rather than a pinned disposable candidate. If the first failing check changed inputs or residual state, the second result describes different conditions; it may repeat side effects and is not the required isolated flake check. Force a pinned isolated rerun with explicit comparable inputs/environment, or record that isolation is unavailable instead of claiming flake triage. Regression: a first failed run mutates input/state; retry must use the original pinned candidate and preserve both attempts' provenance.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.

### F-123 - Campaign full-suite and quality gates bypass receipt certification requirements

- Task: [P3.6.2](TODO.md#L1513).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Location: [execution path](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L1562).
- Analysis: fullSuite treats the suite as Green when registry.last is Passed at the current stamp, without checking testedInputs.eligible or Scheduler.currency.certifies. A test that mutates/restores its input can therefore be accepted here despite an ineligible receipt. Configured quality gates are checked only for Outcome.Failed: Unavailable, Timeout, Inconclusive, stale or ineligible results are ignored, and a later suite run can invalidate their earlier passes without another currency check. Require a current eligible pass for every declared final gate at one final candidate, preserving undeclared as a separate case. Regression: passing-but-mutating suite, missing quality runner, timed-out quality gate and gate stale after suite; none may produce final acceptance.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; source trace and cited existing tests, no new runtime reproduction claimed.
- Fix (2026-09-26, `cf3e80d`): Every declared quality/full-suite gate must have current eligible passing evidence at one final stamp. Missing runners, mutating suites and quality gates stale after the suite remain uncertified.


### F-124 - Admission rollback does not restore the superseded predecessor

- Task: [P4.1.1](TODO.md#L1550).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [kb/Curator.kt](core/src/main/kotlin/io/astrolabe/kb/Curator.kt#L88).
- Analysis: Admit B superseding admitted A, then roll back B's batch. batch() marks A Superseded; rollback() returns only B to Candidate. Neither version is available to injection. Rolling back an older batch after later supersession can also change a note owned by a later batch. Persist before-state/revision ownership for all affected notes and restore transactionally, rejecting later-batch conflicts. Regression: replacement rollback restores A; rollback across later supersession preserves its state.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-125 - Stale-note rechecks accept unresolved and wrong-version dependencies

- Task: [P4.1.2](TODO.md#L1557).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [kb/Curator.kt](core/src/main/kotlin/io/astrolabe/kb/Curator.kt#L129).
- Analysis: recheck strips @version, resolves only note IDs and treats missing dependencies as true. A stale note depending on a changed source path or contract version, without versioned anchors, is immediately readmitted. Injection.eligibility repeats the missing-target/stripped-version logic. Resolve typed note/path/contract dependencies and recorded versions; unknown resolution must retain stale status. Regression: moved path, missing contract and newer contract revision cannot readmit or inject old advice.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-126 - Focus notes bypass freshness and role eligibility

- Task: [P4.1.3](TODO.md#L1562).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [kb/Injection.kt](core/src/main/kotlin/io/astrolabe/kb/Injection.kt#L250).
- Analysis: Controller passes all notes directly to KnowledgeUse/FocusNotes (Controller.kt:1295). render checks only captured Admitted status and matching path, not Injection.eligibility, current versions, dependencies or role scope. A note excluded by ranking as moved can appear in [A]; invalidations after opening cannot update captured Note instances. Reuse current eligibility checks on each render. Regression: moved-anchor, superseded-dependency and role-excluded notes stay absent, including mid-cell invalidation.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-127 - Extractor token usage is logged without budget reconciliation

- Task: [P4.2.1](TODO.md#L1572).
- Severity: medium. Confidence: confirmed_source. Status: open.
- Location: [campaign/Controller.kt](core/src/main/kotlin/io/astrolabe/campaign/Controller.kt#L1017).
- Analysis: ExtractionResult.tokens becomes ExtractionReport.tokens and a journal line saying charged, but Controller.extract discards the report after exporting the finish receipt. No reservation or Accounting call consumes this usage. A supplied Extraction implementation can spend per packet without changing campaign totals/allowance. Reserve and reconcile extraction usage before exporting final totals, including failed calls. Regression: nonzero scripted usage changes billed totals and respects remaining allowance.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-128 - Delegation exceptions reconcile real child work as zero spend

- Task: [P4.4.1](TODO.md#L1605).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [delegate/Delegator.kt](core/src/main/kotlin/io/astrolabe/delegate/Delegator.kt#L253).
- Analysis: runChild converts every RuntimeException (including coroutine cancellation) to Failed with Tokens.ZERO. ChildCells and Writers also return zero when the cell exits through null cancellation. Work already performed before cancellation/exception therefore releases the entire reservation although it may have billed usage. Independent child accounting must reconcile observed usage on every terminal path; keep unknown charges reserved until resolved. Regression: child spends tokens then throws/is cancelled; tree budget retains that spend and another dispatch cannot reuse it.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-129 - Probe packets accept fabricated evidence aliases and unread ranges

- Task: [P4.4.2](TODO.md#L1612).
- Severity: medium. Confidence: reproduced. Status: open.
- Location: [delegate/Probe.kt](core/src/main/kotlin/io/astrolabe/delegate/Probe.kt#L207).
- Analysis: pointer accepts any nonempty #alias without resolving it or checking that the probe received it; range validation checks only a path's readVersions, not displayed line coverage. Observed claims can cite #does-not-exist or unseen line 999999 of a partially read file and are published as observed; freshness even reports every alias Current. Pass the actual evidence resolver and displayed ranges to validation. Regression: nonexistent/unseen aliases and out-of-coverage ranges are gaps, while shown evidence succeeds.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; successful stdin JShell probe against current compiled classes; synthetic data only. Current compiled Probe.parse accepted an observed claim citing #nonexistent with shown={} and returned Parsed.

### F-130 - Review reuse bypasses the failed-required-check veto

- Task: [P4.4.3](TODO.md#L1618).
- Severity: high. Confidence: confirmed_source. Status: fixed (2026-09-26).
- Location: [delegate/ReviewCell.kt](core/src/main/kotlin/io/astrolabe/delegate/ReviewCell.kt#L160).
- Analysis: obtain reuses a matching approved record and returns Approved before outcome(packet, record), where failedRequired is checked. After approval, rerunning a check to a failing result on the same candidate/versions does not invalidate the cached verdict; even a record previously returned Declined for failed checks has record.approved=true and can be reused. Apply the current packet's check veto to cached and fresh approvals and persist effective acceptance consistently. Regression: same-candidate approval then red receipt, and repeated calls on an initially red approved verdict, remain Declined.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.
- Fix (2026-09-26, `210763d`): Cached approvals pass through current required-check veto; persisted ReviewRecord includes failedRequiredChecks and approved reflects the veto.


### F-131 - Cache scheduling can exceed declared maximum delays

- Task: [P4.5.3](TODO.md#L1660).
- Severity: medium. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [route/CacheSchedule.kt](core/src/main/kotlin/io/astrolabe/route/CacheSchedule.kt#L66).
- Analysis: The due-now check does not reserve capacity for future deadlines. With ready A(maxDelay=2), B(1), C(0), D(0), and previous key matching only D, order() chooses D,A,B,C. C moves from index 2 to 3 despite maxDelay=0: all A/B/C deadlines were 2 and taking D first made them infeasible. Before preferring a cache match, verify that remaining slots can still meet every deadline (or use a deadline-feasible scheduling algorithm). Regression: this four-slot case plus exhaustive small permutations verifies position <= original index + maxDelay.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; successful stdin JShell probe against current compiled classes; synthetic data only. Current compiled CacheSchedule.order on A(delay 2), B(1), C(0), D(0), previous=D returned [D, A, B, C], violating C's delay bound.
- Fix (2026-09-26, `f060c97`): Cache preference is accepted only when remaining unit jobs can meet all deadlines; seeded schedule regressions.


### F-132 - Repair allowance is durably consumed only after side effects finish

- Task: [P4.6.3](TODO.md#L1707).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [campaign/Recoveries.kt](core/src/main/kotlin/io/astrolabe/campaign/Recoveries.kt#L158).
- Analysis: CampaignRecovery.repair invokes the helper before incrementing repairs and appending the REPAIR journal record. A crash or thrown helper/acceptance exception after effects but before that append leaves no spent allowance for replay; resume grants the same scoped repair again, possibly repeating unknown effects. Record/reserve the repair attempt before dispatch and reconcile its result afterward; recover incomplete attempts before retry. Regression: inject failure after helper effects but before journal completion, resume, and verify no unaccounted extra repair or blind replay.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-133 - Integration publication does not prove the complete tested candidate equals the published tree

- Task: [P5.1.3](TODO.md#L1765).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [delegate/Integrator.kt](core/src/main/kotlin/io/astrolabe/delegate/Integrator.kt#L278).
- Analysis: After combined checks and gates, publish hashes only the packet's changed paths against their original after hashes. It neither verifies the integration candidate's full stamp nor proves the resulting main stamp equals the one certified by checks/review. A check or review-time process can alter another input on the disposable tree; staged changed paths still pass and only those paths reach main. Bind checks/gates to one full verified candidate manifest and revalidate it before publication, then compare the resulting tree identity. Regression: combined check changes a dependency outside the patch, or review changes it after checks; publication must refuse. S3 uses worktrees and TreeVerification rather than Scheduler.runIsolated, so this is an independent full-tree publication gap; F-079/F-080 remain relevant to the ordinary isolated final-verification path.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-134 - Integration fails to recheck the current contract and generation inside the publication lock

- Task: [P5.1.3](TODO.md#L1765).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [delegate/Integrator.kt](core/src/main/kotlin/io/astrolabe/delegate/Integrator.kt#L280).
- Analysis: admit checks contract version, but publication never compares now.contractVersion with dispatch versions. It also captures current() before waiting for main.mutation; its generation snapshot can be superseded while waiting. An amendment during combined verification or generation change during lock contention can therefore publish an obsolete patch if the authority callback otherwise allows it. Read current authority/generation/contract inside the lock immediately before effects and validate all dispatch versions. Regression: amend during checks and supersede while lock acquisition is suspended; no bytes publish.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-135 - Multi-file integration can leave a partially published main workspace

- Task: [P5.1.3](TODO.md#L1765).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [delegate/Integrator.kt](core/src/main/kotlin/io/astrolabe/delegate/Integrator.kt#L296).
- Analysis: Publication writes files sequentially with Files.write/delete and then marks the intent committed. If the second write fails, earlier files remain changed; no rollback runs and the intent contains only handles/path count, not durable staged postimages or preimages. S3Round finally removes worktrees, so the private recovery source can also disappear. Stage a recoverable transaction manifest and backups before effects, preserve recovery data until terminal reconciliation, and restore on failed publication. Regression: deny/fail the second of two writes and inject a crash mid-loop; resume restores or completes the exact patch without accepting a mixed tree.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-136 - Publication can proceed after cancellation while awaiting approval

- Task: [P5.2.1](TODO.md#L1793).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [campaign/Publisher.kt](core/src/main/kotlin/io/astrolabe/campaign/Publisher.kt#L207).
- Analysis: Publications checks c.refusal once at entry, then Publisher.publish suspends in authority.approve and immediately calls perform after a valid reply. Neither a cancellation/lost lease during that await nor between requested stages is rechecked. The original snapshot can thus be pushed, merged or deployed after the campaign loses publication authority. Supply a live fence and recheck it after approval and before every effect; keep immutable committed-tree identity binding. Regression: cancel while authority approval is pending, return Approved, and assert no commit/push/deploy occurs.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-137 - HTTP QA receipts claim candidate isolation without binding the server to that candidate

- Task: [P5.3.1](TODO.md#L1803).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [delegate/QaDriver.kt](core/src/main/kotlin/io/astrolabe/delegate/QaDriver.kt#L189).
- Analysis: The HTTP path ignores the isolated root passed by Scheduler and sends a request directly to any admitted loopback URL using the host HttpClient. Nothing starts or identifies a server from the exported candidate, so an unrelated/stale localhost service can pass while the scheduler labels the result isolated at the candidate stamp. Loopback does not prove disposable environment or tested-code identity; a Confined runner does not confine this HTTP call either. Require a lifecycle-managed service bound to the candidate/environment and route calls through that environment. Regression: an unrelated localhost service returning expected text cannot certify another candidate.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-138 - QA stores and reports unredacted process and HTTP output

- Task: [P5.3.1](TODO.md#L1803).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [delegate/QaDriver.kt](core/src/main/kotlin/io/astrolabe/delegate/QaDriver.kt#L214).
- Analysis: executed writes the raw transcript directly as BlobKind.LOG without Redaction; drive puts the same output in QaCase.observed, later persisted/exported in QaRunRecord. Credentials printed by a product or returned by HTTP survive in reusable logs and finish artifacts, bypassing the ordinary Run redaction boundary. Redact reusable transcripts and observed fields before storage/rendering, keeping raw data only under an explicit protected policy if necessary. Regression: synthetic secrets in CLI/HTTP output are absent from logs, QA records and finish exports.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-139 - Generated tool registrations retain mutable script and capability collections

- Task: [P5.6.1](TODO.md#L1840).
- Severity: high. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [tool/GeneratedTools.kt](core/src/main/kotlin/io/astrolabe/tool/GeneratedTools.kt#L123).
- Analysis: ToolRegistry stores the caller's GeneratedTool object; ToolSet retains the same object and precomputes its digest. script/capabilities/tests are mutable collection references. Mutating the original script after boundary() silently changes execution within the active attempt while version, catalog and digest remain unchanged; mutating capabilities can remove a required declaration. Deep-copy and expose unmodifiable collections at registration and attempt boundaries; include capability requirements in the digest. Regression: change original and returned collections after registration/boundary, and verify the active executable, requirements and digest cannot diverge.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; successful stdin JShell probe against current compiled classes; synthetic data only. After ToolSet construction, mutating the retained script list from [git,status] to [git,push] changed resolve().script while digestUnchanged=true.
- Fix (2026-09-26, `75e3280`): Generated tools deep-copy and freeze script/capability/test collections; capability requirements and unambiguous argv contribute to the digest.


### F-140 - Watcher feed renders results green against a newer workspace stamp

- Task: [P5.7.1](TODO.md#L1854).
- Severity: medium. Confidence: reproduced. Status: fixed (2026-09-26).
- Location: [verify/Watcher.kt](core/src/main/kotlin/io/astrolabe/verify/Watcher.kt#L110).
- Analysis: render(stampNow) uses stampNow only for the header and passes current.values.map(line) unchanged. WatcherResult.line maps Passed to Green; ChecksRender does not compare per-result stamps. After a tree edit and before any new result arrives, an old pass is still green under the new header. Compare result.stamp with stampNow and emit Stale until a matching result arrives; synchronize record/render if feeds can deliver concurrently. Regression: record a pass at A, render at B without a later callback, and verify stale rather than green.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; successful stdin JShell probe against current compiled classes; synthetic data only. After a Passed watcher result at A, render(B) emitted the Green state's 'watcher' text under the B header, with no stale classification.
- Fix (2026-09-26, `f060c97`): Watcher results render stale when their candidate differs from the current candidate, including unknown current candidates.


### F-141 - Fixture runner loses container failures after tests have completed

- Task: [P6.1.1](TODO.md#L1878).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [eval/src/main/kotlin/io/astrolabe/eval/FixtureRunner.kt](eval/src/main/kotlin/io/astrolabe/eval/FixtureRunner.kt#L240).
- Analysis: Collector.executionFinished records a failed container only for descendant tests not already present in results. If fixture tests pass and @AfterAll fails, all descendants already exist as Passed, so the failure disappears from failed, green and invariant metrics. A failed dynamic-test factory with no discovered descendants is also dropped. Preserve failed-container outcomes explicitly and make green/invariant eligibility fail on any relevant container failure. Regression: passing FX tests followed by throwing @AfterAll, and an FX factory failure before child registration, must produce a nonzero runner exit.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

### F-142 - Shape comparator configurations collapse to the same evaluation identity

- Task: [P6.1.2](TODO.md#L1884).
- Severity: high. Confidence: reproduced. Status: open.
- Location: [eval/src/main/kotlin/io/astrolabe/eval/Campaigns.kt](eval/src/main/kotlin/io/astrolabe/eval/Campaigns.kt#L43).
- Analysis: Variants.configure(B1/B2/B3) freezes the identical base AttemptConfig and stores the differing S0/S1/S2 cap only in VariantConfig.maxShape. Shape arms do the same in EvalArms.configure. Scorecards, trial configuration IDs and PromotionDecision's frozen set use only attempt.fingerprint, so these distinct experiments collide; EvaluationDesign even rejects comparing a baseline with a candidate sharing its fingerprint. Define a configuration identity including enforced shape cap and arm/comparator semantics and use it throughout trials/design/evidence. Regression: B1/B2/B3 and each shape arm have distinct usable evaluation IDs even with identical base Config.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; successful stdin JShell probe against current compiled classes; synthetic data only. With Config(profiles=FakeProfiles.all), B1/B2/B3 caps were [S0,S1,S2] and equalFingerprints=true. The initial probe used an invalid empty profile configuration and is not evidence.

### F-143 - Promotion decision does not bind evaluated trials to the frozen workload partition

- Task: [P6.1.3](TODO.md#L1891).
- Severity: high. Confidence: confirmed_source. Status: open.
- Location: [eval/src/main/kotlin/io/astrolabe/eval/PromotionPolicy.kt](eval/src/main/kotlin/io/astrolabe/eval/PromotionPolicy.kt#L102).
- Analysis: PromotionDecision compares design.manifest to the supplied manifest fingerprint and checks missing declared candidates, but never compares design.trials with manifest.workload/assignment. EvaluationDesign accepts arbitrary PlannedTrials and validateRows checks rows only against that self-declared design. Training/selection tasks, a cherry-picked subset or invented repository cluster identities can therefore be scored under a legitimate final-manifest hash. Evidence.of additionally labels independence Pass from the manifest grouping policy without checking actual trial membership. Construct/validate the planned trial projection from the frozen evidence partition, including exact task/repetition/repository/stratum identity, and bind fixtures to the candidate. Regression: extra, omitted, relabelled and wrong-partition pairs block promotion despite matching manifest hashes.
- Evidence baseline: 9a80e117445be357285fec2cffffe0fd45290a9a; current-source trace and scoped test inspection; no new runtime reproduction claimed.

## Audit completion (2026-09-25)

- Scoped first-pass coverage: **185/185 tasks (100%)**; all P0-P6 task rows reviewed or explicitly skimmed. Simple records/contracts were skimmed; review concentrated on algorithms, authority, evidence currency, recovery and integration. P7 excluded.
- This continuation: **47 tasks reviewed, 20 findings added** (F-124-F-143), **5 runtime reproductions**.
- Findings: **143 recorded; 142 open, 1 resolved**. F-034's missing turn-revert fallback is now wired. All **29** queued historical changed-source findings were rechecked; earlier runtime claims keep their original baseline.
- Verification: **239 tests passed**, zero failures/errors/skips, across V-008-V-010. V-011 records the five synthetic runtime probes. No full build, Linux, remote CI or live-provider validation was performed.
- No task remains in the audit queue. Findings remain for the later authorized repair phase; coverage does not prove defect absence. Only this report was modified. Existing CLAUDE.md/ISSUES.md edits and todo_findings.txt were left untouched.

## Checkpoints

- 2026-09-24: Initialized report from actual task headings. Initial working tree clean. Read TODO task inventory and repository workflow. Historical summary counts are stale; coverage ledger uses headings. Beginning P0.1.

- 2026-09-24 checkpoint: P0.1.1 through P0.3.4 reviewed or explicitly skimmed. Recorded F-001-F-005 from source inspection. Next P0.3.5 (fake adapter), then P0.4 event/host/view seams. No source or test modifications.

- 2026-09-24 checkpoint: P0.3.5-P0.5.2 reviewed, F-006-F-014 recorded. Next P0.6.1. Started focused existing tests; initial sandbox denial of Gradle cache lock resolved via approved escalation. Initial JShell probes found duplicate-call acceptance and overflow acceptance, but core binaries were stale (Config.withProfiles missing); these initial binaries do not establish current-source runtime validation. Await the fresh compilation before final probe evidence.

- 2026-09-24 checkpoint: P0 complete as a scoped review (19 tasks; native ABI tables explicitly excluded from deep review). F-015-F-021 recorded. Next P1.1.1. Focused Gradle invocation succeeded: core: tests=19, failures=0, errors=0, skipped=0; provider-api: tests=15, failures=0, errors=0, skipped=0. Core selected tests executed; provider-api:test was UP-TO-DATE, not a new execution. Refreshed classes used for F-001/F-002/F-003/F-005 and Unicode search probes. Initial DB probe could not load java.sql in JShell; no DB conclusion inferred from that failed probe.

- 2026-09-24 checkpoint: P1.1.1-P1.1.4 reviewed; F-022-F-025 recorded. F-011 reproduced against fresh classes in a disposable database. Next P1.2.1, then Stamper/DirtyState/ShadowRef/Preimages/WorkspacePath in TODO order.

- 2026-09-24 checkpoint: P1.2.1-P1.2.6 reviewed. F-026-F-035 recorded. F-027 and F-035 reproduced with current compiled classes in isolated build/ fixtures; the initial Java TempRepo static-call typo was corrected before evidence collection. Next P1.3.1. Ranges is explicitly carried forward to P1.5.3. No implementation edits.

- 2026-09-24 checkpoint: P1.3.1-P1.3.4 reviewed in TODO order; F-036-F-041 recorded. Current source baseline unchanged. Atlas stale-cache/collapsed-refresh and npm-hook selection reproduced using current classes, stdin-only probes in ignored build/ fixtures. Next P1.4.1.

- 2026-09-24 checkpoint: P1.4.1-P1.4.4 reviewed; F-042-F-044 added and F-026 qualified against Coherence's documented no-throw precondition. Next P1.5.1. No code edits or new test files.

- 2026-09-24 checkpoint: P1.5.1-P1.5.3 reviewed; F-045-F-047 added. Workset missing-file recall and auxiliary-fence validator bypass reproduced. Ranges follow-up completed; F-044 transition reproduced with a corrected fully qualified Intent reference. Next P1.6.1.

- 2026-09-24 checkpoint: P1.6.1-P1.6.4 reviewed; F-048-F-057 added. Probes reproduced hidden dependency acceptance, repeated-recall source mismatch, redacted-tail coverage, same-path batch clobber, normalized-anchor replacement and stale-diff secret disclosure. Existing LookTest/EditTest fixtures invoked reflectively in isolated scratch stores; no test/source edits. Corrected F-034 against the explicitly documented create/delete limitation; actual fallback wiring remains absent. Focused tests for audited modules running under session 79923. Next P1.6.5.

- 2026-09-24 checkpoint: P1.6.5-P1.6.6 reviewed; F-058-F-069 added. Foreground Run body secret disclosure and broken result-to-observation alias reproduced (corrected probe used a real Continuation). Pure parser probes reproduced four false-green scenarios: all-skipped unittest, later Cargo failure, malformed fresh Jest report, short pytest report. Earlier focused audit-module tests finished successfully: 143 tests, 0 failures/errors/skips (session 79923). Their green results do not cover the new adversarial probes. Next P1.6.7.

- 2026-09-24 checkpoint: P1.6.7-P1.6.10 reviewed; F-070-F-074 added. Actual StateTool injected-save failure and TaskTool stale/wrong-question response reproduced (discarded an initial JShell proxy-toString infrastructure failure). Entire P1.6 is covered. Existing run/shaper/verify/state/task/kb tests running as session 74160; capture terminal result before closing checkpoint. Next P1.7.1.

- 2026-09-24 verified checkpoint: V-003 finished, 70 tests passed with zero failures/errors/skips; V-002 had 143 passing tests. Both process sessions are terminal. Report now covers 50 of 133 implemented tasks, 74 findings (27 reproduced); next P1.7.1, 83 implemented items still pending and 52 unimplemented deferred. Baseline HEAD unchanged; only findings.md is untracked/modified. No goal completion is claimed. Deep follow-ups are enumerated in metadata.

- 2026-09-25 plan checkpoint: current HEAD 9a80e117445be357285fec2cffffe0fd45290a9a; all 185 P0-P6 task headings DONE. Refreshed ledger links/statuses; converted 52 obsolete deferred entries to pending. Historical 50-task coverage and 74 findings retained with explicit baseline provenance. Working-tree changes in CLAUDE.md/ISSUES.md and untracked todo_findings.txt predate this audit continuation and are untouched. Next P1.7.1.

- 2026-09-25 checkpoint: P1.7.1-P1.7.8 reviewed/skipped as recorded; added F-075-F-083 (7 high, 2 medium; 6 runtime probes, 3 source-confirmed). V-004 passed 44 tests. F-070 rechecked and remains present; other historical findings retain their explicit prior-baseline provenance. Current scoped coverage 58/185, 127 pending; next P1.8.1. No production/test/configuration edits, full build, remote CI or P7 work.

- 2026-09-25 continuation: through P1.8.8; 66/185 covered, 90 findings; next P1.9.1. Source unchanged.

- 2026-09-25 continuation: through P1.9.6; 72/185 covered, 96 findings; next P1.10.1. Source unchanged.

- 2026-09-25 continuation: through P1.12.4; 83/185 covered, 101 findings; next P2.1.1. Source unchanged.

- 2026-09-25 continuation: through P2.3.4; 98/185 covered, 106 findings; next P2.4.1. Source unchanged.

- 2026-09-25 continuation: through P2.7.3; 113/185 covered, 115 findings; next P3.1.1. Source unchanged.

- 2026-09-25 continuation: through P3.8.2; 138/185 covered, 123 findings; next P4.1.1. Source unchanged.

- 2026-09-25 session end at user request: all started audit groups through P3.8.2 and all pending test runs finished. Coverage 138/185 (74.6%); 47 tasks remain (P4 25, P5 15, P6 7). 123 findings saved. Next P4.1.1; historical changed-source revalidation still pending. No active processes; source unchanged.

- 2026-09-25 audit completed: 185/185 P0-P6 tasks reviewed or explicitly skimmed; P7 excluded. This continuation reviewed 47 tasks and added 20 findings; 143 recorded (142 open, F-034 resolved). Rechecked all 29 queued historical findings. 239 tests passed and 5 new runtime probes reproduced defects. No active processes or source/test changes.
