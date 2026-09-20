# Working Register and Workset

**ASTROLABE 1.0.1 · specification** · Owner: Cell runtime / context-local coverage projection.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §5.2, §5.3. **Read with:** [evidence-coherence](../state/evidence-coherence.md) · [tools](tools.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F02](../../REVIEW.md#f02), [F08](../../REVIEW.md#f08).

<!-- source-section: 5.2 -->
<a id="sec-5-2"></a>

### 5.2 The Working Register (STATE) `[A §5.2 + C §8.2 + B decision packets]`

```markdown
# STATE v14 · cell 8 · I2 "thread ctx through handlers"
## Constraints (inferred)  - keep refund flow untouched (exclusion) · keep Router API (C1)
## Plan       1. [x] locate dispatch (#12)   2. [>] pass ctx into handlers  accept: run: pytest -k ctx  → R2/AC-4
              3. [ ] update 3 call sites  after: 2   4. [~] cancelled: rename Router — out of scope (C1)
## Facts      - v `Router.dispatch(req, ctx)`  src/router.py:88 @a9f1 [#17]
              - h handlers are all keyword-only            (h in NEXT ⇒ flagged risk)
              - x popleft is atomic here                   (refuted #31; kept)
              - v(stale @c02e) handle_user takes 1 arg     src/handlers/user.py:42 [#22]   ← harness-rendered; re-look
## Dead ends  - monkeypatching ctx → import cycle (#22)   scope: handlers built directly in tests   reopen: fixtures isolated
## Decisions  - D1: pass ctx explicitly, not via contextvar — because tests construct handlers directly; rejected: contextvar;
                probe: run pytest -k "direct_construct"   (→ candidate ADR)
## Open       - Q1: does CLI path build handlers? (trip: any edit under src/cli/ → check)  (needs: CON-007)
## Focus      src/handlers/
## Amendments - propose AC-1 cmd → "pytest tests/payments -q -k 'not slow'" because the slow suite needs a live DB (pending)
## Next       edit src/handlers/user.py:42 signature, then run accept
```

**Typed ops** (`state(patch: [...], if?)`): `plan.add(text, accept?, after?, req?)` · `plan.cursor(n)` · `plan.tick(n, evidence)` · `plan.cancel(n, reason)` · `fact.add(kind h|v|x, text, evidence?, anchor?)` · `fact.refute(n, evidence)` · `deadend.add(text, evidence, scope, reopen)` · `decision.add(text, because, rejected, probe?)` · `open.add(text, trip?, needs?)` / `open.close(n, evidence)` · `focus.set(dir)` · `amend.propose(change, reason)` · `next(text)`. Ops may be conditional on a run in the same turn (`if: green(op:N)`) so a fact is never recorded as verified before its evidence exists `[HELM T5]`.

**Harness-enforced invariants** `[HELM §7.3, T2, W4, W5, R2, R3; A §5.2; C §8.2]`: size cap 1,200 tokens (acceptance lives in the contract, not here); exactly one `[>]` while `[ ]` exists; exactly one `Next`; `plan.tick` requires the step's `accept:` green on the current version or an evidence `#id`; `v` requires an evidence id that exists in the store; a `v` fact whose anchor moved is rendered `v(stale @old)` by the harness — the model cannot remove the tag except by re-verifying; fact lines ≤240 chars, no fenced code; refuted facts are kept in the durable register history; active or dependency-referenced ones remain in the bounded projection, while inactive records can move verbatim to the same-task STATUS note with resolvable ids; dead ends carry scope and a reopen condition; `[~]` requires a reason; `h` in the active step is flagged; a red verification line must be fixed or recorded in `Open` before `[>]` advances; `Amendments` is the only place the model may touch acceptance. Epistemic kind (`h/v/x`) and freshness (`current/stale`) are separate axes `[J1 §7.2]`. Output cost per turn is the patch (~30–150 tokens); patches above 400 tokens are rejected `[J2 §8 vs WK]`.
<!-- end-source-section: 5.2 -->

<!-- source-section: 5.3 -->
<a id="sec-5-3"></a>

### 5.3 The Workset and cache-aware staleness `[A §5.3 + C §6.6 — resolution of the immediate-stub disagreement]`

```text
workset = { (path, range, version, source: look|post-edit|seed|recall, turn) }   token-budgeted, not file-counted
KNOWN    : entries whose version == current file hash and whose bytes are live (unstubbed) in [T] or [K]
NOT SEEN : everything else — one line, plus named stale drops:
           "src/handlers/user.py:30-60 stale @c02e (edited by transform #40) → recall #45 or read again"
```

Rules: only exact source bytes actually delivered in the current projection register displayed ranges; an outline, symbol location or enclosing span does not make an undisplayed body KNOWN. `look(def)` counts only for the source bytes it actually renders; an `edit` post-view registers the new range at the new version; a **seed** is a harness-served excerpt in `[K]` with its hash (rendered ⇒ displayed); an anchored hunk outside KNOWN is rejected with the file outline; a version change **drops the entry from KNOWN and announces it in the same turn** — but the physical stub of the stale body happens **at the next eviction batch**, unless the stale body exceeds 800 tokens, in which case it is stubbed immediately `[C §6.6]`. Edit safety rests on the region-seen precondition and the CAS `expect`, not on rewriting the cached transcript; HELM's immediate stub `[HELM W2]`, which A carried, would miss the prompt cache on almost every edit turn. Stubbing a result removes its ranges from KNOWN; `recall` makes them KNOWN again at the recorded version, labelled `historical` if the file changed since; an edit still needs a current-version read. The Workset is exported at cell end and re-served as seeds by the compiler ([§6.2](../context/continuity.md#sec-6-2)). Ordinary edits use coverage from the model request that produced the edit: a parallel probe’s reads, or a read result first produced earlier in the same emitted batch, cannot authorize that already-generated edit. The next model request may use the newly delivered coverage. Ablation: immediate vs mark-then-batch ([§19.5](../evaluation/method.md#sec-19-5)).
<!-- end-source-section: 5.3 -->

