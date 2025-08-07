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

## Key Features (v3.0)

- **Docker Integration**: Uses pre-built Docker images from Docker Hub for faster setup
- **Automatic CUBRID Setup**: Runs setup.sh script after extraction for proper configuration
- **Build Verification**: Automatically verifies CUBRID installation using `cubrid_rel` before running tests
- **Enhanced Error Handling**: Distinguishes between:
  - Test failures (actual test fails)
  - Execution errors (test couldn't run properly)
  - Environment errors (setup issues)
  - Build errors (installation/verification failures)
- **Shell Test Framework Integration**: Proper support for CTP shell test framework
- **Smart Bisect Skipping**: Automatically skips commits that can't be tested due to environment issues
- **Improved Logging**: Detailed logging for debugging test execution issues
- **Pre-built Docker Images**: Option to use pre-built images from Docker Hub instead of building from source
## Quick Start

### Prerequisites

- Java 8 or higher
- Docker (optional but recommended)
- Git 2.0+
- CUBRID build environment
- Network connectivity between producer and consumer

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
### Docker Images

By default, the tool uses pre-built Docker images from Docker Hub:
- **Builder image**: `cubridci/cubridci:develop` (for building CUBRID)
- **Tester image**: `cubridci/cubridci:test_shell` (for running tests)

To pull these images manually:
```bash
docker pull cubridci/cubridci:develop
docker pull cubridci/cubridci:test_shell
```

If you prefer to build images from source, set `use_prebuilt_docker_images=false` in your configuration.

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

# Docker configuration
use_docker=true
use_prebuilt_docker_images=true  # Use pre-built images from Docker Hub
docker_build_image=cubrid-bisect-builder:latest
docker_test_image=cubrid-bisect-tester:latest
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

# Docker configuration
use_docker_consumer=true
use_prebuilt_docker_images=true  # Use pre-built images from Docker Hub
docker_test_image=cubrid-bisect-tester:latest
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
## CUBRID Installation Process

The consumer now properly installs CUBRID by:
1. Extracting the tar.gz build package
2. Finding the CUBRID binaries location
3. Running the `setup.sh` script to configure CUBRID environment
4. Verifying the installation with `cubrid_rel` command
5. Creating necessary directories (databases, etc.)

This ensures proper CUBRID setup following the official installation guide.

## Results Storage

The CTP Bisect system supports **local results storage** in addition to HTTP callback delivery. Results are automatically saved to local files whenever a bisect task completes.

### Storage Location

**Primary Location**: `/tmp/bisect_work/results/`

### Viewing Results

```bash
# View latest result
cat /tmp/bisect_work/results/latest_result.json

# Pretty print with jq (if available)
jq . /tmp/bisect_work/results/latest_result.json
```

## Project Structure

```
CTP/bisect/
├── src/com/navercorp/cubridqa/bisect/
│   ├── BisectProducer.java      # HTTP server, request handling
│   ├── BisectConsumer.java      # Test execution service (with setup.sh support)
│   ├── BisectTask.java          # Bisect logic and coordination
│   ├── BisectConfig.java        # Configuration management
│   ├── DockerBuildManager.java  # Docker build management (with pre-built image support)
│   └── DockerConsumerManager.java # Docker test management (with pre-built image support)
├── conf/                        # Configuration files
├── script/                      # Service management scripts
├── tests/                       # Testing and diagnostic tools
└── README.md                   # This file
```
## How It Works

### Git Bisect Process
1. Tool finds parent of `suspectedStartCommit` as the good commit
2. Runs git bisect between good commit and `suspectedEndCommit`
3. For each commit in binary search:
   - Builds CUBRID (using Docker if enabled)
   - Sends build to consumer
   - Consumer installs CUBRID using setup.sh
   - Consumer runs test and checks results
   - Marks commit as good/bad based on test result
4. Returns the first bad commit where test started failing

### Test Execution
Consumer service:
1. Receives build package and test information
2. Extracts CUBRID build to temporary directory
3. Runs setup.sh script to configure CUBRID
4. Verifies installation with cubrid_rel command
5. Sets up environment variables
6. Executes shell test script
7. Checks for "NOK" in result file
8. Returns pass/fail status to producer

## Troubleshooting

### Common Issues

1. **CUBRID installation errors**
   - Check that setup.sh script exists in the extracted build
   - Verify CUBRID binaries are present (bin/cubrid_rel)
   - Check environment variables are set correctly
   - Ensure databases directory is created

2. **Docker image pull failures**
   - Verify internet connectivity
   - Check Docker Hub access
   - Try pulling images manually: `docker pull cubridci/cubridci:develop`
   - Set `use_prebuilt_docker_images=false` to build from source

3. **Test execution failures**
   - Verify test scripts exist in shell_tc_dir
   - Check test script has execute permissions
   - Ensure CUBRID is properly installed with setup.sh
   - Check for missing dependencies
## Recent Updates (v3.0)

### Task 1: Fixed Consumer Test Execution
- Enhanced Docker test script to properly handle CUBRID setup
- Added setup.sh execution in Docker environment
- Improved error handling for incomplete test output
- Fixed test script execution to ensure proper result generation

### Task 2: Replaced run_cubrid_install with setup.sh
- Consumer now directly runs setup.sh after extracting CUBRID
- Follows official CUBRID installation guide
- Removed all dependencies on run_cubrid_install script
- Fixed hardcoded paths to use dynamic configuration
- Updated findCTPHome method to look for shell directory instead of run_cubrid_install

### Task 3: Pre-built Docker Images Support
- Added `use_prebuilt_docker_images` configuration option (default: true)
- Automatically pulls images from Docker Hub:
  - `cubridci/cubridci:develop` for building
  - `cubridci/cubridci:test_shell` for testing
- Falls back to building from source if configured
- Significantly reduces initial setup time
- Updated both DockerBuildManager and DockerConsumerManager

## Performance

- **Binary search efficiency**: For N commits, requires at most log₂(N) builds
- **Example**: 128 commits → maximum 7 builds
- **Typical timing**: ~3-5 minutes per test (depending on build/test complexity)
- **Parallelization**: Multiple tests run concurrently on same commit range
- **Docker images**: Pre-built images reduce setup time significantly

## Requirements

- Java 8 or higher
- Git 2.0+
- Docker (optional but recommended)
- CUBRID build environment
- Network connectivity between producer and consumer
- Sufficient disk space (20GB+ recommended)

## License

Copyright (c) 2016, Search Solution Corporation. All rights reserved.