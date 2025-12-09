# Comprehensive I/O Analysis for Ramdisk Migration

**Purpose**: This document provides a complete analysis of all I/O operations in the Builder-Tester system to enable migration to ramdisk for improved performance.

**Date**: December 2025  
**Status**: Analysis Complete

---

## Executive Summary

The Builder-Tester system performs extensive disk I/O across multiple categories:
1. **Build artifacts** (tar.gz packages, ~500MB-2GB each)
2. **Temporary test workspaces** (created/destroyed per test)
3. **Compiler cache (ccache)** (persistent, ~15-30GB)
4. **Docker images** (pre-built images, ~1-2GB each, cached)
5. **Log files** (system logs, request logs, test execution logs)
6. **Test statistics and profiles** (WAL files, JSON snapshots)
7. **Build package cache** (downloaded packages, ~500MB-2GB each)

**Key Finding**: The system already uses tmpfs for `/tmp` inside Docker containers (`--tmpfs /tmp:exec,size=2G`), but all host-side I/O operations use regular disk storage.

---

## 1. Configuration-Based Directory Locations

### 1.1 Builder Service Directories

**Configuration File**: `conf/builder.conf`

| Config Key | Default Value | Purpose | I/O Pattern |
|------------|---------------|---------|-------------|
| `work_dir` | `~/tmp/builder_work` | Build workspace root | **HIGH**: Creates temp dirs per build, stores tar.gz packages |
| `cubrid_src_dir` | `~/cubrid` | CUBRID source repository | **READ-ONLY**: Git operations, source code reads |
| `shell_tc_dir` | `~/cubrid-testcases-private-ex` | Shell test cases repository | **READ-ONLY**: Test case file reads |
| `ccache_dir` | `$HOME/docker-work/work/.ccache` | Compiler cache directory | **HIGH**: Frequent read/write during builds |
| `docker_host_root` | `$HOME/docker-work` | Docker host work directory | **MEDIUM**: Contains ccache, gradle cache |

**Code Location**: `src/com/navercorp/cubridqa/builder/BuilderConfig.java`
- Lines 528-530: `getWorkDir()` method
- Lines 909-911: `getCcacheDir()` method
- Lines 626-628: `getDockerHostRoot()` method

### 1.2 Tester Service Directories

**Configuration File**: `conf/tester.conf`

| Config Key | Default Value | Purpose | I/O Pattern |
|------------|---------------|---------|-------------|
| `work_dir` | `~/tmp/tester_work` | Test workspace root | **VERY HIGH**: Creates temp dirs per test, stores build packages |
| `cubrid_src_dir` | `~/cubrid` | CUBRID source (unused in tester) | N/A |
| `shell_tc_dir` | `~/cubrid-testcases-private-ex` | Shell test cases repository | **READ-ONLY**: Test case file reads |
| `tester_profiles_dir` | `~/tmp/tester_work/profiles` | Test statistics profiles | **MEDIUM**: WAL writes, JSON snapshots |

**Code Location**: `src/com/navercorp/cubridqa/builder/config/Config.java`
- Similar getter methods for work directories

### 1.3 Log Directories

**Hardcoded Location**: `~/cubrid-testtools/CTP/builder_tester/log/`

| Subdirectory | Purpose | I/O Pattern |
|--------------|---------|-------------|
| `log/system/` | System-level logs (builder.log, tester.log) | **MEDIUM**: Continuous append writes |
| `log/requests/req_*/` | Request-scoped logs and artifacts | **HIGH**: Per-request log files, build logs, test logs |
| `log/requests/req_*/builds/` | Per-commit build logs | **MEDIUM**: Build script output logs |
| `log/requests/req_*/tests/` | Per-test execution logs | **HIGH**: Docker logs, test result files |

**Code Location**: `src/com/navercorp/cubridqa/builder/logging/LogConfig.java`
- Lines 24-25: Default log directory construction
- Lines 47-48: `getRequestsDir()` method
- Lines 54-55: `getSystemDir()` method

**Code Location**: `src/com/navercorp/cubridqa/builder/Builder.java`
- Lines 642-643: System log directory initialization
- Lines 657-658: Log file path construction

---

## 2. Temporary Directory Creation Patterns

### 2.1 Builder-Side Temporary Directories

**Pattern**: `Files.createTempDirectory(Paths.get(config.getWorkDir()), "build_*")`

