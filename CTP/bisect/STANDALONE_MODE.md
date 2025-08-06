# CUBRID Bisect - Standalone Mode

## Overview

The Standalone Mode for CUBRID Bisect combines both build and test operations into a single Docker container, eliminating the need for separate Producer and Consumer nodes. This simplifies deployment and is ideal for single-machine setups.

## Architecture

### Traditional Mode (Producer-Consumer)
```
[Producer Node]                    [Consumer Node]
    |                                   |
    |- Receives bisect request          |- Receives test request
    |- Manages git bisect               |- Executes tests
    |- Builds CUBRID                    |- Returns pass/fail
    |- Sends test to consumer           |
    |                                   |
    |- Docker: CentOS 6 builder        |- Docker: Rocky Linux 8
```

### Standalone Mode
```
[Standalone Node]
    |
    |- Receives bisect request
    |- Manages git bisect
    |- Builds CUBRID (in Docker)
    |- Executes tests (in same Docker)
    |- Returns results
    |
    |- Docker: Enhanced CentOS 6 with test capabilities
```

## Features

- **Single Container**: Build and test in the same Docker environment
- **Based on Producer Image**: Uses CentOS 6 with devtoolset-8 for compatibility
- **Enhanced with Test Tools**: Includes SSH, Java, test utilities
- **Simplified Deployment**: No need for network communication between nodes
- **Resource Efficient**: Single Docker image, reduced overhead

## Prerequisites

1. **Docker**: Must be installed and running
2. **Git**: For repository management
3. **Java 8+**: For running the service
4. **cubridci Repository**: Will be cloned automatically if not present

## Installation

### 1. Clone the CTP Repository
```bash
git clone https://github.com/CUBRID/cubrid-testtools.git
cd cubrid-testtools/CTP/bisect
```

### 2. Prepare Test Cases
Clone your test cases repository:
```bash
git clone <your-test-repository> ~/cubrid-testcases-private-ex
```

### 3. Build the Standalone Service
```bash
./script/run_standalone.sh build
```

## Configuration

Create or edit `conf/bisect_standalone.conf`:

```properties
# HTTP server port
listen_port=8091

# CUBRID source directory (will be cloned if not exists)
cubrid_src_dir=~/cubrid-src

# Shell test cases directory
shell_tc_dir=~/cubrid-testcases-private-ex

# Build configuration
build_arg=-g ninja -m debug build
build_dir=build_x86_64_debug

# Working directory
work_dir=/tmp/bisect_standalone_work

# Concurrency
max_concurrent_bisects=2

# Enable standalone mode
standalone_mode=true

# Docker configuration
use_docker=true
docker_standalone_image=cubrid-bisect-standalone:latest
```

## Running the Service

### Start the Service
```bash
./script/run_standalone.sh start
```

Or manually:
```bash
java -cp "build/*:lib/*" \
     com.navercorp.cubridqa.bisect.StandaloneBisectService \
     conf/bisect_standalone.conf
```

### Check Prerequisites Only
```bash
./script/run_standalone.sh check
```

## Docker Image Build Process

The standalone Docker image is built automatically on first run:

1. **Base Image**: Built from `~/cubridci` repository (develop branch)
   - CentOS 6 with devtoolset-8
   - GCC 8.3.1 for C++14 support
   - Build tools (CMake, Ninja, etc.)

2. **Enhanced with Test Capabilities**:
   - SSH server for remote access
   - Java 8 for test execution
   - Test utilities (jq, expect, lcov, etc.)
   - Environment variables for CUBRID and CTP

## API Usage

### Submit a Bisect Request

```bash
curl -X POST http://localhost:8091/bisect \
  -H "Content-Type: application/json" \
  -d '{
    "suspectedStartCommit": "abc123",
    "suspectedEndCommit": "def456",
    "tests": [
      "shell/_06_issues/_12_2h/bug_bts_7583/cases/bug_bts_7583.sh"
    ],
    "buildType": "debug",
    "callbackUrl": "http://your-server/callback"
  }'
```

### Health Check

```bash
curl http://localhost:8091/health
```

## Execution Flow

1. **Request Reception**: Service receives bisect request via HTTP
2. **Repository Setup**: Ensures CUBRID source is available and updated
3. **Docker Initialization**: Builds/prepares standalone Docker image
4. **Bisect Process**:
   - For each commit in bisect range:
     - Build CUBRID in Docker
     - Install CUBRID in Docker
     - Run test in same Docker container
     - Mark commit as good/bad
   - Identify first bad commit
5. **Results**: Send callback with results or return via HTTP

## Advantages of Standalone Mode

1. **Simplified Setup**: Single node, single configuration
2. **Consistent Environment**: Build and test in identical environment
3. **No Network Dependencies**: No Producer-Consumer communication needed
4. **Resource Efficiency**: Single Docker image and container
5. **Easier Debugging**: All operations in one place

## Troubleshooting

### Docker Issues
```bash
# Check Docker status
docker version
docker ps

# Check Docker permissions
sudo usermod -aG docker $USER
# Log out and back in
```

### Build Issues
```bash
# Clean and rebuild
rm -rf build/
./script/run_standalone.sh build
```

### Image Building
```bash
# Manually build standalone image
cd ~/cubridci
git checkout develop
docker build -f docker/ci/Dockerfile -t cubrid-bisect-builder:latest docker/ci/

# Check images
docker images | grep cubrid-bisect
```

## Logs

Logs are written to console by default. To enable file logging:

```bash
# Create logging configuration
cat > conf/logging.properties << EOF
handlers=java.util.logging.FileHandler, java.util.logging.ConsoleHandler
.level=INFO
java.util.logging.FileHandler.pattern=logs/bisect-standalone.log
java.util.logging.FileHandler.limit=50000000
java.util.logging.FileHandler.count=5
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
EOF

# Run with logging config
java -Djava.util.logging.config.file=conf/logging.properties \
     -cp "build/*:lib/*" \
     com.navercorp.cubridqa.bisect.StandaloneBisectService \
     conf/bisect_standalone.conf
```

## Comparison with Producer-Consumer Mode

| Feature | Producer-Consumer | Standalone |
|---------|------------------|------------|
| Nodes Required | 2+ | 1 |
| Docker Images | 2 (builder + tester) | 1 (combined) |
| Network Setup | Required | Not needed |
| Scalability | High (multiple consumers) | Limited |
| Complexity | Higher | Lower |
| Resource Usage | Higher | Lower |
| Best For | Distributed testing | Single machine |

## Future Enhancements

- [ ] Web UI for monitoring bisect progress
- [ ] Support for parallel test execution within container
- [ ] Caching of build artifacts
- [ ] Support for other test types (SQL, CCI, etc.)
- [ ] Integration with CI/CD pipelines
