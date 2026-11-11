/**
 * API Routes
 */

const express = require('express');
const router = express.Router();
const githubController = require('../controllers/githubController');
const builderController = require('../controllers/builderController');
const testerController = require('../controllers/testerController');
const reportController = require('../controllers/reportController');
const officialBuildService = require('../services/officialBuildService');
const config = require('../config');

// GitHub API routes
router.get('/github/commits', (req, res) => githubController.getCommits(req, res));
router.get('/github/validate/:sha', (req, res) => githubController.validateCommit(req, res));
router.get('/github/commit/:sha', (req, res) => githubController.getCommitDetails(req, res));

// Builder API routes
router.post('/builder/build', (req, res) => builderController.submitBuild(req, res));
router.post('/builder/build/pr', (req, res) => builderController.submitPrBuild(req, res));
router.get('/builder/status', (req, res) => builderController.getBuildStatus(req, res));
router.post('/builder/queue/remove', (req, res) => builderController.removeFromQueue(req, res));
router.get('/builder/health', (req, res) => builderController.getHealth(req, res));

// Official CUBRID builds of the build store (newest first), for testing without building
router.get('/official-builds', async (req, res) => {
    try {
        res.json(await officialBuildService.list({ refresh: req.query.refresh === '1' }));
    } catch (err) {
        console.error('Error listing official builds:', err);
        res.status(502).json({ error: err.message });
    }
});

// Tester API routes
router.get('/tester/health', (req, res) => testerController.getHealth(req, res));

// Report management API routes
router.get('/reports', (req, res) => reportController.getReportsListJson(req, res));
router.delete('/reports/:id', (req, res) => reportController.deleteReport(req, res));
router.post('/reports/bulk-delete', (req, res) => reportController.bulkDeleteReports(req, res));

// Local info route
router.get('/local-ip', (req, res) => {
    res.json({
        ip: config.server.localIp,
        hostname: config.server.hostname
    });
});

module.exports = router;
