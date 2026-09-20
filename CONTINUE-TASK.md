# Continue ASTROLABE implementation

Resume the existing `TODO.md` plan from the checked-out implementation, starting with the remaining P1 work. Implement and validate successive dependency-ready tasks; preserve the architecture, ownership boundaries, terminology and task IDs. The goal is a reusable Kotlin/JVM SDK consumable from Java, with host events/hooks and no UI coupling. This is implementation work; the earlier planning-only instructions are historical.

**Checkpoint and starting point**

- Handoff baseline: `main` at `0b94875` (session 2 added 18 commits, one per task, none pushed); earlier commits were pushed manually by the owner. Check local status/history before editing, preserve user changes and published history, and leave pushes to the owner.
- P0 foundations are implemented. P1 has contract storage/amendments/digest/S0 auto-derivation, workspace/versioning/recovery, atlas/sniff, evidence records/journal/intents/coherence, register/workset, all seven tool families (look, edit, run, verify, state, task.ask, kb contract) with partition/dispatcher, the verification baseline (checker, receipts/currency, baseline ledger, reserve, exit gate/verifier, scope guard/test integrity), authority controls and the role table. These are components, not a working end-to-end agent: the cell loop, controller orchestration and the SDK facade remain pending.
- Start at **P1.8.2 Layout render**, then P1.8.3 Anchor, P1.8.4 Gauge, P1.8.5 Gates, P1.8.6 Residency, P1.8.7 Cell loop, P1.8.8 ResultPacket, P1.9.1–P1.9.6 controller/facade, P1.11 telemetry, P1.12 validation — the dependency order in `TODO.md` §1. The cell loop wires the finished components; §1's integration-debt bullet names every seam (coherence registration and `takeScheduled`, `Checks.refresh` after each stamp, `ScopeGuard`/`TestIntegrity` before edits, `CellBudget` admissions, `Contracts.open` after capture, workspace `ProtectedPaths` bound to the committed contract).
- The documentation's “no implementation” banners are outdated; `TODO.md` §1 and the per-task `Log:` lines are current as of session 2. Use individual task headings, latest `Log:` entries and actual code/tests to establish progress. Do not redo completed work or equate a scaffold with completed integration.

**Load only the context needed**

1. Read `SOTA-BEST-MIXED-AGENT.md`, `docs/architecture/principles.md` §§1.3 and 2, and `docs/architecture/components.md` §§3.2–3.3 for invariants, ownership and identities.
2. Read `TODO.md` §§0–1, §2.3 (binding conventions), §3.1 (accepted specification refinements), and the current phase goal/validation boundary. Keep §1's integration debts and recorded deviations in view. Consult §§2.1–2.2, §2.4 and §3 only for the modules, producers and decisions relevant to the active task.
3. Select an existing `IN_PROGRESS` task or resolve a recorded blocker; otherwise take the next dependency-ready `TODO`. `Deps` and producer readiness govern, not numeric order. Read its complete entry, prerequisite `Log:` notes, cited specification sections and companion sections only where a boundary is crossed; inspect the affected implementation/tests before designing changes. For P1.4.4, begin with `docs/state/evidence-coherence.md` §4.4 and the relevant scheduler receipt rules.

`TODO.md` is the execution/progress authority; current subsystem documents define the design, with the accepted refinements in TODO §3/§3.1 applied. `ANSWERS.md` and `ISSUES.md` are historical decisions already integrated into the plan: consult a cited entry only if its rationale or regression details are needed. Skip `PREPARE_IMPLEMENTATION_PLAN.md`, historical `sources/`, review/audit history and unrelated subsystems at startup. Record genuinely new ambiguities as `D-nn`; use a documented safe default where possible and ask only for decisions that block correct implementation.

**Other documents to be lazy-loaded if needed**
- `READING-GUIDE.md` - is only a selective loading guide containing information about how to load a parse a project specification.
- `PREPARE_IMPLEMENTATION_PLAN.md` - treat it as historical planning requirements; its “do not implement” instruction belonged to the earlier planning task.
- `actual_state.md` - brief information about previous session and last finished step.

**Implementation discipline**

- Keep the pinned build: Kotlin 2.4.20, Gradle wrapper 9.7.1, JDK 26; `gradle/libs.versions.toml` and the convention plugin define dependency versions. Existing libraries are coroutines, kotlinx.serialization, sqlite-jdbc with explicit SQL, SLF4J, JUnit Jupiter 6.1.3 and kotlin-test. Use Git CLI, ripgrep/JVM search and JDK FFM; preserve `provider-api`'s independence from `core`.
- Follow `.editorconfig`, TODO §2.3 and nearby code: compact idiomatic Kotlin, cohesive domain packages, typed records/outcomes, explicit public APIs, algorithms over extra frameworks, comments for rationale/invariants. Keep one suspend/Flow engine with Java-compatible host SPIs/facade; no public value classes or Kotlin `Result`. Inject clocks/IDs, keep policies deterministic and defaults configurable.
- Preserve harness-owned facts/acceptance, workspace-qualified raw-byte identities, displayed-coverage and redaction boundaries, immutable evidence, single mutation ownership and durable consequential-action ordering. Complete §1's integration debts in their owning tasks.
- Stay within the offline P0–P6 plan: fake provider execution and OpenAI Responses/Anthropic Messages-compatible contracts. Live transports, provider credentials/retries/HTTP, MCP networking, confined backends and live benchmarks remain P7. Optional features retain their gates; mandatory correctness controls stay enabled.
- Follow §2.3: compact domain-oriented Kotlin, cohesive packages, precise names, deterministic policies, explicit ownership, Java-consumable APIs, injected clocks/identities and algorithms over unnecessary frameworks. Preserve authority boundaries, stamped evidence, namespace-qualified coverage, mandatory lifecycle/correctness controls and honest unknown/stale outcomes. Implement small, testable slices without weakening acceptance or adding speculative scaffolding.

**Verify and checkpoint each slice**

Mark the task `IN_PROGRESS`, implement its `Build`/`Done` criteria and mapped FX/AX/IX fixtures, then run focused tests. Use JDK 26; the recorded Windows installation is `C:\Users\user\.gradle\jdks\eclipse_adoptium-26-amd64-windows.2` (`JAVA_HOME`). In PowerShell use `./gradlew.bat`; in Bash use `./gradlew`.

Run `:core:test --tests 'io.astrolabe.<pkg>.*' --console=plain` for focused checks. After public API changes, run `:core:updateKotlinAbi` and/or `:provider-api:updateKotlinAbi` in a separate invocation before `build`; inspect and retain the API dumps. Finish an integrated slice with `build`.

The last recorded Windows build (end of session 2) had 773 tests, zero failures and six platform skips; this is historical evidence, not a fresh run. Linux/POSIX validation was unverified at the checkpoint; check available CI evidence before claiming it. Distinguish `IMPLEMENTED`, platform-qualified `FIXTURE_VALIDATED` and `PROMOTED`; all live gates remain `UNMEASURED`.

Mark `DONE` only when the task's criteria are satisfied. Append a concise dated `Log:` with changes, checks, limitations and any deferred integration owner; update §1 with the next dependency-ready task and blockers. At session end, leave the code and plan consistent and report completed task IDs, validation results, remaining limitations and the exact resume point.

**Keep the next session resumable**
