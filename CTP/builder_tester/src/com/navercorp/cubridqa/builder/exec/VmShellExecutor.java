package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.cache.BuildCache;
import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.git.ShellTcSync;
import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.tester.CancelledRequests;
import com.navercorp.cubridqa.builder.tester.CustomAttachment;
import com.navercorp.cubridqa.builder.tester.TestRequest;
import com.navercorp.cubridqa.builder.tester.TestResult;
import com.navercorp.cubridqa.builder.tester.TestStatus;
import com.navercorp.cubridqa.builder.tester.stats.TestExecutionMetrics;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * execution_mode=vm: runs a shell case on a dedicated QA VM as the VM's own user, with the
 * VM's own CTP, against a build installed at vm_cubrid_home, one case at a time.
 *
 * Each case goes through the same steps CTP shell applies in QAHome regression (see
 * VmScriptFactory); the verdict is CTP's: the case's .result after do_check_more_errors,
 * failing on a blank result or any line containing NOK.
 */
public class VmShellExecutor implements ExecutorStrategy {
    private static final String CUSTOM_ONLY_PATH = "custom_script_test";
    /** ~/CUBRID, its processes and ~/.CUBRID_SHELL_FM are machine-wide: one VM case at a time (shell or SQL). */
    static final Object VM_LOCK = new Object();

    private final Config config;
    private final BuildCache buildCache;
    private final ShellTcSync shellTcSync;

    public VmShellExecutor(Config config, BuildCache buildCache, ShellTcSync shellTcSync) {
        this.config = config;
        this.buildCache = buildCache;
        this.shellTcSync = shellTcSync;
    }

    @Override
    public TestResult execute(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        // ~/CUBRID, ~/.CUBRID_SHELL_FM and the process reset are machine-wide: never overlap cases.
        synchronized (VM_LOCK) {
            return executeLocked(request, workDir, testLogger);
        }
    }

    private TestResult executeLocked(TestRequest request, Path workDir, Logger testLogger) throws Exception {
        long startNs = System.nanoTime();
        TestExecutionMetrics.Builder metrics = TestExecutionMetrics.builder()
            .buildPackageName(request.getBuildPackage())
            .metricsComplete(false);
        String commitShort = request.getCommitShort() != null ? request.getCommitShort() : "unknown";

        Path cubridHome = Paths.get(config.getVmCubridHome()).toAbsolutePath().normalize();
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        String unsafe = checkCubridHome(cubridHome, home);
        if (unsafe != null) {
            return finish(base(request).status(TestStatus.ENVIRONMENT_ERROR).message(unsafe), metrics, startNs);
        }
        Path ctpHome = resolveCtpHome(config);
        if (!Files.isRegularFile(ctpHome.resolve("shell/init_path/init.sh"))
                || !Files.isRegularFile(ctpHome.resolve("shell/init_path/shell_utils.sh"))) {
            return finish(base(request).status(TestStatus.ENVIRONMENT_ERROR)
                .message("CTP not found at " + ctpHome + " (set vm_ctp_home)"), metrics, startNs);
        }

        StringBuilder preamble = new StringBuilder();
        preamble.append("[builder-tester vm] user=").append(System.getProperty("user.name"))
            .append(" CUBRID=").append(cubridHome).append(" CTP_HOME=").append(ctpHome).append('\n');

        // 1. Build: install once per package, like CTP's deploy step.
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
            ensureInstalled(localPackage, commitShort, cubridHome, ctpHome, workDir, preamble, testLogger);
        } catch (Exception e) {
            testLogger.log(Level.SEVERE, "VM build install failed", e);
            return finish(base(request).status(TestStatus.BUILD_ERROR)
                .message("Failed to install build: " + e.getMessage()), metrics, startNs);
        }

        // 2. Case directory: per-request worktree under $HOME, restored to its committed state.
        Path caseDir;
        try {
            caseDir = prepareCaseDir(request, workDir, home, preamble, testLogger);
        } catch (Exception e) {
            return finish(base(request).status(TestStatus.ENVIRONMENT_ERROR)
                .message("Failed to prepare case directory: " + e.getMessage()), metrics, startNs);
        }
        String caseFile = request.getTestScript();
        if (!Files.isRegularFile(caseDir.resolve(caseFile))) {
            return finish(base(request).status(TestStatus.ENVIRONMENT_ERROR)
                .message("Case script not found: " + caseDir.resolve(caseFile)), metrics, startNs);
        }

        // 3. Run the case.
        Path testsDir = requestTestsDir();
        String logName = artifactBaseName(request, commitShort) + attemptSuffix(request) + ".log";
        Path logFile = (testsDir != null ? testsDir : workDir).resolve(logName);
        Files.write(logFile, preamble.toString().getBytes(StandardCharsets.UTF_8));

