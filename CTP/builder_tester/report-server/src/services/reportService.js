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
        const resultsJSON = this.embedJSON(data);

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
                const requestPath = fileService.requestPath(requestId, 'request.json');
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
    /**
     * Serialise data for embedding in a <script> block.
     *
     * JSON.stringify does not escape "<", so content containing "</script>" would end
     * the block early. Escaping it as \u003c is transparent: JSON.parse yields the
     * same string. Test content is arbitrary user input, so this cannot be assumed away.
     */
    embedJSON(data) {
        return JSON.stringify(data).replace(/</g, '\\u003c');
    }

    getReportTemplate(data, requestId, timestamp, resultsJSON) {
        // Use the integrated template from Java report-template.html for full UI
        try {
            const fs = require('fs');
            const path = require('path');
            const templatePath = path.join(__dirname, '..', '..', '..', 'src', 'com', 'navercorp', 'cubridqa', 'builder', 'report', 'report-template.html');
            const raw = fs.readFileSync(templatePath, 'utf8');
            // Replacer FUNCTIONS, not strings: in a string replacement JS treats $',
            // $`, $& and $$ as patterns, and test content legitimately contains them.
            // A shell case with `grep '^-.*\.access$' | awk '{ print $9 }'` has $',
            // which splices the rest of the template into the JSON and truncates it,
            // so JSON.parse fails and the report renders no tests at all.
            return raw
                .replace(/\{\{REQUEST_ID\}\}/g, () => requestId)
                .replace(/\{\{TIMESTAMP\}\}/g, () => timestamp)
                .replace(/\{\{RESULTS_JSON\}\}/g, () => resultsJSON);
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
    analyzeVerdict(commits, results, commitOrder, commitBuildMode, runMode, buildOnlyMode, requestError) {
        const resultsArr = Array.isArray(results) ? results : [];
        const order = Array.isArray(commitOrder) && commitOrder.length > 0 ? commitOrder : (Array.isArray(commits) ? commits : []);

        const mode = (runMode || 'until-pass').toString().toLowerCase();
        const isCheckout = (commitBuildMode || '').toString().toLowerCase() === 'checkout';
        const BUILD_STAGE = new Set(['build_success', 'build_failed', 'upload_success', 'upload_failed']);
        // Build-only when flagged, or when every result is a build/upload stage. A test run in which
        // one commit failed to build still has test results for the others and keeps test wording.
        const isBuildOnly = !!buildOnlyMode || (resultsArr.length > 0 && resultsArr.every(r => {
            const status = (r && r.status ? r.status : '').toString().toLowerCase();
            return BUILD_STAGE.has(status) || (r && r.test === 'build_only');
        }));
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

        // Aggregate per commit across tests. A failure on a commit is what the verdict is about, so
        // it is never hidden by another test's flaky or errored result on the same commit.
        const ERROR_STATUSES = new Set(['error', 'execution_error', 'environment_error', 'build_error']);
        const aggregated = new Map(); // commit -> { pass, fail, error, flaky, unknown, uploadFailed }
        for (const r of resultsArr) {
            if (!r || !r.commit) continue;
            const commit = r.commit;
            const status = (r.status || 'unknown').toString().toLowerCase();
            const slot = aggregated.get(commit) || { pass: false, fail: false, error: false, flaky: false, unknown: false, uploadFailed: false };

            // Uploading the artifact is a stage AFTER the build. A tarball that
            // fails to transfer (bad SFTP credentials, unreachable host) says
            // nothing about whether the commit built, so it is tracked apart
            // instead of being counted as a build failure - folding it in here
            // reported "Build failed" for builds that had in fact succeeded.
            if (status === 'upload_failed') {
                slot.uploadFailed = true;
            }
            else if (r.flaky || status === 'flaky') slot.flaky = true;
            // build_error: the tester could not install the build (VM/direct mode) - an
            // environment problem, not a verdict on the commit.
            else if (ERROR_STATUSES.has(status)) slot.error = true;
            else if (status === 'pass' || status === 'build_success' || status === 'upload_success') slot.pass = true;
            else if (status === 'fail' || status === 'build_failed') slot.fail = true;
            else slot.unknown = true;

            aggregated.set(commit, slot);
        }

        // Per commit: fail (some test failed), pass (everything passed, flaky ones eventually),
        // or skip (only errors / no results: says nothing about the commit).
        let hasFlaky = false;
        let hasError = false;
        let uploadFailures = 0;
        const sequence = [];
        for (const commit of order) {
            const slot = aggregated.get(commit) || { unknown: true };
            if (slot.flaky) hasFlaky = true;
            if (slot.error || slot.unknown) hasError = true;
            if (slot.uploadFailed) uploadFailures += 1;
            if (slot.fail) sequence.push({ commit, s: 'fail' });
            else if ((slot.pass || slot.flaky) && !slot.error && !slot.unknown) sequence.push({ commit, s: 'pass' });
        }

        const patternVerdict = () => {
            const seqCommits = sequence.map(e => e.commit);
            const normalized = sequence.map(e => e.s);
            const commitCount = normalized.length;

            if (commitCount === 1) {
                if (normalized[0] === 'pass') return { text: singleCommitPassText(), class: 'verdict-success' };
                return { text: singleCommitFailText(), class: 'verdict-bug' };
            }

            if (isCheckout) {
                const allPass = normalized.every(st => st === 'pass');
                const allFail = normalized.every(st => st === 'fail');
                if (allPass) return { text: multiCommitPassText(), class: 'verdict-success' };
                if (allFail) return { text: multiCommitAllFailText(), class: 'verdict-preexisting' };

                let transitions = 0;
                for (let i = 1; i < normalized.length; i++) {
                    if (normalized[i] !== normalized[i - 1]) transitions++;
                }

                if (transitions === 1) {
                    const firstFailIndex = normalized.indexOf('fail');
                    if (firstFailIndex > 0 && normalized[0] === 'pass') {
                        return { text: regressionText(seqCommits[firstFailIndex].substring(0, 7)), class: 'verdict-bug' };
                    }
                    const firstPassIndex = normalized.indexOf('pass');
                    if (firstPassIndex > 0 && normalized[0] === 'fail') {
                        return { text: fixText(seqCommits[firstPassIndex].substring(0, 7)), class: 'verdict-success' };
                    }
                }

                return { text: 'Unstable: Multiple transitions', class: 'verdict-unstable' };
            }

            const failCount = normalized.filter(st => st === 'fail').length;
            if (failCount === 0) return { text: multiCommitPassText(), class: 'verdict-success' };
            if (failCount === 1) {
                const failedCommit = seqCommits[normalized.indexOf('fail')] || '';
                return { text: isolatedFailureText(failedCommit.substring(0, 7)), class: 'verdict-bug' };
            }
            if (failCount === normalized.length) return { text: multiCommitAllFailText(), class: 'verdict-preexisting' };
            if (isBuildOnly) return { text: `Build unstable across commits${baselineSuffix}`, class: 'verdict-unstable' };
            return { text: `Unstable: Mixed results across commits${baselineSuffix}`, class: 'verdict-unstable' };
        };

        const verdict = (() => {
            if (resultsArr.length === 0) {
                // The Builder sends {status:'error', message} with no results when a request fails
                // before testing (checkout, build setup, ...).
                if (requestError) return { text: `Error: ${requestError}`, class: 'verdict-error' };
                return { text: 'Unknown: No results', class: 'verdict-error' };
            }
            if (sequence.some(e => e.s === 'fail')) return patternVerdict();
            if (hasError) return { text: 'Error: Test execution failed', class: 'verdict-error' };
            if (hasFlaky) return { text: 'Flaky: Mixed pass/fail across attempts', class: 'verdict-flaky' };
            if (sequence.length === 0) return { text: 'Unknown: No results', class: 'verdict-error' };
            return patternVerdict();
        })();

        // The build/test outcome stands on its own; a failed upload qualifies a
        // successful one rather than overturning it. Reported as a warning, not
        // a bug - nothing is wrong with the commit, the artifact just never
        // reached its destination.
        if (uploadFailures > 0 && verdict.class === 'verdict-success') {
            const which = uploadFailures === 1 ? 'artifact upload failed' : `${uploadFailures} artifact uploads failed`;
            return { text: `${verdict.text} \u2014 ${which}`, class: 'verdict-flaky' };
        }
        return verdict;
    }

    /**
     * Calculate test statistics from results
     * This matches the logic from report.js's calculateStatistics function
     *
     * @param {Array} resultsArray - Array of test results
     * @returns {Object} Statistics with counts
     */
    calculateTestStatistics(resultsArray, context = {}) {
        const stats = { total: 0, passed: 0, failed: 0, error: 0, flaky: 0, unstable: 0 };
        if (!resultsArray || !Array.isArray(resultsArray) || resultsArray.length === 0) {
            return stats;
        }

        // Classify each test by its own verdict, as the report page does (updateStatistics):
        // success -> passed, bug/pre-existing -> failed, unstable, error, flaky. A test that passed
        // on one commit and failed on the next is a regression (failed), not "unstable".
        const byTest = new Map();
        for (const item of resultsArray) {
            const testName = (item && item.test) || 'unknown_test';
            if (!byTest.has(testName)) byTest.set(testName, []);
            byTest.get(testName).push(item);
        }

        const commitOrder = Array.isArray(context.commitOrder) && context.commitOrder.length > 0
            ? context.commitOrder
            : Array.from(new Set(resultsArray.map(r => r && r.commit).filter(Boolean)));

        for (const testResults of byTest.values()) {
            stats.total++;
            const testCommits = new Set(testResults.map(r => r && r.commit));
            const order = commitOrder.filter(c => testCommits.has(c));
            const verdict = this.analyzeVerdict(order, testResults, order,
                context.commitBuildMode, context.runMode, context.buildOnly);
            switch (verdict.class) {
                case 'verdict-success': stats.passed++; break;
                case 'verdict-bug':
                case 'verdict-preexisting': stats.failed++; break;
                case 'verdict-unstable': stats.unstable++; break;
                case 'verdict-flaky': stats.flaky++; break;
                default: stats.error++; break;
            }
        }
        return stats;
    }
}

module.exports = new ReportService();
