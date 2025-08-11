/**
 * CUBRID Test Report Server
 * 
 * A standalone Node.js server for receiving test results and generating
 * interactive HTML reports. This can be used as an alternative to the
 * Java-based report handler.
 * 
 * Usage:
 *   node report-server.js [port]
 * 
 * Default port: 8091
 */

const http = require('http');
const fs = require('fs').promises;
const path = require('path');
const url = require('url');
const querystring = require('querystring');

const PORT = process.argv[2] || 8091;
const LOG_BASE_DIR = path.join(
    process.env.HOME || process.env.USERPROFILE,
    'cubrid-testtools', 'CTP', 'builder_tester', 'log'
);

// Ensure log directories exist
async function ensureDirectories() {
    const requestsDir = path.join(LOG_BASE_DIR, 'requests');
    try {
        await fs.mkdir(requestsDir, { recursive: true });
        console.log(`✓ Log directory ready: ${requestsDir}`);
    } catch (err) {
        console.error(`✗ Failed to create log directory: ${err.message}`);
    }
}
// Generate HTML report template
function generateReportHTML(data, requestId) {
    const timestamp = new Date().toISOString();
    const resultsJSON = JSON.stringify(data);
    
    const html = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CUBRID Test Results - ${requestId}</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        :root {
            --primary-gradient: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            --success-color: #10b981;
            --error-color: #ef4444;
            --warning-color: #f59e0b;
            --info-color: #3b82f6;
        }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: var(--primary-gradient);
            min-height: 100vh;
            color: #1f2937;
            line-height: 1.6;
        }
        .container { max-width: 1400px; margin: 0 auto; padding: 2rem; }
        .header { 
            background: rgba(255,255,255,0.95); 
            backdrop-filter: blur(10px); 
            border-radius: 1rem; 
            padding: 2rem; 
            margin-bottom: 2rem; 
            box-shadow: 0 20px 25px -5px rgba(0,0,0,0.1); 
        }        .header h1 { 
            font-size: 2.5rem; 
            background: var(--primary-gradient); 
            -webkit-background-clip: text; 
            -webkit-text-fill-color: transparent; 
            margin-bottom: 0.5rem; 
        }
        .metadata { display: flex; gap: 2rem; color: #6b7280; font-size: 0.9rem; flex-wrap: wrap; }
        .stats-grid { 
            display: grid; 
            grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); 
            gap: 1.5rem; 
            margin-bottom: 2rem; 
        }
        .stat-card { 
            background: rgba(255,255,255,0.95); 
            backdrop-filter: blur(10px); 
            border-radius: 1rem; 
            padding: 1.5rem; 
        }
        .results-table { 
            background: rgba(255,255,255,0.98); 
            border-radius: 1rem; 
            overflow: hidden; 
        }
        table { width: 100%; border-collapse: collapse; }
        th { 
            padding: 1rem; 
            text-align: left; 
            font-weight: 600; 
            color: #374151; 
            background: #f3f4f6; 
        }
        td { padding: 1rem; border-bottom: 1px solid #e5e7eb; }
        .result-pass { background: #d1fae5; color: #065f46; padding: 0.25rem 0.75rem; border-radius: 0.375rem; }
        .result-fail { background: #fee2e2; color: #991b1b; padding: 0.25rem 0.75rem; border-radius: 0.375rem; }
        .result-error { background: #fef3c7; color: #92400e; padding: 0.25rem 0.75rem; border-radius: 0.375rem; }        .verdict { font-size: 0.9rem; padding: 0.5rem 1rem; border-radius: 0.5rem; font-weight: 500; }
        .verdict-unstable { background: #fef3c7; color: #92400e; }
        .verdict-bug { background: #fee2e2; color: #991b1b; }
        .verdict-preexisting { background: #e0e7ff; color: #3730a3; }
        .verdict-success { background: #d1fae5; color: #065f46; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>🧪 CUBRID Test Results</h1>
            <div class="metadata">
                <div><strong>Request ID:</strong> ${requestId}</div>
                <div><strong>Generated:</strong> ${timestamp}</div>
                <div><strong>Total Tests:</strong> <span id="totalTests">-</span></div>
                <div><strong>Commits:</strong> <span id="totalCommits">-</span></div>
            </div>
        </div>
        
        <div class="stats-grid">
            <div class="stat-card">
                <div style="font-size: 2.5rem; font-weight: bold; color: var(--success-color);" id="passedCount">0</div>
                <div style="color: #6b7280; font-size: 0.9rem;">Tests Passed</div>
            </div>
            <div class="stat-card">
                <div style="font-size: 2.5rem; font-weight: bold; color: var(--error-color);" id="failedCount">0</div>
                <div style="color: #6b7280; font-size: 0.9rem;">Tests Failed</div>
            </div>
            <div class="stat-card">
                <div style="font-size: 2.5rem; font-weight: bold; color: var(--warning-color);" id="unstableCount">0</div>
                <div style="color: #6b7280; font-size: 0.9rem;">Unstable Tests</div>
            </div>            <div class="stat-card">
                <div style="font-size: 2.5rem; font-weight: bold; color: var(--info-color);" id="passRate">0%</div>
                <div style="color: #6b7280; font-size: 0.9rem;">Pass Rate</div>
            </div>
        </div>
        
        <div class="results-table">
            <table id="resultsTable">
                <thead>
                    <tr id="tableHeader">
                        <th>Test Case</th>
                        <th>Fail Num</th>
                        <th>Verdict</th>
                    </tr>
                </thead>
                <tbody id="tableBody"></tbody>
            </table>
        </div>
    </div>
    
    <script>
        const rawData = ${resultsJSON};
        let processedData = {};
        let commits = [];
        
        window.addEventListener('DOMContentLoaded', () => {
            processData();
            renderTable();
        });
        
        function processData() {
            const commitSet = new Set();
            const testGroups = {};
            const results = rawData.results || rawData;            
            if (Array.isArray(results)) {
                results.forEach(result => {
                    const commit = result.commit || 'unknown';
                    const test = result.test || 'unknown';
                    const status = result.status || 'error';
                    
                    commitSet.add(commit);
                    if (!testGroups[test]) testGroups[test] = {};
                    testGroups[test][commit] = status;
                });
            }
            
            commits = Array.from(commitSet).sort();
            processedData = testGroups;
            
            document.getElementById('totalTests').textContent = Object.keys(testGroups).length;
            document.getElementById('totalCommits').textContent = commits.length;
            updateStatistics();
        }
        
        function updateStatistics() {
            let totalTests = 0, passedTests = 0, failedTests = 0, unstableTests = 0;
            
            Object.keys(processedData).forEach(test => {
                const failCount = calculateFailCount(test);
                totalTests++;
                
                if (failCount === 0) passedTests++;
                else if (failCount === commits.length) failedTests++;
                else unstableTests++;
            });
            
            document.getElementById('passedCount').textContent = passedTests;
            document.getElementById('failedCount').textContent = failedTests;            document.getElementById('unstableCount').textContent = unstableTests;
            document.getElementById('passRate').textContent = 
                (totalTests > 0 ? Math.round((passedTests / totalTests) * 100) : 0) + '%';
        }
        
        function calculateFailCount(testName) {
            let failCount = 0;
            commits.forEach(commit => {
                if ((processedData[testName][commit] || 'error') !== 'pass') failCount++;
            });
            return failCount;
        }
        
        function getVerdict(testName, failCount) {
            const numCommits = commits.length;
            
            if (failCount === 0) {
                return { text: "Unstable: Not reproduced", class: "verdict-success" };
            } else if (failCount === 1) {
                let failedCommit = null;
                commits.forEach(commit => {
                    if (processedData[testName][commit] !== 'pass') {
                        failedCommit = commit.substring(0, 7);
                    }
                });
                return { text: "Bug or Revise: Caused by " + failedCommit, class: "verdict-bug" };
            } else if (failCount === numCommits) {
                return { text: "Pre-existing Failure: Likely not caused by listed commits", class: "verdict-preexisting" };
            } else {
                return { text: "Unstable: Fails intermittently across commits", class: "verdict-unstable" };
            }
        }
        
        function renderTable() {
            const headerRow = document.getElementById('tableHeader');            const tbody = document.getElementById('tableBody');
            
            // Clear and add commit columns
            while (headerRow.children.length > 1) headerRow.removeChild(headerRow.children[1]);
            tbody.innerHTML = '';
            
            commits.forEach(commit => {
                const th = document.createElement('th');
                th.style.textAlign = 'center';
                th.innerHTML = commit.substring(0, 7);
                th.title = commit;
                headerRow.insertBefore(th, headerRow.children[headerRow.children.length - 2]);
            });
            
            // Add test rows
            Object.keys(processedData).sort().forEach(test => {
                const row = document.createElement('tr');
                const failCount = calculateFailCount(test);
                const verdict = getVerdict(test, failCount);
                
                // Test name
                const testCell = document.createElement('td');
                testCell.style.fontFamily = 'monospace';
                testCell.textContent = test;
                row.appendChild(testCell);
                
                // Commit results
                commits.forEach(commit => {
                    const cell = document.createElement('td');
                    cell.style.textAlign = 'center';
                    const status = processedData[test][commit] || 'error';
                    
                    let statusClass = 'result-error';
                    let statusText = '⚠️ ERROR';
                                        if (status === 'pass') {
                        statusClass = 'result-pass';
                        statusText = '✅ PASS';
                    } else if (status === 'fail') {
                        statusClass = 'result-fail';
                        statusText = '❌ FAIL';
                    } else if (status === 'build_failed') {
                        statusText = '🔨 BUILD';
                    }
                    
                    cell.innerHTML = '<span class="' + statusClass + '">' + statusText + '</span>';
                    row.appendChild(cell);
                });
                
                // Fail count
                const failCountCell = document.createElement('td');
                failCountCell.style.fontWeight = 'bold';
                failCountCell.style.color = failCount === 0 ? '#065f46' : 
                                           failCount === commits.length ? '#991b1b' : '#92400e';
                failCountCell.textContent = failCount;
                row.appendChild(failCountCell);
                
                // Verdict
                const verdictCell = document.createElement('td');
                verdictCell.innerHTML = '<span class="verdict ' + verdict.class + '">' + verdict.text + '</span>';
                row.appendChild(verdictCell);
                
                tbody.appendChild(row);
            });
        }
    </script>
</body>
</html>`;
    
    return html;
}
// Handle HTTP requests
async function handleRequest(req, res) {
    const parsedUrl = url.parse(req.url, true);
    const pathname = parsedUrl.pathname;
    
    if (pathname === '/callback' && req.method === 'POST') {
        // Handle callback POST request
        let body = '';
        
        req.on('data', chunk => {
            body += chunk.toString();
        });
        
        req.on('end', async () => {
            try {
                const data = JSON.parse(body);
                
                // Generate request ID
                const requestId = 'req_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
                const requestDir = path.join(LOG_BASE_DIR, 'requests', requestId);
                
                // Create directory and save files
                await fs.mkdir(requestDir, { recursive: true });
                
                // Save JSON data
                await fs.writeFile(
                    path.join(requestDir, 'results.json'),
                    JSON.stringify(data, null, 2)
                );
                
                // Generate and save HTML report
                const reportHtml = generateReportHTML(data, requestId);
                await fs.writeFile(
                    path.join(requestDir, 'report.html'),
                    reportHtml
                );                
                console.log('✓ Saved report: ' + requestId);
                
                // Return HTML report as response
                res.writeHead(200, { 'Content-Type': 'text/html; charset=UTF-8' });
                res.end(reportHtml);
                
            } catch (err) {
                console.error('✗ Error handling callback: ' + err.message);
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        
    } else if (pathname === '/report' && req.method === 'GET') {
        // Handle report viewing
        const requestId = parsedUrl.query.id;
        
        if (requestId) {
            // View specific report
            const reportPath = path.join(LOG_BASE_DIR, 'requests', requestId, 'report.html');
            
            try {
                const reportHtml = await fs.readFile(reportPath, 'utf8');
                res.writeHead(200, { 'Content-Type': 'text/html; charset=UTF-8' });
                res.end(reportHtml);
            } catch (err) {
                res.writeHead(404, { 'Content-Type': 'text/html' });
                res.end('<h1>Report not found</h1>');
            }
        } else {
            // List all reports
            try {
                const requestsDir = path.join(LOG_BASE_DIR, 'requests');
                const dirs = await fs.readdir(requestsDir);                
                const reports = [];
                for (const dir of dirs) {
                    const reportFile = path.join(requestsDir, dir, 'report.html');
                    try {
                        const stats = await fs.stat(reportFile);
                        reports.push({
                            id: dir,
                            modified: stats.mtime
                        });
                    } catch (err) {
                        // Skip directories without report.html
                    }
                }
                
                // Sort by modified time (newest first)
                reports.sort((a, b) => b.modified - a.modified);
                
                // Generate listing HTML
                let listHtml = '<!DOCTYPE html><html><head>';
                listHtml += '<title>Test Reports</title>';
                listHtml += '<style>';
                listHtml += 'body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; ';
                listHtml += 'background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ';
                listHtml += 'color: white; padding: 2rem; min-height: 100vh; margin: 0; }';
                listHtml += '.container { max-width: 1200px; margin: 0 auto; }';
                listHtml += 'h1 { font-size: 2.5rem; margin-bottom: 2rem; }';
                listHtml += '.report-list { background: rgba(255,255,255,0.1); backdrop-filter: blur(10px); ';
                listHtml += 'border-radius: 1rem; padding: 2rem; }';
                listHtml += '.report-item { display: flex; justify-content: space-between; align-items: center; ';
                listHtml += 'padding: 1rem; margin-bottom: 1rem; background: rgba(255,255,255,0.05); ';
                listHtml += 'border-radius: 0.5rem; transition: all 0.3s ease; }';
                listHtml += '.report-item:hover { background: rgba(255,255,255,0.15); transform: translateX(10px); }';
                listHtml += 'a { color: white; text-decoration: none; font-weight: 500; }';
                listHtml += '.timestamp { opacity: 0.8; font-size: 0.9rem; }';                listHtml += '</style></head><body>';
                listHtml += '<div class="container">';
                listHtml += '<h1>📊 Test Report Viewer</h1>';
                listHtml += '<div class="report-list">';
                
                if (reports.length === 0) {
                    listHtml += '<p>No reports available</p>';
                } else {
                    reports.forEach(r => {
                        listHtml += '<div class="report-item">';
                        listHtml += '<a href="/report?id=' + r.id + '">📁 ' + r.id + '</a>';
                        listHtml += '<span class="timestamp">' + r.modified.toISOString() + '</span>';
                        listHtml += '</div>';
                    });
                }
                
                listHtml += '</div></div></body></html>';
                
                res.writeHead(200, { 'Content-Type': 'text/html; charset=UTF-8' });
                res.end(listHtml);
                
            } catch (err) {
                res.writeHead(500, { 'Content-Type': 'text/html' });
                res.end('<h1>Error listing reports</h1>');
            }
        }
        
    } else if (pathname === '/health' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'healthy', service: 'report-server' }));
        
    } else {
        res.writeHead(404, { 'Content-Type': 'text/html' });
        res.end('<h1>Not Found</h1><p>Available endpoints: /callback (POST), /report (GET), /health (GET)</p>');
    }
}
// Create and start server
const server = http.createServer(handleRequest);

async function startServer() {
    await ensureDirectories();
    
    server.listen(PORT, () => {
        console.log('');
        console.log('╔════════════════════════════════════════════╗');
        console.log('║     CUBRID Test Report Server Started      ║');
        console.log('╠════════════════════════════════════════════╣');
        console.log('║  Port:     ' + PORT.toString().padEnd(32) + '║');
        console.log('║  Callback: http://localhost:' + PORT + '/callback  ║');
        console.log('║  Reports:  http://localhost:' + PORT + '/report    ║');
        console.log('║  Health:   http://localhost:' + PORT + '/health    ║');
        console.log('╚════════════════════════════════════════════╝');
        console.log('');
        console.log('Press Ctrl+C to stop the server');
    });
}

// Handle shutdown gracefully
process.on('SIGINT', () => {
    console.log('\n\nShutting down server...');
    server.close(() => {
        console.log('Server stopped.');
        process.exit(0);
    });
});

// Start the server
startServer().catch(err => {
    console.error('Failed to start server:', err);
    process.exit(1);
});
