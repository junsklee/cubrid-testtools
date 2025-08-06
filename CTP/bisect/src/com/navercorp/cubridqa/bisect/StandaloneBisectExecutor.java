/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.bisect;

import java.io.*;
import java.util.*;
import java.util.logging.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * StandaloneBisectExecutor - Executes bisect operations in standalone mode
 * 
 * This class performs git bisect operations entirely within a single Docker
 * container that can both build CUBRID and execute tests, eliminating the need
 * for separate producer and consumer nodes.
 */
public class StandaloneBisectExecutor {
    private static final Logger logger = Logger.getLogger(StandaloneBisectExecutor.class.getName());
    
    private final BisectConfig config;
    private final StandaloneDockerManager dockerManager;
    private final ExecutorService executor;
    
    public StandaloneBisectExecutor(BisectConfig config) throws IOException {
        this.config = config;
        this.dockerManager = new StandaloneDockerManager(config);
        this.executor = Executors.newFixedThreadPool(config.getMaxConcurrentBisects());
    }
    
    /**
     * Initialize the standalone executor
     */
    public void initialize() throws IOException, InterruptedException {
        logger.info("Initializing Standalone Bisect Executor...");
        
        // Initialize Docker environment
        dockerManager.initialize();
        
        logger.info("Standalone Bisect Executor initialized successfully");
    }
    
