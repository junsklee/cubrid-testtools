package com.navercorp.cubridqa.builder.exec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class CubridInstaller {
    
    public Path install(String buildPackage, Path workDir, Logger testLogger) 
            throws IOException, InterruptedException {
        testLogger.info("Installing CUBRID from: " + buildPackage);
        
        Path cubridInstallDir = Paths.get(System.getProperty("user.home"), "CUBRID");
        
        // Remove existing installation
        if (Files.exists(cubridInstallDir)) {
            testLogger.info("Removing existing CUBRID installation");
            deleteDirectory(cubridInstallDir.toFile());
        }
        
        // Create new installation directory
        Files.createDirectories(cubridInstallDir);
        
        // Extract the build package
        testLogger.info("Extracting to: " + cubridInstallDir);
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", buildPackage, 
                                              "-C", cubridInstallDir.toString());
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        ProcessIO.StreamReader outputGobbler = new ProcessIO.StreamReader(process.getInputStream(), "EXTRACT");
        outputGobbler.start();
        
        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("CUBRID extraction timeout");
        }
        
        int exitCode = process.exitValue();
        outputGobbler.join(1000);
        
        if (exitCode != 0) {
            String output = outputGobbler.getOutput();
            throw new IOException("CUBRID extraction failed: " + exitCode);
        }
        
        testLogger.info("CUBRID extraction completed");
        
        // Find actual CUBRID directory
        Path actualCubridDir = findCubridBinaries(cubridInstallDir);
        
        if (actualCubridDir == null) {
            throw new IOException("Could not find CUBRID binaries");
        }
        
        testLogger.info("CUBRID binaries found at: " + actualCubridDir);
        
        // Try running setup.sh if available to configure installation
        try {
            Path setupScript = actualCubridDir.resolve("share/scripts/setup.sh");
            if (!Files.exists(setupScript)) {
                setupScript = actualCubridDir.resolve("setup.sh");
            }
            if (Files.exists(setupScript)) {
                testLogger.info("Running setup.sh to configure CUBRID installation");
                ProcessBuilder setupPb = new ProcessBuilder("sh", setupScript.toString(), actualCubridDir.toString());
                setupPb.directory(actualCubridDir.toFile());
                setupPb.redirectErrorStream(true);
                Map<String, String> setupEnv = setupPb.environment();
                setupEnv.put("CUBRID", actualCubridDir.toString());
                setupEnv.put("CUBRID_DATABASES", actualCubridDir.resolve("databases").toString());
                setupEnv.put("PATH", actualCubridDir.resolve("bin") + ":" + System.getenv("PATH"));
                setupEnv.put("LD_LIBRARY_PATH", actualCubridDir.resolve("lib") + ":" +
                        System.getenv().getOrDefault("LD_LIBRARY_PATH", ""));
                Process setupProc = setupPb.start();
                ProcessIO.StreamReader setupOutput = new ProcessIO.StreamReader(setupProc.getInputStream(), "SETUP");
                setupOutput.start();
                boolean setupCompleted = setupProc.waitFor(2, TimeUnit.MINUTES);
                if (!setupCompleted) {
                    setupProc.destroyForcibly();
                    testLogger.warning("setup.sh timeout after 2 minutes, continuing anyway");
                } else if (setupProc.exitValue() != 0) {
                    setupOutput.join(1000);
                    testLogger.warning("setup.sh exited with code: " + setupProc.exitValue());
                } else {
                    testLogger.info("setup.sh completed successfully");
                }
            } else {
                testLogger.info("setup.sh not found, skipping setup");
            }
        } catch (Exception e) {
            testLogger.warning("setup.sh execution failed: " + e.getMessage());
        }

        // Ensure databases directory exists
        Path databasesDir = actualCubridDir.resolve("databases");
        if (!Files.exists(databasesDir)) {
            Files.createDirectories(databasesDir);
        }
        
        return actualCubridDir;
    }
    
    private Path findCubridBinaries(Path extractionDir) throws IOException {
        Path[] candidatePaths = {
            extractionDir,
            extractionDir.resolve("CUBRID"),
            extractionDir.resolve("cubrid"),
            extractionDir.resolve("install")
        };
        
        for (Path candidate : candidatePaths) {
            if (Files.exists(candidate)) {
                Path cubridRel = candidate.resolve("bin/cubrid_rel");
                if (Files.exists(cubridRel) && Files.isExecutable(cubridRel)) {
                    return candidate;
                }
            }
        }
        
        // Search in subdirectories
        try (java.util.stream.Stream<Path> paths = Files.walk(extractionDir, 3)) {
            java.util.Optional<Path> cubridRel = paths
                .filter(path -> path.getFileName().toString().equals("cubrid_rel"))
                .filter(Files::isExecutable)
                .findFirst();
                
            if (cubridRel.isPresent()) {
                return cubridRel.get().getParent().getParent();
            }
        }
        
        return null;
    }
    
    /**
     * Recursively delete a directory
     */
    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        
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