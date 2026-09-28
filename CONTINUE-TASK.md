# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.

**Checkpoint (2026-09-28):** owner-requested out-of-order P7 work — a real LLM transport over AI Gate
(`../llm-transport-sdk/llm`, SDK S-01…S-17 landed) per `ASTROLABE_CHANGES_FOR_LLM_TRANSPORT_SDK.md`, its claims
checked against the source. First release merged `--no-ff` into local `main` (`500c94f`) after a green full build;
follow-up on `feat/ai-gate-qualification`, merged the same way once green. Nothing pushed.
Journal: `audit/OUT-OF-ORDER-P7-AIGATE.md`. D-326–D-336. P0–P6 remain 185/185 DONE.

## This session
- Core seams A-01…A-09: `EstimatorFactory` (provider-api) + `CellModel.rebind`, `ProviderError.ContextOverflow/
  Authentication/Timeout`, capability-driven breakpoints, `OutputLimit` without calls, `ObservableAdapter` →
  `cell.model_progress`, `Astrolabe(estimators, ownsAdapter)`, `AstrolabeJava` with a provider-module adapter.
- `:provider-ai-gate`: `AiGateAdapter` (+ `gate` block v1), translators, D-51 invocation over `LlmCall`, usage from SDK
  buckets, wire-body estimator, `AiGateProfiles.draft`/`qualify`, `LiveSmokeTest` + `liveTest` (opt-in, billable).
- Offline evidence: AX-01..10 against recorded Anthropic/Responses frames and the SDK fake; gateway, Gemini, Codex
  fixtures; one `Astrolabe` campaign end to end. Three Fable 5.1 reviews; findings fixed.
- Full Windows build (first release): 1,803 tests, 0 failures, ABI checks pass.
- CI: optional checkout of `korvin2000/llm-transport-sdk` beside the repository (`continue-on-error`).

## Next
1. Owner: push `main` when approved; CI then builds `:provider-ai-gate` if the SDK repository is reachable (a private
   one needs the `LLM_TRANSPORT_SDK_TOKEN` secret; otherwise the module is skipped, D-332/D-336).
2. Owner-authorised live smoke: set `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `GEMINI_API_KEY`, then run
   `./gradlew :provider-ai-gate:liveTest`; record usage/cancellation evidence in the journal. Gates stay `UNMEASURED`.
3. Before using a gateway profile, run `AiGateProfiles.qualify(llm, draft)` against it and freeze the narrowed profile.

## Carried-forward debts (unchanged)
D-254 recovery, D-70/D-71/D-241 replan and S3 re-selection, D-252 retrieval, D-113/D-120 behaviour maps,
D-124/D-244 review evidence, D-220/D-221 eval arms, D-200 QA scheduling, D-66, D-28, D-92.
Transient: `StamperTest` `git exited -1`; Windows `ProcOwnershipTest` READY timeout; FX-22 `bg-end` order.

## Blockers
None. `gh` is not installed locally.
