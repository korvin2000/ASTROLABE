# P7 provider transport over AI Gate (`llm-transport-sdk`) — `:provider-ai-gate`

Date: 2026-09-28. Baseline: clean `main` at `e66d428`; branch `feat/ai-gate-transport`. Authority: owner request
to implement `ASTROLABE_CHANGES_FOR_LLM_TRANSPORT_SDK.md` (the design doc, "the doc") against the SDK checkout
`../llm-transport-sdk/llm` (SDK commits `372969a` S-01…S-13/S-15/S-17, `877a1a6` S-14/S-16), checking its claims
rather than following it blindly. D-326–D-335. P7 row "Provider transports" is started out of order; P0–P6 unchanged.

## What was built

- Core seams (commit `fb8a778`): A-01 `EstimatorFactory` + `CellModel.rebind`; A-02 `ProviderError.ContextOverflow`,
  `Authentication`, `Timeout` with cell dispositions; A-03 capability-driven breakpoints; A-04 `ReasoningRef.origin()`;
  A-05 `OutputLimit` responses carry no calls; A-06 fixture only; A-07 `ownsAdapter`; A-08 `InvocationProgress`,
  `ObservableAdapter`, `cell.model_progress`; A-09 `AstrolabeJava(config, ProviderAdapter, authority, estimators)`.
- Module `:provider-ai-gate` (commit `5ab328b`): `AiGateAdapter`, `ProfileBinding`/`gate` block, `RequestTranslator`,
  `ResponseTranslator`/`UsageMapper`, `AiGateInvocation`/`ErrorMapper`, `AiGateEstimator`, `AiGateProfiles.draft`,
  `JsonBridge`. Tests (commit `bb6808d`): FakeProvider-backed adapter tests, recorded Anthropic Messages and OpenAI
  Responses SSE fixtures through the real codecs, translation/usage unit tests, one `Astrolabe` campaign end to end.

## Doc claims checked against the source

| Doc claim | Finding | Resolution |
|---|---|---|
| §3 A-01 puts `EstimatorFactory` in `core`, §4.1 returns it from the adapter | the adapter depends on `provider-api` only: unreachable | interface in `provider-api` (D-327) |
| §4.3 reasoning tag uses `responseModel ?: model` | SDK `Handoff` compares the stored origin with `model.ref()`; a dated response model makes the model's own thinking foreign and breaks Anthropic thinking+tool replay | tag with `reply.model()`/`reply.api()`; `responseModel` only in usage provenance (D-326) |
| A-04 "the full native reply is already persisted by `Cell.journalOutput`" | `journalOutput` filters `UsageItem`s and journals normalized items only | unrepresentable reply parts are not replayed (D-334) |
| A-02 authentication → `question = "re-authenticate …"` | a question maps to `WaitingForInput` (`Lifecycle`), an answer cannot fix credentials | `question = null` → `BlockedExternal` (D-331) |
| A-06 "residency may stub or drop older reasoning" | `Residency` never stubs, trims or removes a `ReasoningRef`; the rebuild tail keeps or drops whole turns | no code change; fixture locks it in |
| §6 unconditional `includeBuild("../llm-transport-sdk/llm")` | CI has no sibling checkout; the build would fail | conditional composite build (D-332) |
| §4.4/§4.5/§4.7/§4.2 workarounds (hand-rolled thread, raw per-API usage parsing, placeholder `ToolCall` lookup, adapter origin check) | S-02, S-04, S-10, S-12 have landed | `Llm.start`/`CallOutcome`, typed `Usage`, `ToolResult.of(callId, name, …)`, `HistoryPolicy.REJECT_LOSSY` |
| §4.8 "exact when S-09 exists" | `countTokens` calls the provider endpoint (network) on every estimate | local count over the prepared body; endpoint opt-in `gate.tokenCount` (D-334) |
| §4.5 default `gate.retry.maxAttempts = 2` | the SDK retries only failures documented as not processed (not billed) | SDK/profile retry policy kept (D-333) |
| §4.2 `effort` → reasoning "clamped by the SDK" | clamping is an adaptation: `strict` fails it; models without reasoning control fail too | adapter pre-clamps to supported levels and omits reasoning without control (D-333) |
| §4.6 scan preview warnings | S-08 makes billing-relevant adaptations fatal under `strict` | `strict` default + `strictCodes {option_adapted, max_tokens_clamped}` |

Verified as stated: `Astrolabe.kt` hard-coded `HeuristicEstimator`; `Controller.route` failed for a smaller routed
output limit; unconditional Layout breakpoints; `native != null` ⇒ unknown history; `Response` allowed calls on
`OutputLimit`; `ProviderAdapters.mapError`; `Astrolabe.close()` did not close the adapter; the `completeAsync` cancel
semantics.

## Validation

Windows / JDK 26, targeted: provider-api tests; core `CellTest`, `LayoutTest`, `CellModelTest`, `ResidencyTest`,
`EventsTest`, `TerminalAccountingTest`, `FakeAdapterTest`, `RecoveryCampaignTest`, `EscalationCampaignTest`;
provider-ai-gate 29 tests (adapter 15, Anthropic 8, Responses 1, translation 4, campaign 1). Two independent
reviews (Fable 5.1): core seams — blank-part origin tags, progress relay outside the accounting `try`, a weak tail
assertion (fixed in `5ab328b`); adapter — effort budgets vs output limit (bind-time probe), outcome translation
throws, billing of SDK-internal failures, cancelled flag, known-zero after a 2xx attempt, per-cell prepare cache
(fixed in `1ca19c2`). ABI dumps regenerated (`11ce1bf`).
Full build (`./gradlew build`, Windows, JDK 26, 31 min): green — provider-api 20, core 1,685 (20 platform skips),
eval 52, index-treesitter 17, provider-ai-gate 29 tests; 0 failures/errors; `checkKotlinAbi` passes.

## Not done / next owners

- Live smoke (doc phase 7): one Anthropic and one Responses profile, `llm.test(model)` first; gates stay `UNMEASURED`.
- Gateways (OpenAI-compatible), Gemini and Codex qualification (doc phase 8): probe before declaring `cache_read`.
- CI builds `:provider-ai-gate` only once it checks out the SDK (or the SDK is published beyond `mavenLocal`).
- G-09 live text deltas: the SDK's `Llm.start` does not expose `ChatEvent`s; a UI uses SDK events/streams directly.
- `continuation`, `nativeCompaction`, `hostedExecution` stay `false` until their ASTROLABE contracts exist (S-14 landed in the SDK).
