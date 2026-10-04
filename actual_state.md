# ASTROLABE — implemented state

Snapshot: TODO owns task status; CONTINUE-TASK.md owns next work; audit/SESSION-HISTORY.md records history.

## Counts (2026-10-03, from `#### P… · STATUS` headings)
**P0–P6 185/185 DONE.** P8 (ASTROLABE 2.0, plan `../ASTROLABE-2-PLAN.md`): **27 DONE** — wave A 9/9, wave B 4/4,
wave C 14/14 (C1a, C1b, C2, C3, C4, Dp1, Dp2, C8–C14); **14 TODO** (D1–D5, E1–E4, F1–F2, R1, reserve G1–G2).
Recount: `rg -c '^#### P8\..*· DONE' TODO.md`. P7 out of scope except the AI Gate transport. **Live (2026-10-04):** the first live Studio run failed on basics; hotfix D-407–D-413 is in, verified offline only.

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
S2: CI 37078198525 green at `c592289` (gate P8.B). S3 (2026-10-03): local L2 by each line on main merged into its
branch; CI green on Ubuntu + Windows at `6daabfc` (ripgrep fix), `13cea02` (Dp1, Dp2, C1b), `611dbad` (C3), `878d5fe`
(C3r, C11); the final S3 push (C14, C10) — see the Gate P8.C line in `TODO.md`. Studio tests run only locally.

## History (details in `audit/SESSION-HISTORY.md`; decisions D-112… in TODO §3, owner D-175 deps)
Audit remediation (D-321–D-325); AI Gate transport (D-326–D-336, `liveTest` not run); phase 0 acceptance rule (D-337–D-355);
plan-handoff fix (D-357–D-362); efficiency fix (D-363–D-375).
## ASTROLABE 2.0 waves A and B (2026-10-02/03, D-376–D-396)
Wave A: `create` receipt, `run(op=wait)`, billed amount and timings in `ModelResponded`, stable `[S]` with the mask in
`[A]`, `eval-live` runner, shell-accurate `EffectPolicy`, wire/reserve/output apart, session key and price tiers (D-376–D-387).
S2: screening set of 8 tasks, live-stream redaction of `run` output, offline auditor `eval/audit`, kept return re-verified
on reopen, B4 (wave A not worse over 128 runs), declared acceptance recognised in `run`, provenance axis (D-388–D-396).
## ASTROLABE 2.0 S3 (2026-10-03, wave C closed, D-397–D-406)
Seeds behind `SeedSelector` (V1 default, V2 for direct) and a `v` fact with a dropped anchor kept as `h` (D-398); the
direct protocol specification `kernel-direct/1` in `docs/` (D-399, SPEC until P8.D); optional red checks recorded by the
runtime, sufficiency hint, `Verdict.reviewer` — a model's approval is never `independent` (D-397, D-400); hard per-task
limits (money, minutes, requests) with a one-price reserve, atomic admission, typed `BudgetStop`, raise-and-resume, and
balance profiles Economy/Balanced/Thorough (D-401, D-403); under `IntegrityApproval.Human` only a person clears a
test-integrity flag (D-404); contract tokens follow the policy upward on a reopen, `limitHold`, wire names,
`Astrolabe.resume` (D-405); strict regression gate for blast and types-touched with holds per test identity and a quiet
stop for background runs (D-406). Studio (root): per-run limits and approach, live meter, labels, human review card (D-402).
