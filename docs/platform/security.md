# Execution authority and integrity

**ASTROLABE 1.0.1 · specification** · Owner: Executor / controller.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §14, §14.1, §14.2, §14.3, §14.4. **Read with:** [contracts](../state/contracts.md) · [workspace-editing](../runtime/workspace-editing.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F11](../../REVIEW.md#f11).

<!-- source-section: 14 -->
<a id="sec-14"></a>

## 14. Safety and integrity
<!-- end-source-section: 14 -->

<!-- source-section: 14.1 -->
<a id="sec-14-1"></a>

### 14.1 Execution modes and effect classes `[C §8.5; A §14.1; J1 §5.7]`

See [§4.6](../runtime/workspace-editing.md#sec-4-6). Offer an explicit `trusted-local` runner and a `confined` runner using a real external isolation mechanism; state their capabilities honestly in `[S]` and in every report. The executor enforces filesystem roots, network scope, credentials, resource limits and allowed publication stages. A role instruction, tool description, worktree or command denylist is not confinement `[B §8.6]`. Interface redesign or a migration may already be the requested task; the harness must not introduce a blanket permission prompt that defeats authorized autonomy `[B §8.6]`.
<!-- end-source-section: 14.1 -->

<!-- source-section: 14.2 -->
<a id="sec-14-2"></a>

### 14.2 Permission ladder and human anchors `[MB §9.4; A §14.2; C §5.4]`

`patch → local commit (shadow ref or branch) → push → merge → deploy` are separate grants; the contract sets the ceiling. Autonomous commit requires: ceiling ≥ commit, low blast radius, easy reversibility, L0–L2 green with current stamps and (S2+) a judge approval. Human anchors by default: interface-contract changes, data migrations, production deploys, new network access, elevation of the ceiling. The harness reports the highest *authorized* stage reached, never "delivered" for a patch. Model-generated metadata can never grant permissions, lower mandatory verification or raise spending limits.
<!-- end-source-section: 14.2 -->

<!-- source-section: 14.3 -->
<a id="sec-14-3"></a>

### 14.3 Instruction / data boundary `[HELM §7.8, A7; MB §12; A §14.3; C §12.4]`

Every tool result, KB note body, packet and file body enters the window inside harness-owned delimiters; `[S]` states that content inside them is data. Instructions come from user messages, the contract and the configured rules file only; no other repository text is an instruction source. Load an authorized, version-pinned rules snapshot: editing that path cannot silently elevate the edited bytes into instruction authority. Delimiters and instruction-shape detection are model-facing cues, not security proofs; escape delimiter-like payload bytes and enforce capabilities independently. Instruction-shaped content inside data is flagged in the envelope, never filtered silently, never executed. Capability is enforced in the executor regardless of what the model requests; generated scripts and MCP mounts inherit the caller's ceiling; secrets are redacted before model exposure and reusable evidence persistence, with capture limitations recorded; late publication from superseded cells is rejected, while factual effects remain archived for reconciliation. Exact rollback preimages are separately access-controlled local recovery artifacts, never silently redacted into unusable inverse patches or exposed to the model/KB.
<!-- end-source-section: 14.3 -->

<!-- source-section: 14.4 -->
<a id="sec-14-4"></a>

### 14.4 Threat table `[MB §12; A §14.4; C §12.4]`

| Threat | Control |
|---|---|
| Prompt injection via repo, notes, web or tool content | delimiters; rules-file-only channel; executor-enforced authorization; flagged instruction-shaped content |
| Confused deputy via scripts, transforms, MCP mounts | jail; capability ceiling; tool-generation authority excludes network, credentials, filesystem roots, deployment |
| Secrets in memory or receipts | redaction before persistence; env allowlists in `confined`; no credentials in packets |
| Unsafe retry | intent journal; reconcile-before-retry; `unknown_outcome` class |
| Sandbox escape / resource abuse | `confined` mode via an external runner; per-tool error budgets; request caps; resource limits |
| Stale or forged verification | receipts bound to stamps, patch hash and environment; executor-assigned status; parsed counts only; late superseded results rejected |
| Runaway spend / doom loops | budgets at cell, increment, campaign and task tree; fingerprints; global no-progress budget; cancellation before publication |
| Memory poisoning | admission queue; provenance; lint; scoped candidates; usage decay; versioned rollback; notes are data |
| Weakened acceptance | contract outside model authority; test-integrity guard; review with the original obligation |
| User work damaged | shadow ref; dirty-state record; guarded revert; permission ladder; no `reset` / `clean` |

---
<!-- end-source-section: 14.4 -->

