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
| `--arm <name>` | `default` | the arm: a named configuration over the core's `RunSpec` (below) |
| `--mode auto\|ask` | `auto` | the Studio task mode: `auto` accepts unverified work by policy; `ask` leaves an acceptance request open, the scripted user accepts it (by its `key`) and the run reopens the same work, at most 3 answers, else `ask.outcome` = `ask-exhausted` (core arms only) |

Keys are resolved by the SDK: the provider's environment variable (`OPENROUTER_API_KEY`) or the credential store given
with `--credentials`. The runner never reads or prints a key; without one it stops before the first run and names the
variable. `eval-live tasks` lists the tasks; `eval-live check` verifies every task offline and prints each exit code:
the acceptance fails on the base, fails on the base with `wrong/` laid over it, passes on the base with `reference/`
laid over it, and the visible tests (`{python} -m unittest discover -s tests`) are green on the reference. It exits 1
when a task is unsound; `TaskValidityTest` asserts the same.

The agent's processes (`run`, `verify`, transforms) get no variable of the start scripts (`APP_HOME`, `CLASSPATH`,
`JAVA_OPTS`, `EVAL_LIVE_OPTS`): the core starts them with the platform essentials plus the attempt's
`redaction.envAllowlist` only (`EnvironmentTest`).

## Arms

An arm is a named configuration of one bench, not a branch (plan §6 B5): what runs the task — the core or the loop —
and the core's protocol, shape forcing and model table over `RunSpec.defaults`. Arms are compared on the same tasks,
models, acceptance, limits and accounting; one results directory can hold several arms side by side.

| arm | runs | state |
|---|---|---|
| `default` | the core, structured protocol, the Studio's default launch (`RunSpec.defaults`) | runs |
| `loop` | the reference loop below, over `provider-api` only | runs |
| `direct` | the core with the direct protocol | refused until the core has it (D1) |

An arm that sets a field the core cannot honour yet — the direct protocol (D1), a forced shape (H2), a model table (H3),
or any core field on the loop — is refused with an error naming the field, never run as if it were not set.

### The loop arm: rules of a fair comparison

`loop` is the yardstick for every axis (plan §9.1), not a product interface: one transcript, four tools — `read`,
`edit`, `write`, `shell` — and a model that calls them until it answers without a tool call. It shares everything with
the core arm except the agent's own machinery. These rules were written before the code and bind it:

1. **Inputs.** The request is `prompt.md` verbatim as the first user message. The system text is the loop's tool
   description plus the host facts the default arm gets: `StudioPolicy.platform` and `StudioPolicy.verificationText` of
   the same verification setup (the declared test command, else the review setup with its declared hints). The core's
   working notes describe its own state and task tools and are not given. Workspace, base commit and follow-ups
   (interruption recap + constraint, a message mid-run, a second session) are the bench's, as for the core.
2. **Permissions.** Every shell line is classified by the core's `EffectPolicy.classify` with the core's default
   `EffectPolicyConfig`, the default contract scope's protected paths and a containment probe over the real disk, as
   the core's `run` tool gets them (a delete inside the workspace is W, one outside D); a D-class line is refused, as
   the Studio's `auto` mode refuses a D-class effect no contract allowlists, and recorded as the policy decision
   `effect skipped`. File tools resolve through the core's `WorkspacePath`: never outside the workspace (`..` or a
   link), never `.git` in any case, never a write through a link or into a protected path. Processes get the platform
   essentials plus `redaction.envAllowlist`, as the core's do.
3. **External budget.** The task limits of `RunSpec.defaults` (money, minutes, requests) are checked before every model
   call by the core's own `LimitRule.decide` over the work's spend summed by the core's `LimitSpend.of` — each call's
   money chosen exactly as `Totals` chooses it (the provider's bill, else the usage at the profile's table, paid or
   nominal; an unpriced profile counts no money), else the conservative hold of the core's rule (every input token at
   the dearest input rate plus the full output headroom). A second session of the same work continues its spend. `Exhausted`
   stops before dispatch (outcome `budget_exhausted`, stop code `task_limit_money|minutes|requests`); the first `Reserve`
   of a kind tells the model to check its work and finish. Minutes are active time on the injected clock. The same
   `--deadline-minutes` applies. The cell cap does not: the loop has no cells.
4. **Effort and output.** `RunSpec.effort` and `RunSpec.outputHeadroom(profile)` on every request; the same profile,
   adapter and estimator.
