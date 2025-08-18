# Features

## Core Features
- Concurrent builds: configurable via `max_concurrent_builds`
- Docker isolation for build and test
- Build caching to skip repeat work per commit + type
- Bounded test concurrency per build via `max_concurrent_tests`
- Per-task progress tracking and status endpoint
- Builder to Tester orchestration with callback on completion
- Robust logging under `~/cubrid-testtools/CTP/builder_tester/log` with request-based grouping and automatic cleanup
- Tester debug mode (keep-alive): returns container handle for interactive debugging
- Read-write testcase mount to allow tests to produce result files
- Result detection from test script base name: `foo.sh` → `foo.result`

## Advanced Features
- **Interactive Web Report Viewer**: Visualize test results with automated verdict analysis
- **Report Generation**: Automatic HTML reports with statistics, grouped by test case
- **Callback Support**: POST results to callback URL for automated report generation
- **Export Functionality**: Download test results as JSON or CSV from web interface
- **Flaky Test Detection**: Automatic detection and reporting of flaky tests with retry support
- **Multi-Node Testing**: Distribute tests across multiple tester nodes with round-robin algorithm
- **Ccache Support**: Compiler cache for 5-10x faster rebuilds
- **Build Package Transfer**: Automatic HTTP-based transfer for remote tester nodes
- **Isolated Commit Builds**: Each commit built in isolation against common baseline
- **Merge Commit Support**: Handles merge commits with mainline selection
