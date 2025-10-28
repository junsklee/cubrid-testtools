/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import com.navercorp.cubridqa.builder.logging.*;
import com.navercorp.cubridqa.builder.docker.DockerUtils;

/**
 * DockerBuildManager - Manages CUBRID builds within Docker containers
 */
public class DockerBuildManager {
    private static final Logger logger = Logger.getLogger(DockerBuildManager.class.getName());
    
    private final BuilderConfig config;
    private boolean dockerAvailable;
    private boolean imageReady;
    
    public DockerBuildManager(BuilderConfig config) {
        this.config = config;
        this.dockerAvailable = DockerUtils.isDockerAvailable();
        this.imageReady = false;
        
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
        
        imageReady = true;
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
    
    public String buildCubrid(String commitHash, File workDir, String buildType, String baselineCommit) 
            throws IOException, InterruptedException {
        
        if (!dockerAvailable || !imageReady) {
            return buildCubridDirect(commitHash, workDir, buildType, baselineCommit);
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
        
        // Create build script
        File buildScript = createDockerBuildScript(commitHash, buildType, baselineCommit, workDir);
        
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
        baseDockerCmd.add("-e");
        baseDockerCmd.add("BASELINE_COMMIT=" + baselineCommit);
        baseDockerCmd.add("-e");
        baseDockerCmd.add("GITHUB_TOKEN=" + githubToken);
        
        // Add ccache environment variables if enabled
        if (config.isCcacheEnabled()) {
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CC=ccache gcc");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CXX=ccache g++");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_DIR=/work/.ccache");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_COMPILERCHECK=" + config.getCcacheCompilerCheck());
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_HARDLINK=" + (config.getCcacheHardlink() ? "1" : "0"));
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_MAXSIZE=" + config.getCcacheMaxSize());
            // Improve reuse across different work dirs and enable logging
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_BASEDIR=/work");
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

        // No per-build extra environment overrides (reverted)
        
        baseDockerCmd.add(config.getDockerBuildImage());
        // Run build script in login shell to ensure git-worktree is available and PATH updated
        baseDockerCmd.add("bash");
        baseDockerCmd.add("-lc");
        baseDockerCmd.add("/build.sh");
        
        int attempts = 0;
        IOException lastError = null;
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
        while (attempts < 2) { // first attempt + 1 retry
            attempts++;
            List<String> dockerCommand = new ArrayList<>(baseDockerCmd);
            logger.info("Running Docker build [" + commitShort + "] (attempt " + attempts + "): " + String.join(" ", dockerCommand));

            ProcessBuilder pb = new ProcessBuilder(dockerCommand);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Capture output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 java.io.BufferedWriter logWriter = Files.newBufferedWriter(perBuildLog, java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.info("DOCKER[" + commitShort + "]: " + line);
                    try {
                        logWriter.write(line);
                        logWriter.newLine();
                    } catch (Exception ignore) {}
                }
                try { logWriter.flush(); } catch (Exception ignore) {}
            }
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                lastError = null;
                break;
            }
            lastError = new IOException("Docker build failed with exit code: " + exitCode + " (log: " + perBuildLog.toString() + ")");
            logger.warning("Docker build [" + commitShort + "] attempt " + attempts + " failed (exit=" + exitCode + ")." +
                           (attempts < 2 ? " Retrying..." : " No more retries."));
            try { Thread.sleep(5000); } catch (InterruptedException ignore) { }
        }
        if (lastError != null) {
            throw lastError;
        }
        
