/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.*;
import com.sun.net.httpserver.*;
import org.json.JSONObject;
import org.json.JSONArray;
import com.navercorp.cubridqa.builder.logging.*;
import com.navercorp.cubridqa.builder.docker.DockerUtils;
import com.navercorp.cubridqa.builder.tester.TestRequestValidator;
import com.navercorp.cubridqa.builder.tester.TestRequestValidator.ValidationResult;

/**
 * Builder - Receives build requests and builds CUBRID at specified commits
 * 
 * This service receives HTTP requests to build CUBRID at multiple commits
 * concurrently using Docker containers. Built packages are then sent to the
 * Tester service for testing.
 */
public class Builder {
    private static final Logger logger = Logger.getLogger(Builder.class.getName());
    // The builder runs build requests sequentially: at most 1 BuilderTask at a time.
    // (We still keep an explicit queue for additional requests.)
    private static final int MAX_CONCURRENT_REQUESTS = 1;
    // Custom script attachments safety limits (JSON base64 payloads)
    private static final int MAX_CUSTOM_ATTACHMENTS = 20;
    private static final long MAX_CUSTOM_ATTACHMENTS_BYTES = 5L * 1024 * 1024; // 5MB decoded total
    
    private final BuilderConfig config;
    private final HttpServer server;
    private final ExecutorService buildExecutor;
    private final Map<String, BuilderTask> activeTasks;
    private final BlockingQueue<QueuedBuildRequest> pendingRequests;
    private final DockerBuildManager dockerManager;
    private final LogRotationManager logRotationManager;
    // Guards capacity checks + queue/task transitions to prevent over-admitting tasks under concurrency
    private final Object taskDispatchLock = new Object();
    
    public Builder(BuilderConfig config) throws IOException {
        this.config = config;
        this.buildExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS);
        this.activeTasks = new ConcurrentHashMap<>();
        this.pendingRequests = new LinkedBlockingQueue<>();
        this.dockerManager = new DockerBuildManager(config);
        
        // Initialize logging infrastructure
        LogConfig logConfig = new LogConfig(
            config.getMaxRequestLogs(),
            config.getMaxTarFiles(),
            System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log",
            config.isRequestGroupingEnabled()
        );
        RequestLogManager.initialize(logConfig);
        this.logRotationManager = new LogRotationManager(logConfig);
        
        // Create HTTP server
        this.server = HttpServer.create(new InetSocketAddress(config.getListenPort()), 0);
        this.server.createContext("/build", new BuildRequestHandler());
        this.server.createContext("/build-single", new SingleBuildHandler());
        this.server.createContext("/status", new StatusHandler());
        this.server.createContext("/health", new HealthCheckHandler());
        this.server.createContext("/download/build/", new BuildDownloadHandler());
        
        // Add report handler for viewing test results
        try {
            String logDir = System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log";
            com.navercorp.cubridqa.builder.report.ReportHandler reportHandler = new com.navercorp.cubridqa.builder.report.ReportHandler(logDir);
            this.server.createContext("/report", reportHandler);
            this.server.createContext("/callback", reportHandler);
            this.server.createContext("/api/log/", reportHandler);
            this.server.createContext("/api/logs/", reportHandler);
        } catch (IOException e) {
            logger.warning("Failed to initialize report handler: " + e.getMessage());
        }
        
