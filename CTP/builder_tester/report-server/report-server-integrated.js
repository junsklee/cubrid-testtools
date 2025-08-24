/**
 * CUBRID Builder-Tester Report Server - Working Version
 * Provides a web dashboard for the builder-tester system
 */

const http = require('http');
const https = require('https');
const fs = require('fs').promises;
const path = require('path');
const url = require('url');
const os = require('os');

const PORT = process.argv[2] || 8091;
const BUILDER_HOST = process.env.BUILDER_HOST || 'localhost';
const BUILDER_PORT = process.env.BUILDER_PORT || 8089;

const LOG_BASE_DIR = path.join(
    process.env.HOME || process.env.USERPROFILE,
    'cubrid-testtools', 'CTP', 'builder_tester', 'log'
);

// Modern UI template path
const TOBE_HTML_PATH = path.join(__dirname, 'report-server-tobe.html');

// Get the local IP address
function getLocalIpAddress() {
    const interfaces = os.networkInterfaces();
    for (const name of Object.keys(interfaces)) {
        for (const iface of interfaces[name]) {
            // Skip internal (loopback) and non-IPv4 addresses
            if (!iface.internal && iface.family === 'IPv4') {
                return iface.address;
            }
        }
    }
    return '127.0.0.1'; // Fallback to localhost if no external IP found
}

const LOCAL_IP = getLocalIpAddress();

// Load the modern dashboard UI and inject an override script tag
async function getDashboardHtml() {
    try {
        let html = await fs.readFile(TOBE_HTML_PATH, 'utf-8');
        const callbackDefault = `http://localhost:${PORT}/callback`;
        // Replace default callback if present in template
        html = html.replace('value="http://localhost:8089/callback"', `value="${callbackDefault}"`);
        const injection = `\n<script src="/ui/overrides.js"></script>\n<script src="/ui/reports.js"></script>\n`;
        if (html.includes('</body>')) {
            return html.replace('</body>', injection + '</body>');
        }
        return html + injection;
    } catch (err) {
        console.error('Failed to load modern UI template from', TOBE_HTML_PATH, '\n', (err && (err.stack || err.message)));
        return DASHBOARD_HTML;
    }
}

// Ensure log directories exist
async function ensureDirectories() {
    try {
        await fs.mkdir(path.join(LOG_BASE_DIR, 'results'), { recursive: true });
    } catch (err) {
        console.error('Failed to create directories:', err.message);
    }
    try {
        await fs.mkdir(path.join(LOG_BASE_DIR, 'requests'), { recursive: true });
    } catch (err) {
        console.error('Failed to create requests directory:', err.message);
    }
}

