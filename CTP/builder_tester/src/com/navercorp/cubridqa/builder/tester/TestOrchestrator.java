package com.navercorp.cubridqa.builder.tester;

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
import java.util.concurrent.PriorityBlockingQueue;

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
    private final AtomicInteger heavyInFlight = new AtomicInteger(0);
    private final AtomicInteger queuedTestCount = new AtomicInteger(0);
    private final int maxConcurrencyHeavy;
    private final int maxConcurrencyPostHeavy;
    private final RunningTestTracker runningTestTracker = new RunningTestTracker();
    private final NodeCapacity nodeCapacity;
    private final ActualSampler actualSampler; // Used for Circuit Breaker logic
    private final Object reservationLock = new Object();
    private final PriorityBlockingQueue<QueueToken> waitQueue = new PriorityBlockingQueue<>();

    public TestOrchestrator(Config config, DirectExecutor directExecutor,
                          StandardDockerExecutor standardDockerExecutor,
                          OptimizedDockerExecutor optimizedDockerExecutor,
                          boolean useDocker, Object dockerManager, Object dockerUtils,
                          TestObservationWriter observationWriter, TestStatsStore testStatsStore,
                          NodeCapacity nodeCapacity, ActualSampler actualSampler) {
        this.config = config;
        this.directExecutor = directExecutor;
        this.standardDockerExecutor = standardDockerExecutor;
        this.optimizedDockerExecutor = optimizedDockerExecutor;
        this.useDocker = useDocker;
        this.dockerManager = dockerManager;
        this.dockerUtils = dockerUtils;
        this.observationWriter = observationWriter;
        this.testStatsStore = testStatsStore;
        this.maxConcurrencyHeavy = Math.max(1, config.getMaxConcurrentTestsWhileHeavy());
        this.maxConcurrencyPostHeavy = Math.max(this.maxConcurrencyHeavy, config.getMaxConcurrentTestsAfterHeavy());
        this.nodeCapacity = nodeCapacity;
        this.actualSampler = actualSampler;
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

        // Wait for resources and admit to tracker (prevents TOCTOU overload)
        boolean heavyTest = isHeavyTest(demand);
        
        // Use timeout from request if available, otherwise default to 24 hours
        long reqTimeoutSec = request.optLong("timeout", 0);
        long waitTimeoutMs = reqTimeoutSec > 0 ? reqTimeoutSec * 1000L : 24 * 60 * 60 * 1000L;
        
        boolean reserved = waitForReservation(testId, testKey, demand, heavyTest, testLogger, waitTimeoutMs);
        
        if (!reserved) {
            JSONObject response = new JSONObject();
            response.put("status", "rejected");
            response.put("error", "No capacity - node oversubscribed (timeout waiting for resources)");
            return response;
        }

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
            // Release concurrency slot
            releaseSlot(heavyTest);
            
            // Unregister test from tracker (releases reserved capacity)
            runningTestTracker.unregister(testId);
            
            // Notify waiting threads that capacity/slots are available
            synchronized (reservationLock) {
                reservationLock.notifyAll();
            }
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

    private static class QueueToken implements Comparable<QueueToken> {
        final String testId;
        final long durationMs;
        final long timestamp;

        QueueToken(String testId, long durationMs) {
            this.testId = testId;
            this.durationMs = durationMs;
            this.timestamp = System.nanoTime();
        }

        @Override
        public int compareTo(QueueToken other) {
            // Descending duration (Longest Processing Time First - LPT)
            // This prioritizes elephants over mice
            int cmp = Long.compare(other.durationMs, this.durationMs);
            if (cmp != 0) return cmp;
            // Ascending timestamp (FIFO for ties)
            return Long.compare(this.timestamp, other.timestamp);
        }
    }

    /**
     * Blocks until resources and concurrency slots are available, then admits the test.
     * Uses a priority queue to ensure long tests (elephants) are prioritized over
     * short tests (mice) to reduce overall makespan and prevent starvation.
     * 
     * Returns false if timeout reached.
     */
    private boolean waitForReservation(String testId, String testKey, PredictedDemand pd, boolean heavyTest, Logger logger, long timeoutMs) {
        long start = System.currentTimeMillis();

        QueueToken token = new QueueToken(testId, pd.getDurationMs());
        waitQueue.add(token);
        queuedTestCount.incrementAndGet();
        
        try {
            synchronized (reservationLock) {
                while (true) {
                    // Priority check: Am I the highest priority waiter?
                    // We peek at the head of the queue.
                    QueueToken head = waitQueue.peek();
                    
                    // If I am the head (highest priority), I get exclusive right to try acquiring resources.
                    // This prevents small tests from jumping ahead of a large waiting test.
                    if (head != null && head.testId.equals(testId)) {
                        if (hasLocalHeadroom(pd) && tryAcquireSlot(heavyTest)) {
                            // Success! Claim the resources.
                            runningTestTracker.admit(testId, testKey, pd);
                            runningTestTracker.startRunning(testId);
                            
                            // Remove myself from the queue and notify others 
                            // (so the next head can check if they also fit)
                            waitQueue.remove(token);
                            reservationLock.notifyAll();
                            return true;
                        }
                        // If I am head but don't fit, I wait.
                        // Everyone else waits too, because I am blocking the head of the queue.
                        // This is intentional to prioritize elephants.
                    }

                    long elapsed = System.currentTimeMillis() - start;
                    if (elapsed >= timeoutMs) {
                        logger.warning("Timeout waiting for resources (" + timeoutMs + "ms) for test " + testKey);
                        return false;
                    }

                    try {
                        // Wait for resources to free up or queue position to change
                        reservationLock.wait(1000); 
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        } finally {
            // Ensure token is removed if exception occurs or timeout returns false
            if (waitQueue.contains(token)) {
                waitQueue.remove(token);
            }
            queuedTestCount.decrementAndGet();
        }
    }

    /**
     * Checks if the tester has enough local capacity to admit the predicted demand.
     * Uses "Elastic Overcommit" logic with a "Circuit Breaker" based on real-time load.
     */
    private boolean hasLocalHeadroom(PredictedDemand pd) {
        // 1. Circuit Breaker: Check Real-Time System Load
        // If the system is actually sweating (regardless of what our bookkeeping says), stop admitting.
        if (actualSampler != null) {
            UtilizationSnapshot actual = actualSampler.getLatestSnapshot();
            // Calculate actual utilization percentages
            // Note: actual.getTotalCpuMillicores() is already millicores. 
            // nodeCapacity.getCpuMillicores() is now millicores.
            // So (actual / capacity) * 100.0 gives %.
            double actualCpuPct = (actual.getTotalCpuMillicores()) / nodeCapacity.getCpuMillicores() * 100.0;
            double actualMemPct = (actual.getTotalMemBytes() / (1024.0 * 1024.0)) / nodeCapacity.getMemMb() * 100.0;

            double maxCpu = config.getSchedulingCircuitBreakerCpu();
            double maxMem = config.getSchedulingCircuitBreakerMem();

            if (actualCpuPct > maxCpu || actualMemPct > maxMem) {
                // Circuit Breaker Tripped!
                return false;
            }
        }

        // 2. Elastic Overcommit: Admission Control with Overprovisioning
        
        // Dimension-specific base margins
        final double baseCpu = 0.10, baseMem = 0.20, baseIoRead = 0.35, baseIoWrite = 0.35;
        final double baseNet = 0.25, baseIops = 0.25;
        final double k = 0.50; // Confidence factor
        final double ioSafetyHeadroom = config.getIoSafetyHeadroomRatio(); // Default 0.15

        // Scalar confidence
        double conf = Math.max(0.0, Math.min(1.0, pd.getConfidence()));

        // Compute dimension-specific margins with confidence scaling
        double mCpu = baseCpu + k * (1.0 - conf);
        double mMem = baseMem + k * (1.0 - conf);
        double mIoRead = baseIoRead + k * (1.0 - conf);
        double mIoWrite = baseIoWrite + k * (1.0 - conf);
        double mNet = baseNet + k * (1.0 - conf);
        double mIops = baseIops + k * (1.0 - conf);

        // Get current reserved utilization from tracker
        UtilizationSnapshot reserved = runningTestTracker.getCurrentUtilization();

        // Convert predicted demand to legacy units for comparison
        // Refactored: use millicores directly
        double reqCpuMillicores = pd.getCpuMillicores(); 
        double reqMemMb = pd.getMemBytes() / (1024.0 * 1024.0); // bytes → MB
        double reqIoReadMbPerSec = pd.getIoReadBytesPerSec() / (1024.0 * 1024.0);
        double reqIoWriteMbPerSec = pd.getIoWriteBytesPerSec() / (1024.0 * 1024.0);
        double reqNetMbPerSec = pd.getNetBytesPerSec() / (1024.0 * 1024.0);
        long reqIops = pd.getIops();

        // Apply margins to required resources
        double requiredCpu = reqCpuMillicores * (1.0 + mCpu);
        double requiredMem = reqMemMb + Math.max(reqMemMb * mMem, 100.0); // +100MB floor
        double requiredIoRead = reqIoReadMbPerSec * (1.0 + mIoRead);
        double requiredIoWrite = reqIoWriteMbPerSec * (1.0 + mIoWrite);
        double requiredIops = reqIops * (1.0 + mIops);
        double requiredNet = reqNetMbPerSec * (1.0 + mNet);

        // Compute free capacity with OVERCOMMIT factors
        double overcommitCpu = config.getSchedulingOvercommitCpu();
        double overcommitMem = config.getSchedulingOvercommitMem();

        // Effective Capacity = Physical Capacity * Overcommit Factor
        // CPU is now in millicores
        double effectiveCpuCap = nodeCapacity.getCpuMillicores() * overcommitCpu;
        double effectiveMemCap = nodeCapacity.getMemMb() * overcommitMem;

        double freeCpuPct = effectiveCpuCap - (reserved.getTotalCpuMillicores());
        double freeMem = effectiveMemCap - (reserved.getTotalMemBytes() / (1024.0 * 1024.0));
        
        // I/O and Network are hard physical limits, so we generally don't overcommit them as aggressively
        // or at all, to prevent thrashing/saturation. Kept at 1.0 (no overcommit) by default logic here.
        double freeIoRead = nodeCapacity.getIoReadMbPerSec() - reserved.getTotalIoReadBytesPerSec() / (1024.0 * 1024.0);
        double freeIoWrite = nodeCapacity.getIoWriteMbPerSec() - reserved.getTotalIoWriteBytesPerSec() / (1024.0 * 1024.0);
        double freeIops = nodeCapacity.getIops() - reserved.getTotalIops();
        double freeNet = nodeCapacity.getNetMbPerSec() - reserved.getTotalNetBytesPerSec() / (1024.0 * 1024.0);

        // Get safety headroom
        double keepFreeRead = nodeCapacity.getIoReadMbPerSec() * ioSafetyHeadroom;
        double keepFreeWrite = nodeCapacity.getIoWriteMbPerSec() * ioSafetyHeadroom;

        // Check all dimensions
        return freeCpuPct >= requiredCpu
                && freeMem >= requiredMem
                && (requiredIoRead <= 0 || (freeIoRead - keepFreeRead >= requiredIoRead))
                && (requiredIoWrite <= 0 || (freeIoWrite - keepFreeWrite >= requiredIoWrite))
                && freeIops >= requiredIops
                && freeNet >= requiredNet;
    }

    private boolean isHeavyTest(PredictedDemand pd) {
        if (pd == null) {
            return false;
        }
        if (!pd.hasExplicitPrediction()) {
            return false;
        }
        long durationMs = pd.getDurationMs();
        if (durationMs < config.getSchedulingMiceThresholdMs()) {
            return false;
        }
        double totalIoMb = (Math.max(0, pd.getIoReadBytesPerSec()) + Math.max(0, pd.getIoWriteBytesPerSec()))
                / (1024.0 * 1024.0);
        return totalIoMb >= config.getSchedulingIoHeavyThreshold();
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
     * Attempts to acquire a concurrency slot. Returns false if the adaptive
     * limit would be exceeded (callers should respond with 409 so the builder
     * can retry on another node).
     */
    public boolean tryAcquireSlot(boolean heavyTest) {
        while (true) {
            int current = runningTestCount.get();
            int limit = determineActiveLimit(heavyTest);
            if (current >= limit) {
                return false;
            }
            if (runningTestCount.compareAndSet(current, current + 1)) {
                if (heavyTest) {
                    heavyInFlight.incrementAndGet();
                }
                return true;
            }
        }
    }

    public void releaseSlot(boolean heavyTest) {
        runningTestCount.decrementAndGet();
        if (heavyTest) {
            heavyInFlight.updateAndGet(val -> Math.max(0, val - 1));
        }
    }

    public int getHeavyInFlightCount() {
        return heavyInFlight.get();
    }

    public int getQueuedTestCount() {
        return queuedTestCount.get();
    }

    public int getActiveConcurrencyLimit() {
        return heavyInFlight.get() > 0 ? maxConcurrencyHeavy : maxConcurrencyPostHeavy;
    }

    private int determineActiveLimit(boolean incomingHeavy) {
        if (heavyInFlight.get() > 0 || incomingHeavy) {
            return maxConcurrencyHeavy;
        }
        return maxConcurrencyPostHeavy;
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
                .queuedTests(queuedTestCount.get())
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
