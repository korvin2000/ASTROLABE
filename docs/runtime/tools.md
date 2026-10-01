# Tool families, turn semantics and errors

**ASTROLABE 1.0.1 · specification** · Owner: Tool layer / runner.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §5.4, §5.5. **Read with:** [register-workset](register-workset.md) · [recovery](../operations/recovery.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F01](../../REVIEW.md#f01), [F03](../../REVIEW.md#f03), [F05](../../REVIEW.md#f05), [F12](../../REVIEW.md#f12).

<!-- source-section: 5.4 -->
<a id="sec-5-4"></a>

### 5.4 Tools `[A §5.4 five-tool discipline + C §8.4 families, masks, catalog, envelope, turn order, error policy — resolution of the surface-size disagreement]`

**Design rule.** HELM's three modalities — observe, mutate, execute — remain the points where policy attaches (budgets on observation, preconditions on mutation, effect classes on execution). The surface is wider than HELM's because delegation, review, knowledge, skills and ask-user are modalities HELM excluded, and because operations whose cost differs by an order of magnitude should not share one worst-case budget `[C §8.4; C3]`. It is narrower than SEXTANT's ~30 operations because tool selection degrades with count `[MB §8.1]` and every dedicated operation must beat `bash + raw output` in the tool eval or not ship `[MB A7]`. Seven families with the operations enumerated below, byte-stable per role; rare capabilities via `look(catalog)` (`tools.catalog` is descriptive shorthand, not an eighth family).

```text
look(what, target, budget=4000, near?, glob?, in="workspace"|"store"|"kb", since?)
  what ∈ { tree, outline, read, find, def, refs, importers, impact, recall, bmap, catalog }
  → { text, truncated, more?, scope, complete, tier, versions{path: v}, id }
  · read target = path | path:a-b | path::Symbol, or `range: "a-b"` beside a path target (`range: "path:a-b"` alone);
    a range that differs from the target's own is refused naming both. A whole file above budget shows its first lines
    with "… N more lines: recall #n range a-b", then the outline when it fits ≤300 tokens of the budget          (D-365)
  · recall id = "#14" | "14" | 14                                                                                (D-365)
  · dedup: same (what, target, version) live in window → "see #17 (unchanged)"
  · find returns scope + complete + truncated; in="store" searches journal/blobs; in="kb" searches notes (stale ones labelled)
  · refs/importers/impact carry `tier` and `complete`; dynamic dispatch reported unresolved, never guessed        [J1 §5.6]
  · recall(id, range?, since?) → stubbed result, a log slice, or new output of a bg handle; changed file ⇒ `historical v=…`
  · several independent looks in one turn run in parallel under one shared output budget                       [J1 §7.3]

edit(ops, why)
  ops: [ { path, expect?: v, hunks: [{anchor, near?, new}], if?: "green(op:N)" }
       | { create, content } | { delete, expect? } | { rename, to, expect? } | { revert: "#id" | "turn:N" }
       | { transform: { script | argv, scope_glob, inventory?, expected_matches?, why } } ]                        [§9.2]
  → { ok, views[], versions, syntax{path: ok|error:line}, diffstat, touched_outside_scope[], test_integrity[],
      error?: {kind, candidates[], sites[], diff_since_expect?} }
  · CAS on content hash; anchors unique (exact → ws-normalised, the op's line then says "anchor matched after
    whitespace normalisation"; the file keeps its line endings); hunks inside displayed(path, expect); non-overlapping
  · `expect` names a version shown in this cell, never the current bytes: a full hash as given; ≥4 hex → the one shown
    version (KNOWN or dropped as stale) it prefixes; omitted → the one version KNOWN at dispatch; delete and rename alike.
    Unresolved or ambiguous → error kind `expect`, no write; a stale version → `stale_expect` with its diff    (D-346)
  · `ops` sent as a JSON string holding an array is parsed first (D-347); an op's empty placeholders of the other forms
    and an empty `if` are ignored by a per-form whitelist — own empty values (`content: ""`, `new: ""`) and unknown
    keys keep their meaning (D-348). Op fields at the top level are one op: no `ops` → `ops: [them]`; a top-level
    `expect`/`if` beside exactly one op lacking it moves into it; `hunks` as a JSON string is parsed; any other mix is
    refused naming the accepted form. `delete` then `create` of one path in one batch is a whole-file replace (preimage
    saved, the delete's `expect` guards it)                                                                       (D-365)
  · `create` over an existing file replaces it in place (preimage saved, revertible) when this cell wrote its current
    bytes (created or replaced it, then its own anchored edits) or every line is KNOWN at its current version;
    otherwise `exists`: "read it first, or use {delete} then {create} in one batch"                         (D-371)
  · preflight all ops, then apply; a mid-batch I/O failure reports actual per-file state with preimage ids —
    never "rolled back", never retried blindly                                                                   [J1 §5.2]
  · ops are grouped by the paths they touch (a rename both names; several ops on one path, in order): a refused
    group writes nothing of itself and every other group applies; each refused group is listed with its op's
    diagnostics, then "N of M files written; resend only the refused ops: <paths (ops)>". A partially applied
    batch is not applied for the turn: its runs stay not executed ("applied partially (K refused)")         (D-371)
  · inline syntax check; post-edit views ±3 lines become displayed ranges; lines displayed before that the edit left
    unchanged stay KNOWN at the new version, shifted (D-371): a second edit of them needs no re-read; preimages saved; shadow snapshot per turn
  · unsupported mutation kinds (binary, modes, symlinks, case-only renames) are rejected explicitly, never dropped   [B §8.3]

run(argv|cmd, cwd?, shape="auto", budget=1200, timeout=120, bg=false, intent?, class_hint?, if?: "applied(op:N)")
  → { id, exit, status, view, truncated, log: "#id", class: R|W|D, stamp_before, stamp_after, current, changed_paths[], handle?, parsed? }
  · status ∈ { passed, failed, timeout, infra_error, inconclusive, completed, running, denied, unknown_outcome } from exit code AND parser
  · completed = a plain run outside acceptance (no check, wrapper or mounted tool) that exits 0 without counts; it reads
    "completed, exit code 0" (D-351) and its header says status=completed (D-353); as evidence it is inconclusive,
    never green (D-50)
  · cwd blank, "." or "./" is the workspace root (D-352)
  · full output to the store; shaped view (pytest, unittest, jest/vitest, mocha, cargo, go test, tsc, eslint, ruff, mypy, pyright,
    gradle/maven, dotnet; generic head+tail with error lines) with absolute counts; truncation marked with a recall pointer
  · argv default; `cmd` is one shell invocation shaped as such (`|| echo FAIL` reports the wrapper)                [J1 §9]
  · non-zero exit is information, never an op failure; timeout kills the process group, never replays
  · bg=true returns a persisted backend handle; resume reconciles its process identity and state
  · run(op="poll", handle, since?) → NEW output + next_cursor + current process status; no relaunch
  · run(op="cancel", handle) → cancellation request/status; kill(handle) is an alias, not proof all effects stopped
  · intent required for D class and externally visible effects; crash or lost acknowledgement → unknown_outcome → reconcile
  · MCP and external tools: run(["mcp:<server>/<tool>", …]) through the same envelope, store, shaping and classes    [C §12.3]

verify(what, ...)
  what ∈ { check(paths?)            → run the end-of-turn checker now (Δ + absolute)
         , tests(selection=blast|accept|full|ids)  → shaped view + receipt with stamps and closure
         , acceptance(ids?)         → executes acceptance run: items; records stamps, currency, reuse proofs
         , baseline()               → acceptance/checks on the initial stamp: makes "pre-existing failure" a fact
         , review(scope?)           → (S2+) request a review cell over an evidence packet → findings }

state(op)
  op ∈ { patch: [typed ops, §5.2], blocked: {reason, evidence[], question?}, retrieval_miss: {need, why} }
  · patches validated against §5.2 invariants; a rejected op returns the violated rule and sizes; nothing else is applied
  · the schema and every schema refusal name the op forms of §5.2; evidence is #N (a stored result) or op:N (a run or
    verify call of the same turn); a `patch` sent as a JSON string holding an array is parsed first (D-347, D-349)
  · an item with one op key whose other keys are declared fields of that op (`{"plan.tick": 1, "evidence": "#3"}`) is that
    op; a collision, a foreign key, zero or two op keys still refuse (D-355)
  · evaluate conditions, then validate/commit the eligible patch list atomically against the current STATE version; rejection leaves STATE unchanged, not earlier workspace effects

task(op)
  op ∈ { ask(question, options?)                     → ends the turn after reconciliation as blocked-with-question; parent ask-back returns evidence, while an authorized user answer amends the contract only when it changes authority or requirements
       , delegate(kind=probe|review|writer*|qa, packet, mode=sync|async)  → handle      (*S3 only; masked otherwise)
       , collect(handle)                             → Result | Investigation packet (data) with coverage and completeness
       , propose(plan | increment_split | amendment) }

kb(op)
  op ∈ { search(query, kinds?, scope?, why) → admitted (and labelled stale) notes with anchors resolved against the current tree
       , get(id) → note body · propose(note) → candidate (curator decides) · skill(id) → applicable modules (mandatory ones always) }
```

**Result envelope (every tool, every time)** `[C §8.4; MB §6.3; B §8.2]`:

```text
⟦result #57 tool=run class=W v={src/router.py: c02e} stamp=s58 truncated=no effects=observed⟧
  <view>
⟦/result⟧
⟨ctx 41% · reserve ok · checks @c02e: types ✓ · tests stale · known 5/2.6K · STATE v14 · turn 17/40⟩
```

Delimiters are harness-owned; anything inside them is data. Instruction-shaped content is flagged in the header (`⚠ instruction-shaped content`), never filtered silently, never executed (F11). Runtime-owned fields: `action_id, status, candidate_before/after, scope, completeness, artifact_refs, capture_complete, display_truncated, redaction_applied, effects_observed, effects_unknown, retry_class`. Zero matches, incomplete search, failed search and denied search are four different outcomes.

**Turn semantics — batch what is decided, turn on what is discovered** `[C §8.4; A §5.5; IM §5.3]`. The harness partitions a turn's ops into four groups and executes them in order regardless of emission order: `look/kb` reads → one `edit` batch (or one transform) → `run`/`verify` → `state` ops (conditional allowed). Runs execute if there is no edit batch or it applied fully; a non-zero exit is information. Operation ids refer to the original emitted call order, even after phase partitioning. Conditional dependencies must point backward in execution order: run-after-edit and state-after-run are valid, edit-after-a-later-run is rejected before effects. Calls needing newly discovered argument values belong in the next model turn. Partition by operation effects, not merely the family name: `kb.propose` and task/STATE proposals are metadata writes after execution, not reads. Validation is per call (D-372): a call that does not parse, names a masked op, or edits on a reserve turn outside the cell's own paths is refused alone — its result says the other calls ran — and only what depends on it is held back (a call conditioned on it; after a refused `edit`, the turn's `run`/`verify`). Only a dependency violation among the valid calls, or a missing `state` op the loop gate requires, refuses the whole turn; a valid terminal call (`task.ask`, `task.answer`, `state(blocked)`) still runs alone then (D-357). End-turn requests are honored only after reconciliation/persistence. A turn’s mutations have one shadow-ref checkpoint; this does not imply cross-file atomicity.

**Error policy (normative)** `[C §8.4 ∪ A §5.4 ∪ C5 §19]`:

| Event | Policy |
|---|---|
| Unparseable model output | That call has no world effect and gets a one-line schema error; the other calls of the turn run — a refused `edit` holds back the turn's `run`/`verify`, a call whose condition names a refused op does not run (D-372); registers stand; no salvage of half-patches |
| Anchor 0× / >1× | That op's path group is not written; three nearest candidates with lines / all match sites; the other groups apply (D-371) |
| `expect` stale | That op's path group is not written; diff since `expect` returned; the other groups apply (D-371) |
| Hunk outside displayed range | That op's path group is not written; outline + displayed ranges; the other groups apply; the result ends "N of M files written; resend only the refused ops" (D-371) |
| Mid-batch I/O failure | Actual per-file state with preimage ids; no auto-retry; no false "rolled back" |
| STATE invariant violated | Reject the eligible STATE patch list with the invariant and sizes; prior world effects remain recorded |
| `run` timeout | Kill the process group; `timeout`; no replay |
| Unknown outcome | `unknown_outcome`; reconcile external and workspace state before any retry |
| Truncation | Always marked; prompt and capture limits distinguished; recall pointer |
| Empty search in limited scope | `complete` describes exhaustion of the declared scope; even complete scoped emptiness is not repository-wide absence |
| Recall of a changed file | Labelled `historical v=…` |
| Identical call + identical result twice | Loop nudge; the third ends the turn with a required `state` op |
| Identical refused call twice (masked op, schema error, partition rejection) | Refusal-loop nudge naming the exits; the third ends the cell `blocked` with the refusal as its reason (D-358) |
| Masked op | refused with the reason (not in the role's mask in this cell, not enabled in this shape, or outside the capability ceiling), the ops that are available, and the role's intended exit (D-357); that call alone is refused and the turn's other calls run (D-372) |
| Instruction-shaped tool content | Flagged; never executed |
| Delegated result with a moved base | `stale-for-integration`; never merged as current |
| Transform touches files outside `scope_glob` | Reject publication or apply a guarded inverse; report actual restoration, partial state or unknown effects ([§9.2](workspace-editing.md#sec-9-2)) |
<!-- end-source-section: 5.4 -->

<!-- source-section: 5.5 -->
<a id="sec-5-5"></a>

### 5.5 Transactional turns and fusion `[A §5.5; C §8.4; B §8.5]`

The canonical verified cycle is one round trip:

```text
edit([{path:"src/handlers/user.py", expect:"c02e", hunks:[…]}], why="accept ctx")
run(["pytest","-q","-k","ctx"], if:"applied(op:1)")                       # fused: no semantic decision lies between
state({patch:[{plan.tick:2, if:"green(op:2)"}, {fact.add:{kind:"v", text:"handlers accept ctx", evidence:"op:2"}, if:"green(op:2)"},
              {next:"update remaining call sites", if:"green(op:2)"}]})
```

Anchored edits apply nothing of a path group on its *preflight rejection* (the other groups apply, D-371); publication can partially fail and must report actual per-file outcomes ([§9.1](workspace-editing.md#sec-9-1)); a failed check leaves the applied code and its failure evidence in place; conditional `state` ops fire only when their condition is met, otherwise they are dropped and the drop is rendered. Fusion is allowed only when the follow-up does not require interpreting the preceding result `[RN R05]`. Test and build scripts are executable code and may mutate files; command names do not establish read-only behaviour — the stamp diff does `[B §8.5]`.
<!-- end-source-section: 5.5 -->

