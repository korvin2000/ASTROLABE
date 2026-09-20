# Workspace, guarded edits and transformations

**ASTROLABE 1.0.1 · specification** · Owner: Workspace / tool executor.

[Architecture map](../../SOTA-BEST-MIXED-AGENT.md) · [Document index](../INDEX.md) · [Source convention](../../SOURCE-REGISTER.md#citation-convention)

**Scope:** §4.6, §9, §9.1, §9.2, §9.3, §9.4, §9.5. **Read with:** [register-workset](register-workset.md) · [security](../platform/security.md). Load companion sections only when the task crosses that boundary.

**Applied corrections:** [F02](../../REVIEW.md#f02), [F05](../../REVIEW.md#f05), [F11](../../REVIEW.md#f11).

<!-- source-section: 4.6 -->
<a id="sec-4-6"></a>

### 4.6 Workspace: shadow ref, dirty state, execution modes, orientation `[A §4.5 + C §8.5]`

- **Initial dirty-state record**: tracked delta, staged content, relevant untracked files and modes captured at campaign start; candidates are created from that actual state, not from `HEAD`; `revert` never crosses it; the final report separates agent changes from pre-existing user modifications; applying an accepted patch back to a diverged user workspace is an integration problem, never permission to overwrite `[C2; B §4.2]`.
- **Shadow ref** `refs/astrolabe/<work>/<attempt>/<workspace>/head`: snapshot after every mutating turn; constant-time snapshot selection for any turn; restoring files costs I/O proportional to the affected contents; per-edit `revert:#id` from preimages (guarded against current content); user branches, index and stash are never touched; no `reset`, no `clean`, ever.
- **Execution modes** `[C §8.5; J1 §5.7]`: `trusted-local` — no confinement, effect classes only, labelled as such in `[S]` and in every report; `confined` — an external runner (container, bwrap, sandbox-exec, firejail) with harness-supplied policy: writable roots = workspace + tmp, env allowlist, network off by default, resource limits, timeouts. The harness never calls a denylist or a worktree a sandbox. Protect the canonical harness state and Git common metadata from untrusted subprocess writes; tool capabilities authorize approved access, not arbitrary shell access to those stores.
- **Effect classes** are policy labels verified after the fact: `R` expects no workspace writes and is reclassified to `W` if the stamp changed; `W` writes inside workspace + tmp; `D` covers writes outside the workspace, network egress, mutation of the user's git refs, package installation (configurable), privilege escalation, destructive git. `R/W` run without prompts in either mode; known read-only duties use read-only source mounts or an isolated disposable candidate, with scratch writes explicit; in `trusted-local`, an R label alone is not proof of read-only execution; `D` ends the turn with a question (interactive) or is denied with a recorded reason unless allowlisted by the contract (autonomous).
- **Atlas, symbol index, import graph, version registry**: [§7](../repository/navigation.md#sec-7).

---
<!-- end-source-section: 4.6 -->

<!-- source-section: 9 -->
<a id="sec-9"></a>

## 9. Editing at scale
<!-- end-source-section: 9 -->

<!-- source-section: 9.1 -->
<a id="sec-9-1"></a>

### 9.1 Anchored compare-and-swap path `[A §9.1; C §8.4; C1 §5.2; C2 §4.2]`

Mandatory `expect` (content hash of the file version displayed); anchors unique (exact, then whitespace-normalised); `near` disambiguates; hunks must lie inside displayed ranges of that version and must not overlap; three nearest candidates with line numbers on a failed anchor; *diff since `expect`* on a stale failure so the retry costs no re-read; ±3-line post-edit views register the new range; all ops preflighted before any write; preimages saved; a mid-batch I/O failure reports the actual partial state and never claims rollback `[J1 §5.2]`. Line-range identities are not offered `[J2 §8]`. **A hash is not a lock**: runtime writers are serialized per workspace, and concurrent human edits are handled by rechecking and refusing unsafe publication, never by overwriting `[B §8.3; J1 §5.1]`.
<!-- end-source-section: 9.1 -->

<!-- source-section: 9.2 -->
<a id="sec-9-2"></a>

### 9.2 Scripted transform path `[A §9.2 + B §8.4 + C §8.4 edit.script — MERGED]`

```text
edit([{transform: {script: "<python/node/sed/comby/ast-grep source or path>", scope_glob: "src/**/*.py",
                   inventory: ["src/a.py", "src/b.py", …]?, expected_matches: {min: 30, max: 40}?, preconditions: [...]?,
                   why: "rename Router.dispatch → route across call sites"}}])
→ { ok, files_changed: 14, hunks: 31, per_file: [{path, +n −m, version_after}], diff: "#57", syntax: {…},
    touched_outside_scope: [], inventory_ok: true|false, match_count: 31, representative_sites: [path:line …] }
```

Semantics: the script runs in the jail against the workspace; the harness computes the diff, snapshots the shadow ref, records preimages, runs inline syntax on every changed file, refreshes atlas rows, and returns a **diff receipt** — a bounded per-file summary, the match count against `expected_matches`, a comparison against the declared `inventory`, three representative and three unusual sites for the model to inspect, and a recallable full diff. Changed files enter the Workset as `touched-by-transform (NOT SEEN)`; a subsequent anchored edit needs a current read. The scheduler treats the transform as touching all changed files: blast-radius tests (usually the package or full suite) are mandatory before the increment closes, and the review cell receives the full diff id. Before dispatch, resolve the allowed target inventory, preconditions and expected-count policy; an omitted constraint is explicitly `unspecified`, never `inventory_ok: true`. Record recoverable preimages before mutation, not after discovering the diff. On out-of-scope writes or count failure, do not accept the transform: discard an isolated candidate when available, otherwise attempt a guarded inverse only where current bytes still match its own postimages. Report `restored`, `partial` or `unknown_outcome` with actual effects; never promise unit rollback or undo of external effects. The label is **transformation-based validation** — never a claim that the model read every edited byte `[B §8.4]`. Codemods should be idempotent and scoped; a successful sample does not certify all targets. This is the path for renames, signature migrations, import rewrites and formatter sweeps: forty displayed regions become one diff receipt.
<!-- end-source-section: 9.2 -->

<!-- source-section: 9.3 -->
<a id="sec-9-3"></a>

### 9.3 Reversibility `[A §9.3; C §8.5]`

`revert:#id` (per edit, from preimages, guarded against current content) and `revert:turn:N` (shadow-ref restore) both produce a diff receipt and re-run inline syntax; neither crosses the initial dirty-state record; user branches, index and stash are untouched. In-place undo is a new version-checked inverse change; it never resets unrelated user work or claims to undo an external effect `[B §8.3]`. Reversibility is what lets the model experiment instead of over-confirming (F6).
<!-- end-source-section: 9.3 -->

<!-- source-section: 9.4 -->
<a id="sec-9-4"></a>

### 9.4 Formatters, generators and foreign writes `[A §9.4; C §8.3; J1 §8.2]`

Any `run` whose stamp-after differs from stamp-before is a mutation: the scheduler diffs the tree, refreshes atlas rows, drops affected Workset entries with an announcement, reclassifies an `R` run to `W` only for observed in-scope writes, retaining `D` or `unknown` for broader/uncertain effects, and invalidates reads, facts, notes and receipts by closure. Formatter-induced changes are shown as a compact `touched (by run #41 ruff format: 14 paths)` line so the model is never surprised by a moved anchor.
<!-- end-source-section: 9.4 -->

<!-- source-section: 9.5 -->
<a id="sec-9-5"></a>

### 9.5 Unsupported mutation kinds `[FROM-B §8.3]`

Creating, deleting, renaming, binary changes, file modes, symlinks, case-only renames and generated artifacts need explicit operations and manifest coverage. Unsupported kinds are rejected with a named reason, never silently dropped.

---
<!-- end-source-section: 9.5 -->

