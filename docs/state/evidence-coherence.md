# Evidence store and five-horizon coherence

**ASTROLABE 1.0.1 · specification** · Owner: Evidence store / workspace registry.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §4.3, §4.4. **Read with:** [components](../architecture/components.md) · [scheduler](../verification/scheduler.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F04](../../REVIEW.md#f04).

<!-- source-section: 4.3 -->
<a id="sec-4-3"></a>

### 4.3 Evidence store: journal, blobs, stamps, receipts, evidence states `[A §4.3 + B §5.2, §5.4, §5.5 + C §9.2]`

| Record | Fields | Notes |
|---|---|---|
| **journal event** | `event_id, work_id, attempt_id, context_id, turn, kind (call·result·edit-intent·edit-outcome·check·nudge·boundary·intent·reconcile), args_digest, refs` | append-only; searchable via `look(find, in="store")` |
| **blob** | content-addressed: tool outputs, preimages, post-images, diffs, logs, packets | truncation never applied to blobs, only to views |
| **stamp** (`candidate_id`) | `base_commit, tracked_delta_hash, untracked_manifest_hash, env_id, at` | whole-workspace identity; computed before/after any `run(verify=true)` and at every cell boundary `[C2 §8]` |
| **receipt** | `receipt_id, check_id, acceptance_ids[], cmd, cwd, argv_or_shell, stamp_before, stamp_after, env_id, verifier_version, check_definition_version, contract_version, outcome, parsed {passed, failed, errors, skipped, discovered}, input_closure, raw: blob, limits[], reuse_of?` | immutable; new candidate ⇒ new receipt or a recorded reuse proof ([§8.1](../verification/scheduler.md#sec-8-1)) |
| **observation** | `id, action_id, candidate_id, content_ref, scope {paths, ranges}, completeness, source_versions, capture {complete, redacted}` | what the model actually saw `[B §5.2]` |
| **claim** | `id, text, kind (h·v·x), evidence_state (hypothesis·supported·refuted·stale·unknown), authority (user·rules·observed·inferred), freshness (current·stale·unknown), evidence_refs` | register facts and note claims share this shape `[B §5.4; J1 §7.2]` |
| **intent** | `intent_id, action_id, argv, cwd, expected_effect, idempotency_key?, status (recorded·dispatched·running·observed·committed·unknown)` | persisted before any D-class or externally visible action `[C §8.5; B §5.5]` |

**Evidence states are separate from authority and freshness** `[B §5.4]`: a `supported` claim names its observation and its limits; the runtime validates references but cannot certify the interpretation. Confidence scores, if recorded, never confer permission or correctness. Current source is authoritative about its bytes; user requirements are authoritative about intended behaviour even when the source violates them.

**Consequential-action ordering** `[B §5.5]`: reserve budget and record intent → dispatch → observe running/terminal state → persist artifacts → commit receipt and resulting state. A crash can occur between any pair; action ids and execution handles let resume classify an action as never dispatched, still running, completed, partially applied or unknown. Artifact publication precedes the transaction that references it; orphaned blobs can be collected, missing referenced blobs are integrity failures. SQLite transacts the metadata it owns, not repository edits or remote effects.

Three evidence lines are contract text in `[S]` `[HELM A5]`: *exit 0 proves that invocation only*; *an empty limited-scope search is not absence*; *"pre-existing failure" requires a baseline receipt* ([§8.5](../verification/scheduler.md#sec-8-5)).
<!-- end-source-section: 4.3 -->

<!-- source-section: 4.4 -->
<a id="sec-4-4"></a>

### 4.4 The coherence protocol: one version registry, five horizons `[FROM-C §4.8, §8.3 + A §8.1 closures — NEW unification N1]`

```text
version(path)   := content hash of the working file            stamp(tree) := candidate identity (§3.3)
displayed(path, v) := union of line ranges shown for exactly that version
on change(path, v → v'):
    mark live reads @v stale; displayed(path, ·) := post-edit views @v'
    mark STATE facts anchored @v stale; mark KB notes anchored @v or depending on a changed contract stale-recheck
    mark receipts whose input_closure ∋ path stale; receipts with closure = unknown stale (conservative)
    mark delegated results whose read_versions include (path, v) stale-for-integration
    schedule the end-of-turn checker on path; refresh atlas row; drop Workset entry and announce it
serve(item)     := current iff its anchors match; else labelled stale | historical — never silently current
delete(item)    := never; evict from the window, keep in the store
```

| Horizon | Item | Anchor | Stale when | Consequence |
|---|---|---|---|---|
| Turn | live tool result in `[T]` | `versions{path: v}` | path gains a new version | dropped from KNOWN; marked now, stubbed at the batch ([§5.3](../runtime/register-workset.md#sec-5-3)) |
| Cell / task | STATE fact `v … @v [#id]` | `@v`, `#id` | path version changes | `v(stale @v)` in `[A]`; re-verify before an active step relies on it; two cells unreferenced ⇒ moved to STATUS note ([§6.4](../context/continuity.md#sec-6-4)) |
| Verification | receipt | `stamp_after`, `input_closure` | any path in the closure moves (or closure unknown and anything moves) | `stale`; verify-on-stop re-runs only stale checks; reuse proof when the closure is unchanged ([§8.1](../verification/scheduler.md#sec-8-1)) |
| Project | knowledge note | `anchors[{path, version, symbol}]`, `validity.depends_on` | any anchor or dependency changes | excluded from injection; `stale` on explicit search; curator recheck ([§4.5](../knowledge/records.md#sec-4-5)) |
| Integration | delegated Result Packet | `base.stamp`, `read_versions{}` | main tree or a read dependency moved since dispatch | rejected or re-evaluated; never merged as current ([§10.4](../operations/delegation.md#sec-10-4)) |

One registry, one rule: *mark, never serve as current, never delete the evidence.* The scheduler's validity computation (A) and the coherence protocol (C) are the same code path, which is what lets a receipt survive an unrelated edit while a stale read never survives a related one.
<!-- end-source-section: 4.4 -->

