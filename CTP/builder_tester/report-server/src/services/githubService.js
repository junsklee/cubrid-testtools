/**
 * GitHub Service - Handles GitHub API interactions
 */

const https = require('https');
const config = require('../config');

/**
 * A commit SHA or a ref (branch names may contain "/", e.g. release/11.2), encoded as one URL
 * path segment ("/" becomes %2F, which GitHub accepts). Anything else could reach other
 * api.github.com paths with the server's token (new URL() resolves "..").
 */
function assertSafeCommitRef(sha) {
    if (typeof sha !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._\/-]{0,199}$/.test(sha)
        || sha.includes('..') || sha.includes('//') || sha.endsWith('/')) {
        const err = new Error('Invalid commit SHA');
        err.status = 400;
        throw err;
    }
    return encodeURIComponent(sha);
}

class GitHubService {
    constructor() {
        this.apiUrl = config.github.apiUrl;
        this.token = config.github.token;
        this.owner = config.github.owner;
        this.repo = config.github.repo;
    }

    /**
     * Make a request to GitHub API
     */
    async request(path, options = {}) {
        return new Promise((resolve, reject) => {
            const url = new URL(this.apiUrl + path);
            const repoPrefix = `/repos/${this.owner}/${this.repo}/`;
            if (url.origin !== new URL(this.apiUrl).origin || !url.pathname.startsWith(repoPrefix)) {
                reject(new Error('Refusing GitHub request outside ' + repoPrefix));
                return;
            }
            
            const headers = {
                'User-Agent': 'CUBRID-Builder-Tester',
                'Accept': 'application/vnd.github.v3+json',
                ...options.headers
            };

            if (this.token) {
                headers['Authorization'] = `token ${this.token}`;
            }

            const reqOptions = {
                hostname: url.hostname,
                path: url.pathname + url.search,
                method: options.method || 'GET',
                headers
            };

            const req = https.request(reqOptions, (res) => {
                let data = '';
                // Decode across chunks (as proxyService): a Korean commit message or author split
                // between two chunks became two replacement characters
                res.setEncoding('utf8');
                res.on('error', reject);
                res.on('close', () => {
                    if (!res.complete) {
                        reject(new Error('GitHub API response was cut off'));
                    }
                });

                res.on('data', (chunk) => {
                    data += chunk;
                });
                
                res.on('end', () => {
                    try {
                        const json = data ? JSON.parse(data) : null;
                        if (res.statusCode >= 200 && res.statusCode < 300) {
                            resolve({ statusCode: res.statusCode, json, headers: res.headers });
                        } else {
                            reject(new Error(`GitHub API error: ${res.statusCode} - ${json?.message || data}`));
                        }
                    } catch (err) {
                        reject(new Error(`Failed to parse GitHub response: ${err.message}`));
                    }
                });
            });

            req.on('error', reject);
            // Without a timeout a stalled api.github.com held the dashboard's request open
            req.setTimeout(30000, () => req.destroy(new Error('GitHub API request timeout')));
            req.end();
        });
    }

    /**
     * Get commits from repository
     */
    async getCommits(options = {}) {
        const {
            sha = config.github.defaultBranch,
            page = 1,
            perPage = 30
        } = options;

        const path = `/repos/${this.owner}/${this.repo}/commits?sha=${encodeURIComponent(sha)}&per_page=${perPage}&page=${page}`;
        const result = await this.request(path);
        return result.json;
    }

    /**
     * Validate a commit SHA
     */
    async validateCommit(sha) {
        try {
            const path = `/repos/${this.owner}/${this.repo}/commits/${assertSafeCommitRef(sha)}`;
            const result = await this.request(path);
            return {
                sha,
                valid: result.statusCode === 200,
                commit: result.json
            };
        } catch (err) {
            return {
                sha,
                valid: false,
                error: err.message
            };
        }
    }

    /**
     * Get commit details
     */
    async getCommitDetails(sha) {
        const path = `/repos/${this.owner}/${this.repo}/commits/${assertSafeCommitRef(sha)}`;
        const result = await this.request(path);
        return result.json;
    }
}

module.exports = new GitHubService();
module.exports.assertSafeCommitRef = assertSafeCommitRef;
