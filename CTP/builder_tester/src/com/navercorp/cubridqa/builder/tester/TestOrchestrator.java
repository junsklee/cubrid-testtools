package com.navercorp.cubridqa.builder.tester;

import com.navercorp.cubridqa.builder.exec.ExecutorStrategy;
import com.navercorp.cubridqa.builder.exec.DirectExecutor;
import com.navercorp.cubridqa.builder.exec.StandardDockerExecutor;
import com.navercorp.cubridqa.builder.exec.OptimizedDockerExecutor;
import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.tester.stats.TestExecutionMetrics;
import com.navercorp.cubridqa.builder.tester.stats.TestObservation;
import com.navercorp.cubridqa.builder.tester.stats.TestObservationWriter;
import com.navercorp.cubridqa.builder.tester.stats.TestStatsStore;
import com.navercorp.cubridqa.builder.tester.demand.PredictedDemand;
import com.navercorp.cubridqa.builder.tester.demand.RunningTestTracker;
import com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot;
import com.navercorp.cubridqa.builder.scheduler.NodeMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;

public class TestOrchestrator {
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    private final Config config;
    private final DirectExecutor directExecutor;
    private final StandardDockerExecutor standardDockerExecutor;
    private final OptimizedDockerExecutor optimizedDockerExecutor;
    private final boolean useDocker;
    private final Object dockerManager; // DockerManager - using Object to avoid compile dependency
    private final Object dockerUtils; // DockerUtils - using Object to avoid compile dependency
    private final TestObservationWriter observationWriter;
    private final TestStatsStore testStatsStore;
    private final AtomicInteger runningTestCount = new AtomicInteger(0);
    private final RunningTestTracker runningTestTracker = new RunningTestTracker();

    public TestOrchestrator(Config config, DirectExecutor directExecutor,
                          StandardDockerExecutor standardDockerExecutor,
                          OptimizedDockerExecutor optimizedDockerExecutor,
                          boolean useDocker, Object dockerManager, Object dockerUtils,
                          TestObservationWriter observationWriter, TestStatsStore testStatsStore) {
        this.config = config;
        this.directExecutor = directExecutor;
        this.standardDockerExecutor = standardDockerExecutor;
        this.optimizedDockerExecutor = optimizedDockerExecutor;
        this.useDocker = useDocker;
        this.dockerManager = dockerManager;
        this.dockerUtils = dockerUtils;
        this.observationWriter = observationWriter;
        this.testStatsStore = testStatsStore;
    }

