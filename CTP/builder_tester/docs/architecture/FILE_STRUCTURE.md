# CUBRID Builder/Tester - Complete File Structure and Locations

## Core Java Source Files

### Main Services
```
src/com/navercorp/cubridqa/builder/
├── Builder.java                              # Main builder HTTP service
├── Tester.java                               # Main tester HTTP service
├── BuilderTask.java                          # Build orchestration (1000+ lines)
├── BuilderConfig.java                        # Configuration for both services
├── DockerBuildManager.java                   # Docker build execution (1236 lines)
└── MultipartHelper.java                      # Multipart response handling
```

### Execution Strategies (exec/)
```
exec/
├── ExecutorStrategy.java                     # Interface definition
├── DirectExecutor.java                       # Execute tests directly on host
├── StandardDockerExecutor.java               # Execute in Docker (extract package)
├── OptimizedDockerExecutor.java              # Execute in Docker (pre-built image)
├── EnvScriptFactory.java                     # Generate execution scripts
├── CubridInstaller.java                      # Extract CUBRID packages
├── ProcessIO.java                            # I/O handling for processes
├── ProcessIO.StreamReader.java               # Stream reading helper
├── ProcessIO.StreamWriter.java               # Stream writing helper
├── CtpEnvResolver.java                       # Environment variable resolution
└── DockerCtl.java                            # Docker container control utilities
```

### Docker Management (docker/)
```
docker/
├── DockerTesterManager.java                  # Tester Docker environment setup
├── DockerImageBuilder.java                   # Build/cache Docker images (300+ lines)
└── DockerUtils.java                          # Docker availability checks
```

### Tester/Test Execution (tester/)
```
tester/
├── TestOrchestrator.java                     # Test retry/repeat logic (400+ lines)
├── TestHandler.java                          # HTTP /test request handler
├── TestRequest.java                          # DTO for test requests
├── TestResult.java                           # DTO for test results
├── TestStatus.java                           # Enum for test statuses
├── RunMode.java                              # Run mode enumeration
├── HealthHandler.java                        # HTTP /health handler
├── LogStreamHandler.java                     # HTTP /log/<filename> handler
├── HttpResponseWriter.java                   # HTTP response utilities
├── ExecutionTimeParser.java                  # Parse execution time from output
├── ClientAbortDetector.java                  # Detect client aborts
├── SafeIo.java                               # Safe I/O operations
├── ApiServer.java                            # HTTP server factory
└── TesterService.java                        # Tester service interface
```

### Build Cache Management (cache/)
```
cache/
└── BuildCache.java                           # Build package downloading/caching
```

### Git Operations (git/)
```
git/
└── ShellTcSync.java                          # Shell test case repository sync
```

### Configuration (config/)
```
config/
└── Config.java                               # Tester configuration class
```

### Logging (logging/)
```
logging/
├── LogConfig.java                            # Log configuration
├── LogRotationManager.java                   # Log rotation and cleanup
├── RequestContext.java                       # Request-scoped context
└── RequestLogManager.java                    # Request log management
```

### HTTP Utilities (http/)
```
http/
└── HttpUtils.java                            # HTTP utility functions
```

### Logging Utilities (logs/)
```
logs/
├── LogLocator.java                           # Find log files
└── RequestLogBridge.java                     # Bridge for request logging
```

### Reporting (report/)
```
report/
└── ReportHandler.java                        # Report/callback HTTP handler
```

### Implementations (impl/)
```
impl/
└── MattermostSender.java                     # Mattermost notification sender
```

### Interfaces (interfaces/)
```
interfaces/
└── Sender.java                               # Callback notification interface
```

### Testing (test/)
```
test/
└── SingleCommitDistributionTest.java         # Test for distribution logic
```

---

## Configuration Files

```
conf/
├── builder.conf                              # Builder service configuration
│   - listen_port=8089
│   - max_concurrent_builds=4
│   - test_read_timeout_minutes=160
│   - use_docker=true
│   - smart_scheduling_enabled=true
│
└── tester.conf                               # Tester service configuration
    - tester_port=8090
    - max_concurrent_tests=28
    - max_concurrent_tests_heavy_queue=28
    - max_concurrent_tests_post_heavy=42
    - use_docker_tester=true
    - optimized_docker_enabled=true
```

---

## Log Directory Structure

```
log/
├── system/                                   # System-level logs
│   ├── builder.log                           # Builder service main log
│   └── tester.log                            # Tester service main log
│
├── requests/                                 # Request-grouped logs
│   ├── <request-id-1>/
│   │   ├── builder/                          # Build logs for this request
│   │   │   └── builder.log
│   │   ├── builds/                           # Per-commit build scripts
│   │   │   ├── build_abc1234.log
│   │   │   └── build_def5678.log
│   │   └── tests/                            # Per-test execution details
│   │       ├── docker_script_abc1234_test1.sh
│   │       ├── docker_abc1234_test1.log
│   │       ├── docker_abc1234_test1.1.log
│   │       └── ...
│   │
│   ├── <request-id-2>/
│   │   └── ...
│   │
│   └── <request-id-N>/
│       └── ...
│
└── builds/                                   # Build logs (if grouping disabled)
    ├── build_abc1234_<timestamp>.log
    └── build_def5678_<timestamp>.log
```

---

## Temporary Work Directories