        Path pgidFile = workDir.resolve("vm_case.pgid");
        Path runner = writeScript(workDir.resolve("vm_case.sh"), VmScriptFactory.createCaseScript(
            cubridHome.toString(), ctpHome.toString(), caseDir.toString(), caseFile,
            config.getVmSshPort(), config.getVmBigSpaceDir(), config.getVmDefaultDbcharset(),
            config.getVmIgnoreCoreByKeywords(), pgidFile.toString()));
        try {
            Path saved = (testsDir != null ? testsDir : workDir).resolve(artifactBaseName(request, commitShort) + "_runner.sh");
            Files.copy(runner, saved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignore) { }

        int timeoutSecs = config.getVmCaseTimeoutSeconds();
        testLogger.info("VM case run: " + caseDir.resolve(caseFile) + " (timeout " + timeoutSecs + "s)");
        ProcessBuilder pb = new ProcessBuilder("setsid", "bash", runner.toString());
        pb.directory(home.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = pb.start();
        // Wait in short steps so a cancelled request stops its case (and releases the VM) at once
        // instead of holding VM_LOCK until the case finishes or times out.
        String requestId = RequestContext.getRequestId();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSecs);
        boolean completed = false;
        boolean cancelled = false;
        while (!completed) {
            long leftNanos = deadline - System.nanoTime();
            if (leftNanos <= 0) {
                break;
            }
            completed = process.waitFor(Math.min(TimeUnit.NANOSECONDS.toMillis(leftNanos), 2000L), TimeUnit.MILLISECONDS);
            if (!completed && CancelledRequests.isCancelled(requestId)) {
                cancelled = true;
                break;
            }
        }
        if (cancelled) {
            testLogger.warning("Request cancelled while the VM case was running; killing its process group");
            killProcessGroup(pgidFile, testLogger);
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
            appendLine(logFile, VmScriptFactory.PHASE_PREFIX + "CANCELLED");
        }
        boolean timedOut = !completed && !cancelled;
        if (timedOut) {
            testLogger.warning("VM case timed out after " + timeoutSecs + "s; killing its process group");
            killProcessGroup(pgidFile, testLogger);
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
            appendLine(logFile, VmScriptFactory.PHASE_PREFIX + "TIMEOUT after " + timeoutSecs + "s");
        }

        if (CancelledRequests.isCancelled(RequestContext.getRequestId())) {
            resetProcesses(cubridHome, ctpHome, workDir, testLogger);
            return finish(base(request).status("cancelled").message("Request cancelled"), metrics, startNs);
        }

        // 4. Verdict, as CTP's collectGeneralResult: the .result after do_check_more_errors.
        String resultBase = caseFile.endsWith(".sh") ? caseFile.substring(0, caseFile.length() - 3) : caseFile;
        Path resultPath = caseDir.resolve(resultBase + ".result");
        String resultContent = Files.exists(resultPath)
            ? new String(Files.readAllBytes(resultPath), StandardCharsets.UTF_8) : "";
        boolean blank = resultContent.trim().isEmpty();
        boolean nok = resultContent.contains("NOK");
        boolean pass = !timedOut && !blank && !nok;

        TestResult.Builder result = base(request).executionMode("vm").addAttemptLogFile(logFile);
        if (pass) {
            result.status(TestStatus.PASS);
        } else {
            result.status(TestStatus.FAIL);
            if (timedOut) {
                result.message("Case timeout after " + timeoutSecs + "s");
            } else if (blank) {
                result.message("blank result");
            }
        }
        if (testsDir != null) {
            try {
                Path resultCopy = testsDir.resolve("result_" + artifactBaseName(request, commitShort) + attemptSuffix(request) + ".result");
                Files.write(resultCopy, resultContent.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignore) { }
        }

        // 5. Artifacts the case left behind (they are discarded at the next attempt's restore).
        String keep = config.getVmKeepArtifacts();
        if ("always".equals(keep) || (!pass && !"never".equals(keep))) {
            collectArtifacts(request, commitShort, cubridHome, caseDir, testsDir != null ? testsDir : workDir,
                workDir, result, testLogger);
        }

        // CTP resets processes before every case and when it closes; do the closing reset now
        // so nothing from this case keeps running on the VM between requests.
        resetProcesses(cubridHome, ctpHome, workDir, testLogger);
        return finish(result, metrics, startNs);
    }

    static void ensureInstalled(Path pkg, String commitShort, Path cubridHome, Path ctpHome, Path workDir,
                                 StringBuilder preamble, Logger testLogger) throws Exception {
        String marker = "package=" + pkg.getFileName() + " size=" + Files.size(pkg)
            + " mtime=" + Files.getLastModifiedTime(pkg).toMillis() + " commit=" + commitShort;
        Path markerFile = cubridHome.resolve(".builder_tester_build");
        Path fm = Paths.get(System.getProperty("user.home"), ".CUBRID_SHELL_FM");
        if (Files.isRegularFile(markerFile) && Files.isDirectory(fm)
                && marker.equals(new String(Files.readAllBytes(markerFile), StandardCharsets.UTF_8).trim())) {
            preamble.append("[builder-tester vm] build already installed: ").append(marker).append('\n');
            return;
        }
        testLogger.info("Installing " + pkg + " into " + cubridHome);
        Path script = writeScript(workDir.resolve("vm_install.sh"),
            VmScriptFactory.createInstallScript(cubridHome.toString(), ctpHome.toString(), pkg.toString(), marker));
        Path installLog = workDir.resolve("vm_install.log");
        ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(installLog.toFile());
        Process p = pb.start();
        if (!p.waitFor(15, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("install timed out");
        }
        String out = Files.exists(installLog) ? new String(Files.readAllBytes(installLog), StandardCharsets.UTF_8) : "";
        preamble.append(out);
        if (p.exitValue() != 0) {
            throw new IOException("install script exited " + p.exitValue() + ": " + tail(out, 2000));
        }
    }

    private Path prepareCaseDir(TestRequest request, Path workDir, Path home, StringBuilder preamble,
                                Logger testLogger) throws Exception {
        Path caseDir;
        if (CUSTOM_ONLY_PATH.equals(request.getTestPath())) {
            String safeReq = safe(request.getRequestId() == null ? "unknown" : request.getRequestId());
            String name = safe(request.getTestName());
            Path root = Paths.get(config.getWorkDir()).toAbsolutePath().normalize()
                .resolve("vm_custom_cases").resolve(safeReq).resolve(name);
            deleteRecursively(root);
            caseDir = root.resolve("cases");
            Files.createDirectories(caseDir);
        } else {
            Path repoRoot = shellTcSync.prepareRequestWorkspace(testLogger, request.getRequestId(),
                request.getShellTcBranch(), request.getShellTcCommit());
            caseDir = TestDirectoryResolver.resolve(repoRoot, request, testLogger);
            restorePristine(repoRoot, caseDir, preamble, testLogger);
        }
        if (!caseDir.toAbsolutePath().normalize().startsWith(home)) {
            // CTP resolves scenarios relative to $HOME; cases also assume /home/$USER paths.
            preamble.append("[builder-tester vm] WARNING: case dir is outside $HOME: ").append(caseDir).append('\n');
        }

        List<CustomAttachment> attachments = request.getCustomAttachments();
        if (attachments != null) {
            for (CustomAttachment a : attachments) {
                if (a == null || !a.isValid()) {
                    continue;
                }
                String rel = a.getTargetPath().trim().replace('\\', '/');
                Path target = caseDir.resolve(rel).normalize();
                if (rel.startsWith("/") || rel.contains("..") || !target.startsWith(caseDir)) {
                    throw new IllegalArgumentException("Invalid attachment targetPath: " + rel);
                }
                Files.createDirectories(target.getParent());
                Files.write(target, Base64.getMimeDecoder().decode(a.getContentBase64().trim()));
            }
        }
        String custom = request.getCustomShellScript();
        if (custom != null && !custom.isEmpty()) {
            Path script = caseDir.resolve(request.getTestScript());
            Files.write(script, (custom.endsWith("\n") ? custom : custom + "\n").getBytes(StandardCharsets.UTF_8));
            script.toFile().setExecutable(true);
            preamble.append("[builder-tester vm] case script replaced by the request's custom script\n");
        }
        return caseDir;
    }

    /**
     * Restores the case unit (the directory holding cases/) to the committed state, so every
     * attempt starts the way a fresh regression checkout does.
     */
    private void restorePristine(Path repoRoot, Path caseDir, StringBuilder preamble, Logger testLogger) {
        Path unit = "cases".equals(caseDir.getFileName().toString()) ? caseDir.getParent() : caseDir;
        if (unit == null || !Files.exists(repoRoot.resolve(".git"))) {
            return;
        }
        String rel = repoRoot.relativize(unit).toString();
        if (rel.isEmpty()) {
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(repoRoot.toFile());
            int co = ProcessIO.runAndExitCode(pb, new String[]{"git", "checkout", "--", rel});
            int cl = ProcessIO.runAndExitCode(pb, new String[]{"git", "clean", "-fdxq", "--", rel});
            if (co != 0 || cl != 0) {
                preamble.append("[builder-tester vm] WARNING: could not fully restore ").append(rel).append('\n');
            }
        } catch (Exception e) {
            testLogger.warning("Failed to restore " + unit + ": " + e.getMessage());
        }
    }

    private void collectArtifacts(TestRequest request, String commitShort, Path cubridHome, Path caseDir, Path outDir,
                                  Path workDir, TestResult.Builder result, Logger testLogger) {
        String baseName = artifactBaseName(request, commitShort) + attemptSuffix(request);
        Path text = outDir.resolve("vmcase_" + baseName + ".txt");
        Path tar = outDir.resolve("vmcase_" + baseName + ".tar.gz");
        try {
            Path script = writeScript(workDir.resolve("vm_artifacts.sh"), VmScriptFactory.createArtifactScript(
                cubridHome.toString(), caseDir.toString(), text.toString(), tar.toString()));
            runBounded(new ProcessBuilder("bash", script.toString()), workDir, 5, TimeUnit.MINUTES, testLogger);
            if (Files.isRegularFile(text)) {
                result.addArtifactFile(text, "case_files", Math.max(1, request.getAttemptNumber()), "vm");
            }
        } catch (Exception e) {
            testLogger.warning("Failed to collect case artifacts: " + e.getMessage());
        }
    }

    static void resetProcesses(Path cubridHome, Path ctpHome, Path workDir, Logger testLogger) {
        try {
            Path script = writeScript(workDir.resolve("vm_reset.sh"),
                VmScriptFactory.createResetScript(cubridHome.toString(), ctpHome.toString()));
            runBounded(new ProcessBuilder("bash", script.toString()), workDir, 2, TimeUnit.MINUTES, testLogger);
        } catch (Exception e) {
            testLogger.warning("Process reset failed: " + e.getMessage());
        }
    }

    private void killProcessGroup(Path pgidFile, Logger testLogger) {
        try {
            if (!Files.isRegularFile(pgidFile)) {
                return;
            }
            String pgid = new String(Files.readAllBytes(pgidFile), StandardCharsets.UTF_8).trim();
            if (pgid.matches("\\d+") && !"0".equals(pgid) && !"1".equals(pgid)) {
                runBounded(new ProcessBuilder("kill", "-9", "--", "-" + pgid), pgidFile.getParent(), 30, TimeUnit.SECONDS, testLogger);
            }
        } catch (Exception e) {
            testLogger.warning("Failed to kill case process group: " + e.getMessage());
        }
    }

    /**
     * Runs a helper with its output appended to vm_helper.log instead of a pipe: reading a pipe to
     * EOF first (as before) made the timeout unreachable, so a hung `cubrid service stop` held
     * VM_LOCK forever.
     */
    private static void runBounded(ProcessBuilder pb, Path logDir, long timeout, TimeUnit unit, Logger testLogger)
            throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logDir.resolve("vm_helper.log").toFile()));
        Process p = pb.start();
        if (!p.waitFor(timeout, unit)) {
            testLogger.warning("Helper " + pb.command() + " still running after " + timeout + " " + unit + "; killing it");
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** Refuses install targets whose wipe would be destructive. */
    static String checkCubridHome(Path cubridHome, Path home) {
        if (cubridHome.getParent() == null || cubridHome.equals(home) || home.startsWith(cubridHome)) {
            return "Unsafe vm_cubrid_home: " + cubridHome;
        }
        return null;
    }

    static Path resolveCtpHome(Config config) {
        String configured = config.getVmCtpHome();
        if (configured != null && !configured.isEmpty()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        Path vmDefault = Paths.get(System.getProperty("user.home"), "CTP");
        if (Files.isDirectory(vmDefault.resolve("shell/init_path"))) {
            return vmDefault;
        }
        return Paths.get(CtpEnvResolver.findCTPHome()).toAbsolutePath().normalize();
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

    /**
     * vm_<commit>_<case>_<hash of testPath>: cases with the same script name in different
     * directories must not overwrite each other's logs.
     */
    private static String artifactBaseName(TestRequest request, String commitShort) {
        String path = request.getTestPath() == null ? request.getTestName() : request.getTestPath();
        return "vm_" + safe(commitShort) + "_" + safe(request.getTestName()) + "_" + shortHash(path);
    }

    private static String attemptSuffix(TestRequest request) {
        return request.getAttemptNumber() > 1 ? "." + request.getAttemptNumber() : "";
    }

    private static String shortHash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String safe(String s) {
        return (s == null ? "unknown" : s).replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static Path writeScript(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        path.toFile().setExecutable(true);
        return path;
    }

    private static void appendLine(Path file, String line) {
        try {
            Files.write(file, (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        } catch (IOException ignore) { }
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        File[] children = root.toFile().listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory() && !Files.isSymbolicLink(c.toPath())) {
                    deleteRecursively(c.toPath());
                } else {
                    c.delete();
                }
            }
        }
        root.toFile().delete();
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
