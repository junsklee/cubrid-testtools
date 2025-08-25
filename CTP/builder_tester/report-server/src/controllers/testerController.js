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
            
            const testerUrl = ip.startsWith('http://') || ip.startsWith('https://') 
                ? `${ip}/health`
                : `http://${ip}/health`;
            
            const result = await proxyService.requestJson(testerUrl);
            res.status(result.statusCode).json(result.json);
        } catch (err) {
            console.error('Error getting tester health:', err);
            res.status(500).json({ error: err.message });
        }
    }
}

module.exports = new TesterController();
