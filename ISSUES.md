# ASTROLABE implementation-plan review issues

Review date: **2026-09-20**. Reviewed: [TODO.md](TODO.md), [preparation brief](PREPARE_IMPLEMENTATION_PLAN.md), and the current **1.0.1-proposal** documents reached from [README.md](README.md). Answers and recommended defaults are in [ANSWERS.md](ANSWERS.md).

**Verdict: retain the architecture; revise the implementation plan before treating it as ready for unattended execution.** The major components and all declared fixture IDs have implementation paths. The remaining problems concern exact contracts, early enforcement, stage reachability and what evidence can support a claim. They do not justify replacing the campaign/cell architecture or adding another orchestration framework.

## Reading and evidence conventions

These are document-derived failure scenarios, not observed defects in a running agent. There is no agent implementation to test. **High** means a plausible authority, evidence, recovery or required-workflow failure; **Medium** means an important interface, validation, portability or methodological gap. Severity applies when the affected capability is implemented literally; it is not a claim of an existing exploit.

`TODO L<n>` cites the reviewed file's one-based line numbers; task/decision IDs are the durable locator. Its SHA-256 is `4b794c53943908c62e979589aba89dd8cdef859fbc51e14ea1d2929461bdf735`. Links point to the owning specification sections. `I-*` is this review's namespace, separate from the existing `REVIEW.md` F01–F12 and the architecture's F1–F28 taxonomy.

There are **24 additional plan/specification findings** and one **resolved owner-decision update** (I-25), still to propagate into TODO. Existing broad risks are not counted again as newly discovered risks: for example, F04 already discusses false greens, but it does not resolve a check modifying its own inputs after testing them. Each issue below identifies the remaining mechanism and a concrete regression fixture.

## Priority map

