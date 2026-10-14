package com.navercorp.cubridqa.builder.logging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Manages cleanup of old logs and tar files based on retention policies.
 * Performs cleanup at the beginning of each build request.
 */
public class LogRotationManager {
    private static final Logger logger = Logger.getLogger(LogRotationManager.class.getName());
    private final LogConfig config;
    private final String metadataPath;
    
    public LogRotationManager(LogConfig config) {
        this.config = config;
        this.metadataPath = config.getLogRootDir() + "/.metadata.json";
    }
    
    /**
     * Perform cleanup of old request logs and tar files
     */
    public void performCleanup(String workDir) {
        performCleanup(workDir, java.util.Collections.emptySet());
    }

    /**
     * @param packagesInUse build packages of running requests; their build directories are kept
     */
    public void performCleanup(String workDir, java.util.Set<String> packagesInUse) {
        try {
            cleanupRequestLogs();
            cleanupTarFiles(workDir);
            cleanupBuildDirectories(workDir, packagesInUse);
            updateMetadata();
        } catch (Exception e) {
            logger.warning("Error during cleanup: " + e.getMessage());
        }
    }
    
    /**
     * Clean up old request logs, keeping only the most recent N
     */
    private void cleanupRequestLogs() throws IOException {
        File requestsDir = new File(config.getRequestsDir());
        if (!requestsDir.exists()) {
            return;
        }
        
        // Get all request directories sorted by creation time
        List<File> requestDirs = Arrays.stream(requestsDir.listFiles())
            .filter(File::isDirectory)
            .sorted((a, b) -> {
                try {
                    BasicFileAttributes attrA = Files.readAttributes(a.toPath(), BasicFileAttributes.class);
                    BasicFileAttributes attrB = Files.readAttributes(b.toPath(), BasicFileAttributes.class);
                    return attrB.creationTime().compareTo(attrA.creationTime());
                } catch (IOException e) {
                    return 0;
                }
            })
            .collect(Collectors.toList());
        
        // Keep only the most recent N request directories
        if (requestDirs.size() > config.getMaxRequestLogs()) {
            List<File> toDelete = requestDirs.subList(config.getMaxRequestLogs(), requestDirs.size());
            for (File dir : toDelete) {
                deleteDirectory(dir);
                logger.info("Deleted old request log directory: " + dir.getName());
            }
        }
    }
    
    /**
     * Clean up old tar files in the work directory
     */
    private void cleanupTarFiles(String workDir) {
        if (workDir == null || workDir.isEmpty()) {
            return;
        }
        
        File workDirFile = new File(workDir);
        if (!workDirFile.exists()) {
            return;
        }
        
        // Find all tar.gz files
        File[] tarFiles = workDirFile.listFiles((dir, name) -> 
            name.endsWith(".tar.gz") || name.endsWith(".tar"));
        
        if (tarFiles == null || tarFiles.length <= config.getMaxTarFiles()) {
            return;
        }
        
        // Sort by modification time (newest first)
        Arrays.sort(tarFiles, (a, b) -> 
            Long.compare(b.lastModified(), a.lastModified()));
        
        // Delete old tar files
        for (int i = config.getMaxTarFiles(); i < tarFiles.length; i++) {
            try {
                if (tarFiles[i].delete()) {
                    logger.info("Deleted old tar file: " + tarFiles[i].getName());
                } else {
                    // Try privileged deletion
                    logger.info("Normal deletion failed for tar file: " + tarFiles[i].getName() + ", trying sudo");
                    try {
                        String path = tarFiles[i].getAbsolutePath();
                        // Validate path to prevent command injection
                        if (path.contains(";") || path.contains("|") || path.contains("&") || path.contains("`")) {
                            logger.warning("Refusing to delete path with suspicious characters: " + path);
                            continue;
                        }
                        
                        ProcessBuilder pb = new ProcessBuilder("sudo", "-n", "rm", "-f", path);
                        pb.redirectErrorStream(true);
                        Process p = pb.start();
                        int exitCode = p.waitFor();
                        
                        if (exitCode == 0) {
                            logger.info("Privileged deletion succeeded for tar file: " + tarFiles[i].getName());
                        } else {
                            logger.warning("Privileged deletion failed for tar file: " + tarFiles[i].getName());
                        }
                    } catch (Exception privEx) {
                        logger.warning("Failed privileged deletion for tar file " + tarFiles[i].getName() + ": " + privEx.getMessage());
                    }
                }
            } catch (SecurityException e) {
                logger.warning("Permission denied deleting tar file: " + tarFiles[i].getName());
            }
        }
    }
    