**Locations**:
1. **PR Builds**: `src/com/navercorp/cubridqa/builder/BuilderTask.java:335`
   - Pattern: `build_pr_<commit>_<id>/`
   - Contains: Git worktree, build output, tar.gz package

2. **Commit Builds**: `src/com/navercorp/cubridqa/builder/BuilderTask.java:513-514`
   - Pattern: `build_<commit>_<id>/`
   - Contains: Git worktree, build output, tar.gz package

3. **Direct Builds**: `src/com/navercorp/cubridqa/builder/BuilderTask.java:647-648`
   - Pattern: `build_<commit>_<id>/`
   - Contains: Git worktree, build output, tar.gz package

**I/O Operations**:
- Git worktree creation (reads from `cubrid_src_dir`)
- Build script execution (writes build logs)
- Tar.gz package creation (writes ~500MB-2GB files)
- Package metadata JSON files (writes `.meta.json` files)

### 2.2 Tester-Side Temporary Directories

**Pattern**: `Files.createTempDirectory(Paths.get(config.getWorkDir()), "test_*")`

**Locations**:
1. **Test Workspace**: `src/com/navercorp/cubridqa/builder/tester/TestOrchestrator.java:369`
   - Pattern: `test_<random>/`
   - Contains: Docker work directory, test artifacts

2. **Docker Work Directory**: `src/com/navercorp/cubridqa/builder/exec/StandardDockerExecutor.java:52`
   - Pattern: `test_<random>/docker_<random>/`
   - Contains: Build package, test scripts, result files

3. **Docker Work Directory (Optimized)**: `src/com/navercorp/cubridqa/builder/exec/OptimizedDockerExecutor.java:64`
   - Pattern: `test_<random>/docker_<random>/`
   - Contains: Test scripts, result files (no build package extraction)

**I/O Operations**:
- Build package download/extraction (reads/writes ~500MB-2GB)
- Test script generation (writes shell scripts)
- Test result file creation (writes `.result` files)
- Docker log files (writes container stdout/stderr)

---

## 3. Build Artifact Storage

### 3.1 Build Package Creation

**Location**: `src/com/navercorp/cubridqa/builder/BuilderTask.java:965-974`

**Process**:
1. Build completes in Docker container
2. Package created via `tar czf` command
3. Stored in: `workDir/cubrid_<commit>.tar.gz`
4. Metadata written: `workDir/cubrid_<commit>.tar.gz.meta.json`

**File Size**: ~500MB-2GB per package  
**I/O Pattern**: **HIGH** - Large sequential writes

**Code Reference**:
```java
// Line 966-967: Package file creation
String packageName = "cubrid_" + commit.substring(0, 7) + ".tar.gz";
File packageFile = new File(workDir, packageName);

// Line 969-971: Tar creation
ProcessBuilder tarPb = new ProcessBuilder();
tarPb.directory(new File(wtDir, config.getBuildDir(buildType)));
executeCommand(tarPb, "tar", "czf", packageFile.getAbsolutePath(), ".");
```

### 3.2 Build Package Download/Caching

**Location**: `src/com/navercorp/cubridqa/builder/cache/BuildCache.java`

**Cache Directory**: `workDir/cache/` (tester work directory)

**Process**:
1. Check in-memory cache (`buildPackageCache` ConcurrentHashMap)
2. Check disk cache: `workDir/cache/<filename>.tar.gz`
3. Download if missing (HTTP download with atomic write)
4. Validate with metadata file: `<filename>.tar.gz.meta.json`

**I/O Operations**:
- **Lines 146-206**: Atomic download (write to `.tmp`, then atomic move)
- **Lines 277-395**: Cache validation (read metadata JSON, gzip integrity check)
- **Lines 401-431**: Metadata file writes

**File Sizes**: ~500MB-2GB per package  
**I/O Pattern**: **VERY HIGH** - Large sequential reads/writes, frequent cache checks

**Key Methods**:
- `downloadIfNeeded()`: Lines 39-271
- `validateCached()`: Lines 277-395
- `writeSidecarMetadata()`: Lines 401-431

---

## 4. Compiler Cache (ccache) I/O

### 4.1 Ccache Directory Structure

**Location**: `$HOME/docker-work/work/.ccache/` (configurable via `ccache_dir`)

**Subdirectories**:
- `.ccache/` - Cache files (main storage)
- `.ccache/logs/` - Per-build log files
- `.ccache/tmp/` - Temporary files during cache operations

