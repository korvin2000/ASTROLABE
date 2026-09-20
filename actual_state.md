# ASTROLABE 1.0.1 Kotlin SOTA AI Coding Agent Harness — actual state

## Session 2 (2026-09-20) — resume point

- Branch `main`, working tree clean except the untracked notes (`CONTINUE-TASK.md`, `actual_state.md`, `DESCRIPTION_RU.md` — the owner's files, never committed by the agent). One commit per finished task; nothing pushed (pushes are the owner's).
- **`TODO.md` is the execution authority.** §1 carries the exact next task (**P1.8.2 `Layout` render**), the remaining P1 order (P1.8.2 → P1.8.3 → P1.8.4 → P1.8.5 → P1.8.6 → P1.8.7 → P1.8.8 → P1.9.1–P1.9.6 → P1.11.1–P1.11.2 → P1.12.1–P1.12.4), the integration debts owed by upcoming tasks (one long bullet under "Resume notes"), the recorded deviations and the build commands. Every finished task has a dated `Log:` line with what it built, what it tests and what it deliberately leaves to a later task.

## What session 2 finished (all `DONE`, `IMPLEMENTED` + `FIXTURE_VALIDATED` on Windows only)

P1.4.4 Coherence · P1.1.2 S0 auto-derivation · P1.7.8 ScopeGuard/TestIntegrity · P1.6.2 Partition/Dispatcher · P1.6.3 look · P1.6.4 edit · P1.6.5 run · P1.7.2 Checker · P1.7.4 receipts/currency · P1.7.5 Baseline · P1.7.6 Reserve (`CellBudget`) · P1.7.7 ExitGate/Verifier · P1.6.7 verify · P1.6.8 state · P1.6.9 task.ask · P1.6.10 kb contract · P1.8.1 Role.

Net: 26 P0 tasks + 45 P1 tasks `DONE`; P1 has 17 tasks left (cell runtime, controller/facade, telemetry, validation). All seven tool families, the coherence protocol, receipts/currency/baseline/reserve/exit gate exist as tested components. There is still **no end-to-end agent**: the cell loop (P1.8.7), controller (P1.9.x) and facade (P1.9.6) are what wire them together.

## Verification evidence

- Last full `./gradlew build` on Windows / JDK 26: see the final line of the session report (770+ tests, 0 failures, 6 platform-gated skips expected). `RunTest`'s background-run case is timing-sensitive under parallel load (fixed by a longer child sleep in this session); if it ever flakes again, widen the sleep, do not weaken the assertion.
- Linux/POSIX code paths remain unverified locally (no WSL/Docker); the CI workflow has not run on a remote.
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

1. Follow `TODO.md` §0.1, then read §1 (next task, order, debts) and the `Log:` lines of the tasks the next task depends on.
2. P1.8.2 `Layout` needs: `docs/runtime/context-layout.md` §5.1, `docs/reference/kernel-contract.md` Appendix A, `context.ContractSlice` (P1.1.4), `atlas.Prime` (P1.3.2), `tool.ToolSchemas`/`Envelope` (P1.6.1), `provider.Segment`/`Request` (P0.3.2), `auth.Boundary.DATA_RULE` + `ExecutionModeLabel` (integration debt), `verify.PreexistingLedger.render()` (P1.7.5) for `[K]`, `tool.task.TaskTool.asked` for pinned user messages.
3. Keep the discipline: mark `IN_PROGRESS`, implement Build/Done, focused tests, ABI dump, full build, `Log:` line, §1 update, one commit.
