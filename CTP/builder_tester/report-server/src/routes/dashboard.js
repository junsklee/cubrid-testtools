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
        // Try to load the modern UI template
        const tobeHtmlPath = config.paths.tobeHtml;
        if (await fileService.fileExists(tobeHtmlPath)) {
            let html = await fileService.readFile(tobeHtmlPath);
            
            // Inject configuration
            const configScript = `
<script>
    window.SERVER_CONFIG = {
        builderHost: '${config.builder.host}',
        builderPort: ${config.builder.port},
        reportPort: ${config.server.port},
        localIp: '${config.server.localIp}'
    };
</script>`;
            
            html = html.replace('</head>', `${configScript}</head>`);
            res.send(html);
        } else {
            // Fallback to basic dashboard
            res.render('dashboard', { config });
        }
    } catch (err) {
        console.error('Error loading dashboard:', err);
        res.status(500).send('Error loading dashboard');
    }
});

// Dashboard scripts
router.get('/ui/overrides.js', (req, res) => {
    res.type('application/javascript');
    res.render('overrides', { config });
});

router.get('/ui/reports.js', (req, res) => {
    res.type('application/javascript');
    res.render('reports', { config });
});

module.exports = router;
