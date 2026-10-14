/**
 * CORS and cross-site write protection
 */

const config = require('../config');

const STATE_CHANGING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function allowedOrigins() {
    return String(config.security.corsOrigin || '')
        .split(',')
        .map(o => o.trim())
        .filter(Boolean);
}

/**
 * Origin a browser request came from, or null for non-browser clients (the Builder's
 * callback, curl, scripts), which send neither Origin nor Referer.
 */
function requestOrigin(req) {
    const origin = req.get('Origin');
    if (origin) return origin;
    const referer = req.get('Referer');
    if (!referer) return null;
    try {
        return new URL(referer).origin;
    } catch (e) {
        return 'invalid';
    }
}

function isSameOrigin(req, origin) {
    try {
        return new URL(origin).host === req.get('Host');
    } catch (e) {
        return false;
    }
}

function corsMiddleware(req, res, next) {
    const origins = allowedOrigins();
    const origin = req.get('Origin');

    // The report, delete and build APIs have no authentication, so a browser may only change
    // state from this server's own pages or an origin listed explicitly in CORS_ORIGIN.
    // "*" keeps cross-origin reads working but never authorizes writes.
    if (STATE_CHANGING.has(req.method)) {
        const from = requestOrigin(req);
        if (from && !isSameOrigin(req, from) && !origins.includes(from)) {
            return res.status(403).json({ error: 'Cross-origin request refused' });
        }
    }

    if (config.security.enableCors && origin) {
        if (origins.includes('*')) {
            res.setHeader('Access-Control-Allow-Origin', '*');
        } else if (origins.includes(origin)) {
            res.setHeader('Access-Control-Allow-Origin', origin);
            res.setHeader('Vary', 'Origin');
        }
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    }

    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
    }

    next();
}

module.exports = corsMiddleware;