    /**
     * Runs a test with configurable retry/repeat logic based on run_mode.
     * Now collects log file paths instead of content for multipart sending.
     */
    public JSONObject runTestWithRetry(JSONObject request, Logger testLogger) throws Exception {
        // Generate unique test ID for tracking
        String testId = generateTestId(request);
        String testKey = request.optString("testKey", "unknown");

        // Extract predicted demand from request
        // Pass null for NodeHardware since TestOrchestrator doesn't have access to it
        // This will use conservative defaults if no prediction is provided
        PredictedDemand demand = PredictedDemand.fromRequest(request, null);

        // TODO: Wire up proper state transitions for RunningTestTracker.
        // Currently using admit() for both soft and hard admission. Should be:
        //   1. admit(testId, testKey, demand) - soft admission (here, before execution) ✓
        //   2. startRunning(testId) - hard reservation (right before Docker/executor starts)
        //   3. updatePhase(testId, phase) - if test has phases (setup → run transition)
        //   4. unregister(testId) - release reservation (in finally block) ✓
        // This ensures reserved utilization matches actual running tests and supports
        // phase-based resource modeling (different CPU/mem for setup vs run).

        // Admit test to tracker (reserves capacity)
        runningTestTracker.admit(testId, testKey, demand);
        runningTestCount.incrementAndGet();
        try {
            // Unified execution semantics (v2): minRuns, maxRuns, optional timeBudgetMs
            String runMode = request.optString("runMode", "until-pass").toLowerCase();
        if (!runMode.equals("until-pass") && !runMode.equals("until-fail") && !runMode.equals("fixed-runs")) {
            testLogger.warning("Invalid run_mode '" + runMode + "' in request. Using default 'until-pass'");
            runMode = "until-pass";
        }

        int minRuns = Math.max(1, request.optInt("minRuns", 1));
        int maxRuns = Math.max(minRuns, request.optInt("maxRuns", minRuns));
        Long timeBudgetMs = null;
        if (request.has("timeBudgetMs")) {
            long tb = request.optLong("timeBudgetMs", -1);
            if (tb >= 1) timeBudgetMs = tb;
        }

        JSONObject lastResponse = null;
        List<Path> attemptLogFiles = new ArrayList<>();
        JSONArray attemptLogMetadata = new JSONArray();
        long startTime = System.currentTimeMillis();
        boolean sawFailure = false;
        boolean sawPass = false;

        testLogger.info("Starting test execution with mode '" + runMode + "', minRuns=" + minRuns + ", maxRuns=" + maxRuns + (timeBudgetMs != null ? ", timeBudgetMs=" + timeBudgetMs : ""));

        int attempt = 0;
        while (true) {
            attempt++;
            if (attempt > maxRuns) break; // Guard, should not happen due to checks after attempt

            if (maxRuns > 1) {
                testLogger.info("Running test attempt " + attempt + "/" + maxRuns + " (mode: " + runMode + ")");
            }

            JSONObject requestWithAttempt = new JSONObject(request.toString());
            requestWithAttempt.put("attemptNumber", attempt);

            RunOutcome outcome = runTest(requestWithAttempt, testLogger);
            JSONObject attemptResponse = outcome.response;
            lastResponse = attemptResponse;
            String status = attemptResponse.optString("status", "");

            // Collect log file metadata
            if (attemptResponse.has("logFilePath")) {
                Path logPath = Paths.get(attemptResponse.getString("logFilePath"));
                attemptLogFiles.add(logPath);
                JSONObject attemptMeta = new JSONObject();
                attemptMeta.put("attempt", attempt);
                attemptMeta.put("logFileName", attemptResponse.optString("logFileName", logPath.getFileName().toString()));
                attemptMeta.put("status", status);
                attemptLogMetadata.put(attemptMeta);
                attemptResponse.remove("logFilePath");
            }

            // Track failures and passes to decide flakiness later
            boolean isPass = TestStatus.PASS.getValue().equalsIgnoreCase(status);
            boolean isFailLike = TestStatus.FAIL.getValue().equalsIgnoreCase(status) ||
                                  TestStatus.EXECUTION_ERROR.getValue().equalsIgnoreCase(status) ||
                                  TestStatus.ENVIRONMENT_ERROR.getValue().equalsIgnoreCase(status) ||
                                  TestStatus.BUILD_ERROR.getValue().equalsIgnoreCase(status) ||
                                  (!isPass && !status.equalsIgnoreCase("started"));  // Treat unknown statuses as fail-like, except "started"
            
            if (isFailLike) {
                sawFailure = true;
            }
            if (isPass) {
                sawPass = true;
            }

            // For keepAlive runs where tester returns 'started', end immediately
            recordObservation(outcome.testRequest, outcome.result, testLogger);

            if ("started".equalsIgnoreCase(status)) {
                attemptResponse.put("attempts", attempt);
                attemptResponse.put("attemptLogFiles", attemptLogFiles);
                attemptResponse.put("attemptLogMetadata", attemptLogMetadata);
                // Note: keepAlive mode doesn't check for flakiness since it's not meant for testing
                return attemptResponse;
            }

            // Stop conditions (first true wins):
            // 1) attempt == maxRuns
            if (attempt >= maxRuns) break;

            // 2) time_budget_ms reached
            if (timeBudgetMs != null && (System.currentTimeMillis() - startTime) >= timeBudgetMs) {
                testLogger.info("Time budget reached after attempt " + attempt + "; stopping");
                break;
            }

            // 3) until-pass early exit after minRuns on PASS
            // For flaky detection: only exit early if we have confidence about the test's behavior
            if (runMode.equals("until-pass") && attempt >= minRuns && isPass) {
                // If we've seen both pass and failure, mark as flaky and exit
                if (sawFailure && sawPass) {
                    attemptResponse.put("attempts", attempt);
                    attemptResponse.put("flaky", true);
                    attemptResponse.put("status", "flaky");  // Override status to indicate flakiness
                    testLogger.info("Test marked as flaky - saw both pass and failure in " + attempt + " attempts");
                    attemptResponse.put("attemptLogFiles", attemptLogFiles);
                    attemptResponse.put("attemptLogMetadata", attemptLogMetadata);
                    return attemptResponse;
                }
                // If we've only seen passes and we have sufficient data, exit as stable
                else if (!sawFailure && attempt >= minRuns) {
                    attemptResponse.put("attempts", attempt);
                    testLogger.info("Test appears stable - only passes in " + attempt + " attempts");
                    attemptResponse.put("attemptLogFiles", attemptLogFiles);
                    attemptResponse.put("attemptLogMetadata", attemptLogMetadata);
                    return attemptResponse;
                }
                // If we've only seen failures + this pass, continue running to see if it's consistently passing now
                // If we've only seen one pass so far, continue to gather more data for confidence
                // (don't exit early - let it run more attempts to gather more data)
            }

            // 4) until-fail early exit after minRuns on FAIL-like
            // For flaky detection: only exit early if we have confidence about the test's behavior
            if (runMode.equals("until-fail") && attempt >= minRuns && isFailLike) {
                // If we've seen both pass and failure, mark as flaky and exit
                if (sawFailure && sawPass) {
                    attemptResponse.put("attempts", attempt);
                    attemptResponse.put("flaky", true);
                    attemptResponse.put("status", "flaky");  // Override status to indicate flakiness
                    testLogger.info("Test marked as flaky in until-fail mode - saw both pass and failure in " + attempt + " attempts");
                    attemptResponse.put("attemptLogFiles", attemptLogFiles);
                    attemptResponse.put("attemptLogMetadata", attemptLogMetadata);
                    return attemptResponse;
                }
                // If we've only seen failures and we have sufficient data (at least 2 failures), exit as reproducible
                else if (!sawPass && attempt >= Math.max(2, minRuns)) {
                    attemptResponse.put("attempts", attempt);
                    testLogger.info("Test failure reproduced - only failures in " + attempt + " attempts (reproduce mode)");
                    attemptResponse.put("attemptLogFiles", attemptLogFiles);
                    attemptResponse.put("attemptLogMetadata", attemptLogMetadata);
                    return attemptResponse;
                }
                // If we've only seen passes + this failure, continue running to see if it's consistently failing now
                // If we've only seen one failure so far, continue to gather more data for confidence
                // (don't exit early - let it run more attempts to gather more data)
            }

            // fixed-runs ignores early exits 3) & 4), continue to next attempt
        }

        // Completed due to maxRuns or time budget
        JSONObject finalResponse = lastResponse != null ? new JSONObject(lastResponse.toString()) : new JSONObject();
        finalResponse.put("attempts", Math.max(1, attempt));
        finalResponse.put("attemptLogFiles", attemptLogFiles);
        finalResponse.put("attemptLogMetadata", attemptLogMetadata);
        finalResponse.put("runMode", runMode);
        
        // Synchronously verify all log files are accessible before sending response
        verifyAllLogFilesAccessible(attemptLogFiles, testLogger);

        // Check for flakiness when completing without early exit
        if (runMode.equals("until-pass") && sawFailure && sawPass) {
            finalResponse.put("flaky", true);
            finalResponse.put("status", "flaky");  // Override status to indicate flakiness
            testLogger.info("Test marked as flaky - saw both pass and failure across " + attempt + " attempts");
        }

        if (runMode.equals("until-fail")) {
            // Check for flakiness first
            if (sawFailure && sawPass) {
                finalResponse.put("flaky", true);
                finalResponse.put("status", "flaky");  // Override status to indicate flakiness
                testLogger.info("Test marked as flaky in until-fail mode - saw both pass and failure");
            }
            // Only set "could not reproduce" summary if we didn't see any failures at all
            else if (!sawFailure) {
                testLogger.info("Test did not fail after " + attempt + " attempts (reproduce mode)");
                finalResponse.put("summary", "Could not reproduce failure after " + attempt + " attempts");
            }
        } else if (runMode.equals("fixed-runs")) {
            testLogger.info("Completed " + attempt + " run(s)");
            finalResponse.put("summary", "Completed " + attempt + " runs");
            // Check for flakiness in fixed-runs mode too
            if (sawFailure && sawPass) {
                finalResponse.put("flaky", true);
                finalResponse.put("status", "flaky");  // Override status to indicate flakiness
                testLogger.info("Test marked as flaky in fixed-runs mode - saw both pass and failure");
            }
        }

            return finalResponse;
        } finally {
            // Unregister test from tracker (releases reserved capacity)
            runningTestTracker.unregister(testId);
            runningTestCount.decrementAndGet();
        }
    }
    