        this.server.setExecutor(null); // creates a default executor
    }
    
    public void start() {
        // Ensure work directory exists
        File workDir = new File(config.getWorkDir());
        if (!workDir.exists()) {
            workDir.mkdirs();
        }
        
        // Ensure ccache directory structure exists if ccache is enabled
        if (config.isCcacheEnabled()) {
            try {
                File ccacheDir = new File(config.getCcacheDir());
                if (!ccacheDir.exists()) {
                    ccacheDir.mkdirs();
                }
                File ccacheLogsDir = new File(config.getCcacheDir(), "logs");
                if (!ccacheLogsDir.exists()) {
                    ccacheLogsDir.mkdirs();
                }
                File ccacheTmpDir = new File(config.getCcacheDir(), "tmp");
                if (!ccacheTmpDir.exists()) {
                    ccacheTmpDir.mkdirs();
                }
                logger.info("Ccache directory structure initialized at: " + config.getCcacheDir());
            } catch (Exception e) {
                logger.warning("Failed to create ccache directory structure: " + e.getMessage());
            }
        }

        // Initialize Docker environment if enabled
        if (config.useDocker()) {
            try {
                dockerManager.initialize();
                logger.info("Docker build environment initialized");
            } catch (Exception e) {
                logger.warning("Failed to initialize Docker: " + e.getMessage());
                logger.warning("Will use direct build method");
            }
        }
        
        server.start();
        logger.info("Builder service started on port " + config.getListenPort());
        logger.info("CUBRID source: " + config.getCubridSrcDir());
        logger.info("Work directory: " + config.getWorkDir());
        logger.info("Max concurrent build requests: " + MAX_CONCURRENT_REQUESTS);
        logger.info("Docker enabled: " + config.useDocker());
    }
    
    public void stop() {
        server.stop(0);
        buildExecutor.shutdown();
        try {
            if (!buildExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                buildExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            buildExecutor.shutdownNow();
        }
        logger.info("Builder service stopped");
    }
    
    private class BuildRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                // Generate request ID for this build request
                String requestId = RequestContext.generateRequestId();
                
                // Perform cleanup of old logs and tar files
                logRotationManager.performCleanup(config.getWorkDir());
                
                // Clean up stale containers from previous runs before starting new task
                try {
                    cleanupStaleContainers();
                } catch (Exception cleanupEx) {
                    logger.warning("Container cleanup skipped due to error: " + cleanupEx.getMessage());
                }
                
                // Read request body
                String requestBody = readRequestBody(exchange);
                JSONObject request = new JSONObject(requestBody);
                
                // Add request ID to the request object
                request.put("requestId", requestId);

                // Validate request
                validateRequest(request);

                // Record the request in metadata (with secrets redacted)
                logRotationManager.recordRequest(requestId, sanitizeRequestForLog(request));
                
                // Extract parameters (commits optional when using prNumber)
                JSONArray commits = request.has("commits") ? request.getJSONArray("commits") : new JSONArray();
                JSONArray tests = request.has("tests") ? request.getJSONArray("tests") : new JSONArray();
                boolean buildOnly = request.optBoolean("buildOnly", false);
                // callbackUrl is passed through to the BuilderTask via the request JSON
                @SuppressWarnings("unused")
                String callbackUrl = request.getString("callbackUrl");
                
                // Support both workerIp (singular) and workerIps (array) for backward compatibility
                JSONArray workerIps = new JSONArray();
                if (!buildOnly) {
                    if (request.has("workerIps")) {
                        workerIps = request.getJSONArray("workerIps");
                        if (workerIps.length() == 0) {
                            throw new IllegalArgumentException("workerIps array cannot be empty");
                        }
                    } else {
                        // Backward compatibility: convert single workerIp to array
                        String workerIp = request.optString("workerIp", "localhost");
                        workerIps = new JSONArray().put(workerIp);
                    }
                }
                
                // buildType is used by BuilderTask via the request JSON
                @SuppressWarnings("unused")
                String buildType = request.optString("buildType", "debug");
                
                // Check tester reachability for all worker IPs
                if (!buildOnly) {
                    for (int i = 0; i < workerIps.length(); i++) {
                        validateTesterReachability(workerIps.getString(i));
                    }
                }
                
                // Log request with request ID
                if (buildOnly) {
                    JSONObject upload = request.getJSONObject("buildUpload");
                    String remoteDirLog = upload.optString("remoteDir", "").trim();
                    if (remoteDirLog.isEmpty()) {
                        remoteDirLog = "~";
                    }
                    if (request.has("prNumber")) {
                        logger.info(String.format("[%s] Received build-only PR request for PR #%s (upload to %s:%d%s)",
                            requestId,
                            request.get("prNumber").toString(),
                            upload.optString("host", "unknown"),
                            upload.optInt("port", 22),
                            remoteDirLog));
                    } else {
                        logger.info(String.format("[%s] Received build-only request for %d commits (upload to %s:%d%s)",
                            requestId,
                            commits.length(),
                            upload.optString("host", "unknown"),
                            upload.optInt("port", 22),
                            remoteDirLog));
                    }
                } else if (request.has("prNumber")) {
                    logger.info(String.format("[%s] Received PR build request for PR #%s and %d tests",
                        requestId, request.get("prNumber").toString(), tests.length()));
                } else {
                    logger.info(String.format("[%s] Received build request for %d commits and %d tests",
                        requestId, commits.length(), tests.length()));
                }
                
                // Create task ID (use request ID as task ID)
                String taskId = requestId;
                
                // Check if already running
                if (activeTasks.containsKey(taskId)) {
                    JSONObject response = new JSONObject()
                        .put("status", "already_running")
                        .put("taskId", taskId);
                    sendJsonResponse(exchange, 200, response);
                    return;
                }
                
                // Capacity check + queue/task admission must be atomic to prevent over-admission
                synchronized (taskDispatchLock) {
                    // Check if at capacity - queue the request
                    if (activeTasks.size() >= MAX_CONCURRENT_REQUESTS) {
                        QueuedBuildRequest queuedRequest = new QueuedBuildRequest(taskId, request);
                        pendingRequests.offer(queuedRequest);

                        // Calculate queue position (1-based)
                        int queuePosition = pendingRequests.size();

                        logger.info(String.format("[%s] Build request queued at position %d (capacity: %d/%d)",
                            requestId, queuePosition, activeTasks.size(), MAX_CONCURRENT_REQUESTS));

                        JSONObject response = new JSONObject()
                            .put("status", "queued")
                            .put("taskId", taskId)
                            .put("queuePosition", queuePosition)
                            .put("message", "Build request queued - will start when capacity available");

                        sendJsonResponse(exchange, 202, response);
                        return;
                    }

                    // Admit task
                    submitTaskLocked(taskId, request);
                }

                // Send accepted response
                JSONObject response = new JSONObject()
                    .put("status", "accepted")
                    .put("taskId", taskId)
                    .put("message", "Build request received and processing");

                sendJsonResponse(exchange, 202, response);
                
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error handling request", e);
                JSONObject error = new JSONObject()
                    .put("status", "error")
                    .put("message", e.getMessage());
                sendJsonResponse(exchange, 400, error);
            }
        }
    }

    /**
     * Submit a task while holding {@link #taskDispatchLock} to reserve capacity.
     */
    private void submitTaskLocked(String taskId, JSONObject request) {
        BuilderTask task = new BuilderTask(taskId, request, config, dockerManager);
        activeTasks.put(taskId, task);

        CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } finally {
                activeTasks.remove(taskId);
                processNextQueuedRequest();
            }
        }, buildExecutor);
    }
    
    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                // Get task ID from query parameter
                String query = exchange.getRequestURI().getQuery();
                String taskId = null;
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] pair = param.split("=");
                        if (pair.length == 2 && "taskId".equals(pair[0])) {
                            taskId = pair[1];
                            break;
                        }
                    }
                }
                
                JSONObject response = new JSONObject();
                
                if (taskId != null) {
                    BuilderTask task = activeTasks.get(taskId);
                    if (task != null) {
                        response.put("status", "running")
                                .put("taskId", taskId)
                                .put("progress", task.getProgress())
                                .put("progressSummary", task.getProgressSummary());
                    } else {
                        response.put("status", "not_found")
                                .put("taskId", taskId);
                    }
                } else {
                    // Return all active tasks
                    JSONArray tasks = new JSONArray();
                    for (Map.Entry<String, BuilderTask> entry : activeTasks.entrySet()) {
                        tasks.put(new JSONObject()
                            .put("taskId", entry.getKey())
                            .put("progress", entry.getValue().getProgress())
                            .put("progressSummary", entry.getValue().getProgressSummary()));
                    }
                    response.put("activeTasks", tasks);

                    // Also return queued requests for visibility in dashboards
                    JSONArray queuedTaskIds = new JSONArray();
                    for (QueuedBuildRequest qr : pendingRequests) {
                        queuedTaskIds.put(qr.getRequestId());
                    }
                    response.put("queuedRequests", pendingRequests.size());
                    response.put("queuedTaskIds", queuedTaskIds);
                }
                
                sendJsonResponse(exchange, 200, response);
                
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in status handler", e);
                JSONObject error = new JSONObject()
                    .put("status", "error")
                    .put("message", e.getMessage());
                sendJsonResponse(exchange, 500, error);
            }
        }
    }
    
    private class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                // Check for requestId query parameter for queue position lookup
                String query = exchange.getRequestURI().getQuery();
                String requestId = null;
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] pair = param.split("=");
                        if (pair.length == 2 && "requestId".equals(pair[0])) {
                            requestId = pair[1];
                            break;
                        }
                    }
                }
                
                JSONObject healthResponse = new JSONObject()
                    .put("status", "healthy")
                    .put("service", "Builder")
                    .put("timestamp", System.currentTimeMillis())
                    .put("activeTasks", activeTasks.size())
                    .put("queuedRequests", pendingRequests.size())
                    .put("maxConcurrentBuilds", MAX_CONCURRENT_REQUESTS)
                    .put("workDir", config.getWorkDir())
                    .put("dockerEnabled", config.useDocker());
                
                // If requestId provided, check its position in queue
                if (requestId != null) {
                    int position = 0;
                    boolean found = false;
                    
                    // Check if it's currently running
                    if (activeTasks.containsKey(requestId)) {
                        healthResponse.put("requestStatus", "running");
                        found = true;
                    } else {
                        // Check queue position
                        int pos = 1;
                        for (QueuedBuildRequest qr : pendingRequests) {
                            if (qr.getRequestId().equals(requestId)) {
                                position = pos;
                                found = true;
                                break;
                            }
                            pos++;
                        }
                        
                        if (found) {
                            healthResponse.put("requestStatus", "queued");
                            healthResponse.put("queuePosition", position);
                        } else {
                            healthResponse.put("requestStatus", "not_found");
                        }
                    }
                }
                
                sendJsonResponse(exchange, 200, healthResponse);
                
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in health check", e);
                JSONObject error = new JSONObject()
                    .put("status", "error")
                    .put("message", e.getMessage());
                sendJsonResponse(exchange, 500, error);
            }
        }
    }
    
    private class BuildDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            try {
                // Extract filename from path: /download/build/{filename}
                String path = exchange.getRequestURI().getPath();
                String prefix = "/download/build/";
                if (!path.startsWith(prefix)) {
                    sendResponse(exchange, 404, "Not found");
                    return;
                }

                String filename = path.substring(prefix.length());
                if (filename.isEmpty() || filename.contains("..")) {
                    sendResponse(exchange, 400, "Invalid filename");
                    return;
                }

                // Look for file in work directory build subdirectories
                File buildFile = findBuildFile(config.getWorkDir(), filename);
                if (buildFile == null || !buildFile.exists() || !buildFile.isFile()) {
                    sendResponse(exchange, 404, "Build package not found");
                    return;
                }

                // Send file
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                exchange.sendResponseHeaders(200, buildFile.length());

                try (OutputStream os = exchange.getResponseBody();
                     FileInputStream fis = new FileInputStream(buildFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }

                logger.info("Served build package: " + filename);

            } catch (Exception e) {
                logger.log(Level.WARNING, "Error serving build package", e);
                sendResponse(exchange, 500, "Internal server error");
            }
        }
    }

    private class SingleBuildHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            try {
                // Read request body
                String requestBody = readRequestBody(exchange);
                JSONObject request = new JSONObject(requestBody);

                // Extract parameters
                String commit = request.getString("commit");
                String buildType = request.optString("buildType", "debug");
                String baselineCommit = request.optString("baselineCommit", null);

                logger.info(String.format("Received single build request: commit=%s, type=%s", commit, buildType));

                // Create unique work directory for this build
                String workDirPath = config.getWorkDir() + "/build_" + commit.substring(0, 7) + "_" + System.currentTimeMillis();
                File workDir = new File(workDirPath);
                workDir.mkdirs();

                try {
                    // Execute build
                    String packagePath = dockerManager.buildCubrid(commit, workDir, buildType, baselineCommit);

                    if (packagePath != null && !packagePath.isEmpty()) {
                        // Build succeeded
                        logger.info(String.format("Single build succeeded: commit=%s, package=%s", commit, packagePath));

                        JSONObject response = new JSONObject()
                            .put("status", "success")
                            .put("commit", commit)
                            .put("packagePath", packagePath)
                            .put("message", "Build completed successfully");

                        sendJsonResponse(exchange, 200, response);
                    } else {
                        // Build failed
                        logger.warning(String.format("Single build failed: commit=%s", commit));

                        JSONObject response = new JSONObject()
                            .put("status", "failed")
                            .put("commit", commit)
                            .put("message", "Build failed");

                        sendJsonResponse(exchange, 200, response);
                    }

                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Error during single build", e);

                    JSONObject error = new JSONObject()
                        .put("status", "error")
                        .put("commit", commit)
                        .put("message", e.getMessage());

                    sendJsonResponse(exchange, 500, error);
                }

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error handling single build request", e);
                JSONObject error = new JSONObject()
                    .put("status", "error")
                    .put("message", e.getMessage());
                sendJsonResponse(exchange, 400, error);
            }
        }
    }
    
    /**
     * Find build file in work directory subdirectories.
     * Build files are stored in directories like build_<commit>_<id>/cubrid_<commit>.tar.gz
     */
    private File findBuildFile(String workDir, String filename) {
        File workDirectory = new File(workDir);
        if (!workDirectory.exists() || !workDirectory.isDirectory()) {
            return null;
        }
        
        // Look through all build_* subdirectories
        File[] buildDirs = workDirectory.listFiles(file -> 
            file.isDirectory() && file.getName().startsWith("build_"));
            
        if (buildDirs == null) {
            return null;
        }
        
        for (File buildDir : buildDirs) {
            File candidate = new File(buildDir, filename);
            if (candidate.exists() && candidate.isFile()) {
                return candidate;
            }
        }
        
        return null;
    }
    
    private void validateRequest(JSONObject request) throws IllegalArgumentException {
        boolean buildOnly = request.optBoolean("buildOnly", false);
        boolean hasCommits = request.has("commits") && request.getJSONArray("commits").length() > 0;
        boolean hasPrNumber = request.has("prNumber") && (
            (request.get("prNumber") instanceof Number && ((Number) request.get("prNumber")).intValue() > 0) ||
            (request.get("prNumber") instanceof String && ((String) request.get("prNumber")).trim().matches("\\d+"))
        );
        if (!hasCommits && !hasPrNumber) {
            throw new IllegalArgumentException("Request must contain non-empty 'commits' array or a valid 'prNumber'");
        }

        if (!buildOnly) {
            // Strict validation of run parameters (runMode, minRuns, maxRuns, timeBudgetMs)
            ValidationResult validation = TestRequestValidator.validate(request);
            if (!validation.isValid()) {
                throw new IllegalArgumentException("Invalid run parameters: " + String.join("; ", validation.getErrors()));
            }
            
            // Update request with normalized/defaulted values from validation
            request.put("runMode", validation.getRunMode());
            request.put("minRuns", validation.getMinRuns());
            request.put("maxRuns", validation.getMaxRuns());
            if (validation.getTimeBudgetMs() != null) {
                request.put("timeBudgetMs", validation.getTimeBudgetMs());
            }
        }

        // Check for custom shell script - if provided, tests can be empty
        boolean hasCustomScript = request.has("customShellScript") &&
                                  !request.getString("customShellScript").trim().isEmpty();

        // Validate custom attachments (only allowed when customShellScript is present)
        if (request.has("customAttachments")) {
            Object raw = request.get("customAttachments");
            if (!(raw instanceof JSONArray)) {
                throw new IllegalArgumentException("'customAttachments' must be an array");
            }
            JSONArray atts = (JSONArray) raw;
            if (atts.length() > 0 && !hasCustomScript) {
                throw new IllegalArgumentException("'customAttachments' is only supported with 'customShellScript'");
            }
            if (atts.length() > 0) {
                validateCustomAttachments(atts);
            }
        }

        if (!buildOnly) {
            if (!hasCustomScript) {
                // Standard mode - tests array is required
                if (!request.has("tests") || request.getJSONArray("tests").length() == 0) {
                    throw new IllegalArgumentException("Request must contain non-empty 'tests' array");
                }
            } else {
                // Custom script mode - tests can be empty or missing
                if (!request.has("tests")) {
                    // Create placeholder test if tests array is missing
                    request.put("tests", new JSONArray().put("custom_script_test"));
                } else if (request.getJSONArray("tests").length() == 0) {
                    // Create placeholder test if tests array is empty
                    request.put("tests", new JSONArray().put("custom_script_test"));
                }
            }

            // Validate test paths early to prevent malformed inputs (e.g., report URLs) from becoming "shell/http://..."
            if (request.has("tests")) {
                JSONArray tests = request.getJSONArray("tests");
                List<String> invalid = new ArrayList<>();
                for (int i = 0; i < tests.length(); i++) {
                    String raw = String.valueOf(tests.get(i));
                    if (raw == null) continue;
                    String t = raw.trim();
                    if (t.isEmpty()) continue;
                    if ("custom_script_test".equals(t)) continue;

                    // Enforce strict shell test paths. Reject anything not starting with "shell/".
                    if (!t.startsWith("shell/")) {
                        invalid.add(t);
                        continue;
                    }
                    if (!t.endsWith(".sh")) {
                        invalid.add(t);
                        continue;
                    }
                }
                if (!invalid.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Invalid test path(s): " + String.join(", ", invalid) +
                        ". Expected filesystem paths like shell/.../cases/... .sh."
                    );
                }
            }
        } else {
            validateBuildUpload(request);
        }
        
        // Support both workerIp (singular) and workerIps (array) for backward compatibility
        boolean hasWorkerIp = request.has("workerIp") && !request.getString("workerIp").trim().isEmpty();
        boolean hasWorkerIps = request.has("workerIps") && request.getJSONArray("workerIps").length() > 0;
        
        if (!buildOnly && !hasWorkerIp && !hasWorkerIps) {
            throw new IllegalArgumentException("Request must contain either non-empty 'workerIp' or non-empty 'workerIps' array");
        }
    }

    private void validateBuildUpload(JSONObject request) {
        if (!request.has("buildUpload")) {
            throw new IllegalArgumentException("buildUpload is required when buildOnly is true");
        }
        Object raw = request.get("buildUpload");
        if (!(raw instanceof JSONObject)) {
            throw new IllegalArgumentException("buildUpload must be an object");
        }
        JSONObject buildUpload = (JSONObject) raw;

        String host = buildUpload.optString("host", "").trim();
        String username = buildUpload.optString("username", "").trim();
        String remoteDir = buildUpload.optString("remoteDir", "").trim();
        String password = buildUpload.has("password") ? String.valueOf(buildUpload.get("password")) : "";

        if (host.isEmpty()) {
            throw new IllegalArgumentException("buildUpload.host is required");
        }
        if (username.isEmpty()) {
            throw new IllegalArgumentException("buildUpload.username is required");
        }
        if (password.isEmpty()) {
            throw new IllegalArgumentException("buildUpload.password is required");
        }
        int port = 22;
        if (buildUpload.has("port")) {
            Object portRaw = buildUpload.get("port");
            try {
                port = portRaw instanceof Number
                    ? ((Number) portRaw).intValue()
                    : Integer.parseInt(portRaw.toString().trim());
            } catch (Exception e) {
                throw new IllegalArgumentException("buildUpload.port must be a valid integer");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("buildUpload.port must be between 1 and 65535");
            }
        }

        buildUpload.put("host", host);
        buildUpload.put("username", username);
        buildUpload.put("remoteDir", remoteDir);
        buildUpload.put("port", port);
    }

    private JSONObject sanitizeRequestForLog(JSONObject request) {
        JSONObject sanitized = new JSONObject(request.toString());
        if (sanitized.has("buildUpload")) {
            Object raw = sanitized.get("buildUpload");
            if (raw instanceof JSONObject) {
                ((JSONObject) raw).put("password", "***");
            }
        }
        return sanitized;
    }

    private void validateCustomAttachments(JSONArray atts) {
        if (atts.length() > MAX_CUSTOM_ATTACHMENTS) {
            throw new IllegalArgumentException("Too many attachments: " + atts.length() + " (max " + MAX_CUSTOM_ATTACHMENTS + ")");
        }
        long totalBytes = 0L;
        for (int i = 0; i < atts.length(); i++) {
            Object o = atts.get(i);
            if (!(o instanceof JSONObject)) {
                throw new IllegalArgumentException("customAttachments[" + i + "] must be an object");
            }
            JSONObject a = (JSONObject) o;
            String targetPath = a.optString("targetPath", "").trim();
            String contentBase64 = a.optString("contentBase64", "").trim();
            if (targetPath.isEmpty()) {
                throw new IllegalArgumentException("customAttachments[" + i + "].targetPath is required");
            }
            if (contentBase64.isEmpty()) {
                throw new IllegalArgumentException("customAttachments[" + i + "].contentBase64 is required");
            }
            validateAttachmentTargetPath(targetPath, i);

            // Validate base64 + compute decoded length
            try {
                byte[] decoded = java.util.Base64.getDecoder().decode(contentBase64);
                totalBytes += decoded.length;
                if (totalBytes > MAX_CUSTOM_ATTACHMENTS_BYTES) {
                    throw new IllegalArgumentException("Total attachments size exceeds limit: " + totalBytes +
                        " bytes (max " + MAX_CUSTOM_ATTACHMENTS_BYTES + ")");
                }
            } catch (IllegalArgumentException e) {
                // Could be invalid base64 or our size limit error above
                if (e.getMessage() != null && e.getMessage().startsWith("Total attachments size")) {
                    throw e;
                }
                throw new IllegalArgumentException("customAttachments[" + i + "].contentBase64 is not valid base64");
            }
        }
    }

    private void validateAttachmentTargetPath(String targetPath, int idx) {
        String p = targetPath.trim().replace('\\', '/');
        if (p.startsWith("/")) {
            throw new IllegalArgumentException("customAttachments[" + idx + "].targetPath must be relative (no leading '/')");
        }
        if (p.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("customAttachments[" + idx + "].targetPath contains NUL byte");
        }
        // Disallow whitespace to keep shell quoting and UX predictable
        if (p.matches(".*\\s+.*")) {
            throw new IllegalArgumentException("customAttachments[" + idx + "].targetPath must not contain whitespace");
        }
        // Disallow characters that complicate safe shell embedding
        if (p.contains("'") || p.contains("\"") || p.contains("`") || p.contains("$")) {
            throw new IllegalArgumentException("customAttachments[" + idx + "].targetPath contains unsupported characters (quotes or shell metacharacters)");
        }
        // Disallow traversal segments
        String[] parts = p.split("/");
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("customAttachments[" + idx + "].targetPath must not contain '.' or '..' segments");
            }
        }
    }
    
    private void validateTesterReachability(String workerIp) throws Exception {
        // Parse host and port from workerIp (supports "host:port" format)
        String host = workerIp;
        int port = config.getTesterPort();
        
        if (workerIp.contains(":")) {
            String[] parts = workerIp.split(":");
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port in workerIp: " + workerIp);
            }
        }
        
        logger.info("Checking tester reachability at " + host + ":" + port);
        
        try {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5000);
                logger.info("Tester connectivity test passed");
                return;
            }
        } catch (Exception e) {
            String errorMsg = "Tester at " + host + ":" + port + 
                            " is not reachable: " + e.getMessage();
            logger.severe(errorMsg);
            throw new IllegalArgumentException(errorMsg, e);
        }
    }
    
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody()))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return body.toString();
        }
    }
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) 
            throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private void sendJsonResponse(HttpExchange exchange, int statusCode, JSONObject response)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, statusCode, response.toString());
    }

    // Remove containers left from prior runs. Per requirement, perform from Builder only.
    private void cleanupStaleContainers() throws IOException, InterruptedException {
        if (!DockerUtils.isDockerAvailable()) {
            logger.info("Docker not available; skipping container cleanup");
            return;
        }
        // 1) Remove tester containers kept alive from previous runs (both debug and release)
        removeByNamePrefix("tester_debug_");
        removeByNamePrefix("tester_release_");
        // 2) Prune exited containers for our known images (safe)
        removeExitedByAncestor(config.getDockerBuildImage());
        removeExitedByAncestor(config.getDockerTestImage());
    }

    private void removeByNamePrefix(String prefix) throws IOException, InterruptedException {
        String listCmd = "docker ps -aq --filter name=" + prefix;
        List<String> ids = readCommandOutput(new String[]{"bash", "-lc", listCmd});
        if (!ids.isEmpty()) {
            logger.info("Removing stale tester containers by name prefix '" + prefix + "': " + String.join(",", ids));
            List<String> cmd = new ArrayList<>();
            cmd.add("bash"); cmd.add("-lc");
            cmd.add("docker rm -f " + String.join(" ", ids));
            new ProcessBuilder(cmd).inheritIO().start().waitFor();
        }
    }

    private void removeExitedByAncestor(String image) throws IOException, InterruptedException {
        if (image == null || image.trim().isEmpty()) return;
        String listCmd = "docker ps -aq --filter status=exited --filter ancestor=" + image;
        List<String> ids = readCommandOutput(new String[]{"bash", "-lc", listCmd});
        if (!ids.isEmpty()) {
            logger.info("Pruning exited containers for image '" + image + "': " + String.join(",", ids));
            List<String> cmd = new ArrayList<>();
            cmd.add("bash"); cmd.add("-lc");
            cmd.add("docker rm " + String.join(" ", ids));
            new ProcessBuilder(cmd).inheritIO().start().waitFor();
        }
    }

    private List<String> readCommandOutput(String[] cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line; while ((line = r.readLine()) != null) { if (!line.trim().isEmpty()) lines.add(line.trim()); }
        }
        p.waitFor();
        return lines;
    }
    
    /**
     * Process the next queued build request if capacity is available.
     * Called automatically when a build task completes.
     */
    private void processNextQueuedRequest() {
        // Drain queue while capacity is available; admission is lock-protected to prevent races.
        while (true) {
            QueuedBuildRequest queuedRequest;
            synchronized (taskDispatchLock) {
                if (activeTasks.size() >= MAX_CONCURRENT_REQUESTS || pendingRequests.isEmpty()) {
                    return;
                }
                queuedRequest = pendingRequests.poll();
                if (queuedRequest == null) {
                    return;
                }

                String taskId = queuedRequest.getRequestId();
                JSONObject request = queuedRequest.getRequest();

                logger.info(String.format("[%s] Starting queued build request (waited %dms, remaining in queue: %d)",
                    taskId, queuedRequest.getWaitTimeMs(), pendingRequests.size()));

                try {
                    submitTaskLocked(taskId, request);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, String.format("[%s] Error processing queued request", taskId), e);
                    activeTasks.remove(taskId);
                    // Continue loop to try next queued request
                }
            }
        }
    }
    
    public static void main(String[] args) {
        try {
            // Setup logging
            LogManager.getLogManager().reset();
            Logger rootLogger = Logger.getLogger("");
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.INFO);
            consoleHandler.setFormatter(new SimpleFormatter());
            rootLogger.addHandler(consoleHandler);
            
            // System log directory
            String systemLogDir = System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log/system";
            new File(systemLogDir).mkdirs();
            
            // Clean up any existing lock files and numbered log files
            File logDir = new File(systemLogDir);
            File[] oldLogFiles = logDir.listFiles((dir, name) -> 
                name.matches("builder\\.log\\.(\\d+|lck)"));
            if (oldLogFiles != null) {
                for (File oldFile : oldLogFiles) {
                    oldFile.delete();
                }
            }
            
            // Use StreamHandler with FileOutputStream for direct control over file append
            // This avoids FileHandler's automatic rotation behavior
            String logFilePath = systemLogDir + "/builder.log";
            FileOutputStream fos = new FileOutputStream(logFilePath, true); // true = append mode
            StreamHandler streamHandler = new StreamHandler(fos, new SimpleFormatter());
            streamHandler.setLevel(Level.ALL);
            rootLogger.addHandler(streamHandler);

            rootLogger.setLevel(Level.INFO);
            
            // Load configuration
            String configFile = "conf/builder.conf";
            if (args.length > 0) {
                configFile = args[0];
            }
            
            BuilderConfig config = new BuilderConfig(configFile);
            
            // Create and start builder
            Builder builder = new Builder(config);
            builder.start();
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                builder.stop();
                
                // Flush and close the stream handler
                streamHandler.flush();
                streamHandler.close();
                
                // Clean up any lock files that might have been created
                File lockFile = new File(systemLogDir + "/builder.log.lck");
                if (lockFile.exists()) {
                    lockFile.delete();
                }
            }));
            
            // Keep running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start Builder", e);
            System.exit(1);
        }
    }
}
