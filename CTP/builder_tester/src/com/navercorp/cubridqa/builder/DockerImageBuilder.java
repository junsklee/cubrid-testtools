/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * DockerImageBuilder - Builds and manages Docker images with pre-installed CUBRID builds
 * 
 * This class creates custom Docker images for each CUBRID build to eliminate
 * repeated extraction and setup overhead during test execution.
 * Works with both local and remote tester nodes.
 */
public class DockerImageBuilder {
    private static final Logger logger = Logger.getLogger(DockerImageBuilder.class.getName());
    
    private final BuilderConfig config;
    private final Path workDir;
    private final Map<String, String> imageCache = new ConcurrentHashMap<>();
    private final Map<String, Long> imageBuildTime = new ConcurrentHashMap<>();
    private final int maxCachedImages;
    private final Object buildLock = new Object();
    
    public DockerImageBuilder(BuilderConfig config) {
        this.config = config;
        this.workDir = Paths.get(config.getWorkDir()).resolve("docker_images");
        this.maxCachedImages = config.getBuildCacheSize() > 0 ? config.getBuildCacheSize() : 20;
        
        try {
            Files.createDirectories(workDir);
            loadExistingImages();
        } catch (IOException e) {
            logger.warning("Failed to create docker images work directory: " + e.getMessage());
        }
    }
    
    /**
     * Get or build a Docker image for the given commit.
     * This method first extracts the build package, then builds a Docker image.
     */
    public String getOrBuildImage(String commitHash, Path buildPackage) throws IOException {
        String imageName = "cubrid-test:" + commitHash;
        
        // Check if image already exists
        if (imageExists(imageName)) {
            logger.info("Using existing Docker image: " + imageName);
            updateCacheEntry(commitHash, imageName);
            return imageName;
        }
        
        // Build new image under lock to prevent concurrent builds of same image
        synchronized (buildLock) {
            // Double-check after acquiring lock
            if (imageExists(imageName)) {
                updateCacheEntry(commitHash, imageName);
                return imageName;
            }
            
            logger.info("Building Docker image for commit " + commitHash);
            buildImageFromPackage(commitHash, buildPackage);
            
            // Clean old images if needed
            if (imageCache.size() > maxCachedImages) {
                evictOldestImage();
            }
        }
        
        return imageName;
    }
    
