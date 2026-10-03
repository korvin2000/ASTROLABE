# Rendered turn example

**ASTROLABE 1.0.1 · example** · Owner: Illustrative runtime rendering.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §5.10 (structured protocol), §5.10-D (direct protocol, ASTROLABE 2.0 — **SPEC, implemented in P8.D.1–D.3**). **Read with:** [context-layout](../runtime/context-layout.md) · [kernel-contract](kernel-contract.md). Load companion sections only when the task crosses that boundary.

<!-- source-section: 5.10 -->
<a id="sec-5-10"></a>

### 5.10 One turn, rendered (abridged) `[A §5.10; C Appendix A]`

```text
[K]  I2 "thread ctx through handlers" · R2: … · accept: AC-4 run: pytest -k ctx · C1 do not change refund flow ·
     CON-007 handler signature contract (v3) · seed src/router.py:80-96 @a9f1 · PIT-003 "handlers built directly in tests" ·
     pre-existing: 2 failing (test_legacy_x, test_flaky_y) @s0
…[T]…
#41 edit → ok · views: src/handlers/user.py:40-46 @d1e7 · syntax ok · diffstat +2 −1
#42 run pytest -q -k ctx → exit 1 · 11 passed, 1 failed · FAILED test_cli_ctx — TypeError handle_cli() missing ctx (full: #42, 212 lines) · stamp s8
⟨ctx 38% · reserve ok · checks @d1e7: types ✓ · tests(k ctx) red · known 5/2.6K · STATE v15 · turn 14/40⟩
[A]  ── CONTRACT v3 (S2) ── "Add idempotency-key handling to POST /payments; public API unchanged." + "Also cover the retry path."
     R2 in_progress → AC-1 green @s41 STALE (closure moved) · AC-4 red #42 · AC-2 needs #id · exclusions: refund flow
     ── STATE v15 … 2. [>] pass ctx into handlers … Next: edit src/cli/main.py handle_cli signature
     ── Workset  KNOWN: router.py:80-96@a9f1 · handlers/user.py:30-60@d1e7 · … · NOT SEEN: everything else; src/cli/main.py never read
     ── Touched  M src/handlers/user.py (+2 −1) @c02e→d1e7 "accept ctx" #41
     ── Checks   types(touched): Δ +0 −1 · now 0 @d1e7 ✓ · tests(k ctx): 11 pass 1 fail #42 @s8 · full: stale (s3, closure moved)
     ── impact: `handle_user` signature changed; 3 references not inspected → look(refs) or scope the plan
     ── focus src/cli/  main.py "entry"·handle_cli@31  args.py …
     ⟨trip Q1 fired: edit under src/cli/ pending → check CLI path builds handlers⟩
```

---
<!-- end-source-section: 5.10 -->

<!-- source-section: 5.10-direct -->
<a id="sec-5-10-direct"></a>

### 5.10-D One direct turn, rendered (abridged) `[ASTROLABE 2.0 plan §4.3]`

