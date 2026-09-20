# ASTROLABE 1.0.1 Kotlin SOTA AI Coding Agent Harness — actual state

## Current checkpoint (2026-09-20)

**OOO-02 / P6.1.4 DONE**, from `d1689ef`, D-57. No active override at this checkpoint.
Q/E/cost, paired repository-cluster bounds, diagnostic verdict/repayment and immutable provenance are complete.
[Journal](audit/OUT-OF-ORDER-P6.1.4.md) and [API guide](eval/README.md). Do not reimplement the kernel.
Current **63/179 DONE (35.2%), 4 IN_PROGRESS, 112 TODO**. Eighteen focused eval tests, 7,000 fixed
simulations, independent review, eval ABI and full Windows/JDK 26 offline build pass. Full build freshly
ran 18 eval tests; core 803 tests/6 skips and provider-api 15 tests were UP-TO-DATE, not new executions.
P6.1.1/P6.1.2/P6.1.3 remain TODO; live gates UNMEASURED; CI/Linux findings unchanged.
Next under the continuing out-of-order request: OOO-05 (candidate P5.1.5), admission first.
Normal return: P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2.

## Earlier P2.3.4/P2.6.5 checkpoint

**P2.3.4 / OOO-01 and P2.6.5 / OOO-03 are DONE. No active out-of-order override remains.**
Resume **P0.1.2 + P0.6.1/P0.6.2/P0.6.4, then P1.8.2**. Do not reimplement either kernel or P2.1.1.

