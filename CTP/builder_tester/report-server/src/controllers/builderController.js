/**
 * Builder Controller - Handles Builder proxy endpoints
 */

const proxyService = require('../services/proxyService');
const config = require('../config');

class BuilderController {
    /**
     * Submit build request to Builder
     */
    async submitBuild(req, res) {
        try {
            const builderUrl = `${config.builder.protocol}://${config.builder.host}:${config.builder.port}/build`;
            
            const result = await proxyService.requestJson(builderUrl, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(req.body)
            });
            
            res.status(result.statusCode).json(result.json);
        } catch (err) {
            console.error('Error submitting build:', err);
            res.status(500).json({ error: err.message });
        }
    }

    /**
     * Submit PR build request to Builder (accepts { prNumber, tests, ... })
     */
    async submitPrBuild(req, res) {
        try {
            const builderUrl = `${config.builder.protocol}://${config.builder.host}:${config.builder.port}/build`;

            // Construct body ensuring prNumber is an integer or numeric string
            const body = { ...req.body };
            if (!('prNumber' in body)) {
                return res.status(400).json({ error: 'Missing prNumber' });
            }
            // Ensure commits array is omitted to avoid ambiguity
            if ('commits' in body && Array.isArray(body.commits) && body.commits.length > 0) {
                delete body.commits;
            }

            const result = await proxyService.requestJson(builderUrl, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            res.status(result.statusCode).json(result.json);
        } catch (err) {
            console.error('Error submitting PR build:', err);
            res.status(500).json({ error: err.message });
        }
    }

    /**
     * Get build status from Builder
     */
    async getBuildStatus(req, res) {
        try {
            const { taskId } = req.query;
            
            // Build URL with optional taskId parameter
            let builderUrl = `${config.builder.protocol}://${config.builder.host}:${config.builder.port}/status`;
            if (taskId) {
                builderUrl += `?taskId=${taskId}`;
            }
            
            const result = await proxyService.requestJson(builderUrl);
            res.status(result.statusCode).json(result.json);
        } catch (err) {
            console.error('Error getting build status:', err);
            res.status(500).json({ error: err.message });
        }
    }

    /**
     * Remove a build request from queue or cancel a running request
     */
    async removeFromQueue(req, res) {
        try {
            const taskId = req.body && req.body.taskId ? String(req.body.taskId).trim() : '';
            if (!taskId) {
                return res.status(400).json({ error: 'Missing taskId' });
            }

            const builderUrl = `${config.builder.protocol}://${config.builder.host}:${config.builder.port}/queue/remove`;
            const result = await proxyService.requestJson(builderUrl, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ taskId })
            });

            res.status(result.statusCode).json(result.json);
        } catch (err) {
            console.error('Error removing build request:', err);
            res.status(500).json({ error: err.message });
        }
    }

    /**
     * Get Builder health status
     */
    async getHealth(req, res) {
        try {
            const builderUrl = `${config.builder.protocol}://${config.builder.host}:${config.builder.port}/health`;
            
            const result = await proxyService.requestJson(builderUrl);
            res.status(result.statusCode).json(result.json);
        } catch (err) {
            console.error('Error getting builder health:', err);
            res.status(500).json({ error: err.message });
        }
    }

    /**
     * Proxy any other Builder endpoint
     */
    async proxy(req, res) {
        try {
            const path = req.path.replace('/api/builder', '');
            const builderUrl = `${config.builder.protocol}://${config.builder.host}:${config.builder.port}${path}`;
            
            proxyService.proxyRequest(builderUrl, req, res);
        } catch (err) {
            console.error('Error proxying request:', err);
            res.status(500).json({ error: err.message });
        }
    }
}

module.exports = new BuilderController();
