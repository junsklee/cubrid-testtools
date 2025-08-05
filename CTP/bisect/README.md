# CUBRID Bisect Tool

A standalone Java-based tool for finding the first failing commit for CUBRID shell tests using git bisect.

## Overview

This tool implements a distributed bisect workflow where:
1. **Client** (e.g., QAHome) sends a JSON request with suspected bad commit range and failing tests
2. **Producer** node receives the request and for each test:
   - Automatically finds the parent of the first suspected bad commit to use as the "good" commit
   - Uses git bisect to find the actual first bad commit
   - Builds CUBRID at each bisect step
   - Sends the build to a consumer node for testing
3. **Consumer** node receives builds and test requests, runs tests, and returns results
4. **Producer** aggregates results and sends them back via HTTP callback

## Key Features (v2.0)

- **Build Verification**: Automatically verifies CUBRID installation using `cubrid_rel` before running tests
- **Enhanced Error Handling**: Distinguishes between:
  - Test failures (actual test fails)
  - Execution errors (test couldn't run properly)
  - Environment errors (setup issues)
  - Build errors (installation/verification failures)
- **Shell Test Framework Integration**: Proper support for CTP shell test framework
- **Smart Bisect Skipping**: Automatically skips commits that can't be tested due to environment issues
- **Improved Logging**: Detailed logging for debugging test execution issues

## Quick Start

### Installation

1. Clone the repository:
```bash
cd /path/to/cubrid-testtools/CTP/bisect
```

2. Build the project:
```bash
./build.sh
```

3. Configure the services:
```bash
# Producer configuration
cp conf/bisect_producer.conf.example conf/bisect_producer.conf
# Edit conf/bisect_producer.conf

# Consumer configuration
cp conf/bisect_consumer.conf.example conf/bisect_consumer.conf
# Edit conf/bisect_consumer.conf
```

### Starting Services

Producer node:
```bash
./script/start_producer.sh
```

Consumer node:
```bash
./script/start_consumer.sh
```

### Sending a Bisect Request

```bash
curl -X POST -H "Content-Type: application/json" \
  -d '{
    "suspectedStartCommit": "e4c8127",
    "suspectedEndCommit": "bb2cc88",
    "tests": ["shell/_06_issues/_12_2h/bug_bts_7583/cases/bug_bts_7583.sh"],
    "callbackUrl": "http://localhost:8080/bisect/result"
  }' \
  http://localhost:8089/bisect
```

### Running Tests

Use the provided test scripts:

```bash
# Basic test
./tests/examples/test_bisect.sh

# Test with build preservation
./tests/examples/test_bisect_preserve_builds.sh

# System diagnostics
./tests/diagnostic/diagnose_bisect.sh

# View results
./tests/utils/results_viewer.sh
```

## Configuration

### Producer Configuration (bisect_producer.conf)
```properties
# HTTP server port
listen_port=8089

# Path to CUBRID source repository
cubrid_src_dir=/path/to/cubrid

# Path to shell test cases
shell_tc_dir=/path/to/cubrid-testcases

# CUBRID build arguments
build_arg=-g ninja -m debug build

# Build directory name
build_dir=build_x86_64_debug

# Working directory for bisect operations
work_dir=/tmp/bisect_work

# Consumer port (where to send test requests)
consumer_port=8090

# Maximum concurrent bisect operations
max_concurrent_bisects=4
```

### Consumer Configuration (bisect_consumer.conf)
```properties
# HTTP server port
consumer_port=8090

# Working directory for test execution
work_dir=/tmp/bisect_consumer

# Required: Path to CUBRID source (for config compatibility)
cubrid_src_dir=/path/to/cubrid

# Required: Path to shell test cases
shell_tc_dir=/path/to/cubrid-testcases
```

## API Reference

### Bisect Request

**Endpoint**: `POST /bisect`

```json
{
  "suspectedStartCommit": "string",  // First suspected bad commit
  "suspectedEndCommit": "string",    // Last suspected bad commit
  "buildType": "string",             // Optional: "debug" or "release" (default: "debug")
  "workerIp": "string",              // Optional: Consumer IP (default: "localhost")
  "tests": ["string"],               // Array of test paths
  "callbackUrl": "string",           // URL to POST results
  "autoDeleteBuilds": boolean        // Optional: Delete build files (default: true)
}
```

### Callback Response

```json
{
  "suspectedStartCommit": "string",
  "suspectedEndCommit": "string",
  "workerIp": "string",
  "generatedAt": "string",           // ISO 8601 timestamp
  "tests": [{
    "name": "string",                // Test path
    "status": "string",              // Result status (see below)
    "firstBadCommit": "string",      // Git commit hash (if found)
    "author": "string",              // Commit author (if found)
    "error": "string",               // Error message (if error)
    "runtimeMs": number              // Execution time in milliseconds
  }]
}
```

#### Test Status Types

- **`found`**: Successfully identified the first bad commit
- **`error`**: Bisect failed - no bad commit found in range
- **`incomplete`**: Bisect couldn't complete due to too many untestable commits
- **`environment_issue`**: All commits were skipped due to environment/execution errors

#### Consumer Response Status Types

When the consumer runs a test, it returns one of these statuses:

- **`pass`**: Test executed successfully and passed
- **`fail`**: Test executed successfully and failed
- **`execution_error`**: Test couldn't be executed properly (e.g., script errors, missing result file)
- **`environment_error`**: Environment setup failed (e.g., CUBRID not properly installed)
- **`build_error`**: Build installation or verification failed

The bisect tool handles these statuses intelligently:
- `pass` → marks commit as good
- `fail` → marks commit as bad
- `execution_error`, `environment_error` → skips commit (can't determine good/bad)
- `build_error` → marks commit as bad (broken build)

## Results Storage

The CTP Bisect system supports **local results storage** in addition to HTTP callback delivery. Results are automatically saved to local files whenever a bisect task completes, regardless of whether the HTTP callback succeeds or fails.

### Storage Location

**Primary Location**: `/tmp/bisect_work/results/`

This directory contains:
- Individual result files: `bisect_result_YYYYMMDD_HHMMSS_TASKID.json`
- Latest result file: `latest_result.json` (copy of most recent result)

### File Format

Results are stored as JSON files with the following structure:

```json
{
  "suspectedStartCommit": "bb2cc88",
  "suspectedEndCommit": "e4c8127", 
  "workerIp": "192.168.1.5",
  "generatedAt": "2025-08-05T14:44:51.857Z",
  "tests": [
    {
      "name": "shell/_06_issues/_12_2h/bug_bts_7583/cases/bug_bts_7583.sh",
      "status": "found",
      "firstBadCommit": "bb2cc88e1df96910a3bb30384cd6d307cd6d839a",
      "author": "jongmin-won <55681111+jongmin-won@users.noreply.github.com>",
      "runtimeMs": 397149
    }
  ]
}
```

### Viewing Results

Use the results viewer utility:

```bash
./tests/utils/results_viewer.sh
```

Or manually:

```bash
# List all result files
ls -la /tmp/bisect_work/results/

# View latest result
cat /tmp/bisect_work/results/latest_result.json

# Pretty print with jq (if available)
jq . /tmp/bisect_work/results/latest_result.json
```

### Integration with Build Preservation

When using `autoDeleteBuilds: false`, both results and builds are preserved:

- **Results**: `/tmp/bisect_work/results/bisect_result_TIMESTAMP_TASKID.json`
- **Builds**: `/tmp/bisect_work/bisect_TIMESTAMP/` (contains full CUBRID build)

### Benefits

1. **Reliability**: Results are never lost, even if HTTP callback fails
2. **Persistence**: Results remain available after system restarts
3. **Debugging**: Easy access to historical bisect results
4. **Automation**: Scripts can parse local JSON files for further processing

No additional configuration required. Local storage is automatically enabled for all bisect tasks.

## Project Structure

```
CTP/bisect/
├── src/com/navercorp/cubridqa/bisect/
│   ├── BisectProducer.java      # HTTP server, request handling
│   ├── BisectConsumer.java      # Test execution service
│   ├── BisectTask.java          # Bisect logic and coordination
│   └── BisectConfig.java        # Configuration management
├── script/
│   ├── start_producer.sh        # Start producer service
│   ├── stop_producer.sh         # Stop producer service
│   ├── start_consumer.sh        # Start consumer service
│   └── stop_consumer.sh         # Stop consumer service
├── tests/                       # Testing and diagnostic tools
│   ├── data/                    # Test data files
│   ├── examples/                # Example bisect test scripts
│   ├── diagnostic/              # System diagnostic tools
│   ├── utils/                   # Utility scripts and tools
│   └── README.md                # Testing documentation
├── conf/
│   ├── bisect_producer.conf.example
│   └── bisect_consumer.conf.example
├── runtime/                     # Runtime files (PID files, etc.)
├── lib/                         # Dependencies (JSON library)
├── build/                       # Compiled classes
├── log/                         # Log files (created at runtime)
├── build.sh                     # Build script
├── ARCHITECTURE.md             # System architecture details
├── SCALING.md                  # Scaling guide
└── README.md                   # This file
```

## How It Works

### Git Bisect Process
1. Tool finds parent of `suspectedStartCommit` as the good commit
2. Runs git bisect between good commit and `suspectedEndCommit`
3. For each commit in binary search:
   - Builds CUBRID
   - Sends build to consumer
   - Marks commit as good/bad based on test result
4. Returns the first bad commit where test started failing

### Test Execution
Consumer service:
1. Receives build package and test information
2. Extracts CUBRID build to temporary directory
3. Sets up environment variables
4. Executes shell test script
5. Checks for "NOK" in result file
6. Returns pass/fail status to producer

## Monitoring

### Check Service Status
```bash
# Check if services are running
ps aux | grep -E "Bisect(Producer|Consumer)"

# Check service health
curl http://localhost:8089/health
curl http://localhost:8090/health
```

### View Logs
```bash
# Producer logs
tail -f log/bisect_producer.log

# Consumer logs
tail -f log/bisect_consumer.log
```

## Troubleshooting

### Common Issues

1. **Consumer not receiving requests**
   - Check firewall settings for ports 8089/8090
   - Verify consumer is running: `curl http://localhost:8090/health`
   - Check producer logs for connection errors

2. **Build failures**
   - Ensure CUBRID build dependencies are installed
   - Check disk space in work directory
   - Verify git repository is clean

3. **Test not found**
   - Verify `shell_tc_dir` path in configuration
   - Check test path matches exactly (case-sensitive)

4. **Slow performance**
   - Consider increasing `max_concurrent_bisects`
   - Add more consumer nodes for parallel testing
   - Check network latency between nodes

5. **Results not being saved locally**
   - Check write permissions on `/tmp/bisect_work/`
   - Verify disk space availability
   - Check producer logs for "Results saved locally" messages
   - Ensure the updated bisect-tool.jar is being used

### Debug Mode

Enable detailed logging by modifying the start scripts:
```bash
java -Djava.util.logging.ConsoleHandler.level=ALL ...
```

## Performance

- **Binary search efficiency**: For N commits, requires at most log₂(N) builds
- **Example**: 128 commits → maximum 7 builds
- **Typical timing**: ~3-5 minutes per test (depending on build/test complexity)
- **Parallelization**: Multiple tests run concurrently on same commit range

## Requirements

- Java 8 or higher
- Git 2.0+
- CUBRID build environment
- Network connectivity between producer and consumer
- Sufficient disk space (20GB+ recommended)

## License

Copyright (c) 2016, Search Solution Corporation. All rights reserved.
