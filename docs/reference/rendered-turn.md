# Rendered turn example

**ASTROLABE 1.0.1 · example** · Owner: Illustrative runtime rendering.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §5.10. **Read with:** [context-layout](../runtime/context-layout.md). Load companion sections only when the task crosses that boundary.

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

