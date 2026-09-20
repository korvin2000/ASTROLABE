# Cell context layout

**ASTROLABE 1.0.1 · specification** · Owner: Context compiler / cell runtime.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §5, §5.1. **Read with:** [compiler](../context/compiler.md) · [residency-rebuild](residency-rebuild.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F06](../../REVIEW.md#f06).

<!-- source-section: 5 -->
<a id="sec-5"></a>

## 5. The Cell (execution kernel)

The cell is HELM's loop `[HELM §7]` with judje-1's corrections and six changes agreed by A and C: the contract slice and knowledge slice arrive as compiled `[K]`/`[R]` segments; the register no longer carries acceptance; the Workset is explicit; verification lines carry absolute status; the tool surface gains verify, ask, delegate and knowledge as modalities; stale reads are marked immediately but stubbed at the batch. Everything else — CAS edits, stubs and recall, entry/exit gates, gauge, jail — is carried as baseline.
<!-- end-source-section: 5 -->

<!-- source-section: 5.1 -->
<a id="sec-5-1"></a>

### 5.1 Context layout `[A §5.1 + C §6.4 + MB §4.1 render order]`

```text
[S] system  (~1.0K tok, byte-stable per role for the whole session — cache breakpoint)
    kernel contract (Appendix A) · tool schemas for the role's mask (masked, never removed) · evidence-category lines ·
    error policy · data/instruction rule · execution-mode label (trusted-local | confined)
[R] repo prime + project knowledge  (~1.5–3K tok, byte-stable per repo version and role — cache breakpoint)
    tree digest · languages · sniffed commands · rules file (the ONLY trusted repo text) · hubs ·
    index/contracts.md · index/global.md (one line each) · behaviour-map excerpt for the focus subsystem (≤300)
[K] compiled increment context  (~2–6K tok, stable within the cell — cache breakpoint)
    contract slice: this increment's requirements (verbatim), ALL constraints and exclusions, acceptance ids and kinds ·
    CON/ADR notes touching write_scope · workset seeds (harness-served, hashed, ≤4K) · ≤8 ranked notes (≤1.5K) ·
    skill modules · carry-forward (dead ends, open items, decisions, last verification status) · pre-existing-failure ledger
[T] transcript  (append-only between eviction batches — cache breakpoint at its end)
    main-line user messages pinned verbatim; children use exact applicable excerpts + authority refs (§20.2 D13) · packet · model messages · calls · results | stubs
[A] anchor  (≤2.5K tok, typical ~1.2–1.8K, rebuilt every turn, never persisted, never cached)
    contract digest (≤150: exact goal excerpt + ids/status; full authoritative wording remains in [K]/pinned messages) · STATE register (≤1.2K) · Workset KNOWN / NOT SEEN (≤60) ·
    Touched (≤10) · Checks (≤3 lines, Δ + absolute + stamp) · focus atlas zoom (≤300) · focus notes (≤300, each once per cell) ·
    gauge · nudges (≤2)
```

Cache discipline `[MB A2; C §12.2; B §7.1]`: cache breakpoints sit at the end of `[S]`, `[R]`, `[K]` and `[T]`; `[A]` is the volatile tail. No timestamps or counters in cached regions. Tools are masked, never removed, so schemas stay byte-stable; consecutive cells for the same role and profile are scheduled adjacently where latency allows so `[S][R]` stay hot `[C §5.6]`. The contract digest is rendered at the tail (Manus recitation, `[MB §4.6]`) but capped at 150 tokens because it is uncached every turn; the requirement text itself lives in `[K]`. `[A]` size and STATE upkeep are first-class metrics `[C §6.8]`. Segment stability is keyed to its actual inputs, not an entire evolving repository: `[R]` is a labelled compile-time orientation snapshot, while current atlas/Workset views live in `[A]` and tool results. Recompile affected mandatory content when authority or dependencies change; do not keep it stale to preserve a cache hit. Logical breakpoints are adapter-validated provider capabilities, not guaranteed cache entries.
<!-- end-source-section: 5.1 -->

