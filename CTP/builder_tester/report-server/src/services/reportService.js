/**
 * Report Service - Handles test report generation
 */

const fileService = require('./fileService');
const config = require('../config');
const path = require('path');

class ReportService {
    /**
     * Generate report HTML from test results
     */
    generateReportHTML(data, requestId) {
        const timestamp = new Date().toISOString();
        const resultsJSON = JSON.stringify(data);
        
        // This is a simplified version - in production, use a template engine
        const html = this.getReportTemplate(data, requestId, timestamp, resultsJSON);
        return html;
    }

    /**
     * Process test results and save report
     */
    async processTestResults(requestId, data) {
        try {
            // Save raw results
            await fileService.saveTestResults(requestId, data);
            
            // Try to read existing request.json for configuration data
            let combinedData = data;
            try {
                const requestPath = path.join(config.paths.requests, requestId, 'request.json');
                const requestConfig = JSON.parse(await fileService.readFile(requestPath));
                
                // Combine results data with request configuration
                combinedData = Object.assign({}, requestConfig, data, {
                    // Ensure results data takes precedence, but include config fields
                    runMode: data.runMode || requestConfig.runMode,
                    buildType: data.buildType || requestConfig.buildType,
                    minRuns: data.minRuns || requestConfig.minRuns,
                    maxRuns: data.maxRuns || requestConfig.maxRuns,
                    workerIps: data.workerIps || requestConfig.workerIps,
                    commits: data.commits || requestConfig.commits,
                    baselineCommit: data.baselineCommit || requestConfig.baselineCommit,
                    commitBuildMode: data.commitBuildMode || requestConfig.commitBuildMode,
                    commitOrder: data.commitOrder || requestConfig.commitOrder,
                    commitTimestamps: data.commitTimestamps || requestConfig.commitTimestamps
                });
            } catch (err) {
                console.warn('Could not read request.json, using callback data only:', err.message);
            }
            
            // Generate HTML report with combined data
            const html = this.generateReportHTML(combinedData, requestId);
            
            // Save HTML report
            await fileService.saveReport(requestId, html);
            
            return { success: true, requestId };
        } catch (err) {
            throw new Error(`Failed to process test results: ${err.message}`);
        }
    }

    /**
     * Get list of available reports
     */
    async getReportsList() {
        try {
            return await fileService.getReportsList();
        } catch (err) {
            throw new Error(`Failed to get reports list: ${err.message}`);
        }
    }

    /**
     * Get basic report template
     */
    getReportTemplate(data, requestId, timestamp, resultsJSON) {
        // Use the integrated template from Java report-template.html for full UI
        try {
            const fs = require('fs');
            const path = require('path');
            const templatePath = path.join(__dirname, '..', '..', '..', 'src', 'com', 'navercorp', 'cubridqa', 'builder', 'report', 'report-template.html');
            const raw = fs.readFileSync(templatePath, 'utf8');
            return raw
                .replace(/\{\{REQUEST_ID\}\}/g, requestId)
                .replace(/\{\{TIMESTAMP\}\}/g, timestamp)
                .replace(/\{\{RESULTS_JSON\}\}/g, resultsJSON);
        } catch (e) {
            // Fallback minimal template
            return `<!DOCTYPE html>
<html>
<head>
    <title>Test Report - ${requestId}</title>
    <link rel="stylesheet" href="/css/report.css">
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
</head>
<body>
    <div class="container">
        <h1>Test Report - ${requestId}</h1>
        <p>Generated: ${timestamp}</p>
        <div id="report-content"></div>
    </div>
    <script>
        const reportData = ${resultsJSON};
        (function(){ var s=document.createElement('script'); s.src='/js/report.js'; document.head.appendChild(s); })();
    </script>
</body>
</html>`;
        }
    }

