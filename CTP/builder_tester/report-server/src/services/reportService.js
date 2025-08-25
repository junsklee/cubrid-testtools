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
            
            // Save HTML report            return { text: 'Flaky: Inconsistent results', class: 'verdict-flaky' };
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
