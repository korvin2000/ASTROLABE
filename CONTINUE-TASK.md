# Continue ASTROLABE implementation

**P2.3.4 / OOO-01 and P2.6.5 / OOO-03 are DONE. No active out-of-order override remains.**
Resume **P0.1.2 + P0.6.1/P0.6.2/P0.6.4, then P1.8.2**. Do not reimplement either kernel or P2.1.1.

- [Context selection journal](audit/OUT-OF-ORDER-P2.3.4.md): dependency closure, exact budget arithmetic, deterministic marginal greedy policy, omissions and capacity refusal; commit `5ec1e0c`.
- [Calibration journal](audit/OUT-OF-ORDER-P2.6.5.md): versioned grouping, deduplicated terminal/censored observations, median/ratios and pure warning; completed from `5ec1e0c` in the commit containing this checkpoint.
- Verification: **26 new focused tests**; independent random graph/exhaustive and exact-rational oracles; independent reviews resolved. Final Windows/JDK 26 build: **core 803 tests, zero failures/errors, six existing platform skips**; provider-api UP-TO-DATE (15 green results). ABI checks pass.
- Current counts: P0 15 DONE + 4 IN_PROGRESS; P1 44 DONE + 19 TODO; P2-P6 3 DONE + 93 TODO. Total **62/178 DONE (34.8%), 4 IN_PROGRESS, 112 TODO**.
- P2.3.1/P2.6.4 stay TODO: real sources/rendering/manifest/admission and sizing collection/persistence/controller events/optional CAL injection remain with their owners. OOO-02/04 remain proposals.
- Future out-of-order implementation: resume any ACTIVE/partial kernel first; otherwise use [descending difficulty, proposal §2](OUT-OF-ORDER-PROPOSAL-TASKS.md#complexity-order), starting with OOO-02 (candidate P6.1.4), and [implementation handoff §9](OUT-OF-ORDER-PROPOSAL-TASKS.md#implementation-handoff). The owner requested sorting/preparation only in this update; all seven pending proposals remain unregistered. Normal queue, task count and inactive override remain unchanged.
- Linux/remote CI findings and live gates are unchanged; no promotion claim. Earlier checkpoints below are historical.

**Earlier P2.1.1 checkpoint (2026-09-20):** TODO §1.2 is **COMPLETE**; **P2.1.1 is DONE** as a graph/ledger component. No active override remains: resume reopened P0 validation, then P1.8.2. Do not redo P2.1.1 or jump to P2.1.2; P2 runtime/storage integration is still pending. Dependencies, existing task IDs, acceptance and validation gates remain binding. [Implementation and verification log](audit/OUT-OF-ORDER-P2.1.1.md).

**When the owner asks for further out-of-order implementation:** read [OUT-OF-ORDER-PROPOSAL-TASKS.md](OUT-OF-ORDER-PROPOSAL-TASKS.md), first resume any ACTIVE card in TODO §1.2, otherwise select a dependency-admissible candidate using its ranking and activation protocol. The proposal itself starts no code task and changes no completion count; an ordinary request to continue the main plan follows the queue below.

Resume the existing `TODO.md` plan from the checked-out implementation: first resolve the reopened P0 validation tasks, then continue the remaining P1 work. Implement and validate successive dependency-ready tasks; preserve the architecture, ownership boundaries, terminology and task IDs. The goal is a reusable Kotlin/JVM SDK consumable from Java, with host events/hooks and no UI coupling. This is implementation work; the earlier planning-only instructions are historical.

**Earlier audit checkpoint and general workflow**

- Audit baseline: `main` at `468f5b0`, containing implementation checkpoint `0b94875`; remote CI ran against `468f5b0`. Check local status/history before editing, preserve user changes and published history, and leave pushes to the owner. Both handoff documents are tracked; `DESCRIPTION_RU.md` was untracked at audit start.
- Historical counts after P2.1.1: P0 **15 DONE + 4 IN_PROGRESS**, P1 **44 DONE + 19 TODO**, P2–P6 **1 DONE + 93 TODO**; total **60/176 DONE (34.1%), 4 IN_PROGRESS, 112 TODO**. P0 foundation code exists, but P0.1.2/P0.6.1/P0.6.2/P0.6.4 remain reopened for failed or missing CI validation. These are task counts, not code-volume or effort percentages.
- P1 has contract storage/amendments/digest/S0 auto-derivation, workspace/versioning/recovery, atlas/sniff, evidence records/journal/intents/coherence, register/workset, all seven tool families (look, edit, run, verify, state, task.ask, kb contract) with partition/dispatcher, the verification baseline (checker, receipts/currency, baseline ledger, reserve, exit gate/verifier, scope guard/test integrity), authority controls and the role table. These are components, not a working end-to-end agent: the built JAR has only role configuration in `cell` and constants in `Astrolabe`; controller orchestration and the SDK facade remain pending. `EmptyKb` is not a persistent KB, and the existing Java fixture tests constants, not a campaign.
- Resolve **P0.1.2 CI validation**, including the failures owned by P0.6.1/P0.6.2/P0.6.4 (TODO §1.1). The next P1 code task remains **P1.8.2 Layout render**, then P1.8.3 Anchor, P1.8.4 Gauge, P1.8.5 Gates, P1.8.6 Residency, P1.8.7 Cell loop, P1.8.8 ResultPacket, P1.9.1–P1.9.6 controller/facade, P1.11 telemetry, P1.12 validation. Use TODO §1's distinction between completed component wiring and outstanding runtime integration.
- Historical planning-only banners elsewhere are not current status. TODO sections 1 and 1.1 and the audit notes on reopened tasks supersede the session-2 summaries. Use actual code, executed checks and the latest task logs to establish progress. Do not redo completed components or equate scaffolding with completed integration.

**Load only the context needed**

1. Read `SOTA-BEST-MIXED-AGENT.md`, `docs/architecture/principles.md` §§1.3 and 2, and `docs/architecture/components.md` §§3.2–3.3 for invariants, ownership and identities.
2. Read `TODO.md` §§0–1, §2.3 (binding conventions), §3.1 (accepted specification refinements), and the current phase goal/validation boundary. Keep §1's integration debts and recorded deviations in view. Consult §§2.1–2.2, §2.4 and §3 only for the modules, producers and decisions relevant to the active task.
3. Select an existing `IN_PROGRESS` task or resolve a recorded blocker; otherwise take the next dependency-ready `TODO`. `Deps` and producer readiness govern, not numeric order. Read its complete entry, prerequisite `Log:` notes, cited specification sections and companion sections only where a boundary is crossed; inspect the affected implementation/tests before designing changes. P1.4.4 Coherence is already implemented and passed the targeted audit; do not restart it.

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

Historical local session-2 reports had 773 tests, zero failures and six skips. The 2026-09-20 audit freshly ran 59 selected tests (31 + 28), all passed, including isolated reruns of the three CI failures; it did not rerun the whole suite. [CI run 35514932596](https://github.com/korvin2000/ASTROLABE/actions/runs/35514932596) on `468f5b0` failed: Linux could not execute `gradlew` (mode `100644`, exit 126); Windows core had 758 tests, three failures and 130 skips (TS JUnit filename assertion, repository-index protection, deadline/grandchild log assertion). See TODO §1.1 for reproducible commands and evidence. Local passes do not resolve the remote failures; Linux remains unvalidated. Distinguish `IMPLEMENTED`, platform-qualified `FIXTURE_VALIDATED` and `PROMOTED`; all live gates remain `UNMEASURED`.

Mark `DONE` only when the task's criteria are satisfied. Append a concise dated `Log:` with changes, checks, limitations and any deferred integration owner; update §1 with the next dependency-ready task and blockers. At session end, leave the code and plan consistent and report completed task IDs, validation results, remaining limitations and the exact resume point.

**Keep the next session resumable**

P2.1.1's final local Windows build passed: core executed 777 tests, zero failures/errors, six existing platform skips; provider-api reused its 15 green test results. Focused checks passed 44 tests; 19 tests were added. ABI dump checked. The first full run's intermittent FX-22 poll/terminal-status failure is retained in the implementation log, despite isolated and later full passes. This does not close the existing remote CI/Linux findings.

When P2 is reached normally, use the completed graph component. P2.1.2 validates sanitized proposals; P2.1.4 collects sizing; P2.2.2/P2.2.4 own canonical persistence, admission, current-candidate/lease checks and resume. S1 commits the graph-derived aggregate ledger, not the S0 verifier's per-increment ledger. All live gates remain UNMEASURED.
