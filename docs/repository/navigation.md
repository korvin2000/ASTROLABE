# Repository navigation and impact analysis

**ASTROLABE 1.0.1 · specification** · Owner: Workspace / impact engine.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §7, §7.1, §7.2, §7.3, §7.4, §7.5, §7.6, §7.7. **Read with:** [register-workset](../runtime/register-workset.md) · [scheduler](../verification/scheduler.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F04](../../REVIEW.md#f04).

<!-- source-section: 7 -->
<a id="sec-7"></a>

## 7. Repository understanding at scale
<!-- end-source-section: 7 -->

<!-- source-section: 7.1 -->
<a id="sec-7-1"></a>

### 7.1 Atlas and prime: harness-built structure without bodies `[A §7.1; C §8.6; C5 §6]`

Per file: `path | bytes | lang | hash8 | exports[] | imports[] | tests_for?`. Built lazily on first boot, refreshed O(touched) after every edit, transform or mutating run, cached by repo hash. Rendered by focus: `/` → top-level dirs with sizes; a directory → children with export counts and one-line names; a file → its outline. `[R]` carries the prime: tree to depth 3 with counts (vendor/build/generated collapsed but listed), languages, sniffed commands (`Makefile`, `pyproject`, `package.json`, `Cargo.toml`, `go.mod`), the rules file, top ~10 hubs by inbound references, contracts index, global index, behaviour-map excerpt. Cold start is one cached segment instead of ten exploratory turns `[C1 §10; C3 map]`. **Models invent files; the atlas never lies about existence.**
<!-- end-source-section: 7.1 -->

<!-- source-section: 7.2 -->
<a id="sec-7-2"></a>

### 7.2 Symbol index tiers `[A §7.2; C §8.6; J1 §5.6]`

| Tier | Mechanism | Gives | Honesty |
|---|---|---|---|
| 0 | regex / ctags-grade | outlines, definitions by name | `complete: false` on `refs` |
| 1 | tree-sitter | incremental syntax trees, precise outlines, declaration spans, imports | no cross-module resolution |
| 2 | language-service adapter (optional, first paid adapter) | definitions, references, diagnostics with project semantics; incremental type checking that makes fast checkers viable in large repos | adapter reports scope and unresolved dynamic cases |

Every `refs / importers / impact` result carries `tier` and `complete` so an incomplete index is never mistaken for absence (L8). Do not wait for a global indexing job when a bounded search can advance the task `[B §6.3]`.
<!-- end-source-section: 7.2 -->

<!-- source-section: 7.3 -->
<a id="sec-7-3"></a>

### 7.3 Import graph and blast radius `[A §7.3; C4 §9; HELM R1]`

`blast(E) = closure(importers(E)) ∪ E` from the import graph (tier 1+); tests are selected as `tests_for(blast(E))` using naming conventions and the atlas `tests_for` edges. When the graph is incomplete for a file (dynamic imports, reflection, plugins, generated clients, configuration), the scheduler widens to the package suite and says so in the verify line. Full-suite cadence: every `K = 5` verified increments and at campaign end `[ESTIMATE]`. Import closure is a heuristic wherever runtime behaviour includes reflection, schemas or generated code `[B §9.3]`.
<!-- end-source-section: 7.3 -->

<!-- source-section: 7.4 -->
<a id="sec-7-4"></a>

### 7.4 Impact engine — one analysis, four consumers `[FROM-C §9.3]`

```text
impact(E):   E = edit set (or paths)
  fanin(sym)             from the index (refs count, tier, complete)
  importers_closure(E)   from the import graph (complete flag)
  affected_tests(E)      = tests in importers_closure(E) ∪ tests naming E's modules/symbols ∪ acceptance run: items whose input_closure intersects closure(E); unknown closures widen conservatively
  contracts_touched(E)   = CON/ADR notes whose anchors ∩ E ≠ ∅
  risk(E)                = Σ_hunks Δlines · (1 + log2(1 + fanin(enclosing symbol)))      # C1 §6; θ = 40
```

| Consumer | Use |
|---|---|
| Verification depth | slow checks fire early when `risk > θ`; `affected_tests` is the blast-radius set; full suite amortised every K increments |
| Routing | `contracts_touched ≠ ∅` or high fan-in raises the **risk floor** ([§11.2](../operations/routing.md#sec-11-2)) |
| Shape and human anchors | contract touch ⇒ S2 with an ADR in the main line; interface change ⇒ never auto-merged, never in an S3 child |
| Model-callable | `look(impact, E)` lets the worker ask "what does changing X touch?" before a risky change |

**Impact nudge.** After an edit batch the harness diffs outlines of touched files; a changed definition of a symbol with `fanin > 0` whose references were not inspected since the change fires one `[A]` line ([§5.6](../runtime/gates-termination.md#sec-5-6)). Fallback without an index: a repository-wide literal count excluding the edited file. This turns the missing-complement discipline into a deterministic gate at ~30 tokens, exactly when invented-interface failures (F3) become likely.

**Calculation component:** [P3.2.7 / D-63](../../audit/OUT-OF-ORDER-P3.2.7.md) implements the pure
snapshot arithmetic; [API guide](../../core/README.md#impact-snapshots-p327). Its qualified projections,
added-plus-deleted hunk counts and explicit unknowns do not supply import extraction, KB loading,
nudges, scheduler execution or pre-scan. Those integrations retain P3.2.1–P3.2.6 and their runtime gates.
<!-- end-source-section: 7.4 -->

<!-- source-section: 7.5 -->
<a id="sec-7-5"></a>

### 7.5 Behaviour-to-code maps `[A §7.4; C §7.5; IM §7.3; RN R02]`

`BMAP-<subsystem>` notes: behaviour → entry points → implementation → state read/written → important callers → tests → locators (`path::symbol@hash`). Generated from the index where possible and from validated worker observations otherwise; locators validated at compile time and marked `unresolved` when their symbol moved. Disclosed progressively: subsystem → behaviour → symbol → source. A `look(bmap, "payments")` costs ~200–400 tokens and typically replaces a multi-turn search storm on repeat visits `[HYPOTHESIS]`. Maps assist discovery and never replace current source; adoption is measured on total navigation *plus* upkeep cost `[RN R02, R03]`.
<!-- end-source-section: 7.5 -->

<!-- source-section: 7.6 -->
<a id="sec-7-6"></a>

### 7.6 Missing-complement retrieval protocol `[B §6.2 + A §7.5 + IM §7.2; RN R01]`

1. Discover applicable rules, package/build boundaries, test entry points and the requested behaviour (the prime supplies most of this).
2. Locate likely entry points through paths, symbols, error text, tests and lexical search.
3. Read current intact source units with surrounding types and contracts (outline before slice, slice before file).
4. Record the behaviour path: input → implementation → dependencies/state → outputs → checks.
5. Ask which necessary relationship is still missing — a caller, a config, a schema, a fixture, a test assumption — and search for *that* complement, not for another similar snippet.
6. Inspect callers, consumers, configuration, persistence, error paths and compatibility where the change can affect them (`look(impact)` first).
7. Stop when the next decision is supported, or carry a specific unresolved gap forward in `Open`.

The cheap checklist is a contract line in `[S]` (Appendix A) and the baseline; a multi-call retrieval controller is a probe cell ([§10.2](../operations/delegation.md#sec-10-2)), used when the parent would otherwise spend more than ~10 turns of exploration in its own window `[ESTIMATE]`. The paper's pool-based coverage result does not establish an end-to-end repair rate; the retrieval *question* is adopted, the complete solver is evaluated `[RN R01]`.
<!-- end-source-section: 7.6 -->

<!-- source-section: 7.7 -->
<a id="sec-7-7"></a>

### 7.7 Large-monorepo policy `[A §7.6; IM §7.1]`

Scope searches by package and likely dependency direction, then widen on evidence; `find` results always carry `scope` and `complete`; the atlas root render never lists more than one level; `Focus` selects the zoom; blast radius resolves imports per package; commands are sniffed per manifest; a probe cell can own a wider scope without polluting the parent's window. The index never claims to be the repository.

---
<!-- end-source-section: 7.7 -->
