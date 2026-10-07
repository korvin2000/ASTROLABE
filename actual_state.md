# ASTROLABE — implemented state

Snapshot: TODO owns task status; CONTINUE-TASK.md owns next work; audit/SESSION-HISTORY.md records history.

## Counts (2026-10-07, from `#### P… · STATUS` headings)
**P0–P6 185/185 DONE.** P8 (ASTROLABE 2.0, plan `../ASTROLABE-2-PLAN.md`): **46 DONE / 26 TODO** — waves A, B (with B5,
B7) and C (with C15, C16) done; wave D: Dp3, D1–D3 done, D7, D4, D5 open; wave W done (W0–W10, gates P8.W-A and P8.W,
tag `v2-wave-W`; WF suite `io.astrolabe.workflow` 43 tests, 179.5 s of 180; Studio `*WorkflowScenario*` 25); W11 open; then H, E, F, release.
Recount: `rg -c '^#### P8\..*· DONE' TODO.md`. P7 out of scope except the AI Gate transport. **Live:** `real-dirty-repo`
auto and ask and `real-dirty-reopen` complete on deepseek flash (gate P8.W, `../plan2/reports/SESSION-4B.md`).

## Completion levels
- **P0–P6:** `FIXTURE_VALIDATED` on Windows + Linux CI (JDK 26). Every live gate is `UNMEASURED` (P7).
- **P4 Stage D:** KB (queue, curator, lint, invalidation, injection, extractor, typed `NEG`), skills and behaviour maps,
  delegation (probe, review cell + judge, QA contract, worth estimate), routing (tiers, escalation, cache-aware order,
  shadow seam), recovery (failure classes, `Ladder`, guards, capsule repair), MCP `Mount` + frozen catalog. S2 runs.
- **P5 Stage E:** worktrees, `Writer`, `Integrator`/`MergeQueue`, S3 shape + run loop (flag, off), publication beyond
  `patch` (harness branch, non-force pushes), QA driver, L4 measurement, tier-1 `index-treesitter` (optional), tier-2
  `LanguageService`, `Retriever` + dense seam, generated tools, promotion proposals, `Watcher`.
- **P6 Stage F (`eval`):** fixture runner (`:eval:fixtures`), frozen campaigns, comparators, arms, promotion policy,
  trace mining; since 2.0 the offline auditor `eval/audit` (`:eval:audit`).
- Optional layers off by default (`precompile`, `calibrationPrior`, `otelExport`, `kbInjection = Off`, `qaCell`, S3,
  tier-1 index, dense retrieval).

## Key types by package (entry points only)
- **root:** `Astrolabe`, `Config`, `Flags`, `OptionalLayers`. **`java`:** `AstrolabeJava`, `Java*` SPI forms.
- **`campaign`:** `Controller` (`open`, `run`, `publish`), `ShapeSelector` (S0–S3), `S3Run`, `Recoveries`,
  `Escalations`, `CellOrder`, `Publications`/`Publisher`, `CampaignFinish`, `FinishReceipt`.
- **`delegate`:** `Delegator`, `Probe`, `ReviewCell`/`Judge`/`EvidencePacket`, `QaCell`/`QaDriver`, `Writer`,
  `Integrator`, `WorthTest`. **`recover`:** `FailureClass`, `Ladder`, `Fence`, `Guards`, `Capsule`, `Repair`, `Alternative`.
- **`route`:** `Router`, `TierTable`, `FunctionTable`, `Escalation`, `CacheSchedule`, `ShadowRouting`.
- **`kb`:** `StoreKb`, `Queue`, `Curator`, `Extractor`, `Skill`/`SkillViews`, `BehaviourMaps`, `Retriever`,
  `PromotionProposals`. **`tool`:** `Look`, `Edit`, `Run`, `Verify`, `TaskTool`, `KbTool`, `Mount`/`Catalog`, `GeneratedTools`.
- **`workspace`:** `Worktrees`, `Ownership`, `ScopeAlgebra`. **`atlas`:** `ImportGraph`, `OutlineIndex`, `LanguageService`; **`verify`:** `Scheduler`, `Watcher`.
- **Modules:** `provider-api`, `core`, `eval`, `index-treesitter` (D-175/D-210); with the SDK checkout only: `provider-ai-gate` (D-332), `eval-live` (D-380).

## Store
Schema **v6** (phase 0: `pending_completions`, `acceptance_decisions`; W9: `packets`, the cell packet in its settle transaction); skills/behaviour maps are `BlobKind.MODULE` blobs. E1's binding table takes v7.

## Last verification
Full `check` on CI (Ubuntu + Windows, JDK 26): green at `b3eae38` (gate P8.C, tag `v2-wave-C`). Since owner №28 a push
to `main` runs only the fast job (compile + ABI): green at `b0901dc`. Sessions 4–4B: targeted tests per line and L2
(`assemble testClasses checkKotlinAbi`) on every merged state; no local full suite. Tag `v2-wave-WA` was red (4 defects, fixed by WAF in 4B); tag `v2-wave-W` result not yet read. Studio tests run only locally.

## History (details in `audit/SESSION-HISTORY.md`; decisions in TODO §3)
Audit remediation, AI Gate transport, phase 0 acceptance, plan-handoff and efficiency fixes (D-321–D-375).
## ASTROLABE 2.0 waves A–C and session 4 (2026-10-02/04, D-376–D-425; details in TODO §3 and the session history)
Waves A–C: `create` receipt, `run(op=wait)`, billing and timings, `eval-live`, `EffectPolicy`, screening set, offline
auditor, declared acceptance, provenance, hard per-task limits, strict regression gate, Studio limits. Session 4: the
direct protocol (off by default), one `RunSpec` for Studio and `eval-live`, arm `loop`, nominal subscription prices (D-421).
## ASTROLABE 2.0 session 4A (2026-10-05/06, D-426–D-431)
WF-1…WF-11 scenario guards and `phase.counted`; one git process per snapshot, one read per file per capture; end checks
before the pin, `DecisionKey` v3; `ScratchPolicy` frozen per attempt; review cells on their own budget; Studio one open
per action, card text attached, continue in place; `eval-live --mode auto|ask`.
## ASTROLABE 2.0 session 4B (2026-10-07, D-432–D-438)
Task workflow spec `docs/runtime/task-workflow.md`: history with message kinds (untyped text = steering, amendment only
explicit, card note + Send to agent), objective = request + amendments, response increments, `parentWork`; goal evidence
by `purpose` (regression-only → `unverified`), model goal criteria evaluated by their run, `answered`; `ScratchPolicy` v3
markers and declared outputs; carry from the store (`settle`, packets), seed fallback, increment and parent carry,
append-only projection (WF-15). WF-12…WF-15 guarded; Codex integration review: 11 P1 fixed (WR2, WR2s).
