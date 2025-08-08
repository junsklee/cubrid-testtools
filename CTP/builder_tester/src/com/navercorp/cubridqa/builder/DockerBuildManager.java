/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

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
    
    public String buildCubrid(String commitHash, File workDir, String buildType) 
            throws IOException, InterruptedException {
        
        if (!dockerAvailable || !imageReady) {
            return buildCubridDirect(commitHash, workDir, buildType);
        }
        
        logger.info("Building CUBRID commit " + commitHash + " in Docker container");
        
        // Check for GitHub token
        String githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.trim().isEmpty()) {
            throw new IOException("GITHUB_TOKEN environment variable is not set");
        }
        
        // Create build script
        File buildScript = createDockerBuildScript(commitHash, buildType, workDir);
        
        // Prepare Docker command
        List<String> dockerCommand = new ArrayList<>();
        dockerCommand.add("docker");
        dockerCommand.add("run");
        dockerCommand.add("--rm");
        dockerCommand.add("-v");
        dockerCommand.add(config.getCubridSrcDir() + ":/cubrid-src:ro");
        dockerCommand.add("-v");
        dockerCommand.add(workDir.getAbsolutePath() + ":/output:rw");
        dockerCommand.add("-v");
        dockerCommand.add(buildScript.getAbsolutePath() + ":/build.sh:ro");
        dockerCommand.add("-e");
        dockerCommand.add("COMMIT_HASH=" + commitHash);
        dockerCommand.add("-e");
        dockerCommand.add("BUILD_TYPE=" + buildType);
        dockerCommand.add("-e");
        dockerCommand.add("GITHUB_TOKEN=" + githubToken);
        dockerCommand.add(config.getDockerBuildImage());
        dockerCommand.add("bash");
        dockerCommand.add("/build.sh");
        
        logger.info("Running Docker build: " + String.join(" ", dockerCommand));
        
        ProcessBuilder pb = new ProcessBuilder(dockerCommand);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        // Capture output
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.info("DOCKER: " + line);
            }
        }
        
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            throw new IOException("Docker build failed with exit code: " + exitCode);
        }
        
        // Return path to built package
        String packageName = "cubrid_" + commitHash.substring(0, 7) + ".tar.gz";
        return new File(workDir, packageName).getAbsolutePath();
    }
    
    private File createDockerBuildScript(String commitHash, String buildType, File workDir) 
            throws IOException {
        File script = new File(workDir, "docker_build.sh");
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(script))) {
            writer.println("#!/bin/bash");
            writer.println("set -e");
            writer.println();
            writer.println("# Copy source to working directory");
            writer.println("cp -r /cubrid-src /tmp/cubrid-build");
            writer.println("cd /tmp/cubrid-build");
            writer.println();
            writer.println("# Checkout specific commit");
            writer.println("git checkout ${COMMIT_HASH}");
            writer.println("git submodule update --init --recursive");
            writer.println();
            writer.println("# Clean any previous builds");
            writer.println("rm -rf build_x86_64_*");
            writer.println("rm -rf cubridmanager/*");
            writer.println();
            writer.println("# Build CUBRID");
            writer.println("./build.sh " + config.getBuildArg());
            writer.println();
            writer.println("# Create package");
            writer.println("cd " + config.getBuildDir());
            writer.println("tar czf /output/cubrid_${COMMIT_HASH:0:7}.tar.gz .");
            writer.println();
            writer.println("echo \"Build completed successfully\"");
        }
        
        script.setExecutable(true);
        return script;
    }
    
    private String buildCubridDirect(String commitHash, File workDir, String buildType) 
            throws IOException, InterruptedException {
        logger.warning("Building CUBRID directly on host");
        
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(config.getCubridSrcDir()));
        
        // Checkout commit
        executeCommand(pb, "git", "checkout", commitHash);
        executeCommand(pb, "git", "submodule", "update", "--init", "--recursive");
        
        // Clean and build
        executeCommand(pb, "rm", "-rf", config.getBuildDir());
        executeCommand(pb, "rm", "-rf", "cubridmanager");
        executeCommand(pb, "./build.sh", config.getBuildArg());
        
        // Create package
        String packageName = "cubrid_" + commitHash.substring(0, 7) + ".tar.gz";
        File packageFile = new File(workDir, packageName);
        
        pb.directory(new File(config.getCubridSrcDir(), config.getBuildDir()));
        executeCommand(pb, "tar", "czf", packageFile.getAbsolutePath(), ".");
        
        return packageFile.getAbsolutePath();
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
    
    public boolean isDockerAvailable() {
        return dockerAvailable;
    }
    
    public boolean isReady() {
        return imageReady;
    }
}
