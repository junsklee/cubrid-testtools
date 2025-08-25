/**
 * Report Client-Side JavaScript
 */

// Ensure execution whether this script is injected after DOMContentLoaded or before
(function initReportRendering() {
    function getData() {
        return (typeof reportData !== 'undefined') ? reportData :
               (typeof resultsData !== 'undefined') ? resultsData : null;
    }
    function render() {
        var data = getData();
        if (data) processReportData(data);
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', render);
    } else {
        // DOM already ready
        render();
    }
})();

/**
 * Process and display report data
 */
function processReportData(data) {
    // Prefer modern container id used by current report template
    const container = document.getElementById('report-content') || document.getElementById('resultsContainer');
    if (!container) return;

    // Normalize payload (array of results -> map by test then commit)
    const normalized = normalizePayload(data);

    // Create statistics grid
    const stats = calculateStatistics(normalized);
    const statsHtml = createStatisticsGrid(stats);

    // Create results table
    const tableHtml = createResultsTable(normalized);

    container.innerHTML = statsHtml + tableHtml;

    // Add event listeners
    attachEventListeners();

    // Wire toolbar buttons if present
    wireToolbar(normalized);
}

// Normalize payload to expected shape: { commits: string[], results: { [test]: { [commit]: result } } }
function normalizePayload(data) {
    // If already in expected map shape, return as-is
    if (data && !Array.isArray(data.results)) {
        return data;
    }

    const resultsArray = Array.isArray(data && data.results) ? data.results : [];
    const commitsSet = new Set();
    const resultsMap = {};

    resultsArray.forEach(item => {
        const testName = item.test || 'unknown_test';
        const commit = item.commit || 'unknown_commit';
        commitsSet.add(commit);
        if (!resultsMap[testName]) resultsMap[testName] = {};
        resultsMap[testName][commit] = {
            status: item.status || 'unknown',
            message: item.message || '',
            attempts: item.attempts || 0,
            flaky: !!item.flaky,
            logPath: item.logPath || ''
        };
    });

    return {
        commits: Array.from(commitsSet),
        results: resultsMap
    };
}

/**
 * Calculate statistics from results
 */function calculateStatistics(data) {
    let totalTests = 0;
    let passedTests = 0;
    let failedTests = 0;
    let errorTests = 0;
    let flakyTests = 0;
    
    // Process test results
    if (data.results) {
        for (const testName in data.results) {
            totalTests++;
            const testResults = data.results[testName];
            
            let hasPass = false;
            let hasFail = false;
            let hasError = false;
            
            for (const commit in testResults) {
                const result = testResults[commit];
                if (result.status === 'pass') hasPass = true;
                if (result.status === 'fail') hasFail = true;
                if (result.status === 'error') hasError = true;
                if (result.flaky) flakyTests++;
            }
            
            if (hasError) errorTests++;
            else if (hasPass && !hasFail) passedTests++;
            else failedTests++;
        }
    }
    
    return {
        total: totalTests,
        passed: passedTests,
        failed: failedTests,
        error: errorTests,
        flaky: flakyTests,
        passRate: totalTests > 0 ? Math.round((passedTests / totalTests) * 100) : 0
    };
}

/**
 * Create statistics grid HTML
 */
function createStatisticsGrid(stats) {
    return `
        <div class="stats-grid">
            <div class="stat-card">
                <div class="stat-value">${stats.total}</div>
                <div class="stat-label">Total Tests</div>
            </div>
            <div class="stat-card">
                <div class="stat-value" style="color: var(--success-color)">${stats.passed}</div>
                <div class="stat-label">Passed</div>
            </div>
            <div class="stat-card">
                <div class="stat-value" style="color: var(--error-color)">${stats.failed}</div>
                <div class="stat-label">Failed</div>
            </div>
            <div class="stat-card">
                <div class="stat-value">${stats.passRate}%</div>
                <div class="stat-label">Pass Rate</div>
            </div>
        </div>
    `;
}

/**
 * Create results table HTML
 */
function createResultsTable(data) {
    if (!data.results || Object.keys(data.results).length === 0) {
        return '<p>No test results available</p>';
    }
    
    const commits = data.commits || [];
    const tests = Object.keys(data.results);
    
    let html = '<div class="results-table"><table>';
    
    // Header
    html += '<thead><tr><th>Test Case</th>';
    commits.forEach(commit => {
        html += `<th title="${commit}">${commit.substring(0, 7)}</th>`;
    });
    html += '<th>Verdict</th></tr></thead>';
    
    // Body
    html += '<tbody>';
    tests.forEach(test => {
        html += `<tr><td class="test-name" data-test="${test}">${test}</td>`;
        
        commits.forEach(commit => {
            const result = data.results[test][commit];
            if (result) {
                const statusClass = `result-${result.status}`;
                const statusText = result.status.toUpperCase();
                const attempts = result.attempts ? `(${result.attempts})` : '';
                const logLink = result.logPath ? `<br><a href="/api/log-root/${encodeURIComponent(getRequestId())}/${encodeURIComponent(result.logPath.split('/').pop())}" target="_blank" class="log-link">view log</a>` : '';
                html += `<td><span class="${statusClass}">${statusText}${attempts}</span>${logLink}</td>`;
            } else {
                html += '<td>-</td>';
            }
        });
        
        // Add verdict
        const verdict = determineVerdict(data.results[test], commits);
        html += `<td class="${verdict.class}">${verdict.text}</td>`;
        html += '</tr>';
    });
    html += '</tbody></table></div>';
    
    return html;
}

