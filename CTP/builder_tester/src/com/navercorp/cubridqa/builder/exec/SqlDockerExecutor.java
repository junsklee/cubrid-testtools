package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.cache.BuildCache;
import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.ctp.CtpProvisioner;
import com.navercorp.cubridqa.builder.git.SqlTcSync;
import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.tester.CancelledRequests;
import com.navercorp.cubridqa.builder.tester.SafeIo;
import com.navercorp.cubridqa.builder.tester.TestRequest;
import com.navercorp.cubridqa.builder.tester.TestResult;
import com.navercorp.cubridqa.builder.tester.TestStatus;
import com.navercorp.cubridqa.builder.tester.stats.TestExecutionMetrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes SQL testcases (cubrid-testcases sql/**&#47;cases/*.sql) inside Docker.
 *
 * Reuses the per-commit test images produced by DockerImageBuilder (CUBRID
 * pre-installed at /opt/cubrid) and stages a pinned CTP payload (latest develop,
 * plain — no upstream PR layered on) provided by {@link CtpProvisioner} into the
 * container workspace. A generated sql_env_setup.sh provisions the test DB once
 * per container and a generated sql_run_case.sh executes each case via CTP's
 * ConsoleAgent (our own built-in single-case runner).
 *
 * Two modes (sql_exec_mode):
 *  - "pool": warm agent containers per (request, commit) amortize DB
 *    provisioning across many cases; failures are re-verified once in a fresh
 *    container to rule out cross-case contamination (sql_fresh_verify_on_fail).
 *  - "per_case": one container per case execution (maximal isolation).
 * Retry attempts (attemptNumber > 1) always run in fresh containers so flaky
 * classification is not polluted by warm-agent state.
 */
public class SqlDockerExecutor implements ExecutorStrategy {
    private static final String TESTCASE_MOUNT = SqlScriptFactory.TESTCASE_MOUNT;
    private static final AtomicLong AGENT_LAUNCH_SEQ = new AtomicLong();
    // Synthetic case basename for custom ad-hoc SQL (materialized under /workspace/custom_sql)
    private static final String CUSTOM_CASE_BASENAME = "custom_sql_case";

    private final Config config;
    private final BuildCache buildCache;
    private final SqlTcSync sqlTcSync;
    private final Object imageBuilder; // DockerImageBuilder via reflection (same pattern as OptimizedDockerExecutor)
    private final CtpProvisioner ctpProvisioner;
    private final SqlAgentPool agentPool;

    public SqlDockerExecutor(Config config, BuildCache buildCache, SqlTcSync sqlTcSync,
                             Object imageBuilder, CtpProvisioner ctpProvisioner, SqlAgentPool agentPool) {
        this.config = config;
        this.buildCache = buildCache;
        this.sqlTcSync = sqlTcSync;
        this.imageBuilder = imageBuilder;
        this.ctpProvisioner = ctpProvisioner;
        this.agentPool = agentPool;
    }

    /** Everything one case execution produced, host-side. */
    private static class CaseRun {
        SqlResultParser.SqlCaseOutcome outcome;
        Path attemptDir;        // host dir with status.line/console.log/expected/actual/diff
        String containerConsole; // docker run / docker exec stdout
        String executionEnv;    // "warm_agent" or "fresh_container"
        String failureMessage;  // set when the run could not produce an outcome
    }

    @Override
    public TestResult execute(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        long startNs = System.nanoTime();
        TestExecutionMetrics.Builder metricsBuilder = TestExecutionMetrics.builder()
            .buildPackageName(request.getBuildPackage())
            .metricsComplete(false);

        String testPath = request.getTestPath();
        boolean customMode = request.hasCustomSqlScript();
        testLogger.info("Running SQL testcase in Docker: " + (customMode ? "custom ad-hoc case" : testPath));
        if (request.isKeepAlive()) {
            testLogger.warning("keepAlive is not supported for SQL tests; ignoring");
        }
        if (!customMode && (testPath == null || !testPath.endsWith(".sql") || !testPath.contains("/cases/"))) {
            return finalizeResult(errorBuilder(request, TestStatus.EXECUTION_ERROR,
                "Invalid SQL testcase path: " + testPath), metricsBuilder, startNs, null, false);
        }

        String commitShort = request.getCommitShort() != null ? request.getCommitShort() : "unknown";
        String baselineShort = request.getBaselineShort() != null ? request.getBaselineShort() : "unknown";

        // 1) Per-commit Docker image (CUBRID pre-installed), same cache as the shell path
        boolean imageExistsAlready = hasImage(commitShort, baselineShort, testLogger);
        Path localBuildPackage = null;
        if (!imageExistsAlready) {
            try {
                localBuildPackage = buildCache.downloadIfNeeded(
                    request.getBuildPackage(), commitShort, baselineShort, testLogger);
                metricsBuilder.packageCached(buildCache.wasLastFetchFromCache());
            } catch (Exception e) {
                return finalizeResult(errorBuilder(request, TestStatus.ENVIRONMENT_ERROR,
                    "Failed to download build package: " + e.getMessage()), metricsBuilder, startNs, null, false);
            }
        } else {
            metricsBuilder.packageCached(true);
        }

        String dockerImage;
        boolean dockerImageCached = false;
        try {
            if (imageBuilder == null) {
                throw new IllegalStateException("Docker image builder not available");
            }
            dockerImage = (String) imageBuilder.getClass()
                .getMethod("getOrBuildImage", String.class, String.class, Path.class)
                .invoke(imageBuilder, commitShort, baselineShort, localBuildPackage);
            try {
                Object cacheResult = imageBuilder.getClass().getMethod("wasLastOperationCacheHit").invoke(imageBuilder);
                if (cacheResult instanceof Boolean) {
                    dockerImageCached = (Boolean) cacheResult;
                }
            } catch (Exception ignore) {
            }
            testLogger.info("Using Docker image: " + dockerImage);
        } catch (Exception e) {
            Throwable root = e instanceof java.lang.reflect.InvocationTargetException
                ? ((java.lang.reflect.InvocationTargetException) e).getTargetException() : e;
            return finalizeResult(errorBuilder(request, TestStatus.ENVIRONMENT_ERROR,
                "Failed to build SQL test image: " + (root != null ? root.getMessage() : e.toString())),
                metricsBuilder, startNs, null, false);
        }

        // 2) Testcases source: a pinned worktree for repo cases, or an empty
        //    placeholder for custom ad-hoc cases (the case is materialized under
        //    /workspace by runPerCaseContainer).
        Path repoRoot;
        Path caseFile;
        if (customMode) {
            try {
                repoRoot = Files.createDirectories(workDir.resolve("empty_testcases"));
            } catch (Exception e) {
                return finalizeResult(errorBuilder(request, TestStatus.ENVIRONMENT_ERROR,
                    "Failed to prepare custom SQL workspace: " + e.getMessage()),
                    metricsBuilder, startNs, dockerImage, dockerImageCached);
            }
            caseFile = null;
        } else {
            try {
                repoRoot = sqlTcSync.prepareRequestWorkspace(
                    testLogger, request.getRequestId(), request.getSqlTcBranch(), request.getSqlTcCommit());
            } catch (Exception e) {
                return finalizeResult(errorBuilder(request, TestStatus.ENVIRONMENT_ERROR,
                    "Failed to prepare SQL testcase workspace: " + e.getMessage()),
                    metricsBuilder, startNs, dockerImage, dockerImageCached);
            }
            caseFile = repoRoot.resolve(testPath).normalize();
            if (!caseFile.startsWith(repoRoot) || !Files.isRegularFile(caseFile)) {
                return finalizeResult(errorBuilder(request, TestStatus.EXECUTION_ERROR,
                    "SQL testcase not found in repository: " + testPath),
                    metricsBuilder, startNs, dockerImage, dockerImageCached);
            }
        }

        // 3) CTP payload (latest develop + configured PRs, pinned by builder-resolved SHAs)
        CtpProvisioner.CtpPayload ctpPayload;
        try {
            ctpPayload = ctpProvisioner.preparePayload(request.getCtpSqlBaseSha(), testLogger);
            testLogger.info("Using CTP payload " + ctpPayload.fingerprint);
        } catch (Exception e) {
            return finalizeResult(errorBuilder(request, TestStatus.ENVIRONMENT_ERROR,
                "Failed to prepare CTP payload: " + e.getMessage()),
                metricsBuilder, startNs, dockerImage, dockerImageCached);
        }

        int attemptNumber = Math.max(1, request.getAttemptNumber());
        // Custom ad-hoc cases always run in a fresh per-case container (no shared warm agents).
        boolean usePool = !customMode && "pool".equals(config.getSqlExecMode())
            && attemptNumber == 1 && agentPool != null;

        CaseRun warmRun = null;
        CaseRun finalRun;
        if (usePool) {
            warmRun = runOnAgentPool(request, workDir, dockerImage, repoRoot, ctpPayload, attemptNumber, testLogger);
            if (warmRun == null) {
                testLogger.info("SQL agent pool saturated/unavailable; falling back to per-case container");
                finalRun = runPerCaseContainer(request, workDir, dockerImage, repoRoot, ctpPayload,
                    attemptNumber, false, testLogger);
            } else if (needsFreshVerify(warmRun) && config.isSqlFreshVerifyOnFail()
                    && !CancelledRequests.isCancelled(request.getRequestId())) {
                testLogger.info("Warm-agent run was not a pass (" + describe(warmRun)
                    + "); re-verifying in a fresh container");
                finalRun = runPerCaseContainer(request, workDir, dockerImage, repoRoot, ctpPayload,
                    attemptNumber, true, testLogger);
            } else {
                finalRun = warmRun;
                warmRun = null;
            }
        } else {
            finalRun = runPerCaseContainer(request, workDir, dockerImage, repoRoot, ctpPayload,
                attemptNumber, false, testLogger);
        }

        if (CancelledRequests.isCancelled(request.getRequestId())) {
            return finalizeResult(TestResult.builder()
                .testName(request.getTestName())
                .status("cancelled")
                .message("Request cancelled")
                .commit(request.getCommit() != null ? request.getCommit() : "unknown")
                .commitShort(request.getCommitShort())
                .timestamp(System.currentTimeMillis()), metricsBuilder, startNs, dockerImage, dockerImageCached);
        }

        return buildResult(request, caseFile, finalRun, warmRun, attemptNumber,
            metricsBuilder, startNs, dockerImage, dockerImageCached, testLogger);
    }

    // ------------------------------------------------------------------
    // Pool path
    // ------------------------------------------------------------------

    private CaseRun runOnAgentPool(TestRequest request, Path workDir, String dockerImage, Path repoRoot,
                                   CtpProvisioner.CtpPayload ctpPayload, int attemptNumber, Logger testLogger) {
        String poolKey = SqlAgentPool.poolKey(request.getRequestId(), request.getCommitShort(), request.getBuildType());
        long maxWaitMs = (config.getSqlCaseTimeoutSec() + 120L) * 1000L;
        SqlAgentPool.Agent agent = null;
        try {
            agent = agentPool.tryAcquire(poolKey, maxWaitMs,
                (key, seq, log) -> launchAgent(request, dockerImage, repoRoot, ctpPayload, seq, log), testLogger);
        } catch (Exception e) {
            testLogger.warning("Failed to acquire SQL agent: " + e.getMessage());
            return null;
        }
        if (agent == null) {
            return null;
        }

        boolean taint = false;
        try {
            String slot = request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_") + "_" + System.nanoTime();
            CaseRun run = new CaseRun();
            run.executionEnv = "warm_agent";

            List<String> cmd = new ArrayList<>();
            cmd.add("docker");
            cmd.add("exec");
            cmd.add(agent.containerName);
            cmd.add("bash");
            cmd.add("/workspace/sql_run_case.sh");
            cmd.add(request.getTestPath());
            cmd.add(String.valueOf(attemptNumber));
            cmd.add(slot);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            ProcessIO.StreamReader reader = new ProcessIO.StreamReader(process.getInputStream(), "SQLEXEC");
            reader.start();
            long execTimeoutSec = config.getSqlCaseTimeoutSec() + 60L;
            boolean completed = process.waitFor(execTimeoutSec, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                taint = true; // exec wedged: replace the agent
                run.failureMessage = "docker exec timed out after " + execTimeoutSec + "s (agent replaced)";
                run.outcome = null;
                return run;
            }
            reader.join(2000);
            run.containerConsole = reader.getOutput();

            Path attemptDir = agent.workDir.resolve("results").resolve(slot).resolve("attempt_" + attemptNumber);
            run.attemptDir = attemptDir;
            run.outcome = SqlResultParser.parseStatusFile(attemptDir);
            if (run.outcome == null) {
                run.outcome = SqlResultParser.parseConsole(run.containerConsole);
            }
            if (run.outcome == null) {
                taint = true;
                run.failureMessage = "SQL agent produced no case outcome (agent replaced)";
            } else if (run.outcome.core) {
                taint = true; // core file produced: DB state suspect
            }
            return run;
        } catch (Exception e) {
            taint = true;
            testLogger.warning("SQL agent case execution failed: " + e.getMessage());
            CaseRun run = new CaseRun();
            run.executionEnv = "warm_agent";
            run.failureMessage = "SQL agent case execution failed: " + e.getMessage();
            return run;
        } finally {
            agentPool.release(agent, taint, testLogger);
        }
    }

    private SqlAgentPool.Agent launchAgent(TestRequest request, String dockerImage, Path repoRoot,
                                           CtpProvisioner.CtpPayload ctpPayload, int seq, Logger testLogger) throws Exception {
        String safeReq = (request.getRequestId() == null ? "adhoc" : request.getRequestId())
            .replaceAll("[^a-zA-Z0-9_.-]", "_");
        String commitShort = request.getCommitShort() != null ? request.getCommitShort() : "unknown";
        String buildType = request.getBuildType() != null ? request.getBuildType() : "release";

        Path agentWorkDir = Paths.get(config.getWorkDir(), "sql_agents", safeReq,
            commitShort + "_" + buildType, "agent_" + seq);
        SafeIo.deleteDirectory(agentWorkDir.toFile());
        Files.createDirectories(agentWorkDir);
        stageContainerWorkspace(agentWorkDir, ctpPayload, request, testLogger);
        Files.write(agentWorkDir.resolve("sql_agent_entry.sh"),
            SqlScriptFactory.createAgentEntryScript().getBytes("UTF-8"));

        // Name must contain the safeRequestId so /cancel-request container sweeps catch it
        String containerName = "tester_sqlagent_" + buildType + "_" + safeReq + "_" + commitShort
            + "_" + seq + "_" + AGENT_LAUNCH_SEQ.incrementAndGet();

        List<String> cmd = baseDockerRunCommand(containerName, agentWorkDir, repoRoot, dockerImage);
        cmd.add("-lc");
        cmd.add("bash /workspace/sql_agent_entry.sh");
        // Detach flag must come before the image; rebuild with -d inserted
        int runIdx = cmd.indexOf("run") + 1;
        cmd.add(runIdx, "-d");

        testLogger.info("Launching SQL agent container: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ProcessIO.StreamReader reader = new ProcessIO.StreamReader(process.getInputStream(), "SQLAGENT");
        reader.start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            DockerCtl.safeKillAndRemove(containerName, testLogger);
            throw new IllegalStateException("docker run for SQL agent timed out");
        }
        reader.join(2000);
        if (process.exitValue() != 0) {
            DockerCtl.safeKillAndRemove(containerName, testLogger);
            throw new IllegalStateException("docker run for SQL agent failed: " + reader.getOutput());
        }

        // Wait for provisioning (READY marker written by sql_env_setup.sh)
        long setupTimeoutMs = TimeUnit.MINUTES.toMillis(Math.max(5, config.getTestReadTimeoutMinutes()));
        long deadline = System.currentTimeMillis() + setupTimeoutMs;
        Path ready = agentWorkDir.resolve(SqlScriptFactory.READY_MARKER);
        Path failed = agentWorkDir.resolve("SETUP_FAILED");
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(ready)) {
                testLogger.info("SQL agent ready: " + containerName);
                return new SqlAgentPool.Agent(
                    SqlAgentPool.poolKey(request.getRequestId(), commitShort, buildType),
                    containerName, agentWorkDir);
            }
            if (Files.exists(failed)) {
                String log = readContainerLogs(containerName);
                DockerCtl.safeKillAndRemove(containerName, testLogger);
                throw new IllegalStateException("SQL agent provisioning failed: " + tail(log, 2000));
            }
            Thread.sleep(1000);
        }
        String log = readContainerLogs(containerName);
        DockerCtl.safeKillAndRemove(containerName, testLogger);
        throw new IllegalStateException("SQL agent provisioning timed out: " + tail(log, 2000));
    }

    // ------------------------------------------------------------------
    // Per-case container path
    // ------------------------------------------------------------------

    private CaseRun runPerCaseContainer(TestRequest request, Path workDir, String dockerImage, Path repoRoot,
                                        CtpProvisioner.CtpPayload ctpPayload, int attemptNumber,
                                        boolean freshVerify, Logger testLogger) {
        CaseRun run = new CaseRun();
        run.executionEnv = "fresh_container";
        try {
            Path dockerWorkDir = Files.createTempDirectory(workDir, "sql_docker_");
            stageContainerWorkspace(dockerWorkDir, ctpPayload, request, testLogger);

            // Determine the case argument: an absolute path to a materialized ad-hoc
            // case (custom SQL), or the repo-relative path (normal case).
            String caseArg;
            if (request.hasCustomSqlScript()) {
                Path caseDir = dockerWorkDir.resolve("custom_sql").resolve("cases");
                Path answerDir = dockerWorkDir.resolve("custom_sql").resolve("answers");
                Files.createDirectories(caseDir);
                Files.createDirectories(answerDir);
                Files.write(caseDir.resolve(CUSTOM_CASE_BASENAME + ".sql"),
                    request.getCustomSqlScript().getBytes("UTF-8"));
                String answer = request.getCustomSqlAnswer() == null ? "" : request.getCustomSqlAnswer();
                Files.write(answerDir.resolve(CUSTOM_CASE_BASENAME + ".answer"), answer.getBytes("UTF-8"));
                caseArg = "/workspace/custom_sql/cases/" + CUSTOM_CASE_BASENAME + ".sql";
            } else {
                caseArg = request.getTestPath();
            }
            Files.write(dockerWorkDir.resolve("run_sql_test.sh"),
                SqlScriptFactory.createPerCaseScript(caseArg, attemptNumber).getBytes("UTF-8"));

            String containerName = request.getContainerName();
            if (containerName == null || containerName.trim().isEmpty()) {
                containerName = "tester_sql_" + (request.getBuildType() != null ? request.getBuildType() : "release")
                    + "_" + request.getCommitShort() + "_"
                    + request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_")
                    + "_" + System.currentTimeMillis();
            }
            if (freshVerify) {
                containerName = containerName + "_v" + attemptNumber;
            }

            List<String> cmd = baseDockerRunCommand(containerName, dockerWorkDir, repoRoot, dockerImage);
            cmd.add("-lc");
            cmd.add("bash /workspace/run_sql_test.sh");
            int runIdx = cmd.indexOf("run") + 1;
            cmd.add(runIdx, "--rm");

            testLogger.info("Executing SQL Docker command: " + String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            ProcessIO.StreamReader reader = new ProcessIO.StreamReader(process.getInputStream(), "SQLDOCKER");
            reader.start();
            int timeoutMinutes = Math.max(1, config.getTestReadTimeoutMinutes());
            boolean completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                DockerCtl.safeKillAndRemove(containerName, testLogger);
                run.failureMessage = "SQL Docker test timeout after " + timeoutMinutes + " minutes";
                return run;
            }
            reader.join(2000);
            run.containerConsole = reader.getOutput();

            Path attemptDir = dockerWorkDir.resolve("results").resolve("case").resolve("attempt_" + attemptNumber);
            run.attemptDir = attemptDir;
            run.outcome = SqlResultParser.parseStatusFile(attemptDir);
            if (run.outcome == null) {
                run.outcome = SqlResultParser.parseConsole(run.containerConsole);
            }
            if (run.outcome == null) {
                run.failureMessage = "SQL container produced no case outcome (setup failure?): "
                    + tail(run.containerConsole, 1500);
            }
            return run;
        } catch (Exception e) {
            testLogger.log(Level.WARNING, "SQL per-case container execution failed", e);
            run.failureMessage = "SQL per-case container execution failed: " + e.getMessage();
            return run;
        }
    }

    // ------------------------------------------------------------------
    // Shared plumbing
    // ------------------------------------------------------------------

    /** Stages CTP payload + generated scripts into a host dir that will be mounted at /workspace. */
    private void stageContainerWorkspace(Path hostWorkspaceDir, CtpProvisioner.CtpPayload ctpPayload,
                                         TestRequest request, Logger testLogger) throws Exception {
        Path ctpTarget = hostWorkspaceDir.resolve("CTP");
        SafeIo.deleteDirectory(ctpTarget.toFile());
        SafeIo.copyTestCaseDirectory(ctpPayload.ctpDir, ctpTarget);

        SqlScriptFactory.SqlEnvSettings envSettings = new SqlScriptFactory.SqlEnvSettings(
            config.getSqlDbName(),
            config.getSqlDbCharsetOverride(),
            config.getSqlCreatedbOptsOverride(),
            config.isSqlLoadStoredProcedures(),
            config.getSqlNeedMakeLocaleOverride(),
            config.getSqlHaModeOverride(),
            request.getExpectedBuildVersion());
        Files.write(hostWorkspaceDir.resolve("sql_env_setup.sh"),
            SqlScriptFactory.createEnvSetupScript(envSettings).getBytes("UTF-8"));
        Files.write(hostWorkspaceDir.resolve("sql_run_case.sh"),
            SqlScriptFactory.createCaseRunScript(config.getSqlCaseTimeoutSec()).getBytes("UTF-8"));
    }

    /** Common `docker run` prefix shared by per-case containers and pool agents (no -d/--rm, no command). */
    private List<String> baseDockerRunCommand(String containerName, Path hostWorkspaceDir, Path repoRoot, String dockerImage) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");

        // Config-driven resource limits (same keys as the shell executors)
        if (config.isDockerEnforceCpuLimits() && config.getDockerCpuLimitMillicores() != null) {
            double cpus = Math.max(0.1, config.getDockerCpuLimitMillicores() / 1000.0);
            cmd.add("--cpus=" + String.format(java.util.Locale.ROOT, "%.3f", cpus));
        }
        if (config.isDockerEnforceMemoryLimits() && config.getDockerMemoryLimitMb() != null) {
            long memBytes = Math.max(256L * 1024 * 1024, config.getDockerMemoryLimitMb() * 1024L * 1024);
            cmd.add("--memory=" + memBytes);
            cmd.add("--memory-swap=" + memBytes);
        }

        cmd.add("--log-driver");
        cmd.add("json-file");
        cmd.add("--log-opt");
        cmd.add("max-size=50m");
        cmd.add("--log-opt");
        cmd.add("max-file=3");
        cmd.add("--name");
        cmd.add(containerName);
        // The setup script bind-mounts /opt/cubrid at $HOME/CUBRID (mount --bind needs SYS_ADMIN)
        cmd.add("--cap-add");
        cmd.add("SYS_ADMIN");
        cmd.add("--security-opt");
        cmd.add("apparmor=unconfined");
        cmd.add("--init");
        cmd.add("--tmpfs");
        cmd.add("/tmp:exec,size=2G");
        cmd.add("--shm-size=2g");
        cmd.add("-v");
        cmd.add(hostWorkspaceDir.toString() + ":/workspace");
        cmd.add("-v");
        cmd.add(repoRoot.toString() + ":" + TESTCASE_MOUNT + ":ro");
        cmd.add("-e");
        cmd.add("CTP_HOME=" + SqlScriptFactory.CTP_HOME_IN_CONTAINER);
        cmd.add("-e");
        cmd.add("HOST_UID=" + hostId("-u"));
        cmd.add("-e");
        cmd.add("HOST_GID=" + hostId("-g"));
        cmd.add("-w");
        cmd.add("/workspace");
        cmd.add("--entrypoint");
        cmd.add("bash");
        cmd.add(dockerImage);
        return cmd;
    }

    private TestResult buildResult(TestRequest request, Path caseFile, CaseRun finalRun, CaseRun warmRun,
                                   int attemptNumber, TestExecutionMetrics.Builder metricsBuilder, long startNs,
                                   String dockerImage, boolean dockerImageCached, Logger testLogger) {
        TestResult.Builder resultBuilder = TestResult.builder()
            .testName(request.getTestName())
            .commit(request.getCommit() != null ? request.getCommit() : "unknown")
            .commitShort(request.getCommitShort())
            .timestamp(System.currentTimeMillis());

        String safeTestName = request.getTestName().replaceAll("[^a-zA-Z0-9_.-]", "_");
        String commitShort = request.getCommitShort() != null ? request.getCommitShort() : "unknown";
        String suffix = attemptNumber == 1 ? "" : "." + attemptNumber;

        Path testsDir = resolveRequestTestsDir(request, testLogger);
        if (testsDir != null) {
            // Primary attempt log: container/exec console + ConsoleAgent console output
            try {
                StringBuilder combined = new StringBuilder();
                if (finalRun.containerConsole != null) {
                    combined.append(finalRun.containerConsole);
                }
                Path caseConsole = finalRun.attemptDir != null ? finalRun.attemptDir.resolve("console.log") : null;
                if (caseConsole != null && Files.isReadable(caseConsole)) {
                    combined.append("\n----- ConsoleAgent console -----\n");
                    combined.append(new String(Files.readAllBytes(caseConsole), "UTF-8"));
                }
                Path logFile = testsDir.resolve("sql_" + commitShort + "_" + safeTestName + suffix + ".log");
                Files.write(logFile, combined.toString().getBytes("UTF-8"));
                resultBuilder.addAttemptLogFile(logFile);
                try {
                    metricsBuilder.logSizeBytes(Files.size(logFile));
                } catch (Exception ignore) {
                }
            } catch (Exception e) {
                testLogger.warning("Failed to persist SQL attempt log: " + e.getMessage());
            }

            persistArtifact(resultBuilder, testsDir, finalRun.attemptDir, "answer.diff",
                "sql_diff_" + commitShort + "_" + safeTestName + suffix + ".diff",
                "answer_diff", attemptNumber, finalRun.executionEnv, testLogger);
            persistArtifact(resultBuilder, testsDir, finalRun.attemptDir, "actual.result",
                "sql_actual_" + commitShort + "_" + safeTestName + suffix + ".result",
                "actual_result", attemptNumber, finalRun.executionEnv, testLogger);
            persistArtifact(resultBuilder, testsDir, finalRun.attemptDir, "expected.answer",
                "sql_expected_" + commitShort + "_" + safeTestName + suffix + ".answer",
                "expected_answer", attemptNumber, finalRun.executionEnv, testLogger);
            persistArtifact(resultBuilder, testsDir, finalRun.attemptDir, "core.list",
                "sql_core_" + commitShort + "_" + safeTestName + suffix + ".txt",
                "core_list", attemptNumber, finalRun.executionEnv, testLogger);

            // Case source (once, on the first attempt). Custom ad-hoc cases have no
            // repo file, so write the source from the request; normal cases copy the file.
            if (attemptNumber == 1) {
                try {
                    Path caseCopy = testsDir.resolve("sql_case_" + commitShort + "_" + safeTestName + ".sql");
                    if (request.hasCustomSqlScript()) {
                        Files.write(caseCopy, request.getCustomSqlScript().getBytes("UTF-8"));
                    } else if (caseFile != null && !Files.exists(caseCopy)) {
                        Files.copy(caseFile, caseCopy, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (Files.exists(caseCopy)) {
                        resultBuilder.addArtifactFile(caseCopy, "case_source", attemptNumber, finalRun.executionEnv);
                    }
                } catch (Exception e) {
                    testLogger.warning("Failed to persist SQL case source: " + e.getMessage());
                }
            }

            // Warm-agent run that was overruled by fresh-verify: keep its console for the report
            if (warmRun != null) {
                try {
                    StringBuilder warmLog = new StringBuilder();
                    warmLog.append("Warm-agent run outcome: ").append(describe(warmRun)).append("\n\n");
                    if (warmRun.containerConsole != null) {
                        warmLog.append(warmRun.containerConsole);
                    }
                    Path warmConsole = warmRun.attemptDir != null ? warmRun.attemptDir.resolve("console.log") : null;
                    if (warmConsole != null && Files.isReadable(warmConsole)) {
                        warmLog.append("\n----- ConsoleAgent console (warm agent) -----\n");
                        warmLog.append(new String(Files.readAllBytes(warmConsole), "UTF-8"));
                    }
                    Path warmFile = testsDir.resolve("sql_warm_" + commitShort + "_" + safeTestName + suffix + ".log");
                    Files.write(warmFile, warmLog.toString().getBytes("UTF-8"));
                    resultBuilder.addArtifactFile(warmFile, "warm_console", attemptNumber, "warm_agent");
                } catch (Exception e) {
                    testLogger.warning("Failed to persist warm-agent SQL log: " + e.getMessage());
                }
            }
        }

        SqlResultParser.SqlCaseOutcome outcome = finalRun.outcome;
        if (outcome == null) {
            resultBuilder.status(finalRun.failureMessage != null && finalRun.failureMessage.contains("timeout")
                    ? TestStatus.EXECUTION_ERROR : TestStatus.ENVIRONMENT_ERROR)
                .message(finalRun.failureMessage != null ? finalRun.failureMessage : "SQL case produced no outcome");
        } else if (outcome.isPass()) {
            resultBuilder.status(TestStatus.PASS);
            if (warmRun != null && warmRun.outcome != null && warmRun.outcome.isFail()) {
                resultBuilder.message("Warm-agent failure not reproduced in fresh container (suspected cross-case contamination)");
            }
        } else if (outcome.isFail()) {
            String message = "Answer mismatch (see answer diff)";
            if (outcome.core) {
                message += "; core file produced";
            }
            resultBuilder.status(TestStatus.FAIL).message(message);
        } else if (SqlResultParser.STATUS_NOTRUN.equals(outcome.status)) {
            String caseName = caseFile != null ? caseFile.getFileName().toString() : (safeTestName + ".sql");
            resultBuilder.status(TestStatus.EXECUTION_ERROR)
                .message("No answer file for case (expected .../answers/"
                    + caseName.replace(".sql", ".answer") + ")");
        } else if (SqlResultParser.STATUS_TIMEOUT.equals(outcome.status)) {
            resultBuilder.status(TestStatus.EXECUTION_ERROR)
                .message("Case timed out after " + config.getSqlCaseTimeoutSec() + "s");
        } else {
            resultBuilder.status(TestStatus.EXECUTION_ERROR)
                .message("SQL case execution error" + (outcome.reason != null ? ": " + outcome.reason : ""));
        }

        return finalizeResult(resultBuilder, metricsBuilder, startNs, dockerImage, dockerImageCached,
            "pool".equals(config.getSqlExecMode()) ? "docker_sql_pool" : "docker_sql");
    }

    private void persistArtifact(TestResult.Builder resultBuilder, Path testsDir, Path attemptDir,
                                 String sourceName, String targetName, String artifactType,
                                 int attempt, String executionEnv, Logger testLogger) {
        if (attemptDir == null) {
            return;
        }
        Path source = attemptDir.resolve(sourceName);
        if (!Files.isReadable(source)) {
            return;
        }
        try {
            Path target = testsDir.resolve(targetName);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            resultBuilder.addArtifactFile(target, artifactType, attempt, executionEnv);
        } catch (Exception e) {
            testLogger.warning("Failed to persist SQL artifact " + sourceName + ": " + e.getMessage());
        }
    }

    private Path resolveRequestTestsDir(TestRequest request, Logger testLogger) {
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId == null || requestId.trim().isEmpty()) {
                requestId = request.getRequestId();
            }
            if (requestId != null && config.isRequestGroupingEnabled()) {
                return Paths.get(RequestLogManager.getInstance().createRequestSubdir(requestId, "tests"));
            }
        } catch (Exception e) {
            testLogger.warning("Failed to resolve request tests dir: " + e.getMessage());
        }
        return null;
    }

    private boolean needsFreshVerify(CaseRun warmRun) {
        if (warmRun.outcome == null) {
            return true;
        }
        return !warmRun.outcome.isPass() && !SqlResultParser.STATUS_NOTRUN.equals(warmRun.outcome.status);
    }

    private String describe(CaseRun run) {
        if (run.outcome != null) {
            return "status=" + run.outcome.status + (run.outcome.core ? ", core" : "");
        }
        return run.failureMessage != null ? run.failureMessage : "no outcome";
    }

    private boolean hasImage(String commitShort, String baselineShort, Logger testLogger) {
        if (imageBuilder == null) {
            return false;
        }
        try {
            Object result = imageBuilder.getClass()
                .getMethod("hasImage", String.class, String.class)
                .invoke(imageBuilder, commitShort, baselineShort);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            return false;
        }
    }

    private TestResult.Builder errorBuilder(TestRequest request, TestStatus status, String message) {
        return TestResult.builder()
            .testName(request.getTestName())
            .commit(request.getCommit() != null ? request.getCommit() : "unknown")
            .commitShort(request.getCommitShort())
            .timestamp(System.currentTimeMillis())
            .status(status)
            .message(message);
    }

    private TestResult finalizeResult(TestResult.Builder builder, TestExecutionMetrics.Builder metricsBuilder,
                                      long startNs, String dockerImage, boolean dockerImageCached) {
        return finalizeResult(builder, metricsBuilder, startNs, dockerImage, dockerImageCached, "docker_sql");
    }

    private TestResult finalizeResult(TestResult.Builder builder, TestExecutionMetrics.Builder metricsBuilder,
                                      long startNs, String dockerImage, boolean dockerImageCached, String executionMode) {
        long durationMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
        metricsBuilder.durationMs(durationMs);
        metricsBuilder.dockerImage(dockerImage);
        metricsBuilder.dockerImageCached(dockerImageCached);
        builder.executionMetrics(metricsBuilder.build());
        builder.executionMode(executionMode);
        return builder.build();
    }

    private String readContainerLogs(String containerName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "logs", "--tail", "100", containerName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ProcessIO.StreamReader reader = new ProcessIO.StreamReader(p.getInputStream(), "SQLLOGS");
            reader.start();
            p.waitFor(30, TimeUnit.SECONDS);
            reader.join(2000);
            return reader.getOutput();
        } catch (Exception e) {
            return "";
        }
    }

    private String tail(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
    }

    private static String hostId(String flag) {
        try {
            Process p = new ProcessBuilder("id", flag).start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String id = reader.readLine();
                p.waitFor();
                return id != null ? id.trim() : "1000";
            }
        } catch (Exception e) {
            return "1000";
        }
    }
}
