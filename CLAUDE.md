# ASTROLABE

A Kotlin/JVM SDK for a coding agent harness that Java can consume, with host events/hooks and no UI coupling.
It is built offline through phases P0–P6 against a fake provider. Live transports are P7.
This file is the **only workflow source**. `TODO.md` is the progress authority: statuses, `Log:`, `Deps`, `D-nn`.
The handoff below is already loaded; do not re-read it at startup.

@CONTINUE-TASK.md

## Doc map (load on demand only, bounded sections)
| Need | Read |
|---|---|
| Architecture map, invariants, ownership | `SOTA-BEST-MIXED-AGENT.md`; `docs/architecture/principles.md` §1.3, §2; `docs/architecture/components.md` §3.2–3.3 |
| Lifecycle / roles / overview | `docs/architecture/{lifecycle,roles-shapes,overview}.md` |
| A task's spec | its `Spec:`/`Why:` links + "Read with" companions only; cited `ANSWERS.md`/`ISSUES.md` rows only |
| Binding code conventions (full text) | TODO §2.3 (`rg -n '^### 2.3' TODO.md`) |
| Accepted spec refinements | TODO §3.1; decisions TODO §3 (`rg -n 'D-64' TODO.md`) |
| Producers before consuming a type | TODO §2.4 |
| Choosing which subsystem docs to load | `READING-GUIDE.md` |
| Implemented-state snapshot, counts | `actual_state.md` |
| History (never at startup) | `audit/SESSION-HISTORY.md`, `audit/OUT-OF-ORDER-*.md` |

Skip at startup: `PREPARE_IMPLEMENTATION_PLAN.md` (historical), `sources/`, `REVIEW.md`, `docs/reference/*` history, unrelated subsystems.

## Binding conventions (from TODO §2.3 and the implementation discipline)
- Pinned build: Kotlin 2.4.20, Gradle wrapper 9.7.1, JDK 26. Versions come from `gradle/libs.versions.toml` and the
  `build-logic` convention plugin. Do not add dependencies. The existing ones are coroutines,
  kotlinx.serialization, sqlite-jdbc (explicit SQL), SLF4J, JUnit Jupiter 6.1.3 and kotlin-test.
  Use Git CLI, ripgrep and JDK FFM. `provider-api` never depends on `core`.
- `explicitApi()`. Use `data class`/`sealed interface` records and outcomes, and `enum` for doc vocabularies.
  No public `value class` and no Kotlin `Result` (D-08). Typed ids are validated at construction.
  Copy defensively at authority boundaries. Add `@JvmOverloads`/`@JvmStatic` where Java needs them.
- One `suspend`/`Flow` engine. Every host SPI has a Java form (`CompletableFuture` or synchronous).
  `io.astrolabe.java` has no `suspend`, `Flow` or `value class` (D-07).
- Names use the docs' vocabulary verbatim, with no `Manager/Impl/Helper/Service` suffixes and units in names.
  Comments cover only non-obvious invariants and the § they enforce (`// §8.7`).
- Prefer algorithms to frameworks: explicit state machines with typed transitions, and parse tool output once at the boundary.
- Determinism: policies are pure functions of records. `Clock`/`IdGen` are injected. No wall-clock in cached
  prompt regions. Timestamps are metadata, never identity (I-05).
- Ownership (L9): one writer per table. The controller commits the ledger, the verifier accepts, the curator
  publishes, and the runner assigns tool status — never the model.
- Evidence: `FileVersion` hashes raw bytes and every filesystem operation goes through `WorkspacePath` (D-47).
  Redacted bytes never grant coverage (D-49). Evidence is immutable, and consequential actions are ordered durably.
- Tests use JUnit 5 + kotlin-test, no network and the fake adapter only. Windows and Linux are equal targets (D-12).
- Storage: SQLite is canonical. `exports/` and `kb/*.md` are derived views.
- Mandatory correctness controls stay on. Optional layers go behind `Config` flags, off by default. Live gates are `UNMEASURED`.
- Keep task IDs, terminology and completion levels (`IMPLEMENTED` → `FIXTURE_VALIDATED` → `PROMOTED`).
  Anything not derivable from the docs becomes a `D-nn` item. Never redesign silently.
