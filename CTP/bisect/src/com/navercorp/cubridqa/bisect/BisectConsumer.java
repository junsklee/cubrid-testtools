/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.bisect;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.*;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.*;
import org.json.JSONObject;

/**
 * BisectConsumer - Receives test requests from producer and executes tests
 * 
 * This service runs on test nodes and executes shell tests with provided CUBRID builds.
 * It extracts the build, sets up the environment, runs the test, and returns pass/fail status.
 * 
 * Version 2.0 - Enhanced with:
 * - Build version verification before test execution
 * - Proper differentiation between test failures and execution failures
 * - Better environment setup for shell tests
 * - Improved error handling and logging
 */
public class BisectConsumer {
    private static final Logger logger = Logger.getLogger(BisectConsumer.class.getName());
    
    private final BisectConfig config;
    private final HttpServer server;
    private final DockerConsumerManager dockerManager;
    private final boolean useDocker;
    
    // Test execution status types
    public enum TestStatus {
        PASS("pass"),           // Test executed and passed
        FAIL("fail"),           // Test executed and failed
        EXECUTION_ERROR("execution_error"),  // Test couldn't be executed properly
        ENVIRONMENT_ERROR("environment_error"), // Environment setup failed
        BUILD_ERROR("build_error");  // Build installation/verification failed
        
        private final String value;
        TestStatus(String value) { this.value = value; }
        public String getValue() { return value; }
    }
    
    public BisectConsumer(BisectConfig config) throws IOException {
        this.config = config;
        this.useDocker = config.useDockerForConsumer();
        this.dockerManager = useDocker ? new DockerConsumerManager(config) : null;
        
        // Create work directory if it doesn't exist
        File workDir = new File(config.getWorkDir());
        if (!workDir.exists()) {
            workDir.mkdirs();
        }
        
        // Initialize Docker if enabled
        if (useDocker && dockerManager != null) {
            try {
                logger.info("Initializing Docker consumer environment...");
                dockerManager.initialize();
                logger.info("Docker consumer environment ready");
            } catch (Exception e) {
                logger.warning("Docker initialization failed, falling back to direct execution: " + e.getMessage());
                // Continue without Docker
            }
        }
        
        // Create HTTP server
        this.server = HttpServer.create(new InetSocketAddress(config.getConsumerPort()), 0);
        this.server.createContext("/test", new TestRequestHandler());
        this.server.createContext("/health", new HealthCheckHandler());
        this.server.setExecutor(null); // creates a default executor
    }
    
    public void start() {
        server.start();
        logger.info("BisectConsumer started on port " + config.getConsumerPort());
        logger.info("Work directory: " + config.getWorkDir());
        logger.info("Docker mode: " + (useDocker ? "ENABLED" : "DISABLED"));
        if (useDocker) {
            logger.info("Docker test image: " + config.getDockerTestImage());
        }
    }
    
    public void stop() {
        server.stop(0);
        logger.info("BisectConsumer stopped");
    }
    
    private class TestRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logger.info("Received HTTP " + exchange.getRequestMethod() + " request to " + exchange.getRequestURI());
            
