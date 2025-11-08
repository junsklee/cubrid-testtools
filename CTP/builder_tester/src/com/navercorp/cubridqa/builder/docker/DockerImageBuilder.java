/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder.docker;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

import com.navercorp.cubridqa.builder.BuilderConfig;

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
     * Get or build a Docker image for the given commit built on the given baseline.
     * This method first extracts the build package, then builds a Docker image.
     * The image name includes both commit and baseline to prevent incorrect reuse.
     */
    public String getOrBuildImage(String commitHash, String baselineHash, Path buildPackage) throws IOException {
        String imageKey = commitHash + "_" + baselineHash;
        String imageName = "cubrid-test:" + imageKey;
        
        // Check if image already exists
        if (imageExists(imageName)) {
            logger.info("Using existing Docker image: " + imageName);
            updateCacheEntry(imageKey, imageName);
            return imageName;
        }
        
        // Build new image under lock to prevent concurrent builds of same image
        synchronized (buildLock) {
            // Double-check after acquiring lock
            if (imageExists(imageName)) {
                updateCacheEntry(imageKey, imageName);
                return imageName;
            }
            
            logger.info("Building Docker image for commit " + commitHash + " on baseline " + baselineHash);
            buildImageFromPackage(imageKey, buildPackage);
            
            // Clean old images if needed
            if (imageCache.size() > maxCachedImages) {
                evictOldestImage();
            }
        }
        
        return imageName;
    }
    
    /**
     * Backward compatibility method - use with caution as it may reuse images incorrectly
     * @deprecated Use getOrBuildImage(String, String, Path) instead to specify baseline
     */
    @Deprecated
    public String getOrBuildImage(String commitHash, Path buildPackage) throws IOException {
        logger.warning("Using deprecated getOrBuildImage without baseline - this may cause incorrect image reuse");
        return getOrBuildImage(commitHash, "unknown", buildPackage);
    }
    
    /**
     * Build Docker image directly from the tar.gz package
     */
    private void buildImageFromPackage(String imageKey, Path buildPackage) throws IOException {
        String imageName = "cubrid-test:" + imageKey;
        String sanitizedKey = sanitizeName(imageKey);
        Path stagingDir = workDir.resolve(sanitizedKey + "_setup");
        Files.createDirectories(stagingDir);
        Path setupScript = stagingDir.resolve("setup.sh");
        Files.write(setupScript, createSetupScript().getBytes(StandardCharsets.UTF_8));
        setupScript.toFile().setExecutable(true);
        
        String containerName = "cubrid_img_" + sanitizedKey + "_" + System.currentTimeMillis();
        List<String> runCommand = new ArrayList<>();
        runCommand.add("docker");
        runCommand.add("run");
        runCommand.add("--name");
        runCommand.add(containerName);
        runCommand.add("-v");
        runCommand.add(buildPackage.toAbsolutePath().toString() + ":/mnt/build.tar.gz:ro");
        runCommand.add("-v");
        runCommand.add(setupScript.toAbsolutePath().toString() + ":/mnt/setup.sh:ro");
        runCommand.add("--entrypoint");
        runCommand.add("/bin/bash");
        runCommand.add(config.getDockerTestImage());
        runCommand.add("/mnt/setup.sh");
        
        List<String> commitCommand = new ArrayList<>();
        commitCommand.add("docker");
        commitCommand.add("commit");
        commitCommand.add("--change");
        commitCommand.add("ENV CUBRID=/root/CUBRID");
        commitCommand.add("--change");
        commitCommand.add("ENV CUBRID_DATABASES=/root/CUBRID/databases");
        commitCommand.add("--change");
        commitCommand.add("ENV PATH=/root/CUBRID/bin:/home/cubrid-testtools/CTP/shell/init_path:$PATH");
        commitCommand.add("--change");
        commitCommand.add("ENV LD_LIBRARY_PATH=/root/CUBRID/lib:/root/CUBRID/cci/lib:$LD_LIBRARY_PATH");
        commitCommand.add("--change");
        commitCommand.add("ENV CUBRID_LANG=en_US");
        commitCommand.add("--change");
        commitCommand.add("ENV CUBRID_CHARSET=en_US");
        commitCommand.add("--change");
        commitCommand.add("WORKDIR /workspace");
        commitCommand.add(containerName);
        commitCommand.add(imageName);
        
        try {
            long start = System.currentTimeMillis();
            logger.info("Building Docker image by provisioning container: " + imageName);
            runCommand(runCommand, "[docker-run]");
            runCommand(commitCommand, "[docker-commit]");
            updateCacheEntry(imageKey, imageName);
            long elapsed = System.currentTimeMillis() - start;
            logger.info("Successfully built Docker image: " + imageName + " (" + (elapsed / 1000) + "s)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Docker image build interrupted", e);
        } catch (IOException e) {
            throw e;
        } finally {
            try {
                runCommand(Arrays.asList("docker", "rm", "-f", containerName), null);
            } catch (Exception ignore) { }
            try { deleteDirectory(stagingDir); } catch (Exception ignore) {}
        }
    }
    
    private String createSetupScript() {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("set -euo pipefail\n");
        script.append("log() { echo \"[setup] $*\"; }\n");
        script.append("copy_tree() {\n");
        script.append("  local src=\"$1\"\n");
        script.append("  local dest=\"$2\"\n");
        script.append("  if [[ ! -d \"$src\" ]]; then\n");
        script.append("    return\n");
        script.append("  fi\n");
        script.append("  mkdir -p \"$dest\"\n");
        script.append("  if command -v rsync >/dev/null 2>&1; then\n");
        script.append("    rsync -a \"$src\"/ \"$dest\"/\n");
        script.append("  else\n");
        script.append("    cp -a \"$src\"/. \"$dest\"/\n");
        script.append("  fi\n");
        script.append("}\n");
        script.append("snapshot_tree() {\n");
        script.append("  local src=\"$1\"\n");
        script.append("  if [[ ! -d \"$src\" ]]; then\n");
        script.append("    echo \"\"\n");
        script.append("    return\n");
        script.append("  fi\n");
        script.append("  local snap\n");
        script.append("  snap=$(mktemp -d /tmp/cubrid_snapshot.XXXX)\n");
        script.append("  copy_tree \"$src\" \"$snap\"\n");
        script.append("  echo \"$snap\"\n");
        script.append("}\n");
        script.append("restore_tree() {\n");
        script.append("  local snap=\"$1\"\n");
        script.append("  local dest=\"$2\"\n");
        script.append("  local label=\"$3\"\n");
        script.append("  if [[ -z \"$snap\" || ! -d \"$snap\" ]]; then\n");
        script.append("    return\n");
        script.append("  fi\n");
        script.append("  log \"Restoring pristine $label files\"\n");
        script.append("  mkdir -p \"$dest\"\n");
        script.append("  if command -v rsync >/dev/null 2>&1; then\n");
        script.append("    rsync -a --delete \"$snap\"/ \"$dest\"/\n");
        script.append("  else\n");
        script.append("    rm -rf \"$dest\"\n");
        script.append("    mkdir -p \"$dest\"\n");
        script.append("    cp -a \"$snap\"/. \"$dest\"/\n");
        script.append("  fi\n");
        script.append("}\n");
        script.append("cleanup_snapshots() {\n");
        script.append("  [[ -n \"${CONF_SNAPSHOT:-}\" && -d \"$CONF_SNAPSHOT\" ]] && rm -rf \"$CONF_SNAPSHOT\"\n");
        script.append("  [[ -n \"${DB_SNAPSHOT:-}\" && -d \"$DB_SNAPSHOT\" ]] && rm -rf \"$DB_SNAPSHOT\"\n");
        script.append("}\n");
        script.append("ensure_sample_databases() {\n");
        script.append("  mkdir -p /opt/cubrid/databases\n");
        script.append("  touch /opt/cubrid/databases/databases.txt\n");
        script.append("  if [[ ! -f /opt/cubrid/databases/databases.txt.sample ]]; then\n");
        script.append("    cat <<'SAMPLE' > /opt/cubrid/databases/databases.txt.sample\n");
        script.append("# sample databases.txt for demodb\n");
        script.append("#\n");
        script.append("# db-name vol-path db-host log-path lob-base-path\n");
        script.append("demodb ${CUBRID}/demo localhost ${CUBRID}/demo file:${CUBRID}/demo/lob\n");
        script.append("SAMPLE\n");
        script.append("  fi\n");
        script.append("}\n");
        script.append("trap cleanup_snapshots EXIT\n");
        script.append("log \"Preparing CUBRID installation inside container\"\n");
        script.append("rm -rf /opt/cubrid\n");
        script.append("mkdir -p /opt/cubrid\n");
        script.append("if tar -tf /mnt/build.tar.gz 2>/dev/null | grep -m1 -q '^_install/CUBRID/'; then\n");
        script.append("  log \"Detected packaged _install/CUBRID layout\"\n");
        script.append("  tar -xzf /mnt/build.tar.gz -C /opt/cubrid --strip-components=2\n");
        script.append("  CUBRID_DIR=/opt/cubrid\n");
        script.append("else\n");
        script.append("  log \"Extracting full archive\"\n");
        script.append("  tar -xzf /mnt/build.tar.gz -C /opt/cubrid\n");
        script.append("  CUBRID_DIR=$(find /opt/cubrid -path '*/_install/CUBRID' -type d | head -1)\n");
        script.append("  if [[ -z \"$CUBRID_DIR\" ]]; then\n");
        script.append("    CUBRID_DIR=$(find /opt/cubrid -name 'cubrid_rel' -type f | head -1 | xargs dirname | xargs dirname)\n");
        script.append("  fi\n");
        script.append("  log \"Found CUBRID directory: $CUBRID_DIR\"\n");
        script.append("  if [[ -n \"$CUBRID_DIR\" && \"$CUBRID_DIR\" != \"/opt/cubrid\" && -d \"$CUBRID_DIR\" ]]; then\n");
        script.append("    log \"Syncing CUBRID contents into /opt/cubrid\"\n");
        script.append("    cp -rf \"$CUBRID_DIR\"/* /opt/cubrid/\n");
        script.append("    rm -rf /opt/cubrid/_install\n");
        script.append("    CUBRID_DIR=/opt/cubrid\n");
        script.append("  fi\n");
        script.append("fi\n");
        script.append("CONF_SNAPSHOT=$(snapshot_tree /opt/cubrid/conf)\n");
        script.append("DB_SNAPSHOT=$(snapshot_tree /opt/cubrid/databases)\n");
        script.append("log \"Final CUBRID_DIR=$CUBRID_DIR\"\n");
        script.append("if [[ -f /opt/cubrid/share/scripts/setup.sh ]]; then\n");
        script.append("  log \"Running share/scripts/setup.sh\"\n");
        script.append("  (cd /opt/cubrid && printf 'y\\n' | sh share/scripts/setup.sh /opt/cubrid) || true\n");
        script.append("elif [[ -f /opt/cubrid/setup.sh ]]; then\n");
        script.append("  log \"Running top-level setup.sh\"\n");
        script.append("  (cd /opt/cubrid && printf 'y\\n' | sh setup.sh /opt/cubrid) || true\n");
        script.append("fi\n");
        script.append("restore_tree \"$CONF_SNAPSHOT\" /opt/cubrid/conf conf\n");
        script.append("restore_tree \"$DB_SNAPSHOT\" /opt/cubrid/databases databases\n");
        script.append("ensure_sample_databases\n");
        script.append("for legacy_home in \"$HOME/CUBRID\" \"/root/CUBRID\" \"/home/cubrid-testtools/CUBRID\"; do\n");
        script.append("  if [ -n \"$legacy_home\" ] && [ ! -e \"$legacy_home\" ]; then\n");
        script.append("    legacy_parent=$(dirname \"$legacy_home\")\n");
        script.append("    mkdir -p \"$legacy_parent\" 2>/dev/null || true\n");
        script.append("    ln -s /opt/cubrid \"$legacy_home\" 2>/dev/null || true\n");
        script.append("  fi\n");
        script.append("done\n");
        script.append("HOSTS_CONF=/opt/cubrid/conf/cubrid_hosts.conf\n");
        script.append("if [[ -f \"$HOSTS_CONF\" ]] && ! grep -q \"0.0.0.0\\s\\+your-hostname\" \"$HOSTS_CONF\"; then\n");
        script.append("  printf '0.0.0.0\\t\\tyour-hostname\\n' >> \"$HOSTS_CONF\"\n");
        script.append("fi\n");
        script.append("ls -la /opt/cubrid/bin/ || true\n");
        script.append("/opt/cubrid/bin/cubrid_rel || log \"WARNING: cubrid_rel check failed\"\n");
        return script.toString();
    }
    
    private String sanitizeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
    
    private void runCommand(List<String> command, String logPrefix) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
                if (logPrefix != null && !logPrefix.isEmpty()) {
                    logger.info(logPrefix + " " + line);
                }
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed with exit code " + exitCode + ": " +
                    String.join(" ", command) + "\n" + output);
        }
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
    private void updateCacheEntry(String imageKey, String imageName) {
        imageCache.put(imageKey, imageName);
        imageBuildTime.put(imageKey, System.currentTimeMillis());
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
                        String imageKey = line.substring("cubrid-test:".length());
                        imageCache.put(imageKey, line);
                        imageBuildTime.put(imageKey, System.currentTimeMillis());
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
        String oldestImageKey = null;
        long oldestTime = Long.MAX_VALUE;
        
        for (Map.Entry<String, Long> entry : imageBuildTime.entrySet()) {
            if (entry.getValue() < oldestTime) {
                oldestTime = entry.getValue();
                oldestImageKey = entry.getKey();
            }
        }
        
        if (oldestImageKey != null) {
            String imageName = imageCache.remove(oldestImageKey);
            imageBuildTime.remove(oldestImageKey);
            
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
