/**
 * Report Client-Side JavaScript
 */

document.addEventListener('DOMContentLoaded', function() {
    // Process report data if available
    if (typeof resultsData !== 'undefined') {
        processReportData(resultsData);
    }
});

/**
 * Process and display report data
 */
function processReportData(data) {
    const container = document.getElementById('resultsContainer');
    if (!container) return;
    
    // Create statistics grid
    const stats = calculateStatistics(data);
    const statsHtml = createStatisticsGrid(stats);
    
    // Create results table
    const tableHtml = createResultsTable(data);
    
    container.innerHTML = statsHtml + tableHtml;
    
    // Add event listeners
    attachEventListeners();
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
                html += `<td><span class="${statusClass}">${statusText}${attempts}</span></td>`;
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

/**
 * Show test details modal
 */
function showTestDetails(testName) {
    console.log('Show details for test:', testName);
    // Implementation for showing test details modal
}