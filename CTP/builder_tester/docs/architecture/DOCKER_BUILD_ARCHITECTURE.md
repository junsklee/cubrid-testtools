# CUBRID Test Tools Docker Build System Architecture

## Overview

The CUBRID test tools system consists of two main distributed services:
- **Builder Service**: Receives build requests, builds CUBRID at multiple commits, and distributes tests
- **Tester Service**: Executes tests on assigned worker nodes

The system supports concurrent build and test execution with worker node distribution, Docker containerization, and intelligent caching mechanisms.

---

## 1. DOCKER BUILD SYSTEM - HOW BUILDS ARE TRIGGERED AND RUN

### 1.1 Build Execution Flow

**File**: `/home/qahome/cubrid-testtools/CTP/builder_tester/src/com/navercorp/cubridqa/builder/Builder.java`

#### Request Processing Pipeline
```
HTTP POST /build → BuildRequestHandler → ValidateRequest → BuilderTask (async submission)
    ↓
BuilderTask.run() → Concurrent Docker Builds → Test Distribution → Callback
```

#### Key Components

**Builder Service** (lines 24-132):
- Main entry point receiving HTTP requests at `/build` endpoint
- Maintains `buildExecutor`: Fixed thread pool with configurable concurrency
- Uses `activeTasks`: ConcurrentHashMap tracking ongoing builds
- Max concurrent builds: Configured in `builder.conf` (default 4)

**BuildRequestHandler** (lines 132-241):
- Validates incoming JSON requests
- Supports both single `workerIp` and array `workerIps` formats (backward compatible)
- Validates tester reachability before accepting build
- Generates unique `requestId` for request tracking
- Submits work to `buildExecutor` using CompletableFuture pattern

#### Configuration Parameters
- `listen_port=8089`: Builder HTTP server port
- `max_concurrent_builds=4`: Number of parallel docker build containers
- `use_docker=true`: Enable Docker builds
- `use_prebuilt_docker_images=true`: Use pre-built Docker images vs. building from source
- `docker_build_image=cubridci/cubridci:develop`: Docker image for builds

---

### 1.2 Concurrent Build Execution

**File**: `/home/qahome/cubrid-testtools/CTP/builder_tester/src/com/navercorp/cubridqa/builder/DockerBuildManager.java`

#### Docker Build Process

```java
buildCubrid(commitHash, workDir, buildType, baselineCommit)
    ↓
createDockerBuildScript() → Generates build.sh
    ↓
Execute Docker container with:
  - Volume mounts: CUBRID source, work directories
  - Environment variables: ccache config, GITHUB_TOKEN
  - Build script execution
    ↓
Extract build package to /output/cubrid_<hash>.tar.gz
```

#### Docker Command Construction (lines 118-197)

```bash
docker run --rm \
  -v /cubrid-src:/cubrid-src:ro \
  -v /work:/output:rw \
  -v /host-work:/work:rw \
  -v /gradle-cache:/root/.gradle:rw \
  -e CC=ccache\ gcc \
  -e CXX=ccache\ g++ \
  -e CCACHE_DIR=/work/.ccache \
  cubridci/cubridci:develop \
  bash -lc /build.sh
```

#### Build Script Features
- **Baseline-based building**: Cherry-picks target commit onto baseline (parent of earliest commit)
- **Fallback mechanism**: Format-patch if cherry-pick fails
- **Git version detection**: Handles --jobs and --depth flags based on git version
- **Parallel submodule updates**: Concurrent git submodule initialization
- **Package creation**: Packages only `_install/CUBRID` directory (optimized)
- **Cleanup**: Removes temp branches and workspaces (preserves for ccache optimization)

#### Build Cache (lines 1201-1234)
```java
private static final ConcurrentHashMap<String, String> buildCache
```
- In-memory cache: Commit → Package path mapping
- Includes baseline in cache key to prevent incorrect reuse
- Validates cached builds against baseline before reuse
- Per-build metadata stored for tracking

#### Build Retry Logic (lines 217-251)
- Maximum 2 attempts (initial + 1 retry)
- 5-second wait between retries
- Exit code 0 indicates success

---

## 2. WORKER NODE MANAGEMENT AND CONFIGURATION

### 2.1 Worker Node Discovery and Validation