    /**
     * Build Docker image directly from the tar.gz package
     */
    private void buildImageFromPackage(String commitHash, Path buildPackage) throws IOException {
        String imageName = "cubrid-test:" + commitHash;
        Path dockerfileDir = workDir.resolve(commitHash);
        
        try {
            Files.createDirectories(dockerfileDir);
            
            // Copy build package to docker context
            Path packageInContext = dockerfileDir.resolve("build.tar.gz");
            Files.copy(buildPackage, packageInContext, StandardCopyOption.REPLACE_EXISTING);
            
            // Create optimized Dockerfile that extracts and sets up CUBRID
            String dockerfile = createOptimizedDockerfile();
            Path dockerfilePath = dockerfileDir.resolve("Dockerfile");
            Files.write(dockerfilePath, dockerfile.getBytes());
            
            // Build the image
            List<String> buildCommand = new ArrayList<>();
            buildCommand.add("docker");
            buildCommand.add("build");
            buildCommand.add("-t");
            buildCommand.add(imageName);
            buildCommand.add("--no-cache");  // Ensure fresh build for each commit
            buildCommand.add(".");
            
            ProcessBuilder pb = new ProcessBuilder(buildCommand);
            pb.directory(dockerfileDir.toFile());
            pb.redirectErrorStream(true);
            
            logger.info("Building Docker image: " + String.join(" ", buildCommand));
            Process process = pb.start();
            
            // Log build output
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("Docker build: " + line);
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to build Docker image, exit code: " + exitCode);
            }
            
            // Update cache
            updateCacheEntry(commitHash, imageName);
            
            logger.info("Successfully built Docker image: " + imageName);
            
            // Clean up build context to save space
            deleteDirectory(dockerfileDir);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Docker image build interrupted", e);
        } catch (IOException e) {
            // Clean up on failure
            try { deleteDirectory(dockerfileDir); } catch (Exception ignore) {}
            throw e;
        }
    }
    
    /**
     * Create optimized Dockerfile that extracts and installs CUBRID during image build
     */
    private String createOptimizedDockerfile() {
        StringBuilder dockerfile = new StringBuilder();
        
        // Use the test image as base
        dockerfile.append("FROM ").append(config.getDockerTestImage()).append("\n\n");
        
        // Copy build package
        dockerfile.append("# Copy CUBRID build package\n");
        dockerfile.append("COPY build.tar.gz /tmp/build.tar.gz\n\n");
        
        // Extract and setup CUBRID
        dockerfile.append("# Extract CUBRID build package\n");
        dockerfile.append("RUN mkdir -p /opt/cubrid && \\\n");
        dockerfile.append("    cd /opt/cubrid && \\\n");
        dockerfile.append("    tar -xzf /tmp/build.tar.gz && \\\n");
        dockerfile.append("    rm /tmp/build.tar.gz\n\n");
        
        dockerfile.append("# Find and move CUBRID installation\n");
        dockerfile.append("RUN CUBRID_DIR=$(find /opt/cubrid -path '*/_install/CUBRID' -type d | head -1) && \\\n");
        dockerfile.append("    if [ -z \"$CUBRID_DIR\" ]; then \\\n");
        dockerfile.append("        CUBRID_DIR=$(find /opt/cubrid -name 'cubrid_rel' -type f | head -1 | xargs dirname | xargs dirname); \\\n");
        dockerfile.append("    fi && \\\n");
        dockerfile.append("    echo \"Found CUBRID at: $CUBRID_DIR\" && \\\n");
        dockerfile.append("    if [ \"$CUBRID_DIR\" != \"/opt/cubrid\" ] && [ -d \"$CUBRID_DIR\" ]; then \\\n");
        dockerfile.append("        echo \"Moving CUBRID installation to /opt/cubrid\" && \\\n");
        dockerfile.append("        cp -rf \"$CUBRID_DIR\"/* /opt/cubrid/ && \\\n");
        dockerfile.append("        rm -rf /opt/cubrid/_install; \\\n");
        dockerfile.append("    fi\n\n");
        
        dockerfile.append("# Setup CUBRID environment\n");
        dockerfile.append("RUN if [ -f /opt/cubrid/share/scripts/setup.sh ]; then \\\n");
        dockerfile.append("        cd /opt/cubrid && \\\n");
        dockerfile.append("        echo 'y' | sh share/scripts/setup.sh /opt/cubrid || true; \\\n");
        dockerfile.append("    elif [ -f /opt/cubrid/setup.sh ]; then \\\n");
        dockerfile.append("        cd /opt/cubrid && \\\n");
        dockerfile.append("        echo 'y' | sh setup.sh /opt/cubrid || true; \\\n");
        dockerfile.append("    fi\n\n");
        
        dockerfile.append("# Prepare databases directory and verify installation\n");
        dockerfile.append("RUN mkdir -p /opt/cubrid/databases && \\\n");
        dockerfile.append("    touch /opt/cubrid/databases/databases.txt && \\\n");
        dockerfile.append("    ls -la /opt/cubrid/bin/ && \\\n");
        dockerfile.append("    /opt/cubrid/bin/cubrid_rel || echo \"cubrid_rel check failed\"\n\n");
        
        // Set environment variables
        dockerfile.append("# Set CUBRID environment\n");
        dockerfile.append("ENV CUBRID=/opt/cubrid\n");
        dockerfile.append("ENV CUBRID_DATABASES=/opt/cubrid/databases\n");
        dockerfile.append("ENV PATH=/opt/cubrid/bin:$PATH\n");
        dockerfile.append("ENV LD_LIBRARY_PATH=/opt/cubrid/lib:/opt/cubrid/cci/lib:$LD_LIBRARY_PATH\n");
        dockerfile.append("ENV CUBRID_LANG=en_US\n");
        dockerfile.append("ENV CUBRID_CHARSET=en_US\n\n");
        
        // Set working directory
        dockerfile.append("WORKDIR /workspace\n");
        
        return dockerfile.toString();
    }
    
    /**
     * Check if Docker image exists
     */
    private boolean imageExists(String imageName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", imageName);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Consume output to prevent blocking
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Just consume
                }
            }
            
            int exitCode = process.waitFor();
            return exitCode == 0;
            
        } catch (Exception e) {
            logger.warning("Failed to check if image exists: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Update cache entry with current timestamp
     */
    private void updateCacheEntry(String commitHash, String imageName) {
        imageCache.put(commitHash, imageName);
        imageBuildTime.put(commitHash, System.currentTimeMillis());
    }
    
    /**
     * Delete directory recursively
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    
    /**
     * Load existing images from Docker
     */
    private void loadExistingImages() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "images", 
                "--filter", "reference=cubrid-test:*", 
                "--format", "{{.Repository}}:{{.Tag}}");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("cubrid-test:")) {
                        String commitHash = line.substring("cubrid-test:".length());
                        imageCache.put(commitHash, line);
                        imageBuildTime.put(commitHash, System.currentTimeMillis());
                    }
                }
            }
            
            process.waitFor();
            logger.info("Found " + imageCache.size() + " existing CUBRID test images");
            
        } catch (Exception e) {
            logger.warning("Failed to load existing images: " + e.getMessage());
        }
    }
    
    /**
     * Evict oldest Docker image based on build time
     */
    private void evictOldestImage() {
        if (imageCache.size() <= maxCachedImages) {
            return;
        }
        
        // Find oldest image
        String oldestCommit = null;
        long oldestTime = Long.MAX_VALUE;
        
        for (Map.Entry<String, Long> entry : imageBuildTime.entrySet()) {
            if (entry.getValue() < oldestTime) {
                oldestTime = entry.getValue();
                oldestCommit = entry.getKey();
            }
        }
        
        if (oldestCommit != null) {
            String imageName = imageCache.remove(oldestCommit);
            imageBuildTime.remove(oldestCommit);
            
            // Remove Docker image
            try {
                ProcessBuilder pb = new ProcessBuilder("docker", "rmi", "-f", imageName);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                // Consume output
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    while (reader.readLine() != null) {
                        // Just consume
                    }
                }
                
                process.waitFor();
                logger.info("Evicted old Docker image: " + imageName);
                
            } catch (Exception e) {
                logger.warning("Failed to remove evicted image: " + e.getMessage());
            }
        }
    }
    
    /**
     * Clear all cached images
     */
    public void clearCache() {
        for (String imageName : imageCache.values()) {
            try {
                ProcessBuilder pb = new ProcessBuilder("docker", "rmi", "-f", imageName);
                pb.start().waitFor(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warning("Failed to remove image " + imageName + ": " + e.getMessage());
            }
        }
        
        imageCache.clear();
        imageBuildTime.clear();
        logger.info("Cleared Docker image cache");
    }
    
    /**
     * Get cache statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cached_images", imageCache.size());
        stats.put("max_cached_images", maxCachedImages);
        
        // Get total image size
        long totalSize = 0;
        for (String imageName : imageCache.values()) {
            try {
                ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", 
                    imageName, "--format", "{{.Size}}");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        totalSize += Long.parseLong(line.trim());
                    }
                }
                
                process.waitFor();
            } catch (Exception e) {
                // Ignore
            }
        }
        
        stats.put("total_image_size_mb", totalSize / (1024 * 1024));
        
        return stats;
    }
}