| Issue | Severity | Repair before | Subject |
|---|---|---|---|
| [I-01](#i-01) | High | P0 contract work | Task readiness omits real producers and module boundaries |
| [I-02](#i-02) | High | First S0 mutation | Mandatory scope/integrity/configuration controls arrive too late |
| [I-03](#i-03) | High | Stage C integration | Human review exists but shape selection prevents reaching it |
| [I-04](#i-04) | High | First acceptance receipt | A check can certify an untested post-mutation candidate |
| [I-05](#i-05) | High | Workspace identity contracts | Metadata cache and stamp equality lack correctness rules |
| [I-06](#i-06) | High | Evidence ID contract | Per-cell result aliases collide across carry-forward/resume |
| [I-07](#i-07) | Medium | Observation/dedup contract | Dedup ignores query semantics and live coverage |
| [I-08](#i-08) | High | Campaign-open rules loading | Discovery and pinning do not establish instruction authority |
| [I-09](#i-09) | High | Built-in filesystem operations | Scope checks lack canonical path and alias semantics |
| [I-10](#i-10) | Medium | Observation/Workset integration | Redacted source is not fully displayed source |
| [I-11](#i-11) | High | Context/assessment contracts | Mandatory slices omit full acceptance definitions |
| [I-12](#i-12) | High | Snapshots and baseline materialization | Git snapshots need not preserve working bytes |
| [I-13](#i-13) | High | Parser/baseline integration | Report provenance and test identity are incomplete |
| [I-14](#i-14) | Medium | SDK ABI freeze | Java facade omits Java-implementable extension interfaces |
| [I-15](#i-15) | High | Provider SPI freeze | Cancellation lacks a usable in-flight identity/settlement path |
| [I-16](#i-16) | Medium | Usage/pricing contract | Cache-write aggregation loses billable distinctions |
| [I-17](#i-17) | Medium | Request admission | Average token heuristic is not a hard capacity bound |
| [I-18](#i-18) | High | Public configuration | Experimental ablations can weaken production invariants |
| [I-19](#i-19) | Medium | Stage/progress definitions | Offline completion conflicts with live advancement gates |
| [I-20](#i-20) | High | OS adapter | Process-tree termination and restartable handles need a backend design |
| [I-21](#i-21) | Medium | Storage layout | External state, ownership and durability remain ambiguous |
| [I-22](#i-22) | Medium | Phase validation registry | FX-49 is missing from S1/S2 validation lists |
| [I-23](#i-23) | High | Routing/shape policy | Unknown impact and current-tier success do not establish a lower floor |
| [I-24](#i-24) | Medium | Provider/schema contract | Universal schema masking conflicts with adapter qualification |
| [I-25](#i-25) | Medium; decision resolved | P0.1.1 | Propagate confirmed JDK 26 and equal Windows/Linux support |

<a id="i-01"></a>

## I-01 — Acyclic dependency metadata does not establish task readiness

**High · plan defect.** TODO L9 makes `Deps` authoritative and L1285 claims no task requires later-phase output. A read-only expansion found **174 leaf tasks: 33 explicit dependency declarations and 141 implicit ones**. Under phase barriers and expanded work-package/range references, all references resolve and the graph is acyclic. That does not validate artifact availability.

Concrete missing prerequisites include `Config` using roles/tier policies before their contracts exist (P0.1.3, L158); `Budget` using `Money` from the later P0.3.3 (L169/185); P1 digest/compiler/gate code consuming `Increment`/`Ledger` first declared in P2.1.1 (L274/461/523/605); P1.9.4 requiring P1.11.2 accounting without a dependency; and P3.1.1 requiring impact-derived closures in its completion criterion before P3.2. A second boundary problem is P0.3.2's `Request.estimateTokens(TokenEstimator)` referencing a type assigned to `core/budget`, although `core` already depends on `provider-api`.

**Repair:** create minimal shared record contracts before their first consumer; leave full graph/routing behavior in later phases. Place provider-required abstractions in `provider-api`, or keep request estimation as a core extension. Add cross-package dependencies explicitly and distinguish a declared hook from completed integration. Avoid scaffolding future implementation classes merely to satisfy names. This follows the [component ownership contract](docs/architecture/components.md#sec-3-2) and the preparation brief's dependency-first requirement.

**Acceptance:** maintain a compact artifact-to-producer table; walk every task's imports and completion criteria against its dependency closure; compile each completed slice. A DAG check alone is not sufficient.

<a id="i-02"></a>

## I-02 — S0 can mutate before essential enforcement is assigned

**High · plan sequencing defect.** P1.6.4's preflight (L390) specifies CAS, displayed ranges and unsupported kinds, but full committed-contract scope enforcement is assigned to P3.4.1 (L840). Test-integrity weakening detection/review arrives in P3.4.2 (L845); P1 merely reserves later fields. Attempt configuration is frozen only in P2.2.5 (L658), despite invariant 12 applying to every attempt.

The P1 capability ceiling is valuable, but it does not expressly implement all three missing duties. A P1 agent can edit a normal-path acceptance test inside its broad write scope, get a passing suite, and have no implemented weakening-review path. It may also follow an authorized narrower contract without a task that enforces that scope on built-in edits. These are baseline obligations under [invariants §1.3](docs/architecture/principles.md#sec-1-3) and [scope/acceptance §8.6](docs/verification/acceptance-review.md#sec-8-6), not optional deep analysis.

**Repair:** move basic contract/protected-scope checks and `AttemptConfig` freezing into P1 before first dispatch. Add a conservative acceptance-surface policy in P1: changes to known tests/check definitions require a supported review path or remain unaccepted; unknown classification is not proof of no weakening. P3 may refine precision and add workflows. Preserve already-authorized legitimate test edits without silently accepting weakened behavior.

**Acceptance:** S0 fixtures reject an uncommitted scope expansion, cannot complete after weakening a required check, and ignore mid-attempt configuration changes. Run them before P2/P3 exists.

<a id="i-03"></a>

## I-03 — Stage C's permitted human-review path is unreachable

**High · plan contradiction.** P2.2.1 (L636) blocks every S2 selection until P4. Contract touches, high risk and explicit review items select S2. Yet P3.5.2/P3.8.2 (L861/887) require a human-reviewed migration and rename before P4. The current [roadmap §18.2](docs/implementation/roadmap.md#sec-18-2) explicitly permits human review for Stage C, as does the [shape activation rule](docs/architecture/roles-shapes.md#sec-3-5).

**Failure:** a Stage C contract-touching migration is blocked before reaching a configured human reviewer.

**Repair:** select required capabilities independently from which automatic roles are implemented. Allow sequential execution with an authorized human satisfying the required-review capability; record that substitution. Do not let human review imply availability of probes, routing or repair helpers.

**Acceptance:** the migration completes with a valid human verdict, blocks without one, and still blocks if it requires another unavailable capability.

<a id="i-04"></a>

## I-04 — Receipt currency can certify bytes that were never tested

**High · residual specification gap carried into the plan.** P1.7.4 (L444) uses `stampAfter == stampNow`. The same after-only shortcut appears in [scheduler §8.4](docs/verification/scheduler.md#sec-8-4). P3.1.5 (L788) adds a mutex/isolated candidate but permits a `no isolation` limitation without defining its effect on acceptance.

**Failure:** a test loads source A, passes, then teardown rewrites it to B. Before=A, after=B, now=B: the receipt appears current for B. A Kotlin mutex does not stop the command itself or an already-running background process from writing. An isolated but writable copy also does not prove stable inputs. A writer that changes and restores a file during a check defeats before/after equality too.

**Repair:** define `tested_inputs` and their stability from Stage A. Authoritative checks need frozen source inputs or an enforced writer exclusion covering subprocesses, plus declared scratch/output policy. A relevant input mutation preserves the factual invocation outcome but makes it insufficient to accept final B; reconcile and rerun. Only explicitly supported semantics may certify a generated output. Unknown isolation or dependency stability must affect evidence eligibility, not just prose in `limits`. Cross-candidate reuse remains subject to §8.1's complete-closure proof.

**Acceptance:** passing test followed by source mutation, mutate-and-restore background writer, and mutation inside an isolated candidate cannot certify untested final bytes. Allowed scratch output does not spuriously invalidate a complete declared input closure.

<a id="i-05"></a>

## I-05 — Content identity must not depend on metadata-cache guesses or capture time

**High · plan defect plus identity clarification.** P1.2.1 (L281) prescribes an “mtime/size-validated hash cache.” P0.2.1 (L164) includes `at` in `Stamp` without defining hash/equality exclusions, while P1.2.2 requires stable stamps for equal trees across runs (L289). Current [identities §3.3](docs/architecture/components.md#sec-3-3) and [coherence §4.4](docs/state/evidence-coherence.md#sec-4-4) require content versions.

**Failure:** equal-length external bytes replace a read file while retaining its timestamp; the cache returns the old digest, so CAS or currency can succeed incorrectly. Conversely, hashing capture time causes unchanged candidates to appear different and invalidates every reuse/precompile comparison.

**Repair:** treat mtime/size as hints only. Establish actual byte hashes at consequential read/edit/check/reuse/publication boundaries and detect races during acquisition. Define a versioned canonical candidate encoding with sorted membership and explicit path/type/mode/content/environment fields; exclude `at` and observational counters from identity. Metadata timestamps remain recorded separately.

**Acceptance:** same-size edits with restored mtime invalidate evidence and reject stale CAS; equal candidates captured at different times have equal IDs; an actual relevant mode/membership/environment change changes the appropriate identity.

<a id="i-06"></a>

## I-06 — Per-cell aliases make cross-cell evidence ambiguous

**High · plan contradiction.** D-30 (L119) resets `#n` per context, but P2.4.4 (L704) and [continuity §6.2](docs/context/continuity.md#sec-6-2) promise cross-cell `recall #17`. Facts and dead ends carry these references forward.

**Failure:** cell A's `#17` and cell B's `#17` designate different evidence; a carried fact, recall or revert can resolve incorrectly. Globally unique journal IDs do not repair an unspecified display-alias resolver.

**Repair:** use campaign-global monotonically allocated aliases mapped to immutable canonical artifact/action IDs. Allocate transactionally across children; never recycle on rebuild, cancellation or resume. Preserve source context/workspace as provenance. Explicitly context-qualified aliases are an alternative, but are more verbose and easier to misuse.

**Acceptance:** two sequential cells and parallel children retain unambiguous earlier references across carry-forward, crash/resume, recall and guarded revert.

<a id="i-07"></a>

## I-07 — The dedup key can return an answer to a different observation request

**Medium · inherited specification gap.** D-30/P1.6.3 (L119/383) use `(what,target,version)`, while `glob`, `in`, `near`, `since`, range and budget can change the answer. [Tools §5.4](docs/runtime/tools.md#sec-5-4) and the old proposal repeat the short tuple, without its necessary conditions.

**Failure:** a limited search in one directory suppresses a second search elsewhere, or a truncated view suppresses a larger request. A pointer to an evicted response does not make its source bytes live/KNOWN.

**Repair:** define dedup as semantic request equivalence plus sufficient current resident coverage. Include workspace/source identity and normalized query options; for search, include scope/index version and completeness. Reuse only when the earlier result satisfies the requested coverage and is usable in this projection. Otherwise execute or explicitly rehydrate.

**Acceptance:** changed scope/glob/source/since, expanded range/budget, stubbing and rebuild all yield the requested evidence; incomplete search is never promoted to complete through dedup.

<a id="i-08"></a>

## I-08 — Rules discovery does not establish trust

**High · plan authorization gap.** D-32 (L121) discovers rules-file candidates; P1.3.2/P1.10.1 (L315/547) treat a selected pinned snapshot as trusted. [Security §14.3](docs/platform/security.md#sec-14-3) requires an **authorized**, version-pinned rules snapshot.

**Failure:** opening an unfamiliar repository promotes its unapproved `AGENTS.md` into instructions. Pinning proves byte stability, not authorization.

**Repair:** discovery proposes candidates; existing host configuration or explicit user authority binds canonical path, digest and provenance before promotion to instruction text. Without that binding, the file remains repository data. Preserve approved snapshots on resume; changed bytes do not inherit approval. This does not require repeated prompts where a host trust policy already authorizes the selection.

**Acceptance:** an untrusted rules-named file remains data; an explicitly trusted snapshot is loaded; subsequent replacement and resume cannot silently elevate new instructions.

<a id="i-09"></a>

## I-09 — Lexical scope is insufficient for built-in path authority

**High · missing implementation contract.** P0.6.1 (L230) names `normalizePath`; P1.6.4 (L390) rejects unsupported symlink mutation kinds; P3.4.1 (L840) compares paths to scope. None defines existing symlink/junction ancestors, traversal, protected aliases or case-equivalent paths. [Security §14.1](docs/platform/security.md#sec-14-1) still requires filesystem-root enforcement.

**Failure:** an allowed `src/cache` junction points into `.git` or outside the workspace. A built-in read followed by anchored CAS can pass lexical scope, version and coverage checks while modifying protected bytes. This is separate from the acknowledged trusted-local arbitrary-shell limitation.

**Repair:** define one workspace-path contract for look/edit/registry/stamps/closures/ownership. Reject traversal and outside/protected real targets; unify case/alias identities. Conservatively reject mutation through symlink/reparse ancestors until an explicit operation supports them. Revalidate resolution at publication and state the limits of concurrent replacement. An atomic file move does not provide conditional compare-and-replace against arbitrary external writers; use controlled candidate/host-coordinated publication where that stronger guarantee is required. [Java file-operation contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html).

**Acceptance:** traversal, absolute escape, outside/protected junctions, Windows case aliases and ancestor substitution leave protected/outside bytes unchanged. Unsupported concurrent-publication guarantees are reported instead of claimed.

<a id="i-10"></a>

## I-10 — Redacted source cannot authorize edits to hidden bytes

**Medium · cross-contract gap.** P1.6.3 (L383) registers look results in Workset; P1.5.3 (L365) tracks whole ranges; P1.10.3 (L556) redacts before exposure. [Workset §5.3](docs/runtime/register-workset.md#sec-5-3) permits only exact delivered source bytes to establish displayed coverage.

**Failure:** a redacted line is recorded as fully KNOWN at the raw source hash. Redaction that changes line count also invalidates offsets. Literal anchors may often fail safely, but the authority invariant is false and seeds/recall can propagate it.

**Repair:** keep raw version identity separate from rendered observation. Track a source-to-rendered span mapping/redaction mask; simplest baseline: exclude any redacted line from ordinary anchored-edit coverage. Apply this to reads, post-edit views, seeds and recall. Never expose protected preimages to fill the gap.

**Acceptance:** replacement inside multiline anchors and removed lines do not grant coverage to hidden spans; exact unredacted spans remain usable.

<a id="i-11"></a>

## I-11 — Acceptance IDs do not convey the obligation being assessed

**High · residual specification inconsistency.** P1.1.4 (L274) includes only acceptance IDs/kinds/currency in `ContractSlice`; P4.4.3 (L953) sends that slice to the judge. [Compiler §6.1](docs/context/compiler.md#sec-6-1) contains the same abbreviation, while [acceptance §8.7–8.8](docs/verification/acceptance-review.md#sec-8-7) requires assessment of the stated criterion and complete requirements/evidence.

**Failure:** rollback is specified only in an acceptance item. The worker and judge see `AC-3/check` without its condition, while ID-presence coverage succeeds. The result is unnecessary ignorance or unsupported approval.

**Repair:** mandatory slices contain complete applicable command/selector or check/review text, origin, obligation version and requirement links. Original weakened obligations accompany the diff. IDs-only formatting is permitted in the small anchor digest. Amend the compiler specification and P1.1.4/P2.3.1/P4.4.3 together; the older §6.1 repeats the omission and is not a fix.

**Acceptance:** an acceptance-only rollback condition appears verbatim in implementing/review contexts; a slice retaining just its ID fails coverage.

<a id="i-12"></a>

## I-12 — Temporary-index Git snapshots do not guarantee exact working bytes

**High · plan implementation defect.** P1.2.4 (L299) snapshots using temporary-index `add -A`; P1.7.5 (L450) materializes baseline from that tree. The invariant is preservation of the actual initial dirty candidate, including staged/user state, under [workspace §4.6](docs/runtime/workspace-editing.md#sec-4-6).

Git attributes can normalize line endings/encoding and execute clean filters while adding content. Consequently, a Git tree can differ from the bytes actually read/tested, and configured filters can introduce effects into what appears to be a snapshot operation. [Git attributes](https://git-scm.com/docs/gitattributes).

**Repair:** preserve an explicit raw-byte/type/mode manifest and exact recovery blobs; treat Git trees/refs as snapshot indexing, not proof of byte identity. Where raw Git object storage is used, bypass filters explicitly; initialize the temporary index deliberately and verify every materialized candidate against its manifest. Keep the user's staged index state separately and untouched. Account for relevant ignored/untracked inputs explicitly; reject unsupported forms. [Git raw object hashing](https://git-scm.com/docs/git-hash-object).

**Acceptance:** CRLF/encoding conversion and a configured clean filter do not change the captured baseline or trigger hidden snapshot-time effects; dirty/staged/untracked data restores byte-exactly within supported metadata. Verify fresh candidate manifest equality before checks.

<a id="i-13"></a>

## I-13 — A parser needs invocation-bound reports and collision-free test identities

**High · residual evidence gap.** P1.6.6 (L402) prefers JUnit XML but does not require fresh invocation-owned reports. D-27 (L116) uses JUnit `class#method` and Jest/Vitest titles without module/file qualification. [Scheduler §8.3–8.5](docs/verification/scheduler.md#sec-8-3) requires actual scope, execution evidence and comparable test identities.

**Failure:** an old green XML report survives a successful command that ran no required tests; current stamps plus stale counts become false green. Separately, identical test names in two modules/files collide, making a new failure appear pre-existing or making equivalence falsely pass.

**Repair:** bind reports to action ID, runner/definition, selector, completion and captured artifact provenance. Use fresh report destinations or an explicitly validated build-cache evidence path. Parse complete structured evidence before prompt shaping; generic shell output proves only its invocation. Namespace stable test identity by runner/check/project/module/file and parameterization, retaining multiplicity. Missing or ambiguous evidence remains inconclusive, not green.

**Acceptance:** stale green XML plus no-tests execution, missing terminal capture, forged-looking stdout summary, same-name tests in different modules and parameterized-instance changes do not produce false acceptance/pre-existing classification. Legitimate build-cache reuse requires recorded provenance, not a filename timestamp alone.

<a id="i-14"></a>

## I-14 — Java can call the facade but cannot conveniently implement its required SPIs

**Medium · SDK requirement gap.** P0.3.4/P0.4.2 (L189/207) expose suspend-based `ProviderAdapter` and `Authority`. P1.9.6 wraps the entry point, while P1.12.3 (L588) supplies a Kotlin `FakeAdapter`. This does not validate the preparation brief's reusable Java SDK requirement, and contradicts TODO L76's restriction on public suspend exposure.

**Repair:** keep one suspend-based internal engine, with Java-visible future-based provider/authority interfaces or narrow bridges. Specify cancellation, errors and callback execution. Do not duplicate the controller or leak `Continuation` requirements to ordinary Java hosts.

**Acceptance:** a Java-only consumer implements both fake provider and authority, answers/rejects a request, registers events, cancels work and reads outcomes without coroutine APIs. Use the selected current JDK on both Windows and Linux; older-JDK compatibility is not an owner requirement.

<a id="i-15"></a>

## I-15 — Provider cancellation has no defined in-flight call identity

**High · public contract gap.** P0.3.4 (L189) offers `suspend complete(Request): Response` and `cancel(handle)`, but P0.3.2's request/response (L180) defines no handle acquisition before completion. AX-08 requires cancellation with late output archived, spend reconciled and no action dispatch. [Adapters §15.1](docs/platform/adapters.md#sec-15-1), [recovery §13.1](docs/operations/recovery.md#sec-13-1).

**Failure:** the host cannot name an in-flight provider call, or cancellation ends the coroutine that would receive terminal/late usage. The reservation may leak, spend disappear, or a late tool call execute under cancelled authority.

**Repair:** choose caller-assigned invocation IDs registered before dispatch, or `start → handle` with await/cancel/terminal-observation semantics. Separate cancellation requested, provider acknowledgment and reconciled terminal state. Preserve late native output and usage exactly once without tool execution; retain conservative unknown usage holds. This is an operation-correlation field, not another campaign identity or a networking implementation.

**Acceptance:** cancel before provider ID arrival, race cancellation with a completed tool response, and deliver late usage after cancellation. No tool dispatch occurs; spend settles once; unknown state remains explicit.

<a id="i-16"></a>

## I-16 — One cache-write quantity/rate cannot represent all provider bills

**Medium · provider-neutral record gap.** P0.3.3 (L185–186) has one `cacheWrite` count and category rate. Anthropic supports separately priced five-minute and one-hour cache writes, including mixed usage. [Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching). Current [accounting §15.2](docs/platform/adapters.md#sec-15-2) requires actual billable categories priced once.

**Failure:** aggregating write tokens before pricing loses the class information; a retained native JSON blob cannot repair a generic `Usage.price(table)` contract that has no normalized price dimension.

**Repair:** represent typed billable quantities keyed by the provider's pricing dimension, including write class/TTL where applicable. Preserve aggregate counts only as diagnostics. Use decimal money and dated mappings; unknown/unpriced classes produce unknown cost, never zero. Keep provider-specific mapping implementations deferred.

**Acceptance:** synthetic mixed-write usage prices both classes once; aggregate plus subfields are not double-counted; missing breakdown prevents an exact economic claim.

<a id="i-17"></a>

## I-17 — An average token estimate cannot provide the promised hard admission guarantee

**Medium · contract precision gap.** D-06 (L95) supplies `bytes/3.6`; P0.3.2/P2.3.3 (L181/684) promise complete/hard admission. [Compiler §6.1](docs/context/compiler.md#sec-6-1) requires total effective context and output/reasoning headroom, including provider continuation state.

**Failure:** an atypical token distribution or unknown retained native history exceeds the profile limit despite a locally “valid” request. Actual usage from the response arrives too late to establish the first request's fit.

**Repair:** separate rough scheduling estimates from dispatch admission. Specify profile/tokenizer identity, exact versus estimated count, conservative error margin and treatment of opaque/effective history. Where no safe bound exists, use fresh history or return a capacity result; provider rejection is evidence of an estimation miss, not proof the request fit. Fakes need independent token counts, not the same heuristic on both sides.

**Acceptance:** adversarial text, many tool schemas/results and unknown continuation history cannot be silently marked exact-fit; estimation drift is recorded and subsequent admission adjusted.

<a id="i-18"></a>

## I-18 — Research ablations must not become ordinary production safety switches

**High · configuration boundary defect.** P0.1.3/P6.1.2 (L158/1124) expose every ablation in `Config`. [Evaluation §19.5](docs/evaluation/method.md#sec-19-5) includes reserve-off, test-integrity-off, delta-only and clamped-routing arms. [Promotion §19.6](docs/evaluation/method.md#sec-19-6) forbids disabling required runtime correctness controls to improve a score.

**Failure:** a normal host can select an experimental configuration that invalidates the SDK's claimed guarantees.

**Repair:** distinguish production optional features from evaluation-only counterfactuals. Normal `AttemptConfig` rejects weakened mandatory controls. The offline runner may describe isolated research arms explicitly, but cannot label invariant-violating results production-eligible or promoted. Apply freeze/version rules to both kinds.

**Acceptance:** production startup rejects forbidden combinations; the evaluator can represent them without bypassing eligibility or the immutable accounting/acceptance boundary.

<a id="i-19"></a>

## I-19 — Stage completion and live architectural promotion are conflated

**Medium · explicit scope interpretation required.** D-28/P7 (L117/1154) defer live comparators and economics. The [roadmap §18.2](docs/implementation/roadmap.md#sec-18-2) says no stage starts before its preceding gate is measured, including live A/B/D/E gates. TODO L3 simultaneously says subsystem documents win.

The user's transport exclusion justifies offline engineering, but does not satisfy those live gates. Treating every completed phase as promoted would contradict the [three evaluation levels](docs/evaluation/method.md#sec-19-1).

**Repair:** record the scope interpretation in the plan: separate `IMPLEMENTED`, `FIXTURE_VALIDATED` and `PROMOTED`. Defer live gates explicitly with prerequisites/evidence fields and `UNMEASURED` status. Optional layers remain disabled in normal operation pending their gate; fixture success alone does not establish architecture value.

**Acceptance:** synthetic perfect fixtures cannot report live superiority or Stage B/D/E promotion; later real evaluations attach their own evidence without rewriting engineering history.

<a id="i-20"></a>

## I-20 — Native process ownership and durable handles are not supplied by a name

**High · platform implementation gap.** P0.6.1 (L230) promises `JvmOs` with Windows job objects, POSIX process groups and tree termination, but names no native mechanism or dependency. P1.6.5/P2.2.4 (L396/653) require restartable handles/output/status. D-26's coroutine mutex/scope does not implement these guarantees.

Java's process descendants are a snapshot; they are not a persistent job/group ownership primitive. Windows job objects provide native process grouping and termination with defined inheritance/breakaway behavior. [Java Process](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Process.html), [Windows job objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects).

**Failure:** a child spawns/reparents during timeout, survives the launcher, or outlives the harness's only log pipe. Resume cannot recover output/exit status from a persisted PID alone. Also, “timeout kills the tree” conflicts with FX-22's live-process observation timeout unless the two kinds of timeout are distinguished.

**Repair:** select a compact native binding/helper per supported OS; define launch-time ownership, breakaway policy, durable log capture and terminal record ownership. Distinguish observation timeout, execution deadline, cancellation request, confirmed termination and lost state. Define whether jobs survive harness death; if survival/status recovery is unsupported, report lost/unknown and never relaunch blindly. Keep the helper within the OS adapter, not a new scheduler/service.

**Acceptance:** real Windows/Linux tests cover immediate child spawning, parent exit, harness crash, cancel/deadline races, log-cursor continuation and PID reuse. A missing or unresponsive poll cannot justify duplicate execution. Supported resumable execution must pass process-survival, same-handle polling and durable terminal-status fixtures on both OSes; a `lost/unknown` fallback is honest degradation and cannot close FX-22 or that capability's validation gate.

<a id="i-21"></a>

## I-21 — Canonical storage location, ownership and durability need one concrete policy

**Medium · plan ambiguity.** D-15 (L104) says state is beside the repository but proposes adding it to that repository's `.git/info/exclude`. P0.5.1 (L219) specifies WAL/temp-rename without a durability setting; a mutex is process-local. [State §4](docs/state/contracts.md#sec-4) explicitly requires canonical state outside the source tree, and [evidence §4.3](docs/state/evidence-coherence.md#sec-4-3) requires artifact-before-reference ordering.

**Failure:** an implementer chooses in-tree state against the specification, multiple repositories share one sibling directory, two hosts write one store, or a receipt becomes durable before its referenced artifact. A directory called cache may also be treated as disposable although it contains sole recovery evidence.

**Repair:** use a durable external project root keyed by canonical repository identity, shared across its workspaces with workspace-qualified records. No `.git/info/exclude` change is needed. Enforce one controller process per project as the baseline. Set explicit SQLite foreign-key and durability policies; durably publish artifacts before committing references, with bounded recovery of orphaned blobs. State whether guarantees include OS/power loss and fail closed when required filesystem durability is unavailable. [SQLite WAL](https://www.sqlite.org/wal.html) documents why WAL alone is not a power-loss durability promise.

**Acceptance:** two sibling repositories do not collide; linked worktrees share the intended store without sharing coverage; a second controller cannot acquire ownership; crash points never leave an accepted receipt referencing a missing artifact. Confirm local filesystem/permission behavior on both required OSes.

<a id="i-22"></a>

## I-22 — The every-shape lifecycle fixture is absent from intermediate gates

**Medium · traceability drift.** The FX-49 row (L1212) maps to P2.7.1 and P4.8.1, but Stage B and D headers/task fixture lists (L599/748 and L894/1020) omit it. TODO L1286 claims fixture alignment. [Shape activation §3.5](docs/architecture/roles-shapes.md#sec-3-5) requires lifecycle controls in every shape.

**Repair:** use one fixture registry as the authoritative mapping; require FX-49 parameterizations explicitly for S0, S1, S2 and S3 when each shape appears. Phase summaries may be generated or checked against that registry.

**Acceptance:** a static comparison detects phase/table/task drift; S1 and S2 gates execute their cancellation, lease, reservation, reconciliation and accounting paths before P5. All 59 FX and 10 AX IDs otherwise have mappings; do not describe this as wholesale fixture omission.

<a id="i-23"></a>

## I-23 — Risk floors and calibration contain unsafe evidence shortcuts

**High · policy contract gap.** D-34 (L123) omits explicit `packet.risk` from its floor mapping although [routing §11.2](docs/operations/routing.md#sec-11-2) requires it. D-35 (L124) allows demotion after favorable outcomes without stating that those outcomes concern the proposed cheaper tier. D-40 (L129) infers impact from lexical hits/hubs without an explicit unknown-coverage rule.

**Failure:** a small, high-risk authorization change with no discovered fan-in looks routine; or success by a strong model is treated as evidence that an untested cheaper profile meets the quality floor. Incomplete discovery becomes evidence of no dependency.

**Repair:** map declared risk explicitly and preserve function/risk floors after every adjustment. Keep unknown impact distinct from low impact; perform conservative execution or further inspection and re-evaluate as scope becomes known. Automatic tier reduction needs comparable verified evidence for that tier and the frozen/shadow evaluation policy. Keep threshold values labeled estimates, with demotion disabled until such evidence exists.

**Acceptance:** high declared risk with zero discovered fan-in still gets its floor; fifty successes at a high tier do not authorize an uncalibrated lower tier; missing graph/search coverage cannot establish S0 eligibility by itself.

<a id="i-24"></a>

## I-24 — Schema stability is conditional on adapter correctness

**Medium · clarification of conflicting normative wording.** D-20/P1.6.1 (L109/374) say all operations remain in the schema, never removed. [Adapters §15.1](docs/platform/adapters.md#sec-15-1) explicitly qualifies universal masking as a caching heuristic rather than a cross-provider rule. The older proposal has the same qualification.

**Repair:** retain seven logical tool families and local mask enforcement, but freeze a schema only after its adapter/profile/role validates it. Specify the supported schema dialect and capability behavior. At a new context lineage, select a supported representation or return an explicit unsupported-profile result; never silently change schemas mid-session. Rejection is an acceptable supported outcome when no mapping exists.

**Acceptance:** a fake profile rejecting the full schema either receives an explicitly supported boundary-time mapping or is refused before dispatch. A masking preference cannot override native protocol validity.

<a id="i-25"></a>

## I-25 — Propagate the resolved JDK and platform decisions

**Medium · resolved owner clarification, not an additional defect inferred from the original brief.** During review the user specified no old-JDK compatibility requirement, equal Windows/Linux support, and current testing on Windows. After the toolchain constraint below was explained, the user explicitly selected **JDK 26**. This supersedes TODO D-02's Java 17 consumer/JDK 21 build default and D-12's platform-priority question.

As of this review, **JDK 27** is the latest GA release, published September 15, 2026. [Oracle JDK 27 release notes](https://www.oracle.com/java/technologies/javase/27all-relnotes.html). Current Gradle documentation (9.7.1) lists Java through 26 and explicitly says JVM 27 is unsupported for running Gradle; Kotlin's documented JVM target/release values also end at 26. [Gradle compatibility](https://docs.gradle.org/current/userguide/compatibility.html), [Kotlin compiler options](https://kotlinlang.org/docs/compiler-reference.html).

**Repair:** set JDK 26 as the build toolchain, Gradle daemon JVM, Java/Kotlin target and supported SDK runtime. Retain Kotlin 2.4.20 and pin a validated compatible wrapper. Remove Java 17/21 compatibility work and the Linux-versus-Windows priority question. Do not retain a JDK 27 runtime/test requirement from the unselected alternative. No owner answer or tooling-support blocker remains for this choice.

**Acceptance:** the selected policy is recorded in [ANSWERS.md](ANSWERS.md); propagate it into TODO before implementation and verify build plus Java/Kotlin consumption on JDK 26 on Windows and Linux. Future JDK changes occur at a controlled upgrade boundary, not dynamically during each attempt.

## Suggested repair order

1. **Before P0:** propagate I-25's resolved owner decisions, distinguish offline/promoted status, repair artifact prerequisites and provider/Java/storage contracts (I-01, I-14–I-19, I-21, I-24).
2. **Before first S0 acceptance:** specify and implement authority, content/path identity, exact snapshots, tested-input validity, aliases and observation/report provenance (I-02, I-04–I-13), with the real process backend (I-20).
3. **Before Stage C/D/E closure:** make human review reachable, fix routing evidence rules and fixture mapping, then execute each shape's full regression obligations (I-03, I-22–I-23).
4. Update affected current specification sections where the gap is inherited, then their TODO tasks and D-rows. Re-run static dependency/link/fixture checks; replace blanket checked readiness statements with evidence references and remaining conditions.

This is sequencing advice, not authorization to begin implementation. Preserve task IDs where possible; split contract versus integration duties instead of renumbering the whole plan.

## Review coverage and validation limits

- Reviewed the complete TODO, all D-rows and fixture/coverage maps against the preparation brief and current subsystem boundaries. Independent passes covered architecture/evidence/safety, task dependencies/SDK/stages, and decisions/provider economics.
- The existing documentation validator passed in read-only mode: **4 archived sources, 118 correction operations, 137 section blocks and 39 subsystem documents** verified. Before these new files, it checked 1,182 local links with no error. Its generated report was captured without rewriting the repository's report.
- Validation including both new reports passed across **62 Markdown files and 1,286 local links**, with balanced fences, valid anchors and no integrity errors. Separate checks confirmed exactly one answer row for each D-01–D-42 and R01–R20, unique I-01–I-25 entries, and an unchanged TODO hash.
- The declared task graph has 174 leaf tasks and no cycle under its stated expansion rules. This check does not prove the missing artifact dependencies in I-01.
- The prior F01–F12 corrections remain authoritative. Unknown confinement, heuristic classification, optimal context allocation and live solver economics were already acknowledged; their broad existence is not reported here as a new discovery.
- The older 1.0 source confirms some invariants but repeats several residual ambiguities. No old rule is used to undo the improved authority, accounting, attempt-count or lifecycle corrections.
- No Gradle project, runtime fixture, provider integration or benchmark was executed. Proposed regression fixtures above are future acceptance requirements, not test results.
