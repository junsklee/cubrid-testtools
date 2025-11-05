# Multipart Form-Data Implementation for Test Logs

## Overview

The Builder-Tester system has been updated to send test execution logs as multipart/form-data file attachments instead of embedding them in JSON payloads. This change improves system efficiency, log visibility, and supports multiple retry attempts.

## Changes Made

### 1. New MultipartHelper Class
**File**: `src/com/navercorp/cubridqa/builder/MultipartHelper.java`
- Handles creation and parsing of multipart/form-data HTTP requests and responses
- Supports sending JSON response with multiple file attachments
- Provides methods for both sending and receiving multipart data

### 2. Tester.java Modifications
**File**: `src/com/navercorp/cubridqa/builder/Tester.java`

#### Key Changes:
- **runTestWithRetry()**: Now collects file paths instead of log content
  - Stores log files to disk during test execution
  - Maintains a list of log file paths for all retry attempts
  - Returns metadata about logs without actual content

- **TestRequestHandler.handle()**: Updated to send multipart responses
  - Detects when log files are available
  - Uses MultipartHelper to send JSON + file attachments
  - Falls back to JSON-only response for backward compatibility

- **Log handling methods**:
  - Added `addLogFilePathToResponse()` to store file paths
  - Modified all test execution methods to save logs to files
  - Removed embedding of log content in JSON responses

### 3. BuilderTask.java Modifications
**File**: `src/com/navercorp/cubridqa/builder/BuilderTask.java`

#### Key Changes:
- **HTTP Response Handling**: Now supports both multipart and JSON responses
  - Detects Content-Type header to determine response format
  - Parses multipart responses to extract JSON and log files
  - Saves received log files to disk
  - Maintains backward compatibility with JSON-embedded logs

- **parseMultipartResponse()**: New method to parse multipart data
  - Extracts JSON response from "response" field
  - Extracts log files from file attachments
  - Saves files with original names

## Benefits

### 1. Cleaner Build Logs
- Build.log no longer contains embedded test logs
- Easier to read and debug build process
- Reduced log file sizes

### 2. Better Retry Support
- Each retry attempt's log is sent as a separate file
- No truncation needed for large logs
- Complete logs available for debugging flaky tests

### 3. Improved Performance
- No need to escape/encode large text in JSON
- Reduced memory usage for large logs
- More efficient network transfer

### 4. Backward Compatibility
- System still supports JSON-embedded logs
- Automatic fallback for older clients
- No breaking changes to existing APIs

## Testing

Use the provided test script to verify the implementation:
```bash
./test_multipart.sh
```

This script will:
1. Check if services are running
2. Send a test request with retries enabled
3. Monitor task progress
4. Verify log files are created
5. Check report generation

## Configuration

No configuration changes are required. The system automatically:
- Sends multipart when log files are available
- Falls back to JSON for compatibility
- Handles both formats transparently

## File Structure

Log files are saved in the standard location:
```
~/cubrid-testtools/CTP/builder_tester/log/requests/req_*/tests/
```

File naming convention remains unchanged:
- `docker_<commit>_<test>.log` - First attempt
- `docker_<commit>_<test>.2.log` - Second attempt
- `docker_<commit>_<test>.3.log` - Third attempt
- etc.

## Migration Notes

1. **No action required** for existing deployments
2. The system is backward compatible
3. Both old (JSON) and new (multipart) formats are supported
4. Gradual migration is possible - update Tester first, then Builder

## Technical Details

### Multipart Response Format
```
Content-Type: multipart/form-data; boundary=----FormBoundaryXXXX

------FormBoundaryXXXX
Content-Disposition: form-data; name="response"
Content-Type: application/json

{"status":"pass","test":"test_name",...}
------FormBoundaryXXXX
Content-Disposition: form-data; name="log_attempt_1"; filename="docker_abc123_test.log"
Content-Type: text/plain

[Log content for attempt 1]
------FormBoundaryXXXX
Content-Disposition: form-data; name="log_attempt_2"; filename="docker_abc123_test.2.log"
Content-Type: text/plain

[Log content for attempt 2]
------FormBoundaryXXXX--
```

### JSON Response Metadata
The JSON response includes metadata about logs without content:
```json
{
  "status": "pass",
  "test": "test_name",
  "attempts": 2,
  "flaky": true,
  "attemptLogMetadata": [
    {
      "attempt": 1,
      "logFileName": "docker_abc123_test.log",
      "status": "fail"
    },
    {
      "attempt": 2,
      "logFileName": "docker_abc123_test.2.log",
      "status": "pass"
    }
  ]
}
```

## Future Enhancements

1. **Compression**: Add gzip compression for large log files
2. **Streaming**: Support streaming large files without loading into memory
3. **Selective Downloads**: Allow clients to request specific attempt logs
4. **Log Rotation**: Automatic cleanup of old log files

## Troubleshooting

### Issue: Services still using JSON format
- **Cause**: Services detect multipart support based on log file availability
- **Solution**: Ensure log files are being saved correctly; check file permissions

### Issue: Compilation errors
- **Solution**: Run `./bin/compile.sh` to rebuild the system

### Issue: Missing log files
- **Cause**: File system permissions or disk space
- **Solution**: Check write permissions on log directory and available disk space

## Summary

The multipart implementation successfully addresses the issues with JSON-embedded logs while maintaining full backward compatibility. The system now handles large logs efficiently, supports multiple retry attempts, and provides cleaner build logs for easier debugging.
