# Source and evidence register

**Review date: 20 September 2026.** This maintenance review uses the supplied baseline as architecture authority, its three donors as supporting evidence, and targeted external checks for operational facts and research limits. Source agreement is not runtime validation.

## Supplied material

| Source | Role in this revision | Archive |
|---|---|---|
| ASTROLABE 1.0 | Authoritative architecture; all original sections mapped into the revision | [Original baseline](sources/SOTA-BEST-MIXED-AGENT.md) |
| A — WAYPOINT | Recover decomposition, Workset, scheduler and refactor rationale | [A](sources/SOTA-CODING-AGENT_A.md) |
| B — evidence-guided runtime | Recover evidence, ownership, failure, native protocol and economics details | [B](sources/SOTA-CODING-AGENT_B.md) |
| C — SEXTANT | Recover role, packet, effect, lifecycle and kernel semantics | [C](sources/SOTA-CODING-AGENT_C.md) |

The [source manifest](audit/source-manifest.json) records byte counts and SHA-256 hashes. The original baseline is 232,829 UTF-8 bytes and 1,721 physical lines; a viewer including the trailing empty line may report 1,722.

<a id="citation-convention"></a>

## Citation convention and unavailable sources

Unqualified `§x` in the revised body refers to the baseline section numbering, resolved through explicit links or the [migration map](audit/SECTION-MAP.md). `[A §x]`, `[B §x]` and `[C §x]` refer to the corresponding archived donor. `Appendix A` is the retained implementing-cell kernel contract.

Inherited labels `[HELM]`, `[J1]`, `[J2]`, `[QA]`, `[MB]`, `[IM]`, `[RN Rxx]` and `[C1]`–`[C5]` refer to the older corpus named in the original metadata. The associated HELM/judje files, ideas documents, candidate files, `GOAL.md` and `SOTA-RESEARCH-NOTES.md` were **not uploaded**. Their labels and rationale are retained as inherited provenance, not newly verified quotations or proof that their entire content was inspected. Donor prose about “twelve sources checked” is historical, not the scope of this maintenance review.

A/B/C differ in architecture and source numbering. No donor’s same-numbered section silently overrides ASTROLABE, and no unavailable source was reconstructed from memory. The review identifies source-derived contradictions separately from engineering inferences and primary web checks.

<a id="primary-checks"></a>

## Primary-source checks performed

The following eight official documentation checks and three targeted paper checks are the external evidence used. They are not a complete literature survey, an independent replication or an audit of every inherited reference. URLs and versions identify the inspected source; no full external article is redistributed.

<a id="w01"></a>

### W01 — OpenAI — Prompt caching

Source: `https://developers.openai.com/api/docs/guides/prompt-caching`

**Supported point:** Cache behavior and usage mappings are model/API-dependent. Where cache-write tokens are reported inside input, ordinary input subtracts both cached reads and writes. Separate those categories and preserve the native usage object.

**Use:** F06, F10. **Limit:** Official reference; inspected on 2026-09-20. Do not apply newer-model fields to every compatible endpoint.

<a id="w02"></a>

### W02 — Anthropic — Prompt caching

Source: `https://platform.claude.com/docs/en/build-with-claude/prompt-caching`

**Supported point:** Explicit pre-warming uses a provider request; local compilation alone is not a cache write. Total input combines ordinary, cache-read and cache-creation fields.

**Use:** F06, F10. **Limit:** Official reference; inspected on 2026-09-20. Pre-warming is not introduced as a required ASTROLABE workflow.

<a id="w03"></a>

### W03 — OpenAI — Function calling

Source: `https://developers.openai.com/api/docs/guides/function-calling`

**Supported point:** The function-call exchange retains native assistant output and pairs each tool result with its call ID.

**Use:** F03. **Limit:** Official protocol documentation; inspected on 2026-09-20. The revised pseudocode is illustrative, not an SDK implementation.

<a id="w04"></a>

### W04 — OpenAI — Conversation state

Source: `https://developers.openai.com/api/docs/guides/conversation-state`

**Supported point:** Continuation reduces retransmission, not the obligation to account for effective history; chained previous input remains billable.

**Use:** F03, F06. **Limit:** Official reference; inspected on 2026-09-20. Reduction must not reintroduce unwanted old history through provider state.

<a id="w05"></a>

### W05 — Git — git-worktree

Source: `https://git-scm.com/docs/git-worktree`

**Supported point:** Ordinary refs are shared across linked worktrees; per-worker shadow refs therefore require a distinct namespace. Worktree separation is not an OS capability boundary.

**Use:** F02. **Limit:** Official documentation; inspected on 2026-09-20. Workspace suffix is this revision’s engineering inference.

<a id="w06"></a>

### W06 — Git — git-update-ref

Source: `https://git-scm.com/docs/git-update-ref`

**Supported point:** Ref updates can verify an expected old object ID. That protects publication of the ref, not atomic restoration of every file.

**Use:** F02, F05. **Limit:** Official documentation; inspected on 2026-09-20. Filesystem restoration still needs guarded recovery.

<a id="w07"></a>

### W07 — SQLite — Atomic Commit

Source: `https://sqlite.org/atomiccommit.html`

**Supported point:** SQLite’s commit guarantees concern its database and documented storage assumptions, not arbitrary repository mutations or remote effects.

**Use:** F05, F08. **Limit:** Official engineering documentation; inspected on 2026-09-20. No new transaction framework was inferred.

<a id="w08"></a>

### W08 — MCP — Tools specification, 2025-06-18

Source: `https://modelcontextprotocol.io/specification/2025-06-18/server/tools`

**Supported point:** Tool annotations from untrusted servers are not trusted authority. A read-only hint cannot grant an executor capability.

**Use:** F11. **Limit:** Pinned official specification version; inspected on 2026-09-20. This is not a claim that every later MCP capability was audited.

<a id="w09"></a>

### W09 — The Complexity Trap, arXiv:2508.21433v3

Source: `https://arxiv.org/html/2508.21433v3`

**Supported point:** Observation masking is a meaningful baseline; hybrid comparisons are workload-specific. The paper does not make deterministic rebuilding universally optimal.

**Use:** F12; preserved reduction rationale. **Limit:** Primary research, version pinned; inspected on 2026-09-20. Deterministic rebuilding remains the authoritative baseline choice; no summarizer was added.

<a id="w10"></a>

### W10 — SoL-Pi, arXiv:2609.20519v1

Source: `https://arxiv.org/html/2609.20519v1`

**Supported point:** The complete efficiency configuration and highest-scoring configuration are different operating points. Their largest savings and best quality cannot be combined into one result.

**Use:** F12; preserved economics caveat. **Limit:** Primary research, version pinned; inspected on 2026-09-20. Reported findings do not validate ASTROLABE or establish a universal fusion policy.

<a id="w11"></a>

### W11 — The Missing Complement, arXiv:2609.20050v1

Source: `https://arxiv.org/html/2609.20050v1`

**Supported point:** Constructed-pool evidence coverage and gold-blind repository discovery are different claims. Complementary retrieval is useful motivation, not proof of end-to-end repair success.

**Use:** F12; preserved retrieval rationale. **Limit:** Primary research, version pinned; inspected on 2026-09-20. No additional retrieval-agent orchestration was adopted.

## What web research did not change

No new orchestration layer, memory framework, summary model, graph server or provider-managed agent runtime was imported. Current provider features are adapter capabilities to validate, not grounds for replacing the harness. Existing architecture ratings, numerical defaults, LOC estimates and economic examples remain editorial estimates or hypotheses. Their retention is not independent endorsement of measured performance.
