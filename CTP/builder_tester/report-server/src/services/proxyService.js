/**
 * Proxy Service - Handles HTTP proxy operations
 */

const http = require('http');
const https = require('https');

class ProxyService {
    /**
     * Make HTTP/HTTPS request
     */
    async request(url, options = {}) {
        return new Promise((resolve, reject) => {
            const parsedUrl = new URL(url);
            const client = parsedUrl.protocol === 'https:' ? https : http;
            
            const reqOptions = {
                hostname: parsedUrl.hostname,
                port: parsedUrl.port,
                path: parsedUrl.pathname + parsedUrl.search,
                method: options.method || 'GET',
                headers: options.headers || {},
                timeout: options.timeout || 30000
            };

            if (options.body) {
                reqOptions.headers['Content-Length'] = Buffer.byteLength(options.body);
            }

            const req = client.request(reqOptions, (res) => {
                let data = '';
                
                res.on('data', (chunk) => {
                    data += chunk;
                });
                
                res.on('end', () => {
                    resolve({
                        statusCode: res.statusCode,
                        headers: res.headers,
                        data,
                        json: null
                    });
                });
            });

            req.on('error', reject);
            req.on('timeout', () => {
                req.destroy();
                reject(new Error('Request timeout'));
            });

            if (options.body) {
                req.write(options.body);
            }
            
            req.end();
        });
    }

    /**
     * Make JSON request
     */
    async requestJson(url, options = {}) {
        const result = await this.request(url, {
            ...options,
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json',
                ...options.headers
            }
        });

        try {
            result.json = result.data ? JSON.parse(result.data) : null;
        } catch (err) {
            throw new Error(`Failed to parse JSON response: ${err.message}`);
        }

        return result;
    }

    /**
     * Proxy request to another server
     */
    proxyRequest(targetUrl, req, res) {
        const parsedUrl = new URL(targetUrl);
        const client = parsedUrl.protocol === 'https:' ? https : http;
        
        const options = {
            hostname: parsedUrl.hostname,
            port: parsedUrl.port,
            path: parsedUrl.pathname + parsedUrl.search,
            method: req.method,
            headers: req.headers
        };

        const proxyReq = client.request(options, (proxyRes) => {
            res.writeHead(proxyRes.statusCode, proxyRes.headers);
            proxyRes.pipe(res);
        });

        proxyReq.on('error', (err) => {
            console.error('Proxy error:', err);
            res.writeHead(502);
            res.end('Bad Gateway');
        });

        req.pipe(proxyReq);
    }
}

module.exports = new ProxyService();
