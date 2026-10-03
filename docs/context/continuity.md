# Carry-forward, injection, manifests and pre-compilation

**ASTROLABE 1.0.1 · specification** · Owner: Context compiler.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §6.2, §6.3, §6.4, §6.5, §6.6, §6.7. **Read with:** [records](../knowledge/records.md) · [residency-rebuild](../runtime/residency-rebuild.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F06](../../REVIEW.md#f06), [F08](../../REVIEW.md#f08).

<!-- source-section: 6.2 -->
<a id="sec-6-2"></a>

### 6.2 What carries forward across cells `[FROM-A §6.2]`

| Carried | How | Not carried |
|---|---|---|
| Register | validated; `v` facts re-checked against the store and file versions (stale ones tagged) | transcript |
| Workset seeds | entries chosen by the seed selector (below): the same rule at a cell boundary and at a pressure rebuild; re-served at *current* versions with hashes; one budget of ≤4K tokens | entries for files that changed and entries over the budget (announced as NOT SEEN) |
| Dead ends, open items, decisions | verbatim into `[K]` | model prose |
| Verification status | last receipt per check with validity (closure-based) | raw logs (recallable by id) |
| Touched ledger | compressed: paths + versions | diffs (in store) |
| Pinned user messages, packet | always | — |
| Probe findings used | pointers `(path:range@hash | #id)`; the next cell must `look` to make them KNOWN | the probe's transcript |

The store is campaign-scoped, so `recall #17` works across cells; a stub index of ids referenced by facts is rendered on request, not by default.

**Seed selector** `[ASTROLABE 2.0 plan §4.3, §1 row 18]`

> **Status.** The v1 rule and the budget are the code at `main` `6daabfc` (`K = core/src/main/kotlin/io/astrolabe`). The selector seam and v2 are **SPEC — implemented in P8.C.6** (line `v2/Dp1`, last commit `8b0f555`, ready to merge and not merged when this was written; its [report](../../../plan2/reports/WP-Dp1.md) governs the names below). The binding of v2 to the direct protocol is **SPEC — implemented in P8.D.1**.

One implementation serves both consumers: the cell boundary (`Controller.carryFrom` → `CarryForward.carry`, *code:* `K/campaign/Controller.kt:1251-1263`) and the pressure rebuild inside a cell (*code:* `K/cell/Cell.kt:1098-1101` calls the same function with the live register and Workset export). A selector is a pure function of records and only **orders** candidates; one shared cut decides what becomes KNOWN.

| Part | Rule |
|---|---|
| Candidates | The entries of the previous cell's Workset export — what was displayed, at which version, in which turn (*code:* `K/context/Seeds.kt:27-31`). Nothing outside it can be a seed. |
| Cut (both rules) | Walk the ordered candidates with a remainder of 4 000 tokens (`CarryForward.SEED_CAP_TOKENS`; *code:* `K/context/CarryForward.kt:88, 115-128`). The file moved since it was displayed → not a seed, NOT SEEN `changed`. The entry is larger than the remainder → not a seed, NOT SEEN `over the 4000-token seed budget`, and the walk continues: a later, smaller entry may still fit (first fit). Otherwise it is a seed and the remainder shrinks. The sum never exceeds the cap. P8.C.6 moves this loop to `Seeds.fit`, unchanged. |
| Render | `Seeds.render` shows each seed at its recorded hash; bytes that moved between selection and render are announced NOT SEEN, never shown as KNOWN (*code:* `K/context/Seeds.kt:33-57`). |
| **v1** — `SeedRule.V1`, the default | Entries whose path is mentioned by the text or `accept:` of the `[>]` step (else the first `[ ]` step) or by `Next`, or lies under `Focus`; ordered by path and first line (*code:* `K/context/CarryForward.kt:111-114, 139-147`). The bytes of the result are those before P8.C.6. |
| **v2** — `SeedRule.V2` | Every export entry, once, in this priority by its path: **touched** in the cell (the packet's `changes`, or the cell's own changes at a pressure rebuild) → **red** (an input of a check whose last receipt failed) → **noted** (named in the text or `needs` of an open item that is not closed) → **recent** (everything else). Inside one priority: an entry displayed in this cell before a carried seed, a later turn first, then path, first line and the remaining fields, so the order never depends on the export's order (`SEED_V2_ORDER`). |
| Red, precisely | A failed outcome only — a timeout or an unavailable runner is missing evidence, not red. The last receipt of **each** check is taken, without choosing "the latest" by time (I-05). Its files are the paths of a known closure, the files under a package closure, and the paths the command names; an unknown closure contributes only the paths the command names. |
| NOT SEEN in v2 | Announced for touched, red and noted entries that did not become seeds. A recent entry that did not fit is silent: "NOT SEEN: everything else" already covers it. |
| Choice | `Defaults.seedRule: SeedRule = SeedRule.V1`, frozen with the attempt. A direct cell always uses `V2`, whatever the default says: a direct register has no plan, `Next` or `Focus`, so v1 would select nothing. A structured cell uses `Defaults.seedRule`; moving it to `V2` is decided by the benchmark (D5). |
| Open wiring | At `v2/Dp1` the pressure rebuild reads the rule; the cell boundary (`Controller.carryFrom`) still passes the default. D1 passes the cell's rule, the packet's changes and the last receipt of each check there. |

A `v` fact whose anchor names no version shown in the cell is kept as `h` (P8.C.6): its staleness could not be tracked ([§6.4](#sec-6-4)).
<!-- end-source-section: 6.2 -->

<!-- source-section: 6.3 -->
<a id="sec-6-3"></a>

### 6.3 KB injection ranking and focus notes `[A §6.3 + C §6.3 + IM §8.3]`

`score = w_scope·match(scope, write_scope) + w_dep·overlap(validity.depends_on, contracts in play) + w_fresh·freshness + w_use·use_value + w_evid·evidence_quality − w_len·tokens`; inject the top notes above a threshold, at most 8 / 1.5K tokens; `CON` notes for touched paths bypass the cap. Per turn, ≤300 tokens of **focus notes** anchored in the current `Focus` directory or files touched this turn render in `[A]`, each shown once per cell `[C §6.3]`. Every worker has an escape hatch — `kb.search` with a stated reason — and every miss is logged for retrieval tuning. Log `(injected, cited-in-register?, outcome)` per note for usage-aware pruning; a `retrieval_miss` op or an `Open (needs: …)` line is a labelled negative for the ranker.
<!-- end-source-section: 6.3 -->

<!-- source-section: 6.4 -->
<a id="sec-6-4"></a>

### 6.4 Cross-cell fact coherence `[A §6.4 — now a case of §4.4]`

At compile time the harness re-validates every `v` fact: the evidence id must resolve; if the fact carries a `path@hash` anchor and the hash changed, the fact is rendered `v(stale @old)`. A fact stale for two consecutive cells with no reference is moved to the STATUS note and dropped from the register. `x` facts are retained durably until the campaign ends and offered to the extractor as `PIT` candidates; inactive records need not remain in every 3,000-token register projection. Required carry-forward that still cannot fit causes an explicit capacity response, not deletion.
<!-- end-source-section: 6.4 -->

<!-- source-section: 6.5 -->
<a id="sec-6-5"></a>

### 6.5 Manifest `[A §6.5; MB §4.7; B §7.6]`

Per cell: increment id, work/attempt/candidate/context ids, contract version, register version in, notes injected (ids, versions), seeds (path, range, hash), skills and their versions, model profile and effort, budget arithmetic, omissions with reasons, continuation lineage, reduction operations, estimated tokens and actual usage when returned, and the reason for the boundary (done / partial / replan / pressure / resume). Manifests make "the information was absent" distinguishable from "the model misread it" and are the input for tuning injection and seed budgets from data.
<!-- end-source-section: 6.5 -->

<!-- source-section: 6.6 -->
<a id="sec-6-6"></a>

### 6.6 Deterministic boundary pre-compilation `[NEW N4]`

When a cell’s last mutation has landed and only slow checks remain, the compiler may pre-build the next increment’s `[K]` locally, without a provider request. Tag it with the full compile-input fingerprint: candidate stamp, contract/authority revision, selected increment and carry-forward/register version, referenced note/contract/skill/index versions, role, profile and frozen policy. On successful cell close, reuse only if those inputs and required coverage still match; otherwise discard and recompile. Matching the tree stamp alone is insufficient, because checks, amendments or knowledge admission can change non-tree inputs. Cache population occurs on a provider request, not on local compilation; a provider-supported explicit pre-warm is optional, separately budgeted and off by default. No speculative model generation is introduced. Applies only to `cell_end(next_increment)`, never continuation of a red increment. Cost: wasted deterministic compiles on misses; intended benefit: overlap local boundary preparation with verification. Ablation: pre-compilation on/off, p50/p95 boundary latency, misses and any separately measured warm-up charges ([§19.5](../evaluation/method.md#sec-19-5)). `[HYPOTHESIS]`
<!-- end-source-section: 6.6 -->

<!-- source-section: 6.7 -->
<a id="sec-6-7"></a>

### 6.7 Decomposition calibration prior `[NEW N5]`

Every increment records `sizing: {turns, continuations, rebuilds, files_touched, expected_files}` ([§4.2](../state/contracts.md#sec-4-2)). Post-campaign, the extractor aggregates them into a `CAL-<repo>` note: median turns per increment; overrun rate (continuations > 0 or rebuilds > 0) by `expected_files` band and by subsystem; the mean ratio of touched to expected files. The plan cell receives this note (≤150 tokens) in its context, and the controller uses the same statistics to set `turns_per_cell` and to warn when a proposed increment's `expected_files` sits in a band with > 50 % overrun. This closes A's load-bearing assumption — decomposition quality — with the repository's own history instead of intuition. The note is data, never an instruction; it decays like any other note. `[HYPOTHESIS; ablation §19.5]`

---
<!-- end-source-section: 6.7 -->