**File**: `/home/qahome/cubrid-testtools/CTP/builder_tester/src/com/navercorp/cubridqa/builder/Builder.java`

#### Request Validation (lines 411-431)
```java
// Supports both formats:
workerIps = request.getJSONArray("workerIps"); // Preferred
OR
String workerIp = request.getString("workerIp"); // Backward compatible
```

#### Tester Reachability Check (lines 433-462)
```java
validateTesterReachability(String workerIp)
    ↓
Parse "host:port" format (port defaults to 8090)
    ↓
Socket.connect(InetSocketAddress, 5000ms timeout)
    ↓
Success → continue
Failure → throw IllegalArgumentException
```

### 2.2 Tester Service Configuration

**File**: `/home/qahome/cubrid-testtools/CTP/builder_tester/conf/tester.conf`

```properties
tester_port=8090
max_concurrent_tests=6
use_docker_tester=true
docker_test_image=cubridci/cubridci:test_shell
optimized_docker_enabled=true
build_cache_size=10
```

### 2.3 Tester Service Architecture

**File**: `/home/qahome/cubrid-testtools/CTP/builder_tester/src/com/navercorp/cubridqa/builder/Tester.java`

```
Tester Constructor
    ↓
Initialize Components:
  - BuildCache: Manages build package downloads
  - ShellTcSync: Syncs test case repository
  - CubridInstaller: Extracts CUBRID packages
  - ExecutorStrategy implementations:
    • DirectExecutor: Direct host execution
    • StandardDockerExecutor: Docker with package extraction
    • OptimizedDockerExecutor: Docker with pre-built images
  - TestOrchestrator: Orchestrates retry/repeat logic
    ↓
Create HTTP Server at port 8090
    ↓
Register Handlers:
  /test → TestHandler
  /health → HealthHandler
  /log/ → LogStreamHandler
```

**Thread Pool Configuration**:
```java
newFixedThreadPool(Math.max(1, config.getMaxConcurrentTests()))
```
- Default: 6 concurrent test threads per tester node

---

## 3. WORKLOAD DISTRIBUTION ACROSS NODES

### 3.1 Test Distribution Algorithm

**File**: `/home/qahome/cubrid-testtools/CTP/builder_tester/src/com/navercorp/cubridqa/builder/BuilderTask.java`

#### Distribution Strategy (lines 124-187)

```
1. Create workerTestQueues Map<String, List<Callable<JSONObject>>>
   - One entry per worker node

2. Calculate totalTestExecutions = builtPackages.size() × tests.length()

3. Distribute using GLOBAL TEST INDEX for round-robin:
   
   int globalTestIndex = 0;
   for each commit:
       for each test:
           assignedWorker = workerIps.get(globalTestIndex % workerIps.size())
           globalTestIndex++
           workerTestQueues.get(assignedWorker).add(testCallable)

4. Result: Balanced distribution even with single commit/multiple workers
```

#### Example Distribution
```
Single commit + 3 tests + 2 workers:
  Test 1 → Worker 1 (globalTestIndex=0 % 2 = 0)
  Test 2 → Worker 2 (globalTestIndex=1 % 2 = 1)
  Test 3 → Worker 1 (globalTestIndex=2 % 2 = 0)

Multiple commits + 2 tests each + 2 workers:
  Commit1-Test1 → Worker 1
  Commit1-Test2 → Worker 2
  Commit2-Test1 → Worker 1
  Commit2-Test2 → Worker 2
```

#### Special Single-Commit Handling (lines 140-154)
- Ensures tests distributed across all workers
- Warns if no remote workers receive tests
- Detects imbalanced distribution (max-min > 1)

#### Distribution Summary Logging (lines 189-231)
```
Log for each worker:
  Worker A (local/remote): N tests (X%)
  ...Verification checks...
```

### 3.2 Concurrent Test Execution

#### Thread Pool Management (lines 237-286)

```java
ExecutorService testPool = Executors.newFixedThreadPool(
    workerIps.size() × maxTestsPerWorker
);

// Process workers in priority order: local first, then remote
// This ensures local tests start immediately without waiting for remote workers

for each worker in sortedWorkers:
    for each test in worker's queue:
        testPool.submit(() -> runTest(...))

// Collect all futures and wait for completion
for Future<JSONObject> f in futuresList:
    results.add(f.get())
```

### 3.3 Per-Worker Test Execution

