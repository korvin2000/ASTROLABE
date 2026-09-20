# Learning, skills and offline improvement

**ASTROLABE 1.0.1 · specification** · Owner: Knowledge curator / offline improvement runner.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §12, §12.1, §12.2, §12.3. **Read with:** [records](records.md) · [method](../evaluation/method.md). Load companion sections only when the task crosses that boundary.

<!-- source-section: 12 -->
<a id="sec-12"></a>

## 12. Learning across cells and sessions
<!-- end-source-section: 12 -->

<!-- source-section: 12.1 -->
<a id="sec-12-1"></a>

### 12.1 Pipeline `[A §12.1; C §7.6; MB §10.2; IM §8.2]`

`trace + Result Packet → extractor (low tier, post-cell, from the archived trace — never the busy cell) → candidates (LES, PIT, BMAP-delta, NEG, SKILL-delta, CAL-delta) with evidence refs, anchors and scope → admission queue → curator (lint: evidence present and resolvable, scope bounded, no contradiction, secrets redacted, not a one-off generalization) → admitted (policy in §4.5) → injected by ranking (§6.3) → use tracking → revalidation, supersession, decay → promotion or pruning`.
<!-- end-source-section: 12.1 -->

<!-- source-section: 12.2 -->
<a id="sec-12-2"></a>

### 12.2 Rules

- A failed approach is conditional evidence with its conditions, not a permanent ban `[MB §10.2]`. Diagnoses, not dumps: `symptom | conditions | attempted | observed | reason | evidence | source revision | invalidation` `[IM §8.2]`.
- **Executable promotion**: a recurring, deterministic invariant becomes a test, linter rule or schema check, proposed as a task; the note becomes a pointer; evidence on held-out tasks is required before a rule is generalized `[MB §10.3]`.
- Negative evidence is typed (`NEG` states with scope, version and index coverage), so a later cell never reads "not found" as "absent" `[MB §10.4; B §5.4]`.
- **Skills**: compact procedures with trigger, prerequisites, ordered steps, expected artifacts, verification, failure exit, freshness, token budget and `modules[]{applies_to, mandatory}`; filtering at module granularity; mandatory modules, prerequisites and invariants survive every filter (the migration-skill counterexample `[MB §8.5]`); rendered per-role views cached per `(skill version, role)`; trigger evaluation at meaningful state changes, not every turn `[IM §8.4]`; two overlapping skills resolve procedural conflicts explicitly against the task's authority; a skill never grants authority and never marks a requirement complete `[B §10.3]`.
- **Generated tools**: ephemeral script (via `run`/transform) → project tool (README + schema + tests + declared effects; judge review if side effects) → global (eval-gated, compared against a disposable script and the tool it displaces); registration is versioned and becomes active only at an attempt boundary; a generated wrapper never gains privileges its caller lacks `[MB §8.6; C §12.3]`.
- Poisoning defences: provenance and confidence on every note, ADR sign-off, contradiction lint, scoped candidates until validated, usage-aware pruning, versioned memory so a regression rolls back independently of code; when a note conflicts with current code, investigate the discrepancy, never force the implementation to fit old memory `[B §10.2]`.
- Health telemetry: candidate count, admission rate, injection count, cited-in-register rate, harmful/stale injections, repeated mistakes, index freshness, locator validity — instrument the denominator `[IM §8.3, §8.5]`. An optional embedding server being cold never blocks a cell; a missing contract or rules file is resolved before dependent actions.
<!-- end-source-section: 12.2 -->

<!-- source-section: 12.3 -->
<a id="sec-12-3"></a>

### 12.3 Offline improvement runner `[C §13.7; B §10.5; IM §11; RN R10, R11]`

Three loops: within-task adaptation (the cell's job), across-task learning (the knowledge plane), and **harness evolution** (this runner). Cycle: collect versioned traces and failure categories → find a repeated failure or cost concentration across tasks → state a mechanism-level hypothesis and the affected module → propose one bounded change with predicted quality and economy effects → cheap structural checks and a smoke run → paired comparison against the frozen baseline under **matched total budget** → integrated evaluation (independently good changes can interfere) → 60/40 score only after eligibility → freeze and assess transfer on a separate final set → promote for subsequent attempts with rollback. Mutation scopes: tool behaviour, observation rendering, context policy, STATE validation, completion detection, recovery. Never editable by a candidate: evaluator access, acceptance criteria, budget accounting, adoption rules. The runner changes future attempts only; a live attempt runs a frozen harness. Always compare against spending the same resources on stronger reasoning, better context or another ordinary attempt — more optimization compute is not automatically a better harness `[B §10.5; RN R10]`.

---
<!-- end-source-section: 12.3 -->

