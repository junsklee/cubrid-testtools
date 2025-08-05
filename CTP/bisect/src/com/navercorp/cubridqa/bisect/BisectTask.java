/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.bisect;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * BisectTask - Executes a bisect operation to find the first failing commit
 */
public class BisectTask {
    private static final Logger logger = Logger.getLogger(BisectTask.class.getName());
    
    private final String taskId;
    private final JSONObject request;
    private final BisectConfig config;
    private final List<JSONObject> results;
    
    public BisectTask(String taskId, JSONObject request, BisectConfig config) {
        this.taskId = taskId;
        this.request = request;
        this.config = config;
        this.results = new ArrayList<>();
    }
    
    public void run() {
        logger.info("Starting bisect task: " + taskId);
        long startTime = System.currentTimeMillis();
        
        try {
            // Extract request parameters
            String suspectedStartCommit = request.getString("suspectedStartCommit");
            String suspectedEndCommit = request.getString("suspectedEndCommit");
            String buildType = request.optString("buildType", "debug");
            String workerIp = request.optString("workerIp", "localhost");
            JSONArray tests = request.getJSONArray("tests");
            
            // Ensure CUBRID source repository is set up and updated
            setupCubridRepository();
            
            // Find the parent of suspectedStartCommit to use as the good commit
            String goodCommit = getParentCommit(suspectedStartCommit);
            if (goodCommit == null) {
                throw new RuntimeException("Could not find parent of suspected start commit: " + suspectedStartCommit);
            }
            
            logger.info(String.format("Bisect range: %s (good/parent) -> %s...%s (bad range)", 
                goodCommit, suspectedStartCommit, suspectedEndCommit));
            
            // Run bisect for each test
            for (int i = 0; i < tests.length(); i++) {
                String test = tests.getString(i);
                logger.info("Bisecting test: " + test);
                
                JSONObject result = bisectSingleTest(
                    goodCommit, suspectedEndCommit, buildType, test, workerIp
                );
                results.add(result);
            }
            
            // Send callback with results
            sendCallback();
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Bisect task failed: " + taskId, e);
        }
        
        long duration = System.currentTimeMillis() - startTime;
        logger.info(String.format("Bisect task %s completed in %d seconds", 
            taskId, duration / 1000));
    }
    