// Main dashboard HTML
const DASHBOARD_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CUBRID Builder-Tester Dashboard</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            padding: 20px;
        }
        .container {
            max-width: 1200px;
            margin: 0 auto;
        }
        h1 {
            color: white;
            text-align: center;
            margin-bottom: 30px;
            font-size: 2.5rem;
            text-shadow: 2px 2px 4px rgba(0,0,0,0.2);
        }
        .card {
            background: white;
            border-radius: 12px;
            padding: 30px;
            margin-bottom: 20px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.1);
        }
        h2 {
            color: #333;
            margin-bottom: 20px;
            padding-bottom: 10px;
            border-bottom: 2px solid #f0f0f0;
        }
        .form-group {
            margin-bottom: 20px;
        }
        label {
            display: block;
            margin-bottom: 8px;
            font-weight: 600;
            color: #555;
        }
        input, textarea, select {
            width: 100%;
            padding: 12px;
            border: 2px solid #e0e0e0;
            border-radius: 8px;
            font-size: 14px;
            transition: border-color 0.3s;
        }
        input:focus, textarea:focus, select:focus {
            outline: none;
            border-color: #667eea;
        }
        textarea {
            resize: vertical;
            font-family: 'Courier New', monospace;
        }
        .button {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 12px 30px;
            border: none;
            border-radius: 8px;
            font-size: 16px;
            font-weight: 600;
            cursor: pointer;
            transition: transform 0.2s, box-shadow 0.2s;
        }
        .button:hover {
            transform: translateY(-2px);
            box-shadow: 0 5px 15px rgba(102, 126, 234, 0.4);
        }
        .status {
            padding: 8px 16px;
            border-radius: 20px;
            display: inline-block;
            font-weight: 600;
        }
        .status.online {
            background: #d4edda;
            color: #155724;
        }
        .status.offline {
            background: #f8d7da;
            color: #721c24;
        }
        #result {
            margin-top: 20px;
            padding: 15px;
            border-radius: 8px;
            display: none;
        }
        #result.success {
            background: #d4edda;
            color: #155724;
            border: 1px solid #c3e6cb;
        }
        #result.error {
            background: #f8d7da;
            color: #721c24;
            border: 1px solid #f5c6cb;
        }
        .grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
        }
        @media (max-width: 768px) {
            .grid {
                grid-template-columns: 1fr;
            }
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>🚀 CUBRID Builder-Tester Dashboard</h1>
        
        <div class="card">
            <h2>📤 Submit Build Request</h2>
            <form id="buildForm">
                <div class="form-group">
                    <label>Commits (comma-separated SHA):</label>
                    <textarea id="commits" rows="2" placeholder="Enter commit SHAs, e.g., 6ea587e, abc123">6ea587e</textarea>
                </div>
                
                <div class="form-group">
                    <label>Test Cases (one per line):</label>
                    <textarea id="tests" rows="4" placeholder="Enter test paths...">shell/_01_utility/_38_csql/csql_hist/cases/csql_hist.sh</textarea>
                </div>
                
                <div class="grid">
                    <div class="form-group">
                        <label>Worker IPs:</label>
                        <input type="text" id="workerIps" value="localhost" placeholder="e.g., localhost, 192.168.1.10:8090">
                    </div>
                    
                    <div class="form-group">
                        <label>Build Type:</label>
                        <select id="buildType">
                            <option value="debug">Debug</option>
                            <option value="release">Release</option>
                            <option value="profile">Profile</option>
                        </select>
                    </div>
                </div>
                
                <div class="form-group">
                    <label>Callback URL:</label>
                    <input type="text" id="callbackUrl" value="http://localhost:${PORT}/callback">
                </div>
                
                <button type="submit" class="button">Send Build Request</button>
            </form>
            
            <div id="result"></div>
        </div>
        
        <div class="card">
            <h2>📊 System Status</h2>
            <p><strong>Report Server:</strong> <span class="status online">Running on port ${PORT}</span></p>
            <p><strong>Builder Service:</strong> <span id="builderStatus" class="status offline">Checking...</span></p>
            <p><strong>Configuration:</strong> Builder at ${BUILDER_HOST}:${BUILDER_PORT}</p>
        </div>
    </div>
    
    <script>
        // Check builder status on load
        async function checkBuilderStatus() {
            try {
                const response = await fetch('/api/builder/health');
                const data = await response.json();
                const statusEl = document.getElementById('builderStatus');
                statusEl.className = 'status online';
                statusEl.textContent = 'Connected - ' + (data.status || 'healthy');
            } catch (err) {
                const statusEl = document.getElementById('builderStatus');
                statusEl.className = 'status offline';
                statusEl.textContent = 'Not connected';
            }
        }
        
        checkBuilderStatus();
        setInterval(checkBuilderStatus, 10000); // Check every 10 seconds
        
        // Handle form submission
        document.getElementById('buildForm').addEventListener('submit', async (e) => {
            e.preventDefault();
            
            const resultDiv = document.getElementById('result');
            resultDiv.style.display = 'block';
            resultDiv.className = '';
            resultDiv.innerHTML = 'Sending request...';
            
            const commits = document.getElementById('commits').value
                .split(',')
                .map(s => s.trim())
                .filter(s => s);
            
            const tests = document.getElementById('tests').value
                .split('\\n')
                .map(s => s.trim())
                .filter(s => s);
            
            const workerIps = document.getElementById('workerIps').value
                .split(',')
                .map(s => s.trim())
                .filter(s => s);
            
            const payload = {
                commits: commits,
                tests: tests,
                callbackUrl: document.getElementById('callbackUrl').value,
                workerIps: workerIps,
                buildType: document.getElementById('buildType').value,
                timeout: parseInt((document.getElementById('timeout') || {}).value || '7200'),
                runMode: (document.getElementById('runMode') || {}).value || 'until-pass',
                minRuns: parseInt((document.getElementById('minRuns') || {}).value || '1'),
                maxRuns: parseInt((document.getElementById('maxRuns') || {}).value || '3')
            };
            
            try {
                const response = await fetch('/api/builder/build', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                
                const result = await response.json();
                
                if (response.ok) {
                    resultDiv.className = 'success';
                    resultDiv.innerHTML = '<strong>✓ Success!</strong> ' + 
                        (result.taskId ? 'Task ID: ' + result.taskId : JSON.stringify(result));
                } else {
                    resultDiv.className = 'error';
                    resultDiv.innerHTML = '<strong>✗ Error:</strong> ' + 
                        (result.error || JSON.stringify(result));
                }
            } catch (err) {
                resultDiv.className = 'error';
                resultDiv.innerHTML = '<strong>✗ Error:</strong> ' + err.message;
            }
        });
    </script>
</body>
</html>`;

// Handle HTTP requests
async function handleRequest(req, res) {
    const parsedUrl = url.parse(req.url, true);
    const pathname = parsedUrl.pathname;
    
    // Enable CORS for API endpoints
    if (pathname.startsWith('/api/')) {
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
        
        if (req.method === 'OPTIONS') {
            res.writeHead(200);
            res.end();
            return;
        }
    }
    
    try {
        // UI overrides script
        if (pathname === '/ui/overrides.js') {
            const script = `\n(function(){\n\nconst BUILDER_HOST = ${JSON.stringify(BUILDER_HOST)};\nconst BUILDER_PORT = ${JSON.stringify(BUILDER_PORT)};\nconst REPORT_PORT = ${JSON.stringify(PORT)};\n\nfunction safeToast(msg, type){ try { if (typeof showToast === 'function') showToast(msg, type || 'info'); } catch(e){} }\n\nasync function builderHealth(){\n  try { const res = await fetch('/api/builder/health'); return await res.json(); } catch(e){ return { error: e.message }; }\n}\n\n// Override builder status check to use server proxy\nwindow.checkBuilderStatus = async function(){\n  try {\n    const data = await builderHealth();\n    const statusEl = document.getElementById('builderStatus');\n    if (statusEl) { statusEl.className = 'status online'; statusEl.textContent = 'Connected - ' + (data.status || 'healthy'); }\n  } catch (err) {\n    const statusEl = document.getElementById('builderStatus');\n    if (statusEl) { statusEl.className = 'status offline'; statusEl.textContent = 'Not connected'; }\n  }\n};\n\n// Load commits via server API and reuse page/state from template\nwindow.loadCommits = async function(){\n  if (typeof isLoadingCommits !== 'undefined' && isLoadingCommits) return;\n  if (typeof isLoadingCommits !== 'undefined') isLoadingCommits = true;\n  const commitList = document.getElementById('commitList');\n  try {\n    const page = (typeof currentPage !== 'undefined' ? currentPage : 1);\n    if (page === 1 && commitList) commitList.innerHTML = '<div class=\\"spinner\\"></div>';\n    const res = await fetch('/api/github/commits?page=' + page);\n    if (!res.ok) throw new Error('Failed to fetch commits');\n    const data = await res.json();\n    if (typeof commits === 'undefined') window.commits = [];\n    if (page === 1) { commits = data; if (commitList) commitList.innerHTML = ''; } else { commits = commits.concat(data); }\n    if (typeof renderCommits === 'function') renderCommits();\n    if (typeof currentPage !== 'undefined') currentPage = page + 1;\n    safeToast('Loaded commits successfully', 'success');\n  } catch (e) {\n    if (commitList) commitList.innerHTML = '<p style=\\"color: var(--error);\\">Failed to load commits. Please try again.</p>';\n    safeToast('Failed to load commits', 'error');\n  } finally { if (typeof isLoadingCommits !== 'undefined') isLoadingCommits = false; }\n};\n\n// Validate commits via server API\nwindow.validateCommits = async function(commitShas){\n  const checks = (commitShas || []).map(function(sha){\n    return fetch('/api/github/validate/' + encodeURIComponent(sha))\n      .then(function(r){ return r.json(); })\n      .catch(function(){ return { sha: sha, valid: false }; });\n  });\n  const results = await Promise.all(checks);\n  const invalid = results.filter(function(r){ return !r.valid; }).map(function(r){ return r.sha; });\n  if (invalid.length > 0) { safeToast('Invalid commits: ' + invalid.join(', '), 'error'); return false; }\n  return true;\n};\n\n// Load builder info into modal using server proxy\nwindow.loadBuilderInfo = async function(){\n  const builderInfo = document.getElementById('builderInfo');\n  if (builderInfo) builderInfo.innerHTML = '<div class=\\"spinner\\"></div>';\n  try {\n    const data = await builderHealth();\n    let html = '';\n    Object.keys(data || {}).forEach(function(key){\n      let value = data[key]; if (typeof value === 'object') { try { value = JSON.stringify(value); } catch(e){} }\n      html += '<div class=\\"info-item\\">' +\n              '<div class=\\"info-label\\">' + key + '</div>' +\n              '<div class=\\"info-value\\">' + (value === undefined ? '' : String(value)) + '</div>' +\n              '</div>';\n    });\n    if (builderInfo) builderInfo.innerHTML = html || '<p style=\\"color: var(--text-secondary);\\">No data</p>';\n  } catch (e) { if (builderInfo) builderInfo.innerHTML = '<p style=\\"color: var(--error);\\">Failed to load builder info</p>'; }\n};\n\n// Query tester health via server and render\nwindow.checkTesterStatus = async function(){\n  const testerIpEl = document.getElementById('testerIp');\n  if (!testerIpEl || !testerIpEl.value.trim()) { safeToast('Please enter tester IP address', 'warning'); return; }\n  const testerIp = testerIpEl.value.trim();\n  const testerInfo = document.getElementById('testerInfo');\n  if (testerInfo) testerInfo.innerHTML = '<div class=\\"spinner\\"></div>';\n  try {\n    const res = await fetch('/api/tester/health?ip=' + encodeURIComponent(testerIp));\n    const data = await res.json();\n    let html = '';\n    Object.keys(data || {}).forEach(function(key){\n      let value = data[key]; if (typeof value === 'object') { try { value = JSON.stringify(value); } catch(e){} }\n      html += '<div class=\\"info-item\\">' +\n              '<div class=\\"info-label\\">' + key + '</div>' +\n              '<div class=\\"info-value\\">' + (value === undefined ? '' : String(value)) + '</div>' +\n              '</div>';\n    });\n    if (testerInfo) testerInfo.innerHTML = html || '<p style=\\"color: var(--text-secondary);\\">No data</p>';\n    safeToast('Tester status retrieved successfully', 'success');\n  } catch (e) { if (testerInfo) testerInfo.innerHTML = '<p style=\\"color: var(--error);\\">Failed to load tester info</p>'; safeToast('Failed to load tester info', 'error'); }\n};\n\n// Enhanced submit to call builder proxy and validate\nwindow.submitBuildRequest = async function(){\n  const button = document.getElementById('submitButton');\n  if (button) { button.disabled = true; button.textContent = 'Processing...'; }\n  try {\n    let commitShas = [];\n    if (typeof commitMode !== 'undefined' && commitMode === 'select') { commitShas = Array.from(selectedCommits || []); } else {\n      const manualInputEl = document.getElementById('manualCommitInput');\n      const manualInput = manualInputEl ? manualInputEl.value : '';\n      commitShas = manualInput.split(/[\\n,]/).map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });\n      safeToast('Validating commits...', 'info');\n      const valid = await window.validateCommits(commitShas);\n      if (!valid) throw new Error('Invalid commits detected');\n    }\n    if (!commitShas || commitShas.length === 0) throw new Error('Please select or enter at least one commit');\n\n    const testsInput = (document.getElementById('testsInput') || {}).value || '';\n    const tests = testsInput.split('\\n').map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });\n    if (tests.length === 0) throw new Error('Please enter at least one test case');\n\n    const cbUrlEl = document.getElementById('callbackUrl');\n    const cbUrl = cbUrlEl ? cbUrlEl.value : ('http://localhost:' + REPORT_PORT + '/callback');\n    try { new URL(cbUrl); } catch (e) { throw new Error('Invalid callback URL'); }\n\n    const payload = {\n      commits: commitShas,\n      tests: tests,\n      callbackUrl: cbUrl,\n      workerIps: (typeof workers !== 'undefined' ? workers : ['localhost']),\n      buildType: (document.getElementById('buildType') || {}).value || 'debug',\n      timeout: parseInt((document.getElementById('timeout') || {}).value || '7200'),\n      runMode: (document.getElementById('runMode') || {}).value || 'until-pass',\n      minRuns: parseInt((document.getElementById('minRuns') || {}).value || '1'),\n      maxRuns: parseInt((document.getElementById('maxRuns') || {}).value || '3')\n    };\n\n    const envVarsVal = (document.getElementById('envVars') || {}).value || '';\n    if (envVarsVal.trim().length > 0) { try { payload.envVars = JSON.parse(envVarsVal); } catch (e) { throw new Error('Invalid JSON in environment variables'); } }\n\n    if (typeof displayResponse === 'function') displayResponse('Request sent:\\n' + JSON.stringify(payload, null, 2));\n\n    const resp = await fetch('/api/builder/build', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });\n    let resJson = {}; try { resJson = await resp.json(); } catch(e){}\n    if (!resp.ok) throw new Error(resJson && (resJson.error || JSON.stringify(resJson)) || 'Request failed');\n    safeToast('Build request sent successfully!', 'success');\n    if (typeof startStatusMonitoring === 'function') startStatusMonitoring(payload);\n  } catch (err) { safeToast((err && err.message) ? err.message : String(err), 'error'); }\n  finally { if (button) { button.disabled = false; button.textContent = 'Send Build Request'; } }\n};\n\n// Ensure default callback value on load if field exists and empty\ndocument.addEventListener('DOMContentLoaded', function(){\n  const cb = document.getElementById('callbackUrl');\n  if (cb && (!cb.value || cb.value.trim().length === 0)) { cb.value = 'http://localhost:' + REPORT_PORT + '/callback'; }\n});\n\n})();\n`;
            res.writeHead(200, { 'Content-Type': 'application/javascript' });
            res.end(script);
            return;
        }

        // UI reports script
        if (pathname === '/ui/reports.js') {
            const script = `\n(function(){\n  function addReportsSection(){\n    if (document.getElementById('reportsCard')) return;\n    var container = document.querySelector('.container');\n    if (!container) return;\n    var card = document.createElement('div');\n    card.className = 'card';\n    card.id = 'reportsCard';\n    card.innerHTML = '<h2 class="card-title"><div class="card-title-icon">📑</div> Test Reports</h2>' +
      '<div id="reportsControls" style="margin-bottom:1rem; display:flex; gap:.5rem; align-items:center;">' +
      '<button class="control-btn" id="refreshReportsBtn">Refresh</button>' +
      '<a href="/reports" target="_blank" class="control-btn">Open Reports Page</a>' +
      '</div>' +
      '<div id="reportsList" class="info-grid"><div class="spinner"></div></div>' +
      '<div id="reportEmbed" style="margin-top:1rem; display:none;">' +
      '<iframe id="reportFrame" style="width:100%; height:70vh; border:1px solid #475569; border-radius:8px; background:white;"></iframe>' +
      '</div>';\n    container.appendChild(card);\n    document.getElementById('refreshReportsBtn').onclick = loadReports;\n  }\n\n  async function loadReports(){\n    var list = document.getElementById('reportsList');\n    if (!list) return;\n    list.innerHTML = '<div class="spinner"></div>';\n    try {\n      var res = await fetch('/reports');\n      var html = await res.text();\n      var matches = html.match(/href=\"\\/report\\?id=([^\"]+)\"/g) || [];\n      var ids = matches.map(function(m){ var s = m.match(/id=([^\"]+)/); return s ? s[1] : ''; }).filter(Boolean);\n      if (ids.length === 0) { list.innerHTML = '<p style="color: var(--text-secondary);">No reports available</p>'; return; }\n      list.innerHTML = '';\n      ids.forEach(function(id){\n        var item = document.createElement('div');\n        item.className = 'info-item';\n        var label = document.createElement('div'); label.className = 'info-label'; label.textContent = 'Report ID';\n        var value = document.createElement('div'); value.className = 'info-value'; value.textContent = id;\n        var actions = document.createElement('div'); actions.style.marginTop = '.5rem';\n        var btn = document.createElement('button'); btn.className = 'control-btn'; btn.textContent = 'Open';\n        btn.onclick = function(){\n          var f = document.getElementById('reportFrame');\n          var c = document.getElementById('reportEmbed');\n          f.src = '/dashboard/report?id=' + encodeURIComponent(id);\n          c.style.display = 'block';\n        };\n        actions.appendChild(btn);\n        item.appendChild(label);\n        item.appendChild(value);\n        item.appendChild(actions);\n        list.appendChild(item);\n      });\n    } catch (e) {\n      list.innerHTML = '<p style="color: var(--error);">Failed to load reports</p>';\n    }\n  }\n\n  document.addEventListener('DOMContentLoaded', function(){ addReportsSection(); loadReports(); });\n})();\n`;
            res.writeHead(200, { 'Content-Type': 'application/javascript' });
            res.end(script);
            return;
        }

        // Local IP endpoint
        if (pathname === '/api/local-ip' && req.method === 'GET') {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ip: LOCAL_IP, hostname: os.hostname() }));
            return;
        }

        // GitHub commits proxy
        if (pathname === '/api/github/commits' && req.method === 'GET') {
            const page = parseInt(parsedUrl.query.page || '1', 10) || 1;
            const perPage = parseInt(parsedUrl.query.per_page || '30', 10) || 30;
            const sha = (parsedUrl.query.sha || 'develop');
            const ghPath = `/repos/CUBRID/cubrid/commits?sha=${encodeURIComponent(sha)}&per_page=${perPage}&page=${page}`;
            try {
                const data = await githubRequestJson(ghPath);
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(data));
            } catch (e) {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: e.message }));
            }
            return;
        }

        // GitHub validate commit
        if (pathname.startsWith('/api/github/validate/') && req.method === 'GET') {
            const sha = pathname.split('/').pop();
            const ghPath = `/repos/CUBRID/cubrid/commits/${encodeURIComponent(sha)}`;
            try {
                const { statusCode, json } = await githubRequestJsonWithStatus(ghPath);
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ sha, valid: statusCode === 200, commit: statusCode === 200 ? json : undefined }));
            } catch (e) {
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ sha, valid: false }));
            }
            return;
        }

        // Tester health
        if (pathname === '/api/tester/health' && req.method === 'GET') {
            const target = (parsedUrl.query.ip || '').toString();
            if (!target) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'Missing ip query parameter' }));
                return;
            }
            try {
                const testerUrl = target.startsWith('http://') || target.startsWith('https://') ? target : `http://${target}`;
                const u = new URL(testerUrl);
                u.pathname = '/health';
                const client = u.protocol === 'https:' ? https : http;
                const data = await httpGetJson(client, u);
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(data));
            } catch (e) {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: e.message }));
            }
            return;
        }

        // Main dashboard - serve modern UI
        if (pathname === '/' || pathname === '/index.html') {
            const html = await getDashboardHtml();
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end(html);
            return;
        }

        // Embed report page into a minimal shell for iframe/section usage
        if (pathname === '/dashboard/report' && req.method === 'GET') {
            const requestId = parsedUrl.query.id;
            if (!requestId) {
                res.writeHead(400, { 'Content-Type': 'text/html; charset=UTF-8' });
                res.end('<h1>Missing id</h1>');
                return;
            }
            try {
                const reportHtml = await fs.readFile(path.join(LOG_BASE_DIR, 'requests', requestId, 'report.html'), 'utf8');
                const shell = '<!DOCTYPE html><html><head><meta charset="UTF-8"><title>Report ' + requestId + '</title>'+
                              '<style>body{margin:0;padding:0} .container{padding:0}</style></head><body>' + reportHtml + '</body></html>';
                res.writeHead(200, { 'Content-Type': 'text/html; charset=UTF-8' });
                res.end(shell);
            } catch (e) {
                res.writeHead(404, { 'Content-Type': 'text/html' });
                res.end('<h1>Report not found</h1>');
            }
            return;
        }
        
        // Proxy requests to Builder
        if (pathname.startsWith('/api/builder/')) {
            const builderPath = pathname.replace('/api/builder', '');
            
            const options = {
                hostname: BUILDER_HOST,
                port: BUILDER_PORT,
                path: builderPath,
                method: req.method,
                headers: req.headers
            };
            
            const proxyReq = http.request(options, (proxyRes) => {
                res.writeHead(proxyRes.statusCode, proxyRes.headers);
                proxyRes.pipe(res);
            });
            
            proxyReq.on('error', (err) => {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: err.message }));
            });
            
            req.pipe(proxyReq);
            return;
        }
        
        // Callback endpoint
        if (pathname === '/callback' && req.method === 'POST') {
            let body = '';
            req.on('data', chunk => body += chunk);
            req.on('end', async () => {
                try {
                    const data = JSON.parse(body);
                    const requestId = data.taskId || 'req_' + new Date().toISOString().replace(/[:.]/g, '-') + '_' + Math.random().toString(36).slice(2,8);
                    const requestDir = path.join(LOG_BASE_DIR, 'requests', requestId);
                    await fs.mkdir(requestDir, { recursive: true });
                    // Save JSON
                    await fs.writeFile(path.join(requestDir, 'results.json'), JSON.stringify(data, null, 2));
                    // Generate HTML report
                    const reportHtml = generateReportHTML(data, requestId);
                    await fs.writeFile(path.join(requestDir, 'report.html'), reportHtml);
                    console.log('Saved report:', requestId);
                    res.writeHead(200, { 'Content-Type': 'text/html; charset=UTF-8' });
                    res.end(reportHtml);
                } catch (e) {
                    console.error('Failed to handle callback:', e.message);
                    // Fallback: save raw payload for debugging
                    try {
                        const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
                        const resultFile = path.join(LOG_BASE_DIR, 'results', `result_${timestamp}.json`);
                        await fs.writeFile(resultFile, body);
                    } catch {}
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ success: true, message: 'Callback received' }));
                }
            });
            return;
        }

        // Report pages
        if (pathname === '/reports' && req.method === 'GET') {
            try {
                const requestsDir = path.join(LOG_BASE_DIR, 'requests');
                const entries = await fs.readdir(requestsDir);
                const reports = [];
                for (const dir of entries) {
                    const reportPath = path.join(requestsDir, dir, 'report.html');
                    try {
                        const stat = await fs.stat(reportPath);
                        reports.push({ id: dir, modified: stat.mtime });
                    } catch {}
                }
                reports.sort((a, b) => b.modified - a.modified);
                let html = '<!DOCTYPE html><html><head><meta charset="UTF-8"><title>Test Reports</title>'+
                           '<style>body{font-family:-apple-system,BlinkMacSystemFont,\'Segoe UI\',Roboto,sans-serif;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;padding:2rem;min-height:100vh;margin:0}'+
                           '.container{max-width:1200px;margin:0 auto}.report-list{background:rgba(255,255,255,0.1);backdrop-filter:blur(10px);border-radius:1rem;padding:2rem}'+
                           '.report-item{display:flex;justify-content:space-between;align-items:center;padding:1rem;margin-bottom:1rem;background:rgba(255,255,255,0.05);border-radius:0.5rem;transition:all .3s}'+
                           '.report-item:hover{background:rgba(255,255,255,0.15);transform:translateX(10px)}a{color:white;text-decoration:none;font-weight:500}.timestamp{opacity:.8;font-size:.9rem}</style></head><body>'+
                           '<div class="container"><h1>Enhanced Test Report Viewer</h1><div class="report-list">';
                if (reports.length === 0) {
                    html += '<p>No reports available</p>';
                } else {
                    for (const r of reports) {
                        html += `<div class="report-item"><a href="/report?id=${r.id}">${r.id}</a><span class="timestamp">${r.modified.toISOString()}</span></div>`;
                    }
                }
                html += '</div></div></body></html>';
                res.writeHead(200, { 'Content-Type': 'text/html; charset=UTF-8' });
                res.end(html);
            } catch (e) {
                res.writeHead(500, { 'Content-Type': 'text/html' });
                res.end('<h1>Error listing reports</h1>');
            }
            return;
        }

        if (pathname === '/report' && req.method === 'GET') {
            const requestId = parsedUrl.query.id;
            if (!requestId) {
                res.writeHead(400, { 'Content-Type': 'text/html' });
                res.end('<h1>Missing id</h1>');
                return;
            }
            try {
                const reportHtml = await fs.readFile(path.join(LOG_BASE_DIR, 'requests', requestId, 'report.html'), 'utf8');
                res.writeHead(200, { 'Content-Type': 'text/html; charset=UTF-8' });
                res.end(reportHtml);
            } catch (e) {
                res.writeHead(404, { 'Content-Type': 'text/html' });
                res.end('<h1>Report not found</h1>');
            }
            return;
        }

        // Log APIs for integrated report viewer
        if (pathname.startsWith('/api/log/') && req.method === 'GET') {
            const parts = pathname.split('/').filter(Boolean); // [api, log, <reqId>, <type>, <file>]
            if (parts.length >= 5) {
                const requestId = parts[2];
                const logType = parts[3];
                const fileName = parts.slice(4).join('/');
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

        // Serve root-level logs like builder.log
        if (pathname.startsWith('/api/log-root/') && req.method === 'GET') {
            const parts = pathname.split('/').filter(Boolean); // [api, log-root, <reqId>, <file>]
            if (parts.length >= 4) {
                const requestId = parts[2];
                const fileName = parts.slice(3).join('/');
                try {
                    const fsPath = require('path').join(LOG_BASE_DIR, 'requests', requestId, fileName);
                    const content = await fs.readFile(fsPath, 'utf8');
                    res.writeHead(200, { 'Content-Type': 'text/plain; charset=UTF-8' });
                    res.end(content);
                } catch (e) {
                    res.writeHead(404, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: 'Root log file not found' }));
                }
            } else {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'Invalid path' }));
            }
            return;
        }

        if (pathname.startsWith('/api/logs/') && req.method === 'GET') {
            const parts = pathname.split('/').filter(Boolean); // [api, logs, <reqId>, <type>]
            if (parts.length >= 4) {
                const requestId = parts[2];
                const logType = parts[3];
                const result = await listLogFiles(requestId, logType);
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(result.files));
            } else {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'Invalid path' }));
            }
            return;
        }
        
        // Health check
        if (pathname === '/health') {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ 
                status: 'healthy',
                service: 'report-server',
                version: '1.0.0',
                port: PORT,
                timestamp: Date.now()
            }));
            return;
        }
        
        // 404 for unknown routes
        res.writeHead(404, { 'Content-Type': 'text/html' });
        res.end('<h1>404 - Page Not Found</h1>');
        
    } catch (error) {
        console.error('Server error:', error);
        res.writeHead(500, { 'Content-Type': 'text/html' });
        res.end('<h1>500 - Server Error</h1>');
    }
}

