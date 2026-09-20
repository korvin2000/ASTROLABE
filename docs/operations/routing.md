# Profiles, routing and effort

**ASTROLABE 1.0.1 · specification** · Owner: Router / campaign controller.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §11, §11.1, §11.2, §11.3, §11.4, §11.5. **Read with:** [roles-shapes](../architecture/roles-shapes.md) · [costs](../economics/costs.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F06](../../REVIEW.md#f06), [F07](../../REVIEW.md#f07).

<!-- source-section: 11 -->
<a id="sec-11"></a>

## 11. Model profiles, routing and effort
<!-- end-source-section: 11 -->

<!-- source-section: 11.1 -->
<a id="sec-11-1"></a>

### 11.1 Function-based routing table `[FROM-A §11.1 + C §10.4 tiers and effort + B §11.3]`

Tiers are execution-policy classes, never vendor labels; a profile is `(provider, model, validated configuration, capabilities, context/output limits, cost table date, latency observations, stratum outcomes)`; the tier table is versioned data with a calibration date, re-seeded when catalogs change and followed by a harness calibration suite (tool-call validity, edit accuracy, test-fix rate, recovery behaviour) `[MB §7.2]`. Tier drift is expected every model generation `[MB §14.3]`.

| Function | Default tier | Effort (per call class) | Escalate when | Never | If unaffordable |
|---|---|---|---|---|---|
| Plan cell (decomposition, contracts, ADRs) | high | configured | — | low / medium | narrow the campaign or ask |
| Main implementing cell | high (capable default) | configured | extra-high after two verified failures of the same increment with different hypotheses | routed down on "looks routine" alone; never low | split the increment or checkpoint |
| Continuation cell of a red increment | same as the failing cell | configured | as above | — | — |
| Probe cell | medium | medium | high if findings insufficient twice | — | narrow the question |
| Review cell | high for contract/ADR/test-integrity/campaign scope; medium for routine diffs | medium/high | on `escalate` verdict | low | defer review, never skip a required one silently |
| QA cell (L3) | medium | medium | — | — | — |
| Extraction / curation | low | low | medium when lint finds contradictions | — | queue for later |
| Repair helper (capsule) | low, ≤ 2 attempts | low | owner cell | — | escalate |
| Log shaping, parsing, stamps, hashes, routing itself | deterministic | — | — | any model | — |

`(function, tier, effort, outcome)` quadruples are logged; a learned corrector can be added later without protocol change `[MB §7.5]`. Helper calls are the only place a second model appears in S0/S1 and they never run inside a worker's loop `[C §10.6]`.
<!-- end-source-section: 11.1 -->

<!-- source-section: 11.2 -->
<a id="sec-11-2"></a>

### 11.2 Selection policy — eligibility, affordability, refusal `[B §11.3 + C §10.4 + A never-list — NEW N7]`

```text
select_profile(function, packet, impact, policy):
    eligible   = profiles satisfying required capabilities, authorization, context fit, availability, user pins, calibrated quality floor
    tier       = max(function_default, plan_suggestion, risk_floor(packet.risk, impact.contracts_touched, fanin, reversibility))
    tier       = adjust_with_calibration(tier, packet.features)            # conservative rules until outcome data exists
    tier       = max(tier, never_below(function), risk_floor(packet.risk, impact.contracts_touched, fanin, reversibility))  # calibration cannot lower either floor
    affordable = { p ∈ eligible(tier) : conservative_estimate(p, packet) ≤ remaining_budget − reserves }
    if affordable is empty:
        return REFUSE(narrow_unit | checkpoint | ask_for_changed_constraint)  # never clamp the floor downward (B §11.3)
    return argmin over affordable of expected TOTAL task cost incl. retries, reviews and integration
```

Metadata from the plan cell informs the policy and never decides alone (self-assessed difficulty is poorly calibrated) `[MB §7.3]`. Model boundaries coincide with cell/packet boundaries and never cut through a reasoning chain; provider reasoning artifacts are opaque blobs replayed only to the same provider; cross-provider handoffs transfer explicit goals, decisions, evidence references and open questions `[MB §11.4]`.
<!-- end-source-section: 11.2 -->

<!-- source-section: 11.3 -->
<a id="sec-11-3"></a>

### 11.3 Escalation ladder `[MB §7.4; A §11.2; C §10.5]`

Attempt at tier N → verification → on **verified** failure escalate to N+1 with the failure evidence attached and a stated change: stronger model, more evidence via a probe, narrower increment, different tool, or revised hypothesis (alternative attempt, [§13.3](recovery.md#sec-13-3)). Repeating the same attempt under a different label is not recovery. At most `budget.attempts` per increment, then `blocked`.
<!-- end-source-section: 11.3 -->

<!-- source-section: 11.4 -->
<a id="sec-11-4"></a>

### 11.4 Calibration, shadow routing, cache-aware scheduling `[C §10.6, §5.6; MB §7.5]`

Routing comparisons run offline/shadow on fixed samples; live tasks are never sent to several expensive profiles to tune a router. Consecutive cells for the same role and profile are scheduled adjacently where latency allows so `[S][R]` stay hot; the scheduler never retains irrelevant context to flatter the cache metric.
<!-- end-source-section: 11.4 -->

<!-- source-section: 11.5 -->
<a id="sec-11-5"></a>

### 11.5 Economics `[A §11.3; B §12.3; MB §7.6]`

Every invocation — cells, probes, reviews, QA, extraction, repair, retries, any explicitly enabled provider pre-warm requests — is priced by provider accounting (uncached input, cache read, cache write, output, hosted-tool charges) and summed into `cost_per_accepted_task`. Four quantities are tracked separately: bytes transmitted, model-visible input, billed usage, durable state `[MB §11.3]`. Cache-hit rate is a diagnostic, not an objective.

---
<!-- end-source-section: 11.5 -->