    // A build directory this recent may belong to a build that is still running
    private static final long BUILD_DIR_MIN_AGE_MS = 6L * 60 * 60 * 1000;

    /**
     * Keeps the newest max_tar_files build directories (build_*&#47;cubrid_*.tar.gz, where the Builder
     * stores packages). cleanupTarFiles only looks at tar files directly in work_dir, where none
     * are, so packages were never rotated (285 build directories, 72GB). Directories holding a
     * package of a running request, or modified in the last 6 hours, are kept.
     */
    private void cleanupBuildDirectories(String workDir, java.util.Set<String> packagesInUse) {
        if (workDir == null || workDir.isEmpty()) {
            return;
        }
        File[] buildDirs = new File(workDir).listFiles(f -> f.isDirectory() && f.getName().startsWith("build_")
            && hasPackage(f));
        if (buildDirs == null || buildDirs.length <= config.getMaxTarFiles()) {
            return;
        }
        java.util.Set<String> inUseDirs = new java.util.HashSet<>();
        for (String pkg : packagesInUse) {
            File parent = new File(pkg).getAbsoluteFile().getParentFile();
            if (parent != null) {
                inUseDirs.add(parent.getAbsolutePath());
            }
        }
        Arrays.sort(buildDirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long now = System.currentTimeMillis();
        for (int i = config.getMaxTarFiles(); i < buildDirs.length; i++) {
            File dir = buildDirs[i];
            if (inUseDirs.contains(dir.getAbsolutePath()) || now - dir.lastModified() < BUILD_DIR_MIN_AGE_MS) {
                continue;
            }
            try {
                deleteDirectory(dir);
                logger.info("Deleted old build directory: " + dir.getName());
            } catch (Exception e) {
                logger.warning("Failed to delete build directory " + dir.getName() + ": " + e.getMessage());
            }
        }
    }

    private static boolean hasPackage(File dir) {
        String[] packages = dir.list((d, name) -> name.startsWith("cubrid_") && name.endsWith(".tar.gz"));
        return packages != null && packages.length > 0;
    }

    /**
     * Update metadata file with current request information
     */
    private void updateMetadata() {
        try {
            JSONObject metadata = new JSONObject();
            metadata.put("lastCleanup", Instant.now().toString());
            metadata.put("maxRequestLogs", config.getMaxRequestLogs());
            metadata.put("maxTarFiles", config.getMaxTarFiles());
            
            // Track current request directories
            File requestsDir = new File(config.getRequestsDir());
            if (requestsDir.exists()) {
                JSONArray requests = new JSONArray();
                File[] dirs = requestsDir.listFiles(File::isDirectory);
                if (dirs != null) {
                    for (File dir : dirs) {
                        requests.put(dir.getName());
                    }
                }
                metadata.put("currentRequests", requests);
            }
            
            Files.write(Paths.get(metadataPath), metadata.toString(2).getBytes());
        } catch (Exception e) {
            logger.warning("Failed to update metadata: " + e.getMessage());
        }
    }
    
    /**
     * Record a new request in metadata
     */
    public void recordRequest(String requestId, JSONObject requestData) {
        try {
            Path requestDir = RequestIds.resolveUnder(Paths.get(config.getRequestsDir()), requestId);
            Files.createDirectories(requestDir);
            
            // Save request data
            Path requestFile = requestDir.resolve("request.json");
            Files.write(requestFile, requestData.toString(2).getBytes());
            
            logger.info("Recorded new request: " + requestId);
        } catch (Exception e) {
            logger.warning("Failed to record request: " + e.getMessage());
        }
    }

    /**
     * Delete a specific request log directory
     */
    public boolean deleteRequestLogDir(String requestId) {
        if (!RequestIds.isValid(requestId)) {
            return false;
        }
        try {
            File dir = RequestIds.resolveUnder(Paths.get(config.getRequestsDir()), requestId).toFile();
            if (!dir.exists()) {
                return false;
            }
            deleteDirectory(dir);
            logger.info("Deleted request log directory: " + dir.getName());
            return true;
        } catch (Exception e) {
            logger.warning("Failed to delete request log directory for " + requestId + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Recursively delete a directory
     */
    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        dir.delete();
    }
}
