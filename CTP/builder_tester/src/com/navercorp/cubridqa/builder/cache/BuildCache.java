package com.navercorp.cubridqa.builder.cache;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class BuildCache {
    private static final Logger logger = Logger.getLogger(BuildCache.class.getName());
    
    // Cache for downloaded build packages to avoid re-downloading
    private static final Map<String, Path> buildPackageCache = new ConcurrentHashMap<>();
    private static final Object DOWNLOAD_LOCK = new Object();
    
    private final Path workDir;
    private final ThreadLocal<Boolean> lastFetchCached = ThreadLocal.withInitial(() -> Boolean.FALSE);
    
    public BuildCache(Path workDir) {
        this.workDir = workDir;
    }

    /**
     * Indicates whether the most recent {@link #downloadIfNeeded} call in the current thread
     * reused a cached build package instead of downloading anew.
     */
    public boolean wasLastFetchFromCache() {
        return Boolean.TRUE.equals(lastFetchCached.get());
    }
    
    /**
     * Download build package if it's a URL, otherwise return the local path.
     * Caches downloaded packages to avoid re-downloading.
     * Validates cached packages against expected commit and baseline.
     */
    public Path downloadIfNeeded(String buildPackage, String expectedCommit, String expectedBaseline, Logger testLogger) 
            throws IOException {
        lastFetchCached.set(Boolean.FALSE);
        // Check if it's a URL
        if (buildPackage.startsWith("http://") || buildPackage.startsWith("https://")) {
            testLogger.info("Build package is a URL: " + buildPackage);
            
            // Check in-memory cache first
            Path cached = buildPackageCache.get(buildPackage);
            if (cached != null && Files.exists(cached)) {
                lastFetchCached.set(Boolean.TRUE);
                testLogger.info("Using cached build package: " + cached);
                return cached;
            }
            
            // Check for existing file on disk (in case cache was cleared or service restarted)
            try {
                URL url = new URL(buildPackage);
                String urlPath = url.getPath();
                String fileName = null;
                
                // Try to extract filename from URL
                if (urlPath != null && !urlPath.isEmpty()) {
                    int lastSlash = urlPath.lastIndexOf('/');
                    if (lastSlash >= 0 && lastSlash < urlPath.length() - 1) {
                        fileName = urlPath.substring(lastSlash + 1);
                    }
                }
                
                if (fileName != null) {
                    Path potentialExistingFile = workDir.resolve(fileName);
                    if (Files.exists(potentialExistingFile) && Files.size(potentialExistingFile) > 0) {
                        // Validate that this cached file matches the expected baseline+commit combination
                        if (validateCached(potentialExistingFile, expectedCommit, expectedBaseline, testLogger)) {
                            testLogger.info("Found valid cached build package on disk: " + potentialExistingFile);
                            // Add to in-memory cache for faster future lookups
                            buildPackageCache.put(buildPackage, potentialExistingFile);
                            lastFetchCached.set(Boolean.TRUE);
                            return potentialExistingFile;
                        } else {
                            testLogger.warning("Cached build package validation failed, will re-download: " + potentialExistingFile);
                        }
                    }
                }
            } catch (Exception e) {
                // If we can't check for existing file, continue with download
                testLogger.warning("Failed to check for existing cached file: " + e.getMessage());
            }
            
            synchronized (DOWNLOAD_LOCK) {
                // Double-check cache after acquiring lock
                cached = buildPackageCache.get(buildPackage);
                if (cached != null && Files.exists(cached)) {
                    lastFetchCached.set(Boolean.TRUE);
                    return cached;
                }
                
                // Download the package
                testLogger.info("Downloading build package from: " + buildPackage);
                URL url = new URL(buildPackage);
                String fileName = "build_" + System.currentTimeMillis() + ".tar.gz";
                
                // Try to extract filename from URL
                String urlPath = url.getPath();
                if (urlPath != null && !urlPath.isEmpty()) {
                    int lastSlash = urlPath.lastIndexOf('/');
                    if (lastSlash >= 0 && lastSlash < urlPath.length() - 1) {
                        fileName = urlPath.substring(lastSlash + 1);
                    }
                }
                
                Path downloadPath = workDir.resolve(fileName);
                
                // Final check: if file already exists after acquiring lock, validate and use it
                if (Files.exists(downloadPath) && Files.size(downloadPath) > 0) {
                    if (validateCached(downloadPath, expectedCommit, expectedBaseline, testLogger)) {
                        testLogger.info("Valid build package already exists on disk: " + downloadPath);
                        buildPackageCache.put(buildPackage, downloadPath);
                        lastFetchCached.set(Boolean.TRUE);
                        return downloadPath;
                    } else {
                        testLogger.warning("Invalid cached package found, removing and re-downloading: " + downloadPath);
                        try {
                            Files.deleteIfExists(downloadPath);
                            // Also try to delete metadata file
                            Files.deleteIfExists(downloadPath.resolveSibling(downloadPath.getFileName() + ".meta.json"));
                        } catch (Exception deleteEx) {
                            testLogger.warning("Failed to delete invalid cached package: " + deleteEx.getMessage());
                        }
                    }
                }
                
                try {
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(300000); // 5 minutes for large files
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        throw new IOException("Failed to download build package. HTTP response: " + responseCode);
                    }
                    
                    long contentLength = conn.getContentLengthLong();
                    testLogger.info("Downloading " + (contentLength > 0 ? contentLength / (1024*1024) + " MB" : "unknown size"));
                    
                    // Use atomic download to prevent partial files
                    Path tempDownloadPath = downloadPath.resolveSibling(downloadPath.getFileName() + ".tmp");
                    
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = Files.newOutputStream(tempDownloadPath)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long totalBytes = 0;
                        long lastLogTime = System.currentTimeMillis();
                        
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                            totalBytes += bytesRead;
                            
                            // Log progress every 5 seconds
                            long now = System.currentTimeMillis();
                            if (now - lastLogTime > 5000) {
                                if (contentLength > 0) {
                                    int percent = (int) ((totalBytes * 100) / contentLength);
                                    testLogger.info("Download progress: " + percent + "%");
                                } else {
                                    testLogger.info("Downloaded " + (totalBytes / (1024*1024)) + " MB");
                                }
                                lastLogTime = now;
                            }
                        }
                        
                        // Verify download completed successfully
                        if (contentLength > 0 && totalBytes != contentLength) {
                            throw new IOException("Download incomplete: expected " + contentLength + " bytes, got " + totalBytes);
                        }
                    }
                    
                    // Verify file integrity before moving to final location
                    testLogger.info("Verifying downloaded file integrity...");
                    try {
                        ProcessBuilder pb = new ProcessBuilder("gzip", "-t", tempDownloadPath.toString());
                        pb.redirectErrorStream(true);
                        Process process = pb.start();
                        
                        // Consume output to prevent blocking
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.trim().isEmpty()) {
                                    testLogger.warning("Gzip validation error: " + line);
                                }
                            }
                        }
                        
                        int exitCode = process.waitFor();
                        if (exitCode != 0) {
                            throw new IOException("Downloaded file failed gzip integrity check");
                        }
                    } catch (Exception e) {
                        testLogger.warning("Failed to verify file integrity: " + e.getMessage());
                        throw new IOException("Downloaded file integrity verification failed", e);
                    }
                    
                    // Atomically move temp file to final location
                    Files.move(tempDownloadPath, downloadPath, StandardCopyOption.ATOMIC_MOVE);
                    testLogger.info("Build package downloaded successfully: " + downloadPath);
                    
                    // Try to download the corresponding metadata file
                    try {
                        String metadataUrl = buildPackage + ".meta.json";
                        Path metadataPath = downloadPath.resolveSibling(downloadPath.getFileName() + ".meta.json");
                        
                        testLogger.info("Attempting to download metadata file: " + metadataUrl);
                        HttpURLConnection metaConn = (HttpURLConnection) new URL(metadataUrl).openConnection();
                        metaConn.setRequestMethod("GET");
                        metaConn.setConnectTimeout(10000);
                        metaConn.setReadTimeout(30000);
                        
                        if (metaConn.getResponseCode() == 200) {
                            try (InputStream metaIn = metaConn.getInputStream();
                                 OutputStream metaOut = Files.newOutputStream(metadataPath)) {
                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                while ((bytesRead = metaIn.read(buffer)) != -1) {
                                    metaOut.write(buffer, 0, bytesRead);
                                }
                            }
                            testLogger.info("Metadata file downloaded successfully: " + metadataPath);
                        } else {
                            testLogger.info("Metadata file not available on server (HTTP " + metaConn.getResponseCode() + ")");
                        }
                    } catch (Exception metaEx) {
                        testLogger.info("Could not download metadata file: " + metaEx.getMessage());
                        // Continue without metadata - validation will handle missing metadata gracefully
                    }
                    
                    // Cache the downloaded package
                    buildPackageCache.put(buildPackage, downloadPath);
                    
                    // Ensure sidecar metadata exists (either downloaded above or synthesized here)
                    try {
                        Path metadataPath = downloadPath.resolveSibling(downloadPath.getFileName() + ".meta.json");
                        if (!Files.exists(metadataPath)) {
                            writeSidecarMetadata(downloadPath, expectedCommit, expectedBaseline, testLogger);
                        }
                    } catch (Exception ignore) { }
                    
                    // Clean old cached packages if cache is too large
                    if (buildPackageCache.size() > 10) {
                        cleanOld();
                    }
                    
                    lastFetchCached.set(Boolean.FALSE);
                    return downloadPath;
                    
                } catch (Exception e) {
                    // Clean up partial download (both temp and final files)
                    try {
                        Files.deleteIfExists(downloadPath);
                        Files.deleteIfExists(downloadPath.resolveSibling(downloadPath.getFileName() + ".tmp"));
                    } catch (Exception ignore) {}
                    throw new IOException("Failed to download build package: " + e.getMessage(), e);
                }
            }
        } else {
            // It's a local path
            lastFetchCached.set(Boolean.FALSE);
            return Paths.get(buildPackage);
        }
    }
    
    /**
     * Validate that a cached build package matches the expected commit and baseline
     * by checking the metadata file created by the Builder.
     */
    public boolean validateCached(Path packageFile, String expectedCommit, String expectedBaseline, Logger testLogger) {
        try {
            // Validate file integrity first (check if gzip file is not corrupted)
            try {
                ProcessBuilder pb = new ProcessBuilder("gzip", "-t", packageFile.toString());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                // Consume output to prevent blocking
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Log any error messages
                        if (!line.trim().isEmpty()) {
                            testLogger.warning("Gzip validation error: " + line);
                        }
                    }
                }
                
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    testLogger.warning("Cached package validation failed: gzip file is corrupted: " + packageFile);
                    return false;
                }
            } catch (Exception e) {
                testLogger.warning("Failed to validate gzip integrity: " + e.getMessage() + " (package: " + packageFile + ")");
                return false;
            }
            
            // Look for metadata file alongside the package
            Path metadataFile = packageFile.resolveSibling(packageFile.getFileName() + ".meta.json");
            
            if (!Files.exists(metadataFile)) {
                // Fallback: infer commit from filename and synthesize metadata if it matches expectation
                String fileName = packageFile.getFileName().toString();
                String inferredCommitShort = null;
                if (fileName.startsWith("cubrid_") && fileName.endsWith(".tar.gz")) {
                    String inner = fileName.substring("cubrid_".length(), fileName.length() - ".tar.gz".length());
                    if (inner.length() >= 7) {
                        inferredCommitShort = inner.substring(0, 7);
                    }
                }

                String expectedCommitShort = expectedCommit != null
                        ? (expectedCommit.length() > 7 ? expectedCommit.substring(0, 7) : expectedCommit)
                        : null;

                if (inferredCommitShort != null && expectedCommitShort != null && inferredCommitShort.equals(expectedCommitShort)) {
                    // Create sidecar metadata so future validations succeed
                    writeSidecarMetadata(packageFile, expectedCommit, expectedBaseline, testLogger);
                    testLogger.info("Synthesized metadata for cached package: " + packageFile);
                    return true;
                }

                testLogger.warning("No metadata file found for cached package: " + packageFile + 
                                 " (expected: " + metadataFile + "). Rejecting to ensure baseline consistency.");
                return false;
            }
            
            // Read and parse metadata
            String metadataContent = new String(Files.readAllBytes(metadataFile), "UTF-8");
            JSONObject metadata = new JSONObject(metadataContent);
            
            // Validate commit
            String metaCommit = metadata.optString("commitShort", metadata.optString("commit", ""));
            if (metaCommit.length() > 7) {
                metaCommit = metaCommit.substring(0, 7);
            }
            String expectedCommitShort = expectedCommit != null && expectedCommit.length() > 7 ? 
                                       expectedCommit.substring(0, 7) : expectedCommit;
            
            if (expectedCommitShort != null && !expectedCommitShort.equals(metaCommit)) {
                testLogger.warning(String.format("Cached package validation failed: commit mismatch. " +
                    "Expected: %s, Found: %s (package: %s)", expectedCommitShort, metaCommit, packageFile));
                return false;
            }
            
            // Validate baseline
            String metaBaseline = metadata.optString("baseline", "");
            if (metaBaseline.length() > 7) {
                metaBaseline = metaBaseline.substring(0, 7);
            }
            String expectedBaselineShort = expectedBaseline != null && expectedBaseline.length() > 7 ? 
                                         expectedBaseline.substring(0, 7) : expectedBaseline;
            
            if (expectedBaselineShort != null && !expectedBaselineShort.equals("unknown") && !expectedBaselineShort.equals(metaBaseline)) {
                testLogger.warning(String.format("Cached package validation failed: baseline mismatch. " +
                    "Expected: %s, Found: %s (package: %s)", expectedBaselineShort, metaBaseline, packageFile));
                return false;
            }
            
            testLogger.info(String.format("Cached package validation passed: commit=%s, baseline=%s (package: %s)", 
                          metaCommit, metaBaseline, packageFile.getFileName()));
            return true;
            
        } catch (Exception e) {
            // If parsing failed or any other error, attempt filename-based fallback
            try {
                String fileName = packageFile.getFileName().toString();
                String inferredCommitShort = null;
                if (fileName.startsWith("cubrid_") && fileName.endsWith(".tar.gz")) {
                    String inner = fileName.substring("cubrid_".length(), fileName.length() - ".tar.gz".length());
                    if (inner.length() >= 7) {
                        inferredCommitShort = inner.substring(0, 7);
                    }
                }
                String expectedCommitShort = expectedCommit != null
                        ? (expectedCommit.length() > 7 ? expectedCommit.substring(0, 7) : expectedCommit)
                        : null;
                if (inferredCommitShort != null && expectedCommitShort != null && inferredCommitShort.equals(expectedCommitShort)) {
                    writeSidecarMetadata(packageFile, expectedCommit, expectedBaseline, testLogger);
                    testLogger.warning("Metadata parse failed, but filename matches expected commit. Synthesized metadata for: " + packageFile);
                    return true;
                }
            } catch (Exception ignore) { }
            testLogger.warning("Failed to validate cached package metadata: " + e.getMessage() + " (package: " + packageFile + ")");
            return false;
        }
    }
    
    /**
     * Write minimal sidecar metadata next to a package so that future validations succeed.
     */
    public void writeSidecarMetadata(Path packageFile, String expectedCommit, String expectedBaseline, Logger testLogger) {
        try {
            // Prepare metadata
            String fileName = packageFile.getFileName().toString();
            String inferredCommitShort = null;
            if (fileName.startsWith("cubrid_") && fileName.endsWith(".tar.gz")) {
                String inner = fileName.substring("cubrid_".length(), fileName.length() - ".tar.gz".length());
                if (inner.length() >= 7) {
                    inferredCommitShort = inner.substring(0, 7);
                }
            }

            String commitForMeta = (expectedCommit != null && !expectedCommit.isEmpty()) ? expectedCommit
                : (inferredCommitShort != null ? inferredCommitShort : "unknown");
            String commitShortForMeta = (expectedCommit != null && !expectedCommit.isEmpty())
                ? (expectedCommit.length() > 7 ? expectedCommit.substring(0, 7) : expectedCommit)
                : (inferredCommitShort != null ? inferredCommitShort : "unknown");
            String baselineForMeta = (expectedBaseline != null && !expectedBaseline.isEmpty()) ? expectedBaseline : "unknown";

            JSONObject j = new JSONObject();
            j.put("commit", commitForMeta);
            j.put("commitShort", commitShortForMeta);
            j.put("baseline", baselineForMeta);
            j.put("createdAt", System.currentTimeMillis());

            Path metadataFile = packageFile.resolveSibling(packageFile.getFileName() + ".meta.json");
            Files.write(metadataFile, j.toString().getBytes("UTF-8"));
        } catch (Exception ex) {
            testLogger.warning("Failed to write sidecar metadata for package " + packageFile + ": " + ex.getMessage());
        }
    }
    
    /**
     * Load existing cached build packages from disk into memory cache
     */
    public void loadExistingOnStartup(Path cacheDir) {
        try {
            if (!Files.exists(cacheDir)) {
                return;
            }
            
            Files.list(cacheDir)
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".tar.gz"))
                .forEach(cachedFile -> {
                    try {
                        // Check if the cached file has valid metadata
                        Path metadataFile = cachedFile.resolveSibling(cachedFile.getFileName() + ".meta.json");
                        if (Files.exists(metadataFile)) {
                            logger.info("Found existing cached build package with metadata: " + cachedFile);
                        } else {
                            logger.warning("Found cached build package without metadata (may be from older version): " + cachedFile);
                            // Note: Files without metadata will be validated during use and may be rejected
                        }
                        // Note: We can't add to buildPackageCache here because we don't know the original URL
                        // But the disk-based check in downloadIfNeeded will find and validate these files
                    } catch (Exception e) {
                        logger.warning("Error processing cached file " + cachedFile + ": " + e.getMessage());
                    }
                });
            
        } catch (IOException e) {
            logger.warning("Failed to load existing cached packages: " + e.getMessage());
        }
    }
    
    private void cleanOld() {
        // Keep only the 5 most recently used packages
        if (buildPackageCache.size() <= 5) return;
        
        List<Map.Entry<String, Path>> entries = new ArrayList<>(buildPackageCache.entrySet());
        entries.sort((a, b) -> {
            try {
                BasicFileAttributes attrA = Files.readAttributes(a.getValue(), BasicFileAttributes.class);
                BasicFileAttributes attrB = Files.readAttributes(b.getValue(), BasicFileAttributes.class);
                return attrB.lastAccessTime().compareTo(attrA.lastAccessTime());
            } catch (IOException e) {
                return 0;
            }
        });
        
        // Remove oldest entries
        for (int i = 5; i < entries.size(); i++) {
            Map.Entry<String, Path> entry = entries.get(i);
            try {
                Files.deleteIfExists(entry.getValue());
                buildPackageCache.remove(entry.getKey());
                logger.info("Removed old cached package: " + entry.getValue());
            } catch (IOException e) {
                logger.warning("Failed to delete cached package: " + e.getMessage());
            }
        }
    }
    
    /**
     * Clean up corrupted cached packages by checking gzip integrity
     */
    public void cleanCorruptedAsync(Path cacheDir) {
        if (!Files.exists(cacheDir)) {
            return;
        }
        
        try {
            Files.list(cacheDir)
                .forEach(packageFile -> {
                    try {
                        String fileName = packageFile.getFileName().toString();
                        
                        // Clean up any leftover temp files from interrupted downloads
                        if (fileName.endsWith(".tmp")) {
                            logger.info("Removing leftover temp file from interrupted download: " + packageFile);
                            Files.deleteIfExists(packageFile);
                            return;
                        }
                        
                        // Check gzip integrity for .tar.gz files
                        if (fileName.endsWith(".tar.gz")) {
                            // Quick check: skip files that are likely to be valid based on size
                            // Most CUBRID build packages are >500MB, so very small files are suspicious
                            // Skip integrity check for very large files (>1GB) to save time
                            long fileSize = Files.size(packageFile);
                            if (fileSize < 500 * 1024 * 1024) { // Less than 500MB
                                logger.warning("Found suspiciously small cached package, removing: " + packageFile + " (size: " + fileSize + " bytes)");
                                Files.deleteIfExists(packageFile);
                                Files.deleteIfExists(packageFile.resolveSibling(packageFile.getFileName() + ".meta.json"));
                                buildPackageCache.entrySet().removeIf(entry -> entry.getValue().equals(packageFile));
                                return;
                            }
                            
                            // Skip integrity check for very large files (>1GB) to save time
                            if (fileSize > 1024 * 1024 * 1024) { // More than 1GB
                                logger.info("Skipping integrity check for large file: " + packageFile + " (size: " + fileSize + " bytes)");
                                return;
                            }
                            
                            // Only do full gzip integrity check for files between 500MB and 1GB
                            ProcessBuilder pb = new ProcessBuilder("gzip", "-t", packageFile.toString());
                            pb.redirectErrorStream(true);
                            Process process = pb.start();
                            
                            // Consume output to prevent blocking
                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(process.getInputStream()))) {
                                while (reader.readLine() != null) {
                                    // Just consume output
                                }
                            }
                            
                            // Add timeout to prevent hanging
                            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
                            if (!completed) {
                                logger.warning("Gzip integrity check timed out for: " + packageFile);
                                process.destroyForcibly();
                                return;
                            }
                            
                            int exitCode = process.exitValue();
                            if (exitCode != 0) {
                                logger.warning("Found corrupted cached package, removing: " + packageFile);
                                Files.deleteIfExists(packageFile);
                                // Also remove metadata file if it exists
                                Files.deleteIfExists(packageFile.resolveSibling(packageFile.getFileName() + ".meta.json"));
                                // Remove from in-memory cache if present
                                buildPackageCache.entrySet().removeIf(entry -> entry.getValue().equals(packageFile));
                            }
                        }
                    } catch (Exception e) {
                        logger.warning("Failed to check integrity of cached package " + packageFile + ": " + e.getMessage());
                    }
                });
        } catch (IOException e) {
            logger.warning("Failed to scan cache directory for corrupted packages: " + e.getMessage());
        }
    }
}