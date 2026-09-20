# Fixture `ts-small`

A tiny TypeScript package with a router and passing tests, run by Node's
built-in test runner with no install step. Materialized by
`FixtureRepos.materialize(Fixture.TsSmall)`.

## Runners (cwd = repository root)

| Purpose | Command |
|---|---|
| Canonical | `node --test` |
| Machine-readable, per-file attribution | `node --test --test-reporter=junit` |
| Syntax only, never a type check (D-09) | `node --check src/index.ts` |

`node --test` discovers `test/*.test.ts` and strips the type annotations
itself: type stripping is unflagged from Node 23.6, and `node --check` accepts
`.ts` the same way. Verified on Node v24.18.0. On an older Node the same
commands need `--experimental-strip-types`.

There are no dependencies and no `node_modules`; `npm install` must never run.

## What it is for

- P1.3 atlas/sniff: `package.json` declares the `test` and `check` scripts, so
  the commands are sniffable per manifest.
- P1.6.6 shaping parsers: `smoke` exists in `test/router.test.ts` **and**
  `test/index.test.ts` — same name, two files, two identities (D-27, IX-13).
  The default spec and tap reporters print the bare name for both; only
  `--test-reporter=junit` carries the `file` attribute that separates them,
  which is exactly the ambiguity D-50 says must never read as a match.
  `normalizeCurrency <code>` is the loop-generated parameterized set.
- `tsconfig.json` is there for a future real checker (`tsc --noEmit`); nothing
  in this plan runs it.
