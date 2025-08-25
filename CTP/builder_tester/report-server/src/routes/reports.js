/**
 * Report Routes
 */

const express = require('express');
const router = express.Router();
const reportController = require('../controllers/reportController');

// Report routes
router.post('/callback', (req, res) => reportController.handleCallback(req, res));
router.get('/reports', (req, res) => reportController.getReportsList(req, res));
router.get('/report', (req, res) => reportController.viewReport(req, res));

// Log endpoints to match original server
router.get('/logs/:req_id/tests/:filename', (req, res) => reportController.getTestLog(req, res));
router.get('/api/log/:req_id/tests/:filename', (req, res) => reportController.getTestLog(req, res));
router.get('/api/logs/:req_id/tests', (req, res) => reportController.listTestLogs(req, res));
router.get('/api/logs/:req_id/builds', (req, res) => reportController.listBuildLogs(req, res));
router.get('/api/log-root/:req_id/:filename', (req, res) => reportController.getRootLog(req, res));

module.exports = router;
