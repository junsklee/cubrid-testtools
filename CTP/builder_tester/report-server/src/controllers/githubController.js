/**
 * GitHub Controller - Handles GitHub API endpoints
 */

const githubService = require('../services/githubService');

class GitHubController {
    /**
     * Get commits from GitHub
     */
    async getCommits(req, res) {
        try {
            const { page = 1, per_page = 30, sha = 'develop' } = req.query;
            
            const commits = await githubService.getCommits({
                sha,
                page: parseInt(page),
                perPage: parseInt(per_page)
            });
            
            res.json(commits);
        } catch (err) {
            console.error('Error fetching commits:', err);
            res.status(500).json({ error: err.message });
        }
    }

    /**
     * Validate a commit SHA
     */
    async validateCommit(req, res) {
        try {
            const { sha } = req.params;
            
            if (!sha) {
                return res.status(400).json({ error: 'SHA is required' });
            }
            
            const result = await githubService.validateCommit(sha);
            res.json(result);
        } catch (err) {
            console.error('Error validating commit:', err);
            res.status(500).json({ error: err.message });
        }
    }

    /**
     * Get commit details
     */
    async getCommitDetails(req, res) {
        try {
            const { sha } = req.params;
            
            if (!sha) {
                return res.status(400).json({ error: 'SHA is required' });
            }
            
            const commit = await githubService.getCommitDetails(sha);
            res.json(commit);
        } catch (err) {
            console.error('Error fetching commit details:', err);
            res.status(500).json({ error: err.message });
        }
    }
}

module.exports = new GitHubController();
