# Verification scheduler, evidence and baseline

**ASTROLABE 1.0.1 · specification** · Owner: Verification scheduler.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §8, §8.1, §8.2, §8.3, §8.4, §8.5. **Read with:** [evidence-coherence](../state/evidence-coherence.md) · [navigation](../repository/navigation.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F04](../../REVIEW.md#f04).

<!-- source-section: 8 -->
<a id="sec-8"></a>

## 8. Verification and truthful completion
<!-- end-source-section: 8 -->

<!-- source-section: 8.1 -->
<a id="sec-8-1"></a>

### 8.1 The verification scheduler `[A §8.1 + B §9.2 applicability and reuse proof + C §9.1 layers]`

```yaml
check:
  id: CHK-types-touched | CHK-tests-blast | CHK-accept-AC-4 | CHK-full | CHK-review-inc | CHK-review-campaign | CHK-lint | CHK-quality-gate
  kind: syntax | type | lint | unit | integration | acceptance | full | quality | review
  selector: touched | blast | named(cmd) | all
  input_closure: known(paths[]) | package(p) | unknown        # what invalidates it — joins the coherence protocol (§4.4)
  definition_version: <hash of check definition, argv/cwd/selector and parser policy>
  closure_manifest: <paths@versions + directory membership + relevant config/lockfiles/fixtures; completeness and exclusions>
  cost_class: inline | fast | slow | expensive
  trigger: every_edit | end_of_turn | step_boundary | risk>θ | increment_end | campaign_end | on_demand
  last: {receipt_id, stamp, outcome, counts, applicability: current | stale | unknown, reuse_of?}
```

| Layer | Trigger | Runs | Window cost |
|---|---|---|---|
| Inline syntax | every anchored edit (sync) | parser / `py_compile` / `node --check` | one line per error, in the edit result |
| **End-of-turn checker** | after any mutation, on touched files, time-boxed (20 s) | `pyright`/`mypy`, `tsc --noEmit`, `cargo check`, `go vet`, `ruff`, `eslint` | Δ lines only on change; absolute status line always (`types ✓ 14 files @c02e`); `not_run` if deferred before dispatch, or `timeout` if started and terminated at the time box; scheduled at step boundary, never silence |
| Blast-radius tests ∪ step `accept:` | `[>]` moves · `risk > θ` · fused `run(if: applied)` · `verify(tests)` | `affected_tests(E)` ∪ acceptance `run:` items whose closure moved | shaped view with counts; receipt with stamps and closure |
| Increment acceptance | increment end | all `accept:` of the increment | receipts; exit-gate input |
| Full suite + quality gates | every K increments; campaign end | project suite; configured complexity/duplication thresholds | shaped view |
| Independent review (L5) | risk floor · contract/ADR touch · cheap-tier output · test-integrity flag · `review:` items · campaign end (S2+) | review cell ([§8.8](acceptance-review.md#sec-8-8)) | findings → Open items |
| Integration re-verification | S3 merge | blast radius over the **combined** tree | receipt |

**Validity and applicability** `[A §8.1; B §9.2]`. Any mutation (edit, transform, `run` that changed files — detected by stamp diff) marks stale every check whose input closure intersects the changed paths; a check with `unknown` closure is marked stale conservatively. The historical result never changes; its *applicability* to the current candidate is computed. When the exact declared closure is unchanged and complete, the verifier attaches the old receipt to the new candidate with a recorded **reuse proof** (`reuse_of: rcpt-19, closure_unchanged: [paths@hashes]`); unknown closure requires a rerun at the conservative containing scope. Reuse also requires unchanged check definition, argv/cwd/selector, verifier/parser version, relevant environment and external fixtures; a path list alone is not a completeness proof. Package/glob closures include membership, so added/deleted tests or inputs invalidate them. Import/test-name heuristics choose scope but do not establish a complete reuse closure. Review/check assessments additionally bind their obligation versions ([§8.7](acceptance-review.md#sec-8-7)). **Verify-on-stop** reuses valid receipts and runs only missing or stale checks — never a blind full suite per completion proposal `[IM §9.5]`.

**Reserve.** `reserve.verification` (15 % of the cell's tokens and turns) is unspendable on anything but checks and the final register patch; `reserve.recovery_and_persist` (5 %) covers the Result Packet, receipts and the STATUS note. If the reserve is reached, the cell checkpoints as `partial` and says what is unverified `[IM §9.5; B §11.6]`. Reservations are enforced across concurrent calls and reconciled against actual usage; an external call with uncertain usage keeps a conservative reservation until reconciled `[B §11.6]`.

**Why synchronous in the baseline.** Keel's async watchers deliver the highest-value feedback in the field but need supersession, version tags and a scheduler; a checker that runs after the turn's mutations and before the next prompt delivers the same delta at the same point in the conversation `[QA §4; C §9.1]`. Async watchers return as an extension with an ablation once a tier-2 adapter makes them fast ([§18.2](../implementation/roadmap.md#sec-18-2) Stage C).
<!-- end-source-section: 8.1 -->

<!-- source-section: 8.2 -->
<a id="sec-8-2"></a>

### 8.2 Claim-matched ladder `[MB §9.1; A §8.2; C §9.4; B §9.1]`

| Level | Check | Applies when | Evidence |
|---|---|---|---|
| L0 | Format, lint, types | always | checker receipt |
| L1 | Unit tests (blast radius; acceptance `run:`) | code changes | test receipt with stamps and closure |
| L2 | Integration / e2e; combined-tree checks | cross-module changes, contract touches, S3 integration | receipt |
| L3 | Product use by a QA cell (CLI / HTTP / browser drives the product in a disposable environment) | user-visible behaviour claims | screenshots/logs as artifacts |
| L4 | Measurement / eval gates | performance, agent-behaviour, safety claims | measurement artifacts with workload, environment and variability |
| L5 | Independent clean-context review | consequential decisions, low-tier output, conflicts, `review:` items, campaign end in S2+ | judge verdict |

Depth follows the claim, not ritual: a formatting change needs L0; a parser change needs behavioural cases; a migration needs compatibility **and rollback** checks; a performance claim needs a measurement; a one-line authorization change may need L2 + L5. Tests written by the same cell are useful but not independent proof; existing regression checks are never dropped; `not_tested` is recorded. Model-generated tests' agreement with the generating model is not independent acceptance `[B §9.1]`.
<!-- end-source-section: 8.2 -->

<!-- source-section: 8.3 -->
<a id="sec-8-3"></a>

### 8.3 Rendering: delta + absolute `[L2; A §8.3; J1 §5.3]`

```text
── Checks @d1e7 ── types(touched): Δ +1 −2 · now 3 (#44)          ← never "0 new" alone
                   tests(blast 14): 13 pass 1 fail #42 @s8 · accept AC-4: red
                   full: stale (s3, closure moved 2 increments ago) · review: not run
                   (unchanged red states are still rendered, compressed: "types: no change · still 3 @d1e7")
```

Green is a scoped observation: the line names invocation scope (`touched`, `blast 14`, `k ctx`) and the stamp. Parsed counts come from runner output; a generic exit code never becomes a count; `pytest -k nonexistent` (exit 5) is `inconclusive`, never `passed` `[J1 §9]`. Runners are invoked directly by the scheduler, never through `|| echo` constructions. Superseded checker results are archived, not presented as current; periodic reconciliation catches misses; completion uses receipts, not silence `[B §8.5]`.
<!-- end-source-section: 8.3 -->

<!-- source-section: 8.4 -->
<a id="sec-8-4"></a>

### 8.4 Stamps, receipts and the status vocabulary `[A §8.4; C §9.2; B §9.2]`

Stamp = base commit + tracked delta hash + untracked manifest hash + environment id (toolchain, lockfiles, relevant fixtures). A commit hash alone is insufficient in a dirty tree. Receipts record `stamp_before / stamp_after`, argv, cwd, verifier version, environment and `input_closure`; `current = (stamp_after == stamp_now)` or a valid reuse proof. Historical outcome ∈ `passed · failed · timeout · infra_error · inconclusive · not_run · unavailable · denied · unknown_outcome`; applicability ∈ `current · stale · unknown` is computed separately and never overwrites that outcome; a parse error or an absent result is never mapped to success. Equal before/after hashes alone cannot exclude an intermediate mutation *during* a check; strong evidence requires execution isolation or an enforced no-concurrent-writer boundary, and mutable external services appear in the receipt's limitations `[B §9.2]`.
<!-- end-source-section: 8.4 -->

<!-- source-section: 8.5 -->
<a id="sec-8-5"></a>

### 8.5 Baseline receipt and pre-existing failures `[A §8.5; C2 → HELM A5]`

At campaign start (S1+) or lazily through `verify(baseline)` (S0), the scheduler records a **baseline receipt** — the relevant suite on the initial dirty tree at `s0`. Failures present there form the **pre-existing-failure ledger**, rendered once in `[K]`. A later red that matches the ledger is `pre-existing (unchanged)`; a new red is steering. Nothing may be called pre-existing without this receipt. If edits have already started, the baseline runs against the captured initial dirty candidate, not the current edited tree. Match test identity, comparable environment and failure signature, not only counts or a test name. Baseline failure is not permission to waive task-specific acceptance; any allowed pre-existing full-suite exceptions remain explicit in the finish receipt.
<!-- end-source-section: 8.5 -->