// GitHub helper headers
function githubHeaders() {
    const headers = {
        'User-Agent': 'cubrid-report-server',
        'Accept': 'application/vnd.github+json'
    };
    if (process.env.GITHUB_TOKEN) {
        headers['Authorization'] = `Bearer ${process.env.GITHUB_TOKEN}`;
    }
    return headers;
}

// Simple GitHub GET JSON
function githubRequestJson(pathname) {
    return new Promise((resolve, reject) => {
        const options = {
            hostname: 'api.github.com',
            path: pathname,
            method: 'GET',
            headers: githubHeaders()
        };
        const req = https.request(options, (resp) => {
            let data = '';
            resp.on('data', chunk => data += chunk);
            resp.on('end', () => {
                if (resp.statusCode && resp.statusCode >= 200 && resp.statusCode < 300) {
                    try { resolve(JSON.parse(data)); } catch (e) { reject(new Error('Invalid JSON from GitHub')); }
                } else {
                    reject(new Error(`GitHub API error: ${resp.statusCode}`));
                }
            });
        });
        req.on('error', reject);
        req.end();
    });
}

// GitHub GET returning status and JSON
function githubRequestJsonWithStatus(pathname) {
    return new Promise((resolve, reject) => {
        const options = {
            hostname: 'api.github.com',
            path: pathname,
            method: 'GET',
            headers: githubHeaders()
        };
        const req = https.request(options, (resp) => {
            let data = '';
            resp.on('data', chunk => data += chunk);
            resp.on('end', () => {
                let json = undefined;
                try { json = JSON.parse(data); } catch (e) {}
                resolve({ statusCode: resp.statusCode || 0, json });
            });
        });
        req.on('error', reject);
        req.end();
    });
}

