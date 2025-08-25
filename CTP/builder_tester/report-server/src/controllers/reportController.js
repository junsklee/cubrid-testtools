/**
 * Report Controller - Handles report generation and viewing
 */

const reportService = require('../services/reportService');
const fileService = require('../services/fileService');
const config = require('../config');
const path = require('path');

class ReportController {
    /**
     * Handle callback from test execution
     */
    async handleCallback(req, res) {
        try {
            const requestId = req.body.requestId || `req_${Date.now()}`;
            const result = await reportService.processTestResults(requestId, req.body);
            
            res.json({
                success: true,
                requestId,
                message: 'Report generated successfully',
                reportUrl: `/report?id=${requestId}`
            });
        } catch (err) {
            console.error('Error handling callback:', err);
            res.status(500).json({ error: err.message });
        }
    }

    /**
     * Get list of reports
     */
    async getReportsList(req, res) {
        try {
            const reports = await reportService.getReportsList();
            
            // Generate HTML list
            const html = this.generateReportsListHTML(reports);
            res.send(html);
        } catch (err) {
            console.error('Error getting reports list:', err);
            res.status(500).send('<h1>Error loading reports</h1>');
        }
    }

    /**
     * View specific report
     */
    async viewReport(req, res) {
        try {
            const { id } = req.query;
            
            if (!id) {
                return res.status(400).send('<h1>Report ID is required</h1>');
            }
            
            const reportPath = fileService.getReportPath(id);
            const reportHtml = await fileService.readFile(reportPath);
            
            res.send(reportHtml);
        } catch (err) {
            console.error('Error viewing report:', err);
            res.status(404).send('<h1>Report not found</h1>');
        }
    }

    /**
     * Get test execution log
     */
    async getTestLog(req, res) {
        try {
            const { req_id, filename } = req.params;
            
            if (!req_id || !filename) {
                return res.status(400).json({ error: 'Request ID and filename are required' });
            }
            
            const logPath = path.join(config.paths.requests, req_id, 'tests', filename);
            const logContent = await fileService.readFile(logPath);
            
            res.type('text/plain').send(logContent);
        } catch (err) {
            console.error('Error getting test log:', err);
            res.status(404).send('Log file not found');
        }
    }

    /**
     * Generate reports list HTML
     */
    generateReportsListHTML(reports) {
        const reportItems = reports.map(report => {
            return `<li><a href="/report?id=${report.id}">${report.id} - ${report.modified.toISOString()}</a></li>`;
        }).join('\n');
        
        return `<!DOCTYPE html>
<html>
<head>
    <title>Test Reports</title>
    <link rel="stylesheet" href="/css/reports.css">
</head>
<body>
    <div class="container">
        <h1>Test Reports</h1>
        <ul>${reportItems}</ul>
    </div>
</body>
</html>`;
    }
}

module.exports = new ReportController();