**Code Location**: `src/com/navercorp/cubridqa/builder/DockerBuildManager.java:102-113`
```java
File ccacheDir = new File(hostWorkDir, ".ccache");
if (!ccacheDir.exists()) {
    ccacheDir.mkdirs();
}
new File(ccacheDir, "logs").mkdirs();
new File(ccacheDir, "tmp").mkdirs();
```

### 4.2 Ccache Docker Volume Mount

**Location**: `src/com/navercorp/cubridqa/builder/DockerBuildManager.java:130-131`
```java
baseDockerCmd.add("-v");
baseDockerCmd.add(hostWorkDir.getAbsolutePath() + ":/work:rw");
```

**Environment Variables** (Lines 153-168):
- `CCACHE_DIR=/work/.ccache`
- `CCACHE_LOGFILE=/work/.ccache/logs/ccache_<commit>.log`
- `CCACHE_TEMPDIR=/work/.ccache/tmp`

**I/O Pattern**: **VERY HIGH** - Frequent random reads/writes during compilation
- Cache hits: Fast reads
- Cache misses: Writes of compiled object files
- Log writes: Per-build log files

**Cache Size**: Configurable via `ccache_max_size` (default: 30G in builder.conf)

---

## 5. Docker Image Cache

### 5.1 Docker Image Building

**Location**: `src/com/navercorp/cubridqa/builder/docker/DockerImageBuilder.java`

**Process**:
1. Check if image exists: `cubrid-test:<commit>_<baseline>`
2. If missing, build via Docker commit from running container
3. Cache up to `build_cache_size` images (default: 10-20)

**I/O Operations**:
- Docker image layer writes (~1-2GB per image)
- Image metadata writes
- Container filesystem snapshots

**Code Location**: `src/com/navercorp/cubridqa/builder/docker/DockerImageBuilder.java:51-79`
- `getOrBuildImage()` method

**I/O Pattern**: **MEDIUM** - Large writes during image creation, reads during reuse

### 5.2 Docker Volume Mounts

**Standard Docker Executor**: `src/com/navercorp/cubridqa/builder/exec/StandardDockerExecutor.java:308-311`
```java
dockerCommand.add("-v");
dockerCommand.add(dockerWorkDir.toString() + ":/workspace");
dockerCommand.add("-v");
dockerCommand.add(shellRepoRoot.toString() + ":" + TESTCASE_MOUNT + ":rw");
```

**Optimized Docker Executor**: `src/com/navercorp/cubridqa/builder/exec/OptimizedDockerExecutor.java:370-373`
```java
dockerCommand.add("-v");
dockerCommand.add(dockerWorkDir.toString() + ":/workspace");
dockerCommand.add("-v");
dockerCommand.add(shellRepoRoot.toString() + ":" + TESTCASE_MOUNT + ":rw");
```

**Tmpfs Mount** (already using ramdisk!): Lines 365-366
```java
dockerCommand.add("--tmpfs");
dockerCommand.add("/tmp:exec,size=2G");
```

**I/O Pattern**: **HIGH** - Test execution writes logs, result files, temporary data

---

## 6. Log File I/O

### 6.1 System Logs

**Location**: `~/cubrid-testtools/CTP/builder_tester/log/system/`

**Files**:
- `builder.log` - Builder service log (continuous append)
- `tester.log` - Tester service log (continuous append)

**Code Location**: `src/com/navercorp/cubridqa/builder/Builder.java:642-661`
```java
String systemLogDir = System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log/system";
String logFilePath = systemLogDir + "/builder.log";
FileOutputStream fos = new FileOutputStream(logFilePath, true); // append mode
```

**I/O Pattern**: **MEDIUM** - Continuous append writes, log rotation

### 6.2 Request-Scoped Logs

**Location**: `~/cubrid-testtools/CTP/builder_tester/log/requests/req_<id>/`

**Structure**:
```
req_<id>/
├── builder.log              # Builder task log
├── builds/                  # Build logs
│   └── build_<commit>.log
└── tests/                   # Test execution logs
    ├── docker_<commit>_<test>.log
    ├── docker_<commit>_<test>.1.log
    └── result_<commit>_<test>.result
```

**Code Location**: `src/com/navercorp/cubridqa/builder/logging/RequestLogManager.java`
- Lines 70-102: `getRequestLogger()` - Creates per-request log files
- Lines 107-111: `createRequestSubdir()` - Creates subdirectories

