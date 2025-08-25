/**
 * GitHub Service - Handles GitHub API interactions
 */

const https = require('https');
const config = require('../config');

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

        const path = `/repos/${this.owner}/${this.repo}/commits?sha=${sha}&per_page=${perPage}&page=${page}`;
        const result = await this.request(path);
        return result.json;
    }

    /**
     * Validate a commit SHA
     */
    async validateCommit(sha) {
        try {
            const path = `/repos/${this.owner}/${this.repo}/commits/${sha}`;
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
        const path = `/repos/${this.owner}/${this.repo}/commits/${sha}`;
        const result = await this.request(path);
        return result.json;
    }
}

module.exports = new GitHubService();
