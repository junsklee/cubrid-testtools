/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder.docker;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

/**
 * DockerUtils - Utility class for Docker operations
 * 
 * Provides helper methods for Docker container management,
 * image building, and command execution within containers.
 */
public class DockerUtils {
    private static final Logger logger = Logger.getLogger(DockerUtils.class.getName());
    
    /**
     * Check if Docker is available and running
     */
    public static boolean isDockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "version");
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            logger.warning("Docker not available: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Pull Docker image if not already present
     */
    public static void pullImage(String imageName) throws IOException, InterruptedException {
        logger.info("Checking Docker image: " + imageName);
        
        // Check if image exists
        ProcessBuilder pb = new ProcessBuilder("docker", "images", "-q", imageName);
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        
        process.waitFor();
        
        if (output.length() == 0) {
            logger.info("Image not found locally, pulling: " + imageName);
            pb = new ProcessBuilder("docker", "pull", imageName);
            pb.inheritIO();
            process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to pull Docker image: " + imageName);
            }
            logger.info("Successfully pulled image: " + imageName);
        } else {
            logger.info("Image already exists: " + imageName);
        }
    }
    
    /**
     * Build Docker image from Dockerfile
     */
    public static void buildImage(String dockerfilePath, String imageName, String contextPath) 
            throws IOException, InterruptedException {
        logger.info("Building Docker image: " + imageName);
        
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "build", 
            "-f", dockerfilePath,
            "-t", imageName,
            contextPath
        );
        
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            throw new IOException("Failed to build Docker image: " + imageName);
        }
        
        logger.info("Successfully built image: " + imageName);
    }
    
    /**
     * Execute command in Docker container
     */
    public static CommandResult executeInContainer(String imageName, List<String> volumes, 
                                                  Map<String, String> envVars, String... command) 
            throws IOException, InterruptedException {
        List<String> dockerCommand = new ArrayList<>();
        dockerCommand.add("docker");
        dockerCommand.add("run");
        dockerCommand.add("--rm");
        
        // Add volume mounts
        for (String volume : volumes) {
            dockerCommand.add("-v");
            dockerCommand.add(volume);
        }
        
        // Add environment variables
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            dockerCommand.add("-e");
            dockerCommand.add(entry.getKey() + "=" + entry.getValue());
        }
        
        // Add image name
        dockerCommand.add(imageName);
        
        // Add command to execute
        dockerCommand.addAll(Arrays.asList(command));
        
        logger.info("Executing in container: " + String.join(" ", dockerCommand));
        
        ProcessBuilder pb = new ProcessBuilder(dockerCommand);
        Process process = pb.start();
        
        // Capture output
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        
        Thread stdoutReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                    logger.info("DOCKER: " + line);
                }
            } catch (IOException e) {
                logger.warning("Error reading stdout: " + e.getMessage());
            }
        });
        
        Thread stderrReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                    logger.warning("DOCKER-ERR: " + line);
                }
            } catch (IOException e) {
                logger.warning("Error reading stderr: " + e.getMessage());
            }
        });
        
        stdoutReader.start();
        stderrReader.start();
        
        int exitCode = process.waitFor();
        stdoutReader.join(5000);
        stderrReader.join(5000);
        
        return new CommandResult(exitCode, stdout.toString(), stderr.toString());
    }
    
    /**
     * Result of a Docker command execution
     */
    public static class CommandResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        
        public CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
        
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
