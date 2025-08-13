# Architecture

## Components

- Builder (port 8089): HTTP server; receives build requests, orchestrates builds/tests, sends callbacks
- Tester (port 8090): HTTP server; executes a single test against a provided build package
- DockerBuildManager: initializes/pulls build image and runs builds
- DockerTesterManager: initializes/pulls test image
- DockerUtils: Docker helpers (availability checks, image pull, run helpers)
- **KubernetesManager**: Main orchestration for Kubernetes operations
- **KubernetesBuildManager**: Manages build Jobs in Kubernetes
- **KubernetesTesterManager**: Manages test Jobs in Kubernetes
- **KubernetesConfig**: Configuration for Kubernetes deployment

## Flow

1. Client POSTs to Builder `/build` with `commits[]`, `tests[]`, `callbackUrl`, `workerIp`, `buildType`.
2. Builder task builds each commit (Kubernetes Job, Docker, or direct) in isolation onto a common baseline (parent of earliest commit). For each built artifact, it calls Tester `/test` on `workerIp` with the test details.
3. Tester extracts the build, configures CUBRID, runs the shell test in Kubernetes Job, Docker, or direct fallback. The testcases mount is read-write. Result is read from `<scriptBase>.result` (e.g., `foo.sh` → `foo.result`).
4. Builder aggregates results and POSTs them to `callbackUrl`.

## Execution modes

- **Kubernetes** (when enabled):
  - Build/Test Jobs run as pods with resource limits
  - Automatic workload distribution across nodes
  - Horizontal scaling via deployment replicas
  - Pod anti-affinity for spreading workload
- Docker (default):
  - Build image: `cubridci/cubridci:develop`
  - Test image: `cubridci/cubridci:test_shell`
  - Images are pulled automatically when Docker is available
- Direct fallback:
  - Used when neither Kubernetes nor Docker is available; builds/tests execute on the host

## Kubernetes Architecture

When Kubernetes is enabled:

### Deployments
- **Builder Deployment**: Single replica, exposed via LoadBalancer service
- **Tester Deployment**: Multiple replicas (scalable), internal ClusterIP service

### Jobs
- Each build request creates a Kubernetes Job
- Each test execution creates a separate Job
- Jobs have resource limits and node selectors
- Automatic cleanup after TTL expiration

### Storage
- PersistentVolumeClaims for shared data (builds, test cases, logs)
- ConfigMaps for configuration
- Secrets for sensitive data (GitHub token)

### Scaling
- Horizontal scaling: `kubectl scale deployment/cubrid-tester --replicas=N`
- Node selection: Labels and selectors for workload placement
- Anti-affinity: Spreads pods across different nodes
- Load balancing: Round-robin, random, or least-loaded strategies

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
### Commit isolation details

- Compute baseline as the parent of the earliest requested commit
- Docker build path (default): clone into writable target, checkout a temporary branch at the baseline, cherry-pick the single target commit, sync submodules to gitlinks, clean and build, then package
- Direct host fallback: create a temporary branch + `git worktree add` at baseline, cherry-pick only the target commit, sync submodules, clean and build, package, then remove the worktree and delete the temp branch
- Merge commits are cherry-picked with `-m 1`
- Host binds:
  - `config.docker_host_root` (default `~/docker-work`) binds to `/work` and `/root/.gradle`
  - `config.getCubridSrcDir()` mounted read-only
  - Build artifacts written to `/output` (host work dir)

## Callback

- After all builds/tests complete, Builder POSTs `{ taskId, results[], timestamp }` to the configured `callbackUrl`.