/**
 * CUBRID Builder-Tester Report Server - Working Version
 * Provides a web dashboard for the builder-tester system
 */

const http = require('http');
const https = require('https');
const fs = require('fs').promises;
const path = require('path');
const url = require('url');

const PORT = process.argv[2] || 8091;
const BUILDER_HOST = process.env.BUILDER_HOST || 'localhost';
const BUILDER_PORT = process.env.BUILDER_PORT || 8089;

const LOG_BASE_DIR = path.join(
    process.env.HOME || process.env.USERPROFILE,
    'cubrid-testtools', 'CTP', 'builder_tester', 'log'
);

// Ensure log directories exist
async function ensureDirectories() {
    try {
        await fs.mkdir(path.join(LOG_BASE_DIR, 'results'), { recursive: true });
    } catch (err) {
        console.error('Failed to create directories:', err.message);
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
                buildType: document.getElementById('buildType').value
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
        // Main dashboard
        if (pathname === '/' || pathname === '/index.html') {
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end(DASHBOARD_HTML);
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
                console.log('Received callback:', body);
                
                // Save result to file
                try {
                    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
                    const resultFile = path.join(LOG_BASE_DIR, 'results', `result_${timestamp}.json`);
                    await fs.writeFile(resultFile, body);
                    console.log('Saved result to:', resultFile);
                } catch (err) {
                    console.error('Failed to save result:', err);
                }
                
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: true, message: 'Callback received' }));
            });
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
