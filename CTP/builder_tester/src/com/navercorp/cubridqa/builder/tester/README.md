# CUBRID Tester Refactored Components

A comprehensive, modular test execution service for CUBRID database testing. This directory contains the refactored tester components that provide isolated test execution environments using Docker containers or direct host execution, with advanced features like build caching, git synchronization, retry logic, and flaky test detection.

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                       Tester                           │
│                   (Main Entry Point)                   │
└─────────────────┬───────────────────────────────────────┘
                  │
    ┌─────────────┼─────────────┐
    │             │             │
┌───▼────┐   ┌────▼────┐   ┌────▼─────┐
│  HTTP  │   │ Orchestr│   │Execution │
│ Layer  │   │ ation   │   │Strategies│
└────────┘   └─────────┘   └──────────┘
     │            │              │
┌────▼────────────▼──────────────▼────┐
│          Infrastructure Layer       │
│   BuildCache │ ShellTcSync │ etc.   │
└─────────────────────────────────────┘
```

### Component Layers

1. **HTTP Layer** - REST API endpoints for client communication
2. **Orchestration** - Test coordination, retry logic, flaky detection  
3. **Execution Strategies** - Docker/Direct execution implementations
4. **Infrastructure** - Build caching, git sync, logging, utilities

## 📁 Component Directory Structure

```
src/com/navercorp/cubridqa/builder/
├── Tester.java                        # Main entry point with dependency injection
├── tester/
│   ├── README.md                      # This file - comprehensive documentation
│   ├── TestOrchestrator.java          # Test coordination and retry logic
│   ├── TestHandler.java               # HTTP request handling
│   ├── TestRequest.java               # Request DTO with builder pattern
│   ├── TestResult.java                # Response DTO with attempt metadata
│   ├── TestStatus.java                # Status enumeration
│   ├── HealthHandler.java             # Health check endpoint handler
│   ├── LogStreamHandler.java          # Log file streaming handler
│   ├── HttpResponseWriter.java        # HTTP response utilities
│   ├── ApiServer.java                 # API server management
│   ├── TesterService.java            # Service lifecycle management
│   ├── ClientAbortDetector.java      # Client disconnection detection
│   ├── ExecutionTimeParser.java      # Test execution time parsing
│   ├── RunMode.java                   # Test run mode enumeration
│   └── SafeIo.java                    # Safe I/O operations
├── exec/
│   ├── DirectExecutor.java            # Host-based test execution
│   ├── StandardDockerExecutor.java    # Standard containerized execution
│   ├── OptimizedDockerExecutor.java   # Performance-optimized containers
│   ├── CubridInstaller.java           # CUBRID installation management
│   ├── ExecutorStrategy.java          # Execution strategy interface
│   ├── ProcessIO.java                 # Process input/output handling
│   ├── CtpEnvResolver.java            # CTP environment resolution
│   ├── DockerCtl.java                 # Docker control utilities
│   └── EnvScriptFactory.java          # Environment script generation
├── cache/
│   └── BuildCache.java                # Thread-safe build package caching
├── git/
│   └── ShellTcSync.java               # Git repository synchronization
├── config/
│   └── Config.java                    # Configuration class alias
├── docker/
│   ├── DockerTesterManager.java       # Docker container management
│   ├── DockerImageBuilder.java        # Optimized Docker image creation
│   └── DockerUtils.java               # Docker utility operations
├── http/
│   └── HttpUtils.java                 # HTTP utility functions
├── logging/
│   ├── LogConfig.java                 # Logging configuration
│   ├── RequestLogManager.java         # Request-scoped logging
│   ├── RequestContext.java            # Request context management
│   └── LogRotationManager.java        # Log file rotation
└── logs/
    ├── LogLocator.java                # Log file location services
    └── RequestLogBridge.java          # Bridge for request logging
