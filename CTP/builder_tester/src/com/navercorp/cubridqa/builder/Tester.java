/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.*;
import org.json.JSONObject;

/**
 * Tester - Receives test requests from Builder and executes tests
 * 
 * This service runs on test nodes and executes shell tests with provided CUBRID builds.
 * It extracts the build, sets up the environment, runs the test, and returns pass/fail status.
 */
public class Tester {
    private static final Logger logger = Logger.getLogger(Tester.class.getName());
    
    private final BuilderConfig config;
    private final HttpServer server;
    private final DockerTesterManager dockerManager;
    private final boolean useDocker;
    
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
        this.useDocker = config.useDockerForTester();
        this.dockerManager = useDocker ? new DockerTesterManager(config) : null;
        
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
        this.server.setExecutor(null);
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
            logger.info("Received " + exchange.getRequestMethod() + " request");
            
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                String requestBody = readRequestBody(exchange);
                JSONObject request = new JSONObject(requestBody);
                
                logger.info("Test request for: " + request.getString("testPath"));
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
                    .put("message", e.getMessage());
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
                    .put("service", "Tester")
                    .put("timestamp", System.currentTimeMillis())
                    .put("workDir", config.getWorkDir())
                    .put("dockerEnabled", useDocker);
                
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
        Path workDir = Files.createTempDirectory(Paths.get(config.getWorkDir()), "test_");
        logger.info("Working directory: " + workDir);
        boolean keepAliveRequested = request.optBoolean("keepAlive", config.getKeepFailedContainers());
        try {
            // Check if we should use Docker for test execution
            if (useDocker && dockerManager != null && DockerUtils.isDockerAvailable()) {
                return runTestInDocker(request, workDir);
            } else {
                logger.info("Using direct test execution");
                return runTestDirectly(request, workDir);
            }
        } finally {
            // Cleanup unless keep-alive requested or a keep marker is present
            try {
                if (!keepAliveRequested && !Files.exists(workDir.resolve("KEEP_WORKSPACE"))) {
                    deleteDirectory(workDir.toFile());
                } else {
                    logger.info("Preserving workDir for debugging: " + workDir);
                }
            } catch (Exception ignore) {
                // best effort
            }
        }
    }
    
    private JSONObject runTestInDocker(JSONObject request, Path workDir) throws Exception {
        logger.info("Running test in Docker container...");
        
        String buildPackage = request.getString("buildPackage");
        String testDir = request.getString("testDir");
        String testScript = request.getString("testScript");
        String testName = request.getString("testName");
        String expectedBuildVersion = request.optString("expectedBuildVersion", null);
        boolean keepAlive = request.optBoolean("keepAlive", config.getKeepFailedContainers());
        String containerName = request.optString("containerName", "tester_debug_" + testName.replaceAll("[^a-zA-Z0-9_.-]", "_") + "_" + System.currentTimeMillis());
        
        Path dockerWorkDir = Files.createTempDirectory(workDir, "docker_");
        
        // Copy build package to Docker work directory
        Path dockerBuildPackage = dockerWorkDir.resolve("build.tar.gz");
        Files.copy(Paths.get(buildPackage), dockerBuildPackage);
        
        // Create test execution script for Docker
        String baseTestDir = config.getShellTcDir();
        String relativeTestDir = testDir.startsWith(baseTestDir) ? 
            testDir.substring(baseTestDir.length()).replaceFirst("^/", "") : testDir;
        
        String dockerScript = createDockerTestScript(testScript, testName, 
                                                     expectedBuildVersion, relativeTestDir);
        Path dockerScriptPath = dockerWorkDir.resolve("run_test.sh");
        Files.write(dockerScriptPath, dockerScript.getBytes());
        dockerScriptPath.toFile().setExecutable(true);
        try {
            Path logsDir = Paths.get(System.getProperty("user.home"), "cubrid-testtools", "CTP", "builder_tester", "log", "tests");
            Files.createDirectories(logsDir);
            String safeTestNameForScript = testName.replaceAll("[^a-zA-Z0-9_.-]", "_");
            Path scriptLogPath = logsDir.resolve("docker_script_" + safeTestNameForScript + "_" + System.currentTimeMillis() + ".sh");
            Files.write(scriptLogPath, dockerScript.getBytes("UTF-8"));
            logger.info("Saved generated Docker test script to: " + scriptLogPath.toString());
        } catch (Exception ignore) { }
        
        // Check for GitHub token
        String githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.trim().isEmpty()) {
            logger.severe("GITHUB_TOKEN not set");
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
        dockerCommand.add(dockerWorkDir.toString() + ":/workspace:z");
        dockerCommand.add("-v");
        dockerCommand.add(System.getProperty("user.home") + "/cubrid-testtools:/home/cubrid-testtools:z");
        dockerCommand.add("-v");
        // Mount testcases read-write to allow tests that write result artifacts
        dockerCommand.add(config.getShellTcDir() + ":/home/cubrid-testcases-private-ex:z");
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
        
        logger.info("Executing Docker command: " + String.join(" ", dockerCommand));
        
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
                logger.info("Started debug container: " + containerName);
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
            logger.severe("Docker test timeout");
            return new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", "Docker test timeout after 30 minutes")
                .put("test", testName);
        }
        int exitCode = process.exitValue();
        outputGobbler.join(2000);
        String dockerOutput = outputGobbler.getOutput();
        logger.info("Docker test completed with exit code: " + exitCode);

        // Persist full docker output for diagnostics
        try {
            Path logsDir = Paths.get(System.getProperty("user.home"), "cubrid-testtools", "CTP", "builder_tester", "log", "tests");
            Files.createDirectories(logsDir);
            String safeTestName = testName.replaceAll("[^a-zA-Z0-9_.-]", "_");
            Path logFile = logsDir.resolve("docker_" + safeTestName + "_" + System.currentTimeMillis() + ".log");
            Files.write(logFile, dockerOutput.getBytes("UTF-8"));
            logger.info("Saved full docker test log to: " + logFile.toString());
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
             logger.info("Test result file (" + namedResult.getFileName() + "): " + resultContent.trim());
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
            if (dockerOutput.contains("docker: command not found")) {
                logger.warning("Docker not available, falling back to direct execution");
                return runTestDirectly(request, workDir);
            }
            // Keep container on failure if configured: rerun in detached mode for debugging
            if (config.getKeepFailedContainers()) {
                try { Files.createFile(workDir.resolve("KEEP_WORKSPACE")); } catch (Exception ignore) {}
                List<String> keepCmd = new ArrayList<>();
                keepCmd.add("docker"); keepCmd.add("run"); keepCmd.add("-d");
                keepCmd.add("--name"); keepCmd.add(containerName);
                keepCmd.add("-v"); keepCmd.add(dockerWorkDir.toString() + ":/workspace:z");
                keepCmd.add("-v"); keepCmd.add(System.getProperty("user.home") + "/cubrid-testtools:/home/cubrid-testtools:z");
                 keepCmd.add("-v"); keepCmd.add(config.getShellTcDir() + ":/home/cubrid-testcases-private-ex:z");
                keepCmd.add("-e"); keepCmd.add("GITHUB_TOKEN=" + githubToken);
                keepCmd.add("-e"); keepCmd.add("CTP_HOME=/home/cubrid-testtools/CTP");
                keepCmd.add("-e"); keepCmd.add("init_path=/home/cubrid-testtools/CTP/shell/init_path");
                keepCmd.add("-w"); keepCmd.add("/workspace");
                keepCmd.add("--entrypoint"); keepCmd.add("bash");
                keepCmd.add(config.getDockerTestImage());
                keepCmd.add("-lc"); keepCmd.add("echo READY; tail -f /dev/null");
                try { new ProcessBuilder(keepCmd).start().waitFor(5, TimeUnit.SECONDS); } catch (Exception ignore) {}
                logger.info("Failure container kept for debugging: " + containerName);
                String execCmd = "docker exec -it " + containerName + " bash";
                return new JSONObject()
                    .put("status", TestStatus.EXECUTION_ERROR.getValue())
                    .put("message", "Docker test execution failed; container kept for debugging")
                    .put("test", testName)
                    .put("containerName", containerName)
                    .put("execCommand", execCmd)
                    .put("workspace", dockerWorkDir.toString());
            }
            
            return new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", "Docker test execution failed")
                .put("test", testName)
                .put("exit_code", exitCode);
        }
        
        return new JSONObject()
            .put("status", TestStatus.EXECUTION_ERROR.getValue())
            .put("message", "No clear test result")
            .put("test", testName);
    }
    
    private JSONObject runTestDirectly(JSONObject request, Path workDir) throws Exception {
        String buildPackage = request.getString("buildPackage");
        String testDir = request.getString("testDir");
        String testScript = request.getString("testScript");
        String testName = request.getString("testName");
        String expectedBuildVersion = request.optString("expectedBuildVersion", null);
        
        logger.info("Direct test execution");
        logger.info("  Build package: " + buildPackage);
        logger.info("  Test: " + testName);
        
        // Install CUBRID
        Path installDir = null;
        try {
            installDir = installCubrid(buildPackage, workDir);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to install CUBRID", e);
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
        
        // Run the test
        String resultBaseFromScript = testScript.endsWith(".sh")
            ? testScript.substring(0, testScript.length() - 3)
            : (testScript.contains(".") ? testScript.substring(0, testScript.lastIndexOf('.')) : testScript);
        File namedResultFileObj = new File(testDir, resultBaseFromScript + ".result");
        logger.info("Running test: " + testScript);
        if (namedResultFileObj.exists()) namedResultFileObj.delete();
        
        // Create wrapper script
        String wrapperScript = createTestWrapperScript(testDir, testScript, testName, ctpHome);
        File wrapperFile = new File(workDir.toFile(), "test_wrapper.sh");
        Files.write(wrapperFile.toPath(), wrapperScript.getBytes());
        wrapperFile.setExecutable(true);
        
        // Run test
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(testDir));
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
            logger.severe("Test timeout");
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
            logger.severe("Test execution error: " + testError);
            return new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", "Test script execution error")
                .put("test", testName)
                .put("exit_code", exitCode);
        }
        
        // Check named result first then nok.result
        if (namedResultFileObj.exists()) {
            String resultContent = new String(Files.readAllBytes(namedResultFileObj.toPath()));
            logger.info("Named Result (" + resultBaseFromScript + ".result): " + resultContent.trim());
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
    
    private Path installCubrid(String buildPackage, Path workDir) 
            throws IOException, InterruptedException {
        logger.info("Installing CUBRID from: " + buildPackage);
        
        Path cubridInstallDir = Paths.get(System.getProperty("user.home"), "CUBRID");
        
        // Remove existing installation
        if (Files.exists(cubridInstallDir)) {
            logger.info("Removing existing CUBRID installation");
            deleteDirectory(cubridInstallDir.toFile());
        }
        
        // Create new installation directory
        Files.createDirectories(cubridInstallDir);
        
        // Extract the build package
        logger.info("Extracting to: " + cubridInstallDir);
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
        
        logger.info("CUBRID extraction completed");
        
        // Find actual CUBRID directory
        Path actualCubridDir = findCubridBinaries(cubridInstallDir);
        
        if (actualCubridDir == null) {
            throw new IOException("Could not find CUBRID binaries");
        }
        
        logger.info("CUBRID binaries found at: " + actualCubridDir);
        
        // Try running setup.sh if available to configure installation
        try {
            Path setupScript = actualCubridDir.resolve("share/scripts/setup.sh");
            if (!Files.exists(setupScript)) {
                setupScript = actualCubridDir.resolve("setup.sh");
            }
            if (Files.exists(setupScript)) {
                logger.info("Running setup.sh to configure CUBRID installation");
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
                    logger.warning("setup.sh timeout after 2 minutes, continuing anyway");
                } else if (setupProc.exitValue() != 0) {
                    setupOutput.join(1000);
                    logger.warning("setup.sh exited with code: " + setupProc.exitValue());
                } else {
                    logger.info("setup.sh completed successfully");
                }
            } else {
                logger.info("setup.sh not found, skipping setup");
            }
        } catch (Exception e) {
            logger.warning("setup.sh execution failed: " + e.getMessage());
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
         script.append("export CUBRID_DATABASES=\"$CUBRID_ROOT/databases\"\n");
         script.append("export PATH=\"$CUBRID_ROOT/bin:$PATH\"\n");
         script.append("export LD_LIBRARY_PATH=\"$CUBRID_ROOT/lib:$CUBRID_ROOT/cci/lib:$CUBRID_ROOT/lib64:$LD_LIBRARY_PATH\"\n");
         script.append("export CUBRID_LANG=\"en_US\"\n");
         script.append("export CUBRID_CHARSET=\"en_US\"\n\n");

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
         
         script.append("mkdir -p \"$CUBRID_DATABASES\"\n\n");
 
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
         script.append("cd /home/cubrid-testcases-private-ex/").append(relativeTestDir).append("\n");
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
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, response.getBytes("UTF-8").length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes("UTF-8"));
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
    
    public static void main(String[] args) {
        try {
            // Setup logging
            LogManager.getLogManager().reset();
            Logger rootLogger = Logger.getLogger("");
            
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.INFO);
            consoleHandler.setFormatter(new SimpleFormatter());
            rootLogger.addHandler(consoleHandler);
            
            // File handler for detailed logging
            String logDir = System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log";
            new File(logDir).mkdirs();
            FileHandler fileHandler = new FileHandler(logDir + "/tester.log", true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new SimpleFormatter());
            rootLogger.addHandler(fileHandler);
            
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
            }));
            
            // Keep running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start Tester", e);
            System.exit(1);
        }
    }
}
