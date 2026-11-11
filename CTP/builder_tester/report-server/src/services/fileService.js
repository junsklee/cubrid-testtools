/**
 * File Service - Handles all file system operations
 */

const fs = require('fs').promises;
const path = require('path');
const config = require('../config');

/**
 * Length of buf[0..n) without a trailing incomplete UTF-8 sequence: n when the last character is
 * whole, else the index of its first byte (never 0, so a reader always moves on).
 */
function completeUtf8Length(buf, n) {
    for (let i = n - 1; i >= 0 && i >= n - 4; i--) {
        const b = buf[i];
        if ((b & 0xC0) === 0x80) {
            continue;  // continuation byte: keep looking for the lead byte
        }
        const need = b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC0 ? 2 : 1;
        return (n - i < need && i > 0) ? i : n;
    }
    return n;
}

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
            // Keep the code: callers answer a missing file (ENOENT) with 404, anything else with 500
            const wrapped = new Error(`Failed to read file ${filePath}: ${err.message}`);
            wrapped.code = err.code;
            throw wrapped;
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
     * Read a specific byte range from a file
     * @param {string} filePath Path to the file
     * @param {number} start Start offset (bytes)
     * @param {number} length Number of bytes to read
     * @returns {Promise<{data: string, bytesRead: number}>}
     */
    async readFileRange(filePath, start, length) {
        let fileHandle;
        try {
            fileHandle = await fs.open(filePath, 'r');
            const buffer = Buffer.alloc(length);
            const { bytesRead: read } = await fileHandle.read(buffer, 0, length, start);
            // A range ending inside a multi-byte character decoded it as replacement characters
            // here and again at the start of the next range: end at the character's first byte
            // instead (bytesRead is what the caller advances its offset by)
            const bytesRead = read === length ? completeUtf8Length(buffer, read) : read;

            // Convert to string, assuming UTF-8 for log files
            return {
                data: buffer.toString('utf8', 0, bytesRead),
                bytesRead: bytesRead
            };
        } catch (err) {
            throw new Error(`Failed to read file range ${filePath}: ${err.message}`);
        } finally {
            if (fileHandle) {
                await fileHandle.close();
            }
        }
    }

    /**
     * Request IDs come from unauthenticated callbacks and URLs and become directory names.
     * Same rule as the Java RequestIds class: one path segment, no "..", not hidden.
     */
    isValidRequestId(id) {
        return typeof id === 'string'
            && /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/.test(id)
            && !id.includes('..');
    }

    /** A single file name inside a request directory (no separators, not hidden). */
    isPlainFileName(name) {
        return typeof name === 'string' && name.length > 0 && name.length <= 255
            && !name.startsWith('.') && !/[\\/\0]/.test(name);
    }

    /**
     * Resolve a path inside log/requests/<id>/. Throws if the ID or any segment is unsafe.
     * Every route that turns a request ID or file name into a path must go through here.
     */
    requestPath(requestId, ...segments) {
        if (!this.isValidRequestId(requestId)) {
            const err = new Error('Invalid request ID');
            err.status = 400;
            throw err;
        }
        for (const segment of segments) {
            if (!this.isPlainFileName(segment)) {
                const err = new Error('Invalid file name');
                err.status = 400;
                throw err;
            }
        }
        const root = path.resolve(config.paths.requests);
        const target = path.resolve(root, requestId, ...segments);
        if (!target.startsWith(root + path.sep)) {
            const err = new Error('Invalid request path');
            err.status = 400;
            throw err;
        }
        return target;
    }

    /**
     * Save test results
     */
    async saveTestResults(requestId, data) {
        const requestDir = this.requestPath(requestId);
        await fs.mkdir(requestDir, { recursive: true });
        
        const resultsPath = path.join(requestDir, 'results.json');
        await this.writeFile(resultsPath, JSON.stringify(data, null, 2));
        
        return resultsPath;
    }

    /**
     * Save report HTML
     */
    async saveReport(requestId, html) {
        const requestDir = this.requestPath(requestId);
        await fs.mkdir(requestDir, { recursive: true });
        
        const reportPath = path.join(requestDir, 'report.html');
        await this.writeFile(reportPath, html);
        
        return reportPath;
    }

    /**
     * Get report path
     */
    getReportPath(requestId) {
        return this.requestPath(requestId, 'report.html');
    }

    /**
     * Get results path
     */
    getResultsPath(requestId) {
        return this.requestPath(requestId, 'results.json');
    }

    /**
     * Get list of available reports
     */
    async getReportsList() {
        try {
            const requestsDir = config.paths.requests;
            const dirs = await this.listDirectory(requestsDir);
            const reports = [];

            for (const dir of dirs) {
                if (!dir.startsWith('req_')) continue;

                const reportPath = path.join(requestsDir, dir, 'report.html');
                if (await this.fileExists(reportPath)) {
                    const stats = await this.getFileStats(reportPath);
                    reports.push({
                        id: dir,
                        modified: stats.mtime
                    });
                }
            }

            // Sort by modification time (newest first)
            reports.sort((a, b) => b.modified - a.modified);
            return reports;
        } catch (err) {
            console.error('Error getting reports list:', err);
            return [];
        }
    }

    /**
     * Where a request's tests ran: 'docker', 'vm', 'direct', 'mixed', or null when no test ran
     * (build-only or cancelled requests).
     *
     * results.json does not record the tester's execution mode, but every executor names its
     * attempt logs with its own prefix (exec/*Executor.java in the tester), so the mode is read
     * from those names; this also covers reports written before VM mode existed.
     * @param {Object} resultsData - parsed results.json
     * @param {Object} requestData - parsed request.json
     * @returns {string|null}
     */
    detectExecutionMode(resultsData, requestData) {
        const explicit = (resultsData && resultsData.executionMode) || (requestData && requestData.executionMode);
        if (explicit) return String(explicit).toLowerCase();

        const prefixModes = [
            ['vm_', 'vm'],
            ['docker_opt_', 'docker'],
            ['docker_', 'docker'],
            ['sql_', 'docker'],
            ['direct_', 'direct']
        ];
        const modes = new Set();
        const results = resultsData && Array.isArray(resultsData.results) ? resultsData.results : [];
        for (const r of results) {
            const metadata = r && Array.isArray(r.attemptLogMetadata) ? r.attemptLogMetadata : [];
            for (const m of metadata) {
                if (!m || m.artifactType || typeof m.logFileName !== 'string') continue;
                const hit = prefixModes.find(([prefix]) => m.logFileName.startsWith(prefix));
                if (hit) modes.add(hit[1]);
            }
        }
        if (modes.size === 0) return null;
        return modes.size === 1 ? [...modes][0] : 'mixed';
    }

    /**
     * Get enriched report row data for a single report
     * @param {string} id - Request ID
     * @param {boolean} enrichData - Whether to enrich with results.json data
     * @returns {Promise<Object|null>} Report row or null if not found
     */
    async getReportRow(id, enrichData = true) {
        try {
            const requestsDir = config.paths.requests;
            const reportPath = path.join(requestsDir, id, 'report.html');

            if (!await this.fileExists(reportPath)) {
                return null;
            }

            const stats = await this.getFileStats(reportPath);
            const row = {
                id,
                modified: stats.mtime,
                modifiedMs: stats.mtimeMs
            };

            if (!enrichData) {
                return row;
            }

            // Enrich with results.json and request.json data
            try {
                const resultsPath = path.join(requestsDir, id, 'results.json');
                const requestPath = path.join(requestsDir, id, 'request.json');

                let resultsData = {};
                let requestData = {};

                // Read results.json
                if (await this.fileExists(resultsPath)) {
                    resultsData = JSON.parse(await this.readFile(resultsPath));
                }

                // Read request.json (may have metadata not in results.json)
                if (await this.fileExists(requestPath)) {
                    requestData = JSON.parse(await this.readFile(requestPath));
                }

                // Merge data, preferring results.json but falling back to request.json
                row.buildType = resultsData.buildType || requestData.buildType || null;
                row.runMode = resultsData.runMode || requestData.runMode || null;
                row.minRuns = resultsData.minRuns || requestData.minRuns || null;
                row.maxRuns = resultsData.maxRuns || requestData.maxRuns || null;
                row.commitBuildMode = resultsData.commitBuildMode || requestData.commitBuildMode || null;
                row.buildOnly = resultsData.buildOnly || resultsData.buildOnlyMode || requestData.buildOnly || false;
                row.testType = resultsData.testType || requestData.testType || null;

                // Get commits - try multiple sources
                row.commits = resultsData.commits || resultsData.commitOrder || requestData.commits || requestData.commitOrder || [];

                // If still no commits, extract from results array
                if (row.commits.length === 0 && resultsData.results && Array.isArray(resultsData.results)) {
                    const uniqueCommits = [...new Set(resultsData.results.map(r => r.commit).filter(Boolean))];
                    row.commits = uniqueCommits;
                }

                row.executionMode = this.detectExecutionMode(resultsData, requestData);

                row.commitCount = Array.isArray(row.commits) ? row.commits.length : 0;

                // Get first and last commit short SHAs
                if (row.commitCount > 0) {
                    row.firstCommit = row.commits[0] ? row.commits[0].substring(0, 7) : null;
                    row.lastCommit = row.commits[row.commitCount - 1] ? row.commits[row.commitCount - 1].substring(0, 7) : null;
                }

                // Add raw results for verdict analysis
                row.results = resultsData.results || [];
                // Error callback: the request failed before producing results
                row.requestError = (resultsData.status === 'error' && row.results.length === 0)
                    ? (resultsData.message || 'Request failed') : null;
                row.commitOrder = resultsData.commitOrder || resultsData.commits || requestData.commitOrder || requestData.commits || row.commits;
            } catch (enrichErr) {
                console.warn(`Could not enrich report ${id}:`, enrichErr.message);
            }

            // Check for logs
            try {
                const testsLogsPath = path.join(requestsDir, id, 'tests');
                const buildsLogsPath = path.join(requestsDir, id, 'builds');

                row.hasTestsLogs = await this.fileExists(testsLogsPath);
                row.hasBuildsLogs = await this.fileExists(buildsLogsPath);
            } catch (logsErr) {
                row.hasTestsLogs = false;
                row.hasBuildsLogs = false;
            }

            return row;
        } catch (err) {
            console.error(`Error getting report row for ${id}:`, err);
            return null;
        }
    }

    /**
     * Query reports with pagination and filtering
     * @param {Object} options - Query options
     * @returns {Promise<Object>} Paginated results with items and metadata
     */
    /**
     * A date from <input type="date"> ("YYYY-MM-DD") means that whole local day: new Date() reads it
     * as UTC midnight, which shifted the window by the server's UTC offset (9h in KST) and left
     * the "To" day out (from == to matched nothing). Other values are parsed as given.
     */
    parseDayBoundary(value, endOfDay) {
        const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value).trim());
        if (!m) {
            return new Date(value);
        }
        const day = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
        if (endOfDay) {
            day.setHours(23, 59, 59, 999);
        }
        return day;
    }

    async queryReports(options = {}) {
        try {
            const {
                page = 1,
                pageSize = 25,
                q = '',
                sort = 'modified',
                order = 'desc',
                from = null,
                to = null,
                buildType = null,
                runMode = null
            } = options;

            const requestsDir = config.paths.requests;
            const dirs = await this.listDirectory(requestsDir);

            // Get all report rows with minimal data first
            const allReports = [];
            for (const dir of dirs) {
                if (!dir.startsWith('req_')) continue;

                const reportPath = path.join(requestsDir, dir, 'report.html');
                if (await this.fileExists(reportPath)) {
                    const stats = await this.getFileStats(reportPath);
                    allReports.push({
                        id: dir,
                        modified: stats.mtime,
                        modifiedMs: stats.mtimeMs
                    });
                }
            }

            // Filter by search query (on id)
            let filtered = allReports;
            if (q && q.trim()) {
                const searchLower = q.trim().toLowerCase();
                filtered = filtered.filter(r => r.id.toLowerCase().includes(searchLower));
            }

            // Filter by date range
            if (from) {
                const fromDate = this.parseDayBoundary(from, false);
                if (!isNaN(fromDate)) {
                    filtered = filtered.filter(r => r.modified >= fromDate);
                }
            }
            if (to) {
                const toDate = this.parseDayBoundary(to, true);
                if (!isNaN(toDate)) {
                    filtered = filtered.filter(r => r.modified <= toDate);
                }
            }

            // Sort
            const sortField = sort === 'id' ? 'id' : 'modifiedMs';
            const sortOrder = order === 'asc' ? 1 : -1;
            filtered.sort((a, b) => {
                if (a[sortField] < b[sortField]) return -sortOrder;
                if (a[sortField] > b[sortField]) return sortOrder;
                return 0;
            });

            const totalItems = filtered.length;
            const totalPages = Math.ceil(totalItems / pageSize);
            const currentPage = Math.max(1, Math.min(page, totalPages || 1));

            // Paginate
            const startIdx = (currentPage - 1) * pageSize;
            const endIdx = startIdx + pageSize;
            const pageItems = filtered.slice(startIdx, endIdx);

            // Enrich only the current page items
            const enrichedItems = [];
            for (const item of pageItems) {
                const enriched = await this.getReportRow(item.id, true);
                if (enriched) {
                    enrichedItems.push(enriched);
                }
            }

            // Apply enriched filters (buildType, runMode) if specified
            let finalItems = enrichedItems;
            if (buildType) {
                finalItems = finalItems.filter(r => r.buildType === buildType);
            }
            if (runMode) {
                finalItems = finalItems.filter(r => r.runMode === runMode);
            }

            return {
                items: finalItems,
                page: currentPage,
                pageSize: parseInt(pageSize),
                totalItems,
                totalPages,
                appliedFilters: {
                    q, sort, order, from, to, buildType, runMode
                }
            };
        } catch (err) {
            console.error('Error querying reports:', err);
            return {
                items: [],
                page: 1,
                pageSize: 25,
                totalItems: 0,
                totalPages: 0,
                appliedFilters: options,
                error: err.message
            };
        }
    }

    /**
     * Validate and delete a request directory
     * @param {string} id - Request ID to delete
     * @returns {Promise<Object>} Result with status and message
     */
    async deleteRequestDir(id) {
        try {
            // Validate ID format to prevent directory traversal
            if (!id || typeof id !== 'string') {
                throw new Error('Invalid request ID');
            }

            if (!this.isValidRequestId(id)) {
                throw new Error('Invalid request ID format');
            }

            // Resolve paths safely
            const requestsRoot = path.resolve(config.paths.requests);
            const targetPath = path.resolve(requestsRoot, id);

            // Ensure target is within requests directory
            if (!targetPath.startsWith(requestsRoot + path.sep)) {
                throw new Error('Invalid request path');
            }

            // Check if directory exists
            if (!await this.fileExists(targetPath)) {
                return { status: 'not_found', message: 'Request directory not found' };
            }

            // Try to delete using fs.rm first
            try {
                await fs.rm(targetPath, { recursive: true, force: true });
                return { status: 'deleted', id, message: 'Request directory deleted successfully' };
            } catch (rmErr) {
                // If permission error, try sudo fallback
                if (rmErr.code === 'EPERM' || rmErr.code === 'EACCES') {
                    console.warn(`Permission denied for ${targetPath}, attempting sudo fallback...`);

                    const { spawn } = require('child_process');
                    return new Promise((resolve) => {
                        const sudoProcess = spawn('sudo', ['-n', 'rm', '-rf', targetPath]);

                        let stderr = '';
                        sudoProcess.stderr.on('data', (data) => {
                            stderr += data.toString();
                        });

                        sudoProcess.on('close', (code) => {
                            if (code === 0) {
                                resolve({ status: 'deleted', id, message: 'Request directory deleted with sudo' });
                            } else {
                                resolve({
                                    status: 'error',
                                    message: `Failed to delete with sudo: ${stderr || 'Unknown error'}`,
                                    needsSudo: true
                                });
                            }
                        });

                        sudoProcess.on('error', (err) => {
                            resolve({
                                status: 'error',
                                message: `Sudo command failed: ${err.message}`,
                                needsSudo: true
                            });
                        });
                    });
                } else {
                    throw rmErr;
                }
            }
        } catch (err) {
            console.error(`Error deleting request ${id}:`, err);
            return { status: 'error', message: err.message };
        }
    }
}

module.exports = new FileService();
module.exports.completeUtf8Length = completeUtf8Length;