5. **Acceptance.** The same hidden acceptance on a copy of the finished workspace.
6. **Cache points.** The core's layout rule: the system region `[S]` and the transcript `[T]` are each closed by a
   breakpoint when the profile has explicit breakpoints (`caching.breakpoints`), and carry none otherwise; every request
   carries the work's `sessionKey`. The transcript only grows, so its prefix stays cacheable.
7. **Tool output.** A `read` result is cut to `Defaults.lookBudgetTokens`, a `shell` result to `runBudgetTokens`, at the
   core shaper's 3.6 characters per token, head and tail kept; a shell line runs at most `runTimeoutSeconds`.
8. **Retries and their accounting.** Transport retries are the adapter's — the same AI Gate adapter in both arms. A call
   that fails with a transport error, a rate limit or a timeout is sent again at most twice. Every dispatched call —
   answered, failed or cancelled — is a request and ends in one `ModelResponded` with its reconciled usage (`terminal()`,
   bounded by `providerTerminalWaitSeconds`), so both arms are counted by the same `Totals`. Another provider error
   ends the attempt `failed`; refused credentials end it `blocked_external`. Known limit: retries inside the SDK are not
   visible in N for either arm, while the loop's own repeats are separate calls.
9. **Window overflow.** The loop never compacts or summarises. When the next request does not fit — the adapter's
   validation or the provider's `ContextOverflow` — the content of every tool result but the last four is replaced by a
   stub, once; if the request still does not fit, the attempt ends `failed` ("context window full").
10. **Final check.** When the model answers without a tool call, the loop runs the verification setup's commands (those
    the default arm's contract accepts against). A failing one goes back to the model with its output and the loop
    continues, at most twice; then the attempt ends `completed` either way, and the hidden acceptance judges. A review
    setup (no test command) runs nothing.
11. **Events.** The loop emits what the core emits for model work — `cell.turn_started`, `cell.model_requested`,
    `cell.model_responded`, `cell.tool_called` — on the run's bus, so `events.jsonl` and `Totals` come from the same code
    for both arms. It starts no cell (`cellsStarted` 0, `cells` `null`) and has no contract (a message has no contract
    version; a second session starts a fresh transcript on the same workspace).

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

`<out>/runs/<arm>/<task>/<model>/r<n>/`:

- `result.json` — arm, task, model, repetition, seeded order, attempt outcome, stop code and reason, failure, cells,
  policy decisions, acceptance (passed, exit code, timeout, output tail) and its digest, attempt wall time, totals
  (model requests and responses — every dispatched call ends in one `ModelResponded`, answered, failed or cancelled,
  with its reconciled usage — failed calls, cells, turns, tool calls, tokens uncached/cache read/cache write/output, cost
  with each call priced by the table of the profile its `ModelRequested` named, `costBasis` (`paid`, `nominal`,
  `unpriced`, `unknown`, `mixed`) and `profiles` (responses per profile), span costs, provider models, stop reasons, any other numeric `ModelResponded` field as
  `responded.<path>` = `{sum, known, calls}` — the sum over the `known` of `calls` responses that reported it, partial
  when they differ — and `priceTiers`, the responses per price-tier threshold, `none` when a response named no tier),
  dropped events, changed files, and `interrupt` for a task that has one (below). Anything not observed is `null`, never 0.
  `key` is what the result stands for: the arm and the SHA-256 fingerprints of the configuration (the arm's fields,
  provider, model, effort, cell cap, deadline and the core's launch constants), the code (the classes or jars of
  `eval-live`, the core, `provider-api`, the AI Gate adapter and the SDK) and the task (request, task file fields, base
  tree, hidden acceptance). A later bench keeps a result only for the same key; any other — or none — is moved aside to
  `r<n>.stale-<k>` and the run is made again.
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

`dirt` = `{"dir": "devtools", "files": 1500, "bigMegabytes": 20}` (optional, WP-W0): after the base commit the runner adds an
untracked directory of `files` small files, one of them `bigMegabytes` MB. `dir` is a relative name (under the workspace) or,
T-14, an absolute path — `C:\...`, `\\server\share\...`, `/...`: the tree is then written there, outside the repository,
so it is not dirt of the tree; the runner removes what it wrote when the run ends, and never stops for dirt it cannot write
(an unreachable share, a path of the other system, a directory that is already there is left as it is). `result.json` →
`dirt`: `dir`, `inTree`, `written`, `reason` (`null` for a task without dirt). A start opens the campaign once (T-12): the
verification setup's notes and declared checks go in with the first open, as the Studio's launch does; a second open only
when the core refused the plan or found other notes than expected.

