# Selective loading guide

[Architecture map](SOTA-BEST-MIXED-AGENT.md) · [Full document index](docs/INDEX.md)

## Default route

Read the compact map, [component ownership](docs/architecture/components.md), and the applicable [invariants and laws](docs/architecture/principles.md). Then load the subsystem being changed and only the neighboring contracts it consumes or produces. The “Read with” links are boundary pointers, **not recursive preload instructions**. Numbered § anchors retain their original identifiers.

| Task | Load next |
|---|---|
| Form the implementation plan | [Contracts](docs/state/contracts.md), [lifecycle](docs/architecture/lifecycle.md), [tools](docs/runtime/tools.md), [acceptance](docs/verification/acceptance-review.md), then the [roadmap](docs/implementation/roadmap.md). Add compiler, recovery and provider details when assigning their stages. |
| Implement context management | [Layout](docs/runtime/context-layout.md), [compiler](docs/context/compiler.md), [register / Workset](docs/runtime/register-workset.md), [residency / rebuild](docs/runtime/residency-rebuild.md); consult [continuity](docs/context/continuity.md) and adapter protocol preservation as needed. |
| Implement mutation or recovery | [Workspace / editing](docs/runtime/workspace-editing.md), [tools](docs/runtime/tools.md), [evidence / coherence](docs/state/evidence-coherence.md), [recovery](docs/operations/recovery.md), applicable [authority rules](docs/platform/security.md). |
| Implement acceptance and checks | [Scheduler](docs/verification/scheduler.md), [acceptance / review](docs/verification/acceptance-review.md), impact selection in [navigation](docs/repository/navigation.md#sec-7-4), and [refactor policy](docs/verification/refactoring.md) where applicable. |
| Implement parallel work | [Roles / shapes](docs/architecture/roles-shapes.md), [delegation](docs/operations/delegation.md), workspace/context identities in [components](docs/architecture/components.md#sec-3-3), and cancellation/publication in [recovery](docs/operations/recovery.md). |
| Implement providers, budgets or evaluation | [Adapters / accounting](docs/platform/adapters.md), [costs](docs/economics/costs.md), [routing](docs/operations/routing.md), [evaluation method](docs/evaluation/method.md). |
| Audit why a rule exists | The relevant [review finding](REVIEW.md), [source register](SOURCE-REGISTER.md), then the exact donor section if necessary. Do not reopen the original architecture competition. |

## Authority and context limits

The revised subsystem rules are the build specification. `REVIEW.md` explains them; it is not an overriding patch layer. `reference/candidate-review`, `reference/synthesis-history` and `reference/traceability` retain historical analysis and provenance. Load those for rationale disputes, not ordinary implementation. `sources/` is an audit archive, not startup context.

Search the [section migration map](audit/SECTION-MAP.md) to resolve old unqualified references such as `§8.1`. References explicitly labeled `[A §…]`, `[B §…]` or `[C §…]` refer to the supporting originals, not same-numbered current sections. Some inherited upstream sources were not supplied; their status is explicit in the source register.

The [kernel contract](docs/reference/kernel-contract.md) is compact model-facing policy for implementing/writer cells. It does not replace the full tool specification and must not be imposed as a STATE completion gate on reviewers, probes or curators.

Size columns in the index count bytes and whitespace-delimited words, not model tokens. Load exact sections or ranges when available. Preserve all required boundary contracts even when that costs more context; never substitute a short historical summary for current normative rules.
