# CUBRID Test Report Server

An interactive web-based report viewer for CUBRID Builder-Tester test results.

## Features

- **Callback Handler**: Receives test results via POST requests and automatically generates reports
- **Interactive Visualization**: Groups test results by test case with pass/fail status per commit
- **Multi-Attempt Log Display**: For tests with multiple attempts (PASS(3), FAIL(3), FLAKY(3)), shows clickable links to all attempt logs with status indicators
- **Enhanced Log Viewing**: 
  - Build logs with proper line break formatting
  - Execution logs with automatic path resolution using attemptLogMetadata
  - Test details modal with comprehensive information
- **Verdict Analysis**: Automatically determines test failure patterns using unified semantics:
  - Pass: Not reproduced (0 failures)
  - Bug or Revise: Caused by specific commit (1 failure) 
  - Pre-existing Failure: Likely not caused by listed commits (all failures)
  - Unstable: Fails intermittently across commits (partial failures)
  - Flaky: Mixed pass/fail results across attempts (regardless of run mode)
  - Error: Test execution failed (execution/environment errors)
- **Unified Test Execution Support**: Compatible with new run modes (until-pass, until-fail, fixed-runs)
- **Statistics Dashboard**: Shows pass rate, failed tests, unstable tests, error tests, and flaky tests at a glance
- **Export Options**: Download results as JSON or CSV (in the web interface)

## Installation

### Option 1: Standalone Node.js Server

```bash
cd report-server
node report-server.js [port]
```

Default port is 8091. The server will create necessary log directories automatically.

### Option 2: Integrated with Java Builder

The report functionality is also integrated into the main Builder service (port 8089):
- `/report` - View reports
- `/callback` - Receive results

## Usage

### Sending Test Results

Configure your Builder to use the callback URL:

```json
{
  "commits": ["abc123", "def456"],
  "tests": ["test1.sh", "test2.sh"],
  "callbackUrl": "http://localhost:8091/callback",
  "workerIp": "localhost",
  "buildType": "debug"
}
```

### Viewing Reports

1. **List all reports**: http://localhost:8091/report
2. **View specific report**: http://localhost:8091/report?id=req_xxxxx
3. **Health check**: http://localhost:8091/health

### Health endpoint

The standalone server exposes `/health`:

```bash
curl http://localhost:8091/health
```

Example response:

```json
{ "status": "healthy", "service": "report-server" }
```

- `status`: health state
- `service`: service identifier

## Report Structure

Reports are saved in `~/cubrid-testtools/CTP/builder_tester/log/requests/` with:
- `results.json` - Raw test data
- `report.html` - Interactive HTML report

## Report Interface

The web interface provides:

- **Header**: Request ID, timestamp, test/commit counts
- **Statistics Grid**: Visual KPIs for test results
- **Results Table**: 
  - Test cases as rows (clickable for details)
  - Commits as columns showing pass/fail status with attempt counts (e.g., PASS(3))
  - Fail count column
  - Verdict column with automated analysis
- **Enhanced Modals**:
  - Test details with comprehensive information per commit
  - Multi-attempt log viewer with status indicators (✅ Pass, ❌ Fail, ⚠️ Other)
  - Build log viewer with proper formatting
- **Export Buttons**: Download as JSON or CSV
- **Interactive Elements**: Click test names for details, click result cells for execution logs

## Verdict Logic

| Priority | Condition | Verdict |
|----------|-----------|---------|
| 1 | Mixed pass/fail across attempts | Flaky: Inconsistent results across X attempts |
| 2 | Build errors | Build errors in: [commit_ids] |
| 3 | Execution/environment errors | Error: Test execution failed |
| 4 | 0 failures | Pass: Not reproduced |
| 5 | 1 failure | Bug or Revise: Caused by [commit_id] |
| 6 | All commits fail | Pre-existing Failure: Likely not caused by listed commits |
| 7 | Partial failures | Unstable: Fails intermittently across commits |

**Note**: Flaky detection is unified across all run modes (until-pass, until-fail, fixed-runs) and based on result consistency, not attempt count relative to run mode.

## Unified Test Execution Semantics

The report server now supports the new unified test execution system:

### Run Modes
- **until-pass**: Run tests until they pass or max_runs reached
- **until-fail**: Run tests until they fail or max_runs reached  
- **fixed-runs**: Always run exactly min_runs to max_runs times

### Multi-Attempt Log Handling
- Tests with multiple attempts display as PASS(3), FAIL(3), or FLAKY(3)
- Click "View Execution Log" to see all attempt logs with status indicators
- Each attempt shows: ✅ Attempt 1: docker_opt_abc123_test.log
- Logs are automatically resolved using attemptLogMetadata from test results

## Development

The report uses modern web technologies:
- Glassmorphism design with gradient backgrounds
- Responsive layout for mobile/desktop
- Real-time statistics calculation
- Client-side data processing for performance

## Integration Example

```bash
# Start the report server
node report-server/report-server.js 8091 &

# Send a test request with callback
./bin/test_report_client.sh
```

The results will be automatically processed and a report will be generated and displayed.
