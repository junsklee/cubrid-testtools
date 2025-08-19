/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.logging.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.*;
import org.json.JSONObject;
import com.navercorp.cubridqa.builder.logging.*;

/**
 * Tester - Receives test requests from Builder and executes tests
 * 
 * This service runs on test nodes and executes shell tests with provided CUBRID builds.
 * It extracts the build, sets up the environment, runs the test, and returns pass/fail status.
 * 
 * Concurrent Test Execution:
 * - Each test runs in an isolated Docker container with its own workspace
 * - Test case directories are copied to prevent shared volume conflicts
 * - Database operations are isolated per container
 */
public class Tester {
    private static final Logger logger = Logger.getLogger(Tester.class.getName());
    
    private final BuilderConfig config;
    private final HttpServer server;
    private final DockerTesterManager dockerManager;
    private final boolean useDocker;
    private final DockerImageBuilder imageBuilder;  // Docker image builder for pre-installed builds
    // Prevent concurrent git operations on the shared shell testcases repo
    private static final Object SHELL_TC_SYNC_LOCK = new Object();
    
    // Cache for downloaded build packages to avoid re-downloading
    private static final Map<String, Path> buildPackageCache = new ConcurrentHashMap<>();
    private static final Object DOWNLOAD_LOCK = new Object();
    
    // Test execution status types
    public enum TestStatus {
        PASS("pass"),
        FAIL("fail"),
        EXECUTION_ERROR("execution_error"),
        ENVIRONMENT_ERROR("environment_error"),
        BUILD_ERROR("build_error");
        
        private final String value;
        TestStatus(String value) { this.value = value; }
        public String getValue() { return value; }
    }
    
    public Tester(BuilderConfig config) throws IOException {
        this.config = config;
        
        // Initialize logging infrastructure
        LogConfig logConfig = new LogConfig(
            config.getMaxRequestLogs(),
            10, // Tester doesn't manage tar files, but we need a value
            System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log",
            config.isRequestGroupingEnabled()
        );
        try {
            RequestLogManager.initialize(logConfig);
        } catch (IOException e) {
            logger.warning("Failed to initialize RequestLogManager: " + e.getMessage());
        }
        this.useDocker = config.useDockerForTester();
        this.dockerManager = useDocker ? new DockerTesterManager(config) : null;
        
        // Initialize Docker image builder for optimized test execution
        this.imageBuilder = useDocker ? new DockerImageBuilder(config) : null;
        
        // Create work directory if it doesn't exist
        File workDir = new File(config.getWorkDir());
        if (!workDir.exists()) {
            workDir.mkdirs();
        }
        
        // Initialize Docker if enabled
        if (useDocker && dockerManager != null) {
            try {
                logger.info("Initializing Docker tester environment...");
                dockerManager.initialize();
                logger.info("Docker tester environment ready");
            } catch (Exception e) {
                logger.warning("Docker initialization failed: " + e.getMessage());
            }
        }
        
        // Create HTTP server
        this.server = HttpServer.create(new InetSocketAddress(config.getTesterPort()), 0);
        this.server.createContext("/test", new TestRequestHandler());
        this.server.createContext("/health", new HealthCheckHandler());
        int maxThreads = Math.max(1, config.getMaxConcurrentTests());
        this.server.setExecutor(Executors.newFixedThreadPool(maxThreads));
    }
    
    public void start() {
        server.start();
        logger.info("Tester started on port " + config.getTesterPort());
        logger.info("Work directory: " + config.getWorkDir());
        logger.info("Docker mode: " + (useDocker ? "ENABLED" : "DISABLED"));
        if (useDocker) {
            logger.info("Docker test image: " + config.getDockerTestImage());
        }
    }
    
    public void stop() {
        server.stop(0);
        logger.info("Tester stopped");
    }
    
    private class TestRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Logger requestLogger = logger;  // Default to system logger

            logger.info("Received " + exchange.getRequestMethod() + " request");

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            JSONObject responsePayload = null;
            int httpStatus = 200;

            try {
                String requestBody = readRequestBody(exchange);
                JSONObject request = new JSONObject(requestBody);

                // Extract request ID if provided
                String requestId = request.optString("requestId", null);
                if (requestId != null) {
                    RequestContext.setRequestId(requestId);

                    // Try to get request-specific logger
                    try {
                        if (config.isRequestGroupingEnabled()) {
                            requestLogger = RequestLogManager.getInstance().getRequestLogger(requestId, "tester");
                        }
                    } catch (Exception e) {
                        logger.warning("Failed to create request logger: " + e.getMessage());
                    }
                }

                requestLogger.info("Test request for: " + request.getString("testPath") +
                           (request.has("requestId") ? " [" + request.optString("requestId") + "]" : ""));
                requestLogger.info("Build package: " + request.getString("buildPackage"));

                // Run test with retry using request logger
                responsePayload = runTestWithRetry(request, requestLogger);
                httpStatus = 200;

            } catch (Exception e) {
                // Only errors that occur before generating the result should reach here
                logger.log(Level.SEVERE, "Error processing test request (pre-response)", e);
                responsePayload = new JSONObject()
                    .put("status", TestStatus.EXECUTION_ERROR.getValue())
                    .put("message", e.getMessage());
                httpStatus = 500;
            } finally {
                // Nothing here yet; we still need to attempt to send the response below
            }

