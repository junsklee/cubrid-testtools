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
import com.navercorp.cubridqa.builder.logging.*;

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
    private Logger taskLogger;
    
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
        // Set request context for this thread
        String requestId = request.optString("requestId", taskId);
        RequestContext.setRequestId(requestId);
        
        try {
            // Get request-scoped logger
            taskLogger = RequestLogManager.getInstance().getRequestLogger(requestId, "builder");
            taskLogger.info("Starting builder task: " + taskId);
        } catch (IOException e) {
            taskLogger.warning("Failed to create request logger, using system logger: " + e.getMessage());
            taskLogger = logger;
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Extract request parameters
            JSONArray commits = request.getJSONArray("commits");
            JSONArray tests = request.getJSONArray("tests");
            String buildType = request.optString("buildType", "debug");
            
            // Extract worker IPs (supporting both singular and plural forms)
            List<String> workerIps = new ArrayList<>();
            if (request.has("workerIps")) {
                JSONArray ips = request.getJSONArray("workerIps");
                for (int i = 0; i < ips.length(); i++) {
                    workerIps.add(ips.getString(i));
                }
            } else {
                // Backward compatibility
                workerIps.add(request.optString("workerIp", "localhost"));
            }
            
            String callbackUrl = request.getString("callbackUrl");
            
            taskLogger.info(String.format("Building %d commits for %d tests across %d tester node(s)", 
                commits.length(), tests.length(), workerIps.size()));
            
            // Ensure CUBRID source repository is set up
            setupCubridRepository();

            // Determine common baseline = parent of earliest commit in the list
            String baselineCommit = determineBaselineCommit(commits);
            taskLogger.info("Using baseline (parent of earliest commit): " + baselineCommit);

            // Build all commits concurrently (each in isolation via worktree + cherry-pick)
            Map<String, String> builtPackages = buildCommitsConcurrently(commits, buildType, baselineCommit);
            
            // Distribute tests across multiple tester nodes
            Map<String, List<Callable<JSONObject>>> workerTestQueues = new HashMap<>();
            for (String worker : workerIps) {
                workerTestQueues.put(worker, new ArrayList<>());
            }
            
            // Build a global queue of tests across all commits, distributing round-robin
            int testIndex = 0;
            for (Map.Entry<String, String> entry : builtPackages.entrySet()) {
                final String commit = entry.getKey();
                final String buildPackage = entry.getValue();
                if (buildPackage == null || buildPackage.isEmpty()) {
                    for (int i = 0; i < tests.length(); i++) {
                        results.add(new JSONObject()
                            .put("commit", commit)
                            .put("test", tests.getString(i))
                            .put("status", "build_failed")
                            .put("message", "Build failed for commit " + commit));
                    }
                    continue;
                }
                for (int i = 0; i < tests.length(); i++) {
                    final String raw = tests.getString(i);
                    final String testPath = raw.startsWith("shell/") ? raw : ("shell/" + raw.replaceFirst("^/+", ""));
                    
                    // Distribute test to worker using round-robin
                    final String assignedWorker = workerIps.get(testIndex % workerIps.size());
                    testIndex++;
                    
                    // Log test distribution
                    taskLogger.info(String.format("Assigning test %s (commit %s) to tester node %s", 
                        testPath, commit.substring(0, Math.min(7, commit.length())), assignedWorker));
                    
                    // Create test callable with appropriate build package reference
                    workerTestQueues.get(assignedWorker).add(() -> 
                        runTest(commit, buildPackage, testPath, assignedWorker));
                }
            }
            
            // Fetch max concurrency from first tester (they should all have same config)
            int maxTestsPerWorker = Math.max(1, fetchTesterConcurrency(workerIps.get(0)));
            
            // Run tests for each worker with bounded concurrency
            ExecutorService testPool = Executors.newFixedThreadPool(workerIps.size() * maxTestsPerWorker);
            
            // Capture request ID for test threads
            final String testRequestId = RequestContext.getRequestId();
            
            List<Future<JSONObject>> futuresTests = new ArrayList<>();
            for (Map.Entry<String, List<Callable<JSONObject>>> entry : workerTestQueues.entrySet()) {
                String worker = entry.getKey();
                List<Callable<JSONObject>> workerTests = entry.getValue();
                
                taskLogger.info(String.format("Worker %s assigned %d tests", worker, workerTests.size()));
                
                for (Callable<JSONObject> ct : workerTests) {
                    futuresTests.add(testPool.submit(() -> {
                        // Set request context for this test thread
                        if (testRequestId != null) {
                            RequestContext.setRequestId(testRequestId);
                        }
                        try {
                            return ct.call();
                        } finally {
                            RequestContext.clear();
                        }
                    }));
                }
            }
            for (Future<JSONObject> f : futuresTests) {
                try {
                    results.add(f.get());
                } catch (Exception e) {
                    taskLogger.log(Level.WARNING, "Test execution threw", e);
                    results.add(new JSONObject()
                        .put("commit", "unknown")
                        .put("test", "unknown")
                        .put("status", "error")
                        .put("message", e.getMessage()));
                }
            }
            testPool.shutdown();
            
            // Send callback with results
            sendCallback(callbackUrl);
            
        } catch (Exception e) {
            taskLogger.log(Level.SEVERE, "Builder task failed: " + taskId, e);
            try {
                sendErrorCallback(request.getString("callbackUrl"), e.getMessage());
            } catch (Exception ex) {
                taskLogger.log(Level.SEVERE, "Failed to send error callback", ex);
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        taskLogger.info(String.format("Builder task %s completed in %d seconds", 
            taskId, duration / 1000));
    }
    
    private Map<String, String> buildCommitsConcurrently(JSONArray commits, String buildType, String baselineCommit) 
            throws Exception {
        Map<String, String> builtPackages = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(
            Math.min(commits.length(), config.getMaxConcurrentBuilds()));
        
        // Capture the current request ID to propagate to build threads
        final String requestId = RequestContext.getRequestId();
        
        List<Future<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < commits.length(); i++) {
            final String commit = commits.getString(i);
            final int index = i;
            
            Future<Void> future = executor.submit(() -> {
                // Set the request context for this thread
                if (requestId != null) {
                    RequestContext.setRequestId(requestId);
                }
                
                try {
                    progress.put(commit, 0); // Starting
                    
                    // Check cache first
                    String cacheKey = commit + "_" + buildType;
                    String cachedPackage = buildCache.get(cacheKey);
                    
                    if (cachedPackage != null && new File(cachedPackage).exists()) {
                        taskLogger.info("Using cached build for commit " + commit);
                        builtPackages.put(commit, cachedPackage);
                        progress.put(commit, 100); // Complete
                        return null;
                    }
                    
                    progress.put(commit, 20); // Building
                    
                    // Create work directory for this commit
                    Path workDir = Files.createTempDirectory(
                        Paths.get(config.getWorkDir()), "build_" + commit.substring(0, 7) + "_");
                    
                    // Build the commit (isolated on baseline via worktree + cherry-pick)
                    String buildPackage = buildCommit(commit, buildType, workDir.toFile(), baselineCommit);
                    
                    if (buildPackage != null) {
                        builtPackages.put(commit, buildPackage);
                        buildCache.put(cacheKey, buildPackage);
                        cleanBuildCache(config.getBuildCacheSize());
                    }
                    
                    progress.put(commit, 100); // Complete
                    
                } catch (Exception e) {
                    taskLogger.log(Level.SEVERE, "Failed to build commit " + commit, e);
                    builtPackages.put(commit, ""); // Mark as failed with empty string (ConcurrentHashMap disallows null)
                    progress.put(commit, -1); // Error
                } finally {
                    // Clear the request context for this thread
                    RequestContext.clear();
                }
                return null;
            });
            
            futures.add(future);
        }
        
        // Wait for all builds to complete with configurable timeout
        long timeoutMinutes = config.getBuildTimeoutMinutes();
        for (Future<Void> future : futures) {
            try {
                future.get(timeoutMinutes, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                taskLogger.severe("Build timeout after " + timeoutMinutes + " minutes");
                future.cancel(true);
            }
        }
        
        executor.shutdown();
        return builtPackages;
    }

    private int fetchTesterConcurrency(String workerIp) {
        try {
            String host = workerIp;
            int port = config.getTesterPort();
            if (workerIp.contains(":")) {
                String[] parts = workerIp.split(":");
                host = parts[0];
                try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException ignore) { port = config.getTesterPort(); }
            }
            URL url = new URL("http://" + host + ":" + port + "/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                }
                JSONObject json = new JSONObject(response.toString());
                if (json.has("maxConcurrentTests")) {
                    return json.getInt("maxConcurrentTests");
                }
            } else {
                taskLogger.warning("Health check responded with status: " + status + " from " + host + ":" + port);
            }
        } catch (Exception e) {
            taskLogger.log(Level.WARNING, "Failed to fetch tester concurrency from health endpoint, using default", e);
        }
        // Fallback to a sane default if tester does not report or on error
        return 4;
    }
    
    private String buildCommit(String commit, String buildType, File workDir, String baselineCommit) 
            throws Exception {
        taskLogger.info("Building commit " + commit);
        
        // Use Docker if available
        if (config.useDocker() && dockerManager != null && dockerManager.isReady()) {
            return dockerManager.buildCubrid(commit, workDir, buildType, baselineCommit);
        }
        
        // Direct build fallback using isolated worktree + cherry-pick
        File repoRoot = new File(config.getCubridSrcDir());
        ProcessBuilder repoPb = new ProcessBuilder();
        repoPb.directory(repoRoot);

        // Optional: ensure everything is available locally (non-fatal if it fails)
        try {
            executeCommand(repoPb, "git", "fetch", "--all", "--recurse-submodules=on-demand");
        } catch (Exception ignore) { }

        // 1) Create worktree at the baseline using a temporary branch (avoid --detach for older Git)
        String shortCommit = commit.substring(0, Math.min(commit.length(), 7));
        String tempBranch = "isolate_" + shortCommit + "_tmp";
        File wtDir = new File(workDir, "wt_" + shortCommit);
        try {
            executeCommand(repoPb, "git", "worktree", "add", "-b", tempBranch, wtDir.getAbsolutePath(), baselineCommit);
        } catch (Exception e) {
            taskLogger.warning("git worktree add -b failed (" + e.getMessage() + "), falling back to manual branch creation");
            // Fallback for older git: create branch first, then add worktree
            executeCommand(repoPb, "git", "branch", "-f", tempBranch, baselineCommit);
            executeCommand(repoPb, "git", "worktree", "add", wtDir.getAbsolutePath(), tempBranch);
        }

        boolean success = false;
        try {
            // 2) Cherry-pick only that commit onto the baseline
            boolean isMerge = isMergeCommit(commit, repoRoot);
            ProcessBuilder wtPb = new ProcessBuilder();
            wtPb.directory(wtDir);
            
            boolean cherryPickSucceeded = false;
            Exception cherryPickException = null;
            
            // First attempt: try cherry-pick
            if (isMerge) {
                try {
                    executeCommand(wtPb, "git", "cherry-pick", "-m", "1", "-x", commit);
                    cherryPickSucceeded = true;
                } catch (Exception e) {
                    cherryPickException = e;
                    try { executeCommand(wtPb, "git", "cherry-pick", "--abort"); } catch (Exception ignore) {}
                }
            } else {
                try {
                    executeCommand(wtPb, "git", "cherry-pick", "-x", commit);
                    cherryPickSucceeded = true;
                } catch (Exception e) {
                    cherryPickException = e;
                    try { executeCommand(wtPb, "git", "cherry-pick", "--abort"); } catch (Exception ignore) {}
                }
            }
            
            // Fallback: try format-patch and apply if cherry-pick failed
            if (!cherryPickSucceeded) {
                taskLogger.warning("Cherry-pick failed for " + commit + ", attempting format-patch fallback");
                try {
                    // Create a patch file from the commit
                    File patchFile = new File(workDir, commit + ".patch");
                    ProcessBuilder patchPb = new ProcessBuilder();
                    patchPb.directory(repoRoot);
                    
                    if (isMerge) {
                        // For merge commits, create a diff against first parent
                        executeCommand(patchPb, "git", "format-patch", "-1", "--stdout", "-m", "--first-parent", commit);
                    } else {
                        executeCommand(patchPb, "git", "format-patch", "-1", "--stdout", commit);
                    }
                    
                    // Redirect output to patch file
                    patchPb.redirectOutput(patchFile);
                    Process patchProcess = patchPb.start();
                    patchProcess.waitFor();
                    
                    if (patchFile.exists() && patchFile.length() > 0) {
                        // Apply the patch
                        executeCommand(wtPb, "git", "apply", "--3way", patchFile.getAbsolutePath());
                        
                        // Commit the changes
                        executeCommand(wtPb, "git", "add", "-A");
                        String commitMessage = executeCommandAndGetOutput(repoPb, "git", "log", "--format=%B", "-n", "1", commit).trim();
                        executeCommand(wtPb, "git", "commit", "-m", commitMessage);
                        
                        taskLogger.info("Successfully applied commit " + commit + " using format-patch fallback");
                    } else {
                        throw new RuntimeException("Failed to create patch for " + commit);
                    }
                } catch (Exception fallbackException) {
                    // Both methods failed, report build failure
                    taskLogger.severe("Both cherry-pick and format-patch failed for " + commit);
                    taskLogger.severe("Cherry-pick error: " + cherryPickException.getMessage());
                    taskLogger.severe("Format-patch error: " + fallbackException.getMessage());
                    throw new RuntimeException("Failed to apply commit " + commit + " using both cherry-pick and format-patch methods", fallbackException);
                }
            }

            // 3) Make submodules match the gitlinks from this isolated tree
            executeCommand(wtPb, "git", "submodule", "sync", "--recursive");
            executeCommand(wtPb, "git", "submodule", "update", "--init", "--recursive", "--checkout", "--force");

            // 4) Clean & build
            executeCommand(wtPb, "git", "clean", "-xdf");
            executeCommand(wtPb, "rm", "-rf", config.getBuildDir());
            executeCommand(wtPb, "rm", "-rf", "cubridmanager"); // temporary fix parity

            // Build command
            List<String> buildCmd = new ArrayList<>();
            buildCmd.add("./build.sh");
            for (String token : config.getBuildArg().trim().split("\\s+")) {
                if (!token.isEmpty()) buildCmd.add(token);
            }
            executeCommand(wtPb, buildCmd.toArray(new String[0]));

            // 5) Create package from the isolated worktree build output
            String packageName = "cubrid_" + commit.substring(0, 7) + ".tar.gz";
            File packageFile = new File(workDir, packageName);

            ProcessBuilder tarPb = new ProcessBuilder();
            tarPb.directory(new File(wtDir, config.getBuildDir()));
            executeCommand(tarPb, "tar", "czf", packageFile.getAbsolutePath(), ".");

            success = true;
            return packageFile.getAbsolutePath();
        } finally {
            // 6) Tear down worktree
            try { executeCommand(repoPb, "git", "worktree", "remove", "--force", wtDir.getAbsolutePath()); } catch (Exception e) { taskLogger.warning("Failed to remove worktree " + wtDir.getAbsolutePath() + ": " + e.getMessage()); }
            // delete temporary branch if exists
            try { executeCommand(repoPb, "git", "branch", "-D", tempBranch); } catch (Exception ignore) {}
            // If build failed, ensure worktree dir is wiped
            if (!success) {
                try {
                    deleteRecursively(wtDir);
                } catch (Exception ignore) {}
            }
        }
    }

    private String determineBaselineCommit(JSONArray commits) throws Exception {
        if (commits == null || commits.length() == 0) {
            throw new IllegalArgumentException("No commits provided");
        }
        String earliest = commits.getString(0);
        File repoRoot = new File(config.getCubridSrcDir());
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(repoRoot);
        String base = executeCommandAndGetOutput(pb, "git", "rev-parse", earliest + "^").trim();
        if (base.isEmpty()) {
            throw new RuntimeException("Failed to determine baseline for " + earliest);
        }
        return base;
    }

    private boolean isMergeCommit(String commit, File repoRoot) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(repoRoot);
        String out = executeCommandAndGetOutput(pb, "git", "rev-list", "--parents", "-n1", commit).trim();
        if (out.isEmpty()) return false;
        String[] parts = out.split("\\s+");
        return parts.length > 2;
    }
    
    private JSONObject runTest(String commit, String buildPackage, String testPath, 
                               String workerIp) {
        try {
            // Parse host and port from workerIp (supports "host:port" format)
            String host = workerIp;
            int port = config.getTesterPort();
            
            if (workerIp.contains(":")) {
                String[] parts = workerIp.split(":");
                host = parts[0];
                try {
                    port = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    taskLogger.warning("Invalid port in workerIp: " + workerIp);
                }
            }
            
            // Prepare test request
            String testDir = config.getShellTcDir() + "/" + 
                           testPath.substring(0, testPath.lastIndexOf("/"));
            String testScript = testPath.substring(testPath.lastIndexOf("/") + 1);
            String testName = testScript.replace(".sh", "");
            
            // Determine if this is a local or remote tester
            String buildPackageRef;
            if (isLocalTester(host)) {
                // Local tester - use direct file path
                buildPackageRef = buildPackage;
                taskLogger.info("Using local file path for tester " + workerIp + ": " + buildPackage);
            } else {
                // Remote tester - provide HTTP URL for download
                File packageFile = new File(buildPackage);
                String builderHost = InetAddress.getLocalHost().getHostAddress();
                buildPackageRef = String.format("http://%s:%d/download/build/%s", 
                    builderHost, config.getListenPort(), packageFile.getName());
                taskLogger.info("Using HTTP URL for remote tester " + workerIp + ": " + buildPackageRef);
            }
            
            JSONObject testRequest = new JSONObject()
                .put("buildPackage", buildPackageRef)
                .put("testPath", testPath)
                .put("testDir", testDir)
                .put("testScript", testScript)
                .put("testName", testName)
                .put("commit", commit)  // Add full commit hash
                .put("commitShort", commit.substring(0, Math.min(commit.length(), 7)))  // Add short commit
                .put("expectedBuildVersion", commit.substring(0, 7))
                .put("keepAlive", false);
            
            // Add request ID if available
            String requestId = RequestContext.getRequestId();
            if (requestId != null) {
                testRequest.put("requestId", requestId);
            }
            
            // Persist test request for diagnostics
            try {
                if (requestId != null && config.isRequestGroupingEnabled()) {
                    String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                    String safeTest = testName.replaceAll("[^a-zA-Z0-9_.-]", "_");
                    String safeCommit = commit.substring(0, Math.min(commit.length(), 7));
                    java.nio.file.Path reqFile = Paths.get(testsDir, String.format("test_%s_%s.json", safeCommit, safeTest));
                    java.nio.file.Files.write(reqFile, testRequest.toString(2).getBytes("UTF-8"));
                }
            } catch (Exception ignore) { }

            // Send HTTP request to tester
            URL url = new URL("http://" + host + ":" + port + "/test");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            // Read timeout is configurable via tester.conf (reported by Tester and used by BuilderConfig)
            int testTimeoutMin = config.getTestReadTimeoutMinutes();
            conn.setReadTimeout(testTimeoutMin * 60 * 1000);
            taskLogger.info("Sending test '" + testName + "' (commit " + commit.substring(0, Math.min(7, commit.length())) + ") to tester " + host + ":" + port);
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(testRequest.toString().getBytes());
            }
            
            // Read response (handle non-2xx by reading error stream)
            int httpStatus = conn.getResponseCode();
            taskLogger.info("Tester response HTTP " + httpStatus + " for '" + testName + "' on " + host + ":" + port);
            StringBuilder response = new StringBuilder();
            InputStream is = (httpStatus >= 200 && httpStatus < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                }
            }
            JSONObject responseJson;
            try {
                responseJson = new JSONObject(response.length() == 0 ? "{}" : response.toString());
            } catch (Exception parseEx) {
                responseJson = new JSONObject().put("status", httpStatus >= 200 && httpStatus < 300 ? "unknown" : "execution_error")
                                              .put("message", "Tester returned HTTP " + httpStatus);
            }
            // If tester returned non-2xx, mark as execution_error unless a status is provided
            if (httpStatus < 200 || httpStatus >= 300) {
                String status = responseJson.optString("status", "execution_error");
                String message = responseJson.optString("message", "Tester HTTP status: " + httpStatus);
                return new JSONObject()
                    .put("commit", commit)
                    .put("test", testPath)
                    .put("status", status)
                    .put("message", message);
            }
            
            // Normal success path
            JSONObject result = new JSONObject()
                .put("commit", commit)
                .put("test", testPath)
                .put("status", responseJson.optString("status", "unknown"))
                .put("message", responseJson.optString("message", ""));
                
            // Copy flaky and attempts information if present
            if (responseJson.has("flaky")) {
                result.put("flaky", responseJson.getBoolean("flaky"));
            }
            if (responseJson.has("attempts")) {
                result.put("attempts", responseJson.getInt("attempts"));
            }
            
            return result;
                
        } catch (Exception e) {
            taskLogger.log(Level.SEVERE, "Failed to test commit " + commit + 
                      " with test " + testPath + " on " + workerIp, e);
            return new JSONObject()
                .put("commit", commit)
                .put("test", testPath)
                .put("status", "error")
                .put("message", e.getMessage());
        }
    }
    
    private boolean isLocalTester(String ip) {
        if ("localhost".equalsIgnoreCase(ip) || "127.0.0.1".equals(ip)) {
            return true;
        }
        try {
            String localHost = InetAddress.getLocalHost().getHostAddress();
            return ip.equals(localHost);
        } catch (Exception e) {
            return false;
        }
    }
    
    private void setupCubridRepository() throws Exception {
        File srcDir = new File(config.getCubridSrcDir());
        
        if (!srcDir.exists()) {
            taskLogger.info("Cloning CUBRID repository...");
            srcDir.getParentFile().mkdirs();
            ProcessBuilder pb = new ProcessBuilder();
            executeCommand(pb, "git", "clone", 
                          "https://github.com/CUBRID/cubrid.git", 
                          srcDir.getAbsolutePath());
        }
        
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(srcDir);
        
        // Fetch latest changes (all remotes); also fetch submodules on-demand
        taskLogger.info("Fetching latest changes (all remotes)...");
        try {
            executeCommand(pb, "git", "fetch", "--all", "--prune", "--recurse-submodules=on-demand");
        } catch (Exception e) {
            taskLogger.warning("Fetch --all failed: " + e.getMessage() + ". Falling back to origin only.");
            try { executeCommand(pb, "git", "fetch", "origin"); } catch (Exception ignore) {}
        }
        
        // Checkout develop branch
        try {
            executeCommand(pb, "git", "checkout", "develop");
        } catch (Exception e) {
            executeCommand(pb, "git", "checkout", "-b", "develop", "origin/develop");
        }
        
        executeCommand(pb, "git", "pull", "origin", "develop");
        executeCommand(pb, "git", "submodule", "update", "--init", "--recursive");
        
        taskLogger.info("CUBRID repository ready");
    }
    
    private void sendCallback(String callbackUrl) {
        try {
            JSONObject response = new JSONObject()
                .put("taskId", taskId)
                .put("results", new JSONArray(results))
                .put("timestamp", System.currentTimeMillis());
            
            taskLogger.info("Sending results to " + callbackUrl);
            
            URL url = new URL(callbackUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            
            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream())) {
                writer.write(response.toString());
            }
            
            int responseCode = conn.getResponseCode();
            taskLogger.info("Callback response: " + responseCode);
            
        } catch (Exception e) {
            taskLogger.log(Level.SEVERE, "Failed to send callback", e);
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
            taskLogger.log(Level.SEVERE, "Failed to send error callback", e);
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
            taskLogger.warning("Command failed: " + String.join(" ", command));
            taskLogger.warning("Output: " + output.toString());
            throw new RuntimeException("Command failed with exit code " + exitCode);
        }
    }

    private String executeCommandAndGetOutput(ProcessBuilder pb, String... command)
            throws IOException, InterruptedException {
        pb.command(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            taskLogger.warning("Command failed: " + String.join(" ", command));
            taskLogger.warning("Output: " + output.toString());
            throw new RuntimeException("Command failed with exit code " + exitCode);
        }
        return output.toString();
    }

    private void deleteRecursively(File path) {
        if (path == null || !path.exists()) return;
        if (path.isDirectory()) {
            File[] files = path.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursively(f);
                }
            }
        }
        try { path.delete(); } catch (Exception ignore) {}
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
