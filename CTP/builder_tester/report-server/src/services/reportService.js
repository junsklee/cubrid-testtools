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
        let html = this.getReportTemplate(data, requestId, timestamp, resultsJSON);
        // Inject the SQL add-on (artifact panel, CTP provenance line) only when
        // SQL data is present so existing shell reports stay byte-identical
        if (this.hasSqlData(data)) {
            const bodyEnd = html.lastIndexOf('</body>');
            if (bodyEnd !== -1) {
                html = html.slice(0, bodyEnd) + '    <script src="/js/report-sql.js"></script>\n' + html.slice(bodyEnd);
            }
        }
        return html;
    }

    /**
     * Check whether the payload contains SQL test data
     */
    hasSqlData(data) {
        if (!data) return false;
        if (data.testType === 'sql' || data.ctpProvenance) return true;
        const results = Array.isArray(data.results) ? data.results : [];
        return results.some(r => r && r.testType === 'sql');
    }

    /**
     * Split artifact entries (attemptLogMetadata entries carrying an artifactType
     * key) into a separate artifacts array per result entry so attempt-log
     * consumers don't render artifacts as extra attempts (SQL reports).
     * Shell payloads without artifact entries are returned unchanged.
     */
    separateResultArtifacts(data) {
        if (!data || !Array.isArray(data.results)) return data;
        const hasArtifacts = data.results.some(r =>
            r && Array.isArray(r.attemptLogMetadata) && r.attemptLogMetadata.some(m => m && m.artifactType)
        );
        if (!hasArtifacts) return data;
        const results = data.results.map(r => {
            if (!r || !Array.isArray(r.attemptLogMetadata)) return r;
            const artifacts = r.attemptLogMetadata.filter(m => m && m.artifactType);
            if (artifacts.length === 0) return r;
            return Object.assign({}, r, {
                attemptLogMetadata: r.attemptLogMetadata.filter(m => !(m && m.artifactType)),
                artifacts: artifacts
            });
        });
        return Object.assign({}, data, { results });
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
                    commitTimestamps: data.commitTimestamps || requestConfig.commitTimestamps,
                    testType: data.testType || requestConfig.testType,
                    ctpProvenance: data.ctpProvenance || requestConfig.ctpProvenance
                });
            } catch (err) {
                console.warn('Could not read request.json, using callback data only:', err.message);
            }

            // Expose artifact entries separately from attempt logs (SQL reports)
            combinedData = this.separateResultArtifacts(combinedData);

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
    analyzeVerdict(commits, results, commitOrder, commitBuildMode, runMode, buildOnlyMode) {
        const resultsArr = Array.isArray(results) ? results : [];
        const order = Array.isArray(commitOrder) && commitOrder.length > 0 ? commitOrder : (Array.isArray(commits) ? commits : []);
        const commitCount = order.length;

        const mode = (runMode || 'until-pass').toString().toLowerCase();
        const isCheckout = (commitBuildMode || '').toString().toLowerCase() === 'checkout';
        const isBuildOnly = !!buildOnlyMode || resultsArr.some(r => {
            const status = (r && r.status ? r.status : '').toString().toLowerCase();
            return status === 'build_success' ||
                   status === 'build_failed' ||
                   status === 'upload_success' ||
                   status === 'upload_failed' ||
                   (r && r.test === 'build_only');
        });
        const modeSuffix = (!isBuildOnly && mode !== 'until-pass') ? ` (${mode})` : '';
        const baselineSuffix = isCheckout ? '' : ' (baseline mode)';

        const singleCommitPassText = () => {
            if (isBuildOnly) return 'Build successful (single commit)';
            if (mode === 'until-fail') return `Not reproduced on tested commit${modeSuffix}`;
            if (mode === 'fixed-runs') return `All runs passed on tested commit${modeSuffix}`;
            return 'Pass on tested commit';
        };
        const singleCommitFailText = () => {
            if (isBuildOnly) return 'Build failed (single commit)';
            if (mode === 'until-fail') return `Failure reproduced on tested commit${modeSuffix}`;
            if (mode === 'fixed-runs') return `Failures observed on tested commit${modeSuffix}`;
            return 'Failure observed on tested commit';
        };
        const multiCommitPassText = () => {
            if (isBuildOnly) return `Builds successful across tested commits${baselineSuffix}`;
            if (mode === 'until-fail') return `Not reproduced across tested commits${modeSuffix}${baselineSuffix}`;
            if (mode === 'fixed-runs') return `All runs passed across tested commits${modeSuffix}${baselineSuffix}`;
            return `Pass: No failures across tested commits${baselineSuffix}`;
        };
        const multiCommitAllFailText = () => {
            if (isBuildOnly) return `Builds failed across tested commits${baselineSuffix}`;
            if (mode === 'until-fail') return `Failure reproduced across tested commits${modeSuffix}${baselineSuffix}`;
            if (mode === 'fixed-runs') return `Failures observed across tested commits${modeSuffix}${baselineSuffix}`;
            return `Pre-existing failure across tested commits${baselineSuffix}`;
        };
        const regressionText = (shortSha) => {
            if (isBuildOnly) return `Build regression introduced: ${shortSha}`;
            if (mode === 'until-fail') return `Failure reproduced starting: ${shortSha}${modeSuffix}`;
            if (mode === 'fixed-runs') return `Failure observed starting: ${shortSha}${modeSuffix}`;
            return `Regression introduced: ${shortSha}`;
        };
        const fixText = (shortSha) => {
            if (isBuildOnly) return `Build fixed by later commit: ${shortSha}`;
            if (mode === 'until-fail') return `Failure no longer reproduced after: ${shortSha}${modeSuffix}`;
            if (mode === 'fixed-runs') return `Failure no longer observed after: ${shortSha}${modeSuffix}`;
            return `Fixed by later commit: ${shortSha}`;
        };
        const isolatedFailureText = (shortSha) => {
            if (isBuildOnly) return `Build failed on ${shortSha}${baselineSuffix}`;
            if (mode === 'until-fail') return `Failure reproduced only on ${shortSha}${modeSuffix}${baselineSuffix}`;
            if (mode === 'fixed-runs') return `Failures observed only on ${shortSha}${modeSuffix}${baselineSuffix}`;
            return `Isolated failure on ${shortSha}${baselineSuffix}`;
        };

        // Aggregate per-commit so this function stays correct even if callers pass results across multiple tests.
        // This avoids "last write wins" overwrites when multiple rows share the same commit.
        const aggregated = new Map(); // commit -> { pass, fail, error, flaky, unknown }
        for (const r of resultsArr) {
            if (!r || !r.commit) continue;
            const commit = r.commit;
            const status = (r.status || 'unknown').toString().toLowerCase();
            const slot = aggregated.get(commit) || { pass: false, fail: false, error: false, flaky: false, unknown: false };

            if (r.flaky || status === 'flaky') slot.flaky = true;
            else if (status === 'error' || status === 'execution_error' || status === 'environment_error') slot.error = true;
            else if (status === 'pass' || status === 'build_success' || status === 'upload_success') slot.pass = true;
            else if (status === 'fail' || status === 'build_failed' || status === 'upload_failed') slot.fail = true;
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

        if (hasFlaky) return { text: 'Flaky: Mixed pass/fail across attempts', class: 'verdict-flaky' };
        if (hasError) return { text: 'Error: Test execution failed', class: 'verdict-error' };

        const normalized = order.map((c) => (commitStatus.get(c) === 'pass' ? 'pass' : 'fail'));
        if (normalized.length === 0) {
            return { text: 'Unknown: No results', class: 'verdict-error' };
        }

        if (commitCount === 1) {
            if (normalized[0] === 'pass') return { text: singleCommitPassText(), class: 'verdict-success' };
            return { text: singleCommitFailText(), class: 'verdict-bug' };
        }

        if (isCheckout) {
            const allPass = normalized.every(s => s === 'pass');
            const allFail = normalized.every(s => s === 'fail');
            if (allPass) return { text: multiCommitPassText(), class: 'verdict-success' };
            if (allFail) return { text: multiCommitAllFailText(), class: 'verdict-preexisting' };

            let transitions = 0;
            for (let i = 1; i < normalized.length; i++) {
                if (normalized[i] !== normalized[i - 1]) transitions++;
            }

            if (transitions === 1) {
                const firstFailIndex = normalized.indexOf('fail');
                if (firstFailIndex > 0 && normalized[0] === 'pass') {
                    const regressionCommit = order[firstFailIndex];
                    const shortSha = regressionCommit ? regressionCommit.substring(0, 7) : '';
                    return { text: regressionText(shortSha), class: 'verdict-bug' };
                }
                const firstPassIndex = normalized.indexOf('pass');
                if (firstPassIndex > 0 && normalized[0] === 'fail') {
                    const fixCommit = order[firstPassIndex];
                    const shortSha = fixCommit ? fixCommit.substring(0, 7) : '';
                    return { text: fixText(shortSha), class: 'verdict-success' };
                }
            }

            return { text: 'Unstable: Multiple transitions', class: 'verdict-unstable' };
        }

        const failCount = normalized.filter(s => s === 'fail').length;
        if (failCount === 0) return { text: multiCommitPassText(), class: 'verdict-success' };
        if (failCount === 1) {
            const failIndex = normalized.indexOf('fail');
            const failedCommit = order[failIndex] || '';
            const shortSha = failedCommit ? failedCommit.substring(0, 7) : '';
            return { text: isolatedFailureText(shortSha), class: 'verdict-bug' };
        }
        if (failCount === normalized.length) return { text: multiCommitAllFailText(), class: 'verdict-preexisting' };
        if (isBuildOnly) return { text: `Build unstable across commits${baselineSuffix}`, class: 'verdict-unstable' };
        return { text: `Unstable: Mixed results across commits${baselineSuffix}`, class: 'verdict-unstable' };
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
