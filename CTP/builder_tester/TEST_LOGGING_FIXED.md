# Test Logging and Response Information - Fixed

## Issues Addressed

### 1. Log File Naming and Organization
**Problem**: Test log files were not uniquely named, causing logs from different test runs to overwrite each other or get mixed up.

**Solution**: Updated log file naming to include commit information:
- Test scripts: `docker_script_<commitShort>_<testName>.sh`
- Test logs: `docker_<commitShort>_<testName>.log`
- Build logs: `build_<commitShort>.log`

### 2. Test Response Information
**Problem**: Test responses didn't include enough information to distinguish between multiple runs of the same test on different commits.

**Solution**: Enhanced test response JSON to include:
- `commit`: Full commit hash
- `commitShort`: Short commit hash (7 chars)
- `test`: Test name
- `execution_mode`: "docker" or "direct"
- `timestamp`: When the test was executed
- `execution_time`: Extracted from test results (when available)
- `status`: pass/fail/execution_error

### 3. Preventing Log File Rotation
**Problem**: FileHandler was creating numbered log files (tester.log.1, tester.log.2) and lock files (.lck).

**Solution**: 
- System logs: Use FileHandler with `count=1` to prevent rotation
- Request logs: Use single file handlers with unique names per test execution

## New Log Structure

```
log/
├── system/
│   ├── builder.log         # Single file, no rotation
│   └── tester.log          # Single file, no rotation
└── requests/
    └── req_20250811_150739_5059/
        ├── request.json
        ├── builder.log     # Request-level builder activities
        ├── tester.log      # Request-level tester activities
        ├── builds/
        │   ├── build_228cd61.log
        │   ├── build_dd32812.log
        │   ├── build_6ea587e.log
        │   └── build_0d7296a.log
        └── tests/
            ├── docker_script_228cd61_csql_hist_01.sh
            ├── docker_228cd61_csql_hist_01.log
            ├── docker_script_228cd61_csql_hist.sh
            ├── docker_228cd61_csql_hist.log
            ├── docker_script_dd32812_bug_bts_9521_1.sh
            ├── docker_dd32812_bug_bts_9521_1.log
            ├── docker_script_6ea587e_csql_hist_01.sh
            ├── docker_6ea587e_csql_hist_01.log
            └── ... (unique file for each test execution)
```

## Example Enhanced Test Response

**Before:**
```json
{
  "execution_mode": "docker",
  "test": "csql_hist_01",
  "status": "fail"
}
```

**After:**
```json
{
  "test": "csql_hist_01",
  "commit": "228cd61a5f4e8b3c2d1a9b7e6c5d4f3a2b1c0d9e8",
  "commitShort": "228cd61",
  "execution_mode": "docker",
  "status": "fail",
  "timestamp": 1734567890123,
  "execution_time": "69s"
}
```

## Key Implementation Changes

### BuilderTask.java
```java
// Added commit information to test request
JSONObject testRequest = new JSONObject()
    .put("buildPackage", buildPackage)
    .put("testPath", testPath)
    .put("testDir", testDir)
    .put("testScript", testScript)
    .put("testName", testName)
    .put("commit", commit)  // Full commit hash
    .put("commitShort", commit.substring(0, 7))  // Short commit
    .put("expectedBuildVersion", commit.substring(0, 7))
    .put("keepAlive", false);
```

### Tester.java
```java
// Extract commit info from request
String commit = request.optString("commit", "unknown");
String commitShort = request.optString("commitShort", commit.substring(0, 7));

// Use commit in file names
Path scriptLogPath = Paths.get(testsDir, 
    String.format("docker_script_%s_%s.sh", commitShort, safeTestName));
Path logFile = Paths.get(testsDir, 
    String.format("docker_%s_%s.log", commitShort, safeTestName));

// Include commit in response
JSONObject response = new JSONObject()
    .put("test", testName)
    .put("commit", commit)
    .put("commitShort", commitShort)
    .put("execution_mode", "docker")
    .put("timestamp", System.currentTimeMillis())
    .put("status", status);
```

### RequestLogManager.java
```java
// Prevent rotation with FileHandler configuration
FileHandler fileHandler = new FileHandler(
    logPath,  // file path
    0,        // no size limit
    1,        // single file only (no rotation)
    true      // append mode
);
```

## Benefits

1. **Unique Log Files**: Each test execution gets its own uniquely named log file
2. **Better Traceability**: Can trace which commit each test ran against
3. **No Overwriting**: Test logs from different commits don't overwrite each other
4. **Rich Response Data**: Test responses include all necessary context
5. **Clean Directory**: No .lck files or numbered rotations
6. **Clear Organization**: Easy to find logs for specific commit/test combinations

## Testing

To verify the fixes:

1. Run multiple builds with tests:
```bash
./bin/test_client.sh
```

2. Check the log structure:
```bash
ls -la ~/cubrid-testtools/CTP/builder_tester/log/requests/req_*/tests/
```

3. Verify unique file names with commit prefixes
4. Confirm no .lck or numbered files are created
5. Check that test responses include commit information

## Cleanup Script

Use the provided cleanup script to remove any old numbered log files:
```bash
./cleanup_logs.sh
```

This implementation ensures that each test execution is properly logged with unique file names that include the commit information, making it easy to debug and trace test results across different builds.
