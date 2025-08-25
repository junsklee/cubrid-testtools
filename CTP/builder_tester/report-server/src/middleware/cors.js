/**
 * CORS Middleware
 */

const config = require('../config');

function corsMiddleware(req, res, next) {
    if (config.security.enableCors) {
        res.setHeader('Access-Control-Allow-Origin', config.security.corsOrigin);
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
        
        if (req.method === 'OPTIONS') {
            res.writeHead(200);
            res.end();
            return;
        }
    }
    
    next();
}

module.exports = corsMiddleware;