- [Context selection journal](audit/OUT-OF-ORDER-P2.3.4.md): dependency closure, exact budget arithmetic, deterministic marginal greedy policy, omissions and capacity refusal; commit `5ec1e0c`.
- [Calibration journal](audit/OUT-OF-ORDER-P2.6.5.md): versioned grouping, deduplicated terminal/censored observations, median/ratios and pure warning; completed from `5ec1e0c` in the commit containing this checkpoint.
- Verification: **26 new focused tests**; independent random graph/exhaustive and exact-rational oracles; independent reviews resolved. Final Windows/JDK 26 build: **core 803 tests, zero failures/errors, six existing platform skips**; provider-api UP-TO-DATE (15 green results). ABI checks pass.
- Current counts: P0 15 DONE + 4 IN_PROGRESS; P1 44 DONE + 19 TODO; P2-P6 3 DONE + 93 TODO. Total **62/178 DONE (34.8%), 4 IN_PROGRESS, 112 TODO**.
- P2.3.1/P2.6.4 stay TODO: real sources/rendering/manifest/admission and sizing collection/persistence/controller events/optional CAL injection remain with their owners. OOO-02/04 remain proposals.
- Out-of-order planning refresh at `f6a4feb`: seven unimplemented proposals now follow [descending difficulty, §2](OUT-OF-ORDER-PROPOSAL-TASKS.md#complexity-order), starting with OOO-02 (candidate P6.1.4). [Handoff §9](OUT-OF-ORDER-PROPOSAL-TASKS.md#implementation-handoff) supplies implementation slices, eval setup and progress/verification requirements. All seven remain PROPOSED; no task/module activated, no count change. Apply this order only to a future out-of-order implementation request.
- Linux/remote CI findings and live gates are unchanged; no promotion claim. Earlier checkpoints below are historical.

## Earlier P2.1.1 checkpoint (2026-09-20)

- **P2.1.1 is DONE out of order**, from baseline `d0ca86a`: iterative graph algorithms, immutable snapshots, deterministic frontier, requirement dependencies, evidence-derived ledger and FX-42 protection. The existing increment/ledger types retain their ownership. Verifier results bind work/attempt/context/revision/definition, retain check/review references and reject stale review approvals.
- **No active override remains. Next: P0.1.2 + P0.6.1/P0.6.2/P0.6.4, then P1.8.2.** Do not start P2.1.2 or rebuild this component merely because it was completed ahead of schedule. Plan/controller/storage integration remains with P2.1.2/P2.1.4/P2.2.2/P2.2.4.
- **Counts at that checkpoint:** P0 15 DONE + 4 IN_PROGRESS; P1 44 DONE + 19 TODO; P2–P6 1 DONE + 93 TODO. Total **60/176 DONE (34.1%), 4 IN_PROGRESS, 112 TODO**; counted from task headings.
- **Verification:** 44 focused tests passed; final Windows/JDK 26 offline `build` succeeded. Core executed 777 tests: zero failures/errors, six existing platform skips. Provider-api was UP-TO-DATE (15 green test results). Nineteen tests added; Kotlin ABI regenerated and checked. Linux/remote CI and all live gates remain unvalidated/UNMEASURED.
- **Retained observation:** the first full build once failed RunTest FX-22 when `bg-end` arrived before the terminal status; isolated and later full runs passed. OS/run code and tests were unchanged. Keep this finding with P0.6.1/P0.6.4; a passing rerun is not a diagnosis.
- **Protocol, design choices, commands and integration owners:** [TODO §1.2](TODO.md#12-owner-authorized-analytical-work-2026-09-20) and [implementation log](audit/OUT-OF-ORDER-P2.1.1.md).
- **Future analytical work:** [OUT-OF-ORDER-PROPOSAL-TASKS.md](OUT-OF-ORDER-PROPOSAL-TASKS.md) ranks context selection, statistical evaluation, calibration and impact analysis. All four are proposals; none has been activated or added to the task count. Use its activation protocol when the owner requests further out-of-order implementation; the normal resume point above remains current.

## Session 2 + targeted readiness audit (2026-09-20) — earlier checkpoint

- Audit baseline: branch `main` at `468f5b0`; implementation checkpoint `0b94875`. `TODO.md`, `actual_state.md` and `CONTINUE-TASK.md` are tracked; only `DESCRIPTION_RU.md` was untracked before this documentation correction. Remote CI exists for `468f5b0`; historical "nothing pushed" statements are not current repository state. Leave pushes to the owner.
- **`TODO.md` is the execution authority; code and executed checks establish readiness.** First resolve reopened P0 validation (P0.1.2, P0.6.1, P0.6.2, P0.6.4); the next P1 implementation task remains **P1.8.2 `Layout` render**. The remaining P1 order is P1.8.2–P1.8.8 → P1.9.1–P1.9.6 → P1.11.1–P1.11.2 → P1.12.1–P1.12.4. See TODO §1 for remaining integration work and §1.1 for the audit evidence and exact test commands.

## What session 2 finished (all `DONE`, `IMPLEMENTED` + `FIXTURE_VALIDATED` on Windows only)

P1.4.4 Coherence · P1.1.2 S0 auto-derivation · P1.7.8 ScopeGuard/TestIntegrity · P1.6.2 Partition/Dispatcher · P1.6.3 look · P1.6.4 edit · P1.6.5 run · P1.7.2 Checker · P1.7.4 receipts/currency · P1.7.5 Baseline · P1.7.6 Reserve (`CellBudget`) · P1.7.7 ExitGate/Verifier · P1.6.7 verify · P1.6.8 state · P1.6.9 task.ask · P1.6.10 kb contract · P1.8.1 Role.

Historical audit counts before P2.1.1: **15/19 P0 tasks `DONE`, 4 `IN_PROGRESS`; 44/63 P1 tasks `DONE`, 19 `TODO`; P2–P6: 94 tasks `TODO`. Total: 59/176 `DONE` (33.5%), 4 `IN_PROGRESS`, 113 `TODO`.** Current counts are above. Before reopening failed validation, the actual headings counted 19 P0 + 44 P1 `DONE`, not 26 + 45; the previous "17 left" was also a counting error. These are task counts, not a measured fraction of code or remaining effort. The four reopened tasks have implementations, but their completion criteria are not met across the required environments.

All seven tool families exist at their P1 scope, including only a `Kb` interface/tool and `EmptyKb`, not a persistent knowledge base. There is still **no end-to-end agent**: source inspection and `jar tf`/`javap` of the built core JAR confirm that `cell` contains role configuration only, `Astrolabe` exposes constants only, and there is no controller, `AstrolabeJava` or telemetry implementation. The existing Java smoke test reads `Astrolabe.MODULE`; it does not satisfy P1.12.3's campaign smoke criterion.

## Verification evidence

- Historical local session-2 reports: 773 tests, zero failures/errors, six skips; these were inspected before targeted reruns and are not a fresh full-build result. The earlier 675 figure belongs to session 1.
- Fresh local audit: **59 selected tests passed, zero failures/errors/skips**, in two real `:core:test` executions (31 + 28; no full-suite rerun). Coverage: skeleton/Java fixture, fake provider, S0 derivation, coherence, roles, dispatcher, KB contract, look/edit/run, and the three individual scenarios that failed remotely. Targeted runs replace the local core test report; do not interpret the latest report directory as the historical full suite.
- [CI run 35514932596](https://github.com/korvin2000/ASTROLABE/actions/runs/35514932596) for `468f5b0` failed on both platforms. Linux: `./gradlew: Permission denied`, exit 126; Git records `gradlew` as mode `100644`, and tests never started. Windows: core reported 758 tests, 3 failures, 130 skips. Failures: `FixtureReposTest` TS JUnit filename assertion; `GitTest` repository-index rejection; `ProcOwnershipTest` deadline/grandchild log assertion. These three pass locally in isolation; the CI failures remain unresolved, not disproved by local success. The API listed two runs, both failed. Linux/POSIX validation remains unconfirmed.
- Kotlin ABI dumps (`core/api/core.api`) regenerated and committed after every public-API change.

## Build commands (Windows)

```bash
export JAVA_HOME=/c/Users/user/.gradle/jdks/eclipse_adoptium-26-amd64-windows.2
./gradlew :core:test --tests 'io.astrolabe.<pkg>.*' --console=plain   # focused
./gradlew :core:updateKotlinAbi                                        # after public-API changes, own invocation
./gradlew build                                                         # ≈ 2–3 min
```

Gotchas learned this session: backtick test names may not contain `:` or `;`; a KDoc must not contain the sequence `*/` even inside backticks; the JDK's Windows argument quoting escapes inner quotes for `cmd.exe /c`, so fake commands in tests avoid inner quotes; the blob store enforces artifact-before-row (publish the raw blob before recording a receipt); a second `FixedIdGen()` in one test collides on SQLite primary keys — share one.

## How to resume

1. Follow `TODO.md` §0.1, then read §1/§1.1 and the reopened P0 task logs. Restore CI validation before continuing P1; do not rewrite implemented components merely because their validation task was reopened.
2. P1.8.2 `Layout` needs: `docs/runtime/context-layout.md` §5.1, `docs/reference/kernel-contract.md` Appendix A, `context.ContractSlice` (P1.1.4), `atlas.Prime` (P1.3.2), `tool.ToolSchemas`/`Envelope` (P1.6.1), `provider.Segment`/`Request` (P0.3.2), `auth.Boundary.DATA_RULE` + `ExecutionModeLabel` (integration debt), `verify.PreexistingLedger.render()` (P1.7.5) for `[K]`, `tool.task.TaskTool.asked` for pinned user messages.
3. Keep the discipline: mark `IN_PROGRESS`, implement Build/Done, focused tests, ABI dump, full build, `Log:` line, §1 update, one commit.
