# Core implementation notes

## Symbolic write scopes (P5.1.5)

`workspace.ScopeAlgebra` compares `contract.Scope` records in one integration destination namespace:

```kotlin
val overlap = ScopeAlgebra.intersection(destination, leftScope, rightScope, limits)
val outside = ScopeAlgebra.difference(destination, childScope, parentScope, limits)
```

`ScopeSearchLimits` explicitly bounds NFA states, reached product states and explored transitions.
The returned `ScopeAnalysis` retains immutable scope snapshots, namespace, relation, limits and
actual counts. For intersection, `Disjoint` proves no common lexical path; `Overlap.witnessPath`
is a shortest common path. For difference, `Disjoint` proves lexical containment and a witness names
a child path outside the parent. `Unknown` identifies resource exhaustion or unsupported input.

The matcher preserves `PathPattern` semantics: bare filenames match at any depth, slashed literals
and directory prefixes include descendants, single stars stay within a segment, double stars span
directories. Bare `**` includes line terminators while regex double stars do not. Unicode is matched
as scalar values without normalization; malformed surrogate input is Unknown. Search uses a finite
character-class partition and complete iterative automaton traversal, not sampled paths.

Witness ordering is shortest code-point length, then `a..z`, `0..9`, `_`, `-`, `.`, `/`, then other
scalar values ascending. Canonical lexical paths exclude empty/dot/dot-dot segments, NUL/backslash,
absolute drive prefixes and wholly blank paths. A witness can name a future path or an OS-invalid
filename: this language is a portable lexical superset, not filesystem authorization. Real paths,
case aliases, symlinks and race checks remain with `WorkspacePath`/`ScopeGuard`. Different child
workspace IDs never prove non-conflict at the common destination. S3 remains gated/off.

The full grammar, proof outline, complexity, limits and independent oracle evidence are in the
[P5.1.5 protocol](../audit/OUT-OF-ORDER-P5.1.5.md). Run the focused `ScopeAlgebraTest` and `ScopeOracleTest`
with `:core:test`; public API changes require separate `:core:updateKotlinAbi` before the full build.
