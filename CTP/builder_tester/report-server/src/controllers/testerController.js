/**
 * Tester Controller - Handles Tester health endpoints
 */

const proxyService = require('../services/proxyService');

class TesterController {
    /**
     * Build the /health URL for a tester given as host, host:port or http(s)://host[:port].
     * Only the scheme, host and port come from the caller (default port 8090); the path is
     * always /health, so the endpoint cannot be pointed at other URLs. Returns null if invalid.
     */
    static healthUrl(ip) {
        const raw = String(ip).trim();
        const withScheme = /^https?:\/\//i.test(raw) ? raw : `http://${raw}`;
        let url;
        try {
            url = new URL(withScheme);
        } catch (e) {
            return null;
        }
        if (!/^https?:$/.test(url.protocol) || url.username || url.password
            || (url.pathname && url.pathname !== '/') || url.search || url.hash
            || !/^[A-Za-z0-9.\-\[\]:]+$/.test(url.hostname)) {
            return null;
        }
        const port = url.port || (/^https?:\/\//i.test(raw) ? '' : '8090');
        return `${url.protocol}//${url.hostname}${port ? ':' + port : ''}/health`;
    }

    /**
     * Get Tester health status
     */
    async getHealth(req, res) {
        try {
            const { ip } = req.query;
            
            if (!ip) {
                return res.status(400).json({ error: 'Tester IP is required' });
            }
            
            const testerUrl = TesterController.healthUrl(ip);
            if (!testerUrl) {
                return res.status(400).json({ error: 'ip must be host, host:port or http(s)://host[:port]' });
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
