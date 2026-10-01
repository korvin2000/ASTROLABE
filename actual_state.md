# ASTROLABE — implemented state

Snapshot: TODO owns task status; CONTINUE-TASK.md owns next work; audit/SESSION-HISTORY.md records history.

## Counts (2026-09-26, from `#### P… · STATUS` headings)
**185/185 DONE, 0 IN_PROGRESS, 0 TODO.** P0 19/19 · P1 64/64 · P2 30/30 · P3 25/25 · P4 25/25 · P5 15/15 · P6 7/7.
Recount: `rg -c '^#### P\d+\.\d+\.\d+ .*· DONE' TODO.md`. P7 is out of scope except the owner-requested AI Gate
transport (2026-09-28, merged into local `main`, D-326–D-336).

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
- **`workspace`:** `Worktrees`, `Ownership`, `ScopeAlgebra`. **`atlas`:** `ImportGraph`, `OutlineSource`/`OutlineIndex`,
  `LanguageService`. **`verify`:** `Scheduler`, `Measurement`, `Watcher`, `CampaignReview`.
- **Modules:** `provider-api`, `core`, `eval`, `index-treesitter` (tree-sitter-ng 0.26.6 + grammar jars, D-175/D-210),
  `provider-ai-gate` (`AiGateAdapter` over `net.ai.gate:ai-gate`; present only with the SDK checkout, D-332).

## Store
Schema **v5** (phase 0: `pending_completions`, `acceptance_decisions`); `packets` holds behaviour-snapshot, campaign-review, increment-review and
`integration` rows; skills and behaviour maps are `BlobKind.MODULE` blobs linked as a note's `procedure` module.

## Last verification
Ubuntu + Windows CI green: P4–P6 gates 36159227747, 36162949349, 36167689819, 36172349269; fine-tune + CI repair
(`c407c24`) 36468171641. The AI Gate branch is verified locally only (Windows, JDK 26; journal).

## Recorded deviations
Local choices D-112–D-113, D-120–D-126, D-135–D-137, D-145–D-155, D-160–D-165, D-170–D-174, D-180–D-183, D-190–D-195,
D-200–D-202, D-210–D-213, D-220–D-223, D-230–D-233, D-240–D-244, D-250–D-254, D-260 (TODO §3); owner D-175 (deps).
## Audit remediation (2026-09-28): 142 findings fixed (`audit/BUGFIX-REVIEW.md`); follow-ups D-321–D-325; CI green 36468171641.
## Plan-handoff fix (2026-10-01, owner request, D-357–D-362, branch `fix/plan-handoff`, not merged)
Masked-op refusals explain themselves; refusal loop ends the cell blocked; plan form visible/lenient (`PLAN_FORM`,
`roles/4`); `ShapePolicy.planCell = WhenNeeded` skips the plan cell for an eligible S1 contract; plan/probe run R-class only
(enforced); GLM input tolerance; `gate.body` pass-through. Tests: core cell/tool/campaign/graph/delegate/verify/context.
## AI Gate transport (2026-09-28, merged into local `main`)
`AiGateAdapter(llm, profiles)` + `Astrolabe(…, estimators = adapter.estimators(HeuristicEstimator()))`; profiles bind via
`Profile.config.gate`, drafts/probes via `AiGateProfiles` (D-326–D-336); AX-01..10 pass offline; `liveTest` not yet run.
## Phase 0 — acceptance rule (2026-09-30, owner request, D-337–D-355)
`verify/Resolution.kt` resolves every obligation to passed/failed/unverified; unverified waits for `Authority.decide`
(stop code `acceptance_decision` / `review_rejected`), resumes without a cell, records per-item provenance; `answered`
outcome, host notes, tool ergonomics (E1–E7). Targeted tests only (journal); full build pending (CONTINUE-TASK).
