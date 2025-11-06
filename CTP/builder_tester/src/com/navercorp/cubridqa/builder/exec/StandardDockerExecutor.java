package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.tester.TestRequest;
import com.navercorp.cubridqa.builder.tester.TestResult;
import com.navercorp.cubridqa.builder.tester.TestStatus;
import com.navercorp.cubridqa.builder.cache.BuildCache;
import com.navercorp.cubridqa.builder.git.ShellTcSync;
import com.navercorp.cubridqa.builder.tester.SafeIo;
import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class StandardDockerExecutor implements ExecutorStrategy {
    private final Config config;
    private final BuildCache buildCache;
    private final ShellTcSync shellTcSync;

    public StandardDockerExecutor(Config config, BuildCache buildCache, ShellTcSync shellTcSync) {
        this.config = config;
        this.buildCache = buildCache;
        this.shellTcSync = shellTcSync;
    }

    @Override
    public TestResult execute(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        testLogger.info("Running test in Docker container...");
        
        boolean keepAlive = request.isKeepAlive();
        String containerName = request.getContainerName();
        if (containerName == null || containerName.trim().isEmpty()) {
            String buildType = request.getBuildType() != null ? request.getBuildType() : "debug";
            containerName = "tester_" + buildType + "_" + request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_") + "_" + System.currentTimeMillis();
        }
        
        Path dockerWorkDir = Files.createTempDirectory(workDir, "docker_");
        testLogger.info("Docker work dir: " + dockerWorkDir.toString());

        Path shellRepoRoot = Paths.get(config.getShellTcDir()).toAbsolutePath().normalize();
        Path ctpSourceRoot = Paths.get(System.getProperty("user.home"), "cubrid-testtools", "CTP").toAbsolutePath().normalize();
        Path ctpWorkRoot = dockerWorkDir.resolve("CTP");
        stageCtpResources(ctpSourceRoot, ctpWorkRoot, testLogger);
        String ctpHomeInContainer = "/workspace/CTP";
        
        // Download build package if it's a URL (use shared cache dir to avoid per-test races)
        Path localBuildPackage;
        try {
            Path sharedCacheDir = Paths.get(config.getWorkDir(), "cache");
            try { Files.createDirectories(sharedCacheDir); } catch (Exception ignore) {}
            localBuildPackage = buildCache.downloadIfNeeded(
                request.getBuildPackage(), 
                request.getCommitShort() != null ? request.getCommitShort() : "unknown", 
                "unknown", 
                testLogger
            );
        } catch (Exception e) {
            return TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.ENVIRONMENT_ERROR)
                .message("Failed to download build package: " + e.getMessage())
                .build();
        }
        
        if (localBuildPackage == null || !Files.exists(localBuildPackage)) {
            return TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.ENVIRONMENT_ERROR)
                .message("Downloaded build package missing: " + String.valueOf(localBuildPackage))
                .build();
        }

        // Ensure shell testcases repository is on the requested branch from preferred remote
        try {
            shellTcSync.sync(testLogger);
        } catch (Exception e) {
            testLogger.warning("Failed to sync shell testcases repo: " + e.getMessage());
        }
        
        // Copy build package from cache to Docker work directory
        Path dockerBuildPackage = dockerWorkDir.resolve("build.tar.gz");
        if (!localBuildPackage.equals(dockerBuildPackage)) {
            try {
                Files.copy(localBuildPackage, dockerBuildPackage, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                return TestResult.builder()
                    .testName(request.getTestName())
                    .status(TestStatus.ENVIRONMENT_ERROR)
                    .message("Failed to stage build package for Docker: " + e.getMessage())
                    .build();
            }
        }
        
        // Resolve test case directory within the shell testcases repository
        Path sourceTestDir = Paths.get(request.getTestDir());
        if (!Files.exists(sourceTestDir)) {
            String testPathFull = request.getTestPath();
            if (testPathFull != null && testPathFull.contains("/")) {
                String relDir = testPathFull.substring(0, testPathFull.lastIndexOf("/"));
                Path fallbackDir = Paths.get(config.getShellTcDir(), relDir);
                if (Files.exists(fallbackDir)) {
                    testLogger.warning("Provided testDir not found on tester; using fallback: " + fallbackDir.toString());
                    sourceTestDir = fallbackDir;
                } else {
                    return TestResult.builder()
                        .testName(request.getTestName())
                        .status(TestStatus.ENVIRONMENT_ERROR)
                        .message("Test directory not found on tester: " + sourceTestDir.toString())
                        .build();
                }
            } else {
                return TestResult.builder()
                    .testName(request.getTestName())
                    .status(TestStatus.ENVIRONMENT_ERROR)
                    .message("Invalid testPath; cannot resolve test directory")
                    .build();
            }
        }
        if (!Files.isDirectory(sourceTestDir)) {
            return TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.ENVIRONMENT_ERROR)
                .message("Test directory is not a directory: " + sourceTestDir)
                .build();
        }
        if (!Files.isReadable(sourceTestDir)) {
            return TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.ENVIRONMENT_ERROR)
                .message("Permission denied reading test directory: " + sourceTestDir)
                .build();
        }
        String relativeTestDir = computeRelativeTestDir(shellRepoRoot, sourceTestDir, testLogger);
        testLogger.info("Using test directory: " + sourceTestDir + " (relative: " + (relativeTestDir.isEmpty() ? "." : relativeTestDir) + ")");

        // Create test execution script for Docker (working directly from the overlay checkout)
        String dockerScript = EnvScriptFactory.createDockerScript(
            request.getTestScript(), 
            request.getTestName(), 
            request.getExpectedBuildVersion(), 
            relativeTestDir,
            ctpHomeInContainer
        );
        Path dockerScriptPath = dockerWorkDir.resolve("run_test.sh");
        Files.write(dockerScriptPath, dockerScript.getBytes());
        dockerScriptPath.toFile().setExecutable(true);
        
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                String safeTestNameForScript = request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_");
                // Include commit in the script name for uniqueness
                Path scriptLogPath = Paths.get(testsDir, String.format("docker_script_%s_%s.sh", request.getCommitShort(), safeTestNameForScript));
                Files.write(scriptLogPath, dockerScript.getBytes("UTF-8"));
                testLogger.info("Saved generated Docker test script to: " + scriptLogPath.toString());
            }
        } catch (Exception ignore) { }
        
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
        
        // Run Docker container
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
        dockerCommand.add("-v");
        dockerCommand.add(dockerWorkDir.toString() + ":/workspace");
        dockerCommand.add("-v");
        dockerCommand.add(shellRepoRoot.toString() + ":/workspace/testcases:rw");
        dockerCommand.add("-e");
        dockerCommand.add("GITHUB_TOKEN=" + githubToken);
        dockerCommand.add("-e");
        dockerCommand.add("CTP_HOME=" + ctpHomeInContainer);
        dockerCommand.add("-e");
        dockerCommand.add("init_path=" + ctpHomeInContainer + "/shell/init_path");
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

        // Persist full docker output for diagnostics
        Path logFilePath = null;
        String logFileName = null;
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                String safeTestName = request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_");
                // Include commit and attempt number in the log file name for uniqueness
                int attemptNumber = request.getAttemptNumber();
                if (attemptNumber == 1) {
                    logFileName = String.format("docker_%s_%s.log", request.getCommitShort(), safeTestName);
                } else {
                    logFileName = String.format("docker_%s_%s.%d.log", request.getCommitShort(), safeTestName, attemptNumber);
                }
                logFilePath = Paths.get(testsDir, logFileName);
                Files.write(logFilePath, dockerOutput.getBytes("UTF-8"));
                testLogger.info("Saved full docker test log to: " + logFilePath.toString());
            }
        } catch (Exception ignore) {
            // Swallow logging persistence issues; primary result below still returned
        }
 
         // Check for result file derived from testScript base name
         String resultBaseFromScript = request.getTestScript().endsWith(".sh")
             ? request.getTestScript().substring(0, request.getTestScript().length() - 3)
             : (request.getTestScript().contains(".") ? request.getTestScript().substring(0, request.getTestScript().lastIndexOf('.')) : request.getTestScript());
        Path namedResult = dockerWorkDir.resolve(resultBaseFromScript + ".result");
        if (!Files.exists(namedResult)) {
            Path testcasesRoot = dockerWorkDir.resolve("testcases");
            Path fallback = testcasesRoot.resolve(".");
            if (relativeTestDir != null && !relativeTestDir.isEmpty()) {
                fallback = testcasesRoot.resolve(relativeTestDir);
            }
            fallback = fallback.resolve(resultBaseFromScript + ".result").normalize();
            if (Files.exists(fallback)) {
                namedResult = fallback;
            }
        }

        if (!Files.exists(namedResult)) {
            Path repoResult = shellRepoRoot;
            if (relativeTestDir != null && !relativeTestDir.isEmpty()) {
                repoResult = repoResult.resolve(relativeTestDir);
            }
            repoResult = repoResult.resolve(resultBaseFromScript + ".result").normalize();
            if (Files.exists(repoResult)) {
                namedResult = repoResult;
            }
        }

        if (Files.exists(namedResult)) {
            String resultContent = new String(Files.readAllBytes(namedResult));
            testLogger.info("Test result file (" + namedResult.getFileName() + "): " + resultContent.trim());
             
             // Extract execution time from result if available
             String executionTime = extractExecutionTime(resultContent);
             
             TestResult.Builder resultBuilder = TestResult.builder()
                 .testName(request.getTestName())
                 .commit(request.getCommit() != null ? request.getCommit() : "unknown")
                 .commitShort(request.getCommitShort())
                 .executionMode("docker")
                 .timestamp(System.currentTimeMillis());
             
             if (logFilePath != null) {
                 resultBuilder.addAttemptLogFile(logFilePath);
             }
             
             if (executionTime != null) {
                 resultBuilder.executionTime(executionTime);
             }
             
             if (resultContent.contains("NOK") || resultContent.contains("FAIL")) {
                 resultBuilder.status(TestStatus.FAIL);
             } else if (resultContent.contains("OK") || resultContent.contains("PASS")) {
                 resultBuilder.status(TestStatus.PASS);
             } else {
                 resultBuilder.status(TestStatus.EXECUTION_ERROR)
                        .message("Could not determine test result");
             }
             
             return resultBuilder.build();
        }

        // Check for Docker-specific errors
        if (exitCode != 0) {
            if (dockerOutput.contains("docker: command not found")) {
                testLogger.warning("Docker not available, falling back to direct execution");
                // Signal fallback needed
                throw new Exception("Docker not available");
            }
            // Keep container on failure if configured: rerun in detached mode for debugging
            if (config.getKeepFailedContainers()) {
                try { Files.createFile(workDir.resolve("KEEP_WORKSPACE")); } catch (Exception ignore) {}
                List<String> keepCmd = new ArrayList<>();
                keepCmd.add("docker"); keepCmd.add("run"); keepCmd.add("-d");
                keepCmd.add("--name"); keepCmd.add(containerName);
                keepCmd.add("-v"); keepCmd.add(dockerWorkDir.toString() + ":/workspace");
                keepCmd.add("-v"); keepCmd.add(shellRepoRoot.toString() + ":/workspace/testcases:rw");
                keepCmd.add("-e"); keepCmd.add("GITHUB_TOKEN=" + githubToken);
                keepCmd.add("-e"); keepCmd.add("CTP_HOME=" + ctpHomeInContainer);
                keepCmd.add("-e"); keepCmd.add("init_path=" + ctpHomeInContainer + "/shell/init_path");
                keepCmd.add("-w"); keepCmd.add("/workspace");
                keepCmd.add("--entrypoint"); keepCmd.add("bash");
                keepCmd.add(config.getDockerTestImage());
                keepCmd.add("-lc"); keepCmd.add("echo READY; tail -f /dev/null");
                try { new ProcessBuilder(keepCmd).start().waitFor(5, TimeUnit.SECONDS); } catch (Exception ignore) {}
                testLogger.info("Failure container kept for debugging: " + containerName);
                String execCmd = "docker exec -it " + containerName + " bash";
                
                return TestResult.builder()
                    .testName(request.getTestName())
                    .status(TestStatus.EXECUTION_ERROR)
                    .message("Docker test execution failed; container kept for debugging")
                    .commit(request.getCommit() != null ? request.getCommit() : "unknown")
                    .commitShort(request.getCommitShort())
                    .containerName(containerName)
                    .execCommand(execCmd)
                    .workspace(dockerWorkDir.toString())
                    .timestamp(System.currentTimeMillis())
                    .build();
            }
            
            return TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.EXECUTION_ERROR)
                .message("Docker test execution failed")
                .commit(request.getCommit() != null ? request.getCommit() : "unknown")
                .commitShort(request.getCommitShort())
                .exitCode(exitCode)
                .timestamp(System.currentTimeMillis())
                .build();
        }

        TestResult.Builder missingResultBuilder = TestResult.builder()
            .testName(request.getTestName())
            .commit(request.getCommit() != null ? request.getCommit() : "unknown")
            .commitShort(request.getCommitShort())
            .executionMode("docker")
            .timestamp(System.currentTimeMillis())
            .status(TestStatus.EXECUTION_ERROR)
            .message("No result file produced by test script");

        if (logFilePath != null) {
            missingResultBuilder.addAttemptLogFile(logFilePath);
        }

        return missingResultBuilder.build();
    }
    
    private String computeRelativeTestDir(Path repoRoot, Path testDir, Logger logger) {
        try {
            Path repoReal;
            Path testReal;
            try {
                repoReal = repoRoot.toRealPath();
            } catch (IOException e) {
                repoReal = repoRoot.toAbsolutePath().normalize();
            }
            try {
                testReal = testDir.toRealPath();
            } catch (IOException e) {
                testReal = testDir.toAbsolutePath().normalize();
            }

            if (testReal.startsWith(repoReal)) {
                String rel = repoReal.relativize(testReal).toString().replace('\\', '/');
                return rel.isEmpty() ? "" : rel;
            }
        } catch (Exception e) {
            logger.fine("Unable to compute relative path for test directory: " + e.getMessage());
        }
        logger.fine("Test directory " + testDir + " is outside repository root " + repoRoot + "; using repo root.");
        return "";
    }

    private void stageCtpResources(Path sourceRoot, Path targetRoot, Logger logger) throws IOException {
        SafeIo.deleteDirectory(targetRoot.toFile());
        Files.createDirectories(targetRoot);
        String[] requiredDirs = {"shell", "bin", "common", "conf"};
        for (String dir : requiredDirs) {
            Path sourceDir = sourceRoot.resolve(dir);
            Path targetDir = targetRoot.resolve(dir);
            if (Files.exists(sourceDir)) {
                SafeIo.copyTestCaseDirectory(sourceDir, targetDir);
            } else {
                logger.fine("CTP component missing: " + sourceDir);
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
}
