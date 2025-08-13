# Builder-Tester System

A concurrent build-and-test system for CUBRID. It builds multiple commits in parallel and runs shell tests in isolated Docker containers, returning pass/fail/error results and optional callbacks.

This README is a high-level overview. Detailed docs are in the docs/ directory:

- docs/features/README.md – Feature set and behavior
- docs/architecture/README.md – Components and flow
- docs/usage/README.md – Setup and usage, CLI and APIs
- docs/configuration/README.md – All configuration options and defaults
 - log/LOG_MANAGEMENT.md – Request-based logging design and operations

## Isolated per-commit builds

- Each target commit is built in isolation against a common baseline using a hermetic flow:
  - Baseline is computed as the parent of the earliest commit in your `commits[]` list
  - For each commit, one of the following is used:
    - Docker build (default): clone into a writable work dir inside the container, checkout a temporary branch at the baseline, cherry-pick only that one commit, sync submodules to gitlinks, clean, build, and package
    - Direct host fallback: create a temporary branch + `git worktree add` at the baseline, cherry-pick only that commit, sync submodules to gitlinks, clean, build, package, then remove the worktree and delete the temp branch
  - Merge commits are cherry-picked with `-m 1` (mainline 1) by default
  - This avoids cumulative history and keeps builds hermetic

## Quick start

Prerequisites:
- Java 8+
- Docker
- CUBRID source checkout (or network access for Builder to clone)
- Shell testcases repository
- Environment variable `GITHUB_TOKEN` (required for repository access)

Build and run:
```bash
cd CTP/builder_tester
./bin/compile.sh

# In a shell on the test machine
export GITHUB_TOKEN=your_github_token
./bin/start_tester.sh

# In a shell where Builder runs
export GITHUB_TOKEN=your_github_token
./bin/start_builder.sh
```

Send a build request:
```bash
./bin/test_client.sh
```

Or via curl:
```bash
curl -X POST http://localhost:8089/build \
  -H "Content-Type: application/json" \
  -d '{
    "commits": ["6ea587e"],
    "tests": ["shell/_01_utility/_38_csql/csql_hist/cases/csql_hist.sh"],
    "callbackUrl": "http://your-server/callback",
    "workerIp": "localhost",
    "buildType": "debug"
  }'
```

Check status and health:
```bash
curl http://localhost:8089/status
curl http://localhost:8090/health
```

## APIs

### Builder (port 8089)
- POST `/build` – Accepts `{ commits[], tests[], callbackUrl, workerIp, buildType }`, responds with `{ status, taskId }`
- GET `/status` – Returns running task(s)
- GET `/health`
- GET `/report` – View test result reports (lists all reports)
- GET `/report?id=req_xxx` – View specific test report
- POST `/callback` – Receive test results and generate interactive HTML report

Concurrency:
- Builds: limited by `max_concurrent_builds`
- Tests: limited per built artifact by `max_concurrent_tests`

### Tester (port 8090)
- POST `/test` – Accepts `{ buildPackage, testPath, testDir, testScript, testName, expectedBuildVersion, keepAlive? }`
  - If `keepAlive=true` (or configured to keep failed containers): returns `{ status: "started", containerName, execCommand, workspace }`
  - If `keepAlive=false`: runs synchronously and returns `{ status: "pass|fail|execution_error", test, execution_mode: "docker|direct" }`
- GET `/health`

Notes:
- Test result is detected from the test script base name: for `csql_hist.sh`, the tester expects `csql_hist.result`.
- Tester mounts the testcase repository into the container read-write so tests can produce result artifacts.

## Test Result Visualization

The Builder-Tester system includes an interactive web-based report viewer for analyzing test results:

### Features
- **Interactive Reports**: View test results grouped by test case with pass/fail status for each commit
- **Automated Verdict Analysis**: Automatically determines failure patterns:
  - `Pass: Not reproduced` - No failures across commits
  - `Bug or Revise: Caused by <commit>` - Single commit failure
  - `Pre-existing Failure` - Failures across all commits
  - `Unstable: Fails intermittently` - Partial failures
- **Statistics Dashboard**: Visual KPIs showing pass rate, failed tests, and unstable tests
- **Export Options**: Download results as JSON or CSV

### Using the Report Viewer

#### Option 1: Integrated with Builder (Recommended)
The report viewer is automatically available when running the Builder:
```bash
# View all reports
curl http://localhost:8089/report

# Use callback in your request
curl -X POST http://localhost:8089/build \
  -d '{"commits": [...], "tests": [...], "callbackUrl": "http://localhost:8089/callback"}'
```

#### Option 2: Standalone Node.js Server
For independent operation or custom ports:
```bash
cd report-server
node report-server.js 8091

# Send results to the standalone server
curl -X POST http://localhost:8091/callback -d @results.json
```

### Report Storage
Reports are saved in `~/cubrid-testtools/CTP/builder_tester/log/requests/req_*/`:
- `results.json` - Raw test data
- `report.html` - Self-contained interactive HTML report

## Logs
- System logs: `~/cubrid-testtools/CTP/builder_tester/log/system/{builder.log,tester.log}`
- Request-scoped logs and artifacts: `~/cubrid-testtools/CTP/builder_tester/log/requests/req_*/{builder.log,builds/,tests/}`
- Legacy logs from prior versions: `~/cubrid-testtools/CTP/builder_tester/log/system/legacy_*`
- Optional service stdout/stderr (when using start scripts): `bin/builder_output.log`, `bin/tester_output.log`

Retention and grouping are configurable via `conf/*.conf`:
- `max_request_logs` (keep last N request directories)
- `max_tar_files` (builder: keep last N tar files in work dir)
- `enable_request_grouping` (toggle request-based log grouping)

## Project structure
```
builder_tester/
├── src/com/navercorp/cubridqa/builder/
│   ├── Builder.java
│   ├── BuilderTask.java
│   ├── BuilderConfig.java
│   ├── Tester.java
│   ├── DockerBuildManager.java
│   ├── DockerTesterManager.java
│   ├── DockerUtils.java
│   └── report/
│       ├── ReportHandler.java     # Callback handler and report generator
│       └── ReportTemplate.java    # HTML/JS report template
├── conf/
│   ├── builder.conf
│   └── tester.conf
├── bin/
│   ├── compile.sh
│   ├── start_builder.sh | stop_builder.sh | run_builder.sh
│   ├── start_tester.sh  | stop_tester.sh  | run_tester.sh
│   ├── test_client.sh
│   └── test_report_client.sh     # Test client with callback example
├── lib/
│   └── json.jar
├── report-server/                 # Standalone Node.js report server
│   ├── report-server.js
│   ├── package.json
│   └── README.md
└── docs/
    ├── features/README.md
    ├── architecture/README.md
    ├── usage/README.md
    └── configuration/README.md
```

## Troubleshooting (short)
- Docker: verify `docker ps` works and user has permission.
- Token: ensure `GITHUB_TOKEN` is set in the service environment.
- Builds: inspect `log/builds/*` and `bin/builder_output.log`.
- Tests: inspect `log/tests/*` and `bin/tester_output.log`.