```
Builder:
  ~/tmp/builder_work/
  ├── build_abc1234567_<id>/
  │   ├── docker_build.sh
  │   ├── build_abc1234567.log
  │   └── cubrid_abc1234.tar.gz
  │
  └── build_pr_abcd_<id>/
      ├── docker_build_pr.sh
      ├── build_abc1234.log
      └── cubrid_abc1234.tar.gz

Tester:
  ~/tmp/tester_work/
  ├── test_<random>/
  │   ├── docker_/
  │   │   ├── testcases/
  │   │   │   └── <test-case-files>
  │   │   ├── build.tar.gz
  │   │   ├── run_test.sh
  │   │   └── test.result
  │   │
  │   └── KEEP_WORKSPACE (if keep-alive mode)
  │
  ├── cache/
  │   ├── cubrid_abc1234.tar.gz
  │   ├── cubrid_def5678.tar.gz
  │   └── ...
  │
  └── docker_images/
      ├── cubrid-test_abc1234_def5678_setup/
      │   └── setup.sh
      └── ...

Ccache:
  ~/.docker-work/work/.ccache/
  ├── <cache-files>
  ├── logs/
  │   ├── ccache_abc1234.log
  │   └── ccache_def5678.log
  │
  └── tmp/
      └── <temp-files>
```

---

## Build Artifacts

```
Builder output directory:
  ~/tmp/builder_work/build_<commit>_<id>/
  └── cubrid_<commit-short>.tar.gz

Package format:
  - Contains: tar.gz of _install/CUBRID or full build directory
  - Can be downloaded from: http://builder:8089/download/build/<filename>
  - Downloaded to: ~/tmp/tester_work/cache/
```

---

## Documentation Files

```
docs/
├── README.md                                 # Docs entry point
├── architecture/                             # Architecture docs
├── configuration/                            # Configuration docs
├── usage/                                    # API/usage docs
├── features/                                 # Feature docs
├── wal/                                      # Stats WAL docs
└── *_SCHEDULING_*.md                         # Smart scheduling guides
```

---

## Binary/Compiled Files

```
bin/                                          # Shell entrypoints and helper scripts
├── compile.sh
├── start_builder.sh
├── start_tester.sh
└── stop_*.sh
│
lib/                                          # JARs (including the built artifact)
├── builder-tester.jar
├── json.jar
└── jsch-0.1.55.jar
```

---

## Important File Locations by Function

### For Understanding Build Flow
1. **Request handling**: `/Builder.java` lines 132-241 (BuildRequestHandler)
2. **Build orchestration**: `/BuilderTask.java` lines 45-304 (run method)
3. **Docker execution**: `/DockerBuildManager.java` lines 71-259 (buildCubrid)
4. **Build script**: `/DockerBuildManager.java` lines 687-959 (createDockerBuildScript)

### For Understanding Test Distribution
1. **Distribution algorithm**: `/BuilderTask.java` lines 124-187
2. **Worker management**: `/BuilderTask.java` lines 173-183 (round-robin)
3. **Test execution**: `/BuilderTask.java` lines 237-286 (concurrent pools)
4. **Remote execution**: `/BuilderTask.java` lines 570-640+ (runTest)

### For Understanding Test Execution
1. **Request handling**: `/tester/TestHandler.java`
2. **Orchestration**: `/tester/TestOrchestrator.java` lines 45-226 (runTestWithRetry)
3. **Docker execution**: `/tester/TestOrchestrator.java` lines 320-343 (executeDockerTest)
4. **Optimized executor**: `/exec/OptimizedDockerExecutor.java` lines 35-299
5. **Standard executor**: `/exec/StandardDockerExecutor.java` lines 33-347

### For Understanding Caching
1. **Build caching**: `/BuilderTask.java` lines 384-443 (PR build with cache)
2. **Docker images**: `/docker/DockerImageBuilder.java` lines 51-79 (getOrBuildImage)
3. **Ccache setup**: `/DockerBuildManager.java` lines 147-185 (Docker build)

### For Configuration
1. **Builder config**: `/BuilderConfig.java` (all getters)
2. **Tester config**: `/config/Config.java` (all getters)
3. **Config files**: `/conf/builder.conf` and `/conf/tester.conf`

---

## Related External Files

```
CUBRID source:
  ~/cubrid/                                   # CUBRID repository
  
Test cases:
  ~/cubrid-testcases-private-ex/              # Shell test cases
  
Docker images:
  cubridci/cubridci:develop                   # Build image
  cubridci/cubridci:test_shell                # Test image
```

---

## Class Hierarchy

### ExecutorStrategy
```
ExecutorStrategy (interface)
├── DirectExecutor
├── StandardDockerExecutor
└── OptimizedDockerExecutor
```

### Services
```
Builder
  ├── BuilderTask (per request)
  │   └── DockerBuildManager
  │       └── ??? (fallback to direct)
  │
  └── BuildRequestHandler (HTTP handler)

Tester
  ├── TestOrchestrator (per test)
  │   └── ExecutorStrategy implementations
  │       ├── OptimizedDockerExecutor
  │       ├── StandardDockerExecutor
  │       └── DirectExecutor
  │
  ├── TestHandler (HTTP handler)
  ├── HealthHandler (HTTP handler)
  └── LogStreamHandler (HTTP handler)
```

---

## Entry Points

### Builder Service
- **Main class**: `com.navercorp.cubridqa.builder.Builder`
- **Start method**: `main(String[] args)`
- **Config file**: `conf/builder.conf` (default) or args[0]
- **HTTP port**: Configured in builder.conf (default 8089)

### Tester Service
- **Main class**: `com.navercorp.cubridqa.builder.Tester`
- **Start method**: `main(String[] args)`
- **Config file**: `conf/tester.conf` (default) or args[0]
- **HTTP port**: Configured in tester.conf (default 8090)

---

## Dependencies

### Runtime Dependencies
- Java 8+ (with concurrent collections)
- Docker CLI (if Docker mode enabled)
- Git (for repository operations)
- gcc/g++/make (for building)
- ccache (optional, if ccache_enabled=true)

### Library Dependencies
- json.jar (JSON parsing)
- commons-* (Apache commons)
- HTTP server (JDK HttpServer)
