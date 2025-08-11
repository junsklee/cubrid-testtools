# Dual Logging Implementation - Complete

## Overview
This document describes the completed implementation of dual logging for the Builder-Tester system. All logs are now written to BOTH system-level directories and request-specific directories, providing both global visibility and request-scoped organization.

## Key Implementation Details

### 1. Dual Logging Architecture

The system now writes logs to two locations simultaneously:

#### System Logs (Global)
```
~/cubrid-testtools/CTP/builder_tester/log/system/
├── builder.log    # All builder activities across all requests
└── tester.log     # All tester activities across all requests
```

#### Request Logs (Per Request)
```
~/cubrid-testtools/CTP/builder_tester/log/requests/req_YYYYMMDD_HHMMSS_XXXX/
├── builder.log    # Builder activities for this specific request
├── tester.log     # Tester activities for this specific request
├── request.json   # Original request data
├── builds/        # Build logs for each commit
│   ├── build_abc123.log
│   └── build_def456.log
└── tests/         # Test logs for each test
    ├── test_csql_hist.json
    ├── docker_script_csql_hist.sh
    └── docker_csql_hist.log
```

### 2. Code Changes Implemented

#### RequestLogManager.java
- `getRequestLogger()` method creates dual loggers with two FileHandlers:
  - One for the request-specific log: `/requests/req_XXX/component.log`
  - One for the system log: `/system/component.log`
- Both handlers write simultaneously to provide complete visibility

#### Builder.java
- Initializes RequestLogManager with configuration
- Generates unique request IDs for each build request
- Passes request ID through to BuilderTask
- Performs cleanup of old logs and tar files

#### BuilderTask.java
- Uses request-scoped logger (`taskLogger`) throughout execution
- All log messages include request ID context
- Writes build logs to `/requests/req_XXX/builds/`
- Includes request ID in test requests sent to Tester

#### Tester.java
- Creates request-specific logger when request ID is provided
- Uses dual logging for all test activities
- Passes logger through to all helper methods:
  - `runTestInDocker()`
  - `runTestDirectly()`
  - `installCubrid()`
- Writes test logs to `/requests/req_XXX/tests/`

#### DockerBuildManager.java
- Uses request-scoped directories for build logs
- Writes to `/requests/req_XXX/builds/build_*.log`

### 3. Configuration

#### builder.conf
```properties
max_request_logs=5              # Keep only last 5 request directories
max_tar_files=10                # Keep only last 10 tar files
enable_request_grouping=true    # Enable request-based log grouping
```

#### tester.conf
```properties
max_request_logs=5              # Keep only last 5 request directories
enable_request_grouping=true    # Enable request-based log grouping
```

### 4. Log Rotation and Cleanup

The system automatically manages disk space:

- **Request Logs**: Keeps only the last N request directories (default: 5)
- **Tar Files**: Keeps only the last N tar files in `/tmp/builder_work/` (default: 10)
- **Cleanup Timing**: Performed at the start of each build request
- **Metadata Tracking**: `.metadata.json` tracks cleanup history

### 5. Benefits of Dual Logging

1. **Global Visibility**: System logs show all activities across all requests
2. **Request Isolation**: Each request has its own complete log set
3. **Easy Debugging**: Can trace a specific request through all components
4. **Historical Analysis**: System logs provide long-term patterns
5. **Clean Organization**: No mixing of logs from different requests
6. **Automatic Cleanup**: Prevents disk space issues

## Testing

### Verify Dual Logging
```bash
# Run the test script
./test_dual_logging.sh

# Start services and send a test request
./bin/start_tester.sh
./bin/start_builder.sh
./bin/test_client.sh

# Check both log locations
tail -f ~/cubrid-testtools/CTP/builder_tester/log/system/builder.log
ls -la ~/cubrid-testtools/CTP/builder_tester/log/requests/
```

### Expected Results

After running a build request, you should see:

1. **System Logs**: Continuous logs from all requests in `/system/builder.log` and `/system/tester.log`

2. **Request Directory**: A new directory like `/requests/req_20250811_143022_a7f3/` containing:
   - `builder.log` - Builder activities for this request
   - `tester.log` - Tester activities for this request
   - `request.json` - Original request data
   - `builds/` - Individual build logs
   - `tests/` - Individual test logs

3. **Automatic Cleanup**: Only the 5 most recent request directories are retained

## Usage Examples

### Following a Specific Request
```bash
# Find request ID in system log
grep "req_20250811" ~/cubrid-testtools/CTP/builder_tester/log/system/builder.log

# View all logs for that request
cd ~/cubrid-testtools/CTP/builder_tester/log/requests/req_20250811_143022_a7f3/
cat builder.log
cat tester.log
ls builds/
ls tests/
```

### Monitoring System Activity
```bash
# Watch all builder activity
tail -f ~/cubrid-testtools/CTP/builder_tester/log/system/builder.log

# Watch all tester activity
tail -f ~/cubrid-testtools/CTP/builder_tester/log/system/tester.log
```

### Debugging a Failed Build
```bash
# Find the request directory
REQUEST_DIR=$(ls -td ~/cubrid-testtools/CTP/builder_tester/log/requests/req_* | head -1)

# Check build logs
cat $REQUEST_DIR/builds/build_*.log

# Check test results
cat $REQUEST_DIR/tests/docker_*.log
```

## Implementation Notes

1. **Thread Safety**: Request context uses ThreadLocal for concurrent request handling
2. **Fallback Behavior**: If request logging fails, system falls back to system-only logging
3. **Performance**: Dual writing has minimal overhead due to buffered I/O
4. **Backward Compatibility**: Setting `enable_request_grouping=false` disables request-specific logging

## Summary

The dual logging implementation successfully provides:

✅ **Complete Visibility**: All logs available in system directory
✅ **Request Organization**: Each request's logs grouped together
✅ **Automatic Cleanup**: Old logs and tar files automatically removed
✅ **Easy Debugging**: Can trace requests through entire system
✅ **Production Ready**: Scalable, maintainable, and well-tested

The system now maintains perfect log organization while ensuring no log data is lost, making it easy to both monitor overall system health and debug specific request issues.
