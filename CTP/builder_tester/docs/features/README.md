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
- **Interactive Web Report Viewer**: Enhanced modal system with accurate status color coding
  - **Modal Status Colors**: Green (pass), red (fail), orange (errors), gray (unknown)
  - **Context-Aware Navigation**: Smart back button functionality with proper context maintenance
  - **Multi-Attempt Log Viewing**: Individual log access for each test retry attempt
- **Report Generation**: Automatic HTML reports with statistics, grouped by test case
- **Callback Support**: POST results to callback URL for automated report generation
- **Export Functionality**: Download test results as JSON or CSV from web interface
- **Flaky Test Detection**: Automatic detection and reporting of flaky tests with retry support
- **Multi-Node Testing**: Distribute tests across multiple tester nodes with round-robin algorithm
- **Remote Log Retrieval**: HTTP-based log file transfer from remote testers to builder
  - **HTTP Log Endpoints**: `/log/{filename}` on testers serves log files to builders
  - **Cross-Request Log Search**: Finds log files across different request directories
  - **Metadata-Driven Transfer**: Uses `attemptLogMetadata` for accurate status preservation
- **Ccache Support**: Compiler cache for 5-10x faster rebuilds
- **Build Package Transfer**: Automatic HTTP-based transfer for remote tester nodes
- **Isolated Commit Builds**: Each commit built in isolation against common baseline
- **Merge Commit Support**: Handles merge commits with mainline selection