`reopen` = `{"afterResponses": K}`: the session is closed after K responses and the same work reopens in the same state
root (`result.json` → `reopen`), as the Studio's resume does.

`after` = `"<id>"` (plan §9.2, pairs — "second task in the same project"): the task is the second of a pair whose first
task is `<id>`. The runner copies the first task's base, runs the first task as a work of its own (auto mode, no
scripted interrupt, reopen, message or dirt on either task), runs the first task's acceptance on what it left, commits
that as `after <id>` and then runs the second task in the same working directory and the same state root. Only the
second is measured: outcome, cells, policy decisions, wall time, totals, phases, `workspace.diff` and `changedFiles`
are its own (the diff is taken against the commit the first left); `result.json` → `pair`: `after`, `firstAcceptance`
and `first` (the first task's segment with its totals). The second task's own `base/` is the first task's base with the
first task's `reference/` laid over it — it serves the validity check only (`TaskValidityTest` checks that it matches). A
first task that fails as a harness run stops the pair with a `failure`; in D5 a pair counts as an ordinary task.

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
| `port-framework` | port a small REST service (`library/`, 8 endpoints, token check, request ids) from the in-house `http.server` micro-framework (`tinyhttp/`) to the ASGI-style one in the repository (`miniasgi/`), wire behaviour unchanged; 31 files, 3 packages; the acceptance drives the bare ASGI protocol and checks that the old layer is not imported (debugging set) | the happy paths ported, the implicit behaviour of the old framework lost: error answers in the new framework's `{"detail": …}` shape, request id missing on the 401, invalid JSON a 500, repeated `tag` parameters collapsed to one |
| `large-tree` | the bulky-parcel line moves from 100 to 120 cm in a tree of 52 files and 7 packages; the places are found by following references, not names from the request (one named constant, two re-derived literals, a JSON data file), and two look-alike limits (the postal contract, customs) must stay (debugging set) | the named constant and one literal changed, the label note and the weekly report's config left on 100 cm |
| `two-sessions` | `reopen` after 3 responses: the user's own day instead of the UTC date in six places of a scheduling library, then quiet hours (`next_send_time`) built on the same clock; the acceptance judges both parts of the final tree (debugging set) | three of the six places fixed, quiet hours computed on the UTC clock |

**Closed confirmation set (B6b, for D5 and H7) — not for debugging or threshold tuning.** These tasks are run only by
the confirmation benches; never use them to debug the harness, to tune a policy or a threshold, or as examples.

| id | class | wrong/ |
|---|---|---|
| `scenario-1004` | the 4 October scenario: a notes app (`notes/` API on `http.server`, `notesclient/` client and command line; 19 files, 2 packages) in a repository **without a commit** (`baseCommit: false`) gets an HTML view rendered by `marklet` 1.2.0, a wheel in `vendor/` to install offline into `.venv` and declare in `requirements.txt`; a `message` after 4 responses asks to show raw HTML as text (the acceptance puts the vendored wheel first on `sys.path` and checks it is unchanged) | the page is right but raw HTML in the body passes through: the mid-run message is lost |
| `api-callers` | API change and its callers in 5 packages (21 files): `Inventory.reserve` returns a `Reservation` tied to an order (`order_id` keyword-only, `OutOfStock` instead of `False`), `release`/`ship` take it; checkout, cancel, fulfilment, the shop's 409 and the operator desk follow | every caller converted, but a short order's earlier reservations are not given back: rejected orders leak stock |
| `merge-conflict` | two independent subtasks in one request (CSV and JSON export) that both edit the one registry file (`spend/export/registry.py`; 17 files, 3 packages); the acceptance checks both formats, that nothing was lost and that nothing is there twice (dict keys, definitions, `spend formats`) | both modules written, the JSON entry registered twice and the CSV entry lost — a bad merge |
| `node-api` | Node monorepo (built-ins only, no `npm install`, `node --test`; 18 files, 3 packages): `quote(items, { region, coupon })` returns a breakdown in cents and `formatMoney` takes cents; the API (`node:http`) and the command line follow. The acceptance is Python that runs `node` found by `shutil.which` (it fails with the reason when Node is absent) | the API drops the coupon: the caller does not pass the new option |
| `second-task-pairs` | second task of a pair (`after: api-callers`): reservations become visible — `Inventory.reservations`, `GET /orders/<id>/reservations`, the desk's `holds` and a morning-report line that counts only the desk | the desk's holds and the report line count every reservation, not only the desk's |
