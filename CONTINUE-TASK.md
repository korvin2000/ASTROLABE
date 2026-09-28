# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.

**Checkpoint (2026-09-28):** owner-requested out-of-order P7 work — a real LLM transport over AI Gate
(`../llm-transport-sdk/llm`, SDK S-01…S-17 landed) per `ASTROLABE_CHANGES_FOR_LLM_TRANSPORT_SDK.md`, with its claims
checked against the source. Branch `feat/ai-gate-transport` (from `main` @ `e66d428`), local commits only, not merged
or pushed. Journal: `audit/OUT-OF-ORDER-P7-AIGATE.md`. D-326–D-335. P0–P6 remain 185/185 DONE.

## This session
- Core seams A-01…A-09: `EstimatorFactory` (provider-api) + `CellModel.rebind` (routing to a smaller-output profile
  no longer throws), `ProviderError.ContextOverflow/Authentication/Timeout` (rebuild once / `BlockedExternal` / failed),
  breakpoints only for explicit-marker profiles, `OutputLimit` responses carry no calls, `ObservableAdapter` →
  `cell.model_progress`, `Astrolabe(estimators, ownsAdapter)`, `AstrolabeJava(config, ProviderAdapter, authority, estimators)`.
- New module `:provider-ai-gate`: `AiGateAdapter` (+ `gate` block v1 in `Profile.config`), translators, D-51 invocation
  over `LlmCall`/`CallOutcome`, usage from SDK buckets (unknown never zero), wire-body estimator, `AiGateProfiles.draft`.
  Included only when the SDK checkout exists (composite build; `-Pastrolabe.aiGateBuild=<path>` overrides).
- Doc corrections (journal table): reasoning tag uses the requested model (not `responseModel`), `EstimatorFactory` in
  provider-api, auth → `BlockedExternal`, conditional composite build, SDK APIs replace the doc's workarounds.
- Two independent Fable 5.1 reviews (core seams; adapter) — all findings fixed (`1ca19c2`, earlier review in `5ab328b`).
- ABI dumps regenerated for provider-api, core, provider-ai-gate (`11ce1bf`).

## Next
1. Full Windows build green (1,803 tests, 0 failures); merged `--no-ff` into local `main`; push only with approval.
2. CI does not build `:provider-ai-gate` until the workflow checks out the SDK next to ASTROLABE (or the SDK is
   published beyond `mavenLocal`). Decide which (D-332).
3. Doc phases 7–8: authorised live smoke (Anthropic, then Responses; `llm.test(model)` first), then gateways/Gemini/Codex.

## Carried-forward debts (unchanged)
D-254 recovery, D-70/D-71/D-241 replan and S3 re-selection, D-252 retrieval, D-113/D-120 behaviour maps,
D-124/D-244 review evidence, D-220/D-221 eval arms, D-200 QA scheduling, D-66, D-28, D-92.
Transient: `StamperTest` `git exited -1`; Windows `ProcOwnershipTest` READY timeout; FX-22 `bg-end` order.

## Blockers
None. `gh` is not installed locally.
