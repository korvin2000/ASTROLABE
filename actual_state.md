# ASTROLABE 1.0.1 Kotlin SOTA AI Coding Agent Harness — actual state

## Session 2 + targeted readiness audit (2026-09-20) — resume point

- Audit baseline: branch `main` at `468f5b0`; implementation checkpoint `0b94875`. `TODO.md`, `actual_state.md` and `CONTINUE-TASK.md` are tracked; only `DESCRIPTION_RU.md` was untracked before this documentation correction. Remote CI exists for `468f5b0`; historical "nothing pushed" statements are not current repository state. Leave pushes to the owner.
- **`TODO.md` is the execution authority; code and executed checks establish readiness.** First resolve reopened P0 validation (P0.1.2, P0.6.1, P0.6.2, P0.6.4); the next P1 implementation task remains **P1.8.2 `Layout` render**. The remaining P1 order is P1.8.2–P1.8.8 → P1.9.1–P1.9.6 → P1.11.1–P1.11.2 → P1.12.1–P1.12.4. See TODO §1 for remaining integration work and §1.1 for the audit evidence and exact test commands.

## What session 2 finished (all `DONE`, `IMPLEMENTED` + `FIXTURE_VALIDATED` on Windows only)

P1.4.4 Coherence · P1.1.2 S0 auto-derivation · P1.7.8 ScopeGuard/TestIntegrity · P1.6.2 Partition/Dispatcher · P1.6.3 look · P1.6.4 edit · P1.6.5 run · P1.7.2 Checker · P1.7.4 receipts/currency · P1.7.5 Baseline · P1.7.6 Reserve (`CellBudget`) · P1.7.7 ExitGate/Verifier · P1.6.7 verify · P1.6.8 state · P1.6.9 task.ask · P1.6.10 kb contract · P1.8.1 Role.

Net after audit: **15/19 P0 tasks `DONE`, 4 `IN_PROGRESS`; 44/63 P1 tasks `DONE`, 19 `TODO`; P2–P6: 94 tasks `TODO`. Total: 59/176 `DONE` (33.5%), 4 `IN_PROGRESS`, 113 `TODO`.** Before reopening failed validation, the actual headings counted 19 P0 + 44 P1 `DONE`, not 26 + 45; the previous "17 left" was also a counting error. These are task counts, not a measured fraction of code or remaining effort. The four reopened tasks have implementations, but their completion criteria are not met across the required environments.

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