    private String getParentCommit(String commit) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "git", "rev-parse", commit + "^"
            );
            pb.directory(new File(config.getCubridSrcDir()));
            Process process = pb.start();
            
            StringBuilder parent = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parent.append(line);
                }
            }
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0 && parent.length() > 0) {
                String parentCommit = parent.toString().trim();
                logger.info("Parent of " + commit + " is " + parentCommit);
                return parentCommit;
            }
        } catch (Exception e) {
            logger.warning("Failed to get parent commit: " + e.getMessage());
        }
        return null;
    }
    
    private void setupCubridRepository() throws Exception {
        File srcDir = new File(config.getCubridSrcDir());
        
        if (!srcDir.exists()) {
            logger.info("CUBRID source directory does not exist, cloning repository...");
            // Create parent directory
            srcDir.getParentFile().mkdirs();
            
            // Clone the repository
            ProcessBuilder pb = new ProcessBuilder();
            executeCommand(pb, "git", "clone", "https://github.com/CUBRID/cubrid.git", srcDir.getAbsolutePath());
            logger.info("Successfully cloned CUBRID repository");
        }
        
        // Change to source directory for git operations
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(srcDir);
        
        // Check if it's a git repository
        try {
            executeCommand(pb, "git", "status");
        } catch (Exception e) {
            throw new RuntimeException("Directory exists but is not a git repository: " + srcDir.getAbsolutePath());
        }
        
        // Fetch latest changes
        logger.info("Fetching latest changes from origin...");
        executeCommand(pb, "git", "fetch", "origin");
        
        // Checkout develop branch
        logger.info("Checking out develop branch...");
        try {
            executeCommand(pb, "git", "checkout", "develop");
        } catch (Exception e) {
            // If develop doesn't exist locally, create it from origin/develop
            executeCommand(pb, "git", "checkout", "-b", "develop", "origin/develop");
        }
        
        // Pull latest changes
        logger.info("Pulling latest changes...");
        executeCommand(pb, "git", "pull", "origin", "develop");
        
        // Update submodules
        logger.info("Updating submodules...");
        executeCommand(pb, "git", "submodule", "update", "--init", "--recursive");
        
        logger.info("CUBRID repository setup completed");
    }
    
    private JSONObject bisectSingleTest(String commitFormer, String commitLatter,
                                       String buildType, String testPath, String workerIp) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Create working directory
            File workDir = createTempDirectory();
            logger.info("Working directory: " + workDir.getAbsolutePath());
            
            try {
                // Create judge script
                File judgeScript = createJudgeScript(workDir, testPath, buildType, workerIp);
                
                // Change to source directory
                ProcessBuilder pb = new ProcessBuilder();
                pb.directory(new File(config.getCubridSrcDir()));
                
                // Reset any previous bisect
                executeCommand(pb, "git", "bisect", "reset");
                executeCommand(pb, "git", "submodule", "foreach", "git", "reset", "--hard", "HEAD");
                executeCommand(pb, "git", "submodule", "update");
                
                // Start bisect
                logger.info(String.format("Starting bisect: %s (good) -> %s (bad)", 
                    commitFormer, commitLatter));
                executeCommand(pb, "git", "bisect", "start", commitLatter, commitFormer);
                
                // Run bisect with judge script
                pb.redirectErrorStream(true); // Combine stdout and stderr
                pb.command("git", "bisect", "run", judgeScript.getAbsolutePath());
                Process process = pb.start();
                
                // Capture output
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        logger.info("BISECT: " + line);
                    }
                }
                
                int exitCode = process.waitFor();
                logger.info("Bisect completed with exit code: " + exitCode);
                
                logger.fine("Bisect output:\n" + output.toString());
                
                // Parse result
                return parseBisectOutput(testPath, output.toString(), startTime);
                
            } finally {
                // Cleanup
                try {
                    ProcessBuilder pb = new ProcessBuilder("git", "bisect", "reset");
                    pb.directory(new File(config.getCubridSrcDir()));
                    pb.start().waitFor();
                } catch (Exception e) {
                    logger.warning("Failed to reset bisect: " + e.getMessage());
                }
                
                // Check if we should auto-delete builds
                boolean autoDeleteBuilds = request.optBoolean("autoDeleteBuilds", true);
                if (autoDeleteBuilds) {
                    deleteDirectory(workDir);
                    logger.info("Build files cleaned up for test: " + testPath);
                } else {
                    logger.info("Build files preserved for test: " + testPath + " in: " + workDir.getAbsolutePath());
                }
            }
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error bisecting test: " + testPath, e);
            
            return new JSONObject()
                .put("name", testPath)
                .put("status", "error")
                .put("error", e.getMessage())
                .put("runtimeMs", System.currentTimeMillis() - startTime);
        }
    }
    
    private File createJudgeScript(File workDir, String testPath, String buildType, String workerIp) 
            throws IOException {
        // Extract test information
        String tcDir = config.getShellTcDir() + "/" + testPath.substring(0, testPath.lastIndexOf("/"));
        String tcScript = testPath.substring(testPath.lastIndexOf("/") + 1);
        String tcName = tcScript.replace(".sh", "");
        String tcResult = tcName + ".result";
        
        File script = new File(workDir, "judge.sh");
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(script))) {
            writer.println("#!/bin/bash");
            writer.println("set -e  # exit immediately on error");
            writer.println();
            writer.println("echo \"Building CUBRID at commit $(git rev-parse HEAD)\"");
            writer.println("cd " + config.getCubridSrcDir());
            writer.println();
            writer.println("# Reset and update submodules");
            writer.println("git submodule foreach git reset --hard HEAD");
            writer.println("git submodule update");
            writer.println();
            writer.println("# Clean build");
            writer.println("rm -rf cubridmanager/*  # temporary: cubridmanager fails on Rocky 8");
            writer.println("rm -rf " + config.getBuildDir());
            writer.println();
            writer.println("# Build");
            writer.println("./build.sh " + config.getBuildArg());
            writer.println();
            writer.println("# Create build package");
            writer.println("BUILD_PACKAGE=\"" + workDir.getAbsolutePath() + 
                          "/cubrid_$(git rev-parse --short HEAD).tar.gz\"");
            writer.println("cd " + config.getCubridSrcDir() + "/" + config.getBuildDir());
            writer.println("tar czf \"$BUILD_PACKAGE\" .");
            writer.println();
            writer.println("# Send test request to consumer");
            writer.println("echo \"Sending test request to consumer at " + 
                          workerIp + ":" + config.getConsumerPort() + "\"");
            writer.println();
            writer.println("# Create test request JSON");
            writer.println("cat > " + workDir.getAbsolutePath() + "/test_request.json << 'EOF'");
            writer.println("{");
            writer.println("  \"buildPackage\": \"$BUILD_PACKAGE\",");
            writer.println("  \"testPath\": \"" + testPath + "\",");
            writer.println("  \"testDir\": \"" + tcDir + "\",");
            writer.println("  \"testScript\": \"" + tcScript + "\",");
            writer.println("  \"testName\": \"" + tcName + "\"");
            writer.println("}");
            writer.println("EOF");
            writer.println();
            writer.println("# Send request to consumer and get result");
            writer.println("RESPONSE=$(curl -s -X POST -H \"Content-Type: application/json\" \\");
            writer.println("    -d @" + workDir.getAbsolutePath() + "/test_request.json \\");
            writer.println("    http://" + workerIp + ":" + config.getConsumerPort() + "/test)");
            writer.println();
            writer.println("echo \"Consumer response: $RESPONSE\"");
            writer.println();
            writer.println("# Preserve build files if requested");
            boolean autoDeleteBuilds = request.optBoolean("autoDeleteBuilds", true);
            if (!autoDeleteBuilds) {
                writer.println("echo \"Preserving build files for inspection...\"");
                writer.println("# Copy build directory contents to working directory");
                writer.println("cp -r " + config.getCubridSrcDir() + "/" + config.getBuildDir() + "/* " + workDir.getAbsolutePath() + "/ 2>/dev/null || true");
                writer.println("echo \"Build files preserved in working directory: " + workDir.getAbsolutePath() + "\"");
            }
            writer.println();
            writer.println("# Parse result");
            writer.println("if echo \"$RESPONSE\" | grep -q '\"status\":\"fail\"'; then");
            writer.println("    exit 1  # Test failed (bad commit)");
            writer.println("elif echo \"$RESPONSE\" | grep -q '\"status\":\"pass\"'; then");
            writer.println("    exit 0  # Test passed (good commit)");
            writer.println("else");
            writer.println("    echo \"Error: Invalid response from consumer\"");
            writer.println("    exit 128  # Abort bisect");
            writer.println("fi");
        }
        
        // Make executable
        script.setExecutable(true);
        
        return script;
    }
    
    private JSONObject parseBisectOutput(String testPath, String output, long startTime) {
        // Check if we found the first bad commit
        if (output.contains("is the first bad commit")) {
            // Extract commit hash
            Pattern commitPattern = Pattern.compile("([0-9a-f]{40}) is the first bad commit");
            Matcher matcher = commitPattern.matcher(output);
            
            if (matcher.find()) {
                String firstBadCommit = matcher.group(1);
                
                // Get commit author
                String author = getCommitAuthor(firstBadCommit);
                
                return new JSONObject()
                    .put("name", testPath)
                    .put("status", "found")
                    .put("firstBadCommit", firstBadCommit)
                    .put("author", author)
                    .put("runtimeMs", System.currentTimeMillis() - startTime);
            }
        }
        
        // No bad commit found
        return new JSONObject()
            .put("name", testPath)
            .put("status", "error")
            .put("error", "No bad commit found in range")
            .put("runtimeMs", System.currentTimeMillis() - startTime);
    }
    
    private String getCommitAuthor(String commitHash) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "git", "log", "-1", "--format=%an <%ae>", commitHash
            );
            pb.directory(new File(config.getCubridSrcDir()));
            Process process = pb.start();
            
            StringBuilder author = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    author.append(line);
                }
            }
            
            process.waitFor();
            return author.toString().trim();
        } catch (Exception e) {
            logger.warning("Failed to get commit author: " + e.getMessage());
            return "";
        }
    }
    
    private void sendCallback() {
        try {
            // Build response with corrected field names
            JSONObject response = new JSONObject()
                .put("suspectedStartCommit", request.getString("suspectedStartCommit"))
                .put("suspectedEndCommit", request.getString("suspectedEndCommit"))
                .put("workerIp", request.optString("workerIp", "localhost"))
                .put("generatedAt", new Date().toInstant().toString())
                .put("tests", new JSONArray(results));
            
            String callbackUrl = request.getString("callbackUrl");
            logger.info("Sending results to " + callbackUrl);
            
            // Send HTTP POST
            URL url = new URL(callbackUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            
            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream())) {
                writer.write(response.toString());
            }
            
            int responseCode = conn.getResponseCode();
            logger.info("Callback response: " + responseCode);
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to send callback", e);
        }
    }
    
    private void executeCommand(ProcessBuilder pb, String... command) throws IOException, InterruptedException {
        pb.command(command);
        pb.redirectErrorStream(true); // Combine stdout and stderr
        Process process = pb.start();
        
        // Capture output for logging
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        int exitCode = process.waitFor();
        
        // Log command and output for debugging
        logger.info("Command: " + String.join(" ", command));
        if (output.length() > 0) {
            logger.info("Output: " + output.toString().trim());
        }
        
        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
        }
    }
    
    private File createTempDirectory() throws IOException {
        File tempDir = new File(config.getWorkDir(), "bisect_" + System.currentTimeMillis());
        if (!tempDir.mkdirs()) {
            throw new IOException("Failed to create temp directory: " + tempDir);
        }
        return tempDir;
    }
    
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}

