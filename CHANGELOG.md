# Change record

## Implementation checkpoints — 20 September 2026

- P4.5.5: exact bounded ordering of a small dependency DAG under frozen pairwise switching costs,
  including initial/fixed charges, external prerequisite checks and explicit unsupported/limit outcomes.
- P6.1.5: metadata-only workload partition validation and bounded exact construction, preserving
  task repetitions/must-link groups, weighted quotas and time constraints. Unknown metadata and
  search exhaustion remain explicit; frozen campaign/holdout integrity stays with P6.1.2.

## 1.0.1-proposal — 20 September 2026

**Type:** conservative correction and documentation reorganization of ASTROLABE 1.0-proposal.

| Record | Change and reason |
|---|---|
| F01 | Require committed authority and current assessed acceptance/review before closure; prevent scope and false-completion bypasses. |
| F02 | Scope existing versions, displayed coverage and shadow refs to workspace/context; prevent cross-cell and worktree collisions. |
| F03 | Repair native history, conditional-operation and rebuild semantics; make the existing loop implementable without malformed exchanges. |
| F04 | Use intersection for affected checks; tighten reuse, baseline and outcome/applicability semantics; avoid false green. |
| F05 | Replace unconditional transform-rollback claims with honest candidate discard or guarded inverse recovery. |
| F06 | Count complete context, validate all precompile inputs and separate local compilation from provider cache requests. |
| F07 | Preserve quality floors after calibration and make the existing attempt allowance unambiguous. |
| F08 | Remove competing state/integration ownership and reconcile bounded register retention with durable evidence. |
| F09 | Complete the already-required packet, activation, reservation, cancellation and publication contracts. |
| F10 | Align E with billed cost, account for model-dependent cache writes, and correct stage/comparator inconsistencies. |
| F11 | Tighten effects, approved instruction sources and evidence exposure without adding a security subsystem. |
| F12 | Correct editorial counts/source descriptions; separate inherited provenance from newly verified evidence. |
| D01 | Split the monolith into 39 scoped documents plus an entrypoint, index and loading routes. Preserve all 137 section blocks with stable anchors and a migration map. Archive all four uploads unchanged. |

Detailed reasons and locations: [REVIEW.md](REVIEW.md). Exact content changes, excluding relocation: [semantic-changes.patch](audit/semantic-changes.patch); replayable edits: [edits.json](audit/edits.json).

## Architectural drift guard

**Unchanged:** campaign/cell/increment boundaries; Task Contract–STATE–KB ownership model; four conceptual identities; one version registry and five coherence horizons; `[S][R][K][T][A]`; CAS plus scripted transforms; verification scheduler and hard completion gate; roles as configurations; shapes S0–S3; function-based routing/refusal; curated learning; modular local deployment; the twelve [design commitments](docs/architecture/overview.md#sec-0-4).

**Clarified, not silently retuned:** two substantive attempts means initial plus one alternative per increment; required campaign review follows the existing detailed predicate in §8.8; optional provider pre-warming is separately budgeted and off by default. Existing model choices and numerical heuristics remain hypotheses/defaults.

**Not added:** semantic-summary subsystem, generated orchestration, new worker roles, distributed services, mandatory dense retrieval, alternative architecture or a replacement implementation plan.

## Audit scope

Original-word retention is 97.6% in the content-only comparison. This is not a calibrated conceptual-change percentage. Documentation integrity is reproducibly checked; runtime fixtures, platform behavior, provider integration and benchmark improvements remain unexecuted requirements. See [validation scope](audit/README.md).
