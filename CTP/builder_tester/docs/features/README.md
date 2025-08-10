# Features

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
