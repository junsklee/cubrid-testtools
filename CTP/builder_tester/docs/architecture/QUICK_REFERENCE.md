# CUBRID Docker Build System - Quick Reference

## Key File Paths

```
Builder Service:
  - Main entry: src/com/navercorp/cubridqa/builder/Builder.java
  - Build orchestration: BuilderTask.java (1000+ lines)
  - Docker build execution: DockerBuildManager.java (1236 lines)
  - Configuration: conf/builder.conf

Tester Service:
  - Main entry: src/com/navercorp/cubridqa/builder/Tester.java
  - Test orchestration: tester/TestOrchestrator.java
  - Docker executors: exec/OptimizedDockerExecutor.java, StandardDockerExecutor.java
  - Configuration: conf/tester.conf

Execution strategies:
  - exec/DirectExecutor.java (direct host execution)
  - exec/StandardDockerExecutor.java (docker with extraction)
  - exec/OptimizedDockerExecutor.java (docker with pre-built images)

Docker management:
  - docker/DockerTesterManager.java (tester docker setup)
  - docker/DockerImageBuilder.java (image creation/caching)
```

## Core Classes and Methods

### Builder.java
- Constructor: Initialize HTTP server, executor pools
- `start()`: Start service at port 8089
- `BuildRequestHandler.handle()`: Process POST /build requests
- `cleanupStaleContainers()`: Remove orphaned containers before accepting new builds

### BuilderTask.java
- `run()`: Main orchestration - builds, distributes tests, collects results
- `buildCommitsConcurrently()`: Parallel docker builds (legacy path)
- `buildPullRequest()`: PR-specific build logic
- `computeCommitOrderInfo()`: Commit order + timestamps for callbacks/verdicts
- Build mode: `commit_build_mode` (`checkout` default, `baseline_cherrypick` optional)

### DockerBuildManager.java
- `buildCubrid(commitHash, workDir, buildType, baselineCommit, commitBuildMode)`: Docker build
- `buildPullRequest()`: Build PR branch
- `createDockerBuildScript()`: Generate build.sh script
- `buildCubridDirect()`: Fallback direct host build
- Build retry: 2 attempts with 5-second delays

### Tester.java
- Constructor: Initialize executors, handlers, HTTP server
- Supports three execution strategies with fallback chain
- Thread pool: newFixedThreadPool(maxConcurrentTests) - default 6

### TestOrchestrator.java
- `runTestWithRetry()`: Implement run_mode semantics
- `executeDockerTest()`: Try optimized→standard→direct
- Supports modes: until-pass, until-fail, fixed-runs
- Flaky test detection: both pass and failure observed

## Concurrent Execution

### Builder Service
- Thread pool: FixedThreadPool(maxConcurrentBuilds) - default 4
- Tracks active tasks in ConcurrentHashMap
- Async submission using CompletableFuture

### Tester Service
- Thread pool: FixedThreadPool(maxConcurrentTests) - default 6 per node
- Per-request test execution pools

### Test Distribution
- Smart Scheduling (default): uses `/health` + optional `/score` + profiles/WAL
- Legacy distribution: work-queue when smart scheduling disabled

## Configuration Keys

### builder.conf
- `listen_port=8089`
- `max_concurrent_builds=4`
- `commit_build_mode=checkout`
- `test_read_timeout_minutes=60`
- `use_docker=true`
- `ccache_enabled=true`
- `parallel_jobs=0` (auto-detect)

### tester.conf
- `tester_port=8090`
- `max_concurrent_tests=6`
- `use_docker_tester=true`
- `optimized_docker_enabled=true`

## Request/Response Flow

### Build Request
```json
{
  "commits": ["hash1", "hash2"],    // or prNumber
  "tests": ["shell/test1.sh"],      // or: shell_heavy/... or shell_perf/...
  "workerIps": ["192.168.1.100"],   // or workerIp (singular)
  "callbackUrl": "http://...",
  "buildType": "debug"
}
```

### Test Request (from Builder to Tester)
```json
{
  "testScript": "shell/test.sh",
  "buildPackage": "http://builder:8089/download/build/...",
  "minRuns": 3,
  "maxRuns": 2,
  "runMode": "until-pass",
  "commitShort": "abc1234"
}
```

