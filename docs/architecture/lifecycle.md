# Campaign and cell lifecycle

**ASTROLABE 1.0.1 · specification** · Owner: Campaign controller / cell runtime.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §3.6, §3.7. **Read with:** [tools](../runtime/tools.md) · [acceptance-review](../verification/acceptance-review.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F01](../../REVIEW.md#f01), [F03](../../REVIEW.md#f03), [F06](../../REVIEW.md#f06).

<!-- source-section: 3.6 -->
<a id="sec-3-6"></a>

### 3.6 Data flow for one increment `[FROM-A §3.4 + C §4.6]`

```text
controller.select_increment()          ready frontier of the requirement graph; regression obligations kept; leases checked
compiler.compile(increment)            contract slice + CON/ADR touching scope + workset seeds + precision-gated notes
                                        + skills (modules) + focus atlas + carry-forward + calibration prior → manifest
                                        (or the pre-compiled [K] if its full compile-input fingerprint still matches, §6.6)
cell.run()                             turns: look/edit/run/verify/state/task/kb; scheduler runs checks by trigger;
                                        register validated on each patch; gauge on every result; gates fire once each
cell.terminate()                       done (acceptance green @ current stamp) | blocked | partial | replan | waiting
scheduler.exit_gate()                  resolves "done" (D-337): complete · rework (red check, rejection, open step) · await a decision
verifier.accept(result_packet)         status validated against receipts, never taken from the model's word
(S3) integrator.integrate(result)      stale-result check → merge queue → combined-tree checks → publish
controller.accept()                    ledger update; receipts stored; touched files → invalidation of dependents
extractor.run(trace)                   low tier, post-cell: candidate notes → admission queue → curator lint
controller.decide()                    await → pending completion + one acceptance decision (D-338/D-339); none ⇒ waiting_for_input
controller.next()                      continue | review cell | ask user | finish campaign (full suite + campaign receipt)
```
<!-- end-source-section: 3.6 -->

<!-- source-section: 3.7 -->
<a id="sec-3-7"></a>

### 3.7 Controller and cell, in pseudocode `[A §3.5 extended with C §5.7 and B §11.5]`

This is lifecycle pseudocode, not executable provider code. Calls below use the existing controller, compiler, cell, runner and scheduler; there is no additional orchestrator.

```text
campaign(request, repo, policy):
    C, W, S, KB = open_contract_workspace_store_and_kb(request, repo, policy)
    reconcile_pending_actions_and_workspace(C, W, S)       # before any new consequential action
    settle_pending_completion_if_still_valid(C, G, S, W)  # D-340: accept commits, rework continues, none waits — no cell, no model call
    imp = impact_prescan(C, W)
    shape = select_shape(C, imp, plan=None)                 # S0–S2 until a plan exists
    G = plan_cell(C, W, S, KB) if shape >= S1 and plan_needed(C) else G_single(C)   # planCell = WhenNeeded
    shape = select_shape(C, imp, plan=G)                    # S3 only from validated ownership/contracts
    while unfinished_requirements(C, G):
        enforce_cancellation_leases_authority_and_reservations(C)
        incorporate_observations_and_invalidate(W, S, G)
        if stop_or_external_wait(C, G): return persist_honest_outcome(C, G, S, W)
        inc = G.next_ready(C.budget)
        if inc is None:
            return persist_wait_block_or_exhaustion(C, G, S, W)  # empty frontier never means completed
        if inc.waits_on_handle and not handle_done(inc):
            poll_same_handle_or_advance_independent_work(); continue
        profile = select_profile(inc.function, inc.packet, imp, policy)
        if profile.refused: return persist_refusal_or_narrow(inc, profile, C, G, S, W)
        ctx = take_valid_precompiled_or_compile(inc, C, W, S, KB, profile)
        if ctx.is_capacity_or_evidence_gap: handle_gap_without_dispatch(ctx, inc); continue
        res = cell(ctx, inc, reserved_cell_budget(C, inc))
        reconcile_and_persist_effects(res, W, S)            # includes partial and late results
        ver = scheduler.verify(res, inc)                   # reuse current receipts; never trust model status
        if res.proposes_done and policy.review_required(inc, ver):
            ver = scheduler.obtain_required_review_once(ver, inc, C)  # before closure; reuse current approval
        if shape == S3 and ver.acceptable:
            ver = integrator.integrate(res, ver)            # combined-state checks and required review
        if ver.awaits_decision:                           # D-339: unverified work is done work waiting for an authority
            ver = decide_once(store_pending_completion(res, ver), authority)   # accept | rework | none ⇒ waiting_for_input
        controller.commit_outcome_if_current(ver, C, G, S)  # checks contract/candidate/generation; one ledger owner
        extractor.enqueue_if_enabled(res, KB); telemetry.record(res, ver)
        dispatch_outcome(ver):                             # same increment on partial; no budget resets
            done -> G.close(inc)
            partial -> G.continue_(inc, register=res.register, seeds=res.workset)
            replan -> replace_plan_with_authorized_coverage_preserved(C, G, res)
            waiting -> persist_handle_and_schedule_independent_work(res)
            blocked -> resolve_by_authority_or_return_honest_block(res)
            failed -> recover_or_escalate_within_original_budget(inc, res)
    return finish(C, G, S, W)   # final acceptance + full-suite policy + required campaign review, else explicit gaps

cell(ctx, inc, budget):
    for turn in bounded_turns(budget):
        enforce_dispatch_authority_and_budget(ctx)
        A = render_current_anchor(ctx, inc)
        request = adapter.render_and_admit(ctx.S, ctx.R, ctx.K, ctx.T, A)
        out = model(request, profile=ctx.profile)
        calls = validate_complete_calls_and_dependencies(out) # per call: a refused call and its dependents never run (D-372); no partial-call execution
        journal.persist_native_output(out)
        ctx.T.append_native(out)                            # assistant calls precede their results
        if not calls:
            proposal = validate_role_output(out, ctx.role)  # probe/reviewer packets are not implementing STATE
            completion = scheduler.assess_role_completion(proposal, ctx, inc)
            if completion.accepted: return reconcile_and_persist_role_packet(ctx, completion)
            if completion.awaits_decision: return reconcile_and_persist_pending(ctx, completion)   # D-339: no more turns
            record_completion_gaps(completion)                # a rejection's findings are pinned whole (D-341)
            if completion.cannot_progress: return reconcile_and_persist_incomplete(ctx, completion)
            continue
        ops = partition_by_effect(calls)                     # stable op ids; §5.4 conditions
        for call in ops:
            res = dispatch_or_record_not_executed(call, ctx)
            journal.persist_result(res)
            ctx.T.append_native_result(call.id, res)
            if res.requests_terminal: record_terminal_request_and_stop_new_effects()
        reconcile_workspace_and_persist_checkpoint(ctx)      # every exit path, including partial failure
        scheduler.end_of_turn_checker_if_needed(ctx, time_box=20)
        if terminal_requested(): return persist_role_packet(ctx)
        if (turn + 1) % k == 0: ctx.evict_complete_protocol_units()
        if next_request_exceeds_usable_context(ctx):
            ctx = rebuild("pressure", ctx)                  # replaces S/R/K/T, register projection and Workset
            if second_pressure_rebuild(ctx): return persist_partial_with_replan_hint(ctx)
    return persist_partial(ctx)
```

`plan_needed(C)` is false only under `ShapePolicy.planCell = WhenNeeded` for an S1 contract with no `review:` item, no contract touched and a `G_single(C)` the plan validator admits as it stands (no refactor-mode checklist owed, every requirement with acceptance of its own); the controller then installs `G_single(C)` as the plan, journals `plan cell skipped: <reason>`, and continues into the increment loop. `planCell = Always` runs the plan cell as before; a replan or increment split always does. The plan cell itself may run R-class commands only.

**Task limits (ASTROLABE 2.0 C3, plan §4.6).** `CampaignPolicy.limits` — money, active minutes, model requests; none by default — are the host's and are kept with the campaign (a reopen naming none keeps them; `TaskLimits.NONE` lifts them). `enforce_dispatch_authority_and_budget` asks them before every turn and every admission at one price `C` for the next call (money `C = max(E, u)`: `E` the request's conservative estimate — all input at the dearest rate plus the full output headroom — `u` the dearest accounted call; minutes the mean call time; requests 1) and with a reserve `R = max(0, min(3·C, L − C))`: generation needs `S + C + R ≤ L`, a verify-and-report turn `S + C ≤ L`. The working part, once spent, stays spent until the host changes a limit — the latch is journaled and holds across reopens; the §5.9 gate fires and only verify-and-report turns run, with no edit (the D-366 repair is the turn budget's, never a limit's); a generation turn whose rendered request first meets the reserve at admission is rendered again as verify-and-report. A call known never to have reached the provider releases its hold and counts no request; an unknown outcome keeps its hold. The check is repeated in the transaction that writes the call's durable hold, so concurrent cells never pass a limit together. **Guarantees:** requests — hard; money — the *accounted* spend under conservative admission (a provider billing above the hold can exceed `L` by billed − held, recorded); minutes — an admission threshold (no call starts once a mean call would cross the working part; a running call can overrun by its duration; every `run` launch, wait and poll and every check deadline is cut at its dispatch to the active time left, and with no whole second left nothing is dispatched). A refusal that ends a cell is remembered; at the next boundary the controller stops instead of dispatching: it checkpoints the tree, re-accepts verified increments on current receipts (nothing re-executed), names the best verified candidate — the latest stamp the acceptance verified on the main line, with what was verified there, what only earlier and what was accepted without verification — without replacing the user's tree, and ends `budget_exhausted` with `BudgetStop` `task_limit_*`, the receipt's `limit` and `budget.limit_reached` (action `raise_limit`). Any other stop keeps its own cause. A reopen whose limits leave room again (or after the per-run cell cap, `BudgetStop.CellCap`) applies `Transition.LimitRaised` and continues the same attempt; a limit still spent is journaled and reported. Spend comes from durable records only (usage rows; journal run sessions, paused only while every branch — the run and each child cell in flight — waits for the host, a dead run closed at its last event), so a reopen counts nothing twice. The balance profile (`Economy` / `Balanced` / `Thorough`, `Balanced` = the declared defaults) is frozen with the attempt and applies once, to the model the host supplied — a child cell is never narrowed again.

