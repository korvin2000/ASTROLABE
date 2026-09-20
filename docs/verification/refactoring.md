# Refactor mode and flaky checks

**ASTROLABE 1.0.1 · specification** · Owner: Verification scheduler / main-line planner.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §8.9, §8.10. **Read with:** [acceptance-review](acceptance-review.md) · [workspace-editing](../runtime/workspace-editing.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F04](../../REVIEW.md#f04).

<!-- source-section: 8.9 -->
<a id="sec-8-9"></a>

### 8.9 Refactor mode `[A §8.9 + B §6.4 checklist + C fixtures]`

Activated when the contract's requirements are behaviour-preserving ("refactor", "extract", "rename", "migrate API"). Before the first increment, the plan cell records: behaviour to preserve, interfaces to change, compatibility duration, callers/consumers, data/configuration dependencies, independent acceptance checks — and separates the shared decision (a `CON`/ADR in the main line) from the mechanical edits `[B §6.4]`. The mode adds:

1. **Behaviour snapshot**: baseline receipt over the affected suites plus, where the project has them, characterization outputs (CLI goldens, API fixtures) recorded as blobs at `s0`.
2. **Temporarily-red increments**: `red_ok_until: increment_end`; inline syntax still runs, the checker renders deltas, the impact nudge still fires (it is about uninspected references, not about red tests), but the step-boundary gate does not fire until the declared coherent boundary (signature + implementation + callers) `[J1 §8.2]`.
3. **Transform receipts** ([§9.2](../runtime/workspace-editing.md#sec-9-2)) with mandatory blast-radius closure before the increment can close.
4. **Contract-first for interfaces**: an increment that changes a cross-boundary interface must reference a `CON` note (new or superseded) in `[K]`; the review cell checks the diff against it.
5. **Equivalence evidence**: compare the identities, outcomes and boundary/compatibility cases of preserved tests and available goldens at `s_n` versus `s0`; equal counts alone are not behavioural equivalence; new behaviour requires a new requirement, not a silent extension.
6. **Campaign-scope review** is mandatory ([§8.8](acceptance-review.md#sec-8-8)).
<!-- end-source-section: 8.9 -->

<!-- source-section: 8.10 -->
<a id="sec-8-10"></a>

### 8.10 Flaky checks `[FROM-B §9.3]`

Flaky checks produce uncertainty, not a favourable result. All attempts are preserved; a predeclared retry/triage policy applies (default: one rerun of a failed check in isolation; two disagreeing outcomes ⇒ `inconclusive` and an `Open` item); nothing reruns until a favourable result appears. Claiming a failure predates the change requires the baseline receipt ([§8.5](scheduler.md#sec-8-5)), not intuition from the diff.

---
<!-- end-source-section: 8.10 -->