```

## 🚀 Key Features

### Test Execution Modes

#### Run Modes
- **until-pass**: Run tests until they pass or reach maxRuns limit
- **until-fail**: Run tests until they fail or reach maxRuns limit  
- **fixed-runs**: Always run exactly the specified number of times

#### Flaky Test Detection
Tests are automatically marked as flaky when mixed PASS and FAIL results occur across attempts, enabling prioritization of unstable tests.

### Execution Strategies

#### Standard Docker Execution
- **Image**: `cubridci/cubridci:test_shell`
- **Process**: Extracts build package, installs CUBRID, runs test
- **Isolation**: Complete containerized environment

#### Optimized Docker Execution  
- **Image**: Pre-built with CUBRID already installed
- **Process**: Mounts test files, executes directly
- **Performance**: 80-90% reduction in test execution overhead
- **Features**: tmpfs, shared memory, init process

#### Direct Host Execution
- **Fallback**: When Docker unavailable or disabled  
- **Process**: Installs CUBRID on host, runs test directly
- **Isolation**: Process-level only

### Build Package Caching

#### Cache Key Strategy
```
URL + CommitShort + BaselineShort = Unique Cache Entry
```

#### Validation Logic
- File existence and size > 0
- `gzip -t` integrity check
- Metadata matches expected commit/baseline 
- File modification time within acceptable range

#### Thread Safety
- **DOWNLOAD_LOCK**: Serializes downloads per URL
- **Atomic Operations**: Prevents partial file corruption
- **Race Condition Handling**: Multiple requests for same package

### Git Repository Synchronization

#### Synchronization Process
1. **Lock Acquisition**: Obtains `SHELL_TC_SYNC_LOCK` for thread safety
2. **Stale Lock Cleanup**: Removes any stale git lock files
3. **Repository Reset**: Cleans working directory and resets hard
4. **Remote Fallback**: Tries upstream, falls back to origin
5. **Branch Checkout**: Switches to configured branch (default: develop)
6. **Fetch Latest**: Downloads latest commits from remote
7. **Lock Release**: Releases synchronization lock

## 🔌 HTTP API Reference

### POST /test - Execute Test

Execute a test with configurable retry and execution strategies.

#### Request Format
```json
{
  "testPath": "sql/basic/select_test.sh",
  "buildPackage": "http://builds.cubrid.org/latest.tar.gz",
  "testDir": "/home/tester/testcases/sql/basic",
  "testScript": "select_test.sh", 
  "testName": "select_test",
  "expectedBuildVersion": "11.3.0.0001",
  "requestId": "req_20231201_143022_abc123",
  "runMode": "until-pass",
  "minRuns": 1,
  "maxRuns": 2,
  "timeBudgetMs": 300000,
  "keepAlive": false,
  "containerName": "debug_select_test_123456789"
}
```

#### Response Format (JSON)
```json
{
  "test": "select_test",
  "status": "pass",
  "attempts": 2,
  "execution_mode": "docker",
  "timestamp": 1701435022000,
  "commit": "abc123def456789...",
  "commitShort": "abc123d",
  "execution_time": "45s",
  "runMode": "until-pass",
  "flaky": false,
  "attemptLogMetadata": [
    {
      "attempt": 1,
      "logFileName": "docker_abc123d_select_test.log",
      "status": "fail"
    },
    {
      "attempt": 2, 
      "logFileName": "docker_abc123d_select_test.2.log",
      "status": "pass"
    }
  ]
}
```

### GET /health - Service Health Check

Returns service status and configuration.

```json
{
  "status": "healthy",
  "timestamp": 1701435022000,
  "dockerEnabled": true,
  "maxConcurrentTests": 4,
  "testReadTimeoutMinutes": 30
}
```

### GET /log/{filename} - Stream Log File

Stream test execution log files by filename with efficient searching across request directories.

## 📊 Logging and Monitoring

### Multi-Level Logging Architecture

#### System Logs
Global service-level logging: `log/system/tester.log`

#### Request-Scoped Logs
Isolated logging per test request: `log/requests/req_YYYYMMDD_HHMMSS_XXXX/tester.log`

#### Test Execution Logs
Detailed logs from individual test attempts in `log/requests/req_YYYYMMDD_HHMMSS_XXXX/tests/`

**Naming Convention**: 
- `docker_{commitShort}_{testName}[.{attempt}].log`
- `docker_opt_{commitShort}_{testName}[.{attempt}].log`  
- `direct_{commitShort}_{testName}[.{attempt}].log`

### Generated Script Persistence

Test execution scripts are saved for debugging and reproducibility in request log directories.

## ⚙️ Configuration

### Main Configuration File: conf/tester.conf

#### Core Settings
```properties
# Service Configuration
tester.port=8090
tester.workDir=/tmp/tester_work
tester.maxConcurrentTests=4

# Docker Settings  
docker.enabled=true
docker.testImage=cubridci/cubridci:test_shell
docker.optimizedEnabled=true
docker.keepFailedContainers=false

# Build Cache Settings
cache.maxSize=10
cache.workDir=/tmp/tester_cache
cache.backgroundCleanup=true

# Git Repository Settings
git.shellTcDir=/home/cubrid-testtools/CTP/sql
git.shellTcBranch=develop
git.remoteUpstream=upstream
git.remoteOrigin=origin

# Logging Settings
logging.requestGroupingEnabled=true
logging.maxRequestLogs=100
logging.requestsLogDir=log/requests
logging.systemLogDir=log/system

