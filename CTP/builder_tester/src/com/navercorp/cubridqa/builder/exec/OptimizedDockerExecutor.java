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
import com.navercorp.cubridqa.builder.tester.CancelledRequests;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OptimizedDockerExecutor implements ExecutorStrategy {
    private static final String TESTCASE_MOUNT = EnvScriptFactory.TESTCASE_MOUNT;
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
        
        long startNs = System.nanoTime();
        TestExecutionMetrics.Builder metricsBuilder = TestExecutionMetrics.builder()
            .buildPackageName(request.getBuildPackage())
            .metricsComplete(false);
        
        boolean keepAlive = request.isKeepAlive();
        String containerName = request.getContainerName();
        if (containerName == null || containerName.trim().isEmpty()) {
            // Extract commit hash from Docker image name for container naming
            String commitHash = "unknown";
            if (request.getCommitShort() != null && !request.getCommitShort().trim().isEmpty()) {
                commitHash = request.getCommitShort();
            }

            // Extract build type for container naming
            String buildType = request.getBuildType() != null ? request.getBuildType() : "release";

            // Generate unique container name with timestamp to avoid conflicts
            String uniqueId = String.valueOf(System.currentTimeMillis());
            containerName = "tester_" + buildType + "_" + commitHash + "_" + request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_") + "_" + uniqueId;
        }
        
        Path dockerWorkDir = Files.createTempDirectory(workDir, "docker_");
        testLogger.info("Docker work dir: " + dockerWorkDir.toString());

        Path shellRepoRoot = Paths.get(config.getShellTcDir()).toAbsolutePath().normalize();
        Path ctpSourceRoot = Paths.get(System.getProperty("user.home"), "cubrid-testtools", "CTP").toAbsolutePath().normalize();
        Path ctpWorkRoot = dockerWorkDir.resolve("CTP");
        stageCtpResources(ctpSourceRoot, ctpWorkRoot, testLogger);
        String ctpHomeInContainer = "/workspace/CTP";

        // Check if Docker image already exists BEFORE downloading package
        // This optimization avoids unnecessary package downloads when image is cached
        String commitShort = request.getCommitShort() != null ? request.getCommitShort() : "unknown";
        String baselineShort = request.getBaselineShort() != null ? request.getBaselineShort() : "unknown";
        boolean imageExistsAlready = false;

        if (imageBuilder != null) {
            try {
                Object hasImageResult = imageBuilder.getClass()
                    .getMethod("hasImage", String.class, String.class)
                    .invoke(imageBuilder, commitShort, baselineShort);
                if (hasImageResult instanceof Boolean) {
                    imageExistsAlready = (Boolean) hasImageResult;
                    if (imageExistsAlready) {
                        testLogger.info("Docker image already exists for " + commitShort + "_" + baselineShort +
                                      " - skipping package download");
                    }
                }
            } catch (NoSuchMethodException e) {
                // hasImage method not available, fall through to always download
                testLogger.fine("DockerImageBuilder.hasImage not available, downloading package");
            } catch (Exception e) {
                testLogger.fine("Failed to check image existence: " + e.getMessage());
            }
        }

        // Download build package only if needed (image doesn't exist or check failed)
        Path localBuildPackage = null;
        if (!imageExistsAlready) {
            try {
                Path sharedCacheDir = Paths.get(config.getWorkDir(), "cache");
                Files.createDirectories(sharedCacheDir);
                localBuildPackage = buildCache.downloadIfNeeded(
                    request.getBuildPackage(),
                    commitShort,
                    baselineShort,
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
                    metricsBuilder, startNs, null, false, null);
            }
        } else {
            // Image exists, no package needed - mark as effectively cached
            metricsBuilder.packageCached(true);
            testLogger.info("Package download skipped - Docker image is cached");
        }

        // Build or get Docker image with CUBRID pre-installed
        String dockerImage = null;
        boolean dockerImageCached = false;
        try {
            if (imageBuilder != null) {
                // Use reflection to call getOrBuildImage method
                dockerImage = (String) imageBuilder.getClass()
                    .getMethod("getOrBuildImage", String.class, String.class, Path.class)
                    .invoke(imageBuilder, commitShort, baselineShort, localBuildPackage);
                testLogger.info("Using Docker image: " + dockerImage);
                try {
                    Object cacheResult = imageBuilder.getClass()
                        .getMethod("wasLastOperationCacheHit")
                        .invoke(imageBuilder);
                    if (cacheResult instanceof Boolean) {
                        dockerImageCached = (Boolean) cacheResult;
                    }
                } catch (NoSuchMethodException ignore) {
                    testLogger.fine("DockerImageBuilder.wasLastOperationCacheHit not available");
                } catch (Exception cacheEx) {
                    testLogger.fine("Failed to retrieve Docker image cache hint: " + cacheEx.getMessage());
                }
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
        
        // Ensure shell testcases repository is on the requested branch (once per request)
        try {
            String requestId = RequestContext.getRequestId();
            shellTcSync.syncOncePerRequest(testLogger, requestId);
        } catch (Exception e) {
            testLogger.warning("Failed to sync shell testcases repo: " + e.getMessage());
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
                    metricsBuilder, startNs, dockerImage, dockerImageCached, null);
            }
            if (!Files.isReadable(sourceTestDir)) {
                return finalizeResult(
                    TestResult.builder()
                        .testName(request.getTestName())
                        .status(TestStatus.ENVIRONMENT_ERROR)
                        .message("Permission denied reading test directory: " + sourceTestDir),
                    metricsBuilder, startNs, dockerImage, dockerImageCached, null);
            }
            relativeTestDir = computeRelativeTestDir(shellRepoRoot, sourceTestDir, testLogger);
            testLogger.info("Using test directory: " + sourceTestDir + " (relative: " + (relativeTestDir.isEmpty() ? "." : relativeTestDir) + ")");
        } else {
            testLogger.info("Custom-only script mode: using /workspace/custom_test_execution/... inside container");
        }

        // Create simplified test script (no CUBRID extraction needed!)
        String dockerScript = EnvScriptFactory.createDockerOptimizedScript(
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
        
        // Save script for debugging
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && !CancelledRequests.isCancelled(requestId) && config.isRequestGroupingEnabled()) {
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
            return finalizeResult(
                TestResult.builder()
                    .testName(request.getTestName())
                    .status(TestStatus.ENVIRONMENT_ERROR)
                    .message("GITHUB_TOKEN environment variable not configured"),
                metricsBuilder, startNs, dockerImage, dockerImageCached, null);
        }

        // Create secure environment file for sensitive variables (GITHUB_TOKEN)
        // This prevents the token from appearing in ps -ef output
        try (SecureDockerEnv secureEnv = new SecureDockerEnv(dockerWorkDir)) {
            secureEnv.add("GITHUB_TOKEN", githubToken);
            String envFilePath = secureEnv.getFilePath();

            // Run Docker container with optimized flags
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
        
        // Performance optimizations
        dockerCommand.add("--init");
        dockerCommand.add("--tmpfs");
        dockerCommand.add("/tmp:exec,size=2G");
        dockerCommand.add("--shm-size=2g");
        
        // Volume mounts
        dockerCommand.add("-v");
        dockerCommand.add(dockerWorkDir.toString() + ":/workspace");
        dockerCommand.add("-v");
        dockerCommand.add(shellRepoRoot.toString() + ":" + TESTCASE_MOUNT + ":rw");

        // Environment variables
        dockerCommand.add("-e");
        dockerCommand.add("CTP_HOME=" + ctpHomeInContainer);
        dockerCommand.add("-e");
        dockerCommand.add("init_path=" + ctpHomeInContainer + "/shell/init_path");
        
        // Pass host UID/GID for workspace ownership fix (allows cleanup without sudo)
        dockerCommand.add("-e");
        dockerCommand.add("HOST_UID=" + getHostUid());
        dockerCommand.add("-e");
        dockerCommand.add("HOST_GID=" + getHostGid());

        // Add secure environment file containing GITHUB_TOKEN
        dockerCommand.add("--env-file");
        dockerCommand.add(envFilePath);

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
        DockerStatsCollector statsCollector = (keepAlive || !config.isStatsEnabled())
            ? null
            : new DockerStatsCollector(containerName, testLogger, config.getDockerStatsIntervalMs());
        if (statsCollector != null) {
            statsCollector.start();
        }
        
        if (keepAlive) {
            try { Files.createFile(workDir.resolve("KEEP_WORKSPACE")); } catch (Exception ignore) {}
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
                metricsBuilder, startNs, dockerImage, dockerImageCached, summary);
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
            DockerStatsCollector.StatsSummary summary = stopCollector(statsCollector);
            return finalizeResult(
                TestResult.builder()
                    .testName(request.getTestName())
                    .status(TestStatus.EXECUTION_ERROR)
                    .message("Docker test timeout after " + timeoutMinutes + " minutes"),
                metricsBuilder, startNs, dockerImage, dockerImageCached, summary);
        }
        
        int exitCode = process.exitValue();
        outputGobbler.join(2000);
        String dockerOutput = outputGobbler.getOutput();
        testLogger.info("Docker test completed with exit code: " + exitCode);
        DockerStatsCollector.StatsSummary statsSummary = stopCollector(statsCollector);

        String requestIdForLogs = RequestContext.getRequestId();
        if (CancelledRequests.isCancelled(requestIdForLogs)) {
            return finalizeResult(
                TestResult.builder()
                    .testName(request.getTestName())
                    .status("cancelled")
                    .message("Request cancelled")
                    .commit(request.getCommit() != null ? request.getCommit() : "unknown")
                    .commitShort(request.getCommitShort())
                    .timestamp(System.currentTimeMillis()),
                metricsBuilder, startNs, dockerImage, dockerImageCached, statsSummary);
        }
        
        // Save output log
        Path dockerOptLogFilePath = null;
        Path requestTestsDir = null;
        String dockerOptLogFileName = null;
        String safeTestName = request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_");
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && !CancelledRequests.isCancelled(requestId) && config.isRequestGroupingEnabled()) {
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                requestTestsDir = Paths.get(testsDir);
                // Include attempt number in the log file name for uniqueness
                int attemptNumber = request.getAttemptNumber();
                dockerOptLogFileName = generateDockerOptLogFileName(request.getCommitShort(), request.getTestName(), attemptNumber);
                dockerOptLogFilePath = requestTestsDir.resolve(dockerOptLogFileName);
                Files.write(dockerOptLogFilePath, dockerOutput.getBytes("UTF-8"));
                testLogger.info("Saved optimized Docker test log to: " + dockerOptLogFilePath);
                try {
                    metricsBuilder.logSizeBytes(Files.size(dockerOptLogFilePath));
                } catch (Exception ignore) { }
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
        
        if (!Files.exists(namedResult)) {
            Path repoResult = shellRepoRoot;
            if (relativeTestDir != null && !relativeTestDir.isEmpty()) {
                repoResult = repoResult.resolve(relativeTestDir);
            }
            repoResult = repoResult.resolve(resultBase + ".result").normalize();
            if (Files.exists(repoResult)) {
                namedResult = repoResult;
            }
        }

        if (Files.exists(namedResult)) {
            String resultContent = new String(Files.readAllBytes(namedResult));
            testLogger.info("Test result file content: " + resultContent);
            
            if (requestTestsDir != null) {
                try {
                    int attemptNumber = request.getAttemptNumber();
                    String persistedName = (attemptNumber == 1)
                        ? String.format("result_%s_%s.result", request.getCommitShort(), safeTestName)
                        : String.format("result_%s_%s.%d.result", request.getCommitShort(), safeTestName, attemptNumber);
                    Path persistedResult = requestTestsDir.resolve(persistedName);
                    Files.copy(namedResult, persistedResult, StandardCopyOption.REPLACE_EXISTING);
                    testLogger.info("Saved optimized result snapshot to: " + persistedResult);
                } catch (Exception copyError) {
                    testLogger.warning("Failed to persist optimized result file: " + copyError.getMessage());
                }
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
            } else if (isFailed) {
                resultBuilder.status(TestStatus.FAIL);
            } else {
                resultBuilder.status(TestStatus.EXECUTION_ERROR)
                    .message("Could not determine test result");
            }
            return finalizeResult(resultBuilder, metricsBuilder, startNs, dockerImage, dockerImageCached, statsSummary);
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

        return finalizeResult(resultBuilder, metricsBuilder, startNs, dockerImage, dockerImageCached, statsSummary);
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

    private TestResult finalizeResult(TestResult.Builder builder, TestExecutionMetrics.Builder metricsBuilder, long startNs,
                                      String dockerImage, boolean dockerImageCached,
                                      DockerStatsCollector.StatsSummary statsSummary) {
        long durationMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
        metricsBuilder.durationMs(durationMs);
        metricsBuilder.dockerImage(dockerImage);
        metricsBuilder.dockerImageCached(dockerImageCached);
        if (statsSummary != null && statsSummary.hasSamples()) {
            statsSummary.applyTo(metricsBuilder, durationMs);
        }
        TestExecutionMetrics metrics = metricsBuilder.build();
        builder.executionMetrics(metrics);
        builder.executionMode("docker_optimized");
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
