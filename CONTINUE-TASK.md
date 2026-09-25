# Handoff — next session

Rewritten every session (≤40 lines). Workflow: `CLAUDE.md` § Workflow. State snapshot: `actual_state.md`.
History: `audit/SESSION-HISTORY.md` (never read at startup).

**Checkpoint:** local Windows session on `main` (owner-authorized direct pushes). **185/185 DONE — the P0–P6 plan is complete.**
P4 phase gate CI 36162949349; P5.1–P5.7 + P6.2 gate CI 36167689819; final gate (P5.8 + P6.1 + P6.3) CI 36172349269.
Delegation this session: Opus worktree agents per the global tier table (owner override of the Fable rule); dependencies
are now allowed under D-175 (first use: `index-treesitter`, tree-sitter-ng 0.26.6).

## Next (needs an owner decision — no dependency-ready task remains)
1. **P7 is out of scope of this plan** ("none of these tasks start under this plan"): live provider transports, MCP client,
   confined runner backends, LSP adapter, live evaluation, hosts. Starting any of them needs an owner request.
2. Otherwise pay the carried debts below (each is a local, testable change), or triage the untracked `findings.md` audit.

## Carried-forward debts (no open owner task; D row in brackets)
1. Recovery: per-call `Guards` feeds inside the cell loop, tool-level retry, recovery on the S0 path, a live S2 repair
   fixture [D-254]. Replan / `increment_split` routing to the plan role and `replan` manifests [D-70, D-71]; S3 re-selection
   after a reopen and replanning a rejected unit [D-241].
2. Dense retrieval results do not feed the ranked `[K]` injection; no `indexes/` embedding persistence [D-252].
3. Behaviour maps: no focus subsystem feeds `Prime.render(bmapExcerpt)`; `BMAP_DELTA`/`SKILL_DELTA` candidates are not
   folded into maps/skills; probe findings are not filed as `NEG`/`BMAP` candidates [D-113, D-120].
4. Review evidence packet: diff runs `s0 → now`, coverage report `null` [D-124]; review gate decline/unavailable and rebase
   conflict paths untested in S3 [D-244]. The Linux shell variant of the FX-27 check never ran locally.
5. Eval: 22 §19.5 ablations have no `Config` switch; fixtures run in their own config, not per arm [D-220, D-221].
6. QA is not scheduled by the controller; a model-driven QA cell uses `QaCell.completion` only in P7 [D-200].
7. Older: S0 finish runs no full suite (D-66); `Economics` takes ρ from the caller (D-28); `RequirementGraph.ledger`
   needs stamp equality; the controller's `Verifier.accept` does not re-check impact nudges (D-92); the controller never
   calls `Curator.admit` (host cadence); extraction cost journaled, not priced.
8. Transient, recorded: `StamperTest` `git exited -1`; Windows `ProcOwnershipTest` READY timeout; FX-22 `bg-end` order.

## Owner review suggested (local choices, safe defaults in place)
D-151 (allowance exhaustion ⇒ `blocked_external`), D-152 (risk > θ ignores unknown risk), D-170/D-183 (S2 runs, S3 behind
its flag), D-160 (plan `[S]` without Appendix A) and the repair budget 6 turns / 12K (D-163), D-221 (clamped routing ⇒
`floors` off), D-230 (MAST categories), D-106, D-97.

## Blockers
None. `gh` is not installed locally: CI is polled through the public Actions API.
