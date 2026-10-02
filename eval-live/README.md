# eval-live — the headless live runner (ASTROLABE 2.0, A0)

Runs bench tasks through the product attempt on a live provider, one fresh temporary workspace per run, and judges
each run with a hidden acceptance the agent never sees. It is a CLI process from an installed distribution: nothing
waits on a model for the result. The module exists only where the AI Gate SDK checkout is present (like
`provider-ai-gate`, D-332).

## Build and install

```bash
export JAVA_HOME=/c/Users/user/.gradle/jdks/eclipse_adoptium-26-amd64-windows.2
./gradlew :eval-live:installDist -Pastrolabe.aiGateBuild=C:/work.astrolab/llm-transport-sdk/llm -PbenchDir=C:/work.astrolab/bench/<tag>
```

Without `-PbenchDir` the distribution lands in `eval-live/build/install/eval-live/` and can be copied anywhere. With it,
`bin/`, `lib/` and `tasks/` are replaced and everything else in the directory (results) stays. Running needs JDK 26
(`JAVA_HOME` or `java` on the `PATH`), `git` and Python 3 on the `PATH`.

## Run

```bash
C:/work.astrolab/bench/<tag>/bin/eval-live run --models deepseek/deepseek-v4.1-flash --out C:/work.astrolab/bench/<tag>/results \
  --tasks all --repeats 1 --seed 1
```

| Option | Default | Meaning |
|---|---|---|
| `--models a,b` | required | model ids of the provider |
| `--out <dir>` | required | results directory; runs already there are kept (a stopped bench resumes) |
| `--tasks a,b` | `all` | task ids under the tasks directory |
| `--provider` | `openrouter` | AI Gate provider id |
| `--repeats` | 1 | repetitions of every task × model |
| `--seed` | 0 | seed of the run order (task × model × repetition, shuffled) |
| `--effort` | `Medium` | `Low`, `Medium`, `High` (the Studio's effort) |
| `--max-cells` | 12 | the Studio's cell limit |
| `--deadline-minutes` | 60 | an attempt still running then is cancelled through its token |
| `--catalog <file>` | `<out>/catalog-snapshot.json` | SDK model catalog snapshot (read and kept up to date) |
| `--credentials <file>` | none | SDK credential store file (`CredentialStore.file`) |
| `--python <program>` | detected | interpreter for `{python}` in acceptance commands |
| `--temp <dir>` | `<java.io.tmpdir>/eval-live` | parent of the per-run directories |
| `--keep-workspaces` | off | keep each run's temporary directory for inspection |

Keys are resolved by the SDK: the provider's environment variable (`OPENROUTER_API_KEY`) or the credential store given
with `--credentials`. The runner never reads or prints a key; without one it stops before the first run and names the
variable. `eval-live tasks` lists the tasks; `eval-live check` verifies every task offline (acceptance fails on the
base, passes on the reference, visible tests green on the reference).

## What a run is

1. A fresh `run-*` directory under `--temp`: `workspace/` (the task's `base/`, `git init` and committed as `base`) and
   `state/` (the core's state root, outside the workspace).
2. The attempt as a Studio task in `auto` mode runs it (`StudioPolicy`, copied from ASTROUI: one profile for every
   function, `maxCells` 12, twelve context windows of budget, output headroom a quarter of the window, lease 480 min,
   verification setup and host notes, OpenRouter upstream ignores, the auto host policy) through `Controller`.
3. The hidden acceptance runs in a separate copy of the finished workspace with the task's `acceptance/` laid in as
   `_acceptance/` — files read once when the task was loaded, so a change on disk during the bench never reaches a run.
   The run directory and the copy are removed afterwards.

## Results

`<out>/runs/<task>/<model>/r<n>/`:

- `result.json` — task, model, repetition, seeded order, attempt outcome, stop code and reason, failure, cells,
  policy decisions, acceptance (passed, exit code, timeout, output tail) and its digest, attempt wall time, totals
  (model requests and responses, cells, turns, tool calls, tokens uncached/cache read/cache write/output, cost from
  the profile's price table, span costs, provider models, stop reasons, sums of any other numeric `ModelResponded`
  field), dropped events, changed files. Anything not observed is `null`, never 0.
- `events.jsonl` — every `EventRecord` of the run's bus, whole: fields the events gain later are kept without a change.
- `acceptance.log` — the acceptance output; `workspace.diff` — the agent's changes against the base commit.

`<out>/summary.json` (all results) and `<out>/summary.csv` (one row per run; an unknown value is an empty cell) are
rewritten after every run.

## Tasks

`tasks/<id>/`: `task.json` (`id`, `class`, `title`, `acceptance.argv` with `{python}`, `acceptance.timeoutSeconds`),
`prompt.md` (the request), `base/` (the repository), `acceptance/` (hidden), `reference/` (files of a known-good
solution laid over the base). v0: `bugfix-pagination` (bug with a reproducer), `rest-todo` (greenfield REST API with
an external smoke test), `api-currency` (API change and its callers) — Python 3 standard library only.
