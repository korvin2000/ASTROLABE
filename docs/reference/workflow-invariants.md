# Workflow invariants (WF-1 … WF-15)

**ASTROLABE 2.0 · registry** · Source: plan `../ASTROLABE-2-PLAN.md` §7.2 (owner decision №36, 2026-10-05); defect
numbers WD-nn from `../ASTROLABE-DIAGNOSTICS-2026-10-05.md`.

Properties of the whole, not of modules: each is visible to the user and each was broken in the runs of 5 October.

## Rules

1. Every invariant has a scenario test on the **real composition** with the fake adapter — core: package
   `io.astrolabe.workflow` (`core/src/test/kotlin/io/astrolabe/workflow/`), Studio: `*WorkflowScenario*` — and a row
   here naming the files that can break it.
2. Guards count (git processes, file reads, object writes, opens, finalization attempts, model requests), never seconds.
   The phase counters are `phase.counted` events (`AgentEvent.Telemetry.PhaseCounted`, `io.astrolabe.telemetry.CountedPhase`).
3. The WF suite runs at every merge of every later session, whatever packages the line touched:
   `./gradlew :core:test --tests 'io.astrolabe.workflow.*'` (Studio: `--tests '*WorkflowScenario*'`). It stays within
   three minutes on Windows.
4. **Protection rule.** A line never disables, weakens or deletes a guard. A review remark that cannot be fixed without
   breaking an invariant is not fixed: the line reports a blocker with both sides and the owner decides (the lesson of
   D-364/D-374: the boundary cache was switched off at acceptance boundaries and nobody weighed the latency).
5. The phase counters stay in telemetry. Every session with a live run compares them with the baseline in
   `plan2/reports/SESSION-4A.md` (the W0 "before" numbers are in `plan2/reports/WP-W0.md`).
6. A live failure the suite did not catch first becomes a scenario, then gets fixed.

## Harness

`Scenario` (open a campaign on a directory, play model replies, reopen, answer a host decision, read the counters and
the outcome) over `DirtyRepo` (one commit, no `.gitignore`, an untracked `devtools/` of N small files plus a 20 MB file;
variants: the check writes `build/` output, rewrites tracked `data/notes.json`, or a file is locked). First green
scenario: `DirtyRepoScenarioTest`.

## Registry

