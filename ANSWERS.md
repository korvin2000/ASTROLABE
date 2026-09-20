# ASTROLABE implementation-plan answers

Review date: **2026-09-20**. Target: `TODO.md`, implementing **ASTROLABE 1.0.1-proposal** as a Kotlin/JVM SDK. This is a decision and review document, not an implementation or evidence that runtime fixtures pass. Additional defects, repair locations and acceptance tests are in [ISSUES.md](ISSUES.md).

## Authority and outcome

Use [README.md](README.md), its [architecture map](SOTA-BEST-MIXED-AGENT.md), and the current subsystem documents as the design authority. [PREPARE_IMPLEMENTATION_PLAN.md](PREPARE_IMPLEMENTATION_PLAN.md) supplies the Kotlin/Java, planning-only and deferred-transport requirements. [REVIEW.md](REVIEW.md) explains previous corrections but is not an overriding specification. The archived [1.0 proposal](sources/SOTA-BEST-MIXED-AGENT.md) was consulted only where current wording was incomplete or inconsistent.

**Recommendation: preserve the architecture and repair the plan before implementing its affected contracts.** Its component coverage, explicit evidence ownership, conservative recovery and transport separation are sound foundations. However, a task/fixture entry is not sufficient evidence of implementation readiness. Several proposed records and defaults leave incompatible interpretations, and some mandatory controls arrive after their first consumers.

The review covers all **42 D-decisions**, all **20 open risks**, the preparation brief, task ordering, and all **59 runtime / 10 adapter fixture mappings**. The declared task graph is acyclic under the plan's dependency rules; the unresolved problem is missing semantic prerequisites, not a demonstrated graph cycle.

Decision labels below mean:

- **Confirmed:** explicitly supplied by the user during this review.
- **Keep:** a defensible existing default, with the stated clarification.
- **Revise:** a proposed correction needed before the affected implementation task.
- **Provisional:** an engineering estimate or preference, not a measured optimum.
- **Deferred evidence:** implementation can define the experiment now, but cannot manufacture its result.

These recommendations do not silently amend `TODO.md` or the specification. Where an answer corrects normative ambiguity, adopt the matching issue repair in the owning subsystem and then update the plan. No source document or implementation has been changed by this review.

## Owner questions and practical defaults

**D-01 is resolved:** the user confirmed ownership of `astrolabe.io`. Use group/root package **`io.astrolabe`** and artifact prefix **`astrolabe-`**. Registry verification and publishing credentials remain release work; this confirmation does not authorize a release.

**D-02 and D-12 are resolved:** after discussing current toolchain support, the user selected **JDK 26**. Use JDK 26 for builds, Java/Kotlin targets and the supported SDK runtime; there is **no Java 17/21 compatibility requirement**. Windows and Linux are equally required platforms, with their OS-contract tests starting in P0. The user's current test machine is Windows, not a reason to defer Linux support.

For **D-09**, retain Python → JS/TS → JVM as provisional implementation order, with all three represented in P1 fixtures. The user did not request a different repository-language order.

