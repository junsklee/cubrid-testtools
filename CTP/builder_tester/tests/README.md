# CTP Builder-Tester Test Suite

Comprehensive test suite for the CTP Builder-Tester system. This suite validates all features and ensures system reliability before commits.

## Overview

The test suite provides extensive coverage of:
- Unit tests for individual components
- Integration tests for service interactions
- API tests for HTTP endpoints
- System tests for end-to-end scenarios
- Configuration tests for different modes

## Directory Structure

```
tests/
├── unit/                    # Unit tests for individual components
│   ├── config/             # Configuration parsing tests
│   ├── docker/             # Docker utilities tests
│   └── core/               # Core logic tests
├── integration/            # Integration tests
│   ├── builder_tester_comm_test.sh
│   ├── concurrent_builds_test.sh
│   └── build_cache_test.sh
├── api/                    # HTTP API tests
│   ├── builder_api_test.sh
│   └── tester_api_test.sh
├── system/                 # End-to-end system tests
│   ├── end_to_end_test.sh
│   ├── failure_recovery_test.sh
│   └── keep_alive_mode_test.sh
├── config/                 # Configuration mode tests
│   └── docker_mode_test.sh
├── fixtures/               # Test fixtures and mocks
│   ├── mock_cubrid_src/
│   ├── mock_testcases/
│   └── test_configs/
├── lib/                    # Test utilities
│   └── test_helpers.sh
├── results/                # Test execution results
└── run_tests.sh           # Main test runner
```

## Quick Start

### Run All Tests
```bash
cd tests
./run_tests.sh
```

### Run Specific Test Suite
```bash
# Run only unit tests
./run_tests.sh -s unit

# Run only API tests
./run_tests.sh -s api

# Run integration tests
./run_tests.sh -s integration
```

### Run Tests in Parallel
```bash
# Run tests in parallel with 8 workers
./run_tests.sh -p -j 8
```

### Verbose Output
```bash
# Show detailed test output
./run_tests.sh -v

# Run specific suite with verbose output
./run_tests.sh -v -s system
```

## Test Runner Options

| Option | Description |
|--------|-------------|
| `-s, --suite SUITE` | Run specific test suite (unit/integration/api/system/config/all) |
| `-v, --verbose` | Enable verbose output |
| `-q, --quiet` | Suppress output except errors |
| `-p, --parallel` | Run tests in parallel |
| `-j, --jobs N` | Maximum parallel jobs (default: 4) |
| `--no-html` | Skip HTML report generation |
| `--no-junit` | Skip JUnit XML generation |
| `-h, --help` | Show help message |

## Test Reports

After test execution, reports are generated in the `results/` directory:

- **report.json** - Detailed JSON report with all test results
- **report.html** - HTML dashboard with visual test results
- **junit.xml** - JUnit format for CI/CD integration
- **Individual test logs** - Detailed logs for each test

## Test Categories

### Unit Tests
Test individual components in isolation:
- Configuration parsing
- Docker command generation
- Build task management
- Result detection logic

### Integration Tests
Test component interactions:
- Builder-Tester communication
- Concurrent build limits
- Build caching
- Docker lifecycle

### API Tests
Test HTTP endpoints:
- Request validation
- Response formats
- Error handling
- Status codes

### System Tests
End-to-end scenarios:
- Complete build-test-callback flow
- Multiple concurrent builds
- Failure recovery
- Keep-alive debugging mode

### Configuration Tests
Test different configurations:
- Docker mode
- Direct execution mode
- Concurrency limits
- Timeouts

## Writing New Tests

### Test Structure
Each test file should follow this structure:

```bash
#!/bin/bash
set -e

# Source test helpers
source "$(dirname "$0")/../lib/test_helpers.sh"

echo "Testing Feature X..."

# Setup function
setup() {
    # Prepare test environment
}

# Teardown function
teardown() {
    # Clean up
}

# Test functions
test_feature_1() {
    echo -n "  Testing feature 1... "
    # Test implementation
    echo "PASS"
    return 0
}

# Run tests
trap teardown EXIT
setup
test_feature_1
teardown
trap - EXIT

echo "Feature X tests completed"
exit 0
```

### Using Test Helpers

The `lib/test_helpers.sh` provides utilities:

```bash
# Assertions
assert_equals "expected" "actual" "Test message"
assert_contains "haystack" "needle" "Should contain"
assert_file_exists "/path/to/file"
assert_http_status "http://localhost:8089/health" "200"

# Service management
start_test_builder
start_test_tester
stop_test_builder
stop_test_tester

# HTTP requests
send_build_request '["commit"]' '["test.sh"]'
send_test_request "/package.tar.gz" "test.sh"

# Skip conditions
skip_if "! command -v docker" "Docker not available"
```

## CI/CD Integration

### Pre-commit Hook
Add to `.git/hooks/pre-commit`:

```bash
#!/bin/bash
cd tests && ./run_tests.sh -q
```

### GitHub Actions
```yaml
- name: Run Tests
  run: |
    cd tests
    ./run_tests.sh --no-html
```

### Jenkins
```groovy
stage('Test') {
    sh 'cd tests && ./run_tests.sh'
    junit 'tests/results/junit.xml'
}
```

## Prerequisites

- Java 8+
- Bash 4+
- curl
- nc (netcat)
- Docker (optional, for Docker mode tests)

## Troubleshooting

### Tests Failing
1. Check prerequisites: `./run_tests.sh -h`
2. Run with verbose mode: `./run_tests.sh -v`
3. Check individual test logs in `results/`

### Port Conflicts
Tests use ports 18089 and 18090 to avoid conflicts with running services.

### Docker Tests Skipped
Docker tests are automatically skipped if Docker is not available.

## Exit Codes

- **0** - All tests passed
- **1** - One or more tests failed
- **77** - Test skipped (not counted as failure)

## Contributing

When adding new features to Builder-Tester:
1. Add corresponding tests in appropriate category
2. Update this README if adding new test categories
3. Ensure tests pass before committing
4. Add integration test for feature interactions

## License

Copyright (c) 2016, Search Solution Corporation. All rights reserved.