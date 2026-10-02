# ASTROLABE — implemented state

Snapshot: TODO owns task status; CONTINUE-TASK.md owns next work; audit/SESSION-HISTORY.md records history.

## Counts (2026-10-02, from `#### P… · STATUS` headings)
**P0–P6 185/185 DONE.** P8 (ASTROLABE 2.0, plan `../ASTROLABE-2-PLAN.md`): wave A **9/9 DONE** (A0, A2a, BL, A1, A2b,
A3, A4, A5, A6), 24 TODO in waves B–F + release (incl. P8.C.8, P8.C.9). Recount: `rg -c '^#### P\d+\.\d+\.\d+ .*· DONE' TODO.md`
and `rg -c '^#### P8\..*· DONE' TODO.md`. P7 out of scope except the AI Gate transport (D-326–D-336).

## Completion levels
- **P0–P6:** `FIXTURE_VALIDATED` on Windows + Linux CI (JDK 26). Every live gate is `UNMEASURED` (P7).
- **P4 Stage D:** KB (queue, curator, lint, invalidation, injection), extractor + typed `NEG`, skills (module filtering,
  per-role views, triggers at state changes) and behaviour maps (`look(bmap)`), delegation (probe, review cell + judge
  protocol at two scopes, QA contract, worth estimate, role texts + packet validators), routing (tier/function tables,
  escalation ladder + attempt allowance, cache-aware ordering, shadow seam), recovery (failure classes + `Ladder`,
  fingerprints + guards, capsule repair, alternatives), MCP `Mount` contract + frozen catalog. **S2 campaigns run.**
- **P5 Stage E:** worktrees + workspace-qualified identities, `Writer` role, `Integrator`/`MergeQueue`, S3 shape branch
  and S3 run loop (behind the S3 flag, default off), publication stages beyond `patch` (harness branch
  `refs/heads/astrolabe/<work>/<attempt>`, non-force pushes, host-driven), QA driver (loopback, isolated candidate),
  L4 measurement contract, tier-1 `index-treesitter` module (optional; host plugs it in), tier-2 `LanguageService`,
  `Retriever` + dense seam, generated tools, promotion proposals, `Watcher` seam.
- **P6 Stage F (`eval`):** fixture runner (`./gradlew :eval:fixtures`, reproduces core's JUnit results, report schema in
  `eval/README.md`), frozen campaigns + comparators + arms table, promotion policy, trace mining + experiment bookkeeping.
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
CI (Ubuntu + Windows, JDK 26, full `check`): run 37049344584 green at `4bb181b` (ASTROLABE 2.0 wave A + review fixes,
2026-10-02); run 37044497216 green at `488db92`. Local L2 at every wave-A merge. `provider-ai-gate`, SDK and eval-live
run only locally (CI has no SDK checkout). Earlier CI: P4–P6 gates; `f68032f` 36905875813.

## Recorded deviations
Local choices D-112–D-260 and later rows in TODO §3; owner D-175 (deps).
## History (details in `audit/SESSION-HISTORY.md`)
Audit remediation 2026-09-28 (D-321–D-325); AI Gate transport (D-326–D-336, `liveTest` not run); phase 0 acceptance rule
(D-337–D-355); plan-handoff fix (D-357–D-362); efficiency fix (D-363–D-375): Windows program resolution, `ContentCache`
with fresh stamps at every acceptance boundary, input tolerance + `JsonRepair`, a refused call refuses only itself.
## ASTROLABE 2.0 wave A (2026-10-02, P8, D-376–D-387)
`create` answers with a receipt (D-376); `run(op=wait)` until exit/line/port (D-377, D-381); `ModelResponded` carries the
billed amount, reasoning tokens, timings, upstream, price tier (D-378); stable `[S]`, mask in `[A]`, schema set by role,
fingerprints cover `[S]`/schemas (D-379, D-382); `eval-live` headless runner + 3 v0 tasks (D-380); shell-accurate
`EffectPolicy`, proven deletes/redirects, toolchain collapse in the atlas (D-383); wire/reserve/output apart, window-scaled
growth reserve, capacity fallback (D-384); session key, price tiers, conservative tiered reservation (D-385).
Baseline BL (before A1/A3–A6/A2b): 12/12 accepted, billed $0.106, N 150 (`../plan2/reports/WP-BL.md`).
Reviews: Fable (A4+A5, A3, A6 security), Codex (A1 in line, A2b, wave adversarial: 2 P1 + 4 P2) — all fixed or recorded (D-387).