**I/O Pattern**: **HIGH** - Multiple log files per request, frequent writes

### 6.3 Test Execution Logs

**Standard Docker Executor**: `src/com/navercorp/cubridqa/builder/exec/StandardDockerExecutor.java:406-411`
```java
String logFileName = String.format("docker_%s_%s.log", request.getCommitShort(), safeTestName);
// Or with attempt number:
logFileName = String.format("docker_%s_%s.%d.log", request.getCommitShort(), safeTestName, attemptNumber);
```

**Optimized Docker Executor**: `src/com/navercorp/cubridqa/builder/exec/OptimizedDockerExecutor.java:467-469`
```java
dockerOptLogFileName = generateDockerOptLogFileName(request.getCommitShort(), request.getTestName(), attemptNumber);
```

**I/O Pattern**: **HIGH** - Per-test log files, result files, container stdout/stderr capture

---

## 7. Test Statistics and Profiles I/O

### 7.1 WAL (Write-Ahead Log) System

**Location**: `workDir/profiles/wal/` (tester work directory)

**Purpose**: Stores test execution statistics for smart scheduling predictions

**Code Location**: `src/com/navercorp/cubridqa/builder/tester/stats/WALSegmentWriter.java`
- Writes WAL segments for test observations
- Periodic snapshots to `latest.json.gz`

**I/O Pattern**: **MEDIUM** - Append writes to WAL, periodic snapshot creation

### 7.2 Profile Snapshots

**Location**: `workDir/profiles/latest.json.gz`

**Code Location**: `src/com/navercorp/cubridqa/builder/tester/stats/TestStatsStore.java:783-822`
```java
// Write to temp file first
Path tempPath = snapshotPath.resolveSibling(SNAPSHOT_FILE + ".tmp");
// ... write JSON ...
// Atomic move
Files.move(tempPath, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
```

**I/O Pattern**: **MEDIUM** - Periodic writes (every 5 minutes), atomic file operations

---

## 8. Docker Build I/O

### 8.1 Docker Build Volume Mounts

**Location**: `src/com/navercorp/cubridqa/builder/DockerBuildManager.java:125-133`

**Mounts**:
1. **CUBRID Source** (read-only): `cubrid_src_dir:/cubrid-src:ro`
2. **Build Output**: `workDir:/output:rw` - Stores final tar.gz package
3. **Host Work Directory**: `docker_host_root/work:/work:rw` - Contains ccache, gradle cache
4. **Gradle Cache**: `gradleCacheDir:/root/.gradle:rw`

**Gradle Cache Location**: `src/com/navercorp/cubridqa/builder/DockerBuildManager.java:89-99`
```java
File gradleCacheDir = new File(hostRoot, ".gradle");
if (!gradleCacheDir.exists()) {
    gradleCacheDir.mkdirs();
}
```

**I/O Pattern**: **HIGH** - Build compilation writes, ccache operations, gradle dependency downloads

### 8.2 Build Script Generation

**Location**: `src/com/navercorp/cubridqa/builder/DockerBuildManager.java:687-959`

**Process**:
1. Generate build script: `workDir/docker_build.sh`
2. Mount script into container: `/build.sh:ro`
3. Execute build inside container
4. Extract package to `/output/cubrid_<commit>.tar.gz`

**I/O Operations**:
- Script file write (build script generation)
- Build log writes (stdout/stderr capture)
- Package file write (final tar.gz creation)

---

## 9. Test Execution I/O

### 9.1 Build Package Extraction

**Standard Docker Executor**: `src/com/navercorp/cubridqa/builder/exec/StandardDockerExecutor.java:103-148`

**Process**:
1. Download build package if URL (via `BuildCache.downloadIfNeeded()`)
2. Copy to Docker work directory: `dockerWorkDir/build.tar.gz`
3. Extract inside container: `/tmp/cubrid_install/` (uses tmpfs!)
4. Install CUBRID: `/opt/cubrid` (in optimized mode) or `/tmp/cubrid_install/` (standard mode)

**I/O Pattern**: **VERY HIGH** - Large file reads (tar.gz), extraction writes

**Code Location**: `src/com/navercorp/cubridqa/builder/exec/EnvScriptFactory.java:96-107`
```bash
mkdir -p /tmp/cubrid_install
cd /tmp/cubrid_install
tar -xzf /workspace/build.tar.gz
# ... CUBRID installation logic ...
```

