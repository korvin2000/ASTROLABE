# ASTROLABE 1.0.1 Kotlin SOTA AI Coding Agent Harness — actual state

**Current checkpoint — P0 CLOSED, P1.8.2–P1.8.7 DONE, the S0 cell runs (2026-09-21).**
On `main`; this record's commit. Session ended at a complete, pushed, green checkpoint.

## What is true now

**The P0 gate is closed.** [CI run 35623690223](https://github.com/korvin2000/ASTROLABE/actions/runs/35623690223)
is green on **both** platforms at `8052946` — P0.1.2's acceptance and the both-platform criterion
P0.6.1/P0.6.2/P0.6.4 were reopened for. Ubuntu 2m02 / three platform skips, Windows 8m45 / two.
**P0 is `FIXTURE_VALIDATED`.** Every live gate stays `UNMEASURED` (P7). Do not reopen these.

**The S0 cell runs.** `Cell.run(ctx, increment, budget): CellExit` wires `Layout` (`[S][R][K][T]`),
`Anchor` (`[A]`), `Gauges`, `Gates` and `Residency`: it renders the turn, admits it, fails closed
on an incomplete call set, partitions and dispatches, reconciles, drains the checker, evicts on
the `k` cadence and leaves a **persisted checkpoint on every exit path**, including the failure
ones, proved under fault injection. Exits are distinct: `Completed`, `Blocked`,
`Partial(Pressure|TurnBudget|TokenBudget|Reserve|CompletionStalled)`, `Failed`, `Cancelled`.
Do not reimplement any of P1.8.1–P1.8.7.

**Counts: 79/185 DONE, 0 IN_PROGRESS, 106 TODO.** P0 19/19; P1 51/64; P2–P6 9/102.
Local Windows/JDK 26 full build at this checkpoint: **core 935 tests / 0 failures / 0 errors /
6 existing platform skips**, eval 30, provider-api 15. `checkKotlinAbi` green.

## Resume here

**Next task: P1.8.8 Role completion and `ResultPacket`** — it fills the `RoleCompletion` /
`RoleOutput` / `CompletionDecision` seam `CellContext` already declares. Then P1.9.1–P1.9.6
(controller, S0 compiler, lifecycle controls, finish receipt, `Astrolabe`/`AstrolabeJava` facade),
P1.11.1–P1.11.2 telemetry, P1.12.1–P1.12.4 validation. `Deps` and §2.4 producer readiness govern,
not numeric order. Read each task's complete entry and its prerequisites' `Log:` lines first.

## Carried forward — read before touching these

1. **Only one 2026-09-20 audit debt is still open:** P1.9.2 must open the derived contract after
   workspace capture, bind `ProtectedPaths`, pass `Sniff` commands into `Checks.seed` and approved
   rules into `Prime`. Everything P1.8.2 and P1.8.7 owed is closed (TODO §1 resume notes).
2. **Exit-gate refinement (TODO §3.1):** "red" means a receipt whose outcome is `failed`. An
   inconclusive/timed-out/unavailable check is missing evidence, not red (L8) — it refuses
   completion only where the check is required. Acceptance is unchanged. This mattered because a
   generic shaper returns `inconclusive` until P3.1.4.
3. **Open finding for P1.12.2:** recall pointers exist only for `look` results; a `run`/`edit`
   alias resolves to an action or edit id, so a stubbed run body is recorded as a loss rather than
   recalled. Decide there whether run/edit captures earn observation ids.
4. **Recorded, not diagnosed:** one full-build run failed in `StamperTest` fixture setup with
   `fixture git exited -1 … git config core.autocrlf false` and no output — a `git` process that
   died under the suite's process load, not an assertion. It did not reproduce in an isolated
   rerun or in the two following full builds. `TempRepo.runGit` has no timeout or retry and
   reports only the exit code; start there if it recurs.
5. **ABI:** `Currency` gained a `red` field, so its `copy` signature changed — the one
   non-additive entry in the dump; `@JvmOverloads` preserves the previous constructor.

## How this session worked

P1.8.5, P1.8.6 and P1.8.7 were delegated to Fable worktree agents (owner instruction: route
complex, non-trivial tasks to Fable) and merged with `--no-ff`; each ABI conflict was resolved by
regenerating the dump, never by hand. Worktrees are removed and their branches deleted; on Windows
`git worktree remove` fails with "Filename too long", so use PowerShell
`Remove-Item -LiteralPath '\\?\<path>' -Recurse -Force` then `git worktree prune`.
Delegated work was reviewed before merging, not taken on trust: two changes touched measuring
instruments (the fake adapter's cache model, the exit gate's red rule) and both were checked to be
fidelity fixes rather than criteria tuned to pass.

**Historical checkpoints below; their former next-step instructions are superseded.**

**Current checkpoint — P0 CLOSED and P1.8.2–P1.8.6 DONE (2026-09-21)**, on `main` at `fd7773c`.

**The P0 gate is closed.** [CI run 35623690223](https://github.com/korvin2000/ASTROLABE/actions/runs/35623690223)
is green on **both** platforms at `8052946`, which is P0.1.2's acceptance and the both-platform
criterion P0.6.1/P0.6.2/P0.6.4 were reopened for: Ubuntu 2m02 / three platform skips, Windows
8m45 / two. **P0 is `FIXTURE_VALIDATED`**; every live gate stays `UNMEASURED` (P7).
Three rounds were needed and two of them were this session's own test assumptions, not the
harness. (1) The four recorded failures: `gradlew` mode `100644`; a **real product defect** in
`Git`'s D-53 index guard, which compared path spellings so the runner's short `TEMP` name passed
and `update-index` would have rewritten the user's index; Node 22's junit reporter having no
`file` attribute, so the fixture guarantee needs Node 24; and a fixed 10s execution deadline
shorter than the launcher's ~20s start-up. ripgrep is now installed on both runners — 128 of the
130 Windows skips were `rg is not on PATH`, leaving P0.6.3's both-backends criterion unexercised.
(2) An FX-22 assertion that a background child was still `running`, true only while its sleep
outlasts the poll. (3) The mirror of the original race: the *measured* deadline could exceed the
root's fixed 120s lifetime, so it exited 0 first; the lifetime is now derived from the deadline.
Diagnoses came from the runs' own logs and report artifacts, never from a passing rerun.

**The cell's context machinery is complete.** `Layout` renders the cached `[S][R][K][T]` prefix;
`Anchor` the volatile `[A]` tail with per-block caps, a fixed reduction order and a measured size;
`Gauges` the ~20-token line on every result; `Gates` the S0 gate set as pure functions with a
registration seam for P3/P4 and once-per-condition keys; `Residency` batched eviction, stubs,
recall and `C(t)`. 42 focused tests. P1.8.5 and P1.8.6 were delegated to Fable worktree agents and
merged `--no-ff`. Do not reimplement any of these.

Local Windows/JDK 26 full build: **core 921 tests / 0 failures / 0 errors / 6 existing platform
skips**, eval 30, provider-api 15; ABI additive, `checkKotlinAbi` green after resolving the two
delegated branches' dump conflict by regenerating. **78/185 DONE, 0 IN_PROGRESS, 107 TODO**;
P0 complete, P1 at 50/64.

**Recorded, not diagnosed:** one full-build run failed in `StamperTest` fixture setup with
`fixture git exited -1 … git config core.autocrlf false` and no output — a `git` process that died
abnormally under the suite's process load, not an assertion. It did not reproduce in an isolated
rerun or the following full build. `TempRepo.runGit` has no timeout or retry and reports only the
exit code; if it recurs, start there.

Return: **P1.8.7 Cell turn loop** (delegated when this record was written), then P1.8.8
ResultPacket, P1.9.* controller/facade, P1.11.* telemetry, P1.12.* validation. TODO §1.1 holds the
CI evidence; §1.2 has no active override.

**Historical checkpoints below; their former next-step instructions are superseded.**

**Current checkpoint — P1.8.2 Layout DONE (2026-09-21)**, on `main`; commit containing this record,
from `e51cb9a`. `cell.Layout` renders the cached `[S][R][K][T]` regions: `[S]` = the frozen `Kernel`
contract (Appendix A, `kernel/1`) + role duties/packet + the seven families with an `enabled this
turn` line + the three evidence lines + the normative `ErrorPolicy` table + `Boundary.DATA_RULE` +
`ExecutionModeLabel`; `[R]` = the prime text; `[K]` = `CompiledK` (slice verbatim + pre-existing
ledger); `[T]` = `Transcript` (pinned user messages verbatim, then native items). Four breakpoints,
no clock/counter/absolute path in any cached region, omitted rather than empty regions. 7 tests.
Seeds, ranked notes, skills and carry-forward join `CompiledK` in P2–P4, not here. Do not redo this.

**P0 validation: repaired, second CI run pending.** All four failures of run 35514932596 — repeated
exactly by run 35532489501 at `3f3edc4`, so there is no fifth mode — were diagnosed from those runs'
own logs and artifacts: `gradlew` mode `100644`; a **real product defect** in `Git`'s D-53 index
guard (it compared path spellings, so the runner's short `TEMP` name passed); Node 22's junit
reporter having no `file` attribute; and a 10s execution deadline shorter than the launcher's ~20s
start-up. ripgrep and Node 24 are now installed/pinned in the workflow. Run **35621166065** is the
first to reach the Linux suite at all: **878 tests, 1 failure, 3 skips**, and that one failure was a
host-dependent status assertion this session had just added to FX-22, now removed. A rerun is
needed; **P0.1.2, P0.6.1, P0.6.2 and P0.6.4 stay IN_PROGRESS until both jobs are green**.

Local Windows/JDK 26 full build: **core 885 tests / 0 failures / 0 errors / 6 existing platform
skips**, eval 30, provider-api 15; ABI additive. **70/185 DONE, 4 IN_PROGRESS, 111 TODO**.
Return: **confirm CI green -> P1.8.3 Anchor**, then P1.8.4–8, P1.9.*, P1.11.*, P1.12.*.
TODO §1.1 holds the CI evidence table; §1.2 has no active override. All live gates UNMEASURED.

**Historical checkpoints below; their former next-step instructions are superseded.**

**Current checkpoint — P0 validation repair applied (2026-09-21)**, on `main`; commit containing this record, from `1e23b90`.
All four failures of [CI run 35514932596](https://github.com/korvin2000/ASTROLABE/actions/runs/35514932596)
were diagnosed from that run's own logs and report artifact — not from reruns — and repaired:
`gradlew` mode `100755` (Linux exit 126); a **real product defect** in `Git`'s D-53 index guard,
which compared path spellings and let the runner's short `TEMP` name through; Node 22's junit
reporter having no `file` attribute (the fixture guarantee needs Node 24); and a fixed 10s
execution deadline shorter than the launcher's ~20s start-up on the runner. The workflow now
installs ripgrep on both runners (128 of the 130 Windows skips were `rg is not on PATH`) and
pins Node 24. The local FX-22 intermittency is diagnosed too: two wall-clock races in `RunTest`,
both reproduced locally and removed. Each repair has a test that fails without it; the `Git`
guard was checked with a reverted-guard negative control.
Local Windows/JDK 26 full build: **core 878 tests / 0 failures / 0 errors / 6 existing platform
skips**, eval 30, provider-api 15, all executed; ABI dumps unchanged.
**69/185 DONE, 4 IN_PROGRESS, 112 TODO** — unchanged: P0.1.2, P0.6.1, P0.6.2 and P0.6.4 close
only when both remote jobs are green, and **Linux is still entirely unvalidated**. That needs a
push, which is the owner's call.
Return: **push -> confirm CI green -> P1.8.2 Layout**. TODO §1.1 holds the evidence table; §1.2
has no active override. All live gates remain UNMEASURED.

**Historical checkpoints below; their former next-step instructions are superseded.**

**Current checkpoint — P3.2.7 / OOO-04 DONE (2026-09-20)**, D-63, on `main`.
Checkpoint is the commit containing this record; baseline `3f3edc4`. Do not reimplement this kernel.
Immutable qualified impact snapshots, reverse BFS, test/acceptance closure joins, contract anchors,
added+deleted line risk and explicit package/workspace fallback requirements are complete.
**69/185 DONE, 4 IN_PROGRESS, 112 TODO**. All nine out-of-order proposals are complete; no ACTIVE override.
20 new impact tests, 500 graph and 300 hunk oracle cases; all 76 atlas tests, author review and ABI pass.
Final Windows/JDK 26 full build: **core 875 tests/0 failures/errors/6 existing platform skips** executed;
eval 30 and provider-api 15 green results reused. Eval also executed in the first full run.
The first full run repeated the known FX-22 poll/terminal-status failure; isolated and final full reruns
passed without code/test changes. Failure evidence is preserved; the P0 investigation remains open.
[Protocol](audit/OUT-OF-ORDER-P3.2.7.md), [API guide](core/README.md#impact-snapshots-p327); TODO §1.2 governs.
P3.2.1–P3.2.6 retain discovery, runtime assembly, tools, nudges, scheduler/pre-scan and runtime FX-37/54.
Return: **P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2**. CI/Linux/live gates remain open/UNMEASURED.

**Historical checkpoints below; their former next-candidate instructions are superseded.**

**Working branch: `main` (owner preference, 2026-09-20).** Ordinary development continues on `main`.
All four commits from `feature/out-of-order-kernels` were integrated by fast-forward from `3e5c1ce`
to `6298975`: P6.1.5, P4.5.5, P4.5.4 and P1.11.3. Source content matches the validated checkpoint
below; this integration changes no task status. Historical branch names describe their original
checkpoints and are not instructions to switch branches. The owner authorized publishing this integration.

**Completed in order: P4.5.4 / OOO-06, then P1.11.3 / OOO-09**, D-61/D-62.
P4.5.4 commit `817a32b`; P1.11.3 is the commit containing this checkpoint, `feature/out-of-order-kernels`.
**68/184 DONE, 4 IN_PROGRESS, 112 TODO**. No ACTIVE override. Session ends at a complete checkpoint.
AttemptCost: exact finite attempt costs/terminal probabilities and separate admission checks.
TraceAnalytics: immutable dedup, exclusive/inclusive money, worker/busy/concurrency/elapsed time and
explicit causal paths with honest lower-bound/unknown semantics. Do not reimplement these kernels.
30 focused tests; independent oracles on 300 attempt policies and 300 traces; author reviews resolved.
Final Windows/JDK 26 full build passed in 3m18s: **core 855/0 failures/errors/6 platform skips**,
**eval 30/0 failures/errors/skips** executed; provider-api 15 green results reused. ABI checks pass.
Protocols: [P4.5.4](audit/OUT-OF-ORDER-P4.5.4.md), [P1.11.3](audit/OUT-OF-ORDER-P1.11.3.md).
[API guide](core/README.md). P4.5.1/P4.5.2 and P1.11.1/P1.11.2 retain their runtime/integration gates.
Next out-of-order: **OOO-04 / candidate P3.2.7**, not registered/started; apply admission §4 and check
D-63 availability before code. Normal return P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2.
TODO §1.2 governs. Remote CI/Linux and all live gates remain open/UNMEASURED.

**Historical checkpoints below.**

**P4.5.4 / OOO-06 DONE**, D-61, from `955fb77`; commit containing this checkpoint.
Exact finite attempt policies, full cost/terminal masses, unknowns and separate admission checks.
**67/183 DONE, 4 IN_PROGRESS, 112 TODO**. No ACTIVE override. 15 focused tests and 300 seed-454
trajectory oracles pass; author review resolved; core ABI and full Windows/JDK 26 build pass in 3m19s.
Core **840 tests/0 failures/errors/6 platform skips**, eval **30/0 failures/errors/skips** executed;
provider-api 15 green results reused. [Protocol](audit/OUT-OF-ORDER-P4.5.4.md), [API](core/README.md).
Do not reimplement AttemptCost. P4.5.1/P4.5.2 retain real estimates/gates/Router/controller/FX work.
Next authorized proposal **OOO-09 / candidate P1.11.3**, then OOO-04; admission and D-62 check first.
Normal return P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2. CI/Linux/live gates remain open/UNMEASURED.

**Historical checkpoints below.**

**Completed this session: P6.1.5 / OOO-07, then P4.5.5 / OOO-08**, D-59/D-60.
P6.1.5 commit `eb143e8`; P4.5.5 is the commit containing this checkpoint, on `feature/out-of-order-kernels`.
Current **66/182 DONE (36.3%), 4 IN_PROGRESS, 112 TODO**. No ACTIVE override remains.
Workload partitioning: immutable metadata, weighted quotas, transitive groups/time, bounded exact search.
DAG oracle: dependency-safe subset-DP with exact initial/switch/fixed costs and explicit model/resource limits.
24 new focused tests; independent 300-table workload + 200-DAG oracles; reviews resolved; ABI/full build pass.
Final Windows/JDK 26 build executed **core 825 tests, 0 failures/errors, 6 platform skips**, **eval 30 tests,
0 failures/errors/skips**; provider-api reused 15 green results. Linux/CI/live gates remain open/UNMEASURED.
Protocols: [P6.1.5](audit/OUT-OF-ORDER-P6.1.5.md), [P4.5.5](audit/OUT-OF-ORDER-P4.5.5.md).
Do not reimplement these kernels. P6.1.2 retains runner/manifest/holdout/FX-47 work; P4.5.3 retains
calibration/controller/shadow/FX-45 work. The session ends at this complete checkpoint.
Next out-of-order: **OOO-06 → candidate P4.5.4**, then OOO-09 → OOO-04; admission/registration first.
Normal return: P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2. TODO §1.2 remains authoritative.

**Earlier checkpoint:**

**P6.1.5 / OOO-07 DONE**, D-59; checkpoint is the commit containing this record.
Immutable workload metadata, must-link/time/quota validator and exact bounded assignment are ready.
Current **65/181 DONE (35.9%), 4 IN_PROGRESS, 112 TODO**. 12 focused tests, 300 oracle tables,
independent review resolved, ABI and full Windows/JDK 26 build pass: eval 30 tests executed;
core 813 tests/6 platform skips and provider-api 15 green results reused UP-TO-DATE.
[Protocol](audit/OUT-OF-ORDER-P6.1.5.md). No active override; next authorized proposal OOO-08.
Normal return P0 validation -> P1.8.2. Parents/CI/Linux/live gates unchanged. Do not rebuild P6.1.5.

**Previous checkpoints below are historical.**

## Current checkpoint (2026-09-20)

**P6.1.4 / OOO-02 and P5.1.5 / OOO-05 DONE**, in the requested order. No ACTIVE override remains.
Scorecard/paired inference: commit `89d10c9`, D-57, [protocol](audit/OUT-OF-ORDER-P6.1.4.md).
Scope intersection/difference: D-58, [protocol](audit/OUT-OF-ORDER-P5.1.5.md), commit containing this checkpoint.
Current **64/180 DONE (35.6%), 4 IN_PROGRESS, 112 TODO**. Both reviews clear; ABI/full build pass.
Final Windows/JDK 26 build freshly executed **core 813 tests, 0 failures/errors, 6 platform skips** and
**eval 18 tests, 0 failures/errors/skips** (including 7,000 simulations); provider-api reused 15 green results.
Do not reimplement either kernel. P6.1.1/P6.1.2/P6.1.3 and P5.1.1/P5.1.4 retain their integration;
CI/Linux findings and live gates remain unchanged/UNMEASURED. S3 stays off.
Next out-of-order proposal: **OOO-07**, candidate P6.1.5; admission/registration before code.
Normal return: P0.1.2 + P0.6.1/P0.6.2/P0.6.4 -> P1.8.2.

## Earlier P6.1.4 checkpoint

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
