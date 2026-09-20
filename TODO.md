# ASTROLABE — implementation TODO (Kotlin/JVM SDK)

**Status: planning artifact only. No source code exists yet.** Spec authority: the architecture documents under `docs/` reached from [SOTA-BEST-MIXED-AGENT.md](SOTA-BEST-MIXED-AGENT.md). This file only *sequences* the build; where it seems to conflict with a subsystem document, the document wins and the conflict becomes a `D-nn` item in [§3](#3-decisions-and-open-items). Owner decisions and their reasons are recorded in [ANSWERS.md](ANSWERS.md); plan defects found in review, with their regression fixtures, are in [ISSUES.md](ISSUES.md) (`I-nn`, tracked here as `IX-nn` rows in [§5](#5-fixture-map)). Both are decision records, not specification.

## 0 How to use this file

### 0.1 Resume protocol (every session)
1. Read the [architecture map](SOTA-BEST-MIXED-AGENT.md), [components §3.2–3.3](docs/architecture/components.md#sec-3-2), [invariants §1.3](docs/architecture/principles.md#sec-1-3) and [laws §2](docs/architecture/principles.md#sec-2).
2. Read §0–§3 of this file, then `grep -n "· IN_PROGRESS\|· BLOCKED" TODO.md`; continue that task, else take the first `· TODO` task of the current phase whose `Deps` are all `DONE` (listed order is a hint; `Deps` is authoritative; a task without `Deps` depends only on earlier tasks of its own work package). Check the [artifact → producer table §2.4](#24-artifact--producer-table) before consuming a type: its producer must be `DONE`.
3. Load only the `Spec:` links of that task plus their "Read with" companions, and the `ANSWERS.md`/`ISSUES.md` rows the task cites. Do not preload the whole `docs/` tree.
4. Implement; run the narrowest checks; update the task status, append a `Log:` line, update [§1 Progress](#1-progress).
5. Anything not derivable from the docs → add a `D-nn` item, choose a default if safe, never redesign silently.

### 0.2 IDs, status, tags
- **IDs** are stable: phase `P<n>`, work package `P<n>.<m>`, task `P<n>.<m>.<k>`, decision `D-nn`, runtime fixture `FX-nn` ([§5](#5-fixture-map)), adapter fixture `AX-nn`, issue-regression fixture `IX-nn` (from `ISSUES.md` I-nn).
- **Status** is the last token of a task heading after `·`: `TODO` · `IN_PROGRESS` · `BLOCKED(D-nn|reason)` · `DONE`. Work-package and phase status is derived (all tasks `DONE`).
- **Completion levels** (three, never conflated — I-19, [§19.1](docs/evaluation/method.md#sec-19-1)): `IMPLEMENTED` (named artifacts and integration paths exist) → `FIXTURE_VALIDATED` (the phase's FX/AX/IX harness tests pass on Windows and Linux) → `PROMOTED` (its predeclared live quality/cost/regression gate passed). Under this plan every live gate stays **`UNMEASURED`** (P7); optional layers stay off for normal use until promoted; mandatory correctness controls are never disabled in a production configuration (D-48).
- **Kind tag** after the ID: `[C]` contract/scaffold (types, interfaces, storage schema) · `[M]` minimal working implementation · `[I]` integration between components · `[V]` validation/hardening (fixtures, fault injection) · `[O]` optional/deferred (carries its evaluation gate; ships behind a `Config` flag, off by default).
- **Task fields** (only where useful): `Why` · `Deps` · `Pkg` (module/package) · `Build` (expected types/functions) · `Spec` (doc sections, fixtures, `I-nn`) · `Notes` · `Done` (completion criteria) · `Log` (appended by the implementer: date, decisions, files).
- Numbers in tasks are the [declared defaults §17](docs/reference/defaults.md#sec-17); all live in `Config`, none hard-coded.

### 0.3 Scope boundary of this plan
In scope: the whole architecture through S3 as a library, validated with a **fake provider adapter**. Out of scope ([P7](#p7-deferred-out-of-scope-boundary)): live provider transports, auth, retry/backoff, HTTP clients, MCP client transports, confined-runner backends, live benchmark campaigns. Every phase states which fixtures it must pass and which platform/provider assumptions remain unsupported. The roadmap rule "no stage starts before the previous gate is measured" ([§18.2](docs/implementation/roadmap.md#sec-18-2)) is interpreted for this offline plan as: engineering proceeds through P6 with each live gate recorded `UNMEASURED` and its prerequisites named; promotion claims wait for live evaluation (I-19, D-28).

## 1 Progress
- **Phase:** P1 · **Next task:** P1.1.1 · **Blocked:** none
- **Open decisions needing an owner answer:** none (D-01, D-02, D-09, D-12, D-15 answered 2026-09-20 in `ANSWERS.md`; provisional defaults are labelled in §3)
- **Session log**
  - 2026-09-20 — plan created from docs 1.0.1; no Gradle project, no code. Local machine has git 2.45 and ripgrep, **no JDK/Gradle installed** (install JDK 26 + Gradle 9.7.0 wrapper before P0.1.1).
  - 2026-09-20 — implementation started: P0.1.1 skeleton + P0.1.2 CI green locally (Windows, JDK 26). Toolchain found on the machine: Temurin 26.0.2.1 under `~/.gradle/jdks/`, Gradle 9.7.1 wrapper dist cached, ripgrep 15.2, git 2.45, Python 3.14, Node 24. Linux is validated only through the CI workflow (no local Linux runtime).
  - 2026-09-20 — first independent review applied (30 findings). Second review (`ANSWERS.md` D-01–D-42 + R01–R20, `ISSUES.md` I-01–I-25) applied: §3 re-dispositioned, D-43–D-53 added, §2.4 producer table, P1.2.6/P1.7.8 added, IX fixture rows, risk table in §6.

## 2 Target structure and conventions

### 2.1 Gradle modules (create a module only when its first task starts)
| Module | Artifact | Purpose | Phase |
|---|---|---|---|
| `build-logic/` | — | convention plugin `astrolabe.kotlin-library`: Kotlin 2.4.20 with `-Xjdk-release=26`, Java `--release 26`, JDK 26 toolchain for compile, tests and the Gradle daemon (D-02), Gradle **9.7.0** wrapper pinned with `distributionSha256Sum`, explicit dependency versions in the catalog, `explicitApi()`, `abiValidation()`, `java-library`, `java-test-fixtures`, `maven-publish`, JUnit 5 | P0 |
| `provider-api/` | `astrolabe-provider-api` | item model, capabilities, adapter SPI (Kotlin + Java forms), invocation ids, billable usage/price types, tool schema types, token-estimation abstraction. **No networking; no dependency on `core`.** Future provider modules depend only on this. | P0 |
| `core/` | `astrolabe-core` | everything else (one process, packages below); `testFixtures` = fake adapter, temp repos, scripted model, fault injection | P0–P5 |
| `index-treesitter/` | `astrolabe-index-treesitter` | tier-1 symbol index/syntax via `io.github.tree-sitter:ktreesitter` (native binary; optional) | P5 |
| `eval/` | `astrolabe-eval` | evaluation runner: fixture suites, frozen campaigns, research arms, scorecard, promotion; separate entry point | P6 |
| deferred | `astrolabe-provider-openai`, `-anthropic`, `-compat`, `astrolabe-mcp-client`, `astrolabe-runner-confined-*`, `astrolabe-cli` | see P7 | — |

Libraries (pin explicit versions at P0.1.1): `kotlinx-coroutines-core` (+`-jdk8` for `CompletableFuture` bridges), `kotlinx-serialization-json`, `org.xerial:sqlite-jdbc` (bundles FTS5), `org.slf4j:slf4j-api`, JUnit 5 + `kotlin-test`; optional `com.knuddels:jtokkit` (OpenAI-family token estimates, D-06), `io.github.tree-sitter:ktreesitter` (P5). OS bindings use the JDK's `java.lang.foreign` (FFM) API, no JNA (D-43). No Spring, no Exposed/SQLDelight (D-03), no JGit (D-04), no YAML library (D-24).

### 2.2 Package map (`core`, root package `io.astrolabe`, D-01)
| Package | Owns (docs component) | Key types |
|---|---|---|
| `id` | four identities, hashing, versions, stamps [§3.3](docs/architecture/components.md#sec-3-3) | `WorkId`, `AttemptId`, `ContextId`, `WorkspaceId`, `Generation`, `Identities`, `Digest`, `FileVersion`, `Stamp` (canonical encoding, I-05) |
| `budget` | budgets, reservations [§8.1 reserve](docs/verification/scheduler.md#sec-8-1) | `Tokens`, `Budget`, `Reservations`, `Reserve` (`TokenEstimator` lives in `provider-api`) |
| `event` | UI/host hooks (outbound events, inbound authority, read views) | `AgentEvent` (sequenced), `Phase`, `Events`, `EventSink`, `Authority` (+ Java form), `Views` |
| `store` | external project state root (D-44), SQLite, blobs [§4](docs/state/contracts.md#sec-4) | `Layout`, `ProjectLock`, `Db`, `BlobStore`, `Migrations` |
| `os` | OS adapter, process ownership, git CLI, search | `Os`, `Proc`, `ProcessOwner` (FFM job object / process group, D-43), `Git`, `Search` |
| `contract` | Task Contract, amendments [§4.1](docs/state/contracts.md#sec-4-1) | `Contract`, `Requirement`, `Acceptance`, `Constraint`, `Scope`, `Authorization`, `Amendment`, `Contracts` (store), minimal `Increment`/`Ledger` records (S0 form) |
| `graph` | requirement graph, increments, ledger [§4.2](docs/state/contracts.md#sec-4-2) | `RequirementGraph`, `Increment` (full), `Ledger`, `Sizing` |
| `workspace` | version registry, stamps, dirty state, shadow ref, preimages, path contract [§4.6](docs/runtime/workspace-editing.md#sec-4-6) | `Workspace`, `WorkspacePath` (D-47), `VersionRegistry`, `Stamper`, `DirtyState`, `Snapshot` (manifest + blobs, D-53), `ShadowRef`, `Preimages` |
| `atlas` | orientation, index tiers, import graph, impact [§7](docs/repository/navigation.md#sec-7) | `Atlas`, `Prime`, `Sniff`, `Outline`, `SymbolIndex`, `ImportGraph`, `Impact`, `Focus` |
| `evidence` | journal, aliases, receipts, observations, claims, intents, coherence [§4.3–4.4](docs/state/evidence-coherence.md#sec-4-3) | `Journal`, `Alias` (campaign-global `#n`, D-46), `Receipt`, `TestedInputs` (D-45), `Observation` (with redaction mask, D-49), `Claim`, `Intent`, `Coherence` |
| `register` | STATE register [§5.2](docs/runtime/register-workset.md#sec-5-2) | `Register`, `Op`, `Patch`, `Validator`, `RegisterRender`, `ContractDigest` |
| `workset` | Workset, KNOWN/NOT SEEN [§5.3](docs/runtime/register-workset.md#sec-5-3) | `Workset`, `Entry`, `Coverage`, `Seeds` |
| `tool` (+ `tool.look`, `tool.edit`, `tool.run`, `tool.verify`, `tool.state`, `tool.task`, `tool.kb`) | seven families, envelope, turn partition, runner [§5.4–5.5](docs/runtime/tools.md#sec-5-4) | `ToolCall`, `Envelope`, `Gauge`, `Partition`, `Dispatcher`, `Catalog`, `Look`, `Edit`, `Transform`, `Runner`, `Shaper`, `Verify`, `StateTool`, `TaskTool`, `KbTool` |
| `cell` | cell runtime [§3.7](docs/architecture/lifecycle.md#sec-3-7), [§5](docs/runtime/context-layout.md#sec-5) | `Role`, `Cell`, `Layout`, `Anchor`, `Gates`, `Residency`, `Turn`, `ResultPacket` |
| `context` | compiler, manifest, carry-forward, rebuild, pre-compilation [§6](docs/context/compiler.md#sec-6) | `Compiler`, `ContractSlice`, `Manifest`, `CarryForward`, `Rebuild`, `Precompile`, `Admission` |
| `verify` | scheduler, receipts validity, checker, gate, guards, review protocol, refactor mode [§8](docs/verification/scheduler.md#sec-8) | `Check`, `Checks` (registry), `Scheduler`, `Closure`, `Applicability`, `Checker`, `ChecksRender`, `Baseline`, `ExitGate`, `Verifier`, `ScopeGuard`, `TestIntegrity`, `ReviewRequest`, `Verdict`, `RefactorMode`, `Flaky` |
| `campaign` | controller, shapes, lifecycle, attempt config, resume, finish receipt [§3.5–3.7](docs/architecture/roles-shapes.md#sec-3-5) | `Controller`, `Shape`, `ShapeSelector`, `Lifecycle`, `AttemptConfig`, `Lease`, `Cancellation`, `Resume`, `FinishReceipt` |
| `kb` | knowledge base, curator, skills, extractor, calibration [§4.5](docs/knowledge/records.md#sec-4-5), [§12](docs/knowledge/learning.md#sec-12) | `Note`, `NoteKind`, `Kb`, `KbWriter`, `Index`, `Queue`, `Curator`, `Injection`, `Skill`, `Extractor`, `CalibrationStats` |
| `delegate` | packets, probe/review/QA/writer, integrator [§10](docs/operations/delegation.md#sec-10) | `TaskPacket`, `InvestigationPacket`, `Delegator`, `Probe`, `ReviewCell`, `QaCell`, `Writer`, `Integrator`, `MergeQueue`, `Ownership` |
| `recover` | failure classes, fingerprints, ladder, capsule, alternatives [§13](docs/operations/recovery.md#sec-13) | `FailureClass`, `Fingerprint`, `ErrorSignature`, `Ladder`, `Capsule`, `Repair`, `Alternative`, `Reconcile` |
| `route` | profiles, tiers, selection, escalation [§11](docs/operations/routing.md#sec-11) | `Tier`, `TierTable`, `Router`, `Escalation`, `CalibrationLog` |
| `auth` | execution authority, boundary, rules trust, redaction, permission ladder [§14](docs/platform/security.md#sec-14) | `Capability`, `Ceiling`, `Boundary`, `RulesTrust` (D-32), `Redaction`, `PermissionLadder` |
| `telemetry` | spans, four quantities, accounting, exports [§15.2, §15.5](docs/platform/adapters.md#sec-15-2) | `Span`, `Quantities`, `Accounting`, `Outcomes`, `Export` |
| `java` | Java-facing facade and SPI bridges (D-07) | `AstrolabeJava`, `JavaProviderAdapter`, `JavaAuthority` |
| root | facade + config | `Astrolabe`, `Config`, `Defaults`, `Mode` |

`provider-api` package `io.astrolabe.provider`: `Item` (sealed), `ToolSchema`, `ToolMask`, `Segment`, `Request`, `Estimate`, `TokenEstimator`, `InvocationId`, `Invocation`, `Response`, `StopReason`, `Capabilities`, `Profile`, `PriceTable`, `BillableUsage`, `Money`, `ProviderAdapter` (+ `JavaProviderAdapter` SPI form), `ProviderError`.

### 2.3 Code conventions (binding for every task)
- **Kotlin style:** `explicitApi()`; `data class`/`sealed interface` for records and outcomes; `enum` for closed vocabularies from the docs (status words, effect classes, kinds); no `value class` in public API (Java mangling, D-08); no Kotlin `Result` in public API; typed ids validated non-empty/canonical at construction; immutable collections or defensive copies at authority boundaries; `fun interface` for callbacks; `@JvmOverloads`/`@JvmStatic` where Java callers need them.
- **Async and Java (D-07, I-14):** one `suspend`/`Flow` engine inside `core`; every host-implemented SPI (`ProviderAdapter`, `Authority`, `EventSink`) has a Java-implementable form (`CompletableFuture`-based or synchronous) with documented cancellation propagation, exception mapping, callback threading and resource closure; the `io.astrolabe.java` package contains no `suspend`, `Flow` or `value class`.
- **Names:** docs vocabulary verbatim (`Increment`, `Receipt`, `Workset`, `Stamp`), precise verbs, no `Manager/Impl/Helper/Service` suffixes (the docs' "delegation service" is `Delegator`), explicit units in names (`tokens`, `seconds`, `bytes`).
- **Structure as documentation:** comments only for non-obvious invariants, protocol constraints (e.g. "assistant calls precede their results"), and the doc section they enforce (`// §8.7`).
- **Algorithms over frameworks:** sorted interval lists for displayed ranges; hash sets/maps for closures and joins; BFS over the import graph for blast radius; Kahn/Tarjan for requirement-graph and note-dependency cycles; bounded heaps for eviction order and note top-k; explicit state machines (`Lifecycle`, `Intent`, `Proc`, `Invocation`) with typed transitions; parse tool args and runner output once at the boundary into internal types.
- **Determinism:** every policy (`select_shape`, `select_profile`, `compile`, eviction, gates) is a pure function of records, logged with its inputs; `Clock` and `IdGen` injected; no wall-clock or counters in cached prompt regions; capture timestamps are metadata, never part of an identity (I-05).
- **Ownership (L9):** one writer per store table; controller commits ledger, verifier accepts, curator publishes notes, runner assigns tool status — never the model. One controller process per project store (D-44).
- **Evidence and paths:** `FileVersion` hashes raw bytes, metadata caches are hints (I-05); every built-in filesystem operation goes through the `WorkspacePath` contract (D-47); redacted bytes never grant coverage (D-49).
- **Tests:** JUnit 5 + kotlin-test; fixtures from [§19.3](docs/evaluation/fixtures.md#sec-19-3) and `ISSUES.md` are harness tests first; no network; fake adapter only; Windows and Linux are equal targets and both run in CI from P0 (D-12).
- **Storage rule:** SQLite is canonical for structured records; Markdown/JSON under `exports/` and `kb/*.md` are derived views ([§4](docs/state/contracts.md#sec-4)); durability policy per D-44.

### 2.4 Artifact → producer table
A task may consume a type only after its producing task is `DONE` (I-01). Extenders add fields/behaviour without moving ownership.

| Artifact | First declared (producer) | Extended by |
|---|---|---|
| `Identities`, `Stamp` (canonical encoding), `FileVersion`, `Digest`, `IdGen` | P0.2.1 | P1.2.2 (computation) |
| `Item`, `ToolSchema`, `Segment`, `Request`, `Estimate`, `TokenEstimator`, `InvocationId`, `Invocation`, `Response` | P0.3.1–P0.3.2 | — |
| `Capabilities`, `Profile`, `PriceTable`, `BillableUsage`, `Money`, `UsageNormalizer` | P0.3.3 | P4.5.1 (tier table data) |
| `ProviderAdapter`, `JavaProviderAdapter`, `ProviderError` | P0.3.4 | — |
| `Tokens`, `Budget`, `Reservations`, `Reserve` | P0.2.2 (`Deps: P0.3.3` for `Money`) | P1.7.6 |
| `AgentEvent`, `Events`, `EventSink`, `Authority`, `JavaAuthority`, `ReviewRequest`, `Verdict`, `Views` | P0.4.1–P0.4.3 | P4.4.3 (review cell) |
| `Config`, `Defaults`, `AttemptConfig` | P0.1.3 (core sections) | P1.8.1 roles, P1.10.3 redaction, P4.1.3 injection weights, P4.5.1 tier table |
| `Layout`, `ProjectLock`, `Db`, `BlobStore`, schema v1 | P0.5.1–P0.5.2 | migrations per phase |
| `Os`, `Proc`, `ProcessOwner`, `Git`, `Search`, test kit | P0.6.1–P0.6.4 | P5.1.1 (worktrees) |
| `Contract`, `Acceptance`, `Amendment`, `Contracts`, minimal `Increment`/`Ledger` | P1.1.1 | P2.1.1 (graph, dependencies, sizing) |
| `WorkspacePath`, `VersionRegistry`, `Stamper`, `DirtyState`, `Snapshot`, `ShadowRef`, `Preimages` | P1.2.1–P1.2.6 | P5.1.1 |
| `Atlas`, `Prime`, `Outline`, `SymbolIndex`, `Sniff` | P1.3.1–P1.3.4 | P3.2, P5.4 |
| `Journal`, `Alias`, `Receipt`, `TestedInputs`, `Observation`, `Claim`, `Intent`, `Coherence` | P1.4.1–P1.4.4 | P2.6.3, P3.1.2, P5.1.3 |
| `Register`, `Op`, `Patch`, `Validator`, `Workset` | P1.5.1–P1.5.3 | P2.4 |
| `ToolCall`, `Envelope`, `Gauge`, `Partition`, `Dispatcher`, tool families | P1.6.1–P1.6.10 | P2.1.5, P3.2.3, P3.3, P4.4.1, P4.7 (unmasked ops) |
| `Check`, `Checks`, `Scheduler`, `Checker`, `ChecksRender`, `Baseline`, `ExitGate`, `Verifier`, `ScopeGuard`, `TestIntegrity` (baseline) | P1.7.1–P1.7.8 | P3.1, P3.4 |
| `Role`, `Cell`, `Layout`, `Anchor`, `Gates`, `Residency`, `ResultPacket` | P1.8.1–P1.8.8 | P2.5, P4.4.6 |
| `Lifecycle`, `Controller`, `Lease`, `Cancellation`, `FinishReceipt`, `Compiler` (S0 form), `Astrolabe`, `AstrolabeJava` | P1.9.1–P1.9.6 | P2.2, P2.3 |
| `Boundary`, `RulesTrust`, `Capability`, `Ceiling`, `Redaction`, `PermissionLadder` | P1.10.1–P1.10.4 | P5.2 |
| `Span`, `Accounting`, `Outcomes`, `Export` | P1.11.1–P1.11.2 | — |
| `RequirementGraph`, `Sizing`, plan role, `task.propose` | P2.1.1–P2.1.5 | — |
| `ContractSlice` (full), `Manifest`, `CarryForward`, `Rebuild`, `Admission` | P2.3–P2.5 (S0 `ContractSlice` from P1.1.4) | P3.7 |
| `Note`, `Kb`, `KbWriter`, `Index`, `CalibrationStats` | P2.6.1–P2.6.4 | P4.1–P4.3 |
| `ImportGraph`, `Impact`, `Transform`, `RefactorMode`, `Flaky`, `Precompile` | P3.2, P3.3, P3.5, P3.6, P3.7 | — |
| packets, `Delegator`, `ReviewCell`, `Router`, `TierTable`, `Ladder`, `Fingerprint`, `Capsule`, `Mount` | P4.4–P4.7 | P5.1 |

## 3 Decisions and open items
Dispositions follow `ANSWERS.md` (2026-09-20): **CONFIRMED** (owner answer) · **KEEP** (default stands, with the stated clarification) · **REVISED** (corrected before the affected task) · **PROVISIONAL** (engineering estimate, not a measured optimum; tunable) · **DEFERRED EVIDENCE** (experiment defined now, result only from live evaluation). No row is `OPEN`.

| ID | Question | Resolution | Gates |
|---|---|---|---|
| D-01 | Group/root package/artifact prefix | **CONFIRMED** `io.astrolabe` / `astrolabe-*` (domain ownership confirmed). Registry verification and publishing credentials are release work, not authorized by this. | P0.1.1 |
| D-02 | JVM target | **CONFIRMED** JDK 26 for build toolchain, Java/Kotlin targets (`--release 26`, `-Xjdk-release=26`), Gradle daemon, tests and supported runtime; no Java 17/21 compatibility work; JDK 27 not used (Gradle/Kotlin support stops at 26); JDK upgrades are deliberate version boundaries. | P0.1.1, P1.12.3 |
| D-03 | SQLite access | **KEEP** `sqlite-jdbc`, explicit SQL, small tx/migration helper; `foreign_keys=ON` per connection; durability and transaction boundaries per D-44; blob publication before the referencing transaction. | P0.5.1 |
| D-04 | Git access | **KEEP** `git` CLI with a temporary index and expected-old-id ref updates; Git 2.20 is a proposed minimum to *test*, not a substitute for feature tests; raw recovery snapshots bypass content conversion (D-53). | P0.6.2 |
| D-05 | Search backend | **REVISED** ripgrep with a documented common-subset JVM fallback: literal vs regex mode, supported regex syntax subset, ignore rules, hidden/binary files, Unicode/case rules, symlink policy; unsupported patterns fail explicitly; zero matches / partial / failed / denied remain distinct. | P0.6.3 |
| D-06 | Token estimation | **REVISED** heuristic estimates (bytes/3.6 with code-aware tweaks; jtokkit optional) are for *planning*; dispatch uses a profile-specific admission contract recording estimator id/version, exact vs estimated, uncertainty margin and headroom, including effective history and every serialized contribution; unknown continuation size ⇒ fresh lineage or a capacity result (I-17). | P0.3.2, P2.3.3 |
| D-07 | Async API style | **REVISED** `suspend`/`Flow` inside; `io.astrolabe.java` facade **and** Java-implementable inbound SPIs (`JavaProviderAdapter`, `JavaAuthority`, `EventSink`) with defined future cancellation, exception mapping, callback threading, resource closure and bounded event delivery; a Java-authored adapter and authority are tested (I-14). | P0.3.4, P0.4.2, P1.9.6, P1.12.3 |
| D-08 | Identity types | **KEEP** validated public `data class`es; no value-class mangling or Kotlin `Result` in the Java surface; immutable collections/defensive copies at authority boundaries; ids validated non-empty/canonical. | P0.2.1 |
| D-09 | First language/runner set | **PROVISIONAL** order Python → JS/TS → JVM, all three represented in P1 fixtures; implement one end-to-end runner slice (Python) first; `node --check` never claims TypeScript validation (project TS checking belongs to its declared checker); missing required runner ⇒ `unavailable`; unsupported inline syntax ⇒ `not_run`. cargo/go/dotnet/mocha/unittest in P3.1.4. | P1.6.6, P1.7.2, P3.4.2 |
| D-10 | Inline syntax mechanism | **KEEP** cheap language CLI where available; tree-sitter later (both ERROR and MISSING nodes count); accepted file types and parser completeness declared; a syntax receipt concerns the exact file version and is never behavioural acceptance; otherwise `not_run`. | P1.6.4, P5.4.1 |
| D-11 | Confined runner backend | **KEEP** trusted-local implementation, `ConfinedRunner` contract only; if a host *requires* confinement, configuration/dispatch fails rather than substituting trusted-local; the execution label describes limitations, it enforces nothing. | P1.10.2 |
| D-12 | Platforms | **CONFIRMED** Windows and Linux equal support targets, OS-contract tests from P0.6.1 on both; current hands-on testing is on Windows; the native process-control mechanism is named and tested before job-object/process-group semantics are claimed (D-43); macOS and other modes unsupported until tested. | P0.6.1, P1.12.4 |
| D-13 | Environment fingerprint (`env_id`) | **REVISED** versioned canonical fingerprint: OS/arch, resolved tool/checker/parser identities and versions, relevant effective environment values as protected digests (names alone are insufficient), build flags, lock/dependency state, runner policy id, external fixture identities; unknown relevant inputs prevent cross-candidate reuse. | P1.2.2 |
| D-14 | Redaction | **KEEP, stricter boundary** configurable regex set + env allowlist, heuristic and labelled; redaction limits recorded per observation; raw logs never exposed through recall/export unredacted; signed opaque provider items never rewritten into invalid replay artifacts; exact preimages in protected recovery storage; redacted ranges grant no coverage (D-49). | P1.10.3 |
| D-15 | State location | **REVISED** external durable per-project root, host-configurable, keyed by canonical repository identity (D-44); no `.git/info/exclude` mutation; worktrees share the project store with distinct `workspace_id`s; authoritative state never in a disposable cache and never inside the source tree ([§4](docs/state/contracts.md#sec-4)). | P0.5.1 |
| D-16 | `select_shape` size classes S/M/L | **PROVISIONAL** S: ≤ 3 expected files ∧ 1 requirement ∧ one package; L: cross-package ∨ > 10 expected files ∨ ≥ 4 requirements; else M. Risk, required review, authority and unavailable capabilities override file counts; incomplete discovery is `unknown`, never low risk; recalibrate only from outcome evidence. | P2.2.1 |
| D-17 | Contract digest ≤ 150 tokens | **REVISED** bounded digest of the *current authorized objective* (not always the first request): objective excerpt, obligation ids/status/currency, critical exclusions; deterministic truncation labels even when the first sentence exceeds the cap; full acceptance definitions stay mandatory in `[K]` and review packets (D-52). | P1.1.4 |
| D-18 | `greedy_cover` admission | **KEEP** deterministic greedy after mandatory content; unit = note ∪ transitive `depends_on` bundle with cycle detection; marginal added tokens priced so shared dependencies count once; mandatory content that cannot fit ⇒ rescope / larger profile / capacity result; a bounded heuristic, not an optimal-cover claim. | P2.3.1 |
| D-19 | Failure-fingerprint normalization | **REVISED** normalize only identified volatile data (hex ids, timestamps, temp paths, addresses); preserve workspace-relative path/module, error code and meaningful literals; keep a coarse `ErrorSignature` separate from the full attempt `Fingerprint` (error + attempted fix + state + requirement) so a changed fix cannot erase repeated-error detection. | P4.6.2 |
| D-20 | Model-facing tool schemas | **REVISED** seven logical families with local mask enforcement always; a schema is frozen only after its adapter/profile/role lineage validates it (supported dialect and capability behaviour recorded); masking preferred where valid ([§15.1](docs/platform/adapters.md#sec-15-1) makes universal masking conditional on provider correctness); a new lineage may select a supported schema or return an explicit unsupported-profile result; schemas never change mid-session (I-24). | P1.6.1 |
| D-21 | MCP mounts | **KEEP** mount/catalog contracts in core, MCP networking deferred; server annotations are hints; local approval and capability validation decide effects and dispatch rights; descriptor text is data. | P4.7.1 |
| D-22 | Second-model calls (judge, probe, extractor, repair) | **KEEP** one adapter contract with routed helper profiles and fakes; helper calls consume the originating budget and obey cancellation/evidence rules; a scripted judge tests protocol only, never judgment quality or savings. | P4.4, P4.5 |
| D-23 | Review before review cells exist (Stage C) | **KEEP and make reachable** `Authority.review` satisfies a *required-review capability* in P2–P3 with sequential execution, recorded as a substitution; it never implies probes, routing or repair helpers; no available required reviewer ⇒ `blocked` (I-03). | P2.2.1, P3.5.2 |
| D-24 | KB Markdown front matter | **KEEP** fixed export-only YAML subset with deterministic quoting/escaping, no YAML parser; SQLite canonical; no Markdown import/recovery path — typed records are recovered, Markdown is for views and golden tests only. | P2.6.1, P1.5.1 |
| D-25 | Serialization | **KEEP with versioning** `kotlinx.serialization` JSON with schema version and provider/model/protocol provenance on every persisted record; native provider items as opaque `JsonElement` replayed unchanged only on a compatible lineage; explicit task/evidence data transfers across providers; protected native replay storage and redacted presentation are separate views. | P0.3.1, P0.5.2 |
| D-26 | Concurrency and process ownership | **REVISED** structured coroutine lifetimes (`SupervisorJob` per campaign), bounded parallel reads, serialized workspace mutation; a process-local mutex excludes nothing outside the process, so the baseline is **one controller process per project store** enforced by an OS file lock (D-44), with S3 workers inside that owner; cancellation performs bounded evidence/reservation cleanup and never loses late effects; slow event listeners never hold mutation or database locks. | P0.5.1, P1.6.2, P1.9.4, P0.4.1 |
| D-27 | Test identity per runner | **REVISED** namespaced runner-native identities: (check, project/module, file/suite, name, parameterization) as needed; duplicates preserved with multiplicity, never overwritten; ambiguous matching cannot prove pre-existing failure or equivalence (D-50). | P1.6.6, P1.7.5 |
| D-28 | Comparators B0/B-HELM and all live gates | **DEFERRED EVIDENCE** offline harness now; every live gate recorded `UNMEASURED` with prerequisites; fakes cannot satisfy B0/B-HELM, memory, routing or S3 gates; completion levels per §0.2 (I-19). | P6, P7 |
| D-29 | Logical cache breakpoints | **KEEP, qualified** S/R/K/T breakpoints are logical hints; the adapter maps supported mechanisms, limits and minimums or uses uncached operation if policy permits; fake cache behaviour is a deterministic simulation, not a prediction of provider hits; billable cache-write classes preserved (I-16). | P0.3.2, P0.3.5 |
| D-30 | Result ids and dedup | **REVISED** campaign-global persisted `#n` aliases (never reset per cell) mapped to canonical journal/artifact ids, allocated transactionally incl. children, never recycled, with context/workspace provenance; `look` dedup requires semantic request equivalence and sufficient live coverage, not `(what, target, version)` alone (D-46; I-06, I-07). | P1.6.1, P1.6.3, P2.4.4 |
| D-31 | S0 default write scope | **KEEP with authority semantics** repository-local scope minus protected defaults (`.git/`, CI config, lockfiles, migrations dirs); scope bounds possible writes, it authorizes no unrelated work; already-authorized CI/lockfile/migration changes belong in the committed contract, not behind a blanket prompt; basic enforcement exists in S0 (P1.7.8, I-02). | P1.1.2, P1.7.8 |
| D-32 | Rules-file trust | **REVISED** discovery (`.astrolabe/rules.md`, `AGENTS.md`, `CLAUDE.md`) only *proposes* candidates; host configuration or explicit user authority binds canonical path + digest + provenance before the snapshot becomes instruction text; otherwise the file stays repository data; an approved snapshot is reused across resumes; changed bytes do not inherit approval (I-08). | P1.3.2, P1.10.1 |
| D-33 | Anchored hunk semantics `{anchor, near?, new}` | **KEEP, clarified** exact matching first; then a tightly specified whitespace normalization (blank runs collapsed, trailing blanks ignored, line endings normalized) with an explicit mapping back to original-byte spans; apply `near` (a literal within 20 preceding lines) to select among candidates, then require exactly one non-overlapping span inside currently displayed coverage; `new` replaces the anchored span (insertion = anchor + inserted text); semantically ambiguous whitespace/string cases refuse with candidates rather than choosing. Removing normalization would be a spec amendment ([§9.1](docs/runtime/workspace-editing.md#sec-9-1)). | P1.6.4 |
| D-34 | `risk_floor` tier mapping | **REVISED** declared `packet.risk` maps explicitly (`blast_radius ≥ 3` ∨ reversibility = hard ∨ declared high ⇒ `high`; `blast_radius = 2` ⇒ `medium`); contract touch and fan-in (≥ 20 ⇒ `high`, ≥ 5 ⇒ `medium`) supplement it and can never erase a high-risk obligation whose dependencies were not discovered; function and risk floors re-applied after every adjustment (I-23). Versioned with the tier table. | P4.5.1 |
| D-35 | `adjust_with_calibration` | **PROVISIONAL, automatic demotion OFF** promotion to a higher tier allowed after ≥ 20 logged quadruples for the feature class with > 30 % verified failure; demotion requires comparable *verified outcomes for the proposed cheaper tier* from a frozen/shadow evaluation — sample thresholds alone never authorize it; never below `max(never_below, risk_floor)`. | P4.5.1 |
| D-36 | Harness-origin KB notes vs "curator is the sole publisher" | **KEEP** one serialized `KbWriter` with explicit authority per operation: harness STATUS records are same-task checkpoints (`admitted_by: harness`, lint-exempt, never injected elsewhere); the curator alone publishes reusable admitted knowledge; shared plumbing never turns a model proposal into a harness fact. | P2.4.3, P2.6.1 |
| D-37 | Injection score weights and threshold | **PROVISIONAL** `w_scope = 3, w_dep = 2, w_fresh = 1, w_use = 1, w_evid = 1, w_len = tokens/500`, threshold 2.0, with bounded feature definitions/ranges declared before use; staleness is an eligibility check before ranking; mandatory CON/invariant content bypasses optional caps but counts toward total admission; tuned from held-out usefulness and failure data. | P4.1.3 |
| D-38 | Role policy text ownership | **KEEP** frozen, versioned SDK default texts ([Appendix A](docs/reference/kernel-contract.md#sec-appendix-a) for implementing/writer; [§3.4](docs/architecture/roles-shapes.md#sec-3-4) duties for others) with host overrides that change wording only, never executor authority or acceptance obligations; mandatory shared policy is preserved even beyond a cosmetic line count; text version recorded in attempt and compile fingerprints. | P1.8.1, P2.1.2, P4.4.6, P3.7.1 |
| D-39 | `ambiguous_bug` and `measured slack` | **PROVISIONAL** `ambiguous_bug` = defect-class request with no reproducible failing acceptance `run:` at open, or ≥ 2 plausible causes recorded by the plan cell (no reproducible failure is *uncertainty*, not proof of a defect); `measured slack` = remaining budget ≥ 1.5 × the sequential-S1 estimate including reservations, review, verification, integration and an uncertainty margin, in consistent units, and the parallel-cell limit not exhausted; logged as `select_shape` inputs; `1.5×` cannot enable S3 before its promotion. | P2.2.1, P5.1.4 |
| D-40 | Impact pre-scan at campaign open | **REVISED** candidate paths = paths named in the request ∪ lexical hits of request identifiers ∪ atlas hubs of the focus subsystem; the pre-scan is *incomplete discovery*: record its coverage, unresolved dependencies and explicit risk; no hits never establishes no contract impact; refresh when actual touched symbols/paths become known and stop/upgrade before a now-disallowed action (I-23). | P3.2.6 |
| D-41 | Transform jail before a confined backend exists | **KEEP with a firm limit** trusted-local transforms run only when trusted-local mode is authorized; a post-hoc diff is not a jail and cannot disprove outside/network effects; inventories, preimages, expected counts, honest effect status and required checks/review are the controls; a confinement requirement returns `unsupported` until the P7 backend exists. | P3.3.1 |
| D-42 | Calibration prior before the KB path exists | **KEEP** deterministic P2 `CalibrationStats` block (optional prior, off until its ablation) and the later admitted `CAL` note path; never inject both; model-written CAL text is never treated as measured statistics; provenance, versioning and decay retained. | P2.6.4, P4.2.1 |
| D-43 | Process ownership backend (I-20) | **REVISED/NEW** `ProcessOwner` via JDK FFM (`java.lang.foreign`): Windows job objects (kill-on-close, breakaway denied) and POSIX process groups (`setsid`/`setpgid`, `killpg`); durable log capture to store-owned files with cursors; terminal record written by a supervisor independent of the requesting coroutine; five distinct outcomes: observation timeout (FX-22, harness alive), execution deadline, cancellation request, confirmed termination, lost/unknown; processes do **not** survive harness death by default, so after a crash their handles resolve `lost` and are never relaunched blindly; a detached/survivable mode is a separate later capability with its own fixtures. | P0.6.1, P1.6.5, P2.2.4 |
| D-44 | Storage root, ownership, durability (I-21) | **REVISED/NEW** root = `<host-configured or OS user-state dir>/astrolabe/projects/<repo-identity>/` with `repo-identity = digest(root commit id, canonical repository path)`; shared by all worktrees of the repository with workspace-qualified records; `ProjectLock` (OS file lock) enforces one controller process per project; SQLite `foreign_keys=ON`, WAL, `synchronous=FULL`; blob bytes and directory fsynced before the referencing transaction commits; bounded orphan-blob recovery; unsupported flush/rename guarantees fail closed; recovery coverage stated per platform: process termination always, OS/power loss only where fsync semantics hold. | P0.5.1 |
| D-45 | Tested inputs and receipt currency (I-04) | **REVISED/NEW** a receipt carries `tested_inputs` (paths@versions from its declared closure ∪ observed access) and `input_stability ∈ {exclusive, isolated, unknown}`: `exclusive` = the workspace mutation lock is held for the whole check and a post-check rescan detects any subprocess write; `isolated` = an exported candidate whose manifest is verified before and after; a required acceptance receipt is eligible only with `exclusive|isolated` and no relevant input mutation during the check; mutation during a check keeps the factual outcome but makes the receipt ineligible for the final tree (rerun); `stampAfter == now` is necessary, not sufficient; declared scratch/output policy prevents spurious invalidation. Spec refinement of [§8.4](docs/verification/scheduler.md#sec-8-4) (§3.1). | P1.7.4, P3.1.5 |
| D-46 | Evidence aliases and dedup (I-06, I-07) | **REVISED/NEW** `Alias` = campaign-global monotonically allocated `#n` → canonical journal/artifact id, transactional, never recycled on rebuild/cancel/resume, with context/workspace provenance; `look` dedup key = normalized request (`what`, target, range, `in`, `glob`, `near`, `since`, budget class, workspace, index version) ∧ sufficient *live* coverage in the current projection; an evicted or incomplete earlier result never satisfies a broader request. Spec refinement of [§5.4](docs/runtime/tools.md#sec-5-4) (§3.1). | P1.4.1, P1.6.3, P2.4.4 |
| D-47 | Workspace path contract (I-09) | **NEW** `WorkspacePath` used by every built-in filesystem operation (look, edit, registry, stamps, closures, ownership): canonical real-path resolution; traversal and absolute escapes rejected; mutation through symlink/junction/reparse ancestors rejected until an explicit operation supports it; case/alias identity unified on case-insensitive filesystems; protected targets matched by real path; resolution revalidated at publication; atomic rename provides no compare-and-replace against external writers — that limit is stated, and stronger guarantees require host-coordinated publication. | P1.2.6 |
| D-48 | Production vs evaluation configuration (I-18) | **NEW** `Config.flags` holds production-optional features only; counterfactual research arms (reserve off, test-integrity off, delta-only, clamped routing, …) live in `EvalArms` of the `eval` module; production `AttemptConfig` validation rejects any combination that disables a mandatory control (reserve, test-integrity guard, Δ+absolute, floors, lifecycle controls); the evaluator may run such arms but marks them ineligible for promotion; freeze/version rules apply to both. | P0.1.3, P1.9.4, P6.1.2 |
| D-49 | Redaction and coverage (I-10) | **NEW** every `Observation` carries a redaction mask (source line ↔ rendered line mapping); redacted lines are excluded from anchored-edit coverage and from seed/recall-derived coverage; raw source version identity stays separate from the rendered observation; protected preimages never fill the gap. | P1.5.3, P1.6.3, P1.10.3 |
| D-50 | Report provenance and test identity (I-13) | **NEW** runner reports bound to `action_id`, runner/definition version, selector, completion status and captured-artifact provenance; fresh per-invocation report destinations (a build-cache path is allowed only with recorded provenance); structured evidence parsed before prompt shaping; test identity = (check, project/module, file/suite, name, parameterization) with multiplicity; missing/ambiguous evidence ⇒ `inconclusive`, never green. | P1.6.6, P1.7.5 |
| D-51 | Provider invocation identity and cancellation (I-15) | **NEW** caller-assigned `InvocationId` registered before dispatch; `ProviderAdapter.start(request, id): Invocation` with `await()`, `cancel()` and terminal observation; states requested → cancel-requested → provider-acknowledged → terminal-reconciled; late native output and usage archived exactly once and never executed; conservative unknown-usage hold until reconciled; an operation-correlation field, not a fifth identity. | P0.3.2, P0.3.4, P0.3.5 |
| D-52 | Contract slice content (I-11) | **NEW** the mandatory `[K]` slice and every review packet carry the complete applicable acceptance definitions (run command/selector, check/review text, origin, obligation version, requirement links) and, for weakened checks, the original obligation beside the diff; ids-only rendering is allowed only in the ≤ 150-token anchor digest; assessment acceptance stays with the verifier/user. Spec refinement of [§6.1](docs/context/compiler.md#sec-6-1) (§3.1). | P1.1.4, P2.3.1, P4.4.3 |
| D-53 | Snapshot byte fidelity (I-12) | **NEW** the snapshot authority is a raw-byte/type/mode manifest plus exact recovery blobs; Git trees/refs merely index them; blobs are written with `git hash-object --no-filters -w` (or restored from recovery blobs) so no attribute filter or line-ending conversion runs; the temporary index is initialized deliberately from the manifest; every materialized candidate is verified against its manifest before checks; the user's staged index is untouched; ignored/untracked inputs are accounted for explicitly; unsupported forms (submodules, sparse checkout) are rejected by name. | P1.2.3, P1.2.4, P1.7.5 |

### 3.1 Specification refinements recorded by this plan
The plan follows the refined reading below; a later documentation-maintenance pass should apply them to the cited sections (they are not silent redesigns — each keeps the section's intent and closes an ambiguity found in review):
- [§8.4](docs/verification/scheduler.md#sec-8-4): `current = (stamp_after == stamp_now)` is necessary, not sufficient; add `tested_inputs`/`input_stability` (D-45, I-04).
- [§6.1](docs/context/compiler.md#sec-6-1) and [§5.1 `[K]`](docs/runtime/context-layout.md#sec-5-1): "acceptance ids and kinds" → complete acceptance definitions with origin and obligation version (D-52, I-11).
- [§5.4 look dedup](docs/runtime/tools.md#sec-5-4) and result ids: dedup needs semantic request equivalence + live coverage; `#n` aliases are campaign-global (D-46, I-06/I-07), consistent with [§6.2](docs/context/continuity.md#sec-6-2) cross-cell recall.
- [§5.1/§5.4 "masked, never removed"](docs/runtime/context-layout.md#sec-5-1) is governed by [§15.1](docs/platform/adapters.md#sec-15-1)'s adapter qualification (D-20, I-24).
- [§14.3](docs/platform/security.md#sec-14-3): "authorized, version-pinned rules snapshot" — pinning ≠ authorization; trust is bound by host policy (D-32, I-08).

## 4 Phases

Legend per phase header: **Fixtures** = FX/AX rows that must pass as harness tests before the phase closes · **Unsupported until later** = assumptions the phase does not validate.

---

## P0 Foundation — project, contracts, hooks, storage, OS
Goal: scaffolding that establishes real contracts and boundaries; nothing here talks to a model or a repo yet. Mirrors [§3.2 minimal forms](docs/architecture/components.md#sec-3-2) and the "resolve ownership and public record/tool contracts first" instruction of the map.
**Fixtures:** AX-01..AX-10 at contract level (fake adapter + `validate()`); their runtime consequences are re-verified in the phases the §5 AX table names · **Unsupported until later:** any provider parity; confined execution.

### P0.1 Build and conventions
#### P0.1.1 [C] Gradle multi-module skeleton · DONE
- Why: reproducible build with Java-consumable outputs.
- Deps: — (install JDK 26 first; D-01, D-02, I-25)
- Build: `settings.gradle.kts` (root `astrolabe`; `includeBuild("build-logic")`; `include(":provider-api", ":core")`), `gradle/libs.versions.toml` with explicit pinned versions, Gradle **9.7.0** wrapper with `distributionSha256Sum`, `build-logic` convention plugin `astrolabe.kotlin-library` (see §2.1), `core/build.gradle.kts` (`api(project(":provider-api"))`, test fixtures), `.editorconfig`, `.gitignore`.
- Notes: JDK 26 toolchain for compile, tests and the Gradle daemon; Kotlin `-Xjdk-release=26`, Java `--release 26`; `kotlin.explicitApi()`; `abiValidation()` opt-in (`ExperimentalAbiValidation`); `-Xjsr305=strict`; JUnit Platform; `maven-publish` with sources jar. `provider-api` must not depend on `core` (enforced by module graph). No placeholder modules for `index-treesitter`/`eval`.
- Done: `gradlew build` green with an empty `core` and `provider-api` on JDK 26; `updateKotlinAbi` produces dumps; Java test fixture module compiles against `core`; wrapper checksum verified.
- Log: 2026-09-20 — Gradle 9.7.1 wrapper pinned (sha256 from services.gradle.org; 9.7.1 is the current patch of the planned 9.7.0 line and was cached locally), JDK 26 via ~/.gradle/jdks (Temurin 26.0.2.1) + gradle-daemon-jvm.properties (toolchainVersion=26, foojay resolver), Kotlin 2.4.20, coroutines 1.11.0 (jdk8 module merged into core since 1.7 — not added), serialization 1.11.0, sqlite-jdbc 3.53.4.0, slf4j 2.0.19, JUnit Jupiter 6.1.3 (successor of JUnit 5 line; same org.junit.jupiter API). KGP 2.4 ABI DSL: calling abiValidation {} enables it; tasks are updateKotlinAbi/checkKotlinAbi (dumps in <module>/api/*.api, committed). Java test fixture (core/src/testFixtures/java) compiles against core; gradlew build green on Windows.

#### P0.1.2 [V] CI matrix · DONE
- Build: workflow running `gradlew check` on Linux and Windows (D-12); caches Gradle; publishes test reports.
- Done: both jobs green on the skeleton.
- Log: 2026-09-20 — .github/workflows/ci.yml: ubuntu-latest + windows-latest matrix, Temurin 26, gradle/actions/setup-gradle, gradlew check, test reports uploaded. Not yet executed on a remote CI runner (no remote configured in this session); local Windows run green.

#### P0.1.3 [C] `Config` and `Defaults` · DONE
- Why: every number of [§17](docs/reference/defaults.md#sec-17) is configurable per task and none is hard-coded; harness changes take effect only at attempt boundaries (invariant 12).
- Pkg: root `io.astrolabe`
- Build: `Defaults` with one named field per §17 row (shape policy, `turnsPerCell = 40` with the 80 % nudge, `alpha = 0.65`, `k = 8`, `m = 6`, `rMaxTokens = 16_000`, `anchorMaxTokens = 2_500`, `immediateStubTokens = 800`, `lookBudget = 1_500`, `runBudget = 1_200`, `registerCap = 1_200`, `digestCap = 150`, `patchCap = 400`, fact/note/summary caps, seeds/injection/focus caps, `touchedInAnchor = 10`, `checkerTimeBoxSeconds = 20`, `theta = 40`, `fullSuiteCadence = 5`, reserves, guard counters, probe/review/repair/attempt/depth/parallel limits, `campaignCells = 12`, flaky policy, admission policy, profiles, mode/execution mode/`dClass`/ceiling, `runTimeoutSeconds = 120`); `Config(defaults, mode, executionMode, rulesFile (trust binding per D-32), stateRoot (D-44), flags: one switch per production-optional `[O]` mechanism only)`; sections owned by later tasks are added there, not here (roles + texts P1.8.1/D-38, redaction P1.10.3, tierTable P4.5.1, injectionWeights P4.1.3/D-37) — I-01; counterfactual research arms live in `EvalArms` of the `eval` module, never in `Config` (D-48); `AttemptConfig` (harness version, config snapshot, profiles, role-text versions) with a validator that rejects any combination disabling a mandatory control; frozen at campaign open (P1.9.4).
- Done: a test enumerates the §17 table against `Defaults` fields; every `[O]` flag defaults off; the validator rejects a reserve-off or test-integrity-off production config (IX-18).
- Log: 2026-09-20 — Defaults (one field per §17 row, 25 rows; DefaultsTest maps the table to the fields both ways so no unowned number exists), ShapePolicy (D-16 classes, s3Enabled=false, slackFactor 1.5), ProfileRoles, Config(defaults, profiles, profileRoles, mode, executionMode, dClass, ceiling, rulesFile: RulesBinding (D-32), stateRoot, flags: Flags = 13 [O] switches, all off by test), ConfigViolation; AttemptConfig(harnessVersion, config, roleTextVersions, controls: Controls, production) with a @Transient fingerprint over stable JSON; freeze() throws InvalidConfig on any production violation (reserve 0, checker time box 0, attempts 0, unknown profile…); researchArm() is the EvalArms entry (production=false, never promotion-eligible) — IX-18. Declared owning-package enums early: auth.ExecutionMode, auth.Stage (extended by P1.10.x), route.Tier (extended by P4.5.1), root Mode/DClassPolicy. Astrolabe.VERSION = 0.1.0.

### P0.2 Identities, hashing, tokens, budgets
#### P0.2.1 [C] `id` package · DONE
- Why: [§3.3](docs/architecture/components.md#sec-3-3) four identities on every record; namespace rule (`workspace_id`, projection generation).
- Build: `WorkId`, `AttemptId`, `ContextId`, `WorkspaceId`, `Generation` (projection), `ExecutionGeneration`, `Identities(work, attempt, candidate: Stamp?, context: ContextId?)` — all validated non-empty/canonical at construction (D-08); `Digest` (SHA-256 hex; `hash8` short form); `FileVersion(digest)` = hash of **raw bytes**; `Stamp(baseCommit, trackedDeltaHash, untrackedManifestHash, envId) { val id }` where `id` hashes a **versioned canonical encoding** (sorted membership; explicit path/type/mode/content/environment fields) and the capture time `at` is stored as metadata **outside** identity (I-05); `IdGen` (injectable, deterministic in tests).
- Done: equality/serialization tests; equal candidates captured at different times have equal `Stamp.id`; a mode/membership/environment change changes it; Java-visible (`data class`, D-08).
- Log: 2026-09-20 — id package: WorkId/AttemptId/ContextId/WorkspaceId validated `[A-Za-z0-9._-]{1,128}`; Digest (SHA-256 hex, hash8), FileVersion (raw bytes), Stamp with @Transient id = digest of CanonicalEncoding('stamp', v1: base/tracked/untracked/env); CapturedStamp carries `at` outside identity; Identities carries CandidateId (= Stamp.id) instead of a full Stamp so rows never duplicate stamp components (the stamps table keeps them); Generation/ExecutionGeneration; IdGen with RandomIdGen + FixedIdGen (test fixtures). 11 tests green.

#### P0.2.2 [C][M] `budget` package · DONE
- Why: invariant 10 (every child/retry/rebuild consumes the originating budget); reservations enforced across concurrent calls ([§8.1 reserve](docs/verification/scheduler.md#sec-8-1)); FX-25.
- Deps: P0.3.2 (`TokenEstimator`), P0.3.3 (`Money`) — I-01
- Build: `Tokens`, `HeuristicEstimator : TokenEstimator` (bytes/3.6 with code-aware tweaks; jtokkit adapter optional; planning use only, D-06), `Budget(cells, turnsPerCell, tokens, cost: Money?, attempts, reserves)`, `Reservations` (atomic `reserve(amount)` → `Reservation`; `reconcile(actual)`; `release`; conservative hold for uncertain external usage), `Reserve` (verification 15 % + recovery/persist 5 % of cell; campaign recovery 10 %; raised to known check costs).
- Done: concurrent reservation test cannot overspend; unknown usage keeps a hold until reconciled.
- Log: 2026-09-20 — budget: Tokens (non-negative, saturating minus, fraction), Reserves, Budget.of(defaults), CellReserve + Reserve.cell (15 %/5 % of tokens and turns, verification raised to known check costs) / Reserve.campaign, HeuristicEstimator (bytes/3.6 + symbols/4, 10 % margin, never exact, planning only), Reservations (atomic reserve → Reservation.reconcile/release; overrun recorded; unknown usage keeps its hold). FX-25 concurrency test: 300 concurrent reserves of 10 on 1000 ⇒ exactly 100 granted.

### P0.3 Provider API module (contract level only)
#### P0.3.1 [C] Item model · DONE
- Why: [§15.1](docs/platform/adapters.md#sec-15-1) item-based internal message model mapping losslessly to Responses items and Messages blocks.
- Pkg: `provider-api` / `io.astrolabe.provider`
- Build: `sealed interface Item` — `Message(role, parts)`, `ToolCall(id, name, argsJson)`, `ToolResult(callId, content, isError)`, `ReasoningRef(providerTag, opaque)`, `UsageItem`, `OpaqueContinuation(providerTag, payload)`; each with `native: JsonElement?` passthrough (D-25). `ContentPart` (text; other kinds opaque).
- Done: round-trip serialization; `ToolCall`/`ToolResult` pairing helper `pairs(items)` detects broken pairs (feeds FX-21).
- Log: 2026-09-20 — Item sealed hierarchy (@SerialName discriminators, native passthrough), ContentPart Text|Opaque, Items.pairs → Pairing(unmatched/orphan/duplicate, broken). Round-trip + pairing tests.

#### P0.3.2 [C] Request/Response and tool schema types · DONE
- Build: `ToolSchema(name, description, jsonSchema, dialect)`, `ToolMask(ops)`, `Segment(kind: S|R|K|T|A, items, breakpoint: Boolean)` (D-29), `Request(segments, tools, profile, effort, maxOutputTokens, continuation?)`, `InvocationId` (caller-assigned, registered before dispatch, D-51), `Response(items, stop: StopReason, usage: BillableUsage?, continuation?)`, `StopReason { EndTurn, ToolUse, OutputLimit, Refusal, Cancelled, Truncated }`, `Effort`; `interface TokenEstimator { estimate(text|request): Estimate }` and `Estimate(tokens, exact: Boolean, estimatorId, version, marginTokens)` live here so `Request` needs nothing from `core` (I-01); `Request.estimate(estimator)` charges every serialized contribution once (tools, pinned text, retained protocol items, effective continuation history where known) and reports `exact=false` with a margin whenever any part is estimated (I-17).
- Done: an unknown continuation size yields `Estimate(exact=false, unknownHistory=true)`, never a silent fit; charging test per [§6.1](docs/context/compiler.md#sec-6-1).
- Log: 2026-09-20 — ToolSchema/SchemaDialect/ToolMask/Segment(S..A, breakpoint)/Request (layout order + unique tool names enforced)/InvocationId/Response (Truncated|Cancelled ⇒ no ToolCall by construction)/StopReason/Effort; Estimate + TokenEstimator + Request.estimate charging tools, items and continuation once; OpaqueContinuation.effectiveHistoryTokens == null ⇒ unknownHistory, never a silent fit.

#### P0.3.3 [C] Capabilities, profile, usage, money · DONE
- Why: [§15.1–15.2](docs/platform/adapters.md#sec-15-1) capability description separated from request construction; accounting without double counting; missing usage is `unknown`, never zero.
- Build: `Capabilities(toolSchemaValidation, parallelToolCalls, streaming, outputLimit, contextLimit, nativeCompaction, continuation, cancellation, hostedExecution, caching: CacheCapability(breakpoints, minimumTokens), usageFields)`, `Profile(provider, model, config, capabilities, limits, priceTable, latency, stratumOutcomes)`, `PriceTable(date, currency, price per billable dimension)`, `BillableUsage(quantities: Map<BillingDimension, Long>, unknown: Set<BillingDimension>, native: JsonElement?, schemaVersion, provenance(provider, model, protocol))` where `BillingDimension` is provider-defined (e.g. `uncached_input`, `cache_read`, `cache_write_5m`, `cache_write_1h`, `output`, `hosted_tool_x`) — aggregate counts (`totalInput`, `totalCacheWrite`) are derived diagnostics, never priced (I-16); `reasoningIncludedInOutput` flag; `Money(currency, amount: BigDecimal, unknown: Boolean)`; `UsageNormalizer` (per-provider mapping interface; rules of §15.2 in KDoc; implementations deferred to P7 except the fake).
- Done: `BillableUsage.price(table)` prices each dimension once; a mixed 5-minute/1-hour cache-write fixture prices both classes; an unpriced or missing dimension yields `Money.unknown = true`, never zero (IX-16).
- Log: 2026-09-20 — Capabilities (context/output limits inside, no separate Limits type), CacheCapability(writeClasses), Profile, PriceTable (dated, per-million BigDecimal as decimal strings), BillingDimension (provider-defined, constants for common ones), BillableUsage(quantities, unknown, native, provenance, schemaVersion) with derived totals and price(table) → Money.unknown when any dimension is unknown or unpriced (IX-16 mixed 5m/1h fixture), UsageNormalizer fun interface with §15.2 rules in KDoc.

#### P0.3.4 [C] `ProviderAdapter` contract and errors · DONE
- Build: `interface ProviderAdapter { capabilities(); validate(Request, Estimate): Validation (tool pairing, breakpoints supported, schema dialect supported, admission vs contextLimit + output headroom using exact-or-margin counts, no evicted-tail continuation); start(Request, InvocationId): Invocation; normalizer }`, `Invocation { suspend await(): Response; cancel(); state: Requested|CancelRequested|ProviderAcknowledged|TerminalReconciled; terminal: late output + usage observed exactly once }` (D-51), `sealed ProviderError { Transport, RateLimit(retryAfter), OutputLimit, Refusal, ExpiredContinuation, InvalidRequest, UnsupportedSchema, MissingUsage }`; Java SPI form `JavaProviderAdapter` (`CompletableFuture<Response>`, `cancel()`, documented threading/exception mapping) with a bridge to the Kotlin interface (D-07, I-14).
- Notes: three retry semantics (provider / tool / verification) are recorded separately but **retry policy itself is P7**. Never expose a half-generated tool call: a truncated stream yields `StopReason.Truncated` and no executable `ToolCall`. Cancellation never ends the coroutine that receives terminal/late usage; the reservation holds until reconciled.
- Done: KDoc states every rule of §15.1 the adapter must uphold; cancel-before-id, cancel racing a completed response and late usage after cancel settle spend once with no tool dispatch (IX-15); ABI dump committed.
- Log: 2026-09-20 — ProviderAdapter/Validation/Problem(Kind)/Validations.standard (pairing, breakpoints, dialect, continuation support, unknown history, admission with margin + output headroom)/Invocation(await, cancel, terminal; InvocationState)/Terminal/ProviderError sealed; JavaProviderAdapter + JavaInvocation (CompletableFuture) + ProviderAdapters.fromJava bridge that never cancels the host's futures on coroutine cancellation (calls cancel(); terminal() still completes) with exception mapping; error mapping and cancel-then-late-usage tested; ABI dump committed. Cancel-before-id and cancel-racing-completion arrive with the fake adapter (P0.3.5).

#### P0.3.5 [M] Fake adapter + scripted model (test fixtures) · DONE
- Why: the only model used by this plan's validation; must exercise AX-01..AX-10.
- Pkg: `core/testFixtures` (depends on `provider-api`)
- Build: `ScriptedModel` DSL (turn matchers on request content → response items), `FakeAdapter` (records requests, segments, breakpoints, invocation states; counts tokens with its **own independent** deterministic tokenizer, not the core heuristic (I-17); synthesizes `BillableUsage` by segment stability: unchanged prefix ⇒ cache read, changed ⇒ uncached + a cache write in a configurable class (5-minute/1-hour mixes for IX-16); injects faults: interrupted stream, broken pairing, output-limit stop, refusal, expired continuation, unsupported schema dialect, cancellation with late output/usage, missing usage), `FakeProfile`s (main/helper/escalation) with fake dated price tables and declared context limits; a Java-authored `JavaProviderAdapter` fake for P1.12.3.
- Done: AX-01..AX-10 expressed as tests against `FakeAdapter` + `validate()`; IX-15, IX-16, IX-17, IX-24 fixtures runnable.
- Log: 2026-09-20 — testFixtures: FakeProfiles (main/helper/escalation/strictOnly/tiny with dated USD price tables and declared limits), FakeTokenizer (whitespace pieces + symbols; independent of the core heuristic), ScriptedModel (ordered once-matchers + fallback, builder DSL, FaultKind ×8), FakeCachePolicy (write-class cycling), FakeAdapter (segment-stability cache simulation, drift-recording validate, AX-07 foreign reasoning refusal, D-51 states, holdResponses/release for races, late output on cancel, missing usage). FakeAdapterTest: AX-01..AX-10, IX-15 (cancel before ack / cancel racing completion / late usage once), IX-16, IX-17, IX-24 — 11 tests green. Java-authored JavaProviderAdapter fake arrives with P1.12.3.

### P0.4 Events, host hooks and views (UI integration seam)
#### P0.4.1 [C] `AgentEvent` model and `Events` bus · DONE
- Why: external applications observe execution, progress, tool calls, state changes, warnings and results without coupling to a UI; phase tags per [§15.5](docs/platform/adapters.md#sec-15-5).
- Build: `Phase { understand, locate, edit, verify, recover, retrieve, compact, delegate, plan, review, integrate }`, `SpanId`, `sealed interface AgentEvent(at, ids: Identities, phase, span, parent)` with families: `Campaign.{Opened, ShapeSelected, IncrementSelected, IncrementClosed, Finished}`, `Contract.{Amended, AmendmentProposed, AmendmentResolved}`, `Cell.{Started, TurnStarted, ModelRequested, ModelResponded(usage), ToolCalled, ToolResulted(header), GateFired, RegisterPatched, WorksetChanged, Rebuilt(reason), Ended(packetRef)}`, `Edit.{Applied, Rejected, Reverted, Transformed}`, `Run.{Started, Output(handle, cursor), Finished, Reconciled}`, `Check.{Scheduled, Started, Finished(receiptRef), Stale}`, `Ask.{Question, Answered}`, `Blocked`, `Warning(kind, text)`, `Budget.{Reserved, Reconciled, Exhausted}`, `Routing.Decided`, `Delegation.{Dispatched, Collected, Rejected}`, `Recovery.{Classified, Repaired, Escalated}`, `Kb.{Proposed, Admitted, Invalidated}`. Events carry ids/refs, not bodies, and a monotonically increasing `seq` per campaign so a consumer can detect gaps and resynchronize from `Views` (authoritative state stays in the journal). `Events` (`SharedFlow<AgentEvent>` with documented bounded buffering/replay semantics + `subscribe(EventSink): Subscription`), `fun interface EventSink` (Java-implementable; invoked off the mutation and database locks; a throwing or slow listener never aborts an edit between its effects and its receipt — D-26).
- Done: every event serializable and sequenced; a recorder sink in test fixtures; a failing listener leaves the emitting operation's receipt intact (test); Java can subscribe without coroutines.
- Log: 2026-09-20 — event: Phase (11), SpanId, sealed AgentEvent with 12 families / 47 typed events (@SerialName 'family.event'; refs only, never bodies), EventRecord(seq, at, event) assigned by the bus; Events(clock, replay, bufferCapacity): emit never blocks, per-subscriber Channel(DROP_OLDEST) buffers so one slow sink never affects another, replay tail on subscribe, sinkFailures counter, records() Flow for Kotlin, EventSink fun interface + Subscription(dropped) for Java; EventRecorder + FakeClock fixtures. Tests: round trip of every family, ordering, slow/throwing listener isolation with visible seq gaps, replay.

#### P0.4.2 [C] `Authority` (inbound host hooks) and `Mode` · DONE
- Why: "who answers `blocked`" is a policy ([§1.2](docs/architecture/principles.md#sec-1-2)); amendments, D-class approvals and human review need a host seam ([§4.1](docs/state/contracts.md#sec-4-1), [§4.6](docs/runtime/workspace-editing.md#sec-4-6), D-23).
- Build: `interface Authority { suspend ask(Question): Answer; suspend approve(DClassRequest): Decision; suspend resolve(AmendmentProposal): Resolution; suspend review(ReviewRequest): Verdict? }` (`ReviewRequest`/`Verdict` are the [§8.8](docs/verification/acceptance-review.md#sec-8-8) protocol types, declared now in `verify` as contracts; the review cell arrives in P4.4.3), `Mode { Interactive, Autonomous }`, `AutonomousPolicy` (never auto-accept weakening; D-class denied unless contract allowlist; `ask` ⇒ `blocked`), `AutonomousAuthority` default; every inbound reply carries the id of the pending question/review/amendment and the contract revision it answers, and a late reply is revalidated against the current revision before use; Java SPI form `JavaAuthority` (`CompletableFuture`-based) with a bridge (D-07, I-14).
- Done: contract tests: weakening proposals are never resolved as accepted by policy; answers are recorded as evidence vs amendment per §4.1; a reply for a superseded revision is rejected, not applied.
- Log: 2026-09-20 — Authority (suspend ask/approve/resolve/review returning null for 'no answer' ⇒ blocked), Question/Answer(changesRequirements)/DClassRequest(contractAllowlisted)/Decision/AmendmentProposal(weakening)/Resolution(Accepted|Rejected|Pending), AutonomousPolicy + AutonomousAuthority (never accepts a weakening; D-class only via contract allowlist; ask ⇒ null), Replies.check(reply, currentRevision) ⇒ Current|Superseded; verify.ReviewRequest/Verdict/Finding/ReviewCoverage (§8.8 protocol; InsufficientEvidence must name the criterion); java.JavaAuthority (CompletableFuture, null = no answer, exceptional completion = no answer) + event.Authorities.fromJava bridge. Mode/DClassPolicy declared in P0.1.3. Tests: policy rules, superseded-revision rejection, evidence-vs-amendment flag, verdict validation, Java bridge.

#### P0.4.3 [C] `Views` (read projections) and exports · DONE
- Why: [§4](docs/state/contracts.md#sec-4) `exports/` derived views; [risk 19](docs/reference/risks.md#sec-21) "exports exist for a UI to consume".
- Deps: P0.5.2
- Build: `Views(store)` → `ContractView`, `LedgerView`, `RegisterView`, `WorksetView`, `ChecksView`, `BudgetView`, `ReceiptView`, `FinishReceiptView`; `Export.write(views, exports/)` JSON + Markdown; all read-only.
- Done: views are pure reads of P0.5 tables; export files diffable and deterministic.
- Log: 2026-09-20 — event.Views(store): contract/ledger/register(ctx)/workset(ctx)/checks/budget/receipts/finishReceipt as pure SELECT projections over StoredRow bodies; Export.write(views, work, exportsDir, contexts) writes one JSON file per view (stable key order, prettyPrint) + summary.md; byte-identical on repeated runs (test). Tests: ViewsTest 10, ExportTest 5.

### P0.5 Storage
#### P0.5.1 [C][M] External project state root, `ProjectLock`, `Db`, `BlobStore` · DONE
- Why: [§4](docs/state/contracts.md#sec-4) canonical state outside the source tree; [§4.3](docs/state/evidence-coherence.md#sec-4-3) content-addressed blobs, publication before reference; D-44, D-15, I-21.
- Build: `Layout(root)` with `root = <Config.stateRoot or OS user-state dir>/astrolabe/projects/<repo-identity>/` (`repo-identity = digest(root commit id, canonical repository path)`; shared by all worktrees of the repository) containing `state.sqlite`, `blobs/<digest>`, `blobs/recovery/` (restricted), `native/` (protected provider replay material, D-25), `kb/`, `exports/`, `indexes/` (disposable), `candidates/`, `campaigns/`; `ProjectLock` (OS file lock; one controller process per project; a second controller fails to acquire ownership); `Db` (sqlite-jdbc, `foreign_keys=ON` per connection, WAL, `synchronous=FULL`, busy timeout, `tx {}`, typed row mappers, `Migrations`, every persisted record carries `schemaVersion`); `BlobStore` (`put(bytes): Digest` = write temp → fsync file → rename → fsync directory, `get`, `exists`, `gc(referenced)` with bounded orphan recovery; a missing referenced blob is an integrity failure); durability statement per platform (process termination always covered; OS/power loss only where fsync semantics hold; unsupported guarantees fail closed at open).
- Notes: D-03; no `.git/info/exclude` change; nothing authoritative under a cache path or inside the repository. Blobs are never truncated; only views are.
- Done: crash-safety test at every ordering point (no accepted receipt references a missing artifact); two sibling repositories do not collide; linked worktrees share the store without sharing coverage; second-controller acquisition fails; migrations idempotent (IX-21).
- Log: 2026-09-20 — io.astrolabe.store (delegated worktree agent, merged): RepoIdentity.of(git) = digest(canonical encoding of sorted root commits + real path of the common git dir) so linked worktrees share one store and siblings differ; Layout(root) with database/blobs/blobs/tmp/blobs/recovery/native/kb/exports/indexes/candidates/campaigns, Layout.resolve(stateRoot ?: OS user-state dir); ProjectLock.acquire (FileChannel lock on one sentinel byte past the holder record so the holder stays readable; second acquire ⇒ ProjectLockHeld); Db.open (single connection, foreign_keys=ON, WAL, synchronous=FULL, busy_timeout read back and fail closed via StoreUnsupported; tx {} BEGIN IMMEDIATE; typed Row getters); BlobStore.put = temp → force(true) → ATOMIC_MOVE → best-effort dir fsync (refused on Windows, documented) → blobs row in its own tx (publication before reference), get/exists/gc(referenced, grace, bounded 4096, integrity check first), FaultPoints/CrashPoint seam tested at every ordering point; Store.open(config|stateRoot, git, clock, faults). Durability statement on Layout: process termination always; OS/power loss only where fsync semantics hold. Tests: RepoIdentity 6, Layout 5, ProjectLock 4, Db 7, Migrations 7, BlobStore 9, Store 6 (IX-21: siblings distinct, worktrees shared, second controller refused, no receipt references a missing blob at any crash point).

#### P0.5.2 [C] Schema v1 · DONE
- Build: tables (one writer each, §2.3): `journal`, `blobs`, `stamps`, `receipts`, `observations`, `claims`, `intents`, `contracts` + `requests` + `requirements` + `acceptance` + `constraints` + `amendments`, `increments` + `ledger` + `sizing`, `cells` + `turns` + `manifests`, `register_versions`, `workset_exports`, `notes` + `note_queue` + `note_usage` (FTS5 virtual table over summary/anchors), `routing_log`, `usage` (per call, native + normalized), `leases`, `handles` (bg processes), `packets`. Every row carries `Identities` columns.
- Done: schema documented in `Migrations` KDoc; `Views` (P0.4.3) compile against it.
- Log: 2026-09-20 — Migrations v1 (schema_version table, idempotent): 27 tables + notes_fts (FTS5, MATCH verified) with uniform identity columns (work_id, attempt_id, candidate_id?, context_id?) + schema_version + created_at + body JSON, typed key columns only for PK/unique/FK (receipts.raw_blob and observations.content_blob reference blobs(digest) so a missing artifact cannot be referenced); aliases table (work_id, alias_no UNIQUE) for D-46; writer ownership per table (L9) tabulated in the Migrations KDoc; StoredRow(table, key, identities, schemaVersion, createdAt, body) is the P0 row shape, typed decoding is added by the owning P1 tasks.

### P0.6 OS adapter, git, search, test kit
#### P0.6.1 [C][M] `Os` and `Proc` · DONE
- Why: [§13.1](docs/operations/recovery.md#sec-13-1) process identity beyond PID, log cursor, cancellation; [§18.1](docs/implementation/roadmap.md#sec-18-1) OS adapter validated per platform.
- Build: `interface Os { spawn(argv|cmd, cwd, env: allowlisted, deadlineSeconds, captureTo: store-owned log file): Proc; terminate(Proc); replaceFileAtomically; realPath }`, `ProcessOwner` via JDK FFM bindings (D-43): Windows job object per launch (kill-on-close, breakaway denied) and POSIX process group (`setsid`/`setpgid`, `killpg`); `Proc(handle, pid, startedAt, identityKey, ownerToken, logPath, logCursor, status)` persisted independently of the requesting coroutine; terminal record written by a supervisor thread; outcomes distinguished: observation timeout (poll returned, process alive), execution deadline (tree terminated), cancellation requested, confirmed termination (exit code), lost/unknown (handle unrecoverable, e.g. after harness death; never relaunched blindly). Timeouts kill the tree, never replay.
- Done: real tests on Windows and Linux: immediate child spawning, parent exit, harness crash → `lost`, cancel/deadline races, log-cursor continuation, PID reuse; a missing/unresponsive poll never justifies duplicate execution (IX-20; D-12).
- Log: 2026-09-20 — io.astrolabe.os (delegated worktree agent, merged): Os interface (spawn/poll/terminate/advanceCursor/reattach/resolve/replaceFileAtomically/realPath), LocalOs(clock, ownerToken), SpawnSpec/Command(Argv|Shell)/EnvPolicy (essentials ∪ allowlist ∪ extra; System.getenv(name) used because the env Map is case-sensitive on Windows), Proc (@Serializable sidecar <log>.proc.json written by atomic replace, terminal record by a daemon supervisor thread), IdentityKey(pid, startEpochMillis), ProcStatus Running|Exited|DeadlineExceeded|Cancelled|Lost, Poll(newBytes, nextCursor, status, timedOut). Native (D-43): Windows CreateProcessW CREATE_SUSPENDED + job object KILL_ON_CLOSE (no breakaway) + AssignProcessToJobObject + ResumeThread — owned before the first instruction, no race; POSIX posix_spawnp POSIX_SPAWN_SETSID (fallback SETPGROUP) + file actions, kill(-pgid), waitpid(WNOHANG), group leadership verified with getpgid. Descendants never outlive the root; after a harness crash a foreign-token sidecar resolves Lost (POSIX: running-but-unowned then Lost); no relaunch API. Tests on Windows: LocalOsProcessTest 10, ProcOwnershipTest 6 (1 Linux-only skipped), ProcResolutionTest 7, LocalOsFilesTest 5 — grandchild kill by terminate/deadline, cursor continuation, observation timeout leaves the process running (FX-22 part), deadline-vs-cancel race, PID reuse ⇒ Lost. --enable-native-access=ALL-UNNAMED added to the test JVM (not required on 26.0.2.1; documented for consumers). POSIX path unexecuted locally (CI only).

#### P0.6.2 [C][M] `Git` CLI wrapper · DONE
- Build: `Git(repo)`: `status` (porcelain v2), `revParse`, `hashObject(bytes, noFilters = true, write)` (raw object storage, D-53), `lsFiles`, `diff`, `show`, `catFile`, `updateIndex(tempIndex, entries)` + `writeTree(tempIndex)` (temporary index initialized deliberately, never the user's), `commitTree`, `updateRef(ref, new, expectedOld)`, `readRef`, `worktreeAdd/Remove` (P5), `version()` (Git ≥ 2.20 is a *tested* minimum, D-04). Never `reset`, `clean`, `stash`, never touches the user index or `.git/info/exclude`.
- Done: tests on a temp repo incl. dirty/staged/untracked, CRLF `.gitattributes` and a configured clean filter (raw hashing bypasses both); `updateRef` with a wrong expected id fails.
- Log: 2026-09-20 — Git(repo) CLI wrapper (Git.kt, GitTypes.kt) built by a delegated worktree agent and merged: status porcelain v2 -z typed (StatusEntry sealed, enums for modes/codes), revParse, readRef (show-ref --verify), lsFiles, lsTree, diff, show, catFile (byte-exact), hashObject --no-filters (D-53 proof: with `* text=auto eol=lf` + a live clean filter, staging alters bytes while hashObject/catFile keep sha1('blob <len>\0<raw>') and raw bytes), updateIndex(tempIndex) replaces the temp index deliberately and refuses the user's index, writeTree, commitTree, updateRef with expected-old (RefUpdateRejected on mismatch, ref unmoved), worktreeAdd/Remove, version()/requireMinimum, unsupportedForms() (submodules/sparse rejected by name). Env cleared + allowlisted (GIT_TERMINAL_PROMPT=0, LC_ALL=C, GIT_OPTIONAL_LOCKS=0). User index byte-identical across the temp-index round trip (test). TempRepo fixture (P0.6.4 part) with commit/stage/modify/untracked/gitattributes/cleanFilter/crlfVariant/symlinkAncestor/caseAlias (FixtureSupport.Unsupported when the OS denies). Tests: GitTest 17, GitTypesTest 9, TempRepoTest 9 — green on Windows; Linux via CI; junction helper deferred to P1.2.6.

#### P0.6.3 [C][M] `Search` · DONE
- Build: `Search.find(pattern, mode: literal|regex, scope: paths|glob, budgetBytes, since?) → Hits(scope, complete, truncated, backend)`; ripgrep backend + JVM fallback over a **documented common subset** (D-05): supported regex syntax, ignore rules, hidden/binary handling, Unicode/case rules, symlink policy; a pattern outside the subset fails explicitly (`unsupported`), never silently differs; four outcomes distinguished: zero matches / incomplete / failed / denied ([§5.4 envelope](docs/runtime/tools.md#sec-5-4)).
- Done: same results and flags from both backends on fixture repos for every subset feature; an unsupported pattern is refused by both.
- Log: 2026-09-20 — io.astrolabe.os.search (delegated worktree agent, merged): Search/SearchRequest/SearchScope(All|Paths|Glob)/Hit/Hits/SearchOutcome(Found|Incomplete|Failed|Denied|Unsupported), PatternSubset.check shared by both backends (documented accepted/refused regex forms; leading (?i) only), shared candidate-file resolver (git ls-files --cached --others --exclude-standard or a .git-skipping walk; hidden, symlink and NUL-binary files excluded; since by mtime), RipgrepSearch pinned to `rg --json --color never --no-config --line-number --column --crlf -j1 …` with stderr classified into Denied vs Failed, JvmSearch over the same subset; Searches.auto/available. Verified rg 15.2 facts: explicit file args bypass rg's hidden/binary rules (so filtering lives in the resolver), --max-count is per file, default output order is not argument order. Tests: PatternSubsetTest 218, SearchBackendParityTest 23 (2 POSIX-only skipped on Windows), SearchesTest 5. Deadlines belong to P0.6.1; large files read whole.

#### P0.6.4 [M] Test kit · DONE
- Pkg: `core/testFixtures`
- Build: `TempRepo` builder (init, commits, dirty/staged/untracked files, `.gitattributes` CRLF/filter variants, symlink/junction ancestors, case-alias paths), fixture repos (small Python, TS, Gradle/Kotlin projects with runnable tests; same-name tests in two modules; a parameterized test), `EventRecorder`, `StoreInspector`, `FaultInjector` (crash points: during command, after mutation before receipt, during rebuild, after external effect with lost ack — [§13.4](docs/operations/recovery.md#sec-13-4); mid-check background writer; mutate-and-restore writer), `FakeClock`, `FixedIdGen`.
- Done: used by P1 tests; fixture repos' tests run on both CI platforms on JDK 26.
- Log: 2026-09-20 — Test kit complete: TempRepo (P0.6.2 merge), FixedIdGen, FakeClock (shared timeline across zones), EventRecorder, StoreInspector (P0.5 merge), FaultInjector = BlobStore FaultPoints/CrashPoint (store) + Consequential boundaries (evidence) — a cross-cutting FaultInjector for runtime crash points arrives with the cell/controller tasks; fixture repositories (delegated worktree agent, merged): python-small (pyproject + unittest/pytest-compatible tests, same-name test_smoke in two modules, subTest + generated parameterized tests), python-failing (one failing + one skipped test for the baseline ledger), ts-small (node --test with .test.ts, Node 24 strips types unflagged; test:junit reporter carries file for same-name disambiguation), gradle-small (JUnit BOM 6.1.3, two classes with smoke(), @ParameterizedTest; run via the cached Gradle 9.7.1 dist, --offline); FixtureRepos.materialize/testCommand/runTests, Runners.python/node/gradle/run, index files checked against the resource tree (.gitignore ships as _gitignore because Gradle drops dotfiles from resources). pytest is absent locally, so the unittest path is the exercised one. All fixture suites run green on Windows (Gradle fixture ≈ 18 s).

---

## P1 Stage A — Dependable cell (S0)
Goal: [§18.2 Stage A](docs/implementation/roadmap.md#sec-18-2): one implementing cell with contract, evidence, coherence, tools, synchronous checker, register, gates, exit gate, P0 lifecycle controls, accounting. Shapes S1+ are not available yet (an S0-ineligible request ends as an honest `blocked`).
**Fixtures:** FX-01..10, 13 (partial: `unavailable` receipt), 15, 16 (stale marking; reuse proofs P3), 18, 21, 23 (open-time reconciliation), 24, 25, 26 (single-cell form), 38, 39 (executor ceiling), 43, 48, 49 (S0), 50, 58, 59; IX-02, 04, 05, 06, 07, 08, 09, 10, 12, 13, 14, 15, 16, 17, 20 · **Unsupported until later:** resume across sessions (P2), rebuild (P2), blast radius/closures beyond `known(paths)` (P3), precise test-integrity classification (P3.4.2; P1 has the conservative policy of P1.7.8), review cells (P4; the human path exists from P1.7.8), KB content (P2/P4), confined execution (P7 — a host that requires it is refused, D-11), live providers (P7). Live gate (B1 vs B0/B-HELM): `UNMEASURED`.

### P1.1 Task Contract (S0 form)
#### P1.1.1 [C][M] Contract records and store · DONE
- Why: [§4.1](docs/state/contracts.md#sec-4-1) harness-owned, user-authoritative contract; invariant 1–2.
- Pkg: `contract`
- Build: `Contract(workId, version, attemptId, mode, shape, requests: append-only List<Request>, requirements, acceptance, constraints, exclusions, contractsTouched, scope: Scope(writePaths, protectedPaths), budget: Budget, authorization: Authorization(ladderCeiling, dClass, capabilitySet), risk, amendmentsPending)`, `Requirement(id, text, acceptance ids, dependsOn, authorityRef, status: harness-derived)`, `sealed Acceptance { Run(cmd, origin, last), Check(text, origin, evidenceRef), Review(text, origin, signedBy) }` with `Origin { user, harness, model(strengthens), amended(v) }`, `Constraint(id, text, authority)`, `Contracts` store (versioned rows; version bumps only on authorized amendment; failed attempts preserved); the **minimal** `Increment(id, requirementIds, accept, writeScope, expectedFiles, status)` and `Ledger(requirementId → status, evidence, stampValid)` records used by S0 (`G_single`) are declared here so P1 consumers never depend on P2 (I-01); P2.1.1 extends them with the graph fields.
- Done: model-side APIs cannot mutate acceptance (only `strengthens` add and `propose`); store round trip; FX-15 test.
- Log: 2026-09-20 — contract package: Contract (validated refs, unique ids), UserRequest, Requirement(+RequirementStatus), Origin sealed (user/harness/model(strengthens)/amended@v), Command(argv, cwd), Acceptance sealed Run/Check/Review with obligationVersion (D-52), Constraint, Scope, Authorization(+dClassAllowlist), Risk, Amendment, minimal Increment/IncrementStatus/Ledger/LedgerEntry for S0; Contract.strengthen is the only model-side mutation (add-only, Model origin required). ContractRepository seam (history/append/replaceLatest) with InMemoryContractRepository + Contracts store API. Remaining for DONE: SQLite ContractRepository over the store (after P0.5 merges).
- Log: 2026-09-20 — P0 complete; SQLite ContractRepository next.
- Log: 2026-09-20 — SqliteContractRepository(store, clock): contracts table keeps every version's full record; requests append-only by id; requirements/acceptance/constraints rewritten as the current-version projection; amendments upserted by id — so Views.contract reflects the store. Reopen round trip + FX-15 tests green.

#### P1.1.2 [M] S0 auto-derivation · TODO
- Why: [§4.1 auto-derivation](docs/state/contracts.md#sec-4-1); [§3.5 S0](docs/architecture/roles-shapes.md#sec-3-5).
- Deps: P1.3.1, P1.3.4 (atlas, sniffed commands)
- Build: `Contracts.deriveS0(request, atlas)` → `AC-1: run <sniffed suite> (origin harness, scope touched)`, default write scope (D-31), protected paths; requirement `R1` = request text.
- Done: fixture repos yield the right runner command; no acceptance ⇒ still derived; model must state goal-level acceptance in its first patch or ask (entry gate, P1.8.5).

#### P1.1.3 [M] Amendments channel · DONE
- Why: F1/F17 closed at the data model; [§4.1 origins and amendments](docs/state/contracts.md#sec-4-1).
- Deps: P0.4.2
- Build: `Amendment(by: model|user, cell, change, reason, status: pending|accepted|rejected)`, `Contracts.propose` (model → pending), `Contracts.amendByUser(text)` (append request, bump version, authority = message), `Contracts.resolve(via Authority)`; policy never auto-accepts a weakening; parent answers to children are evidence, not amendments; factual answers do not bump the version.
- Done: FX-15; version bump only on authorized amendment; events `Contract.*` emitted.
- Log: 2026-09-20 — Contracts.propose (pending, grants nothing), amendByUser (append request verbatim + version bump; authority = the message), resolve via Authority (weakening ⇒ Rejected by AutonomousAuthority; Accepted ⇒ apply + version bump with amended@vN provenance; Pending ⇒ unchanged), resolved() history; Contract.* events emitted. FX-15 covered: model cannot edit/remove acceptance; a rejected weakening leaves version and items unchanged.

#### P1.1.4 [M] Contract digest and contract slice · TODO
- Why: [§5.1](docs/runtime/context-layout.md#sec-5-1) digest ≤ 150 tokens in `[A]`, verbatim slice in `[K]`.
- Pkg: `register.ContractDigest`, `context` (slice)
- Build: `ContractDigest.render(contract, ledger) ≤ 150 tokens` (D-17: current authorized objective excerpt, obligation ids/status/currency, critical exclusions, deterministic truncation label), `ContractSlice.forIncrement(contract, increment)` = requirements verbatim, ALL constraints/exclusions, and the **complete** applicable acceptance definitions (run command/selector, check/review text, origin, obligation version, requirement links — D-52, I-11); ids-only rendering exists only inside the digest.
- Done: token cap enforced with the estimator; byte-stable for equal inputs; a slice that carries an acceptance id without its definition fails coverage (IX-11).

### P1.2 Workspace core
#### P1.2.1 [C][M] `Workspace` and `VersionRegistry` · TODO
- Why: [§3.3](docs/architecture/components.md#sec-3-3) `version(workspace, path)`, `displayed(context, generation, workspace, path, v)`; F2 (namespace rule).
- Pkg: `workspace`
- Deps: P1.2.6 (`WorkspacePath`)
- Build: `Workspace(id, root, git)`, `VersionRegistry { version(path): FileVersion (raw-byte hash; an mtime/size cache is only a hint and is bypassed at every consequential boundary — read, edit CAS, check, reuse, publication — with acquisition-race detection, I-05); displayed(ctx, gen, ws, path, v): Ranges; show(ctx, gen, ws, path, v, range, redactionMask); change(path, from, to) → listeners }`, `Ranges` (sorted merged intervals), `ChangeListener` (Coherence hooks in P1.4.4).
- Notes: shared content hashes never share edit authority across contexts/workspaces (FX-51 in P2/P5).
- Done: displayed ranges union/merge tests; change notifications fire once per version transition; a same-size external rewrite with a restored mtime is detected and rejects a stale CAS (IX-05).

#### P1.2.2 [M] `Stamper` · TODO
- Why: [§8.4](docs/verification/scheduler.md#sec-8-4) stamp = base commit + tracked delta hash + untracked manifest hash + env id; computed before/after `run(verify)` and at every cell boundary.
- Deps: P0.6.2, P0.2.1
- Build: `Stamper.stamp(workspace): Stamp` over the versioned canonical candidate encoding (sorted membership, path/type/mode/content; harness state lives outside the tree, D-44), `Stamper.diff(a, b): changedPaths`, `EnvFingerprint.compute()` (D-13: OS/arch, resolved tool/checker/parser identities and versions, protected digests of relevant effective environment values, build flags, lock/dependency state, runner policy id, external fixture identities; unknown relevant inputs ⇒ `envKnown=false`, which blocks cross-candidate reuse), stamps table with `at` as metadata.
- Done: editing a file changes the stamp; equal trees ⇒ equal stamps across runs and capture times; a changed lockfile or tool version changes `envId` (IX-05).

#### P1.2.3 [M] Dirty-state record · TODO
- Why: [§4.6](docs/runtime/workspace-editing.md#sec-4-6) initial dirty-state record; FX-06.
- Build: `DirtyState.capture(workspace)` at campaign open → a `Snapshot` = raw-byte/type/mode **manifest** plus exact recovery blobs for tracked delta, staged content (read from the user's index without touching it) and relevant untracked files (D-53), + `s0` stamp; ignored/untracked inputs accounted for explicitly; unsupported forms (submodules, sparse checkout) rejected by name; `DirtyState.separate(final)` → `agent` / `by_run` / `pre_existing_user_changes` for the finish receipt.
- Done: FX-06 test: user changes survive edits, failed checks, reverts; CRLF conversion and a configured clean filter do not alter the captured bytes (IX-12).

#### P1.2.4 [M] `ShadowRef` snapshots and `revert:turn:N` · TODO
- Why: [§4.6](docs/runtime/workspace-editing.md#sec-4-6), [§9.3](docs/runtime/workspace-editing.md#sec-9-3): snapshot after every mutating turn; constant-time selection; no `reset`/`clean`; never crosses the dirty-state record.
- Deps: P0.6.2
- Build: `ShadowRef(work, attempt, workspace)` → `refs/astrolabe/<work>/<attempt>/<ws>/head`; `snapshot(turn)` = build a `Snapshot` manifest (raw bytes/type/mode) → store blobs with `git hash-object --no-filters -w` (or from recovery blobs) → deliberately initialized temporary index from the manifest → `write-tree` → `commit-tree` (parent = previous) → `update-ref` with expected old id; the manifest is the authority, the Git tree only indexes it (D-53); `restore(turn)` writes bytes from the manifest's blobs, guarded per file against divergent current content; `materialize(turn, dir)` exports a candidate and verifies it against the manifest; snapshot 0 = the captured dirty state.
- Done: restore never touches user refs/index/stash; expected-old-id mismatch fails loudly; FX-05 (inverse refuses divergent content); a `.gitattributes` filter never changes snapshot bytes or runs at snapshot time (IX-12).

#### P1.2.5 [M] `Preimages` and `revert:#id` · TODO
- Build: `Preimages.save(path, version, bytes)` into `blobs/recovery/` (unredacted, restricted, D-14); `revert(editId)` = version-checked inverse producing a diff receipt + inline syntax; refuses when current bytes ≠ postimage.
- Done: FX-05; preimage saved before any write (ordering test).

#### P1.2.6 [C][M] `WorkspacePath` contract · TODO
- Why: [§14.1](docs/platform/security.md#sec-14-1) filesystem-root enforcement; lexical scope is insufficient (I-09, D-47).
- Deps: P0.6.1 (`realPath`)
- Pkg: `workspace`
- Build: `WorkspacePath.resolve(workspace, userPath) → Resolved(relative, real, kind) | Rejected(reason)`: canonical real-path resolution; `..` traversal and absolute escapes rejected; mutation through symlink/junction/reparse ancestors rejected until an explicit operation supports them (reads report the link kind); case/alias identity unified on case-insensitive filesystems; protected paths matched by real path; `revalidate(resolved)` at publication; documented limit: atomic rename is not compare-and-replace against external writers — stronger guarantees need host-coordinated publication. Used by look/edit/registry/stamps/closures/ownership (single entry point).
- Done: traversal, absolute escape, outside/protected junction targets, Windows case aliases and ancestor substitution leave protected/outside bytes unchanged; unsupported concurrent-publication guarantees are reported, not claimed (IX-09).

### P1.3 Orientation tier 0
#### P1.3.1 [M] `Atlas` · TODO
- Why: [§7.1](docs/repository/navigation.md#sec-7-1) harness-built structure without bodies; "the atlas never lies about existence".
- Pkg: `atlas`
- Build: `AtlasRow(path, bytes, lang, hash8, exports, imports, testsFor)`, `Atlas.build(workspace)` lazy, cached in `indexes/` by repo hash, `refresh(touchedPaths)` O(touched); collapse rules for vendor/build/generated (listed, not expanded); `Focus.render(atlas, focus: root|dir|file)` (root → dirs with sizes; dir → children with export counts + one-line names; file → outline).
- Done: rebuild-from-cache equals fresh build; refresh after an edit updates only touched rows.

#### P1.3.2 [M] `Prime` (`[R]` content) · TODO
- Why: [§5.1 `[R]`](docs/runtime/context-layout.md#sec-5-1), [§7.1](docs/repository/navigation.md#sec-7-1); byte-stable per repo version and role.
- Build: `Prime.render(atlas, sniff, rules: RulesTrust.approved?, kbIndex, focusSubsystem)`: tree depth 3 with counts, languages, sniffed commands, the **approved** rules-file snapshot only (D-32: discovery proposes candidates; `RulesTrust` (P1.10.1) binds path + digest + provenance through host configuration or explicit user authority; an unapproved candidate is listed as repository data, never rendered as instructions), top-10 hubs by inbound refs (tier 0: import count), `index/contracts.md` + `index/global.md` lines (empty until P2.6), behaviour-map excerpt ≤ 300 (empty until P4.3).
- Done: same inputs ⇒ identical bytes; no timestamps; an unapproved `AGENTS.md` never appears in `[R]` as instructions (IX-08).

#### P1.3.3 [M] `Outline` and `SymbolIndex` tier 0 · TODO
- Why: [§7.2](docs/repository/navigation.md#sec-7-2) regex/ctags-grade tier with `complete:false` on refs.
- Build: `Language` detection, `Outline.of(path)` (declarations with spans for D-09 languages; generic fallback = top-level lines), `SymbolIndex.def(name)`, `refs(name) → complete=false, tier=0`.
- Done: outlines for fixture repos; `refs` always flagged incomplete at tier 0.

#### P1.3.4 [M] `Sniff` commands · TODO
- Build: `Sniff.commands(atlas)` → test/build/lint/type-check commands per manifest (Makefile, pyproject, package.json, Cargo.toml, go.mod, build.gradle(.kts), pom.xml), per package in monorepos ([§7.7](docs/repository/navigation.md#sec-7-7)).
- Done: fixture repos map to the right runner; unknown ⇒ `none` (never guessed).

### P1.4 Evidence store
#### P1.4.1 [M] `Journal` · DONE
- Why: [§4.3](docs/state/evidence-coherence.md#sec-4-3) append-only journal searchable via `look(find, in="store")`.
- Pkg: `evidence`
- Build: `JournalEvent(eventId, ids, turn, kind: call|result|editIntent|editOutcome|check|nudge|boundary|intent|reconcile, argsDigest, refs)`, `Journal.append/search(query, scope)` (FTS over text views), ordering guarantees.
- Done: append-only enforced (no update/delete API); search returns `complete` per scope.
- Log: 2026-09-20 — Journal(store, clock): JournalEvent(eventId, ids, turn, kind ×9, argsDigest, refs, text, payload, at, seq) with seq assigned per work inside the append tx; append/get/events(scope)/search(query, scope, limit) → JournalHits(complete per scope)/lastSeq; no update or delete API. Search is an exhaustive case-insensitive scan over the text views (FTS reserved for notes in schema v1). SqliteIntentJournal and SqliteAliases back the P1.4.3 seams (open intents survive reopen; aliases monotone across reopen, never recycled).

#### P1.4.2 [C][M] `Receipt`, `Observation`, `Claim` · DONE
- Why: [§4.3](docs/state/evidence-coherence.md#sec-4-3), [§8.4](docs/verification/scheduler.md#sec-8-4) status vocabulary; invariant 4–5.
- Build: `Receipt(receiptId, checkId, acceptanceIds, cmd, cwd, argvOrShell, stampBefore, stampAfter, envId, verifierVersion, checkDefinitionVersion, contractVersion, outcome: Outcome{passed, failed, timeout, infraError, inconclusive, notRun, unavailable, denied, unknownOutcome}, parsed: Counts, inputClosure: Closure{Known(paths), Package(p), Unknown}, raw: blobRef, limits, reuseOf?)` immutable; `Observation(id, actionId, candidate, contentRef, scope, completeness, sourceVersions, capture)`, `Claim(id, text, kind h|v|x, evidenceState, authority, freshness, evidenceRefs)`.
- Done: applicability is **not** a receipt field (computed, P1.4.4/P3.1.2); parse error or absent result never maps to `passed`.
- Log: 2026-09-20 — evidence records: Outcome (9 states, only Passed is green), Counts, Closure (Known/Package/Unknown), InputStability + TestedInputs (D-45 eligibility), Limit, Receipt (immutable; a Passed receipt requires parsed counts with something executed; greenForFinalTree needs eligible inputs), RedactionMask + Observation.coverage(path) = ranges − hidden (D-49), Claim (v needs evidence), Anchor, workspace.Ranges/LineRange (sorted merged intervals with minus/intersect/covers). Applicability is computed elsewhere (P1.4.4/P3.1.2), never stored.

#### P1.4.3 [M] `Intent` journal and consequential-action ordering · DONE
- Why: invariant 6; [§4.3 ordering](docs/state/evidence-coherence.md#sec-4-3); FX-23, FX-24.
- Build: `Intent(intentId, actionId, argv, cwd, expectedEffect, idempotencyKey?, status: recorded|dispatched|running|observed|committed|unknown)`, `Consequential.run(reserve → intent → dispatch → observe → persist → commit)` helper enforcing the order; `Intents.openAtStart()` → `unknown_outcome` list for reconciliation.
- Done: fault injection at each boundary leaves a classifiable state; no action after an unreconciled unknown.
- Log: 2026-09-20 — Intent + IntentStatus (recorded→dispatched→running→observed→committed | unknown), IntentJournal seam (record/update/open/get; monotone transitions) with InMemoryIntentJournal (SQLite version follows the store merge), Consequential.run(reserve → intent → dispatch → observe → persist → commit) returning Completed | Unknown(intentId) | NotDispatched; journal.open() = unknown_outcome list at start. Fault injection at every boundary tested (FX-23/24 partial); Alias (D-46 campaign-global #n) + Aliases seam with in-memory impl.

#### P1.4.4 [M] `Coherence` (turn, cell, verification horizons) · TODO
- Why: [§4.4](docs/state/evidence-coherence.md#sec-4-4) one registry, one rule: mark, never serve as current, never delete; F19.
- Deps: P1.2.1, P1.4.2
- Build: `Coherence.onChange(path, v → v')`: mark live reads @v stale (Workset hook), STATE facts @v stale (register hook), receipts with closure ∋ path or `Unknown` stale, schedule checker on path, refresh atlas row, drop Workset entry + announcement; `serve(item)` current iff anchors match else `stale|historical`; project (notes) and integration (delegated results) hooks registered in P2.6.3 / P5.1.3.
- Done: FX-07 (partial: reads/facts/receipts by known closure), FX-16 (stale after change; reuse proof arrives in P3.1.2).

### P1.5 Register (STATE) and Workset
#### P1.5.1 [C][M] `Register` model and Markdown render · DONE
- Why: [§5.2](docs/runtime/register-workset.md#sec-5-2); model-owned, harness-validated; rendered by the harness at the tail.
- Pkg: `register`
- Build: `Register(version, cell, increment, constraints, plan: List<Step(n, mark [ ]|[>]|[x]|[~], text, accept?, after?, req?, evidence?)>, facts: List<Fact(kind h|v|x, text, anchor?, evidenceId?, stale?)>, deadEnds, decisions, open, focus, amendments, next)`, `RegisterRender.markdown(register)` exactly in the §5.2 shape with harness-added `v(stale @old)` tags; `RegisterParser` (Markdown → `Register`, used only for golden round-trip tests — recovery and STATUS notes use typed records, never Markdown import, D-24; the model emits typed ops only); `register_versions` persistence per patch.
- Done: render golden test against the §5.2 example; cap 1,200 tokens measured.
- Log: 2026-09-20 — register: Mark/Step/Fact(staleAt harness-only)/DeadEnd/Decision/OpenItem(trip, fired)/AmendmentLine/Register with markStale and fireTrips, Trips glob/prefix predicates; RegisterRender.markdown in the §5.2 shape (deterministic golden test built from the §5.2 example; v(stale @old) and fired-trip lines harness-rendered), tokens() measured with the heuristic estimator (example ≈ within the 1,200 cap). RegisterParser deliberately not written: typed records round-trip through JSON, Markdown is a view only (D-24).

#### P1.5.2 [M] Typed ops, `Patch`, `Validator`, conditional ops · DONE
- Why: [§5.2 typed ops and invariants](docs/runtime/register-workset.md#sec-5-2); F21; [§5.5](docs/runtime/tools.md#sec-5-5) conditional ops.
- Build: `sealed Op { PlanAdd, PlanCursor, PlanTick, PlanCancel, FactAdd, FactRefute, DeadendAdd, DecisionAdd, OpenAdd, OpenClose, FocusSet, AmendPropose, Next }`, `Patch(ops, ifConditions: green(op:N)|applied(op:N))`, `Validator.check(register, patch, evidence, checks)` enforcing: one `[>]` while `[ ]` exists; exactly one `Next`; `tick` needs green accept on current version or evidence id; `v` needs an existing store id; ≤ 240 chars, no fences; `[~]` needs reason; dead ends need scope + reopen; `h` in active step flagged; red verify line needs an `Open` before `[>]` advances; `Amendments` only place for acceptance; cap 1,200; patches above 400 tokens rejected. `open.add(text, trip?, needs?)` trips are path/glob predicates evaluated after each edit batch; a fired trip renders one `⟨trip Qn fired: …⟩` line in `[A]` (P1.8.3), once. Conditions evaluated after runs; eligible list committed atomically against the STATE version; rejection returns the violated rule + sizes and leaves STATE unchanged (prior world effects stand).
- Done: one test per invariant; refuted facts retained in history; conditional drop rendered.
- Log: 2026-09-20 — Op sealed (13 typed ops), Condition green|applied(op:N) with parser, PatchOp/Patch, Validator.check(register, patch, ValidationContext) → Applied(register, appliedOps, dropped, flags, sizes) | Rejected(rule, detail, sizes): conditions evaluated first, one Next per patch, one [>] while [ ] exists, tick needs green accept or an existing evidence id, v needs an existing store id, ≤ 240 chars, no fences, [~] needs a reason, dead ends need scope + reopen, red verify line needs an Open before the cursor advances, register cap 1,200 / patch cap 400 tokens, refuted facts kept, h/stale facts under Next flagged (word-overlap heuristic). Atomic: rejection leaves the register unchanged.

#### P1.5.3 [M] `Workset` · DONE
- Why: [§5.3](docs/runtime/register-workset.md#sec-5-3) KNOWN/NOT SEEN, mark-then-stub, region-seen; F3.
- Pkg: `workset`
- Build: `Entry(path, range, version, source: look|postEdit|seed|recall, turn)`, `Workset` (token-budgeted), `known()`/`notSeen()` render (≤ 60 tokens + named stale drops), `covers(path, v, range)` region-seen check using *dispatch-time* coverage (FX-50) and excluding any line hidden by an observation's redaction mask (D-49 — reads, post-edit views, seeds and recall alike), `onVersionChange` → drop + announce now, physical stub at the next batch unless body > 800 tokens (immediate), `recall(id)` re-registers at recorded version (`historical` if changed), `export()` at cell end.
- Done: outline/def spans never make bodies KNOWN; stale announcement same turn; stub timing tests.
- Log: 2026-09-20 — workset: Entry(path, range, version, source look|postEdit|seed|recall, turn, resultId, tokens, hidden), StaleDrop(stubNow when tokens > 800), WorksetView (immutable dispatch-time snapshot, FX-50), Workset.register/covers/onVersionChange (drop + announce now)/stub/recall (Known | Historical)/takeAnnouncements/export/seed/render (≤ 60 tokens + named stale drops). Coverage excludes redacted lines (IX-10); outlines never register.

### P1.6 Tools
#### P1.6.1 [C] Tool contracts, schemas, `Envelope`, `Gauge` · DONE
- Why: [§5.4](docs/runtime/tools.md#sec-5-4) seven byte-stable families; envelope every tool every time; runtime-owned fields.
- Pkg: `tool`
- Build: `ToolFamily { look, edit, run, verify, state, task, kb }`, per-family arg types (serializable) + `ToolSchemas.forLineage(adapter, profile, role)` (D-20: frozen only after the adapter validates the dialect; masking preferred where valid; an unsupported profile is refused before dispatch, never silently rewritten; never changed mid-session), `ToolCall(opId = emitted order, family, args)`, result ids rendered as campaign-global `Alias` values `#n` (D-46, never reset per cell), `Envelope(header: resultId, tool, cls, versions, stamp, truncated, effects; body view; footer gauge)` renderer with harness-owned delimiters `⟦…⟧`/`⟨…⟩`, runtime-owned fields (`action_id, status, candidate_before/after, scope, completeness, artifact_refs, capture_complete, display_truncated, redaction_applied, effects_observed, effects_unknown, retry_class`), `⚠ instruction-shaped content` flag hook (P1.10.1), `Gauge.render(ctx%, reserve, checks@v, known n/tok, STATE vN, turn i/N)` ~20 tokens.
- Done: schema bytes identical across a session for a validated lineage; a fake profile rejecting the dialect is refused before dispatch (IX-24); envelope golden test.
- Log: 2026-09-20 — tool: ToolFamily (7, wire names), EffectClass R|W|D, ToolOps (family.op vocabulary; implementingS0 mask), per-family arg data classes (LookArgs, EditArgs/EditOpArgs/HunkArgs/TransformArgs, RunArgs incl. op=poll|cancel, VerifyArgs, StateArgs (raw patch ops), TaskArgs, KbArgs) validated at construction, ToolSchemas.forLineage(adapter, profile, mask) → Supported(SchemaSet with fingerprint) | Unsupported (dialect not validated by the adapter, IX-24) with additionalProperties=false JSON schemas, ToolCalls.parse (opId = emitted order; any bad call ⇒ Invalid, no world effect), Envelope/EnvelopeHeader/RuntimeFields/Effects/Gauge (~20 tokens) rendering the §5.4 shape with auth.Boundary.escape of delimiter bytes; ⚠ flag hook via header.flags (InstructionShape arrives in P1.10.1). Golden envelope test.

#### P1.6.2 [M] Turn partition and `Dispatcher` · TODO
- Why: [§5.4 turn semantics](docs/runtime/tools.md#sec-5-4), [§5.5](docs/runtime/tools.md#sec-5-5), F03 (stable op ids, legal dependencies).
- Build: `Partition.of(calls)` → `look/kb` reads ∥ → one `edit` batch (or one transform) → `run/verify` → `state`/`task`/`kb.propose` metadata; op ids keep emitted order; conditions must point backward in execution order (edit-after-later-run rejected before effects); runs execute only if no edit batch or it applied fully; `NotExecuted(reason)` disposition per accepted call id; parallel looks under one shared budget (D-26); end-turn honored only after reconciliation + persistence; one shadow checkpoint per mutating turn.
- Done: partition property tests; FX-50; every call id gets a result or an explicit disposition.

#### P1.6.3 [M] `look` (tree, outline, read, find, recall, catalog, def) · TODO
- Why: [§5.4 look](docs/runtime/tools.md#sec-5-4); L1 budgets on observation; F10.
- Pkg: `tool.look`
- Build: `Look(what, target, budget=1500, near?, glob?, in, since?)`, targets `path | path:a-b | path::Symbol`, whole-file reads above budget refused → outline + hint, dedup `see #17 (unchanged)` only when the normalized request is semantically equivalent (`what`, target, range, `in`, `glob`, `near`, `since`, budget class, workspace, index version) **and** the earlier result is still live with sufficient coverage in this projection — otherwise execute or explicitly rehydrate; an incomplete search never satisfies a broader one (D-46, IX-07); every rendered read carries its redaction mask into the `Observation` (D-49); `find` in `workspace|store|kb` with `scope/complete/truncated`, `recall(id, range?, since?)` (stubbed result, log slice, or new bg output; changed file ⇒ `historical v=…`), `catalog`; `def` via tier 0; `refs/importers/impact` masked until P3.2, `bmap` until P4.3. Every result registers an `Observation` and Workset entries.
- Done: budget/truncation/recall pointer tests; FX-10 (prompt vs capture limits distinguished).

#### P1.6.4 [M] `edit` anchored CAS batch · TODO
- Why: [§9.1](docs/runtime/workspace-editing.md#sec-9-1), [§5.4 edit](docs/runtime/tools.md#sec-5-4), [§9.5](docs/runtime/workspace-editing.md#sec-9-5); F2, F6, F20.
- Pkg: `tool.edit`
- Deps: P1.2.1, P1.2.4, P1.2.5, P1.2.6, P1.5.3, P1.7.8
- Build: `sealed EditOp { Anchored(path, expect: FileVersion, hunks: List<Hunk(anchor, near?, new)>, if?), Create, Delete(expect), Rename(expect), Revert(id|turn), Transform (P3.3) }`, every path through `WorkspacePath` (D-47), `Anchors.locate` (D-33: exact → specified whitespace normalization with original-byte span mapping; `near` selects among candidates; exactly one non-overlapping span inside displayed, unredacted coverage; ambiguous ⇒ refuse with three nearest candidates / all sites), `Preflight` (all ops; CAS on `expect` re-hashed from raw bytes; hunks inside `displayed(path, expect)`; non-overlapping; committed-contract scope and protected paths checked by the baseline `ScopeGuard` (P1.7.8); unsupported kinds rejected by name), `Apply` (preimages first; serialized per workspace; mid-batch I/O failure ⇒ actual per-file state + preimage ids, no "rolled back", no auto-retry), inline `Syntax` (D-10), post-edit views ±3 lines registered at the new version, `diffstat`, `EditResult(ok, views, versions, syntax, diffstat, touchedOutsideScope, testIntegrity (baseline flags from P1.7.8; precision in P3.4.2), error?: {kind, candidates, sites, diffSinceExpect})`.
- Done: FX-01..05; stale `expect` returns diff-since-expect; no write on any preflight rejection; an out-of-contract path is refused in S0 (IX-02).

#### P1.6.5 [M] `run`, `Runner`, effect classes, handles · TODO
- Why: [§5.4 run](docs/runtime/tools.md#sec-5-4), [§4.6 execution modes/effect classes](docs/runtime/workspace-editing.md#sec-4-6), [§9.4](docs/runtime/workspace-editing.md#sec-9-4), [§13.1](docs/operations/recovery.md#sec-13-1); F15, F19.
- Pkg: `tool.run`
- Deps: P0.6.1, P1.2.2, P1.4.3, P1.10.2
- Build: `Runner` interface + `TrustedLocalRunner(os)`; `ConfinedRunner` interface only (D-11); `EffectClass { R, W, D }` policy label + post-hoc verification by stamp diff (`R`→`W` only for observed in-scope writes; `D`/`unknown` retained); D-class ⇒ `Intent` + `Authority.approve` (interactive) or deny with reason unless contract-allowlisted (autonomous); `run(argv|cmd, cwd, shape, budget=1200, timeout=120, bg, intent, classHint, if: applied(op:N))` → `RunResult(id, exit, status, view, truncated, log blob, cls, stampBefore/After, current, changedPaths, handle?, parsed?)`; `cmd` = one shell invocation reported as a wrapper; the execution deadline kills the tree via `ProcessOwner`, no replay; `bg=true` persists a `Handle` (owner token, identity key, log path, cursor, status — durable independently of the calling coroutine, D-43); `poll(handle, since, observationTimeout)` → new output + cursor + status, an observation timeout leaves the process running (FX-22), no relaunch; `cancel(handle)` = request/status, not proof; lost acknowledgement or an unrecoverable handle ⇒ `unknown_outcome`/`lost`; `touched (by run #n …: k paths)` line; MCP invocation shape reserved (`mcp:` prefix → P4.7).
- Done: FX-07 (touched by run + Workset drop), FX-22 (poll same handle after harness restart — handle persistence part), FX-24, non-zero exit is information not an op failure.

#### P1.6.6 [M] Shaping parsers (`Shaper`) · TODO
- Why: [§8.3](docs/verification/scheduler.md#sec-8-3) parsed counts from runner output; wrapper exit never equals suite status; F16.
- Build: `Shaper` registry: pytest first (the end-to-end runner slice), then jest/vitest, then Gradle/Maven via JUnit XML (D-09), generic head+tail with error lines (cargo/go/dotnet/mocha/unittest/ruff/eslint/mypy/pyright/tsc in P3.1.4 or here if cheap); structured reports bound to `action_id`, runner/definition version, selector, completion status and captured-artifact provenance, written to a **fresh per-invocation destination** (a build-cache path only with recorded provenance) and parsed before prompt shaping (D-50); `Counts(passed, failed, errors, skipped, discovered)`; test identity = (check, project/module, file/suite, name, parameterization) with multiplicity preserved (D-27); `Status` from exit code AND parser (`pytest -k nonexistent` exit 5 ⇒ `inconclusive`); wrapper detection (`|| true`, `|| echo`) ⇒ status from the runner invocation only; missing or ambiguous structured evidence ⇒ `inconclusive`; truncation marks with recall pointer, prompt limit vs capture limit distinguished.
- Done: FX-08, FX-09, FX-10 with recorded runner outputs as test resources; stale green XML + no-tests run, forged stdout summary, same-name tests in two modules and a changed parameterized instance never yield green or "pre-existing" (IX-13).

#### P1.6.7 [M] `verify` op · TODO
- Why: [§5.4 verify](docs/runtime/tools.md#sec-5-4).
- Pkg: `tool.verify`
- Deps: P1.7.*
- Build: `verify(check(paths?))` → checker now; `verify(tests(selection = accept|ids|full))` (`blast` in P3.2.5) → shaped view + receipt; `verify(acceptance(ids?))` executes `run:` items and records stamps/currency; `verify(baseline())`; `verify(review)` masked until P3.5.2 (human) / P4.4.3 (cell).
- Done: every verify produces a receipt or an explicit `unavailable` receipt (FX-13 partial).

#### P1.6.8 [M] `state` op · TODO
- Build: `state(patch)` → P1.5.2; `state(blocked{reason, evidence, question?})` ends the cell as `blocked` after reconciliation; `state(retrieval_miss{need, why})` logged as a labelled negative (feeds P4.1.3).
- Done: rejected patch returns rule + sizes; nothing else applied.

#### P1.6.9 [M] `task.ask` · TODO
- Why: [§5.4 task](docs/runtime/tools.md#sec-5-4), [§4.1](docs/state/contracts.md#sec-4-1) answers as evidence vs amendment.
- Pkg: `tool.task`
- Build: `task(ask(question, options?))` → reconcile, end turn as blocked-with-question; interactive: `Authority.ask` then resume the same cell with the answer pinned; autonomous: cell ends `blocked`; user answers amend only when they change authority/requirements. `propose` arrives in P2.1.5, `delegate/collect` in P4.4.1 (masked until then).
- Done: answer recorded as evidence without a version bump when factual; amendment path when it changes requirements.

#### P1.6.10 [C] `kb` op contract · TODO
- Build: `interface Kb { search(query, kinds?, scope?, why): Hits(complete); get(id); skill(id) }` + `EmptyKb` (S0 without notes: complete-empty results, never "absent"); `kb.propose` masked until P4.1.
- Done: envelope `complete` semantics on the empty KB.

### P1.7 Verification baseline
#### P1.7.1 [C][M] `Check` and `Checks` registry (S0 set) · TODO
- Why: [§8.1](docs/verification/scheduler.md#sec-8-1) checks with kinds, selectors, cost classes, triggers; closures join coherence.
- Pkg: `verify`
- Build: `Check(id, kind: syntax|type|lint|unit|integration|acceptance|full|quality|review, selector: touched|blast|named(cmd)|all, inputClosure, definitionVersion (hash of definition + argv/cwd/selector + parser policy), closureManifest (P3.1.1), costClass, trigger, last)`, `Checks` registry seeded from sniffed commands + contract acceptance (`CHK-types-touched`, `CHK-lint`, `CHK-accept-<AC>`, `CHK-full`); S0 closures: `Known(touched paths)` for touched selectors, `Unknown` otherwise.
- Done: definition_version changes when argv/parser policy changes (FX-16 input).

#### P1.7.2 [M] End-of-turn `Checker` (synchronous, time-boxed) · TODO
- Why: [§8.1 layer table](docs/verification/scheduler.md#sec-8-1); D12 sync first.
- Build: `Checker.run(touched, timeBoxSeconds = 20)` invoking type/lint runners directly (D-09), scheduled after any mutation at the step boundary; `not_run` if deferred before dispatch, `timeout` if started then killed at the box (FX-58); Δ vs previous result + absolute status; superseded results archived, never presented as current.
- Done: FX-18 (no new errors while failures persist ⇒ "no change · still N"), FX-58.

#### P1.7.3 [M] `ChecksRender` (Δ + absolute) · TODO
- Why: L2; [§8.3](docs/verification/scheduler.md#sec-8-3).
- Build: `── Checks @stamp ──` lines: scope (`touched`, `k ctx`, `blast 14`), Δ, absolute, stamp, receipt id; unchanged red compressed; ≤ 3 lines in `[A]`; `stale (…closure moved…)`/`not run` states.
- Done: golden render of the §8.3 example.

#### P1.7.4 [M] Receipt emission and currency · TODO
- Why: [§8.4](docs/verification/scheduler.md#sec-8-4) refined by D-45 (I-04): a receipt supports the candidate actually tested.
- Build: `Scheduler.record(run/verify result)` → `Receipt` with `stamp_before/after`, argv/cwd, env, verifier version, definition version, parsed counts, limits, closure, `tested_inputs` (paths@versions from the declared closure ∪ observed access) and `input_stability ∈ {exclusive, isolated, unknown}`; `exclusive` = the workspace mutation lock is held for the whole check and a post-check rescan of `tested_inputs` (raw hashes) shows no write by any subprocess or external writer; eligibility: a required acceptance receipt counts as current only with `exclusive|isolated` stability, no relevant input mutation during the check, and `stampAfter == stampNow` (necessary, not sufficient); a mutation during the check keeps the factual outcome and marks the receipt `ineligible-for-final-tree` ⇒ rerun; declared scratch/output policy prevents spurious invalidation; stale marking through `Coherence`; reuse proofs arrive in P3.1.2.
- Done: a failed command cannot become a green receipt; exit 0 wrapper never green (FX-08); passing test followed by source mutation, mutate-and-restore background writer, and unknown stability never certify untested final bytes (IX-04).

#### P1.7.5 [M] `Baseline` receipt and pre-existing-failure ledger · TODO
- Why: [§8.5](docs/verification/scheduler.md#sec-8-5); the evidence line "pre-existing failure requires a baseline receipt".
- Deps: P1.2.3, P1.2.4 (materialize the captured initial candidate into `candidates/` from snapshot 0 and verify it against its manifest before the run, D-53), P1.6.6 (namespaced test identities)
- Build: `Baseline.run(suite)` on the verified initial candidate at `s0` → `PreexistingLedger(entries: namespaced test identity + environment fingerprint + failure signature, with multiplicity)`; rendered once in `[K]`; later red matching ⇒ `pre-existing (unchanged)`, else steering; ambiguous identity never matches (D-50); never permission to waive task acceptance; exceptions explicit in the finish receipt.
- Done: baseline runs against the captured tree even after edits; identity+signature matching, not counts; same-name tests in two modules stay distinct (IX-13).

#### P1.7.6 [M] `Reserve` enforcement · TODO
- Why: [§8.1 reserve](docs/verification/scheduler.md#sec-8-1); FX-43.
- Deps: P0.2.2
- Build: verification 15 % + recovery/persist 5 % of the cell's tokens and turns held from cell start (raised to known check costs); the 15 % is spendable only on checks and the final register patch, the 5 % only on the Result Packet, receipts and the STATUS note; when only the reserve remains the `reserve reached` gate blocks new edits and generation ("verify and report"); reserve reached with checks outstanding ⇒ `partial` naming the unverified scope.
- Done: FX-43; reserve spend never on generation.

#### P1.7.7 [M] `ExitGate` and `Verifier.accept` · TODO
- Why: [§8.7](docs/verification/acceptance-review.md#sec-8-7), [§5.9](docs/runtime/gates-termination.md#sec-5-9); F8; F01 corrections (committed authority, criterion-bound current evidence, required review approval).
- Build: `ExitGate.evaluate(register, contract, increment, receipts, reviews, flags) → Accepted | Refused(missing: list)`: every `run:` acceptance green *and current*; every `check:` has an accepted assessment of the stated criterion (not merely an id); every `review:` signed (human path P3, judge P4); no red verify line without an `Open` item; no `[ ]`/`[>]` without disposition; acceptance-surface flags (P1.7.8 baseline; P3.4.2 precision) justified **and** reviewed through a supported path when they touch a required check; no unresolved impact nudge for a changed public definition (P3.2.4); only receipts eligible per D-45 count. `Verifier.accept(packet)` validates status against receipts (never the model's word), binds `base_stamp`, `patch_hash`, `resulting_stamp`, env; repeated unsupported finalization ⇒ gap-directed recovery, not an endless gate; budget/cancellation/process-wait are explicit non-completed exits.
- Done: gate refusal lists exactly what is missing; `Open` cannot waive a required acceptance; ledger untouched on refusal.

#### P1.7.8 [M] Baseline `ScopeGuard` and conservative acceptance-surface policy (S0) · TODO
- Why: invariants 1–2 and [§8.6](docs/verification/acceptance-review.md#sec-8-6) are baseline obligations, not deep analysis; S0 must not mutate before they exist (I-02, D-31); P3.4 adds precision and workflows.
- Deps: P1.1.1, P1.2.6, P0.4.2
- Pkg: `verify`
- Build: `ScopeGuard.check(edit, contract)`: every built-in edit path must lie inside the committed `contract.scope.write_paths` (real path, D-47) and outside `protected_paths` (D-class); outside ⇒ refused until an amendment is approved and committed (a pending proposal grants nothing); already-authorized CI/lockfile/migration work is represented in the committed contract, not blocked by a blanket prompt. `TestIntegrity.baseline(diff)`: conservative classifier — any edit or run touching known test files, check definitions (`pytest.ini`, `jest.config.*`, `conftest.py`, build test config, CI files) or acceptance commands ⇒ `TestIntegrityFlag(kind = unclassified-weakening-risk)` rendered as the acceptance-surface line; completion with an unresolved flag on a required check needs `Authority.review` approval (human path) with the original obligation attached, otherwise the increment stays unaccepted; unknown classification is never proof of no weakening; legitimate test edits pass through review, not silence.
- Done: S0 fixtures: an uncommitted scope expansion is rejected; a weakened required check cannot complete without a review verdict; mid-attempt configuration changes are ignored (with P1.9.4) — all before P2/P3 exist (IX-02); FX-14/FX-52 pass in their baseline form.

### P1.8 Cell runtime
#### P1.8.1 [C] `Role` configuration · TODO
- Why: [§3.4](docs/architecture/roles-shapes.md#sec-3-4) role = context view × note scope × skill filter × tool mask × permission × tier prior × duties × ask-back × packet; declared in configuration, not code; never a security boundary.
- Pkg: `cell`
- Build: `Role(name, contextView, noteScope, skillFilter, toolMask, permission, tierPrior, duties, askBack, packetKind, personaLines ≤ 3)`; `Roles.implementing` default; effective ops = role mask ∩ shape/stage ∩ authorization.
- Done: roles loadable from `Config`; effective-ops intersection test.

#### P1.8.2 [M] `Layout` render `[S][R][K][T]` · TODO
- Why: [§5.1](docs/runtime/context-layout.md#sec-5-1) cache discipline: breakpoints at the ends of S/R/K/T; byte-stable per role/repo version/cell; no timestamps/counters in cached regions.
- Deps: P1.1.4, P1.3.2, P1.6.1, P0.3.2
- Build: `Layout.render(role, prime, k: CompiledK, t: Transcript) → List<Segment>`; `[S]` = kernel contract text ([Appendix A](docs/reference/kernel-contract.md#sec-appendix-a)) + role schemas + the three evidence lines + error policy + data/instruction rule + execution-mode label; `[K]` in S0 = contract slice + pre-existing ledger (seeds/notes/skills arrive P2–P4); `[T]` = native items, pinned user messages verbatim.
- Done: stability test: same inputs ⇒ identical segment bytes across turns; adapter `validate()` accepts.

#### P1.8.3 [M] `Anchor` render `[A]` · TODO
- Why: [§5.1 `[A]`](docs/runtime/context-layout.md#sec-5-1) ≤ 2.5K, rebuilt every turn, never cached; [§5.10 example](docs/reference/rendered-turn.md#sec-5-10).
- Build: `Anchor.render(digest ≤ 150, register ≤ 1.2K, workset ≤ 60, touched ≤ 10, checks ≤ 3 lines, focusZoom ≤ 300, focusNotes ≤ 300 (P4), gauge, nudges ≤ 2, fired trip lines (P1.5.2))` with `[A]` size metric.
- Done: golden test against §5.10; cap enforcement per block.

#### P1.8.4 [M] `Gauge` on every result · TODO
- Build: gauge appended to every envelope (P1.6.1) and after the turn; inputs from residency (`ctx %`), reserve, checks state, Workset, register version, turn counter.
- Done: ~20 tokens; present on every result.

#### P1.8.5 [M] `Gates` and nudges (S0 set) · TODO
- Why: [§5.6](docs/runtime/gates-termination.md#sec-5-6) one line each, once per condition, computed by the harness.
- Build: `Gates.evaluate(state) → List<Nudge|Rejection>`: entry (first non-register edit without an `accept:` step / unresolved acceptance), exit (P1.7.7), pressure (`tokens > α·C_max` ⇒ P1.8.7 handling), stall (3 turns without a progress event; live build output is work), loop (identical `(tool, args, result hash)` twice ⇒ nudge; third ends the turn with a required `state` op), no/two cursors, red-not-recorded, stale-fact-in-Next, reserve, turn budget 80 %. Scope/acceptance-surface (P3.4), impact (P3.2.4), contract touch (P3.4.3; active once CON notes exist), repeated failure signature (P4.6.2) register later through the same interface.
- Done: each gate fires once per condition in tests; progress events defined (evidence-backed tick, h→v with id, green run advancing an AC, verified new fact, new dead end).

#### P1.8.6 [M] `Residency`: eviction, stubs, recall · TODO
- Why: [§5.7](docs/runtime/residency-rebuild.md#sec-5-7); L1; F26 (batched eviction preserves the prefix cache).
- Build: `Residency(k = 8, rMax = 16K, stubTokens ≈ 20)`: results live `k` turns then stub; total live-result budget `R_max` stubs the oldest refetchable early; batch order by `p_reuse · c_refetch` (raw current-state observations first, verdicts last); model messages older than `3k` turns trimmed to first line + calls; user messages and packet pinned; complete tool-call/result units preserved (adapter `validate()` rejects broken pairs — FX-21); stub = recoverable pointer to captured bytes; `C(t)` accounting from §5.7.
- Done: residency bound holds with multi-result turns; one cache miss per `k` turns in the fake adapter's accounting.

#### P1.8.7 [M] `Cell` turn loop · TODO
- Why: [§3.7 `cell()`](docs/architecture/lifecycle.md#sec-3-7) with F03 corrections; [§5.5](docs/runtime/tools.md#sec-5-5) transactional turns.
- Deps: P1.6.2, P1.8.2–P1.8.6, P1.7.2, P1.4.3, P0.3.4
- Build: `Cell.run(ctx, increment, budget): CellExit` — per turn: enforce dispatch authority + budget reservation; render anchor; `adapter.validate` + hard admission check (P2.3.3 refines) + `complete`; `validate_complete_calls_and_dependencies` (fail closed, no partial-call execution); journal native output **before** results; append native items (assistant calls precede results); no calls ⇒ role completion path (P1.8.8); partition + dispatch; reconcile workspace + persist checkpoint on **every** exit path; end-of-turn checker; terminal request handling; eviction every `k`; pressure: in P1 checkpoint + terminate `partial` with a replan hint (full rebuild is P2.5.2; a P1 cell never summarises); turn budget exhaustion ⇒ `partial`.
- Done: first vertical slice (P1.12.2) runs through this loop; every exit path leaves a persisted checkpoint (fault injection).

#### P1.8.8 [M] Role completion and `ResultPacket` · TODO
- Why: [§5.9](docs/runtime/gates-termination.md#sec-5-9); `assess_role_completion` (implementing gate vs declared packet validators).
- Build: `ResultPacket` with all §5.9 fields (`contract_version`, `execution_generation`, `base`, `read_versions` runtime-observed, `status` proposal, `waiting`, `register`, `workset_export`, `changes`, `transforms`, `receipts` refs, `stamp`, `coverage` from telemetry, `flags`, `not_tested`, `notes_to_persist`, `open_questions`, `blocked`, `self_assessment`, `cost`); `Completion.assess(proposal, role)` (implementing ⇒ `ExitGate`; other roles ⇒ packet validators in P2/P4); gaps recorded; `cannot_progress` ⇒ incomplete exit; a completion proposal may request scheduler-owned checks but is accepted only with current evidence (outer verification reuses them).
- Done: packet fields never taken from model text where the docs say runtime-owned; `done` is a proposal until the verifier accepts.

### P1.9 Controller (S0 path) and P0 lifecycle controls
#### P1.9.1 [C][M] `Lifecycle` state machines · TODO
- Why: [§3.2](docs/architecture/components.md#sec-3-2) "one state machine over typed records"; [§5.9 outcomes](docs/runtime/gates-termination.md#sec-5-9); invariant 11.
- Pkg: `campaign`
- Build: `CampaignState`/`CampaignOutcome { completed, waiting_for_process, waiting_for_input, blocked_external, budget_exhausted, cancelled, failed }`, `CellState`, `IncrementStatus`, typed transitions (only via receipts/packets), persisted with the ledger; `partial` is never `verified`.
- Done: illegal transitions impossible by construction; outcome table exhaustively tested.

#### P1.9.2 [M] Campaign open and reconciliation · TODO
- Why: [§3.7 `campaign()`](docs/architecture/lifecycle.md#sec-3-7) first lines; [§13.1](docs/operations/recovery.md#sec-13-1) reconcile before any consequential action.
- Deps: P1.1.*, P1.2.*, P1.4.3, P0.5
- Build: `Controller.open(repo, request, policy)`: create/load contract, workspace, store, `EmptyKb`; capture dirty state + `s0`; reconcile open intents and stamp drift (`Touched (external)`); impact pre-scan stub returns `unknown` until P3.2.6; `ShapeSelector` in P1 returns `S0` when eligible, else `blocked("shape S1+ unavailable")` (honest, FX-48/49).
- Done: reopening a store with an open intent yields `unknown_outcome` and blocks retries (FX-23 open-time part).

#### P1.9.3 [M] S0 run and `Compiler` (S0 form) · TODO
- Why: [§3.6](docs/architecture/lifecycle.md#sec-3-6) data flow for one increment; `G_single(C)`.
- Pkg: `campaign`, `context`
- Build: `Compiler.compile(increment, campaignState, profile)` S0 form = mandatory only (contract slice, rules, pre-existing ledger, native protocol items) with budget arithmetic and `NEEDS_RESCOPING`; same class grows in P2.3. `Controller.runS0()`: compile → cell → reconcile/persist → `Scheduler.verify` (reuse current receipts) → `Verifier.accept` → `commit_outcome_if_current` (contract/candidate/generation check; one ledger owner) → finish (final acceptance at final stamp) → archive trace (extraction P4). `dispatch_outcome` for `done | partial | blocked | waiting | failed` (S0 has no replan; `partial` ⇒ honest partial outcome since continuation cells are P2).
- Done: end-to-end S0 with the scripted model on a fixture repo; ledger updates only on receipts.

#### P1.9.4 [M] P0 lifecycle controls · TODO
- Why: [§3.5](docs/architecture/roles-shapes.md#sec-3-5) last row (F28): cancellation, leases, budgets, reconciliation, accounting active in every shape; F09.
- Deps: P0.1.3, P0.2.2, P0.5.1, P1.11.2
- Build: `AttemptConfig` frozen at campaign open (harness version, validated config snapshot incl. flags and role-text versions, profiles) — invariant 12 applies to every attempt, so mid-attempt configuration changes are ignored and a production config that disables a mandatory control is rejected (D-48, I-02); `ProjectLock` acquired before any consequential action (D-44); `Cancellation` token checked before dispatch and before any publication, with bounded cleanup of reservations and evidence that never discards late effects (D-26); `Lease(workspace, holder, expiry, executionGeneration)` single writer per workspace, expiry revokes publication authority only; `Reservations` per cell (atomic, P0.2.2); reconciliation on every terminal path; `Accounting` per call (P1.11.2).
- Done: FX-25, FX-48, FX-49 (S0 parameterization); cancelled cell's late effects are archived, its publication refused (single-cell form of FX-26); a config edit during an attempt has no effect until the next attempt (IX-02, IX-18).

#### P1.9.5 [M] `FinishReceipt` (S0 form) · TODO
- Why: [§5.9 campaign finish receipt](docs/runtime/gates-termination.md#sec-5-9).
- Build: requirements with status/blockers; acceptance items (kind, status, stamp, currency, log ids); changes split `agent | by_run | pre_existing_user_changes`; `acceptance_surface_modified` (P3.4); checks run with verifier version + environment; `not_verified`; dead ends/decisions/ADR candidates/open items/pending amendments; routing decisions (P4); budget by cache class + helper share; memory candidates (P4); `highest_authorized_stage` (`patch` only in P1; never "delivered").
- Done: exported via `Views`/`Export`; a budget stop reports `partial`.

#### P1.9.6 [C][M] `Astrolabe` facade and `AstrolabeJava` · TODO
- Why: the SDK entry point for Kotlin and Java hosts (D-07); nothing sits above the controller.
- Deps: P1.9.3, P0.1.3, P0.4.1–P0.4.3
- Pkg: root `io.astrolabe`, `java`
- Build: `Astrolabe(config, adapter: ProviderAdapter, authority: Authority)`: `open(repo): Project` (state root, project lock, workspace, store, kb), `suspend campaign(project, request, policy?): CampaignHandle` (`workId`, `await(): CampaignOutcome`, `cancel()`, `amend(text)`, `events: Flow<AgentEvent>`, `views`), `close()` (resource closure documented); `AstrolabeJava` mirrors it with `CompletableFuture` (cancellation propagates to the campaign), blocking variants, `EventSink` registration, and accepts `JavaProviderAdapter`/`JavaAuthority` implementations through the bridges of P0.3.4/P0.4.2; documented exception mapping and callback threading; no `suspend`, `Flow` or `value class` in `io.astrolabe.java` (D-07, I-14).
- Done: P1.12.3 uses only `AstrolabeJava` with Java-authored adapter and authority; ABI dump reviewed.

### P1.10 Authority and integrity baseline
#### P1.10.1 [M] `Boundary`: delimiters and instruction-shape flag · TODO
- Why: [§14.3](docs/platform/security.md#sec-14-3); F11; delimiters are cues, not security.
- Pkg: `auth`
- Build: escape of delimiter-like payload bytes; `InstructionShape.detect(text)` heuristics ⇒ envelope flag `⚠ instruction-shaped content` (never filtered, never executed); `RulesTrust` (D-32): discovery proposes candidate files; only a binding from host configuration or explicit user authority (canonical path + digest + provenance) promotes a snapshot to instruction text; the approved snapshot is reused across resumes; replaced bytes drop back to data until re-approved; editing the rules path never elevates edited bytes; `[S]` data/instruction rule text.
- Done: FX-38 (repo file / tool result instructing the agent ⇒ data, flagged, authorization unchanged); an untrusted rules-named file stays data, a trusted snapshot loads, replacement + resume cannot elevate new instructions (IX-08).

#### P1.10.2 [M] Capability ceiling and effect policy · TODO
- Why: L10; [§14.1](docs/platform/security.md#sec-14-1); executor enforces regardless of model request.
- Build: `Capability`/`Ceiling` from `Contract.authorization.capabilitySet`; `Policy.classify(run) → EffectClass` (protected paths ⇒ D; network/outside-workspace/git refs/package install (configurable)/privilege/destructive git ⇒ D); generated scripts inherit the caller's ceiling; `ExecutionMode { TrustedLocal, Confined }` label in `[S]` and every report (the label describes limitations, it enforces nothing); a host that *requires* `Confined` fails at configuration/dispatch until a backend exists (P7) — trusted-local is never substituted (D-11).
- Done: a masked/denied op is refused in the executor even when the schema would allow it; FX-39 (ceiling) single-executor form; `Confined` required + no backend ⇒ refusal, not a relabelled trusted-local run.

#### P1.10.3 [M] `Redaction` · TODO
- Build: `Redaction.apply(bytes) → (redacted, mask: source-line ↔ rendered-line mapping, limitations)` before model exposure and before reusable evidence persistence; the mask travels with the `Observation` and excludes redacted lines from coverage (D-49); recall and export never return unredacted bytes; signed opaque provider items are stored in protected `native/` and never rewritten (D-25); env allowlist in runner; recovery preimages exempt and access-restricted (D-14); `capture.redacted` and redaction limits recorded per observation.
- Done: secret patterns never reach envelopes, notes, recall or exports in tests; a redacted line inside a multi-line anchor grants no coverage while exact unredacted spans remain usable (IX-10); preimage revert still exact.

#### P1.10.4 [C] `PermissionLadder` data model · TODO
- Why: [§14.2](docs/platform/security.md#sec-14-2) separate grants `patch → local commit → push → merge → deploy`; ceiling from the contract.
- Build: `Stage` enum, `PermissionLadder(ceiling)`, `highestAuthorizedStage` tracking; only `patch` reachable in P1 (commit policy P5.2).
- Done: any attempt above the ceiling is refused and recorded.

### P1.11 Telemetry and accounting
#### P1.11.1 [M] `Span`s and metrics · TODO
- Why: [§15.5](docs/platform/adapters.md#sec-15-5) phase tags, parent/child spans, exclusive cost recorded once, critical-path wall clock vs summed worker time.
- Pkg: `telemetry`
- Build: `Span(id, parent, phase, ids, start/end, exclusiveCost)`, per-cell metrics (tokens by cache class, `[A]` size, STATE upkeep tokens, tool calls, tool seconds, checks by layer, gates fired, rebuilds, turns, boundary reason, manifest ref, pre-compilation hit/miss), per-campaign metrics (cost per accepted task, first-attempt pass rate, verified/blocked/cancelled increments, continuations, rebuilds per cell, boundary cost share, probe/review usage, escalations, human interventions with reasons); per-project metric model (KB usage rates, retrieval misses, routing calibration quadruples, MAST-tagged failure distribution, calibration-prior drift, post-merge reverts and churn as deployment outcomes reported by the host through `Outcomes.report(workId, outcome)`) with its sources filled in by P2.6, P4.1 and P4.5.
- Done: inclusive totals derived without double counting (test); events emitted for every span.

#### P1.11.2 [M] `Accounting` and exports · TODO
- Why: [§15.2](docs/platform/adapters.md#sec-15-2), [§11.5](docs/operations/routing.md#sec-11-5) four quantities; missing usage = unknown.
- Build: per call: native usage retained + normalized categories + `Money` from the profile's dated price table; `Quantities(bytesTransmitted, modelVisibleInput, billedUsage, durableState)`; `cost_per_accepted_task` (undefined at zero); cold vs warm reported separately; `Export` JSON files under `exports/`; OpenTelemetry GenAI span export `[O]`.
- Done: FX-59 (missing usage stays unknown; no zero-spend assumption); fake adapter invoices reconcile with recorded segments.

### P1.12 Stage A validation
#### P1.12.1 [V] Harness fixture tests · TODO
- Build: FX-01..10, 13 (partial), 15, 16 (stale marking), 18, 21, 23 (open-time), 24, 25, 26 (single-cell form), 38, 39 (executor ceiling), 43, 48, 49 (S0), 50, 58, 59 + AX-01..10 + IX-02, 04, 05, 06, 07, 08, 09, 10, 12, 13, 15, 16, 17, 20 as JUnit tests using the test kit and fault injector; invariant metrics that must be zero ([§19.4](docs/evaluation/method.md#sec-19-4)) asserted where applicable (unseen-content edits, destructive missteps, silent acceptance changes, stale bodies served, unauthorized publications, late superseded results merged, false green).
- Done: all listed fixtures green on Windows and Linux (JDK 26); phase level `FIXTURE_VALIDATED`, live gate `UNMEASURED`.

#### P1.12.2 [V] First vertical slice · TODO
- Why: [§18.2 first vertical slice](docs/implementation/roadmap.md#sec-18-2).
- Build: scripted campaign on a fixture repo with a cross-file defect: contract → locate/read → guarded patch (one deliberately stale attempt) → run/poll a test (bg handle) → evidence → forced interruption (fault injector between edit apply and receipt) → reopen reconciles (full resume is P2.2.4) → verify candidate → receipt; initially dirty file preserved and separated in the finish receipt.
- Done: slice passes; per-call billed usage visible in exports; turn counts recorded as the fake-adapter baseline (B0/B-HELM are live comparators, D-28).

#### P1.12.3 [V] Java consumption smoke · TODO
- Deps: P1.9.6
- Build: Java test source set (or `samples/java`) calling `AstrolabeJava`: implements a **Java-authored** `JavaProviderAdapter` (scripted) and `JavaAuthority`, opens a project, runs an S0 campaign, answers and rejects a question, subscribes an `EventSink`, cancels work, reads `Views` and outcomes; no coroutine APIs, `suspend`, `Flow` or `value class` visible (I-14).
- Done: compiles and runs with plain `javac`-level Java on JDK 26 on Windows and Linux (IX-14); ABI dump reviewed.

#### P1.12.4 [V] Platform validation · TODO
- Build: Windows and Linux (equal targets, JDK 26) tests for the `ProcessOwner` backend (job objects / process groups: child spawning, parent exit, harness crash → `lost`, cancel/deadline races, log-cursor continuation, PID reuse), file replacement, `WorkspacePath` resolution (junctions/symlinks/case aliases), encoding (D-12, D-43, D-47); document supported execution modes and the recovery-coverage statement of D-44 per platform.
- Done: both platforms green; unsupported behaviours listed in `Config` docs, not assumed (IX-20).

---

## P2 Stage B — Continuity (S1)
Goal: [§18.2 Stage B](docs/implementation/roadmap.md#sec-18-2): campaign controller, requirement graph, plan cell, increments, compiler with seeds/coverage/manifest, carry-forward, cross-cell coherence, the one rebuild mechanism (five uses), resume/reconcile, calibration prior, KB read path with STATUS/CAL notes written deterministically by the harness.
**Fixtures:** FX-11, 19, 20, 22, 23 (full resume), 35 (invalidation marks; injection exclusion P4), 42, 45, 46, 49 (S1), 51 (context-local coverage), 56, 57; IX-03 (human-review substitution), IX-06, IX-11, IX-17 + the four crash intervals of [§13.4](docs/operations/recovery.md#sec-13-4) · **Unsupported until later:** closures beyond `known(paths)`/reuse proofs (P3), impact pre-scan (P3.2.6), review cells (P4), KB admission/injection ranking (P4), probes/routing/recovery ladder (P4), S3 (P5). Live gate (B2 ≥ B1 on medium tasks): `UNMEASURED`.

### P2.1 Full contract, requirement graph, ledger, plan cell
#### P2.1.1 [C][M] `RequirementGraph`, `Increment`, `Ledger` · TODO
- Why: [§4.2](docs/state/contracts.md#sec-4-2); invariant 2; F5.
- Pkg: `graph`
- Build: `Increment(id, requirementIds, title, accept, dependsOn, writeScope, expectedFiles, risk, redOkUntil, produces: artifact|resolves(Q), status: verified|in_progress|pending|blocked|cancelled(reason), cells, sizing)`, `RequirementGraph(increments, regressionObligations, ownershipMap)`, `Ledger` (harness-derived per requirement: status, evidence receipts, stamp validity), `Graph.validate(contract)`: every requirement covered; each increment has ≥ 1 executable `accept:` or a `check:` with a named evidence kind; every node produces an artifact or resolves a named uncertainty; cycles ⇒ one joint increment or an explicit planning conflict (Tarjan SCC), never an unreachable frontier; `readyFrontier(budget)` deterministic (stable order by dependency depth, then id); model reordering within the frontier via register only; `continue(inc)` vs `cancel(inc, reason)` (cancellations kept); import cycles do not dictate order.
- Done: validation rejects "think more" nodes and uncovered requirements; frontier determinism test; FX-42 (verified increment never re-executed; regression obligation only).

#### P2.1.2 [M] Plan cell role and packet validator · TODO
- Why: [§3.4 plan cell](docs/architecture/roles-shapes.md#sec-3-4), [§4.1 origins](docs/state/contracts.md#sec-4-1), [§4.2](docs/state/contracts.md#sec-4-2); main line only, never a child.
- Deps: P2.1.1, P1.8.1, P0.4.2
- Pkg: `cell` (role), `campaign` (validator)
- Build: `Roles.plan` (context view: contract, prime, GLOBAL + CON + ADR notes, behaviour maps, CAL prior; mask: `look`, `kb`, `state`, `task.ask`, `task.delegate(probe)` (masked until P4.4.2), `verify.baseline`; tier prior high), `PlanPacket(graphProposal, acceptanceProposals, increments with write scopes, ownershipMap, decisionPackets, conCandidates, adrCandidates, shapeSuggestion)`, `PlanPacketValidator` (the declared completion validator for this role; feeds `Completion.assess`), the plan-role policy text (a few operational lines from the §3.4 duties column; D-38), interactive approval of proposed acceptance via `Authority.resolve`; autonomous mode freezes them as `model`-origin with the receipt listing them; policy never auto-accepts weakening.
- Done: plan cell output stored as a proposal; controller stores the graph only after `Graph.validate`; missing acceptance for an unexpressible requirement names the needed judgment (`review:`), never a fabricated oracle (D17 in [§20.2](docs/reference/decisions.md#sec-20-2)).

#### P2.1.3 [M] Decision packets · TODO
- Why: [§4.2 decision packets](docs/state/contracts.md#sec-4-2); F4.
- Build: `decision.add(text, because, rejected, probe?)` already in the register (P1.5.2); boundary-crossing decisions marked `→ candidate ADR` in the packet (`adrCandidates`; KB queue in P4.1); stall gate may suggest the named probe (P1.8.5 hook).
- Done: ADR candidates appear in the finish receipt; private reasoning never serialized.

#### P2.1.4 [M] Sizing telemetry · TODO
- Why: [§6.7](docs/context/continuity.md#sec-6-7) inputs; [§3.8](docs/architecture/components.md#sec-3-8) pressure rebuild counted as decomposition failure.
- Build: `Sizing(turns, continuations, rebuilds, filesTouched, expectedFiles)` per increment, updated at cell end; persisted in `sizing`.
- Done: continuation and rebuild counters verified by tests.

#### P2.1.5 [M] `task.propose(plan | increment_split | amendment)` · TODO
- Why: [§5.4 task](docs/runtime/tools.md#sec-5-4); required by the scope guard (P3.4.1) and by `NEEDS_RESCOPING` handling (P2.2.2); needs no delegation machinery.
- Deps: P2.1.1, P2.1.2, P1.1.3
- Pkg: `tool.task`
- Build: `propose(plan)` → plan proposal to the controller (plan role); `propose(increment_split)` → the controller records the request and re-plans within authorized coverage (a packet to the plan role); `propose(amendment)` → `Contracts.propose` (P1.1.3); all three are metadata writes after execution in the turn partition; unmasked for plan/implementing roles per shape.
- Done: a split proposal reaches the plan role as a packet; proposals never change the contract or the graph directly.

### P2.2 Campaign controller (S1 path)
#### P2.2.1 [M] `ShapeSelector` (S0–S2) and activation table · TODO
- Why: [§3.5](docs/architecture/roles-shapes.md#sec-3-5) deterministic, logged `select_shape`; collapsibility contract; F09 (S3 only after validated planning, P5.1.4).
- Pkg: `campaign`
- Build: `ShapeSelector.select(contract, impact, plan?) → Shape + inputs log` (size classes D-16; risk = max(blast radius, contract touch ⇒ high, reversibility); S0 iff size S ∧ low risk ∧ no `review:` ∧ no resume expected; S2 iff `review:` items ∨ risk ≥ high ∨ contract touch ∨ ambiguous bug (D-39); else S1; S3 branch disabled until P5.1.4; unknown impact/discovery coverage is `unknown`, never low risk, and cannot establish S0 eligibility by itself (I-23)), `Activation(shape)` = feature set per the §3.5 table (used to mask tools/features); **required capabilities are selected independently of which automatic roles exist** (I-03, D-23): an S2 selection whose only missing capability is *required review* runs sequentially with `Authority.review` satisfying it, the substitution recorded in the shape log; a selection needing another unavailable capability (probes, routing, repair) ends as an honest `blocked("capability unavailable: …")`; upgrade only on traced evidence (pressure, probe request, risk floor); downgrade aggressively, but a downgrade never removes an outstanding required check/review; required review obligations (incl. refactor mode) select ≥ S2 or an authorized human review.
- Done: table-driven tests for every row of the shape table; `Campaign.ShapeSelected` event carries inputs.

#### P2.2.2 [M] Campaign loop · TODO
- Why: [§3.7 `campaign()`](docs/architecture/lifecycle.md#sec-3-7) verbatim structure, [§3.6](docs/architecture/lifecycle.md#sec-3-6).
- Deps: P2.1.*, P2.3, P2.4, P2.5
- Build: `Controller.run()`: `while unfinished_requirements`: enforce cancellation/leases/authority/reservations → incorporate observations + invalidate (Coherence) → stop/external wait ⇒ persist honest outcome → `next_ready(budget)`; empty frontier ⇒ wait/block/exhaustion (never `completed`) → handle-wait polling or independent work → `select_profile` (single main profile until P4.5) → `take_valid_precompiled_or_compile` (pre-compilation P3.7) → capacity/evidence gap handled without dispatch (`NEEDS_RESCOPING` ⇒ ask the plan role for `increment_split`; `NEEDS_MORE_EVIDENCE` ⇒ worker investigates) → cell with reserved budget → reconcile + persist effects (incl. partial/late) → `Scheduler.verify` (reuse current receipts) → required review once when proposed done (human path P3.5.2 / cell P4.4.3) → `commit_outcome_if_current` (contract version, candidate, execution generation; one ledger owner) → extractor enqueue (P4.2) → telemetry → `dispatch_outcome`: `done` close · `partial` continue same increment with register + seeds, no budget reset · `replan` replace plan with authorized coverage preserved · `waiting` persist handle + independent work · `blocked` resolve by authority or honest block · `failed` recover within original budget (ladder P4.6; P2: escalate to `blocked`) → `finish`.
- Done: scripted multi-increment campaign on a fixture repo; empty frontier with unverified requirements ends as `blocked`/`budget_exhausted`, never `completed`.

#### P2.2.3 [M] Sequential role switching · TODO
- Why: [§3.5 S1](docs/architecture/roles-shapes.md#sec-3-5) "sequential role switching in one executor"; [§5.8](docs/runtime/residency-rebuild.md#sec-5-8) `rebuild(role_switch)`.
- Deps: P2.5.1
- Build: plan → implementing (and back on `replan`) through `Rebuild(RoleSwitch(role'))` with `m = 0`, new mask + knowledge view, STATUS note written, provider continuation never reused across the switch.
- Done: transcript tail empty after switch; role mask enforced; FX-56 (call/result ids and opaque items remain valid).

#### P2.2.4 [M] `Resume` protocol · TODO
- Why: [§13.4](docs/operations/recovery.md#sec-13-4); crash intervals; F12.
- Deps: P2.5.1, P1.4.3, P1.6.5 (handles)
- Build: `Resume.run(store, workspace)`: load contract, ledger, last register, Workset export, live handles, frozen attempt configuration; diff current stamp vs last recorded ⇒ `Touched (external)`; handles ⇒ `running | exited | lost` (identity key, not PID alone); open intents without receipts ⇒ `unknown_outcome` ⇒ reconcile before any retry; recompute receipt applicability by closure; delegated results with moved bases ⇒ `stale-for-integration` (P4/P5); `Rebuild(Resume)`; first turn sees `KNOWN: seeds only` + one-line resume note; register never trusted over the workspace; granted authorization preserved.
- Done: FX-22 (poll the same handle, no duplicate launch), FX-23 (both crash points), the four §13.4 crash intervals via `FaultInjector`.

#### P2.2.5 [M] Attempt freeze · TODO
- Why: invariant 12; [§3.3 `attempt_id`](docs/architecture/components.md#sec-3-3).
- Build: the `AttemptConfig` frozen in P1.9.4 is persisted per attempt under `campaigns/`; harness/config changes apply only at attempt boundaries; new attempt ids are controller-assigned (alternative attempts P4.6.4) and each carries its own frozen snapshot; resume reloads the frozen snapshot, never the live `Config`.
- Done: a resumed attempt runs under its original snapshot even after `Config` changed (test).

#### P2.2.6 [M] Campaign finish · TODO
- Why: [§8.7 campaign gate](docs/verification/acceptance-review.md#sec-8-7), [§7.3 cadence](docs/repository/navigation.md#sec-7-3), [§5.9 finish receipt](docs/runtime/gates-termination.md#sec-5-9).
- Build: `Controller.finish()`: all contract acceptance at the final stamp; full suite green or failures in the pre-existing ledger (allowed exceptions explicit); full-suite cadence every `K = 5` verified increments and at campaign end; campaign review predicate `(shape ≥ S2 ∧ increments ≥ 3) ∨ refactor_mode ∨ explicitly required` (human path P3 / cell P4); full `FinishReceipt`; explicit gaps otherwise.
- Done: finish never reports `completed` with a stale acceptance receipt.

### P2.3 Context compiler
#### P2.3.1 [M] `Compiler.compile()` (full) · TODO
- Why: [§6.1](docs/context/compiler.md#sec-6-1) deterministic function of (increment, campaign state); L3; F06 (count the complete serialized context; validate all inputs).
- Pkg: `context`
- Deps: P1.9.3 (S0 form), P2.4, P2.6, P1.3.1
- Build: `compile(increment, contract, workspace, store, kb, seeds, profile)`: reconcile contract/candidate versions; `mandatory` = contract slice ∪ mandatory skill modules (P4.3; empty until then) ∪ rules ∪ `index/contracts.md` ∪ CON/ADR notes whose anchors ∩ write_scope ≠ ∅ ∪ carry-forward ∪ pre-existing ledger ∪ packet required refs ∪ native protocol items that must remain intact; `budget = C_profile·α − |S| − |R| − |pinned T + retained protocol| − reserve(output + next observation + [A]_max + margin)`; `NEEDS_RESCOPING_OR_LARGER_PROFILE` when mandatory exceeds budget (never drop an invariant); `workset_seeds ≤ 4K` re-served at current versions with hashes (changed files ⇒ NOT SEEN + note); `kb_slice` (CON/GLOBAL only until the ranker in P4.1.3); skill modules (P4.3); `focus_zoom` + `calibration_prior` (P2.6.4); `greedy_cover` (D-18: transitive `depends_on` bundles with cycle detection; marginal token pricing so shared dependencies count once; no silent mandatory drop); the contract slice carries complete acceptance definitions (D-52); expand `depends_on` within budget; recheck versions/coverage; coverage assertion (presence of constraints, acceptance, CON for touched paths, rules) ⇒ `NEEDS_MORE_EVIDENCE`; `adapter.validate(render)`; persist `Manifest`; return `[S][R][K]`. Selection order: mandatory → affected contracts → carry-forward → seeds → local implementation → lessons/pitfalls → skills → background.
- Done: property tests: mandatory never dropped; every serialized contribution charged once; rules/index content referenced for coverage, not duplicated into `[K]`; FX-19 (missing constraint ⇒ validation fails).

#### P2.3.2 [M] `Manifest` · TODO
- Why: [§6.5](docs/context/continuity.md#sec-6-5) "absent" vs "misread" distinguishable.
- Build: `Manifest(incrementId, ids, contractVersion, registerVersionIn, notesInjected (ids, versions), seeds (path, range, hash), skills + versions, profile + effort, budgetArithmetic, omissions with reasons, continuationLineage, reductionOps, estimatedTokens, actualUsage?, boundaryReason: done|partial|replan|pressure|resume)` persisted per cell; `Cell.Ended` links it.
- Done: manifest for every compiled context; actual usage filled after the first response.

#### P2.3.3 [M] Hard admission check before every dispatch · TODO
- Why: [§6.1](docs/context/compiler.md#sec-6-1) total-context admission incl. output/reasoning headroom; re-estimated on actual usage; [§15.1](docs/platform/adapters.md#sec-15-1) effective continuation history counts.
- Build: `Admission.check(request, profile, estimate: Estimate, lastActualUsage) → ok | CapacityCondition`: planning estimates (D-06) are not the admission bound — admission uses the profile's tokenizer identity where available, otherwise the estimate plus its declared margin, and records exact-vs-estimated per request; effective/opaque continuation history counts or, if its size is unknown, forces a fresh lineage or a capacity result (never a silent fit); provider rejection is recorded as an estimation miss and the margin adjusted; multi-result turns re-estimated; capacity ⇒ pressure rebuild (P2.5.2) or explicit capacity condition, never a malformed history (I-17).
- Done: fake adapter with a small context limit triggers rebuild, never an oversize request; adversarial text and unknown continuation history are never marked exact-fit (IX-17).

### P2.4 Carry-forward and cross-cell coherence
#### P2.4.1 [M] `CarryForward` · TODO
- Why: [§6.2](docs/context/continuity.md#sec-6-2) table.
- Pkg: `context`
- Build: register validated (v facts re-checked against store + file versions; stale tagged); seeds = Workset entries referenced by the next step's plan text, `Focus` or `Next`, re-served at current versions ≤ 4K (changed ⇒ NOT SEEN announced); dead ends/open/decisions verbatim into `[K]`; last receipt per check with validity; touched ledger compressed (paths + versions); pinned messages + packet always; probe findings as pointers (P4.4.2). Transcript, model prose, raw logs never carried.
- Done: FX-11 (amendments and scoped dead ends survive a rollover; KNOWN = seeds only, declared).

#### P2.4.2 [M] Cross-cell fact coherence and bounded retention · TODO
- Why: [§6.4](docs/context/continuity.md#sec-6-4); F08 (bounded register vs durable evidence); FX-57.
- Build: at compile: every `v` fact's evidence id must resolve; moved anchor ⇒ `v(stale @old)`; stale for two consecutive cells and unreferenced ⇒ moved to the STATUS note and dropped from the projection; `x` facts durable until campaign end (PIT candidates in P4.2.2); inactive records archived verbatim with resolvable ids; required carry-forward that cannot fit ⇒ explicit capacity response, not deletion.
- Done: FX-20 (race-report evidence reachable via Dead ends/STATUS after repeated rebuilds), FX-57.

#### P2.4.3 [M] `STATUS` note (campaign checkpoint) · TODO
- Why: [§4.5 note kinds](docs/knowledge/records.md#sec-4-5) `STATUS` (same task only); written at `role_switch` and `cell_end`.
- Deps: P2.6.1
- Build: harness-origin `STATUS-<work>` note revision per boundary, written through the single serialized `KbWriter` (P2.6.1; `admitted_by: harness`, lint-exempt, D-36): archived register records, verification status summary, open handles; exported to `kb/notes/STATUS-W-….md`; never injected into other tasks.
- Done: resume reads STATUS for archived facts; note revisions versioned in SQLite.

#### P2.4.4 [M] Workset export/seed round trip and cross-cell recall · TODO
- Build: `Workset.export()` at cell end → `workset_exports`; `Seeds.from(export, nextStep)`; store is campaign-scoped and `#n` aliases are campaign-global (D-46), so `recall #17` and a carried `v … [#17]` fact resolve to the same evidence in every later cell, child and resumed attempt; stub index of ids referenced by facts rendered on request only.
- Done: two sequential cells and parallel children keep unambiguous earlier references across carry-forward, crash/resume, recall and guarded revert (IX-06).
- Done: seed rendered ⇒ displayed at its hash; recall across cells labelled `historical` when changed.

### P2.5 The one rebuild mechanism (five uses)
#### P2.5.1 [M] `Rebuild(reason)` · TODO
- Why: [§5.8](docs/runtime/residency-rebuild.md#sec-5-8) N2; F03 (replace the whole projection; preserve protocol units; never reuse a continuation that restores evicted bodies).
- Pkg: `context`
- Deps: P2.3.1, P2.4.*, P1.8.6
- Build: `sealed RebuildReason { Pressure, Resume, RoleSwitch(role), AlternativeAttempt(profile?), CellEnd(next: NextIncrement|Continuation) }`; `Rebuild.run(reason, ctx)`: checkpoint (STATE, Workset export, receipts, journal; STATUS note for RoleSwitch/CellEnd; new attempt id for AlternativeAttempt via controller); `[S][R]` reused iff input versions match, else recompiled; `[K]` = `compile(increment', seeds, carryForward)`; `[T]` = applicable pinned messages + packet + `note("rebuilt: <reason>")` + last `m = 6` complete protocol turns with stubs (`m = 0` for RoleSwitch/AlternativeAttempt/CellEnd; no proposer transcript in a review context); `[A]` = digest + validated STATE (stale tagged; Dead ends emphasised for alternative) + `KNOWN = seeds only`; projection generation += 1; old projection checkpointed before installation; **no model summarization**; provider continuation id never reused across a role switch or when the implicit history would restore evicted bodies/replaced `[K]`/earlier anchors (fresh provider lineage; complete tool-call/result units and required opaque items preserved).
- Done: all five reasons exercised in tests; FX-56; generation increments visible on records.

#### P2.5.2 [M] Pressure use and decomposition-failure accounting · TODO
- Why: [§5.6 pressure gate](docs/runtime/gates-termination.md#sec-5-6), [§3.8](docs/architecture/components.md#sec-3-8).
- Build: `α = 0.65` of `C_profile` (gauge every result); first pressure ⇒ fold into register + `Rebuild(Pressure)`; second pressure rebuild in the same cell ⇒ terminate `partial` with a `replan` hint; both counted in `Sizing.rebuilds` and fed to the calibration prior.
- Done: FX-11 rollover path through pressure; second rebuild terminates honestly.

#### P2.5.3 [M] Projection validation after rebuild · TODO
- Why: [§13.2 "lost constraint or evidence after a rebuild"](docs/operations/recovery.md#sec-13-2); FX-19.
- Build: compare mandatory coverage before/after installation (constraints, acceptance ids, amendments, CON refs); on loss restore the previous projection and rehydrate authority before any action.
- Done: FX-19.

### P2.6 Knowledge base read path, STATUS/CAL (deterministic)
#### P2.6.1 [C][M] `Note` model, `Kb` store, index generation, Markdown export · TODO
- Why: [§4.5](docs/knowledge/records.md#sec-4-5) three layers (index → notes → raw); SQLite canonical, Markdown derived (F08); [§4.4 project horizon](docs/state/evidence-coherence.md#sec-4-4).
- Pkg: `kb`
- Build: `NoteKind { ADR, CON, LES, PIT, BMAP, NEG, SKILL, STATUS, CAL }`, `Note` front matter fields (`id, kind, status: candidate|admitted|stale|superseded|deprecated|rejected, summary ≤ 200 chars, body ≤ 120 tokens (no code bodies), scope, anchors[{path, version, symbol}], confidence, basis{requirementRefs, evidenceRefs}, validity{dependsOn, lastValidated, invalidationTrigger}, supersedes, signedBy, origin{work, cell, extractor, admittedBy}, usage{injected, cited, lastCited}`), linked versioned modules for BMAP/SKILL detail, `Kb` store (notes + revisions; `raw/<work>/<cell>/…` traces/packets/manifests append-only), `KbWriter` (the one serialized write path; owned by the `Curator` from P4.1.1; harness-origin STATUS notes use it with `admitted_by: harness`, D-36), `Index.regenerate()` deterministic (`index/global.md ≤ 1.5K`, `index/contracts.md` every active CON, `index/subsystem-<s>.md ≤ 1K`), `Export.markdown(note)` (D-24) as immutable exported views.
- Done: regenerate is idempotent; exports never read back as authority.

#### P2.6.2 [M] `kb.search` / `kb.get` read path · TODO
- Why: [§5.4 kb](docs/runtime/tools.md#sec-5-4); FX-46 (optional services unavailable ⇒ direct-source work continues).
- Build: FTS5 over summaries + anchors (`note_queue` excluded); anchors resolved against the current tree ⇒ `stale` labels; scope/kind filters; `complete` per scope; `why` logged; dense retrieval seam only in P5.5.
- Done: empty/cold index degrades with a reported degradation line, never blocks a cell.

#### P2.6.3 [I] Project-horizon coherence · TODO
- Build: `Coherence` hook: note anchors or `validity.depends_on` changed ⇒ `stale` (excluded from injection, labelled on explicit search, curator recheck in P4.1.2).
- Done: FX-35 pre-condition (invalidation marks) verified; injection exclusion tested in P4.

#### P2.6.4 [M] `Calibration` statistics (harness-rendered prior) · TODO
- Why: [§6.7](docs/context/continuity.md#sec-6-7) N5; [§18.2](docs/implementation/roadmap.md#sec-18-2): earlier stages collect CAL statistics deterministically, admission/injection waits for Stage D; the KB `CAL` note path arrives with the extractor (P4.2.1) — D-42.
- Build: post-campaign deterministic aggregation of `Sizing` → `CalibrationStats` (median turns per increment; overrun rate by `expected_files` band and subsystem; touched/expected ratio) persisted in `state.sqlite`; rendered as a harness-owned ≤ 150-token block in the plan cell's `[K]` (data, never instruction; same status as the pre-existing ledger); the controller uses the same statistics for `turns_per_cell` and emits a `Warning` when a proposed increment's band has > 50 % overrun. `[O gate: ablation §19.5 calibration prior on/off]` for the plan-cell block; statistics collection always on.
- Done: deterministic aggregation test; warning emitted; block absent when no history exists.

### P2.7 Stage B validation
#### P2.7.1 [V] Fixtures and crash intervals · TODO
- Build: FX-11, 19, 20, 22, 23, 35 (invalidation marks), 42, 45, 46, 49 (S1 parameterization: cancellation, lease, reservation, reconciliation, accounting paths), 51 (two contexts, same path + hash ⇒ context-local displayed ranges), 56, 57; IX-03, IX-06, IX-11, IX-17; crash during a command / after a mutation before its receipt / during a rebuild / after an external effect with lost acknowledgement.
- Done: all green on both platforms; a forced boundary and a crash both resume with exact constraints, open failures and recoverable evidence.

#### P2.7.2 [V] Scripted long refactor under context pressure · TODO
- Build: multi-increment scripted campaign with a small fake context limit: increment boundaries, seeds, carry-forward, STATUS notes, no dropped requirement; continuation cells do not redo verified increments.
- Done: manifests show boundary reasons; no invariant metric non-zero.

#### P2.7.3 [V] Economics report from manifests · TODO
- Build: per-campaign export of boundary cost share, continuations per increment, rebuilds per cell, `[A]` share, cache classes from the fake adapter; break-even estimate per [§16.2](docs/economics/costs.md#sec-16-2) as a diagnostic.
- Done: report generated; the B2 ≥ B1 gate is recorded as **deferred to live evaluation** (D-28).

---

## P3 Stage C — Verification depth and refactor mode
Goal: [§18.2 Stage C](docs/implementation/roadmap.md#sec-18-2): scheduler with closures and reuse proofs, reserve (done), impact engine and nudge, blast radius, transform path with inventory, test-integrity and scope guards, refactor mode, flaky policy, boundary pre-compilation. Required refactor review uses the human path until P4 (D-23).
**Fixtures:** FX-07 (full closure invalidation), 12, 13, 14, 16, 17, 37, 40, 44, 52, 54; IX-03 (Stage C migration with human review), IX-04 (isolated candidate), IX-23 (unknown impact) · **Unsupported until later:** review cells (P4), CON notes from the curator (P4.1; P3 reads whatever CON notes exist), tier-1/2 index (P5.4), async checkers (P5.7), a real jail for transforms (P7 confined backends; until then D-41 applies and the residual risk is recorded in transform receipts).

### P3.1 Scheduler (full)
#### P3.1.1 [M] Check definitions with closures and manifests · TODO
- Why: [§8.1](docs/verification/scheduler.md#sec-8-1) `input_closure`, `definition_version`, `closure_manifest`, cost classes, triggers.
- Pkg: `verify`
- Build: `Closure { Known(paths), Package(p), Unknown }` + `ClosureManifest(pathsAtVersions, directoryMembership, configAndLockfiles, fixtures, completeness, exclusions)`; triggers `every_edit | end_of_turn | step_boundary | risk>θ | increment_end | campaign_end | on_demand`; cost classes `inline | fast | slow | expensive`; registry rows `CHK-types-touched, CHK-tests-blast, CHK-accept-<AC>, CHK-full, CHK-review-inc, CHK-review-campaign, CHK-lint, CHK-quality-gate`.
- Done: closure computed for blast-selected checks from the impact engine (P3.2); package/glob closures include membership.

#### P3.1.2 [M] Validity, applicability, reuse proofs · TODO
- Why: [§8.1 validity](docs/verification/scheduler.md#sec-8-1), [§8.4](docs/verification/scheduler.md#sec-8-4); F04 (intersection, not subset); D5 in [§20.2](docs/reference/decisions.md#sec-20-2).
- Build: `Applicability.of(receipt, now) → current | stale | unknown` computed, never stored on the receipt: stale when changed paths ∩ closure ≠ ∅ (FX-54) or closure `Unknown` and anything moved; `ReuseProof(reuseOf, closureUnchanged: pathsAtHashes)` attached only when closure complete and unchanged **and** check definition, argv/cwd/selector, verifier/parser version, environment and external fixtures unchanged; unknown closure ⇒ rerun at the conservative containing scope; historical outcome immutable.
- Done: FX-16, FX-54; a path list alone never proves completeness (test with an added test file invalidating a package closure).

#### P3.1.3 [M] Verify-on-stop · TODO
- Build: on a completion proposal run only missing or stale checks; reuse valid receipts with recorded proofs; never a blind full suite.
- Done: second completion proposal after an unrelated edit reuses receipts (test).

#### P3.1.4 [M] Layer wiring and additional runners · TODO
- Why: [§8.1 layer table](docs/verification/scheduler.md#sec-8-1), [§8.2 ladder](docs/verification/scheduler.md#sec-8-2) L0–L2 (L3–L5 arrive P4/P5).
- Build: blast-radius tests ∪ step `accept:` on `[>]` move / `risk > θ` / fused `run(if: applied)` / `verify(tests(blast))`; increment acceptance at increment end; full suite + quality gates every `K` and at campaign end; integration re-verification hook for P5.1.3; remaining shapers (cargo, go test, dotnet, mocha, unittest, ruff, eslint, mypy, pyright, tsc, `cargo check`, `go vet`).
- Done: triggers fire per the table in a scripted campaign; `not_tested` recorded in packets.

#### P3.1.5 [M] No-concurrent-writer boundary during checks · TODO
- Why: [§8.4](docs/verification/scheduler.md#sec-8-4) equal before/after hashes cannot exclude a mid-check mutation; FX-17.
- Build: `input_stability` per D-45: `exclusive` for `inline|fast` checks (workspace mutation lock held; post-check rescan of `tested_inputs`); `slow|expensive` checks run on an exported isolated candidate (`candidates/`, materialized from the snapshot and verified against its manifest before and after the check — `isolated`); anything else is `unknown` and **ineligible** as a required acceptance receipt (not merely a `limits` note); mutable external services listed as limitations.
- Done: FX-17; mutation inside an isolated candidate during the check cannot certify the final bytes; allowed scratch output does not invalidate a complete declared closure (IX-04).

#### P3.1.6 [M] `unavailable` receipts and blocked path · TODO
- Build: required check cannot run (missing runner/toolchain) ⇒ `unavailable` receipt + `blocked` report with the concrete blocker; no endless gating.
- Done: FX-13 (full).

### P3.2 Impact engine and index (tier 0/1 heuristics)
#### P3.2.1 [M] `ImportGraph` and `tests_for` · TODO
- Why: [§7.3](docs/repository/navigation.md#sec-7-3); heuristic where runtime uses reflection/schemas/generated code.
- Pkg: `atlas`
- Build: per-language import extraction for the D-09 set (+ Gradle/Maven module deps); `complete` flag per file (dynamic imports/plugins/generated clients ⇒ false); `tests_for` edges by naming conventions + atlas rows; per-package resolution in monorepos.
- Done: FX-37 (runtime-only dependency ⇒ `complete:false` visible, package-suite fallback).

#### P3.2.2 [M] `Impact.analyze(E)` · TODO
- Why: [§7.4](docs/repository/navigation.md#sec-7-4) one analysis, four consumers; F04 (intersection).
- Build: `Impact(fanin(sym) with tier/complete, importersClosure(E) (BFS), affectedTests(E) = tests in closure ∪ tests naming E's modules/symbols ∪ acceptance `run:` items whose closure ∩ closure(E) ≠ ∅ (unknown closures widen), contractsTouched(E) = CON/ADR notes with anchors ∩ E, risk(E) = Σ_hunks Δlines·(1 + log2(1 + fanin(enclosing symbol))), θ = 40)`; consumers: verification depth (slow checks early when `risk > θ`), routing risk floor (P4.5.1), shape/human anchors (contract touch ⇒ S2 + ADR in main line; interface change never auto-merged/never in an S3 child), `look(impact)`.
- Done: worked example from §7.4 reproduced; FX-54 via `affectedTests`.

#### P3.2.3 [M] `look(refs | importers | impact)` · TODO
- Build: unmask the three ops; every result carries `tier` and `complete`; dynamic dispatch reported unresolved, never guessed; scoped by package first ([§7.7](docs/repository/navigation.md#sec-7-7)).
- Done: results flagged incomplete at tier 0; no invented references.

#### P3.2.4 [M] Impact nudge and exit-gate binding · TODO
- Why: [§7.4 impact nudge](docs/repository/navigation.md#sec-7-4), [§5.6 impact gate](docs/runtime/gates-termination.md#sec-5-6); F3.
- Build: after each edit batch diff outlines of touched files; changed definition (signature/visibility/export) of a symbol with `fanin > 0` whose references were not inspected since the change ⇒ one `[A]` line; fallback without an index = repository-wide literal count excluding the edited file; resolution tracked (`look(refs)` on the symbol or plan rescoping) and required by the exit gate for changed public definitions.
- Done: nudge fires once per changed symbol; exit gate refuses with an unresolved nudge.

#### P3.2.5 [M] Blast radius selection · TODO
- Build: `blast(E) = closure(importers(E)) ∪ E`; tests = `tests_for(blast(E))`; incomplete graph ⇒ widen to the package suite and say so in the verify line; `verify(tests(blast))` unmasked.
- Done: verify line names the scope (`blast 14` / `package pay`).

#### P3.2.6 [I] Impact pre-scan at campaign open · TODO
- Build: `Controller.open` runs `Impact` over the request's candidate paths (D-40: paths named in the request, lexical hits of request identifiers, atlas hubs; logged as inputs) and records the pre-scan as **incomplete discovery** — coverage, unresolved dependencies and explicit risk; zero hits never means no contract impact; the scan is refreshed when actual touched symbols/paths become known and the controller stops or upgrades before a now-disallowed action (I-23) ⇒ `impact.contract_touch`, fan-in estimate ⇒ `ShapeSelector` risk inputs and `contracts_touched` in the contract.
- Done: shape decisions logged with pre-scan inputs.

### P3.3 Scripted transform path
#### P3.3.1 [M] `edit(transform)` with diff receipt · TODO
- Why: [§9.2](docs/runtime/workspace-editing.md#sec-9-2); F05 corrections (preimages first; discard or guarded inverse; honest partial state).
- Pkg: `tool.edit`
- Deps: P1.6.4, P1.6.5, P3.1.1, P3.2.5
- Build: `Transform(script|argv, scopeGlob, inventory?, expectedMatches?{min,max}, preconditions?, why)`: resolve allowed target inventory/preconditions/expected-count policy before dispatch (omitted ⇒ `unspecified`, never `inventory_ok: true`); record preimages before mutation; run the script through the `Runner` (jail = confined runner when available; else trusted-local **only when that mode is authorized**, with post-hoc stamp diff and label — a diff is not a jail; a confinement requirement returns `unsupported`, D-41); harness computes the diff, snapshots the shadow ref, inline syntax on every changed file, refreshes atlas rows; `TransformReceipt(filesChanged, hunks, perFile(+n −m, versionAfter), diff blob, syntax, touchedOutsideScope, inventoryOk, matchCount, representativeSites ×3, unusualSites ×3)`; changed files enter the Workset as `touched-by-transform (NOT SEEN)`; scheduler treats all changed files as touched ⇒ blast-radius checks mandatory before increment close; review receives the diff id (P4.4.3).
- Done: 40-file rename fixture produces one receipt; anchored edit on a transformed file requires a fresh read.

#### P3.3.2 [M] Out-of-scope and count-failure handling · TODO
- Build: reject the transform when `touched_outside_scope ≠ ∅` or the match count misses `expected_matches`: discard the isolated candidate when one was used; else guarded inverse only where current bytes still match the transform's own postimages; report `restored | partial | unknown_outcome` with actual effects; never promise unit rollback or undo of external effects.
- Done: FX-40.

### P3.4 Scope guard and test-integrity guard
#### P3.4.1 [M] `ScopeGuard` · TODO
- Why: [§8.6 scope guard](docs/verification/acceptance-review.md#sec-8-6); F01 (pending proposals grant nothing).
- Pkg: `verify`
- Build: classify each edit's diff against `increment.write_scope` and `contract.scope`: outside increment but inside contract ⇒ warn once, then `task.propose(increment_split)` or justification in `why`; outside contract ⇒ rejected until an amendment is approved **and committed**; revalidate against the committed contract version and the executor ceiling before dispatch; protected paths are D-class.
- Done: FX-52 (pending amendment followed by an out-of-scope call ⇒ no dispatch).

#### P3.4.2 [M] `TestIntegrity` classifier and acceptance-surface line · TODO
- Why: [§8.6 test-integrity guard](docs/verification/acceptance-review.md#sec-8-6) N6; F17; D10 in [§20.2](docs/reference/decisions.md#sec-20-2).
- Build: deterministic per-language classifier (D-09) over the diff: (a) deleted/renamed test functions or files, (b) weakened assertions (`assert x == y` → `assert x`, widened tolerances), (c) skip/xfail/only markers and `.skip` calls, (d) snapshot/golden updates, (e) CI/config changes altering which checks run (`pytest.ini`, `jest.config.*`, `conftest.py`, build test config), (f) edits to acceptance commands; each detection ⇒ `TestIntegrityFlag(path, kind, originalObligation)` rendered as the acceptance-surface line in the edit result and collected into the finish receipt (`acceptance_surface_modified` with the worker's recorded reason); a weakening of an existing required check forces review (human P3.5.2 / cell P4.4.3) with the original obligation attached; heuristic and labelled as such.
- Done: FX-14; flags must be justified in the Result Packet for the exit gate.

#### P3.4.3 [I] Gate registrations · TODO
- Build: register scope, acceptance-surface, contract-touch (needs CON anchors; active once any CON note exists) and repeated-failure-signature (fingerprints P4.6.2) gates with `Gates` (P1.8.5).
- Done: gate table of [§5.6](docs/runtime/gates-termination.md#sec-5-6) fully populated except judge-dependent ones.

### P3.5 Refactor mode
#### P3.5.1 [M] Activation, behaviour snapshot, `red_ok_until` · TODO
- Why: [§8.9](docs/verification/refactoring.md#sec-8-9); [§1.2 broad refactor class](docs/architecture/principles.md#sec-1-2).
- Pkg: `verify`
- Build: `RefactorMode.detect(contract)` (explicit contract flag or behaviour-preserving requirement keywords: refactor/extract/rename/migrate API) ⇒ plan cell records the §8.9 checklist (behaviour to preserve, interfaces to change, compatibility duration, callers/consumers, data/config dependencies, independent acceptance checks; shared decision separated from mechanical edits); behaviour snapshot = baseline receipt over affected suites + characterization outputs (CLI goldens, API fixtures) as blobs at `s0`; `red_ok_until: increment_end` in gates: inline syntax, checker deltas and impact nudge still run; the step-boundary gate waits for the declared coherent boundary (signature + implementation + callers); no forced per-file rollback; final gate enforced.
- Done: FX-12.

#### P3.5.2 [M] Contract-first interfaces, equivalence evidence, mandatory campaign review · TODO
- Why: [§8.9 items 3–6](docs/verification/refactoring.md#sec-8-9); D-23.
- Build: an increment changing a cross-boundary interface must reference a `CON` note (new or superseded) in `[K]` (validated when the KB has CON notes; otherwise recorded as a planning gap); equivalence evidence compares test identities, outcomes and boundary/compatibility cases of preserved tests and goldens at `s_n` vs `s0` (equal counts insufficient; new behaviour ⇒ new requirement); campaign-scope review mandatory: `Authority.review(ReviewRequest(full diff, contract, receipts, rubric))` human path; unavailable ⇒ `blocked`, never skipped; `verify(review)` unmasked in this human form.
- Done: refactor campaign cannot be accepted without a signed review; equivalence report attached to the finish receipt.

### P3.6 Flaky policy, cadence, quality gates
#### P3.6.1 [M] `Flaky` policy · TODO
- Why: [§8.10](docs/verification/refactoring.md#sec-8-10).
- Build: one isolated rerun of a failed check; two disagreeing outcomes ⇒ `inconclusive` + `Open` item; all attempts preserved as receipts; nothing reruns until a favourable result appears; "pre-existing" still requires the baseline receipt.
- Done: fixture with a nondeterministic test yields `inconclusive`, never `passed`.

#### P3.6.2 [M] Full-suite cadence and quality gates · TODO
- Build: `K = 5` verified increments + campaign end (P2.2.6 wiring); `CHK-quality-gate` = configured complexity/duplication threshold commands (`[O]` until a project configures them; never invented).
- Done: cadence test over a scripted 7-increment campaign.

### P3.7 Boundary pre-compilation `[O gate: ablation §19.5 pre-compilation on/off; miss rate vs latency saved]`
#### P3.7.1 [O][M] `Precompile` · TODO
- Why: [§6.6](docs/context/continuity.md#sec-6-6) N4; F06 (full input fingerprint; no provider cache population locally).
- Pkg: `context`
- Build: after the cell's last mutation when only `slow|expensive` checks remain, build the next increment's `[K]` locally; `Fingerprint(candidate stamp, contract/authority revision, selected increment, carry-forward/register version, note/contract/skill/index versions, role, role-text version (D-38), profile, frozen policy)`; on cell close reuse iff fingerprint and required coverage still match, else discard and recompile; only for `cell_end(next_increment)`, never continuation of a red increment; provider pre-warm requests are a separate, budgeted, off-by-default option (not implemented here); hit/miss + p50/p95 boundary latency in telemetry.
- Done: FX-44; no stale seed ever served (invariant test).

### P3.8 Stage C validation
#### P3.8.1 [V] Fixtures · TODO
- Build: FX-07 (full), 12, 13, 14, 16, 17, 37, 40, 44, 52, 54.
- Done: green on both platforms.

#### P3.8.2 [V] Scripted migration and 40-file rename · TODO
- Build: cross-file migration campaign completes with fewer redundant checks (reuse proofs visible in receipts) and no lost requirement; 40-file rename via transform is reconciled, reviewed (human path), reversible (`revert:turn:N`), and produces no false green.
- Done: both scripted campaigns pass; invariant metrics zero.

---

## P4 Stage D — Knowledge and delegation (S2)
Goal: [§18.2 Stage D](docs/implementation/roadmap.md#sec-18-2): KB with queue/curator/invalidation/injection, extraction, BMAP, skills, probe cells, review cells at both scopes with the judge protocol, function routing with refusal, escalation ladder, capsule repair, alternative attempts, MCP mounts (contract).
**Fixtures:** FX-13 (with review), 26 (probe/review cancellation), 29, 30, 31, 32, 33, 34, 35, 36, 39, 41, 45, 49 (S2), 53, 55; IX-11 (judge sees obligations), IX-23 (floors and demotion), IX-24 · **Unsupported until later:** S3 writers/integrator (P5), MCP transport (P7), live judge calibration on real models (P7), warm-vs-cold KB gate (P7).

### P4.1 Knowledge base (full)
#### P4.1.1 [M] `Queue`, `Curator`, admission policy · TODO
- Why: [§4.5 operations and admission](docs/knowledge/records.md#sec-4-5); F18; D7 in [§20.2](docs/reference/decisions.md#sec-20-2).
- Pkg: `kb`
- Build: `Queue.enqueue(candidate)`; `Curator.admit` (lint: dedupe by summary similarity, evidence present and resolvable, scope bounded, no contradiction with admitted notes, secrets redacted, not a one-off generalization), `supersede`, `deprecate` (never rewrite a body in place); writes serialized; versioned admission batches with independent rollback; policy: interactive ⇒ queue for the user (`Authority.resolve`), autonomous ⇒ auto-admit only lint-passing, anchored, subsystem/task-family-scoped factual `LES` and conditional `PIT` at `confidence ≤ 0.6`, `admitted_by: policy`; everything else waits; ADR candidates are never auto-admitted — `signed_by` is set only through `Authority.resolve` (interactive), autonomous mode keeps them queued ([§12.2](docs/knowledge/learning.md#sec-12-2) ADR sign-off).
- Done: FX-36 (one failed use stays a scoped PIT candidate); contradiction lint test; rollback of a batch leaves code untouched.

#### P4.1.2 [M] Invalidation, usage tracking, pruning, promotion, index regeneration · TODO
- Build: dependency-driven `invalidate` via the project-horizon hook (P2.6.3) with curator recheck; `usage{injected, cited, last_cited}` updated from register citations; `prune` (injected repeatedly, never cited ⇒ decay); `promote` (recurring `LES`/`PIT` ⇒ a proposed *task* for a test/linter/schema check; note becomes a pointer; never auto-committed); `regenerate-index` after every admission batch; health telemetry (candidate count, admission rate, injection count, cited rate, harmful/stale injections, repeated mistakes, index freshness, locator validity).
- Done: FX-35 (note referencing a superseded contract flagged before injection).

#### P4.1.3 [M] `Injection` ranking, focus notes, retrieval-miss logging · TODO
- Why: [§6.3](docs/context/continuity.md#sec-6-3); F23 (CON always compiled in).
- Deps: P2.3.1 (`kb_slice` hook)
- Build: `score = w_scope·match(scope, write_scope) + w_dep·overlap(depends_on, contracts in play) + w_fresh·freshness + w_use·use_value + w_evid·evidence_quality − w_len·tokens` (default weights and threshold D-37 with bounded feature definitions declared before use; staleness is an eligibility check *before* ranking; tunable from held-out usefulness data); cap 8 notes / 1.5K; `CON` for touched paths bypass the cap; nothing when nothing is strongly relevant; never re-inject unchanged advice within a cell; focus notes ≤ 300 tokens per turn anchored in `Focus` or files touched this turn, each once per cell, rendered in `[A]` (P1.8.3 slot); `(injected, cited-in-register?, outcome)` logged per note; `retrieval_miss` ops and `Open (needs: …)` lines logged as labelled negatives; `kb.propose` unmasked; the `CAL` note (P4.2.1) reaches the plan cell through this path and replaces the P2 statistics block (D-42).
- Done: FX-29 (CON compiled in; impact flags the contract touch); ranking deterministic for equal inputs; `[O gate: ablation KB injection off/frozen/live]` flag for the live path.

### P4.2 Extractor
#### P4.2.1 [M] Post-cell `Extractor` · TODO
- Why: [§12.1](docs/knowledge/learning.md#sec-12-1), [§3.4 extractor row](docs/architecture/roles-shapes.md#sec-3-4); low tier, from the archived trace, never in the busy cell.
- Pkg: `kb`
- Deps: P4.5.1 (helper profile), P4.4.1 (packet dispatch)
- Build: inputs = final STATE, journal digest, diff summary, receipts, Result Packet; outputs = candidates `LES | PIT | BMAP-delta | NEG | SKILL-delta | CAL-delta` with evidence refs, anchors, scope, in the diagnosis shape `symptom | conditions | attempted | observed | reason | evidence | source revision | invalidation`; enqueued (P4.1.1); `CAL-delta` aggregates `CalibrationStats` (P2.6.4) into the `CAL-<repo>` note per [§6.7](docs/context/continuity.md#sec-6-7) (D-42); S0 runs extraction at finish; cost charged to the originating work.
- Done: scripted extractor produces lint-passing candidates on the vertical-slice trace; never blocks the campaign loop.

#### P4.2.2 [M] Typed negative evidence and derived candidates · TODO
- Why: [§12.2](docs/knowledge/learning.md#sec-12-2) NEG states; [§8.8 findings](docs/verification/acceptance-review.md#sec-8-8).
- Build: `NEG` states `unknown · unsearched · searched-empty(scope, version, index coverage) · contradicted · verified-absent(bounded domain)`; `x` facts ⇒ `PIT` candidates at campaign end; review findings ≥ major ⇒ `Open` items in the next register + `PIT` candidates; recurring repair diagnoses ⇒ `PIT` candidates.
- Done: a later cell reading a `NEG` note never sees "not found" as "absent" (render test).

### P4.3 Skills and behaviour maps
#### P4.3.1 [M] `Skill` notes and module filtering · TODO
- Why: [§12.2 skills](docs/knowledge/learning.md#sec-12-2); [§6.1 mandatory skill modules](docs/context/compiler.md#sec-6-1); F06 (mandatory invariants survive filters).
- Pkg: `kb`
- Build: `Skill(trigger, prerequisites, steps, expectedArtifacts, verification, failureExit, freshness, tokenBudget, modules[{appliesTo, mandatory, body}])`; filtering at module granularity; mandatory modules, prerequisites and invariants survive every filter; per-`(skill version, role)` rendered views cached; trigger evaluation at meaningful state changes only; overlapping skills resolve conflicts explicitly against the task's authority; a skill never grants authority or marks a requirement complete; `kb.skill(id)`; `Compiler` mandatory set includes mandatory modules (charged to the total budget).
- Done: migration-skill counterexample test (mandatory module retained under an aggressive filter).

#### P4.3.2 [M] `BMAP` notes and `look(bmap)` · TODO
- Why: [§7.5](docs/repository/navigation.md#sec-7-5) `[HYPOTHESIS]`; `[O gate: ablation behaviour maps on/off; measured on navigation + upkeep cost]`.
- Build: `BMAP-<subsystem>`: behaviour → entry points → implementation → state read/written → important callers → tests → locators `path::symbol@hash`; generated from the index where possible, from validated worker observations otherwise (extractor `BMAP-delta`); locators validated at compile time (`unresolved` when the symbol moved); progressive disclosure subsystem → behaviour → symbol → source; `look(bmap, subsystem)` ≈ 200–400 tokens; `[R]` excerpt ≤ 300 for the focus subsystem.
- Done: stale locators shown `unresolved`; maps never replace current source (an edit still needs a current read).

### P4.4 Delegation
#### P4.4.1 [C][M] Packets and `Delegator` · TODO
- Why: [§10.1](docs/operations/delegation.md#sec-10-1); F09 (dispatch base, dependencies, contract revision, execution generation in packets); F13.
- Pkg: `delegate`
- Build: `TaskPacket(ids, incrementId, role, applicableRequirements/constraints as exact excerpts + authority refs, contractVersion, dispatchCandidate: Stamp, workspace, requiredEvidence, uncertainties, readScope, writeScope, capabilityCeiling, reservedBudget, executionGeneration)`; `InvestigationPacket` (P4.4.2); `ResultPacket` reused with `base` and `read_versions`; `Delegator.dispatch(kind, packet, mode: sync|async) → Handle`, `collect(handle)`; limits: writers depth 1 / probes depth 2, max parallel cells 3, task-tree budget, leases with timeouts, cancellation checked before start and before publication; late results from superseded units rejected and their spend counted; children publish immutable observations even after cancellation but cannot integrate or accept under a superseded generation; children receive packets with exact excerpts, never the parent's full transcript (D13 in [§20.2](docs/reference/decisions.md#sec-20-2)); a child's interface assumptions are dependencies even outside its write scope. `task.delegate/collect` unmasked per role/shape (`propose` exists since P2.1.5).
- Done: FX-26 (probe/review form), FX-41 pre-condition (findings carry versions).

#### P4.4.2 [M] `Probe` cell · TODO
- Why: [§10.2](docs/operations/delegation.md#sec-10-2); [§7.6](docs/repository/navigation.md#sec-7-6) as a probe.
- Build: `Roles.probe` (fresh read-only cell; `look`, `kb.search`, `run` R-class only, own STATE, `task.ask` to parent; medium tier; budget 15 turns / 40K); packet out `findings[]{claim, kind: observed|inferred, evidence: path:range@hash | #id}`, `searched{scopes, complete, indexCoverage}`, `unresolved[]`, `cost`; declared packet validator; parent gets a ≤ 400-token summary in `[T]`; findings are pointers the parent must `look` (dedup makes it cheap); candidates for `NEG`/`BMAP`; parallel probes read-isolated (S2 allowed); plan cell may delegate probes (unmask).
- Done: FX-41 (findings for changed ranges marked stale; parent re-looks).

#### P4.4.3 [M] `ReviewCell` and judge protocol (two scopes) · TODO
- Why: [§8.8](docs/verification/acceptance-review.md#sec-8-8) N8; F25; F01 (required review approval before closure; assessments bind versions).
- Deps: P4.5.1 (review tier), P3.4.2 (original obligations), P3.3.1 (transform diff ids)
- Build: `Roles.review` (fresh context, no proposer transcript; tools `look` read-only, `kb.search`, `verify(tests)` on an **isolated copy** of the candidate tree from `candidates/`; budgets ≤ 10 `look` / 30K increment, 60K campaign); `EvidencePacket(contractSlice with complete acceptance definitions, origins and obligation versions (D-52), diff, receipts with parsed counts, CON/ADR notes touching the paths, testIntegrityFlags with original obligations beside the diff, preexistingLedger, coverageReport, rubric)`; `Verdict(approve|revise|reject|insufficient_evidence|escalate, findings[{severity, location path:line@hash, issue, suggestedFix, kind}], coverage{filesReviewed, ranges, unread} from telemetry, contractViolations, confidence)`; increment-scope triggers (risk floor, contract/ADR touch, cheap-tier output, test-integrity flag, `review:` item) and campaign-scope predicate (P2.2.6); rules: acceptance criteria first, executable checks outrank opinion, `insufficient_evidence` with a named criterion is correct, competing proposals presented symmetrically in randomized order with equal budgets, ties/`escalate` ⇒ higher tier or human; review can never override a failed required check; findings ≥ major ⇒ `Open` + `PIT`; assessments bind contract version, reviewed candidate/patch, criterion and evidence versions; changed dependencies invalidate approval (freshness `unknown` if unassessed); `scheduler.obtain_required_review_once` reuses a current approval; reviewer never recursively demands a review of its own verdict; `verify(review)` ⇒ review cell (human `Authority.review` remains the fallback).
- Done: FX-30, FX-53, FX-13 (review path), injected-defect fixtures caught (P4.8.2).

#### P4.4.4 [C] `QaCell` contract (L3) · TODO
- Why: [§10.3](docs/operations/delegation.md#sec-10-3), [§8.2 L3](docs/verification/scheduler.md#sec-8-2).
- Build: packet in (contract, behaviour under test, entry points) / out (receipts, cases, screenshots/logs as blobs); requires a disposable environment (`ConfinedRunner` or an isolated candidate with an explicit `trusted-local` label); implementation `[O]` in P5.3.
- Done: contract + validator only; masked until P5.3.

#### P4.4.5 [O] Worth test estimate · TODO
- Build: `delegated_cost` estimate (context duplication + child generation + tool work + parent interpretation + validation + integration + retries) recorded on `Delegation.Dispatched` as advisory data; never decides alone.
- Done: present in events/telemetry.

#### P4.4.6 [C] Role policy texts and packet validators (probe, review, QA, repair, extractor) · TODO
- Why: [§3.4](docs/architecture/roles-shapes.md#sec-3-4) persona text is at most a few operational lines; non-implementing roles use declared packet duties, never the implementing STATE gate ([kernel contract scope note](docs/reference/kernel-contract.md)); D-38.
- Deps: P4.4.2, P4.4.3, P4.4.4, P4.6.3, P4.2.1
- Build: versioned default texts in `Config` resources per role (source: the duties and output columns of §3.4); the declared packet validators bound to `Completion.assess`; per-role `[S]` assembly reusing the shared evidence lines, error policy and data rule; hosts may override texts; texts are part of the frozen attempt configuration.
- Done: every non-implementing role has a text and a validator; no role text grants authority or marks a requirement complete.

### P4.5 Routing
#### P4.5.1 [M] Tier table, function table, `Router.selectProfile` · TODO
- Why: [§11.1–11.2](docs/operations/routing.md#sec-11-1) N7; F24; F07 (re-apply both floors after calibration); D6 in [§20.2](docs/reference/decisions.md#sec-20-2).
- Pkg: `route`
- Build: `Tier { low, medium, high, extraHigh, deterministic }`, `TierTable` (versioned data with calibration date; profiles per tier), `FunctionTable` rows per §11.1 (default tier, effort per call class, escalate-when, never-below, if-unaffordable action); `Router.selectProfile(function, packet, impact, policy)`: `eligible` (capabilities, authorization, context fit, availability, user pins, calibrated quality floor) → `tier = max(function default, plan suggestion, risk_floor(risk, contracts_touched, fanin, reversibility))` (risk-floor table D-34 — declared `packet.risk` maps explicitly; contract touch and fan-in supplement it and cannot erase a high-risk obligation with undiscovered dependencies) → `adjust_with_calibration` (D-35: promotion only; automatic demotion **off** until comparable verified outcomes for the proposed cheaper tier exist from a frozen/shadow evaluation) → `tier = max(tier, never_below(function), risk_floor)` (FX-55) → `affordable` = conservative estimate ≤ remaining − reserves → empty ⇒ `REFUSE(narrow_unit | checkpoint | ask_for_changed_constraint)` (never clamp; FX-32) → argmin expected total cost incl. retries/reviews/integration; `(function, tier, effort, outcome)` quadruples logged (`CalibrationLog`); helper calls never inside a worker loop; provider reasoning artifacts opaque per provider; cross-provider handoff transfers explicit goals/decisions/evidence/open questions only.
- Done: FX-32, FX-55; controller uses the router (P2.2.2 hook) with fake profiles.

#### P4.5.2 [M] `Escalation` ladder and attempt allowance · TODO
- Why: [§11.3](docs/operations/routing.md#sec-11-3), [§13.3](docs/operations/recovery.md#sec-13-3); F07 (two substantive attempts per increment incl. the initial one).
- Build: on **verified** failure escalate to N+1 with evidence and a stated change (stronger model, probe evidence, narrower increment, different tool, alternative attempt); repeating under a new label is not recovery; `budget.attempts` (default 2) counts substantive attempts per increment; new attempt ids never replenish it; then `blocked`.
- Done: FX-31 (deceptively hard task ⇒ escalation or `task.ask`, never invented behaviour).

#### P4.5.3 [M] Cache-aware scheduling and shadow-routing seam · TODO
- Why: [§11.4](docs/operations/routing.md#sec-11-4).
- Build: consecutive cells with the same role + profile scheduled adjacently where latency allows (controller ordering hint); never retain irrelevant context to flatter the cache metric (FX-45); shadow-routing comparison runs offline only (`eval` hook, P6) — live tasks never fan out to several expensive profiles.
- Done: FX-45; ordering hint test.

### P4.6 Recovery ladder
#### P4.6.1 [M] Failure classes and `recover()` · TODO
- Why: [§13.1–13.2](docs/operations/recovery.md#sec-13-1); F15; invariant 6.
- Pkg: `recover`
- Build: `FailureClass` table rows (transport/rate limit → adapter (P7); invalid tool arguments → cell hint; stale anchor/region unseen → re-read + diff-since-expect; build/environment → repair helper; behavioural test failure → revise hypothesis; missing repository contract → complement retrieval/probe; repeated failed hypothesis → dead end + alternative; truncated model response → adapter continuation (never execute a partial call); unknown action outcome → reconcile; lost constraint after rebuild → restore projection; authorization denial → ask or blocked, never a bypass; budget exhaustion → `partial` with STATE as report; superseded unit → cancel, keep evidence, count spend), `Ladder.recover(failure)`: classify execution state and completed effects → reconcile if unknown → bounded deterministic retry (≤ 2 with backoff, classified-safe ops only) → one scoped repair within granted capability → return to the owning cell with evidence and reason; execution generation increments on reassignment/supersession; runner checks generation, lease, authority and atomic reservation before dispatch; verifier/integrator recheck before acceptance/publication; late observations persisted even when publication is refused; reconcile an old owner's unknown effects before granting another writer the same workspace.
- Done: table-driven tests per class; "what escalation must not conceal" column asserted in messages.

#### P4.6.2 [M] `Fingerprint`s, global no-progress budget, guards · TODO
- Why: [§13.2 ladder steps 1–2](docs/operations/recovery.md#sec-13-2); F7; FX-33.
- Build: `ErrorSignature` (coarse: D-19 normalization of volatile data only, preserving workspace-relative path/module, error code and meaningful literals) stored separately from the full `Fingerprint = hash(errorSignature, attempted fix, relevant state, affected requirement)`, both campaign-scoped, so a changed fix cannot erase repeated-error detection; global no-progress budget across all cells; doom-loop guard (same tool + args ≥ 3 without a state change; a meaningful edit or new observation resets); per-tool error budgets; request caps; repeated-failure-signature gate (same normalized error after 2 repairs ⇒ nudge to change hypothesis / record dead end / request alternative).
- Done: FX-33; identical commands against changed inputs are not a loop (test).

#### P4.6.3 [M] Failure `Capsule` and `Repair` helper · TODO
- Why: [§13.2 step 4](docs/operations/recovery.md#sec-13-2); [§3.4 repair helper row](docs/architecture/roles-shapes.md#sec-3-4); D14 in [§20.2](docs/reference/decisions.md#sec-20-2); FX-34.
- Deps: P4.5.1 (low-tier helper profile), P4.4.1
- Build: `Capsule(intendedOperation, acceptanceCriterion, callArguments, environment, errorOrExit, artifactVersions, completedEffects, rawEvidenceRefs, previousAttempts, allowedFixes, remainingBudget)`; `Roles.repair` (fresh small context; tools masked to the failing family + `look` + `run` within capsule scope; ≤ 2 attempts; low tier); outputs `fixed(corrected call + verified result) | diagnosis | escalate`; the caller's `[A]` always receives a ≤ 100-token diagnosis line; a repair never rewrites a failure into apparent success (deleting a failing test ⇒ the original acceptance still fails); S2+ only.
- Done: FX-34; diagnosis line present in the owning cell's next anchor.

#### P4.6.4 [M] `Alternative` attempts · TODO
- Why: [§13.3](docs/operations/recovery.md#sec-13-3); `Rebuild(AlternativeAttempt)` (P2.5.1).
- Build: same hypothesis failed twice ⇒ new controller-assigned attempt id, same contract, STATE Dead ends + Decisions attached, empty tail, optional escalation profile; both attempts' receipts kept; selection by acceptance evidence never by plurality; attempt count not reset; no speculative branching before simpler recovery.
- Done: alternative attempt preserves previous evidence and consumes the same increment allowance.

### P4.7 MCP mounts and catalog (contract)
#### P4.7.1 [C] `Mount` contract and catalog · TODO
- Why: [§15.3](docs/platform/adapters.md#sec-15-3); F11 corrections (annotations are hints); D-21.
- Pkg: `tool` (catalog), `tool.run` (invocation)
- Build: `Mount(server, tools: descriptors, localApproval, effectClassOverride)`; `look(catalog)` one-liners; `run(["mcp:<server>/<tool>", …])` through the same envelope/store/shaping/effect classes (locally approved validated read-only ⇒ `R`; otherwise `D` until configured); schemas never change mid-session; caller's ceiling inherited; `McpClient` transport interface only (P7).
- Done: FX-39 with a fake mount; catalog stable within a session.

### P4.8 Stage D validation
#### P4.8.1 [V] Fixtures · TODO
- Build: FX-13, 26, 29, 30, 31, 32, 33, 34, 35, 36, 39, 41, 45, 49 (S2 parameterization), 53, 55; IX-11, IX-23, IX-24.
- Done: green on both platforms.

#### P4.8.2 [V] Review, recovery and routing fixtures · TODO
- Build: labelled injected-defect diffs the review cell must catch (scripted judge responses validated against the protocol, not model quality); recovery ladder fixtures per failure class; routing table with fake profiles and budgets; warm-vs-cold KB comparison recorded as **deferred to live evaluation** (D-28).
- Done: protocol fixtures green; judge calibration on real models listed under P7.

---

## P5 Stage E — Scale (S3) and measured adapters
Goal: [§18.2 Stage E](docs/implementation/roadmap.md#sec-18-2): writer cells + integrator + merge queue, permission ladder + commit policy, L3 QA cells, L4 eval gates, tier-1/2 index, dense retrieval seam, generated tools, skills promotion, async checker seam (a learned routing corrector is added later without protocol change per [§11.1](docs/operations/routing.md#sec-11-1); nothing is scaffolded for it). Every feature here ships behind a flag with its ablation gate; S3 ships **off** by default ([§10.4](docs/operations/delegation.md#sec-10-4)).
**Fixtures:** FX-26 (writers), 27, 28, 39, 46 (tier-1/dense degradation), 49 (S3), 51 (worktrees); zero unauthorized-stage publications · **Unsupported until later:** S3 vs sequential gate, language-service adapter parity, any live measurement (P7).

### P5.1 Writer cells, worktrees, integrator `[O gate: B4 beats sequential S1 under equal budgets on decomposable tasks]`
#### P5.1.1 [M] Worktrees and workspace-qualified identities · TODO
- Why: [§10.4](docs/operations/delegation.md#sec-10-4); F02 (per-workspace shadow-ref suffix; ordinary refs are shared across worktrees); FX-51.
- Pkg: `workspace`, `os` (`Git.worktreeAdd/Remove`)
- Build: `Workspaces.createWorktree(work, attempt, increment) → Workspace(id)` under `candidates/`; `ShadowRef` per workspace; `VersionRegistry`/displayed coverage keyed by `workspace_id`; `Ownership(paths → increment)` (overlapping ownership serializes; interface changes forbidden in parallel increments); a worktree is edit isolation, not a security boundary.
- Done: FX-51 (same path + hash in two worktrees ⇒ separate coverage and refs); worktree removal never touches user branches.

#### P5.1.2 [M] `Writer` role · TODO
- Build: `Roles.writer` = implementer mask − delegation − CON/ADR writes; child contract slice with `base.stamp` and `read_versions`; budget charged to the parent; contract questions via `task.ask` to the parent, never decided locally; own worktree + shadow ref; packet validator = implementing exit gate against the child slice.
- Done: writer cannot emit `task.delegate` or propose CON/ADR notes (mask test).

#### P5.1.3 [M] `Integrator` and `MergeQueue` · TODO
- Why: [§10.4 `integrate()`](docs/operations/delegation.md#sec-10-4); [§4.4 integration horizon](docs/state/evidence-coherence.md#sec-4-4); F13; F09 (recheck ownership/cancellation before publication).
- Pkg: `delegate`
- Deps: P3.1.4 (integration re-verification hook), P3.2.5, P4.4.3
- Build: `Integrator.integrate(result)`: validate `result.base == recorded dispatch base` and current contract/authority/execution generation; if `main.current_stamp ≠ result.base.stamp` or any recorded read dependency moved ⇒ `stale-for-integration` ⇒ rebase in the child's worktree and re-run acceptance, or reject with evidence (FX-28); `MergeQueue` serialized under destination ownership: capture `integration_base` → apply the patch in an isolated integration candidate → combined-tree checker → blast radius over the **union** of merged edit sets → all affected acceptance → contract lint + required current review → publish only if main still matches `integration_base` and authority/generation remain valid; receipts bind `integration_base`, patch hash, resulting stamp and environment; controller alone updates the ledger; a clean textual merge proves nothing — semantic disagreement returns the shared decision to the main line (FX-27); integration-horizon `Coherence` hook registered.
- Done: FX-26 (cancelled writer's late patch archived, publication rejected), FX-27, FX-28.

#### P5.1.4 [M] `select_shape` S3 branch and global limits · TODO
- Why: [§3.5 `select_shape`](docs/architecture/roles-shapes.md#sec-3-5) S3 only from a validated plan; [§10.4 global limits](docs/operations/delegation.md#sec-10-4).
- Build: S3 iff `plan.units ≥ 2 ∧ disjoint(write_paths) ∧ no interface change in units ∧ contracts stable at fixed versions ∧ measured slack (D-39; slack includes reservations, review, verification, integration and an uncertainty margin, and never enables S3 before its promotion) ∧ S3.enabled`; never on the initial pass; max parallel cells 3; writer depth 1; leases with timeouts; cancellation before start and before publication; interface changes and design decisions never in S3 children; `Config.s3Enabled = false` default.
- Done: shape table tests extended; late results from superseded units rejected with spend counted.

### P5.2 Permission ladder and commit policy
#### P5.2.1 [M] Stages beyond `patch` · TODO
- Why: [§14.2](docs/platform/security.md#sec-14-2); invariant: never "delivered" for a patch.
- Pkg: `auth`, `campaign`
- Build: `local commit` (on a harness branch or shadow ref, never the user's branch — [§20.1 TRACE rejection](docs/reference/decisions.md#sec-20-1)), `push`, `merge`, `deploy` as separate D-class grants through `Authority.approve`; autonomous commit predicate: ceiling ≥ commit ∧ low blast radius ∧ easy reversibility ∧ L0–L2 green with current stamps ∧ (S2+) judge approval; default human anchors: interface-contract changes, data migrations, production deploys, new network access, ceiling elevation; model-generated metadata can never grant permissions, lower mandatory verification or raise spending limits; `highest_authorized_stage` in the finish receipt.
- Done: fixture "zero unauthorized-stage publications" across a scripted campaign with every ceiling value.

### P5.3 QA cell (L3) and L4 gates `[O gate: L3/L4 fixtures; requires a disposable environment]`
#### P5.3.1 [O][M] `QaCell` implementation · TODO
- Deps: P4.4.4; a `ConfinedRunner` backend (P7) or an isolated candidate with the `trusted-local` label
- Build: drives the product (CLI/HTTP; browser out of scope) in a disposable environment, never production; receipts with screenshots/logs as blobs; L3 rows in the ladder wiring (P3.1.4).
- Done: fixture project exercised end-to-end with artifacts attached to the finish receipt.

#### P5.3.2 [O][C] L4 measurement gate contract · TODO
- Build: `Check.kind = quality` measurement variant: artifacts with workload, environment and variability; performance/safety claims require it ([§8.2 L4](docs/verification/scheduler.md#sec-8-2)).
- Done: contract + validator; runner hook for project-defined measurement commands.

### P5.4 Index tiers 1–2 `[O gate: ablation language-service adapter on/off]`
#### P5.4.1 [O][M] `index-treesitter` module (tier 1) · TODO
- Why: [§7.2 tier 1](docs/repository/navigation.md#sec-7-2); D-10 (ERROR-node syntax check).
- Pkg: new module `index-treesitter` / `io.astrolabe.index.treesitter`
- Build: `TreeSitterIndex : SymbolIndex` via `io.github.tree-sitter:ktreesitter` + grammar artifacts for the D-09 languages; incremental syntax trees cached per `(path, version)`; precise outlines, declaration spans, imports; `tier = 1`, no cross-module resolution claimed; `Syntax` provider from ERROR **and** MISSING nodes with declared accepted file types and parser completeness (a syntax receipt concerns the exact file version only, D-10); native-library loading failures degrade to tier 0 with a reported degradation line (FX-46).
- Done: outlines/spans match tier-0 fixtures or better; module optional at runtime.

#### P5.4.2 [O][C] Language-service adapter contract (tier 2) · TODO
- Build: `interface LanguageService { defs, refs (scope + unresolved dynamic cases), diagnostics, incrementalTypeCheck }` feeding `look(def/refs/impact)` with `tier = 2` and the fast checker; implementation (LSP-backed) deferred to P7.
- Done: contract + fake implementation in test fixtures.

### P5.5 Dense retrieval seam `[O gate: only after measured lexical misses; ablation on/off]`
#### P5.5.1 [O][C] `Retriever` interface · TODO
- Build: `Retriever` with the lexical default (P2.6.2); embedding provider contract (compute + index in `indexes/`, disposable); a cold or missing service never blocks a cell (FX-46); measured lexical-miss rate from retrieval-miss logs is the enabling evidence.
- Done: contract + fake; ranker can consume a second candidate source without protocol change.

### P5.6 Generated tools and skills promotion `[O]`
#### P5.6.1 [O][M] Generated tool lifecycle · TODO
- Why: [§12.2 generated tools](docs/knowledge/learning.md#sec-12-2), [§15.3](docs/platform/adapters.md#sec-15-3).
- Build: ephemeral script (via `run`/transform) → project tool (README + schema + tests + declared effects; judge review if side effects) → global (eval-gated vs a disposable script and the tool it displaces); versioned registration active only at an attempt boundary; inherits the caller's ceiling (FX-39); exposed through `look(catalog)`.
- Done: registration timing test; ceiling test.

#### P5.6.2 [O][M] Skills and executable promotion tasks · TODO
- Build: `promote` output (P4.1.2) rendered as proposed tasks (test/linter/schema check) with the evidence on held-out tasks required before generalization; never auto-committed.
- Done: proposal artifact in `exports/`.

### P5.7 Async checker seam `[O gate: only with a tier-2 adapter and an ablation sync vs async]`
#### P5.7.1 [O][C] `Watcher` interface · TODO
- Why: [§8.1 "why synchronous in the baseline"](docs/verification/scheduler.md#sec-8-1); D12 in [§20.2](docs/reference/decisions.md#sec-20-2).
- Build: watcher results carry supersession + version tags and feed the same `ChecksRender`; off by default; sync checker remains the baseline.
- Done: contract + fake watcher; superseded results never presented as current.

### P5.8 Stage E validation
#### P5.8.1 [V] Fixtures and publication invariant · TODO
- Build: FX-26, 27, 28, 39, 46, 49, 51; zero unauthorized-stage publications; S3 vs sequential S1 comparison recorded as deferred to live evaluation (D-28).
- Done: green on both platforms; S3 flag default off verified.

---

## P6 Stage F — Evaluation runner and offline improvement
Goal: [§18.2 Stage F](docs/implementation/roadmap.md#sec-18-2), [§19](docs/evaluation/method.md#sec-19), [§12.3](docs/knowledge/learning.md#sec-12-3): the deterministic parts of the evaluation program (fixture harness, frozen campaigns, ablation switches, scorecard, promotion policy, contamination checks) and the improvement-runner skeleton. Live comparators and campaigns are P7 (D-28).
**Fixtures:** FX-47; the full FX/AX suites as the runner's own regression set · **Unsupported until later:** any live benchmark, B0/B-HELM, judge calibration on real models.

### P6.1 `eval` module
#### P6.1.1 [M] Fixture harness runner · TODO
- Pkg: new module `eval` / `io.astrolabe.eval` (depends on `core` + its test fixtures)
- Build: executable FX/AX suites with fault injection as a runnable program (not only JUnit); invariant metrics that must be zero in every configuration ([§19.4](docs/evaluation/method.md#sec-19-4)): ordinary anchored edits to unseen content, destructive missteps, silent acceptance changes, stale bodies served as current, unauthorized-stage publications, late superseded results merged, false-green incidents; separate entry point (`main`).
- Done: runner reproduces the JUnit results and exports a machine-readable report.

#### P6.1.2 [M] Frozen campaigns, comparators, ablation switches, workload manifests · TODO
- Why: [§19.1–19.2](docs/evaluation/method.md#sec-19-1), [§19.5](docs/evaluation/method.md#sec-19-5); invariant 12; F27.
- Build: `campaigns/` frozen manifests (attempt config, harness version, flags, strata, repositories, partitions by repository/task family/time); comparator configurations B0, B-HELM, B1–B5, Target (B0/B-HELM need live providers ⇒ config only); every §19.5 ablation as an `EvalArms` entry (research arms live in the `eval` module, never in production `Config`; arms that disable a mandatory control are runnable but marked ineligible for promotion — D-48, I-18); every live gate recorded with status `UNMEASURED`, its prerequisites and the evidence fields it will need (I-19); contamination checks: hidden acceptance outside the solver's workspace, answer-bearing patches/notes removed, mutable memory reset for cold runs and frozen/equal for warm runs, a repeatedly consulted selection set flagged as no longer a holdout (FX-47).
- Done: FX-47; flags enumerated and documented from one table.

#### P6.1.3 [M] Scorecard and promotion policy · TODO
- Why: [§19.4](docs/evaluation/method.md#sec-19-4), [§19.6](docs/evaluation/method.md#sec-19-6); F10 (E uses billed cost).
- Build: `Q = 100·Σ w_s·accepted_s/total_s`; `Cost_s = total_online_cost_s / accepted_s` (helpers, retries, review, integration included); `E_s = 100·clamp((Cost_high − Cost_s)/(Cost_high − Cost_low), 0, 1)`; `T_s` token-volume diagnostic; `eligible_score = 0.60·Q + 0.40·E` only among configurations passing quality floors and invariant checks; weights ≥ 0 summing to one; missing billing ⇒ unknown ⇒ blocks an economic claim; stratum with no accepted trial ⇒ `E_s = 0` and floor failure; online cost separated from one-off index/memory/calibration/harness-search cost with the repayment volume; promotion predicate (no complex-stratum loss; pre-set default: ≥ baseline pass rate at ≤ 60 % of baseline cost, or a higher pass rate at equal cost, revisable; one-sided paired bound above −2 pp clustered by repository/task, ceilings, frozen candidates with multiple-selection accounted); inconclusive keeps the baseline.
- Done: unit tests with synthetic trial tables; paired-bound helper tested.

### P6.2 Offline improvement runner skeleton `[O]`
#### P6.2.1 [O][M] Trace mining and experiment bookkeeping · TODO
- Why: [§12.3](docs/knowledge/learning.md#sec-12-3); never editable by a candidate: evaluator access, acceptance, budget accounting, adoption rules; live attempts run a frozen harness.
- Build: mine versioned traces for repeated failure categories (MAST-tagged distribution) and cost concentration; `Hypothesis(mechanism, module, predictedQuality, predictedEconomy)`; bounded change proposal record; matched-total-budget experiment plan vs the frozen baseline; integrated-evaluation and transfer-set bookkeeping; promotion for subsequent attempts with rollback; comparison against "spend the same on stronger reasoning / better context / another attempt".
- Done: records and reports only; no automatic harness mutation.

### P6.3 Validation
#### P6.3.1 [V] Runner and scorecard checks · TODO
- Build: fixture suite green through the runner; scorecard tests; contamination fixture; export schema documented.
- Done: `eval` module publishes its report format; live campaigns listed under P7.

---

## P7 Deferred (out of scope boundary)
Listed so the contracts above stay clean; none of these tasks start under this plan.

| Area | What is deferred | Contract it plugs into |
|---|---|---|
| Provider transports | `astrolabe-provider-openai` (Responses items), `-anthropic` (Messages blocks), `-compat` (OpenAI-compatible with *verified* parity): HTTP clients, auth, streaming, capability probes (never inferred from an API-shaped URL), native compaction, continuation, cancellation, hosted-tool output routing, provider-specific `UsageNormalizer` per [§15.2](docs/platform/adapters.md#sec-15-2), three retry semantics, AX-01..10 against recorded protocol fixtures + an authorized integration smoke campaign | `ProviderAdapter`, `UsageNormalizer`, `Capabilities`, `Segment` breakpoints (P0.3) |
| MCP client | stdio/HTTP transports, server handshake, schema fetch | `Mount`, `McpClient` (P4.7) |
| Confined runner backends | container/bwrap/sandbox-exec/firejail with writable roots = workspace + tmp, env allowlist, network off by default, resource limits, timeouts; Git common metadata protected from untrusted writes | `ConfinedRunner` (P1.6.5, P1.10.2) |
| Language service | LSP-backed tier-2 adapter | `LanguageService` (P5.4.2) |
| Live evaluation | B0/B-HELM comparators, stage gates (B1 vs B0/B-HELM, B2 ≥ B1, warm vs cold KB, routing savings, S3 vs sequential), tier-table calibration suite, judge calibration on labelled fixtures with real models, offline improvement experiments — all recorded `UNMEASURED` until run; nothing in P1–P6 may report `PROMOTED` (I-19) | `eval` module (P6) |
| Hosts | CLI, UI, OpenTelemetry exporter | `Astrolabe`/`AstrolabeJava`, `Events`, `Views`, `Export` |

---

## 5 Fixture map
Rows of [§19.3](docs/evaluation/fixtures.md#sec-19-3) in document order (`FX`), and [§15.4](docs/platform/adapters.md#sec-15-4) adapter fixtures (`AX`). "Phase" = where the fixture must first pass; later phases keep it green.

| FX | Scenario (abridged) | Phase | Tasks |
|---|---|---|---|
| 01 | file changed after inspection; anchor still unique ⇒ `expect` mismatch + diff since expect | P1 | P1.6.4 |
| 02 | hunk in a never-displayed region ⇒ reject; outline + displayed ranges | P1 | P1.6.4, P1.5.3 |
| 03 | second hunk ambiguous in preflight ⇒ apply none | P1 | P1.6.4 |
| 04 | I/O failure after the first file was replaced ⇒ actual partial state + preimage ids | P1 | P1.6.4, P1.2.5 |
| 05 | human edits after an agent patch, before undo ⇒ inverse refuses divergent content | P1 | P1.2.4, P1.2.5 |
| 06 | initially dirty/staged/untracked user changes preserved and separated | P1 | P1.2.3, P1.9.5 |
| 07 | formatter/generator/codemod modifies other files ⇒ touched (by run); Workset dropped; invalidation by closure | P1 → P3 | P1.6.5, P1.4.4, P3.1.2 |
| 08 | failed test wrapped in a successful shell command ⇒ runner status, parsed counts | P1 | P1.6.6, P1.7.4 |
| 09 | `pytest -k nonexistent` (exit 5) ⇒ `inconclusive` | P1 | P1.6.6 |
| 10 | logs exceed prompt and capture limits ⇒ both distinguished; retrieval path | P1 | P1.6.3, P1.6.6 |
| 11 | rollover after rejected hypotheses and amendments ⇒ they survive; KNOWN = seeds | P2 | P2.4.1, P2.5.2 |
| 12 | multi-file interface migration temporarily red ⇒ `red_ok_until`; final gate | P3 | P3.5.1 |
| 13 | required check cannot run ⇒ `unavailable` receipt; `blocked`; no endless gating | P1 → P3 → P4 | P1.6.7, P3.1.6, P4.4.3 |
| 14 | acceptance test weakened / skip / snapshot ⇒ flagged; review with original obligation | P3 | P3.4.2 |
| 15 | model attempts to remove/edit an acceptance item ⇒ impossible; pending amendment | P1 | P1.1.1, P1.1.3 |
| 16 | old green log after source/lockfile/check-definition change ⇒ `stale` unless reuse proof | P1 → P3 | P1.4.4, P3.1.2 |
| 17 | source mutates during a check and returns ⇒ isolation/no-concurrent-writer policy | P3 | P3.1.5 |
| 18 | checker reports no new errors while failures persist ⇒ "no change · still N" | P1 | P1.7.2, P1.7.3 |
| 19 | constraint/amendment disappears during a rebuild ⇒ validation fails / rehydrate | P2 | P2.5.3, P2.3.1 |
| 20 | repeated rebuilds reduce a race report to "fixed" ⇒ evidence reachable via Dead ends/STATUS | P2 | P2.4.2, P2.4.3 |
| 21 | provider tool-call/result pair broken by eviction ⇒ adapter rejects before dispatch | P1 | P0.3.4, P1.8.6 |
| 22 | long build times out while the process lives ⇒ resume polls the same handle | P2 | P1.6.5, P2.2.4 |
| 23 | crash between edit apply and receipt / after an external effect before receipt ⇒ reconcile from intent + stamp | P1 → P2 | P1.4.3, P1.9.2, P2.2.4 |
| 24 | external command with an uncertain timeout ⇒ `unknown_outcome`; reconcile | P1 | P1.6.5, P1.4.3 |
| 25 | concurrent calls exhaust the budget ⇒ reservations prevent overspend | P1 | P0.2.2, P1.9.4 |
| 26 | cancelled worker returns a late patch/effect ⇒ archived; publication rejected | P1 → P4 → P5 | P1.9.4, P4.4.1, P5.1.3 |
| 27 | two cleanly merging patches disagree semantically ⇒ integrator rejects; decision to main line | P5 | P5.1.3 |
| 28 | child result whose base moved ⇒ `stale-for-integration` | P5 | P5.1.3 |
| 29 | role silo hides a dependency ⇒ CON compiled in; impact flags the touch | P4 | P4.1.3, P3.2.2 |
| 30 | fresh judge lacks the rollback criterion ⇒ `insufficient_evidence` naming it | P4 | P4.4.3 |
| 31 | cheap task deceptively hard ⇒ escalation or `task.ask`; never invented behaviour | P4 | P4.5.2 |
| 32 | low-cost profile cannot meet the floor ⇒ narrow/checkpoint; floor never lowered | P4 | P4.5.1 |
| 33 | several cells repeat equivalent failing attempts ⇒ global no-progress budget | P4 | P4.6.2 |
| 34 | repair helper deletes a failing test ⇒ original acceptance still fails | P4 | P4.6.3 |
| 35 | popular note references a superseded contract ⇒ invalidated before injection | P2 → P4 | P2.6.3, P4.1.2 |
| 36 | one failed use creates a global rule ⇒ stays a scoped `PIT` candidate | P4 | P4.1.1 |
| 37 | runtime-only dependency missing from the import graph ⇒ `complete:false`; package fallback | P3 | P3.2.1, P3.2.5 |
| 38 | repository file or tool result instructs the agent ⇒ data; flagged; authorization unchanged | P1 | P1.10.1, P1.10.2 |
| 39 | generated tool or MCP mount requests broader access ⇒ caller's ceiling enforced | P1 → P4 → P5 | P1.10.2, P4.7.1, P5.6.1 |
| 40 | transform outside `scope_glob` or wrong match count ⇒ rejected; honest restoration state | P3 | P3.3.2 |
| 41 | probe findings for ranges that changed ⇒ pointers stale; parent re-looks | P4 | P4.4.2 |
| 42 | continuation cell for a verified increment ⇒ ledger prevents re-execution | P2 | P2.1.1 |
| 43 | cell hits the reserve with checks outstanding ⇒ `partial`, unverified scope named | P1 | P1.7.6 |
| 44 | pre-compiled `[K]` whose inputs changed ⇒ discarded and recompiled | P3 | P3.7.1 |
| 45 | old unrelated context retained to flatter the cache ⇒ judged by economics; eviction unchanged | P2 → P4 | P2.7.3, P4.5.3 |
| 46 | optional index/memory/embedding service unavailable ⇒ work continues; degradation reported | P2 → P5 | P2.6.2, P5.4.1, P5.5.1 |
| 47 | hidden final answers reachable through memory/notes ⇒ evaluation rejected as contaminated | P6 | P6.1.2 |
| 48 | tiny task ⇒ S0 with lifecycle controls; overhead measured | P1 | P1.9.2, P1.9.4 |
| 49 | minimalism becomes underspecification ⇒ operational fixtures pass in every shape (parameterized S0/S1/S2/S3, I-22) | P1 → P2 → P4 → P5 | P1.9.4, P2.7.1, P4.8.1, P5.8.1 |
| 50 | outline names an unread body; read + edit in one batch ⇒ rejected via dispatch-time coverage | P1 | P1.5.3, P1.6.2 |
| 51 | two contexts/worktrees share path + hash ⇒ ranges and shadow refs stay local | P2 → P5 | P1.2.1, P2.7.1, P5.1.1 |
| 52 | pending scope amendment then an out-of-scope call ⇒ no dispatch until approved | P3 | P3.4.1 |
| 53 | required review returns `revise` after local tests pass ⇒ unaccepted; findings steer | P4 | P4.4.3, P1.7.7 |
| 54 | one changed file inside a larger acceptance dependency set ⇒ intersection schedules the check | P3 | P3.1.2, P3.2.2 |
| 55 | calibration proposes a tier below the risk floor ⇒ both floors respected | P4 | P4.5.1 |
| 56 | native output with tool calls, then a rebuild ⇒ ids and opaque items valid; no inherited evicted tail | P2 | P2.5.1, P2.2.3 |
| 57 | refuted facts exceed the register cap ⇒ archived verbatim; explicit capacity gap | P2 | P2.4.2 |
| 58 | command starts, then the checker time box expires ⇒ `timeout`, not `not_run` | P1 | P1.7.2 |
| 59 | missing cache usage or a billed pre-warm ⇒ unknown stays unknown; no free warm-up | P1 | P1.11.2, P0.3.3 |

| AX | Adapter fixture ([§15.4](docs/platform/adapters.md#sec-15-4)) | Phase | Tasks |
|---|---|---|---|
| 01 | streamed tool call interrupted before completion ⇒ no execution of a partial call | P0/P1 | P0.3.4, P0.3.5, P1.8.7 |
| 02 | multiple tool-result pairing | P0/P1 | P0.3.1, P1.8.6 |
| 03 | output-limit stop | P0/P1 | P0.3.2, P1.8.7 |
| 04 | provider refusal | P0/P1 | P0.3.4 |
| 05 | expired continuation ⇒ fresh lineage | P0/P2 | P0.3.4, P2.5.1 |
| 06 | native compaction ⇒ effective history still admitted and valid | P0/P2 | P0.3.4, P2.3.3 |
| 07 | model-family change at a packet boundary ⇒ explicit handoff, opaque reasoning not replayed | P0/P4 | P0.3.1, P4.5.1 |
| 08 | cancellation with late output ⇒ output archived, not acted on | P0/P1 | P0.3.4, P1.9.4 |
| 09 | missing usage ⇒ unknown, never zero | P0/P1 | P0.3.3, P1.11.2 |
| 10 | cached-token normalization per provider ⇒ each category priced once | P0 | P0.3.3 (fake), P7 (live) |

Issue-regression fixtures from [ISSUES.md](ISSUES.md) (`IX-nn` = the "Acceptance" paragraph of `I-nn`; plan-level items I-01, I-19, I-22, I-25 have no runtime fixture and are closed by this revision):

| IX | Regression fixture (abridged) | Phase | Tasks |
|---|---|---|---|
| 02 | S0 rejects an uncommitted scope expansion; cannot complete after weakening a required check; ignores mid-attempt config changes | P1 | P1.7.8, P1.6.4, P1.9.4 |
| 03 | Stage C contract-touching migration completes with a valid human verdict, blocks without one, still blocks on another unavailable capability | P2 → P3 | P2.2.1, P3.5.2 |
| 04 | passing test then source mutation; mutate-and-restore writer; mutation inside an isolated candidate ⇒ never certifies untested bytes; scratch output does not invalidate a complete closure | P1 → P3 | P1.7.4, P3.1.5 |
| 05 | same-size edit with restored mtime invalidates evidence and rejects stale CAS; equal candidates at different times have equal ids; mode/membership/environment change changes the id | P1 | P1.2.1, P1.2.2, P0.2.1 |
| 06 | sequential cells and parallel children keep unambiguous `#n` references across carry-forward, crash/resume, recall, guarded revert | P1 → P2 | P1.6.1, P2.4.4 |
| 07 | changed scope/glob/source/since, expanded range/budget, stubbing and rebuild all yield the requested evidence; incomplete search never promoted to complete by dedup | P1 | P1.6.3 |
| 08 | untrusted rules-named file stays data; trusted snapshot loads; replacement + resume cannot elevate new instructions | P1 | P1.10.1, P1.3.2 |
| 09 | traversal, absolute escape, outside/protected junctions, Windows case aliases, ancestor substitution leave protected/outside bytes unchanged; unsupported publication guarantees reported | P1 | P1.2.6 |
| 10 | replacement inside multi-line anchors and removed lines grant no coverage to hidden spans; exact unredacted spans remain usable | P1 | P1.10.3, P1.5.3 |
| 11 | an acceptance-only rollback condition appears verbatim in implementing and review contexts; an id-only slice fails coverage | P1 → P2 → P4 | P1.1.4, P2.3.1, P4.4.3 |
| 12 | CRLF/encoding conversion and a clean filter do not change the captured baseline or run at snapshot time; dirty/staged/untracked data restores byte-exactly; candidate manifest equality verified before checks | P1 | P1.2.3, P1.2.4, P1.7.5 |
| 13 | stale green XML + no-tests run, missing terminal capture, forged stdout summary, same-name tests in different modules, parameterized-instance change ⇒ no false acceptance or pre-existing classification | P1 | P1.6.6, P1.7.5 |
| 14 | a Java-only consumer implements fake provider and authority, answers/rejects, registers events, cancels, reads outcomes, on JDK 26 on both OSes | P1 | P1.12.3, P0.3.4, P0.4.2 |
| 15 | cancel before provider id, cancel racing a completed tool response, late usage after cancel ⇒ no tool dispatch, spend settles once, unknown stays explicit | P0/P1 | P0.3.4, P0.3.5, P1.9.4 |
| 16 | mixed 5-minute/1-hour cache writes priced once each; aggregate + subfields not double-counted; missing breakdown blocks an exact economic claim | P0 | P0.3.3, P0.3.5 |
| 17 | adversarial text, many tool schemas/results, unknown continuation history never marked exact-fit; estimation drift recorded and admission adjusted | P0 → P2 | P0.3.2, P2.3.3 |
| 18 | production startup rejects forbidden control-disabling combinations; the evaluator can represent them without bypassing eligibility | P0 → P1 → P6 | P0.1.3, P1.9.4, P6.1.2 |
| 20 | Windows/Linux: immediate child spawning, parent exit, harness crash, cancel/deadline races, log-cursor continuation, PID reuse; no duplicate launch after a missing poll; `lost/unknown` never closes FX-22 | P0 → P1 | P0.6.1, P1.6.5, P1.12.4 |
| 21 | sibling repositories do not collide; linked worktrees share the store without sharing coverage; a second controller cannot acquire ownership; no accepted receipt references a missing artifact at any crash point | P0 | P0.5.1 |
| 23 | high declared risk with zero discovered fan-in keeps its floor; fifty high-tier successes do not authorize an uncalibrated lower tier; missing coverage cannot establish S0 eligibility | P2 → P3 → P4 | P2.2.1, P3.2.6, P4.5.1 |
| 24 | a fake profile rejecting the full schema gets an explicit supported boundary-time mapping or is refused before dispatch; masking never overrides native validity | P1 | P1.6.1, P0.3.5 |

## 6 Coverage matrix
Every component of [§3.2](docs/architecture/components.md#sec-3-2) and every workflow of the docs has an implementation path. Use this to check completeness after each phase.

| Component (§3.2) | Work packages |
|---|---|
| Campaign controller | P1.9, P2.1, P2.2, P3.2.6, P4.5 (profile), P5.1.4, P5.2 |
| Context compiler | P1.9.3 (S0 form), P2.3, P2.4, P2.5, P3.7 |
| Cell runtime | P1.8, P2.5, P4.1.3 (focus notes) |
| Tool layer + runner | P1.6, P3.3, P3.4, P4.7 |
| Workspace | P1.2, P1.3, P3.2, P5.1.1, P5.4 |
| Evidence store | P0.5, P1.4, P3.1 |
| Verification scheduler / integrator | P1.7, P3.1, P3.4, P3.5, P3.6, P5.1.3, P5.3 |
| Knowledge base + curator | P2.6, P4.1, P4.2, P4.3, P5.6.2 |
| Delegation service | P4.4, P5.1, P5.3 |
| Recovery ladder + router | P4.5, P4.6 |
| Provider adapters | P0.3 (contract + fake) · live: P7 |
| Telemetry + evaluation runner | P0.4, P1.11, P6 |

| Workflow / protocol | Tasks |
|---|---|
| [§3.6](docs/architecture/lifecycle.md#sec-3-6) one-increment data flow | P2.2.2 (each line maps to: P2.1.1 select · P2.3.1 compile · P1.8.7 run · P1.8.8 terminate · P1.7.7 exit gate/accept · P5.1.3 integrate · P2.2.2 accept/ledger · P4.2.1 extract · P2.2.2 next) |
| [§3.7](docs/architecture/lifecycle.md#sec-3-7) `campaign()` / `cell()` | P2.2.2 / P1.8.7 |
| [§4.4](docs/state/evidence-coherence.md#sec-4-4) coherence, five horizons | P1.4.4 (turn, cell, verification) · P2.6.3 (project) · P5.1.3 (integration) |
| [§5.4](docs/runtime/tools.md#sec-5-4) turn semantics + error policy | P1.6.2, P1.6.4–P1.6.6, P3.3.2 |
| [§5.8](docs/runtime/residency-rebuild.md#sec-5-8) rebuild, five uses | P2.5.1 (+ P2.5.2 pressure, P2.2.4 resume, P2.2.3 role switch, P4.6.4 alternative, P2.2.2 cell end) |
| [§6.1](docs/context/compiler.md#sec-6-1) compile + [§6.6](docs/context/continuity.md#sec-6-6) pre-compile + [§6.7](docs/context/continuity.md#sec-6-7) CAL | P2.3.1, P3.7.1, P2.6.4 |
| [§7.4](docs/repository/navigation.md#sec-7-4) impact, four consumers | P3.2.2 (verification depth, routing floor P4.5.1, shape P2.2.1/P3.2.6, `look(impact)` P3.2.3) |
| [§7.6](docs/repository/navigation.md#sec-7-6) missing-complement protocol | `[S]` contract line (P1.8.2) + probe cell (P4.4.2) |
| [§8.1](docs/verification/scheduler.md#sec-8-1) scheduler, validity, reuse, reserve, verify-on-stop | P1.7.1, P1.7.6, P3.1.1–P3.1.3 |
| [§8.2](docs/verification/scheduler.md#sec-8-2) claim-matched ladder L0–L5 | P1.7.2 (L0), P3.1.4 (L1–L2), P5.3 (L3–L4), P4.4.3 (L5) |
| [§8.5](docs/verification/scheduler.md#sec-8-5) baseline / [§8.6](docs/verification/acceptance-review.md#sec-8-6) guards / [§8.7](docs/verification/acceptance-review.md#sec-8-7) exit gate / [§8.8](docs/verification/acceptance-review.md#sec-8-8) judge / [§8.9](docs/verification/refactoring.md#sec-8-9) refactor / [§8.10](docs/verification/refactoring.md#sec-8-10) flaky | P1.7.5 / P3.4 / P1.7.7 / P4.4.3 / P3.5 / P3.6.1 |
| [§9](docs/runtime/workspace-editing.md#sec-9) editing at scale | P1.6.4, P3.3, P1.2.4–P1.2.5, P1.6.5 (§9.4) |
| [§10](docs/operations/delegation.md#sec-10) delegation + `integrate()` | P4.4, P5.1 |
| [§11](docs/operations/routing.md#sec-11) `select_profile`, escalation, calibration, economics | P4.5, P1.11.2 |
| [§12](docs/knowledge/learning.md#sec-12) learning pipeline, skills, generated tools, offline runner | P4.2, P4.1, P4.3, P5.6, P6.2 |
| [§13](docs/operations/recovery.md#sec-13) `recover()`, ladder, alternatives, resume | P4.6, P2.2.4, P1.4.3 |
| [§14](docs/platform/security.md#sec-14) modes, permission ladder, boundary, threat table | P1.10, P5.2 |
| [§15](docs/platform/adapters.md#sec-15) adapters, accounting, MCP, observability | P0.3, P1.11, P4.7 · live: P7 |
| [§16](docs/economics/costs.md#sec-16) cache-preserving invariant, boundary economics | P1.8.6, P1.8.2, P2.7.3 |
| [§17](docs/reference/defaults.md#sec-17) defaults | `Config`/`Defaults` (P0.1.1; every numeric default referenced by its task) |
| [§18.2](docs/implementation/roadmap.md#sec-18-2) stages A–F + first vertical slice | P1–P6, P1.12.2 |
| [§19](docs/evaluation/method.md#sec-19) evaluation, ablations, promotion | P6.1, flags across P2.6.4/P3.7/P4.1.3/P4.3.2/P5.* |
| [Appendix A](docs/reference/kernel-contract.md#sec-appendix-a) kernel contract | P1.8.2 (`[S]` text, implementing/writer roles only) |
| Failure taxonomy F1–F28 ([§1.4](docs/architecture/principles.md#sec-1-4)) | each row's named mechanisms map to the sections above; F28 = P1.9.4 in every shape |
| Invariants 1–12 ([§1.3](docs/architecture/principles.md#sec-1-3)) | 1–2: P1.1, P1.7.8 · 3: P1.2.1/P1.5.3 · 4: P1.6.1/P1.7.4 · 5: P1.7.4/P3.1.2 · 6: P1.4.3 · 7: P1.9.4/P5.1.3 · 8: P2.6/P4.1 · 9: P2.4.2/P2.5.3 · 10: P0.2.2/P4.4.1 · 11: P1.9.1 · 12: P1.9.4/P2.2.5/P6.1.2 |

Open risks [§21](docs/reference/risks.md#sec-21) → owning tasks (policy and reversal evidence per `ANSWERS.md`; every live measurement stays `UNMEASURED` here):

| Risk | Owning tasks | Risk | Owning tasks |
|---|---|---|---|
| R01 decomposition quality | P2.1.4, P2.6.4, P2.7.3, P6/P7 | R11 routing drift | P4.5, P6/P7 |
| R02 anchor cost | P1.8.3, P1.11, P6/P7 | R12 ritual review | P3.5.2, P4.4.3, P4.8.2 |
| R03 stale bytes before batching | P1.5.3, P1.8.6, P6/P7 | R13 hidden parallel dependencies | P5.1, P5.8, P6/P7 |
| R04 checker latency | P1.7.2, P5.4.2, P5.7 | R14 OS recovery assumptions | P0.6.1, P1.12.4, P2.2.4 |
| R05 tool breadth | P1.6.1, P6/P7 | R15 pre-compile waste/staleness | P3.7, P1.11 |
| R06 test-integrity precision | P1.7.8, P3.4.2, P4.8.2 | R16 calibration overfit | P2.6.4, P4.2.1, P6/P7 |
| R07 wrong codemod | P3.3, P3.8.2, P4.4.3 | R17 evaluation leakage | P6.1.2, P6.2, P7 |
| R08 harmful memory | P2.6.3, P4.1, P6/P7 | R18 harness bugs | every phase's `[V]` package, P6.1.1 |
| R09 evidence-ontology overhead | P1.4, P2.6, P1.11 | R19 interactive transparency | P0.4, P1.9.5–P1.9.6, P1.12.3 |
| R10 compiler omissions | P2.3, P4.4.2, P6/P7 | R20 seeds/notes/skills split | P2.3, P4.1.3, P4.3, P6/P7 |

## 7 Final validation of this plan (performed 2026-09-20; re-run after any restructuring)
- [x] Every component of §3.2 and every workflow/protocol has tasks (§6).
- [x] Order respects dependencies: contracts and storage (P0) → S0 kernel (P1) → continuity (P2) → verification depth (P3) → knowledge/delegation/routing/recovery (P4) → S3/adapters (P5) → evaluation (P6); phases are strictly ordered, but within a phase `Deps` may point forward (e.g. P1.6.7 → P1.7), so §0.1 selects by `Deps`, not by listing order; no task needs a later phase's output except through an explicitly masked op or a registered hook.
- [x] Independent review (2026-09-20, fresh context) applied: facade/config tasks (P0.1.3, P1.9.6), `Deps`-based resume rule, D-33–D-42, `task.propose` (P2.1.5), trip evaluation, CAL split (P2.6.4/P4.2.1), fixture-list alignment with §5, role texts (P4.4.6), removal of the routing-corrector scaffold.
- [x] No hidden assumptions: every choice not in the docs is a `D-nn` row (§3) with a disposition; no row is `OPEN`.
- [x] No invented subsystem: modules mirror §18.1; no service, queue, second database, orchestration framework, new role or new identity; optional features carry their §19.5 gates and ship off; the only additions are contracts real consumers need (`WorkspacePath`, `ProcessOwner`, `ProjectLock`, `Alias`, `TestedInputs`, `RulesTrust`) — none is a diagram mirror.
- [x] Deferred transport/provider work is isolated behind `ProviderAdapter`/`Invocation`, `UsageNormalizer`, `Mount`/`McpClient`, `ConfinedRunner`, `LanguageService` (P7 table); every live gate is `UNMEASURED`.
- [x] Compact for repeated use: §0–§3 is the per-session header; phases are loaded per task via `Spec:` links; §2.4, §5 and §6 are lookup tables.
- [x] Self-sufficient: task fields name types, packages, doc sections and completion criteria; the resume protocol (§0.1) needs only the docs + this file (+ the cited `ANSWERS.md`/`ISSUES.md` rows).
- [x] Owner answers for D-01, D-02, D-09, D-12, D-15 applied (`ANSWERS.md`); all 42 rows re-dispositioned.
- [x] Second review (`ISSUES.md` I-01–I-25) applied: §2.4 producer table (I-01); S0 scope/acceptance-surface/attempt-freeze controls (I-02 → P1.7.8, P1.9.4); human-review reachability (I-03); tested-input receipt eligibility (I-04 → D-45); raw-byte identities (I-05); campaign-global aliases and semantic dedup (I-06/07 → D-46); rules trust (I-08 → D-32); `WorkspacePath` (I-09 → P1.2.6); redaction masks (I-10 → D-49); full acceptance definitions in slices (I-11 → D-52); snapshot fidelity (I-12 → D-53); report provenance/test identity (I-13 → D-50); Java SPIs (I-14); invocation/cancel (I-15 → D-51); billable dimensions (I-16); admission contract (I-17); production vs eval config (I-18 → D-48); completion levels (I-19); process backend (I-20 → D-43); storage policy (I-21 → D-44); FX-49 per shape (I-22); floors/calibration/pre-scan (I-23); schema lineage (I-24); JDK 26 + equal platforms (I-25). Every runtime-testable issue has an `IX` row in §5.
- [x] Phase fixture lists equal the §5 Phase columns (script-checked after this revision).
