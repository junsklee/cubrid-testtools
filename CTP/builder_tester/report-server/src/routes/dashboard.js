/**
 * Dashboard Routes
 */

const express = require('express');
const router = express.Router();
const path = require('path');
const config = require('../config');
const fileService = require('../services/fileService');

// Main dashboard
router.get('/', async (req, res) => {
    try {
        // Use the modular EJS template with extracted components
        res.render('dashboard', { config });
    } catch (err) {
        console.error('Error loading dashboard:', err);
        res.status(500).send('Error loading dashboard');
    }
});

// Dashboard report view (from original integrated server)
router.get('/dashboard/report', async (req, res) => {
    try {
        const { id } = req.query;
        
        if (!id) {
            return res.status(400).send('<h1>Report ID is required</h1>');
        }
        
        // This loads the same report functionality as /report but from dashboard context
        const reportPath = fileService.getReportPath(id);
        const reportHtml = await fileService.readFile(reportPath);
        
        res.send(reportHtml);
    } catch (err) {
        console.error('Error viewing dashboard report:', err);
        res.status(404).send('<h1>Report not found</h1>');
    }
});

// Dashboard scripts
router.get('/ui/overrides.js', (req, res) => {
    res.type('application/javascript');
    res.render('overrides', { config });
});

router.get('/ui/reports.js', (req, res) => {
    // Serve the reports management JavaScript file
    res.type('application/javascript');
    res.sendFile(path.join(config.paths.publicDir, 'js', 'reports.js'));
});

module.exports = router;