// Generic HTTP(S) GET JSON helper
function httpGetJson(client, urlObj) {
    return new Promise((resolve, reject) => {
        const req = client.request({
            hostname: urlObj.hostname,
            port: urlObj.port,
            path: urlObj.pathname + (urlObj.search || ''),
            method: 'GET'
        }, (resp) => {
            let data = '';
            resp.on('data', chunk => data += chunk);
            resp.on('end', () => {
                try { resolve(JSON.parse(data)); } catch (e) { reject(new Error('Invalid JSON response')); }
            });
        });
        req.setTimeout(8000, () => { req.destroy(new Error('Request timeout')); });
        req.on('error', reject);
        req.end();
    });
}

// Report helpers
async function readLogFile(requestId, logType, fileName) {
    try {
        const logPath = path.join(LOG_BASE_DIR, 'requests', requestId, logType, fileName);
        const content = await fs.readFile(logPath, 'utf8');
        return { success: true, content };
    } catch (err) {
        return { success: false, error: err.message };
    }
}

async function listLogFiles(requestId, logType) {
    try {
        const dirPath = path.join(LOG_BASE_DIR, 'requests', requestId, logType);
        const files = await fs.readdir(dirPath);
        return { success: true, files };
    } catch (err) {
        return { success: false, files: [] };
    }
}

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
            <h1>CUBRID Test Results</h1>
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
            while (headerRow.children.length > 1) headerRow.removeChild(headerRow.children[1]);
            tbody.innerHTML = '';
            commits.forEach(commit => {
                const th = document.createElement('th');
                th.style.textAlign = 'center';
                th.innerHTML = commit.substring(0, 7);
                th.title = commit;
                headerRow.insertBefore(th, headerRow.children[headerRow.children.length - 2]);
            });
            Object.keys(processedData).sort().forEach(test => {
                const row = document.createElement('tr');
                const verdict = getTestVerdict(test);
                const failCount = calculateFailCount(test);
                const testCell = document.createElement('td');
                testCell.innerHTML = '<span class="test-name" data-test="' + encodeURIComponent(test) + '">' + test + '</span>';
                row.appendChild(testCell);
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
                    cell.innerHTML = '<span class="' + statusClass + ' result-action" data-test="' + encodeURIComponent(test) + '" data-commit="' + commit + '">' + statusText + '</span>';
                    row.appendChild(cell);
                });
                const failCountCell = document.createElement('td');
                failCountCell.style.fontWeight = 'bold';
                failCountCell.style.color = failCount === 0 ? '#065f46' : (failCount === commits.length ? '#991b1b' : '#92400e');
                failCountCell.textContent = failCount;
                row.appendChild(failCountCell);
                const verdictCell = document.createElement('td');
                verdictCell.innerHTML = '<span class="verdict ' + verdict.class + '">' + verdict.text + '</span>';
                row.appendChild(verdictCell);
                tbody.appendChild(row);
            });
            Array.prototype.forEach.call(document.querySelectorAll('.test-name'), function(el){
                el.addEventListener('click', function(){
                    var t = decodeURIComponent(el.getAttribute('data-test'));
                    showTestDetails(t);
                });
            });
            Array.prototype.forEach.call(document.querySelectorAll('.result-action'), function(el){
                el.addEventListener('click', function(){
                    var t = decodeURIComponent(el.getAttribute('data-test'));
                    var c = el.getAttribute('data-commit');
                    showExecutionLog(t, c);
                });
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
                              testName.replace(/'/g, "\\'") + '\', \'" + commit + "\')">View Execution Log</button>';
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

                    // Root logs quick access
                    logsContent += '<div style="margin-bottom: 1rem;">';
                    logsContent += '<button class="btn" onclick="loadRootLog(\'builder.log\')">Open Main builder.log</button> ';
                    logsContent += '<button class="btn" style="margin-left:.5rem" onclick="loadRootLog(\'tester.log\')">Open Main tester.log</button>';
                    logsContent += '</div>';
                    
                    if (files.length === 0) {
                        logsContent += '<p>No build logs available.</p>';
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

        // Load root-level log (e.g., builder.log, tester.log)
        async function loadRootLog(fileName) {
            try {
                const response = await fetch('/api/log-root/' + requestId + '/' + fileName);
                if (response.ok) {
                    const logContent = await response.text();
                    const content = '<div class="log-section">' +
                                   '<h3 class="log-section-title">' + fileName + '</h3>' +
                                   '<div class="log-viewer">' + escapeHtml(logContent || '(empty)') + '</div>' +
                                   '</div>';
                    showModal(fileName, content);
                } else {
                    alert('Failed to load ' + fileName);
                }
            } catch (error) {
                alert('Error loading ' + fileName + ': ' + error.message);
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

// Start the server
async function startServer() {
    await ensureDirectories();
    
    const server = http.createServer(handleRequest);
    
    server.listen(PORT, () => {
        console.log(`
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║     CUBRID Builder-Tester Report Server                     ║
║                                                              ║
╠══════════════════════════════════════════════════════════════╣
║                                                              ║
║     Dashboard: http://localhost:${PORT}                       ║
║     Builder:   http://${BUILDER_HOST}:${BUILDER_PORT}                    ║
║                                                              ║
║     Features:                                                ║
║     • Interactive web dashboard                             ║
║     • Build request submission                              ║
║     • System status monitoring                              ║
║     • API proxy to Builder service                          ║
║     • Callback endpoint for results                         ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
        `);
    });
}

// Start the server
startServer().catch(console.error);