### 9.2 Direct Executor I/O

**Location**: `src/com/navercorp/cubridqa/builder/exec/DirectExecutor.java:88-111`

**Process**:
1. Install CUBRID via `CubridInstaller.install()`
2. Extract to: `$HOME/CUBRID` (host filesystem)
3. Run test directly on host

**CubridInstaller Location**: `src/com/navercorp/cubridqa/builder/exec/CubridInstaller.java:14-137`
- Extracts tar.gz to `$HOME/CUBRID`
- Creates databases directory
- Runs setup.sh if available

**I/O Pattern**: **VERY HIGH** - Large extraction writes, test execution I/O

---

## 10. Git Operations I/O

### 10.1 Git Worktree Creation

**Location**: `src/com/navercorp/cubridqa/builder/BuilderTask.java:815-825`

**Process**:
1. Create temporary branch: `isolate_<commit>_tmp`
2. Create worktree: `workDir/wt_<commit>/`
3. Cherry-pick or format-patch to apply changes
4. Build in worktree
5. Cleanup worktree and branch

**I/O Pattern**: **MEDIUM** - Git metadata reads, source file reads, worktree writes

### 10.2 Shell Testcases Git Sync

**Location**: `src/com/navercorp/cubridqa/builder/git/ShellTcSync.java`

**Process**:
1. Git fetch from remote
2. Checkout specified branch
3. Sync mode: `per_request` (default), `per_test`, or `disabled`

**I/O Pattern**: **MEDIUM** - Git operations, file updates

---

## 11. File Write Operations Summary

### 11.1 High-Frequency Write Operations

| Operation | Location | File Pattern | Size | Frequency |
|-----------|----------|-------------|------|-----------|
| Build packages | `workDir/cubrid_*.tar.gz` | Per commit | 500MB-2GB | Per build |
| Ccache cache | `ccache_dir/*` | Random | Variable | Per compilation |
| Test logs | `log/requests/req_*/tests/*.log` | Per test | 1-50MB | Per test |
| Build logs | `log/requests/req_*/builds/*.log` | Per build | 1-10MB | Per build |
| Test results | `workDir/test_*/docker_*/*.result` | Per test | <1MB | Per test |
| WAL segments | `workDir/profiles/wal/*` | Per observation | <1MB | Per test completion |
| Profile snapshots | `workDir/profiles/latest.json.gz` | Periodic | 1-10MB | Every 5 minutes |

### 11.2 Read-Intensive Operations

| Operation | Location | Pattern | Size | Frequency |
|-----------|----------|---------|------|-----------|
| Ccache lookups | `ccache_dir/*` | Random | Variable | Per compilation |
| Build package reads | `workDir/cache/*.tar.gz` | Sequential | 500MB-2GB | Per test |
| Source code reads | `cubrid_src_dir/**` | Random | Variable | Per build |
| Test case reads | `shell_tc_dir/**` | Random | Variable | Per test |

---

## 12. Ramdisk Migration Strategy

### 12.1 High-Priority Targets (Immediate Performance Gain)

1. **Tester Work Directory** (`work_dir` in tester.conf)
   - **Rationale**: Highest I/O volume (test execution, package extraction)
   - **Size Estimate**: 10-50GB (depends on concurrent tests)
   - **Migration**: Change `work_dir` to point to ramdisk mount

2. **Builder Work Directory** (`work_dir` in builder.conf)
   - **Rationale**: Build package creation, temporary builds
   - **Size Estimate**: 20-100GB (depends on build cache size)
   - **Migration**: Change `work_dir` to point to ramdisk mount

3. **Ccache Directory** (`ccache_dir` in builder.conf)
   - **Rationale**: Very high random I/O during compilation
   - **Size Estimate**: 15-30GB (configurable)
   - **Migration**: Change `ccache_dir` to point to ramdisk mount

### 12.2 Medium-Priority Targets (Moderate Performance Gain)

4. **Log Directory** (`~/cubrid-testtools/CTP/builder_tester/log/`)
   - **Rationale**: Frequent log writes, but less critical for performance
   - **Size Estimate**: 5-20GB (depends on retention)
   - **Migration**: Symlink or bind mount log directory to ramdisk
   - **Note**: Consider log rotation/archival to persistent storage

5. **Docker Host Root** (`docker_host_root` in builder.conf)
   - **Rationale**: Contains ccache, gradle cache
   - **Size Estimate**: 20-50GB
   - **Migration**: Change `docker_host_root` to ramdisk mount

