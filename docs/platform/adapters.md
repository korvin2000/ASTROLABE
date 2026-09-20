# Provider adapters, accounting and observability

**ASTROLABE 1.0.1 · specification** · Owner: Provider adapters / telemetry.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §15, §15.1, §15.2, §15.3, §15.4, §15.5. **Read with:** [tools](../runtime/tools.md) · [costs](../economics/costs.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F03](../../REVIEW.md#f03), [F10](../../REVIEW.md#f10), [F11](../../REVIEW.md#f11).

<!-- source-section: 15 -->
<a id="sec-15"></a>

## 15. Platform: adapters, accounting, MCP, observability
<!-- end-source-section: 15 -->

<!-- source-section: 15.1 -->
<a id="sec-15-1"></a>

### 15.1 Provider adapters `[MB §11; B §12.1–12.2; C §12.1]`

An **item-based** internal message model (`message · tool_call · tool_result · reasoning_ref · usage · opaque_continuation`) maps onto OpenAI Responses items and Anthropic Messages content blocks without loss; two native adapters plus an OpenAI-compatible fallback with **verified, not assumed** parity. Each adapter separates capability description (tool schema validation, parallel tool requests, streaming, output/context limits, native compaction, continuation, cancellation, hosted execution, caching with its breakpoints and minimums, usage fields), request construction, event normalization, continuation handling and usage/error accounting. Provider errors, tool failures and task-level verification failures have three different retry semantics and are recorded separately. Never execute a half-generated tool call because a stream ended; preserve native tool-call/result pairs through eviction — if safe reduction cannot fit them, return an explicit capacity condition rather than a malformed history `[B §7.4]`. A gateway exposing one endpoint does not establish identical behaviour underneath; capabilities are probed, never inferred from an API-shaped URL. Hosted (provider-side) tool output enters the same evidence and accounting pipeline but does not inherit capabilities for the local executor `[B §12.1]`. A universal "mask rather than remove tools" instruction is a caching heuristic, not a cross-provider correctness rule — the adapter validates it `[B §12.2]`. Persist the complete validated native assistant output, including tool calls and required opaque items, before adding matching results. Dispatch only complete schema-valid calls; keep a result or explicit not-executed disposition for each accepted call id. Local phase ordering never permits malformed native history. Effective continuation history, not payload size, is subject to context admission ([§6.1](../context/compiler.md#sec-6-1)).
<!-- end-source-section: 15.1 -->

<!-- source-section: 15.2 -->
<a id="sec-15-2"></a>

### 15.2 Accounting without double counting `[FROM-B §12.3]`

For each call, retain the native usage object and normalized categories with explicit semantics: OpenAI cached-input counts are a subset of reported input; where the selected model/API also reports `input_tokens_details.cache_write_tokens`, ordinary input is `input_tokens − cached_tokens − cache_write_tokens`, with each category priced once using that profile’s dated mapping; Anthropic reports input, cache-read and cache-creation as separate categories whose sum is total input. Apply provider-specific mappings; never add fields with similar names indiscriminately; do not count reasoning twice when it is already inside output usage; preserve `unknown` and bounded estimates when usage is incomplete; missing usage is recorded as missing, never as zero. Compute money from the applicable dated price table and the actual billable categories, including hosted-tool charges. Report end-to-end wall time and aggregate worker duration separately; parallel duration is not additive latency. Report cold and warm cache/memory conditions separately. Persisted conversation, cache reuse, source memory and restart durability are four distinct features `[MB §11.3]`.
<!-- end-source-section: 15.2 -->

<!-- source-section: 15.3 -->
<a id="sec-15-3"></a>

### 15.3 MCP mounts and external capabilities `[C §12.3; MB §8.6; QA §4.6]`

External capabilities are **mounted**, not added as tools: `look(catalog)` lists them as one-liners; `run(["mcp:<server>/<tool>", …])` invokes them through the same envelope, store, shaping and effect classes (locally approved, validated read-only capability → `R`; remote annotations are hints, otherwise `D` until configured). Tool schemas never change mid-session.
<!-- end-source-section: 15.3 -->

<!-- source-section: 15.4 -->
<a id="sec-15-4"></a>

### 15.4 Adapter acceptance fixtures `[FROM-B §12.4]`

Streamed tool calls interrupted before completion; multiple tool-result pairing; an output-limit stop; provider refusal; expired continuation; native compaction; model-family change at a packet boundary; cancellation with late output; missing usage; cached-token normalization per provider. Recorded protocol fixtures plus a small authorized integration smoke campaign; a successful text completion alone does not qualify an adapter for autonomous coding.
<!-- end-source-section: 15.4 -->

<!-- source-section: 15.5 -->
<a id="sec-15-5"></a>

### 15.5 Observability `[A §15; C §12.5; MB §13; B §15.4]`

**Per cell**: tokens by cache class, `[A]` size, STATE upkeep tokens, tool calls, seconds in tools, checks run by layer, gates fired, rebuilds, turns, boundary reason, manifest, pre-compilation hit/miss. **Per campaign**: cost per accepted task, first-attempt increment pass rate, verified/blocked/cancelled increments, continuations per increment, rebuilds per cell, boundary cost share, probes and reviews with cost and whether their findings were used, escalations, alternative attempts, human interventions with reasons (missing requirement · scope decision · environment · approval of an external effect · incorrect implementation). **Per project**: KB usage rates, retrieval misses, routing calibration quadruples, MAST-tagged failure distribution, calibration prior drift, post-merge reverts and churn as deployment outcomes. Phase tags on every event — `understand · locate · edit · verify · recover · retrieve · compact · delegate · plan · review · integrate` — with parent/child span ids; exclusive cost recorded once at its producing span, inclusive totals derived without double counting; critical-path wall clock separate from summed worker time `[B §15.4]`. Packets, receipts, manifests and traces are files — diffable, replayable, agent-readable; OpenTelemetry GenAI spans where available.

---
<!-- end-source-section: 15.5 -->

