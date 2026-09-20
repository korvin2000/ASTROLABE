# Fixture `python-failing`

`python-small` with one test that is red before the harness starts and one that
is skipped. Materialized by `FixtureRepos.materialize(Fixture.PythonFailing)`.

## Runners (cwd = repository root)

| Purpose | Command |
|---|---|
| Canonical (pytest, when installed) | `python -m pytest -q` |
| Stdlib fallback, always available | `python -m unittest discover -s tests -v` |

Both exit non-zero and name `test_pre_existing_failure`; the suite is otherwise
identical to `python-small`, so counts differ by exactly one failure and one
skip.

## What it is for

- P1.7.5 pre-existing ledger: `HandlersTest.test_pre_existing_failure` gives a
  stable identity + failure signature to record at `s0`; later red matching it
  reads `pre-existing (unchanged)` instead of steering, and
  `test_skipped_until_fixed` gives a skip that must never count as green.
- FX-08 (a failing suite wrapped in an exit-0 shell command) needs a suite that
  genuinely fails; this is it. FX-09 (`pytest -k nonexistent`, exit 5 ⇒
  `inconclusive`) needs the same repository *and* an installed pytest, which
  this plan's hosts do not guarantee — `Runners.pytest()` says whether it is
  there.
- `test_smoke` in two modules and the parameterized cases of
  `tests/test_router.py` are unchanged from `python-small` (D-27, IX-13).
