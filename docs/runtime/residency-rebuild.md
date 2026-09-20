# Residency, eviction and rebuild

**ASTROLABE 1.0.1 · specification** · Owner: Context compiler / cell runtime.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §5.7, §5.8. **Read with:** [compiler](../context/compiler.md) · [adapters](../platform/adapters.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F03](../../REVIEW.md#f03).

<!-- source-section: 5.7 -->
<a id="sec-5-7"></a>

### 5.7 Gauge, residency and eviction `[A §5.7; C §6.5; HELM §7.6; J1 §5.5]`

Every tool result ends with the ~20-token gauge. Tool results live in full for `k = 8` turns, then become ~20-token stubs; because a turn may carry several results and pinned items exist, the age rule alone does not bound residency `[J1 §5.5]` — a **total live-result budget** `R_max = 16K` tokens is enforced in addition, stubbing the oldest refetchable results early when exceeded. Within a batch the stubbing order is by refetchability `value = p_reuse · c_refetch`: raw observations of current repository state first, verdicts (diffs, exit codes, receipts) last `[C3 §6.3]`. Model messages older than `3k` turns are trimmed to their first line plus the calls made. User messages and the packet are pinned. Stubs are `recall`-able; the store is searchable; an evicted result is a recoverable *pointer to captured bytes*, not a bare address `[J1 §5.4]`.

```text
C(t) = |S| + |R| + |K| + |T_live(t)| + |A(t)|        T_live = Σ live results (≤ R_max) + σ·|stubs| + model messages
uncached per turn ≈ |A| + new model message + new results;   cached ≈ S + R + K + T_live
cache miss: once per k turns (batch eviction), once per rebuild, once per immediate stub of a large stale body
```
<!-- end-source-section: 5.7 -->

<!-- source-section: 5.8 -->
<a id="sec-5-8"></a>

### 5.8 One rebuild mechanism, five uses `[C §6.7 + A §5.8, §6 — NEW unification N2]`

```text
rebuild(reason ∈ {pressure, resume, role_switch(role'), alternative_attempt(profile'), cell_end(next_increment | continuation)}):
    checkpoint: persist STATE, Workset export, receipts, journal; (role_switch, cell_end) write a STATUS note; (alternative) new attempt id
    [S][R] ← reused only when their input versions still match; otherwise recompiled for authority/dependency/role/profile changes
    [K]    ← compile(increment', seeds = referenced Workset entries re-served at current versions, carry-forward)     # §6
    [T]    ← applicable pinned messages + packet + note("rebuilt: <reason>") + last m = 6 complete protocol turns with stubs
              (m = 0 for role_switch, alternative_attempt and cell_end; no proposer transcript in a review context)
    [A]    ← contract digest + STATE (validated; stale facts tagged; Dead ends emphasised for alternative) + KNOWN = seeds only (declared)
```

Pressure (`ctx ≥ α = 0.65`) and resume use it unchanged; sequential role switching in S1 uses it with a new role's mask and knowledge view; the alternative attempt ([§13.3](../operations/recovery.md#sec-13-3)) uses it with an empty tail and, optionally, the escalation profile; **cell end** uses it with `m = 0` and the next increment's `[K]` — the campaign layer and the kernel share one implementation of "start from validated state". **No model summarises anything at rebuild.** STATE is the only model-written summary used for rebuild — bounded, validated, evidence-referenced and maintained continuously rather than written under pressure `[J1 §5.4; C §6.7]`. A cell that rebuilds under pressure twice is terminated as `partial` with a `replan` hint: two rebuilds mean the increment was mis-sized `[A §5.8]`. A provider continuation identifier is never reused across a role switch `[MB §3.6]`. The adapter must likewise not reuse a continuation whose implicit history restores evicted bodies, replaced `[K]` or earlier volatile anchors. Reconstruct the selected native history or start a compatible fresh provider lineage. Preserve complete tool-call/result units and required opaque items; a local rebuild replaces the whole context/Workset projection, not just `T`. Checkpoint the old projection before installation and record the new generation; do not summarize at rebuild.
<!-- end-source-section: 5.8 -->

