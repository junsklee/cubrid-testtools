const fileService = require('../fileService');

const results = (...names) => ({
    results: [{ attemptLogMetadata: names.map((logFileName, i) => ({ logFileName, attempt: i + 1, status: 'pass' })) }]
});

describe('detectExecutionMode', () => {
    test('VM attempt logs mean a VM run', () => {
        expect(fileService.detectExecutionMode(results('vm_fd030f8_api27_1b0bda76.log', 'vm_fd030f8_api27_1b0bda76.2.log'), {})).toBe('vm');
    });

    test('Docker shell and SQL attempt logs mean a Docker run', () => {
        expect(fileService.detectExecutionMode(results('docker_opt_fd030f8_api27.log'), {})).toBe('docker');
        expect(fileService.detectExecutionMode(results('docker_fd030f8_api27.log'), {})).toBe('docker');
        expect(fileService.detectExecutionMode(results('sql_fd030f8_case.log'), {})).toBe('docker');
    });

    test('artifact entries do not decide the mode', () => {
        const data = results('docker_opt_fd030f8_x.log');
        data.results[0].attemptLogMetadata.push({ logFileName: 'vmcase_vm_fd030f8_x_1.txt', artifactType: 'case_files' });
        expect(fileService.detectExecutionMode(data, {})).toBe('docker');
    });

    test('a request spread over Docker and VM testers is mixed', () => {
        const data = { results: [...results('vm_a_x_1.log').results, ...results('docker_opt_a_y.log').results] };
        expect(fileService.detectExecutionMode(data, {})).toBe('mixed');
    });

    test('no attempt logs (build-only, cancelled) gives no mode', () => {
        expect(fileService.detectExecutionMode({ results: [{ test: 'build_only' }] }, {})).toBeNull();
        expect(fileService.detectExecutionMode({}, {})).toBeNull();
    });

    test('an explicit executionMode wins over log names', () => {
        expect(fileService.detectExecutionMode({ executionMode: 'VM', ...results('docker_opt_a_x.log') }, {})).toBe('vm');
    });
});
