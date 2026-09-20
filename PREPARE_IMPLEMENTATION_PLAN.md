# Prepare a detailed TODO plan for future implementation

Start with `README.md` and analyze the complete architecture documentation first. Treat the documentation as the authoritative design baseline. At this stage there is no existing implementation and no existing `PLAN.md`: the task is only to prepare a high-quality implementation plan for the next step.

## GOALS

- Create a self-sufficient, implementation-ready `TODO.md` / planning document for implementing the described SOTA `ASTROLAB` AI coding-agent architecture.
- Derive the plan strictly from the existing architecture documentation; do not start implementation in this step.
- Translate the architectural design into a concrete, hierarchical, dependency-aware sequence of implementation tasks that can later be executed step by step by Codex, Claude Code, or another coding harness.
- Preserve the architecture, concepts, abstractions, component boundaries, workflows and terminology described in the documentation unless an implementation-critical ambiguity must be explicitly flagged.

## Technology stack

- Kotlin 2.4.20.
- Gradle as build tool.
- Use suitable third-party libraries where they clearly reduce boilerplate, duplicated infrastructure code or implementation complexity without introducing unnecessary framework weight.
- Target a reusable Kotlin/JVM SDK/library that can also be consumed from Java projects.

## Planning requirements

- Produce a hierarchical, well-thought, implementation-oriented TODO structure with phases, work packages and fine-grained executable tasks.
- Make the plan optimized for LLM readability and navigation: compact wording, stable task IDs, clear hierarchy, minimal duplication and low context/token overhead.
- Each implementation task should be concrete enough that a coding agent can execute it without re-deriving the whole architecture from scratch.
- For every meaningful task include, where useful: purpose, dependencies/prerequisites, affected subsystem or package, expected artifacts/classes/interfaces, relevant architecture references, implementation notes and clear completion criteria.
- Explicitly distinguish:
  - architectural scaffolding/contracts,
  - minimal working implementation,
  - integration between components,
  - validation/hardening,
  - optional or deferred work.
- Order tasks by real implementation dependencies rather than by documentation order.
- Start with project/module/package structure and core contracts/interfaces, then proceed through coherent implementation blocks until the complete architecture can be assembled.
- Avoid creating placeholder classes merely to mirror architecture diagrams. Introduce scaffolding only where it establishes a real contract, dependency boundary or later integration point.
- Design the plan for execution across multiple independent sessions. A new agent/session should be able to resume by reading only the architecture documentation plus this TODO file and the current task/progress state.
- Define a lightweight progress-tracking convention directly in the TODO structure, e.g. task IDs and statuses such as `TODO`, `IN_PROGRESS`, `BLOCKED`, `DONE`, plus short implementation notes where necessary.
- Identify architectural ambiguities, missing details or implementation decisions that cannot be derived confidently from the documentation, but keep them isolated as explicit decision/blocker items instead of silently redesigning the architecture.
- The result must be a planning artifact only. Do not generate production source code, implement classes, create a working Gradle project, or begin execution of the plan in this step.

## Code organisation and implementation constraints to reflect in the plan

- Organize the future implementation as an SDK/library/external module usable from Kotlin and Java projects.
- Design hooks/events for UI/frontend integration so external applications can observe agent execution, progress, tool calls, state changes, warnings and results without coupling the core engine to a specific UI.
- Plan for well-structured object-oriented Kotlin using modern language features where they improve clarity and compactness.
- Prefer reference-like, compact and readable code; avoid unnecessary layers, wrappers, managers, factories, excessive indirection and oversized abstractions.
- Use cohesive feature/domain packages in a logical hierarchy; avoid both a single flat package and needless package fragmentation.
- Names should be compact and self-explanatory: domain vocabulary, precise verbs, no redundant prefixes or type suffixes such as `ManagerImpl` or `userDataObject`; short locals are acceptable in tiny scopes; units should be explicit where relevant.
- Treat code structure and naming as primary documentation. Comments should explain only non-obvious rationale, invariants, algorithms, protocol constraints or compatibility requirements.
- For complex routines, workflows and orchestration, prefer appropriate algorithms and data structures over framework-heavy solutions: sets/maps for membership and joins, bounded heaps for top-k/scheduling, graph algorithms where applicable, explicit state machines for conditional workflows, compact dense representations where appropriate, and boundary parsing with reusable internal representations.
- Reflect testability, deterministic behavior, observability and clean component boundaries in the plan, but do not invent large testing or infrastructure subsystems not justified by the architecture.
- Plan a flexible transport/connection API compatible with the OpenAI Responses API and Anthropic Messages API at the abstraction/contract level.

## Explicitly outside this implementation plan

- Actual transport-layer/API-gateway implementation and live provider connectivity are out of scope for the implementation covered by this plan.
- Include only the interfaces, adapters, wrappers, stubs/fakes and integration contracts required so the rest of the architecture can be implemented and tested independently.
- Provider-specific networking, authentication, retry/backoff policy, production HTTP clients and deployment-specific gateway logic will be implemented separately.

## Final validation of the plan

Before finalizing the TODO document, critically review it against the architecture documentation and verify that:

- every major architectural component and workflow has a corresponding implementation path;
- task ordering respects dependencies and minimizes rework;
- no implementation step requires hidden assumptions that are absent from both the architecture documentation and the TODO;
- no large subsystem has been invented beyond the documented architecture;
- deferred transport/provider work is cleanly separated behind explicit contracts;
- the TODO is compact enough for repeated LLM use but detailed enough to drive implementation without repeatedly reconstructing the architecture;
- the final document is self-sufficient, internally consistent and ready to be used in the next phase: actual Kotlin implementation.