> **Status: SPEC — implemented in P8.D.1–D.3** (the anchor is P8.D.2). No code renders this yet. *code:* cites the current code as `path:line` under `K = core/src/main/kotlin/io/astrolabe` at `main` `6daabfc`. The protocol itself is specified in [Appendix A-D](kernel-contract.md#sec-appendix-a-direct).

The same turn as §5.10, in a direct cell (S0). The harness journal replaces the STATE block; nothing else in the layout moves.

```text
[S]  astrolabe · role direct · kernel-direct/1 · roles/5 · error-policy/5 · eight kernel lines · duties · tools: look(tree, outline, read, find, def,
     refs, recall) · edit(anchored, create, delete, rename, revert) · run(run, wait, cancel) · verify(check, baseline) · state(note, blocked) ·
     task(ask, answer, finish) · evidence lines · error policy · data rule · execution mode
[K]  R2 "thread ctx through handlers" · accept: AC-4 run: pytest -q -k ctx · C1 do not change refund flow ·
     seed src/router.py:80-96 @a9f1 · pre-existing: 2 failing (test_legacy_x, test_flaky_y) @s0
…[T]…
#41 edit → ok · views: src/handlers/user.py:40-46 @d1e7 · syntax ok · diffstat +2 −1
#42 run pytest -q -k ctx → exit 1 · 11 passed, 1 failed · FAILED test_cli_ctx — TypeError handle_cli() missing ctx (full: #42, 212 lines) · stamp s8
state → STATE v3 · note o1 recorded · register 212/3000 tokens
⟨ctx 38% · reserve ok · checks types ✓ · tests ✗1 · known 5/2.6K · STATE v3 · turn 14/80⟩
[A]  ── CONTRACT v3 (S0) ── "Add idempotency-key handling to POST /payments; public API unchanged." + "Also cover the retry path."
     R2 in_progress → AC-4 red #42 · exclusions: refund flow
     ── Workset  KNOWN: router.py:80-96@a9f1 · handlers/user.py:30-60@d1e7 · … · NOT SEEN: everything else; src/cli/main.py never read
     ── Touched  M src/handlers/user.py (+2 −1) @c02e→d1e7 "accept ctx" #41
     ── Checks @d1e7 ── types: ✓ @d1e7 · tests: now 1 @d1e7 (#42)
     ── Runs     h2 uvicorn app.main:app → running · ready (port 8000)
                 #42 pytest -q -k ctx → red: 1 failed @d1e7
     ── Notes (STATE v3)
                 o1 the CLI path builds handlers without ctx
                 d1 pass ctx as a keyword argument; keep the positional order
                 h2 handle_cli is the only caller outside src/handlers
     enabled this turn: all role tools
     ⟨ctx 38% · reserve ok · checks types ✓ · tests ✗1 · known 5/2.6K · STATE v3 · turn 14/80⟩
     impact: `handle_user` (src/handlers/user.py) signature changed; 3 references not inspected → look(refs)
```

**The journal in `[A]`.** Blocks appear in this order; an empty block is omitted. The render is a pure function of its records — the register, the check registry, the run handles, the touched ledger, the Workset, the turn's mask and the gates — and reads no clock, so a line never shows an elapsed time.

| # | Block | Cap | Source | When over the target |
|---|---|---|---|---|
| 1 | contract digest | `effectiveDigestCapTokens(n)`: 150 tokens plus 8 per requirement, ceiling 2 000 (*code:* `K/Defaults.kt:41-44`, `K/cell/Cell.kt:731`); never line-capped (*code:* `K/cell/Anchor.kt:133`) | `ContractDigest` | never reduced |
| 2 | `── Workset` — KNOWN and NOT SEEN | 60 tokens (*code:* `Anchor.kt:211`) | Workset | never reduced |
| 3 | `── Touched` | the last 3 entries (the structured anchor shows 10 and falls to 3 under pressure: *code:* `K/Defaults.kt:57`, `Anchor.kt:148-152, 217`) | touched ledger | — |
| 4 | `── Checks` | 3 lines (*code:* `K/verify/ChecksRender.kt:57`) | check registry | never reduced |
| 5 | `── Runs` | `directRunsMaxLines` = 5 lines | run handles and check receipts | step 1: live handles and red receipts only |
| 6 | `── Notes (STATE v<N>)` | `directNotesMaxTokens` = 200 tokens, whole lines | register | step 2: 100 tokens |
| 7 | `enabled this turn: …` | 1 line (*code:* `K/cell/Layout.kt:204-216`) | the turn's mask | never reduced |
| 8 | gauge | 1 line, about 20 tokens (*code:* `K/tool/Envelope.kt:75-77`) | gauges | never reduced |
| 9 | nudges | 4 lines, hard refusals first (*code:* `Anchor.kt:214`, `K/cell/Cell.kt:673-678`) | gates, completion seam | never reduced |
| 10 | diagnosis lines of the repair helper | as produced | `ctx.diagnoses` | never reduced |

Size. The target is `directAnchorTargetTokens` = 800 tokens. A typical S0 turn: digest 160, Workset 60, Touched 90, Checks 60, Runs 125, Notes 200, enabled 8, gauge 25 — about 730 tokens — plus about 45 per nudge. When the composed text is over the target, step 1 and then step 2 apply, each named in `AnchorRender.reductions`; if it is still over, the anchor is sent as it is and the reductions say `over the 800-token target: <n> tokens`. The hard cap stays `anchorMaxTokens` = 5 000, and `overBudget` keeps its meaning (*code:* `K/cell/Anchor.kt:74-85, 158-159`). The compile's growth reserve keeps reserving `anchorMaxTokens` (D-384). The three new numbers are §17 defaults that D2 adds to [defaults](defaults.md#sec-17).

`── Runs`. The lines, in this order:

1. every live handle of the cell, in handle order: `<handle> <command> → running`, with ` · ready (<line or port>)` once a readiness condition was met;
2. for each registered check that has a command and a last receipt — a declared acceptance command recognised in `run`, and the model's own checks `CHK-model-*` (D-394; *code:* `K/verify/Check.kt:182-196, 383`) — that last receipt: `<alias> <command> → green | red: <n> failed | timeout | unavailable | inconclusive @<stamp4>`, with ` (stale)` when the receipt is not current for the tree. Red receipts come first, then the others; inside each group the higher receipt alias first.

The command is the canonical argv joined by spaces, cut at 60 characters with `…`. Lines beyond the cap collapse into a last line `+<n> more`. A command without a receipt is not listed: its last result is in `[T]`. A check listed here is still summarised in `── Checks`: Checks answers whether the candidate is green, Runs which command said so and what is still running. The runtime's record of a red non-mandatory check (P8.C.2: derived from the check's last receipt, never written into the register) is shown on that check's line as ` · known red, not required`.

