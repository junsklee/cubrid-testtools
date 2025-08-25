/**
 * Report Service - Handles test report generation
 */

const fileService = require('./fileService');
const config = require('../config');

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
            
            // Generate HTML report
            const html = this.generateReportHTML(data, requestId);
            
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
    analyzeVerdict(commits, results) {
        let failCount = 0;
        let hasError = false;
        let hasFlaky = false;

        for (const result of results) {
            if (result.status === 'flaky') {
                hasFlaky = true;
            } else if (result.status === 'error' || result.status === 'execution_error') {
                hasError = true;
            } else if (result.status === 'fail') {
                failCount++;
            }
        }

        if (hasFlaky) {
            return { text: 'Flaky: Inconsistent results', class: 'verdict-flaky' };
        }
        
        if (hasError) {
            return { text: 'Error: Test execution failed', class: 'verdict-error' };
        }
        
        if (failCount === 0) {
            return { text: 'Pass: Not reproduced', class: 'verdict-success' };
        } else if (failCount === 1) {
            return { text: 'Bug or Revise: Caused by commit', class: 'verdict-bug' };
        } else if (failCount === commits.length) {
            return { text: 'Pre-existing Failure', class: 'verdict-preexisting' };
        } else {
            return { text: 'Unstable: Fails intermittently', class: 'verdict-unstable' };
        }
    }
}

module.exports = new ReportService();
