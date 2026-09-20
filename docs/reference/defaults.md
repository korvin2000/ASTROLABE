# Declared defaults

**ASTROLABE 1.0.1 · specification** · Owner: Versioned policy configuration.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §17. **Read with:** [roles-shapes](../architecture/roles-shapes.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F03](../../REVIEW.md#f03), [F04](../../REVIEW.md#f04), [F07](../../REVIEW.md#f07), [F08](../../REVIEW.md#f08).

<!-- source-section: 17 -->
<a id="sec-17"></a>

## 17. Defaults

All numbers are declared defaults for the first evaluation round, not derived optima `[ESTIMATE]`; all configurable per task.

| Parameter | Default | Note |
|---|---|---|
| Shape | policy ([§3.5](../architecture/roles-shapes.md#sec-3-5)); S0 for small, low-risk work | logged with inputs |
| Cell turn budget | 40 (soft; nudge at 80 %) | continuation cell on exhaustion; calibration prior may adjust per repo |
| `α` pressure threshold | 0.65 of `C_profile` | gauge every result; second rebuild ⇒ `partial` + replan |
| `k` eviction batch / `m` turns kept on rebuild | 8 / 6 (0 for role switch, alternative attempt and cell end) | ablation: batched vs pressure-only within short cells |
| `R_max` total live results / `[A]` max | 16K / 2.5K tokens | explicit residency bound |
| Immediate-stub threshold for stale reads | 800 tokens | [§5.3](../runtime/register-workset.md#sec-5-3) |
| `look.budget` / `run.budget` | 1,500 / 1,200 tokens | shared across parallel looks in one turn |
| Register cap / contract digest cap / patch cap | 1,200 / 150 / 400 tokens | acceptance lives in `[K]` |
| Fact line / note body / note summary | ≤ 240 chars / ≤ 120 tokens / ≤ 200 chars | no code in facts |
| Workset seeds per cell / KB injection / focus notes / focus zoom | ≤ 4K / ≤ 8 notes 1.5K (CON uncapped) / ≤ 300 / ≤ 300 tokens | |
| Touched ledger in `[A]` | ≤ 10 files | rest via recall |
| Checker time box | 20 s | deferred ⇒ `not_run`; started then timed out ⇒ `timeout`; scheduled at step boundary |
| `θ` risk threshold for early slow checks | 40 | `Σ Δlines·(1+log2(1+fanin))` `[C1 §6]` |
| Full-suite cadence | every 5 verified increments and at campaign end | |
| Reserves | cell: verification 15 % + recovery/persist 5 % of tokens and turns; campaign: recovery 10 % | unspendable elsewhere; raised to known check costs before start `[B §11.6]` |
| Stall / loop / repeated signature / doom-loop guard | 3 turns / 2 identical / 2 repairs / 3 same calls | |
| Probe cell | 15 turns / 40K tokens, medium tier | |
| Review cell | ≤ 10 `look` / 30K tokens (increment), 60K (campaign); high tier for contract, design, campaign scope | |
| Repair helper / substantive attempts per increment / delegation depth / parallel cells | 2 repair calls / 2 total (initial + one alternative) / 1 writers, 2 probes / 3 (S3 off by default) | |
| Campaign cells | 12 (soft) | user override |
| Flaky policy | one isolated rerun; disagreement ⇒ `inconclusive` + Open item | [§8.10](../verification/refactoring.md#sec-8-10) |
| Memory admission | interactive: queue; autonomous: factual `LES`/conditional `PIT` with anchors, scoped, confidence ≤ 0.6 | [§4.5](../knowledge/records.md#sec-4-5) |
| Profiles | main: one capable model, configured effort; helper: cheap, low effort; escalation: none | [§11](../operations/routing.md#sec-11) |
| Mode | `interactive`, `trusted-local`, `d_class: ask`, ceiling `patch` | autonomous commit off |
| Timeouts | `run` 120 s; process-group kill; never replay | |

---
<!-- end-source-section: 17 -->

