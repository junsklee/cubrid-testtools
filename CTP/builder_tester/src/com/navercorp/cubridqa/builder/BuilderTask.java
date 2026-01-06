/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.*;
import java.util.stream.Collectors;
import org.json.JSONObject;
import org.json.JSONArray;
import com.navercorp.cubridqa.builder.logging.*;
import com.navercorp.cubridqa.builder.workload.*;
import com.navercorp.cubridqa.builder.scheduler.*;
import com.navercorp.cubridqa.builder.tester.stats.*;

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
    private String baselineCommit;
    private WorkloadDistributor workloadDistributor;

    // Progress tracking for dashboard
    private final AtomicInteger buildsCompleted = new AtomicInteger(0);
    private final AtomicInteger testsCompleted = new AtomicInteger(0);
    private volatile int buildsTotal = 0;
    private volatile int testsTotal = 0;
    private volatile String currentPhase = "initializing";
    private volatile String currentCommit = "";
    private volatile String currentTest = "";

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
        this.workloadDistributor = null; // Will be initialized in run()
    }
    
    public void run() {
        // Set request context for this thread
        String requestId = request.optString("requestId", taskId);
        RequestContext.setRequestId(requestId);
        
        try {
            // Get request-scoped logger
            taskLogger = RequestLogManager.getInstance().getRequestLogger(requestId, "builder");
            taskLogger.info("Starting builder task: " + taskId);
            
            // Ensure tests subdirectory exists up front (even before tests run)
            if (RequestLogManager.getInstance().isRequestGroupingEnabled()) {
                RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
            }
        } catch (IOException e) {
            taskLogger.warning("Failed to create request logger, using system logger: " + e.getMessage());
            taskLogger = logger;
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Extract request parameters
            JSONArray commits = request.has("commits") ? request.getJSONArray("commits") : new JSONArray();
            JSONArray tests = request.getJSONArray("tests");
            String buildType = request.optString("buildType", "debug");

            // Initialize progress totals
            this.buildsTotal = request.has("prNumber") ? 1 : commits.length();
            this.testsTotal = this.buildsTotal * tests.length();
            this.currentPhase = "setup";

            Integer prNumber = null;
            if (request.has("prNumber")) {
                try {
                    prNumber = request.get("prNumber") instanceof Number ? ((Number) request.get("prNumber")).intValue() : Integer.parseInt(request.get("prNumber").toString());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid prNumber value");
                }
                if (prNumber == null || prNumber <= 0) {
                    throw new IllegalArgumentException("prNumber must be a positive integer");
                }
            }
            
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

            // Initialize WorkloadDistributor with all nodes (including localhost)
            // Format: "host:port" - add default builder port if not specified
            List<String> nodeIds = new ArrayList<>();
            for (String workerIp : workerIps) {
                if (workerIp.contains(":")) {
                    nodeIds.add(workerIp);
                } else {
                    // Add default builder port (8089)
                    nodeIds.add(workerIp + ":8089");
                }
            }
            // Add localhost if not already in the list
            boolean hasLocalhost = nodeIds.stream().anyMatch(n ->
                n.startsWith("localhost:") || n.startsWith("127.0.0.1:"));
            if (!hasLocalhost) {
                nodeIds.add(0, "localhost:8089"); // Add at beginning for priority
            }

            this.workloadDistributor = new WorkloadDistributor(nodeIds);
            taskLogger.info("Initialized WorkloadDistributor with nodes: " + nodeIds);
            
            if (prNumber != null) {
                taskLogger.info(String.format("Building PR #%d for %d tests across %d tester node(s)", 
                    prNumber, tests.length(), workerIps.size()));
            } else {
                taskLogger.info(String.format("Building %d commits for %d tests across %d tester node(s)", 
                    commits.length(), tests.length(), workerIps.size()));
            }
            
            // Ensure CUBRID source repository is set up
            setupCubridRepository();

            this.currentPhase = "building";
            Map<String, String> builtPackages;
            if (prNumber != null) {
                // Resolve PR head and baseline (merge-base against develop)
                PRResolution pr = resolvePullRequest(prNumber);
                this.baselineCommit = pr.baselineSha;
                taskLogger.info(String.format("Resolved PR #%d → head=%s, baseline=%s", prNumber,
                    pr.headSha.substring(0, Math.min(7, pr.headSha.length())),
                    pr.baselineSha.substring(0, Math.min(7, pr.baselineSha.length()))));

                // Build PR snapshot (no cherry-pick)
                builtPackages = buildPullRequest(pr, buildType);
            } else {
                // Determine common baseline = parent of earliest commit in the list
                this.baselineCommit = determineBaselineCommit(commits);
                taskLogger.info("Using baseline (parent of earliest commit): " + this.baselineCommit);

                // Build all commits SEQUENTIALLY using WorkloadDistributor
                builtPackages = buildCommitsSequentially(commits, buildType, this.baselineCommit);
            }
            
            // Distribute tests across multiple tester nodes
            Map<String, Integer> workerCapacities = new LinkedHashMap<>(); // current caps (start heavy)
            Map<String, Integer> workerPeakCaps = new LinkedHashMap<>();
            Map<String, AtomicInteger> dispatchCounts = new ConcurrentHashMap<>();
            for (String worker : workerIps) {
                TesterCaps caps = fetchTesterCaps(worker);
                workerCapacities.put(worker, caps.heavyCap);
                workerPeakCaps.put(worker, caps.peakCap);
                dispatchCounts.put(worker, new AtomicInteger(0));
                taskLogger.info(String.format("Tester %s reports max_concurrent_tests=%d (peak=%d)", worker, caps.heavyCap, caps.peakCap));
            }
            // Calculate total number of test executions
            int totalTestExecutions = builtPackages.size() * tests.length();
            taskLogger.info(String.format("Distributing %d test executions (%d commits × %d tests) across %d workers",
                totalTestExecutions, builtPackages.size(), tests.length(), workerIps.size()));

            final String finalBuildType = buildType;
            final String testRequestId = RequestContext.getRequestId();

            // Track which testers received tests for this requestId
            Set<String> testersUsed = ConcurrentHashMap.newKeySet();

            this.currentPhase = "testing";
            if (config.isPullSchedulingEnabled()) {
                taskLogger.info("Pull-based scheduling flag enabled - builder will respond to tester pull when wired; legacy push flow remains active.");
            }

            // Choose distribution strategy
            if (config.isSmartSchedulingEnabled()) {
                taskLogger.info("Using SMART SCHEDULING for test distribution");
                distributeTestsWithSmartScheduling(
                    builtPackages,
                    tests,
                    workerIps,
                    finalBuildType,
                    testRequestId,
                    workerCapacities,
                    workerPeakCaps,
                    testersUsed
                );
            } else {
                taskLogger.info("Using LEGACY work-queue distribution");
                distributeTestsLegacy(builtPackages, tests, workerCapacities, dispatchCounts, finalBuildType, testRequestId, totalTestExecutions, testersUsed);
            }
            
            // Send finalize requests to all testers that received tests for this requestId
            if (testRequestId != null && !testRequestId.isEmpty() && !testersUsed.isEmpty()) {
                sendFinalizeRequests(testRequestId, testersUsed);
            }
            
            // Calculate execution time and send callback with results
            long duration = System.currentTimeMillis() - startTime;
            this.currentPhase = "callback";
            sendCallback(callbackUrl, duration);
            this.currentPhase = "done";
            
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

    private static class PRResolution {
        final int prNumber;
        final String prBranch;
        final String headSha;
        final String baselineSha;
        PRResolution(int prNumber, String prBranch, String headSha, String baselineSha) {
            this.prNumber = prNumber;
            this.prBranch = prBranch;
            this.headSha = headSha;
            this.baselineSha = baselineSha;
        }
    }

    private PRResolution resolvePullRequest(int prNumber) {
        try {
            File repoRoot = new File(config.getCubridSrcDir());
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(repoRoot);

            // Ensure repository is up-to-date
            try { executeCommand(pb, "git", "fetch", "--all", "--prune", "--recurse-submodules=on-demand"); } catch (Exception ignore) {}

            String prBranch = "pr_" + prNumber + "_br";

            boolean fetched = false;
            Exception lastEx = null;
            // Try origin first
            try {
                executeCommand(pb, "git", "fetch", "origin", "pull/" + prNumber + "/head:" + prBranch);
                fetched = true;
            } catch (Exception e1) {
                lastEx = e1;
                // Fallback to upstream if configured
                try {
                    executeCommand(pb, "git", "fetch", "upstream", "pull/" + prNumber + "/head:" + prBranch);
                    fetched = true;
                } catch (Exception e2) {
                    lastEx = e2;
                }
            }

            if (!fetched) {
                throw new RuntimeException("Failed to fetch PR #" + prNumber + " from origin/upstream: " + (lastEx != null ? lastEx.getMessage() : "unknown error"));
            }

            // Resolve head SHA
            String headSha = executeCommandAndGetOutput(pb, "git", "rev-parse", prBranch).trim();
            if (headSha.isEmpty()) {
                throw new RuntimeException("Failed to resolve head SHA for PR branch: " + prBranch);
            }

            // Compute baseline as merge-base with develop; fallback to parent
            String baseline;
            try {
                baseline = executeCommandAndGetOutput(pb, "git", "merge-base", headSha, "develop").trim();
                if (baseline.isEmpty()) throw new RuntimeException("empty");
            } catch (Exception e) {
                try {
                    baseline = executeCommandAndGetOutput(pb, "git", "rev-parse", headSha + "^").trim();
                } catch (Exception e2) {
                    throw new RuntimeException("Failed to determine baseline for PR #" + prNumber + ": " + e2.getMessage());
                }
            }

            return new PRResolution(prNumber, prBranch, headSha, baseline);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("PR resolution failed for #" + prNumber + ": " + e.getMessage(), e);
        }
    }

    private Map<String, String> buildPullRequest(PRResolution pr, String buildType) throws Exception {
        Map<String, String> builtPackages = new ConcurrentHashMap<>();

        String normalizedCommit = pr.headSha;
        String normalizedShort = normalizedCommit.substring(0, Math.min(7, normalizedCommit.length()));

        // Check in-memory cache (include baseline in key to avoid incorrect reuse)
        String baselineShort = pr.baselineSha.substring(0, Math.min(7, pr.baselineSha.length()));
        String cacheKey = normalizedCommit + "_" + buildType + "_" + baselineShort;
        String cachedPackage = buildCache.get(cacheKey);
        if (cachedPackage != null && new File(cachedPackage).exists()) {
            // Validate baseline matches before reusing
            if (validateCachedBaseline(cachedPackage, normalizedCommit, pr.baselineSha)) {
                taskLogger.info("Using cached build for PR head " + normalizedCommit + " (baseline: " + baselineShort + ")");
                builtPackages.put(normalizedCommit, cachedPackage);
                progress.put(normalizedCommit, 100);
                buildsCompleted.incrementAndGet();
                createCachedBuildLog(normalizedCommit, cachedPackage, "memory cache");
                try { ensureBuildMetadata(cachedPackage, normalizedCommit, buildType, pr.baselineSha); } catch (Exception ignore) {}
                return builtPackages;
            } else {
                taskLogger.warning("Cached build validation failed for PR head " + normalizedCommit + ", will rebuild");
                buildCache.remove(cacheKey);
            }
        }

        // Check disk cache
        String diskPackage = findExistingBuildPackageOnDisk(normalizedShort, buildType, pr.baselineSha, normalizedCommit);
        if (diskPackage != null) {
            taskLogger.info("Using cached build from disk for PR head " + normalizedCommit);
            builtPackages.put(normalizedCommit, diskPackage);
            buildCache.put(cacheKey, diskPackage);
            progress.put(normalizedCommit, 100);
            buildsCompleted.incrementAndGet();
            createCachedBuildLog(normalizedCommit, diskPackage, "disk cache");
            try { ensureBuildMetadata(diskPackage, normalizedCommit, buildType, pr.baselineSha); } catch (Exception ignore) {}
            return builtPackages;
        }

        progress.put(normalizedCommit, 20);

        // Create work directory
        Path workDir = Files.createTempDirectory(Paths.get(config.getWorkDir()), "build_pr_" + normalizedShort + "_");

        String buildPackage = null;
        try {
            if (config.useDocker() && dockerManager != null && dockerManager.isReady()) {
                buildPackage = dockerManager.buildPullRequest(pr.prBranch, normalizedCommit, workDir.toFile(), buildType, pr.baselineSha);
            } else {
                // Direct PR build: checkout head SHA and build as-is (no cherry-pick)
                buildPackage = buildFromHeadDirect(normalizedCommit, buildType, workDir.toFile());
            }

            if (buildPackage != null) {
                builtPackages.put(normalizedCommit, buildPackage);
                buildCache.put(cacheKey, buildPackage);
                writeBuildMetadata(buildPackage, normalizedCommit, buildType, pr.baselineSha);
                cleanBuildCache(config.getBuildCacheSize());
            }
            progress.put(normalizedCommit, 100);
            buildsCompleted.incrementAndGet();
        } catch (Exception e) {
            taskLogger.log(Level.SEVERE, "Failed to build PR #" + pr.prNumber + " (head " + normalizedCommit + ")", e);
            builtPackages.put(normalizedCommit, "");
            progress.put(normalizedCommit, -1);
        }

        return builtPackages;
    }

    private String buildFromHeadDirect(String headSha, String buildType, File workDir) throws Exception {
        File repoRoot = new File(config.getCubridSrcDir());
        ProcessBuilder repoPb = new ProcessBuilder();
        repoPb.directory(repoRoot);
        try { executeCommand(repoPb, "git", "fetch", "--all", "--recurse-submodules=on-demand"); } catch (Exception ignore) { }

        String shortCommit = headSha.substring(0, Math.min(headSha.length(), 7));
        String tempBranch = "isolate_pr_" + shortCommit + "_tmp";
        File wtDir = new File(workDir, "wt_pr_" + shortCommit);
        try {
            executeCommand(repoPb, "git", "worktree", "add", "-b", tempBranch, wtDir.getAbsolutePath(), headSha);
        } catch (Exception e) {
            taskLogger.warning("git worktree add -b failed (" + e.getMessage() + "), falling back to manual branch creation");
            executeCommand(repoPb, "git", "branch", "-f", tempBranch, headSha);
            executeCommand(repoPb, "git", "worktree", "add", wtDir.getAbsolutePath(), tempBranch);
        }

        boolean success = false;
        try {
            ProcessBuilder wtPb = new ProcessBuilder();
            wtPb.directory(wtDir);

            executeCommand(wtPb, "git", "submodule", "sync", "--recursive");
            executeCommand(wtPb, "git", "submodule", "update", "--init", "--recursive", "--checkout", "--force");
            executeCommand(wtPb, "git", "clean", "-xdf");
            executeCommand(wtPb, "rm", "-rf", config.getBuildDir(buildType));
            executeCommand(wtPb, "rm", "-rf", "cubridmanager");

            // ccache setup similar to buildCommit
            if (config.isCcacheEnabled()) {
                try {
                    File logsDir = new File(config.getCcacheDir(), "logs");
                    if (!logsDir.exists()) logsDir.mkdirs();
                    File tmpDir = new File(config.getCcacheDir(), "tmp");
                    if (!tmpDir.exists()) tmpDir.mkdirs();
                } catch (Exception ignore) {}
                wtPb.environment().put("CC", "ccache gcc");
                wtPb.environment().put("CXX", "ccache g++");
                wtPb.environment().put("CCACHE_DIR", config.getCcacheDir());
                wtPb.environment().put("CCACHE_COMPILERCHECK", config.getCcacheCompilerCheck());
                wtPb.environment().put("CCACHE_HARDLINK", config.getCcacheHardlink() ? "1" : "0");
                wtPb.environment().put("CCACHE_MAXSIZE", config.getCcacheMaxSize());
                wtPb.environment().put("CCACHE_BASEDIR", new File(config.getWorkDir()).getAbsolutePath());
                wtPb.environment().put("CCACHE_NOHASHDIR", "1");
                wtPb.environment().put("CCACHE_LOGFILE", new File(config.getCcacheDir(), "logs/ccache_" + shortCommit + ".log").getAbsolutePath());
                if (config.getCcacheReadonlyDirect()) wtPb.environment().put("CCACHE_READONLY_DIRECT", "1");
                wtPb.environment().put("CCACHE_STATS", config.getCcacheStatsEnabled() ? "true" : "false");
                if (!config.getCcacheNamespace().isEmpty()) wtPb.environment().put("CCACHE_NAMESPACE", config.getCcacheNamespace());
                if (!config.getCcacheSloppiness().isEmpty()) wtPb.environment().put("CCACHE_SLOPPINESS", config.getCcacheSloppiness());
                try { executeCommand(wtPb, "ccache", "-M", config.getCcacheMaxSize()); } catch (Exception ignore) {}
            }

            wtPb.environment().put("MAKEFLAGS", "-j" + config.getParallelJobs());
            List<String> buildCmd = new ArrayList<>();
            buildCmd.add("./build.sh");
            String normalizedArgs = DockerBuildManager.normalizeBuildArg(config.getBuildArg(), buildType);
            for (String token : normalizedArgs.trim().split("\\s+")) {
                if (!token.isEmpty()) buildCmd.add(token);
            }
            executeCommand(wtPb, buildCmd.toArray(new String[0]));

            String packageName = "cubrid_" + shortCommit + ".tar.gz";
            File packageFile = new File(workDir, packageName);
            ProcessBuilder tarPb = new ProcessBuilder();
            tarPb.directory(new File(wtDir, config.getBuildDir(buildType)));
            executeCommand(tarPb, "tar", "czf", packageFile.getAbsolutePath(), ".");
            success = true;
            return packageFile.getAbsolutePath();
        } finally {
            try { executeCommand(repoPb, "git", "worktree", "remove", "--force", wtDir.getAbsolutePath()); } catch (Exception e) { taskLogger.warning("Failed to remove worktree " + wtDir.getAbsolutePath() + ": " + e.getMessage()); }
            try { executeCommand(repoPb, "git", "branch", "-D", tempBranch); } catch (Exception ignore) {}
            if (!success) { try { deleteRecursively(wtDir); } catch (Exception ignore) {} }
        }
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
                    // Normalize commit to full SHA to make cache keys stable
                    String normalizedCommit = resolveFullCommitHashSafe(commit);
                    
                    // Skip the baseline commit itself - it should not be in the build targets
                    if (normalizedCommit.equals(baselineCommit)) {
                        taskLogger.info("Skipping baseline commit " + commit + " - it should not be a build target");
                        return null;
                    }
                    
                    progress.put(commit, 0); // Starting
                    String normalizedShort = normalizedCommit.substring(0, Math.min(7, normalizedCommit.length()));
                    
                    // Check in-memory cache first using normalized key (include baseline to avoid incorrect reuse)
                    String baselineShort = baselineCommit.substring(0, Math.min(7, baselineCommit.length()));
                    String cacheKey = normalizedCommit + "_" + buildType + "_" + baselineShort;
                    String cachedPackage = buildCache.get(cacheKey);
                    if (cachedPackage != null && new File(cachedPackage).exists()) {
                        // Validate baseline matches before reusing
                        if (validateCachedBaseline(cachedPackage, normalizedCommit, baselineCommit)) {
                            taskLogger.info("Using cached build for commit " + normalizedCommit + " (baseline: " + baselineShort + ")");
                            builtPackages.put(commit, cachedPackage);
                            progress.put(commit, 100); // Complete
                            
                            // Create build log for cached build
                            createCachedBuildLog(commit, cachedPackage, "memory cache");
                            // Ensure metadata exists for cached package so remote testers can validate without re-download
                            try { ensureBuildMetadata(cachedPackage, normalizedCommit, buildType, baselineCommit); } catch (Exception ignore) {}
                            
                            return null;
                        } else {
                            taskLogger.warning("Cached build validation failed for commit " + normalizedCommit + ", will rebuild");
                            buildCache.remove(cacheKey);
                        }
                    }

                    // Fall back to scanning disk for an existing package if memory cache missed
                    String diskPackage = findExistingBuildPackageOnDisk(normalizedShort, buildType, baselineCommit, normalizedCommit);
                    if (diskPackage != null) {
                        taskLogger.info("Using cached build from disk for commit " + normalizedCommit);
                        builtPackages.put(commit, diskPackage);
                        buildCache.put(cacheKey, diskPackage);
                        progress.put(commit, 100); // Complete
                        
                        // Create build log for cached build
                        createCachedBuildLog(commit, diskPackage, "disk cache");
                        // Ensure metadata exists for cached package on disk
                        try { ensureBuildMetadata(diskPackage, normalizedCommit, buildType, baselineCommit); } catch (Exception ignore) {}
                        
                        return null;
                    }
                    
                    progress.put(commit, 20); // Building
                    
                    // Create work directory for this commit
                    Path workDir = Files.createTempDirectory(
                        Paths.get(config.getWorkDir()), "build_" + normalizedShort + "_");
                    
                    // Build the commit (isolated on baseline via worktree + cherry-pick)
                    String buildPackage = buildCommit(normalizedCommit, buildType, workDir.toFile(), baselineCommit);
                    
                    if (buildPackage != null) {
                        builtPackages.put(commit, buildPackage);
                        buildCache.put(cacheKey, buildPackage);
                        // Persist metadata for validation in future requests
                        writeBuildMetadata(buildPackage, normalizedCommit, buildType, baselineCommit);
                        cleanBuildCache(config.getBuildCacheSize());
                    }
                    
                    progress.put(commit, 100); // Complete
                    buildsCompleted.incrementAndGet();
                    
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

    /**
     * Build commits SEQUENTIALLY using WorkloadDistributor
     * This ensures only one build runs at a time, maximizing ccache hit ratio
     * and avoiding concurrency issues with shared directories.
     */
    private Map<String, String> buildCommitsSequentially(JSONArray commits, String buildType, String baselineCommit)
            throws Exception {
        Map<String, String> builtPackages = new ConcurrentHashMap<>();

        // Capture the current request ID
        final String requestId = RequestContext.getRequestId();

        taskLogger.info(String.format("Building %d commits SEQUENTIALLY (one at a time)", commits.length()));

        // Build each commit one by one
        for (int i = 0; i < commits.length(); i++) {
            final String commit = commits.getString(i);

            try {
                // Set the request context
                if (requestId != null) {
                    RequestContext.setRequestId(requestId);
                }

                // Normalize commit to full SHA
                String normalizedCommit = resolveFullCommitHashSafe(commit);

                // Skip the baseline commit itself
                if (normalizedCommit.equals(baselineCommit)) {
                    taskLogger.info("Skipping baseline commit " + commit + " - it should not be a build target");
                    continue;
                }

                progress.put(commit, 0); // Starting
                String normalizedShort = normalizedCommit.substring(0, Math.min(7, normalizedCommit.length()));

                // Check build cache first
                String baselineShort = baselineCommit.substring(0, Math.min(7, baselineCommit.length()));
                String cacheKey = normalizedCommit + "_" + buildType + "_" + baselineShort;
                String cachedPackage = buildCache.get(cacheKey);

                if (cachedPackage != null && new File(cachedPackage).exists()) {
                    // Validate baseline matches before reusing
                    if (validateCachedBaseline(cachedPackage, normalizedCommit, baselineCommit)) {
                        taskLogger.info("Using cached build for commit " + normalizedCommit + " (baseline: " + baselineShort + ")");
                        builtPackages.put(commit, cachedPackage);
                        progress.put(commit, 100);
                        buildsCompleted.incrementAndGet();

                        // Since it's cached, we assume localhost has it
                        workloadDistributor.completeBuild("localhost:8089", normalizedCommit, cachedPackage);

                        createCachedBuildLog(commit, cachedPackage, "memory cache");
                        try { ensureBuildMetadata(cachedPackage, normalizedCommit, buildType, baselineCommit); } catch (Exception ignore) {}
                        continue;
                    } else {
                        taskLogger.warning("Cached build validation failed for commit " + normalizedCommit + ", will rebuild");
                        buildCache.remove(cacheKey);
                    }
                }

                // Check disk cache
                String diskPackage = findExistingBuildPackageOnDisk(normalizedShort, buildType, baselineCommit, normalizedCommit);
                if (diskPackage != null) {
                    taskLogger.info("Using cached build from disk for commit " + normalizedCommit);
                    builtPackages.put(commit, diskPackage);
                    buildCache.put(cacheKey, diskPackage);
                    progress.put(commit, 100);
                    buildsCompleted.incrementAndGet();

                    // Assume localhost has it
                    workloadDistributor.completeBuild("localhost:8089", normalizedCommit, diskPackage);

                    createCachedBuildLog(commit, diskPackage, "disk cache");
                    try { ensureBuildMetadata(diskPackage, normalizedCommit, buildType, baselineCommit); } catch (Exception ignore) {}
                    continue;
                }

                progress.put(commit, 20); // Building

                // Assign build to a node using WorkloadDistributor
                String assignedNode = workloadDistributor.assignBuild(normalizedCommit, buildType, baselineCommit);
                taskLogger.info(String.format("Build %d/%d: commit %s assigned to node %s",
                    i + 1, commits.length(), normalizedShort, assignedNode));

                String buildPackage = null;

                // Check if assigned node is localhost
                if (workloadDistributor.isNodeLocal(assignedNode)) {
                    // Build locally
                    taskLogger.info("Building locally on " + assignedNode);
                    Path workDir = Files.createTempDirectory(
                        Paths.get(config.getWorkDir()), "build_" + normalizedShort + "_");
                    buildPackage = buildCommit(normalizedCommit, buildType, workDir.toFile(), baselineCommit);
                } else {
                    // Build remotely
                    taskLogger.info("Triggering remote build on " + assignedNode);
                    try {
                        JSONObject result = RemoteBuildClient.triggerRemoteBuild(
                            assignedNode, normalizedCommit, buildType, baselineCommit);

                        if ("success".equals(result.optString("status"))) {
                            buildPackage = result.optString("packagePath");
                            taskLogger.info("Remote build succeeded: " + buildPackage);
                        } else {
                            taskLogger.warning("Remote build failed: " + result.optString("message"));
                        }
                    } catch (IOException e) {
                        taskLogger.log(Level.SEVERE, "Remote build request failed", e);
                    }
                }

                // Mark build complete in WorkloadDistributor
                workloadDistributor.completeBuild(assignedNode, normalizedCommit, buildPackage);

                if (buildPackage != null && !buildPackage.isEmpty()) {
                    builtPackages.put(commit, buildPackage);
                    buildCache.put(cacheKey, buildPackage);
                    writeBuildMetadata(buildPackage, normalizedCommit, buildType, baselineCommit);
                    cleanBuildCache(config.getBuildCacheSize());
                    progress.put(commit, 100);
                    buildsCompleted.incrementAndGet();
                } else {
                    taskLogger.severe("Build failed for commit " + normalizedCommit);
                    builtPackages.put(commit, "");
                    progress.put(commit, -1);
                }

            } catch (Exception e) {
                taskLogger.log(Level.SEVERE, "Failed to build commit " + commit, e);
                builtPackages.put(commit, "");
                progress.put(commit, -1);
            } finally {
                RequestContext.clear();
            }
        }

        if (requestId != null) {
            RequestContext.setRequestId(requestId);
        }

        taskLogger.info("Sequential build completed. Successfully built: " +
            builtPackages.entrySet().stream().filter(e -> !e.getValue().isEmpty()).count() +
            "/" + commits.length());

        return builtPackages;
    }

    /**
     * Resolve the given commit identifier to a full 40-character SHA using the
     * configured repository. If resolution fails, returns the original string.
     */
    private String resolveFullCommitHashSafe(String commit) {
        try {
            File repoRoot = new File(config.getCubridSrcDir());
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(repoRoot);
            String out = executeCommandAndGetOutput(pb, "git", "rev-parse", commit).trim();
            if (out != null && !out.isEmpty()) {
                return out;
            }
        } catch (Exception ignore) { }
        return commit;
    }



    private static final class TesterCaps {
        final int heavyCap;
        final int peakCap;
        TesterCaps(int heavyCap, int peakCap) {
            this.heavyCap = heavyCap;
            this.peakCap = peakCap;
        }
    }

    private TesterCaps fetchTesterCaps(String workerIp) {
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
                if (json.has("concurrency")) {
                    JSONObject concurrency = json.getJSONObject("concurrency");

                    int heavyLimit = concurrency.optInt("maxWhileHeavy",
                            concurrency.optInt("max", 0));
                    if (heavyLimit > 0) {
                        int peak = concurrency.optInt("maxAfterHeavy",
                                concurrency.optInt("maxPeak",
                                        concurrency.optInt("max", heavyLimit)));
                        return new TesterCaps(Math.max(1, heavyLimit), Math.max(heavyLimit, peak));
                    }

                    // Fall back to currently active limit if heavy cap missing
                    int activeLimit = concurrency.optInt("activeLimit", 0);
                    if (activeLimit > 0) {
                        int peak = concurrency.optInt("maxAfterHeavy",
                                concurrency.optInt("maxPeak", activeLimit));
                        return new TesterCaps(Math.max(1, activeLimit), Math.max(activeLimit, peak));
                    }

                    // Legacy fallback
                    if (concurrency.has("max")) {
                        int max = concurrency.optInt("max", 4);
                        return new TesterCaps(Math.max(1, max), Math.max(1, max));
                    }
                }
                if (json.has("maxConcurrentTests")) {
                    int max = json.getInt("maxConcurrentTests");
                    return new TesterCaps(Math.max(1, max), Math.max(1, max));
                }
            } else {
                taskLogger.warning("Health check responded with status: " + status + " from " + host + ":" + port);
            }
        } catch (Exception e) {
            taskLogger.log(Level.WARNING, "Failed to fetch tester concurrency from health endpoint, using default", e);
        }
        // Fallback to a sane default if tester does not report or on error
        return new TesterCaps(4, 4);
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
            // 2) Ensure clean baseline state and cherry-pick the target commit
            ProcessBuilder wtPb = new ProcessBuilder();
            wtPb.directory(wtDir);
            
            // Always start from a clean baseline state to ensure consistent version numbering
            taskLogger.info("Resetting to baseline before cherry-picking " + commit);
            executeCommand(wtPb, "git", "checkout", "-f", baselineCommit);
            executeCommand(wtPb, "git", "reset", "--hard", baselineCommit);
            executeCommand(wtPb, "git", "clean", "-fdx");
            
            // Now cherry-pick the target commit onto the clean baseline
            boolean isMerge = isMergeCommit(commit, repoRoot);
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
            executeCommand(wtPb, "rm", "-rf", config.getBuildDir(buildType));
            executeCommand(wtPb, "rm", "-rf", "cubridmanager"); // temporary fix parity

            // Set up ccache environment if enabled
            if (config.isCcacheEnabled()) {
                try {
                    File logsDir = new File(config.getCcacheDir(), "logs");
                    if (!logsDir.exists()) logsDir.mkdirs();
                    File tmpDir = new File(config.getCcacheDir(), "tmp");
                    if (!tmpDir.exists()) tmpDir.mkdirs();
                } catch (Exception ignore) {}
                wtPb.environment().put("CC", "ccache gcc");
                wtPb.environment().put("CXX", "ccache g++");
                wtPb.environment().put("CCACHE_DIR", config.getCcacheDir());
                wtPb.environment().put("CCACHE_COMPILERCHECK", config.getCcacheCompilerCheck());
                wtPb.environment().put("CCACHE_HARDLINK", config.getCcacheHardlink() ? "1" : "0");
                wtPb.environment().put("CCACHE_MAXSIZE", config.getCcacheMaxSize());
                wtPb.environment().put("CCACHE_BASEDIR", new File(config.getWorkDir()).getAbsolutePath());
                wtPb.environment().put("CCACHE_NOHASHDIR", "1");
                wtPb.environment().put("CCACHE_LOGFILE", new File(config.getCcacheDir(), "logs/ccache_" + shortCommit + ".log").getAbsolutePath());
                if (config.getCcacheReadonlyDirect()) wtPb.environment().put("CCACHE_READONLY_DIRECT", "1");
                wtPb.environment().put("CCACHE_STATS", config.getCcacheStatsEnabled() ? "true" : "false");
                if (!config.getCcacheNamespace().isEmpty()) wtPb.environment().put("CCACHE_NAMESPACE", config.getCcacheNamespace());
                if (!config.getCcacheSloppiness().isEmpty()) wtPb.environment().put("CCACHE_SLOPPINESS", config.getCcacheSloppiness());
                try { executeCommand(wtPb, "ccache", "-M", config.getCcacheMaxSize()); } catch (Exception ignore) {}
            }

            // Build command with output capture for logging
            List<String> buildCmd = new ArrayList<>();
            buildCmd.add("./build.sh");
            String normalizedArgs = DockerBuildManager.normalizeBuildArg(config.getBuildArg(), buildType);
            for (String token : normalizedArgs.trim().split("\\s+")) {
                if (!token.isEmpty()) buildCmd.add(token);
            }
            
            // Capture build output
            String buildOutput = executeCommandAndGetOutput(wtPb, buildCmd.toArray(new String[0]));
            
            // Save build log
            try {
                String requestId = RequestContext.getRequestId();
                if (requestId != null && config.isRequestGroupingEnabled()) {
                    String buildsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "builds");
                    String buildLogFile = String.format("build_%s.log", commit.substring(0, 7));
                    Path logFile = Paths.get(buildsDir, buildLogFile);
                    Files.write(logFile, buildOutput.getBytes("UTF-8"));
                    taskLogger.info("Saved build log to: " + logFile.toString());
                }
            } catch (Exception e) {
                taskLogger.warning("Failed to save build log: " + e.getMessage());
            }

            // 5) Create package from the isolated worktree build output
            String packageName = "cubrid_" + commit.substring(0, 7) + ".tar.gz";
            File packageFile = new File(workDir, packageName);

            ProcessBuilder tarPb = new ProcessBuilder();
            tarPb.directory(new File(wtDir, config.getBuildDir(buildType)));
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

    /**
     * Write a small metadata JSON alongside the built package to allow safe reuse
     * across requests and validate buildType/baseline consistency quickly.
     */
    private void writeBuildMetadata(String packagePath, String fullCommit, String buildType, String baselineCommit) {
        try {
            File pkg = new File(packagePath);
            File meta = new File(pkg.getParentFile(), pkg.getName() + ".meta.json");
            JSONObject j = new JSONObject();
            j.put("commit", fullCommit);
            j.put("commitShort", fullCommit.substring(0, Math.min(7, fullCommit.length())));
            j.put("buildType", buildType);
            j.put("baseline", baselineCommit);
            j.put("createdAt", System.currentTimeMillis());
            try (FileWriter w = new FileWriter(meta)) {
                w.write(j.toString());
            }
        } catch (Exception ignore) { }
    }

    /**
     * Ensure a .meta.json exists for a given package; if missing or empty, (re)write it.
     */
    private void ensureBuildMetadata(String packagePath, String fullCommit, String buildType, String baselineCommit) {
        try {
            File pkg = new File(packagePath);
            File meta = new File(pkg.getParentFile(), pkg.getName() + ".meta.json");
            boolean needsWrite = true;
            if (meta.exists() && meta.isFile()) {
                try (BufferedReader br = new BufferedReader(new FileReader(meta))) {
                    String content = br.readLine();
                    if (content != null && content.trim().length() > 1) {
                        needsWrite = false; // metadata exists and is non-empty
                    }
                } catch (Exception ignore) {
                    needsWrite = true;
                }
            }
            if (needsWrite) {
                writeBuildMetadata(packagePath, fullCommit, buildType, baselineCommit);
            }
        } catch (Exception ignore) { }
    }

    /**
     * Validate that a cached build package matches the expected baseline.
     * Returns true if baseline matches or if metadata cannot be read (fail-open for backward compatibility).
     */
    private boolean validateCachedBaseline(String packagePath, String expectedCommit, String expectedBaseline) {
        try {
            File pkg = new File(packagePath);
            File meta = new File(pkg.getParentFile(), pkg.getName() + ".meta.json");
            
            if (!meta.exists()) {
                taskLogger.warning("No metadata found for cached build, cannot validate baseline: " + packagePath);
                return false; // Fail-closed: reject cache entries without metadata
            }
            
            // Read and parse metadata
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(meta))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            JSONObject metadata = new JSONObject(sb.toString());
            
            // Validate commit matches
            String metaCommit = metadata.optString("commit", "");
            String metaCommitShort = metaCommit.length() > 7 ? metaCommit.substring(0, 7) : metaCommit;
            String expectedCommitShort = expectedCommit.length() > 7 ? expectedCommit.substring(0, 7) : expectedCommit;
            
            if (!metaCommitShort.equals(expectedCommitShort)) {
                taskLogger.warning(String.format("Cached build rejected: commit mismatch (cached: %s, expected: %s)", 
                    metaCommitShort, expectedCommitShort));
                return false;
            }
            
            // Validate baseline matches
            String metaBaseline = metadata.optString("baseline", "");
            String metaBaselineShort = metaBaseline.length() > 7 ? metaBaseline.substring(0, 7) : metaBaseline;
            String expectedBaselineShort = expectedBaseline != null && expectedBaseline.length() > 7 ? 
                                          expectedBaseline.substring(0, 7) : expectedBaseline;
            
            if (expectedBaselineShort != null && !metaBaselineShort.equals(expectedBaselineShort)) {
                taskLogger.warning(String.format("Cached build rejected: baseline mismatch (cached: %s, expected: %s)", 
                    metaBaselineShort, expectedBaselineShort));
                return false;
            }
            
            taskLogger.info(String.format("Cached build validation passed: commit=%s, baseline=%s", 
                metaCommitShort, metaBaselineShort));
            return true;
            
        } catch (Exception e) {
            taskLogger.warning("Failed to validate cached build metadata: " + e.getMessage());
            return false; // Fail-closed on errors
        }
    }

    /**
     * Try to find an existing package on disk and verify it matches the expected
     * buildType and baseline (when metadata is present). Returns null if not found
     * or if validation fails.
     */
    private String findExistingBuildPackageOnDisk(String commitShort, String buildType, String baselineCommit, String fullCommit) {
        try {
            File workDirRoot = new File(config.getWorkDir());
            if (!workDirRoot.exists() || !workDirRoot.isDirectory()) {
                return null;
            }
            File[] buildDirs = workDirRoot.listFiles(f -> f.isDirectory() && f.getName().startsWith("build_"));
            if (buildDirs == null || buildDirs.length == 0) {
                return null;
            }
            
            String targetName = "cubrid_" + commitShort + ".tar.gz";
            File bestCandidate = null;
            long bestMtime = Long.MIN_VALUE;
            
            // Search through all build directories to find the best matching package
            for (File dir : buildDirs) {
                File candidate = new File(dir, targetName);
                if (!candidate.exists() || !candidate.isFile()) {
                    continue;
                }
                
                // Check if this candidate has the correct metadata
                File meta = new File(candidate.getParentFile(), candidate.getName() + ".meta.json");
                if (!meta.exists()) {
                    taskLogger.warning("No metadata found for cached build " + commitShort + " in " + dir.getName() + ", skipping to ensure baseline consistency");
                    continue; // no metadata, skip to be safe
                }
                
                // Validate metadata
                try {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new FileReader(meta))) {
                        String line; while ((line = br.readLine()) != null) sb.append(line);
                    }
                    JSONObject j = new JSONObject(sb.toString());
                    
                    // Validate commit matches
                    String metaCommit = j.optString("commitShort", j.optString("commit", ""));
                    if (metaCommit.length() > 7) metaCommit = metaCommit.substring(0, 7);
                    if (!metaCommit.equals(commitShort)) {
                        taskLogger.warning(String.format("Cached build in %s rejected for %s: commit mismatch (cached: %s, requested: %s)", 
                            dir.getName(), commitShort, metaCommit, commitShort));
                        continue;
                    }
                    
                    // Validate build type matches  
                    String metaBuildType = j.optString("buildType", buildType);
                    if (!buildType.equals(metaBuildType)) {
                        taskLogger.warning(String.format("Cached build in %s rejected for %s: buildType mismatch (cached: %s, requested: %s)", 
                            dir.getName(), commitShort, metaBuildType, buildType));
                        continue;
                    }
                    
                    // Validate baseline matches - this is the critical fix
                    String metaBaseline = j.optString("baseline", null);
                    if (metaBaseline != null && baselineCommit != null && !metaBaseline.equals(baselineCommit)) {
                        taskLogger.warning(String.format("Cached build in %s rejected for %s: baseline mismatch (cached baseline: %s, current baseline: %s)", 
                            dir.getName(), commitShort, metaBaseline.substring(0, Math.min(7, metaBaseline.length())), 
                            baselineCommit.substring(0, Math.min(7, baselineCommit.length()))));
                        continue;
                    }
                    
                    // This candidate passes all validation checks
                    long mtime = candidate.lastModified();
                    if (mtime > bestMtime) {
                        bestCandidate = candidate;
                        bestMtime = mtime;
                    }
                    
                } catch (Exception e) {
                    taskLogger.warning("Error validating cached build metadata for " + commitShort + " in " + dir.getName() + ": " + e.getMessage());
                    continue;
                }
            }
            
            if (bestCandidate != null) {
                taskLogger.info(String.format("Cached build validation passed for %s (buildType: %s, baseline: %s) from %s", 
                    commitShort, buildType, 
                    baselineCommit != null ? baselineCommit.substring(0, Math.min(7, baselineCommit.length())) : "null",
                    bestCandidate.getParentFile().getName()));
                return bestCandidate.getAbsolutePath();
            }
            
            return null;
        } catch (Exception e) {
            taskLogger.warning("Error searching for cached build for " + commitShort + ": " + e.getMessage());
            return null;
        }
    }

    private String determineBaselineCommit(JSONArray commits) throws Exception {
        if (commits == null || commits.length() == 0) {
            throw new IllegalArgumentException("No commits provided");
        }
        
        File repoRoot = new File(config.getCubridSrcDir());
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(repoRoot);
        
        // If only one commit, use it directly
        if (commits.length() == 1) {
            String commit = commits.getString(0);
            String base = executeCommandAndGetOutput(pb, "git", "rev-parse", commit + "^").trim();
            if (base.isEmpty()) {
                throw new RuntimeException("Failed to determine baseline for " + commit);
            }
            return base;
        }
        
        // For multiple commits, find the chronologically earliest one
        String earliestCommit = null;
        long earliestTimestamp = Long.MAX_VALUE;
        
        taskLogger.info("Finding earliest commit among " + commits.length() + " commits by commit date...");
        
        for (int i = 0; i < commits.length(); i++) {
            String commit = commits.getString(i);
            
            try {
                // Get commit timestamp
                String timestampStr = executeCommandAndGetOutput(pb, "git", "log", "-1", "--format=%ct", commit).trim();
                long timestamp = Long.parseLong(timestampStr);
                
                taskLogger.info(String.format("Commit %s has timestamp %d", 
                    commit.substring(0, Math.min(7, commit.length())), timestamp));
                
                if (timestamp < earliestTimestamp) {
                    earliestTimestamp = timestamp;
                    earliestCommit = commit;
                }
            } catch (Exception e) {
                taskLogger.warning("Failed to get timestamp for commit " + commit + ": " + e.getMessage());
                // If we can't get timestamp, treat it as very old to be conservative
                if (earliestTimestamp == Long.MAX_VALUE) {
                    earliestCommit = commit;
                    earliestTimestamp = 0;
                }
            }
        }
        
        if (earliestCommit == null) {
            throw new RuntimeException("Could not determine earliest commit");
        }
        
        taskLogger.info(String.format("Earliest commit determined: %s (timestamp: %d)", 
            earliestCommit.substring(0, Math.min(7, earliestCommit.length())), earliestTimestamp));
        
        // Get the parent of the earliest commit as baseline
        String base = executeCommandAndGetOutput(pb, "git", "rev-parse", earliestCommit + "^").trim();
        if (base.isEmpty()) {
            throw new RuntimeException("Failed to determine baseline for earliest commit " + earliestCommit);
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
                               String workerIp, String baselineCommit, String buildType) {
        return runTest(commit, buildPackage, testPath, workerIp, baselineCommit, buildType, null);
    }

    private JSONObject runTest(String commit, String buildPackage, String testPath,
                               String workerIp, String baselineCommit, String buildType, TestInstance testInstance) {
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
            
            // Extract custom script from build request if present
            String customShellScript = request.optString("customShellScript", null);
            String customScriptTestPath = request.optString("customScriptTestPath", null);

            // Prepare test request
            String testDir, testScript, testName;

            // Check if this is a custom script placeholder
            if (testPath.equals("custom_script_test")) {
                // Custom script without test path - use temporary directory
                testDir = "/tmp/custom_test_execution";
                testScript = "custom_script.sh";
                testName = "custom_script_" + System.currentTimeMillis();
            } else if (customShellScript != null && customScriptTestPath != null && !customScriptTestPath.isEmpty()) {
                // Custom script with test path provided - use that for environment setup
                testDir = config.getShellTcDir() + "/" +
                         customScriptTestPath.substring(0, customScriptTestPath.lastIndexOf("/"));
                testScript = customScriptTestPath.substring(customScriptTestPath.lastIndexOf("/") + 1);
                testName = testScript.replace(".sh", "");
            } else {
                // Standard mode - extract from testPath
                testDir = config.getShellTcDir() + "/" +
                         testPath.substring(0, testPath.lastIndexOf("/"));
                testScript = testPath.substring(testPath.lastIndexOf("/") + 1);
                testName = testScript.replace(".sh", "");
            }
            
            // Determine if this is a local or remote tester
            String buildPackageRef;
            if (isLocalTester(host)) {
                // Local tester - use direct file path (tester will skip if Docker image is cached)
                buildPackageRef = buildPackage;
                taskLogger.info("Sending package reference to local tester " + workerIp + ": " + buildPackage);
            } else {
                // Remote tester - provide HTTP URL for download (tester will skip if Docker image is cached)
                File packageFile = new File(buildPackage);
                String builderHost = InetAddress.getLocalHost().getHostAddress();
                buildPackageRef = String.format("http://%s:%d/download/build/%s",
                    builderHost, config.getListenPort(), packageFile.getName());
                taskLogger.info("Sending package URL to remote tester " + workerIp + ": " + buildPackageRef);
            }
            
            // Resolve run parameters with request-level overrides (camelCase or snake_case)
            String runModeOverride = request.optString("runMode", request.optString("run_mode", config.getRunMode()));
            int minRunsOverride = request.has("minRuns") ? request.optInt("minRuns", config.getMinRuns()) :
                                  (request.has("min_runs") ? request.optInt("min_runs", config.getMinRuns()) : config.getMinRuns());
            int maxRunsOverride = request.has("maxRuns") ? request.optInt("maxRuns", config.getMaxRuns()) :
                                  (request.has("max_runs") ? request.optInt("max_runs", config.getMaxRuns()) : config.getMaxRuns());
            Long timeBudgetOverride = null;
            if (request.has("timeBudgetMs")) {
                long tb = request.optLong("timeBudgetMs", -1);
                if (tb >= 1) timeBudgetOverride = tb;
            } else if (request.has("time_budget_ms")) {
                long tb = request.optLong("time_budget_ms", -1);
                if (tb >= 1) timeBudgetOverride = tb;
            } else {
                timeBudgetOverride = config.getTimeBudgetMs();
            }

            JSONObject testRequest = new JSONObject()
                .put("buildPackage", buildPackageRef)
                .put("testPath", testPath)
                .put("testDir", testDir)
                .put("testScript", testScript)
                .put("testName", testName)
                .put("testKey", testPath)  // Add testKey for test tracking
                .put("commit", commit)  // Add full commit hash
                .put("commitShort", commit.substring(0, Math.min(commit.length(), 7)))  // Add short commit
                .put("baseline", baselineCommit)  // Add baseline commit for Docker image differentiation
                .put("baselineShort", baselineCommit.substring(0, Math.min(baselineCommit.length(), 7)))  // Add short baseline
                .put("expectedBuildVersion", commit.substring(0, 7))
                .put("buildType", buildType != null ? buildType : "debug")  // Add build type for container naming
                .put("keepAlive", false)
                .put("runMode", runModeOverride)
                .put("minRuns", minRunsOverride)
                .put("maxRuns", maxRunsOverride);

            // Optional time budget
            if (timeBudgetOverride != null) {
                testRequest.put("timeBudgetMs", timeBudgetOverride);
            }

            // Add predicted demands if available (smart scheduling)
            if (testInstance != null && testInstance.getConfidence() > 0.0) {
                JSONObject predicted = new JSONObject()
                    .put("durationMs", testInstance.getPredictedDurationMs())
                    .put("cpuPct", testInstance.getPredictedCpuPct())
                    .put("memMb", testInstance.getPredictedMemMb())
                    .put("ioMbPerSec", testInstance.getPredictedIoMbPerSec())
                    .put("ioReadBytesPerSec", (long) (testInstance.getPredictedIoReadMbPerSec() * 1024 * 1024))
                    .put("ioWriteBytesPerSec", (long) (testInstance.getPredictedIoWriteMbPerSec() * 1024 * 1024))
                    .put("iops", testInstance.getPredictedIops())
                    .put("netMbPerSec", testInstance.getPredictedNetMbPerSec())
                    .put("confidence", testInstance.getConfidence());
                testRequest.put("predicted", predicted);
                taskLogger.fine(String.format("Sending test with predictions: CPU=%.1f%%, mem=%.0fMB, IO_R=%.1fMB/s, IO_W=%.1fMB/s, conf=%.2f",
                    testInstance.getPredictedCpuPct(), testInstance.getPredictedMemMb(),
                    testInstance.getPredictedIoReadMbPerSec(), testInstance.getPredictedIoWriteMbPerSec(),
                    testInstance.getConfidence()));
            }

            // Add request ID if available
            String requestId = RequestContext.getRequestId();
            if (requestId != null) {
                testRequest.put("requestId", requestId);
            }

            // Add custom shell script if provided
            if (customShellScript != null && !customShellScript.isEmpty()) {
                testRequest.put("customShellScript", customShellScript);
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
            
            // Read response - check if it's multipart or JSON
            int httpStatus = conn.getResponseCode();
            String contentType = conn.getHeaderField("Content-Type");
            taskLogger.info("Tester response HTTP " + httpStatus + " for '" + testName + "' on " + host + ":" + port + " (Content-Type: " + contentType + ")");
            
            JSONObject responseJson = null;
            Map<String, byte[]> logFiles = new HashMap<>();
            
            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                // Parse multipart response
                String boundary = null;
                String[] parts = contentType.split(";");
                for (String part : parts) {
                    part = part.trim();
                    if (part.startsWith("boundary=")) {
                        boundary = part.substring(9);
                        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                            boundary = boundary.substring(1, boundary.length() - 1);
                        }
                        break;
                    }
                }
                
                if (boundary != null) {
                    // Read the entire response body
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (InputStream is = (httpStatus >= 200 && httpStatus < 300) ? conn.getInputStream() : conn.getErrorStream()) {
                        if (is != null) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                baos.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                    
                    // Parse multipart data
                    MultipartHelper.MultipartRequest multipartData = parseMultipartResponse(baos.toByteArray(), boundary);
                    
                    // Extract JSON response
                    String jsonResponse = multipartData.getField("response");
                    if (jsonResponse != null) {
                        responseJson = new JSONObject(jsonResponse);
                    }
                    
                    // Extract log files
                    for (Map.Entry<String, MultipartHelper.MultipartRequest.FileData> entry : multipartData.getFiles().entrySet()) {
                        String fieldName = entry.getKey();
                        MultipartHelper.MultipartRequest.FileData fileData = entry.getValue();
                        logFiles.put(fileData.fileName, fileData.content);
                    }
                    
                    taskLogger.info("Received multipart response with " + logFiles.size() + " log files");
                }
            } else {
                // Parse regular JSON response (backward compatibility)
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
                
                // Log tester response payload for visibility
                try {
                    String payload = response.length() == 0 ? "{}" : response.toString();
                    taskLogger.info("Tester response payload for '" + testName + "' on " + host + ":" + port + ": " + payload);
                } catch (Exception ignore) { }
                
                try {
                    responseJson = new JSONObject(response.length() == 0 ? "{}" : response.toString());
                } catch (Exception parseEx) {
                    responseJson = new JSONObject().put("status", httpStatus >= 200 && httpStatus < 300 ? "unknown" : "execution_error")
                                                  .put("message", "Tester returned HTTP " + httpStatus);
                }
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
            
            // Save log files received via multipart
            if (!logFiles.isEmpty()) {
                try {
                    if (requestId != null && config.isRequestGroupingEnabled()) {
                        String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                        
                        taskLogger.info("Saving " + logFiles.size() + " log files from multipart response");
                        
                        Path lastLogPath = null;
                        for (Map.Entry<String, byte[]> entry : logFiles.entrySet()) {
                            String fileName = entry.getKey();
                            byte[] content = entry.getValue();
                            
                            Path logFile = Paths.get(testsDir, fileName);
                            Files.write(logFile, content);
                            lastLogPath = logFile;
                            
                            taskLogger.info("Saved log file: " + logFile.toString() + " (" + content.length + " bytes)");
                        }
                        
                        // Set logPath to the last log file for backward compatibility
                        if (lastLogPath != null) {
                            result.put("logPath", lastLogPath.toString());
                        }
                    }
                } catch (Exception e) {
                    taskLogger.warning("Failed to save multipart log files: " + e.getMessage());
                }
            }
            // Handle attempt log metadata from JSON response - fetch actual log content from remote tester or copy from local filesystem
            else if (responseJson.has("attemptLogMetadata")) {
                try {
                    JSONArray metadata = responseJson.getJSONArray("attemptLogMetadata");
                    boolean isLocal = isLocalTester(host);
                    
                    if (isLocal) {
                        taskLogger.info("Received metadata for " + metadata.length() + " attempt logs - copying from local filesystem");
                    } else {
                        taskLogger.info("Received metadata for " + metadata.length() + " attempt logs - fetching content from remote tester");
                    }
                    
                    if (requestId != null && config.isRequestGroupingEnabled()) {
                        String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                        
                        for (int i = 0; i < metadata.length(); i++) {
                            JSONObject attemptMeta = metadata.getJSONObject(i);
                            String logFileName = attemptMeta.getString("logFileName");
                            int attemptNum = attemptMeta.getInt("attempt");
                            String status = attemptMeta.optString("status", "unknown");
                            
                            // Add small delay between remote log fetches to avoid overwhelming the remote tester
                            if (!isLocal && i > 0) {
                                try {
                                    Thread.sleep(500); // 500ms delay between remote log fetches
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            
                            try {
                                if (isLocal) {
                                    // Local tester - copy log file directly from filesystem
                                    Path sourceLogFile = findLocalLogFile(requestId, logFileName);
                                    if (sourceLogFile != null && Files.exists(sourceLogFile)) {
                                        Path destLogFile = Paths.get(testsDir, logFileName);
                                        if (!sourceLogFile.equals(destLogFile)) {
                                            byte[] logContent = Files.readAllBytes(sourceLogFile);
                                            Files.write(destLogFile, logContent);
                                            taskLogger.info("Copied attempt " + attemptNum + " log (" + status + ") from " + sourceLogFile.toString() + " to: " + destLogFile.toString());
                                        } else {
                                            taskLogger.info("Log file already in correct location for attempt " + attemptNum + " (" + status + "): " + destLogFile.toString());
                                        }
                                    } else {
                                        taskLogger.warning("Local log file not found for: " + logFileName);
                                    }
                                } else {
                                    // Remote tester - fetch log content via HTTP with retry
                                    String logContent = null;
                                    int maxRetries = 3;
                                    for (int retry = 0; retry < maxRetries; retry++) {
                                        try {
                                            logContent = fetchLogContentFromRemoteTester(host, port, logFileName);
                                            if (logContent != null && !logContent.isEmpty()) {
                                                break; // Success, exit retry loop
                                            }
                                            if (retry < maxRetries - 1) {
                                                taskLogger.info("Retrying log fetch for " + logFileName + " (attempt " + (retry + 2) + "/" + maxRetries + ")");
                                                Thread.sleep(1000 * (retry + 1)); // Exponential backoff: 1s, 2s, 3s
                                            }
                                        } catch (Exception fetchEx) {
                                            if (retry == maxRetries - 1) {
                                                throw fetchEx; // Re-throw on final attempt
                                            }
                                            taskLogger.warning("Log fetch attempt " + (retry + 1) + " failed for " + logFileName + ": " + fetchEx.getMessage());
                                            Thread.sleep(1000 * (retry + 1)); // Exponential backoff
                                        }
                                    }
                                    
                                    if (logContent != null && !logContent.isEmpty()) {
                                        Path logFile = Paths.get(testsDir, logFileName);
                                        Files.write(logFile, logContent.getBytes("UTF-8"));
                                        taskLogger.info("Saved attempt " + attemptNum + " log (" + status + ") to: " + logFile.toString());
                                    } else {
                                        taskLogger.warning("Empty or null log content received for: " + logFileName + " after " + maxRetries + " attempts");
                                    }
                                }
                            } catch (Exception logFetchEx) {
                                taskLogger.warning("Failed to " + (isLocal ? "copy" : "fetch") + " log content for " + logFileName + ": " + logFetchEx.getMessage());
                            }
                        }
                        
                        // Set logPath to the final attempt for backward compatibility
                        if (metadata.length() > 0) {
                            JSONObject lastAttemptMeta = metadata.getJSONObject(metadata.length() - 1);
                            String lastFileName = lastAttemptMeta.getString("logFileName");
                            result.put("logPath", Paths.get(testsDir, lastFileName).toString());
                        }
                    }
                    
                    // Store metadata for report generation
                    result.put("attemptLogMetadata", metadata);
                } catch (Exception e) {
                    taskLogger.warning("Failed to process attempt log metadata: " + e.getMessage());
                }
            }
            // Handle multiple attempt logs from tester (old JSON-embedded format for backward compatibility)
            if (responseJson.has("allAttemptLogs")) {
                try {
                    if (requestId != null && config.isRequestGroupingEnabled()) {
                        String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                        JSONArray allAttemptLogs = responseJson.getJSONArray("allAttemptLogs");
                        
                        taskLogger.info("Received " + allAttemptLogs.length() + " attempt logs from remote tester");
                        
                        for (int i = 0; i < allAttemptLogs.length(); i++) {
                            JSONObject attemptLog = allAttemptLogs.getJSONObject(i);
                            String logContent = attemptLog.getString("logContent");
                            String logFileName = attemptLog.getString("logFileName");
                            boolean logTruncated = attemptLog.optBoolean("logTruncated", false);
                            int attemptNum = attemptLog.getInt("attempt");
                            
                            Path logFile = Paths.get(testsDir, logFileName);
                            Files.write(logFile, logContent.getBytes("UTF-8"));
                            
                            taskLogger.info("Saved attempt " + attemptNum + " log to: " + logFile.toString() + 
                                           (logTruncated ? " (truncated)" : ""));
                        }
                        
                        // Set logPath to the final attempt for backward compatibility
                        if (allAttemptLogs.length() > 0) {
                            JSONObject lastAttemptLog = allAttemptLogs.getJSONObject(allAttemptLogs.length() - 1);
                            String lastFileName = lastAttemptLog.getString("logFileName");
                            result.put("logPath", Paths.get(testsDir, lastFileName).toString());
                        }
                    }
                } catch (Exception e) {
                    taskLogger.warning("Failed to save multiple attempt logs: " + e.getMessage());
                }
            }
            // Handle single log content from tester (backward compatibility)
            else if (responseJson.has("logContent")) {
                String logContent = responseJson.getString("logContent");
                String logFileName = responseJson.optString("logFileName", null);
                boolean logTruncated = responseJson.optBoolean("logTruncated", false);
                
                // Save log to request directory
                try {
                    if (requestId != null && config.isRequestGroupingEnabled()) {
                        String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                        String safeTestName = testName.replaceAll("[^a-zA-Z0-9_.-]", "_");
                        String commitShort = commit.substring(0, Math.min(commit.length(), 7));
                        
                        // Use the filename from Tester if provided, otherwise generate one
                        String fileName = logFileName != null ? logFileName : 
                            String.format("test_%s_%s.log", commitShort, safeTestName);
                        
                        Path logFile = Paths.get(testsDir, fileName);
                        Files.write(logFile, logContent.getBytes("UTF-8"));
                        
                        taskLogger.info("Saved test execution log to: " + logFile.toString() + 
                                       (logTruncated ? " (truncated)" : ""));
                        
                        // Add log path to result for later reference
                        result.put("logPath", logFile.toString());
                    }
                } catch (Exception e) {
                    taskLogger.warning("Failed to save test log: " + e.getMessage());
                }
            }
            
            return result;
                
        } catch (Exception e) {
            // Determine if this is a network/communication failure that should trigger circuit breaker
            boolean isNetworkFailure = isNetworkError(e);

            if (isNetworkFailure) {
                // Network failure - mark node as failed for circuit breaker
                String nodeId = workerIp.contains(":") ? workerIp : workerIp + ":8089";
                if (workloadDistributor != null) {
                    workloadDistributor.markNodeFailed(nodeId, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                taskLogger.log(Level.SEVERE, "Network failure testing commit " + commit +
                          " with test " + testPath + " on " + workerIp + " (node marked as failed)", e);
            } else {
                // Other failure (test execution error, not network issue)
                taskLogger.log(Level.SEVERE, "Failed to test commit " + commit +
                          " with test " + testPath + " on " + workerIp, e);
            }

            return new JSONObject()
                .put("commit", commit)
                .put("test", testPath)
                .put("status", "error")
                .put("message", e.getMessage());
        }
    }

    /**
     * Determines if an exception indicates a network/communication failure.
     * Used to trigger circuit breaker for unreachable nodes.
     */
    private boolean isNetworkError(Exception e) {
        if (e == null) return false;

        // Check exception types that indicate network issues
        if (e instanceof java.net.SocketTimeoutException) return true;
        if (e instanceof java.net.ConnectException) return true;
        if (e instanceof java.net.UnknownHostException) return true;
        if (e instanceof java.net.NoRouteToHostException) return true;
        if (e instanceof java.net.SocketException) return true;
        if (e instanceof java.io.IOException) {
            String msg = e.getMessage();
            if (msg != null) {
                msg = msg.toLowerCase();
                // Check for common network error messages
                if (msg.contains("connection refused") ||
                    msg.contains("connection reset") ||
                    msg.contains("network is unreachable") ||
                    msg.contains("connection timed out") ||
                    msg.contains("no route to host") ||
                    msg.contains("connection aborted") ||
                    msg.contains("broken pipe")) {
                    return true;
                }
            }
        }

        // Check cause chain for network errors
        Throwable cause = e.getCause();
        if (cause instanceof Exception) {
            return isNetworkError((Exception) cause);
        }

        return false;
    }
    
    private boolean isLocalTester(String ip) {
        if ("localhost".equalsIgnoreCase(ip) || "127.0.0.1".equals(ip)) {
            taskLogger.info("Tester " + ip + " identified as LOCAL (localhost/127.0.0.1)");
            return true;
        }
        
        try {
            String localHost = InetAddress.getLocalHost().getHostAddress();
            boolean isLocal = ip.equals(localHost);
            return isLocal;
        } catch (Exception e) {
            taskLogger.warning("Failed to determine local host address for comparison with " + ip + ": " + e.getMessage());
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
    
    /**
     * Sends finalize requests to all testers that received tests for a requestId.
     * This signals the testers to flush and close the request journal.
     *
     * @param requestId The request ID to finalize
     * @param testersUsed Set of tester IPs (host:port format) that received tests
     */
    private void sendFinalizeRequests(String requestId, Set<String> testersUsed) {
        taskLogger.info("Sending finalize requests for requestId: " + requestId + " to " + testersUsed.size() + " tester(s)");
        
        for (String testerIp : testersUsed) {
            try {
                // Parse host and port from testerIp (supports "host:port" format)
                String host = testerIp;
                int port = config.getTesterPort();
                
                if (testerIp.contains(":")) {
                    String[] parts = testerIp.split(":");
                    host = parts[0];
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        taskLogger.warning("Invalid port in testerIp: " + testerIp);
                    }
                }
                
                // Send finalize request
                URL url = new URL("http://" + host + ":" + port + "/finalize-request");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000); // 10 second timeout for finalize
                
                JSONObject requestBody = new JSONObject()
                    .put("requestId", requestId);
                
                try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream())) {
                    writer.write(requestBody.toString());
                }
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    taskLogger.info("Finalize request sent successfully to " + testerIp + " for requestId: " + requestId);
                } else {
                    taskLogger.warning("Finalize request to " + testerIp + " returned HTTP " + responseCode + " for requestId: " + requestId);
                }
                
            } catch (Exception e) {
                taskLogger.log(Level.WARNING, "Failed to send finalize request to " + testerIp + " for requestId: " + requestId, e);
            }
        }
    }
    
    private void sendCallback(String callbackUrl, long durationMs) {
        try {
            // Include original requestId so the report server saves under the correct request directory
            String requestIdForCallback = RequestContext.getRequestId();
            if (requestIdForCallback == null || requestIdForCallback.trim().isEmpty()) {
                requestIdForCallback = request.optString("requestId", taskId);
            }

            // Format execution time in user-friendly format
            long durationSeconds = durationMs / 1000;
            String executionTime;
            if (durationSeconds < 60) {
                executionTime = durationSeconds + "s";
            } else if (durationSeconds < 3600) {
                long minutes = durationSeconds / 60;
                long seconds = durationSeconds % 60;
                executionTime = minutes + "m " + seconds + "s";
            } else {
                long hours = durationSeconds / 3600;
                long minutes = (durationSeconds % 3600) / 60;
                executionTime = hours + "h " + minutes + "m";
            }

            JSONObject response = new JSONObject()
                .put("requestId", requestIdForCallback)
                .put("taskId", taskId)
                .put("results", new JSONArray(results))
                .put("baselineCommit", this.baselineCommit)
                .put("executionTime", executionTime)
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
            // Include original requestId so the report server saves under the correct request directory
            String requestIdForCallback = RequestContext.getRequestId();
            if (requestIdForCallback == null || requestIdForCallback.trim().isEmpty()) {
                requestIdForCallback = request.optString("requestId", taskId);
            }

            JSONObject response = new JSONObject()
                .put("requestId", requestIdForCallback)
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
        
        try {
            boolean deleted = path.delete();
            
            // If deletion failed and path still exists, try privileged deletion
            if (!deleted && path.exists()) {
                String pathStr = path.getAbsolutePath();
                // Validate path to prevent command injection
                if (pathStr.contains(";") || pathStr.contains("|") || pathStr.contains("&") || pathStr.contains("`")) {
                    taskLogger.warning("Refusing to delete path with suspicious characters: " + pathStr);
                    return;
                }
                
                ProcessBuilder pb = new ProcessBuilder("sudo", "-n", "rm", "-rf", pathStr);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                int exitCode = p.waitFor();
                
                if (exitCode == 0) {
                    taskLogger.fine("Privileged deletion succeeded for: " + pathStr);
                } else {
                    taskLogger.warning("Privileged deletion failed (exit=" + exitCode + ") for: " + pathStr);
                }
            }
        } catch (Exception e) {
            taskLogger.warning("Failed deletion for " + path.getAbsolutePath() + ": " + e.getMessage());
        }
    }
    
    private void cleanBuildCache(int maxSize) {
        if (buildCache.size() > maxSize) {
            // Evict oldest entries without deleting files on disk.
            int toRemove = buildCache.size() - maxSize;
            Iterator<Map.Entry<String, String>> iter = buildCache.entrySet().iterator();
            while (iter.hasNext() && toRemove > 0) {
                iter.next();
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

    /**
     * Returns a summarized progress object for real-time monitoring.
     * Includes current phase, commit, test, and overall completion percentage.
     */
    public JSONObject getProgressSummary() {
        JSONObject summary = new JSONObject();
        summary.put("phase", currentPhase);
        summary.put("currentCommit", currentCommit);
        summary.put("currentTest", currentTest);
        summary.put("buildsCompleted", buildsCompleted.get());
        summary.put("buildsTotal", buildsTotal);
        summary.put("testsCompleted", testsCompleted.get());
        summary.put("testsTotal", testsTotal);

        int totalTasks = buildsTotal + testsTotal;
        int completedTasks = buildsCompleted.get() + testsCompleted.get();
        
        int percent = 0;
        if (totalTasks > 0) {
            percent = (int) (100.0 * completedTasks / totalTasks);
        }

        // Rule: clamp to 99% unless the task is completely done (callback finished)
        if (percent >= 100 && !"done".equals(currentPhase)) {
            percent = 99;
        }
        
        summary.put("percent", percent);
        summary.put("totalTasks", totalTasks);
        summary.put("completedTasks", completedTasks);
        
        return summary;
    }
    
    /**
     * Create a build log entry for cached builds to maintain consistent log structure
     */
    private void createCachedBuildLog(String commit, String packagePath, String cacheType) {
        try {
            String requestId = RequestContext.getRequestId();
            if (requestId != null && config.isRequestGroupingEnabled()) {
                String buildsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "builds");
                String buildLogFile = String.format("build_%s.log", commit.substring(0, 7));
                Path logFile = Paths.get(buildsDir, buildLogFile);
                
                // Create build log content for cached build
                StringBuilder logContent = new StringBuilder();
                logContent.append("Build retrieved from ").append(cacheType).append("\n");
                logContent.append("Commit: ").append(commit).append("\n");
                logContent.append("Package: ").append(packagePath).append("\n");
                logContent.append("Timestamp: ").append(new java.util.Date()).append("\n");
                logContent.append("Status: CACHED (no rebuild required)\n");
                
                if (new File(packagePath).exists()) {
                    logContent.append("Package size: ").append(new File(packagePath).length()).append(" bytes\n");
                }
                
                Files.write(logFile, logContent.toString().getBytes("UTF-8"));
                taskLogger.info("Created build log for cached build: " + logFile.toString());
            }
        } catch (Exception e) {
            taskLogger.warning("Failed to create cached build log: " + e.getMessage());
        }
    }
    
    /**
     * Parse multipart response data
     */
    private MultipartHelper.MultipartRequest parseMultipartResponse(byte[] data, String boundary) throws IOException {
        MultipartHelper.MultipartRequest request = new MultipartHelper.MultipartRequest();
        String boundaryDelimiter = "--" + boundary;
        
        // Convert to string for easier parsing (assuming ISO-8859-1 for binary safety)
        String dataStr = new String(data, "ISO-8859-1");
        String[] parts = dataStr.split(boundaryDelimiter);
        
        for (String part : parts) {
            if (part.isEmpty() || part.equals("--\r\n") || part.equals("--")) {
                continue;
            }
            
            // Find the double CRLF that separates headers from content
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd == -1) {
                continue;
            }
            
            String headers = part.substring(0, headerEnd);
            String contentStr = part.substring(headerEnd + 4);
            
            // Remove trailing CRLF
            if (contentStr.endsWith("\r\n")) {
                contentStr = contentStr.substring(0, contentStr.length() - 2);
            }
            
            // Parse headers
            String fieldName = null;
            String fileName = null;
            
            String[] headerLines = headers.split("\r\n");
            for (String header : headerLines) {
                if (header.toLowerCase().startsWith("content-disposition:")) {
                    // Parse Content-Disposition header
                    String[] dispositionParts = header.split(";");
                    for (String disPart : dispositionParts) {
                        disPart = disPart.trim();
                        if (disPart.startsWith("name=")) {
                            fieldName = extractQuotedValue(disPart.substring(5));
                        } else if (disPart.startsWith("filename=")) {
                            fileName = extractQuotedValue(disPart.substring(9));
                        }
                    }
                }
            }
            
            if (fieldName != null) {
                if (fileName != null) {
                    // It's a file
                    request.addFile(fieldName, fileName, contentStr.getBytes("ISO-8859-1"));
                } else {
                    // It's a regular field
                    request.addField(fieldName, contentStr);
                }
            }
        }
        
        return request;
    }
    
    /**
     * Find local log file by searching the same request directory structure
     */
    private Path findLocalLogFile(String requestId, String logFileName) {
        try {
            if (requestId != null && config.isRequestGroupingEnabled()) {
                // First try the current request's tests directory
                String testsDir = RequestLogManager.getInstance().createRequestSubdir(requestId, "tests");
                Path localLogFile = Paths.get(testsDir, logFileName);
                if (Files.exists(localLogFile)) {
                    return localLogFile;
                }
                
                // If not found, search across all request directories (most recent first)
                String logBaseDir = System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log/requests";
                Path requestsDir = Paths.get(logBaseDir);
                if (Files.exists(requestsDir) && Files.isDirectory(requestsDir)) {
                    try (java.util.stream.Stream<Path> requestDirs = Files.list(requestsDir)) {
                        List<Path> sortedDirs = requestDirs.filter(Files::isDirectory)
                            .sorted((a, b) -> {
                                try {
                                    return Long.compare(Files.getLastModifiedTime(b).toMillis(), 
                                                      Files.getLastModifiedTime(a).toMillis());
                                } catch (IOException e) {
                                    return b.getFileName().toString().compareTo(a.getFileName().toString());
                                }
                            })
                            .collect(Collectors.toList());
                        
                        for (Path requestDir : sortedDirs) {
                            Path testsSubDir = requestDir.resolve("tests");
                            if (Files.exists(testsSubDir) && Files.isDirectory(testsSubDir)) {
                                Path logFile = testsSubDir.resolve(logFileName);
                                if (Files.exists(logFile)) {
                                    return logFile;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            taskLogger.warning("Error searching for local log file " + logFileName + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Fetch log content from remote tester
     */
    private String fetchLogContentFromRemoteTester(String host, int port, String logFileName) throws Exception {
        String url = "http://" + host + ":" + port + "/log/" + logFileName;
        int connectTimeout = config.getLogFetchConnectTimeoutSeconds();
        int readTimeout = config.getLogFetchReadTimeoutSeconds();
        taskLogger.info(String.format("Fetching log from: %s (connect timeout: %ds, read timeout: %ds)", 
            url, connectTimeout, readTimeout));
        
        HttpURLConnection conn = null;
        try {
            URL logUrl = new URL(url);
            conn = (HttpURLConnection) logUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getLogFetchConnectTimeoutSeconds() * 1000); // Convert to milliseconds
            conn.setReadTimeout(config.getLogFetchReadTimeoutSeconds() * 1000); // Convert to milliseconds
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                }
                return content.toString();
            } else if (responseCode == 404) {
                taskLogger.warning("Log file not found on remote tester: " + logFileName);
                return null;
            } else {
                taskLogger.warning("Failed to fetch log from remote tester. HTTP " + responseCode + " for: " + url);
                return null;
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * Extract value from quoted string
     */
    private String extractQuotedValue(String value) {
        value = value.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Legacy distribution: shared work queue pulled by all tester threads.
     */
    private void distributeTestsLegacy(Map<String, String> builtPackages, JSONArray tests,
                                       Map<String, Integer> workerCapacities, Map<String, AtomicInteger> dispatchCounts,
                                       String buildType, String testRequestId, int totalTestExecutions,
                                       Set<String> testersUsed) {
        ConcurrentLinkedQueue<TestJob> pendingJobs = new ConcurrentLinkedQueue<>();
        AtomicInteger globalTestIndex = new AtomicInteger(0);

        // Build job queue
        for (Map.Entry<String, String> entry : builtPackages.entrySet()) {
            String commit = entry.getKey();
            String buildPackage = entry.getValue();
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
                String raw = tests.getString(i);
                String testPath = raw.startsWith("shell") ? raw : ("shell/" + raw.replaceFirst("^/+", ""));
                pendingJobs.offer(new TestJob(commit, buildPackage, testPath));
            }
        }

        // Create thread pool with total capacity
        int totalSlots = Math.max(1, workerCapacities.values().stream().mapToInt(Integer::intValue).sum());
        ExecutorService testPool = Executors.newFixedThreadPool(totalSlots);
        List<Future<Void>> slotFutures = new ArrayList<>();

        // Create worker threads
        for (String worker : workerCapacities.keySet()) {
            int capacity = workerCapacities.get(worker);
            for (int slot = 0; slot < capacity; slot++) {
                final String finalWorker = worker;
                final int slotIndex = slot;
                slotFutures.add(testPool.submit(() -> {
                    while (true) {
                        TestJob job = pendingJobs.poll();
                        if (job == null) break;

                        int testNumber = globalTestIndex.incrementAndGet();
                        dispatchCounts.get(finalWorker).incrementAndGet();
                        taskLogger.info(String.format(
                            "Dispatching test %d/%d: %s (commit %s) → worker %s (slot %d)",
                            testNumber, totalTestExecutions, job.testPath,
                            job.commit.substring(0, Math.min(7, job.commit.length())),
                            finalWorker, slotIndex));

                        try {
                            if (testRequestId != null) {
                                RequestContext.setRequestId(testRequestId);
                            }
                            // Track that this tester received a test for this requestId
                            testersUsed.add(finalWorker);
                            this.currentCommit = job.commit;
                            this.currentTest = job.testPath;
                            JSONObject testResult = runTest(job.commit, job.buildPackage, job.testPath,
                                finalWorker, this.baselineCommit, buildType);
                            results.add(testResult);
                        } catch (Exception e) {
                            taskLogger.log(Level.WARNING, "Test execution threw", e);
                            results.add(new JSONObject()
                                .put("commit", job.commit)
                                .put("test", job.testPath)
                                .put("status", "error")
                                .put("message", e.getMessage()));
                        } finally {
                            testsCompleted.incrementAndGet();
                            RequestContext.clear();
                        }
                    }
                    return null;
                }));
            }
        }

        // Wait for completion
        for (Future<Void> f : slotFutures) {
            try {
                f.get();
            } catch (Exception e) {
                taskLogger.log(Level.WARNING, "Test dispatcher error", e);
            }
        }
        testPool.shutdown();

        // Log utilization summary
        taskLogger.info("Dynamic tester utilization summary:");
        for (Map.Entry<String, AtomicInteger> entry : dispatchCounts.entrySet()) {
            String worker = entry.getKey();
            taskLogger.info(String.format("  Worker %s (%s) handled %d tests (capacity=%d)",
                worker,
                isLocalTester(worker) ? "local" : "remote",
                entry.getValue().get(),
                workerCapacities.get(worker)));
        }
    }

    /**
     * Smart scheduling distribution: uses scheduler for intelligent test placement.
     */
    private void distributeTestsWithSmartScheduling(Map<String, String> builtPackages, JSONArray tests,
                                                     List<String> workerIps, String buildType, String testRequestId,
                                                     Map<String, Integer> workerCapacities, Map<String, Integer> workerPeakCaps,
                                                     Set<String> testersUsed) {
        taskLogger.info("[Smart Scheduling] Initializing scheduler...");

        // Load test profiles for heavy test classification from Tester's WAL system
        // The WAL system exports latest.json.gz every 5 minutes with all test statistics
        java.nio.file.Path walStatsPath = config.getTesterProfilesDir().resolve("latest.json.gz");
        taskLogger.info("[Smart Scheduling] Loading profiles from WAL stats: " + walStatsPath);
        
        TestProfileLoader profileLoader = new TestProfileLoader(walStatsPath);
        Map<String, TestProfile> testProfiles = profileLoader.load();
        taskLogger.info("[Smart Scheduling] Loaded " + testProfiles.size() + " test profiles");
        taskLogger.info("[Smart Scheduling] " + HeavyProfiler.summarize(testProfiles));

        // Compute elephant threshold from profiles (P75 of durations, min 60s)
        long elephantThresholdMs = ElephantThresholdCalculator.computeFromProfiles(
            testProfiles.values(), 
            config.getElephantMinMs()
        );
        taskLogger.info("[Smart Scheduling] Elephant threshold: " + elephantThresholdMs + "ms");

        // NOTE: Demand inflation is DISABLED - it kills throughput by artificially inflating
        // resource predictions and causing hasResourceHeadroom() to reject test placements.
        // Heavy classification is used ONLY for queue ordering and soft scoring preference.

        // Initialize scheduler components
        List<String> schedulerNodes = normalizeTesterNodes(workerIps);
        Map<String, AtomicInteger> nodeInflight = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> nodeInflightElephants = new ConcurrentHashMap<>();
        final long miceThreshold = config.getSchedulingMiceThresholdMs();

        ScoreFunction.NodeLoadProvider loadProvider = nodeId -> {
            String workerKey = extractWorkerKey(nodeId);
            AtomicInteger currentTotal = nodeInflight.get(workerKey);
            if (currentTotal == null) {
                return 0.0;
            }
            int capacity = workerCapacities.getOrDefault(workerKey, config.getMaxConcurrentTests());
            if (capacity <= 0) {
                capacity = 1;
            }

            // Base load penalty
            double ratio = currentTotal.get() / (double) capacity;
            double loadWeight = 0.2; // favor spreading assignments when nodes are busy
            double basePenalty = loadWeight * ratio;

            // Additional penalty for elephant-heavy nodes (prevents pile-up)
            AtomicInteger currentElephants = nodeInflightElephants.get(workerKey);
            int elephantCount = (currentElephants != null) ? currentElephants.get() : 0;
            double elephantRatio = elephantCount / (double) capacity;
            double elephantPenaltyWeight = config.getSchedulingElephantLoadPenalty();
            double elephantPenalty = elephantPenaltyWeight * elephantRatio;

            return basePenalty + elephantPenalty;
        };

        NodeDirectory nodeDirectory = new NodeDirectory(
            schedulerNodes,
            config.getSchedulingNodePollIntervalSeconds(),
            config.getSchedulingNodeStaleThresholdSeconds(),
            config
        );
        nodeDirectory.start();
        // Seed snapshots before offering thousands of tests
        nodeDirectory.pollNow();
        if (nodeDirectory.getHealthyNodes().isEmpty()) {
            try {
                Thread.sleep(Math.max(1000, config.getSchedulingNodePollIntervalSeconds() * 1000));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        ScoreFunction scoreFunction = new ScoreFunction(
            config.getSchedulingWeightPressure(),
            config.getSchedulingWeightDuration(),
            config.getSchedulingWeightImageCache(),
            config.getSchedulingWeightPackageCache(),
            config.getSchedulingWeightAgeBoost(),
            config.getSchedulingWeightHeavy(),  // w6: heavy test penalty
            config.getSchedulingWeightIo(),
            config.getSchedulingWeightCpu(),
            config.getSchedulingWeightMem(),
            config.getSchedulingWeightNet(),
            loadProvider
        );

        // Use computed elephant threshold for queue separation
        ReadyQueue readyQueue = new ReadyQueue(elephantThresholdMs);
        SchedulerService scheduler = new SchedulerService(nodeDirectory, scoreFunction, readyQueue, config.getSchedulingElephantWeight(), config);

        // Normalize tests once for scoring and instance creation
        List<String> normalizedTests = new ArrayList<>();
        for (int i = 0; i < tests.length(); i++) {
            String raw = tests.getString(i);
            String testPath = raw.startsWith("shell") ? raw : ("shell/" + raw.replaceFirst("^/+", ""));
            normalizedTests.add(testPath);
        }

        // Build test instances
        List<TestInstance> testInstances = new ArrayList<>();
        for (Map.Entry<String, String> entry : builtPackages.entrySet()) {
            String commit = entry.getKey();
            String buildPackage = entry.getValue();

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

            Map<String, ScorePrediction> scorePredictions = Collections.emptyMap();
            if (!schedulerNodes.isEmpty()) {
                String scorerNode = schedulerNodes.get(0);
                scorePredictions = fetchScorePredictions(scorerNode, commit,
                    baselineCommit != null ? baselineCommit : "unknown", normalizedTests);
            }

            for (String testPath : normalizedTests) {
                ScorePrediction pred = scorePredictions.get(testPath);

                // Create test instance with default predictions (actual prediction would query /score endpoint)
                TestInstance.Builder tib = TestInstance.builder()
                    .testKey(testPath)
                    .commit(commit)
                    .baseline(this.baselineCommit != null ? this.baselineCommit : "unknown")
                    .buildPackage(buildPackage);

                if (pred != null) {
                    if (pred.tpredMs > 0) {
                        tib.predictedDurationMs(pred.tpredMs);
                    }
                    if (pred.cpuPct >= 0) {
                        tib.predictedCpuPct(pred.cpuPct);
                    }
                    if (pred.memMb >= 0) {
                        tib.predictedMemMb(pred.memMb);
                    }

                    // Prefer separate I/O read/write values if available (from score endpoint)
                    // Fall back to combined ioMbPerSec if separate values not available
                    boolean hasSeparateIoValues = pred.ioReadMbPerSec >= 0 && pred.ioWriteMbPerSec >= 0;
                    if (hasSeparateIoValues) {
                        tib.predictedIoReadMbPerSec(pred.ioReadMbPerSec);
                        tib.predictedIoWriteMbPerSec(pred.ioWriteMbPerSec);
                    } else if (pred.ioMbPerSec >= 0) {
                        // Fallback: use combined value (Builder will auto-split 50/50)
                        tib.predictedIoMbPerSec(pred.ioMbPerSec);
                    }

                    // IOPS predictions can be disabled via config (use_iops_predictions=false)
                    // Disabled by default until node capacity is increased or predictions are calibrated
                    if (config.useIopsPredictions() && pred.iops > 0) {
                        tib.predictedIops(pred.iops);
                    }
                    if (pred.netMbPerSec >= 0) {
                        tib.predictedNetMbPerSec(pred.netMbPerSec);
                    }
                    if (pred.confidence >= 0) {
                        tib.confidence(pred.confidence);
                    }
                }

                // Apply heavy test classification from profile (for queue ordering and soft scoring only)
                // NOTE: We do NOT inflate demands - that kills throughput by making hasResourceHeadroom()
                // think nodes are more loaded than they are. Heavy classification is used only for:
                // 1. Queue ordering: HEAVY/EXTREME tests go to elephant queue (start early)
                // 2. Soft scoring: small nudge to spread heavy tests across nodes when possible
                TestProfile profile = TestProfileLoader.getOrDefault(testProfiles, testPath);
                tib.fromProfile(profile);

                TestInstance instance = tib.build();
                testInstances.add(instance);
            }
        }

        // Log how many tests are classified as heavy (HEAVY or EXTREME)
        int heavyCandidates = 0;
        int extremeCandidates = 0;
        for (TestInstance ti : testInstances) {
            if (ti.isHeavy()) {
                heavyCandidates++;
                if (ti.isExtreme()) {
                    extremeCandidates++;
                }
            }
        }
        final AtomicInteger heavyAssigned = new AtomicInteger(0);
        taskLogger.info(String.format(
                "[Smart Scheduling] Heavy tests: %d HEAVY + %d EXTREME = %d/%d total (threshold=%dms)",
                heavyCandidates - extremeCandidates,
                extremeCandidates,
                heavyCandidates,
                testInstances.size(),
                elephantThresholdMs));

        taskLogger.info("[Smart Scheduling] Offering " + testInstances.size() + " tests to scheduler");
        scheduler.offer(testInstances);

        // Poll scheduler and submit tests
        int totalHeavy = workerCapacities.values().stream().mapToInt(Integer::intValue).sum();
        int totalPeak = workerPeakCaps.values().stream().mapToInt(Integer::intValue).sum();
        if (totalPeak <= 0) {
            totalPeak = Math.max(1, workerIps.size());
        }
        if (totalHeavy <= 0) {
            totalHeavy = Math.max(1, workerIps.size());
        }

        ExecutorService testExecutor = Executors.newFixedThreadPool(totalPeak);
        Semaphore capacitySemaphore = new Semaphore(totalHeavy);
        final AtomicInteger currentTotalPermits = new AtomicInteger(totalHeavy);
        Map<String, Boolean> workerSawHeavy = new ConcurrentHashMap<>();
        List<Future<?>> inflightTests = Collections.synchronizedList(new ArrayList<>());

        int assignedCount = 0;
        int noEligibleCount = 0;
        try {
            while (scheduler.hasPending()) {
                boolean heavyBacklog = heavyAssigned.get() < heavyCandidates;
                // Dynamically ramp permits only after heavies have been observed and drained
                int desiredTotal = 0;
                for (String worker : workerIps) {
                    NodeSnapshot snap = nodeDirectory.getSnapshot(worker.contains(":") ? worker : worker + ":" + config.getTesterPort());
                    int peakCap = workerPeakCaps.getOrDefault(worker, workerCapacities.getOrDefault(worker, 1));
                    int currentCap = workerCapacities.getOrDefault(worker, 1);
                    if (snap == null) {
                        desiredTotal += currentCap;
                        continue;
                    }
                    if (snap.getHeavyRunning() > 0) {
                        workerSawHeavy.put(worker, true);
                    }
                    boolean heaviesRunning = snap.getHeavyRunning() > 0 || snap.getActiveLimit() <= snap.getMaxWhileHeavy();
                    boolean canRamp = Boolean.TRUE.equals(workerSawHeavy.get(worker))
                            && !heaviesRunning
                            && snap.getRunningTests() >= snap.getMaxWhileHeavy()
                            && !heavyBacklog;
                    int advertised = snap.getActiveLimit();
                    int desired = canRamp ? Math.max(currentCap, Math.min(peakCap, advertised)) : currentCap;
                    workerCapacities.put(worker, desired);
                    desiredTotal += desired;
                }
                if (desiredTotal > currentTotalPermits.get()) {
                    int delta = desiredTotal - currentTotalPermits.get();
                    capacitySemaphore.release(delta);
                    currentTotalPermits.addAndGet(delta);
                    taskLogger.fine(String.format("Increasing total permits to %d (delta=%d)", desiredTotal, delta));
                }

                try {
                    capacitySemaphore.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

                Optional<Assignment> assignment = scheduler.assignNext();
                if (!assignment.isPresent()) {
                    capacitySemaphore.release();
                    noEligibleCount++;
                    if (noEligibleCount > 10) {
                        taskLogger.warning("[Smart Scheduling] No eligible nodes after 10 attempts, waiting...");
                        try {
                            Thread.sleep(5000);  // Wait 5s for nodes to become available
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        noEligibleCount = 0;
                    }
                    continue;
                }

                noEligibleCount = 0;
                Assignment a = assignment.get();
                if (isHeavyCandidate(a.getTest())) {
                    heavyAssigned.incrementAndGet();
                }
                assignedCount++;
                taskLogger.info(String.format("[Smart Scheduling] Assignment %d/%d: %s",
                    assignedCount, testInstances.size(), a));

                String nodeId = a.getTargetNodeId();
                String workerIp = nodeId.contains(":") ? nodeId.substring(0, nodeId.indexOf(":")) : nodeId;
                AtomicInteger inflightCounter = nodeInflight.computeIfAbsent(workerIp, k -> new AtomicInteger());
                inflightCounter.incrementAndGet();

                // Track elephant separately for load balancing
                boolean isElephant = a.getTest().getPredictedDurationMs() > miceThreshold;
                AtomicInteger elephantCounter = null;
                if (isElephant) {
                    elephantCounter = nodeInflightElephants.computeIfAbsent(workerIp, k -> new AtomicInteger());
                    elephantCounter.incrementAndGet();
                }

                final AtomicInteger finalElephantCounter = elephantCounter;
                Future<?> future = testExecutor.submit(() -> {
                    try {
                        if (testRequestId != null) {
                            RequestContext.setRequestId(testRequestId);
                        }
                        // Track that this tester received a test for this requestId
                        testersUsed.add(workerIp);
                        this.currentCommit = a.getCommit();
                        this.currentTest = a.getTestKey();
                        JSONObject testResult = runTest(a.getCommit(), a.getTest().getBuildPackage(),
                            a.getTestKey(), workerIp, this.baselineCommit, buildType, a.getTest());
                        results.add(testResult);
                    } catch (Exception e) {
                        taskLogger.log(Level.WARNING, "[Smart Scheduling] Test execution error", e);
                        results.add(new JSONObject()
                            .put("commit", a.getCommit())
                            .put("test", a.getTestKey())
                            .put("status", "error")
                            .put("message", e.getMessage()));
                    } finally {
                        testsCompleted.incrementAndGet();
                        RequestContext.clear();
                        inflightCounter.decrementAndGet();
                        if (finalElephantCounter != null) {
                            finalElephantCounter.decrementAndGet();
                        }
                        capacitySemaphore.release();
                    }
                });
                inflightTests.add(future);
            }
        } finally {
            testExecutor.shutdown();
            for (Future<?> future : inflightTests) {
                try {
                    future.get();
                } catch (Exception e) {
                    taskLogger.log(Level.WARNING, "[Smart Scheduling] Test task interrupted", e);
                }
            }
            nodeDirectory.stop();
        }

        taskLogger.info("[Smart Scheduling] Completed: assigned " + assignedCount + " tests");
    }

    private static final class TestJob {
        private final String commit;
        private final String buildPackage;
        private final String testPath;

        private TestJob(String commit, String buildPackage, String testPath) {
            this.commit = commit;
            this.buildPackage = buildPackage;
            this.testPath = testPath;
        }
    }

    /**
     * Ensures smart scheduling polls the actual tester port even if the request
     * only supplied bare IPs (legacy behavior).
     */
    private List<String> normalizeTesterNodes(List<String> workerIps) {
        return workerIps.stream()
            .map(ip -> ip.contains(":") ? ip : ip + ":" + config.getTesterPort())
            .distinct()
            .collect(Collectors.toList());
    }

    private String extractWorkerKey(String nodeId) {
        if (nodeId == null) {
            return "";
        }
        int idx = nodeId.indexOf(':');
        return idx >= 0 ? nodeId.substring(0, idx) : nodeId;
    }

    /**
     * Lightweight builder-side heuristic to estimate whether a test will be treated
     * as heavy by the tester (duration + IO). Used only for pre-dispatch logging.
     */
    private boolean isHeavyCandidate(TestInstance test) {
        long duration = test.getPredictedDurationMs();
        double io = test.getPredictedIoReadMbPerSec() + test.getPredictedIoWriteMbPerSec();
        if (io <= 0) {
            io = test.getPredictedIoMbPerSec();
        }
        return duration >= config.getSchedulingMiceThresholdMs()
                && io >= config.getSchedulingIoHeavyThreshold();
    }

    /**
     * Calls tester /score endpoint to fetch predictions, including IOPS, for the given tests.
     * Returns an empty map on failure to keep scheduling resilient.
     */
    private Map<String, ScorePrediction> fetchScorePredictions(String scorerNode, String commit,
                                                               String baseline, List<String> tests) {
        Map<String, ScorePrediction> out = new HashMap<>();
        if (scorerNode == null || scorerNode.isEmpty() || tests == null || tests.isEmpty()) {
            return out;
        }
        try {
            URL url = new URL("http://" + scorerNode + "/score");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            JSONObject req = new JSONObject();
            req.put("commit", commit);
            req.put("baseline", baseline);
            JSONArray arr = new JSONArray();
            for (String t : tests) {
                arr.put(t);
            }
            req.put("tests", arr);

            byte[] payload = req.toString().getBytes("UTF-8");
            conn.getOutputStream().write(payload);
            conn.getOutputStream().flush();

            int code = conn.getResponseCode();
            if (code != 200) {
                taskLogger.fine("[Smart Scheduling] /score returned non-200 (" + code + ") from " + scorerNode);
                return out;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            JSONObject resp = new JSONObject(sb.toString());
            if (!resp.has("predictions")) {
                return out;
            }
            JSONObject preds = resp.getJSONObject("predictions");
            for (String key : preds.keySet()) {
                JSONObject p = preds.getJSONObject(key);
                ScorePrediction sp = new ScorePrediction();
                sp.tpredMs = (long) p.optDouble("tpred_ms", -1.0);
                sp.cpuPct = p.optDouble("cpu_pct", -1.0);
                sp.memMb = p.optDouble("mem_mb", -1.0);
                sp.ioMbPerSec = p.optDouble("io_mb_s", -1.0);

                // Parse separate I/O read/write values from score endpoint (in bytes/sec)
                // Convert from bytes/sec to MB/s for consistency with other metrics
                if (p.has("ioReadBytesPerSec")) {
                    sp.ioReadMbPerSec = p.getDouble("ioReadBytesPerSec") / (1024.0 * 1024.0);
                } else {
                    sp.ioReadMbPerSec = -1.0;
                }
                if (p.has("ioWriteBytesPerSec")) {
                    sp.ioWriteMbPerSec = p.getDouble("ioWriteBytesPerSec") / (1024.0 * 1024.0);
                } else {
                    sp.ioWriteMbPerSec = -1.0;
                }

                sp.iops = p.optDouble("iops", -1.0);
                sp.netMbPerSec = p.optDouble("net_mb_s", -1.0);
                sp.confidence = p.optDouble("confidence", -1.0);
                out.put(key, sp);
            }
        } catch (Exception e) {
            taskLogger.fine("[Smart Scheduling] Failed to fetch /score predictions from " + scorerNode + ": " + e.getMessage());
        }
        return out;
    }

    private static final class ScorePrediction {
        long tpredMs;
        double cpuPct;
        double memMb;
        double ioMbPerSec;
        double ioReadMbPerSec;
        double ioWriteMbPerSec;
        double iops;
        double netMbPerSec;
        double confidence;
    }
}