function getRequestId() {
    try {
        if (typeof reportData !== 'undefined' && reportData.requestId) return reportData.requestId;
        if (typeof resultsData !== 'undefined' && resultsData.requestId) return resultsData.requestId;
    } catch (e) {}
    // Fallback: parse from URL param `id`
    try {
        const params = new URLSearchParams(window.location.search);
        return params.get('id') || '';
    } catch (e) { return ''; }
}

/**
 * Determine test verdict
 */
function determineVerdict(testResults, commits) {
    // Simplified verdict logic
    let failCount = 0;
    let hasFlaky = false;
    
    for (const commit of commits) {
        const result = testResults[commit];
        if (result) {
            if (result.flaky) hasFlaky = true;
            if (result.status === 'fail') failCount++;
        }
    }
    
    if (hasFlaky) {
        return { text: 'Flaky', class: 'verdict-flaky' };
    }
    if (failCount === 0) {
        return { text: 'Pass', class: 'verdict-success' };
    }
    if (failCount === commits.length) {
        return { text: 'Pre-existing', class: 'verdict-preexisting' };
    }
    return { text: 'Unstable', class: 'verdict-unstable' };
}

/**
 * Attach event listeners
 */
function attachEventListeners() {
    // Add click handlers for test names
    document.querySelectorAll('.test-name').forEach(el => {
        el.addEventListener('click', function() {
            const testName = this.getAttribute('data-test');
            showTestDetails(testName);
        });
    });
}

function wireToolbar(data) {
    const reqId = getRequestId();
    const btnBuilder = document.getElementById('btnBuilderLog');
    const btnTester = document.getElementById('btnTesterLog');
    const btnJson = document.getElementById('btnDownloadJson');
    const btnCsv = document.getElementById('btnDownloadCsv');
    const modal = document.getElementById('log-modal');
    const modalTitle = document.getElementById('log-modal-title');
    const modalBody = document.getElementById('log-modal-body');
    const modalClose = document.getElementById('log-modal-close');

    function openModal(title, content) {
        modalTitle.textContent = title;
        modalBody.textContent = content || '';
        modal.classList.add('show');
    }
    function closeModal() { modal.classList.remove('show'); }
    if (modalClose) modalClose.addEventListener('click', closeModal);
    if (modal) modal.addEventListener('click', (e) => { if (e.target === modal) closeModal(); });

    if (btnBuilder) {
        btnBuilder.addEventListener('click', async () => {
            try {
                const res = await fetch(`/api/log-root/${encodeURIComponent(reqId)}/builder.log`);
                const text = await res.text();
                openModal('Builder Log', text);
            } catch (e) { openModal('Builder Log', 'Failed to load log'); }
        });
    }
    if (btnTester) {
        btnTester.addEventListener('click', async () => {
            try {
                const res = await fetch(`/api/log-root/${encodeURIComponent(reqId)}/tester.log`);
                const text = await res.text();
                openModal('Tester Log', text);
            } catch (e) { openModal('Tester Log', 'Failed to load log'); }
        });
    }
    if (btnJson) {
        btnJson.addEventListener('click', () => {
            const blob = new Blob([JSON.stringify((typeof reportData !== 'undefined' ? reportData : data), null, 2)], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url; a.download = `${reqId || 'results'}.json`; a.click();
            URL.revokeObjectURL(url);
        });
    }
    if (btnCsv) {
        btnCsv.addEventListener('click', () => {
            const csv = toCsv(data);
            const blob = new Blob([csv], { type: 'text/csv' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url; a.download = `${reqId || 'results'}.csv`; a.click();
            URL.revokeObjectURL(url);
        });
    }
}

function toCsv(data) {
    const commits = data.commits || [];
    const tests = Object.keys(data.results || {});
    const header = ['Test', ...commits.map(c => c.substring(0,7)), 'Verdict'];
    const lines = [header.join(',')];
    tests.forEach(test => {
        const row = [JSON.stringify(test)];
        commits.forEach(commit => {
            const result = (data.results[test] || {})[commit];
            row.push(result ? result.status.toUpperCase() : '');
        });
        const verdict = determineVerdict(data.results[test], commits).text;
        row.push(verdict);
        lines.push(row.join(','));
    });
    return lines.join('\n');
}

/**
 * Show test details modal
 */
function showTestDetails(testName) {
    console.log('Show details for test:', testName);
    // Implementation for showing test details modal
}