# ASTROLABE — implemented state

Snapshot, rewritten each session (≤60 lines). Progress truth is the TODO task statuses and `Log:` lines;
next work is `CONTINUE-TASK.md`; history is `audit/SESSION-HISTORY.md`.

## Counts (2026-09-25, from `#### P… · STATUS` headings)
**185/185 DONE, 0 IN_PROGRESS, 0 TODO.** P0 19/19 · P1 64/64 · P2 30/30 · P3 25/25 · P4 25/25 · P5 15/15 · P6 7/7.
Recount: `rg -c '^#### P\d+\.\d+\.\d+ .*· DONE' TODO.md`. P7 (live transports, hosts, live evaluation) is out of scope.

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
- **Modules:** `provider-api`, `core`, `eval`, `index-treesitter` (tree-sitter-ng 0.26.6 + grammar jars, D-175/D-210).

## Store
Schema **v4** (no bump this session). `packets` also holds behaviour-snapshot, campaign-review, increment-review and
`integration` rows; skills and behaviour maps are `BlobKind.MODULE` blobs linked as a note's `procedure` module.

## Last verification
| Gate (Ubuntu + Windows green) | CI run |
|---|---|
| P4.3, P4.5, P4.6, P4.7 | 36159227747 |
| P4.4, P4.8 (P4 phase) | 36162949349 |
| P5.1–P5.7, P6.2 | 36167689819 |
| P5.8, P6.1, P6.3 (final) | 36172349269 |

## Recorded deviations
Local choices D-112–D-113, D-120–D-126, D-135–D-137, D-145–D-155, D-160–D-165, D-170–D-174, D-180–D-183, D-190–D-195,
D-200–D-202, D-210–D-213, D-220–D-223, D-230–D-233, D-240–D-244, D-250–D-254, D-260 (TODO §3). Owner decision D-175
(new dependencies allowed under pinned-version rules).