- Pay each TODO §1 integration debt in its owning task.
- **Push authority:** `main` only with owner approval. Cloud sessions push their session branch.
- **Delegation (owner rule):** route complex, non-trivial tasks to Fable worktree agents
  (`.claude/worktrees/agent-*`). Merge with `--no-ff` after reviewing; don't take the work on trust.
  Resolve ABI dump conflicts by regenerating, never by hand. Delegated agents follow the same verification tiers.
- **Out-of-order work** only on an explicit owner request. An ACTIVE card in TODO §1.2 takes precedence;
  the protocol is `OUT-OF-ORDER-PROPOSAL-TASKS.md`, with this file's verification tiers and session-end state rules replacing its per-substep builds and handoff updates.

## Commands
| Purpose | Windows (JDK 26, Git Bash; PowerShell: `./gradlew.bat`) | Linux cloud sandbox (JDK 25 scratch copy) |
|---|---|---|
| Environment | `export JAVA_HOME=/c/Users/user/.gradle/jdks/eclipse_adoptium-26-amd64-windows.2` | automatic: SessionStart hook bootstraps in background |
| Focused test | `./gradlew :core:test --tests 'io.astrolabe.<pkg>.<Class>Test' -q --console=plain` | `scripts/sandbox-gradle.sh :core:test --tests 'io.astrolabe.<pkg>.<Class>Test'` |
| Other module's tests compile | `./gradlew :eval:testClasses -q` | `scripts/sandbox-gradle.sh :eval:testClasses` |
| ABI regen (own invocation) | `./gradlew :core:updateKotlinAbi` (`:provider-api:`/`:eval:` if touched) | same args via wrapper; it copies `*/api/*.api` back |
| Gate build | `./gradlew build -q --console=plain` | `scripts/sandbox-gradle.sh build` |
| Counts | `rg -c '^#### P\d+\.\d+\.\d+ .*· DONE' TODO.md` (swap `DONE`/`TODO`/`IN_PROGRESS`; `P1\.` per phase) | same |

Sandbox wrapper: it waits for the bootstrap marker, syncs the tree and re-applies the retarget, then prints one OK line
with test totals, or the failure lines plus the log tail. The full log is in `${TMPDIR:-/tmp}/astrolabe-jdk25.state/last.log`,
and a manual bootstrap is `scripts/sandbox-gradle.sh --setup`. The scratch copy is never committed; CI on JDK 26 remains the authority.
CI (`.github/workflows/ci.yml`) runs `./gradlew check` (incl. `checkKotlinAbi`) on ubuntu-latest + windows-latest, JDK 26.
It triggers on **push to `main`** and on **pull requests**, so a session branch gets CI only through an open PR.

## Known failures and gotchas
- **Environmental; never block a gate and are never re-proved:** in the Linux sandbox, `FixtureReposTest` gradle-small
  (nested offline Gradle) and `SearchBackendParityTest` case-insensitive `café` (rg/locale).
- **Transient, recorded, not diagnosed:** `StamperTest` `git exited -1` under suite load (`TempRepo.runGit` has no timeout).
  Also RunTest FX-22 `bg-end` arriving before the terminal status. Re-run once; a repeat means a real failure to investigate.
- In the sandbox JDK 26 cannot be provisioned (foojay/Adoptium/GitHub downloads refused). `rsync` is absent.
  `apt-get install openjdk-25-jdk-headless` works. Maven Central answers 429 in bursts: the wrapper retries.
