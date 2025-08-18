# Builder-Tester System

A concurrent build-and-test system for CUBRID. It builds multiple commits in parallel and runs shell tests in isolated Docker containers, returning pass/fail/error results with comprehensive reporting capabilities.

## Quick Overview

The Builder-Tester system provides:
- **Parallel Build Execution**: Build multiple commits concurrently with configurable limits
- **Distributed Testing**: Run tests across multiple tester nodes for faster execution
- **Isolated Testing**: Each test runs in its own Docker container or process
- **Build Caching**: Avoid redundant builds with intelligent caching
- **Performance Optimization**: Ccache support for 5-10x faster rebuilds
- **Comprehensive Reporting**: Interactive web-based reports with automated verdict analysis
- **Flaky Test Detection**: Automatic identification of intermittently failing tests

## Documentation Structure

- [docs/features/README.md](docs/features/README.md) – Complete feature set
- [docs/architecture/README.md](docs/architecture/README.md) – System architecture and components
- [docs/usage/README.md](docs/usage/README.md) – Setup, usage instructions, and API reference
- [docs/configuration/README.md](docs/configuration/README.md) – Configuration options and defaults
- [log/LOG_MANAGEMENT.md](log/LOG_MANAGEMENT.md) – Logging architecture and management
- [docs/CCACHE_GUIDE.md](docs/CCACHE_GUIDE.md) – Compiler cache setup for faster builds
- [docs/MULTI_NODE_TESTING.md](docs/MULTI_NODE_TESTING.md) – Distributed testing across nodes

## Quick Start

### Prerequisites
- Java 8+
- Docker
- CUBRID source checkout (or network access for Builder to clone)
- Shell testcases repository
- Environment variable `GITHUB_TOKEN` (required for repository access)

### Build and Run

```bash
cd CTP/builder_tester
./bin/compile.sh

# Optional: Set up ccache for faster rebuilds
./bin/manage_ccache.sh install
./bin/manage_ccache.sh setup

# Start Tester service
export GITHUB_TOKEN=your_github_token
./bin/start_tester.sh

# Start Builder service
export GITHUB_TOKEN=your_github_token
./bin/start_builder.sh
```

### Send a Test Request

```bash
# Using the test client script
./bin/test_client.sh

# Or via curl
curl -X POST http://localhost:8089/build \
  -H "Content-Type: application/json" \
  -d '{
    "commits": ["6ea587e"],
    "tests": ["shell/_01_utility/_38_csql/csql_hist/cases/csql_hist.sh"],
    "callbackUrl": "http://localhost:8089/callback",
    "workerIp": "localhost",
    "buildType": "debug"
  }'
```

### View Results

Access the interactive web report:
```bash
# List all reports
curl http://localhost:8089/report

# View specific report
curl http://localhost:8089/report?id=req_xxxxx
```

## Core Features

### Isolated Per-Commit Builds

Each commit is built in complete isolation:
- Baseline computed as parent of earliest commit in request
- Each commit cherry-picked onto baseline individually
- Submodules synced to correct versions
- Merge commits handled with mainline selection (`-m 1`)
- Prevents cumulative build errors

### Build Performance

- **Ccache Support**: 5-10x faster rebuilds with compiler caching
- **Parallel Compilation**: Auto-detects CPU cores for optimal parallelization
- **Build Artifact Caching**: Reuses previous builds when possible
- **Docker Volume Optimization**: Host bind mounts for better performance

### Test Execution

- **Docker Isolation**: Each test runs in its own container
- **Direct Fallback**: Runs on host when Docker unavailable
- **Result Detection**: Automatic pass/fail detection from test output
- **Keep-Alive Mode**: Debug failed tests with interactive container access
- **Retry Support**: Configurable retry count for flaky test detection

### Multi-Node Testing

Distribute tests across multiple tester nodes:
```json
{
  "workerIps": ["192.168.1.10", "192.168.1.11", "192.168.1.12"],
  "tests": ["test1.sh", "test2.sh", "test3.sh", "test4.sh", "test5.sh", "test6.sh"]
}
```
- Round-robin distribution ensures even load
- Automatic build package transfer via HTTP
- Local optimization for same-machine testers

### Reporting and Analysis

Interactive HTML reports provide:
- **Test Results Matrix**: Tests × Commits with pass/fail status
- **Automated Verdicts**: 
  - "Pass: Not reproduced" - No failures
  - "Bug or Revise: Caused by <commit>" - Single commit failure
  - "Pre-existing Failure" - All commits fail
  - "Unstable: Fails intermittently" - Partial failures
  - "Flaky: Passed after X attempts" - Retry successes
- **Statistics Dashboard**: Pass rate, failure counts, test categories
- **Export Options**: Download as JSON or CSV

## API Reference

### Builder Service (Port 8089)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/build` | POST | Submit build request |
| `/status` | GET | Query running tasks |
| `/health` | GET | Service health check |
| `/report` | GET | View test reports |
| `/callback` | POST | Receive test results |

### Tester Service (Port 8090)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/test` | POST | Execute single test |
| `/health` | GET | Service health check |

## Logging System

The system implements comprehensive logging with:
- **System Logs**: Global activity logs in `log/system/`
- **Request Logs**: Per-request logs in `log/requests/req_*/`
- **Automatic Cleanup**: Configurable retention policies
- **Thread Context**: Proper context propagation across threads

See [log/LOG_MANAGEMENT.md](log/LOG_MANAGEMENT.md) for details.

## Configuration

Key configuration files:
- `conf/builder.conf` - Builder service settings
- `conf/tester.conf` - Tester service settings

Important options:
```properties
# Concurrency
max_concurrent_builds=4
max_concurrent_tests=6

# Performance
ccache_enabled=true
parallel_jobs=0  # Auto-detect

# Logging
enable_request_grouping=true
max_request_logs=5
```

See [docs/configuration/README.md](docs/configuration/README.md) for all options.

## Project Structure

```
builder_tester/
├── src/                    # Java source code
│   └── com/navercorp/cubridqa/builder/
├── conf/                   # Configuration files
├── bin/                    # Shell scripts
├── lib/                    # Dependencies
├── build/                  # Compiled classes
├── log/                    # Log files
├── docs/                   # Documentation
├── examples/               # Example scripts
├── tests/                  # Test suite
└── report-server/          # Node.js report server
```

## Troubleshooting

Common issues and solutions:

| Issue | Solution |
|-------|----------|
| Docker permission denied | Ensure user has Docker access: `docker ps` |
| GITHUB_TOKEN not set | Export token before starting services |
| Build failures | Check `log/requests/req_*/builds/*.log` |
| Test failures | Check `log/requests/req_*/tests/*.log` |
| Port already in use | Change ports in configuration files |
| Tester unreachable | Verify network connectivity and firewall rules |

## Advanced Topics

- **Ccache Setup**: See [docs/CCACHE_GUIDE.md](docs/CCACHE_GUIDE.md)
- **Multi-Node Testing**: See [docs/MULTI_NODE_TESTING.md](docs/MULTI_NODE_TESTING.md)
- **Custom Docker Images**: Configure in `conf/*.conf`
- **Direct Execution Mode**: Set `use_docker=false` for non-Docker builds

## Contributing

When contributing to the Builder-Tester system:
1. Run the test suite: `cd tests && ./run_tests.sh`
2. Update documentation for new features
3. Follow existing code patterns and conventions
4. Add tests for new functionality