## Key Algorithms

### Test Distribution (BuilderTask lines 137-176)
```
int globalTestIndex = 0;
for commit in builtPackages:
    for test in tests:
        worker = workerIps[globalTestIndex % workerIps.size()]
        globalTestIndex++
        queue test for worker
```

### Run Mode Semantics (TestOrchestrator lines 45-226)
- `until-pass`: Stop after min_runs if pass (or on max_runs)
- `until-fail`: Stop after min_runs if fail (or on max_runs)
- `fixed-runs`: Always run exactly min_runs to max_runs
- Flaky detection: Mark if both pass and fail observed

### Ccache Optimization
- Host: `$HOME/docker-work/work/.ccache` (persistent across builds)
- Container: Mounted at `/work/.ccache`
- Path: Fixed `/work/cubrid-build` for hit ratio optimization
- Git shim: Return deterministic version (0000000) for consistency

## Docker Build Process

1. **Build Mode**: `checkout` (default) or `baseline_cherrypick`
2. **Source Checkout**: Clone with reference to reduce network usage
3. **Commit Application**:
   - checkout: `git checkout <commit>`
   - baseline_cherrypick: cherry-pick with format-patch fallback
4. **Submodule Sync**: Parallel update with git version detection
5. **Build Execution**: With ccache and deterministic version
6. **Package Creation**: Packages only _install/CUBRID (optimized)
7. **Cleanup**: Removes temp branches (keeps workspace for ccache)

## Worker Node Management

### Discovery & Validation
- Format: "host:port" (port defaults to 8090)
- Validation: Socket.connect() with 5-second timeout
- Formats: "localhost", "192.168.1.100", "192.168.1.100:8090"

### Types
- Local: localhost, 127.0.0.1, 192.168.x.x
- Remote: Any other network address

### Communication
- HTTP POST to http://worker:8090/test
- Connect timeout: 10 seconds
- Read timeout: 60 minutes (configurable)
- No retry logic (fail on first failure)

## Build Caching

### In-Memory Cache
- ConcurrentHashMap<String, String>
- Key: commit + buildType + baselineKey (baseline SHA or `history`)
- Validates cached builds before reuse

### Disk Cache
- Filename: cubrid_<commit>.tar.gz
- Location: work_dir/build_<hash>/
- Cleanup: Rotated based on max_tar_files

## Docker Image Caching (Optimized Mode)

### Image Builder
- Class: DockerImageBuilder
- Cache: Concurrent image name → package mapping
- LRU eviction: When cache size exceeds max_cached_images
- Image naming: cubrid-test:commit_baselineKey

### Image Build Process
1. Extract build package
2. Run Docker container with extraction
3. Commit container to create image
4. Clean up temporary container

## Logging & Tracking

### Request Tracking
- Request ID: Generated per build request
- Grouped logs: Separate directory per request ID
- Log rotation: Configurable max_request_logs

### Per-Build Logging
- Location: log/system/builder.log or request-grouped
- Build script output: log/builds/build_<commit>.log
- Ccache logs: ~/.docker-work/work/.ccache/logs/

## Error Handling & Fallback

### Docker Build
- Fallback: Direct host build if Docker unavailable
- Retry: 2 attempts with 5-second delays

### Test Execution
- Fallback chain: Optimized→Standard→Direct
- Graceful degradation: Continues with less-optimized strategy

### Container Cleanup
- Startup cleanup: Remove stale tester_* containers
- Safety net: Prevents resource exhaustion from orphaned containers

## Performance Tuning

### Parallel Jobs
- Config: parallel_jobs=0 (auto-detect from CPU cores)
- Environment: MAKEFLAGS=-j<N>

### Ccache Configuration
- Max size: 15G (configurable)
- Hardlink: Enabled for fast cache reuse
- Sloppiness: Tuned for consistency

### Thread Pools
- Builder: workerIps.size() * maxTestsPerWorker threads
- Example: 2 workers × 6 concurrent = 12-thread pool

## Monitoring

### Health Endpoints
- Builder: GET /health
- Tester: GET /health

### Status Endpoints
- Builder: GET /status?taskId=<id>

### Log Access
- Tester: GET /log/<filename>
- Builder: GET /download/build/<filename>
