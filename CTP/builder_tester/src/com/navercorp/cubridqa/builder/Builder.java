/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import com.sun.net.httpserver.*;
import org.json.JSONObject;
import org.json.JSONArray;
import com.navercorp.cubridqa.builder.logging.*;

/**
 * Builder - Receives build requests and builds CUBRID at specified commits
 * 
 * This service receives HTTP requests to build CUBRID at multiple commits
 * concurrently using Docker containers. Built packages are then sent to the
 * Tester service for testing.
 */
public class Builder {
    private static final Logger logger = Logger.getLogger(Builder.class.getName());
    
    private final BuilderConfig config;
    private final HttpServer server;
    private final ExecutorService buildExecutor;
    private final Map<String, BuilderTask> activeTasks;
    private final DockerBuildManager dockerManager;
    private final LogRotationManager logRotationManager;
    
    public Builder(BuilderConfig config) throws IOException {
        this.config = config;
        this.buildExecutor = Executors.newFixedThreadPool(config.getMaxConcurrentBuilds());
        this.activeTasks = new ConcurrentHashMap<>();
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
        this.server.createContext("/status", new StatusHandler());
        this.server.createContext("/health", new HealthCheckHandler());
        this.server.createContext("/download/build/", new BuildDownloadHandler());
        
        // Add report handler for viewing test results
        try {
            String logDir = System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log";
            this.server.createContext("/report", new com.navercorp.cubridqa.builder.report.ReportHandler(logDir));
            this.server.createContext("/callback", new com.navercorp.cubridqa.builder.report.ReportHandler(logDir));
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
        logger.info("Max concurrent builds: " + config.getMaxConcurrentBuilds());
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
                
                // Record the request in metadata
                logRotationManager.recordRequest(requestId, request);
                
                // Validate request
                validateRequest(request);
                
                // Extract parameters
                JSONArray commits = request.getJSONArray("commits");
                JSONArray tests = request.getJSONArray("tests");
                String callbackUrl = request.getString("callbackUrl");
                
                // Support both workerIp (singular) and workerIps (array) for backward compatibility
                JSONArray workerIps;
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
                
                String buildType = request.optString("buildType", "debug");
                
                // Check tester reachability for all worker IPs
                for (int i = 0; i < workerIps.length(); i++) {
                    validateTesterReachability(workerIps.getString(i));
                }
                
                // Log request with request ID
                logger.info(String.format("[%s] Received build request for %d commits and %d tests",
                    requestId, commits.length(), tests.length()));
                
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
                
                // Create and submit task
                BuilderTask task = new BuilderTask(taskId, request, config, dockerManager);
                activeTasks.put(taskId, task);
                
                CompletableFuture.runAsync(() -> {
                    try {
                        task.run();
                    } finally {
                        activeTasks.remove(taskId);
                    }
                }, buildExecutor);
                
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
                                .put("progress", task.getProgress());
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
                            .put("progress", entry.getValue().getProgress()));
                    }
                    response.put("activeTasks", tasks);
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
                JSONObject healthResponse = new JSONObject()
                    .put("status", "healthy")
                    .put("service", "Builder")
                    .put("timestamp", System.currentTimeMillis())
                    .put("activeTasks", activeTasks.size())
                    .put("workDir", config.getWorkDir())
                    .put("dockerEnabled", config.useDocker())
                    .put("maxConcurrentBuilds", config.getMaxConcurrentBuilds());
                
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
                
                // Look for file in work directory
                File buildFile = new File(config.getWorkDir(), filename);
                if (!buildFile.exists() || !buildFile.isFile()) {
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
    
    private void validateRequest(JSONObject request) throws IllegalArgumentException {
        if (!request.has("commits") || request.getJSONArray("commits").length() == 0) {
            throw new IllegalArgumentException("Request must contain non-empty 'commits' array");
        }
        if (!request.has("tests") || request.getJSONArray("tests").length() == 0) {
            throw new IllegalArgumentException("Request must contain non-empty 'tests' array");
        }
        if (!request.has("workerIp") || request.getString("workerIp").trim().isEmpty()) {
            throw new IllegalArgumentException("Request must contain non-empty 'workerIp'");
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
        // 1) Remove tester debug containers kept alive from previous runs
        removeByNamePrefix("tester_debug_");
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
