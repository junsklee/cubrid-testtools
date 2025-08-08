/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * BuilderTask - Builds CUBRID at multiple commits and runs tests
 */
public class BuilderTask {
    private static final Logger logger = Logger.getLogger(BuilderTask.class.getName());
    
    private final String taskId;
    private final JSONObject request;
    private final BuilderConfig config;
    private final DockerBuildManager dockerManager;
    private final List<JSONObject> results;
    private final Map<String, Integer> progress;
    
    // Thread-safe build cache shared across all tasks
    private static final ConcurrentHashMap<String, String> buildCache = new ConcurrentHashMap<>();
    
    public BuilderTask(String taskId, JSONObject request, BuilderConfig config, 
                       DockerBuildManager dockerManager) {
        this.taskId = taskId;
        this.request = request;
        this.config = config;
        this.dockerManager = dockerManager;
        this.results = Collections.synchronizedList(new ArrayList<>());
        this.progress = new ConcurrentHashMap<>();
    }
    
    public void run() {
        logger.info("Starting builder task: " + taskId);
        long startTime = System.currentTimeMillis();
        
        try {
            // Extract request parameters
            JSONArray commits = request.getJSONArray("commits");
            JSONArray tests = request.getJSONArray("tests");
            String buildType = request.optString("buildType", "debug");
            String workerIp = request.optString("workerIp", "localhost");
            String callbackUrl = request.getString("callbackUrl");
            
            logger.info(String.format("Building %d commits for %d tests", 
                commits.length(), tests.length()));
            
            // Ensure CUBRID source repository is set up
            setupCubridRepository();
            
            // Build all commits concurrently
            Map<String, String> builtPackages = buildCommitsConcurrently(commits, buildType);
            
            // Test all builds
            for (Map.Entry<String, String> entry : builtPackages.entrySet()) {
                String commit = entry.getKey();
                String buildPackage = entry.getValue();
                
                if (buildPackage == null) {
                    // Build failed
                    for (int i = 0; i < tests.length(); i++) {
                        JSONObject result = new JSONObject()
                            .put("commit", commit)
                            .put("test", tests.getString(i))
                            .put("status", "build_failed")
                            .put("message", "Build failed for commit " + commit);
                        results.add(result);
                    }
                    continue;
                }
                
                // Test this build with all tests
                for (int i = 0; i < tests.length(); i++) {
                    String testPath = tests.getString(i);
                    JSONObject testResult = runTest(commit, buildPackage, testPath, workerIp);
                    results.add(testResult);
                }
            }
            
            // Send callback with results
            sendCallback(callbackUrl);
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Builder task failed: " + taskId, e);
            try {
                sendErrorCallback(request.getString("callbackUrl"), e.getMessage());
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Failed to send error callback", ex);
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        logger.info(String.format("Builder task %s completed in %d seconds", 
            taskId, duration / 1000));
    }
    
    private Map<String, String> buildCommitsConcurrently(JSONArray commits, String buildType) 
            throws Exception {
        Map<String, String> builtPackages = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(
            Math.min(commits.length(), config.getMaxConcurrentBuilds()));
        
        List<Future<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < commits.length(); i++) {
            final String commit = commits.getString(i);
            final int index = i;
            
            Future<Void> future = executor.submit(() -> {
                try {
                    progress.put(commit, 0); // Starting
                    
                    // Check cache first
                    String cacheKey = commit + "_" + buildType;
                    String cachedPackage = buildCache.get(cacheKey);
                    
                    if (cachedPackage != null && new File(cachedPackage).exists()) {
                        logger.info("Using cached build for commit " + commit);
                        builtPackages.put(commit, cachedPackage);
                        progress.put(commit, 100); // Complete
                        return null;
                    }
                    
                    progress.put(commit, 20); // Building
                    
                    // Create work directory for this commit
                    Path workDir = Files.createTempDirectory(
                        Paths.get(config.getWorkDir()), "build_" + commit.substring(0, 7) + "_");
                    
                    // Build the commit
                    String buildPackage = buildCommit(commit, buildType, workDir.toFile());
                    
                    if (buildPackage != null) {
                        builtPackages.put(commit, buildPackage);
                        buildCache.put(cacheKey, buildPackage);
                        cleanBuildCache(config.getBuildCacheSize());
                    }
                    
                    progress.put(commit, 100); // Complete
                    
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to build commit " + commit, e);
                    builtPackages.put(commit, null); // Mark as failed
                    progress.put(commit, -1); // Error
                }
                return null;
            });
            
            futures.add(future);
        }
        
        // Wait for all builds to complete
        for (Future<Void> future : futures) {
            try {
                future.get(30, TimeUnit.MINUTES); // 30 min timeout per build
            } catch (TimeoutException e) {
                logger.severe("Build timeout");
                future.cancel(true);
            }
        }
        
        executor.shutdown();
        return builtPackages;
    }
    
    private String buildCommit(String commit, String buildType, File workDir) 
            throws Exception {
        logger.info("Building commit " + commit);
        
        // Use Docker if available
        if (config.useDocker() && dockerManager != null && dockerManager.isReady()) {
            return dockerManager.buildCubrid(commit, workDir, buildType);
        }
        
        // Direct build fallback
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(config.getCubridSrcDir()));
        
        // Checkout commit
        executeCommand(pb, "git", "checkout", commit);
        executeCommand(pb, "git", "submodule", "update", "--init", "--recursive");
        
        // Clean and build
        executeCommand(pb, "rm", "-rf", config.getBuildDir());
        executeCommand(pb, "rm", "-rf", "cubridmanager");  // temporary fix
        executeCommand(pb, "./build.sh", config.getBuildArg());
        
        // Create package
        String packageName = "cubrid_" + commit.substring(0, 7) + ".tar.gz";
        File packageFile = new File(workDir, packageName);
        
        pb.directory(new File(config.getCubridSrcDir(), config.getBuildDir()));
        executeCommand(pb, "tar", "czf", packageFile.getAbsolutePath(), ".");
        
        return packageFile.getAbsolutePath();
    }
    