    /**
     * Synchronously verify that all log files are accessible via HTTP before sending response.
     * This ensures the builder can fetch them immediately without timing issues.
     */
    private void verifyAllLogFilesAccessible(List<Path> logFiles, Logger testLogger) {
        if (logFiles == null || logFiles.isEmpty()) {
            return; // Nothing to verify
        }
        
        testLogger.info("Verifying " + logFiles.size() + " log files are accessible before sending response...");
        
        // Create a LogLocator to test file accessibility
        com.navercorp.cubridqa.builder.logs.LogLocator logLocator = 
            new com.navercorp.cubridqa.builder.logs.LogLocator();
        
        int timeoutSeconds = config.getLogFileVerificationTimeoutSeconds();
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;
        
        // Verify each log file
        for (Path logFile : logFiles) {
            String fileName = logFile.getFileName().toString();
            testLogger.info("Verifying log file accessibility: " + fileName);
            
            boolean verified = false;
            int attempts = 0;
            
            while (!verified && (System.currentTimeMillis() - startTime) < timeoutMs) {
                attempts++;
                
                try {
                    Path foundFile = logLocator.findLogFile(fileName);
                    if (foundFile != null && Files.exists(foundFile) && Files.isReadable(foundFile)) {
                        // Additional check: try to read file size to ensure it's fully written
                        long fileSize = Files.size(foundFile);
                        testLogger.info("Log file verified accessible: " + fileName + 
                                       " (" + fileSize + " bytes, attempt " + attempts + ")");
                        verified = true;
                        break;
                    }
                } catch (Exception e) {
                    testLogger.fine("Log file verification attempt " + attempts + " failed for " + fileName + ": " + e.getMessage());
                }
                
                // Wait before next attempt (exponential backoff: 100ms, 200ms, 400ms, etc.)
                try {
                    long delay = Math.min(100L * (1L << (attempts - 1)), 1000L); // Cap at 1 second
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    testLogger.warning("Log file verification interrupted for " + fileName);
                    return;
                }
            }
            
            if (!verified) {
                testLogger.warning("Log file verification timed out after " + timeoutSeconds + 
                                  "s for: " + fileName + " (attempts: " + attempts + ")");
            }
        }
        
        testLogger.info("Log file verification completed");
    }