        // Return path to built package
        String packageName = "cubrid_" + commitHash.substring(0, 7) + ".tar.gz";
        return new File(workDir, packageName).getAbsolutePath();
    }

    public String buildPullRequest(String prBranch, String headSha, File workDir, String buildType, String baselineCommit)
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
        baseDockerCmd.add("-e");
        baseDockerCmd.add("GITHUB_TOKEN=" + githubToken);

        if (config.isCcacheEnabled()) {
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CC=ccache gcc");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CXX=ccache g++");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_DIR=/work/.ccache");
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_COMPILERCHECK=" + config.getCcacheCompilerCheck());
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_HARDLINK=" + (config.getCcacheHardlink() ? "1" : "0"));
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_MAXSIZE=" + config.getCcacheMaxSize());
            baseDockerCmd.add("-e");
            baseDockerCmd.add("CCACHE_BASEDIR=/work");
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

        baseDockerCmd.add(config.getDockerBuildImage());
        baseDockerCmd.add("bash");
        baseDockerCmd.add("-lc");
        baseDockerCmd.add("/build.sh");

        int attempts = 0;
        IOException lastError = null;
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
        while (attempts < 2) {
            attempts++;
            List<String> dockerCommand = new ArrayList<>(baseDockerCmd);
            logger.info("Running Docker PR build [" + commitShort + "] (attempt " + attempts + "): " + String.join(" ", dockerCommand));

            ProcessBuilder pb = new ProcessBuilder(dockerCommand);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 java.io.BufferedWriter logWriter = Files.newBufferedWriter(perBuildLog, java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.info("DOCKER[" + commitShort + "]: " + line);
                    try {
                        logWriter.write(line);
                        logWriter.newLine();
                    } catch (Exception ignore) {}
                }
                try { logWriter.flush(); } catch (Exception ignore) {}
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                lastError = null;
                break;
            }
            lastError = new IOException("Docker PR build failed with exit code: " + exitCode + " (log: " + perBuildLog.toString() + ")");
            logger.warning("Docker PR build [" + commitShort + "] attempt " + attempts + " failed (exit=" + exitCode + ")." +
                           (attempts < 2 ? " Retrying..." : " No more retries."));
            try { Thread.sleep(5000); } catch (InterruptedException ignore) { }
        }
        if (lastError != null) {
            throw lastError;
        }

        String packageName = "cubrid_" + headSha.substring(0, 7) + ".tar.gz";
        return new File(workDir, packageName).getAbsolutePath();
    }

    private File createDockerPrBuildScript(String prBranch, String headSha, String buildType, String baselineCommit, File workDir)
            throws IOException {
        File script = new File(workDir, "docker_build_pr.sh");
        final String finalBuildArgs = normalizeBuildArg(config.getBuildArg(), buildType);

        try (PrintWriter writer = new PrintWriter(new FileWriter(script))) {
            writer.println("#!/bin/bash");
            writer.println("set -e");
            writer.println();

            if (config.isCcacheEnabled()) {
                writer.println("# Configure ccache for faster builds");
                writer.println("if command -v ccache &> /dev/null; then");
                writer.println("  mkdir -p /work/.ccache/logs /work/.ccache/tmp || true");
                writer.println("  ccache -M ${CCACHE_MAXSIZE:-5G} || true");
                writer.println("  if [ -n \"$CCACHE_LOGFILE\" ]; then rm -f \"$CCACHE_LOGFILE\" || true; fi");
                writer.println("  ccache -z || true");
                writer.println("  echo 'Ccache status before build:'");
                writer.println("  ccache -s || true");
                writer.println("fi");
                writer.println();
            }

            writer.println("# Prepare working directory with FIXED name for ccache path consistency");
            writer.println("if [ -d /work ]; then");
            writer.println("  target=/work/cubrid-build");
            writer.println("else");
            writer.println("  target=/tmp/cubrid-build");
            writer.println("fi");
            writer.println("rm -rf \"$target\"");
            writer.println("mkdir -p \"$target\"");
            writer.println("cd \"$target\"");
            writer.println();
            writer.println("# Clone repository from host reference");
            writer.println("git clone --no-checkout --reference /cubrid-src --dissociate /cubrid-src repo || git clone --no-checkout /cubrid-src repo");
            writer.println("cd repo");
            writer.println("git config advice.detachedHead false");
            writer.println("git config user.email build@localhost");
            writer.println("git config user.name Build Bot");
            writer.println();
            writer.println("# Try to fetch PR head from upstream to ensure commit object exists");
            writer.println("git remote add upstream https://github.com/CUBRID/cubrid.git 2>/dev/null || true");
            writer.println("tmp_branch=pr_${COMMIT_HASH:0:7}_tmp");
            writer.println("# Derive PR number from PR_BRANCH if available (extract digits)");
            writer.println("prn=''\nif [ -n \"${PR_NUMBER}\" ]; then prn=${PR_NUMBER}; elif [ -n \"${PR_BRANCH}\" ]; then prn=$(echo \"${PR_BRANCH}\" | sed 's/[^0-9]//g'); fi");
            writer.println("if [ -n \"$prn\" ]; then");
            writer.println("  echo \"Fetching PR #$prn from upstream\"");
            writer.println("  git fetch upstream pull/$prn/head:$tmp_branch || git fetch origin pull/$prn/head:$tmp_branch || true");
            writer.println("fi");
            writer.println("# Checkout PR head by commit hash as authoritative fallback");
            writer.println("git checkout -B \"$tmp_branch\" \"${COMMIT_HASH}\" || git checkout \"$tmp_branch\" || true");
            writer.println();
            writer.println("git submodule sync --recursive");
            writer.println("git submodule update --init --recursive --checkout --force");
            writer.println();
            writer.println("git clean -xdf");
            writer.println("rm -rf build_x86_64_*");
            writer.println();

            // Add git shim for deterministic version if ccache is enabled
            if (config.isCcacheEnabled()) {
                writer.println("# Create git shim for deterministic version (ccache optimization)");
                writer.println("GIT_SHIM_DIR=\"/tmp/git-shim-$$\"");
                writer.println("mkdir -p \"$GIT_SHIM_DIR\"");
                writer.println("cat > \"$GIT_SHIM_DIR/git\" << 'GITSHIMEOF'");
                writer.println("#!/usr/bin/env bash");
                writer.println("# Intercept version queries and return deterministic values");
                writer.println("if [[ \"$1\" == \"rev-parse\" && \"$2\" == \"--short=7\" ]]; then");
                writer.println("  echo \"0000000\"");
                writer.println("elif [[ \"$1\" == \"rev-list\" ]]; then");
                writer.println("  echo \"0\"");
                writer.println("else");
                writer.println("  exec /usr/bin/git \"$@\"");
                writer.println("fi");
                writer.println("GITSHIMEOF");
                writer.println("chmod +x \"$GIT_SHIM_DIR/git\"");
                writer.println("export PATH=\"$GIT_SHIM_DIR:$PATH\"");
                writer.println("echo \"Git shim active for deterministic version\"");
                writer.println();
            }

            writer.println("# Build CUBRID");
            writer.println("if [ -f /opt/rh/devtoolset-8/enable ]; then");
            writer.println("  echo 'Using devtoolset-8 for build'");
            writer.println("  source /opt/rh/devtoolset-8/enable");
            writer.println("  ./build.sh " + finalBuildArgs + " || { echo '[FATAL] Build failed'; exit 1; }");
            writer.println("else");
            writer.println("  echo 'Building with default toolchain'");
            writer.println("  ./build.sh " + finalBuildArgs + " || { echo '[FATAL] Build failed'; exit 1; }");
            writer.println("fi");
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
            writer.println("tar czf /output/cubrid_${COMMIT_HASH:0:7}.tar.gz .");
            writer.println();
            writer.println("# Cleanup");
            writer.println("cd \"$target/repo\"");
            writer.println("git checkout --detach || true");
            writer.println("git branch -D \"$tmp_branch\" || true");
            writer.println("cd /");
            writer.println("rm -rf \"$target\" || true");
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
            String normalizedArgs = normalizeBuildArg(config.getBuildArg(), buildType);
            for (String token : normalizedArgs.trim().split("\\s+")) { if (!token.isEmpty()) buildCmd.add(token); }
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
            writer.println();
            
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
                writer.println("fi");
                writer.println();
            }

            // Reverted path normalization flags (keep environment unchanged)
            
            writer.println("# Prepare working directory with FIXED name for ccache path consistency");
            writer.println("if [ -d /work ]; then");
            writer.println("  target=/work/cubrid-build");
            writer.println("else");
            writer.println("  target=/tmp/cubrid-build");
            writer.println("fi");
            writer.println("rm -rf \"$target\"");
            writer.println("mkdir -p \"$target\" ");
            writer.println("cd \"$target\"");
            writer.println();
            writer.println("# Clone source into writable target using local reference to avoid network fetches");
            writer.println("git clone --no-checkout --reference /cubrid-src --dissociate /cubrid-src repo || git clone --no-checkout /cubrid-src repo");
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
            writer.println("    echo 'Format-patch fallback succeeded'");
            writer.println("    return 0");
            writer.println("  else");
            writer.println("    echo 'Both cherry-pick and format-patch failed'");
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
            writer.println("git submodule sync --recursive");
            writer.println("git submodule update --init --recursive --checkout --force");
            writer.println();

            writer.println("# Clean any previous builds");
            writer.println("git clean -xdf");
            writer.println("rm -rf build_x86_64_*");
            writer.println();

            // Add git shim for deterministic version if ccache is enabled
            if (config.isCcacheEnabled()) {
                writer.println("# Create git shim for deterministic version (ccache optimization)");
                writer.println("GIT_SHIM_DIR=\"/tmp/git-shim-$$\"");
                writer.println("mkdir -p \"$GIT_SHIM_DIR\"");
                writer.println("cat > \"$GIT_SHIM_DIR/git\" << 'GITSHIMEOF'");
                writer.println("#!/usr/bin/env bash");
                writer.println("# Intercept version queries and return deterministic values");
                writer.println("if [[ \"$1\" == \"rev-parse\" && \"$2\" == \"--short=7\" ]]; then");
                writer.println("  echo \"0000000\"");
                writer.println("elif [[ \"$1\" == \"rev-list\" ]]; then");
                writer.println("  echo \"0\"");
                writer.println("else");
                writer.println("  exec /usr/bin/git \"$@\"");
                writer.println("fi");
                writer.println("GITSHIMEOF");
                writer.println("chmod +x \"$GIT_SHIM_DIR/git\"");
                writer.println("export PATH=\"$GIT_SHIM_DIR:$PATH\"");
                writer.println("echo \"Git shim active for deterministic version\"");
                writer.println();
            }

            writer.println("# Build CUBRID");

            // Check if devtoolset-8 is available and use it
            writer.println("# Try to use devtoolset-8 if available");
            writer.println("if [ -f /opt/rh/devtoolset-8/enable ]; then");
            writer.println("  echo 'Using devtoolset-8 for build'");
            writer.println("  source /opt/rh/devtoolset-8/enable");
            writer.println("  ./build.sh " + finalBuildArgs + " || { echo '[FATAL] Build failed'; exit 1; }");
            writer.println("else");
            writer.println("  echo 'Building with default toolchain'");
            writer.println("  ./build.sh " + finalBuildArgs + " || { echo '[FATAL] Build failed'; exit 1; }");
            writer.println("fi");
            writer.println();
            
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
            writer.println("if [ \"${BUILD_SKIP_PACKAGE:-0}\" != \"1\" ]; then tar czf /output/cubrid_${COMMIT_HASH:0:7}.tar.gz .; fi");
            writer.println();
            writer.println("# Cleanup temporary branch and workspace");
            writer.println("cd \"$target/repo\"");
            writer.println("git checkout --detach || true");
            writer.println("git branch -D \"$tmp_branch\" || true");
            writer.println("cd /");
            writer.println("rm -rf \"$target\" || true");
            writer.println("echo \"Build completed successfully\"");
        }
        
        script.setExecutable(true);
        return script;
    }
    
    private String buildCubridDirect(String commitHash, File workDir, String buildType, String baselineCommit) 
            throws IOException, InterruptedException {
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
            String normalizedArgs = normalizeBuildArg(config.getBuildArg(), buildType);
            for (String token : normalizedArgs.trim().split("\\s+")) {
                if (!token.isEmpty()) buildCmd.add(token);
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
    
    private void executeCommand(ProcessBuilder pb, String... command) 
            throws IOException, InterruptedException {
        pb.command(command);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed: " + String.join(" ", command));
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
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        try { f.delete(); } catch (Exception ignore) {}
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
        String mode = (buildType != null && buildType.trim().equalsIgnoreCase("release")) ? "release" : "debug";

        List<String> options = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        String[] parts = buildArg.trim().isEmpty() ? new String[0] : buildArg.trim().split("\\s+");

        boolean skipNext = false;
        for (int i = 0; i < parts.length; i++) {
            if (skipNext) { skipNext = false; continue; }
            String t = parts[i];
            if ("-m".equals(t)) {
                // Skip existing -m and its value if present
                skipNext = (i + 1 < parts.length);
                continue;
            }
            // Classify positional targets (must be last): build | dist | all
            if (!t.startsWith("-") && ("build".equals(t) || "dist".equals(t) || "all".equals(t))) {
                targets.add(t);
                continue;
            }
            options.add(t);
        }

        // Ensure -m <mode> appears before any target
        options.add("-m");
        options.add(mode);

        // If no explicit target provided, default to leaving options only
        List<String> out = new ArrayList<>(options);
        out.addAll(targets);
        return String.join(" ", out);
    }
}
