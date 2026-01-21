# Builder-Tester System

A concurrent build-and-test system for CUBRID. It builds multiple commits in parallel and runs shell tests in isolated Docker containers, returning pass/fail/error results and optional callbacks.

This README is a high-level overview. Detailed docs are in the docs/ directory:

- docs/features/README.md – Feature set and behavior
- docs/architecture/README.md – Components and flow
- docs/usage/README.md – Setup and usage, CLI and APIs
- docs/configuration/README.md – All configuration options and defaults
- docs/DOCKER_OPTIMIZATION.md – Docker performance optimization (80-90% faster)
- log/LOG_MANAGEMENT.md – Request-based logging design and operations

## Isolated per-commit builds

- Each target commit is built in isolation against a common baseline using a hermetic flow:
  - Baseline is computed as the parent of the earliest commit in your `commits[]` list
  - **The baseline commit itself is automatically excluded from build targets** (it would result in baseline version, not baseline+1)
  - For each remaining commit, the build process ensures a clean baseline state:
    - Docker build (default): clone into a writable work dir inside the container, checkout a temporary branch at the baseline, reset hard to baseline, cherry-pick only that one commit, sync submodules to gitlinks, clean, build, and package
    - Direct host fallback: create a temporary branch + `git worktree add` at the baseline, reset hard to baseline, cherry-pick only that commit, sync submodules to gitlinks, clean, build, package, then remove the worktree and delete the temp branch
  - Each build starts from a pristine baseline state to ensure consistent version numbering (baseline + 1)
  - Merge commits are cherry-picked with `-m 1` (mainline 1) by default
  - This avoids cumulative history and keeps builds hermetic

## Build Performance Features

- **Ccache Support**: Compiler cache for 5-10x faster rebuilds
  - Persistent cache across builds
  - Automatic setup and management
  - Works in both Docker and direct build modes
  - See docs/CCACHE_GUIDE.md for details
- **Parallel Compilation**: Auto-detects CPU cores for optimal parallelization
- **Build Artifact Caching**: Reuses previous builds when possible
- **Lean Build Packages**: Tarballs include only `_install/CUBRID`, shrinking transfer and extraction time while preserving legacy fallbacks
- **Shell Testcase Sync Throttling**: Configurable `shell_tc_sync_interval_seconds` prevents redundant git fetches during heavy retry cycles
- **Docker Image Optimization**: Pre-built images with CUBRID installed
  - 80-90% reduction in test execution overhead
  - Automatic image caching per commit
  - Transparent fallback on failures
  - See docs/DOCKER_OPTIMIZATION.md for details

## Smart Scheduling Features

- **Smart Scheduling System**: Intelligent multi-resource-aware test distribution
  - Replaces round-robin with filter→score→bind algorithm
  - Per-node test history and resource demand prediction
  - Cache locality optimization (Docker images, build packages)
  - Mice/elephants queue separation for optimal throughput
  - See docs/SMART_SCHEDULING_ARCHITECTURE.md for details
- **I/O-First Scheduling**: Prioritizes I/O capacity in scheduling decisions
  - Separate read/write bandwidth tracking
  - I/O-dominant scoring with configurable weights
  - Prevents disk saturation on I/O-bound tests
- **Heavy Test Scheduling**: Automatic classification and isolation of resource-intensive tests
  - NORMAL/HEAVY/EXTREME classification based on historical usage
  - Heavy tests route to elephant queue for early scheduling
  - Soft penalty spreads heavy tests across nodes
  - Effective capacity reporting with overcommit factors
  - Circuit breaker protection against memory overload
  - See docs/SMART_SCHEDULING_CONFIG.md for configuration

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

# Optional: Set up ccache for faster rebuilds
./bin/manage_ccache.sh install
./bin/manage_ccache.sh setup

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

Notes:
- `tests[]` entries must be shell testcase paths starting with one of: `shell/`, `shell_heavy/`, `shell_perf/` (and ending with `.sh`).

Check status and health:
```bash
curl http://localhost:8089/status
curl http://localhost:8090/health
```

## APIs

### Builder (port 8089)
- POST `/build` – Accepts `{ commits[], tests[], callbackUrl, workerIp, buildType, runMode?, minRuns?, maxRuns? }`, responds with `{ status, taskId }`
- GET `/status` – Returns running task(s)
- GET `/health`
- GET `/report` – View test result reports (lists all reports)
- GET `/report?id=req_xxx` – View specific test report
- POST `/callback` – Receive test results and generate interactive HTML report

#### New Build Request Parameters
- `runMode`: Test execution mode - "until-pass", "until-fail", or "fixed-runs" (optional)
- `minRuns`: Minimum number of test attempts (optional, default from config)
- `maxRuns`: Maximum number of test attempts (optional, default from config)

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

### Health endpoints

#### Builder `/health` (default port 8089)

```bash
curl http://localhost:8089/health
```

