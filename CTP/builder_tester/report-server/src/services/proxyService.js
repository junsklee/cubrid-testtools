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
                // Decode across chunks: a multi-byte character split between two chunks
                // (Korean text) became two replacement characters with per-chunk decoding
                res.setEncoding('utf8');
                // A connection dropped mid-body (a Builder restart) emitted neither 'end' nor a
                // request error: the call never settled and the browser waited for server.timeout
                res.on('error', reject);
                res.on('close', () => {
                    if (!res.complete) {
                        reject(new Error('Response was cut off'));
                    }
                });

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

}

module.exports = new ProxyService();
