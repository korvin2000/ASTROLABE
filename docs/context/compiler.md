# Context compiler and admission budget

**ASTROLABE 1.0.1 · specification** · Owner: Context compiler.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §6, §6.1. **Read with:** [context-layout](../runtime/context-layout.md) · [contracts](../state/contracts.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F06](../../REVIEW.md#f06).

<!-- source-section: 6 -->
<a id="sec-6"></a>

## 6. Context compilation (between cells)
<!-- end-source-section: 6 -->

<!-- source-section: 6.1 -->
<a id="sec-6-1"></a>

### 6.1 The compile function `[A §6.1 + C §6.2 + B §7.2]`

```text
compile(increment, C, W, S, KB, seeds, profile):
    reconcile_contract_and_candidate_versions()                                                   # B §7.2
    mandatory = contract_slice(C, increment)          # requirements (verbatim), ALL constraints, exclusions, acceptance ids + kinds
              ∪ mandatory_skill_modules_with_prerequisites_and_invariants
              ∪ rules_file ∪ index/contracts.md ∪ CON/ADR notes whose anchors ∩ increment.write_scope ≠ ∅
              ∪ carry_forward(register: dead ends, open items, decisions, last verify lines)
              ∪ pre_existing_failure_ledger ∪ packet.required_refs ∪ native protocol items that must remain intact
    budget    = C_profile·α − |S| − |R| − |pinned_T_and_retained_protocol| − reserve(output + next observation + [A]_max + estimation margin)
    if tokens(mandatory) > budget:  return NEEDS_RESCOPING_OR_LARGER_PROFILE      # never drop an invariant; controller asks for increment_split
    selected  = mandatory
    selected += workset_seeds(seeds, share ≤ 4K)          # re-served at current versions with hashes; changed files → NOT SEEN + note
    selected += kb_slice(KB, increment, cap = 8 notes / 1.5K)   # precision-gated (§6.3); may be empty
    selected += skill_modules(increment)                  # module granularity; mandatory sections survive filters
    selected += focus_zoom(W.atlas, register.focus or increment.write_scope) + calibration_prior(KB, increment)   # §6.7
    selected  = greedy_cover(mandatory, optional_candidates=selected - mandatory, budget=budget)  # bounded admission, dependency bundles, no silent mandatory drop
    expand dependencies of selected notes (depends_on) within budget; recheck versions and coverage
    assert coverage(selected, required = [constraints, acceptance, CON for touched paths, rules])   # presence, not understanding
    if required coverage unmet:  return NEEDS_MORE_EVIDENCE                                        # worker investigates; absence ≠ evidence
    validate_tool_pairing_and_total_context(render)                                                # adapter rejects malformed histories
    persist manifest(selected, versions, omissions with reasons, budget arithmetic, reason for the boundary)
    return render([S], [R], [K] = selected)
```

Selection order is a starting policy, not a theorem about attention: **mandatory → affected contracts → carry-forward → direct evidence (seeds) → local implementation → lessons/pitfalls → skills → background** `[MB §4.1]`. `C_profile` is the profile's *actual* supported context; reserves account for output and reasoning behaviour per provider; a per-result cap does not bound a multi-result turn, so the admission check is re-estimated on actual provider usage `[B §7.2]`. Coverage assertions check presence; they cannot prove that every necessary relationship was identified or that the model understood it; every serialized contribution is charged once, including tools, pinned user text, retained protocol items and provider-effective continuation history; rules/index content already in `[S]/[R]` is referenced for coverage, not duplicated into `[K]`. Before every model dispatch, a hard total-context admission check includes output/reasoning headroom; fixed component caps are ceilings, not additive promises — the worker can report a missing complement at any time, and an omitted optional note must never look like a searched-and-absent fact `[B §7.2]`.
<!-- end-source-section: 6.1 -->

