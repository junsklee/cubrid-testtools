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
router.get('/logs/:req_id/tests/:filename', (req, res) => reportController.getTestLog(req, res));

module.exports = router;
