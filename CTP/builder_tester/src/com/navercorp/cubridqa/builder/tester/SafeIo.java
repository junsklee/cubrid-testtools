package com.navercorp.cubridqa.builder.tester;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.logging.Logger;
import java.io.BufferedReader;

public class SafeIo {
    private static final Logger logger = Logger.getLogger(SafeIo.class.getName());
    
    public static void copyTestCaseDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(source) || !Files.isDirectory(source)) {
            throw new IOException("Source test directory does not exist: " + source);
        }
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                try {
                    Files.createDirectories(targetDir);
                } catch (IOException e) {
                    logger.warning("Failed to create directory: " + targetDir + " - " + e.getMessage());
                    throw e;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                try {
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    // Fail fast on permission errors to surface clear message
                    logger.warning("Failed to copy file: " + file + " - " + e.getMessage());
                    throw e;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                // Propagate AccessDeniedException or any IO error to caller for proper HTTP error response
                logger.warning("Visit failed for " + file + " - " + (exc != null ? exc.getMessage() : "unknown error"));
                throw exc != null ? exc : new IOException("Failed visiting: " + file);
            }
        });
    }
    
    public static void deleteDirectory(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }
    
    /**
     * Delete a directory using privileged deletion by default.
     *
     * @param dir Directory to delete
     * @param logger Logger for error reporting
     */
    public static void deleteDirectoryWithPrivileges(File dir, Logger logger) {
        if (dir == null || !dir.exists()) {
            return;
        }

        try {
            // Validate path to prevent command injection
            String path = dir.getAbsolutePath();
            if (path.contains(";") || path.contains("|") || path.contains("&") || path.contains("`")) {
                logger.warning("Refusing to delete path with suspicious characters: " + path);
                return;
            }

            ProcessBuilder pb = new ProcessBuilder("sudo", "-n", "rm", "-rf", path);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // Read output for logging
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = p.waitFor();

            if (exitCode == 0) {
                logger.info("Privileged deletion succeeded for: " + path);
            } else {
                logger.warning("Privileged deletion failed (exit=" + exitCode + ") for: " + path +
                             (output.length() > 0 ? "\nOutput: " + output.toString() : ""));
            }
        } catch (IOException e) {
            logger.warning("Failed to execute privileged deletion for " + dir.getAbsolutePath() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            logger.warning("Privileged deletion interrupted for " + dir.getAbsolutePath());
            Thread.currentThread().interrupt();
        }
    }
    
    public static void drain(InputStream is) {
        try {
            byte[] buffer = new byte[1024];
            while (is.read(buffer) != -1) {
                // Just consume the stream
            }
        } catch (IOException e) {
            // Ignore errors during drain
        }
    }
    
    public static String escapeShell(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        
        // Simple shell escaping - wrap in single quotes and escape any single quotes
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