**File**: `/home/qahome/cubrid-testtools/CTP/builder_tester/src/com/navercorp/cubridqa/builder/BuilderTask.java`

#### Remote Test Execution (lines 570-640+)

```java
runTest(commit, buildPackage, testPath, assignedWorker, baselineCommit, buildType)
    ↓
1. Build tester URL: http://<assignedWorker>:8090/test
2. Prepare JSON request:
   {
     "testScript": "shell/...",
     "testName": "...",
     "buildPackage": "http://builder:8089/download/build/...",
     "commitShort": "...",
     "baselineShort": "...",
     "buildType": "debug|release",
     "expectedBuildVersion": "...",
     "minRuns": 3,
     "maxRuns": 5,
     "runMode": "until-pass|until-fail|fixed-runs"
   }
3. POST to /test endpoint
4. Wait for response (timeout configurable, default 60 minutes)
5. Parse JSON result with status, logs, execution details
```

#### HTTP Communication Parameters
- **Connect timeout**: 10 seconds (configurable)
- **Read timeout**: 60 minutes per test (configurable)
- **Retry policy**: No built-in retry; fail on first connection failure
- **Log fetch timeout**: 120 seconds for fetching result logs

---

## 4. BUILDER_TESTER COMPONENT STRUCTURE

### 4.1 Directory Structure

```
/home/qahome/cubrid-testtools/CTP/builder_tester/
├── src/com/navercorp/cubridqa/builder/
│   ├── Builder.java                          # Main builder service
│   ├── Tester.java                           # Main tester service
│   ├── BuilderTask.java                      # Build + test orchestration
│   ├── BuilderConfig.java                    # Configuration management
│   ├── DockerBuildManager.java               # Docker build execution
│   │
│   ├── exec/                                 # Execution strategies
│   │   ├── ExecutorStrategy.java             # Interface
│   │   ├── DirectExecutor.java               # Direct execution
│   │   ├── StandardDockerExecutor.java       # Docker w/ extraction
│   │   ├── OptimizedDockerExecutor.java      # Docker w/ pre-built images
│   │   ├── EnvScriptFactory.java             # Script generation
│   │   ├── CubridInstaller.java              # Package extraction
│   │   ├── ProcessIO.java                    # I/O handling
│   │   └── CtpEnvResolver.java               # Environment resolution
│   │
│   ├── docker/                               # Docker management
│   │   ├── DockerTesterManager.java          # Tester Docker setup
│   │   ├── DockerImageBuilder.java           # Image creation/caching
│   │   └── DockerUtils.java                  # Docker utilities
│   │
│   ├── tester/                               # Test execution
│   │   ├── TestOrchestrator.java             # Retry/repeat logic
│   │   ├── TestHandler.java                  # HTTP /test handler
│   │   ├── TestRequest.java                  # Request DTO
│   │   ├── TestResult.java                   # Result DTO
│   │   ├── TestStatus.java                   # Status enum
│   │   ├── HealthHandler.java                # HTTP /health handler
│   │   ├── LogStreamHandler.java             # HTTP /log handler
│   │   └── ApiServer.java                    # HTTP server
│   │
│   ├── cache/                                # Build cache
│   │   └── BuildCache.java                   # Package downloading
│   │
│   ├── git/                                  # Git operations
│   │   └── ShellTcSync.java                  # Test case sync
│   │
│   ├── logging/                              # Request logging
│   │   ├── LogConfig.java
│   │   ├── RequestContext.java
│   │   ├── RequestLogManager.java
│   │   └── LogRotationManager.java
│   │
│   ├── config/                               # Configuration
│   │   └── Config.java                       # Tester config
│   │
│   ├── http/                                 # HTTP utilities
│   │   └── HttpUtils.java
│   │
│   ├── report/                               # Reporting
│   │   └── ReportHandler.java
│   │
│   ├── impl/                                 # Implementations
│   │   └── MattermostSender.java
│   │
│   └── logs/                                 # Log handling
│       ├── LogLocator.java
│       └── RequestLogBridge.java
│
├── conf/
│   ├── builder.conf                          # Builder configuration
│   └── tester.conf                           # Tester configuration
│
└── log/
    ├── system/                               # System logs
    ├── requests/                             # Request-grouped logs
    └── builds/                               # Build logs
```

