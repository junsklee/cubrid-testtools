package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.cache.BuildCache;
import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.ctp.CtpProvisioner;
import com.navercorp.cubridqa.builder.docker.DockerImageBuilder;
import com.navercorp.cubridqa.builder.docker.DockerLabels;
import com.navercorp.cubridqa.builder.git.SqlTcSync;
import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.tester.CancelledRequests;
import com.navercorp.cubridqa.builder.tester.SafeIo;
import com.navercorp.cubridqa.builder.tester.TestRequest;
import com.navercorp.cubridqa.builder.tester.TestResult;
import com.navercorp.cubridqa.builder.tester.TestStatus;
import com.navercorp.cubridqa.builder.tester.stats.TestExecutionMetrics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs one shard of the full SQL suite in Docker the way cubridci's gha-ci runs its shards: in the
 * per-commit image (the test image with the build installed), the shard's tree at
 * /home/cubrid-testcases and the request's CTP at /home/cubrid-testtools, then the test image's own
 * `/entrypoint.sh test sql` (one `ctp.sh sql` run over the tree, CTP's conf/sql.conf) and
 * `/entrypoint.sh results sql`. The verdict of each case comes back as an artifact
 * (SqlBatch.VERDICTS_SUFFIX); the Builder re-runs the failed ones one by one.
 */
public class SqlBatchDockerExecutor implements ExecutorStrategy {
    /** Where the test image's entrypoint expects things ($WORKDIR=/home). */
    static final String TREE_IN_CONTAINER = "/home/cubrid-testcases";
    private static final String TESTTOOLS_IN_CONTAINER = "/home/cubrid-testtools";

    private final Config config;
    private final BuildCache buildCache;
    private final SqlTcSync sqlTcSync;
    private final Object imageBuilder;
    private final CtpProvisioner ctpProvisioner;

    public SqlBatchDockerExecutor(Config config, BuildCache buildCache, SqlTcSync sqlTcSync,
                                  Object imageBuilder, CtpProvisioner ctpProvisioner) {
        this.config = config;
        this.buildCache = buildCache;
        this.sqlTcSync = sqlTcSync;
        this.imageBuilder = imageBuilder;
        this.ctpProvisioner = ctpProvisioner;
    }

    /** The batch's wall clock: the request's sqlBatch.timeoutMinutes, 10 minutes to a day. */
    static int timeoutMinutes(TestRequest request) {
        return Math.max(10, Math.min(1440, request.getSqlBatchTimeoutMinutes()));
    }

    @Override
    public TestResult execute(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        long startNs = System.nanoTime();
        TestExecutionMetrics.Builder metrics = TestExecutionMetrics.builder()
            .buildPackageName(request.getBuildPackage())
            .metricsComplete(false);
        String commitShort = request.getCommitShort() != null ? request.getCommitShort() : "unknown";
        String baselineShort = request.getBaselineShort() != null ? request.getBaselineShort() : "unknown";
        testLogger.info("Running full-suite SQL shard " + request.getSqlBatchShard() + " in Docker: "
            + request.getSqlBatchDirs());

        // 1) The per-commit image, as for single SQL cases
        String imageVariant = DockerImageBuilder.imageVariant(baselineShort, request.getBuildType(), request.getBuildPackage());
        String dockerImage;
        try {
            Path localPackage = null;
            if (!hasImage(commitShort, imageVariant)) {
                localPackage = buildCache.downloadIfNeeded(request.getBuildPackage(), commitShort, baselineShort, testLogger);
            }
            if (imageBuilder == null) {
                throw new IllegalStateException("Docker image builder not available");
            }
            dockerImage = (String) imageBuilder.getClass()
                .getMethod("getOrBuildImage", String.class, String.class, Path.class)
                .invoke(imageBuilder, commitShort, imageVariant, localPackage);
        } catch (Exception e) {
            Throwable root = e instanceof java.lang.reflect.InvocationTargetException
                ? ((java.lang.reflect.InvocationTargetException) e).getTargetException() : e;
            return finish(error(request, TestStatus.ENVIRONMENT_ERROR,
                "Failed to prepare the SQL test image: " + (root != null ? root.getMessage() : e)), metrics, startNs, null);
        }

        // 2) The shard's tree from the request's pinned testcases, and a CTP of its own (CTP writes
        //    its conf and results into it)
        Path ws = Files.createTempDirectory(workDir, "sql_batch_");
        Path tree = ws.resolve("cubrid-testcases");
        Path testtools = ws.resolve("cubrid-testtools");
        Path out = Files.createDirectories(ws.resolve("out"));
        int staged;
        try {
            Path repoRoot = sqlTcSync.prepareRequestWorkspace(
                testLogger, request.getRequestId(), request.getSqlTcBranch(), request.getSqlTcCommit());
            staged = SqlBatch.stageShardTree(repoRoot, request.getSqlBatchDirs(), tree);
            CtpProvisioner.CtpPayload ctp = ctpProvisioner.preparePayload(request.getCtpSqlBaseSha(), testLogger);
            SafeIo.copyTestCaseDirectory(ctp.ctpDir, testtools.resolve("CTP"));
            Files.write(ws.resolve("run_batch.sh"), script().getBytes(StandardCharsets.UTF_8));
            testLogger.info("Shard tree: " + staged + " cases; CTP payload " + ctp.fingerprint);
        } catch (Exception e) {
            cleanup(ws, testLogger);
            return finish(error(request, TestStatus.ENVIRONMENT_ERROR,
                "Failed to stage the SQL shard: " + e.getMessage()), metrics, startNs, dockerImage);
        }

        // 3) One CTP run over the tree
        String containerName = ("tester_sqlbatch_" + commitShort + "_" + request.getSqlBatchShard() + "_"
            + System.currentTimeMillis()).replaceAll("[^a-zA-Z0-9_.-]", "_");
        List<String> cmd = dockerRun(containerName, request, ws, tree, testtools, out, dockerImage);
        testLogger.info("Executing SQL shard container: " + String.join(" ", cmd));
        long startedAt = System.currentTimeMillis();
        int timeout = timeoutMinutes(request);
        String console;
        boolean timedOut = false;
        boolean cancelled = false;
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            ProcessIO.StreamReader reader = new ProcessIO.StreamReader(process.getInputStream(), "SQLBATCH");
            reader.start();
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeout);
            boolean done = false;
            while (!done && System.nanoTime() < deadline) {
                done = process.waitFor(5, TimeUnit.SECONDS);
                if (!done && CancelledRequests.isCancelled(request.getRequestId())) {
                    cancelled = true;
                    break;
                }
            }
            if (!done) {
                timedOut = !cancelled;
                DockerCtl.safeKillAndRemove(containerName, testLogger);
                process.destroyForcibly();
            }
            reader.join(5000);
            console = reader.getOutput() + (timedOut ? "\n===== TIMEOUT after " + timeout + " minutes =====\n" : "");
        } catch (Exception e) {
            testLogger.log(Level.WARNING, "SQL shard container failed", e);
            DockerCtl.safeKillAndRemove(containerName, testLogger);
            console = "SQL shard container failed: " + e;
        }

        // 4) The verdicts CTP wrote, by repository path
        Map<String, SqlBatch.Verdict> verdicts;
        try {
            verdicts = SqlBatch.collect(testtools.resolve("CTP/sql/result"), startedAt, TREE_IN_CONTAINER);
        } catch (Exception e) {
            testLogger.warning("Could not read the shard's summary_info: " + e.getMessage());
            verdicts = new java.util.LinkedHashMap<>();
        }
        TestResult.Builder result = summarize(request, verdicts, staged, console, timedOut, cancelled, testLogger);
        cleanup(ws, testLogger);
        return finish(result, metrics, startNs, dockerImage);
    }

    /**
     * The verdicts and the run's console as the shard's result: pass when every case staged passed,
     * fail when any failed or never ran, execution_error when CTP left no verdict at all.
     */
    TestResult.Builder summarize(TestRequest request, Map<String, SqlBatch.Verdict> verdicts, int staged, String console,
                                 boolean timedOut, boolean cancelled, Logger testLogger) {
        return summarize(request, verdicts, staged, console, timedOut, cancelled, requestTestsDir(request, testLogger),
            "docker", testLogger);
    }

    static TestResult.Builder summarize(TestRequest request, Map<String, SqlBatch.Verdict> verdicts, int staged,
                                        String console, boolean timedOut, boolean cancelled, Path testsDir,
                                        String where, Logger testLogger) {
        long ok = verdicts.values().stream().filter(v -> v.ok).count();
        long nok = verdicts.size() - ok;
        long unrun = Math.max(0, staged - verdicts.size());
        String status;
        String message;
        if (cancelled) {
            status = "cancelled";
            message = "Request cancelled";
        } else if (verdicts.isEmpty()) {
            status = TestStatus.EXECUTION_ERROR.getValue();
            message = (timedOut ? "CTP SQL shard timed out" : "CTP SQL shard left no verdict (no summary_info)")
                + " in " + where + ": " + tail(console, 600);
        } else {
            status = (nok > 0 || unrun > 0) ? TestStatus.FAIL.getValue() : TestStatus.PASS.getValue();
            message = String.format(Locale.ROOT, "CTP batch in %s: %d ok, %d nok%s of %d cases%s",
                where, ok, nok, unrun > 0 ? ", " + unrun + " not run" : "", staged, timedOut ? " (timed out)" : "");
        }
        TestResult.Builder builder = TestResult.builder()
            .testName(request.getTestName())
            .commit(request.getCommit() != null ? request.getCommit() : "unknown")
            .commitShort(request.getCommitShort())
            .timestamp(System.currentTimeMillis())
            .status(status)
            .message(message)
            .attempts(1);
        if (testsDir != null) {
            try {
                String base = ("sql_batch_" + request.getCommitShort() + "_" + request.getSqlBatchShard())
                    .replaceAll("[^A-Za-z0-9_.-]", "_");
                Path log = testsDir.resolve(base + ".log");
                Files.write(log, console.getBytes(StandardCharsets.UTF_8));
                Path tsv = testsDir.resolve(base + SqlBatch.VERDICTS_SUFFIX);
                Files.write(tsv, SqlBatch.toTsv(verdicts).getBytes(StandardCharsets.UTF_8));
                // The run's log is the attempt's log; the verdicts an artifact, which the retry loop keeps in
                // attemptLogMetadata and a remote tester sends with the logs
                builder.addAttemptLogFile(log).logPath(log.toString());
                builder.addArtifactFile(tsv, "sql_batch_verdicts", 1, where);
            } catch (Exception e) {
                testLogger.warning("Could not write the shard's logs: " + e.getMessage());
            }
        }
        return builder;
    }

    /**
     * The container's script: the test image's entrypoint, as a gha-ci shard calls it. The build is
     * at $CUBRID (/opt/cubrid in the per-commit image); the entrypoint runs CTP with HOME=/home,
     * where some CTP code looks for ~/CUBRID. Files go back to the tester's user.
     */
    static String script() {
        return "#!/bin/bash\n"
            + "# Generated by builder-tester: one shard of the full SQL suite, as cubridci gha-ci runs it.\n"
            + "[ -e /home/CUBRID ] || ln -s \"$CUBRID\" /home/CUBRID\n"
            + "cd /home\n"
            + "echo \"[sql-batch] $(find " + TREE_IN_CONTAINER + "/sql -path '*/cases/*.sql' | wc -l) cases in "
            + TREE_IN_CONTAINER + "/sql\"\n"
            + "rc=0\n"
            + "/entrypoint.sh test sql || rc=$?\n"
            + "/entrypoint.sh results sql /out || echo \"[sql-batch] could not copy the results\"\n"
            + "echo \"[sql-batch] entrypoint test exited $rc\"\n"
            + "chown -R \"$HOST_UID:$HOST_GID\" " + TREE_IN_CONTAINER + " " + TESTTOOLS_IN_CONTAINER + " /out 2>/dev/null || true\n"
            + "exit 0\n";
    }

    private List<String> dockerRun(String containerName, TestRequest request, Path ws, Path tree, Path testtools,
                                   Path out, String dockerImage) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        if (config.isDockerEnforceCpuLimits() && config.getDockerCpuLimitMillicores() != null) {
            cmd.add("--cpus=" + String.format(Locale.ROOT, "%.3f", Math.max(0.1, config.getDockerCpuLimitMillicores() / 1000.0)));
        }
        // Always a hard memory limit, whatever docker_enforce_memory_limits says: a shard is a whole
        // CUBRID server, and one whose memory ran away (7 GB, a case the build does not support)
        // took the shared host down with it. Over the limit, the kernel kills it in its container
        // only; its cases come back not run and are re-run one by one.
        long memBytes = request.getSqlBatchMemoryLimitMb() * 1024L * 1024L;
        cmd.add("--memory=" + memBytes);
        cmd.add("--memory-swap=" + memBytes);
        cmd.add("--log-driver");
        cmd.add("json-file");
        cmd.add("--log-opt");
        cmd.add("max-size=50m");
        cmd.add("--name");
        cmd.add(containerName);
        DockerLabels.addRequestLabel(cmd, request.getRequestId());
        cmd.add("--init");
        cmd.add("--tmpfs");
        cmd.add("/tmp:exec,size=2G");
        cmd.add("--shm-size=2g");
        cmd.add("-v");
        cmd.add(tree + ":" + TREE_IN_CONTAINER);
        cmd.add("-v");
        cmd.add(testtools + ":" + TESTTOOLS_IN_CONTAINER);
        cmd.add("-v");
        cmd.add(out + ":/out");
        cmd.add("-v");
        cmd.add(ws.resolve("run_batch.sh") + ":/run_batch.sh:ro");
        cmd.add("-e");
        cmd.add("HOST_UID=" + hostId("-u"));
        cmd.add("-e");
        cmd.add("HOST_GID=" + hostId("-g"));
        cmd.add("--entrypoint");
        cmd.add("bash");
        cmd.add(dockerImage);
        cmd.add("/run_batch.sh");
        return cmd;
    }

    private boolean hasImage(String commitShort, String imageVariant) {
        if (imageBuilder == null) {
            return false;
        }
        try {
            Object result = imageBuilder.getClass().getMethod("hasImage", String.class, String.class)
                .invoke(imageBuilder, commitShort, imageVariant);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            return false;
        }
    }

    private Path requestTestsDir(TestRequest request, Logger testLogger) {
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

    private static TestResult.Builder error(TestRequest request, TestStatus status, String message) {
        return TestResult.builder()
            .testName(request.getTestName())
            .commit(request.getCommit() != null ? request.getCommit() : "unknown")
            .commitShort(request.getCommitShort())
            .timestamp(System.currentTimeMillis())
            .status(status)
            .message(message);
    }

    private static TestResult finish(TestResult.Builder builder, TestExecutionMetrics.Builder metrics, long startNs,
                                     String dockerImage) {
        metrics.durationMs(Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)));
        metrics.dockerImage(dockerImage);
        builder.executionMetrics(metrics.build());
        builder.executionMode("docker_sql_batch");
        return builder.build();
    }

    private static void cleanup(Path ws, Logger testLogger) {
        try {
            SafeIo.deleteDirectory(ws.toFile());
        } catch (Exception e) {
            testLogger.warning("Could not remove " + ws + ": " + e.getMessage());
        }
    }

    static String tail(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : "..." + s.substring(s.length() - max);
    }

    private static String hostId(String flag) {
        try {
            Process p = new ProcessBuilder("id", flag).start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String id = reader.readLine();
                p.waitFor();
                return id != null ? id.trim() : "1000";
            }
        } catch (Exception e) {
            return "1000";
        }
    }
}
