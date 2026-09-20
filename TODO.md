# ASTROLABE — implementation TODO (Kotlin/JVM SDK)

**Status: planning artifact only. No source code exists yet.** Spec authority: the architecture documents under `docs/` reached from [SOTA-BEST-MIXED-AGENT.md](SOTA-BEST-MIXED-AGENT.md). This file only *sequences* the build; where it seems to conflict with a subsystem document, the document wins and the conflict becomes a `D-nn` item in [§3](#3-decisions-and-open-items).

## 0 How to use this file

### 0.1 Resume protocol (every session)
1. Read the [architecture map](SOTA-BEST-MIXED-AGENT.md), [components §3.2–3.3](docs/architecture/components.md#sec-3-2), [invariants §1.3](docs/architecture/principles.md#sec-1-3) and [laws §2](docs/architecture/principles.md#sec-2).
2. Read §0–§3 of this file, then `grep -n "· IN_PROGRESS\|· BLOCKED" TODO.md`; continue that task, else take the first `· TODO` task of the current phase whose `Deps` are all `DONE` (listed order is a hint; `Deps` is authoritative; a task without `Deps` depends only on earlier tasks of its own work package).
3. Load only the `Spec:` links of that task plus their "Read with" companions. Do not preload the whole `docs/` tree.
4. Implement; run the narrowest checks; update the task status, append a `Log:` line, update [§1 Progress](#1-progress).
5. Anything not derivable from the docs → add a `D-nn` item, choose a default if safe, never redesign silently.

### 0.2 IDs, status, tags
- **IDs** are stable: phase `P<n>`, work package `P<n>.<m>`, task `P<n>.<m>.<k>`, decision `D-nn`, runtime fixture `FX-nn` ([§5](#5-fixture-map)), adapter fixture `AX-nn`.
- **Status** is the last token of a task heading after `·`: `TODO` · `IN_PROGRESS` · `BLOCKED(D-nn|reason)` · `DONE`. Work-package and phase status is derived (all tasks `DONE`).
- **Kind tag** after the ID: `[C]` contract/scaffold (types, interfaces, storage schema) · `[M]` minimal working implementation · `[I]` integration between components · `[V]` validation/hardening (fixtures, fault injection) · `[O]` optional/deferred (carries its evaluation gate; ships behind a `Config` flag, off by default).
- **Task fields** (only where useful): `Why` · `Deps` · `Pkg` (module/package) · `Build` (expected types/functions) · `Spec` (doc sections, fixtures) · `Notes` · `Done` (completion criteria) · `Log` (appended by the implementer: date, decisions, files).
- Numbers in tasks are the [declared defaults §17](docs/reference/defaults.md#sec-17); all live in `Config`, none hard-coded.

### 0.3 Scope boundary of this plan
In scope: the whole architecture through S3 as a library, validated with a **fake provider adapter**. Out of scope ([P7](#p7-deferred-out-of-scope-boundary)): live provider transports, auth, retry/backoff, HTTP clients, MCP client transports, confined-runner backends, live benchmark campaigns. Every phase below states which fixtures it must pass and which platform/provider assumptions remain unsupported.

## 1 Progress
- **Phase:** P0 · **Next task:** P0.1.1 · **Blocked:** none
- **Open decisions needing an owner answer:** D-01, D-02, D-09, D-12, D-15
- **Session log**
  - 2026-09-20 — plan created from docs 1.0.1; no Gradle project, no code. Local machine has git 2.45 and ripgrep, **no JDK/Gradle installed** (install before P0.1.1).

## 2 Target structure and conventions

### 2.1 Gradle modules (create a module only when its first task starts)
| Module | Artifact | Purpose | Phase |
|---|---|---|---|
| `build-logic/` | — | convention plugin `astrolabe.kotlin-library` (Kotlin 2.4.20, `explicitApi()`, toolchain 21 / target 17 (D-02), `abiValidation()`, `java-library`, `java-test-fixtures`, `maven-publish`, JUnit 5) | P0 |
| `provider-api/` | `astrolabe-provider-api` | item model, capabilities, adapter contract, usage/price types, tool schema types. **No networking.** Future provider modules depend only on this. | P0 |
| `core/` | `astrolabe-core` | everything else (one process, packages below); `testFixtures` = fake adapter, temp repos, scripted model, fault injection | P0–P5 |
| `index-treesitter/` | `astrolabe-index-treesitter` | tier-1 symbol index/syntax via `io.github.tree-sitter:ktreesitter` (native binary; optional) | P5 |
| `eval/` | `astrolabe-eval` | evaluation runner: fixture suites, frozen campaigns, scorecard, promotion; separate entry point | P6 |
| deferred | `astrolabe-provider-openai`, `-anthropic`, `-compat`, `astrolabe-mcp-client`, `astrolabe-runner-confined-*`, `astrolabe-cli` | see P7 | — |

Libraries (verify latest versions at P0.1.1; no pins here): `kotlinx-coroutines-core` (+`-jdk8` for `CompletableFuture` bridges), `kotlinx-serialization-json`, `org.xerial:sqlite-jdbc` (bundles FTS5), `org.slf4j:slf4j-api`, JUnit 5 + `kotlin-test`; optional `com.knuddels:jtokkit` (OpenAI-family token estimates, D-06), `io.github.tree-sitter:ktreesitter` (P5). No Spring, no Exposed/SQLDelight (D-03), no JGit (D-04), no YAML library (D-24).

### 2.2 Package map (`core`, root package `io.astrolabe`, D-01)
| Package | Owns (docs component) | Key types |
|---|---|---|
| `id` | four identities, hashing, versions, stamps [§3.3](docs/architecture/components.md#sec-3-3) | `WorkId`, `AttemptId`, `ContextId`, `WorkspaceId`, `Generation`, `Identities`, `Digest`, `FileVersion`, `Stamp` |
| `budget` | tokens, budgets, reservations [§8.1 reserve](docs/verification/scheduler.md#sec-8-1) | `Tokens`, `TokenEstimator`, `Budget`, `Reservations`, `Reserve` |
| `event` | UI/host hooks (outbound events, inbound authority, read views) | `AgentEvent`, `Phase`, `Events`, `EventSink`, `Authority`, `Views` |
| `store` | `.astrolabe/` layout, SQLite, blobs [§4](docs/state/contracts.md#sec-4) | `Layout`, `Db`, `BlobStore`, `Migrations` |
| `os` | OS adapter, processes, git CLI, search | `Os`, `Proc`, `Git`, `Search` |
| `contract` | Task Contract, amendments [§4.1](docs/state/contracts.md#sec-4-1) | `Contract`, `Requirement`, `Acceptance`, `Constraint`, `Scope`, `Authorization`, `Amendment`, `Contracts` (store) |
| `graph` | requirement graph, increments, ledger [§4.2](docs/state/contracts.md#sec-4-2) | `RequirementGraph`, `Increment`, `Ledger`, `Sizing` |
| `workspace` | version registry, stamps, dirty state, shadow ref, preimages [§4.6](docs/runtime/workspace-editing.md#sec-4-6) | `Workspace`, `VersionRegistry`, `Stamper`, `DirtyState`, `ShadowRef`, `Preimages` |
| `atlas` | orientation, index tiers, import graph, impact [§7](docs/repository/navigation.md#sec-7) | `Atlas`, `Prime`, `Sniff`, `Outline`, `SymbolIndex`, `ImportGraph`, `Impact`, `Focus` |
| `evidence` | journal, receipts, observations, claims, intents, coherence [§4.3–4.4](docs/state/evidence-coherence.md#sec-4-3) | `Journal`, `Receipt`, `Observation`, `Claim`, `Intent`, `Coherence` |
| `register` | STATE register [§5.2](docs/runtime/register-workset.md#sec-5-2) | `Register`, `Op`, `Patch`, `Validator`, `RegisterRender`, `ContractDigest` |
| `workset` | Workset, KNOWN/NOT SEEN [§5.3](docs/runtime/register-workset.md#sec-5-3) | `Workset`, `Entry`, `Coverage`, `Seeds` |
| `tool` (+ `tool.look`, `tool.edit`, `tool.run`, `tool.verify`, `tool.state`, `tool.task`, `tool.kb`) | seven families, envelope, turn partition, runner [§5.4–5.5](docs/runtime/tools.md#sec-5-4) | `ToolCall`, `Envelope`, `Gauge`, `Partition`, `Dispatcher`, `Catalog`, `Look`, `Edit`, `Transform`, `Runner`, `Shaper`, `Verify`, `StateTool`, `TaskTool`, `KbTool` |
| `cell` | cell runtime [§3.7](docs/architecture/lifecycle.md#sec-3-7), [§5](docs/runtime/context-layout.md#sec-5) | `Role`, `Cell`, `Layout`, `Anchor`, `Gates`, `Residency`, `Turn`, `ResultPacket` |
| `context` | compiler, manifest, carry-forward, rebuild, pre-compilation [§6](docs/context/compiler.md#sec-6) | `Compiler`, `Manifest`, `CarryForward`, `Rebuild`, `Precompile`, `Admission` |
| `verify` | scheduler, receipts validity, checker, gate, review protocol, refactor mode [§8](docs/verification/scheduler.md#sec-8) | `Check`, `Checks` (registry), `Scheduler`, `Closure`, `Applicability`, `Checker`, `ChecksRender`, `Baseline`, `ExitGate`, `Verifier`, `ScopeGuard`, `TestIntegrity`, `Review`, `RefactorMode`, `Flaky` |
| `campaign` | controller, shapes, lifecycle, resume, finish receipt [§3.5–3.7](docs/architecture/roles-shapes.md#sec-3-5) | `Controller`, `Shape`, `ShapeSelector`, `Lifecycle`, `Lease`, `Cancellation`, `Resume`, `FinishReceipt` |
| `kb` | knowledge base, curator, skills, extractor, calibration [§4.5](docs/knowledge/records.md#sec-4-5), [§12](docs/knowledge/learning.md#sec-12) | `Note`, `NoteKind`, `Kb`, `Index`, `Queue`, `Curator`, `Injection`, `Skill`, `Extractor`, `Calibration` |
| `delegate` | packets, probe/review/QA/writer, integrator [§10](docs/operations/delegation.md#sec-10) | `TaskPacket`, `InvestigationPacket`, `Delegator`, `Probe`, `ReviewCell`, `QaCell`, `Writer`, `Integrator`, `MergeQueue`, `Ownership` |
| `recover` | failure classes, fingerprints, ladder, capsule, alternatives [§13](docs/operations/recovery.md#sec-13) | `FailureClass`, `Fingerprint`, `Ladder`, `Capsule`, `Repair`, `Alternative`, `Reconcile` |
| `route` | profiles, tiers, selection, escalation [§11](docs/operations/routing.md#sec-11) | `Tier`, `TierTable`, `Router`, `Escalation`, `CalibrationLog` |
| `auth` | execution authority, boundary, redaction, permission ladder [§14](docs/platform/security.md#sec-14) | `Capability`, `Ceiling`, `Boundary`, `Redaction`, `PermissionLadder` |
| `telemetry` | spans, four quantities, accounting, exports [§15.2, §15.5](docs/platform/adapters.md#sec-15-2) | `Span`, `Quantities`, `Accounting`, `Export` |
| `java` | Java-facing facade (`CompletableFuture`/blocking) (D-07) | `AstrolabeJava` |
| root | facade + config | `Astrolabe`, `Config`, `Defaults`, `Mode` |

`provider-api` package `io.astrolabe.provider`: `Item` (sealed), `ToolSchema`, `ToolMask`, `Segment`, `Request`, `Response`, `StopReason`, `Capabilities`, `Profile`, `PriceTable`, `Usage`, `Money`, `ProviderAdapter`, `ProviderError`.

### 2.3 Code conventions (binding for every task)
- **Kotlin style:** `explicitApi()`; `data class`/`sealed interface` for records and outcomes; `enum` for closed vocabularies from the docs (status words, effect classes, kinds); no `value class` in public API (Java mangling, D-08); no Kotlin `Result` in public API; `fun interface` for callbacks; `@JvmOverloads`/`@JvmStatic` where Java callers need them; `suspend` only inside `core` and in the Kotlin facade, never in `io.astrolabe.java`.
- **Names:** docs vocabulary verbatim (`Increment`, `Receipt`, `Workset`, `Stamp`), precise verbs, no `Manager/Impl/Helper/Service` suffixes (the docs' "delegation service" is `Delegator`), explicit units in names (`tokens`, `seconds`, `bytes`).
- **Structure as documentation:** comments only for non-obvious invariants, protocol constraints (e.g. "assistant calls precede their results"), and the doc section they enforce (`// §8.7`).
- **Algorithms over frameworks:** sorted interval lists for displayed ranges; hash sets/maps for closures and joins; BFS over the import graph for blast radius; Kahn/Tarjan for requirement-graph cycles; bounded heaps for eviction order and note top-k; explicit state machines (`Lifecycle`, `Intent`, `Proc`) with typed transitions; parse tool args and runner output once at the boundary into internal types.
- **Determinism:** every policy (`select_shape`, `select_profile`, `compile`, eviction, gates) is a pure function of records, logged with its inputs; `Clock` and `IdGen` injected; no wall-clock or counters in cached prompt regions.
- **Ownership (L9):** one writer per store table; controller commits ledger, verifier accepts, curator publishes notes, runner assigns tool status — never the model.
- **Tests:** JUnit 5 + kotlin-test; fixtures from [§19.3](docs/evaluation/fixtures.md#sec-19-3) are harness tests first; no network; fake adapter only; Linux + Windows CI for anything touching processes or paths (D-12).
- **Storage rule:** SQLite is canonical for structured records; Markdown/JSON under `exports/` and `kb/*.md` are derived views ([§4](docs/state/contracts.md#sec-4)).

## 3 Decisions and open items
`OPEN` needs an owner answer (task marked `BLOCKED(D-nn)` if it cannot proceed on the default). `DEFAULT` = implementation choice taken because the docs leave it open; override by editing this table.

| ID | Question | Resolution | Gates |
|---|---|---|---|
| D-01 | Group/root package/artifact prefix | DEFAULT `io.astrolabe` / `astrolabe-*`. OPEN: confirm domain ownership. | P0.1.1 |
| D-02 | JVM target | DEFAULT toolchain 21, `jvmTarget`/`release` 17 for consumers; OPEN if consumers are all on 21 (then 21 for stable `invokedynamic` `when`). | P0.1.1 |
| D-03 | SQLite access | DEFAULT `sqlite-jdbc` + hand-written SQL behind `Db` (tx, mappers, migrations); no ORM/codegen. | P0.5.1 |
| D-04 | Git access | DEFAULT `git` CLI wrapper (worktrees, `update-ref` with expected old id, temp index for shadow snapshots); JGit rejected (weak worktree/ref-CAS support). `git` ≥ 2.20 on PATH is a runtime requirement. | P0.6.2 |
| D-05 | Search backend | DEFAULT ripgrep when on PATH, JVM regex scan fallback; identical `scope/complete/truncated` semantics. | P0.6.3 |
| D-06 | Token estimation | DEFAULT `TokenEstimator` heuristic (bytes/3.6 with code-aware tweaks) + optional jtokkit for OpenAI-family profiles; all budgets are estimates re-estimated from actual provider usage ([§6.1](docs/context/compiler.md#sec-6-1)). | P0.2.2 |
| D-07 | Async API style | DEFAULT `suspend`/`Flow` inside; `io.astrolabe.java` facade exposes `CompletableFuture` + blocking calls + listener registration. | P1.9.6, P1.12.3 |
| D-08 | Identity types | DEFAULT `data class` (Java-visible), not `@JvmInline value class`. | P0.2.1 |
| D-09 | First language/runner set for inline syntax, checker, shaping parsers, test-integrity classifier | DEFAULT Python (`py_compile`, pytest, ruff, pyright/mypy), JS/TS (`node --check`, jest/vitest, tsc, eslint), JVM (Gradle/Maven via JUnit XML, `javac`/`kotlinc` not used inline), generic head+tail. cargo/go/dotnet/mocha/unittest in P3.1. OPEN: confirm priority order. | P1.6.6, P1.7.2, P3.4.2 |
| D-10 | Inline syntax mechanism | DEFAULT language CLI where cheap (D-09); tree-sitter ERROR nodes when `index-treesitter` present; otherwise `not_run` (never silent). | P1.6.4 |
| D-11 | Confined runner backend | Contract only (`ConfinedRunner`); backend (container/bwrap/sandbox-exec/firejail) is a deferred module (P7). Until then every report carries the `trusted-local` label. | P1.10.2 |
| D-12 | Primary validation platform | Docs: one platform first, then Windows explicitly. OPEN: Linux-first CI with Windows matrix from P1 (recommended), or Windows-first (authoring machine). Process-tree kill = job objects on Windows, process groups on POSIX. | P0.6.1, P1.12.4 |
| D-13 | Environment id (`env_id`) inputs | DEFAULT SHA-256 over: OS/arch, sniffed toolchain versions (interpreter/runtime/build tool), lockfile hashes, runner config id (mode, env allowlist), verifier version. | P1.2.2 |
| D-14 | Redaction rules | DEFAULT configurable regex set (common API-key/token shapes, `Authorization:` headers) + env allowlist; applied before model exposure and before reusable evidence; exact preimages kept unredacted in `blobs/recovery/` with restricted access ([§14.3](docs/platform/security.md#sec-14-3)). | P1.10.3 |
| D-15 | State directory and git exclusion | DEFAULT `.astrolabe/` beside the repo (alt: user cache keyed by repo hash via `Config`), excluded from stamps. OPEN: may the harness append `.astrolabe/` to `.git/info/exclude` (local, not user-visible in diffs)? Default yes. | P0.5.1 |
| D-16 | `select_shape` size classes S/M/L | DEFAULT S: ≤ 3 expected files ∧ 1 requirement ∧ one package; L: cross-package ∨ > 10 expected files ∨ ≥ 4 requirements; else M. Declared default, tuned from CAL data later. | P2.2.1 |
| D-17 | Contract digest ≤ 150 tokens render | DEFAULT first request text cut at a sentence boundary + requirement ids/status + acceptance ids/status/currency + exclusions; full wording stays in `[K]`. | P1.1.4 |
| D-18 | `greedy_cover` admission | DEFAULT unit = note ∪ its `depends_on` bundle; value = injection score; greedy by value/tokens after mandatory; never drops mandatory; ties by id (deterministic). | P2.3.1 |
| D-19 | Failure-fingerprint normalization | DEFAULT strip paths→basename, hex/digits/timestamps→placeholders, keep exception/error class + first message line + affected requirement id + digest of attempted fix. | P4.6.2 |
| D-20 | Model-facing tool schemas | DEFAULT one JSON-schema tool per family (7) with `what`/`op` discriminator; **all** operations stay in the schema — masked, never removed ([§5.1](docs/runtime/context-layout.md#sec-5-1), [§16.1](docs/economics/costs.md#sec-16-1)); schema bytes fixed for the whole session; the role's `[S]` lists its unavailable ops; the executor refuses masked ops and ops not yet built in a stage; the token cost of masked entries is accepted per §16.1. | P1.6.1 |
| D-21 | MCP mounts | Contract + catalog in core (P4.7); MCP client transport deferred (P7). | P4.7.1 |
| D-22 | Second-model calls (judge, probe, extractor, repair) | Same `ProviderAdapter` contract with a routed `Profile`; validated with fake profiles until live adapters exist. | P4.4, P4.5 |
| D-23 | Review before review cells exist (Stage C) | DEFAULT `Authority.review` (human path) is the required-review implementation in P3; autonomous mode without a review authority ⇒ honest `blocked`, never skipped ([§18.2 note](docs/implementation/roadmap.md#sec-18-2)). | P3.5.2 |
| D-24 | KB Markdown front matter | DEFAULT small internal YAML-subset writer for exports; no YAML parser dependency (SQLite is canonical). | P2.6.1 |
| D-25 | Serialization | DEFAULT `kotlinx.serialization` JSON for packets/manifests/receipts/exports; native provider items kept as opaque `JsonElement` blobs. | P0.3.1 |
| D-26 | Concurrency | DEFAULT `Dispatchers.IO` for processes; one `Mutex` per workspace for mutations; parallel `look`s via `async` under a shared budget; `SupervisorJob` per campaign. | P1.6.2 |
| D-27 | Test identity per runner | DEFAULT pytest node id, jest/vitest full title path, JUnit `class#method`; part of each `Shaper`. | P1.6.6 |
| D-28 | Comparators B0/B-HELM and all live gates | Need live providers ⇒ P7; P6 builds the deterministic harness and scorecard only. | P6 |
| D-29 | Logical cache breakpoints | DEFAULT `Request.segments` carry `breakpoint=true` at ends of S/R/K/T; adapters map to provider mechanisms and *validate* support; fake adapter records them and prices cache classes from segment stability. | P0.3.2 |
| D-30 | Result ids and dedup keys | DEFAULT `#n` sequential per cell (context) for model-facing ids; journal `event_id` global; `look` dedup key `(what, target, version)`. | P1.6.1 |
| D-31 | S0 default write scope | DEFAULT repo root minus protected defaults (`.git/`, `.astrolabe/`, CI config, lockfiles, migrations dirs) when the request names none. | P1.1.2 |
| D-32 | Rules file discovery | DEFAULT `Config.rulesFile`; candidates in order `.astrolabe/rules.md`, `AGENTS.md`, `CLAUDE.md`; version-pinned snapshot at campaign open ([§14.3](docs/platform/security.md#sec-14-3)). | P1.3.2 |
| D-33 | Anchored hunk semantics `{anchor, near?, new}` | DEFAULT anchor = a literal (possibly multi-line) text span that must match exactly once — exact bytes first, then whitespace-normalised (blank runs collapsed, trailing blanks ignored, line endings normalised); `new` replaces the anchored span (an insertion is `new = anchor + inserted text`); `near` = a literal that must occur within 20 lines before the anchor and only selects among multiple matches; hunks are resolved to spans and must not overlap. | P1.6.4 |
| D-34 | `risk_floor` tier mapping | DEFAULT `high` when `contracts_touched ≠ ∅` ∨ `blast_radius ≥ 3` ∨ reversibility = hard ∨ max fan-in of a changed public symbol ≥ 20; `medium` when fan-in ≥ 5 ∨ `blast_radius = 2`; otherwise no floor beyond the function's never-below tier. Versioned with the tier table. | P4.5.1 |
| D-35 | `adjust_with_calibration` rules | DEFAULT no adjustment until ≥ 20 logged `(function, tier, effort, outcome)` quadruples exist for the packet's feature class; then raise one tier when the verified-failure rate at the current tier exceeds 30 %; lower by at most one tier only with ≥ 50 outcomes at ≤ 5 % failure; never below `max(never_below, risk_floor)`. | P4.5.1 |
| D-36 | Harness-origin KB notes (STATUS; CAL from P4) vs "the curator is the sole KB publisher" | DEFAULT every note write goes through the one serialized `KbWriter` (P2.6.1), owned by the `Curator` from P4.1.1; STATUS notes carry `admitted_by: harness`, are lint-exempt, same-task only and never injected elsewhere. | P2.4.3, P2.6.1 |
| D-37 | Injection score weights and threshold | DEFAULT `w_scope = 3, w_dep = 2, w_fresh = 1, w_use = 1, w_evid = 1, w_len = 1 per 500 tokens`, threshold 2.0; tuned from manifests and `(injected, cited, outcome)` logs; stored in `Config.injectionWeights`. | P4.1.3 |
| D-38 | Role policy text ownership | DEFAULT the SDK ships versioned default texts ([Appendix A](docs/reference/kernel-contract.md#sec-appendix-a) for implementing/writer cells; the [§3.4](docs/architecture/roles-shapes.md#sec-3-4) duties column for the others) as `Config` resources frozen per attempt; hosts may override; never more than a few operational lines. | P1.8.1, P2.1.2, P4.4.6 |
| D-39 | `ambiguous_bug` and `measured slack` predicates of `select_shape` | DEFAULT `ambiguous_bug` = defect-class request with no failing acceptance `run:` reproducible at open, or the plan cell recorded ≥ 2 plausible causes; `measured slack` = remaining budget ≥ 1.5 × the sequential-S1 estimate of all candidate units and the parallel-cell limit not exhausted. Both logged as `select_shape` inputs. | P2.2.1, P5.1.4 |
| D-40 | Impact pre-scan inputs at campaign open | DEFAULT candidate paths = paths named in the request ∪ lexical hits of request identifiers (`Search`) ∪ atlas hubs of the focus subsystem; `Impact` over that set; inputs logged with the shape decision. | P3.2.6 |
| D-41 | Transform jail before a confined backend exists | DEFAULT transforms run through the trusted-local runner with post-hoc stamp diff and the `trusted-local` label; the residual confused-deputy risk ([§14.4](docs/platform/security.md#sec-14-4)) is recorded in the transform receipt's `limits` until a `ConfinedRunner` backend (P7) is configured. | P3.3.1 |
| D-42 | Calibration prior before the KB path exists | DEFAULT P2 renders `CalibrationStats` as a harness-owned ≤ 150-token block in the plan cell's `[K]` (same status as the pre-existing ledger, not a KB admission); the `CAL` KB note with decay arrives via extractor + curator in P4 and replaces the block. | P2.6.4, P4.2.1 |

## 4 Phases

Legend per phase header: **Fixtures** = FX/AX rows that must pass as harness tests before the phase closes · **Unsupported until later** = assumptions the phase does not validate.

---

## P0 Foundation — project, contracts, hooks, storage, OS
Goal: scaffolding that establishes real contracts and boundaries; nothing here talks to a model or a repo yet. Mirrors [§3.2 minimal forms](docs/architecture/components.md#sec-3-2) and the "resolve ownership and public record/tool contracts first" instruction of the map.
**Fixtures:** AX-01..AX-10 at contract level (fake adapter + `validate()`); their runtime consequences are re-verified in the phases the §5 AX table names · **Unsupported until later:** any provider parity; confined execution.

### P0.1 Build and conventions
#### P0.1.1 [C] Gradle multi-module skeleton · TODO
- Why: reproducible build with Java-consumable outputs.
- Deps: — (install JDK 21 + Gradle wrapper first; D-01, D-02)
- Build: `settings.gradle.kts` (root `astrolabe`; `includeBuild("build-logic")`; `include(":provider-api", ":core")`), `gradle/libs.versions.toml`, `build-logic` convention plugin `astrolabe.kotlin-library` (see §2.1), `core/build.gradle.kts` (`api(project(":provider-api"))`, test fixtures), `.editorconfig`, `.gitignore`.
- Notes: Gradle wrapper on the newest version in the 7.6.3–9.7.0 compatibility window; `kotlin.explicitApi()`; `abiValidation()` opt-in (`ExperimentalAbiValidation`); `-Xjsr305=strict`; JUnit Platform; `maven-publish` with sources jar. No placeholder modules for `index-treesitter`/`eval`.
- Done: `gradlew build` green with an empty `core` and `provider-api`; `updateKotlinAbi` produces dumps; Java test fixture module compiles against `core`.

#### P0.1.2 [V] CI matrix · TODO
- Build: workflow running `gradlew check` on Linux and Windows (D-12); caches Gradle; publishes test reports.
- Done: both jobs green on the skeleton.

#### P0.1.3 [C] `Config` and `Defaults` · TODO
- Why: every number of [§17](docs/reference/defaults.md#sec-17) is configurable per task and none is hard-coded; harness changes take effect only at attempt boundaries (invariant 12).
- Pkg: root `io.astrolabe`
- Build: `Defaults` with one named field per §17 row (shape policy, `turnsPerCell = 40` with the 80 % nudge, `alpha = 0.65`, `k = 8`, `m = 6`, `rMaxTokens = 16_000`, `anchorMaxTokens = 2_500`, `immediateStubTokens = 800`, `lookBudget = 1_500`, `runBudget = 1_200`, `registerCap = 1_200`, `digestCap = 150`, `patchCap = 400`, fact/note/summary caps, seeds/injection/focus caps, `touchedInAnchor = 10`, `checkerTimeBoxSeconds = 20`, `theta = 40`, `fullSuiteCadence = 5`, reserves, guard counters, probe/review/repair/attempt/depth/parallel limits, `campaignCells = 12`, flaky policy, admission policy, profiles, mode/execution mode/`dClass`/ceiling, `runTimeoutSeconds = 120`); `Config(defaults, mode, executionMode, rulesFile, stateDir, flags: one switch per `[O]`/ablatable mechanism, roles + role texts (D-38), tierTable, redaction, injectionWeights (D-37))`; loadable from JSON and programmatically; frozen into `AttemptConfig` (P2.2.5).
- Done: a test enumerates the §17 table against `Defaults` fields; every `[O]` flag defaults off.

### P0.2 Identities, hashing, tokens, budgets
#### P0.2.1 [C] `id` package · TODO
- Why: [§3.3](docs/architecture/components.md#sec-3-3) four identities on every record; namespace rule (`workspace_id`, projection generation).
- Build: `WorkId`, `AttemptId`, `ContextId`, `WorkspaceId`, `Generation` (projection), `ExecutionGeneration`, `Identities(work, attempt, candidate: Stamp?, context: ContextId?)`, `Digest` (SHA-256 hex; `hash8` short form), `FileVersion(digest)`, `Stamp(baseCommit, trackedDeltaHash, untrackedManifestHash, envId, at) { val id }`, `IdGen` (injectable, deterministic in tests).
- Done: equality/serialization tests; `Stamp.id` stable for equal fields; Java-visible (`data class`, D-08).

#### P0.2.2 [C][M] `budget` package · TODO
- Why: invariant 10 (every child/retry/rebuild consumes the originating budget); reservations enforced across concurrent calls ([§8.1 reserve](docs/verification/scheduler.md#sec-8-1)); FX-25.
- Build: `Tokens`, `TokenEstimator` (+ `HeuristicEstimator`; jtokkit adapter optional, D-06), `Budget(cells, turnsPerCell, tokens, cost: Money?, attempts, reserves)`, `Reservations` (atomic `reserve(amount)` → `Reservation`; `reconcile(actual)`; `release`; conservative hold for uncertain external usage), `Reserve` (verification 15 % + recovery/persist 5 % of cell; campaign recovery 10 %; raised to known check costs).
- Done: concurrent reservation test cannot overspend; unknown usage keeps a hold until reconciled.

### P0.3 Provider API module (contract level only)
#### P0.3.1 [C] Item model · TODO
- Why: [§15.1](docs/platform/adapters.md#sec-15-1) item-based internal message model mapping losslessly to Responses items and Messages blocks.
- Pkg: `provider-api` / `io.astrolabe.provider`
- Build: `sealed interface Item` — `Message(role, parts)`, `ToolCall(id, name, argsJson)`, `ToolResult(callId, content, isError)`, `ReasoningRef(providerTag, opaque)`, `UsageItem`, `OpaqueContinuation(providerTag, payload)`; each with `native: JsonElement?` passthrough (D-25). `ContentPart` (text; other kinds opaque).
- Done: round-trip serialization; `ToolCall`/`ToolResult` pairing helper `pairs(items)` detects broken pairs (feeds FX-21).

#### P0.3.2 [C] Request/Response and tool schema types · TODO
- Build: `ToolSchema(name, description, jsonSchema)`, `ToolMask(ops)`, `Segment(kind: S|R|K|T|A, items, breakpoint: Boolean)` (D-29), `Request(segments, tools, profile, effort, maxOutputTokens, continuation?)`, `Response(items, stop: StopReason, usage: Usage?, continuation?)`, `StopReason { EndTurn, ToolUse, OutputLimit, Refusal, Cancelled, Truncated }`, `Effort`.
- Done: `Request.estimateTokens(estimator)` charges every serialized contribution once (tools, pinned text, retained protocol items) — [§6.1](docs/context/compiler.md#sec-6-1).

#### P0.3.3 [C] Capabilities, profile, usage, money · TODO
- Why: [§15.1–15.2](docs/platform/adapters.md#sec-15-1) capability description separated from request construction; accounting without double counting; missing usage is `unknown`, never zero.
- Build: `Capabilities(toolSchemaValidation, parallelToolCalls, streaming, outputLimit, contextLimit, nativeCompaction, continuation, cancellation, hostedExecution, caching: CacheCapability(breakpoints, minimumTokens), usageFields)`, `Profile(provider, model, config, capabilities, limits, priceTable, latency, stratumOutcomes)`, `PriceTable(date, perMillion by category, hostedTool)`, `Usage(uncachedInput, cacheRead, cacheWrite, output, reasoningIncludedInOutput, hostedToolUnits, unknown: Set<Field>, native: JsonElement?)`, `Money(currency, amount)`, `UsageNormalizer` (per-provider mapping interface; rules of §15.2 documented in KDoc; implementations deferred to P7 except the fake).
- Done: `Usage.price(table)` prices each category once; `unknown` propagates into `Money.unknown`.

#### P0.3.4 [C] `ProviderAdapter` contract and errors · TODO
- Build: `interface ProviderAdapter { capabilities(); validate(Request): Validation (tool pairing, breakpoints supported, admission vs contextLimit + output headroom, no evicted-tail continuation); suspend complete(Request): Response; cancel(handle); normalizer }`, `sealed ProviderError { Transport, RateLimit(retryAfter), OutputLimit, Refusal, ExpiredContinuation, InvalidRequest, MissingUsage }`.
- Notes: three retry semantics (provider / tool / verification) are recorded separately but **retry policy itself is P7**. Never expose a half-generated tool call: `Response` from a truncated stream carries `StopReason.Truncated` and no executable `ToolCall`.
- Done: KDoc states every rule of §15.1 the adapter must uphold; ABI dump committed.

#### P0.3.5 [M] Fake adapter + scripted model (test fixtures) · TODO
- Why: the only model used by this plan's validation; must exercise AX-01..AX-10.
- Pkg: `core/testFixtures` (depends on `provider-api`)
- Build: `ScriptedModel` DSL (turn matchers on request content → response items), `FakeAdapter` (records requests, segments, breakpoints; synthesizes `Usage` by segment stability: unchanged prefix ⇒ cache read, changed ⇒ uncached/cache write; injects faults: interrupted stream, broken pairing, output-limit stop, refusal, expired continuation, cancellation with late output, missing usage), `FakeProfile`s (main/helper/escalation) with fake dated price tables.
- Done: AX-01..AX-10 expressed as tests against `FakeAdapter` + `validate()`.

### P0.4 Events, host hooks and views (UI integration seam)
#### P0.4.1 [C] `AgentEvent` model and `Events` bus · TODO
- Why: external applications observe execution, progress, tool calls, state changes, warnings and results without coupling to a UI; phase tags per [§15.5](docs/platform/adapters.md#sec-15-5).
- Build: `Phase { understand, locate, edit, verify, recover, retrieve, compact, delegate, plan, review, integrate }`, `SpanId`, `sealed interface AgentEvent(at, ids: Identities, phase, span, parent)` with families: `Campaign.{Opened, ShapeSelected, IncrementSelected, IncrementClosed, Finished}`, `Contract.{Amended, AmendmentProposed, AmendmentResolved}`, `Cell.{Started, TurnStarted, ModelRequested, ModelResponded(usage), ToolCalled, ToolResulted(header), GateFired, RegisterPatched, WorksetChanged, Rebuilt(reason), Ended(packetRef)}`, `Edit.{Applied, Rejected, Reverted, Transformed}`, `Run.{Started, Output(handle, cursor), Finished, Reconciled}`, `Check.{Scheduled, Started, Finished(receiptRef), Stale}`, `Ask.{Question, Answered}`, `Blocked`, `Warning(kind, text)`, `Budget.{Reserved, Reconciled, Exhausted}`, `Routing.Decided`, `Delegation.{Dispatched, Collected, Rejected}`, `Recovery.{Classified, Repaired, Escalated}`, `Kb.{Proposed, Admitted, Invalidated}`. Events carry ids/refs, not bodies. `Events` (`SharedFlow<AgentEvent>` + `subscribe(EventSink): Subscription`), `fun interface EventSink`.
- Done: every event serializable; a recorder sink in test fixtures; Java can subscribe without coroutines.

#### P0.4.2 [C] `Authority` (inbound host hooks) and `Mode` · TODO
- Why: "who answers `blocked`" is a policy ([§1.2](docs/architecture/principles.md#sec-1-2)); amendments, D-class approvals and human review need a host seam ([§4.1](docs/state/contracts.md#sec-4-1), [§4.6](docs/runtime/workspace-editing.md#sec-4-6), D-23).
- Build: `interface Authority { suspend ask(Question): Answer; suspend approve(DClassRequest): Decision; suspend resolve(AmendmentProposal): Resolution; suspend review(ReviewRequest): Verdict? }` (`ReviewRequest`/`Verdict` are the [§8.8](docs/verification/acceptance-review.md#sec-8-8) protocol types, declared now in `verify` as contracts; the review cell arrives in P4.4.3), `Mode { Interactive, Autonomous }`, `AutonomousPolicy` (never auto-accept weakening; D-class denied unless contract allowlist; `ask` ⇒ `blocked`), `AutonomousAuthority` default.
- Done: contract tests: weakening proposals are never resolved as accepted by policy; answers are recorded as evidence vs amendment per §4.1.

#### P0.4.3 [C] `Views` (read projections) and exports · TODO
- Why: [§4](docs/state/contracts.md#sec-4) `exports/` derived views; [risk 19](docs/reference/risks.md#sec-21) "exports exist for a UI to consume".
- Deps: P0.5.2
- Build: `Views(store)` → `ContractView`, `LedgerView`, `RegisterView`, `WorksetView`, `ChecksView`, `BudgetView`, `ReceiptView`, `FinishReceiptView`; `Export.write(views, exports/)` JSON + Markdown; all read-only.
- Done: views are pure reads of P0.5 tables; export files diffable and deterministic.

### P0.5 Storage
#### P0.5.1 [C][M] `.astrolabe/` layout, `Db`, `BlobStore` · TODO
- Why: [§4](docs/state/contracts.md#sec-4) storage layout; [§4.3](docs/state/evidence-coherence.md#sec-4-3) content-addressed blobs, publication before reference.
- Build: `Layout(root)` (`state.sqlite`, `blobs/<digest>`, `blobs/recovery/` (restricted), `kb/`, `exports/`, `indexes/`, `candidates/`, `campaigns/`), `Db` (sqlite-jdbc, WAL, busy timeout, `tx {}`, typed row mappers, `Migrations`), `BlobStore` (`put(bytes): Digest` write-temp-then-rename, `get`, `exists`, `gc(referenced)` hook, integrity check = missing referenced blob is an integrity failure).
- Notes: D-03, D-15 (git exclusion). Blobs are never truncated; only views are.
- Done: crash-safety test (partial blob write leaves no referenced garbage); migrations idempotent.

#### P0.5.2 [C] Schema v1 · TODO
- Build: tables (one writer each, §2.3): `journal`, `blobs`, `stamps`, `receipts`, `observations`, `claims`, `intents`, `contracts` + `requests` + `requirements` + `acceptance` + `constraints` + `amendments`, `increments` + `ledger` + `sizing`, `cells` + `turns` + `manifests`, `register_versions`, `workset_exports`, `notes` + `note_queue` + `note_usage` (FTS5 virtual table over summary/anchors), `routing_log`, `usage` (per call, native + normalized), `leases`, `handles` (bg processes), `packets`. Every row carries `Identities` columns.
- Done: schema documented in `Migrations` KDoc; `Views` (P0.4.3) compile against it.

### P0.6 OS adapter, git, search, test kit
#### P0.6.1 [C][M] `Os` and `Proc` · TODO
- Why: [§13.1](docs/operations/recovery.md#sec-13-1) process identity beyond PID, log cursor, cancellation; [§18.1](docs/implementation/roadmap.md#sec-18-1) OS adapter validated per platform.
- Build: `interface Os { spawn(argv|cmd, cwd, env: allowlisted, timeoutSeconds, captureTo: BlobSink): Proc; killTree(Proc); replaceFileAtomically; normalizePath }`, `Proc(handle, pid, startedAt, identityKey, status: Running|Exited(code)|Lost, logCursor)`, `JvmOs` (job objects on Windows, process groups on POSIX; timeout kills the tree, never replays).
- Done: platform tests on Linux + Windows: tree kill, timeout, output cursor, PID-reuse guard (D-12).

#### P0.6.2 [C][M] `Git` CLI wrapper · TODO
- Build: `Git(repo)`: `status` (porcelain v2), `revParse`, `hashObject`, `lsFiles`, `diff`, `show`, `writeTree(tempIndex)`, `commitTree`, `updateRef(ref, new, expectedOld)`, `readRef`, `worktreeAdd/Remove` (P5), `infoExclude(add)` (D-15). Never `reset`, `clean`, `stash`, never touches the user index (temp `GIT_INDEX_FILE`).
- Done: tests on a temp repo incl. dirty/staged/untracked; `updateRef` with a wrong expected id fails.

#### P0.6.3 [C][M] `Search` · TODO
- Build: `Search.find(pattern, scope: paths|glob, budgetBytes, since?) → Hits(scope, complete, truncated)`; ripgrep backend + JVM fallback (D-05); four outcomes distinguished: zero matches / incomplete / failed / denied ([§5.4 envelope](docs/runtime/tools.md#sec-5-4)).
- Done: same results and flags from both backends on fixture repos.

#### P0.6.4 [M] Test kit · TODO
- Pkg: `core/testFixtures`
- Build: `TempRepo` builder (init, commits, dirty/staged/untracked files), fixture repos (small Python, TS, Gradle/Kotlin projects with runnable tests), `EventRecorder`, `StoreInspector`, `FaultInjector` (crash points: during command, after mutation before receipt, during rebuild, after external effect with lost ack — [§13.4](docs/operations/recovery.md#sec-13-4)), `FakeClock`, `FixedIdGen`.
- Done: used by P1 tests; fixture repos' tests run on both CI platforms.

---

## P1 Stage A — Dependable cell (S0)
Goal: [§18.2 Stage A](docs/implementation/roadmap.md#sec-18-2): one implementing cell with contract, evidence, coherence, tools, synchronous checker, register, gates, exit gate, P0 lifecycle controls, accounting. Shapes S1+ are not available yet (an S0-ineligible request ends as an honest `blocked`).
**Fixtures:** FX-01..10, 13 (partial: `unavailable` receipt), 15, 16 (stale marking; reuse proofs P3), 18, 21, 23 (open-time reconciliation), 24, 25, 26 (single-cell form), 38, 39 (executor ceiling), 43, 48, 49, 50, 58, 59 · **Unsupported until later:** resume across sessions (P2), rebuild (P2), blast radius/closures beyond `known(paths)` (P3), review (P3 human / P4 cell), KB content (P2/P4), confined execution (P7), live providers (P7).

### P1.1 Task Contract (S0 form)
#### P1.1.1 [C][M] Contract records and store · TODO
- Why: [§4.1](docs/state/contracts.md#sec-4-1) harness-owned, user-authoritative contract; invariant 1–2.
- Pkg: `contract`
- Build: `Contract(workId, version, attemptId, mode, shape, requests: append-only List<Request>, requirements, acceptance, constraints, exclusions, contractsTouched, scope: Scope(writePaths, protectedPaths), budget: Budget, authorization: Authorization(ladderCeiling, dClass, capabilitySet), risk, amendmentsPending)`, `Requirement(id, text, acceptance ids, dependsOn, authorityRef, status: harness-derived)`, `sealed Acceptance { Run(cmd, origin, last), Check(text, origin, evidenceRef), Review(text, origin, signedBy) }` with `Origin { user, harness, model(strengthens), amended(v) }`, `Constraint(id, text, authority)`, `Contracts` store (versioned rows; version bumps only on authorized amendment; failed attempts preserved).
- Done: model-side APIs cannot mutate acceptance (only `strengthens` add and `propose`); store round trip; FX-15 test.

#### P1.1.2 [M] S0 auto-derivation · TODO
- Why: [§4.1 auto-derivation](docs/state/contracts.md#sec-4-1); [§3.5 S0](docs/architecture/roles-shapes.md#sec-3-5).
- Deps: P1.3.1, P1.3.4 (atlas, sniffed commands)
- Build: `Contracts.deriveS0(request, atlas)` → `AC-1: run <sniffed suite> (origin harness, scope touched)`, default write scope (D-31), protected paths; requirement `R1` = request text.
- Done: fixture repos yield the right runner command; no acceptance ⇒ still derived; model must state goal-level acceptance in its first patch or ask (entry gate, P1.8.5).

#### P1.1.3 [M] Amendments channel · TODO
- Why: F1/F17 closed at the data model; [§4.1 origins and amendments](docs/state/contracts.md#sec-4-1).
- Deps: P0.4.2
- Build: `Amendment(by: model|user, cell, change, reason, status: pending|accepted|rejected)`, `Contracts.propose` (model → pending), `Contracts.amendByUser(text)` (append request, bump version, authority = message), `Contracts.resolve(via Authority)`; policy never auto-accepts a weakening; parent answers to children are evidence, not amendments; factual answers do not bump the version.
- Done: FX-15; version bump only on authorized amendment; events `Contract.*` emitted.

#### P1.1.4 [M] Contract digest and contract slice · TODO
- Why: [§5.1](docs/runtime/context-layout.md#sec-5-1) digest ≤ 150 tokens in `[A]`, verbatim slice in `[K]`.
- Pkg: `register.ContractDigest`, `context` (slice)
- Build: `ContractDigest.render(contract, ledger) ≤ 150 tokens` (D-17), `ContractSlice.forIncrement(contract, increment)` = requirements verbatim, ALL constraints/exclusions, acceptance ids + kinds + currency.
- Done: token cap enforced with the estimator; byte-stable for equal inputs.

### P1.2 Workspace core
#### P1.2.1 [C][M] `Workspace` and `VersionRegistry` · TODO
- Why: [§3.3](docs/architecture/components.md#sec-3-3) `version(workspace, path)`, `displayed(context, generation, workspace, path, v)`; F2 (namespace rule).
- Pkg: `workspace`
- Build: `Workspace(id, root, git)`, `VersionRegistry { version(path): FileVersion (mtime/size-validated hash cache); displayed(ctx, gen, ws, path, v): Ranges; show(ctx, gen, ws, path, v, range); change(path, from, to) → listeners }`, `Ranges` (sorted merged intervals), `ChangeListener` (Coherence hooks in P1.4.4).
- Notes: shared content hashes never share edit authority across contexts/workspaces (FX-51 in P2/P5).
- Done: displayed ranges union/merge tests; change notifications fire once per version transition.

#### P1.2.2 [M] `Stamper` · TODO
- Why: [§8.4](docs/verification/scheduler.md#sec-8-4) stamp = base commit + tracked delta hash + untracked manifest hash + env id; computed before/after `run(verify)` and at every cell boundary.
- Deps: P0.6.2, P0.2.1
- Build: `Stamper.stamp(workspace): Stamp` (excludes `.astrolabe/`), `Stamper.diff(a, b): changedPaths`, `EnvId.compute()` (D-13), stamps table.
- Done: editing a file changes the stamp; editing `.astrolabe/` does not; equal trees ⇒ equal stamps across runs.

#### P1.2.3 [M] Dirty-state record · TODO
- Why: [§4.6](docs/runtime/workspace-editing.md#sec-4-6) initial dirty-state record; FX-06.
- Build: `DirtyState.capture(workspace)` at campaign open → blobs (tracked delta, staged content, untracked files + modes) + `s0` stamp; `DirtyState.separate(final)` → `agent` / `by_run` / `pre_existing_user_changes` for the finish receipt.
- Done: FX-06 test: user changes survive edits, failed checks, reverts.

#### P1.2.4 [M] `ShadowRef` snapshots and `revert:turn:N` · TODO
- Why: [§4.6](docs/runtime/workspace-editing.md#sec-4-6), [§9.3](docs/runtime/workspace-editing.md#sec-9-3): snapshot after every mutating turn; constant-time selection; no `reset`/`clean`; never crosses the dirty-state record.
- Deps: P0.6.2
- Build: `ShadowRef(work, attempt, workspace)` → `refs/astrolabe/<work>/<attempt>/<ws>/head`; `snapshot(turn)` = temp index `add -A` (minus `.astrolabe/`) → `write-tree` → `commit-tree` (parent = previous) → `update-ref` with expected old id; `restore(turn)` writes files from that tree, guarded per file against divergent current content; snapshot 0 = captured dirty state.
- Done: restore never touches user refs/index/stash; expected-old-id mismatch fails loudly; FX-05 (inverse refuses divergent content).

#### P1.2.5 [M] `Preimages` and `revert:#id` · TODO
- Build: `Preimages.save(path, version, bytes)` into `blobs/recovery/` (unredacted, restricted, D-14); `revert(editId)` = version-checked inverse producing a diff receipt + inline syntax; refuses when current bytes ≠ postimage.
- Done: FX-05; preimage saved before any write (ordering test).

### P1.3 Orientation tier 0
#### P1.3.1 [M] `Atlas` · TODO
- Why: [§7.1](docs/repository/navigation.md#sec-7-1) harness-built structure without bodies; "the atlas never lies about existence".
- Pkg: `atlas`
- Build: `AtlasRow(path, bytes, lang, hash8, exports, imports, testsFor)`, `Atlas.build(workspace)` lazy, cached in `indexes/` by repo hash, `refresh(touchedPaths)` O(touched); collapse rules for vendor/build/generated (listed, not expanded); `Focus.render(atlas, focus: root|dir|file)` (root → dirs with sizes; dir → children with export counts + one-line names; file → outline).
- Done: rebuild-from-cache equals fresh build; refresh after an edit updates only touched rows.

#### P1.3.2 [M] `Prime` (`[R]` content) · TODO
- Why: [§5.1 `[R]`](docs/runtime/context-layout.md#sec-5-1), [§7.1](docs/repository/navigation.md#sec-7-1); byte-stable per repo version and role.
- Build: `Prime.render(atlas, sniff, rules, kbIndex, focusSubsystem)`: tree depth 3 with counts, languages, sniffed commands, rules-file snapshot (D-32; the only trusted repo text), top-10 hubs by inbound refs (tier 0: import count), `index/contracts.md` + `index/global.md` lines (empty until P2.6), behaviour-map excerpt ≤ 300 (empty until P4.3).
- Done: same inputs ⇒ identical bytes; no timestamps.

#### P1.3.3 [M] `Outline` and `SymbolIndex` tier 0 · TODO
- Why: [§7.2](docs/repository/navigation.md#sec-7-2) regex/ctags-grade tier with `complete:false` on refs.
- Build: `Language` detection, `Outline.of(path)` (declarations with spans for D-09 languages; generic fallback = top-level lines), `SymbolIndex.def(name)`, `refs(name) → complete=false, tier=0`.
- Done: outlines for fixture repos; `refs` always flagged incomplete at tier 0.

#### P1.3.4 [M] `Sniff` commands · TODO
- Build: `Sniff.commands(atlas)` → test/build/lint/type-check commands per manifest (Makefile, pyproject, package.json, Cargo.toml, go.mod, build.gradle(.kts), pom.xml), per package in monorepos ([§7.7](docs/repository/navigation.md#sec-7-7)).
- Done: fixture repos map to the right runner; unknown ⇒ `none` (never guessed).

### P1.4 Evidence store
#### P1.4.1 [M] `Journal` · TODO
- Why: [§4.3](docs/state/evidence-coherence.md#sec-4-3) append-only journal searchable via `look(find, in="store")`.
- Pkg: `evidence`
- Build: `JournalEvent(eventId, ids, turn, kind: call|result|editIntent|editOutcome|check|nudge|boundary|intent|reconcile, argsDigest, refs)`, `Journal.append/search(query, scope)` (FTS over text views), ordering guarantees.
- Done: append-only enforced (no update/delete API); search returns `complete` per scope.

#### P1.4.2 [C][M] `Receipt`, `Observation`, `Claim` · TODO
- Why: [§4.3](docs/state/evidence-coherence.md#sec-4-3), [§8.4](docs/verification/scheduler.md#sec-8-4) status vocabulary; invariant 4–5.
- Build: `Receipt(receiptId, checkId, acceptanceIds, cmd, cwd, argvOrShell, stampBefore, stampAfter, envId, verifierVersion, checkDefinitionVersion, contractVersion, outcome: Outcome{passed, failed, timeout, infraError, inconclusive, notRun, unavailable, denied, unknownOutcome}, parsed: Counts, inputClosure: Closure{Known(paths), Package(p), Unknown}, raw: blobRef, limits, reuseOf?)` immutable; `Observation(id, actionId, candidate, contentRef, scope, completeness, sourceVersions, capture)`, `Claim(id, text, kind h|v|x, evidenceState, authority, freshness, evidenceRefs)`.
- Done: applicability is **not** a receipt field (computed, P1.4.4/P3.1.2); parse error or absent result never maps to `passed`.

#### P1.4.3 [M] `Intent` journal and consequential-action ordering · TODO
- Why: invariant 6; [§4.3 ordering](docs/state/evidence-coherence.md#sec-4-3); FX-23, FX-24.
- Build: `Intent(intentId, actionId, argv, cwd, expectedEffect, idempotencyKey?, status: recorded|dispatched|running|observed|committed|unknown)`, `Consequential.run(reserve → intent → dispatch → observe → persist → commit)` helper enforcing the order; `Intents.openAtStart()` → `unknown_outcome` list for reconciliation.
- Done: fault injection at each boundary leaves a classifiable state; no action after an unreconciled unknown.

#### P1.4.4 [M] `Coherence` (turn, cell, verification horizons) · TODO
- Why: [§4.4](docs/state/evidence-coherence.md#sec-4-4) one registry, one rule: mark, never serve as current, never delete; F19.
- Deps: P1.2.1, P1.4.2
- Build: `Coherence.onChange(path, v → v')`: mark live reads @v stale (Workset hook), STATE facts @v stale (register hook), receipts with closure ∋ path or `Unknown` stale, schedule checker on path, refresh atlas row, drop Workset entry + announcement; `serve(item)` current iff anchors match else `stale|historical`; project (notes) and integration (delegated results) hooks registered in P2.6.3 / P5.1.3.
- Done: FX-07 (partial: reads/facts/receipts by known closure), FX-16 (stale after change; reuse proof arrives in P3.1.2).

### P1.5 Register (STATE) and Workset
#### P1.5.1 [C][M] `Register` model and Markdown render · TODO
- Why: [§5.2](docs/runtime/register-workset.md#sec-5-2); model-owned, harness-validated; rendered by the harness at the tail.
- Pkg: `register`
- Build: `Register(version, cell, increment, constraints, plan: List<Step(n, mark [ ]|[>]|[x]|[~], text, accept?, after?, req?, evidence?)>, facts: List<Fact(kind h|v|x, text, anchor?, evidenceId?, stale?)>, deadEnds, decisions, open, focus, amendments, next)`, `RegisterRender.markdown(register)` exactly in the §5.2 shape with harness-added `v(stale @old)` tags; `RegisterParser` (Markdown → `Register`, the inverse of the render, for STATUS export/import and tests — the model still emits typed ops only); `register_versions` persistence per patch.
- Done: render golden test against the §5.2 example; cap 1,200 tokens measured.

#### P1.5.2 [M] Typed ops, `Patch`, `Validator`, conditional ops · TODO
- Why: [§5.2 typed ops and invariants](docs/runtime/register-workset.md#sec-5-2); F21; [§5.5](docs/runtime/tools.md#sec-5-5) conditional ops.
- Build: `sealed Op { PlanAdd, PlanCursor, PlanTick, PlanCancel, FactAdd, FactRefute, DeadendAdd, DecisionAdd, OpenAdd, OpenClose, FocusSet, AmendPropose, Next }`, `Patch(ops, ifConditions: green(op:N)|applied(op:N))`, `Validator.check(register, patch, evidence, checks)` enforcing: one `[>]` while `[ ]` exists; exactly one `Next`; `tick` needs green accept on current version or evidence id; `v` needs an existing store id; ≤ 240 chars, no fences; `[~]` needs reason; dead ends need scope + reopen; `h` in active step flagged; red verify line needs an `Open` before `[>]` advances; `Amendments` only place for acceptance; cap 1,200; patches above 400 tokens rejected. `open.add(text, trip?, needs?)` trips are path/glob predicates evaluated after each edit batch; a fired trip renders one `⟨trip Qn fired: …⟩` line in `[A]` (P1.8.3), once. Conditions evaluated after runs; eligible list committed atomically against the STATE version; rejection returns the violated rule + sizes and leaves STATE unchanged (prior world effects stand).
- Done: one test per invariant; refuted facts retained in history; conditional drop rendered.

#### P1.5.3 [M] `Workset` · TODO
- Why: [§5.3](docs/runtime/register-workset.md#sec-5-3) KNOWN/NOT SEEN, mark-then-stub, region-seen; F3.
- Pkg: `workset`
- Build: `Entry(path, range, version, source: look|postEdit|seed|recall, turn)`, `Workset` (token-budgeted), `known()`/`notSeen()` render (≤ 60 tokens + named stale drops), `covers(path, v, range)` region-seen check using *dispatch-time* coverage (FX-50), `onVersionChange` → drop + announce now, physical stub at the next batch unless body > 800 tokens (immediate), `recall(id)` re-registers at recorded version (`historical` if changed), `export()` at cell end.
- Done: outline/def spans never make bodies KNOWN; stale announcement same turn; stub timing tests.

### P1.6 Tools
#### P1.6.1 [C] Tool contracts, schemas, `Envelope`, `Gauge` · TODO
- Why: [§5.4](docs/runtime/tools.md#sec-5-4) seven byte-stable families; envelope every tool every time; runtime-owned fields.
- Pkg: `tool`
- Build: `ToolFamily { look, edit, run, verify, state, task, kb }`, per-family arg types (serializable) + `ToolSchemas.forRole(role)` (D-20; computed once per session), `ToolCall(opId = emitted order, family, args)`, `Envelope(header: resultId, tool, cls, versions, stamp, truncated, effects; body view; footer gauge)` renderer with harness-owned delimiters `⟦…⟧`/`⟨…⟩`, runtime-owned fields (`action_id, status, candidate_before/after, scope, completeness, artifact_refs, capture_complete, display_truncated, redaction_applied, effects_observed, effects_unknown, retry_class`), `⚠ instruction-shaped content` flag hook (P1.10.1), `Gauge.render(ctx%, reserve, checks@v, known n/tok, STATE vN, turn i/N)` ~20 tokens.
- Done: schema bytes identical across a session for a role; envelope golden test.

#### P1.6.2 [M] Turn partition and `Dispatcher` · TODO
- Why: [§5.4 turn semantics](docs/runtime/tools.md#sec-5-4), [§5.5](docs/runtime/tools.md#sec-5-5), F03 (stable op ids, legal dependencies).
- Build: `Partition.of(calls)` → `look/kb` reads ∥ → one `edit` batch (or one transform) → `run/verify` → `state`/`task`/`kb.propose` metadata; op ids keep emitted order; conditions must point backward in execution order (edit-after-later-run rejected before effects); runs execute only if no edit batch or it applied fully; `NotExecuted(reason)` disposition per accepted call id; parallel looks under one shared budget (D-26); end-turn honored only after reconciliation + persistence; one shadow checkpoint per mutating turn.
- Done: partition property tests; FX-50; every call id gets a result or an explicit disposition.

#### P1.6.3 [M] `look` (tree, outline, read, find, recall, catalog, def) · TODO
- Why: [§5.4 look](docs/runtime/tools.md#sec-5-4); L1 budgets on observation; F10.
- Pkg: `tool.look`
- Build: `Look(what, target, budget=1500, near?, glob?, in, since?)`, targets `path | path:a-b | path::Symbol`, whole-file reads above budget refused → outline + hint, dedup `see #17 (unchanged)` (D-30), `find` in `workspace|store|kb` with `scope/complete/truncated`, `recall(id, range?, since?)` (stubbed result, log slice, or new bg output; changed file ⇒ `historical v=…`), `catalog`; `def` via tier 0; `refs/importers/impact` masked until P3.2, `bmap` until P4.3. Every result registers an `Observation` and Workset entries.
- Done: budget/truncation/recall pointer tests; FX-10 (prompt vs capture limits distinguished).

#### P1.6.4 [M] `edit` anchored CAS batch · TODO
- Why: [§9.1](docs/runtime/workspace-editing.md#sec-9-1), [§5.4 edit](docs/runtime/tools.md#sec-5-4), [§9.5](docs/runtime/workspace-editing.md#sec-9-5); F2, F6, F20.
- Pkg: `tool.edit`
- Deps: P1.2.1, P1.2.4, P1.2.5, P1.5.3
- Build: `sealed EditOp { Anchored(path, expect: FileVersion, hunks: List<Hunk(anchor, near?, new)>, if?), Create, Delete(expect), Rename(expect), Revert(id|turn), Transform (P3.3) }`, `Anchors.locate` (D-33: exact → whitespace-normalised; `near` disambiguates; 0×/>1× ⇒ three nearest candidates / all sites), `Preflight` (all ops; CAS on `expect`; hunks inside `displayed(path, expect)`; non-overlapping; unsupported kinds rejected by name), `Apply` (preimages first; serialized per workspace; mid-batch I/O failure ⇒ actual per-file state + preimage ids, no "rolled back", no auto-retry), inline `Syntax` (D-10), post-edit views ±3 lines registered at the new version, `diffstat`, `EditResult(ok, views, versions, syntax, diffstat, touchedOutsideScope, testIntegrity (P3.4), error?: {kind, candidates, sites, diffSinceExpect})`.
- Done: FX-01..05; stale `expect` returns diff-since-expect; no write on any preflight rejection.

#### P1.6.5 [M] `run`, `Runner`, effect classes, handles · TODO
- Why: [§5.4 run](docs/runtime/tools.md#sec-5-4), [§4.6 execution modes/effect classes](docs/runtime/workspace-editing.md#sec-4-6), [§9.4](docs/runtime/workspace-editing.md#sec-9-4), [§13.1](docs/operations/recovery.md#sec-13-1); F15, F19.
- Pkg: `tool.run`
- Deps: P0.6.1, P1.2.2, P1.4.3, P1.10.2
- Build: `Runner` interface + `TrustedLocalRunner(os)`; `ConfinedRunner` interface only (D-11); `EffectClass { R, W, D }` policy label + post-hoc verification by stamp diff (`R`→`W` only for observed in-scope writes; `D`/`unknown` retained); D-class ⇒ `Intent` + `Authority.approve` (interactive) or deny with reason unless contract-allowlisted (autonomous); `run(argv|cmd, cwd, shape, budget=1200, timeout=120, bg, intent, classHint, if: applied(op:N))` → `RunResult(id, exit, status, view, truncated, log blob, cls, stampBefore/After, current, changedPaths, handle?, parsed?)`; `cmd` = one shell invocation reported as a wrapper; timeout kills the tree, no replay; `bg=true` persists a `Handle` (identity key, cursor, status); `poll(handle, since)` → new output + cursor + status, no relaunch; `cancel(handle)` = request/status, not proof; lost acknowledgement ⇒ `unknown_outcome`; `touched (by run #n …: k paths)` line; MCP invocation shape reserved (`mcp:` prefix → P4.7).
- Done: FX-07 (touched by run + Workset drop), FX-22 (poll same handle after harness restart — handle persistence part), FX-24, non-zero exit is information not an op failure.

#### P1.6.6 [M] Shaping parsers (`Shaper`) · TODO
- Why: [§8.3](docs/verification/scheduler.md#sec-8-3) parsed counts from runner output; wrapper exit never equals suite status; F16.
- Build: `Shaper` registry: pytest, jest/vitest, Gradle/Maven (JUnit XML preferred, console fallback), generic head+tail with error lines (D-09; cargo/go/dotnet/mocha/unittest/ruff/eslint/mypy/pyright/tsc in P3.1.4 or here if cheap); `Counts(passed, failed, errors, skipped, discovered)`, test identities (D-27), `Status` from exit code AND parser (`pytest -k nonexistent` exit 5 ⇒ `inconclusive`), wrapper detection (`|| true`, `|| echo`) ⇒ status from the runner invocation only, truncation marks with recall pointer, prompt limit vs capture limit distinguished.
- Done: FX-08, FX-09, FX-10 with recorded runner outputs as test resources.

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
- Build: `Scheduler.record(run/verify result)` → `Receipt` with `stamp_before/after`, argv/cwd, env, verifier version, definition version, parsed counts, limits, closure; `current = stampAfter == stampNow` (reuse proof arrives P3.1.2); stale marking through `Coherence`.
- Done: a failed command cannot become a green receipt; exit 0 wrapper never green (FX-08).

#### P1.7.5 [M] `Baseline` receipt and pre-existing-failure ledger · TODO
- Why: [§8.5](docs/verification/scheduler.md#sec-8-5); the evidence line "pre-existing failure requires a baseline receipt".
- Deps: P1.2.3, P1.2.4 (materialize the captured initial candidate into `candidates/` via the shadow snapshot 0 tree when edits have already started)
- Build: `Baseline.run(suite)` on the initial candidate at `s0` → `PreexistingLedger(entries: test identity + environment + failure signature)`; rendered once in `[K]`; later red matching ⇒ `pre-existing (unchanged)`, else steering; never permission to waive task acceptance; exceptions explicit in the finish receipt.
- Done: baseline runs against the captured tree even after edits; identity+signature matching, not counts.

#### P1.7.6 [M] `Reserve` enforcement · TODO
- Why: [§8.1 reserve](docs/verification/scheduler.md#sec-8-1); FX-43.
- Deps: P0.2.2
- Build: verification 15 % + recovery/persist 5 % of the cell's tokens and turns held from cell start (raised to known check costs); the 15 % is spendable only on checks and the final register patch, the 5 % only on the Result Packet, receipts and the STATUS note; when only the reserve remains the `reserve reached` gate blocks new edits and generation ("verify and report"); reserve reached with checks outstanding ⇒ `partial` naming the unverified scope.
- Done: FX-43; reserve spend never on generation.

#### P1.7.7 [M] `ExitGate` and `Verifier.accept` · TODO
- Why: [§8.7](docs/verification/acceptance-review.md#sec-8-7), [§5.9](docs/runtime/gates-termination.md#sec-5-9); F8; F01 corrections (committed authority, criterion-bound current evidence, required review approval).
- Build: `ExitGate.evaluate(register, contract, increment, receipts, reviews, flags) → Accepted | Refused(missing: list)`: every `run:` acceptance green *and current*; every `check:` has an accepted assessment of the stated criterion (not merely an id); every `review:` signed (human path P3, judge P4); no red verify line without an `Open` item; no `[ ]`/`[>]` without disposition; test-integrity flags justified (P3.4); no unresolved impact nudge for a changed public definition (P3.2.4). `Verifier.accept(packet)` validates status against receipts (never the model's word), binds `base_stamp`, `patch_hash`, `resulting_stamp`, env; repeated unsupported finalization ⇒ gap-directed recovery, not an endless gate; budget/cancellation/process-wait are explicit non-completed exits.
- Done: gate refusal lists exactly what is missing; `Open` cannot waive a required acceptance; ledger untouched on refusal.

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
- Build: `Cancellation` token checked before dispatch and before any publication; `Lease(workspace, holder, expiry, executionGeneration)` single writer per workspace, expiry revokes publication authority only; `Reservations` per cell (atomic, P0.2.2); reconciliation on every terminal path; `Accounting` per call (P1.11.2).
- Done: FX-25, FX-48, FX-49; cancelled cell's late effects are archived, its publication refused (single-cell form of FX-26).

#### P1.9.5 [M] `FinishReceipt` (S0 form) · TODO
- Why: [§5.9 campaign finish receipt](docs/runtime/gates-termination.md#sec-5-9).
- Build: requirements with status/blockers; acceptance items (kind, status, stamp, currency, log ids); changes split `agent | by_run | pre_existing_user_changes`; `acceptance_surface_modified` (P3.4); checks run with verifier version + environment; `not_verified`; dead ends/decisions/ADR candidates/open items/pending amendments; routing decisions (P4); budget by cache class + helper share; memory candidates (P4); `highest_authorized_stage` (`patch` only in P1; never "delivered").
- Done: exported via `Views`/`Export`; a budget stop reports `partial`.

#### P1.9.6 [C][M] `Astrolabe` facade and `AstrolabeJava` · TODO
- Why: the SDK entry point for Kotlin and Java hosts (D-07); nothing sits above the controller.
- Deps: P1.9.3, P0.1.3, P0.4.1–P0.4.3
- Pkg: root `io.astrolabe`, `java`
- Build: `Astrolabe(config, adapter: ProviderAdapter, authority: Authority)`: `open(repo): Project` (state dir, workspace, store, kb), `suspend campaign(project, request, policy?): CampaignHandle` (`workId`, `await(): CampaignOutcome`, `cancel()`, `amend(text)`, `events: Flow<AgentEvent>`, `views`); `AstrolabeJava` mirrors it with `CompletableFuture`, blocking variants and `EventSink` registration; no `suspend`, `Flow` or `value class` in `io.astrolabe.java`.
- Done: P1.12.3 uses only `AstrolabeJava`; ABI dump reviewed.

### P1.10 Authority and integrity baseline
#### P1.10.1 [M] `Boundary`: delimiters and instruction-shape flag · TODO
- Why: [§14.3](docs/platform/security.md#sec-14-3); F11; delimiters are cues, not security.
- Pkg: `auth`
- Build: escape of delimiter-like payload bytes; `InstructionShape.detect(text)` heuristics ⇒ envelope flag `⚠ instruction-shaped content` (never filtered, never executed); rules-file pinned snapshot (D-32) — editing the rules path never elevates edited bytes; `[S]` data/instruction rule text.
- Done: FX-38 (repo file / tool result instructing the agent ⇒ data, flagged, authorization unchanged).

#### P1.10.2 [M] Capability ceiling and effect policy · TODO
- Why: L10; [§14.1](docs/platform/security.md#sec-14-1); executor enforces regardless of model request.
- Build: `Capability`/`Ceiling` from `Contract.authorization.capabilitySet`; `Policy.classify(run) → EffectClass` (protected paths ⇒ D; network/outside-workspace/git refs/package install (configurable)/privilege/destructive git ⇒ D); generated scripts inherit the caller's ceiling; `ExecutionMode { TrustedLocal, Confined }` label in `[S]` and every report (confined backend P7).
- Done: a masked/denied op is refused in the executor even when the schema would allow it; FX-39 (ceiling) single-executor form.

#### P1.10.3 [M] `Redaction` · TODO
- Build: `Redaction.apply(bytes) → (redacted, limitations)` before model exposure and before reusable evidence persistence; env allowlist in runner; recovery preimages exempt and access-restricted (D-14); `capture.redacted` recorded on observations.
- Done: secret patterns never reach envelopes or notes in tests; preimage revert still exact.

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
- Build: FX-01..10, 13 (partial), 15, 16 (stale marking), 18, 21, 23 (open-time), 24, 25, 26 (single-cell form), 38, 39 (executor ceiling), 43, 48, 49, 50, 58, 59 + AX-01..10 as JUnit tests using the test kit and fault injector; invariant metrics that must be zero ([§19.4](docs/evaluation/method.md#sec-19-4)) asserted where applicable (unseen-content edits, destructive missteps, silent acceptance changes, stale bodies served, unauthorized publications, late superseded results merged, false green).
- Done: all listed fixtures green on Linux + Windows.

#### P1.12.2 [V] First vertical slice · TODO
- Why: [§18.2 first vertical slice](docs/implementation/roadmap.md#sec-18-2).
- Build: scripted campaign on a fixture repo with a cross-file defect: contract → locate/read → guarded patch (one deliberately stale attempt) → run/poll a test (bg handle) → evidence → forced interruption (fault injector between edit apply and receipt) → reopen reconciles (full resume is P2.2.4) → verify candidate → receipt; initially dirty file preserved and separated in the finish receipt.
- Done: slice passes; per-call billed usage visible in exports; turn counts recorded as the fake-adapter baseline (B0/B-HELM are live comparators, D-28).

#### P1.12.3 [V] Java consumption smoke · TODO
- Deps: P1.9.6
- Build: Java test source set (or `samples/java`) calling `AstrolabeJava`: open project, run an S0 campaign with `FakeAdapter`, subscribe an `EventSink`, read `Views`; no Kotlin types leaking (`suspend`, `value class`).
- Done: compiles and runs with plain `javac`-level Java; ABI dump reviewed.

#### P1.12.4 [V] Platform validation · TODO
- Build: Linux + Windows tests for process trees, timeouts, file replacement, path normalization, encoding (D-12); document supported modes.
- Done: both platforms green; unsupported behaviours listed in `Config` docs, not assumed.

---

## P2 Stage B — Continuity (S1)
Goal: [§18.2 Stage B](docs/implementation/roadmap.md#sec-18-2): campaign controller, requirement graph, plan cell, increments, compiler with seeds/coverage/manifest, carry-forward, cross-cell coherence, the one rebuild mechanism (five uses), resume/reconcile, calibration prior, KB read path with STATUS/CAL notes written deterministically by the harness.
**Fixtures:** FX-11, 19, 20, 22, 23 (full resume), 35 (invalidation marks; injection exclusion P4), 42, 45, 46, 51 (context-local coverage), 56, 57 + the four crash intervals of [§13.4](docs/operations/recovery.md#sec-13-4) · **Unsupported until later:** closures beyond `known(paths)`/reuse proofs (P3), impact pre-scan (P3.2.6), review cells (P4), KB admission/injection ranking (P4), probes/routing/recovery ladder (P4), S3 (P5), live economics gate B2 ≥ B1 (P7).

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
- Build: `ShapeSelector.select(contract, impact, plan?) → Shape + inputs log` (size classes D-16; risk = max(blast radius, contract touch ⇒ high, reversibility); S0 iff size S ∧ low risk ∧ no `review:` ∧ no resume expected; S2 iff `review:` items ∨ risk ≥ high ∨ contract touch ∨ ambiguous bug (D-39); else S1; S3 branch disabled until P5.1.4; until P4 an S2 selection ends as an honest `blocked("shape S2 unavailable")`, mirroring P1.9.2), `Activation(shape)` = feature set per the §3.5 table (used to mask tools/features); upgrade only on traced evidence (pressure, probe request, risk floor); downgrade aggressively, but a downgrade never removes an outstanding required check/review; required review obligations (incl. refactor mode) select ≥ S2 or an authorized human review.
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
- Build: `AttemptConfig(harnessVersion, policy, profiles, flags)` frozen per attempt in `campaigns/`; harness/config changes apply only at attempt boundaries; new attempt ids controller-assigned (alternative attempts P4.6.4).
- Done: mid-attempt config edits are ignored until the next attempt (test).

#### P2.2.6 [M] Campaign finish · TODO
- Why: [§8.7 campaign gate](docs/verification/acceptance-review.md#sec-8-7), [§7.3 cadence](docs/repository/navigation.md#sec-7-3), [§5.9 finish receipt](docs/runtime/gates-termination.md#sec-5-9).
- Build: `Controller.finish()`: all contract acceptance at the final stamp; full suite green or failures in the pre-existing ledger (allowed exceptions explicit); full-suite cadence every `K = 5` verified increments and at campaign end; campaign review predicate `(shape ≥ S2 ∧ increments ≥ 3) ∨ refactor_mode ∨ explicitly required` (human path P3 / cell P4); full `FinishReceipt`; explicit gaps otherwise.
- Done: finish never reports `completed` with a stale acceptance receipt.

### P2.3 Context compiler
#### P2.3.1 [M] `Compiler.compile()` (full) · TODO
- Why: [§6.1](docs/context/compiler.md#sec-6-1) deterministic function of (increment, campaign state); L3; F06 (count the complete serialized context; validate all inputs).
- Pkg: `context`
- Deps: P1.9.3 (S0 form), P2.4, P2.6, P1.3.1
- Build: `compile(increment, contract, workspace, store, kb, seeds, profile)`: reconcile contract/candidate versions; `mandatory` = contract slice ∪ mandatory skill modules (P4.3; empty until then) ∪ rules ∪ `index/contracts.md` ∪ CON/ADR notes whose anchors ∩ write_scope ≠ ∅ ∪ carry-forward ∪ pre-existing ledger ∪ packet required refs ∪ native protocol items that must remain intact; `budget = C_profile·α − |S| − |R| − |pinned T + retained protocol| − reserve(output + next observation + [A]_max + margin)`; `NEEDS_RESCOPING_OR_LARGER_PROFILE` when mandatory exceeds budget (never drop an invariant); `workset_seeds ≤ 4K` re-served at current versions with hashes (changed files ⇒ NOT SEEN + note); `kb_slice` (CON/GLOBAL only until the ranker in P4.1.3); skill modules (P4.3); `focus_zoom` + `calibration_prior` (P2.6.4); `greedy_cover` (D-18) with dependency bundles and no silent mandatory drop; expand `depends_on` within budget; recheck versions/coverage; coverage assertion (presence of constraints, acceptance, CON for touched paths, rules) ⇒ `NEEDS_MORE_EVIDENCE`; `adapter.validate(render)`; persist `Manifest`; return `[S][R][K]`. Selection order: mandatory → affected contracts → carry-forward → seeds → local implementation → lessons/pitfalls → skills → background.
- Done: property tests: mandatory never dropped; every serialized contribution charged once; rules/index content referenced for coverage, not duplicated into `[K]`; FX-19 (missing constraint ⇒ validation fails).

#### P2.3.2 [M] `Manifest` · TODO
- Why: [§6.5](docs/context/continuity.md#sec-6-5) "absent" vs "misread" distinguishable.
- Build: `Manifest(incrementId, ids, contractVersion, registerVersionIn, notesInjected (ids, versions), seeds (path, range, hash), skills + versions, profile + effort, budgetArithmetic, omissions with reasons, continuationLineage, reductionOps, estimatedTokens, actualUsage?, boundaryReason: done|partial|replan|pressure|resume)` persisted per cell; `Cell.Ended` links it.
- Done: manifest for every compiled context; actual usage filled after the first response.

#### P2.3.3 [M] Hard admission check before every dispatch · TODO
- Why: [§6.1](docs/context/compiler.md#sec-6-1) total-context admission incl. output/reasoning headroom; re-estimated on actual usage; [§15.1](docs/platform/adapters.md#sec-15-1) effective continuation history counts.
- Build: `Admission.check(request, profile, lastActualUsage) → ok | CapacityCondition`; multi-result turns re-estimated; capacity ⇒ pressure rebuild (P2.5.2) or explicit capacity condition, never a malformed history.
- Done: fake adapter with a small context limit triggers rebuild, never an oversize request.

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
- Build: `Workset.export()` at cell end → `workset_exports`; `Seeds.from(export, nextStep)`; store is campaign-scoped so `recall #17` resolves across cells; stub index of ids referenced by facts rendered on request only.
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
- Build: FX-11, 19, 20, 22, 23, 35 (invalidation marks), 42, 45, 46, 51 (two contexts, same path + hash ⇒ context-local displayed ranges), 56, 57; crash during a command / after a mutation before its receipt / during a rebuild / after an external effect with lost acknowledgement.
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
**Fixtures:** FX-07 (full closure invalidation), 12, 13, 14, 16, 17, 37, 40, 44, 52, 54 · **Unsupported until later:** review cells (P4), CON notes from the curator (P4.1; P3 reads whatever CON notes exist), tier-1/2 index (P5.4), async checkers (P5.7), a real jail for transforms (P7 confined backends; until then D-41 applies and the residual risk is recorded in transform receipts).

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
- Build: hold the workspace mutation lock for `inline|fast` checks; `slow|expensive` checks run on an exported isolated candidate (`candidates/`, from the shadow snapshot) or record `limits: [no isolation]` in the receipt; mutable external services listed as limitations.
- Done: FX-17.

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
- Build: `Controller.open` runs `Impact` over the request's candidate paths (D-40: paths named in the request, lexical hits of request identifiers, atlas hubs; logged as inputs) ⇒ `impact.contract_touch`, fan-in estimate ⇒ `ShapeSelector` risk inputs and `contracts_touched` in the contract.
- Done: shape decisions logged with pre-scan inputs.

### P3.3 Scripted transform path
#### P3.3.1 [M] `edit(transform)` with diff receipt · TODO
- Why: [§9.2](docs/runtime/workspace-editing.md#sec-9-2); F05 corrections (preimages first; discard or guarded inverse; honest partial state).
- Pkg: `tool.edit`
- Deps: P1.6.4, P1.6.5, P3.1.1, P3.2.5
- Build: `Transform(script|argv, scopeGlob, inventory?, expectedMatches?{min,max}, preconditions?, why)`: resolve allowed target inventory/preconditions/expected-count policy before dispatch (omitted ⇒ `unspecified`, never `inventory_ok: true`); record preimages before mutation; run the script through the `Runner` (jail = confined runner when available, else trusted-local with post-hoc stamp diff, labelled, D-41); harness computes the diff, snapshots the shadow ref, inline syntax on every changed file, refreshes atlas rows; `TransformReceipt(filesChanged, hunks, perFile(+n −m, versionAfter), diff blob, syntax, touchedOutsideScope, inventoryOk, matchCount, representativeSites ×3, unusualSites ×3)`; changed files enter the Workset as `touched-by-transform (NOT SEEN)`; scheduler treats all changed files as touched ⇒ blast-radius checks mandatory before increment close; review receives the diff id (P4.4.3).
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
- Build: after the cell's last mutation when only `slow|expensive` checks remain, build the next increment's `[K]` locally; `Fingerprint(candidate stamp, contract/authority revision, selected increment, carry-forward/register version, note/contract/skill/index versions, role, profile, frozen policy)`; on cell close reuse iff fingerprint and required coverage still match, else discard and recompile; only for `cell_end(next_increment)`, never continuation of a red increment; provider pre-warm requests are a separate, budgeted, off-by-default option (not implemented here); hit/miss + p50/p95 boundary latency in telemetry.
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
**Fixtures:** FX-13 (with review), 26 (probe/review cancellation), 29, 30, 31, 32, 33, 34, 35, 36, 39, 41, 45, 53, 55 · **Unsupported until later:** S3 writers/integrator (P5), MCP transport (P7), live judge calibration on real models (P7), warm-vs-cold KB gate (P7).

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
- Build: `score = w_scope·match(scope, write_scope) + w_dep·overlap(depends_on, contracts in play) + w_fresh·freshness + w_use·use_value + w_evid·evidence_quality − w_len·tokens` (default weights and threshold D-37, tunable from manifests); cap 8 notes / 1.5K; `CON` for touched paths bypass the cap; nothing when nothing is strongly relevant; never re-inject unchanged advice within a cell; focus notes ≤ 300 tokens per turn anchored in `Focus` or files touched this turn, each once per cell, rendered in `[A]` (P1.8.3 slot); `(injected, cited-in-register?, outcome)` logged per note; `retrieval_miss` ops and `Open (needs: …)` lines logged as labelled negatives; `kb.propose` unmasked; the `CAL` note (P4.2.1) reaches the plan cell through this path and replaces the P2 statistics block (D-42).
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
- Build: `Roles.review` (fresh context, no proposer transcript; tools `look` read-only, `kb.search`, `verify(tests)` on an **isolated copy** of the candidate tree from `candidates/`; budgets ≤ 10 `look` / 30K increment, 60K campaign); `EvidencePacket(contractSlice, diff, receipts with parsed counts, CON/ADR notes touching the paths, testIntegrityFlags with original obligations, preexistingLedger, coverageReport, rubric)`; `Verdict(approve|revise|reject|insufficient_evidence|escalate, findings[{severity, location path:line@hash, issue, suggestedFix, kind}], coverage{filesReviewed, ranges, unread} from telemetry, contractViolations, confidence)`; increment-scope triggers (risk floor, contract/ADR touch, cheap-tier output, test-integrity flag, `review:` item) and campaign-scope predicate (P2.2.6); rules: acceptance criteria first, executable checks outrank opinion, `insufficient_evidence` with a named criterion is correct, competing proposals presented symmetrically in randomized order with equal budgets, ties/`escalate` ⇒ higher tier or human; review can never override a failed required check; findings ≥ major ⇒ `Open` + `PIT`; assessments bind contract version, reviewed candidate/patch, criterion and evidence versions; changed dependencies invalidate approval (freshness `unknown` if unassessed); `scheduler.obtain_required_review_once` reuses a current approval; reviewer never recursively demands a review of its own verdict; `verify(review)` ⇒ review cell (human `Authority.review` remains the fallback).
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
- Build: `Tier { low, medium, high, extraHigh, deterministic }`, `TierTable` (versioned data with calibration date; profiles per tier), `FunctionTable` rows per §11.1 (default tier, effort per call class, escalate-when, never-below, if-unaffordable action); `Router.selectProfile(function, packet, impact, policy)`: `eligible` (capabilities, authorization, context fit, availability, user pins, calibrated quality floor) → `tier = max(function default, plan suggestion, risk_floor(risk, contracts_touched, fanin, reversibility))` (risk-floor table D-34) → `adjust_with_calibration` (conservative rules D-35) → `tier = max(tier, never_below(function), risk_floor)` (FX-55) → `affordable` = conservative estimate ≤ remaining − reserves → empty ⇒ `REFUSE(narrow_unit | checkpoint | ask_for_changed_constraint)` (never clamp; FX-32) → argmin expected total cost incl. retries/reviews/integration; `(function, tier, effort, outcome)` quadruples logged (`CalibrationLog`); helper calls never inside a worker loop; provider reasoning artifacts opaque per provider; cross-provider handoff transfers explicit goals/decisions/evidence/open questions only.
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
- Build: `Fingerprint = hash(normalized error (D-19), attempted fix, relevant state, affected requirement)` campaign-scoped; global no-progress budget across all cells; doom-loop guard (same tool + args ≥ 3 without a state change; a meaningful edit or new observation resets); per-tool error budgets; request caps; repeated-failure-signature gate (same normalized error after 2 repairs ⇒ nudge to change hypothesis / record dead end / request alternative).
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
- Build: FX-13, 26, 29, 30, 31, 32, 33, 34, 35, 36, 39, 41, 45, 53, 55.
- Done: green on both platforms.

#### P4.8.2 [V] Review, recovery and routing fixtures · TODO
- Build: labelled injected-defect diffs the review cell must catch (scripted judge responses validated against the protocol, not model quality); recovery ladder fixtures per failure class; routing table with fake profiles and budgets; warm-vs-cold KB comparison recorded as **deferred to live evaluation** (D-28).
- Done: protocol fixtures green; judge calibration on real models listed under P7.

---

## P5 Stage E — Scale (S3) and measured adapters
Goal: [§18.2 Stage E](docs/implementation/roadmap.md#sec-18-2): writer cells + integrator + merge queue, permission ladder + commit policy, L3 QA cells, L4 eval gates, tier-1/2 index, dense retrieval seam, generated tools, skills promotion, async checker seam (a learned routing corrector is added later without protocol change per [§11.1](docs/operations/routing.md#sec-11-1); nothing is scaffolded for it). Every feature here ships behind a flag with its ablation gate; S3 ships **off** by default ([§10.4](docs/operations/delegation.md#sec-10-4)).
**Fixtures:** FX-26 (writers), 27, 28, 39, 46 (tier-1/dense degradation), 49 (every shape), 51 (worktrees); zero unauthorized-stage publications · **Unsupported until later:** S3 vs sequential gate, language-service adapter parity, any live measurement (P7).

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
- Build: S3 iff `plan.units ≥ 2 ∧ disjoint(write_paths) ∧ no interface change in units ∧ contracts stable at fixed versions ∧ measured slack (D-39) ∧ S3.enabled`; never on the initial pass; max parallel cells 3; writer depth 1; leases with timeouts; cancellation before start and before publication; interface changes and design decisions never in S3 children; `Config.s3Enabled = false` default.
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
- Build: `TreeSitterIndex : SymbolIndex` via `io.github.tree-sitter:ktreesitter` + grammar artifacts for the D-09 languages; incremental syntax trees cached per `(path, version)`; precise outlines, declaration spans, imports; `tier = 1`, no cross-module resolution claimed; `Syntax` provider from ERROR nodes; native-library loading failures degrade to tier 0 with a reported degradation line (FX-46).
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
- Build: `campaigns/` frozen manifests (attempt config, harness version, flags, strata, repositories, partitions by repository/task family/time); comparator configurations B0, B-HELM, B1–B5, Target (B0/B-HELM need live providers ⇒ config only); every §19.5 ablation as a `Config` flag with its default; contamination checks: hidden acceptance outside the solver's workspace, answer-bearing patches/notes removed, mutable memory reset for cold runs and frozen/equal for warm runs, a repeatedly consulted selection set flagged as no longer a holdout (FX-47).
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
| Live evaluation | B0/B-HELM comparators, stage gates (B2 ≥ B1, warm vs cold KB, routing savings, S3 vs sequential), tier-table calibration suite, judge calibration on labelled fixtures with real models, offline improvement experiments | `eval` module (P6) |
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
| 49 | minimalism becomes underspecification ⇒ operational fixtures pass in every shape | P1 → P5 | P1.9.4, P2.7.1, P4.8.1, P5.8.1 |
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
| Invariants 1–12 ([§1.3](docs/architecture/principles.md#sec-1-3)) | 1–2: P1.1 · 3: P1.2.1/P1.5.3 · 4: P1.6.1/P1.7.4 · 5: P3.1.2 · 6: P1.4.3 · 7: P1.9.4/P5.1.3 · 8: P2.6/P4.1 · 9: P2.4.2/P2.5.3 · 10: P0.2.2/P4.4.1 · 11: P1.9.1 · 12: P2.2.5/P6.1.2 |

## 7 Final validation of this plan (performed 2026-09-20; re-run after any restructuring)
- [x] Every component of §3.2 and every workflow/protocol has tasks (§6).
- [x] Order respects dependencies: contracts and storage (P0) → S0 kernel (P1) → continuity (P2) → verification depth (P3) → knowledge/delegation/routing/recovery (P4) → S3/adapters (P5) → evaluation (P6); phases are strictly ordered, but within a phase `Deps` may point forward (e.g. P1.6.7 → P1.7), so §0.1 selects by `Deps`, not by listing order; no task needs a later phase's output except through an explicitly masked op or a registered hook.
- [x] Independent review (2026-09-20, fresh context) applied: facade/config tasks (P0.1.3, P1.9.6), `Deps`-based resume rule, D-33–D-42, `task.propose` (P2.1.5), trip evaluation, CAL split (P2.6.4/P4.2.1), fixture-list alignment with §5, role texts (P4.4.6), removal of the routing-corrector scaffold.
- [x] No hidden assumptions: every choice not in the docs is a `D-nn` row (§3) with a default or `OPEN`.
- [x] No invented subsystem: modules mirror §18.1; no service, queue, second database, orchestration framework, new role or new identity; optional features carry their §19.5 gates and ship off.
- [x] Deferred transport/provider work is isolated behind `ProviderAdapter`, `UsageNormalizer`, `Mount`/`McpClient`, `ConfinedRunner`, `LanguageService` (P7 table).
- [x] Compact for repeated use: §0–§3 (~350 lines) is the per-session header; phases are loaded per task via `Spec:` links; fixture map and coverage matrix are lookup tables.
- [x] Self-sufficient: task fields name types, packages, doc sections and completion criteria; the resume protocol (§0.1) needs only the docs + this file.
- [ ] Owner answers for D-01, D-02, D-09, D-12, D-15 (recorded in §1 as open).