**D-15 needs no permission question:** current [state ownership §4](docs/state/contracts.md#sec-4) explicitly places canonical state outside the source tree. Choose external project storage and do not modify `.git/info/exclude` by default. This resolves the contradictory location/exclusion wording without expanding authority.

No remaining architecture question requires an immediate owner answer to finish these documents. Deployment backend, publishing credentials, live provider choices, actual cost/quality measurements and eventual UI preferences are later inputs to their own tasks.

## Answers to D-01–D-42

The IDs below refer to [TODO §3](TODO.md#3-decisions-and-open-items). Detailed requirements remain in the cited current subsystem, not in the older proposal.

### Build, SDK and platform decisions

| ID | Disposition and answer | Reason / implementation consequence |
|---|---|---|
| D-01 | **Confirmed:** `io.astrolabe`, `astrolabe-*`. | User confirmed domain ownership. Finalize before ABI/package publication; no speculative namespace change is needed. |
| D-02 | **Confirmed:** JDK 26 for build toolchain, Java/Kotlin targets and supported runtime. | Set Java `--release 26` and Kotlin `-Xjdk-release=26` consistently. Test Java/Kotlin consumption on JDK 26 on both OSes; no older-runtime matrix is required. The user's specific JDK 26 choice supersedes the earlier general preference for latest JDK. |
| D-03 | **Keep:** `sqlite-jdbc`, explicit SQL and a small transaction/migration helper. | SQLite owns structured state; do not add an ORM. Enable foreign keys per connection, specify durability and transaction boundaries, and keep blob publication before its referencing transaction. See [I-21](ISSUES.md#i-21). |
| D-04 | **Keep:** Git CLI, temporary index and expected-old ref updates. | This matches the required Git semantics without another abstraction. Treat Git 2.20 as a proposed minimum to test, not a substitute for feature/platform tests. Remove the unverified blanket claim about JGit weaknesses. Raw recovery snapshots must bypass content conversion: [I-12](ISSUES.md#i-12). |
| D-05 | **Revise:** ripgrep with a documented common-subset JVM fallback. | Define literal/regex mode, supported syntax, ignore rules, hidden/binary files, Unicode/case rules and symlink policy. Java and ripgrep regex engines cannot be assumed interchangeable. Unsupported patterns fail explicitly; zero matches, partial search, failure and denial remain distinct. |
| D-06 | **Revise:** heuristic estimates for planning; a profile-specific admission contract for dispatch. | `bytes/3.6` is not a proved upper bound. Record estimator/version, uncertainty and headroom; include effective history and all serialized contributions. Unknown continuation size requires fresh lineage or a capacity result. See [I-17](ISSUES.md#i-17). |
| D-07 | **Revise:** retain Kotlin `suspend`/`Flow`, add Java-friendly inbound SPIs as well as the facade. | Java must implement provider and authority callbacks without coroutine internals. Define future cancellation, exception mapping, callback threading, resource closure and bounded event delivery. Test a Java-authored adapter and authority: [I-14](ISSUES.md#i-14). |
| D-08 | **Keep:** validated public data classes. | Avoid public value-class mangling and Kotlin `Result` in the Java surface. Use immutable collections/defensive copies at authority boundaries; typed IDs do not by themselves validate nonempty or canonical values. |
| D-09 | **Provisional:** Python, then JS/TS, then JVM; all three represented in P1 fixtures. | Preserve the planned breadth but implement one end-to-end runner slice first. `node --check` must not claim general TypeScript validation; project-specific TS checking belongs to its declared checker. Missing required runners yield `unavailable`; unsupported inline syntax yields `not_run`. |
| D-10 | **Keep with clarification:** cheap language syntax check, optional parser later, truthful unsupported result. | Define accepted file types and parser completeness. For tree-sitter, account for both error and missing syntax nodes; a syntax receipt concerns the exact file version and is not behavioral acceptance. |
| D-11 | **Keep:** trusted-local implementation, confined backend contract only. | If a host requires confinement, fail configuration/dispatch rather than substituting trusted-local. An execution label describes limitations; it does not enforce network, credential or filesystem isolation. [Security §14.1](docs/platform/security.md#sec-14-1). |
| D-12 | **Confirmed:** Windows and Linux are equal support targets, tested from the OS adapter's first task. | P0.6.1 already promises both. Current hands-on testing is on Windows. Select an actual native process-control mechanism before claiming job-object/process-group semantics. macOS and other modes remain unsupported until tested. [I-20](ISSUES.md#i-20). |
| D-13 | **Revise:** versioned canonical environment fingerprint. | Include resolved tool/checker/parser identities, relevant effective environment values represented by protected digests, build flags, lock/dependency state, runner policy and external fixture identities. An env allowlist's names alone do not capture its values. Unknown relevant inputs prevent cross-candidate reuse. |
| D-14 | **Keep with a stricter boundary:** redact exposed/reusable artifacts, preserve exact protected recovery data separately. | Regex coverage is heuristic. Record redaction limits; do not expose raw logs through recall/export or turn signed opaque provider items into invalid replay artifacts. Redacted source ranges do not grant authority over hidden bytes: [I-10](ISSUES.md#i-10). |
| D-15 | **Revise:** external, durable, per-project state root; no Git exclusion mutation. | Prefer host-configured storage or an OS user-state directory keyed by canonical repository identity, independent of the changing tree hash. Worktrees share the project store and retain distinct workspace IDs. Do not put authoritative state in a disposable cache. [I-21](ISSUES.md#i-21). |

### Context, tools and integration contracts

| ID | Disposition and answer | Reason / implementation consequence |
|---|---|---|
| D-16 | **Provisional:** retain S/M/L thresholds as frozen policy estimates. | Risk, required review, authority and unavailable capabilities override file counts. Incomplete discovery is `unknown`, not low risk. Recalibration needs outcome evidence. |
| D-17 | **Revise:** bounded digest of the current authorized objective, not always the first request. | Prioritize objective excerpt, obligation IDs/status and critical exclusions; label truncation deterministically, even when the first sentence exceeds 150 tokens. The full applicable acceptance definitions must remain mandatory in `[K]` and review packets: [I-11](ISSUES.md#i-11). |
| D-18 | **Keep:** deterministic greedy admission after mandatory content. | Expand transitive note dependencies, detect cycles, and price marginal added tokens so shared dependencies count once. If mandatory content cannot fit, rescope/raise profile or return a capacity result. This is a bounded heuristic, not an optimal-cover claim. |
| D-19 | **Revise:** normalize only identified volatile data. | Preserve workspace-relative path/module, error code and meaningful literals; basename-only paths and removal of every number merge unrelated failures. Store a coarse error signature separately from the full attempt fingerprint, so a changed attempted fix cannot erase repeated-error detection. |
| D-20 | **Revise:** seven logical families; stable schemas within a validated adapter/profile/role lineage. | Prefer masking where valid. [Adapters §15.1](docs/platform/adapters.md#sec-15-1) explicitly makes this conditional on provider correctness. A new lineage may select a supported schema; local execution always enforces the mask. [I-24](ISSUES.md#i-24). |
| D-21 | **Keep:** mount/catalog contracts in core; MCP networking deferred. | Server annotations are hints. Local approval and capability validation determine effects and dispatch rights; descriptor text remains data. [Adapters §15.3](docs/platform/adapters.md#sec-15-3). |
| D-22 | **Keep:** one adapter contract, routed helper profiles and fakes. | All helper calls consume the originating budget and obey cancellation and evidence rules. A scripted judge tests its protocol; it cannot demonstrate judgment quality or savings. |
| D-23 | **Keep and make reachable:** authorized human review in Stage C. | Permit sequential execution when human review satisfies the required missing capability; do not pretend that it supplies probes or routing. Without an available required reviewer, return `blocked`. [I-03](ISSUES.md#i-03). |
| D-24 | **Keep:** fixed export-only YAML subset, no YAML parser. | Quote/escape values deterministically. SQLite remains canonical. Do not add Markdown import/recovery merely to justify `RegisterParser`; recover typed records, with Markdown used for views and golden tests. |
| D-25 | **Keep with versioning:** JSON records and opaque provider-native payloads. | Persist schema version and provider/model/protocol provenance. Replay required opaque material unchanged only on compatible lineage; transfer explicit task/evidence data across providers. Protected native replay storage and redacted presentation are separate views. |
| D-26 | **Revise:** structured coroutine lifetimes, bounded parallel reads, serialized workspace mutation. | A process-local mutex cannot exclude another SDK process. Baseline policy: one controller process per project store, enforced by a host/OS lock; its S3 workers remain inside that owner. Cancellation performs bounded evidence/reservation cleanup and never loses late effects. Slow event listeners must not hold mutation or database locks. |
| D-27 | **Revise:** namespaced runner-native test identities. | Include check, project/module, file/suite and parameterization as needed, not just JUnit `class#method` or a Jest title. Preserve duplicates rather than overwriting a map entry. Ambiguous matching cannot prove pre-existing failure/equivalence. [I-13](ISSUES.md#i-13). |
| D-28 | **Deferred evidence:** build the offline harness now; record live gates as `UNMEASURED`. | Separate engineering completion, runtime fixture validation and architecture promotion. Fakes cannot satisfy B0/B-HELM, memory, routing or S3 economic/quality gates. [I-19](ISSUES.md#i-19). |
| D-29 | **Keep with qualification:** S/R/K/T breakpoints are logical hints. | The adapter maps supported mechanisms, limits and minimums, or uses uncached operation if policy permits. Fake cache behavior is a deterministic simulation, not a prediction of provider hits. Preserve billable cache-write classes: [I-16](ISSUES.md#i-16). |
| D-30 | **Revise:** campaign-global persisted display aliases plus canonical evidence IDs. | Never reset `#n` per cell. Allocate aliases transactionally; retain context/workspace provenance. Dedup requires semantically equal arguments and sufficient live coverage, not merely `(what,target,version)`. [I-06](ISSUES.md#i-06), [I-07](ISSUES.md#i-07). |

### Policy and empirical defaults

| ID | Disposition and answer | Reason / implementation consequence |
|---|---|---|
| D-31 | **Keep with explicit authority semantics:** default task scope is repository-local with protected defaults. | Scope bounds possible writes; it does not authorize unrelated work. Already-authorized CI/lockfile/migration changes should be represented in the committed contract rather than blocked by a new blanket prompt. Basic enforcement must exist in S0: [I-02](ISSUES.md#i-02). |
| D-32 | **Revise:** discovery proposes rules files; host policy establishes trust. | Only an explicitly configured/authorized path and snapshot become instructions. A file named `AGENTS.md` or `CLAUDE.md` is not automatically trusted merely because it is pinned. Reuse an approved snapshot across resumes; changed bytes do not inherit approval. [I-08](ISSUES.md#i-08). |
| D-33 | **Clarify before implementation:** exact matching first; tightly specified normalization fallback. | Current §9.1 retains whitespace-normalized matching, so do not silently remove it. Define normalization and its original-byte span mapping; apply `near`, then require exactly one nonoverlapping current displayed span. Semantically ambiguous whitespace/string cases should refuse with candidates, not choose a target. A stricter exact-only baseline would be an explicit spec amendment. |
| D-34 | **Revise:** include declared packet risk explicitly in the floor. | Contract touch, fan-in and blast radius supplement risk; they cannot erase a high-risk obligation whose dependencies were not discovered. Preserve function/risk floors after calibration. [I-23](ISSUES.md#i-23). |
| D-35 | **Provisional, automatic demotion off:** retain thresholds only as experimental configuration. | Fifty successful high-tier calls do not demonstrate competence of a cheaper tier. Demotion needs comparable, verified outcomes for that proposed tier and a frozen/shadow evaluation; sample thresholds alone are not a quality guarantee. [I-23](ISSUES.md#i-23). |
| D-36 | **Keep:** one serialized KB write path, with explicit authority per operation. | Harness STATUS records are same-task checkpoints; the curator publishes admitted reusable knowledge. Shared writer plumbing does not turn a model proposal into a harness fact. [Knowledge §4.5](docs/knowledge/records.md#sec-4-5). |
| D-37 | **Provisional:** retain weights only after defining feature ranges and units. | Use `tokens/500` for the proposed length penalty; define bounded scope/dependency/freshness/use/evidence features. Staleness is an eligibility check before ranking. Mandatory CON/invariant content bypasses optional ranking caps but still counts toward total admission. Tune from held-out usefulness and failure data. |
| D-38 | **Keep:** frozen, versioned role text with host overrides. | Overrides change wording and defaults, never executor authority or acceptance obligations. Preserve mandatory shared policy even if it exceeds a cosmetic line count. Record the text version in attempt/compile fingerprints. |
| D-39 | **Provisional:** retain ambiguity/slack predicates as logged estimates. | No reproducible failing check is uncertainty, not proof of a particular defect. Slack must include reservations, review, verification, integration and uncertainty in consistent units. `1.5×` is not a measured optimum and cannot enable S3 before promotion. |
| D-40 | **Revise:** lexical/hub pre-scan is incomplete discovery. | Record its coverage, unresolved dependencies and explicit risk. No hits cannot establish no contract impact. Refresh when actual touched symbols/paths become known and stop/upgrade before a now-disallowed action. [I-23](ISSUES.md#i-23). |
| D-41 | **Keep with a firm limit:** trusted-local transforms may run only when that mode is authorized. | A post-hoc diff is not a jail and cannot disprove outside/network effects. Use inventories, preimages, expected counts, honest effect status and required checks/review. A confinement requirement returns unsupported until the P7 backend exists. |
| D-42 | **Keep:** deterministic P2 statistics; optional prior; later admitted CAL path. | Do not inject both representations or treat model-written CAL text as measured statistics. Retain provenance, versioning and decay, and leave the prior's benefit unproven until its ablation. |

### Verified build and provider facts

Kotlin **2.4.20 is a real release**, dated September 7, 2026; do not replace the requested version as a presumed typo. Its documented Gradle compatibility range includes 9.7.0. Recommend pinning **Gradle 9.7.0**, with a wrapper checksum and explicit dependency versions at P0, rather than a moving “newest compatible” instruction. [Kotlin release history](https://kotlinlang.org/docs/releases.html), [Kotlin/Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html).

The owner-selected **JDK 26** is supported by the documented Gradle runtime/toolchain matrix and Kotlin target range. Configure Kotlin `-Xjdk-release=26` and Java `--release 26`, with the selected Gradle daemon and test runtime also on 26. [Kotlin compiler options](https://kotlinlang.org/docs/compiler-reference.html), [Gradle's Java compatibility table](https://docs.gradle.org/current/userguide/compatibility.html).

JDK 27 became GA on September 15, 2026, but currently documented Gradle runtime support and Kotlin targets stop at 26. This was explained to the user, who explicitly answered **“Use JDK 26.”** No temporary JDK 27 runtime requirement or unresolved build-policy question remains. Future JDK upgrades are deliberate version changes after tooling validation. [Oracle JDK 27 release notes](https://www.oracle.com/java/technologies/javase/27all-relnotes.html).

Billable usage must retain provider-defined subcategories. For example, Anthropic distinguishes five-minute and one-hour cache writes and reports them separately; collapsing both into one `cacheWrite × rate` loses pricing information. The contract can model this now using fake usage fixtures without implementing networking. [Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching).

## Cross-cutting decisions needed beyond the D-table

### Evidence identity and acceptance

Candidate identity hashes a versioned canonical encoding of actual candidate content/manifests and environment. Capture timestamp `at` is metadata and is excluded from candidate equality. `FileVersion` hashes raw bytes. Metadata caches can accelerate navigation but cannot establish CAS authority at mutation, verification or publication boundaries. Workspace identity remains distinct from content identity. See [identities §3.3](docs/architecture/components.md#sec-3-3) and [I-05](ISSUES.md#i-05).

A receipt supports the candidate actually checked. Preserve historical outcome separately from current applicability. If a checker mutates a relevant input, its successful exit does not automatically verify the resulting tree. Generated scratch artifacts may be excluded only through declared, complete input/scratch policy. Unknown isolation/input stability is insufficient for a required current acceptance receipt. Run on a stable candidate and bind the evidence to its manifest, then establish applicability to the destination. [I-04](ISSUES.md#i-04).

Applicable acceptance definitions, original obligations, command/criterion text, authority origin and obligation version belong in mandatory compiler/review input. IDs alone are sufficient for an anchor digest, not for a judge. Assessment acceptance still belongs to the verifier/user; this change does not ask the model to certify its own evidence. [I-11](ISSUES.md#i-11).

### Smallest viable storage and recovery contract

Keep one canonical SQLite store with content-addressed artifacts; do not add another service. Use a non-disposable external project directory with restricted recovery/native-protocol material. Enforce one owning controller process initially; the same process may schedule isolated S3 workspaces.

Document whether recovery covers process termination only or also OS/power failure. Recommended local durable mode is SQLite WAL with explicit `synchronous=FULL`, durable artifact publication before metadata commit, and fail-closed handling of unsupported flush/rename guarantees. Atomic rename alone is not the durability contract. SQLite distinguishes these guarantees by synchronous mode. [SQLite WAL](https://www.sqlite.org/wal.html), [synchronous modes](https://www.sqlite.org/pragma.html#pragma_synchronous).

The plan may use a compact native platform binding or helper for process groups/job objects; it must name and test the mechanism. Persist backend identity, output location/cursor and terminal status independently of the requesting coroutine. A cancelled wait, runtime timeout, confirmed process termination and lost process are different outcomes. An unrecoverable process handle becomes `lost/unknown`, never a duplicate launch. [Recovery §13.1](docs/operations/recovery.md#sec-13-1), [I-20](ISSUES.md#i-20).

### Public interfaces and events

Keep `provider-api` independent of `core`: provider-owned DTOs and SPI types live there; core policies consume them. Put token-estimation abstractions needed by `Request` in that independent module, or implement estimation as a core extension. Introduce only records needed by real consumers; the full future database schema and every policy implementation need not exist in P0.

Use one internal async engine with Kotlin and Java adapters. Give outbound events sequence numbers and documented buffering/replay semantics. A listener failure must not abort an edit after its effects but before its receipt. Authoritative state remains in the journal/views; a UI can detect dropped notifications and resynchronize. Inbound authority replies must identify their pending question/review/amendment and contract revision, and be revalidated when they arrive late. These are SDK boundary clarifications, not a new UI subsystem.

### Engineering sequence versus demonstrated architecture value

The user's transport exclusion makes **offline implementation through P6** a sensible deliverable. The literal architecture statement that no stage starts before its preceding live gate is measured cannot simultaneously be met with live gates deferred to P7. Record this as an explicit planning-scope interpretation:

1. `IMPLEMENTED`: named artifacts and integration paths exist.
2. `FIXTURE_VALIDATED`: the applicable runtime/adapter contracts passed their real harness tests.
3. `PROMOTED`: predeclared live quality, cost and regression gates passed.

During this review all implementation/runtime statuses remain unexecuted. During future offline work, the third status remains `UNMEASURED`. Optional layers remain off for normal use until their own evidence exists. Mandatory correctness controls stay enabled in every supported shape. [Roadmap §18.2](docs/implementation/roadmap.md#sec-18-2), [evaluation §19.1](docs/evaluation/method.md#sec-19-1), [I-18](ISSUES.md#i-18), [I-19](ISSUES.md#i-19).

## Answers to the 20 open architecture risks

These rows answer [risks §21](docs/reference/risks.md#sec-21). They define an initial policy and the evidence that could overturn it. **No live result is available**; numerical “best settings” would be invented. Use the existing [evaluation method](docs/evaluation/method.md), matched budgets and held-out strata rather than a separate research subsystem.

| Risk | Best current answer / action | Evidence and reversal rule | Owning plan tasks |
|---|---|---|---|
| R01 Decomposition quality | Keep increments, continuation cells and durable coverage; log actual versus expected size. | Compare B2 with B-HELM on long/migration strata; measure continuations, rebuilds and boundary cost. Revert the boundary policy only if equivalent quality makes its overhead unjustified. | P2.1.4, P2.6.4, P2.7.3, P6/P7 |
| R02 Anchor cost | Keep authoritative status/digest; trim optional focus material first on short tasks. | Measure billed anchor share and drift per stratum. A smaller anchor is useful only without lost constraints or higher downstream cost. | P1.8.3, P1.11, P6/P7 |
| R03 Stale bytes before batching | Keep immediate stale marking and revocation of displayed authority; defer physical removal per current policy. | Zero stale-authorized edits is mandatory. Compare immediate versus batched stubbing for rejection/confusion and money; tune 800 tokens / `k`, not the authority rule. | P1.5.3, P1.8.6, P6/P7 |
| R04 Checker latency | Keep synchronous bounded checker first, with truthful `not_run` versus `timeout`. | Record p95 latency and unfinished checks; try incremental tier-2 support before async watchers. Adopt async only with supersession and a successful ablation. | P1.7.2, P5.4.2, P5.7 |
| R05 Tool breadth | Keep seven logical families and enforce supported schemas/masks. | Compare invalid calls and accepted-task cost against the smaller tool arm. Remove losing conveniences at a version boundary; never trade protocol validity for cache stability. | P1.6.1, P6/P7 |
| R06 Test-integrity precision | Use a labeled heuristic, with conservative handling when analysis is unavailable. | Measure false positives/negatives on per-language weakening fixtures; review weakened obligations, not every legitimate test edit. Never infer safety from no flag. | P1 baseline guard, P3.4.2, P4.8.2 |
| R07 Wrong codemod | Use target inventory/counts, preimages, full diff, unusual sites, affected checks and review. | Inject compilable but semantically wrong transforms; inspect escaped regressions. Narrow scope/require review when evidence is insufficient. Passing samples do not prove all targets correct. | P3.3, P3.8.2, P4.4.3 |
| R08 Harmful memory | Keep provenance, freshness exclusion, scoped admission, quarantine and rollback. | Off/frozen/live comparisons on held-out tasks; harmful stale injections block promotion. Memory never grants authority or substitutes for current acceptance. | P2.6.3, P4.1, P6/P7 |
| R09 Evidence ontology overhead | Store active criteria, actual observations and explicit dependencies; retain direct search. | Track upkeep cost and stale edges against acceptance gain. Delete speculative derived relationships before weakening evidence retention. | P1.4, P2.6, P1.11 |
| R10 Compiler omissions | Require mandatory definitions and omission manifests; allow targeted complement retrieval. | Trace ask-backs/repairs caused by missing evidence; compare a simpler context policy. Relax optional budgets or rescope rather than silently omit authority. Presence checks do not prove understanding. | P2.3, P4.4.2, P6/P7 |
| R11 Routing drift | Use dated profile data, explicit risk/function floors and a capable default. | Evaluate proposed tier on comparable difficult tasks; restore capable routing for failing strata. Aggregate savings cannot offset complex-stratum loss. | P4.5, P6/P7 |
| R12 Ritual review | Supply complete obligations/evidence and measure the reviewer itself. | Labeled injected defects, false findings, confirmed defect yield and total review cost. Narrow optional triggers where justified; preserve required reviews and human fallback. | P3.5.2, P4.4.3, P4.8.2 |
| R13 Hidden parallel dependencies | Keep S3 off initially; require stable contracts, disjoint ownership and combined verification. | Compare S3 with sequential work under equal total resources. Serialize task families with repeated semantic integration repairs; do not add another coordinator. | P5.1, P5.8, P6/P7 |
| R14 OS recovery assumptions | Declare supported process/filesystem modes and test real platform faults. | Orphaned jobs, ambiguous replacements or lost status fail that capability. Restrict support rather than claiming it from a JVM abstraction. | P0.6.1, P1.12.4, P2.2.4 |
| R15 Precompile waste/staleness | Reuse only on complete compile-input equality at consumption. | Zero stale seeds; measure hit rate, wasted work and p50/p95 boundary latency. Disable when costs exceed benefit; local compilation never proves a warm provider cache. | P3.7, P1.11 |
| R16 Calibration overfit | Keep a decayed, provenance-linked optional prior; do not bypass planning. | Held-out repositories/task families, on/off comparison and overrun drift. Disable the prior if it fails to improve sizing without quality loss. | P2.6.4, P4.2.1, P6/P7 |
| R17 Evaluation leakage | Freeze development/selection/final partitions and memory conditions. | Audit answer-bearing artifacts and selection-set reuse; replace compromised final sets and report failed transfer. Hidden answers stay outside solver access. | P6.1.2, P6.2, P7 |
| R18 Harness bugs | Treat invariants as eligibility, not score tradeoffs. | Fault injection plus adversarial fixture failures block the affected capability. A benchmark win cannot excuse false-green, unauthorized publication or evidence loss. | Every phase, P6.1.1 |
| R19 Interactive transparency | SDK events/views/finish receipts are sufficient for this scope, provided reasons and pending actions are explicit. | Later host usability tests classify “unclear state” interventions. UI design is deferred; no need to invent a frontend now. | P0.4, P1.9.5–6, P1.12.3 |
| R20 Seeds/notes/skills split | Mandatory first, then current source seeds and relevant optional material under total admission. | Record usefulness, retrieval misses, later repairs and per-stratum cost. The 4K seed / 8-note / 1.5K limits are initial caps, not universally optimal allocations. | P2.3, P4.1.3, P4.3, P6/P7 |

## Fallback decisions and unresolved evidence

The archived 1.0 proposal did not provide a superior replacement for the remaining gaps:

- Its §6.2 already says recall is campaign-scoped, supporting globally unambiguous evidence aliases; it does not justify D-30's per-cell reset.
- Its §6.1 also abbreviates acceptance to IDs/kinds, so the compiler omission must be repaired using the current authority/review invariants, not by copying older pseudocode.
- Its §5.4 repeats the underspecified dedup tuple; it does not solve changed scope/budget/coverage requests.
- Its §15.1 already qualifies universal masking as a heuristic, supporting the current adapter rule over D-20's absolute wording.
- Its §4 keeps state outside the source tree. Its earlier attempt-count and authority wording must not undo the corrections already applied in 1.0.1.

The remaining uncertainty is empirical or implementation-specific: actual solver quality, cache savings, judge quality, language-parser coverage, OS guarantees and optimal thresholds. Each now has a task, evidence requirement or explicit unsupported result. Public API/storage/evidence contracts should be settled before code; tuning should follow measured behavior.