Example response:

```json
{
  "status": "healthy",
  "service": "Builder",
  "timestamp": 1755143569000,
  "activeTasks": 0,
  "workDir": "/tmp/builder_work",
  "dockerEnabled": true,
  "maxConcurrentBuilds": 4
}
```

- **status**: health state of the service
- **service**: service identifier
- **timestamp**: server time in milliseconds
- **activeTasks**: number of running build tasks
- **workDir**: Builder working directory
- **dockerEnabled**: whether Docker is used for builds
- **maxConcurrentBuilds**: concurrency limit for builds

#### Tester `/health` (default port 8090)

```bash
curl http://localhost:8090/health
```

Example response:

```json
{
  "service": "Tester",
  "dockerEnabled": true,
  "workDir": "/tmp/tester_work",
  "testReadTimeoutMinutes": 60,
  "maxConcurrentTests": 6,
  "status": "healthy",
  "timestamp": 1755143569766
}
```

- **status**: health state of the service
- **service**: service identifier
- **timestamp**: server time in milliseconds
- **workDir**: Tester working directory
- **dockerEnabled**: whether Docker is used for test execution
- **maxConcurrentTests**: concurrency limit for tests (from `tester.conf`)
- **testReadTimeoutMinutes**: Builder→Tester HTTP read timeout (minutes)

#### Report Server `/health` (default port 8091)

If using the standalone Node.js report server:

```bash
curl http://localhost:8091/health
```

Example response:

```json
{ "status": "healthy", "service": "report-server" }
```

- **status**: health state of the service
- **service**: service identifier

### Status endpoint (Builder)

The Builder exposes `/status` to query running tasks and per-commit progress. The `taskId` equals the generated request ID (e.g., `req_YYYYMMDD_HHMMSS_XXXX`).

List all active tasks:

```bash
curl "http://localhost:8089/status"
```

Example response:

```json
{
  "activeTasks": [
    {
      "taskId": "req_20250814_123034_6077",
      "progress": {
        "1609a3a41c5b73492cf5b716ced19196bd428494": 100,
        "8dae125ebffa6cd333cd55406e0a0439b6f2b82d": 100,
        "dd64b39dbf6914a739636e80c66a5c25cb2daa46": 100
      }
    }
  ]
}
```

Query a specific task:

```bash
curl "http://localhost:8089/status?taskId=req_20250814_123034_6077"
```

Possible responses:

```json
{ "status": "running", "taskId": "req_...", "progress": { "<commit_sha>": 0|20|100|-1 } }
{ "status": "not_found", "taskId": "req_..." }
```

Progress map semantics:
- Keys: full commit SHA
- Values: integer progress per commit
  - `0`: queued/starting
  - `20`: building
  - `100`: built and packaged
  - `-1`: error for that commit

## Test Result Visualization

The Builder-Tester system includes an interactive web-based report viewer for analyzing test results:

### Features
- **Interactive Reports**: View test results grouped by test case with pass/fail status for each commit
- **Enhanced Log Viewing**: 
  - Multi-attempt log display with clickable status indicators (✅ Pass, ❌ Fail, ⚠️ Other)
  - Build logs with proper line break formatting
  - Execution logs with automatic path resolution using attemptLogMetadata
  - Test details modal with comprehensive per-commit information
- **Automated Verdict Analysis**: Automatically determines failure patterns:
  - `Pass: Not reproduced` - No failures across commits
  - `Bug or Revise: Caused by <commit>` - Single commit failure
  - `Pre-existing Failure` - Failures across all commits
  - `Unstable: Fails intermittently` - Partial failures
  - `Flaky: Inconsistent results across attempts` - Mixed pass/fail results (unified across all run modes)
  - `Error: Test execution failed` - Tests with execution/environment errors
- **Statistics Dashboard**: Visual KPIs showing pass rate, failed tests, unstable tests, error tests, and flaky tests
- **Export Options**: Download results as JSON or CSV

### Unified Test Execution & Flaky Detection

The system now supports unified test execution semantics with configurable run modes:

#### Run Modes (configured in `builder.conf`)
- **until-pass**: Run tests until they pass or max_runs reached
- **until-fail**: Run tests until they fail or max_runs reached
- **fixed-runs**: Always run exactly min_runs to max_runs times

#### Configuration Parameters
- `run_mode`: Execution mode (until-pass, until-fail, fixed-runs)
- `min_runs`: Minimum number of test attempts (default: 1)
- `max_runs`: Maximum number of test attempts (default: 3)

#### Flaky Test Detection
- **Unified Logic**: Flaky detection works consistently across all run modes
- **Result-Based**: A test is flaky if it has mixed pass/fail results across attempts
- **Multi-Attempt Display**: Tests show as PASS(3), FAIL(3), or FLAKY(3) indicating attempt count
- **Comprehensive Logging**: All attempt logs are preserved and accessible via the report interface

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
