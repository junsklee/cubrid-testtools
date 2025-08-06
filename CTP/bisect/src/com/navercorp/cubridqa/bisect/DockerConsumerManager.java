/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.bisect;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

/**
 * DockerConsumerManager - Manages CUBRID test execution within Docker containers
 * 
 * This class handles running CUBRID shell tests in a Docker environment
 * using the test_shell branch Docker image from cubridci repository.
 */
public class DockerConsumerManager {
    private static final Logger logger = Logger.getLogger(DockerConsumerManager.class.getName());
    
    private static final String TEST_IMAGE = "cubrid-bisect-tester:latest";
    
    private final BisectConfig config;
    private boolean dockerAvailable;
    private boolean imageReady;
    
    public DockerConsumerManager(BisectConfig config) {
        this.config = config;
        this.dockerAvailable = DockerUtils.isDockerAvailable();
        this.imageReady = false;
        
        if (!dockerAvailable) {
            logger.warning("Docker is not available. Using direct test execution.");
        }
    }
    
    /**
     * Initialize Docker environment for testing
     */
    public void initialize() throws IOException, InterruptedException {
        if (!dockerAvailable) {
            return;
        }
        
        // Ensure cubridci repository is available
        ensureCubridCIRepository();
        
        // Build test image from test_shell branch
        buildTestImage();
        
        imageReady = true;
    }
    
    /**
     * Ensure cubridci repository is available with test_shell branch
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
        
        // Ensure test_shell branch is available
        logger.info("Fetching test_shell branch...");
        ProcessBuilder pb = new ProcessBuilder("git", "fetch", "origin", "test_shell:test_shell");
        pb.directory(cubridciDir);
        Process process = pb.start();
        process.waitFor();
        
        logger.info("CubridCI repository ready at: " + cubridciDir.getAbsolutePath());
    }
    
    /**
     * Build Docker image for test environment
     */
    private void buildTestImage() throws IOException, InterruptedException {
        File cubridciDir = new File(System.getProperty("user.home"), "cubridci");
        
        // Switch to test_shell branch for test environment
        logger.info("Switching to test_shell branch for test environment...");
        ProcessBuilder pb = new ProcessBuilder("git", "checkout", "test_shell");
        pb.directory(cubridciDir);
        Process process = pb.start();
        process.waitFor();
        
        String dockerfilePath = cubridciDir.getAbsolutePath() + "/docker/ci/Dockerfile";
        String contextPath = cubridciDir.getAbsolutePath() + "/docker/ci";
        
        logger.info("Building Docker image for CUBRID test environment: " + TEST_IMAGE);
        DockerUtils.buildImage(dockerfilePath, TEST_IMAGE, contextPath);
    }
}
