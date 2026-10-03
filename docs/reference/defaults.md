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
| Cell turn budget | 80 (soft; nudge at 80 %; D-366, was 40) | continuation cell on exhaustion; calibration prior may adjust per repo; a reserve reached by the turn count still admits edits to files the cell already changed (D-366) |
| `α` pressure threshold | 0.65 of `C_profile` | gauge every result; second rebuild ⇒ `partial` + replan |
| `k` eviction batch / `m` turns kept on rebuild | 8 / 6 (0 for role switch, alternative attempt and cell end) | ablation: batched vs pressure-only within short cells |
| `R_max` total live results / `[A]` max | 48K / 5K tokens (D-370) | explicit residency bound; also one turn's read budget |
| Immediate-stub threshold for stale reads | 2,400 tokens (D-370) | [§5.3](../runtime/register-workset.md#sec-5-3) |
| `look.budget` / `run.budget` | 4,000 / 4,000 tokens: what a call without `budget` gets (D-370); the larger sizes the compile's growth reserve with `[A]` max — in full from a 65,536-token window, proportionally below it | looks of one turn share `R_max`; a look is admitted with what is left (cut, not refused) down to 300 tokens |
| Register cap / contract digest cap / patch cap | 3,000 (D-370) / 150 (+8 per requirement, ceiling 2,000; 0 per requirement pins 150, D-270) / 1,200 tokens (D-365) | acceptance lives in `[K]` |
| Fact line / note body / note summary | ≤ 600 chars (D-370) / ≤ 120 tokens / ≤ 200 chars | no code in facts |
| Workset seeds per cell / KB injection / focus notes / focus zoom | ≤ 4K / ≤ 8 notes 1.5K (CON uncapped) / ≤ 300 / ≤ 300 tokens | |
| Touched ledger in `[A]` | ≤ 10 files | rest via recall |
| Checker time box | 20 s; 120 s for a touched-selector check that fell back to project-wide scope (D-322) | deferred ⇒ `not_run`; started then timed out ⇒ `timeout`; scheduled at step boundary |
| `θ` risk threshold for early slow checks | 40 | `Σ Δlines·(1+log2(1+fanin))` `[C1 §6]` |
| Full-suite cadence | every 5 verified increments and at campaign end | |
| Reserves | cell: verification 15 % + recovery/persist 5 % of tokens and turns; campaign: recovery 10 % | unspendable elsewhere; raised to known check costs before start `[B §11.6]` |
| Stall / loop / repeated signature / doom-loop guard | 5 turns (D-366) / 2 identical / 2 repairs / 3 same calls | an applied edit batch or a new run result is progress (D-366) |
| Probe cell | 15 turns / 40K tokens, medium tier | |
| Review cell | ≤ 10 `look` / 30K tokens (increment), 60K (campaign); high tier for contract, design, campaign scope | |
| Repair helper / substantive attempts per increment / delegation depth / parallel cells | 2 repair calls / 3 total (initial + two alternatives, D-370) / 1 writers, 2 probes / 3 (S3 off by default) | |
| Campaign cells | 12 (soft) | user override |
| Task limits (C3) | none: money, active minutes and model requests are `null` unless the host sets them (`CampaignPolicy.limits`); no built-in ceiling; kept with the campaign across reopens | reserve `max(0, min(3·C, L − C))` for the next call's price `C`; requests hard, money bounds the accounted spend under conservative admission, minutes an admission threshold with every `run` and check deadline cut at dispatch to the active time left (none left: nothing dispatched); the reserve latch holds until the host changes a limit; a limit stop ends `budget_exhausted` (`BudgetStop`), names the best verified candidate and continues the same attempt when the host raises the limit |
| Balance profile (C3) | `Balanced` = the defaults in this table; `Economy`: results ×0.75, window ×0.75 and below the first price tier unless that would pass the slowdown ceiling, full suite at campaign end only, effort −1; `Thorough`: results ×2, full suite every 3 verified increments, effort +1 on dear models (≥ 5 USD/M output) | frozen per attempt; the model's slowdown estimate vs Balanced at `λ = 1.5`, `κ = 0.3` (unmeasured, not a guarantee) is kept ≤ 2× (soft) and < 3× (hard) in requests and time (owner №8); stop-loss `k` 2/3/4 is an E4 shadow value only |
| Flaky policy | one isolated rerun; disagreement ⇒ `inconclusive` + Open item | [§8.10](../verification/refactoring.md#sec-8-10) |
| Memory admission | interactive: queue; autonomous: factual `LES`/conditional `PIT` with anchors, scoped, confidence ≤ 0.6 | [§4.5](../knowledge/records.md#sec-4-5) |
| Profiles | main: one capable model, configured effort; helper: cheap, low effort; escalation: none | [§11](../operations/routing.md#sec-11) |
| Mode | `interactive`, `trusted-local`, `d_class: ask`, `integrity_approval: autonomous` (D-320), unknown outcomes reconciled by the `host` (D-321), ceiling `patch` | autonomous commit off |
| Timeouts | `run` 600 s when the call names none, an explicit value clamped to 3,600 s (D-370); process-group kill; never replay; one git command 600 s (`gitDeadlineSeconds`, D-303); provider terminal wait 60 s (then usage unknown, conservative funding, D-314) | |

---
<!-- end-source-section: 17 -->

