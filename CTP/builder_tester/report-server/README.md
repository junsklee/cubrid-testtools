# CUBRID Test Report Server

A modern, modular web-based dashboard and report viewer for CUBRID Builder-Tester test results.

## Architecture

This is a **modular Express.js application** with clean separation of concerns:

```
report-server/
├── src/
│   ├── server.js           # Main application entry point
│   ├── config/             # Configuration management
│   │   └── index.js        # Centralized configuration
│   ├── controllers/        # Request handlers
│   │   ├── builderController.js  # Builder service proxy
│   │   ├── githubController.js   # GitHub API integration
│   │   ├── reportController.js   # Report generation and viewing
│   │   └── testerController.js   # Tester health checks
│   ├── middleware/         # Express middleware
│   │   ├── cors.js         # CORS configuration
│   │   └── errorHandler.js # Error handling
│   ├── routes/            # Route definitions
│   │   ├── api.js         # API endpoints
│   │   ├── dashboard.js   # Dashboard routes
│   │   ├── health.js      # Health check routes
│   │   └── reports.js     # Report routes
│   └── services/          # Business logic
│       ├── fileService.js    # File system operations
│       ├── githubService.js  # GitHub API client
│       ├── proxyService.js   # HTTP proxy utilities
│       └── reportService.js  # Report generation logic
├── views/                 # EJS templates
│   ├── dashboard.ejs      # Main dashboard template
│   ├── reports.ejs        # Reports listing template
│   └── overrides.ejs      # Client script overrides
├── public/               # Static assets
│   ├── css/
│   │   ├── dashboard.css # Dashboard styles
│   │   └── report.css    # Report viewer styles
│   └── js/
│       ├── dashboard.js  # Dashboard client logic
│       └── report.js     # Report viewer logic
├── .env                  # Environment configuration
├── package.json          # Dependencies and scripts
└── package-lock.json     # Locked dependency versions
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

Reports are stored in `~/cubrid-testtools/CTP/builder_tester/log/requests/` (directory name matches taskId):
- `results.json` - Raw test data; includes `requestId`, `taskId`, `results` (array), and attempt metadata
- `report.html` - Interactive HTML report (integrated template)
- `tests/*.log` - Test execution logs per test/attempt (e.g., docker_opt_<commit>_<test>.log)
- `builds/*.log` - Build logs per commit (e.g., build_<commit7>.log)

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

#### Dashboard & UI
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | Main dashboard interface |
| GET | `/reports` | Reports listing page |
| GET | `/report?id=<req_id>` | Specific report viewer |
| GET | `/ui/overrides.js` | Dashboard override scripts |
| GET | `/ui/reports.js` | Reports page scripts |

#### Reports & Callbacks
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/callback` | Test results callback handler (prefers taskId for directory) |
| GET | `/api/logs/<req_id>/tests` | List test logs (array of filenames) |
| GET | `/api/log/<req_id>/tests/<filename>` | Get specific test log |
| GET | `/api/logs/<req_id>/builds` | List build logs (array of filenames) |
| GET | `/api/log/<req_id>/builds/<filename>` | Get specific build log |
| GET | `/api/log-root/<req_id>/<filename>` | Get system logs |

#### System & Health
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Health check endpoint |
| GET | `/api/local-ip` | Get server local IP and hostname |

#### GitHub Integration
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/github/commits` | Get commits (supports pagination) |
| GET | `/api/github/validate/<sha>` | Validate commit SHA |
| GET | `/api/github/commit/<sha>` | Get commit details |

#### Builder Proxy
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/builder/build` | Submit build request |
| GET | `/api/builder/status` | Get build status |
| GET | `/api/builder/health` | Builder service health |
| ALL | `/api/builder/*` | Proxy other builder endpoints |

#### Tester Integration
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/tester/health` | Tester service health check |

## Deployment

### Environment Variables

Create a `.env` file in the report-server directory:

```bash
# Server Configuration
REPORT_PORT=8091           # Server port (default: 8091)
HOST=0.0.0.0              # Host binding (default: 0.0.0.0)
NODE_ENV=production       # Environment (development/production)

# Builder Integration
BUILDER_HOST=localhost    # Builder service host
BUILDER_PORT=8089        # Builder service port
BUILDER_PROTOCOL=http    # Protocol (http/https)

# GitHub Integration (Optional)
GITHUB_TOKEN=ghp_xxx     # Personal access token for API rate limits

# Security Settings
CORS_ORIGIN=*            # CORS allowed origins
RATE_LIMIT_MAX=100       # Max requests per 15 minutes

# Paths (Auto-configured)
# LOG_BASE_DIR=~/cubrid-testtools/CTP/builder_tester/log
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

## Implementation Notes

- Report HTML is generated from `src/com/navercorp/cubridqa/builder/report/report-template.html` with placeholders `{{REQUEST_ID}}`, `{{TIMESTAMP}}`, and `{{RESULTS_JSON}}` substituted at generation time to provide the full interactive UI.
- Callback handling normalizes payload fields so the directory id, `requestId`, and `taskId` are consistent (prefers `taskId` for legacy parity).
- Log APIs return arrays (not wrapped objects) for compatibility with the integrated viewer logic.
- Root logs are accessible via `/api/log-root/<req_id>/<filename>` (e.g., builder.log, tester.log).

## Troubleshooting

### Common Issues

1. **Port Already in Use**
   ```bash
   # Find process using port 8091
   lsof -i :8091
   # Kill the process or use different port
   node src/server.js 8092
   ```

2. **GitHub API Rate Limit**
   - Set `GITHUB_TOKEN` in `.env` file
   - Use a GitHub personal access token

3. **Builder Connection Failed**
   - Verify Builder is running: `curl http://localhost:8089/health`
   - Check `BUILDER_HOST` and `BUILDER_PORT` in `.env`

4. **Missing Dependencies**
   ```bash
   npm ci  # Clean install from package-lock.json
   ```

5. **Permission Errors**
   - Ensure write permissions for log directories
   - Check `~/cubrid-testtools/CTP/builder_tester/log/` permissions

## Contributing

### Development Workflow

1. **Setup Development Environment**
   ```bash
   git clone <repository>
   cd report-server
   npm install
   cp .env.example .env  # Configure environment
   npm run dev           # Start in development mode
   ```

2. **Code Style**
   - Follow modular pattern: routes → controllers → services
   - Keep functions small and focused
   - Use async/await for asynchronous operations
   - Add JSDoc comments for public functions

3. **Adding New Features**
   - **New API endpoint**: Create controller, service, and route
   - **New UI page**: Add EJS template and route
   - **New middleware**: Add to `src/middleware/` and register in server.js

4. **Testing**
   ```bash
   # Manual testing
   curl http://localhost:8091/health
   
   # Submit test request
   curl -X POST http://localhost:8091/callback \
     -H "Content-Type: application/json" \
     -d '{"requestId": "test_123", "results": {}}'
   ```

## License

Apache License 2.0 - See LICENSE file for details

## Support

For issues or questions:
- Check the main Builder-Tester documentation
- Review logs in `~/cubrid-testtools/CTP/builder_tester/log/`
- Contact the CUBRID QA Team