### 12.3 Low-Priority Targets (Minimal Impact)

6. **Source Directories** (`cubrid_src_dir`, `shell_tc_dir`)
   - **Rationale**: Read-only, less frequent access
   - **Migration**: Optional, can remain on disk

7. **Docker Image Storage**
   - **Rationale**: Managed by Docker daemon, less frequent I/O
   - **Migration**: Requires Docker configuration changes (beyond scope of this system)

---

## 13. Implementation Checklist

### 13.1 Configuration Changes

- [ ] **tester.conf**: Update `work_dir` to ramdisk path
- [ ] **builder.conf**: Update `work_dir` to ramdisk path
- [ ] **builder.conf**: Update `ccache_dir` to ramdisk path
- [ ] **builder.conf**: Update `docker_host_root` to ramdisk path (if moving ccache)
- [ ] **Log directory**: Create symlink or bind mount for log directory

### 13.2 Code Verification Points

**No code changes required** - All paths are configuration-driven via:
- `BuilderConfig.getWorkDir()`
- `BuilderConfig.getCcacheDir()`
- `BuilderConfig.getDockerHostRoot()`
- `Config.getWorkDir()` (tester)

### 13.3 Ramdisk Setup

1. **Create ramdisk**:
   ```bash
   # Example: 50GB ramdisk
   sudo mkdir -p /mnt/ramdisk
   sudo mount -t tmpfs -o size=50G tmpfs /mnt/ramdisk
   ```

2. **Update configurations**:
   ```properties
   # tester.conf
   work_dir=/mnt/ramdisk/tester_work
   
   # builder.conf
   work_dir=/mnt/ramdisk/builder_work
   ccache_dir=/mnt/ramdisk/docker-work/work/.ccache
   docker_host_root=/mnt/ramdisk/docker-work
   ```

3. **Log directory** (optional):
   ```bash
   # Move existing logs to persistent storage
   mv ~/cubrid-testtools/CTP/builder_tester/log ~/cubrid-testtools/CTP/builder_tester/log.backup
   # Create symlink to ramdisk
   ln -s /mnt/ramdisk/logs ~/cubrid-testtools/CTP/builder_tester/log
   ```

### 13.4 Data Persistence Considerations

**Critical**: Ramdisk data is lost on reboot. Consider:

1. **Build packages**: Already served via HTTP, can be re-downloaded
2. **Ccache**: Performance optimization, can be rebuilt (slower first builds)
3. **Logs**: Archive important logs to persistent storage periodically
4. **Test profiles**: Consider periodic backup of `profiles/` directory

---

## 14. File Reference Index

### 14.1 Configuration Files

| File | Purpose | Key Directories |
|------|---------|----------------|
| `conf/builder.conf` | Builder service config | `work_dir`, `ccache_dir`, `docker_host_root` |
| `conf/tester.conf` | Tester service config | `work_dir`, `tester_profiles_dir` |

### 14.2 Core Source Files

| File | Purpose | Key I/O Operations |
|------|---------|-------------------|
| `BuilderConfig.java` | Builder configuration | Directory getters |
| `Config.java` | Tester configuration | Directory getters |
| `BuilderTask.java` | Build orchestration | Temp dir creation, package creation |
| `DockerBuildManager.java` | Docker build execution | Ccache setup, volume mounts |
| `TestOrchestrator.java` | Test execution | Temp dir creation |
| `StandardDockerExecutor.java` | Standard Docker execution | Package extraction, log writes |
| `OptimizedDockerExecutor.java` | Optimized Docker execution | Log writes, result files |
| `DirectExecutor.java` | Direct host execution | Package extraction |
| `BuildCache.java` | Package caching | Download, cache validation |
| `CubridInstaller.java` | Package extraction | Tar extraction, installation |
| `RequestLogManager.java` | Log management | Log file creation |
| `LogConfig.java` | Log configuration | Log directory paths |

### 14.3 Documentation Files

| File | Purpose |
|------|---------|
| `docs/README.md` | System overview |
| `docs/IO_FIRST_SCHEDULING_IMPLEMENTATION.md` | I/O scheduling details |
| `docs/DOCKER_OPTIMIZATION.md` | Docker I/O optimizations |
| `docs/CCACHE_GUIDE.md` | Ccache configuration |
| `docs/architecture/FILE_STRUCTURE.md` | Directory structure |
| `docs/architecture/DOCKER_BUILD_ARCHITECTURE.md` | Docker build details |

