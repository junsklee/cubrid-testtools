# SQL Tester — Usage & Internals

Builder-Tester can execute **SQL testcases** from
[CUBRID/cubrid-testcases](https://github.com/CUBRID/cubrid-testcases) in addition
to shell testcases. The Builder side (per-commit CUBRID build tarballs, per-commit
Docker test images) is shared with the shell path; execution is a dedicated
Docker-based SQL runner driven by **CTP latest `develop` with
[PR #757](https://github.com/CUBRID/cubrid-testtools/pull/757) merged on top**
(`bin/run_sql.sh`, single-case execution).

Design rationale and architecture diagrams: [SQL_TESTER_DESIGN.md](SQL_TESTER_DESIGN.md).

## Submitting an SQL request

```json
POST /build
{
  "commits": ["<sha>", "..."],
  "tests": ["sql/_01_object/_01_type/_004_integer/cases/1014.sql"],
  "testType": "sql",
  "buildType": "debug",
  "runMode": "fixed-runs", "minRuns": 1, "maxRuns": 1,
  "workerIp": "localhost",
  "callbackUrl": "http://reporthost:8091/callback"
}
```

- `testType` is optional: if every test path starts with `sql/`, the type is inferred.
- Test paths must match `sql/**/cases/<name>.sql` (a `/cases/` component and the
  `.sql` suffix are required; nesting depth between `sql/` and `cases/` varies in
  the repo and is not validated). The expected output must exist at the sibling
  `answers/<name>.answer` — a case without an answer file is reported as
  `execution_error`, not silently skipped.
- Optional `sqlTcBranch` selects the cubrid-testcases branch (default:
  `sql_tc_branch`, normally `develop`). The Builder resolves the exact testcase
  commit at admission and every tester pins a git worktree to it.
- Mixed shell+SQL requests are rejected; submit one request per type.
- `customShellScript` is not supported with `testType=sql`.

Sample client: `bin/test_client_sql.sh`.

## What runs where

1. **Builder** resolves, once per request:
   - the exact `cubrid-testcases` commit (`sqlTcCommit`),
   - the exact CTP provenance via `git ls-remote`: head of `ctp_sql_ref`
     (default `develop`) plus the head SHA of every PR in `ctp_sql_prs`
     (default `757`). These SHAs ride along in every `/test` request and in the
     callback (`ctpProvenance`), so all testers execute the identical CTP and
     the report shows exactly what ran.
2. **Tester** (per node):
   - `CtpProvisioner` keeps a cache under `<work_dir>/ctp_sql/`: a clone of
     `ctp_sql_repo`, plus payload directories `payload_<fingerprint>/CTP`
     containing `CTP/{bin,common,conf,sql}` (~16 MB, prebuilt jars) built by
     merging the PR heads onto the base SHA. Payloads are rebuilt only when
     upstream moves; a merge conflict fails the test with an actionable error
     (pin a compatible base with `ctp_sql_pin`).
   - `SqlTcSync` maintains `<work_dir>/sql_tc_requests/<requestId>/repo`, a git
     worktree of `sql_tc_dir` pinned to `sqlTcCommit`, mounted read-only at
     `/workspace/testcases`.
   - `SqlDockerExecutor` runs the case in the same per-commit image the shell
     path uses (`cubrid-test:<commit>_<baseline>`, CUBRID pre-installed at
     `/opt/cubrid`); CTP is mounted, not baked, so images stay cached when CTP
     moves.

Inside a container, the generated `sql_env_setup.sh` provisions the DB once —
replicating `CTP/sql/bin/run.sh`. All provisioning settings (`db_charset`,
`cubrid_createdb_opts`, `need_make_locale`, the `[sql/cubrid.conf]`,
`[sql/cubrid_ha.conf]` and broker sections) are read **at runtime from the CTP
payload's own `conf/sql.conf`** via CTP's `ini.sh` — so configuration defaults
always track latest develop, not a hardcoded snapshot; `sql_*` keys in
tester.conf act as explicit overrides only. The script then compiles locales,
runs `cubrid createdb`, loads Java stored procedures, starts server/broker/
javasp, and rewrites the `Function_Db/basic_qa.xml` dburl. Fixed ports/shm-ids
are safe because every container has its own network and IPC namespace. Each
case then runs via `CTP/bin/run_sql.sh <case>.sql basic` (PR #757). The verdict
is parsed from the console `[OK]`/`[NOK]` — never the exit code, which is
always 0.

Note: warnings like `The 'java_stored_procedure' parameter ... Deprecated
parameter` on CUBRID ≥11.5 come from upstream CTP's own `conf/sql.conf`
(`[sql/cubrid.conf] java_stored_procedure=yes`) and appear in plain
`ctp.sh sql` runs too; they are harmless and disappear automatically if
upstream updates its shipped configuration.

## Execution modes (`sql_exec_mode`)

- **`pool` (default)** — warm agent containers per (request, commit), capped at
  `sql_agents_per_commit`. DB provisioning (~30–60 s) is paid once per agent;
  cases execute via `docker exec` at seconds per case. Any non-pass outcome in a
  warm agent is **re-verified once in a fresh container**
  (`sql_fresh_verify_on_fail=true`), which eliminates cross-case-contamination
  false positives; the warm console is kept as a `warm_console` artifact.
  Agents are replaced after a core file, a wedged exec, or `sql_agent_max_cases`
  executions, and reaped after `sql_agent_idle_timeout_sec` idle.
- **`per_case`** — one container per case execution (maximal isolation,
  forensic runs). Retry attempts (`maxRuns > 1`) always use fresh containers in
  both modes so flaky classification is never polluted by warm-agent state.

## Results and artifacts

Statuses map as: `[OK]` → `pass`; confirmed `[NOK]` → `fail` (message notes a
core file if one was produced); missing answer file → `execution_error`;
per-case timeout (`sql_case_timeout_sec`) → `execution_error`; provisioning or
Docker failures → `environment_error`. Flaky detection (`runMode`/`minRuns`/
`maxRuns`) works exactly as for shell tests.

Per attempt, the tester ships (multipart) and the Builder stores under
`log/requests/<requestId>/tests/`:

| artifactType | file | when |
|---|---|---|
| (attempt log) | `sql_<commit7>_<testName>[.N].log` | always (container console + run_sql.sh output) |
| `answer_diff` | `sql_diff_<commit7>_<testName>[.N].diff` | on failure — unified diff expected vs actual |
| `actual_result` | `sql_actual_<commit7>_<testName>[.N].result` | on failure |
| `expected_answer` | `sql_expected_<commit7>_<testName>[.N].answer` | on failure |
| `case_source` | `sql_case_<commit7>_<testName>.sql` | first attempt |
| `warm_console` | `sql_warm_<commit7>_<testName>[.N].log` | warm-agent run overruled by fresh verify |
| `core_list` | `sql_core_<commit7>_<testName>[.N].txt` | crash detected |

`<testName>` is the dotted unique form of the case path (basenames like
`1014.sql` repeat across suites), e.g. `_01_object._01_type._004_integer.1014`.
The report server renders failed SQL cells with Diff / Actual / Expected /
Case SQL / Console tabs and shows the CTP provenance in the report header.

## Configuration reference

`conf/builder.conf`: `sql_tc_dir`, `sql_tc_branch`, `sql_tc_preferred_remote`,
`ctp_sql_repo`, `ctp_sql_ref`, `ctp_sql_prs`, `ctp_sql_pin`.

`conf/tester.conf`: all of the above plus `sql_tc_sync_mode`, `sql_exec_mode`,
`sql_agents_per_commit`, `sql_agent_idle_timeout_sec`, `sql_agent_max_cases`,
`sql_fresh_verify_on_fail`, `sql_case_timeout_sec`, `sql_db_name`,
`sql_db_charset`, `sql_createdb_opts`, `sql_load_stored_procedures`,
`sql_need_make_locale`, `sql_ha_mode`, `ctp_sql_payload_keep`.

Provisioning defaults are not baked in: they are resolved from the CTP
payload's shipped `conf/sql.conf` at container start, giving answer parity with
upstream daily regression even when upstream changes its defaults.

## Prerequisites per node

- A clone of `cubrid-testcases` at `sql_tc_dir` (builder **and** tester nodes).
- Docker (SQL tests have no direct-execution fallback).
- Network access to `ctp_sql_repo` for the CTP payload fetch (cached afterward).

## Current limitations

- `medium/`, `isolation/`, CCI-interface runs and answer-file generation are out
  of scope (see design doc §13 for extension paths).
- Charset-variant answers (`.answer_D_utf8_C_utf8_bin`, ...) follow CTP's
  selection under `test_default.xml`; running whole suites under a different
  charset needs a `sql_db_charset` change and is untested.
- Core callstack extraction is limited to listing core files (`core_list`).
- `keepAlive` is ignored for SQL tests.
