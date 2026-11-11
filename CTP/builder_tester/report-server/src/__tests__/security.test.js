/**
 * Unauthenticated endpoints must not let a caller escape log/requests, reach other
 * GitHub API paths with the server's token, fetch arbitrary URLs, or change state
 * from another web origin.
 */

const fs = require('fs');
const os = require('os');
const path = require('path');

const home = fs.mkdtempSync(path.join(os.tmpdir(), 'report-server-sec-'));
process.env.HOME = home;
process.env.NODE_ENV = 'test';
process.env.CORS_ORIGIN = '*';

const request = require('supertest');
const app = require('../server');

const requestsDir = path.join(home, 'cubrid-testtools', 'CTP', 'builder_tester', 'log', 'requests');

beforeAll(() => {
    fs.mkdirSync(path.join(requestsDir, 'req_1', 'tests'), { recursive: true });
    fs.writeFileSync(path.join(requestsDir, 'req_1', 'tests', 'a.log'), 'test log');
    fs.writeFileSync(path.join(requestsDir, 'req_1', 'builder.log'), 'builder log');
    fs.writeFileSync(path.join(home, 'secret.txt'), 'TOP SECRET');
});

afterAll(() => {
    fs.rmSync(home, { recursive: true, force: true });
});

describe('log endpoints', () => {
    test('serve files inside a request directory', async () => {
        const res = await request(app).get('/api/log/req_1/tests/a.log');
        expect(res.status).toBe(200);
        expect(res.text).toBe('test log');
        const root = await request(app).get('/api/log-root/req_1/builder.log');
        expect(root.text).toBe('builder log');
    });

    test.each([
        '/api/log-root/..%2F..%2F..%2F..%2F..%2F..%2Fsecret.txt/x',
        '/api/log-root/req_1/..%2F..%2F..%2F..%2F..%2F..%2Fsecret.txt',
        '/api/log/req_1/tests/..%2F..%2F..%2F..%2F..%2F..%2F..%2Fsecret.txt',
        '/logs/..%2F..%2F..%2F..%2F..%2F..%2F/tests/secret.txt',
        '/api/log/req_1/builds/..%2F..%2F..%2F..%2F..%2F..%2F..%2Fsecret.txt',
    ])('refuse traversal: %s', async (url) => {
        const res = await request(app).get(url);
        expect(res.text).not.toContain('TOP SECRET');
        expect(res.status).toBeGreaterThanOrEqual(400);
    });
});

describe('/callback', () => {
    test('refuses a taskId that leaves log/requests', async () => {
        const res = await request(app)
            .post('/callback')
            .send({ taskId: '../../../../../pwned', results: [] });
        expect(res.status).toBe(400);
        expect(fs.existsSync(path.join(home, 'pwned'))).toBe(false);
    });

    test('refuses markup in the request ID', async () => {
        const res = await request(app)
            .post('/callback')
            .send({ taskId: 'req_x\'"><img src=x onerror=alert(1)>', results: [] });
        expect(res.status).toBe(400);
    });
});

describe('cross-site writes', () => {
    test('a form post from another origin is refused', async () => {
        const res = await request(app)
            .post('/api/reports/bulk-delete')
            .set('Origin', 'http://evil.example')
            .type('form')
            .send('mode=keepLast&count=1');
        expect(res.status).toBe(403);
    });

    test('a same-origin JSON dry run is allowed', async () => {
        const res = await request(app)
            .post('/api/reports/bulk-delete')
            .set('Host', 'reports.local:8091')
            .set('Origin', 'http://reports.local:8091')
            .send({ mode: 'keepLast', count: 1, dryRun: true });
        expect(res.status).toBe(200);
        expect(res.body.dryRun).toBe(true);
    });

    test('form-encoded bodies are not parsed', async () => {
        const res = await request(app)
            .post('/api/reports/bulk-delete')
            .type('form')
            .send('mode=keepLast&count=1');
        expect(res.status).toBe(400);
    });
});

describe('/api/github/commit/:sha', () => {
    test('rejects dot segments before any request is made', async () => {
        const res = await request(app).get('/api/github/commit/..%2F..%2F..%2Fuser%2Frepos');
        expect(res.status).toBe(400);
    });

    test('accepts branch names with "/" and keeps them in one encoded path segment', () => {
        const { assertSafeCommitRef } = require('../services/githubService');
        const encoded = assertSafeCommitRef('release/11.2');
        expect(encoded).toBe('release%2F11.2');
        expect(new URL('https://api.github.com/repos/CUBRID/cubrid/commits/' + encoded).pathname)
            .toBe('/repos/CUBRID/cubrid/commits/release%2F11.2');
        for (const bad of ['/release', 'release/', 'release//11.2', 'release/../x', 'a b']) {
            expect(() => assertSafeCommitRef(bad)).toThrow('Invalid commit SHA');
        }
    });
});

describe('/api/tester/health', () => {
    test('rejects a URL with its own path or query', async () => {
        const res = await request(app).get('/api/tester/health')
            .query({ ip: 'http://127.0.0.1:9/internal/admin?x=' });
        expect(res.status).toBe(400);
    });
});