### 4.2 Key Classes

#### Builder.java (610 lines)
- **Purpose**: Main HTTP server for build requests
- **Key Methods**:
  - `start()`: Initialize and start HTTP server
  - `BuildRequestHandler.handle()`: Process build requests
  - `StatusHandler.handle()`: Return task status
  - `HealthCheckHandler.handle()`: Health check endpoint
  - `cleanupStaleContainers()`: Remove orphaned containers

#### BuilderTask.java (1000+ lines)
- **Purpose**: Orchestrate builds and test distribution
- **Key Methods**:
  - `run()`: Main execution flow
  - `buildCommitsConcurrently()`: Parallel build execution
  - `buildPullRequest()`: PR-specific build logic
  - `determineBaselineCommit()`: Calculate baseline
  - `runTest()`: Remote test execution via HTTP

#### DockerBuildManager.java (1236 lines)
- **Purpose**: Execute builds in Docker containers
- **Key Methods**:
  - `buildCubrid()`: Build from commit hash
  - `buildPullRequest()`: Build PR branch
  - `createDockerBuildScript()`: Generate build script
  - `buildCubridDirect()`: Fallback direct build

#### Tester.java (210 lines)
- **Purpose**: Main HTTP server for test requests
- **Key Methods**:
  - Constructor: Initialize all execution strategies and handlers
  - `start()`: Start HTTP server

#### TestOrchestrator.java (400+ lines)
- **Purpose**: Manage test execution with retry logic
- **Key Methods**:
  - `runTestWithRetry()`: Implement run_mode semantics
  - `executeDockerTest()`: Orchestrate Docker execution (with fallback)
  - `executeDirectTest()`: Direct host execution

#### ExecutorStrategy implementations
- **DirectExecutor**: Execution on host machine
- **StandardDockerExecutor**: Docker with CUBRID extraction
- **OptimizedDockerExecutor**: Docker with pre-built images

#### DockerImageBuilder.java (300+ lines)
- **Purpose**: Build and cache Docker images with CUBRID pre-installed
- **Key Methods**:
  - `getOrBuildImage()`: Get existing or build new image
  - `buildImageFromPackage()`: Docker commit-based image creation
  - `evictOldestImage()`: LRU cache management

---

## 5. WORKLOAD DISTRIBUTION LOGIC

### 5.1 Load Balancing Algorithm

**Round-robin distribution with global index** (BuilderTask.java lines 137-176):

```java
int globalTestIndex = 0;
for (Map.Entry<String, String> entry : builtPackages.entrySet()) {
    for (int i = 0; i < tests.length(); i++) {
        final String assignedWorker = workerIps.get(globalTestIndex % workerIps.size());
        globalTestIndex++;
        // Queue test for assigned worker
    }
}
```

**Key Properties**:
- Simple modulo-based assignment
- No worker status checking (assumes all workers have equal capacity)
- Even distribution guaranteed for all commit×test combinations
- Single-commit special case handling

### 5.2 Worker Prioritization

**Order of execution** (BuilderTask.java lines 244-252):
```java
// Sort: local testers first (true > false in reverse comparison)
sortedWorkers.sort((e1, e2) -> {
    boolean isLocal1 = isLocalTester(e1.getKey());
    boolean isLocal2 = isLocalTester(e2.getKey());
    return Boolean.compare(isLocal2, isLocal1);
});
```

**Local vs Remote Detection** (BuilderTask.java):
```java
private boolean isLocalTester(String worker) {
    return worker.equals("localhost") || 
           worker.equals("127.0.0.1") ||
           worker.contains("192.168.");  // Local network heuristic
}
```

### 5.3 Concurrent Execution Pool

**Pool sizing** (BuilderTask.java line 237):
```java
ExecutorService testPool = Executors.newFixedThreadPool(
    workerIps.size() * maxTestsPerWorker
);
```

Where `maxTestsPerWorker` = `config.getMaxConcurrentTests()` (default 6)

**Example**: 2 workers × 6 concurrent = 12-thread pool for test submission

---

## 6. CCACHE SHARED DIRECTORY MANAGEMENT

### 6.1 Configuration

**File**: `builder.conf`

```properties
ccache_enabled=true
ccache_dir=$HOME/docker-work/work/.ccache
ccache_max_size=15G
ccache_compilercheck=mtime
ccache_hardlink=true
ccache_sloppiness=file_macro,time_macros,include_file_mtime,include_file_ctime
```