    private RunOutcome runTest(JSONObject requestJson, Logger testLogger) throws Exception {
        TestRequest testRequest = new TestRequest(requestJson);
        Path workDir = Files.createTempDirectory(Paths.get(config.getWorkDir()), "test_");
        testLogger.info("Working directory: " + workDir);
        
        boolean keepAliveRequested = testRequest.isKeepAlive();
        try {
            // Check if we should use Docker for test execution
            if (useDocker && dockerManager != null && isDockerAvailable()) {
                TestResult result = executeDockerTest(testRequest, workDir, testLogger);
                return new RunOutcome(testRequest, result, convertToJSONObject(result));
            } else {
                testLogger.info("Using direct test execution");
                TestResult result = executeDirectTest(testRequest, workDir, testLogger);
                return new RunOutcome(testRequest, result, convertToJSONObject(result));
            }
        } finally {
            // Cleanup unless keep-alive requested or a keep marker is present
            try {
                if (!keepAliveRequested && !Files.exists(workDir.resolve("KEEP_WORKSPACE"))) {
                    deleteDirectory(workDir.toFile());
                } else {
                    testLogger.info("Preserving workDir for debugging: " + workDir);
                }
            } catch (Exception ignore) {
                // best effort
            }
        }
    }

    private TestResult executeDockerTest(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        // Try to use optimized Docker execution with pre-built images
        if (optimizedDockerExecutor != null && config.isOptimizedDockerEnabled()) {
            try {
                testLogger.info("Attempting optimized Docker execution with pre-built image...");
                TestResult result = optimizedDockerExecutor.execute(request, workDir, testLogger);
                return result;
            } catch (Exception e) {
                testLogger.warning("Optimized Docker execution failed, falling back to standard: " + e.getMessage());
                // Fall through to standard execution
            }
        }
        
        // Standard Docker execution
        try {
            TestResult result = standardDockerExecutor.execute(request, workDir, testLogger);
            return result;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Docker not available")) {
                testLogger.warning("Docker not available, falling back to direct execution");
                return executeDirectTest(request, workDir, testLogger);
            }
            throw e;
        }
    }

    private TestResult executeDirectTest(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        return directExecutor.execute(request, workDir, testLogger);
    }

    private JSONObject convertToJSONObject(TestResult result) {
        JSONObject json = new JSONObject();
        json.put("test", result.getTestName());
        json.put("status", result.getStatus());
        
        if (result.getMessage() != null) {
            json.put("message", result.getMessage());
        }
        if (result.getCommit() != null) {
            json.put("commit", result.getCommit());
        }
        if (result.getCommitShort() != null) {
            json.put("commitShort", result.getCommitShort());
        }
        if (result.getExecutionMode() != null) {
            json.put("execution_mode", result.getExecutionMode());
        }
        if (result.getExecutionTime() != null) {
            json.put("execution_time", result.getExecutionTime());
        }
        if (result.getExitCode() != null) {
            json.put("exit_code", result.getExitCode());
        }
        if (result.getTimestamp() != null) {
            json.put("timestamp", result.getTimestamp());
        }
        if (result.getContainerName() != null) {
            json.put("containerName", result.getContainerName());
        }
        if (result.getExecCommand() != null) {
            json.put("execCommand", result.getExecCommand());
        }
        if (result.getWorkspace() != null) {
            json.put("workspace", result.getWorkspace());
        }
        
        // Handle log files for multipart response
        if (!result.getAttemptLogFiles().isEmpty()) {
            json.put("attemptLogFiles", result.getAttemptLogFiles());
        }
        
        // Add single log file path for backward compatibility
        if (!result.getAttemptLogFiles().isEmpty()) {
            Path firstLogFile = result.getAttemptLogFiles().get(0);
            json.put("logFilePath", firstLogFile.toString());
            json.put("logFileName", firstLogFile.getFileName().toString());
        }
        
        return json;
    }

    private boolean isDockerAvailable() {
        try {
            if (dockerUtils != null) {
                return (Boolean) dockerUtils.getClass()
                    .getMethod("isDockerAvailable")
                    .invoke(dockerUtils);
            }
        } catch (Exception e) {
            // Fallback to simple check
        }
        return true; // Assume available if check fails
    }

    private void recordObservation(TestRequest request, TestResult result, Logger logger) {
        if (observationWriter == null || request == null || result == null) {
            return;
        }
        try {
            TestExecutionMetrics metrics = result.getExecutionMetrics();
            if (metrics == null) {
                metrics = TestExecutionMetrics.unknown();
            }
            String testKey = firstNonEmpty(request.getTestPath(), request.getTestName());
            TestObservation.Builder builder = TestObservation.builder()
                .testKey(testKey != null ? testKey : request.getTestName())
                .commit(firstNonEmpty(result.getCommit(), request.getCommit()))
                .baseline(firstNonEmpty(request.getBaselineShort(), request.getBaseline()))
                .executor(firstNonEmpty(result.getExecutionMode(), useDocker ? "docker" : "direct"))
                .status(result.getStatus())
                .attempts(Math.max(result.getAttempts(), request.getAttemptNumber()))
                .imageTag(metrics.getDockerImage())
                .buildPackage(firstNonEmpty(metrics.getBuildPackageName(), request.getBuildPackage()))
                .durationMs(metrics.getDurationMs())
                .cpuPctMean(metrics.getCpuPctMean())
                .cpuPctPeak(metrics.getCpuPctPeak())
                .memMbMean(metrics.getMemMbMean())
                .memMbPeak(metrics.getMemMbPeak())
                .ioMbPerSecMean(metrics.getIoMbPerSecMean())
                .iopsMean(metrics.getIopsMean())
                .netMbPerSecMean(metrics.getNetMbPerSecMean())
                .bytesReadMb(metrics.getBytesReadMb())
                .bytesWriteMb(metrics.getBytesWriteMb())
                .dockerImageCached(metrics.isDockerImageCached())
                .packageCached(metrics.isPackageCached())
                .metricsComplete(metrics.isMetricsComplete())
                .extra("attemptNumber", request.getAttemptNumber());

            long logSizeBytes = metrics.getLogSizeBytes();
            if (logSizeBytes >= 0) {
                long kb = Math.max(0L, (logSizeBytes + 1023) / 1024);
                builder.logSizeKb(kb);
            }
            if (result.getTimestamp() != null) {
                builder.timestamp(Instant.ofEpochMilli(result.getTimestamp()));
            }
            TestObservation obs = builder.build();

            // Write to old gzipped WAL for backward compatibility (if needed)
            observationWriter.recordObservation(obs);

            // Write to new segmented WAL via TestStatsStore (also updates in-memory stats)
            // Pass requestId from builder request for request journal tracking
            if (testStatsStore != null) {
                testStatsStore.recordObservation(obs, request.getRequestId());
            }
        } catch (Exception e) {
            logger.fine("Failed to record test observation: " + e.getMessage());
        }
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty() && !"unknown".equalsIgnoreCase(value.trim())) {
                return value;
            }
        }
        return null;
    }

    private static final class RunOutcome {
        final TestRequest testRequest;
        final TestResult result;
        final JSONObject response;

        RunOutcome(TestRequest testRequest, TestResult result, JSONObject response) {
            this.testRequest = testRequest;
            this.result = result;
            this.response = response;
        }
    }

    /**
     * Recursively delete a directory
     */
    private void deleteDirectory(java.io.File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * Returns the current number of running tests.
     * Used by HealthHandler for concurrency reporting.
     */
    public int getRunningTestCount() {
        return runningTestCount.get();
    }

    /**
     * Returns current utilization snapshot based on running test predictions.
     * Used by HealthHandler to report accurate resource utilization.
     *
     * @return utilization snapshot (never null)
     */
    public UtilizationSnapshot getCurrentUtilization() {
        return runningTestTracker.getCurrentUtilization();
    }

    /**
     * Snapshots reserved utilization into a NodeMetrics payload for optional pull-based scheduling.
     * This is purely observational and does not alter the existing push-based flow.
     */
    public NodeMetrics snapshotNodeMetrics() {
        UtilizationSnapshot reserved = runningTestTracker.getCurrentUtilization();
        return NodeMetrics.builder()
                .cpuUsedPct(reserved.getTotalCpuMillicores() / 10.0) // mCPU → %
                .memUsedMb(reserved.getTotalMemBytes() / BYTES_PER_MB)
                .ioReadUsedMbPerSec(reserved.getTotalIoReadBytesPerSec() / BYTES_PER_MB)
                .ioWriteUsedMbPerSec(reserved.getTotalIoWriteBytesPerSec() / BYTES_PER_MB)
                .iopsUsed(reserved.getTotalIops())
                .netUsedMbPerSec(reserved.getTotalNetBytesPerSec() / BYTES_PER_MB)
                .runningTests(runningTestCount.get())
                .queuedTests(0)
                .build();
    }

    /**
     * Generates a unique test ID for tracking.
     *
     * Format: {testKey}@{commit_short}@{timestamp}@{random}
     *
     * @param request the test request JSON
     * @return unique test ID
     */
    private String generateTestId(JSONObject request) {
        String testKey = request.optString("testKey", "unknown");
        String commit = request.optString("commit", "unknown");
        String commitShort = commit.length() > 7 ? commit.substring(0, 7) : commit;
        long timestamp = System.currentTimeMillis();
        String random = Integer.toHexString((int)(Math.random() * 65536));

        // Replace slashes in testKey to make it file-system friendly
        String sanitizedTestKey = testKey.replace("/", "_").replace("\\", "_");

        return String.format("%s@%s@%d@%s", sanitizedTestKey, commitShort, timestamp, random);
    }
}