- Backtick test names may not contain `.`, `:` or `;` (so no `(P1.12.4)`-style task ids in names). A KDoc must not contain `*/`, even inside backticks.
- On Windows, the JDK's argument quoting escapes inner quotes for `cmd.exe /c`: fake test commands avoid inner quotes.
- The blob store enforces artifact-before-row: publish the raw blob before recording a receipt.
- A second `FixedIdGen()` in one test collides on SQLite primary keys. Share one.
- `gradlew` must stay git mode `100755`. The test JVM runs with `--enable-native-access=ALL-UNNAMED` (FFM in `os`).
- Windows worktree cleanup: `Remove-Item -LiteralPath '\\?\<path>' -Recurse -Force`, then `git worktree prune`.

## Workflow
**Bootstrap.** The handoff is already loaded. Read only the current group's task entries: locate them with `rg -n '^#### P1\.9\.' TODO.md` and read bounded ranges, never all of TODO.md.
Then run `git log --oneline -10` and `git status`. For the task at hand only, read its cited spec sections and its prerequisites' `Log:` lines.
No doc tour, no exploration. Get counts with `rg -c`.

**Batching.**
- Take dependency-ready tasks in queue order: `IN_PROGRESS`/`BLOCKED` first, then the first `· TODO` whose `Deps` are all `DONE`.
  `Deps` and TODO §2.4 govern, not numbering. A task without `Deps` depends on the earlier tasks of its work package.
  Keep the same group together.
- Size each task S/M/L, where L means a new subsystem or cross-cutting work. Aim to finish the current block, else as many tasks as fit.
  Only a genuinely L task justifies one task per session.
- Mark a task `DONE` when its `Build`/`Done` criteria pass with focused tests. A failing gate reopens only the tasks it implicates.
- After each task: set its status and a one-line dated `Log:` (what changed, files, limits, deferred owner), make a local commit,
  and continue. No summaries, no confirmation requests.
- For a new ambiguity, record a `D-nn` in TODO §3 with a safe default and continue. Ask only if the choice is expensive to reverse.
- Delegated worktree agents follow the same tiers.

**Verification tiers.**
- *Task:* focused tests only (`:core:test --tests 'io.astrolabe.<pkg>.<Class>Test'`). Write the FX/AX/IX fixtures its `Done` names, nothing beyond:
  no extra oracles, simulations or negative controls unless `Done` requires them. No full suite, build, ABI regen, push or CI.
- *Task exception:* if it changes a public API another module uses, compile that module's tests once.
- *Before any push:* if public API changed, run `updateKotlinAbi` once and commit the dumps.
- *Block gate:* at the end of a second-level group (e.g. all remaining P1.9.x) and at every phase end. A group with ≤2 remaining
  tasks merges with the next one. The gate runs full `build`, then pushes to a branch that triggers CI (main only with owner approval),
  then waits for CI on both platforms, once. Tick its `- [ ] **Gate …**` line in TODO.md with the run link.
  Fix failures before the next block; bisect via the per-task commits.
- Task entries' per-task "full build / both platforms / ABI" wording is discharged at the gate (TODO §0.1 precedence line).
- Known environmental failures (above) don't block a gate; no baseline reruns to re-prove them.
- Long commands run quietly; never paste full logs.

**State.**
- TODO statuses and one-line `Log:` lines are the progress truth.
- At session end: rewrite `CONTINUE-TASK.md` (≤40 lines: checkpoint, next tasks, debts, blockers, last gate) and refresh
  `actual_state.md` (≤60 lines: snapshot, counts). Touch TODO §1 only if debts or blockers changed.
  Append ≤10 lines to `audit/SESSION-HISTORY.md`.
- In cloud sessions, push the session branch without waiting for CI (gates excepted).

**Context budget.**
- Use the whole session; don't wind down early. No L task after ~60% context, no new task after ~75%.
- At ~80%, or when told "wrap up": get the current task compiling with focused tests green (or revert it), commit,
  write the handoff, push, and stop with a ≤10-line report.
- After auto-compaction, re-read `CONTINUE-TASK.md` and the current group, then continue.
- Never end with uncommitted or non-compiling work.
