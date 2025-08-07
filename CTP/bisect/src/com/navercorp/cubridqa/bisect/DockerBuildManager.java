/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.bisect;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

/**
 * DockerBuildManager - Manages CUBRID builds within Docker containers
 * 
 * This class handles building CUBRID in a Docker environment to ensure
 * consistent and isolated build environments across different systems.
 */
public class DockerBuildManager {
    private static final Logger logger = Logger.getLogger(DockerBuildManager.class.getName());
    
    private static final String BUILD_IMAGE = "cubrid-bisect-builder:latest";  // For building CUBRID
    private static final String TEST_IMAGE = "cubrid-bisect-tester:latest";    // For testing CUBRID
    
    private final BisectConfig config;
    private boolean dockerAvailable;
    private boolean imageReady;
    
    public DockerBuildManager(BisectConfig config) {
        this.config = config;
        this.dockerAvailable = DockerUtils.isDockerAvailable();
        this.imageReady = false;
        
        if (!dockerAvailable) {
            logger.warning("Docker is not available. Falling back to direct build.");
        }
    }
    
    /**
     * Initialize Docker environment
     */
    public void initialize() throws IOException, InterruptedException {
        if (!dockerAvailable) {
            return;
        }
        
        // Ensure cubridci repository is available
        ensureCubridCIRepository();
        
        // Build custom image using cubridci Dockerfile
        buildCustomImage();
        
        imageReady = true;
    }
    
    /**
     * Ensure cubridci repository is cloned and has both branches available
     */
    private void ensureCubridCIRepository() throws IOException, InterruptedException {
        File cubridciDir = new File(System.getProperty("user.home"), "cubridci");
        
        if (!cubridciDir.exists()) {
            logger.info("Cloning cubridci repository...");
            ProcessBuilder pb = new ProcessBuilder(
                "git", "clone",
                "https://github.com/CUBRID/cubridci.git",
                cubridciDir.getAbsolutePath()
            );
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to clone cubridci repository");
            }
        }
        
        // Fetch both branches
        logger.info("Fetching cubridci branches...");
        ProcessBuilder pb = new ProcessBuilder("git", "fetch", "origin", "develop:develop");
        pb.directory(cubridciDir);
        Process process = pb.start();
        process.waitFor();
        
        pb = new ProcessBuilder("git", "fetch", "origin", "test_shell:test_shell");
        pb.directory(cubridciDir);
        process = pb.start();
        process.waitFor();
        
        logger.info("CubridCI repository ready at: " + cubridciDir.getAbsolutePath());
    }
    
    /**
     * Build custom Docker images from cubridci
     */
    private void buildCustomImage() throws IOException, InterruptedException {
        File cubridciDir = new File(System.getProperty("user.home"), "cubridci");
        
        // Build the builder image from develop branch
        logger.info("Switching to develop branch for build environment...");
        ProcessBuilder pb = new ProcessBuilder("git", "checkout", "develop");
        pb.directory(cubridciDir);
        Process process = pb.start();
        process.waitFor();
        
        String dockerfilePath = cubridciDir.getAbsolutePath() + "/docker/ci/Dockerfile";
        String contextPath = cubridciDir.getAbsolutePath();
        
        logger.info("Building Docker image for CUBRID build environment: " + BUILD_IMAGE);
        DockerUtils.buildImage(dockerfilePath, BUILD_IMAGE, contextPath);
        
        // Note: The test_shell branch image could be built similarly if needed for consumer
        // For now, consumer can continue using its existing setup or we can dockerize it later
    }
    
    /**
     * Build CUBRID in Docker container
     */
    public String buildCubrid(String commitHash, File workDir, String buildType) 
            throws IOException, InterruptedException {
        
        if (!dockerAvailable || !imageReady) {
            // Fallback to direct build
            return buildCubridDirect(commitHash, workDir, buildType);
        }
        
        logger.info("Building CUBRID commit " + commitHash + " in Docker container");
        
        // Check for GitHub token
        String githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.trim().isEmpty()) {
            throw new IOException("GITHUB_TOKEN environment variable is not set or is empty. Cannot run Docker container that requires private repository access.");
        }
        
        // Prepare build script
        File buildScript = createDockerBuildScript(commitHash, buildType, workDir);
        
        // Prepare volumes
        List<String> volumes = new ArrayList<>();
        volumes.add(config.getCubridSrcDir() + ":/cubrid-src:ro");  // Read-only source
        volumes.add(workDir.getAbsolutePath() + ":/build-output:rw");  // Output directory
        volumes.add(buildScript.getAbsolutePath() + ":/build.sh:ro");  // Build script
        
        // Environment variables
        Map<String, String> envVars = new HashMap<>();
        envVars.put("COMMIT_HASH", commitHash);
        envVars.put("BUILD_TYPE", buildType);
        envVars.put("GITHUB_TOKEN", githubToken);
        
        // Execute build in container
        DockerUtils.CommandResult result = DockerUtils.executeInContainer(
            BUILD_IMAGE, volumes, envVars, "bash", "/build.sh"
        );
        
        if (!result.isSuccess()) {
            throw new IOException("Docker build failed with exit code: " + result.exitCode);
        }
        
        // Return path to built package
        String packageName = "cubrid_" + commitHash.substring(0, 7) + ".tar.gz";
        return new File(workDir, packageName).getAbsolutePath();
    }
    
    /**
     * Create build script for Docker execution
     */
    private File createDockerBuildScript(String commitHash, String buildType, File workDir) 
            throws IOException {
        File script = new File(workDir, "docker_build.sh");
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(script))) {
            writer.println("#!/bin/bash");
            writer.println("set -e");
            writer.println();
            writer.println("# This script runs inside the CentOS 6 + devtoolset-8 container");
            writer.println("# The devtoolset-8 is already enabled by the container entrypoint");
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
            writer.println("rm -rf cubridmanager/*  # temporary: cubridmanager fails on Rocky 8");
            writer.println();
            writer.println("# Build CUBRID");
            writer.println("./build.sh " + config.getBuildArg());
            writer.println();
            writer.println("# Create package");
            writer.println("cd " + config.getBuildDir());
            writer.println("tar czf /build-output/cubrid_${COMMIT_HASH:0:7}.tar.gz .");
            writer.println();
            writer.println("echo \"Build completed successfully\"");
        }
        
        script.setExecutable(true);
        return script;
    }
    
    /**
     * Fallback: Build CUBRID directly on host (original method)
     */
    private String buildCubridDirect(String commitHash, File workDir, String buildType) 
            throws IOException, InterruptedException {
        logger.warning("Building CUBRID directly on host (Docker not available)");
        
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(config.getCubridSrcDir()));
        
        // Checkout commit
        executeCommand(pb, "git", "checkout", commitHash);
        executeCommand(pb, "git", "submodule", "update", "--init", "--recursive");
        
        // Clean and build
        executeCommand(pb, "rm", "-rf", config.getBuildDir());
        executeCommand(pb, "rm", "-rf", "cubridmanager");  // temporary fix
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
