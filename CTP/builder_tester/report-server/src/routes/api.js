/**
 * API Routes
 */

const express = require('express');
const router = express.Router();
const githubController = require('../controllers/githubController');
const builderController = require('../controllers/builderController');
const testerController = require('../controllers/testerController');
const config = require('../config');

// GitHub API routes
router.get('/github/commits', (req, res) => githubController.getCommits(req, res));
router.get('/github/validate/:sha', (req, res) => githubController.validateCommit(req, res));
router.get('/github/commit/:sha', (req, res) => githubController.getCommitDetails(req, res));

// Builder API routes
router.post('/builder/build', (req, res) => builderController.submitBuild(req, res));
router.post('/builder/build/pr', (req, res) => builderController.submitPrBuild(req, res));
router.get('/builder/status', (req, res) => builderController.getBuildStatus(req, res));
router.get('/builder/health', (req, res) => builderController.getHealth(req, res));
router.all('/builder/*', (req, res) => builderController.proxy(req, res));

// Tester API routes
router.get('/tester/health', (req, res) => testerController.getHealth(req, res));

// Local info route
router.get('/local-ip', (req, res) => {
    res.json({
        ip: config.server.localIp,
        hostname: config.server.hostname
    });
});

module.exports = router;
