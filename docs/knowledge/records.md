# Knowledge records and admission

**ASTROLABE 1.0.1 · specification** · Owner: Knowledge base / serialized curator.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §4.5. **Read with:** [learning](learning.md) · [continuity](../context/continuity.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F08](../../REVIEW.md#f08).

<!-- source-section: 4.5 -->
<a id="sec-4-5"></a>

### 4.5 Knowledge Base `[A §4.4 + C §7 + B §10 merged]`

```text
.astrolabe/kb/
  index/global.md          ≤ 1.5K tok: conventions, active ADRs, active CON notes — one line each; regenerated, never edited
  index/contracts.md       every active CON note, one line each — always visible to every role
  index/subsystem-<s>.md   ≤ 1K tok per subsystem view · behaviour/ progressive-disclosure maps
  notes/ADR-012.md  CON-007.md  LES-231.md  PIT-003.md  BMAP-payments.md  NEG-019.md  SKILL-migrate.md  STATUS-W-0042.md  CAL-repo.md
  raw/W-0042/cell-7/…      traces, packets, manifests — append-only, indexed by work/attempt/cell id
  queue/                   candidate notes awaiting admission
  schema.md · index.sqlite (optional disposable FTS view; canonical records and usage counters are in state.sqlite)
```

Note kinds: `ADR` (decision, signed), `CON` (cross-boundary contract; always visible when its paths are in scope), `LES` (lesson), `PIT` (pitfall, with the conditions under which it failed), `BMAP` (behaviour-to-code map, [§7.5](../repository/navigation.md#sec-7-5)), `NEG` (negative evidence: `unknown · unsearched · searched-empty(scope, version, index coverage) · contradicted · verified-absent(bounded domain)` `[MB §10.4; B §5.4]`), `SKILL` (procedure with trigger, prerequisites, modules, checks, failure exit `[IM §8.4]`), `STATUS` (campaign checkpoint, same task only), `CAL` (calibration prior, [§6.7](../context/continuity.md#sec-6-7)).

Front matter (every note): `id, kind, status (candidate·admitted·stale·superseded·deprecated·rejected), summary (≤200 chars, the index line), body (≤120 tokens, no code bodies) (compact note unit; BMAP/SKILL detail uses linked, versioned units/modules, whose mandatory parts remain subject to the total compile budget), scope (global·subsystem·path-glob·task-family·roles), anchors[{path, version, symbol}], confidence, basis {requirement_refs, evidence_refs}, validity {depends_on: [contract@v, path@hash], last_validated: stamp, invalidation_trigger}, supersedes, signed_by, origin {work, cell, extractor, admitted_by}, usage {injected, cited, last_cited}`.

Operations: `enqueue` (post-cell extraction) · `admit` (curator: dedupe by summary similarity, evidence present and resolvable, scope bounded, no contradiction with admitted notes, secrets redacted, not a one-off generalization) · `query` (id, tag, FTS over summaries and anchors; dense retrieval only after measured lexical misses `[IM §7.4]`) · `lint` · `regenerate-index` (deterministic) · `invalidate` (dependency-driven, [§4.4](../state/evidence-coherence.md#sec-4-4)) · `promote` (recurring `LES`/`PIT` ⇒ a test, linter or schema check proposed as a *task*, never auto-committed; the note becomes a pointer `[MB §10.3]`) · `prune` (usage-aware: injected repeatedly but never cited decays). The curator adds, supersedes or deprecates — **never rewrites a note body in place** `[MB A9; RN R07]`; writes are serialized.

**Admission policy (resolution of A/B vs C).** Interactive mode queues candidates for the user. Autonomous mode admits automatically only evidence-backed factual `LES` and conditional `PIT` candidates that pass deterministic lint, carry resolvable anchors, are scoped to a subsystem or task family (never global), and are marked `admitted_by: policy, confidence ≤ 0.6`; everything else waits in the queue. Memory is versioned so a bad batch rolls back independently of code `[C §7.6; A §12.2; B §10.2]`. The on/off/frozen ablation is the guard ([§19.5](../evaluation/method.md#sec-19-5)).

**Injection is precision-gated at compile time** ([§6.3](../context/continuity.md#sec-6-3)): cap 8 notes / 1.5K tokens per cell; `CON` notes for touched paths bypass the cap; return nothing when nothing is strongly relevant; never re-inject unchanged advice inside a cell. Retrieval misses (`Open (needs: CON-007)` lines, `kb.search` calls with a stated reason) are logged as labelled negatives for the ranker `[MB §4.4]`.
<!-- end-source-section: 4.5 -->

