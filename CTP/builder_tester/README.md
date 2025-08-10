# Builder-Tester System

A concurrent build-and-test system for CUBRID. It builds multiple commits in parallel and runs shell tests in isolated Docker containers, returning pass/fail/error results and optional callbacks.

This README is a high-level overview. Detailed docs are in the docs/ directory:

- docs/features/README.md – Feature set and behavior
- docs/architecture/README.md – Components and flow
- docs/usage/README.md – Setup and usage, CLI and APIs
- docs/configuration/README.md – All configuration options and defaults

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

## Logs
- Builder logs: `~/cubrid-testtools/CTP/builder_tester/log/builder.log`, plus `bin/builder_output.log`
- Tester logs: `~/cubrid-testtools/CTP/builder_tester/log/tester.log`, plus `bin/tester_output.log`
- Per-build/test logs: `~/cubrid-testtools/CTP/builder_tester/log/builds|tests`

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
│   └── DockerUtils.java
├── conf/
│   ├── builder.conf
│   └── tester.conf
├── bin/
│   ├── compile.sh
│   ├── start_builder.sh | stop_builder.sh | run_builder.sh
│   ├── start_tester.sh  | stop_tester.sh  | run_tester.sh
│   └── test_client.sh
├── lib/
│   └── json.jar
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
