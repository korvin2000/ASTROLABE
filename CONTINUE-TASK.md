# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
**Active program: ASTROLABE 2.0** — plan `../ASTROLABE-2-PLAN.md` (changes only via its §17), status = phase P8 in
`TODO.md` (`rg -n '^#### P8' TODO.md`), cards/reports `../plan2/`. Next session: prompt A.2 of the plan with S2.

**Checkpoint (2026-10-02, S1 done):** wave A **9/9 DONE** (P8.A.1–A.9), decisions **D-376–D-386**. Rollback tag
`v1.0.1-final` in all three repos. Merged `--no-ff` into `main`: A4, A5 (+A5r), A2a (SDK `dba7ab7` + core), A3 (+A3r),
A0, A6, A1 (+A1r), A2b. Baseline BL (`../plan2/reports/WP-BL.md`): 12/12 accepted, billed $0.106 vs estimate $0.207,
N 150, built from branch `v2/BL` (= `a245ac7` + A2a's own commits + A0) — the reference for B4.

## Reviews this session (owner rule: Codex for math, Fable for architecture/logic — plan §17)
Fable: A4+A5 (1 bug + 3 risks → A3 masks, A5r), A3 (3 risks → A3r), A6 security (6 bugs → fixed on v2/A6). Codex: A1 (in
line), A2b (2 × P2 → fixed; router follow-up A1r). Wave adversarial review (Codex): see "Last gate".

## Next (S2 per plan §7)
1. B2 (screening set ≤ 8 tasks; move hidden acceptance out of the distribution; add a known-wrong patch per task) →
   B1 (offline auditor) → B4 (baseline vs wave A on the screening set, billed money, pinned upstream).
2. Start C1a, C2 (tasks). P8.C.8 (pending-save idempotency) needs `campaign/Controller.kt`.
3. Owner decisions due before S3: plan §11 №2–4, 6, 7; before S2: №9a (non-inferiority threshold).

## Risks to watch in B4
Stricter `EffectPolicy` (D-383: delete/move/redirect after a non-link-safe segment is D, e.g. `build && test > log`;
`> $null` in `cmd`); `create` receipt may cause compensating reads (D-376); accept-unverified share (6/12 in BL, no
`ReviewPass` in the runner); router money estimates now dearest-rate (D-386).

## Carried-forward debts (frozen in 2.0, plan §12)
D-254, D-70/D-71/D-241, D-252, D-113/D-120, D-124/D-244, D-200; D-66, D-28, D-92 (D-220/D-221 partly paid by eval-live).
Transient (A6 hardened the probes, keep watching): `StamperTest` `git exited -1`; Windows `ProcOwnershipTest`; FX-22.

## Branches
All wave-A branches merged; pushed `v2/*` backups remain on origin until deleted (`v2/BL` kept as the baseline source).

## Last gate
L2 at every merge (green, counts in each task's `Log:`). CI on push of `main`: PENDING — fill in run id and result.

## Blockers
None. `gh` not installed; CI status via the public API (`curl -s https://api.github.com/repos/korvin2000/ASTROLABE/actions/runs?branch=main`).
