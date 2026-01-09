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
import com.navercorp.cubridqa.builder.tester.stats.TestExecutionMetrics;
import com.navercorp.cubridqa.builder.docker.SecureDockerEnv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class StandardDockerExecutor implements ExecutorStrategy {
    private static final String TESTCASE_MOUNT = EnvScriptFactory.TESTCASE_MOUNT;
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
        long startNs = System.nanoTime();
        TestExecutionMetrics.Builder metricsBuilder = TestExecutionMetrics.builder()
            .buildPackageName(request.getBuildPackage())
            .dockerImageCached(false)
            .metricsComplete(false);
        
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
            metricsBuilder.packageCached(buildCache.wasLastFetchFromCache());
            if (localBuildPackage != null) {
                metricsBuilder.buildPackageName(localBuildPackage.getFileName().toString());
            }
        } catch (Exception e) {
            return finalizeResult(
                TestResult.builder()
                    .testName(request.getTestName())
                    .status(TestStatus.ENVIRONMENT_ERROR)
                    .message("Failed to download build package: " + e.getMessage()),
                metricsBuilder, startNs, "docker", null);
        }
        
        if (localBuildPackage == null || !Files.exists(localBuildPackage)) {
            return finalizeResult(
                TestResult.builder()
                    .testName(request.getTestName())
                    .status(TestStatus.ENVIRONMENT_ERROR)
                    .message("Downloaded build package missing: " + String.valueOf(localBuildPackage)),
                metricsBuilder, startNs, "docker", null);
        }

        // Ensure shell testcases repository is on the requested branch (once per request)
        try {
            String requestId = RequestContext.getRequestId();
            shellTcSync.syncOncePerRequest(testLogger, requestId);
        } catch (Exception e) {
            testLogger.warning("Failed to sync shell testcases repo: " + e.getMessage());
        }
        
        // Copy build package from cache to Docker work directory
        Path dockerBuildPackage = dockerWorkDir.resolve("build.tar.gz");
        if (!localBuildPackage.equals(dockerBuildPackage)) {
            try {
                Files.copy(localBuildPackage, dockerBuildPackage, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                return finalizeResult(
                    TestResult.builder()
                        .testName(request.getTestName())
                        .status(TestStatus.ENVIRONMENT_ERROR)
                        .message("Failed to stage build package for Docker: " + e.getMessage()),
                    metricsBuilder, startNs, "docker", null);
            }
        }
        
        boolean customOnlyMode = "custom_script_test".equals(request.getTestPath());
        String relativeTestDir = "";
        if (!customOnlyMode) {
            // Resolve test case directory within the local shell testcases checkout
            Path sourceTestDir;
            try {
                sourceTestDir = TestDirectoryResolver.resolve(shellRepoRoot, request, testLogger);
            } catch (IllegalArgumentException e) {
                return finalizeResult(
                    TestResult.builder()
                        .testName(request.getTestName())
                        .status(TestStatus.ENVIRONMENT_ERROR)
                        .message(e.getMessage()),
                    metricsBuilder, startNs, "docker", null);
            }
            if (!Files.isReadable(sourceTestDir)) {
                return finalizeResult(
                    TestResult.builder()
                        .testName(request.getTestName())
                        .status(TestStatus.ENVIRONMENT_ERROR)
                        .message("Permission denied reading test directory: " + sourceTestDir),
                    metricsBuilder, startNs, "docker", null);
            }
            relativeTestDir = computeRelativeTestDir(shellRepoRoot, sourceTestDir, testLogger);
            testLogger.info("Using test directory: " + sourceTestDir + " (relative: " + (relativeTestDir.isEmpty() ? "." : relativeTestDir) + ")");
        } else {
            testLogger.info("Custom-only script mode: using /workspace/custom_test_execution/... inside container");
        }

        // Create test execution script for Docker (working directly from the overlay checkout)
        String dockerScript = EnvScriptFactory.createDockerScript(
            request.getTestScript(),
            request.getTestName(),
            request.getExpectedBuildVersion(),
            relativeTestDir,
            ctpHomeInContainer,
            request.getCustomShellScript(),
            request.getCustomAttachments(),
            customOnlyMode,
            request.getRequestId()
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
            return finalizeResult(
                TestResult.builder()
                    .testName(request.getTestName())
                    .status(TestStatus.ENVIRONMENT_ERROR)
                    .message("GITHUB_TOKEN environment variable not configured"),
                metricsBuilder, startNs, "docker", null);
        }

        // Create secure environment file for sensitive variables (GITHUB_TOKEN)
        // This prevents the token from appearing in ps -ef output
        try (SecureDockerEnv secureEnv = new SecureDockerEnv(dockerWorkDir)) {
            secureEnv.add("GITHUB_TOKEN", githubToken);
            String envFilePath = secureEnv.getFilePath();

            // Run Docker container
            List<String> dockerCommand = new ArrayList<>();
            dockerCommand.add("docker");
            dockerCommand.add("run");

        // Runtime limits from predicted demand or configured values (enforce admission control)
        com.navercorp.cubridqa.builder.tester.demand.PredictedDemand pd = request.getPredictedDemand();

        // CPU limits: only apply if explicitly enabled via config (default disabled to prioritize test success)
        boolean cpuLimitsApplied = false;
        double cpus = 0.0;
        if (config.isDockerEnforceCpuLimits()) {
            // Priority: configured value > predicted demand > minimum
            Integer configuredMillicores = config.getDockerCpuLimitMillicores();
            if (configuredMillicores != null) {
                // Use configured value
                cpus = Math.max(0.1, configuredMillicores / 1000.0);
                dockerCommand.add("--cpus=" + String.format(java.util.Locale.ROOT, "%.3f", cpus));
                cpuLimitsApplied = true;
                testLogger.fine(String.format("Using configured CPU limit: %d millicores (%.3f cpus)", configuredMillicores, cpus));
            } else if (pd != null) {
                // Use predicted demand
                cpus = Math.max(0.1, pd.getCpuMillicores() / 1000.0);
                dockerCommand.add("--cpus=" + String.format(java.util.Locale.ROOT, "%.3f", cpus));
                cpuLimitsApplied = true;
                testLogger.fine(String.format("Using predicted CPU demand: %d millicores (%.3f cpus)", pd.getCpuMillicores(), cpus));
            }
        }

        // Memory limits: only apply if explicitly enabled via config (default disabled to prioritize test success)
        boolean memLimitsApplied = false;
        long memBytes = 0;
        if (config.isDockerEnforceMemoryLimits()) {
            // Priority: configured value > predicted demand > minimum
            Integer configuredMb = config.getDockerMemoryLimitMb();
            if (configuredMb != null) {
                // Use configured value
                memBytes = Math.max(256L * 1024 * 1024, configuredMb * 1024L * 1024);
                dockerCommand.add("--memory=" + memBytes);
                dockerCommand.add("--memory-swap=" + memBytes); // Disable swap to prevent thrashing
                memLimitsApplied = true;
                testLogger.fine(String.format("Using configured memory limit: %d MB", configuredMb));
            } else if (pd != null) {
                // Use predicted demand
                memBytes = Math.max(256L * 1024 * 1024, pd.getMemBytes());
                dockerCommand.add("--memory=" + memBytes);
                dockerCommand.add("--memory-swap=" + memBytes); // Disable swap to prevent thrashing
                memLimitsApplied = true;
                testLogger.fine(String.format("Using predicted memory demand: %d bytes (%d MB)", pd.getMemBytes(), pd.getMemBytes() / (1024 * 1024)));
            }
        }

        // I/O throughput limits: apply read/write limits if configured
        boolean ioLimitsApplied = false;
        long ioReadBps = 0;
        long ioWriteBps = 0;
        Integer configuredReadMbps = config.getDockerIoReadLimitMbps();
        Integer configuredWriteMbps = config.getDockerIoWriteLimitMbps();

        if (configuredReadMbps != null || configuredWriteMbps != null) {
            // Detect or get configured block device
            String devicePath = config.getDockerIoDevice();
            if (devicePath == null) {
                devicePath = DockerIoLimits.detectRootDevice(testLogger);
            }

            if (devicePath != null) {
                if (configuredReadMbps != null) {
                    ioReadBps = configuredReadMbps * 1024L * 1024; // Convert MB/s to bytes/s
                    dockerCommand.add("--device-read-bps");
                    dockerCommand.add(devicePath + ":" + ioReadBps);
                    testLogger.fine(String.format("Using I/O read limit: %d MB/s (%d bytes/s) on %s",
                        configuredReadMbps, ioReadBps, devicePath));
                }

                if (configuredWriteMbps != null) {
                    ioWriteBps = configuredWriteMbps * 1024L * 1024; // Convert MB/s to bytes/s
                    dockerCommand.add("--device-write-bps");
                    dockerCommand.add(devicePath + ":" + ioWriteBps);
                    testLogger.fine(String.format("Using I/O write limit: %d MB/s (%d bytes/s) on %s",
                        configuredWriteMbps, ioWriteBps, devicePath));
                }

                ioLimitsApplied = true;
            } else {
                testLogger.warning("I/O limits configured but device detection failed. Limits not applied.");
            }
        }

        // Log the limits configuration
        StringBuilder limitsMsg = new StringBuilder("Docker limits: ");
        limitsMsg.append(cpuLimitsApplied ? String.format("cpus=%.3f", cpus) : "cpus=unlimited");
        limitsMsg.append(", ");
        limitsMsg.append(memLimitsApplied ? String.format("memory=%dMB", memBytes / (1024 * 1024)) : "memory=unlimited");
        if (ioLimitsApplied) {
            limitsMsg.append(", I/O: ");
            if (configuredReadMbps != null) {
                limitsMsg.append(String.format("read=%dMB/s", configuredReadMbps));
            }
            if (configuredWriteMbps != null) {
                if (configuredReadMbps != null) limitsMsg.append(" ");
                limitsMsg.append(String.format("write=%dMB/s", configuredWriteMbps));
            }
        }
        testLogger.info(limitsMsg.toString());

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
        dockerCommand.add("--cap-add");
        dockerCommand.add("SYS_ADMIN");
        dockerCommand.add("--security-opt");
        dockerCommand.add("apparmor=unconfined");
        dockerCommand.add("-v");
        dockerCommand.add(dockerWorkDir.toString() + ":/workspace");
        dockerCommand.add("-v");
        dockerCommand.add(shellRepoRoot.toString() + ":" + TESTCASE_MOUNT + ":rw");
        dockerCommand.add("-e");
        dockerCommand.add("CTP_HOME=" + ctpHomeInContainer);
        dockerCommand.add("-e");
        dockerCommand.add("init_path=" + ctpHomeInContainer + "/shell/init_path");
        
        // Pass host UID/GID for workspace ownership fix (allows cleanup without sudo)
        dockerCommand.add("-e");
        dockerCommand.add("HOST_UID=" + getHostUid());
        dockerCommand.add("-e");
        dockerCommand.add("HOST_GID=" + getHostGid());
        
        dockerCommand.add("-w");
        dockerCommand.add("/workspace");

        // Add secure environment file containing GITHUB_TOKEN
        dockerCommand.add("--env-file");
        dockerCommand.add(envFilePath);

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
        DockerStatsCollector statsCollector = (keepAlive || !config.isStatsEnabled())
            ? null
            : new DockerStatsCollector(containerName, testLogger, config.getDockerStatsIntervalMs());
        if (statsCollector != null) {
            statsCollector.start();
        }
        
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
            DockerStatsCollector.StatsSummary summary = stopCollector(statsCollector);
            return finalizeResult(
                TestResult.builder()
                    .testName(request.getTestName())
                    .status(TestStatus.PASS) // Special handling needed for keep-alive
                    .message("started")
                    .containerName(containerName)
                    .execCommand(execCmd)
                    .workspace(dockerWorkDir.toString()),
                metricsBuilder, startNs, "docker", summary);
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
            DockerStatsCollector.StatsSummary summary = stopCollector(statsCollector);
            return finalizeResult(
                TestResult.builder()
                    .testName(request.getTestName())
                    .status(TestStatus.EXECUTION_ERROR)
                    .message("Docker test timeout after " + timeoutMinutes + " minutes"),
                metricsBuilder, startNs, "docker", summary);
        }
        int exitCode = process.exitValue();
        outputGobbler.join(2000);
        String dockerOutput = outputGobbler.getOutput();
        testLogger.info("Docker test completed with exit code: " + exitCode);
        DockerStatsCollector.StatsSummary statsSummary = stopCollector(statsCollector);

        // Persist full docker output for diagnostics
        Path logFilePath = null;
        Path requestTestsDir = null;
        String logFileName = null;
        String safeTestName = request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_");
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                requestTestsDir = Paths.get(testsDir);
                // Include commit and attempt number in the log file name for uniqueness
                int attemptNumber = request.getAttemptNumber();
                if (attemptNumber == 1) {
                    logFileName = String.format("docker_%s_%s.log", request.getCommitShort(), safeTestName);
                } else {
                    logFileName = String.format("docker_%s_%s.%d.log", request.getCommitShort(), safeTestName, attemptNumber);
                }
                logFilePath = requestTestsDir.resolve(logFileName);
                Files.write(logFilePath, dockerOutput.getBytes("UTF-8"));
                testLogger.info("Saved full docker test log to: " + logFilePath);
            try {
                metricsBuilder.logSizeBytes(Files.size(logFilePath));
            } catch (Exception ignore) { }
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
            testLogger.info("Test result file (" + namedResult.toAbsolutePath() + "): " + resultContent.trim());
            
            if (requestTestsDir != null) {
                try {
                    int attemptNumber = request.getAttemptNumber();
                    String persistedName = (attemptNumber == 1)
                        ? String.format("result_%s_%s.result", request.getCommitShort(), safeTestName)
                        : String.format("result_%s_%s.%d.result", request.getCommitShort(), safeTestName, attemptNumber);
                    Path persistedResult = requestTestsDir.resolve(persistedName);
                    Files.copy(namedResult, persistedResult, StandardCopyOption.REPLACE_EXISTING);
                    testLogger.info("Saved result snapshot to: " + persistedResult);
                } catch (Exception copyError) {
                    testLogger.warning("Failed to persist result file: " + copyError.getMessage());
                }
            }
             
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
             
            // Robust status parsing: only treat tokens after ':' as verdicts, ignore words like 'broker_start_fail'
            boolean isFailed = java.util.regex.Pattern
                .compile("(?mi)^.*:\\s*(NOK|FAIL)\\b|Internal\\s+Error")
                .matcher(resultContent)
                .find();
            boolean isPassed = java.util.regex.Pattern
                .compile("(?mi)^.*:\\s*(OK|PASS)\\b")
                .matcher(resultContent)
                .find();

            if (isPassed && !isFailed) {
                resultBuilder.status(TestStatus.PASS);
            } else if (isFailed) {
                resultBuilder.status(TestStatus.FAIL);
            } else {
                resultBuilder.status(TestStatus.EXECUTION_ERROR)
                       .message("Could not determine test result");
            }
             
             return finalizeResult(resultBuilder, metricsBuilder, startNs, "docker", statsSummary);
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
                keepCmd.add("--cap-add"); keepCmd.add("SYS_ADMIN");
                keepCmd.add("--security-opt"); keepCmd.add("apparmor=unconfined");
                keepCmd.add("-v"); keepCmd.add(dockerWorkDir.toString() + ":/workspace");
                keepCmd.add("-v"); keepCmd.add(shellRepoRoot.toString() + ":" + TESTCASE_MOUNT + ":rw");
                keepCmd.add("--env-file"); keepCmd.add(envFilePath);
                keepCmd.add("-e"); keepCmd.add("CTP_HOME=" + ctpHomeInContainer);
                keepCmd.add("-e"); keepCmd.add("init_path=" + ctpHomeInContainer + "/shell/init_path");
                keepCmd.add("-e"); keepCmd.add("HOST_UID=" + getHostUid());
                keepCmd.add("-e"); keepCmd.add("HOST_GID=" + getHostGid());
                keepCmd.add("-w"); keepCmd.add("/workspace");
                keepCmd.add("--entrypoint"); keepCmd.add("bash");
                keepCmd.add(config.getDockerTestImage());
                keepCmd.add("-lc"); keepCmd.add("echo READY; tail -f /dev/null");
                try { new ProcessBuilder(keepCmd).start().waitFor(5, TimeUnit.SECONDS); } catch (Exception ignore) {}
                testLogger.info("Failure container kept for debugging: " + containerName);
                String execCmd = "docker exec -it " + containerName + " bash";
                
                return finalizeResult(
                    TestResult.builder()
                        .testName(request.getTestName())
                        .status(TestStatus.EXECUTION_ERROR)
                        .message("Docker test execution failed; container kept for debugging")
                        .commit(request.getCommit() != null ? request.getCommit() : "unknown")
                        .commitShort(request.getCommitShort())
                        .containerName(containerName)
                        .execCommand(execCmd)
                        .workspace(dockerWorkDir.toString())
                        .timestamp(System.currentTimeMillis()),
                    metricsBuilder, startNs, "docker", statsSummary);
            }
            
            return finalizeResult(
                TestResult.builder()
                    .testName(request.getTestName())
                    .status(TestStatus.EXECUTION_ERROR)
                    .message("Docker test execution failed")
                    .commit(request.getCommit() != null ? request.getCommit() : "unknown")
                    .commitShort(request.getCommitShort())
                    .exitCode(exitCode)
                    .timestamp(System.currentTimeMillis()),
                metricsBuilder, startNs, "docker", statsSummary);
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

        return finalizeResult(missingResultBuilder, metricsBuilder, startNs, "docker", statsSummary);
        } // SecureDockerEnv auto-closes here, removing the env file
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

    private TestResult finalizeResult(TestResult.Builder builder, TestExecutionMetrics.Builder metricsBuilder,
                                      long startNs, String executionMode,
                                      DockerStatsCollector.StatsSummary statsSummary) {
        long durationMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
        metricsBuilder.durationMs(durationMs);
        if (statsSummary != null && statsSummary.hasSamples()) {
            statsSummary.applyTo(metricsBuilder, durationMs);
        }
        TestExecutionMetrics metrics = metricsBuilder.build();
        builder.executionMetrics(metrics);
        if (executionMode != null) {
            builder.executionMode(executionMode);
        }
        return builder.build();
    }

    private DockerStatsCollector.StatsSummary stopCollector(DockerStatsCollector collector) {
        if (collector == null) {
            return null;
        }
        return collector.stopAndSummarize();
    }
    
    /**
     * Get the host user's UID for ownership fix inside container
     */
    private static String getHostUid() {
        try {
            ProcessBuilder pb = new ProcessBuilder("id", "-u");
            Process p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String uid = reader.readLine();
                p.waitFor();
                return uid != null ? uid.trim() : "1000";
            }
        } catch (Exception e) {
            return "1000"; // Default fallback
        }
    }
    
    /**
     * Get the host user's GID for ownership fix inside container
     */
    private static String getHostGid() {
        try {
            ProcessBuilder pb = new ProcessBuilder("id", "-g");
            Process p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String gid = reader.readLine();
                p.waitFor();
                return gid != null ? gid.trim() : "1000";
            }
        } catch (Exception e) {
            return "1000"; // Default fallback
        }
    }
}
