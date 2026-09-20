# Context, cache and session economics

**ASTROLABE 1.0.1 · specification** · Owner: Context compiler / telemetry.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §16, §16.1, §16.2, §16.3, §16.4. **Read with:** [adapters](../platform/adapters.md) · [method](../evaluation/method.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F06](../../REVIEW.md#f06).

<!-- source-section: 16 -->
<a id="sec-16"></a>

## 16. Token and session economy
<!-- end-source-section: 16 -->

<!-- source-section: 16.1 -->
<a id="sec-16-1"></a>

### 16.1 Mechanism → saving map `[A §16.1 extended]`

| Mechanism | Saves | Costs / risk | Source |
|---|---|---|---|
| Cell boundaries at increments (fresh compiled context) | the O(N) window of a long session; attention decay | one prefix cache-write of `[K]` per cell; re-orientation if decomposition is poor | A |
| Cache-preserving boundary invariant (`[S][R]` stable across cells; `[K]` stable within; batched eviction; mark-then-stub) | prefix reuse across boundaries and edit turns | 800-token immediate stubs; one miss per `k` turns | NEW N3 (B arithmetic + C staleness) |
| Deterministic boundary pre-compilation | boundary wall-clock latency | wasted compiles on stamp misses (no model tokens) | NEW N4 |
| Bounded residency (`k`, `R_max`, stubs ~20 tok, recall) | tool-result residency bounded (~16K) regardless of cell length | one cache miss per `k` turns on `[T]` | C1 / HELM, bound per J1 [§5.5](../runtime/tools.md#sec-5-5) |
| Register at the tail + typed patches | cached reads of the prefix every turn; output ~30–150 tokens per turn | `[A]` uncached every turn (~1.2–1.8K) | HELM, C3, C |
| Δ + absolute verification lines; sync time-boxed checker | a correct edit costs one ~5-token line; no watcher scheduler | checker latency (time-boxed) | C1 amended; C |
| Transactional turns + fused `run(if: applied)` | a verified cycle in one round trip instead of three or four | none when semantics are explicit | C3 / HELM T5 |
| Fuzzy anchor diagnostics + diff-since-expect | the "invalid edit → re-read → retry" cycle | none | C1, C3 |
| Post-edit views registering ranges | re-reads after edits | ~50 tok per edit | HELM |
| Prime + atlas + focus zoom + sniffed commands | the cold-start grep/glob storm | O(touched) refresh | C5, C3 |
| Whole-file reads refused above budget | multi-thousand-token dumps | occasional second `look` | HELM |
| Workset seeds across cells | re-discovery of the files the next step needs | ≤4K tok per cell | A |
| Precision-gated KB injection, BMAP notes, focus notes | repeated discovery across sessions | ≤1.5K + 300 tok per cell; stale-advice risk (mitigated [§12](../knowledge/learning.md#sec-12)) | IM [§8](../verification/scheduler.md#sec-8), MB [§4](../state/contracts.md#sec-4), C |
| Impact nudge | the "invented interface" repair loop | ~30 tok when it fires | C |
| Probe cells | exploration noise dies with the probe; parent keeps a 400-token summary | probe cost (counted) | MB [§5](../runtime/context-layout.md#sec-5), A |
| Scripted transforms with diff receipts | forty displayed regions become one receipt | mandatory blast-radius run | A, B, C |
| Verify-on-stop with reuse proofs and closures | ceremonial full-suite reruns; reruns after unrelated edits | closure bookkeeping | IM [§9.5](../runtime/workspace-editing.md#sec-9-5), A, B |
| Masked tool schemas, per-role masks, catalog | schema churn and avoidable cache invalidation; masking does not remove schema tokens from model-visible context | fixed-schema input and cache charges remain | MB [§8](../verification/scheduler.md#sec-8), C |
| Process handles with new-output-only polling | re-injected accumulated logs | none | IM [§5.1](../runtime/context-layout.md#sec-5-1) |
| Effort per call class | reasoning tokens on helper calls | — | C |
| Gauge | behaviour regulation for ~20 tok/result | — | C3 |
<!-- end-source-section: 16.1 -->

<!-- source-section: 16.2 -->
<a id="sec-16-2"></a>

### 16.2 Cache-reuse arithmetic as a design constraint `[FROM-B §7.5 — the analytic core, restated]`

B's worked example (explicitly hypothetical prices: $2/M uncached, $0.20/M cached, $8/M output) shows the trap this architecture must avoid: an accumulating 60K-token window at 90 % cache reuse costs **$0.912** of input over 40 calls; a "selective" 24K-token context at 50 % reuse costs **$1.056** — more money for fewer tokens — and only reaches **$0.451** when its reuse climbs to 85 %. Three design rules follow, and each is enforced rather than hoped for:

1. **Preserve stable prefixes except at declared coherence/rebuild boundaries.** Eviction happens in batches every `k` turns; stale reads are marked immediately but stubbed at the batch (or immediately only when > 800 tokens); `[K]` is compiled per projection; tool schemas are byte-stable; the anchor is the routine volatile tail. Authorized amendments or invalid mandatory inputs take precedence over cache reuse ([§5.1](../runtime/context-layout.md#sec-5-1), [§5.8](../runtime/residency-rebuild.md#sec-5-8)).
2. **A boundary must pay for its cache write.** With ρ ≈ 0.1 (cached-read price relative to uncached `[C1 §7.4]`) and `[S][R]` cached identically in both cases, continuing an old cell whose live `[K]+[T]` is `C_live` tokens costs ≈ `ρ·C_live + |A| + new` per turn; a fresh cell with a compiled `[K]` of `|K|` tokens pays a one-time cache write of ≈ `1.25·|K|` (cache-write price on some providers) and then `ρ·|K| + |A| + new` per turn. The per-turn saving is `ρ·(C_live − |K|)`, so break-even arrives after `N_be ≈ 1.25·|K| / (ρ·(C_live − |K|))` turns — for `|K| = 6K`, `C_live = 60K`: ≈ 1.4, i.e. **one to two turns**; for `C_live = 20K`: ≈ 5.4, i.e. **about five turns** `[ESTIMATE; coefficients from C1 §7.4 as audited by J1 §5.5; conservative because the old cell's window would keep growing]`. Short increments may not repay this illustrative reconstruction charge; the conclusion depends on actual reuse, provider pricing and whether a semantic boundary was required anyway; the calibration prior ([§6.7](../context/continuity.md#sec-6-7)) and the `continuations per increment` metric exist to keep increments in the band where boundaries pay.
3. **Measure the invoice, not the prompt.** The economy term of the score is billed cost per accepted task by cache class; `[A]` size, STATE upkeep and boundary cost share are reported beside it. Cache-hit rate is a diagnostic.
<!-- end-source-section: 16.2 -->

<!-- source-section: 16.3 -->
<a id="sec-16-3"></a>

### 16.3 Compaction triggers as tuning rules `[FROM-B §7.4; IM §6.5]`

```text
capacity trigger:  the next valid request would exceed the usable context budget                → pressure rebuild (α)
economic trigger:  expected remaining input savings > checkpoint cost + cache reconstruction + expected rehydration
                                                                                                    → tunes α, k and the cell turn budget per stratum
```

The economic inequality is not evaluated live per turn (its inputs are estimates); it is the rule by which `α`, `k`, `R_max` and `turns_per_cell` are tuned from manifests and billed usage per task class and profile. When estimates are weak, conservative occupancy thresholds are used and outcomes measured; no calibrated probability of future reuse is invented.
<!-- end-source-section: 16.3 -->

<!-- source-section: 16.4 -->
<a id="sec-16-4"></a>

### 16.4 Session efficiency and output discipline `[A §16.2, §16.4; C §6.8]`

Target shapes `[ESTIMATE, to be measured]`: cold start = 1 compile + 0–1 `look(tree/outline)` turns instead of ~10–15 exploration turns; hand edit + check = 1 turn; failed anchor = bounded diagnostic followed by correction on the next model turn; increment close = 1 verification turn (fused) + 0–1 review; boundary = 0 model turns when pre-compilation hits; continuation after a crash = 1 compile, 0 model turns lost. The anchor is input rendered by the harness; the model emits register *patches* and one intent line per turn; full rewrites are rejected above 400 tokens per patch. `[A]` is uncached every turn (~1.2–1.8K); over a 40-turn cell that is 50–70K tokens, accepted because the alternative reintroduces goal drift, and measured.

---
<!-- end-source-section: 16.4 -->