# Test Execution Settings
test.timeoutMinutes=30
test.retryCount=2
test.defaultRunMode=until-pass
```

## 🔧 Developer Guide

### Architecture Patterns

#### Dependency Injection Pattern
```java
// Tester constructor assembles components
public Tester(Config config) {
    // Create infrastructure components
    this.buildCache = new BuildCache(config, logger);
    this.shellTcSync = new ShellTcSync(config, logger);
    this.cubridInstaller = new CubridInstaller();
    
    // Create execution strategies 
    this.directExecutor = new DirectExecutor(config, buildCache, shellTcSync, cubridInstaller);
    this.standardDockerExecutor = new StandardDockerExecutor(config, buildCache, shellTcSync);
    this.optimizedDockerExecutor = new OptimizedDockerExecutor(config, buildCache, shellTcSync, imageBuilder);
    
    // Create orchestrator
    this.testOrchestrator = new TestOrchestrator(config, directExecutor, standardDockerExecutor, optimizedDockerExecutor, useDocker, dockerManager, dockerUtils);
}
```

#### Strategy Pattern for Execution
```java
public interface ExecutorStrategy {
    TestResult execute(TestRequest request, Path workDir, Logger testLogger) throws Exception;
}

// Implementations:
// - DirectExecutor: Host-based execution
// - StandardDockerExecutor: Standard containerized execution
// - OptimizedDockerExecutor: Performance-optimized containers
```

#### Builder Pattern for DTOs
```java
TestResult result = TestResult.builder()
    .testName(request.getTestName())
    .status(TestStatus.PASS)
    .executionMode("docker")
    .executionTime("45s")
    .addAttemptLogFile(logFilePath)
    .build();
```

### Extension Points

#### Adding New Execution Strategies
1. **Implement ExecutorStrategy interface**
2. **Register in TestOrchestrator**

#### Custom HTTP Endpoints
1. **Create Handler implementing HttpHandler**
2. **Register in Tester**

#### Build Cache Extensions
1. **Custom Cache Implementation**

### Testing Strategies

#### Unit Testing Components
Mock dependencies and test individual components in isolation.

#### Integration Testing
Test complete request/response flow with embedded tester.

#### Docker Testing  
Test container lifecycle with Docker daemon dependency.

## 🚨 Troubleshooting

### Common Issues

#### Service Won't Start
- Check if port 8090 is in use
- Verify configuration file
- Check permissions

#### Docker Tests Failing  
- Check Docker daemon status
- Test Docker permissions
- Verify image availability
- Check Docker storage space

#### Build Cache Issues
- Check cache directory and disk space
- Clear corrupted cache files
- Verify network connectivity to build server

#### Git Sync Problems
- Check repository status and permissions
- Fix repository state with git reset
- Update remote URLs
- Remove stale lock files

#### Memory Issues
- Increase Java heap size
- Reduce concurrent tests
- Clean up old containers
- Restart service

#### Test Timeouts
- Increase timeout in configuration
- Check for infinite loops in test scripts
- Verify system resources
- Use keep-alive mode for debugging

### Debug Mode

#### Enable Debug Logging
```bash
export TESTER_DEBUG=true
export TESTER_LOG_LEVEL=FINE
```

#### Keep-Alive Debugging
```bash
curl -X POST http://localhost:8090/test -d '{
  "testPath": "failing/debug_test.sh",
  "keepAlive": true,
  "containerName": "debug_session",
  ...
}'

# Connect to container for investigation
docker exec -it debug_session bash
```

#### Request Tracing
Follow request-specific logs using request ID for detailed tracing.

## 📈 Performance Optimization

### Tuning Guidelines

#### Hardware Requirements
**Recommended**: 8+ cores, 16GB+ RAM, 100GB+ NVMe SSD, 10Gbps network

#### Configuration Optimization
```properties
# High-throughput configuration
tester.maxConcurrentTests=16
tester.httpThreadPoolSize=32

# Docker performance
docker.optimized.shmSize=8g
docker.optimized.tmpfsSize=8G
docker.optimized.cpuLimit=4.0
docker.optimized.memoryLimit=16g
```

### Monitoring Performance

#### Key Metrics to Track
- Request Rate: Tests per second
- Response Time: P50, P95, P99 latencies  
- Error Rate: Failed tests percentage
- Cache Hit Rate: Build package cache efficiency
- Docker Metrics: Container creation/destruction time
- Resource Usage: CPU, memory, disk utilization

## 🔒 Security

### Container Security
- Run containers with limited privileges
- Apply resource limits
- Use read-only root filesystem where possible

### Network Security
- Use firewall rules to restrict access
- Consider TLS termination via reverse proxy

### Secrets Management
- Use Docker secrets for sensitive data
- Secure environment variable handling

## 🚀 Deployment Guide

### Production Deployment

#### System Requirements
Install Java 8+, Docker, Git, and configure Docker permissions.

#### Service Installation
Create service user, configure environment, compile and start services.

#### Systemd Service
Create systemd unit file for automatic startup and management.

### High Availability Setup

#### Load Balancer Configuration
Use HAProxy or similar for distributing load across multiple tester instances.

#### Shared Storage
Configure NFS or similar for shared build cache across nodes.

### Backup and Recovery

#### Configuration Backup
Regular backup of configuration files and logs.

#### Disaster Recovery
Procedures for restoring from backups and restarting services.

---

*This documentation covers the comprehensive refactored CUBRID Tester components with all features implemented through the modular architecture. For additional details, refer to the main project README and documentation in the docs/ directory.*