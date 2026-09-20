# ASTROLABE — architecture map

**Version 1.0.1-proposal · 20 September 2026**  
Conservative maintenance of the supplied ASTROLABE baseline. **Design specification; not implemented or benchmark-validated.**

## Authority and loading

This file is the entrypoint; the linked subsystem documents collectively contain the revised architecture. The uploaded `SOTA-BEST-MIXED-AGENT.md` supplied the authoritative design. A/B/C are supporting rationale, not alternative specifications. The four originals in `sources/` are immutable historical inputs: **do not load them by default or implement from them instead of this revision**.

Read this map, then the relevant subsystem and its immediate input/output contracts. Use the [loading guide](READING-GUIDE.md) for task-specific routes, the [full index](docs/INDEX.md) for discovery, and the [section map](audit/SECTION-MAP.md) for an inherited § reference. Detailed subsystem rules govern; illustrative pseudocode is not a second protocol. Historical rankings and source-provenance tables do not impose implementation requirements.

## Architecture retained

A **deterministic campaign controller** owns the durable contract, requirement graph, ledger, budgets and lifecycle. A **cell** executes one **increment** from compiled context; semantic completion aligns context, verification and checkpoint boundaries. The **verifier** assesses acceptance, and the controller records only supported transitions. Roles are configurations of the same runtime; S0–S3 remain policy-selected shapes, with parallel writers an optional extension.

```text
User / authorized amendment
          |
          v
Campaign controller: contract -> graph / ready increment -> shape / profile
          |                                      ^
          v                                      | supported outcome
Context compiler -> bounded cell -> tools -> workspace
          ^                |                       |
          |                v                       v
     KB + evidence <--- observations / receipts <- verifier / integrator
          |
     recovery, routing, adapters and accounting use the same records
```

The contract–STATE–KB ownership split, four identities, one workspace-version registry with five coherence horizons, `[S][R][K][T][A]` context layout, CAS edits plus audited transforms, closure-aware verification, refusal-based routing and quality-constrained 60/40 evaluation are preserved. [The original twelve design commitments](docs/architecture/overview.md#sec-0-4) remain unchanged.

## Subsystem map

| Concern | Start here | Boundary / companion |
|---|---|---|
| Objective and invariants | [Principles](docs/architecture/principles.md) | [Overview](docs/architecture/overview.md) |
| Ownership and lifecycle | [Components and identities](docs/architecture/components.md) | [Lifecycle](docs/architecture/lifecycle.md), [roles / shapes](docs/architecture/roles-shapes.md) |
| Durable task and evidence | [Contract / graph / ledger](docs/state/contracts.md) | [Evidence and coherence](docs/state/evidence-coherence.md) |
| Cell and observations | [Context layout](docs/runtime/context-layout.md) | [Register / Workset](docs/runtime/register-workset.md), [residency / rebuild](docs/runtime/residency-rebuild.md) |
| Execution and edits | [Tools and turn semantics](docs/runtime/tools.md) | [Workspace / edits / transforms](docs/runtime/workspace-editing.md), [gates / packets](docs/runtime/gates-termination.md) |
| Context selection | [Compiler](docs/context/compiler.md) | [Carry-forward / injection / pre-compilation](docs/context/continuity.md) |
| Repository understanding | [Navigation and impact engine](docs/repository/navigation.md) | [Workset](docs/runtime/register-workset.md) |
| Verification and completion | [Scheduler / receipts / baseline](docs/verification/scheduler.md) | [Acceptance / review](docs/verification/acceptance-review.md), [refactor / flaky checks](docs/verification/refactoring.md) |
| Delegation and recovery | [Delegation / integration](docs/operations/delegation.md) | [Recovery](docs/operations/recovery.md), [routing](docs/operations/routing.md) |
| Knowledge and improvement | [Records / admission](docs/knowledge/records.md) | [Learning / skills](docs/knowledge/learning.md) |
| Platform and authority | [Adapters / accounting](docs/platform/adapters.md) | [Execution authority](docs/platform/security.md) |
| Economics and defaults | [Cost model](docs/economics/costs.md) | [Declared defaults](docs/reference/defaults.md) |
| Implementation and evaluation | [Original staged roadmap](docs/implementation/roadmap.md) | [Evaluation method](docs/evaluation/method.md), [future runtime fixtures](docs/evaluation/fixtures.md) |

## Before writing the implementation plan

Resolve component ownership and public record/tool contracts first; then follow the preserved staged roadmap. Keep required lifecycle controls in S0. Each planned stage must name its applicable runtime fixtures and unsupported platform/provider assumptions. Optional features retain their original evaluation gates; this revision does not pre-approve their performance value.

[Review](REVIEW.md) · [Change record](CHANGELOG.md) · [Source register](SOURCE-REGISTER.md) · [Audit and validation](audit/README.md) · [Glossary](docs/reference/glossary.md) · [Implementing-cell kernel contract](docs/reference/kernel-contract.md)
