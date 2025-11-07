# Architecture

## Components

- Builder (port 8089): HTTP server; receives build requests, orchestrates builds/tests, sends callbacks
- Tester (port 8090): HTTP server; executes a single test against a provided build package
  - **Enhanced with `/log/{filename}` endpoint**: Serves test execution logs to remote builders
  - **Multi-attempt log support**: Stores separate log files for each test retry
- DockerBuildManager: initializes/pulls build image and runs builds
- DockerTesterManager: initializes/pulls test image
- DockerUtils: Docker helpers (availability checks, image pull, run helpers)
- **ReportHandler**: Enhanced report system with improved modal functionality
- **MultipartHelper**: Handles efficient log transfer via HTTP multipart responses

## Flow

1. Client POSTs to Builder `/build` with `commits[]`, `tests[]`, `callbackUrl`, `workerIp`, `buildType`.
2. Builder task builds each commit (Docker or direct) in isolation onto a common baseline (parent of earliest commit). For each built artifact, it calls Tester `/test` on `workerIp` with the test details.
3. Tester extracts the build, configures CUBRID, runs the shell test in Docker (or direct fallback). The testcases mount is read-write. Result is read from `<scriptBase>.result` (e.g., `foo.sh` → `foo.result`).
   - **Multi-attempt execution**: If retry is configured, creates separate log files (test.log, test.2.log, etc.)
   - **Response with metadata**: Returns `attemptLogMetadata` array with status and filename for each attempt
4. **Enhanced result processing**: Builder fetches log files from remote testers using `/log/{filename}` endpoint
5. Builder aggregates results and POSTs them to `callbackUrl` with comprehensive log data.

## Execution modes

- Docker (default):
  - Build image: `cubridci/cubridci:develop`
  - Test image: `cubridci/cubridci:test_shell`
  - Images are pulled automatically when Docker is available
- Direct fallback:
  - Used when Docker is unavailable; builds/tests execute on the host

### Adaptive test scheduling

- Builder queries every tester’s `/health` endpoint to learn its advertised `maxConcurrentTests`.
- For each tester we start workers equal to its capacity. Every worker immediately grabs a pending test and sends it to that tester; when the response returns it pulls the next job from the shared queue.
- Because slots are tied to testers, each node receives its full concurrency allotment instantly and is fed a new test the moment it finishes the previous one.
- This keeps high-powered testers saturated without starving slower nodes, and avoids the up-front static partitioning that previously left fast machines idle.

## Tester keep-alive (debug) mode

- When `keepAlive=true` (or `keep_failed_containers=true`), Tester starts the container detached and returns:
  - `containerName`, `execCommand`, and `workspace`
- You can attach with `docker exec -it <container> bash`
- An environment snapshot is available at `/workspace/debug_env.sh`

## Directories

- Builder work dir: `/tmp/builder_work`
- Tester work dir: `/tmp/tester_work`
- Logs (on host):
  - System: `~/cubrid-testtools/CTP/builder_tester/log/system/{builder.log,tester.log}`
  - Requests: `~/cubrid-testtools/CTP/builder_tester/log/requests/req_*/{request.json,builder.log,builds/,tests/}`
  - Metadata: `~/cubrid-testtools/CTP/builder_tester/log/.metadata.json`
  - See log/LOG_MANAGEMENT.md for detailed logging architecture

### Shell testcases workspace

- `shell_tc_dir` in the config points to the shared testcases checkout (often mounted read-only when multiple hosts share the same tree).
- The builder now prepares a writable overlay clone automatically:
  - `shell_tc_overlay_mode` (default `auto`) decides when to activate it. Options: `auto` (use overlay only when the source is read-only), `enabled`, or `disabled`.
  - `shell_tc_overlay_dir` sets the target clone/overlay location; default is `<work_dir>/shell_tc_overlay`.
- All runtime interactions (`git fetch`, branch checkout, copying tests for execution) operate against the overlay when it is active, keeping the original checkout pristine for other runners.

## Logging Architecture

The system implements a dual logging approach:
- **System Logs**: Global visibility of all activities across all requests
- **Request Logs**: Isolated logs for each specific request with unique request IDs
- **Thread Context**: RequestContext propagation across executor threads
- **Automatic Cleanup**: Configurable retention of request logs and build artifacts

### Log File Naming
- Build logs: `build_<commit_short>.log`
- Test scripts: `docker_script_<commit_short>_<test_name>.sh`
- Test output: `docker_<commit_short>_<test_name>.log`

## Docker run details (Tester)

- Mounts:
  - `/workspace` (writable) – build.tar.gz + generated run_test.sh + collected result artifacts
  - `~/cubrid-testtools` → `/home/cubrid-testtools`
  - `<shell_tc_dir overlay>` → `/workspace/testcases` (read-write)
- Env vars: `GITHUB_TOKEN`, `CTP_HOME`, `init_path`
- Workdir: `/workspace`
- Entrypoint: `bash -lc /workspace/run_test.sh` (synchronous mode)
- Shell testcases run in-place on the overlay clone that is mounted into the container at `/workspace/testcases`, avoiding per-test copies while leaving the shared source checkout untouched.

## Result detection

- Tester derives result filename from the test script base: `csql_hist.sh` → `csql_hist.result`
- PASS if result file contains `OK` or `PASS`; FAIL if it contains `NOK` or `FAIL`

## Build orchestration

- Builder pulls/uses `cubridci/cubridci:develop` and runs builds inside containers
- Host bind mounts used to improve performance and persistence:

### Commit isolation details

- Compute baseline as the parent of the earliest requested commit
- **Automatically exclude the baseline commit itself from build targets** (baseline should result in baseline version, not baseline+1)
- For each target commit, ensure clean baseline state for consistent version numbering:
- Docker build path (default): clone into writable target, checkout a temporary branch at the baseline, reset hard to baseline, cherry-pick the single target commit, sync submodules to gitlinks, clean and build, then package
- Direct host fallback: create a temporary branch + `git worktree add` at baseline, reset hard to baseline, cherry-pick only the target commit, sync submodules, clean and build, package, then remove the worktree and delete the temp branch
- Each build starts from pristine baseline state, ensuring all target commits get version = baseline + 1
- Merge commits are cherry-picked with `-m 1`
- Host binds:
  - `config.docker_host_root` (default `~/docker-work`) binds to `/work` and `/root/.gradle`
  - `config.getCubridSrcDir()` mounted read-only
  - Build artifacts written to `/output` (host work dir)

## Callback

- After all builds/tests complete, Builder POSTs `{ taskId, results[], timestamp }` to the configured `callbackUrl`.
