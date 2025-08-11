# Log Organization Fix - Complete

## Problem Solved
The logging system was creating logs in multiple places and not properly organizing them by request. Specifically:
- Build logs were going to `/log/builds/` instead of `/log/requests/req_XXX/builds/`
- Test logs were not being organized under request directories
- Logs were being duplicated in both system and request directories

## Solution Implemented

### 1. Removed Duplicate Logging
- Modified `RequestLogManager.getRequestLogger()` to write ONLY to request directories
- System logs now contain only service-level events (startup, shutdown, etc.)
- No more duplicate content in multiple locations

### 2. Fixed Thread Context Propagation
The main issue was that `RequestContext` is thread-local and wasn't being propagated to executor threads.

#### BuilderTask.java - Build Threads
```java
// Capture the current request ID to propagate to build threads
final String requestId = RequestContext.getRequestId();

Future<Void> future = executor.submit(() -> {
    // Set the request context for this thread
    if (requestId != null) {
        RequestContext.setRequestId(requestId);
    }
    try {
        // Build logic here
    } finally {
        RequestContext.clear();
    }
});
```

#### BuilderTask.java - Test Threads
```java
// Capture request ID for test threads
final String testRequestId = RequestContext.getRequestId();

futuresTests.add(testPool.submit(() -> {
    if (testRequestId != null) {
        RequestContext.setRequestId(testRequestId);
    }
    try {
        return ct.call();
    } finally {
        RequestContext.clear();
    }
}));
```

### 3. Proper Log Organization

The system now creates this structure for each request:

```
log/
├── system/
│   ├── builder.log    # Service startup/shutdown only
│   └── tester.log     # Service startup/shutdown only
└── requests/
    └── req_20250811_143022_a7f3/
        ├── request.json           # Original request data
        ├── builder.log            # All builder activities for this request
        ├── tester.log             # All tester activities for this request
        ├── builds/
        │   ├── build_abc123.log   # Detailed build log for commit abc123
        │   └── build_def456.log   # Detailed build log for commit def456
        └── tests/
            ├── test_csql_hist.json      # Test request data
            ├── docker_script_csql_hist.sh # Docker test script
            └── docker_csql_hist.log      # Docker test output

```

## Key Changes Made

### RequestLogManager.java
- Removed dual logging (was writing to both system and request directories)
- Now writes ONLY to request-specific directories
- System logger reserved for service-level events only

### BuilderTask.java
- Added RequestContext propagation to build executor threads
- Added RequestContext propagation to test executor threads
- Ensures all parallel operations maintain the request context

### DockerBuildManager.java
- Already correctly using RequestContext to determine log path
- Writes build logs to `/requests/req_XXX/builds/build_*.log`

### Tester.java
- Already correctly using request-specific logger
- Writes test logs to `/requests/req_XXX/tests/`

## Benefits

1. **No Duplication**: Each log entry goes to exactly ONE location
2. **Clear Organization**: All logs for a request are in one directory
3. **Thread Safety**: RequestContext properly propagated to all threads
4. **Efficient**: Reduced I/O by eliminating duplicate writes
5. **Easy Debugging**: Can trace entire request lifecycle in one place

## Testing

Run the test script to verify:
```bash
./test_log_organization.sh
```

To fully test with actual requests:
```bash
# Start services
./bin/start_tester.sh
./bin/start_builder.sh

# Send a test request
./bin/test_client.sh

# Check the logs
ls -la ~/cubrid-testtools/CTP/builder_tester/log/requests/
```

## Expected Results

After running a request, you should see:

1. **System logs** (`/log/system/`):
   - Minimal content - only service start/stop
   - No request-specific details

2. **Request directory** (`/log/requests/req_XXX/`):
   - `request.json` - Original request
   - `builder.log` - All builder activities
   - `tester.log` - All tester activities (if tests ran)
   - `builds/` - Individual build logs
   - `tests/` - Individual test logs and scripts

3. **No duplicate content** - Each log entry appears in only one place

## Configuration

The behavior is controlled by these settings in `builder.conf` and `tester.conf`:
```properties
enable_request_grouping=true    # Enable request-based organization
max_request_logs=5              # Keep only last 5 requests
max_tar_files=10                # Keep only last 10 tar files
```

## Summary

The logging system now:
- ✅ Organizes all logs by request ID
- ✅ Eliminates duplicate logging
- ✅ Properly propagates context to all threads
- ✅ Creates clear directory structure
- ✅ Automatically cleans up old logs
- ✅ Makes debugging much easier

The fix ensures that when you look for logs related to a specific request, you'll find everything in one place: `/log/requests/req_XXX/`, with no duplicate information scattered across the system.
