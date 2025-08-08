/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

/**
 * DockerTesterManager - Manages CUBRID test execution within Docker containers
 */
public class DockerTesterManager {
    private static final Logger logger = Logger.getLogger(DockerTesterManager.class.getName());
    
    private final BuilderConfig config;
    private boolean dockerAvailable;
    private boolean imageReady;
    
    public DockerTesterManager(BuilderConfig config) {
        this.config = config;
        this.dockerAvailable = DockerUtils.isDockerAvailable();
        this.imageReady = false;
        
        if (!dockerAvailable) {
            logger.warning("Docker is not available. Using direct test execution.");
        }
    }
    
    public void initialize() throws IOException, InterruptedException {
        if (!dockerAvailable) {
            return;
        }
        
        if (config.usePrebuiltDockerImages()) {
            logger.info("Using pre-built Docker test image: " + config.getDockerTestImage());
            pullPrebuiltTestImage();
        } else {
            logger.info("Building Docker test image from source");
            buildTestImage();
        }
        
        imageReady = true;
    }
    
    private void pullPrebuiltTestImage() throws IOException, InterruptedException {
        String imageName = config.getDockerTestImage();
        logger.info("Pulling Docker image: " + imageName);
        
        ProcessBuilder pb = new ProcessBuilder("docker", "pull", imageName);
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            throw new IOException("Failed to pull Docker image: " + imageName);
        }
        
        logger.info("Successfully pulled test image: " + imageName);
    }
    
    private void buildTestImage() throws IOException, InterruptedException {
        // For now, use pre-built image
        pullPrebuiltTestImage();
    }
    
    public boolean isDockerAvailable() {
        return dockerAvailable;
    }
    
    public boolean isReady() {
        return imageReady;
    }
}
