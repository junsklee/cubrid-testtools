# CTP Bisect Testing Suite

This directory contains all testing, diagnostic, and utility scripts for the CTP Bisect system.

## Directory Structure

```
tests/
├── data/              # Test data files and sample requests
├── examples/          # Example bisect test scripts
├── diagnostic/        # System diagnostic and troubleshooting tools
├── utils/             # Utility scripts and tools
└── README.md          # This file
```

## Data Files (`data/`)

### `test_request.json`
Sample test request JSON for consumer testing.

**Usage:**
Used by diagnostic scripts to test consumer functionality with a minimal request payload.

## Examples (`examples/`)

### `test_bisect.sh`
Basic bisect test script that demonstrates the standard workflow.

**Usage:**
```bash
cd tests/examples
./test_bisect.sh
```

**Features:**
- Tests multiple shell test cases
- Uses default commit range
- Standard build cleanup (auto-delete enabled)

### `test_bisect_preserve_builds.sh`
Advanced bisect test script with build preservation enabled.

**Usage:**
```bash
cd tests/examples
./test_bisect_preserve_builds.sh
```

**Features:**
- Single test for faster execution
- Build preservation enabled (`autoDeleteBuilds: false`)
- Demonstrates how to keep build files for inspection
- Shows preserved build locations

## Diagnostic Tools (`diagnostic/`)

### `diagnose_bisect.sh`
Comprehensive system diagnostic script that checks both producer and consumer status.

**Usage:**
```bash
cd tests/diagnostic
./diagnose_bisect.sh
```

**Checks:**
- Service status (producer and consumer)
- Port availability (8089, 8090)
- HTTP endpoint health
- Configuration files
- Recent log activity
- Provides recommendations

### `diagnose_consumer.sh`
Focused diagnostic script for consumer-specific issues.

**Usage:**
```bash
cd tests/diagnostic
./diagnose_consumer.sh
```

**Features:**
- Consumer process verification
- Port status checking
- Creates and tests with sample build packages
- Endpoint testing with real requests
- Log analysis

### `test_consumer.sh`
Simple consumer connectivity test.

**Usage:**
```bash
cd tests/diagnostic
./test_consumer.sh
```

**Purpose:**
- Quick verification that consumer is reachable
- Sends basic test request
- Useful for automated health checks

## Utilities (`utils/`)

### `results_viewer.sh`
Results management and viewing utility.

**Usage:**
```bash
cd tests/utils
./results_viewer.sh
```

**Features:**
- Lists all available result files
- Shows summary of latest results
- Provides commands for manual inspection
- Supports JSON parsing with `jq` if available

### `start_callback_receiver.sh`
HTTP callback receiver for testing bisect results delivery.

**Usage:**
```bash
cd tests/utils
./start_callback_receiver.sh [port]
```

**Features:**
- Receives HTTP POST callbacks with bisect results
- Displays results in formatted output
- Shows both local and network IP addresses
- Auto-compiles Java source if needed
- Default port: 8080

**Note:** This is a blocking server that waits for HTTP requests. It will run until stopped with Ctrl+C.

### `test_callback_receiver.sh`
Test script to verify the callback receiver is working.

**Usage:**
```bash
# In terminal 1: Start the receiver
./start_callback_receiver.sh 8080

# In terminal 2: Test it
./test_callback_receiver.sh 8080
```

### `TestCallbackReceiver.java`
Java implementation of the callback receiver with expected result validation.

## Running Tests

### Prerequisites
1. Producer and consumer services must be running
2. Configuration files must be properly set up
3. CUBRID source repository must be available

### Quick Test Sequence
```bash
# 1. Run diagnostics first
cd tests/diagnostic
./diagnose_bisect.sh

# 2. If all checks pass, run a basic test
cd ../examples
./test_bisect.sh

# 3. View results
cd ../utils
./results_viewer.sh
```

### Environment Variables

Most scripts support these environment variables:

- `PRODUCER_HOST`: Producer hostname (default: localhost)
- `PRODUCER_PORT`: Producer port (default: 8089)
- `CONSUMER_HOST`: Consumer hostname (default: localhost)  
- `CONSUMER_PORT`: Consumer port (default: 8090)
- `CALLBACK_URL`: Results callback URL (default: http://localhost:8080/bisect/result)
- `AUTO_DELETE_BUILDS`: Build cleanup flag (default: true)

**Example:**
```bash
PRODUCER_HOST=remote-server CALLBACK_URL=http://my-server:8080/results ./test_bisect.sh
```

## Troubleshooting

1. **Tests fail immediately**: Run diagnostic scripts first
2. **Connection refused**: Check if services are running and ports are open
3. **Build failures**: Verify CUBRID source directory and build dependencies
4. **Results not found**: Check if callback receiver is running or use local results storage

## Integration with Main System

These test scripts are designed to work with the main bisect system without modification. They use the same:
- Configuration files (`conf/bisect_producer.conf`, `conf/bisect_consumer.conf`)
- Log files (`log/bisect_producer.log`, `log/bisect_consumer.log`)
- Working directories (`/tmp/bisect_work/`, `/tmp/bisect_consumer/`)
- Results storage (`/tmp/bisect_work/results/`)