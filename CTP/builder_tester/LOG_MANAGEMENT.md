# Log Management Documentation

## Overview
This document describes the comprehensive design of the logging system for the Builder-Tester application. The refactoring introduces request-based log organization, automatic cleanup, and improved log management.

## Key Changes

### 1. New Logging Infrastructure Classes

#### RequestLogManager.java
- Central logging manager for request-scoped and system-level logging
- Provides structured logging with automatic organization by request ID
- Creates separate loggers for each component within a request

#### RequestContext.java
- Thread-local context for tracking request IDs throughout execution
- Generates unique request IDs with format: `req_TIMESTAMP_RANDOM`
- Example: `req_20250811_143022_a7f3`

#### LogConfig.java
- Configuration for log management and retention policies
- Manages settings for max request logs, max tar files, and log directories

#### LogRotationManager.java
- Handles cleanup of old logs and tar files based on retention policies
- Performs cleanup at the beginning of each build request
- Tracks metadata about requests and cleanup operations

### 2. Log Directory Structure

```
~/cubrid-testtools/CTP/builder_tester/log/
├── requests/                      # All request-based logs
│   ├── req_20250811_143022_a7f3/  # Format: req_DATE_TIME_ID
│   │   ├── request.json           # Original request data
│   │   ├── builder.log            # Builder logs for this request
│   │   ├── builds/                # Build logs for each commit
│   │   │   ├── build_abc123.log
│   │   │   └── build_def456.log
│   │   └── tests/                 # Test logs for each test
│   │       ├── test_csql_hist.json
│   │       ├── docker_script_csql_hist.sh
│   │       └── docker_csql_hist.log
│   └── req_20250811_144512_b8e4/
├── system/                        # System-level logs
│   ├── builder.log               # General builder service logs
│   └── tester.log                # General tester service logs
└── .metadata.json                # Track request history for cleanup
```

### 3. Configuration Settings

#### builder.conf
```properties
# Log management configuration
max_request_logs=5              # Keep only last 5 request directories
max_tar_files=10                # Keep only last 10 tar files
enable_request_grouping=true    # Enable request-based log grouping
```

#### tester.conf
```properties
# Log management configuration  
max_request_logs=5              # Keep only last 5 request directories
enable_request_grouping=true    # Enable request-based log grouping
```

### 4. Existing Classes Integration

#### Builder.java
- Generates unique request IDs for each build request
- Initializes logging infrastructure on startup
- Performs cleanup of old logs and tar files before each request
- Passes request ID to BuilderTask

#### BuilderTask.java
- Uses request-scoped logging throughout execution
- All logs are written to the request-specific directory
- Includes request ID in test requests sent to Tester

#### Tester.java
- Accepts and uses request IDs from Builder
- Writes test logs to request-specific directories
- Maintains request context throughout test execution

#### DockerBuildManager.java
- Uses request-scoped directories for build logs
- Organized build logs by request ID when available

#### BuilderConfig.java
- Uses methods to read new configuration parameters:
  - `getMaxRequestLogs()`
  - `getMaxTarFiles()`
  - `isRequestGroupingEnabled()`

## Features

### 1. Request-Based Log Organization
- All logs related to a single request are grouped together
- Easy to track and debug specific requests
- Clean separation between different requests

### 2. Automatic Cleanup
- Old request logs are automatically deleted (keeps last N requests)
- Old tar files are automatically deleted (keeps last N files)
- Cleanup happens at the start of each build request
- Configurable retention policies

### 3. Request ID Tracking
- Unique ID generated for each request
- ID follows the request through all components
- Appears in log messages for easy correlation
- Format: `req_YYYYMMDD_HHMMSS_XXXX`

### 4. Improved Log Structure
- Clear separation between system and request logs
- Hierarchical organization of build and test logs
- Metadata tracking for audit and cleanup

## Benefits

1. **Better Organization**: Logs are organized by request, making debugging easier
2. **Automatic Cleanup**: No manual cleanup needed, prevents disk space issues
3. **Request Tracking**: Easy to trace a request through the entire system
4. **Configurable**: Retention policies can be adjusted via configuration
5. **Scalable**: Structure supports high-volume logging without clutter
6. **Maintainable**: Clean separation of concerns in logging infrastructure

## Usage

### Starting the Services
```bash
# Start with new logging in effect
./bin/start_tester.sh
./bin/start_builder.sh
```

### Viewing Logs
```bash
# View system logs
tail -f ~/cubrid-testtools/CTP/builder_tester/log/system/builder.log

# View specific request logs
cd ~/cubrid-testtools/CTP/builder_tester/log/requests/req_*
cat builder.log

# View build logs for a request
cat builds/build_*.log

# View test logs for a request
cat tests/docker_*.log
```

### Configuration
Edit `conf/builder.conf` or `conf/tester.conf` to adjust:
- `max_request_logs`: Number of request directories to keep
- `max_tar_files`: Number of tar build files to keep
- `enable_request_grouping`: Enable/disable request-based grouping

## Testing

Run the test script to verify the refactoring:
```bash
sh tests/loggging/test_log_refactoring.sh
```

This script checks:
- Log directory structure
- Configuration settings
- Request directory count
- Tar file management

## Migration Notes

- The new logging structure will be created automatically on first run
- Old logs remain in their original locations
- No data migration is required
- System is backward compatible when `enable_request_grouping=false`

## Future Enhancements

Potential improvements for future iterations:
1. Log compression for archived requests
2. Remote log shipping capability
3. Log search and analysis tools
4. Web interface for log viewing
5. Metrics and monitoring integration
