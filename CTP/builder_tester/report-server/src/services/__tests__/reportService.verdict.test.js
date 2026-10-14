/**
 * Request verdicts and per-test counts on the reports list.
 */

const reportService = require('../reportService');

const r = (test, commit, status, extra = {}) => Object.assign({ test, commit, status }, extra);

describe('analyzeVerdict', () => {
    test('a regression is not hidden by another test being flaky', () => {
        const results = [
            r('A', 'c1', 'pass'), r('A', 'c2', 'fail'),
            r('B', 'c1', 'pass', { flaky: true }), r('B', 'c2', 'pass')
        ];
        const v = reportService.analyzeVerdict(['c1', 'c2'], results, ['c1', 'c2'], 'checkout', 'until-pass', false);
        expect(v).toEqual({ text: 'Regression introduced: c2', class: 'verdict-bug' });
    });

    test('a VM install failure (build_error) is an error, not a regression', () => {
        const results = [r('A', 'c1', 'pass'), r('A', 'c2', 'build_error')];
        const v = reportService.analyzeVerdict(['c1', 'c2'], results, ['c1', 'c2'], 'checkout', 'until-pass', false);
        expect(v.class).toBe('verdict-error');
    });

    test('an error callback with no results shows its message', () => {
        const v = reportService.analyzeVerdict([], [], ['c1'], 'checkout', 'until-pass', false, 'Command failed with exit code 128');
        expect(v).toEqual({ text: 'Error: Command failed with exit code 128', class: 'verdict-error' });
    });

    test('one commit failing to build keeps test wording for the run', () => {
        const results = [
            r('A', 'c1', 'pass'), r('A', 'c2', 'fail'), r('A', 'c3', 'build_failed')
        ];
        const v = reportService.analyzeVerdict(['c1', 'c2', 'c3'], results, ['c1', 'c2', 'c3'], 'checkout', 'until-fail', false);
        expect(v.text).toBe('Failure reproduced starting: c2 (until-fail)');
    });

    test('build-only runs keep build wording', () => {
        const results = [r('Build', 'c1', 'build_success'), r('Build', 'c2', 'build_failed')];
        const v = reportService.analyzeVerdict(['c1', 'c2'], results, ['c1', 'c2'], 'checkout', 'until-pass', false);
        expect(v.text).toBe('Build regression introduced: c2');
    });

    test('a failed upload still qualifies a successful build', () => {
        const results = [r('Build', 'c1', 'build_success'), r('Build', 'c1', 'upload_failed')];
        const v = reportService.analyzeVerdict(['c1'], results, ['c1'], 'checkout', 'until-pass', true);
        expect(v.class).toBe('verdict-flaky');
        expect(v.text).toContain('artifact upload failed');
    });
});

describe('calculateTestStatistics', () => {
    test('counts a regression as failed, like the report page', () => {
        const results = [
            r('A', 'c1', 'pass'), r('A', 'c2', 'fail'),
            r('B', 'c1', 'pass', { flaky: true }), r('B', 'c2', 'pass'),
            r('C', 'c1', 'pass'), r('C', 'c2', 'pass')
        ];
        const stats = reportService.calculateTestStatistics(results, { commitOrder: ['c1', 'c2'], commitBuildMode: 'checkout' });
        expect(stats).toEqual({ total: 3, passed: 1, failed: 1, error: 0, flaky: 1, unstable: 0 });
    });

    test('counts an environment error as error', () => {
        const stats = reportService.calculateTestStatistics([r('A', 'c1', 'environment_error')], { commitOrder: ['c1'] });
        expect(stats.error).toBe(1);
    });
});
