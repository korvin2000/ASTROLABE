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
| Workset seeds | entries chosen by the seed selector (below): the same rule at a cell boundary and at a pressure rebuild; re-served at *current* versions with hashes; one budget of ≤4K tokens | entries for files that changed and entries over the budget (announced as NOT SEEN, except v2's recency filler, which is silent) |
| Dead ends, open items, decisions | verbatim into `[K]`; in a direct line the whole active register with note ids (below) | model prose |
| Verification status | last receipt per check with validity (closure-based) | raw logs (recallable by id) |
| Touched ledger | compressed: paths + versions | diffs (in store) |
| Pinned user messages, packet | always | — |
| Probe findings used | pointers `(path:range@hash | #id)`; the next cell must `look` to make them KNOWN | the probe's transcript |

The store is campaign-scoped, so `recall #17` works across cells; a stub index of ids referenced by facts is rendered on request, not by default.

**Seed selector** `[ASTROLABE 2.0 plan §4.3, §1 row 18; D-398]`

> **Status.** The selector, both rules and the one budget are code since P8.C.6 (D-398): *code:* is `path:line` under `K = core/src/main/kotlin/io/astrolabe` at `main` `e28133a`. **SPEC — implemented in P8.D.1:** the binding of v2 to the direct protocol and the wiring of the cell boundary. **SPEC — implemented in P8.D.2:** the carry-forward block of a direct line.

One selector serves both consumers: the cell boundary and the pressure rebuild inside a cell call the same `CarryForward.carry` (*code:* `K/context/CarryForward.kt:95-130`, from `K/campaign/Controller.kt:1251-1267` and `K/cell/Cell.kt:1099-1104`). A `SeedSelector` is a pure function of its `SeedInputs` — the previous register, the Workset export, the paths the cell changed and the last receipt of each check — and it only **orders** candidates (*code:* `K/context/SeedSelector.kt:68-81`); `Seeds.fit` then applies the one budget, so no rule decides on its own what is KNOWN.

| Part | Rule |
|---|---|
| Candidates | The entries of the previous cell's Workset export — what was displayed, at which version, in which turn (*code:* `K/context/Seeds.kt:56-61`). Nothing outside it can be a seed. |
| Cut — `Seeds.fit`, both rules | Walk the ordered candidates with a remainder of 4 000 tokens (`CarryForward.SEED_CAP_TOKENS`; *code:* `K/context/CarryForward.kt:91`, `K/context/Seeds.kt:34-50`). The file moved since it was displayed → not re-served. The entry is larger than the remainder → not re-served, and the walk continues: a later, smaller entry may still fit (first fit). Otherwise it is a seed and the remainder shrinks. The sum never exceeds the cap. A candidate left out is announced NOT SEEN — `changed`, or `over the 4000-token seed budget` — when its reason is an announced one. |
| Render | `Seeds.render` shows each seed at its recorded hash; bytes that moved between the cut and the render are announced NOT SEEN, never shown as KNOWN (*code:* `K/context/Seeds.kt:63-87`). |
| **v1** — `SeedRule.V1`, the default | Entries whose path is mentioned by the text or `accept:` of the `[>]` step (else the first `[ ]` step) or by `Next`, or lies under `Focus`; ordered by path and first line (*code:* `K/context/SeedSelector.kt:86-93`). Its results are byte-identical to those before P8.C.6. |
| **v2** — `SeedRule.V2` | Every export entry **with visible lines** takes the first reason that holds for its path: **touched** in the cell (the packet's `changes`, or the cell's own changes at a pressure rebuild) → **red** (an input of a check whose last receipt failed) → **noted** (named in the text or `needs` of an open item that is not closed) → **recent** (everything else). An entry whose lines are all hidden — a transform's record, which costs nothing — is no candidate. The order is strict and total, so it never depends on the export's order: the reason; an entry displayed in this cell before one carried in as a seed; a later turn first; then path, first line, ranges, version, result id, tokens, source and hidden lines (`SEED_V2_ORDER`). After ordering, one candidate remains per (path, version, range): the same lines are never paid for twice, and never both re-served and announced (*code:* `K/context/SeedSelector.kt:103-115, 125-139`). |
| Red, precisely | A failed outcome only — a timeout or an unavailable runner is missing evidence, not red. The last receipt of **each** check is taken, without choosing "the latest" by time (I-05). Its files are the paths of a known closure, the files under a package closure, and the paths the command names; an unknown closure contributes only the paths the command names (*code:* `K/context/SeedSelector.kt:146-153`). |
| NOT SEEN in v2 | Announced for touched, red and noted entries that did not become seeds. A recent entry that did not fit is silent: "NOT SEEN: everything else" already covers it (*code:* `K/context/SeedSelector.kt:40-58`). |
| Choice | `Defaults.seedRule: SeedRule = SeedRule.V1`, frozen with the attempt (*code:* `K/Defaults.kt:52`). **SPEC — P8.D.1:** a direct cell always uses `V2`, whatever the default says — a direct register has no plan, `Next` or `Focus`, so v1 would select nothing. A structured cell uses `Defaults.seedRule`; moving it to `V2` is decided by the benchmark (D5). |
| Wiring | The pressure rebuild reads the rule and passes the cell's changes and the last receipts (*code:* `K/cell/Cell.kt:1099-1104`). The cell boundary does not yet: `Controller.carryFrom` calls `carry` with its defaults, which is v1 (*code:* `K/campaign/Controller.kt:1263-1266`). **SPEC — P8.D.1:** it passes the cell's rule and the last receipt of each check; the touched paths come from the packet it is given, and in S0 after a handoff from the kept handoff record ([Appendix A-D.6](../reference/kernel-contract.md#sec-appendix-a-direct-6)). |
| Known residuals (D-398) | The budget charges an entry's recorded tokens, not the rendered block. Overlapping but unequal ranges of one file are not merged. v1 keeps its zero-cost and duplicate cases, for byte compatibility. |

A `v` fact whose anchor the patch parser drops — a version that names no version shown in the cell — is kept as `h`: unanchored, its staleness could not be tracked (P8.C.6; *code:* `K/tool/state/PatchParser.kt:40, 194`; see [§6.4](#sec-6-4)).

**Carry-forward of a direct line** `[SPEC — implemented in P8.D.2]`. The carry-forward block renders dead ends, open items, decisions and amendments without ids, and no hypotheses or verified facts (*code:* `K/context/CarryForward.kt:48-83`): in the structured protocol the STATE block of `[A]` shows those. A direct `[A]` shows only the notes that fit 200 tokens, so at every rebuild and cell boundary the carry-forward block of a direct line carries the **whole active register with note ids** — open items, dead ends, decisions, pending amendments, hypotheses and verified notes with their evidence and stale marks — in the order and line forms of the `[A]` Notes block ([§5.10-D](../reference/rendered-turn.md#sec-5-10-direct)). Its size is bounded by the register cap of 3 000 tokens. Archived notes are not carried; they stay readable ([Appendix A-D.4](../reference/kernel-contract.md#sec-appendix-a-direct-4)).
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

