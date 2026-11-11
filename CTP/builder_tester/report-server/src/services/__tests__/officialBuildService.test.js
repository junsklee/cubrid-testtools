/**
 * The official build list: the build store's Tomcat directory listing, newest first.
 */

const { OfficialBuildService, parseListing } = require('../officialBuildService');

// Rows as the store (Tomcat) writes them, including the entries that are not builds
function row(name, modified) {
    return `<tr>
<td align="left">&nbsp;&nbsp;
<a href="/REPO_ROOT/store_01/${encodeURIComponent(name)}/"><tt>${name}/</tt></a></td>
<td align="right"><tt>&nbsp;</tt></td>
<td align="right"><tt>${modified}</tt></td>
</tr>`;
}

const LISTING = `<html><head><title>Directory Listing For /store_01/</title></head><body><table>
<tr><td align="left"><font size="+1"><strong>Filename</strong></font></td></tr>
${row('$CUBRID_VERSION', 'Tue, 26 Feb 2019 01:07:04 GMT')}
${row('10.1.3.7698-553a7231d', 'Tue, 07 May 2019 09:20:57 GMT')}
${row('11.4.7.1995-f39ab8c', 'Tue, 10 Nov 2026 06:50:53 GMT')}
${row('11.5.0.2856-1b26459', 'Mon, 09 Nov 2026 15:15:05 GMT')}
${row('11.0.17.0452-47a0a54', 'Tue, 10 Nov 2026 08:11:15 GMT')}
${row('11.5.0.2846-ce905f8', 'Fri, 06 Nov 2026 14:24:41 GMT')}
${row('tmp', 'Wed, 01 Jan 2025 00:00:00 GMT')}
${row('11.5.0.2846-ce905f8', 'Fri, 06 Nov 2026 14:24:41 GMT')}
</table></body></html>`;

describe('parseListing', () => {
    test('lists only versions, newest Last Modified first, with their release lines', () => {
        const { builds, series } = parseListing(LISTING);
        expect(builds.map(b => b.version)).toEqual([
            '11.0.17.0452-47a0a54',
            '11.4.7.1995-f39ab8c',
            '11.5.0.2856-1b26459',
            '11.5.0.2846-ce905f8',
            '10.1.3.7698-553a7231d'
        ]);
        expect(series).toEqual(['11.5', '11.4', '11.0', '10.1']);
        expect(builds[2]).toEqual({
            version: '11.5.0.2856-1b26459',
            series: '11.5',
            commit: '1b26459',
            build: 2856,
            lastModified: '2026-11-09T15:15:05.000Z'
        });
        expect(builds[4].commit).toBe('553a7231d');
    });

    test('a page without rows lists nothing', () => {
        expect(parseListing('<html>Not Found</html>')).toEqual({ builds: [], series: [] });
    });

    test('markup in a directory name is not a version', () => {
        const { builds } = parseListing(row('11.5.0.1-abcdef0"><script>x</script>', 'Mon, 09 Nov 2026 15:15:05 GMT'));
        expect(builds).toEqual([]);
    });
});

// The Builder is not running in tests: the configured store is used
const noBuilder = async () => { throw new Error('connect ECONNREFUSED 127.0.0.1:8089'); };

describe('OfficialBuildService.list', () => {
    test("lists the Builder's store when the Builder names one", async () => {
        const fetchText = jest.fn().mockResolvedValue(LISTING);
        let builderStore = 'http://builder-store.example/store_02';
        const service = new OfficialBuildService({
            storeUrl: 'http://configured.example/', cacheTtlMs: 60000, fetchText,
            resolveStoreUrl: async () => builderStore
        });
        expect((await service.list()).storeUrl).toBe('http://builder-store.example/store_02/');
        expect(fetchText.mock.calls[0][0]).toBe('http://builder-store.example/store_02/');

        // The Builder moved to another store: the cached list of the old one is not used
        builderStore = 'http://other.example/s/';
        expect((await service.list()).storeUrl).toBe('http://other.example/s/');
        expect(fetchText).toHaveBeenCalledTimes(2);

        // A Builder without the setting (older code), or with a value that is not a URL
        builderStore = undefined;
        expect((await service.list()).storeUrl).toBe('http://configured.example/');
        builderStore = 'javascript:alert(1)';
        expect((await service.list()).storeUrl).toBe('http://configured.example/');
    });

    test('fetches the store once per cache period', async () => {
        const fetchText = jest.fn().mockResolvedValue(LISTING);
        const service = new OfficialBuildService({ storeUrl: 'http://store.example/s', cacheTtlMs: 60000, fetchText, resolveStoreUrl: noBuilder });
        const [a, b] = await Promise.all([service.list(), service.list()]);
        await service.list();
        expect(fetchText).toHaveBeenCalledTimes(1);
        expect(fetchText.mock.calls[0][0]).toBe('http://store.example/s/');
        expect(a).toBe(b);
        expect(a.storeUrl).toBe('http://store.example/s/');
        expect(a.builds).toHaveLength(5);

        await service.list({ refresh: true });
        expect(fetchText).toHaveBeenCalledTimes(2);
    });

    test('keeps the last list when the store stops answering', async () => {
        const fetchText = jest.fn()
            .mockResolvedValueOnce(LISTING)
            .mockRejectedValueOnce(new Error('connect ECONNREFUSED'));
        const service = new OfficialBuildService({ storeUrl: 'http://store.example/', cacheTtlMs: 0, fetchText, resolveStoreUrl: noBuilder });
        await service.list();
        const stale = await service.list();
        expect(stale.stale).toBe(true);
        expect(stale.builds).toHaveLength(5);
    });

    test('an empty or unreachable store is an error', async () => {
        const empty = new OfficialBuildService({ storeUrl: 'http://store.example/', fetchText: async () => '<html></html>', resolveStoreUrl: noBuilder });
        await expect(empty.list()).rejects.toThrow('lists no builds');
        const down = new OfficialBuildService({
            storeUrl: 'http://store.example/',
            fetchText: async () => { throw new Error('connect ECONNREFUSED'); },
            resolveStoreUrl: noBuilder
        });
        await expect(down.list()).rejects.toThrow('ECONNREFUSED');
    });
});

describe('GET /api/official-builds', () => {
    const request = require('supertest');
    const service = require('../officialBuildService');
    const app = require('../../server');

    afterEach(() => jest.restoreAllMocks());

    test('answers the list', async () => {
        jest.spyOn(service, 'list').mockResolvedValue({ storeUrl: 'http://s/', series: ['11.5'], builds: [] });
        const res = await request(app).get('/api/official-builds');
        expect(res.status).toBe(200);
        expect(res.body.series).toEqual(['11.5']);
        expect(service.list).toHaveBeenCalledWith({ refresh: false });
    });

    test('a store that cannot be read is a 502 with the reason', async () => {
        jest.spyOn(service, 'list').mockRejectedValue(new Error('Official build store answered HTTP 500'));
        const res = await request(app).get('/api/official-builds?refresh=1');
        expect(res.status).toBe(502);
        expect(res.body.error).toContain('HTTP 500');
        expect(service.list).toHaveBeenCalledWith({ refresh: true });
    });
});