**Raise and continue (ASTROLABE 2.0 C14).** The contract's token budget follows the host's `CampaignPolicy.tokens` on a reopen: a different value is written into the contract at its version — a budget is not an amendment, so reviews and receipts bound to the version stay valid — and journaled (`budget: contract tokens set by the host`); a decrease stops at the tokens already spent, and the same policy changes nothing (D-392). A `BudgetStop.ContractBudget` stop is therefore resumable: a reopen that raised the tokens so they leave room applies `Transition.LimitRaised`; otherwise the stop holds (`budget: contract budget still reached`). After any reopen of a `budget_exhausted` campaign that could not continue, `OpenedCampaign.limitHold` names what holds it — `LimitHold(stop, status, reason)`: the task limit (possibly another than the one it stopped on) or the contract budget, with the task limits' spend — beside the journal record and, for a task limit, `budget.limit_reached` (`stopped`). The facade continues a stopped task with `Astrolabe.resume(project, work, policy)` (`AstrolabeJava.resume`/`resumeBlocking`), the same work and attempt; `CampaignHandle.limitHold` carries the hold. `BudgetStop`, `LimitKind` and `CostBasis` travel in JSON as their wire words (`task_limit_money`, `money`, `estimated`, …); records written with the constants' names still read. The host's explicit effort (`CellModel.effortExplicit`) is stronger than the profile's effort step; an effort left to the profile is stepped as above.

`assess_role_completion` uses the implementing exit gate for implementers/writers, and the declared packet validator for plan/probe/review/QA/helper roles; it never lets a reviewer recursively demand a review of its own verdict. A completion proposal can request scheduler-owned required checks/review, but is accepted only with current evidence. The outer verification step reuses those results instead of invoking a second judge. All terminal branches reconcile/persist already-started actions; a failed check becomes a specific gap, a missing or unverifiable one an acceptance decision (D-337–D-340), never an endless finalization loop and never a `blocked` or `failed` outcome by itself. Boundary pre-compilation may run locally while final checks are pending ([§6.6](../context/continuity.md#sec-6-6)); publication remains contingent on successful verification.
<!-- end-source-section: 3.7 -->

