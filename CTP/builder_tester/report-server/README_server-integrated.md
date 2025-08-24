# CUBRID Builder-Tester Report Server - Integrated Dashboard

## Overview

This is a fully integrated web dashboard for the CUBRID Builder-Tester system. It provides an interactive UI for submitting build requests, monitoring build status, and viewing test results.

## Features

### 🚀 Interactive Dashboard
- **Commit Selection**: 
  - Select from the latest 30 commits from CUBRID/cubrid develop branch
  - Load more commits with pagination (30 at a time)
  - Manual entry option for specific commit SHAs
  - Automatic validation of manually entered commits

- **Test Management**:
  - Enter test cases line by line
  - Load sample test cases with one click
  - Support for multiple test formats

- **Worker Configuration**:
  - Visual chip-based display of worker nodes
  - Add/remove workers dynamically
  - Support for multiple tester nodes with round-robin distribution

- **Build Configuration**:
  - Select build type (debug/release/profile)
  - Configure callback URL
  - Set custom timeout and retry counts

### 📊 System Monitoring & Enhanced Reporting
- **Builder Health Check**: View builder status, version, active tasks, and configuration
- **Tester Health Check**: Check any tester node's status and capabilities
- **Real-time Build Status**: Monitor build progress with live updates
- **Advanced Test Result Visualization**: 
  - Interactive test reports with unified execution semantics support
  - Multi-attempt log display for PASS(3), FAIL(3), FLAKY(3) results
  - Enhanced modals with comprehensive test details
  - Proper log formatting with line breaks and status indicators
  - Unified flaky test detection across all run modes

### 🎨 Modern UI/UX
- Dark mode theme with glassmorphic design
- Smooth animations and transitions
- Toast notifications for user feedback
- Responsive design for desktop and mobile
- Loading states and error handling

## Installation

1. **Prerequisites**:
   ```bash
   # Node.js 14+ required
   node --version
   
   # Set GitHub token (recommended to avoid rate limits)
   export GITHUB_TOKEN=your_github_token
   ```

2. **Start the Report Server**:
   ```bash
   cd /Users/jun/cubrid-testtools/CTP/builder_tester
   ./bin/start_report_server.sh
   ```

   Or manually:
   ```bash
   cd report-server
   BUILDER_HOST=localhost BUILDER_PORT=8089 node report-server-integrated.js
   ```

3. **Access the Dashboard**:
   ```
   http://localhost:8091
   ```

## Configuration

### Environment Variables
- `BUILDER_HOST`: Builder service hostname (default: localhost)
- `BUILDER_PORT`: Builder service port (default: 8089)
- `REPORT_PORT`: Report server port (default: 8091)
- `GITHUB_TOKEN`: GitHub personal access token for API requests

### Configuration File
The server reads configuration from `../conf/builder.conf` if available.

## API Endpoints

### Dashboard Endpoints
- `GET /` - Main dashboard interface
- `GET /reports` - View all test reports
- `GET /report?file=<filename>` - View specific report
- `GET /health` - Report server health check

### API Endpoints

#### Dashboard Endpoints
- `GET /` - Main dashboard interface
- `GET /reports` - View all test reports
- `GET /report?id=<req_id>` - View specific report
- `GET /health` - Report server health check

#### External API Proxies
- `GET /api/github/commits?page=<n>` - Fetch commits from GitHub
- `GET /api/github/validate/<sha>` - Validate commit SHA
- `POST /api/builder/build` - Submit build request (supports new unified execution parameters)
- `GET /api/builder/status?taskId=<id>` - Get build status
- `GET /api/builder/health` - Builder health check
- `GET /api/tester/health?ip=<ip:port>` - Tester health check

#### Report & Log Endpoints
- `POST /callback` - Receive test results and generate reports
- `GET /api/log/<req_id>/tests/<filename>` - Get specific test execution log
- `GET /api/logs/<req_id>/tests` - List available test logs
- `GET /api/logs/<req_id>/builds` - List available build logs
- `GET /api/log-root/<req_id>/<filename>` - Get system logs (builder.log, tester.log)

## Usage Examples

### 1. Submit a Build Request
1. Open the dashboard at http://localhost:8091
2. Select commits from GitHub or enter manually
3. Add test cases (or load samples)
4. Configure worker IPs if needed
5. Click "Send Build Request"

### 2. Monitor Build Progress
- After submitting, the status monitor automatically updates every 5 seconds
- View per-commit progress and test completion status

### 3. Check System Health
- Click "System Info" button in the header
- View Builder configuration and status
- Enter any Tester IP to check its health

### 4. View Test Reports
- Click "View Reports" in the header
- Browse all available test reports
- Click on any report to view detailed results

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────┐
│                 │     │                  │     │             │
│  Web Browser    │────▶│  Report Server   │────▶│   Builder   │
│  (Dashboard)    │     │   (Port 8091)    │     │ (Port 8089) │
│                 │     │                  │     │             │
└─────────────────┘     └──────────────────┘     └─────────────┘
                               │                        │
                               │                        │
                               ▼                        ▼
                        ┌──────────────────┐     ┌─────────────┐
                        │                  │     │             │
                        │     GitHub       │     │   Tester    │
                        │      API         │     │ (Port 8090) │
                        │                  │     │             │
                        └──────────────────┘     └─────────────┘
```

## Troubleshooting

### Common Issues

1. **GitHub API Rate Limit**:
   - Set `GITHUB_TOKEN` environment variable
   - Use a GitHub personal access token

2. **Builder Not Accessible**:
   - Check if Builder is running: `curl http://localhost:8089/health`
   - Verify BUILDER_HOST and BUILDER_PORT settings

3. **Commits Not Loading**:
   - Check network connectivity to GitHub
   - Verify GITHUB_TOKEN is valid
   - Check browser console for errors

4. **Build Request Fails**:
   - Ensure at least one worker IP is configured
   - Verify callback URL is accessible from Builder
   - Check Builder logs for detailed error messages

## Development

### File Structure
```
report-server/
├── report-server-integrated.js  # Main server file with embedded UI
├── package.json                  # Node.js package configuration
├── README.md                     # This file
└── report-server.js             # Original report server (backup)
```

### Customization
- Modify styles in the `getDashboardStyles()` function
- Update UI components in the `generateDashboardHTML()` function
- Add new API endpoints in the `handleRequest()` function
- Customize client-side logic in the `getDashboardScript()` function

## Security Considerations

- Always use HTTPS in production environments
- Protect the GitHub token and never expose it client-side
- Implement authentication for production deployments
- Validate all user inputs on the server side
- Use environment-specific callback URLs

## License

Apache License 2.0 - See LICENSE file for details

## Support

For issues or questions:
- Check the main Builder-Tester documentation
- Review logs in `~/cubrid-testtools/CTP/builder_tester/log/`
- Contact the CUBRID QA Team
