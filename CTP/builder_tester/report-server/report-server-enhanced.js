/**
 * CUBRID Test Report Server - Enhanced Version
 * 
 * Enhanced features:
 * - Build log viewer
 * - Test execution log viewer
 * - Modal for test case details
 * - Clickable result cells to view logs
 * 
 * Usage:
 *   node report-server-enhanced.js [port]
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
        console.log(`Log directory ready: ${requestsDir}`);
    } catch (err) {
        console.error(`Failed to create log directory: ${err.message}`);
    }
}

// Helper function to read log files
async function readLogFile(requestId, logType, fileName) {
    try {
        const logPath = path.join(LOG_BASE_DIR, 'requests', requestId, logType, fileName);
        const content = await fs.readFile(logPath, 'utf8');
        return { success: true, content };
    } catch (err) {
        return { success: false, error: err.message };
    }
}

// Helper function to list files in a directory
async function listLogFiles(requestId, logType) {
    try {
        const dirPath = path.join(LOG_BASE_DIR, 'requests', requestId, logType);
        const files = await fs.readdir(dirPath);
        return { success: true, files };
    } catch (err) {
        return { success: false, files: [] };
    }
}

// Generate enhanced HTML report template
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
        }        .container { max-width: 1400px; margin: 0 auto; padding: 2rem; }
        .header { 
            background: rgba(255,255,255,0.95); 
            backdrop-filter: blur(10px); 
            border-radius: 1rem; 
            padding: 2rem; 
            margin-bottom: 2rem; 
            box-shadow: 0 20px 25px -5px rgba(0,0,0,0.1); 
        }
        .header h1 { 
            font-size: 2.5rem; 
            background: var(--primary-gradient); 
            -webkit-background-clip: text; 
            -webkit-text-fill-color: transparent; 
            margin-bottom: 0.5rem; 
        }
        .metadata { display: flex; gap: 2rem; color: #6b7280; font-size: 0.9rem; flex-wrap: wrap; }
        .action-buttons {
            display: flex;
            gap: 1rem;
            margin-top: 1rem;
        }
        .btn {
            padding: 0.5rem 1rem;
            background: var(--primary-gradient);
            color: white;
            border: none;
            border-radius: 0.5rem;
            cursor: pointer;
            font-weight: 500;
            transition: transform 0.2s;
        }
        .btn:hover {
            transform: scale(1.05);
        }
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
        .test-name {
            font-family: monospace;
            cursor: pointer;
            color: #3b82f6;
            text-decoration: underline;
        }
        .test-name:hover {
            color: #2563eb;
        }
        .result-cell {
            cursor: pointer;
            transition: background-color 0.2s;
        }
        .result-cell:hover {
            background-color: #f3f4f6;
        }
        .result-pass { background: #d1fae5; color: #065f46; padding: 0.25rem 0.75rem; border-radius: 0.375rem; }
        .result-fail { background: #fee2e2; color: #991b1b; padding: 0.25rem 0.75rem; border-radius: 0.375rem; }
        .result-error { background: #fef3c7; color: #92400e; padding: 0.25rem 0.75rem; border-radius: 0.375rem; }
        .result-flaky { background: #e0f2fe; color: #0c4a6e; padding: 0.25rem 0.75rem; border-radius: 0.375rem; }        .verdict { font-size: 0.9rem; padding: 0.5rem 1rem; border-radius: 0.5rem; font-weight: 500; }
        .verdict-error {
            background: linear-gradient(135deg, #fef3c7 0%, #fde68a 100%);
            color: #92400e;
        }
        .verdict-flaky {
            background: linear-gradient(135deg, #e0f2fe 0%, #bae6fd 100%);
            color: #0c4a6e;
        }
        .verdict-bug { background: #fee2e2; color: #991b1b; }
        .verdict-preexisting { background: #e0e7ff; color: #3730a3; }
        .verdict-success { background: #d1fae5; color: #065f46; }
        
        /* Modal styles */
        .modal {
            display: none;
            position: fixed;
            z-index: 1000;
            left: 0;
            top: 0;
            width: 100%;
            height: 100%;
            background-color: rgba(0, 0, 0, 0.5);
            animation: fadeIn 0.3s;
        }
        .modal-content {
            background: white;
            margin: 5% auto;
            padding: 2rem;
            border-radius: 1rem;
            width: 90%;
            max-width: 1200px;
            max-height: 80vh;
            overflow-y: auto;
            animation: slideIn 0.3s;
        }
        .modal-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 1.5rem;
            padding-bottom: 1rem;
            border-bottom: 2px solid #e5e7eb;
        }
        .modal-title {
            font-size: 1.5rem;
            font-weight: 600;
            color: #1f2937;
        }
        .close-btn {
            background: none;
            border: none;
            font-size: 2rem;
            cursor: pointer;
            color: #6b7280;
        }
        .close-btn:hover {
            color: #374151;
        }
        .log-viewer {
            background: #1f2937;
            color: #e5e7eb;
            padding: 1rem;
            border-radius: 0.5rem;
            font-family: 'Courier New', monospace;
            font-size: 0.875rem;
            line-height: 1.5;
            overflow-x: auto;
            max-height: 500px;
            overflow-y: auto;
        }
        .log-section {
            margin-bottom: 1.5rem;
        }
        .log-section-title {
            font-size: 1.1rem;
            font-weight: 600;
            color: #374151;
            margin-bottom: 0.5rem;
        }
        .file-list {
            display: flex;
            flex-direction: column;
            gap: 0.5rem;
        }
        .file-link {
            color: #3b82f6;
            text-decoration: none;
            padding: 0.5rem;
            background: #f3f4f6;
            border-radius: 0.375rem;
            display: inline-block;
            transition: background-color 0.2s;
        }
        .file-link:hover {
            background: #e5e7eb;
        }
        @keyframes fadeIn {
            from { opacity: 0; }
            to { opacity: 1; }
        }
        @keyframes slideIn {
            from { transform: translateY(-50px); opacity: 0; }
            to { transform: translateY(0); opacity: 1; }
        }
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
            <div class="action-buttons">
                <button class="btn" onclick="viewBuildLogs()">View Build Logs</button>
                <button class="btn" onclick="exportJSON()">Export JSON</button>
                <button class="btn" onclick="exportCSV()">Export CSV</button>
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
            </div>
            <div class="stat-card">
                <div style="font-size: 2.5rem; font-weight: bold; color: #92400e;" id="errorCount">0</div>
                <div style="color: #6b7280; font-size: 0.9rem;">Error Tests</div>
            </div>
            <div class="stat-card">
                <div style="font-size: 2.5rem; font-weight: bold; color: #0c4a6e;" id="flakyCount">0</div>
                <div style="color: #6b7280; font-size: 0.9rem;">Flaky Tests</div>
            </div>
            <div class="stat-card">
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
    
    <!-- Modal for viewing details -->
    <div id="detailModal" class="modal">
        <div class="modal-content">
            <div class="modal-header">
                <h2 class="modal-title" id="modalTitle">Test Details</h2>
                <button class="close-btn" onclick="closeModal()">&times;</button>
            </div>
            <div id="modalBody"></div>
        </div>
    </div>    
    <script>
        const rawData = ${resultsJSON};
        const requestId = '${requestId}';
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
                    const flaky = result.flaky || false;
                    const attempts = result.attempts || 1;
                    const logPath = result.logPath || null;
                    
                    commitSet.add(commit);
                    if (!testGroups[test]) testGroups[test] = {};
                    
                    testGroups[test][commit] = {
                        status: status,
                        flaky: flaky,
                        attempts: attempts,
                        logPath: logPath,
                        message: result.message || ''
                    };
                });
            }
            
            commits = Array.from(commitSet).sort();
            processedData = testGroups;
            
            document.getElementById('totalTests').textContent = Object.keys(testGroups).length;
            document.getElementById('totalCommits').textContent = commits.length;
            updateStatistics();
        }        
        function updateStatistics() {
            let totalTests = 0, passedTests = 0, failedTests = 0, unstableTests = 0, errorTests = 0, flakyTests = 0;
            
            Object.keys(processedData).forEach(test => {
                const verdict = getTestVerdict(test);
                totalTests++;
                
                if (verdict.class === 'verdict-success') passedTests++;
                else if (verdict.class === 'verdict-bug' || verdict.class === 'verdict-preexisting') failedTests++;
                else if (verdict.class === 'verdict-unstable') unstableTests++;
                else if (verdict.class === 'verdict-error') errorTests++;
                else if (verdict.class === 'verdict-flaky') flakyTests++;
            });
            
            document.getElementById('passedCount').textContent = passedTests;
            document.getElementById('failedCount').textContent = failedTests;
            document.getElementById('unstableCount').textContent = unstableTests;
            document.getElementById('errorCount').textContent = errorTests;
            document.getElementById('flakyCount').textContent = flakyTests;
            document.getElementById('passRate').textContent = 
                (totalTests > 0 ? Math.round((passedTests / totalTests) * 100) : 0) + '%';
        }
        
        function calculateFailCount(testName) {
            let failCount = 0;
            commits.forEach(commit => {
                const result = processedData[testName][commit];
                if (result && result.status !== 'pass') failCount++;
            });
            return failCount;
        }        
        function getTestVerdict(testName) {
            // Check for flaky test first
            let hasFlaky = false;
            let maxAttempts = 1;
            
            commits.forEach(commit => {
                const result = processedData[testName][commit];
                if (result && result.flaky) {
                    hasFlaky = true;
                    if (result.attempts > maxAttempts) maxAttempts = result.attempts;
                }
            });
            
            if (hasFlaky) {
                return { text: 'Flaky: Passed after ' + maxAttempts + ' attempts', class: 'verdict-flaky' };
            }
            
            // Check for errors
            const errorCommits = [];
            const buildErrs = [];
            commits.forEach(commit => {
                const result = processedData[testName][commit];
                if (!result) return;
                
                const statusLower = result.status.toString().toLowerCase();
                if (statusLower === 'build_failed') {
                    buildErrs.push(commit.substring(0,7));
                } else if (statusLower === 'execution_error' || statusLower === 'environment_error' || statusLower === 'error') {
                    errorCommits.push(commit.substring(0,7));
                }
            });
            
            if (buildErrs.length > 0) {
                return { text: 'Build errors in: ' + buildErrs.join(', '), class: 'verdict-bug' };
            }
            if (errorCommits.length > 0) {
                return { text: 'Error: Test execution failed', class: 'verdict-error' };
            }
            
            // Traditional verdict logic
            const failCount = calculateFailCount(testName);
            const numCommits = commits.length;
            
            if (failCount === 0) {
                return { text: "Pass: Not reproduced", class: "verdict-success" };
            } else if (failCount === 1) {
                let failedCommit = null;
                commits.forEach(commit => {
                    const result = processedData[testName][commit];
                    if (result && result.status !== 'pass') {
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
            const headerRow = document.getElementById('tableHeader');
            const tbody = document.getElementById('tableBody');
            
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
                const verdict = getTestVerdict(test);
                const failCount = calculateFailCount(test);
                
                // Test name - clickable to show details
                const testCell = document.createElement('td');
                testCell.innerHTML = '<span class="test-name" onclick="showTestDetails(\'' + 
                    test.replace(/'/g, "\\'") + '\')">' + test + '</span>';
                row.appendChild(testCell);
                
                // Commit results - clickable to show logs
                commits.forEach(commit => {
                    const cell = document.createElement('td');
                    cell.className = 'result-cell';
                    cell.style.textAlign = 'center';
                    
                    const result = processedData[test][commit];
                    if (!result) {
                        cell.innerHTML = '<span class="result-error">N/A</span>';
                        row.appendChild(cell);
                        return;
                    }
                    
                    let statusClass = 'result-error';
                    let statusText = 'ERROR';
                    
                    if (result.status === 'pass') {
                        statusClass = result.flaky ? 'result-flaky' : 'result-pass';
                        statusText = result.flaky ? 'FLAKY(' + result.attempts + ')' : 'PASS';
                    } else if (result.status === 'fail') {
                        statusClass = 'result-fail';
                        statusText = 'FAIL';
                    } else if (result.status === 'build_failed') {
                        statusText = 'BUILD';
                    }
                    
                    cell.innerHTML = '<span class="' + statusClass + '" onclick="showExecutionLog(\'' + 
                        test.replace(/'/g, "\\'") + '\', \'' + commit + '\')">' + statusText + '</span>';
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
        // Modal functions
        function showModal(title, content) {
            document.getElementById('modalTitle').textContent = title;
            document.getElementById('modalBody').innerHTML = content;
            document.getElementById('detailModal').style.display = 'block';
        }
        
        function closeModal() {
            document.getElementById('detailModal').style.display = 'none';
        }
        
        // Show test case details
        async function showTestDetails(testName) {
            let content = '<div class="log-section">';
            content += '<h3 class="log-section-title">Test Information</h3>';
            content += '<p><strong>Test Path:</strong> ' + testName + '</p>';
            content += '</div>';
            
            // Show results for each commit
            content += '<div class="log-section">';
            content += '<h3 class="log-section-title">Execution Results</h3>';
            content += '<div class="file-list">';
            
            commits.forEach(commit => {
                const result = processedData[testName][commit];
                if (result) {
                    const commitShort = commit.substring(0, 7);
                    const status = result.status.toUpperCase();
                    const statusColor = result.status === 'pass' ? '#10b981' : 
                                       result.status === 'fail' ? '#ef4444' : '#f59e0b';
                    
                    content += '<div style="margin-bottom: 1rem; padding: 1rem; background: #f9fafb; border-radius: 0.5rem;">';
                    content += '<div style="display: flex; justify-content: space-between; align-items: center;">';
                    content += '<strong>Commit ' + commitShort + ':</strong>';
                    content += '<span style="color: ' + statusColor + '; font-weight: bold;">' + status + '</span>';
                    content += '</div>';
                    
                    if (result.flaky) {
                        content += '<p style="color: #0c4a6e; margin-top: 0.5rem;">Flaky test - passed after ' + 
                                  result.attempts + ' attempts</p>';
                    }
                    
                    if (result.message) {
                        content += '<p style="color: #6b7280; margin-top: 0.5rem;">' + result.message + '</p>';
                    }
                    
                    content += '<button class="btn" style="margin-top: 0.5rem;" onclick="showExecutionLog(\'' + 
                              testName.replace(/'/g, "\\'") + '\', \'' + commit + '\')">View Execution Log</button>';
                    content += '</div>';
                }
            });
            
            content += '</div>';
            content += '</div>';
            
            // Try to load test script and related files
            content += '<div class="log-section">';
            content += '<h3 class="log-section-title">Test Files</h3>';
            content += '<div id="testFilesSection">Loading...</div>';
            content += '</div>';
            
            showModal('Test Details: ' + testName, content);
            
            // Load test files asynchronously
            loadTestFiles(testName);
        }        
        // Show execution log for a specific test and commit
        async function showExecutionLog(testName, commit) {
            const result = processedData[testName][commit];
            if (!result) {
                showModal('Error', '<p>No log available for this test execution.</p>');
                return;
            }
            
            const commitShort = commit.substring(0, 7);
            let content = '<div class="log-section">';
            content += '<h3 class="log-section-title">Execution Details</h3>';
            content += '<p><strong>Test:</strong> ' + testName + '</p>';
            content += '<p><strong>Commit:</strong> ' + commitShort + ' (' + commit + ')</p>';
            content += '<p><strong>Status:</strong> ' + result.status.toUpperCase() + '</p>';
            
            if (result.flaky) {
                content += '<p><strong>Attempts:</strong> ' + result.attempts + ' (Flaky test)</p>';
            }
            
            content += '</div>';
            
            content += '<div class="log-section">';
            content += '<h3 class="log-section-title">Execution Log</h3>';
            content += '<div id="logContent" class="log-viewer">Loading log...</div>';
            content += '</div>';
            
            showModal('Execution Log: ' + testName + ' @ ' + commitShort, content);
            
            // Load log content
            loadExecutionLog(testName, commit);
        }
        
        // Load execution log via API
        async function loadExecutionLog(testName, commit) {
            try {
                const safeTestName = testName.replace(/[^a-zA-Z0-9_.-]/g, '_');
                const commitShort = commit.substring(0, 7);
                
                // Try different log file patterns
                const patterns = [
                    'docker_' + commitShort + '_' + safeTestName.split('/').pop().replace('.sh', '') + '.log',
                    'direct_' + commitShort + '_' + safeTestName.split('/').pop().replace('.sh', '') + '.log',
                    'test_' + commitShort + '_' + safeTestName.split('/').pop().replace('.sh', '') + '.log'
                ];
                
                let logFound = false;
                for (const pattern of patterns) {
                    const response = await fetch('/api/log/' + requestId + '/tests/' + pattern);
                    if (response.ok) {
                        const logContent = await response.text();
                        document.getElementById('logContent').textContent = logContent;
                        logFound = true;
                        break;
                    }
                }
                
                if (!logFound) {
                    // Try to list available logs
                    const listResponse = await fetch('/api/logs/' + requestId + '/tests');
                    if (listResponse.ok) {
                        const files = await listResponse.json();
                        const relevantFiles = files.filter(f => 
                            f.includes(commitShort) && f.includes(safeTestName.split('/').pop().replace('.sh', ''))
                        );
                        
                        if (relevantFiles.length > 0) {
                            // Show list of available logs
                            let content = '<p>Available log files:</p><div class="file-list">';
                            relevantFiles.forEach(file => {
                                content += '<a href="#" class="file-link" onclick="loadSpecificLog(\'' + 
                                          file + '\'); return false;">' + file + '</a>';
                            });
                            content += '</div>';
                            document.getElementById('logContent').innerHTML = content;
                        } else {
                            document.getElementById('logContent').textContent = 'No log file found for this execution.';
                        }
                    } else {
                        document.getElementById('logContent').textContent = 'Failed to load log file.';
                    }
                }
            } catch (error) {
                document.getElementById('logContent').textContent = 'Error loading log: ' + error.message;
            }
        }        
        // Load specific log file
        async function loadSpecificLog(fileName) {
            try {
                const response = await fetch('/api/log/' + requestId + '/tests/' + fileName);
                if (response.ok) {
                    const logContent = await response.text();
                    document.getElementById('logContent').textContent = logContent;
                } else {
                    document.getElementById('logContent').textContent = 'Failed to load log file: ' + fileName;
                }
            } catch (error) {
                document.getElementById('logContent').textContent = 'Error loading log: ' + error.message;
            }
        }
        
        // View build logs
        async function viewBuildLogs() {
            let content = '<div class="log-section">';
            content += '<h3 class="log-section-title">Build Logs</h3>';
            content += '<div id="buildLogsSection">Loading...</div>';
            content += '</div>';
            
            showModal('Build Logs', content);
            
            // Load build logs
            try {
                const response = await fetch('/api/logs/' + requestId + '/builds');
                if (response.ok) {
                    const files = await response.json();
                    let logsContent = '<div class="file-list">';
                    
                    if (files.length === 0) {
                        logsContent = '<p>No build logs available.</p>';
                    } else {
                        files.forEach(file => {
                            const commitShort = file.replace('build_', '').replace('.log', '');
                            logsContent += '<div style="margin-bottom: 1rem;">';
                            logsContent += '<button class="btn" onclick="loadBuildLog(\'' + file + '\')">';
                            logsContent += 'Build Log - Commit ' + commitShort + '</button>';
                            logsContent += '</div>';
                        });
                    }
                    
                    logsContent += '</div>';
                    document.getElementById('buildLogsSection').innerHTML = logsContent;
                } else {
                    document.getElementById('buildLogsSection').innerHTML = '<p>Failed to load build logs.</p>';
                }
            } catch (error) {
                document.getElementById('buildLogsSection').innerHTML = '<p>Error: ' + error.message + '</p>';
            }
        }
        
        // Load specific build log
        async function loadBuildLog(fileName) {
            try {
                const response = await fetch('/api/log/' + requestId + '/builds/' + fileName);
                if (response.ok) {
                    const logContent = await response.text();
                    const content = '<div class="log-section">' +
                                   '<h3 class="log-section-title">' + fileName + '</h3>' +
                                   '<div class="log-viewer">' + escapeHtml(logContent) + '</div>' +
                                   '</div>';
                    showModal('Build Log: ' + fileName, content);
                } else {
                    alert('Failed to load build log: ' + fileName);
                }
            } catch (error) {
                alert('Error loading build log: ' + error.message);
            }
        }
        
        // Load test files (stub for now)
        async function loadTestFiles(testName) {
            const section = document.getElementById('testFilesSection');
            section.innerHTML = '<p>Test script: ' + testName + '</p>' +
                               '<p style="color: #6b7280;">Additional test files would be listed here if available.</p>';
        }
        
        // Utility function to escape HTML
        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }
        
        // Export functions
        function exportJSON() {
            const dataStr = JSON.stringify(rawData, null, 2);
            const dataBlob = new Blob([dataStr], {type: 'application/json'});
            const url = URL.createObjectURL(dataBlob);
            const link = document.createElement('a');
            link.href = url;
            link.download = requestId + '_results.json';
            link.click();
        }
        
        function exportCSV() {
            let csv = 'Test,Commit,Status,Flaky,Attempts\\n';
            Object.keys(processedData).forEach(test => {
                commits.forEach(commit => {
                    const result = processedData[test][commit];
                    if (result) {
                        csv += test + ',' + commit + ',' + result.status + ',' + 
                               (result.flaky || false) + ',' + (result.attempts || 1) + '\\n';
                    }
                });
            });
            
            const dataBlob = new Blob([csv], {type: 'text/csv'});
            const url = URL.createObjectURL(dataBlob);
            const link = document.createElement('a');
            link.href = url;
            link.download = requestId + '_results.csv';
            link.click();
        }
        
        // Close modal when clicking outside
        window.onclick = function(event) {
            const modal = document.getElementById('detailModal');
            if (event.target === modal) {
                closeModal();
            }
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
    
    // CORS headers for API endpoints
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
    
    if (req.method === 'OPTIONS') {
        res.writeHead(200);
        res.end();
        return;
    }
    
    // API endpoint to get log file
    if (pathname.startsWith('/api/log/')) {
        const pathParts = pathname.split('/').filter(p => p);
        if (pathParts.length >= 5) {
            const requestId = pathParts[2];
            const logType = pathParts[3]; // 'builds' or 'tests'
            const fileName = pathParts.slice(4).join('/');
            
            const result = await readLogFile(requestId, logType, fileName);
            if (result.success) {
                res.writeHead(200, { 'Content-Type': 'text/plain; charset=UTF-8' });
                res.end(result.content);
            } else {
                res.writeHead(404, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'Log file not found' }));
            }
        } else {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Invalid log path' }));
        }
        return;
    }
    
    // API endpoint to list log files
    if (pathname.startsWith('/api/logs/')) {
        const pathParts = pathname.split('/').filter(p => p);
        if (pathParts.length >= 3) {
            const requestId = pathParts[2];
            const logType = pathParts[3]; // 'builds' or 'tests'
            
            const result = await listLogFiles(requestId, logType);
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify(result.files));
        } else {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Invalid path' }));
        }
        return;
    }
    
    if (pathname === '/callback' && req.method === 'POST') {
        // Handle callback POST request
        let body = '';
        
        req.on('data', chunk => {
            body += chunk.toString();
        });
        
        req.on('end', async () => {
            try {
                const data = JSON.parse(body);
                
                // Generate request ID if not provided
                const requestId = data.taskId || 'req_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
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
                
                console.log('Saved report: ' + requestId);
                
                // Return HTML report as response
                res.writeHead(200, { 'Content-Type': 'text/html; charset=UTF-8' });
                res.end(reportHtml);
                
            } catch (err) {
                console.error('Error handling callback: ' + err.message);
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
                listHtml += '.timestamp { opacity: 0.8; font-size: 0.9rem; }';
                listHtml += '</style></head><body>';
                listHtml += '<div class="container">';
                listHtml += '<h1>Enhanced Test Report Viewer</h1>';
                listHtml += '<div class="report-list">';
                
                if (reports.length === 0) {
                    listHtml += '<p>No reports available</p>';
                } else {
                    reports.forEach(r => {
                        listHtml += '<div class="report-item">';
                        listHtml += '<a href="/report?id=' + r.id + '">' + r.id + '</a>';
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
        res.end(JSON.stringify({ status: 'healthy', service: 'enhanced-report-server' }));
        
    } else {
        res.writeHead(404, { 'Content-Type': 'text/html' });
        res.end('<h1>Not Found</h1><p>Available endpoints: /callback (POST), /report (GET), /health (GET), /api/log/*, /api/logs/*</p>');
    }
}

// Create and start server
const server = http.createServer(handleRequest);

async function startServer() {
    await ensureDirectories();
    
    server.listen(PORT, () => {
        console.log('');
        console.log('╔════════════════════════════════════════════════╗');
        console.log('║  CUBRID Enhanced Test Report Server Started   ║');
        console.log('╠════════════════════════════════════════════════╣');
        console.log('║  Port:     ' + PORT.toString().padEnd(36) + '║');
        console.log('║  Callback: http://localhost:' + PORT + '/callback      ║');
        console.log('║  Reports:  http://localhost:' + PORT + '/report        ║');
        console.log('║  API Logs: http://localhost:' + PORT + '/api/log/*     ║');
        console.log('║  Health:   http://localhost:' + PORT + '/health        ║');
        console.log('╚════════════════════════════════════════════════╝');
        console.log('');
        console.log('Enhanced features:');
        console.log('  • View build logs for each commit');
        console.log('  • Click test names to see test details');
        console.log('  • Click PASS/FAIL cells to view execution logs');
        console.log('  • Support for flaky test logs with multiple attempts');
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