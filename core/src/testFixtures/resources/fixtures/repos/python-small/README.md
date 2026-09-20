# Fixture `python-small`

A tiny Python package with a router, one handler module and passing tests.
Materialized by `FixtureRepos.materialize(Fixture.PythonSmall)`, which copies
these files into a fresh `TempRepo` and commits them as `initial`.

## Runners (cwd = repository root)

| Purpose | Command |
|---|---|
| Canonical (pytest, when installed) | `python -m pytest -q` |
| Stdlib fallback, always available | `python -m unittest discover -s tests -v` |

Both forms rely on `python -m` putting the repository root on `sys.path`, so
`import pay` resolves without an install. `python` is `python3` or `py` on hosts
where that name resolves first (`Runners.python()` probes in that order).

There are no third-party dependencies and nothing to install: tests are
`unittest.TestCase` classes, which pytest also collects.

## What it is for

- P1.3 atlas/sniff: `pyproject.toml` declares `[tool.pytest.ini_options]`
  with `testpaths`, so the test command is sniffable per manifest.
- P1.6.6 shaping parsers: `test_smoke` exists in `tests/test_router.py` **and**
  `tests/test_handlers.py` — same name, two modules, two identities (D-27,
  IX-13). `NormalizeCurrencyTest` carries both parameterization shapes: one
  `subTest` loop over three values, and three generated
  `test_normalize_currency_<code>` methods.
- P1.12.2 vertical slice: `pay.router.dispatch` calls
  `pay.handlers.user.handle_user(req)` with the request only. Adding a context
  parameter is a cross-file edit; changing one side alone turns the suite red.
