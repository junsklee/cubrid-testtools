/**
 * Tester Controller - Handles Tester health endpoints
 */

const proxyService = require('../services/proxyService');

class TesterController {
    /**
     * Get Tester health status
     */
    async getHealth(req, res) {
        try {
            const { ip } = req.query;
            
            if (!ip) {
                return res.status(400).json({ error: 'Tester IP is required' });
            }
            
            // Default to port 8090 for tester if no port specified
            let testerUrl;
            if (ip.startsWith('http://') || ip.startsWith('https://')) {
                testerUrl = `${ip}/health`;
            } else if (ip.includes(':')) {
                testerUrl = `http://${ip}/health`;
            } else {
                testerUrl = `http://${ip}:8090/health`;
            }
            
            const result = await proxyService.requestJson(testerUrl);
            res.status(result.statusCode).json(result.json);
        } catch (err) {
            console.error('Error getting tester health:', err);
            res.status(500).json({ error: err.message });
        }
    }
}

module.exports = new TesterController();