    /**
     * Execute a bisect request in standalone mode
     */
    public CompletableFuture<JSONObject> executeBisect(JSONObject request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performBisect(request);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Bisect execution failed", e);
                return createErrorResponse(e);
            }
        }, executor);
    }
    
    /**
     * Perform the actual bisect operation
     */
    private JSONObject performBisect(JSONObject request) throws Exception {
        // Extract request parameters
        String suspectedStartCommit = request.getString("suspectedStartCommit");
        String suspectedEndCommit = request.getString("suspectedEndCommit");
        String buildType = request.optString("buildType", "debug");
        JSONArray tests = request.getJSONArray("tests");
        
        logger.info(String.format("Starting standalone bisect: %s...%s with %d tests",
            suspectedStartCommit, suspectedEndCommit, tests.length()));
        
        // Setup CUBRID repository
        setupCubridRepository();
        
        // Find the parent of suspectedStartCommit as the good commit
        String goodCommit = getParentCommit(suspectedStartCommit);
        if (goodCommit == null) {
            throw new RuntimeException("Could not find parent of suspected start commit: " + suspectedStartCommit);
        }
        
        JSONArray results = new JSONArray();
        
        // Bisect each test
        for (int i = 0; i < tests.length(); i++) {
            String testPath = tests.getString(i);
            logger.info("Bisecting test " + (i + 1) + "/" + tests.length() + ": " + testPath);
            
            JSONObject testResult = bisectSingleTest(
                goodCommit, suspectedEndCommit, buildType, testPath
            );
            results.put(testResult);
        }
        
        // Create response
        JSONObject response = new JSONObject();
        response.put("status", "completed");
        response.put("mode", "standalone");
        response.put("results", results);
        response.put("summary", createSummary(results));
        
        return response;
    }
    
    /**
     * Bisect a single test to find the first failing commit
     */
    private JSONObject bisectSingleTest(String goodCommit, String badCommit, 
                                       String buildType, String testPath) throws Exception {
        
        logger.info(String.format("Bisecting test %s: %s (good) -> %s (bad)",
            testPath, goodCommit.substring(0, 7), badCommit.substring(0, 7)));
        
        // Create working directory
        File workDir = new File(config.getWorkDir(), "bisect_" + System.currentTimeMillis());
        workDir.mkdirs();
        
        try {
            // Run git bisect using standalone Docker execution
            String firstBadCommit = runGitBisect(goodCommit, badCommit, buildType, testPath, workDir);
            
            // Get commit details
            JSONObject commitDetails = getCommitDetails(firstBadCommit);
            
            // Create result
            JSONObject result = new JSONObject();
            result.put("test", testPath);
            result.put("firstBadCommit", firstBadCommit);
            result.put("commitDetails", commitDetails);
            result.put("goodCommit", goodCommit);
            result.put("badCommit", badCommit);
            
            logger.info(String.format("Test %s: First bad commit is %s", 
                testPath, firstBadCommit.substring(0, 7)));
            
            return result;
            
        } finally {
            // Cleanup
            deleteDirectory(workDir);
        }
    }
    
    /**
     * Run git bisect to find the first bad commit
     */
    private String runGitBisect(String goodCommit, String badCommit, String buildType,
                               String testPath, File workDir) throws Exception {
        
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(config.getCubridSrcDir()));
        
        // Reset any previous bisect
        executeCommand(pb, "git", "bisect", "reset");
        
        // Start bisect
        executeCommand(pb, "git", "bisect", "start", badCommit, goodCommit);
        
        // Run bisect with Docker-based judge
        int testedCommits = 0;
        String currentCommit;
        
        while (true) {
            // Get current commit being tested
            currentCommit = getCurrentCommit(pb);
            testedCommits++;
            
            logger.info(String.format("Testing commit %d: %s", testedCommits, currentCommit));
            
            // Test this commit using standalone Docker
            StandaloneDockerManager.BisectResult result = 
                dockerManager.executeBuildAndTest(currentCommit, testPath, buildType);
            
            // Mark as good or bad
            String bisectResult;
            if (result.testPassed) {
                logger.info("Commit " + currentCommit.substring(0, 7) + " is GOOD");
                bisectResult = executeCommand(pb, "git", "bisect", "good");
            } else {
                logger.info("Commit " + currentCommit.substring(0, 7) + " is BAD");
                bisectResult = executeCommand(pb, "git", "bisect", "bad");
            }
            
            // Check if bisect is complete
            if (bisectResult.contains("is the first bad commit")) {
                // Extract commit hash from result
                String firstBadCommit = extractFirstBadCommit(bisectResult);
                
                // Reset bisect
                executeCommand(pb, "git", "bisect", "reset");
                
                logger.info("Bisect complete. First bad commit: " + firstBadCommit);
                return firstBadCommit;
            }
            
            // Safety check to prevent infinite loop
            if (testedCommits > 50) {
                throw new RuntimeException("Bisect taking too long, possible issue with repository");
            }
        }
    }
    
    /**
     * Get the current commit being tested by git bisect
     */
    private String getCurrentCommit(ProcessBuilder pb) throws Exception {
        String output = executeCommand(pb, "git", "rev-parse", "HEAD");
        return output.trim();
    }
    
    /**
     * Extract first bad commit from git bisect output
     */
    private String extractFirstBadCommit(String bisectOutput) {
        // Look for pattern like "1234567890abcdef is the first bad commit"
        String[] lines = bisectOutput.split("\n");
        for (String line : lines) {
            if (line.contains("is the first bad commit")) {
                String[] parts = line.split(" ");
                if (parts.length > 0) {
                    return parts[0];
                }
            }
        }
        throw new RuntimeException("Could not extract first bad commit from: " + bisectOutput);
    }
    
    /**
     * Get detailed information about a commit
     */
    private JSONObject getCommitDetails(String commitHash) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(config.getCubridSrcDir()));
        
        // Get commit details
        String details = executeCommand(pb, "git", "show", "--no-patch", 
            "--format=format:%H%n%an%n%ae%n%at%n%s%n%b", commitHash);
        
        String[] lines = details.split("\n");
        
        JSONObject commitInfo = new JSONObject();
        commitInfo.put("hash", lines[0]);
        commitInfo.put("author", lines.length > 1 ? lines[1] : "");
        commitInfo.put("authorEmail", lines.length > 2 ? lines[2] : "");
        commitInfo.put("timestamp", lines.length > 3 ? lines[3] : "");
        commitInfo.put("subject", lines.length > 4 ? lines[4] : "");
        
        // Get commit body
        StringBuilder body = new StringBuilder();
        for (int i = 5; i < lines.length; i++) {
            body.append(lines[i]).append("\n");
        }
        commitInfo.put("body", body.toString().trim());
        
        return commitInfo;
    }
    
    /**
     * Setup CUBRID repository
     */
    private void setupCubridRepository() throws Exception {
        File srcDir = new File(config.getCubridSrcDir());
        
        if (!srcDir.exists()) {
            logger.info("CUBRID source directory does not exist, cloning repository...");
            srcDir.getParentFile().mkdirs();
            
            ProcessBuilder pb = new ProcessBuilder();
            executeCommand(pb, "git", "clone", 
                "https://github.com/CUBRID/cubrid.git", srcDir.getAbsolutePath());
            logger.info("Successfully cloned CUBRID repository");
        }
        
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(srcDir);
        
        // Fetch latest changes
        logger.info("Fetching latest changes...");
        executeCommand(pb, "git", "fetch", "origin");
        
        // Ensure we're on develop branch
        try {
            executeCommand(pb, "git", "checkout", "develop");
        } catch (Exception e) {
            executeCommand(pb, "git", "checkout", "-b", "develop", "origin/develop");
        }
        
        // Update submodules
        executeCommand(pb, "git", "submodule", "update", "--init", "--recursive");
        
        logger.info("CUBRID repository ready");
    }
    
    /**
     * Get parent commit of a given commit
     */
    private String getParentCommit(String commit) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(config.getCubridSrcDir()));
        
        String parent = executeCommand(pb, "git", "rev-parse", commit + "^");
        return parent.trim();
    }
    
    /**
     * Execute a command and return output
     */
    private String executeCommand(ProcessBuilder pb, String... command) throws Exception {
        pb.command(command);
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // Read error stream
            StringBuilder error = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line).append("\n");
                }
            }
            throw new RuntimeException("Command failed: " + String.join(" ", command) + 
                                     "\nError: " + error.toString());
        }
        
        return output.toString();
    }
    
    /**
     * Create summary of bisect results
     */
    private JSONObject createSummary(JSONArray results) {
        JSONObject summary = new JSONObject();
        
        // Count unique bad commits
        Set<String> uniqueBadCommits = new HashSet<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.getJSONObject(i);
            uniqueBadCommits.add(result.getString("firstBadCommit"));
        }
        
        summary.put("totalTests", results.length());
        summary.put("uniqueBadCommits", uniqueBadCommits.size());
        summary.put("badCommitsList", new JSONArray(uniqueBadCommits));
        
        return summary;
    }
    
    /**
     * Create error response
     */
    private JSONObject createErrorResponse(Exception e) {
        JSONObject response = new JSONObject();
        response.put("status", "error");
        response.put("mode", "standalone");
        response.put("message", e.getMessage());
        response.put("type", e.getClass().getSimpleName());
        
        return response;
    }
    
    /**
     * Delete directory recursively
     */
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
    
    /**
     * Shutdown the executor
     */
    public void shutdown() {
        logger.info("Shutting down Standalone Bisect Executor...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
