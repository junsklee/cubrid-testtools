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
            // Prefer taskId (legacy behavior) then requestId; else generate
            const requestId = req.body.taskId || req.body.requestId || `req_${Date.now()}`;

            // Normalize payload so embedded report data matches the directory name
            const normalizedData = Object.assign({}, req.body, {
                requestId: requestId,
                taskId: req.body.taskId || requestId
            });

            const result = await reportService.processTestResults(requestId, normalizedData);
            
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
            const config = require('../config');
            
            // Use EJS template for consistent styling
            res.render('reports', { 
                reports,
                config,
                title: 'Test Reports'
            });
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
     * List test logs for a request
     */
    async listTestLogs(req, res) {
        try {
            const { req_id } = req.params;
            const logsPath = path.join(config.paths.requests, req_id, 'tests');
            
            if (!await fileService.fileExists(logsPath)) {
                return res.status(404).json([]);
            }
            
            const files = await fileService.listDirectory(logsPath);
            const logFiles = files.filter(file => file.endsWith('.log'));
            
            // Return array for compatibility with integrated report template
            res.json(logFiles);
        } catch (err) {
            console.error('Error listing test logs:', err);
            res.status(500).json([]);
        }
    }

    /**
     * List build logs for a request
     */
    async listBuildLogs(req, res) {
        try {
            const { req_id } = req.params;
            const logsPath = path.join(config.paths.requests, req_id, 'builds');
            
            if (!await fileService.fileExists(logsPath)) {
                return res.status(404).json([]);
            }
            
            const files = await fileService.listDirectory(logsPath);
            const logFiles = files.filter(file => file.endsWith('.log'));
            
            // Return array for compatibility with integrated report template
            res.json(logFiles);
        } catch (err) {
            console.error('Error listing build logs:', err);
            res.status(500).json([]);
        }
    }

    /**
     * Get root log file (builder.log, tester.log)
     */
    async getRootLog(req, res) {
        try {
            const { req_id, filename } = req.params;
            
            if (!req_id || !filename) {
                return res.status(400).json({ error: 'Request ID and filename are required' });
            }
            
            const logPath = path.join(config.paths.requests, req_id, filename);
            const logContent = await fileService.readFile(logPath);
            
            res.type('text/plain').send(logContent);
        } catch (err) {
            console.error('Error getting root log:', err);
            res.status(404).send('Log file not found');
        }
    }

    /**
     * Get specific build log content
     */
    async getBuildLog(req, res) {
        try {
            const { req_id, filename } = req.params;
            
            if (!req_id || !filename) {
                return res.status(400).json({ error: 'Request ID and filename are required' });
            }
            
            const logPath = path.join(config.paths.requests, req_id, 'builds', filename);
            const logContent = await fileService.readFile(logPath);
            
            res.type('text/plain').send(logContent);
        } catch (err) {
            console.error('Error getting build log:', err);
            res.status(404).send('Log file not found');
        }
    }

}

module.exports = new ReportController();