            if (!"POST".equals(exchange.getRequestMethod())) {
                logger.warning("Rejected non-POST request: " + exchange.getRequestMethod());
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                // Read request body
                String requestBody = readRequestBody(exchange);
                logger.info("Request body: " + requestBody);
                
                JSONObject request = new JSONObject(requestBody);
                
                logger.info("Received test request for: " + request.getString("testPath"));
                logger.info("Build package: " + request.getString("buildPackage"));
                
                // Run test
                JSONObject result = runTest(request);
                
                // Send response
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                sendResponse(exchange, 200, result.toString());
                logger.info("Sent response: " + result.toString());
                
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error processing test request", e);
                JSONObject error = new JSONObject()
                    .put("status", TestStatus.EXECUTION_ERROR.getValue())
                    .put("message", e.getMessage())
                    .put("error_type", "request_processing");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                sendResponse(exchange, 500, error.toString());
            }
        }
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
                    .put("service", "BisectConsumer")
                    .put("timestamp", System.currentTimeMillis())
                    .put("workDir", config.getWorkDir());
                
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
        // Create work directory for this test
        Path workDir = Files.createTempDirectory(Paths.get(config.getWorkDir()), "test_");
        logger.info("Working directory: " + workDir);
        
        try {
            // Check if we should use Docker for test execution
            if (useDocker && dockerManager != null && DockerUtils.isDockerAvailable()) {
                return runTestInDocker(request, workDir);
            } else {
                logger.info("Using direct test execution (Docker not available or disabled)");
                return runTestDirectly(request, workDir);
            }
        } finally {
            // Cleanup
            deleteDirectory(workDir.toFile());
        }
    }
    
    /**
     * Run test in Docker container for better isolation
     */
    private JSONObject runTestInDocker(JSONObject request, Path workDir) throws Exception {
        logger.info("Running test in Docker container...");
        
        // Extract request parameters
        String buildPackage = request.getString("buildPackage");
        String testDir = request.getString("testDir");
        String testScript = request.getString("testScript");
        String testName = request.getString("testName");
        String expectedBuildVersion = request.optString("expectedBuildVersion", null);
        
        logger.info("Docker test parameters:");
        logger.info("  Build package: " + buildPackage);
        logger.info("  Test directory: " + testDir);
        logger.info("  Test script: " + testScript);
        logger.info("  Test name: " + testName);
        
        // Create a temporary directory for Docker volumes
        Path dockerWorkDir = Files.createTempDirectory(workDir, "docker_");
        
        // Copy test files to Docker work directory
        Path testSourceDir = Paths.get(testDir);
        Path dockerTestDir = dockerWorkDir.resolve("test");
        copyDirectory(testSourceDir, dockerTestDir);
        
        // Copy build package to Docker work directory
        Path dockerBuildPackage = dockerWorkDir.resolve("build.tar.gz");
        Files.copy(Paths.get(buildPackage), dockerBuildPackage);
        
        // Create test execution script for Docker
        String dockerScript = createDockerTestScript(testScript, testName, expectedBuildVersion);
        Path dockerScriptPath = dockerWorkDir.resolve("run_test.sh");
        Files.write(dockerScriptPath, dockerScript.getBytes());
        dockerScriptPath.toFile().setExecutable(true);
        
        // Run Docker container
        List<String> dockerCommand = Arrays.asList(
            "docker", "run", "--rm",
            "-v", dockerWorkDir.toString() + ":/workspace",
            "-w", "/workspace",
            config.getDockerTestImage(),
            "test", // Use 'test' role for tester image
            "bash", "/workspace/run_test.sh"
        );
        
        logger.info("Executing Docker command: " + String.join(" ", dockerCommand));
        
        ProcessBuilder pb = new ProcessBuilder(dockerCommand);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        // Capture output
        StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), "DOCKER");
        outputGobbler.start();
        
        // Wait for completion with timeout
        boolean completed = process.waitFor(30, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            logger.severe("Docker test timeout after 30 minutes");
            return new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", "Docker test timeout after 30 minutes")
                .put("test", testName);
        }
        
        int exitCode = process.exitValue();
        outputGobbler.join(2000);
        String dockerOutput = outputGobbler.getOutput();
        
        logger.info("Docker test completed with exit code: " + exitCode);
        
        // Check for result file in Docker work directory
        Path resultFile = dockerTestDir.resolve("nok.result");
        if (Files.exists(resultFile)) {
            String resultContent = new String(Files.readAllBytes(resultFile));
            logger.info("Docker test result: " + resultContent.trim());
            
            if (resultContent.contains("NOK") || resultContent.contains("FAIL")) {
                return new JSONObject()
                    .put("status", TestStatus.FAIL.getValue())
                    .put("test", testName)
                    .put("execution_mode", "docker");
            } else if (resultContent.contains("OK") || resultContent.contains("PASS")) {
                return new JSONObject()
                    .put("status", TestStatus.PASS.getValue())
                    .put("test", testName)
                    .put("execution_mode", "docker");
            }
        }
        
        // Check for Docker-specific errors
        if (exitCode != 0) {
            if (dockerOutput.contains("docker: command not found") || 
                dockerOutput.contains("Cannot connect to the Docker daemon")) {
                logger.warning("Docker execution failed, falling back to direct execution");
                return runTestDirectly(request, workDir);
            }
            
            return new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", "Docker test execution failed: " + dockerOutput.substring(0, Math.min(200, dockerOutput.length())))
                .put("test", testName)
                .put("exit_code", exitCode);
        }
        
        // No clear result
        return new JSONObject()
            .put("status", TestStatus.EXECUTION_ERROR.getValue())
            .put("message", "Docker test completed but no clear result")
            .put("test", testName);
    }
    
    /**
     * Run test directly on host (fallback or when Docker disabled)
     */
    private JSONObject runTestDirectly(JSONObject request, Path workDir) throws Exception {
        // Extract request parameters
        String buildPackage = request.getString("buildPackage");
        String testDir = request.getString("testDir");
        String testScript = request.getString("testScript");
        String testName = request.getString("testName");
        
        // Optional: expected build version for verification
        String expectedBuildVersion = request.optString("expectedBuildVersion", null);
        
        logger.info("Direct test parameters:");
        logger.info("  Build package: " + buildPackage);
        logger.info("  Test directory: " + testDir);
        logger.info("  Test script: " + testScript);
        logger.info("  Test name: " + testName);
        if (expectedBuildVersion != null) {
            logger.info("  Expected build version: " + expectedBuildVersion);
        }
            
            // Install CUBRID by extracting the build package directly
            logger.info("Installing CUBRID build by extracting: " + buildPackage);
            Path actualInstallDir = null;
            try {
                actualInstallDir = installCubridDirectly(buildPackage, workDir);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to install CUBRID build", e);
                return new JSONObject()
                    .put("status", TestStatus.BUILD_ERROR.getValue())
                    .put("message", "Failed to install CUBRID: " + e.getMessage())
                    .put("test", testName);
            }
            
            // Verify CUBRID installation by running cubrid_rel
            logger.info("Verifying CUBRID installation...");
            String installedVersion = verifyCubridInstallation(actualInstallDir);
            if (installedVersion == null) {
                logger.severe("CUBRID installation verification failed - cubrid_rel command failed");
                return new JSONObject()
                    .put("status", TestStatus.BUILD_ERROR.getValue())
                    .put("message", "CUBRID installation verification failed")
                    .put("test", testName);
            }
            
            logger.info("CUBRID installation verified. Version: " + installedVersion);
            
            // Check if expected version matches (if provided)
            if (expectedBuildVersion != null && !installedVersion.contains(expectedBuildVersion)) {
                logger.warning("Build version mismatch. Expected: " + expectedBuildVersion + ", Got: " + installedVersion);
                return new JSONObject()
                    .put("status", TestStatus.BUILD_ERROR.getValue())
                    .put("message", "Build version mismatch")
                    .put("expected_version", expectedBuildVersion)
                    .put("installed_version", installedVersion)
                    .put("test", testName);
            }
            
            // Set environment for CUBRID (using the actual installation directory)
            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("CUBRID", actualInstallDir.toString());
            env.put("CUBRID_DATABASES", actualInstallDir.resolve("databases").toString());
            env.put("PATH", actualInstallDir.resolve("bin") + ":" + System.getenv("PATH"));
            env.put("LD_LIBRARY_PATH", actualInstallDir.resolve("lib") + ":" + 
                    System.getenv().getOrDefault("LD_LIBRARY_PATH", ""));
            
            // Load CUBRID environment
            env.put("CUBRID_LANG", "en_US");
            env.put("CUBRID_CHARSET", "en_US");
            
            // Set CTP_HOME if available for shell test framework
            String ctpHome = findCTPHome();
            if (ctpHome != null) {
                env.put("CTP_HOME", ctpHome);
                env.put("init_path", ctpHome + "/shell/init_path");
                // Add shell test utilities to PATH
                env.put("PATH", ctpHome + "/shell/init_path:" + env.get("PATH"));
            }
            
            // Ensure databases directory exists
            if (!Files.exists(actualInstallDir.resolve("databases"))) {
                Files.createDirectories(actualInstallDir.resolve("databases"));
            }
            
            // Run the test with proper shell test framework setup
            String resultFile = "nok.result";  // Standard result file name used by shell test framework
            logger.info("Running test: " + testScript + " in directory: " + testDir);
            
            // Remove old result file if exists
            File resultFileObj = new File(testDir, resultFile);
            if (resultFileObj.exists()) {
                resultFileObj.delete();
                logger.info("Removed existing result file: " + resultFile);
            }
            
            // Create wrapper script that properly sets up the test environment
            String wrapperScript = createTestWrapperScript(testDir, testScript, testName, ctpHome);
            File wrapperFile = new File(workDir.toFile(), "test_wrapper.sh");
            Files.write(wrapperFile.toPath(), wrapperScript.getBytes());
            wrapperFile.setExecutable(true);
            
            // Run test through wrapper script
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(new File(testDir));
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.command("bash", wrapperFile.getAbsolutePath());
            
            Process process = pb.start();
            
            // Capture output for debugging
            StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), "OUTPUT");
            StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), "ERROR");
            outputGobbler.start();
            errorGobbler.start();
            
            // Wait for completion
            boolean completed = process.waitFor(30, TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                logger.severe("Test timeout after 30 minutes");
                return new JSONObject()
                    .put("status", TestStatus.EXECUTION_ERROR.getValue())
                    .put("message", "Test timeout after 30 minutes")
                    .put("test", testName);
            }
            
            int exitCode = process.exitValue();
            logger.info("Test script exited with code: " + exitCode);
            
            // Wait a bit for output gobblers to finish
            outputGobbler.join(2000);
            errorGobbler.join(2000);
            
            // Check for test execution errors in the output
            String testOutput = outputGobbler.getOutput();
            String testError = errorGobbler.getOutput();
            
            // Check if test script had execution errors (like command not found, syntax errors, etc.)
            if (exitCode != 0 && (testError.contains("command not found") || 
                                   testError.contains("syntax error") ||
                                   testError.contains("No such file or directory"))) {
                logger.severe("Test execution error detected: " + testError);
                return new JSONObject()
                    .put("status", TestStatus.EXECUTION_ERROR.getValue())
                    .put("message", "Test script execution error: " + testError.substring(0, Math.min(200, testError.length())))
                    .put("test", testName)
                    .put("exit_code", exitCode);
            }
            
            // Check result file
            if (resultFileObj.exists()) {
                String resultContent = new String(Files.readAllBytes(resultFileObj.toPath()));
                logger.info("Result file content: " + resultContent.trim());
                
                if (resultContent.contains("NOK") || resultContent.contains("FAIL")) {
                    logger.info("Test " + testName + " FAILED");
                    return new JSONObject()
                        .put("status", TestStatus.FAIL.getValue())
                        .put("test", testName)
                        .put("build_version", installedVersion);
                } else if (resultContent.contains("OK") || resultContent.contains("PASS")) {
                    logger.info("Test " + testName + " PASSED");
                    return new JSONObject()
                        .put("status", TestStatus.PASS.getValue())
                        .put("test", testName)
                        .put("build_version", installedVersion);
                } else {
                    // Result file exists but doesn't contain clear pass/fail indicator
                    logger.warning("Result file exists but status unclear: " + resultContent);
                    return new JSONObject()
                        .put("status", TestStatus.EXECUTION_ERROR.getValue())
                        .put("message", "Result file exists but status unclear")
                        .put("result_content", resultContent.substring(0, Math.min(100, resultContent.length())))
                        .put("test", testName);
                }
            } else {
                // No result file generated
                logger.warning("No result file generated for test " + testName);
                
                // Check if this is likely an environment or setup issue
                if (testOutput.contains("CUBRID") && testOutput.contains("not found")) {
                    return new JSONObject()
                        .put("status", TestStatus.ENVIRONMENT_ERROR.getValue())
                        .put("message", "CUBRID environment not properly set up")
                        .put("test", testName);
                }
                
                // General execution error - test ran but didn't produce result
                return new JSONObject()
                    .put("status", TestStatus.EXECUTION_ERROR.getValue())
                    .put("message", "No result file generated - test may not have run properly")
                    .put("test", testName)
                    .put("exit_code", exitCode);
            }
    }
    
    private Path installCubridDirectly(String buildPackage, Path workDir) throws IOException, InterruptedException {
        logger.info("Installing CUBRID by extracting build package: " + buildPackage);
        
        // Create installation directory
        Path cubridInstallDir = Paths.get(System.getProperty("user.home"), "CUBRID");
        
        // Remove existing installation if it exists
        if (Files.exists(cubridInstallDir)) {
            logger.info("Removing existing CUBRID installation");
            deleteDirectory(cubridInstallDir.toFile());
        }
        
        // Create new installation directory
        Files.createDirectories(cubridInstallDir);
        
        // Extract the build package directly to the installation directory
        logger.info("Extracting build package to: " + cubridInstallDir);
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", buildPackage, "-C", cubridInstallDir.toString());
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        // Capture output
        StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), "EXTRACT");
        outputGobbler.start();
        
        // Wait for completion
        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("CUBRID extraction timeout after 5 minutes");
        }
        
        int exitCode = process.exitValue();
        outputGobbler.join(1000);
        
        if (exitCode != 0) {
            String output = outputGobbler.getOutput();
            throw new IOException("CUBRID extraction failed with exit code: " + exitCode + 
                                  ". Output: " + output.substring(0, Math.min(500, output.length())));
        }
        
        logger.info("CUBRID extraction completed successfully");
        
        // The build package contains the entire build directory structure
        // We need to find where the actual CUBRID binaries are located
        Path actualCubridDir = findCubridBinariesInExtraction(cubridInstallDir);
        
        if (actualCubridDir == null) {
            throw new IOException("Could not find CUBRID binaries in extracted package");
        }
        
        logger.info("CUBRID binaries found at: " + actualCubridDir);
        return actualCubridDir;
    }
    
    private Path findCubridBinariesInExtraction(Path extractionDir) throws IOException {
        // The build package might extract to various structures
        // Look for the bin/cubrid_rel executable to identify the CUBRID installation
        
        // Check common locations where CUBRID might be extracted
        Path[] candidatePaths = {
            extractionDir,
            extractionDir.resolve("CUBRID"),
            extractionDir.resolve("cubrid"),
            extractionDir.resolve("install"),
            extractionDir.resolve("bin").getParent()  // if bin exists, parent might be CUBRID root
        };
        
        for (Path candidate : candidatePaths) {
            if (Files.exists(candidate)) {
                // Look for bin/cubrid_rel in this directory and subdirectories
                Path cubridRel = candidate.resolve("bin/cubrid_rel");
                if (Files.exists(cubridRel) && Files.isExecutable(cubridRel)) {
                    logger.info("Found cubrid_rel at: " + cubridRel);
                    return candidate;
                }
            }
        }
        
        // If direct search fails, walk the directory tree to find cubrid_rel
        logger.info("Searching for cubrid_rel in extracted directory tree...");
        try (java.util.stream.Stream<Path> paths = Files.walk(extractionDir, 3)) {
            java.util.Optional<Path> cubridRel = paths
                .filter(path -> path.getFileName().toString().equals("cubrid_rel"))
                .filter(Files::isExecutable)
                .findFirst();
                
            if (cubridRel.isPresent()) {
                Path binDir = cubridRel.get().getParent();
                Path cubridRoot = binDir.getParent();
                logger.info("Found CUBRID installation at: " + cubridRoot);
                return cubridRoot;
            }
        }
        
        return null;
    }
    
    /**
     * Find CTP_HOME directory by searching upward from current location
     */
    private String findCTPHome() {
        // Try environment variable first
        String ctpHome = System.getenv("CTP_HOME");
        if (ctpHome != null && new File(ctpHome).exists()) {
            return ctpHome;
        }
        
        // Try to find it relative to current working directory
        File currentDir = new File(".").getAbsoluteFile();
        while (currentDir != null) {
            File commonScript = new File(currentDir, "common/script/run_cubrid_install");
            if (commonScript.exists()) {
                return currentDir.getAbsolutePath();
            }
            currentDir = currentDir.getParentFile();
        }
        
        // Try common locations
        String[] commonPaths = {
            "/home/qahome/cubrid-testtools/CTP",
            "/Users/jun/cubrid-testtools/CTP",
            System.getProperty("user.home") + "/cubrid-testtools/CTP"
        };
        
        for (String path : commonPaths) {
            File ctpDir = new File(path);
            if (ctpDir.exists() && new File(ctpDir, "common/script/run_cubrid_install").exists()) {
                logger.info("Found CTP_HOME at: " + path);
                return path;
            }
        }
        
        // Default fallback
        logger.warning("Could not find CTP_HOME, using default assumption");
        return "/home/qahome/cubrid-testtools/CTP";
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
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) 
            throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
    
    /**
     * Enhanced Stream gobbler to consume and store process output
     */
    private static class StreamGobbler extends Thread {
        private final InputStream is;
        private final String type;
        private final StringBuilder output = new StringBuilder();
        
        StreamGobbler(InputStream is, String type) {
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
                    if (type.startsWith("INSTALL") || type.equals("ERROR") || type.equals("OUTPUT")) {
                        // Log important output at INFO level
                        logger.info(type + ": " + line);
                    } else {
                        // Log other output at FINE level
                        logger.fine(type + ": " + line);
                    }
                }
            } catch (IOException e) {
                logger.warning("Error reading " + type + " stream: " + e.getMessage());
            }
        }
    }
    
    public static void main(String[] args) {
        try {
            // Setup logging
            LogManager.getLogManager().reset();
            Logger rootLogger = Logger.getLogger("");
            
            // Console handler for normal output
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.INFO);
            consoleHandler.setFormatter(new SimpleFormatter());
            rootLogger.addHandler(consoleHandler);
            
            // File handler for detailed logging
            String logDir = System.getProperty("user.home") + "/cubrid-testtools/CTP/bisect/log";
            new File(logDir).mkdirs();
            FileHandler fileHandler = new FileHandler(logDir + "/bisect_consumer.log", true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new SimpleFormatter());
            rootLogger.addHandler(fileHandler);
            
            rootLogger.setLevel(Level.INFO);
            
            // Load configuration
            String configFile = "conf/bisect_consumer.conf";
            if (args.length > 0) {
                configFile = args[0];
            }
            
            // Consumer uses same config class but different file
            BisectConfig config = new BisectConfig(configFile);
            
            // Create and start consumer
            BisectConsumer consumer = new BisectConsumer(config);
            consumer.start();
            
            logger.info("=========================================");
            logger.info("BisectConsumer v3.0 - Docker Edition");
            logger.info("Features:");
            logger.info("  - Docker-based test isolation (enabled by default)");
            logger.info("  - Build version verification");
            logger.info("  - Execution error differentiation");
            logger.info("  - Shell test framework integration");
            logger.info("  - Automatic fallback to direct execution");
            logger.info("=========================================");
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                consumer.stop();
            }));
            
            // Keep running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start BisectConsumer", e);
            System.exit(1);
        }
    }
    
    /**
     * Verify CUBRID installation by running cubrid_rel command
     */
    private String verifyCubridInstallation(Path cubridHome) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cubridHome.resolve("bin/cubrid_rel").toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                logger.warning("cubrid_rel command timeout");
                return null;
            }
            
            if (process.exitValue() == 0) {
                String version = output.toString().trim();
                logger.info("CUBRID version verified: " + version);
                return version;
            } else {
                logger.warning("cubrid_rel failed with exit code: " + process.exitValue());
                logger.warning("Output: " + output.toString());
                return null;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to verify CUBRID installation", e);
            return null;
        }
    }
    
    /**
     * Create a wrapper script that properly sets up the test environment
     */
    private String createTestWrapperScript(String testDir, String testScript, String testName, String ctpHome) {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("# Auto-generated test wrapper script\n");
        script.append("set -e\n\n");
        
        // Set up environment
        script.append("# Set up environment\n");
        script.append("export CTP_HOME=\"").append(ctpHome).append("\"\n");
        script.append("export PATH=\"$CTP_HOME/shell/init_path:$PATH\"\n");
        script.append("export PATH=\"$HOME/CUBRID/bin:$PATH\"\n");
        script.append("export LD_LIBRARY_PATH=\"$HOME/CUBRID/lib:$LD_LIBRARY_PATH\"\n\n");
        
        // Change to test directory
        script.append("# Change to test directory\n");
        script.append("cd \"").append(testDir).append("\"\n\n");
        
        // Source init.sh if available (for shell test framework)
        script.append("# Source shell test framework if available\n");
        script.append("if [ -f \"$CTP_HOME/shell/init_path/init.sh\" ]; then\n");
        script.append("    source \"$CTP_HOME/shell/init_path/init.sh\"\n");
        script.append("    echo \"Sourced shell test framework\"\n");
        script.append("fi\n\n");
        
        // Define helper functions
        script.append("# Helper functions for test result handling\n");
        script.append("write_ok() {\n");
        script.append("    echo \"Test ").append(testName).append(": OK\" > nok.result 2>/dev/null || echo \"OK\"\n");
        script.append("}\n\n");
        script.append("write_nok() {\n");
        script.append("    echo \"Test ").append(testName).append(": NOK\" > nok.result 2>/dev/null || echo \"NOK\"\n");
        script.append("}\n\n");
        
        // Execute the actual test
        script.append("# Execute the test\n");
        script.append("echo \"Starting test: ").append(testName).append("\"\n");
        script.append("bash \"").append(testScript).append("\"\n");
        script.append("TEST_EXIT_CODE=$?\n\n");
        
        // Ensure result file exists
        script.append("# Ensure result file exists based on exit code if not already created\n");
        script.append("if [ ! -f \"nok.result\" ]; then\n");
        script.append("    if [ $TEST_EXIT_CODE -eq 0 ]; then\n");
        script.append("        write_ok\n");
        script.append("    else\n");
        script.append("        write_nok\n");
        script.append("    fi\n");
        script.append("fi\n\n");
        
        script.append("exit $TEST_EXIT_CODE\n");
        
        return script.toString();
    }
    
    /**
     * Create Docker test execution script with improved isolation and error handling
     */
    private String createDockerTestScript(String testScript, String testName, String expectedBuildVersion) {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("# Auto-generated Docker test execution script\n");
        script.append("set -e\n\n");
        
        // Extract and install CUBRID build
        script.append("# Extract CUBRID build\n");
        script.append("echo \"Extracting CUBRID build...\"\n");
        script.append("mkdir -p /tmp/cubrid_install\n");
        script.append("cd /tmp/cubrid_install\n");
        script.append("tar -xzf /workspace/build.tar.gz\n");
        script.append("CUBRID_ROOT=$(find /tmp/cubrid_install -name \"bin\" -type d | head -1 | xargs dirname)\n");
        script.append("if [ -z \"$CUBRID_ROOT\" ]; then\n");
        script.append("    echo \"ERROR: Could not find CUBRID installation\"\n");
        script.append("    exit 1\n");
        script.append("fi\n\n");
        
        // Set up CUBRID environment
        script.append("# Set up CUBRID environment\n");
        script.append("export CUBRID=\"$CUBRID_ROOT\"\n");
        script.append("export CUBRID_DATABASES=\"$CUBRID_ROOT/databases\"\n");
        script.append("export PATH=\"$CUBRID_ROOT/bin:$PATH\"\n");
        script.append("export LD_LIBRARY_PATH=\"$CUBRID_ROOT/lib:$LD_LIBRARY_PATH\"\n");
        script.append("export CUBRID_LANG=\"en_US\"\n");
        script.append("export CUBRID_CHARSET=\"en_US\"\n\n");
        
        // Verify CUBRID installation
        script.append("# Verify CUBRID installation\n");
        script.append("echo \"Verifying CUBRID installation...\"\n");
        script.append("if ! cubrid_rel; then\n");
        script.append("    echo \"ERROR: CUBRID verification failed\"\n");
        script.append("    exit 1\n");
        script.append("fi\n\n");
        
        // Create databases directory
        script.append("mkdir -p \"$CUBRID_DATABASES\"\n\n");
        
        // Change to test directory and run test
        script.append("# Execute test\n");
        script.append("cd /workspace/test\n");
        script.append("echo \"Starting test: ").append(testName).append("\"\n");
        script.append("bash \"").append(testScript).append("\"\n");
        script.append("TEST_EXIT_CODE=$?\n\n");
        
        // Ensure result file exists
        script.append("# Ensure result file exists based on exit code if not already created\n");
        script.append("if [ ! -f \"nok.result\" ]; then\n");
        script.append("    if [ $TEST_EXIT_CODE -eq 0 ]; then\n");
        script.append("        echo \"Test ").append(testName).append(": OK\" > nok.result\n");
        script.append("    else\n");
        script.append("        echo \"Test ").append(testName).append(": NOK\" > nok.result\n");
        script.append("    fi\n");
        script.append("fi\n\n");
        
        script.append("exit $TEST_EXIT_CODE\n");
        
        return script.toString();
    }
    
    /**
     * Copy directory contents recursively
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException("Source directory does not exist: " + source);
        }
        
        Files.createDirectories(target);
        
        Files.walk(source)
            .forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.copy(sourcePath, targetPath, 
                                 StandardCopyOption.REPLACE_EXISTING,
                                 StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy: " + sourcePath, e);
                }
            });
    }
}