            // Try to send the response once. If client disconnected (broken pipe), just log and do not overwrite result.
            if (responsePayload == null) {
                responsePayload = new JSONObject()
                    .put("status", TestStatus.EXECUTION_ERROR.getValue())
                    .put("message", "No result generated");
                httpStatus = 500;
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                sendResponse(exchange, httpStatus, responsePayload.toString());
                logger.info("Sent response: " + responsePayload.toString());
            } catch (IOException ioe) {
                String msg = ioe.getMessage() == null ? "" : ioe.getMessage();
                if (isClientAbort(ioe) || msg.contains("insufficient bytes written")) {
                    // Client (likely Builder) closed connection early. Do not treat as test failure.
                    logger.warning("Client disconnected before response was fully sent. Result was: " + responsePayload.toString());
                } else {
                    logger.log(Level.SEVERE, "Failed to send response", ioe);
                }
            } finally {
                RequestContext.clear();
            }
        }
    }
    
    /**
     * Run a test with retry logic. Retries on FAIL, EXECUTION_ERROR, and ENVIRONMENT_ERROR.
     * Returns immediately for PASS or STARTED (keepAlive mode) or for BUILD_ERROR.
     */
    private JSONObject runTestWithRetry(JSONObject request, Logger testLogger) throws Exception {
        // retry_count is number of retries; total attempts = 1 + retries. If 0 (default), run once with no retries.
        int totalAttempts = 1 + Math.max(0, config.getTestRetryCount());
        JSONObject lastResult = null;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            if (totalAttempts > 1) {
                testLogger.info("Running test attempt " + attempt + "/" + totalAttempts);
            }
            
            // Add attempt number to request for logging purposes
            JSONObject requestWithAttempt = new JSONObject(request.toString());
            requestWithAttempt.put("attemptNumber", attempt);
            
            lastResult = runTest(requestWithAttempt, testLogger);
            String status = lastResult.optString("status", "");
            // For keepAlive=true Docker runs, we get 'started' — no further retries
            if ("started".equalsIgnoreCase(status)) {
                lastResult.put("attempts", attempt);
                return lastResult;
            }
            if (TestStatus.PASS.getValue().equalsIgnoreCase(status)) {
                lastResult.put("attempts", attempt);
                // Mark as flaky if it passed after retries
                if (attempt > 1) {
                    lastResult.put("flaky", true);
                    testLogger.info("Test marked as flaky - passed after " + attempt + " attempts");
                }
                return lastResult;
            }
            
            // Retry on FAIL, EXECUTION_ERROR, and ENVIRONMENT_ERROR
            boolean shouldRetry = TestStatus.FAIL.getValue().equalsIgnoreCase(status) ||
                                TestStatus.EXECUTION_ERROR.getValue().equalsIgnoreCase(status) ||
                                TestStatus.ENVIRONMENT_ERROR.getValue().equalsIgnoreCase(status);
            
            if (!shouldRetry) {
                // Don't retry on BUILD_ERROR or unknown statuses
                lastResult.put("attempts", attempt);
                return lastResult;
            }
            
            if (attempt < totalAttempts) {
                testLogger.info("Test " + status + " on attempt " + attempt + ", retrying...");
                continue;
            }
            // Last attempt, return as is with attempts
            lastResult.put("attempts", attempt);
            return lastResult;
        }
        // Safety fallback
        return lastResult != null ? lastResult : new JSONObject().put("status", TestStatus.EXECUTION_ERROR.getValue()).put("message", "No result");
    }

    private class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                JSONObject healthResponse = new JSONObject()
                    .put("status", "healthy")
                    .put("service", "Tester")
                    .put("timestamp", System.currentTimeMillis())
                    .put("workDir", config.getWorkDir())
                    .put("dockerEnabled", useDocker)
                    .put("maxConcurrentTests", Math.max(1, config.getMaxConcurrentTests()))
                    .put("testReadTimeoutMinutes", config.getTestReadTimeoutMinutes())
                    .put("retryCount", config.getTestRetryCount());
                
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                sendResponse(exchange, 200, healthResponse.toString());
                
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in health check", e);
                JSONObject error = new JSONObject()
                    .put("status", "error")
                    .put("message", e.getMessage());
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                sendResponse(exchange, 500, error.toString());
            }
        }
    }
    
    private JSONObject runTest(JSONObject request) throws Exception {
        return runTest(request, logger);  // Use system logger by default
    }
    
    private JSONObject runTest(JSONObject request, Logger testLogger) throws Exception {
        Path workDir = Files.createTempDirectory(Paths.get(config.getWorkDir()), "test_");
        testLogger.info("Working directory: " + workDir);
        boolean keepAliveRequested = request.optBoolean("keepAlive", config.getKeepFailedContainers());
        try {
            // Check if we should use Docker for test execution
            if (useDocker && dockerManager != null && DockerUtils.isDockerAvailable()) {
                return runTestInDocker(request, workDir, testLogger);
            } else {
                testLogger.info("Using direct test execution");
                return runTestDirectly(request, workDir, testLogger);
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
    
    private JSONObject runTestInDocker(JSONObject request, Path workDir) throws Exception {
        return runTestInDocker(request, workDir, logger);
    }
    
    private JSONObject runTestInDocker(JSONObject request, Path workDir, Logger testLogger) throws Exception {
        // Try to use optimized Docker execution with pre-built images
        if (imageBuilder != null && config.isOptimizedDockerEnabled()) {
            try {
                testLogger.info("Attempting optimized Docker execution with pre-built image...");
                return runTestInDockerOptimized(request, workDir, testLogger);
            } catch (Exception e) {
                testLogger.warning("Optimized Docker execution failed, falling back to standard: " + e.getMessage());
                // Fall through to standard execution
            }
        }
        
        // Standard Docker execution (original implementation)
        testLogger.info("Running test in Docker container...");
        
        String buildPackage = request.getString("buildPackage");
        String testDir = request.getString("testDir");
        String testScript = request.getString("testScript");
        String testName = request.getString("testName");
        String expectedBuildVersion = request.optString("expectedBuildVersion", null);
        String commit = request.optString("commit", "unknown");
        String commitShort = request.optString("commitShort", commit.substring(0, Math.min(commit.length(), 7)));
        boolean keepAlive = request.optBoolean("keepAlive", config.getKeepFailedContainers());
        String containerName = request.optString("containerName", "tester_debug_" + testName.replaceAll("[^a-zA-Z0-9_.-]", "_") + "_" + System.currentTimeMillis());
        if (containerName == null || containerName.trim().isEmpty()) {
            containerName = "tester_debug_" + testName.replaceAll("[^a-zA-Z0-9_.-]", "_") + "_" + System.currentTimeMillis();
        }
        
        Path dockerWorkDir = Files.createTempDirectory(workDir, "docker_");
        testLogger.info("Docker work dir: " + dockerWorkDir.toString());
        
        // Download build package if it's a URL (use shared cache dir to avoid per-test races)
        Path localBuildPackage;
        try {
            Path sharedCacheDir = Paths.get(config.getWorkDir(), "cache");
            try { Files.createDirectories(sharedCacheDir); } catch (Exception ignore) {}
            localBuildPackage = downloadBuildPackageIfNeeded(buildPackage, sharedCacheDir, testLogger);
        } catch (IOException e) {
            return new JSONObject()
                .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                .put("message", "Failed to download build package: " + e.getMessage())
                .put("test", testName);
        }
        if (localBuildPackage == null || !Files.exists(localBuildPackage)) {
            return new JSONObject()
                .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                .put("message", "Downloaded build package missing: " + String.valueOf(localBuildPackage))
                .put("test", testName);
        }

        // Ensure shell testcases repository is on the requested branch from preferred remote
        try {
            syncShellTestcasesRepo(testLogger);
        } catch (Exception e) {
            testLogger.warning("Failed to sync shell testcases repo: " + e.getMessage());
        }
        
        // Copy build package from cache to Docker work directory
        Path dockerBuildPackage = dockerWorkDir.resolve("build.tar.gz");
        if (!localBuildPackage.equals(dockerBuildPackage)) {
            try {
                Files.copy(localBuildPackage, dockerBuildPackage, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                return new JSONObject()
                    .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                    .put("message", "Failed to stage build package for Docker: " + e.getMessage())
                    .put("test", testName);
            }
        }
        
        // Resolve and copy test case directory to isolated workspace
        Path testCasesDir = dockerWorkDir.resolve("testcases");
        Files.createDirectories(testCasesDir);
        Path sourceTestDir = Paths.get(testDir);
        if (!Files.exists(sourceTestDir)) {
            String testPathFull = request.optString("testPath", null);
            if (testPathFull != null && testPathFull.contains("/")) {
                String relDir = testPathFull.substring(0, testPathFull.lastIndexOf("/"));
                Path fallbackDir = Paths.get(config.getShellTcDir(), relDir);
                if (Files.exists(fallbackDir)) {
                    testLogger.warning("Provided testDir not found on tester; using fallback: " + fallbackDir.toString());
                    sourceTestDir = fallbackDir;
                } else {
                    return new JSONObject()
                        .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                        .put("message", "Test directory not found on tester: " + sourceTestDir.toString())
                        .put("test", testName);
                }
            } else {
                return new JSONObject()
                    .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                    .put("message", "Invalid testPath; cannot resolve test directory")
                    .put("test", testName);
            }
        }
        if (!Files.isReadable(sourceTestDir)) {
            return new JSONObject()
                .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                .put("message", "Permission denied reading test directory: " + sourceTestDir)
                .put("test", testName);
        }
        try {
            copyTestCaseDirectory(sourceTestDir, testCasesDir);
        } catch (IOException e) {
            return new JSONObject()
                .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                .put("message", "Failed to copy test cases: " + e.getMessage())
                .put("test", testName);
        }
        testLogger.info("Copied test case directory to isolated workspace: " + testCasesDir);
        
        // Create test execution script for Docker (now using isolated testcases dir)
        String dockerScript = createDockerTestScript(testScript, testName, 
                                                     expectedBuildVersion, "");
        Path dockerScriptPath = dockerWorkDir.resolve("run_test.sh");
        Files.write(dockerScriptPath, dockerScript.getBytes());
        dockerScriptPath.toFile().setExecutable(true);
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                String safeTestNameForScript = testName.replaceAll("[^a-zA-Z0-9_.-]", "_");
                // Include commit in the script name for uniqueness
                Path scriptLogPath = Paths.get(testsDir, String.format("docker_script_%s_%s.sh", commitShort, safeTestNameForScript));
                Files.write(scriptLogPath, dockerScript.getBytes("UTF-8"));
                testLogger.info("Saved generated Docker test script to: " + scriptLogPath.toString());
            }
        } catch (Exception ignore) { }
        
        // Check for GitHub token
        String githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.trim().isEmpty()) {
            testLogger.severe("GITHUB_TOKEN not set");
            return new JSONObject()
                .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                .put("message", "GITHUB_TOKEN environment variable not configured")
                .put("test", testName);
        }
        
        // Run Docker container
        List<String> dockerCommand = new ArrayList<>();
        dockerCommand.add("docker");
        dockerCommand.add("run");
        if (keepAlive) {
            dockerCommand.add("-d");
            dockerCommand.add("--name");
            dockerCommand.add(containerName);
        } else {
            dockerCommand.add("--rm");
        }
        dockerCommand.add("-v");
        dockerCommand.add(dockerWorkDir.toString() + ":/workspace");
        dockerCommand.add("-v");
        dockerCommand.add(System.getProperty("user.home") + "/cubrid-testtools:/home/cubrid-testtools:ro");
        // No longer mounting the entire testcases directory - using isolated copy instead
        dockerCommand.add("-e");
        dockerCommand.add("GITHUB_TOKEN=" + githubToken);
        dockerCommand.add("-e");
        dockerCommand.add("CTP_HOME=/home/cubrid-testtools/CTP");
        dockerCommand.add("-e");
        dockerCommand.add("init_path=/home/cubrid-testtools/CTP/shell/init_path");
        dockerCommand.add("-w");
        dockerCommand.add("/workspace");
        if (keepAlive) {
            dockerCommand.add("--entrypoint");
            dockerCommand.add("bash");
            dockerCommand.add(config.getDockerTestImage());
            dockerCommand.add("-lc");
            dockerCommand.add("/workspace/run_test.sh; echo READY; tail -f /dev/null");
        } else {
            dockerCommand.add("--entrypoint");
            dockerCommand.add("bash");
            dockerCommand.add(config.getDockerTestImage());
            dockerCommand.add("-lc");
            dockerCommand.add("/workspace/run_test.sh");
        }
        
        testLogger.info("Executing Docker command: " + String.join(" ", dockerCommand));
        
        ProcessBuilder pb = new ProcessBuilder(dockerCommand);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        if (keepAlive) {
            try { Files.createFile(workDir.resolve("KEEP_WORKSPACE")); } catch (Exception ignore) {}
            // For detached container, read quick output then return handle for exec
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                StringBuilder out = new StringBuilder();
                while ((line = br.readLine()) != null) {
                    out.append(line).append('\n');
                }
                testLogger.info("Started debug container: " + containerName);
            } catch (Exception ignore) {}
            String execCmd = "docker exec -it " + containerName + " bash";
            return new JSONObject()
                .put("status", "started")
                .put("test", testName)
                .put("containerName", containerName)
                .put("execCommand", execCmd)
                .put("workspace", dockerWorkDir.toString());
        }
        
        StreamReader outputGobbler = new StreamReader(process.getInputStream(), "DOCKER");
        outputGobbler.start();
        boolean completed = process.waitFor(30, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            testLogger.severe("Docker test timeout");
            return new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", "Docker test timeout after 30 minutes")
                .put("test", testName);
        }
        int exitCode = process.exitValue();
        outputGobbler.join(2000);
        String dockerOutput = outputGobbler.getOutput();
        testLogger.info("Docker test completed with exit code: " + exitCode);

        // Persist full docker output for diagnostics
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                String safeTestName = testName.replaceAll("[^a-zA-Z0-9_.-]", "_");
                // Include commit and attempt number in the log file name for uniqueness
                int attemptNumber = request.optInt("attemptNumber", 1);
                String logFileName;
                if (attemptNumber == 1) {
                    logFileName = String.format("docker_%s_%s.log", commitShort, safeTestName);
                } else {
                    logFileName = String.format("docker_%s_%s.%d.log", commitShort, safeTestName, attemptNumber);
                }
                Path logFile = Paths.get(testsDir, logFileName);
                Files.write(logFile, dockerOutput.getBytes("UTF-8"));
                testLogger.info("Saved full docker test log to: " + logFile.toString());
            }
        } catch (Exception ignore) {
            // Swallow logging persistence issues; primary result below still returned
        }
 
         // Check for result file derived from testScript base name
         String resultBaseFromScript = testScript.endsWith(".sh")
             ? testScript.substring(0, testScript.length() - 3)
             : (testScript.contains(".") ? testScript.substring(0, testScript.lastIndexOf('.')) : testScript);
         Path namedResult = dockerWorkDir.resolve(resultBaseFromScript + ".result");
         if (Files.exists(namedResult)) {
             String resultContent = new String(Files.readAllBytes(namedResult));
             testLogger.info("Test result file (" + namedResult.getFileName() + "): " + resultContent.trim());
             
             // Extract execution time from result if available
             String executionTime = extractExecutionTime(resultContent);
             
             JSONObject response = new JSONObject()
                 .put("test", testName)
                 .put("commit", commit)
                 .put("commitShort", commitShort)
                 .put("execution_mode", "docker")
                 .put("timestamp", System.currentTimeMillis());
             
             if (executionTime != null) {
                 response.put("execution_time", executionTime);
             }
             
             if (resultContent.contains("NOK") || resultContent.contains("FAIL")) {
                 response.put("status", TestStatus.FAIL.getValue());
             } else if (resultContent.contains("OK") || resultContent.contains("PASS")) {
                 response.put("status", TestStatus.PASS.getValue());
             } else {
                 response.put("status", TestStatus.EXECUTION_ERROR.getValue())
                        .put("message", "Could not determine test result");
             }
             
             return response;
         }
        
        // Check for Docker-specific errors
        if (exitCode != 0) {
            if (dockerOutput.contains("docker: command not found")) {
                testLogger.warning("Docker not available, falling back to direct execution");
                return runTestDirectly(request, workDir);
            }
            // Keep container on failure if configured: rerun in detached mode for debugging
            if (config.getKeepFailedContainers()) {
                try { Files.createFile(workDir.resolve("KEEP_WORKSPACE")); } catch (Exception ignore) {}
                List<String> keepCmd = new ArrayList<>();
                keepCmd.add("docker"); keepCmd.add("run"); keepCmd.add("-d");
                keepCmd.add("--name"); keepCmd.add(containerName);
                keepCmd.add("-v"); keepCmd.add(dockerWorkDir.toString() + ":/workspace");
                keepCmd.add("-v"); keepCmd.add(System.getProperty("user.home") + "/cubrid-testtools:/home/cubrid-testtools:ro");
                keepCmd.add("-e"); keepCmd.add("GITHUB_TOKEN=" + githubToken);
                keepCmd.add("-e"); keepCmd.add("CTP_HOME=/home/cubrid-testtools/CTP");
                keepCmd.add("-e"); keepCmd.add("init_path=/home/cubrid-testtools/CTP/shell/init_path");
                keepCmd.add("-w"); keepCmd.add("/workspace");
                keepCmd.add("--entrypoint"); keepCmd.add("bash");
                keepCmd.add(config.getDockerTestImage());
                keepCmd.add("-lc"); keepCmd.add("echo READY; tail -f /dev/null");
                try { new ProcessBuilder(keepCmd).start().waitFor(5, TimeUnit.SECONDS); } catch (Exception ignore) {}
                testLogger.info("Failure container kept for debugging: " + containerName);
                String execCmd = "docker exec -it " + containerName + " bash";
                return new JSONObject()
                    .put("status", TestStatus.EXECUTION_ERROR.getValue())
                    .put("message", "Docker test execution failed; container kept for debugging")
                    .put("test", testName)
                    .put("commit", commit)
                    .put("commitShort", commitShort)
                    .put("containerName", containerName)
                    .put("execCommand", execCmd)
                    .put("workspace", dockerWorkDir.toString())
                    .put("timestamp", System.currentTimeMillis());
            }
            
            return new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", "Docker test execution failed")
                .put("test", testName)
                .put("commit", commit)
                .put("commitShort", commitShort)
                .put("exit_code", exitCode)
                .put("timestamp", System.currentTimeMillis());
        }
        
        return new JSONObject()
            .put("status", TestStatus.EXECUTION_ERROR.getValue())
            .put("message", "No clear test result")
            .put("test", testName);
    }
    
    private JSONObject runTestDirectly(JSONObject request, Path workDir) throws Exception {
        return runTestDirectly(request, workDir, logger);
    }
    
    private JSONObject runTestDirectly(JSONObject request, Path workDir, Logger testLogger) throws Exception {
        String buildPackage = request.getString("buildPackage");
        String testDir = request.getString("testDir");
        String testScript = request.getString("testScript");
        String testName = request.getString("testName");
        String expectedBuildVersion = request.optString("expectedBuildVersion", null);
        String commit = request.optString("commit", "unknown");
        String commitShort = request.optString("commitShort", commit.substring(0, Math.min(commit.length(), 7)));
        
        testLogger.info("Direct test execution");
        testLogger.info("  Build package: " + buildPackage);
        testLogger.info("  Test: " + testName);
        
        // Download build package if it's a URL
        Path localBuildPackage;
        try {
            localBuildPackage = downloadBuildPackageIfNeeded(buildPackage, workDir, testLogger);
        } catch (IOException e) {
            return new JSONObject()
                .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                .put("message", "Failed to download build package: " + e.getMessage())
                .put("test", testName);
        }
        if (localBuildPackage == null || !Files.exists(localBuildPackage)) {
            return new JSONObject()
                .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                .put("message", "Downloaded build package missing: " + String.valueOf(localBuildPackage))
                .put("test", testName);
        }

        // Ensure shell testcases repository is on the requested branch from preferred remote
        try {
            syncShellTestcasesRepo(testLogger);
        } catch (Exception e) {
            testLogger.warning("Failed to sync shell testcases repo: " + e.getMessage());
        }
        
        // Install CUBRID
        Path installDir = null;
        try {
            installDir = installCubrid(localBuildPackage.toString(), workDir, testLogger);
        } catch (Exception e) {
            testLogger.log(Level.SEVERE, "Failed to install CUBRID", e);
            return new JSONObject()
                .put("status", TestStatus.BUILD_ERROR.getValue())
                .put("message", "Failed to install CUBRID: " + e.getMessage())
                .put("test", testName);
        }

        // Set environment for CUBRID
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("CUBRID", installDir.toString());
        env.put("CUBRID_DATABASES", installDir.resolve("databases").toString());
        env.put("PATH", installDir.resolve("bin") + ":" + System.getenv("PATH"));
        env.put("LD_LIBRARY_PATH", installDir.resolve("lib") + ":" + 
                System.getenv().getOrDefault("LD_LIBRARY_PATH", ""));
        env.put("CUBRID_LANG", "en_US");
        env.put("CUBRID_CHARSET", "en_US");
        
        // Set CTP_HOME if available
        String ctpHome = findCTPHome();
        if (ctpHome != null) {
            env.put("CTP_HOME", ctpHome);
            env.put("init_path", ctpHome + "/shell/init_path");
            env.put("PATH", ctpHome + "/shell/init_path:" + env.get("PATH"));
        }
        
        // Ensure databases directory exists
        if (!Files.exists(installDir.resolve("databases"))) {
            Files.createDirectories(installDir.resolve("databases"));
        }
        
        // Resolve test directory on tester host
        Path sourceTestDir = Paths.get(testDir);
        if (!Files.exists(sourceTestDir)) {
            String testPathFull = request.optString("testPath", null);
            if (testPathFull != null && testPathFull.contains("/")) {
                String relDir = testPathFull.substring(0, testPathFull.lastIndexOf("/"));
                Path fallbackDir = Paths.get(config.getShellTcDir(), relDir);
                if (Files.exists(fallbackDir)) {
                    testLogger.warning("Provided testDir not found on tester; using fallback: " + fallbackDir.toString());
                    sourceTestDir = fallbackDir;
                } else {
                    return new JSONObject()
                        .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                        .put("message", "Test directory not found on tester: " + sourceTestDir.toString())
                        .put("test", testName);
                }
            } else {
                return new JSONObject()
                    .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                    .put("message", "Invalid testPath; cannot resolve test directory")
                    .put("test", testName);
            }
        }

        // Run the test
        String resultBaseFromScript = testScript.endsWith(".sh")
            ? testScript.substring(0, testScript.length() - 3)
            : (testScript.contains(".") ? testScript.substring(0, testScript.lastIndexOf('.')) : testScript);
        File namedResultFileObj = new File(sourceTestDir.toFile(), resultBaseFromScript + ".result");
        testLogger.info("Running test: " + testScript);
        if (namedResultFileObj.exists()) namedResultFileObj.delete();
        
        // Create wrapper script
        String wrapperScript = createTestWrapperScript(sourceTestDir.toString(), testScript, testName, ctpHome);
        File wrapperFile = new File(workDir.toFile(), "test_wrapper.sh");
        Files.write(wrapperFile.toPath(), wrapperScript.getBytes());
        wrapperFile.setExecutable(true);
        
        // Run test
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(sourceTestDir.toFile());
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.command("bash", wrapperFile.getAbsolutePath());
        
        Process process = pb.start();
        
        StreamReader outputGobbler = new StreamReader(process.getInputStream(), "OUTPUT");
        StreamReader errorGobbler = new StreamReader(process.getErrorStream(), "ERROR");
        outputGobbler.start();
        errorGobbler.start();
        
        boolean completed = process.waitFor(30, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            testLogger.severe("Test timeout");
            return new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", "Test timeout after 30 minutes")
                .put("test", testName);
        }
        
        int exitCode = process.exitValue();
        outputGobbler.join(2000);
        errorGobbler.join(2000);
        
        String testOutput = outputGobbler.getOutput();
        String testError = errorGobbler.getOutput();
        
        // Check for execution errors
        if (exitCode != 0 && (testError.contains("command not found") || 
                              testError.contains("syntax error"))) {
            testLogger.severe("Test execution error: " + testError);
            return new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", "Test script execution error")
                .put("test", testName)
                .put("exit_code", exitCode);
        }
        
        // Check named result first then nok.result
        if (namedResultFileObj.exists()) {
            String resultContent = new String(Files.readAllBytes(namedResultFileObj.toPath()));
            testLogger.info("Named Result (" + resultBaseFromScript + ".result): " + resultContent.trim());
            if (resultContent.contains("NOK") || resultContent.contains("FAIL")) {
                return new JSONObject().put("status", TestStatus.FAIL.getValue()).put("test", testName);
            } else if (resultContent.contains("OK") || resultContent.contains("PASS")) {
                return new JSONObject().put("status", TestStatus.PASS.getValue()).put("test", testName);
            }
        }
        
        return new JSONObject()
            .put("status", TestStatus.EXECUTION_ERROR.getValue())
            .put("message", "No result file generated")
            .put("test", testName)
            .put("exit_code", exitCode);
    }
    
    private Path installCubrid(String buildPackage, Path workDir, Logger testLogger) 
            throws IOException, InterruptedException {
        testLogger.info("Installing CUBRID from: " + buildPackage);
        
        Path cubridInstallDir = Paths.get(System.getProperty("user.home"), "CUBRID");
        
        // Remove existing installation
        if (Files.exists(cubridInstallDir)) {
            testLogger.info("Removing existing CUBRID installation");
            deleteDirectory(cubridInstallDir.toFile());
        }
        
        // Create new installation directory
        Files.createDirectories(cubridInstallDir);
        
        // Extract the build package
        testLogger.info("Extracting to: " + cubridInstallDir);
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", buildPackage, 
                                              "-C", cubridInstallDir.toString());
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        StreamReader outputGobbler = new StreamReader(process.getInputStream(), "EXTRACT");
        outputGobbler.start();
        
        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("CUBRID extraction timeout");
        }
        
        int exitCode = process.exitValue();
        outputGobbler.join(1000);
        
        if (exitCode != 0) {
            String output = outputGobbler.getOutput();
            throw new IOException("CUBRID extraction failed: " + exitCode);
        }
        
        testLogger.info("CUBRID extraction completed");
        
        // Find actual CUBRID directory
        Path actualCubridDir = findCubridBinaries(cubridInstallDir);
        
        if (actualCubridDir == null) {
            throw new IOException("Could not find CUBRID binaries");
        }
        
        testLogger.info("CUBRID binaries found at: " + actualCubridDir);
        
        // Try running setup.sh if available to configure installation
        try {
            Path setupScript = actualCubridDir.resolve("share/scripts/setup.sh");
            if (!Files.exists(setupScript)) {
                setupScript = actualCubridDir.resolve("setup.sh");
            }
            if (Files.exists(setupScript)) {
                testLogger.info("Running setup.sh to configure CUBRID installation");
                ProcessBuilder setupPb = new ProcessBuilder("sh", setupScript.toString(), actualCubridDir.toString());
                setupPb.directory(actualCubridDir.toFile());
                setupPb.redirectErrorStream(true);
                Map<String, String> setupEnv = setupPb.environment();
                setupEnv.put("CUBRID", actualCubridDir.toString());
                setupEnv.put("CUBRID_DATABASES", actualCubridDir.resolve("databases").toString());
                setupEnv.put("PATH", actualCubridDir.resolve("bin") + ":" + System.getenv("PATH"));
                setupEnv.put("LD_LIBRARY_PATH", actualCubridDir.resolve("lib") + ":" +
                        System.getenv().getOrDefault("LD_LIBRARY_PATH", ""));
                Process setupProc = setupPb.start();
                StreamReader setupOutput = new StreamReader(setupProc.getInputStream(), "SETUP");
                setupOutput.start();
                boolean setupCompleted = setupProc.waitFor(2, TimeUnit.MINUTES);
                if (!setupCompleted) {
                    setupProc.destroyForcibly();
                    testLogger.warning("setup.sh timeout after 2 minutes, continuing anyway");
                } else if (setupProc.exitValue() != 0) {
                    setupOutput.join(1000);
                    testLogger.warning("setup.sh exited with code: " + setupProc.exitValue());
                } else {
                    testLogger.info("setup.sh completed successfully");
                }
            } else {
                testLogger.info("setup.sh not found, skipping setup");
            }
        } catch (Exception e) {
            testLogger.warning("setup.sh execution failed: " + e.getMessage());
        }

        // Ensure databases directory exists
        Path databasesDir = actualCubridDir.resolve("databases");
        if (!Files.exists(databasesDir)) {
            Files.createDirectories(databasesDir);
        }
        
        return actualCubridDir;
    }
    
    private Path findCubridBinaries(Path extractionDir) throws IOException {
        Path[] candidatePaths = {
            extractionDir,
            extractionDir.resolve("CUBRID"),
            extractionDir.resolve("cubrid"),
            extractionDir.resolve("install")
        };
        
        for (Path candidate : candidatePaths) {
            if (Files.exists(candidate)) {
                Path cubridRel = candidate.resolve("bin/cubrid_rel");
                if (Files.exists(cubridRel) && Files.isExecutable(cubridRel)) {
                    return candidate;
                }
            }
        }
        
        // Search in subdirectories
        try (java.util.stream.Stream<Path> paths = Files.walk(extractionDir, 3)) {
            java.util.Optional<Path> cubridRel = paths
                .filter(path -> path.getFileName().toString().equals("cubrid_rel"))
                .filter(Files::isExecutable)
                .findFirst();
                
            if (cubridRel.isPresent()) {
                return cubridRel.get().getParent().getParent();
            }
        }
        
        return null;
    }
    
    private String findCTPHome() {
        String ctpHome = System.getenv("CTP_HOME");
        if (ctpHome != null && new File(ctpHome).exists()) {
            return ctpHome;
        }
        
        // Try common locations
        String[] commonPaths = {
            System.getProperty("user.home") + "/cubrid-testtools/CTP"
        };
        
        for (String path : commonPaths) {
            File ctpDir = new File(path);
            if (ctpDir.exists() && new File(ctpDir, "shell").exists()) {
                return path;
            }
        }
        
        return System.getProperty("user.home") + "/cubrid-testtools/CTP";
    }
    
    private String createTestWrapperScript(String testDir, String testScript, 
                                          String testName, String ctpHome) {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("set -e\n\n");
        
        script.append("# Set up environment\n");
        script.append("export CTP_HOME=\"").append(ctpHome).append("\"\n");
        script.append("export PATH=\"$CTP_HOME/shell/init_path:$PATH\"\n");
        script.append("export PATH=\"$HOME/CUBRID/bin:$PATH\"\n");
        script.append("export LD_LIBRARY_PATH=\"$HOME/CUBRID/lib:$LD_LIBRARY_PATH\"\n\n");
        
        script.append("# Change to test directory\n");
        script.append("cd \"").append(testDir).append("\"\n\n");
        
        script.append("# Source shell test framework if available\n");
        script.append("if [ -f \"$CTP_HOME/shell/init_path/init.sh\" ]; then\n");
        script.append("    source \"$CTP_HOME/shell/init_path/init.sh\"\n");
        script.append("fi\n\n");
        
        script.append("# Execute test\n");
        script.append("bash \"").append(testScript).append("\"\n");
        script.append("TEST_EXIT_CODE=$?\n\n");
        
        script.append("exit $TEST_EXIT_CODE\n");
        
        return script.toString();
    }
    
    private String createDockerTestScript(String testScript, String testName, 
                                           String expectedBuildVersion, String relativeTestDir) {
         StringBuilder script = new StringBuilder();
         script.append("#!/bin/bash\n");
         script.append("set -e\n");
         script.append("set -x\n\n");
         
         script.append("# Extract CUBRID build\n");
         script.append("echo \"Extracting CUBRID build...\"\n");
         script.append("mkdir -p /tmp/cubrid_install\n");
         script.append("cd /tmp/cubrid_install\n");
         script.append("tar -xzf /workspace/build.tar.gz\n");
         script.append("# Prefer packaged install layout\n");
         script.append("if [ -d /tmp/cubrid_install/_install/CUBRID ]; then\n");
         script.append("  CUBRID_ROOT=/tmp/cubrid_install/_install/CUBRID\n");
         script.append("else\n");
         script.append("  CUBRID_ROOT=$(find /tmp/cubrid_install -name \"bin\" -type d | head -1 | xargs dirname)\n");
         script.append("fi\n");
         script.append("if [ -z \"$CUBRID_ROOT\" ]; then\n");
         script.append("    echo \"ERROR: Could not find CUBRID installation\"\n");
         script.append("    exit 1\n");
         script.append("fi\n\n");
         
         script.append("# Run setup.sh to configure CUBRID if available (non-interactive)\n");
         script.append("if [ -f \"$CUBRID_ROOT/share/scripts/setup.sh\" ]; then\n");
         script.append("    echo \"Running setup.sh...\"\n");
         script.append("    cd \"$CUBRID_ROOT\"\n");
         script.append("    yes | sh share/scripts/setup.sh \"$CUBRID_ROOT\" || true\n");
         script.append("    cd -\n");
         script.append("elif [ -f \"$CUBRID_ROOT/setup.sh\" ]; then\n");
         script.append("    echo \"Running setup.sh...\"\n");
         script.append("    cd \"$CUBRID_ROOT\"\n");
         script.append("    yes | sh setup.sh \"$CUBRID_ROOT\" || true\n");
         script.append("    cd -\n");
         script.append("fi\n\n");
 
         script.append("# Set up CUBRID environment\n");
         script.append("# Source default env if created by setup\n");
         script.append("if [ -f /root/.cubrid.sh ]; then\n");
         script.append("  . /root/.cubrid.sh\n");
         script.append("fi\n");
         script.append("export CUBRID=\"$CUBRID_ROOT\"\n");
         script.append("export PATH=\"$CUBRID_ROOT/bin:/home/cubrid-testtools/CTP/shell/init_path:$PATH\"\n");
         script.append("export LD_LIBRARY_PATH=\"$CUBRID_ROOT/lib:$CUBRID_ROOT/cci/lib:$CUBRID_ROOT/lib64:$LD_LIBRARY_PATH\"\n");
         script.append("export CUBRID_LANG=\"en_US\"\n");
         script.append("export CUBRID_CHARSET=\"en_US\"\n");
         script.append("export CTP_HOME=\"/home/cubrid-testtools/CTP\"\n");
         script.append("export init_path=\"/home/cubrid-testtools/CTP/shell/init_path\"\n\n");

         script.append("# Emit debug env snapshot for docker exec sessions\n");
         script.append("cat > /workspace/debug_env.sh <<'EOS'\n");
         script.append("export CUBRID=\"$CUBRID_ROOT\"\n");
         script.append("export CUBRID_DATABASES=\"$CUBRID_ROOT/databases\"\n");
         script.append("export PATH=\"$CUBRID_ROOT/bin:$PATH\"\n");
         script.append("export LD_LIBRARY_PATH=\"$CUBRID_ROOT/lib:$CUBRID_ROOT/cci/lib:$CUBRID_ROOT/lib64:$LD_LIBRARY_PATH\"\n");
         script.append("export CTP_HOME=\"/home/cubrid-testtools/CTP\"\n");
         script.append("export init_path=\"/home/cubrid-testtools/CTP/shell/init_path\"\n");
         script.append("EOS\n");
         script.append("chmod +x /workspace/debug_env.sh\n\n");
         
         script.append("# Verify installation\n");
         script.append("echo \"Verifying CUBRID installation...\"\n");
         script.append("if ! cubrid_rel; then\n");
         script.append("    echo \"ERROR: CUBRID verification failed\"\n");
         script.append("    exit 1\n");
         script.append("fi\n\n");
         
         script.append("mkdir -p \"$CUBRID_DATABASES\"\n");
         script.append("# Ensure clean server log directory to avoid leftover state\n");
         script.append("mkdir -p \"$CUBRID/log/server\"\n");
         script.append("rm -f \"$CUBRID/log/server/*\" || true\n");
         script.append("# Reset databases registry so createdb uses this workspace\n");
         script.append(": > \"$CUBRID_DATABASES/databases.txt\"\n\n");
 
         // If expected build version provided, verify
         script.append("# Verify expected build version if provided\n");
         script.append("if [ -n \"");
         script.append(expectedBuildVersion != null ? expectedBuildVersion : "");
         script.append("\" ]; then\n");
         script.append("    INSTALLED_VER=$(cubrid_rel 2>/dev/null | head -1)\n");
         script.append("    echo \"Installed version: $INSTALLED_VER\"\n");
         if (expectedBuildVersion != null) {
             script.append("    if [[ \"$INSTALLED_VER\" != *\"");
             script.append(expectedBuildVersion);
             script.append("\"* ]]; then\n");
             script.append("        echo \"WARNING: Expected build version ");
             script.append(expectedBuildVersion);
             script.append(" not found in installed version\"\n");
             script.append("    fi\n");
         }
         script.append("fi\n\n");
         
         script.append("# Run test\n");
         script.append("cd /workspace/testcases\n");
         script.append("bash ").append(testScript).append("\n");
         script.append("TEST_EXIT=$?\n\n");
         
         // Determine result base name from test script (strip .sh)
         String resultBase = testScript.endsWith(".sh") ? 
             testScript.substring(0, testScript.length() - 3) :
             (testScript.contains(".") ? testScript.substring(0, testScript.lastIndexOf('.')) : testScript);
         script.append("# Copy result file if generated by test\n");
         script.append("if [ -f \"" + resultBase + ".result\" ]; then\n");
         script.append("    cp \"" + resultBase + ".result\" /workspace/\n");
         script.append("fi\n\n");
         
         script.append("exit $TEST_EXIT\n");
         
         return script.toString();
     }
    
    /**
     * Optimized Docker test execution using pre-built images with CUBRID already installed.
     * This eliminates the need to extract and setup CUBRID for each test.
     */
    private JSONObject runTestInDockerOptimized(JSONObject request, Path workDir, Logger testLogger) throws Exception {
        testLogger.info("Running test in optimized Docker container with pre-built image...");
        
        String buildPackage = request.getString("buildPackage");
        String testDir = request.getString("testDir");
        String testScript = request.getString("testScript");
        String testName = request.getString("testName");
        String expectedBuildVersion = request.optString("expectedBuildVersion", null);
        String commit = request.optString("commit", "unknown");
        String commitShort = request.optString("commitShort", commit.substring(0, Math.min(commit.length(), 7)));
        boolean keepAlive = request.optBoolean("keepAlive", config.getKeepFailedContainers());
        String containerName = request.optString("containerName", 
            "tester_opt_" + testName.replaceAll("[^a-zA-Z0-9_.-]", "_") + "_" + System.currentTimeMillis());
        
        Path dockerWorkDir = Files.createTempDirectory(workDir, "docker_");
        testLogger.info("Docker work dir: " + dockerWorkDir.toString());
        
        // Download build package if it's a URL
        Path localBuildPackage;
        try {
            Path sharedCacheDir = Paths.get(config.getWorkDir(), "cache");
            Files.createDirectories(sharedCacheDir);
            localBuildPackage = downloadBuildPackageIfNeeded(buildPackage, sharedCacheDir, testLogger);
        } catch (IOException e) {
            return new JSONObject()
                .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                .put("message", "Failed to download build package: " + e.getMessage())
                .put("test", testName);
        }
        
        // Build or get Docker image with CUBRID pre-installed
        String dockerImage;
        try {
            if (imageBuilder != null) {
                dockerImage = imageBuilder.getOrBuildImage(commitShort, localBuildPackage);
                testLogger.info("Using Docker image: " + dockerImage);
            } else {
                testLogger.info("Image builder not available, falling back to standard execution");
                throw new Exception("Image builder not available");
            }
        } catch (Exception e) {
            testLogger.warning("Failed to build optimized Docker image: " + e.getMessage());
            throw e;  // Let the calling method handle fallback
        }
        
        // Ensure shell testcases repository is on the requested branch
        try {
            syncShellTestcasesRepo(testLogger);
        } catch (Exception e) {
            testLogger.warning("Failed to sync shell testcases repo: " + e.getMessage());
        }
        
        // Copy test case directory to isolated workspace
        Path testCasesDir = dockerWorkDir.resolve("testcases");
        Files.createDirectories(testCasesDir);
        Path sourceTestDir = Paths.get(testDir);
        if (!Files.exists(sourceTestDir)) {
            String testPathFull = request.optString("testPath", null);
            if (testPathFull != null && testPathFull.contains("/")) {
                String relDir = testPathFull.substring(0, testPathFull.lastIndexOf("/"));
                Path fallbackDir = Paths.get(config.getShellTcDir(), relDir);
                if (Files.exists(fallbackDir)) {
                    testLogger.warning("Provided testDir not found; using fallback: " + fallbackDir);
                    sourceTestDir = fallbackDir;
                } else {
                    return new JSONObject()
                        .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                        .put("message", "Test directory not found: " + sourceTestDir)
                        .put("test", testName);
                }
            }
        }
        
        try {
            copyTestCaseDirectory(sourceTestDir, testCasesDir);
        } catch (IOException e) {
            return new JSONObject()
                .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                .put("message", "Failed to copy test cases: " + e.getMessage())
                .put("test", testName);
        }
        testLogger.info("Copied test case directory to isolated workspace");
        
        // Create simplified test script (no CUBRID extraction needed!)
        String dockerScript = createOptimizedDockerTestScript(testScript, testName, expectedBuildVersion);
        Path dockerScriptPath = dockerWorkDir.resolve("run_test.sh");
        Files.write(dockerScriptPath, dockerScript.getBytes());
        dockerScriptPath.toFile().setExecutable(true);
        
        // Save script for debugging
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                String safeTestName = testName.replaceAll("[^a-zA-Z0-9_.-]", "_");
                Path scriptLogPath = Paths.get(testsDir, 
                    String.format("docker_script_opt_%s_%s.sh", commitShort, safeTestName));
                Files.write(scriptLogPath, dockerScript.getBytes("UTF-8"));
                testLogger.info("Saved optimized Docker test script to: " + scriptLogPath);
            }
        } catch (Exception ignore) {}
        
        // Check for GitHub token
        String githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.trim().isEmpty()) {
            testLogger.severe("GITHUB_TOKEN not set");
            return new JSONObject()
                .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                .put("message", "GITHUB_TOKEN environment variable not configured")
                .put("test", testName);
        }
        
        // Run Docker container with optimized flags
        List<String> dockerCommand = new ArrayList<>();
        dockerCommand.add("docker");
        dockerCommand.add("run");
        if (keepAlive) {
            dockerCommand.add("-d");
            dockerCommand.add("--name");
            dockerCommand.add(containerName);
        } else {
            dockerCommand.add("--rm");
        }
        
        // Performance optimizations
        dockerCommand.add("--init");
        dockerCommand.add("--tmpfs");
        dockerCommand.add("/tmp:exec,size=2G");
        dockerCommand.add("--shm-size=2g");
        
        // Volume mounts
        dockerCommand.add("-v");
        dockerCommand.add(dockerWorkDir.toString() + ":/workspace");
        dockerCommand.add("-v");
        dockerCommand.add(System.getProperty("user.home") + "/cubrid-testtools:/home/cubrid-testtools:ro");
        
        // Environment variables
        dockerCommand.add("-e");
        dockerCommand.add("GITHUB_TOKEN=" + githubToken);
        dockerCommand.add("-e");
        dockerCommand.add("CTP_HOME=/home/cubrid-testtools/CTP");
        dockerCommand.add("-e");
        dockerCommand.add("init_path=/home/cubrid-testtools/CTP/shell/init_path");
        
        // Working directory
        dockerCommand.add("-w");
        dockerCommand.add("/workspace");
        
        // Override entrypoint to use bash instead of the base image entrypoint
        dockerCommand.add("--entrypoint");
        dockerCommand.add("bash");
        
        // Use the pre-built image
        dockerCommand.add(dockerImage);
        
        // Command to execute
        if (keepAlive) {
            dockerCommand.add("-lc");
            dockerCommand.add("/workspace/run_test.sh; echo READY; tail -f /dev/null");
        } else {
            dockerCommand.add("-lc");
            dockerCommand.add("/workspace/run_test.sh");
        }
        
        testLogger.info("Executing optimized Docker command: " + String.join(" ", dockerCommand));
        
        ProcessBuilder pb = new ProcessBuilder(dockerCommand);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        if (keepAlive) {
            try { Files.createFile(workDir.resolve("KEEP_WORKSPACE")); } catch (Exception ignore) {}
            String execCmd = "docker exec -it " + containerName + " bash";
            return new JSONObject()
                .put("status", "started")
                .put("test", testName)
                .put("containerName", containerName)
                .put("execCommand", execCmd)
                .put("workspace", dockerWorkDir.toString());
        }
        
        // Read output
        StreamReader outputGobbler = new StreamReader(process.getInputStream(), "DOCKER");
        outputGobbler.start();
        boolean completed = process.waitFor(30, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            testLogger.severe("Docker test timeout");
            return new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", "Docker test timeout after 30 minutes")
                .put("test", testName);
        }
        
        int exitCode = process.exitValue();
        outputGobbler.join(2000);
        String dockerOutput = outputGobbler.getOutput();
        testLogger.info("Docker test completed with exit code: " + exitCode);
        
        // Save output log
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                String safeTestName = testName.replaceAll("[^a-zA-Z0-9_.-]", "_");
                Path logFile = Paths.get(testsDir, 
                    String.format("docker_opt_%s_%s.log", commitShort, safeTestName));
                Files.write(logFile, dockerOutput.getBytes("UTF-8"));
                testLogger.info("Saved optimized Docker test log to: " + logFile);
            }
        } catch (Exception ignore) {}
        
        // Check for result file
        String resultBase = testScript.endsWith(".sh") ? 
            testScript.substring(0, testScript.length() - 3) : testScript;
        Path namedResult = dockerWorkDir.resolve(resultBase + ".result");
        
        if (Files.exists(namedResult)) {
            String resultContent = new String(Files.readAllBytes(namedResult));
            testLogger.info("Test result file content: " + resultContent);
            
            boolean isPassed = resultContent.contains("OK") || 
                              resultContent.toUpperCase().contains("PASS");
            boolean isFailed = resultContent.contains("NOK") || 
                              resultContent.toUpperCase().contains("FAIL");
            
            if (isPassed && !isFailed) {
                return new JSONObject()
                    .put("status", TestStatus.PASS.getValue())
                    .put("test", testName)
                    .put("commit", commit)
                    .put("commitShort", commitShort)
                    .put("execution_mode", "docker_optimized")
                    .put("timestamp", System.currentTimeMillis());
            } else if (isFailed) {
                return new JSONObject()
                    .put("status", TestStatus.FAIL.getValue())
                    .put("test", testName)
                    .put("commit", commit)
                    .put("commitShort", commitShort)
                    .put("execution_mode", "docker_optimized")
                    .put("timestamp", System.currentTimeMillis());
            }
        }
        
        // Check exit code
        if (exitCode == 0) {
            return new JSONObject()
                .put("status", TestStatus.PASS.getValue())
                .put("test", testName)
                .put("commit", commit)
                .put("commitShort", commitShort)
                .put("execution_mode", "docker_optimized")
                .put("timestamp", System.currentTimeMillis());
        } else {
            return new JSONObject()
                .put("status", TestStatus.FAIL.getValue())
                .put("test", testName)
                .put("commit", commit)
                .put("commitShort", commitShort)
                .put("execution_mode", "docker_optimized")
                .put("exit_code", exitCode)
                .put("timestamp", System.currentTimeMillis());
        }
    }
    
    /**
     * Create optimized Docker test script for pre-built images.
     * CUBRID is already installed in /opt/cubrid, so we just run the test.
     */
    private String createOptimizedDockerTestScript(String testScript, String testName, 
                                                   String expectedBuildVersion) {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("set -e\n");
        script.append("set -x\n\n");
        
        script.append("# CUBRID is pre-installed in /opt/cubrid\n");
        script.append("export CUBRID=/opt/cubrid\n");
        script.append("export CUBRID_DATABASES=/opt/cubrid/databases\n");
        script.append("export PATH=/opt/cubrid/bin:/home/cubrid-testtools/CTP/shell/init_path:$PATH\n");
        script.append("export LD_LIBRARY_PATH=/opt/cubrid/lib:/opt/cubrid/cci/lib:$LD_LIBRARY_PATH\n");
        script.append("export CUBRID_LANG=en_US\n");
        script.append("export CUBRID_CHARSET=en_US\n");
        script.append("export CTP_HOME=/home/cubrid-testtools/CTP\n");
        script.append("export init_path=/home/cubrid-testtools/CTP/shell/init_path\n\n");
        
        script.append("# Verify CUBRID installation\n");
        script.append("echo \"Verifying CUBRID installation...\"\n");
        script.append("cubrid_rel\n\n");
        
        // Verify expected build version if provided
        if (expectedBuildVersion != null && !expectedBuildVersion.isEmpty()) {
            script.append("# Verify expected build version\n");
            script.append("INSTALLED_VER=$(cubrid_rel 2>/dev/null | head -1)\n");
            script.append("echo \"Installed version: $INSTALLED_VER\"\n");
            script.append("if [[ \"$INSTALLED_VER\" != *\"").append(expectedBuildVersion).append("\"* ]]; then\n");
            script.append("    echo \"WARNING: Expected build version ").append(expectedBuildVersion);
            script.append(" not found in installed version\"\n");
            script.append("fi\n\n");
        }
        
        script.append("# Clean any previous database state\n");
        script.append("rm -rf /opt/cubrid/databases/*\n");
        script.append("mkdir -p /opt/cubrid/databases\n");
        script.append("touch /opt/cubrid/databases/databases.txt\n\n");
        
        script.append("# Run the test\n");
        script.append("cd /workspace/testcases\n");
        script.append("bash ").append(testScript).append("\n");
        script.append("TEST_EXIT=$?\n\n");
        
        // Copy result file to workspace
        String resultBase = testScript.endsWith(".sh") ? 
            testScript.substring(0, testScript.length() - 3) : testScript;
        script.append("# Copy result file if generated\n");
        script.append("if [ -f \"").append(resultBase).append(".result\" ]; then\n");
        script.append("    cp \"").append(resultBase).append(".result\" /workspace/\n");
        script.append("fi\n\n");
        
        script.append("exit $TEST_EXIT\n");
        
        return script.toString();
    }
    
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), "UTF-8"))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return body.toString();
        }
    }
    
    private class StreamReader extends Thread {
        private final InputStream is;
        private final String type;
        private final StringBuilder output = new StringBuilder();
        
        public StreamReader(InputStream is, String type) {
            this.is = is;
            this.type = type;
        }
        
        public String getOutput() {
            return output.toString();
        }
        
        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append("\n");
                    if (type.equals("ERROR") || type.equals("OUTPUT")) {
                        logger.info(type + ": " + line);
                    } else {
                        logger.fine(type + ": " + line);
                    }
                }
            } catch (IOException e) {
                logger.warning("Error reading " + type + " stream: " + e.getMessage());
            }
        }
    }
    
    /**
     * Extract execution time from test result content
     * Looking for patterns like "time=42" or "time: 42"
     */
    private String extractExecutionTime(String resultContent) {
        // Look for time pattern in result
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("time[=:]\\s*(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(resultContent);
        if (matcher.find()) {
            return matcher.group(1) + "s";
        }
        return null;
    }
    
    /**
     * Determines whether an IOException likely indicates the client disconnected
     * (e.g., broken pipe, connection reset) while we were writing the response.
     * Such cases should be logged but not treated as test execution failures.
     */
    private boolean isClientAbort(IOException ioe) {
        Throwable t = ioe;
        while (t != null) {
            if (t instanceof java.net.SocketException) {
                String m = t.getMessage();
                if (m != null && (m.contains("Broken pipe") || m.contains("Connection reset") || m.contains("reset by peer"))) {
                    return true;
                }
            }
            if (t instanceof java.nio.channels.ClosedChannelException) {
                return true;
            }
            t = t.getCause();
        }
        String msg = ioe.getMessage();
        return msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset") || msg.contains("reset by peer"));
    }
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, response.getBytes("UTF-8").length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes("UTF-8"));
        }
    }
    
    private void copyTestCaseDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(source) || !Files.isDirectory(source)) {
            throw new IOException("Source test directory does not exist: " + source);
        }
        Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                try {
                    Files.createDirectories(targetDir);
                } catch (IOException e) {
                    logger.warning("Failed to create directory: " + targetDir + " - " + e.getMessage());
                    throw e;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                try {
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    // Fail fast on permission errors to surface clear message
                    logger.warning("Failed to copy file: " + file + " - " + e.getMessage());
                    throw e;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                // Propagate AccessDeniedException or any IO error to caller for proper HTTP error response
                logger.warning("Visit failed: " + file + " - " + (exc != null ? exc.getMessage() : "unknown error"));
                throw exc != null ? exc : new IOException("Failed visiting: " + file);
            }
        });
    }
    
    /**
     * Download build package if it's a URL, otherwise return the local path.
     * Caches downloaded packages to avoid re-downloading.
     */
    private Path downloadBuildPackageIfNeeded(String buildPackage, Path workDir, Logger testLogger) 
            throws IOException {
        // Check if it's a URL
        if (buildPackage.startsWith("http://") || buildPackage.startsWith("https://")) {
            testLogger.info("Build package is a URL: " + buildPackage);
            
            // Check cache first
            Path cached = buildPackageCache.get(buildPackage);
            if (cached != null && Files.exists(cached)) {
                testLogger.info("Using cached build package: " + cached);
                return cached;
            }
            
            synchronized (DOWNLOAD_LOCK) {
                // Double-check cache after acquiring lock
                cached = buildPackageCache.get(buildPackage);
                if (cached != null && Files.exists(cached)) {
                    return cached;
                }
                
                // Download the package
                testLogger.info("Downloading build package from: " + buildPackage);
                URL url = new URL(buildPackage);
                String fileName = "build_" + System.currentTimeMillis() + ".tar.gz";
                
                // Try to extract filename from URL
                String urlPath = url.getPath();
                if (urlPath != null && !urlPath.isEmpty()) {
                    int lastSlash = urlPath.lastIndexOf('/');
                    if (lastSlash >= 0 && lastSlash < urlPath.length() - 1) {
                        fileName = urlPath.substring(lastSlash + 1);
                    }
                }
                
                Path downloadPath = workDir.resolve(fileName);
                
                try {
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(300000); // 5 minutes for large files
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        throw new IOException("Failed to download build package. HTTP response: " + responseCode);
                    }
                    
                    long contentLength = conn.getContentLengthLong();
                    testLogger.info("Downloading " + (contentLength > 0 ? contentLength / (1024*1024) + " MB" : "unknown size"));
                    
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = Files.newOutputStream(downloadPath)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long totalBytes = 0;
                        long lastLogTime = System.currentTimeMillis();
                        
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                            totalBytes += bytesRead;
                            
                            // Log progress every 5 seconds
                            long now = System.currentTimeMillis();
                            if (now - lastLogTime > 5000) {
                                if (contentLength > 0) {
                                    int percent = (int) ((totalBytes * 100) / contentLength);
                                    testLogger.info("Download progress: " + percent + "%");
                                } else {
                                    testLogger.info("Downloaded " + (totalBytes / (1024*1024)) + " MB");
                                }
                                lastLogTime = now;
                            }
                        }
                    }
                    
                    testLogger.info("Build package downloaded successfully: " + downloadPath);
                    
                    // Cache the downloaded package
                    buildPackageCache.put(buildPackage, downloadPath);
                    
                    // Clean old cached packages if cache is too large
                    if (buildPackageCache.size() > 10) {
                        cleanOldCachedPackages();
                    }
                    
                    return downloadPath;
                    
                } catch (Exception e) {
                    // Clean up partial download
                    try {
                        Files.deleteIfExists(downloadPath);
                    } catch (Exception ignore) {}
                    throw new IOException("Failed to download build package: " + e.getMessage(), e);
                }
            }
        } else {
            // It's a local path
            return Paths.get(buildPackage);
        }
    }
    
    private void cleanOldCachedPackages() {
        // Keep only the 5 most recently used packages
        if (buildPackageCache.size() <= 5) return;
        
        List<Map.Entry<String, Path>> entries = new ArrayList<>(buildPackageCache.entrySet());
        entries.sort((a, b) -> {
            try {
                BasicFileAttributes attrA = Files.readAttributes(a.getValue(), BasicFileAttributes.class);
                BasicFileAttributes attrB = Files.readAttributes(b.getValue(), BasicFileAttributes.class);
                return attrB.lastAccessTime().compareTo(attrA.lastAccessTime());
            } catch (IOException e) {
                return 0;
            }
        });
        
        // Remove oldest entries
        for (int i = 5; i < entries.size(); i++) {
            Map.Entry<String, Path> entry = entries.get(i);
            try {
                Files.deleteIfExists(entry.getValue());
                buildPackageCache.remove(entry.getKey());
                logger.info("Removed old cached package: " + entry.getValue());
            } catch (IOException e) {
                logger.warning("Failed to delete cached package: " + e.getMessage());
            }
        }
    }
    
    private void deleteDirectory(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
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

    /**
     * Ensure the shell testcases repository at shell_tc_dir is checked out to the configured
     * branch using the preferred remote (upstream), falling back to origin when needed.
     * This only applies to the shell testcases repo and does not affect other repositories.
     */
    private void syncShellTestcasesRepo(Logger log) throws IOException, InterruptedException {
        synchronized (SHELL_TC_SYNC_LOCK) {
            String repoPath = config.getShellTcDir();
            String targetBranch = config.getShellTcBranch();
            File repoDir = new File(repoPath);
            if (!repoDir.exists() || !repoDir.isDirectory()) {
                log.warning("shell_tc_dir does not exist: " + repoPath + "; skipping sync");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(repoDir);

            // Verify git repo
            if (runAndExitCode(pb, new String[]{"git", "rev-parse", "--is-inside-work-tree"}) != 0) {
                log.warning("shell_tc_dir is not a git repository: " + repoPath + "; skipping sync");
                return;
            }

            // Determine preferred remote from config (default: upstream), fallback to origin if missing
            String preferred = config.getShellTcPreferredRemote();
            if (preferred == null || preferred.trim().isEmpty()) {
                preferred = "upstream";
            }
            String chosenRemote = preferred;
            if (runAndExitCode(pb, new String[]{"git", "remote", "get-url", preferred}) != 0) {
                chosenRemote = "origin";
                if (runAndExitCode(pb, new String[]{"git", "remote", "get-url", chosenRemote}) != 0) {
                    log.warning("Neither 'upstream' nor 'origin' remotes are configured in " + repoPath + "; skipping sync");
                    return;
                }
            }

            // Prefer upstream if it has the branch; otherwise use origin if available
            if (!remoteBranchExists(pb, chosenRemote, targetBranch)) {
                if (!"origin".equals(chosenRemote)
                    && runAndExitCode(pb, new String[]{"git", "remote", "get-url", "origin"}) == 0
                    && remoteBranchExists(pb, "origin", targetBranch)) {
                    chosenRemote = "origin";
                } else {
                    log.warning("Branch '" + targetBranch + "' not found on remote '" + chosenRemote + "'. Skipping sync.");
                    return;
                }
            }

            log.info("Syncing shell testcases repo: branch='" + targetBranch + "' via remote='" + chosenRemote + "'");

            // Fetch just the target branch to reduce traffic
            runOrThrow(pb, new String[]{"git", "fetch", chosenRemote, targetBranch});
            // Create/reset local branch to remote branch
            runOrThrow(pb, new String[]{"git", "checkout", "-B", targetBranch, chosenRemote + "/" + targetBranch});
            // Ensure clean state (avoid untracked noise)
            runAndExitCode(pb, new String[]{"git", "clean", "-df"});
            // Hard reset to remote branch to avoid local drift
            runOrThrow(pb, new String[]{"git", "reset", "--hard", chosenRemote + "/" + targetBranch});
        }
    }

    /**
     * Checks if a remote branch exists by using 'git ls-remote --heads <remote> <branch>'.
     */
    private boolean remoteBranchExists(ProcessBuilder pb, String remote, String branch) throws IOException, InterruptedException {
        // Use git ls-remote --heads <remote> <branch> and check for any output lines
        ProcessBuilder lp = new ProcessBuilder(
            "git", "ls-remote", "--heads", remote, branch
        );
        lp.directory(pb.directory());
        lp.redirectErrorStream(true);
        Process p = lp.start();
        boolean found = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    found = true;
                    break;
                }
            }
        }
        p.waitFor();
        return found;
    }

    private int runAndExitCode(ProcessBuilder basePb, String[] cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(basePb.directory());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        drain(p.getInputStream());
        p.waitFor();
        return p.exitValue();
    }

    private void runOrThrow(ProcessBuilder basePb, String[] cmd) throws IOException, InterruptedException {
        int ec = runAndExitCode(basePb, cmd);
        if (ec != 0) {
            throw new IOException("Command failed (" + ec + "): " + String.join(" ", cmd));
        }
    }

    private void drain(InputStream is) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            while (r.readLine() != null) { /* discard */ }
        } catch (IOException ignore) {}
    }

    private String escapeShell(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "'\\''");
    }
    
    public static void main(String[] args) {
        try {
            // Setup logging
            LogManager.getLogManager().reset();
            Logger rootLogger = Logger.getLogger("");
            
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.INFO);
            consoleHandler.setFormatter(new SimpleFormatter());
            rootLogger.addHandler(consoleHandler);
            
            // System log directory
            String systemLogDir = System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log/system";
            new File(systemLogDir).mkdirs();
            
            // Clean up any existing lock files and numbered log files
            File logDir = new File(systemLogDir);
            File[] oldLogFiles = logDir.listFiles((dir, name) -> 
                name.matches("tester\\.log\\.(\\d+|lck)"));
            if (oldLogFiles != null) {
                for (File oldFile : oldLogFiles) {
                    oldFile.delete();
                }
            }
            
            // Use StreamHandler with FileOutputStream for direct control over file append
            // This avoids FileHandler's automatic rotation behavior
            String logFilePath = systemLogDir + "/tester.log";
            FileOutputStream fos = new FileOutputStream(logFilePath, true); // true = append mode
            StreamHandler streamHandler = new StreamHandler(fos, new SimpleFormatter());
            streamHandler.setLevel(Level.ALL);
            rootLogger.addHandler(streamHandler);
            
            rootLogger.setLevel(Level.INFO);
            
            // Load configuration
            String configFile = "conf/tester.conf";
            if (args.length > 0) {
                configFile = args[0];
            }
            
            BuilderConfig config = new BuilderConfig(configFile);
            
            // Create and start tester
            Tester tester = new Tester(config);
            tester.start();
            
            logger.info("=========================================");
            logger.info("Tester Service v1.0");
            logger.info("Docker-based test execution with isolation");
            logger.info("=========================================");
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                tester.stop();
                
                // Flush and close the stream handler
                streamHandler.flush();
                streamHandler.close();
                
                // Clean up any lock files that might have been created
                File lockFile = new File(systemLogDir + "/tester.log.lck");
                if (lockFile.exists()) {
                    lockFile.delete();
                }
            }));
            
            // Keep running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start Tester", e);
            System.exit(1);
        }
    }
}
