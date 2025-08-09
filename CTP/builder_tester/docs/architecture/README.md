# Architecture

## Components

- Builder (port 8089): HTTP server; receives build requests, orchestrates builds/tests, sends callbacks
- Tester (port 8090): HTTP server; executes a single test against a provided build package
- DockerBuildManager: initializes/pulls build image and runs builds
- DockerTesterManager: initializes/pulls test image
- DockerUtils: Docker helpers (availability checks, image pull, run helpers)

## Flow

1. Client POSTs to Builder `/build` with `commits[]`, `tests[]`, `callbackUrl`, `workerIp`, `buildType`.
2. Builder task builds each commit (Docker or direct). For each built artifact, it calls Tester `/test` on `workerIp` with the test details.
3. Tester extracts the build, configures CUBRID, runs the shell test in Docker (or direct fallback). The testcases mount is read-write. Result is read from `<scriptBase>.result` (e.g., `foo.sh` → `foo.result`).
4. Builder aggregates results and POSTs them to `callbackUrl`.

## Execution modes

- Docker (default):
  - Build image: `cubridci/cubridci:develop`
  - Test image: `cubridci/cubridci:test_shell`
  - Images are pulled automatically when Docker is available
- Direct fallback:
  - Used when Docker is unavailable; builds/tests execute on the host

## Tester keep-alive (debug) mode

- When `keepAlive=true` (or `keep_failed_containers=true`), Tester starts the container detached and returns:
  - `containerName`, `execCommand`, and `workspace`
- You can attach with `docker exec -it <container> bash`
- An environment snapshot is available at `/workspace/debug_env.sh`

## Directories

- Builder work dir: `/tmp/builder_work`
- Tester work dir: `/tmp/tester_work`
- Logs (on host): `~/cubrid-testtools/CTP/builder_tester/log/{builder.log,tester.log,builds,tests}`

## Docker run details (Tester)

- Mounts:
  - `/workspace` (writable) – build.tar.gz + generated run_test.sh + copied results
  - `~/cubrid-testtools` → `/home/cubrid-testtools`
  - `<shell_tc_dir>` → `/home/cubrid-testcases-private-ex` (read-write)
- Env vars: `GITHUB_TOKEN`, `CTP_HOME`, `init_path`
- Workdir: `/workspace`
- Entrypoint: `bash -lc /workspace/run_test.sh` (synchronous mode)

## Result detection

- Tester derives result filename from the test script base: `csql_hist.sh` → `csql_hist.result`
- PASS if result file contains `OK` or `PASS`; FAIL if it contains `NOK` or `FAIL`

## Build orchestration

- Builder pulls/uses `cubridci/cubridci:develop` and runs builds inside containers
- Host bind mounts used to improve performance and persistence:
  - `config.docker_host_root` (default `~/docker-work`) binds to `/work` and `/root/.gradle`
  - `config.getCubridSrcDir()` mounted read-only
  - Build artifacts written to `/output` (host work dir)

## Callback

- After all builds/tests complete, Builder POSTs `{ taskId, results[], timestamp }` to the configured `callbackUrl`.