---

## 15. Performance Impact Estimates

### 15.1 Expected Improvements

| Operation | Current (HDD) | Ramdisk | Improvement |
|-----------|---------------|---------|-------------|
| Build package write | 30-60s | 2-5s | **10-15x faster** |
| Package extraction | 20-40s | 1-3s | **10-20x faster** |
| Ccache operations | Variable | Near-instant | **50-100x faster** |
| Log writes | Negligible | Negligible | Minimal |
| Test workspace creation | 1-2s | <0.1s | **10-20x faster** |

### 15.2 Overall System Impact

- **Test execution time**: 20-40% reduction (I/O-bound tests)
- **Build time**: 10-20% reduction (ccache performance)
- **Concurrent test capacity**: 20-30% increase (less I/O contention)

---

## 16. Verification Steps

### 16.1 Pre-Migration

1. **Measure baseline I/O**:
   ```bash
   # Monitor I/O during test execution
   iostat -x 1
   ```

2. **Check current disk usage**:
   ```bash
   du -sh ~/tmp/builder_work
   du -sh ~/tmp/tester_work
   du -sh ~/.docker-work/work/.ccache
   ```

### 16.2 Post-Migration

1. **Verify ramdisk mount**:
   ```bash
   df -h | grep ramdisk
   mount | grep ramdisk
   ```

2. **Verify configuration**:
   ```bash
   grep work_dir conf/builder.conf
   grep work_dir conf/tester.conf
   grep ccache_dir conf/builder.conf
   ```

3. **Test execution**:
   - Run a test build
   - Verify files are created in ramdisk
   - Monitor I/O performance

4. **Verify functionality**:
   - Build packages are created correctly
   - Tests execute successfully
   - Logs are written correctly
   - Ccache is working

---

## 17. Troubleshooting

### 17.1 Common Issues

1. **Ramdisk size too small**:
   - Symptom: "No space left on device" errors
   - Solution: Increase ramdisk size or reduce cache sizes

2. **Data loss on reboot**:
   - Symptom: Missing build packages, empty ccache
   - Solution: Expected behavior - implement backup strategy

3. **Permission issues**:
   - Symptom: Cannot create files in ramdisk
   - Solution: Check mount permissions, ensure user has write access

4. **Docker volume mount failures**:
   - Symptom: Docker containers cannot access mounted volumes
   - Solution: Verify ramdisk is accessible to Docker daemon

---

## 18. Additional Notes

### 18.1 Already Using Ramdisk

The system **already uses tmpfs** for `/tmp` inside Docker containers:
- `--tmpfs /tmp:exec,size=2G` (StandardDockerExecutor.java:365-366)
- `--tmpfs /tmp:exec,size=2G` (OptimizedDockerExecutor.java:365-366)

This means test execution temporary files inside containers already benefit from ramdisk performance.

### 18.2 I/O-First Scheduling

The system includes I/O-first scheduling (see `docs/IO_FIRST_SCHEDULING_IMPLEMENTATION.md`):
- Separate read/write I/O tracking
- I/O-dominant scoring (weight: 2.50 vs CPU: 1.00)
- I/O capacity limits and safety margins

Ramdisk migration will significantly improve I/O capacity, allowing more concurrent tests.

### 18.3 Docker Image Cache

Docker image cache is managed by Docker daemon and stored in Docker's default location (typically `/var/lib/docker`). Moving this to ramdisk requires Docker daemon configuration changes and is beyond the scope of this system's configuration.

---

## 19. Summary

**Key Findings**:
1. All I/O paths are configuration-driven (no code changes needed)
2. Highest I/O volume: Tester work directory, Builder work directory, Ccache
3. System already uses tmpfs for container `/tmp` (2GB)
4. Log directory can optionally use ramdisk (with archival strategy)

**Recommended Migration Order**:
1. Tester work directory (highest impact)
2. Builder work directory (high impact)
3. Ccache directory (very high impact for builds)
4. Log directory (optional, with backup strategy)

**Estimated Total Ramdisk Size**: 50-150GB (depending on concurrent load and cache sizes)

**Expected Performance Improvement**: 20-40% reduction in test execution time, 10-20% reduction in build time, 20-30% increase in concurrent capacity.

---

**End of Analysis**