### 6.2 Docker Volume Mounting

**DockerBuildManager.java** (lines 101-185):

```java
// Host directories under single mount point
File hostRoot = new File(config.getDockerHostRoot());  // ~/.docker-work
File hostWorkDir = new File(hostRoot, "work");
File ccacheDir = new File(hostWorkDir, ".ccache");

// Create required subdirectories
new File(ccacheDir, "logs").mkdirs();
new File(ccacheDir, "tmp").mkdirs();

// Mount into container
baseDockerCmd.add("-v");
baseDockerCmd.add(hostWorkDir + ":/work:rw");

// Environment variables for ccache inside container
baseDockerCmd.add("-e");
baseDockerCmd.add("CCACHE_DIR=/work/.ccache");
baseDockerCmd.add("-e");
baseDockerCmd.add("CCACHE_BASEDIR=/tmp/cubrid-build");
baseDockerCmd.add("-e");
baseDockerCmd.add("CCACHE_NOHASHDIR=1");
baseDockerCmd.add("-e");
baseDockerCmd.add("CCACHE_LOGFILE=/work/.ccache/logs/ccache_" + commitShort + ".log");
baseDockerCmd.add("-e");
baseDockerCmd.add("CCACHE_TEMPDIR=/work/.ccache/tmp");
```

### 6.3 Build Workspace Strategy

**DockerBuildManager.java** (lines 737-747):

```bash
# Per-build workspace with shared ccache
if [ -d /work ]; then
    target=/work/cubrid-build
else
    target=/tmp/cubrid-build
fi

# Fixed path for ccache consistency (NOT unique per build)
rm -rf "$target"
mkdir -p "$target"
cd "$target"
```

**Key Optimization**:
- Shared `/work/.ccache` directory across all builds
- Fixed `/work/cubrid-build` path for consistent cache hits
- Per-build cleanup (rm -rf $target) between builds
- CCACHE_NOHASHDIR=1: Ignore directory in hash calculation
- CCACHE_BASEDIR=/tmp/cubrid-build: Normalize paths for reuse

### 6.4 Git Shim for Deterministic Version

**DockerBuildManager.java** (lines 541-560, 883-903):

```bash
# Create git shim to intercept version queries
GIT_SHIM_DIR="/tmp/git-shim-$$"
mkdir -p "$GIT_SHIM_DIR"
cat > "$GIT_SHIM_DIR/git" << 'GITSHIMEOF'
#!/usr/bin/env bash
# Return deterministic values for version queries
if [[ "$1" == "rev-parse" && "$2" == "--short=7" ]]; then
    echo "0000000"
elif [[ "$1" == "rev-list" ]]; then
    echo "0"
else
    exec /usr/bin/git "$@"
fi
GITSHIMEOF
chmod +x "$GIT_SHIM_DIR/git"
export PATH="$GIT_SHIM_DIR:$PATH"
```

**Purpose**: Make build version queries deterministic for ccache consistency

---

## 7. EXECUTION FLOW EXAMPLES

### Example 1: Single Commit, Multiple Commits, Multiple Tests, 2 Workers

```
Request:
{
  "commits": ["abc1234", "def5678"],
  "tests": ["shell/sql/test1.sh", "shell/sql/test2.sh"],
  "workerIps": ["192.168.1.100", "192.168.1.101"],
  "callbackUrl": "http://ci.example.com/callback"
}

Step 1: Build Phase (Concurrent)
┌─────────────────────────────────────┐
│ DockerBuildManager (Thread 1)        │
│ Build: abc1234                      │
│ Output: build_1234.tar.gz           │
├─────────────────────────────────────┤
│ DockerBuildManager (Thread 2)        │
│ Build: def5678                      │
│ Output: build_5678.tar.gz           │
└─────────────────────────────────────┘

Step 2: Test Distribution (Round-robin)
globalTestIndex = 0:
  abc1234 + test1 → 192.168.1.100 (0 % 2 = 0)
globalTestIndex = 1:
  abc1234 + test2 → 192.168.1.101 (1 % 2 = 1)
globalTestIndex = 2:
  def5678 + test1 → 192.168.1.100 (2 % 2 = 0)
globalTestIndex = 3:
  def5678 + test2 → 192.168.1.101 (3 % 2 = 1)

Step 3: Test Execution (Parallel via Tester nodes)
Worker 192.168.1.100:
  POST /test → abc1234 + test1
  POST /test → def5678 + test1

Worker 192.168.1.101:
  POST /test → abc1234 + test2
  POST /test → def5678 + test2

Step 4: Callback
POST http://ci.example.com/callback
{
  "results": [
    {
      "commit": "abc1234",
      "test": "shell/sql/test1.sh",
      "status": "pass|fail",
      ...
    },
    ...
  ]
}
```

