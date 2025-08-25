# CUBRID Test Report Server

A modern, modular web-based dashboard and report viewer for CUBRID Builder-Tester test results.

## Architecture

This is a **modular Express.js application** with clean separation of concerns:

```
report-server/
├── src/
│   ├── server.js           # Main application entry point
│   ├── config/             # Configuration management
│   ├── controllers/        # Request handlers (builder, github, report, tester)
│   ├── middleware/         # Express middleware (cors, error handling)
│   ├── routes/            # Route definitions (api, dashboard, reports)
│   └── services/          # Business logic (file, github, proxy, report)
├── views/                 # EJS templates (dashboard, reports, overrides)
├── public/               # Static assets (CSS, JavaScript)
└── package.json          # Dependencies and scripts
```

## Features

### 🎛️ **Interactive Dashboard**
- **Modern Dark Theme UI**: Professional glassmorphism design with animated backgrounds
- **Build Request Interface**: Submit test requests with commit selection and worker management
- **GitHub Integration**: Browse and select commits directly from GitHub API
- **Advanced Configuration**: Timeout settings, run modes, environment variables
- **Real-time Status Monitoring**: Builder and tester health checks
- **Recent Reports**: Quick access to latest test results

### 📊 **Enhanced Report Viewer**
- **Multi-Attempt Log Display**: For tests with multiple attempts (PASS(3), FAIL(3), FLAKY(3))
- **Interactive Test Details**: Click test names for comprehensive information modals
- **Build Log Viewing**: Proper formatting with line breaks for build logs
- **Automatic Log Resolution**: Uses attemptLogMetadata for accurate log file paths
- **Export Options**: Download results as JSON or CSV

### 🔄 **Unified Test Execution Support**
- **Run Modes**: until-pass, until-fail, fixed-runs
- **Flaky Test Detection**: Unified logic across all run modes based on result consistency
- **Multi-Worker Support**: Distribute tests across multiple tester nodes
- **Callback Integration**: Automatic report generation on test completion

### 📈 **Verdict Analysis System**
Intelligent test failure pattern analysis:
- **Pass**: Not reproduced (0 failures)
- **Bug/Revise**: Caused by specific commit (1 failure)
- **Pre-existing**: Likely not caused by listed commits (all failures)
- **Unstable**: Fails intermittently across commits (partial failures)
- **Flaky**: Mixed pass/fail results across attempts
- **Error**: Test execution/environment failures

## Quick Start

### Installation & Setup

```bash
cd report-server
npm install
```

### Start the Server

```bash
# Development mode
npm run dev

# Production mode
npm start

# Or run directly
node src/server.js [port]
```

Default port is **8091**. The server creates log directories automatically.

### Access the Application

- **Dashboard**: http://localhost:8091/
- **Reports**: http://localhost:8091/reports
- **Health Check**: http://localhost:8091/health

## Usage

### 1. Dashboard Interface

Navigate to http://localhost:8091/ to access the modern dashboard:

1. **Select Commits**: Browse GitHub commits or enter commit SHAs manually
2. **Configure Tests**: Enter test case paths  
3. **Set Workers**: Add tester node IPs with health validation
4. **Advanced Options**: Configure timeouts, run modes, environment variables
5. **Submit Request**: Send build request with automatic callback URL

### 2. Monitoring & Reports

- **Build Status**: Real-time monitoring of active builds
- **Recent Reports**: Quick access to latest test results
- **Report Viewer**: Detailed test analysis with interactive elements

### 3. API Integration

Configure your Builder to use the callback URL:

```json
{
  "commits": ["abc123", "def456"],
  "tests": ["test1.sh", "test2.sh"],
  "callbackUrl": "http://localhost:8091/callback",
  "workerIps": ["tester1", "tester2"],
  "buildType": "debug",
  "runMode": "until-pass",
  "minRuns": 1,
  "maxRuns": 3
}
```

## Report Structure

Reports are stored in `~/cubrid-testtools/CTP/builder_tester/log/requests/`:
- `results.json` - Raw test data with attemptLogMetadata
- `report.html` - Interactive HTML report with enhanced features

## Development

### Project Structure

- **Modular Design**: Clean separation between routes, controllers, and services
- **EJS Templates**: Server-side rendering with data injection
- **Static Assets**: Organized CSS and JavaScript in public/ directory
- **Configuration**: Centralized config management
- **Error Handling**: Comprehensive error middleware

### Technology Stack

- **Backend**: Node.js, Express.js, EJS templating
- **Frontend**: Vanilla JavaScript, CSS3 with CSS variables
- **Security**: Helmet, CORS, rate limiting
- **Logging**: Morgan HTTP request logging
- **Compression**: Gzip compression for better performance

### API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | Main dashboard interface |
| GET | `/reports` | Reports listing page |
| GET | `/report?id=<req_id>` | Specific report viewer |
| POST | `/callback` | Test results callback handler |
| GET | `/health` | Health check endpoint |
| GET | `/api/local-ip` | Get server local IP |
| GET | `/api/builder/health` | Builder service health |
| GET | `/api/tester/health` | Tester service health |

## Deployment

### Environment Variables

```bash
# Server configuration
REPORT_PORT=8091
HOST=0.0.0.0

# Builder integration
BUILDER_HOST=localhost
BUILDER_PORT=8089

# GitHub integration
GITHUB_TOKEN=your_token

# Security
NODE_ENV=production
CORS_ORIGIN=*
```

### Production Deployment

```bash
# Install dependencies
npm ci --production

# Start with PM2 (recommended)
npm install -g pm2
pm2 start src/server.js --name "report-server"

# Or with systemd service
sudo systemctl start report-server
```

## Migration from Legacy Server

This modular version replaces the old monolithic `report-server-tobe.html` file with:
- ✅ Separate, maintainable CSS and JavaScript files
- ✅ EJS templating for dynamic content
- ✅ Proper Express.js routing and middleware
- ✅ Enhanced error handling and logging
- ✅ Improved security and performance features

All functionality from the legacy server has been preserved and enhanced.