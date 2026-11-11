/**
 * Official build service - lists the official CUBRID builds of the build store
 *
 * The store is a Tomcat directory listing with one directory per version
 * (11.5.0.2856-1b26459/, 10.1.3.7698-553a7231d/) and its Last Modified time. The
 * listing is about 300 KB, so it is fetched at most once per cacheTtlMs.
 *
 * The store is the one the Builder takes official builds from (its /health names it), so
 * the dashboard never offers builds the Builder would then refuse; the report server's own
 * setting is only used while the Builder does not answer.
 */

const http = require('http');
const https = require('https');
const config = require('../config');
const proxyService = require('./proxyService');

// As the Builder's OfficialBuilds.VERSION: major.minor.patch.build-commit
const VERSION_RE = /^(\d{1,3})\.(\d{1,3})\.(\d{1,4})\.(\d{1,6})-([0-9a-f]{7,40})$/;
// One listing row: the directory link, then the size and Last Modified cells
const ROW_RE = /<a href="[^"]*"><tt>([^<]+?)\/?<\/tt><\/a><\/td>\s*<td[^>]*><tt>[^<]*<\/tt><\/td>\s*<td[^>]*><tt>([^<]*)<\/tt>/g;
const MAX_LISTING_BYTES = 8 * 1024 * 1024;

function parseVersion(name) {
    const m = VERSION_RE.exec(name);
    if (!m) return null;
    return {
        version: name,
        series: `${m[1]}.${m[2]}`,
        parts: [m[1], m[2], m[3], m[4]].map(Number),
        commit: m[5]
    };
}

function compareSeriesDesc(a, b) {
    const [amaj, amin] = a.split('.').map(Number);
    const [bmaj, bmin] = b.split('.').map(Number);
    return (bmaj - amaj) || (bmin - amin);
}

/**
 * Builds of a store listing, newest Last Modified first, and the release lines (series)
 * present, newest first. Entries that are not versions ($CUBRID_VERSION/, tmp/) are skipped.
 */
function parseListing(html) {
    const builds = [];
    const seen = new Set();
    let m;
    ROW_RE.lastIndex = 0;
    while ((m = ROW_RE.exec(html)) !== null) {
        const info = parseVersion(m[1].trim());
        if (!info || seen.has(info.version)) continue;
        seen.add(info.version);
        const ts = Date.parse(m[2].trim());
        builds.push({
            version: info.version,
            series: info.series,
            commit: info.commit,
            build: info.parts[3],
            lastModified: Number.isFinite(ts) ? new Date(ts).toISOString() : null
        });
    }
    builds.sort((a, b) => {
        const at = a.lastModified ? Date.parse(a.lastModified) : 0;
        const bt = b.lastModified ? Date.parse(b.lastModified) : 0;
        return (bt - at) || b.version.localeCompare(a.version);
    });
    const series = Array.from(new Set(builds.map(b => b.series))).sort(compareSeriesDesc);
    return { builds, series };
}

function fetchText(url, timeoutMs) {
    return new Promise((resolve, reject) => {
        let parsed;
        try {
            parsed = new URL(url);
        } catch (e) {
            reject(new Error(`Invalid official build store URL: ${url}`));
            return;
        }
        const client = parsed.protocol === 'https:' ? https : parsed.protocol === 'http:' ? http : null;
        if (!client) {
            reject(new Error(`Unsupported protocol for the official build store: ${parsed.protocol}`));
            return;
        }
        const req = client.get(parsed, { timeout: timeoutMs }, (res) => {
            if (res.statusCode !== 200) {
                res.resume();
                reject(new Error(`Official build store answered HTTP ${res.statusCode}`));
                return;
            }
            res.setEncoding('utf8');
            let body = '';
            res.on('data', (chunk) => {
                body += chunk;
                if (body.length > MAX_LISTING_BYTES) {
                    req.destroy(new Error('Official build store listing is too large'));
                }
            });
            res.on('end', () => resolve(body));
            res.on('error', reject);
        });
        req.on('timeout', () => req.destroy(new Error(`Official build store did not answer within ${timeoutMs} ms`)));
        req.on('error', reject);
    });
}

function normalizeStoreUrl(url) {
    if (typeof url !== 'string' || !/^https?:\/\/[^\s]+$/.test(url.trim())) return null;
    const v = url.trim();
    return v.endsWith('/') ? v : v + '/';
}

/** The Builder's official_build_store_url, or null when the Builder does not answer. */
async function builderStoreUrl() {
    const b = config.builder;
    const result = await proxyService.requestJson(`${b.protocol}://${b.host}:${b.port}/health`, { timeout: 3000 });
    return result && result.json ? result.json.officialBuildStoreUrl : null;
}

class OfficialBuildService {
    constructor(options = {}) {
        this.storeUrl = normalizeStoreUrl(options.storeUrl || config.officialBuilds.storeUrl);
        this.cacheTtlMs = options.cacheTtlMs != null ? options.cacheTtlMs : config.officialBuilds.cacheTtlMs;
        this.timeoutMs = options.timeoutMs || config.officialBuilds.timeoutMs;
        this.fetchText = options.fetchText || fetchText;
        this.resolveStoreUrl = options.resolveStoreUrl || builderStoreUrl;
        this.cache = null; // { at, value }
        this.inFlight = null; // { storeUrl, promise }
    }

    async currentStoreUrl() {
        let fromBuilder = null;
        try {
            fromBuilder = normalizeStoreUrl(await this.resolveStoreUrl());
        } catch {
            // Builder down or restarting: the configured store
        }
        return fromBuilder || this.storeUrl;
    }

    /** { storeUrl, fetchedAt, series: [...], builds: [{version, series, commit, build, lastModified}] } */
    async list({ refresh = false } = {}) {
        const storeUrl = await this.currentStoreUrl();
        const cached = this.cache && this.cache.value.storeUrl === storeUrl ? this.cache : null;
        if (!refresh && cached && Date.now() - cached.at < this.cacheTtlMs) {
            return cached.value;
        }
        // Concurrent dashboard loads share one fetch of the store
        if (!this.inFlight || this.inFlight.storeUrl !== storeUrl) {
            const promise = (async () => {
                try {
                    const html = await this.fetchText(storeUrl, this.timeoutMs);
                    const { builds, series } = parseListing(html);
                    if (builds.length === 0) {
                        throw new Error('The official build store lists no builds');
                    }
                    const value = { storeUrl, fetchedAt: new Date().toISOString(), series, builds };
                    this.cache = { at: Date.now(), value };
                    return value;
                } finally {
                    if (this.inFlight && this.inFlight.promise === promise) this.inFlight = null;
                }
            })();
            this.inFlight = { storeUrl, promise };
        }
        try {
            return await this.inFlight.promise;
        } catch (err) {
            // A store that is briefly down: the last listing of it beats an empty picker
            if (cached) return { ...cached.value, stale: true };
            throw err;
        }
    }
}

module.exports = new OfficialBuildService();
module.exports.OfficialBuildService = OfficialBuildService;
module.exports.parseListing = parseListing;