### Example 2: PR Build with Single Commit

```
Request:
{
  "prNumber": 1234,
  "tests": ["shell/sql/test1.sh"],
  "workerIps": ["localhost"],
  "callbackUrl": "http://localhost:9000/callback"
}

Step 1: PR Resolution
  git fetch origin pull/1234/head:pr_1234_br
  PR_HEAD_SHA = abc1234567
  BASELINE_SHA = def5678 (merge-base with develop)

Step 2: Build PR
  DockerBuildManager.buildPullRequest(pr_1234_br, abc1234567, ...)
  Output: cubrid_abc1234.tar.gz

Step 3: Test Distribution (1 commit, 1 test, 1 worker)
  localhost + test1 → Queue test

Step 4: Test Execution
  POST http://localhost:8090/test
  {
    "testScript": "shell/sql/test1.sh",
    "buildPackage": "http://localhost:8089/download/build/cubrid_abc1234.tar.gz",
    "minRuns": 3,
    "maxRuns": 5,
    "runMode": "until-pass"
  }

Step 5: Tester Response (with retries)
  Attempt 1: FAIL → Continue
  Attempt 2: PASS → Early exit (min_runs=3, but we got pass, continue for confidence)
  Attempt 3: PASS → Exit as stable
  
  Response:
  {
    "test": "shell/sql/test1.sh",
    "status": "pass",
    "attempts": 3
  }
```

---

## 8. KEY CONFIGURATION FILES

### builder.conf
```properties
listen_port=8089
max_concurrent_builds=4
cubrid_src_dir=~/cubrid
shell_tc_dir=~/cubrid-testcases-private-ex
work_dir=~/tmp/builder_work
tester_port=8090
use_docker=true
docker_build_image=cubridci/cubridci:develop
ccache_enabled=true
ccache_dir=$HOME/docker-work/work/.ccache
parallel_jobs=0  # Auto-detect
```

### tester.conf
```properties
tester_port=8090
max_concurrent_tests=6
cubrid_src_dir=~/cubrid
shell_tc_dir=~/cubrid-testcases-private-ex
work_dir=~/tmp/tester_work
use_docker_tester=true
docker_test_image=cubridci/cubridci:test_shell
optimized_docker_enabled=true
build_cache_size=10
test_read_timeout_minutes=60
```

---

## 9. CONCURRENT EXECUTION SAFETY

### 9.1 Thread-Safe Collections

**BuilderTask.java**:
```java
private final List<JSONObject> results = Collections.synchronizedList(...);
private final Map<String, Integer> progress = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<String, String> buildCache;
```

### 9.2 Build Container Cleanup

**Builder.java** (lines 491-528):
```java
cleanupStaleContainers()
  ├── removeByNamePrefix("tester_debug_")
  ├── removeByNamePrefix("tester_release_")
  └── removeExitedByAncestor(dockerImage)
```

Executed before accepting new build requests to prevent resource exhaustion.

### 9.3 Per-Build Isolation

- Unique workspace per build: `build_<commit>_<timestamp>/`
- Fixed ccache directory shared across builds
- Per-build log files: `build_<commit>.log`

---

## Summary of Architecture

The Docker build system is a highly distributed architecture with:

1. **Builder Service**: Orchestrates parallel builds and test distribution
2. **Tester Services**: Multiple nodes executing tests concurrently
3. **Round-robin Distribution**: Even test load across workers regardless of commit count
4. **Smart Caching**: Build packages, Docker images, and compiler cache (ccache)
5. **Concurrent Execution**: Fixed thread pools at builder and tester level
6. **Graceful Degradation**: Fallback from optimized→standard→direct execution
7. **Request Tracking**: Per-request logging and callback notification

