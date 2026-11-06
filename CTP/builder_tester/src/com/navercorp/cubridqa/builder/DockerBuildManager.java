/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import com.navercorp.cubridqa.builder.docker.DockerLabels;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import com.navercorp.cubridqa.builder.logging.*;
import com.navercorp.cubridqa.builder.docker.DockerUtils;
import com.navercorp.cubridqa.builder.docker.SecureDockerEnv;

/**
 * DockerBuildManager - Manages CUBRID builds within Docker containers
 */
public class DockerBuildManager {
    private static final Logger logger = Logger.getLogger(DockerBuildManager.class.getName());
    
    private final BuilderConfig config;
    private boolean dockerAvailable;
    private boolean imageReady;
    // docker_build_image, or the local image that adds ccache to it (see ensureCcache)
    private volatile String buildImage;

    // ccache 3.1.6, as cubridci/cubridci:develop carried it until 2026-11-03
    private static final String EL6_CCACHE_RPM =
        "https://archives.fedoraproject.org/pub/archive/epel/6/x86_64/Packages/c/ccache-3.1.6-2.el6.x86_64.rpm";

    public DockerBuildManager(BuilderConfig config) {
        this.config = config;
        this.dockerAvailable = DockerUtils.isDockerAvailable();
        this.imageReady = false;
        this.buildImage = config.getDockerBuildImage();

        if (!dockerAvailable) {
            logger.warning("Docker is not available. Falling back to direct build.");
        }
    }
    
    public void initialize() throws IOException, InterruptedException {
        if (!dockerAvailable) {
            return;
        }
        
        if (config.usePrebuiltDockerImages()) {
            logger.info("Using pre-built Docker image: " + config.getDockerBuildImage());
            pullPrebuiltImage();
        } else {
            logger.info("Building Docker image from source");
            buildCustomImage();
        }
        if (config.isCcacheEnabled()) {
            buildImage = ensureCcache(config.getDockerBuildImage());
        }

        imageReady = true;
    }

    /**
     * Returns an image with ccache for builds that pass CC="ccache gcc". cubridci/cubridci:develop
     * dropped ccache on 2026-11-03; an image without it gets a local derivative that adds it back
     * (the same 3.1.6 on CentOS 6, so the existing cache still hits). Docker's layer cache makes
     * this instant until the base image changes. On failure the base image is used, and the build
     * script compiles without ccache.
     */
    private String ensureCcache(String image) {
        try {
            Process check = new ProcessBuilder("docker", "run", "--rm", "--entrypoint", "/bin/sh", image,
                                               "-c", "command -v ccache")
                .redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
            if (check.waitFor() == 0) {
                return image;
            }
            String name = image.substring(image.lastIndexOf('/') + 1);
            String tag = name.substring(name.lastIndexOf(':') + 1).replaceAll("[^A-Za-z0-9_.-]", "_");
            String derived = "cubridci-local:" + tag + "-ccache";
            logger.info(image + " has no ccache; building " + derived + " with it");
            String dockerfile = "FROM " + image + "\n"
                + "RUN if grep -q 'release 6' /etc/redhat-release; then"
                + " rpm --import /etc/pki/rpm-gpg/RPM-GPG-KEY-EPEL-6 && curl -fsSL -o /tmp/ccache.rpm " + EL6_CCACHE_RPM
                + " && rpm -K /tmp/ccache.rpm && rpm -i /tmp/ccache.rpm && rm -f /tmp/ccache.rpm;"
                + " else yum install -y ccache && yum clean all; fi\n";
            ProcessBuilder pb = new ProcessBuilder("docker", "build", "-t", derived, "-");
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process build = pb.start();
            try (OutputStream in = build.getOutputStream()) {
                in.write(dockerfile.getBytes("UTF-8"));
            }
            if (!build.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
                build.destroyForcibly();
                throw new IOException("docker build timed out");
            }
            if (build.exitValue() != 0) {
                throw new IOException("docker build exited with " + build.exitValue());
            }
            logger.info("Builds run in " + derived);
            return derived;
        } catch (Exception e) {
            logger.warning("Could not add ccache to " + image + " (" + e.getMessage() + "); builds compile without ccache");
            return image;
        }
    }
    
    private void pullPrebuiltImage() throws IOException, InterruptedException {
        String imageName = config.getDockerBuildImage();
        logger.info("Pulling Docker image: " + imageName);
        
        ProcessBuilder pb = new ProcessBuilder("docker", "pull", imageName);
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            throw new IOException("Failed to pull Docker image: " + imageName);
        }
        