`K/` = `core/src/main/kotlin/io/astrolabe/`; `Studio:` = the Studio backend in the root repository.
Counters: `open`/`snapshot`/`finish` = `phase.counted` events of that phase; fields `gitProcesses`, `filesRead`,
`bytesRead` (tree reads, the atlas's included), `blobsRead`/`blobBytesRead` (store blobs read back for the tree),
`objectsWritten`, `opens`, `finishAttempts`; "model requests" = the fake adapter's calls.

| № | Property | Guard | Files that can break it (WD) | Counter |
|---|---|---|---|---|
| WF-1 | One user action is one campaign open | Studio `*WorkflowScenario*` (root repo, P8.W.5): `StudioWorkflowScenarioTest` "a start opens its campaign once…", "Continue reopens the same work once…" (limit: a project with no declared checks still opens twice — needs a core `CampaignPolicy` hook, session 4B) | Studio: `StudioHost.kt` (WD-04); `K/campaign/Controller.kt` `open` | `open` events per host action (`opens`) |
| WF-2 | Boundary cost does not depend on the number of untracked files | `DirtyRepoScenarioTest` (P8.W.2) | `K/workspace/ShadowRef.kt`, `DirtyState.kt`, `Stamper.kt`, `Workspace.kt`, `K/store/BlobStore.kt`, `K/workspace/ContentCache.kt`, `K/os/Git.kt`, `K/atlas/Atlas.kt` (WD-01, WD-02, WD-07) | `gitProcesses` per `open`/`snapshot` equal at 300 and 1500 files; per capture `filesRead` + `blobsRead` ≤ files (+ atlas rows at an open), the 20 MB file read once |
| WF-3 | What is unchanged is stored once | `DirtyRepoScenarioTest` (P8.W.2) | `K/workspace/ShadowRef.kt`, `K/store/BlobStore.kt`, `K/campaign/Controller.kt` snapshot calls (WD-01, WD-07) | `objectsWritten` of the second `snapshot` after one edited file = 1; a second work's `open` writes 0 objects and no recovery blob |
| WF-4 | An unreadable file does not end the run | `UnreadableFileScenarioTest` (P8.W.2) | `K/workspace/Workspace.kt`, `Stamper.kt`, `DirtyState.kt` (WD-05) | outcome is a resumable stop naming the path, never `agent_error` (`DirtyRepo` variant `LockedFile`) |
| WF-5 | Finalization is idempotent | core: `FinalizationScenarioTest` (P8.W.1: re-entry reruns nothing, keeps its request, a second stop names what lifts it); `OutputPolicyScenarioTest` (P8.W.3: a gate writing only output under a declared root moves no candidate and asks nothing; output written while a decision is asked voids nothing; a gate rewriting its inputs stops once with `rewrittenInputs` typed — a tracked file under `build/` and a tracked source among them — and the accept records them accepted; a red gate that rewrote an input stays red) | `K/campaign/Controller.kt` `stopOrFinish`, `fullSuite`, open (frozen policy), `K/verify/Resolution.kt`, `K/verify/Scheduler.kt` (`ScratchPolicy`, tested inputs, currency), `K/workspace/Stamper.kt`, `DirtyState.kt`, `K/AttemptConfig.kt` (WD-08, WD-14, WD-15, WD-06) | `finishAttempts` without new information yields no repeated stop; a check writing only scratch output does not move the candidate (one AC-1 receipt, no decision request) |
| WF-6 | A person's or policy's decision is final for an unchanged candidate | core: `FinalizationScenarioTest` (P8.W.1: request keyed by `DecisionKey`, reissued under its id); Studio `TaskWorkflowScenarioTest.anAcceptKeptByItsKeyEndsTheTaskWithNoCheckAndNoModelCallAfterIt`, `aStoredDecisionIsFoundByItsKey…` (scoped to work and attempt), `aKeylessDecisionNeedsItsOwnCandidate` (`HostAuthority.kt`, `DecisionService.java`, `StudioDb.java`) | `K/campaign/Controller.kt` (decide, pending completion), `K/verify/Resolution.kt`; Studio: `DecisionService.java` (WD-10) | no check runs after "accept"; a stored decision applies to a repeated request |
| WF-7 | Evidence survives a reopen | core: `FinalizationScenarioTest` (P8.W.1) | `K/campaign/Controller.kt` open (check registry, held receipts) (WD-09) | no "no receipt" after a reopen without changes |
| WF-8 | Free text never silently becomes a decision | Studio `*WorkflowScenario*` (root repo, P8.W.5): `TaskWorkflowScenarioTest.freeTextOnAnOpenAcceptanceCardDecidesNothing`, `aMessageThatFindsTheCardClosedIsRoutedNotLost` | Studio: `TaskService.java` (WD-11, WD-12) | text with an open acceptance card is not recorded as "rework" without the user's choice |
| WF-9 | A blocking reviewer starts and can read | core: `ReviewScenarioTest` (P8.W.4: the review cell on the default review budget and a 64K-output model makes ≥ 1 model request and ≥ 1 `look`; a review its budget cannot admit is `unavailable` with the numbers before any model call; a failed open still emits its open `phase.counted`); Studio part pending P8.W.5 | `K/cell/Cell.kt`, `K/cell/CellContext.kt` (`boundedOutput`), `K/budget/CellBudget.kt`, `K/delegate/ReviewCell.kt`, `K/campaign/Controller.kt`, `K/cell/Role.kt`, `K/tool/verify/Verify.kt`; Studio: `ReviewPass.java` (WD-16, WD-17, WD-18) | the review cell makes ≥ 1 model request at the default budget with a large-output model; a reviewer without tools does not block |
| WF-10 | A repeatable failure continues the same work | Studio `*WorkflowScenario*` (root repo, P8.W.5): `TaskWorkflowScenarioTest.continueAfterAFailureContinuesTheSameWork`, `aMessageAfterAFailureContinuesTheSameWorkToo`; bridge "Continue from a resumable stop…" (limit: an exception inside a cell ends the core run final `failed`, `K/campaign/Controller.kt`, `Lifecycle.kt` — session 4B) | Studio: `TaskService.java`, `StudioHost.kt` (WD-26) | after a failure "Continue" opens the same `workId` |
| WF-11 | A continuation does not lose the assignment | Studio `*WorkflowScenario*` (root repo, P8.W.5): `TaskWorkflowScenarioTest.everyRunsRequestCarriesEveryUserMessageWordForWord` (order, repeats, `[End of context]` inside a message, per-run frame) | Studio: `TaskService.java` follow-up recap (WD-25) | every run's request holds all of the task's user messages verbatim, the original first |
| WF-12 | Tests are not the goal | pending P8.W.8 | `K/cell/Gates.kt`, `K/campaign/PlanNeed.kt`, `K/verify/Resolution.kt`, `K/tool/verify/Verify.kt`, `K/tool/task/TaskTool.kt`, `K/tool/run/` (WD-19…WD-23) | a task is not closed as verified on discovered suites alone; "verified independently" needs goal-level evidence |
| WF-13 | A user's message reaches the executor | pending P8.W.7 | `K/campaign/Controller.kt`, `K/contract/`; Studio: `TaskService.java` (WD-13, WD-24) | ≥ 1 model request after a message to work whose increments are all closed |
| WF-14 | Knowledge survives a boundary | pending P8.W.9 | `K/context/`, `K/cell/Cell.kt`, `K/campaign/Controller.kt`; Studio: `StudioHost.kt` (WD-27, WD-28, WD-29) | after a cell boundary, a reopen and a follow-up the next cell gets the summary, the touched list and seeds; the share of repeated reads on the fixture is bounded (`filesRead`) |
| WF-15 | Appending does not reset the cache | pending P8.W.9 | `K/context/`, `K/cell/Cell.kt` pinned rows (WD-28, WD-31) | request prefix bytes unchanged after a new message, an answered question or a review note |