`── Notes (STATE v<N>)`. One note per line, `<id> <text>`, with the ids of [A-D.4](kernel-contract.md#sec-appendix-a-direct-4). `N` is the register version, the same number the gauge shows. The register keeps one numbered list per kind and no global order, so the order is by kind and then newest first: open items that are not closed (`o<n>`), dead ends (`dead<n>`, with ` [#id]`), decisions (`d<n>`), pending amendments (`a<n> … (pending)`), stale verified notes (`v<n>(stale @<hash4>)`), hypotheses (`h<n>`), verified notes (`v<n> … [#id]`). Lines are added in that order while they fit the cap; the rest is one line `… +<n> notes not shown`. A note that is not shown is not lost: it stays in the register, and dead ends, open items, decisions and amendments are written verbatim into `[K]` at every rebuild and cell boundary ([§6.2](../context/continuity.md#sec-6-2)).

Absent in a direct anchor: the STATE block with its plan, `Focus` and `Next`; the focus zoom; focus notes; fired trips.

**What stays byte-stable.**

- `[S]` is a pure function of the role and the execution mode (*code:* `K/cell/Layout.kt:154-159, 172-196`): every direct cell of an attempt sends the same bytes, and the turn's mask is not an input.
- The tool schemas are the direct set of six, fixed for the line; the turn's narrower mask is named in `[A]` and enforced by the executor.
- `[R]` and `[K]` are unchanged in kind: `[K]` is stable within the cell between rebuilds.
- `[T]` is append-only between eviction batches. Every tool result carries its header and the gauge, so the step-by-step deltas — the result of a note, a check line, the gauge — stay in cached results, as they do today.
- `[A]` is volatile: rebuilt every turn, never cached, never stored in `[T]` (*code:* `K/cell/Anchor.kt:82-85`). It is the only place the journal lives.
- No timestamp and no counter enters a cached region; the protocol never changes inside a line.

If the benchmark (D5) shows that the uncached `[A]` costs noticeably, the alternative is to append the journal to the last tool result instead of sending it as a tail message. That branch is not specified in 2.0.
<!-- end-source-section: 5.10-direct -->

