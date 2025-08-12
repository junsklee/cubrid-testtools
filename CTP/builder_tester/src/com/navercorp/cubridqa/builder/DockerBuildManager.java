/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import com.navercorp.cubridqa.builder.logging.*;

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
    
    private File createDockerBuildScript(String commitHash, String buildType, String baselineCommit, File workDir) 
            throws IOException {
        File script = new File(workDir, "docker_build.sh");
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(script))) {
            writer.println("#!/bin/bash");
            writer.println("set -e");
            writer.println();
            writer.println("# Prepare working directory (prefer host-mounted /work if available), per-commit to avoid collisions");
            writer.println("if [ -d /work ]; then");
            writer.println("  target=/work/cubrid-build_${COMMIT_HASH:0:7}");
            writer.println("else");
            writer.println("  target=/tmp/cubrid-build_${COMMIT_HASH:0:7}");
            writer.println("fi");
            writer.println("rm -rf \"$target\"");
            writer.println("mkdir -p \"$target\" ");
            writer.println("cd \"$target\"");
            writer.println();
            writer.println("# Clone source into writable target to avoid touching read-only bind mount");
            writer.println("git clone --no-checkout /cubrid-src repo");
            writer.println("cd repo");
            writer.println("git config advice.detachedHead false");
            writer.println("git config user.email build@localhost");
            writer.println("git config user.name Build Bot");
            writer.println("git fetch --all --recurse-submodules=on-demand || true");
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
            writer.println("# Determine if it's a merge commit and apply");
            writer.println("if git rev-list --parents -n1 \"${COMMIT_HASH}\" | awk '{exit (NF>2)?0:1}'; then");
            writer.println("  apply_commit \"${COMMIT_HASH}\" true || { echo '[FATAL] Failed to apply commit'; exit 1; }");
            writer.println("else");
            writer.println("  apply_commit \"${COMMIT_HASH}\" false || { echo '[FATAL] Failed to apply commit'; exit 1; }");
            writer.println("fi");
            writer.println("git submodule sync --recursive");
            writer.println("git submodule update --init --recursive --checkout --force");
            writer.println();
            writer.println("# Clean any previous builds");
            writer.println("git clean -xdf");
            writer.println("rm -rf build_x86_64_*");
            writer.println("rm -rf cubridmanager/*");
            writer.println();
            writer.println("# Build CUBRID");
            writer.println("./build.sh " + config.getBuildArg() + " || { echo '[FATAL] Build failed'; exit 1; }");
            writer.println();
            writer.println("# Create package");
            writer.println("cd " + config.getBuildDir());
            writer.println("tar czf /output/cubrid_${COMMIT_HASH:0:7}.tar.gz .");
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
            // detect merge commit
            String parents = executeAndGet(repoPb, "git", "rev-list", "--parents", "-n1", commitHash).trim();
            boolean isMerge = parents.split("\\s+").length > 2;
            
            boolean cherryPickSucceeded = false;
            Exception cherryPickException = null;
            
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
            executeCommand(wtPb, "rm", "-rf", config.getBuildDir());
            executeCommand(wtPb, "rm", "-rf", "cubridmanager");
            java.util.List<String> buildCmd = new java.util.ArrayList<>();
            buildCmd.add("./build.sh");
            for (String token : config.getBuildArg().trim().split("\\s+")) {
                if (!token.isEmpty()) buildCmd.add(token);
            }
            executeCommand(wtPb, buildCmd.toArray(new String[0]));
            String packageName = "cubrid_" + commitHash.substring(0, 7) + ".tar.gz";
            File packageFile = new File(workDir, packageName);
            ProcessBuilder tarPb = new ProcessBuilder();
            tarPb.directory(new File(wtDir, config.getBuildDir()));
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
}