    /**
     * Analyze test verdict based on results
     */
    analyzeVerdict(commits, results, commitOrder, commitBuildMode) {
        const resultsArr = Array.isArray(results) ? results : [];
        const order = Array.isArray(commitOrder) && commitOrder.length > 0 ? commitOrder : (Array.isArray(commits) ? commits : []);

        // Aggregate per-commit so this function stays correct even if callers pass results across multiple tests.
        // This avoids "last write wins" overwrites when multiple rows share the same commit.
        const aggregated = new Map(); // commit -> { pass, fail, error, flaky, unknown }
        for (const r of resultsArr) {
            if (!r || !r.commit) continue;
            const commit = r.commit;
            const status = (r.status || 'unknown').toString().toLowerCase();
            const slot = aggregated.get(commit) || { pass: false, fail: false, error: false, flaky: false, unknown: false };

            if (status === 'pass') slot.pass = true;
            else if (status === 'fail') slot.fail = true;
            else if (status === 'flaky') slot.flaky = true;
            else if (status === 'error' || status === 'execution_error') slot.error = true;
            else slot.unknown = true;

            aggregated.set(commit, slot);
        }

        const commitStatus = new Map(); // commit -> pass|fail|error|flaky|unknown
        let hasFlaky = false;
        let hasError = false;
        for (const commit of order) {
            const slot = aggregated.get(commit) || { pass: false, fail: false, error: false, flaky: false, unknown: true };
            let s = 'unknown';
            if (slot.flaky) s = 'flaky';
            else if (slot.error) s = 'error';
            else if (slot.fail) s = 'fail';
            else if (slot.unknown) s = 'unknown';
            else if (slot.pass) s = 'pass';
            commitStatus.set(commit, s);
            if (s === 'flaky') hasFlaky = true;
            if (s === 'error') hasError = true;
        }

        if (hasFlaky) return { text: 'Flaky: Inconsistent results', class: 'verdict-flaky' };
        if (hasError) return { text: 'Error: Test execution failed', class: 'verdict-error' };

        const normalized = order.map((c) => (commitStatus.get(c) === 'pass' ? 'pass' : 'fail'));
        if (normalized.length === 0) {
            return { text: 'Unknown: No results', class: 'verdict-error' };
        }

        if (commitBuildMode === 'checkout') {
            const allPass = normalized.every(s => s === 'pass');
            const allFail = normalized.every(s => s === 'fail');
            if (allPass) return { text: 'Pass: Not reproduced', class: 'verdict-success' };
            if (allFail) return { text: 'Pre-existing Failure', class: 'verdict-preexisting' };

            let transitions = 0;
            for (let i = 1; i < normalized.length; i++) {
                if (normalized[i] !== normalized[i - 1]) transitions++;
            }

            if (transitions === 1) {
                const firstFailIndex = normalized.indexOf('fail');
                if (firstFailIndex > 0 && normalized[0] === 'pass') {
                    const regressionCommit = order[firstFailIndex];
                    const shortSha = regressionCommit ? regressionCommit.substring(0, 7) : '';
                    return { text: `Regression introduced: ${shortSha}`, class: 'verdict-bug' };
                }
                const firstPassIndex = normalized.indexOf('pass');
                if (firstPassIndex > 0 && normalized[0] === 'fail') {
                    const fixCommit = order[firstPassIndex];
                    const shortSha = fixCommit ? fixCommit.substring(0, 7) : '';
                    return { text: `Fixed by later commit: ${shortSha}`, class: 'verdict-success' };
                }
            }

            return { text: 'Unstable: Multiple transitions', class: 'verdict-unstable' };
        }

        const failCount = normalized.filter(s => s === 'fail').length;
        if (failCount === 0) return { text: 'Pass: Not reproduced', class: 'verdict-success' };
        if (failCount === 1) return { text: 'Bug or Revise: Caused by commit', class: 'verdict-bug' };
        if (failCount === normalized.length) return { text: 'Pre-existing Failure', class: 'verdict-preexisting' };
        return { text: 'Unstable: Fails intermittently', class: 'verdict-unstable' };
    }

    /**
     * Calculate test statistics from results
     * This matches the logic from report.js's calculateStatistics function
     *
     * @param {Array} resultsArray - Array of test results
     * @returns {Object} Statistics with counts
     */
    calculateTestStatistics(resultsArray) {
        if (!resultsArray || !Array.isArray(resultsArray) || resultsArray.length === 0) {
            return {
                total: 0,
                passed: 0,
                failed: 0,
                error: 0,
                flaky: 0,
                unstable: 0
            };
        }

        // First, convert array format to map format like report.js expects
        // resultsMap: { testName: { commit: { status, flaky, ... }, ... }, ... }
        const resultsMap = {};

        // Check if this is build-only mode
        const buildOnlyMode = resultsArray.length > 0 && resultsArray.some(r =>
            r.status === 'build_success' || r.status === 'upload_success' || r.status === 'upload_failed' || r.test === 'build_only'
        );

        for (const item of resultsArray) {
            const testName = item.test || 'unknown_test';
            const commit = item.commit || 'unknown_commit';

            if (!resultsMap[testName]) {
                resultsMap[testName] = {};
            }

            // In build-only mode, prioritize build_success over upload statuses
            // Don't overwrite build_success with upload_success or upload_failed
            const existingData = resultsMap[testName][commit];
            const shouldSkip = buildOnlyMode &&
                              existingData &&
                              existingData.status === 'build_success' &&
                              (item.status === 'upload_failed' || item.status === 'upload_success');

            if (!shouldSkip) {
                resultsMap[testName][commit] = {
                    status: item.status || 'unknown',
                    flaky: !!item.flaky,
                    message: item.message || '',
                    attempts: item.attempts || 0
                };
            }
        }

        // Now apply the EXACT same logic as report.js's calculateStatistics
        let totalTests = 0;
        let passedTests = 0;
        let failedTests = 0;
        let errorTests = 0;
        let flakyTests = 0;
        let unstableTests = 0;

        for (const testName in resultsMap) {
            totalTests++;
            const testResults = resultsMap[testName];

            let hasPass = false;
            let hasFail = false;
            let hasError = false;
            let hasFlaky = false;

            for (const commit in testResults) {
                const result = testResults[commit];
                const status = result.status || '';

                // Recognize both test statuses and build-only statuses
                if (status === 'pass' || status === 'build_success' || status === 'upload_success') hasPass = true;
                if (status === 'fail' || status === 'build_failed' || status === 'upload_failed') hasFail = true;
                if (status === 'error' || status === 'execution_error') hasError = true;
                if (result.flaky) hasFlaky = true;
            }

            // Classify this test (using report.js logic + unstable)
            if (hasError) {
                errorTests++;
            } else if (hasFlaky) {
                flakyTests++;
            } else if (hasPass && hasFail) {
                // Test passed on some commits and failed on others = unstable
                unstableTests++;
            } else if (hasPass && !hasFail) {
                passedTests++;
            } else {
                failedTests++;
            }
        }

        return {
            total: totalTests,
            passed: passedTests,
            failed: failedTests,
            error: errorTests,
            flaky: flakyTests,
            unstable: unstableTests
        };
    }
}

module.exports = new ReportService();
