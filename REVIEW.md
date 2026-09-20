# Conservative architecture review

**Baseline:** supplied ASTROLABE `SOTA-BEST-MIXED-AGENT.md`, version 1.0-proposal.  
**Result:** 1.0.1-proposal; documentation maintenance, not architectural replacement.

## Verdict

Preserve the architecture. Its campaign/cell/increment decomposition, explicit authority and evidence ownership, shared coherence protocol, scheduler, bounded context and selective topology form a coherent design. The consequential defects are mostly inconsistencies between prose, schemas, pseudocode and inherited operational claims—not reasons to replace those components.

The findings below are source-derived inconsistencies or narrow engineering inferences, not measured defects from a running implementation. “High” means a plausible authority, evidence or execution-correctness failure if implemented literally; “Medium” means a material policy, efficiency or implementation-clarity defect. W identifiers refer to the separately checked [primary-source register](SOURCE-REGISTER.md#primary-checks). Corrections are applied in the subsystem text itself.

<a id="f01"></a>

### F01 — Authority and completion could be bypassed · High

The scope guard admitted a pending amendment, and the controller example closed work before review. Some gates also treated an existing evidence ID as sufficient assessment. Require committed authorization, criterion-bound current evidence and required review approval before closure; keep honest non-completed exits.

**Basis:** Baseline §§3.7, 4.1, 5.6, 8.6–8.8; B §§5.1, 9.4–9.6. **Applied in:** [Scope and acceptance](docs/verification/acceptance-review.md), [Lifecycle](docs/architecture/lifecycle.md).

<a id="f02"></a>

### F02 — Observation and workspace identities were under-scoped · High

A global path/range registry can leak one cell’s read authority into another or conflate worktrees. Namespace paths by workspace and displayed bytes by context/projection; keep the same four conceptual identities. Outlines do not authorize unseen bodies. Give each workspace its own shadow-ref suffix: ordinary Git refs are shared between worktrees (W05).

**Basis:** Baseline §§3.3, 4.4, 4.6, 5.3; B §§4.2, 8.3; W05–W06. **Applied in:** [Identities](docs/architecture/components.md), [Workset](docs/runtime/register-workset.md), [Workspace](docs/runtime/workspace-editing.md).

<a id="f03"></a>

### F03 — Turn and rebuild examples could violate native protocol · High

The loop appended results without the assistant’s native calls and rebuilt only the transcript variable. Preserve complete call/result exchanges, replace the full context projection, and use role-specific packet completion. Define stable operation IDs, legal conditional dependencies, STATE patch atomicity and existing poll/cancel semantics. No extra execution layer was introduced.

**Basis:** Baseline §§3.7, 5.4–5.5, 5.8, 15.1; B §§7.1–7.4, 8.1–8.5; C §8.4; W03–W04. **Applied in:** [Tools](docs/runtime/tools.md), [Rebuild](docs/runtime/residency-rebuild.md), [Loop](docs/architecture/lifecycle.md).

<a id="f04"></a>

### F04 — Check selection and reuse had false-green paths · High

The impact formula used subset containment where dependency intersection is needed. Correct that predicate, retain conservative unknown-closure fallback, and include check definitions, environment and directory membership in reuse validation. Separate historical outcome from applicability. Baseline checks must use the captured initial candidate; matching counts alone do not prove pre-existing failures or refactor equivalence.

**Basis:** Baseline §§7.4, 8.1, 8.3–8.5, 8.9; B §9.2. **Applied in:** [Impact](docs/repository/navigation.md), [Validity](docs/verification/scheduler.md), [Refactoring](docs/verification/refactoring.md).

<a id="f05"></a>

### F05 — Transform rollback promised more than filesystem operations guarantee · High

“Reverted as a unit” conflicted with the existing partial-write recovery model. Capture preimages first; distinguish preflight refusal from partial publication. Discard an isolated rejected candidate or apply a guarded inverse only against the runtime’s own unchanged postimages; record partial/unknown effects. Ordinary unseen-edit invariants now explicitly exclude the separately audited transform path.

**Basis:** Baseline §§5.4–5.5, 9.1–9.4, 19.3–19.4, Appendix A; B §§8.3–8.4; W07. **Applied in:** [Edit / transform](docs/runtime/workspace-editing.md), [Future fixtures](docs/evaluation/fixtures.md).

<a id="f06"></a>

### F06 — Context and pre-compilation budgets omitted changing inputs · High

Admission must count the complete serialized/effective context, including retained user/protocol items. Preserve mandatory skill invariants before optional content. Validate precompiled context against all compilation inputs, not the tree stamp alone. Local compilation does not write a provider cache; any supported pre-warming request is separate, budgeted and off by default (W01–W02).

**Basis:** Baseline §§5.1, 5.8, 6.1, 6.6, 16.1–16.3; B §§7.1–7.4; W01–W04. **Applied in:** [Compiler](docs/context/compiler.md), [Pre-compilation](docs/context/continuity.md), [Cache economics](docs/economics/costs.md).

<a id="f07"></a>

### F07 — Calibration and retry wording could defeat existing policy floors · Medium

Reapply both function and risk floors after calibration. Define the existing two-attempt default as two substantive attempts per increment, including the initial attempt; changing attempt IDs does not replenish that allowance. Operational retries keep their separate bounded counters and shared campaign budget.

**Basis:** Baseline §§11.1–11.3, 13.3, 17; B §§11.3–11.4. **Applied in:** [Routing](docs/operations/routing.md), [Attempts](docs/operations/recovery.md).

<a id="f08"></a>

### F08 — State ownership and retention rules contradicted each other · Medium

Keep one integration acceptance owner and one controller-owned ledger. Treat structured KB records as canonical SQLite state and Markdown as views. Map donor note labels to existing LES/PIT kinds. Preserve refutations durably without forcing every inactive fact into the bounded register; use existing STATUS records and linked skill/map modules rather than inventing new storage abstractions.

**Basis:** Baseline §§3.2, 4, 4.5, 5.2, 6.4, 12; B §§4.1, 10.1–10.3. **Applied in:** [State authority](docs/state/contracts.md), [KB](docs/knowledge/records.md), [Bounded STATE](docs/runtime/register-workset.md).

<a id="f09"></a>

### F09 — Existing lifecycle controls lacked a consistent end-to-end contract · High

Choose S3 only after validated planning; intersect role tools with shape and authority. Carry dispatch base, dependencies, contract revision and execution generation in existing packets. Recheck ownership/cancellation before publication, preserve late effect evidence, and distinguish empty readiness from completed work. These clarify already-required leases, reservations and cancellation—not a new scheduler.

**Basis:** Baseline §§3.4–3.7, 5.9, 10, 13; B §§11.2, 11.5–11.6; C §§5.3, 6.1. **Applied in:** [Activation](docs/architecture/roles-shapes.md), [Integration](docs/operations/delegation.md), [Lifecycle controls](docs/operations/recovery.md).

<a id="f10"></a>

### F10 — The evaluation formula measured a different economy objective · Medium

The declared objective used billed cost, but the scoring formula used raw tokens. Normalize E with total online cost per accepted trial; retain tokens as diagnostics and the same 60/40 weights and quality gates. Update provider-specific cache-write accounting where reported (W01), stage prerequisites and comparator labels. Cold-start estimates remain measured targets, not guaranteed invariants.

**Basis:** Baseline §§1.1, 15.2, 18.2, 19.3–19.4; B §§12.3, 15.4; W01–W02. **Applied in:** [Score](docs/evaluation/method.md), [Accounting](docs/platform/adapters.md), [Stages](docs/implementation/roadmap.md).

<a id="f11"></a>

### F11 — Effect labels and trusted text needed narrower guarantees · High

Do not infer read-only authority from an MCP annotation or an R label (W08). Enforce approved capabilities before dispatch, retain unknown effects, pin authorized rules-file versions and distinguish late evidence from publication. Delimiters are model-facing cues, not confinement. Keep exact undo preimages in protected local recovery storage while redacting exposed/reusable evidence.

**Basis:** Baseline §§4.3, 4.6, 5.4, 14, 15.3; B §§8.6, 11.2; C §12.4; W08. **Applied in:** [Authority](docs/platform/security.md), [MCP](docs/platform/adapters.md), [Effects](docs/runtime/workspace-editing.md).

<a id="f12"></a>

### F12 — Synthesis metadata contained inaccurate or unverifiable assertions · Editorial

Correct failure/tool counts and the claim that B contained no layout, gates or defaults; retain the original candidate ranking as historical editorial judgment. Distinguish supplied sources, newly checked primary evidence and unavailable upstream material. Existing estimates and hypotheses remain labeled; no inherited research claim becomes benchmark evidence for ASTROLABE.

**Basis:** Baseline §§0.1–0.2, 1.4, 5.4, 22; B §§7.1, 9.6, 11.6; W09–W11. **Applied in:** [Historical comparison](docs/reference/candidate-review.md), [Evidence register](SOURCE-REGISTER.md).

## Deliberately unchanged

No new service, database authority, orchestration framework, agent type or conceptual identity was introduced. The twelve commitments, main decomposition, seven tool families, role/configuration model, S0–S3 topology, deterministic rebuild choice, optional S3 policy and quality-first evaluation remain. Thresholds, reserve percentages, tier priors and experimental value claims were not retuned without measurements. The original implementation roadmap is preserved, with prerequisite contradictions corrected.

## Scope and residual uncertainty

The three targeted paper checks corroborate limited mechanisms and cautions; they do not validate ASTROLABE’s composed performance. The upstream research-notes file and other inherited corpus documents were not supplied. Parser reliability, mutation races, platform confinement, context sufficiency, judge quality, model calibration and the proposed economic gains still require implementation fixtures and controlled evaluation. The added fixtures are requirements, not reported passes.

## Change-budget evidence

All **137 original section blocks** have one mapped destination across **39 documents**. In the content-only comparison, **97.6% of original whitespace-delimited words remain unchanged**; the logical text grows from 232,829 to 252,198 UTF-8 bytes. Relocation, link expansion and document wrappers are excluded. These are text-diff measures, **not an objective percentage of conceptual change**; the architectural preservation claim rests on unchanged components and commitments. Exact edits, originals and reproduction checks are in [audit](audit/README.md).
