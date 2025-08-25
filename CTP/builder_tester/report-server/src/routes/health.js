/**
 * Health Check Routes
 */

const express = require('express');
const router = express.Router();
const config = require('../config');

// Health check endpoint
router.get('/health', (req, res) => {
    res.json({
        status: 'healthy',
        service: 'report-server',
        version: config.app.version,
        environment: config.app.environment,
        uptime: process.uptime()
    });
});

module.exports = router;