    private JSONObject runTest(String commit, String buildPackage, String testPath, 
                               String workerIp) {
        try {
            // Prepare test request
            String testDir = config.getShellTcDir() + "/" + 
                           testPath.substring(0, testPath.lastIndexOf("/"));
            String testScript = testPath.substring(testPath.lastIndexOf("/") + 1);
            String testName = testScript.replace(".sh", "");
            
            JSONObject testRequest = new JSONObject()
                .put("buildPackage", buildPackage)
                .put("testPath", testPath)
                .put("testDir", testDir)
                .put("testScript", testScript)
                .put("testName", testName)
                .put("expectedBuildVersion", commit.substring(0, 7));
            
            // Send HTTP request to tester
            URL url = new URL("http://" + workerIp + ":" + config.getTesterPort() + "/test");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(300000); // 5 min timeout for test
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(testRequest.toString().getBytes());
            }
            
            // Read response
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }
            
            JSONObject responseJson = new JSONObject(response.toString());
            
            return new JSONObject()
                .put("commit", commit)
                .put("test", testPath)
                .put("status", responseJson.getString("status"))
                .put("message", responseJson.optString("message", ""));
                
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to test commit " + commit + 
                      " with test " + testPath, e);
            return new JSONObject()
                .put("commit", commit)
                .put("test", testPath)
                .put("status", "error")
                .put("message", e.getMessage());
        }
    }
    
    private void setupCubridRepository() throws Exception {
        File srcDir = new File(config.getCubridSrcDir());
        
        if (!srcDir.exists()) {
            logger.info("Cloning CUBRID repository...");
            srcDir.getParentFile().mkdirs();
            ProcessBuilder pb = new ProcessBuilder();
            executeCommand(pb, "git", "clone", 
                          "https://github.com/CUBRID/cubrid.git", 
                          srcDir.getAbsolutePath());
        }
        
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(srcDir);
        
        // Fetch latest changes
        logger.info("Fetching latest changes...");
        executeCommand(pb, "git", "fetch", "origin");
        
        // Checkout develop branch
        try {
            executeCommand(pb, "git", "checkout", "develop");
        } catch (Exception e) {
            executeCommand(pb, "git", "checkout", "-b", "develop", "origin/develop");
        }
        
        executeCommand(pb, "git", "pull", "origin", "develop");
        executeCommand(pb, "git", "submodule", "update", "--init", "--recursive");
        
        logger.info("CUBRID repository ready");
    }
    
    private void sendCallback(String callbackUrl) {
        try {
            JSONObject response = new JSONObject()
                .put("taskId", taskId)
                .put("results", new JSONArray(results))
                .put("timestamp", System.currentTimeMillis());
            
            logger.info("Sending results to " + callbackUrl);
            
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
    
    private void sendErrorCallback(String callbackUrl, String errorMessage) {
        try {
            JSONObject response = new JSONObject()
                .put("taskId", taskId)
                .put("status", "error")
                .put("message", errorMessage)
                .put("timestamp", System.currentTimeMillis());
            
            URL url = new URL(callbackUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            
            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream())) {
                writer.write(response.toString());
            }
            
            conn.getResponseCode();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to send error callback", e);
        }
    }
    
    private void executeCommand(ProcessBuilder pb, String... command) 
            throws IOException, InterruptedException {
        pb.command(command);
        pb.redirectErrorStream(true);
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
        
        if (exitCode != 0) {
            logger.warning("Command failed: " + String.join(" ", command));
            logger.warning("Output: " + output.toString());
            throw new RuntimeException("Command failed with exit code " + exitCode);
        }
    }
    
    private void cleanBuildCache(int maxSize) {
        if (buildCache.size() > maxSize) {
            // Simple FIFO cleanup
            int toRemove = buildCache.size() - maxSize;
            Iterator<Map.Entry<String, String>> iter = buildCache.entrySet().iterator();
            while (iter.hasNext() && toRemove > 0) {
                Map.Entry<String, String> entry = iter.next();
                File file = new File(entry.getValue());
                if (file.exists()) {
                    file.delete();
                }
                iter.remove();
                toRemove--;
            }
        }
    }
    
    public JSONObject getProgress() {
        JSONObject progressJson = new JSONObject();
        for (Map.Entry<String, Integer> entry : progress.entrySet()) {
            progressJson.put(entry.getKey(), entry.getValue());
        }
        return progressJson;
    }
}