        logger.info("Successfully pulled image: " + imageName);
    }
    
    private void buildCustomImage() throws IOException, InterruptedException {
        // This would build from source if needed
        // For now, we'll use the pre-built images
        pullPrebuiltImage();
    }
    
    /**
     * Builds without workspace reuse. Callers that do not know the branch (/build-single) cannot
     * pass the build_workspace_reuse_branches gate, so they must not inherit a previous workspace.
     */
    public String buildCubrid(String commitHash, File workDir, String buildType, String baselineCommit, String commitBuildMode)
            throws IOException, InterruptedException {
        return buildCubrid(commitHash, workDir, buildType, baselineCommit, commitBuildMode, false, null);
    }

    /**
     * @param reuseWorkspace whether this build may reuse the previous build's workspace.
     *        The caller resolves the policy (request override, branch gate, else
     *        build_workspace_reuse); this is passed per request rather than held on this
     *        shared instance, so concurrent builds cannot affect each other.
     * @param cubridBranch the branch being built, which forms part of the workspace's
     *        identity so a workspace is never inherited across branches.
     *
     * Builds are serialized: /build and /build-single (and the direct fallback's /cubrid-src
     * worktrees) share one build workspace, /work/cubrid-build, and its reuse markers.
     */
    public synchronized String buildCubrid(String commitHash, File workDir, String buildType, String baselineCommit, String commitBuildMode,
                              boolean reuseWorkspace, String cubridBranch) 
            throws IOException, InterruptedException {
        
        if (!dockerAvailable || !imageReady) {
            return buildCubridDirect(commitHash, workDir, buildType, baselineCommit, commitBuildMode);
        }
        
        logger.info("Building CUBRID commit " + commitHash + " in Docker container");
        final String commitShort = commitHash.substring(0, Math.min(commitHash.length(), 7));
        
        // Check for GitHub token
        String githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.trim().isEmpty()) {
            throw new IOException("GITHUB_TOKEN environment variable is not set");
        }
        
        // Prepare host directories to avoid filling Docker overlay under /var
        File hostRoot = new File(config.getDockerHostRoot());
        if (!hostRoot.exists()) {
            hostRoot.mkdirs();
        }
        File hostWorkDir = new File(hostRoot, "work");
        if (!hostWorkDir.exists()) {
            hostWorkDir.mkdirs();
        }
        File gradleCacheDir = new File(hostRoot, ".gradle");
        if (!gradleCacheDir.exists()) {
            gradleCacheDir.mkdirs();
        }
        
        // Prepare ccache directory if enabled (place it under hostWorkDir to avoid cross-device link issues)
        File ccacheDir = null;
        if (config.isCcacheEnabled()) {
            ccacheDir = new File(hostWorkDir, ".ccache");
            if (!ccacheDir.exists()) {
                ccacheDir.mkdirs();
                logger.info("Created ccache directory under work mount: " + ccacheDir.getAbsolutePath());
            }
            // Ensure required subdirectories exist to avoid runtime errors
            try { new File(ccacheDir, "logs").mkdirs(); } catch (Exception ignore) {}
            try { new File(ccacheDir, "tmp").mkdirs(); } catch (Exception ignore) {}
        }
        
        boolean checkoutMode = "checkout".equalsIgnoreCase(commitBuildMode);

        // Create build script
        File buildScript = checkoutMode
            ? createDockerCheckoutBuildScript(commitHash, buildType, workDir)
            : createDockerBuildScript(commitHash, buildType, baselineCommit, workDir);
        
        // Prepare Docker command
        List<String> baseDockerCmd = new ArrayList<>();
        baseDockerCmd.add("docker");
        baseDockerCmd.add("run");
        baseDockerCmd.add("--rm");
        // Use host networking to reduce DNS/network issues during dependency downloads
        baseDockerCmd.add("--network=host");
        baseDockerCmd.add("-v");
        baseDockerCmd.add(config.getCubridSrcDir() + ":/cubrid-src:ro");
        baseDockerCmd.add("-v");
        baseDockerCmd.add(workDir.getAbsolutePath() + ":/output:rw");
        // Bind host work and Gradle cache into the container
        baseDockerCmd.add("-v");
        baseDockerCmd.add(hostWorkDir.getAbsolutePath() + ":/work:rw");
        baseDockerCmd.add("-v");
        baseDockerCmd.add(gradleCacheDir.getAbsolutePath() + ":/root/.gradle:rw");
        
        // Note: No separate mount for ccache needed since it's inside hostWorkDir (mounted at /work)
        
        baseDockerCmd.add("-v");
        baseDockerCmd.add(buildScript.getAbsolutePath() + ":/build.sh:ro");
        baseDockerCmd.add("-e");
        baseDockerCmd.add("COMMIT_HASH=" + commitHash);
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BUILD_TYPE=" + buildType);
        if (!checkoutMode && baselineCommit != null) {
            baseDockerCmd.add("-e");
            baseDockerCmd.add("BASELINE_COMMIT=" + baselineCommit);
        }
        
        // Add ccache environment variables if enabled
        if (config.isCcacheEnabled()) {
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CC=ccache gcc");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CXX=ccache g++");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_DIR=/work/.ccache");
            // ccache before 3.3 (3.1.6 in cubridci:develop) compiles the preprocessed output on a miss,
            // which has no comments: every /* fall through */ becomes an -Werror=implicit-fallthrough error.
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_CPP2=1");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_COMPILERCHECK=" + config.getCcacheCompilerCheck());
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_HARDLINK=" + (config.getCcacheHardlink() ? "1" : "0"));
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_MAXSIZE=" + config.getCcacheMaxSize());
            // Improve reuse across different work dirs and enable logging
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_BASEDIR=/tmp/cubrid-build");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_NOHASHDIR=1");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_LOGFILE=/work/.ccache/logs/ccache_" + commitShort + ".log");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_TEMPDIR=/work/.ccache/tmp");
            // Optional tuning knobs
            if (config.getCcacheReadonlyDirect()) {
                baseDockerCmd.add("-e");
                baseDockerCmd.add("CCACHE_READONLY_DIRECT=1");
            }
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_STATS=" + (config.getCcacheStatsEnabled() ? "true" : "false"));
            if (!config.getCcacheNamespace().isEmpty()) {
                baseDockerCmd.add("-e");
                baseDockerCmd.add("CCACHE_NAMESPACE=" + config.getCcacheNamespace());
            }
            if (!config.getCcacheSloppiness().isEmpty()) {
                baseDockerCmd.add("-e");
                baseDockerCmd.add("CCACHE_SLOPPINESS=" + config.getCcacheSloppiness());
            }
        }
        
        // Add parallel jobs configuration
        baseDockerCmd.add("-e");
        baseDockerCmd.add("MAKEFLAGS=-j" + config.getParallelJobs());
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BT_ACCEL=" + (config.isBuildAccelEnabled() ? "1" : "0"));
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BT_WORKSPACE_REUSE=" + (reuseWorkspace ? "1" : "0"));
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BT_BRANCH=" + (cubridBranch == null ? "" : cubridBranch));
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BT_IMAGE_ID=" + resolveBuildImageId());
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BT_WORKSPACE_REUSE_MAX=" + config.getBuildWorkspaceReuseMax());
        
        // Pass host UID/GID for output ownership fix (allows cleanup without sudo)
        baseDockerCmd.add("-e");
        baseDockerCmd.add("HOST_UID=" + getHostUid());
        baseDockerCmd.add("-e");
        baseDockerCmd.add("HOST_GID=" + getHostGid());

        // No per-build extra environment overrides (reverted)
        
        // Image and command are appended by runBuildContainer, after the per-attempt options
        
        // Prepare per-build log file
        Path perBuildLog = null;
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String buildsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "builds");
                perBuildLog = Paths.get(buildsDir, "build_" + commitShort + ".log");
            } else {
                Path buildsLogDir = Paths.get(System.getProperty("user.home"), "cubrid-testtools", "CTP", "builder_tester", "log", "builds");
                Files.createDirectories(buildsLogDir);
                perBuildLog = buildsLogDir.resolve("build_" + commitShort + "_" + System.currentTimeMillis() + ".log");
            }
        } catch (Exception e) {
            logger.warning("Failed to create build log directory: " + e.getMessage());
            perBuildLog = Paths.get(workDir.getAbsolutePath(), "build_" + commitShort + ".log");
        }

        // Create secure environment file for sensitive variables (GITHUB_TOKEN)
        // This prevents the token from appearing in ps -ef output
        try (SecureDockerEnv secureEnv = new SecureDockerEnv(workDir.toPath())) {
            secureEnv.add("GITHUB_TOKEN", githubToken);
            String envFilePath = secureEnv.getFilePath();

            // Must precede the image: anything after it is passed to the container's command.
            baseDockerCmd.add("--env-file");
            baseDockerCmd.add(envFilePath);
            runBuildContainer(baseDockerCmd, "Docker build", commitShort, perBuildLog);

            // Return path to built package
            String packageName = "cubrid_" + commitHash.substring(0, 7) + ".tar.gz";
            return new File(workDir, packageName).getAbsolutePath();
        } // SecureDockerEnv auto-closes here, removing the env file
    }

    // Requests cancelled while one of their builds may be running (see cancelBuilds)
    private static final java.util.Set<String> CANCELLED_REQUESTS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong BUILD_SEQ = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Stops the running build containers of a cancelled request and keeps its builds from retrying.
     */
    public void cancelBuilds(String requestId) {
        if (!RequestIds.isValid(requestId)) {
            return;
        }
        CANCELLED_REQUESTS.add(requestId);
        try {
            List<String> ids = new ArrayList<>();
            Process list = new ProcessBuilder("docker", "ps", "-q", "--filter", DockerLabels.requestFilter(requestId))
                .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(list.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) ids.add(line.trim());
                }
            }
            list.waitFor();
            if (!ids.isEmpty()) {
                List<String> kill = new ArrayList<>(Arrays.asList("docker", "kill"));
                kill.addAll(ids);
                new ProcessBuilder(kill).redirectErrorStream(true).start().waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                logger.info("Stopped build containers of cancelled request " + requestId + ": " + ids);
            }
        } catch (IOException e) {
            logger.warning("Failed to stop build containers of " + requestId + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs a build container (one retry), streaming its output to the per-build log.
     * Each attempt gets a unique name and the request label, so build_timeout_minutes can stop
     * it and a cancel (cancelBuilds, or the Builder's /queue/remove cleanup) can find it.
     *
     * @param dockerOptions "docker run ..." options without the image and command
     */
    private void runBuildContainer(List<String> dockerOptions, String label, String commitShort, Path perBuildLog)
            throws IOException, InterruptedException {
        String requestId = RequestContext.getRequestId();
        long timeoutMinutes = Math.max(1, config.getBuildTimeoutMinutes());
        IOException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) { // first attempt + 1 retry
            if (requestId != null && CANCELLED_REQUESTS.contains(requestId)) {
                throw new IOException(label + " cancelled");
            }
            String containerName = "bt_build_" + commitShort + "_" + BUILD_SEQ.incrementAndGet() + "_" + System.currentTimeMillis();
            List<String> dockerCommand = new ArrayList<>(dockerOptions);
            dockerCommand.add("--name");
            dockerCommand.add(containerName);
            DockerLabels.addRequestLabel(dockerCommand, requestId);
            dockerCommand.add(buildImage);
            // Run build script in login shell to ensure git-worktree is available and PATH updated
            dockerCommand.add("bash");
            dockerCommand.add("-lc");
            dockerCommand.add("/build.sh");
            logger.info("Running " + label + " [" + commitShort + "] (attempt " + attempt + ", container " + containerName + ")");
            // Don't log the full command to avoid exposing the env file path in logs

            ProcessBuilder pb = new ProcessBuilder(dockerCommand);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Reading the output blocks until the container exits, so the timeout is enforced by a
            // watchdog that stops the container (docker kill) and the client process.
            java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                try {
                    if (!process.waitFor(timeoutMinutes, java.util.concurrent.TimeUnit.MINUTES)) {
                        timedOut.set(true);
                        logger.severe(label + " [" + commitShort + "] exceeded build_timeout_minutes=" + timeoutMinutes + "; stopping " + containerName);
                        try {
                            new ProcessBuilder("docker", "kill", containerName).redirectErrorStream(true).start()
                                .waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (IOException ignore) {
                        }
                        process.destroyForcibly();
                    }
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
            }, "build-watchdog-" + containerName);
            watchdog.setDaemon(true);
            watchdog.start();

            List<String> phases = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 java.io.BufferedWriter logWriter = Files.newBufferedWriter(perBuildLog, java.nio.charset.StandardCharsets.UTF_8,
                     java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String phase = parsePhaseMarker(line);
                    if (phase != null) {
                        phases.add(phase);
                    }
                    logger.info("DOCKER[" + commitShort + "]: " + line);
                    try {
                        logWriter.write(line);
                        logWriter.newLine();
                    } catch (Exception ignore) {}
                }
                try { logWriter.flush(); } catch (Exception ignore) {}
            }

            int exitCode = process.waitFor();
            watchdog.interrupt();
            if (!phases.isEmpty()) {
                logPhaseSummary(commitShort, phases);
            }
            if (exitCode == 0 && !timedOut.get()) {
                return;
            }
            if (timedOut.get()) {
                // A hung download or build would hang again; do not retry.
                throw new IOException(label + " timed out after " + timeoutMinutes + " minutes (log: " + perBuildLog + ")");
            }
            if (requestId != null && CANCELLED_REQUESTS.contains(requestId)) {
                throw new IOException(label + " cancelled");
            }
            lastError = new IOException(label + " failed with exit code: " + exitCode + " (log: " + perBuildLog.toString() + ")");
            logger.warning(label + " [" + commitShort + "] attempt " + attempt + " failed (exit=" + exitCode + ")." +
                           (attempt < 2 ? " Retrying..." : " No more retries."));
            if (attempt < 2) {
                Thread.sleep(5000);
            }
        }
        throw lastError;
    }

    public String buildPullRequest(String prBranch, String headSha, File workDir, String buildType, String baselineCommit)
            throws IOException, InterruptedException {
        // No branch to gate on: no workspace reuse (see the buildCubrid overload)
        return buildPullRequest(prBranch, headSha, workDir, buildType, baselineCommit, false, null);
    }

    /** @param reuseWorkspace see {@link #buildCubrid}. Serialized with buildCubrid (shared workspace). */
    public synchronized String buildPullRequest(String prBranch, String headSha, File workDir, String buildType, String baselineCommit,
                                   boolean reuseWorkspace, String cubridBranch)
            throws IOException, InterruptedException {
        if (!dockerAvailable || !imageReady) {
            return buildPrDirect(prBranch, headSha, workDir, buildType, baselineCommit);
        }

        logger.info("Building PR branch " + prBranch + " (head " + headSha + ") in Docker container");
        final String commitShort = headSha.substring(0, Math.min(headSha.length(), 7));

        String githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.trim().isEmpty()) {
            throw new IOException("GITHUB_TOKEN environment variable is not set");
        }

        File hostRoot = new File(config.getDockerHostRoot());
        if (!hostRoot.exists()) {
            hostRoot.mkdirs();
        }
        File hostWorkDir = new File(hostRoot, "work");
        if (!hostWorkDir.exists()) {
            hostWorkDir.mkdirs();
        }
        File gradleCacheDir = new File(hostRoot, ".gradle");
        if (!gradleCacheDir.exists()) {
            gradleCacheDir.mkdirs();
        }

        File ccacheDir = null;
        if (config.isCcacheEnabled()) {
            ccacheDir = new File(hostWorkDir, ".ccache");
            if (!ccacheDir.exists()) {
                ccacheDir.mkdirs();
                logger.info("Created ccache directory under work mount: " + ccacheDir.getAbsolutePath());
            }
            try { new File(ccacheDir, "logs").mkdirs(); } catch (Exception ignore) {}
            try { new File(ccacheDir, "tmp").mkdirs(); } catch (Exception ignore) {}
        }

        File buildScript = createDockerPrBuildScript(prBranch, headSha, buildType, baselineCommit, workDir);

        List<String> baseDockerCmd = new ArrayList<>();
        baseDockerCmd.add("docker");
        baseDockerCmd.add("run");
        baseDockerCmd.add("--rm");
        baseDockerCmd.add("--network=host");
        baseDockerCmd.add("-v");
        baseDockerCmd.add(config.getCubridSrcDir() + ":/cubrid-src:ro");
        baseDockerCmd.add("-v");
        baseDockerCmd.add(workDir.getAbsolutePath() + ":/output:rw");
        baseDockerCmd.add("-v");
        baseDockerCmd.add(hostWorkDir.getAbsolutePath() + ":/work:rw");
        baseDockerCmd.add("-v");
        baseDockerCmd.add(gradleCacheDir.getAbsolutePath() + ":/root/.gradle:rw");
        baseDockerCmd.add("-v");
        baseDockerCmd.add(buildScript.getAbsolutePath() + ":/build.sh:ro");
        baseDockerCmd.add("-e");
        baseDockerCmd.add("COMMIT_HASH=" + headSha);
        baseDockerCmd.add("-e");
        baseDockerCmd.add("PR_BRANCH=" + prBranch);
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BUILD_TYPE=" + buildType);
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BASELINE_COMMIT=" + baselineCommit);

        if (config.isCcacheEnabled()) {
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CC=ccache gcc");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CXX=ccache g++");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_DIR=/work/.ccache");
            // ccache before 3.3 (3.1.6 in cubridci:develop) compiles the preprocessed output on a miss,
            // which has no comments: every /* fall through */ becomes an -Werror=implicit-fallthrough error.
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_CPP2=1");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_COMPILERCHECK=" + config.getCcacheCompilerCheck());
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_HARDLINK=" + (config.getCcacheHardlink() ? "1" : "0"));
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_MAXSIZE=" + config.getCcacheMaxSize());
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_BASEDIR=/tmp/cubrid-build");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_NOHASHDIR=1");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_LOGFILE=/work/.ccache/logs/ccache_" + commitShort + ".log");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_TEMPDIR=/work/.ccache/tmp");
            if (config.getCcacheReadonlyDirect()) {
                baseDockerCmd.add("-e");
                baseDockerCmd.add("CCACHE_READONLY_DIRECT=1");
            }
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_STATS=" + (config.getCcacheStatsEnabled() ? "true" : "false"));
            if (!config.getCcacheNamespace().isEmpty()) {
                baseDockerCmd.add("-e");
                baseDockerCmd.add("CCACHE_NAMESPACE=" + config.getCcacheNamespace());
            }
            if (!config.getCcacheSloppiness().isEmpty()) {
                baseDockerCmd.add("-e");
                baseDockerCmd.add("CCACHE_SLOPPINESS=" + config.getCcacheSloppiness());
            }
        }

        baseDockerCmd.add("-e");
        baseDockerCmd.add("MAKEFLAGS=-j" + config.getParallelJobs());
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BT_ACCEL=" + (config.isBuildAccelEnabled() ? "1" : "0"));
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BT_WORKSPACE_REUSE=" + (reuseWorkspace ? "1" : "0"));
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BT_BRANCH=" + (cubridBranch == null ? "" : cubridBranch));
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BT_IMAGE_ID=" + resolveBuildImageId());
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BT_WORKSPACE_REUSE_MAX=" + config.getBuildWorkspaceReuseMax());

        // Image and command are appended by runBuildContainer, after the per-attempt options

        Path perBuildLog = null;
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String buildsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "builds");
                perBuildLog = Paths.get(buildsDir, "build_" + commitShort + ".log");
            } else {
                Path buildsLogDir = Paths.get(System.getProperty("user.home"), "cubrid-testtools", "CTP", "builder_tester", "log", "builds");
                Files.createDirectories(buildsLogDir);
                perBuildLog = buildsLogDir.resolve("build_" + commitShort + "_" + System.currentTimeMillis() + ".log");
            }
        } catch (Exception e) {
            logger.warning("Failed to create build log directory: " + e.getMessage());
            perBuildLog = Paths.get(workDir.getAbsolutePath(), "build_" + commitShort + ".log");
        }

        // Create secure environment file for sensitive variables (GITHUB_TOKEN)
        // This prevents the token from appearing in ps -ef output
        try (SecureDockerEnv secureEnv = new SecureDockerEnv(workDir.toPath())) {
            secureEnv.add("GITHUB_TOKEN", githubToken);
            String envFilePath = secureEnv.getFilePath();

            // Must precede the image: anything after it is passed to the container's command.
            baseDockerCmd.add("--env-file");
            baseDockerCmd.add(envFilePath);
            runBuildContainer(baseDockerCmd, "Docker PR build", commitShort, perBuildLog);

            String packageName = "cubrid_" + headSha.substring(0, 7) + ".tar.gz";
            return new File(workDir, packageName).getAbsolutePath();
        } // SecureDockerEnv auto-closes here, removing the env file
    }

    private File createDockerPrBuildScript(String prBranch, String headSha, String buildType, String baselineCommit, File workDir)
            throws IOException {
        File script = new File(workDir, "docker_build_pr.sh");
        final String finalBuildArgs = normalizeBuildArg(config.getBuildArg(), buildType);

        try (PrintWriter writer = new PrintWriter(new FileWriter(script))) {
            writer.println("#!/bin/bash");
            writer.println("set -e");
            writer.println("set -o pipefail");
            writer.println();
            writeBuildAccelHelpers(writer);

            if (config.isCcacheEnabled()) {
                writer.println("# Configure ccache for faster builds");
                writer.println("if command -v ccache &> /dev/null; then");
                writer.println("  mkdir -p /work/.ccache/logs /work/.ccache/tmp || true");
                writer.println("  ccache -M ${CCACHE_MAXSIZE:-5G} || true");
                writer.println("  if [ -n \"$CCACHE_LOGFILE\" ]; then rm -f \"$CCACHE_LOGFILE\" || true; fi");
                writer.println("  ccache -z || true");
                writer.println("  echo 'Ccache status before build:'");
                writer.println("  ccache -s || true");
                writer.println("else");
                writer.println("  # CC=\"ccache gcc\" without ccache: cmake would find no compiler");
                writer.println("  echo '[WARN] ccache is not in the build image; compiling without it' >&2");
                writer.println("  export CC=gcc CXX=g++");
                writer.println("fi");
                writer.println();
            }

            writer.println("create_package() {");
            writer.println("  local dest=\"$1\"");
            writer.println("  echo \"Creating build package at $dest\"");
            writer.println("  if [ ! -d _install/CUBRID ]; then");
            writer.println("    echo '[WARN] Expected _install/CUBRID not found; packaging entire build directory' >&2");
            writer.println("    if command -v pigz >/dev/null 2>&1; then");
            writer.println("      tar cf - . | pigz -1 > \"$dest\"");
            writer.println("    else");
            writer.println("      tar cf - . | gzip -1 > \"$dest\"");
            writer.println("    fi");
            writer.println("    return");
            writer.println("  fi");
            writer.println("  if command -v pigz >/dev/null 2>&1; then");
            writer.println("    tar cf - _install/CUBRID | pigz -1 > \"$dest\"");
            writer.println("  else");
            writer.println("    tar cf - _install/CUBRID | gzip -1 > \"$dest\"");
            writer.println("  fi");
            writer.println("}");
            writer.println();

            writer.println("# Prepare an isolated working directory per build to avoid collisions");
            writer.println("# Use fixed build directory for ccache optimization");
            writer.println("if [ -d /work ]; then");
            writer.println("  target=/work/cubrid-build");
            writer.println("else");
            writer.println("  target=/tmp/cubrid-build");
            writer.println("fi");
            writer.println("bt_prepare_workspace \"$target\" \"${COMMIT_HASH}\"");
            writer.println("echo \"Using build workspace: $target\"");
            writer.println("cd \"$target\"");
            writer.println();
            writer.println("# Clone repository from host reference");
            writer.println("if [ \"${BT_WORKSPACE_REUSED:-0}\" = \"1\" ]; then");
            writer.println("  echo '[bt-accel] workspace: keeping the existing checkout'");
            writer.println("elif ! git clone --no-checkout --reference /cubrid-src --dissociate /cubrid-src repo; then");
            writer.println("  echo '[WARN] Reference clone failed; falling back to standard clone' >&2");
            writer.println("  rm -rf repo 2>/dev/null || true");
            writer.println("  git clone --no-checkout /cubrid-src repo");
            writer.println("fi");
            writer.println("cd repo");
            writer.println("git config advice.detachedHead false");
            writer.println("git config user.email build@localhost");
            writer.println("git config user.name Build Bot");
            writer.println();
            writer.println("GIT_VERSION_RAW=$(git --version 2>/dev/null)");
            writer.println("GIT_VERSION=$(echo \"$GIT_VERSION_RAW\" | awk '{print $3}')");
            writer.println("if [ -z \"$GIT_VERSION\" ]; then");
            writer.println("  GIT_MAJOR=0");
            writer.println("  GIT_MINOR=0");
            writer.println("  GIT_VERSION_DISPLAY=${GIT_VERSION_RAW:-unknown}");
            writer.println("else");
            writer.println("  GIT_MAJOR=${GIT_VERSION%%.*}");
            writer.println("  GIT_MINOR_TMP=${GIT_VERSION#*.}");
            writer.println("  GIT_MINOR=${GIT_MINOR_TMP%%.*}");
            writer.println("  GIT_VERSION_DISPLAY=$GIT_VERSION");
            writer.println("fi");
            writer.println("SUPPORTS_SUBMODULE_JOBS=0");
            writer.println("SUPPORTS_SUBMODULE_DEPTH=0");
            writer.println("if [ \"${GIT_MAJOR:-0}\" -gt 2 ] || { [ \"${GIT_MAJOR:-0}\" -eq 2 ] && [ \"${GIT_MINOR:-0}\" -ge 8 ]; }; then");
            writer.println("  SUPPORTS_SUBMODULE_JOBS=1");
            writer.println("fi");
            writer.println("if [ \"${GIT_MAJOR:-0}\" -gt 2 ] || { [ \"${GIT_MAJOR:-0}\" -eq 2 ] && [ \"${GIT_MINOR:-0}\" -ge 9 ]; }; then");
            writer.println("  SUPPORTS_SUBMODULE_DEPTH=1");
            writer.println("fi");
            writer.println("supports_submodule_jobs() {");
            writer.println("  [ \"$SUPPORTS_SUBMODULE_JOBS\" = \"1\" ]");
            writer.println("}");
            writer.println("supports_submodule_depth() {");
            writer.println("  [ \"$SUPPORTS_SUBMODULE_DEPTH\" = \"1\" ]");
            writer.println("}");
            writer.println("echo \"Detected git version: ${GIT_VERSION_DISPLAY}\"");
            writer.println();
            writer.println("# Try to fetch PR head from upstream to ensure commit object exists");
            writer.println("git remote add upstream https://github.com/CUBRID/cubrid.git 2>/dev/null || true");
            writer.println("tmp_branch=pr_${COMMIT_HASH:0:7}_tmp");
            writer.println("# Derive PR number from PR_BRANCH if available (extract digits)");
            writer.println("prn=''\nif [ -n \"${PR_NUMBER}\" ]; then prn=${PR_NUMBER}; elif [ -n \"${PR_BRANCH}\" ]; then prn=$(echo \"${PR_BRANCH}\" | sed 's/[^0-9]//g'); fi");
            writer.println("if [ -n \"$prn\" ]; then");
            writer.println("  echo \"Fetching PR #$prn from upstream\"");
            writer.println("  git fetch upstream +pull/$prn/head:$tmp_branch || git fetch origin +pull/$prn/head:$tmp_branch || true");
            writer.println("fi");
            writer.println("# Checkout PR head by commit hash as authoritative fallback");
            writer.println("git checkout -B \"$tmp_branch\" \"${COMMIT_HASH}\" || git checkout \"$tmp_branch\" || true");
            writer.println();
            writer.println("bt_phase clone");
            writer.println("git submodule sync --recursive");
            writer.println("bt_local_submodules");
            writer.println("SUBMODULE_JOBS=${SUBMODULE_JOBS:-$(nproc 2>/dev/null || echo 4)}");
            writer.println("SUBMODULE_ARGS=(--init --recursive --checkout --force)");
            writer.println("if [ \"$SUPPORTS_SUBMODULE_JOBS\" = \"1\" ]; then");
            writer.println("  SUBMODULE_ARGS+=(--jobs \"$SUBMODULE_JOBS\")");
            writer.println("else");
            writer.println("  echo \"[INFO] git submodule --jobs not supported in git ${GIT_VERSION_DISPLAY}\"");
            writer.println("fi");
            writer.println("if [ \"$SUPPORTS_SUBMODULE_DEPTH\" = \"1\" ]; then");
            writer.println("  SUBMODULE_ARGS+=(--depth 1)");
            writer.println("else");
            writer.println("  echo \"[INFO] git submodule --depth not supported in git ${GIT_VERSION_DISPLAY}\"");
            writer.println("fi");
            writer.println("if ! git submodule update \"${SUBMODULE_ARGS[@]}\"; then");
            writer.println("  echo '[WARN] Submodule update failed; restoring remote URLs and retrying' >&2");
            writer.println("  git submodule sync --recursive");
            writer.println("  git submodule update --init --recursive --checkout --force");
            writer.println("fi");
            writer.println("bt_phase submodules");
            writer.println();
            writer.println("git clean -xdf -e \"build_x86_64_*\"");
            writer.println("[ \"${BT_WORKSPACE_REUSED:-0}\" = \"1\" ] || rm -rf build_x86_64_*");
            writer.println("bt_clean_submodules");
            // One marker per build directory: release and debug directories both survive a reused
            // workspace, and a shared marker let one type's build vouch for the other's stale inputs.
            writer.println("bt_guard_build_inputs " + config.getBuildDir(buildType) + " \"$target/.bt_inputs." + config.getBuildDir(buildType) + "\"");
            writer.println("bt_reset_install " + config.getBuildDir(buildType));
            writer.println("mkdir -p " + config.getBuildDir(buildType));
            writer.println("bt_seed_jdk " + config.getBuildDir(buildType));
            writer.println("bt_seed_3rdparty " + config.getBuildDir(buildType));
            writer.println("bt_phase seed");
            writer.println();

            // Add git shim for deterministic version if ccache is enabled
            if (config.isCcacheEnabled()) {
                writer.println("# Create git shim for deterministic version (ccache optimization)");
                writer.println("GIT_SHIM_DIR=\"/tmp/git-shim\"");
                writer.println("mkdir -p \"$GIT_SHIM_DIR\"");
                writer.println("REAL_GIT_BIN=\"$(command -v git || true)\"");
                writer.println("if [ -z \"$REAL_GIT_BIN\" ]; then echo '[FATAL] git not found for shim'; exit 1; fi");
                writer.println("export REAL_GIT_BIN");
                writer.println("cat > \"$GIT_SHIM_DIR/git\" << 'GITSHIMEOF'");
                writer.println("#!/usr/bin/env bash");
                writer.println("# Intercept version queries and return deterministic values");
                writer.println("if [[ \"$1\" == \"rev-parse\" && \"$2\" == \"--short=7\" ]]; then");
                writer.println("  echo \"0000000\"");
                writer.println("elif [[ \"$1\" == \"rev-list\" ]]; then");
                writer.println("  echo \"0\"");
                writer.println("else");
                writer.println("  exec \"$REAL_GIT_BIN\" \"$@\"");
                writer.println("fi");
                writer.println("GITSHIMEOF");
                writer.println("chmod +x \"$GIT_SHIM_DIR/git\"");
                writer.println("export PATH=\"$GIT_SHIM_DIR:$PATH\"");
                writer.println("echo \"Git shim active for deterministic version\"");
                writer.println();
            }

            writePerlPrerequisite(writer);
            writer.println("# Build CUBRID");
            writeDockerBuildArgCompatibility(writer, finalBuildArgs);
            writer.println("if [ -f /opt/rh/devtoolset-8/enable ]; then");
            writer.println("  echo 'Using devtoolset-8 for build'");
            writer.println("  source /opt/rh/devtoolset-8/enable");
            writer.println("  ./build.sh \"${BUILD_ARGS[@]}\" || { echo '[FATAL] Build failed'; exit 1; }");
            writer.println("else");
            writer.println("  echo 'Building with default toolchain'");
            writer.println("  ./build.sh \"${BUILD_ARGS[@]}\" || { echo '[FATAL] Build failed'; exit 1; }");
            writer.println("fi");
            writer.println("bt_phase build");
            writer.println("bt_save_3rdparty " + config.getBuildDir(buildType));
            writer.println();

            // Cleanup git shim if it was created
            if (config.isCcacheEnabled()) {
                writer.println("# Cleanup git shim");
                writer.println("rm -rf \"$GIT_SHIM_DIR\" 2>/dev/null || true");
                writer.println();
            }

            if (config.isCcacheEnabled()) {
                writer.println("# Report ccache statistics after build");
                writer.println("if command -v ccache &> /dev/null; then");
                writer.println("  echo 'Ccache status after build:'");
                writer.println("  ccache -s || true");
                writer.println("  echo 'Ccache configuration (via environment):'");
                writer.println("  echo \\\"  CCACHE_DIR=$CCACHE_DIR\\\"");
                writer.println("  echo \\\"  CCACHE_MAXSIZE=$CCACHE_MAXSIZE\\\"");
                writer.println("  echo \\\"  CCACHE_HARDLINK=$CCACHE_HARDLINK\\\"");
                writer.println("  echo \\\"  CCACHE_COMPILERCHECK=$CCACHE_COMPILERCHECK\\\"");
                writer.println("  echo \\\"  CCACHE_LOGFILE=$CCACHE_LOGFILE\\\"");
                writer.println("fi");
                writer.println();
            }
            writer.println("# Create package");
            writer.println("cd " + config.getBuildDir(buildType));
            writer.println("create_package /output/cubrid_${COMMIT_HASH:0:7}.tar.gz");
            writer.println("bt_phase package");
            writer.println();
            writer.println("# Fix ownership of output files for host cleanup");
            writer.println("if [ -n \"$HOST_UID\" ] && [ -n \"$HOST_GID\" ]; then");
            writer.println("  chown -R \"$HOST_UID:$HOST_GID\" /output 2>/dev/null || true");
            writer.println("fi");
            writer.println();
            writer.println("# Cleanup");
            writer.println("cd \"$target/repo\"");
            writer.println("git checkout --detach || true");
            writer.println("git branch -D \"$tmp_branch\" || true");
            writer.println("cd /");
            writer.println("# Note: Not removing $target to preserve ccache-friendly paths");
            writer.println("bt_workspace_ok \"$target\"");
            writer.println("echo 'PR build completed successfully'");
        }

        script.setExecutable(true);
        return script;
    }

    private String buildPrDirect(String prBranch, String headSha, File workDir, String buildType, String baselineCommit)
            throws IOException, InterruptedException {
        logger.warning("Building PR directly on host");

        File repoRoot = new File(config.getCubridSrcDir());
        ProcessBuilder repoPb = new ProcessBuilder();
        repoPb.directory(repoRoot);
        try { executeCommand(repoPb, "git", "fetch", "--all", "--recurse-submodules=on-demand"); } catch (Exception ignore) {}

        String shortCommit = headSha.substring(0, Math.min(headSha.length(), 7));
        String tempBranch = "isolate_pr_" + shortCommit + "_tmp";
        File wtDir = new File(workDir, "wt_pr_" + shortCommit);
        try {
            executeCommand(repoPb, "git", "worktree", "add", "-b", tempBranch, wtDir.getAbsolutePath(), headSha);
        } catch (Exception e) {
            executeCommand(repoPb, "git", "branch", "-f", tempBranch, headSha);
            executeCommand(repoPb, "git", "worktree", "add", wtDir.getAbsolutePath(), tempBranch);
        }

        boolean success = false;
        try {
            ProcessBuilder wtPb = new ProcessBuilder();
            wtPb.directory(wtDir);

            executeCommand(wtPb, "git", "submodule", "sync", "--recursive");
            executeCommand(wtPb, "git", "submodule", "update", "--init", "--recursive", "--checkout", "--force");
            executeCommand(wtPb, "git", "clean", "-xdf");
            executeCommand(wtPb, "rm", "-rf", config.getBuildDir(buildType));

            if (config.isCcacheEnabled()) {
                try {
                    File logsDir = new File(config.getCcacheDir(), "logs");
                    if (!logsDir.exists()) { logsDir.mkdirs(); }
                    File tmpDir = new File(config.getCcacheDir(), "tmp");
                    if (!tmpDir.exists()) { tmpDir.mkdirs(); }
                } catch (Exception ignore) {}
                wtPb.environment().put("CC", "ccache gcc");
                wtPb.environment().put("CXX", "ccache g++");
                wtPb.environment().put("CCACHE_DIR", config.getCcacheDir());
                wtPb.environment().put("CCACHE_COMPILERCHECK", config.getCcacheCompilerCheck());
                wtPb.environment().put("CCACHE_HARDLINK", config.getCcacheHardlink() ? "1" : "0");
                wtPb.environment().put("CCACHE_MAXSIZE", config.getCcacheMaxSize());
                wtPb.environment().put("CCACHE_BASEDIR", new File(config.getWorkDir()).getAbsolutePath());
                wtPb.environment().put("CCACHE_NOHASHDIR", "1");
                File ccacheLogFile = new File(config.getCcacheDir(), "logs/ccache_" + shortCommit + ".log");
                if (ccacheLogFile.exists() && !ccacheLogFile.delete()) { /* ignore */ }
                wtPb.environment().put("CCACHE_LOGFILE", ccacheLogFile.getAbsolutePath());
                if (config.getCcacheReadonlyDirect()) wtPb.environment().put("CCACHE_READONLY_DIRECT", "1");
                wtPb.environment().put("CCACHE_STATS", config.getCcacheStatsEnabled() ? "true" : "false");
                if (!config.getCcacheNamespace().isEmpty()) wtPb.environment().put("CCACHE_NAMESPACE", config.getCcacheNamespace());
                if (!config.getCcacheSloppiness().isEmpty()) wtPb.environment().put("CCACHE_SLOPPINESS", config.getCcacheSloppiness());
                try { executeCommand(wtPb, "ccache", "-z"); } catch (Exception ignore) {}
                try { executeCommand(wtPb, "ccache", "-M", config.getCcacheMaxSize()); } catch (Exception ignore) {}
            }

            wtPb.environment().put("MAKEFLAGS", "-j" + config.getParallelJobs());
            java.util.List<String> buildCmd = new java.util.ArrayList<>();
            buildCmd.add("./build.sh");
            for (String token : resolveBuildArgsForScript(wtDir, config.getBuildArg(), buildType)) {
                buildCmd.add(token);
            }
            executeCommand(wtPb, buildCmd.toArray(new String[0]));

            String packageName = "cubrid_" + shortCommit + ".tar.gz";
            File packageFile = new File(workDir, packageName);
            ProcessBuilder tarPb = new ProcessBuilder();
            tarPb.directory(new File(wtDir, config.getBuildDir(buildType)));
            executeCommand(tarPb, "tar", "czf", packageFile.getAbsolutePath(), ".");
            success = true;
            return packageFile.getAbsolutePath();
        } finally {
            try { executeCommand(repoPb, "git", "worktree", "remove", "--force", wtDir.getAbsolutePath()); } catch (Exception e) { logger.warning("Failed to remove worktree " + wtDir.getAbsolutePath() + ": " + e.getMessage()); }
            try { executeCommand(repoPb, "git", "branch", "-D", tempBranch); } catch (Exception ignore) {}
            if (!success) { try { deleteRecursively(wtDir); } catch (Exception ignore) {} }
        }
    }
    
    private File createDockerBuildScript(String commitHash, String buildType, String baselineCommit, File workDir) 
            throws IOException {
        File script = new File(workDir, "docker_build.sh");
        // Normalize build args to honor requested buildType (debug/release)
        final String finalBuildArgs = normalizeBuildArg(config.getBuildArg(), buildType);
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(script))) {
            writer.println("#!/bin/bash");
            writer.println("set -e");
            // A failing `tar | pigz` must fail the build, not ship a truncated package as success
            writer.println("set -o pipefail");
            writer.println();
            writeBuildAccelHelpers(writer);
            
            // Setup ccache if enabled
            if (config.isCcacheEnabled()) {
                writer.println("# Configure ccache for faster builds (compatible with older ccache)");
                writer.println("if command -v ccache &> /dev/null; then");
                writer.println("  mkdir -p /work/.ccache/logs /work/.ccache/tmp || true");
                writer.println("  # Set max size (use -M for compatibility)");
                writer.println("  ccache -M ${CCACHE_MAXSIZE:-5G} || true");
                writer.println("  # Reset statistics for per-build reporting and truncate log");
                writer.println("  if [ -n \"$CCACHE_LOGFILE\" ]; then rm -f \"$CCACHE_LOGFILE\" || true; fi");
                writer.println("  ccache -z || true");
                writer.println("  # hard_link is controlled via CCACHE_HARDLINK env; avoid unsupported ccache flags on older versions");
                writer.println("  echo 'Ccache status before build:'");
                writer.println("  ccache -s || true");
                writer.println("else");
                writer.println("  # CC=\"ccache gcc\" without ccache: cmake would find no compiler");
                writer.println("  echo '[WARN] ccache is not in the build image; compiling without it' >&2");
                writer.println("  export CC=gcc CXX=g++");
                writer.println("fi");
                writer.println();
            }

            // Reverted path normalization flags (keep environment unchanged)

            writer.println("create_package() {");
            writer.println("  local dest=\"$1\"");
            writer.println("  echo \"Creating build package at $dest\"");
            writer.println("  if [ ! -d _install/CUBRID ]; then");
            writer.println("    echo '[WARN] Expected _install/CUBRID not found; packaging entire build directory' >&2");
            writer.println("    if command -v pigz >/dev/null 2>&1; then");
            writer.println("      tar cf - . | pigz -1 > \"$dest\"");
            writer.println("    else");
            writer.println("      tar cf - . | gzip -1 > \"$dest\"");
            writer.println("    fi");
            writer.println("    return");
            writer.println("  fi");
            writer.println("  if command -v pigz >/dev/null 2>&1; then");
            writer.println("    tar cf - _install/CUBRID | pigz -1 > \"$dest\"");
            writer.println("  else");
            writer.println("    tar cf - _install/CUBRID | gzip -1 > \"$dest\"");
            writer.println("  fi");
            writer.println("}");
            writer.println();
            
            writer.println("# Prepare an isolated working directory per build to avoid collisions");
            writer.println("# Use fixed build directory for ccache optimization");
            writer.println("if [ -d /work ]; then");
            writer.println("  target=/work/cubrid-build");
            writer.println("else");
            writer.println("  target=/tmp/cubrid-build");
            writer.println("fi");
            writer.println("bt_prepare_workspace \"$target\" \"${COMMIT_HASH}\"");
            writer.println("echo \"Using build workspace: $target\"");
            writer.println("cd \"$target\"");
            writer.println();
            writer.println("# Clone source into writable target using local reference to avoid network fetches");
            writer.println("if [ \"${BT_WORKSPACE_REUSED:-0}\" = \"1\" ]; then");
            writer.println("  echo '[bt-accel] workspace: keeping the existing checkout'");
            writer.println("elif ! git clone --no-checkout --reference /cubrid-src /cubrid-src repo; then");
            writer.println("  echo '[WARN] Reference clone failed; falling back to standard clone' >&2");
            writer.println("  rm -rf repo 2>/dev/null || true");
            writer.println("  git clone --no-checkout /cubrid-src repo");
            writer.println("fi");
            writer.println("cd repo");
            writer.println("git config advice.detachedHead false");
            writer.println("git config user.email build@localhost");
            writer.println("git config user.name Build Bot");
            writer.println();
            writer.println("# Checkout baseline on a temporary branch and cherry-pick the commit (no worktree required)");
            writer.println("tmp_branch=isolate_${COMMIT_HASH:0:7}_tmp");
            writer.println("git checkout -B \"$tmp_branch\" \"${BASELINE_COMMIT}\"");
            writer.println();
            writer.println("# Store the current repo path for patch creation");
            writer.println("current_repo=\"$target/repo\"");
            writer.println();
            writer.println("# Function to apply commit with fallback");
            writer.println("apply_commit() {");
            writer.println("  local commit_hash=$1");
            writer.println("  local is_merge=$2");
            writer.println("  ");
            writer.println("  # First attempt: cherry-pick");
            writer.println("  if [ \"$is_merge\" = \"true\" ]; then");
            writer.println("    if git cherry-pick -m 1 -x \"$commit_hash\" 2>/dev/null; then");
            writer.println("      echo 'Cherry-pick succeeded'");
            writer.println("      return 0");
            writer.println("    else");
            writer.println("      git cherry-pick --abort 2>/dev/null || true");
            writer.println("    fi");
            writer.println("  else");
            writer.println("    if git cherry-pick -x \"$commit_hash\" 2>/dev/null; then");
            writer.println("      echo 'Cherry-pick succeeded'");
            writer.println("      return 0");
            writer.println("    else");
            writer.println("      git cherry-pick --abort 2>/dev/null || true");
            writer.println("    fi");
            writer.println("  fi");
            writer.println("  ");
            writer.println("  # Fallback: format-patch and apply");
            writer.println("  echo 'Cherry-pick failed, attempting format-patch fallback'");
            writer.println("  ");
            writer.println("  # Create patch in the current directory");
            writer.println("  if [ \"$is_merge\" = \"true\" ]; then");
            writer.println("    git format-patch -1 --stdout -m --first-parent \"$commit_hash\" > /tmp/commit.patch");
            writer.println("  else");
            writer.println("    git format-patch -1 --stdout \"$commit_hash\" > /tmp/commit.patch");
            writer.println("  fi");
            writer.println("  ");
            writer.println("  # Apply patch");
            writer.println("  if git apply --3way /tmp/commit.patch; then");
            writer.println("    # Get commit message and commit");
            writer.println("    commit_msg=$(git log --format=%B -n 1 \"$commit_hash\")");
            writer.println("    git add -A");
            writer.println("    git commit -m \"$commit_msg\"");
            writer.println("    rm -f /tmp/commit.patch 2>/dev/null || true");
            writer.println("    echo 'Format-patch fallback succeeded'");
            writer.println("    return 0");
            writer.println("  else");
            writer.println("    rm -f /tmp/commit.patch 2>/dev/null || true");
            writer.println("    git reset --hard HEAD 2>/dev/null || true");
            // No checkout fallback: checking out the commit builds its whole ancestry, which is not
            // "baseline + this commit" yet would be labelled, cached and reported as such.
            writer.println("    echo 'Both cherry-pick and format-patch failed: the commit does not apply on the baseline'");
            writer.println("    return 1");
            writer.println("  fi");
            writer.println("}");
            writer.println();
            writer.println("# Optionally skip applying commit (baseline-only warm)");
            writer.println("if [ \"${BUILD_BASELINE_ONLY:-0}\" = \"1\" ]; then");
            writer.println("  echo 'BUILD_BASELINE_ONLY=1: building baseline without applying commit'");
            writer.println("else");
            writer.println("  # Determine if it's a merge commit and apply");
            writer.println("  if git rev-list --parents -n1 \"${COMMIT_HASH}\" | awk '{exit (NF>2)?0:1}'; then");
            writer.println("    apply_commit \"${COMMIT_HASH}\" true || { echo '[FATAL] Failed to apply commit'; exit 1; }");
            writer.println("  else");
            writer.println("    apply_commit \"${COMMIT_HASH}\" false || { echo '[FATAL] Failed to apply commit'; exit 1; }");
            writer.println("  fi");
            writer.println("fi");
            writer.println("GIT_VERSION_RAW=$(git --version 2>/dev/null)");
            writer.println("GIT_VERSION=$(echo \"$GIT_VERSION_RAW\" | awk '{print $3}')");
            writer.println("if [ -z \"$GIT_VERSION\" ]; then");
            writer.println("  GIT_MAJOR=0");
            writer.println("  GIT_MINOR=0");
            writer.println("  GIT_VERSION_DISPLAY=${GIT_VERSION_RAW:-unknown}");
            writer.println("else");
            writer.println("  GIT_MAJOR=${GIT_VERSION%%.*}");
            writer.println("  GIT_MINOR_TMP=${GIT_VERSION#*.}");
            writer.println("  GIT_MINOR=${GIT_MINOR_TMP%%.*}");
            writer.println("  GIT_VERSION_DISPLAY=$GIT_VERSION");
            writer.println("fi");
            writer.println("SUPPORTS_SUBMODULE_JOBS=0");
            writer.println("SUPPORTS_SUBMODULE_DEPTH=0");
            writer.println("if [ \"${GIT_MAJOR:-0}\" -gt 2 ] || { [ \"${GIT_MAJOR:-0}\" -eq 2 ] && [ \"${GIT_MINOR:-0}\" -ge 8 ]; }; then");
            writer.println("  SUPPORTS_SUBMODULE_JOBS=1");
            writer.println("fi");
            writer.println("if [ \"${GIT_MAJOR:-0}\" -gt 2 ] || { [ \"${GIT_MAJOR:-0}\" -eq 2 ] && [ \"${GIT_MINOR:-0}\" -ge 9 ]; }; then");
            writer.println("  SUPPORTS_SUBMODULE_DEPTH=1");
            writer.println("fi");
            writer.println("supports_submodule_jobs() {");
            writer.println("  [ \"$SUPPORTS_SUBMODULE_JOBS\" = \"1\" ]");
            writer.println("}");
            writer.println("supports_submodule_depth() {");
            writer.println("  [ \"$SUPPORTS_SUBMODULE_DEPTH\" = \"1\" ]");
            writer.println("}");
            writer.println("echo \"Detected git version: ${GIT_VERSION_DISPLAY}\"");
            writer.println("bt_phase clone");
            writer.println("git submodule sync --recursive");
            writer.println("bt_local_submodules");
            writer.println("SUBMODULE_JOBS=${SUBMODULE_JOBS:-$(nproc 2>/dev/null || echo 4)}");
            writer.println("SUBMODULE_ARGS=(--init --recursive --checkout --force)");
            writer.println("if [ \"$SUPPORTS_SUBMODULE_JOBS\" = \"1\" ]; then");
            writer.println("  SUBMODULE_ARGS+=(--jobs \"$SUBMODULE_JOBS\")");
            writer.println("else");
            writer.println("  echo \"[INFO] git submodule --jobs not supported in git ${GIT_VERSION_DISPLAY}\"");
            writer.println("fi");
            writer.println("if [ \"$SUPPORTS_SUBMODULE_DEPTH\" = \"1\" ]; then");
            writer.println("  SUBMODULE_ARGS+=(--depth 1)");
            writer.println("else");
            writer.println("  echo \"[INFO] git submodule --depth not supported in git ${GIT_VERSION_DISPLAY}\"");
            writer.println("fi");
            writer.println("if ! git submodule update \"${SUBMODULE_ARGS[@]}\"; then");
            writer.println("  echo '[WARN] Submodule update failed; restoring remote URLs and retrying' >&2");
            writer.println("  git submodule sync --recursive");
            writer.println("  git submodule update --init --recursive --checkout --force");
            writer.println("fi");
            writer.println("bt_phase submodules");
            writer.println();

            writer.println("# Clean any previous builds");
            writer.println("git clean -xdf -e \"build_x86_64_*\"");
            writer.println("[ \"${BT_WORKSPACE_REUSED:-0}\" = \"1\" ] || rm -rf build_x86_64_*");
            writer.println("bt_clean_submodules");
            // One marker per build directory: release and debug directories both survive a reused
            // workspace, and a shared marker let one type's build vouch for the other's stale inputs.
            writer.println("bt_guard_build_inputs " + config.getBuildDir(buildType) + " \"$target/.bt_inputs." + config.getBuildDir(buildType) + "\"");
            writer.println("bt_reset_install " + config.getBuildDir(buildType));
            writer.println("mkdir -p " + config.getBuildDir(buildType));
            writer.println("bt_seed_jdk " + config.getBuildDir(buildType));
            writer.println("bt_seed_3rdparty " + config.getBuildDir(buildType));
            writer.println("bt_phase seed");
            writer.println();

            // Add git shim for deterministic version if ccache is enabled
            if (config.isCcacheEnabled()) {
                writer.println("# Create git shim for deterministic version (ccache optimization)");
                writer.println("GIT_SHIM_DIR=\"/tmp/git-shim\"");
                writer.println("mkdir -p \"$GIT_SHIM_DIR\"");
                writer.println("REAL_GIT_BIN=\"$(command -v git || true)\"");
                writer.println("if [ -z \"$REAL_GIT_BIN\" ]; then echo '[FATAL] git not found for shim'; exit 1; fi");
                writer.println("export REAL_GIT_BIN");
                writer.println("cat > \"$GIT_SHIM_DIR/git\" << 'GITSHIMEOF'");
                writer.println("#!/usr/bin/env bash");
                writer.println("# Intercept version queries and return deterministic values");
                writer.println("if [[ \"$1\" == \"rev-parse\" && \"$2\" == \"--short=7\" ]]; then");
                writer.println("  echo \"0000000\"");
                writer.println("elif [[ \"$1\" == \"rev-list\" ]]; then");
                writer.println("  echo \"0\"");
                writer.println("else");
                writer.println("  exec \"$REAL_GIT_BIN\" \"$@\"");
                writer.println("fi");
                writer.println("GITSHIMEOF");
                writer.println("chmod +x \"$GIT_SHIM_DIR/git\"");
                writer.println("export PATH=\"$GIT_SHIM_DIR:$PATH\"");
                writer.println("echo \"Git shim active for deterministic version\"");
                writer.println();
            }

            writePerlPrerequisite(writer);
            writer.println("# Build CUBRID");
            writeDockerBuildArgCompatibility(writer, finalBuildArgs);

            // Check if devtoolset-8 is available and use it
            writer.println("# Try to use devtoolset-8 if available");
            writer.println("if [ -f /opt/rh/devtoolset-8/enable ]; then");
            writer.println("  echo 'Using devtoolset-8 for build'");
            writer.println("  source /opt/rh/devtoolset-8/enable");
            writer.println("  ./build.sh \"${BUILD_ARGS[@]}\" || { echo '[FATAL] Build failed'; exit 1; }");
            writer.println("else");
            writer.println("  echo 'Building with default toolchain'");
            writer.println("  ./build.sh \"${BUILD_ARGS[@]}\" || { echo '[FATAL] Build failed'; exit 1; }");
            writer.println("fi");
            writer.println("bt_phase build");
            writer.println("bt_save_3rdparty " + config.getBuildDir(buildType));
            writer.println();

            // Cleanup git shim if it was created
            if (config.isCcacheEnabled()) {
                writer.println("# Cleanup git shim");
                writer.println("rm -rf \"$GIT_SHIM_DIR\" 2>/dev/null || true");
                writer.println();
            }

            // Report ccache statistics after build
            if (config.isCcacheEnabled()) {
                writer.println("# Report ccache statistics after build");
                writer.println("if command -v ccache &> /dev/null; then");
                writer.println("  echo 'Ccache status after build:'");
                writer.println("  ccache -s || true");
                writer.println("  echo 'Ccache configuration (via environment):'");
                writer.println("  echo \\\"  CCACHE_DIR=$CCACHE_DIR\\\"");
                writer.println("  echo \\\"  CCACHE_MAXSIZE=$CCACHE_MAXSIZE\\\"");
                writer.println("  echo \\\"  CCACHE_HARDLINK=$CCACHE_HARDLINK\\\"");
                writer.println("  echo \\\"  CCACHE_COMPILERCHECK=$CCACHE_COMPILERCHECK\\\"");
                writer.println("  echo \\\"  CCACHE_LOGFILE=$CCACHE_LOGFILE\\\"");
                writer.println("fi");
                writer.println();
            }
            
            writer.println("# Create package (can be skipped during baseline warm)");
            writer.println("cd " + config.getBuildDir(buildType));
            writer.println("if [ \"${BUILD_SKIP_PACKAGE:-0}\" != \"1\" ]; then");
            writer.println("  create_package /output/cubrid_${COMMIT_HASH:0:7}.tar.gz");
            writer.println("  bt_phase package");
            writer.println("fi");
            writer.println();
            writer.println("# Fix ownership of output files for host cleanup");
            writer.println("if [ -n \"$HOST_UID\" ] && [ -n \"$HOST_GID\" ]; then");
            writer.println("  chown -R \"$HOST_UID:$HOST_GID\" /output 2>/dev/null || true");
            writer.println("fi");
            writer.println();
            writer.println("# Cleanup temporary branch and workspace");
            writer.println("cd \"$target/repo\"");
            writer.println("git checkout --detach || true");
            writer.println("git branch -D \"$tmp_branch\" || true");
            writer.println("cd /");
            writer.println("# Note: Not removing $target to preserve ccache-friendly paths");
            writer.println("bt_workspace_ok \"$target\"");
            writer.println("echo \"Build completed successfully\"");
        }
        
        script.setExecutable(true);
        return script;
    }

    private File createDockerCheckoutBuildScript(String commitHash, String buildType, File workDir)
            throws IOException {
        File script = new File(workDir, "docker_build_checkout.sh");
        final String finalBuildArgs = normalizeBuildArg(config.getBuildArg(), buildType);

        try (PrintWriter writer = new PrintWriter(new FileWriter(script))) {
            writer.println("#!/bin/bash");
            writer.println("set -e");
            // A failing `tar | pigz` must fail the build, not ship a truncated package as success
            writer.println("set -o pipefail");
            writer.println();
            writeBuildAccelHelpers(writer);

            if (config.isCcacheEnabled()) {
                writer.println("# Configure ccache for faster builds (compatible with older ccache)");
                writer.println("if command -v ccache &> /dev/null; then");
                writer.println("  mkdir -p /work/.ccache/logs /work/.ccache/tmp || true");
                writer.println("  ccache -M ${CCACHE_MAXSIZE:-5G} || true");
                writer.println("  if [ -n \"$CCACHE_LOGFILE\" ]; then rm -f \"$CCACHE_LOGFILE\" || true; fi");
                writer.println("  ccache -z || true");
                writer.println("  echo 'Ccache status before build:'");
                writer.println("  ccache -s || true");
                writer.println("else");
                writer.println("  # CC=\"ccache gcc\" without ccache: cmake would find no compiler");
                writer.println("  echo '[WARN] ccache is not in the build image; compiling without it' >&2");
                writer.println("  export CC=gcc CXX=g++");
                writer.println("fi");
                writer.println();
            }

            writer.println("create_package() {");
            writer.println("  local dest=\"$1\"");
            writer.println("  echo \"Creating build package at $dest\"");
            writer.println("  if [ ! -d _install/CUBRID ]; then");
            writer.println("    echo '[WARN] Expected _install/CUBRID not found; packaging entire build directory' >&2");
            writer.println("    if command -v pigz >/dev/null 2>&1; then");
            writer.println("      tar cf - . | pigz -1 > \"$dest\"");
            writer.println("    else");
            writer.println("      tar cf - . | gzip -1 > \"$dest\"");
            writer.println("    fi");
            writer.println("    return");
            writer.println("  fi");
            writer.println("  if command -v pigz >/dev/null 2>&1; then");
            writer.println("    tar cf - _install/CUBRID | pigz -1 > \"$dest\"");
            writer.println("  else");
            writer.println("    tar cf - _install/CUBRID | gzip -1 > \"$dest\"");
            writer.println("  fi");
            writer.println("}");
            writer.println();

            writer.println("# Prepare an isolated working directory per build to avoid collisions");
            writer.println("# Use fixed build directory for ccache optimization");
            writer.println("if [ -d /work ]; then");
            writer.println("  target=/work/cubrid-build");
            writer.println("else");
            writer.println("  target=/tmp/cubrid-build");
            writer.println("fi");
            writer.println("bt_prepare_workspace \"$target\" \"${COMMIT_HASH}\"");
            writer.println("echo \"Using build workspace: $target\"");
            writer.println("cd \"$target\"");
            writer.println();
            writer.println("# Clone source into writable target using local reference to avoid network fetches");
            writer.println("if [ \"${BT_WORKSPACE_REUSED:-0}\" = \"1\" ]; then");
            writer.println("  echo '[bt-accel] workspace: keeping the existing checkout'");
            writer.println("elif ! git clone --no-checkout --reference /cubrid-src /cubrid-src repo; then");
            writer.println("  echo '[WARN] Reference clone failed; falling back to standard clone' >&2");
            writer.println("  rm -rf repo 2>/dev/null || true");
            writer.println("  git clone --no-checkout /cubrid-src repo");
            writer.println("fi");
            writer.println("cd repo");
            writer.println("git config advice.detachedHead false");
            writer.println("git config user.email build@localhost");
            writer.println("git config user.name Build Bot");
            writer.println();
            writer.println("# Checkout the requested commit directly");
            writer.println("git checkout --detach \"${COMMIT_HASH}\"");
            writer.println();

            writer.println("GIT_VERSION_RAW=$(git --version 2>/dev/null)");
            writer.println("GIT_VERSION=$(echo \"$GIT_VERSION_RAW\" | awk '{print $3}')");
            writer.println("if [ -z \"$GIT_VERSION\" ]; then");
            writer.println("  GIT_MAJOR=0");
            writer.println("  GIT_MINOR=0");
            writer.println("  GIT_VERSION_DISPLAY=${GIT_VERSION_RAW:-unknown}");
            writer.println("else");
            writer.println("  GIT_MAJOR=${GIT_VERSION%%.*}");
            writer.println("  GIT_MINOR_TMP=${GIT_VERSION#*.}");
            writer.println("  GIT_MINOR=${GIT_MINOR_TMP%%.*}");
            writer.println("  GIT_VERSION_DISPLAY=$GIT_VERSION");
            writer.println("fi");
            writer.println("SUPPORTS_SUBMODULE_JOBS=0");
            writer.println("SUPPORTS_SUBMODULE_DEPTH=0");
            writer.println("if [ \"${GIT_MAJOR:-0}\" -gt 2 ] || { [ \"${GIT_MAJOR:-0}\" -eq 2 ] && [ \"${GIT_MINOR:-0}\" -ge 8 ]; }; then");
            writer.println("  SUPPORTS_SUBMODULE_JOBS=1");
            writer.println("fi");
            writer.println("if [ \"${GIT_MAJOR:-0}\" -gt 2 ] || { [ \"${GIT_MAJOR:-0}\" -eq 2 ] && [ \"${GIT_MINOR:-0}\" -ge 9 ]; }; then");
            writer.println("  SUPPORTS_SUBMODULE_DEPTH=1");
            writer.println("fi");
            writer.println("echo \"Detected git version: ${GIT_VERSION_DISPLAY}\"");
            writer.println();
            writer.println("bt_phase clone");
            writer.println("git submodule sync --recursive");
            writer.println("bt_local_submodules");
            writer.println("SUBMODULE_JOBS=${SUBMODULE_JOBS:-$(nproc 2>/dev/null || echo 4)}");
            writer.println("SUBMODULE_ARGS=(--init --recursive --checkout --force)");
            writer.println("if [ \"$SUPPORTS_SUBMODULE_JOBS\" = \"1\" ]; then");
            writer.println("  SUBMODULE_ARGS+=(--jobs \"$SUBMODULE_JOBS\")");
            writer.println("else");
            writer.println("  echo \"[INFO] git submodule --jobs not supported in git ${GIT_VERSION_DISPLAY}\"");
            writer.println("fi");
            writer.println("if [ \"$SUPPORTS_SUBMODULE_DEPTH\" = \"1\" ]; then");
            writer.println("  SUBMODULE_ARGS+=(--depth 1)");
            writer.println("else");
            writer.println("  echo \"[INFO] git submodule --depth not supported in git ${GIT_VERSION_DISPLAY}\"");
            writer.println("fi");
            writer.println("if ! git submodule update \"${SUBMODULE_ARGS[@]}\"; then");
            writer.println("  echo '[WARN] Submodule update failed; restoring remote URLs and retrying' >&2");
            writer.println("  git submodule sync --recursive");
            writer.println("  git submodule update --init --recursive --checkout --force");
            writer.println("fi");
            writer.println("bt_phase submodules");
            writer.println();
            writer.println("git clean -xdf -e \"build_x86_64_*\"");
            writer.println("[ \"${BT_WORKSPACE_REUSED:-0}\" = \"1\" ] || rm -rf build_x86_64_*");
            writer.println("bt_clean_submodules");
            // One marker per build directory: release and debug directories both survive a reused
            // workspace, and a shared marker let one type's build vouch for the other's stale inputs.
            writer.println("bt_guard_build_inputs " + config.getBuildDir(buildType) + " \"$target/.bt_inputs." + config.getBuildDir(buildType) + "\"");
            writer.println("bt_reset_install " + config.getBuildDir(buildType));
            writer.println("mkdir -p " + config.getBuildDir(buildType));
            writer.println("bt_seed_jdk " + config.getBuildDir(buildType));
            writer.println("bt_seed_3rdparty " + config.getBuildDir(buildType));
            writer.println("bt_phase seed");
            writer.println();

            if (config.isCcacheEnabled()) {
                writer.println("# Create git shim for deterministic version (ccache optimization)");
                writer.println("GIT_SHIM_DIR=\"/tmp/git-shim\"");
                writer.println("mkdir -p \"$GIT_SHIM_DIR\"");
                writer.println("REAL_GIT_BIN=\"$(command -v git || true)\"");
                writer.println("if [ -z \"$REAL_GIT_BIN\" ]; then echo '[FATAL] git not found for shim'; exit 1; fi");
                writer.println("export REAL_GIT_BIN");
                writer.println("cat > \"$GIT_SHIM_DIR/git\" << 'GITSHIMEOF'");
                writer.println("#!/usr/bin/env bash");
                writer.println("if [[ \"$1\" == \"rev-parse\" && \"$2\" == \"--short=7\" ]]; then");
                writer.println("  echo \"0000000\"");
                writer.println("elif [[ \"$1\" == \"rev-list\" ]]; then");
                writer.println("  echo \"0\"");
                writer.println("else");
                writer.println("  exec \"$REAL_GIT_BIN\" \"$@\"");
                writer.println("fi");
                writer.println("GITSHIMEOF");
                writer.println("chmod +x \"$GIT_SHIM_DIR/git\"");
                writer.println("export PATH=\"$GIT_SHIM_DIR:$PATH\"");
                writer.println("echo \"Git shim active for deterministic version\"");
                writer.println();
            }

            writePerlPrerequisite(writer);
            writer.println("# Build CUBRID");
            writeDockerBuildArgCompatibility(writer, finalBuildArgs);
            writer.println("if [ -f /opt/rh/devtoolset-8/enable ]; then");
            writer.println("  echo 'Using devtoolset-8 for build'");
            writer.println("  source /opt/rh/devtoolset-8/enable");
            writer.println("  ./build.sh \"${BUILD_ARGS[@]}\" || { echo '[FATAL] Build failed'; exit 1; }");
            writer.println("else");
            writer.println("  echo 'Building with default toolchain'");
            writer.println("  ./build.sh \"${BUILD_ARGS[@]}\" || { echo '[FATAL] Build failed'; exit 1; }");
            writer.println("fi");
            writer.println("bt_phase build");
            writer.println("bt_save_3rdparty " + config.getBuildDir(buildType));
            writer.println();

            if (config.isCcacheEnabled()) {
                writer.println("# Cleanup git shim");
                writer.println("rm -rf \"$GIT_SHIM_DIR\" 2>/dev/null || true");
                writer.println();
            }

            if (config.isCcacheEnabled()) {
                writer.println("# Report ccache statistics after build");
                writer.println("if command -v ccache &> /dev/null; then");
                writer.println("  echo 'Ccache status after build:'");
                writer.println("  ccache -s || true");
                writer.println("  echo 'Ccache configuration (via environment):'");
                writer.println("  echo \\\"  CCACHE_DIR=$CCACHE_DIR\\\"");
                writer.println("  echo \\\"  CCACHE_MAXSIZE=$CCACHE_MAXSIZE\\\"");
                writer.println("  echo \\\"  CCACHE_HARDLINK=$CCACHE_HARDLINK\\\"");
                writer.println("  echo \\\"  CCACHE_COMPILERCHECK=$CCACHE_COMPILERCHECK\\\"");
                writer.println("  echo \\\"  CCACHE_LOGFILE=$CCACHE_LOGFILE\\\"");
                writer.println("fi");
                writer.println();
            }

            writer.println("# Create package");
            writer.println("cd " + config.getBuildDir(buildType));
            writer.println("create_package /output/cubrid_${COMMIT_HASH:0:7}.tar.gz");
            writer.println("bt_phase package");
            writer.println();
            writer.println("# Fix ownership of output files for host cleanup");
            writer.println("if [ -n \"$HOST_UID\" ] && [ -n \"$HOST_GID\" ]; then");
            writer.println("  chown -R \"$HOST_UID:$HOST_GID\" /output 2>/dev/null || true");
            writer.println("fi");
            writer.println();
            writer.println("bt_workspace_ok \"$target\"");
            writer.println("echo \"Build completed successfully\"");
        }

        script.setExecutable(true);
        return script;
    }
    
    private String buildCubridDirect(String commitHash, File workDir, String buildType, String baselineCommit,
                                     String commitBuildMode) throws IOException, InterruptedException {
        if ("checkout".equalsIgnoreCase(commitBuildMode)) {
            return buildCubridDirectCheckout(commitHash, workDir, buildType);
        }

        logger.warning("Building CUBRID directly on host");
        
        // Use the same isolated worktree + cherry-pick strategy on host
        File repoRoot = new File(config.getCubridSrcDir());
        ProcessBuilder repoPb = new ProcessBuilder();
        repoPb.directory(repoRoot);
        try {
            executeCommand(repoPb, "git", "fetch", "--all", "--recurse-submodules=on-demand");
        } catch (Exception ignore) {}
        if (baselineCommit == null || baselineCommit.trim().isEmpty()) {
            baselineCommit = executeAndGet(repoPb, "git", "rev-parse", commitHash + "^").trim();
        }
        File wtDir = new File(workDir, "wt_" + commitHash.substring(0, 7));
        executeCommand(repoPb, "git", "worktree", "add", "--detach", wtDir.getAbsolutePath(), baselineCommit);

        boolean ok = false;
        try {
            ProcessBuilder wtPb = new ProcessBuilder();
            wtPb.directory(wtDir);
            // Detect merge commit
            String parents = executeAndGet(repoPb, "git", "rev-list", "--parents", "-n1", commitHash).trim();
            boolean isMerge = parents.split("\\s+").length > 2;
            
            boolean cherryPickSucceeded = false;
            Exception cherryPickException = null;
            
            {
                // First attempt: try cherry-pick
                if (isMerge) {
                    try { 
                        executeCommand(wtPb, "git", "cherry-pick", "-m", "1", "-x", commitHash);
                        cherryPickSucceeded = true;
                    } catch (Exception e) { 
                        cherryPickException = e;
                        try { executeCommand(wtPb, "git", "cherry-pick", "--abort"); } catch (Exception ignore) {} 
                    }
                } else {
                    try { 
                        executeCommand(wtPb, "git", "cherry-pick", "-x", commitHash);
                        cherryPickSucceeded = true;
                    } catch (Exception e) { 
                        cherryPickException = e;
                        try { executeCommand(wtPb, "git", "cherry-pick", "--abort"); } catch (Exception ignore) {} 
                    }
                }
            }
            
            // Fallback: try format-patch and apply if cherry-pick failed
            if (!cherryPickSucceeded) {
                logger.warning("Cherry-pick failed for " + commitHash + ", attempting format-patch fallback");
                try {
                    // Create a patch file from the commit
                    File patchFile = new File(workDir, commitHash + ".patch");
                    ProcessBuilder patchPb = new ProcessBuilder();
                    patchPb.directory(repoRoot);
                    
                    if (isMerge) {
                        // For merge commits, create a diff against first parent
                        patchPb.command("git", "format-patch", "-1", "--stdout", "-m", "--first-parent", commitHash);
                    } else {
                        patchPb.command("git", "format-patch", "-1", "--stdout", commitHash);
                    }
                    
                    // Redirect output to patch file
                    patchPb.redirectOutput(patchFile);
                    Process patchProcess = patchPb.start();
                    patchProcess.waitFor();
                    
                    if (patchFile.exists() && patchFile.length() > 0) {
                        // Apply the patch
                        executeCommand(wtPb, "git", "apply", "--3way", patchFile.getAbsolutePath());
                        
                        // Commit the changes
                        executeCommand(wtPb, "git", "add", "-A");
                        String commitMessage = executeAndGet(repoPb, "git", "log", "--format=%B", "-n", "1", commitHash);
                        executeCommand(wtPb, "git", "commit", "-m", commitMessage);
                        
                        logger.info("Successfully applied commit " + commitHash + " using format-patch fallback");
                    } else {
                        throw new RuntimeException("Failed to create patch for " + commitHash);
                    }
                } catch (Exception fallbackException) {
                    // Both methods failed
                    logger.severe("Both cherry-pick and format-patch failed for " + commitHash);
                    throw new RuntimeException("Failed to apply commit " + commitHash + " using both methods", fallbackException);
                }
            }
            executeCommand(wtPb, "git", "submodule", "sync", "--recursive");
            executeCommand(wtPb, "git", "submodule", "update", "--init", "--recursive", "--checkout", "--force");
            executeCommand(wtPb, "git", "clean", "-xdf");
            executeCommand(wtPb, "rm", "-rf", config.getBuildDir(buildType));

            // Set up environment for ccache if enabled
            if (config.isCcacheEnabled()) {
                // Ensure logs and tmp directories exist
                try {
                    File logsDir = new File(config.getCcacheDir(), "logs");
                    if (!logsDir.exists()) {
                        logsDir.mkdirs();
                    }
                    File tmpDir = new File(config.getCcacheDir(), "tmp");
                    if (!tmpDir.exists()) {
                        tmpDir.mkdirs();
                    }
                } catch (Exception ignore) {}
                String commitShort = commitHash.substring(0, Math.min(commitHash.length(), 7));
                wtPb.environment().put("CC", "ccache gcc");
                wtPb.environment().put("CXX", "ccache g++");
                wtPb.environment().put("CCACHE_DIR", config.getCcacheDir());
                wtPb.environment().put("CCACHE_COMPILERCHECK", config.getCcacheCompilerCheck());
                wtPb.environment().put("CCACHE_HARDLINK", config.getCcacheHardlink() ? "1" : "0");
                wtPb.environment().put("CCACHE_MAXSIZE", config.getCcacheMaxSize());
                // Improve reuse across different work dirs and enable logging
                wtPb.environment().put("CCACHE_BASEDIR", new File(config.getWorkDir()).getAbsolutePath());
                wtPb.environment().put("CCACHE_NOHASHDIR", "1");
                wtPb.environment().put("CCACHE_LOGFILE", new File(config.getCcacheDir(), "logs/ccache_" + commitShort + ".log").getAbsolutePath());
                if (config.getCcacheReadonlyDirect()) {
                    wtPb.environment().put("CCACHE_READONLY_DIRECT", "1");
                }
                wtPb.environment().put("CCACHE_STATS", config.getCcacheStatsEnabled() ? "true" : "false");
                if (!config.getCcacheNamespace().isEmpty()) {
                    wtPb.environment().put("CCACHE_NAMESPACE", config.getCcacheNamespace());
                }
                if (!config.getCcacheSloppiness().isEmpty()) {
                    wtPb.environment().put("CCACHE_SLOPPINESS", config.getCcacheSloppiness());
                }
                
                // Initialize ccache
                try {
                    // Set max size with legacy-compatible -M
                    executeCommand(wtPb, "ccache", "-M", config.getCcacheMaxSize());
                    // Ensure hard_link is configured explicitly with fallback
                    // Older ccache (3.1.6) does not support --set-config or -o; rely on CCACHE_HARDLINK env only
                    // TODO: Implement --set-config and/or -o for hard_link when build environment is updated
                    logger.info("Ccache initialized for direct build");
                } catch (Exception e) {
                    logger.warning("Failed to initialize ccache: " + e.getMessage());
                }
            }
            
            // Set parallel build flags
            wtPb.environment().put("MAKEFLAGS", "-j" + config.getParallelJobs());
            
            java.util.List<String> buildCmd = new java.util.ArrayList<>();
            buildCmd.add("./build.sh");
            for (String token : resolveBuildArgsForScript(wtDir, config.getBuildArg(), buildType)) {
                buildCmd.add(token);
            }
            executeCommand(wtPb, buildCmd.toArray(new String[0]));
            
            // Report ccache statistics after build
            if (config.isCcacheEnabled()) {
                try {
                    String stats = executeAndGet(wtPb, "ccache", "-s");
                    logger.info("Ccache statistics after build:\n" + stats);
                    
                    // Print active configuration
                    String commitShort = commitHash.substring(0, Math.min(commitHash.length(), 7));
                    logger.info("Ccache configuration (via environment):");
                    logger.info("  CCACHE_DIR=" + config.getCcacheDir());
                    logger.info("  CCACHE_MAXSIZE=" + config.getCcacheMaxSize());
                    logger.info("  CCACHE_HARDLINK=" + config.getCcacheHardlink());
                    logger.info("  CCACHE_COMPILERCHECK=" + config.getCcacheCompilerCheck());
                    logger.info("  CCACHE_LOGFILE=" + new File(config.getCcacheDir(), "logs/ccache_" + commitShort + ".log").getAbsolutePath());
                } catch (Exception e) {
                    logger.warning("Failed to get ccache statistics: " + e.getMessage());
                }
            }
            String packageName = "cubrid_" + commitHash.substring(0, 7) + ".tar.gz";
            File packageFile = new File(workDir, packageName);
            ProcessBuilder tarPb = new ProcessBuilder();
            tarPb.directory(new File(wtDir, config.getBuildDir(buildType)));
            executeCommand(tarPb, "tar", "czf", packageFile.getAbsolutePath(), ".");
            ok = true;
            return packageFile.getAbsolutePath();
        } finally {
            try { executeCommand(repoPb, "git", "worktree", "remove", "--force", wtDir.getAbsolutePath()); } catch (Exception ignore) {}
            if (!ok) { try { deleteRecursively(wtDir); } catch (Exception ignore) {} }
        }
    }

    private String buildCubridDirectCheckout(String commitHash, File workDir, String buildType)
            throws IOException, InterruptedException {
        logger.warning("Building CUBRID directly on host (checkout mode)");

        File repoRoot = new File(config.getCubridSrcDir());
        ProcessBuilder repoPb = new ProcessBuilder();
        repoPb.directory(repoRoot);
        try {
            executeCommand(repoPb, "git", "fetch", "--all", "--recurse-submodules=on-demand");
        } catch (Exception ignore) {}

        String shortCommit = commitHash.substring(0, Math.min(commitHash.length(), 7));
        String tempBranch = "checkout_" + shortCommit + "_tmp";
        File wtDir = new File(workDir, "wt_" + shortCommit);
        try {
            executeCommand(repoPb, "git", "worktree", "add", "-b", tempBranch, wtDir.getAbsolutePath(), commitHash);
        } catch (Exception e) {
            executeCommand(repoPb, "git", "branch", "-f", tempBranch, commitHash);
            executeCommand(repoPb, "git", "worktree", "add", wtDir.getAbsolutePath(), tempBranch);
        }

        boolean ok = false;
        try {
            ProcessBuilder wtPb = new ProcessBuilder();
            wtPb.directory(wtDir);

            executeCommand(wtPb, "git", "submodule", "sync", "--recursive");
            executeCommand(wtPb, "git", "submodule", "update", "--init", "--recursive", "--checkout", "--force");
            executeCommand(wtPb, "git", "clean", "-xdf");
            executeCommand(wtPb, "rm", "-rf", config.getBuildDir(buildType));
            executeCommand(wtPb, "rm", "-rf", "cubridmanager");

            if (config.isCcacheEnabled()) {
                try {
                    File logsDir = new File(config.getCcacheDir(), "logs");
                    if (!logsDir.exists()) {
                        logsDir.mkdirs();
                    }
                    File tmpDir = new File(config.getCcacheDir(), "tmp");
                    if (!tmpDir.exists()) {
                        tmpDir.mkdirs();
                    }
                } catch (Exception ignore) {}
                wtPb.environment().put("CC", "ccache gcc");
                wtPb.environment().put("CXX", "ccache g++");
                wtPb.environment().put("CCACHE_DIR", config.getCcacheDir());
                wtPb.environment().put("CCACHE_COMPILERCHECK", config.getCcacheCompilerCheck());
                wtPb.environment().put("CCACHE_HARDLINK", config.getCcacheHardlink() ? "1" : "0");
                wtPb.environment().put("CCACHE_MAXSIZE", config.getCcacheMaxSize());
                wtPb.environment().put("CCACHE_BASEDIR", new File(config.getWorkDir()).getAbsolutePath());
                wtPb.environment().put("CCACHE_NOHASHDIR", "1");
                wtPb.environment().put("CCACHE_LOGFILE", new File(config.getCcacheDir(), "logs/ccache_" + shortCommit + ".log").getAbsolutePath());
                if (config.getCcacheReadonlyDirect()) {
                    wtPb.environment().put("CCACHE_READONLY_DIRECT", "1");
                }
                wtPb.environment().put("CCACHE_STATS", config.getCcacheStatsEnabled() ? "true" : "false");
                if (!config.getCcacheNamespace().isEmpty()) {
                    wtPb.environment().put("CCACHE_NAMESPACE", config.getCcacheNamespace());
                }
                if (!config.getCcacheSloppiness().isEmpty()) {
                    wtPb.environment().put("CCACHE_SLOPPINESS", config.getCcacheSloppiness());
                }
                try { executeCommand(wtPb, "ccache", "-M", config.getCcacheMaxSize()); } catch (Exception ignore) {}
            }

            wtPb.environment().put("MAKEFLAGS", "-j" + config.getParallelJobs());
            java.util.List<String> buildCmd = new java.util.ArrayList<>();
            buildCmd.add("./build.sh");
            for (String token : resolveBuildArgsForScript(wtDir, config.getBuildArg(), buildType)) {
                buildCmd.add(token);
            }
            executeCommand(wtPb, buildCmd.toArray(new String[0]));

            String packageName = "cubrid_" + commitHash.substring(0, 7) + ".tar.gz";
            File packageFile = new File(workDir, packageName);
            ProcessBuilder tarPb = new ProcessBuilder();
            tarPb.directory(new File(wtDir, config.getBuildDir(buildType)));
            executeCommand(tarPb, "tar", "czf", packageFile.getAbsolutePath(), ".");
            ok = true;
            return packageFile.getAbsolutePath();
        } finally {
            try { executeCommand(repoPb, "git", "worktree", "remove", "--force", wtDir.getAbsolutePath()); } catch (Exception ignore) {}
            try { executeCommand(repoPb, "git", "branch", "-D", tempBranch); } catch (Exception ignore) {}
            if (!ok) { try { deleteRecursively(wtDir); } catch (Exception ignore) {} }
        }
    }
    
    private void executeCommand(ProcessBuilder pb, String... command) 
            throws IOException, InterruptedException {
        pb.command(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Drain the output: ./build.sh fills a 64KB pipe within seconds and would block forever.
        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.fine(line);
                tail.addLast(line);
                if (tail.size() > 20) {
                    tail.removeFirst();
                }
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed: " + String.join(" ", command) + "\n" + String.join("\n", tail));
        }
    }

    private String executeAndGet(ProcessBuilder pb, String... command) throws IOException, InterruptedException {
        pb.command(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("Command failed: " + String.join(" ", command));
        }
        return output.toString();
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        // Never descend through a symbolic link (a worktree may contain committed links)
        if (f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        
        try {
            boolean deleted = f.delete();
            
            // If deletion failed and file still exists, try privileged deletion
            if (!deleted && f.exists()) {
                String path = f.getAbsolutePath();
                // Validate path to prevent command injection
                if (path.contains(";") || path.contains("|") || path.contains("&") || path.contains("`")) {
                    logger.warning("Refusing to delete path with suspicious characters: " + path);
                    return;
                }
                
                ProcessBuilder pb = new ProcessBuilder("sudo", "-n", "rm", "-rf", path);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                int exitCode = p.waitFor();
                
                if (exitCode == 0) {
                    logger.fine("Privileged deletion succeeded for: " + path);
                } else {
                    logger.warning("Privileged deletion failed (exit=" + exitCode + ") for: " + path);
                }
            }
        } catch (Exception e) {
            logger.warning("Failed deletion for " + f.getAbsolutePath() + ": " + e.getMessage());
        }
    }
    
    public boolean isDockerAvailable() {
        return dockerAvailable;
    }
    
    public boolean isReady() {
        return imageReady;
    }

    /**
     * Normalize build arguments to honor requested buildType (debug/release).
     * Removes any existing -m flag and adds the correct one based on buildType.
     * Keeps build targets (build/dist/all) at the end.
     *
     * @param buildArg Base build arguments from configuration
     * @param buildType "debug" or "release"
     * @return Normalized build arguments string
     */
    public static String normalizeBuildArg(String buildArg, String buildType) {
        if (buildArg == null) buildArg = "";
        String normalizedType = (buildType == null) ? "" : buildType.trim();
        String mode;
        if ("release".equalsIgnoreCase(normalizedType)) {
            mode = "release";
        } else if ("coverage".equalsIgnoreCase(normalizedType)) {
            mode = "coverage";
        } else if ("profile".equalsIgnoreCase(normalizedType)) {
            mode = "profile";
        } else if ("debug".equalsIgnoreCase(normalizedType)) {
            mode = "debug";
        } else {
            // Default to release when buildType is missing or unrecognized
            mode = "release";
        }

        List<String> options = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        String[] parts = buildArg.trim().isEmpty() ? new String[0] : buildArg.trim().split("\\s+");

        for (int i = 0; i < parts.length; i++) {
            String token = parts[i];
            if (token.isEmpty()) {
                continue;
            }

            if ("-m".equals(token)) {
                // Skip existing -m flag and its value, if any
                if (i + 1 < parts.length) {
                    i++;
                }
                continue;
            }

            if ("-c".equals(token)) {
                if (i + 1 < parts.length) {
                    String value = parts[++i];
                    if (value != null && value.startsWith("-DCMAKE_BUILD_TYPE=")) {
                        // Drop existing CMAKE build type overrides; we will add the correct one later
                        continue;
                    }
                    options.add("-c");
                    options.add(value);
                    continue;
                }
                // Stray -c without value; keep as-is
                options.add(token);
                continue;
            }

            if (token.startsWith("-DCMAKE_BUILD_TYPE=")) {
                // Drop standalone overrides as well
                continue;
            }

            if (!token.startsWith("-") && ("build".equals(token) || "dist".equals(token) || "all".equals(token))) {
                targets.add(token);
                continue;
            }

            options.add(token);
        }

        // Ensure -m <mode> appears before any target
        options.add("-m");
        options.add(mode);

        String cmakeBuildType = null;
        if ("release".equals(mode)) {
            cmakeBuildType = "Release";
        } else if ("debug".equals(mode)) {
            cmakeBuildType = "Debug";
        } else if ("coverage".equals(mode)) {
            cmakeBuildType = "Coverage";
        } else if ("profile".equals(mode)) {
            cmakeBuildType = "Profile";
        }

        if (cmakeBuildType != null) {
            options.add("-c");
            options.add("-DCMAKE_BUILD_TYPE=" + cmakeBuildType);
        }

        List<String> out = new ArrayList<>(options);
        out.addAll(targets);
        return String.join(" ", out).trim();
    }

    public static List<String> resolveBuildArgsForScript(File sourceDir, String buildArg, String buildType) {
        List<String> args = splitBuildArgs(normalizeBuildArg(buildArg, buildType));
        if (!containsGeneratorOption(args)) {
            return args;
        }

        String help = readBuildScriptHelp(sourceDir);
        if (help == null || buildScriptHelpSupportsGeneratorOption(help)) {
            return args;
        }

        logger.info("./build.sh does not support -g; using the branch default build generator");
        return removeGeneratorOption(args);
    }

    public static boolean buildScriptHelpSupportsGeneratorOption(String helpText) {
        if (helpText == null) {
            return false;
        }
        String[] lines = helpText.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals("-g") || trimmed.startsWith("-g ")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitBuildArgs(String args) {
        List<String> tokens = new ArrayList<>();
        if (args == null || args.trim().isEmpty()) {
            return tokens;
        }
        for (String token : args.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static boolean containsGeneratorOption(List<String> args) {
        for (String arg : args) {
            if ("-g".equals(arg) || (arg != null && arg.startsWith("-g") && arg.length() > 2)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> removeGeneratorOption(List<String> args) {
        List<String> adapted = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("-g".equals(arg)) {
                if (i + 1 < args.size()) {
                    i++;
                }
                continue;
            }
            if (arg != null && arg.startsWith("-g") && arg.length() > 2) {
                continue;
            }
            adapted.add(arg);
        }
        return adapted;
    }

    private static String readBuildScriptHelp(File sourceDir) {
        if (sourceDir == null || !sourceDir.isDirectory()) {
            return null;
        }
        File buildScript = new File(sourceDir, "build.sh");
        if (!buildScript.isFile()) {
            return null;
        }

        ProcessBuilder pb = new ProcessBuilder("./build.sh", "-?");
        pb.directory(sourceDir);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            logger.fine("Unable to inspect ./build.sh help for build argument compatibility: " + e.getMessage());
            return null;
        }
    }

    /**
     * Image id of the build image, so a republished tag invalidates a reused workspace.
     * docker_build_image is a moving tag: without this, a workspace keeps 3rdparty
     * libraries and objects built by the previous image's toolchain. Empty on failure,
     * which simply means the image stops contributing to the workspace identity.
     */
    private String resolveBuildImageId() {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", "--format", "{{.Id}}",
                                           buildImage).start();
            String id;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                id = r.readLine();
            }
            p.waitFor();
            return (id == null) ? "" : id.trim();
        } catch (Exception e) {
            logger.warning("Could not resolve build image id: " + e.getMessage());
            return "";
        }
    }

    /**
     * Inlines build-accel.sh into a generated build script. The helpers remove the
     * network round-trips the build otherwise repeats from scratch every time (the
     * Temurin JDK, the 3rdparty tarballs, the submodule clones); see that file for
     * why each one is safe to skip. They are inlined rather than mounted so the
     * generated script stays self-contained and reproducible from the log.
     *
     * Emitted unconditionally, so the script shape does not depend on configuration;
     * the BT_ACCEL environment variable is what turns the accelerators on and off.
     */
    private static void writeBuildAccelHelpers(PrintWriter writer) {
        String[] candidates = {
            "build-accel.sh",
            "/com/navercorp/cubridqa/builder/build-accel.sh"
        };
        for (String candidate : candidates) {
            try (InputStream in = DockerBuildManager.class.getResourceAsStream(candidate)) {
                if (in == null) {
                    continue;
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.println(line);
                    }
                }
                writer.println();
                return;
            } catch (IOException e) {
                // fall through to the next candidate
            }
        }
        // Without the resource the build still has to work, so emit no-op stubs
        // rather than letting the generated script die on an unbound function.
        logger.warning("build-accel.sh resource not found; builds run without accelerators");
        writer.println("# build-accel.sh resource unavailable; accelerators disabled");
        writer.println("bt_phase() { :; }");
        writer.println("bt_local_submodules() { :; }");
        writer.println("bt_seed_jdk() { :; }");
        writer.println("bt_seed_3rdparty() { :; }");
        writer.println("bt_save_3rdparty() { :; }");
        // Every helper the scripts call needs a stub, or the script dies on an unknown command.
        // Without the accelerators there is no workspace reuse: always start from an empty target.
        writer.println("bt_prepare_workspace() { BT_WORKSPACE_REUSED=0; rm -rf \"$1\"; mkdir -p \"$1\"; }");
        writer.println("bt_workspace_ok() { :; }");
        writer.println("bt_clean_submodules() { :; }");
        writer.println("bt_guard_build_inputs() { :; }");
        writer.println("bt_reset_install() { :; }");
        writer.println();
    }

    private void logPhaseSummary(String commitShort, List<String> phases) {
        String summary = "Build phases [" + commitShort + "]: " + String.join(" ", phases);
        logger.info(summary);
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                RequestLogManager.getInstance().getRequestLogger(requestId, "builder").info(summary);
            }
        } catch (Exception ignore) {
            // the summary is already in the global log; never fail a build over it
        }
    }

    /**
     * Collects the "[BT-PHASE] name=x seconds=n total=t" markers that build-accel.sh
     * emits, so a slowdown can be attributed to a phase instead of guessed at from
     * wall-clock. Returns null for any other line.
     */
    private static String parsePhaseMarker(String line) {
        if (line == null || !line.startsWith("[BT-PHASE]")) {
            return null;
        }
        String name = null;
        String seconds = null;
        for (String token : line.substring("[BT-PHASE]".length()).trim().split("\\s+")) {
            if (token.startsWith("name=")) {
                name = token.substring(5);
            } else if (token.startsWith("seconds=")) {
                seconds = token.substring(8);
            }
        }
        return (name == null || seconds == null) ? null : name + "=" + seconds + "s";
    }

    /**
     * Emit a best-effort shell snippet that guarantees Perl's Time::Piece module is
     * present before invoking ./build.sh. CUBRID's OpenSSL 3.x build step aborts early
     * with "requires Perl with the Time::Piece module" when it is missing, which the
     * stock cubridci build images do not ship. The container runs as root, so the
     * package install needs no sudo. Failures here are non-fatal: build.sh still emits
     * the canonical prerequisite error if the module cannot be provided.
     */
    private static void writePerlPrerequisite(PrintWriter writer) {
        writer.println("# Ensure Perl Time::Piece is available (required by the OpenSSL 3.x build)");
        writer.println("ensure_perl_time_piece() {");
        writer.println("  if perl -MTime::Piece -e 1 >/dev/null 2>&1; then");
        writer.println("    return 0");
        writer.println("  fi");
        writer.println("  echo '[INFO] Perl Time::Piece module missing; installing (required by OpenSSL 3.x build)'");
        writer.println("  if command -v dnf >/dev/null 2>&1; then");
        writer.println("    dnf install -y perl-Time-Piece >/dev/null 2>&1 || dnf install -y perl-core >/dev/null 2>&1 || true");
        writer.println("  elif command -v yum >/dev/null 2>&1; then");
        writer.println("    yum install -y perl-Time-Piece >/dev/null 2>&1 || yum install -y perl-core >/dev/null 2>&1 || true");
        writer.println("  elif command -v apt-get >/dev/null 2>&1; then");
        writer.println("    apt-get update >/dev/null 2>&1 || true");
        writer.println("    apt-get install -y perl >/dev/null 2>&1 || true");
        writer.println("  fi");
        writer.println("  if perl -MTime::Piece -e 1 >/dev/null 2>&1; then");
        writer.println("    echo '[INFO] Perl Time::Piece module installed successfully'");
        writer.println("  else");
        writer.println("    echo '[WARN] Perl Time::Piece still unavailable; OpenSSL 3.x build may fail' >&2");
        writer.println("  fi");
        writer.println("}");
        writer.println("ensure_perl_time_piece");
    }

    private static void writeDockerBuildArgCompatibility(PrintWriter writer, String finalBuildArgs) {
        writer.print("BUILD_ARGS=(");
        for (String token : splitBuildArgs(finalBuildArgs)) {
            writer.print(" ");
            writer.print(shellQuote(token));
        }
        writer.println(" )");
        writer.println("adapt_build_args_for_build_sh() {");
        writer.println("  local help_text");
        writer.println("  help_text=\"$(./build.sh -? 2>&1 || true)\"");
        writer.println("  if printf '%s\\n' \"$help_text\" | grep -Eq '^[[:space:]]*-g([[:space:]]|$)'; then");
        writer.println("    return 0");
        writer.println("  fi");
        writer.println("  local adapted=()");
        writer.println("  local skip_next=0");
        writer.println("  local removed=0");
        writer.println("  local arg");
        writer.println("  for arg in \"${BUILD_ARGS[@]}\"; do");
        writer.println("    if [ \"$skip_next\" = \"1\" ]; then");
        writer.println("      skip_next=0");
        writer.println("      continue");
        writer.println("    fi");
        writer.println("    case \"$arg\" in");
        writer.println("      -g)");
        writer.println("        skip_next=1");
        writer.println("        removed=1");
        writer.println("        continue");
        writer.println("        ;;");
        writer.println("      -g*)");
        writer.println("        removed=1");
        writer.println("        continue");
        writer.println("        ;;");
        writer.println("    esac");
        writer.println("    adapted+=(\"$arg\")");
        writer.println("  done");
        writer.println("  if [ \"$removed\" = \"1\" ]; then");
        writer.println("    echo '[INFO] ./build.sh does not support -g; using the branch default build generator'");
        writer.println("    BUILD_ARGS=(\"${adapted[@]}\")");
        writer.println("  fi");
        writer.println("}");
        writer.println("adapt_build_args_for_build_sh");
    }

    private static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
    
    /**
     * Get the host user's UID for output ownership fix inside container
     */
    private static String getHostUid() {
        try {
            ProcessBuilder pb = new ProcessBuilder("id", "-u");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String uid = reader.readLine();
                p.waitFor();
                return uid != null ? uid.trim() : "1000";
            }
        } catch (Exception e) {
            return "1000"; // Default fallback
        }
    }
    
    /**
     * Get the host user's GID for output ownership fix inside container
     */
    private static String getHostGid() {
        try {
            ProcessBuilder pb = new ProcessBuilder("id", "-g");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String gid = reader.readLine();
                p.waitFor();
                return gid != null ? gid.trim() : "1000";
            }
        } catch (Exception e) {
            return "1000"; // Default fallback
        }
    }
}
