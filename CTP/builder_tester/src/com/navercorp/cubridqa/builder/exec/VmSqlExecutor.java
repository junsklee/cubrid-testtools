package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.cache.BuildCache;
import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.ctp.CtpProvisioner;
import com.navercorp.cubridqa.builder.git.SqlTcSync;
import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.tester.CancelledRequests;
import com.navercorp.cubridqa.builder.tester.RequestEndHooks;
import com.navercorp.cubridqa.builder.tester.SafeIo;
import com.navercorp.cubridqa.builder.tester.TestRequest;
import com.navercorp.cubridqa.builder.tester.TestResult;
import com.navercorp.cubridqa.builder.tester.TestStatus;
import com.navercorp.cubridqa.builder.tester.stats.TestExecutionMetrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs SQL testcases (cubrid-testcases sql/**&#47;cases/*.sql) on a QA VM (execution_mode=vm), the
 * way QAHome SQL regression runs them: the build installed into the VM's CUBRID as for shell cases,
 * the test DB provisioned from the VM's own CTP and SQL conf (common/ext/run_sql.sh, then CTP's
 * sql/bin/run.sh), and each case run by CTP's ConsoleAgent against that DB.
 *
 * <p>The DB stays up between the cases of a request, as in a regression run. It is provisioned
 * again when the build or request changes, when it is no longer running (a shell case resets the
 * VM's processes), after a case left a core file, for every retry attempt, and to re-verify a
 * failure (sql_fresh_verify_on_fail): the Docker SQL executor uses a fresh container for those.
 * Provisioning writes into CTP (Function_Db, local.properties), so it uses a copy of the VM's CTP
 * taken per request, never the VM's own. When the request ends, the DB is stopped.
 */
public class VmSqlExecutor implements ExecutorStrategy {
    private static final String CUSTOM_CASE_BASENAME = "custom_sql_case";

    private final Config config;
    private final BuildCache buildCache;
    private final SqlTcSync sqlTcSync;
    private final CtpProvisioner ctpProvisioner;

    /** What the running test DB was provisioned for, and where; guarded by VM_LOCK. */
    private String provisionedKey;
    private volatile String provisionedRequest;

    public VmSqlExecutor(Config config, BuildCache buildCache, SqlTcSync sqlTcSync, CtpProvisioner ctpProvisioner) {
        this.config = config;
        this.buildCache = buildCache;
        this.sqlTcSync = sqlTcSync;
        this.ctpProvisioner = ctpProvisioner;
        RequestEndHooks.register(this::requestEnded);
    }

    @Override
    public TestResult execute(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        synchronized (VmShellExecutor.VM_LOCK) {
            return executeLocked(request, workDir, testLogger);
        }
    }

    private TestResult executeLocked(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        long startNs = System.nanoTime();
        TestExecutionMetrics.Builder metrics = TestExecutionMetrics.builder()
            .buildPackageName(request.getBuildPackage())
            .metricsComplete(false);
        String commitShort = request.getCommitShort() != null ? request.getCommitShort() : "unknown";
        String testPath = request.getTestPath();
        boolean customMode = request.hasCustomSqlScript();
        if (!customMode && (testPath == null || !testPath.endsWith(".sql") || !testPath.contains("/cases/"))) {
            return finish(base(request).status(TestStatus.EXECUTION_ERROR)
                .message("Invalid SQL testcase path: " + testPath), metrics, startNs);
        }

        Path cubridHome = Paths.get(config.getVmCubridHome()).toAbsolutePath().normalize();
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        String unsafe = VmShellExecutor.checkCubridHome(cubridHome, home);
        if (unsafe != null) {
            return finish(base(request).status(TestStatus.ENVIRONMENT_ERROR).message(unsafe), metrics, startNs);
        }
        Path ctpHome = VmShellExecutor.resolveCtpHome(config);
        StringBuilder ctpNote = new StringBuilder();
        VmCtp.ensureInstalled(config, ctpProvisioner, request, ctpHome, ctpNote, Logger.getLogger(VmSqlExecutor.class.getName()));
        if (!Files.isDirectory(ctpHome.resolve("sql/lib")) || !Files.isRegularFile(ctpHome.resolve("bin/ini.sh"))) {
            return finish(base(request).status(TestStatus.ENVIRONMENT_ERROR)
                .message("CTP at " + ctpHome + " has no SQL tool (sql/lib, bin/ini.sh); set vm_ctp_home"), metrics, startNs);
        }

        StringBuilder preamble = new StringBuilder();
        preamble.append("[builder-tester vm] user=").append(System.getProperty("user.name"))
            .append(" CUBRID=").append(cubridHome).append(" CTP_HOME=").append(ctpHome).append('\n');
        preamble.append(ctpNote);

        // 1. Build: installed once per package, shared with shell cases
        Path localPackage;
        try {
            localPackage = buildCache.downloadIfNeeded(request.getBuildPackage(), commitShort, "unknown", testLogger);
            metrics.packageCached(buildCache.wasLastFetchFromCache());
        } catch (Exception e) {
            return finish(base(request).status(TestStatus.ENVIRONMENT_ERROR)
                .message("Failed to download build package: " + e.getMessage()), metrics, startNs);
        }
        if (localPackage == null || !Files.isRegularFile(localPackage)) {
            return finish(base(request).status(TestStatus.ENVIRONMENT_ERROR)
                .message("Build package missing: " + localPackage), metrics, startNs);
        }
        try {
            VmShellExecutor.ensureInstalled(localPackage, commitShort, cubridHome, ctpHome, workDir, preamble, testLogger);
        } catch (Exception e) {
            testLogger.log(Level.SEVERE, "VM build install failed", e);
            return finish(base(request).status(TestStatus.BUILD_ERROR)
                .message("Failed to install build: " + e.getMessage()), metrics, startNs);
        }

        String safeRequest = safe(request.getRequestId() == null ? "adhoc" : request.getRequestId());
        Path workspace = Paths.get(config.getWorkDir()).toAbsolutePath().normalize().resolve("sql_vm").resolve(safeRequest);

        // The SQL conf regression uses for this build: job.conf's MKEY_CONFIG for the version's SQL
        // job, else run_sql.sh's default (conf/sql_local.conf). A shell VM's CTP usually has neither;
        // then Builder-Tester's copy of the regression conf, and conf/sql.conf only without one.
        String buildVersion = installedVersion(cubridHome, workDir);
        String wantedConf = selectSqlConf(buildVersion, config.getVmSqlConfRules(), config.getVmSqlDefaultConf());
        Path sqlConf;
        try {
            sqlConf = resolveSqlConf(ctpHome, wantedConf, workspace, buildVersion, preamble, testLogger);
        } catch (IOException e) {
            return finish(base(request).status(TestStatus.ENVIRONMENT_ERROR).message(e.getMessage()), metrics, startNs);
        }
        preamble.append("[builder-tester vm] SQL conf: ").append(sqlConf).append(" (build ").append(buildVersion).append(")\n");

        // 2. Testcases: the request's pinned worktree (or an empty root for an ad-hoc case)
        Path repoRoot;
        Path caseFile = null;
        try {
            Files.createDirectories(workspace);
            if (customMode) {
                repoRoot = Files.createDirectories(workspace.resolve("empty_testcases"));
            } else {
                repoRoot = sqlTcSync.prepareRequestWorkspace(
                    testLogger, request.getRequestId(), request.getSqlTcBranch(), request.getSqlTcCommit());
                caseFile = repoRoot.resolve(testPath).normalize();
                if (!caseFile.startsWith(repoRoot) || !Files.isRegularFile(caseFile)) {
                    return finish(base(request).status(TestStatus.EXECUTION_ERROR)
                        .message("SQL testcase not found in repository: " + testPath), metrics, startNs);
                }
            }
        } catch (Exception e) {
            return finish(base(request).status(TestStatus.ENVIRONMENT_ERROR)
                .message("Failed to prepare SQL testcase workspace: " + e.getMessage()), metrics, startNs);
        }

        // 3. Scripts: provisioning from a per-request copy of the VM's CTP, and the case runner
        String caseArg;
        try {
            Path ctpCopy = workspace.resolve("CTP");
            if (!Files.isDirectory(ctpCopy.resolve("sql/lib"))) {
                SafeIo.deleteDirectory(ctpCopy.toFile());
                SafeIo.copyTestCaseDirectory(ctpHome, ctpCopy);
            }
            SqlScriptFactory.SqlEnvSettings settings = new SqlScriptFactory.SqlEnvSettings(
                config.getSqlDbName(), config.getSqlDbCharsetOverride(), config.getSqlCreatedbOptsOverride(),
                config.isSqlLoadStoredProcedures(), config.getSqlNeedMakeLocaleOverride(), config.getSqlHaModeOverride(),
                request.getExpectedBuildVersion());
            writeScript(workspace.resolve("sql_env_setup.sh"), SqlScriptFactory.createVmEnvSetupScript(settings,
                cubridHome.toString(), ctpCopy.toString(), repoRoot.toString(), workspace.toString(),
                sqlConf.toString(), true));
            writeScript(workspace.resolve("sql_run_case.sh"),
                SqlScriptFactory.createCaseRunScript(config.getSqlCaseTimeoutSec(), workspace.toString()));
            if (customMode) {
                Path customRoot = workspace.resolve("custom_sql").resolve(safe(request.getTestName()) + "_" + System.nanoTime());
                Files.createDirectories(customRoot.resolve("cases"));
                Files.createDirectories(customRoot.resolve("answers"));
                Path customCase = customRoot.resolve("cases").resolve(CUSTOM_CASE_BASENAME + ".sql");
                Files.write(customCase, request.getCustomSqlScript().getBytes(StandardCharsets.UTF_8));
                String answer = request.getCustomSqlAnswer() == null ? "" : request.getCustomSqlAnswer();
                Files.write(customRoot.resolve("answers").resolve(CUSTOM_CASE_BASENAME + ".answer"),
                    answer.getBytes(StandardCharsets.UTF_8));
                caseArg = customCase.toString();
            } else {
                caseArg = testPath;
            }
        } catch (Exception e) {
            return finish(base(request).status(TestStatus.ENVIRONMENT_ERROR)
                .message("Failed to stage the SQL workspace: " + e.getMessage()), metrics, startNs);
        }

        // 4. Run: on the warm DB, then (if it failed) once more on a freshly provisioned one
        String key = request.getRequestId() + "|" + installedBuild(cubridHome) + "|" + repoRoot;
        int attempt = Math.max(1, request.getAttemptNumber());
        SqlCaseRun warmRun = null;
        SqlCaseRun finalRun = runCase(request, workspace, caseArg, attempt, key, attempt > 1,
            attempt > 1 ? "vm_fresh" : "vm_warm", preamble, testLogger);
        if (attempt == 1 && !CancelledRequests.isCancelled(request.getRequestId())
                && SqlResultWriter.needsFreshVerify(finalRun) && config.isSqlFreshVerifyOnFail()) {
            testLogger.info("SQL case was not a pass on the running DB (" + SqlResultWriter.describe(finalRun)
                + "); re-verifying on a freshly provisioned DB");
            warmRun = finalRun;
            finalRun = runCase(request, workspace, caseArg, attempt, key, true, "vm_fresh", new StringBuilder(), testLogger);
        }

        if (CancelledRequests.isCancelled(request.getRequestId())) {
            return finish(base(request).status("cancelled").message("Request cancelled"), metrics, startNs);
        }
        TestResult.Builder result = SqlResultWriter.build(request, caseFile, finalRun, warmRun, attempt,
            requestTestsDir(), "vm_sql", config.getSqlCaseTimeoutSec(), metrics, testLogger);
        return finish(result, metrics, startNs);
    }

    /**
     * Runs one case, provisioning the DB first when needed (or always, when fresh). The returned
     * run's console holds the preamble, any provisioning output and the runner's output.
     */
    private SqlCaseRun runCase(TestRequest request, Path workspace, String caseArg, int attempt, String key,
                               boolean fresh, String env, StringBuilder preamble, Logger testLogger) {
        SqlCaseRun run = new SqlCaseRun();
        run.executionEnv = env;
        StringBuilder console = new StringBuilder(preamble);
        try {
            if (fresh || !key.equals(provisionedKey) || !dbRunning(workspace, testLogger)) {
                console.append("===== [builder-tester vm] provision SQL test DB").append(fresh ? " (fresh)" : "").append('\n');
                String failure = provision(workspace, console, testLogger);
                if (failure != null) {
                    provisionedKey = null;
                    run.containerConsole = console.toString();
                    run.failureMessage = failure;
                    return run;
                }
                provisionedKey = key;
                provisionedRequest = request.getRequestId();
            } else {
                console.append("===== [builder-tester vm] SQL test DB already running for this request\n");
            }

            String slot = safe(request.getTestName()) + "_" + System.nanoTime();
            Path pgidFile = workspace.resolve("sql_case.pgid");
            Files.deleteIfExists(pgidFile);
            Path out = workspace.resolve("sql_case_" + slot + ".out");
            // setsid + the pid file: a cancel or a hang kills the whole process group
            ProcessBuilder pb = new ProcessBuilder("setsid", "bash", "-c",
                "echo $$ > " + VmScriptFactory.quote(pgidFile.toString()) + "; exec bash "
                    + VmScriptFactory.quote(workspace.resolve("sql_run_case.sh").toString()) + " "
                    + VmScriptFactory.quote(caseArg) + " " + attempt + " " + VmScriptFactory.quote(slot));
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(out.toFile());
            console.append("===== [builder-tester vm] run case ").append(caseArg).append('\n');
            Process process = pb.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.getSqlCaseTimeoutSec() + 120L);
            boolean completed = false;
            while (!completed && System.nanoTime() < deadline) {
                completed = process.waitFor(2, TimeUnit.SECONDS);
                if (!completed && CancelledRequests.isCancelled(request.getRequestId())) {
                    break;
                }
            }
            if (!completed) {
                killProcessGroup(pgidFile, workspace, testLogger);
                process.destroyForcibly();
                process.waitFor(30, TimeUnit.SECONDS);
                provisionedKey = null;
                console.append(CancelledRequests.isCancelled(request.getRequestId())
                    ? "===== [builder-tester vm] CANCELLED\n" : "===== [builder-tester vm] runner did not finish; killed\n");
            }
            if (Files.exists(out)) {
                console.append(new String(Files.readAllBytes(out), StandardCharsets.UTF_8));
            }
            run.containerConsole = console.toString();
            run.attemptDir = workspace.resolve("results").resolve(slot).resolve("attempt_" + attempt);
            run.outcome = SqlResultParser.parseStatusFile(run.attemptDir);
            if (run.outcome == null) {
                run.outcome = SqlResultParser.parseConsole(run.containerConsole);
            }
            if (run.outcome == null) {
                run.failureMessage = completed ? "SQL case produced no outcome" : "SQL case runner killed";
                provisionedKey = null;
            } else if (run.outcome.core) {
                provisionedKey = null; // a core file: the DB state is suspect
            }
            return run;
        } catch (Exception e) {
            testLogger.log(Level.WARNING, "VM SQL case execution failed", e);
            provisionedKey = null;
            run.containerConsole = console.toString();
            run.failureMessage = "VM SQL case execution failed: " + e.getMessage();
            return run;
        }
    }

    /** Runs sql_env_setup.sh; returns null when the DB is up, else why it is not. */
    private String provision(Path workspace, StringBuilder console, Logger testLogger) throws Exception {
        Path ready = workspace.resolve(SqlScriptFactory.READY_MARKER);
        Files.deleteIfExists(ready);
        Files.deleteIfExists(workspace.resolve("sql_env.sh"));
        Path log = workspace.resolve("sql_env_setup.log");
        ProcessBuilder pb = new ProcessBuilder("bash", workspace.resolve("sql_env_setup.sh").toString());
        pb.directory(workspace.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        Process p = pb.start();
        long minutes = Math.max(10, config.getTestReadTimeoutMinutes());
        boolean done = p.waitFor(minutes, TimeUnit.MINUTES);
        if (!done) {
            p.destroyForcibly();
            p.waitFor(30, TimeUnit.SECONDS);
        }
        String output = Files.exists(log) ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
        console.append(output);
        if (!done) {
            return "SQL test DB provisioning timed out after " + minutes + " minutes";
        }
        if (p.exitValue() != 0 || !Files.exists(ready)) {
            testLogger.warning("SQL test DB provisioning failed (exit " + p.exitValue() + ")");
            return "SQL test DB provisioning failed: " + tail(output, 1500);
        }
        return null;
    }

    /** True when the provisioned server and a broker are running (a shell case may have stopped them). */
    private boolean dbRunning(Path workspace, Logger testLogger) {
        Path env = workspace.resolve("sql_env.sh");
        if (!Files.isRegularFile(env)) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", "source " + VmScriptFactory.quote(env.toString())
                + " && cubrid server status 2>/dev/null | grep -qw \"$SQL_DB_NAME\""
                + " && cubrid broker status 2>/dev/null | grep -q PID");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(workspace.resolve("vm_helper.log").toFile()));
            Process p = pb.start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            testLogger.fine("DB status check failed: " + e.getMessage());
            return false;
        }
    }

    /** Request finished or cancelled: stop the DB it provisioned and drop its workspace. */
    private void requestEnded(String requestId, boolean cancelled) {
        Path workspace = Paths.get(config.getWorkDir()).toAbsolutePath().normalize()
            .resolve("sql_vm").resolve(safe(requestId));
        // A request that never provisioned the SQL DB here (every shell-only request) has nothing to
        // undo: taking VM_LOCK made its finalize wait behind a running case, up to its 2h timeout,
        // while the Builder's finalize call gave up after 10s
        if (!requestId.equals(provisionedRequest) && !Files.isDirectory(workspace)) {
            return;
        }
        synchronized (VmShellExecutor.VM_LOCK) {
            if (requestId.equals(provisionedRequest)) {
                Logger log = Logger.getLogger(VmSqlExecutor.class.getName());
                Path cubridHome = Paths.get(config.getVmCubridHome()).toAbsolutePath().normalize();
                try {
                    Files.createDirectories(workspace);
                    VmShellExecutor.resetProcesses(cubridHome, VmShellExecutor.resolveCtpHome(config), workspace, log);
                } catch (IOException e) {
                    log.warning("Could not stop the SQL test DB of " + requestId + ": " + e.getMessage());
                }
                provisionedKey = null;
                provisionedRequest = null;
            }
            if (Files.isDirectory(workspace)) {
                SafeIo.deleteDirectory(workspace.toFile());
            }
        }
    }

    /**
     * The conf file for a build version: the first rule whose patterns match, else defaultConf.
     * rules is "patterns=conf;patterns=conf" with job.conf acceptversions globs ("10.1.*,10.2.*").
     */
    static String selectSqlConf(String version, String rules, String defaultConf) {
        if (version != null && rules != null) {
            for (String rule : rules.split(";")) {
                int eq = rule.lastIndexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String conf = rule.substring(eq + 1).trim();
                for (String pattern : rule.substring(0, eq).split(",")) {
                    if (!pattern.trim().isEmpty() && !conf.isEmpty() && versionMatches(version, pattern.trim())) {
                        return conf;
                    }
                }
            }
        }
        return defaultConf;
    }

    /** job.conf acceptversions matching: '*' is any text, everything else literal. */
    static boolean versionMatches(String version, String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            regex.append(c == '*' ? ".*" : java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return version.matches(regex.toString());
    }

    /** "11.0.17.0000" from cubrid_rel's "CUBRID 11.0 (11.0.17.0000-0000000) ...", or null. */
    static String parseVersion(String cubridRelOutput) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(cubridRelOutput);
        return m.find() ? m.group(1) : null;
    }

    /** Where Builder-Tester's copies of regression SQL confs live (jar resources). */
    static final String BUNDLED_CONF_DIR = "vm_sql_conf/";

    /**
     * The file to provision with: the VM's CTP/conf/&lt;wanted&gt;, else Builder-Tester's copy of it
     * (written to the request workspace), else the VM's conf/sql.conf with a warning.
     */
    static Path resolveSqlConf(Path ctpHome, String wanted, Path workspace, String buildVersion,
                               StringBuilder preamble, Logger testLogger) throws IOException {
        Path confDir = ctpHome.resolve("conf");
        Path vmConf = confDir.resolve(wanted).normalize();
        if (vmConf.startsWith(confDir) && Files.isRegularFile(vmConf)) {
            return vmConf;
        }
        if (wanted.matches("[A-Za-z0-9_.-]+")) {
            try (java.io.InputStream bundled = VmSqlExecutor.class.getResourceAsStream(BUNDLED_CONF_DIR + wanted)) {
                if (bundled != null) {
                    Path copy = workspace.resolve("regression_conf").resolve(wanted);
                    Files.createDirectories(copy.getParent());
                    Files.copy(bundled, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    preamble.append("[builder-tester vm] this VM's CTP has no conf/").append(wanted)
                        .append("; using Builder-Tester's copy of the regression conf (cubrid-testtools doc/sql_guide.md)\n");
                    return copy;
                }
            }
        }
        Path fallback = confDir.resolve("sql.conf");
        if (!Files.isRegularFile(fallback)) {
            throw new IOException("Neither conf/" + wanted + " nor conf/sql.conf is in " + ctpHome);
        }
        preamble.append("[builder-tester vm] WARNING: regression provisions build ").append(buildVersion)
            .append(" with conf/").append(wanted).append(", which neither this VM's CTP nor Builder-Tester has; using conf/sql.conf\n");
        testLogger.warning("conf/" + wanted + " missing in " + ctpHome + " and not bundled; using conf/sql.conf");
        return fallback;
    }

    /** The installed build's version, from its cubrid_rel. */
    private static String installedVersion(Path cubridHome, Path workDir) {
        try {
            Path out = workDir.resolve("cubrid_rel.out");
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "export CUBRID=" + VmScriptFactory.quote(cubridHome.toString())
                    + "; export LD_LIBRARY_PATH=\"$CUBRID/lib:$LD_LIBRARY_PATH\"; \"$CUBRID/bin/cubrid_rel\"");
            pb.redirectErrorStream(true);
            pb.redirectOutput(out.toFile());
            Process p = pb.start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return parseVersion(new String(Files.readAllBytes(out), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String installedBuild(Path cubridHome) {
        try {
            return new String(Files.readAllBytes(cubridHome.resolve(".builder_tester_build")), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static void killProcessGroup(Path pgidFile, Path workspace, Logger testLogger) {
        try {
            if (!Files.isRegularFile(pgidFile)) {
                return;
            }
            String pgid = new String(Files.readAllBytes(pgidFile), StandardCharsets.UTF_8).trim();
            if (pgid.matches("\\d+") && !"0".equals(pgid) && !"1".equals(pgid)) {
                Process p = new ProcessBuilder("kill", "-9", "--", "-" + pgid)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(workspace.resolve("vm_helper.log").toFile()))
                    .start();
                p.waitFor(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            testLogger.warning("Failed to kill the SQL case process group: " + e.getMessage());
        }
    }

    private Path requestTestsDir() {
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && !CancelledRequests.isCancelled(requestId) && config.isRequestGroupingEnabled()) {
                return Paths.get(RequestLogManager.getInstance().createRequestSubdir(requestId, "tests"));
            }
        } catch (Exception ignore) { }
        return null;
    }

    private static void writeScript(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        path.toFile().setExecutable(true);
    }

    private static String safe(String s) {
        return (s == null ? "unknown" : s).replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private static TestResult.Builder base(TestRequest request) {
        return TestResult.builder()
            .testName(request.getTestName())
            .commit(request.getCommit() != null ? request.getCommit() : "unknown")
            .commitShort(request.getCommitShort())
            .timestamp(System.currentTimeMillis());
    }

    private static TestResult finish(TestResult.Builder builder, TestExecutionMetrics.Builder metrics, long startNs) {
        metrics.durationMs(Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)));
        builder.executionMetrics(metrics.build());
        builder.executionMode("vm");
        return builder.build();
    }
}
