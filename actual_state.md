# ASTROLABE — implemented state

Snapshot: TODO owns task status; CONTINUE-TASK.md owns next work; audit/SESSION-HISTORY.md records history.

## Counts (2026-10-03, from `#### P… · STATUS` headings)
**P0–P6 185/185 DONE.** P8 (ASTROLABE 2.0, plan `../ASTROLABE-2-PLAN.md`): **17 DONE** — wave A 9/9, wave B 4/4 (B1,
B2, B4, B4 follow-up A3 ablation), wave C 4/9 (C1a, C2, C8, C9); **19 TODO** (C1b, C3, C4, Dp1, Dp2, D1–D5, E1–E4, F1–F2,
R1, reserve G1–G2). Recount: `rg -c '^#### P8\..*· DONE' TODO.md`. P7 out of scope except the AI Gate transport.

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
Schema **v5** (phase 0: `pending_completions`, `acceptance_decisions`); skills/behaviour maps are `BlobKind.MODULE` blobs.

## Last verification
CI (Ubuntu + Windows, JDK 26, full `check`): run 37049344584 green at `4bb181b` (wave A + review fixes, 2026-10-02).
S2 (2026-10-03): local L2 at every merge (packages per card; the C9 merge broke `cell.ResultRecallTest`, fixed in
`c61490a`); CI 37078198525 green at `c592289` (Ubuntu + Windows; gate P8.B); S2 push with C2: CI 37081214729. `provider-ai-gate`, SDK and eval-live run only locally.

## History (details in `audit/SESSION-HISTORY.md`; decisions D-112… in TODO §3, owner D-175 deps)
Audit remediation 2026-09-28 (D-321–D-325); AI Gate transport (D-326–D-336, `liveTest` not run); phase 0 acceptance rule
(D-337–D-355); plan-handoff fix (D-357–D-362); efficiency fix (D-363–D-375): Windows program resolution, `ContentCache`
with fresh stamps at every acceptance boundary, input tolerance + `JsonRepair`, a refused call refuses only itself.
## ASTROLABE 2.0 wave A (2026-10-02, P8, D-376–D-387)
`create` answers with a receipt (D-376); `run(op=wait)` until exit/line/port (D-377, D-381); `ModelResponded` carries the
billed amount, reasoning tokens, timings, upstream, price tier (D-378); stable `[S]`, mask in `[A]`, schema set by role,
fingerprints cover `[S]`/schemas (D-379, D-382); `eval-live` headless runner + 3 v0 tasks (D-380); shell-accurate
`EffectPolicy`, proven deletes/redirects, toolchain collapse in the atlas (D-383); wire/reserve/output apart, window-scaled
growth reserve, capacity fallback (D-384); session key, price tiers, conservative tiered reservation (D-385).
BL baseline 12/12 accepted, $0.106; reviews Fable (A3, A4+A5, A6) and Codex (A1, A2b, wave: 2 P1 + 4 P2) — fixed (D-387).
## ASTROLABE 2.0 S2 (2026-10-03, P8.B, P8.C.1/3/8/9, D-388–D-396)
Screening set of 8 tasks with known-wrong patches, hidden parts packed out of the distribution, `interrupt` scenario
(D-388); `run` output redacted as a live stream, whole-line polls (D-390); offline auditor `eval/audit` (D-391); kept
return re-verified on reopen without a model call (D-392); B4: wave A not worse on any metric over 128 runs (D-393);
method: provider-independent metrics first (D-395); declared acceptance recognised in `run`, `CHK-model-*` checks
(D-394); provenance axis `independent`/`agent_test`/`unverified` with a final-tree test-integrity taint (D-396).
