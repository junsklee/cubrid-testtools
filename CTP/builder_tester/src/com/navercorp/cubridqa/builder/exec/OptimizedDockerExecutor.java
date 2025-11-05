package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.tester.TestRequest;
import com.navercorp.cubridqa.builder.tester.TestResult;
import com.navercorp.cubridqa.builder.tester.TestStatus;
import com.navercorp.cubridqa.builder.cache.BuildCache;
import com.navercorp.cubridqa.builder.git.ShellTcSync;
import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OptimizedDockerExecutor implements ExecutorStrategy {
    private final Config config;
    private final BuildCache buildCache;
    private final ShellTcSync shellTcSync;
    private final Object imageBuilder; // DockerImageBuilder - using Object to avoid compile dependency

    public OptimizedDockerExecutor(Config config, BuildCache buildCache, ShellTcSync shellTcSync, Object imageBuilder) {
        this.config = config;
        this.buildCache = buildCache;
        this.shellTcSync = shellTcSync;
        this.imageBuilder = imageBuilder;
    }

    @Override
    public TestResult execute(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        testLogger.info("Running test in optimized Docker container with pre-built image...");
        
        boolean keepAlive = request.isKeepAlive();
        String containerName = request.getContainerName();
        if (containerName == null || containerName.trim().isEmpty()) {
            // Extract commit hash from Docker image name for container naming
            String commitHash = "unknown";
            if (request.getCommitShort() != null && !request.getCommitShort().trim().isEmpty()) {
                commitHash = request.getCommitShort();
            }

            // Extract build type for container naming
            String buildType = request.getBuildType() != null ? request.getBuildType() : "debug";

            // Generate unique container name with timestamp to avoid conflicts
            String uniqueId = String.valueOf(System.currentTimeMillis());
            containerName = "tester_" + buildType + "_" + commitHash + "_" + request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_") + "_" + uniqueId;
        }
        
        Path dockerWorkDir = Files.createTempDirectory(workDir, "docker_");
        testLogger.info("Docker work dir: " + dockerWorkDir.toString());
        
        // Download build package if it's a URL
        Path localBuildPackage;
        try {
            Path sharedCacheDir = Paths.get(config.getWorkDir(), "cache");
            Files.createDirectories(sharedCacheDir);
            localBuildPackage = buildCache.downloadIfNeeded(
                request.getBuildPackage(), 
                request.getCommitShort() != null ? request.getCommitShort() : "unknown", 
                request.getBaselineShort() != null ? request.getBaselineShort() : "unknown", 
                testLogger
            );
        } catch (Exception e) {
            return TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.ENVIRONMENT_ERROR)
                .message("Failed to download build package: " + e.getMessage())
                .build();
        }
        
        // Build or get Docker image with CUBRID pre-installed
        String dockerImage;
        try {
            if (imageBuilder != null) {
                // Use reflection to call getOrBuildImage method
                dockerImage = (String) imageBuilder.getClass()
                    .getMethod("getOrBuildImage", String.class, String.class, Path.class)
                    .invoke(imageBuilder, 
                           request.getCommitShort() != null ? request.getCommitShort() : "unknown", 
                           request.getBaselineShort() != null ? request.getBaselineShort() : "unknown", 
                           localBuildPackage);
                testLogger.info("Using Docker image: " + dockerImage);
            } else {
                testLogger.info("Image builder not available, falling back to standard execution");
                throw new Exception("Image builder not available");
            }
        } catch (Exception e) {
            Throwable root = e instanceof InvocationTargetException ? ((InvocationTargetException) e).getTargetException() : e;
            String message = root != null && root.getMessage() != null ? root.getMessage() : e.toString();
            testLogger.log(Level.WARNING, "Failed to build optimized Docker image: " + message, root != null ? root : e);
            throw e;  // Let the calling method handle fallback
        }
        
        // Ensure shell testcases repository is on the requested branch
        try {
            shellTcSync.sync(testLogger);
        } catch (Exception e) {
            testLogger.warning("Failed to sync shell testcases repo: " + e.getMessage());
        }
        
        // Copy test case directory to isolated workspace
        Path testCasesDir = dockerWorkDir.resolve("testcases");
        Files.createDirectories(testCasesDir);
        Path sourceTestDir = Paths.get(request.getTestDir());
        if (!Files.exists(sourceTestDir)) {
            String testPathFull = request.getTestPath();
            if (testPathFull != null && testPathFull.contains("/")) {
                String relDir = testPathFull.substring(0, testPathFull.lastIndexOf("/"));
                Path fallbackDir = Paths.get(config.getShellTcDir(), relDir);
                if (Files.exists(fallbackDir)) {
                    testLogger.warning("Provided testDir not found; using fallback: " + fallbackDir);
                    sourceTestDir = fallbackDir;
                } else {
                    return TestResult.builder()
                        .testName(request.getTestName())
                        .status(TestStatus.ENVIRONMENT_ERROR)
                        .message("Test directory not found: " + sourceTestDir)
                        .build();
                }
            }
        }
        
        try {
            copyTestCaseDirectory(sourceTestDir, testCasesDir);
        } catch (IOException e) {
            return TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.ENVIRONMENT_ERROR)
                .message("Failed to copy test cases: " + e.getMessage())
                .build();
        }
        testLogger.info("Copied test case directory to isolated workspace");
        
        // Create simplified test script (no CUBRID extraction needed!)
        String dockerScript = EnvScriptFactory.createDockerOptimizedScript(
            request.getTestScript(), 
            request.getTestName(), 
            request.getExpectedBuildVersion()
        );
        Path dockerScriptPath = dockerWorkDir.resolve("run_test.sh");
        Files.write(dockerScriptPath, dockerScript.getBytes());
        dockerScriptPath.toFile().setExecutable(true);
        
        // Save script for debugging
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                String safeTestName = request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_");
                Path scriptLogPath = Paths.get(testsDir, 
                    String.format("docker_script_opt_%s_%s.sh", request.getCommitShort(), safeTestName));
                Files.write(scriptLogPath, dockerScript.getBytes("UTF-8"));
                testLogger.info("Saved optimized Docker test script to: " + scriptLogPath);
            }
        } catch (Exception ignore) {}
        
        // Check for GitHub token
        String githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.trim().isEmpty()) {
            testLogger.severe("GITHUB_TOKEN not set");
            return TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.ENVIRONMENT_ERROR)
                .message("GITHUB_TOKEN environment variable not configured")
                .build();
        }
        
        // Run Docker container with optimized flags
        List<String> dockerCommand = new ArrayList<>();
        dockerCommand.add("docker");
        dockerCommand.add("run");
        // Limit container log growth to prevent host disk exhaustion
        dockerCommand.add("--log-driver");
        dockerCommand.add("json-file");
        dockerCommand.add("--log-opt");
        dockerCommand.add("max-size=50m");
        dockerCommand.add("--log-opt");
        dockerCommand.add("max-file=3");
        if (keepAlive) {
            dockerCommand.add("-d");
        } else {
            dockerCommand.add("--rm");
        }
        // Always name the container so we can manage it on timeout/failure
        dockerCommand.add("--name");
        dockerCommand.add(containerName);
        
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
            
            // Return special "started" status for keep-alive containers
            return TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.PASS) // Special handling needed for keep-alive
                .message("started")
                .containerName(containerName)
                .execCommand(execCmd)
                .workspace(dockerWorkDir.toString())
                .build();
        }
        
        // Read output
        ProcessIO.StreamReader outputGobbler = new ProcessIO.StreamReader(process.getInputStream(), "DOCKER");
        outputGobbler.start();
        int timeoutMinutes = Math.max(1, config.getTestReadTimeoutMinutes());
        boolean completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            testLogger.severe("Docker test timeout");
            // Attempt to stop and remove the container if it's still running
            DockerCtl.safeKillAndRemove(containerName, testLogger);
            return TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.EXECUTION_ERROR)
                .message("Docker test timeout after " + timeoutMinutes + " minutes")
                .build();
        }
        
        int exitCode = process.exitValue();
        outputGobbler.join(2000);
        String dockerOutput = outputGobbler.getOutput();
        testLogger.info("Docker test completed with exit code: " + exitCode);
        
        // Save output log
        Path dockerOptLogFilePath = null;
        String dockerOptLogFileName = null;
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                String safeTestName = request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_");
                // Include attempt number in the log file name for uniqueness
                int attemptNumber = request.getAttemptNumber();
                dockerOptLogFileName = generateDockerOptLogFileName(request.getCommitShort(), request.getTestName(), attemptNumber);
                dockerOptLogFilePath = Paths.get(testsDir, dockerOptLogFileName);
                Files.write(dockerOptLogFilePath, dockerOutput.getBytes("UTF-8"));
                testLogger.info("Saved optimized Docker test log to: " + dockerOptLogFilePath);
            } else {
                testLogger.warning("Log not saved - requestId: " + requestId + ", groupingEnabled: " + config.isRequestGroupingEnabled());
            }
        } catch (Exception e) {
            testLogger.log(Level.SEVERE, "Failed to save test log: " + e.getMessage(), e);
        }
        
        // Check for result file
        String resultBase = request.getTestScript().endsWith(".sh") ? 
            request.getTestScript().substring(0, request.getTestScript().length() - 3) : request.getTestScript();
        Path namedResult = dockerWorkDir.resolve(resultBase + ".result");
        
        if (Files.exists(namedResult)) {
            String resultContent = new String(Files.readAllBytes(namedResult));
            testLogger.info("Test result file content: " + resultContent);
            
            boolean isPassed = resultContent.contains("OK") || 
                              resultContent.toUpperCase().contains("PASS");
            boolean isFailed = resultContent.contains("NOK") || 
                              resultContent.toUpperCase().contains("FAIL");
            
            TestResult.Builder resultBuilder = TestResult.builder()
                .testName(request.getTestName())
                .commit(request.getCommit() != null ? request.getCommit() : "unknown")
                .commitShort(request.getCommitShort())
                .executionMode("docker_optimized")
                .timestamp(System.currentTimeMillis());
            
            if (dockerOptLogFilePath != null) {
                resultBuilder.addAttemptLogFile(dockerOptLogFilePath);
            }
            
            if (isPassed && !isFailed) {
                resultBuilder.status(TestStatus.PASS);
                return resultBuilder.build();
            } else if (isFailed) {
                resultBuilder.status(TestStatus.FAIL);
                return resultBuilder.build();
            }
        }
        
        // Check exit code
        TestResult.Builder resultBuilder = TestResult.builder()
            .testName(request.getTestName())
            .commit(request.getCommit() != null ? request.getCommit() : "unknown")
            .commitShort(request.getCommitShort())
            .executionMode("docker_optimized")
            .timestamp(System.currentTimeMillis());
            
        if (dockerOptLogFilePath != null) {
            resultBuilder.addAttemptLogFile(dockerOptLogFilePath);
        }
        
        if (exitCode == 0) {
            resultBuilder.status(TestStatus.PASS);
        } else {
            resultBuilder.status(TestStatus.FAIL).exitCode(exitCode);
        }
        
        return resultBuilder.build();
    }
    
    private void copyTestCaseDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(source) || !Files.isDirectory(source)) {
            throw new IOException("Source test directory does not exist: " + source);
        }
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                try {
                    Files.createDirectories(targetDir);
                } catch (IOException e) {
                    throw e;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                try {
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    // Fail fast on permission errors to surface clear message
                    throw e;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                // Propagate AccessDeniedException or any IO error to caller for proper HTTP error response
                throw exc != null ? exc : new IOException("Failed visiting: " + file);
            }
        });
    }
    
    /**
     * Generate docker optimized log filename with attempt number for uniqueness
     */
    private String generateDockerOptLogFileName(String commitShort, String testName, int attemptNumber) {
        String safeTestName = testName.replaceAll("[^a-zA-Z0-9_.-]", "_");
        if (attemptNumber == 1) {
            return String.format("docker_opt_%s_%s.log", commitShort, safeTestName);
        } else {
            return String.format("docker_opt_%s_%s.%d.log", commitShort, safeTestName, attemptNumber);
        }
    }
}
