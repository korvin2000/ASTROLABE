# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
**Active program: ASTROLABE 2.0** — plan `../ASTROLABE-2-PLAN.md` (changes only via its §17), status = phase P8 in
`TODO.md` (`rg -n '^#### P8' TODO.md`), cards/reports `../plan2/`. Next session: prompt A.2 of the plan with S2.

**Checkpoint (2026-10-02, S1 done):** wave A **9/9 DONE** (P8.A.1–A.9), decisions **D-376–D-387**. Rollback tag
`v1.0.1-final` in all three repos. Merged `--no-ff` into `main`: A4, A5 (+A5r), A2a (SDK `dba7ab7` + core), A3 (+A3r),
A0, A6, A1 (+A1r), A2b, then the wave review fixes AR (core) and SDK `fdbd733`. Baseline BL (`../plan2/reports/WP-BL.md`): 12/12 accepted, billed $0.106 vs estimate $0.207,
N 150, built from branch `v2/BL` (= `a245ac7` + A2a's own commits + A0) — the reference for B4.

## Reviews this session (owner rule: Codex for math, Fable for architecture/logic — plan §17)
Fable: A4+A5 (1 bug + 3 risks → A3 masks, A5r), A3 (3 risks → A3r), A6 security (6 bugs → fixed on v2/A6). Codex: A1 (in
line), A2b (2 × P2 → fixed; router follow-up A1r). Wave adversarial review (Codex, §A.7): 2 × P1 (secrets via `until_line`; failed/cancelled calls missing from bench totals) + 4 × P2 (Java constructors, eval-live sums, SDK late route, poll-only roles) — all fixed (D-387).

## Next (S2 per plan §7)
1. B2 (screening set ≤ 8 tasks; move hidden acceptance out of the distribution; add a known-wrong patch per task) →
   B1 (offline auditor) → B4 (baseline vs wave A on the screening set, billed money, pinned upstream).
2. Start C1a, C2 (tasks). Tails: P8.C.8 (pending-save idempotency, `campaign/Controller.kt`), P8.C.9 (`run(op=poll)` slices redacted across polls).
3. Owner decisions due before S3: plan §11 №2–4, 6, 7; before S2: №9a (non-inferiority threshold).

## Risks to watch in B4
Stricter `EffectPolicy` (D-383: delete/move/redirect after a non-link-safe segment is D, e.g. `build && test > log`;
`> $null` in `cmd`); `create` receipt may cause compensating reads (D-376); accept-unverified share (6/12 in BL, no
`ReviewPass` in the runner); router money estimates now dearest-rate (D-386); every dispatched call emits `ModelResponded`, failed/cancelled too (D-387); `Precompile.Fingerprint` constructor is a Java break.

## Carried-forward debts (frozen in 2.0, plan §12)
D-254, D-70/D-71/D-241, D-252, D-113/D-120, D-124/D-244, D-200; D-66, D-28, D-92 (D-220/D-221 partly paid by eval-live).
Transient (A6 hardened the probes, keep watching): `StamperTest` `git exited -1`; Windows `ProcOwnershipTest`; FX-22.

## Branches
All wave-A branches merged and deleted (local + origin, both repos); only `v2/BL` is kept as the baseline source.

## Last gate
Gate P8.A closed: L2 at every merge (counts in each task's `Log:`); CI green on Ubuntu + Windows — run 37044497216
(`488db92`, wave A) and run 37049344584 (`4bb181b`, with the review fixes); tag `v2-wave-A` at `4bb181b`.

## Blockers
None. `gh` not installed; CI status via the public API (`curl -s https://api.github.com/repos/korvin2000/ASTROLABE/actions/runs?branch=main`).
