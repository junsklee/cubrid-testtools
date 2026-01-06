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

    /**
     * Tail builder.log for a specific request ID
     * Bounded reads with offset cursor for real-time monitoring
     */
    async tailBuilderLog(req, res) {
        try {
            const { taskId, offset, maxBytes, knownMtimeMs } = req.query;
            const start = parseInt(offset) || 0;
            const limit = parseInt(maxBytes) || 1024 * 64; // Default 64KB per chunk
            
            if (!taskId) {
                return res.status(400).json({ status: 'invalid', message: 'taskId is required' });
            }

            // Security: validate taskId format to prevent directory traversal
            if (!/^[A-Za-z0-9_-]+$/.test(taskId)) {
                return res.status(400).json({ status: 'invalid', message: 'Invalid taskId format' });
            }

            const logPath = path.join(config.paths.requests, taskId, 'builder.log');
            
            if (!await fileService.fileExists(logPath)) {
                return res.json({ status: 'not_found', taskId, message: 'builder.log not found yet' });
            }

            const stats = await fileService.getFileStats(logPath);
            const fileSize = stats.size;
            const mtimeMs = stats.mtimeMs;

            // Short-circuit if nothing new to read
            if (start >= fileSize && knownMtimeMs && parseInt(knownMtimeMs) === Math.floor(mtimeMs)) {
                return res.json({
                    status: 'ok',
                    taskId,
                    content: '',
                    nextOffset: start,
                    fileSize,
                    mtimeMs: Math.floor(mtimeMs),
                    recommendedPollMs: 10000 // Quiet file, back off
                });
            }

            // Read the next chunk
            const actualLimit = Math.min(limit, fileSize - start);
            let content = '';
            let nextOffset = start;

            if (actualLimit > 0) {
                const result = await fileService.readFileRange(logPath, start, actualLimit);
                content = result.data;
                nextOffset = start + result.bytesRead;
            }

            res.json({
                status: 'ok',
                taskId,
                content,
                nextOffset,
                fileSize,
                mtimeMs: Math.floor(mtimeMs),
                recommendedPollMs: actualLimit > 0 ? 2000 : 5000
            });

        } catch (err) {
            console.error('Error tailing builder log:', err);
            res.status(500).json({ status: 'error', message: err.message });
        }
    }

}

module.exports = new ReportController();
