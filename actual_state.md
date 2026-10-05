# ASTROLABE — implemented state

Snapshot: TODO owns task status; CONTINUE-TASK.md owns next work; audit/SESSION-HISTORY.md records history.

## Counts (2026-10-05, from `#### P… · STATUS` headings)
**P0–P6 185/185 DONE.** P8 (ASTROLABE 2.0, plan `../ASTROLABE-2-PLAN.md`): **38 DONE / 33 TODO** — waves A, B (with B5,
B7) and C (with C15, C16) done; wave D: Dp3, D1–D3 done, D7, D4, D5 open; wave W (workflow stabilization, sessions 4A/4B):
W0–W2 done (scenario guards `io.astrolabe.workflow`, WF suite 188 s), W3–W10 open; then H, E, F, release.
Recount: `rg -c '^#### P8\..*· DONE' TODO.md`. P7 out of scope except the AI Gate transport. **Live:** hotfix D-407–D-413
verified offline only; one live screening of `eval-live` (16 runs, deepseek flash) passed 8/8 in both arms.

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
Full `check` on CI (Ubuntu + Windows, JDK 26): green at `b3eae38` (gate P8.C, tag `v2-wave-C`). Since owner №28 a push
to `main` runs only the fast job (compile + ABI): green at `b0901dc`. Session 4: targeted tests per line and L2
(`assemble testClasses checkKotlinAbi`) on every merged state; no full suite. Studio tests run only locally.

## History (details in `audit/SESSION-HISTORY.md`; decisions in TODO §3)
Audit remediation (D-321–D-325); AI Gate transport (D-326–D-336); phase 0 acceptance rule (D-337–D-355); plan-handoff
fix (D-357–D-362); efficiency fix (D-363–D-375).
## ASTROLABE 2.0 waves A–C (2026-10-02/03, D-376–D-406; details in TODO §3 and the session history)
Wave A: `create` receipt, `run(op=wait)`, billed amount and timings in `ModelResponded`, stable `[S]` with the mask in
`[A]`, `eval-live` runner, shell-accurate `EffectPolicy`, session key and price tiers. Wave B: screening set of 8 tasks,
offline auditor `eval/audit`, B4 (wave A not worse over 128 runs). Wave C: declared acceptance recognised in `run`,
provenance axis, seeds behind `SeedSelector`, hard per-task limits with `BudgetStop` and raise-and-resume, balance
profiles, human-only clearing of test-integrity flags, strict regression gate per test identity, Studio limits and labels.
## ASTROLABE 2.0 session 4 (2026-10-04, D-414–D-425)
The direct protocol in the core, off by default (`Config.protocol`): specification for S0–S3, role `direct` with
`kernel-direct/1` and 23 operations, `Roles.mainLine(protocol, shape)`, the direct `[A]` journal (Runs, Notes),
`state(note)`, `task(finish)` with the conditional claim, epoch handoff under its own grant (`campaign/Handoffs.kt`).
One `RunSpec` for Studio and `eval-live`; `eval-live` arms and the reference arm `loop`; nominal price of subscription
models (paid and nominal spend apart, the money limit on their sum); per-test results of `node --test`; a per-turn
output budget for `run` and `verify`; Studio `auto` mode asks about D-class effects. Money is judged over price profiles (D-421).
