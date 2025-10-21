package com.navercorp.cubridqa.builder.tester;

import com.navercorp.cubridqa.builder.exec.ExecutorStrategy;
import com.navercorp.cubridqa.builder.exec.DirectExecutor;
import com.navercorp.cubridqa.builder.exec.StandardDockerExecutor;
import com.navercorp.cubridqa.builder.exec.OptimizedDockerExecutor;
import com.navercorp.cubridqa.builder.config.Config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class TestOrchestrator {
    private final Config config;
    private final DirectExecutor directExecutor;
    private final StandardDockerExecutor standardDockerExecutor;
    private final OptimizedDockerExecutor optimizedDockerExecutor;
    private final boolean useDocker;
    private final Object dockerManager; // DockerManager - using Object to avoid compile dependency
    private final Object dockerUtils; // DockerUtils - using Object to avoid compile dependency

    public TestOrchestrator(Config config, DirectExecutor directExecutor, 
                          StandardDockerExecutor standardDockerExecutor, 
                          OptimizedDockerExecutor optimizedDockerExecutor,
                          boolean useDocker, Object dockerManager, Object dockerUtils) {
        this.config = config;
        this.directExecutor = directExecutor;
        this.standardDockerExecutor = standardDockerExecutor;
        this.optimizedDockerExecutor = optimizedDockerExecutor;
        this.useDocker = useDocker;
        this.dockerManager = dockerManager;
        this.dockerUtils = dockerUtils;
    }

    /**
     * Runs a test with configurable retry/repeat logic based on run_mode.
     * Now collects log file paths instead of content for multipart sending.
     */
    public JSONObject runTestWithRetry(JSONObject request, Logger testLogger) throws Exception {
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

        JSONObject lastResult = null;
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

            lastResult = runTest(requestWithAttempt, testLogger);
            String status = lastResult.optString("status", "");

            // Collect log file metadata
            if (lastResult.has("logFilePath")) {
                Path logPath = Paths.get(lastResult.getString("logFilePath"));
                attemptLogFiles.add(logPath);
                JSONObject attemptMeta = new JSONObject();
                attemptMeta.put("attempt", attempt);
                attemptMeta.put("logFileName", lastResult.optString("logFileName", logPath.getFileName().toString()));
                attemptMeta.put("status", status);
                attemptLogMetadata.put(attemptMeta);
                lastResult.remove("logFilePath");
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
            if ("started".equalsIgnoreCase(status)) {
                lastResult.put("attempts", attempt);
                lastResult.put("attemptLogFiles", attemptLogFiles);
                lastResult.put("attemptLogMetadata", attemptLogMetadata);
                // Note: keepAlive mode doesn't check for flakiness since it's not meant for testing
                return lastResult;
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
                    lastResult.put("attempts", attempt);
                    lastResult.put("flaky", true);
                    lastResult.put("status", "flaky");  // Override status to indicate flakiness
                    testLogger.info("Test marked as flaky - saw both pass and failure in " + attempt + " attempts");
                    lastResult.put("attemptLogFiles", attemptLogFiles);
                    lastResult.put("attemptLogMetadata", attemptLogMetadata);
                    return lastResult;
                }
                // If we've only seen passes and we have sufficient data, exit as stable
                else if (!sawFailure && attempt >= minRuns) {
                    lastResult.put("attempts", attempt);
                    testLogger.info("Test appears stable - only passes in " + attempt + " attempts");
                    lastResult.put("attemptLogFiles", attemptLogFiles);
                    lastResult.put("attemptLogMetadata", attemptLogMetadata);
                    return lastResult;
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
                    lastResult.put("attempts", attempt);
                    lastResult.put("flaky", true);
                    lastResult.put("status", "flaky");  // Override status to indicate flakiness
                    testLogger.info("Test marked as flaky in until-fail mode - saw both pass and failure in " + attempt + " attempts");
                    lastResult.put("attemptLogFiles", attemptLogFiles);
                    lastResult.put("attemptLogMetadata", attemptLogMetadata);
                    return lastResult;
                }
                // If we've only seen failures and we have sufficient data (at least 2 failures), exit as reproducible
                else if (!sawPass && attempt >= Math.max(2, minRuns)) {
                    lastResult.put("attempts", attempt);
                    testLogger.info("Test failure reproduced - only failures in " + attempt + " attempts (reproduce mode)");
                    lastResult.put("attemptLogFiles", attemptLogFiles);
                    lastResult.put("attemptLogMetadata", attemptLogMetadata);
                    return lastResult;
                }
                // If we've only seen passes + this failure, continue running to see if it's consistently failing now
                // If we've only seen one failure so far, continue to gather more data for confidence
                // (don't exit early - let it run more attempts to gather more data)
            }

            // fixed-runs ignores early exits 3) & 4), continue to next attempt
        }

        // Completed due to maxRuns or time budget
        lastResult.put("attempts", Math.max(1, attempt));
        lastResult.put("attemptLogFiles", attemptLogFiles);
        lastResult.put("attemptLogMetadata", attemptLogMetadata);
        lastResult.put("runMode", runMode);
        
        // Synchronously verify all log files are accessible before sending response
        verifyAllLogFilesAccessible(attemptLogFiles, testLogger);

        // Check for flakiness when completing without early exit
        if (runMode.equals("until-pass") && sawFailure && sawPass) {
            lastResult.put("flaky", true);
            lastResult.put("status", "flaky");  // Override status to indicate flakiness
            testLogger.info("Test marked as flaky - saw both pass and failure across " + attempt + " attempts");
        }

        if (runMode.equals("until-fail")) {
            // Check for flakiness first
            if (sawFailure && sawPass) {
                lastResult.put("flaky", true);
                lastResult.put("status", "flaky");  // Override status to indicate flakiness
                testLogger.info("Test marked as flaky in until-fail mode - saw both pass and failure");
            }
            // Only set "could not reproduce" summary if we didn't see any failures at all
            else if (!sawFailure) {
                testLogger.info("Test did not fail after " + attempt + " attempts (reproduce mode)");
                lastResult.put("summary", "Could not reproduce failure after " + attempt + " attempts");
            }
        } else if (runMode.equals("fixed-runs")) {
            testLogger.info("Completed " + attempt + " run(s)");
            lastResult.put("summary", "Completed " + attempt + " runs");
            // Check for flakiness in fixed-runs mode too
            if (sawFailure && sawPass) {
                lastResult.put("flaky", true);
                lastResult.put("status", "flaky");  // Override status to indicate flakiness
                testLogger.info("Test marked as flaky in fixed-runs mode - saw both pass and failure");
            }
        }

        return lastResult;
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

    private JSONObject runTest(JSONObject requestJson, Logger testLogger) throws Exception {
        TestRequest request = new TestRequest(requestJson);
        Path workDir = Files.createTempDirectory(Paths.get(config.getWorkDir()), "test_");
        testLogger.info("Working directory: " + workDir);
        
        boolean keepAliveRequested = request.isKeepAlive();
        try {
            // Check if we should use Docker for test execution
            if (useDocker && dockerManager != null && isDockerAvailable()) {
                return executeDockerTest(request, workDir, testLogger);
            } else {
                testLogger.info("Using direct test execution");
                return executeDirectTest(request, workDir, testLogger);
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

    private JSONObject executeDockerTest(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        // Try to use optimized Docker execution with pre-built images
        if (optimizedDockerExecutor != null && config.isOptimizedDockerEnabled()) {
            try {
                testLogger.info("Attempting optimized Docker execution with pre-built image...");
                TestResult result = optimizedDockerExecutor.execute(request, workDir, testLogger);
                return convertToJSONObject(result);
            } catch (OptimizedDockerExecutor.ImageBuildInProgressException e) {
                testLogger.info("Optimized Docker image is still building; falling back to standard execution for now.");
                // Fall through to standard execution once the warm image is ready
            } catch (Exception e) {
                testLogger.warning("Optimized Docker execution failed, falling back to standard: " + e.getMessage());
                // Fall through to standard execution
            }
        }
        
        // Standard Docker execution
        try {
            TestResult result = standardDockerExecutor.execute(request, workDir, testLogger);
            return convertToJSONObject(result);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Docker not available")) {
                testLogger.warning("Docker not available, falling back to direct execution");
                return executeDirectTest(request, workDir, testLogger);
            }
            throw e;
        }
    }

    private JSONObject executeDirectTest(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        TestResult result = directExecutor.execute(request, workDir, testLogger);
        return convertToJSONObject(result);
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
}