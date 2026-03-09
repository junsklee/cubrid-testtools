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

    /**
     * Get reports list as JSON with pagination and filtering
     */
    async getReportsListJson(req, res) {
        try {
            const options = {
                page: parseInt(req.query.page) || 1,
                pageSize: parseInt(req.query.pageSize) || 25,
                q: req.query.q || '',
                sort: req.query.sort || 'modified',
                order: req.query.order || 'desc',
                from: req.query.from || null,
                to: req.query.to || null,
                buildType: req.query.buildType || null,
                runMode: req.query.runMode || null
            };

            const result = await fileService.queryReports(options);

            // Add verdict analysis and test counts to each item
            for (const item of result.items) {
                // Use the same calculateTestStatistics logic as report.js
                const stats = reportService.calculateTestStatistics(item.results || []);

                item.testCounts = {
                    pass: stats.passed,
                    fail: stats.failed,
                    error: stats.error,
                    flaky: stats.flaky,
                    unstable: stats.unstable
                };

                // Still add overall verdict for compatibility
                if (item.results && item.commitOrder) {
                    const verdict = reportService.analyzeVerdict(
                        item.commits,
                        item.results,
                        item.commitOrder,
                        item.commitBuildMode,
                        item.runMode,
                        item.buildOnly
                    );
                    item.verdict = verdict;
                }
            }

            res.json(result);
        } catch (err) {
            console.error('Error getting reports list JSON:', err);
            res.status(500).json({ error: err.message });
        }
    }

    /**
     * Delete a single report
     */
    async deleteReport(req, res) {
        try {
            const { id } = req.params;

            if (!id) {
                return res.status(400).json({ error: 'Request ID is required' });
            }

            const result = await fileService.deleteRequestDir(id);

            if (result.status === 'not_found') {
                return res.status(404).json(result);
            }

            if (result.status === 'error') {
                return res.status(500).json(result);
            }

            res.json(result);
        } catch (err) {
            console.error('Error deleting report:', err);
            res.status(500).json({ error: err.message });
        }
    }

    /**
     * Bulk delete reports
     */
    async bulkDeleteReports(req, res) {
        try {
            const { mode, ids, count, from, to, dryRun } = req.body;

            if (!mode) {
                return res.status(400).json({ error: 'Delete mode is required' });
            }

            // Safety cap to prevent accidental deletion of too many reports
            const MAX_BULK_DELETE = 500;

            let targetIds = [];

            // Determine which IDs to delete based on mode
            if (mode === 'selected') {
                if (!ids || !Array.isArray(ids)) {
                    return res.status(400).json({ error: 'ids array is required for selected mode' });
                }
                targetIds = ids;
            } else if (mode === 'newest' || mode === 'oldest') {
                if (!count || count <= 0) {
                    return res.status(400).json({ error: 'count is required for newest/oldest mode' });
                }
                // Get all reports sorted
                const sortOrder = mode === 'newest' ? 'desc' : 'asc';
                const result = await fileService.queryReports({
                    page: 1,
                    pageSize: Math.min(count, MAX_BULK_DELETE),
                    sort: 'modified',
                    order: sortOrder
                });
                targetIds = result.items.map(item => item.id);
            } else if (mode === 'dateRange') {
                if (!from || !to) {
                    return res.status(400).json({ error: 'from and to dates are required for dateRange mode' });
                }
                const result = await fileService.queryReports({
                    page: 1,
                    pageSize: MAX_BULK_DELETE,
                    from,
                    to,
                    sort: 'modified',
                    order: 'desc'
                });
                targetIds = result.items.map(item => item.id);
            } else if (mode === 'keepLast') {
                if (!count || count <= 0) {
                    return res.status(400).json({ error: 'count is required for keepLast mode' });
                }
                // Get all reports
                const allResult = await fileService.queryReports({
                    page: 1,
                    pageSize: 10000, // Get all
                    sort: 'modified',
                    order: 'desc'
                });
                // Skip the first N (keep them), delete the rest
                targetIds = allResult.items.slice(count).map(item => item.id);
            } else {
                return res.status(400).json({ error: 'Invalid mode' });
            }

            // Apply safety cap
            if (targetIds.length > MAX_BULK_DELETE) {
                return res.status(400).json({
                    error: `Too many items to delete (${targetIds.length}). Maximum is ${MAX_BULK_DELETE} per request.`
                });
            }

            // If dry run, just return what would be deleted
            if (dryRun) {
                return res.json({
                    dryRun: true,
                    mode,
                    targetIds,
                    count: targetIds.length,
                    message: `Would delete ${targetIds.length} reports`
                });
            }

            // Perform actual deletion
            const deletedIds = [];
            const failed = [];

            for (const id of targetIds) {
                const result = await fileService.deleteRequestDir(id);
                if (result.status === 'deleted') {
                    deletedIds.push(id);
                } else {
                    failed.push({ id, error: result.message });
                }
            }

            res.json({
                dryRun: false,
                mode,
                deletedIds,
                failed,
                summary: {
                    deleted: deletedIds.length,
                    failed: failed.length,
                    total: targetIds.length
                }
            });
        } catch (err) {
            console.error('Error bulk deleting reports:', err);
            res.status(500).json({ error: err.message });
        }
    }

}

module.exports = new ReportController();
