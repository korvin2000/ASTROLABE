# Implementation stages and estimates

**ASTROLABE 1.0.1 · planning** · Owner: Implementation planning.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §18, §18.1, §18.2. **Read with:** [lifecycle](../architecture/lifecycle.md) · [fixtures](../evaluation/fixtures.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F10](../../REVIEW.md#f10).

> The original roadmap and engineering estimates are preserved. This is architecture input to a later implementation plan, not an implementation claim.

<!-- source-section: 18 -->
<a id="sec-18"></a>

## 18. Implementation plan
<!-- end-source-section: 18 -->

<!-- source-section: 18.1 -->
<a id="sec-18-1"></a>

### 18.1 Modules and honest size `[A §18.1; C §15.1; J1 §7.6 and QA §4.5 warn against "weekend-scale" claims — ESTIMATE]`

| Plane | Module | Contents | ~LOC |
|---|---|---|---|
| Execution | cell runtime | loop, layout, anchor render, gauge, gates, eviction, rebuild (five uses), dispatch, turn partition | 950 |
| Execution | registers | contract digest, STATE parser, typed ops, validator, conditional ops, amendments, coherence marks | 500 |
| Execution | workset + version registry | range registry, KNOWN/NOT SEEN, mark-then-stub, export/seed, `version()`, `displayed()`, stamps | 400 |
| Platform | evidence store | journal, blobs, receipts, closures, intents, observations, claims, search, four identities | 550 |
| Execution | look | tree, outline, read, find (4 sources), def/refs/importers/impact, recall (+since), bmap, catalog, dedup | 750 |
| Execution | edit | CAS, region check, anchors + candidates, preflight/apply, inline syntax, views, revert, transform receipt, scope/test-integrity classifiers | 900 |
| Execution | run + runner | trusted-local and confined adapters, effect classes, shaping parsers, timeouts, bg handles, stamps, reconciliation, intent journal | 750 |
| Execution | shadow git + dirty state | snapshots, guarded revert, preimages, dirty-state record | 200 |
| Execution | orientation | prime, atlas, tree-sitter tier, import graph, focus zoom | 500 |
| Verification | scheduler | registry, triggers, closures, applicability and reuse proofs, baseline ledger, end-of-turn checker, impact engine, Δ+absolute render, exit gate, reserve, flaky policy | 750 |
| Control | campaign controller | contract, amendments, requirement graph, increments, ledger, cell lifecycle, shape and profile selection, leases, cancellation, resume/reconcile, fingerprints, cache-aware scheduling | 900 |
| Context | context compiler | selection, greedy cover, seeds, KB ranking, skills modules, coverage, manifest, budget, pre-compilation, calibration prior | 600 |
| Knowledge | knowledge base | notes, index generation, queue, curator lint, invalidation, promotion hooks, extraction prompts, skills manifests, BMAP, CAL | 950 |
| Execution | delegation | probe/review/QA/writer cells, packets, judge protocol (two scopes), integrator, merge queue (S3) | 700 |
| Recovery/Routing | recovery + routing | classification, fingerprints, capsule + repair helper, alternative attempt, tier table, select_profile, escalation, calibration logs | 600 |
| Platform | provider adapters | item model; Responses, Messages, compat; capability probes; continuation; usage by cache class | 900 |
| Platform | MCP mounts + generated tools | catalog, mount invocation, tool lifecycle | 300 |
| Platform | telemetry | phase tags, spans, four quantities, cost accounting, exports | 400 |
| Platform | evaluation runner | fixtures, ablation runner, scorecard, promotion/rollback, improvement runner skeleton | 700 |
| **Total** | | **kernel + S0 ≈ 5.5K · full system through S3 ≈ 13–15K** (Python or TypeScript; SQLite + files; no server; no second model inside a worker loop) | |

Realistic effort: Stages A–C are two to three engineer-months; the full system through Stage E is a small team for two to three quarters `[C §15.2]`. "Weekend-plus" applies to none of it; a short central loop does not measure total system complexity `[B §14.2]`. Substrate: Python (`asyncio`, `sqlite3`, official provider SDKs, thin wrappers around Git, ripgrep, test runners, tree-sitter, selected language services) or TypeScript — one core language, not two runtimes; execution behind an OS adapter validated on one platform first, then Windows and others explicitly for paths, encoding, subprocess trees, timeouts and file replacement `[B §4.1]`.
<!-- end-source-section: 18.1 -->

<!-- source-section: 18.2 -->
<a id="sec-18-2"></a>

### 18.2 Stages and exit gates `[A §18.2; C §15.2; B §16; IM §14]`

| Stage | Build | Gate before expanding |
|---|---|---|
| **A — Dependable cell (S0)** | contract (auto-derived acceptance), evidence store with four identities, version registry, shadow ref + dirty state, `look/edit/run/verify/state`, CAS + region-seen, inline syntax, **synchronous** end-of-turn checker, register + gates, gauge, execution-mode label, baseline receipt, exit gate, minimal cancellation/lease checks, atomic budget reservations, action reconciliation, task.ask and adapters with usage by cache class, telemetry | J1 [§8.2](../verification/scheduler.md#sec-8-2) cases pass as harness tests; stale/ambiguous edits fail safely; user dirty changes survive; a failed command cannot become a green receipt; wrapper exit 0 is not green; partial batches reported truthfully; billed usage visible per call; B0 and B-HELM numbers recorded |
| **B — Continuity (S1)** | campaign controller, requirement graph, plan cell, increments, compiler with seeds, coverage and manifest, carry-forward, cross-cell coherence, stubs/recall, mark-then-stub, `R_max`, pressure rebuild as one of five rebuild uses, resume/reconcile, calibration prior, `task.ask` | a forced boundary and a crash both resume with exact constraints, open failures and recoverable evidence; continuation cells do not redo verified increments; B2 (S1) ≥ B1 (S0) quality at lower billed cost on medium tasks |
| **C — Verification depth & refactor mode** | scheduler with closures and reuse proofs, reserve, impact engine and nudge, blast radius, transform path with inventory, test-integrity and scope guards, refactor mode, flaky policy, boundary pre-compilation; async checkers only with a tier-2 adapter and an ablation | cross-file migrations complete with fewer redundant checks and no lost requirements; a 40-file rename is transformed, reconciled, reviewed and reversible; no false green in fixtures |
| **D — Knowledge & delegation (S2)** | KB with queue/curator/invalidation/injection, extraction, BMAP, skills, probe cells, review cells at both scopes with judge protocol and bias controls, function routing with refusal, escalation ladder, capsule repair, alternative attempts, MCP mounts | warm-memory runs beat cold on held-out tasks without stale-advice regressions; review catches injected defects on fixtures; routing saves cost with no complex-stratum loss (pre-set gate); recovery ladder passes its fixtures |
| **E — Measured adapters & scale (S3)** | language-service adapter, dense retrieval if lexical misses persist, writer cells + integrator + merge queue, L3 QA cells, L4 eval gates, permission ladder + commit policy, skills promotion, learned routing corrector | each feature passes its ablation ([§19.5](../evaluation/method.md#sec-19-5)) or ships off; B4 beats sequential under equal budgets on decomposable tasks; zero unauthorized-stage publications |
| **F — Offline improvement loop** | trace mining, versioned harness changes, matched-budget experiments, promotion/rollback | generalization on held-out repositories beats simple baselines under matched budget `[RN R10]` |

**First vertical slice** `[B §16.1]`: one realistic cross-file defect end to end — contract → locate and read → guarded patch → run/poll a test → capture evidence → force interruption → resume → verify candidate → emit receipt — including an initially dirty file and one deliberately stale patch attempt. Then a long refactor with forced context pressure to exercise the increment boundary, seeds and closures without dropped requirements. No stage starts before the previous gate is measured; features that fail their gate remain behind a flag with their ablation data attached. Stage C’s mandatory refactor review uses the already allowed human-review path until Stage D’s automatic review cell is ready; without that review, the refactor cannot be accepted. Earlier stages may collect CAL statistics deterministically; knowledge admission/automatic injection waits for its implemented Stage D path. Required P0 correctness controls are never treated as optional ablations in live work.

---
<!-- end-source-section: 18.2 -->

