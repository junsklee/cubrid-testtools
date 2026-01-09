package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.tester.TestRequest;
import com.navercorp.cubridqa.builder.tester.TestResult;
import com.navercorp.cubridqa.builder.tester.TestStatus;
import com.navercorp.cubridqa.builder.cache.BuildCache;
import com.navercorp.cubridqa.builder.git.ShellTcSync;
import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.tester.stats.TestExecutionMetrics;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DirectExecutor implements ExecutorStrategy {
    private final Config config;
    private final BuildCache buildCache;
    private final ShellTcSync shellTcSync;
    private final CubridInstaller cubridInstaller;

    public DirectExecutor(Config config, BuildCache buildCache, ShellTcSync shellTcSync, CubridInstaller cubridInstaller) {
        this.config = config;
        this.buildCache = buildCache;
        this.shellTcSync = shellTcSync;
        this.cubridInstaller = cubridInstaller;
    }

    @Override
    public TestResult execute(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        testLogger.info("Direct test execution");
        testLogger.info("  Build package: " + request.getBuildPackage());
        testLogger.info("  Test: " + request.getTestName());
        
        long startNs = System.nanoTime();
        TestExecutionMetrics.Builder metricsBuilder = TestExecutionMetrics.builder()
            .buildPackageName(request.getBuildPackage())
            .metricsComplete(false);
        
        // Download build package if it's a URL
        Path localBuildPackage;
        try {
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
            return finalizeResult(TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.ENVIRONMENT_ERROR)
                .message("Failed to download build package: " + e.getMessage())
                , metricsBuilder, startNs);
        }
        
        if (localBuildPackage == null || !Files.exists(localBuildPackage)) {
            return finalizeResult(TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.ENVIRONMENT_ERROR)
                .message("Downloaded build package missing: " + String.valueOf(localBuildPackage)),
                metricsBuilder, startNs);
        }

        // Ensure shell testcases repository is on the requested branch (once per request)
        try {
            String requestId = RequestContext.getRequestId();
            shellTcSync.syncOncePerRequest(testLogger, requestId);
        } catch (Exception e) {
            testLogger.warning("Failed to sync shell testcases repo: " + e.getMessage());
        }
        
        // Install CUBRID
        Path installDir = null;
        try {
            installDir = cubridInstaller.install(localBuildPackage.toString(), workDir, testLogger);
        } catch (Exception e) {
            testLogger.log(Level.SEVERE, "Failed to install CUBRID", e);
            return finalizeResult(TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.BUILD_ERROR)
                .message("Failed to install CUBRID: " + e.getMessage()),
                metricsBuilder, startNs);
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
        String ctpHome = CtpEnvResolver.findCTPHome();
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
        Path sourceTestDir;
        try {
            Path shellRepoRoot = Paths.get(config.getShellTcDir()).toAbsolutePath().normalize();
            sourceTestDir = TestDirectoryResolver.resolve(shellRepoRoot, request, testLogger);
        } catch (IllegalArgumentException e) {
            return finalizeResult(TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.ENVIRONMENT_ERROR)
                .message(e.getMessage()),
                metricsBuilder, startNs);
        }

        // Run the test
        String resultBaseFromScript = request.getTestScript().endsWith(".sh")
            ? request.getTestScript().substring(0, request.getTestScript().length() - 3)
            : (request.getTestScript().contains(".") ? request.getTestScript().substring(0, request.getTestScript().lastIndexOf('.')) : request.getTestScript());
        File namedResultFileObj = new File(sourceTestDir.toFile(), resultBaseFromScript + ".result");
        testLogger.info("Running test: " + request.getTestScript());
        if (namedResultFileObj.exists()) namedResultFileObj.delete();
        
        // Create wrapper script
        String wrapperScript = EnvScriptFactory.createDirectWrapperScript(
            sourceTestDir.toString(), request.getTestScript(), request.getTestName(), ctpHome,
            request.getCustomShellScript(), request.getCustomAttachments());
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
        
        ProcessIO.StreamReader outputGobbler = new ProcessIO.StreamReader(process.getInputStream(), "OUTPUT");
        ProcessIO.StreamReader errorGobbler = new ProcessIO.StreamReader(process.getErrorStream(), "ERROR");
        outputGobbler.start();
        errorGobbler.start();
        
        int timeoutMinutes = Math.max(1, config.getTestReadTimeoutMinutes());
        boolean completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            testLogger.severe("Test timeout");
            return finalizeResult(TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.EXECUTION_ERROR)
                .message("Test timeout after " + timeoutMinutes + " minutes"),
                metricsBuilder, startNs);
        }
        
        int exitCode = process.exitValue();
        outputGobbler.join(2000);
        errorGobbler.join(2000);
        
        String testOutput = outputGobbler.getOutput();
        String testError = errorGobbler.getOutput();
        
        // Combine output and error for log content
        String directLogContent = "=== STDOUT ===\n" + testOutput + "\n\n=== STDERR ===\n" + testError;
        String directLogFileName = null;
        Path directLogFilePath = null;
        
        // Save logs to file
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                String safeTestName = request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_");
                String commitShortForLog = request.getCommitShort() != null ? request.getCommitShort() : "unknown";
                int attemptNumber = request.getAttemptNumber();
                if (attemptNumber == 1) {
                    directLogFileName = String.format("direct_%s_%s.log", commitShortForLog, safeTestName);
                } else {
                    directLogFileName = String.format("direct_%s_%s.%d.log", commitShortForLog, safeTestName, attemptNumber);
                }
                directLogFilePath = Paths.get(testsDir, directLogFileName);
                Files.write(directLogFilePath, directLogContent.getBytes("UTF-8"));
                testLogger.info("Saved direct test log to: " + directLogFilePath.toString());
            }
        } catch (Exception ignore) {
            // Swallow logging persistence issues
        }
        
        // Check for execution errors
        if (exitCode != 0 && (testError.contains("command not found") || 
                              testError.contains("syntax error"))) {
            testLogger.severe("Test execution error: " + testError);
            TestResult.Builder resultBuilder = TestResult.builder()
                .testName(request.getTestName())
                .status(TestStatus.EXECUTION_ERROR)
                .message("Test script execution error")
                .exitCode(exitCode);
            
            if (directLogFilePath != null) {
                resultBuilder.addAttemptLogFile(directLogFilePath);
            }
            
            return finalizeResult(resultBuilder, metricsBuilder, startNs);
        }
        
        // Check named result first then nok.result
        if (namedResultFileObj.exists()) {
            String resultContent = new String(Files.readAllBytes(namedResultFileObj.toPath()));
            testLogger.info("Named Result (" + resultBaseFromScript + ".result): " + resultContent.trim());
            
            TestResult.Builder resultBuilder = TestResult.builder()
                .testName(request.getTestName());
                
            if (resultContent.contains("NOK") || resultContent.contains("FAIL")) {
                resultBuilder.status(TestStatus.FAIL);
            } else if (resultContent.contains("OK") || resultContent.contains("PASS")) {
                resultBuilder.status(TestStatus.PASS);
            } else {
                resultBuilder.status(TestStatus.EXECUTION_ERROR)
                           .message("Could not determine test result");
            }
            
            if (directLogFilePath != null) {
                resultBuilder.addAttemptLogFile(directLogFilePath);
            }
            
            return finalizeResult(resultBuilder, metricsBuilder, startNs);
        }
        
        TestResult.Builder resultBuilder = TestResult.builder()
            .testName(request.getTestName())
            .status(TestStatus.EXECUTION_ERROR)
            .message("No result file generated")
            .exitCode(exitCode);
            
        if (directLogFilePath != null) {
            resultBuilder.addAttemptLogFile(directLogFilePath);
        }
        
        return finalizeResult(resultBuilder, metricsBuilder, startNs);
    }
    
    private TestResult finalizeResult(TestResult.Builder builder, TestExecutionMetrics.Builder metricsBuilder, long startNs) {
        long durationMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
        metricsBuilder.durationMs(durationMs);
        TestExecutionMetrics metrics = metricsBuilder.build();
        builder.executionMetrics(metrics);
        builder.executionMode("direct");
        return builder.build();
    }
}
