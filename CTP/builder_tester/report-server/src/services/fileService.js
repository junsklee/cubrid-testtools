/**
 * File Service - Handles all file system operations
 */

const fs = require('fs').promises;
const path = require('path');
const config = require('../config');

class FileService {
    /**
     * Ensure required directories exist
     */
    async ensureDirectories() {
        const dirs = [
            config.paths.results,
            config.paths.requests,
            config.paths.logBase
        ];

        for (const dir of dirs) {
            try {
                await fs.mkdir(dir, { recursive: true });
            } catch (err) {
                console.error(`Failed to create directory ${dir}:`, err.message);
            }
        }
    }

    /**
     * Read file contents
     */
    async readFile(filePath, encoding = 'utf8') {
        try {
            return await fs.readFile(filePath, encoding);
        } catch (err) {
            throw new Error(`Failed to read file ${filePath}: ${err.message}`);
        }
    }

    /**
     * Write file contents
     */
    async writeFile(filePath, content, encoding = 'utf8') {
        try {
            await fs.writeFile(filePath, content, encoding);
        } catch (err) {
            throw new Error(`Failed to write file ${filePath}: ${err.message}`);
        }
    }

    /**
     * Check if file exists
     */
    async fileExists(filePath) {
        try {
            await fs.access(filePath);
            return true;
        } catch {
            return false;
        }
    }

    /**
     * List files in directory
     */
    async listDirectory(dirPath) {
        try {
            const files = await fs.readdir(dirPath);
            return files;
        } catch (err) {
            throw new Error(`Failed to list directory ${dirPath}: ${err.message}`);
        }
    }

    /**
     * Get file stats
     */
    async getFileStats(filePath) {
        try {
            return await fs.stat(filePath);
        } catch (err) {
            throw new Error(`Failed to get file stats ${filePath}: ${err.message}`);
        }
    }

    /**
     * Save test results
     */
    async saveTestResults(requestId, data) {
        const requestDir = path.join(config.paths.requests, requestId);
        await fs.mkdir(requestDir, { recursive: true });
        
        const resultsPath = path.join(requestDir, 'results.json');
        await this.writeFile(resultsPath, JSON.stringify(data, null, 2));
        
        return resultsPath;
    }

    /**
     * Save report HTML
     */
    async saveReport(requestId, html) {
        const requestDir = path.join(config.paths.requests, requestId);
        await fs.mkdir(requestDir, { recursive: true });
        
        const reportPath = path.join(requestDir, 'report.html');
        await this.writeFile(reportPath, html);
        
        return reportPath;
    }

    /**
     * Get report path
     */
    getReportPath(requestId) {
        return path.join(config.paths.requests, requestId, 'report.html');
    }

    /**
     * Get results path
     */
    getResultsPath(requestId) {
        return path.join(config.paths.requests, requestId, 'results.json');
    }
}

module.exports = new FileService();
