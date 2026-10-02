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

The installed `tasks/<id>/` holds only `task.json`, `prompt.md` and `base/`. The hidden parts (`acceptance/`,
`reference/`, `wrong/`) ship as resources of `lib/eval-live-hidden.jar` (the build's `hiddenJar`: `evallive/hidden/index`
lists every file as `<task>/<part>/<path>`) and are read through the class loader, so no open file of them lies in the
bench. A task directory that has its hidden parts as directories (the source tree, `--tasks-dir`) is read from them
instead; parts are never mixed from both places.

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
variable. `eval-live tasks` lists the tasks; `eval-live check` verifies every task offline and prints each exit code:
the acceptance fails on the base, fails on the base with `wrong/` laid over it, passes on the base with `reference/`
laid over it, and the visible tests (`{python} -m unittest discover -s tests`) are green on the reference. It exits 1
when a task is unsound; `TaskValidityTest` asserts the same.

The agent's processes (`run`, `verify`, transforms) get no variable of the start scripts (`APP_HOME`, `CLASSPATH`,
`JAVA_OPTS`, `EVAL_LIVE_OPTS`): the core starts them with the platform essentials plus the attempt's
`redaction.envAllowlist` only (`EnvironmentTest`).

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
  (model requests and responses — every dispatched call ends in one `ModelResponded`, answered, failed or cancelled,
  with its reconciled usage — failed calls, cells, turns, tool calls, tokens uncached/cache read/cache write/output, cost from
  the profile's price table, span costs, provider models, stop reasons, any other numeric `ModelResponded` field as
  `responded.<path>` = `{sum, known, calls}` — the sum over the `known` of `calls` responses that reported it, partial
  when they differ — and `priceTiers`, the responses per price-tier threshold, `none` when a response named no tier),
  dropped events, changed files, and `interrupt` for a task that has one (below). Anything not observed is `null`, never 0.
- `events.jsonl` — every `EventRecord` of the run's bus, whole: fields the events gain later are kept without a change.
- `acceptance.log` — the acceptance output; `workspace.diff` — the agent's changes against the base commit.

`<out>/summary.json` (all results) and `<out>/summary.csv` (one row per run; an unknown value is an empty cell) are
rewritten after every run.

## Tasks

`tasks/<id>/`: `task.json` (`id`, `class`, `title`, `acceptance.argv` with `{python}`, `acceptance.timeoutSeconds`,
optional `interrupt`), `prompt.md` (the request), `base/` (the repository), and the hidden parts: `acceptance/` (run
from the copy's root as `_acceptance/`, its own copy of every check, never the workspace's tests), `reference/` (files
of a known-good solution laid over the base) and `wrong/` (files of a plausible but wrong solution laid over the base —
the typical mistake of the task's class; required: a task without it does not load). Python 3 standard library only,
no network, paths through `os.path`/`pathlib`, no `shell=True`; an agent needs minutes, the acceptance at most 120 s.

`interrupt` = `{"afterResponses": K, "constraint": "<text>"}`: after the K-th `cell.model_responded` of the attempt the
runner cancels it through its token (the Studio's stop, reason `stopped by the user`) and waits for its outcome; then,
as the Studio does with a message after a stop (`TaskService.message`: a cancelled run is not resumable, so it is a
follow-up), it starts a new run of the same task in the same workspace and state root, with the request
`TaskService.recap` (earlier request, outcome, files changed so far) + the constraint. An agent that ends before K
responses gets the same follow-up. `result.json` → `interrupt`: `afterResponses`, `constraint`, `mode`
(`cancelResume` | `followUp`, `null` when the first segment failed and no follow-up ran), `atResponse` (K when the
runner stopped it), `segments` (work id, outcome, stop code, reason, failure, cells, wall time and totals of each). The
run's own fields describe the whole: outcome and ids of the last segment, the sum of cells, wall time and totals of
both, every policy decision; `summary.csv` adds `interrupt_mode`.

| id | class | wrong/ |
|---|---|---|
| `bugfix-pagination` | bug with a reproducer | fixes the reproducer's case only: empty and exactly full listings get a page too many |
| `api-currency` | API change and its callers | changes the API, leaves the receipt on the old call |
| `rest-todo` | greenfield REST API with an external smoke test | `DELETE` of an unknown id answers 204, not 404 |
| `interrupt-csv` | interruption with a new user constraint (K = 3: `;` delimiter, `export_rows` unchanged) | comma-separated: the late constraint is lost |
| `env-launcher` | environment / launcher (`dev.py test` calls `python3` and always exits 0) | `python` instead of `python3`; the acceptance starts `dev.py` from a fresh virtual environment with a `PATH` holding no Python, so only `sys.executable` runs the environment's interpreter (on Windows a child named `python` is found beside the running interpreter whatever the `PATH`) |
| `red-test` | a visible test already red on the base (the same bug sits in the batch recount, which only the hidden acceptance covers; a changed `tests/test_reorder.py` is refused) | fixes the single-item path only |
| `ui-clear-done` | UI change with little build: a "Clear completed" button in a plain-JavaScript page plus `DELETE /api/todos?done=true` (API over HTTP, HTML through `html.parser`, `static/app.js` checked as text — no JavaScript engine) | the server removes every todo, not only the completed ones |
| `investigate-totals` | long investigation with a refuted first hypothesis (the request blames float rounding; the cause is an inclusive month end) | `Decimal` rounding, the month boundary still inclusive |
