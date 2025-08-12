/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.bisect;

import java.io.*;
import java.net.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
    private final DockerBuildManager dockerBuildManager;
    private final StandaloneDockerManager standaloneManager;
    
    /**
     * Cache for built packages to avoid rebuilding same commits
     * Key: commit hash + build type, Value: package file path
     */
    private static final Map<String, String> buildCache = new HashMap<>();
    
    /**
     * Get cached build package if available
     */
    private String getCachedBuild(String commit, String buildType) {
        String cacheKey = commit + "_" + buildType;
        String cachedPath = buildCache.get(cacheKey);
        
        if (cachedPath != null && new File(cachedPath).exists()) {
            logger.info("Using cached build for commit " + commit + " (" + buildType + ")");
            return cachedPath;
        }
        
        return null;
    }
    
    /**
     * Add build to cache
     */
    private void cacheBuild(String commit, String buildType, String packagePath) {
        String cacheKey = commit + "_" + buildType;
        buildCache.put(cacheKey, packagePath);
        logger.info("Cached build for commit " + commit + " (" + buildType + ")");
    }
    
    /**
     * Clear old builds from cache to prevent memory issues
     * Keeps only the last N builds
     */
    private void cleanBuildCache(int maxCacheSize) {
        if (buildCache.size() > maxCacheSize) {
            // Remove oldest entries (simple FIFO approach)
            int toRemove = buildCache.size() - maxCacheSize;
            Iterator<Map.Entry<String, String>> iter = buildCache.entrySet().iterator();
            while (iter.hasNext() && toRemove > 0) {
                Map.Entry<String, String> entry = iter.next();
                // Delete the actual file
                File file = new File(entry.getValue());
                if (file.exists()) {
                    file.delete();
                }
                iter.remove();
                toRemove--;
            }
            logger.info("Cleaned build cache, kept " + maxCacheSize + " entries");
        }
    }

    public BisectTask(String taskId, JSONObject request, BisectConfig config) {
        this.taskId = taskId;
        this.request = request;
        this.config = config;
        this.results = new ArrayList<>();
        this.dockerBuildManager = new DockerBuildManager(config);
        this.standaloneManager = config.isStandaloneMode() ? new StandaloneDockerManager(config) : null;
    }
    
    public void run() {
        logger.info("Starting bisect task: " + taskId);
        long startTime = System.currentTimeMillis();
        
        try {
            // Check if running in standalone mode
            if (config.isStandaloneMode()) {
                runStandalone();
                return;
            }
            
            // Initialize Docker environment if enabled
            if (config.useDocker()) {
                try {
                    dockerBuildManager.initialize();
                    if (dockerBuildManager.isDockerAvailable()) {
                        logger.info("Docker build environment initialized successfully");
                    } else {
                        logger.warning("Docker not available, will use direct build");
                    }
                } catch (Exception e) {
                    logger.warning("Failed to initialize Docker environment: " + e.getMessage());
                    logger.warning("Falling back to direct build");
                }
            }
            
            // Extract request parameters
            String suspectedStartCommit = request.getString("suspectedStartCommit");
            String suspectedEndCommit = request.getString("suspectedEndCommit");
            String buildType = request.optString("buildType", "debug");
            String workerIp = request.optString("workerIp", "localhost");
            JSONArray tests = request.getJSONArray("tests");
            
            // Ensure CUBRID source repository is set up and updated
            setupCubridRepository();
            
            // Check if this is a single commit test (start and end are the same)
            boolean isSingleCommitTest = suspectedStartCommit.equals(suspectedEndCommit);
            
            if (isSingleCommitTest) {
                logger.info("Single commit test detected - testing only commit: " + suspectedStartCommit);
                // For single commit test, directly test the commit without bisect
                for (int i = 0; i < tests.length(); i++) {
                    String test = tests.getString(i);
                    logger.info("Testing single commit for test: " + test);
                    
                    // Build once and test
                    File workDir = createTempDirectory();
                    // Build CUBRID for this commit
                    logger.info("Building CUBRID for commit: " + suspectedStartCommit);
                    ProcessBuilder pb = new ProcessBuilder();
                    pb.directory(new File(config.getCubridSrcDir()));
                    
                    // Checkout the commit
                    executeCommand(pb, "git", "checkout", suspectedStartCommit);
                    executeCommand(pb, "git", "submodule", "update", "--init", "--recursive");
                    
                    // Build CUBRID
                    String buildPackage = null;
                    if (config.useDocker() && dockerBuildManager.isReady()) {
                        buildPackage = dockerBuildManager.buildCubrid(suspectedStartCommit, workDir, buildType);
                    } else {
                        // Direct build
                        executeCommand(pb, "./build.sh", config.getBuildArg());
                        
                        // Create package
                        String packageName = "cubrid_" + suspectedStartCommit.substring(0, 7) + ".tar.gz";
                        File packageFile = new File(workDir, packageName);
                        pb.directory(new File(config.getCubridSrcDir(), config.getBuildDir()));
                        executeCommand(pb, "tar", "czf", packageFile.getAbsolutePath(), ".");
                        buildPackage = packageFile.getAbsolutePath();
                    }
                    
                    logger.info("Build package created: " + buildPackage);
                    // Test the build
                    // Test the build by sending request to consumer
                    String testDir = config.getShellTcDir() + "/" + test.substring(0, test.lastIndexOf("/"));
                    String testScript = test.substring(test.lastIndexOf("/") + 1);
                    String testName = testScript.replace(".sh", "");
                    
                    JSONObject testRequest = new JSONObject();
                    testRequest.put("buildPackage", buildPackage);
                    testRequest.put("testPath", test);
                    testRequest.put("testDir", testDir);
                    testRequest.put("testScript", testScript);
                    testRequest.put("testName", testName);
                    
                    // Send HTTP request to consumer
                    URL url = new URL("http://" + workerIp + ":" + config.getConsumerPort() + "/test");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(testRequest.toString().getBytes());
                    }
                    
                    // Read response
                    StringBuilder response = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                    }
                    
                    JSONObject responseJson = new JSONObject(response.toString());
                    boolean testPassed = "pass".equals(responseJson.getString("status"));
                    logger.info("Test result: " + responseJson.getString("status"));
                    
                    JSONObject result = new JSONObject();
                    result.put("name", test);
                    result.put("status", testPassed ? "pass" : "fail");
                    result.put("message", "Single commit test " + (testPassed ? "passed" : "failed"));
                    if (!testPassed) {
                        result.put("failedCommit", suspectedStartCommit);
                        String author = getCommitAuthor(suspectedStartCommit);
                        if (author != null) result.put("author", author);
                    }
                    results.add(result);
                    
                    // Clean up if requested
                    if (request.optBoolean("autoDeleteBuilds", true)) {
                        deleteDirectory(workDir);
                    }
                }
            } else {
            
            // Find the parent of suspectedStartCommit to use as the good commit
                // Validate commit range
                if (!validateCommitRange(suspectedStartCommit, suspectedEndCommit)) {
                    logger.warning("Invalid commit range, attempting to swap start and end commits");
                    // Try swapping
                    String temp = suspectedStartCommit;
                    suspectedStartCommit = suspectedEndCommit;
                    suspectedEndCommit = temp;
                    if (!validateCommitRange(suspectedStartCommit, suspectedEndCommit)) {
                        throw new RuntimeException("Invalid commit range: commits are not related or on different branches");
                    }
                    logger.info("Swapped commit order for valid range");
                }
                
                // Get commit count for estimation
                int commitCount = getCommitCount(suspectedStartCommit, suspectedEndCommit);
                if (commitCount > 0) {
                    int estimatedBuilds = (int) Math.ceil(Math.log(commitCount + 1) / Math.log(2));
                    logger.info(String.format("Commit range contains %d commits, estimated ~%d builds needed", 
                        commitCount, estimatedBuilds));
                }
                
                // Check if a custom good commit was provided
                String goodCommit = request.optString("goodCommit", null);
                if (goodCommit != null) {
                    logger.info("Using custom good commit: " + goodCommit);
                    // Validate that good commit is ancestor of bad commit
                    if (!validateCommitRange(goodCommit, suspectedEndCommit)) {
                        throw new RuntimeException("Invalid: good commit " + goodCommit + 
                            " is not an ancestor of bad commit " + suspectedEndCommit);
                    }
                } else {
                    // Find the parent of suspectedStartCommit to use as good commit
                    logger.info("No good commit provided, using parent of " + suspectedStartCommit);
                    goodCommit = getParentCommit(suspectedStartCommit);
                }
            if (goodCommit == null) {
                throw new RuntimeException("Could not find parent of suspected start commit: " + suspectedStartCommit);
            }
            
            logger.info(String.format("Bisect range: %s (good/parent) -> %s...%s (bad range)", 
                goodCommit, suspectedStartCommit, suspectedEndCommit));

            // Fast path: if only two commits in range (adjacent), avoid git bisect
            // git rev-list --count start..end returns 1 when end is direct descendant of start
            if (commitCount >= 0 && commitCount <= 1) {
                logger.info("Using two-commit fast path (no git bisect)");
                for (int i = 0; i < tests.length(); i++) {
                    String test = tests.getString(i);
                    logger.info("Fast-path bisecting test: " + test);
                    JSONObject result = bisectTwoCommitFastPath(
                        goodCommit, suspectedStartCommit, suspectedEndCommit, buildType, test, workerIp
                    );
                    results.add(result);
                }
            } else {
                // Default: run bisect per test
                for (int i = 0; i < tests.length(); i++) {
                    String test = tests.getString(i);
                    logger.info("Bisecting test: " + test);
                    JSONObject result = bisectSingleTest(
                        goodCommit, suspectedEndCommit, buildType, test, workerIp
                    );
                    results.add(result);
                }
            }
            
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
    
    /**
     * Run bisect in standalone mode (build and test in single container)
     */
    private void runStandalone() throws Exception {
        logger.info("Running bisect task in STANDALONE mode");
        
        // Initialize standalone Docker environment
        if (standaloneManager != null) {
            standaloneManager.initialize();
        } else {
            throw new IllegalStateException("Standalone manager not initialized");
        }
        
        // Extract request parameters
        String suspectedStartCommit = request.getString("suspectedStartCommit");
        String suspectedEndCommit = request.getString("suspectedEndCommit");
        String buildType = request.optString("buildType", "debug");
        JSONArray tests = request.getJSONArray("tests");
        
        // Setup repository
        setupCubridRepository();
        
        // Find parent commit as good commit
        String goodCommit = getParentCommit(suspectedStartCommit);
        if (goodCommit == null) {
            throw new RuntimeException("Could not find parent of suspected start commit: " + suspectedStartCommit);
        }
        
        logger.info(String.format("Standalone bisect range: %s (good) -> %s (bad)", 
            goodCommit, suspectedEndCommit));
        
        // Bisect each test using standalone Docker
        for (int i = 0; i < tests.length(); i++) {
            String test = tests.getString(i);
            logger.info("Bisecting test in standalone mode: " + test);
            
            JSONObject result = bisectSingleTestStandalone(
                goodCommit, suspectedEndCommit, buildType, test
            );
            results.add(result);
        }
        
        // Send callback with results
        sendCallback();
    }
    
    /**
     * Bisect a single test using standalone Docker
     */
    private JSONObject bisectSingleTestStandalone(String goodCommit, String badCommit,
                                                 String buildType, String testPath) throws Exception {
        
        logger.info(String.format("Standalone bisect for test %s", testPath));
        
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(config.getCubridSrcDir()));
        
        // Reset any previous bisect
        executeCommand(pb, "git", "bisect", "reset");
        
        // Start bisect
        executeCommand(pb, "git", "bisect", "start", badCommit, goodCommit);
        
        String firstBadCommit = null;
        int testedCommits = 0;
        
        while (true) {
            // Get current commit
            String currentCommit = getCurrentCommit(pb);
            testedCommits++;
            
            logger.info(String.format("Testing commit %d in standalone: %s", 
                testedCommits, currentCommit));
            
            // Test using standalone Docker
            StandaloneDockerManager.BisectResult result = 
                standaloneManager.executeBuildAndTest(currentCommit, testPath, buildType);
            
            // Mark as good or bad
            String bisectResult;
            if (result.testPassed) {
                logger.info("Commit " + currentCommit.substring(0, 7) + " is GOOD");
                bisectResult = executeCommandWithOutput(pb, "git", "bisect", "good");
            } else {
                logger.info("Commit " + currentCommit.substring(0, 7) + " is BAD");
                bisectResult = executeCommandWithOutput(pb, "git", "bisect", "bad");
            }
            
            // Check if complete
            if (bisectResult.contains("is the first bad commit")) {
                firstBadCommit = extractFirstBadCommit(bisectResult);
                break;
            }
            
            if (testedCommits > 50) {
                throw new RuntimeException("Bisect taking too long");
            }
        }
        
        // Reset bisect
        executeCommand(pb, "git", "bisect", "reset");
        
        // Create result
        JSONObject result = new JSONObject();
        result.put("test", testPath);
        result.put("firstBadCommit", firstBadCommit);
        result.put("goodCommit", goodCommit);
        result.put("badCommit", badCommit);
        result.put("mode", "standalone");
        result.put("testedCommits", testedCommits);
        
        return result;
    }
    
    /**
     * Get current commit hash
     */
    private String getCurrentCommit(ProcessBuilder pb) throws Exception {
        String output = executeCommandWithOutput(pb, "git", "rev-parse", "HEAD");
        return output.trim();
    }
    
    /**
     * Extract first bad commit from bisect output
     */
    private String extractFirstBadCommit(String bisectOutput) {
        String[] lines = bisectOutput.split("\n");
        for (String line : lines) {
            if (line.contains("is the first bad commit")) {
                String[] parts = line.split(" ");
                if (parts.length > 0) {
                    return parts[0];
                }
            }
        }
        throw new RuntimeException("Could not extract first bad commit");
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
        // Avoid interactive Git prompts that can hang
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        
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
        
        // Update submodules (parallel jobs, non-interactive)
        logger.info("Updating submodules...");
        try {
            executeCommand(pb, "git", "submodule", "update", "--init", "--recursive", "--jobs", "4", "--depth", "1", "--recommend-shallow", "--progress");
        } catch (Exception e) {
            logger.warning("Submodule update encountered an issue: " + e.getMessage());
            // Fallback without --jobs
            executeCommand(pb, "git", "submodule", "update", "--init", "--recursive");
        }
        
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

                // Prep repo
                ProcessBuilder pb = new ProcessBuilder();
                pb.directory(new File(config.getCubridSrcDir()));
                executeCommand(pb, "git", "bisect", "reset");
                executeCommand(pb, "git", "clean", "-fdx");
                executeCommand(pb, "git", "submodule", "sync");
                try {
                    executeCommand(pb, "git", "submodule", "update", "--init", "--recursive", "--jobs", "4", "--depth", "1", "--recommend-shallow");
                } catch (Exception e) {
                    logger.warning("Submodule update encountered an issue: " + e.getMessage());
                    executeCommand(pb, "git", "submodule", "update", "--init", "--recursive");
                }

                // Start bisect (bad, good)
                logger.info(String.format("Starting bisect: %s (good) -> %s (bad)", commitFormer, commitLatter));
                executeCommand(pb, "git", "bisect", "start", commitLatter, commitFormer);

                int testedCommits = 0;
                String firstBadCommit = null;
                while (true) {
                    // Current commit
                    String currentCommit = getCurrentCommit(pb);
                    testedCommits++;
                    logger.info(String.format("Testing commit %d: %s", testedCommits, currentCommit));

                    // Run judge script
                    Process js = new ProcessBuilder("bash", "-lc", judgeScript.getAbsolutePath())
                        .redirectErrorStream(true)
                        .start();
                    StringBuilder judgeOut = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(js.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            judgeOut.append(line).append('\n');
                        }
                    }
                    int judgeExit = js.waitFor();

                    String bisectResult;
                    if (judgeExit == 0) {
                        bisectResult = executeCommandWithOutput(pb, "git", "bisect", "good");
                    } else if (judgeExit == 1) {
                        bisectResult = executeCommandWithOutput(pb, "git", "bisect", "bad");
                    } else if (judgeExit == 125) {
                        bisectResult = executeCommandWithOutput(pb, "git", "bisect", "skip");
                    } else {
                        // Unknown failure, abort
                        executeCommand(pb, "git", "bisect", "reset");
                        return new JSONObject()
                            .put("name", testPath)
                            .put("status", "error")
                            .put("error", "Judge failed with exit code " + judgeExit)
                            .put("runtimeMs", System.currentTimeMillis() - startTime);
                    }

                    // Check completion
                    if (bisectResult.contains("is the first bad commit")) {
                        firstBadCommit = extractFirstBadCommit(bisectResult);
                        logger.info("Bisect complete. First bad commit: " + firstBadCommit);
                        // Build final result output similar to parseBisectOutput
                        String author = getCommitAuthor(firstBadCommit);
                        return new JSONObject()
                            .put("name", testPath)
                            .put("status", "found")
                            .put("firstBadCommit", firstBadCommit)
                            .put("author", author)
                            .put("runtimeMs", System.currentTimeMillis() - startTime);
                    }
                    if (bisectResult.contains("There are only 'skip'ped commits left to test")) {
                        return new JSONObject()
                            .put("name", testPath)
                            .put("status", "environment_issue")
                            .put("error", "All commits skipped due to environment/execution errors")
                            .put("runtimeMs", System.currentTimeMillis() - startTime);
                    }
                    if (bisectResult.contains("bisect run cannot continue")) {
                        return new JSONObject()
                            .put("name", testPath)
                            .put("status", "incomplete")
                            .put("error", "Too many untestable commits in range")
                            .put("runtimeMs", System.currentTimeMillis() - startTime);
                    }

                    if (testedCommits > 50) {
                        executeCommand(pb, "git", "bisect", "reset");
                        return new JSONObject()
                            .put("name", testPath)
                            .put("status", "error")
                            .put("error", "Bisect taking too long, aborted after 50 commits")
                            .put("runtimeMs", System.currentTimeMillis() - startTime);
                    }
                }
                
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

    /**
     * Fast path for two-commit ranges: directly test start and end commits without invoking git bisect
     */
    private JSONObject bisectTwoCommitFastPath(
        String goodCommit, String suspectedStartCommit, String suspectedEndCommit,
        String buildType, String testPath, String workerIp
    ) {
        long startTime = System.currentTimeMillis();
        try {
            // Test suspectedStartCommit first
            JSONObject startResult = testSingleCommitDirect(suspectedStartCommit, buildType, testPath, workerIp);
            boolean startPassed = "pass".equals(startResult.optString("status"));

            if (!startPassed) {
                // Start is bad -> first bad is suspectedStartCommit
                return new JSONObject()
                    .put("name", testPath)
                    .put("status", "found")
                    .put("firstBadCommit", suspectedStartCommit)
                    .put("runtimeMs", System.currentTimeMillis() - startTime);
            }

            // Otherwise test suspectedEndCommit
            JSONObject endResult = testSingleCommitDirect(suspectedEndCommit, buildType, testPath, workerIp);
            boolean endPassed = "pass".equals(endResult.optString("status"));

            if (!endPassed) {
                return new JSONObject()
                    .put("name", testPath)
                    .put("status", "found")
                    .put("firstBadCommit", suspectedEndCommit)
                    .put("runtimeMs", System.currentTimeMillis() - startTime);
            }

            // Neither failed
            return new JSONObject()
                .put("name", testPath)
                .put("status", "no_bad_commit")
                .put("message", "Both commits passed in two-commit fast path")
                .put("runtimeMs", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return new JSONObject()
                .put("name", testPath)
                .put("status", "error")
                .put("error", e.getMessage())
                .put("runtimeMs", System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Build a specific commit and run a single test directly (no git bisect)
     * Returns consumer-style JSON with status: pass|fail|execution_error|environment_error|build_error
     */
    private JSONObject testSingleCommitDirect(
        String commit, String buildType, String testPath, String workerIp
    ) throws Exception {
        // Prepare work dir
        File workDir = createTempDirectory();
        try {
            // Checkout commit
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(new File(config.getCubridSrcDir()));
            // Avoid interactive prompts that can hang
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            executeCommand(pb, "git", "checkout", commit);
            try {
            executeCommand(pb, "git", "submodule", "update", "--init", "--recursive", "--jobs", "4", "--depth", "1", "--recommend-shallow", "--progress");
            } catch (Exception e) {
                logger.warning("Submodule update encountered an issue: " + e.getMessage());
                executeCommand(pb, "git", "submodule", "update", "--init", "--recursive");
            }

            // Build using Docker or direct
            String buildPackagePath;
            if (config.useDocker() && dockerBuildManager.isDockerAvailable()) {
                // Use docker path: reuse judge's docker build inner script generation
                // Minimal duplication: call writeDirectBuildCommands when docker not available
                // Here we mimic docker build path from judge
                // Create docker build script
                File dockerScript = new File(workDir, "docker_build_internal.sh");
                try (PrintWriter writer = new PrintWriter(new FileWriter(dockerScript))) {
                    writer.println("#!/bin/bash");
                    writer.println("set -e");
                    writer.println("cp -r /cubrid-src /tmp/cubrid-build");
                    writer.println("cd /tmp/cubrid-build");
                    writer.println("git checkout ${COMMIT_HASH}");
                    writer.println("git submodule update --init --recursive");
                    writer.println("rm -rf build_x86_64_*");
                    writer.println("rm -rf cubridmanager/*");
                    writer.println("./build.sh " + config.getBuildArg());
                    writer.println("BUILD_DIR=$(ls -d build_x86_64_* | head -1)");
                    writer.println("cd $BUILD_DIR");
                    writer.println("tar czf /output/cubrid_${COMMIT_HASH:0:7}.tar.gz .");
                }
                dockerScript.setExecutable(true);

                // Run docker build
                ProcessBuilder dpb = new ProcessBuilder(
                    "bash", "-lc",
                    String.join(" ", Arrays.asList(
                        "docker run --rm",
                        "-v \"" + config.getCubridSrcDir() + ":/cubrid-src:ro\"",
                        "-v \"" + workDir.getAbsolutePath() + ":/output:rw\"",
                        "-e COMMIT_HASH=\"" + commit + "\"",
                        "cubrid-bisect-builder:latest",
                        "bash /output/docker_build_internal.sh"
                    ))
                );
                dpb.redirectErrorStream(true);
                Process dp = dpb.start();
                dp.waitFor();
                File cached = new File(new File(config.getWorkDir(), "cache"), "cubrid_" + commit.substring(0,7) + "_" + buildType + ".tar.gz");
                File built = new File(workDir, "cubrid_" + commit.substring(0, 7) + ".tar.gz");
                if (built.exists()) {
                    cached.getParentFile().mkdirs();
                    built.renameTo(cached);
                }
                buildPackagePath = cached.getAbsolutePath();
            } else {
                // Direct build
                writeDirectBuildCommands(new PrintWriter(new FileWriter(new File(workDir, "noop"))), workDir);
                // The above helper writes to script normally; replicate minimal direct build inline
                executeCommand(pb, "rm", "-rf", config.getBuildDir());
                executeCommand(pb, "./build.sh", config.getBuildArg());
                File cacheDir = new File(config.getWorkDir(), "cache");
                cacheDir.mkdirs();
                File packageFile = new File(cacheDir, "cubrid_" + commit.substring(0,7) + "_" + buildType + ".tar.gz");
                pb.directory(new File(config.getCubridSrcDir(), config.getBuildDir()));
                executeCommand(pb, "tar", "czf", packageFile.getAbsolutePath(), ".");
                buildPackagePath = packageFile.getAbsolutePath();
            }

            // Send test to consumer
            String testDir = config.getShellTcDir() + "/" + testPath.substring(0, testPath.lastIndexOf("/"));
            String testScript = testPath.substring(testPath.lastIndexOf("/") + 1);
            String testName = testScript.replace(".sh", "");

            JSONObject testRequest = new JSONObject();
            testRequest.put("buildPackage", buildPackagePath);
            testRequest.put("testPath", testPath);
            testRequest.put("testDir", testDir);
            testRequest.put("testScript", testScript);
            testRequest.put("testName", testName);

            URL url = new URL("http://" + request.optString("workerIp", "localhost") + ":" + config.getConsumerPort() + "/test");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(testRequest.toString().getBytes());
            }
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }
            return new JSONObject(response.toString());
        } finally {
            // Cleanup workspace
            if (request.optBoolean("autoDeleteBuilds", true)) {
                deleteDirectory(workDir);
            }
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
            
            // Prepare caching variables
            writer.println("# Build cache configuration");
            writer.println("CACHE_DIR=\"" + new File(config.getWorkDir(), "cache").getAbsolutePath() + "\"");
            writer.println("mkdir -p \"$CACHE_DIR\"");
            writer.println("BUILD_TYPE=\"" + buildType + "\"");
            writer.println();

            // Check if Docker should be used for builds
            if (config.useDocker() && dockerBuildManager.isDockerAvailable()) {
                writer.println("# Docker build enabled with pre-built images");
                writer.println("echo \"Building CUBRID using Docker...\"");
                writer.println("cd " + config.getCubridSrcDir());
                writer.println("COMMIT_HASH=$(git rev-parse HEAD)");
                writer.println();
                
                // Determine which Docker image to use based on configuration
                String dockerImage = config.usePrebuiltDockerImages() ? 
                    "cubrid-bisect-builder:latest" : config.getDockerBuildImage();
                
                writer.println("# Use " + (config.usePrebuiltDockerImages() ? "pre-built" : "built") + " Docker images");
                writer.println("echo \"Building CUBRID commit $COMMIT_HASH using Docker...\"");
                writer.println();
                writer.println("# Cache lookup");
                writer.println("PKG_NAME=cubrid_${COMMIT_HASH:0:7}_${BUILD_TYPE}.tar.gz");
                writer.println("CACHED_PKG=\"$CACHE_DIR/$PKG_NAME\"");
                writer.println("if [ -f \"$CACHED_PKG\" ]; then");
                writer.println("  echo \"Cache hit: $CACHED_PKG\"");
                writer.println("  BUILD_PACKAGE=\"$CACHED_PKG\"");
                writer.println("else");
                writer.println("  echo \"Cache miss, building...\"");
                writer.println("  # Create build script for Docker");
                writer.println("cat > \"" + workDir.getAbsolutePath() + "/docker_build_internal.sh\" << 'EOF'");
                writer.println("#!/bin/bash");
                writer.println("set -e");
                writer.println();
                writer.println("# Copy source to working directory");
                writer.println("cp -r /cubrid-src /tmp/cubrid-build");
                writer.println("cd /tmp/cubrid-build");
                writer.println();
                writer.println("# Checkout specific commit");
                writer.println("git checkout ${COMMIT_HASH}");
                writer.println("git submodule update --init --recursive");
                writer.println();
                writer.println("# Clean previous builds");
                writer.println("rm -rf build_x86_64_*");
                writer.println("rm -rf cubridmanager/*");
                writer.println();
                writer.println("# Build CUBRID");
                writer.println("./build.sh " + config.getBuildArg());
                writer.println();
                writer.println("# Determine build directory");
                writer.println("BUILD_DIR=$(ls -d build_x86_64_* | head -1)");
                writer.println();
                writer.println("# Create package");
                writer.println("cd $BUILD_DIR");
                writer.println("tar czf /output/cubrid_${COMMIT_HASH:0:7}.tar.gz .");
                writer.println();
                writer.println("echo \"Build completed successfully\"");
                writer.println("EOF");
                writer.println();
                writer.println("chmod +x \"" + workDir.getAbsolutePath() + "/docker_build_internal.sh\"");
                writer.println();
                writer.println("# Run Docker build with " + (config.usePrebuiltDockerImages() ? "pre-built" : "built") + " image");
                writer.println("docker run --rm \\");
                writer.println("    -v \"" + config.getCubridSrcDir() + ":/cubrid-src:ro\" \\");
                writer.println("    -v \"" + workDir.getAbsolutePath() + ":/output:rw\" \\");
                writer.println("    -e COMMIT_HASH=\"$COMMIT_HASH\" \\");
                writer.println("    -e BUILD_ARGS=\"" + config.getBuildArg() + "\" \\");
                writer.println("    " + dockerImage + " \\");
                writer.println("    bash /output/docker_build_internal.sh");
                writer.println();
                writer.println("# Move to cache path");
                writer.println("TMP_PKG=\"" + workDir.getAbsolutePath() + "/cubrid_${COMMIT_HASH:0:7}.tar.gz\"");
                writer.println("mkdir -p \"$CACHE_DIR\" ");
                writer.println("mv -f \"$TMP_PKG\" \"$CACHED_PKG\"");
                writer.println("BUILD_PACKAGE=\"$CACHED_PKG\"");
                writer.println("echo \"Docker build completed. Cached as: $BUILD_PACKAGE\"");
                writer.println("fi");
            } else {
                writer.println("# Direct build (Docker not enabled or not available)");
                writer.println("echo \"Building CUBRID at commit $(git rev-parse HEAD)\"");
                writer.println("cd " + config.getCubridSrcDir());
                writer.println();
                // Direct build with cache
                writer.println("COMMIT_HASH=$(git rev-parse HEAD)");
                writer.println("PKG_NAME=cubrid_${COMMIT_HASH:0:7}_${BUILD_TYPE}.tar.gz");
                writer.println("CACHED_PKG=\"$CACHE_DIR/$PKG_NAME\"");
                writer.println("if [ -f \"$CACHED_PKG\" ]; then");
                writer.println("  echo \"Cache hit: $CACHED_PKG\" ");
                writer.println("  BUILD_PACKAGE=\"$CACHED_PKG\" ");
                writer.println("else");
                writeDirectBuildCommands(writer, workDir);
                writer.println("# Move to cache and set BUILD_PACKAGE");
                writer.println("mv -f \"" + workDir.getAbsolutePath() + "/cubrid_$(git rev-parse --short HEAD).tar.gz\" \"$CACHED_PKG\"");
                writer.println("BUILD_PACKAGE=\"$CACHED_PKG\" ");
                writer.println("fi");
            }
            
            writer.println();
            writer.println("# Send test request to consumer");
            writer.println("echo \"Sending test request to consumer at " + 
                          workerIp + ":" + config.getConsumerPort() + "\"");
            writer.println();
            writer.println("# Create test request JSON");
            writer.println("cat > " + workDir.getAbsolutePath() + "/test_request.json << EOF");
            writer.println("{");
            writer.println("  \"buildPackage\": \"${BUILD_PACKAGE}\",");
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
            writer.println("# Log the request for debugging");
            writer.println("echo \"Test request sent:\" >> " + workDir.getAbsolutePath() + "/judge.log");
            writer.println("cat " + workDir.getAbsolutePath() + "/test_request.json >> " + workDir.getAbsolutePath() + "/judge.log");
            writer.println("echo \"Response: $RESPONSE\" >> " + workDir.getAbsolutePath() + "/judge.log");
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
            writer.println("# Parse result - Handle all status types");
            writer.println("if echo \"$RESPONSE\" | grep -q '\"status\":\"fail\"'; then");
            writer.println("    echo \"Test FAILED - marking commit as bad\"");
            writer.println("    exit 1  # Test failed (bad commit)");
            writer.println("elif echo \"$RESPONSE\" | grep -q '\"status\":\"pass\"'; then");
            writer.println("    echo \"Test PASSED - marking commit as good\"");
            writer.println("    exit 0  # Test passed (good commit)");
            writer.println("elif echo \"$RESPONSE\" | grep -q '\"status\":\"execution_error\"'; then");
            writer.println("    echo \"Test EXECUTION ERROR - cannot determine commit status\"");
            writer.println("    echo \"This is likely an environment or test setup issue, not a code issue\"");
            writer.println("    echo \"Skipping this commit (git bisect skip)\"");
            writer.println("    exit 125  # Skip this commit - can't test");
            writer.println("elif echo \"$RESPONSE\" | grep -q '\"status\":\"environment_error\"'; then");
            writer.println("    echo \"ENVIRONMENT ERROR - cannot run test properly\"");
            writer.println("    echo \"Skipping this commit (git bisect skip)\"");
            writer.println("    exit 125  # Skip this commit - can't test");
            writer.println("elif echo \"$RESPONSE\" | grep -q '\"status\":\"build_error\"'; then");
            writer.println("    echo \"BUILD ERROR - build verification failed\"");
            writer.println("    echo \"This might indicate a broken build at this commit\"");
            writer.println("    echo \"Marking as bad (build doesn't work)\"");
            writer.println("    exit 1  # Mark as bad - build is broken");
            writer.println("else");
            writer.println("    echo \"Error: Unknown or invalid response from consumer\"");
            writer.println("    echo \"Response was: $RESPONSE\"");
            writer.println("    exit 128  # Abort bisect");
            writer.println("fi");
        }
        
        // Make executable
        script.setExecutable(true);
        
        return script;
    }
    
    private void writeDirectBuildCommands(PrintWriter writer, File workDir) {
        writer.println("# Reset and update submodules");
        writer.println("git submodule foreach git reset --hard HEAD");
        writer.println("git submodule update");
        writer.println();
        writer.println("# Clean build");
        writer.println("rm -rf cubridmanager/*  # temporary: cubridmanager fails on Rocky 8");
        writer.println("rm -rf " + config.getBuildDir());
        writer.println();
        writer.println("# Build with error handling");
        writer.println("echo \"Building CUBRID...\"");
        writer.println("if ! ./build.sh " + config.getBuildArg() + "; then");
        writer.println("    echo \"[ERROR] CUBRID build failed at commit $(git rev-parse HEAD)\"");
        writer.println("    echo \"This is likely a build issue, not a test issue - skipping commit\"");
        writer.println("    exit 125  # Skip this commit - can't test due to build failure");
        writer.println("fi");
        writer.println();
        writer.println("# Verify build directory exists");
        writer.println("if [ ! -d \"" + config.getCubridSrcDir() + "/" + config.getBuildDir() + "\" ]; then");
        writer.println("    echo \"[ERROR] Build directory not created: " + config.getBuildDir() + "\"");
        writer.println("    echo \"Build may have failed silently - skipping commit\"");
        writer.println("    exit 125  # Skip this commit - can't test due to build failure");
        writer.println("fi");
        writer.println();
        writer.println("# Create build package");
        writer.println("BUILD_PACKAGE=\"" + workDir.getAbsolutePath() + 
                      "/cubrid_$(git rev-parse --short HEAD).tar.gz\"");
        writer.println("cd " + config.getCubridSrcDir() + "/" + config.getBuildDir());
        writer.println("if ! tar czf \"$BUILD_PACKAGE\" .; then");
        writer.println("    echo \"[ERROR] Failed to create build package\"");
        writer.println("    exit 125  # Skip this commit - can't test due to packaging failure");
        writer.println("fi");
        writer.println();
        writer.println("# Verify build package was created");
        writer.println("if [ ! -f \"$BUILD_PACKAGE\" ]; then");
        writer.println("    echo \"[ERROR] Build package not created: $BUILD_PACKAGE\"");
        writer.println("    exit 125  # Skip this commit - can't test due to packaging failure");
        writer.println("fi");
        writer.println("echo \"Build package created successfully: $BUILD_PACKAGE\"");
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
                
                logger.info("Found first bad commit for " + testPath + ": " + firstBadCommit);
                
                return new JSONObject()
                    .put("name", testPath)
                    .put("status", "found")
                    .put("firstBadCommit", firstBadCommit)
                    .put("author", author)
                    .put("runtimeMs", System.currentTimeMillis() - startTime);
            }
        } else if (output.contains("bisect run cannot continue")) {
            // Bisect couldn't complete due to too many skipped commits
            logger.warning("Bisect could not complete for " + testPath + " - too many untestable commits");
            return new JSONObject()
                .put("name", testPath)
                .put("status", "incomplete")
                .put("error", "Too many untestable commits in range")
                .put("runtimeMs", System.currentTimeMillis() - startTime);
        } else if (output.contains("There are only 'skip'ped commits left to test")) {
            // All commits were skipped
            logger.warning("All commits were skipped for " + testPath + " - environment issues");
            return new JSONObject()
                .put("name", testPath)
                .put("status", "environment_issue")
                .put("error", "All commits skipped due to environment/execution errors")
                .put("runtimeMs", System.currentTimeMillis() - startTime);
        }
        
        // No bad commit found or other error
        logger.severe("Bisect failed for " + testPath + " - no bad commit found");
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
            
            // Save results locally first
            saveResultsLocally(response);
            
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
    
    private void saveResultsLocally(JSONObject response) {
        try {
            // Create results directory
            File resultsDir = new File(config.getWorkDir(), "results");
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
            }
            
            // Generate result filename with timestamp and task ID
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String filename = String.format("bisect_result_%s_%s.json", timestamp, taskId);
            File resultFile = new File(resultsDir, filename);
            
            // Write JSON result to file
            try (FileWriter writer = new FileWriter(resultFile)) {
                writer.write(response.toString(2)); // Pretty print with 2-space indent
            }
            
            logger.info("Results saved locally to: " + resultFile.getAbsolutePath());
            
            // Also create a latest result symlink/copy for easy access
            File latestFile = new File(resultsDir, "latest_result.json");
            try (FileWriter writer = new FileWriter(latestFile)) {
                writer.write(response.toString(2));
            }
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to save results locally", e);
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
    
    /**
     * Execute command and return output as String
     */
    private String executeCommandWithOutput(ProcessBuilder pb, String... command) 
            throws IOException, InterruptedException {
        pb.command(command);
        pb.redirectErrorStream(true);
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
            throw new RuntimeException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
        }
        
        return output.toString();
    }
    
    /**
     * Validate that the commit range is valid
     * Returns true if endCommit is a descendant of startCommit
     */
    private boolean validateCommitRange(String startCommit, String endCommit) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(config.getCubridSrcDir()));
        
        try {
            // Check if endCommit is reachable from startCommit
            pb.command("git", "merge-base", "--is-ancestor", startCommit, endCommit);
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                logger.info("Commit range validated: " + startCommit + " is ancestor of " + endCommit);
                return true;
            } else {
                logger.warning("Invalid commit range: " + startCommit + " is not ancestor of " + endCommit);
                
                // Check if they're in reverse order
                pb.command("git", "merge-base", "--is-ancestor", endCommit, startCommit);
                process = pb.start();
                exitCode = process.waitFor();
                
                if (exitCode == 0) {
                    logger.warning("Commits appear to be in reverse order");
                }
                return false;
            }
        } catch (Exception e) {
            logger.warning("Failed to validate commit range: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get the number of commits between two commits
     */
    private int getCommitCount(String startCommit, String endCommit) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(config.getCubridSrcDir()));
        pb.command("git", "rev-list", "--count", startCommit + ".." + endCommit);
        
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            try {
                return Integer.parseInt(output.toString().trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
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

